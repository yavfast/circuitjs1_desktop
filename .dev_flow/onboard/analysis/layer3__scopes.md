# Layer 3: Scopes Sub-Unit

Package: `com.lushprojects.circuitjs1.client`
Files (5):
- `src/main/java/com/lushprojects/circuitjs1/client/Scope.java` (2731 lines)
- `src/main/java/com/lushprojects/circuitjs1/client/ScopeManager.java` (302 lines)
- `src/main/java/com/lushprojects/circuitjs1/client/ScopePlot.java` (193 lines)
- `src/main/java/com/lushprojects/circuitjs1/client/ScopeCheckBox.java` (18 lines)
- `src/main/java/com/lushprojects/circuitjs1/client/ScopePopupMenu.java` (71 lines)

## Purpose

Implements the in-circuit oscilloscope subsystem: time-domain multi-trace plotting, XY/2D mode, FFT spectrum, cursor readout, triggering (auto/normal/single, rising/falling, holdoff, pre-trigger position), history/persistence overlay, manual and auto vertical scaling, AC coupling per plot, RMS/average/duty-cycle/frequency statistics. Two hosting modes: docked scopes arranged in columns at bottom of canvas (owned by `ScopeManager`), and undocked/floating scopes embedded inside a `ScopeElm` circuit element (`elmScope`). Concept boundary is one "scope-visualization" cluster: Scope (widget), ScopePlot (trace), ScopeManager (document-level layout), ScopeCheckBox/ScopePopupMenu (UI glue).

## Per-file Key Entities

### Scope.java (`Scope extends BaseCirSimDelegate`)

Constants/flags (Scope.java:38-80):
- Dump flags: `FLAG_YELM=32`, `FLAG_IVALUE=2048`, `FLAG_PLOTS=4096`, `FLAG_PERPLOTFLAGS=1<<18`, `FLAG_PERPLOT_MAN_SCALE=1<<19`, `FLAG_MAN_SCALE=16`, `FLAG_DIVISIONS=1<<21`, `FLAG_TRIGGER=1<<22`, `FLAG_HISTORY=1<<23`.
- Trigger modes: `TRIG_MODE_AUTO=0`, `TRIG_MODE_NORMAL=1`, `TRIG_MODE_SINGLE=2`.
- Trigger slopes: `TRIG_SLOPE_RISING=0`, `TRIG_SLOPE_FALLING=1`.
- History modes: `HISTORY_CAPTURE_MANUAL=0`, `HISTORY_CAPTURE_ON_TRIGGER=1`.
- Value IDs: `VAL_VOLTAGE=0`, `VAL_CURRENT=3`, `VAL_POWER=7`/`VAL_POWER_OLD=1`, `VAL_R=2`, transistor values `VAL_IB/IC/IE/VBE/VBC/VCE`.
- Units: `UNITS_V=0`, `UNITS_A=1`, `UNITS_W=2`, `UNITS_OHMS=3`, `UNITS_COUNT=4`.
- `V_POSITION_STEPS=200`, `multa={2.0,2.5,2.0}` for grid step sequencing, `MIN_MAN_SCALE=1e-9`.

