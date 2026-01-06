## Meta
- last_updated: 2026-01-05T12:00:00+02:00
- project_root: /home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop
- language: uk
- active_skills: [skill-context, skill-docs, skill-scaffold, skill-testing]
- agent_notes: Локальний DB-first протокол + робочі локальні скрипти памʼяті (SQLite+Chroma+Ollama) + wrapper `ai_mem.sh` для `.venv`.

## Current Task

- task_id: CONTEXT-RULES-SYNC-001
- goal: Оновити шаблон `active_context` так, щоб він вимагав глобальний контекст задачі та фіксацію нових фактів/епізодів для довготривалої памʼяті без жорстких лімітів на кількість рядків/пунктів.
- global_context: Проєкт використовує DB-first памʼять та протокол контексту; активний контекст має бути достатньо деталізованим для відновлення роботи агентом у новому чаті, але без зайвого дублювання.
- current_focus: docs/context_rules/active_context_template.md; синхронізація ai_memory/active_context.md під новий шаблон.
- active_files: [docs/context_rules/active_context_template.md, docs/context_rules/context_rules.md, ai_memory/active_context.md]
- scope_in: Правки шаблону активного контексту й синхронізація active_context.md під поточний запит.
- scope_out: Будь-які зміни у коді/скриптах памʼяті (ai_mem.sh, ingestion/search) та перебудова інших документів поза контекст-правилами.

## Plan & References
- plan: Оновити шаблон (додати global_context + Long-term Memory Candidates; прибрати жорсткі ліміти), синхронізувати active_context.md.
- related_docs:
	- docs/context_rules/context_rules.md
	- docs/context_rules/active_context_template.md
- related_code:
	- ai_memory/active_context.md

## Progress
- done:
	- [x] Оновлено шаблон `active_context_template`: додано `global_context` у Current Task.
	- [x] Додано секцію Long-term Memory Candidates для збереження фактів/епізодів у довготривалу памʼять.
	- [x] Прибрано жорсткі числові ліміти на рядки/пункти (замінено на рекомендації “достатньо для відновлення, без надлишковості”).
	- [x] Оновлено rules (docs/context_rules/context_rules.md): зафіксовано принцип “без жорстких лімітів, достатньо для resume”, додано resumability quality-check.
- in_progress:
	- Немає.
- next:
	- Використати нові секції в наступних задачах і при потребі уточнити формулювання (без введення числових лімітів).

## Breadcrumbs
- last_focus: "Update active context template to include global context and long-term memory candidates; remove hard size limits."
- last_edit_targets: ["docs/context_rules/active_context_template.md", "ai_memory/active_context.md"]
- next_hops: "Ensure template guidance matches rules and is usable in practice."
- resume_recipe: "Open active_context_template.md, confirm sections match desired behavior, then sync active_context.md after any rule changes."

## Guardrails
- invariants:
	- Активний контекст має бути достатнім для відновлення роботи без “вгадувань”, але без повторення логів/дифів.
	- Не вводити жорсткі числові ліміти на рядки/пункти; натомість — принцип “достатньо, але не надлишково”.
	- Quick Resume YAML блок завжди лишається в кінці файлу.

## Decisions
- 2026-01-05T00:00:00+02:00 → Додати `global_context` та секцію Long-term Memory Candidates у шаблон активного контексту; прибрати жорсткі числові ліміти, замінивши їх принципом “достатньо, але не надлишково”.
- 2026-01-05T12:00:00+02:00 → Switched to ELM-GEOM-REFACTOR-CTX context.

## Open Questions / Blockers


## Long-term Memory Candidates
- facts_to_save:
	- Шаблон активного контексту має уникати жорстких числових лімітів; достатність визначається здатністю агента відновити роботу без “вгадувань”.
	- Додаткове поле `global_context` корисне для швидкого розуміння “чому це робимо” при відновленні в новому чаті.
- episodes_to_ingest:
	- Оновлено `docs/context_rules/active_context_template.md`: додано `global_context` і секцію Long-term Memory Candidates, прибрано числові ліміти з Meta/Progress/Long-term.

```yaml
# Quick Resume — CONTEXT-RULES-SYNC-001
goal: Update active context template to capture global task context and long-term memory candidates without hard size limits.
focus_now: Keep docs/context_rules/active_context_template.md and ai_memory/active_context.md in sync.
next_action: Apply the updated template in the next task and adjust wording only if a real failure-mode appears.
key_files: [docs/context_rules/active_context_template.md, docs/context_rules/context_rules.md, ai_memory/active_context.md]
verify_cmd: sed -n '1,140p' docs/context_rules/active_context_template.md | cat
last_result: success
```

## Archive Meta
- archived_at: 2026-01-05T12:00:00+02:00
- archived_reason: "User requested switch to ELM-GEOM-REFACTOR-CTX"
- status: archived