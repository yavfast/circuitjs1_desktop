# Dev-Flow Active Context

A thin index over the task files in [`tasks/`](tasks/) — active and recently completed tasks only; per-task state lives in those files.

## Resume

- `/dev-flow resume task_E_AGT` — `verify` — merged to `master` and pushed at c1d0d90 (2026-10-05); next: the developer's manual NW.js devmode checks (relays/controlled sources free-run + the older owed list); optional: sparse solver, PL_AGA Phase 16b

## Active Tasks

| Task | Phase | Started | Contributors | Next |
|------|-------|---------|--------------|------|
| [task_E_AGT](tasks/task_E_AGT.md) — agent automation over MCP (epic [E_AGT](../docs/agent-automation.epic.md)) | implement (PL_AGA Phases 11–15: agent model definitions) + verify (live agent series) | 2026-10-01 | main | review + commit Phase 14 → Phase 15 → re-run series T8–T10; manual checks still owed |

## Recently Completed

| Task | Phase | Completed | Contributors | Result |
|------|-------|-----------|--------------|--------|
| [task_C_SLV](tasks/task_C_SLV.md) — sparse linear system solver | implement | 2026-10-06 | main | PL_SLV P1–P6 on `feat/sparse-solver` (not merged/pushed); `test:mcp` done (net namespace); owed: NW.js devmode check |
| [task_20261005_153627_spike-solver-defects](tasks/task_20261005_153627_spike-solver-defects.md) — solver defects from the sparse-solver spike | fix | 2026-10-05 | main | reset run stamps once; singular retry escalates; singular message maps reduced columns; live `solver_defects` |
| [task_20261005_150450_sparse-solver-research](tasks/task_20261005_150450_sparse-solver-research.md) — sparse solver; WebGPU / server acceleration | research | 2026-10-05 | main | spike [sparse-solver.spike.md](../docs/sparse-solver.spike.md) concluded; skill simulator/solver-performance; cache sparse-spike; next: C_SIM concept interview |
| [task_20261002_100004_circuit-lang-research](tasks/task_20261002_100004_circuit-lang-research.md) — language for circuit description, simulation and measurement | research | 2026-10-02 | main | spike [circuit-script-language.spike.md](../docs/circuit-script-language.spike.md) concluded; skills automation/circuit-experiment-language, agent-run-behaviour; next: concept interview |
| [task_20261001_142742_mcp-research](tasks/task_20261001_142742_mcp-research.md) — app as MCP server + agent client/skill | research | 2026-10-01 | main | spike [mcp-agent-bridge.spike.md](../docs/mcp-agent-bridge.spike.md) concluded; skills automation/ |
| [task_20261001_131500_backlog-fixes-3](tasks/task_20261001_131500_backlog-fixes-3.md) — backlog batch 3 (BL-C07/C08/C09) | fix | 2026-10-01 | main | fix/backlog-20260930, merged to master |
| [task_20261001_113805_backlog-fixes-2](tasks/task_20261001_113805_backlog-fixes-2.md) — backlog batch 2 (BL-C01..C04, BL-A01) | fix | 2026-10-01 | main | fix/backlog-20260930, merged to master |
| [task_20260930_205344_backlog-fixes](tasks/task_20260930_205344_backlog-fixes.md) — backlog defect fixes (BL-A06/A05/A03/B05/B06) | fix | 2026-09-30 | main | 21079bc on fix/backlog-20260930, merged to master |
| [task_20260930_173830_code-audit-full](tasks/task_20260930_173830_code-audit-full.md) — full code audit + defect fixes | audit (code) + fix | 2026-09-30 | main | Plan [whole_20260930_173830](audit/whole_20260930_173830.plan.md); fixes on master (b64e347, f0aee41, df62f60) |
| [task_20260621_125942_fix-bl-drop](tasks/task_20260621_125942_fix-bl-drop.md) — JSON type-name aliases | fix | 2026-06-21 | main | c8d61db |
| [task_20260621_104235_code-audit-defects](tasks/task_20260621_104235_code-audit-defects.md) — defects audit (security + correctness) | audit (code) | 2026-06-21 | main | cdba7b6, cbfb7b7 |
| onboard (no task file) — reverse-engineered docs, rules, skills | onboard | 2026-04-19 | — | [onboard/report.md](onboard/report.md); old dashboard in [session_history/](session_history/active_context_onboard_20260419.md) |

## Deferred (todos)

Register [todos/_index.md](todos/_index.md): 3 candidates · 0 queued · 0 contested — TD_20261005_145712_forced-run ("Run for…" a set span to skip transients), TD_20261005_155200_cappar-retry-limit (verify first), TD_20261005_220500_run-rng-determinism (latches random from reset).

- TD_20261005_145712_sparse-solver (PL_SIM backlog) delivered by PL_SLV (task_C_SLV).

Other open work lives in the backlog of [audit/whole_20260930_173830.plan.md](audit/whole_20260930_173830.plan.md): ITEM-15 (CSP, active) · BL-A01…A16 · BL-B01…B06 · BL-C01…C11 · BL-D01…D03 (2026-10-02, circuit-language spike) · June BL-RD/BL-AR/BL-01…04 · proposed decisions PL_AUDIT_20260930_173830_DEC_01 (release build profile) and DEC_03 (element↔dialog cycle).

## Notes

- 2026-10-01 branch verify: PASS vs master (regression full corpus + real undo); NW.js manual checklist pending with the developer.
- Suggested next: DEC_01 build profile, BL-A11 test layer, ITEM-15 CSP.
- Verification tool: `npm run buildgwt && npm run test:live` ([tests/live/README.md](../tests/live/README.md)) — RULE_TEST_006.
- Tree at 2026-10-01: branch `master`, fix/backlog-20260930 fast-forwarded in (not pushed); merged feature branches `fix/audit-20260930`, `fix/audit-defects-20260621`, `fix/backlog-20260930` still exist locally.

---

*Dashboard maintained by dev-flow commands. Each contributor updates only their own row context (e.g., adds itself to Contributors when joining a task). Hygiene: keep under ~80 lines; any contributor may rebuild it from `tasks/*.md` when in doubt. See `phases/status.md` (dev-flow skill).*
