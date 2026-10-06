# MCP end-to-end harness

`e2e.mjs` checks the in-app MCP server ([SP_MCP](../../docs/mcp-server.sp.md), plan [PL_MCP Phase 4](../../docs/mcp-server.plan.md#PL_MCP_P4)) and the path-based file contracts of the Agent API ([SP_AGA_02_14](../../docs/agent-api.sp.md#SP_AGA_02_14), [PL_AGA Phase 9](../../docs/agent-api.plan.md#PL_AGA_P9)) against a real NW.js instance. Headless Chromium (`tests/live`) has no Node, so it cannot run the server or touch the file system.

Every scenario launches the NW.js SDK binary (`node_modules/nw/nwjs/nw`) on `target/site`. Each launch gets its own scratch `HOME` (instance registry `~/.circuitjs1/instances`, XDG directories) and `--user-data-dir` (preferences in `localStorage`) under `OUT_DIR/run/<launch>/`. The harness never touches the real `~/.circuitjs1`, the real NW profile or the Claude Code configuration. It drives the endpoint with raw JSON-RPC (`fetch`, raw sockets) and the MCP SDK client, and drives the page through CDP (`--remote-debugging-port`). At the end of each scenario it ends the NW process group with SIGTERM, then SIGKILL, and waits until the group is gone, so ports 7311..7330 are free for the next scenario.

## Requirements

- Node 22 or later for the harness (built-in `WebSocket`, `fetch`). The app itself runs on the embedded Node 18.0.0 of NW.js.
- `npm install` at the repository root (NW.js 0.64.1 SDK flavor, `@modelcontextprotocol/sdk` for the SDK client rows).
- A compiled build: `npm run buildgwt` (GWT and `scripts/mcp-server.js` in `target/site/`).
- Linux with `Xvfb` (Arch `xorg-server-xvfb`, Debian/Ubuntu `xvfb`). The harness starts one Xvfb of its own (`-displayfd`, so no fixed display number) and stops it with SIGTERM, which removes its lock file. Without Xvfb, or with `NW_DISPLAY=1`, the NW windows open on `$DISPLAY`. No window manager is needed.
- Ports 7311..7330 and 7400 free: close every running CircuitJS1 instance first. The harness exits with code 2 when they are busy.
- Optional, `clients` group only: `npx` with network access (MCP Inspector CLI) and a logged-in `claude` CLI (Claude Code).
- Bridge rows (`bridge`, `bridge_long`, `bridge_clients`): `npm install` in `mcp/bridge/` ([mcp/bridge/README.md](../../mcp/bridge/README.md)); without it those rows print SKIP. They also need `ps` (process groups of the instances the bridge starts).

## Run

```bash
npm run buildgwt
npm run test:mcp                        # default group: fast, deterministic (about 2 minutes)
npm run test:mcp -- slow                # timeout probes (~100 s) and the 120 s budget run
npm run test:mcp -- clients             # Inspector CLI and Claude Code (optional, auto-SKIP)
npm run test:mcp -- all                 # every group
node tests/mcp/e2e.mjs tools files      # single scenarios
node tests/mcp/e2e.mjs --list           # group, scenario and rows, without running
```

Each row prints one line, `PASS|FAIL|SKIP <ID> <title> {json}`. A FAIL lists the failed checks with their evidence. The run ends with `SUMMARY {"pass":…,"fail":…,"skip":…}`. The rows of groups that were not selected, and the manual rows, print SKIP with a reason. Exit code: 0 when no row fails (SKIP allowed), 1 when any row fails, 2 on a harness error (no build, no display, ports busy, bad argument).

**Ports busy because a CircuitJS1 app is running.** The harness needs 7311..7330 and 7400 free. Instead of closing the app, run it in an own network namespace (its own loopback; the running app is untouched): `unshare -rn sh -c 'ip link set lo up && npm run test:mcp'`. Inside `unshare -r` the process is root of the namespace, so the row "saveFile: overwrite rules…" fails its `readOnlyDirEacces` check (root writes into the read-only fixture directory); every other row is meaningful. There are no LAN addresses in the namespace, so the LAN rows check nothing.

| Variable | Default | Meaning |
|---|---|---|
| `SITE_DIR` | `target/site` | Site root with `circuitjs.html` and `scripts/mcp-server.js` |
| `NW_BIN` | `node_modules/nw/nwjs/nw` | NW.js binary |
| `OUT_DIR` | `<os tmpdir>/circuitjs-mcp-e2e` | Scratch homes, profiles, fixtures, `nw.log`/`nw.err` per launch, `results.json` |
| `NW_DISPLAY` | unset | `1`: use `$DISPLAY` instead of an own Xvfb |
| `INSPECTOR_PKG` | `@modelcontextprotocol/inspector@latest` | Inspector package for `npx` |
| `CLAUDE_MODEL` | `haiku` | Model of the Claude Code check |
| `VERBOSE` | unset | Also list passed checks and scenario times |

## Scenarios

| Group | Scenario | Rows |
|---|---|---|
| default | `endpoint` | SP_MCP_01_02 instance record (one 0600 record in a 0700 directory, logged); SP_MCP_02_01 initialize revisions (2025-11-25, 2025-06-18, older → 2025-11-25), `server/discover` → 400, DELETE 405, -32700/-32600 (batch)/-32601/-32602 (unknown tool), tool-call counter, equal ids of concurrent clients, other path 404; SP_MCP_01_01 default listening address `127.0.0.1` (status and record host `127.0.0.1`, URLs exactly the loopback one, the port refuses connections on every LAN IPv4 address of the machine); SP_MCP_05_01 Origin foreign 403, no Origin 200, GET 405, unsupported revision header 400 with a JSON-RPC body, arbitrary `Mcp-Session-Id`, notification 202 with empty body, unknown URI -32002; SP_MCP_03_01 `null` Origin 403 and local origins 200; SP_MCP_02_05 File → Exit removes the record and releases the port |
| default | `hostile` | Requests the SDK's strict schema would drop (extra key, `params: []`, `_meta` string, object/null id, three at once) → -32600 within 3 s; an invalid notification → 202; bad params → -32602 naming the field; 400 `notifications/cancelled` for every plausible internal id while 30 requests are in flight; 413 for a declared and for a streamed body over 16 MB, with the connection closed |
| default | `portbusy` | SP_MCP_05_01 port busy: 7311 taken → listening on 7312, record says 7312 |
| default | `allbusy` | SP_MCP_05_04 all 20 ports taken → `failed: no free port in range 7311..7330`, logged, no record, Agent API usable, menu item without "(off)", info dialog shows the reason |
| default | `instances` | SP_MCP_05_02 "New window" (`new_instance`) → two records with distinct ports and pids, the second serves; `close()` of it → one record, port released; SP_MCP_02_05 `close(true)` → no record |
| default | `stale` | SP_MCP_05_04 crash record: dead-pid record and temp file deleted; live-pid, unparseable and foreign files kept; logged |
| default | `tools` | Through the SDK client, with a page recorder around `CircuitJS1Agent.callAsync`: SP_MCP_02_02 handshake, 15 tools, 4 resources; SP_MCP_05_01 `circuit_layout` on the SP_AGA T8 fixture (one `text_overlap`, read-only and idempotent) and with `includeBoxes` on 2000 grounded chain resistors with 5 crossed values (reduced once to `includeBoxes: false`, note, the 5 issues kept); SP_MCP_05_01 `circuit_edit` domain error and schema error (no Agent API call), `circuit_render` png, catalogue, unknown URI/type, path outside the examples index, agent-format, documents, d1 → d2 round trip, file rule (`/etc/x.conf`) with a `.json` save/open, templates, examples, sizing on `alu74181.txt`; SP_MCP_05_04 huge result; SP_MCP_05_02 the three invariants over every tool result of the scenario, with 16 probes at Σ `maxPoints` 2000 filled to the decimation maximum (16 × 124 = 1984 points, SP_AGA_03_07); all 15 tools called |
| default | `long` | SP_MCP_05_03 three `circuit_get` from a second client answered during a 5 s `circuit_run` |
| default | `dialog` | SP_MCP_05_01 info dialog listening (status, instance ID, URLs, command line, default settings) and counter (3 calls live, 4 after reopening); SP_MCP_02_04 Copy (a CDP click; `nw.Clipboard`), Close, Escape |
| default | `settings` | One profile over three launches with `nw.App.quit()` between them: invalid port/host rejected, nothing stored; SP_MCP_05_01 disable → restart → `disabled`, no port bound, no record, menu "(off)", dialog `disabled`; re-enable on `0.0.0.0:7400` with Enter → restart → listening with the `127.0.0.1` URL first, then one per LAN IPv4 address (status, record and dialog), and a LAN URL answers |
| default | `files` | SP_AGA_05_01 `saveFile` json (reopened with identical IDs), not allowed (`.md`), overwrite foreign, overwrite prose text, `openFile` missing; SP_AGA_03_09 / 02_14: relative path, link to `.md`, empty/blank/circuit overwrite, write through a symlink, dangling link, directory, missing parent, read-only directory (EACCES), no staging file left, `no_path`, explicit format, save to the document's path seals the transaction (`auto`); open of `.md`, link, directory, > 10 MB, prose, a failing parse and off-lattice JSON without disclosing content or keys, no document or closed-tab entry, BOM file; SP_AGA_03_03 a text and a JSON file naming unknown diode models open with one `value_adjusted` per element and register nothing (a later `add` of the name is `invalid_value`); into a background handle (visible tab unchanged), rejected into a handle, `activate`; foreign staging name (EEXIST); busy policy during a run |
| default | `bg_files` | SP_AGA_05_02 R1: the visible tab free-runs the `tests/live` reference fixture; on background X `createDocument`, `importCircuit`, `checkpoint`, `applyEdits`, `undo`, `run` (2 s), `render`, `exportCircuit`, **`openFile` into X**, `closeDocument`; an R1 sample (copy of the `agent_bg` page helpers) after each call and at every slice end equals the pre-call sample; Sliders dialog not rebuilt; active-tab rate ≥ 50 %. R2: the `agent_bg` sequence plus `openFile` into the document on background X and on active Y → equal circuit text (scope scales masked), UI state, title, modified flag (cleared), file path, undo depth, log buffer, closed-tab dumps |
| default | `bridge` | The stdio bridge `circuitjs-mcp` ([SP_MCB_05](../../docs/mcp-bridge.sp.md#SP_MCB_05)) as a stdio server (SDK client over stdio) and as a CLI, with a scratch HOME and an app wrapper (NW.js with its own profile per start, so a second start is not handed to the running instance). Rows: no app (3 bridge tools, "No CircuitJS1 instance"); `bridge_select` unknown / unreachable / both given (-32602), `bridge_launch` without an app; `bridge_launch` with `lrc.txt` (new active document holds the file, list_changed); with the app: 15 tools unchanged + 3 bridge tools, the app's instructions + the bridge sentence; SP_MCP_05_03 bridge forwarding and SP_MCB_05_02 transparency (10 tool/resource cases and a -32602 equal to the direct result; `tools` minus `bridge_*` = the target list, stdio and CLI); CLI exit codes 0/1/2/3, `instances` with 2 live + 1 stale, `read`, `launch` with a file; eval row `call circuit_connectivity`; edges: stale records only, explicit URL unreachable, stdin = inline (400 elements); two windows (select each → its documents); app closed mid-session (`Instance gone`, then re-resolution); stdout carries the protocol only |
| default (always) | `manual` | SKIP rows: SP_MCP_05_01 hidden window and SP_MCP_05_03 private-network agent (manual, below), SP_MCB_05_01 Claude Desktop (manual, [mcp/bridge/README.md](../../mcp/bridge/README.md#manual-rows)), SP_MCP_05_04 browser build (live scenario `mcp_browser`) |
| slow | `hostile_slow` | An incomplete header block and an incomplete body are closed by the server (`headersTimeout` 10 s, `requestTimeout` 60 s; Node checks every 30 s), and the server keeps answering |
| slow | `long120` | `circuit_run` with `budgetMs: 120000` answers (`budget_exhausted`) inside the host timeout; reads from a second client are served meanwhile |
| slow | `bridge_long` | SP_MCB_05_04 `circuitjs-mcp call circuit_run` with `budgetMs: 120000` → exit 0, `budget_exhausted`, within the 130 s default forward timeout |
| clients | `bridge_clients` | Claude Code over stdio: a temporary `--mcp-config` with `command: node mcp/bridge/bin/circuitjs-mcp.js --registry <scratch>`, `--strict-mcp-config` → `connected`, 15 + 3 tools, a real `circuit_types` call through the bridge |
| clients | `clients` | MCP Inspector CLI `tools/list` 15, `resources/list` 4, `resources/templates/list` 3; Claude Code `claude -p --mcp-config <tmp> --strict-mcp-config --no-session-persistence --model haiku`: server `connected` with 15 `mcp__circuitjs__*` tools, and a real `circuit_types` call. Each row prints SKIP when its tool is missing or does not run non-interactively (no network, not logged in) |

## Manual rows

These rows need a person at the machine (a real window manager) or a second machine. Record each run below with the date, the build and `observed` or the deviation.

**SP_MCP_05_01 "Runtime settings: hidden window"** ([SP_MCP_02_06](../../docs/mcp-server.sp.md#SP_MCP_02_06)). Steps:
1. `npm run build` and start the packaged app, or `npm start`, on a desktop with a window manager.
2. Connect a client: `claude mcp add --transport http circuitjs http://127.0.0.1:7311/mcp`, or use the SDK client.
3. Import a small RC circuit (`circuit_import`), then call `circuit_run` with `span` set so that the run needs about 2 s of wall time (for example `span: "2 s"` with `maxTimeStep: "5 us"`; check `wallMs`). Note `wallMs`.
4. Minimise the window (also try covering it with another window), and repeat the same `circuit_run` with `reset: true`. Note `wallMs`.
5. Pass: the hidden-window `wallMs` is within 25 % of the visible one.

**SP_MCP_05_03 "Private-network agent"**. Steps:
1. Start the app. With the default listening address `127.0.0.1` the dialog shows only `http://127.0.0.1:7311/mcp`, and from the other machine the port does not answer (`curl -m 3 http://<lan-ip>:7311/mcp` fails with "connection refused").
2. Open Options → "MCP Server...", set the listening address to `0.0.0.0`, press Save and restart the app. Open the dialog again and copy a LAN URL (`http://<lan-ip>:7311/mcp`).
3. On another machine of the same LAN: `claude mcp add --transport http circuitjs http://<lan-ip>:7311/mcp`, then ask for a `circuit_types` call (or use the Inspector CLI: `npx @modelcontextprotocol/inspector --cli http://<lan-ip>:7311/mcp --transport http --method tools/call --tool-name circuit_types`).
4. Pass: step 1 refused, the tools are usable after step 2, and no token or other credential is asked for. Afterwards set the address back to `127.0.0.1`.

| Row | Date | Build / platform | Result | By |
|---|---|---|---|---|
| SP_MCP_05_01 Runtime settings: hidden window | — | — | not yet observed | — |
| SP_MCP_05_03 Private-network agent | — | — | not yet observed | — |
