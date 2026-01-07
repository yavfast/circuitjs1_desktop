## Meta
- last_updated: 2026-01-07T12:15:00+02:00
- project_root: /home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop
- language: uk
- active_skills: [circuitjs1-dev-workflow]
	agent_notes: GWT DevMode у браузері (http://127.0.0.1:8888/circuitjs.html). Дебаг/фікс симулятора для схеми з двома ізольованими "островами".

## Current Task
- task_id: SIM-SINGULAR-MATRIX-ISLANDS
	goal: Виправити збій запуску симуляції ("Сингулярна матриця!") на схемі з двома ізольованими електричними контурами ("помножувач ємності"), без rollback.
	global_context: Потрібен forward-fix в solver/analyze/stamping, щоб такі схеми симулювалися стабільно.
	current_focus: Відновлення базової матриці для нелінійних підітерацій (origMatrix/origRightSide) + обробка кількох GroundElm.
	active_files: [src/main/java/com/lushprojects/circuitjs1/client/CircuitSimulator.java, src/main/java/com/lushprojects/circuitjs1/client/CircuitMath.java]
	scratchpad: Коренева причина знайдена: origMatrix/origRightSide не заповнювались, якщо simplifyMatrix() НЕ робив спрощення → у нелінійних схемах кожна Newton-підітерація відновлювала нульову матрицю, що викликало повторні LU fail та "Convergence failed"/"Singular matrix".
	scope_in: Solver/analyze/stamping, обробка ground/ізольованих контурів, LU-діагностика.
	scope_out: UI, формат схем, rollback.

## Plan & References
	plan: (ad-hoc) — зробити origMatrix snapshot завжди; перевірити на реальному дампі через JS API.
	related_docs:
		- docs/JS_API.md
		- docs/context_rules/context_rules.md
		- docs/skills_rules/skills_rules.md
	related_code:
		- src/main/java/com/lushprojects/circuitjs1/client/CircuitSimulator.java
		- src/main/java/com/lushprojects/circuitjs1/client/CircuitMath.java
		- src/main/java/com/lushprojects/circuitjs1/client/element/GroundElm.java
	session_history: []

## Progress
	done:
		- Відтворено реальну проблемну схему у браузері (elementCount=21; два GroundElm; два ізольовані острови).
		- Додано діагностику LU-провалу (col/row/pivotAbs + мапінг змінної) та розширено стабілізацію (діагональ для voltage-source current змінних).
		- Зроблено robust ground mapping: усі GroundElm мапляться в node=0 у CircuitSimulator.setGroundNode().
		- Знайдено та виправлено ключовий баг: у CircuitSimulator.simplifyMatrix() origMatrix/origRightSide не копіювались коли спрощення не відбулось.
		- Перевірено в DevMode після `mvn -DskipTests package`: схема тепер робить кроки (час росте), stopMessage/errorMessage порожні.

	in_progress:
		- Прибирання/зменшення зайвого debug-логування (опційно; тільки якщо заважає).

	next:
		- Прогнати ще кілька нетипових нелінійних схем (opamp/діоди), щоб переконатися що regressions нема.
		- (Опційно) прибрати надто шумні матричні дампи або заховати за debug-флагом.

## Breadcrumbs
	last_focus: "origMatrix snapshot is missing when simplifyMatrix makes no reduction"
	last_edit_targets: ["src/main/java/com/lushprojects/circuitjs1/client/CircuitSimulator.java"]
	resume_recipe: "В DevMode викликати CircuitJS1.resetSimulation(); кілька разів CircuitJS1.stepSimulation(); перевірити CircuitJS1.getSimInfo() що time>0 і errorMessage порожній."

## Guardrails
	invariants:
		- Не робити rollback.
		- Не ламати existing linear solve: зміна повинна лише гарантувати snapshot origMatrix/origRightSide для nonlinear sub-iterations.
	definition_of_done:
		- Проблемна схема з 2 островами стартує і робить кроки без "Singular matrix"/"Convergence failed".

## Long-term Memory Candidates
	facts_to_save:
		- У CircuitSimulator нелінійний цикл кожної підітерації відновлює circuitMatrix з origMatrix → origMatrix має бути завжди заповнений, навіть якщо simplifyMatrix нічого не спростив.
	episodes_to_ingest:
		- Episode: Fix origMatrix snapshot when simplifyMatrix no-ops (SIM-SINGULAR-MATRIX-ISLANDS)

```yaml
# Quick Resume — SIM-SINGULAR-MATRIX-ISLANDS
goal: Fix "Singular matrix" on multi-island capacitance multiplier circuit
focus_now: Ensure origMatrix/origRightSide snapshot is always populated for nonlinear sub-iterations
next_action: Re-test a few nonlinear example circuits in DevMode via CircuitJS1.stepSimulation()
key_files: [src/main/java/com/lushprojects/circuitjs1/client/CircuitSimulator.java, src/main/java/com/lushprojects/circuitjs1/client/CircuitMath.java]
verify_cmd: mvn -DskipTests package
last_result: success
```
