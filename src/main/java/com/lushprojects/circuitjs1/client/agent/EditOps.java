package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;
import com.google.gwt.json.client.JSONValue;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.Scope;
import com.lushprojects.circuitjs1.client.ScopeManager;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.HasBuiltInSlider;
import com.lushprojects.circuitjs1.client.element.HasControlWidget;
import com.lushprojects.circuitjs1.client.element.ScopeElm;
import com.lushprojects.circuitjs1.client.io.json.CircuitElementFactory;
import com.lushprojects.circuitjs1.client.util.EchoText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [SP_AGA_02_04] applyEdits: an ordered batch of incremental edits, applied atomically
 * ([SP_AGA_DEC_01], [SP_AGA_03_04]).
 * <ol>
 * <li><b>Validate first.</b> The whole batch is validated against a model of the document's
 *     element set in which earlier edits are visible to later ones (an added ID is usable by a
 *     later edit, a deleted one is not; moves update the model geometry; scope views are
 *     counted). Any error rejects the batch with nothing applied.</li>
 * <li><b>Apply</b> inside a {@link Mutation}: agent origin, grid pinned to the document's own
 *     option, snapshot restored on any failure.</li>
 * </ol>
 * {@code set} is merge-then-apply with full read-back (steps 1-5 of SP_AGA_02_04): merged =
 * exported properties ⊕ declared conditional properties at their current values ⊕ patch;
 * applied through the element's JSON property application; geometry and nodes re-run; every key
 * read back, a patched key whose value differs from the request or an unpatched writable key whose
 * value changed is reported as {@code value_adjusted} (a read-only key follows another key or the
 * geometry, so its change is not reported). A property that selects another canonical type
 * changes the record's {@code type}; the ID stays.
 */
final class EditOps {

    /** Maximum number of edits in one batch. */
    static final int MAX_EDITS = 200;
    /** Maximum number of element records returned. */
    static final int MAX_RECORDS = 50;
    /** Maximum number of PostRefs in one markOpen edit. */
    static final int MAX_MARKS = 500;

    private EditOps() {
    }

    static void register(AgentApi api) {
        api.registerMutating("applyEdits", AgentApi.DocPolicy.OPTIONAL, AgentApi.BusyPolicy.REJECTED, EditOps::applyEdits);
    }

    // ---------------------------------------------------------------- batch model

    /** One element of the validation model. */
    private static final class Item {
        String id;
        Catalogue.TypeInfo type;
        /** The document's element (null for an element added by the batch). */
        CircuitElm live;
        String[] pins;
        double x1, y1, x2, y2;
        /** Keys of an existing element that its type's catalogue entry does not list. */
        Map<String, Catalogue.PropertyInfo> extra;
    }

    private abstract static class Edit {
        int index;
    }

    private static final class AddEdit extends Edit {
        AgentCircuitConverter.Spec spec;
    }

    private static final class MoveEdit extends Edit {
        String id;
        boolean by;
        double dx, dy;
        double[] start, end;
    }

    private static final class DeleteEdit extends Edit {
        String id;
    }

    private static final class SetEdit extends Edit {
        String id;
        final LinkedHashMap<String, Object> patch = new LinkedHashMap<>();
        Integer flags;
    }

    private static final class DescribeEdit extends Edit {
        String id;
        String text;
    }

    private static final class ScopeEdit extends Edit {
        String id;
        boolean add;
        int value = -1;
    }

    /** [SP_AGA_02_04] defineModel: a validated model definition ([SP_AGA_03_11]). */
    private static final class DefineEdit extends Edit {
        ModelOps.Planned planned;
    }

    private static final class MarkEdit extends Edit {
        final List<String> refs = new ArrayList<>();
        boolean open;
    }

    /** The validation model: the element set as the batch changes it. */
    private static final class Model {
        final Map<String, Item> items = new LinkedHashMap<>();
        /** Docked scope views as the sets of element IDs they plot. */
        final List<Set<String>> scopeViews = new ArrayList<>();
        int maxScopes;
    }

    // ---------------------------------------------------------------- contract

