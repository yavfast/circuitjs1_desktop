# Magnetics & Transmission Elements  {#C_EMG}

> **Code:** C_EMG
> **Status:** draft
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** OnboardDocGen
>
> **Depends on:** [C_ELB](./element-base.concept.md), [C_UTL](./util-locale-log.concept.md), [C_GEO](./geometry.concept.md), [C_RND](./rendering-primitives.concept.md), [C_MDS](./math-dsp.concept.md) (CircuitMath.invertMatrix), [C_EPS](./elements-passives.concept.md) (`Inductor.FLAG_BACK_EULER`)
> **Used by:** circuit element factory, simulator, renderer
> **Spike:** —
> **Specification:** [SP_EMG](./elements-magnetics-transmission.sp.md)
> **Plan:** [elements-magnetics-transmission.plan.md](./elements-magnetics-transmission.plan.md)
>
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-graphic-and-magnetics.md](../.dev_flow/onboard/analysis/domain-core__cat-graphic-and-magnetics.md)
> (magnetics cluster only — the analysis recommended splitting into two
> concepts; this is the magnetics/transmission half).
>
> Four multi-port electromagnetic/transmission elements: three coupled-
> inductor transformers stamped via inverted mutual-inductance matrices,
> and one lossless transmission line implemented with Bergeron's method.

## 1. Philosophy  {#C_EMG_01}

### 1.1. Core Principle  {#C_EMG_01_01}

This concept documents CircuitJS1's multi-port **propagation** elements.
Two mechanisms coexist:

1. **Mutual-inductance coupling via matrix inversion** — transformer
   windings are modeled as coupled inductors. `stamp()` builds the N×N
   inductance matrix, inverts it (hand-coded for 2/3 windings,
   `CircuitMath.invertMatrix` for N), scales by `ts = Δt/2` (trap) or
   `Δt` (back-Euler), and stamps the result as conductances + voltage-
   controlled current sources — a Norton companion in MNA space.
2. **Bergeron transmission line** — `TransLineElm` uses two ring
   buffers of past voltages plus matched-impedance Thévenin sources at
   each end. Each port presents `Z_0`; the other end's delayed wave
   drives a voltage source updated in `doStep()`.

### 1.2. Design Constraints  {#C_EMG_01_02}

- Transformers do **not** use the `Inductor` helper — they inline their
  own companion-model inversion. Only `Inductor.FLAG_BACK_EULER` is
  shared as a flag bit.
- Each winding allocates one internal node so the series winding-
  resistance R and the pure inductor sit in separate rows; when R=0 a
  `1e8` conductance stub still references the node.
- Coupling coefficient must be strictly `0 < k < 1`; `k==1` gives a
  singular matrix. All three transformers clamp, but runtime defensive
  clamp is absent (latent issue).
- `TransLineElm` cap: `MAX_DELAY_STEPS = 100_000`; over-clamp sets
  `simulator.converged=false`.

## 2. Domain Model  {#C_EMG_02}

### 2.1. Key Entities  {#C_EMG_02_01}

- **`TransformerElm`** — 2-winding; 4 ext posts; 2 internal nodes;
  hand-coded 2×2 inverse → `a1..a4`.
- **`TappedTransformerElm`** — 3-coil (primary + 2 tapped halves); 5
  ext posts; 3 internal; 3×3 inverse stored in `a[9]` with an
  "effective secondary coupling" reduction.
- **`CustomTransformerElm`** — N-coil; description DSL
  (`"1,1:1+1"`-like) parsed to winding spec; `coilCount` internal
  nodes; uses `CircuitMath.invertMatrix`.
- **`TransLineElm`** — lossless 2-conductor delay line; 4 ext posts, 2
  internal nodes, **2 voltage sources**; ring buffers size
  `lenSteps = delay / maxTimeStep`.

### 2.2. Data Flows  {#C_EMG_02_02}

Transformer per-step (all three):

    stamp (once):
        L = self-inductance matrix with signed mutual-coupling entries
        L_inv = invert(L); multiply by ts
        stampConductance(diagonal); stampVCCurrentSource(off-diagonal)
        stampResistor(windingR) on each coil
    startIteration:
        Vd_k = V(int_k) - V(end_k)
        curSourceValue_k = current_k [+ Σ a[k,j]·Vd_j   if trap]
    doStep:
        stampCurrentSource(int_k, end_k, curSourceValue_k) for each coil
    calculateCurrent:
        current_k = curSourceValue_k + Σ a[k,j]·Vd_j

