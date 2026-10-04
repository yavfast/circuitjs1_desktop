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
import com.lushprojects.circuitjs1.client.ColorSettings;
import com.lushprojects.circuitjs1.client.MenuManager;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.Scope;
import com.lushprojects.circuitjs1.client.ScopeManager;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.ScopeElm;
import com.lushprojects.circuitjs1.client.io.CircuitExporter;
import com.lushprojects.circuitjs1.client.io.CircuitFormat;
import com.lushprojects.circuitjs1.client.io.ModelDependencies;
import com.lushprojects.circuitjs1.client.io.ModelSpecCodec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exports circuit in JSON format (version 2.2).
 * 
 * JSON structure:
 * {
 *   "schema": { "format": "circuitjs", "version": "2.2" },
 *   "simulation": { ... simulation parameters ... },
 *   "elements": { ... element definitions ... },
 *   "scopes": [ ... scope configurations ... ],
 *   "adjustables": [ ... sliders ... ],
 *   "models": [ ... model definitions the circuit uses (only when non-empty) ... ]
 * }
 */
public class JsonCircuitExporter implements CircuitExporter {

    private final JsonCircuitFormat format;
    private Map<CircuitElm, String> elementIds;
    private Set<String> usedIds;
    private boolean includeState;

    public JsonCircuitExporter(JsonCircuitFormat format) {
        this.format = format;
        this.includeState = false;
    }

    @Override
    public String export(CircuitDocument document) {
        return export(document, false);
    }

    @Override
    public String export(CircuitDocument document, boolean includeState) {
        this.includeState = includeState;
        elementIds = new HashMap<>();
        usedIds = new HashSet<>();

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

        // 7. Models the circuit uses (2.2; only when there is one)
        JSONArray models = buildModels(document.simulator.elmList);
        if (models.size() > 0) {
            root.put("models", models);
        }

        return formatJson(root.toString());
    }

    @Override
    public String exportSelection(CircuitDocument document, List<CircuitElm> selection) {
        if (selection == null || selection.isEmpty()) {
            return "{}";
        }

        elementIds = new HashMap<>();
        usedIds = new HashSet<>();

        JSONObject root = new JSONObject();

        // Schema
        root.put("schema", buildSchema());

        // Only selected elements; every ID (and pin location) is collected first, as buildElements
        // does, so a Scope element listed before its target still names it
        pinsByLocation = new HashMap<>();
        for (CircuitElm elm : selection) {
            collectPins(elm, generateElementId(elm));
        }
        JSONObject elements = new JSONObject();
        for (CircuitElm elm : selection) {
            elements.put(elementIds.get(elm), buildElement(elm));
        }
        root.put("elements", elements);

        // Models the selection uses (2.2), so a paste of it defines them
        JSONArray models = buildModels(selection);
        if (models.size() > 0) {
            root.put("models", models);
        }

        return formatJson(root.toString());
    }

    /**
     * The {@code simulation} object of a JSON v2 export of {@code document} (time steps, display
     * options, bars, hint), read from the bound session widgets as {@link #export} does. Used by
     * the agent circuit view ([SP_AGA_02_05] {@code simulation}).
     */
    public JSONObject exportSimulation(CircuitDocument document) {
        return buildSimulation(document);
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
        sim.put("voltage_range", new JSONString(formatWithUnit(ColorSettings.get().getVoltageRange(), "V")));

        // Speed settings
        sim.put("simulation_speed", new JSONNumber(cirSim.speedBar.getValue()));
        sim.put("current_speed", new JSONNumber(cirSim.currentBar.getValue()));
        sim.put("power_brightness", new JSONNumber(cirSim.powerBar.getValue()));

        // Auto time step: written also when off, because the importer keeps the target
        // document's own setting when the key is absent (SP_MCP_05_01 circuit round trip)
        sim.put("auto_time_step", JSONBoolean.getInstance(simulator.adjustTimeStep));

        // Circuit hint (text format 'h' line); items are element indices, as in the text format
        CircuitRenderer renderer = document.getRenderer();
        if (renderer.getHintType() != -1) {
            JSONObject hint = new JSONObject();
            hint.put("type", new JSONNumber(renderer.getHintType()));
            hint.put("item1", new JSONNumber(renderer.getHintItem1()));
            hint.put("item2", new JSONNumber(renderer.getHintItem2()));
            sim.put("hint", hint);
        }

        return sim;
    }