    static OperationResult applyEdits(AgentApi.Call call) {
        JSONArray edits = call.args.optArray("edits", 1, MAX_EDITS);
        if (edits == null && !call.args.has("edits")) {
            call.args.invalid("edits", "is required", "Pass a list of 1 to " + MAX_EDITS + " edits.");
        }
        if (call.args.failed()) {
            return call.args.failure();
        }
        final Catalogue cat = call.sim.getAgentCatalogue();
        final CircuitDocument doc = call.doc;
        Model model = buildModel(doc, cat);
        List<Issue> issues = new ArrayList<>();
        final List<Edit> plan = new ArrayList<>();
        // [SP_AGA_03_11] names = session ∪ batch: a new model is visible to the later edits
        ModelOps.Scope scope = new ModelOps.Scope(call.sim, call.doc);
        ModelNames.beginScope(scope);
        try {
            for (int i = 0; i < edits.size(); i++) {
                Edit e = validate(edits.get(i), i, model, cat, scope, issues);
                if (e != null) {
                    plan.add(e);
                }
            }
        } finally {
            ModelNames.endScope();
        }
        if (!issues.isEmpty()) {
            // nothing applied, catalogues untouched
            return OperationResult.failure(issues);
        }
        return Mutation.run(call.sim, doc, ctx -> apply(ctx, plan, cat));
    }

    private static Model buildModel(CircuitDocument doc, Catalogue cat) {
        Model m = new Model();
        for (CircuitElm elm : doc.simulator.elmList) {
            Item it = new Item();
            it.id = elm.getElementId();
            it.live = elm;
            it.type = cat.find(elm.getJsonTypeName());
            it.pins = PinNames.of(elm);
            it.x1 = CellGeometry.toCells(elm.getX());
            it.y1 = CellGeometry.toCells(elm.getY());
            it.x2 = CellGeometry.toCells(elm.getX2());
            it.y2 = CellGeometry.toCells(elm.getY2());
            m.items.put(it.id, it);
        }
        ScopeManager sm = doc.scopeManager;
        m.maxScopes = sm.getMaxScopes();
        for (int i = 0; i < sm.getScopeCount(); i++) {
            Scope s = sm.getScope(i);
            Set<String> view = new HashSet<>();
            if (s != null && s.plots != null) {
                for (int p = 0; p < s.plots.size(); p++) {
                    CircuitElm e = s.plots.get(p).getElm();
                    if (e != null) {
                        view.add(e.getElementId());
                    }
                }
            }
            if (!view.isEmpty()) {
                m.scopeViews.add(view);
            }
        }
        return m;
    }

    // ---------------------------------------------------------------- validation

    private static final String OPS = "add, move, delete, set, describe, addScope, removeScope, markOpen, defineModel";

    private static Edit validate(JSONValue v, int i, Model m, Catalogue cat, ModelOps.Scope scope, List<Issue> issues) {
        String where = "edits[" + i + "]";
        JSONObject o = v == null ? null : v.isObject();
        JSONString opv = o == null || o.get("op") == null ? null : o.get("op").isString();
        if (opv == null) {
            issues.add(Issue.of(IssueCode.INVALID_VALUE, "Argument '" + where + ".op' is required.",
                    "Use one of: " + OPS + "."));
            return null;
        }
        String op = opv.stringValue();
        int before = issues.size();
        Edit e;
        switch (op) {
            case "add":
                e = validateAdd(o, where, i, m, cat, scope, issues);
                break;
            case "move":
                e = validateMove(o, where, m, issues);
                break;
            case "delete": {
                DeleteEdit d = new DeleteEdit();
                d.id = requireElement(o, "id", where, m, issues);
                if (d.id != null) {
                    m.items.remove(d.id);
                    removeFromViews(m, d.id);
                }
                e = d;
                break;
            }
            case "set":
                e = validateSet(o, where, m, scope, issues);
                break;
            case "describe": {
                DescribeEdit d = new DescribeEdit();
                d.id = requireElement(o, "id", where, m, issues);
                JSONValue t = o.get("description");
                if (t == null || t.isNull() != null) {
                    issues.add(Issue.of(IssueCode.INVALID_VALUE, "Argument '" + where + ".description' is required.",
                            "Pass the description text (an empty string clears it)."));
                } else {
                    d.text = AgentCircuitConverter.readDescription(t, where + ".description", issues, d.id);
                }
                e = d;
                break;
            }
            case "addScope":
            case "removeScope":
                e = validateScope(o, where, "addScope".equals(op), m, issues);
                break;
            case "markOpen":
                e = validateMark(o, where, m, issues);
                break;
            case "defineModel": {
                DefineEdit d = new DefineEdit();
                if (!present(o, "model")) {
                    issues.add(Issue.of(IssueCode.INVALID_VALUE, "Argument '" + where + ".model' is required.",
                            "Pass a ModelSpec {kind, name, from?, parameters}."));
                } else {
                    d.planned = ModelOps.validate(o.get("model"), where + ".model", scope, false, issues);
                }
                e = d;
                break;
            }
            default:
                issues.add(Issue.of(IssueCode.INVALID_VALUE, "Argument '" + where + ".op' is not one of the allowed values: '" + EchoText.clip(op) + "'.",
                        "Use one of: " + OPS + "."));
                return null;
        }
        if (issues.size() > before) {
            return null;
        }
        e.index = i;
        return e;
    }

