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
| [E_AGT](task_E_AGT.md) | verify (owed manual devmode checks) | in-progress | main | 2026-10-06 |

## Recently Completed

| Task | Phase | Status | Contributors | Updated |
|---|---|---|---|---|
| [C_SLV](task_C_SLV.md) | implement | done | main | 2026-10-06 |
| [spike-solver-defects](task_20261005_153627_spike-solver-defects.md) | fix | done | main | 2026-10-05 |
| [sparse-solver-research](task_20261005_150450_sparse-solver-research.md) | research | done | main | 2026-10-05 |
| [circuit-lang-research](task_20261002_100004_circuit-lang-research.md) | research | done | main | 2026-10-02 |
| [mcp-research](task_20261001_142742_mcp-research.md) | research | done | main | 2026-10-01 |
| [backlog-fixes-3](task_20261001_131500_backlog-fixes-3.md) | fix | done | main | 2026-10-01 |
| [backlog-fixes-2](task_20261001_113805_backlog-fixes-2.md) | fix | done | main | 2026-10-01 |
| [backlog-fixes](task_20260930_205344_backlog-fixes.md) | fix | done | main | 2026-09-30 |
| [code-audit-full](task_20260930_173830_code-audit-full.md) | audit (code) + fix | done | main | 2026-09-30 |

## Retired

Moved whole to [`../session_history/`](../session_history/) by `/dev-flow audit context` on 2026-10-06 (done, older than ~30 days): `task_20260621_104235_code-audit-defects` (defects audit, cdba7b6, cbfb7b7), `task_20260621_125942_fix-bl-drop` (JSON type-name aliases, c8d61db).
