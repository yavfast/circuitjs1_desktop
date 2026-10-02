---
skill: js-api-surface
domain: automation
topics: [js-api, circuitjs1-global, jsni-bridge, element-id, scope-data, stop-message, undo, documents]
source: research
updated: 2026-10-01
---

# `CircuitJS1` JS API — real behaviour vs. the docs

## Context

`window.CircuitJS1` is the only programmatic surface used by tests, the remote-debug agent and any agent bridge. [docs/JS_API.md](../../../docs/JS_API.md) matches it by name but overstates its behaviour in several places. Verified during the MCP spike ([docs/mcp-agent-bridge.spike.md](../../../docs/mcp-agent-bridge.spike.md), 2026-10-01).

## Key concepts

- Installed by `CirSim.setupJSInterface()` (`CirSim.java:1419`) after `loadSimulator`; fires the `$wnd.oncircuitjsloaded(api)` hook. Per-element JS methods come from `CircuitElm.addJSMethods` (`element/CircuitElm.java:~1387`).
- Every call resolves `getActiveDocument()` — there is no document/tab API, although `DocumentManager.createDocument/setActiveDocument/closeDocument` exist in Java.
- One JS thread; the simulation loop is a per-document GWT `Timer` (16 ms, `CircuitDocument.java:151`). API calls run between ticks.

## Usage in this project

- Driving tests: `tests/live/harness.mjs` (CDP, headless Chromium) — undo is driven with real key events, not the API.
- Remote debug: `war/scripts/remote-debug-agent.js` (`?remote-debug=<channel>`), relays through `server/remote-debug-server.js` (socket.io `:3030/debug`).

## Pitfalls

- **Pin names can repeat** within one element (chips: D/JK flip-flops have two `Q` texts) — `elementId.pinName` is not unique without a disambiguation rule.
- **Grid-sized elements**: PotElm, SCRElm, TriacElm, TappedTransformerElm, TransLineElm, WattmeterElm size from `circuitEditor().gridSize` (8 under Small Grid) — geometry differs with the user's preference.
- **Conditional JSON properties**: some elements export keys only when non-default (CapacitorElm `initial_voltage`, `series_resistance`, `back_euler`), so a fresh instance's `getJsonProperties()` is not the full key set.

- **Import always replaces.** `importFromJson` / `importCircuit` run with flags=0 → `ImportLifecycle.resetCircuitState` wipes the circuit and resets UI sliders; no append path is reachable for JSON. Both return `void`; import problems (unknown type, bad pin, missing `schema`) appear only as log lines.
- **JSON authoring traps.** `schema: {format:"circuitjs", version:"2.x"}` is mandatory (silently rejected otherwise); pins need `{position:{x,y}}`; `"VoltageSource"` is not a registered type (use `DCVoltage`/`ACVoltage`/`VoltageSourceDC`…); `connected_to` creates auto-wires only when it is a *string*, while the exporter writes an *array*; import does not snap to the grid.
- **Property setters are not generic.** `setPropertyValue` is overridden only in Resistor, Capacitor, Transformer, TappedTransformer — other types return false. `updateElementProperties` → `applyJsonProperties` is not a patch: every omitted key is reset to its default, flag bits can be set but not cleared, and `setPoints`/`allocNodes` are not re-run. `getEditInfo`/`setEditValue` is the universal parameter contract.
- **Element IDs (since PL_AGA Phase 2).** One per-document registry (`ElementIdRegistry`, reached through `CircuitDocument.nextElementId/raiseIdCounter/resetElementIds/settleElementIds`) is the only ID source; `getElementIds()`, `getElementById()` and the JSON export keys all read it. Prefixes are letters only (`CC2` → `CC1`, `Timer555` → `TIM1`). `ImportLifecycle` resets counters on content replacement and settles IDs at the start of `finalizeCircuitLoading`: supplied JSON keys are kept when valid and unique (else regenerated with an `[WARN] ids_regenerated` log line), then elements without ID get `<prefix><counter+1>` in element order (text loads are deterministic). Undo entries carry `elementIds`; an undo/redo restore keeps the counters (no reset) and reassigns IDs by index — a retired number is never reissued until the next content replacement. Pitfalls: a JSON *paste* (RC_RETAIN) generates IDs instead of keeping keys; any `getElementId()` on an element not yet settled (e.g. a dragged-but-not-placed element drawn with its ID) consumes a counter number; IDs are not carried by the text format, so a text reload renumbers custom JSON keys.
- **Stepping.** `stepSimulation` = one synchronous timestep (with repaint). `setTimeStep` is overwritten by `analyzeCircuit` (`timeStep = maxTimeStep`) — use `setMaxTimeStep`. API `resetSimulation` does not reset scope graphs. Free-run speed is wall-clock bound. `needAnalyze()` analyzes synchronously only when stopped (`BaseCirSim.java:99`).
- **Diagnostics.** `CircuitSimulator.stop()` messages ("Singular matrix!", "Voltage source/wire loop with no resistance!", "wire loop detected", "Convergence failed! Element: id") are not logged — only `getSimInfo().stopMessage/errorMessage`; `stopElm` is not exposed. `warn()` messages are logged. Floating nodes are tied to ground through a large resistor with only a console line, and a ground is implied at the first voltage-source terminal — floating nodes read as 0 V, never as an error.
- **Measurement.** `getNodeVoltage(label)` returns 0 for an unknown label. The static `LabeledNodeElm.labelList` is only a scratch map of one wire-closure pass (it holds the labels of whichever document was analysed last); since PL_AGA Phase 5 `getNodeVoltage` and the agent's nets/readings resolve labels from the document's own label elements (`getNode(0)`). `getScopeData` returns min/max per pixel bucket in a ring buffer with no time axis (bucket = `maxTimeStep*speed`, `ScopePlot.java:~130`).
- **Displaced posts (geometry refactor `dde7f33`, fixed).** From `dde7f33` until the fix `ElmGeometry` started with `lead1 == point1` / `lead2 == point2` (same objects), so an element whose `setPoints`/`draw` interpolates into `getLead1()`/`getLead2()` moved its own posts (Inverter, Crystal, Schmitt, InvertingSchmitt, DelayBuffer, TestPoint, StopTrigger, FM source). Leads are now always separate objects (copied by value from the posts on each geometry update); `geom_posts` in `tests/live/harness.mjs` guards it. Do not reintroduce a lead that is a post object.
- **SVG.** `getCircuitAsSVG()` returns `undefined`; the result arrives only through the `onsvgrendered` hook, which is skipped on the first call while `canvas2svg.js` loads.
- **Hooks are single-slot** (`onupdate`, `ontimestep`, `onsvgrendered`) — competing bridges overwrite each other. `onanalyze` and `ontimestep` fire only for the visible tab's document: they are skipped while an agent operation has a background document bound (PL_AGA Phases 5 and 7).
- **Delete via API** skips the scope cleanup, `setUnsavedChanges` and `mouseElm/menuElm` clearing that `CircuitEditor.doDelete` performs; no API mutation calls `pushUndo`.

## References

- [docs/JS_API.md](../../../docs/JS_API.md), [docs/EXPORT_CJS.md](../../../docs/EXPORT_CJS.md), [io/json-format](../io/json-format.md), [editor/undo-snapshot-model](../editor/undo-snapshot-model.md)
