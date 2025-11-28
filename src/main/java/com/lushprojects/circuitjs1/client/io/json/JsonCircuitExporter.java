/*
    Copyright (C) Paul Falstad and Iain Sharp

    This file is part of CircuitJS1.

    CircuitJS1 is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 2 of the License, or
    (at your option) any later version.

    CircuitJS1 is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with CircuitJS1.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.lushprojects.circuitjs1.client.io.json;

import com.google.gwt.json.client.*;
import com.lushprojects.circuitjs1.client.Adjustable;
import com.lushprojects.circuitjs1.client.AdjustableManager;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.CircuitRenderer;
import com.lushprojects.circuitjs1.client.CircuitSimulator;
import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.MenuManager;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.Scope;
import com.lushprojects.circuitjs1.client.ScopeManager;
import com.lushprojects.circuitjs1.client.ScopePlot;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.io.CircuitExporter;
import com.lushprojects.circuitjs1.client.io.CircuitFormat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports circuit in JSON format (version 2.0).
 * 
 * JSON structure:
 * {
 *   "schema": { "format": "circuitjs", "version": "2.0" },
 *   "simulation": { ... simulation parameters ... },
 *   "elements": { ... element definitions ... },
 *   "scopes": [ ... scope configurations ... ]
 * }
 */
public class JsonCircuitExporter implements CircuitExporter {

    private final JsonCircuitFormat format;
    private int elementCounter;
    private Map<CircuitElm, String> elementIds;

    public JsonCircuitExporter(JsonCircuitFormat format) {
        this.format = format;
    }

    @Override
    public String export(CircuitDocument document) {
        elementCounter = 0;
        elementIds = new HashMap<>();

        JSONObject root = new JSONObject();

        // 1. Schema section
        root.put("schema", buildSchema());

        // 2. Simulation parameters
        root.put("simulation", buildSimulation(document));

        // 3. Elements (this also populates pinsByLocation)
        root.put("elements", buildElements(document));

        // 4. Nodes (connection points where 3+ pins meet)
        JSONObject nodes = buildNodes();
        if (nodes.size() > 0) {
            root.put("nodes", nodes);
        }

        // 5. Scopes
        JSONArray scopes = buildScopes(document);
        if (scopes.size() > 0) {
            root.put("scopes", scopes);
        }

        // 6. Adjustables (sliders)
        JSONArray adjustables = buildAdjustables(document);
        if (adjustables.size() > 0) {
            root.put("adjustables", adjustables);
        }

        return formatJson(root.toString());
    }

    @Override
    public String exportSelection(CircuitDocument document, List<CircuitElm> selection) {
        if (selection == null || selection.isEmpty()) {
            return "{}";
        }

        elementCounter = 0;
        elementIds = new HashMap<>();

        JSONObject root = new JSONObject();

        // Schema
        root.put("schema", buildSchema());

        // Only selected elements
        JSONObject elements = new JSONObject();
        for (CircuitElm elm : selection) {
            String id = generateElementId(elm);
            elements.put(id, buildElement(elm));
        }
        root.put("elements", elements);

        return formatJson(root.toString());
    }

    private JSONObject buildSchema() {
        JSONObject schema = new JSONObject();
        schema.put("format", new JSONString("circuitjs"));
        schema.put("version", new JSONString(JsonCircuitFormat.FORMAT_VERSION));
        return schema;
    }

