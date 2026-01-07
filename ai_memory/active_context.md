## Meta
- last_updated: 2026-01-07T18:12:00+02:00
- project_root: /home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop
- language: uk
- active_skills: [circuitjs1-dev-workflow]
	agent_notes: GWT DevMode у браузері (http://127.0.0.1:8888/circuitjs.html). Сим-стан: CircuitDocument.errorMessage + CircuitSimulator.stopMessage.

## Current Task
- task_id: TRANSFORMER-WINDING-R-AUTOTIMESTEP
	goal: Додати внутрішні опори обмоток у трансформатори (звичайний/з відводом/custom), зменшити дефолтний coupling до 0.99, та увімкнути авто-зменшення timestep за замовчуванням.
	global_context: На деяких схемах була детермінована зупинка через non-convergence; ідеальні трансформатори (k≈1, R=0) підсилюють проблеми збіжності. Авто-backoff timestep вже існує, але має бути default-on.
	current_focus: Серійні R через internal nodes для всіх transformer-елементів + JSON/legacy dump backward-compat; увімкнути CircuitSimulator.adjustTimeStep по замовчуванню.
	active_files: [src/main/java/com/lushprojects/circuitjs1/client/element/TransformerElm.java, src/main/java/com/lushprojects/circuitjs1/client/element/TappedTransformerElm.java, src/main/java/com/lushprojects/circuitjs1/client/element/CustomTransformerElm.java, src/main/java/com/lushprojects/circuitjs1/client/CircuitSimulator.java]
	scratchpad: Авто backoff в CircuitSimulator при adjustTimeStep=true (halving dt до minTimeStep). Для R обмоток: node(start) -- R -- nodeInt -- L/M -- node(end).
	scope_in: Мінімальні зміни моделей трансформатора + дефолти; без зміни solver-алгоритмів.
	scope_out: Перебудова convergence-алгоритму або UI-редизайн.

## Other Tasks (This Chat)

- task_id: SIM-CONVERGENCE-RESET-NODE-MARKERS
	goal: Додати ідентифікатор елемента до повідомлення про збіжність, зробити Reset симуляції коректним (включно зі скиданням стану елементів/solver), і прибрати “завислі” маркери вузлів/пінів після видалення елемента.
	status: completed
	global_context: Після невдалої збіжності/stop користувачу важко зрозуміти, який елемент винен, а також важко відновити роботу (reset не чистив stop/error). При редагуванні схеми у режимі “stopped” візуальні маркери постів могли не оновлюватися без аналізу.
	current_focus: (історично) Convergence loop у CircuitSimulator.runCircuit(), JS API resetSimulation у CirSim, синхронізація analyze state у BaseCirSim.needAnalyze(), та відсутність canvas-overlay помилок у CircuitRenderer.
	active_files: [src/main/java/com/lushprojects/circuitjs1/client/CircuitSimulator.java, src/main/java/com/lushprojects/circuitjs1/client/CirSim.java, src/main/java/com/lushprojects/circuitjs1/client/BaseCirSim.java, src/main/java/com/lushprojects/circuitjs1/client/CircuitRenderer.java]
	scope_in: Мінімальні зміни у повідомленні/стані reset та синхронізації пост-маркерів; без рефакторингів solver.
	scope_out: Глибока діагностика нелінійних моделей або зміна алгоритму збіжності.

## Plan & References
	TRANSFORMER-WINDING-R-AUTOTIMESTEP:
		plan: manage_todo_list (completed; verify in DevMode/JS API optionally)
		related_docs:
			- docs/JS_API.md
			related_code:
			- src/main/java/com/lushprojects/circuitjs1/client/CircuitSimulator.java (auto timestep backoff + default)
			- src/main/java/com/lushprojects/circuitjs1/client/element/TransformerElm.java
			- src/main/java/com/lushprojects/circuitjs1/client/element/TappedTransformerElm.java
			- src/main/java/com/lushprojects/circuitjs1/client/element/CustomTransformerElm.java
		session_history:
			- ai_memory/tmp_episode_transformer_winding_resistance_autotimestep_2026-01-07.json

	SIM-CONVERGENCE-RESET-NODE-MARKERS:
		plan: manage_todo_list (completed)
		related_docs:
			- docs/JS_API.md
			related_code:
			- src/main/java/com/lushprojects/circuitjs1/client/CircuitSimulator.java
			- src/main/java/com/lushprojects/circuitjs1/client/CirSim.java
			- src/main/java/com/lushprojects/circuitjs1/client/BaseCirSim.java
			- src/main/java/com/lushprojects/circuitjs1/client/CircuitRenderer.java
		session_history:
			- ai_memory/tmp_episode_std_circuits_error_ux.json

## Progress
	TRANSFORMER-WINDING-R-AUTOTIMESTEP:
		done:
			- Додано серійні опори обмоток (editable) для TransformerElm/TappedTransformerElm/CustomTransformerElm.
			- Для всіх трансформаторів дефолтний coupling зменшено до 0.99 (замість ~0.999).
			- CustomTransformerElm: stamping/iteration переведено на internal nodes, щоб R реально впливала на розв’язок.
			- CustomTransformerElm: dump/JSON оновлено для збереження winding resistance (dump з backward-compat через явний tapOverrideCount=0).
			- CircuitSimulator: adjustTimeStep увімкнено за замовчуванням для нових документів/сесій.
			- `mvn test -DskipTests=true` — BUILD SUCCESS.
		in_progress: []
		next:
			- У DevMode/браузері: відкрити проблемну схему та перевірити, що stop через non-convergence відтворюється рідше/зникає завдяки R та k=0.99.
			- Перевірити опції симуляції: "Auto-Adjust Timestep" має бути увімкнено по замовчуванню.

	SIM-CONVERGENCE-RESET-NODE-MARKERS:
		done:
			- Convergence stop включає елемент: "Convergence failed! Element: <ID>" і підсвічує stopElm.
			- resetSimulation() чистить stop/error, скидає елементи та solver state і дозволяє одразу запускати симуляцію.
			- needAnalyze() у stopped режимі робить analyzeCircuit() одразу, щоб пост-маркери не зависали після delete/edits.
			- stopMessage показується у верхньому лівому кутку під "Mode", а осцилографи лишаються видимими.
		in_progress: []
		next:
			- (опційно) Ручна перевірка в DevMode: спровокувати stop, перевірити атрибуцію/Reset/delete у stopped режимі.

## Breadcrumbs
	TRANSFORMER-WINDING-R-AUTOTIMESTEP:
		last_focus: "Додати R обмоток + k=0.99 для стабільності збіжності; ввімкнути auto timestep за замовчуванням."
		last_edit_targets: ["src/main/java/com/lushprojects/circuitjs1/client/element/TransformerElm.java", "src/main/java/com/lushprojects/circuitjs1/client/element/TappedTransformerElm.java", "src/main/java/com/lushprojects/circuitjs1/client/element/CustomTransformerElm.java", "src/main/java/com/lushprojects/circuitjs1/client/CircuitSimulator.java"]
		resume_recipe: "DevMode → відкрити схему → Run. Перевірити Edit Options: Auto-Adjust Timestep=true. Перевірити, що в Edit елементів трансформатора доступні поля опорів обмоток."

	SIM-CONVERGENCE-RESET-NODE-MARKERS:
		last_focus: "Convergence failed без вказання елемента; reset не чистив stop/error; пост-маркери лишались після delete."
		last_edit_targets: ["src/main/java/com/lushprojects/circuitjs1/client/CircuitSimulator.java", "src/main/java/com/lushprojects/circuitjs1/client/CirSim.java", "src/main/java/com/lushprojects/circuitjs1/client/BaseCirSim.java", "src/main/java/com/lushprojects/circuitjs1/client/CircuitRenderer.java"]
		resume_recipe: "DevMode → JS API: import circuit, run, спровокувати stop; перевірити stopMessage/errorMessage; натиснути Reset Simulation; delete елемент у stopped режимі і перевірити що post markers зникли."

## Guardrails
	TRANSFORMER-WINDING-R-AUTOTIMESTEP:
		invariants:
			- Не додавати нових UI панелей/діалогів.
			- Не міняти solver-алгоритм; лише дефолти та моделі трансформаторів.
		definition_of_done:
			- У трансформаторів є опори обмоток (editable) і вони зберігаються у dump/JSON.
			- Дефолтний coupling = 0.99.
			- Auto-Adjust Timestep увімкнено по замовчуванню.

	SIM-CONVERGENCE-RESET-NODE-MARKERS:
		invariants:
			- Не додавати нових UI панелей/діалогів.
			- Не міняти математику solver; лише атрибуція/стан reset та синхронізація draw state.
		definition_of_done:
			- Convergence повідомлення містить ID проблемного елемента.
			- Reset повертає симуляцію у стан, де можна одразу запускати знов.
			- Після delete у stopped режимі маркери постів не лишаються.

## Long-term Memory Candidates
	TRANSFORMER-WINDING-R-AUTOTIMESTEP:
		facts_to_save:
			- Для стабільності збіжності трансформаторів: додавати серійні опори обмоток через internal nodes (node(start)--R--nodeInt--L/M--node(end)).
			- Auto timestep backoff вже є в CircuitSimulator; default-on суттєво допомагає з non-convergence.
		episodes_to_ingest:
			- Episode: Transformer winding resistances + coupling default 0.99 + default-on auto timestep.

	SIM-CONVERGENCE-RESET-NODE-MARKERS:
		facts_to_save:
			- Для атрибуції non-convergence: у кожній sub-iteration запам’ятати перший CircuitElm, після doStep() якого converged став false.
			- У stopped режимі analyze-derived draw state (наприклад postDrawList) треба оновлювати синхронно під час needAnalyze().
		episodes_to_ingest:
			- Episode: Attribute convergence to element + make reset reset elements+solver + keep post markers in sync + remove canvas error overlay.

```yaml
# Quick Resume — TRANSFORMER-WINDING-R-AUTOTIMESTEP
goal: Add winding resistances to transformers, set default coupling=0.99, and enable auto timestep adjust by default
focus_now: Verify DevMode behavior on a previously failing circuit; confirm options + element edits
next_action: In DevMode open the failing circuit and run; verify "Auto-Adjust Timestep" is on and convergence is improved
key_files: [src/main/java/com/lushprojects/circuitjs1/client/element/TransformerElm.java, src/main/java/com/lushprojects/circuitjs1/client/element/TappedTransformerElm.java, src/main/java/com/lushprojects/circuitjs1/client/element/CustomTransformerElm.java, src/main/java/com/lushprojects/circuitjs1/client/CircuitSimulator.java]
verify_cmd: mvn -q -DskipTests=true test
last_result: success

---

# Quick Resume — SIM-CONVERGENCE-RESET-NODE-MARKERS
goal: Attribute convergence errors to element ID; fix reset (elements+solver); avoid stale node/post markers after edits
focus_now: Optional manual regression checks in DevMode
next_action: Trigger a convergence failure and confirm the message includes element ID; then Reset Simulation and delete elements in stopped mode
key_files: [src/main/java/com/lushprojects/circuitjs1/client/CircuitSimulator.java, src/main/java/com/lushprojects/circuitjs1/client/CirSim.java, src/main/java/com/lushprojects/circuitjs1/client/BaseCirSim.java, src/main/java/com/lushprojects/circuitjs1/client/CircuitRenderer.java]
verify_cmd: mvn -q test
last_result: success
```
