# Scope Visualization — Time/Frequency/XY Display  {#C_SCP}

> **Code:** C_SCP
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard-doc-gen
>
> **Depends on:** [C_ELB](./element-base.concept.md), [C_RND](./rendering-primitives.concept.md), [C_MDS](./math-dsp.concept.md) (CircuitMath + FFT), [C_UTL](./util-locale-log.concept.md), [C_DOC](./document-model.concept.md)
> **Used by:** [C_EDI](./canvas-editor.concept.md), [C_MEN](./menus-actions.concept.md), C_DSP (dialog-specialized)
> **Spike:** —
> **Specification:** [SP_SCP](./scope-visualization.sp.md)
> **Plan:** [scope-visualization.plan.md](./scope-visualization.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/layer3__scopes.md`.
>
> The in-circuit oscilloscope subsystem: multi-trace time-domain plotting with AC coupling, XY/2D mode, FFT spectrum, trigger state machine (AUTO/NORMAL/SINGLE × rising/falling), persistence overlay, cursor readout, RMS/avg/duty/frequency statistics. Two hosting modes: docked scopes (owned by `ScopeManager`) and floating scopes embedded inside a `ScopeElm` via `ScopeElm.elmScope`.

## 1. Philosophy  {#C_SCP_01}

### 1.1. Core Principle  {#C_SCP_01_01}

Scopes decouple measurement from simulation: every element implements `getScopeValue/getScopeUnits/canShowValueInScope`, so adding a new element type automatically exposes signals without touching scope code. A fixed-capacity array of 20 docked scopes plus per-element floating scopes gives predictable layout while allowing dense multi-signal views.

### 1.2. Design Constraints  {#C_SCP_01_02}

- **Power-of-two ring buffer** per plot (`scopePointCount`), so index math is a single `& mask`. Size grows to `nextPow2(rect.width)` on reset.
- **Pixel-column aggregation.** Many sim steps collapse into one (min, max) bar per pixel; samples are drawn as vertical segments.
- **One cursor, one time-base** globally (`static cursorTime/cursorUnits/cursorScope`). Multi-document coexistence is compromised.
- **Trigger freezes display** via a snapshot (`TriggerFrame`) captured at the trigger moment; live rolling resumes only in AUTO mode with no trigger.
- **FFT avoids DC.** Real input is `0.5*(maxValues[i] + minValues[i])` averaged, eliminating the 0-Hz spike from DC bias.

## 2. Domain Model  {#C_SCP_02}

### 2.1. Key Entities  {#C_SCP_02_01}

```
ScopeManager extends BaseCirSimDelegate
  Scope[20]   scopes              -- fixed capacity
  int         scopeCount, scopeSelected, scopeMenuSelected, menuScope
  int[]       scopeColCount
  setupScopes()                   -- per-frame layout pass
  addScope / addToScope / dockScope / undockScope
  stackScope / unstackScope / combineScope / separateAll
  canDelayWireProcessing()        -- solver hint

Scope extends BaseCirSimDelegate
  Vector<ScopePlot> plots, visiblePlots
  Rectangle         rect
  Canvas            imageCanvas          -- persistent for 2D/XY
  FFT               fft
  Trigger state: triggerEnabled/Mode/Slope/Level/Holdoff/Position/Source
  History: Vector<HistoryFrame>, depth 1..64
  Trigger frame: TriggerFrame(width, plotCount, minValues[][], maxValues[][],
                              startIndex, rightEdgeTime, triggerTime)
  Visibility flags (showV/I/Scale/Max/Min/Freq/FFT/Negative/RMS/Average/
                    DutyCycle/ElmInfo, logSpectrum, plot2d, plotXY,
                    maxScale, manualScale)
  static cursorTime, cursorUnits, cursorScope

ScopePlot
  double[] minValues, maxValues, sampleValues   -- all length scopePointCount
  int      ptr, scopePointCount, scopePlotSpeed
  AC coupling: y[i] = alpha*(y[i-1] + x[i] - x[i-1]); alpha via speed
  manScale / manVPosition / applyManualScale

ScopeCheckBox            -- CheckBox subclass with menuCmd routing
ScopePopupMenu           -- MenuBar with MyCommand("scopepop", …) actions
```

### 2.2. Data Flows  {#C_SCP_02_02}

```
Create
  Docked:   ScopeManager.addScope(elm) -> reuse slot or new Scope; setElm(elm)
  Floating: new ScopeElm(...) -> elmScope = new Scope(cirSim, null)
  Import:   undump(st) -> plots from FLAG_PLOTS or legacy yelm/ivalue stream

Sample (per sim step)
  CirSim.runCircuit
    -> Scope.timeStep()
         -> ScopePlot.timeStep(): update min/max/sample in ring buffer;
            advance ptr only when Δt ≥ maxTimeStep * scopePlotSpeed
         -> if plot2d: drawTo(imageCanvas, x=v, y=yval)

Render (per frame)
  ScopeManager.setupScopes()   -- prune needToRemove, compact, layout rects
  FOR each scope: scope.draw(g)
    -> updateTimeBaseForDraw() -- rolling vs triggered vs frozen
    -> visiblePlots loop calcPlotScale
    -> layered drawPlot (other units → A → V → selected last)
    -> drawInfoTexts, drawCursor
```

## 3. Mechanisms  {#C_SCP_03}

### 3.1. Core Algorithm  {#C_SCP_03_01}

**Ring buffer write.** Each sim step: `sampleValues[ptr] = v`; update `min/maxValues[ptr]`. Advance `ptr = (ptr+1) & mask` only when wall-clock `simulator.t − lastUpdateTime ≥ maxTimeStep * scopePlotSpeed`. Result: multiple sim steps accumulate into one pixel column's (min, max) bar.

**Trigger detection.** `findTriggerIndex(plot, pre, post)` scans back from `ptr` up to `min(spc-2, max(width*4, width+post+2))` pairs. Rising: `prev < level && cur >= level`; falling: inverse. Holdoff suppresses triggers within `triggerHoldoff` seconds of `lastTriggerTime`. Disabled when `showFFT || plot2d || plotXY`.

**Timebase state machine.** AUTO returns rolling window when no trigger; NORMAL captures `TriggerFrame` on trigger, freezes on loss; SINGLE locks after the first trigger until `rearmSingleTrigger()`.

**FFT.** Source is first visible plot, averaged min+max to kill DC; walks ring backwards from `ptr`, zero imag, in-place complex FFT; linear magnitude or `log(m)` scaled to buffer height. X-axis labelled by `drawFFTVerticalGridLines`; `maxFrequency = 1/(maxTimeStep*speed*divs*2)`.

**Stats.** All delegate to `CircuitMath`: `calculateWaveformMetrics` (RMS, average), `calculateDutyCycle`, `calculateAverage` + `calculateFrequency` — accept `(viewWidth, startIndex, ringSize, maxV[], [minV[]], midpoint, …)`. `midpoint = (maxValue+minValue)/2` from `calcMaxAndMin`.

**2D/XY.** `imageCanvas` persists across frames with 1% alpha fade every 3 frames; current (v, yval) is rasterised via `drawTo` during `timeStep`.

**Layout.** `setupScopes()` computes `column_count = max(position)+1`, width = `(canvasWidth − INFO_WIDTH*[3/2 if ≤2 cols]) / colct`, assigns rects row-by-row, unifies `speed` within a column, and calls `renderer.setCircuitArea()` when scopeCount changes.

### 3.2. Edge Cases  {#C_SCP_03_02}

- Hard cap: Scope[20]. `dockScope`/`addScope` silently drop beyond.
- String-identity menu keys (`handleMenu` uses `==` on interned literals).
- `needToRemove()` drops plots whose `elm` is gone; scope dropped when all plots gone.
- `scopeTimeStep != simulator.maxTimeStep` → `resetGraph()`.
- Viewing a `WireElm` forces `canDelayWireProcessing() = false` (per-iteration wire current).

## 4. Integration Points  {#C_SCP_04}

### 4.1. Dependencies  {#C_SCP_04_01}

- **[C_ELB](./element-base.concept.md)** — `CircuitElm.getScopeValue/getScopeUnits/getScopeText/canShowValueInScope`.
- **[C_RND](./rendering-primitives.concept.md)** — `Graphics`, `Canvas`, `Context2d` (save/restore/clipRect/drawImage/setAlpha).
- **[C_MDS](./math-dsp.concept.md)** — `CircuitMath` waveform stats; `util/FFT`.
- **[C_UTL](./util-locale-log.concept.md)** — `Locale.LS`, `CustomLogicModel.escape/unescape` for label text.
- **[C_EDI](./canvas-editor.concept.md)** — `mouseCursorX/Y` feeds scope cursor; `findElmInScope` hit-tests.
- **[C_MEN](./menus-actions.concept.md)** — `ScopePopupMenu`, `MyCommand("scopepop", …)` route.
- **C_DSP** — `ScopePropertiesDialog` for manual scale/trigger/history knobs.
- **C_USR** — `OptionsManager("scopeDefaults")` persistence.

### 4.2. API Surface  {#C_SCP_04_02}

- `ScopeManager`: `addScope/addToScope/dockScope/undockScope/stackScope/unstackScope/combineScope/separateAll/setupScopes/canDelayWireProcessing/getScope/setScope/getScopeCount`.
- `Scope`: `setElm/addElm/addValue/combine/separate/removePlot/timeStep/draw/handleMenu/properties/getFlags/setFlags/dump/undump/saveAsDefault/loadDefaults/speedUp/slowDown/setSpeed/selectScope/checkForSelection/drawCursor`.
- `ScopePlot`: `reset/timeStep/setAcCoupled/applyManualScale/assignColor/startIndex(w)`.
- `ScopeCheckBox`: field `menuCmd`.
- `ScopePopupMenu`: `doScopePopupChecks(floating, canstack, cancombine, canunstack, scope)`.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
