# Implementation Plan: Agent API  {#PL_AGA}

> **Code:** PL_AGA
> **Status:** draft
> **Created:** 2026-10-01
> **Updated:** 2026-10-01
>
> **Concept:** [C_AGA](./agent-api.concept.md)
> **Specification:** [SP_AGA](./agent-api.sp.md)
> **Depends on:** none for implementation; Phase 9 and Phase 10 close only after [PL_MCP Phase 4](./mcp-server.plan.md#PL_MCP_P4) has run their checks in the NW.js harness
> **Used by:** [PL_MCP](./mcp-server.plan.md), [PL_AGS](./agent-skill.plan.md)
>
> Builds the transport-free Agent API in Java (GWT): a new `client/agent/` package, the changes it needs in documents, undo, IDs, importers, the simulator and the renderer, and one JS export the MCP server calls. It opens with a prototype that closes SP_AGA_DEC_04, then works bottom-up from identity to runs and files.

## Goal

Restating the task intent ([task_E_AGT](../.dev_flow/tasks/task_E_AGT.md)) for this plan's scope: AI agents must be able to create, edit, inspect, simulate, measure, debug and checkpoint circuits in any open document through one typed operation set.

When this plan is complete:
- every SP_AGA contract is callable from the page's JavaScript and returns the OperationResult of SP_AGA_01_08;
- element IDs are stable;
- background documents can be driven without disturbing the active tab;
- the live harness proves the SP_AGA_05 criteria.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Language / level | Java 17 under GWT 2.12 emulation | Project standard (RULE_STYLE_001, RULE_STYLE_002: no reflection, `java.io`, threads) |
| Package | `com.lushprojects.circuitjs1.client.agent` (layer L3) | The Agent API orchestrates documents, undo and the simulator, so it is app-shell code; it must not be imported by L0–L2 (RULE_ARCH_001) |
| Document-scoped state | Fields on `CircuitDocument`: the element ID registry (a core class at client root, used by user paths too), plus agent-package objects (transaction, open marks, solver event list, busy flag, handle) | RULE_ARCH_006: per-document state lives on `CircuitDocument` |
| Layering of shared types | `ElementIdRegistry` at client root behind `CircuitDocument` methods (`nextElementId`, `raiseIdCounter`, `resetElementIds`); the import report collector `io/ImportReport.java` in `io/`; `agent/` only consumes them | RULE_ARCH_001/002: `io/` (L2) and user paths must not import `agent/` (L3) |
| Session-scoped state | Catalogue cache, document-handle counter, log sequence counter on `BaseCirSim`/`LogManager` | RULE_ARCH_006 |
| Result values | `com.google.gwt.json.client` (`JSONObject`/`JSONArray`) built in Java, serialized to a JSON string | Already used by `io/json`; one value type across the boundary |
| JS boundary | One new JSNI adapter `agent/AgentJsBridge.java` exporting `window.CircuitJS1Agent = {call(op, argsJson) → resultJson, callAsync(op, argsJson, callback), reportError(message)}`; installed from `CirSim.setupJSInterface()`; entry points wrapped with `$entry`, so a Java exception reaches the global handler and the call returns `undefined`, which callers map to `internal_error` | RULE_ARCH_008: one clustered adapter; string JSON avoids JSNI object marshalling; RULE_ERR_004 |
| Client-root additions | `ElementIdRegistry` (document-scoped state used by user paths and `io/`), `PathFileAdapter` (placed beside `LoadFile` by SP_AGA_03_09) | Not stateless utilities, so RULE_STRUCT_007 (utilities into `util/`) does not apply; each is reachable from L2 without an `agent/` import |
| Path file I/O | One new JSNI adapter `PathFileAdapter.java` at client root, beside `LoadFile` (SP_AGA_03_09), using `$wnd.nw.require('fs')`/`('path')` | RULE_ARCH_008; the existing `LogManager` uses the same NW Node access; the only other JSNI file of this plan |
| Element creation | `CircuitElementFactory` (JSON) for `add` and AgentCircuit import; `CircuitLoader`/format registry for text and JSON import | RULE_ARCH_005 |
| Geometry writes | Through `geom()` / `setEndpoints` + `setPoints` only | RULE_STRUCT_002 |
| Logging | `CirSim.console` / `LogManager` only | RULE_STYLE_008 |
| Exceptions | Batch-rollback guard re-reports via `GWT.reportUncaughtException` | RULE_ERR_004 (SP_AGA_03_10) |
| Tests | Extend `tests/live/harness.mjs` with `agent_*` scenarios calling `CircuitJS1Agent` over CDP | No JUnit in the build; the harness is the project's verification tool (RULE_TEST_006) |

## Required Knowledge

| Kind | Ref | Applies to | Note |
|------|-----|-----------|------|
| rule | RULE_ARCH_001/002 | all | `agent/` is L3; no new layer inversions |
| rule | RULE_ARCH_005 | P4 | factories only |
| rule | RULE_ARCH_006 | P1, P2, P6, P7 | per-document vs session state |
| rule | RULE_ARCH_008 | P1, P9 | JSNI only in `AgentJsBridge` and `PathFileAdapter` |
| rule | RULE_ARCH_009/010 | P3 | catalogue type names are factory keys; conditional property keys round-trip |
| rule | RULE_STRUCT_002 | P4 | geometry through `geom()` |
| rule | RULE_STRUCT_010 | P4 | adjustables via `AdjustableManager` |
| rule | RULE_STYLE_002, RULE_STYLE_008, RULE_STYLE_010 | all | GWT emulation; logging; lossless numbers |
| rule | RULE_ERR_004 | P4, P7 | exception reporting |
| rule | RULE_TEST_001, RULE_TEST_003, RULE_TEST_005, RULE_TEST_006 | every phase | `npm run buildgwt`; round-trip; devmode check of editor/undo; `npm run test:live` |
| skill (apply) | automation/js-api-surface | all | current pitfalls of the existing API |
| skill (apply) | automation/agent-mcp-surface | P0, P8 | background-document coupling, recovery mode |
| skill (apply) | io/json-format, io/text-format | P2, P4 | importer/exporter quirks |
| skill (apply) | editor/undo-snapshot-model | P2, P6 | full-snapshot undo |
| skill (apply) | simulator/time-step-control, simulator/newton-raphson-loop | P5, P7 | stepping, recovery |
| skill (apply) | gwt/jsni-patterns, gwt/build-pipeline | P1, P9 | JSNI and build |
| skill (update) | automation/js-api-surface | P2, P10 | record the new ID scheme and the `CircuitJS1Agent` export |
| skill (create) | automation/background-documents | P0 | the chosen mechanism and its pitfalls, if the prototype yields non-obvious findings |

## Progress

- [ ] [Phase 0 — Background-document prototype (closes SP_AGA_DEC_04)](#PL_AGA_P0)
- [ ] [Phase 1 — Foundations: results, documents, JS export](#PL_AGA_P1)
- [ ] [Phase 2 — Element identity and pin names](#PL_AGA_P2)
- [ ] [Phase 3 — Catalogue](#PL_AGA_P3)
- [ ] [Phase 4 — Geometry, edits and import](#PL_AGA_P4)
- [ ] [Phase 5 — Connectivity, readings and diagnostics](#PL_AGA_P5)
- [ ] [Phase 6 — Transactions and history](#PL_AGA_P6)
- [ ] [Phase 7 — Runs, probes and simulation control](#PL_AGA_P7)
- [ ] [Phase 8 — Background-document completion and render](#PL_AGA_P8)
- [ ] [Phase 9 — Path-based files](#PL_AGA_P9)
- [ ] [Phase 10 — Documentation propagation](#PL_AGA_P10)

## Phases

### Phase 0 — Background-document prototype [TODO]  {#PL_AGA_P0}

**Depends on:** none
**Implements:** resolution of [SP_AGA_DEC_04](./agent-api.sp.md#SP_AGA_DEC_04)
**Verify:** the reduced R1/R2 sequence of "What to do" below, observed by hand in devmode against the R1 list and the R2 state fields of [SP_AGA_05_02](./agent-api.sp.md#SP_AGA_05_02); the full rows are proven in Phases 8 and 9

What to do:
- On a scratch branch `proto/agent-bg-doc`, implement the minimum to exercise option A (scoped silent bind):
  - a `withDocumentScope(doc, op)` helper that saves the active tab's UI state through the existing per-tab save/restore of `DocumentManager`;
  - it binds the target through `BaseCirSim.bindDocument()` without listener notifications or repaint, runs `op`, then rebinds and restores.
- Run the R1 sequence with the active tab free-running: a text import, an undo, a 2 s stepping loop in 20 ms slices, and an export. Observe:
  - the sliders dialog;
  - the menu check items;
  - the bars;
  - the hint;
  - the view;
  - the active tab's simulated-time rate.
- R2 check: run the same reduced sequence on an identical document Y while Y is the active tab, and compare X and Y on circuit text, saved UI state, view transform, adjustables, hint, title, modified flag and file path.
- If option A shows a sliders-dialog rebuild or a rate below 50 %, try option C: A for load/undo/export/render, and direct stepping of the target simulator for runs.
- Record the outcome in [PL_AGA_DEC_01](#PL_AGA_DEC_01) and close SP_AGA_DEC_04 (spec record → `resolved`).
- Discard the branch after the decision. Only the decision and findings carry over.

### Phase 1 — Foundations: results, documents, JS export (`client/agent/`) [TODO]  {#PL_AGA_P1}

**Depends on:** Phase 0
**Implements:** [SP_AGA_03_08](./agent-api.sp.md#SP_AGA_03_08) mechanism (per PL_AGA_DEC_01), [SP_AGA_01_02](./agent-api.sp.md#SP_AGA_01_02) (handles), [SP_AGA_01_07](./agent-api.sp.md#SP_AGA_01_07), [SP_AGA_01_08](./agent-api.sp.md#SP_AGA_01_08), [SP_AGA_02](./agent-api.sp.md#SP_AGA_02) common rules and class table, [SP_AGA_02_02](./agent-api.sp.md#SP_AGA_02_02)
**Verify:** [SP_AGA_05_01](./agent-api.sp.md#SP_AGA_05_01) rows `listDocuments / createDocument`, `closeDocument` (unsaved, last document); invariant "Background operations never switch tabs" for `createDocument` — plus: `CircuitJS1Agent.call("listDocuments","{}")` returns valid JSON in the live harness

What to create:
| Entity | Module | Purpose |
|--------|--------|---------|
| AgentApi | `client/agent/AgentApi.java` | Dispatcher: op name → contract handler; common rules (document resolution, argument ranges → `invalid_value`, busy check per class table, `not_ready`) |
| OperationResult, Issue, IssueCode | `client/agent/OperationResult.java`, `Issue.java`, `IssueCode.java` | Result model, 50-issue cap with `truncatedIssues`, issue key computation |
| AgentArgs | `client/agent/AgentArgs.java` | Typed reading/validation of JSON arguments (ranges, enums, cardinality) |
| DocumentHandles | `client/agent/DocumentHandles.java` + field on `CircuitDocument` | `d<n>` assignment at creation (session counter on `BaseCirSim`) |
| DocumentsOps | `client/agent/DocumentsOps.java` | listDocuments / createDocument / activateDocument / closeDocument through `DocumentManager` |
| AgentJsBridge | `client/agent/AgentJsBridge.java` | JSNI export of `CircuitJS1Agent`; installed from `CirSim.setupJSInterface()` |
| DocumentScope | `client/agent/DocumentScope.java` | The one entry through which every contract touches its target document, implementing the mechanism PL_AGA_DEC_01 selected; Phases 2–7 use it from the start, Phase 8 completes the session-coupled path list and adds render |

Notes:
- `CircuitJS1Agent` is separate from the existing `CircuitJS1` global, whose methods stay as they are (SP_AGA_06_01).
- The live harness gets a helper `agentCall(op, args)` and an `agent_docs` scenario.

### Phase 2 — Element identity and pin names [TODO]  {#PL_AGA_P2}

**Depends on:** Phase 1
**Implements:** [SP_AGA_03_02](./agent-api.sp.md#SP_AGA_03_02), [SP_AGA_04_03](./agent-api.sp.md#SP_AGA_04_03), undo extension field `elementIds` of [SP_AGA_01_10](./agent-api.sp.md#SP_AGA_01_10)
**Verify:** through existing paths only (the agent contracts arrive later): (a) load a text example, user-undo/redo with Ctrl+Z/Ctrl+Y in the live harness, and compare `CircuitJS1.getElementIds()` before/after; (b) JSON export of a loaded example has keys equal to `CircuitJS1.getElementIds()`; (c) JSON import of `R1..R5`, Ctrl+Z, Ctrl+Y, then a UI-placed resistor gets `R6`; (d) `npm run test:live` roundtrip and undo scenarios unchanged. The SP_AGA_05 rows that need agent contracts are verified in Phases 3, 4, 6 and 9

What to change:
| Entity | Module | Change |
|--------|--------|--------|
| ElementIdRegistry | `ElementIdRegistry.java` at client root (owned by `CircuitDocument`, replacing `elementTypeCounters`; reached through `CircuitDocument.nextElementId`/`raiseIdCounter`/`resetElementIds`) | Letters-only prefixes, counter raise on every incoming ID, skip present IDs, reset on content replacement |
| `CircuitElm.getIdPrefix` default | `element/CircuitElm.java` | Letters only |
| JSON exporter keys | `io/json/JsonCircuitExporter.java` | Keys from `getElementId()`; remove `generateElementId` counter |
| JSON importer keys | `io/json/JsonCircuitImporter.java` | Invalid or duplicate keys regenerated with `ids_regenerated` |
| Undo snapshot IDs | `UndoManager.java` (`UndoItem`) | `elementIds` captured on push, restored after `loadUndoItem`, counters raised |
| Text load IDs | `CircuitLoader` / `ImportLifecycle` | Deterministic generated IDs in file order after counter reset |
| PinNames | `client/agent/PinNames.java` | Sanitize, `pin<i>`, `_<k>` suffix (consumed by the catalogue and agent records; the file formats keep their own pin names) |

### Phase 3 — Catalogue [TODO]  {#PL_AGA_P3}

**Depends on:** Phase 2
**Implements:** [SP_AGA_02_01](./agent-api.sp.md#SP_AGA_02_01), [SP_AGA_01_05](./agent-api.sp.md#SP_AGA_01_05), conditional key source of [SP_AGA_03_03](./agent-api.sp.md#SP_AGA_03_03)
**Verify:** [SP_AGA_05_01](./agent-api.sp.md#SP_AGA_05_01) rows `listTypes`, `describeType` (Resistor, NPN, Capacitor conditional keys, flip-flop pin names, alias, unknown); [SP_AGA_05_02](./agent-api.sp.md#SP_AGA_05_02) "Pin names unique" — plus: catalogue type count equals the number of distinct canonical JSON type names produced by the factory

What to create / change:
| Entity | Module | Purpose |
|--------|--------|---------|
| Catalogue | `client/agent/Catalogue.java` (session cache on `BaseCirSim`) | Measure every factory key at grid 16, dedupe by canonical name, aliases independent of registry order, label/slider hints by unique value match |
| Conditional property declaration | new method `getJsonConditionalProperties()` (default empty) on `element/CircuitElm.java`, overridden by each element that exports keys only when non-default | Complete key set (SP_AGA_03_03) |

Notes:
- Find the elements with conditional keys by searching `getJsonProperties` implementations for guarded `props.put`. CapacitorElm is a known one. Each override lists the key with its default.

### Phase 4 — Geometry, edits and import [TODO]  {#PL_AGA_P4}

**Depends on:** Phase 3
**Implements:** [SP_AGA_01_01](./agent-api.sp.md#SP_AGA_01_01), [SP_AGA_01_03](./agent-api.sp.md#SP_AGA_01_03), [SP_AGA_01_04](./agent-api.sp.md#SP_AGA_01_04), [SP_AGA_01_12](./agent-api.sp.md#SP_AGA_01_12), [SP_AGA_02_03](./agent-api.sp.md#SP_AGA_02_03), [SP_AGA_02_04](./agent-api.sp.md#SP_AGA_02_04), [SP_AGA_02_05](./agent-api.sp.md#SP_AGA_02_05), `exportCircuit` of [SP_AGA_02_14](./agent-api.sp.md#SP_AGA_02_14), [SP_AGA_03_01](./agent-api.sp.md#SP_AGA_03_01), [SP_AGA_03_03](./agent-api.sp.md#SP_AGA_03_03), [SP_AGA_03_04](./agent-api.sp.md#SP_AGA_03_04), [SP_AGA_03_10](./agent-api.sp.md#SP_AGA_03_10), agent-origin suppression of undo pushes ([SP_AGA_04_01](./agent-api.sp.md#SP_AGA_04_01) "Agent origin")
**Verify:** [SP_AGA_05_01](./agent-api.sp.md#SP_AGA_05_01) rows `importCircuit` (off-lattice, round trip, broken text, legacy round trip), `applyEdits` (add+set, partial set, invalid in batch, move by, move by off lattice, describe, add/removeScope, delete in scope); [SP_AGA_05_01](./agent-api.sp.md#SP_AGA_05_01) row `exportCircuit` json keys; [SP_AGA_05_02](./agent-api.sp.md#SP_AGA_05_02) "`ok=false` ⇒ document unchanged" (circuit text, IDs, open marks), "One ID scheme", "Grid preference of other tabs has no effect" (posts only; the undo/redo part in Phase 6); [SP_AGA_05_03](./agent-api.sp.md#SP_AGA_05_03) "Legacy circuit inspection"; [SP_AGA_05_04](./agent-api.sp.md#SP_AGA_05_04) batch size, supplied ID matching generated form, scope slots, single-post orientation, legacy coordinates — plus: a forced exception inside a batch (debug hook in a devmode build) restores the snapshot, returns `internal_error` and reaches the global handler (SP_AGA_03_10); `npm run test:live` roundtrip/textfid unchanged (RULE_TEST_003)

What to create / change:
| Entity | Module | Purpose |
|--------|--------|---------|
| CellGeometry | `client/agent/CellGeometry.java` | Cells↔px, lattice checks (0.5 for edits, 1/16 for import), grid pin to the document option |
| AgentCircuitConverter | `client/agent/AgentCircuitConverter.java` | AgentCircuit/ElementRecord → JSON v2 (`_startpoint`/`_endpoint`) |
| EditOps | `client/agent/EditOps.java` | Batch validation model and application of all `applyEdits` ops; `set` merge-then-apply with full read-back |
| CircuitView | `client/agent/CircuitView.java` | `getCircuit` records, ordering, paging, concise detail; `exportCircuit` (text/JSON content through the existing exporters — the non-file contract of SP_AGA_02_14) |
| Snapshot | `client/agent/DocumentSnapshot.java` | Capture/restore circuit text, IDs, open marks, scopes, view transform, UI state; model-catalogue entries touched by a text import |
| Importer reporting | `io/ImportReport.java` (new, L2) + `io/text/TextCircuitImporter.java`, `io/json/JsonCircuitImporter.java`, `io/ImportLifecycle` | Report skipped/failed/adjusted items with codes to an `ImportReport` the caller passes (SP_AGA_03_04 table) |
| Agent-origin flag | `UndoManager.java` + flag on `CircuitDocument` | `pushUndo` is suppressed while an agent mutation runs, so reused editor paths add no undo entry |
| Open marks | `client/agent/OpenMarks.java` + field on `CircuitDocument` | Set of PostRefs |
| Scope by element | `ScopeManager.java` | Public add/remove-by-element used by `addScope`/`removeScope` |

Notes:
- Results of mutating contracts carry `connectivity` once Phase 5 provides it and `transaction` once Phase 6 provides it; in this phase both fields are absent and are verified in those phases. Agent edits of this phase add no undo entries; the transaction entry arrives in Phase 6. All of this lives on the feature branch and is not released before Phase 6.
- `set` that changes the canonical type keeps the ID (SP_AGA_02_04 step 4).

### Phase 5 — Connectivity, readings and diagnostics [TODO]  {#PL_AGA_P5}

**Depends on:** Phase 4
**Implements:** [SP_AGA_01_06](./agent-api.sp.md#SP_AGA_01_06), [SP_AGA_01_11](./agent-api.sp.md#SP_AGA_01_11), [SP_AGA_02_06](./agent-api.sp.md#SP_AGA_02_06), [SP_AGA_02_07](./agent-api.sp.md#SP_AGA_02_07), [SP_AGA_02_11](./agent-api.sp.md#SP_AGA_02_11), [SP_AGA_03_05](./agent-api.sp.md#SP_AGA_03_05), [SP_AGA_03_06](./agent-api.sp.md#SP_AGA_03_06), [SP_AGA_03_07](./agent-api.sp.md#SP_AGA_03_07) connectivity caps
**Verify:** [SP_AGA_05_01](./agent-api.sp.md#SP_AGA_05_01) rows `importCircuit` RC divider in cells (`connectivity.errorCount = 0`), `applyEdits` markOpen, dangling wire end, post on wire body, delta cap; `getConnectivity` (netFilter, labelled ground, parallel wires, reserved label); `read` unknown label; `getDiagnostics` log cursor; [SP_AGA_05_02](./agent-api.sp.md#SP_AGA_05_02) "No 0-V fallback", "Labels are per document" — plus: connectivity of all bundled examples (`CIRCUITS=all`) completes without exceptions

What to create / change:
| Entity | Module | Purpose |
|--------|--------|---------|
| Connectivity | `client/agent/Connectivity.java` | Nets from the document's analysed nodes, naming (incl. `label:` escaping), geometric issue rules, delta with caps |
| Readings | `client/agent/Readings.java` | `read`, probe target resolution (shared with Phase 7) |
| Solver events | `CircuitSimulator.java` (`warn`, `stop`, forced-step branch) + `client/agent/SolverEvents.java` on `CircuitDocument` | Keep the untranslated key; per-document event list cleared at analysis start; `convergence_failed` event under recovery |
| Log sequence | `LogManager.java` | `seq` per entry; `getLogsSince(seq, limit)` with gap detection |
| Diagnostics | `client/agent/DiagnosticsOps.java` | `getDiagnostics` |

### Phase 6 — Transactions and history [TODO]  {#PL_AGA_P6}

**Depends on:** Phase 5
**Implements:** [SP_AGA_01_10](./agent-api.sp.md#SP_AGA_01_10), [SP_AGA_02_12](./agent-api.sp.md#SP_AGA_02_12), [SP_AGA_02_13](./agent-api.sp.md#SP_AGA_02_13), [SP_AGA_04_01](./agent-api.sp.md#SP_AGA_04_01)
**Verify:** [SP_AGA_05_01](./agent-api.sp.md#SP_AGA_05_01) rows `checkpoint` (named, nothing), `undo / redo`, `undo` nothing, `restoreCheckpoint`; [SP_AGA_05_02](./agent-api.sp.md#SP_AGA_05_02) "IDs survive undo/redo", "No duplicate IDs after restore" (through the agent contracts), "Grid preference of other tabs has no effect" (undo/redo part), "`ok=false` ⇒ document unchanged" (no undo entry added by a rejected batch), "A user edit never merges into an agent entry", "Agent-origin pushes never auto-seal"; [SP_AGA_05_03](./agent-api.sp.md#SP_AGA_05_03) "User undoes agent work"; [SP_AGA_05_04](./agent-api.sp.md#SP_AGA_05_04) idle seal, checkpoint after ID-only change — plus: existing `undo` and `paste` live scenarios unchanged; manual devmode check of undo menu labels (RULE_TEST_005)

What to create / change:
| Entity | Module | Purpose |
|--------|--------|---------|
| AgentTransaction | `client/agent/AgentTransaction.java` + field on `CircuitDocument` | State machine of SP_AGA_04_01, seed-entry reuse, idle timer (GWT `Timer`, 300 s) |
| Undo extension | `UndoManager.java`, `CircuitEditor.doUndo/doRedo` | Extension fields, move rules between stacks, seal hook before user pushes, user undo/redo seal |
| User-save seal | `CirSim` save paths (`nodeSave`/`nodeSaveAs` callers in `ActionManager`) | Seal the open transaction before a user save |
| Menu labels | `MenuManager.java` / undo menu items | `Undo: <comment>` / `Redo: <comment>` |
| HistoryOps | `client/agent/HistoryOps.java` | checkpoint, getHistory, undo, redo, restoreCheckpoint; modified flag |

### Phase 7 — Runs, probes and simulation control [TODO]  {#PL_AGA_P7}

**Depends on:** Phase 6
**Implements:** [SP_AGA_01_09](./agent-api.sp.md#SP_AGA_01_09), [SP_AGA_02_09](./agent-api.sp.md#SP_AGA_02_09), [SP_AGA_02_10](./agent-api.sp.md#SP_AGA_02_10), [SP_AGA_03_07](./agent-api.sp.md#SP_AGA_03_07) decimation, [SP_AGA_04_02](./agent-api.sp.md#SP_AGA_04_02)
**Verify:** [SP_AGA_05_01](./agent-api.sp.md#SP_AGA_05_01) rows `simControl` (configure, invalid), `run` (span, settle, shorted source, forced non-convergence, budget, points cap); [SP_AGA_05_03](./agent-api.sp.md#SP_AGA_05_03) "Run owns stepping", "User interrupts a run", "Free-running during edits"; [SP_AGA_05_04](./agent-api.sp.md#SP_AGA_05_04) closing during run, settle never reached — plus: RULE_TEST_002 devmode check (analog, digital, subcircuit example) after the stepping changes; a forced exception inside a slice (debug hook in a devmode build) ends the run with `internal_error` and the callback fires

What to create / change:
| Entity | Module | Purpose |
|--------|--------|---------|
| RunController | `client/agent/RunController.java` | Async run with 20 ms slices (`Scheduler.scheduleFixedDelay`/zero-delay `Timer`), budget, settle, cancel requests, finally-clear busy; every slice is guarded: an exception ends the run with `solver_stop` + `internal_error`, is passed to `GWT.reportUncaughtException`, and the completion callback is always invoked exactly once |
| ProbeRecorder | `client/agent/ProbeRecorder.java` | Streaming time-weighted stats, bucket decimation, 6-digit output |
| Stepping without repaint | `CircuitSimulator.java` | A single-step entry used by runs (no repaint, no wall-clock pacing) |
| Free-run skip | `CircuitDocument.SimulationLoop` | Skip a busy document |
| Cancel hooks | `CircuitEditor` (user edits), `UndoManager`/`CircuitEditor.doUndo/doRedo`, `ActionManager` run/stop/reset commands, `DocumentManager.closeDocument`, tab close | Raise a cancel request on a busy document and defer the user action to the run's end at the next slice boundary |
| SimControlOps | `client/agent/SimControlOps.java` | run/stop/reset/configure |

### Phase 8 — Background-document completion and render [TODO]  {#PL_AGA_P8}

**Depends on:** Phase 7
**Implements:** [SP_AGA_03_08](./agent-api.sp.md#SP_AGA_03_08), [SP_AGA_02_08](./agent-api.sp.md#SP_AGA_02_08)
**Verify:** [SP_AGA_05_02](./agent-api.sp.md#SP_AGA_05_02) R1 and R2 without the `openFile` step (that step in Phase 9), "Background operations never switch tabs"; [SP_AGA_05_01](./agent-api.sp.md#SP_AGA_05_01) rows `run` background document, `render` png background; [SP_AGA_05_03](./agent-api.sp.md#SP_AGA_05_03) "Agent builds while user watches" — plus: manual devmode observation of the active tab during the R1 sequence

What to implement:
- Audit every session-coupled path listed in SP_AGA_03_08 against `DocumentScope` (created in Phase 1) and close the gaps the R1/R2 checks reveal: exporters' options, load reset/options, adjustables, hint, centring, undo view, analysis request, stop state, modified/title/path setters, console routing, closed-tab dump, rendering.
- Offscreen render: draw the document into a detached canvas sized to the circuit bounds plus a 1-cell margin. SVG goes through `canvas2svg.js`, preloaded asynchronously, with a load failure reported as `render_failed` and no alert.

### Phase 9 — Path-based files [TODO]  {#PL_AGA_P9}

**Depends on:** Phase 8
**Implements:** [SP_AGA_02_14](./agent-api.sp.md#SP_AGA_02_14) `openFile` and `saveFile` (`exportCircuit` is in Phase 4), [SP_AGA_03_09](./agent-api.sp.md#SP_AGA_03_09)
**Verify:** [SP_AGA_05_01](./agent-api.sp.md#SP_AGA_05_01) rows `saveFile` (json, not allowed, overwrite foreign, overwrite prose text), `openFile` missing; [SP_AGA_05_02](./agent-api.sp.md#SP_AGA_05_02) R1/R2 `openFile` step on a background document — run in the PL_MCP Phase 4 NW.js harness; plus: in the browser build every file contract returns `file_unavailable` (live harness)

What to create:
| Entity | Module | Purpose |
|--------|--------|---------|
| PathFileAdapter | `PathFileAdapter.java` at client root beside `LoadFile` (JSNI) | stat/read (≤ 10 MB)/write via staging file + rename |
| CircuitTest | `client/agent/CircuitContentTest.java` | Side-effect-free circuit test: JSON via the importer's schema validation; text lines classified by a new predicate `CircuitElmCreator.isKnownDumpType(String)` (no element creation) that normalises the first token exactly as `TextCircuitImporter.processCircuitLine` does (numeric token → `parseInt`, else first char code; codes 32/34/38 share values with ' ', '"', '&'), the options line `$`, the auxiliary prefixes `o` (scope), `h` (hint), `38` (adjustable), `%`/`?`/`B` (ignored), and the model dump prefixes of the four model classes recognised by their first token only (no `undumpModel` call); content counts as a circuit only when at least one element or options line is present |
| FileOps | `client/agent/FileOps.java` | openFile (incl. `activate`, rejection), saveFile; title/path/modified updates |

Notes:
- The file checks run in the NW.js end-to-end harness of [PL_MCP Phase 4](./mcp-server.plan.md#PL_MCP_P4), because headless Chromium has no Node file system. This phase is implemented before PL_MCP Phase 2 and closed when PL_MCP Phase 4 passes its rows; the browser-build `file_unavailable` check runs here in the live harness.

### Phase 10 — Documentation propagation [TODO]  {#PL_AGA_P10}

**Depends on:** Phase 9 (closed)
**Implements:** [SP_AGA_06_01](./agent-api.sp.md#SP_AGA_06_01) (documented behaviour changes)
**Verify:** `/dev-flow propagate` reports no drift for C_AGA/SP_AGA, C_DOC, C_UND, C_IOF, C_APC

What to update:
- [docs/JS_API.md](./JS_API.md):
  - a `CircuitJS1Agent` section;
  - the ID scheme;
  - the corrected examples (the drift listed in the spike).
- [docs/EXPORT_CJS.md](./EXPORT_CJS.md): JSON element keys now come from runtime IDs.
- C_DOC, C_UND, C_IOF and C_APC concepts/specs: the changes listed in SP_AGA_06_01.
- Skills `automation/js-api-surface` and `editor/undo-snapshot-model`.

## Backlog

- Split SP_AGA into an umbrella plus children (it is above the docs soft-split size) — return when: the next `/dev-flow audit docs` flags it, or SP_AGA grows further.
- Agent control of adjustable sliders (values of element sliders) — return when: an eval or user request needs an agent to drive sliders.
- Per-element validity ranges as a declared contract (beyond element clamping) — return when: agents are seen setting physically meaningless values that elements accept.

## Design Decisions  {#PL_AGA_DEC}

### DEC_01 — Background-document mechanism (closes SP_AGA_DEC_04)  {#PL_AGA_DEC_01}

> **Status:** open
> **Date:** 2026-10-01

**Question:** Which of SP_AGA_DEC_04's options (A scoped silent bind, B explicit routing, C hybrid) does the implementation use?

**Options considered:**
| Option | Consequence |
|--------|-------------|
| A — scoped silent bind | One helper covers every session-coupled path; cost per bind (UI-state save/restore) |
| B — explicit routing | Many touched paths; no bind cost |
| C — hybrid | Bind for loads/undo/export/render, direct stepping for runs |

**Decision:** OPEN — see resolution trigger.
**Rationale:** Needs the Phase 0 measurements.
**Resolution trigger:** end of [Phase 0](#PL_AGA_P0); Phase 1 does not start while this is open.

## Changelog

| Date | Change |
|------|--------|
| 2026-10-01 | Initial version |
