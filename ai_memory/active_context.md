## Meta
- last_updated: 2025-12-26T12:22:10+02:00
- project_root: /home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop
- language: uk
- active_skills: [skill-context, skill-docs]
 agent_notes: Linux workspace; цей чат присвячений процесам/документації керування контекстом задач (не змінам коду симулятора).
- recent_activity: Оновлено канонічні правила керування контекстом у `docs/context_rules/context_rules.md` (entrypoint + посилання на модулі). Підтримується реєстр архівів у `ai_memory/context_history/contexts_index.yaml`.

## Current Task

- task_id: CTX-SWITCHING-RULES-001
	goal: Розробити й формалізувати систему переключення контексту задачі при зміні категорії/теми: архівація попереднього контексту, індексація, пошук/відновлення або bootstrap нового контексту.
	current_focus: Описати/підтримувати правила в `docs/context_rules/context_rules.md` та підтримувати реєстр `ai_memory/context_history/contexts_index.yaml`.
	active_files: [docs/context_rules/context_rules.md, ai_memory/context_history/contexts_index.yaml]
	status: in-progress
	scope_in: Правила (алгоритм) архівації/відновлення/bootstrapping контекстів; визначення формату реєстру; мінімальні вимоги до summary та naming.
	scope_out: Автоматизація у вигляді скриптів/CLI; зміни в симуляторі; зміни формату експорту схем.

## Plan & References
plan: manage_todo_list (Define artifacts → Update rules → Create registry/archive → Switch active_context → Record skill experience)
- related_docs:
	- docs/context_rules/context_rules.md
	- docs/context_rules/switching.md
	- docs/context_rules/sync.md
	- docs/project.md
	- ai_skills/skills_agent.md
	- ai_memory/memory.md
- related_code: []

## Progress
- done:
		- Додано/узгоджено протокол переключення контекстів у `docs/context_rules/switching.md`.
	- Створено реєстр `ai_memory/context_history/contexts_index.yaml`.
	- Заархівовано попередній контекст у `ai_memory/context_history/ELM-GEOM-REFACTOR-CTX.md` та оновлено запис у реєстр.
	- Звірено модульні правила `docs/context_rules/*` (sync/switching/staleness/multi_task/hygiene) з основним документом.
- in_progress:
		- Уточнити правила/узгодженість: уникати дублювання та розсинхрону між `docs/context_rules/context_rules.md` і модулями `docs/context_rules/*`.
- next:
		- Визначити, що “entrypoint” для агентів — `docs/context_rules/context_rules.md` (канон), а модулями є `docs/context_rules/*.md`.
	- Додати мінімальні формальні інваріанти/валідацію формату `ai_memory/active_context.md` (хоч би lint-правила/чекліст).
	- Уточнити “good match” правило (поріг тегів/категорій) та де воно визначене єдиним джерелом.

## Breadcrumbs
 last_focus: "Формалізація протоколу переключення контекстів + створення реєстру контекстів."
- last_edit_targets: ["docs/context_rules/context_rules.md", "ai_memory/context_history/contexts_index.yaml"]
 next_hops: "1) Перевірити, що rules entrypoint посилається на всі модулі 2) Перевірити switching.md (archive/register/restore/bootstrap) 3) Додати experience entry."
 navigation_queries: ["Switching Flow", "contexts_index.yaml", "context_history", "Archive Summary", "Bootstrapping rule"]
 resume_recipe: "1) Відкрити docs/context_rules/context_rules.md 2) Перевірити switching.md + contexts_index.yaml 3) За потреби оновити experience у skill-context"

## Guardrails
- invariants:
	- `ai_memory/active_context.md` завжди описує тільки ОДНУ поточну задачу (решта — або Secondary Tasks, або архів).
	- Перед переключенням завжди робити sync активного контексту (щоб архів був коректним).
	- Реєстр `ai_memory/context_history/contexts_index.yaml` є єдиним списком усіх архівів (не покладатися на “пам’ять” або випадкове сканування файлів).
	- Не переносити між задачами застаріваючі секції (Progress/Breadcrumbs/Failure Context) — тільки стабільні частини.
- scope_guardrails:
	- Не додавати зайві “nice-to-have” механізми (CLI, UI, додаткові формати), поки не буде потреби.

- temporary_allowances:
	- "Дозволено довільно змінювати вміст каталогу ai_skills без додаткового підтвердження користувача."

## User Preferences

- ai_skills_change_policy: "free-to-edit-without-confirmation"

## Decisions
- 2025-12-26T10:12:27+02:00 → Попередній контекст (`ELM-GEOM-REFACTOR-CTX`) заархівовано в `ai_memory/context_history/` і зареєстровано в `contexts_index.yaml`, бо поточна тема чату змінилась на правила керування контекстами.
- 2025-12-26T10:20:57+02:00 → Архіви переведені на канонічні імена за `task_id` (один актуальний snapshot на задачу) для уникнення зберігання неактуальних копій.
- 2025-12-26T12:08:30+02:00 → Створено модульні правила у `docs/context_rules/` (індекс + окремі flow-файли) та визначено entrypoint.
- 2025-12-26T12:22:10+02:00 → Проведено ревʼю узгодженості між `docs/context_rules/*` та фактичним `ai_memory/context_history/contexts_index.yaml`; зафіксовано наступні кроки для уникнення розсинхрону.

## Open Questions / Blockers
- Чи потрібен окремий “human-readable” index (Markdown) на додачу до YAML, чи достатньо YAML?
- Чи вводимо явний список `category` значень (enum) або дозволяємо довільні?
- Який мінімальний поріг “гарного збігу” для auto-restore (наприклад: ≥2 спільних теги + найсвіжіший)?
- Чи треба формалізувати формат активного контексту (наприклад, YAML-frontmatter) для машинної перевірки та зменшення “вільного” форматування?

## Verification
- Перевірка узгодженості: `docs/context_rules/context_rules.md` є entrypoint і посилається на `switching.md`/`sync.md`; `ai_memory/context_history/contexts_index.yaml` має хоча б один валідний запис.

## Quick Resume — CTX-SWITCHING-RULES-001

```yaml
# Quick Resume — CTX-SWITCHING-RULES-001
goal: "Розробити й формалізувати систему переключення контексту задачі при зміні категорії/теми: архівація попереднього контексту, індексація, пошук/відновлення або bootstrap нового контексту."
focus_now: "Ревʼю та покращення docs/context_rules/context_rules.md; звірка з active_context + registry."
next_action: "Внести точкові покращення в docs/context_rules/context_rules.md / switching.md (детермінізація ‘good match’, поля registry, дрібні фікси форматування) і за потреби оновити записи experience."
key_files: ["docs/context_rules/context_rules.md", "docs/context_rules/switching.md", "ai_memory/active_context.md", "ai_memory/context_history/contexts_index.yaml", "ai_skills/skills/base-skills/skill-context/experience.yaml"]
verify_cmd: "(no build)"
last_result: pending
```
