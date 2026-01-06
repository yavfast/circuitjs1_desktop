# AI Skills Memory Protocol (Local DB-first): Graph + Vectors + Ollama

Дата: 2025-12-30

Цей документ описує нову архітектуру протоколу AI Skills, орієнтовану на симуляцію довготривалої памʼяті (LTM).

Ключова зміна від попереднього підходу: **основна інформація зберігається у локальній БД**, а не у файлових логах/маніфестах. Попередній протокол враховується як “досвід дизайну” (pipeline, поняття skills/episodes/metrics), але **на нього не спираємось як на джерело істини**.

Обмеження/вимоги:
- Працює **локально**, offline.
- **Без складних налаштувань зовнішнього ПО** (без Neo4j як обовʼязкової залежності).
- Векторний пошук через **просту локальну векторну БД**: ChromaDB.
- Ембеддинги формуються локально через **Ollama**.
- Потрібні скрипти для: ініціалізації, запису (ingest/save), пошуку (search), експорту метаданих для onboarding.

Документ AI-орієнтований: інваріанти, компактні структури, псевдокод.

---

## 0. Терміни (коротко)

- **Skill**: запис “навички” у БД (опис, входи/виходи, тригери, дозволи, приклади, повʼязані концепти).
- **Episode**: атомарний запис події (запит користувача, план, інструментальні виклики, результат).
- **Temporal KG**: граф знань, у якому ребра/факти мають часову валідність і вагу (салієнс).
- **Episodic Memory**: “що сталося” (епізоди виконання).
- **Semantic Memory**: “що ми знаємо” (узагальнені твердження/патерни, виведені з епізодів).
- **Consolidation**: перетворення епізодів у семантичні звʼязки/статистики.

---

## 1. Аналіз попереднього протоколу (корисні ідеї, які переносимо)

Попередній протокол (YAML-first, file-based) мав сильні сторони, які варто зберегти на рівні процесів, але без привʼязки до файлів як SoT.

### 1.1 Що переносимо

- **Ясні контракти**: skill має inputs/outputs, дозволи, приклади.
- **Детермінований цикл**: search → analysis → compose → apply → validate → evaluate → save → improve.
- **Аудит і відтворюваність**: “що було зроблено і чому”.

### 1.2 Чого уникаємо

1) **Погана масштабованість контексту**
    - історія/журнали ростуть; агент змушений читати/семплити шматки → контекст брудний і дорогий.

2) **Слабка звʼязність знань**
     - звʼязки “яка навичка працює з яким патерном/файлом/версією/помилкою” лишаються неявними.

3) **Темпоральність “вручну”**
     - важко відповісти “що перестало працювати після зміни X” без повного аналізу логів.

4) **Самовдосконалення не форситься**
     - протокол дозволяє `skill-improve`, але не змушує завжди акумулювати знання/епізоди та будувати індексацію.

Висновок: файлова SoT-модель породжує “контекстні дампи”, а не LTM. У новому протоколі SoT — **локальна БД**.

---

## 2. Цілі протоколу (вимоги)

### 2.1 Продуктові цілі

- **LTM симуляція**: агент “памʼятає” успішні/провальні стратегії, повʼязує їх з контекстом і часом.
- **Кращий відбір навичок**: швидко знаходити найбільш релевантні навички і композиції.
- **Пояснюваність**: “чому обрано цю навичку” (через графові докази).

### 2.2 Інженерні цілі

- **DB-first**: основні дані (skills/episodes/edges/metrics) у БД.
- **Компактність**: агент отримує “context pack”, а не дамп історії.
- **Локальність і простота**: без вимоги до важких зовнішніх сервісів.

### 2.3 Протокольні вимоги (запит користувача)

- **AI-орієнтований протокол**: строгі кроки, короткі структури даних.
- **Псевдокод**: опис процесів процедурно.
- **Самовдосконалення**: цикл improvement з валідацією.
- **Форсувати використання і накопичення**: кожна задача породжує епізод, оновлює граф і метрики.

---

## 4. Операційні принципи (Оновлення 2026-01-01)

Для забезпечення зручності та стійкості роботи агентів впроваджено наступні принципи:

### 4.1 Пропорційність (Proportionality)
- **Великі задачі**: Повний цикл (Search -> Plan -> Execute -> Validate -> Full Episode).
- **Дрібні задачі** (виправлення помилок, однорядкові зміни):
    - Допускається спрощений формат епізоду.
    - Допускається **batching** (групування кількох дрібних змін в один епізод в кінці сесії).
    - Мета: не створювати бюрократичний бар'єр для швидких фіксів.

### 4.2 Стійкість інструментарію (Tooling Resilience)
- Якщо скрипти `ai_mem.sh` або база даних недоступні (помилки, таймаути):
    - **Fallback**: Агент повинен записати текстовий звіт (мінімальний епізод) у файл `ai_memory/memory.md`.
    - Робота не повинна блокуватися через збій інструментів пам'яті.