    private static Edit validateAdd(JSONObject o, String where, int i, Model m, Catalogue cat, ModelOps.Scope scope,
            List<Issue> issues) {
        AgentCircuitConverter.Spec spec = AgentCircuitConverter.parseSpec(o.get("element"), where + ".element", i,
                CellGeometry.EDIT_LATTICE, cat, issues);
        if (spec == null) {
            return null;
        }
        if (spec.id != null && m.items.containsKey(spec.id)) {
            issues.add(Issue.of(IssueCode.ID_TAKEN, "Element ID " + spec.id + " is already used in the document ("
                    + where + ").", "Choose another ID, or omit id to generate one.").elements(spec.id));
            return null;
        }
        AddEdit a = new AddEdit();
        a.spec = spec;
        Item it = new Item();
        // a generated ID is not known yet: later edits can only name supplied IDs
        it.id = spec.id != null ? spec.id : "#add" + i;
        it.type = spec.type;
        it.pins = modelPins(spec.type, spec.given, spec.type.pins, scope);
        it.x1 = spec.x1;
        it.y1 = spec.y1;
        it.x2 = spec.x2;
        it.y2 = spec.y2;
        m.items.put(it.id, it);
        return a;
    }

    private static Edit validateMove(JSONObject o, String where, Model m, List<Issue> issues) {
        MoveEdit mv = new MoveEdit();
        mv.id = requireElement(o, "id", where, m, issues);
        boolean hasBy = present(o, "by");
        boolean hasStart = present(o, "start");
        boolean hasEnd = present(o, "end");
        if (hasBy == hasStart || (hasBy && hasEnd)) {
            issues.add(Issue.of(IssueCode.INVALID_VALUE, "Argument '" + where + "' needs either start (and optional end) or by.",
                    "Pass {op: \"move\", id, by: {dx, dy}} or {op: \"move\", id, start, end?}."));
            return null;
        }
        if (mv.id == null) {
            return null;
        }
        Item it = m.items.get(mv.id);
        double nx1, ny1, nx2, ny2;
        if (hasBy) {
            JSONObject by = o.get("by").isObject();
            double[] d = by == null ? null : new double[] { number(by, "dx"), number(by, "dy") };
            if (d == null || Double.isNaN(d[0]) || Double.isNaN(d[1])) {
                issues.add(Issue.of(IssueCode.INVALID_VALUE, "Argument '" + where + ".by' must be {dx, dy} in cells.",
                        "Pass e.g. {\"dx\": 2, \"dy\": 0}.").elements(mv.id));
                return null;
            }
            for (int k = 0; k < 2; k++) {
                if (!CellGeometry.onLattice(d[k], CellGeometry.EDIT_LATTICE)) {
                    issues.add(Issue.of(IssueCode.OFF_LATTICE, "Argument '" + where + ".by." + (k == 0 ? "dx" : "dy")
                            + "' = " + d[k] + " is not a multiple of 0.5 cell.", "Use a multiple of 0.5 cell.").elements(mv.id));
                    return null;
                }
            }
            mv.by = true;
            mv.dx = d[0];
            mv.dy = d[1];
            nx1 = it.x1 + d[0];
            ny1 = it.y1 + d[1];
            nx2 = it.x2 + d[0];
            ny2 = it.y2 + d[1];
        } else {
            mv.start = CellGeometry.readPoint(o.get("start"), where + ".start", CellGeometry.EDIT_LATTICE, issues, mv.id);
            if (hasEnd) {
                mv.end = CellGeometry.readPoint(o.get("end"), where + ".end", CellGeometry.EDIT_LATTICE, issues, mv.id);
                if (mv.end == null) {
                    return null;
                }
            }
            if (mv.start == null) {
                return null;
            }
            nx1 = mv.start[0];
            ny1 = mv.start[1];
            if (mv.end != null) {
                nx2 = mv.end[0];
                ny2 = mv.end[1];
                if (nx1 == nx2 && ny1 == ny2) {
                    issues.add(Issue.of(IssueCode.ZERO_LENGTH, "Move of " + mv.id + " gives end = start.",
                            "Give an end point different from start.").elements(mv.id).at(nx1, ny1));
                    return null;
                }
            } else {
                nx2 = it.x2 + (nx1 - it.x1);
                ny2 = it.y2 + (ny1 - it.y1);
            }
        }
        if (Math.max(Math.max(Math.abs(nx1), Math.abs(ny1)), Math.max(Math.abs(nx2), Math.abs(ny2))) > CellGeometry.MAX_CELLS) {
            issues.add(Issue.of(IssueCode.INVALID_VALUE, "Move of " + mv.id + " puts a point outside ±4096 cells (" + where + ").",
                    "Keep coordinates within -4096 to 4096 cells.").elements(mv.id));
            return null;
        }
        it.x1 = nx1;
        it.y1 = ny1;
        it.x2 = nx2;
        it.y2 = ny2;
        return mv;
    }

