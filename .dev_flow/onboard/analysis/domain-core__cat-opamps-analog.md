# Category Analysis: domain-core / opamps-and-analog-signal

> **Path:** src/main/java/com/lushprojects/circuitjs1/client/element/ (15 files)
> **Layer:** 2 (SCC-A, element catalogue)
> **Analyzed:** 2026-04-18
> **Total LOC:** ≈ 2,883

## Purpose

The `opamps-and-analog-signal` category bundles the **analog-signal active
building blocks** of CircuitJS1 — the class of elements that model linear /
quasi-linear gain stages with behavioural rather than device-physics
equations. Three orthogonal axes:

1. **Amplifiers** (`OpAmpElm`, `OpAmpRealElm`, `OpAmpSwapElm`, `OTAElm`,
   `ComparatorElm`) — high-gain differential input, one signal output,
   with output clamping or quasi-digital thresholding.
2. **Hysteresis / threshold devices** (`SchmittElm`, `InvertingSchmittElm`)
   — digital-valued output with two thresholds and slew limiting.
3. **Dependent sources & signal building blocks** (`VCVSElm`, `VCCSElm`,
   `CCVSElm`, `CCCSElm`, `CC2Elm`/`CC2NegElm`, `VCOElm`, `PhaseCompElm`)
   — user-expression-driven controlled sources, second-generation current
   conveyors, plus two hand-coded PLL helpers.

Unlike the semiconductor category (diodes / BJTs / MOSFETs) which stamps
physics-based Shockley / Ebers-Moll equations, this category stamps
**high-level behavioural equations**. Consequently many of these elements
rely on the `Expr` / `ExprParser` / `ExprState` triad from the project
root — the same expression engine used by the arbitrary waveform.

## Per-element catalog

| Element | Extends | Posts | V-sources | Linear? | Dump-type | Parameters | File:lines |
|---|---|---|---|---|---|---|---|
| `OpAmpElm` | `CircuitElm` | 3 (in−, in+, out) | 1 | no (`nonLinear()` true) | `'a'` (97) | `maxOut`, `minOut`, `gbw` *(retained, unused)*, `gain`, saved V(in−)/V(in+) | OpAmpElm.java:33-392 |
| `OpAmpSwapElm` | `OpAmpElm` | 3 | 1 | no | — (`getDumpClass() == OpAmpElm.class`) | inherits; sets `FLAG_SWAP` | OpAmpSwapElm.java:24-36 |
| `OpAmpRealElm` | `CompositeElm` | 5 (in−, in+, out, V+, V−) | via subnetlist | depends on children | `409` | `modelType` (LM741 / LM324 / LM324v2), `slewRate`, `currentLimit`, `capValue` | OpAmpRealElm.java:14-369 |
| `OTAElm` | `CompositeElm` | 5 (in+, in−, Iabc, Ibias, out) | via subnetlist | depends on children | `402` | `posVolt` (+5…+20 V), `negVolt` (−20…−5 V) | OTAElm.java:12-211 |
| `ComparatorElm` | `CompositeElm` | 3 (in−, in+, out) | via subnetlist (OpAmpElm + AnalogSwitch + Ground) | yes (inherits children) | `401` | `FLAG_SMALL`, `FLAG_SWAP`, drawing size | ComparatorElm.java:12-152 |
| `SchmittElm` | `InvertingSchmittElm` | 2 | 1 | yes | `182` | inherits; non-inverting transfer fn | SchmittElm.java:31-123 |
| `InvertingSchmittElm` | `CircuitElm` | 2 | 1 | yes | `183` | `slewRate` (V/ns), `lowerTrigger`, `upperTrigger`, `logicOnLevel`, `logicOffLevel` | InvertingSchmittElm.java:33-247 |
| `VCVSElm` | `VCCSElm` | `inputCount + 2` | 1 | no | `212` | `expr`, `exprString`, `inputCount` | VCVSElm.java:27-149 |
| `VCCSElm` | `ChipElm` | `inputCount + 2` | 0 | no | `213` | `expr`, `exprString`, `inputCount` | VCCSElm.java:34-274 |
| `CCVSElm` | `VCCSElm` | `inputCount + 2` (pairs on input) | `1` spice / `1 + inputPairCount` normal | no | `214` | `expr`, `inputCount` (even), `FLAG_SPICE` | CCVSElm.java:31-267 |
| `CCCSElm` | `VCCSElm` | `inputCount + 2` | `0` spice / `inputPairCount` normal | no | `215` | `expr`, `inputCount` (even), `FLAG_SPICE` | CCCSElm.java:31-269 |
| `CC2Elm` | `ChipElm` | 3 (X, Y, Z) | 1 | yes (linear stamps) | `179` | `gain` (±1) | CC2Elm.java:28-119 |
| `CC2NegElm` | `CC2Elm` | 3 | 1 | yes | — (`getDumpClass() == CC2Elm.class`) | `gain = -1` | CC2NegElm.java:5-12 |
| `VCOElm` | `ChipElm` | 6 (Vi, Vo, C, C, R1, R2) | 3 | no | `158` | `cResistance = 1e6` (const), internal `cDir` | VCOElm.java:28-142 |
| `PhaseCompElm` | `ChipElm` | 3 (I1, I2, O) | 1 | no | `161` | internal `ff1`, `ff2` edge-triggered flip-flops | PhaseCompElm.java:28-103 |

