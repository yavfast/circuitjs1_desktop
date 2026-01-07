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
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.element.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Factory for creating CircuitElm instances from JSON.
 * 
 * Uses element constructors with simple (x, y) parameters.
 * JSON type names are registered explicitly for each element type.
 * CircuitDocument is set via setCircuitDocument() after creation.
 */
public class CircuitElementFactory {

    /**
     * Functional interface for element constructor.
     * Uses (CircuitDocument, x, y) constructor.
     */
    @FunctionalInterface
    public interface ElementConstructor {
        CircuitElm create(CircuitDocument doc, int x, int y);
    }

    /**
     * Mapping from JSON type name to element constructor.
     */
    private static final Map<String, ElementConstructor> JSON_TYPE_TO_CONSTRUCTOR = new HashMap<>();

    /**
     * Flag to track if the factory has been initialized.
     */
    private static boolean initialized = false;

    /**
     * Initializes the factory by registering all element constructors.
     * Called lazily on first use.
     */
    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        // Basic passive elements
        register("Wire", WireElm::new);
        register("Ground", GroundElm::new);
        register("Resistor", ResistorElm::new);
        register("Capacitor", CapacitorElm::new);
        register("PolarCapacitor", PolarCapacitorElm::new);
        register("Inductor", InductorElm::new);
        register("Potentiometer", PotElm::new);

        // Voltage sources
        register("DCVoltage", DCVoltageElm::new);
        register("ACVoltage", ACVoltageElm::new);
        register("Rail", RailElm::new);
        register("ACRail", ACRailElm::new);
        register("SquareRail", SquareRailElm::new);
        register("VarRail", VarRailElm::new);
        // Aliases used by element JSON export (waveform-specific type names)
        register("VoltageSourceDC", (doc, x, y) -> VoltageElm.createWithWaveform(doc, x, y,
                com.lushprojects.circuitjs1.client.element.waveform.Waveform.WF_DC));
        register("VoltageSourceAC", (doc, x, y) -> VoltageElm.createWithWaveform(doc, x, y,
                com.lushprojects.circuitjs1.client.element.waveform.Waveform.WF_AC));
        register("VoltageSourceSquare", (doc, x, y) -> VoltageElm.createWithWaveform(doc, x, y,
                com.lushprojects.circuitjs1.client.element.waveform.Waveform.WF_SQUARE));
        register("VoltageSourceTriangle", (doc, x, y) -> VoltageElm.createWithWaveform(doc, x, y,
                com.lushprojects.circuitjs1.client.element.waveform.Waveform.WF_TRIANGLE));
        register("VoltageSourceSawtooth", (doc, x, y) -> VoltageElm.createWithWaveform(doc, x, y,
                com.lushprojects.circuitjs1.client.element.waveform.Waveform.WF_SAWTOOTH));
        register("VoltageSourcePulse", (doc, x, y) -> VoltageElm.createWithWaveform(doc, x, y,
                com.lushprojects.circuitjs1.client.element.waveform.Waveform.WF_PULSE));
        register("VoltageSourceNoise", (doc, x, y) -> VoltageElm.createWithWaveform(doc, x, y,
                com.lushprojects.circuitjs1.client.element.waveform.Waveform.WF_NOISE));
        // Legacy alias: previously a variable waveform existed; treat it as DC.
        register("VoltageSourceVar", (doc, x, y) -> VoltageElm.createWithWaveform(doc, x, y,
            com.lushprojects.circuitjs1.client.element.waveform.Waveform.WF_DC));
        // Rail aliases
        register("VariableRail", VarRailElm::new);
        register("ExternalVoltage", ExtVoltageElm::new);
        register("Antenna", AntennaElm::new);
        register("Sweep", SweepElm::new);
        register("Noise", NoiseElm::new);
        register("AM", AMElm::new);
        register("FM", FMElm::new);

        // Current source
        register("Current", CurrentElm::new);

        // Diodes
        register("Diode", DiodeElm::new);
        register("Zener", ZenerElm::new);
        register("LED", LEDElm::new);
        register("LEDArray", LEDArrayElm::new);
        register("Varactor", VaractorElm::new);
        register("TunnelDiode", TunnelDiodeElm::new);