    private static Edit validateSet(JSONObject o, String where, Model m, ModelOps.Scope scope, List<Issue> issues) {
        SetEdit s = new SetEdit();
        s.id = requireElement(o, "id", where, m, issues);
        boolean hasProps = present(o, "properties");
        boolean hasFlags = present(o, "flags");
        if (!hasProps && !hasFlags) {
            issues.add(Issue.of(IssueCode.INVALID_VALUE, "Argument '" + where + ".properties' is required.",
                    "Pass {key: value} with keys from describeType (and optional flags)."));
            return null;
        }
        if (s.id == null) {
            return null;
        }
        Item it = m.items.get(s.id);
        if (hasProps) {
            JSONObject props = o.get("properties").isObject();
            if (props == null) {
                issues.add(Issue.of(IssueCode.INVALID_VALUE, "Argument '" + where + ".properties' must be an object.",
                        "Pass {key: value} with keys from describeType.").elements(s.id));
                return null;
            }
            AgentCircuitConverter.readProperties(props, where + ".properties", it.type, extraKeys(it), false, s.patch,
                    issues, s.id);
            it.pins = modelPins(it.type, s.patch, it.pins, scope);
        }
        s.flags = AgentCircuitConverter.readFlags(o.get("flags"), where + ".flags", issues, s.id);
        return s;
    }

    /**
     * [SP_AGA_02_04] "earlier edits visible to later ones": a {@code CustomLogic} or
     * {@code Subcircuit} whose {@code model_name} the edit gives has the posts of that model
     * (session entry or a {@code defineModel} earlier in the batch) for the later edits of the batch.
     */
    private static String[] modelPins(Catalogue.TypeInfo type, Map<String, Object> props, String[] current,
            ModelOps.Scope scope) {
        Object name = props.get("model_name");
        if (type != null && name instanceof String) {
            String[] pins = "CustomLogic".equals(type.type) ? ModelOps.logicPins((String) name, scope)
                    : "Subcircuit".equals(type.type) ? ModelOps.subcircuitPins((String) name, scope) : null;
            if (pins != null) {
                return pins;
            }
        }
        return current;
    }

    /** Keys an existing element exports beyond its catalogue entry (kind inferred from the value). */
    private static Map<String, Catalogue.PropertyInfo> extraKeys(Item it) {
        if (it.extra != null || it.live == null) {
            return it.extra;
        }
        it.extra = new HashMap<>();
        CircuitElm elm = it.live;
        java.util.Set<String> readOnly = elm.getJsonReadOnlyProperties();
        for (Map.Entry<String, Object> e : PropertyValues.current(elm).entrySet()) {
            if (it.type != null && it.type.property(e.getKey()) != null) {
                continue;
            }
            Catalogue.PropertyInfo p = Catalogue.describeValue(e.getKey(), e.getValue());
            if (p != null) {
                p.readOnly = readOnly.contains(p.key);
                it.extra.put(p.key, p);
            }
        }
        return it.extra;
    }