## Shared patterns

### 1. Op-amp saturation / clamping via Newton-Raphson pivot

`OpAmpElm.doStep()` (OpAmpElm.java:198-236) is the canonical ideal-op-amp
stamp. Three-branch piecewise linear model:

- **Linear region** `|vd| < maxAdj/gain` → slope `dx = gain`, offset `x =
  midpoint`. Stamps `(vn, in−) = +gain`, `(vn, in+) = −gain`,
  `(vn, out) = 1`, RHS = midpoint.
- **Upper saturation** → slope `dx = 1e-4` (tiny), offset `x = maxOut`.
- **Lower saturation** → slope `dx = 1e-4`, offset `x = minOut`.

Convergence forcing: each step compares `vd` to `lastvd`; if
`|Δvd| > 0.1 V` or the output is outside `[minOut, maxOut]` by more than
0.1 V, `simulator.converged = false`. Random jitter
(`RandomUtils.getRand(4) == 1` at OpAmpElm.java:211,214) kicks it out of
limit-cycle lock when `vd` straddles zero.

### 2. Gain-dependent stamping for dependent sources

The `VCCSElm` family (VCCSElm, VCVSElm, CCVSElm, CCCSElm) all share the
same algorithm at VCCSElm.java:118-179:

1. Set `exprState.values[i] = V_i` for every input.
2. Evaluate `v0 = expr.eval(exprState)` (current / voltage target).
3. For each input `i`, numerically differentiate
   `dx = (expr(v) − expr(v − Δv)) / Δv` with `Δv = max(v − lastV, 1e-6)`.
4. Stamp the partial `dx` via the appropriate primitive:
   - `VCCSElm` → `stampVCCurrentSource(out+, out−, in_i, 0, dx)`
   - `VCVSElm` → `stampMatrix(vn, in_i, −dx)` (output side of VS row)
   - `CCVSElm` → `stampMatrix(outVS_row, inVS_row, −dx)`
     (differentiates w.r.t. input-sense-VS **current**, not voltage)
   - `CCCSElm` → `stampCCCS(out−, out+, inVS, dx)`
5. Adjust RHS by `rs -= dx * x_i` and stamp final RHS (or current src).
6. Convergence gate: `Math.abs(V_i − lastV_i) > getConvergeLimit()`
   (VCCSElm.java:101-108 — 0.001 V initially, relaxed to 0.1 V after 200
   sub-iterations).

This is a **Newton-Raphson companion model for an arbitrary nonlinear
controlled source**, with the Jacobian approximated by finite differences
against the previous sub-iteration. It works for any `Expr` tree.

### 3. Current-sensing via zero-volt voltage sources

CCVS / CCCS cannot directly observe terminal current — MNA only exposes
voltage-source currents. They bridge this by stamping a
`stampVoltageSource(n1, n2, vn, 0)` across each input pair (CCVSElm.java:85-88,
CCCSElm.java:84-87). The solver then sets `pins[i+1].current = +I_vs` on
each solve, and `setCurrent(vn, c)` demuxes by matching `voltSource`.

