# Archived dashboard (onboard era) — moved 2026-09-30 by dashboard regeneration

# Active Context

- **Current work:** Onboard (Phase 0) — reverse-engineering dev-flow docs
- **Phase:** onboard
- **Status:** completed — all 3 validation findings resolved (2026-04-19)
- **Started:** 2026-04-18
- **Completed:** 2026-04-19

## Progress State

- [x] Step 1: Initialize workspace
- [x] Step 2: Map project structure
- [x] Step 3: Dependency analysis + layer decomposition
- [x] Scope confirmation with user
- [x] Step 4: Per-module analysis — 37 files, 17 logical modules
- [x] Step 5: Coding rules extracted — 45 rules across 6 categories
- [x] Step 5a: Skills knowledge base — 10 skills across 5 domains
- [x] Step 6: Concept/spec/plan docs — 45 triples (135 files)
- [x] Step 7: `docs/_index.md` + 4 epics
- [x] Step 8: Validation + `.dev_flow/onboard/report.md`

## Next Step

Onboard fully complete. Optional follow-ups:
1. Sample accuracy check — pick 2–3 concepts, read them end-to-end against source to confirm they describe current behavior before treating as authoritative.
2. Cross-link hand-written docs (INTERNALS.md, elements.md, JS_API.md, EXPORT_*.md, remote_dbg*.md) from relevant concept "See also" sections.
3. Commit the onboard output as an initial bulk commit (or one PR per epic).

## Relevant Documents

- Project root: /home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop
- Final report: `.dev_flow/onboard/report.md`
- Index: `docs/_index.md`
- Epics: `docs/{domain-core,editor,simulator,visualization}.epic.md`
- 45 concept triples: `docs/*.{concept,sp,plan}.md`
- Rules: `.dev_flow/rules/` + `_index.yaml`
- Skills: `.dev_flow/skills/` + hierarchical `_index.yaml`
- Authoritative raw analyses: `.dev_flow/onboard/analysis/*.md` (37 files)
- Issues log: `.dev_flow/onboard/issues.md`
- State: `.dev_flow/onboard/state.yaml` (status: completed)

## Recent Changes

- 2026-04-19 — Step 8 complete; report.md written; state.yaml closed as `completed`.
- 2026-04-19 — Step 7 complete; index + 4 epics generated.
- 2026-04-19 — Step 6 complete; 45 concept triples generated.
- 2026-04-19 — Step 5a complete; skills knowledge base seeded.
- 2026-04-19 — Step 5 complete; 45 coding rules extracted.
- 2026-04-18 — Steps 1-4 complete; all 17 modules analyzed.

## Notes / Blockers

- V-1 is a blocker for treating `docs/*.concept.md` as canonical — fix first.
- Authoritative raw content lives in `.dev_flow/onboard/analysis/*.md`. Concepts are distilled summaries; fall back to analyses when detail is needed.
- No JUnit harness in the project — Phase 5 (Test) uses build checks + roundtrip + devmode (see `.dev_flow/rules/testing.md`).
- User preference: pause between waves during long `/dev-flow onboard` runs (see user memory `feedback_pause_control.md`).
