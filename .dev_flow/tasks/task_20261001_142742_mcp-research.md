# Task: Research — app as MCP server, agent client & skill

> **Task ID:** `task_20261001_142742_mcp-research`
> **Created:** 2026-10-01 14:27
> **Last updated:** 2026-10-01 15:05
> **Status:** `done`
> **Contributors:** `main`
> **Autonomy:** `checkpoints`

## Current Work Item

| Field | Value |
|-------|-------|
| **Document** | `spike` — [mcp-agent-bridge.spike.md](../../docs/mcp-agent-bridge.spike.md) |
| **Pipeline phase** | `research` |
| **Traceable ID** | n/a (spike; target concept to be created) |

## Intent

- **Goal (why):** let AI agents create, edit and debug circuits in CircuitJS1 Desktop.
- **Target state:** the app acts as an MCP server; an MCP client and an agent skill exist for driving it.
- **Expected result:** this spike answers transport/hosting, API gaps and tool/skill shape so a concept can be written (inferred).

## Description

Research spike before the concept. Questions and scope are framed in the spike file. — main

## Subtasks

### Subtask: spike investigation
> Author: `main` — Created: 14:27 — Last updated: 15:05 — Status: `done`

**Goal:** answer the three framed questions, persist durable findings as skills.

**Progress:**
- [x] Frame spike (questions, scope, time-box)
- [x] Run three researchers (transport, surface gaps, ergonomics) and synthesize — spike `concluded`
- [x] Persist durable findings → `.dev_flow/skills/automation/` (js-api-surface, agent-mcp-surface)
- [x] Hand off: next phase = epic + concept interview (decision forks listed in spike Conclusion)

**Activity:**
- 15:05 — spike concluded; spot-checked load-bearing claims (Node 18.0.0 runtime, nw.require in page, 4-class setPropertyValue, SVG void) against code
- 14:27 — spike framed; profile dir absent and not git-ignored → no profile writes

## Coordination Notes

## Blocking Issues

{No blockers yet.}

## Relevant Context

- `{s:pin}` Spike verdict: in-app Streamable HTTP (Node 18.0.0 → SDK v1 core + custom transport, or hand-rolled) + stdio bridge `circuitjs-mcp` + Java `AgentApi` facade + netlist-first tools + skill. First step: SDK-on-Node-18.0 prototype (A1 vs A1b).
- Follow-up offered: JS_API.md drift propagate pass (spike Entry 2).
