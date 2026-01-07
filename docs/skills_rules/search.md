# Search Rules (ai_mem.sh search)

This file contains the detailed rules for how agents SHOULD search memory.
The main protocol overview is in [docs/skills_rules/skills_rules.md](skills_rules.md).

## 1) Search-first protocol (RECOMMENDED)

Run quick retrieval early to avoid reinventing decisions.

When to run searches:
- At the start of any non-trivial request (analysis/design/refactor/tests).
- During the **context-forming stage**: if `ctx_path` is uncertain, do a broad search first, then pick a stable path and re-run scoped.

Minimum set (2 queries):

```bash
# A) Broad (no context yet)
./ai_mem.sh search --seed-mode chroma --query "<intent + key terms>" --top 5 --depth 2 --evidence 2

# B) Scoped (after choosing a stable ctx_path)
./ai_mem.sh search --seed-mode chroma --query "<intent + key terms>" --top 5 --depth 2 --evidence 2 \
  --context-text "domain/subdomain/topic" --context-strict
```

Seed-mode guidance:
- Use `--seed-mode chroma` for semantic similarity (incl. multilingual).
- Use `--seed-mode sql` for deterministic test/diagnostic runs.

Query template (short, stable tokens):
- `<intent> + <artifact> + <symptom/signal> + <domain>`
- Examples: "context hierarchy scoping", "seed corpus ambiguous token tests", "ollama embeddings timeout"

## 2) Context scoping (MUST)

Search MUST be scoped via context.
For context path format + derivation rules see: [docs/skills_rules/context.md](context.md).

Practical usage:

```bash
./ai_mem.sh search --query "..." --top 5 --depth 2 --evidence 1 \
  --context-text "gwt/ui/refactor" --context-strict
```

## 3) Result usage (MUST)

- Skill selection MUST be justified: either cite evidence from retrieved episodes/metrics, or explicitly state "no prior data".
- Prefer rerunning scoped search once `ctx_path` becomes stable.
