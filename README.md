# CircuitJS1 Desktop Mod

**Circuit Simulator is renamed CircuitJS1 Desktop Mod**

![](screenshot.png)

The source code for offline version of the **Circuit Simulator** with minor modifications based on [modified NW.js](https://github.com/SEVA77/nw.js_mod). It was originally written by Paul Falstad as a Java Applet. It was adapted by Iain Sharp to run in the browser using GWT. The program was modified and compiled to offline version for Windows (x32, x64), Linux (x32, x64) and MacOS (x64, arm64) by Usevalad Khatkevich.

This program is distributed by me as a program for education. It is not recommended to use the program for modeling real circuits, since many components in the program are idealized.

The program supports the following languages: English, Russian, Danish, German, Polish, Spanish, French, Italian, Portuguese, Czech, Norwegian, Chinese, Japanese.

For a web version of the application see:

Paul's Page: https://www.falstad.com/circuit/ \
Source code: https://github.com/pfalstad/circuitjs1

Iain's Page: https://lushprojects.com/circuitjs/ \
Source code: https://github.com/sharpie7/circuitjs1

## Downloads:

You can download this program for Windows (x32, x64), Linux (x32, x64) and Mac OS X (x64, arm64):
- [Latest release](https://github.com/SEVA77/circuitjs1/releases/latest)
- [All Releases](https://github.com/SEVA77/circuitjs1/releases)

> If you have problems with this application, you can try to use [this offline application of the main developer](http://www.falstad.com/circuit/offline/) based on Electron.

## MCP server (AI agents)

Every running desktop instance is also an [MCP](https://modelcontextprotocol.io) server, so AI agents such as Claude Code can build, edit, simulate, measure and debug circuits in the open documents through 14 `circuit_*` tools. The tools map onto the Agent API (`window.CircuitJS1Agent`, see [docs/JS_API.md](docs/JS_API.md) and [docs/agent-api.sp.md](docs/agent-api.sp.md)); the server itself is specified in [docs/mcp-server.sp.md](docs/mcp-server.sp.md).

**Connect Claude Code:**
```
claude mcp add --transport http circuitjs http://127.0.0.1:7311/mcp
```

Hosts that start only stdio servers (Claude Desktop) and shell scripts use the bridge and command-line client `circuitjs-mcp` in [mcp/bridge](mcp/bridge/README.md).

The URL of each instance is shown in Options → "MCP Server...", together with the status, the instance ID and this command with a Copy button (when the server is opened to the network, the LAN URLs as well). The first instance listens on port 7311; further instances ("New window" starts a separate process) take the next free port up to 7330. Each running instance writes a record with its URLs to `~/.circuitjs1/instances/<pid>-<startedAtMs>.json` (user-only file, removed when the window closes; stale records are safe to delete) so that tools can find it. The browser (web) build has no server.

**Security.** The server is **on by default and has no token or password**. By default it listens on `127.0.0.1` only, so it is reachable from this computer only: any program running on this computer under any user can drive the app — read and change open circuits, run simulations, and open or save `.txt`/`.json` circuit files under your user account (a save overwrites an existing file only when it is empty or is a circuit). No other device can connect. The only protections are: requests from web pages of other sites are rejected (a browser `Origin` header other than `localhost`, `127.0.0.1` or `[::1]` gets HTTP 403), the file tools are limited to circuit files, and no tool executes code. This was a deliberate trade-off for zero-setup agent access ([C_MCP_DEC_02](docs/mcp-server.concept.md#C_MCP_DEC_02)).

**Opening it to the network** (an agent on another machine): open Options → "MCP Server...", set the listening address to `0.0.0.0` (all interfaces) or to one LAN address of this computer, press **Save** and **restart the app**; the dialog then lists the LAN URL to give the agent (with one LAN address, that address replaces `127.0.0.1` too). Then **any device on your local network that can reach the port can drive the app** in the same way, still without a token. Do this on trusted networks only, and set the address back to `127.0.0.1` when you no longer need it.

A listening address saved in that dialog is kept across updates: a profile that saved `0.0.0.0` before the default became `127.0.0.1` (2026-10-02) stays reachable from the network — set it back to `127.0.0.1` there if that is not wanted.

**Turning it off:** untick **Enabled** in the same dialog, press **Save** and **restart the app** — the app then opens no port and writes no instance record (the Options menu item reads "MCP Server... (off)").

Settings apply at the next start of the app. They are stored as the preferences `mcpServerEnabled`, `mcpServerPort`, `mcpServerHost` and `mcpServerPortRange` ([docs/user-preferences.sp.md](docs/user-preferences.sp.md)).

## Building the program

The tools you will need to build the project are:

* JDK 17+
* Maven 3+
* Node.js with npm

Install the dependencies in the local `node_modules` folder:
```
npm install
```

For desktop packaging (currently builds a Linux x64 release by default):
```
npm run build
```

For a full rebuild with complete cleanup (then package a Linux x64 release):
```
npm run full
```

*Output folder:* `./out/`

Also you can build only the GWT application:
```
npm run buildgwt
```

and run it in NW.js SDK version:
```
npm start
```

`npm start` launches NW.js in detached mode (the terminal command returns immediately).

*Output folder:* `./target/site/`

## Development

Various build options, checker and devmod are available in the development menu:

```
npm run dev
```

***Separate commands:***

Check the build steps:
```
npm run check
```

Run devmode:
```
npm run devmode
```

Devmode works directly in the `war` directory separate from the `target/site` directory.

### Standard circuits location

The built-in/standard example circuits (the files served under `/circuits/...` in the web UI/devmode) are stored in:

`src/main/java/com/lushprojects/circuitjs1/public/circuits`

These circuits are a convenient baseline corpus for testing (manual checks and automated import/export roundtrips).

Note: `npm run devmode` starts `mvn gwt:devmode` and also tries to launch NW.js for the dev UI; it is a long-running process.

## License

This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation; either version 2 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.

© Usevalad Khatkevich 2025

## Credits

* [Paul Falstad](https://github.com/pfalstad) - Creator
* [Iain Sharp](https://github.com/sharpie7) - JavaScript conversion, so there are more opportunities for the development of this application.
* [Brian Gordon](https://github.com/briangordon) - Mavenized version of circuitjs1
