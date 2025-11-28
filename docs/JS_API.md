# CircuitJS1 JavaScript API

This document describes the JavaScript API available for programmatic control of CircuitJS1 from web browsers or automation tools.

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
    console.log(elm.getType(), elm.getVoltageDiff());
});
```

Each element has these methods:
- `getType()` - Element class name (e.g., "ResistorElm")
- `getInfo()` - Get element info (varies by element)
- `getVoltageDiff()` - Voltage across element
- `getVoltage(postIndex)` - Voltage at specific post
- `getCurrent()` - Current through element
- `getLabelName()` - Label if present
- `getPostCount()` - Number of connection posts

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
Import circuit from text format.

```javascript
const circuitText = `$ 1 0.000005 5.459815003314424 50 5 43 5e-11
r 208 176 384 176 0 1000
v 208 288 208 176 0 1 40 5 0 0 0.5`;

CircuitJS1.importCircuit(circuitText, false);
```

### exportAsJson(): string
Export circuit in JSON format (version 2.0).

```javascript
const jsonData = CircuitJS1.exportAsJson();
console.log(JSON.parse(jsonData));
```

### importFromJson(json: string): void
Import circuit from JSON format.

```javascript
const json = CircuitJS1.exportAsJson();
// Modify or store...
CircuitJS1.importFromJson(json);
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
    
    // Import a simple RC circuit
    const circuit = `$ 1 0.000005 10.634267539816555 50 5 50 5e-11
r 208 112 208 272 0 10000
c 208 272 352 272 0 0.00001 -2.1287128712871296
v 352 272 352 112 0 1 40 5 0 0 0.5
w 352 112 208 112 0
o 0 64 0 4099 5 0.1 0 2 0 3`;
    
    api.importCircuit(circuit, false);
    
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

## Using with Chrome DevTools

The API can be tested directly in Chrome DevTools console:

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
```

## Notes

- All API methods are synchronous
- The `importFromJson()` clears the existing circuit before importing
- Scope data arrays are circular buffers with `ptr` indicating current position
- Time values are in seconds
- Voltage values are in Volts
- Current values are in Amperes
