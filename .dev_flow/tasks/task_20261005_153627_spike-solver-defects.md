# Task: Fix — solver defects found by the sparse-solver spike

> **Task ID:** `task_20261005_153627_spike-solver-defects`
> **Created:** 2026-10-05 15:36
> **Last updated:** 2026-10-05 17:20
> **Status:** `done`
> **Contributors:** `main`
> **Autonomy:** `checkpoints` (fix round started without asking per the developer's standing preference "fix every found defect right away"; commit still needs the developer's yes)

## Current Work Item

| Field | Value |
|-------|-------|
| **Document** | code fix — [SP_SIM_02](../../docs/simulator-engine.sp.md) SINGULAR row; [SP_AGA_02_10](../../docs/agent-api.sp.md) `run` |
| **Pipeline phase** | `fix` |
| **Traceable ID** | SP_SIM, SP_AGA (origin: [sparse-solver.spike.md](../../docs/sparse-solver.spike.md) Entry 1) |

## Intent

- **Goal (why):** the spike found two solver defects; the developer wants found defects fixed at once.
- **Target state:** (1) an agent `run` with `reset: true` stamps and LU-factors once (today twice — 2 × 21.7 s on a 2000-node grid); (2) a nonlinear LU failure with the singular-matrix stabilizers already active is reported (SP_SIM SINGULAR: escalate to panic 3 / stop), never re-factored on the matrix `lu_factor` has overwritten in place.
- **Expected result:** live scenario `solver_defects` passes; readings unchanged; agent run / free-run scenarios still pass.

## Subtasks

### Subtask: fix round
> Author: `main` — Created: 15:36 — Last updated: 15:55 — Status: `done`

**Progress:**
- [x] Reproduce: live scenario `solver_defects` fails on HEAD a6bec19 build (`resetRunStampsOnce` stamp 2 / factor 2; `singularReportedNotRetried` no `singular_matrix` event)
- [x] Fix `RunController` start (node analysis only when `reset`) and the Newton-loop singular retry (escalate per SP_SIM_02 SINGULAR)
- [x] buildgwt + `solver_defects` + regression `agent_run agent_freerun agent_bg agent_defects verify_defects agent_history loadstate` — all PASS
- [x] Clean-context review — PASS with advisories; applied: harness re-entry + `finally`, plan wording, spec §02_10 clarification, stale Forks line, bookkeeping; corpus sweep (advisory 1): 342 examples with `reset: true`, 0 hit the changed branch (positive control confirms detection), 0 exceptions
- [x] Docs impact (SP_AGA §02_10 + changelog, PL_AGA Done, PL_SIM changelog, README, spike), reflect (skills newton-raphson-loop #6, agent-run-behaviour; no rule — the invariant is local), spotted `cappar.txt` → TD_20261005_155200_cappar-retry-limit
- [x] Third defect (found while writing SP_SLV): `describeMatrixVariable` named the unknown of a reduced column by its full index → mapped through `mapCol`; check `singularVariableMapped` fails on the pre-fix build (`nodeVoltage(node=3)`), passes after; `solver_defects` ×2, `agent_run`, `agent_freerun`, `verify_defects` PASS
- [x] Commit sign-off: developer approved (`/dev-flow commit`, 2026-10-05); RULE_TEST_002 devmode check unobserved — manual steps given to the developer

**Activity:**
- 17:20 — describeMatrixVariable fix + check (failing-first verified by a temporary revert build)
- 15:55 — review advisories applied; corpus sweep clean; awaiting commit sign-off
- 15:41 — fixed; `solver_defects` + 7 regression scenarios PASS
- 15:36 — reproduced both defects with the new `solver_defects` scenario (failing first)

## Coordination Notes

## Blocking Issues

{No blockers yet.}

## Relevant Context

- Root causes: `RunController.start` → `doc.ensureAnalysed()` (`agent/RunController.java:241`) then `sliceBody` → `sim.resetAction()` (needsAnalysis + resetSolverState) → `ensureAnalysed()` again; `CircuitSimulator` Newton loop (`:2104-2117`) → `stampSingularMatrixStabilizers()` into the partly factored, row-swapped `circuitMatrix` + second `lu_factor`. The stabilizers are already in the stamp while `singularStabilizersActive` (`stampCircuit` `:993-995`).
