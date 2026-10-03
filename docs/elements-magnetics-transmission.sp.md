# Magnetics & Transmission Elements — Specification  {#SP_EMG}

> **Code:** SP_EMG
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_EMG](./elements-magnetics-transmission.concept.md)
> **Depends on specs:** [SP_ELB](./element-base.sp.md), [SP_UTL](./util-locale-log.sp.md), [SP_GEO](./geometry.sp.md), [SP_RND](./rendering-primitives.sp.md), [SP_MDS](./math-dsp.sp.md) (`CircuitMath.invertMatrix`), [SP_EPS](./elements-passives.sp.md) (`Inductor.FLAG_BACK_EULER`)
> **Used by specs:** — (will be filled by higher layers)
> **Plan:** [elements-magnetics-transmission.plan.md](./elements-magnetics-transmission.plan.md)
>
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-graphic-and-magnetics.md](../.dev_flow/onboard/analysis/domain-core__cat-graphic-and-magnetics.md)
> (magnetics cluster only)
>
> Dense catalog of four multi-port propagation elements: three coupled-
> inductor transformers and one Bergeron transmission line.

## 01. Element Catalog  {#SP_EMG_01}

> Implements: [C_EMG_02](./elements-magnetics-transmission.concept.md#C_EMG_02)

### 01_01. Element Table  {#SP_EMG_01_01}

| Element | Extends | Posts | V-src | Int.nodes | Linear? | State vars | Dump | Parameters | File:lines |
|---|---|---|---|---|---|---|---|---|---|
| `TransformerElm` | `CircuitElm` | 4 | 0 | 2 (priInt, secInt) | yes | `current[2]`, `curSourceValue[2]`, `a1..a4` | `'T'` 84 | `inductance`, `ratio` (stored `1/x`), `couplingCoef`, `primaryResistance`, `secondaryResistance`, `FLAG_BACK_EULER=2` | TransformerElm.java:31-789 |
| `TappedTransformerElm` | `CircuitElm` | 5 | 0 | 3 (priInt, secInt1, secInt2) | yes | `current[4]` (incl. tap), `curSourceValue[3]`, `a[9]` | 169 | `inductance`, `ratio`, `couplingCoef`, 3× winding `resistance`, `FLAG_BACK_EULER` | TappedTransformerElm.java:30-801 |
| `CustomTransformerElm` | `CircuitElm` | `nodeCount` (desc-driven) | 0 | `coilCount` (one per winding) | yes | `coilCurrent[N]`, `curSourceValue[N]`, `xformMatrix[N][N]` | 406 | `description` (DSL), `inductance`, `couplingCoef`, per-coil `windingResistance`, tap overrides, `FLAG_BACK_EULER` | CustomTransformerElm.java:34-1210 |
| `TransLineElm` | `CircuitElm` | 4 | **2** | 2 | yes (linear delay line) | `voltageL[lenSteps]`, `voltageR[lenSteps]`, `ptr`, `lastStepCount` | 171 | `delay` (s), `imped` (Ω, default 75) | TransLineElm.java:32-409 |

Legend: posts = `getPostCount()`; v-src = `getVoltageSourceCount()`;
int.nodes = `getInternalNodeCount()`.

Windings and JSON pin names (post order; 2026-10-03, measured in the live scenario
`verify_defects`):
- `TransformerElm`: windings are posts 0–2 (primary, L1 = `inductance`) and 1–3
  (secondary, L2 = L1·ratio²); pins `p1`, `s1`, `p2`, `s2`. `p1`/`s1` are the
  in-phase ends (open secondary: V(s1) − V(s2) = k·ratio·(V(p1) − V(p2))).
  `FLAG_REVERSE` (`reverse_polarity`) swaps the drawn positions of posts 1 and 3
  only, so `s1` stays the in-phase end. The JSON 2.0 names `pri1`, `pri2`,
  `sec1`, `sec2` (posts 0..3, which put `pri2` on the secondary) are import
  aliases (`getJsonPinAliases`).
- `TappedTransformerElm`: primary posts 0–1, secondary halves 2–3 and 3–4; pins
  `pri1`, `pri2`, `sec1`, `tap`, `sec2` (unchanged; `pri1`/`sec1` in phase).
- `CustomTransformerElm`: generic `pin1`…`pinN` in node order (the description
  defines the windings).

Shared traits:
- None of the transformers use the `Inductor` helper — they inline the
  companion-model inversion. Only `Inductor.FLAG_BACK_EULER` (= 2) is
  imported as a flag-bit constant.
- Each winding gets one internal node so the series winding-R and the
  pure inductor occupy separate MNA rows. R==0 stamps a `1e8`
  conductance stub to keep the internal node referenced.
- All three transformers are Norton companions (conductances +
  voltage-controlled current sources); `getVoltageSourceCount()==0`.
- `TransLineElm` is Thévenin (two voltage sources driven by past-
  voltage ring buffers).

### 01_02. Post Indexing  {#SP_EMG_01_02}

    TransformerElm           TappedTransformerElm         TransLineElm
    0 ── priInt ── 2         0 ── priInt ── 3             2 ── Rz ── int0 ── [Vs1] ── 0  (gnd_in)
    1 ── secInt ── 3         1 ── secInt1 ── 4 (tap)      3 ── Rz ── int1 ── [Vs2] ── 1  (gnd_out)
                             2 ── secInt2 ── 4

`Vs1 = -voltageR[oldest]`, `Vs2 = -voltageL[oldest]`; `Rz = imped`.

### 01_03. CustomTransformerElm Description DSL  {#SP_EMG_01_03}

Grammar (informal):

    description := coil-list (':' coil-list)?
    coil-list   := coil ('+' coil | ',' coil)*
    coil        := integer_turns        # sign = polarity

- `,` separates independent coils on the same side.
- `+` joins adjacent coils at a shared tap node.
- `:` splits primary list from secondary list; zero or one allowed.
- `parseDescription` rejects: unparseable string, `n == 0` token,
  multiple `:` separators. On reject `applyJsonProperties` falls back
  to the previous valid description.
- `primaryCoils` = index of first secondary coil (after `:`).
- Self-inductance of coil `i`: `turns_i² · baseInductance`.

## 02. Contracts  {#SP_EMG_02}

### 02_01. Stamp contract (transformers)  {#SP_EMG_02_01}

Pseudocode (CustomTransformerElm generalizes, 2-coil and 3-coil hand-code):

    build L[N][N]:
        L[i][i] = winding[i].inductance
        L[i][j] = L[j][i] = couplingCoef · √(L_i · L_j) · polarity_i · polarity_j
    invert L → Ainv                 # 2×2 / 3×3 analytic; N×N via CircuitMath.invertMatrix
    ts = (flags & FLAG_BACK_EULER) ? Δt : Δt/2
    FOR each coil i,j:
        a[i][j] = Ainv[i][j] · ts
        IF i==j: stampConductance(intNode_i, endNode_i, a[i][i])
        ELSE:    stampVCCurrentSource(intNode_i, endNode_i, intNode_j, endNode_j, a[i][j])
    FOR each coil i:
        stampResistor(externalNode_i, intNode_i, windingResistance_i)  # or 1e8 conductance if R==0
        stampRightSide(...)

### 02_02. Step contract (transformers)  {#SP_EMG_02_02}

    startIteration():
        FOR each coil k:
            Vd_k = V(intNode_k) - V(endNode_k)
            IF trap:  curSourceValue_k = current_k + Σ_j a[k][j]·Vd_j
            ELSE:     curSourceValue_k = current_k                    # back-Euler

    doStep():
        FOR each coil k:
            stampCurrentSource(intNode_k, endNode_k, curSourceValue_k)

    calculateCurrent():
        current_k = curSourceValue_k + Σ_j a[k][j]·Vd_j

### 02_03. TransLineElm step contract  {#SP_EMG_02_03}

    stamp():   (once)
        stampVoltageSource(int0, post0, Vs1)
        stampVoltageSource(int1, post1, Vs2)
        stampResistor(post2, int0, imped)
        stampResistor(post3, int1, imped)

    startIteration():
        voltageL[ptr] = (V2 - V0) + (V2 - V_int0)      # 2× forward-wave
        voltageR[ptr] = (V3 - V1) + (V3 - V_int1)      # 2× backward-wave

    doStep():
        nextPtr = (ptr + 1) mod lenSteps               # oldest slot after wrap
        updateVoltageSource(int0, post0, Vs1, -voltageR[nextPtr])
        updateVoltageSource(int1, post1, Vs2, -voltageL[nextPtr])

    stepFinished():
        IF timeStepCount != lastStepCount:
            ptr = (ptr + 1) mod lenSteps
            lastStepCount = timeStepCount

Where `lenSteps = (int)(delay / maxTimeStep)`, clamped to
`MAX_DELAY_STEPS = 100_000`. Over-clamp truncates `delay` and sets
`simulator.converged = false`.

### 02_04. Per-element callouts  {#SP_EMG_02_04}

- **TransformerElm** — 2×2 hand-coded inverse stored as scalars
  `a1, a2, a3, a4`; `a2 == a3 = -M·deti·ts` by symmetry.
- **TappedTransformerElm** — 3×3 inverse after "effective secondary
  coupling" reduction. Two secondary halves are modelled with
  `m2 = couplingCoef · l2` (same flux ≈ self-inductance of each half);
  `det = l1 · l2' - 2·m1²` with `l2' = l2 + m2`. Tap-wire current is
  derived: `current[3] = current[1] - current[2]`.
- **CustomTransformerElm** — uses `CircuitMath.invertMatrix` on the
  `coilCount × coilCount` signed-coupling matrix. JSON state splits:
  `description + taps` go through `getJsonProperties`; `coilCurrent[i]`
  go through `getJsonState`.
- **TransLineElm** — ground-return drift watcher: if
  `|V(post0)| > 1e-5` or `|V(post1)| > 1e-5`, `converged=false`
  (convergence hint, not hard stop). `getConnection(n1,n2) == false`
  always — the 4 posts are always independent.

## 03. Validation Rules  {#SP_EMG_03}

### 03_01. Input Validation  {#SP_EMG_03_01}

| Parameter | Rule | Source |
|---|---|---|
| `inductance` (all transformers) | > 0 | edit setter reject |
| `ratio` | > 0; stored value is N2/N1 (secondary turns per primary turn: L2 = L1·ratio²); the dialog row shows N1/N2 and stores `1/ei.value` (TappedTransformer: `ratio` is the whole secondary, each half has L1·ratio²/4) | edit setter reject (TappedTransformer rejected 0 only from 2026-10-03; before, it tested the old `ratio` instead of the entry) |
| `couplingCoef` | strictly `0 < k < 1`; import clamps out-of-range to `0.99` | `TransformerElm:602`, `TappedTransformerElm:678`, `CustomTransformerElm:151-153, 1151-1153` |
| winding `resistance` | >= 0; if 0 → stamp `1e8` conductance stub | `:487-489` |
| `description` (Custom) | non-empty, no `n==0` token, ≤1 `:` separator; fallback on import reject | `:484-523, 1169-1173` |
| `delay` (TransLine) | > 0; triggers `reset()` | `TransLineElm.java:309-315` |
| `imped` (TransLine) | > 0; triggers `reset()` | `:309-315` |
| `lenSteps` | capped at `MAX_DELAY_STEPS = 100_000`; over-cap → `converged=false` | `:38, 104-108` |
| `reset()` | guarded against `maxTimeStep == 0` | `:96-97` |

### 03_02. Singularity / numeric hazards  {#SP_EMG_03_02}

- `couplingCoef == 1` → `det == 0` → `Infinity` propagates; only
  edit-time clamp, **no runtime defensive guard** in `stamp()`.
- Winding R = 0 → internal node only referenced via `1e8` stub;
  iteration is stable but the stub is visible in MNA sparsity.

## 04. State Transitions  {#SP_EMG_04}

### 04_01. Transformer step lifecycle  {#SP_EMG_04_01}

    analyzeCircuit ──► stamp() [once]
        • build & invert L
        • scale by ts (trap: Δt/2, back-Euler: Δt)
        • stampConductance diag / stampVCCurrentSource off-diag
        • stampResistor (or 1e8 stub) per winding; stampRightSide

    each Δt:
        startIteration() ──► sample Vd_k, update curSourceValue_k
        doStep() ────────► stampCurrentSource per coil
        solve ──────────► calculateCurrent() closes the loop
        stepFinished() ─► no-op (currents already settled)

    reset(): zero currents + node voltages (indices 0..postCount+internal-1)

### 04_02. TransLineElm step lifecycle  {#SP_EMG_04_02}

    analyzeCircuit ──► stamp() [once] (2 Vsrc + 2 Rz)
    each Δt:
        startIteration() ──► voltageL[ptr], voltageR[ptr] sampled
        doStep() ────────► Vs1,Vs2 ← -opposite_ring[(ptr+1) mod lenSteps]
        stepFinished() ──► ptr advance once per timeStepCount
    reset(): allocate ring buffers from current delay/maxTimeStep;
             clamp to MAX_DELAY_STEPS; set converged=false on clamp

## 05. Verification Criteria  {#SP_EMG_05}

### 05_01. Functional Expectations  {#SP_EMG_05_01}

| Contract | Scenario | Expected |
|---|---|---|
| TransformerElm | 1:1, k=0.999, L=1 mH, sine drive | secondary mirrors primary within k·V |
| TransformerElm | ratio=2, ideal load | V_sec = 2·V_pri, I_sec = I_pri/2 |
| TappedTransformerElm | centre tap grounded, balanced load | upper/lower halves equal+opposite |
| CustomTransformerElm | `"1,1:1+1"` description | 2 primary coils, tap-joined secondary |
| CustomTransformerElm | bad description import | fallback to previous valid one |
| TransLineElm | matched term. (Z=imped) | no reflection; step propagates after `delay` |
| TransLineElm | open/short far end | full reflection with correct polarity |

### 05_02. Invariant Checks  {#SP_EMG_05_02}

| Invariant | Verification |
|---|---|
| `getVoltageSourceCount() == 0` for all 3 transformers | reflection on class |
| `getVoltageSourceCount() == 2` for TransLineElm | reflection |
| Internal nodes = coil count (transformers) | assertion |
| `couplingCoef` import clamped to `0.99` when out of range | JSON round-trip |
| Ring-buffer size respects `MAX_DELAY_STEPS` | oversize-load test |

### 05_03. Integration Scenarios  {#SP_EMG_05_03}

| Scenario | Preconditions | Expected |
|---|---|---|
| Save / reload TransformerElm | dump + reload | all 5 parameters preserved |
| CustomTransformerElm JSON round-trip | save + reload | `description`, tap overrides, per-coil currents all restored |
| TransLineElm over-long delay | `delay/maxTimeStep > 1e5` | `delay` truncated, spurious `converged=false` on first step (Issue #4) |
| Back-Euler flag toggle | `FLAG_BACK_EULER` set | `ts = Δt`, no trapezoidal correction in `startIteration` |

### 05_04. Edge Cases and Boundaries  {#SP_EMG_05_04}

| Case | Input | Expected |
|---|---|---|
| `couplingCoef == 1` (bad dump) | legacy file | `Infinity` propagates — latent issue #3 |
| Winding R = 0 | explicit 0 | stamped via `1e8` conductance stub |
| `maxTimeStep == 0` at construction | init race | `TransLineElm.reset()` short-circuits (`:96-97`) |
| `V_gnd ≈ 1e-4` on TransLine | near-balanced | convergence hint fires |
| TappedTransformerElm.reset zeros 8 nodes | hard-coded | correct by accident; brittle if counts change (Issue #13) |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
