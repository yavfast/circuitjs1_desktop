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

## Hard rules (strict)

### Active context is incremental

Edits to `ai_memory/active_context.md` are **incremental**: the file is only **supplemented** with new facts/progress or **corrected** for accuracy/clarity within the *same* task.

Allowed during sync:
- Update `Meta.last_updated`
- Clarify/adjust `goal` wording without changing task intent
- Add/adjust `current_focus`, `active_files`, `scratchpad`
- Move items between `done` / `in_progress` / `next`

Forbidden during sync:
- Replacing `Current Task` with an unrelated task
- Changing `Current Task.task_id`
- “Resetting” the file to a new template while losing prior task state

### Significant task change requires switching

If the intended task changes materially (new topic/subsystem, new goal/category, or you need a different `task_id`), you MUST follow the switching protocol in `switching.md` (sync → archive → registry → restore/bootstrap). Do **not** overwrite the active context directly.
