## Meta
- last_updated: 2026-01-05T19:15:00+02:00
- project_root: /home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop
- language: uk
- active_skills: [circuitjs1-dev-workflow, circuitjs1-ui-maintenance]
	agent_notes: Linux workspace; DevMode запускаємо через scripts/run_dev_web.sh.

## Current Task

- task_id: DIALOGS-POSITION-RESIZE-COLLAPSE
	goal: Виправити позиціювання діалогів після refresh/resize: fallback по центру, переміщення у видиму область, збереження відносного положення до ближчих країв при зміні розміру, та можливість згортання у заголовок.
	global_context: Після оновлення сторінки деякі діалоги (зокрема Sliders) відновлюються некоректно і опиняються в (0,0). Потрібна єдина поведінка для всіх діалогів.
	current_focus: Base class `Dialog` + місця, де вручну позиціонують Sliders/Controls.
	active_files: [src/main/java/com/lushprojects/circuitjs1/client/dialog/Dialog.java, src/main/java/com/lushprojects/circuitjs1/client/CirSim.java, src/main/java/com/lushprojects/circuitjs1/client/dialog/HelpDialog.java, src/main/java/com/lushprojects/circuitjs1/client/dialog/LicenseDialog.java, src/main/java/com/lushprojects/circuitjs1/client/dialog/ModDialog.java]
	status: in_progress
	scope_in: Поведінка позиції/resize/collapse для всіх `Dialog` та діалогів, що ще були на `DialogBox`.
	scope_out: Редизайн UI/стилів діалогів; зміни для non-dialog popups/context menus.

## Plan & References
	plan: manage_todo_list (Dialog positioning + collapse)
	related_docs:
		- docs/project.md
		- docs/context_rules/active_context_template.md
	related_code:
		- src/main/java/com/lushprojects/circuitjs1/client/dialog/Dialog.java
		- src/main/java/com/lushprojects/circuitjs1/client/CirSim.java
		- src/main/java/com/lushprojects/circuitjs1/client/dialog/

## Progress
	done:
		- Base [src/main/java/com/lushprojects/circuitjs1/client/dialog/Dialog.java](src/main/java/com/lushprojects/circuitjs1/client/dialog/Dialog.java):
		  - Center fallback when no persisted/explicit position.
		  - Clamp into viewport on show and after drag.
		  - Nearest-edge anchoring; on browser resize preserve offsets to nearest edges.
		  - Collapse/expand toggle in caption (hides content; header stays draggable).
		- Fixed Sliders positioning guard in [src/main/java/com/lushprojects/circuitjs1/client/CirSim.java](src/main/java/com/lushprojects/circuitjs1/client/CirSim.java) (check `slidersDialog.isPositionRestored()` instead of `controlsDialog`).
		- Migrated dialogs that still extended `DialogBox` to extend `Dialog`:
		  - [src/main/java/com/lushprojects/circuitjs1/client/dialog/HelpDialog.java](src/main/java/com/lushprojects/circuitjs1/client/dialog/HelpDialog.java)
		  - [src/main/java/com/lushprojects/circuitjs1/client/dialog/LicenseDialog.java](src/main/java/com/lushprojects/circuitjs1/client/dialog/LicenseDialog.java)
		  - [src/main/java/com/lushprojects/circuitjs1/client/dialog/ModDialog.java](src/main/java/com/lushprojects/circuitjs1/client/dialog/ModDialog.java)
		- Build verified: `mvn -q -DskipTests package`.

	in_progress:
		- Manual UX verification in DevMode: refresh/resize/collapse for key dialogs.

	next:
		- Запустити DevMode (`./scripts/run_dev_web.sh`), відкрити Sliders/Mod/Help, refresh сторінки:
		  - перевірити, що позиція відновлюється або центрується (не (0,0)).
		  - перевірити, що діалог завжди у видимій області.
		  - змінити розмір вікна: діалог має “триматися” ближчих країв.
		  - згортання/розгортання через +/- у заголовку.

## Breadcrumbs
	last_focus: "Dialogs lose position after refresh/resize; add global rules + collapse."
	last_edit_targets: ["src/main/java/com/lushprojects/circuitjs1/client/dialog/Dialog.java", "src/main/java/com/lushprojects/circuitjs1/client/CirSim.java"]
	resume_recipe: "Run DevMode, open dialogs, refresh + resize, verify clamp/anchor/collapse."

## Guardrails
	invariants:
		- Якщо позицію визначити неможливо → показувати по центру.
		- Якщо діалог поза межами → перемістити у видиму область.
		- При resize → зберігати відносне положення до ближчих країв.
		- Не додавати зайвих UI сторінок/фіч, окрім згортання у заголовок.

## Verification
	- Build (Maven + GWT): OK (`mvn -q -DskipTests package`).

## Long-term Memory Candidates
	facts_to_save:
		- Базовий `Dialog` тепер відповідає за clamp/anchor/collapse; специфічні діалоги не повинні реалізовувати ці правила повторно.
	episodes_to_ingest:
		- ai_memory/episode_dialogs_position_resize_collapse_2026-01-05.json

## Quick Resume — DIALOGS-POSITION-RESIZE-COLLAPSE
```yaml
# Quick Resume — DIALOGS-POSITION-RESIZE-COLLAPSE
goal: "Dialogs: center fallback + clamp + edge-anchored resize + collapse"
focus_now: "Manual DevMode verification (refresh/resize/collapse)"
next_action: "Run ./scripts/run_dev_web.sh, open Sliders dialog, refresh page, resize window, verify behavior"
key_files: [src/main/java/com/lushprojects/circuitjs1/client/dialog/Dialog.java, src/main/java/com/lushprojects/circuitjs1/client/CirSim.java]
verify_cmd: mvn -q -DskipTests package
last_result: success
```