    private static Edit validateScope(JSONObject o, String where, boolean add, Model m, List<Issue> issues) {
        ScopeEdit s = new ScopeEdit();
        s.add = add;
        s.id = requireElement(o, "element", where, m, issues);
        JSONValue q = o.get("quantity");
        if (add && q != null && q.isNull() == null) {
            s.value = AgentCircuitConverter.scopeValue(q.isString() == null ? null : q.isString().stringValue());
            if (s.value < 0) {
                issues.add(Issue.of(IssueCode.INVALID_VALUE, "Argument '" + where + ".quantity' is not one of the allowed values.",
                        "Use one of: voltage, current, power."));
                return null;
            }
        }
        if (s.id == null) {
            return null;
        }
        if (add) {
            if (m.scopeViews.size() >= m.maxScopes) {
                issues.add(Issue.of(IssueCode.SCOPE_LIMIT, "No free scope slot for " + s.id + " (" + where + "): all "
                        + m.maxScopes + " scope views are used.", "Remove a scope view first (removeScope).").elements(s.id));
                return null;
            }
            Set<String> view = new HashSet<>();
            view.add(s.id);
            m.scopeViews.add(view);
        } else {
            removeFromViews(m, s.id);
        }
        return s;
    }

    private static Edit validateMark(JSONObject o, String where, Model m, List<Issue> issues) {
        MarkEdit mk = new MarkEdit();
        JSONValue pv = o.get("posts");
        JSONArray posts = pv == null ? null : pv.isArray();
        if (posts == null || posts.size() < 1 || posts.size() > MAX_MARKS) {
            issues.add(Issue.of(IssueCode.INVALID_VALUE, "Argument '" + where + ".posts' must be a list of 1 to " + MAX_MARKS
                    + " PostRefs.", "Pass e.g. [\"R1.pin2\"] or [\"R1.#1\"]."));
            return null;
        }
        JSONValue ov = o.get("open");
        if (ov != null && ov.isNull() == null && ov.isBoolean() == null) {
            issues.add(Issue.of(IssueCode.INVALID_VALUE, "Argument '" + where + ".open' must be true or false.",
                    "Pass open: false to remove marks, or omit it."));
            return null;
        }
        mk.open = ov == null || ov.isBoolean() == null || ov.isBoolean().booleanValue();
        for (int k = 0; k < posts.size(); k++) {
            JSONString s = posts.get(k).isString();
            String ref = s == null ? null : s.stringValue();
            String elmId = OpenMarks.elementOf(ref);
            Item it = elmId == null ? null : m.items.get(elmId);
            if (it == null) {
                issues.add(Issue.of(IssueCode.UNKNOWN_POST, "Argument '" + where + ".posts[" + k + "]' names no post of the document: "
                        + (s == null ? EchoText.clip(String.valueOf(posts.get(k))) : "'" + EchoText.clip(ref) + "'") + ".", "Use <ElementId>.<PinName> or <ElementId>.#<index>; getCircuit lists the posts."));
                continue;
            }
            String pin = OpenMarks.resolvePin(ref, it.pins);
            if (pin == null) {
                issues.add(Issue.of(IssueCode.UNKNOWN_POST, "Element " + elmId + " has no post '" + EchoText.clip(ref.substring(elmId.length() + 1))
                        + "' (" + where + ".posts[" + k + "]).", "Its pins are: " + String.join(", ", it.pins) + ".")
                        .elements(elmId));
                continue;
            }
            mk.refs.add(ref);
        }
        return mk;
    }

    private static String requireElement(JSONObject o, String key, String where, Model m, List<Issue> issues) {
        JSONValue v = o.get(key);
        JSONString s = v == null ? null : v.isString();
        if (s == null) {
            issues.add(Issue.of(IssueCode.INVALID_VALUE, "Argument '" + where + "." + key + "' is required.",
                    "Pass the ElementId of an element of the document."));
            return null;
        }
        if (!m.items.containsKey(s.stringValue()) || s.stringValue().startsWith("#")) {
            issues.add(CircuitView.unknownElement(s.stringValue(), where + "." + key));
            return null;
        }
        return s.stringValue();
    }

    private static void removeFromViews(Model m, String id) {
        for (int k = m.scopeViews.size() - 1; k >= 0; k--) {
            Set<String> view = m.scopeViews.get(k);
            if (view.remove(id) && view.isEmpty()) {
                m.scopeViews.remove(k);
            }
        }
    }

    private static boolean present(JSONObject o, String key) {
        JSONValue v = o.get(key);
        return v != null && v.isNull() == null;
    }

    private static double number(JSONObject o, String key) {
        JSONValue v = o.get(key);
        JSONNumber n = v == null ? null : v.isNumber();
        if (n == null || Double.isInfinite(n.doubleValue())) {
            return Double.NaN;
        }
        return n.doubleValue();
    }

    // ---------------------------------------------------------------- application

