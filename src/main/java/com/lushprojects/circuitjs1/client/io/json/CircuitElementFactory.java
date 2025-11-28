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
import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.element.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Factory for creating CircuitElm instances from JSON.
 * 
 * Uses element constructors list and builds JSON type mapping dynamically
 * from getJsonTypeName() method of each element.
 */
public class CircuitElementFactory {

    /**
     * Functional interface for element constructor.
     * Since GWT doesn't support full reflection, we use explicit constructors.
     */
    @FunctionalInterface
    public interface ElementConstructor {
        CircuitElm create(int x, int y);
    }

    /**
     * Element registration entry containing constructor and JSON type name.
     */
    private static class ElementEntry {
        final ElementConstructor constructor;
        final String jsonTypeName;

        ElementEntry(ElementConstructor constructor, String jsonTypeName) {
            this.constructor = constructor;
            this.jsonTypeName = jsonTypeName;
        }
    }

    /**
     * List of all registered element constructors.
     */
    private static final List<ElementEntry> ELEMENT_ENTRIES = new ArrayList<>();

    /**
     * Mapping from JSON type name to element entry (built from ELEMENT_ENTRIES).
     */
    private static final Map<String, ElementEntry> JSON_TYPE_TO_ENTRY = new HashMap<>();

    /**
     * Flag to track if the type map has been built.
     */
    private static boolean typeMapBuilt = false;

    static {
        // Register all element constructors
        // The JSON type name is obtained from a temporary instance's getJsonTypeName()

        // Basic passive elements
        register(WireElm::new);
        register(GroundElm::new);
        register(ResistorElm::new);
        register(CapacitorElm::new);
        register(PolarCapacitorElm::new);
        register(InductorElm::new);
        register(PotElm::new);

        // Voltage sources
        register(DCVoltageElm::new);
        register(ACVoltageElm::new);
        register(RailElm::new);
        register(ACRailElm::new);
        register(SquareRailElm::new);
        register(VarRailElm::new);
        register(AntennaElm::new);
        register(SweepElm::new);
        register(NoiseElm::new);
        register(AMElm::new);
        register(FMElm::new);

        // Current source
        register(CurrentElm::new);

        // Diodes
        register(DiodeElm::new);
        register(ZenerElm::new);
        register(LEDElm::new);
        register(LEDArrayElm::new);
        register(VaractorElm::new);
        register(TunnelDiodeElm::new);

        // Bipolar transistors
        register(NTransistorElm::new);
        register(PTransistorElm::new);
        register(NDarlingtonElm::new);
        register(PDarlingtonElm::new);

        // MOSFETs
        register(NMosfetElm::new);
        register(PMosfetElm::new);

        // JFETs
        register(NJfetElm::new);
        register(PJfetElm::new);

        // Thyristors
        register(SCRElm::new);
        register(DiacElm::new);
        register(TriacElm::new);

        // Other semiconductors
        register(TriodeElm::new);
        register(UnijunctionElm::new);

        // Op-amps
        register(OpAmpElm::new);
        register(OpAmpSwapElm::new);
        register(OpAmpRealElm::new);
        register(ComparatorElm::new);
        register(OTAElm::new);

        // Controlled sources
        register(VCVSElm::new);
        register(VCCSElm::new);
        register(CCVSElm::new);
        register(CCCSElm::new);
        register(CC2Elm::new);
        register(CC2NegElm::new);

        // Switches
        register(SwitchElm::new);
        register(PushSwitchElm::new);
        register(Switch2Elm::new);
        register(MBBSwitchElm::new);
        register(DPDTSwitchElm::new);
        register(CrossSwitchElm::new);
        register(AnalogSwitchElm::new);
        register(AnalogSwitch2Elm::new);
        register(TriStateElm::new);
        register(RelayElm::new);
        register(RelayCoilElm::new);
        register(RelayContactElm::new);
        register(TimeDelayRelayElm::new);

        // Transformers
        register(TransformerElm::new);
        register(TappedTransformerElm::new);
        register(CustomTransformerElm::new);
        register(TransLineElm::new);

        // Logic gates
        register(InverterElm::new);
        register(AndGateElm::new);
        register(NandGateElm::new);
        register(OrGateElm::new);
        register(NorGateElm::new);
        register(XorGateElm::new);
        register(SchmittElm::new);
        register(InvertingSchmittElm::new);
        register(DelayBufferElm::new);

        // Flip-flops
        register(DFlipFlopElm::new);
        register(JKFlipFlopElm::new);
        register(TFlipFlopElm::new);
        register(LatchElm::new);

        // Counters
        register(CounterElm::new);
        register(Counter2Elm::new);
        register(RingCounterElm::new);

        // Logic I/O
        register(LogicInputElm::new);
        register(LogicOutputElm::new);
        register(ClockElm::new);

        // Converters
        register(DACElm::new);
        register(ADCElm::new);
        register(PhaseCompElm::new);
        register(VCOElm::new);

        // Multiplexers
        register(MultiplexerElm::new);
        register(DeMultiplexerElm::new);
        register(SipoShiftElm::new);
        register(PisoShiftElm::new);
        register(SeqGenElm::new);
        register(SRAMElm::new);

        // Adders
        register(HalfAdderElm::new);
        register(FullAdderElm::new);
        register(MonostableElm::new);

        // Displays
        register(SevenSegElm::new);
        register(SevenSegDecoderElm::new);
        register(DecimalDisplayElm::new);

        // Timer
        register(TimerElm::new);

        // Custom logic
        register(CustomLogicElm::new);
        register(CustomCompositeElm::new);

        // Measuring
        register(ProbeElm::new);
        register(OutputElm::new);
        register(AmmeterElm::new);
        register(OhmMeterElm::new);
        register(WattmeterElm::new);
        register(TestPointElm::new);
        register(DataRecorderElm::new);
        register(StopTriggerElm::new);
        register(ScopeElm::new);

        // Audio
        register(AudioOutputElm::new);
        register(AudioInputElm::new);

        // Input
        register(DataInputElm::new);
        register(ExtVoltageElm::new);

        // Other components
        register(LampElm::new);
        register(FuseElm::new);
        register(SparkGapElm::new);
        register(MemristorElm::new);
        register(CrystalElm::new);
        register(LDRElm::new);
        register(ThermistorNTCElm::new);
        register(OptocouplerElm::new);

        // Motors
        register(DCMotorElm::new);
        register(ThreePhaseMotorElm::new);
        register(MotorProtectionSwitchElm::new);

        // Labels and graphics
        register(TextElm::new);
        register(BoxElm::new);
        register(LineElm::new);
        register(LabeledNodeElm::new);
    }

