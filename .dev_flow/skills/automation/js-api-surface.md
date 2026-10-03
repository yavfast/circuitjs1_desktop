---
skill: js-api-surface
domain: automation
topics: [js-api, circuitjs1-global, circuitjs1agent, jsni-bridge, element-id, scope-data, stop-message, undo, documents, debug-diagnostics]
source: research
updated: 2026-10-02
---

# `CircuitJS1` JS API — real behaviour vs. the docs

## Context

`window.CircuitJS1` is the legacy scripting surface used by tests, the remote-debug agent and page embedding; `window.CircuitJS1Agent` (since PL_AGA Phase 1) is the Agent API (SP_AGA) that the in-app MCP server projects. Findings verified during the MCP spike ([docs/mcp-agent-bridge.spike.md](../../../docs/mcp-agent-bridge.spike.md), 2026-10-01); the spike's doc drift (examples, property setters, `setTimeStep`, SVG) was corrected in [docs/JS_API.md](../../../docs/JS_API.md) by PL_AGA Phase 10 (2026-10-02), which also documents `CircuitJS1Agent` and the ID scheme.

## Key concepts

- Installed by `CirSim.setupJSInterface()` after `loadSimulator`; the `$wnd.oncircuitjsloaded(api)` hook is fired later by `CirSim.callLoadedHook()`, after `markStartupCompleted`. Per-element JS methods come from `CircuitElm.addJSMethods` (`element/CircuitElm.java:~1387`).
- Every `CircuitJS1` call resolves `getActiveDocument()` — it has no document/tab API. `CircuitJS1Agent` addresses any open document by handle (`d1`, `d2` …; `doc` absent = active) and runs background-document work in a scoped silent bind ([background-documents](background-documents.md)).
- `CircuitJS1Agent = {call(op, argsJson) → resultJson, callAsync(op, argsJson, callback), reportError(message)}` is installed by `AgentJsBridge.install` at the end of `setupJSInterface`; contracts answer `not_ready` until `markStartupCompleted`, `oncircuitjsloaded` fires after that. `run`/`render` only through `callAsync` (`call` → `invalid_value`). Its `debug*` members are harness diagnostics, not contract (JS_API.md lists them as such): do not build agent features on them.
- One JS thread; the simulation loop is a per-document GWT `Timer` (16 ms, `CircuitDocument.SimulationLoop`). API calls run between ticks.

## Usage in this project

- Driving tests: `tests/live/harness.mjs` (CDP, headless Chromium) — undo is driven with real key events, not the API.
- Remote debug: `war/scripts/remote-debug-agent.js` (`?remote-debug=<channel>`), relays through `server/remote-debug-server.js` (socket.io `:3030/debug`).

## Pitfalls

- **Pin names can repeat** within one element (chips: D/JK flip-flops have two `Q` texts) — `elementId.pinName` is not unique without a disambiguation rule.
- **Grid-sized elements**: PotElm, SCRElm, TriacElm, TappedTransformerElm, TransLineElm, WattmeterElm size from `circuitEditor().gridSize` (8 under Small Grid) — geometry differs with the user's preference. TappedTransformerElm takes the grid minimum (4 cells) only when created or resized; a loaded spacing is drawn as saved (a legacy `169` line without the spacing field keeps the original 32 px), and JSON import reads spacing and tap back from the `pri1`-`pri2` and `sec1`-`tap` pin distances.
- **Conditional JSON properties**: some elements export keys only when non-default (CapacitorElm `initial_voltage`, `series_resistance`, `back_euler`), so a fresh instance's `getJsonProperties()` is not the full key set.

