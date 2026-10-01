# Spike: MCP server in the app + agent client & skill for circuit authoring

> **Status:** concluded
> **Created:** 2026-10-01
> **Updated:** 2026-10-01
> **Author:** main
> **Time-box:** 1 session; three parallel sub-question researchers, ≤15 external sources in total (spent: ~14)
> **Scope:** codebase + external (no prototype)
> **Mode:** single (fanned out by sub-question, not by lens)
>
> **Target concept:** epic [E_AGT](./agent-automation.epic.md) — [C_AGA](./agent-api.concept.md), [C_MCP](./mcp-server.concept.md), [C_MCB](./mcp-bridge.concept.md), [C_AGS](./agent-skill.concept.md). The concept interview of 2026-10-01 chose agent-written grid-cell coordinates (C_AGA_DEC_01) over the netlist-first recommendation, and an always-on server without access control (C_MCP_DEC_02) over the security floor below.
> **Serves:** —
> **Question(s):**
> 1. **Transport & hosting.** How can the NW.js desktop app act as an MCP server — which MCP transport, where the server code runs (NW.js Node context vs. GWT/JS page vs. an external process), whether the official MCP SDK runs on the Node bundled with NW.js `0.64.1`, and what role an "MCP client" (a stdio bridge the agent host launches) has to play?
> 2. **Surface & gaps.** Does the existing automation surface (`CircuitJS1` JS API, JSON circuit format, remote-debug server, logs) cover the create / edit / debug loop an agent needs, and which capabilities are missing?
> 3. **Agent ergonomics & skill.** What tool/resource set, and what agent skill content, let an AI agent author and debug circuits reliably — informed by prior art (EDA/SPICE/CAD MCP servers)?

## Context

The developer wants AI agents to create, edit and debug circuits in CircuitJS1 Desktop. The app itself becomes the MCP server; an MCP client and an agent skill are to be developed alongside. Today agents can only drive the app indirectly — through Chrome DevTools MCP `evaluate_script` against the `CircuitJS1` global (see [JS_API.md](./JS_API.md)) or through the WebSocket remote-debug server ([remote_dbg.md](./remote_dbg.md)). Neither exposes typed, discoverable tools, and the GWT/NW.js runtime constrains where an MCP server can live. The concept cannot be authored until the transport, the gap list and the tool shape rest on verified facts.

## Exploration Log

### Entry 1 — 2026-10-01 — Q1: transport and hosting

**What was tried / researched:** NW.js manifests and boot path in the repo; how the existing remote-debug channel bridges page ↔ external process; NW.js runtime versions from the build cache; MCP specification transports (revision 2026-07-28); `@modelcontextprotocol/sdk` 1.31.0 and the v2 split packages (npm metadata + unpacked tarball); Claude Code and Claude Desktop MCP connection docs.