Fields (Scope.java:81-189):
- `scopePointCount=128` — power-of-two ring buffer size (per plot). Grows to next power of 2 ≥ rect.width at reset (Scope.java:314-316).
- `FFT fft`, `position` (column), `speed` (timesteps per pixel), `stackCount`, `Rectangle rect`.
- Visibility flags: `showI, showV, showScale, showMax, showMin, showFreq, showFFT, showNegative, showRMS, showAverage, showDutyCycle, showElmInfo, logSpectrum, plot2d, plotXY, maxScale, manualScale`.
- `Vector<ScopePlot> plots, visiblePlots` — all plots vs currently visible subset (after `calcVisiblePlots`).
- Rendering: `imageCanvas` (GWT Canvas for 2D mode persistence), `graphics`, `draw_ox/draw_oy` (XY last point), `alphaCounter` (2D fade-out).
- Scale state: `double[] scale` per unit, `boolean[] reduceRange`, `scaleX/scaleY` (2D), `gridStepX/Y`, `maxValue/minValue`, `manDivisions` (static `lastManDivisions`).
- Cursor: `static cursorTime, cursorUnits, cursorScope` (one global cursor shared across all scopes).
- Trigger state (Scope.java:122-143): `triggerEnabled`, `triggerMode/Slope/Level/Holdoff/Position/Source`, `singleTriggered`, `singleFrozen*`, `lastTriggerTime`, `lastTriggeredStartIndex`, timebase overrides `timeBaseStartIndexOverride/RightEdgeTime/TriggerTime`, `lastDisplayedStartIndex`.
- History (Scope.java:146-165): inner class `HistoryFrame(width, minValues[], maxValues[], color)`; `historyEnabled/Depth/CaptureMode/Source`, `Vector<HistoryFrame> historyFrames`, `lastHistoryCapturedStartIndex`.
- Trigger frame (Scope.java:168-189): inner class `TriggerFrame(width, plotCount, minValues[][], maxValues[][], startIndex, rightEdgeTime, triggerTime)` — snapshot of ring-buffer window at trigger moment for stable display.

Key methods:
- `Scope(BaseCirSim, CircuitDocument)` (Scope.java:243-254) — construct canvas, `initialize()` → `resetGraph()`.
- `initialize()` (732), `resetGraph(boolean full)` (313-332) — resizes ring buffers to next pow-2 ≥ width, clears history & trigger frame, reallocates image canvas.
- `setElm/addElm/setValue/addValue/setValues` (800-876) — attaches `CircuitElm` and creates ScopePlot(s); transistors default to VCE, others to voltage (+ optional current plot if "dots" menu on).
- `combine(Scope)`/`separate(Scope[], int)` (910-936) — stacked-scope merging/splitting.
- `removePlot(int)` (938).
- `calcVisiblePlots()` (757-782) — filters plots into `visiblePlots` per showV/showI/others, assigns color via `ScopePlot.assignColor(count)`.
- `timeStep()` (959-994) — per-sim-step: each plot samples; for `plot2d` mode also rasters current (v, yval) into `imageCanvas` via `drawTo()`.
- `draw(Graphics)` (1273-1380) — main render entry.
- Trigger logic: `findTriggerIndex(plot, pre, post)` (529-564), `updateTimeBaseForDraw()` (566-664), `captureTriggerFrame(startIndex, rightEdgeTime, triggerTime)` (210-241), `rearmSingleTrigger()` (408-418).
- History: `captureHistoryFrame(plot, startIndex)` (666-684), `captureHistoryNow()` (461-470), `clearHistory()`, `trimHistoryToDepth()`.
- Statistics: `drawRMS/drawAverage/drawDutyCycle/drawFrequency` (1879-1982) delegate to `CircuitMath`.
- FFT: `drawFFT(g)` (1069-1129), `drawFFTVerticalGridLines(g)` (1047-1067).
- 2D/XY: `draw2d(g)` (1157-1232), `clear2dView()` (1007-1018), `drawTo(x,y)` (996-1005), `calc2dGridPx(w,h,div)` (953-956).
- Main plot: `drawPlot(g, plot, allPlotsSameUnits, selected, allSelected)` (1504-1728); `calcPlotScale(plot)` (1427-1477); `calcMaxAndMin(units)` (1384-1424); `calcGridStepX()` (1483-1492); `getGridMaxFromManScale(div, manScale)` (1500-1502).
- Cursor: `selectScope(mouseX, mouseY)` (1735-1746), `checkForSelection(mouseX, mouseY)` (1749-1780), `checkForSelectionElsewhere()` (1249-1271), `drawCursor(g)` (1782-1838), `drawCursorInfo(...)` (1840-1868), `clearCursorInfo()` (1730-1733).
- UI dispatch: `handleMenu(mi, state)` (2511-2600) — string-command router for scope checkbox/menu items.
- Dump/undump: `getFlags()` (2179-2211), `dump()` (2231-2289), `undump(StringTokenizer)` (2291-2457), `setFlags(flags)` (2459-2475), `saveAsDefault()`/`loadDefaults()` (2477-2499).
- Speed control: `speedUp/slowDown` (2137-2149), `setSpeed(int)` (2093-2102), `onMouseWheel(e)` (2637-2647).
- Misc: `needToRemove()` (2676-2693), `viewingWire()` (2656), `getElm/getXElm/getYElm`, `properties()` (2133) — shows `ScopePropertiesDialog`.

