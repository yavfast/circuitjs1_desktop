# Task: C_SLV — sparse linear system solver

> **Task ID:** `task_C_SLV`
> **Created:** 2026-10-05 16:40
> **Last updated:** 2026-10-05 18:00
> **Status:** `in-progress`
> **Contributors:** `main`
> **Autonomy:** `checkpoints`

## Current Work Item

| Field | Value |
|-------|-------|
| **Document** | `concept` — [linear-solver.concept.md](../../docs/linear-solver.concept.md) |
| **Pipeline phase** | `plan` (PL_SLV written; Plan → Code gate self-checked; awaiting design sign-off) |
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
- [ ] Design sign-off with the developer, then `/dev-flow implement` Phase 1

**Activity:**
- 18:00 — plan written; gate self-checked

## Coordination Notes

- Uncommitted work of task_20261005_153627_spike-solver-defects (fix) and task_20261005_150450_sparse-solver-research is in the same tree; its commit sign-off is still open with the developer.

## Blocking Issues

{No blockers yet.}

## Relevant Context

- `{s:pin}` SP_SLV key shapes: per-stamp solver lifecycle (`beginStamp`) with CarriedState (extra positions + symbolic) per engine analysis while mapRow/mapCol unchanged; `solverRestampPending` (stamp only, never preStamp); sparse store + reduction on every path (bit identity by summation order); DENSE_MAX_SIZE = 64; simControl action `solver` (non-mutating); Diagnostics/simControl `solver` block; Other Options row before the time-step rows.
- `{s:pin}` C_SLV decisions: in-house sparse LU (transversal + minimum degree + left-looking threshold pivoting + growth-checked refactor); fully sparse store/snapshot/reduction; Auto (dense < ~64 reduced unknowns, today's algorithm unchanged) / Dense / Sparse; session default in the UI, persisted with preferences; per-document override via Agent/JS API, never saved; same absolute 1e-14 singular test and diagnostics; path/size/nnz observable in diagnostics.
- Spec must touch: SP_SIM (storage behind stamping primitives, reduction), SP_MDS (dense kernel unchanged), SP_AGA §02_09 simControl + diagnostics fields, SP_APC (options UI), JSON/text formats untouched.
