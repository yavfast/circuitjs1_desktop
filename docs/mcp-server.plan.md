# Implementation Plan: In-app MCP Server  {#PL_MCP}

> **Code:** PL_MCP
> **Status:** draft
> **Created:** 2026-10-01
> **Updated:** 2026-10-01
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
| MCP protocol layer | SDK 1.x core (`Server`) + own JSON-response Streamable HTTP transport; bundle loaded by `<script src="scripts/mcp-server.js">` in the page context; no Node `crypto` (Web Crypto for session ids) — [PL_MCP_DEC_01](#PL_MCP_DEC_01) | Ran in all three run modes and connected Claude Code and the Inspector ([Phase 0](#PL_MCP_P0)) |
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
- [ ] [Phase 1 — Endpoint, start-up, preferences, registry](#PL_MCP_P1)
- [ ] [Phase 2 — Tools, resources and result shaping](#PL_MCP_P2)
- [ ] [Phase 3 — Menu item and info dialog](#PL_MCP_P3)
- [ ] [Phase 4 — End-to-end harness](#PL_MCP_P4)
- [ ] [Phase 5 — Documentation propagation](#PL_MCP_P5)

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

### Phase 1 — Endpoint, start-up, preferences, registry (`mcp/server/src/`) [TODO]  {#PL_MCP_P1}

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

### Phase 2 — Tools, resources and result shaping [TODO]  {#PL_MCP_P2}

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

### Phase 3 — Menu item and info dialog [TODO]  {#PL_MCP_P3}

**Depends on:** Phases 1–2
**Implements:** [SP_MCP_02_04](./mcp-server.sp.md#SP_MCP_02_04)
**Verify:** [SP_MCP_05_01](./mcp-server.sp.md#SP_MCP_05_01) rows Info dialog (listening, disable, counter); [SP_MCP_05_04](./mcp-server.sp.md#SP_MCP_05_04) all ports busy (dialog shows the reason) — plus: devmode manual check of the dialog in English and Ukrainian locale strings (RULE_STYLE_003)

What to create:
| Entity | Module | Purpose |
|--------|--------|---------|
| McpServerDialog | `dialog/McpServerDialog.java` | Status, instance ID, URLs, copyable command line, call counter, editable settings with Save |
| Menu item | `MenuManager.java` (Options menu) + `ActionManager.java` | "MCP Server…" / "(off)" |
| Status channel | `AgentJsBridge.java` → `McpServerStatus` | Server status/counter callback into Java |

### Phase 4 — End-to-end harness (`tests/mcp/`) [TODO]  {#PL_MCP_P4}

**Depends on:** Phases 1–3
**Implements:** [SP_MCP_05](./mcp-server.sp.md#SP_MCP_05) automation; PL_AGA Phase 9 file cases
**Verify:** `node tests/mcp/e2e.mjs` prints PASS for every automated SP_MCP_05_01/05_02/05_04 row and the SP_AGA file rows; SP_MCP_05_03 "Private-network agent" and SP_MCP_05_01 "Runtime settings hidden window" checked by hand and recorded as `observed` with date in `tests/mcp/README.md`

What to create:
| Entity | Module | Purpose |
|--------|--------|---------|
| Harness | `tests/mcp/e2e.mjs` | Launch NW.js SDK on `target/site` with a scratch `HOME`; JSON-RPC client; scenario list mirroring SP_MCP_05 |
| README | `tests/mcp/README.md` | Requirements (Node ≥ 22 for the harness — the app itself runs on its embedded Node 18; a display, or Xvfb on headless Linux; `npm install` in `mcp/bridge/` for the bridge rows), run commands, manual rows |
| npm script | root `package.json` `test:mcp` | Entry point |

### Phase 5 — Documentation propagation [TODO]  {#PL_MCP_P5}

**Depends on:** Phase 4
**Implements:** [SP_MCP_06_01](./mcp-server.sp.md#SP_MCP_06_01) (rollback switch documented)
**Verify:** `/dev-flow propagate` reports no drift for C_MCP/SP_MCP and C_USR — plus: setting `mcpServerEnabled = false` in the info dialog and restarting leaves no listener and no instance record (the minimum safe state)

What to update:
- `README.md` and [docs/project.md](./project.md): an "MCP server" section with the connect command.
- C_USR spec: the new preference keys.
- Skill automation/agent-mcp-surface: the prototype findings.

## Backlog

- Serve the stateless 2026-07-28 protocol revision in-app — return when: a Node-18-compatible SDK line supports it, or a host drops the initialize-based revisions.
- Progress notifications for long runs (SP_MCP_DEC_02 rejected B) — return when: hosts show progress to the model and an eval shows agents mis-handling long runs.
- Devmode manifest quoting (found by Phase 0): `scripts/devmode/package.json` `--user-data-dir=\"/tmp/chrome/devmode\"` keeps the quotes, so NW creates a `"/tmp/chrome/devmode"` directory relative to the launch directory — return when: Phase 1 edits `scripts/devmode/package.json` for the SP_MCP_02_06 flags.
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

**Decision:** A — SDK 1.x core (`Server`) with our own JSON-response Streamable HTTP transport over `http`, serving revision 2025-11-25 and the older ones the SDK supports. The bundle is loaded by a `<script src="scripts/mcp-server.js">` that registers a global in the page context. It must contain no `require("crypto")`; session ids come from Web Crypto.
**Rationale:** In [Phase 0](#PL_MCP_P0) A ran in all three run modes on Node 18.0.0, and both Claude Code and the Inspector connected to the release runtime. The only blocker found, the missing Node crypto in the release runtime, came from our own code and has a one-line fix. B's own costs (revision fallback, headers, schema validation) stay with upstream under A. The bundle (0.9 MB minified) and start-up (under 0.3 s) are acceptable.
**Resolved by:** the developer, 2026-10-01, at the Phase 0 sign-off (recommended option accepted).

## Changelog

| Date | Change |
|------|--------|
| 2026-10-01 | Initial version |
| 2026-10-01 | Phase 0 done; DEC_01 resolved by the developer (A, script-tag loading, no Node crypto); Shutdown row corrected to `unload`; backlog: devmode manifest quoting |
