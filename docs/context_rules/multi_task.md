# Multi-task Rules

`ai_memory/active_context.md` must have exactly **one** primary **Current Task**.

Additionally, `ai_memory/active_context.md` MAY include **other tasks from the same chat**.
This is useful when the conversation naturally branches into 2–3 tasks and you need fast switching
without losing state.

## Status values

- `not-started` — task defined but work not begun
- `in-progress` — actively being worked on
- `paused` — work interrupted, can resume
- `blocked` — cannot proceed (dependency/blocker)
- `completed` — finished successfully

## Priority values

- `high` — likely to become Current Task soon
- `medium` — important but not urgent
- `low` — backlog; archive first if needed

## Other tasks (same chat)

Recommended: keep **1–3** other tasks as a short list. If there are more, archive the lowest-value
ones first (see `switching.md`).

```yaml
other_tasks:
  - task_id: TASK-ID
    goal: "One sentence"
    status: not-started | in-progress | paused | blocked | completed
    priority: high | medium | low
    current_focus: "What you were doing last" # optional
    active_files: [path1, path2]               # optional
    next: "Next step"                         # optional
    blocked_by: "<reason>" # if status=blocked
```

### Rules

- **Other Tasks section is mandatory if other_tasks exists.** Use a dedicated section
  (e.g. “Other Tasks (This Chat)”) with a structure similar to “Current Task”.
- If other tasks exist, sections like **Plan & References**, **Progress**, **Breadcrumbs**,
  **Guardrails**, **Long-term Memory Candidates**, and **Quick Resume** should be grouped by `task_id`
  to avoid mixing unrelated state.
- Keep other tasks concise: enough to resume, not a full transcript.

### Promotion

To switch focus between tasks *within the same chat*:

- Promote an item from `other_tasks` to **Current Task** by swapping:
  - move the old Current Task into `other_tasks` (preserving its state)
  - update `status` fields appropriately
  - update all grouped sections so each task’s info stays under its own `task_id`

This is **not** the same as the cross-chat switching protocol.

To switch to a task that is NOT already captured in `ai_memory/active_context.md`,
or when starting a new chat with an unrelated goal, use `switching.md` (archive + registry).
