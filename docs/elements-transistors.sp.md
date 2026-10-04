# Transistor and Tube Elements — Specification  {#SP_ETR}

> **Code:** SP_ETR
> **Status:** draft
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_ETR](./elements-transistors.concept.md)
> **Depends on specs:** [SP_ELB](./element-base.sp.md), [SP_SHM](./shared-models.sp.md)
> **Used by specs:** io-framework, editor
> **Plan:** [elements-transistors.plan.md](./elements-transistors.plan.md)
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-transistors.md](../.dev_flow/onboard/analysis/domain-core__cat-transistors.md)
>
> Catalog and contracts for the 13 transistor and tube elements.

## 01. Data Structures  {#SP_ETR_01}

### 01_01. Per-element catalog  {#SP_ETR_01_01}

| Element | Extends | Model | Polarity | Posts | V-sources | Non-linear | Dump-type | File:lines |
|---|---|---|---|---|---|---|---|---|
| TransistorElm (base) | CircuitElm | TransistorModel + per-elm `beta` | `pnp` flag: +1 NPN / -1 PNP | 3 (B,C,E) | 0 | yes | `'t'` (116) | TransistorElm.java:1-807 |
| NTransistorElm | TransistorElm | inherited | +1 (super false) | 3 | 0 | yes | `'t'` | NTransistorElm.java:1-37 |
| PTransistorElm | TransistorElm | inherited | -1 (super true) | 3 | 0 | yes | `'t'` | PTransistorElm.java:1-37 |
| MosfetElm (base) | CircuitElm | per-instance `vt`, `beta` + 2 Diode helpers (body diodes) | `pnp` + FLAG_PNP=1 | 3 or 4 with FLAG_BODY_TERMINAL | 0 | yes | `'f'` (102) | MosfetElm.java:1-728 |
| NMosfetElm | MosfetElm | inherited | +1 | 3/4 | 0 | yes | `'f'` | NMosfetElm.java:1-36 |
| PMosfetElm | MosfetElm | inherited | -1 | 3/4 | 0 | yes | `'f'` | PMosfetElm.java:1-36 |
| JfetElm | MosfetElm | per-instance `vt`, `beta` + 2 Diode helpers (gate junctions) | inherited `pnp` | 3 (showBulk=false) | 0 | yes (inherited) | `'j'` | JfetElm.java:1-301 |
| NJfetElm | JfetElm | inherited | +1 | 3 | 0 | yes | `'j'` | NJfetElm.java:1-32 |
| PJfetElm | JfetElm | inherited | -1 | 3 | 0 | yes | `'j'` | PJfetElm.java:1-13 |
| DarlingtonElm | CompositeElm | two NTransistorElm children | stored on elm + mutated into child pnp | 3 external | 0 | yes (any child) | 400 | DarlingtonElm.java:1-174 |
| NDarlingtonElm | DarlingtonElm | inherited | +1 | 3 | 0 | yes | 400 | NDarlingtonElm.java:1-16 |
| PDarlingtonElm | DarlingtonElm | inherited | -1 | 3 | 0 | yes | 400 | PDarlingtonElm.java:1-14 |
| TriodeElm | CircuitElm | per-instance `mu`, `kg1` only | N/A (unidirectional) | 3 (plate, grid, cathode) | 0 | yes | 173 | TriodeElm.java:1-325 |

### 01_02. BJT stamp block  {#SP_ETR_01_02}

Dense 3×3 conductance + 3-entry RHS per iteration:

    Base row:      gpi + gmu
    Collector row: gmu + go
    Emitter row:   gpi + gm + go
    RHS: stampRightSide with ceqbe, ceqbc (linearised equiv currents)

### 01_03. MOSFET stamp block  {#SP_ETR_01_03}

2×3 block into drain / source rows only (gate is zero-current in
square-law):

    rs = -pnp*ids0 + Gds*vds + gm*vgs
    stamp Gds, gm into (drain, source, gate) matrix entries
    stamp rs into RHS

Body-diode branch (optional): two `Diode.doStep` calls.

### 01_04. Triode stamp block  {#SP_ETR_01_04}

2×3 block (plate row ≅ drain, cathode row ≅ source). Grid row NOT
stamped in the plate block — grid current goes through an explicit
6 kΩ / 1e8 Ω resistor.

## 02. Contracts  {#SP_ETR_02}

### 02_01. Polarity flag  {#SP_ETR_02_01}

`int pnp ∈ {+1, -1}`. N/P subclasses exist only to:

- Pass `true/false` for `pnpflag` to super ctor.
- Override `getDumpClass()` to return the base class so text-format
  dump type stays unique per base.
