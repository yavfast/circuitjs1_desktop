# AI Skills Memory — Rules for AI Agents

**Version:** 1.3 | **Date:** 2026-01-06

Entry-point rules for using the local AI Skills Memory system.

Details live in:
- [docs/skills_rules/context.md](context.md)
- [docs/skills_rules/search.md](search.md)
- [docs/skills_rules/episode_schema.md](episode_schema.md)
- [docs/skills_rules/save.md](save.md)
- [docs/skills_rules/cli.md](cli.md)

## 0) Non-negotiables (MUST)

- **LTM-first**: Capture memory only when it increases future usefulness (avoid bureaucracy).
- **Selection must be justified**: evidence (episodes/metrics) or "no prior data".
- **Retrieval is context-aware**: always use a short hierarchical context path (`ctx_path`).
- **Vector-first**: For term similarity (incl. multilingual), prefer vector retrieval (Chroma/Ollama embeddings).

## 0.1) Search-first (RECOMMENDED)

Detailed search rules are in [docs/skills_rules/search.md](search.md).

## 1) Mandatory loop (pseudocode)

```text
procedure RUN_STDIN(cmd, json_payload):
    # Execute a command and pass JSON via stdin.
    # Example implementation: echo JSON | cmd
    EXEC(cmd, stdin=json_payload)

procedure RUN_JSON_FILE(cmd, json_path):
    # Execute a command and pass a JSON file path.
    EXEC(cmd + " --json " + json_path)

procedure HANDLE_REQUEST(user_request):
    # LOAD & SCOPE
    active_ctx = LOAD_ACTIVE_CONTEXT()
    # Strategy: 1. Check active_context. 2. Ask LLM/Ollama. 3. Default to 'general'.
    ctx_path = RESOLVE_CONTEXT_PATH(active_ctx, user_request)

    # SEARCH (context-aware)
    candidates = SKILL_SEARCH(user_request, ctx_path)
    selection = SELECT_SKILLS(candidates)
    ASSERT(selection.justified)  # evidence or "no prior data"

    # PLAN + EXECUTE
    plan = COMPOSE_PLAN(user_request, selection)
    result = APPLY(plan)

    # VALIDATE + EVALUATE
    validation = VALIDATE(result)
    evaluation = EVALUATE(result, validation)

    # SAVE (RECOMMENDED; MUST for non-trivial work)
    # Trivial tasks may be batched or skipped if they add no LTM value.
    episode = BUILD_EPISODE(user_request, plan, result, validation, evaluation, ctx_path)
    maybe INGEST_EPISODE(episode)

    # LEARN (OPTIONAL BUT EXPECTED)
    maybe NEW_SKILL = DETECT_NEW_SKILL(user_request, result)
    if NEW_SKILL:
        STORE_SKILL(NEW_SKILL)

    maybe IMPROVE = SHOULD_IMPROVE(evaluation)
    if IMPROVE:
        proposal = PROPOSE_MINIMAL_IMPROVEMENT(evaluation)
        if VALIDATE_IMPROVEMENT(proposal):
            ADOPT_IMPROVEMENT(proposal)

    # SYNC CONTEXT
    UPDATE_ACTIVE_CONTEXT(user_request, result, evaluation)
    return result
```

## 2) Retrieval + scoping (MUST)

See:
- Context derivation/scoping: [docs/skills_rules/context.md](context.md)
- Search protocol and CLI patterns: [docs/skills_rules/search.md](search.md)


## 3) Detect and store new skills (SHOULD)

```text
function DETECT_NEW_SKILL(user_request, result):
    if result.contains_reusable_pattern OR result.high_impact_fix OR result.project_specific_knowledge:
        return EXTRACT_SKILL_DEF(user_request, result)
    return null

function STORE_SKILL(skill_def):
    ASSERT(skill_def.has_examples)
    # Recommended in chat: stdin (easy inline review)
    RUN_STDIN("./ai_mem.sh skill put --stdin", skill_def)
    # Alternative: file-based (--json) for reproducibility
    # RUN_JSON_FILE("./ai_mem.sh skill put", "/path/to/skill_def.json")

function INGEST_EPISODE(episode):
    # Recommended in chat: stdin (no files)
    RUN_STDIN("./ai_mem.sh ingest --stdin", episode)
    # Alternative: file-based
    # RUN_JSON_FILE("./ai_mem.sh ingest", "ai_memory/session_history/episode_YYYY-MM-DD_short_slug.json")
```

## 4) Save / ingest (RECOMMENDED; MUST for non-trivial work)

Detailed save rules (episode file locations, stdin vs file examples, when to add episodes/facts) are in:
[docs/skills_rules/save.md](save.md).

## 5) CLI quick reference

See [docs/skills_rules/cli.md](cli.md).

## 6) Invariants

- Context-aware: search is always scoped.
- Accumulation is proportional: prioritize LTM value, batch/skip trivial work.
- Gated improvement: validate before adopting changes.

    
