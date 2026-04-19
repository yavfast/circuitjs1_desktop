# Implementation Plan: Magnetics & Transmission Elements  {#PL_EMG}

> **Code:** PL_EMG
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_EMG](./elements-magnetics-transmission.concept.md)
> **Specification:** [SP_EMG](./elements-magnetics-transmission.sp.md)
> **Depends on plans:** [PL_ELB](./element-base.plan.md), [PL_UTL](./util-locale-log.plan.md), [PL_GEO](./geometry.plan.md), [PL_RND](./rendering-primitives.plan.md), [PL_MDS](./math-dsp.plan.md), [PL_EPS](./elements-passives.plan.md)
> **Used by plans:** — (will be filled by higher layers)
>
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-graphic-and-magnetics.md](../.dev_flow/onboard/analysis/domain-core__cat-graphic-and-magnetics.md)
> (magnetics cluster only)

## Goal

Document the four shipped multi-port propagation elements — three
coupled-inductor transformers (2, 3, N coils) stamped via inverted
mutual-inductance matrices, and one lossless Bergeron transmission
line — and capture the backlog of refactoring and robustness items
surfaced by the backing analysis.

## Technology Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Companion model (transformers) | Norton (conductance + VCCS) | Matches the N-coil coupled-inductor pattern; no v-source slots needed |
| Inverse (2 coils) | hand-coded `a1..a4` | Fast path for the common case |
| Inverse (3 coils) | hand-coded `a[9]` with secondary-half reduction | Exploits `m2 ≈ l2` identity for tapped secondary |
| Inverse (N coils) | `CircuitMath.invertMatrix` | Generality outweighs O(N³) cost |
| Winding-R node | one internal node per coil | Keeps series R and inductor in separate MNA rows; R=0 uses `1e8` stub |
| TransLine model | Bergeron with two ring buffers | Lossless matched-impedance propagation with linear stamp |
| Integrator selector | shared `Inductor.FLAG_BACK_EULER` bit | Consistent trapezoidal ↔ back-Euler toggle across passives + transformers |
| Delay cap | `MAX_DELAY_STEPS = 100_000` | Memory bound; overflow nudges solver with `converged=false` |

## Progress

- [DONE] Phase 1 — TransformerElm (2-coil hand-coded inverse)
- [DONE] Phase 2 — TappedTransformerElm (3-coil with secondary-half reduction)
- [DONE] Phase 3 — CustomTransformerElm (N-coil + description DSL)
- [DONE] Phase 4 — TransLineElm (Bergeron ring buffers)
- [backlog] Phase 5 — refactor and robustness

## Phases

### Phase 1 — TransformerElm [DONE]

**Implements:** [SP_EMG_01_01](./elements-magnetics-transmission.sp.md#SP_EMG_01_01), [SP_EMG_02_01](./elements-magnetics-transmission.sp.md#SP_EMG_02_01)

- 2×2 hand-coded inverse; 4 posts + 2 internal nodes; primary/secondary
  resistances; shortcut `T`.

### Phase 2 — TappedTransformerElm [DONE]

**Implements:** [SP_EMG_01_01](./elements-magnetics-transmission.sp.md#SP_EMG_01_01), [SP_EMG_02_04](./elements-magnetics-transmission.sp.md#SP_EMG_02_04)

- 3×3 inverse via secondary-half coupling reduction; derived tap-wire
  current; 5 posts + 3 internal nodes.

### Phase 3 — CustomTransformerElm [DONE]

**Implements:** [SP_EMG_01_01](./elements-magnetics-transmission.sp.md#SP_EMG_01_01), [SP_EMG_01_03](./elements-magnetics-transmission.sp.md#SP_EMG_01_03)

- N-coil general case; description DSL; `CircuitMath.invertMatrix`;
  split JSON state.

### Phase 4 — TransLineElm [DONE]

**Implements:** [SP_EMG_02_03](./elements-magnetics-transmission.sp.md#SP_EMG_02_03)

- Bergeron method; two ring buffers; matched-impedance Thévenin sources;
  `MAX_DELAY_STEPS` cap; ground-drift convergence hint.

## Backlog

Items deferred (Issues copied verbatim from the backing analysis):

1. **Transformers bypass the `Inductor` helper**. Every claim that
   "TransformerElm uses 2 Inductor helpers + mutual inductance" is
   **factually wrong** for this codebase. The three transformer
   classes inline their own companion-model inversion. Only the
   `Inductor.FLAG_BACK_EULER` bit is shared. This is a refactoring
   opportunity — `Inductor` could be generalized to expose a
   `stampCoupled(matrix, ts)` API and eliminate ~200 LOC of duplicated
   stamp/startIteration/calculateCurrent across the three files.
2. **Near-identical stamp/startIteration/calculateCurrent loops** in
   TransformerElm, TappedTransformerElm, CustomTransformerElm (with
   only matrix-size differences). CustomTransformerElm's N-coil
   implementation is strictly more general and could subsume the
   other two (at a small performance cost for the hot 2×2 case).
3. **`couplingCoef == 1` is a singularity**. All three transformers
   clamp via edit-time bounds but none refuse it at runtime — if an
   older dump file happens to contain `1.0` the `invertMatrix`/
   `1/(l1*l2-m*m)` computation produces `Infinity` and propagates
   into the MNA matrix. TransformerElm has no defensive clamp in
   `stamp()` itself.
4. **TransLineElm marks `converged=false` from `reset()` on buffer
   clamp** (`:107`) — `reset()` is also called on circuit load, so
   loading a saved file with excessive delay produces a spurious
   convergence-fail notification on the very first step. Cosmetic
   but confusing.
5. **TransLineElm hard-codes the RG-58 velocity factor** (0.65 in
   `getInfo`) for the "length" display — it is not a property of the
   simulation, only a UI hint. Users modelling other cable types see
   an incorrect length label.
6. **CustomTransformerElm JSON state omits tap overrides per step** —
   `getJsonState()` exports `coilCurrent{i}` only (`:1194-1200`);
   tap offsets go through `getJsonProperties` (`:1124-1138`). Split
   is correct by convention but the two read paths interleave
   awkwardly during `applyJsonProperties` (which both rebuilds
   from `description` and restores tap overrides).
7. **TransLineElm `voltSource1` and `voltSource2` not clamped in
   `setVoltageSource(n, v)`** (`:204-209`) — if `n > 1` is ever
   passed, the `else` branch silently overwrites `voltSource2`.
   Defensive `assert n < 2` would catch this.
8. **CustomTransformerElm has a JSNI `console()` helper** (`:40-43`)
   gated by a `DEBUG_RESIZE_HANDLES` flag (`:38`) — dev-only
   instrumentation left in production; should be removed or
   replaced with the standard `CirSim.console` logger.
9. **TappedTransformerElm.reset zeros 8 nodes** (indices 0..7 at
   `:478-485`) even though `getPostCount()+getInternalNodeCount()
   == 5+3 == 8` — correct by accident; any future change to either
   count will silently de-sync.
10. **TransformerElm.reset zeros 6 nodes** (indices 0..5 at
    `:439-444`) while `getPostCount()+getInternalNodeCount() == 4+2
    == 6` — same brittleness as #9. A `for (i = 0; i <
    getPostCount()+getInternalNodeCount(); i++)` loop would remove
    the magic number.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
