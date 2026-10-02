# Epic: Agent Automation over MCP  {#E_AGT}

> **Code:** E_AGT
> **Status:** draft
> **Created:** 2026-10-01
> **Updated:** 2026-10-01
> **Author:** main
>
> AI agents (Claude Code and other MCP hosts) create, edit and debug circuits in CircuitJS1 Desktop. The application itself is the MCP server. A stdio bridge connects hosts that only speak stdio, and an agent skill teaches the authoring and debugging workflow. Read this epic to see how the four concepts fit together, in what order they are built, and which interview decisions shaped them. Origin: [mcp-agent-bridge.spike.md](./mcp-agent-bridge.spike.md).

## Stakeholders

| Role | Who | Interest |
|------|-----|----------|
| Requester / owner | project developer | Agents build and debug circuits in the desktop app without hand-driving the UI |
| Consumer | AI agents (Claude Code, Claude Desktop, other MCP hosts) | Typed, discoverable tools with actionable results |
| Consumer | app user sharing the session with an agent | The agent's edits are visible, grouped and reversible in the undo history |
| Consumer | test automation (`tests/live`) | A typed control surface in place of `eval`-driven scripts |

## Concepts

| Order | Code | Name | Status | Description |
|-------|------|------|--------|-------------|
| 1 | [C_AGA](./agent-api.concept.md) | Agent API | draft | Application-side operations for agents: stable element identity, grid-cell geometry, incremental edits, connectivity report, bounded runs, probes, diagnostics, checkpoints, documents |
| 2 | [C_MCP](./mcp-server.concept.md) | In-app MCP Server | draft | Streamable HTTP endpoint inside the desktop runtime that exposes the Agent API as MCP tools and resources; always on, local machine by default (private network by setting), one server per app instance with an instance registry |
| 3 | [C_MCB](./mcp-bridge.concept.md) | MCP Bridge & CLI | draft | The "MCP client" deliverable: stdio process that proxies stdio-only hosts to an app instance, discovers or launches instances, and doubles as a command-line client for scripts and tests |
| 4 | [C_AGS](./agent-skill.concept.md) | Circuit Authoring Skill | draft | Agent skill (workflow checklist + references + evals) that teaches agents the grid-cell authoring model, the verify loop and the error→fix mapping |

## Concept Dependencies

    C_AGA ──→ C_MCP ──→ C_MCB
                 └────→ C_AGS   (C_AGS also reads C_AGA's coordinate and error vocabulary)

## Implementation Order

1. C_AGA — every tool is a projection of an Agent API operation; the gaps found by the spike (identity, incremental edits, connectivity, runs, diagnostics) block everything else.
2. C_MCP — depends on C_AGA operations; its hosting prototype also closes [C_MCP_DEC_03](./mcp-server.concept.md#C_MCP_DEC_03) (protocol layer).
3. C_MCB — depends on C_MCP's endpoint and instance registry.
4. C_AGS — written against the live tool set of C_MCP; its evals run through C_MCB's command-line mode or Claude Code directly.

## Interview Decisions (2026-10-01)

The concept interview asked eight questions; each answer is recorded in the concept it shapes.

| Interview | Question | Answer | Record |
|-----------|----------|--------|--------|
| DEC_01 | What is the "MCP client"? | Stdio bridge + CLI | [C_MCB_DEC_01](./mcp-bridge.concept.md#C_MCB_DEC_01) |
| DEC_02 | How is element placement given? | The agent writes coordinates | [C_AGA_DEC_01](./agent-api.concept.md#C_AGA_DEC_01) |
| DEC_05 | Which coordinates and which tools? | Grid cells; whole-circuit import and incremental edits | [C_AGA_DEC_01](./agent-api.concept.md#C_AGA_DEC_01) |
| DEC_03 | Stable element IDs? | Document-scoped ID registry | [C_AGA_DEC_02](./agent-api.concept.md#C_AGA_DEC_02) |
| DEC_04 | Which documents can the agent touch? | Any tab by document handle | [C_AGA_DEC_03](./agent-api.concept.md#C_AGA_DEC_03) |
| DEC_06 | Agent edits in undo history? | Transactions sealed by commented checkpoints | [C_AGA_DEC_04](./agent-api.concept.md#C_AGA_DEC_04) |
| DEC_07 | Several app windows? | Server per instance + instance registry | [C_MCP_DEC_01](./mcp-server.concept.md#C_MCP_DEC_01) |
| DEC_08 | Server activation and protection? | Always on; local machine by default, private network by setting (amended 2026-10-02); no access-control mode | [C_MCP_DEC_02](./mcp-server.concept.md#C_MCP_DEC_02) |

## Acceptance Criteria

- [ ] From Claude Code, an agent builds an RC low-pass filter in a new tab, runs it, and reports the measured cut-off frequency within 10 % of the design value, using only MCP tools.
- [ ] An agent finds and fixes a seeded defect (a floating post, a missing ground) from the connectivity report and diagnostics alone, without a screenshot.
- [ ] The agent's edits appear in the undo history as named checkpoints; the user undoes the last checkpoint with Ctrl+Z and the circuit returns to the previous checkpoint.
- [ ] Claude Desktop reaches the same tools through the stdio bridge.
- [ ] Two app windows are open; the bridge lists both instances and the agent works in the chosen one.
- [ ] The skill's eval scenarios pass on at least two model sizes.
- [ ] [JS_API.md](./JS_API.md), [EXPORT_CJS.md](./EXPORT_CJS.md) and the remote-debug docs are reconciled with the new surface.

## Risks & Mitigations

| # | Risk | Impact | Probability | Mitigation |
|---|------|--------|-------------|------------|
| 1 | The agent mis-places posts, which leaves floating or mid-wire contacts that the simulator silently grounds | high | high | Every edit returns the connectivity report ([C_AGA_03_03](./agent-api.concept.md#C_AGA_03_03)); the skill makes "report clean" a gate before simulating |
| 2 | The runtime's old embedded Node cannot host the stock MCP server libraries | medium | medium | Hosting prototype before the plan; hand-rolled protocol layer as the fallback ([C_MCP_DEC_03](./mcp-server.concept.md#C_MCP_DEC_03)) |
| 3 | A long simulation run freezes the UI (single event loop) | medium | medium | Bounded, chunked runs that yield between slices ([C_AGA_03_04](./agent-api.concept.md#C_AGA_03_04)) |
| 4 | A server opened to the private network (non-default listening address) without access control is driven by an unintended client | medium | low | Accepted by the developer ([C_MCP_DEC_02](./mcp-server.concept.md#C_MCP_DEC_02)). No code-execution tool exists, and file actions are limited to circuit files ([SP_MCP_DEC_03](./mcp-server.sp.md#SP_MCP_DEC_03)). Remaining exposure: such a client can edit circuits (reversible by undo), close documents discarding unsaved work, and save or overwrite circuit files |
| 5 | User and agent edit the same document at the same time | medium | medium | An open agent transaction is sealed when the user edits ([C_AGA_03_05](./agent-api.concept.md#C_AGA_03_05)); agents are steered to their own tab by the skill |

## Success Criteria

- All concepts have `Status: active` and corresponding specs/plans.
- Integration points between concepts are tested.
- No cross-concept conflicts remain.
- All epic-level acceptance criteria are met.

## Changelog

| Date | Change |
|------|--------|
| 2026-10-01 | Initial version from the spike and the concept interview |