### 4.3 Стратегія контексту (Context Strategy)
Агент не повинен "вигадувати" контекст навмання. Алгоритм визначення `ctx_path`:
1.  **Active Context**: Перевірити `ai_memory/active_context.md` (поле `current_focus` або `context_path`).
2.  **LLM Classification**: Якщо не вказано, запитати у моделі: "Classify this task into a domain/subdomain/topic path".
3.  **Default**: Використати загальний контекст (наприклад, `general/misc`), якщо класифікація неможлива.

### 4.4 Vector-First Retrieval
- Для пошуку за змістом (особливо мультимовного або неточного) пріоритет надається **векторному пошуку** (`--seed-mode chroma`).
- SQL-пошук (`--seed-mode sql`) використовується для детермінованих тестів або пошуку за точними ідентифікаторами.

---

## 5. Артефакти (що зберігаємо і де)

### 3.1 DB-first (локально) — основне

**Основне сховище:**
- **SQLite (graph + metadata + temporal)** — таблиці вузлів/ребер/епізодів/метрик.
- **ChromaDB (vectors)** — вектори для вузлів/ребер/епізодів і швидкий KNN.

Ці дві частини утворюють LTM:
- SQLite відповідає за **структуру** (граф, часову валідність, звʼязки, метрики, розгортання “сусідства”).
- ChromaDB відповідає за **семантичний пошук** (similarity) та первинне “seed” retrieval.

### 3.2 Файли в репозиторії — лише для onboarding (опційно)

Основні дані НЕ зберігаються у репозиторії. Але для швидкого onboarding і дебагу дозволяється тримати **легкий snapshot метаданих**, який генерується з БД:

- `ai_skills/onboarding_snapshot.yaml` (або `.md`) — коротка “карта” системи: топ-навіки, концепти, типові флоу, часті failure modes, як запускати скрипти.

Цей snapshot:
- SHOULD бути маленьким (≈ 1–3 сторінки тексту)
- MUST не бути джерелом істини
- MAY комітитись у git

### 3.3 Інваріанти протоколу (MUST)

1) **БД — source of truth** для skills/episodes/graph/metrics.
2) Кожна виконана задача → принаймні 1 `Episode` записаний у БД.
3) Вибір skills/композицій → має бути підкріплений доказами (підбір із retrieval + витягнуті звʼязки/метрики) або причиною “даних нема”.
4) Будь-яке самовдосконалення → проходить validate/evaluate gate.
5) Система працює локально: SQLite файл + ChromaDB persist directory.

---

## 4. Модель даних (Graph + Vectors)

Нижче — рекомендована мінімальна схема (достатня для LTM).

### 4.1 Вузли (Nodes) — логічна модель

- `Skill` { `skill_id`, `name`, `description`, `io_schema`, `permissions`, `examples[]` }
- `SkillRevision` { `skill_id`, `rev`, `created_at`, `source` }
- `Concept` { `key`, `aliases[]` }
- `Context` { `context_id`, `summary`, `level`, `parent_id` }  # ієрархічний контекст (тема/домен/scope)
- `Task` { `task_id`, `prompt_hash`, `timestamp` }
- `Episode` { `episode_id`, `timestamp`, `summary`, `trace_hash` }
- `Outcome` { `status` = success|failure|partial, `severity`, `notes` }
- `Artifact` { `path`, `kind` }  # будь-який артефакт: файл/URL/команда/обʼєкт
- `FailureMode` { `signature`, `category` }
- `Fix` { `kind`, `diff_hash` }
- `Test` { `name`, `result`, `duration_ms` }
- `Environment` { `os`, `runtime`, `tooling` }

### 4.2 Ребра (Edges) — логічна модель

Усі ребра мають базові поля:
- `valid_from`, `valid_to` (може бути null)
- `weight` (салієнс/довіра)
- `evidence_refs[]` (посилання на епізоди/тести)

Ключові ребра:
- `Skill -[HAS_CAPABILITY]-> Concept`
- `Task -[HAS_EPISODE]-> Episode`
- `Episode -[USED_SKILL]-> Skill`
- `Episode -[TOUCHED_ARTIFACT]-> Artifact`
- `Episode -[RESULTED_IN]-> Outcome`
- `Outcome -[HAS_FAILURE_MODE]-> FailureMode` (якщо failure)
- `FailureMode -[MITIGATED_BY]-> Fix`
- `Episode -[RAN_TEST]-> Test`
- `Skill -[COMPOSED_WITH]-> Skill` (learned)
- `Skill -[SIMILAR_TO]-> Skill` (learned)

