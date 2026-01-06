# Episode JSON Schema (MVP)

An Episode is the mandatory unit of persistence for every completed task.

## Required fields

```json
{
  "timestamp": "2026-01-01T13:00:00+02:00",
  "task_text": "User request text",
  "summary": "1-2 sentence outcome summary",
  "used_skills": ["skill-a", "skill-b"],
  "touched_artifacts": ["path/or/id"],
  "contexts": ["domain/subdomain/topic"],
  "outcome": {"status": "success", "notes": "optional"},
  "errors": [],
  "tests": []
}
```

Notes:
- `contexts` MUST include the hierarchical context path used for retrieval.
- `outcome.status` is one of: `success` | `failure` | `partial`.

## Minimal extraction rules (deterministic)

```text
function BUILD_EPISODE(user_request, plan, result, validation, evaluation, ctx_path):
    return {
        timestamp: NOW(),
        task_text: user_request,
        summary: SUMMARIZE(result),
        used_skills: UNIQUE(plan.skills),
        touched_artifacts: UNIQUE(TOUCHED_FILES_AND_KEY_ARTIFACTS(result)),
        contexts: [ctx_path],
        outcome: {status: evaluation.status, notes: evaluation.notes},
        errors: UNIQUE(ERROR_MESSAGES(result, validation)),
        tests: UNIQUE(TEST_RESULTS(validation))
    }
```

## Fallback rule

If ingestion is impossible:
- Append a minimal entry to `ai_memory/memory.md` with the same fields (as text), and record the reason.
