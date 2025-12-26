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

## Cross-reference checklist (before modifying rules)

When changing definitions, thresholds, or schemas, verify consistency across modules:

| Change type | Files to check |
|-------------|----------------|
| Status enum | `definitions.md`, `switching.md`, `multi_task.md`, `session_history.md` |
| Staleness thresholds | `definitions.md`, `context_rules.md`, `staleness.md` |
| Good match rule | `definitions.md`, `context_rules.md`, `switching.md` |
| Registry schema | `definitions.md`, `switching.md`, `contexts_index.yaml` |
| Active context structure | `definitions.md`, `active_context_template.md`, `sync.md` |
| Priority values | `definitions.md`, `multi_task.md` |
| Category values | `definitions.md`, `switching.md` |

**Procedure:**

1. Make change in `definitions.md` first (single source of truth).
2. Update `context_rules.md` if pseudocode references the changed value.
3. Update narrative modules that duplicate or elaborate the rule.
4. Bump `schema_version` in `contexts_index.yaml` if registry schema changed.
5. Add a Decisions entry in `active_context.md` documenting the change.
