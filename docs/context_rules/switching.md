# Switching Flow (archive/register/restore/bootstrap)

This file defines a deterministic protocol for switching between task contexts.

> **Canonical definitions:** See `definitions.md` for status enums, category values, good match rule, and registry schema.

## Artifacts

- Active context: `ai_memory/active_context.md`
- Archived contexts: `ai_memory/context_history/<task_id>.md`
- Registry: `ai_memory/context_history/contexts_index.yaml`

The registry is the discoverable list of all saved contexts.

## When a switch is required

Switch when:

- The user explicitly starts a new task/topic/category.
- The new goal/scope contradicts the current Current Task.
- Work is about to move to an unrelated subsystem.

If unsure: ask one clarifying question, OR create a new context and mark the previous as `paused`.

## Switch protocol (algorithm)

When switching from old task A to new task B:

1) **Synchronize** `ai_memory/active_context.md` (see `sync.md`).
2) **Archive** context A:
   - Copy `ai_memory/active_context.md` to `ai_memory/context_history/<task_id>.md`.
   - Append an **Archive Summary** (resume-oriented) to the archived copy.
   - Ensure the archive ends with the Quick Resume block.
   - Mark status as `paused` or `completed`.
3) **Register** the archive in `contexts_index.yaml`.
4) **Locate prior context for B** by searching registry (`task_id`, `title`, `category`, `tags`).

### Deterministic “good match” rule

> Canonical rule defined in `definitions.md`. Summary:

- If `task_id` matches exactly → good match.
- Else if `category` matches AND ≥ `GOOD_MATCH_MIN_TAGS` (default: 2) overlapping `tags` → good match.
- Else → no match; bootstrap a new context.

5) **Restore or bootstrap**:
   - If good match found:
     - Restore by copying archive to `ai_memory/active_context.md`.
     - If a git branch is specified, switch to it.
     - Do drift checks (missing files, git status) and record in Decisions/Blockers.
     - Update `last_updated` and Decisions.
   - If not found:
     - Bootstrap a new `ai_memory/active_context.md` using the template.

Finally: record a Decisions entry (why switch, what archived, what restored/created).

## Archive naming

Default policy: **one canonical archive per `task_id`**.

- Canonical: `<task_id>.md`
- Optional milestone snapshot (rare): `YYYY-MM-DD__<task_id>__<slug>.md`

## Registry schema (summary)

> Full schema with required/recommended/optional fields: see `definitions.md`.

Required fields per entry: `task_id`, `title`, `category`, `tags`, `status`, `archived_at`, `file`, `summary`.

## Bootstrapping rule

When bootstrapping, reuse only stable cross-task info:

- Environment snapshot and stable commands
- Project-wide guardrails
- Links to shared docs

Do not copy task-specific progress/breadcrumbs/failure logs.

## Updating history after restoring (no stale copies)

If you restored from an archive:

- Work in `ai_memory/active_context.md`.
- When switching away or persisting progress: overwrite the canonical archive `<task_id>.md`.
- Update the registry entry: bump `archived_at`, refresh `summary`/`restore_recipe`, set `status`.

## Manual Context Commands

Users can explicitly request context operations using these commands:

### List available contexts

**Trigger phrases:** "list contexts", "show contexts", "available tasks"

**Response format:**

```markdown
## Available Contexts

**Current:** `CTX-SWITCHING-RULES-001` — Context switching rules *(in-progress)*

**Archived:**

1. `TASK-001` — Feature X
   - status: paused | category: feature
   - archived: 2025-12-25
   - summary: Short description of the task...

2. `TASK-002` — Refactor Y
   - status: completed | category: refactor
   - archived: 2025-12-20
   - summary: Short description of the task...

---
To switch: "switch to #1" or "switch to TASK-001"
```

### Switch to specific context

**Trigger phrases:** "switch to #N", "switch to <task_id>", "open context <task_id>", "restore #N"

**Algorithm:**

1. Parse target:
   - If starts with `#` → resolve index to `task_id` from registry (1-based).
   - Otherwise → use as `task_id` directly.
2. Sync & archive current context (standard switch protocol).
3. Find entry in `contexts_index.yaml` by resolved `task_id` (exact match required).
4. If not found → error: "Context not found. Use 'list contexts' to see available."
5. Restore from archive file.
6. Apply drift checks.
7. Report what was restored and current focus.

### Create new context

**Trigger phrases:** "new context <name>", "create task <name>", "start new task <name>"

**Algorithm:**

1. Sync & archive current context.
2. Bootstrap new context with provided name/goal.
3. Assign new `task_id` (auto-generated or user-provided).
4. Report new context created.

### Archive current context (without switching)

**Trigger phrases:** "archive context", "save and close", "persist context"

**Algorithm:**

1. Sync current context.
2. Archive to `<task_id>.md`.
3. Register/update in `contexts_index.yaml`.
4. Keep current context active (don't bootstrap new).
5. Report archive path.