TransLine per-step (Bergeron):

    stamp (once):
        2× stampVoltageSource(int_k, post_k, vs_k)
        2× stampResistor(post_k+2, int_k, imped)
    startIteration:
        voltageL[ptr] = (v_in+ - v_in-) + (v_in+ - v_int0)
        voltageR[ptr] = (v_out+ - v_out-) + (v_out+ - v_int1)
    doStep:
        updateVoltageSource(vs1, -voltageR[(ptr+1) % lenSteps])
        updateVoltageSource(vs2, -voltageL[(ptr+1) % lenSteps])
    stepFinished:
        ptr = (ptr+1) % lenSteps    # once per timeStepCount

## 3. Mechanisms  {#C_EMG_03}

### 3.1. Core Algorithm  {#C_EMG_03_01}

**TransformerElm — 2×2 hand-coded inverse:**

    L1 = inductance; L2 = inductance · ratio²
    M  = k · √(L1·L2)
    deti = 1 / (L1·L2 - M²)
    ts = trap ? Δt/2 : Δt
    a1 =  L2·deti·ts ; a2 = a3 = -M·deti·ts ; a4 =  L1·deti·ts

**TappedTransformerElm — 3×3 with secondary-half coupling reduction:**
each half of the secondary carries near-identity coupling (same flux);
inverse entries follow analytic formulas in `a[9]`.

**CustomTransformerElm — N×N general case:**

    xformMatrix[i][i] = windings[i].inductance
    xformMatrix[i][j] = xformMatrix[j][i] = k · √(L_i · L_j) · p_i · p_j
    CircuitMath.invertMatrix(xformMatrix, coilCount)

Description DSL: `,` separates coils, `+` joins adjacent coils (tap),
`:` separates primary from secondary, negative turns = reversed polarity.

**TransLineElm — Bergeron:** each end Thévenin source value equals
`-opposite_ring_buffer[oldest]`. `nextPtr` at `(ptr+1) % lenSteps`
naturally indexes the oldest slot thanks to ring wrap.

### 3.2. Edge Cases  {#C_EMG_03_02}

- `k == 1` → `det == 0` → `Infinity` propagates; only edit-time clamp,
  no runtime guard.
- Winding R = 0 → `stampConductance(..., 1e8)` stub keeps internal
  node referenced.
- `TransLineElm.reset()` marks `converged=false` on buffer clamp — even
  when triggered by file load (cosmetic nuisance).
- `TransLineElm` ground-return drift: `|V_gnd| > 1e-5` at either end
  nudges `converged=false`.
- CustomTransformerElm JSON state splits between `getJsonProperties`
  (description + taps) and `getJsonState` (per-coil currents); import
  interleave is awkward.

## 4. Integration Points  {#C_EMG_04}

### 4.1. Dependencies  {#C_EMG_04_01}

- [C_ELB](./element-base.concept.md) — `CircuitElm`.
- [C_EPS](./elements-passives.concept.md) — `Inductor.FLAG_BACK_EULER`
  constant shared as flag bit.
- [C_MDS](./math-dsp.concept.md) — `CircuitMath.invertMatrix` (Custom
  only).
- `CircuitSimulator` — stampResistor, stampConductance,
  stampVCCurrentSource, stampCurrentSource, stampRightSide,
  stampVoltageSource, updateVoltageSource, timeStep, maxTimeStep,
  timeStepCount, converged.
- `CustomLogicModel.escape/unescape` — Custom description serialization.
- `io.json.UnitParser.parse` — "mH"-style properties.

### 4.2. API Surface  {#C_EMG_04_02}

- Transformers: `setEditValue` for `inductance`, `ratio` (stored as
  `1/x`), `couplingCoef`, `primary/secondaryResistance`.
- CustomTransformerElm: `description` string + `windingResistance` per
  coil; `parseDescription` validates.
- TransLineElm: `delay`, `imped`; `setEditValue` triggers `reset()`
  which reallocates buffers.
- Shortcut `T` (TransformerElm); others via menu.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