- **Import always replaces.** `importFromJson` / `importCircuit` run with flags=0 → `ImportLifecycle.resetCircuitState` wipes the circuit and resets UI sliders; no append path is reachable for JSON. Both return `void`; import problems (unknown type, bad pin, missing `schema`) appear only as log lines. The Agent API `importCircuit` passes an `ImportReport` and returns them as issues with codes (`import_element_skipped`, `import_schema_invalid`, …; SP_AGA_03_04).
- **JSON authoring traps.** `schema: {format:"circuitjs", version:"2.x"}` is mandatory (silently rejected otherwise); pins need `{position:{x,y}}`; `"VoltageSource"` is not a registered type (use `DCVoltage`/`ACVoltage`/`VoltageSourceDC`…); `connected_to` creates auto-wires only when it is a *string*, while the exporter writes an *array*; import does not snap to the grid.
- **Property setters are not generic.** `setPropertyValue` is overridden only in Resistor, Capacitor, Transformer, TappedTransformer — other types return false. `updateElementProperties` → `applyJsonProperties` is not a patch: every omitted key is reset to its default, flag bits can be set but not cleared, and `setPoints`/`allocNodes` are not re-run. `getEditInfo`/`setEditValue` is the universal parameter contract.
- **Element IDs (since PL_AGA Phase 2).** One per-document registry (`ElementIdRegistry`, reached through `CircuitDocument.nextElementId/raiseIdCounter/resetElementIds/settleElementIds`) is the only ID source; `getElementIds()`, `getElementById()` and the JSON export keys all read it. Prefixes are letters only (`CC2` → `CC1`, `Timer555` → `TIM1`). `ImportLifecycle` resets counters on content replacement and settles IDs at the start of `finalizeCircuitLoading`: supplied JSON keys are kept when valid and unique (else regenerated with an `[WARN] ids_regenerated` log line), then elements without ID get `<prefix><counter+1>` in element order (text loads are deterministic). Undo entries carry `elementIds`; an undo/redo restore keeps the counters (no reset) and reassigns IDs by index — a retired number is never reissued until the next content replacement. Pitfalls: a JSON *paste* (RC_RETAIN) generates IDs instead of keeping keys; any `getElementId()` on an element not yet settled (e.g. a dragged-but-not-placed element drawn with its ID) consumes a counter number; IDs are not carried by the text format, so a text reload renumbers custom JSON keys.
- **Stepping.** `stepSimulation` = one synchronous timestep (with repaint). `setTimeStep` is overwritten by `analyzeCircuit` (`timeStep = maxTimeStep`) — use `setMaxTimeStep`. API `resetSimulation` does not reset scope graphs. Free-run speed is wall-clock bound. `needAnalyze()` analyzes synchronously only when stopped (`BaseCirSim.java:99`).
- **Diagnostics.** Through `CircuitJS1`, `CircuitSimulator.stop()` messages ("Singular matrix!", "Voltage source/wire loop with no resistance!", "wire loop detected", "Convergence failed! Element: id") are not logged — only `getSimInfo().stopMessage/errorMessage`; `stopElm` is not exposed. Since PL_AGA Phase 5 the simulator keeps the untranslated key next to the text (`stopKey`, `warningKey`) and a per-document event list since the last analysis (`getSolverEvents()`, at most 64, repeats folded, convergence failures one family), which `getDiagnostics`, run issues and the `source_or_wire_loop` rule read (SP_AGA_03_06). `warn()` messages are logged. Floating nodes are tied to ground through a large resistor with only a console line, and a ground is implied at the first voltage-source terminal — floating nodes read as 0 V, never as an error.
- **Measurement.** `getNodeVoltage(label)` returns 0 for an unknown label. The static `LabeledNodeElm.labelList` is only a scratch map of one wire-closure pass (it holds the labels of whichever document was analysed last); since PL_AGA Phase 5 `getNodeVoltage` and the agent's nets/readings resolve labels from the document's own label elements (`getNode(0)`). `getScopeData` returns min/max per pixel bucket in a ring buffer with no time axis (bucket = `maxTimeStep*speed`, `ScopePlot.java:~130`).
- **Displaced posts (geometry refactor `dde7f33`, fixed).** From `dde7f33` until the fix `ElmGeometry` started with `lead1 == point1` / `lead2 == point2` (same objects), so an element whose `setPoints`/`draw` interpolates into `getLead1()`/`getLead2()` moved its own posts (Inverter, Crystal, Schmitt, InvertingSchmitt, DelayBuffer, TestPoint, StopTrigger, FM source). Leads are now always separate objects (copied by value from the posts on each geometry update); `geom_posts` in `tests/live/harness.mjs` guards it. Do not reintroduce a lead that is a post object.
- **SVG.** `getCircuitAsSVG()` returns `undefined`; the result arrives only through the `onsvgrendered` hook, which on the first call fires after `canvas2svg.js` has loaded (the export is re-run then).
- **`window.CircuitJS1Mcp`** (since PL_MCP Phase 1) is the in-app MCP server's global from `scripts/mcp-server.js`. It is not a scripting API: the app calls `start`; `status()` and `stop()` are for diagnostics. In a browser build it reports `disabled` (`no desktop runtime`). The app-side copy of the status is `CircuitJS1Agent.debugMcpStatus()`.
- **Hooks are single-slot** (`onupdate`, `ontimestep`, `onsvgrendered`) — competing bridges overwrite each other. `onanalyze` and `ontimestep` fire only for the visible tab's document: they are skipped while an agent operation has a background document bound (PL_AGA Phases 5 and 7).
- **Delete via API** skips the scope cleanup, `setUnsavedChanges` and `mouseElm/menuElm` clearing that `CircuitEditor.doDelete` performs; no `CircuitJS1` element mutation calls `pushUndo` (the Agent API records its edits in agent transactions instead).
- **Scripted run control ends agent runs.** `CircuitJS1.setSimRunning` (bound to `CirSim.scriptSetSimRunning`), `resetSimulation` and `stepSimulation` call `cancelAgentRun()` on the active document first; `clearCircuit` seals an open agent transaction and resets the ID counters (SP_AGA_04_01/04_02).

## References

- [docs/JS_API.md](../../../docs/JS_API.md), [docs/agent-api.sp.md](../../../docs/agent-api.sp.md), [docs/EXPORT_CJS.md](../../../docs/EXPORT_CJS.md), [io/json-format](../io/json-format.md), [editor/undo-snapshot-model](../editor/undo-snapshot-model.md), [agent-mcp-surface](agent-mcp-surface.md)