        // Bipolar transistors
        register("TransistorNPN", NTransistorElm::new);
        register("TransistorPNP", PTransistorElm::new);
        register("DarlingtonNPN", NDarlingtonElm::new);
        register("DarlingtonPNP", PDarlingtonElm::new);

        // MOSFETs
        register("MosfetN", NMosfetElm::new);
        register("MosfetP", PMosfetElm::new);
        register("NMosfet", NMosfetElm::new); // Alternative name (from JSON export)
        register("PMosfet", PMosfetElm::new); // Alternative name (from JSON export)

        // JFETs
        register("JfetN", NJfetElm::new);
        register("JfetP", PJfetElm::new);
        register("NJFET", NJfetElm::new); // Alternative name (from JSON export)
        register("PJFET", PJfetElm::new); // Alternative name (from JSON export)

        // Thyristors
        register("SCR", SCRElm::new);
        register("Diac", DiacElm::new);
        register("Triac", TriacElm::new);

        // Other semiconductors
        register("Triode", TriodeElm::new);
        register("Unijunction", UnijunctionElm::new);

        // Op-amps
        register("OpAmp", OpAmpElm::new);
        register("OpAmpSwap", OpAmpSwapElm::new);
        register("OpAmpReal", OpAmpRealElm::new);
        register("Comparator", ComparatorElm::new);
        register("OTA", OTAElm::new);

        // Controlled sources
        register("VCVS", VCVSElm::new);
        register("VCCS", VCCSElm::new);
        register("CCVS", CCVSElm::new);
        register("CCCS", CCCSElm::new);
        register("CC2", CC2Elm::new);
        register("CC2Neg", CC2NegElm::new);

        // Switches
        register("Switch", SwitchElm::new);
        register("PushSwitch", PushSwitchElm::new);
        register("Switch2", Switch2Elm::new);
        register("MBBSwitch", MBBSwitchElm::new);
        register("DPDTSwitch", DPDTSwitchElm::new);
        register("CrossSwitch", CrossSwitchElm::new);
        register("AnalogSwitch", AnalogSwitchElm::new);
        register("AnalogSwitch2", AnalogSwitch2Elm::new);
        register("TriState", TriStateElm::new);
        register("Relay", RelayElm::new);
        register("RelayCoil", RelayCoilElm::new);
        register("RelayContact", RelayContactElm::new);
        register("TimeDelayRelay", TimeDelayRelayElm::new);

        // Transformers
        register("Transformer", TransformerElm::new);
        register("TappedTransformer", TappedTransformerElm::new);
        register("CustomTransformer", CustomTransformerElm::new);
        register("TransLine", TransLineElm::new);

        // Logic gates
        register("Inverter", InverterElm::new);
        register("AndGate", AndGateElm::new);
        register("NandGate", NandGateElm::new);
        register("OrGate", OrGateElm::new);
        register("NorGate", NorGateElm::new);
        register("XorGate", XorGateElm::new);
        register("Schmitt", SchmittElm::new);
        register("InvertingSchmitt", InvertingSchmittElm::new);
        register("DelayBuffer", DelayBufferElm::new);

        // Flip-flops
        register("DFlipFlop", DFlipFlopElm::new);
        register("JKFlipFlop", JKFlipFlopElm::new);
        register("TFlipFlop", TFlipFlopElm::new);
        register("Latch", LatchElm::new);

        // Counters
        register("Counter", CounterElm::new);
        register("Counter2", Counter2Elm::new);
        register("RingCounter", RingCounterElm::new);

        // Logic I/O
        register("LogicInput", LogicInputElm::new);
        register("LogicOutput", LogicOutputElm::new);
        register("Clock", ClockElm::new);

        // Converters
        register("DAC", DACElm::new);
        register("ADC", ADCElm::new);
        register("PhaseComp", PhaseCompElm::new);
        register("VCO", VCOElm::new);

        // Multiplexers
        register("Multiplexer", MultiplexerElm::new);
        register("DeMultiplexer", DeMultiplexerElm::new);
        register("SipoShift", SipoShiftElm::new);
        register("PisoShift", PisoShiftElm::new);
        register("SeqGen", SeqGenElm::new);
        register("SRAM", SRAMElm::new);

