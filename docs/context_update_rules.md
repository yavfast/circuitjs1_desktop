<!--
PURPOSE
  This document defines deterministic rules for any AI agent regarding:
  - saving the “active context” of current tasks,
  - updating/synchronizing that context during work,
  - maintaining references to plans and related artifacts.

SCOPE
  These rules apply to all chat sessions in this repository.
-->

# Rules for saving and updating active task context

## 1) Definitions

- **Active context** — the minimal but sufficient task state that allows another AI to resume work in a new chat without guessing.
- **Current task** — the primary task the agent is working on in this chat right now.
- **Task plan** — a separate artifact (a file in `ai_memory/` or `docs/`, or a plan in `manage_todo_list`) that contains execution steps.
- **Context synchronization** — bringing `ai_memory/active_context.md` into alignment with the actual state of the current chat.
- **Breadcrumbs** — short “resume hints” that make it possible to jump back into the work quickly (what was last touched, what to search for, and where to go next). Breadcrumbs should survive small repo drift between sessions.
- **Guardrails** — explicit constraints that must not be violated while working (invariants, forbidden changes, risk hotspots, temporary allowances, and definition of done).

## 2) Sources of truth and files

### 2.1. Where active context is stored

- Active context is stored in `ai_memory/active_context.md`.
- `ai_memory/active_context.md` is the **only** “startup” file that must be sufficient to resume work.

### 2.2. Where plans are stored

- A task plan may be stored:
  - in files under `ai_memory/` (e.g., `ai_memory/*_plan.md`, `ai_memory/*_ctx.md`), or
  - in files under `docs/` (if the plan/spec is part of documentation), or
  - in the internal plan of the `manage_todo_list` tool.

**Mandatory:** references/pointers to all relevant plan files or artifacts must be listed in `ai_memory/active_context.md`.

## 3) New chat startup rules

1) At the start of every new chat, the agent **must always** load and follow `ai_memory/active_context.md` without additional reminders.
2) If `ai_memory/active_context.md` is empty or stale, the agent must:
	- record this as a risk/blocker in the chat,
	- reconstruct context from artifacts (if known),
	- and update `ai_memory/active_context.md` to the current state.

## 4) Minimal active context structure (mandatory template)

`ai_memory/active_context.md` **must** contain the following sections in the given order (so other AIs can parse/validate unambiguously):

1) **Meta**
	- `last_updated`: ISO-8601 datetime (UTC or with timezone)
	- `project_root`: repository root path
	- `language`: `uk` (default)
	- `active_skills`: list of critical skill IDs (optional)
	- `agent_notes`: 1–3 lines about environment specifics (optional)

2) **Current Task**
	- `task_id`: short stable identifier (e.g. `CTX-RULES-001`)
	- `goal`: clear goal (1–2 sentences)
	- `current_focus`: specific files, methods, or lines being worked on (optional)
	- `active_files`: list of files currently open or recently edited (optional)
	- `scratchpad`: last immediate thought or intermediate state (optional)
	- `scope_in`: what is included
	- `scope_out`: what is explicitly excluded

3) **Plan & References**
	- `plan`: reference to a plan (file or “manage_todo_list”) + short status
	- `related_docs`: list of files (if any)
	- `related_code`: list of files/folders (if code changes exist)	- `session_history`: direct link(s) to session history file(s) relevant to the current task (see §12)
4) **Progress**
	- `done`: 2–6 bullets completed
	- `in_progress`: 1–4 bullets in progress
	- `next`: 1–6 next steps (must be actionable)

5) **Breadcrumbs** (optional, recommended)
	- `last_focus`: 1–2 sentences (what you were doing when you paused)
	- `last_edit_targets`: short list of key symbols/files recently touched
	- `next_hops`: shortlist of “where to go next” (each item should say *why*)
	- `navigation_queries`: 5–10 search patterns/keywords/commands that quickly re-locate relevant code
	- `resume_recipe`: 3–6 minimal steps to regain momentum in a new session

6) **Guardrails** (optional, recommended)
	- `invariants`: 3–8 “must hold” statements (optionally with how to validate)
	- `scope_guardrails`: explicit “do not change” constraints (to prevent scope creep)
	- `risk_hotspots`: areas most likely to regress + expected failure mode
	- `temporary_allowances`: time-boxed exceptions (what, why, until when)
	- `definition_of_done`: 2–5 crisp exit criteria

7) **Decisions**
	- short decision log: (datetime → decision → reason)

8) **Open Questions / Blockers**
	- questions that require clarification
	- blockers with a concrete reason

9) **Verification** (optional)
	- `last_green_build`: timestamp/commit of last successful build
	- `failing_tests`: list of currently failing tests (if any)
	- verification commands/steps: `npm`, `mvn`, scripts, tests

10) **Deferred / Parking Lot** (optional)
	- Ideas or tasks found but postponed to avoid scope creep.

11) **External Dependencies** (optional)
	- `waiting_for`: clarifications or decisions from user/team
	- `blocked_by`: external factors (PRs, releases, other tasks)
	- `depends_on`: related tasks/PRs that must be completed first

