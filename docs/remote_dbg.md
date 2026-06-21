# CircuitJS1 Remote Debug System

Remote debugging system for CircuitJS1 that enables remote control, diagnostics, and AI-assisted circuit manipulation over WebSocket connections.

## Quick Start

### 1. Start Debug Server

```bash
cd server
npm install    # First time only
npm start
```

Server runs on **http://localhost:3030**

### 2. Connect CircuitJS1

Open CircuitJS1 with the remote-debug URL parameter:

```
http://127.0.0.1:8888/circuitjs.html?remote-debug=my-session-id
```

Or use menu: **Options → Remote Debug**

---

## Using with NW.js (Desktop)

The desktop app is an NW.js wrapper around the compiled site in `target/site`.

### Option A (recommended): launch NW.js normally

```bash
npm run buildgwt
npm start
```

Then either:
- Use **Options → Remote Debug** to open the viewer URL (it generates a channel), and reload the app with `?remote-debug=<channel>` as instructed.
- Or manually open the app entry with URL params (see Option B).

### Option B: force Remote Debug params on startup

If you want NW.js to start already connected to a specific channel/server (useful for E2E tests), create a small wrapper app directory with its own `package.json`:

```json
{
    "name": "circuitjs1-nw-remote-debug",
    "version": "0.0.0",
    "main": "http://127.0.0.1:8000/circuitjs.html?remote-debug=<CHANNEL>&remote-debug-server=http://127.0.0.1:3030"
}
```

Launch it using the bundled NW binary:

```bash
./node_modules/nw/nwjs/nw /absolute/path/to/that/wrapper
```

### Troubleshooting: "Cannot open .../nwjs/package.nw"

On Linux, this NW.js build may log an attempt to open `.../nwjs/package.nw` even when an app folder is provided. By itself it can be benign noise.

Treat it as a real problem only if you also see errors about your app path (e.g. it cannot open your `target/site` or wrapper), or the window never appears / the agent never connects.

Fixes:
- Prefer `npm start` (uses the project’s known-good launch path).
- Or run the binary directly and pass an explicit folder containing `package.json`, e.g. `./target/site` or your wrapper directory.

### 3. Open Web Viewer

Navigate to:

```
http://localhost:3030/?channel=my-session-id
```

The channel ID must match between CircuitJS1 and the viewer.

---

## Features

| Feature | Status | Description |
|---------|--------|-------------|
| Remote Console | ✅ | Execute JavaScript via CircuitJS1 API |
| Console Interception | ✅ | Auto-capture console.log/warn/error to dashboard |
| Screenshots | ✅ | Capture circuit canvas (PNG/SVG) |
| Simulation Control | ✅ | Start/Stop/Step/Reset |
| Telemetry Streaming | ✅ | Real-time element voltage/current/power |
| Element Editing | ✅ | Modify element properties remotely |
| AI Chat | 🔜 | AI-assisted circuit analysis |

---

### Console Interception

When the agent script is loaded, it automatically intercepts:
- `console.log()` → INFO log
- `console.warn()` → WARN log  
- `console.error()` → ERROR log
- `console.info()` → INFO log
- Uncaught exceptions
- Unhandled promise rejections

All intercepted logs appear in the dashboard Console tab with `[agent]` prefix.

### Document Info

The agent sends a `document_info` event after connecting. The server also caches the latest `document_info` per channel and replays it to viewers that connect later (so the dashboard still shows document metadata even if it was opened after the agent).

## JavaScript API Examples

### Get Simulation Info

```javascript
CircuitJS1.getSimInfo()
// { time: 0.001234, running: true, elementCount: 15, ... }
```

### Control Simulation

```javascript
CircuitJS1.setSimRunning(true);   // Start
CircuitJS1.setSimRunning(false);  // Stop
CircuitJS1.stepSimulation();       // Single step
CircuitJS1.resetSimulation();      // Reset to t=0
```

### Access Elements

```javascript
// Get all element IDs
CircuitJS1.getElementIds()

// Get element info
CircuitJS1.getElementInfo("R1")

// Modify element property
CircuitJS1.setElementProperty("R1", "resistance", 2200)
```

### Export/Import Circuits

```javascript
// Export to JSON
const json = CircuitJS1.exportAsJson();

// Import from JSON
CircuitJS1.importFromJson(jsonString);
```

---

## Message Protocol

Communication between viewer and agent uses JSON messages over Socket.IO.

### Core Messages

| Message | Direction | Description |
|---------|-----------|-------------|
| `execute` | Viewer→Agent | Execute JS command |
| `execute_result` | Agent→Viewer | Command result |
| `screenshot` | Viewer→Agent | Capture canvas |
| `sim_control` | Viewer→Agent | Simulation control |
| `telemetry_subscribe` | Viewer→Agent | Start streaming |
| `element_update` | Viewer→Agent | Modify element |
| `document_log` | Agent→Viewer | Per-circuit log |

### Example: Execute Command

```json
// Request
{ "type": "execute", "command": "CircuitJS1.getTime()" }

// Response
{ "type": "execute_result", "success": true, "result": "0.00123" }
```

---

## Configuration

### URL Parameters

| Parameter | Description | Example |
|-----------|-------------|---------|
| `remote-debug` | Channel ID for debug session | `?remote-debug=abc123` |
| `remote-debug-server` | Custom server URL | `?remote-debug-server=http://host:3030` |

### Environment Variables (Server)

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | 3030 | Server port |
| `CORS_ORIGIN` | `*` | Allowed origins |

---

## File Structure

```
server/
├── remote-debug-server.js    # Main server
├── public/
│   └── index.html            # Web viewer dashboard
└── package.json

src/main/webapp/scripts/
└── remote-debug-agent.js     # Client agent

war/
├── circuitjs.html            # Modified for agent loading
└── scripts/
    └── remote-debug-agent.js # Deployed agent
```

---

## Security Notes

> ⚠️ Remote debugging exposes full JavaScript execution access **by design** — this is intended diagnostic/automation functionality under a **localhost-only** trust model. See the settled [Trust Model & Design Decision](./remote_dbg_concept.md#trust-model--design-decision-settled-2026-06-21).

- Localhost-only, operator-initiated: the agent is inert unless a session is opted in (URL param or Options → Remote Debug)
- Channel ID acts as basic authentication (long random UUID)
- Run the debug server on localhost (`127.0.0.1`)
- The full-`eval` execution is intentional for trusted viewers; the optional command-allowlist, loopback enforcement, and auth layer are required only if ever exposed beyond localhost

---

## Changelog

### v0.2.0 (in progress)
- Tabbed log interface (console + per-document)
- Menu integration (Options → Remote Debug)
- AI Chat tab (planned)

### v0.1.0
- Initial implementation
- Socket.IO server with channel sessions
- Web viewer dashboard
- Agent script with full API access
