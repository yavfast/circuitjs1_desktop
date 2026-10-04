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
import com.lushprojects.circuitjs1.client.dialog.ControlsDialog;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.ScopeElm;
import com.lushprojects.circuitjs1.client.io.CircuitFormat;
import com.lushprojects.circuitjs1.client.io.CircuitImporter;
import com.lushprojects.circuitjs1.client.io.ImportLifecycle;
import com.lushprojects.circuitjs1.client.io.ImportReport;
import com.lushprojects.circuitjs1.client.io.ModelDependencies;
import com.lushprojects.circuitjs1.client.io.ModelSpecCodec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Imports circuit from JSON format (any version 2.x; 2.2 adds the models section).
 * 
 * This importer handles the new JSON format with explicit
 * element properties and pin connections.
 */
public class JsonCircuitImporter implements CircuitImporter {

    private final JsonCircuitFormat format;

    // Map from element ID to created element (for scope/adjustable references)
    private Map<String, CircuitElm> importedElements;

    /** Report of the running import ([SP_AGA_03_04]); null for user loads. */
    private ImportReport report;

    public JsonCircuitImporter(JsonCircuitFormat format) {
        this.format = format;
    }

    @Override
    public void importCircuit(String data, CircuitDocument document, int flags) {
        importCircuit(data, document, flags, null);
    }

    @Override
    public void importCircuit(String data, CircuitDocument document, int flags, ImportReport report) {
        this.report = report;
        if (report != null) {
            // [SP_AGA_03_04] logic entries that elements' fallbacks create are restored on rejection
            CustomLogicModel.beginFallbackRecording(report::addModelRestorer);
            DiodeModel.beginFallbackRecording(report::addModelRestorer);
        }
        try {
            importJson(data, document, flags);
        } finally {
            if (report != null) {
                CustomLogicModel.endFallbackRecording();
                DiodeModel.endFallbackRecording();
            }
            this.report = null;
        }
    }

    /** Adds an item to the report of the running import (no-op for user loads). */
    private void reportItem(String code, ImportReport.Severity severity, String message, String key) {
        if (report != null) {
            report.addForKey(code, severity, message, key);
        }
    }