    private static OperationResult apply(Mutation.Context ctx, List<Edit> plan, Catalogue cat) {
        CircuitDocument doc = ctx.doc;
        Map<String, CircuitElm> byId = CircuitView.byId(doc);
        List<Issue> warnings = new ArrayList<>();
        List<String> created = new ArrayList<>();
        Set<String> touched = new LinkedHashSet<>();
        List<ModelOps.Planned> defined = new ArrayList<>();
        boolean sliders = false;

        // [SP_AGA_03_02] Every supplied ID raises its counter before any ID is generated
        for (Edit e : plan) {
            if (e instanceof AddEdit && ((AddEdit) e).spec.id != null) {
                doc.raiseIdCounter(((AddEdit) e).spec.id);
            }
        }
        int applied = 0;
        for (Edit e : plan) {
            if (e instanceof AddEdit) {
                AgentCircuitConverter.Spec spec = ((AddEdit) e).spec;
                CircuitElm elm = CircuitElementFactory.createFromJson(spec.type.type,
                        AgentCircuitConverter.toJsonElement(spec), doc);
                if (elm == null) {
                    throw new Mutation.Rejected(Issue.of(IssueCode.INVALID_VALUE, "Element " + spec.subject + " of type "
                            + spec.type.type + " could not be created (edits[" + e.index + "]).",
                            "Check its points and properties against describeType.").elements(spec.subject));
                }
                elm.setCircuitDocument(doc);
                String id = spec.id != null ? spec.id : doc.nextElementId(elm.getIdPrefix());
                elm.setElementId(id);
                doc.simulator.elmList.add(elm);
                byId.put(id, elm);
                created.add(id);
                touched.add(id);
                sliders |= elm instanceof HasBuiltInSlider || elm instanceof HasControlWidget;
                if (spec.flags != null) {
                    // the TypeInfo defaults must not override explicitly given flag bits
                    applyExplicitFlags(elm, spec.flags, warnings);
                }
                reportAdjusted(id, spec.type, spec.given, null, PropertyValues.current(elm), warnings);
                Issue collapsed = CellGeometry.checkPlacement(elm, spec.type,
                        spec.endGiven ? new double[] { spec.x2, spec.y2 } : null, id, warnings);
                if (collapsed != null) {
                    throw new Mutation.Rejected(CellGeometry.rejection(collapsed, warnings));
                }
            } else if (e instanceof MoveEdit) {
                MoveEdit mv = (MoveEdit) e;
                CircuitElm elm = byId.get(mv.id);
                if (mv.by) {
                    elm.move(CellGeometry.toPx(mv.dx), CellGeometry.toPx(mv.dy));
                } else if (mv.end == null) {
                    elm.move(CellGeometry.toPx(mv.start[0]) - elm.getX(), CellGeometry.toPx(mv.start[1]) - elm.getY());
                } else {
                    elm.setEndpoints(CellGeometry.toPx(mv.start[0]), CellGeometry.toPx(mv.start[1]),
                            CellGeometry.toPx(mv.end[0]), CellGeometry.toPx(mv.end[1]));
                    elm.setPoints();
                    Issue collapsed = CellGeometry.checkPlacement(elm, cat.find(elm.getJsonTypeName()), mv.end, mv.id,
                            warnings);
                    if (collapsed != null) {
                        throw new Mutation.Rejected(CellGeometry.rejection(collapsed, warnings));
                    }
                }
                touched.add(mv.id);
            } else if (e instanceof DeleteEdit) {
                String id = ((DeleteEdit) e).id;
                CircuitElm elm = byId.remove(id);
                Issue removed = delete(doc, elm, byId);
                if (removed != null) {
                    warnings.add(removed);
                }
                sliders |= elm instanceof HasBuiltInSlider || elm instanceof HasControlWidget;
                touched.remove(id);
            } else if (e instanceof SetEdit) {
                SetEdit s = (SetEdit) e;
                CircuitElm elm = byId.get(s.id);
                set(elm, s, cat, warnings);
                sliders |= elm instanceof HasBuiltInSlider || elm instanceof HasControlWidget;
                touched.add(s.id);
            } else if (e instanceof DescribeEdit) {
                DescribeEdit d = (DescribeEdit) e;
                byId.get(d.id).setDescription(d.text);
            } else if (e instanceof ScopeEdit) {
                ScopeEdit s = (ScopeEdit) e;
                CircuitElm elm = byId.get(s.id);
                if (s.add) {
                    if (doc.scopeManager.addScopeView(elm, s.value) < 0) {
                        throw new Mutation.Rejected(Issue.of(IssueCode.SCOPE_LIMIT, "No free scope slot for " + s.id
                                + " (edits[" + e.index + "]).", "Remove a scope view first (removeScope).").elements(s.id));
                    }
                } else {
                    doc.scopeManager.removeScopeViews(elm);
                }
            } else if (e instanceof DefineEdit) {
                ModelOps.Planned p = ((DefineEdit) e).planned;
                // [SP_AGA_02_04] the restorer is recorded before the entry is written; a failure
                // later in the batch runs it (newest first) before the snapshot is restored
                Runnable restorer = ModelOps.define(p);
                if (restorer != null) {
                    ctx.onRollback(restorer);
                }
                defined.add(p);
            } else if (e instanceof MarkEdit) {
                MarkEdit mk = (MarkEdit) e;
                for (String ref : mk.refs) {
                    String elmId = OpenMarks.elementOf(ref);
                    CircuitElm elm = byId.get(elmId);
                    String pin = elm == null ? null : OpenMarks.resolvePin(ref, PinNames.of(elm));
                    if (pin == null) {
                        // the post set changed earlier in the batch (a configuration set)
                        throw new Mutation.Rejected(Issue.of(IssueCode.UNKNOWN_POST, "Post '" + EchoText.clip(ref) + "' does not exist after the earlier edits (edits["
                                + e.index + "]).", "Mark the posts the element has after the change.").elements(elmId));
                    }
                    doc.setOpenMark(elmId + "." + pin, mk.open);
                }
            }
            applied++;
            if (applied == 1) {
                ctx.checkForcedFailure("after the first edit of applyEdits");
            }
        }
        if (sliders) {
            // RULE_STRUCT_010: built-in sliders and control rows are managed per document
            doc.adjustableManager.updateSliders();
        }
        OpenMarks.prune(doc);
        Mutation.finish(ctx);

        JSONObject data = new JSONObject();
        data.put("applied", new JSONNumber(applied));
        JSONArray ids = new JSONArray();
        for (int i = 0; i < created.size(); i++) {
            ids.set(i, new JSONString(created.get(i)));
        }
        data.put("created", ids);
        JSONArray records = new JSONArray();
        // Mutation.finish analysed the document; after a failed analysis no PostRecord gets a net.
        // The report is the one of the ConnectivityDelta (one analysis, nothing changes after it).
        Connectivity.Nets nets = touched.isEmpty() ? null : ctx.analyseFinal().nets;
        if (doc.isAnalysisFailed()) {
            nets = null;
        }
        int truncated = 0;
        for (String id : touched) {
            CircuitElm elm = byId.get(id);
            if (elm == null) {
                continue;
            }
            if (records.size() < MAX_RECORDS) {
                records.set(records.size(), CircuitView.record(elm, doc, cat, true, nets));
            } else {
                truncated++;
            }
        }
        data.put("elements", records);
        data.put("truncated", new JSONNumber(truncated));
        if (!defined.isEmpty()) {
            data.put("models", ModelOps.records(ctx.sim, defined));
        }
        OperationResult result = OperationResult.success(data);
        for (Issue w : warnings) {
            result.addIssue(w);
        }
        return result;
    }

