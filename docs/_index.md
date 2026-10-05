# CircuitJS1 Desktop Mod — Documentation Index

> Index of all active concepts, specifications, and plans. Generated from the
> onboard procedure (see `.dev_flow/onboard/report.md`).

## Layer 0 — Leaf utilities

| ID | Title | Status | Summary | Files |
|---|---|---|---|---|
| C_UTL | Util: Locale, Log, PerfMonitor | active | i18n table, session log buffer, and perf-counter primitives shared across all layers | [concept](util-locale-log.concept.md) · [spec](util-locale-log.sp.md) · [plan](util-locale-log.plan.md) |
| C_GEO | Geometry Primitives | active | Point, Rectangle, Polygon, IntPair — 2D integer geometry used by every renderer and hit-test | [concept](geometry.concept.md) · [spec](geometry.sp.md) · [plan](geometry.plan.md) |
| C_RND | Rendering Primitives | active | Color, Font, Graphics — thin GWT-to-canvas wrappers consumed by every element's draw() | [concept](rendering-primitives.concept.md) · [spec](rendering-primitives.sp.md) · [plan](rendering-primitives.plan.md) |
| C_MDS | Math / DSP Utilities | active | CircuitMath, StringTokenizer, RandomUtils, FFT — numeric + parsing helpers for solver and scopes | [concept](math-dsp.concept.md) · [spec](math-dsp.sp.md) · [plan](math-dsp.plan.md) |
| C_EXP | Expression Engine | active | Expr, ExprParser, ExprState — arithmetic expression compiler for custom sources and logic | [concept](expression-engine.concept.md) · [spec](expression-engine.sp.md) · [plan](expression-engine.plan.md) |
| C_PLT | Platform Bridge | active | GWTUtils (PlatformUtils deleted 2026-10-01) — thin JSNI layer isolating browser/NW.js-specific calls | [concept](platform-bridge.concept.md) · [spec](platform-bridge.sp.md) · [plan](platform-bridge.plan.md) |

## Layer 1 — Reusable UI primitives

| ID | Title | Status | Summary | Files |
|---|---|---|---|---|
| C_TAB | UI Tabs — Multi-Document Tab Bar | approved | Reusable GWT tab-bar panel that hosts the multi-document switcher | [concept](ui-tabs.concept.md) · [spec](ui-tabs.sp.md) · [plan](ui-tabs.plan.md) |
| C_LUW | Legacy UI Wrappers | approved | Checkbox, Choice, Scrollbar, CheckboxMenuItem — DOM-backed GWT controls used by dialogs | [concept](legacy-ui-wrappers.concept.md) · [spec](legacy-ui-wrappers.sp.md) · [plan](legacy-ui-wrappers.plan.md) |

## Layer 2 — Domain core & IO framework

### Element contracts and shared abstractions

| ID | Title | Status | Summary | Files |
|---|---|---|---|---|
| C_ELB | Element Base — Abstract Circuit-Element Contract | active | Abstract base unifying simulation, topology, rendering, edit, and persistence for all ~135 elements | [concept](element-base.concept.md) · [spec](element-base.sp.md) · [plan](element-base.plan.md) |
| C_WFM | Waveforms — Signal Generators | active | Strategy-pattern V(t) generators (DC, AC, Square, Pulse, Noise, Var) for voltage-source elements | [concept](waveforms.concept.md) · [spec](waveforms.sp.md) · [plan](waveforms.plan.md) |
| C_SHM | Shared Element Models | active | Named parameter catalogs (Diode, Transistor, CustomLogic, CustomComposite) shared across instances | [concept](shared-models.concept.md) · [spec](shared-models.sp.md) · [plan](shared-models.plan.md) |

### Circuit element categories (14)

