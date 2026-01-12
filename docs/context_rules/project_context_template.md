# Project Context Template

This file defines the recommended structure of `ai_memory/project_context.md`.

## Purpose

`ai_memory/project_context.md` is a durable, project-wide onboarding artifact.
It should help a new AI agent understand the repository quickly at the start of a new chat.

## Recommended sections (order is required)

1) **Meta**
   - `last_updated`: ISO-8601 datetime (UTC or with timezone)
   - `project_root`: repository root path
   - `primary_language`: `uk` or `en`

2) **What This Repo Is**
   - short description (1–2 paragraphs)

3) **Key Modules / Folders**
   - major modules, responsibilities, and key paths

4) **Build / Run / Debug**
   - common commands and what they do
   - where outputs live (build artifacts/output directories)

5) **Project-Specific Concepts**
   - domain concepts specific to this repo (frameworks, runtime, data formats, lifecycle)

6) **Common Gotchas**
   - recurring pitfalls + workarounds

7) **References**
   - pointers to canonical docs under `docs/`

## Copy-paste template

```markdown
# Project Context — <Project Name>

## Meta
- last_updated: <ISO-8601>
- project_root: <path>
- primary_language: <uk/en>

## What This Repo Is
<short description>

## Key Modules / Folders
- <module> — <responsibility> — key paths

## Build / Run / Debug
- <command> — <what it does>

## Project-Specific Concepts
- <concept> — <explanation>

## Common Gotchas
- <gotcha> — <fix/workaround>

## References
- README.md
- docs/
- <runbooks/>
- <ADRs/>
- ...
```

## Quality gate

`project_context.md` is “good” if a new agent can:

- pick the right build/run command in <2 minutes,
- locate the main entrypoints/modules quickly,
- know which docs to read next.
