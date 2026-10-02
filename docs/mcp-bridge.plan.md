# Implementation Plan: MCP Bridge & CLI  {#PL_MCB}

> **Code:** PL_MCB
> **Status:** draft
> **Created:** 2026-10-01
> **Updated:** 2026-10-02
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
| MCP library | Official MCP TypeScript SDK 1.x (`@modelcontextprotocol/sdk`, pinned 1.31.0 — the same pin as the in-app server), for both the stdio server side and the Streamable HTTP client side | Current protocol revision for stdio hosts without our own protocol code; one SDK line across server and bridge (PL_MCP_DEC_01). The v2 split packages (`@modelcontextprotocol/{client,server}` 2.x) are a later migration for both together (backlog) |
| Distribution | `npm install -g ./mcp/bridge` (or `npx --prefix`); not published to the npm registry | Publishing is an outward action the developer has not requested (backlog) |
| Tests | `node --test` unit tests in `mcp/bridge/test/` against a fake HTTP MCP server; end-to-end rows run by `tests/mcp/e2e.mjs` against a real instance | Unit + mock for the bridge logic; real forwarding in the PL_MCP harness |

## Required Knowledge

| Kind | Ref | Applies to | Note |
|------|-----|-----------|------|
| skill (apply) | automation/agent-mcp-surface | all | host behaviour (Claude Desktop stdio-only, env inheritance) |
| rule | RULE_STYLE_008 (spirit) | all | errors to stderr, data to stdout only |

## Progress

