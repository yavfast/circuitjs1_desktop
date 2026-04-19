# Scope Visualization — Specification  {#SP_SCP}

> **Code:** SP_SCP
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_SCP](./scope-visualization.concept.md)
> **Depends on specs:** [SP_ELB](./element-base.sp.md), SP_RND, SP_MDS, SP_UTL, SP_DOC
> **Used by specs:** [SP_EDI](./canvas-editor.sp.md), [SP_MEN](./menus-actions.sp.md), SP_DSP
> **Plan:** [scope-visualization.plan.md](./scope-visualization.plan.md)

## 01. Data Structures  {#SP_SCP_01}

### 01_01. ScopeManager state  {#SP_SCP_01_01}

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| scopes | Scope[20] | all null | Fixed-capacity slot array. |
| scopeCount | int | 0 | Active docked scopes. |
| scopeColCount | int[] | — | Per-column counts after `setupScopes`. |
| scopeSelected / scopeMenuSelected / menuScope | int | −1 | Selection indices. |

### 01_02. Scope state  {#SP_SCP_01_02}

Key fields: `scopePointCount` (pow2, default 128; grown to `nextPow2(rect.width)`); `position`, `speed`, `stackCount`; `rect`; visibility flags; `scale[UNITS_COUNT]`, `reduceRange[]`; trigger state (`triggerEnabled/Mode/Slope/Level/Holdoff/Position/Source`, `singleTriggered`, `singleFrozen*`, `lastTriggerTime`); history (`historyEnabled/Depth/CaptureMode/Source`, `Vector<HistoryFrame>`); `TriggerFrame`; `imageCanvas`; `fft`.

### 01_03. ScopePlot state  {#SP_SCP_01_03}

| Field | Type | Description |
|-------|------|-------------|
| elm | CircuitElm | Source element. |
| units | int | UNITS_V/A/W/OHMS. |
| value | int | VAL_* selector (voltage, current, R, VCE, …). |
| minValues[], maxValues[], sampleValues[] | double[scopePointCount] | Ring buffer. |
| ptr | int | Write head; masked on advance. |
| scopePlotSpeed | int | Sim-steps per pixel column. |
| acAlpha | double | IIR high-pass coefficient (`1 − 1/(1.15·speed·spc)`). |
| manScaleSet / manScale / manVPosition | — | Manual scale overrides. |

### 01_04. Flags & constants  {#SP_SCP_01_04}

Dump flags: `FLAG_YELM=32`, `FLAG_IVALUE=2048`, `FLAG_PLOTS=4096`, `FLAG_PERPLOTFLAGS=1<<18`, `FLAG_PERPLOT_MAN_SCALE=1<<19`, `FLAG_MAN_SCALE=16`, `FLAG_DIVISIONS=1<<21`, `FLAG_TRIGGER=1<<22`, `FLAG_HISTORY=1<<23`.

Trigger modes: AUTO=0, NORMAL=1, SINGLE=2. Slopes: RISING=0, FALLING=1. History capture: MANUAL=0, ON_TRIGGER=1. Units: V=0, A=1, W=2, OHMS=3, COUNT=4. `V_POSITION_STEPS=200`, `multa={2.0,2.5,2.0}`, `MIN_MAN_SCALE=1e-9`.

## 02. Contracts  {#SP_SCP_02}

### 02_01. ScopePlot.timeStep  {#SP_SCP_02_01}

    FUNCTION timeStep():
        v  = elm.getScopeValue(value)
        v' = acCoupled ? acFilter(v) : v
        sampleValues[ptr] = v'
        IF v' < minValues[ptr]: minValues[ptr] = v'
        IF v' > maxValues[ptr]: maxValues[ptr] = v'
        IF simulator.t - lastUpdateTime >= maxTimeStep * scopePlotSpeed:
            ptr = (ptr + 1) & (scopePointCount - 1)
            minValues[ptr] = maxValues[ptr] = sampleValues[ptr] = v'
            lastUpdateTime += maxTimeStep * scopePlotSpeed

### 02_02. Scope.findTriggerIndex  {#SP_SCP_02_02}

    FUNCTION findTriggerIndex(plot, pre, post):
        maxSearch = min(spc - 2, max(width*4, width + post + 2))
        FOR k in 1..maxSearch:
            cur  = sampleValues[(ptr - k) & mask]
            prev = sampleValues[(ptr - k - 1) & mask]
            IF edgeMatches(prev, cur, slope, level):
                triggerTime = timeOfSlot(k)
                IF triggerTime - lastTriggerTime < holdoff: CONTINUE
                RETURN (ptr - k) & mask
        RETURN null

### 02_03. ScopeManager.setupScopes  {#SP_SCP_02_03}

    FUNCTION setupScopes():
        prune scopes where needToRemove(); compact; normalise position
        colct = max(position) + 1
        widthBudget = canvasWidth - INFO_WIDTH * (1.5 if colct<=2 else 1.0)
        w = max(widthBudget / colct, 2 * margin)
        FOR each scope:
            stackCount = scopeColCount[col]
            unify speed across column (call resetGraph if changed)
            rect = (col*w, canvasHeight - h + row*colh, w - margin, colh)
            if rect changed: setRect(rect)
        IF scopeCount changed: renderer.setCircuitArea()

## 03. Validation Rules  {#SP_SCP_03}

- `scopePointCount` is always a power of two.
- `ptr < scopePointCount` always holds post-advance (mask enforces).
- `scopes.length == 20` invariant.
- `triggerEnabled` implies `!showFFT && !plot2d && !plotXY`.
- AC coupling only valid for `UNITS_V`.

## 04. State Transitions  {#SP_SCP_04}

Trigger lifecycle:

| From | To | Trigger | Side effect |
|------|----|---------|-------------|
| AUTO, no trigger | rolling | per frame | null override |
| NORMAL, found trigger | frozen | trigger edge | capture TriggerFrame |
| NORMAL, lost trigger | held | per frame | retain last TriggerFrame |
| SINGLE, found first trigger | locked | trigger edge | singleTriggered=true; freeze |
| SINGLE, locked | armed | rearmSingleTrigger() | clear triggerFrame |

## 05. Verification Criteria  {#SP_SCP_05}

### 05_01. Functional  {#SP_SCP_05_01}

| Contract | Scenario | Input | Expected |
|----------|----------|-------|----------|
| ScopePlot.timeStep | many sim steps | `scopePlotSpeed=10` | one ptr advance per 10 samples |
| findTriggerIndex | rising slope crossing | pre=10, post=100 | returns index of crossing |
| setupScopes | 2 scopes position=0 | — | side-by-side columns |
| FFT | pure sine 1 kHz | voltage plot | magnitude peak near 1 kHz bin |

### 05_02. Invariants  {#SP_SCP_05_02}

| Invariant | Verification |
|-----------|--------------|
| Ring mask correctness | `(idx) & mask == idx mod spc` |
| 0-Hz suppression in FFT | first bin magnitude ≈ 0 for AC-only input |

### 05_03. Edge Cases  {#SP_SCP_05_03}

| Case | Input | Expected |
|------|-------|----------|
| dock beyond 20 | addScope call | silently dropped |
| scopeTimeStep mismatch | maxTimeStep changed | resetGraph() on next draw |
| all plots removed | elm deletion | scope pruned in setupScopes |
| viewing WireElm | any scope | canDelayWireProcessing() = false |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