Optional `FLAG_SPICE` path (CCVSElm.java:186-187, CCCSElm.java:194-196)
substitutes existing externally-supplied `VoltageElm`s via
`setParentList()` (CCVSElm.java:219, CCCSElm.java:221) — skipping the
zero-volt dummy sources. Used when importing spice subcircuits.

### 4. Composite-based modelling for realistic analog elements

`OpAmpRealElm`, `OTAElm`, `ComparatorElm` extend `CompositeElm` and build
themselves from a hard-coded string-dump of primitive children:

- `OpAmpRealElm` — three model strings: `model741String` (22 transistors
  + 11 resistors + 1 cap), `lm324ModelString`, `lm324v2ModelString` (ON
  Semi 2018 Gen model). Slew rate is tuned by multiplying compensation
  capacitance (OpAmpRealElm.java:138, 161). Current limit adjusts output
  resistors + transistor betas.
- `OTAElm` — 2 × RailElm (±9 V supplies), 15 transistors, with visible
  Iabc / Ibias current-programming input.
- `ComparatorElm` — tiny: `OpAmpElm + AnalogSwitchElm + GroundElm` —
  an op-amp whose sign is used to gate an analog switch to ground,
  producing Hi-Z / 0 V quasi-digital output.

Hitting the comparator case, the hysteresis is **not** configurable —
any hysteresis comes from the OpAmpElm's own saturation at the rail
voltages of the internal op-amp. A true hysteretic comparator requires
the Schmitt classes.

## Ideal vs real op-amps

| Aspect | `OpAmpElm` (ideal) | `OpAmpRealElm` (real) |
|---|---|---|
| Class strategy | Behavioural stamp | Composite of transistor-level model |
| Input impedance | ∞ (no input connection — `getConnection() == false`, only output touches ground) | Finite (determined by transistor physics) |
| Output impedance | ≈ 0 (voltage source) | Finite (output-stage resistors) |
| Slew rate | Not modelled | Compensation-cap value scaled inversely to `slewRate`: `C = 30e-12 / (slewRate/.6)` at OpAmpRealElm.java:138 |
| Saturation | Hard clamp via piecewise-linear stamp | Soft — rails are the V+/V− supply pins |
| Current limit | Not modelled (output can sink/source ∞) | Adjusts output-stage resistors + betas (OpAmpRealElm.java:149-152) |
| Supply pins | None — internally referenced | Real V+/V− posts (posts 3, 4) |
| Convergence | Newton-Raphson with 3 linear pieces + random shake-off | Inherits nonlinearity from composite transistors |
| GBW | Field persisted but unused (see OpAmpElm.java:60-62 and dump at :93) | Determined by transistor model dynamics |

Both share: 3 externally-visible signal posts (in−, in+, out), inverting
input on post 0. `FLAG_SWAP` flips the pin layout for `+` on top instead
of `-` on top (purely cosmetic — `OpAmpSwapElm` sets this flag in the
constructor at OpAmpSwapElm.java:27).

## Schmitt / Comparator distinctions

Three different abstractions for "output switches when input crosses
threshold":

### `ComparatorElm`  (not hysteretic)
- Composed of an ideal op-amp driving an analog switch to ground.
- **No** programmable thresholds or hysteresis — switch point = 0 V.
- Output is either 0 V (low) or Hi-Z (high) — literally tri-stated,
  hence menu label "Comparator (Hi-Z/GND output)" in docs/elements.md.
- Hysteresis = 0 (aside from the ideal op-amp's internal saturation
  limit-cycle jitter).

### `InvertingSchmittElm` / `SchmittElm`  (hysteretic, digital output)
- Single-input, single-output, 2-terminal element.
- State machine with two thresholds (`upperTrigger`, `lowerTrigger`)
  and `boolean state` (InvertingSchmittElm.java:37, 130-149). **Not** a
  companion model — the output is clamped via `updateVoltageSource`, not
  stamped against a derivative.
- Inverting transfer:
  - `state=false` (out low) → only goes high when Vi < lowerTrigger
  - `state=true` (out high) → only goes low when Vi > upperTrigger
