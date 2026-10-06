# Task: C_SLV — sparse linear system solver

> **Task ID:** `task_C_SLV`
> **Created:** 2026-10-05 16:40
> **Last updated:** 2026-10-06
> **Status:** `done`
> **Contributors:** `main`
> **Autonomy:** `full` — "Go, commit each phase autonomously" (design sign-off answer 2026-10-05; scope: per-phase commits on `feat/sparse-solver` after review + tests; no merge, no push)

## Current Work Item

| Field | Value |
|-------|-------|
| **Document** | `concept` — [linear-solver.concept.md](../../docs/linear-solver.concept.md) |
| **Pipeline phase** | `implement` complete (PL_SLV P1–P6 committed; `master` fast-forwarded to a7ff642 and pushed 2026-10-06) |
| **Traceable ID** | C_SLV (from PL_SIM backlog TD_20261005_145712_sparse-solver; spike [sparse-solver.spike.md](../../docs/sparse-solver.spike.md)) |

## Intent

- **Goal (why):** large circuits analyse and step too slowly with the dense O(m³) LU (2000 nodes: 22 s analysis; 1000-node diode ladder: 5.9 s per step).
- **Target state:** a solver whose cost follows the non-zeros for large circuits, with today's dense path kept bit-identical for small ones, and a user-visible solver mode.
- **Expected result:** C_SLV concept → SP_SLV → PL_SLV, then implementation.

## Subtasks

### Subtask: concept
> Author: `main` — Created: 16:40 — Last updated: 17:01 — Status: `done`

**Progress:**
- [x] Context: spike, C_SIM, C_MDS, skills (solver-performance, mna-stamping, newton-raphson-loop), RULE_ARCH_001/006
- [x] Interview: DEC_01 A (in-house), DEC_02 A (fully sparse), DEC_03 B (Auto + UI mode), DEC_04 A (same absolute singular test), DEC_05 C (session preference + unsaved per-document API override)
- [x] Concept written (new C_SLV; C_SIM delegates storage/factor/solve); index, epic E_SIMULATOR, glossary (Solve path, Solver mode, Pattern, Refactorization), PL_SIM backlog item promoted
- [x] Concept → Spec gate self-check: no contradiction with C_SIM §3.2; integration points C_SIM, C_MDS, C_DOC, C_APC, C_AGA; IS/IS NOT; checklist incl. rollback (Dense mode) and maintainer; reuse check; no banned phrases; DECs resolved
- [x] SP_SLV (see subtask spec)

**Activity:**
- 17:01 — concept draft complete; C_DOC added as dependency (RULE_ARCH_006 scopes)
- 16:40 — concept phase started from the spike hand-off

### Subtask: spec
> Author: `main` — Created: 17:05 — Last updated: 17:22 — Status: `done`

**Progress:**
- [x] Code read: stamping primitives, RowInfo, simplifyMatrix, Newton loop, simControl/Diagnostics/MCP circuit_sim, Other Options dialog, OptionsManager
- [x] Interview: SP_SLV_DEC_01 A (simControl action `solver`, non-mutating); without interview (SP_SLV_DEC_02): store + reduction sparse on every path (path depends on the reduced size), DENSE_MAX_SIZE = 64, UI row in Other Options, no scripting-interface method, corrected singular diagnostics
- [x] SP_SLV written; concept narrowed (Agent API only; mode change → re-stamp); Used-by back-references in SP_SIM/SP_MDS/SP_DOC/SP_AGA/SP_MCP; index
- [x] Spotted defect (dense `describeMatrixVariable` wrong unknown after reduction) fixed in task_20261005_153627_spike-solver-defects
- [x] Clean-context review: round 1 BLOCK (12 findings: per-stamp lifecycle, re-stamp-only mode change, structural L/U reach, sparse singular test at least as strict, reduction identity scope, MCP/dialog/SP_AGA details, test realism) → fixed; round 2 PASS with advisories → applied (carried symbolic reuse via patternVersion continuity, reach in the factorization, backward-error criterion, wording)
- [x] PL_SLV (see subtask plan)

**Activity:**
- 17:45 — review rounds 1–2 applied; spec ready for the plan
- 17:22 — spec draft complete; Spec → Plan gate self-check passed (types, contracts + errors, criteria incl. bit identity and performance targets, integration scenarios, rollback, no banned phrases, DECs resolved)

