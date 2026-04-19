# Op-Amp and Analog-Signal Elements — Specification  {#SP_EOA}

> **Code:** SP_EOA
> **Status:** draft
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_EOA](./elements-opamps-analog.concept.md)
> **Depends on specs:** [SP_ELB](./element-base.sp.md), [SP_GEO](./geometry.sp.md), expression-engine spec
> **Used by specs:** io-framework, editor
> **Plan:** [elements-opamps-analog.plan.md](./elements-opamps-analog.plan.md)
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-opamps-analog.md](../.dev_flow/onboard/analysis/domain-core__cat-opamps-analog.md)
>
> Catalog and contracts for the 15 op-amp / analog-signal elements.

## 01. Data Structures  {#SP_EOA_01}

### 01_01. Per-element catalog  {#SP_EOA_01_01}

| Element | Extends | Posts | V-sources | Linear? | Dump-type | Parameters | File:lines |
|---|---|---|---|---|---|---|---|
| OpAmpElm | CircuitElm | 3 (in-, in+, out) | 1 | no | `'a'` (97) | maxOut, minOut, gbw (unused), gain, saved V(in-)/V(in+) | OpAmpElm.java:33-392 |
| OpAmpSwapElm | OpAmpElm | 3 | 1 | no | — (getDumpClass=OpAmpElm.class) | inherits; sets FLAG_SWAP | OpAmpSwapElm.java:24-36 |
| OpAmpRealElm | CompositeElm | 5 (in-, in+, out, V+, V-) | via subnetlist | depends on children | 409 | modelType (LM741 / LM324 / LM324v2), slewRate, currentLimit, capValue | OpAmpRealElm.java:14-369 |
| OTAElm | CompositeElm | 5 (in+, in-, Iabc, Ibias, out) | via subnetlist | depends on children | 402 | posVolt (+5..+20V), negVolt (-20..-5V) | OTAElm.java:12-211 |
| ComparatorElm | CompositeElm | 3 (in-, in+, out) | via subnetlist (OpAmp+Switch+Ground) | yes (inherits children) | 401 | FLAG_SMALL, FLAG_SWAP, drawing size | ComparatorElm.java:12-152 |
| SchmittElm | InvertingSchmittElm | 2 | 1 | yes | 182 | inherits; non-inverting transfer | SchmittElm.java:31-123 |
| InvertingSchmittElm | CircuitElm | 2 | 1 | yes | 183 | slewRate (V/ns), lowerTrigger, upperTrigger, logicOnLevel, logicOffLevel | InvertingSchmittElm.java:33-247 |
| VCVSElm | VCCSElm | inputCount+2 | 1 | no | 212 | expr, exprString, inputCount | VCVSElm.java:27-149 |
| VCCSElm | ChipElm | inputCount+2 | 0 | no | 213 | expr, exprString, inputCount | VCCSElm.java:34-274 |
| CCVSElm | VCCSElm | inputCount+2 (pairs) | 1 spice / 1+inputPairCount normal | no | 214 | expr, inputCount (even), FLAG_SPICE | CCVSElm.java:31-267 |
| CCCSElm | VCCSElm | inputCount+2 (pairs) | 0 spice / inputPairCount normal | no | 215 | expr, inputCount (even), FLAG_SPICE | CCCSElm.java:31-269 |
| CC2Elm | ChipElm | 3 (X, Y, Z) | 1 | yes (linear stamps) | 179 | gain (±1) | CC2Elm.java:28-119 |
| CC2NegElm | CC2Elm | 3 | 1 | yes | — (getDumpClass=CC2Elm.class) | gain=-1 | CC2NegElm.java:5-12 |
| VCOElm | ChipElm | 6 (Vi, Vo, C, C, R1, R2) | 3 | no | 158 | cResistance=1e6 const, internal cDir | VCOElm.java:28-142 |
| PhaseCompElm | ChipElm | 3 (I1, I2, O) | 1 | no | 161 | internal ff1, ff2 | PhaseCompElm.java:28-103 |

## 02. Contracts  {#SP_EOA_02}

### 02_01. OpAmpElm piecewise-linear companion  {#SP_EOA_02_01}

Three regions:

| Region | Condition | Slope dx | Offset x |
|--------|-----------|----------|----------|
| linear | `|vd| < maxAdj/gain` | `gain` | midpoint |
| upper sat | `vd > maxAdj/gain` | `1e-4` | `maxOut` |
| lower sat | `vd < −maxAdj/gain` | `1e-4` | `minOut` |

Stamp: `(vn, in-) = +gain`, `(vn, in+) = −gain`, `(vn, out) = 1`,
RHS = offset. Convergence: `|Δvd| > 0.1 V` or out-of-rail by >0.1 V →
`converged=false`. `RandomUtils.getRand(4) == 1` kicks limit cycles.

### 02_02. VCCS-family finite-difference Jacobian  {#SP_EOA_02_02}

For each Newton iteration:

1. Set `exprState.values[i] = V_i` for every input `i ∈ [0..inputCount)`.
2. Evaluate `v0 = expr.eval(exprState)`.
3. For each input i: `Δv = max(v − lastV, 1e-6)`,
   `dx = (expr(v) − expr(v − Δv)) / Δv`.
4. Stamp partial via appropriate primitive (per SP_EOA_01 table).
5. `rs -= dx * x_i`; stamp final RHS (or current source).
6. If `|V_i − lastV_i| > getConvergeLimit()` → `converged=false`.

`getConvergeLimit()` = 0.001 V initially, relaxed to 0.1 V after 200
sub-iterations.

### 02_03. CCVS/CCCS current sensing  {#SP_EOA_02_03}

- Stamp `stampVoltageSource(n1, n2, vn, 0)` across each input pair.
- Solver sets `pins[i+1].current = +I_vs`.
- `setCurrent(vn, c)` demuxes by matching `voltSource` to pin index.
- `FLAG_SPICE` path: skip zero-volt dummy sources; use externally
  supplied `VoltageElm`s resolved via `setParentList()`.

### 02_04. Schmitt state machine  {#SP_EOA_02_04}

InvertingSchmittElm:

    state=false (out at logicOffLevel):
        if V(in) < lowerTrigger: state = true
    state=true (out at logicOnLevel):
        if V(in) > upperTrigger: state = false

Non-inverting `SchmittElm`: opposite polarity. Slew: per-step output
change capped at `slewRate * timeStep * 1e9` V (note the 1e9 because
slewRate is in V/ns).

### 02_05. Composite analog elements  {#SP_EOA_02_05}

- `OpAmpRealElm` — three model strings (`model741String`,
  `lm324ModelString`, `lm324v2ModelString`). Slew rate tuned via
  compensation cap `C = 30e-12 / (slewRate / 0.6)`. Current limit
  adjusts output-stage resistors + transistor betas.
- `OTAElm` — 2 RailElm supplies (±9 V) + 15 transistors with
  Iabc / Ibias programming inputs.
- `ComparatorElm` — OpAmpElm + AnalogSwitchElm + GroundElm. Op-amp's
  sign gates switch to ground, producing 0 V / Hi-Z output.

### 02_06. VCO internal oscillator  {#SP_EOA_02_06}

- 6 posts: Vi (control), Vo (output), 2 cap pins, R1 (sense-VS = k·Vi),
  R2 (5 V supply).
- Internal state: `cCurrent`, `cDir ∈ {+1, -1}`.
- Thresholds: `vo<2.5 && vc>4.5` → latch `vo=5`, `dir=-1`;
  `vo>2.5 && vc<0.5` → latch `vo=0`, `dir=+1`.
- Output swing: 0–5 V. Frequency set by external R, C; Vi modulates.
- Permanent 1 MΩ across cap pins to avoid singular matrix.

### 02_07. Phase comparator  {#SP_EOA_02_07}

- Two edge-triggered flip-flops `ff1`, `ff2`.
- `ff1` set on rising I1; `ff2` set on rising I2.
- Both set → both reset.
- Output: high if ff1 only, 0 V if ff2 only, Hi-Z (stamp `1` on VS
  diagonal) otherwise.
- Threshold: inherited `getThreshold()` (half highVoltage).

## 03. Validation Rules  {#SP_EOA_03}

- OpAmpElm: `gain ∈ [10, 1_000_000]` via EditInfo. Legacy `FLAG_LOWGAIN`
  → 1000; no flag → 100000.
- OpAmpElm: out-of-rail by >0.1 V → `converged=false`.
- OpAmpRealElm: editing model rebuilds composite (`ei.newDialog = true`).
- OTAElm: EditInfo range enforces `posVolt ∈ [5,20]`, `negVolt ∈ [-20,-5]`.
- InvertingSchmittElm: auto-swap lower/upper if inverted; thresholds
  ∈ [0.01, 5] V.
- VCCSElm: Expr parse error → `Window.alert`; expr=null → doStep skips.
- CCVSElm/CCCSElm: odd inputCount rejected (inputs are differential
  pairs).
- CCVSElm `getConnection(n1,n2) = n1/2 == n2/2`.
- OpAmpElm `getConnection() = false`, `hasGroundConnection(2)=true`.

## 04. State Transitions  {#SP_EOA_04}

Schmitt state machine — see 02_04. VCO direction flip — see 02_06.

## 05. Verification Criteria  {#SP_EOA_05}

### 05_01. Functional Expectations  {#SP_EOA_05_01}

| Element | Scenario | Expected |
|---------|----------|----------|
| OpAmpElm | linear region | `Vout = gain · Vd`, clipped to [minOut, maxOut] |
| InvertingSchmittElm | Vi crosses upperTrigger | output snaps low (with slew) |
| VCCSElm | expr = `a*b` | output current = `V(in0)·V(in1)` |
| VCOElm | Vi step | output frequency changes accordingly |
| PhaseCompElm | two aligned clocks | output Hi-Z steady |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
