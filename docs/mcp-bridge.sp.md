# MCP Bridge & CLI — Specification  {#SP_MCB}

> **Code:** SP_MCB
> **Status:** draft
> **Created:** 2026-10-01
> **Updated:** 2026-10-01
>
> **Concept:** [C_MCB](./mcp-bridge.concept.md)
> **Depends on:** [SP_MCP](./mcp-server.sp.md)
> **Used by:** [SP_AGS](./agent-skill.sp.md)
> **Plan:** [mcp-bridge.plan.md](./mcp-bridge.plan.md)
>
> This specification defines the `circuitjs-mcp` program. Run without a subcommand it is a stdio MCP server that forwards to an app instance. Run with a subcommand it is a command-line client. The spec covers target resolution, the bridge-owned tools, forwarding, launch, the CLI grammar, output and exit codes. Read it to implement the bridge, to configure a stdio host, or to script the app.

## Contents

- [01. Data Structures](#SP_MCB_01) — options, target, bridge tool results, CLI output
- [02. Contracts](#SP_MCB_02) — stdio server mode, bridge tools, CLI subcommands
- [03. Validation Rules](#SP_MCB_03) — target resolution, launch, forwarding rules
- [04. State Transitions](#SP_MCB_04) — bridge session target state
- [05. Verification Criteria](#SP_MCB_05) — functional, invariant, integration, edge-case checks
- [06. Reversibility](#SP_MCB_06) — uninstall

## 01. Data Structures  {#SP_MCB_01}

> Implements: [C_MCB_02](./mcp-bridge.concept.md#C_MCB_02)

### 01_01. Options  {#SP_MCB_01_01}

Options can be given as command-line flags or as environment variables. When both are set, the flag wins. The two target selectors count as one option: a `--url` or `--instance` flag hides both `CIRCUITJS_MCP_URL` and `CIRCUITJS_MCP_INSTANCE`, so an inherited URL never overrides an explicit `--instance`. Boolean env values `1`, `true` and `yes` are true.

| Flag | Env | Type | Default | Description |
|------|-----|------|---------|-------------|
| `--url <url>` | `CIRCUITJS_MCP_URL` | string | — | Explicit endpoint; skips the registry |
| `--instance <id>` | `CIRCUITJS_MCP_INSTANCE` | string | — | Instance ID from the registry |
| `--launch` | `CIRCUITJS_MCP_LAUNCH=1` | bool | false | Start the app when no instance is live |
| `--app <path>` | `CIRCUITJS_APP` | string | — (required for launch) | Path of the packaged app executable (e.g. the `CircuitSimulator` binary inside the unpacked package directory) |
| `--registry <dir>` | `CIRCUITJS_MCP_REGISTRY` | string | `<user home>/.circuitjs1/instances` | Instance directory ([SP_MCP_01_02](./mcp-server.sp.md#SP_MCP_01_02)) |
| `--timeout <ms>` | `CIRCUITJS_MCP_TIMEOUT` | int | 130000 | Per-request forward timeout (above the run budget cap) |
| `--launch-timeout <ms>` | — | int | 30000 | Wait for a launched instance's record |

### 01_02. Target  {#SP_MCB_01_02}

`{url: string, instanceId: string?, source: "explicit" | "registry" | "launched"}`; at most one per bridge process. An explicit URL target has no `instanceId`.

### 01_03. InstanceInfo (bridge tool output)  {#SP_MCB_01_03}

`{instanceId, url, title, appVersion, toolsVersion, startedAt, selected: bool}` built from instance records whose `pid` is alive; `url` = the record's first URL.

### 01_04. CLI output  {#SP_MCB_01_04}

- **Stdout.** Exactly one JSON document followed by a newline.
  - `call`: the tool result's `structuredContent`, or `{content: [...]}` when it has none.
  - `read`: the resource contents.
  - `instances`, `tools`: arrays.
- **Stderr.** Human-readable errors only.

Exit codes:
| Code | Meaning |
|------|---------|
| 0 | Success (`call`: result not `isError`) |
| 1 | `call` returned `isError: true` |
| 2 | Usage error (unknown subcommand, bad JSON arguments) |
| 3 | No reachable instance / connection error / timeout |

## 02. Contracts  {#SP_MCB_02}

### 02_01. Stdio server mode  {#SP_MCB_02_01}

Started as `circuitjs-mcp [options]`. It speaks MCP over stdio to the host and serves the current protocol revision supported by the bridge's MCP library. It speaks to the target with the revision the target's `protocolRevisions` advertise.

| Request from host | Behaviour |
|-------------------|-----------|
| initialize / handshake | Resolve the target ([§03_01](#SP_MCB_03_01)), without failing the handshake when none. Answer with `serverInfo {name: "circuitjs-mcp", version}`, capabilities `tools {listChanged: true}`, `resources {listChanged: true}`, and `instructions` = the target's `instructions` (which carry its `toolsVersion`) followed by one sentence on the bridge tools; with no target, the bridge sentence alone |
| tools/list | Target's tools (when a target exists) followed by the bridge tools of [§02_02](#SP_MCB_02_02) |
| tools/call of a target tool | Forward unchanged; return the target's result unchanged |
| tools/call of a bridge tool | Handled locally |
| resources/list, resources/templates/list, resources/read | Forward when a target exists; empty lists / -32002 otherwise |
| Any target tool while no target | Tool result `isError: true` with text `No CircuitJS1 instance. Start the app or call bridge_launch.` |

When the target changes, the bridge sends `notifications/tools/list_changed` and `notifications/resources/list_changed`.

### 02_02. Bridge tools  {#SP_MCB_02_02}

| Tool | Input | Output (`structuredContent`) | Annotations |
|------|-------|------------------------------|-------------|
| bridge_instances | — | `{instances: InstanceInfo[], target: Target?}` | readOnly, idempotent |
| bridge_select | `instanceId?` \| `url?` (exactly one) | `{target: Target}` | not readOnly, idempotent |
| bridge_launch | `file: string?` | `{target: Target, opened: {doc}?}` | not readOnly, not idempotent |

Errors (as `isError` results with text):
| Condition | Text |
|-----------|------|
| `bridge_select` unknown instance | `Unknown instance <id>. Live instances: <ids>` |
| `bridge_select` unreachable URL | `Cannot reach <url>: <reason>. Live instances: <ids>` |
| `bridge_launch` without a configured app | `No app executable configured. Set --app or CIRCUITJS_APP.` |
| `bridge_launch` app not found (absent or not a regular file) | `App executable not found: <absolute path>.` |
| `bridge_launch` spawn failure | `Cannot start the app <path>: <code>.` (e.g. `EACCES`) |
| `bridge_launch` timeout | `The app started but no instance record appeared within <ms> ms.` |

`bridge_launch` (and CLI `launch`) with `file` calls the target's `circuit_file` tool with `{action: "open", path: <absolute file path>, into: "new", activate: true}` after the instance is live.

### 02_03. CLI subcommands  {#SP_MCB_02_03}

Grammar:

    circuitjs-mcp [options]                          # stdio server mode
    circuitjs-mcp instances [options]
    circuitjs-mcp tools [options]
    circuitjs-mcp call <tool> [<json-args> | -] [options]   # "-" reads arguments from stdin
    circuitjs-mcp read <uri> [options]
    circuitjs-mcp launch [<file>] [options]
    circuitjs-mcp --help | --version

| Subcommand | Behaviour | Output |
|------------|-----------|--------|
| instances | List live instances | `InstanceInfo[]` |
| tools | Resolve target; list its tools | `{name, title, annotations}[]` |
| call | Resolve target (honours `--launch`); call one tool | §01_04 |
| read | Resolve target; read one resource | resource contents |
| launch | Start the app (and open `file`); wait for its record | `Target` (+ `{doc}`) |

## 03. Validation Rules  {#SP_MCB_03}

### 03_01. Target resolution  {#SP_MCB_03_01}

Processing logic:

    FUNCTION resolveTarget(opts):
        IF opts.url: probe(opts.url); RETURN {url, source: explicit}           # probe failure → error listing live registry instances; no automatic fallback
        records ← readRegistry(opts.registry); delete records whose pid is not alive
        IF opts.instance: RETURN that record or error "Unknown instance"
        IF records non-empty: RETURN the record with the latest startedAt (source: registry)
        IF opts.launch: RETURN launch(opts)
        RETURN none

The probe is an MCP handshake against the URL, bounded at 3 s.

**Registry records.** Only regular files named `<pid>-<startedAtMs>.json` (and their `.json.tmp` staging files) are records; a record counts only when its content's `instanceId` equals the name stem and its `pid` equals the name's pid. Cleanup deletes a record only when that pid is not alive (a permission error counts as alive) and never touches other files, symbolic links or directories. Liveness is checked in the bridge's own process namespace, so the bridge and the app must run in the same PID namespace (not one inside a Flatpak/snap sandbox or a container and the other outside).

### 03_02. Launch  {#SP_MCB_03_02}

- **Start.** Spawn `opts.app` detached, with no arguments and with stdout/stderr ignored. Note the time of the spawn.
- **Wait.** Poll the registry every 250 ms for a record with `startedAt` at or after the spawn time, until `--launch-timeout`.
- **After launch.** The launched app stays running when the bridge exits.

### 03_03. Forwarding rules  {#SP_MCB_03_03}

- Tool names, arguments, results and resource contents are forwarded byte-for-byte as JSON values. The bridge never edits `structuredContent`.
- Each forwarded request is bounded by `--timeout`. A timeout returns an `isError` result `Timed out after <ms> ms` and keeps the target.
- A connection failure clears the target and returns `isError` `Instance gone: <url>`. The next call re-resolves ([§03_01](#SP_MCB_03_01)).
- Bridge tool names (`bridge_*`) never collide with target tools (`circuit_*`).

## 04. State Transitions  {#SP_MCB_04}

### 04_01. Bridge session target  {#SP_MCB_04_01}

    [no target] --resolve/select/launch ok--> [target] --connection failure--> [no target]
    [target] --bridge_select other--> [target']        (list_changed notifications)

| From | To | Condition | Side effects |
|------|----|-----------|-------------|
| no target | target | Resolution, `bridge_select` or `bridge_launch` succeeds | Fetch target tool list; send list_changed |
| target | no target | Connection failure | Send list_changed |
| target | target' | `bridge_select` of another instance | Send list_changed |

## 05. Verification Criteria  {#SP_MCB_05}

### 05_01. Functional Expectations  {#SP_MCB_05_01}

| Contract | Scenario | Input | Expected outcome |
|----------|----------|-------|------------------|
| Stdio mode | Claude Desktop config with `circuitjs-mcp` | app running | Tools of the app + 3 bridge tools listed |
| Stdio mode | No app, no `--launch` | — | Handshake ok; only bridge tools; target tool calls return the "No CircuitJS1 instance" error |
| bridge_launch | App installed | `file: example.txt` | Target set; a new document holds the file |
| CLI call | Valid | `call circuit_types '{"type":"Resistor"}'` | Exit 0; stdout is the OperationResult whose `data` is the TypeInfo |
| CLI call | Domain error | `call circuit_edit '{"edits":[{"op":"delete","id":"X9"}]}'` | Exit 1; issues on stdout |
| CLI call | Bad JSON args | `call circuit_get '{'` | Exit 2 |
| CLI | No instance | `tools` without app | Exit 3 |
| CLI instances | Two live, one stale record | `instances` | Exit 0; 2 entries; stale record deleted |
| CLI read | Catalogue | `read circuitjs://catalogue` | Exit 0; type index JSON |
| CLI launch | With file | `launch /abs/rc.txt --app <exe>` | Exit 0; Target and `{doc}` printed; the app shows the circuit |
| bridge_select | Unknown instance | `instanceId: "x"` | `isError`; text lists live instance IDs |
| bridge_select | Unreachable URL | `url: http://127.0.0.1:9/mcp` | `isError`; `Cannot reach …` |
| bridge_select | Both given | `instanceId` and `url` | JSON-RPC -32602 |
| bridge_launch | No app configured | — | `isError`; `No app executable configured…` |

### 05_02. Invariant Checks  {#SP_MCB_05_02}

| Invariant | Verification method |
|-----------|-------------------|
| Forwarding is transparent | For each SP_MCP §05_01 tool case, bridge result equals direct result |
| Bridge never defines circuit tools | `tools` output minus `bridge_*` equals the target's `tools/list` |

### 05_03. Integration Scenarios  {#SP_MCB_05_03}

| Scenario | Preconditions | Steps | Expected result |
|----------|--------------|-------|-----------------|
| Two windows | Two app instances | `bridge_instances` → `bridge_select` the older → `circuit_documents list` | Documents of the selected instance |
| App closed mid-session | Target live | Close the app; call a tool; restart app; call again | First call `Instance gone`; second call succeeds on the new instance |
| Eval harness | App live | Skill eval checker runs `call circuit_connectivity` | JSON parsed by the checker; exit code 0 |

### 05_04. Edge Cases and Boundaries  {#SP_MCB_05_04}

| Case | Input | Expected behavior |
|------|-------|-------------------|
| Stale records only | All pids dead | Records deleted; treated as no instance |
| Explicit URL unreachable | `--url http://10.0.0.9:7311/mcp` down | Error naming the URL and the live registry instances (the registry is read for the message only; no fallback) |
| Arguments from stdin | `call circuit_import -` with a large circuit on stdin | Same as inline arguments |
| Run longer than default timeout | `circuit_run budgetMs=120000` | Completes (timeout 130 s > budget cap) |

## 06. Reversibility  {#SP_MCB_06}

### 06_01. Rollback Strategy  {#SP_MCB_06_01}

| Aspect | Rollback approach |
|--------|-------------------|
| Data/state changes | None owned; it only reads and cleans instance records |
| Artifacts | The installed command and the host configuration entries the user added |
| Dependent modules | [SP_AGS](./agent-skill.sp.md) eval checkers use the CLI |
| External contracts | CLI grammar, exit codes and bridge tool names are user-facing; a breaking change bumps the major version |

## Changelog

| Date | Change |
|------|--------|
| 2026-10-01 | Initial version |
| 2026-10-01 | Review round 1: `--app` has no PATH default, `into` argument name, CLI output wording, verification gaps |
| 2026-10-02 | PL_MCB Phase 1: target selectors as one option, boolean env values, spawn-failure error, absolute app path, explicit-URL target without instanceId; registry record and cleanup rule, shared PID namespace |