**Контекстні ребра (hierarchical context):**
- `Episode -[IN_CONTEXT]-> Context`  # епізод належить до контексту
- `Skill -[APPLICABLE_IN]-> Context`  # навичка застосовна в контексті
- `Artifact -[BELONGS_TO]-> Context`  # артефакт належить до контексту
- `Context -[CHILD_OF]-> Context`  # ієрархія: дочірній → батьківський

### 4.2.1 Ієрархічна структура контексту (Context Hierarchy)

Контекст може мати ієрархічну (деревоподібну) структуру для організації знань за тематикою.

**Приклад ієрархії:**
```
програмування
├── java
│   ├── паттерни
│   │   ├── singleton
│   │   └── factory
│   ├── spring
│   └── concurrency
├── python
│   ├── async
│   └── data-science
└── загальне
    ├── алгоритми
    └── архітектура
```

**Зберігання ієрархії:**
- Кожен `Context` має `parent_id` → посилання на батьківський контекст (або NULL для кореня).
- `level` — глибина в дереві (0 = корінь, 1 = перший рівень, ...).
- Ребро `Context -[CHILD_OF]-> Context` дублює зв'язок для зручності графового traversal.

**Переваги ієрархії:**
1. **Scoped retrieval**: запит "java" повертає всі епізоди/навички з контекстів `java`, `java/паттерни`, `java/spring`, тощо.
2. **Узагальнення**: якщо факт валідний у `java/паттерни/singleton`, він також валідний у `java` і `програмування` (inheritance up).
3. **Спеціалізація**: факт у `програмування` може бути перевизначений/уточнений у `java` (override down).

**Алгоритм побудови ієрархії при ingest:**
```text
function BUILD_CONTEXT_HIERARCHY(context_text):
    # 1) Витягти ключові теми з тексту (NLP або евристики)
    topics = EXTRACT_TOPICS(context_text)
    # topics: ["програмування", "java", "паттерни"]

    # 2) Побудувати/знайти ланцюжок у дереві
    parent_id = NULL
    level = 0
    for topic in topics:
        ctx = FIND_OR_CREATE_CONTEXT(topic, parent_id, level)
        parent_id = ctx.context_id
        level += 1

    # 3) Повернути найглибший (leaf) контекст
    return parent_id
```

**Алгоритм scoped retrieval:**
```text
function GET_ALL_DESCENDANTS(context_id):
    # Рекурсивно зібрати всі дочірні контексти
    children = SQL_SELECT WHERE parent_id = context_id
    result = [context_id]
    for child in children:
        result += GET_ALL_DESCENDANTS(child.context_id)
    return result

function SCOPED_SEARCH(query, context_id):
    scope = GET_ALL_DESCENDANTS(context_id)
    # Знайти всі епізоди/навички, що IN_CONTEXT будь-якого з scope
    return SEARCH_IN_CONTEXTS(query, scope)
```

**MVP-спрощення:**
- Ієрархія будується з тексту контексту через розділювач `/` або `:` (наприклад, `"програмування/java/паттерни"`).
- Якщо передано простий текст — створюється один плоский контекст (без ієрархії).

### 4.3 Фізичне зберігання (SQLite + Chroma)

**SQLite (рекомендована мінімальна схема):**

- `nodes(node_id TEXT PRIMARY KEY, node_type TEXT, key TEXT, name TEXT, summary TEXT, meta_json TEXT, created_at TEXT, updated_at TEXT)`
- `edges(edge_id TEXT PRIMARY KEY, src_id TEXT, dst_id TEXT, rel_type TEXT, fact TEXT, weight REAL, valid_from TEXT, valid_to TEXT, last_seen_at TEXT, evidence_json TEXT)`
- `episodes(episode_id TEXT PRIMARY KEY, timestamp TEXT, task_text TEXT, summary TEXT, outcome_status TEXT, trace_json TEXT)`
- `episode_links(episode_id TEXT, node_id TEXT, edge_id TEXT)`

**ChromaDB (колекції):**
- `chroma_nodes` (documents = node.summary/name)
- `chroma_edges` (documents = edge.fact)
- `chroma_episodes` (documents = episode.summary)

У `metadata` Chroma зберігаємо:
- `kind: node|edge|episode`
- `id: node_id/edge_id/episode_id`
- `type: node_type/rel_type`

### 4.3 “Симуляція довготривалої памʼяті” (LTM mechanics)

Механіка LTM виводиться через:

1) **Салієнс (weight)**
     - збільшується при success, зменшується при failure;
     - з часом підлягає згасанню (decay), щоб старі факти не домінували.

2) **Консолідація**
    - епізоди агрегуються в “семантичні” ребра типу `Skill -[EFFECTIVE_FOR]-> Concept` (або `Skill -[EFFECTIVE_FOR]-> Artifact`) з метриками.

3) **Забування (forgetting)**
     - не видаляє дані одразу, а зменшує ваги/пріоритети та переводить епізоди у cold storage.

Псевдоформула (одна з простих):