### ScopeManager.java (`ScopeManager extends BaseCirSimDelegate`)

Fields (6-23): fixed-capacity `Scope[] scopes = new Scope[20]`, `int scopeCount`, `int[] scopeColCount`, `scopeSelected/scopeMenuSelected/menuScope` indices.

Methods:
- IO accessors: `getScopeCount/setScopeCount/getScope/setScope` (26-43).
- `updateScopes()` (45-49) — re-applies rects.
- `scopeMenuIsSelected(Scope)` (53-59) — handles both docked (index < scopeCount) and floating (index ≥ scopeCount → via `CircuitSimulator.getNthScopeElm`).
- `canDelayWireProcessing()` (64-75) — tells solver whether to compute wire currents every iteration (required if any scope or `ScopeElm` views a `WireElm`).
- Docking: `dockScope(CircuitElm)` (77-84) steals an `elmScope` from a `ScopeElm`; `undockScope(ScopeElm)` (86-95) puts the scope back into a new `ScopeElm`.
- Stack/combine: `canStackScope/canUnstackScope/canCombineScope` (97-124); `stackScope/unstackScope/combineScope/stackAll/unstackAll/combineAll/separateAll` (126-206) — manipulate `position` field of scopes to create multi-scope columns.
- `addScope(CircuitElm)` (208-224) — reuses empty slot or appends; copies speed from previous.
- `addToScope(int n, CircuitElm)` (226-234) — dispatches to docked or `ScopeElm.elmScope` by n.
- `setupScopes()` (236-300) — per-frame layout pass:
  1. Prune `needToRemove()` scopes, compact array, normalize `position` indices.
  2. Compute column count (`pos+1`), scope column populations (`scopeColCount`).
  3. Allocate width = `(canvasWidth - INFO_WIDTH*[3/2 if ≤2 cols]) / colct`, min width `2*margin`.
  4. For each scope set `stackCount`, unify `speed` across a column (calling `resetGraph` if changed), assign `Rectangle(col*w, canvasHeight-h + row*colh, w-marg, colh)` and call `setRect()` if changed.
  5. If scopeCount changed → `renderer.setCircuitArea()`.

### ScopePlot.java

One trace. Constructors (47-69) store `CircuitElm elm`, `int units`, optional `value` and `manScale`. Factory `create(...)`.

Buffer fields (10-13): `double[] minValues, maxValues, sampleValues` (all length `scopePointCount`), `int ptr` (current write index), `int scopePointCount`, `int scopePlotSpeed`.

AC coupling (42-44, 120-124): IIR high-pass `y[i] = alpha·(y[i-1] + x[i] - x[i-1])`, coefficient `acAlpha = 1 - 1/(1.15·speed·scopePointCount)` set in `reset()` (89). Always computed so switch to AC is primed.

Manual scale (36-38): `manScaleSet`, `manScale` (units/div), `manVPosition` (−100..+100 where ±V_POSITION_STEPS/2 = top/bottom). For UNITS_OHMS/UNITS_W default position is bottom (63-64). `applyManualScale(scale, pos)` (71-75).

