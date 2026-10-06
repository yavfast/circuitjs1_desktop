# Dev-Flow Active Context

A thin index over the task files in [`tasks/`](tasks/) — active and recently completed tasks only; per-task state lives in those files.

## Resume

- `/dev-flow resume task_E_AGT` — `verify` — all work merged to `master` and pushed (c1d0d90, 2026-10-05); next: the developer's owed manual NW.js devmode checks (relays/controlled sources free-run + the older list), then a fix round for what they find; optional: PL_AGA Phase 16b (backlog)

## Active Tasks

| Task | Phase | Started | Contributors | Next |
|------|-------|---------|--------------|------|
| [task_E_AGT](tasks/task_E_AGT.md) — agent automation over MCP (epic [E_AGT](../docs/agent-automation.epic.md)) | verify (owed manual devmode checks) | 2026-10-01 | main | the developer's manual check results → fix round → close the task |

## Recently Completed

| Task | Phase | Completed | Contributors | Result |
|------|-------|-----------|--------------|--------|
| [task_C_SLV](tasks/task_C_SLV.md) — sparse linear system solver | implement | 2026-10-06 | main | PL_SLV P1–P6 merged to `master` and pushed at a7ff642 (2026-10-06); `test:mcp` and a scripted NW.js check pass |
| [task_20261005_153627_spike-solver-defects](tasks/task_20261005_153627_spike-solver-defects.md) — solver defects from the sparse-solver spike | fix | 2026-10-05 | main | reset run stamps once; singular retry escalates; singular message maps reduced columns; live `solver_defects` |
| [task_20261005_150450_sparse-solver-research](tasks/task_20261005_150450_sparse-solver-research.md) — sparse solver; WebGPU / server acceleration | research | 2026-10-05 | main | spike [sparse-solver.spike.md](../docs/sparse-solver.spike.md) concluded; skill simulator/solver-performance; delivered by C_SLV |
| [task_20261002_100004_circuit-lang-research](tasks/task_20261002_100004_circuit-lang-research.md) — language for circuit description, simulation and measurement | research | 2026-10-02 | main | spike [circuit-script-language.spike.md](../docs/circuit-script-language.spike.md) concluded; skills automation/circuit-experiment-language, agent-run-behaviour; next: concept interview |
| [task_20261001_142742_mcp-research](tasks/task_20261001_142742_mcp-research.md) — app as MCP server + agent client/skill | research | 2026-10-01 | main | spike [mcp-agent-bridge.spike.md](../docs/mcp-agent-bridge.spike.md) concluded; skills automation/ |

Older completed tasks: [tasks/_index.md](tasks/_index.md); retired task files and the onboard-era dashboard: [session_history/](session_history/).

## Deferred (todos)

Register [todos/_index.md](todos/_index.md): 3 candidates · 0 queued · 0 contested — TD_20261005_145712_forced-run ("Run for…" a set span to skip transients; returns after `task_E_AGT` closes), TD_20261005_155200_cappar-retry-limit (verify first), TD_20261005_220500_run-rng-determinism (latches random from reset).

Other open work lives in the backlog of [audit/whole_20260930_173830.plan.md](audit/whole_20260930_173830.plan.md): ITEM-15 (CSP, active) · BL-A01…A16 · BL-B01…B06 · BL-C01…C11 · BL-D01…D03 (2026-10-02, circuit-language spike) · June BL-RD/BL-AR/BL-01…04 · proposed decisions PL_AUDIT_20260930_173830_DEC_01 (release build profile) and DEC_03 (element↔dialog cycle); PL_AGA backlog: Phase 16b (layout check for the remaining classes).

## Notes

- Verification tools: `npm run buildgwt && npm run test:live` ([tests/live/README.md](../tests/live/README.md), RULE_TEST_006); `npm run test:mcp` / `test:mcp-unit` (MCP); `npm run test:unit` (JUnit, solver).
- Tree at 2026-10-06: branch `master` = `origin/master`; local branches `design/agent-mcp` and `feat/sparse-solver` are fully merged into `master` and never pushed.

---

*Dashboard maintained by dev-flow commands. Each contributor updates only their own row context (e.g., adds itself to Contributors when joining a task). Hygiene: keep under ~80 lines; any contributor may rebuild it from `tasks/*.md` when in doubt. See `phases/status.md` (dev-flow skill).*
