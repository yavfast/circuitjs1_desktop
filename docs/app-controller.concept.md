# App Controller  {#C_APC}

> **Code:** C_APC
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-10-02
> **Author:** onboard
>
> **Depends on:** [C_SIM](./simulator-engine.concept.md), [C_DOC](./document-model.concept.md), [C_TAB](./ui-tabs.concept.md), [C_LUW](./legacy-ui-wrappers.concept.md), [C_USR](./user-preferences.concept.md), [C_EDI](./canvas-editor.concept.md), [C_MEN](./menus-actions.concept.md), [C_AGA](./agent-api.concept.md), [C_MCP](./mcp-server.concept.md)
> **Used by:** —
> **Spike:** —
> **Specification:** [SP_APC](./app-controller.sp.md)
> **Plan:** [app-controller.plan.md](./app-controller.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/layer3__simulator-core.md`
> (§2.4, §11).
>
> `CirSim` — the GWT UI shell and top-level application controller. Extends `BaseCirSim`, owns the browser-facing widgets (canvas, toolbar, menus, scrollbars, controls dialog), installs the `$wnd.CircuitJS1` scripting bridge and, beside it, the `$wnd.CircuitJS1Agent` Agent API export, starts the in-app MCP server, bootstraps the app in `init()` (called from the `onModuleLoad` entry), and drives the simulator per-frame. Contains zero matrix math — pure orchestration.

## 1. Philosophy  {#C_APC_01}

### 1.1. Core Principle  {#C_APC_01_01}

Keep the engine (`CircuitSimulator`) free of UI types. `CirSim` is the
one-and-only bridge between the DOM / GWT widget tree and the simulator.
It speaks two languages at once:

- **Inwards** — Java APIs on `BaseCirSim`, `DocumentManager`,
  `CircuitSimulator`, `CircuitEditor`, etc.
- **Outwards** — JSNI methods installed on `$wnd.CircuitJS1` (and, through `AgentJsBridge`, on `$wnd.CircuitJS1Agent`); menu commands; GWT `Timer` / `NativePreviewHandler` events.

### 1.2. Design Constraints  {#C_APC_01_02}

- Single class per document shell (one `CirSim` per GWT module).
- Multi-tab: the controller stays, the active `CircuitDocument` swaps under it via `bindDocument`. A background document is bound only for the duration of a `DocumentScope` silent bind ([C_DOC](./document-model.concept.md), PL_AGA_DEC_01).
- All JS hooks (`onupdate`, `onanalyze`, `ontimestep`, `onsvgrendered` on `$wnd.CircuitJS1`, and the window-level `oncircuitjsloaded`) are fired from `callXxxHook()` / `callLoadedHook()` helpers; the engine does not know JS exists.
- UI state (transform matrix, hint, speed/current/power bar values, voltage range, display flags) is stored on `CircuitDocument` so switching tabs restores it.

## 2. Domain Model  {#C_APC_02}

### 2.1. Key Entities  {#C_APC_02_01}

- **CirSim** — subclass of `BaseCirSim` implementing
  `NativePreviewHandler`. Owns:
  - main GWT layout (canvas + toolbar + menu bar + side panels + tab bar).
  - `speedBar`, `currentBar`, `powerBar`, `timeStepBar` (scroll bars).
  - touch/mouse/keyboard dispatch.
  - controls dialog, sliders dialog, about dialog, etc.
  - `mcpServerStatus` (`McpServerStatus`) — session state of the in-app MCP server, updated through `AgentJsBridge` and read by the Options menu item and the MCP Server dialog ([C_MCP](./mcp-server.concept.md)).
- **JS bridge** — `$wnd.CircuitJS1`, a fresh object assigned in `setupJSInterface()`.
- **Agent API export** — `$wnd.CircuitJS1Agent = {call, callAsync, reportError}` (plus harness-only `debug*` diagnostics), installed at the end of `setupJSInterface()` by `AgentJsBridge.install` ([C_AGA](./agent-api.concept.md)).

### 2.2. Data Flows  {#C_APC_02_02}

```
onModuleLoad ──► circuitjs1.loadSimulator()
  new CirSim()  (BaseCirSim ctor: managers, initial document)
  CirSim.init()
  ├─ build GWT UI (canvas, toolbar, menus, tab bar)
  ├─ documentManager.restoreSession()  ──► tabs (else the start-up circuit)
  ├─ setupJSInterface()                  ──► $wnd.CircuitJS1 + $wnd.CircuitJS1Agent
  ├─ AgentJsBridge.startMcpServer(this)  ──► in-app MCP server (C_MCP)
  ├─ resetAction(); setSimRunning(circuitInfo.running)
  ├─ markStartupCompleted()              ──► agent contracts stop answering not_ready
  └─ callLoadedHook()                    ──► $wnd.oncircuitjsloaded($wnd.CircuitJS1)

per animation frame (GWT Timer owned by CircuitDocument.SimulationLoop)
  └─ SimulationLoop.update()
       ├─ step(): nothing while the document is agent-busy; else, if running:
       │    ├─ simulator.analyzeCircuit()          (if dcAnalysisFlag)
       │    ├─ simulator.preStampAndStampCircuit() (if needsStamp)
       │    └─ simulator.runCircuit(false)
       │  then notifyUpdateListeners() ──► BaseCirSim.updateListener ──► renderer.render()
       └─ one-shot runAfterNextFrame actions (agent slice yield)
```

## 3. Mechanisms  {#C_APC_03}

### 3.1. Core Algorithm  {#C_APC_03_01}

**Iteration rate.** `getIterCount()` maps the speed scrollbar (0..100) to `0.1 * exp((v−61)/24)` — nine orders of magnitude. Consumed by `CircuitSimulator.runCircuit` to gate how many timesteps fit into each animation frame.

**JS hooks.** Five fire-points:
- `callAnalyzeHook()` — after topology built.
- `callTimeStepHook()` — after each timestep commit.
- `callUpdateHook()` — after frame render.
- `callSVGRenderedHook()` — after SVG export.
- `callLoadedHook()` — once, at the end of `init()` after `markStartupCompleted()`, so the hook may call both globals.

`callAnalyzeHook` and `callTimeStepHook` return without calling the user hook while `DocumentScope` has a document other than the visible tab bound (`visibleWhileBound`): a background analysis or agent-run timestep is not the visible circuit's ([SP_AGA_06_01](./agent-api.sp.md#SP_AGA_06_01) item 17).

**JS bridge installation** (`setupJSInterface`): assigns a fresh `$wnd.CircuitJS1` object with `setSimRunning` (bound to `scriptSetSimRunning`, which first ends an agent run of the active document), `isRunning`, `getTime`, `getTimeStep`/`setTimeStep`, `getMaxTimeStep`/`setMaxTimeStep`, `resetSimulation`, `stepSimulation`, `getSimInfo`, `getNodeVoltage`, `setExtVoltage`, plus element/scope/log/circuit-IO access, then calls `AgentJsBridge.install(this)` for `$wnd.CircuitJS1Agent`. `getNodeVoltage` reads the active document's own analysed labelled nodes, not the session-wide label registry ([SP_AGA_06_01](./agent-api.sp.md#SP_AGA_06_01) item 2). Hooks register by assigning to `$wnd.CircuitJS1.onupdate`, etc.; `oncircuitjsloaded` is assigned on the window. The MCP server is started right after (`AgentJsBridge.startMcpServer`, SP_MCP_02_05) and stopped by File → Exit (`stopMcpServer`) and on window unload.

**Reset + single-step.** Two JS-exposed controls; both first call `cancelAgentRun()` on the active document ([SP_AGA_04_02](./agent-api.sp.md#SP_AGA_04_02)):
- `resetSimulation()` — stops, clears errors, zeroes time,
  resets elements, drops solver state, re-analyzes. Mirrors
  `BaseCirSim.resetAction` with minor differences (see §3.2).
- `stepSimulation()` — stop if running, analyze if dirty,
  `preStampAndStampCircuit()` if `needsStamp`, force
  `lastIterTime = now − 1000`, call `runCircuit(true)`.

`clearCircuit()` (JS) is a content replacement: it seals an open agent transaction first and resets the document's element IDs.

### 3.2. Edge Cases  {#C_APC_03_02}

- **resetSimulation vs resetAction drift** — `CirSim.resetSimulation`
  zeros `lastIterTime` but does not reset scopes; `resetAction` resets
  scopes but not `lastIterTime`. Consolidate.
- **Inactive tabs** — simulation loop pauses via `CircuitDocument.isActive=false`. The controller stays bound to the active doc, except inside a `DocumentScope` silent bind; when that bind is undone, `refreshSessionWidgets` re-derives the visible tab's time-step bar (without its command), power bar, Undo/Redo and edit items, Save item and window title.
- **Headless** — the base class stub `getIterCount()` returns 0;
  any test path must subclass `CirSim` or inject.

## 4. Integration Points  {#C_APC_04}

### 4.1. Dependencies  {#C_APC_04_01}

- [C_SIM](./simulator-engine.concept.md) — drives the engine per frame.
- [C_DOC](./document-model.concept.md) — owns the multi-tab document
  pool and binds the active one.
- [C_TAB](./ui-tabs.concept.md) — tab bar widget, `DocumentManagerListener`.
- [C_LUW](./legacy-ui-wrappers.concept.md) — canvas sizing, window events.
- [C_USR](./user-preferences.concept.md) — display settings, options.
- [C_EDI](./canvas-editor.concept.md) — selection/drag/mouse-mode.
- [C_MEN](./menus-actions.concept.md) — menu bar, action dispatch.
- [C_AGA](./agent-api.concept.md) — `$wnd.CircuitJS1Agent` export; readiness via `markStartupCompleted`.
- [C_MCP](./mcp-server.concept.md) — in-app MCP server start/stop and `McpServerStatus`.

### 4.2. API Surface  {#C_APC_04_02}

Java (public or package-private):
- `init()`, `setupJSInterface()`, `resetSimulation()`, `stepSimulation()`, `scriptSetSimRunning(b)`, `clearCircuit()`, `getIterCount()`, `console(String)` static, `callAnalyzeHook`, `callTimeStepHook`, `callUpdateHook`, `callSVGRenderedHook`, `callLoadedHook`, `refreshSessionWidgets(saveAllowed)`, `stopMcpServer()`, field `mcpServerStatus`.
- GWT entry: `onModuleLoad()` (via `CircuitJS1.java` entry class).
- Menu: Options → "MCP Server..." (" (off)" appended while the server is disabled; the label follows `mcpServerStatus`) → `ActionManager` `options`/`mcpserver` → `DialogManager.showMcpServerDialog` → `McpServerDialog`. File → Exit calls `stopMcpServer()` before closing the window.

JavaScript (on `$wnd.CircuitJS1`):
- Run control: `setSimRunning`, `isRunning`, `resetSimulation`,
  `stepSimulation`, `getTime`, `getTimeStep`, `setTimeStep`,
  `getMaxTimeStep`, `setMaxTimeStep`, `getSimInfo`.
- Probes: `getNodeVoltage(name)`, `setExtVoltage(name,v)`.
- Hooks (user-assigned callbacks): `onupdate`, `onanalyze`, `ontimestep`,
  `onsvgrendered`; window-level `oncircuitjsloaded`.
- Circuit IO / scope / log / element access.

JavaScript (on `$wnd.CircuitJS1Agent`, [C_AGA](./agent-api.concept.md)): `call(op, argsJson) → resultJson`, `callAsync(op, argsJson, callback)`, `reportError(message)`; contracts answer `not_ready` until start-up completed.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
| 2026-10-02 | PL_AGA Phase 10 propagate: `$wnd.CircuitJS1Agent` export, MCP server start/stop and `mcpServerStatus`; init order ends with `markStartupCompleted` → `callLoadedHook`; analyze/timestep hooks skipped for a background bound document; scripted run/reset/step cancel an agent run; `getNodeVoltage` per document; `clearCircuit` seals and resets IDs; `DocumentScope` exception and `refreshSessionWidgets`; MCP Server menu item; stale line numbers dropped. |
