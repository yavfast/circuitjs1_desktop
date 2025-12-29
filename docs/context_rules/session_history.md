# Session History Files

Purpose: keep detailed per-session change history without bloating `ai_memory/active_context.md`.

## Location and naming

- Store session history files in `ai_memory/session_history/`.
- One file per session: `session_YYYY-MM-DD_NNN.md` (NNN is a per-day sequence).

## When to create

- At the start of a new chat session that involves non-trivial work.
- Skip for purely informational/Q&A sessions.

## When to update

Update incrementally:

- After each request that results in meaningful changes.
- When completing a task or milestone: add a summary + next-session notes.

## Status values

- `in-progress` — session ongoing
- `completed` — session finished

## Template

```markdown
---
started: 2025-12-25T16:00:00+02:00
last_updated: 2025-12-25T17:30:00+02:00
task_id: TASK-ID
status: in-progress | completed
files_changed: 0
commits: 0
---

# Session YYYY-MM-DD_NNN

## Summary
1–3 sentences.

## Changelog
Chronological changes.

## Notes for Next Session
What might be lost.
```

## Rules

- Reference session history file(s) from `ai_memory/active_context.md` under `Plan & References.session_history`.
- Do not delete session history files automatically.
- Same privacy rules as active context (no secrets).
