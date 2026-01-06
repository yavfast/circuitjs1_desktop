# Context-aware Retrieval Rules

Goal: make retrieval cheap + relevant by always scoping search with a short hierarchical context representation.

## 1) Context path format (recommended)

Use a short hierarchical path:

- Format: `domain/subdomain/topic`
- Separator: `/`
- Size: 3–6 segments max
- Charset: prefer lowercase ascii, digits, `-`, `/`

Examples:
- `circuitjs/export/json`
- `ai-skills-memory/protocol/docs`
- `gwt/ui/refactor`

## 2) How to derive it

```text
function BUILD_CONTEXT_PATH(active_context, user_request):
    # Extract a few stable concepts (not the whole chat):
    # - project area (folder/feature)
    # - task type (refactor/test/docs/bugfix)
    # - domain keywords
    topics = EXTRACT_TOPICS(active_context, user_request)
    tokens = NORMALIZE(topics)
    return JOIN(tokens[0:6], "/")
```

Rules:
- Prefer canonical repo terms (folder names, feature names, file stems).
- If multiple possible paths exist, choose the simplest that matches the task.
- Keep it stable across the task (do not constantly re-derive).

## 3) Context scoping semantics

When a hierarchical context is used:

- The system SHOULD treat `a/b/c` as a descendant of `a/b`.
- Scoped retrieval SHOULD include descendants by default.

```text
function RESOLVE_CONTEXT_SCOPE(ctx_path):
    node = FIND_OR_CREATE_CONTEXT_NODE(ctx_path)
    return {node + ALL_DESCENDANTS(node)}
```

## 4) Practical usage with ai_mem.sh

```bash
./ai_mem.sh search --query "..." --top 5 --depth 2 --evidence 1 \
  --context-text "gwt/ui/refactor" --context-strict
```

Notes:
- `--context-text` accepts free text too, but prefer context-path for token efficiency.
- `--context-strict` keeps retrieval inside the context scope.

## 5) Recommendation: hybrid context (practical)

- Use a canonical context path (`a/b/c`) for *scoping* and hierarchy.
- If you want extra nuance ("what exactly is going on"), prefer putting that nuance into the query itself or the Episode `summary/task_text`, rather than inventing many one-off context strings.
- If you must pass non-path context text (spaces/punctuation), keep it short and include stable tokens; SQL-mode has a deterministic token-based fallback, and Chroma-mode can use embedding similarity.
