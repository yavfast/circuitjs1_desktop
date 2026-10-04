# In-app MCP Server — Specification  {#SP_MCP}

> **Code:** SP_MCP
> **Status:** draft
> **Created:** 2026-10-01
> **Updated:** 2026-10-04
>
> **Concept:** [C_MCP](./mcp-server.concept.md)
> **Depends on:** [SP_AGA](./agent-api.sp.md), [SP_USR](./user-preferences.sp.md)
> **Used by:** [SP_MCB](./mcp-bridge.sp.md), [SP_AGS](./agent-skill.sp.md)
> **Plan:** [mcp-server.plan.md](./mcp-server.plan.md)
>
> This specification defines the in-app MCP endpoint: its HTTP behaviour, preferences, instance records, the tool and resource catalogue mapped onto [SP_AGA](./agent-api.sp.md) contracts, result shaping, and the user-visible server info. Read it to implement the server, to add a tool, or to write the bridge against it. The protocol layer is resolved as the SDK 1.x core with a custom HTTP transport ([C_MCP_DEC_03](./mcp-server.concept.md#C_MCP_DEC_03)); this spec fixes the behaviour that layer must satisfy.

## Contents

- [01. Data Structures](#SP_MCP_01) — preferences, instance record, tool descriptor, tool result
- [02. Contracts](#SP_MCP_02) — endpoint behaviour, the tool catalogue, resources, server info
- [03. Validation Rules](#SP_MCP_03) — origin rule, argument validation, error mapping, sizing
- [04. State Transitions](#SP_MCP_04) — server lifecycle and instance record lifecycle
- [05. Verification Criteria](#SP_MCP_05) — functional, invariant, integration, edge-case checks
- [06. Reversibility](#SP_MCP_06) — disabling and removing the server
- [07. Design Decisions](#SP_MCP_DEC) — tool granularity, response transport

## 01. Data Structures  {#SP_MCP_01}

> Implements: [C_MCP_02](./mcp-server.concept.md#C_MCP_02)

### 01_01. Server preferences  {#SP_MCP_01_01}

Stored in the user-preferences store ([SP_USR](./user-preferences.sp.md#SP_USR_01_01)), with no URL-query layer; they are read once at start-up, so changes apply at the next app start. An invalid stored value is replaced by its default for this run, a warning log line names the replaced keys, and the stored value is not rewritten.

| Key | Type | Default | Constraints | Description |
|-----|------|---------|-------------|-------------|
| mcpServerEnabled | bool | true | exactly `"true"` or `"false"` | Start the endpoint with the app ([C_MCP_DEC_02](./mcp-server.concept.md#C_MCP_DEC_02)) |
| mcpServerPort | int | 7311 | 1024..65535; `mcpServerPort + mcpServerPortRange − 1 ≤ 65535` | Base port |
| mcpServerPortRange | int | 20 | 1..100; `mcpServerPort + mcpServerPortRange − 1 ≤ 65535`; an invalid value falls back to 20, or to `65535 − mcpServerPort + 1` when 20 does not fit | Number of consecutive ports tried |
| mcpServerHost | string | `127.0.0.1` | trimmed; `localhost`, an IPv4 dotted quad or an IPv6 literal without brackets or zone | Listening address; the default is the local machine only, `0.0.0.0` (or a LAN address) opens the server to the private network ([C_MCP_DEC_02](./mcp-server.concept.md#C_MCP_DEC_02)) |

### 01_02. Instance record  {#SP_MCP_01_02}

One JSON file `<instanceId>.json` in the instance directory `<user home>/.circuitjs1/instances/`.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| instanceId | string | yes | `<pid>-<startedAtMs>` |
| pid | int | yes | Process ID |
| port | int | yes | Bound port |
| host | string | yes | Listening address |
| urls | string[] | yes | For a wildcard `host` (`0.0.0.0`, `::`): `http://127.0.0.1:<port>/mcp` first, then one URL per non-internal IPv4 address. For a loopback `host`: one loopback URL only — `http://127.0.0.1:<port>/mcp` for `localhost` and any `127.x.x.x` address, `http://[::1]:<port>/mcp` for `::1`. For one specific address: that address's URL only (127.0.0.1 does not answer there) |
| appVersion | string | yes | App version from the manifest |
| startedAt | string | yes | ISO-8601 UTC |
| title | string | yes | Window title at start |
| protocolRevisions | string[] | yes | Protocol revisions the endpoint serves |
| toolsVersion | string | yes | Version `MAJOR.MINOR` of the tool and resource contract ([§06_01](#SP_MCP_06_01)) |

### 01_03. Tool descriptor  {#SP_MCP_01_03}

| Field | Type | Description |
|-------|------|-------------|
| name | string | `circuit_<verb>` ([§02_02](#SP_MCP_02_02)) |
| title | string | Human-readable title |
| description | string | Agent-facing description: purpose, when to use, key arguments, what comes back; ≤ 1200 chars |
| inputSchema | JSON Schema object | Derived from the mapped SP_AGA contract inputs |
| outputSchema | JSON Schema object | OperationResult ([SP_AGA_01_08](./agent-api.sp.md#SP_AGA_01_08)) with the contract's `data` shape |
| annotations | {readOnlyHint, destructiveHint, idempotentHint, openWorldHint: bool} | Per [§02_02](#SP_MCP_02_02); `openWorldHint` is false for every tool |

### 01_04. Tool result  {#SP_MCP_01_04}

| Part | Content |
|------|---------|
| structuredContent | The SP_AGA OperationResult |
| content[0] | Text: the OperationResult serialized as compact JSON |
| content[1] | Image (`image/png`) for `circuit_render` with `format=png`; omitted otherwise |
| isError | `true` exactly when `ok = false` |

For `circuit_render` with `format=png` the base64 PNG appears only in the image part; `structuredContent.data.content` is replaced by `"<image>"` (the only deviation of `structuredContent` from the Agent API result).

## 02. Contracts  {#SP_MCP_02}

### 02_01. Endpoint behaviour  {#SP_MCP_02_01}

- **Address.** `POST http://<host>:<port>/mcp`, `Content-Type: application/json`, one JSON-RPC message per request.
- **Response.** Replies are `application/json` single responses. No server-initiated stream: `GET /mcp` and `DELETE /mcp` answer 405. Notifications from the client answer 202 with no body, and so does a JSON-RPC response sent by the client (the server sends no requests). `notifications/cancelled` is dropped: a call cannot be cancelled ([DEC_02](#SP_MCP_DEC_02)), and forwarding it could abort another client's request.
- **Protocol revisions.** The endpoint serves the initialize-based revisions `2025-11-25` and `2025-06-18`. `initialize` answers with the client's requested revision when it is served, else with `2025-11-25`. The stateless `2026-07-28` revision is not served in-app ([C_MCP_DEC_03](./mcp-server.concept.md#C_MCP_DEC_03)); serving it is a backlog item of [PL_MCP](./mcp-server.plan.md).
- **Version header.** A request whose `MCP-Protocol-Version` header names a revision the endpoint does not serve gets HTTP 400 with a JSON-RPC error body, which lets dual-era clients fall back. A request without the header is treated as `2025-06-18`.
- **Other requests.** Paths other than `/mcp` answer 404; a body over 16 MB answers 413; a JSON-RPC batch is rejected with `-32600`, unparseable JSON with `-32700`. `OPTIONS` preflight requests from allowed (local) origins are answered with CORS headers; other origins get the 403 of the Origin rule. HTTP status per error: 400 for `-32700` and `-32600` (and an unserved version header); 200 for errors of a well-formed request (`-32601`, `-32602`, tool errors, and the `-32603` of a request that got no response within the 200 s backstop); 500 with `-32603` when the dispatch of a request fails with an exception; 503 with `-32603` "Server is shutting down" for a request still pending when the server closes, and 200 with the same error for a request that arrives after the protocol layer has closed. An `id` that is not a string or number is answered as `null`. Client-supplied text echoed in an error message (object keys, tool names, URIs) is cut to a bounded length.
- **Timeouts.** The HTTP server closes a request whose headers do not arrive within 10 s or whose whole request does not arrive within 60 s; a long-running response is not limited by these. An asynchronous Agent API call (`run`, `render`) that never calls back ends after 180 s as an `internal_error` tool result. A request the protocol layer never answers gets `-32603` after a 200 s backstop, so every request gets exactly one reply.
- **Sessions.** The server keeps no protocol session state and issues no `Mcp-Session-Id`. Session headers sent by clients are ignored.
- **Capabilities.** `tools` (with `listChanged: false`) and `resources` (with `listChanged: false`, `subscribe: false`). No `prompts`, no sampling, no elicitation.
- **Server info.** `serverInfo` = `{name: "circuitjs1", version: <appVersion>}`. `instructions` is one paragraph naming the coordinate unit (grid cells), the verify loop (connectivity report → run → measure), the skill name `circuitjs-circuits` and the `toolsVersion`.
- **Concurrency.** Requests are processed on the app's event loop. A `circuit_run` request answers when its run ends, and other requests are served meanwhile.

### 02_02. Tool catalogue  {#SP_MCP_02_02}

> **Criticality:** critical

Every tool takes the `doc` argument of SP_AGA contracts, optional except where the contract requires it (`circuit_documents` `activate`/`close`).

| Tool | Maps to (SP_AGA) | Arguments (beyond `doc`) | readOnlyHint | destructiveHint | idempotentHint |
|------|------------------|---------------------------|----------|-------------|------------|
| circuit_types | listTypes / describeType / listModels | `type?` (describe when given), `filter?`, `models?: diode\|transistor\|logic\|subcircuit\|all` and `model?: name` (listModels when `models` is given, `all` = no `kind`; `model` = listModels `name` and needs one kind; SP_AGA §02_15). `type` together with `models`, `model` without `models`, and `model` with `models: all` are JSON-RPC -32602 (§02 argument rule) | true | false | true |
| circuit_documents | listDocuments / createDocument / activateDocument / closeDocument | `action: list\|create\|activate\|close`, `title?`, `activate?`, `discardChanges?` | false | true (`close`) | false |
| circuit_import | importCircuit | `circuit` (AgentCircuit object or text) | false | true (replaces the circuit) | false |
| circuit_edit | applyEdits | `edits[]` (incl. `defineModel` with a ModelSpec, SP_AGA §01_13; create-only, no `replace`) | false | true (`delete`) | false |
| circuit_get | getCircuit | `detail?`, `ids?`, `offset?`, `limit?` | true | false | true |
| circuit_connectivity | getConnectivity | `includeNets?`, `netFilter?` | true | false | true |
| circuit_read | read | `targets[]` | true | false | true |
| circuit_render | render | `format?`, `scale?`, `includeScopes?` | true | false | true |
| circuit_sim | simControl | `action`, `settings?` | false | true (`reset`) | false |
| circuit_run | run | `mode?`, `span?`, `settle?`, `budgetMs?`, `probes?`, `recordFrom?`, `maxPoints?`, `reset?` | false | true (`reset`) | false |
| circuit_diagnostics | getDiagnostics | `log?` | true | false | true |
| circuit_checkpoint | checkpoint | `comment` | false | false | false |
| circuit_history | getHistory / undo / redo / restoreCheckpoint | `action: list\|undo\|redo\|restore`, `steps?`, `checkpointId?`, `limit?` | false | true (`undo`, `restore`) | false |
| circuit_file | openFile / saveFile / exportCircuit | `action: open\|save\|export`, `path?`, `into?`, `activate?`, `format?` | false | true (`save` overwrite) | false |

Annotations are the most conservative values over the tool's actions: a tool with any mutating action declares `readOnlyHint: false`, and `destructiveHint` is true when any action is destructive. `openWorldHint` is false for every tool.

Every tool description contains these points, all stated in the tool text:
- what the tool does;
- that coordinates are grid cells (for tools that take geometry);
- the default `doc` behaviour;
- one minimal argument example.

### 02_03. Resources  {#SP_MCP_02_03}

| URI (or template) | MIME | Content | Maps to |
|-------------------|------|---------|---------|
| `circuitjs://catalogue` | application/json | Type index | listTypes |
| `circuitjs://catalogue/{type}` | application/json | TypeInfo | describeType |
| `circuitjs://documents` | application/json | Document list | listDocuments |
| `circuitjs://documents/{doc}/circuit` | application/json | `{elements: ElementRecord[], simulation, scopes, models?, modelsTruncated?}` of the document at full detail, all pages; importable unchanged through `circuit_import`. `models` is present when the circuit uses a non-built-in model; `modelsTruncated` when > 0 | getCircuit (`detail: full`, all pages) |
| `circuitjs://examples` | application/json | `{path, title, menu}[]` of the bundled example circuits (`menu`: the Circuits submenu path) | example index of the app package |
| `circuitjs://examples/{path}` | text/plain | Example circuit text (legacy format); only paths listed in the index | bundled example file |
| `circuitjs://docs/agent-format` | text/markdown | Coordinate model, ElementSpec, edit ops, issue codes | text shipped with the app |

`resources/list` lists the fixed URIs. `resources/templates/list` lists the templates. An unknown URI returns JSON-RPC error -32002 (resource not found), as do an unknown type, an unknown document and an example path not in the index; any other rejection of the mapped Agent API call is -32603 carrying the issue.

### 02_04. Server info for the user  {#SP_MCP_02_04}

The Options menu has an item "MCP Server..." (three dots, as every other menu item) that opens an info dialog through the dialog router. The dialog shows:
- status (`listening` or `failed: <reason>` or `disabled`), shown untranslated: these are the wire names of [§04_01](#SP_MCP_04_01), also used in the log;
- instance ID;
- the `urls` of the instance record;
- one copyable command line `claude mcp add --transport http circuitjs <first URL>` (read-only text with a Copy button that writes the system clipboard only); without a URL (any state but `listening`) the instance ID and URL rows show "—", and the command box and Copy are empty and disabled;
- the count of handled tool calls in this run (0 when the server is disabled or failed; the last count after it stops);
- editable settings: an "Enabled" checkbox, the base port and the listening address. "Save" writes them to the preferences ([§01_01](#SP_MCP_01_01)) and states that they apply at the next start.

The menu item text is "MCP Server...", followed by "(off)" when the server is disabled. The port range is not edited in the dialog; the base port is validated against the stored range.

### 02_05. Start-up and shutdown  {#SP_MCP_02_05}

Processing logic:

    FUNCTION startServer():                               # called by the app's own start-up, right after the Agent API export
                                                          # (not through the single-slot "loaded" page hook)
        read and validate the preferences (§01_01); an invalid value falls back to its default; log one warning naming the keys
        IF the server script is not loaded (no window.CircuitJS1Mcp):
            IF desktop runtime: status ← failed("server script not loaded (scripts/mcp-server.js)"); log; RETURN
            ELSE: status ← disabled("no desktop runtime"); RETURN
        IF NOT desktop runtime: status ← disabled("no desktop runtime"); log; RETURN
        IF NOT pref.mcpServerEnabled: status ← disabled("disabled in preferences"); log; RETURN
        status ← starting
        FOR port IN pref.mcpServerPort .. pref.mcpServerPort + pref.mcpServerPortRange − 1:
            IF listen(pref.mcpServerHost, port) succeeds: BREAK
            IF the error is not EADDRINUSE or EACCES: BREAK      # a reserved port counts as busy
        IF not listening:
            status ← failed("no free port in range <first>..<last>")     # last error EADDRINUSE / EACCES
                  OR failed("listen on <host> failed: <message>")        # any other error
            log; RETURN
        delete dead-pid instance records; write instance record (§01_02) atomically (temp file + rename), file mode user-only
        status ← listening; log "MCP server listening on <urls>"

    FUNCTION stopServer():                                # window close / app exit
        close listener; delete own instance record

### 02_06. Desktop runtime settings  {#SP_MCP_02_06}

The package manifest's Chromium arguments gain `--disable-background-timer-throttling`, `--disable-renderer-backgrounding` and `--disable-backgrounding-occluded-windows`, so agent runs keep their pace while the window is hidden, minimised or covered ([C_MCP_03_01](./mcp-server.concept.md#C_MCP_03_01)).

## 03. Validation Rules  {#SP_MCP_03}

### 03_01. Origin rule  {#SP_MCP_03_01}

- A request without an `Origin` header is accepted.
- A request whose `Origin` host is `localhost`, `127.0.0.1` or `[::1]` (any port) is accepted.
- Any other `Origin` is answered with HTTP 403 and an empty body.
- No other authentication is performed ([C_MCP_DEC_02](./mcp-server.concept.md#C_MCP_DEC_02)).

### 03_02. Argument validation  {#SP_MCP_03_02}

- Arguments are validated against `inputSchema` before mapping. Type, required-field and enum violations are JSON-RPC errors -32602 with a message naming the field.
- Unknown arguments, an argument that the chosen `action` does not take, and an action's own required arguments (`doc` for `circuit_documents` `activate`/`close`, `checkpointId` for `restore`, `path` for `open`, `settings` for `configure`) are -32602 too. Inapplicable `circuit_types` argument combinations (`type` with `models`, `model` without `models`, `model` with `models: all`) are -32602 as well.
- Range keywords in the schemas (minimum, maximum, item counts, patterns) inform the agent and are not enforced by the server: ranges belong to the domain validation below.
- Domain validation (lattice, IDs, property keys, value formats) is left to the Agent API and comes back as `isError` tool results.

### 03_03. Error mapping  {#SP_MCP_03_03}

| Condition | Response |
|-----------|----------|
| Body not JSON | JSON-RPC -32700 |
| Not a JSON-RPC request object | JSON-RPC -32600 |
| Unknown method | JSON-RPC -32601 |
| Unknown tool name / schema violation | JSON-RPC -32602 |
| Agent API returned `ok = false` | Tool result, `isError: true` |
| SVG render text or export content over the size limit ([§03_04](#SP_MCP_03_04)) | Tool result, `isError: true`, server issue `result_too_large` (error) |
| Unexpected exception in a tool | Tool result, `isError: true`, issue `internal_error` with the exception message; the exception is passed to the application's global uncaught-exception handler through the Agent API export (RULE_ERR_004) |

### 03_04. Sizing  {#SP_MCP_03_04}

- **Limit.** The serialized text part of a tool result stays ≤ 60 000 characters. This is about 20 000 tokens for numeric JSON at about 3 characters per token, below the host's default 25 000-token cap.
- **Runs.** `circuit_run` results stay within the limit by the Agent API caps (Σ `maxPoints` ≤ 2000, 6 significant digits) and are never re-requested.
- **Mutating tools.** These are never re-executed. Their results stay within the limit by the Agent API caps (≤ 50 element records, ≤ 50 issues per list, `ids` only up to 200 elements).
- **Read-only tools.** A read-only tool whose result would exceed the limit is re-executed with a smaller request, and the result of that *effective* call is returned:
  - `circuit_get`: `detail: "concise"`, then `limit` halved until it fits (`nextOffset` tells the agent where to continue);
  - `circuit_connectivity`: `includeNets: false` (issues only);
  - `circuit_diagnostics`: log `limit` halved until it fits;
  - `circuit_types`: never exceeds (bounded by the catalogue);
  - `circuit_render` with `format=svg`: when the SVG text exceeds the limit, the result is `isError` with issue `result_too_large` and hint "Use format png or a lower scale."
  - `circuit_file` with `action: export`: when the content exceeds the limit, the result is `isError` with issue `result_too_large` and hint "Use action save, or circuit_get pages."
  The text part then starts with a note naming the reduced arguments.
- **Fallback.** A result that still exceeds the limit after its reductions, or of a tool that is never re-executed, keeps its whole OperationResult in `structuredContent`; only its text part drops the trailing items of its largest arrays and names them in a leading note (whole items only, never mid-structure). The Agent API caps keep every measured result below this point.
- **Resources.** Resource reads are not tool results and are not subject to this limit; `circuitjs://documents/{doc}/circuit` and example texts are returned whole.
- **Structure.** A result is never cut mid-structure.

## 04. State Transitions  {#SP_MCP_04}

### 04_01. Server lifecycle  {#SP_MCP_04_01}

    [disabled] (pref off or no desktop runtime)
    [starting] --listen ok--> [listening] --window unload--> [stopped]
    [starting] --no free port--> [failed]

| From | To | Condition | Side effects |
|------|----|-----------|-------------|
| starting | listening | A port in range bound | Instance record written; log line |
| starting | failed | Range exhausted or listen error | Status reason kept for the info dialog; app continues |
| listening | stopped | Window unload (window-manager close or File → Exit) | Listener closed; record deleted |

### 04_02. Instance record lifecycle  {#SP_MCP_04_02}

| Event | Effect |
|-------|--------|
| Server listening | Record created |
| Clean exit | Record deleted by its owner |
| Crash | Record stays; readers ([SP_MCB_03_01](./mcp-bridge.sp.md#SP_MCB_03_01)) delete records whose `pid` is not alive |
| Start-up of any instance | The starting instance also deletes dead-pid records it finds |

## 05. Verification Criteria  {#SP_MCP_05}

### 05_01. Functional Expectations  {#SP_MCP_05_01}

| Contract | Scenario | Input | Expected outcome |
|----------|----------|-------|------------------|
| Endpoint | Claude Code connects | `claude mcp add --transport http circuitjs http://127.0.0.1:7311/mcp` | `/mcp` lists the server as connected with 14 tools |
| Endpoint | MCP Inspector | Inspector at localhost origin | Handshake, `tools/list`, `resources/list` succeed |
| circuit_types | inapplicable combination | `{type:"Resistor", models:"diode"}`, `{model:"x"}`, `{models:"all", model:"x"}` | JSON-RPC -32602 naming the argument |
| Origin rule | Foreign web page | `Origin: http://example.com` | 403 |
| Origin rule | No origin | curl POST | 200 |
| circuit_edit | domain error | unknown property | result `isError: true`, `structuredContent.issues[0].code = unknown_property` |
| circuit_edit | schema error | `edits` not an array | JSON-RPC -32602 |
| circuit_render | png | — | image content part present; text part carries `"<image>"` |
| Resources | catalogue | read `circuitjs://catalogue/Resistor` | TypeInfo JSON |
| Start-up | port busy | 7311 taken | server on 7312; record says 7312 |
| Info dialog | listening | open "MCP Server…" | URLs and command line shown |
| Info dialog | disable | untick "Enabled", Save, restart | status `disabled`; no port bound; no instance record |
| Endpoint | GET | `GET /mcp` | 405 |
| Endpoint | unsupported revision header | `MCP-Protocol-Version: 1999-01-01` | 400 with JSON-RPC error body |
| Endpoint | session header | request with an arbitrary `Mcp-Session-Id` | processed normally |
| Resources | unknown URI | read `circuitjs://nope` | JSON-RPC -32002 |
| Resources | circuit round trip | read `circuitjs://documents/d1/circuit`, pass it to `circuit_import` on d2 | d2 equals d1 |
| Tool | file rule | `circuit_file save` to `/etc/x.conf` | `isError`, issue `file_not_allowed` |
| Endpoint | notification | POST a JSON-RPC notification | 202, empty body |
| Resources | templates | `resources/templates/list` | the three templates of [§02_03](#SP_MCP_02_03) |
| Resources | examples | read `circuitjs://examples`, then one listed path | index JSON; the example text |
| Info dialog | counter | 3 tool calls, open the dialog | count shows 3 |
| Runtime settings | hidden window | minimise the window, `circuit_run` with `span` needing ~2 s wall time | `wallMs` within 25 % of the same run with the window visible |
| Sizing | oversized read | `circuit_get` with `detail: full`, `limit: 500` on the largest example | text ≤ 60 000 chars; note names the reduced arguments; `nextOffset` present |

### 05_02. Invariant Checks  {#SP_MCP_05_02}

| Invariant | Verification method |
|-----------|-------------------|
| Tools add no circuit logic | Every tool's `structuredContent` equals the OperationResult of the effective Agent API call (the requested arguments, or the reduced ones of [§03_04](#SP_MCP_03_04)), except the PNG content of `circuit_render` ([§01_04](#SP_MCP_01_04)) |
| `isError` ⇔ `ok = false` | Checked over every §05_01 case |
| Text part ≤ 60 000 chars | Large-circuit `circuit_get` and `circuit_connectivity` on the largest bundled example; `circuit_run` with 16 probes at Σ `maxPoints` = 2000 |
| One record per live instance | Two windows → two records with distinct ports; close one → one record |

### 05_03. Integration Scenarios  {#SP_MCP_05_03}

| Scenario | Preconditions | Steps | Expected result |
|----------|--------------|-------|-----------------|
| Private-network agent | Listening address set to `0.0.0.0` in the info dialog and the app restarted; agent host on another machine of the LAN | Connect to the LAN URL from the info dialog; `circuit_types` | Tools usable; no token asked. With the default address the LAN URL is not offered and the port does not answer from another machine |
| Long run while reading | `circuit_run` with 5 s budget in flight | Call `circuit_get` | `circuit_get` answers before the run ends |
| Bridge forwarding | Bridge running ([SP_MCB](./mcp-bridge.sp.md)) | Call any tool via the bridge | Same result as direct |

### 05_04. Edge Cases and Boundaries  {#SP_MCP_05_04}

| Case | Input | Expected behavior |
|------|-------|-------------------|
| All ports busy | 20 ports taken | status `failed`; app usable; info dialog shows the reason |
| Browser build | Opened in a browser | status `disabled`; no listen attempt |
| Crash record | Stale record with dead pid | Deleted by the next starting instance |
| Huge result | `circuit_get` with `limit=500`, `detail=full` on a big circuit | Concise retry or truncation with `nextOffset` |

## 06. Reversibility  {#SP_MCP_06}

### 06_01. Rollback Strategy  {#SP_MCP_06_01}

| Aspect | Rollback approach |
|--------|-------------------|
| Data/state changes | Four preference keys; instance files under `~/.circuitjs1/instances/` (safe to delete) |
| Artifacts | The server script bundled into the package, the menu item and the info dialog |
| Dependent modules | [SP_MCB](./mcp-bridge.sp.md) and [SP_AGS](./agent-skill.sp.md) need the endpoint; the Agent API does not depend on the server |
| External contracts | Tool names, arguments and result shapes are the agent-facing contract, versioned by `toolsVersion` (initially `1.0`; `1.1` since the agent model definitions of [PL_AGA](./agent-api.plan.md) Phases 11–14 — `defineModel`, `circuit_types` `models`/`model`, AgentCircuit and `circuit_get` `models`): a breaking change bumps MAJOR, an addition bumps MINOR; the skill states the `toolsVersion` it supports |

Minimum safe state: `mcpServerEnabled = false` disables the endpoint without code changes. To set it, open Options → "MCP Server...", untick "Enabled", Save and restart the app: nothing listens and no instance record is written. Verified by `tests/mcp/e2e.mjs` scenario `settings`, row `disable` (checks `statusDisabled`, `noRecord`, `noPortBound`, `menuOff`).

## 07. Design Decisions  {#SP_MCP_DEC}

### DEC_01 — How many tools?  {#SP_MCP_DEC_01}

> **Status:** resolved
> **Date:** 2026-10-01

**Question:** Should the server expose one tool per Agent API contract (~25), or group related contracts behind an `action` argument (14)?

**Options considered:**
| Option | Consequence |
|--------|-------------|
| A — 14 grouped tools (Recommended) | A smaller tool list to choose from; `action` discriminators; annotations become the most conservative over actions |
| B — one tool per contract (~25) | Exact annotations per tool; a longer list that agents scan on every turn |

**Decision:** A — 14 grouped tools (confirmed by the developer).
**Rationale:** Tool-design guidance favours fewer, consolidated tools; the grouped families (documents, history, files) are each used together.
**Rejected because:** B — tool-list size grows without adding capability.

### DEC_02 — Single JSON responses or event streams?  {#SP_MCP_DEC_02}

> **Status:** resolved
> **Date:** 2026-10-01

**Question:** Should replies be single JSON responses, or should long calls stream progress?

**Options considered:**
| Option | Consequence |
|--------|-------------|
| A — single JSON responses | Simplest transport; a run answers when it ends (bounded by its 120 s budget cap, below the host's 5-minute idle timeout) |
| B — event-stream responses with progress notifications | Progress for long runs; more transport code on the old runtime |

**Decision:** A — single JSON responses.
**Rationale:** Runs are budget-capped below host timeouts; progress has no consumer in the agent loop.
**Rejected because:** B — transport complexity with no consumer.

### DEC_03 — How are file actions bounded without access control?  {#SP_MCP_DEC_03}

> **Status:** resolved
> **Date:** 2026-10-01

**Question:** With no authentication ([C_MCP_DEC_02](./mcp-server.concept.md#C_MCP_DEC_02)), any client that can reach the server (since 2026-10-02 only local programs by default; private-network clients once the listening address is opened) can call `circuit_file` with an arbitrary path. How are open/save bounded?

**Options considered:**
| Option | Consequence |
|--------|-------------|
| A — circuit files only | Only absolute `.txt`/`.json` paths; open ≤ 10 MB; save overwrites only empty or circuit files ([SP_AGA_03_09](./agent-api.sp.md#SP_AGA_03_09)); no change for agents |
| B — A, and file actions for loopback clients only | Remote agents work through import/export content only |
| C — no restriction | Any client can read or overwrite any user-writable file |

**Decision:** A — circuit files only.
**Rationale:** It removes the arbitrary-file read/overwrite exposure that review round 1 found, without adding the access control the developer rejected.
**Rejected because:** B — it limits remote agents more than the stated environment needs. C — arbitrary file overwrite is out of proportion to the feature.

## Changelog

| Date | Change |
|------|--------|
| 2026-10-01 | Initial version |
| 2026-10-01 | Review round 3: SVG size rule, resources exempt from the tool-result limit, required `doc` for activate/close |
| 2026-10-01 | Review round 1: named protocol revisions and header/session handling, `toolsVersion`, annotation corrections, resource shape, settings in the info dialog, start-up trigger, Chromium arguments, sizing rules, error reporting via the global handler, file-rule decision DEC_03, verification gaps |
| 2026-10-02 | PL_MCP Phase 2: example index `menu` field and listed-paths rule; resource-read error mapping; unknown and action-inapplicable arguments are -32602, range keywords advisory; text-part fallback for results that cannot be reduced |
| 2026-10-02 | PL_MCP Phase 1: instance-record URLs per host kind; 404/413/batch/parse errors and CORS preflight for local origins; start-up failure when the server script is missing; invalid preferences fall back with a warning; review: HTTP status per JSON-RPC error, `null` for unreadable ids, bounded echo of client text |
| 2026-10-02 | PL_MCP Phase 3: menu text with three dots, untranslated status wire names, Copy button and empty rows without a URL, port range not edited in the dialog |
| 2026-10-02 | Default `mcpServerHost` is `127.0.0.1` (C_MCP_DEC_02 amended by the developer); private-network agent row needs the LAN setting |
| 2026-10-02 | PL_MCP Phase 5 propagate: start-up order (script check first) and disable/failure reasons; EACCES as a busy port; loopback URLs for `localhost`/`127.x`/`::1`; HTTP 500/503 and backstop status; receive, agent and backstop timeouts; client responses 202, `notifications/cancelled` dropped; preference constraints and fallback, no URL layer; exact size hints; how to reach the minimum safe state and its e2e check |
| 2026-10-04 | Model definitions (SP_AGA_DEC_07): `circuit_types` `models`/`model` → listModels; `circuit_edit` `defineModel`; AgentCircuit `models` in `circuit_import` and `circuit_get` |
| 2026-10-04 | SP_AGA model review round 1: `circuit_types` argument combinations, `defineModel` create-only; implemented within PL_AGA Phase 11 (MCP server part) |
| 2026-10-04 | Model definitions review round 2: inapplicable `circuit_types` argument combinations are -32602 like other inapplicable arguments |
| 2026-10-04 | Phase 11 review: the document circuit resource carries `models` / `modelsTruncated` |
| 2026-10-04 | `toolsVersion` 1.1 (§06_01: an addition bumps MINOR): the agent model definitions of PL_AGA Phases 11–14; the skill compatibility line, agent-format header and the version checks of the tests follow (PL_AGA Phase 15) |
