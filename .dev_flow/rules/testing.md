# Testing Rules

**Metadata**
- Scope: verification procedures for circuit-simulator changes.
- Config scanned: no JUnit/TestNG dependency in `pom.xml`; `<goal>test</goal>` on the GWT plugin is present but no test sources under `src/test/java/`. `package.json` supplies `npm run check` (build verification) and `npm run devmode` (interactive dev server). There is a `tests/` directory at the project root but it is not wired into Maven.
- Evidence source: `.dev_flow/onboard/project_structure.md`, `pom.xml:107-143`, `package.json` scripts, `layer3__simulator-core.md`.

**Status:** CircuitJS1 has no JUnit suite. Automated verification is browser-driven: `npm run test:live` (headless Chromium over the JS APIs, `tests/live/`), `npm run test:mcp` (NW.js end-to-end for the MCP server and path-based files, `tests/mcp/`) and `npm run test:mcp-unit` (node tests of the MCP server modules); the rest is build checks, manual devmode exercises and round-trip circuits. Rules in this document are therefore mostly `should`, and the file documents the verification procedure as much as prescriptive rules. It is expected to expand as automated tests are introduced.

---

## Rule: BuildCheckBeforeCommit

**Category:** testing
**Severity:** should
**Applies to:** any change that touches Java sources

### Description
Run the GWT compile before committing: `npm run buildgwt` (Maven `gwt:compile` into `target/site/`). A clean compile is the minimum bar. `mvn -o -q compile` is a faster javac-level pre-check but misses GWT translation errors. Note: `npm run check` does **not** compile — it only reports the timestamps of existing build outputs.

### Examples
```bash
npm run buildgwt   # GWT compile; must end with BUILD SUCCESS
```

### Rationale
GWT's JS-cross-compile produces opaque errors at runtime when emulation breaks — compile errors are cheaper to diagnose than runtime console messages.

---

## Rule: DevmodeVerificationForSimulatorChanges

**Category:** testing
**Severity:** should
**Applies to:** changes to `CircuitSimulator`, `CircuitElm.stamp`/`doStep`/`startIteration`/`stepFinished`, `Diode`, `Inductor`, `CircuitMath`, time-step control

### Description
For any change to simulator core (stamping, Newton loop, time-step, non-convergence recovery) run `npm run devmode` and manually exercise at minimum:
- **A non-linear analog circuit** (e.g. a diode rectifier or op-amp oscillator) — exercises Newton iteration and `Diode.limitStep`.
- **A digital circuit** (a counter, flip-flop chain, or logic-gate demo) — exercises `ChipElm.stamp`/`doStep` and voltage-source handling.
- **A subcircuit / custom composite circuit** — exercises `CompositeElm.loadComposite` and nested child routing (`domain-core__element-base.md:368-403`).

Observe: does the circuit run without `stopMessage`? Does Reset (`BaseCirSim.resetAction`, `layer3__simulator-core.md:74-77`) restore a clean state? Do the scopes show plausible waveforms?

### Rationale
Simulator changes have non-local effects. The non-convergence escalator (commit `fb4ee85`) can mask regressions by silently force-advancing time — visual inspection of a known-good circuit is the cheapest detector.

---

## Rule: LiveHarnessForIoAndEditorChanges

**Category:** testing
**Severity:** should
**Applies to:** changes to `io/**`, `CircuitElmCreator`, `CircuitElementFactory`, element `dump()`/JSON methods, `UndoManager`, `CircuitEditor` copy/paste, `CircuitElm.dumpValue`/number formatting

### Description
After `npm run buildgwt`, run `npm run test:live` (`tests/live/harness.mjs`). It drives the compiled build headlessly: undo/redo, paste/duplicate, text-format fidelity (raw file vs text export, number by number), text↔JSON roundtrip (`CIRCUITS=all` for the whole example corpus, `JSON_STATE=1` to include simulation state) and one element of every JSON type. Compare the result with the previous run; the README lists the accepted residual differences.

### Rationale
The 2026-09-30 audit found undo loading an empty circuit, paste dropping an element, JSON import resetting parameters/geometry of most element types, and a text writer rounding every value — none visible to a compile or an element-count check, all caught by this harness.

---

## Rule: NwEndToEndForMcpAndFileChanges