| ID | Title | Status | Summary | Files |
|---|---|---|---|---|
| C_EPS | Passive Elements | draft | Resistors, capacitors, inductors, wires — linear two-terminal passives | [concept](elements-passives.concept.md) · [spec](elements-passives.sp.md) · [plan](elements-passives.plan.md) |
| C_ESRC | Source Elements | draft | Voltage/current sources driven by waveform generators and DC presets | [concept](elements-sources.concept.md) · [spec](elements-sources.sp.md) · [plan](elements-sources.plan.md) |
| C_EDS | Diode & Semiconductor Elements | draft | Diodes, Zeners, SCRs, varactors — non-linear semis using shared DiodeModel | [concept](elements-diodes-semis.concept.md) · [spec](elements-diodes-semis.sp.md) · [plan](elements-diodes-semis.plan.md) |
| C_ETR | Transistor & Tube Elements | draft | BJTs, MOSFETs, JFETs, vacuum tubes — 3/4-terminal active devices | [concept](elements-transistors.concept.md) · [spec](elements-transistors.sp.md) · [plan](elements-transistors.plan.md) |
| C_EOA | Op-Amp & Analog-Signal Elements | draft | Op-amps, VCOs, analog switches, custom-function sources | [concept](elements-opamps-analog.concept.md) · [spec](elements-opamps-analog.sp.md) · [plan](elements-opamps-analog.plan.md) |
| C_ELC | Combinational Logic Elements | draft | Gates, muxes, decoders, clock — stateless boolean combinational logic | [concept](elements-logic-combinational.concept.md) · [spec](elements-logic-combinational.sp.md) · [plan](elements-logic-combinational.plan.md) |
| C_ELS | Sequential Logic Elements | draft | Flip-flops, latches, counters, shift registers — clock-driven state elements | [concept](elements-logic-sequential.concept.md) · [spec](elements-logic-sequential.sp.md) · [plan](elements-logic-sequential.plan.md) |
| C_ECH | Complex IC Elements | draft | Full chips (555, 7-seg, RAM, UART) derived from ChipElm with pinout wiring | [concept](elements-chips.concept.md) · [spec](elements-chips.sp.md) · [plan](elements-chips.plan.md) |
| C_ESW | Switch Elements | draft | SPST/SPDT/DPDT switches, pushbuttons, relays as user-interactable topology breaks | [concept](elements-switches.concept.md) · [spec](elements-switches.sp.md) · [plan](elements-switches.plan.md) |
| C_EIO | IO, Probes, Meters, Displays | draft | Voltmeters, ammeters, labeled nodes, LEDs, 7-segment displays, logic probes | [concept](elements-io-probes-meters.concept.md) · [spec](elements-io-probes-meters.sp.md) · [plan](elements-io-probes-meters.plan.md) |
| C_EAR | Audio & RF Elements | draft | Speaker, antenna, AM/FM generators — audio-output and RF-signal elements | [concept](elements-audio-rf.concept.md) · [spec](elements-audio-rf.sp.md) · [plan](elements-audio-rf.plan.md) |
| C_EEM | Electromechanical Elements | draft | Motors, servos, thermistors, photoresistors — transducers between physics domains | [concept](elements-electromechanical.concept.md) · [spec](elements-electromechanical.sp.md) · [plan](elements-electromechanical.plan.md) |
| C_EGR | Graphic Overlay Elements | draft | Text labels, boxes, box-labels — non-electrical annotations drawn on the canvas | [concept](elements-graphic-overlay.concept.md) · [spec](elements-graphic-overlay.sp.md) · [plan](elements-graphic-overlay.plan.md) |
| C_EMG | Magnetics & Transmission Elements | draft | Transformers, coupled inductors, transmission lines — multi-port magnetic/distributed devices | [concept](elements-magnetics-transmission.concept.md) · (spec and plan pending) |

### Dialog system

| ID | Title | Status | Summary | Files |
|---|---|---|---|---|
| C_EIC | Edit-Info Contract | active | Universal parameter-editing pipeline: element exposes EditInfo rows, dialog renders them | [concept](edit-info-contract.concept.md) · [spec](edit-info-contract.sp.md) · [plan](edit-info-contract.plan.md) |
| C_IEU | Import/Export UI | active | Dialog surfaces for importing/exporting circuit text and file format selection | [concept](import-export-ui.concept.md) · [spec](import-export-ui.sp.md) · [plan](import-export-ui.plan.md) |
| C_DIN | Informational Dialogs | active | Read-only app dialogs: About, Help, License, Shortcuts, ShowLog, UncaughtException, Mod | [concept](dialog-info.concept.md) · [spec](dialog-info.sp.md) · [plan](dialog-info.plan.md) |
| C_DSP | Specialized Dialogs | active | Bespoke dialogs (Controls, CompositeModel, Scope properties, SearchDialog, Sliders, Subcircuit) | [concept](dialog-specialized.concept.md) · [spec](dialog-specialized.sp.md) · [plan](dialog-specialized.plan.md) |

### IO framework

| ID | Title | Status | Summary | Files |
|---|---|---|---|---|
| C_IOF | IO Framework | active | Pluggable circuit-format exporters/importers for text and JSON dump formats | [concept](io-framework.concept.md) · [spec](io-framework.sp.md) · [plan](io-framework.plan.md) |

