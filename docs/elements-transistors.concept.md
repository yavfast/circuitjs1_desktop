# Transistor and Tube Elements  {#C_ETR}

> **Code:** C_ETR
> **Status:** draft
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** OnboardDocGen
>
> **Depends on:** [C_ELB](./element-base.concept.md), [C_SHM](./shared-models.concept.md), [C_GEO](./geometry.concept.md), [C_RND](./rendering-primitives.concept.md)
> **Used by:** element factory, editor, scope
> **Spike:** —
> **Specification:** [SP_ETR](./elements-transistors.sp.md)
> **Plan:** [elements-transistors.plan.md](./elements-transistors.plan.md)
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-transistors.md](../.dev_flow/onboard/analysis/domain-core__cat-transistors.md)
>
> Every 3-terminal (+ optional body) active non-linear device: BJTs
> (Gummel-Poon), MOSFETs (SPICE Level-1 square law), JFETs (square-law
> channel + gate PN junctions), Darlington pair (CompositeElm), and the
> vacuum triode (Koren 3/2-power plate law).

## 1. Philosophy  {#C_ETR_01}

### 1.1. Core Principle  {#C_ETR_01_01}

All category members stamp their own linearised conductance block + RHS
companion each Newton iteration. BJTs pull physics from a shared
`TransistorModel` catalog entry; MOSFET/JFET/Triode keep their physics
parameters as plain per-instance fields. Polarity (N vs P) is a scalar
flag (`pnp = +1` / `-1`) multiplied into every equation; concrete N/P
subclasses are thin constructor wrappers.

### 1.2. Design Constraints  {#C_ETR_01_02}

- All elements report 0 voltage sources — they work entirely through
  non-linear conductance stamps + RHS vector.
- 3 posts default (4 for MOSFET with `FLAG_BODY_TERMINAL`).
- `DarlingtonElm extends CompositeElm` (not `TransistorElm`) — composed
  of two `NTransistorElm` children with `pnp` mutated in place.
- `JfetElm extends MosfetElm` — reuses square-law channel; adds two
  `Diode` instances for gate PN junctions; disables body-diode path via
  `showBulk()=false`.

## 2. Domain Model  {#C_ETR_02}

### 2.1. Key Entities  {#C_ETR_02_01}

Per-element name list (full catalog in SP_ETR):

- BJT: `TransistorElm` (base), `NTransistorElm`, `PTransistorElm`
- MOSFET: `MosfetElm` (base), `NMosfetElm`, `PMosfetElm`
- JFET: `JfetElm` (base), `NJfetElm`, `PJfetElm`
- Darlington (composite): `DarlingtonElm`, `NDarlingtonElm`,
  `PDarlingtonElm`
- Tube: `TriodeElm`

Total: 13 concrete elements.

### 2.2. Data Flows  {#C_ETR_02_02}

BJT per Newton iteration:

    doStep ->
      read vbc, vbe (scaled by pnp)
      isConverged check; gmin stepping; limitStep
      compute cbe, cbc (Gummel-Poon exp terms)
      compute qb, dqbdve/dqbdvc (base charge, early+knee)
      NaN/Inf guards on all conductances/currents
      stampMatrix 3x3 block + stampRightSide

MOSFET `calculate()`:

    pick region by (vgs, vds):
      cutoff   -> gmin-only
      triode   -> β·((vgs-vt)·vds - vds²/2)
      saturate -> ½·β·(vgs-vt)²
    source/drain auto-swap by polarity sign
    stamp 2x3 block (drain, source rows); gate row not stamped
    (body diodes stepped via Diode helper if FLAG_BODY_DIODE)

Triode `doStep`:

    compute vgk, vpk; ival = vgk + vpk/mu
    grid-current branch: 6 kΩ when vgk > 0.01 else 1e8 Ω
    plate-current branch: Gds = 1e-8 when ival<0
                          else ids = ival^1.5/kg1
    stamp 2x3 block (plate row like drain, cathode row like source)

## 3. Mechanisms  {#C_ETR_03}

### 3.1. Core Algorithms  {#C_ETR_03_01}

**BJT Gummel-Poon (inlined in `TransistorElm.doStep`):** SPICE
`bjtload.c` equivalent. No separate helper class. Reads IS, NF/NR,
ISE/ISC, NE/NC, VAF/VAR, IKF/IKR from `TransistorModel`. Uses
`limitStep` with `maxDelta = 2·vt` (20·vt in panic). `gmin` starts at
1e-12, grows exponentially past `gminStartIters` (default 100, 20 in
panic). NaN/Inf guards on every intermediate.

**MOSFET SPICE Level-1:** Four-region (cutoff / triode / saturate; last
two only for on-state). Source/drain auto-swap by polarity. Optional
body diodes: two `Diode` helpers, stepped with the channel. `maxDelta
= 0.5 V` (5 V panic) on S / D.

**JFET:** `extends MosfetElm`. Adds two `Diode` instances (gate-source,
gate-drain) stamped in `stamp()` with polarity-aware orientation; steps
them after super `doStep`. `showBulk()=false` suppresses body-diode
path inherited from MOSFET.

**Triode Koren 3/2-power:** `ids = ival^1.5 / kg1` where
`ival = vgk + vpk/mu`. Grid-cathode resistor is 6 kΩ when the grid goes
positive, 1e8 Ω otherwise. `±0.5 V` per-iteration limit on grid/cathode.

**Darlington via CompositeElm:** Hard-coded netlist string
`"NTransistorElm 1 2 4\rNTransistorElm 4 2 3"`, `modelExternalNodes =
{1,2,3}`. Polarity applied by reaching into `compElmList` and mutating
child `pnp` after construction.

### 3.2. Edge Cases  {#C_ETR_03_02}

- NaN/Inf guards on `qb`, `dqbdve/dqbdvc`, `ceqbe/ceqbc`, gpi/gmu/go/gm,
  ib/ic/ie — replaced with safe defaults (0 or 1).
- `stepFinished` clamps `|Ib|`, `|Ic|`, `|Ie| ≤ 1e12`; `badIters`
  counter prevents indefinite gmin escalation (default 200, 1M in
  panic mode).
- MOSFET source/drain auto-swap when `pnp*V[1] > pnp*V[2]` — MOSFETs
  are symmetric in simulation despite real asymmetry.
- Triode disables `canFlipX()` and `canFlipY()` — electron flow
  direction is fixed.

## 4. Integration Points  {#C_ETR_04}

### 4.1. Dependencies  {#C_ETR_04_01}

- [C_ELB](./element-base.concept.md) — `CircuitElm`, `CompositeElm`,
  `ElmGeometry`.
- [C_SHM](./shared-models.concept.md) — `TransistorModel` (BJT only),
  `Diode` helper (MOSFET body diodes, JFET gate diodes).
- Simulator entry points: `stampMatrix`, `stampRightSide`,
  `stampNonLinear`, `stampResistor` (triode grid),
  `getConvergencePanicLevel`, `getExtraConvergenceGmin`,
  `subIterations`, `converged` flag.
- `CircuitMath.isConverged` — BJT.

### 4.2. API Surface  {#C_ETR_04_02}

Dump-types: `'t'` (116) BJT, `'f'` (102) MOSFET, `'j'` JFET, `400`
Darlington, `173` Triode. `EditTransistorModelDialog` edits shared BJT
model; `TransistorModel.updateModel()` → `CircuitSimulator.updateModels()`
→ every `TransistorElm` re-runs `setup()` (SimulationContextAware
propagation).

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
