# Op-Amp and Analog-Signal Elements  {#C_EOA}

> **Code:** C_EOA
> **Status:** draft
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** OnboardDocGen
>
> **Depends on:** [C_ELB](./element-base.concept.md), [C_GEO](./geometry.concept.md), expression-engine concept
> **Used by:** element factory, editor
> **Spike:** —
> **Specification:** [SP_EOA](./elements-opamps-analog.sp.md)
> **Plan:** [elements-opamps-analog.plan.md](./elements-opamps-analog.plan.md)
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-opamps-analog.md](../.dev_flow/onboard/analysis/domain-core__cat-opamps-analog.md)
>
> Analog-signal active building blocks — op-amps (ideal + composite real),
> hysteresis / threshold devices (Schmitt family, comparator), dependent
> sources driven by user expressions (VCVS, VCCS, CCVS, CCCS, CC2), and
> PLL helpers (VCO, PhaseComp). Uses behavioural equations rather than
> device-physics stamps.

## 1. Philosophy  {#C_EOA_01}

### 1.1. Core Principle  {#C_EOA_01_01}

Two reusable patterns unify the category: (1) **Newton-Raphson piecewise
companion** for amplifier saturation / clamping (OpAmpElm), and (2)
**finite-difference-Jacobian stamp for arbitrary `Expr`** for dependent
sources (VCCS family). Composites (OpAmpRealElm, OTAElm, ComparatorElm)
embed primitive sub-netlists for realistic modeling. Schmitt elements
implement state-machine output clamping with slew limiting.

### 1.2. Design Constraints  {#C_EOA_01_02}

- Inputs on opamps/schmitts are high-impedance probes:
  `getConnection()=false`, only the output has
  `hasGroundConnection(out)=true`.
- VCCS-family stamps partial derivatives `dx` via finite differences
  against the previous sub-iteration, with `Δv = max(v − lastV, 1e-6)`.
- Composite variants (OpAmpRealElm, OTAElm, ComparatorElm) build
  themselves from hard-coded model strings via `CompositeElm.loadComposite`.

## 2. Domain Model  {#C_EOA_02}

### 2.1. Key Entities  {#C_EOA_02_01}

Per-element name list (full catalog in SP_EOA):

- Amplifiers: `OpAmpElm`, `OpAmpSwapElm`, `OpAmpRealElm`, `OTAElm`,
  `ComparatorElm`
- Hysteresis: `SchmittElm`, `InvertingSchmittElm`
- Dependent sources: `VCVSElm`, `VCCSElm`, `CCVSElm`, `CCCSElm`
- Current conveyors: `CC2Elm`, `CC2NegElm`
- PLL helpers: `VCOElm`, `PhaseCompElm`

Total: 15 concrete elements.

### 2.2. Data Flows  {#C_EOA_02_02}

OpAmp `doStep` (three-branch piecewise linear):

    compute vd = V(in+) − V(in-)
    pick region: linear (slope=gain), upper-sat (slope=1e-4, offset=maxOut),
                 lower-sat (slope=1e-4, offset=minOut)
    stamp matrix entries + RHS
    compare vd to lastvd; if |Δvd| > 0.1 or out-of-rail: converged=false
    random jitter to break limit cycles

VCCS-family `doStep`:

    for each input i: exprState.values[i] = V_i
    evaluate v0 = expr.eval(exprState)
    for each input i: numerical partial dx = (expr(v) − expr(v−Δv))/Δv
        stamp appropriate primitive (VC/VS/CCVS/CCCS variant)
        rs -= dx * x_i
    stamp final RHS (or current source)
    convergence: |V_i − lastV_i| > getConvergeLimit() → converged=false

## 3. Mechanisms  {#C_EOA_03}

### 3.1. Core Algorithms  {#C_EOA_03_01}

**Dependent-source stamping primitives:**

| | Input sense | Output kind | Stamp primitive |
|---|---|---|---|
| VCCS | voltage | current | `stampVCCurrentSource(out+, out-, in_i, 0, dx)` |
| VCVS | voltage | voltage | `stampMatrix(vn_out, in_i, -dx)` on VS row |
| CCVS | current (via 0-V sense VS) | voltage | `stampMatrix(vn_out, vn_in_i, -dx)` (both VS rows) |
| CCCS | current (via 0-V sense VS) | current | `stampCCCS(out-, out+, vn_in_i, dx)` |

**Current sensing:** CCVS/CCCS can't directly observe terminal current
(MNA only exposes VS currents). They stamp `stampVoltageSource(n1, n2,
vn, 0)` across each input pair. `setCurrent(vn, c)` demuxes by matching
`voltSource`. Optional `FLAG_SPICE` path substitutes existing external
`VoltageElm`s via `setParentList()`.

**Op-amp random jitter:** `RandomUtils.getRand(4) == 1` kicks out of
limit-cycle lock at the linear/saturation boundary.

**Schmitt state machine:** Hysteresis with two thresholds
(`lowerTrigger`, `upperTrigger`) + slew limit `slewRate·timeStep·1e9` V
(slewRate in V/ns). InvertingSchmitt: state=false + Vi<lower → go high;
state=true + Vi>upper → go low.

**VCO internal oscillation:** Timing capacitor driven by +/-k·Vi,
threshold flip at Vc > 4.5 → output=5V, dir=-1; Vc < 0.5 → output=0,
dir=+1. Permanent 1 MΩ across cap pins to avoid singular matrix.

**Phase comparator:** Type-II PFD with two edge-triggered flip-flops
(`ff1`, `ff2`). Both set → reset both. Output: high if ff1 only, 0 V if
ff2 only, Hi-Z otherwise (stamped by placing `1` on VS diagonal to fix
output current at zero).

### 3.2. Edge Cases  {#C_EOA_03_02}

- OpAmpElm GBW field retained but unused.
- `ComparatorElm` has NO hysteresis — purely sign-of-diff switching to
  ground, relies on op-amp saturation jitter.
- VCCSElm `broken=true` → 100 MΩ resistor stand-in to avoid singular matrix.
- Expr parse error → `Window.alert`; `expr=null`; `doStep` skips stamps.
- PhaseCompElm stamps `stampNonLinear(0)` (ground) — unusual.

## 4. Integration Points  {#C_EOA_04}

### 4.1. Dependencies  {#C_EOA_04_01}

- [C_ELB](./element-base.concept.md) — `CircuitElm`, `ChipElm`,
  `CompositeElm`.
- Expression engine (`Expr` / `ExprParser` / `ExprState`) — VCCS family.
- `RandomUtils` — OpAmpElm jitter.
- CompositeElm model strings embed TransistorElm / DiodeElm / etc.
  from other categories.

### 4.2. API Surface  {#C_EOA_04_02}

Dump-types: `'a'` (97) OpAmp; 401 Comparator, 402 OTA, 409 OpAmpReal;
182 Schmitt, 183 InvertingSchmitt; 212 VCVS, 213 VCCS, 214 CCVS, 215
CCCS; 179 CC2; 158 VCO, 161 PhaseComp. Dump-class aliasing: OpAmpSwap
→ OpAmpElm.class, CC2NegElm → CC2Elm.class.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