Methods:
- `startIndex(w)` (77-79): `return ptr + scopePointCount - w` — left edge of viewport in the ring.
- `reset(spc, sp, full)` (81-111) — grows/shrinks ring buffer preserving aligned data when possible via `(ptr - i) & (oldSpc - 1)` remap unless speed changed or `full=true`.
- `timeStep()` (113-136) — sample from `elm.getScopeValue(value)`, update AC filter, write `sampleValues[ptr]`, update `minValues[ptr]/maxValues[ptr]`. Advance `ptr = (ptr+1) & (scopePointCount-1)` only when wall-clock `simulator.t - lastUpdateTime ≥ maxTimeStep * scopePlotSpeed` (many sim steps per pixel get aggregated into a single (min,max) bar).
- `getUnitText(v)` (138-150) → `CircuitElm.getVoltageText/getCurrentText/getUnitText`.
- `assignColor(count)` (157-173) — first plot of each unit uses theme colors (foreground V, yellow/dark-yellow A); additional plots cycle through `colors[]` palette of 8 hues.
- `setAcCoupled/canAcCouple/isAcCoupled` (175-188) — only UNITS_V allowed. `getPlotFlags()` (190-192) → `FLAG_AC=1`.

### ScopeCheckBox.java (18 lines)

`CheckBox` subclass with `public String menuCmd` tying a checkbox to a scope popup-menu command string (consumed by `Scope.handleMenu`). Overrides `setValue(boolean)` to skip a no-op change.

### ScopePopupMenu.java (71 lines)

GWT `MenuBar` with `removeScopeItem, dockItem, undockItem, maxScaleItem, stackItem, unstackItem, combineItem, removePlotItem, resetItem, propertiesItem` — all wired to `new MyCommand("scopepop", "<action>")`. `doScopePopupChecks(floating, canstack, cancombine, canunstack, Scope)` (55-65) updates visibility/enabled state; dock visible only for floating (ScopeElm-embedded) scope, stack/unstack/combine only for docked.

## Scope Lifecycle

1. **Create**
   - Docked: `ScopeManager.addScope(CircuitElm)` (208) reuses slot or `new Scope(cirSim, doc)` + `setElm(ce)`.
   - Floating: `new ScopeElm(...)` constructs `elmScope = new Scope(cirSim(), null)` (ScopeElm:38) then `setElm(elmScope, this)`.
   - Import: `undump(st)` builds plots from `FLAG_PLOTS` (new-style) or legacy (yelm/ivalue) token stream.

2. **Attach to element/node**
   - `Scope.setElm(ce)` (800) → `plots = new Vector<>()` + `addValue(val, ce)` (830) creates primary voltage plot plus optional current plot (for non-output elements when dots view is on). `addElm(ce)` adds without reset.
   - Multiple elements combined: `combine(Scope)` (910) merges `visiblePlots` lists.

3. **Sample**
   - Every sim step: `CirSim.runCircuit` → Scope.timeStep() (959) → per-plot `ScopePlot.timeStep()` (113) feeds ring buffer min/max/sample from `elm.getScopeValue(value)`.
   - 2D mode rasterizes to `imageCanvas` immediately during timeStep.

4. **Render** (each GWT frame)
   - `CirSim` paint calls `ScopeManager.setupScopes()` (layout) then iterates `scopes[i].draw(g)`.
   - `Scope.draw` (1273) → `updateTimeBaseForDraw()` (566) chooses rolling vs triggered vs frozen frame; loops `visiblePlots` calling `calcPlotScale`; then layered `drawPlot` passes (other → A → V → selected on top); draws info texts; draws shared cursor.
   - FFT, grid, history overlay, trigger marker all drawn in `drawPlot` or bridging helpers.

5. **Remove**
   - `needToRemove()` (2676) — called by `ScopeManager.setupScopes()` first thing, prunes plots whose `elm` no longer located; scope dropped when all plots gone.
   - Menu: "Remove Scope" / "Remove Plot" → `Scope.removePlot(int)` (938) via `handleMenu`/`ScopePopupMenu` path.
   - Undock: `ScopeManager.undockScope(new ScopeElm)` (86-95) moves to floating; dock: `dockScope(ce)` (77-84) steals back.

## Sample Buffer Format

Ring buffer (per ScopePlot), **power-of-two size** so index math uses `& (scopePointCount-1)` mask everywhere (e.g., ScopePlot.java:100-101, 131; Scope.java:232, 1415, 1458).

Three parallel arrays of `double`, all length `scopePointCount`:
- `sampleValues[]` — last raw (or AC-filtered) sample at that ring slot (used by trigger detector).
- `minValues[ptr]`, `maxValues[ptr]` — aggregated min/max over the pixel-column (because `speed` timesteps collapse into one pixel). Drawn as vertical bars in `drawPlot`.