- Non-inverting transfer (`SchmittElm` overrides `doStep()` at
  SchmittElm.java:53-77): same state machine, opposite output polarity.
- **Slew limiting** (InvertingSchmittElm.java:152-153): output rate
  capped at `slewRate * timeStep * 1e9` V — note the `1e9` because
  `slewRate` is specified in V/ns, not V/s.
- Editor auto-fixes `lowerTrigger > upperTrigger` swap
  (InvertingSchmittElm.java:200-207).
- Validates that both thresholds ∈ [0.01, 5] V
  (InvertingSchmittElm.java:170-174).

## Dependent-source family

**No** common `GeneralDependentSource` subclass exists. The family is
structured by inheritance from `VCCSElm`:

```
ChipElm
  └── VCCSElm            (voltage-in, current-out: the root)
        ├── VCVSElm      (voltage-in, voltage-out)
        ├── CCVSElm      (current-in, voltage-out)
        └── CCCSElm      (current-in, current-out)
```

All four reuse the same `Expr` evaluator, `ExprState` cache,
`getConvergeLimit()` (VCCSElm.java:101), `sign(a,b)` helper
(VCCSElm.java:97), and the numerical-derivative Jacobian pattern. The
differences are:

| | Input sense | Output kind | Stamp primitive (per input) |
|---|---|---|---|
| VCCS | voltage | current | `stampVCCurrentSource(out+, out−, in_i, 0, dx)` |
| VCVS | voltage | voltage | `stampMatrix(vn_out, in_i, −dx)` on a voltage-source row |
| CCVS | current (via 0-V sense VS) | voltage | `stampMatrix(vn_out, vn_in_i, −dx)` — both rows are VS rows |
| CCCS | current (via 0-V sense VS) | current | `stampCCCS(out−, out+, vn_in_i, dx)` |

**Pin layout convention** (common):
- Inputs on SIDE_W, labelled `A, B, C, …` (single-ended) or
  `A+, A−, B+, B−, …` (differential, CCVS/CCCS).
- Outputs on SIDE_E, labelled `V+/V−` (VCVS, CCVS) or `C+/C−` / `O+/O−`
  (VCCS, CCCS).

**Expression variables**: single letters `a, b, c, …` mapped to
`exprState.values[0..inputCount−1]`. CCVS / CCCS additionally expose `i`
as a legacy alias for the first current input (CCVSElm.java:158-163).

**`broken` flag**: VCCSElm.java:40, 122-128 — set by the simulator
when the output has no DC current path; `doStep()` stamps a 100 MΩ
resistor as a safety to avoid singular-matrix errors. CCCSElm inherits.

### `hasCurrentOutput()` / `getOutputNode(n)`

These two hooks (VCCSElm.java:110-116, VCVSElm.java:127-129,
CCVSElm.java:181-183, CCCSElm.java:190-192) let external analysis code
(e.g. wire-closure) understand which terminal is the controlled output.

## Current conveyors (CCII+ / CCII−)

`CC2Elm` is remarkably compact (CC2Elm.java:78-90): three linear stamps.
- `stampVoltageSource(0, X_node, vs)` — X is anchored to ground-reference
  (raises the voltage source).
- `stampVCVS(0, Y_node, 1, vs)` — Y follows X's voltage (buffer).
- `stampCCCS(0, Z_node, vs, gain)` — Z current = ±1 × X current.

`gain` is `+1` (CCII+) or `−1` (CC2NegElm). `calculateCurrent()` at
CC2Elm.java:87-90 mirrors X's current into Z, also scaled by `gain`.
Pins: `X` (output, SIDE_W), `Y` (SIDE_W), `Z` (output, SIDE_E). This is
a **purely linear** element — `nonLinear()` is not overridden (commented
out at line 72).

## VCO: internal oscillator state

`VCOElm` is a hand-rolled **timing-capacitor oscillator** (VCOElm.java:28-142).

Six posts: `Vi` (control), `Vo` (output), two cap pins `C`, two charging
pins `R1` (sense-VS measuring input current = `k·Vi`), `R2` (5 V supply
sourcing a constant current).

Internal state (line 77-78): `cCurrent` (latched capacitor current),
`cDir` (+1 charging / −1 discharging).