        // Adders
        register("HalfAdder", HalfAdderElm::new);
        register("FullAdder", FullAdderElm::new);
        register("Monostable", MonostableElm::new);

        // Displays
        register("SevenSeg", SevenSegElm::new);
        register("SevenSegDecoder", SevenSegDecoderElm::new);
        register("DecimalDisplay", DecimalDisplayElm::new);

        // Timer
        register("Timer", TimerElm::new);

        // Custom logic
        register("CustomLogic", CustomLogicElm::new);
        register("CustomComposite", CustomCompositeElm::new);

        // Measuring
        register("Probe", ProbeElm::new);
        register("Output", OutputElm::new);
        register("Ammeter", AmmeterElm::new);
        register("OhmMeter", OhmMeterElm::new);
        register("Wattmeter", WattmeterElm::new);
        register("TestPoint", TestPointElm::new);
        register("DataRecorder", DataRecorderElm::new);
        register("StopTrigger", StopTriggerElm::new);
        register("Scope", ScopeElm::new);

        // Audio
        register("AudioOutput", AudioOutputElm::new);
        register("AudioInput", AudioInputElm::new);

        // Input
        register("DataInput", DataInputElm::new);
        register("ExtVoltage", ExtVoltageElm::new);

        // Other components
        register("Lamp", LampElm::new);
        register("Fuse", FuseElm::new);
        register("SparkGap", SparkGapElm::new);
        register("Memristor", MemristorElm::new);
        register("Crystal", CrystalElm::new);
        register("LDR", LDRElm::new);
        register("ThermistorNTC", ThermistorNTCElm::new);
        register("Optocoupler", OptocouplerElm::new);

        // Motors
        register("DCMotor", DCMotorElm::new);
        register("ThreePhaseMotor", ThreePhaseMotorElm::new);
        register("MotorProtectionSwitch", MotorProtectionSwitchElm::new);

        // Labels and graphics
        register("Text", TextElm::new);
        register("Box", BoxElm::new);
        register("Line", LineElm::new);
        register("LabeledNode", LabeledNodeElm::new);

