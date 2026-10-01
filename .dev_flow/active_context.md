# Dev-Flow Active Context

A thin index over the task files in [`tasks/`](tasks/) — active and recently completed tasks only; per-task state lives in those files.

## Active Tasks

| Task | Phase | Started | Contributors | Next |
|------|-------|---------|--------------|------|
| [task_E_AGT](tasks/task_E_AGT.md) — agent automation over MCP (epic [E_AGT](../docs/agent-automation.epic.md)) | spec | 2026-10-01 | main | commit sign-off → `/dev-flow plan` |

## Recently Completed

| Task | Phase | Completed | Contributors | Result |
|------|-------|-----------|--------------|--------|
| [task_20261001_142742_mcp-research](tasks/task_20261001_142742_mcp-research.md) — app as MCP server + agent client/skill | research | 2026-10-01 | main | spike [mcp-agent-bridge.spike.md](../docs/mcp-agent-bridge.spike.md) concluded; skills automation/ |
| [task_20261001_131500_backlog-fixes-3](tasks/task_20261001_131500_backlog-fixes-3.md) — backlog batch 3 (BL-C07/C08/C09) | fix | 2026-10-01 | main | fix/backlog-20260930, merged to master |
| [task_20261001_113805_backlog-fixes-2](tasks/task_20261001_113805_backlog-fixes-2.md) — backlog batch 2 (BL-C01..C04, BL-A01) | fix | 2026-10-01 | main | fix/backlog-20260930, merged to master |
| [task_20260930_205344_backlog-fixes](tasks/task_20260930_205344_backlog-fixes.md) — backlog defect fixes (BL-A06/A05/A03/B05/B06) | fix | 2026-09-30 | main | 21079bc on fix/backlog-20260930, merged to master |
| [task_20260930_173830_code-audit-full](tasks/task_20260930_173830_code-audit-full.md) — full code audit + defect fixes | audit (code) + fix | 2026-09-30 | main | Plan [whole_20260930_173830](audit/whole_20260930_173830.plan.md); fixes on master (b64e347, f0aee41, df62f60) |
| [task_20260621_125942_fix-bl-drop](tasks/task_20260621_125942_fix-bl-drop.md) — JSON type-name aliases | fix | 2026-06-21 | main | c8d61db |
| [task_20260621_104235_code-audit-defects](tasks/task_20260621_104235_code-audit-defects.md) — defects audit (security + correctness) | audit (code) | 2026-06-21 | main | cdba7b6, cbfb7b7 |
| onboard (no task file) — reverse-engineered docs, rules, skills | onboard | 2026-04-19 | — | [onboard/report.md](onboard/report.md); old dashboard in [session_history/](session_history/active_context_onboard_20260419.md) |

## Deferred (todos)

_No `todos/` register. Open work lives in the backlog of [audit/whole_20260930_173830.plan.md](audit/whole_20260930_173830.plan.md): ITEM-15 (CSP, active) · BL-A01…A16 · BL-B01…B06 · BL-C01…C06 · June BL-RD/BL-AR/BL-01…04 · proposed decisions PL_AUDIT_20260930_173830_DEC_01 (release build profile) and DEC_03 (element↔dialog cycle)._

## Notes

- 2026-10-01 branch verify: PASS vs master (regression full corpus + real undo); NW.js manual checklist pending with the developer.
- Suggested next: DEC_01 build profile, BL-A11 test layer, ITEM-15 CSP.
- Verification tool: `npm run buildgwt && npm run test:live` ([tests/live/README.md](../tests/live/README.md)) — RULE_TEST_006.
- Tree at 2026-10-01: branch `master`, fix/backlog-20260930 fast-forwarded in (not pushed); merged feature branches `fix/audit-20260930`, `fix/audit-defects-20260621`, `fix/backlog-20260930` still exist locally.

---

*Dashboard maintained by dev-flow commands. Each contributor updates only their own row context (e.g., adds itself to Contributors when joining a task). Hygiene: keep under ~80 lines; any contributor may rebuild it from `tasks/*.md` when in doubt. See `phases/status.md` (dev-flow skill).*
