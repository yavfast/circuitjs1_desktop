# CLI Quick Reference (ai_mem.sh)

These are the minimal commands agents SHOULD use at task start/end.

## Task start: retrieve with context

```bash
./ai_mem.sh search --query "task description" --top 5 --depth 2 --evidence 1 \
  --context-text "domain/subdomain/topic" --context-strict
```

Recommended for real agent use (vector-first, multilingual-friendly):

```bash
./ai_mem.sh search --seed-mode chroma --query "task description" --top 5 --depth 2 --evidence 1 \
  --context-text "domain/subdomain/topic" --context-strict
```

## Task end: ingest Episode (MANDATORY)

```bash
mkdir -p ai_memory/session_history
./ai_mem.sh ingest --json ai_memory/session_history/episode_YYYY-MM-DD_short_slug.json

# Or (no files)
./ai_mem.sh ingest --stdin
```

## Skills metadata (optional but recommended)

```bash
./ai_mem.sh skill list
./ai_mem.sh skill get --name "skill-name"
./ai_mem.sh skill put --json skill_def.json

# Or (no files)
./ai_mem.sh skill put --stdin
```

## Tips

- Prefer `--context-text "a/b/c"` (hierarchical context path) over long free text.
- Use `--evidence 1` when you need explainability for the selected skills.
- Use `--seed-mode sql` mainly for deterministic tests/fallbacks.