**Findings:**
- The page has Node integration. The production manifest is [war/package.json](../war/package.json) (`"main": "circuitjs.html"`, no `node-remote`), copied to `target/site/`. `LogManager.java:125,214` already calls `$wnd.nw.require('fs')`, so `nw.require('http')` works in the same window. The devmode manifest `scripts/devmode/package.json` adds `node-remote: <all_urls>` and CDP on port 9222.
- The release runtime is **NW.js `0.64.1-mod1`** (SEVA77 fork, `scripts/dev_n_build.js:83,310`) = **Node 18.0.0 / Chromium 101.0.4951.67** (`out/nwjs_cache/manifest.json`). Node and Blink share one event loop in NW.js, so an in-page HTTP handler can call `$wnd.CircuitJS1.*` directly.
- The MCP spec has two standard transports: stdio and Streamable HTTP; HTTP+SSE (2024-11-05) is deprecated. Revision **2026-07-28** removed protocol sessions, the `initialize` handshake and the GET stream; every POST carries `MCP-Protocol-Version`, `Mcp-Method`, `Mcp-Name` headers; a dual-era server answers with HTTP 400 to trigger the legacy `initialize` fallback. Security text: servers MUST validate `Origin` (403 on mismatch), SHOULD bind to 127.0.0.1, SHOULD authenticate — the threat is DNS rebinding.
- SDK fit on Node 18.0.0: `@modelcontextprotocol/sdk` 1.31.0 (CJS+ESM, `engines >=18`, protocol up to 2025-11-25) — its core `Server`/`McpServer` is usable, but the stock `StreamableHTTPServerTransport` depends on `@hono/node-server` (Node ≥18.14.1) and global `crypto.randomUUID()` (absent in Node 18 by default). The v2 packages (`@modelcontextprotocol/server` 2.2.0, `@modelcontextprotocol/node` 2.1.0, protocol 2026-07-28) need Node ≥20 — not in-app.
- The NW package ships only `target/site` with no `node_modules`, so any JS dependency must be bundled (e.g. esbuild → one CJS file under `war/` or `public/`).
- Claude Code connects to a localhost HTTP server directly (`claude mcp add --transport http circuitjs http://127.0.0.1:<port>/mcp --header "Authorization: Bearer <token>"`, or `.mcp.json` `type: http`). Tool output is capped at 25k tokens by default (`MAX_MCP_OUTPUT_TOKENS`); image content is shown inline. Claude Desktop's config accepts **stdio servers only** — an HTTP server needs a stdio proxy (`mcp-remote` or our own bridge).
- Existing bridge prior art: `server/remote-debug-server.js` (Express + socket.io on `:3030/debug`, `CORS_ORIGIN='*'`) and the page agent `war/scripts/remote-debug-agent.js` (loaded by `?remote-debug=<channel>`, executes `eval(command)`). Its relay pattern (channel → single page agent → viewers, request ids) fits a browser-only target, but `eval` + CORS `*` must not become the MCP path.
- "New window" opens a separate NW instance (`ActionManager.java:215`, `new_instance: true`) with its own `CircuitJS1` — several instances would each try to bind the same port.

**Open questions:**
- Does the SDK v1 core bundled to CJS load and run under Node 18.0.0 via `nw.require`? (prototype needed)
- Multi-instance policy: a server per instance on incrementing ports + discovery file, or one primary server.
- Background-timer throttling of a hidden/unfocused window while an agent drives the simulation (`--disable-background-timer-throttling` in `chromium-args`?).

---

### Entry 2 — 2026-10-01 — Q2: existing automation surface vs. the agent loop

**What was tried / researched:** `CirSim.setupJSInterface` (`CirSim.java:1419-1474`) against [JS_API.md](./JS_API.md); JSON importer/exporter and factory; remote-debug agent; live harness and roundtrip test; log, scope, document and undo plumbing. Evaluated against a 10-step loop: catalogue → create → edit → topology → run → measure → diagnose → visualize → save/load → undo.

**Findings (coverage):**

