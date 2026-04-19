# Implementation Plan: Electromechanical Elements  {#PL_EEM}

> **Code:** PL_EEM
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_EEM](./elements-electromechanical.concept.md)
> **Specification:** [SP_EEM](./elements-electromechanical.sp.md)
> **Depends on plans:** [PL_ELB](./element-base.plan.md), [PL_UTL](./util-locale-log.plan.md), [PL_GEO](./geometry.plan.md), [PL_RND](./rendering-primitives.plan.md), [PL_EPS](./elements-passives.plan.md)
> **Used by plans:** circuit element factory, simulator, cat-switches cross-ref
>
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-electromechanical.md](../.dev_flow/onboard/analysis/domain-core__cat-electromechanical.md)

## Goal

Document the 6 shipped electromechanical elements and collect backlog
items around label de-duplication, abstraction unification, reset
consistency, and hardcoded physics constants.

## Technology Decisions

| Decision | Choice | Rationale |
|---|---|---|
| DC-motor mechanical DOF | companion Inductor (`J` as L) | Clean MNA integration via existing helper |
| 3-phase mechanical DOF | forward-Euler in `startIteration` | Avoids 6×6 coupling with mechanical |
| Coil integration method | Inductor with FLAG_BACK_EULER | Stability with mechanical coupling |
| Split-coil↔contact coupling | shared `label` string | Industrial ladder-diagram convention |
| Integrated vs split model | two parallel classes kept | Pedagogical compact + distributed forms |
| TimeDelayRelay threshold | hardcoded 2.5 V | Matches default logic mid-rail |

## Progress

- [DONE] Phase 1 — `DCMotorElm` (companion-inductor mechanical)
- [DONE] Phase 2 — `ThreePhaseMotorElm` (5×5 coupled matrix + Euler)
- [DONE] Phase 3 — `RelayElm` (integrated model, old+new stamp modes)
- [DONE] Phase 4 — `RelayCoilElm` + `RelayContactElm` (split pair + FSM)
- [DONE] Phase 5 — `TimeDelayRelayElm` (ChipElm-based threshold timer)
- [backlog] Phase 6 — Cross-cutting cleanups

## Phases

### Phase 1 — DC Motor [DONE]

**Implements:** [SP_EEM_02_02](./elements-electromechanical.sp.md#SP_EEM_02_02)

- Two Inductor helpers (armature, inertia); two controlled VS.

### Phase 2 — 3-phase Motor [DONE]

**Implements:** [SP_EEM_02_03](./elements-electromechanical.sp.md#SP_EEM_02_03)

- 5×5 xformMatrix; invertMatrix; Zp=2 hardcoded; Euler speed.

### Phase 3 — RelayElm (integrated) [DONE]

- Up to 4 poles; old + new stamping; migration button.

### Phase 4 — Split Coil/Contact [DONE]

**Implements:** [SP_EEM_02_01](./elements-electromechanical.sp.md#SP_EEM_02_01)

- 4-state FSM with 4 type modes; label broadcast.

### Phase 5 — TimeDelayRelay [DONE]

**Implements:** [SP_EEM_02_04](./elements-electromechanical.sp.md#SP_EEM_02_04)

- 10 kΩ sense; asymmetric onDelay/offDelay.

## Backlog

Items deferred (from analysis Issues):

1. Reconcile `RelayCoilElm.setParentList(Vector)` vs base
   `ArrayList<CircuitElm>`; verify elmList population path (Issue #1).
2. Warn on duplicate coil labels during analyze (Issue #2).
3. Remove dead `tau` field from DCMotorElm dump or implement
   static-friction param (Issue #3).
4. Decide whether motor `reset()` should zero angle/speed
   (Issues #4, #14).
5. Make `ThreePhaseMotorElm.Zp` user-editable (Issue #5).
6. Upgrade 3-phase Euler speed integration to match BE-companion
   MNA (Issue #6).
7. Expose `TimeDelayRelayElm` threshold as EditInfo (Issue #7).
8. Guard `onDelay`/`offDelay` against negative values (Issue #8).
9. Extract shared `RelayCoilPhysics` helper from RelayElm +
   RelayCoilElm (Issue #9).
10. Extract `drawThickerLine` and `interpPointFix` to base utility
    (Issues #10, #11).
11. Fix `RelayContactElm.getJsonPinNames` to emit "nc" when NC flag set
    (Issue #12).
12. Remove RelayElm "old model" heuristic once migration is universal
    (Issue #13).
13. Centralized dump-type registry (Issue #15).
14. Make `RelayCoilElm.avgCurrent` smoothing factor configurable
    (Issue #16).
15. Clear stale higher-index `switchCurrent` entries when poleCount
    decreases in `RelayElm.setupPoles` (Issue #17).

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
