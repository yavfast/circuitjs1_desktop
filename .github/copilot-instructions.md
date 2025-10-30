# AI Skills Protocol

## Mandatory usage at chat start

The following project-wide instructions are mandatory. They apply at the beginning of every new AI chat session in this repository.

- The assistant MUST initialize and use the "AI Skills Protocol" as the primary operating mode before planning or executing any non-trivial task.
- The specification of record is `ai_skills/skills_agent.md`. Treat it as authoritative for discovery, selection, composition, execution, evaluation, and improvement of skills.

### Startup checklist (MUST)

1) Protocol reference: Load and adhere to `ai_skills/skills_agent.md`.
2) Skills root path: Set the skills search root to `${WORKSPACE_ROOT}/ai_skills/skills`.
	- If the path is missing or empty, STOP and request correction before continuing.
3) Discovery and validation: Recursively discover `skill.yaml` files and validate them against `ai_skills/skills_spec.schema.yaml` and `ai_skills/experience_spec.schema.yaml` (when present).
4) Base flow before execution: For any non-trivial request, run the base sequence
	`skill-search → skill-analysis → skill-compose (if beneficial) → skill-apply`.
5) Governance and safety: Enforce autonomy level default = 2 (semi-autonomous with rollback), respect resource budgets/timeouts, default network policy `deny:*`, and least-privilege permissions.
6) Evaluation and learning: After execution, perform `skill-evaluate` and append an entry to the corresponding `experience.yaml`; propose safe, non-breaking improvements via `skill-improve` when warranted.

### Compliance gate (MUST)

- If protocol initialization or schema validation fails, DO NOT proceed with multi-step work. Ask to fix skills path/schemas or provide missing files. Trivial Q&A that requires no edits or tool use may proceed, but MUST still declare that the protocol startup check ran.

### Progress cadence (SHOULD)

- After parallel, read-only discovery/analysis steps, provide a concise progress update and the next action.