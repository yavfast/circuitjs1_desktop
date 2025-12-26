# Multi-task Rules (Primary + Secondary Tasks)

`ai_memory/active_context.md` must represent **one** primary Current Task.

> **Canonical definitions:** See `definitions.md` for status enums and priority values.

## Secondary tasks

Additional tasks are allowed only as a short “Secondary Tasks” list.

### Format (mandatory)

```yaml
secondary_tasks:
  - task_id: TASK-ID-HERE
    goal: "One sentence describing the task"
    status: queued | paused | blocked
    priority: high | medium | low
    next: "1-2 immediate next steps"
    blocked_by: "<reason or task_id>" # only if status=blocked
```

### Rules

- Maximum 3 secondary tasks.
  - If more are needed: archive the lowest-priority task to `ai_memory/context_history/` and register it in `contexts_index.yaml` with `status: paused`.
- Priority values (see `definitions.md`):
  - `high`: likely to become Current Task soon
  - `medium`: important but not urgent
  - `low`: backlog; can be archived first
- Status transitions (see `definitions.md` for full enum):
  - `queued` → `paused`: work started but interrupted
  - `queued` → `blocked`: dependency prevents progress
  - any → Current Task: requires full switch protocol

### Promotion to Current Task

When a secondary task becomes the focus, use the full switch protocol described in `switching.md`.
