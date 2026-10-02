# Implementation Plan: MCP Bridge & CLI  {#PL_MCB}

> **Code:** PL_MCB
> **Status:** completed
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
- [x] [Phase 2 — Stdio forwarding, bridge tools, launch](#PL_MCB_P2)
- [x] [Phase 3 — CLI subcommands](#PL_MCB_P3)
- [x] [Phase 4 — Tests and host snippets](#PL_MCB_P4)

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

### Phase 2 — Stdio forwarding, bridge tools, launch [DONE]  {#PL_MCB_P2}

**Depends on:** Phase 1; PL_MCP Phase 2
**Implements:** [SP_MCB_02_01](./mcp-bridge.sp.md#SP_MCB_02_01), [SP_MCB_02_02](./mcp-bridge.sp.md#SP_MCB_02_02), [SP_MCB_03_03](./mcp-bridge.sp.md#SP_MCB_03_03), [SP_MCB_04_01](./mcp-bridge.sp.md#SP_MCB_04_01)
**Verify:** [SP_MCB_05_01](./mcp-bridge.sp.md#SP_MCB_05_01) rows Stdio mode (with and without app), bridge_launch, bridge_select (unknown, unreachable, both given, no app configured); [SP_MCB_05_02](./mcp-bridge.sp.md#SP_MCB_05_02) both invariants ("never defines circuit tools" via stdio `tools/list`; its CLI form in Phase 3); [SP_MCB_05_03](./mcp-bridge.sp.md#SP_MCB_05_03) two windows, app closed mid-session; [SP_MCP_05_03](./mcp-server.sp.md#SP_MCP_05_03) bridge forwarding

What to create:
| Entity | Module | Purpose |
|--------|--------|---------|
| Stdio server | `mcp/bridge/src/stdio-server.js` | Handshake with forwarded instructions, tool/resource mirroring, list_changed |
| Forwarder | `mcp/bridge/src/forward.js` | Byte-for-byte forwarding, timeout, instance-gone handling; the client side pins its protocol revision to the newest one in the target record's `protocolRevisions` (never relying on the stateless revision the app may not serve), tested against the fake server |
| Bridge tools | `mcp/bridge/src/bridge-tools.js` | `bridge_instances`, `bridge_select`, `bridge_launch` |

**Result (2026-10-02).**
- **Modules.**
  - `src/forward.js`:
    - `TargetConnection(resolution, {timeoutMs})`: one SDK `Client` over a `PinnedTransport`, a `StreamableHTTPClientTransport` subclass that rewrites the initialize `protocolVersion` to `pinnedRevision(record)`. That revision is the newest entry of `protocolRevisions` the SDK supports; with no record the SDK default applies, and a record with no common revision is a "Cannot reach" error. `connect(3000)` returns the handshake. `request(method, params, {signal, timeoutMs})` goes through `Client.request` with a pass-through `z.looseObject({})` result schema, so there is no output-schema validation and nothing is stripped.
    - Errors from `request`: a timeout is `ForwardError('timeout', 'Timed out after <ms> ms')`; a socket failure (`isConnectionFailure`) is `ForwardError('gone', 'Instance gone: <url>')`; a target JSON-RPC error is rethrown by `passThroughError` with the same code, message and data (the McpError prefix is removed).
    - `BridgeSession(opts, deps)`: `target`, `onChange(fn)`, `ensure({launch})`, `connect(resolution)`, `adopt(conn)`, `drop(conn)`, `forward(conn, method, params, o)`, `liveRecords()`, `close()`. `ensure` resolves once for concurrent callers. For an explicit URL, the probe is the connection's own handshake, so there is no second handshake. `adopt` and `drop` notify only when the target really changes. `NO_INSTANCE_TEXT`.
  - `src/bridge-tools.js`: `BRIDGE_TOOLS` descriptors (open output schemas, annotations per SP_MCB_02_02), `BRIDGE_SENTENCE`, `isBridgeTool`, `callBridgeTool(session, name, args)`, and `launchAndOpen(session, file)` for the CLI `launch` subcommand. `launchAndOpen` throws `BridgeError` / `ForwardError` / `OpenFailed`.
    - Argument checks: unknown argument, a value that is not a non-empty string, `bridge_select` with both or neither selector, or a non-http(s) URL is -32602.
  - `src/stdio-server.js`: `createBridgeServer(session)` and `runStdio(opts)`, an SDK `Server` over `StdioServerTransport`.
    - initialize resolves first (with `--launch`), then answers through `server._oninitialize` with the instructions replaced (pinned SDK internal).
    - tools/call is registered on the base `Protocol`: the SDK `Server` wrapper re-parses results with zod, which would rebuild content blocks.
    - list_changed is sent only after `initialized`.
    - Shutdown on stdin end, transport close, SIGINT or SIGTERM. Diagnostics go to stderr as `circuitjs-mcp: target <url> (<source>, instance <id>)` / `no target`.
  - `bin/circuitjs-mcp.js`: without a subcommand it runs `runStdio`; subcommands still answer "not implemented yet" (Phase 3).
- **Behaviour the spec leaves open** (spec deviations in the phase report):
  - Target tool calls re-resolve with `--launch` honoured. tools/list and resources/* re-resolve without launching.
  - A resolution error on a target tool call (for example an explicit URL that does not answer) is an `isError` result with the `Cannot reach …` text (C_MCB_03_05).
  - Resources: `resources/read` with no target is -32002 `No CircuitJS1 instance… (resource <uri>)`. A timeout is -32001 `Timed out after <ms> ms`. Instance gone is -32603 on read, and an empty list for the list methods.
  - `bridge_launch` uses a running instance (the current target if it is live, else the latest record) and does not start the app. A second start would be handed to the running NW.js process (single instance) and register nothing.
  - When `circuit_file` rejects the file, the result is `isError` with `The instance <id> is running and selected, but opening <abs> failed:` followed by the `circuit_file` text parts. The target stays.
  - The target tool list is not prefetched on a transition (SP_MCB_04_01): tools/list is forwarded on every request.
- **Tests.**
  - `forward.test.mjs`: revision pin, failure classification, unchanged results, timeout keeps the target, gone clears it.
  - `stdio.test.mjs`: the real program as a child process; SDK client over stdio (`test/bridge-client.mjs`).
  - `launch-tool.test.mjs`: an executable fake app that runs `fake-server.mjs` and registers itself.
  - `fake-server.mjs` gains tools/call echo, resources/read, `handle(msg)` and `port`.
  - `node --test mcp/bridge/test/`: 61/61, about 4.8 s.
- **Real app** (scratch HOME and registry, `target/site` under Xvfb, wrapper executable with its own profile per start; script outside the repo), all passed:
  - no app: 3 tools and the "No CircuitJS1 instance" result;
  - `bridge_launch {file: …/lrc.txt}`: launched in about 6 s, `opened {doc: "d2"}`, and the document is active with that `filePath`;
  - tools/list = the 14 app descriptors unchanged + 3 bridge tools;
  - transparency: `circuit_types` (one type and the list), `circuit_edit` domain error, `circuit_get`, `resources/list`, templates and read are deep-equal to direct; a -32602 is passed through with the same code and message;
  - two windows: `bridge_instances` 2 → select the newer, then the older → `circuit_documents list` shows the selected instance's documents; list_changed on each switch;
  - app killed mid-session: `Instance gone: <url>`, the next call re-resolves to the remaining instance and the stale record is deleted. After the last instance: gone, then "No CircuitJS1 instance", then `bridge_launch` and the next call succeed;
  - `circuit_run budgetMs 120000` comes back through the bridge after 120.1 s (`budget_exhausted`) with the default 130 s timeout;
  - the host saw no stdout noise;
  - **Claude Code** 2.1.287: `claude -p --mcp-config <tmp> --strict-mcp-config` with `command: node bin/circuitjs-mcp.js --launch --app <wrapper> --registry <scratch>`. Status connected, 17 tools; `bridge_instances` and `circuit_types` called and answered.
- **Phase 3 hooks.**
  - CLI `call` / `tools` / `read`: `new BridgeSession(opts)` → `ensure()` (honours `--launch`) → `session.forward(conn, 'tools/call' | 'tools/list' | 'resources/read', params)`.
  - Map errors to exit codes: `BridgeError` / `ForwardError` / null → 3; `isError` → 1; a passed-through JSON-RPC error → 2 or 3 (decide per SP_MCB_01_04).
  - CLI `launch [<file>]` reuses `launchAndOpen`.
  - `test/bridge-client.mjs` `raw(method, params, timeoutMs)` defaults to 20 s on the host side; pass a longer one for long runs.
- **Review fixes (2026-10-02).**
  - An HTTP error status from the target (SDK `StreamableHTTPError`) whose body is a JSON-RPC error passes through with code, message and data. Any other body is `ForwardError('http', '<url> answered HTTP <status>: <body>')`: an `isError` result for tools, -32603 for resources; the target is kept.
  - `BridgeSession.launchOnce()`: one launch in flight per session, shared by the `--launch` resolution and `bridge_launch`.
  - `ensure({launch: true})` that meets a resolution without launch resolves again when that one found nothing.
  - A target switch closes the old connection only after its calls in flight have settled (`closeWhenIdle`).
  - A JSON-RPC error of `circuit_file` gives the `…failed: <message>` text. The open-failure text is now one text part.
  - `bridge_launch` `file` must be absolute (-32602).
  - An end-to-end guard: a result with extra keys in content blocks and top-level `_meta` passes through the stdio side unchanged (it fails when tools/call is registered through the SDK `Server`).
  - Tests: 69/69.
  - **App server:** `mcp/server/src/protocol.js` `JsonResponseTransport.send` strips one `MCP error <code>: ` prefix from outgoing error messages (unit test). The bridge's prefix removal concerns the prefix its own SDK client adds, so it works with old and new apps. `npm run test:mcp`: 55 pass / 0 fail / 9 skip.

### Phase 3 — CLI subcommands [DONE]  {#PL_MCB_P3}

**Depends on:** Phase 2
**Implements:** [SP_MCB_01_04](./mcp-bridge.sp.md#SP_MCB_01_04), [SP_MCB_02_03](./mcp-bridge.sp.md#SP_MCB_02_03)
**Verify:** [SP_MCB_05_01](./mcp-bridge.sp.md#SP_MCB_05_01) rows CLI call (valid, domain error, bad JSON), CLI no instance, CLI instances, CLI read, CLI launch; [SP_MCB_05_02](./mcp-bridge.sp.md#SP_MCB_05_02) "never defines circuit tools" via the `tools` subcommand; [SP_MCB_05_04](./mcp-bridge.sp.md#SP_MCB_05_04) arguments from stdin, long run

What to create:
| Entity | Module | Purpose |
|--------|--------|---------|
| CLI | `mcp/bridge/src/cli.js` | Grammar, stdout JSON, exit codes 0–3, `--help`, `--version` |

**Result (2026-10-02).**
- **Module.** `src/cli.js`: `runCli(parsed, {stdin, writeOut, writeErr, prog}, deps)` → exit code. It has one `BridgeSession` per invocation, closed at the end, and the constants `EXIT_OK` 0, `EXIT_TOOL_ERROR` 1, `EXIT_USAGE` 2, `EXIT_UNREACHABLE` 3. `bin/circuitjs-mcp.js` passes the process streams and exits once stdout is flushed, so an idle HTTP socket cannot keep the process alive. `--help` lists the grammar, the options, the output rule and the exit codes.
- **Subcommands.**
  - `instances`: `InstanceInfo[]`; `selected` marks the instance the options would resolve to (`--url` by URL, `--instance`, else the latest). Always exit 0; `[]` when none.
  - `tools`: the target's tools as `{name, title, annotations}`, following `nextCursor`. `title` falls back to `annotations.title`, then null. Bridge tools are not listed.
  - `call <tool> [<json> | -]`: arguments default to `{}` and must be a JSON object (stdin for `-`). Only `call` honours `--launch`. Bridge tool names run in-process through `callBridgeTool`.
  - `read <uri>`: prints the result's `contents` array unchanged.
  - `launch [<file>]`: `launchAndOpen(session, path.resolve(file))`. A relative file resolves against the CLI's cwd. Prints `{target, opened?: {doc}}`. A rejected file exits 1, prints the `circuit_file` result as `call` would, and writes the "…is running and selected, but opening … failed: …" text to stderr.
- **Exit codes and errors.**
  - **0 / 1:** 1 when the result is `isError`; its `structuredContent` (or `{content}`) is still printed.
  - **2:** usage errors (unknown subcommand, wrong operand count, bad option, bad JSON or non-object arguments), checked before any connection. A target JSON-RPC error -32600/-32601/-32602/-32002 also exits 2: the request was wrong.
  - **3:** no instance, `BridgeError`, `ForwardError` (timeout, gone, HTTP), and any other JSON-RPC error.
  - On exit 2/3 stdout stays empty and stderr holds one line, `circuitjs-mcp: <text>`. A JSON-RPC error reads `<tool or uri>: <message> (JSON-RPC <code>)`; the no-instance text is `No CircuitJS1 instance. Start the app, or pass --launch (with --app), or run "circuitjs-mcp launch".`.
- **Tests.**
  - `test/cli.test.mjs` (12) runs the program as a child process against fake endpoints: valid, domain error, no `structuredContent`, bad JSON / usage (7 forms), JSON-RPC 2 vs 3, timeout, 1.2 MB arguments on stdin vs inline, `tools` none / paged, `instances` 2 live + 1 stale, `read` / unknown / unreachable `--url`, a bridge tool via `call`, `launch` with a relative file (and a second `launch` reuses the app), `launch` rejected file, `call --launch`.
  - `test/fake-app.mjs` now holds the executable fake app shared with `launch-tool.test.mjs`.
  - `node --test mcp/bridge/test/`: 81/81, about 13 s.
- **Real app** (scratch HOME and registry, Xvfb; script outside the repo). Every SP_MCB_05_01 CLI row passed:
  - `tools` without app → 3;
  - `launch /abs/lrc.txt --app <wrapper>` → 0, Target + `{doc}`; the active document is lrc.txt;
  - `call circuit_types '{"type":"Resistor"}'` → 0, OperationResult with TypeInfo;
  - `circuit_edit` delete X9 → 1, issues on stdout;
  - `circuit_get '{'` → 2, and an argument the app rejects → 2;
  - `read circuitjs://catalogue` → 0;
  - `instances` with 2 live + 1 stale → 2 entries, the stale record deleted.

  Also passed:
  - SP_MCB_05_02: `tools` (14) equals the target's `tools/list` projection;
  - SP_MCB_05_03 eval row: `call circuit_connectivity` → 0, JSON parsed;
  - SP_MCB_05_04: a 2500-element `circuit_import -` (64 KB) on stdin equals the inline result; `circuit_run budgetMs 120000` → 0 after 120.5 s.
- **Review fixes (2026-10-02).**
  - Every diagnostic is one stderr line: option errors read `<message>; run "circuitjs-mcp --help" for usage`, and newlines inside messages are folded.
  - An unexpected error prints `unexpected error: <message>`; `CIRCUITJS_MCP_DEBUG=1` adds the stack (listed in `--help`).
  - `read` without a `contents` array → exit 3 `<uri>: malformed resources/read result`.
  - A tool name already at the start of a message is not repeated.
  - An interactive stdin (`-` on a TTY) first prints `reading arguments from stdin (end with Ctrl-D)`.
  - `tools` paging stops on a repeated cursor or after 100 pages (exit 3).
  - New tests: connection lost mid-call → 3 `Instance gone`; -32600 / -32601 → 2; a 4 MB `structuredContent` arrives complete; `call bridge_launch` with a relative file → 2; one-line stderr on exit 2/3. 88/88 pass.
- **App performance (outside this plan).** `circuit_import` of a legacy text with N resistors on a grid (all pins dangling) takes 5.9 s for N = 1250 and 51 s for N = 2500, and runs past 130 s for N = 5000 (CLI exit 3, `Timed out after 130000 ms`). N = 60000 ended in `Instance gone` after 67 s. The growth is about cubic, so a "large" circuit on stdin is limited by the app, not by the bridge.
- **Phase 4 hooks.** `runCli` can be driven in-process with fake streams. The e2e bridge rows can run `bin/circuitjs-mcp.js` with `--registry <scratch HOME>/.circuitjs1/instances` and `--app <wrapper>`; a wrapper that sets `HOME` and a profile per start avoids the NW single-instance hand-off.

### Phase 4 — Tests and host snippets [DONE]  {#PL_MCB_P4}

**Depends on:** Phase 3; [PL_MCP Phase 4](./mcp-server.plan.md#PL_MCP_P4) (the harness it extends)
**Implements:** [SP_MCB_05](./mcp-bridge.sp.md#SP_MCB_05) automation, [SP_MCB_06_01](./mcp-bridge.sp.md#SP_MCB_06_01) (uninstall documented in the README)
**Verify:** `npm test` in `mcp/bridge/` passes; the bridge rows of `tests/mcp/e2e.mjs` pass; the Claude Desktop row is checked by hand and recorded as `observed` with date in `mcp/bridge/README.md`

What to create:
| Entity | Module | Purpose |
|--------|--------|---------|
| Unit tests | `mcp/bridge/test/*.test.mjs` + `test/fake-server.mjs` | Resolution, forwarding, timeouts, list_changed, CLI exit codes |
| E2E rows | `tests/mcp/e2e.mjs` (extends PL_MCP harness) | Bridge against a real instance |
| README | `mcp/bridge/README.md` | Install, options, CLI, host configuration |

**Result (2026-10-02).**
- **Unit tests.** The 88 tests of Phases 1–3 cover every automatable SP_MCB_05 row against fake endpoints (`test/fake-server.mjs`, `test/fake-app.mjs`). Phase 4 adds no unit test: there was no gap. `npm test` in `mcp/bridge/`: 88/88.
- **End to end** (`tests/mcp/e2e.mjs`).
  - **Scenario `bridge`** (default group, about 25 s; 12 rows) replaces the "bridge forwarding" SKIP. The bridge runs as a stdio server (SDK client of `mcp/bridge/test/bridge-client.mjs`) and as the CLI.
    - It starts the app itself through `appWrapper(dir, home)`: a shell script that sets the scratch `HOME`/XDG/`DISPLAY` and execs NW.js with `--user-data-dir=<dir>/udd-$$`, so each start has its own profile and NW.js does not hand a second start to the running process.
    - New helpers: `bridgeMissing()` (SKIP with "run npm install in mcp/bridge"), `bridgeCli(args, {home, stdin})` and `rawOf(client)` (pass-through zod schema).
    - Process groups: before `exec`, the wrapper appends `$$` (its pgid as a detached group leader) to `<dir>/groups`. The harness signals only those groups, and only while each still exists and its leader's command line names the scratch dir. Instances started by `bridge_launch` or the CLI are therefore ended at teardown, and also on harness exit, abortRun and SIGINT.
    - Teardown deletes the records the stopped instances leave behind.
    - Rows: SP_MCB_05_01 (no app; bridge tool errors; `bridge_launch` with a file; with the app: tools and instructions; the CLI rows), SP_MCP_05_03 bridge forwarding, SP_MCB_05_02 both invariants (stdio and CLI), SP_MCB_05_03 (eval row; two windows, including the error texts listing both live IDs; app closed mid-session: Instance gone, re-resolution to the other window, then all closed, the app restarted, and the call succeeds on the new instance), SP_MCB_05_04 (stale only, explicit URL unreachable, stdin = inline), and SP_MCB_02_01 (stdout carries the protocol only).
  - **`bridge_long`** (slow): `call circuit_run` with `budgetMs: 120000` → exit 0 after 120.5 s.
  - **`bridge_clients`** (clients): Claude Code 2.1.287 over stdio, with a temporary `--mcp-config` (`command: node mcp/bridge/bin/circuitjs-mcp.js --registry <scratch>`) and `--strict-mcp-config` → connected, 17 tools, and a real `circuit_types` call.
  - **`manual`** now lists the Claude Desktop row (SP_MCB_05_01) as a manual SKIP pointing to `mcp/bridge/README.md`.
  - `npm run test:mcp` (default group): 67 pass / 0 fail / 12 skip.
- **Docs.** `mcp/bridge/README.md` covers install, options and environment, target order, host snippets (Claude Code, Claude Desktop with the PATH note, generic stdio), bridge tools, the CLI with exit codes and examples, security, uninstall (SP_MCB_06_01), tests, and the manual Claude Desktop row (not yet observed: the developer runs it). Both install forms were checked with a scratch `--prefix`: `npm install -g --install-links ./mcp/bridge` (a copy with its dependencies) and `npm install -g ./mcp/bridge` (a link; it needs `npm install` in the folder first). `npm uninstall -g circuitjs-mcp` removes it, and `npx --prefix mcp/bridge circuitjs-mcp` runs in place. `package.json` `files` includes the README.
  - `tests/mcp/README.md`: requirements, scenario rows and the manual row pointer.
  - Root README: a pointer to the bridge in the MCP section.
- **Open.** The Claude Desktop row (SP_MCB_05_01) still has to be checked by hand and recorded as `observed` with a date in the README's "Manual rows" table.

## Backlog

- Migrate the in-app server and the bridge together to the MCP SDK v2 split packages (`@modelcontextprotocol/{client,server}` 2.x) — return when: SDK 1.x stops receiving fixes, or a needed protocol revision (e.g. the stateless 2026-07-28) is only in v2.
- Publish `circuitjs-mcp` to the npm registry — return when: the developer decides to distribute it publicly.
- Browser-only build target through a relay (C_MCP excluded it) — return when: a hosted web build of the app is planned.

## Changelog

| Date | Change |
|------|--------|
| 2026-10-01 | Initial version |
| 2026-10-02 | Phase 1 done: package, options, registry reader, target resolution, launch; Result with Phase 2 hooks |
| 2026-10-02 | Phase 2 done: stdio server, forwarder (revision pin, transparent results, timeout, instance gone), bridge tools; Result with Phase 3 hooks |
| 2026-10-02 | Phase 3 done: CLI subcommands, exit codes, stdin arguments; Result with Phase 4 hooks |
| 2026-10-02 | Phase 4 done: e2e bridge scenarios (default, slow, clients), bridge README with host snippets and uninstall; plan completed (Claude Desktop row left to the developer) |
