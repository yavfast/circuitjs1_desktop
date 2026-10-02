# In-app MCP Server  {#C_MCP}

> **Code:** C_MCP
> **Status:** draft
> **Created:** 2026-10-01
> **Updated:** 2026-10-01
> **Author:** main
> **Owner:** app-shell maintainers (desktop runtime integration)
> **Complexity:** medium
> **Criticality:** supporting
>
> **Depends on:** [C_AGA](./agent-api.concept.md), [C_APC](./app-controller.concept.md), [C_USR](./user-preferences.concept.md)
> **Used by:** [C_MCB](./mcp-bridge.concept.md), [C_AGS](./agent-skill.concept.md)
> **Epic:** [E_AGT](./agent-automation.epic.md)
> **Spike:** [mcp-agent-bridge.spike.md](./mcp-agent-bridge.spike.md)
> **Specification:** [SP_MCP](./mcp-server.sp.md)
> **Plan:** [mcp-server.plan.md](./mcp-server.plan.md)
>
> The desktop application acts as an MCP server. It runs a Streamable HTTP endpoint inside its own desktop runtime and exposes the [Agent API](./agent-api.concept.md) as typed MCP tools and resources. Read this concept to connect an agent host, add or change a tool, or understand how several app windows are found. It covers hosting, reachability, the instance registry, the tool/resource projection, result shaping, and the protocol-layer choice.

## Contents

