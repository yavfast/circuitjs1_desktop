# Implementation Plan: Transistor and Tube Elements  {#PL_ETR}

> **Code:** PL_ETR
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_ETR](./elements-transistors.concept.md)
> **Specification:** [SP_ETR](./elements-transistors.sp.md)
> **Depends on plans:** [PL_ELB](./element-base.plan.md), [PL_SHM](./shared-models.plan.md)
> **Used by plans:** —
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-transistors.md](../.dev_flow/onboard/analysis/domain-core__cat-transistors.md)

## Goal

Stable catalog of 13 transistor and tube elements: BJT (Gummel-Poon),
MOSFET (SPICE L1 square-law), JFET (square-law + gate diodes),
Darlington (CompositeElm), and vacuum triode (Koren 3/2-power).

## Progress

- [x] Phase 1 — TransistorElm Gummel-Poon inline + N/P subclasses [DONE]
- [x] Phase 2 — MosfetElm square-law + body diodes + N/P subclasses [DONE]
- [x] Phase 3 — JfetElm (MosfetElm+gate diodes) + N/P subclasses [DONE]
- [x] Phase 4 — DarlingtonElm (CompositeElm) + N/P subclasses [DONE]
- [x] Phase 5 — TriodeElm Koren 3/2-power + grid resistor [DONE]

## Phases

### All phases [DONE]

Catalog fully implemented per SP_ETR.

## Backlog

Issues surfaced by the analysis:

1. **No `Transistor.java` solver helper** analogous to `Diode.java`.
   160 LOC Gummel-Poon lives inline in `TransistorElm.doStep`. Darlington
   must use CompositeElm sub-netlist instead of stacking solver calls.
2. **DarlingtonElm.modelString hard-codes NTransistorElm** for both
   polarities; pnp mutated in-place after construction. Fragile if
   `TransistorElm.setup` ever re-reads polarity from model/dump.
3. **JfetElm extends MosfetElm** drags in body-diode code JFET must
   disable via `showBulk()=false`. `FLAG_BODY_TERMINAL` dead code for
   JFET. Extract `SquareLawChannel` helper to avoid inheritance quirk.
4. **Polarity as scalar `int pnp` is error-prone** — `+1/-1` is magic;
   typo flips polarity silently.
5. **`TransistorElm.stamp` emits `System.out.println` on non-convergence**
   — should route through `CirSim.console`.
6. **`MosfetElm` has no `startIteration`** — state update only in
   `calculate()`. Harder to reason about.
7. **TriodeElm has no shared model / catalog** — every instance stores
   own `mu`/`kg1`. Adding pentodes/beam tetrodes would need a TubeModel.
8. **TriodeElm `gridCurrentR = 6 kΩ` is hard-coded** — not editable,
   inappropriate for non-12AX7 tubes.
9. **Darlington dump=400, Triode=173 magic numbers** — no central
   registry (same as element-base issue).
10. **JfetElm.getConnection always returns true** — claims every JFET
    pin is connected to every other, which is wrong at connectivity-
    analysis layer (gate is isolated except through gate diodes).
    Compare TriodeElm which correctly returns `!(n1==1 || n2==1)`.
11. **MOSFET source/drain auto-swap** makes MOSFETs symmetric in
    simulation — real MOSFETs are asymmetric (body diode in parallel
    with source, not drain, except for body-diode FETs).

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial retrospective plan |
