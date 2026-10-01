# MCP Bridge & CLI  {#C_MCB}

> **Code:** C_MCB
> **Status:** draft
> **Created:** 2026-10-01
> **Updated:** 2026-10-01
> **Author:** main
> **Owner:** automation tooling maintainers (ships beside the app, outside the app package)
> **Complexity:** low
> **Criticality:** peripheral
>
> **Depends on:** [C_MCP](./mcp-server.concept.md)
> **Used by:** [C_AGS](./agent-skill.concept.md)
> **Epic:** [E_AGT](./agent-automation.epic.md)
> **Spike:** [mcp-agent-bridge.spike.md](./mcp-agent-bridge.spike.md)
> **Specification:** [SP_MCB](./mcp-bridge.sp.md)
> **Plan:** [mcp-bridge.plan.md](./mcp-bridge.plan.md)
>
> The epic's "MCP client" deliverable. It is a small process that an agent host starts over stdio. It finds a running CircuitJS1 instance (or starts one) and forwards MCP traffic to that instance's endpoint. The same program also works as a command-line client for scripts and tests. Read this concept to connect a stdio-only host such as Claude Desktop, to script the app from a shell, or to run the skill's evals.

## Contents

- [1. Philosophy](#C_MCB_01) — why a bridge exists although the app is the server
- [2. Domain Model](#C_MCB_02) — bridge, target instance, bridge-owned tools, CLI invocation
- [3. Mechanisms](#C_MCB_03) — discovery, launch, forwarding, CLI mode, edge cases
- [4. Integration Points](#C_MCB_04) — what it uses and exposes
- [5. Design Decisions](#C_MCB_DEC) — what the "MCP client" is

## 1. Philosophy  {#C_MCB_01}

### 1.1. Core Principle  {#C_MCB_01_01}

In MCP terms the agent host is already the client. What hosts lack is a way in:
- Claude Desktop accepts only servers it starts over stdio.
- Scripts and tests want a plain command, not an MCP session.

The bridge fills both gaps. It holds no circuit logic and defines no circuit tools of its own; it forwards to the app's endpoint ([C_MCP](./mcp-server.concept.md)). The tool set is therefore defined once and cannot drift.

### 1.2. Design Constraints  {#C_MCB_01_02}

- **Transparent forwarding.** Circuit tools and resources pass through unchanged. The bridge adds only the instance-selection tools of [§2.1](#C_MCB_02_01).
- **Host-side runtime.** The bridge runs on the agent host's machine with its own current runtime, not the app's embedded one. It may therefore serve the current MCP protocol revision to stdio hosts while the app serves whatever revision [C_MCP_DEC_03](./mcp-server.concept.md#C_MCP_DEC_03) settles.
- **Distribution.** The bridge is not part of the app package. It ships as a separately installable command together with the [agent skill](./agent-skill.concept.md).
- **Rollback.** Removing the bridge affects only stdio hosts and scripts. Hosts that speak HTTP connect to the app directly.

**This concept IS:** a stdio↔HTTP forwarder, instance discovery and launch, and a command-line client.

**This concept IS NOT:**
- an agent or chat application with its own model loop (rejected in [C_MCB_DEC_01](#C_MCB_DEC_01));
- an in-app chat panel;
- a second definition of the circuit tools.

## 2. Domain Model  {#C_MCB_02}

### 2.1. Key Entities  {#C_MCB_02_01}

| Entity | Meaning |
|--------|---------|
| **Bridge session** | One stdio connection from a host; forwards to one target instance at a time |
| **Target instance** | The app endpoint the session forwards to. It is chosen from the instance registry ([C_MCP_03_03](./mcp-server.concept.md#C_MCP_03_03)) or given as an explicit address, for instances on another machine of the private network |
| **Bridge-owned tools** | `list instances`, `select instance`, `launch instance`. They are the only tools not forwarded |
| **CLI invocation** | One shell command that lists tools, calls one tool with arguments, or reads one resource, and prints the structured result |

### 2.2. Data Flows  {#C_MCB_02_02}

```
Stdio host ──stdio──► Bridge ──(registry / explicit address)──► Target instance endpoint
Shell/test ──argv───► Bridge (CLI mode) ──────────────────────► Target instance endpoint ──► printed result
```

## 3. Mechanisms  {#C_MCB_03}

### 3.1. Discovery and selection  {#C_MCB_03_01}

The target is resolved in this order:
1. An explicit address given by option or by the `select instance` tool.
2. The single live instance in the registry.
3. When several instances are live, the bridge picks the most recently started one; `list instances` shows all of them, so the agent can switch.

Stale registry records are skipped.

### 3.2. Launch  {#C_MCB_03_02}

When no instance is live, `launch instance` (or a start-up option) starts the app with an optional circuit file. The bridge waits for the new instance's registry record, bounded by a timeout, then forwards to it.

### 3.3. Forwarding  {#C_MCB_03_03}

- **Catalogue.** The bridge mirrors the target's tool and resource catalogue and adds its own tools to the list.
- **Calls.** It forwards calls and returns results unchanged.
- **Target change.** When the selected instance changes, the bridge notifies the host that the tool list changed.

### 3.4. CLI mode  {#C_MCB_03_04}

- **Commands.** The same program run with a subcommand lists instances or tools, calls one tool with JSON arguments, or reads a resource.
- **Output.** It prints the structured result as JSON.
- **Exit status.** The exit status is non-zero when the call failed or the result was an error result.
- **Uses.** This mode drives the skill's evals and lets shell scripts and the live test harness drive the app without an MCP host.

### 3.5. Edge Cases  {#C_MCB_03_05}

| Case | Behaviour |
|------|-----------|
| No instance and launch not requested | Forwarded calls return an error result that says to start the app or call `launch instance` |
| Target instance exits mid-session | The call fails with "instance gone"; the next call re-resolves the target ([§3.1](#C_MCB_03_01)) |
| Explicit address unreachable | Error result naming the address and listing the live registry instances; the bridge does not switch to them on its own |
| Launch timeout | Error result; the started process is left running for the user |

## 4. Integration Points  {#C_MCB_04}

### 4.1. Dependencies  {#C_MCB_04_01}

- [C_MCP](./mcp-server.concept.md) — the endpoint, the instance registry, and how the app is launched.

### 4.2. API Surface  {#C_MCB_04_02}

- **To stdio hosts:** an MCP server over stdio, carrying the forwarded tools and resources plus the bridge-owned tools.
- **To shells and tests:** a command-line client.
- **To users:** host configuration snippets for Claude Desktop and Claude Code, shipped with the [skill](./agent-skill.concept.md).

## 5. Design Decisions  {#C_MCB_DEC}

### DEC_01 — What is the "MCP client" deliverable?  {#C_MCB_DEC_01}

> **Status:** resolved
> **Date:** 2026-10-01

**Question:** The app is the server and the agent host is the protocol client. What does the requested "MCP client" become? (Interview DEC_01.)

**Options considered:**
| Option | Consequence |
|--------|-------------|
| A — stdio bridge + CLI | Small; serves stdio-only hosts and scripts; tools defined once in-app |
| B — a dedicated agent application with its own model loop | Large: own UI, API keys, conversation handling; duplicates existing hosts |
| C — A plus an in-app chat panel | Largest; the panel needs keys and its own concept |

**Decision:** A — stdio bridge + CLI.
**Rationale:** Existing agent hosts already provide the model loop; the missing pieces are the stdio entry and a scripting entry.
**Rejected because:** B — duplicates Claude Code/Desktop. C — out of proportion for the stated goal.

## Changelog

| Date | Change |
|------|--------|
| 2026-10-01 | Initial version from the spike and the concept interview |