$$w_{t} = w_{0} \cdot e^{-\lambda \Delta t} + \alpha \cdot success - \beta \cdot failure$$

---

## 5. Протокол виконання задачі (обовʼязковий цикл)

Це “форсуючий” цикл: агент не має пропускати кроки без причини.

### 5.1 Вхід/вихід протоколу

**Вхід:** user_request, repo_state, active_context

**Вихід:**
- виконані зміни / відповідь;
- оновлений `Episode` у БД;
- (опційно) improvement proposal.

### 5.2 Core loop (псевдокод)

```text
procedure HANDLE_REQUEST(user_request):
    ctx = LOAD_ACTIVE_CONTEXT()
    env = CAPTURE_ENV()

    task = NEW_TASK(user_request)
    episode = NEW_EPISODE(task, ctx, env)

    # MUST: always consult skills + memory
    candidates = SKILL_SEARCH(user_request, ctx)
    analysis = SKILL_ANALYSIS(user_request, candidates, ctx)

    plan = SKILL_COMPOSE(user_request, analysis, ctx)
    ASSERT(plan != null)

    result = SKILL_APPLY(plan, ctx)
    validation = SKILL_VALIDATE(result, ctx)
    evaluation = SKILL_EVALUATE(result, validation, ctx)

    MEMORY_SAVE(task, episode, plan, result, validation, evaluation)

    if SHOULD_IMPROVE(evaluation):
         proposal = SKILL_IMPROVE(task, episode, plan, evaluation)
         # MUST: validate improvements before adopting
         proposal_validation = SKILL_VALIDATE(proposal, ctx)
         proposal_eval = SKILL_EVALUATE(proposal, proposal_validation, ctx)
         if proposal_eval.is_net_positive:
                 ADOPT_IMPROVEMENT(proposal)
         else:
                 REJECT_IMPROVEMENT(proposal)

    UPDATE_ACTIVE_CONTEXT(task, result, evaluation)
    RETURN FINAL_ANSWER(result)
```

### 5.3 Де саме використовується граф

```text
function SKILL_SEARCH(user_request, ctx):
    db_candidates = MEMORY_QUERY_SKILLS(user_request, ctx)

    # MUST: merge + rank with temporal evidence
    ranked = RANK_SKILLS(db_candidates,
         features=[semantic_similarity,
                             recent_success_rate,
                             failure_penalty,
                             recency_decay,
                            touched_artifacts_overlap,
                             composition_synergy])

    return TOP_K(ranked, k=5)
```

```text
function MEMORY_QUERY_SKILLS(user_request, ctx):
    concepts = EXTRACT_CONCEPTS(user_request, ctx)
    # Expand neighborhood: concepts -> skills -> episodes -> outcomes
    subgraph = TRAVERSE(concepts,
         depth=2,
            edge_types=[HAS_CAPABILITY, USED_SKILL, RESULTED_IN, TOUCHED_ARTIFACT])
    return SUMMARIZE_SKILL_EVIDENCE(subgraph)
```

Примітка: тут `TRAVERSE` працює по SQLite-ребрах. “Seed” для traversal беремо з Chroma (vector search), а потім розгортаємо граф у SQLite.

---

## 6. “Форсування” накопичення знань (обовʼязкові правила)

Це ключова відмінність v2: протокол не “дозволяє”, а “вимагає”.

### 6.1 Правила MUST

1) **No-episode = no-done**
     - якщо агент не може записати Episode у БД → він має:
         - пояснити це;
         - записати мінімальний fallback у `ai_skills/onboarding_snapshot.yaml` (як тимчасовий журнал) або в `ai_memory/memory.md`.

2) **Selection must be justified**
     - кожен вибір навички/композиції повинен мати:
         - або графові докази (посилання на епізоди/метрики),
         - або причину “даних нема” + стратегія “збір даних” (A/B, safe default).

3) **Evaluate and consolidate**
     - після `skill-apply` завжди виконується `skill-evaluate` і викликається `CONSOLIDATE_EPISODE`.

### 6.2 Консолідація (псевдокод)

```text
procedure CONSOLIDATE_EPISODE(episode):
    facts = EXTRACT_FACTS(episode)
    UPDATE_TEMPORAL_EDGES(facts)
    UPDATE_SKILL_METRICS(episode.used_skills, episode.outcome)

    if episode.outcome == failure:
         failure_mode = CLASSIFY_FAILURE(episode)
         LINK(episode, HAS_FAILURE_MODE, failure_mode)

    # optional: derive semantic memory
        if ENOUGH_EVIDENCE(episode.used_skills, concept_or_artifact):
            UPDATE_EDGE(skill, EFFECTIVE_FOR, concept_or_artifact,
                     weight=AGGREGATE_SUCCESS_RATE(...),
                     valid_from=..., valid_to=null)
```

---

## 7. Самовдосконалення (controlled improvement)

Самовдосконалення — це не “авто-редагування”, а керований процес.

