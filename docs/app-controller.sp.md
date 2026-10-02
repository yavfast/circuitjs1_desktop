# App Controller — Specification  {#SP_APC}

> **Code:** SP_APC
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-10-02
>
> **Concept:** [C_APC](./app-controller.concept.md)
> **Depends on specs:** [SP_SIM](./simulator-engine.sp.md), [SP_DOC](./document-model.sp.md), [SP_TAB](./ui-tabs.sp.md), [SP_LUW](./legacy-ui-wrappers.sp.md), [SP_USR](./user-preferences.sp.md), [SP_EDI](./canvas-editor.sp.md), [SP_MEN](./menus-actions.sp.md)
> **Used by specs:** —
> **Plan:** [app-controller.plan.md](./app-controller.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/layer3__simulator-core.md` §2.4, §11.
> Defines the `CirSim` controller surface, the `$wnd.CircuitJS1` JS bridge contract, where the `$wnd.CircuitJS1Agent` export ([SP_AGA](./agent-api.sp.md)) and the in-app MCP server ([SP_MCP](./mcp-server.sp.md)) hook in, and the bootstrap/shutdown sequence.

## 01. Data Structures  {#SP_APC_01}

> Implements: [C_APC_02](./app-controller.concept.md#C_APC_02)

### 01_01. CirSim owned widgets  {#SP_APC_01_01}

| Field | Type | Purpose |
|-------|------|---------|
| canvas | Canvas | GWT drawing surface |
| speedBar | Scrollbar | simulation speed (0..100) |
| currentBar | Scrollbar | display current intensity |
| powerBar | Scrollbar | display power intensity |
| timeStepBar | Scrollbar | maximum time step; set from code by `ControlsDialog.syncTimeStepBar()` without firing its command |
| mcpServerStatus | McpServerStatus | session state of the in-app MCP server (state, reason, instance ID, address/URLs, tool-call count) and its preferences; updated through `AgentJsBridge`, read by the Options menu item and `McpServerDialog` |
| mainMenuBar | MenuBar | top-level menu |
| buttonBar | HorizontalPanel | toolbar buttons |
| tabBarPanel | TabBarPanel | multi-tab header |

### 01_02. JS-bridge object  {#SP_APC_01_02}

`$wnd.CircuitJS1` (a fresh object assigned by `setupJSInterface`):

| Member | Kind | Arity | Description |
|--------|------|-------|-------------|
| setSimRunning(b) | method | 1 | start/stop (`scriptSetSimRunning`: ends an agent run of the active document first) |
| isRunning() | method | 0 | → bool |
| getTime() | method | 0 | → seconds |
| getTimeStep() / setTimeStep(v) | method | 0/1 | dt |
| getMaxTimeStep()/setMaxTimeStep(v) | method | 0/1 | maxDt |
| resetSimulation() | method | 0 | full reset |
| stepSimulation() | method | 0 | single step |
| getSimInfo() | method | 0 | → JSON info |
| getNodeVoltage(name) | method | 1 | probe by label, from the active document's own labelled nodes |
| setExtVoltage(name, v) | method | 2 | external source drive |
| onupdate / onanalyze / ontimestep / onsvgrendered | callback slot | — | user-assigned hooks |
| element / scope / log / circuit IO | methods | — | see source |

`$wnd.oncircuitjsloaded` is a window-level slot, called once with `$wnd.CircuitJS1` by `callLoadedHook()`.

### 01_03. Agent API export  {#SP_APC_01_03}

`$wnd.CircuitJS1Agent` (installed by `AgentJsBridge.install(this)` at the end of `setupJSInterface`; contracts in [SP_AGA_02](./agent-api.sp.md#SP_AGA_02)):

| Member | Kind | Arity | Description |
|--------|------|-------|-------------|
| call(op, argsJson) | method | 2 | synchronous contract → result JSON |
| callAsync(op, argsJson, callback) | method | 3 | sliced asynchronous contract (`run`, `render`) |
| reportError(message) | method | 1 | pass a JS caller's unexpected exception to the global handler |
| debug* | methods | — | live-harness diagnostics, not contracts |

## 02. Contracts  {#SP_APC_02}

### 02_01. setupJSInterface  {#SP_APC_02_01}

Purpose: install `$wnd.CircuitJS1` methods via JSNI.

Processing:
    FUNCTION setupJSInterface():
        // JSNI block:
        var that = this
        $wnd.CircuitJS1 = {
            setSimRunning: $entry(function(run) { that.@CirSim::scriptSetSimRunning(Z)(run) }),
            // ... one $entry-wrapped member per method listed in SP_APC_01_02
        }
        // hook slots are absent until the user assigns them
        @AgentJsBridge::install(CirSim)(that)       // $wnd.CircuitJS1Agent (SP_APC_01_03)

### 02_02. resetSimulation  {#SP_APC_02_02}

Purpose: JS-exposed full reset.

Processing:
    doc.cancelAgentRun()              // SP_AGA_04_02: a scripted reset ends an agent run first
    stop running; clear errors
    zero t, lastIterTime, timeStepAccum, timeStepCount
    reset every element
    simulator.resetSolverState()
    simulator.clearStopState()
    needAnalyze()

### 02_03. stepSimulation  {#SP_APC_02_03}

Purpose: JS-exposed single-step, overriding pacing.

Processing:
    doc.cancelAgentRun()              // SP_AGA_04_02
    if doc.isRunning(): setSimRunning(false)
    if dcAnalysisFlag: simulator.analyzeCircuit()
    if simulator.needsStamp: simulator.preStampAndStampCircuit()
    lastIterTime = now - 1000     // bypass stepRate gate
    simulator.runCircuit(true)

### 02_04. getIterCount  {#SP_APC_02_04}

Purpose: map speed scrollbar to iteration rate.

Input: `speedBar.getValue()` in `[0..100]`.
Output: `double iterCount = 0.1 * exp((v - 61) / 24)`.

### 02_05. JS hooks  {#SP_APC_02_05}

- `callAnalyzeHook()` — fired at end of `analyzeCircuit`.
- `callTimeStepHook()` — fired after each committed timestep.
- `callUpdateHook()` — fired after frame render.
- `callSVGRenderedHook(svgXml)` — fired after SVG export.
- `callLoadedHook()` — calls `$wnd.oncircuitjsloaded($wnd.CircuitJS1)` once, at the end of `init()` after `markStartupCompleted()`.

Each guards against missing slot (`if (fn) fn()`). `callAnalyzeHook` and `callTimeStepHook` first return without calling while `visibleWhileBound != null && getActiveDocument() != visibleWhileBound` — a `DocumentScope` has a background document bound ([SP_AGA_06_01](./agent-api.sp.md#SP_AGA_06_01) item 17).

### 02_06. clearCircuit  {#SP_APC_02_06}

Purpose: JS-exposed content replacement of the active document.

Processing:
    doc.undoManager.sealTransaction()   // SP_AGA_04_01
    delete every element; clear elmList, node lists, label/ground registries
    scope count 0; adjustables reset; zero t, timeStepAccum, lastIterTime
    doc.resetElementIds()               // SP_AGA_03_02
    needAnalyze(); repaint()

## 03. Validation Rules  {#SP_APC_03}

- `setSimRunning(true)` is a no-op when `activeDocument.errorMessage != null`.
- `getNodeVoltage(name)` returns 0 for unknown labels and for a label on ground (does not throw).
- JS-bridge methods must be callable from any frame; never block.

## 04. State Transitions  {#SP_APC_04}

Bootstrap:

    [pre-boot] --onModuleLoad--> [managers-built]
    [managers-built] --buildUI--> [ui-ready]
    [ui-ready] --restoreSession--> [tabs-loaded]
    [tabs-loaded] --setupJSInterface (+ CircuitJS1Agent)--> [js-ready]
    [js-ready] --AgentJsBridge.startMcpServer--> [mcp-started]
    [mcp-started] --resetAction + setSimRunning(circuitInfo.running)--> [reset]
    [reset] --markStartupCompleted--> [ready]      // agent contracts stop answering not_ready
    [ready] --callLoadedHook (oncircuitjsloaded)--> [live]

Shutdown: File → Exit → `stopMcpServer()` → close the window (the server also stops on window unload).

Frame cycle (live):

    [idle] --Timer tick--> [busy? skip] | [analyzing?] --> [stamping?] --> [running] --> [rendering] --> [idle]

## 05. Verification Criteria  {#SP_APC_05}

### 05_01. Functional Expectations  {#SP_APC_05_01}

| Contract | Scenario | Expected |
|----------|----------|----------|
| setupJSInterface | page load | $wnd.CircuitJS1.setSimRunning and $wnd.CircuitJS1Agent.call are functions |
| oncircuitjsloaded | page load | fires once, after start-up completed; CircuitJS1Agent.call does not answer not_ready from it |
| resetSimulation | from JS | time==0, no stop msg, canvas repaints |
| stepSimulation | after stop | one timestep advances |
| getIterCount | speed=61 | returns 0.1 |
| hooks | user sets onupdate | invoked after each frame |

### 05_02. Invariant Checks  {#SP_APC_05_02}

| Invariant | Verification |
|-----------|-------------|
| single CirSim | GWT entry point instantiates once |
| JS bridge reinstall | re-calling setupJSInterface assigns fresh `CircuitJS1` and `CircuitJS1Agent` objects (hook slots set on the old `CircuitJS1` object are dropped) |
| active doc only free-runs | inactive docs' SimulationLoop is stopped; agent runs step background documents in slices through `DocumentScope` |
| hooks follow the visible tab | no `onanalyze`/`ontimestep` call while a background document is bound |

### 05_03. Integration Scenarios  {#SP_APC_05_03}

| Scenario | Steps | Expected |
|----------|-------|----------|
| Host page drives sim | onload → setSimRunning(true) → read getTime | monotonic |
| SVG export | menu Export SVG | onsvgrendered fires with svg string |
| Tab switch | user clicks tab | canvas redraws with new doc's transform |

### 05_04. Edge Cases  {#SP_APC_05_04}

| Case | Expected |
|------|----------|
| setTimeStep(0) | ignored / clamped to minTimeStep |
| setExtVoltage(unknown) | no-op, no throw |
| stepSimulation while stopped with error | error shown; no step |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
| 2026-10-02 | PL_AGA Phase 10 propagate: §01_01 `timeStepBar`, `mcpServerStatus`; §01_02 fresh object, `scriptSetSimRunning`, per-document `getNodeVoltage`, window-level `oncircuitjsloaded`; new §01_03 `CircuitJS1Agent`; §02_01 pseudo-code ends with `AgentJsBridge.install`; §02_02/§02_03 `cancelAgentRun` first; §02_05 `callLoadedHook` and background-bind hook skip; new §02_06 `clearCircuit`; §03 unknown label reads 0; §04 bootstrap with MCP start and readiness, shutdown; §05 checks. |
