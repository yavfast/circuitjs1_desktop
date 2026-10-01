# Tasks Index

Catalog of per-task context files. Source of truth = the task files themselves;
this index and `active_context.md` are derived views — rebuild from `tasks/*.md`
headers if they drift.

## Conventions

- **Naming:** tied to a traceable doc → `task_<DocID>.md`; otherwise
  `task_YYYYMMDD_HHMMSS_<slug>.md` (slug = 1–3-word kebab-case).
- **Ownership:** each contributor edits only their own `### Subtask:` block and
  their tagged entries in shared sections. Append, don't rewrite.
- **Retention:** fully-compacted `done` tasks older than ~30 days move to
  `.dev_flow/session_history/` and drop from the Active/Recently-Completed lists.

## Active

| Task | Phase | Status | Contributors | Updated |
|---|---|---|---|---|
| [E_AGT](task_E_AGT.md) | concept | in-progress | main | 2026-10-01 |

## Recently Completed

| Task | Phase | Status | Contributors | Updated |
|---|---|---|---|---|
| [mcp-research](task_20261001_142742_mcp-research.md) | research | done | main | 2026-10-01 |
| [backlog-fixes-3](task_20261001_131500_backlog-fixes-3.md) | fix | done | main | 2026-10-01 |
| [backlog-fixes-2](task_20261001_113805_backlog-fixes-2.md) | fix | done | main | 2026-10-01 |
| [backlog-fixes](task_20260930_205344_backlog-fixes.md) | fix | done | main | 2026-09-30 |
| [code-audit-full](task_20260930_173830_code-audit-full.md) | audit (code) + fix | done | main | 2026-09-30 |
| [fix-bl-drop](task_20260621_125942_fix-bl-drop.md) | fix | done | main | 2026-06-21 |
| [code-audit-defects](task_20260621_104235_code-audit-defects.md) | audit (code) | done | main | 2026-06-21 |
