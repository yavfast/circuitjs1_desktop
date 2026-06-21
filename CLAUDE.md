# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

CircuitJS1 Desktop Mod — an offline analog/digital circuit simulator. The simulation engine and UI are written in **Java**, cross-compiled to JavaScript by **GWT 2.12**, and packaged as a desktop app via **NW.js**. It is a fork of Paul Falstad / Iain Sharp's CircuitJS1, refactored from the original monolithic `CirSim` into a layered, multi-document architecture.

Toolchain: JDK 17+, Maven 3+, Node.js + npm.

## Build & run commands

All npm scripts delegate to `scripts/dev_n_build.js` (an interactive menu when run bare; flags select an action):

| Command | What it does | Output |
|---|---|---|
| `npm install` | Install Node deps (NW.js, nw-builder) | `node_modules/` |
| `npm run buildgwt` | GWT compile only (`mvn ... gwt:compile`) | `target/site/` |
| `npm start` | Launch NW.js SDK against `target/site/` (detached; returns immediately) | — |
| `npm run devmode` | `mvn gwt:devmode` + NW.js dev UI. Long-running. Serves/recompiles from the `war/` dir (separate from `target/site/`) | `war/` |
| `npm run build` | Full desktop package (Linux x64 by default) | `out/` |
| `npm run full` | Clean + full rebuild + package | `out/` |
| `npm run check` | Validate build steps | — |
| `npm run dev` | Interactive build/dev/package menu | — |

GWT compiles in `-draftCompile` / `DETAILED` style (fast, unobfuscated) per `pom.xml`. The GWT module is `com.lushprojects.circuitjs1.circuitjs1` (entry point `client/circuitjs1.java`, renamed to `circuitjs1`).

There is **no JUnit suite** wired into the build. Functional testing is done in-browser against the JS API — see "Testing" below.

## Architecture

### Compilation & layering
Java in `src/main/java/com/lushprojects/circuitjs1/client/` → GWT → JS. Static web assets (HTML, CSS, help, fonts, example circuits) live under `client/public/` and `war/`. The Java/JS boundary is crossed only through clustered `native` JSNI methods (entry point, `CirSim` `$wnd.CircuitJS1.*` bridge, `ClipboardManager`, `LoadFile`, `LogManager`, audio elements) — **do not sprinkle JSNI into element/domain classes.**

Package layering (enforced by convention, not tooling — see `.dev_flow/rules/architecture.md`):
- **L0** — `client/util/` + root primitives (`Point`, `Rectangle`, `Polygon`, `Graphics`, `Color`, `StringTokenizer`). Stateless.
- **L1** — root UI widgets, menu items, `ui/tabs/`.
- **L2** — domain core: `client/element/` (155 element classes), `client/dialog/`, shared `*Model` classes, `client/io/` (+ `io/text/`, `io/json/`).
- **L3** — app shell: `CirSim`, `BaseCirSim`, `CircuitDocument`, the `*Manager` classes, entry point.

New code must not import downstream layers. `element/` reaches managers **only** through `CircuitDocument`, never by importing L3 managers directly. Three known layer inversions are documented and frozen — don't add a fourth.

### Document / session scopes
The key structural idea after refactoring:

- **`BaseCirSim` / `CirSim`** — one per app session (GWT module). Owns session-scoped managers: `logManager`, `dialogManager`, `menuManager`, `documentManager`, `clipboardManager`, `actionManager`, `displaySettings`, the renderer, and global model catalogs (`DiodeModel.modelMap`, etc.). `CirSim` is the UI shell (`extends BaseCirSim`); `BaseCirSim.bindDocument()` swaps the active document.
- **`CircuitDocument`** — one per open tab/circuit. Owns document-scoped state: the element list, `simulator` (`CircuitSimulator`), `circuitEditor` (`CircuitEditor`), `scopeManager`, `undoManager`, adjustables, log buffer.
- **`BaseCirSimDelegate`** — base class for the managers/sub-systems. Provides delegated accessors (`simulator()`, `renderer()`, `circuitEditor()`, `undoManager()`, etc.) that resolve through the active `CircuitDocument`. Most subsystems extend this.

