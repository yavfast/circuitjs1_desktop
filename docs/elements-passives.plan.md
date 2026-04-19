# Implementation Plan: Passive Elements  {#PL_EPS}

> **Code:** PL_EPS
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_EPS](./elements-passives.concept.md)
> **Specification:** [SP_EPS](./elements-passives.sp.md)
> **Depends on plans:** [PL_ELB](./element-base.plan.md)
> **Used by plans:** —
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-passives.md](../.dev_flow/onboard/analysis/domain-core__cat-passives.md)
>
> Retrospective plan covering the implemented 14-element passive catalog.

## Goal

Stable catalog of 14 passive elements covering linear/reactive/nonlinear/
topology/env-driven patterns, all extending `CircuitElm` directly (except
`PolarCapacitorElm`).

## Progress

- [x] Phase 1 — Linear 2-terminal (Resistor, Wire) [DONE]
- [x] Phase 2 — Topology-only (Ground, LabeledNode) [DONE]
- [x] Phase 3 — Reactive companion models (Capacitor, PolarCapacitor, Inductor) [DONE]
- [x] Phase 4 — 3-terminal Pot [DONE]
- [x] Phase 5 — Non-linear state (Memristor, SparkGap, Fuse, Lamp) [DONE]
- [x] Phase 6 — Environment-driven (ThermistorNTC, LDR) [DONE]

## Phases

### Phase 1–6 — All [DONE]

Catalog fully implemented per SP_EPS. See per-element file:line citations
in the specification table.

## Backlog

Issues surfaced by the analysis and still open:

1. **VaristorElm missing** — requested 15 files, only 14 present. Either
   re-implement or drop from catalog and remove stale references.
2. **MemristorElm lacks edit-value guards** — `setEditValue` accepts any
   value; users can zero `r_on` or mobility and blow up the solver.
   Mirror the ResistorElm `1e-9` clamp pattern.
3. **Duplicated resistor-body draw code** across ResistorElm,
   ThermistorNTCElm, LDRElm, FuseElm — extract a shared `draw2Leads` /
   zigzag helper on `CircuitElm` / `BaseCircuitElm`.
4. **CapacitorElm magic constants** — default initialVoltage 1e-3 V and
   default seriesResistance 1e-3 Ω are hard-coded; surface rationale in
   docs or editor.
5. **Dump-type registry** — mix of single-char (`'r' 'c' 'l' 'm' 'w' 'g'`)
   and numeric codes (174, 181, 187, 207, 209, 350, 374, 404) with no
   central enum. Collision risk when adding new elements.
6. **LabeledNodeElm `labelList` is static** — per-JVM global; sharing
   between simultaneous documents is brittle. Scope to per-document.
7. **PotElm slider position is a property, not state** — inconsistent
   with Capacitor/Inductor/Lamp which split state from properties.
8. **GroundElm legacy `FLAG_OLD_STYLE`** — only path that stamps a
   voltage source; dead code for modern documents. Add import migration
   to drop the branch.
9. **LampElm `startIteration` called from constructor** — timeStep may
   be 0 at that point; the math works but the pattern is fragile.
10. **ThermistorNTCElm** — missing `@Override` annotations; inline magic
    numbers (t0=273.15, .0099, .0001).
11. **PolarCapacitorElm `stepFinished` hack** — setting `converged=false`
    from stepFinished is unusual; document intent in code comment.
12. **WireElm interaction with scope probes** — `isRemovableWire=true`
    means wires disappear post-analysis; UI code expecting stable
    references must handle this.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial retrospective plan |