Oscillation logic (`doStep()`, VCOElm.java:80-107):
1. Read cap voltage `vc = V(C−) − V(C+)` and current output `vo`.
2. Direction: `+1` while `vo < 2.5 V`, `−1` while `vo > 2.5 V`.
3. Threshold flip:
   - If `vo < 2.5 && vc > 4.5` → latch `vo = 5`, `dir = −1` (full high).
   - If `vo > 2.5 && vc < 0.5` → latch `vo = 0`, `dir = +1` (full low).
4. Stamp output voltage source at `vo`.
5. Route summed current from R1+R2 into the capacitor via four
   `stampMatrix` entries with sign `±dir` — the capacitor charges at a
   rate proportional to `Vi`.

A 1 MΩ `cResistance` is permanently stamped across the cap pins to
prevent singular matrices if the user omits the external capacitor
(VCOElm.java:71, 76).

**Frequency:** determined by user-supplied external R + C across the two
C pins; Vi gates the R1 current. Output swing: 0–5 V.

## Phase comparator (Type II — tri-state PFD)

`PhaseCompElm` implements a **charge-pump phase-frequency detector** on
edges of I1 vs I2 (PhaseCompElm.java:66-87). Two internal edge-triggered
flip-flops `ff1`, `ff2`:
- `ff1` set on rising edge of I1, `ff2` set on rising edge of I2.
- When both set simultaneously, both clear (reset).
- Output: `high` if ff1 only, `0 V` if ff2 only, **Hi-Z** if neither.

Hi-Z is modelled by stamping a `1` on the VS-row diagonal
(PhaseCompElm.java:82-83) — effectively fixes the output-VS current to
zero, which with the downstream loop filter integrates to "leave the
voltage where it was."

Threshold = `getThreshold()` inherited from `ChipElm` (default `half of
highVoltage`).

## Validation rules

- `OpAmpElm.setGain()`: gain clamped to 10–1,000,000 via EditInfo range
  (OpAmpElm.java:262). Legacy dumps without `FLAG_GAIN` fall back to
  1,000 (`FLAG_LOWGAIN`) or 100,000 (OpAmpElm.java:82-89). Comment at
  line 86-87 notes: 100,000 broke `e-amp-dfdx.txt`; 1,000 broke
  `amp-schmitt.txt` — hence the flag.
- `OpAmpElm` forces `simulator.converged = false` when output strays
  beyond `[minOut − 0.1, maxOut + 0.1]` (OpAmpElm.java:204-205).
- `OpAmpRealElm.setEditValue(n==0)` triggers full `initModel()` re-build
  and `ei.newDialog = true` so the dialog refits the changed parameter
  set (the 324v2 model hides the slew-rate / current-limit fields).
- `OTAElm` validates `posVolt ∈ [5,20]`, `negVolt ∈ [−20,−5]` via
  EditInfo ranges (OTAElm.java:173-175) but not programmatically.
- `InvertingSchmittElm.setEditValue` (line 200-207) auto-swaps
  lower/upper trigger if the user inverts them. Threshold range: 0.01–5 V.
- `VCCSElm.parseExpr` `Window.alert`s on parse errors (VCCSElm.java:248)
  — the element remains in a broken state, `expr == null` → `doStep()`
  skips stamping, `broken=true` path kicks in.
- `CCVSElm.setChipEditValue / CCCSElm.setEditValue` reject odd input
  counts (CCVSElm.java:209, CCCSElm.java:211) because inputs are
  differential pairs.
- `CCVSElm.getConnection(n1,n2) = n1/2 == n2/2` — pairs are treated as
  mutually connected, but pair-to-pair is isolated.
- `OpAmpElm.getConnection() = false` always; `hasGroundConnection(2)`
  (output) is true — so analysis knows output is DC-referenced.
- `VCOElm` / `PhaseCompElm` rely on the external user R/C for actual
  frequency; stamping a fallback 1 MΩ resistor across the cap pins
  (VCOElm.java:71) to avoid singular matrices.

## Integration points (element-base)

From the lifecycle contract described in `domain-core__element-base.md`:

- **Non-linear flag.** `OpAmpElm.nonLinear()=true` (line 96),
  `VCCSElm.nonLinear()=true` (line 83), `VCOElm.nonLinear()=true`
  (line 57), `PhaseCompElm.nonLinear()=true` (line 52). `CC2Elm` is
  linear despite its comment on line 72. Composite elements
  (`OpAmpRealElm`, `OTAElm`, `ComparatorElm`) inherit nonlinearity from
  their children via `CompositeElm.nonLinear()` (OR over children).
- **Voltage-source count override.** `OpAmpElm=1`, `VCVSElm=1`,
  `VCCSElm=0`, `CCVSElm=1 or 1+pairs`, `CCCSElm=0 or pairs`, `CC2Elm=1`,
  `VCOElm=3`, `PhaseCompElm=1`. All override the single `voltSource`
  default correctly via the `ChipElm.setVoltageSource(j, vs)` hook.
- **`setCurrent(vn, c)` routing.** VCVS/CCVS/CCCS override
  (VCVSElm.java:131, CCVSElm.java:189, CCCSElm.java:198) to dispatch by
  matching `voltSource` index to the correct pin and mirror it into the
  paired pin with opposite sign. OpAmpElm does not override — it uses
  the default single-source path.
- **`getCurrentIntoNode(n)` overrides.** OpAmpElm returns `-current` for
  output post only (line 280-284) — inputs are strictly zero-current.
  Schmitt variants likewise expose current only at output (InvertingSchmittElm.java:222).
- **Composite sub-element access.** `OpAmpRealElm.getCapacitor()` at
  line 184-188 reaches into `compElmList` by index to tweak the
  compensation cap — tight coupling to model-string ordering.
- **`getConnection()` convention.** All three-terminal amplifiers return
  `false` universally (OpAmpElm, ComparatorElm, OTAElm — no input-to-
  output DC path). `CC2Elm` and `VCCS`-family expose pair-connectivity
  logic.
- **Dump-class decoupling.** `OpAmpSwapElm.getDumpClass() == OpAmpElm.class`
  and `CC2NegElm.getDumpClass() == CC2Elm.class` (OpAmpSwapElm.java:30,
  CC2NegElm.java:10) — both convenience subclasses share persistence
  with their parent; the distinguishing state is a flag or a constructor
  parameter, not a new dump type.
- **JSON pin names.** Every element overrides `getJsonPinNames()` with a
  canonical labelled list — `OpAmp` swaps `in-`/`in+` depending on
  `FLAG_SWAP` (OpAmpElm.java:326-331).
- **Start/end JSON anchors.** OpAmpElm overrides both
  `getJsonStartPoint()` / `getJsonEndPoint()` (line 333-345) because
  `point1` is the body anchor, not a pin — referenced in the
  element-base `getJsonStartPoint()` comment.

## Issues / questions

1. **GBW retained but unused** (OpAmpElm.java:60-62, 316-318). The
   comment admits "GBW has no effect in this version of the simulator,
   but we retain it to keep the file format the same." Ideal op-amp thus
   has infinite bandwidth — users who need bandwidth must pick
   `OpAmpRealElm`. Candidate for either implementation or deprecation +
   file-format migration.
2. **Random jitter in Newton-Raphson** (OpAmpElm.java:211, 214:
   `RandomUtils.getRand(4) == 1`). Non-determinism in the solver path
   is unusual and may be surprising when debugging convergence; it's a
   hack to break limit cycles in the linear-region / saturation
   boundary. Worth a comment block explaining *why*.
3. **`ComparatorElm` lacks hysteresis.** The menu label says
   "Comparator (Hi-Z/GND output)" — correct, but many users expect a
   comparator to have at least a small programmable hysteresis. The
   current implementation relies on the internal op-amp's saturation
   random-jitter alone, which can chatter. The dedicated
   (Inverting)SchmittElm exists, but "Schmitt" is a different menu
   category (signal-processing / logic-shaper) — naming is confusing.
4. **`VCOElm` has fixed 0–5 V output**, 2.5 V threshold, 1 MΩ fallback.
   These are magic numbers; no EditInfo rows. For a PLL demo it's
   adequate but rigid.
