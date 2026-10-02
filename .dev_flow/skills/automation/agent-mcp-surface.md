---
skill: agent-mcp-surface
domain: automation
topics: [mcp, nw-js-runtime, node-version, streamable-http, stdio-bridge, tool-design, agent-skill, grid-cells, nw-flavor, node-crypto, script-loading, shutdown-events]
source: research
updated: 2026-10-02
verified: prototype PL_MCP Phase 0 (2026-10-01)
---

# Hosting an MCP server in the app & designing tools for circuit agents

## Context

Findings of [docs/mcp-agent-bridge.spike.md](../../../docs/mcp-agent-bridge.spike.md) (2026-10-01) about making CircuitJS1 Desktop an MCP server driven by AI agents. Version facts are dated — re-check the runtime and SDK versions before relying on them (`out/nwjs_cache/manifest.json`, npm registry).

## Key concepts

- **Runtime.** Release builds use NW.js `0.64.1-mod1` (SEVA77 fork, `scripts/dev_n_build.js:83`) = **Node 18.0.0 / Chromium 101**. The app page has Node integration (`war/package.json`, `main: circuitjs.html`); `$wnd.nw.require(...)` already works (`LogManager.java:125`). Devmode manifest `scripts/devmode/package.json` adds `node-remote` and CDP 9222.
- **Packaging.** The NW package is `target/site` only — no `node_modules`. JS dependencies must be bundled into one file under `war/` / `public/`: an esbuild IIFE bundle loaded by a `<script>` tag (see Loading). Avoid ESM `import()` in the NW Node context.
- **MCP SDK fit (measured, [PL_MCP_DEC_01](../../../docs/mcp-server.plan.md#PL_MCP_DEC_01)).** `@modelcontextprotocol/sdk` 1.31 core `Server` runs on the embedded Node 18.0.0 in all three run modes. It is paired with our own JSON-response Streamable HTTP transport over `http`; the stock `StreamableHTTPServerTransport` needs `@hono/node-server` (Node ≥18.14.1). It serves 2025-11-25 and older. SDK v2 / protocol 2026-07-28 needs Node ≥20, so it can run only in an external bridge process.
- **Runtime flavors.** `npm start` and devmode run `node_modules/nw` 0.64.1 **SDK** flavor. The release build runs 0.64.1-mod1 **normal** flavor. Both have Node 18.0.0 and Chromium 101.
- **Loading.** `<script src="scripts/mcp-server.js">`: an esbuild bundle (`--platform=node --format=iife`) registers a global in the page context. `require` for `http` works there. This is the only method that works in `npm start`, packaged and devmode; `require(path)` fails in devmode, where the package root is `scripts/devmode/` and the page is remote.
- **Hosts.** Claude Code connects to Streamable HTTP directly (`claude mcp add --transport http circuitjs http://127.0.0.1:7311/mcp`, no auth header — [C_MCP_DEC_02](../../../docs/mcp-server.concept.md#C_MCP_DEC_02)); default tool-output cap 25k tokens (SP_MCP keeps text parts ≤ 60 000 chars); images render inline. Claude Desktop config is stdio-only → needs a stdio bridge (`mcp-remote` or our own).
- **Exposure policy (developer decision [C_MCP_DEC_02](../../../docs/mcp-server.concept.md#C_MCP_DEC_02), overrides the spike's security floor).** Always on, reachable from localhost and the private network, no token / access-control mode. Kept: foreign-`Origin` rejection (spec MUST, invisible to agents) and no code-execution tool. Do not reintroduce tokens or opt-in without the developer.
- **Instances.** "New window" is a separate NW process (`ActionManager.java:215`, `new_instance: true`) → each instance needs its own port + a discovery record.

## Usage in this project

Tool-design conventions recommended by the spike (prior art: circuitjs-mcp, SPICEBridge, KiCad MCPs, Anthropic "writing tools for agents"):
- Authoring geometry (decision [C_AGA_DEC_01](../../../docs/agent-api.concept.md#C_AGA_DEC_01)): the agent writes coordinates in **grid cells** (1 cell = 16 px, half-cell lattice), via whole-circuit import or incremental edits; no server auto-layout. `LabeledNode` (same name = same net) is the agent's tool for named nets.
- Lint topology on every edit (floating nets, missing ground, source/wire loops, one-pin nets) — the simulator hides floating nodes.
- Return measurements and stats plus decimated series (`maxPoints` default 200, Σ ≤ 2000 per run — SP_AGA_03_07), never raw scope buffers.
- Rejected operations as `isError: true` results with fix hints; issues found by successful operations in normal results; protocol errors only for bad requests.
- 14 grouped `circuit_*` tools (SP_MCP_02_02) with `outputSchema` + `structuredContent` + annotations; catalogue, documents, examples and the agent-format doc as resources.
- Agent skill: SKILL.md workflow checklist (build → lint → simulate → measure → fix) with one-level reference files; `name` ≤64 chars without "claude"/"anthropic"; ≥3 evals.

## Pitfalls

- **The release runtime's Node has no OpenSSL.** `require('crypto')` throws `Node.js is not compiled with OpenSSL crypto support` in 0.64.1-mod1 normal flavor only; the SDK flavor has crypto. Keep `require("crypto")` out of the bundle and use Web Crypto `crypto.randomUUID()` of the page context. Only a packaged run catches this.
- **Claude Code probes the stateless revision first.** It sends `server/discover` (2026-07-28) without a session and falls back to `initialize` on a 400. It then opens a GET SSE stream; answer 405.
- **Shutdown.** `unload`, `pagehide` and `beforeunload` fire on both a window-manager close and `close(true)`. The NW `close` event fires only on the former, and Node `process` `exit` fires on neither. Remove registry records in `unload` with synchronous `fs`.
- **New window.** `new_instance: true` gives a separate renderer process with its own `process.pid` under the same NW browser process (same `ppid`). Each window runs its own server on the next free port.
- **Paths.** `process.cwd()` and `nw.__dirname` are the package root: `target/site`, `package.nw`, or `scripts/devmode/` in devmode. `nw.App.startPath` is the launch directory.
- The simulator runs with non-convergence recovery on: source/wire loops and singular matrices arrive as `warn()` while simulation continues, not as `stop()` — map both (SP_AGA_03_06).
- Desktop "save" (`CirSim.nodeSave`) is a browser download and "open" is a file picker. The path-based seam is `PathFileAdapter` (client root, JSNI, since PL_AGA Phase 9): sync `fs` via `nw.require`, availability detected per call (a plain browser → `file_unavailable`), staging file + rename in the target's directory, symlinks followed (the real name must be `.txt`/`.json` too), no parent directories created. Headless Chromium has no Node `fs`; real file checks run in NW.js (`xvfb-run -a node_modules/nw/nwjs/nw --remote-debugging-port=<p> --user-data-dir=<tmp> target/site` + CDP works for a scripted check; Node there is 18.0.0).
- Many mechanisms act on the active document only (undo dump via `ActionManager.dumpCircuit`, `BaseCirSim.needAnalyze/stop`, renderer) — how background-document work meets SP_AGA_03_08 R1/R2 is resolved by SP_AGA_DEC_04 as a scoped silent bind (A) — see [background-documents](background-documents.md).

- The existing remote-debug channel (`server/remote-debug-server.js`: socket.io, CORS `*`; page agent uses `eval`) is a relay model only — do not expose it as the MCP path.
- Hidden/unfocused windows throttle timers — affects agent-driven runs unless stepping is synchronous or `chromium-args` disable background throttling.
- A CDP sidecar (as in `tests/live/harness.mjs`) works but needs `--remote-debugging-port`, an unauthenticated control surface.

## References

- [docs/mcp-agent-bridge.spike.md](../../../docs/mcp-agent-bridge.spike.md), [js-api-surface](js-api-surface.md), [gwt/jsni-patterns](../gwt/jsni-patterns.md), [gwt/build-pipeline](../gwt/build-pipeline.md)
- https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http · https://code.claude.com/docs/en/mcp · https://www.anthropic.com/engineering/writing-tools-for-agents · https://platform.claude.com/docs/en/agents-and-tools/agent-skills/best-practices · https://github.com/Suzu-Gears/circuitjs-mcp