    private JSONObject buildSimulation(CircuitDocument document) {
        CirSim cirSim = document.getCirSim();
        CircuitSimulator simulator = document.simulator;
        MenuManager menuManager = cirSim.menuManager;

        JSONObject sim = new JSONObject();

        // Time step with unit
        sim.put("time_step", new JSONString(formatWithUnit(simulator.maxTimeStep, "s")));
        sim.put("min_time_step", new JSONString(formatWithUnit(simulator.minTimeStep, "s")));

        // Display options
        JSONObject display = new JSONObject();
        display.put("show_dots", JSONBoolean.getInstance(menuManager.dotsCheckItem.getState()));
        display.put("show_voltage", JSONBoolean.getInstance(menuManager.voltsCheckItem.getState()));
        display.put("show_power", JSONBoolean.getInstance(menuManager.powerCheckItem.getState()));
        display.put("show_values", JSONBoolean.getInstance(menuManager.showValuesCheckItem.getState()));
        display.put("small_grid", JSONBoolean.getInstance(menuManager.smallGridCheckItem.getState()));
        sim.put("display", display);

        // Voltage range
        sim.put("voltage_range", new JSONString(formatWithUnit(CircuitElm.voltageRange, "V")));

        // Speed settings
        sim.put("current_speed", new JSONNumber(cirSim.currentBar.getValue()));
        sim.put("power_brightness", new JSONNumber(cirSim.powerBar.getValue()));

        // Auto time step
        if (simulator.adjustTimeStep) {
            sim.put("auto_time_step", JSONBoolean.getInstance(true));
        }

        return sim;
    }

    /**
     * Helper class to store pin info for connection building.
     */
    private static class PinInfo {
        String elementId;
        String pinName;
        int x, y;

        PinInfo(String elementId, String pinName, int x, int y) {
            this.elementId = elementId;
            this.pinName = pinName;
            this.x = x;
            this.y = y;
        }

        String getReference() {
            return elementId + "." + pinName;
        }
    }

    // Map from coordinate key to list of pins at that location
    private Map<String, List<PinInfo>> pinsByLocation;

    private JSONObject buildElements(CircuitDocument document) {
        CircuitSimulator simulator = document.simulator;

        // First pass: collect all pins and their locations
        pinsByLocation = new HashMap<>();
        for (int i = 0; i < simulator.elmList.size(); i++) {
            CircuitElm elm = simulator.elmList.get(i);
            String id = generateElementId(elm);
            collectPins(elm, id);
        }

        // Second pass: build elements with connection info
        JSONObject elements = new JSONObject();
        for (int i = 0; i < simulator.elmList.size(); i++) {
            CircuitElm elm = simulator.elmList.get(i);
            String id = elementIds.get(elm);
            elements.put(id, buildElement(elm, id));
        }

        return elements;
    }

