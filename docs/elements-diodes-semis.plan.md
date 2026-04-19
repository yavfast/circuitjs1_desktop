# Implementation Plan: Diode and Semiconductor Elements  {#PL_EDS}

> **Code:** PL_EDS
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_EDS](./elements-diodes-semis.concept.md)
> **Specification:** [SP_EDS](./elements-diodes-semis.sp.md)
> **Depends on plans:** [PL_ELB](./element-base.plan.md), [PL_SHM](./shared-models.plan.md)
> **Used by plans:** —
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-diodes-semis.md](../.dev_flow/onboard/analysis/domain-core__cat-diodes-semis.md)

## Goal

Stable catalog of 11 diode/semiconductor elements built on the shared
`Diode` Newton-Raphson helper and `DiodeModel` catalog (plus two
composites and one bespoke tunnel-diode exponential).

## Progress

- [x] Phase 1 — DiodeElm core (Shockley via Diode helper, Zener branch) [DONE]
- [x] Phase 2 — ZenerElm, LEDElm, VaractorElm (DiodeElm subclasses) [DONE]
- [x] Phase 3 — LEDArrayElm (ChipElm-based grid) [DONE]
- [x] Phase 4 — SCRElm, TriacElm, DiacElm (latching thyristors) [DONE]
- [x] Phase 5 — TunnelDiodeElm (bespoke triple exponential) [DONE]
- [x] Phase 6 — UnijunctionElm, OptocouplerElm (CompositeElm-based) [DONE]

## Phases

### All phases [DONE]

Catalog fully implemented per SP_EDS.

## Backlog

Issues surfaced by the analysis:

1. **DiacElm.state lost across save/reload** — `dump()` omits `state`,
   no `*JsonState` override. Triac round-trips correctly; Diac doesn't.
2. **SCR latching recomputed inside `doStep`** — inequality evaluated
   every Newton step rather than in `startIteration` (as Triac/Diac do).
   Can oscillate between on/off stamps during iterations.
3. **TunnelDiode constants hard-coded** — `pvp, pip, pvv, piv, pvpp`
   fit a 1N3712-ish curve; no DiodeModel binding, no parameterization.
4. **VaractorElm divides by `model.fwdrop`** — if a user-authored
   "advanced" model never calls `setForwardVoltage()`, fwdrop may be 0
   → Inf in C(V). No guard.
5. **LEDArrayElm has no user control over diode model** — hardcoded
   `default-led`; customizing forward drop requires editing default-led
   globally.
6. **LEDArrayElm.stamp allocates `diodes[sizeX·sizeY]` every call** —
   acceptable but unpooled; 16×16 grid allocates 256 helpers per resize.
7. **OptocouplerElm.getEditInfo returns null** — CTR polynomial, LED
   model, and β=700 all uneditable from UI.
8. **UnijunctionElm forces `adjustTimeStep = true`** at construction —
   no per-circuit opt-out.
9. **SCR/Triac/Diac use 0.01–500 Ω magic on-resistances** not exposed
   as editable parameters.
10. **LEDArrayElm silently overrides commented-out `getConnection`** —
    "strange behavior with unconnected pins".
11. **SCRElm `FLAG_GATE_FIX` inconsistency** — set on new instances,
    not on legacy dumps; TriacElm has no equivalent flag.
12. **Model binding for SCR/Triac/Diac is invisible** — `setupForDefaultModel`
    resolves "default" by name; renaming it orphans them.
13. **Optocoupler.getConnection uses `n1/2 == n2/2`** — assumes pin
    order; any reordering silently connects isolated sides.
14. **DiodeElm hardcoded models** — `default`, `default-zener`,
    `default-led`, `x2n2646-emitter` are seeded in `DiodeModel` but not
    user-extensible without code edit.
15. **Dump-type scattering** — 175/176/177 consecutive (PN cluster),
    but 203/206/405/407/417 scattered; same central-registry concern.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial retrospective plan |
