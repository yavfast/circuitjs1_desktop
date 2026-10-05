# Claude Code: connect CircuitJS1 and install this skill

## 1. Connect the tools

The CircuitJS1 Desktop app is the MCP server: every running instance listens on `http://127.0.0.1:7311/mcp`, and a second instance takes the next free port up to 7330. Options → "MCP Server..." in the app shows the URL and a ready-made command.

**Security.** The in-app server has no token or password. By default it listens on `127.0.0.1` only: any program on this computer can drive the app, other devices cannot connect. When the user sets the listening address to `0.0.0.0` in that dialog (Save, then restart the app), any device on the local network that reaches the port can drive the app too; do that on trusted networks only. Read the security note in the CircuitJS1 repository's root README, section "MCP server (AI agents)" (`README.md#mcp-server-ai-agents`).

**Direct HTTP (recommended).** Start the app, then:

```bash
claude mcp add --transport http circuitjs http://127.0.0.1:7311/mcp
```

Add `--scope user` to make the server available in every project, not only the current directory. The app must be running when Claude Code connects.

**Bridge alternative.** The `circuitjs-mcp` bridge (in `mcp/bridge/` of the CircuitJS1 repository, Node.js 20 or later) is a stdio server that finds a running instance, or starts the app itself with `--launch`:

Install the bridge once:

```bash
cd mcp/bridge && npm install && cd ../.. && npm install -g --install-links ./mcp/bridge
```

Then add it in **either** form. Without the app path (the bridge uses a running app):

```bash
claude mcp add circuitjs -- circuitjs-mcp --launch
```

**Or** with the app path, which `--launch` needs to start the app itself:

```bash
claude mcp add circuitjs -e CIRCUITJS_APP=/absolute/path/to/CircuitSimulator -- circuitjs-mcp --launch
```

Through the bridge you also get `bridge_instances`, `bridge_select` and `bridge_launch`.

**Check.** `claude mcp list` shows `circuitjs` as connected; inside a session, `/mcp` lists its 15 `circuit_*` tools (18 with the bridge). The server name `circuitjs` is the prefix of the tool names (`mcp__circuitjs__circuit_types`).

## 2. Install the skill

Copy the whole `circuitjs-circuits` directory (with `SKILL.md`, `reference/` and `hosts/`) into a skills location:
- for you, in every project: `~/.claude/skills/circuitjs-circuits/`;
- for one project, shared through its repository: `<project>/.claude/skills/circuitjs-circuits/`.

```bash
mkdir -p ~/.claude/skills
cp -r mcp/skill/circuitjs-circuits ~/.claude/skills/
```

Start a new Claude Code session; the skill triggers on requests to design, simulate, tune or fix a circuit in CircuitJS1. The skill works with toolsVersion 1.3 (see `SKILL.md`).

## 3. Uninstall

The skill holds no data. Removing it leaves the tools fully usable, only less guided.
1. Delete the skill directory: `rm -rf ~/.claude/skills/circuitjs-circuits` (or the project copy under `.claude/skills/`).
2. To remove the tools as well: `claude mcp remove circuitjs` (add `--scope user` if you added it there).
3. If you installed the bridge only for this: `npm uninstall -g circuitjs-mcp`.

The skill name `circuitjs-circuits` is how hosts trigger it; a renamed copy is a different skill.