    /**
     * Removes {@code elm}, its scope views (docked plots and floating scope elements left without
     * plots) and its open marks.
     *
     * @return the {@code scope_removed} info issue, or null when no view showed the element
     */
    private static Issue delete(CircuitDocument doc, CircuitElm elm, Map<String, CircuitElm> byId) {
        String id = elm.getElementId();
        int docked = doc.scopeManager.removeScopeViews(elm);
        doc.circuitEditor.forgetElement(elm);
        elm.delete();
        doc.simulator.elmList.remove(elm);
        OpenMarks.removeElement(doc, id);
        // floating scope elements that only showed this element go with it
        List<String> floating = new ArrayList<>();
        Set<CircuitElm> scopeElms = new HashSet<>();
        for (CircuitElm e : doc.simulator.elmList) {
            if (e instanceof ScopeElm) {
                scopeElms.add(e);
            }
        }
        doc.simulator.deleteUnusedScopeElms();
        for (CircuitElm e : scopeElms) {
            if (!doc.simulator.elmList.contains(e)) {
                doc.circuitEditor.forgetElement(e);
                String sid = e.getElementId();
                floating.add(sid);
                byId.remove(sid);
                OpenMarks.removeElement(doc, sid);
            }
        }
        if (docked == 0 && floating.isEmpty()) {
            return null;
        }
        StringBuilder msg = new StringBuilder("Deleting " + id + " removed ");
        if (docked > 0) {
            msg.append(docked).append(" docked scope view").append(docked == 1 ? "" : "s");
        }
        if (!floating.isEmpty()) {
            msg.append(docked > 0 ? " and " : "").append("scope element").append(floating.size() == 1 ? " " : "s ")
                    .append(String.join(", ", floating));
        }
        msg.append('.');
        Issue issue = Issue.of(IssueCode.SCOPE_REMOVED, msg.toString(), "Add a scope view of another element with addScope if needed.")
                .elements(id);
        for (String f : floating) {
            issue.elements(f);
        }
        return issue;
    }

