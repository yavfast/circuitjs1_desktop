# Context Workflow (pseudocode)

Modules:
- `docs/context_rules/active_context_template.md` — active context template
- `docs/context_rules/sync.md` — sync flow
- `docs/context_rules/switching.md` — archive/restore/bootstrapping
- `docs/context_rules/multi_task.md` — parallel task rules
- `docs/context_rules/staleness.md` — staleness policies
- `docs/context_rules/manual_commands.md` — manual CLI commands
- `docs/context_rules/hygiene_security.md` — hygiene & security checks

```text
# ══ ARTIFACTS & CONSTANTS ══
ACTIVE_CONTEXT_FILE   = "ai_memory/active_context.md"
CONTEXT_REGISTRY_FILE = "ai_memory/context_history/contexts_index.yaml"
ARCHIVE_DIR           = "ai_memory/context_history/"
STALENESS_WARN_DAYS = 7;  STALENESS_FULL_REFRESH = 30;  GOOD_MATCH_MIN_TAGS = 2

# ══ CORE INVARIANTS ══
assert ACTIVE_CONTEXT_FILE exists
assert active_context.current_task is exactly one task

# ══ HELPER FUNCTIONS ══
function IS_STALE(ctx):
   age = DAYS_SINCE(ctx.meta.last_updated)
   if age > STALENESS_FULL_REFRESH: return { stale: true, severity: "full_refresh" }
   if age > STALENESS_WARN_DAYS: return { stale: true, severity: "warn" }
   if ANY_MISSING(ctx.current_task.active_files): return { stale: true, severity: "missing_files" }
   return { stale: false }

function IS_GOOD_MATCH(candidate, intent):
   if candidate.task_id == intent.task_id: return true
   if candidate.category == intent.category AND COUNT_OVERLAP(candidate.tags, intent.tags) >= GOOD_MATCH_MIN_TAGS: return true
   return false

function CHOOSE_FLOW(ctx, intent):
   if INTENT_CONTRADICTS_CURRENT_TASK(ctx, intent): return "SWITCHING"
   if intent.wants_parallel_tasks: return "MULTI_TASK"
   return "SYNC"

# ══ MAIN ENTRY POINT ══
procedure START_NEW_CHAT(user_message):
   ctx = READ_CONTEXT(ACTIVE_CONTEXT_FILE)

   staleness = IS_STALE(ctx)
   if staleness.stale:
      ctx = REFRESH_STALE_CONTEXT(ctx, staleness.severity)
      WRITE_CONTEXT(ACTIVE_CONTEXT_FILE, ctx)

   intent = INFER_USER_INTENT(user_message)
   flow = CHOOSE_FLOW(ctx, intent)

   if flow == "SWITCHING":
      ctx = DO_SWITCHING(ctx, intent)
      WRITE_CONTEXT(ACTIVE_CONTEXT_FILE, ctx)
   elif flow == "MULTI_TASK":
      ctx = APPLY_MULTI_TASK_RULES(ctx, intent)
      WRITE_CONTEXT(ACTIVE_CONTEXT_FILE, ctx)
   return ctx

# ══ SYNC (after each request) ══
procedure AFTER_EACH_USER_REQUEST(ctx, result):
   did_change = result.repo_files_changed OR result.plan_or_scope_changed OR result.decision_made
   if did_change:
      ctx = SYNC_CONTEXT(ctx, result)
      WRITE_CONTEXT(ACTIVE_CONTEXT_FILE, ctx)
   return ctx

function SYNC_CONTEXT(ctx, result):
   ctx.meta.last_updated = NOW_ISO8601()
   ctx.current_task.goal = NORMALIZE_GOAL(ctx.current_task.goal, result.user_intent)
   ctx.plan_and_references = UPDATE_REFERENCES(ctx.plan_and_references, result)
   ctx.progress = UPDATE_PROGRESS(ctx.progress, result)
   if result.decision_made: ctx.decisions.add(MAKE_DECISION_ENTRY(result))
   if result.has_blocker: ctx.open_questions_or_blockers.add(MAKE_BLOCKER_ENTRY(result))
   ctx.quick_resume = UPDATE_QUICK_RESUME(ctx, result)
   return ctx

# ══ SWITCHING ══
procedure DO_SWITCHING(ctx, intent):
   # A) Sync & archive current
   ctx = SYNC_CONTEXT(ctx, { decision_made: true, reason: "pre-switch" })
   WRITE_CONTEXT(ACTIVE_CONTEXT_FILE, ctx)
   archive_file = ARCHIVE_DIR + ctx.current_task.task_id + ".md"
   WRITE_CONTEXT(archive_file, ENSURE_ARCHIVE_SUMMARY(ctx))
   
   # B) Update registry
   registry = READ_YAML(CONTEXT_REGISTRY_FILE)
   registry = UPSERT_REGISTRY_ENTRY(registry, ctx, archive_file)
   WRITE_YAML(CONTEXT_REGISTRY_FILE, registry)

   # C) Find match or bootstrap
   candidate = FIND_MATCH_IN_REGISTRY(registry, intent)
   if candidate AND IS_GOOD_MATCH(candidate, intent):
      new_ctx = READ_CONTEXT(candidate.file)
      new_ctx.meta.last_updated = NOW_ISO8601()
      new_ctx.decisions.add("Restored from " + candidate.file)
      return APPLY_DRIFT_CHECKS(new_ctx)
   
   new_ctx = BOOTSTRAP_NEW_CONTEXT(intent)
   new_ctx.meta.last_updated = NOW_ISO8601()
   new_ctx.decisions.add("Bootstrapped new context")
   return new_ctx

# ══ QUALITY GATE ══
procedure BEFORE_FINISHING_RESPONSE(ctx):
   assert ctx.progress.next is actionable
   assert ctx.quick_resume.next_action is concrete
   assert ACTIVE_CONTEXT_CONTAINS_NO_SECRETS(ctx)

# ══ MANUAL COMMANDS (see manual_commands.md for full pseudocode) ══
# LIST_CONTEXTS()      → "list contexts", "show contexts"
# SWITCH_TO_CONTEXT(t) → "switch to #N", "switch to <task_id>"
# CREATE_NEW_CONTEXT() → "new context <name>", "create task <name>"
# ARCHIVE_CURRENT()    → "archive context", "save and close"
```
