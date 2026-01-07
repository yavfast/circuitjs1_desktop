# Active Context Template

This file defines the required structure of `ai_memory/active_context.md`.

## Purpose

`ai_memory/active_context.md` must be sufficient to resume work in a new chat without guessing, while avoiding redundancy.

## Mandatory sections (order is required)

1) **Meta**
   - `last_updated`: ISO-8601 datetime (UTC or with timezone)
   - `project_root`: repository root path
   - `language`: `uk` (default)
   - `active_skills`: list of critical skill IDs (optional)
   - `agent_notes`: short notes about environment specifics (optional)

2) **Current Task**
   - `task_id`: short stable identifier
   - `goal`: clear goal (1–2 sentences)
   - `global_context`: short global framing of the task (why it exists / how it fits into the larger project)
   - `current_focus`: specific files/symbols being worked on (optional)
   - `active_files`: recently edited/open files (optional)
   - `scratchpad`: short intermediate state (optional)
   - `scope_in`: what is included
   - `scope_out`: what is excluded

2b) **Other Tasks (This Chat)** (optional)

If the current chat contains multiple tasks that you may switch between, add this section.
Keep it concise, but structure each task similarly to “Current Task”.

Recommended structured list:

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

If this section exists, group other sections by `task_id` where practical (Plan/Progress/etc.),
so different tasks don’t get mixed together.

3) **Plan & References**
   - `plan`: reference to plan (file or `manage_todo_list`) + short status
   - `related_docs`: relevant docs files
   - `related_code`: relevant code paths
   - `session_history`: links to session history files (see `session_history.md`)

4) **Progress**
   - `done`: completed items (keep concise; include what matters for resuming)
   - `in_progress`: items currently being worked on
   - `next`: concrete next steps (actionable)

## Recommended sections (use for non-trivial tasks)

5) **Breadcrumbs**
   - last focus, last edit targets, next hops (with “why”), navigation queries, resume recipe

6) **Guardrails**
   - invariants, scope guardrails, risk hotspots, temporary allowances, definition of done

7) **Long-term Memory Candidates**
   - `facts_to_save`: new stable facts discovered during this task that are worth keeping long-term
   - `episodes_to_ingest`: new episodes (what happened + outcome + where) to ingest into long-term memory
   - Keep this section focused; prefer concrete, reusable knowledge over chat history

## Optional sections

Use only when relevant (decisions, blockers, verification, environment snapshot, failure context, search cache, git state, etc.).

## Quick Resume Block (mandatory, always at end)

Keep a copy-paste-ready YAML block at the end of the file:

```yaml
# Quick Resume — <task_id>
goal: <one sentence>
focus_now: <what exactly we are doing>
next_action: <concrete next step>
key_files: [<3-5 most relevant files>]
verify_cmd: <command to verify current state>
last_result: success | failure | pending
```

If multiple tasks exist in the active context, you may include multiple Quick Resume blocks,
one per `task_id`, as long as they are all kept at the end of the file.

## Content principles

- Prefer *state* over history
- Avoid duplication: logs/diffs → session history
- Unsure → record as Open Question / Blocker

## Key constraints

- Exactly **one** Current Task at a time
- `Current Task.task_id` is **stable** during normal sync. If it needs to change, use the switching protocol in `switching.md` (archive + registry + restore/bootstrap).
- Quick Resume block **must** be at the end
- `last_updated` must be ISO-8601 with timezone

