# App Controller  {#C_APC}

> **Code:** C_APC
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard
>
> **Depends on:** [C_SIM](./simulator-engine.concept.md), [C_DOC](./document-model.concept.md), [C_TAB](./ui-tabs.concept.md), [C_LUW](./legacy-ui-wrappers.concept.md), [C_USR](./user-preferences.concept.md), [C_EDI](./canvas-editor.concept.md), [C_MEN](./menus-actions.concept.md)
> **Used by:** —
> **Spike:** —
> **Specification:** [SP_APC](./app-controller.sp.md)
> **Plan:** [app-controller.plan.md](./app-controller.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/layer3__simulator-core.md`
> (§2.4, §11).
>
> `CirSim` — the GWT UI shell and top-level application controller.
> Extends `BaseCirSim`, owns the browser-facing widgets (canvas,
> toolbar, menus, scrollbars, controls dialog), installs the
> `$wnd.CircuitJS1` JavaScript bridge, bootstraps the app on
> `onModuleLoad`, and drives the simulator per-frame. Contains zero
> matrix math — pure orchestration.

## 1. Philosophy  {#C_APC_01}

### 1.1. Core Principle  {#C_APC_01_01}

Keep the engine (`CircuitSimulator`) free of UI types. `CirSim` is the
one-and-only bridge between the DOM / GWT widget tree and the simulator.
It speaks two languages at once:

- **Inwards** — Java APIs on `BaseCirSim`, `DocumentManager`,
  `CircuitSimulator`, `CircuitEditor`, etc.
- **Outwards** — JSNI methods installed on `$wnd.CircuitJS1`; menu
  commands; GWT `Timer` / `NativePreviewHandler` events.

### 1.2. Design Constraints  {#C_APC_01_02}

- Single class per document shell (one `CirSim` per GWT module).
- Multi-tab: the controller stays, the active `CircuitDocument` swaps
  under it via `bindDocument`.
- All JS hooks (`onupdate`, `onanalyze`, `ontimestep`, `onsvgrendered`,
  `oncircuitjsloaded`) are fired from `callXxxHook()` helpers; the engine
  does not know JS exists.
- UI state (transform matrix, speed/current/power bar values, display
  flags) is stored on `CircuitDocument` so switching tabs restores it.

## 2. Domain Model  {#C_APC_02}

### 2.1. Key Entities  {#C_APC_02_01}

- **CirSim** — subclass of `BaseCirSim` implementing
  `NativePreviewHandler`. Owns:
  - main GWT layout (canvas + toolbar + menu bar + side panels + tab bar).
  - `speedBar`, `currentBar`, `powerBar` (scroll bars).
  - touch/mouse/keyboard dispatch.
  - controls dialog, about dialog, etc.
- **JS bridge** — `$wnd.CircuitJS1` object populated in
  `setupJSInterface()` (L1437).

### 2.2. Data Flows  {#C_APC_02_02}

```
onModuleLoad
  ├─ construct managers (BaseCirSim ctor)
  ├─ build GWT UI (canvas, toolbar, tab bar)
  ├─ documentManager.restoreSession()  ──► tabs
  ├─ setupJSInterface()                  ──► $wnd.CircuitJS1
  └─ callOnLoadedHook()                  ──► user script

per animation frame (GWT Timer owned by CircuitDocument.SimulationLoop)
  └─ CircuitDocument.update()
       ├─ simulator.analyzeCircuit()   (if dcAnalysisFlag)
       ├─ simulator.preStampAndStamp() (if needsStamp)
       ├─ simulator.runCircuit(false)
       └─ notifyUpdate()  ──► BaseCirSim.updateListener ──► renderer.repaint()
```

## 3. Mechanisms  {#C_APC_03}

### 3.1. Core Algorithm  {#C_APC_03_01}

**Iteration rate.** `getIterCount()` (L520) maps the speed scrollbar
(0..100) to `0.1 * exp((v−61)/24)` — nine orders of magnitude. Consumed
by `CircuitSimulator.runCircuit` to gate how many timesteps fit into
each animation frame.

**JS hooks.** Four fire-points:
- `callAnalyzeHook()` (L1516) — after topology built.
- `callTimeStepHook()` (L1522) — after each timestep commit.
- `callUpdateHook()` (L1510) — after frame render.
- `callSVGRenderedHook()` (L1528) — after SVG export.

**JS bridge installation** (`setupJSInterface`, L1437): populates
`$wnd.CircuitJS1` with `setSimRunning`, `isRunning`, `getTime`,
`getTimeStep`/`setTimeStep`, `getMaxTimeStep`/`setMaxTimeStep`,
`resetSimulation`, `stepSimulation`, `getSimInfo`, `getNodeVoltage`,
`setExtVoltage`, plus element/scope/log/circuit-IO access. Hooks
register by assigning to `$wnd.CircuitJS1.onupdate`, etc.

**Reset + single-step.** Two JS-exposed controls:
- `resetSimulation()` (L1195) — stops, clears errors, zeroes time,
  resets elements, drops solver state, re-analyzes. Mirrors
  `BaseCirSim.resetAction` with minor differences (see §3.2).
- `stepSimulation()` (L1225) — stop if running, analyze if dirty,
  `preStampAndStampCircuit()` if `needsStamp`, force
  `lastIterTime = now − 1000`, call `runCircuit(true)`.

### 3.2. Edge Cases  {#C_APC_03_02}

- **resetSimulation vs resetAction drift** — `CirSim.resetSimulation`
  zeros `lastIterTime` but does not reset scopes; `resetAction` resets
  scopes but not `lastIterTime`. Consolidate.
- **Inactive tabs** — simulation loop pauses via
  `CircuitDocument.isActive=false`. Controller stays bound to the
  active doc only.
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

### 4.2. API Surface  {#C_APC_04_02}

Java (public):
- `setupJSInterface()`, `resetSimulation()`, `stepSimulation()`,
  `getIterCount()`, `console(String)` static, `callAnalyzeHook`,
  `callTimeStepHook`, `callUpdateHook`, `callSVGRenderedHook`.
- GWT entry: `onModuleLoad()` (via `CircuitJS1.java` entry class).

JavaScript (on `$wnd.CircuitJS1`):
- Run control: `setSimRunning`, `isRunning`, `resetSimulation`,
  `stepSimulation`, `getTime`, `getTimeStep`, `setTimeStep`,
  `getMaxTimeStep`, `setMaxTimeStep`, `getSimInfo`.
- Probes: `getNodeVoltage(name)`, `setExtVoltage(name,v)`.
- Hooks (user-assigned callbacks): `onupdate`, `onanalyze`, `ontimestep`,
  `onsvgrendered`, `oncircuitjsloaded`.
- Circuit IO / scope / log / element access.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
