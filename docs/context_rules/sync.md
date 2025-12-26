# Sync Flow (when/how to update active context)

This file defines when and how to synchronize `ai_memory/active_context.md`.

## When to sync

Synchronize `ai_memory/active_context.md`:

- After any significant plan change.
- After repository changes (create/edit/delete files) that relate to the task.
- After discovering or resolving blockers.
- After clarifying requirements that change goal/scope.
- After identifying new guardrails/risk hotspots/DoD.
- After productive navigation/search worth caching.
- Before finishing a response if you made decisions, changed files, or gained important conclusions.

Minimum rule: if a new chat might start “from zero”, sync now.

## How to sync (algorithm)

1) Update `Meta.last_updated`.
2) Verify `Current Task.goal` matches the actual goal.
3) Update `Plan & References`:
   - add/remove plan and artifact references
   - add key files touched
   - keep session history links current
4) Update `Progress`:
   - move finished items to `done`
   - reflect real current work in `in_progress`
   - write actionable `next`
5) Update `Breadcrumbs` (if present):
   - keep navigation queries and resume recipe current
6) Update `Guardrails` (if present):
   - add invariants/constraints
   - remove expired temporary allowances
7) Add entries to `Decisions` if any were made.
8) Add uncertainty to `Open Questions / Blockers`.
9) Update optional sections when applicable (Environment Snapshot, Failure Context, Search Cache, Git State, Risk Register, etc.).

## Principles

- Necessary sufficiency over full detail.
- No duplication.
- Precise wording; don’t hide uncertainty.
- Stable `task_id`.
