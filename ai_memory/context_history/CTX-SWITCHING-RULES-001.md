## Meta
- last_updated: 2025-12-29T15:40:00+02:00
- project_root: /home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop
- language: uk
- active_skills: [skill-context, skill-docs]
  agent_notes: Linux workspace; цей чат присвячений процесам/документації керування контекстом задач (не змінам коду симулятора).
- recent_activity: Видалено `definitions.md`, вміст розподілено по модульних файлах.

## Current Task

- task_id: CTX-SWITCHING-RULES-001
	goal: Формалізувати систему переключення контексту задачі: архівація, індексація, відновлення/bootstrap.
	current_focus: Підтримка правил у `docs/context_rules/` та реєстру `contexts_index.yaml`.
	active_files: [docs/context_rules/*.md, ai_memory/context_history/contexts_index.yaml]
	status: in-progress
	scope_in: Правила архівації/відновлення/bootstrap; формат реєстру; вимоги до summary/naming.
	scope_out: CLI-автоматизація; зміни коду симулятора.

## Plan & References
plan: Модульна документація у `docs/context_rules/`
- related_docs:
	- docs/context_rules/context_rules.md (entrypoint)
	- docs/context_rules/switching.md
	- docs/context_rules/sync.md
	- docs/context_rules/staleness.md
	- docs/context_rules/multi_task.md
	- docs/context_rules/hygiene_security.md
- related_code: []

## Progress
- done:
	- [x] Створено протокол переключення у `switching.md`.
	- [x] Створено реєстр `contexts_index.yaml`.
	- [x] Модульні правила `docs/context_rules/*` узгоджено.
	- [x] **Видалено `definitions.md`** — вміст розподілено по модулях.
	- [x] Оновлено cross-references у `context_rules.md` та `hygiene_security.md`.
- in_progress:
	- Уточнити правила частоти sync активного контексту.
- next:
	- Додати explicit тригери для sync у `sync.md`.
	- Перевірити, що всі модулі self-contained.

## Breadcrumbs
 last_focus: "Розподіл definitions.md по модулях."
- last_edit_targets: ["multi_task.md", "staleness.md", "switching.md", "session_history.md", "active_context_template.md", "hygiene_security.md", "context_rules.md"]
 next_hops: "1) Уточнити sync triggers"
 resume_recipe: "Відкрити docs/context_rules/sync.md"

## Guardrails
- invariants:
	- `active_context.md` описує тільки ОДНУ поточну задачу.
	- Перед переключенням — sync.
- scope_guardrails:
	- Не додавати CLI/UI поки не потрібно.

## Decisions
- 2025-12-26T10:12:27+02:00 → Архівовано попередній контекст.
- 2025-12-26T10:20:57+02:00 → Канонічні імена архівів за `task_id`.
- 2025-12-26T12:08:30+02:00 → Створено модульні правила.
- 2025-12-29T11:42:37+02:00 → Видалено `definitions.md`.

## Open Questions / Blockers
- Чи достатньо YAML-реєстру?
- Який мінімальний поріг для auto-restore?
