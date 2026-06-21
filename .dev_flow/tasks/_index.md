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

_(none active)_

## Recently Completed

| Task | Phase | Status | Contributors | Updated |
|---|---|---|---|---|
| [code-audit-defects](task_20260621_104235_code-audit-defects.md) | audit (code) | done | main | 2026-06-21 |