12) **User Preferences** (optional)
	- `communication_language`: preferred language for responses
	- `commit_style`: conventional commits, squash, etc.
	- `code_review_required`: yes/no
	- `auto_format`: formatting preferences

13) **Lessons Learned** (optional)
	- Useful patterns discovered during this task
	- Pitfalls to avoid (with brief explanation)
	- Tips for similar future work

14) **Environment Snapshot** (optional)
	- `os`: operating system (Linux / macOS / Windows)
	- `shell`: bash / zsh / powershell
	- `runtime_versions`: node, java, gwt, nwjs versions
	- `last_successful_run`: timestamp of last successful build/run
	- `known_env_issues`: environment-specific quirks or workarounds

15) **Failure Context** (optional)
	- `last_failure_time`: when the last failure occurred
	- `failure_type`: compile | runtime | test
	- `error_signature`: key error message or pattern
	- `failing_files`: files/lines where the error manifests
	- `attempted_fixes`: what was tried and the outcome
	- `resolution_status`: resolved | pending

16) **Search & Navigation Cache** (optional)
	- `recent_greps`: useful grep patterns with result counts and sample files
	- `recent_semantic_searches`: queries and top hits
	- `useful_file_paths`: frequently accessed files for quick reference

17) **Code Patterns Registry** (optional)
	- `migration_pattern`: name, before/after code, applied_in, pending_in
	- Used for consistent refactoring across files

18) **Conversation Anchors** (optional)
	- `key_user_statements`: important user instructions that influence work style
	- `implicit_preferences`: observed preferences (incremental changes, verification frequency)
	- `last_user_intent`: the most recent user goal in plain language

19) **Git State Snapshot** (optional)
	- `current_branch`: active branch name
	- `base_branch`: branch to merge into
	- `uncommitted_changes`: count of modified files
	- `last_commit`: hash and message
	- `stash_entries`: list of stashed work
	- `merge_conflicts`: none | list of conflicting files

20) **Tool Usage History** (optional)
	- `frequently_used_commands`: critical commands for this task
	- `last_terminal_sessions`: recent commands with exit codes
	- `preferred_tools`: which tools work best for which purposes

21) **Mental Model State** (optional)
	- `architecture_understanding`: high-level description of relevant architecture
	- `key_invariants`: rules that must always hold
	- `gotchas`: non-obvious behaviors or edge cases discovered

22) **Time & Effort Tracking** (optional)
	- `task_started`: date when work began
	- `estimated_effort`: expected sessions/hours
	- `sessions_spent`: actual sessions completed
	- `time_per_phase`: breakdown by task phase
	- `velocity_notes`: observations on work speed

23) **Risk Register** (optional)
	- `high`: critical risks with mitigation strategies and status
	- `medium`: moderate risks worth monitoring
	- `low`: minor concerns for awareness

The content must be **necessarily sufficient** to resume work:

- Prefer *state* over history: record “what is true now” and “what to do next”.
- Avoid duplication: long logs, diffs, or exhaustive file lists should go into a session history file (see §12) or a dedicated artifact and be referenced.
- If in doubt, keep the active context readable and actionable: someone should be able to execute `next` and use `Breadcrumbs` without guessing.

## 5) When to update active context

The agent **must** synchronize `ai_memory/active_context.md`:

- After any significant plan change (added/removed steps, changed priorities).
- After any repository changes (create/edit/delete files) if they relate to the task.
- After discovering or resolving blockers.
- After clarifying requirements that change the “goal/scope”.
- After you identify new Guardrails (invariants, forbidden changes, temporary allowances, DoD).
- After you perform navigation/search work that you would want to repeat quickly (update Breadcrumbs).
- Before finishing a response, if you made a decision, changed files, or gained new important conclusions.

Minimum rule: **if a new chat might start “from zero”, update the context now.**

## 6) How to update active context (algorithm)

During synchronization:

1) Update `last_updated`.
2) Verify that `Current Task.goal` matches the actual goal of the chat.
3) Update `Plan & References`:
	- add/remove references to plan files,
	- add key files that were touched.
4) Update `Progress`:
	- move finished items to `done`,
	- reflect current state in `in_progress`,
	- write real next steps in `next`.
5) Update `Breadcrumbs` (if present):
	- keep `navigation_queries` and `resume_recipe` current,
	- update `next_hops` when priorities change.
6) Update `Guardrails` (if present):
	- add new invariants/constraints,
	- remove temporary allowances that are no longer needed.
7) If a new decision was made, add an entry to `Decisions`.
8) If there is uncertainty, add it to `Open Questions / Blockers`.
9) Update optional context sections as needed:
	- `Environment Snapshot`: after environment changes or version updates,
	- `Failure Context`: after encountering or resolving errors,
	- `Search Cache`: after productive search sessions,
	- `Code Patterns`: when establishing or applying refactoring patterns,
	- `Git State`: after commits, branch switches, or stash operations,
	- `Mental Model`: when gaining new architectural insights or discovering gotchas,
	- `Risk Register`: when identifying new risks or updating mitigation status.

Principles:

- **Necessary sufficiency over full detail.** Keep enough to resume confidently; move verbose details (logs, long file lists, code snippets) into separate files and reference them.
- **No duplication.** One fact belongs in one place (either in the context or in a referenced artifact).
- **Precise wording.** Avoid “somewhere/maybe/probably”; if unsure, it is an “Open Question”.
- **Stable identifiers.** Do not change `task_id` for minor clarifications.

## 7) Rules for multiple tasks at once

- `ai_memory/active_context.md` must have **one** primary “Current Task”.
- Additional tasks are allowed only as “Secondary Tasks” (a short list with `task_id`, `goal`, `status`, `next`).
- If focus changes, the agent must:
  - finish synchronizing the old task,
  - switch “Current Task” to the new one,
  - record the reason in `Decisions`.

## 8) Archiving and keeping context clean

- When a task is completed, the agent must:
  1) record a short summary (what was done, where, how it was verified),
  2) remove unnecessary details from active context,
  3) move long historical notes to `ai_memory/memory.md` (or another explicitly specified history file) and add a reference.

Goal: `ai_memory/active_context.md` must not become a journal; it is the “state as of now”.

## 9) Mandatory rules for artifact references

- Use **relative paths** within the repository.
- If the plan/spec is a file, it must be explicitly listed in `Plan & References`.
- If the plan is maintained via `manage_todo_list`, `Plan & References.plan` must say `manage_todo_list` + a short status.
- If the context depends on a specific command/script, add it under `Verification`.

## 10) Security and privacy

- Do not store secrets, tokens, passwords, private keys, or personal data in `active_context.md`.
- Do not paste large logs or dumps; save them into a separate file (if needed) and reference it.

## 11) Quality check before finishing a response (short checklist)

Before finishing substantial work, the agent checks:

- `ai_memory/active_context.md` is updated and `last_updated` is current.
- `goal/scope` do not contradict the real state.
- All important plan files and artifacts are listed in `Plan & References`.
- `next` contains concrete actionable steps.
- If used, `Breadcrumbs` and `Guardrails` are up to date and still reflect the intended constraints.
- New decisions and blockers are not lost.
- If a failure occurred, `Failure Context` captures the error and attempted fixes.
- If significant search/navigation was done, consider caching useful patterns in `Search Cache`.
- If new risks were identified, they are recorded in `Risk Register`.
- `Git State` reflects current branch and uncommitted changes (for multi-session work).

## 12) Session History Files

To preserve detailed session context without bloating `active_context.md`, the agent **should** maintain session history files.

### 12.1. Storage location

- Session history files are stored in `ai_memory/session_history/`.
- One file per session, named: `session_YYYY-MM-DD_NNN.md` (NNN = sequence number for that day, e.g., `session_2025-12-25_001.md`).

### 12.2. When to create and update a session file

**Creation:**
- At the **start** of a new chat session that involves non-trivial work.
- Skip for purely informational/Q&A sessions that don't change code or plans.

**Updates (incremental):**
- After **each request** that results in meaningful changes (code edits, plan updates, decisions).
- When **completing a task or milestone** — add a summary of accomplished work.
- The concept of "session end" is non-deterministic; instead, update incrementally as work progresses.

### 12.3. Session file structure (template)

The session file uses **YAML frontmatter** for machine-parseable metadata and **Markdown body** for human-readable details.

````markdown
---
started: 2025-12-25T16:00:00+02:00
last_updated: 2025-12-25T17:30:00+02:00
task_id: TASK-ID-HERE
status: in-progress | completed
files_changed: 0
commits: 0
---

# Session YYYY-MM-DD_NNN

## Summary

Brief overall summary of what was accomplished in this session (1-3 sentences).
Update incrementally as work progresses.

## Changelog

Chronological log of changes, grouped by time or milestone.

### HH:MM — Milestone/Action title
- **Changed**: `path/to/file.java` — brief description
- **Commit**: `abc1234` — commit message (if any)
- **Note**: additional context if needed

### HH:MM — Another action
- **Changed**: `path/to/another.md` — what was modified
- **Error**: description → **Resolution**: how it was fixed

## Notes for Next Session

- Important context that might be lost between sessions
- Partially completed work details
- Decisions pending confirmation
````

**Field descriptions:**

| Field | Description |
|-------|-------------|
| `started` | Session start time (ISO-8601) |
| `last_updated` | Last update time (update after each meaningful change) |
| `task_id` | Reference to Current Task from active_context |
| `status` | `in-progress` or `completed` |
| `files_changed` | Count of unique files modified (for quick stats) |
| `commits` | Count of commits made (for quick stats) |

### 12.4. Rules for session history

- **Incremental updates**: After each request that produces changes, append to `Changed Files`, `Key Actions`, or `Commits` sections.
- **Task/milestone completion**: When finishing a task or significant milestone, update `summary` and add notes to `Notes for Next Session`.
- **Reference in active_context**: The `Plan & References.session_history` field must contain direct link(s) to history file(s) relevant to the current task, with a brief note that these files contain the change history for this context.
- **Retention**: Keep last 10-15 session files; older files can be archived or summarized into `ai_memory/memory.md`.
- **Privacy**: Same rules as §10 — no secrets, tokens, or personal data.

