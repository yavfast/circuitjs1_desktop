# CircuitJS1 JavaScript API

This document describes the JavaScript API available for programmatic control of CircuitJS1 from web browsers or automation tools.

## Running the Application

### Development Mode (GWT DevMode)

For development and testing with live code reload:

```bash
# Navigate to project directory
cd /path/to/circuitjs1_desktop

# Start GWT DevMode
mvn gwt:devmode
```

The application will be available at:
- **Application**: http://127.0.0.1:8888/circuitjs.html
- **Code Server**: http://127.0.0.1:9876/

GWT DevMode automatically recompiles Java code when you refresh the page, making it ideal for development and testing the JavaScript API.

### Production Build

For production use:

```bash
# Compile GWT to JavaScript
mvn gwt:compile

# The compiled application is in target/circuitjs1mod-*/circuitjs1/
```

## Using with Chrome DevTools MCP

The JavaScript API can be controlled programmatically using [Chrome DevTools MCP](https://github.com/anthropics/anthropic-cookbook/tree/main/misc/chrome_devtools_mcp) (Model Context Protocol). This enables AI assistants like Claude to directly interact with CircuitJS1.

### Setup Chrome DevTools MCP

1. Install Chrome DevTools MCP server
2. Configure your MCP client (e.g., Claude Desktop) to connect to the server
3. Open CircuitJS1 in Chrome browser
4. The MCP tools can now control the browser

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
    "schema": {"format": "circuitjs", "version": "2.0"},
    "simulation": {
      "time_step": "5 us",
      "voltage_range": "5 V"
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

1. **Start DevMode**: Run `mvn gwt:devmode` in terminal
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

All API methods are available through the global `CircuitJS1` object that is created when the application loads.

```javascript
// Check if API is available
if (typeof CircuitJS1 !== 'undefined') {
    console.log("CircuitJS1 API is ready!");
}
```

## Hooks

You can register callback hooks for lifecycle events:

```javascript
// Called when CircuitJS1 is loaded
window.oncircuitjsloaded = function(api) {
    console.log("CircuitJS1 loaded", api);
};

// Called on each simulation update
CircuitJS1.onupdate = function(api) {
    console.log("Time:", api.getTime());
};

// Called after circuit analysis
CircuitJS1.onanalyze = function(api) {
    console.log("Circuit analyzed");
};

// Called on each time step
CircuitJS1.ontimestep = function(api) {
    // Called every simulation step
};

// Called when SVG is rendered
CircuitJS1.onsvgrendered = function(api, svgData) {
    console.log("SVG rendered", svgData.length, "bytes");
};
```

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
Set simulation time step.

```javascript
CircuitJS1.setTimeStep(1e-6); // 1 microsecond
```

### getMaxTimeStep(): number
Get maximum allowed time step.

### setMaxTimeStep(ts: number): void
Set maximum time step.

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
Set a property value for an element. Returns true if successful.

```javascript
// Change resistance of R1 to 2000 ohms
const success = CircuitJS1.setElementProperty("R1", "resistance", 2000);
console.log("Property set:", success);

// Change capacitance of C1 to 100 microfarads
CircuitJS1.setElementProperty("C1", "capacitance", 100e-6);
```

### updateElementProperties(id: string, properties: object): boolean
Update multiple properties of an element at once. This method allows updating element
properties without creating a new object, preserving the simulation state (voltages, currents).
Returns true if successful.

```javascript
// Update multiple properties at once
const success = CircuitJS1.updateElementProperties("R1", {
    resistance: 4700
});
console.log("Properties updated:", success);

// Example: Export-Modify-Update workflow (without recreating element)
const props = CircuitJS1.getElementProperties("C1");
console.log("Current props:", props);
// Modify and update
CircuitJS1.updateElementProperties("C1", {
    capacitance: 47e-6,
    initial_voltage: 5
});
```

**Use case: Export-Modify-Import without creating new object**

```javascript
// 1. Get current properties
const id = "R1";
const currentProps = CircuitJS1.getElementProperties(id);
console.log("Before:", currentProps);

// 2. Modify properties (e.g., received from external editor)
const modifiedProps = { resistance: 10000 };

// 3. Apply changes without recreating element
CircuitJS1.updateElementProperties(id, modifiedProps);

// 4. Verify - simulation state is preserved
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
    "version": "2.0",
    "elements": {
        "R1": {  // Same ID preserved
            "type": "Resistor",
            "pins": {
                "A": {"x": 300, "y": 200},  // New position
                "B": {"x": 400, "y": 200}
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
Export circuit in JSON format (version 2.0) without simulation state.

```javascript
const jsonData = CircuitJS1.exportAsJson();
console.log(JSON.parse(jsonData));
```

### exportAsJsonWithState(): string
Export circuit in JSON format (version 2.0) including simulation state (pin voltages, currents, internal element states).

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

```javascript
// Create circuit programmatically
const circuit = {
  "schema": {"format": "circuitjs", "version": "2.0"},
  "simulation": {
    "time_step": "5 us",
    "voltage_range": "5 V"
  },
  "elements": {
    "R1": {
      "type": "Resistor",
      "p1": {"x": 208, "y": 176},
      "p2": {"x": 384, "y": 176},
      "properties": {"resistance": "1 kΩ"}
    },
    "V1": {
      "type": "VoltageSource", 
      "p1": {"x": 208, "y": 288},
      "p2": {"x": 208, "y": 176},
      "properties": {"waveform": "square", "frequency": "40 Hz", "voltage": "5 V"}
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

### getCircuitAsSVG(): string
Export circuit as SVG image.

```javascript
const svg = CircuitJS1.getCircuitAsSVG();
// Use SVG data for documentation or display
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

## Complete Example

```javascript
// Wait for CircuitJS1 to load
window.oncircuitjsloaded = function(api) {
    
    // Import a simple RC circuit using JSON format
    const circuit = {
      "schema": {"format": "circuitjs", "version": "2.0"},
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

- All API methods are synchronous
- The `importFromJson()` clears the existing circuit before importing
- Scope data arrays are circular buffers with `ptr` indicating current position
- Time values are in seconds
- Voltage values are in Volts
- Current values are in Amperes
- GWT DevMode requires ~20-30 seconds for initial compilation on page load
