# Project Structure: CircuitJS1 Desktop Mod

## Overview

**Name:** CircuitJS1 Desktop Mod (fork of sharpie7/circuitjs1)
**Type:** Java circuit simulator compiled to JS via GWT, packaged as a desktop app
via modified NW.js. Educational tool — idealized components, not for production-grade
simulation.

**Build stack:**
- Java 17+ (source of truth — `src/main/java`)
- GWT 2.x (Java → JavaScript compiler, produces `war/circuitjs1/*`)
- Maven 3+ (`pom.xml` — orchestrates GWT compile + package)
- Node.js + npm (desktop packaging, devmode scripts, icon/test utilities)
- NW.js modified (`SEVA77/nw.js_mod`) — desktop runtime that hosts the compiled app

**Runtime targets:** Linux x32/x64, Windows x32/x64, macOS x64/arm64.

## Top-level Layout

```
circuitjs1_desktop/
├── src/main/
│   ├── java/com/lushprojects/circuitjs1/
│   │   ├── client/                    ← All application Java code (GWT-compiled)
│   │   └── public/                    ← Static assets served at runtime
│   │       └── circuits/              ← Built-in example circuits (load test corpus)
│   └── webapp/                        ← GWT module descriptor, host HTML, web.xml
├── war/                               ← GWT devmode output + runtime assets
│   ├── circuitjs1/                    ← Compiled .nocache.js + permutation files
│   ├── avr8js/, js/, scripts/, font/  ← Third-party + runtime JS/assets
│   ├── help/                          ← In-app help pages (HTML)
│   └── WEB-INF/
├── server/public/                     ← Local static server assets (devmode helper)
├── scripts/                           ← Node/bash build helpers
│   ├── devmode/                       ← Devmode launcher for NW.js
│   ├── icons/                         ← Icon generation
│   └── tests/                         ← Test runner scripts
├── tests/                             ← Test fixtures / scenarios
├── templates/                         ← Project templates
├── docs/                              ← Pre-existing hand-written docs
│   ├── project.md, elements.md, JS_API.md, EXPORT_CJS.md, EXPORT_OLD.md
│   ├── remote_dbg.md, remote_dbg_concept.md, skills_graph_concept.md
│   ├── circuit_manual_uk.md, search_test_cases.md, project_context.yaml
│   ├── context_rules/                 ← Project-context rules (pre-dev-flow)
│   └── skills_rules/                  ← Pre-existing skill definitions
├── out/                               ← Packaged desktop release output
├── target/                            ← Maven / GWT build output (site/ = devmode bundle)
├── ai_memory/                         ← Assistant memory sidecar (excluded from analysis)
├── ai_skills -> ../ai_skills          ← Symlink to shared skills library (excluded)
├── .vscode/, .idea/, .github/         ← IDE / CI metadata
├── README.md, INTERNALS.md, GEMINI.md ← High-level overviews
├── pom.xml, package.json, package-lock.json
├── .gitignore, .gitattributes, .classpath
└── .dev_flow/                         ← This onboard workspace
```

Directories excluded from analysis: `node_modules/`, `target/`, `out/`, `war/` (build
artifacts or third-party libs), `.git/`, `.idea/`, `ai_memory/`, `ai_skills/`,
`Схеми/` (user circuits), `tmp*/`, `.rlm/`.

## Application Source: `com.lushprojects.circuitjs1.client`

275 Java files organized into nine logical packages. All application logic lives
here — everything else in the tree is build/runtime glue.

| Package / location | Files | Purpose |
|---|---:|---|
| `client/` (root) | 73 | Top-level orchestration: simulator core, editor, UI shell, managers |
| `client/element/` | 146 | Circuit element implementations (components) + base classes |
| `client/element/waveform/` | 9 | Waveform generators used by voltage/signal elements |
| `client/dialog/` | 30 | All Swing-like GWT dialogs (edit, export, import, about, etc.) |
| `client/io/` | 4 | Circuit import/export framework (format registry, element factory, unit parser) |
| `client/io/text/` | 3 | Legacy text circuit format (Falstad line-based) |
| `client/io/json/` | 5 | JSON circuit format (modern) |
| `client/util/` | 3 | Locale (i18n strings), Log, PerfMonitor |
| `client/ui/tabs/` | 2 | Reusable tab widget (TabBarPanel, TabWidget) |
| `client/options/` | 0 | Empty subdirectory — no files |
| `client/ui/` | 0 | Empty (only `tabs/` subdirectory is populated) |