    /**
     * Helper class to store pin info for connection building.
     */
    private static class PinInfo {
        String elementId;
        CircuitElm.Pin pin;
        int x, y;

        PinInfo(String elementId, CircuitElm.Pin pin, int x, int y) {
            this.elementId = elementId;
            this.pin = pin;
            this.x = x;
            this.y = y;
        }

        String getReference() {
            return elementId + "." + pin.getName();
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
        CircuitElm.Pin[] pins = elm.getPins();
        for (CircuitElm.Pin pin : pins) {
            Point pos = pin.getPosition();
            if (pos != null) {
                String key = pos.x + "," + pos.y;
                pinsByLocation.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new PinInfo(elementId, pin, pos.x, pos.y));
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
        if (elm instanceof ScopeElm && props.containsKey("scope")) {
            // element references as this export keys them (a repeated ID is suffixed here)
            props.put("scope", ((ScopeElm) elm).jsonScope(elementIds::get));
        }
        if (!props.isEmpty()) {
            JSONObject propsObj = new JSONObject();
            for (Map.Entry<String, Object> entry : props.entrySet()) {
                propsObj.put(entry.getKey(), toJsonValue(entry.getValue()));
            }
            element.put("properties", propsObj);
        }

        // Pins with connections
        CircuitElm.Pin[] pinArr = elm.getPins();
        JSONObject pins = new JSONObject();
        
        if (pinArr.length > 0) {
            for (CircuitElm.Pin pinObj : pinArr) {
                Point pos = pinObj.getPosition();
                if (pos == null) {
                    continue;
                }

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
                        if (!other.elementId.equals(elementId) || !other.pin.getName().equals(pinObj.getName())) {
                            connections.set(idx++, new JSONString(other.getReference()));
                        }
                    }
                    if (connections.size() > 0) {
                        pin.put("connected_to", connections);
                    }
                }

                pins.put(pinObj.getName(), pin);
            }
        }
        
        // Add _startpoint if element needs it (delegates to element's getJsonStartPoint)
        // This is outside postCount check to support graphic elements (Text, Box, Line)
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
        
        // Only add pins section if there's something in it
        if (pins.size() > 0) {
            element.put("pins", pins);
        }

        // Flags (internal, for reimport)
        // Always written: an absent _flags means "keep the constructor's defaults" on import,
        // which differ from 0 for several elements (JFET/MOSFET body diode, SCR, pot, 555).
        int flags = elm.getJsonFlags();
        element.put("_flags", new JSONNumber(flags));

        // State (simulation state - voltages, currents, internal state)
        if (includeState) {
            java.util.Map<String, Object> state = elm.getJsonState();
            if (state != null && !state.isEmpty()) {
                JSONObject stateObj = new JSONObject();
                for (java.util.Map.Entry<String, Object> entry : state.entrySet()) {
                    stateObj.put(entry.getKey(), toJsonValue(entry.getValue()));
                }
                element.put("state", stateObj);
            }
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
                // one form for docked and in-circuit scopes (JsonScopeCodec)
                scopes.set(scopeIdx++, toJsonValue(JsonScopeCodec.toMap(scope, scope.position, elementIds::get)));
            }
        }

