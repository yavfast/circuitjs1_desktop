# Switching Flow

Protocol for switching between task contexts.

## Artifacts

- Active context: `ai_memory/active_context.md`
- Archives: `ai_memory/context_history/<task_id>.md`
- Registry: `ai_memory/context_history/contexts_index.yaml`

## Category values

- `refactor` — code restructuring
- `feature` — new functionality
- `bugfix` — bug fixes
- `docs` — documentation
- `infra` — build/CI/tooling
- `research` — investigation
- `test` — test coverage
- `migration` — data/code migration

## Archive status values

- `paused` — suspended, can resume
- `completed` — finished, for reference

## Good match rule

When searching for context to restore:

1. `task_id` exact match → good match
2. `category` matches AND ≥2 overlapping `tags` → good match
3. Otherwise → bootstrap new context

## When to switch

- User starts new task/topic/category
- New goal contradicts current task
- Moving to unrelated subsystem

If unsure: ask, or create new context and mark previous as `paused`.

## Switch protocol

1. **Sync** active context (see `sync.md`)
2. **Archive** current context:
   - Copy to `ai_memory/context_history/<task_id>.md`
   - Add Archive Summary and Quick Resume block
   - Set status: `paused` or `completed`
3. **Register** in `contexts_index.yaml`
4. **Find match** by `task_id`, `category`, `tags`
5. **Restore or bootstrap**:
   - Match found: restore, switch branch, do drift checks
   - No match: bootstrap from template
6. **Record** in Decisions

## Registry schema

Required fields:
- `task_id`, `title`, `category`, `tags`, `status`, `archived_at`, `file`, `summary`

Recommended:
- `related_paths`, `restore_recipe`

Optional:
- `confidence`, `last_restored_at`, `repo_revision`, `git_branch`

## Archive naming

- Canonical: `<task_id>.md` (one per task)
- Milestone (rare): `YYYY-MM-DD__<task_id>__<slug>.md`

## Bootstrapping

Reuse only stable info:
- Environment snapshot
- Project-wide guardrails
- Shared doc links

Do NOT copy: progress, breadcrumbs, failure logs.

## After restore

- Work in `ai_memory/active_context.md`
- On switch: overwrite canonical archive
- Update registry: `archived_at`, `summary`, `status`

## Manual commands

See `manual_commands.md` for user-triggered context operations.
