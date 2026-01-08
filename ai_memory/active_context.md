## Meta
- last_updated: 2026-01-08T13:45:00+02:00
- project_root: /home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop
- language: uk
- active_skills: [circuitjs1-dev-workflow]
	agent_notes: GWT DevMode у браузері (http://127.0.0.1:8888/circuitjs.html). Сим-стан: CircuitDocument.errorMessage + CircuitSimulator.stopMessage.

## Current Task

- task_id: EDITOR-DELETE-UNDO-SHORTCUTS
	goal: Виправити 3 баги редактора: (1) видалення інколи прибирає “зайвий” елемент, (2) undo після delete може очищати всю схему, (3) після відкриття схеми одразу не працюють швидкі команди додавання елементів.
	global_context: Є мульти-таб архітектура з делегуванням подій на active document; delete/undo/shortcuts прив’язані до selection/mouse hover, undo snapshots, та фокуса canvas.
	current_focus: Зробити delete детермінованим (лише selected або один явний target), привести undo history у консистентний стан після open/load, та повернути фокус на canvas після open/tab switch.
	active_files: [src/main/java/com/lushprojects/circuitjs1/client/CircuitEditor.java, src/main/java/com/lushprojects/circuitjs1/client/UndoManager.java, src/main/java/com/lushprojects/circuitjs1/client/LoadFile.java, src/main/java/com/lushprojects/circuitjs1/client/DocumentManager.java]
	scratchpad:
		- Root-cause (1): `doDelete()` видаляв також `isMouseElm()` (hover) → при delete selected могла зникати ще одна (hovered) деталь.
		- Root-cause (2): undo stack міг містити pre-load “порожній” стан; Ctrl+Z міг повертати схему до empty. Рішення: reset+seed undo після open/load/restore.
		- Root-cause (3): keypress shortcuts не доходили без фокуса в canvas (особливо після open/tab switch). Рішення: setFocus(true) після load та при зміні active document.
	scope_in: Мінімальні зміни в delete/undo/focus; без редизайну UI.
	scope_out: Переробка всієї системи undo або повний рефактор input handling.

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
	EDITOR-DELETE-UNDO-SHORTCUTS:
		done:
			- `CircuitEditor.doDelete()`: тепер при наявності selection видаляє лише selected; якщо selection нема — видаляє максимум один елемент (menuElm або mouseElm).
			- `UndoManager`: додано `resetAndSeedFromCurrentCircuit()` для скидання undo/redo та seed поточним станом.
			- `LoadFile.doLoad()`: після open/load робить reset+seed undo і ставить фокус на canvas.
			- `DocumentManager`: після restore closed tab робить reset+seed undo; після tab switch ставить фокус на canvas (Timer).
			- `mvn -q -DskipTests=true test` — OK.
		in_progress: []
		next:
			- Ручний smoke: відкрити схему → без кліку натиснути shortcut (напр. `r`) → має перейти в Add mode; Delete selected не має чіпати hovered; Ctrl+Z після delete відновлює елемент (без очищення схеми).

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
```
