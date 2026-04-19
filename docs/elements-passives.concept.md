# Passive Elements  {#C_EPS}

> **Code:** C_EPS
> **Status:** draft
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** OnboardDocGen
>
> **Depends on:** [C_ELB](./element-base.concept.md) (element-base contract), [C_GEO](./geometry.concept.md), [C_RND](./rendering-primitives.concept.md), [C_UTL](./util-locale-log.concept.md)
> **Used by:** element factory, editor, scopes
> **Spike:** —
> **Specification:** [SP_EPS](./elements-passives.sp.md)
> **Plan:** [elements-passives.plan.md](./elements-passives.plan.md)
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-passives.md](../.dev_flow/onboard/analysis/domain-core__cat-passives.md)
>
> Two-terminal (and a few 1-/3-terminal) passive components — the simplest
> reference implementations of the `CircuitElm` contract. Covers pure-R
> topology, companion-model reactive elements, non-linear state elements,
> environment-driven resistors, and topology-only nodes.

## 1. Philosophy  {#C_EPS_01}

### 1.1. Core Principle  {#C_EPS_01_01}

Passive elements are the canonical demonstration of the MNA stamping
contract in `CircuitElm`. They span the full range from trivial single-line
stamps (ResistorElm) through companion-model reactives (CapacitorElm,
InductorElm) to Newton-iterated non-linearities (MemristorElm, SparkGapElm,
FuseElm, LampElm). Treat this category as the canonical reference when
introducing new elements.

### 1.2. Design Constraints  {#C_EPS_01_02}

- Every concrete class extends `CircuitElm` directly (PolarCapacitorElm
  extends CapacitorElm) — no common passive subclass exists.
- Linear passives use the default `stampResistor` path only; no voltage
  sources are claimed unless a series resistance introduces an internal
  node.
- Non-linear passives call `stampNonLinear` in `stamp()` and restamp
  their resistance in `doStep()` each Newton iteration.
- Topology-only elements (WireElm, GroundElm, LabeledNodeElm) set
  `isWireEquivalent=true` / `isRemovableWire=true` so the simulator
  collapses their posts during connectivity analysis.

## 2. Domain Model  {#C_EPS_02}

### 2.1. Key Entities  {#C_EPS_02_01}

Per-element name list (full catalog in SP_EPS):

- Linear 2-terminal: `ResistorElm`, `WireElm`
- Topology-only: `WireElm`, `GroundElm`, `LabeledNodeElm`
- Reactive (companion-model): `CapacitorElm`, `PolarCapacitorElm`,
  `InductorElm`
- 3-terminal linear: `PotElm`
- Non-linear state: `MemristorElm`, `SparkGapElm`, `FuseElm`, `LampElm`
- Environment-driven: `ThermistorNTCElm`, `LDRElm`

Total: 14 concrete elements in
`src/main/java/com/lushprojects/circuitjs1/client/element/`.

### 2.2. Data Flows  {#C_EPS_02_02}

Lifecycle for linear passive (ResistorElm):

    construct -> stamp(stampResistor) -> doStep (no-op) -> calculateCurrent

Lifecycle for reactive (CapacitorElm):

    construct -> stamp(internal companion R) -> startIteration(update I-src)
    -> doStep(stamp current source) -> stepFinished(capture voltDiff)

Lifecycle for non-linear (MemristorElm/SparkGapElm/FuseElm/LampElm):

    construct -> stamp(stampNonLinear) -> startIteration(update state->R)
    -> doStep(stampResistor with new R) -> stepFinished

## 3. Mechanisms  {#C_EPS_03}

### 3.1. Core Algorithms  {#C_EPS_03_01}

**Companion model (CapacitorElm, InductorElm):** Trapezoidal or
backward-Euler integrator expressed as (resistor in parallel with
current source). `compResistance = Δt / (2C)` (trapz) or `Δt / C`
(backward-Euler); `curSourceValue` updated in `startIteration` to carry
history. InductorElm delegates all math to the `Inductor` helper
(`client/Inductor.java`); the trapz resistor value is `2L/Δt`.

**Newton stamping for non-linear R:** `stamp()` marks nodes non-linear
but does not stamp resistance; `doStep()` stamps a fresh
`stampResistor(n0, n1, R_current)` each iteration. State (dopeWidth,
heat, temp, latched bool) is advanced in `startIteration()` from the
previous step's current, then R is recomputed.

**Sliders:** PotElm, ThermistorNTCElm, LDRElm attach a GWT `Scrollbar`
to the vertical panel; `execute()` triggers `needsAnalysis()` +
`setPoints()`. `delete()` must un-register the widget.

**Label-based topology:** LabeledNodeElm registers its `text` label in
a static `labelList` map; all elements sharing a label collapse into
one node during connectivity analysis. GroundElm uses a similar single-
cache (`firstGround`) to collapse all ground symbols into one node.

### 3.2. Edge Cases  {#C_EPS_03_02}

- DC analysis: CapacitorElm replaces itself with a 1e8 Ω resistor when
  `circuitInfo.dcAnalysisFlag` is set.
- Non-positive R/L/C are clamped (R=1e-9, C=1e-12) silently to prevent
  matrix singularity.
- PolarCapacitorElm clamps voltDiff to `-maxNegativeVoltage` on reverse
  breach and marks simulator non-converged instead of aborting.
- SparkGap/Memristor force `converged=false` on degenerate (non-finite
  or zero) resistance to drive another Newton pass.
- WireElm/GroundElm/LabeledNodeElm have empty `stamp()` — simulator
  handles them via wire-closure.

## 4. Integration Points  {#C_EPS_04}

### 4.1. Dependencies  {#C_EPS_04_01}

- [C_ELB](./element-base.concept.md) — `CircuitElm` base, `ElmGeometry`,
  `BaseCircuitElm` draw helpers, `Inductor` physics helper.
- [C_RND](./rendering-primitives.concept.md) — Graphics/Polygon/Color.
- [C_UTL](./util-locale-log.concept.md) — unit formatting.
- `io.json.UnitParser`, `CustomLogicModel.escape/unescape` for
  serialization round-trip.
- GWT widgets (`Scrollbar`, `Label`) — slider-owning elements only.

### 4.2. API Surface  {#C_EPS_04_02}

Dump-type dispatch covers tokens `'r' 'c' 'l' 'm' 'w' 'g'` (single-char
legacy) plus numeric codes `174 181 187 207 209 350 374 404`. Every
element round-trips via `dump()` / ctor-from-StringTokenizer and via
`getJsonTypeName()` + `getJsonProperties()` / `applyJsonProperties()`.
See SP_EPS for the full catalog.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