**Category:** testing
**Severity:** should
**Applies to:** `mcp/server/**`, the MCP start-up and status path (`AgentJsBridge.startMcpServer`, `McpServerStatus`, `dialog/McpServerDialog`, the Options menu item), `PathFileAdapter`, `agent/FileOps`, `agent/CircuitContentTest`, the Chromium arguments in `war/package.json`

### Description
After `npm run buildgwt`, run `npm run test:mcp` (`tests/mcp/e2e.mjs`, see `tests/mcp/README.md`). It launches the real NW.js SDK binary under its own Xvfb with a scratch `HOME` and profile per launch and checks the SP_MCP_05 rows (endpoint, Origin, start-up/shutdown and registry, tools and resources with the "no circuit logic" invariant, sizing, info dialog and settings restarts) and the SP_AGA file rows (real `openFile`/`saveFile`, R1/R2 `openFile` step). Run `npm run test:mcp -- slow` when request or run timeouts change, and `-- clients` (Inspector CLI, Claude Code; auto-SKIP without them) when transport or host-facing behaviour changes. Close running CircuitJS1 instances first: ports 7311..7330 and 7400 must be free. `tests/live` cannot replace it: headless Chromium has no Node, so it runs neither the server nor the file system.

### Rationale
The server, the instance registry and the file adapter run only on the NW.js embedded Node 18.0; their defects (a dropped request that hangs, a record left after exit, a staging file left behind, a background `openFile` that disturbs the visible tab) are invisible to a GWT compile, the unit tests and the browser harness.

---

## Rule: IoFormatRoundTrip

**Category:** testing
**Severity:** should
**Applies to:** changes to `io/text/*`, `io/json/*`, `CircuitElmCreator`, `CircuitElementFactory`, new `getDumpType`/`getJsonTypeName` implementations, any `dump()`/`applyJsonProperties`/`applyJsonState` change

### Description
For io-framework changes, verify text and JSON round-trips against the built-in circuits in `src/main/java/com/lushprojects/circuitjs1/public/circuits/*`:
1. Open a representative circuit in devmode.
2. Export to text format, close, reopen, confirm identical rendering and simulation behavior.
3. Repeat with JSON export/import.
4. For the subcircuit path, round-trip a circuit containing a `CustomCompositeElm`.

### Rationale
The text/JSON formats are the user's only persistence surface. A broken round-trip silently corrupts saved files and is unrecoverable once distributed.

---

## Rule: ExampleCircuitForNewElement

**Category:** testing
**Severity:** should
**Applies to:** new concrete `*Elm` classes

### Description
When adding a new element type, drop at least one example circuit that exercises it into `src/main/java/com/lushprojects/circuitjs1/public/circuits/` (the directory is GWT-public and bundled into the WAR; example circuits also serve as regression fixtures for the next person touching io-framework).

### Rationale
The example circuit becomes the de-facto acceptance test for the element: later changes that break it will be visible in devmode within seconds.

---

## Rule: UseDevmodeForEditorInteractionChanges

**Category:** testing
**Severity:** should
**Applies to:** changes to `CircuitEditor`, `CircuitRenderer`, `ActionManager`, mouse/keyboard handling, undo/redo

### Description
Interaction code can't be usefully verified by compile alone. Run `npm run devmode` and manually exercise the specific interaction: drag/select/rotate/flip/copy/paste/undo-redo on a sample circuit. Keyboard shortcuts (`getShortcut`) must place the element under the cursor. Undo-redo must round-trip through save/reset (`a488ebb`, `08f2799`).

### Rationale
Recent commits (`08f2799`, `a488ebb`) were editor-regression fixes for exactly these flows; they were not caught by static checks because the bugs were in GWT event routing.

---

## Future: AutomatedTests

When a JUnit harness is introduced, this file will expand. Candidate entry points already friendly to unit testing:
- `util/Locale`, `util/StorageHelper` — pure functions.
- `CircuitMath.lu_factor` / `lu_solve` — matrix math, no GWT dependencies.
- `Diode.limitStep`, `Diode.calculateCurrent` — pure numerical helpers.
- `CustomLogicModel.parseRules` — parser with observable output (`rulesLeft`/`rulesRight`).
- `io/text/` and `io/json/` round-trip harnesses driven from Java `String` fixtures.

`BaseCirSim.getIterCount()` stub-in-superclass (`layer3__simulator-core.md:81`, `564`) means test harnesses for the engine can subclass `BaseCirSim` and inject a fake iter count, sidestepping the GWT UI dependencies.
