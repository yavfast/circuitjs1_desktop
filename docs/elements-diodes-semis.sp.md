# Diode and Semiconductor Elements — Specification  {#SP_EDS}

> **Code:** SP_EDS
> **Status:** draft
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_EDS](./elements-diodes-semis.concept.md)
> **Depends on specs:** [SP_ELB](./element-base.sp.md), [SP_SHM](./shared-models.sp.md), [SP_GEO](./geometry.sp.md)
> **Used by specs:** io-framework, editor
> **Plan:** [elements-diodes-semis.plan.md](./elements-diodes-semis.plan.md)
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-diodes-semis.md](../.dev_flow/onboard/analysis/domain-core__cat-diodes-semis.md)
>
> Catalog and contracts for the 11 diode/semiconductor elements and the
> shared `Diode` Newton-Raphson helper.

## 01. Data Structures  {#SP_EDS_01}

### 01_01. Per-element catalog  {#SP_EDS_01_01}

| Element | Extends | Posts | V-src | Int.nodes | Model-ref | Non-linear? | Dump-type | Parameters | File:lines |
|---|---|---|---|---|---|---|---|---|---|
| DiodeElm | CircuitElm | 2 (anode, cathode) | 0 | 0 or 1 (if seriesResistance>0) | DiodeModel by name | yes | `'d'` (100) | modelName | DiodeElm.java:39-349 |
| ZenerElm | DiodeElm | 2 | 0 | 0/1 | DiodeModel (default-zener) | yes (via super) | `'z'` (122) | modelName, legacy zvoltage | ZenerElm.java:33-133 |
| TunnelDiodeElm | CircuitElm | 2 | 0 | 0 | none (hard-coded constants) | yes | 175 | — (all consts) | TunnelDiodeElm.java:29-217 |
| VaractorElm | DiodeElm | 2 | 1 (companion-cap) | 1 (between diode + cap) | inherited DiodeModel | yes | 176 | baseCapacitance, capvoltdiff | VaractorElm.java:10-199 |
| LEDElm | DiodeElm | 2 | 0 | 0/1 | DiodeModel default-led | yes (via super) | 162 | colorR/G/B, maxBrightnessCurrent, inherited model | LEDElm.java:32-179 |
| LEDArrayElm | ChipElm | sizeX+sizeY | 0 | 0 | DiodeModel default-led (shared) | yes | 405 | sizeX, sizeY (2..16) | LEDArrayElm.java:31-237 |
| SCRElm | CircuitElm | 3 (anode, cathode, gate) | 0 | 1 (inode) | DiodeModel default | yes | 177 | triggerI, holdingI, gresistance, lastvac, lastvag | SCRElm.java:38-404 |
| TriacElm | CircuitElm | 3 (MT2, MT1, gate) | 0 | 1 (mtinode) | DiodeModel default (2 Diodes) | yes | 206 | triggerI, holdingI, cresistance, state | TriacElm.java:39-363 |
| DiacElm | CircuitElm | 2 | 0 | 2 | DiodeModel default (2 Diodes) | yes | 203 | onresistance, offresistance, breakdown, holdcurrent, state | DiacElm.java:33-231 |
| UnijunctionElm | CompositeElm | 3 (E, B1, B2) | inherited | inherited | via inner DiodeElm (x2n2646-emitter) | yes | 417 | hard-coded ujtModelDump | UnijunctionElm.java:29-189 |
| OptocouplerElm | CompositeElm | 4 (LED-A, LED-K, C, E) | inherited | 3 internal sub-elements | inner DiodeElm + TransistorElm (β=700); CCCS polynomial | yes | 407 | (none user-editable) | OptocouplerElm.java:12-230 |

### 01_02. `Diode` helper contract  {#SP_EDS_01_02}

`client/Diode.java` — numerical strategy object, not a CircuitElm.

- `Diode(CircuitSimulator s)` — ctor.
- `setup(DiodeModel m)` — reads `saturationCurrent` (IS),
  `breakdownVoltage` (Vz), `vscale = N·Vt`, `vdcoef = 1/vscale`;
  computes `vcrit`, `vzcrit`, `zoffset` (-5 mA at zvoltage).
- `setupForDefaultModel()` — pulls `DiodeModel.getDefaultModel()`.
- `stamp(n0, n1)` — records nodes and calls `stampNonLinear` on each;
  no conductance stamped here.
- `doStep(voltdiff)` — Newton step (see Concept mechanisms).
- `calculateCurrent(voltdiff)` — Shockley eval without matrix
  side-effects.
- `limitStep(vnew, vold)` — caps Δv.

Reuse sites:

