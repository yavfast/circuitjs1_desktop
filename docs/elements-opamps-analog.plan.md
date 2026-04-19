# Implementation Plan: Op-Amp and Analog-Signal Elements  {#PL_EOA}

> **Code:** PL_EOA
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_EOA](./elements-opamps-analog.concept.md)
> **Specification:** [SP_EOA](./elements-opamps-analog.sp.md)
> **Depends on plans:** [PL_ELB](./element-base.plan.md), expression-engine plan
> **Used by plans:** —
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-opamps-analog.md](../.dev_flow/onboard/analysis/domain-core__cat-opamps-analog.md)

## Goal

Stable catalog of 15 analog-signal building blocks using two reusable
patterns: Newton-Raphson piecewise companion (op-amps) and finite-
difference Jacobian over arbitrary `Expr` (dependent sources).

## Progress

- [x] Phase 1 — OpAmpElm (piecewise linear + saturation clamp) [DONE]
- [x] Phase 2 — OpAmpSwapElm, OpAmpRealElm, OTAElm, ComparatorElm [DONE]
- [x] Phase 3 — InvertingSchmittElm + SchmittElm (hysteresis + slew) [DONE]
- [x] Phase 4 — VCCSElm, VCVSElm, CCVSElm, CCCSElm (Expr-driven) [DONE]
- [x] Phase 5 — CC2Elm, CC2NegElm (current conveyors) [DONE]
- [x] Phase 6 — VCOElm, PhaseCompElm (PLL helpers) [DONE]

## Phases

### All phases [DONE]

Catalog fully implemented per SP_EOA.

## Backlog

Issues surfaced by the analysis:

1. **GBW retained but unused** on OpAmpElm — comment admits it's there
   only to keep file format stable. Candidate for implementation or
   deprecation + migration.
2. **Random jitter in Newton-Raphson** (OpAmpElm `RandomUtils`) — makes
   solver non-deterministic; hack to break limit cycles. Document why.
3. **ComparatorElm lacks hysteresis** — misleading menu label; use
   SchmittElm for real hysteresis. Naming/UX issue.
4. **VCOElm fixed 0–5 V output, 2.5 V threshold, 1 MΩ fallback** —
   magic numbers, no EditInfo.
5. **PhaseCompElm `stampNonLinear(0)` on ground** — unusual; verify it's
   a safe no-op.
6. **CCVSElm/CCCSElm spice path may NPE** — `voltageSources[]` may
   contain nulls if no matching VoltageElm in composite.
7. **Expr parse failure uses `Window.alert`** — blocks element creation;
   prefer inline error marker.
8. **OpAmpRealElm model-string child indices are magic** — resistor
   slot 21+i, cap at `compElmList.get(modelType==741 ? 20 : 4)`. Any
   reordering of model string silently breaks slew tuning.
9. **SchmittElm extends InvertingSchmittElm** — semantically backwards;
   state field interpretation differs between the two. Extract
   `SchmittBase`.
10. **Unit inconsistency** — InvertingSchmittElm `slewRate` in V/ns,
    OpAmpRealElm `slewRate` in V/µs, VCOElm has no slew-rate field.
11. **CC2Elm has commented-out `nonLinear()=true`** — hints element was
    once non-linear. Clarify in comment.
12. **CCCSElm broken-pair detection** — only output pair checked; input
    pair disconnection undetected.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial retrospective plan |
