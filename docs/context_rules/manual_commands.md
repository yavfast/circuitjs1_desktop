```markdown
# Manual Context Commands

User-triggered commands for explicit context management.

> See `context_rules.md` for core workflow pseudocode.

## Available Commands

- **List** — "list contexts", "show contexts" — Show all archived contexts
- **Switch** — "switch to #N", "switch to \<task_id\>" — Switch to specific context
- **Create** — "new context \<name\>", "create task \<name\>" — Create new context
- **Archive** — "archive context", "save and close" — Archive current without switching

## LIST_CONTEXTS

```text
procedure LIST_CONTEXTS():
   registry = READ_YAML(CONTEXT_REGISTRY_FILE)
   current_ctx = READ_CONTEXT(ACTIVE_CONTEXT_FILE)
   
   output = "## Available Contexts\n\n"
   output += "**Current:** `" + current_ctx.current_task.task_id + "` — "
   output += current_ctx.current_task.goal + " *(" + current_ctx.current_task.status + ")*\n\n"
   output += "**Archived:**\n\n"
   
   for i, entry in enumerate(registry.contexts):
      # Format: N. `task_id` — title
      #         - status: X | category: Y
      #         - archived: YYYY-MM-DD
      #         - summary: ...
      output += FORMAT_HIERARCHICAL_ENTRY(i+1, entry)
   
   output += "---\nTo switch: 'switch to #N' or 'switch to <task_id>'"
   return output
```

## SWITCH_TO_CONTEXT

```text
procedure SWITCH_TO_CONTEXT(target):
   registry = READ_YAML(CONTEXT_REGISTRY_FILE)
   
   # Resolve: #N → index, else → task_id
   if target starts with "#":
      index = PARSE_INT(target[1:]) - 1
      if index < 0 OR index >= LENGTH(registry.contexts):
         return ERROR("Invalid index.")
      candidate = registry.contexts[index]
   else:
      candidate = FIND_BY_TASK_ID(registry, target)
      if candidate is null:
         return ERROR("Context not found.")
   
   ctx = READ_CONTEXT(ACTIVE_CONTEXT_FILE)
   ctx = DO_SWITCHING(ctx, { task_id: candidate.task_id, explicit: true })
   WRITE_CONTEXT(ACTIVE_CONTEXT_FILE, ctx)
   
   return "Switched to: " + candidate.title
```

## CREATE_NEW_CONTEXT

```text
procedure CREATE_NEW_CONTEXT(name, goal):
   ctx = READ_CONTEXT(ACTIVE_CONTEXT_FILE)
   ctx = SYNC_CONTEXT(ctx, { reason: "pre-create sync" })
   ARCHIVE_AND_REGISTER(ctx)
   
   new_ctx = BOOTSTRAP_NEW_CONTEXT({ name: name, goal: goal })
   new_ctx.current_task.task_id = GENERATE_TASK_ID(name)
   new_ctx.meta.last_updated = NOW_ISO8601()
   WRITE_CONTEXT(ACTIVE_CONTEXT_FILE, new_ctx)
   
   return "Created: " + new_ctx.current_task.task_id
```

## ARCHIVE_CURRENT_CONTEXT

```text
procedure ARCHIVE_CURRENT_CONTEXT():
   ctx = READ_CONTEXT(ACTIVE_CONTEXT_FILE)
   ctx = SYNC_CONTEXT(ctx, { reason: "manual archive" })
   archive_file = ARCHIVE_AND_REGISTER(ctx)
   return "Archived to: " + archive_file
```

```