### `client/` root-level modules (73 files)

Logical clusters visible from names:

| Cluster | Representative files | Role |
|---|---|---|
| Simulator core | `CirSim`, `BaseCirSim`, `BaseCirSimDelegate`, `CircuitSimulator`, `CircuitMath`, `CircuitConst`, `SimulationContextAware` | Main simulation loop, numerical solver, timing |
| Editor & interaction | `CircuitEditor`, `CircuitEditorEventHandler`, `CircuitRenderer`, `MouseMode`, `MyCommand`, `ActionManager`, `MenuManager`, `Toolbar`, `ClipboardManager`, `ClipboardCallback`, `UndoManager` | Canvas interaction, commands, history |
| Circuit state | `CircuitDocument`, `DocumentManager`, `CircuitInfo`, `CircuitLoader`, `CircuitElmCreator`, `CircuitNode`, `CircuitNodeLink`, `CircuitUtils`, `FindPathInfo`, `NodeMapEntry`, `WireInfo`, `RowInfo` | Document model, netlist construction, persistence hooks |
| Options / settings | `OptionsManager`, `DisplaySettings`, `ColorSettings`, `QueryParameters` | Configuration, URL parameters |
| Scopes (oscilloscopes) | `Scope`, `ScopeManager`, `ScopePlot`, `ScopeCheckBox`, `ScopePopupMenu` | Live signal visualization |
| Dialog glue | `DialogManager`, `SliderDialog` (root-level), `ExtListEntry` | Cross-cutting dialog coordination |
| Models (non-element) | `DiodeModel`, `TransistorModel`, `CustomLogicModel`, `CustomCompositeModel` | Shared physics/parameter models referenced by multiple Elm classes |
| Adjustable / sliders | `Adjustable`, `AdjustableManager` | Parameter sliders driving element properties |
| Util-like (pre-`util/` extraction) | `Point`, `Rectangle`, `IntPair`, `Polygon`, `Color`, `Font`, `Graphics`, `CircuitMath`, `StringTokenizer`, `RandomUtils`, `FFT`, `Expr`, `ExprParser`, `ExprState`, `PlatformUtils`, `GWTUtils`, `LogManager` | Geometry, math, rendering primitives — legacy `util`-like code kept at client root |
| UI widgets | `Checkbox`, `CheckboxMenuItem`, `CheckboxAlignedMenuItem`, `Choice`, `Scrollbar` | Small GWT-wrapped controls |
| File I/O | `LoadFile`, `SRAMLoadFile`, `ImportFromDropbox` | Browser file loading / Dropbox integration |
| Diode (historical) | `Diode` | Old numerical model (distinct from `element/DiodeElm`) |
| App entry | `circuitjs1` | GWT `EntryPoint` (bootstraps the app) |

### `client/element/` (146 files)

- **Base classes:** `CircuitElm` (abstract root), `BaseCircuitElm`, `ElmGeometry`,
  `ChipElm` (multi-pin IC base), `CompositeElm` (element-of-elements base),
  `CustomCompositeElm`, `CustomCompositeChipElm`, `CustomLogicElm`, `SwitchElm`
  (switch base), `GraphicElm` (drawing-only base), `Inductor` (physics helper).
- **Concrete elements:** ~135 `*Elm` / `*GateElm` classes — every circuit
  component the simulator supports (resistor, capacitor, diode, transistor
  variants, op-amps, logic gates, audio, antenna, DAC/ADC, switches, motors,
  displays, scopes, etc.).

The catalog-style listing is already documented at [docs/elements.md](../../docs/elements.md).

### `client/element/waveform/` (9 files)

One abstract `Waveform` base plus eight concrete waveform types (AC, DC, Noise,
Pulse, Sawtooth, Square, Triangle, Var). Used by voltage-source elements.

### `client/dialog/` (30 files)

