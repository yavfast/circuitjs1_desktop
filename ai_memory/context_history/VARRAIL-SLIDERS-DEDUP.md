## Meta
- last_updated: 2026-01-05T18:20:00+02:00
- project_root: /home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop
- language: uk
- active_skills: [circuitjs1-dev-workflow, circuitjs1-ui-maintenance]
  agent_notes: Linux workspace; DevMode запускаємо через scripts/run_dev_web.sh.

## Current Task

- task_id: VARRAIL-SLIDERS-DEDUP
	goal: Прибрати дублікати слайдерів у Sliders dialog (особливо для VarRail/Adjustable), щоб повторні rebuild-и / refresh не додавали ті самі повзунки кілька разів.
	global_context: Після ввімкнення Sliders dialog для VarRail (auto Adjustable) з'явилися дублікати UI-рядків при повторних викликах createSliders()/updateSliders() і після оновлення сторінки.
	status: archived
	scope_in: Точкові правки Adjustable/SlidersDialog/AdjustableManager та VarRail для стабільного UI.
	scope_out: Редизайн діалогу Sliders або нові UI-фічі поза вимогами.

## Progress
- done:
	- Fixed duplicate slider rows by making slider creation idempotent and clearing UI references after deletion.
	- Fixed duplicates after page refresh by deduping AdjustableManager.adjustables (by element + editItem + sharedSlider).
	- Deferred VarRail auto-adjustable creation until slider-creation time; removed VarRail’s separate vertical-panel slider UI.
	- Build verified: `mvn -q -DskipTests compile`.

## Quick Resume — VARRAIL-SLIDERS-DEDUP
```yaml
# Quick Resume — VARRAIL-SLIDERS-DEDUP
goal: "No duplicate sliders after refresh/rebuild"
focus_now: "(archived)"
next_action: "Open Sliders dialog, refresh page, ensure no duplicates"
key_files: [src/main/java/com/lushprojects/circuitjs1/client/AdjustableManager.java, src/main/java/com/lushprojects/circuitjs1/client/Adjustable.java, src/main/java/com/lushprojects/circuitjs1/client/element/VarRailElm.java, src/main/java/com/lushprojects/circuitjs1/client/dialog/SlidersDialog.java]
verify_cmd: mvn -q -DskipTests compile
last_result: success
```
