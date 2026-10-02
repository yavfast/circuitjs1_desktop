# Implementation Plan: Circuit Authoring Skill  {#PL_AGS}

> **Code:** PL_AGS
> **Status:** draft
> **Created:** 2026-10-01
> **Updated:** 2026-10-01
>
> **Concept:** [C_AGS](./agent-skill.concept.md)
> **Specification:** [SP_AGS](./agent-skill.sp.md)
> **Depends on:** [PL_MCP](./mcp-server.plan.md) (Phase 2 tools), [PL_MCB](./mcp-bridge.plan.md) (Phase 3 CLI)
> **Used by:** —
>
> Writes the `circuitjs-circuits` skill (entry, references, host snippets), builds the consistency checks and the eval runner on the bridge CLI, and runs the eval set on two model sizes.

## Goal

For this plan's scope ([task_E_AGT](../.dev_flow/tasks/task_E_AGT.md)): agents that load the skill build, tune and debug circuits through the MCP tools without guessing geometry or tool usage. When this plan is complete, the SP_AGS_05 eval set and consistency checks pass, and the results are recorded per skill version.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Location | `mcp/skill/circuitjs-circuits/` (layout of SP_AGS_01_01) | Ships beside the bridge |
| Content format | Markdown per the agent-skill format rules | SP_AGS_01_02 |
| Example circuits | Written as AgentCircuit JSON blocks, produced from live `circuit_get` output of circuits built in the app | Correct by construction; checked by the consistency script |
| Scripts | Node ≥ 20 ES modules calling `circuitjs-mcp` (CLI mode) | One dependency path; the bridge CLI exists for exactly this |
| Eval execution | Agent runs through Claude Code in non-interactive mode (`claude -p` with the skill installed and the server added), one run per scenario × model × repetition, followed by `evals/check.mjs` | Repeatable runs; the checker never trusts the agent's own claims |

## Required Knowledge

| Kind | Ref | Applies to | Note |
|------|-----|-----------|------|
| skill (apply) | automation/agent-mcp-surface | P1 | tool-design conventions, skill format rules |
| skill (apply) | automation/js-api-surface | P1 | pitfalls the diagnostics reference must explain |
| skill (apply) | simulator/time-step-control | P1 | simulation reference rules of thumb |

## Progress