`EditDialog`, `EditInfo`, `EditOptions`, `EditCompositeModelDialog`,
`EditDiodeModelDialog`, `EditTransistorModelDialog`, `EditDialogLoadFile`,
per-format `Export*Dialog` and `Import*Dialog`, help/about dialogs,
`SubcircuitDialog`, `SliderDialog`/`SlidersDialog`, `SearchDialog`,
`ControlsDialog`, `ShortcutsDialog`, `ShowLogDialog`, `ScopePropertiesDialog`,
`UncaughtExceptionDialog`, `AboutBox`, `LicenseDialog`, `ModDialog`,
`ScrollValuePopup`. `Editable` is a marker interface. `Dialog` is the base.

### `client/io/` (4 files)

`CircuitFormat` (format interface), `CircuitFormatRegistry`,
`CircuitExporter`/`CircuitImporter` (orchestrators),
`CircuitElementFactory` (element creation from format metadata),
`UnitParser` (physical-unit string parsing).

### `client/io/text/` (3 files)

`TextCircuitFormat`, `TextCircuitExporter`, `TextCircuitImporter` — legacy
Falstad newline-delimited format (documented in
[docs/EXPORT_CJS.md](../../docs/EXPORT_CJS.md),
[docs/EXPORT_OLD.md](../../docs/EXPORT_OLD.md)).

### `client/io/json/` (5 files)

`JsonCircuitFormat`, `JsonCircuitExporter`, `JsonCircuitImporter`, plus supporting
files — modern JSON format for circuit save/load.

### `client/util/` (3 files)

- `Locale` — i18n string dictionary.
- `Log` — logging façade.
- `PerfMonitor` — profiling helper (depends on `element/BaseCircuitElm`).

### `client/ui/tabs/` (2 files)

`TabBarPanel`, `TabWidget` — reusable tab UI (only place where a formal `ui/`
layer exists so far).

## Non-Application Code

- `src/main/webapp/` — GWT module XML (`.gwt.xml`), host HTML, servlet configs.
- `src/main/java/com/lushprojects/circuitjs1/public/circuits/` — built-in circuit
  corpus served at `/circuits/...` and used for import/export roundtrip testing.
- `server/public/` — small static helper (documented in `remote_dbg_concept.md`).
- `scripts/` — build/dev automation (devmode launcher, icon generation, test
  runners).
- `tests/` — test fixtures (not a Java test suite — this is a GWT/UI-heavy
  project with manual + scripted verification).

## Testing Infrastructure

No embedded JUnit suite inside `src/test/`. Automated testing consists of:
- Build checks: `npm run check`
- Import/export roundtrips over the built-in circuits corpus
  (`scripts/tests/…`)
- Manual verification via devmode (`npm run devmode` — long-running process)

Implication for the pipeline: **Phase 5 (Test) and Phase 7 (Verify) operate
primarily on build + scripted roundtrip checks plus manual devmode runs.**
There is no unit test harness to extend with mocks.

## Pre-existing Documentation (to cross-reference, not duplicate)

| File | Relevance to onboard |
|---|---|
| [INTERNALS.md](../../INTERNALS.md) | Core simulator architecture overview |
| [docs/project.md](../../docs/project.md) | Project-level architecture notes |
| [docs/elements.md](../../docs/elements.md) | Catalog of all circuit elements |
| [docs/JS_API.md](../../docs/JS_API.md) | JS-side bridge API |
| [docs/EXPORT_CJS.md](../../docs/EXPORT_CJS.md) | Modern text export format |
| [docs/EXPORT_OLD.md](../../docs/EXPORT_OLD.md) | Legacy export format |
| [docs/remote_dbg_concept.md](../../docs/remote_dbg_concept.md) | Remote debug bridge concept |
| [docs/remote_dbg.md](../../docs/remote_dbg.md) | Remote debug runbook |
| [docs/skills_graph_concept.md](../../docs/skills_graph_concept.md) | Skills/knowledge graph concept |
| [docs/project_context.yaml](../../docs/project_context.yaml) | Machine-readable project context |
| [docs/circuit_manual_uk.md](../../docs/circuit_manual_uk.md) | End-user manual (Ukrainian) |

Generated concepts will **reference** these documents in their Dependencies and
Notes sections rather than re-stating their content.
