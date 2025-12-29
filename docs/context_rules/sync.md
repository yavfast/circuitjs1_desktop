# Sync Flow

When and how to update `ai_memory/active_context.md`.

## Sync triggers

**Always sync after:**

- Creating, deleting, or renaming files
- Batch editing (3+ files in one task)
- Changing documentation structure
- Significant plan/scope change
- Resolving or discovering blockers
- Making a decision that affects future work
- Before finishing response if files changed or conclusions made

**Also sync after:**

- Clarifying requirements that change goal/scope
- Identifying new guardrails or risk hotspots
- Productive search/navigation worth caching
- Completing a milestone or subtask

**Rule of thumb:** if a new chat might start "from zero", sync now.

## How to sync

1. Update `Meta.last_updated`
2. Verify `Current Task.goal` matches actual goal
3. Update `Plan & References`:
   - Add/remove artifact references
   - Add key files touched
4. Update `Progress`:
   - Move finished → `done`
   - Reflect current work in `in_progress`
   - Write actionable `next`
5. Update `Breadcrumbs` (if present)
6. Update `Guardrails` (if present)
7. Add `Decisions` entries if any made
8. Add uncertainty to `Open Questions / Blockers`
9. Update optional sections (Failure Context, Git State, etc.)

## Principles

- Necessary sufficiency over full detail
- No duplication
- Precise wording; don't hide uncertainty
- Stable `task_id`
