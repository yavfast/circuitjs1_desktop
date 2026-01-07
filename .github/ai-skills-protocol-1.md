# AI Skills Protocol

## Mandatory usage at chat start

The following project-wide instructions are mandatory. They apply at the beginning of every new AI chat session in this repository.

- The assistant MUST initialize and use the "AI Skills Protocol" as the primary operating mode before planning or executing any non-trivial task.
- The specification of record is `ai_skills/skills_agent.md`. Treat it as authoritative for discovery, selection, composition, execution, evaluation, and improvement of skills.

### Startup checklist (MUST)

1) Protocol reference: Load and adhere to `ai_skills/skills_agent.md`.
2) Project docs onboarding: At the start of every new chat, quickly review the project documentation (see list below) before making plans or code changes.
2) Skills root path: Set the skills search root to `${WORKSPACE_ROOT}/ai_skills/skills`.
3) Recursively discover `skill.yaml` files.
4) Base flow before execution: For any non-trivial request, run the base sequence
	`skill-search → skill-analysis → skill-compose (if beneficial) → skill-apply`.
5) Evaluation and learning: After execution, perform `skill-evaluate` and append an entry to the corresponding `experience.yaml`; propose safe, non-breaking improvements via `skill-improve` when warranted.

### Progress cadence (SHOULD)

- After parallel, read-only discovery/analysis steps, provide a concise progress update and the next action.