- [x] [Phase 1 — Skill entry, references, host snippets](#PL_AGS_P1)
- [ ] [Phase 2 — Consistency checks](#PL_AGS_P2)
- [ ] [Phase 3 — Eval set and runner](#PL_AGS_P3)
- [ ] [Phase 4 — Eval runs and results](#PL_AGS_P4)

## Phases

### Phase 1 — Skill entry, references, host snippets [DONE]  {#PL_AGS_P1}

**Depends on:** PL_MCP Phase 2
**Implements:** [SP_AGS_01_01](./agent-skill.sp.md#SP_AGS_01_01), [SP_AGS_01_02](./agent-skill.sp.md#SP_AGS_01_02), [SP_AGS_02](./agent-skill.sp.md#SP_AGS_02), [SP_AGS_03_01](./agent-skill.sp.md#SP_AGS_03_01), [SP_AGS_04_01](./agent-skill.sp.md#SP_AGS_04_01), [SP_AGS_06_01](./agent-skill.sp.md#SP_AGS_06_01) (uninstall noted in `hosts/claude-code.md`)
**Verify:** [SP_AGS_03_01](./agent-skill.sp.md#SP_AGS_03_01) size and form limits (line counts, contents tables, one-level links); [SP_AGS_05_04](./agent-skill.sp.md#SP_AGS_05_04) both rows as text checks (the entry tells the agent to compare `toolsVersion` and to stop with connection guidance when no tools are reachable) — plus: every section required by SP_AGS_02_01–02_07 present

What to create:
- `SKILL.md`
- `reference/geometry.md`, `reference/elements.md`, `reference/diagnostics.md`, `reference/patterns.md`, `reference/simulation.md`
- `hosts/claude-code.md`, `hosts/claude-desktop.json`

**Result (2026-10-02).**
- **Files.** `mcp/skill/circuitjs-circuits/`: `SKILL.md` (105 lines), `reference/geometry.md` (152), `elements.md` (61), `diagnostics.md` (143), `patterns.md` (237), `simulation.md` (110), `hosts/claude-code.md` (install, connect, uninstall), `hosts/claude-desktop.json` (the SP_AGS_02_07 entry verbatim).
- **Examples from the app.** Ten AgentCircuit blocks (the geometry RC example and nine patterns: divider, RC, RLC, rectifier, common-emitter, inverting, non-inverting, 555 astable, AND gate + LED). Each was built through the bridge CLI against `target/site` under Xvfb with a scratch HOME. The blocks are `circuit_get` output trimmed to the keys the example sets. Every block re-imports with `ok` and 0 errors, and its stated run reproduces the quoted values (e.g. RC `out` peakToPeak 1.693 V, 555 67.9 Hz / 0.527, CE gain 20.1, LED 9.75 mA).
- **Derived-pin examples** (NPN, op-amp, 555) are real `circuit_edit` replies.
- **Polarity.** The skill states no pin-name polarity. It tells agents to verify on a known case and to orient sources `start` = ground side, as in every pattern.
- **Verify.** A scratch check (766 checks, all pass) covers:
  - line limits, contents tables and one-level links;
  - the frontmatter, the SP_AGS_02_01 section order and contents, the 05_04 text rows, and every section of 02_02–02_07;
  - issue-code coverage: 55 codes parsed from SP_AGA §03_04–03_06 plus `result_too_large`, 55 rows;
  - tool names against `circuitjs-mcp tools`;
  - every element row's type, aliases, pins, geometry, size and property keys against `circuitjs://catalogue`;
  - whole-cell coordinates, and the live import of every block.
- **Deviations.**
  - The elements table has 26 rows, the SP_AGS_02_03 required list (spec wording aligned 2026-10-02).
  - The compatibility line and the no-tools guidance are two short paragraphs inside "Purpose and scope", which keeps the six-section order.
  - Checklist step 1 (clarify) names no tool.
  - Example blocks drop the non-default `flags` the app adds on import. The blocks import identically without them.

### Phase 2 — Consistency checks [TODO]  {#PL_AGS_P2}

**Depends on:** Phase 1; PL_MCB Phase 3
**Implements:** [SP_AGS_03_02](./agent-skill.sp.md#SP_AGS_03_02), [SP_AGS_05_03](./agent-skill.sp.md#SP_AGS_05_03)
**Verify:** `node mcp/skill/tools/check-consistency.mjs` passes against a running instance; a deliberately misspelled type name in a scratch copy makes it fail

What to create:
| Entity | Module | Purpose |
|--------|--------|---------|
| Consistency script | `mcp/skill/tools/check-consistency.mjs` | Catalogue names (from `circuitjs://catalogue`), example imports, issue-code coverage against the code lists parsed from `docs/agent-api.sp.md` §03_05/§03_06 and the server codes of `docs/mcp-server.sp.md` §03_03, tool coverage (from `circuitjs-mcp tools`) |

### Phase 3 — Eval set and runner [TODO]  {#PL_AGS_P3}

**Depends on:** Phase 2
**Implements:** [SP_AGS_01_03](./agent-skill.sp.md#SP_AGS_01_03), [SP_AGS_05_01](./agent-skill.sp.md#SP_AGS_05_01), [SP_AGS_05_02](./agent-skill.sp.md#SP_AGS_05_02)
**Verify:** `evals/check.mjs` scores a hand-built correct circuit of each scenario as PASS and a hand-broken one as FAIL; [SP_MCB_05_03](./mcp-bridge.sp.md#SP_MCB_05_03) eval harness row (checker parses CLI output, exit code 0)

What to create:
| Entity | Module | Purpose |
|--------|--------|---------|
| Scenarios | `evals/evals.json` | The four SP_AGS_05_01 scenarios with checks |
| Fixtures | `evals/fixtures/fix-broken-amp.txt`, `evals/fixtures/tune-divider.txt` | Starting circuits |
| Checker | `evals/check.mjs` | Loads fixtures into new documents, appends the document handle to prompts, evaluates checks with `reset: true` |
| Run driver | `evals/run.mjs` | Runs `claude -p` per scenario × model × repetition, then the checker; writes `evals/results.md` |

### Phase 4 — Eval runs and results [TODO]  {#PL_AGS_P4}

**Depends on:** Phase 3; PL_AGA, PL_MCP, PL_MCB complete
**Implements:** [SP_AGS_05_02](./agent-skill.sp.md#SP_AGS_05_02) pass rule, [SP_AGS_05_05](./agent-skill.sp.md#SP_AGS_05_05) integration scenarios
**Verify:** `evals/results.md` shows every scenario passing in ≥ 2 of 3 runs on each of two model sizes; the Claude Code and Claude Desktop integration rows recorded as `observed` with date

What to do:
- Run the eval set. This spends model usage, so it needs the developer's go-ahead at that time.
- Iterate on the skill text until the pass rule holds.
- Record the results.

## Backlog

- Template library as MCP resources (prior-art suggestion) — return when: evals show agents failing on circuits that a template would cover.
- Additional eval scenarios (logic, 555, op-amp filters) — return when: the four core scenarios pass stably.

## Changelog

| Date | Change |
|------|--------|
| 2026-10-01 | Initial version |
| 2026-10-02 | Phase 1 done: skill entry, references, host snippets |