## Layer 3 — Application shell

### Simulator

| ID | Title | Status | Summary | Files |
|---|---|---|---|---|
| C_SIM | Simulator Engine | active | MNA matrix builder, Newton–Raphson non-linear iteration, and time-stepping core | [concept](simulator-engine.concept.md) · [spec](simulator-engine.sp.md) · [plan](simulator-engine.plan.md) |
| C_APC | App Controller | active | `CirSim` — GWT UI shell and top-level controller wiring all subsystems together | [concept](app-controller.concept.md) · [spec](app-controller.sp.md) · [plan](app-controller.plan.md) |
| C_DOC | Document Model | active | Per-tab circuit state container and multi-tab document lifecycle | [concept](document-model.concept.md) · [spec](document-model.sp.md) · [plan](document-model.plan.md) |
| C_NET | Netlist Graph | active | Solver-facing node/link graph built from element pins each re-analyze pass | [concept](netlist-graph.concept.md) · [spec](netlist-graph.sp.md) · [plan](netlist-graph.plan.md) |

### Editor

| ID | Title | Status | Summary | Files |
|---|---|---|---|---|
| C_EDI | Canvas Editor — Interactive Editing Shell | active | Routes canvas events through a MouseMode state machine, drives drag/select/create and repaint | [concept](canvas-editor.concept.md) · [spec](canvas-editor.sp.md) · [plan](canvas-editor.plan.md) |
| C_UND | Commands & Undo — Full-Snapshot History | active | Full-circuit snapshot undo/redo stack plus a separate crash-recovery slot | [concept](commands-undo.concept.md) · [spec](commands-undo.sp.md) · [plan](commands-undo.plan.md) |
| C_MEN | Menus & Actions — Dispatch & i18n | active | Single ActionManager funnel routes menu, popup, toolbar, and keyboard commands | [concept](menus-actions.concept.md) · [spec](menus-actions.sp.md) · [plan](menus-actions.plan.md) |
| C_CLP | Clipboard — Circuit-Text Bridge | active | Bridges selection text to browser clipboard via JSNI with async-read fallback | [concept](clipboard.concept.md) · [spec](clipboard.sp.md) · [plan](clipboard.plan.md) |

### User-facing state

| ID | Title | Status | Summary | Files |
|---|---|---|---|---|
| C_USR | User Preferences — Tri-Layer Resolution | active | URL query → localStorage → defaults registry feeding display, color, and option settings | [concept](user-preferences.concept.md) · [spec](user-preferences.sp.md) · [plan](user-preferences.plan.md) |
| C_SCP | Scope Visualization — Time/Freq/XY | active | In-circuit oscilloscope: multi-trace, FFT, trigger state machine, RMS/avg statistics | [concept](scope-visualization.concept.md) · [spec](scope-visualization.sp.md) · [plan](scope-visualization.plan.md) |
| C_ADJ | Adjustable Sliders — Live Parameter Binding | active | Scrollbar widgets bound to element EditInfo fields for live parameter tuning | [concept](adjustable-sliders.concept.md) · [spec](adjustable-sliders.sp.md) · [plan](adjustable-sliders.plan.md) |

### Infrastructure

| ID | Title | Status | Summary | Files |
|---|---|---|---|---|
| C_LOG | Session Logging — Async File Writer | active | In-memory log buffer plus batched NW.js `fs.appendFile` writer with ShowLogDialog tail | [concept](session-logging.concept.md) · [spec](session-logging.sp.md) · [plan](session-logging.plan.md) |
| C_DRT | Dialog Routing — Active Dialog Router | active | Single-slot active-dialog registry and factory for 19 dialog classes | [concept](dialog-routing.concept.md) · [spec](dialog-routing.sp.md) · [plan](dialog-routing.plan.md) |
| C_FBR | Browser File Bridge | active | LoadFile/SRAMLoadFile adapters turning `<input type=file>` events into Java strings | [concept](browser-file-bridge.concept.md) · [spec](browser-file-bridge.sp.md) · [plan](browser-file-bridge.plan.md) |
| C_APE | App Entrypoint — GWT Bootstrap | active | `circuitjs1` GWT EntryPoint: install exception hook, load locale, construct CirSim | [concept](app-entrypoint.concept.md) · [spec](app-entrypoint.sp.md) · [plan](app-entrypoint.plan.md) |

### Agent automation (E_AGT)

