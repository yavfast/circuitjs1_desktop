# App Controller — Specification  {#SP_APC}

> **Code:** SP_APC
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_APC](./app-controller.concept.md)
> **Depends on specs:** [SP_SIM](./simulator-engine.sp.md), [SP_DOC](./document-model.sp.md), [SP_TAB](./ui-tabs.sp.md), [SP_LUW](./legacy-ui-wrappers.sp.md), [SP_USR](./user-preferences.sp.md), [SP_EDI](./canvas-editor.sp.md), [SP_MEN](./menus-actions.sp.md)
> **Used by specs:** —
> **Plan:** [app-controller.plan.md](./app-controller.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/layer3__simulator-core.md` §2.4, §11.
> Defines the `CirSim` controller surface, the `$wnd.CircuitJS1` JS
> bridge contract, and the bootstrap/shutdown sequence.

## 01. Data Structures  {#SP_APC_01}

> Implements: [C_APC_02](./app-controller.concept.md#C_APC_02)

### 01_01. CirSim owned widgets  {#SP_APC_01_01}

| Field | Type | Purpose |
|-------|------|---------|
| canvas | Canvas | GWT drawing surface |
| speedBar | Scrollbar | simulation speed (0..100) |
| currentBar | Scrollbar | display current intensity |
| powerBar | Scrollbar | display power intensity |
| mainMenuBar | MenuBar | top-level menu |
| buttonBar | HorizontalPanel | toolbar buttons |
| tabBarPanel | TabBarPanel | multi-tab header |
| iFrame | HTMLPanel | outer layout root |

### 01_02. JS-bridge object  {#SP_APC_01_02}

`$wnd.CircuitJS1` (installed by `setupJSInterface`):

| Member | Kind | Arity | Description |
|--------|------|-------|-------------|
| setSimRunning(b) | method | 1 | start/stop |
| isRunning() | method | 0 | → bool |
| getTime() | method | 0 | → seconds |
| getTimeStep() / setTimeStep(v) | method | 0/1 | dt |
| getMaxTimeStep()/setMaxTimeStep(v) | method | 0/1 | maxDt |
| resetSimulation() | method | 0 | full reset |
| stepSimulation() | method | 0 | single step |
| getSimInfo() | method | 0 | → JSON info |
| getNodeVoltage(name) | method | 1 | probe by label |
| setExtVoltage(name, v) | method | 2 | external source drive |
| onupdate / onanalyze / ontimestep / onsvgrendered / oncircuitjsloaded | callback slot | — | user-assigned hooks |
| element / scope / log / circuit IO | methods | — | see source |

## 02. Contracts  {#SP_APC_02}

### 02_01. setupJSInterface  {#SP_APC_02_01}

Purpose: install `$wnd.CircuitJS1` methods via JSNI.

Processing:
    FUNCTION setupJSInterface():
        // JSNI block:
        $wnd.CircuitJS1 = $wnd.CircuitJS1 || {}
        $wnd.CircuitJS1.setSimRunning = function(b) { this.@CirSim::setSimRunning(Z)(b) }
        // ... repeat for each method listed in SP_APC_01_02
        // hook slots default to no-op; user overwrites them.

### 02_02. resetSimulation  {#SP_APC_02_02}

Purpose: JS-exposed full reset.

Processing:
    stop running; clear errors
    zero t, lastIterTime, timeStepAccum, timeStepCount
    reset every element
    simulator.resetSolverState()
    simulator.clearStopState()
    needAnalyze()

### 02_03. stepSimulation  {#SP_APC_02_03}

Purpose: JS-exposed single-step, overriding pacing.

Processing:
    setSimRunning(false)
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
- `callOnLoadedHook()` — fired after `onModuleLoad` completes.

Each guards against missing slot (`if (fn) fn()`).

## 03. Validation Rules  {#SP_APC_03}

- `setSimRunning(true)` is a no-op when `activeDocument.errorMessage != null`.
- `getNodeVoltage(name)` returns NaN for unknown labels (does not throw).
- JS-bridge methods must be callable from any frame; never block.

## 04. State Transitions  {#SP_APC_04}

Bootstrap:

    [pre-boot] --onModuleLoad--> [managers-built]
    [managers-built] --buildUI--> [ui-ready]
    [ui-ready] --restoreSession--> [tabs-loaded]
    [tabs-loaded] --setupJSInterface + oncircuitjsloaded--> [live]

Frame cycle (live):

    [idle] --Timer tick--> [analyzing?] --> [stamping?] --> [running] --> [rendering] --> [idle]

## 05. Verification Criteria  {#SP_APC_05}

### 05_01. Functional Expectations  {#SP_APC_05_01}

| Contract | Scenario | Expected |
|----------|----------|----------|
| setupJSInterface | page load | $wnd.CircuitJS1.setSimRunning is function |
| resetSimulation | from JS | time==0, no stop msg, canvas repaints |
| stepSimulation | after stop | one timestep advances |
| getIterCount | speed=61 | returns 0.1 |
| hooks | user sets onupdate | invoked after each frame |

### 05_02. Invariant Checks  {#SP_APC_05_02}

| Invariant | Verification |
|-----------|-------------|
| single CirSim | GWT entry point instantiates once |
| JS bridge idempotent | re-calling setupJSInterface is safe |
| active doc only simulates | inactive docs' SimulationLoop is stopped |

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