    private void importJson(String data, CircuitDocument document, int flags) {
        if (data == null || data.trim().isEmpty()) {
            CirSim.console("JSON import: empty data");
            reportItem(ImportReport.SCHEMA_INVALID, ImportReport.Severity.ERROR, "the JSON circuit is empty", null);
            return;
        }

        try {
            JSONValue parsed = JSONParser.parseStrict(data);
            if (parsed == null || parsed.isObject() == null) {
                CirSim.console("JSON import: invalid JSON structure");
                reportItem(ImportReport.SCHEMA_INVALID, ImportReport.Severity.ERROR,
                        "the JSON circuit is not a JSON object", null);
                return;
            }

            JSONObject root = parsed.isObject();

            // Validate schema
            if (!validateSchema(root)) {
                CirSim.console("JSON import: invalid or unsupported schema");
                reportItem(ImportReport.SCHEMA_INVALID, ImportReport.Severity.ERROR,
                        "schema must be {format: \"circuitjs\", version: \"2.x\"}", null);
                return;
            }

            // Reset circuit state unless retaining (shared with TextCircuitImporter)
            if ((flags & CircuitConst.RC_RETAIN) == 0) {
                ImportLifecycle.resetCircuitState(document);
            }

            importedElements = new HashMap<>();

            // [SP_AGA_03_12] "Import subcircuits only" takes the subcircuit entries of the models
            // section and the entries they depend on, and nothing else (text: '.' lines)
            if ((flags & CircuitConst.RC_SUBCIRCUITS) != 0) {
                int defined = defineModels(root, document, true);
                ImportLifecycle.finalizeCircuitLoading(document, flags, report);
                CirSim.console("JSON import: " + defined + " model(s) of the subcircuits imported");
                return;
            }

            // 0. [SP_AGA_03_12] Models, before the elements that name them
            defineModels(root, document, false);

            // 1. Parse simulation parameters (a paste keeps the current document settings,
            // as the text importer does)
            if ((flags & CircuitConst.RC_RETAIN) == 0) {
                parseSimulation(root, document);
            }

            // 2. Parse elements
            int elementCount = parseElements(root, document, (flags & CircuitConst.RC_RETAIN) == 0);

            // 3. Create auto-wires from connected_to references
            int wireCount = createAutoWires(root, document);

            // 3a. In-circuit scopes: their settings name other elements, which all exist now
            applyScopeElements(root);

            // 4. Parse scopes
            int scopeCount = parseScopes(root, document);

            // 5. Parse adjustables
            int adjustableCount = parseAdjustables(root, document);

            // 6-7. Shared post-processing (sliders, analysis, models, caches). Centring waits
            // until the explicit bounds below have been re-applied.
            ImportLifecycle.finalizeCircuitLoading(document, flags | CircuitConst.RC_NO_CENTER, report);

            // 8. Re-apply explicit element bounds from JSON after analysis to preserve
            // exact geometry
            JSONValue elementsValue = root.get("elements");
            if (elementsValue != null && elementsValue.isObject() != null) {
                JSONObject elementsObj = elementsValue.isObject();
                for (String elementId : elementsObj.keySet()) {
                    JSONValue elementValue = elementsObj.get(elementId);
                    if (elementValue == null || elementValue.isObject() == null)
                        continue;
                    JSONObject elementJson = elementValue.isObject();
                    JSONValue boundsVal = elementJson.get("bounds");
                    if (boundsVal != null && boundsVal.isObject() != null) {
                        JSONObject b = boundsVal.isObject();
                        JSONValue lv = b.get("left");
                        JSONValue tv = b.get("top");
                        JSONValue rv = b.get("right");
                        JSONValue bv = b.get("bottom");
                        if (lv != null && tv != null && rv != null && bv != null && lv.isNumber() != null
                        && tv.isNumber() != null && rv.isNumber() != null && bv.isNumber() != null) {
                            CircuitElm elm = importedElements.get(elementId);
                            if (elm != null) {
                                try {
                                    int left = (int) lv.isNumber().doubleValue();
                                    int top = (int) tv.isNumber().doubleValue();
                                    int right = (int) rv.isNumber().doubleValue();
                                    int bottom = (int) bv.isNumber().doubleValue();
                                    // Re-apply the bounding box after analysis (geometry itself comes
                                    // from the pins; bounds are not endpoints)
                                    elm.setBbox(new com.lushprojects.circuitjs1.client.Point(left, top),
                                            new com.lushprojects.circuitjs1.client.Point(right, bottom), 0);
                                } catch (Exception e) {
                                    CirSim.console("JSON import: failed to re-apply bounds for " + elementId + ": "
                                            + e.getMessage());
                                }
                            }
                        }
                    }
                }
            }

            if ((flags & CircuitConst.RC_NO_CENTER) == 0) {
                document.getRenderer().centreCircuit();
            }

            CirSim.console("JSON import: " + elementCount + " elements, " + wireCount + " auto-wires, " +
                    scopeCount + " scopes, " + adjustableCount + " adjustables");

        } catch (Exception e) {
            CirSim.console("JSON import error: " + e);
            // A JSONException from parseStrict is a malformed circuit; anything later is a failed load
            boolean parse = e instanceof JSONException;
            reportItem(parse ? ImportReport.SCHEMA_INVALID : ImportReport.ELEMENT_SKIPPED, ImportReport.Severity.ERROR,
                    parse ? "the JSON circuit is not valid JSON" : "the JSON import failed: " + e, null);
        }
    }

