# Task: Agent automation over MCP (epic E_AGT)

> **Task ID:** `task_E_AGT`
> **Created:** 2026-10-01 15:20
> **Last updated:** 2026-10-01 22:10
> **Status:** `in-progress`
> **Contributors:** `main`
> **Autonomy:** `checkpoints`

## Current Work Item

| Field | Value |
|-------|-------|
| **Document** | `epic + concepts` — [agent-automation.epic.md](../../docs/agent-automation.epic.md), [agent-api](../../docs/agent-api.concept.md), [mcp-server](../../docs/mcp-server.concept.md), [mcp-bridge](../../docs/mcp-bridge.concept.md), [agent-skill](../../docs/agent-skill.concept.md) |
| **Pipeline phase** | `plan` |
| **Traceable ID** | E_AGT · C_AGA · C_MCP · C_MCB · C_AGS · SP_AGA · SP_MCP · SP_MCB · SP_AGS · PL_AGA · PL_MCP · PL_MCB · PL_AGS |

## Intent

- **Goal (why):** AI agents create, edit and debug circuits in CircuitJS1 Desktop.
- **Target state:** the app is an MCP server; a stdio bridge/CLI ("MCP client") and an agent skill exist.
- **Expected result:** epic acceptance criteria in [agent-automation.epic.md](../../docs/agent-automation.epic.md) (inferred from the request + interview).

## Description

Follows the research task [task_20261001_142742_mcp-research](task_20261001_142742_mcp-research.md) and its spike. Concept interview held 2026-10-01 (8 questions; mapping in the epic). — main

## Subtasks

### Subtask: concept authoring
> Author: `main` — Created: 15:20 — Last updated: 16:10 — Status: `done`

**Goal:** epic + four concepts with all interview decisions recorded; pass the Concept→Spec gate.

**Progress:**
- [x] Interview batch 1 (DEC_01–04) and batch 2 (DEC_05–08)
- [x] Epic + C_AGA, C_MCP, C_MCB, C_AGS written; glossary created; index updated
- [x] Skill `automation/agent-mcp-surface` corrected to the developer's decisions (exposure policy, geometry)
- [x] Concept→Spec gate self-check
- [x] Move to spec phase

**Activity:**
- 16:10 — developer overrode two spike recommendations: agent-written grid-cell coordinates (no netlist-first layout) and an always-on server with no access control (only spec-mandated Origin rejection kept)
- 16:10 — open decision C_MCP_DEC_03 (protocol layer) — trigger: hosting prototype at the start of C_MCP planning

### Subtask: specification authoring
> Author: `main` — Created: 16:20 — Last updated: 17:05 — Status: `in-progress`

**Goal:** SP_AGA, SP_MCP, SP_MCB, SP_AGS; pass the Spec→Plan gate; clean-context review of the design unit before commit sign-off.

**Progress:**
- [x] Code facts gathered (IDs, JSON import/export, undo item, sim stop/warn, labels static, scopes, documents, grid, multi-terminal geometry)
- [x] Four specs written; banned-phrase + link checks pass
- [x] Review round 1 (FAIL) → fix pass
- [x] Review round 2 (FAIL: N1, N2 must) → fix pass 2
- [x] Review round 3 (FAIL: 3 new must) → fix pass 3
- [x] Review round 4 (FAIL) → fix pass 4: routing made requirement-level (R1/R2) with mechanism decision SP_AGA_DEC_04 open until the plan prototype
- [x] Review rounds 5–6 → PASS; F1–F3 applied and confirmed
- [x] Committed 496d9c9 on `design/agent-mcp` (not pushed)
- [x] Move to plan phase

**Activity:**
- 20:45 — review converged after 6 rounds; open by design: SP_AGA_DEC_04 (background-doc mechanism), C_MCP_DEC_03 (protocol layer) — both closed by plan-start prototypes
- 18:10 — review round 1 FAIL; developer resolved SP_AGA_DEC_01=A, SP_MCP_DEC_01=A, new SP_MCP_DEC_03=A (circuit files only); fix pass applied across specs, concepts, epic, glossary, skills
- 17:05 — proposed decisions for design sign-off: SP_AGA_DEC_01 (one atomic `applyEdits` batch), SP_MCP_DEC_01 (14 grouped tools)
- 17:05 — concept C_AGA_04_01 C_IOF row corrected: no append-import mode (batch `add` uses the factory)