        // Keep initialization quiet in production/DevMode; JSON import may call this
        // frequently.
    }

    /**
     * Registers an element constructor with explicit JSON type name.
     */
    private static void register(String jsonTypeName, ElementConstructor constructor) {
        JSON_TYPE_TO_CONSTRUCTOR.put(jsonTypeName, constructor);
    }

    /**
     * Ensures the factory is initialized.
     */
    private static void ensureInitialized() {
        if (!initialized) {
            init();
        }
    }

    /**
     * Creates a CircuitElm from JSON element definition.
     * 
     * @param jsonType    The JSON type name (e.g., "Resistor")
     * @param elementJson The full JSON object for the element
     * @param document    The circuit document for the new element
     * @return The created element, or null if type is unknown
     */
    public static CircuitElm createFromJson(String jsonType, JSONObject elementJson, CircuitDocument document) {
        ensureInitialized();

        // Get constructor from type mapping
        ElementConstructor constructor = JSON_TYPE_TO_CONSTRUCTOR.get(jsonType);
        if (constructor == null) {
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

            // Check for _startpoint (used for elements where point1 doesn't match any pin,
            // like OpAmp)
            JSONValue startpointValue = pins.get("_startpoint");
            if (startpointValue != null && startpointValue.isObject() != null) {
                int[] startpointPos = getPinPosition(startpointValue);
                if (startpointPos != null) {
                    x1 = startpointPos[0];
                    y1 = startpointPos[1];
                    hasStartpoint = true;
                }
            }

            // Check for _endpoint (used by single-terminal elements and elements where
            // point2 doesn't match any pin)
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
                if (key.equals("_startpoint") || key.equals("_endpoint"))
                    continue;

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

        // Create element using constructor with CircuitDocument
        CircuitElm elm;
        try {
            elm = constructor.create(document, x1, y1);
        } catch (Exception e) {
            CirSim.console("CircuitElementFactory: Exception creating " + jsonType + ": " + e.getMessage());
            return null;
        }
        if (elm == null) {
            CirSim.console("CircuitElementFactory: Failed to create element: " + jsonType);
            return null;
        }

        // Set second point (use canonical setter to keep geometry derived fields in
        // sync)
        elm.setEndpoints(elm.getX(), elm.getY(), x2, y2);

        // For single-terminal elements without _endpoint, try to restore x2/y2 from
        // bounds
        // This preserves orientation/size for elements like Rail, Ground (legacy
        // support)
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
                    int newX2, newY2;
                    if (x1 == left) {
                        newX2 = right;
                    } else {
                        newX2 = left;
                    }
                    if (y1 == top) {
                        newY2 = bottom;
                    } else {
                        newY2 = top;
                    }
                    elm.setEndpoints(elm.getX(), elm.getY(), newX2, newY2);
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
            try {
                elm.applyJsonPinPositions(pinsMap);
            } catch (Exception e) {
                CirSim.console(
                        "CircuitElementFactory: applyJsonPinPositions failed for " + jsonType + ": " + e.getMessage());
                return null;
            }
        }

        // Apply properties - delegate to element
        JSONValue propsValue = elementJson.get("properties");
        if (propsValue != null && propsValue.isObject() != null) {
            Map<String, Object> propsMap = jsonObjectToMap(propsValue.isObject());
            elm.applyJsonProperties(propsMap);
        }

        // Apply simulation state (voltages, currents, internal state)
        JSONValue stateValue = elementJson.get("state");
        if (stateValue != null && stateValue.isObject() != null) {
            Map<String, Object> stateMap = jsonObjectToMap(stateValue.isObject());
            elm.applyJsonState(stateMap);
        }

        // Apply description
        JSONValue descValue = elementJson.get("description");
        if (descValue != null && descValue.isString() != null) {
            elm.setDescription(descValue.isString().stringValue());
        }

        // Finalize import
        try {
            elm.finalizeJsonImport();
        } catch (Exception e) {
            CirSim.console("CircuitElementFactory: finalizeJsonImport failed for " + jsonType + ": " + e.getMessage());
            return null;
        }

        // Preserve explicit bounds from JSON if provided to ensure export/import
        // idempotency
        JSONValue boundsValueFinal = elementJson.get("bounds");
        if (boundsValueFinal != null && boundsValueFinal.isObject() != null) {
            JSONObject boundsObj = boundsValueFinal.isObject();
            JSONValue leftVal = boundsObj.get("left");
            JSONValue topVal = boundsObj.get("top");
            JSONValue rightVal = boundsObj.get("right");
            JSONValue bottomVal = boundsObj.get("bottom");
            if (leftVal != null && topVal != null && rightVal != null && bottomVal != null
                    && leftVal.isNumber() != null && topVal.isNumber() != null
                    && rightVal.isNumber() != null && bottomVal.isNumber() != null) {
                int left = (int) leftVal.isNumber().doubleValue();
                int top = (int) topVal.isNumber().doubleValue();
                int right = (int) rightVal.isNumber().doubleValue();
                int bottom = (int) bottomVal.isNumber().doubleValue();
                try {
                    // Set coords to match JSON bounds so any later re-computation
                    // uses the intended geometry.
                    elm.setEndpoints(left, top, right, bottom);
                    // Use public setBbox(Point,Point,double) to update boundingBox
                    // (avoids calling package-private API and works across packages).
                    elm.setBbox(new com.lushprojects.circuitjs1.client.Point(left, top),
                            new com.lushprojects.circuitjs1.client.Point(right, bottom), 0);
                } catch (Exception e) {
                    CirSim.console("CircuitElementFactory: failed to apply JSON bounds: " + e.getMessage());
                }
            }
        }

        return elm;
    }

    /**
     * Gets all registered JSON type names.
     * 
     * @return List of all known JSON type names
     */
    public static List<String> getAllJsonTypeNames() {
        ensureInitialized();
        return new ArrayList<>(JSON_TYPE_TO_CONSTRUCTOR.keySet());
    }

    /**
     * Checks if a JSON type is known.
     */
    public static boolean isKnownType(String jsonType) {
        ensureInitialized();
        return JSON_TYPE_TO_CONSTRUCTOR.containsKey(jsonType);
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
        return new int[] { x, y };
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