Putting state in the wrong scope is a real bug: catalog-style state on a document leaks-or-dies across tab switches; per-circuit state on the session leaks across documents.

### Simulation engine
`CircuitSimulator` builds and solves the circuit. The math is modified nodal analysis: a matrix `A x = B` per node, with extra rows for voltage sources, current-source models for capacitors/inductors (numerical integration — backward Euler or trapezoidal), and Newton iteration with voltage-step limiting for nonlinear devices (diodes, transistors). LU factorization is reused across timesteps when the circuit is fully linear. Each element implements `stamp()` (build matrix, called at analysis time) and `doStep()` (per-timestep update + convergence check). **Read `INTERNALS.md` before touching solver code.**

### Elements & factories
Every component subclasses `element/CircuitElm` (abstract, `extends BaseCircuitElm implements Editable`). Geometry goes through `ElmGeometry` via `geom()` ("HARD MODE": endpoints are mutated only through `geom()`, never direct field writes).

Element instances are constructed **only** through factories so save/load/undo/composite-rebuild all work:
- **`CircuitElmCreator`** — legacy text format, dispatches on `getDumpType()` (a `char`).
- **`CircuitElementFactory`** — JSON format, dispatches on `getJsonTypeName()`.

Don't `new SomeElm(...)` outside these factories, test fixtures, or editor placement code.

Element parameter UI is exposed via the `Editable` contract — `getEditInfo(n)` / `setEditValue(n, ei)` returning `EditInfo`. Elements open dialogs through `circuitDocument.getDialogManager().showEditElementDialog(this)`; they must **not** `new` a concrete `*Dialog`. `DialogManager` is a single-slot router for the currently-open dialog (bypassing it breaks key-handling/`dialogIsShowing()`).

Reusable, named, serializable parameter sets (diode physics, transistor Gummel-Poon coefficients, custom logic truth tables, composite subcircuits) become a `*Model` class with a global registry; one-off instance values stay as element fields.

### File formats
`io/CircuitFormatRegistry` is the single dispatch point. Concrete formats (`io/text/TextCircuitFormat`, `io/json/JsonCircuitFormat`) **self-register via a static initializer** — the registry never imports them. The text format is the legacy `.txt` "dump" encoding (default, `DEFAULT_FORMAT_ID = "text"`); JSON is the modern self-describing schema. See `docs/EXPORT_OLD.md` and `docs/EXPORT_CJS.md`.

## Testing

Built-in example circuits live in `src/main/java/com/lushprojects/circuitjs1/client/public/circuits` (served at `/circuits/...`). They are the baseline corpus for manual checks and import/export roundtrip tests.

`tests/json_roundtrip_test.js` loads each example as text → export JSON → clear → import JSON → export text → diff. It runs in the browser DevTools console (`await runJsonRoundtripTests()`) or via remote-debug automation, driving the app through its JS API. The JS automation API (import/export, simulation control, scopes, logs) is documented in `docs/JS_API.md`. The remote-debug harness for NW.js is in `server/` (`remote-debug-server.js`, `scripts/open_web_dbg.sh`).

## Conventions

- **All code comments in English.** Match the existing file's style, naming, and comment density.
- `AI_TODO:` / `AI_THINK:` markers in code are in-progress notes from prior AI sessions — treat `AI_*` comments as high-priority context and remove them once the work they describe is done.
- Don't change or delete code unrelated to the current task; verify new code doesn't break existing behavior.

## Further reading (existing project docs)

This repo already carries extensive structured documentation — consult it before reverse-engineering:
- `docs/project.md`, `docs/elements.md`, `docs/JS_API.md`, `docs/EXPORT_CJS.md`, `docs/EXPORT_OLD.md`, `docs/circuit_manual_uk.md`.
- `docs/*.concept.md` / `*.sp.md` / `*.plan.md` — per-feature concept→spec→plan pipeline (the `dev-flow` workflow).
- `.dev_flow/rules/` — codified rules for architecture, structure, naming, style, error-handling, testing (with severities and rationale). `.dev_flow/onboard/` holds the dependency graph, layer analysis, and known issues.
- `INTERNALS.md` — simulation theory.
