# Passive Elements — Specification  {#SP_EPS}

> **Code:** SP_EPS
> **Status:** draft
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_EPS](./elements-passives.concept.md)
> **Depends on specs:** [SP_ELB](./element-base.sp.md), [SP_GEO](./geometry.sp.md), [SP_RND](./rendering-primitives.sp.md), [SP_UTL](./util-locale-log.sp.md)
> **Used by specs:** element factory, editor
> **Plan:** [elements-passives.plan.md](./elements-passives.plan.md)
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-passives.md](../.dev_flow/onboard/analysis/domain-core__cat-passives.md)
>
> Catalog and contracts for the 14 passive elements.

## 01. Data Structures  {#SP_EPS_01}

### 01_01. Per-element catalog  {#SP_EPS_01_01}

| Element | Extends | Posts | V-src | Int.nodes | Linear? | State vars | Dump-type | Parameters (units) | File:lines |
|---|---|---|---|---|---|---|---|---|---|
| ResistorElm | CircuitElm | 2 | 0 | 0 | Y | — | `'r'` (114) | `resistance` (Ω) | ResistorElm.java:31-185 |
| CapacitorElm | CircuitElm | 2 | 0 | 0 or 1 | Y (companion) | voltDiff, curSourceValue, compResistance | `'c'` (99) | capacitance (F), initialVoltage (V), seriesResistance (Ω); flags FLAG_BACK_EULER=2, FLAG_RESISTANCE=4 | CapacitorElm.java:33-396 |
| PolarCapacitorElm | CapacitorElm | 2 | 0 | inherits | Y (+ clamp) | inherits + clamp voltDiff | 209 | inherits + maxNegativeVoltage (V) | PolarCapacitorElm.java:10-114 |
| InductorElm | CircuitElm | 2 | 0 | 0 | Y (companion via Inductor helper) | ind.current, ind.curSourceValue, ind.compResistance | `'l'` (108) | inductance (H), initialCurrent (A); flag Inductor.FLAG_BACK_EULER=2 | InductorElm.java:29-216 |
| PotElm | CircuitElm | 3 | 0 | 0 | Y | position, resistance1/2, current1..3, curcount1..3 | 174 | maxResistance (Ω), position [0..1], sliderText; flags FLAG_SHOW_VALUES=1, FLAG_FLIP=2, FLAG_FLIP_OFFSET=4 | PotElm.java:36-456 |
| MemristorElm | CircuitElm | 2 | 0 | 0 | N (iterative R) | dopeWidth, resistance | `'m'` (109) | r_on (Ω), r_off (Ω), dopeWidth (m), totalWidth (m), mobility (m²/(s·V)) | MemristorElm.java:32-233 |
| SparkGapElm | CircuitElm | 2 | 0 | 0 | N (two-state hysteresis) | state (bool), resistance | 187 | onresistance, offresistance (Ω), breakdown (V), holdcurrent (A) | SparkGapElm.java:31-205 |
| FuseElm | CircuitElm | 2 | 0 | 0 | N (switches on blow) | heat (I²t), blown (bool) | 404 | resistance (Ω), i2t (A²·s), heat, blown; flag FLAG_IEC_SYMBOL=1; const blownResistance=1e9 | FuseElm.java:31-245 |
| WireElm | CircuitElm | 2 | 0 | 0 | Y (trivial, no stamp) | — | `'w'` (119) | none (flags FLAG_SHOWCURRENT=1, FLAG_SHOWVOLTAGE=2) | WireElm.java:29-178 |
| LabeledNodeElm | CircuitElm | 1 | 0 | 0 | Y (topology only) | text, static labelList registry | 207 | text (String); flags FLAG_ESCAPE=4, FLAG_INTERNAL=1 | LabeledNodeElm.java:33-229 |
| GroundElm | CircuitElm | 1 | 0 (or 1 legacy FLAG_OLD_STYLE) | 0 | Y | symbolType (enum 0..3) | `'g'` (103) | symbolType (int); flag FLAG_OLD_STYLE=1 | GroundElm.java:30-233 |
| ThermistorNTCElm | CircuitElm | 2 | 0 | 0 | Y (R recomputed per stamp) | resistance, temperature, position | 350 | r25 (Ω), r50 (Ω), minTempr/maxTempr (°C), position, sliderText; derived rneg40, b25100 | ThermistorNTCElm.java:29-293 |
| LDRElm | CircuitElm | 2 | 0 | 0 | Y (R recomputed per stamp) | resistance, lux, position | 374 | position, sliderText; defaults minLux=0.1, maxLux=10000 | LDRElm.java:21-260 |
| LampElm | CircuitElm | 2 | 0 | 0 | N (thermal) | temp (K), resistance | 181 | nom_pow (W), nom_v (V), warmTime (s), coolTime (s); const roomTemp=300K, filament_len=24 | LampElm.java:33-308 |

