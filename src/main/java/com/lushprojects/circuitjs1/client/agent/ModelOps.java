package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONBoolean;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;
import com.google.gwt.json.client.JSONValue;
import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.CircuitSimulator;
import com.lushprojects.circuitjs1.client.CustomCompositeModel;
import com.lushprojects.circuitjs1.client.CustomLogicModel;
import com.lushprojects.circuitjs1.client.DiodeModel;
import com.lushprojects.circuitjs1.client.DocumentScope;
import com.lushprojects.circuitjs1.client.ExtListEntry;
import com.lushprojects.circuitjs1.client.element.ChipElm;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.CustomCompositeElm;
import com.lushprojects.circuitjs1.client.element.MosfetElm;
import com.lushprojects.circuitjs1.client.io.CircuitFormatRegistry;
import com.lushprojects.circuitjs1.client.io.ModelDependencies;
import com.lushprojects.circuitjs1.client.io.ModelDependencies.Ref;
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
 * <li><b>Subcircuits.</b> A ModelSpec is built from its {@code source} document
 *     ({@link Scope#buildSubcircuit}: read-only, inside the source's {@code DocumentScope}); a
 *     subcircuit definition's inner references are checked after the identity test — the static
 *     scan of {@link ModelSpecCodec#innerProblem}, then a trial build with a restorer for every
 *     catalogue entry it creates ({@link #trialBuild}).</li>
 * <li><b>listModels</b> ([SP_AGA_02_15]), ModelRecords and the usage scan ({@code usedBy}), and the
 *     models of a document for {@code getCircuit} ([SP_AGA_02_05]) — both through the dependency
 *     closure of subcircuit models ([SP_AGA_03_11] "Dependencies").</li>
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
        /** The new definitions in call order (all kinds). */
        private final List<ModelSpecCodec.Definition> order = new ArrayList<>();
        final CirSim sim;
        /** The document the call changes (a subcircuit source must be another one). */
        final CircuitDocument target;

        Scope(CirSim sim, CircuitDocument target) {
            this.sim = sim;
            this.target = target;
        }

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
            order.add(d);
        }

        /**
         * [SP_AGA_01_13] "Subcircuit source": checks the source handle (open, not busy, not the
         * target) and builds the model from the source's whole circuit inside its
         * {@code DocumentScope}, read-only ({@code CircuitSimulator.buildCompositeReadOnly}),
         * with the editor's pin layout; its stored circuit is the source's own text dump.
         */
        @Override
        public CustomCompositeModel buildSubcircuit(String handle, String name, boolean showLabel, String field,
                List<ModelSpecCodec.Problem> problems) {
            final CircuitDocument src = DocumentHandles.find(sim, handle);
            if (src == null) {
                Issue u = DocumentHandles.unknown(sim, handle);
                problems.add(new ModelSpecCodec.Problem(ModelSpecCodec.UNKNOWN_DOCUMENT, field + ".doc", u.getMessage(), u.getHint()));
                return null;
            }
            if (src.isAgentBusy()) {
                Issue b = AgentApi.busyIssue(src);
                problems.add(new ModelSpecCodec.Problem(ModelSpecCodec.BUSY, field + ".doc", b.getMessage(), b.getHint()));
                return null;
            }
            if (src == target) {
                problems.add(sourceProblem(field, "a subcircuit cannot be built from the document it is defined in",
                        "Build the block in its own document (createDocument), then name that document as the source."));
                return null;
            }
            CircuitSimulator.CompositeBuild b = DocumentScope.call(sim, src, () -> src.simulator.buildCompositeReadOnly());
            CustomCompositeModel.BuildProblem problem = b.problem;
            CustomCompositeModel m = b.model;
            if (problem == null) {
                problem = m.layoutPins();
            }
            if (problem != null) {
                problems.add(sourceProblem(field, problem.reason, SOURCE_HINT));
                return null;
            }
            m.name = name;
            m.setShowLabel(showLabel);
            // [SP_AGA_01_13] "Build": modelCircuit is the source document's own circuit dump
            m.modelCircuit = DocumentScope.call(sim, src,
                    () -> CircuitFormatRegistry.getDefault().createExporter().export(src));
            return m;
        }

        /** Inner-reference names: the session catalogues plus the earlier definitions of the call. */
        ModelSpecCodec.InnerNames innerNames() {
            return new ModelSpecCodec.InnerNames() {
                @Override
                public boolean exists(String kind, String name) {
                    return pending(kind, name) != null || ModelSpecCodec.entry(kind, name) != null;
                }

                @Override
                public String subcircuitNodeList(String name) {
                    ModelSpecCodec.Definition d = pending(ModelSpecCodec.SUBCIRCUIT, name);
                    CustomCompositeModel m = d != null ? d.composite() : CustomCompositeModel.findEntry(name);
                    return m == null ? null : m.nodeList;
                }
            };
        }
    }

    private static final String SOURCE_HINT = "Fix the source circuit: label every external pin with a labelled node "
            + "(one label per node, none on ground, each used by an element) and connect every internal node.";

    private static ModelSpecCodec.Problem sourceProblem(String field, String reason, String hint) {
        return new ModelSpecCodec.Problem(ModelSpecCodec.INVALID_VALUE, field,
                "Argument '" + field + "': " + reason + ".", hint);
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
        if (d.innerField != null && !checkInner(d, scope, issues)) {
            return null;
        }
        scope.add(d);
        return new Planned(d, false);
    }

    private static IssueCode codeOf(String problemCode) {
        if (ModelSpecCodec.UNKNOWN_DOCUMENT.equals(problemCode)) {
            return IssueCode.UNKNOWN_DOCUMENT;
        }
        if (ModelSpecCodec.BUSY.equals(problemCode)) {
            return IssueCode.BUSY;
        }
        if (ModelSpecCodec.UNKNOWN_PROPERTY.equals(problemCode)) {
            return IssueCode.UNKNOWN_PROPERTY;
        }
        if (ModelSpecCodec.UNKNOWN_MODEL.equals(problemCode)) {
            return IssueCode.UNKNOWN_MODEL;
        }
        return IssueCode.INVALID_VALUE;
    }

    /**
     * [SP_AGA_01_13] "Inner references" of a new subcircuit definition: the static scan (class
     * names, model-name fields against the session plus the earlier definitions of the call,
     * recursion), then a trial build ({@link #trialBuild}). Any problem is {@code invalid_value}
     * naming {@code source} or {@code modelText}; the catalogues are unchanged either way.
     *
     * @return true when the definition passes
     */
    private static boolean checkInner(ModelSpecCodec.Definition d, Scope scope, List<Issue> issues) {
        CustomCompositeModel m = d.composite();
        String reason = ModelSpecCodec.innerProblem(m, scope.innerNames());
        if (reason == null) {
            reason = trialBuild(m, scope);
        }
        if (reason == null) {
            return true;
        }
        issues.add(Issue.of(IssueCode.INVALID_VALUE, "Argument '" + d.innerField + "': " + reason + ".",
                m.extList == null || m.extList.isEmpty() ? "Give the subcircuit at least one external pin (a labelled node in its source)."
                        : "Define the models the subcircuit's elements use first (dependencies first: earlier defineModel edits "
                        + "or earlier models entries), or pass the model line exactly as getCircuit returns it."));
        return false;
    }

    /**
     * Builds the elements of {@code m} once, as a {@code Subcircuit} of it would, in the call's
     * target document, and discards them. The earlier definitions of the call are registered for
     * the trial, and every catalogue entry the trial creates or rewrites (legacy forward-drop
     * diodes, fallback logic models, those registrations) is put back afterwards, as are the
     * session-wide MOSFET display flags ([SP_AGA_03_08] R1).
     *
     * @return null, or the reason when building throws (its message)
     */
    static String trialBuild(CustomCompositeModel m, Scope scope) {
        final List<Runnable> restorers = new ArrayList<>();
        Map<String, List<String>> before = new HashMap<>();
        for (String k : ModelSpecCodec.KINDS) {
            before.put(k, ModelSpecCodec.names(k));
        }
        // a MOSFET's load constructor sets the session-wide display flags from its dump
        int mosfetFlags = MosfetElm.getGlobalFlags();
        DiodeModel.beginFallbackRecording(restorers::add);
        CustomLogicModel.beginFallbackRecording(restorers::add);
        String reason = null;
        try {
            for (ModelSpecCodec.Definition p : scope.order) {
                if (ModelSpecCodec.entry(p.kind, p.name) == null) {
                    restorers.add(ModelSpecCodec.define(p));
                }
            }
            CustomCompositeElm.trialLoad(scope.target, m);
        } catch (Throwable t) {
            // [SP_AGA_01_13] any exception while building is invalid_value, never internal_error
            reason = "the model does not load (" + (t.getMessage() != null ? Catalogue.clipName(t.getMessage()) : t.getClass().getName()) + ")";
        } finally {
            MosfetElm.setGlobalFlags(mosfetFlags);
            DiodeModel.endFallbackRecording();
            CustomLogicModel.endFallbackRecording();
            for (int i = restorers.size() - 1; i >= 0; i--) {
                restorers.get(i).run();
            }
            // anything else the trial created goes too
            for (String k : ModelSpecCodec.KINDS) {
                List<String> was = before.get(k);
                for (String n : ModelSpecCodec.names(k)) {
                    if (!was.contains(n)) {
                        ModelSpecCodec.discard(k, n);
                    }
                }
            }
        }
        return reason;
    }

    /**
     * Validates the AgentCircuit {@code models} list (at most 200, in array order, dependencies
     * first) into {@code scope}.
     *
     * @return the planned definitions in order (empty when absent), or null on errors
     */
    static List<Planned> validateList(JSONValue v, String where, Scope scope, List<Issue> issues) {
        return validateList(v, where, scope, false, issues);
    }

    /**
     * As {@link #validateList(JSONValue, String, Scope, List)}; {@code file} entries (the
     * {@code models} section of a JSON v2 text, [SP_AGA_03_12] "Full definitions") must not carry
     * {@code from} or {@code source}.
     */
    static List<Planned> validateList(JSONValue v, String where, Scope scope, boolean file, List<Issue> issues) {
        List<Planned> out = new ArrayList<>();
        if (v == null || v.isNull() != null) {
            return out;
        }
        JSONArray list = v.isArray();
        // [SP_AGA_03_12] a file's models section has no cap (the exporter writes them all)
        if (list == null || (!file && list.size() > MAX_MODELS)) {
            issues.add(Issue.of(IssueCode.INVALID_VALUE, "Argument '" + where + "' must be a list of "
                    + (file ? "" : "at most " + MAX_MODELS + " ") + "ModelSpec or ModelText entries.",
                    "Pass [{kind, name, parameters}] or [{kind, name, modelText}]."));
            return null;
        }
        int before = issues.size();
        for (int i = 0; i < list.size(); i++) {
            ModelSpecCodec.Problem fileProblem = file ? ModelSpecCodec.fileEntryProblem(list.get(i), where + "[" + i + "]") : null;
            if (fileProblem != null) {
                issues.add(Issue.of(codeOf(fileProblem.code), fileProblem.message, fileProblem.hint));
                continue;
            }
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

    /**
     * [SP_AGA_02_04] The PinNames of a {@code Subcircuit} with the subcircuit model {@code name}
     * (an earlier definition of the call, else the session entry): {@code pin1}..{@code pinN} in
     * the model's pin order ([SP_AGA_01_13] "Build").
     *
     * @return the pin names, or null when no such model exists
     */
    static String[] subcircuitPins(String name, Scope scope) {
        ModelSpecCodec.Definition d = scope == null ? null : scope.pending(ModelSpecCodec.SUBCIRCUIT, name);
        CustomCompositeModel m = d != null ? d.composite() : CustomCompositeModel.findEntry(name);
        if (m == null || m.extList == null) {
            return null;
        }
        return PinNames.fromJsonNames(null, m.extList.size());
    }

    // ---------------------------------------------------------------- usage and closure

    /** @return the models an element uses, directly and through subcircuit models ({@link ModelDependencies}, L2) */
    private static List<Ref> usedModels(CircuitElm elm, Map<String, List<Ref>> cache) {
        return ModelDependencies.usedModels(elm, cache);
    }

    /**
     * [SP_AGA_03_11] "Dependencies": the usage of the session's models by the open documents. An
     * element uses the models it references directly and, for a Subcircuit, every model its
     * subcircuit model depends on.
     */
    static final class Usage {
        /** kind + "\u0000" + name → doc handle → element IDs */
        private final Map<String, LinkedHashMap<String, List<String>>> uses = new HashMap<>();

        static Usage scan(CirSim sim) {
            Usage u = new Usage();
            Map<String, List<Ref>> cache = new HashMap<>();
            for (CircuitDocument doc : sim.documentManager.getDocuments()) {
                String handle = DocumentHandles.of(doc);
                for (CircuitElm elm : doc.simulator.elmList) {
                    for (Ref r : usedModels(elm, cache)) {
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
     * [SP_AGA_02_05] {@code models}: the non-built-in models of the document — those its elements
     * reference and, through subcircuit models, those their element dumps reference
     * ([SP_AGA_03_11] "Dependencies") — each once, dependencies first, otherwise in order of first
     * use, as ModelSpec or ModelText by the Form rule; at most {@link #MAX_MODELS}, the rest
     * counted. "First use" follows the record order of {@code getCircuit} (element IDs), so a
     * re-imported form lists its models in the same order.
     */
    static DocumentModels documentModels(CircuitDocument doc) {
        ModelNames.ensureDefaults();
        DocumentModels out = new DocumentModels();
        List<CircuitElm> elms = new ArrayList<>(doc.simulator.elmList);
        CircuitView.sortById(elms);
        for (Ref r : ModelDependencies.circuitModels(elms)) {
            if (out.models.size() >= MAX_MODELS) {
                out.truncated++;
                continue;
            }
            JSONObject m = ModelSpecCodec.encode(r.kind, r.name);
            if (m != null) {
                out.models.set(out.models.size(), m);
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