    /**
     * Registers an element constructor.
     * Creates a temporary instance to get the JSON type name.
     */
    private static void register(ElementConstructor constructor) {
        // Create temporary instance to get JSON type name
        CircuitElm tempElm = constructor.create(0, 0);
        String jsonTypeName = tempElm.getJsonTypeName();
        ELEMENT_ENTRIES.add(new ElementEntry(constructor, jsonTypeName));
    }

    /**
     * Builds the JSON type to entry map from the registered entries.
     * Called lazily on first use.
     */
    private static void ensureTypeMapBuilt() {
        if (typeMapBuilt) {
            return;
        }
        for (ElementEntry entry : ELEMENT_ENTRIES) {
            JSON_TYPE_TO_ENTRY.put(entry.jsonTypeName, entry);
        }
        typeMapBuilt = true;
    }

    /**
     * Creates a CircuitElm from JSON element definition.
     * 
     * @param jsonType The JSON type name (e.g., "Resistor")
     * @param elementJson The full JSON object for the element
     * @return The created element, or null if type is unknown
     */
    public static CircuitElm createFromJson(String jsonType, JSONObject elementJson) {
        ensureTypeMapBuilt();

        // Get element entry from type mapping
        ElementEntry entry = JSON_TYPE_TO_ENTRY.get(jsonType);
        if (entry == null) {
            CirSim.console("CircuitElementFactory: Unknown element type: " + jsonType);
            return null;
        }

        // Get position from pins
        int x1 = 0, y1 = 0;
        int x2 = 0, y2 = 0;
        boolean hasStartpoint = false;
        boolean hasEndpoint = false;

        JSONValue pinsValue = elementJson.get("pins");
        if (pinsValue != null && pinsValue.isObject() != null) {
            JSONObject pins = pinsValue.isObject();
            
            // Check for _startpoint (used for elements where point1 doesn't match any pin, like OpAmp)
            JSONValue startpointValue = pins.get("_startpoint");
            if (startpointValue != null && startpointValue.isObject() != null) {
                int[] startpointPos = getPinPosition(startpointValue);
                if (startpointPos != null) {
                    x1 = startpointPos[0];
                    y1 = startpointPos[1];
                    hasStartpoint = true;
                }
            }
            
            // Check for _endpoint (used by single-terminal elements and elements where point2 doesn't match any pin)
            JSONValue endpointValue = pins.get("_endpoint");
            if (endpointValue != null && endpointValue.isObject() != null) {
                int[] endpointPos = getPinPosition(endpointValue);
                if (endpointPos != null) {
                    x2 = endpointPos[0];
                    y2 = endpointPos[1];
                    hasEndpoint = true;
                }
            }
            
            // Find first two pins (excluding _startpoint and _endpoint)
            String[] pinKeys = getObjectKeys(pins);
            int pinCount = 0;
            for (String key : pinKeys) {
                if (key.equals("_startpoint") || key.equals("_endpoint")) continue;
                
                int[] pos = getPinPosition(pins.get(key));
                if (pos != null) {
                    if (pinCount == 0 && !hasStartpoint) {
                        x1 = pos[0];
                        y1 = pos[1];
                    } else if (pinCount == 1 && !hasEndpoint) {
                        x2 = pos[0];
                        y2 = pos[1];
                    }
                    pinCount++;
                }
            }
            
            if (pinCount == 1 && !hasEndpoint) {
                x2 = x1;
                y2 = y1;
            }
        }

        // Create element using constructor from registry
        CircuitElm elm = entry.constructor.create(x1, y1);
        if (elm == null) {
            CirSim.console("CircuitElementFactory: Failed to create element: " + jsonType);
            return null;
        }

        // Set second point
        elm.x2 = x2;
        elm.y2 = y2;

        // For single-terminal elements without _endpoint, try to restore x2/y2 from bounds
        // This preserves orientation/size for elements like Rail, Ground (legacy support)
        if (elm.getPostCount() == 1 && !hasEndpoint) {
            JSONValue boundsValue = elementJson.get("bounds");
            if (boundsValue != null && boundsValue.isObject() != null) {
                JSONObject bounds = boundsValue.isObject();
                // Calculate x2/y2 from bounds - use opposite corner from x1/y1
                JSONValue rightVal = bounds.get("right");
                JSONValue bottomVal = bounds.get("bottom");
                JSONValue leftVal = bounds.get("left");
                JSONValue topVal = bounds.get("top");
                if (rightVal != null && bottomVal != null && leftVal != null && topVal != null) {
                    int left = (int) leftVal.isNumber().doubleValue();
                    int top = (int) topVal.isNumber().doubleValue();
                    int right = (int) rightVal.isNumber().doubleValue();
                    int bottom = (int) bottomVal.isNumber().doubleValue();
                    // Determine x2/y2 based on which corner x1/y1 is at
                    if (x1 == left) {
                        elm.x2 = right;
                    } else {
                        elm.x2 = left;
                    }
                    if (y1 == top) {
                        elm.y2 = bottom;
                    } else {
                        elm.y2 = top;
                    }
                }
            }
        }

        // Apply flags
        JSONValue flagsValue = elementJson.get("_flags");
        if (flagsValue != null && flagsValue.isNumber() != null) {
            elm.applyJsonFlags((int) flagsValue.isNumber().doubleValue());
        }

        // Apply pin positions (skip for single-terminal to preserve x2/y2)
        if (pinsValue != null && pinsValue.isObject() != null && elm.getPostCount() > 1) {
            Map<String, Map<String, Integer>> pinsMap = parsePinsMap(pinsValue.isObject());
            elm.applyJsonPinPositions(pinsMap);
        }

        // Apply properties - delegate to element
        JSONValue propsValue = elementJson.get("properties");
        if (propsValue != null && propsValue.isObject() != null) {
            Map<String, Object> propsMap = jsonObjectToMap(propsValue.isObject());
            elm.applyJsonProperties(propsMap);
        }

        // Apply description
        JSONValue descValue = elementJson.get("description");
        if (descValue != null && descValue.isString() != null) {
            elm.setDescription(descValue.isString().stringValue());
        }

        // Finalize import
        elm.finalizeJsonImport();

        return elm;
    }

