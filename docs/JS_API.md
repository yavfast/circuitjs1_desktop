# CircuitJS1 JavaScript API

This document describes the JavaScript API available for programmatic control of CircuitJS1 from web browsers or automation tools: the scripting global `CircuitJS1` and the Agent API global [`CircuitJS1Agent`](#circuitjs1agent-agent-api).

## Running the Application

### Development Mode (GWT DevMode)

For development and testing with live code reload:

```bash
# Navigate to project directory
cd /path/to/circuitjs1_desktop

# Before starting, check DevMode isn't already running
# (it typically listens on 8888 for the app and 9876 for the code server)
ss -ltnp | grep -E ':(8888|9876)\b' || true

# If you prefer checking by process name instead of ports:
pgrep -fa 'gwt:devmode' || true

# Recommended: start DevMode in the background so it keeps running
# even if you run other build commands / restart shells.
nohup mvn gwt:devmode > devmode.log 2>&1 & disown

# Follow logs (optional)
tail -f devmode.log

# Option A (foreground): start GWT DevMode directly
mvn gwt:devmode &

# Option B: npm wrapper (starts DevMode and also tries to launch NW.js)
npm run devmode &
```

The application will be available at:
- **Application**: http://127.0.0.1:8888/circuitjs.html
- **Code Server**: http://127.0.0.1:9876/

When you plan to use the JavaScript API interactively or via Chrome DevTools MCP, open the application URL in **Google Chrome** and use **Chrome DevTools** (Console). This is the supported workflow for automation; avoid relying on embedded/alternate browsers where DevTools/MCP attachment may not work.

GWT DevMode automatically recompiles Java code when you refresh the page, making it ideal for development and testing the JavaScript API.

### Production Build

For production use:

```bash
# Option A: compile via Maven
mvn gwt:compile

# Option B: npm wrapper (runs a full Maven clean+install and copies output to target/site)
npm run buildgwt

# The compiled application is in target/circuitjs1mod-*/circuitjs1/
```

## Using with Chrome DevTools MCP

The JavaScript API can be controlled programmatically using [Chrome DevTools MCP](https://github.com/anthropics/anthropic-cookbook/tree/main/misc/chrome_devtools_mcp) (Model Context Protocol). This enables AI assistants like Claude to directly interact with CircuitJS1.

The desktop app also runs its own MCP server, which gives agents typed `circuit_*` tools over the Agent API without a browser in between — see the "MCP server" section of the [README](../README.md). The Chrome DevTools route below remains useful for development and debugging.

Important: MCP can only control pages that are open inside the **Chrome instance connected to the MCP server** (agent-controlled Chrome). Opening CircuitJS1 in a different browser window, an embedded preview, or a separate Chrome profile that is not connected to MCP will not be controllable via MCP.

### Setup Chrome DevTools MCP

1. Install Chrome DevTools MCP server
2. Configure your MCP client (e.g., Claude Desktop) to connect to the server
3. Start/open the **agent-controlled Chrome** instance (the one exposed to MCP)
4. Open CircuitJS1 inside that Chrome instance (via navigation or creating a new page)
5. The MCP tools can now control the browser and execute API calls on the selected page

Minimal “agent Chrome” workflow:

```text
# Create/open a page inside the MCP-controlled Chrome
mcp_chrome-devtoo_new_page(url="http://127.0.0.1:8888/circuitjs.html")

# Verify the app is loaded and the API is available
mcp_chrome-devtoo_evaluate_script(function="() => ({ ready: document.readyState, hasCircuitJS1: typeof CircuitJS1 !== 'undefined' })")
```

### MCP Tools for CircuitJS1

**Navigate to application:**
```
mcp_chrome-devtoo_navigate_page(url="http://127.0.0.1:8888/circuitjs.html")
```

**Execute JavaScript API calls:**
```
mcp_chrome-devtoo_evaluate_script(function="() => {
  return CircuitJS1.getSimInfo();
}")
```

**Take screenshots for verification:**
```
mcp_chrome-devtoo_take_screenshot()
```

**Get page snapshot (accessibility tree):**
```
mcp_chrome-devtoo_take_snapshot()
```

### Example: Automated Circuit Testing via MCP

```javascript
// Example evaluate_script for testing a circuit
() => {
  // Load test circuit (RC circuit with voltage source)
  const circuit = {
    "schema": {"format": "circuitjs", "version": "2.1"},
    "simulation": {
      "time_step": "5 us",
      "voltage_range": "5 V"
    },
    "elements": {
      "R1": {
        "type": "Resistor",
        "pins": {"pin1": {"position": {"x": 208, "y": 112}}, "pin2": {"position": {"x": 208, "y": 272}}},
        "properties": {"resistance": "10 kΩ"}
      },
      "C1": {
        "type": "Capacitor",
        "pins": {"pin1": {"position": {"x": 208, "y": 272}}, "pin2": {"position": {"x": 352, "y": 272}}},
        "properties": {"capacitance": "10 uF"}
      },
      "V1": {
        "type": "VoltageSourceSquare",
        "pins": {"minus": {"position": {"x": 352, "y": 272}}, "plus": {"position": {"x": 352, "y": 112}}},
        "properties": {"frequency": "40 Hz", "max_voltage": "5 V"}
      },
      "W1": {
        "type": "Wire",
        "pins": {"a": {"position": {"x": 352, "y": 112}}, "b": {"position": {"x": 208, "y": 112}}}
      }
    }
  };

  CircuitJS1.importFromJson(JSON.stringify(circuit));
  
  // Run simulation
  CircuitJS1.setSimRunning(true);
  
  return {
    elementCount: CircuitJS1.getElementCount(),
    isRunning: CircuitJS1.isRunning()
  };
}
```

### MCP Workflow for Development

1. **Start DevMode**: Run `mvn gwt:devmode` (or `npm run devmode` if you want the npm wrapper)
2. **Navigate browser**: Use `navigate_page` to open CircuitJS1
3. **Wait for load**: Wait ~20-30 seconds for GWT compilation on first load
4. **Check API availability**: 
   ```javascript
   () => typeof CircuitJS1 !== 'undefined'
   ```
5. **Interact with API**: Use `evaluate_script` for any API calls
6. **View console logs**: Use `list_console_messages` to see application logs
7. **Capture state**: Use `take_screenshot` or `take_snapshot` as needed

## Global Object

All API methods are available through the global `CircuitJS1` object that is created when the application loads. A second, separate global `CircuitJS1Agent` carries the Agent API — the typed, document-aware interface used by AI agents and the in-app MCP server (see [CircuitJS1Agent](#circuitjs1agent-agent-api)).

```javascript
// Check if API is available
if (typeof CircuitJS1 !== 'undefined') {
    console.log("CircuitJS1 API is ready!");
}
```

## Hooks

You can register callback hooks for lifecycle events:

`oncircuitjsloaded` fires once at the end of start-up, after the initial run state and mouse mode are applied and after `window.CircuitJS1Agent` reports ready, so settings made inside the hook are kept.

```javascript
// Called when CircuitJS1 is loaded
window.oncircuitjsloaded = function(api) {
    console.log("CircuitJS1 loaded", api);
};

// Called on each simulation update
CircuitJS1.onupdate = function(api) {
    console.log("Time:", api.getTime());
};

// Called after circuit analysis (visible document only)
CircuitJS1.onanalyze = function(api) {
    console.log("Circuit analyzed");
};

// Called on each time step (visible document only)
CircuitJS1.ontimestep = function(api) {
    // Called every simulation step
};

// Called when SVG is rendered
CircuitJS1.onsvgrendered = function(api, svgData) {
    console.log("SVG rendered", svgData.length, "bytes");
};
```

Each hook is a single slot: assigning it replaces the previous handler. `onanalyze` and `ontimestep` fire only for the visible tab's document; they are not called while an agent operation works on a background document (for example a background `run`).

## Simulation Control

### setSimRunning(run: boolean): void
Start or stop the simulation.

```javascript
CircuitJS1.setSimRunning(true);  // Start simulation
CircuitJS1.setSimRunning(false); // Stop simulation
```

### isRunning(): boolean
Check if simulation is currently running.

```javascript
if (CircuitJS1.isRunning()) {
    console.log("Simulation is running");
}
```

### getTime(): number
Get current simulation time in seconds.

```javascript
const simTime = CircuitJS1.getTime();
console.log("Simulation time:", simTime, "seconds");
```

### getTimeStep(): number
Get current simulation time step in seconds.

```javascript
const dt = CircuitJS1.getTimeStep();
console.log("Time step:", dt, "seconds");
```

### setTimeStep(ts: number): void
Set the current simulation time step. The value does not stick: the next circuit analysis sets the time step back to the maximum time step. To change the step of a circuit, use `setMaxTimeStep`.

```javascript
CircuitJS1.setTimeStep(1e-6); // 1 microsecond
```

### getMaxTimeStep(): number
Get maximum allowed time step.

### setMaxTimeStep(ts: number): void
Set the maximum time step (and the current time step to the same value).

### resetSimulation(): void
Reset simulation time to 0 and reset all elements to initial state.

```javascript
CircuitJS1.resetSimulation();
console.log(CircuitJS1.getTime()); // 0
```

### stepSimulation(): void
Perform a single simulation step (for debugging/testing).

```javascript
CircuitJS1.setSimRunning(false);
CircuitJS1.stepSimulation();
console.log(CircuitJS1.getTime()); // One time step later
```

### getSimInfo(): SimInfo
Get comprehensive simulation information.

```javascript
const info = CircuitJS1.getSimInfo();
// Returns:
// {
//   time: number,           // Current simulation time
//   timeStep: number,       // Current time step
//   maxTimeStep: number,    // Maximum time step
//   running: boolean,       // Is simulation running
//   stopMessage: string,    // Error message if stopped
//   elementCount: number    // Number of circuit elements
// }
```

## Node and Voltage Access

### getNodeVoltage(name: string): number
Get voltage at a labeled node.

```javascript
const voltage = CircuitJS1.getNodeVoltage("Vout");
console.log("Vout =", voltage, "V");
```

### setExtVoltage(name: string, voltage: number): void
Set voltage of an external voltage source by name.

```javascript
CircuitJS1.setExtVoltage("Vin", 5.0); // Set Vin to 5V
```

## Element Access

### getElements(): Element[]
Get array of all circuit elements.

```javascript
const elements = CircuitJS1.getElements();
elements.forEach(elm => {
    console.log(elm.getId(), elm.getType(), elm.getVoltageDiff());
});
```

Each element has these methods:
- `getId()` - Unique element ID (e.g., "R1", "C2", "Q3")
- `getType()` - Element class name (e.g., "ResistorElm")
- `getTypeName()` - JSON type name (e.g., "Resistor")
- `getDescription()` - Element description if set
- `getInfo()` - Get element info array (varies by element)
- `getVoltageDiff()` - Voltage across element
- `getVoltage(postIndex)` - Voltage at specific post
- `getCurrent()` - Current through element
- `getPower()` - Power dissipation/consumption
- `getLabelName()` - Label if present
- `getPostCount()` - Number of connection posts
- `setProperty(name, value)` - Set a property value (returns boolean)
- `getX()`, `getY()` - Start point coordinates
- `getX2()`, `getY2()` - End point coordinates  
- `isSelected()` - Check if element is selected

### getElementCount(): number
Get total number of elements in circuit.

```javascript
const count = CircuitJS1.getElementCount();
console.log("Circuit has", count, "elements");
```

### getElementByIndex(index: number): Element | null
Get a specific element by index.

```javascript
const elm = CircuitJS1.getElementByIndex(0);
if (elm) {
    console.log("First element:", elm.getType());
}
```

### getElementById(id: string): Element | null
Get a specific element by its unique ID.

```javascript
const resistor = CircuitJS1.getElementById("R1");
if (resistor) {
    console.log("R1 voltage:", resistor.getVoltageDiff());
    console.log("R1 current:", resistor.getCurrent());
}
```

### getElementIds(): string[]
Get array of all element IDs in the circuit.

```javascript
const ids = CircuitJS1.getElementIds();
console.log("Element IDs:", ids);
// ["R1", "R2", "C1", "V1", "GND1", ...]
```

### Element IDs

Element IDs come from one per-document registry. The same IDs are returned by `getElementIds()`, accepted by `getElementById()` and the other ID-based methods, used as the element keys of the JSON export, and used as element IDs by the Agent API.

- **Form.** `^[A-Za-z][A-Za-z0-9_]{0,31}$`, unique within the document.
- **Generated IDs.** `<prefix><n>`, numbered per prefix (`R1`, `R2`, `C1`, `W1` …); `n` is the prefix's counter plus one, skipping IDs already present. The prefix is letters only: the element's own prefix where it defines one (`R`, `C`, `L`, `W`, `GND`, `V`, `I`, `D`, `LED`, `Z`, `U`, `M`, `K`, `T`, `SW`), otherwise the first three letters of its JSON type name, upper-cased, with digits dropped (`TransistorNPN` → `TRA1`, `CC2` → `CC1`, `Timer555` → `TIM1`).
- **Counters.** Within one circuit content they never decrease: a deleted element's number is not reissued, and every ID that enters the document (add, import, paste, undo/redo) raises its prefix's counter. Replacing the content (import, open, clear) resets the counters.
- **Loading.** A JSON import keeps its element keys when they are valid and unique; an invalid or repeated key is replaced by a generated ID and an `ids_regenerated` warning is logged. The text format carries no IDs, so a text load numbers the elements in file order (the same file always gives the same IDs). Pasted and duplicated elements receive generated IDs.
- **Undo/redo** restores each element's ID.

Before this registry the JSON exporter numbered its keys with one global counter (`R1`, `C2`, `W3`); see [agent-api.sp.md §03_02](./agent-api.sp.md#SP_AGA_03_02) and [§06_01](./agent-api.sp.md#SP_AGA_06_01).

### getElementInfo(id: string): ElementInfo | null
Get comprehensive information about an element by ID.

```javascript
const info = CircuitJS1.getElementInfo("R1");
console.log(info);
// {
//   id: "R1",
//   type: "ResistorElm",
//   typeName: "Resistor",
//   description: null,
//   postCount: 2,
//   voltageDiff: 5.0,
//   current: 0.005,
//   power: 0.025,
//   x: 200, y: 100,
//   x2: 300, y2: 100,
//   selected: false
// }
```

### getElementProperties(id: string): object | null
Get the editable properties of an element.

```javascript
const props = CircuitJS1.getElementProperties("R1");
console.log(props);
// { resistance: "1 kΩ" }

const capProps = CircuitJS1.getElementProperties("C1");
console.log(capProps);
// { capacitance: "10 µF", initial_voltage: "0 V" }
```

### setElementProperty(id: string, property: string, value: number): boolean
Set a numeric property value for an element. Returns true if successful. Only a few element types implement it — resistor (`resistance`), capacitor (`capacitance`), transformer and tapped transformer; every other type returns false. The universal way to change element parameters is the Agent API (`applyEdits` with a `set` edit, see [CircuitJS1Agent](#circuitjs1agent-agent-api)).

```javascript
// Change resistance of R1 to 2000 ohms
const success = CircuitJS1.setElementProperty("R1", "resistance", 2000);
console.log("Property set:", success);

// Change capacitance of C1 to 100 microfarads
CircuitJS1.setElementProperty("C1", "capacitance", 100e-6);
```

### updateElementProperties(id: string, properties: object): boolean
Apply a JSON `properties` object to an element without recreating it. Returns true when the element was found. It is not a patch: the element reads its whole JSON property set from the object, so every key you omit goes back to its default value (for example, `{initial_voltage: 5}` on a 47 µF capacitor also resets its capacitance to 10 µF). Pass the complete property set from `getElementProperties`, modified. Element geometry is not recomputed, and no undo entry is pushed.

```javascript
// Update multiple properties at once
const success = CircuitJS1.updateElementProperties("R1", {
    resistance: 4700
});
console.log("Properties updated:", success);

// Example: Export-Modify-Update workflow (without recreating element)
const props = CircuitJS1.getElementProperties("C1");
console.log("Current props:", props);
// Modify and update: pass the full set, omitted keys reset to their defaults
CircuitJS1.updateElementProperties("C1", Object.assign({}, props, {
    capacitance: 47e-6,
    initial_voltage: 5
}));
```

**Use case: Export-Modify-Import without creating new object**

```javascript
// 1. Get current properties
const id = "R1";
const currentProps = CircuitJS1.getElementProperties(id);
console.log("Before:", currentProps);

// 2. Modify properties (e.g., received from external editor); keep the other keys
const modifiedProps = Object.assign({}, currentProps, { resistance: 10000 });

// 3. Apply changes without recreating element
CircuitJS1.updateElementProperties(id, modifiedProps);

// 4. Verify
const newProps = CircuitJS1.getElementProperties(id);
console.log("After:", newProps);
```

**Use case: Move element to new position (delete + recreate)**

When you need to change an element's position or connections, use delete + recreate workflow.
This is the recommended approach because changing topology requires full circuit reanalysis anyway.

```javascript
// 1. Get current element data
const id = "R1";
const props = CircuitJS1.getElementProperties(id);
const info = CircuitJS1.getElementInfo(id);
console.log("Current position:", info.x, info.y, "->", info.x2, info.y2);

// 2. Delete the old element
CircuitJS1.deleteElementById(id);

// 3. Create new element with same ID but different position
const circuit = {
    "schema": {"format": "circuitjs", "version": "2.1"},  // required, otherwise the import is rejected
    "elements": {
        "R1": {  // Same ID preserved
            "type": "Resistor",
            "pins": {
                "pin1": {"position": {"x": 304, "y": 208}},  // New position
                "pin2": {"position": {"x": 400, "y": 208}}
            },
            "properties": {
                "resistance": 1000  // Use value from props if needed
            }
        }
    }
};
CircuitJS1.importFromJson(JSON.stringify(circuit));

// 4. Verify - element exists with same ID at new position
const newInfo = CircuitJS1.getElementInfo(id);
console.log("New position:", newInfo.x, newInfo.y, "->", newInfo.x2, newInfo.y2);
```

**Why delete + recreate instead of move API?**
- Changing element position changes circuit topology
- Circuit requires full reanalysis (`needAnalyze()`) anyway
- This approach guarantees correct connections
- Element ID can be preserved through JSON import

### selectElementById(id: string, addToSelection?: boolean): boolean
Select an element by ID. If `addToSelection` is true, adds to current selection.

```javascript
// Select only R1
CircuitJS1.selectElementById("R1");

// Add C1 to selection
CircuitJS1.selectElementById("C1", true);
```

### deleteElementById(id: string): boolean
Delete an element by ID.

```javascript
const deleted = CircuitJS1.deleteElementById("R1");
console.log("Element deleted:", deleted);
```

### deleteElementByIndex(index: number): boolean
Delete an element by index.

```javascript
const deleted = CircuitJS1.deleteElementByIndex(0);
console.log("Element deleted:", deleted);
```

## Circuit Export/Import

### exportCircuit(): string
Export circuit in text format (original format).

```javascript
const textData = CircuitJS1.exportCircuit();
// Save or process the text data
```

### importCircuit(text: string, subcircuitsOnly: boolean): void
Import circuit from text format (legacy format).

```javascript
// Legacy text format (still supported)
const circuitText = `$ 1 0.000005 5.459815003314424 50 5 43 5e-11
r 208 176 384 176 0 1000
v 208 288 208 176 0 1 40 5 0 0 0.5`;

CircuitJS1.importCircuit(circuitText, false);

// Prefer using importFromJson() with JSON format instead
```

### exportAsJson(): string
Export circuit in JSON format (version 2.1) without simulation state.

```javascript
const jsonData = CircuitJS1.exportAsJson();
console.log(JSON.parse(jsonData));
```

### exportAsJsonWithState(): string
Export circuit in JSON format (version 2.1) including simulation state (pin voltages, currents, internal element states).

This method includes additional `state` field for each element containing:
- `pins`: Object with pin names as keys, each containing `v` (voltage) and `i` (current into node)
- Element-specific state like `voltage_diff` for capacitors, `current` for inductors, `ib`/`ic`/`ie` for transistors

```javascript
const jsonData = CircuitJS1.exportAsJsonWithState();
const circuit = JSON.parse(jsonData);

// Access capacitor state
const capState = circuit.elements.C1.state;
console.log('Capacitor voltage difference:', capState.voltage_diff);
console.log('Pin1 voltage:', capState.pins.pin1.v);

// Access transistor state
const transistorState = circuit.elements.Q1.state;
console.log('Base voltage:', transistorState.pins.base.v);
console.log('Collector current:', transistorState.ic);
```

### importFromJson(json: string): void
Import circuit from JSON format (recommended). Supports importing simulation state if present.

Any `2.x` schema version is accepted. Files written as 2.0 with the superseded polar pin names (`positive`/`negative` of voltage and current sources, `probe+`/`probe-` of the ohmmeter) load with their old meaning; see [EXPORT_CJS.md](./EXPORT_CJS.md) (version 2.1 note).

```javascript
// Create circuit programmatically
const circuit = {
  "schema": {"format": "circuitjs", "version": "2.1"},
  "simulation": {
    "time_step": "5 us",
    "voltage_range": "5 V"
  },
  "elements": {
    "R1": {
      "type": "Resistor",
      "pins": {"pin1": {"position": {"x": 208, "y": 176}}, "pin2": {"position": {"x": 384, "y": 176}}},
      "properties": {"resistance": "1 kΩ"}
    },
    "V1": {
      "type": "VoltageSourceSquare",
      "pins": {"minus": {"position": {"x": 208, "y": 288}}, "plus": {"position": {"x": 208, "y": 176}}},
      "properties": {"frequency": "40 Hz", "max_voltage": "5 V"}
    }
  }
};

CircuitJS1.importFromJson(JSON.stringify(circuit));

// Or re-import exported circuit
const exported = CircuitJS1.exportAsJson();
CircuitJS1.importFromJson(exported);
```

### clearCircuit(): void
Clear the circuit (create new blank circuit).

```javascript
CircuitJS1.clearCircuit();
console.log(CircuitJS1.getElementCount()); // 0
```

### getCircuitAsSVG(): void
Render the circuit as an SVG image. The call returns `undefined`; the SVG text arrives through the `onsvgrendered` hook. On the first call of a session the vector exporter script (`canvas2svg.js`) is loaded first and the hook fires after it has loaded. The Agent API `render` contract returns the image as its own result instead.

```javascript
CircuitJS1.onsvgrendered = function(api, svg) {
    // Use SVG data for documentation or display
    console.log("SVG:", svg.length, "chars");
};
CircuitJS1.getCircuitAsSVG();
```

## Scope (Oscilloscope) Access

### getScopeCount(): number
Get number of active scopes.

```javascript
const scopeCount = CircuitJS1.getScopeCount();
console.log("Active scopes:", scopeCount);
```

### getScopeInfo(index: number): ScopeInfo | null
Get information about a specific scope.

```javascript
const info = CircuitJS1.getScopeInfo(0);
// Returns:
// {
//   index: number,         // Scope index
//   elementType: string,   // Element type being monitored
//   showVoltage: boolean,  // Showing voltage
//   showCurrent: boolean,  // Showing current
//   showFFT: boolean,      // Showing FFT
//   speed: number,         // Display speed
//   plotCount: number      // Number of plots
// }
```

### getScopeData(scopeIndex: number, plotIndex: number): ScopeData | null
Get raw scope data for a specific plot.

```javascript
const data = CircuitJS1.getScopeData(0, 0);
// Returns:
// {
//   minValues: number[],   // Min values array
//   maxValues: number[],   // Max values array
//   ptr: number,           // Current pointer position
//   pointCount: number,    // Total points in buffer
//   units: number          // Unit type (0=V, 1=A, 2=W, 3=Ohms)
// }
```

## Canvas Control

### redrawCanvasSize(): void
Force redraw and resize canvas.

```javascript
CircuitJS1.redrawCanvasSize();
```

## Logging

### getLogs(): string[]
Get all log entries.

```javascript
const logs = CircuitJS1.getLogs();
logs.forEach(entry => console.log(entry));
```

### getLastLogs(count: number): string[]
Get last N log entries.

```javascript
const recentLogs = CircuitJS1.getLastLogs(10);
console.log("Last 10 log entries:", recentLogs);
```

### getLogCount(): number
Get total number of log entries.

```javascript
const count = CircuitJS1.getLogCount();
console.log("Total log entries:", count);
```

### addLog(message: string): void
Add a custom log entry.

```javascript
CircuitJS1.addLog("Custom message from API");
```

### Global error logging
Uncaught exceptions and unhandled promise rejections are automatically captured by the application and recorded in the same log store available via `getLogs()` / `getLastLogs()`. Error entries include a timestamp and, when available, a stack trace and context. Entries use the tags **[UNHANDLED_ERROR]** and **[UNHANDLED_REJECTION]** so they are easy to filter.

A user-facing modal dialog is shown when an unhandled error or rejection occurs (titles: **"Unhandled error"** / **"Unhandled promise rejection"**). The dialog contains the error details and a **Copy details** button so users can easily share the information.

Example: retrieve and filter recent error logs

```javascript
// get the last 50 log entries and show only error-related lines
const logs = CircuitJS1.getLastLogs(50);
const errors = logs.filter(l => /UNHANDLED_ERROR|UNHANDLED_REJECTION|API_LOG/i.test(l));
console.log('Recent errors:', errors);
```

If the optional `scripts/debug-logger.js` is loaded, an on-page debug panel is available as `window.debugConsole` and logs are also written into that panel. The debug logger also queues API logs when `CircuitJS1.addLog` is not yet available and flushes them when the API becomes ready (exposed via `window.debugConsole._flushPendingApiLogs()`).

Notes:
- Use `CircuitJS1.getLogCount()` and `CircuitJS1.getLastLogs()` to programmatically collect error logs for reporting or telemetry.
- The error dialog is intended for interactive desktop/dev use; if you need to suppress UI in automated or production runs, add a small wrapper to disable the debug UI or load a production configuration that omits `debug-logger.js`.

### clearLogs(): void
Clear all log entries.

```javascript
CircuitJS1.clearLogs();
console.log(CircuitJS1.getLogCount()); // 0
```

## Permissions

### allowSave(allow: boolean): void
Enable or disable save functionality.

```javascript
CircuitJS1.allowSave(true);
```

## CircuitJS1Agent (Agent API)

`window.CircuitJS1Agent` is the Agent API: a transport-free interface through which agents build, edit, inspect, run, measure, debug and checkpoint circuits in **any open document**, not only the visible one. It is installed at start-up together with `CircuitJS1`, in the desktop and the browser build. The in-app MCP server maps its tools onto it (see [mcp-server.sp.md](./mcp-server.sp.md) and the "MCP server" section of the [README](../README.md)). The full contract is [agent-api.sp.md](./agent-api.sp.md) (SP_AGA); this section is an overview.

### Entry points

| Method | Description |
|---|---|
| `call(op, argsJson): string` | Runs a synchronous contract and returns its result as a JSON string. `argsJson` is a JSON object string (an object is also accepted). Calling `run` or `render` here returns `invalid_value`: they complete asynchronously |
| `callAsync(op, argsJson, callback): void` | Runs any contract; `callback(resultJson)` is called exactly once. A synchronous contract or a rejection calls back before `callAsync` returns; an accepted `run` or `render` calls back when it ends |
| `reportError(message): void` | Passes an unexpected exception of a JS caller (the MCP server) to the application's global uncaught-exception handler, where it is shown and logged like an application error |

Every result is an OperationResult:

```javascript
{
  ok: true,              // false: rejected, nothing was changed
  data: { ... },         // contract output
  issues: [              // rejection reasons (ok = false) or warnings/info; at most 50, errors first
    { code: "dangling_post", severity: "error", message: "...", elements: ["R1"], posts: ["R1.pin2"], hint: "...", key: "..." }
  ],
  truncatedIssues: 0,
  connectivity: { ... }, // mutating contracts: connectivity delta (issues added/cleared)
  transaction: { open: true, pendingEdits: 1 }  // mutating contracts: the agent transaction
}
```

Common rules: `doc` (a document handle `d1`, `d2` …) selects the document, and its absence means the active one; an unknown handle is `unknown_document`. Before start-up has completed every contract returns `not_ready`, and an unknown `op` is `invalid_value` with the operation list in its hint. Domain failures are results, never exceptions.

### Contracts

| Contract | Purpose |
|---|---|
| `listTypes`, `describeType` | Element catalogue: type names, aliases, pins, default size, property keys with kinds, units and defaults ([§02_01](./agent-api.sp.md#SP_AGA_02_01)) |
| `listDocuments`, `createDocument`, `activateDocument`, `closeDocument` | Open documents by handle; only `activateDocument`, `activate: true` and closing the active document change the visible tab ([§02_02](./agent-api.sp.md#SP_AGA_02_02)) |
| `importCircuit` | Replace a document's circuit with an agent circuit (grid-cell coordinates), a JSON v2 text or a legacy text, atomically ([§02_03](./agent-api.sp.md#SP_AGA_02_03)) |
| `applyEdits` | Ordered, atomic batch of `add`, `move`, `delete`, `set` (a property patch), `describe`, `addScope`, `removeScope` and `markOpen` edits ([§02_04](./agent-api.sp.md#SP_AGA_02_04)) |
| `getCircuit` | The circuit in agent form: element records with posts in cells, paged ([§02_05](./agent-api.sp.md#SP_AGA_02_05)) |
| `getConnectivity` | Nets and connectivity issues (dangling posts, posts on wire bodies, no ground, isolated groups, source/wire loops …) ([§02_06](./agent-api.sp.md#SP_AGA_02_06)) |
| `read` | Instant net, post and element readings at the current simulated time ([§02_07](./agent-api.sp.md#SP_AGA_02_07)) |
| `render` (async) | SVG or PNG image of the whole circuit of one document, as the result ([§02_08](./agent-api.sp.md#SP_AGA_02_08)) |
| `simControl` | Free-running run/stop/reset and time-step settings (`configure`) ([§02_09](./agent-api.sp.md#SP_AGA_02_09)) |
| `run` (async) | Advance simulated time by a span or until settled, under a wall-clock budget, with probe statistics and decimated series ([§02_10](./agent-api.sp.md#SP_AGA_02_10)) |
| `getDiagnostics` | Solver state and events, last import issues and the session log ([§02_11](./agent-api.sp.md#SP_AGA_02_11)) |
| `checkpoint` | Seal the open agent transaction as one commented undo entry ([§02_12](./agent-api.sp.md#SP_AGA_02_12)) |
| `getHistory`, `undo`, `redo`, `restoreCheckpoint` | Undo history with checkpoint comments, multi-step undo/redo, return to a checkpoint ([§02_13](./agent-api.sp.md#SP_AGA_02_13)) |
| `openFile`, `saveFile`, `exportCircuit` | Path-based open/save of `.txt`/`.json` circuit files (desktop runtime only, otherwise `file_unavailable`) and export as text or JSON ([§02_14](./agent-api.sp.md#SP_AGA_02_14)) |

Agent coordinates are grid cells (1 cell = 16 editor pixels, half-cell lattice); pins are named `<ElementId>.<PinName>` (for example `R1.pin1`, `V1.plus`; a two-post voltage source has `minus`/`plus`, a current source `in`/`out` — [§03_02](./agent-api.sp.md#SP_AGA_03_02) "Polar names"). Issue codes and their severities are listed in [§03_05](./agent-api.sp.md#SP_AGA_03_05) and [§03_06](./agent-api.sp.md#SP_AGA_03_06).

### Example

```javascript
const A = window.CircuitJS1Agent;
const call = (op, args) => JSON.parse(A.call(op, JSON.stringify(args || {})));
const callAsync = (op, args) => new Promise(resolve =>
    A.callAsync(op, JSON.stringify(args || {}), r => resolve(JSON.parse(r))));

// RC charging circuit in grid cells
const imp = call("importCircuit", { circuit: { elements: [
    { type: "VoltageSourceDC", start: {x: 0, y: 4}, end: {x: 0, y: 0}, properties: {max_voltage: "5 V"} },
    { id: "R1", type: "Resistor", start: {x: 0, y: 0}, end: {x: 6, y: 0}, properties: {resistance: "1k"} },
    { id: "C1", type: "Capacitor", start: {x: 6, y: 0}, end: {x: 6, y: 4}, properties: {capacitance: "1 uF"} },
    { type: "Wire", start: {x: 6, y: 4}, end: {x: 0, y: 4} },
    { type: "Ground", start: {x: 0, y: 4}, end: {x: 0, y: 5} }
]}});
console.log(imp.data.ids);                  // ["V1", "R1", "C1", "W1", "GND1"]
console.log(call("getConnectivity").data.issues);  // []

// Run 5 ms from t = 0 and probe the capacitor voltage
const run = await callAsync("run", { span: "5 ms", reset: true, maxPoints: 20,
                                     probes: [{ name: "vc", element: "C1" }] });
console.log(run.data.probes[0].stats.final);  // ≈ 4.97 V

// Seal the edits as one named undo entry
call("checkpoint", { comment: "RC charging circuit" });
```

### Diagnostics (not part of the contract)

`CircuitJS1Agent` also carries `debug*` functions used by the test harnesses (`tests/live/harness.mjs`, `tests/mcp/e2e.mjs`): `debugViewState`, `debugDocState`, `debugSessionState`, `debugClosedTabs`, `debugCanvasPixels`, `debugCircuitTest`, `debugMcpStatus`, `debugSetSliceProbe`, `debugSetIdleSealMs`, `debugAgentOriginPush` and the fault injectors `debugFailNextMutation`, `debugFailNextUndoLoad`, `debugFailNextRunSlice`, `debugFailNextSvgLoad`. They are not part of the Agent API contract, may change or disappear without notice, and must not be used by scripts or agents.

## Complete Example

```javascript
// Wait for CircuitJS1 to load
window.oncircuitjsloaded = function(api) {
    
    // Import a simple RC circuit using JSON format
    const circuit = {
      "schema": {"format": "circuitjs", "version": "2.1"},
      "simulation": {
        "time_step": "5 us",
        "voltage_range": "5 V",
        "current_speed": 50
      },
      "elements": {
        "R1": {
          "type": "Resistor",
          "p1": {"x": 208, "y": 112},
          "p2": {"x": 208, "y": 272},
          "properties": {"resistance": "10 kΩ"}
        },
        "C1": {
          "type": "Capacitor",
          "p1": {"x": 208, "y": 272},
          "p2": {"x": 352, "y": 272},
          "properties": {"capacitance": "10 uF"}
        },
        "V1": {
          "type": "VoltageSource",
          "p1": {"x": 352, "y": 272},
          "p2": {"x": 352, "y": 112},
          "properties": {"waveform": "square", "frequency": "40 Hz", "voltage": "5 V"}
        },
        "W1": {
          "type": "Wire",
          "p1": {"x": 352, "y": 112},
          "p2": {"x": 208, "y": 112}
        }
      },
      "scopes": [
        {"element": "C1", "show_voltage": true, "show_current": false}
      ]
    };
    
    api.importFromJson(JSON.stringify(circuit));
    
    // Register update hook
    api.onupdate = function() {
        const elements = api.getElements();
        if (elements.length > 0) {
            const cap = elements[1]; // Capacitor
            console.log("Capacitor voltage:", cap.getVoltageDiff().toFixed(3), "V");
        }
    };
    
    // Run simulation for 1 second of real time
    api.setSimRunning(true);
    
    setTimeout(function() {
        api.setSimRunning(false);
        console.log("Final simulation time:", api.getTime().toFixed(6), "seconds");
        
        // Export as JSON
        const json = api.exportAsJson();
        console.log("Circuit JSON:", json);
    }, 1000);
};
```

## Using with Chrome DevTools Console

The API can be tested directly in Chrome DevTools console (F12 → Console):

```javascript
// Check element count
CircuitJS1.getElementCount()

// Run simulation
CircuitJS1.setSimRunning(true)

// Stop and check time
CircuitJS1.setSimRunning(false)
CircuitJS1.getTime()

// Step through simulation
CircuitJS1.stepSimulation()

// Export to JSON
CircuitJS1.exportAsJson()

// View logs
CircuitJS1.getLogs()
CircuitJS1.getLastLogs(10)
```

### Debugging Tips

1. **Check API availability first:**
   ```javascript
   typeof CircuitJS1 !== 'undefined' // should return true
   ```

2. **List all available methods:**
   ```javascript
   Object.keys(CircuitJS1)
   ```

3. **Monitor simulation in real-time:**
   ```javascript
   setInterval(() => {
     console.log('Time:', CircuitJS1.getTime().toFixed(6));
   }, 100);
   ```

4. **Watch element voltages:**
   ```javascript
   CircuitJS1.getElements().forEach((elm, i) => {
     console.log(i, elm.getType(), elm.getVoltageDiff().toFixed(3) + 'V');
   });
   ```

## Notes

- All `CircuitJS1` methods are synchronous; `getCircuitAsSVG` delivers its result through the `onsvgrendered` hook
- Every `CircuitJS1` method acts on the active (visible) document only; the Agent API addresses any open document by handle
- `setSimRunning`, `resetSimulation` and `stepSimulation` first end an agent `run` of the active document ([SP_AGA_04_02](./agent-api.sp.md#SP_AGA_04_02))
- Element edits through `CircuitJS1` (property setters, deletes) do not push an undo entry
- The `importFromJson()` clears the existing circuit before importing
- Scope data arrays are circular buffers with `ptr` indicating current position
- Time values are in seconds
- Voltage values are in Volts
- Current values are in Amperes
- GWT DevMode requires ~20-30 seconds for initial compilation on page load