Write protocol (ScopePlot.timeStep 113-136):
- Every sim step: update `sampleValues[ptr] = v`; `if (v < minValues[ptr]) minValues[ptr] = v; if (v > maxValues[ptr]) maxValues[ptr] = v;`.
- Only when `simulator.t - lastUpdateTime ≥ maxTimeStep*scopePlotSpeed` advance `ptr = (ptr+1) & mask`, initialize new slot to `v`, push `lastUpdateTime` forward.

Read protocol: viewport is last `rect.width` pixels; start index `ptr + scopePointCount - w` masked. When `timeBaseStartIndexOverride != null` (trigger frozen), use that instead. When `triggerFrame` used, the frame has already pre-extracted a contiguous `width`-long array so no masking is needed (`useFrame` branches in `drawPlot`/`calcPlotScale`/`calcMaxAndMin`).

Not a sliding window in the classical sense — it is a rolling ring buffer with pixel-column aggregation; sliding is achieved by advancing `ptr`.

## FFT and Statistics Usage

FFT (Scope.java:1069-1129):
- Allocates `FFT(scopePointCount)` lazily/when size changes.
- Source: first visible plot (or `plots.firstElement()`). Uses `real[i] = 0.5 * (maxValues[ii] + minValues[ii])` averaged (comment: avoids 0-Hz spike from DC bias).
- Walks ring backwards from `ptr`, zero imag, computes FFT in place.
- Linear mode: plot magnitude scaled to `maxM` in buffer range, drawn bottom-up.
- Log mode (`logSpectrum`): `y = y0 - (log(m)*ymult - log(scale[u])*ymult)`.
- X-axis labeled via `drawFFTVerticalGridLines` — 20 divisions, `maxFrequency = 1/(maxTimeStep*speed*divs*2)`.
- Cursor over FFT reports `maxFrequency * x/rect.width` in Hz (drawCursor 1810-1815).

Statistics — all delegate to `CircuitMath` (see root-utils.md):
- `CircuitMath.calculateWaveformMetrics(width, startIndex, ringSize, maxValues, minValues, midpoint)` → `WaveformMetrics{rms, average, valid}` — used by `drawRMS` (1879) and `drawAverage` (1947).
- `CircuitMath.calculateDutyCycle(width, startIndex, ringSize, maxV, minV, midpoint)` → `DutyCycleInfo{dutyCycle, valid}` — `drawDutyCycle` (1958).
- `CircuitMath.calculateAverage(width, ipa, ringSize, minV, maxV)` then `CircuitMath.calculateFrequency(width, ipa, ringSize, maxV, avg, maxTimeStep, speed)` → `FreqData{frequency, ...}` — `drawFrequency` (1970).
- `midpoint = (maxValue + minValue)/2` from `calcMaxAndMin` — shared threshold for RMS/avg/duty.

## Trigger Modes

State (Scope.java:122-143): `triggerEnabled`, `triggerMode` (auto/normal/single), `triggerSlope` (rising/falling), `triggerLevel`, `triggerHoldoff` (s), `triggerPosition` (0..1 fraction of pre-trigger), `triggerSource` (index in visiblePlots).

Edge detection `findTriggerIndex(plot, pre, post)` (529-564):
- Scans back from `ptr` up to `min(scopePointCount-2, max(width*4, width+post+2))` steps.
- For each pair (prev, cur): rising: `prev < level && cur >= level`; falling: `prev > level && cur <= level`.
- Respects holdoff: skips if `triggerTime - lastTriggerTime < triggerHoldoff`.

