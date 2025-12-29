# Active Context Template

This file defines the required structure of `ai_memory/active_context.md`.

## Purpose

`ai_memory/active_context.md` must be minimal but sufficient to resume work in a new chat without guessing.

## Mandatory sections (order is required)

1) **Meta**
   - `last_updated`: ISO-8601 datetime (UTC or with timezone)
   - `project_root`: repository root path
   - `language`: `uk` (default)
   - `active_skills`: list of critical skill IDs (optional)
   - `agent_notes`: 1–3 lines about environment specifics (optional)

2) **Current Task**
   - `task_id`: short stable identifier
   - `goal`: clear goal (1–2 sentences)
   - `current_focus`: specific files/symbols being worked on (optional)
   - `active_files`: recently edited/open files (optional)
   - `scratchpad`: short intermediate state (optional)
   - `scope_in`: what is included
   - `scope_out`: what is excluded

3) **Plan & References**
   - `plan`: reference to plan (file or `manage_todo_list`) + short status
   - `related_docs`: relevant docs files
   - `related_code`: relevant code paths
   - `session_history`: links to session history files (see `session_history.md`)

4) **Progress**
   - `done`: 2–6 completed items
   - `in_progress`: 1–4 items in progress
   - `next`: 1–6 concrete next steps

## Recommended sections (use for non-trivial tasks)

5) **Breadcrumbs**
   - last focus, last edit targets, next hops (with “why”), navigation queries, resume recipe

6) **Guardrails**
   - invariants, scope guardrails, risk hotspots, temporary allowances, definition of done

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

## Content principles

- Prefer *state* over history
- Avoid duplication: logs/diffs → session history
- Unsure → record as Open Question / Blocker

## Key constraints

- Exactly **one** Current Task at a time
- Quick Resume block **must** be at the end
- `last_updated` must be ISO-8601 with timezone