### 7.1 Типи покращень

- **Retrieval improvement**: краще ранжування/фільтрація навичок.
- **Skill content improvement**: уточнення записів skill у БД (опис, приклади, тести, ресурси).
- **Composition improvement**: нові шаблони DAG або мапінги між схемами.

### 7.2 Guardrails

- Не змінювати записи skill у БД без підстави:
    - посилання на епізоди/тести,
    - мінімальна зміна (diff на рівні полів),
    - validate/evaluate.

### 7.3 Improvement loop (псевдокод)

```text
function SHOULD_IMPROVE(evaluation):
    return (
        evaluation.failure_repeated OR
        evaluation.latency_regression OR
        evaluation.low_confidence_selection OR
        evaluation.user_feedback_negative
    )

procedure SKILL_IMPROVE(task, episode, plan, evaluation):
    hypothesis = DERIVE_HYPOTHESIS(evaluation, episode)
    candidate_change = PROPOSE_MINIMAL_CHANGE(hypothesis)
    attach_evidence(candidate_change, episode, evaluation)
    return candidate_change
```

---

## 8. Архітектура реалізації (локально, без важких залежностей)

### 8.1 Компоненти

1) **Memory DB (SQLite)**
    - зберігає граф (nodes/edges), епізоди, метрики, темпоральну валідність.

2) **Vector DB (ChromaDB)**
    - зберігає embeddings для nodes/edges/episodes;
    - забезпечує KNN пошук.

3) **Embedding Provider (Ollama)**
    - локальні embeddings через HTTP API.

4) **Memory Scripts (CLI)**
    - `init` / `ingest` / `search` / `export-onboarding`.

5) **Agent Integration Layer**
    - агент викликає скрипти (або бібліотеку) для читання/запису памʼяті.

### 8.2 Мінімальні API (контракт)

Це логічний API, який реалізується скриптами/бібліотекою локально:

```text
tool mem.init() -> {db_path, chroma_path}
tool mem.upsert_episode(episode_json) -> {episode_id}
tool mem.query_skills(query, ctx) -> [{skill_id, score, evidence_summary, last_success_at, known_failures[]}]
tool mem.get_skill_profile(skill_id) -> {metrics, top_concepts, failure_modes}
tool mem.suggest_composition(query, candidates) -> {dag, rationale}
tool mem.export_onboarding_snapshot() -> {path}
```

### 8.3 Вимоги до нової БД (MUST)

**MUST:**
- Локально: SQLite файл + ChromaDB persist folder.
- Векторний пошук через ChromaDB.
- Embeddings через локальну Ollama.
- Мінімальна інсталяція: `python3` + `pip install chromadb requests`.

**MUST NOT:**
- Вимагати Neo4j/Graphiti/іншу важку інфраструктуру як обовʼязкову умову.

### 8.4 Функціонал “Graphiti-like” без Graphiti (детально)

Graphiti корисний як еталон: темпоральний граф + інкрементальний ingest + hybrid search + conflict resolution.
Оскільки ми обираємо ChromaDB (а Graphiti не інтегрується з нею напряму), у протоколі визначається власний “двошаровий” механізм:

- **Vector layer (ChromaDB)**: швидко знаходить кандидати (nodes/edges/episodes).
- **Graph layer (SQLite)**: дає traversal, темпоральність, метрики, консистентні update/invalidations.

#### 8.4.1 Ingestion pipeline (episode → graph)

```text
procedure ADD_EPISODE(raw_event):
    e = NORMALIZE_EVENT(raw_event)
    eid = UPSERT_EPISODE_ROW(e)

    # 1) Extract entities/relations
    facts = EXTRACT_FACTS(e)
    # facts: [{src_key, src_type, rel_type, dst_key, dst_type, fact_text, confidence}]

    # 2) Entity resolution (dedup)
    for each fact in facts:
         src_id = RESOLVE_OR_CREATE_NODE(fact.src_type, fact.src_key, fact.src_text)
         dst_id = RESOLVE_OR_CREATE_NODE(fact.dst_type, fact.dst_key, fact.dst_text)

         edge_id = RESOLVE_EDGE(src_id, fact.rel_type, dst_id)

         # 3) Temporal update + conflict handling
         HANDLE_EDGE_UPDATE(edge_id, fact, eid)

    # 4) Update vector indexes
    INDEX_EPISODE_VECTORS(eid, e.summary)
    for updated nodes/edges: INDEX_NODE/EDGE_VECTORS(...)

    # 5) Metrics & consolidation
    UPDATE_METRICS_FROM_EPISODE(eid)
    maybe CONSOLIDATE(eid)

    return eid
```

#### 8.4.2 Extract facts (дві стратегії)

**Стратегія A (рекомендовано для MVP): structured facts**
- Агент/інструменти під час виконання задачі формують структурний “event JSON” з полями:
    - `used_skills[]`, `touched_artifacts[]`, `outcome`, `errors[]`, `tests[]`, `free_text_summary`.