    /**
     * Gets all registered JSON type names.
     * @return List of all known JSON type names
     */
    public static List<String> getAllJsonTypeNames() {
        ensureTypeMapBuilt();
        return new ArrayList<>(JSON_TYPE_TO_ENTRY.keySet());
    }

    /**
     * Checks if a JSON type is known.
     */
    public static boolean isKnownType(String jsonType) {
        ensureTypeMapBuilt();
        return JSON_TYPE_TO_ENTRY.containsKey(jsonType);
    }

    // Helper methods

    private static String[] getObjectKeys(JSONObject obj) {
        java.util.Set<String> keySet = obj.keySet();
        return keySet.toArray(new String[0]);
    }

    private static int[] getPinPosition(JSONValue pinValue) {
        if (pinValue == null || pinValue.isObject() == null) {
            return null;
        }
        JSONObject pin = pinValue.isObject();
        JSONValue posValue = pin.get("position");
        if (posValue == null || posValue.isObject() == null) {
            return null;
        }
        JSONObject pos = posValue.isObject();
        JSONValue xVal = pos.get("x");
        JSONValue yVal = pos.get("y");
        if (xVal == null || yVal == null) {
            return null;
        }
        int x = (int) xVal.isNumber().doubleValue();
        int y = (int) yVal.isNumber().doubleValue();
        return new int[] {x, y};
    }

