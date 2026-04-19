# Implementation Plan: Switch Elements  {#PL_ESW}

> **Code:** PL_ESW
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_ESW](./elements-switches.concept.md)
> **Specification:** [SP_ESW](./elements-switches.sp.md)
> **Depends on plans:** [PL_ELB](./element-base.plan.md), [PL_UTL](./util-locale-log.plan.md), [PL_GEO](./geometry.plan.md), [PL_RND](./rendering-primitives.plan.md)
> **Used by plans:** editor-interaction, circuit element factory
>
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-switches.md](../.dev_flow/onboard/analysis/domain-core__cat-switches.md)

## Goal

Document the shipped state of the 9 switch elements and capture backlog
items (abstraction unification, ranges, stamping modernization, label
de-duplication) for future iteration.

## Technology Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Mechanical stamp | 0-V voltage source per pole | MNA-clean; current cleanly recoverable |
| Analog stamp | resistor pair + `nonLinear()` | Matrix re-stamp per Newton iteration |
| Motor-protection model | `I²t` heat integrator + `1e9 Ω` blown stamp | Physically meaningful latch |
| Click pathway | `instanceof SwitchElm` gate | Simple; excludes analog/MPS by design |
| Ganged toggle | `FLAG_LABEL` + `label` string / numeric `link` group | Back-compat with legacy dumps |

## Progress

- [DONE] Phase 1 — `SwitchElm` base (SPST + momentary + label)
- [DONE] Phase 2 — `Switch2Elm`, `PushSwitchElm`
- [DONE] Phase 3 — `DPDTSwitchElm`, `CrossSwitchElm`, `MBBSwitchElm`
- [DONE] Phase 4 — `AnalogSwitchElm`, `AnalogSwitch2Elm`
- [DONE] Phase 5 — `MotorProtectionSwitchElm` + label-driven contact drive
- [backlog] Phase 6 — cross-cutting refactors

## Phases

### Phase 1 — SwitchElm base [DONE]

**Implements:** [SP_ESW_01_01](./elements-switches.sp.md#SP_ESW_01_01)

- 2-post, 2-position, click-toggle + momentary; dump `'s'`.

### Phase 2 — SP-Nthrow + momentary [DONE]

**Implements:** [SP_ESW_02_01](./elements-switches.sp.md#SP_ESW_02_01)

- Switch2Elm parametric throws; PushSwitchElm 17-line subclass.

### Phase 3 — Multi-pole mechanical [DONE]

**Implements:** [SP_ESW_01_02](./elements-switches.sp.md#SP_ESW_01_02)

- Per-pole 0-V voltage sources; 4-position MBB rotation; 2×2 crossbar.

### Phase 4 — Analog (voltage-controlled) [DONE]

**Implements:** [SP_ESW_02_03](./elements-switches.sp.md#SP_ESW_02_03)

- r_on/r_off, threshold, FLAG_INVERT, FLAG_PULLDOWN.

### Phase 5 — MotorProtection + contact linking [DONE]

**Implements:** [SP_ESW_02_02](./elements-switches.sp.md#SP_ESW_02_02)

- `I²t` latch; `setSwitchPositions()` broadcasts to `RelayContactElm`.

## Backlog

Items deferred (from analysis Issues):

1. Extract `AbstractSwitchElm` interface so analog/MPS participate in a
   uniform click/toggle framework (Issue #1).
2. Move `MBBSwitchElm.both` assignment out of `getVoltageSourceCount()`
   side effect into `startIteration` / `updateState` (Issue #2).
3. Investigate generalized wire-closure for N-node multi-pole collapse
   (Issue #3, referenced #646).
4. Introduce central `SwitchDumpTypes` constants registry (Issue #4).
5. Make `MotorProtectionSwitchElm` geometry grid-parametric
   (Issue #5).
6. Fix `AnalogSwitch2Elm.calculateCurrent` to use per-branch resistor
   (Issue #6).
7. Normalize `Switch2Elm.flipX/Y` vs `DPDTSwitchElm.flip()` formulas
   (Issue #7).
8. Consider adding `'P'` shortcut for `PushSwitchElm` (Issue #8).
9. Validate `CrossSwitchElm` `isRemovableWire` comment origin (Issue #9).
10. Add rail-selectable analog-switch pulldown target (Issue #10).
11. Reconcile `SwitchElm.getJsonTypeName()` overload with
    `PushSwitchElm.getJsonTypeName()` (Issue #11).
12. Add null-guard to `MotorProtectionSwitchElm.applyJsonState`
    (Issue #12).

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