Timebase state machine `updateTimeBaseForDraw()` (566-664):
- AUTO (default): no trigger found → null override → rolling window.
- NORMAL: trigger found → sets `timeBaseStartIndexOverride` to `(triggerIndex - pre) & mask`, captures `TriggerFrame` so subsequent draws use that snapshot; if not found → retain last `triggerFrame` (freezes display).
- SINGLE: same as NORMAL on first trigger, then `singleTriggered=true` locks `singleFrozenStartIndex/RightEdgeTime/TriggerTime`. `rearmSingleTrigger()` (408) clears to arm again.
- Trigger marker drawn as vertical line at `getTriggerPixelX()` (Scope.java:1642-1649).
- Disabled when `showFFT || plot2d || plotXY` (`isTriggerAvailable()` 334).

History/persistence (when `historyEnabled && isHistoryAvailable()`):
- `HISTORY_CAPTURE_ON_TRIGGER`: on every new triggered startIndex, `captureHistoryFrame` copies current ring window into `HistoryFrame` (Scope.java:620-625).
- `HISTORY_CAPTURE_MANUAL`: only `captureHistoryNow()` adds frames.
- `historyDepth` 1..64, `trimHistoryToDepth()` drops oldest.
- Drawn as dimmed overlay in `drawPlot` (1655-1677) with alpha `0.08 + 0.32*(i+1)/(n+1)`.

## Rendering (Graphics + CircuitMath)

Main render path — `Scope.draw(g)` (1273-1380):
1. If `scopeTimeStep != simulator.maxTimeStep` → `resetGraph()`.
2. If `plot2d` → `draw2d(g)` (1157) and return (handled below).
3. `drawSettingsWheel(g)` (1131-1155) — small gear icon bottom-left for dialog.
4. `g.save(); g.translate(rect.x, rect.y); g.clipRect(0,0,rect.width,rect.height);`
5. `updateTimeBaseForDraw()`.
6. If `showFFT` → `drawFFTVerticalGridLines(g); drawFFT(g);`.
7. Reset `reduceRange`, if `maxScale && !manualScale` reset all `scale[i]=1e-4` (rescaling from scratch).
8. Per visible plot: `calcPlotScale(plot)` (1427) — auto-power-of-two growth unless `manualScale`; set `somethingSelected` if elm is mouse-elm; set `reduceRange[plot.units]`.
9. `checkForSelectionElsewhere()` (1249) — pick a plot matching the global `cursorUnits` if another scope owns cursor.
10. If `allPlotsSameUnits || showMax || showMin` → `calcMaxAndMin(firstUnits)` (1384).
11. **Layered drawing**: non-V/A units first, then A units, then V units (voltage on top), finally the selected plot. Each `drawPlot(g, plot, allPlotsSameUnits, selected, allSelected)`:
    - Picks color (override to gray if somethingSelected, theme select color if selected).
    - Reads source: ring buffer with `(i + ipa) & mask` or `triggerFrame.maxValues[pi][i]` when frozen.
    - Computes `gridMax, gridMid, positionOffset` (manual uses `getGridMaxFromManScale(manDivisions, manScale)`; auto re-centers when `allPlotsSameUnits`).
    - Sets `plot.plotOffset = -gridMid + positionOffset`, `plot.gridMult = maxy / gridMax`.
    - Computes `gridStepY` (auto: sequence of `multa[multptr%3]`, manual: `manScale`); `gridStepX = calcGridStepX()` (sequence seeded from `1e-15` up to `ts*20`).
    - Draws 201 horizontal gridlines (centered on 0, stepped by `gridStepY`), vertical gridlines every `gridStepX` from `rightEdgeTime` leftward (major every 10th).
    - If trigger armed+frozen, draws trigger pixel line.
    - History overlay loop (behind live trace, per-frame alpha).
    - Main plot loop: for x in 0..width, draw vertical segment from `maxy - minvy` to `maxy - maxvy`, collapsing runs where `minvy==maxvy==prev` into a single horizontal line at trace exit.
12. `drawInfoTexts(g)` (2002) — stacked overlay (textY accumulator) with: scale text, Max, Min, RMS, Average, Duty, label/text, Frequency, elm info.
13. `g.restore(); drawCursor(g);`
14. Auto-scale shrink: `if (plots.get(0).ptr > 5 && !manualScale)` halve `scale[i]` for each unit where `reduceRange[i]` still true (never saw out-of-range data this frame).
15. If properties dialog open, `properties.refreshDraw()`.

