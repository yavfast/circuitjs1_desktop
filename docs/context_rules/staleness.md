# Staleness Detection and Refresh on Load

> **Canonical thresholds:** See `definitions.md` for `STALENESS_WARN_DAYS`, `STALENESS_FULL_REFRESH`, `COMMIT_DRIFT_THRESHOLD`.

## When to check

Check freshness:

- At the start of every new chat when loading `ai_memory/active_context.md`.
- When restoring an archived context.

## Staleness indicators

A context is stale if any are true (thresholds from `definitions.md`):

1) `last_updated` age > `STALENESS_WARN_DAYS` (7) → warn; verify key files exist.
2) `last_updated` age > `STALENESS_FULL_REFRESH` (30) → require full refresh before proceeding.
3) Any `active_files` missing → update list; record in Decisions.
4) Branch mismatch (branch missing/different) → ask user or switch; record in Decisions.
5) `repo_revision` drift > `COMMIT_DRIFT_THRESHOLD` (50) commits behind current HEAD → warn; check for conflicts with Progress.

## Refresh protocol

1) Record staleness (Decisions or Blockers depending on severity).
2) Verify existence of `active_files` and Quick Resume `key_files`.
3) Check git state: branch, uncommitted changes, distance from archived revision.
4) Refresh stale sections:
   - update active_files
   - update git state snapshot if present
   - clear/mark old failure context as “needs verification”
   - review in-progress items
5) Bump `last_updated`.
6) Add Decisions entry describing what was verified/updated.

## Lightweight vs full refresh

- Lightweight: staleness < `STALENESS_FULL_REFRESH` days and no missing files → update timestamp, verify files, proceed.
- Full: staleness ≥ `STALENESS_FULL_REFRESH` days or critical files missing → re-scan related code, rebuild Progress/Breadcrumbs, optionally ask for confirmation before major work.