- Override `getShortcut()` on NPN, NMOS, PMOS (not on JFET / Darlington
  which are menu-only).

Consequence: a `NTransistorElm` dumped and reimported may come back as
`TransistorElm` with positive pnp, depending on factory path. The
subclass is a "new blank element" convenience.

### 02_02. Darlington composition  {#SP_ETR_02_02}

- Hard-coded netlist: `"NTransistorElm 1 2 4\rNTransistorElm 4 2 3"`.
- `modelExternalNodes = {1, 2, 3}` (B, C, E).
- Child `pnp` mutated in place by the Darlington ctor after super
  construction.
- Simulation fully delegated to children via CompositeElm default
  implementations.
- Gummel-Poon runs twice per Newton iteration; shared internal node
  (child1 E = child2 B) couples them through the matrix.

### 02_03. JFET add-on stamps  {#SP_ETR_02_03}

- Two `Diode` instances (`diodeGS`, `diodeGD`) stamped in `JfetElm.stamp`
  with polarity-aware orientation (anode on gate for n-JFET, cathode on
  gate for p-JFET).
- `JfetElm.doStep` calls `super.doStep` (MOSFET square-law) then steps
  both diodes.
- `getCurrentIntoNode(n)`: `{ post 0 (gate): -(Igs+Igd), post 1: +Igs+ids,
  post 2: -ids+Igd }`; `Igs`/`Igd` are computed in `stepFinished` (since
  2026-10-04; before they stayed 0).
- MOSFET/JFET pin names and reported quantities
  ([SP_AGA_DEC_08](./agent-api.sp.md#SP_AGA_DEC_08)): post 1 is the source of
  an n-channel and the drain of a p-channel device; `getCurrent()` is the
  current into the drain terminal (positive into the drain for both
  polarities), `getVoltageDiff()` drain minus source; the scope keeps `ids`
  and post 2 minus post 1.
- Defaults: `vt = -4 V`, `β = 0.00125` (Hayes/Horowitz p.155).

## 03. Validation Rules  {#SP_ETR_03}

- **`TransistorElm.limitStep`** — SPICE-style Newton voltage limit on
  `vbe` and `vbc`; sets `converged=false` when limiting fires.
  `maxDelta=2·vt` (20·vt in panic).
- **`TransistorElm` NaN/Inf guards** — on `qb`, `dqbdve`, `dqbdvc`,
  `gpi`, `gmu`, `go`, `gm`, `ceqbe`, `ceqbc`, `ib`, `ic`, `ie`.
- **`TransistorElm.stepFinished`** — `|I| ≤ 1e12`; over-limit →
  `converged=false`.
- **`TransistorElm.badIters`** — threshold 200 (normal) / 1M (panic);
  gates gmin escalation.
- **`MosfetElm.nonConvergence`** — adaptive: diff<10 mV always OK;
  looser than 0.1 % after subIterations>10 and even looser >100.
- **`MosfetElm`** — `maxDelta=0.5` V (5 V panic); cutoff/saturation
  `Gds = max(1e-8, getExtraConvergenceGmin())`.
- **`TriodeElm`** — fixed ±0.5 V per-iteration limit on grid/cathode;
  convergence tol 0.01 V.
- **Breakdown limits:** none in category — no zener-style reverse-
  breakdown on BJT, no gate-oxide breakdown on MOSFET.

## 04. State Transitions  {#SP_ETR_04}

No explicit state machines — all sequential work is Newton iteration
state (lastvbc/lastvbe, lastv0/1/2, etc.).

## 05. Verification Criteria  {#SP_ETR_05}

### 05_01. Functional Expectations  {#SP_ETR_05_01}

| Element | Scenario | Expected |
|---------|----------|----------|
| NTransistorElm | NPN common-emitter, small-signal | ic ≈ β·ib, vce > 0 |
| NMosfetElm | Vgs > Vt, Vds small | linear region, ids ≈ β·(Vgs-Vt)·Vds |
| NMosfetElm | saturation | ids ≈ ½β·(Vgs-Vt)² |
| NJfetElm | reverse-biased gate | gate diodes off, square-law channel |
| DarlingtonElm | gain = β₁ · β₂ | current amplification |
| TriodeElm | plate load line | ids = ival^1.5/kg1; grid conducts via 6kΩ when Vgk>0 |

### 05_02. Invariant Checks  {#SP_ETR_05_02}

- All elements report 0 voltage sources.
- BJT / MOSFET symmetry under N↔P via `pnp` flag.
- Darlington sum-over-children post/vs counts yield 3 external / 0 vs.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
