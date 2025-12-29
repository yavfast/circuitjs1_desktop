# Multi-task Rules

`ai_memory/active_context.md` must have exactly **one** primary Current Task.

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

## Secondary tasks

Max 3 secondary tasks allowed as a short list:

```yaml
secondary_tasks:
  - task_id: TASK-ID
    goal: "One sentence"
    status: queued | paused | blocked
    priority: high | medium | low
    next: "Next step"
    blocked_by: "<reason>" # if status=blocked
```

### Rules

- If >3 secondary tasks: archive lowest-priority to `ai_memory/context_history/`.
- Status transitions: `queued` → `paused`/`blocked`; any → Current Task requires full switch.

### Promotion

To promote secondary → primary: use switch protocol from `switching.md`.