    private static Map<String, Map<String, Integer>> parsePinsMap(JSONObject pinsJson) {
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        for (String pinName : pinsJson.keySet()) {
            JSONValue pinValue = pinsJson.get(pinName);
            if (pinValue != null && pinValue.isObject() != null) {
                JSONObject pin = pinValue.isObject();
                JSONValue posValue = pin.get("position");
                if (posValue != null && posValue.isObject() != null) {
                    JSONObject pos = posValue.isObject();
                    Map<String, Integer> posMap = new LinkedHashMap<>();
                    JSONValue xVal = pos.get("x");
                    JSONValue yVal = pos.get("y");
                    if (xVal != null && xVal.isNumber() != null) {
                        posMap.put("x", (int) xVal.isNumber().doubleValue());
                    }
                    if (yVal != null && yVal.isNumber() != null) {
                        posMap.put("y", (int) yVal.isNumber().doubleValue());
                    }
                    result.put(pinName, posMap);
                }
            }
        }
        return result;
    }

    /**
     * Converts a JSONObject to a Map<String, Object>.
     * Handles nested objects, arrays, and primitive types.
     */
    public static Map<String, Object> jsonObjectToMap(JSONObject json) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (json == null) {
            return map;
        }
        for (String key : json.keySet()) {
            JSONValue value = json.get(key);
            map.put(key, jsonValueToObject(value));
        }
        return map;
    }

    private static Object jsonValueToObject(JSONValue value) {
        if (value == null || value.isNull() != null) {
            return null;
        }
        if (value.isString() != null) {
            return value.isString().stringValue();
        }
        if (value.isNumber() != null) {
            return value.isNumber().doubleValue();
        }
        if (value.isBoolean() != null) {
            return value.isBoolean().booleanValue();
        }
        if (value.isObject() != null) {
            return jsonObjectToMap(value.isObject());
        }
        if (value.isArray() != null) {
            JSONArray arr = value.isArray();
            Object[] result = new Object[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                result[i] = jsonValueToObject(arr.get(i));
            }
            return result;
        }
        return null;
    }
}
