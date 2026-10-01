# Task: Agent automation over MCP (epic E_AGT)

> **Task ID:** `task_E_AGT`
> **Created:** 2026-10-01 15:20
> **Last updated:** 2026-10-01 23:59
> **Status:** `in-progress`
> **Contributors:** `main`
> **Autonomy:** `full` — "Продовжуй до повного завершення реалізації цього функціоналу" + answer "No stops" (2026-10-01): commit each phase after review without asking, never push

## Current Work Item

| Field | Value |
|-------|-------|
| **Document** | `plan` — [agent-api.plan.md](../../docs/agent-api.plan.md) Phase 0 and [mcp-server.plan.md](../../docs/mcp-server.plan.md) Phase 0 (prototypes) |
| **Pipeline phase** | `implement` — PL_AGA P0 + PL_MCP P0 done; next PL_AGA P1 |
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
> Author: `main` — Created: 16:20 — Last updated: 22:40 — Status: `done`

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
- 22:40 — status flag corrected to `done` at checkpoint (the phase finished and was committed as 496d9c9; the flag had not been flipped)
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
- [x] Hand off to the implementation subtask

**Activity:**
- 22:10 — open by design: PL_AGA_DEC_01 (closes SP_AGA_DEC_04), PL_MCP_DEC_01 (closes C_MCP_DEC_03) — resolved by the P0 prototypes
- 22:10 — SP_AGA_DEC_04 trigger edited in place (non-breaking) to the reduced prototype sequence

### Subtask: implementation
> Author: `main` — Created: 22:40 — Last updated: 23:59 — Status: `in-progress`

**Goal:** implement PL_AGA, PL_MCP, PL_MCB, PL_AGS in plan order; one commit sign-off per plan phase.

**Progress:**
- [x] PL_AGA Phase 0 prototype built (worktree on `proto/agent-bg-doc`, scratchpad) and measured headless over 5 configurations; result in the plan's Phase 0 block; skill `automation/background-documents` created
- [x] Developer chose A + 4 conditions; PL_AGA_DEC_01 and SP_AGA_DEC_04 `resolved`; Phase 0 `[DONE]`; scratch worktree and branch removed
- [x] Commit sign-off for the Phase 0 record (docs + skill) — given 2026-10-01
- [x] PL_MCP Phase 0 hosting prototype (scratchpad only — no branch needed): SDK 1.31 core + own transport in npm start / packaged / devmode; Claude Code + Inspector connected; developer resolved PL_MCP_DEC_01 = A (closes C_MCP_DEC_03)
- [x] Commit of the PL_MCP Phase 0 record (review FAIL → delta PASS)
- [x] PL_AGA Phase 1 — foundations (review FAIL: circuitArea leak, layering → fixed → delta PASS); also fixed pre-existing tab-switch scope collapse and hint leak
- [x] PL_AGA Phase 2 — element identity and pin names (review PASS; follow-ups applied; also fixed CirSim JSNI Vector→ArrayList signatures)
- [x] PL_AGA Phase 3 — catalogue (review FAIL ×2 → fixed; spec §01_05/§02_01 amended: built-in defaults, English labels, readOnly keys, `add` applies TypeInfo defaults; XNORGate factory key removed)
- [ ] **Next:** PL_AGA Phase 4 — geometry, edits and import

**Activity:**
- 23:59 — developer resolved PL_MCP_DEC_01 = A (SDK core, script-tag loading, no Node crypto)
- 23:59 — PL_MCP P0: release runtime (0.64.1-mod1 normal) Node lacks OpenSSL → `require('crypto')` throws; only the script-tag load works in all 3 modes; Claude Code probes `server/discover` (2026-07-28) then falls back; a stray `"/tmp/chrome/devmode"` dir created in the repo root by the devmode run (quoted manifest arg) was removed; filed in PL_MCP backlog
- 23:58 — developer resolved PL_AGA_DEC_01 = A + 4 conditions (closes SP_AGA_DEC_04)
- 23:55 — PL_AGA P0: today's `bindDocument` starves the active tab when bound per slice (0 % rate); silent field-swap bind + sliders-dialog guard passes R1 at 97.5 %, R2 equal on all §05_02 fields given the per-document hint (condition 3); slices 23–25 ms vs the 20 ms bound (disclosed, Phase 8 proves it); pre-existing tab-switch hint leak filed in the PL_AGA backlog
- 23:55 — P0 verified headless (harness `eval` + probe) instead of by hand in devmode — stronger evidence: every R1 field sampled per call and per slice
- 22:40 — tree at checkpoint: branch `design/agent-mcp`, clean apart from this checkpoint's `.dev_flow/` edits; commits 496d9c9 + f48c7e0 ahead of `master`, not pushed

## Review Rounds