5. **`PhaseCompElm.stamp()` calls `stampNonLinear(0)`** (line 60) — node
   0 is ground, usually treated as always linear; stamping it non-linear
   is unusual. Is this a safe no-op?
6. **CCVSElm stamp for spice vs normal** uses `voltageSources[]` filled
   in by `setParentList()` — but the array is `new VoltageElm[
   inputPairCount]` (CCVSElm.java:226) and may contain nulls if no
   matching VoltageElm is found inside the composite. Null dereferenced
   at CCVSElm.java:82 / CCCSElm.java:81 will NPE. Defensive check
   missing.
7. **Expr parse failure behaviour.** `parseExpr()` alerts the user via
   GWT `Window.alert` (VCCSElm.java:248). This blocks the entire
   element-creation flow; expr is left null; `doStep()` then skips
   everything. An inline error marker on the element would be more
   discoverable.
8. **OpAmpRealElm model-string child indices are magic.** Resistor slot
   21+i at line 144, capacitor at `compElmList.get(modelType == 741 ? 20
   : 4)`. Any re-ordering of the model-string silently breaks slew-rate
   tuning.
9. **`SchmittElm extends InvertingSchmittElm`**: semantically backwards
   (non-inverting is the default, not a special case of inverting), and
   the `state` field interpretation differs between the two
   (InvertingSchmittElm.java:133 vs SchmittElm.java:55). A shared
   `SchmittBase` would clarify.
10. **InvertingSchmittElm `slewRate` field in V/ns** (not V/s), whereas
    `OpAmpRealElm.slewRate` is V/µs, and `VCOElm` has no slew-rate field.
    Inconsistent units across the category.
11. **`CC2Elm` commented-out `nonLinear()=true`** (line 72) — hints the
    element was once nonlinear; worth clarifying in a comment whether
    the current linear stamp is a modelling simplification or a bug-fix.
12. **CCCS doStep missing `broken` handling for pairs.** The top-level
    `broken` check (CCCSElm.java:99-105) stamps only the output pair —
    but if any single input pair lacks a connection it is not detected.

## Suggested concept boundary

**Single concept: `opamps-and-analog-signal`.** Rationale:

- All 15 elements are **behavioural analog** — they sit above the
  physics layer (diodes, transistors, passives) and below the
  digital-logic chips.
- The five biggest files (VCCSElm, CCVSElm, CCCSElm, OpAmpElm,
  OpAmpRealElm) collectively implement the two reusable patterns that
  make this concept cohere: **Newton-Raphson piecewise companion
  model** (op-amp saturation) and **finite-difference-Jacobian stamp
  for arbitrary `Expr`** (dependent sources). Splitting would duplicate
  these patterns across concepts.
- `ComparatorElm`, `OTAElm`, `OpAmpRealElm` are structurally composites
  but are most usefully documented alongside their ideal counterparts
  for comparison (see "Ideal vs real op-amps" table above).
- `VCOElm` / `PhaseCompElm` are technically "Analog and Hybrid Chips"
  menu-wise, but both rely on the analog-feedback idioms (Newton-Raphson,
  non-digital thresholding) rather than the flip-flop / gate digital
  idioms. They fit this concept better than `digital-chips`.
- `CC2Elm` / `CC2NegElm` are purely linear stamps but are catalogued
  next to op-amps and OTAs in docs/elements.md ("Active Building
  Blocks") and functionally belong here — controlled-source behavioural
  building blocks.

If a finer split becomes desirable:

1. **opamp-amplifiers** — `OpAmpElm`, `OpAmpSwapElm`, `OpAmpRealElm`,
   `OTAElm`, `ComparatorElm`.
2. **hysteresis-elements** — `SchmittElm`, `InvertingSchmittElm`.
3. **dependent-sources** — `VCVSElm`, `VCCSElm`, `CCVSElm`, `CCCSElm`,
   `CC2Elm`, `CC2NegElm`.
4. **pll-helpers** — `VCOElm`, `PhaseCompElm`.

The sub-splits (2), (3), (4) are mostly independent; split (1) shares
the "diff-input → clamped output" pattern and is the biggest. But the
unifying NR-companion-for-behavioural-equations narrative is best told
as a single concept — recommend keeping them together.
