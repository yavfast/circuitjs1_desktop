# circuitjs-mcp

`circuitjs-mcp` connects AI agent hosts and shell scripts to a running CircuitJS1 Desktop instance. It has two modes:

- **Stdio MCP server.** Run without a subcommand, it is an MCP server over stdio for hosts that only start servers themselves, such as Claude Desktop. It forwards every `circuit_*` tool and resource to an app instance unchanged, and adds three tools of its own: `bridge_instances`, `bridge_select` and `bridge_launch`.
- **Command-line client.** Run with a subcommand, it calls one tool or reads one resource. It prints JSON and returns an exit code, for scripts, tests and eval checkers.

The app itself is the MCP server: every running instance listens on `http://127.0.0.1:7311/mcp` (the next instance on the next free port up to 7330). Hosts that speak Streamable HTTP, such as Claude Code, can connect to it directly without this bridge (see the root [README](../../README.md#mcp-server-ai-agents)). The bridge defines no circuit tools; it finds an instance through the instance registry and forwards to it.

Specification: [docs/mcp-bridge.sp.md](../../docs/mcp-bridge.sp.md). Concept: [docs/mcp-bridge.concept.md](../../docs/mcp-bridge.concept.md).

## Install

You need Node.js 20 or later on the machine where the agent host runs. The bridge is not published to the npm registry; install it from this repository:

```bash
cd mcp/bridge && npm install && cd ../..        # the bridge's own dependencies
npm install -g --install-links ./mcp/bridge      # a copy: works after the repository moves
# or: npm install -g ./mcp/bridge                # a link to this folder (follows your edits)
circuitjs-mcp --version
```

Without a global install, run the program in place:

```bash
node /path/to/circuitjs1_desktop/mcp/bridge/bin/circuitjs-mcp.js --version
npx --prefix /path/to/circuitjs1_desktop/mcp/bridge circuitjs-mcp --version
```

## Options

Every option can be given as a flag or as an environment variable. When both are set, the flag wins. A target selector given as a flag (`--url` or `--instance`) hides both selector variables.

| Flag | Environment | Default | Meaning |
|------|-------------|---------|---------|
| `--url <url>` | `CIRCUITJS_MCP_URL` | — | Explicit endpoint, for example an instance on another machine (`http://<host>:7311/mcp`). The registry is not used, and there is no fallback when the URL does not answer |
| `--instance <id>` | `CIRCUITJS_MCP_INSTANCE` | — | Instance ID from the registry (see `instances`) |
| `--launch` | `CIRCUITJS_MCP_LAUNCH=1` (also `true`, `yes`) | off | Start the app when no instance is running |
| `--app <path>` | `CIRCUITJS_APP` | — | Packaged app executable, needed to launch: the `CircuitSimulator` binary in the unpacked package directory (`out/<platform>/…`) |
| `--registry <dir>` | `CIRCUITJS_MCP_REGISTRY` | `~/.circuitjs1/instances` | Instance registry directory |
| `--timeout <ms>` | `CIRCUITJS_MCP_TIMEOUT` | 130000 | Per-request forward timeout; above the 120 s cap of a `circuit_run` budget |
| `--launch-timeout <ms>` | — | 30000 | Wait for a launched instance's registry record |
| — | `CIRCUITJS_MCP_DEBUG=1` | off | Add the stack trace to an unexpected error |

**Which instance.** In this order: `--url`; `--instance`; the most recently started live instance in the registry; with `--launch`, a newly started app; otherwise none. Records of processes that are gone are deleted on the way. The bridge and the app must run in the same process namespace (not one of them inside a Flatpak/snap sandbox or a container), because liveness is checked by pid.

## Host configuration

**Claude Code** connects to the app directly (`claude mcp add --transport http circuitjs http://127.0.0.1:7311/mcp`). To use the bridge instead, for example to get `bridge_launch`:

```bash
claude mcp add circuitjs -- circuitjs-mcp
claude mcp add circuitjs -e CIRCUITJS_APP=/opt/circuitjs/CircuitSimulator -- circuitjs-mcp --launch
```

**Claude Desktop** starts only stdio servers. Edit `claude_desktop_config.json` (macOS `~/Library/Application Support/Claude/`, Windows `%APPDATA%\Claude\`; Settings → Developer → Edit Config) and restart Claude Desktop:

```json
{
  "mcpServers": {
    "circuitjs": {
      "command": "circuitjs-mcp",
      "args": ["--launch"],
      "env": { "CIRCUITJS_APP": "/path/to/CircuitJS1 Desktop Mod/CircuitSimulator" }
    }
  }
}
```

Claude Desktop does not start servers from your login shell, so it may not see your `PATH` (nvm, Homebrew). If `circuitjs-mcp` is not found, give absolute paths: `"command": "/usr/local/bin/node", "args": ["/path/to/circuitjs1_desktop/mcp/bridge/bin/circuitjs-mcp.js", "--launch"]`. On Windows always use this form (`"command": "C:\\Program Files\\nodejs\\node.exe"`, the script path in `args`): the global command is a `circuitjs-mcp.cmd` shim, which a host does not start without a shell. Leave out `args` and `env` when you start the app yourself.

**Any other stdio host:** command `circuitjs-mcp` (or `node <repo>/mcp/bridge/bin/circuitjs-mcp.js`), no arguments needed, options as above.

In stdio mode the host gets the app's tools followed by the bridge tools, and the app's instructions followed by one sentence about the bridge tools. With no instance running, only the bridge tools are listed, and an app tool returns `No CircuitJS1 instance. Start the app or call bridge_launch.`. When the target changes (`bridge_select`, `bridge_launch`, the app closed), the bridge sends `tools/list_changed` and `resources/list_changed`. A closed app gives `Instance gone: <url>` once; the next call finds the next instance.

| Bridge tool | Arguments | Result |
|-------------|-----------|--------|
| `bridge_instances` | — | `{instances: [{instanceId, url, title, appVersion, toolsVersion, startedAt, selected}], target?}` |
| `bridge_select` | exactly one of `instanceId`, `url` | `{target}`; forward to that instance from now on |
| `bridge_launch` | `file?` (absolute `.txt`/`.json` path) | `{target, opened?: {doc}}`; start the app (or use the running instance) and open the file in a new, visible document |

## Command line

```
circuitjs-mcp instances [options]
circuitjs-mcp tools [options]
circuitjs-mcp call <tool> [<json-args> | -] [options]     # "-" reads the arguments from stdin
circuitjs-mcp read <uri> [options]
circuitjs-mcp launch [<file>] [options]
circuitjs-mcp --help | --version
```

| Subcommand | Prints (one JSON document on stdout) |
|------------|--------------------------------------|
| `instances` | `[{instanceId, url, title, appVersion, toolsVersion, startedAt, selected}]`; `selected` marks the instance the options pick |
| `tools` | `[{name, title, annotations}]` of the instance's tools |
| `call` | The tool result's `structuredContent` (the OperationResult), or `{content: [...]}` when it has none. Only `call` honours `--launch` |
| `read` | The resource's `contents` array |
| `launch` | `{target, opened?: {doc}}`; a relative file is resolved against the current directory |

| Exit code | Meaning |
|-----------|---------|
| 0 | Success |
| 1 | The tool returned an error result (`isError`), or `launch` could not open the file: the output is still printed (for example the issues of a rejected edit) |
| 2 | Usage error: unknown subcommand or option, bad or non-object JSON arguments, or a request the app rejects as invalid (JSON-RPC -32600, -32601, -32602, unknown resource -32002) |
| 3 | No reachable instance, connection error, timeout, or another failure of the app |

On exit 2 or 3, stdout is empty and stderr carries one line `circuitjs-mcp: <reason>`.

```bash
circuitjs-mcp call circuit_types '{"type":"Resistor"}' | jq .data.pins
circuitjs-mcp call circuit_import - < my-circuit.json            # {"circuit": ...} on stdin
circuitjs-mcp call circuit_run '{"span":"5 ms","reset":true,"probes":[{"net":"out"}]}'
circuitjs-mcp read circuitjs://catalogue | jq -r '.[0].text' | jq '.types | length'
circuitjs-mcp launch ./rc.txt --app /opt/circuitjs/CircuitSimulator
```

## Security

The bridge adds no access control and needs none of its own: it reaches only what the app's MCP server already serves to every program on the machine (and, when the app's listening address is opened to the network, to the local network), which has no token by design. Read the security note in the root [README](../../README.md#mcp-server-ai-agents) before you use the app on an untrusted network. The bridge reads and cleans the instance registry and starts the configured app executable; it never starts anything else.

## Uninstall

[SP_MCB_06_01](../../docs/mcp-bridge.sp.md#SP_MCB_06_01): the bridge owns no data.
1. Remove the host entries you added: `claude mcp remove circuitjs`, or delete the `circuitjs` entry from `claude_desktop_config.json`.
2. `npm uninstall -g circuitjs-mcp`.
3. Optionally delete the bridge's own dependencies: `rm -rf mcp/bridge/node_modules`.

Instance records in `~/.circuitjs1/instances` belong to the app, which deletes them itself.

## Tests

```bash
cd mcp/bridge && npm test           # unit tests against a fake app endpoint (no build needed)
npm run test:mcp -- bridge          # from the repository root: the bridge rows against real NW.js instances
```

The end-to-end rows are part of `tests/mcp/e2e.mjs` ([tests/mcp/README.md](../../tests/mcp/README.md)): `bridge` (default group), `bridge_long` (slow), `bridge_clients` (Claude Code over stdio).

## Manual rows

The Claude Desktop row needs a desktop with Claude Desktop installed. Steps:
1. Install the bridge (above) and add the Claude Desktop entry with `--launch` and `CIRCUITJS_APP`; restart Claude Desktop.
2. In a new chat, check that the `circuitjs` connector lists the app's 14 `circuit_*` tools and the 3 `bridge_*` tools (the app starts when Claude Desktop connects, if it was not running).
3. Ask for `circuit_types` with type `Resistor`, then for `bridge_instances`.
4. Pass: both answer; with the app closed beforehand and `--launch` left out, only the 3 bridge tools are listed and a circuit tool returns "No CircuitJS1 instance".

| Row | Date | Platform / host version | Result | By |
|-----|------|-------------------------|--------|----|
| SP_MCB_05_01 Stdio mode: Claude Desktop config with `circuitjs-mcp` | — | — | not yet observed | — |