| Loop step | Existing API | Coverage | Gap |
|---|---|---|---|
| 1 Catalogue | none (harness regexes `*.cache.js`, `tests/live/harness.mjs:587`) | none | No list of the 177 factory keys; no per-type pins/properties/units/defaults; `getEditInfo` not exposed |
| 2 Create | `importFromJson`, `importCircuit` | partial | Always replaces the circuit (flags=0) and resets UI sliders; no append; returns `void`; JSON import does not snap to the grid; `connected_to` auto-wires accept only a string while the exporter writes an array |
| 3 Edit | `setElementProperty`, `updateElementProperties`, `deleteElementById/ByIndex` | weak | `setPropertyValue` overridden in only 4 classes (Resistor, Capacitor, Transformer, TappedTransformer); `applyJsonProperties` resets every omitted key to its default and never re-runs `setPoints`/`allocNodes`; no move/rotate/add-wire/add-into-existing; API delete skips the cleanup `CircuitEditor.doDelete` performs; unknown keys silently ignored |
| 4 Topology | `exportAsJson` `connected_to` / `nodes` | misleading | Geometric adjacency only — not electrical nets (wires, labels, ground not merged); simulator node index, `badConnectionList`, floating nodes not exposed |
| 5 Run/step | `setSimRunning`, `stepSimulation`, `resetSimulation`, `set/getMaxTimeStep` | partial | `stepSimulation` = one synchronous timestep with repaint; no `runFor`/`runUntil`; `setTimeStep` is overwritten by `analyzeCircuit`; API reset does not reset scope graphs; free-run speed is wall-clock bound (not deterministic) |
| 6 Measure | `getNodeVoltage(label)`, element `getVoltageDiff/getCurrent/getPower`, `exportAsJsonWithState`, `getScopeData` | partial | Unknown label returns 0 (indistinguishable from 0 V); scope data = min/max pixel buckets in a ring buffer with no time axis; no add/remove probe API |
| 7 Diagnose | `getSimInfo().stopMessage/errorMessage`, `getLogs` | partial | `stop()` messages are not logged; the culprit `stopElm` is not exposed; warnings not in `getSimInfo`; logs unstructured; import errors appear only as log lines |
| 8 Visualize | `getCircuitAsSVG` | broken | Java `void` → returns `undefined`; result only via async `onsvgrendered` hook, which is skipped on the first call while `canvas2svg.js` loads |
| 9 Save/load | string export/import | partial | No file-path save/open through the API |
| 10 Undo | none | none | No API mutation calls `pushUndo`; undo reloads a text snapshot and regenerates element IDs |
| Multi-doc | none | none | Everything targets `getActiveDocument()`; `DocumentManager.create/setActive/closeDocument` exist in Java but are not exposed |

- **Element identity is unstable** — the top gap. IDs are lazy per-prefix counters, reset only by `clearCircuit` (`CirSim.java:972`), not carried by the text format or undo, can duplicate after JSON import, and the JSON exporter uses a separate scheme (`JsonCircuitExporter.generateElementId`), so `exportAsJsonWithState().elements.X` need not match `getElementById("X")`.
- **Documented vs. implemented drift** in [JS_API.md](./JS_API.md): `getCircuitAsSVG` is not a synchronous string; the `importFromJson` example uses the unregistered type `"VoltageSource"` (silently skipped); the "move element" example has no `schema` block and no `position` wrapper; `setElementProperty`/`updateElementProperties` overstate genericity and state preservation; `setTimeStep` does not stick. [EXPORT_CJS.md](./EXPORT_CJS.md) names types not in the factory (`DCVoltageSource`, `NMOSFET`, `PolarizedCapacitor`).
- **Runtime risks:** everything runs on one JS thread under a per-document GWT `Timer` (16 ms, `CircuitDocument.java:151`); `needAnalyze()` analyzes synchronously only when stopped (`BaseCirSim.java:99`), so topology reads after an edit may be stale while running; hooks (`onupdate`/`ontimestep`/`onsvgrendered`) are single-slot and would be overwritten by competing bridges; `LabeledNodeElm.labelList` and ground node lists are static (session-scoped).

**Open questions:**
- ID policy: persist IDs in the text dump (format change) or keep a session ID↔element map that survives undo.
- Whether one MCP call = one undo step, and whether background tabs are addressable by `docId`.
- Unverified: whether `getElements()[i].getLabelName()` throws on non-label elements.

---

### Entry 3 — 2026-10-01 — Q3: tool ergonomics, prior art, skill shape

**What was tried / researched:** existing circuit/EDA MCP servers; Anthropic guidance on writing tools for agents; MCP tool features (2025-06-18 tools page); Agent Skills authoring best practices; repo checks of JSON geometry requirements and solver diagnostics.

