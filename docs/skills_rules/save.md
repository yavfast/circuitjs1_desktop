# Save Rules (Episodes, Facts, Skills)

This file contains the detailed rules for saving memory (Episodes/Facts/Skills).
The main protocol overview is in [docs/skills_rules/skills_rules.md](skills_rules.md).

## 1) Episode files location (MUST)

Episodes are stored in the DB; files are optional and temporary.

- If you create an episode JSON file, store it only in one of:
  - `./ai_memory/session_history/` (repo-local, safe for long sessions)
  - `/tmp/ai_memory/` (OS temp, disposable)
- Do **not** write episode JSON files into the project root.

## 2) Ingest an Episode

Episode must include `contexts: [ctx_path]`. See full schema: [docs/skills_rules/episode_schema.md](episode_schema.md).

### 2.1) File-based ingest (recommended for reproducibility)

```bash
mkdir -p ai_memory/session_history
./ai_mem.sh ingest --json ai_memory/session_history/episode_YYYY-MM-DD_short_slug.json
```

### 2.2) Stdin-based ingest (recommended for chat review / no files)

```bash
cat <<'JSON' | ./ai_mem.sh ingest --stdin
{
  "timestamp": "2026-01-06T00:00:00+02:00",
  "task_text": "...",
  "summary": "...",
  "contexts": ["ai-skills/rules/episodes"],
  "used_skills": ["docs"],
  "touched_artifacts": ["docs/skills_rules/save.md"],
  "outcome": {"status": "success"},
  "errors": [],
  "tests": []
}
JSON
```

## 3) Store a Skill

### 3.1) File-based

```bash
./ai_mem.sh skill put --json /path/to/skill_def.json
```

### 3.2) Stdin-based (recommended for chat review)

```bash
cat <<'JSON' | ./ai_mem.sh skill put --stdin
{
  "skill_id": "example-skill",
  "title": "Example skill",
  "description": "One paragraph description.",
  "tags": ["docs", "workflow"]
}
JSON
```

## 4) When to add Episodes and Facts (RECOMMENDED)

Goal: long-term memory that is easy to reuse later.

Add an **Episode** when at least one applies:
- **Novelty**: new domain/topic, new subsystem, new artifact type, or "first time" workflow.
- **Non-obvious decision**: trade-offs, constraints, or chosen approach that would be hard to re-derive.
- **Complex investigation**: multi-step research, lots of dead ends, or cross-file reasoning.
- **High impact change**: refactor, architecture change, new automation/tooling, or risky behavior change.
- **Failure / tricky debug**: new failure mode, flaky behavior, environment-specific fixes.
- **Validation matters**: you ran tests/builds and the result is important evidence.

Add **Facts** (edges/skills/notes) when they are stable and reusable:
- A repeatable pattern, command sequence, or diagnosis recipe.
- A rule-of-thumb that is true across tasks in this repo.
- A mapping between symptoms and root causes (with evidence).

You can skip or batch episodes when:
- The task is purely mechanical (typo, whitespace, trivial rename) and adds no reusable knowledge.
- The work is a small part of a larger task and you plan a single consolidated episode at the end.

## 5) Which format is more convenient?

- `--stdin` is best when the agent is operating inside a chat and you want easy inline review.
- `--json <file>` is best when the content is long, you want easy re-run/audit, or you want a stable artifact during a long session.
