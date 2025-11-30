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
import com.lushprojects.circuitjs1.client.*;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.io.CircuitFormat;
import com.lushprojects.circuitjs1.client.io.CircuitImporter;

import java.util.HashMap;
import java.util.Map;

/**
 * Imports circuit from JSON format (version 2.0).
 * 
 * This importer handles the new JSON format with explicit
 * element properties and pin connections.
 */
public class JsonCircuitImporter implements CircuitImporter {

    private final JsonCircuitFormat format;

    // Map from element ID to created element (for scope/adjustable references)
    private Map<String, CircuitElm> importedElements;

    public JsonCircuitImporter(JsonCircuitFormat format) {
        this.format = format;
    }

    @Override
    public void importCircuit(String data, CircuitDocument document, int flags) {
        if (data == null || data.trim().isEmpty()) {
            CirSim.console("JSON import: empty data");
            return;
        }

        try {
            JSONValue parsed = JSONParser.parseStrict(data);
            if (parsed == null || parsed.isObject() == null) {
                CirSim.console("JSON import: invalid JSON structure");
                return;
            }

            JSONObject root = parsed.isObject();

            // Validate schema
            if (!validateSchema(root)) {
                CirSim.console("JSON import: invalid or unsupported schema");
                return;
            }

            // Reset circuit state unless retaining (same as TextCircuitImporter)
            if ((flags & CircuitConst.RC_RETAIN) == 0) {
                resetCircuitState(document);
            }

            importedElements = new HashMap<>();

            // 1. Parse simulation parameters
            parseSimulation(root, document);

            // 2. Parse elements
            int elementCount = parseElements(root, document);

            // 3. Create auto-wires from connected_to references
            int wireCount = createAutoWires(root, document);

            // 4. Parse scopes
            int scopeCount = parseScopes(root, document);

            // 5. Parse adjustables
            int adjustableCount = parseAdjustables(root, document);

            // 6. Create UI sliders for adjustables
            document.adjustableManager.createSliders();

            // 7. Notify document that import is complete
            document.getCirSim().needAnalyze();

            CirSim.console("JSON import: " + elementCount + " elements, " + wireCount + " auto-wires, " +
                    scopeCount + " scopes, " + adjustableCount + " adjustables");

        } catch (Exception e) {
            CirSim.console("JSON import error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Reset circuit state before import (similar to TextCircuitImporter).
     */
    private void resetCircuitState(CircuitDocument document) {
        CirSim cirSim = document.getCirSim();
        CircuitSimulator simulator = document.simulator;
        CircuitEditor circuitEditor = document.circuitEditor;
        ScopeManager scopeManager = document.scopeManager;

        // Clear existing elements
        circuitEditor.clearMouseElm();
        for (int i = 0; i < simulator.elmList.size(); i++) {
            CircuitElm element = simulator.elmList.get(i);
            element.delete();
        }

        // Reset simulation parameters
        simulator.t = simulator.timeStepAccum = 0;
        simulator.elmList.clear();
        document.adjustableManager.reset();
        simulator.lastIterTime = 0;

        // Reset scope count
        scopeManager.setScopeCount(0);
    }

    private void parseSimulation(JSONObject root, CircuitDocument document) {
        JSONValue simValue = root.get("simulation");
        if (simValue == null || simValue.isObject() == null) {
            return;
        }

        JSONObject sim = simValue.isObject();
        CircuitSimulator simulator = document.simulator;
        CirSim cirSim = document.getCirSim();
        MenuManager menuManager = cirSim.menuManager;

        // Time step
        JSONValue timeStepValue = sim.get("time_step");
        if (timeStepValue != null) {
            if (timeStepValue.isString() != null) {
                simulator.maxTimeStep = UnitParser.parse(timeStepValue.isString().stringValue());
            } else if (timeStepValue.isNumber() != null) {
                simulator.maxTimeStep = timeStepValue.isNumber().doubleValue();
            }
        }

        JSONValue minTimeStepValue = sim.get("min_time_step");
        if (minTimeStepValue != null) {
            if (minTimeStepValue.isString() != null) {
                simulator.minTimeStep = UnitParser.parse(minTimeStepValue.isString().stringValue());
            } else if (minTimeStepValue.isNumber() != null) {
                simulator.minTimeStep = minTimeStepValue.isNumber().doubleValue();
            }
        }

        // Voltage range
        JSONValue voltageRangeValue = sim.get("voltage_range");
        if (voltageRangeValue != null) {
            if (voltageRangeValue.isString() != null) {
                CircuitElm.voltageRange = UnitParser.parse(voltageRangeValue.isString().stringValue());
            } else if (voltageRangeValue.isNumber() != null) {
                CircuitElm.voltageRange = voltageRangeValue.isNumber().doubleValue();
            }
        }

        // Speed settings
        JSONValue currentSpeedValue = sim.get("current_speed");
        if (currentSpeedValue != null && currentSpeedValue.isNumber() != null && cirSim.currentBar != null) {
            cirSim.currentBar.setValue((int) currentSpeedValue.isNumber().doubleValue());
        }

        JSONValue powerBrightnessValue = sim.get("power_brightness");
        if (powerBrightnessValue != null && powerBrightnessValue.isNumber() != null && cirSim.powerBar != null) {
            cirSim.powerBar.setValue((int) powerBrightnessValue.isNumber().doubleValue());
        }

        // Auto time step
        JSONValue autoTimeStepValue = sim.get("auto_time_step");
        if (autoTimeStepValue != null && autoTimeStepValue.isBoolean() != null) {
            simulator.adjustTimeStep = autoTimeStepValue.isBoolean().booleanValue();
        }

        // Display options
        JSONValue displayValue = sim.get("display");
        if (displayValue != null && displayValue.isObject() != null) {
            JSONObject display = displayValue.isObject();

            setCheckItem(menuManager.dotsCheckItem, display, "show_dots");
            setCheckItem(menuManager.voltsCheckItem, display, "show_voltage");
            setCheckItem(menuManager.powerCheckItem, display, "show_power");
            setCheckItem(menuManager.showValuesCheckItem, display, "show_values");
            setCheckItem(menuManager.smallGridCheckItem, display, "small_grid");
        }
    }

    private void setCheckItem(CheckboxMenuItem checkbox, JSONObject obj, String key) {
        JSONValue value = obj.get(key);
        if (value != null && value.isBoolean() != null) {
            checkbox.setState(value.isBoolean().booleanValue());
        }
    }

    private int parseElements(JSONObject root, CircuitDocument document) {
        JSONValue elementsValue = root.get("elements");
        CirSim.console("parseElements: elementsValue=" + (elementsValue != null ? "exists" : "null"));
        if (elementsValue == null || elementsValue.isObject() == null) {
            CirSim.console("parseElements: no elements object found");
            return 0;
        }

        JSONObject elements = elementsValue.isObject();
        int count = 0;
        CirSim.console("parseElements: found " + elements.keySet().size() + " element keys");

        for (String elementId : elements.keySet()) {
            CirSim.console("parseElements: processing element '" + elementId + "'");
            JSONValue elementValue = elements.get(elementId);
            if (elementValue == null || elementValue.isObject() == null) {
                CirSim.console("parseElements: element '" + elementId + "' value is null or not object");
                continue;
            }

            JSONObject elementJson = elementValue.isObject();

            // Get type
            JSONValue typeValue = elementJson.get("type");
            if (typeValue == null || typeValue.isString() == null) {
                CirSim.console("JSON import: element " + elementId + " has no type");
                continue;
            }

            String jsonType = typeValue.isString().stringValue();
            CirSim.console("parseElements: element '" + elementId + "' has type '" + jsonType + "'");

            // Create element using factory with CircuitDocument
            CirSim.console("parseElements: calling CircuitElementFactory.createFromJson for '" + jsonType + "'");
            CircuitElm elm = CircuitElementFactory.createFromJson(jsonType, elementJson, document);
            CirSim.console("parseElements: factory returned " + (elm != null ? elm.getClass().getSimpleName() : "null"));
            if (elm == null) {
                CirSim.console("JSON import: failed to create element " + elementId + " of type " + jsonType);
                continue;
            }

            // Set element ID from JSON (preserve original ID)
            elm.setElementId(elementId);

            // Initialize element with document (important for elements that override setCircuitDocument
            // to initialize internal components like diodes in MosfetElm)
            elm.setCircuitDocument(document);

            // Add to simulator
            document.simulator.elmList.add(elm);

            // Store for reference
            importedElements.put(elementId, elm);
            count++;
        }

        return count;
    }

    /**
     * Creates Wire elements automatically based on connected_to references in pin
     * definitions.
     * This allows netlist-style connections without requiring exact coordinate
     * matching.
     */
    private int createAutoWires(JSONObject root, CircuitDocument document) {
        JSONValue elementsValue = root.get("elements");
        if (elementsValue == null || elementsValue.isObject() == null) {
            return 0;
        }

        JSONObject elements = elementsValue.isObject();
        int wireCount = 0;

        // Track which connections we've already created to avoid duplicates
        java.util.Set<String> createdConnections = new java.util.HashSet<>();

        // Iterate through all elements and their pins
        for (String elementId : elements.keySet()) {
            JSONValue elementValue = elements.get(elementId);
            if (elementValue == null || elementValue.isObject() == null) {
                continue;
            }

            JSONObject elementJson = elementValue.isObject();
            JSONValue pinsValue = elementJson.get("pins");
            if (pinsValue == null || pinsValue.isObject() == null) {
                continue;
            }

            JSONObject pins = pinsValue.isObject();

            // Get the element
            CircuitElm sourceElement = importedElements.get(elementId);
            if (sourceElement == null) {
                continue;
            }

            // Check each pin for connected_to
            for (String pinName : pins.keySet()) {
                JSONValue pinValue = pins.get(pinName);
                if (pinValue == null || pinValue.isObject() == null) {
                    continue;
                }

                JSONObject pin = pinValue.isObject();

                // Look for connected_to reference
                JSONValue connectedToValue = pin.get("connected_to");
                if (connectedToValue == null || connectedToValue.isString() == null) {
                    continue;
                }

                String connectedTo = connectedToValue.isString().stringValue();

                // Parse connected_to reference (format: "elementId.pinName" or "elementId")
                String[] parts = connectedTo.split("\\.");
                String targetElementId = parts[0];
                String targetPinName = parts.length > 1 ? parts[1] : null;

                // Get target element
                CircuitElm targetElement = importedElements.get(targetElementId);
                if (targetElement == null) {
                    CirSim.console("JSON auto-wire: target element not found: " + targetElementId);
                    continue;
                }

                // Get source pin position
                JSONValue sourcePosValue = pin.get("position");
                if (sourcePosValue == null || sourcePosValue.isObject() == null) {
                    continue;
                }
                JSONObject sourcePos = sourcePosValue.isObject();
                JSONValue sourceXVal = sourcePos.get("x");
                JSONValue sourceYVal = sourcePos.get("y");
                if (sourceXVal == null || sourceYVal == null) {
                    continue;
                }
                int sourceX = (int) sourceXVal.isNumber().doubleValue();
                int sourceY = (int) sourceYVal.isNumber().doubleValue();

                // Get target pin position
                int targetX, targetY;
                if (targetPinName != null) {
                    // Specific pin referenced
                    JSONValue targetElementValue = elements.get(targetElementId);
                    if (targetElementValue == null || targetElementValue.isObject() == null) {
                        continue;
                    }
                    JSONObject targetElementJson = targetElementValue.isObject();
                    JSONValue targetPinsValue = targetElementJson.get("pins");
                    if (targetPinsValue == null || targetPinsValue.isObject() == null) {
                        continue;
                    }
                    JSONObject targetPins = targetPinsValue.isObject();
                    JSONValue targetPinValue = targetPins.get(targetPinName);
                    if (targetPinValue == null || targetPinValue.isObject() == null) {
                        CirSim.console("JSON auto-wire: target pin not found: " + connectedTo);
                        continue;
                    }
                    JSONObject targetPin = targetPinValue.isObject();
                    JSONValue targetPosValue = targetPin.get("position");
                    if (targetPosValue == null || targetPosValue.isObject() == null) {
                        continue;
                    }
                    JSONObject targetPos = targetPosValue.isObject();
                    JSONValue targetXVal = targetPos.get("x");
                    JSONValue targetYVal = targetPos.get("y");
                    if (targetXVal == null || targetYVal == null) {
                        continue;
                    }
                    targetX = (int) targetXVal.isNumber().doubleValue();
                    targetY = (int) targetYVal.isNumber().doubleValue();
                } else {
                    // No specific pin - use element's first post position
                    targetX = targetElement.x;
                    targetY = targetElement.y;
                }

                // Create unique connection ID to avoid duplicates
                String connectionId1 = sourceX + "," + sourceY + "-" + targetX + "," + targetY;
                String connectionId2 = targetX + "," + targetY + "-" + sourceX + "," + sourceY;

                if (createdConnections.contains(connectionId1) || createdConnections.contains(connectionId2)) {
                    continue; // Already created this wire
                }

                // Create Wire element
                com.lushprojects.circuitjs1.client.element.WireElm wire = new com.lushprojects.circuitjs1.client.element.WireElm(
                        document, sourceX, sourceY);
                wire.x2 = targetX;
                wire.y2 = targetY;
                wire.setPoints();
                wire.setCircuitDocument(document);

                // Add to simulator
                document.simulator.elmList.add(wire);
                wireCount++;

                // Mark this connection as created
                createdConnections.add(connectionId1);

                CirSim.console("JSON auto-wire: created " + elementId + "." + pinName + " -> " + connectedTo);
            }
        }

        return wireCount;
    }

    private int parseScopes(JSONObject root, CircuitDocument document) {
        JSONValue scopesValue = root.get("scopes");
        if (scopesValue == null || scopesValue.isArray() == null) {
            return 0;
        }

        JSONArray scopes = scopesValue.isArray();
        ScopeManager scopeManager = document.scopeManager;
        CirSim cirSim = document.getCirSim();
        int count = 0;

        for (int i = 0; i < scopes.size(); i++) {
            JSONValue scopeValue = scopes.get(i);
            if (scopeValue == null || scopeValue.isObject() == null) {
                continue;
            }

            JSONObject scopeJson = scopeValue.isObject();

            // Get element reference
            JSONValue elementValue = scopeJson.get("element");
            if (elementValue == null || elementValue.isString() == null) {
                continue;
            }

            String elementId = elementValue.isString().stringValue();
            CircuitElm elm = importedElements.get(elementId);
            if (elm == null) {
                CirSim.console("JSON import: scope references unknown element: " + elementId);
                continue;
            }

            // Create scope
            Scope scope = new Scope(cirSim, document);
            scope.setElm(elm);

            // Position
            JSONValue posValue = scopeJson.get("position");
            if (posValue != null && posValue.isNumber() != null) {
                scope.position = (int) posValue.isNumber().doubleValue();
            }

            // Speed - use setSpeed() to sync with plots
            JSONValue speedValue = scopeJson.get("speed");
            if (speedValue != null && speedValue.isNumber() != null) {
                scope.setSpeed((int) speedValue.isNumber().doubleValue());
            }

            // Display options
            JSONValue displayValue = scopeJson.get("display");
            if (displayValue != null && displayValue.isObject() != null) {
                JSONObject display = displayValue.isObject();
                scope.showV = getBoolean(display, "show_voltage", true);
                scope.showI = getBoolean(display, "show_current", false);
                scope.showScale = getBoolean(display, "show_scale", true);
                scope.showMax = getBoolean(display, "show_max", false);
                scope.showMin = getBoolean(display, "show_min", false);
                scope.showFreq = getBoolean(display, "show_frequency", false);
                scope.showFFT = getBoolean(display, "show_fft", false);
                scope.showRMS = getBoolean(display, "show_rms", false);
                scope.showAverage = getBoolean(display, "show_average", false);
                scope.showDutyCycle = getBoolean(display, "show_duty_cycle", false);
                scope.showNegative = getBoolean(display, "show_negative", false);
                scope.showElmInfo = getBoolean(display, "show_element_info", true);
            }

            // Plot modes
            JSONValue plotModeValue = scopeJson.get("plot_mode");
            if (plotModeValue != null && plotModeValue.isObject() != null) {
                JSONObject plotMode = plotModeValue.isObject();
                scope.plot2d = getBoolean(plotMode, "plot_2d", false);
                scope.plotXY = getBoolean(plotMode, "plot_xy", false);
                scope.maxScale = getBoolean(plotMode, "max_scale", false);
                scope.logSpectrum = getBoolean(plotMode, "log_spectrum", false);
            }

            // Scale settings for different units
            JSONValue scalesValue = scopeJson.get("scales");
            if (scalesValue != null && scalesValue.isObject() != null) {
                JSONObject scales = scalesValue.isObject();
                scope.setScale(Scope.UNITS_V, getDouble(scales, "voltage", 5));
                scope.setScale(Scope.UNITS_A, getDouble(scales, "current", 1));
                scope.setScale(Scope.UNITS_OHMS, getDouble(scales, "ohms", 5));
                scope.setScale(Scope.UNITS_W, getDouble(scales, "watts", 5));
            }

            // Add scope at current index
            scopeManager.setScope(count, scope);
            count++;
        }

        // Update scope count
        scopeManager.setScopeCount(count);
        return count;
    }

    private int parseAdjustables(JSONObject root, CircuitDocument document) {
        JSONValue adjustablesValue = root.get("adjustables");
        if (adjustablesValue == null || adjustablesValue.isArray() == null) {
            return 0;
        }

        JSONArray adjustables = adjustablesValue.isArray();
        AdjustableManager adjustableManager = document.adjustableManager;
        CirSim cirSim = document.getCirSim();
        int count = 0;

        for (int i = 0; i < adjustables.size(); i++) {
            JSONValue adjValue = adjustables.get(i);
            if (adjValue == null || adjValue.isObject() == null) {
                continue;
            }

            JSONObject adjJson = adjValue.isObject();

            // Get element reference
            JSONValue elementValue = adjJson.get("element");
            if (elementValue == null || elementValue.isString() == null) {
                continue;
            }

            String elementId = elementValue.isString().stringValue();
            CircuitElm elm = importedElements.get(elementId);
            if (elm == null) {
                CirSim.console("JSON import: adjustable references unknown element: " + elementId);
                continue;
            }

            // Get edit item
            int editItem = 0;
            JSONValue editItemValue = adjJson.get("edit_item");
            if (editItemValue != null && editItemValue.isNumber() != null) {
                editItem = (int) editItemValue.isNumber().doubleValue();
            }

            // Create adjustable
            Adjustable adj = new Adjustable(cirSim, elm, editItem);

            // Label
            JSONValue labelValue = adjJson.get("label");
            if (labelValue != null && labelValue.isString() != null) {
                adj.sliderText = labelValue.isString().stringValue();
            }

            // Value range
            JSONValue minValue = adjJson.get("min_value");
            if (minValue != null && minValue.isNumber() != null) {
                adj.minValue = minValue.isNumber().doubleValue();
            }

            JSONValue maxValue = adjJson.get("max_value");
            if (maxValue != null && maxValue.isNumber() != null) {
                adj.maxValue = maxValue.isNumber().doubleValue();
            }

            // Note: current_value is not applied here because slider is not created yet.
            // The slider will be created with default value by createSliders().
            // If we need to restore the exact value, we would need to implement
            // a deferred value setting mechanism.

            // Add adjustable directly to the list
            adjustableManager.adjustables.add(adj);
            count++;
        }

        return count;
    }

    private boolean getBoolean(JSONObject obj, String key, boolean defaultValue) {
        JSONValue value = obj.get(key);
        if (value == null || value.isBoolean() == null) {
            return defaultValue;
        }
        return value.isBoolean().booleanValue();
    }

    private double getDouble(JSONObject obj, String key, double defaultValue) {
        JSONValue value = obj.get(key);
        if (value == null || value.isNumber() == null) {
            return defaultValue;
        }
        return value.isNumber().doubleValue();
    }

    private boolean validateSchema(JSONObject root) {
        JSONValue schemaValue = root.get("schema");
        if (schemaValue == null || schemaValue.isObject() == null) {
            return false;
        }

        JSONObject schema = schemaValue.isObject();

        JSONValue formatValue = schema.get("format");
        if (formatValue == null || formatValue.isString() == null) {
            return false;
        }

        String formatStr = formatValue.isString().stringValue();
        if (!"circuitjs".equals(formatStr)) {
            return false;
        }

        JSONValue versionValue = schema.get("version");
        if (versionValue == null || versionValue.isString() == null) {
            return false;
        }

        String version = versionValue.isString().stringValue();
        // Accept version 2.x
        return version.startsWith("2.");
    }

    @Override
    public boolean canImport(String data) {
        if (data == null || data.trim().isEmpty()) {
            return false;
        }

        String trimmed = data.trim();

        // Quick check: must start with '{' for JSON object
        if (!trimmed.startsWith("{")) {
            return false;
        }

        try {
            JSONValue parsed = JSONParser.parseStrict(trimmed);
            if (parsed == null || parsed.isObject() == null) {
                return false;
            }

            JSONObject root = parsed.isObject();
            return validateSchema(root);

        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public CircuitFormat getFormat() {
        return format;
    }
}
