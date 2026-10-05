package com.lushprojects.circuitjs1.client.io;

import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;
import com.google.gwt.json.client.JSONValue;
import com.lushprojects.circuitjs1.client.CustomCompositeModel;
import com.lushprojects.circuitjs1.client.CustomLogicModel;
import com.lushprojects.circuitjs1.client.DiodeModel;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.TransistorModel;
import com.lushprojects.circuitjs1.client.element.BaseCircuitElm;
import com.lushprojects.circuitjs1.client.element.ChipElm;
import com.lushprojects.circuitjs1.client.element.CompositeModelScan;
import com.lushprojects.circuitjs1.client.util.EchoText;
import com.lushprojects.circuitjs1.client.util.UnitValues;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * [SP_AGA_01_13] ModelSpec / ModelText codec of the four session model catalogues (diode,
 * transistor, custom logic, subcircuit): validation and decoding of a definition into a detached
 * model that no catalogue holds yet, the model line it produces (the "identical" comparison of
 * [SP_AGA_03_11]), the encoding of a catalogue entry as ModelSpec or ModelText by the Form rule
 * of [SP_AGA_02_05], record parameters, and the catalogue write itself
 * ({@link #define}, which returns the entry's restorer).
 * <p>
 * Lives in {@code io/} (L2) so the JSON format can reuse it ([SP_AGA_03_12], PL_AGA Phase 14);
 * the agent package (L3) orchestrates (batches, results, usage). Problems are returned as
 * (code, field, reason) items, never as alerts. Model lines are compared through the classes'
 * side-effect-free {@code modelLine()}: {@code dump()} marks entries dumped and the logic
 * {@code dump()} rewrites its stored rules.
 * <p>
 * All four kinds are defined. Logic rules are validated by the editor's own parser
 * ({@link CustomLogicModel#parseRules(String, int, int)}), never by alerts. A subcircuit ModelSpec
 * is built from its {@code source} document by the caller ({@link Context#buildSubcircuit}: the
 * documents are the app shell's); a subcircuit ModelText is read into a detached model. The inner
 * references of either ({@link Definition#innerField}) are checked by the caller after the
 * create-only identity test ({@code element/CompositeModelScan} and a trial build).
 */
public final class ModelSpecCodec {

    public static final String DIODE = "diode";
    public static final String TRANSISTOR = "transistor";
    public static final String LOGIC = "logic";
    public static final String SUBCIRCUIT = "subcircuit";
    /** Kinds in listing order ([SP_AGA_02_15]). */
    public static final String[] KINDS = { DIODE, TRANSISTOR, LOGIC, SUBCIRCUIT };

    /** Problem codes (wire form). */
    public static final String INVALID_VALUE = "invalid_value";
    public static final String UNKNOWN_PROPERTY = "unknown_property";
    public static final String UNKNOWN_MODEL = "unknown_model";
    /** [SP_AGA_01_13] Subcircuit {@code source}: the handle names no open document. */
    public static final String UNKNOWN_DOCUMENT = "unknown_document";
    /** [SP_AGA_01_13] Subcircuit {@code source}: the source document is busy with an agent run. */
    public static final String BUSY = "busy";

    /** [SP_AGA_01_13] ModelName pattern of a ModelSpec. */
    public static final String NAME_PATTERN = "^[A-Za-z0-9][A-Za-z0-9_.+-]{0,39}$";

    /** [SP_AGA_01_13] Logic ModelSpec limits: pins per side, rule lines, chars per rule line, info chars. */
    public static final int MAX_LOGIC_PINS = 32;
    public static final int MAX_RULES = 256;
    public static final int MAX_RULE_CHARS = 100;
    public static final int MAX_INFO_CHARS = 200;
    /** [SP_AGA_01_13] Pin name of a logic ModelSpec, markup included. */
    public static final String PIN_PATTERN = "^[A-Za-z0-9/#:_+-]{1,8}$";

    /** Relative tolerance of the record form's forward-voltage check. */
    static final double FORWARD_VOLTAGE_TOLERANCE = 1e-9;

    private static final String DELIMITERS = " +\t\n\r\f";

    private ModelSpecCodec() {
    }

    /** One problem of a definition: code, the field it names (argument path), reason and fix. */
    public static final class Problem {
        public final String code;
        public final String field;
        public final String message;
        public final String hint;

        public Problem(String code, String field, String message, String hint) {
            this.code = code;
            this.field = field;
            this.message = message;
            this.hint = hint;
        }
    }

    /** A decoded definition. */
    public static final class Definition {
        public final String kind;
        public final String name;
        /** The model line the definition produces (the identical check). */
        public final String line;
        /** The detached model to register (a diode, transistor, logic or subcircuit model). */
        final Object model;
        /**
         * A problem that rejects the definition only as a new entry (a logic ModelText whose rules
         * do not parse): an identical existing entry is still accepted, as the text importer
         * accepts an identical model line ([SP_AGA_02_05] "Round trip"). Null when none.
         */
        public final Problem newEntryProblem;
        /**
         * Subcircuit only: the argument path whose inner references the caller checks after the
         * identity test ({@code ….source} for a model built from a document, {@code ….modelText});
         * null for other kinds.
         */
        public final String innerField;

        Definition(String kind, String name, String line, Object model) {
            this(kind, name, line, model, null, null);
        }

        Definition(String kind, String name, String line, Object model, Problem newEntryProblem, String innerField) {
            this.kind = kind;
            this.name = name;
            this.line = line;
            this.model = model;
            this.newEntryProblem = newEntryProblem;
            this.innerField = innerField;
        }

        /** @return the detached model when it is a diode model, else null (used as a {@code from} base) */
        public DiodeModel diode() {
            return model instanceof DiodeModel ? (DiodeModel) model : null;
        }

        /** @return the detached model when it is a transistor model, else null */
        public TransistorModel transistor() {
            return model instanceof TransistorModel ? (TransistorModel) model : null;
        }

        /** @return the detached model when it is a logic model, else null (its pins for later edits) */
        public CustomLogicModel logic() {
            return model instanceof CustomLogicModel ? (CustomLogicModel) model : null;
        }

        /** @return the detached model when it is a subcircuit model, else null (its pins and dumps) */
        public CustomCompositeModel composite() {
            return model instanceof CustomCompositeModel ? (CustomCompositeModel) model : null;
        }
    }

    /** What decoding needs from its caller. */
    public interface Context {
        /**
         * @return a model of {@code kind} defined earlier in the same batch or {@code models} list
         *         (a {@code from} base), or null
         */
        Definition pending(String kind, String name);

        /** @return a parameter string as the caller's value rules read it (e.g. outer quotes dropped) */
        String valueText(String raw);

        /**
         * [SP_AGA_01_13] "Subcircuit source": builds the model from the whole circuit of the open
         * document {@code handle} without changing that document, named {@code name}, with the
         * chip label shown when {@code showLabel}. Problems ({@link #UNKNOWN_DOCUMENT},
         * {@link #BUSY}, {@link #INVALID_VALUE} naming {@code field}) go to {@code problems}.
         *
         * @return the detached model (no catalogue entry), or null after a problem
         */
        CustomCompositeModel buildSubcircuit(String handle, String name, boolean showLabel, String field,
                List<Problem> problems);
    }

    // ---------------------------------------------------------------- catalogue access

    /** @return true when {@code kind} is one of {@link #KINDS} */
    public static boolean isKind(String kind) {
        for (String k : KINDS) {
            if (k.equals(kind)) {
                return true;
            }
        }
        return false;
    }

    /** @return the catalogue entry of that kind and name (internal ones included), or null */
    public static Object entry(String kind, String name) {
        if (name == null) {
            return null;
        }
        switch (kind) {
            case DIODE:
                return DiodeModel.findEntry(name);
            case TRANSISTOR:
                return TransistorModel.findEntry(name);
            case LOGIC:
                return CustomLogicModel.findEntry(name);
            case SUBCIRCUIT:
                return CustomCompositeModel.findEntry(name);
            default:
                return null;
        }
    }

    /**
     * @return true when an element line of the running import created the entry (a CustomLogic
     *         fallback or a legacy {@code fwdrop} diode), so it is not a session entry yet
     */
    public static boolean isFallbackName(String kind, String name) {
        if (LOGIC.equals(kind)) {
            return CustomLogicModel.isFallbackName(name);
        }
        if (DIODE.equals(kind)) {
            return DiodeModel.isFallbackName(name);
        }
        return false;
    }

    /** @return true for an internal entry (never listed, never accepted as a value or base) */
    public static boolean isInternal(Object entry) {
        if (entry instanceof DiodeModel) {
            return ((DiodeModel) entry).isInternal();
        }
        if (entry instanceof TransistorModel) {
            return ((TransistorModel) entry).isInternal();
        }
        if (entry instanceof CustomCompositeModel) {
            return ((CustomCompositeModel) entry).isInternal();
        }
        return false; // no logic entry is internal
    }

    /** [SP_AGA_01_13] "Built-in": diode/transistor built-in flag, composite {@code builtin}; never logic. */
    public static boolean isBuiltIn(Object entry) {
        if (entry instanceof DiodeModel) {
            return ((DiodeModel) entry).builtIn;
        }
        if (entry instanceof TransistorModel) {
            return ((TransistorModel) entry).builtIn;
        }
        if (entry instanceof CustomCompositeModel) {
            return ((CustomCompositeModel) entry).isBuiltin();
        }
        return false;
    }

    /** @return the name of a catalogue entry */
    public static String nameOf(Object entry) {
        if (entry instanceof DiodeModel) {
            return ((DiodeModel) entry).name;
        }
        if (entry instanceof TransistorModel) {
            return ((TransistorModel) entry).name;
        }
        if (entry instanceof CustomLogicModel) {
            return ((CustomLogicModel) entry).getName();
        }
        if (entry instanceof CustomCompositeModel) {
            return ((CustomCompositeModel) entry).name;
        }
        return null;
    }

    /** @return the side-effect-free model line of a catalogue entry */
    public static String lineOf(Object entry) {
        if (entry instanceof DiodeModel) {
            return ((DiodeModel) entry).modelLine();
        }
        if (entry instanceof TransistorModel) {
            return ((TransistorModel) entry).modelLine();
        }
        if (entry instanceof CustomLogicModel) {
            return ((CustomLogicModel) entry).modelLine();
        }
        if (entry instanceof CustomCompositeModel) {
            return ((CustomCompositeModel) entry).modelLine();
        }
        return null;
    }

    /**
     * [SP_AGA_02_15] The listed (non-internal) entries of a kind, built-in first, then by name in
     * code-point order. The logic {@code default} entry is ensured first ([SP_AGA_01_13]).
     */
    public static List<Object> listed(String kind) {
        List<Object> all = new ArrayList<>();
        switch (kind) {
            case DIODE:
                all.addAll(DiodeModel.entries());
                break;
            case TRANSISTOR:
                all.addAll(TransistorModel.entries());
                break;
            case LOGIC:
                CustomLogicModel.ensureDefault();
                all.addAll(CustomLogicModel.entries());
                break;
            case SUBCIRCUIT:
                all.addAll(CustomCompositeModel.entries());
                break;
            default:
                break;
        }
        List<Object> out = new ArrayList<>();
        for (Object e : all) {
            // names never alias, but a map value could be registered twice (editor rename)
            if (!isInternal(e) && nameOf(e) != null && !out.contains(e)) {
                out.add(e);
            }
        }
        Collections.sort(out, new Comparator<Object>() {
            @Override
            public int compare(Object a, Object b) {
                boolean ba = isBuiltIn(a), bb = isBuiltIn(b);
                if (ba != bb) {
                    return ba ? -1 : 1;
                }
                return nameOf(a).compareTo(nameOf(b));
            }
        });
        return out;
    }

    /** @return the names of every entry of a kind's catalogue, internal ones included */
    public static List<String> names(String kind) {
        List<String> out = new ArrayList<>();
        List<? extends Object> all;
        switch (kind) {
            case DIODE:
                all = DiodeModel.entries();
                break;
            case TRANSISTOR:
                all = TransistorModel.entries();
                break;
            case LOGIC:
                all = CustomLogicModel.entries();
                break;
            case SUBCIRCUIT:
                all = CustomCompositeModel.entries();
                break;
            default:
                return out;
        }
        for (Object e : all) {
            String n = nameOf(e);
            if (n != null && !out.contains(n)) {
                out.add(n);
            }
        }
        return out;
    }

    /**
     * Removes an entry that a validation trial created (a non-built-in entry only); used to put a
     * catalogue back after a trial build ([SP_AGA_01_13] "Inner references").
     */
    public static void discard(String kind, String name) {
        switch (kind) {
            case DIODE:
                DiodeModel.removeFallback(name, DiodeModel.findEntry(name));
                break;
            case TRANSISTOR:
                TransistorModel.removeFallback(name, TransistorModel.findEntry(name));
                break;
            case LOGIC:
                CustomLogicModel.discardEntry(name);
                break;
            case SUBCIRCUIT:
                CustomCompositeModel.discardEntry(name);
                break;
            default:
                break;
        }
    }

    /**
     * [SP_AGA_03_04] "Model catalogues": captures the catalogue entry {@code name} of {@code kind}
     * as it is now; the returned action puts it back (re-applies its values in place, or removes
     * an entry that did not exist). Null for an unknown kind.
     */
    public static Runnable entryRestorer(String kind, String name) {
        switch (kind) {
            case DIODE:
                return DiodeModel.entryRestorer(name);
            case TRANSISTOR:
                return TransistorModel.entryRestorer(name);
            case LOGIC:
                return CustomLogicModel.entryRestorer(name);
            case SUBCIRCUIT:
                return CustomCompositeModel.entryRestorer(name);
            default:
                return null;
        }
    }

    /**
     * [SP_AGA_03_12] "User loads and openFile": loads a model line into its catalogue exactly as
     * the text importer loads it ({@code undumpModel}): the session entry of that name is
     * overwritten in place (elements keep their model object), or created. No alert: a logic line
     * whose rules do not parse keeps the rules before the bad line and returns the parser's
     * message.
     *
     * @param line a model line (as {@link Definition#line})
     * @return the rule error of a logic line, or null
     */
    public static String loadLine(String kind, String line) {
        StringTokenizer st = new StringTokenizer(line, DELIMITERS);
        st.nextToken(); // line type
        switch (kind) {
            case DIODE:
                DiodeModel.undumpModel(st);
                return null;
            case TRANSISTOR:
                TransistorModel.undumpModel(st);
                return null;
            case LOGIC:
                return CustomLogicModel.undumpModel(st);
            case SUBCIRCUIT:
                CustomCompositeModel.undumpModel(st);
                return null;
            default:
                throw new IllegalArgumentException("model kind " + kind);
        }
    }

    /**
     * [SP_AGA_03_12] "Full definitions": an entry of a file's {@code models} section carries no
     * {@code from} and no {@code source} (input-only fields of a ModelSpec).
     *
     * @return the problem naming the field, or null
     */
    public static Problem fileEntryProblem(JSONValue v, String where) {
        JSONObject o = v == null ? null : v.isObject();
        if (o == null) {
            return null; // decode reports it
        }
        if (o.get("from") != null) {
            return invalid(where + ".from", "is not allowed in a file: a file carries full definitions",
                    "Give the model's full parameters (the form exportCircuit and getCircuit write), without from.");
        }
        if (o.get("source") != null) {
            return invalid(where + ".source", "is not allowed in a file: a file carries full definitions",
                    "Give the subcircuit as a ModelText {kind, name, modelText} (the form exportCircuit and getCircuit write).");
        }
        return null;
    }

    /** @return the first token of a model line of that kind */
    public static String lineToken(String kind) {
        switch (kind) {
            case DIODE:
                return "34";
            case TRANSISTOR:
                return "32";
            case LOGIC:
                return "!";
            case SUBCIRCUIT:
                return ".";
            default:
                return null;
        }
    }

    // ---------------------------------------------------------------- definitions

    /**
     * Registers a decoded definition as a new catalogue entry under its name. The caller has
     * checked that the name is free (create-only) and that the definition is registrable.
     *
     * @return the restorer that removes the entry again, taken before the write
     */
    public static Runnable define(Definition d) {
        if (d.model instanceof DiodeModel) {
            Runnable r = DiodeModel.entryRestorer(d.name);
            DiodeModel.defineEntry((DiodeModel) d.model);
            return r;
        }
        if (d.model instanceof TransistorModel) {
            Runnable r = TransistorModel.entryRestorer(d.name);
            TransistorModel.defineEntry((TransistorModel) d.model);
            return r;
        }
        if (d.model instanceof CustomLogicModel) {
            Runnable r = CustomLogicModel.entryRestorer(d.name);
            CustomLogicModel.defineEntry((CustomLogicModel) d.model);
            return r;
        }
        if (d.model instanceof CustomCompositeModel) {
            Runnable r = CustomCompositeModel.entryRestorer(d.name);
            CustomCompositeModel.defineEntry((CustomCompositeModel) d.model);
            return r;
        }
        throw new IllegalStateException("model kind " + d.kind + " cannot be registered");
    }

    /**
     * Decodes one ModelSpec or ModelText ({@code modelText} present) without any catalogue write.
     *
     * @param where argument path of the entry ({@code edits[2].model}, {@code circuit.models[0]})
     * @return the definition, or null when {@code problems} received errors
     */
    public static Definition decode(JSONValue v, String where, Context ctx, List<Problem> out) {
        List<Problem> problems = new ArrayList<>();
        Definition d = decodeEntry(v, where, ctx, problems);
        out.addAll(problems);
        return problems.isEmpty() ? d : null;
    }

    private static Definition decodeEntry(JSONValue v, String where, Context ctx, List<Problem> problems) {
        JSONObject o = v == null ? null : v.isObject();
        if (o == null) {
            problems.add(invalid(where, "must be a ModelSpec or ModelText object",
                    "Pass {kind, name, parameters} or {kind, name, modelText}."));
            return null;
        }
        String kind = string(o, "kind");
        if (kind == null || !isKind(kind)) {
            problems.add(invalid(where + ".kind", kind == null ? "is required" : "is not one of the allowed values",
                    "Use one of: diode, transistor, logic, subcircuit."));
            return null;
        }
        if (o.get("name") == null || o.get("name").isString() == null) {
            problems.add(invalid(where + ".name", "is required", "Pass the model name as a string."));
            return null;
        }
        String name = o.get("name").isString().stringValue();
        return o.get("modelText") != null ? decodeText(o, kind, name, where, problems)
                : decodeSpec(o, kind, name, where, ctx, problems);
    }

    private static Definition decodeSpec(JSONObject o, String kind, String name, String where, Context ctx,
            List<Problem> problems) {
        boolean param = DIODE.equals(kind) || TRANSISTOR.equals(kind);
        if (!param && o.get("from") != null) {
            problems.add(invalid(where + ".from", "is for diode and transistor models only",
                    "Omit from; a " + kind + " model is given in full."));
            return null;
        }
        String[] allowed = param ? new String[] { "kind", "name", "from", "parameters" }
                : LOGIC.equals(kind) ? new String[] { "kind", "name", "inputs", "outputs", "rules", "info" }
                : new String[] { "kind", "name", "source", "showLabel" };
        for (String key : o.keySet()) {
            if (!contains(allowed, key)) {
                problems.add(invalid(where + "." + clip(key), "is not a field of a " + kind + " ModelSpec",
                        "Fields: " + String.join(", ", allowed) + "."));
            }
        }
        if (!name.matches(NAME_PATTERN)) {
            problems.add(invalid(where + ".name", "is not a valid model name: '" + clip(name) + "'",
                    "Use 1-40 characters: a letter or digit, then letters, digits, '_', '.', '+' or '-'."));
        }
        if (!problems.isEmpty()) {
            return null;
        }
        switch (kind) {
            case DIODE:
                return decodeDiode(o, name, where, ctx, problems);
            case TRANSISTOR:
                return decodeTransistor(o, name, where, ctx, problems);
            case LOGIC:
                return decodeLogicSpec(o, name, where, problems);
            default:
                return decodeSubcircuitSpec(o, name, where, ctx, problems);
        }
    }

    // ---------------------------------------------------------------- subcircuit

    /** [SP_AGA_01_13] Subcircuit ModelSpec: {@code source: {doc}} and {@code showLabel}. */
    private static Definition decodeSubcircuitSpec(JSONObject o, String name, String where, Context ctx,
            List<Problem> problems) {
        String sw = where + ".source";
        JSONValue src = o.get("source");
        JSONObject so = src == null ? null : src.isObject();
        if (so == null) {
            problems.add(invalid(sw, src == null ? "is required" : "must be an object {doc}",
                    "Pass source: {doc: \"d2\"} naming the open document whose circuit becomes the model."));
            return null;
        }
        for (String key : so.keySet()) {
            if (!"doc".equals(key)) {
                problems.add(invalid(sw + "." + clip(key), "is not a field of a subcircuit source", "A source is {doc}."));
            }
        }
        JSONValue dv = so.get("doc");
        if (dv == null || dv.isString() == null) {
            problems.add(invalid(sw + ".doc", dv == null ? "is required" : "must be a document handle string",
                    "Pass the handle of the open document to build from (listDocuments lists them)."));
        }
        JSONValue lv = o.get("showLabel");
        if (lv != null && lv.isBoolean() == null) {
            problems.add(invalid(where + ".showLabel", "must be true or false", "Omit showLabel to show the model name on the chip."));
        }
        if (!problems.isEmpty()) {
            return null;
        }
        if (ctx == null) {
            problems.add(invalid(sw, "cannot be built here", "Define the subcircuit with defineModel or importCircuit models."));
            return null;
        }
        boolean showLabel = lv == null || lv.isBoolean().booleanValue();
        CustomCompositeModel m = ctx.buildSubcircuit(dv.isString().stringValue(), name, showLabel, sw, problems);
        if (m == null) {
            return null;
        }
        return new Definition(SUBCIRCUIT, name, m.modelLine(), m, null, sw);
    }

    /** What the inner-reference check of a subcircuit model resolves names against. */
    public interface InnerNames {
        /** @return true when a model of {@code kind} named {@code name} can be used by the model's elements */
        boolean exists(String kind, String name);

        /** @return the node list of the subcircuit model {@code name} (for its nested dumps), or null */
        String subcircuitNodeList(String name);
    }

    /** @return the model kind of an element's model catalogue ({@code zener} → {@code diode}), or null */
    public static String kindOfCatalogue(String catalogue) {
        if ("zener".equals(catalogue)) {
            return DIODE;
        }
        return isKind(catalogue) ? catalogue : null;
    }

    /**
     * [SP_AGA_01_13] "Inner references", the static part: the class names of the model's node
     * list must name element classes, and the model-name fields of its element dumps must resolve
     * through {@code names}; a nested subcircuit that is the model itself is a recursion. A model
     * without a pin is rejected first ([SP_AGA_01_13] "Pins"). No element is built and no
     * catalogue is touched.
     *
     * @return the reason of the first problem ("inner model x unknown", …), or null
     */
    public static String innerProblem(final CustomCompositeModel m, final InnerNames names) {
        if (m.extList == null || m.extList.isEmpty()) {
            return "a subcircuit needs at least one pin";
        }
        final String[] first = new String[1];
        CompositeModelScan.scan(m.nodeList, m.elmDump, new CompositeModelScan.Resolver() {
            @Override
            public String nodeList(String name) {
                return name.equals(m.name) ? null : names.subcircuitNodeList(name);
            }
        }, new CompositeModelScan.Listener() {
            @Override
            public void reference(String catalogue, String name) {
                if (first[0] != null) {
                    return;
                }
                String kind = kindOfCatalogue(catalogue);
                if (SUBCIRCUIT.equals(kind) && name.equals(m.name)) {
                    first[0] = "a Subcircuit inside uses the model being defined, " + clip(name) + " (recursion)";
                } else if (kind != null && !names.exists(kind, name)) {
                    first[0] = "inner model " + clip(name) + " unknown";
                }
            }

            @Override
            public void unknownClass(String className) {
                if (first[0] == null) {
                    first[0] = "unknown element class " + clip(className) + " in the node list";
                }
            }
        });
        return first[0];
    }

    // ---------------------------------------------------------------- diode

    private static final String[] DIODE_CORE = { "saturation_current", "series_resistance", "emission_coefficient",
            "breakdown_voltage" };

    private static Definition decodeDiode(JSONObject o, String name, String where, Context ctx, List<Problem> problems) {
        DiodeModel base = diodeBase(o, where, ctx, problems);
        JSONObject params = parameters(o, where, problems);
        if (base == null || params == null) {
            return null;
        }
        String pw = where + ".parameters";
        Double[] core = new Double[4];
        Double fv = null, fc = null;
        for (String key : params.keySet()) {
            int ci = indexOf(DIODE_CORE, key);
            if (ci == 0) {
                core[0] = value(params, key, pw, "A", true, ">", false, ctx, problems);
            } else if (ci == 1) {
                core[1] = value(params, key, pw, "Ohm", true, ">=", false, ctx, problems);
            } else if (ci == 2) {
                core[2] = value(params, key, pw, null, false, ">", false, ctx, problems);
            } else if (ci == 3) {
                core[3] = value(params, key, pw, "V", true, ">=", false, ctx, problems);
            } else if ("forward_voltage".equals(key)) {
                fv = value(params, key, pw, "V", true, ">", false, ctx, problems);
            } else if ("forward_current".equals(key)) {
                fc = value(params, key, pw, "A", true, ">", false, ctx, problems);
            } else {
                problems.add(new Problem(UNKNOWN_PROPERTY, pw + "." + clip(key), "Parameter '" + pw + "." + clip(key)
                        + "' is not a diode model parameter.", "Diode parameters: saturation_current, series_resistance, "
                        + "emission_coefficient, breakdown_voltage, forward_voltage, forward_current."));
            }
        }
        if (!problems.isEmpty()) {
            return null;
        }
        boolean hasFv = params.get("forward_voltage") != null;
        boolean hasFc = params.get("forward_current") != null;
        int coreCount = 0;
        for (Double c : core) {
            coreCount += c != null ? 1 : 0;
        }
        boolean record = coreCount == 4;
        if (hasFc && !hasFv) {
            problems.add(invalid(pw + ".forward_current", "needs forward_voltage",
                    "Give forward_voltage and forward_current together (the simple form), e.g. \"2.1 V\" at \"20 mA\"."));
            return null;
        }
        if (hasFv && !hasFc) {
            problems.add(invalid(pw + ".forward_voltage", "needs forward_current",
                    "Give forward_voltage and forward_current together (the simple form), e.g. \"2.1 V\" at \"20 mA\"."));
            return null;
        }
        if (hasFv && !record && (core[1] != null || core[2] != null)) {
            problems.add(invalid(pw + ".forward_voltage", "cannot be combined with "
                    + (core[2] != null ? "emission_coefficient" : "series_resistance") + " unless all four core keys are given",
                    "Use the simple form (forward_voltage, forward_current, optional saturation_current and breakdown_voltage) "
                            + "or the core keys without forward_voltage."));
            return null;
        }
        int flags = 0;
        double is, rs, n, bv, storedFc = 0;
        if (record) {
            is = core[0];
            rs = core[1];
            n = core[2];
            bv = core[3];
            if (hasFc) {
                if (rs != 0) {
                    problems.add(invalid(pw + ".forward_current", "requires series_resistance = 0",
                            "A simple model has no series resistance; omit forward_voltage/forward_current or set series_resistance to 0."));
                    return null;
                }
                double drop = DiodeModel.createDetached(name, 0, is, rs, n, bv, 0).forwardVoltageAt(fc);
                if (!(Math.abs(fv - drop) <= FORWARD_VOLTAGE_TOLERANCE * Math.abs(drop))) {
                    problems.add(invalid(pw + ".forward_voltage", "is " + BaseCircuitElm.getJsonUnitText(fv, "V")
                                    + " but the core values give " + BaseCircuitElm.getJsonUnitText(drop, "V") + " at forward_current",
                            "forward_voltage is derived here; omit emission_coefficient and series_resistance to use the simple form."));
                    return null;
                }
                flags = DiodeModel.FLAGS_SIMPLE;
                storedFc = fc;
            }
        } else if (hasFv) {
            // simple form: the editor's "Create New Simple Model" (IS kept, RS 0, N solved)
            is = core[0] != null ? core[0] : base.saturationCurrent;
            bv = core[3] != null ? core[3] : base.breakdownVoltage;
            rs = 0;
            n = DiodeModel.simpleEmissionCoefficient(fv, fc, is);
            if (!(n > 0) || Double.isInfinite(n)) {
                problems.add(invalid(pw + ".forward_voltage", "gives no valid emission coefficient with this saturation current",
                        "Use a forward_current well above saturation_current."));
                return null;
            }
            flags = DiodeModel.FLAGS_SIMPLE;
            storedFc = fc;
        } else {
            is = core[0] != null ? core[0] : base.saturationCurrent;
            rs = core[1] != null ? core[1] : base.seriesResistance;
            n = core[2] != null ? core[2] : base.emissionCoefficient;
            bv = core[3] != null ? core[3] : base.breakdownVoltage;
        }
        DiodeModel dm = DiodeModel.createDetached(name, flags, is, rs, n, bv, storedFc);
        return new Definition(DIODE, name, dm.modelLine(), dm);
    }

    private static DiodeModel diodeBase(JSONObject o, String where, Context ctx, List<Problem> problems) {
        String from = fromName(o, where, problems);
        if (from == null) {
            return problems.isEmpty() ? DiodeModel.findEntry("default") : null;
        }
        Definition p = ctx == null ? null : ctx.pending(DIODE, from);
        if (p != null && p.diode() != null) {
            return p.diode();
        }
        DiodeModel dm = DiodeModel.findEntry(from);
        if (dm == null || dm.isInternal()) {
            problems.add(unknownFrom(where, from, DIODE));
            return null;
        }
        return dm;
    }

    // ---------------------------------------------------------------- transistor

    private static final String[] TRANSISTOR_KEYS = { "saturation_current", "beta_reverse", "emission_coefficient_forward",
            "emission_coefficient_reverse", "leakage_be_current", "leakage_bc_current", "leakage_be_emission",
            "leakage_bc_emission", "early_voltage_forward", "early_voltage_reverse", "knee_current_forward",
            "knee_current_reverse" };

    private static Definition decodeTransistor(JSONObject o, String name, String where, Context ctx,
            List<Problem> problems) {
        TransistorModel base = transistorBase(o, where, ctx, problems);
        JSONObject params = parameters(o, where, problems);
        if (base == null || params == null) {
            return null;
        }
        String pw = where + ".parameters";
        TransistorModel tm = TransistorModel.createDetached(name, base);
        for (String key : params.keySet()) {
            Double v;
            switch (indexOf(TRANSISTOR_KEYS, key)) {
                case 0:
                    v = value(params, key, pw, "A", true, ">", false, ctx, problems);
                    if (v != null) tm.satCur = v;
                    break;
                case 1:
                    v = value(params, key, pw, null, false, ">", false, ctx, problems);
                    if (v != null) tm.betaR = v;
                    break;
                case 2:
                    v = value(params, key, pw, null, false, ">", false, ctx, problems);
                    if (v != null) tm.emissionCoeffF = v;
                    break;
                case 3:
                    v = value(params, key, pw, null, false, ">", false, ctx, problems);
                    if (v != null) tm.emissionCoeffR = v;
                    break;
                case 4:
                    v = value(params, key, pw, "A", true, ">=", false, ctx, problems);
                    if (v != null) tm.BEleakCur = v;
                    break;
                case 5:
                    v = value(params, key, pw, "A", true, ">=", false, ctx, problems);
                    if (v != null) tm.BCleakCur = v;
                    break;
                case 6:
                    v = value(params, key, pw, null, false, ">", false, ctx, problems);
                    if (v != null) tm.leakBEemissionCoeff = v;
                    break;
                case 7:
                    v = value(params, key, pw, null, false, ">", false, ctx, problems);
                    if (v != null) tm.leakBCemissionCoeff = v;
                    break;
                case 8:
                    v = value(params, key, pw, "V", true, ">", true, ctx, problems);
                    if (v != null) tm.invEarlyVoltF = inverse(v);
                    break;
                case 9:
                    v = value(params, key, pw, "V", true, ">", true, ctx, problems);
                    if (v != null) tm.invEarlyVoltR = inverse(v);
                    break;
                case 10:
                    v = value(params, key, pw, "A", true, ">", true, ctx, problems);
                    if (v != null) tm.invRollOffF = inverse(v);
                    break;
                case 11:
                    v = value(params, key, pw, "A", true, ">", true, ctx, problems);
                    if (v != null) tm.invRollOffR = inverse(v);
                    break;
                default:
                    problems.add(new Problem(UNKNOWN_PROPERTY, pw + "." + clip(key), "Parameter '" + pw + "." + clip(key)
                            + "' is not a transistor model parameter.", "Transistor parameters: " + String.join(", ", TRANSISTOR_KEYS) + "."));
            }
        }
        if (!problems.isEmpty()) {
            return null;
        }
        return new Definition(TRANSISTOR, name, tm.modelLine(), tm);
    }

    /** "inf" (stored as the inverse 0) is read as positive infinity. */
    private static double inverse(double v) {
        return Double.isInfinite(v) ? 0 : 1 / v;
    }

    private static TransistorModel transistorBase(JSONObject o, String where, Context ctx, List<Problem> problems) {
        String from = fromName(o, where, problems);
        if (from == null) {
            return problems.isEmpty() ? TransistorModel.findEntry("default") : null;
        }
        Definition p = ctx == null ? null : ctx.pending(TRANSISTOR, from);
        if (p != null && p.transistor() != null) {
            return p.transistor();
        }
        TransistorModel tm = TransistorModel.findEntry(from);
        if (tm == null || tm.isInternal()) {
            problems.add(unknownFrom(where, from, TRANSISTOR));
            return null;
        }
        return tm;
    }

    // ---------------------------------------------------------------- logic

    /**
     * [SP_AGA_01_13] Logic ModelSpec: pins (1..32 per side, the pin-name pattern, not empty after
     * the markup is removed, unique within the model as given), rules (1..256 lines of at most 100
     * chars, parsed by the editor's parser; a bad line names {@code rules[<i>]}), info (at most 200
     * chars, default the name). Flags are 0, as the editor creates a model.
     */
    private static Definition decodeLogicSpec(JSONObject o, String name, String where, List<Problem> problems) {
        List<String> inputs = stringList(o, "inputs", where, true, problems);
        List<String> outputs = stringList(o, "outputs", where, true, problems);
        List<String> rules = stringList(o, "rules", where, true, problems);
        String info = name;
        if (o.get("info") != null) {
            if (o.get("info").isString() == null) {
                problems.add(invalid(where + ".info", "must be a string", "Pass the info text, or omit it."));
            } else {
                info = o.get("info").isString().stringValue();
                if (info.length() > MAX_INFO_CHARS) {
                    problems.add(invalid(where + ".info", "has " + info.length() + " characters; at most " + MAX_INFO_CHARS
                            + " are allowed", "Shorten the info text."));
                }
            }
        }
        if (!problems.isEmpty()) {
            return null;
        }
        List<String> seen = new ArrayList<>();
        pins(inputs, "inputs", where, seen, problems);
        pins(outputs, "outputs", where, seen, problems);
        String rw = where + ".rules";
        if (rules.isEmpty() || rules.size() > MAX_RULES) {
            problems.add(invalid(rw, "must hold 1 to " + MAX_RULES + " rule lines (got " + rules.size() + ")",
                    "Pass the truth table as rule lines such as \"11=1\"; '#' lines are comments."));
        }
        for (int i = 0; i < rules.size(); i++) {
            String r = rules.get(i);
            if (r.indexOf('\n') >= 0 || r.indexOf('\r') >= 0) {
                problems.add(invalid(rw + "[" + i + "]", "must be one line", "Pass each rule line as its own list entry."));
            } else if (r.length() > MAX_RULE_CHARS) {
                problems.add(invalid(rw + "[" + i + "]", "has " + r.length() + " characters; at most " + MAX_RULE_CHARS
                        + " are allowed", "Split the rule or use pattern letters."));
            }
        }
        if (!problems.isEmpty()) {
            return null;
        }
        String[] in = inputs.toArray(new String[0]);
        String[] out = outputs.toArray(new String[0]);
        CustomLogicModel lm = CustomLogicModel.createDetached(name, in, out, info, String.join("\n", rules));
        if (lm.getRuleError() != null) {
            int line = Math.max(0, lm.getRuleErrorLine());
            problems.add(invalid(rw + "[" + line + "]", "does not parse: " + lm.getRuleError() + " ('"
                    + clip(rules.get(Math.min(line, rules.size() - 1))) + "')", ruleHint(in.length, out.length)));
            return null;
        }
        return new Definition(LOGIC, name, lm.modelLine(), lm);
    }

    /** Validates one side's pin names into {@code problems}; {@code seen} collects the names so far. */
    private static void pins(List<String> names, String key, String where, List<String> seen, List<Problem> problems) {
        String w = where + "." + key;
        if (names.isEmpty() || names.size() > MAX_LOGIC_PINS) {
            problems.add(invalid(w, "must hold 1 to " + MAX_LOGIC_PINS + " pin names (got " + names.size() + ")",
                    "Pass the " + key + " as a list of pin names, e.g. [\"A\", \"B\"]."));
            return;
        }
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            String wi = w + "[" + i + "]";
            if (!n.matches(PIN_PATTERN)) {
                problems.add(invalid(wi, "is not a valid pin name: '" + clip(n) + "'",
                        "Use 1-8 characters: letters, digits, '_', '+', '-', and the markup '/', '#', ':'."));
            } else if (ChipElm.pinText(n).isEmpty()) {
                problems.add(invalid(wi, "is empty after its markup is removed: '" + clip(n) + "'",
                        "Give the pin a text after the markup; a clock input is written like \"CLK:C\"."));
            } else if (seen.contains(n)) {
                problems.add(invalid(wi, "repeats the pin name '" + clip(n) + "'", "Pin names are unique within the model."));
            }
            seen.add(n);
        }
    }

    private static String ruleHint(int inputs, int outputs) {
        return "A rule is left=right: the left side has " + inputs + " to " + (inputs + outputs)
                + " characters (0, 1, ? = any, + = rising, - = falling, or a pattern letter), one per input then per output; "
                + "the right side has " + outputs + " (0, 1, _ = high impedance, or a pattern letter of the left side).";
    }

    // ---------------------------------------------------------------- ModelText

    private static Definition decodeText(JSONObject o, String kind, String name, String where, List<Problem> problems) {
        for (String key : o.keySet()) {
            if (!"kind".equals(key) && !"name".equals(key) && !"modelText".equals(key)) {
                problems.add(invalid(where + "." + clip(key), "is not a field of a ModelText", "A ModelText is {kind, name, modelText}."));
            }
        }
        if (name.isEmpty() || name.startsWith("~")) {
            problems.add(invalid(where + ".name", "must be non-empty and must not start with '~'",
                    "Use the name the model line carries."));
        }
        JSONString t = o.get("modelText").isString();
        if (t == null) {
            problems.add(invalid(where + ".modelText", "must be a string", "Pass one model line of the text format."));
        }
        if (!problems.isEmpty()) {
            return null;
        }
        String text = t.stringValue();
        if (text.endsWith("\n")) {
            text = text.substring(0, text.length() - 1);
        }
        String mw = where + ".modelText";
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            problems.add(invalid(mw, "must be exactly one model line", "Pass one line (a single trailing newline is allowed)."));
            return null;
        }
        StringTokenizer st = new StringTokenizer(text, DELIMITERS);
        String token = st.hasMoreTokens() ? st.nextToken() : null;
        if (!lineToken(kind).equals(token)) {
            problems.add(invalid(mw, "must start with '" + lineToken(kind) + "', the line type of a " + kind + " model",
                    "Use the model line of the text format for this kind."));
            return null;
        }
        String lineName = st.hasMoreTokens() ? CustomLogicModel.unescape(st.nextToken()) : null;
        if (!name.equals(lineName)) {
            problems.add(invalid(mw, "names the model '" + clip(String.valueOf(lineName)) + "', not '" + clip(name) + "'",
                    "Make name equal to the (unescaped) name token of the line."));
            return null;
        }
        try {
            switch (kind) {
                case DIODE: {
                    DiodeModel dm = DiodeModel.undumpDetached(name, st);
                    return new Definition(kind, name, dm.modelLine(), dm);
                }
                case TRANSISTOR: {
                    TransistorModel tm = TransistorModel.undumpDetached(name, st);
                    return new Definition(kind, name, tm.modelLine(), tm);
                }
                case LOGIC: {
                    // [SP_AGA_01_13] "Line": the rules are validated by the editor's parser, never alerted
                    CustomLogicModel lm = CustomLogicModel.undumpDetached(name, st);
                    // a bad rule rejects a new entry only: the session may hold this very line (an
                    // editor-made model keeps rules that do not parse), and an identical line is accepted
                    Problem bad = lm.getRuleError() == null ? null
                            : invalid(mw, "has rules that do not parse at rule line " + (lm.getRuleErrorLine() + 1)
                                    + ": " + lm.getRuleError(), ruleHint(lm.inputs.length, lm.outputs.length));
                    return new Definition(kind, name, lm.modelLine(), lm, bad, null);
                }
                default: {
                    // [SP_AGA_01_13] the inner references are checked after the identity test
                    CustomCompositeModel cm = CustomCompositeModel.undumpDetached(name, st);
                    return new Definition(kind, name, cm.modelLine(), cm, null, mw);
                }
            }
        } catch (RuntimeException e) {
            problems.add(invalid(mw, "has a field that does not parse" + (e.getMessage() != null ? " (" + clip(e.getMessage()) + ")" : ""),
                    "Pass the model line exactly as the text format writes it."));
            return null;
        }
    }

    /**
     * The model line that the fields of a text model line produce when the text importer loads
     * them (after its line type and name), without any catalogue write.
     *
     * @throws RuntimeException when a field is missing or does not parse
     */
    public static String normalizedLine(String kind, String name, StringTokenizer st) {
        switch (kind) {
            case DIODE:
                return DiodeModel.undumpDetached(name, st).modelLine();
            case TRANSISTOR:
                return TransistorModel.undumpDetached(name, st).modelLine();
            case LOGIC:
                return CustomLogicModel.normalizedLine(name, st);
            default:
                return CustomCompositeModel.normalizedLine(name, st);
        }
    }

    /** @return the kind of a text model line type (34, 32, '!', '.'), or null */
    public static String kindOfLineType(int typeId) {
        switch (typeId) {
            case 34:
                return DIODE;
            case 32:
                return TRANSISTOR;
            case '!':
                return LOGIC;
            case '.':
                return SUBCIRCUIT;
            default:
                return null;
        }
    }

    // ---------------------------------------------------------------- encoding

    /**
     * [SP_AGA_01_13] Record parameters of a diode or transistor entry in the output form: quantity
     * keys as lossless unit strings, number keys as JSON numbers, infinite values as {@code "inf"};
     * a diode record carries the four core keys, plus {@code forward_voltage} (derived at the stored
     * forward current) and {@code forward_current} for a simple model. Null for other kinds.
     */
    public static JSONObject parameters(Object entry) {
        JSONObject p = new JSONObject();
        if (entry instanceof DiodeModel) {
            DiodeModel dm = (DiodeModel) entry;
            p.put("saturation_current", quantity(dm.saturationCurrent, "A"));
            p.put("series_resistance", quantity(dm.seriesResistance, "Ohm"));
            p.put("emission_coefficient", number(dm.emissionCoefficient));
            p.put("breakdown_voltage", quantity(dm.breakdownVoltage, "V"));
            if (dm.isSimple() && dm.getForwardCurrent() > 0) {
                p.put("forward_voltage", quantity(dm.forwardVoltageAt(dm.getForwardCurrent()), "V"));
                p.put("forward_current", quantity(dm.getForwardCurrent(), "A"));
            }
            return p;
        }
        if (entry instanceof TransistorModel) {
            TransistorModel tm = (TransistorModel) entry;
            p.put("saturation_current", quantity(tm.satCur, "A"));
            p.put("beta_reverse", number(tm.betaR));
            p.put("emission_coefficient_forward", number(tm.emissionCoeffF));
            p.put("emission_coefficient_reverse", number(tm.emissionCoeffR));
            p.put("leakage_be_current", quantity(tm.BEleakCur, "A"));
            p.put("leakage_bc_current", quantity(tm.BCleakCur, "A"));
            p.put("leakage_be_emission", number(tm.leakBEemissionCoeff));
            p.put("leakage_bc_emission", number(tm.leakBCemissionCoeff));
            p.put("early_voltage_forward", infinite(tm.invEarlyVoltF, "V"));
            p.put("early_voltage_reverse", infinite(tm.invEarlyVoltR, "V"));
            p.put("knee_current_forward", infinite(tm.invRollOffF, "A"));
            p.put("knee_current_reverse", infinite(tm.invRollOffR, "A"));
            return p;
        }
        return null;
    }

    /**
     * [SP_AGA_02_05] "Form": the entry as a ModelSpec when its ModelSpec passes validation and
     * reproduces the entry's model line exactly, otherwise (and always for subcircuit models) as
     * a ModelText. Null when the catalogue has no such entry.
     */
    public static JSONObject encode(String kind, String name) {
        Object entry = entry(kind, name);
        if (entry == null) {
            return null;
        }
        String line = lineOf(entry);
        JSONObject spec = name.matches(NAME_PATTERN) ? specOf(kind, name, entry) : null;
        if (spec != null) {
            List<Problem> problems = new ArrayList<>();
            Definition d = decode(spec, "model", null, problems);
            if (d != null && line.equals(d.line)) {
                return spec;
            }
        }
        JSONObject text = new JSONObject();
        text.put("kind", new JSONString(kind));
        text.put("name", new JSONString(name));
        text.put("modelText", new JSONString(line));
        return text;
    }

    /** @return the ModelSpec fields of a diode, transistor or logic entry (unvalidated), or null */
    private static JSONObject specOf(String kind, String name, Object entry) {
        JSONObject spec = new JSONObject();
        spec.put("kind", new JSONString(kind));
        spec.put("name", new JSONString(name));
        JSONObject params = parameters(entry);
        if (params != null) {
            spec.put("parameters", params);
            return spec;
        }
        if (entry instanceof CustomLogicModel) {
            CustomLogicModel lm = (CustomLogicModel) entry;
            spec.put("inputs", strings(lm.inputs));
            spec.put("outputs", strings(lm.outputs));
            spec.put("rules", strings(ruleLines(lm.getRules())));
            spec.put("info", new JSONString(lm.infoText == null ? "" : lm.infoText));
            return spec;
        }
        return null;
    }

    /**
     * [SP_AGA_01_13] ModelRecord {@code rules}: the stored rules split into lines (the trailing
     * newline the text format adds is not a line).
     */
    public static String[] ruleLines(String rules) {
        if (rules == null || rules.isEmpty()) {
            return new String[0];
        }
        String r = rules.endsWith("\n") ? rules.substring(0, rules.length() - 1) : rules;
        return r.split("\n", -1);
    }

    /** @return the strings as a JSON array */
    public static JSONArray strings(String[] values) {
        JSONArray a = new JSONArray();
        if (values != null) {
            for (int i = 0; i < values.length; i++) {
                a.set(i, new JSONString(values[i]));
            }
        }
        return a;
    }

    // ---------------------------------------------------------------- helpers

    private static JSONValue quantity(double v, String unit) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return new JSONString(String.valueOf(v));
        }
        return new JSONString(BaseCircuitElm.getJsonUnitText(v, unit));
    }

    private static JSONValue number(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return new JSONString(String.valueOf(v));
        }
        return new JSONNumber(v);
    }

    /** An inverse field (0 = infinite) in the output form. */
    private static JSONValue infinite(double inverse, String unit) {
        return inverse == 0 ? new JSONString("inf") : quantity(1 / inverse, unit);
    }

    private static String fromName(JSONObject o, String where, List<Problem> problems) {
        JSONValue f = o.get("from");
        if (f == null || f.isNull() != null) {
            return null;
        }
        if (f.isString() == null) {
            problems.add(invalid(where + ".from", "must be a model name string", "Name a listed model of the same kind."));
            return null;
        }
        return f.isString().stringValue();
    }

    private static Problem unknownFrom(String where, String from, String kind) {
        return new Problem(UNKNOWN_MODEL, where + ".from", "Argument '" + where + ".from' names no listed " + kind
                + " model: '" + clip(from) + "'.", "Use a name listModels lists for kind " + kind + ", or omit from (default: '" + "default" + "').");
    }

    private static JSONObject parameters(JSONObject o, String where, List<Problem> problems) {
        JSONValue p = o.get("parameters");
        JSONObject obj = p == null ? null : p.isObject();
        if (obj == null) {
            problems.add(invalid(where + ".parameters", p == null ? "is required" : "must be an object",
                    "Pass parameters: {key: value} (an empty object copies the from model)."));
        }
        return obj;
    }

    /**
     * Reads one parameter value: a JSON number, or a string parsed strictly (quantity keys take an
     * optional SI prefix and their unit, number keys no unit; {@code inf} where allowed).
     *
     * @param op ">" (positive) or ">=" (non-negative)
     * @return the value (positive infinity for "inf"), or null after adding a problem
     */
    private static Double value(JSONObject params, String key, String pw, String unit, boolean quantity, String op,
            boolean allowInf, Context ctx, List<Problem> problems) {
        JSONValue v = params.get(key);
        Double d = null;
        if (v != null && v.isNumber() != null) {
            double x = v.isNumber().doubleValue();
            d = Double.isNaN(x) || Double.isInfinite(x) ? null : x;
        } else if (v != null && v.isString() != null) {
            String s = v.isString().stringValue();
            if (ctx != null) {
                s = ctx.valueText(s);
            }
            if (allowInf && "inf".equals(s == null ? null : s.trim())) {
                return Double.POSITIVE_INFINITY;
            }
            d = UnitValues.parse(s, quantity ? unit : null);
        }
        String field = pw + "." + key;
        if (d == null) {
            problems.add(invalid(field, (quantity ? "must be a number or a unit string such as \"" + example(unit) + "\""
                    : "must be a number without a unit") + (allowInf ? " (or \"inf\")" : "") + " (got " + clip(String.valueOf(v)) + ")",
                    quantity ? "Use a number or a string with an SI prefix and the unit " + unit + "." : "Use a plain number."));
            return null;
        }
        boolean okRange = ">".equals(op) ? d > 0 : d >= 0;
        if (!okRange) {
            problems.add(invalid(field, "must be " + (">".equals(op) ? "> 0" : ">= 0") + (allowInf ? " or \"inf\"" : "")
                    + " (got " + clip(String.valueOf(v)) + ")", "Use a value in the parameter's range."));
            return null;
        }
        return d;
    }

    private static String example(String unit) {
        if ("A".equals(unit)) {
            return "20 mA";
        }
        if ("Ohm".equals(unit)) {
            return "1 Ohm";
        }
        return "2.1 " + unit;
    }

    private static List<String> stringList(JSONObject o, String key, String where, boolean required, List<Problem> problems) {
        JSONValue v = o.get(key);
        List<String> out = new ArrayList<>();
        JSONArray a = v == null ? null : v.isArray();
        if (a == null) {
            if (v != null || required) {
                problems.add(invalid(where + "." + key, v == null ? "is required" : "must be a list of strings",
                        "Pass " + key + " as a list of strings."));
            }
            return out;
        }
        for (int i = 0; i < a.size(); i++) {
            JSONString s = a.get(i).isString();
            if (s == null) {
                problems.add(invalid(where + "." + key + "[" + i + "]", "must be a string", "Pass " + key + " as a list of strings."));
            } else {
                out.add(s.stringValue());
            }
        }
        return out;
    }

    private static Problem invalid(String field, String problem, String hint) {
        return new Problem(INVALID_VALUE, field, "Argument '" + field + "' " + problem + ".", hint);
    }

    private static String string(JSONObject o, String key) {
        JSONValue v = o.get(key);
        return v == null || v.isString() == null ? null : v.isString().stringValue();
    }

    private static boolean contains(String[] list, String s) {
        return indexOf(list, s) >= 0;
    }

    private static int indexOf(String[] list, String s) {
        for (int i = 0; i < list.length; i++) {
            if (list[i].equals(s)) {
                return i;
            }
        }
        return -1;
    }

    /** Bounded echo of client values in messages ({@link EchoText}). */
    static String clip(String s) {
        return EchoText.clip(s);
    }
}
