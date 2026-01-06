# Core project documentation (review at chat start)

- `docs/project.md` — project overview, structure, and tooling stack (Maven/GWT/Node/NW.js).
iteration).
- `docs/JS_API.md` — JavaScript API for automation/testing (import/export, simulation control, scopes, logs).
- `docs/EXPORT_CJS.md` — proposed/modern JSON circuit export format (self-describing schema, pins/nodes).
- `docs/EXPORT_OLD.md` — legacy “dump” text export format (element line encodings + header).
- `docs/circuit_manual_uk.md` — Ukrainian manual for authoring circuits in text format (practical examples).
- `docs/elements.md` — catalog of circuit elements (menu categories, classes, short descriptions).

# Active Context (MUST)

- On every new chat start, ALWAYS load and follow `ai_memory/active_context.md`.
- Keep `ai_memory/active_context.md` continuously synchronized with the current chat/task state.
- The rules and required template for updating/syncing the active context are defined in `docs/context_rules/context_rules.md` and MUST be followed.

# AI Skills Memory (MUST)

Use the local AI Skills Memory system (`./ai_mem.sh`) to accumulate and retrieve knowledge:

1. **On task start**: Run `./ai_mem.sh search --query "task description" --context-text "domain/subdomain/topic" --context-strict` to find relevant prior knowledge (context-aware).
2. **On task end**: Save Episode via `./ai_mem.sh ingest --json episode.json` (MANDATORY).
3. **Detect new skills**: If a novel reusable pattern is discovered, save it via `./ai_mem.sh skill put`.
4. **Justify selections**: Every skill choice must reference evidence (episodes/metrics) or state "no prior data".

Full protocol and pseudocode: `docs/skills_rules/skills_rules.md`
