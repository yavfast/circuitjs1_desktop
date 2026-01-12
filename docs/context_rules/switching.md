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

## Strict rule: archive before replacing the task

It is **FORBIDDEN** to replace `Current Task` (or change `Current Task.task_id`) in `ai_memory/active_context.md` as part of a normal sync.

If the task changes materially, you MUST:
1) Sync the current active context (`sync.md`)
2) Archive it into `ai_memory/context_history/<task_id>.md`
3) Update the registry `ai_memory/context_history/contexts_index.yaml`
4) Only then restore/bootstrap the new task context

This prevents accidental loss of the previous task state and ensures history stays coherent.

## Multi-task active contexts (same chat)

If `ai_memory/active_context.md` contains multiple tasks (Current Task + Other Tasks from the same chat):

- Switching focus **within the same chat** can be done by promoting an “Other Task” into “Current Task” (see `multi_task.md`).
- Switching to a **new unrelated task** (typically at the start of a new chat) MUST archive **each task separately by task_id**.
   Do not collapse multiple tasks into one archive file.

## New chat: activate one task, archive the rest

On NEW chat start, if `ai_memory/active_context.md` contains multiple tasks from previous sessions and the new intent matches one of them:

- Promote the matching one to **Current Task**.
- Archive all non-matching tasks separately by `task_id` into `ai_memory/context_history/` and update `contexts_index.yaml`.
- Remove the archived task descriptions from `ai_memory/active_context.md`.

This keeps the active context focused on the user’s current request and prevents unrelated tasks from drifting forward.

## Switch protocol

1. **Sync** active context (see `sync.md`)
2. **Archive** current context:
   - If only one task exists: copy to `ai_memory/context_history/<task_id>.md`
   - If multiple tasks exist: create/update one archive per task: `ai_memory/context_history/<task_id>.md`
   - Add Archive Summary and Quick Resume block
   - Set status: `paused` or `completed`
3. **Register** in `contexts_index.yaml`
4. **Find match** by `task_id`, `category`, `tags`
5. **Restore or bootstrap**:
   - Match found: restore, switch branch, do drift checks
   - No match: bootstrap from template
6. **Record** in Decisions

## Project-wide context (optional)

If switching uncovered durable project-wide information (repo-wide architecture, new dev commands, gotchas, module boundaries), update `ai_memory/project_context.md`.
See `docs/context_rules/project_context_rules.md`.

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