| Round | Scope | Mode | Baseline | Verdict | Carry-over (identity → recurrence) |
|-------|-------|------|----------|---------|-------------------------------------|
| 1 | full (no-baseline; docs-only design unit) | full | working tree 2026-10-01 17:05 | FAIL (9 must, 25 should) | M1–M9, S1–S25 → 1 |
| 2 | delta ∪ carry-over ∪ critical | full (must open) | working tree 2026-10-01 18:10 | FAIL (2 must open: M3/N1, M4/N2; N3–N19 should) | M3, M4 → 2; N3–N19 → 1 |
| 3 | delta ∪ carry-over ∪ critical | full | working tree 2026-10-01 18:55 | FAIL (carry-over all resolved; 3 new must: load-path session writes, lattice contradiction, import caps; S1–S6) | R3-M1..M3, R3-S1..S6 → 1 |
| 4 | delta ∪ carry-over ∪ critical | full | working tree 2026-10-01 19:40 | FAIL (R3-M1 remainder recurring → §03_08 restated as requirements + open DEC_04; new M2 slider seeds as limits; S1–S8) | R3-M1 → 2; R4-M2, R4-S1..S8 → 1 |
| 5 | delta ∪ carry-over ∪ critical | full | working tree 2026-10-01 20:05 | FAIL (R1 not testable; grid-pin contradiction; S1–S6) | R5-M1, R5-M2 → 1 |
| 6 | delta ∪ carry-over | full | working tree 2026-10-01 20:30 | PASS (F1–F3 should) | — |
| 6b | F1–F3 prescribed fixes | confirm (main, mechanical) | working tree 2026-10-01 20:45 | PASS (R1 timing bounds measurable; R1/R2 rows 2 cells; skill grid-sized parts rule present) | — |
| P1 | plans (full) | full | working tree 2026-10-01 21:30 | FAIL (4 must, 15 should) | → 1 |
| P2 | plans delta ∪ carry-over | full | 21:50 | FAIL (N1 must) | N1 → 1 |
| P3 | plans delta | full | 22:05 | PASS (S1, S2 should → applied, confirm) | — |

**Always in scope:** SP_AGA §01_06, §02_04, §02_10, §03_02, §03_04, §03_05, §03_08, §04_01 (declared `Criticality: critical`).

## Coordination Notes

## Blocking Issues

{No blockers yet.}

## Relevant Context

- `{s:pin}` Resolved 2026-10-01: [PL_AGA_DEC_01](../../docs/agent-api.plan.md#PL_AGA_DEC_01) = A, scoped silent bind + 4 conditions (closes [SP_AGA_DEC_04](../../docs/agent-api.sp.md#SP_AGA_DEC_04)); skill `automation/background-documents`. Also resolved 2026-10-01: [PL_MCP_DEC_01](../../docs/mcp-server.plan.md#PL_MCP_DEC_01) = A, SDK 1.x core + own transport, script-tag loading, no Node crypto (closes [C_MCP_DEC_03](../../docs/mcp-server.concept.md#C_MCP_DEC_03)). No open design decisions remain. — main
- `{s:pin}` Spec-level decisions settled by the developer: SP_AGA_DEC_01 one atomic `applyEdits` batch · SP_MCP_DEC_01 14 grouped tools · SP_MCP_DEC_03 file actions on circuit files only (.txt/.json, ≤10 MB, overwrite only empty/circuit files). — main
- Read first on resume — main:
  - [agent-automation.epic.md](../../docs/agent-automation.epic.md) — epic, interview-decision map, acceptance criteria
  - plans [agent-api.plan.md](../../docs/agent-api.plan.md), [mcp-server.plan.md](../../docs/mcp-server.plan.md), [mcp-bridge.plan.md](../../docs/mcp-bridge.plan.md), [agent-skill.plan.md](../../docs/agent-skill.plan.md)
  - specs [agent-api.sp.md](../../docs/agent-api.sp.md) (§03_08 R1/R2, §05_02 R1/R2 rows), [mcp-server.sp.md](../../docs/mcp-server.sp.md)
  - [mcp-agent-bridge.spike.md](../../docs/mcp-agent-bridge.spike.md) — runtime facts (NW 0.64.1-mod1 = Node 18.0.0)
  - [tests/live/README.md](../../tests/live/README.md) — harness the `agent_*` scenarios extend
  - skills `.dev_flow/skills/automation/` — Pitfalls sections

- `{s:pin}` Decisions: C_AGA_DEC_01 grid cells + import & incremental · DEC_02 per-document ID registry · DEC_03 any tab by handle · DEC_04 transactions sealed by commented checkpoints (auto-seal on user edit/save/close/idle) · C_MCP_DEC_01 server per instance + registry · DEC_02 always on, LAN, no token · DEC_03 OPEN protocol layer · C_MCB_DEC_01 stdio bridge + CLI.
- Skills: `.dev_flow/skills/automation/` (js-api-surface, agent-mcp-surface).
