# Implementation Plan: MCP Bridge & CLI  {#PL_MCB}

> **Code:** PL_MCB
> **Status:** draft
> **Created:** 2026-10-01
> **Updated:** 2026-10-01
>
> **Concept:** [C_MCB](./mcp-bridge.concept.md)
> **Specification:** [SP_MCB](./mcp-bridge.sp.md)
> **Depends on:** [PL_MCP](./mcp-server.plan.md) (Phase 1 endpoint and registry; Phase 2 for real tools; Phase 4 harness for this plan's Phase 4)
> **Used by:** [PL_AGS](./agent-skill.plan.md)
>
> Builds `circuitjs-mcp`, a Node package that is both a stdio MCP server forwarding to an app instance and a command-line client. It is installed from the repository and is not published.

## Goal

For this plan's scope ([task_E_AGT](../.dev_flow/tasks/task_E_AGT.md)): the "MCP client" deliverable. When this plan is complete:
- Claude Desktop and other stdio hosts reach the app's tools through `circuitjs-mcp`;
- scripts and the skill's evals call any tool from a shell with JSON output and exit codes;
- the SP_MCB_05 criteria pass.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Runtime | Node ≥ 20, JavaScript ES modules, JSDoc types | Runs on the host's Node, not the app's; no TypeScript build step for a small package |
| Location | `mcp/bridge/` with its own `package.json` (`name: circuitjs-mcp`, `bin: circuitjs-mcp`) | Separate install surface from the app package |
| MCP library | Official MCP TypeScript SDK, current major, for both the stdio server side and the Streamable HTTP client side; exact version pinned in Phase 1 | Current protocol revision for stdio hosts without our own protocol code |
| Distribution | `npm install -g ./mcp/bridge` (or `npx --prefix`); not published to the npm registry | Publishing is an outward action the developer has not requested (backlog) |
| Tests | `node --test` unit tests in `mcp/bridge/test/` against a fake HTTP MCP server; end-to-end rows run by `tests/mcp/e2e.mjs` against a real instance | Unit + mock for the bridge logic; real forwarding in the PL_MCP harness |

## Required Knowledge

| Kind | Ref | Applies to | Note |
|------|-----|-----------|------|
| skill (apply) | automation/agent-mcp-surface | all | host behaviour (Claude Desktop stdio-only, env inheritance) |
| rule | RULE_STYLE_008 (spirit) | all | errors to stderr, data to stdout only |

## Progress

- [ ] [Phase 1 — Package, options, registry and target resolution](#PL_MCB_P1)
- [ ] [Phase 2 — Stdio forwarding, bridge tools, launch](#PL_MCB_P2)
- [ ] [Phase 3 — CLI subcommands](#PL_MCB_P3)
- [ ] [Phase 4 — Tests and host snippets](#PL_MCB_P4)

## Phases

### Phase 1 — Package, options, registry and target resolution [TODO]  {#PL_MCB_P1}

**Depends on:** PL_MCP Phase 1
**Implements:** [SP_MCB_01_01](./mcp-bridge.sp.md#SP_MCB_01_01), [SP_MCB_01_02](./mcp-bridge.sp.md#SP_MCB_01_02), [SP_MCB_01_03](./mcp-bridge.sp.md#SP_MCB_01_03), [SP_MCB_03_01](./mcp-bridge.sp.md#SP_MCB_03_01), [SP_MCB_03_02](./mcp-bridge.sp.md#SP_MCB_03_02)
**Verify:** [SP_MCB_05_04](./mcp-bridge.sp.md#SP_MCB_05_04) stale records only, explicit URL unreachable — plus: unit tests for option precedence (flag over env) and latest-instance selection

What to create:
| Entity | Module | Purpose |
|--------|--------|---------|
| Package | `mcp/bridge/package.json`, `bin/circuitjs-mcp.js` | Entry point, SDK pin, `engines.node >= 20` |
| Options | `mcp/bridge/src/options.js` | Flags + env |
| Registry reader | `mcp/bridge/src/registry.js` | Read records, pid liveness, delete stale |
| Target | `mcp/bridge/src/target.js` | Resolution order, 3 s probe |
| Launch | `mcp/bridge/src/launch.js` | Detached spawn, 250 ms registry poll |

### Phase 2 — Stdio forwarding, bridge tools, launch [TODO]  {#PL_MCB_P2}

**Depends on:** Phase 1; PL_MCP Phase 2
**Implements:** [SP_MCB_02_01](./mcp-bridge.sp.md#SP_MCB_02_01), [SP_MCB_02_02](./mcp-bridge.sp.md#SP_MCB_02_02), [SP_MCB_03_03](./mcp-bridge.sp.md#SP_MCB_03_03), [SP_MCB_04_01](./mcp-bridge.sp.md#SP_MCB_04_01)
**Verify:** [SP_MCB_05_01](./mcp-bridge.sp.md#SP_MCB_05_01) rows Stdio mode (with and without app), bridge_launch, bridge_select (unknown, unreachable, both given, no app configured); [SP_MCB_05_02](./mcp-bridge.sp.md#SP_MCB_05_02) both invariants ("never defines circuit tools" via stdio `tools/list`; its CLI form in Phase 3); [SP_MCB_05_03](./mcp-bridge.sp.md#SP_MCB_05_03) two windows, app closed mid-session; [SP_MCP_05_03](./mcp-server.sp.md#SP_MCP_05_03) bridge forwarding

What to create:
| Entity | Module | Purpose |
|--------|--------|---------|
| Stdio server | `mcp/bridge/src/stdio-server.js` | Handshake with forwarded instructions, tool/resource mirroring, list_changed |
| Forwarder | `mcp/bridge/src/forward.js` | Byte-for-byte forwarding, timeout, instance-gone handling; the client side pins its protocol revision to the newest one in the target record's `protocolRevisions` (never relying on the stateless revision the app may not serve), tested against the fake server |
| Bridge tools | `mcp/bridge/src/bridge-tools.js` | `bridge_instances`, `bridge_select`, `bridge_launch` |

### Phase 3 — CLI subcommands [TODO]  {#PL_MCB_P3}

**Depends on:** Phase 2
**Implements:** [SP_MCB_01_04](./mcp-bridge.sp.md#SP_MCB_01_04), [SP_MCB_02_03](./mcp-bridge.sp.md#SP_MCB_02_03)
**Verify:** [SP_MCB_05_01](./mcp-bridge.sp.md#SP_MCB_05_01) rows CLI call (valid, domain error, bad JSON), CLI no instance, CLI instances, CLI read, CLI launch; [SP_MCB_05_02](./mcp-bridge.sp.md#SP_MCB_05_02) "never defines circuit tools" via the `tools` subcommand; [SP_MCB_05_04](./mcp-bridge.sp.md#SP_MCB_05_04) arguments from stdin, long run

What to create:
| Entity | Module | Purpose |
|--------|--------|---------|
| CLI | `mcp/bridge/src/cli.js` | Grammar, stdout JSON, exit codes 0–3, `--help`, `--version` |

### Phase 4 — Tests and host snippets [TODO]  {#PL_MCB_P4}

**Depends on:** Phase 3; [PL_MCP Phase 4](./mcp-server.plan.md#PL_MCP_P4) (the harness it extends)
**Implements:** [SP_MCB_05](./mcp-bridge.sp.md#SP_MCB_05) automation, [SP_MCB_06_01](./mcp-bridge.sp.md#SP_MCB_06_01) (uninstall documented in the README)
**Verify:** `npm test` in `mcp/bridge/` passes; the bridge rows of `tests/mcp/e2e.mjs` pass; the Claude Desktop row is checked by hand and recorded as `observed` with date in `mcp/bridge/README.md`

What to create:
| Entity | Module | Purpose |
|--------|--------|---------|
| Unit tests | `mcp/bridge/test/*.test.mjs` + `test/fake-server.mjs` | Resolution, forwarding, timeouts, list_changed, CLI exit codes |
| E2E rows | `tests/mcp/e2e.mjs` (extends PL_MCP harness) | Bridge against a real instance |
| README | `mcp/bridge/README.md` | Install, options, CLI, host configuration |

## Backlog

- Publish `circuitjs-mcp` to the npm registry — return when: the developer decides to distribute it publicly.
- Browser-only build target through a relay (C_MCP excluded it) — return when: a hosted web build of the app is planned.

## Changelog

| Date | Change |
|------|--------|
| 2026-10-01 | Initial version |
