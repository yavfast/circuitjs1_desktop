# Implementation Plan: In-app MCP Server  {#PL_MCP}

> **Code:** PL_MCP
> **Status:** completed
> **Created:** 2026-10-01
> **Updated:** 2026-10-02
>
> **Concept:** [C_MCP](./mcp-server.concept.md)
> **Specification:** [SP_MCP](./mcp-server.sp.md)
> **Depends on:** [PL_AGA](./agent-api.plan.md) Phases 1–8 closed; PL_AGA Phase 9 implemented (it is closed by this plan's Phase 4)
> **Used by:** [PL_MCB](./mcp-bridge.plan.md), [PL_AGS](./agent-skill.plan.md)
>
> Builds the Streamable HTTP MCP endpoint that runs inside the NW.js page on its embedded Node 18.0.0. It also covers the instance registry, the 14 tools and the resources mapped onto `CircuitJS1Agent`, the menu item and info dialog, and an end-to-end harness that drives a real NW.js instance. It opens with a hosting prototype that closes C_MCP_DEC_03.

## Goal

For this plan's scope ([task_E_AGT](../.dev_flow/tasks/task_E_AGT.md)): every running desktop instance is an MCP server that Claude Code reaches with one `claude mcp add --transport http` command. No setup is needed beyond that.

When this plan is complete:
- an agent host lists the 14 `circuit_*` tools and the resources;
- every tool call returns the Agent API result shaped per SP_MCP;
- the SP_MCP_05 criteria pass in the NW.js end-to-end harness.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Server language/runtime | Plain JavaScript, CommonJS, targeting Node 18.0.0 (NW.js `0.64.1-mod1`) | The embedded runtime; ESM in the NW Node context is risky (skill automation/agent-mcp-surface) |
| Source location | `mcp/server/src/*.js` (new top-level `mcp/` for all agent-automation tooling) | Keeps non-GWT code out of `src/main/java` and `war/` |
| Bundling | esbuild (root `devDependency`) → one file `war/scripts/mcp-server.js` (git-ignored, generated) | The NW package ships `target/site` only, without `node_modules`; `war/` content reaches `target/site` through the existing Maven resource copy |
| Build integration | New step in `scripts/dev_n_build.js` running the bundle before the GWT/site copy for `buildgwt`, `buildall`, `fullrebuild` and devmode | One build path; `npm run buildgwt` stays the single gate (RULE_TEST_001) |
| HTTP | Node built-in `http` module | No express/hono (Node 18.0 incompatibility of the stock SDK transport) |
| MCP protocol layer | SDK 1.x core (`Server`) + own JSON-response Streamable HTTP transport; bundle loaded by `<script src="scripts/mcp-server.js">` in the page context; no Node `crypto` (the server issues no session ids; should an id ever be needed, Web Crypto of the page context) — [PL_MCP_DEC_01](#PL_MCP_DEC_01) | Ran in all three run modes and connected Claude Code and the Inspector ([Phase 0](#PL_MCP_P0)) |
| Java side | Start trigger in the existing `client/agent/AgentJsBridge.java`; server status kept in a session object `McpServerStatus` on `CirSim` (client root), which the dialog reads; preferences through `OptionsManager.getOptionFromStorage`/`setOptionInStorage`; dialog `dialog/McpServerDialog.java` | RULE_ARCH_008 (no new JSNI file), RULE_ARCH_001/002 (the dialog reaches status through `CirSim`, not `agent/`), RULE_STRUCT_003 |
| Instance registry | `<home>/.circuitjs1/instances/<instanceId>.json`, mode 0600, write via temp + rename | SP_MCP_01_02 |
| End-to-end tests | `tests/mcp/e2e.mjs` (Node ≥ 22): launches the NW.js SDK binary on `target/site` with a scratch `HOME` directory, waits for the instance record, drives the endpoint with raw JSON-RPC | Headless Chromium has no Node, so only a real NW.js run exercises the server |

## Required Knowledge

| Kind | Ref | Applies to | Note |
|------|-----|-----------|------|
| rule | RULE_ARCH_008 | P1, P3 | JSNI only in `AgentJsBridge` |
| rule | RULE_STRUCT_003, RULE_ARCH_003 | P3 | dialog class and routing |
| rule | RULE_STYLE_003 | P3 | `Locale.LS` for dialog and menu strings |
| rule | RULE_ERR_004 | P2 | tool exceptions → global handler |
| rule | RULE_TEST_001, RULE_TEST_006 | every phase | build gate; live harness unaffected |
| skill (apply) | automation/agent-mcp-surface | all | runtime versions, host behaviour, exposure policy |
| skill (apply) | gwt/build-pipeline | P1 | build step placement |
| skill (update) | automation/agent-mcp-surface | P0, P5 | prototype findings (SDK load result, `nw.require` path resolution) |

## Progress

- [x] [Phase 0 — Hosting prototype (closes C_MCP_DEC_03)](#PL_MCP_P0)
- [x] [Phase 1 — Endpoint, start-up, preferences, registry](#PL_MCP_P1)
- [x] [Phase 2 — Tools, resources and result shaping](#PL_MCP_P2)
- [x] [Phase 3 — Menu item and info dialog](#PL_MCP_P3)
- [x] [Phase 4 — End-to-end harness](#PL_MCP_P4)
- [x] [Phase 5 — Documentation propagation](#PL_MCP_P5)

## Phases

### Phase 0 — Hosting prototype [DONE]  {#PL_MCP_P0}

**Depends on:** none (can run in parallel with PL_AGA Phase 0)
**Implements:** resolution of [C_MCP_DEC_03](./mcp-server.concept.md#C_MCP_DEC_03)
**Verify:** Claude Code (`claude mcp add --transport http`) and the MCP Inspector both list one dummy tool from an NW.js instance; the result is recorded in [PL_MCP_DEC_01](#PL_MCP_DEC_01)

What to do (scratch branch `proto/mcp-hosting`):
1. **Bundle and load.** Bundle `@modelcontextprotocol/sdk` 1.x core (`Server`, types, zod, ajv) with esbuild to CJS for Node 18.0. Load it in the NW page and record how the module path resolves in all three run modes: `npm start` (`target/site` package), the packaged build, and devmode (remote page from `http://127.0.0.1:8888` with package root `scripts/devmode/`). Choose one loading method that works in all three (for example an absolute path built from the page location, or a script served at `scripts/mcp-server.js` that registers itself). Also record what `process.pid` returns in a `new_instance` window, and which events fire on window close and on the File → Exit path (`nw.Window.get().close(true)`).
2. **Transport.** Write a short (~100–150 lines) Streamable HTTP transport over `http` with JSON responses. Answer `initialize`, `tools/list` and `tools/call` for one dummy tool.
3. **Connect.** Connect Claude Code and the MCP Inspector. Record:
   - the revisions they negotiate;
   - any failure;
   - the bundle size;
   - the start-up time.
4. **Fallback.** If step 1 or 2 fails, write the same answers with a hand-rolled JSON-RPC layer and connect again.
5. **Decide.** Close PL_MCP_DEC_01 and C_MCP_DEC_03, then discard the branch.

**Result (2026-10-01).** The prototype ran entirely in the scratchpad: a copy of the built site with an inline loader and the bundle, so no Java change and no scratch branch were needed. The pieces:
- `@modelcontextprotocol/sdk` 1.31.0 (zod 4.6.5), core `Server` only;
- a ~150-line JSON-response Streamable HTTP transport over `http`;
- one dummy tool `circuit_ping`, which returns the pid, the runtime versions and the active document's element count read from `window.CircuitJS1`;
- esbuild 0.28 bundles for both loading methods.

Three substitutions, which limit what each column proves:
- **Packaged** ran a scratch copy of the release `out/linux-x64/CircuitJS1 Desktop Mod` (binary, libraries) with `package.nw` replaced by the prototype site, not a fresh `npm run build` artifact. The runtime and the layout are the release ones.
- **Devmode** ran the devmode manifest (`scripts/devmode/`, remote page, `node-remote`) against a static `python3 -m http.server` on 8888 serving the prototype site, not GWT devmode. It proves the package root and remote-page behaviour, not a code-server session. No host was connected in devmode; the `curl` handshake and tool call were.
- **Claude Code** was connected with `claude -p --mcp-config <file> --strict-mcp-config`, an HTTP server entry equal to what `claude mcp add --transport http` writes. This left the developer's Claude Code configuration untouched.

| Run mode | Runtime (`process.versions`) | `<script src="scripts/mcp-server.js">` (page context) | `require(<package root>/scripts/mcp-server.cjs)` (Node context) |
|---|---|---|---|
| `npm start` (`nw target/site`) | NW 0.64.1 SDK flavor, Node 18.0.0, Chromium 101 | works, listening 240–280 ms after the loader starts | works (path from `nw.__dirname`) |
| Packaged (release `CircuitSimulator` + `package.nw`) | NW 0.64.1-mod1 normal flavor, Node 18.0.0 | works after the change below | failed: Node has no crypto in this build |
| Devmode (remote page `http://127.0.0.1:8888/`, package `scripts/devmode/`) | NW 0.64.1 SDK flavor, Node 18.0.0 | works | fails: no server file under the package root |

Findings:
- **The release runtime's Node has no OpenSSL.** `require('crypto')` throws `Node.js is not compiled with OpenSSL crypto support` in the normal-flavor 0.64.1-mod1; only the packaged mode shows it. The SDK core does not require `crypto`. The prototype's own session-id call did, so it now uses Web Crypto `crypto.randomUUID()` of the page context. The bundle must contain no `require("crypto")`.
- **Loading method: the script tag.** The page loads `scripts/mcp-server.js`, and the bundle registers a global. This works in all three modes because the page context exposes `require` for `http`. `require` by path fails in devmode. Paths: `process.cwd()` and `nw.__dirname` are the package root (`target/site`, `package.nw`, or `scripts/devmode/` in devmode); `nw.App.startPath` is the launch directory.
- **Hosts.** Claude Code 2.1.286 (`claude -p --mcp-config`, HTTP) and MCP Inspector CLI 2.9.0 listed and called the tool against the `npm start` and packaged instances. Both negotiated **2025-11-25**, the SDK's latest; a client asking for 2025-06-18 got 2025-06-18. The one failure on the way was the Node-crypto error above.
- **Host behaviour.** Claude Code first sends `server/discover` (revision 2026-07-28) without a session. A 400 reply makes it fall back to `initialize`. It then opens a GET SSE stream, which a 405 reply declines without error.
- **Size and start-up.** The bundle is 1.8 MB unminified and 0.9 MB minified (190 KB gzipped). It builds in about 1 s. The server listens 230–280 ms after the loader starts.
- **New window.** `nw.Window.open(..., {new_instance: true})` gives a separate renderer process with its own `process.pid` under the same NW browser process (same `ppid`). It started its own server, which fell back to the next port (7312).
- **Close events.** On a window-manager close the NW `close` event fires, then `beforeunload`, `pagehide` and `unload`. On File → Exit (`close(true)`) only `beforeunload`, `pagehide` and `unload` fire. `process.on('exit')` fired on neither path, and the port was released on both. Registry removal therefore belongs in `unload`, using synchronous `fs`. This corrects the Phase 1 "Shutdown" row.
- **Fallback.** Step 4 (a hand-written layer) was not needed.
- **Unrelated defect.** `scripts/devmode/package.json` passes `--user-data-dir=\"/tmp/chrome/devmode\"` with literal quotes, so NW creates a `"/tmp/chrome/devmode"` directory relative to the launch directory. It is filed in the Backlog.

### Phase 1 — Endpoint, start-up, preferences, registry (`mcp/server/src/`) [DONE]  {#PL_MCP_P1}

**Depends on:** Phase 0; PL_AGA Phase 1 (`CircuitJS1Agent` exists)
**Implements:** [SP_MCP_01_01](./mcp-server.sp.md#SP_MCP_01_01), [SP_MCP_01_02](./mcp-server.sp.md#SP_MCP_01_02), [SP_MCP_02_01](./mcp-server.sp.md#SP_MCP_02_01), [SP_MCP_02_05](./mcp-server.sp.md#SP_MCP_02_05), [SP_MCP_02_06](./mcp-server.sp.md#SP_MCP_02_06), [SP_MCP_03_01](./mcp-server.sp.md#SP_MCP_03_01), [SP_MCP_03_03](./mcp-server.sp.md#SP_MCP_03_03), [SP_MCP_04](./mcp-server.sp.md#SP_MCP_04)
**Verify:** [SP_MCP_05_01](./mcp-server.sp.md#SP_MCP_05_01) rows Endpoint (Claude Code and Inspector connect and complete the handshake — tool and resource counts are checked in Phase 2; GET, unsupported revision header, session header, notification), Origin rule, Start-up port busy; [SP_MCP_05_02](./mcp-server.sp.md#SP_MCP_05_02) "One record per live instance"; [SP_MCP_05_04](./mcp-server.sp.md#SP_MCP_05_04) all ports busy (status `failed` logged; the dialog part in Phase 3), browser build, crash record

What to create / change:
| Entity | Module | Purpose |
|--------|--------|---------|
| Server entry | `mcp/server/src/index.js` | `start(agentBridge, prefs, onStatus)`, `stop()` |
| HTTP endpoint | `mcp/server/src/http.js` | POST/GET/DELETE handling, Origin rule, version header, 202 for notifications |
| Protocol layer | `mcp/server/src/protocol.js` | Per PL_MCP_DEC_01 |
| Registry | `mcp/server/src/registry.js` | Write/remove own record; delete dead-pid records at start |
| Agent client | `mcp/server/src/agent.js` | Promise wrapper over `CircuitJS1Agent.call`/`callAsync`, JSON parsing |
| Build step | `scripts/dev_n_build.js`, root `package.json` (esbuild devDependency, `build:mcp` script), `.gitignore` (`war/scripts/mcp-server.js`) | Generated bundle |
| Start trigger | `client/agent/AgentJsBridge.java` (`startMcpServer` native) called from `CirSim` start-up right after `setupJSInterface()` | SP_MCP_02_05 |
| Preferences | `OptionsManager` storage keys (C_USR) | `mcpServerEnabled`, `mcpServerPort`, `mcpServerPortRange`, `mcpServerHost` |
| Status | `McpServerStatus.java` (client root, session object on `CirSim`; client root because the L2 dialog reads it — a stateful session object, not a utility) | Status, reason, URLs, call counter; updated by the server through `AgentJsBridge` |
| Chromium args | `war/package.json` and `scripts/devmode/package.json` | SP_MCP_02_06 flags |
| Shutdown | Window `unload` handler with synchronous `fs`, plus the File → Exit command before `close(true)` | `stop()` removes the record on every exit path. Phase 0 measured: `unload` fires on both a window-manager close and `close(true)`, Node `process` `exit` fires on neither, and a `close` listener alone misses `close(true)` and would block closing |

**Result (2026-10-02).**
- **Modules.** `mcp/server/src/` holds `index.js` (entry, lifecycle, instance record content, URLs, instructions), `http.js` (HTTP rules), `protocol.js` (SDK `Server` + `JsonResponseTransport`), `registry.js` and `agent.js`. `mcp/server/build.js` (esbuild 0.28.2) writes `war/scripts/mcp-server.js`: IIFE, `platform: node`, target `node18.0`/`chrome101`, unminified 1.8 MB, built in about 0.2 s. The build fails when the bundle contains `require("crypto")`. Root devDependencies are pinned exactly: `@modelcontextprotocol/sdk` 1.31.0, `zod` 4.6.5, `esbuild` 0.28.2. `npm run build:mcp` builds the bundle alone; `scripts/dev_n_build.js` runs it first in `buildGWT` (and so in `buildall`, `fullrebuild`) and in devmode.
- **Loading and start.** `war/circuitjs.html` loads `<script src="scripts/mcp-server.js">` before the GWT selection script. The script registers `window.CircuitJS1Mcp = {start, stop, status, TOOLS_VERSION, PROTOCOL_REVISIONS}`. It requires the Node built-ins (`http`, `os`, `fs`, `path`, `buffer`) lazily, inside functions, so a browser build loads it without error. The desktop runtime is detected through `globalThis.require` and `globalThis.process.versions.node`; a bare `typeof require` is always true after esbuild's shim. Start-up: `CirSim` → `setupJSInterface()` → `AgentJsBridge.startMcpServer(sim)`. That method calls `McpServerStatus.readPrefs()` (an invalid stored value falls back to its default, with a warning) and then `CircuitJS1Mcp.start(CircuitJS1Agent, prefs, onStatus)`. The status callback updates `CirSim.mcpServerStatus` and logs `MCP server listening on <urls> (instance <id>)`, `MCP server failed: <reason>` (warning) or `MCP server disabled: <reason>`. A desktop page without the bundle is `failed: server script not loaded`.
- **Phase 2 hooks.**
  - `createProtocol({appVersion, instructions, tools, resources, onToolCall})` takes two providers. `tools` is `{list(), call(name, args, extra)}`; `resources` is `{list(), templates(), read(uri)}`.
  - Phase 1 passes empty providers. `tools/list` and `resources/list` return `[]`; an unknown tool is -32602, an unknown URI -32002 (`RESOURCE_NOT_FOUND`); `McpError`/`ErrorCode` are re-exported.
  - `index.js` creates `createAgentClient(CircuitJS1Agent)`. Its `.call(op, args, {timeoutMs})` resolves to the parsed OperationResult. It always uses `callAsync`. A synchronous contract that did not call back, or that returned `undefined` or unparseable text, becomes `internal_error`. `run`/`render` have a safety timeout (`ASYNC_TIMEOUT_MS`, 180 s since the Phase 1 review; the earlier 300 s note is superseded, see the Phase 2 Result). `.reportError(message)` reaches the global handler. Phase 2 builds `tools.js`/`resources.js` over this client and passes them to `createProtocol`.
- **Phase 3 hooks.** `McpServerStatus` (client root, `CirSim.mcpServerStatus`) provides:
  - `getState()`, `describe()` (`listening` / `failed: <reason>` / `disabled`), `getInstanceId()`, `getUrls()` and `getToolCalls()`;
  - `PREF_*` keys and defaults, `readPrefs()`, `isValidPort`, `isValidPortRange` and `isValidHost` for "Save".
  The counter counts every `tools/call` the protocol layer handles, unknown tools included. Each change reaches Java through the status callback, with no log line.
- **Phase 4 hooks.** The scratch NW.js driver (`<session scratchpad>/mcp1/nw_mcp.mjs`, outside the repo) has `launchNw` (Xvfb, CDP, scratch `HOME` and profile), `mcpPost`, `records` and `occupy(ports)`. Diagnostic: `CircuitJS1Agent.debugMcpStatus()`. `CircuitJS1Mcp.status()` also returns `recordFile`, `removedRecords` and `registryError`.
- **Transport facts.**
  - All HTTP clients share one SDK `Server`. Client JSON-RPC ids are replaced by internal ids (`mcp-<n>`) and put back on the reply, so equal ids of concurrent clients cannot collide. `notifications/cancelled` is dropped before the SDK (cancellation is not supported), so no client can abort another client's request.
  - Every request gets exactly one reply (review fix):
    - a message the SDK's strict `isJSONRPCRequest` rejects (extra top-level key, `params` not an object, `_meta` not an object) is answered -32600 / HTTP 400 instead of being dropped by the SDK; invalid notifications are ignored (202);
    - the served methods' params are checked against the SDK request schemas first: -32602 `Invalid params for <method>: params.<field> (<reason>)`, also for `initialize` without params;
    - a backstop of `PENDING_TIMEOUT_MS` = 200 s answers -32603 and drops the pending entry. It is longer than the agent client's asynchronous timeout of 180 s, which is above the 120 s run budget cap;
    - the HTTP server has `headersTimeout` 10 s and `requestTimeout` 60 s for incomplete requests, which do not limit a long response;
    - a declared `Content-Length` over 16 MB gets 413 before the body is read; a streamed body that exceeds it gets 413 too, both with `Connection: close` and the socket destroyed.
  - `initialize` is normalised before the SDK handler sees it: the requested revision is kept when it is 2025-11-25 or 2025-06-18; anything else gets 2025-11-25. The SDK alone would also accept 2025-03-26 and older.
  - Check order: Origin (403, empty body) → path (404) → `OPTIONS` (204, CORS headers for local origins) → not `POST` (405 with `Allow`) → version header (absent = 2025-06-18; unserved → 400 with a -32600 body) → body over 16 MB (413) → not JSON (-32700, HTTP 400) → not one request object, batch arrays included (-32600, HTTP 400) → a client response (202) → a notification (202, empty body) → a request (200, JSON).
- **Registry.** `instanceId` = `<pid>-<startedAtMs>`. The directory and the file are set to 0700 and 0600 by `chmod` after creation, because the umask masks the creation modes and an older directory may be wider, through `<id>.json.tmp` + rename. Start-up deletes the records and temp files of dead pids (`process.kill(pid, 0)`; EPERM counts as alive). Unparseable files are left alone. The record is removed by the `unload` listener (synchronous `fs`) and by File → Exit (`CirSim.stopMcpServer()` before `close(true)`). `stop()` is idempotent and also destroys open sockets: Node 18.0 has no `closeAllConnections`.
- **URLs.** `127.0.0.1` first, then the non-internal IPv4 addresses, for a wildcard host (`0.0.0.0`, `::`). A loopback host gets only its loopback URL. A host set to one specific address gets only that address's URL, because `127.0.0.1` does not answer there (a spec gap, see the report).
- **Runtime settings.** Both manifests carry the three SP_MCP_02_06 flags. The devmode `--user-data-dir` quoting is fixed, which closes that backlog item.
- **Verified** with NW.js 0.64.1 SDK flavor (Node 18.0.0) under Xvfb, with a scratch `HOME`:
  - endpoint rows, Origin rows and errors (30 checks);
  - port busy (7311 taken → 7312, and the record says 7312) and all ports busy (`failed: no free port in range 7311..7330`, logged, no record, app usable);
  - two windows (`new_instance`) → two records with distinct ports; `close()` of one → one record, port released; `close(true)` → none;
  - stale records (dead-pid record and temp file deleted; live-pid, unparseable and foreign files kept; logged);
  - clients: the SDK client (negotiated 2025-11-25, server `circuitjs1` 1.3.2), MCP Inspector CLI 2.9.0 (`tools/list`, `resources/list`) and Claude Code 2.1.287 (`claude -p --mcp-config <tmp> --strict-mcp-config` → `connected`);
  - browser build: live scenario `mcp_browser`.
  - hostile input (review round 1, 18 checks): the strict-schema cases answered within 3 s, also three at once; -32602 for params (initialize without params, wrong `protocolVersion` type, `resources/read` without `uri`, `tools/call` without `name`); 400 cancellations for every plausible internal id leave 30 concurrent requests answered; declared and streamed 413 with the connection closed; incomplete headers and an incomplete body closed by the server; the server keeps answering afterwards.
  Phase 4 moves these probes into `tests/mcp/e2e.mjs`: the endpoint, Origin and error rows, port busy and all busy, two windows and the close paths, stale records, the strict-schema, params, cancellation and 413 probes, and the incomplete-request probes (slow, about 100 s, which can be an opt-in group). The real-client checks (SDK client, Inspector CLI, Claude Code with a temporary `--mcp-config`) stay optional, because they need network or credentials.
  - packaged: `npm run build` (0.64.1-mod1 normal flavor, bundle in `package.nw/scripts/`) wrote a 0600 record (`appVersion` 1.3.2), answered `initialize` (2025-11-25), a notification with 202 and `tools/list`, and rejected a foreign Origin with 403. No Node-crypto error.
  Not run: removal of the packaged record on close (the normal flavor has no CDP and Xvfb has no window manager), and devmode.
  A window-manager close was approximated by `nw.Window.get().close()`, which fires the NW `close` event and then `unload`, as Phase 0 measured; no window manager runs under Xvfb.

### Phase 2 — Tools, resources and result shaping [DONE]  {#PL_MCP_P2}

**Depends on:** Phase 1; PL_AGA Phases 2–9 (implemented)
**Implements:** [SP_MCP_01_03](./mcp-server.sp.md#SP_MCP_01_03), [SP_MCP_01_04](./mcp-server.sp.md#SP_MCP_01_04), [SP_MCP_02_02](./mcp-server.sp.md#SP_MCP_02_02), [SP_MCP_02_03](./mcp-server.sp.md#SP_MCP_02_03), [SP_MCP_03_02](./mcp-server.sp.md#SP_MCP_03_02), [SP_MCP_03_04](./mcp-server.sp.md#SP_MCP_03_04)
**Verify:** [SP_MCP_05_01](./mcp-server.sp.md#SP_MCP_05_01) rows Endpoint (Claude Code lists 14 tools; Inspector `tools/list` and `resources/list`), circuit_edit (domain error, schema error), circuit_render png, Resources (catalogue, unknown URI, circuit round trip, templates, examples), Tool file rule, Sizing oversized read; [SP_MCP_05_02](./mcp-server.sp.md#SP_MCP_05_02) "Tools add no circuit logic", "`isError` ⇔ `ok = false`", "Text part ≤ 60 000 chars"; [SP_MCP_05_03](./mcp-server.sp.md#SP_MCP_05_03) long run while reading; [SP_MCP_05_04](./mcp-server.sp.md#SP_MCP_05_04) huge result

What to create:
| Entity | Module | Purpose |
|--------|--------|---------|
| Tool table | `mcp/server/src/tools.js` | 14 descriptors: names, titles, agent-facing descriptions, input/output JSON Schemas, annotations, mapping to Agent API ops |
| Schemas | `mcp/server/src/schemas.js` | JSON Schemas built from the SP_AGA structures (ElementSpec, Edit, ProbeSpec, OperationResult) |
| Resources | `mcp/server/src/resources.js` | Catalogue, documents, examples (from `circuits/setuplist.txt` in the package), `agent-format` text |
| Agent format text | `mcp/server/agent-format.md` (bundled as a string) | Coordinate model, ElementSpec, edit ops, issue codes |
| Shaping | `mcp/server/src/shaping.js` | Text part, PNG image part, size limit and the per-tool shrink rules, `result_too_large`; an `undefined` or unparseable return from `CircuitJS1Agent` becomes an `internal_error` result |

**Result (2026-10-02).**
- **Modules.** `mcp/server/src/` gains:
  - `tools.js`: the 14 descriptors (name, title, agent-facing description of 600–1100 chars with the default `doc` rule and one example, input and output schemas, annotations of the SP_MCP_02_02 table, `openWorldHint: false`), the mapping to the SP_AGA contracts and the per-tool reductions;
  - `schemas.js`: CellPoint, ElementSpec, the eight Edit variants (`oneOf` by `op`), ProbeSpec, AgentCircuit, Issue and `operationResult(dataFields)`;
  - `validate.js`: the argument check of SP_MCP_03_02;
  - `shaping.js`: text part, PNG image part, `result_too_large`, `internal_error`, last-resort text shortening;
  - `resources.js`: the four fixed resources and three templates.
  `mcp/server/agent-format.md` is bundled as a string (esbuild `.md` text loader in `build.js`). `index.js` passes `createTools(agent)` and `createResources({agent})` to `createProtocol`. The bundle is 1.9 MB unminified.
- **Mapping.** Arguments pass to the contract under their SP_AGA names; only the grouping arguments (`action`; `type` of `circuit_types`) are consumed. `circuit_sim` passes `action` to `simControl`. `doc` passes through on every tool; session-scoped contracts validate it.
- **Validation** (SP_MCP_03_02). Types, required fields, enumerations, unknown arguments (`additionalProperties: false` at the top level), an argument that the chosen action does not take, and the action-specific required arguments (`doc` for `activate`/`close`, `checkpointId` for `restore`, `path` for `open`, `settings` for `configure`) are -32602 naming the field; `span`/`settle` per run mode are left to the Agent API, for example `Invalid arguments for tool circuit_edit: edits[0].op must be one of add, move, …`. Range keywords in the schemas (minimum, maximum, maxItems, pattern) inform the agent but are not enforced: the Agent API answers them as `invalid_value` results with a hint.
- **Output schemas** are open: nothing inside `data` is required and nested objects allow other fields. The SDK client validates `structuredContent` against `outputSchema` with ajv, also for `isError` results.
- **Shaping.** See SP_MCP_01_04 and 03_04. A reduced read-only result's text starts with a note line, for example `[result reduced to fit the 60000-character limit: called with detail="concise" instead of "full", limit=125 instead of 500; continue at offset 125]`; `structuredContent` is the effective call's OperationResult. Fallbacks, when nothing more can be reduced or for a tool that is never re-executed (mutations, runs): the text part alone halves its largest arrays and names them in the note, and `structuredContent` keeps the whole result. This was not reached by any measured result.
- **Resources.** `circuitjs://examples` reads `circuitjs1/setuplist.txt` and the example files by `fetch` relative to the page (cached index, the same paths as `CircuitLoader`); entries are `{path, title, menu}`, and `circuitjs://examples/{path}` serves listed paths only. The document circuit resource reads `getCircuit` at `detail: full` in pages of 500. An Agent API rejection of a resource read is -32002 for `unknown_type`/`unknown_document`, else -32603 with the issue.
- **Timeouts (reconciled).** Agent client `ASYNC_TIMEOUT_MS` = 180 s: a run ends within `budgetMs` (≤ 120 s) plus one slice, with 60 s margin for queued slices and renders. Protocol backstop `PENDING_TIMEOUT_MS` = 200 s. Both are below the hosts' 300 s response timeout, and a unit check asserts the order. Measured: `circuit_run` with `budgetMs: 120000` answered after 120.09 s (`budget_exhausted`, `wallMs` 120027), and reads from a second client during it took 56–336 ms.
- **Behaviour change outside `mcp/`.** `JsonCircuitExporter` now always writes `simulation.auto_time_step`, also `false`. Before, the key was written only when on, and the importer keeps the target document's own setting when the key is absent. So the SP_MCP_05_01 circuit round trip from the start-up document (auto off, from its example) into a new document (auto on) differed in that one setting.
- **Verified.** NW.js 0.64.1 SDK flavor under Xvfb, scratch `HOME`, scratch driver `<session scratchpad>/mcp2/nw_mcp2.mjs`. A page recorder wraps `CircuitJS1Agent.callAsync`, and every tool result is compared with the Agent API call the server made. 49 checks passed:
  - endpoint: SDK client 14 tools, 4 resources, 3 templates; Inspector CLI `tools/list` 14, `resources/list` 4, templates 3; Claude Code 2.1.287 (`claude -p --mcp-config <tmp> --strict-mcp-config`): `connected`, 14 `mcp__circuitjs__*` tools, and a real `circuit_types` call answered (Haiku);
  - circuit_edit: domain error `isError` with `unknown_property`; schema error -32602 without an Agent API call;
  - circuit_render png: image part (PNG signature) and `"<image>"` in the text and `structuredContent`;
  - resources: `catalogue/Resistor` TypeInfo, unknown URI and unknown type -32002, examples index (336 entries) and `ohms.txt`, a path outside the index -32002, agent-format, documents, d1 → d2 round trip equal;
  - file rule: `save /etc/x.conf` → `file_not_allowed`; save and open of a `.json` path;
  - invariants over the 39 tool results of the run: `structuredContent` = the effective OperationResult (render png apart; `result_too_large` rejections apart), `isError` ⇔ `ok = false`, text ≤ 60 000;
  - sizing on `alu74181.txt` (413 elements): `circuit_get` full/500 (120 074 chars) → concise/500 → /250 → /125, 33 945 chars, note, `nextOffset` 125; `circuit_connectivity` 18 125; `circuit_get` default 48 209; JSON export 230 092 → `result_too_large`; SVG at scale 2 → `result_too_large`; `circuit_run` with 16 probes at `maxPoints` 125 → 23 366 chars (1024 points; 2000 points would be about 46 000);
  - SP_MCP_05_03 long run: a 5 s run in flight, three `circuit_get` calls from a second client answered in 15–346 ms before it ended.
  Pure-JS checks: `npm run test:mcp-unit` (`node --test mcp/server/test/`, 24 tests) covers the catalogue, schemas compiled with strict ajv, the mapping of all 27 tool/action forms, validation messages, shaping and reductions with a fake agent, resources, the timeout order and a pass through the SDK server.
- **Phase 4 hooks.** These scratch checks move into `tests/mcp/e2e.mjs`:
  - the page recorder and the invariant loop (no circuit logic, `isError` ⇔ `ok`, text limit);
  - the tool rows (edit domain and schema errors, render png/svg, file rule, save/open), the resource rows (catalogue, unknown, round trip, templates, examples) and the sizing rows on `alu74181.txt`;
  - the long-run reads.
  The 120 s run is slow (opt-in group). The Inspector and Claude Code checks stay optional (network, credentials).
- **Review round 1 (2026-10-02).** Error replies bound echoed text: URIs at 200 chars, Agent API and exception messages at 300 (resources, agent client, HTTP layer, tool exceptions; unit test with a 1 MB URI). `settle.tolerance`/`window`/`maxSpan` accept unit strings like `span`. The `circuit_run` span/settle mode checks moved back to the Agent API. The `circuit_types` text says a given `doc` must be open. The agent-format text no longer states a probe polarity formula and qualifies step halving with `autoTimeStep`. Rechecked: `npm run test:mcp-unit` 25/25, `mcp_browser` 8, NW tools and long checks 41/41.

### Phase 3 — Menu item and info dialog [DONE]  {#PL_MCP_P3}

**Depends on:** Phases 1–2
**Implements:** [SP_MCP_02_04](./mcp-server.sp.md#SP_MCP_02_04)
**Verify:** [SP_MCP_05_01](./mcp-server.sp.md#SP_MCP_05_01) rows Info dialog (listening, disable, counter); [SP_MCP_05_04](./mcp-server.sp.md#SP_MCP_05_04) all ports busy (dialog shows the reason) — plus: devmode manual check of the dialog in English and Ukrainian locale strings (RULE_STYLE_003)

What to create:
| Entity | Module | Purpose |
|--------|--------|---------|
| McpServerDialog | `dialog/McpServerDialog.java` | Status, instance ID, URLs, copyable command line, call counter, editable settings with Save |
| Menu item | `MenuManager.java` (Options menu) + `ActionManager.java` | "MCP Server…" / "(off)" |
| Status channel | `AgentJsBridge.java` → `McpServerStatus` | Server status/counter callback into Java |

**Result (2026-10-02).**
- **Code.**
  - `dialog/McpServerDialog` (extends `Dialog`, option prefix `mcp.server`) is opened only by `DialogManager.showMcpServerDialog()`, from `ActionManager` `options`/`mcpserver`. It shows status (`McpServerStatus.describe()`), instance ID, URLs and the call counter (both `—` unless `listening`), a read-only command box with `McpServerStatus.getConnectCommand()` = `claude mcp add --transport http circuitjs <first URL>` and a Copy button (empty and disabled without a URL), and the settings Enabled / Base port / Listening address with Save and Close.
  - Save validates with `isValidPort`, `isValidPortRange` (against the stored range, which the dialog does not edit) and `isValidHost`. An invalid value shows a message and nothing is stored. A valid Save writes `mcpServerEnabled`, `mcpServerPort` and `mcpServerHost`, says "Saved. The settings apply at the next start of the app." and changes nothing at run time. An unchanged form (valid stored values) writes nothing ("Nothing to save: the settings are unchanged."). Enter saves only while the focus is in a settings field (the dialog stays open); Enter right after opening or on a focused button writes nothing. Escape closes. The command box text is set only when the command changes, so a per-call status change keeps a selection.
  - The dialog imports only `McpServerStatus`, `OptionsManager` (already a `Dialog` dependency) and `Locale`. Copy is a `Consumer<String>` that `DialogManager` binds to the new `ClipboardManager.copyText`: the system clipboard only, so the internal circuit clipboard used by Paste stays untouched. It tries `$doc.execCommand('copy')` first, then `navigator.clipboard`.
  - Status channel: the callback already flowed through `AgentJsBridge` (Phase 1). What was added is `McpServerStatus.addListener/removeListener`, fired by `update()` and `set()`. The open dialog listens (counter live) and unregisters in `hide`.
  - Menu: `MenuManager.mcpServerItem` (Options, after "Remote Debug...") reads "MCP Server..." plus " (off)" when the state is `disabled`. It follows the status through a listener, because the status arrives after the menu is built. `CheckboxAlignedMenuItem.setAlignedText` keeps the item's alignment.
  - Strings: all labels and messages go through `Locale.LS`. `locale_uk.txt` gains 17 keys; "(off)" and "Copy" already existed. The status values (`listening`, `failed: <reason>`, `disabled`) stay untranslated: they are the SP_MCP_04_01 wire names, also in the log and in `debugMcpStatus`.
- **Verified** with NW.js 0.64.1 SDK flavor under Xvfb, scratch `HOME` and profile, Ukrainian UI locale (scratch driver `<session scratchpad>/mcp3/nw_mcp3.mjs`, 42/42):
  - listening: the dialog shows status, ID and the three URLs as in `debugMcpStatus`, and the command line with `http://127.0.0.1:7311/mcp`;
  - counter: `initialize` + 3 `tools/call` while open → 3 (live); closed, one more call, reopened → 4;
  - Copy: a CDP mouse click puts the command line on the clipboard (`nw.Clipboard`);
  - Close and Escape close it; the listener is removed;
  - invalid settings rejected with nothing stored: port 80, `abc`, 65530 (range 20), host `999.1.1.1`, `example.com`;
  - disable: untick, Save → message, keys stored, still `listening`. Graceful quit removes the record. Restart on the same profile → `disabled` (`disabled in preferences`), no record, 7311 free, menu "Сервер MCP... (вимк)", dialog `disabled` / `—` / empty command line;
  - re-enable on `127.0.0.1:7400` with Enter, restart → listening on that URL only, menu without "(off)";
  - all 20 ports busy → menu without "(off)", dialog `failed: no free port in range 7311..7330`.
  Browser build: live scenario `mcp_dialog` (39 checks, Enter-focus rows included), including side pages `?lang=en` and `?lang=uk` that compare every dialog string with its key and its `locale_uk.txt` translation.
- **Not run (manual).** The devmode check of RULE_STYLE_003:
  1. `npm run devmode`.
  2. Open `http://127.0.0.1:8888/circuitjs.html?lang=en`, then Options → "MCP Server...". Check every label, both buttons and the Save messages: port 80, then a valid Save.
  3. Repeat with `?lang=uk`.
  4. Look for clipped text in the Ukrainian labels.
- **Phase 4 hooks.** Move the `nw_mcp3.mjs` rows into `tests/mcp/e2e.mjs`:
  - `listening`: dialog content, counter, Copy;
  - `settings`: rejects, disable → restart → disabled → re-enable → restart, all on one `--user-data-dir`, with a graceful `nw.App.quit()` between launches;
  - `allbusy`.
  The page helpers find the dialog by `#mcpServerInfo` (rows `> tbody > tr`), `#mcpServerCommand` and `#mcpServerMessage`. Options submenu items are in the DOM only while the menu is open.

### Phase 4 — End-to-end harness (`tests/mcp/`) [DONE]  {#PL_MCP_P4}

**Depends on:** Phases 1–3
**Implements:** [SP_MCP_05](./mcp-server.sp.md#SP_MCP_05) automation; PL_AGA Phase 9 file cases
**Verify:** `node tests/mcp/e2e.mjs` prints PASS for every automated SP_MCP_05_01/05_02/05_04 row and the SP_AGA file rows; SP_MCP_05_03 "Private-network agent" and SP_MCP_05_01 "Runtime settings hidden window" checked by hand and recorded as `observed` with date in `tests/mcp/README.md`

What to create:
| Entity | Module | Purpose |
|--------|--------|---------|
| Harness | `tests/mcp/e2e.mjs` | Launch NW.js SDK on `target/site` with a scratch `HOME`; JSON-RPC client; scenario list mirroring SP_MCP_05 |
| README | `tests/mcp/README.md` | Requirements (Node ≥ 22 for the harness — the app itself runs on its embedded Node 18; a display, or Xvfb on headless Linux; `npm install` in `mcp/bridge/` for the bridge rows), run commands, manual rows |
| npm script | root `package.json` `test:mcp` | Entry point |

**Result (2026-10-02).**
- **Harness.** `tests/mcp/e2e.mjs` (Node ≥ 22), `npm run test:mcp [-- group|scenario…]`, `--list`. Per scenario: one NW.js 0.64.1 SDK launch (three for `settings`) on `target/site` with its own scratch `HOME` (plus XDG directories) and `--user-data-dir` under `OUT_DIR` (default `<tmpdir>/circuitjs-mcp-e2e`); one own Xvfb per run (`-displayfd`, stopped with SIGTERM so its lock file goes; `NW_DISPLAY=1` uses `$DISPLAY`); teardown SIGTERM → SIGKILL of the NW process group and a wait for the group to exit; the harness checks that 7311..7330 and 7400 are free at start (else exit 2) and before every scenario. One `PASS|FAIL|SKIP <ID> <title> {json}` line per row (declared per scenario; rows not completed print FAIL, also after a harness error: an unhandled rejection prints `HARNESS ERROR`, finishes the pending rows as FAIL, prints the SUMMARY and exits 2), `SUMMARY`, exit 0/1/2 as `tests/live` (Node < 22 → exit 2); a failed launch set-up ends its NW process group; `npx`/`claude` children are tracked like NW and ended on exit, SIGINT, SIGTERM and SIGHUP; NW and child processes run in their scratch directory; `results.json` in `OUT_DIR`.
- **Rows: 64.** Default group 55 automated rows in 12 scenarios (`endpoint`, `hostile`, `portbusy`, `allbusy`, `instances`, `stale`, `tools`, `long`, `dialog`, `settings`, `files`, `bg_files`) plus 4 always-SKIP rows (`manual`); `slow` 2 rows (`hostile_slow` incomplete requests, `long120`); `clients` 3 rows (Inspector CLI; Claude Code connect; Claude Code call). Every automated SP_MCP_05_01/05_02/05_04 row and SP_MCP_05_03 "Long run while reading" has a row named by its ID; the extra rows carry the ID of the section they check (SP_MCP_01_02, 02_01, 02_02, 02_03, 02_04, 02_05, 03_01, 03_03; SP_AGA_02_14, 03_09).
- **Results (2026-10-02, target/site of this branch).** Default: 55 PASS, 0 FAIL, 9 SKIP (the 4 manual/bridge/browser rows, the 5 not-selected slow/clients rows) in 81 s. `slow`: 2 PASS (incomplete headers closed after 29.8 s, body after 89.8 s; 120 s run answered after 120.1 s, `wallMs` 120 026, reads 32–58 ms). `clients`: 3 PASS (Inspector CLI 14/4/3; Claude Code 2.1.287 `connected`, 14 tools, a real `circuit_types` call answered with Haiku). `npm run test:mcp-unit` 25/25.
- **Moved from the scratch drivers.** `mcp1/nw_mcp.mjs` + `nw_mcp_lib.mjs`: launch/CDP/registry/occupy helpers, the endpoint, Origin and error checks, File → Exit, port busy, all busy, two windows and the close paths, stale records, the hostile probes (slow part → `slow`), the Inspector and Claude Code checks (→ `clients`); the SDK client handshake moved into `tools`. `mcp2/nw_mcp2.mjs`: the page recorder, tool/resource/sizing rows and the invariant loop (`tools`), the 5 s long run (`long`) and the 120 s run (`slow`), the Inspector/Claude 14-tool checks (`clients`). `mcp3/nw_mcp3.mjs`: dialog helpers (`#mcpServerInfo`), listening/counter/Copy/Escape (`dialog`), the settings restarts on one profile (`settings`), the dialog part of all busy (merged into `allbusy`). `nw/nw_files.mjs`: all its file checks (49 in `files`). The R1/R2 `openFile` step is new; it uses a minimal copy of the `tests/live` `agent_bg` page helpers (`r1Sample`, slice probe, Sliders observer, `debugDocState`, `debugClosedTabs`), because `tests/live/harness.mjs` runs `main()` on import.
- **Dropped.** `pkg_check.mjs` (packaged release flavor: no CDP in the normal flavor; stays a manual check), `clip_probe.mjs` (superseded by the Copy row), the info-only checks `render_svg_scaled_info`/`svg_large_info` (no expected outcome in SP_MCP), and the settings run's restore of the default preferences (each run uses a fresh profile).
- **Not automated.** SP_MCP_05_01 "Runtime settings: hidden window" and SP_MCP_05_03 "Private-network agent": manual, steps and the `observed` table in `tests/mcp/README.md` (not yet observed). SP_MCP_05_03 "Bridge forwarding": SKIP until PL_MCB. SP_MCP_05_04 "Browser build": SKIP pointing to the live scenario `mcp_browser`.
- **Σ maxPoints = 2000 worst case.** With 16 probes the decimation of SP_AGA_03_07 emits at most 2 × ⌊maxPoints/2⌋ points per probe, so 2000 points cannot all be emitted: 16 × 125 gives at most 16 × 124 = 1984, and an even split (124/126, 124/128) has different bucket counts that never fill together. The `tools` run reaches that maximum (fixed 5 µs step, 3920 samples: every probe 124 points) and asserts 1984 points and a text part of 41 798 chars ≤ 60 000. The SP_MCP_05_02 row reads "Σ `maxPoints` = 2000", which this satisfies as the request; the emitted maximum is 1984.
- **Harness facts.** The R1 sequence needs the `checkpoint` after `importCircuit` (as `agent_bg`): without it the import and the edit share one agent transaction and `undo` empties the document. Claude Code may spend one turn on a deferred-tool search before calling an MCP tool (`--max-turns 6`); the call row checks the tool result it received, and the answer only on a `success` result. Earlier scratch runs had left 34 stale `/tmp/.X<n>-lock` files from SIGKILLed `xvfb-run` sessions; the harness's own Xvfb avoids that.

### Phase 5 — Documentation propagation [DONE]  {#PL_MCP_P5}

**Depends on:** Phase 4
**Implements:** [SP_MCP_06_01](./mcp-server.sp.md#SP_MCP_06_01) (rollback switch documented)
**Verify:** `/dev-flow propagate` reports no drift for C_MCP/SP_MCP and C_USR — plus: setting `mcpServerEnabled = false` in the info dialog and restarting leaves no listener and no instance record (the minimum safe state)

What to update:
- `README.md` and [docs/project.md](./project.md): an "MCP server" section with the connect command.
- C_USR spec: the new preference keys.
- Skill automation/agent-mcp-surface: the prototype findings.

**Result (2026-10-02).** Documentation only, no code change. `README.md` and [project.md](./project.md) have an "MCP server (AI agents)" section: what the server is, the connect command `claude mcp add --transport http circuitjs <URL>`, where the URL is shown (Options → "MCP Server..."), ports 7311..7330, instance records in `~/.circuitjs1/instances/`, the security implication of the always-on, token-less LAN endpoint ([C_MCP_DEC_02](./mcp-server.concept.md#C_MCP_DEC_02)) with the Origin rule, and the rollback switch (Enabled off → Save → restart; `127.0.0.1` for local-only). [SP_USR](./user-preferences.sp.md) lists the four keys with defaults, validation, fallback and the missing URL layer; C_USR qualifies its "no central registry"/"URL wins" statements. SP_MCP brought in line with the code (start-up order with the script check first and the exact reasons, loopback URLs, HTTP 500/503/200 shutdown and backstop statuses, receive/agent/backstop timeouts, 202 for client responses, dropped `notifications/cancelled`, preference constraints, exact size hints) and §06_01 states how to reach the minimum safe state. Skill automation/agent-mcp-surface already held the prototype findings; added the negotiated revision, bundle size and start-up time, and the user-doc pointer. Verify: the minimum safe state ("`mcpServerEnabled = false` + restart → no listener, no instance record") is covered by `tests/mcp/e2e.mjs` scenario `settings`, row `disable` (checks `savedStorage`, `recordRemovedOnQuit`, `statusDisabled`, `noRecord`, `noPortBound`, `menuOff`, `dialogDisabled`), which passed in the Phase 4 run (default group 55 PASS, 2026-10-02); not re-run here. Propagate drift check (C_MCP/SP_MCP, C_USR): no remaining drift.

## Backlog

- Serve the stateless 2026-07-28 protocol revision in-app — return when: a Node-18-compatible SDK line supports it, or a host drops the initialize-based revisions.
- Progress notifications for long runs (SP_MCP_DEC_02 rejected B) — return when: hosts show progress to the model and an eval shows agents mis-handling long runs.
- MCP prompts — return when: a host surfaces prompts to users and the skill cannot cover a workflow.

## Design Decisions  {#PL_MCP_DEC}

### DEC_01 — Protocol layer (closes C_MCP_DEC_03)  {#PL_MCP_DEC_01}

> **Status:** resolved
> **Date:** 2026-10-01

**Question:** Does the in-app server use the official SDK v1 core with a custom HTTP transport, or a self-written tools/resources-only JSON-RPC layer?

**Options considered:**
| Option | Consequence |
|--------|-------------|
| A — SDK v1 core + custom transport (bundled) | Upstream keeps protocol handling current; bundle size; depends on loading under Node 18.0 |
| B — self-written layer | No dependency; we follow spec changes ourselves |

**Decision:** A — SDK 1.x core (`Server`) with our own JSON-response Streamable HTTP transport over `http`, serving revisions 2025-11-25 and 2025-06-18 (SP_MCP_02_01). The bundle is loaded by a `<script src="scripts/mcp-server.js">` that registers a global in the page context. It must contain no `require("crypto")`. The stateless server issues no session ids; any id it ever needs comes from Web Crypto of the page context.
**Rationale:** In [Phase 0](#PL_MCP_P0) A ran in all three run modes on Node 18.0.0, and both Claude Code and the Inspector connected to the release runtime. The only blocker found, the missing Node crypto in the release runtime, came from our own code and has a one-line fix. B's own costs (revision fallback, headers, schema validation) stay with upstream under A. The bundle (0.9 MB minified) and start-up (under 0.3 s) are acceptable.
**Resolved by:** the developer, 2026-10-01, at the Phase 0 sign-off (recommended option accepted).

## Changelog

| Date | Change |
|------|--------|
| 2026-10-01 | Initial version |
| 2026-10-02 | Phase 5 done (documentation propagation, Result block); plan completed |
| 2026-10-01 | Phase 0 done; DEC_01 resolved by the developer (A, script-tag loading, no Node crypto); Shutdown row corrected to `unload`; backlog: devmode manifest quoting |
| 2026-10-02 | Phase 1 done (Result block); devmode manifest quoting fixed and removed from the backlog |
| 2026-10-02 | Phase 2 done (Result block); agent client and backstop timeouts reconciled (180 s / 200 s) |
| 2026-10-02 | Phase 4 done (Result block): `tests/mcp/e2e.mjs`, `npm run test:mcp`; closes PL_AGA Phase 9 |
