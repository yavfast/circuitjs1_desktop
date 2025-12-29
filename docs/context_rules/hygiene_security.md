# Hygiene, Artifact References, Security, Checklist

## Keep active context clean

Goal: `ai_memory/active_context.md` is “state as of now”, not a journal.

When a task is completed:

1) Record a short summary (what/where/how verified).
2) Remove unnecessary details from active context.
3) Move long historical notes to a history file (e.g. session history) and reference it.

## Artifact references

- Use relative paths within the repo.
- If plan/spec is a file, list it in `Plan & References`.
- If plan is in `manage_todo_list`, record `plan: manage_todo_list` + short status.
- If context depends on a command/script, add it under Verification.

## Security and privacy

- Never store secrets/tokens/passwords/private keys/personal data in active context.
- Don’t paste huge logs/dumps into active context; put them into a separate file and reference it.

## Pre-finish checklist

Before finishing substantial work:

- `ai_memory/active_context.md` updated; `last_updated` current.
- `goal/scope` matches reality.
- Key plan files/artifacts listed.
- `Progress.next` is concrete.
- Breadcrumbs/Guardrails updated if used.
- New decisions and blockers recorded.
- Failures captured in Failure Context.
- If significant navigation/search: add a minimal search cache.
- Git state recorded when relevant.

## Cross-reference checklist

When changing definitions, verify consistency:

- **Status enum**: `multi_task.md`, `switching.md`, `session_history.md`
- **Staleness thresholds**: `staleness.md`, `context_rules.md`
- **Good match rule**: `switching.md`, `context_rules.md`
- **Registry schema**: `switching.md`, `contexts_index.yaml`
- **Active context structure**: `active_context_template.md`, `sync.md`
- **Priority values**: `multi_task.md`
- **Category values**: `switching.md`

Procedure:

1. Update the module that owns the definition.
2. Check `context_rules.md` if pseudocode references the value.
3. Bump `schema_version` in `contexts_index.yaml` if schema changed.
4. Add Decisions entry in `active_context.md`.