        return scopes;
    }

    /**
     * [SP_AGA_03_12] The {@code models} section: the circuit's models ([SP_AGA_03_11]
     * "Dependencies": the non-built-in models its elements use, through subcircuit models too),
     * dependencies first, otherwise in order of first use by element ID (as {@code getCircuit}
     * lists them), each a ModelSpec or a ModelText by the Form rule of [SP_AGA_02_05], no cap.
     */
    private JSONArray buildModels(List<CircuitElm> elms) {
        List<CircuitElm> ordered = new ArrayList<>(elms);
        ModelDependencies.sortById(ordered);
        JSONArray models = new JSONArray();
        for (ModelDependencies.Ref r : ModelDependencies.circuitModels(ordered)) {
            JSONObject m = ModelSpecCodec.encode(r.kind, r.name);
            if (m != null) {
                models.set(models.size(), m);
            }
        }
        return models;
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
        // Index in the exported array (entries with no element are skipped, so it can differ
        // from the list index); shared_slider refers to it.
        java.util.Map<Adjustable, Integer> exportedIndex = new java.util.HashMap<>();
        for (Adjustable a : adjList) {
            if (a != null && a.getElm() != null) {
                exportedIndex.put(a, exportedIndex.size());
            }
        }
        
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
                    Integer shared = exportedIndex.get(adj.sharedSlider);
                    int sharedIdx = shared == null ? -1 : shared;
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
     * [SP_AGA_03_02] "One scheme": element keys are the document's registry IDs
     * ({@link CircuitElm#getElementId()}), so an exported key names the same element as the
     * scripting global and the Agent API. The registry keeps IDs unique within a document; a
     * repeated ID (a defect) is disambiguated here so that no element is dropped from the export.
     */
    private String generateElementId(CircuitElm elm) {
        if (elementIds.containsKey(elm)) {
            return elementIds.get(elm);
        }
        String id = elm.getElementId();
        if (usedIds.contains(id)) {
            String base = id;
            int k = 2;
            while (usedIds.contains(base + "_" + k)) {
                k++;
            }
            id = base + "_" + k;
            CirSim.console("[WARN] JSON export: repeated element ID " + base + " exported as " + id);
        }
        usedIds.add(id);
        elementIds.put(elm, id);
        return id;
    }

    @SuppressWarnings("unchecked")
    private JSONValue toJsonValue(Object value) {
        if (value == null) {
            return JSONNull.getInstance();
        } else if (value instanceof String) {
            return new JSONString((String) value);
        } else if (value instanceof Number) {
            return new JSONNumber(((Number) value).doubleValue());
        } else if (value instanceof Boolean) {
            return JSONBoolean.getInstance((Boolean) value);
        } else if (value instanceof java.util.Map) {
            // Handle nested Map (for state pins)
            JSONObject obj = new JSONObject();
            java.util.Map<String, Object> map = (java.util.Map<String, Object>) value;
            for (java.util.Map.Entry<String, Object> entry : map.entrySet()) {
                obj.put(entry.getKey(), toJsonValue(entry.getValue()));
            }
            return obj;
        } else if (value instanceof java.util.List) {
            // Lists (sub-element states, delay buffers, tap offsets) are written as JSON arrays;
            // the importer reads JSON arrays back as java.util.List.
            JSONArray arr = new JSONArray();
            java.util.List<Object> list = (java.util.List<Object>) value;
            for (int i = 0; i < list.size(); i++) {
                arr.set(i, toJsonValue(list.get(i)));
            }
            return arr;
        } else if (value instanceof Object[]) {
            JSONArray arr = new JSONArray();
            Object[] items = (Object[]) value;
            for (int i = 0; i < items.length; i++) {
                arr.set(i, toJsonValue(items[i]));
            }
            return arr;
        } else if (value instanceof double[]) {
            JSONArray arr = new JSONArray();
            double[] items = (double[]) value;
            for (int i = 0; i < items.length; i++) {
                arr.set(i, new JSONNumber(items[i]));
            }
            return arr;
        } else {
            return new JSONString(value.toString());
        }
    }

    private String formatWithUnit(double value, String unit) {
        // One serializer for all JSON values (simulation settings and element properties).
        return CircuitElm.getJsonUnitText(value, unit);
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