- [1. Philosophy](#C_MCP_01) — why the app itself is the server and which constraints bound it
- [2. Domain Model](#C_MCP_02) — endpoint, instance registry, tools, resources and the request flow
- [3. Mechanisms](#C_MCP_03) — hosting, reachability, registry, tool projection, result shaping, edge cases
- [4. Integration Points](#C_MCP_04) — what it uses and what it exposes to hosts and the bridge
- [5. Design Decisions](#C_MCP_DEC) — instances, activation/protection, protocol layer

## 1. Philosophy  {#C_MCP_01}

### 1.1. Core Principle  {#C_MCP_01_01}

The circuit lives in the running application, so the application itself serves it to agents. There is no external automation process to keep in sync with the editor. An agent host such as Claude Code connects straight to the app's endpoint. Hosts that speak only stdio go through the [bridge](./mcp-bridge.concept.md), which forwards to the same endpoint, so every tool is defined once, here.

### 1.2. Design Constraints  {#C_MCP_01_02}

- **Projection, not logic.** Each tool maps to [Agent API](./agent-api.concept.md) operations and adds no circuit logic of its own. A capability missing from the Agent API is added there, not here.
- **Desktop runtime only.** The endpoint runs in the desktop shell's embedded server-side runtime, which the app page can already reach. A plain browser page cannot listen on a port, so a browser-only build is not an MCP target.
- **Old embedded runtime.** The shell bundles an older server-side runtime version (Node 18.0.0 at the time of the spike; see the `automation/agent-mcp-surface` skill). Stock MCP server libraries partly do not run on it; the library core does, with a custom transport ([C_MCP_DEC_03](#C_MCP_DEC_03)). Everything the endpoint needs ships inside the application package, because the package carries no separately installed modules.
- **Always on, private-network trust** ([C_MCP_DEC_02](#C_MCP_DEC_02)).
  - The server starts with every app instance.
  - It is reachable from the local machine and the private network.
  - It carries no access-control mode.
  - Two bounds remain, neither of which asks anything of agents: requests whose `Origin` header names a foreign web page are rejected (the MCP transport specification requires it, and agents do not send that header), and file actions are limited to circuit files ([SP_MCP_DEC_03](./mcp-server.sp.md#SP_MCP_DEC_03)).
- **No code execution.** No tool evaluates code. The developer-facing remote-debug channel keeps that role.
- **Bounded output.** Agent hosts cap tool output (Claude Code: 25k tokens by default), so results are shaped to fit ([§3.4](#C_MCP_03_04)).
- **Rollback.** The server is an additive layer. Removing it, or switching it off by preference, leaves the editor and the Agent API intact.

**This concept IS:** hosting, reachability, the instance registry, the tool/resource projection and result shaping.

**This concept IS NOT:**
- the circuit operations themselves ([C_AGA](./agent-api.concept.md));
- the stdio bridge ([C_MCB](./mcp-bridge.concept.md));
- the agent workflow guidance ([C_AGS](./agent-skill.concept.md));
- MCP prompts. The skill carries the workflow, so the server exposes no prompt templates.

## 2. Domain Model  {#C_MCP_02}

### 2.1. Key Entities  {#C_MCP_02_01}

| Entity | Meaning |
|--------|---------|
| **Endpoint** | One Streamable HTTP MCP endpoint per app instance, at a port and path |
| **Instance record** | `{instance id, port, listening addresses, process id, app version, started at, window title}`. Written to the instance registry when the endpoint starts and removed when it stops |
| **Instance registry** | A per-user directory of instance records. Readers drop records whose process no longer exists |
| **Tool** | A named, typed operation projected from the Agent API. It carries an input schema, an output schema, behaviour hints (read-only, destructive, idempotent) and a description written for agents |
| **Resource** | Read-only, addressable content: the element catalogue, the current circuit of a document, the example circuits, the circuit format reference |
| **Tool result** | Structured content conforming to the tool's output schema, a short text rendering for hosts without structured support, and image content for renders |

### 2.2. Data Flows  {#C_MCP_02_02}

```
Agent host ──HTTP POST──► Endpoint ──► Origin check ──► protocol layer ──► tool ──► Agent API ──► document
     ▲                                                                                  │
     └───────────── tool result (structured + text [+ image]) ◄──────── result shaping ◄┘

App start ──► pick port ──► listen ──► write instance record     App exit ──► remove instance record
```

## 3. Mechanisms  {#C_MCP_03}

### 3.1. Hosting  {#C_MCP_03_01}

- **Runtime.** The endpoint lives in the app page's embedded server-side runtime. That runtime shares one event loop with the editor and simulator, so a tool reaches the Agent API directly, with no inter-process hop.
- **Pacing.** Long operations rely on the Agent API's sliced runs to keep the UI responsive.
- **Hidden windows.** A window that is hidden or unfocused must not slow agent runs. Run pacing does not depend on display timers ([C_AGA_03_04](./agent-api.concept.md#C_AGA_03_04)), and the shell's background-throttling switches are set where the runtime still throttles.

### 3.2. Reachability and port selection  {#C_MCP_03_02}

- **Port.** Each instance takes the first free port at or above a base port. The base port and the listening address are user preferences.
- **Default reachability.** By default the endpoint is reachable on the local machine and the private network ([C_MCP_DEC_02](#C_MCP_DEC_02)).
- **Showing the address.** The app shows its endpoint address in its info dialog (opened from the Options menu, whose item also shows when the server is off), so the user can give it to an agent on the same or another machine.

### 3.3. Instance registry  {#C_MCP_03_03}

Several windows are several processes, because "New window" starts a new instance. Each instance runs its own endpoint and writes an instance record ([C_MCP_DEC_01](#C_MCP_DEC_01)). The bridge and command-line client read the registry to list instances and pick one. Hosts on another machine skip the registry and use the address the app shows. Document handles stay per instance; one instance never forwards to another.

### 3.4. Tool projection and result shaping  {#C_MCP_03_04}

- **Groups.** Tools follow the Agent API groups: catalogue, documents, build/edit, inspect, simulate, measure, debug, history.
- **Names.** Every tool name carries one common prefix, so hosts that merge several servers keep them apart.
- **Size.** Every tool is designed to stay below the host's output cap:
  - lists are concise by default, with an optional detailed form;
  - large lists paginate;
  - probe series are decimated to a caller-chosen point count within a cap;
  - raw scope buffers are never returned.
- **Errors.** An operation the Agent API rejected (validation, unknown target, busy document) comes back as a **tool result marked as an error**, carrying the issues and their fix hints, so the agent can correct itself. Problems found by a successful operation (connectivity errors after an edit, solver issues met during a run) come back in a normal result's issue list. Protocol-level errors are reserved for malformed requests and unknown tools.
- **Renders.** Images come back as image content, which hosts display inline.
- **Resources.** The element catalogue, each document's current circuit, the example circuit corpus and the circuit format reference are exposed as resources, so agents and the skill read them on demand rather than having them copied into prompts.

### 3.5. Edge Cases  {#C_MCP_03_05}

| Case | Behaviour |
|------|-----------|
| Base port and the next ports are taken | Try a bounded range; if all are busy, the app reports that the server could not start and keeps working without it |
| Stale instance record (process gone) | Readers ignore it and remove it |
| Request with a foreign `Origin` | Rejected as forbidden |
| App instance closes while a host is connected | Its record is removed; the host's next call fails to connect; the bridge reports the instance as gone |
| Output would exceed the cap | Shaped by pagination/decimation; a result never silently truncates mid-structure |

## 4. Integration Points  {#C_MCP_04}

### 4.1. Dependencies  {#C_MCP_04_01}

- [C_AGA](./agent-api.concept.md) — every tool is a projection of its operations.
- [C_APC](./app-controller.concept.md) — the clustered native boundary through which the page reaches the Agent API, and app start-up, where the endpoint starts.
- [C_USR](./user-preferences.concept.md) — the base port, the listening address, and the switch that disables the server.

### 4.2. API Surface  {#C_MCP_04_02}

- **To agent hosts:** an MCP endpoint, with tools and resources as in [§3.4](#C_MCP_03_04).
- **To the bridge and CLI:** the same endpoint, plus the instance registry ([§3.3](#C_MCP_03_03)).
- **To the user:** the endpoint address shown in the app, and preferences for port, address and on/off.

## 5. Design Decisions  {#C_MCP_DEC}

### DEC_01 — What happens with several app windows?  {#C_MCP_DEC_01}

> **Status:** resolved
> **Date:** 2026-10-01

**Question:** Each "New window" is a separate process. How do agents reach each one? (Interview DEC_07.)

**Options considered:**
| Option | Consequence |
|--------|-------------|
| A — a server in every instance, plus a per-user instance registry | Every window is reachable; the bridge lists and selects; stale records need cleanup |
| B — only the first instance on a fixed port serves | Simpler; other windows are unreachable, and which window serves depends on launch order |

**Decision:** A — a server per instance plus an instance registry.
**Rationale:** Every open window stays addressable, and the agent can choose explicitly.
**Rejected because:** B — reachability would depend on launch order.

### DEC_02 — How is the server activated and protected?  {#C_MCP_DEC_02}

> **Status:** resolved
> **Date:** 2026-10-01

**Question:** Off by default and enabled explicitly with a per-run token on loopback only, or always on? (Interview DEC_08.)

**Options considered:**
| Option | Consequence |
|--------|-------------|
| A — off by default, enabled by menu/launch flag, loopback only, per-run token | Smallest exposure; needs a token handover step for every host |
| B — always on | No setup for agents; every running instance listens |

**Decision:** B, with the developer's addition — always on, reachable from the local machine and the private network, and **no access-control mode** (no token). Only the specification-mandated foreign-`Origin` rejection is kept, because it costs agents nothing.
**Rationale:** The app runs on a personal machine and a private network, where the developer sees no realistic attacker. Connecting must take no setup.
**Consequence:** Any private-network client can edit, run, close (discarding unsaved work) and save circuits; file actions are bounded to circuit files ([SP_MCP_DEC_03](./mcp-server.sp.md#SP_MCP_DEC_03)), so no client can read the content of, or overwrite, files that are not circuits.
**Rejected because:** A — the token handover and opt-in add friction with no benefit in the stated environment.

### DEC_03 — Which protocol layer serves MCP inside the old embedded runtime?  {#C_MCP_DEC_03}

> **Status:** resolved
> **Date:** 2026-10-01

**Question:** Should the endpoint use the official MCP server library's core with a custom HTTP transport, or a self-written protocol layer? And which protocol revision should it serve: the legacy initialize-based one, or the current stateless one, or both?

**Options considered:**
| Option | Consequence |
|--------|-------------|
| A — library core (legacy-revision line) + custom HTTP transport, bundled into the package | Spec handling maintained upstream; depends on that line loading on the old runtime; serves the legacy revision, which current hosts negotiate down to |
| B — self-written tools/resources-only protocol layer | No dependencies; we track spec changes ourselves (headers, revision fallback) |
| C — A or B in-app, with the bridge serving the current revision to stdio hosts | Modern-revision compliance where the host runtime allows it |

**Decision:** A in-app — the library core of the legacy-revision line (SDK 1.x) with a custom HTTP transport, bundled into the package; it serves the initialize-based revisions (2025-11-25 and older). Serving the stateless revision stays a backlog item of the plan; option C is not taken here — what the bridge serves to stdio hosts is [C_MCB](./mcp-bridge.concept.md)'s decision.
**Rationale:** The hosting prototype loaded the core on the embedded runtime in all three run modes, and Claude Code and the MCP Inspector connected to the release build and negotiated the legacy revision ([PL_MCP_DEC_01](./mcp-server.plan.md#PL_MCP_DEC_01)).
**Resolved by:** the developer, 2026-10-01, after the PL_MCP Phase 0 prototype.

## Changelog

| Date | Change |
|------|--------|
| 2026-10-01 | Initial version from the spike and the concept interview |
| 2026-10-01 | C_MCP_DEC_03 resolved: A in-app (SDK 1.x core + custom HTTP transport), after the PL_MCP Phase 0 prototype |