| Element | # Diode helpers | Stamp layout |
|---|---:|---|
| DiodeElm | 1 | (0, internal) + Rs to 1, OR (0, 1) if no Rs |
| LEDArrayElm | sizeX·sizeY | row pin → col pin per LED |
| SCRElm | 1 | (inode, cathode) |
| TriacElm | 2 | (mt2, mtinode) and (mtinode, mt2) |
| DiacElm | 2 | (2, 1) and (1, 3) |
| TunnelDiodeElm | 0 | rolls its own exponential |

## 02. Contracts  {#SP_EDS_02}

### 02_01. DiodeElm stamping  {#SP_EDS_02_01}

- `setup()` resolves `modelName → model` via
  `DiodeModel.getModelWithNameOrCopy`.
- Series resistance handled in the element, not in `Diode.java`:
  when `model.seriesResistance > 0`, `getInternalNodeCount()=1` and
  stamp becomes `Diode.stamp(node0, internalNode) +
  stampResistor(internalNode, node1, Rs)`; else direct
  `Diode.stamp(node0, node1)`.
- `stepFinished` clamps `|current| ≤ 1e12` and flags non-convergence
  on Inf/NaN.
- Static `lastModelName` remembers user's last pick (overridden by
  `lastZenerModelName`, `lastLEDModelName`).

### 02_02. Thyristor latching  {#SP_EDS_02_02}

SCRElm (stateless per-step):

    aresistance = (-icmult*ic + ia*iamult > 1) ? 0.0105 : 1e6
    icmult = 1/triggerI; iamult = 1/holdingI - icmult

TriacElm (persistent state, dumped to disk):

    if |i2| < holdingI: state = false
    if |ig| > triggerI: state = true

DiacElm (persistent state, NOT dumped — bug-like):

    if |current| < holdcurrent: state = false
    if |V(0) − V(1)| > breakdown: state = true

### 02_03. Composite semis (Unijunction, Optocoupler)  {#SP_EDS_02_03}

UJT netlist (nodes `{1,2,3} → {E, B2, B1}`):

    DiodeElm 1 4       (model x2n2646-emitter)
    VoltageElm 4 5
    CCVSElm 4 5 6 0
    ResistorElm 0 6
    VCCSElm 5 7 5 7 6 7 5
    CapacitorElm 5 7
    ResistorElm 7 2
    ResistorElm 3 5

Optocoupler netlist (nodes `{6,2,4,5} → {LED-A, LED-K, C, E}`):

    DiodeElm 6 1
    CCCSElm 1 2 3 4       (5th-order polynomial per Clare CLA03)
    NTransistorElm 3 4 5  (β=700)

## 03. Validation Rules  {#SP_EDS_03}

- `DiodeElm.stepFinished` — clamp `|current| ≤ 1e12`, non-conv on NaN.
- `SCRElm.doStep` — coerce non-finite / non-positive triggerI /
  holdingI to 1e-12; flag non-conv.
- `SCRElm.calculateCurrent` — same for `gresistance`/`aresistance`.
- `DiacElm.calculateCurrent` — zero current + non-conv on degenerate R.
- `TunnelDiodeElm.doStep` — small-conductance fallback on non-finite
  geq/i.
- `DiodeElm.setup` — `getModelWithNameOrCopy` never throws on missing
  model; copies current as stand-in.
- `SCRElm.setPoints` / `TriacElm.setPoints` — collapse to zero length
  if lead too short to fit gate stub; `creationFailed()` deletes element.
- `LEDArrayElm.setChipEditValue` — reject grid dims outside [2, 16].
- `VaractorElm.setCurrent(int x, double)` — ignores `x`; always routes
  to `capCurrent`. Asserts exactly one voltage source.

## 04. State Transitions  {#SP_EDS_04}

Triac state machine (see 02_02). Diac similar with voltage trigger.

## 05. Verification Criteria  {#SP_EDS_05}

### 05_01. Functional Expectations  {#SP_EDS_05_01}

| Element | Scenario | Expected |
|---------|----------|----------|
| DiodeElm | forward bias | Shockley `I = IS·(e^(v/vscale) − 1)` |
| ZenerElm | reverse bias > Vz | secondary exponential engages |
| TunnelDiodeElm | v in [0.1 V, 0.37 V] | negative differential resistance |
| VaractorElm | reverse-biased | C = C0/sqrt(1 - V/fwdrop) |
| SCRElm | gate pulse | latches, stays on while Ia > holdingI |
| DiacElm | V > breakdown | fires; drops out at I < holdcurrent |
| Optocoupler | 10 mA LED current | phototransistor delivers β·i_base per polynomial |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