    private void collectPins(CircuitElm elm, String elementId) {
        String[] pinNames = elm.getJsonPinNames();
        int postCount = elm.getPostCount();
        for (int i = 0; i < postCount && i < pinNames.length; i++) {
            Point pos = elm.getJsonPinPosition(i);
            if (pos != null) {
                String key = pos.x + "," + pos.y;
                pinsByLocation.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new PinInfo(elementId, pinNames[i], pos.x, pos.y));
            }
        }
    }

    private JSONObject buildElement(CircuitElm elm, String elementId) {
        JSONObject element = new JSONObject();

        // Type
        element.put("type", new JSONString(elm.getJsonTypeName()));

        // Description (if present)
        String desc = elm.getDescription();
        if (desc != null && !desc.isEmpty()) {
            element.put("description", new JSONString(desc));
        }

        // Bounds
        Map<String, Integer> bounds = elm.getJsonBounds();
        JSONObject boundsObj = new JSONObject();
        for (Map.Entry<String, Integer> entry : bounds.entrySet()) {
            boundsObj.put(entry.getKey(), new JSONNumber(entry.getValue()));
        }
        element.put("bounds", boundsObj);

        // Properties
        Map<String, Object> props = elm.getJsonProperties();
        if (!props.isEmpty()) {
            JSONObject propsObj = new JSONObject();
            for (Map.Entry<String, Object> entry : props.entrySet()) {
                propsObj.put(entry.getKey(), toJsonValue(entry.getValue()));
            }
            element.put("properties", propsObj);
        }

        // Pins with connections
        String[] pinNames = elm.getJsonPinNames();
        int postCount = elm.getPostCount();
        if (postCount > 0) {
            JSONObject pins = new JSONObject();
            for (int i = 0; i < postCount && i < pinNames.length; i++) {
                Point pos = elm.getJsonPinPosition(i);
                if (pos != null) {
                    JSONObject pin = new JSONObject();
                    JSONObject position = new JSONObject();
                    position.put("x", new JSONNumber(pos.x));
                    position.put("y", new JSONNumber(pos.y));
                    pin.put("position", position);

                    // Find connections (other pins at same location)
                    String key = pos.x + "," + pos.y;
                    List<PinInfo> pinsAtLocation = pinsByLocation.get(key);
                    if (pinsAtLocation != null && pinsAtLocation.size() > 1) {
                        JSONArray connections = new JSONArray();
                        int idx = 0;
                        for (PinInfo other : pinsAtLocation) {
                            // Skip self
                            if (!other.elementId.equals(elementId) || !other.pinName.equals(pinNames[i])) {
                                connections.set(idx++, new JSONString(other.getReference()));
                            }
                        }
                        if (connections.size() > 0) {
                            pin.put("connected_to", connections);
                        }
                    }

                    pins.put(pinNames[i], pin);
                }
            }
            
            // Add _startpoint if element needs it (delegates to element's getJsonStartPoint)
            Point startPoint = elm.getJsonStartPoint();
            if (startPoint != null) {
                JSONObject startpointPin = new JSONObject();
                JSONObject startpointPos = new JSONObject();
                startpointPos.put("x", new JSONNumber(startPoint.x));
                startpointPos.put("y", new JSONNumber(startPoint.y));
                startpointPin.put("position", startpointPos);
                pins.put("_startpoint", startpointPin);
            }
            
            // Add _endpoint if element needs it (delegates to element's getJsonEndPoint)
            Point endPoint = elm.getJsonEndPoint();
            if (endPoint != null) {
                JSONObject endpointPin = new JSONObject();
                JSONObject endpointPos = new JSONObject();
                endpointPos.put("x", new JSONNumber(endPoint.x));
                endpointPos.put("y", new JSONNumber(endPoint.y));
                endpointPin.put("position", endpointPos);
                pins.put("_endpoint", endpointPin);
            }
            
            element.put("pins", pins);
        }

        // Flags (internal, for reimport)
        int flags = elm.getJsonFlags();
        if (flags != 0) {
            element.put("_flags", new JSONNumber(flags));
        }

        return element;
    }

    // Keep old method for backward compatibility
    private JSONObject buildElement(CircuitElm elm) {
        return buildElement(elm, elementIds.get(elm));
    }

    /**
     * Build nodes section - connection points where 3+ pins meet.
     * These are junction points in the circuit.
     */
    private JSONObject buildNodes() {
        JSONObject nodes = new JSONObject();
        int nodeCounter = 0;

        for (Map.Entry<String, List<PinInfo>> entry : pinsByLocation.entrySet()) {
            List<PinInfo> pinsAtLocation = entry.getValue();
            // Only create a node if 3+ pins meet at this location
            if (pinsAtLocation.size() >= 3) {
                nodeCounter++;
                String nodeId = "N" + nodeCounter;

                JSONObject node = new JSONObject();

                // Position
                PinInfo firstPin = pinsAtLocation.get(0);
                JSONObject position = new JSONObject();
                position.put("x", new JSONNumber(firstPin.x));
                position.put("y", new JSONNumber(firstPin.y));
                node.put("position", position);

                // Connections - all pins that meet at this node
                JSONArray connections = new JSONArray();
                int idx = 0;
                for (PinInfo pin : pinsAtLocation) {
                    connections.set(idx++, new JSONString(pin.getReference()));
                }
                node.put("connections", connections);

                nodes.put(nodeId, node);
            }
        }

        return nodes;
    }

    private JSONArray buildScopes(CircuitDocument document) {
        JSONArray scopes = new JSONArray();
        ScopeManager scopeManager = document.scopeManager;
        int scopeIdx = 0;

        for (int i = 0; i < scopeManager.getScopeCount(); i++) {
            Scope scope = scopeManager.getScope(i);
            if (scope != null && scope.getElm() != null) {
                JSONObject scopeObj = new JSONObject();
                
                // Reference to main element
                String elmId = elementIds.get(scope.getElm());
                if (elmId != null) {
                    scopeObj.put("element", new JSONString(elmId));
                }
                
                // Position in scope stack
                scopeObj.put("position", new JSONNumber(scope.position));
                
                // Time scale (speed)
                scopeObj.put("speed", new JSONNumber(scope.speed));
                
                // Display options
                JSONObject display = new JSONObject();
                display.put("show_voltage", JSONBoolean.getInstance(scope.showV));
                display.put("show_current", JSONBoolean.getInstance(scope.showI));
                display.put("show_scale", JSONBoolean.getInstance(scope.showScale));
                display.put("show_max", JSONBoolean.getInstance(scope.showMax));
                display.put("show_min", JSONBoolean.getInstance(scope.showMin));
                display.put("show_frequency", JSONBoolean.getInstance(scope.showFreq));
                display.put("show_fft", JSONBoolean.getInstance(scope.showFFT));
                display.put("show_rms", JSONBoolean.getInstance(scope.showRMS));
                display.put("show_average", JSONBoolean.getInstance(scope.showAverage));
                display.put("show_duty_cycle", JSONBoolean.getInstance(scope.showDutyCycle));
                display.put("show_negative", JSONBoolean.getInstance(scope.showNegative));
                display.put("show_element_info", JSONBoolean.getInstance(scope.showElmInfo));
                scopeObj.put("display", display);
                
                // Plot modes
                JSONObject plotMode = new JSONObject();
                plotMode.put("plot_2d", JSONBoolean.getInstance(scope.plot2d));
                plotMode.put("plot_xy", JSONBoolean.getInstance(scope.plotXY));
                plotMode.put("max_scale", JSONBoolean.getInstance(scope.maxScale));
                plotMode.put("log_spectrum", JSONBoolean.getInstance(scope.logSpectrum));
                scopeObj.put("plot_mode", plotMode);
                
                // Manual scale settings
                if (scope.isManualScale()) {
                    JSONObject manualScale = new JSONObject();
                    manualScale.put("enabled", JSONBoolean.getInstance(true));
                    manualScale.put("divisions", new JSONNumber(scope.manDivisions));
                    scopeObj.put("manual_scale", manualScale);
                }
                
                // Plots (individual traces)
                JSONArray plotsArray = new JSONArray();
                java.util.Vector<ScopePlot> plots = scope.plots;
                if (plots != null) {
                    int plotIdx = 0;
                    for (int j = 0; j < plots.size(); j++) {
                        ScopePlot plot = plots.get(j);
                        if (plot != null && plot.getElm() != null) {
                            JSONObject plotObj = new JSONObject();
                            
                            // Element reference for this plot
                            String plotElmId = elementIds.get(plot.getElm());
                            if (plotElmId != null) {
                                plotObj.put("element", new JSONString(plotElmId));
                            }
                            
                            // Units type (0=V, 1=A, 2=W, 3=Ohm)
                            plotObj.put("units", new JSONString(getUnitsName(plot.units)));
                            
                            // Color
                            if (plot.color != null) {
                                plotObj.put("color", new JSONString(plot.color));
                            }
                            
                            // Manual scale for this plot
                            plotObj.put("scale", new JSONNumber(plot.manScale));
                            plotObj.put("v_position", new JSONNumber(plot.manVPosition));
                            
                            // AC coupling
                            if (plot.isAcCoupled()) {
                                plotObj.put("ac_coupled", JSONBoolean.getInstance(true));
                            }
                            
                            plotsArray.set(plotIdx++, plotObj);
                        }
                    }
                }
                if (plotsArray.size() > 0) {
                    scopeObj.put("plots", plotsArray);
                }
                
                scopes.set(scopeIdx++, scopeObj);
            }
        }

        return scopes;
    }
    
    /**
     * Build adjustables (sliders) section.
     */
    private JSONArray buildAdjustables(CircuitDocument document) {
        JSONArray adjustables = new JSONArray();
        AdjustableManager adjustableManager = document.adjustableManager;
        
        if (adjustableManager == null) {
            return adjustables;
        }
        
        java.util.ArrayList<Adjustable> adjList = adjustableManager.getAdjustables();
        int idx = 0;
        
        for (int i = 0; i < adjList.size(); i++) {
            Adjustable adj = adjList.get(i);
            if (adj != null && adj.getElm() != null) {
                JSONObject adjObj = new JSONObject();
                
                // Element reference
                String elmId = elementIds.get(adj.getElm());
                if (elmId != null) {
                    adjObj.put("element", new JSONString(elmId));
                }
                
                // Edit item index (which property this slider controls)
                adjObj.put("edit_item", new JSONNumber(adj.getEditItem()));
                
                // Slider label
                if (adj.sliderText != null && !adj.sliderText.isEmpty()) {
                    adjObj.put("label", new JSONString(adj.sliderText));
                }
                
                // Value range
                adjObj.put("min_value", new JSONNumber(adj.minValue));
                adjObj.put("max_value", new JSONNumber(adj.maxValue));
                
                // Current value
                adjObj.put("current_value", new JSONNumber(adj.getSliderValue()));
                
                // Shared slider reference
                if (adj.sharedSlider != null) {
                    int sharedIdx = adjList.indexOf(adj.sharedSlider);
                    if (sharedIdx >= 0) {
                        adjObj.put("shared_slider", new JSONNumber(sharedIdx));
                    }
                }
                
                adjustables.set(idx++, adjObj);
            }
        }
        
        return adjustables;
    }
    
    /**
     * Convert units constant to string name.
     */
    private String getUnitsName(int units) {
        switch (units) {
            case Scope.UNITS_V: return "V";
            case Scope.UNITS_A: return "A";
            case Scope.UNITS_W: return "W";
            case Scope.UNITS_OHMS: return "Ω";
            default: return "V";
        }
    }

    private String generateElementId(CircuitElm elm) {
        // Check if already has ID
        if (elementIds.containsKey(elm)) {
            return elementIds.get(elm);
        }

        // Generate ID based on type and counter
        String typeName = elm.getJsonTypeName();
        // Shorten common names
        String prefix;
        switch (typeName) {
            case "Resistor": prefix = "R"; break;
            case "Capacitor": prefix = "C"; break;
            case "Inductor": prefix = "L"; break;
            case "TransistorNPN":
            case "TransistorPNP": prefix = "Q"; break;
            case "Diode": prefix = "D"; break;
            case "LED": prefix = "LED"; break;
            case "Wire": prefix = "W"; break;
            case "Ground": prefix = "GND"; break;
            case "VoltageSource":
            case "DCVoltage": prefix = "V"; break;
            case "CurrentSource": prefix = "I"; break;
            case "OpAmp": prefix = "U"; break;
            default: prefix = typeName.substring(0, Math.min(3, typeName.length())); break;
        }

        elementCounter++;
        String id = prefix + elementCounter;
        elementIds.put(elm, id);
        return id;
    }

    private JSONValue toJsonValue(Object value) {
        if (value == null) {
            return JSONNull.getInstance();
        } else if (value instanceof String) {
            return new JSONString((String) value);
        } else if (value instanceof Number) {
            return new JSONNumber(((Number) value).doubleValue());
        } else if (value instanceof Boolean) {
            return JSONBoolean.getInstance((Boolean) value);
        } else {
            return new JSONString(value.toString());
        }
    }

    private String formatWithUnit(double value, String unit) {
        // Use SI prefixes
        String[] prefixes = {"f", "p", "n", "u", "m", "", "k", "M", "G", "T"};
        double[] multipliers = {1e-15, 1e-12, 1e-9, 1e-6, 1e-3, 1, 1e3, 1e6, 1e9, 1e12};

        int idx = 5; // Start with no prefix
        double absValue = Math.abs(value);

        if (absValue != 0) {
            for (int i = 0; i < multipliers.length; i++) {
                if (absValue >= multipliers[i] * 0.999 && absValue < multipliers[i] * 1000) {
                    idx = i;
                    break;
                }
            }
        }

        double scaledValue = value / multipliers[idx];
        String prefix = prefixes[idx];

        // Format number
        if (scaledValue == Math.floor(scaledValue) && scaledValue < 1e6) {
            return String.valueOf((long) scaledValue) + " " + prefix + unit;
        } else {
            // Use up to 4 decimal places
            String formatted = String.valueOf(scaledValue);
            if (formatted.contains(".") && formatted.length() > formatted.indexOf('.') + 5) {
                formatted = formatted.substring(0, formatted.indexOf('.') + 5);
            }
            // Remove trailing zeros
            while (formatted.endsWith("0") && formatted.contains(".")) {
                formatted = formatted.substring(0, formatted.length() - 1);
            }
            if (formatted.endsWith(".")) {
                formatted = formatted.substring(0, formatted.length() - 1);
            }
            return formatted + " " + prefix + unit;
        }
    }

    /**
     * Compact JSON formatter - simple objects on one line, 
     * complex structures with line breaks.
     */
    private String formatJson(String json) {
        StringBuilder sb = new StringBuilder();
        int indent = 0;
        boolean inString = false;
        char prevChar = 0;
        
        // Track nesting for compact output of simple objects
        int[] nestingStack = new int[100];
        int nestingDepth = 0;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (c == '"' && prevChar != '\\') {
                inString = !inString;
            }

            if (!inString) {
                if (c == '{' || c == '[') {
                    // Check if this is a simple object (no nested objects/arrays)
                    boolean isSimple = isSimpleBlock(json, i);
                    
                    if (isSimple) {
                        nestingStack[nestingDepth++] = 1; // Mark as compact
                        sb.append(c);
                    } else {
                        nestingStack[nestingDepth++] = 0; // Mark as expanded
                        sb.append(c);
                        sb.append('\n');
                        indent++;
                        appendIndent(sb, indent);
                    }
                } else if (c == '}' || c == ']') {
                    nestingDepth--;
                    boolean wasCompact = nestingDepth >= 0 && nestingStack[nestingDepth] == 1;
                    
                    if (wasCompact) {
                        sb.append(c);
                    } else {
                        sb.append('\n');
                        indent--;
                        appendIndent(sb, indent);
                        sb.append(c);
                    }
                } else if (c == ',') {
                    boolean isCompact = nestingDepth > 0 && nestingStack[nestingDepth - 1] == 1;
                    sb.append(c);
                    if (!isCompact) {
                        sb.append('\n');
                        appendIndent(sb, indent);
                    } else {
                        sb.append(' ');
                    }
                } else if (c == ':') {
                    sb.append(c);
                    sb.append(' ');
                } else {
                    sb.append(c);
                }
            } else {
                sb.append(c);
            }

            prevChar = c;
        }

        return sb.toString();
    }

    /**
     * Check if block starting at position is simple (no nested objects/arrays).
     */
    private boolean isSimpleBlock(String json, int start) {
        char openChar = json.charAt(start);
        char closeChar = (openChar == '{') ? '}' : ']';
        
        int depth = 1;
        boolean inStr = false;
        
        for (int i = start + 1; i < json.length() && depth > 0; i++) {
            char c = json.charAt(i);
            char prev = (i > 0) ? json.charAt(i - 1) : 0;
            
            if (c == '"' && prev != '\\') {
                inStr = !inStr;
            }
            
            if (!inStr) {
                if (c == '{' || c == '[') {
                    // Has nested structure - not simple
                    return false;
                } else if (c == closeChar) {
                    depth--;
                }
            }
        }
        
        // Simple if no nested structures
        return true;
    }

    private void appendIndent(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) {
            sb.append("  ");
        }
    }

    @Override
    public CircuitFormat getFormat() {
        return format;
    }
}