### Subtask: plan authoring
> Author: `main` — Created: 21:00 — Last updated: 22:10 — Status: `done`

**Goal:** PL_AGA, PL_MCP, PL_MCB, PL_AGS passing the Plan→Code gate; design + commit sign-off.

**Progress:**
- [x] Four plans written; spec-section coverage check passes
- [x] Plan review: round 1 FAIL (4 must) → round 2 FAIL (1 must) → round 3 PASS; S1/S2 applied (confirm)
- [x] Design sign-off and commit sign-off given by the developer (2026-10-01); new test scenarios (`agent_*` live scenarios, `tests/mcp/e2e.mjs`) approved
- [ ] **Next:** `/dev-flow implement` starting with PL_AGA P0 and PL_MCP P0 prototypes

**Activity:**
- 22:10 — open by design: PL_AGA_DEC_01 (closes SP_AGA_DEC_04), PL_MCP_DEC_01 (closes C_MCP_DEC_03) — resolved by the P0 prototypes
- 22:10 — SP_AGA_DEC_04 trigger edited in place (non-breaking) to the reduced prototype sequence

## Review Rounds

| Round | Scope | Mode | Baseline | Verdict | Carry-over (identity → recurrence) |
|-------|-------|------|----------|---------|-------------------------------------|
| 1 | full (no-baseline; docs-only design unit) | full | working tree 2026-10-01 17:05 | FAIL (9 must, 25 should) | M1–M9, S1–S25 → 1 |
| 2 | delta ∪ carry-over ∪ critical | full (must open) | working tree 2026-10-01 18:10 | FAIL (2 must open: M3/N1, M4/N2; N3–N19 should) | M3, M4 → 2; N3–N19 → 1 |
| 3 | delta ∪ carry-over ∪ critical | full | working tree 2026-10-01 18:55 | FAIL (carry-over all resolved; 3 new must: load-path session writes, lattice contradiction, import caps; S1–S6) | R3-M1..M3, R3-S1..S6 → 1 |
| 4 | delta ∪ carry-over ∪ critical | full | working tree 2026-10-01 19:40 | FAIL (R3-M1 remainder recurring → §03_08 restated as requirements + open DEC_04; new M2 slider seeds as limits; S1–S8) | R3-M1 → 2; R4-M2, R4-S1..S8 → 1 |
| 5 | delta ∪ carry-over ∪ critical | full | working tree 2026-10-01 20:05 | FAIL (R1 not testable; grid-pin contradiction; S1–S6) | R5-M1, R5-M2 → 1 |
| 6 | delta ∪ carry-over | full | working tree 2026-10-01 20:30 | PASS (F1–F3 should) | — |
| P1 | plans (full) | full | working tree 2026-10-01 21:30 | FAIL (4 must, 15 should) | → 1 |
| P2 | plans delta ∪ carry-over | full | 21:50 | FAIL (N1 must) | N1 → 1 |
| P3 | plans delta | full | 22:05 | PASS (S1, S2 should → applied, confirm) | — |
| 6b | F1–F3 prescribed fixes | confirm (main, mechanical) | working tree 2026-10-01 20:45 | PASS (R1 timing bounds measurable; R1/R2 rows 2 cells; skill grid-sized parts rule present) | — |

**Always in scope:** SP_AGA §01_06, §02_04, §02_10, §03_02, §03_04, §03_05, §03_08, §04_01 (declared `Criticality: critical`).

## Coordination Notes

## Blocking Issues

{No blockers yet.}

## Relevant Context

- `{s:pin}` Decisions: C_AGA_DEC_01 grid cells + import & incremental · DEC_02 per-document ID registry · DEC_03 any tab by handle · DEC_04 transactions sealed by commented checkpoints (auto-seal on user edit/save/close/idle) · C_MCP_DEC_01 server per instance + registry · DEC_02 always on, LAN, no token · DEC_03 OPEN protocol layer · C_MCB_DEC_01 stdio bridge + CLI.
- Skills: `.dev_flow/skills/automation/` (js-api-surface, agent-mcp-surface).