- [x] [Phase 1 — Package, options, registry and target resolution](#PL_MCB_P1)
- [ ] [Phase 2 — Stdio forwarding, bridge tools, launch](#PL_MCB_P2)
- [ ] [Phase 3 — CLI subcommands](#PL_MCB_P3)
- [ ] [Phase 4 — Tests and host snippets](#PL_MCB_P4)

## Phases

### Phase 1 — Package, options, registry and target resolution [DONE]  {#PL_MCB_P1}

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

**Result (2026-10-02).**
- **Package.** `mcp/bridge/package.json`: `name: circuitjs-mcp`, version 0.1.0, `private: true` (guards against an accidental publish; `npm install -g ./mcp/bridge` still works), `type: module`, `bin: circuitjs-mcp → bin/circuitjs-mcp.js`, `engines.node >= 20`, `files: bin/, src/`, license GPL-2.0-or-later. Dependencies pinned exactly like the root: `@modelcontextprotocol/sdk` 1.31.0, `zod` 4.6.5 (SDK peer). The registry's `latest` of `@modelcontextprotocol/sdk` is 1.31.0; SDK v2 ships as separate `@modelcontextprotocol/{client,server}` 2.2.0 packages (Node ≥ 20) and was not adopted here. `npm install` inside `mcp/bridge/` gives its own `node_modules/`; the lockfile and `node_modules/` are git-ignored by the root `.gitignore`. `npm test` there = `node --test test/`; no root script was added.
- **Modules.**
  - `src/options.js` — `parseArgs(argv, env, {homedir, cwd})` → `{options, positionals, help, version}`; `UsageError` (`exitCode` 2); `DEFAULT_TIMEOUT_MS` 130000, `DEFAULT_LAUNCH_TIMEOUT_MS` 30000, `defaultRegistryDir()`. `--flag value` and `--flag=value`; `--` ends the options; `-` stays a positional (stdin arguments, Phase 3). `--registry` is resolved against the cwd; `--url` must be http(s); ints are 1..2^31-1 (setTimeout limit); a variable is not parsed when its flag is given. A selector flag (`--url` or `--instance`) hides both selector variables, so an inherited `CIRCUITJS_MCP_URL` cannot override `--instance` (resolution checks the URL first). This refines the per-option precedence of SP_MCB_01_01.
  - `src/registry.js` — `readRegistry(dir, {isAlive})` → `{records (live, usable, oldest first), removed}`. It considers only regular files (`lstat`, no symlinks) named `^(\d+)-(\d+)\.json(\.tmp)?$`. A record counts, and may be deleted, only when its `instanceId` equals the name stem and its `pid` the name's pid; a temp file is deleted when its name's pid is dead. Unparseable or mismatching files stay; the directory is never created (`--registry` may point at any directory). `parseRecordName`. `pidAlive`, `latestRecord` (latest `startedAt`, tie by `instanceId`), `findRecord`, `instanceInfo(record, target)` (SP_MCB_01_03; `selected` by `instanceId`, else by URL), `liveIdsText` (`"a, b"` or `"none"`), `startedAtMs`.
  - `src/target.js` — `resolveTarget(opts, deps)` → `Resolution {target, record, handshake?}` or `null`; `probe(url, {timeoutMs})` (SDK `Client` + `StreamableHTTPClientTransport`, initialize + initialized, raced against a timer, connection closed after) → `{protocolVersion, serverInfo, instructions, capabilities}`; `PROBE_TIMEOUT_MS` 3000; `targetOf(record, source)`; `reasonOf(error)` (socket code such as `ECONNREFUSED`; `HTTP <status> (<body>)` for an SDK HTTP error; else the innermost cause message, e.g. `bad port`).
  - `src/launch.js` — `launch(opts, deps)` → `Resolution` with `source: 'launched'`; `LAUNCH_POLL_MS` 250. Spawn detached, no arguments, `stdio: 'ignore'`, `unref()`; waits for the `spawn` event. Takes the latest record with `startedAt` ≥ the spawn time.
  - `src/errors.js` — `BridgeError(code, message)`; codes `unreachable`, `unknown_instance`, `no_app`, `app_not_found`, `app_spawn_failed`, `launch_timeout`; messages are the SP_MCB_02_02 texts.
  - `src/version.js` — `NAME`, `VERSION` from `package.json`.
  - `bin/circuitjs-mcp.js` — parses options; `--help` (grammar + option table) and `--version` to stdout; usage errors to stderr with exit 2. Stdio mode and subcommands report "not implemented yet" (exit 2) until Phases 2 and 3.
- **Tests.** `mcp/bridge/test/`: `options`, `registry`, `target`, `launch` `.test.mjs`, `helpers.mjs` (temp registry, records in the app's format) and `fake-server.mjs` (`startFakeServer({instructions, revisions, tools, delayMs, hang, postStatus})` → `{url, port, requests, close}`; JSON-response endpoint like the app's transport: POST → one JSON reply, notification → 202, GET → 405). The launch tests spawn a generated Node script as the "app", which writes a record like the real one. `node --test mcp/bridge/test/`: 42/42 pass, about 1.4 s; temp dirs are removed after each file. Review fix: the server's `mcp/server/src/registry.js` `removeDeadRecords` had the same content-only pid check and now applies the same name rule (unit test in `mcp/server/test/unit.test.js`; `npm run test:mcp` 55 pass / 0 fail / 9 skip).
- **Checked against the real app** (scratch script, `target/site` under Xvfb through a wrapper executable): `--launch --app <wrapper>` → `{source: launched}` after about 5.3 s; a second resolution picks the same record (`source: registry`); `--url` of that record probes with protocol 2025-11-25, `serverInfo {name: circuitjs1, version: 1.3.2}`. After the app was killed (no `unload`), the next resolution deleted its stale record and returned `null`. `--url http://10.0.0.9:7311/mcp` fails after 3.01 s with `Cannot reach …: no MCP answer within 3000 ms. Live instances: none`.
- **Phase 2 hooks.**
  - The SDK 1.31 `Client.connect` always sends `initialize` with `LATEST_PROTOCOL_VERSION` (2025-11-25). Pinning the forwarder's revision to the record's newest `protocolRevisions` entry needs an override (subclass, or rewriting the initialize request in a wrapping transport). The app serves 2025-11-25 today, so the default happens to match.
  - An explicit URL has no record: use `handshake.protocolVersion` / `handshake.instructions` from the Resolution, or reconnect.
  - fetch (undici) refuses some ports outright (`bad port`: 9 among them); the SP_MCB_05_01 `bridge_select` example `http://127.0.0.1:9/mcp` therefore fails with reason `bad port`, not `ECONNREFUSED`. The text still starts `Cannot reach`.
  - `Target` omits `instanceId` for an explicit URL; `instanceInfo` then marks `selected` by URL.
  - Errors carry `code`: Phase 2 maps them to `isError` results; Phase 3 maps them to exit 3.
  - `launch()` while an NW instance of the same app is already running may hand the launch to the running process (NW single-instance behaviour) and produce no new record → `launch_timeout`. `bridge_launch` should decide what to do when an instance is already live.

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

- Migrate the in-app server and the bridge together to the MCP SDK v2 split packages (`@modelcontextprotocol/{client,server}` 2.x) — return when: SDK 1.x stops receiving fixes, or a needed protocol revision (e.g. the stateless 2026-07-28) is only in v2.
- Publish `circuitjs-mcp` to the npm registry — return when: the developer decides to distribute it publicly.
- Browser-only build target through a relay (C_MCP excluded it) — return when: a hosted web build of the app is planned.

## Changelog

| Date | Change |
|------|--------|
| 2026-10-01 | Initial version |
| 2026-10-02 | Phase 1 done: package, options, registry reader, target resolution, launch; Result with Phase 2 hooks |
