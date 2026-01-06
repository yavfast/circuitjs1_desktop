## Meta
- last_updated: 2025-12-28T10:45:00+02:00
- project_root: /home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop
- language: uk
- active_skills: [circuitjs1-dev-workflow, circuitjs1-ui-maintenance]
  agent_notes: Linux workspace; DevMode запускаємо через scripts/run_dev_web.sh; візуальна перевірка через Chrome DevTools MCP + docs/JS_API.md.
- recent_activity: Added `ElmGeometry.setX2/setY2`; migrated `TextElm`, `DiacElm`, `GroundElm`, `SevenSegElm`; updated CirSim JSNI to use getters; both `mvn clean compile` and `npm run buildgwt` succeeded.

## Current Task

- task_id: ELM-GEOM-REFACTOR-CTX
	goal: Форсовано завершити рефакторинг геометрії: зробити використання legacy геометричних полів неможливим і перевести весь код на `geom()`/`ElmGeometry`.
	current_focus: Continue migrating remaining direct field usages (x/y/x2/y2/point1/point2/lead1/lead2) to `geom()` getters.
	active_files: [src/main/java/com/lushprojects/circuitjs1/client/element/*]
	status: in_progress
	scope_in: Replace legacy field accesses and JSNI reads with `geom()`/getter calls; run compile + GWT build after each batch.

## Plan & References
 plan: ai_memory/elm_geom_refactor_plan.md
- related_docs:
	- ai_memory/elm_geom_refactor_inventory.md
	- docs/project.md
- related_code:
	- src/main/java/ (geometry: `CircuitElm`, `ElmGeometry`, `*Elm`)

## Progress
- done:
	- [x] Refactored: `SCRElm`, `FMElm`, `OpAmpElm`, `CustomCompositeElm`.
	- [x] **Refactored `JsonCircuitImporter.java`**:
	  - Replaced direct access to legacy fields with `setEndpoints()` and `setBbox()`.
	- [x] **Refactored `TriacElm.java`** & **`TriodeElm.java`**:
	  - Methods updated to use `geom()` API.
	- [x] **Refactored `InductorElm.java`** & **`LabeledNodeElm.java`**.
	- [x] **Refactored Waveforms**:
	  - `NoiseWaveform` and `DCWaveform` fixed to use `geom()` API.
	  - Validated other waveforms.
	- [x] Refactored previously: `ThreePhaseMotorElm`, `TransformerElm`, `TunnelDiodeElm`, `UnijunctionElm`, `CircuitElementFactory`.
	- [x] `RelayContactElm` uses `geom()` (verified).
	- [x] Added `ElmGeometry.setX2(int)` and `setY2(int)`.
	- [x] Fixed `TextElm`, `DiacElm`, `GroundElm`, `SevenSegElm` to use `geom()` / getters.
	- [x] Updated `CirSim.buildElementInfo()` JSNI to expose `getX/getY/getX2/getY2`.

	in_progress:
	- Continue migrating remaining direct field usages across elements and JSNI sites.
	- Run JS API smoke tests and visual checks in DevMode (manual).

- next:
	- Continue batch-migrating elements that still reference legacy fields.
	- Run full test-suite and smoke tests after next batch of changes.
	- Re-run `npm run buildgwt` after each batch.


## Breadcrumbs
 last_focus: "Refactoring TextElm."
- last_edit_targets: ["TextElm.java"]
 next_hops: "1) Fix ElmGeometry → 2) Compile."
 resume_recipe: "Fix TextElm compilation."

## Guardrails
- invariants:
	- HARD MODE active.

## Verification
- Build (Java): FAILED on `TextElm`.
- Build (GWT): Pending.

## Failure Context
- last_failure_time: 2025-12-28T10:43:00+02:00
- failure_type: compile
- error_signature: "cannot find symbol: method setX2(int)" in TextElm.
- failing_files: ["TextElm.java"]

## Quick Resume — ELM-GEOM-REFACTOR-CTX
```yaml
goal: "Fix TextElm Compilation"
focus_now: "Extend ElmGeometry or Fix TextElm"
last_result: "mvn compile FAILED"
files_broken: ["TextElm.java"]
```

## Archive Meta
- archived_at: 2025-12-29T11:09:51+02:00
- archived_reason: "User requested switch to CTX-SWITCHING-RULES-001"
- status: paused