| ID | Title | Status | Summary | Files |
|---|---|---|---|---|
| C_AGA | Agent API | draft | Transport-free agent operations: stable element IDs, grid-cell geometry, incremental edits, connectivity report, bounded runs/probes, diagnostics, commented checkpoints | [concept](agent-api.concept.md) · [spec](agent-api.sp.md) · [plan](agent-api.plan.md) |
| C_MCP | In-app MCP Server | draft | Always-on Streamable HTTP endpoint in the desktop runtime projecting the Agent API as tools/resources; per-instance registry | [concept](mcp-server.concept.md) · [spec](mcp-server.sp.md) · [plan](mcp-server.plan.md) |
| C_MCB | MCP Bridge & CLI | draft | Stdio forwarder for stdio-only hosts, instance discovery/launch, command-line client | [concept](mcp-bridge.concept.md) · [spec](mcp-bridge.sp.md) · [plan](mcp-bridge.plan.md) |
| C_AGS | Circuit Authoring Skill | draft | Agent skill: workflow checklist, references, evals | [concept](agent-skill.concept.md) · [spec](agent-skill.sp.md) · [plan](agent-skill.plan.md) |

## Epics

| ID | Title | Span |
|---|---|---|
| [E_DOMAIN_CORE](domain-core.epic.md) | Circuit elements + edit UI + serialization | C_ELB + C_WFM + C_SHM + 14 element-category concepts + C_EIC + C_IEU + C_DIN + C_DSP + C_IOF |
| [E_EDITOR](editor.epic.md) | Canvas editing + commands + menus + clipboard | C_EDI + C_UND + C_MEN + C_CLP |
| [E_SIMULATOR](simulator.epic.md) | Numerical simulation engine + document model | C_SIM + C_APC + C_DOC + C_NET |
| [E_VISUALIZATION](visualization.epic.md) | Scope, sliders, display settings | C_SCP + C_ADJ + C_USR |
| [E_AGT](agent-automation.epic.md) | AI agents drive the app over MCP | C_AGA + C_MCP + C_MCB + C_AGS |

## Spikes

- [mcp-agent-bridge.spike.md](mcp-agent-bridge.spike.md) — concluded 2026-10-01: app as MCP server (in-app Streamable HTTP + stdio bridge), JS API gaps, agent tools & skill; feeds epic E_AGT (the interview chose agent-written grid-cell coordinates over the spike's netlist-first recommendation)
- [circuit-script-language.spike.md](circuit-script-language.spike.md) — concluded 2026-10-02: a language for describing circuits, running simulations and measuring at circuit points; verdict feasible in layers — an experiment layer (SPICE-style measures, spec checks, sweeps/Monte Carlo) over the Agent API, hosted outside the app; target concept not yet created; relates to epic E_AGT

## Pre-existing hand-written docs (cross-reference)

- [INTERNALS.md](../INTERNALS.md) — core simulator architecture notes
- [project.md](project.md)
- [elements.md](elements.md) — catalog of all circuit elements
- [JS_API.md](JS_API.md) — JS bridge API
- [EXPORT_CJS.md](EXPORT_CJS.md), [EXPORT_OLD.md](EXPORT_OLD.md)
- [remote_dbg.md](remote_dbg.md), [remote_dbg_concept.md](remote_dbg_concept.md)
- [skills_graph_concept.md](skills_graph_concept.md)
- [circuit_manual_uk.md](circuit_manual_uk.md) (end-user manual, Ukrainian)
- [search_test_cases.md](search_test_cases.md)
- [project_context.yaml](project_context.yaml)

## Onboard artifacts (raw analyses — NOT the authoritative docs)

- [.dev_flow/onboard/project_structure.md](../.dev_flow/onboard/project_structure.md)
- [.dev_flow/onboard/layers.md](../.dev_flow/onboard/layers.md)
- [.dev_flow/onboard/analysis/](../.dev_flow/onboard/analysis/) — per-module analyses backing the concepts above
- [.dev_flow/onboard/report.md](../.dev_flow/onboard/report.md) — final onboard report

## Notes

- **ID collision resolved (2026-04-19):** the original `C_EDI` collision
  between `canvas-editor` and `elements-diodes-semis` was fixed by renaming
  the diodes-semis triple to `C_EDS` / `SP_EDS` / `PL_EDS`.
- **Missing spec/plan:** `elements-magnetics-transmission.sp.md` and
  `elements-magnetics-transmission.plan.md` — check whether both are
  present; flagged during Step 7 and subsequently addressed in a follow-up.
