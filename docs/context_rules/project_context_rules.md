# Project Context Rules (project-wide onboarding)

This document defines the concept and rules for **project-wide context**.

## Artifact

- File: `ai_memory/project_context.md`
- Purpose: onboarding + shared understanding of the repository (architecture, modules, tooling, concepts).

## What belongs in `project_context.md`

Durable, repo-wide information:

- What the project is (1–2 paragraphs)
- High-level architecture (major modules + responsibilities)
- Tooling/build/run/debug commands and where outputs live
- Key domain concepts specific to this codebase (frameworks, runtime, data formats, lifecycle, etc.)
- “Gotchas” that repeatedly matter (different run modes, build outputs, environment quirks, etc.)
- Pointers to the canonical docs (README, `docs/`, runbooks, ADRs)

## What must NOT be in `project_context.md`

- Task-specific progress, TODOs, breadcrumbs
- Chat logs, large diffs, verbose build outputs
- Secrets/tokens/passwords/private keys

Those belong in:
- `ai_memory/active_context.md` (current task)
- `ai_memory/context_history/` (archived tasks)
- `ai_memory/session_history/` (detailed session logs)

## Lifecycle rules

### Creation (one-time)

- Create `ai_memory/project_context.md` once, based on:
  - `README.md` and docs under `docs/` (if present), and/or
  - quick codebase reconnaissance for module boundaries.

### Updates (only when needed)

Update `ai_memory/project_context.md` only when the agent learns **project-wide** information that will help future chats.

Allowed update moments:

- During **context switching** (when archiving/restoring tasks).
- During **active context sync**, if new repo-wide knowledge was discovered.

Do not update it for every small change; keep it stable and low-churn.

## Minimal structure (recommended template)

See: `docs/context_rules/project_context_template.md`.

## Quality gate

`project_context.md` is “good” if a new agent can:

- pick the right build/run command in <2 minutes,
- locate the main entrypoints/modules quickly,
- know which docs to read next.
