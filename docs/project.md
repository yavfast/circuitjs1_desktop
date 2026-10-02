# CircuitJS1 Desktop Project Documentation

## Overview

**CircuitJS1 Desktop** is a desktop adaptation of the popular CircuitJS1 electronic circuit simulator. It allows users to design, simulate, and analyze electronic circuits in an interactive graphical environment. The project is based on the open-source CircuitJS1 web simulator, providing additional desktop-specific features and packaging for standalone use.

### Key Features

- **Interactive Circuit Design:** Drag-and-drop interface for creating and editing circuits.
- **Real-Time Simulation:** Simulate circuit behavior with real-time updates.
- **Cross-Platform Compatibility:** Runs on Windows, macOS, and Linux.
- **Extensive Component Library:** Includes resistors, capacitors, transistors, diodes, and more.
- **Customizable:** Supports custom components and localization.
- **Offline Mode:** Fully functional without internet access.
- **AI agent access:** Every desktop instance is an MCP server that agents (e.g. Claude Code) use to build, simulate and debug circuits (see [MCP server](#mcp-server-ai-agents)).

## Technology Stack

- **Java** (core simulator + GWT sources compiled to JavaScript)
- **JavaScript/HTML/CSS** (web interface, GWT-based frontend)
- **Maven** (build automation)
- **Node.js** (scripts for development and packaging)
- **Inno Setup** (Windows installer creation)

## Project Structure

- `src/main/java/` — Java source code for the simulation engine and desktop application.
- `war/` — Web application resources for deployment.
- `mcp/` — In-app MCP server sources (`mcp/server/src/`), its bundler and unit tests.
- `docs/` — Project documentation (this file, guides, etc.).
- `scripts/` — Development and build scripts (Node.js, shell scripts).
- `icons/` — Application icons for various platforms.
- `templates/` — Localization templates for different languages.
- `tests/` — Test circuit files and harnesses (`tests/live/` headless Chromium, `tests/mcp/` NW.js end-to-end for the MCP server).
- `target/` — Maven/GWT build output (compiled classes, GWT output, `target/site/`).

## Getting Started

### Prerequisites

- **Java JDK 17+**
- **Node.js** (for development scripts)
- **Maven** (for building Java components)
- (Optional) **Inno Setup** (for building Windows installers)

### Build and Run

1. **Install Node dependencies:**
   ```sh
   npm install
   ```

2. **Build and run (recommended workflow):**
   - Build only the GWT web app:
     ```sh
     npm run buildgwt
     ```
     Output: `./target/site/`
   - Run it in NW.js SDK:
     ```sh
     npm start
     ```

   Notes (NW.js):
   - `npm start` runs `node scripts/dev_n_build.js --rungwt`, which launches the NW.js binary returned by `require('nw').findpath()` against `./target/site`.
   - If you need to launch NW.js manually (useful for debugging launch issues), the binary is typically at:
     ```sh
     ./node_modules/nw/nwjs/nw ./target/site
     ```
   - If your GPU/driver stack is problematic, try adding Chromium flags:
     ```sh
     ./node_modules/nw/nwjs/nw ./target/site --disable-gpu
     ```

3. **Full desktop build (packaging):**
   - Build a desktop release (currently packages Linux x64 by default):
     ```sh
     npm run build
     ```
   - Full rebuild (complete cleanup, then package Linux x64):
     ```sh
     npm run full
     ```
   Output: `./out/`

4. **GWT DevMode (for UI/Java iteration):**
   ```sh
   npm run devmode
   ```
   DevMode runs directly from `war/` and is separate from `target/site/`.

### Parameters

- Runtime/debug options are controlled via scripts in `scripts/` and build flags; see the root README for the supported `npm` commands.

## Debugging Methods

- **Debug scripts:** Use `scripts/debug_runner.js` or `scripts/debug-logger.js` for enhanced logging and debugging.
- **Shell scripts:** `scripts/run_dev_web.sh` and `scripts/run_dev_app.sh` are available for quick local workflows.
- **Logs:** Check `debug.log` for runtime logs and errors.
- **Maven:** Use `mvnDebug` for remote debugging with IDEs.

Tip (terminal): If you want the NW.js app launcher to return immediately, run it in detached mode:
```sh
./scripts/run_dev_app.sh --detach
```

## Additional Information

### MCP server (AI agents)

Every running desktop instance starts an [MCP](https://modelcontextprotocol.io) (Model Context Protocol) server inside the app: a Streamable HTTP endpoint at `http://<host>:<port>/mcp` with 14 `circuit_*` tools and a few resources, projected onto the Agent API (`window.CircuitJS1Agent`, [JS_API.md](./JS_API.md#circuitjs1agent-agent-api), [agent-api.sp.md](./agent-api.sp.md)). Specification: [mcp-server.sp.md](./mcp-server.sp.md); concept and decisions: [mcp-server.concept.md](./mcp-server.concept.md).

- **Connect Claude Code:** `claude mcp add --transport http circuitjs http://127.0.0.1:7311/mcp` (use the URL the app shows).
- **Where the URL is shown:** Options → "MCP Server..." — status, instance ID, URLs (local and LAN), tool calls in this run, and the connect command with a Copy button. The start-up log line also names the URL.
- **Ports:** the first instance takes 7311; each further instance ("New window" is a separate process) takes the next free port up to 7330 (base port and range are preferences).
- **Instance records:** `~/.circuitjs1/instances/<pid>-<startedAtMs>.json` (mode 0600) lists each running instance and its URLs for discovery; removed when the window closes, stale records are safe to delete.
- **Security:** by decision [C_MCP_DEC_02](./mcp-server.concept.md#C_MCP_DEC_02) the server is always on, listens on all interfaces (`0.0.0.0`) and has **no token or other access control**. Any local program and any device on the local network that can reach the port can read and change open circuits, run simulations, and open or save `.txt`/`.json` circuit files with the user's permissions. The remaining bounds are the Origin rule — a request whose browser `Origin` header is not `localhost`, `127.0.0.1` or `[::1]` gets HTTP 403, so foreign web pages cannot call it — the restriction of file tools to circuit files, and the absence of any code-execution tool.
- **Turning it off (rollback switch):** Options → "MCP Server..." → untick Enabled → Save → restart the app. With `mcpServerEnabled = false` the app opens no port and writes no instance record; the menu item reads "MCP Server... (off)". Setting the listening address to `127.0.0.1` limits it to the local machine. Settings apply at the next start; the preference keys are `mcpServerEnabled`, `mcpServerPort`, `mcpServerPortRange`, `mcpServerHost` ([user-preferences.sp.md](./user-preferences.sp.md)).
- **Build and tests:** `npm run build:mcp` bundles `mcp/server/src/` into `war/scripts/mcp-server.js` (generated, git-ignored; `npm run buildgwt` and devmode run it first). `npm run test:mcp-unit` runs the server's unit checks; `npm run test:mcp` drives a real NW.js instance end to end ([tests/mcp/README.md](../tests/mcp/README.md)).

### Localization

The `templates/` directory contains language templates for translation. To add a new language, create a new template file and update the `update_languages.sh` script.

### Installer Creation

Use the `Inno Setup/` scripts to build Windows installers. Customize the `.iss` files for specific configurations.

### Web Version

The `site/` and `war/` directories contain resources for the web version of CircuitJS1. These can be deployed to a web server for online use.

### Licensing

See `LICENSE` for licensing details. This project is distributed under the GNU General Public License (GPL), version 2 or later.

### Contributing

Contributions are welcome. Please follow the existing code style and submit pull requests with clear descriptions. Refer to the `README.md` for guidelines.

### References

- [CircuitJS1 Original Project](https://github.com/sharpie7/circuitjs1)
- [GWT (Google Web Toolkit)](http://www.gwtproject.org/)
- [Inno Setup](http://www.jrsoftware.org/isinfo.php)

---

*For further details, refer to the README.md and comments in the source code.*
