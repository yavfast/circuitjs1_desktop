## Meta
- last_updated: 2026-01-08T00:00:00+02:00
- project_root: /home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop
- language: uk
- active_skills: [circuitjs1-dev-workflow]
	agent_notes: GWT DevMode у браузері (http://127.0.0.1:8888/circuitjs.html). Сим-стан: CircuitDocument.errorMessage + CircuitSimulator.stopMessage.

## Current Task

- task_id: SOLVER-NONCONVERGENCE-RECOVERY
	goal: Максимально уникати зупинки симуляції на чисельних збоях (non-convergence/singular matrix/structural singularities) — симуляція має залишатись “живою” (educational UX > точність на спайках).
	global_context: Після серії змін “never stop” потрібно прибрати залишкові solver-level stop() (Singular matrix/Matrix error/wire loop/FindPathInfo checks) і не допускати фатальних винятків при невдалому stamp.
	current_focus: Перевести solver/validation на non-fatal warnings + recovery, щоб цикл симуляції не зупинявся і не падав у exceptions.
	active_files: [src/main/java/com/lushprojects/circuitjs1/client/CircuitSimulator.java, src/main/java/com/lushprojects/circuitjs1/client/FindPathInfo.java, src/main/java/com/lushprojects/circuitjs1/client/CircuitDocument.java, src/main/java/com/lushprojects/circuitjs1/client/CirSim.java, src/main/java/com/lushprojects/circuitjs1/client/CircuitRenderer.java]
	scope_in: М’яке відновлення/попередження замість stop на сингулярностях/матриці/структурних петлях; без нових UI панелей.
	scope_out: Повна SPICE-подібна реалізація source-stepping/gear solver, або перепис математики моделей.

## Other Tasks (This Chat)

- task_id: EDITOR-DELETE-UNDO-SHORTCUTS
	goal: Виправити 3 баги редактора: delete зайвий елемент, undo після delete може очистити схему, shortcuts не працюють одразу після open/tab switch.
	status: archived
	global_context: Виправлення input/undo/focus в editor-частині; контекст заархівовано.
	current_focus: Manual smoke в DevMode (shortcuts/delete/undo).
	active_files: [ai_memory/context_history/EDITOR-DELETE-UNDO-SHORTCUTS.md]
	next: "За потреби відновити з ai_memory/context_history/EDITOR-DELETE-UNDO-SHORTCUTS.md і зробити ручний smoke"

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
	SIM-CONVERGENCE-RESET-NODE-MARKERS:
		done:
			- Convergence stop включає елемент: "Convergence failed! Element: <ID>" і підсвічує stopElm.
			- resetSimulation() чистить stop/error, скидає елементи та solver state і дозволяє одразу запускати симуляцію.
			- needAnalyze() у stopped режимі робить analyzeCircuit() одразу, щоб пост-маркери не зависали після delete/edits.
			- stopMessage показується у верхньому лівому кутку під "Mode", а осцилографи лишаються видимими.
		in_progress: []
		next:
			- (опційно) Ручна перевірка в DevMode: спровокувати stop, перевірити атрибуцію/Reset/delete у stopped режимі.

	SOLVER-NONCONVERGENCE-RECOVERY:
		done:
			- `CircuitSimulator`: додано panic-mode recovery, щоб не стопатись на non-convergence: тимчасові node-to-ground шунти + extra gmin для PN моделей + force-step як крайній випадок.
			- `CircuitSimulator`: у panic-mode зменшено budget sub-iterations (щоб UI не фрізився на 5000 ітерацій).
			- `TransistorElm`: пом’якшено limiter threshold у panic-mode; додано підтримку simulator-provided extra gmin.
			- `TransistorElm`: додано захист від overflow у `Math.exp()` (clamp аргументів) щоб уникати NaN/Inf після релаксації limiter.
			- `TransistorElm`: прибрано hard-stop на NaN/Inf та "max current exceeded" (замість цього: clamping + converged=false).
			- `Diode`: panic-aware limiter (релаксація), extra gmin від simulator, adaptive gmin раніше у panic-mode, та захист від overflow у `Math.exp()`.
			- `TunnelDiodeElm`: panic-aware limiter, extra gmin, та захист від overflow/NaN/Inf при штампуванні.
			- `MosfetElm`: panic-aware per-iteration voltage limiting та використання simulator extra gmin як floor для малого `Gds`.
			- `DiodeElm`/`LEDArrayElm`/`SevenSegElm`: прибрано hard-stop "max current exceeded" → clamp + `converged=false`.
			- `ThermistorNTCElm`: захист від overflow у `Math.exp()` при обчисленні опору (щоб не отримувати Infinity/NaN).
			- `TransLineElm`: прибрано hard-stop на "delay too large"/"need to ground"; замість цього clamp delay до буфера і `converged=false`.
			- `PolarCapacitorElm`: прибрано hard-stop при перевищенні зворотної напруги; clamp внутрішнього `voltDiff` + `converged=false`.
			- `CircuitSimulator`: додано глобальний safety-net для stamp API (stampMatrix/stampRightSide/stampResistor/stampConductance/stampCurrentSource/updateVoltageSource): NaN/Inf/extreme → clamp/0 + `converged=false`.
			- `SCRElm`/`DiacElm`/`SparkGapElm`: захист від divide-by-zero/NaN параметрів у обчисленні струмів/множників (щоб не з’являлись Inf у логіці стану).
			- DevMode: перевірено на проблемній схемі, що stopMessage більше не з’являється (симуляція може бути повільною при малому timestep, але не зупиняється з помилкою).
			- `CircuitSimulator`: додано non-fatal `warningMessage`/`warn()`; сингулярності/матриця/внутрішні петлі тепер можуть деградувати без `stop()`.
			- `CircuitSimulator.calcWireInfo()`: "wire loop detected" у recovery mode більше не зупиняє — ламаємо циклічну залежність і наближено рахуємо струми.
			- `FindPathInfo.validateElement()`: structural checks (voltage-source loop / rail-to-ground no-resistance) у recovery mode → warn + enable stabilizers, без stop.
			- `CircuitSimulator.preStampAndStampCircuit()`: тепер повертає boolean; при невдалому pre-stamp/stamp не йдемо в `runCircuit()`.
			- `CircuitDocument`/`CirSim.stepSimulation()`: якщо stamp не завершився — пропускаємо крок замість exception/stop.
			- `CircuitRenderer`: показує `warningMessage` під "Mode" коли немає фатального stopMessage.
		in_progress: []
		next:
			- DevMode regression-smoke: схеми з (1) voltage-source loops/rail-to-ground, (2) stiff switching (SCR/DIAC/TRIAC), (3) wire-heavy nets.
			- Перевірити, що немає errorMessage і що warningMessage не фрізить/не перешкоджає time advance.

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
			- Якщо симуляція виглядає “залиплою” без stop/error: перевірити tiny maxTimeStep/timeStep (наприклад 1e-8..1e-9). Підняття maxTimeStep прискорює сим-час (за умови стабільності схеми).
		episodes_to_ingest:
			- Episode: Transformer winding resistances + coupling default 0.99 + default-on auto timestep.
			- Episode: DevMode root-cause: non-convergence attributed to transistor (TRA2) driven by near-ideal transformer coupling; “stuck” traced to tiny maxTimeStep; verified runtime recovery via setMaxTimeStep().

	SIM-CONVERGENCE-RESET-NODE-MARKERS:
		facts_to_save:
			- Для атрибуції non-convergence: у кожній sub-iteration запам’ятати перший CircuitElm, після doStep() якого converged став false.
			- У stopped режимі analyze-derived draw state (наприклад postDrawList) треба оновлювати синхронно під час needAnalyze().
		episodes_to_ingest:
			- Episode: Attribute convergence to element + make reset reset elements+solver + keep post markers in sync + remove canvas error overlay.

```yaml
# Quick Resume — TRANSFORMER-WINDING-R-AUTOTIMESTEP
goal: Add winding resistances to transformers, set default coupling=0.99, and enable auto timestep adjust by default
focus_now: Verify DevMode behavior on a previously failing circuit; confirm convergence root-cause and address “stuck” due to tiny maxTimeStep
next_action: In DevMode check getSimInfo() at “stuck”; if timeStep/maxTimeStep are tiny, raise maxTimeStep (Controls or JS API setMaxTimeStep()) and re-test stability
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

---

# Quick Resume — SOLVER-NONCONVERGENCE-RECOVERY
goal: Keep simulation running (never stop) on singular/non-convergence/structural singularities via recovery + non-fatal warnings
focus_now: Validate DevMode behavior for singular matrix + FindPathInfo structural loops without triggering errorMessage/stop
next_action: Run DevMode and load a few stiff/invalid circuits; confirm time advances and only warningMessage appears (no stop)
key_files: [src/main/java/com/lushprojects/circuitjs1/client/CircuitSimulator.java, src/main/java/com/lushprojects/circuitjs1/client/FindPathInfo.java, src/main/java/com/lushprojects/circuitjs1/client/CircuitDocument.java, src/main/java/com/lushprojects/circuitjs1/client/CircuitRenderer.java]
verify_cmd: mvn -q -DskipTests=true test
last_result: success
```