Invariants:
- Every non-trivial passive overrides `stamp()`, `calculateCurrent()`,
  `getInfo()`, `getEditInfo()`, `setEditValue()`, `draw()`, `dump()`,
  `getDumpType()`.
- Reactive / non-linear additionally override `startIteration()`,
  `doStep()`, `nonLinear()`, `reset()`, `stepFinished()`.
- V-src count is 0 except when Capacitor's `seriesResistance>0` introduces
  an internal node (still 0 voltage sources in MNA sense; the internal
  node is stamped via resistors).

## 02. Contracts  {#SP_EPS_02}

### 02_01. Companion-model reactive (CapacitorElm, InductorElm)  {#SP_EPS_02_01}

Purpose: numerically integrate energy-storage differential equations
using trapezoidal or backward-Euler implicit companion models.

- `compResistance`: trapz `Δt/(2C)` / back-Euler `Δt/C` for caps;
  trapz `2L/Δt` / back-Euler `L/Δt` for inductors.
- `stamp()`: `stampResistor(n0, n1, compResistance)` (cap may stamp an
  internal node for seriesResistance case: `capNode2=2`).
- `startIteration()`: `curSourceValue = -V/R - I` (trapz) or `-V/R`
  (back-Euler).
- `doStep()`: `stampCurrentSource(n0, n1, curSourceValue)`.
- `stepFinished()`: capture `voltDiff` and call `calculateCurrent()`.
- DC analysis branch (cap only): stamp `1e8 Ω` instead.

### 02_02. Non-linear iterative R  {#SP_EPS_02_02}

Applies to MemristorElm, SparkGapElm, FuseElm, LampElm.

- `stamp()`: `stampNonLinear(node)` per post; no R stamped here.
- `startIteration()`: advance per-element state variable from previous
  current/voltage; recompute `resistance`.
- `doStep()`: `stampResistor(n0, n1, resistance)`.
- Convergence: force `simulator().converged = false` on non-finite /
  zero-resistance degenerate state.

### 02_03. Topology-only (WireElm, GroundElm, LabeledNodeElm)  {#SP_EPS_02_03}

- `stamp()` is empty.
- `isWireEquivalent() = true` and `isRemovableWire() = true`.
- GroundElm: `hasGroundConnection(n)=true`; static `firstGround` cache
  collapses all ground symbols to one node.
- LabeledNodeElm: static `labelList` HashMap keys by text; first instance
  per label registers its `point1`; `setNode()` records assigned global
  node number back into the entry.

## 03. Validation Rules  {#SP_EPS_03}

- ResistorElm: R ≤ 0 → clamp to 1e-9.
- CapacitorElm: C ≤ 0 → clamp to 1e-12.
- InductorElm: require L > 0 (reject edit otherwise).
- FuseElm/SparkGapElm: edit values must be > 0; silently drop bad edits.
- MemristorElm: **no guard** (accepts any) — see Plan backlog.
- ThermistorNTCElm: enforce r25 > r50 via EditInfo bounds; clamp `exp`
  argument to ±700 to prevent overflow.
- LampElm: clamp `tp ≤ 5390 K` in R(T) formula.
- PolarCapacitorElm: on `voltDiff < -maxNegativeVoltage`, clamp to
  `-maxNegativeVoltage` and mark solver non-converged (educational/
  robustness behaviour, not hard failure).
- CapacitorElm.getInternalNodeCount: early return 0 when
  `circuitDocument == null` (construction-time NPE guard).

## 04. State Transitions  {#SP_EPS_04}

### 04_01. SparkGapElm  {#SP_EPS_04_01}

| From | To | Condition |
|------|----|-----------|
| off  | on | `|Vd| > breakdown` |
| on   | off | `|I| < holdcurrent` |

### 04_02. FuseElm  {#SP_EPS_04_02}

| From | To | Condition |
|------|----|-----------|
| ok | blown | `heat > i2t` |
| ok | ok (decay) | `heat -= heat·Δt/3` (3 s self-dissipation) |
| blown | blown | permanent until `reset()` |

## 05. Verification Criteria  {#SP_EPS_05}

### 05_01. Functional Expectations  {#SP_EPS_05_01}

| Element | Scenario | Expected |
|---------|----------|----------|
| ResistorElm | simple V/R | `I = V/R`; for R≤0 → clamped to 1e-9 |
| CapacitorElm | DC step | initial-transient via companion, then V settled |
| InductorElm | DC step | initial-transient; `reset()` restores `current=initialCurrent` |
| FuseElm | I²t integration | blown when cumulative heat exceeds i2t |
| LampElm | thermal warm/cool | R follows polynomial of T with separate capw/capc constants |

### 05_02. Invariant Checks  {#SP_EPS_05_02}

- All 14 respect `stampResistor` / `stampNonLinear` / `stampCurrentSource`
  contract of SP_ELB.
- `delete()` on slider-owning elements un-registers GWT widgets.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