- `EXTRACT_FACTS` перетворює це у факти детерміновано (без LLM).

**Стратегія B (optional): LLM extraction через Ollama**
- Для неструктурованих логів/текстів: Ollama витягує entities/relations у JSON.
- MUST мати safety: low-confidence факти записуються з низькою вагою і не інвалідовують існуючі ребра.

#### 8.4.3 Entity resolution (як “dedup” без граф-БД)

```text
function RESOLVE_OR_CREATE_NODE(type, key, text):
    # 1) exact match by (type,key)
    node = SQL_SELECT nodes where node_type=type and key=key
    if node exists: return node_id

    # 2) semantic match via Chroma (optional)
    candidates = CHROMA_QUERY(collection=chroma_nodes, query=text, filter={type:type}, top_k=5)
    if BEST(candidates).score >= THRESHOLD:
         return candidates[0].metadata.id

    # 3) create new
    node_id = NEW_ID()
    SQL_INSERT nodes(node_id,type,key,name/summary,meta)
    CHROMA_ADD(chroma_nodes, id=node_id, doc=text, meta={kind:"node", id:node_id, type:type})
    return node_id
```

#### 8.4.4 Conflict handling + temporal validity

Ідея: ребра мають `valid_from/valid_to`. Новий факт може:
- підтвердити існуюче ребро (оновити `last_seen_at`, підняти вагу)
- суперечити (закрити старе ребро `valid_to=now`, створити нове з іншим `fact`)

MVP-правило суперечності (просте):
- для відносин типу `RESULTED_IN`, `HAS_FAILURE_MODE`, `MITIGATED_BY` — допускаємо багато ребер, але з різними `episode_id` у evidence.
- для “унікальних” відносин (наприклад, `Skill HAS_CAPABILITY Concept`) — якщо новий факт конфліктний і confidence високий, інвалідовуємо попередній.

#### 8.4.5 Алгоритм графоутворення (детально, DB-first)

Це “ядро” системи: перетворити один Episode у набір вузлів/ребер так, щоб граф був:
- інкрементально оновлюваний;
- дедуплікований (одні й ті самі сутності не плодяться);
- темпоральний (valid_from/valid_to);
- придатний до гібридного пошуку (вектори + traversal).

**Вхід:** структурний episode JSON (MVP) або сирий текст (optional)

**Вихід:**
- `episodes` row
- `nodes` upserts
- `edges` upserts (з evidence)
- Chroma upserts для nodes/edges/episodes

##### Крок 0 — Нормалізація (canonicalization)

```text
function NORMALIZE_EVENT(e):
    e.timestamp = e.timestamp or now()
    e.used_skills = UNIQUE(NORMALIZE_ID(x) for x in e.used_skills)
    e.touched_artifacts = UNIQUE(NORMALIZE_PATH(x) for x in e.touched_artifacts)
    e.outcome.status = e.outcome.status or "partial"
    e.errors = UNIQUE(NORMALIZE_TEXT(x) for x in e.errors)
    return e
```

##### Крок 1 — Створення/оновлення базового вузла Episode

```text
episode_node = UPSERT_NODE(type="episode", key=episode_id,
                                                     name="Episode " + episode_id,
                                                     summary=e.summary)
LINK(episode_id, episode_node)
```

##### Крок 2 — Факти з structured fields (детерміновано)

MVP-факти, які завжди генеруються:

- `Episode -[USED_SKILL]-> Skill`
- `Episode -[TOUCHED_ARTIFACT]-> Artifact`
- `Episode -[RESULTED_IN]-> Outcome`
- `Outcome -[HAS_FAILURE_MODE]-> FailureMode` (якщо є errors або status=failure)

```text
facts = []

for s in e.used_skills:
    facts += {src:("episode",episode_id), rel:"USED_SKILL", dst:("skill",s), fact_text:"used " + s, confidence:1.0}

for a in e.touched_artifacts:
    facts += {src:("episode",episode_id), rel:"TOUCHED_ARTIFACT", dst:("artifact",a), fact_text:"touched " + a, confidence:1.0}

facts += {src:("episode",episode_id), rel:"RESULTED_IN", dst:("outcome",e.outcome.status), fact_text:e.outcome.status, confidence:1.0}

if e.outcome.status == "failure" or len(e.errors)>0:
    for err in e.errors:
        sig = FAILURE_SIGNATURE(err)
        facts += {src:("outcome",e.outcome.status), rel:"HAS_FAILURE_MODE", dst:("failure_mode",sig), fact_text:err, confidence:0.7}
```

##### Крок 3 — Dedup сутностей (entity resolution)

1) exact match у SQLite по `(node_type,key)`
2) semantic match у Chroma (опційно), коли key не заданий або “плаває”
3) create new