2D/XY mode — `draw2d(g)` (1157-1232):
- Maintains persistent `imageCanvas`, fades 1% alpha every 3 frames.
- Draws horizontal and vertical axes; manual scale adds grid at `calc2dGridPx`.
- Cursor inside rect shows `x/y` unit text converted back to engineering units.

Cursor drawing (`drawCursor` 1782-1838):
- Single global cursor (`cursorScope` static) cross-shared among all scopes.
- `cursorX = rect.x + rect.width - (rightEdgeTime-cursorTime)/(dt)`.
- Plots an filled oval at current selected-plot Y; info stack: value, optional `dt=<>` from trigger, time text.
- `drawCursorInfo` (1840-1868): draws highlighted X line across rect, fills background strip above rect, writes info above rect.

CircuitMath helpers used (see `root-utils.md`):
- `calculateAverage`, `calculateFrequency`, `calculateWaveformMetrics`, `calculateDutyCycle` — all accept `(viewWidth, startIndex, ringBufferSize, maxValues[], [minValues[],] midpoint|avg, ...)`, respect ring-buffer indexing internally.

## Integration Points

- **ScopeElm** (`element/ScopeElm.java`) — embeds `Scope elmScope = new Scope(cirSim(), null)`; delegates `timeStep() → elmScope.timeStep()`, `resetGraph(true)`, `draw(g) → elmScope.draw(g)` with `elmScope.position = -1` marker (floating). Dump/undump wraps `elmScope.dump()` stripping the "o_" prefix (ScopeElm:111,115). ScopeManager treats these as n ≥ scopeCount via `simulator.getNthScopeElm(n-scopeCount)` (ScopeManager.java:58, 72, 232).
- **CircuitElm.getScopeValue(value)**, **getScopeUnits(value)**, **getScopeText(value)**, **canShowValueInScope(value)** — contract implemented by every element to expose measurable quantities; special-cased: `TransistorElm` gets VCE/IC/IB/IE/VBE/VBC, generic elms get voltage(0)+power, others may offer resistance `VAL_R`.
- **CircuitMath** (`root-utils.md`) — waveform statistics and FFT-friendly aggregations.
- **FFT** (`util/FFT`) — in-place complex FFT, `magnitude(re, im)`.
- **Graphics/Canvas** — GWT `com.google.gwt.canvas.client.Canvas` + `Context2d`; wrapped by `Graphics` helper (save/restore, translate, clipRect, setAlpha, measureWidth, fillRect, drawLine, drawString, fillOval, drawImage).
- **ColorSettings** — theme-aware colors (printable vs normal) threaded through grid/foreground/background/select/positive colors.
- **ScopePropertiesDialog** (see `domain-core__dialog-specialized.md`) — opened via `properties()` (Scope.java:2133) or gear-wheel click; editable: manual scale per plot, manVPosition, trigger level/mode, history depth. Calls `Scope.nextHighestScale()` (static on dialog) for rounding.
- **CirSim.menuManager** — provides `printableCheckItem`, `dotsCheckItem` consulted for color selection and auto-add-current logic.
- **OptionsManager** (`scopeDefaults`) — persists default flags+speed across sessions.
- **MyCommand("scopepop", ...)** — popup menu actions routed through the CirSim command dispatcher which eventually calls `ScopeManager.combineScope`/etc. and `Scope.handleMenu`.
- **CircuitDocument / BaseCirSimDelegate** — Scope and ScopeManager both extend `BaseCirSimDelegate` giving access to `simulator()`, `renderer()`, `circuitEditor()`, `scopeManager()`, `cirSim`.
- **ScopeCheckBox** consumed by `ScopePropertiesDialog` and menu code to bind checkbox state ↔ scope flags via `menuCmd` (handled by `Scope.handleMenu`).
- **AudioOutputElm/LogicOutputElm/OutputElm/ProbeElm** excluded from automatic current plot in `addValue` (Scope.java:836-842).
- **WireElm** special-cased: if any scope views a wire, `canDelayWireProcessing()` returns false forcing per-iteration wire-current computation.

