# Implementation Plan: Netlist Graph  {#PL_NET}

> **Code:** PL_NET
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_NET](./netlist-graph.concept.md)
> **Specification:** [SP_NET](./netlist-graph.sp.md)
> **Depends on plans:** [PL_ELB](./element-base.plan.md), [PL_GEO](./geometry.plan.md)
> **Used by plans:** —
>
> Retrospective plan captured from existing code during onboarding.

## Goal

Document the solver-facing graph primitives as shipped: `CircuitNode`,
`CircuitNodeLink`, `NodeMapEntry`, `WireInfo`, `RowInfo`, `FindPathInfo`.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Graph rep | Adjacency list (`CircuitNode.links`) | compact, direct simulator access |
| Wire merge | Mutable NodeMapEntry + overwrite merge | simple; Union-Find is a backlog item |
| Wire currents | Post-solve reconstruction via WireInfo | wires out of matrix, keeps it small |
| Row metadata | RowInfo array alongside matrix | enables ROW_CONST simplification |
| Validation | DFS with type filters, in-place repair | single pass, educational-mode robustness |

## Progress

- [x] Phase 1 — CircuitNode + CircuitNodeLink
- [x] Phase 2 — NodeMapEntry + wire closure
- [x] Phase 3 — WireInfo + calcWireInfo readiness ordering
- [x] Phase 4 — RowInfo + simplifyMatrix integration
- [x] Phase 5 — FindPathInfo validator

## Phases

### Phase 1 — CircuitNode + CircuitNodeLink (`CircuitNode.java`, `CircuitNodeLink.java`) [DONE]

**Implements:** [SP_NET_01_01](./netlist-graph.sp.md#SP_NET_01_01), [SP_NET_01_02](./netlist-graph.sp.md#SP_NET_01_02)

### Phase 2 — NodeMapEntry + closure (`NodeMapEntry.java` + `CircuitSimulator.calculateWireClosure`) [DONE]

**Implements:** [SP_NET_02_01](./netlist-graph.sp.md#SP_NET_02_01)

### Phase 3 — WireInfo (`WireInfo.java` + `CircuitSimulator.calcWireInfo`) [DONE]

**Implements:** [SP_NET_02_03](./netlist-graph.sp.md#SP_NET_02_03)

### Phase 4 — RowInfo (`RowInfo.java` + `CircuitSimulator.simplifyMatrix`) [DONE]

**Implements:** [SP_NET_01_05](./netlist-graph.sp.md#SP_NET_01_05)

### Phase 5 — FindPathInfo (`FindPathInfo.java`) [DONE]

**Implements:** [SP_NET_02_04](./netlist-graph.sp.md#SP_NET_02_04)

## Backlog

- Replace `NodeMapEntry` + O(n) merge walk with Union-Find.
- Sentinel (-1) defaults for `RowInfo.mapCol`/`mapRow` to catch bugs.
- Generalise `WireInfo.post` beyond 2-post assumption.
- Encapsulate `CircuitNode` fields (private + accessors) to prevent
  accidental mutation outside the simulator.
- Split `FindPathInfo.validateElement` into pure `validate(...)` +
  `repair(...)` pair so the mutation is explicit.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