Критично: MVP має працювати без LLM, тому для skills/artifacts/outcomes/failure_mode ми використовуємо **стабільні key**.

##### Крок 4 — Upsert ребер з темпоральністю та evidence

Для кожного факту:

```text
src_id = RESOLVE_OR_CREATE_NODE(f.src.type, f.src.key, f.src_text)
dst_id = RESOLVE_OR_CREATE_NODE(f.dst.type, f.dst.key, f.dst_text)

edge = SELECT active edge WHERE (src_id,dst_id,rel_type,fact) AND valid_to IS NULL
if exists:
    edge.last_seen_at = now
    edge.weight = UPDATE_WEIGHT(edge.weight, f, e.outcome)
    edge.evidence += episode_id
else:
    INSERT edge(valid_from=now, valid_to=NULL, weight=INIT_WEIGHT(f, e.outcome), evidence=[episode_id])
    CHROMA_UPSERT(edge.fact)
```

**Update weight (MVP):**

```text
function UPDATE_WEIGHT(w, fact, outcome):
    # simple bounded update
    delta = 0
    if outcome.status == "success": delta = +1
    if outcome.status == "failure": delta = -1
    delta = delta * fact.confidence
    return CLAMP(w + delta, -10, +10)
```

##### Крок 5 — Конфлікти (MVP policy)

Для MVP конфлікти мінімізуються тим, що більшість фактів — **епізодичні** (episode→*), і вони не суперечать одне одному.
Конфлікти важливі для “семантичних” ребер (skill↔concept, skill↔skill):

- `unique_rel_types`: `HAS_CAPABILITY`, `EFFECTIVE_FOR` (опційно)
- `multi_rel_types`: `HAS_FAILURE_MODE`, `MITIGATED_BY`, `RAN_TEST`

Правило:
- якщо `unique_rel_type` і новий факт конфліктний з високою confidence → закрити старе ребро `valid_to=now` і створити нове.

##### Крок 6 — Векторні індекси

- Episode: `summary` → `chroma_episodes`
- Node: `name + summary` → `chroma_nodes`
- Edge: `fact` → `chroma_edges`

Embeddings генеруються тільки через Ollama. Для деградації в offline-режимі без Ollama скрипти можуть працювати без векторного шару (але з втратою semantic search).

---

## 9. Інтеграція Ollama (embeddings) + ChromaDB

```text
function OLLAMA_EMBED(text):
    model = ENV("AI_EMBED_MODEL") or "nomic-embed-text"
    url = ENV("OLLAMA_URL") or "http://127.0.0.1:11434"
    return HTTP_POST(url + "/api/embeddings", {model: model, prompt: text}).embedding
```

```text
function VECTOR_QUERY(scope, query_text, top_k):
    vec = OLLAMA_EMBED(query_text)
    return CHROMA_QUERY(collection=scope, query_vector=vec, top_k=top_k)
```

---

## 10. Обовʼязкові скрипти (CLI)

Система повинна мати мінімальний набір скриптів (локально), без MCP/серверів як обовʼязкових залежностей.

**MUST scripts:**
- `init` — створити SQLite схему + Chroma колекції
- `ingest` — записати епізод у БД, оновити граф і вектори
- `search` — hybrid retrieval (Chroma → SQLite traversal → ranking)
- `export-onboarding` — згенерувати `ai_skills/onboarding_snapshot.yaml`

**RECOMMENDED (DB-first metadata):**
- `skill put/get/list` — керування описами навичок без файлів (запис у SQLite таблицю `skill_defs`).

Рекомендована форма: один CLI `mem.py` з підкомандами.

Псевдокод CLI:

```text
mem init --db ./ai_skills_memory.sqlite --chroma ./ai_skills_chroma/
mem ingest --json episode.json
mem search --query "refactor gwt code" --top 10
mem export-onboarding --out ai_skills/onboarding_snapshot.yaml
```

Приклад запуску без встановлення wrapper-команди `mem`:

```text
python3 scripts/ai_skills_memory/mem.py init --db ./ai_skills_memory.sqlite --chroma ./ai_skills_chroma/
python3 scripts/ai_skills_memory/mem.py ingest --db ./ai_skills_memory.sqlite --chroma ./ai_skills_chroma/ --json episode.json
python3 scripts/ai_skills_memory/mem.py search --db ./ai_skills_memory.sqlite --chroma ./ai_skills_chroma/ --query "refactor gwt code" --top 10
python3 scripts/ai_skills_memory/mem.py export-onboarding --db ./ai_skills_memory.sqlite --out ai_skills/onboarding_snapshot.yaml
```

Рекомендований варіант для цього репозиторію (локальний `.venv` + автоперевірка Ollama + стабільні шляхи до DB/Chroma):