## Issues / Code Smells

1. **Fixed array capacity** — `Scope[20]` hard-coded in `ScopeManager` (line 20), `scopeColCount` same; silently drops scopes beyond 20 (`dockScope` returns, `addScope` returns).
2. **String-identity menu keys** — `handleMenu` uses `==` instead of `.equals` on interned string literals (Scope.java:2512-2598); works only because literals are interned at both call sites, brittle to refactors.
3. **Static cursor state** — `cursorTime/cursorUnits/cursorScope` static shared across all Scope instances; multi-document support would break. Similarly `lastManDivisions` global.
4. **Oversized Scope class** — 2731 lines mixing rendering, state machine, stats, dump, UI, canvas management. Prime candidate for splitting (render, trigger controller, buffer, persistence).
5. **`UNUSED` constructors / dead comments** — e.g., commented-out `select()` (2602-2608); `Locale.LS(...)` called on already-prepared labels in a few places.
6. **Ring buffer size growth heuristic** — always doubles to ≥ `rect.width`; a very tall/thin scope keeps small buffer, but FFT resolution = `scopePointCount`, so FFT quality is tied to scope width.
7. **Trigger search cost** — O(maxSearch) per frame per scope; fine but could be prohibitive with many triggered scopes + high speed.
8. **`FLAG_PLOTS` legacy/new split** — two full parser branches in `undump` with largely duplicated trigger/history parsing (Scope.java:2366-2446).
9. **`text != ""` object-identity comparison** (Scope.java:2033) — should be `!text.isEmpty()`.
10. **FFT magnitude uses `maxM=1e-8` floor** only for linear; in log mode `val0 = log(scale[u])*ymult` uses current scale which may not match FFT dynamic range.
11. **History frame allocation per capture** — O(width) doubles twice, accumulating in `Vector<HistoryFrame>`; with depth 64 + wide scopes that's ~64·2·W·8 bytes, tolerable but not pooled.
12. **Visibility mismatch**: `ScopePlot` fields are package-private but some are referenced by `Scope` across the package boundary; mixing `public` and package-private inconsistently (e.g., `position`, `speed` public; `stackCount` package; `rect` public).
13. **Reflection-style trigger re-arm** — SINGLE-mode `triggerFrame=null` on `setTriggerMode` & `rearmSingleTrigger` is easy to get out of sync with `singleTriggered` flag.
14. `ScopeCheckBox.setValue(boolean)` ignores event firing when state unchanged; GWT default behavior is the same, so the override is essentially redundant.

## Concept Boundary

Single concept: **scope-visualization** — time/frequency/XY display of element-level signals, decoupled from the simulator via the `CircuitElm.getScopeValue/getScopeUnits` contract and from the document via `ScopeManager` (array slot allocation, layout, docking between standalone scopes and `ScopeElm` wrappers).

Boundaries:
- Upward to Concept-simulation / CircuitElm: reads `getScopeValue` per sim step; influences solver only via `canDelayWireProcessing()` (wire-current-per-iteration hint).
- Upward to Dialog: `ScopePropertiesDialog` (separate concept — specialized dialogs).
- Upward to IO / Persistence: `dump/undump/saveAsDefault/loadDefaults` — textual serialization compatible with legacy CircuitJS files; `ScopeManager.getScope/setScope` accessors for export.
- Upward to Editor: cursor readout coupled to `CircuitEditor.mouseCursorX/Y`; menu invocation via `MyCommand("scopepop", ...)` routed through the same editor-command pipeline used elsewhere.
- Downward to Utils: `CircuitMath` (waveform metrics) and `util.FFT`, `util.Locale`, `CustomLogicModel` (escape/unescape label text).
- Downward to Widgets: `ScopeCheckBox`, `CheckboxMenuItem`, `CheckboxAlignedMenuItem`, `MenuBar` — Scope-specific GWT UI primitives.

`ScopeElm` is the bridge between this concept and the element-catalog concept: it contains a Scope but is owned as an element; `ScopeManager.dockScope`/`undockScope` migrate ownership.
