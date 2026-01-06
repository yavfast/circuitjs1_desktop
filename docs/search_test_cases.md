# Search Test Cases (Seed Corpus)

This file contains practical, copy-paste-ready search scenarios against the existing seeded test data in:
- `scripts/ai_skills_memory/test_data/episodes.jsonl`
- `scripts/ai_skills_memory/test_data/skill_defs.json`

Goal:
- Provide real-ish usage examples for AI agents.
- Cover multilingual / cross-domain / ambiguous-term behavior.
- Show when `--seed-mode chroma` vs `--seed-mode sql` is useful.

## 0) One-time setup (seed fresh test DB)

Deterministic setup (no embeddings):

```bash
python3 scripts/ai_skills_memory/seed_test_db.py --reset --no-embed

# Point ai_mem.sh to the seeded *test* storage
export AI_SKILLS_DB=ai_memory/ai_skills_memory_test.sqlite
export AI_SKILLS_CHROMA=ai_memory/ai_skills_chroma_test
```

Semantic setup (embeddings enabled; requires local Ollama):

```bash
python3 scripts/ai_skills_memory/seed_test_db.py --reset

# Point ai_mem.sh to the seeded *test* storage
export AI_SKILLS_DB=ai_memory/ai_skills_memory_test.sqlite
export AI_SKILLS_CHROMA=ai_memory/ai_skills_chroma_test
```

Notes:
- `--seed-mode sql` works well with the deterministic setup.
- `--seed-mode chroma` is most meaningful with embeddings enabled.
- If you already use `ai_mem.sh` for the main DB, remember this file intentionally targets the test corpus via env vars above.

## 1) Ambiguous term resolved by context: GREEN

**Intent:** same token should map to different skills depending on context.

Nature context (expect: `domain-nature`):

```bash
./ai_mem.sh search --seed-mode sql --query "AMBIG:GREEN" --top 5 --depth 2 --evidence 2 \
  --context-text "природа" --context-strict
```

Politics context (expect: `domain-politics`):

```bash
./ai_mem.sh search --seed-mode sql --query "AMBIG:GREEN" --top 5 --depth 2 --evidence 2 \
  --context-text "політика" --context-strict
```

Variant (semantic): try free-form context phrasing:

```bash
./ai_mem.sh search --seed-mode chroma --query "AMBIG:GREEN" --top 5 --depth 2 --evidence 2 \
  --context-text "environment / ecology" --context-strict
```

Success criteria:
- Top skill differs by context.
- Evidence episodes show the matching context episodes.

## 2) Ambiguous term resolved by context: VIRUS (biology vs cybersecurity)

Biology context (expect: `domain-biology`):

```bash
./ai_mem.sh search --seed-mode sql --query "AMBIG:VIRUS" --top 5 --depth 2 --evidence 2 \
  --context-text "біологія" --context-strict
```

Cybersecurity context (expect: `domain-cyber`):

```bash
./ai_mem.sh search --seed-mode sql --query "AMBIG:VIRUS" --top 5 --depth 2 --evidence 2 \
  --context-text "кібербезпека" --context-strict
```

Semantic cross-language probe (may vary by embedding model):

```bash
./ai_mem.sh search --seed-mode chroma --query "How does a virus replicate?" --top 5 --depth 2 --evidence 2 \
  --context-text "biology/viruses" --context-strict
```

Success criteria:
- With strict scoping, the top skill tracks the selected domain.

## 3) Ambiguous term resolved by context: PARTY (social vs political)

Social context (expect: `domain-society`):

```bash
./ai_mem.sh search --seed-mode sql --query "AMBIG:PARTY" --top 5 --depth 2 --evidence 2 \
  --context-text "соціум" --context-strict
```

Politics context (expect: `domain-politics`):

```bash
./ai_mem.sh search --seed-mode sql --query "AMBIG:PARTY" --top 5 --depth 2 --evidence 2 \
  --context-text "політика" --context-strict
```

## 4) Engineering case: embedding failures and recovery

Failure episode exists in the seed corpus for Ollama timeouts.

Deterministic (expect: `ollama-embeddings` appears; evidence includes timeout):

```bash
./ai_mem.sh search --seed-mode sql --query "Ollama embeddings timeout" --top 5 --depth 2 --evidence 3
```

Semantic phrasing variant:

```bash
./ai_mem.sh search --seed-mode chroma --query "local embeddings API timed out" --top 5 --depth 2 --evidence 3
```

Success criteria:
- `ollama-embeddings` is present.
- Evidence shows at least one failure episode (`outcome=failure`) mentioning timeout.

## 5) Engineering case: GWT build failure

Deterministic (expect: `gwt-build`):

```bash
./ai_mem.sh search --seed-mode sql --query "GWT build failed classpath" --top 5 --depth 2 --evidence 2
```

Semantic phrasing variant:

```bash
./ai_mem.sh search --seed-mode chroma --query "Maven GWT compilation failure missing class" --top 5 --depth 2 --evidence 2
```

## 6) Context hierarchy behavior (descendants included)

Seed corpus contains a leaf context `біологія/віруси/реплікація`.

Parent scope (expect: still finds biology-virus episodes via descendants):

```bash
./ai_mem.sh search --seed-mode sql --query "AMBIG:VIRUS" --top 5 --depth 2 --evidence 2 \
  --context-text "біологія" --context-strict
```

Leaf scope:

```bash
./ai_mem.sh search --seed-mode sql --query "AMBIG:VIRUS" --top 5 --depth 2 --evidence 2 \
  --context-text "біологія/віруси/реплікація" --context-strict
```

Success criteria:
- Both runs surface `domain-biology`, but leaf scope typically yields tighter evidence.

## 7) Multi-agent / multi-domain reality check

This is a sanity scenario to simulate multiple agents working across unrelated domains.

Run three different queries with strict contexts:

```bash
./ai_mem.sh search --seed-mode sql --query "migration timing" --top 5 --depth 2 --evidence 2 \
  --context-text "природа" --context-strict

./ai_mem.sh search --seed-mode sql --query "budget debate framing" --top 5 --depth 2 --evidence 2 \
  --context-text "політика" --context-strict

./ai_mem.sh search --seed-mode sql --query "conflict de-escalation" --top 5 --depth 2 --evidence 2 \
  --context-text "соціум" --context-strict
```

Success criteria:
- Each query surfaces the matching domain skill and does not drift into the others when strict scoping is enabled.