```text
./ai_mem.sh init
./ai_mem.sh ingest --json episode.json
./ai_mem.sh search --query "refactor gwt code" --top 10
./ai_mem.sh export-onboarding --out ai_skills/onboarding_snapshot.yaml
./ai_mem.sh skill put --json skill_def.json
```

### Тестовий корпус і перевірка функціоналу

Для регресій і швидкої валідації потрібна **окрема тестова БД**, яка створюється один раз і надалі повторно використовується.

**Артефакти:**
- `scripts/ai_skills_memory/test_data/episodes.jsonl` — базовий різноманітний корпус епізодів
- `scripts/ai_skills_memory/test_data/skill_defs.json` — DB-first metadata для навичок
- `scripts/ai_skills_memory/seed_test_db.py` — one-time (idempotent) seed тестової БД
- `scripts/ai_skills_memory/run_functional_tests.py` — повний цикл перевірки (generate → ingest → query → verify)

**Шляхи тестового storage (за замовчуванням):**
- SQLite: `ai_memory/ai_skills_memory_test.sqlite`
- Chroma: `ai_memory/ai_skills_chroma_test/`

**Seed (створити один раз, надалі можна повторювати — ідемпотентно):**

```text
./.venv/bin/python scripts/ai_skills_memory/seed_test_db.py \
    --db ai_memory/ai_skills_memory_test.sqlite \
    --chroma ai_memory/ai_skills_chroma_test
```

**Запустити функціональні тести:**

```text
./.venv/bin/python scripts/ai_skills_memory/run_functional_tests.py
```

**Режими seed для пошуку (`mem search` / `search.py`):**
- `--seed-mode chroma` (default): Chroma vectors → seeds → BFS → ranking
- `--seed-mode sql`: детермінований seed через SQLite `LIKE` (корисно для тестів/офлайн), далі BFS → ranking

**Зовнішній контекст (scope) для пошуку — як векторизований текст:**
- Контекст запиту часто походить із “загального контексту чату” і не зводиться до фіксованих ключів.
- У прототипі контекст зберігається як `context`-нода (summary = текст контексту) і теж індексується у Chroma.

Параметри пошуку:
- `--context-text "..."` (repeatable): довільний опис контексту (кілька фрагментів дозволено).
- `--context <...>`: alias для контекст-тексту (для зручності, якщо короткі значення).
- `--context-top N`: скільки найближчих context-нод брати з векторного пошуку.
- `--context-depth D`: наскільки розширювати “дозволений” підграф від епізодів, повʼязаних з контекстом.
- `--context-strict`: обмежити retrieval контекстним підграфом (якщо передано будь-який контекст-текст, strict вмикається за замовчуванням у прототипі).

Приклад (пошук у межах контексту чату як тексту):

```text
./ai_mem.sh search --query "virus" --seed-mode sql \
    --context-text "Ми обговорюємо кібербезпеку та шкідливе ПЗ" \
    --context-strict
```

---

## 11. План реалізації (поетапно)

Ціль: отримати відчутний ефект LTM без ризику “зламати” існуючий процес.

### Phase 0 — Специфікація (цей документ)

- Зафіксувати схему графу, інваріанти, API, mandatory loop.

### Phase 1 — MVP (SQLite + Chroma + Ollama)

- Реалізувати `mem init/ingest/search/export-onboarding`.
- Зробити structured facts ingest (без LLM), щоб MVP був стабільний і прогнозований.
- Додати індексацію в Chroma: nodes/edges/episodes.

### Phase 2 — Автоматизація графу (Graphiti-like)

- Додати (optional) LLM extraction через Ollama для неструктурованих текстів.
- Додати conflict handling + temporal validity на ключових типах ребер.

### Phase 3 — Інтеграція з агентом

- При кожному виконанні:
    - агент викликає `mem ingest` (MUST)
    - агент будує контекст через `mem search` (MUST)

### Phase 4 — Smart retrieval + composition

- Ранжування на основі:
    - близькість до концептів,
    - успішність по часу,
    - penalty за повторні failure modes,
    - синергія композицій.

### Phase 5 — Consolidation + self-improve

- Пакетна/фоновa консолідація.
- Автоматичні improvement proposals + gate через тести.

### Критерії успіху

- Зменшення “контекстного шуму” (менше читання логів).
- Краща стабільність (менше повторних failure на однакових задачах).
- Прискорення відбору навичок (менше ручного пошуку).

---

## 12. Додаток: компактний приклад Episode (JSON)

```json
{
  "timestamp": "2025-12-30T13:00:00+02:00",
  "task_text": "Design local DB-first AI Skills memory protocol",
  "summary": "Updated protocol to SQLite graph + Chroma vectors + Ollama embeddings",
  "used_skills": ["docs", "memory-design"],
  "touched_artifacts": ["ai_skills/skills_graph_concept.md"],
  "outcome": {"status": "success", "notes": "Spec updated"},
  "errors": [],
  "tests": []
}
```

