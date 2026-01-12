# Context Workflow (pseudocode)

Modules:
- `docs/context_rules/active_context_template.md` — active context template
- `docs/context_rules/project_context_rules.md` — project-wide onboarding rules (one-time creation + occasional updates)
- `docs/context_rules/project_context_template.md` — project context template
- `docs/context_rules/sync.md` — sync flow
- `docs/context_rules/switching.md` — archive/restore/bootstrapping
- `docs/context_rules/multi_task.md` — parallel task rules
- `docs/context_rules/staleness.md` — staleness policies
- `docs/context_rules/manual_commands.md` — manual CLI commands
- `docs/context_rules/hygiene_security.md` — hygiene & security checks

```text
# ══ ARTIFACTS & CONSTANTS ══
ACTIVE_CONTEXT_FILE   = "ai_memory/active_context.md"
PROJECT_CONTEXT_FILE  = "ai_memory/project_context.md"
CONTEXT_REGISTRY_FILE = "ai_memory/context_history/contexts_index.yaml"
ARCHIVE_DIR           = "ai_memory/context_history/"
STALENESS_WARN_DAYS = 7;  STALENESS_FULL_REFRESH = 30;  GOOD_MATCH_MIN_TAGS = 2

# ══ CORE INVARIANTS ══
assert ACTIVE_CONTEXT_FILE exists
assert active_context.current_task is exactly one task  # primary task
# active_context may also include other tasks from the same chat (see multi_task.md)

# ══ CONTENT PRINCIPLES (for ACTIVE_CONTEXT_FILE) ══
# - No hard numeric limits (bullets/lines). Write as much as needed to resume work without guessing.
# - Avoid redundancy: prefer state over chat history; move logs/diffs to session history.
# - Keep next steps actionable and concrete.

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
   # Multi-task means: keep multiple tasks inside the same active_context
   # (only when they belong to this same chat and it's useful to switch between them).
   if intent.wants_parallel_tasks: return "MULTI_TASK"
   return "SYNC"

# On NEW chat start, the active context must be focused.
# If the current file contains multiple tasks from previous sessions, keep only the one
# that matches the new intent; archive all others separately by task_id.
procedure PRUNE_UNRELATED_TASKS_ON_NEW_CHAT(ctx, intent):
   if NOT ctx.has_multiple_tasks: return ctx

   target_task_id = FIND_MATCHING_TASK_ID_IN_ACTIVE_CONTEXT(ctx, intent)  # may return null
   if target_task_id is null:
      return ctx  # DO_SWITCHING will handle full archive+replace when flow=SWITCHING

   # 1) Sync before mutating/archiving to avoid losing latest state
   ctx = SYNC_CONTEXT(ctx, { decision_made: true, reason: "new-chat prune unrelated tasks" })
   WRITE_CONTEXT(ACTIVE_CONTEXT_FILE, ctx)

   # 2) Archive everything except the target task
   archives = ARCHIVE_TASKS_EXCEPT(ctx, target_task_id)  # writes ARCHIVE_DIR/<task_id>.md

   # 3) Registry update for archived tasks
   registry = READ_YAML(CONTEXT_REGISTRY_FILE)
   registry = UPSERT_REGISTRY_ENTRIES(registry, ctx, archives)
   WRITE_YAML(CONTEXT_REGISTRY_FILE, registry)

   # 4) Keep only the target task in active_context
   focused = KEEP_ONLY_TASK_SECTIONS(ctx, target_task_id)
   focused.meta.last_updated = NOW_ISO8601()
   focused.decisions.add("Pruned unrelated tasks on new chat; archived others")
   return focused

# INTENT_CONTRADICTS_CURRENT_TASK MUST return true if intent implies a different task_id
# or a materially different goal/topic. Task switches are handled only via DO_SWITCHING.

# ══ MAIN ENTRY POINT ══
procedure START_NEW_CHAT(user_message):
   project_ctx = READ_PROJECT_CONTEXT(PROJECT_CONTEXT_FILE)  # optional but recommended
   ctx = READ_CONTEXT(ACTIVE_CONTEXT_FILE)

   staleness = IS_STALE(ctx)
   if staleness.stale:
      ctx = REFRESH_STALE_CONTEXT(ctx, staleness.severity)
      WRITE_CONTEXT(ACTIVE_CONTEXT_FILE, ctx)

   intent = INFER_USER_INTENT(user_message)

   # NEW chat rule: activate matching task and archive all unrelated ones
   ctx = PRUNE_UNRELATED_TASKS_ON_NEW_CHAT(ctx, intent)
   WRITE_CONTEXT(ACTIVE_CONTEXT_FILE, ctx)

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

   # Project-wide onboarding context (optional): update only when information is durable
   # and applies to the entire repo (not a single task).
   if result.has_project_wide_insight:
      project_ctx = SYNC_PROJECT_CONTEXT(READ_PROJECT_CONTEXT(PROJECT_CONTEXT_FILE), result)
      WRITE_PROJECT_CONTEXT(PROJECT_CONTEXT_FILE, project_ctx)
   return ctx

function SYNC_CONTEXT(ctx, result):
   ctx.meta.last_updated = NOW_ISO8601()

   # Strict invariant: sync must not change the task identity.
   # If you need a different task_id, you MUST run DO_SWITCHING (archive+registry).
   assert ctx.current_task.task_id is unchanged

   ctx.current_task.goal = NORMALIZE_GOAL(ctx.current_task.goal, result.user_intent)
   ctx.plan_and_references = UPDATE_REFERENCES(ctx.plan_and_references, result)
   ctx.progress = UPDATE_PROGRESS(ctx.progress, result)
   if result.decision_made: ctx.decisions.add(MAKE_DECISION_ENTRY(result))
   if result.has_blocker: ctx.open_questions_or_blockers.add(MAKE_BLOCKER_ENTRY(result))
   ctx.quick_resume = UPDATE_QUICK_RESUME(ctx, result)
   return ctx

# ══ SWITCHING ══
procedure DO_SWITCHING(ctx, intent):
   # A) Sync & archive current context.
   # If active_context contains multiple tasks (current + other tasks),
   # you MUST archive them separately by task_id so none of them are lost.
   ctx = SYNC_CONTEXT(ctx, { decision_made: true, reason: "pre-switch" })
   WRITE_CONTEXT(ACTIVE_CONTEXT_FILE, ctx)

   archives = ARCHIVE_ALL_TASKS_SEPARATELY(ctx)  # writes ARCHIVE_DIR/<task_id>.md
   
   # B) Update registry
   registry = READ_YAML(CONTEXT_REGISTRY_FILE)
   registry = UPSERT_REGISTRY_ENTRIES(registry, ctx, archives)
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

   # Optional: if switching uncovered project-wide learnings, persist them
   if intent.has_project_wide_insight:
      project_ctx = SYNC_PROJECT_CONTEXT(READ_PROJECT_CONTEXT(PROJECT_CONTEXT_FILE), intent)
      WRITE_PROJECT_CONTEXT(PROJECT_CONTEXT_FILE, project_ctx)
   return new_ctx

# ══ QUALITY GATE ══
procedure BEFORE_FINISHING_RESPONSE(ctx):
   assert ctx.progress.next is actionable
   assert ctx.quick_resume.next_action is concrete
   assert ACTIVE_CONTEXT_CONTAINS_NO_SECRETS(ctx)
   # Quality check: sufficient detail to resume, without redundant bulk.
   assert ACTIVE_CONTEXT_IS_RESUMABLE(ctx)

# ══ MANUAL COMMANDS (see manual_commands.md for full pseudocode) ══
# LIST_CONTEXTS()      → "list contexts", "show contexts"
# SWITCH_TO_CONTEXT(t) → "switch to #N", "switch to <task_id>"
# CREATE_NEW_CONTEXT() → "new context <name>", "create task <name>"
# ARCHIVE_CURRENT()    → "archive context", "save and close"
```