### Subtask: plan
> Author: `main` — Created: 17:50 — Last updated: 18:00 — Status: `done`

**Progress:**
- [x] Interview: PL_SLV_DEC_01 A (JUnit 5 for the GWT-free `client/solver/` kernel + live harness for integration)
- [x] Without interview: package `client/solver/` (L0, RULE_STRUCT_007/RULE_ARCH_001), int-pair open-addressing slot map (no `long`, no boxing), branch `feat/sparse-solver`, one commit per phase
- [x] PL_SLV: 6 phases (baseline + JUnit → kernel → store/reduction dense-only gated by corpus bit identity → sparse path + mode + Agent API → UI/MCP/docs → perf/verify/propagate); every spec section covered; Verify per phase
- [x] Design sign-off with the developer (2026-10-05: go, per-phase autonomous commits)

**Activity:**
- 18:00 — plan written; gate self-checked

### Subtask: implement
> Author: `main` — Created: 18:20 — Last updated: 01:30 — Status: `done`

**Progress:**
- [x] P1 baseline + JUnit — 21d4ee9
- [x] P2 sparse kernel — 68612a5
- [x] P3 store + reduction, dense only — c594c61 (corpus 327/327 bit-identical)
- [x] P4 sparse path, mode, Agent API — 3a42579
- [x] P5 Options row, MCP (toolsVersion 1.3), docs — dc3010f
- [x] P6 perf (all §05_05 targets met), rollback, full verify, propagation, `calcWireInfo` index — P6 commit
- Every phase: clean-context review PASS, advisories fixed before commit.
- `npm run test:mcp` run 2026-10-06 in an own network namespace: 70 pass, 1 namespace artifact (`readOnlyDirEacces`), solver checks pass.
- RULE_TEST_002 stand-in run 2026-10-06 in the real NW.js app (`nw_check.mjs`): all checks pass. Merge into `master` and push: the developer agreed ("Ок", 2026-10-06), but the session's permission rules blocked the push; left to the developer (`git checkout master && git merge --ff-only feat/sparse-solver && git push origin master`).
- Merged and pushed on the developer's word (2026-10-06 "Merge and push feat/sparse-solver"): `master` fast-forwarded e531346..a7ff642 (no merge commit), origin/master a6bec19..a7ff642; branch `feat/sparse-solver` kept locally, not pushed.

**Activity:**
- 01:30 — P6: RC-ladder analysis missed 0.5 s in the node analysis (`calcWireInfo` 1.7 s) → indexed; all targets met; rule RULE_TEST_008 written (should)
- 23:05 — P4: GWT prunes `setSolverModeDefault` until P5's dialog calls it; session checks gated until then
- 23:00 — upstream escalation (spec fix, not code): SP_SLV_05_01 agreement tolerance scales with conditioning
- 22:10 — P1/P2 commits redone (a staged `git mv` had slipped into P1; branch unpushed)
- 21:50 — spotted defect filed: TD_20261005_220500_run-rng-determinism

## Coordination Notes

- (2026-10-05 18:20) The earlier uncommitted spike/fix work was committed in e531346; the tree was clean at branch creation.

## Blocking Issues

{No blockers yet.}

## Relevant Context

- `{s:pin}` SP_SLV key shapes: per-stamp solver lifecycle (`beginStamp`) with CarriedState (extra positions + symbolic) per engine analysis while mapRow/mapCol unchanged; `solverRestampPending` (stamp only, never preStamp); sparse store + reduction on every path (bit identity by summation order); DENSE_MAX_SIZE = 64; simControl action `solver` (non-mutating); Diagnostics/simControl `solver` block; Other Options row before the time-step rows.
- `{s:pin}` C_SLV decisions: in-house sparse LU (transversal + minimum degree + left-looking threshold pivoting + growth-checked refactor); fully sparse store/snapshot/reduction; Auto (dense < ~64 reduced unknowns, today's algorithm unchanged) / Dense / Sparse; session default in the UI, persisted with preferences; per-document override via Agent/JS API, never saved; same absolute 1e-14 singular test and diagnostics; path/size/nnz observable in diagnostics.
- Spec must touch: SP_SIM (storage behind stamping primitives, reduction), SP_MDS (dense kernel unchanged), SP_AGA §02_09 simControl + diagnostics fields, SP_APC (options UI), JSON/text formats untouched.