**Findings:**
- **Closest prior art — [Suzu-Gears/circuitjs-mcp](https://github.com/Suzu-Gears/circuitjs-mcp):** runs the CircuitJS web app in headless Chromium via Playwright, 8 coarse tools (`circuit_load` with Falstad text, `circuit_state`, `run_transient(probes, seconds, maxSamples)`, `circuit_export`, `screenshot`, `sim_control`, `eval_js`). Weaknesses: the agent writes raw dump text with 16-px coordinates, raw series without stats, no structured errors or lint, no skill.
- **LLMs corrupt Falstad geometry** ([dul1-git/falstad-ai-prompts](https://github.com/dul1-git/falstad-ai-prompts)): hallucinated coordinates, flags and terminal connections → "Singular matrix", flat 0 V traces, non-starting oscillators. Their mitigation — modify known-good circuits rather than generate from scratch — argues for server-side geometry and a template library.
- **SPICE MCP servers measure instead of dumping** ([clanker-lover/spicebridge](https://github.com/clanker-lover/spicebridge), 28 tools): `validate_netlist` before simulate, `measure_gain/bandwidth/transient/power`, `compare_specs` pass/fail, `draw_schematic`.
- **KiCad MCP servers show tool explosion** (100–185 tools); schematic capture relies on net labels rather than routed wires.
- **Anthropic tool-writing guidance** ([writing-tools-for-agents](https://www.anthropic.com/engineering/writing-tools-for-agents)): consolidate operations, namespace names, human-readable identifiers, `response_format` concise/detailed, paginate/truncate under the 25k-token cap, actionable errors, eval-driven iteration.
- **MCP tool features:** `title`, `outputSchema` + conforming `structuredContent`, annotations (read-only / destructive / idempotent / open-world hints, untrusted by clients), image and `resource_link` content. Tool-execution failures are `isError: true` results the model can self-correct from — lint and solver failures belong there, not in JSON-RPC errors.
- **Agent Skills rules** ([best practices](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/best-practices)): `name` ≤64 chars, lowercase/digits/hyphens, no "claude"/"anthropic"; `description` ≤1024 chars, third person, what + when; body <500 lines; references one level deep with a TOC past 100 lines; copyable checklists and validate→fix loops; MCP tools referenced by fully qualified name; ≥3 evals, tested across model sizes.
- **Repo facts bearing on the design:**
  - JSON pins carry absolute positions; orientation is implied by pin coordinates.
  - `JsonCircuitImporter.createAutoWires()` (~l.389) creates straight, possibly diagonal wires between existing positions. It is not a layout engine.
  - Grid size is 16 px, or 8 with small grid (`CircuitEditor.java:872`).
  - `LabeledNodeElm` (JSON `LabeledNode`) joins same-named labels electrically. A label stub per pin connects without any routing, and those label names are what `getNodeVoltage(name)` reads.
  - The simulator silently ties unconnected nodes to ground through a large resistor, emitting only a console line (`CircuitSimulator` ~l.500–560).
  - The simulator adds an implicit ground at the first voltage-source terminal (~l.408).
  - Together these mean floating nodes read as 0 V, not as an error.
  - 135 element classes implement `getJsonTypeName/getJsonPinNames/getJsonProperties`, so a catalogue can be generated through `CircuitElementFactory`.

**Open questions:**
- Real auto-layout (placement + orthogonal routing) in v1, or label-stub layout first.
- Netlist as a third `CircuitFormatRegistry` format, or MCP-only input.
- "Settled" / DC operating point definition in a transient-only simulator (|dV/dt| < ε, or N·τ).
- Catalogue size: all types or a curated subset with search.

## Alternatives Considered

**A. Where the MCP server runs**

| # | Approach | Pros | Cons | Verdict |
|---|----------|------|------|---------|
| A1 | Streamable HTTP inside the NW.js page via `nw.require('http')`, bound to 127.0.0.1 + Origin/Host check + bearer token; SDK v1 core with a custom ~100-line transport, bundled to one CJS file | The app *is* the server; Claude Code connects directly; no extra process; synchronous access to `CircuitJS1` / Java facade | Node 18.0.0 excludes the stock SDK HTTP transport and SDK v2; dual-era spec compliance is ours; port clash across instances; hidden-window timer throttling | chosen-candidate (pending SDK-on-Node-18.0 prototype) |
| A1b | Same hosting, hand-rolled tools-only JSON-RPC (no SDK) | Zero dependencies, ~200 lines | We track spec changes (header checks, era fallback) ourselves | fallback if A1's prototype fails |
| A2 | Stdio bridge process (`circuitjs-mcp`) on the host's Node ≥20 with SDK v2, transparent proxy to the in-app endpoint; can discover/launch the app | Serves stdio-only hosts (Claude Desktop); full modern-spec compliance; tools defined once in-app | Second process; app still needs its private endpoint | chosen-candidate — the companion "MCP client" piece |
| A2b | Bridge over the existing remote-debug socket.io channel | Exists; covers browser-only/devmode | `eval`, CORS `*`, manual `?remote-debug=` reload | rejected for desktop; possible later for browser-only after hardening |
| A3 | Agent spawns the app over stdio (headless) | Simplest host config | NW GUI has no usable stdio JSON-RPC channel; no headless GWT path | rejected |
| A4 | External Node sidecar driving NW.js over CDP (like `tests/live/harness.mjs` / circuitjs-mcp) | No in-app server code; async-friendly | App is not the server (contradicts the request); needs `--remote-debugging-port`, which is itself an unauthenticated control surface | rejected as primary; the CDP path stays a test tool |

**B. API layer the tools call**

| # | Approach | Pros | Cons | Verdict |
|---|----------|------|------|---------|
| B1 | Wrap today's `CircuitJS1` methods | No Java work | Fails steps 1, 3, 4, 8, 10 and multi-doc; unstable IDs; silent errors | rejected |
| B2 | New Java facade (`AgentApi`, L3) with structured results, its JSNI export kept clustered in the existing bridge per the JSNI-clustering rule | Fixes gaps at the source; typed `{ok, id, errors[]}` results; reusable by the remote-debug agent and tests | Java work across editor, simulator, io; touches undo and ID policy | chosen-candidate |

**C. Authoring mode exposed to the agent**

| # | Approach | Pros | Cons | Verdict |
|---|----------|------|------|---------|
| C1 | Agent writes full JSON/text with coordinates | No layout engine | LLMs corrupt geometry; silent floating nodes; token-heavy | kept only as import (`circuit_load`) |
| C2 | Netlist-first: elements + pins→net names; server places on the grid and connects (label stubs first, orthogonal wires later) | Correct by construction; small payloads; lint speaks net names | Needs a layout step in the app; label-stub drawings look crowded | chosen-candidate (primary) |
| C3 | Fine-grained place/connect/move tools | Easy incremental edits | Round-trips, tool explosion, coordinates leak back | rejected for geometry; net-level edits (`add`/`remove`/`connect`/`set_props`) kept |
| C4 | Template + parameter edit | Most reliable per prior art | Limited to the catalogue | complement to C2 |

## Conclusion

**Verdict:** Feasible. The recommended shape for the concept to decide on:
- **In-app MCP server.** A Streamable HTTP endpoint inside the NW.js page on Node 18.0.0, local-only and authenticated (A1, or A1b if the SDK does not load).
- **Stdio bridge.** A thin bridge, `circuitjs-mcp` (A2), is what the developer's "MCP client" maps to. In MCP terms the agent host is the client, so this piece is a bridge, not a client. It serves stdio-only hosts and doubles as a scripting/test CLI.
- **Java agent facade.** The tools call a new Java facade (B2) that closes the API gaps, because the current `CircuitJS1` surface cannot support editing or debugging.
- **Netlist-first authoring.** The main authoring path is netlist-first (C2 + C4), with lint, measurement and diagnosis built into the tools.
- **Agent skill.** A skill drives the build → lint → simulate → measure → fix loop.

**Key constraints discovered:**
- **Runtime:** NW.js `0.64.1-mod1` means Node 18.0.0 and Chromium 101.
  - Only SDK v1 (protocol ≤ 2025-11-25) can run in-app, and only its core, not its stock HTTP transport. SDK v2 / protocol 2026-07-28 needs Node ≥20.
  - Dependencies must be bundled into `war/`, because the package ships no `node_modules`.
- **Security floor:** the page runs with full Node privileges, so the server must meet all of these:
  - Bind to 127.0.0.1.
  - Validate `Origin` and `Host`.
  - Require a per-run bearer token.
  - Stay off by default.
  - Expose no `eval` tool.
- **Single JS thread:** long runs must be chunked to keep the UI alive, and mutations need the simulation stopped (or a forced analyze) for topology reads to be fresh.
- **Multiple instances:** each NW.js instance is a separate process, so port allocation and discovery are required.
- **Element IDs:** today's IDs are not stable across load/undo/export, so edit tools are blocked until an ID policy exists.
- **Floating nodes:** the simulator hides them (silent ground tie), so a topology lint is mandatory, not optional.
- **Output size:** Claude Code caps tool output at 25k tokens by default, so results are stats plus decimated series. Raw scope buffers are never returned.

**Recommendations for the concept:**
- **Scope as one epic of 3–4 concepts:**
  - (1) agent API facade: catalogue, stable IDs, incremental edit, netlist, diagnostics, `runFor`, probes, undo, documents, SVG;
  - (2) in-app MCP server: transport, security, discovery, tool/resource registry;
  - (3) stdio bridge/CLI;
  - (4) agent skill + evals.
- **Tool inventory:** around 20 namespaced `circuit_*` tools grouped as create / edit / inspect / simulate / measure / debug / file. The researcher's candidate list is in Entry 3 and `.dev_flow/skills/automation/agent-mcp-surface.md`.
  - Tool definitions carry `outputSchema`, `structuredContent` and annotations.
  - Lint and solver failures are returned as `isError` results.
- **Resources:**
  - the element catalogue (generated through `CircuitElementFactory`);
  - the current circuit (netlist view and full JSON);
  - templates;
  - the example corpus (`public/circuits`).
- **Skill:**
  - an `authoring-circuitjs-circuits` SKILL.md workflow checklist;
  - references: elements, netlist format, error codes → fixes, patterns, legacy text;
  - at least 3 evals (RC low-pass at fc = 1 kHz, LED current limit, debugging a broken amplifier).
- **Decision forks to settle in the concept interview:**
  - ID policy;
  - one MCP call = one undo step;
  - active-tab only vs. `docId`;
  - real auto-layout in v1 vs. label stubs;
  - netlist as a registry format vs. MCP-only;
  - DC-op/settle definition;
  - multi-instance discovery;
  - the protocol era served in-app (legacy only, with the bridge serving modern, vs. dual-era);
  - whether to add the background-throttling `chromium-args`.
- **Exclude/defer:**
  - browser-only build as a target (only via a hardened relay later);
  - AC/frequency analysis (the simulator is transient-only; measure from transient);
  - `eval_js` escape hatch;
  - Claude Desktop "custom connectors" (remote-brokered, cannot reach localhost — unverified).
- **Doc fixes, independent of the feature:** the [JS_API.md](./JS_API.md) drift listed in Entry 2 (`getCircuitAsSVG`, the `"VoltageSource"` example, the move example, the property-setter claims) is worth a propagate pass.
- **First implementation step:** a ≤1-hour prototype that loads the bundled SDK v1 core in NW's Node 18.0.0 and answers `tools/list` from Claude Code. It decides A1 vs A1b.

**Artifacts to keep:**
- — (npm metadata and the SDK 1.31.0 tarball are cheap to re-fetch; not promoted)

**Artifacts to discard:**
- `/tmp/claude-1000/circuitjs1-desktop/downloads/` (SDK tarball, npm metadata) and `/tmp/claude-1000/circuitjs1-desktop/spike/` (researcher notes)