    /** [SP_AGA_02_04] {@code set} steps 1-5. */
    private static void set(CircuitElm elm, SetEdit s, Catalogue cat, List<Issue> warnings) {
        Catalogue.TypeInfo type = cat.find(elm.getJsonTypeName());
        LinkedHashMap<String, Object> before = PropertyValues.current(elm);
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>(before);
        merged.putAll(s.patch);
        elm.applyJsonProperties(merged);
        // An explicit flags patch is applied last, so it wins over property-backed bits; the
        // read-back below reports every property it changed
        applyExplicitFlags(elm, s.flags, warnings);
        LinkedHashMap<String, Object> after = PropertyValues.current(elm);
        reportAdjusted(elm.getElementId(), type, s.patch, before, after, warnings);
    }

    /**
     * Applies explicit raw flags after the properties (they win over property-backed bits), then
     * re-runs geometry and nodes; reports {@code value_adjusted} when the element changes them.
     */
    static void applyExplicitFlags(CircuitElm elm, Integer flags, List<Issue> warnings) {
        if (flags != null) {
            elm.applyJsonFlags(flags);
        }
        elm.refreshAfterPropertyChange();
        if (flags != null && elm.getJsonFlags() != flags) {
            warnings.add(adjusted(elm.getElementId(), "flags", (double) elm.getJsonFlags(),
                    "the requested " + flags));
        }
    }

    /**
     * Step 5: a patched key whose value differs from the parsed request, or (when {@code before}
     * is given) an unpatched key whose value changed, yields {@code value_adjusted} with the
     * effective value.
     */
    private static void reportAdjusted(String id, Catalogue.TypeInfo type, Map<String, Object> patch,
            Map<String, Object> before, Map<String, Object> after, List<Issue> warnings) {
        for (Map.Entry<String, Object> p : patch.entrySet()) {
            Object effective = after.get(p.getKey());
            String unit = unitOf(type, p.getKey());
            if (!PropertyValues.same(p.getValue(), effective, unit)) {
                warnings.add(adjusted(id, p.getKey(), effective, "the requested " + EchoText.clip(PropertyValues.display(p.getValue()))));
            }
        }
        if (before == null) {
            return;
        }
        for (Map.Entry<String, Object> b : before.entrySet()) {
            if (patch.containsKey(b.getKey()) || !isScalar(b.getValue()) || isReadOnly(type, b.getKey())) {
                continue;
            }
            Object effective = after.get(b.getKey());
            if (!PropertyValues.same(b.getValue(), effective, unitOf(type, b.getKey()))) {
                warnings.add(adjusted(id, b.getKey(), effective, "the previous " + EchoText.clip(PropertyValues.display(b.getValue()))));
            }
        }
    }

    /** A read-only key follows the geometry or another key: its change is the expected consequence. */
    private static boolean isReadOnly(Catalogue.TypeInfo type, String key) {
        Catalogue.PropertyInfo p = type == null ? null : type.property(key);
        return p != null && p.readOnly;
    }

    private static boolean isScalar(Object v) {
        return v instanceof String || v instanceof Number || v instanceof Boolean;
    }

    private static String unitOf(Catalogue.TypeInfo type, String key) {
        Catalogue.PropertyInfo p = type == null ? null : type.property(key);
        return p == null ? null : p.unit;
    }

    private static Issue adjusted(String id, String key, Object effective, String instead) {
        return Issue.of(IssueCode.VALUE_ADJUSTED, id + "." + key + " is " + (effective == null ? "no longer exported"
                : EchoText.clip(PropertyValues.display(effective))) + " instead of " + instead + ".",
                "The element clamps or derives this value; use the effective value or change related properties.")
                .elements(id);
    }
}
