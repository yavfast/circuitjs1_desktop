# Diode and Semiconductor Elements  {#C_EDS}

> **Code:** C_EDS
> **Status:** draft
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** OnboardDocGen
>
> **Depends on:** [C_ELB](./element-base.concept.md), [C_SHM](./shared-models.concept.md), [C_GEO](./geometry.concept.md), [C_RND](./rendering-primitives.concept.md)
> **Used by:** element factory, editor
> **Spike:** —
> **Specification:** [SP_EDS](./elements-diodes-semis.sp.md)
> **Plan:** [elements-diodes-semis.plan.md](./elements-diodes-semis.plan.md)
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-diodes-semis.md](../.dev_flow/onboard/analysis/domain-core__cat-diodes-semis.md)
>
> Every 2- or 3-terminal semiconductor device **other than** BJT/FET/OTA:
> the PN-junction family (diode, zener, varactor, tunnel diode), LEDs,
> the latching thyristor family (SCR, Triac, Diac), plus two composites
> (Unijunction, Optocoupler).

## 1. Philosophy  {#C_EDS_01}

### 1.1. Core Principle  {#C_EDS_01_01}

Almost every element is a configuration of PN junctions (via the shared
`Diode` Newton-Raphson helper) plus linear glue. Three structural
patterns: DiodeElm-subclass (Zener, LED, Varactor), hand-rolled
multi-Diode container (SCR, Triac, Diac, LEDArray), and CompositeElm
subclass (Unijunction, Optocoupler). TunnelDiodeElm is the lone outlier
with its own triple-exponential law.

### 1.2. Design Constraints  {#C_EDS_01_02}

- `nonLinear() == true` for every element in the category.
- Shared solver: `client/Diode.java` (not a CircuitElm — a numerical
  strategy object). Its `stamp(n0, n1)` registers nodes non-linear; all
  conductance/current-source stamping happens in `doStep(voltdiff)` per
  Newton iteration.
- Shared catalog: `DiodeModel` (see C_SHM). Most elements bind by name
  via `DiodeModel.getModelWithNameOrCopy`; SCR/Triac/Diac use
  `setupForDefaultModel()`; TunnelDiode is unparameterized.

## 2. Domain Model  {#C_EDS_02}

### 2.1. Key Entities  {#C_EDS_02_01}

Per-element name list (full catalog in SP_EDS):

- PN-junction family: `DiodeElm`, `ZenerElm`, `LEDElm`, `VaractorElm`,
  `LEDArrayElm`, `TunnelDiodeElm`
- Thyristor latch family: `SCRElm`, `TriacElm`, `DiacElm`
- Composite semis: `UnijunctionElm`, `OptocouplerElm`

Total: 11 concrete elements plus one shared helper (`client/Diode.java`).

### 2.2. Data Flows  {#C_EDS_02_02}

Per Newton iteration (DiodeElm flow):

    doStep(voltdiff) -> Diode.doStep(voltdiff):
        limitStep(vnew, vold)    -- cap forward / Zener excursions
        stampConductance(geq)    -- linearised Shockley
        stampCurrentSource(nc)   -- parallel current
        (zener branch adds second exponential for reverse bias)

Latching (Triac/Diac): boolean `state` updated in `startIteration`
(Triac: gate/holding current thresholds; Diac: voltage-breakdown +
hold-current). SCR recomputes latching inequality inside `doStep`
every Newton iteration (no persistent `state`).

## 3. Mechanisms  {#C_EDS_03}

### 3.1. Core Algorithms  {#C_EDS_03_01}

**`Diode` helper Newton step:** Shockley `I = IS·(e^(v·vdcoef) − 1)`
linearized to `geq = vdcoef·IS·eval + gmin`, parallel current
`nc = (eval − 1)·IS − geq·v`. Zener branch adds
`e^((−v−zoffset)·vzcoef)` when `voltdiff < 0 && zvoltage != 0`.
`gmin` ramps with `subIterations` (more aggressive under
`getConvergencePanicLevel() > 0`). `limitStep` caps `|Δv| ≤ 2·vscale`
forward / `2·vt` Zener (10× in panic mode).

**TunnelDiodeElm triple-exponential:** Hard-coded 1N3712-like constants.
Three terms: reverse-biased tail, characteristic negative-resistance
region around peak, thermal/diffusion current above valley voltage. Own
`limitStep` with `|Δv| ≤ 1 V` (5 V panic). Safety: falls back to small
conductance on non-finite geq/i.

**VaractorElm C(V):** PN diode in parallel with trapezoidal-companion
capacitor. `C(V) = C0` (forward) or `C0 / (1 − V/fwdrop)^0.5` (reverse).
Allocates 1 internal node + 1 voltage source for Thevenin capacitor
representation.

**Thyristor latching (SCR / Triac / Diac):** Variable resistor
(`aresistance`) between internal node and cathode, switched between
a low on-value (0.01–500 Ω) and 1e6–1e8 Ω off. SCR: inequality
`−icmult·ic + ia·iamult > 1` recomputed in `doStep` (stateless).
Triac: explicit `boolean state`, persisted in dump; updated in
`startIteration` via gate/holding current thresholds. Diac: voltage-
triggered state, NOT persisted (issue — see plan backlog).

**Composite semis (Unijunction, Optocoupler):** Extend `CompositeElm`;
build themselves from a hard-coded sub-netlist string via
`loadComposite`. UJT adds CCVS/VCCS feedback loop + capacitor (and
forces `adjustTimeStep = true`). Optocoupler installs a piecewise
5th-order polynomial in a CCCS (Clare CLA03 app-note fit) to link LED
current to phototransistor base current; `getConnection` isolates LED
side from transistor side.

### 3.2. Edge Cases  {#C_EDS_03_02}

- `DiodeElm.stepFinished` clamps `|current| ≤ 1e12` and flags
  non-convergence on Inf/NaN.
- SCR coerces non-finite / non-positive `triggerI`/`holdingI` to 1e-12.
- DiacElm zeroes current and flags non-convergence on degenerate R.
- TunnelDiode falls back to small conductance on non-finite geq/i.
- LEDArrayElm silently commented-out `getConnection` to avoid "strange
  behavior with unconnected pins".

## 4. Integration Points  {#C_EDS_04}

### 4.1. Dependencies  {#C_EDS_04_01}

- [C_ELB](./element-base.concept.md) — `CircuitElm`, `CompositeElm`,
  `ChipElm` (for LEDArrayElm's pin layout).
- [C_SHM](./shared-models.concept.md) — `DiodeModel` catalog + `Diode`
  solver helper.
- Simulator stamping primitives: `stampNonLinear`, `stampResistor`,
  `stampConductance`, `stampCurrentSource`, `stampVoltageSource`,
  `updateVoltageSource`.

### 4.2. API Surface  {#C_EDS_04_02}

Dump-types: `'d'` (100) Diode, `'z'` (122) Zener, plus numeric `162`
LED, `175` TunnelDiode, `176` Varactor, `177` SCR, `203` Diac, `206`
Triac, `405` LEDArray, `407` Optocoupler, `417` Unijunction.
Every element implements `nonLinear()`, `stamp()`, `doStep()`,
`draw()`, JSON serialization.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
