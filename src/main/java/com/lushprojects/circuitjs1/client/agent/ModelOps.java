package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONBoolean;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;
import com.google.gwt.json.client.JSONValue;
import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.CustomCompositeModel;
import com.lushprojects.circuitjs1.client.CustomLogicModel;
import com.lushprojects.circuitjs1.client.ExtListEntry;
import com.lushprojects.circuitjs1.client.element.ChipElm;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.io.ModelSpecCodec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [SP_AGA_01_13] / [SP_AGA_03_11] Model definitions of the Agent API: the orchestration around
 * the L2 codec ({@link ModelSpecCodec}).
 * <ul>
 * <li><b>Validation in a batch.</b> A {@link Scope} holds the names of one call (session ∪ the
 *     new definitions so far); a definition whose name exists is accepted unchanged when its
 *     model line is identical ({@code existing}), otherwise it is {@code name_taken}; an internal
 *     name is always taken. Nothing is written while validating.</li>
 * <li><b>Application.</b> Each new definition records its entry restorer before it is written
 *     ({@link #define}); the caller's rollback runs them in reverse.</li>
 * <li><b>listModels</b> ([SP_AGA_02_15]), ModelRecords and the usage scan ({@code usedBy}), and the
 *     models of a document for {@code getCircuit} ([SP_AGA_02_05]).</li>
 * </ul>
 */
final class ModelOps {

    /** Maximum number of AgentCircuit {@code models} entries and of {@code getCircuit} models. */
    static final int MAX_MODELS = 200;

    /** [SP_AGA_06_01] item 27: the hint of a {@code name_taken} model of importCircuit content. */
    static final String IMPORT_NAME_TAKEN_HINT =
            "Open the file with `openFile` to load its models as the editor does, or rename the model.";

    private ModelOps() {
    }

    static void register(AgentApi api) {
        api.register("listModels", AgentApi.DocPolicy.NONE, AgentApi.BusyPolicy.SERVED, ModelOps::listModels);
    }

    // ---------------------------------------------------------------- validation

    /** The definitions of one call being validated: kind → name → new definition, in order. */
    static final class Scope implements ModelSpecCodec.Context {
        private final Map<String, LinkedHashMap<String, ModelSpecCodec.Definition>> created = new HashMap<>();

        @Override
        public ModelSpecCodec.Definition pending(String kind, String name) {
            Map<String, ModelSpecCodec.Definition> m = created.get(kind);
            return m == null ? null : m.get(name);
        }

        @Override
        public String valueText(String raw) {
            // [SP_AGA_03_03] one pair of outer double quotes is tolerated
            return PropertyValues.unquote(raw);
        }

        /** Listed names per catalogue, read once per validation (the catalogues do not change during it). */
        private final Map<String, List<String>> listed = new HashMap<>();

        List<String> listed(String catalogue) {
            List<String> l = listed.get(catalogue);
            if (l == null) {
                l = ModelNames.list(catalogue);
                listed.put(catalogue, l);
            }
            return l;
        }

        void add(ModelSpecCodec.Definition d) {
            LinkedHashMap<String, ModelSpecCodec.Definition> m = created.get(d.kind);
            if (m == null) {
                m = new LinkedHashMap<>();
                created.put(d.kind, m);
            }
            m.put(d.name, d);
        }
    }

    /** A validated definition and its outcome. */
    static final class Planned {
        final ModelSpecCodec.Definition def;
        /** True when the definition is identical to an entry (or an earlier one of the call): nothing to write. */
        final boolean existing;

        Planned(ModelSpecCodec.Definition def, boolean existing) {
            this.def = def;
            this.existing = existing;
        }
    }

    /**
     * Validates one ModelSpec or ModelText against {@code scope} and, when it is new, adds it to
     * the scope (later entries and elements may use it).
     *
     * @param where      argument path ({@code edits[0].model}, {@code circuit.models[2]})
     * @param importHint true for importCircuit content: {@code name_taken} gets the openFile hint
     * @return the planned definition, or null when {@code issues} received errors
     */
    static Planned validate(JSONValue v, String where, Scope scope, boolean importHint, List<Issue> issues) {
        ModelNames.ensureDefaults();
        List<ModelSpecCodec.Problem> problems = new ArrayList<>();
        ModelSpecCodec.Definition d = ModelSpecCodec.decode(v, where, scope, problems);
        for (ModelSpecCodec.Problem p : problems) {
            issues.add(Issue.of(codeOf(p.code), p.message, p.hint));
        }
        if (d == null) {
            return null;
        }
        Object entry = ModelSpecCodec.entry(d.kind, d.name);
        ModelSpecCodec.Definition earlier = scope.pending(d.kind, d.name);
        String takenHint = importHint ? IMPORT_NAME_TAKEN_HINT
                : "Names are create-only: define the model under a new name and set the elements' model to it.";
        if (entry != null && ModelSpecCodec.isInternal(entry)) {
            issues.add(Issue.of(IssueCode.NAME_TAKEN, "The " + d.kind + " model name '" + Catalogue.clipName(d.name)
                    + "' is reserved by an internal model (" + where + ").", takenHint));
            return null;
        }
        String existingLine = earlier != null ? earlier.line : entry != null ? ModelSpecCodec.lineOf(entry) : null;
        if (earlier != null || entry != null) {
            if (d.line != null && d.line.equals(existingLine)) {
                return new Planned(d, true);
            }
            issues.add(Issue.of(IssueCode.NAME_TAKEN, "The " + d.kind + " model '" + Catalogue.clipName(d.name) + "' "
                    + (earlier != null ? "is defined earlier in this call" : "exists in the session")
                    + " with a different definition (" + where + ").", takenHint));
            return null;
        }
        if (d.newEntryProblem != null) {
            // [SP_AGA_01_13] "Line": checked after the identity test, so an identical entry re-imports
            ModelSpecCodec.Problem p = d.newEntryProblem;
            issues.add(Issue.of(codeOf(p.code), p.message, p.hint));
            return null;
        }
        if (d.unsupported != null) {
            issues.add(Issue.of(IssueCode.INVALID_VALUE, "Argument '" + where + "': " + d.unsupported + ".",
                    "Define diode, transistor and logic models; a " + d.kind + " model can be used when the session already has it."));
            return null;
        }
        scope.add(d);
        return new Planned(d, false);
    }

    private static IssueCode codeOf(String problemCode) {
        if (ModelSpecCodec.UNKNOWN_PROPERTY.equals(problemCode)) {
            return IssueCode.UNKNOWN_PROPERTY;
        }
        if (ModelSpecCodec.UNKNOWN_MODEL.equals(problemCode)) {
            return IssueCode.UNKNOWN_MODEL;
        }
        return IssueCode.INVALID_VALUE;
    }

    /**
     * Validates the AgentCircuit {@code models} list (at most 200, in array order, dependencies
     * first) into {@code scope}.
     *
     * @return the planned definitions in order (empty when absent), or null on errors
     */
    static List<Planned> validateList(JSONValue v, String where, Scope scope, List<Issue> issues) {
        List<Planned> out = new ArrayList<>();
        if (v == null || v.isNull() != null) {
            return out;
        }
        JSONArray list = v.isArray();
        if (list == null || list.size() > MAX_MODELS) {
            issues.add(Issue.of(IssueCode.INVALID_VALUE, "Argument '" + where + "' must be a list of at most " + MAX_MODELS
                    + " ModelSpec or ModelText entries.", "Pass [{kind, name, parameters}] or [{kind, name, modelText}]."));
            return null;
        }
        int before = issues.size();
        for (int i = 0; i < list.size(); i++) {
            Planned p = validate(list.get(i), where + "[" + i + "]", scope, true, issues);
            if (p != null) {
                out.add(p);
            }
        }
        return issues.size() == before ? out : null;
    }

    /**
     * Writes a planned definition when it is new.
     *
     * @return the restorer that removes the entry again (taken before the write), or null when
     *         the definition was identical to an existing entry
     */
    static Runnable define(Planned p) {
        return p.existing ? null : ModelSpecCodec.define(p.def);
    }

    // ---------------------------------------------------------------- listModels

    /** [SP_AGA_02_15] {@code listModels(kind?, name?)} → {@code {models: ModelRecord[]}}. */
    static OperationResult listModels(AgentApi.Call call) {
        String kind = call.args.optEnum("kind", ModelSpecCodec.KINDS, null);
        String name = call.args.optString("name", null);
        if (name != null && kind == null && !call.args.failed()) {
            call.args.invalid("name", "requires kind", "Pass kind (diode, transistor, logic or subcircuit) with name.");
        }
        if (call.args.failed()) {
            return call.args.failure();
        }
        ModelNames.ensureDefaults();
        Usage usage = Usage.scan(call.sim);
        JSONArray list = new JSONArray();
        if (name != null) {
            Object entry = ModelSpecCodec.entry(kind, name);
            if (entry == null || ModelSpecCodec.isInternal(entry)) {
                return OperationResult.failure(Issue.of(IssueCode.UNKNOWN_MODEL, "Argument 'name' names no listed " + kind
                        + " model: '" + Catalogue.clipName(name) + "'.", "Call listModels with kind " + kind + " to see its models."));
            }
            list.set(0, record(kind, entry, usage, false));
        } else {
            for (String k : ModelSpecCodec.KINDS) {
                if (kind != null && !kind.equals(k)) {
                    continue;
                }
                for (Object e : ModelSpecCodec.listed(k)) {
                    list.set(list.size(), record(k, e, usage, false));
                }
            }
        }
        JSONObject data = new JSONObject();
        data.put("models", list);
        return OperationResult.success(data);
    }

    // ---------------------------------------------------------------- records

    /** [SP_AGA_01_13] ModelRecord of a catalogue entry. */
    static JSONObject record(String kind, Object entry, Usage usage, boolean existing) {
        String name = ModelSpecCodec.nameOf(entry);
        JSONObject r = new JSONObject();
        r.put("kind", new JSONString(kind));
        r.put("name", new JSONString(name));
        r.put("builtIn", JSONBoolean.getInstance(ModelSpecCodec.isBuiltIn(entry)));
        if (existing) {
            r.put("existing", JSONBoolean.getInstance(true));
        }
        JSONObject params = ModelSpecCodec.parameters(entry);
        if (params != null) {
            r.put("parameters", params);
        }
        if (entry instanceof CustomLogicModel) {
            CustomLogicModel lm = (CustomLogicModel) entry;
            r.put("inputs", strings(lm.inputs));
            r.put("outputs", strings(lm.outputs));
            r.put("rules", strings(ModelSpecCodec.ruleLines(lm.getRules())));
            r.put("info", new JSONString(lm.infoText == null ? "" : lm.infoText));
        }
        if (entry instanceof CustomCompositeModel) {
            CustomCompositeModel cm = (CustomCompositeModel) entry;
            r.put("showLabel", JSONBoolean.getInstance(cm.showLabel()));
            JSONArray pins = new JSONArray();
            if (cm.extList != null) {
                for (int i = 0; i < cm.extList.size(); i++) {
                    ExtListEntry e = cm.extList.get(i);
                    JSONObject p = new JSONObject();
                    p.put("pin", new JSONString("pin" + (i + 1)));
                    p.put("label", new JSONString(e.name == null ? "" : e.name));
                    p.put("side", new JSONString(side(e.side)));
                    pins.set(i, p);
                }
            }
            r.put("pins", pins);
        }
        r.put("usedBy", usage.usedBy(kind, name));
        return r;
    }

    /** @return the chip side of a pin as N, S, W or E */
    private static String side(int s) {
        switch (s) {
            case ChipElm.SIDE_N:
                return "N";
            case ChipElm.SIDE_S:
                return "S";
            case ChipElm.SIDE_E:
                return "E";
            default:
                return "W";
        }
    }

    private static JSONArray strings(String[] values) {
        return ModelSpecCodec.strings(values);
    }

    /**
     * [SP_AGA_02_04] The PinNames a {@code CustomLogic} element has with the logic model
     * {@code name} — a model defined earlier in the call being validated, else the session entry
     * — so later edits of the batch see the posts the model gives it ([SP_AGA_01_13] "Pin
     * markup": names after markup removal, made unique by [SP_AGA_03_02]).
     *
     * @return the pin names, or null when no such model exists
     */
    static String[] logicPins(String name, Scope scope) {
        ModelSpecCodec.Definition d = scope == null ? null : scope.pending(ModelSpecCodec.LOGIC, name);
        CustomLogicModel lm = d != null ? d.logic() : CustomLogicModel.findEntry(name);
        if (lm == null || lm.inputs == null || lm.outputs == null) {
            return null;
        }
        String[] raw = new String[lm.inputs.length + lm.outputs.length];
        for (int i = 0; i < raw.length; i++) {
            String n = i < lm.inputs.length ? lm.inputs[i] : lm.outputs[i - lm.inputs.length];
            String t = ChipElm.pinText(n);
            // as ChipElm.getJsonPinNames: a pin without text is pin<i>
            raw[i] = t.isEmpty() ? "pin" + (i + 1) : t;
        }
        return PinNames.fromJsonNames(raw, raw.length);
    }

    // ---------------------------------------------------------------- usage and closure

    /** One model reference of an element: its kind and name. */
    static final class Ref {
        final String kind;
        final String name;

        Ref(String kind, String name) {
            this.kind = kind;
            this.name = name;
        }
    }

    /**
     * @return the models an element references directly through its model-name keys
     *         ({@code model}, {@code model_name})
     */
    static List<Ref> refsOf(CircuitElm elm) {
        List<Ref> refs = new ArrayList<>();
        Map<String, Object> props = null;
        for (String key : new String[] { "model", "model_name" }) {
            String kind = ModelNames.kindOf(elm.getJsonModelCatalogue(key));
            if (kind == null) {
                continue;
            }
            if (props == null) {
                props = PropertyValues.current(elm);
            }
            Object v = props.get(key);
            if (v instanceof String && !((String) v).isEmpty()) {
                refs.add(new Ref(kind, (String) v));
            }
        }
        return refs;
    }

    /**
     * [SP_AGA_03_11] "Dependencies": the usage of the session's models by the open documents.
     * Direct references only: the reading of the elements inside subcircuit models comes with
     * the subcircuit definitions (PL_AGA Phase 13).
     */
    static final class Usage {
        /** kind + "\u0000" + name → doc handle → element IDs */
        private final Map<String, LinkedHashMap<String, List<String>>> uses = new HashMap<>();

        static Usage scan(CirSim sim) {
            Usage u = new Usage();
            for (CircuitDocument doc : sim.documentManager.getDocuments()) {
                String handle = DocumentHandles.of(doc);
                for (CircuitElm elm : doc.simulator.elmList) {
                    for (Ref r : refsOf(elm)) {
                        String key = r.kind + "\u0000" + r.name;
                        LinkedHashMap<String, List<String>> byDoc = u.uses.get(key);
                        if (byDoc == null) {
                            byDoc = new LinkedHashMap<>();
                            u.uses.put(key, byDoc);
                        }
                        List<String> ids = byDoc.get(handle);
                        if (ids == null) {
                            ids = new ArrayList<>();
                            byDoc.put(handle, ids);
                        }
                        String id = elm.getElementId();
                        if (!ids.contains(id)) {
                            ids.add(id);
                        }
                    }
                }
            }
            return u;
        }

        /** @return {@code usedBy}: one {@code {doc, ids}} per open document that uses the model */
        JSONArray usedBy(String kind, String name) {
            JSONArray out = new JSONArray();
            LinkedHashMap<String, List<String>> byDoc = uses.get(kind + "\u0000" + name);
            if (byDoc == null) {
                return out;
            }
            for (Map.Entry<String, List<String>> e : byDoc.entrySet()) {
                JSONObject o = new JSONObject();
                o.put("doc", new JSONString(e.getKey()));
                o.put("ids", strings(e.getValue().toArray(new String[0])));
                out.set(out.size(), o);
            }
            return out;
        }
    }

    /** The models of a document for {@code getCircuit}: entries in output form and the count left out. */
    static final class DocumentModels {
        final JSONArray models = new JSONArray();
        int truncated;
    }

    /**
     * [SP_AGA_02_05] {@code models}: the non-built-in models the document's elements reference,
     * each once in order of first use, as ModelSpec or ModelText by the Form rule; at most
     * {@link #MAX_MODELS}, the rest counted. "First use" follows the record order of
     * {@code getCircuit} (element IDs), so a re-imported form lists its models in the same order.
     */
    static DocumentModels documentModels(CircuitDocument doc) {
        ModelNames.ensureDefaults();
        DocumentModels out = new DocumentModels();
        List<String> seen = new ArrayList<>();
        List<CircuitElm> elms = new ArrayList<>(doc.simulator.elmList);
        CircuitView.sortById(elms);
        for (CircuitElm elm : elms) {
            for (Ref r : refsOf(elm)) {
                String key = r.kind + "\u0000" + r.name;
                if (seen.contains(key)) {
                    continue;
                }
                seen.add(key);
                Object entry = ModelSpecCodec.entry(r.kind, r.name);
                if (entry == null || ModelSpecCodec.isBuiltIn(entry)) {
                    continue;
                }
                if (out.models.size() >= MAX_MODELS) {
                    out.truncated++;
                    continue;
                }
                JSONObject m = ModelSpecCodec.encode(r.kind, r.name);
                if (m != null) {
                    out.models.set(out.models.size(), m);
                }
            }
        }
        return out;
    }

    /** @return the {@code models} data of a {@code defineModel} batch: one record per definition */
    static JSONArray records(CirSim sim, List<Planned> defined) {
        Usage usage = Usage.scan(sim);
        JSONArray out = new JSONArray();
        for (Planned p : defined) {
            Object entry = ModelSpecCodec.entry(p.def.kind, p.def.name);
            if (entry != null) {
                out.set(out.size(), record(p.def.kind, entry, usage, p.existing));
            }
        }
        return out;
    }
}