    /**
     * [SP_AGA_03_12] Defines the entries of the {@code models} section, in file order
     * (dependencies first), before any element is created.
     * <ul>
     * <li>Files carry full definitions: an entry with {@code from} or {@code source}, or one the
     *     codec rejects, is invalid.</li>
     * <li>Agent content ({@code importCircuit}, a create-only report): an entry whose name exists
     *     is accepted unchanged when its model line is identical and is {@code name_taken}
     *     otherwise; a new subcircuit entry passes the static inner-reference and pin check
     *     ({@link ModelSpecCodec#innerProblem}); an invalid entry is an error that rejects the
     *     import; each new entry records its restorer.</li>
     * <li>User loads, paste, subcircuits-only import and {@code openFile}: the text importer's
     *     behaviour for model lines — the entry's model line overwrites the session entry of that
     *     name (a restorer recorded first when a report is collected); an invalid entry is skipped
     *     with a console message (a {@code value_adjusted} warning on a report), never an alert;
     *     elements naming it take the fallback of an unknown model. A logic entry whose rules do
     *     not parse loads the rules before the bad line; a user load alerts the parser's message as
     *     a text {@code !} line does ({@link ImportLifecycle#alertRuleErrorOnUserLoad}).</li>
     * </ul>
     *
     * @param subcircuitsOnly "Import subcircuits only": the subcircuit entries and the entries
     *                        they depend on, nothing else
     * @return the number of entries defined or accepted
     */
    private int defineModels(JSONObject root, CircuitDocument document, boolean subcircuitsOnly) {
        JSONValue modelsValue = root.get("models");
        if (modelsValue == null || modelsValue.isNull() != null) {
            return 0;
        }
        JSONArray list = modelsValue.isArray();
        if (list == null) {
            modelEntryProblem("models", "the models section is not a list; ignored",
                    "Write models as a list of ModelSpec or ModelText entries.");
            return 0;
        }
        List<ModelSpecCodec.Definition> defs = new ArrayList<>();
        List<String> where = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            String w = "models[" + i + "]";
            List<ModelSpecCodec.Problem> problems = new ArrayList<>();
            ModelSpecCodec.Problem fileProblem = ModelSpecCodec.fileEntryProblem(list.get(i), w);
            ModelSpecCodec.Definition d = null;
            if (fileProblem != null) {
                problems.add(fileProblem);
            } else {
                // no context: a file entry names no batch, no source document and no from base
                d = ModelSpecCodec.decode(list.get(i), w, null, problems);
            }
            if (d == null) {
                ModelSpecCodec.Problem p = problems.isEmpty() ? null : problems.get(0);
                modelEntryProblem(w, "entry skipped: " + (p == null ? "invalid" : p.message),
                        p == null || p.hint == null ? "Write the entry as getCircuit or exportCircuit writes it." : p.hint);
                continue;
            }
            defs.add(d);
            where.add(w);
        }
        if (subcircuitsOnly) {
            List<ModelSpecCodec.Definition> kept = subcircuitsWithDependencies(defs);
            for (int i = defs.size() - 1; i >= 0; i--) {
                if (!kept.contains(defs.get(i))) {
                    defs.remove(i);
                    where.remove(i);
                }
            }
        }
        int count = 0;
        for (int i = 0; i < defs.size(); i++) {
            // RULE_ERR_003: one entry that fails to load never aborts the circuit
            try {
                if (defineModel(defs.get(i), where.get(i), document)) {
                    count++;
                }
            } catch (RuntimeException e) {
                modelEntryProblem(where.get(i), "entry failed to load: " + e,
                        "Write the entry as getCircuit or exportCircuit writes it.");
            }
        }
        return count;
    }

    /** Defines one decoded entry (see {@link #defineModels}). @return true when defined or accepted */
    private boolean defineModel(ModelSpecCodec.Definition d, String where, CircuitDocument document) {
        if (report != null && report.isCreateOnlyModels()) {
            // [SP_AGA_03_11] "Create-only names" (agent importCircuit content)
            Object entry = ModelSpecCodec.entry(d.kind, d.name);
            if (entry != null) {
                if (ModelSpecCodec.isInternal(entry) || !d.line.equals(ModelSpecCodec.lineOf(entry))) {
                    report.addForKey(ImportReport.NAME_TAKEN, ImportReport.Severity.ERROR, where + ": the " + d.kind
                            + " model '" + d.name + "' exists in the session with a different definition", null);
                    return false;
                }
                return true; // identical: nothing to write
            }
            if (d.newEntryProblem != null) {
                modelEntryProblem(where, d.newEntryProblem.message, d.newEntryProblem.hint);
                return false;
            }
            if (d.composite() != null) {
                // [SP_AGA_01_13] "Inner references" (static part) and "Pins"
                String reason = ModelSpecCodec.innerProblem(d.composite(), SESSION_NAMES);
                if (reason != null) {
                    modelEntryProblem(where, "subcircuit model '" + d.name + "': " + reason,
                            "Define the models its elements use first (earlier models entries, dependencies first).");
                    return false;
                }
            }
            report.addModelRestorer(ModelSpecCodec.define(d));
            return true;
        }
        // user loads and openFile: the entry's model line overwrites the session entry, as a
        // text model line does
        if (report != null) {
            report.addModelRestorer(ModelSpecCodec.entryRestorer(d.kind, d.name));
        }
        String ruleError = ModelSpecCodec.loadLine(d.kind, d.line);
        // a logic model whose rules do not parse loads the rules before the bad line; a user load
        // alerts the parser's message as a text '!' line does, an agent path reports it
        if (ruleError != null && !ImportLifecycle.alertRuleErrorOnUserLoad(document, report, ruleError)) {
            modelEntryProblem(where, "the rules of logic model '" + d.name + "' do not parse (" + ruleError + ")",
                    "Fix the rule line: left=right, one left character per input up to one per pin, one right character per output.");
        }
        return true;
    }

    /** Model names of the session catalogues (an element fallback's logic entry does not count). */
    private static final ModelSpecCodec.InnerNames SESSION_NAMES = new ModelSpecCodec.InnerNames() {
        @Override
        public boolean exists(String kind, String name) {
            if (ModelSpecCodec.LOGIC.equals(kind)) {
                return !CustomLogicModel.isUnresolved(name);
            }
            return ModelSpecCodec.entry(kind, name) != null;
        }

        @Override
        public String subcircuitNodeList(String name) {
            CustomCompositeModel m = CustomCompositeModel.findEntry(name);
            return m == null ? null : m.nodeList;
        }
    };

    /**
     * [SP_AGA_03_12] "Import subcircuits only": the subcircuit entries and, transitively, the
     * entries of the same list their element dumps reference.
     */
    private static List<ModelSpecCodec.Definition> subcircuitsWithDependencies(List<ModelSpecCodec.Definition> defs) {
        Map<String, ModelSpecCodec.Definition> byKey = new LinkedHashMap<>();
        for (ModelSpecCodec.Definition d : defs) {
            byKey.put(d.kind + "\u0000" + d.name, d);
        }
        List<ModelSpecCodec.Definition> kept = new ArrayList<>();
        for (ModelSpecCodec.Definition d : defs) {
            if (d.composite() != null) {
                keepWithDependencies(d, byKey, kept);
            }
        }
        return kept;
    }

    private static void keepWithDependencies(ModelSpecCodec.Definition d, Map<String, ModelSpecCodec.Definition> byKey,
            List<ModelSpecCodec.Definition> kept) {
        if (kept.contains(d)) {
            return;
        }
        kept.add(d);
        if (d.composite() == null) {
            return;
        }
        for (ModelDependencies.Ref r : ModelDependencies.innerRefsOf(d.composite())) {
            ModelSpecCodec.Definition dep = byKey.get(r.kind + "\u0000" + r.name);
            if (dep != null) {
                keepWithDependencies(dep, byKey, kept);
            }
        }
    }

    /**
     * A {@code models} entry that is not loaded as written: a console line always; on a report an
     * {@code invalid_value} error (agent content) or a {@code value_adjusted} warning
     * ({@code openFile}). Never an alert.
     */
    private void modelEntryProblem(String where, String message, String hint) {
        CirSim.console("JSON import: " + where + ": " + message);
        if (report != null) {
            report.addModelEntryProblem(where + ": " + message, hint);
        }
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

        // Time step (same effect as the text options line: current and max step, UI bar)
        JSONValue timeStepValue = sim.get("time_step");
        if (timeStepValue != null) {
            double ts = 0;
            if (timeStepValue.isString() != null) {
                ts = UnitParser.parse(timeStepValue.isString().stringValue());
            } else if (timeStepValue.isNumber() != null) {
                ts = timeStepValue.isNumber().doubleValue();
            }
            if (ts > 0) {
                simulator.maxTimeStep = simulator.timeStep = ts;
                // [SP_AGA_06_01 item 18] nearest bar position, without the bar's re-quantising command
                cirSim.timeStepBar.setValueWithoutCommand(ControlsDialog.timeStepToPosition(ts));
                cirSim.controlsDialog.updateTimeStepLabel();
            } else {
                CirSim.console("JSON import: ignoring invalid time_step " + timeStepValue);
                reportItem(ImportReport.SETTING_INVALID, ImportReport.Severity.WARNING,
                        "simulation.time_step is not a positive time; kept at its default", "time_step");
            }
        }

        JSONValue minTimeStepValue = sim.get("min_time_step");
        if (minTimeStepValue != null) {
            double mts = 0;
            if (minTimeStepValue.isString() != null) {
                mts = UnitParser.parse(minTimeStepValue.isString().stringValue());
            } else if (minTimeStepValue.isNumber() != null) {
                mts = minTimeStepValue.isNumber().doubleValue();
            }
            if (mts > 0) {
                simulator.minTimeStep = mts;
            } else {
                reportItem(ImportReport.SETTING_INVALID, ImportReport.Severity.WARNING,
                        "simulation.min_time_step is not a positive time; kept at its default", "min_time_step");
            }
        }

        // Voltage range
        JSONValue voltageRangeValue = sim.get("voltage_range");
        if (voltageRangeValue != null) {
            double vr = 0;
            if (voltageRangeValue.isString() != null) {
                vr = UnitParser.parse(voltageRangeValue.isString().stringValue());
            } else if (voltageRangeValue.isNumber() != null) {
                vr = voltageRangeValue.isNumber().doubleValue();
            }
            if (vr > 0) {
                ColorSettings.get().setVoltageRange(vr);
            } else {
                reportItem(ImportReport.SETTING_INVALID, ImportReport.Severity.WARNING,
                        "simulation.voltage_range is not a positive voltage; kept at its default", "voltage_range");
            }
        }

        // Speed settings
        JSONValue simSpeedValue = sim.get("simulation_speed");
        if (simSpeedValue != null && simSpeedValue.isNumber() != null && cirSim.speedBar != null) {
            cirSim.speedBar.setValue((int) simSpeedValue.isNumber().doubleValue());
        }
        JSONValue currentSpeedValue = sim.get("current_speed");
        if (currentSpeedValue != null && currentSpeedValue.isNumber() != null && cirSim.currentBar != null) {
            cirSim.currentBar.setValue((int) currentSpeedValue.isNumber().doubleValue());
        }

        JSONValue powerBrightnessValue = sim.get("power_brightness");
        if (powerBrightnessValue != null && powerBrightnessValue.isNumber() != null && cirSim.powerBar != null) {
            cirSim.powerBar.setValue((int) powerBrightnessValue.isNumber().doubleValue());
        }

        // Circuit hint
        JSONValue hintValue = sim.get("hint");
        if (hintValue != null && hintValue.isObject() != null) {
            JSONObject hint = hintValue.isObject();
            CircuitRenderer renderer = document.getRenderer();
            renderer.setHintType(getInt(hint, "type", -1));
            renderer.setHintItem1(getInt(hint, "item1", 0));
            renderer.setHintItem2(getInt(hint, "item2", 0));
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
            document.circuitEditor.setGrid();
        }
    }

    private void setCheckItem(CheckboxMenuItem checkbox, JSONObject obj, String key) {
        JSONValue value = obj.get(key);
        if (value != null && value.isBoolean() != null) {
            checkbox.setState(value.isBoolean().booleanValue());
        }
    }

    private int parseElements(JSONObject root, CircuitDocument document, boolean keepKeys) {
        JSONValue elementsValue = root.get("elements");
        if (elementsValue == null || elementsValue.isObject() == null) {
            return 0;
        }

        JSONObject elements = elementsValue.isObject();
        int count = 0;
        int skipped = 0;

        for (String elementId : elements.keySet()) {
            // [audit ITEM-04 / CF-04] RULE_ERR_003: isolate a malformed element (skip + log),
            // never let one bad element abort the whole circuit import.
            try {
            JSONValue elementValue = elements.get(elementId);
            if (elementValue == null || elementValue.isObject() == null) {
                CirSim.console("JSON import: element '" + elementId + "' is not an object");
                reportItem(ImportReport.ELEMENT_SKIPPED, ImportReport.Severity.ERROR,
                        "element " + elementId + " is not an object", elementId);
                skipped++;
                continue;
            }

            JSONObject elementJson = elementValue.isObject();

            // Get type
            JSONValue typeValue = elementJson.get("type");
            if (typeValue == null || typeValue.isString() == null) {
                CirSim.console("JSON import: element " + elementId + " has no type");
                reportItem(ImportReport.ELEMENT_SKIPPED, ImportReport.Severity.ERROR,
                        "element " + elementId + " has no type", elementId);
                skipped++;
                continue;
            }

            String jsonType = typeValue.isString().stringValue();

            // Create element using factory with CircuitDocument
            CircuitElm elm = CircuitElementFactory.createFromJson(jsonType, elementJson, document);
            if (elm == null) {
                CirSim.console("JSON import: failed to create element " + elementId + " of type " + jsonType);
                reportItem(ImportReport.ELEMENT_SKIPPED, ImportReport.Severity.ERROR,
                        "element " + elementId + " of type " + jsonType + " could not be created", elementId);
                skipped++;
                continue;
            }

            // [SP_AGA_03_03] "Model names": JSON carries no model definitions; the element fell
            // back (a copy of its previous model registered under the name)
            if (report != null && elm.getUnresolvedModelName() != null) {
                report.addUnresolvedModel("element " + elementId, elm.getUnresolvedModelName(), 0, elementId);
                // agent loads register nothing: the next element with that name is reported too
                elm.dropUnresolvedModel();
            }

            // A single-post element without _endpoint takes its end point from the informational
            // bounds (CircuitElementFactory): geometry adjusted from bounds (SP_AGA_03_04)
            JSONValue pinsForBounds = elementJson.get("pins");
            boolean hasEndpointPin = pinsForBounds != null && pinsForBounds.isObject() != null
                    && pinsForBounds.isObject().get("_endpoint") != null;
            if (elm.getPostCount() == 1 && !hasEndpointPin && elementJson.get("bounds") != null
                    && elementJson.get("bounds").isObject() != null) {
                reportItem(ImportReport.GEOMETRY_ADJUSTED, ImportReport.Severity.WARNING,
                        "element " + elementId + ": its end point is derived from bounds (no _endpoint pin)", elementId);
            }

            // [SP_AGA_03_02] Content replacement keeps the JSON keys as element IDs; an invalid or
            // repeated key is replaced by a generated ID (ids_regenerated) when the import settles
            // (ImportLifecycle.finalizeCircuitLoading). A paste (RC_RETAIN) creates elements with
            // generated IDs; the key still resolves scope/adjustable/connected_to references.
            if (keepKeys) {
                elm.setElementId(elementId);
            }

            // Initialize element with document (important for elements that override
            // setCircuitDocument
            // to initialize internal components like diodes in MosfetElm)
            elm.setCircuitDocument(document);

            // Parse p1/p2 positions and set endpoints
            JSONValue p1Val = elementJson.get("p1");
            JSONValue p2Val = elementJson.get("p2");
            if (p1Val != null && p1Val.isObject() != null && p2Val != null && p2Val.isObject() != null) {
                JSONObject p1 = p1Val.isObject();
                JSONObject p2 = p2Val.isObject();
                JSONValue p1x = p1.get("x");
                JSONValue p1y = p1.get("y");
                JSONValue p2x = p2.get("x");
                JSONValue p2y = p2.get("y");
                if (p1x != null && p1y != null && p2x != null && p2y != null &&
                    p1x.isNumber() != null && p1y.isNumber() != null &&
                    p2x.isNumber() != null && p2y.isNumber() != null) {
                    try {
                        int x1 = (int) p1x.isNumber().doubleValue();
                        int y1 = (int) p1y.isNumber().doubleValue();
                        int x2 = (int) p2x.isNumber().doubleValue();
                        int y2 = (int) p2y.isNumber().doubleValue();
                        if (x1 != elm.getX() || y1 != elm.getY() || x2 != elm.getX2() || y2 != elm.getY2()) {
                            // p1/p2 override the geometry the pins gave
                            reportItem(ImportReport.GEOMETRY_ADJUSTED, ImportReport.Severity.WARNING,
                                    "element " + elementId + ": p1/p2 replace the endpoints given by its pins", elementId);
                        }
                        elm.setEndpoints(x1, y1, x2, y2);
                        // finalizeJsonImport() already ran in the factory; endpoints update must refresh geometry.
                        elm.setPoints();
                    } catch (Exception e) {
                        CirSim.console("JSON import: failed to apply p1/p2 for " + elementId + ": " + e.getMessage());
                    }
                }
            }

            // Add to simulator
            document.simulator.elmList.add(elm);

            // Store for reference
            importedElements.put(elementId, elm);

            // If explicit bounds were provided in JSON, apply them now (after full
            // initialization)
            JSONValue boundsVal = elementJson.get("bounds");
            if (boundsVal != null && boundsVal.isObject() != null) {
                JSONObject b = boundsVal.isObject();
                JSONValue lv = b.get("left");
                JSONValue tv = b.get("top");
                JSONValue rv = b.get("right");
                JSONValue bv = b.get("bottom");
                if (lv != null && tv != null && rv != null && bv != null && lv.isNumber() != null
                        && tv.isNumber() != null && rv.isNumber() != null && bv.isNumber() != null) {
                    try {
                        int left = (int) lv.isNumber().doubleValue();
                        int top = (int) tv.isNumber().doubleValue();
                        int right = (int) rv.isNumber().doubleValue();
                        int bottom = (int) bv.isNumber().doubleValue();
                        // Bounding box only; endpoints come from the pins (see CircuitElementFactory)
                        elm.setBbox(new com.lushprojects.circuitjs1.client.Point(left, top),
                                new com.lushprojects.circuitjs1.client.Point(right, bottom), 0);
                    } catch (Exception e) {
                        CirSim.console("JSON import: failed to apply bounds for " + elementId + ": " + e.getMessage());
                    }
                }
            }

            count++;
            } catch (Exception elementError) {
                // toString() keeps the exception type; getMessage() is null for NPE/JS errors
                CirSim.console("JSON import: skipping element '" + elementId
                        + "' due to error: " + elementError);
                reportItem(ImportReport.ELEMENT_SKIPPED, ImportReport.Severity.ERROR,
                        "element " + elementId + " failed to load: " + elementError, elementId);
                skipped++;
            }
        }

        if (skipped > 0) {
            CirSim.console("JSON import: " + skipped + " element(s) could not be imported and were skipped");
        }
        return count;
    }

    /**
     * [SP_AGA_03_12] Applies the {@code scope} property of every imported {@code Scope} element
     * (text 403) once all elements exist. One scope whose settings fail is skipped with a console
     * line and a {@code value_adjusted} warning; it never aborts the load (RULE_ERR_003).
     */
    private void applyScopeElements(JSONObject root) {
        JSONValue elementsValue = root.get("elements");
        JSONObject elements = elementsValue == null ? null : elementsValue.isObject();
        if (elements == null) {
            return;
        }
        for (String key : elements.keySet()) {
            CircuitElm elm = importedElements.get(key);
            if (!(elm instanceof ScopeElm)) {
                continue;
            }
            JSONObject e = elements.get(key).isObject();
            JSONValue props = e == null ? null : e.get("properties");
            JSONValue scope = props == null || props.isObject() == null ? null : props.isObject().get("scope");
            if (scope == null || scope.isObject() == null) {
                continue;
            }
            try {
                ((ScopeElm) elm).applyJsonScope(CircuitElementFactory.jsonObjectToMap(scope.isObject()), importedElements::get);
            } catch (RuntimeException ex) {
                CirSim.console("JSON import: the scope settings of element " + key + " could not be applied: " + ex);
                reportItem(ImportReport.VALUE_ADJUSTED, ImportReport.Severity.WARNING,
                        "element " + key + ": its scope settings could not be applied (" + ex + ")", key);
            }
        }
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
                    reportItem(ImportReport.WIRE_SKIPPED, ImportReport.Severity.WARNING,
                            "auto-wire " + elementId + "." + pinName + " -> " + connectedTo + " skipped: no such element", elementId);
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
                if (sourceXVal == null || sourceYVal == null
                        || sourceXVal.isNumber() == null || sourceYVal.isNumber() == null) {
                    continue;   // [audit ITEM-04 / CF-04] non-numeric coordinate → skip, don't NPE
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
                        reportItem(ImportReport.WIRE_SKIPPED, ImportReport.Severity.WARNING,
                                "auto-wire " + elementId + "." + pinName + " -> " + connectedTo + " skipped: no such pin", elementId);
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
                    if (targetXVal == null || targetYVal == null
                            || targetXVal.isNumber() == null || targetYVal.isNumber() == null) {
                        continue;   // [audit ITEM-04 / CF-04] non-numeric coordinate → skip, don't NPE
                    }
                    targetX = (int) targetXVal.isNumber().doubleValue();
                    targetY = (int) targetYVal.isNumber().doubleValue();
                } else {
                    // No specific pin - use element's first post position
                    targetX = targetElement.getX();
                    targetY = targetElement.getY();
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
                wire.setEndpoints(sourceX, sourceY, targetX, targetY);
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
        // Append after existing scopes (0 after a reset; a paste keeps the current ones)
        int first = scopeManager.getScopeCount();
        int count = first;

        for (int i = 0; i < scopes.size(); i++) {
            if (count >= scopeManager.getMaxScopes()) {
                CirSim.console("JSON import: ignoring scopes beyond the limit of " + scopeManager.getMaxScopes());
                reportItem(ImportReport.SCOPE_LIMIT, ImportReport.Severity.WARNING,
                        (scopes.size() - i) + " scope(s) beyond the limit of " + scopeManager.getMaxScopes() + " ignored", "#" + i);
                break;
            }
            JSONValue scopeValue = scopes.get(i);
            if (scopeValue == null || scopeValue.isObject() == null) {
                continue;
            }

            JSONObject scopeJson = scopeValue.isObject();

            // Get element reference
            JSONValue elementValue = scopeJson.get("element");
            CircuitElm elm = null;
            if (elementValue != null && elementValue.isString() != null) {
                String elementId = elementValue.isString().stringValue();
                elm = importedElements.get(elementId);
                if (elm == null) {
                    CirSim.console(
                            "JSON import: scope references unknown element: " + elementId + " — attempting fallback");
                    // try to fallback to the first plot element specified in the scope
                    JSONValue plotsValueFallback = scopeJson.get("plots");
                    if (plotsValueFallback != null && plotsValueFallback.isArray() != null) {
                        JSONArray plotsArrayFallback = plotsValueFallback.isArray();
                        for (int pf = 0; pf < plotsArrayFallback.size(); pf++) {
                            JSONValue pv = plotsArrayFallback.get(pf);
                            if (pv == null || pv.isObject() == null)
                                continue;
                            JSONObject pj = pv.isObject();
                            JSONValue pvElmVal = pj.get("element");
                            if (pvElmVal != null && pvElmVal.isString() != null) {
                                String pid = pvElmVal.isString().stringValue();
                                CircuitElm resolved = importedElements.get(pid);
                                if (resolved != null) {
                                    elm = resolved;
                                    CirSim.console("JSON import: scope fallback attached to plot element: " + pid);
                                    break;
                                }
                            }
                        }
                    }
                    // final fallback: attach to first imported element (if any)
                    if (elm == null && !importedElements.isEmpty()) {
                        for (CircuitElm c : importedElements.values()) {
                            elm = c;
                            break;
                        }
                        CirSim.console("JSON import: scope fallback attached to first imported element");
                    }
                }
            }
            if (elm == null) {
                CirSim.console("JSON import: skipping scope because no available element to attach");
                continue;
            }

            // Create scope (one form for docked and in-circuit scopes: JsonScopeCodec)
            Scope scope = new Scope(cirSim, document);
            JsonScopeCodec.apply(scope, elm, CircuitElementFactory.jsonObjectToMap(scopeJson), importedElements::get, document);

            // Add scope at current index
            scopeManager.setScope(count, scope);
            count++;
        }

        // Update scope count
        scopeManager.setScopeCount(count);
        return count - first;
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
        // Exported index -> imported adjustable, and the pending shared_slider references
        Adjustable[] byIndex = new Adjustable[adjustables.size()];
        int[] sharedRef = new int[adjustables.size()];

        for (int i = 0; i < adjustables.size(); i++) {
            sharedRef[i] = -1;
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

            // Label (optional in hand-written files: fall back to the edit item name, as the
            // Sliders dialog does when a slider is added)
            JSONValue labelValue = adjJson.get("label");
            if (labelValue != null && labelValue.isString() != null) {
                adj.sliderText = labelValue.isString().stringValue();
            } else {
                EditInfo ei = elm.getEditInfo(editItem);
                adj.sliderText = ei != null && ei.name != null ? ei.name.replaceAll(" \\(.*\\)$", "") : "";
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

            // current_value needs no separate restore: createSlider() positions the slider from
            // the element's own (already imported) property value.

            JSONValue sharedValue = adjJson.get("shared_slider");
            if (sharedValue != null && sharedValue.isNumber() != null) {
                sharedRef[i] = (int) sharedValue.isNumber().doubleValue();
            }

            // Add adjustable directly to the list
            adjustableManager.adjustables.add(adj);
            byIndex[i] = adj;
            count++;
        }

        // Resolve shared sliders once every adjustable exists (references use exported indices)
        for (int i = 0; i < byIndex.length; i++) {
            int ref = sharedRef[i];
            if (byIndex[i] != null && ref >= 0 && ref < byIndex.length && ref != i && byIndex[ref] != null
                    && byIndex[ref].sharedSlider == null) {
                byIndex[i].sharedSlider = byIndex[ref];
            }
        }
        adjustableManager.reorderAdjustables();

        return count;
    }



    private int getInt(JSONObject obj, String key, int defaultValue) {
        JSONValue value = obj.get(key);
        if (value == null || value.isNumber() == null) {
            return defaultValue;
        }
        return (int) value.isNumber().doubleValue();
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
