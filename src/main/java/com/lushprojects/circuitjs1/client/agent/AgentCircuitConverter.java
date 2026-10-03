package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;
import com.google.gwt.json.client.JSONValue;
import com.lushprojects.circuitjs1.client.ElementIdRegistry;
import com.lushprojects.circuitjs1.client.Scope;
import com.lushprojects.circuitjs1.client.io.json.JsonCircuitFormat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [SP_AGA_01_03] ElementSpec validation and the conversion of ElementSpecs and AgentCircuits
 * ([SP_AGA_02_03]) to the JSON v2 format, which the element factory and the JSON importer read.
 * <ul>
 * <li>Cells become pixels ({@code × 16}, exact); the two defining points become the pseudo-pins
 *     {@code _startpoint} / {@code _endpoint}, so every type gets exactly the given points.</li>
 * <li>Properties not given take the TypeInfo defaults ([SP_AGA_01_05] "Defaults"); flags not
 *     given take the TypeInfo default flags.</li>
 * <li>An ElementRecord ([SP_AGA_01_04]) is accepted wherever an ElementSpec is: {@code posts} is
 *     ignored, and read-only keys are accepted and ignored.</li>
 * </ul>
 */
final class AgentCircuitConverter {

    /** Maximum number of elements of an AgentCircuit. */
    static final int MAX_ELEMENTS = 5000;
    /** Maximum number of scope views of an AgentCircuit. */
    static final int MAX_SCOPES = 20;

    /** One validated ElementSpec. */
    static final class Spec {
        /** Supplied ID, or null (generated). */
        String id;
        /** Issue subject: the ID, or {@code #<i>} for a spec without one. */
        String subject;
        Catalogue.TypeInfo type;
        double x1, y1, x2, y2;
        /** Given properties in the element's JSON value format (read-only keys dropped). */
        final LinkedHashMap<String, Object> given = new LinkedHashMap<>();
        Integer flags;
        String description;
    }

    private AgentCircuitConverter() {
    }

    /**
     * Validates one ElementSpec.
     *
     * @param v       the spec (an ElementRecord is accepted)
     * @param where   argument path for messages ({@code edits[3].element}, {@code circuit.elements[0]})
     * @param index   0-based position, used as subject {@code #<i>} when the spec has no ID
     * @param lattice {@link CellGeometry#EDIT_LATTICE} (add) or {@link CellGeometry#IMPORT_LATTICE}
     * @param issues  receives every violation
     * @return the spec, or null when it has errors
     */
    static Spec parseSpec(JSONValue v, String where, int index, double lattice, Catalogue cat, List<Issue> issues) {
        int errors = issues.size();
        JSONObject o = v == null ? null : v.isObject();
        if (o == null) {
            issues.add(Issue.of(IssueCode.INVALID_VALUE, "Argument '" + where + "' must be an ElementSpec object.",
                    "Pass {type, start, end?, properties?, id?}.").elements("#" + index));
            return null;
        }
        Spec spec = new Spec();
        spec.subject = "#" + index;
        JSONValue idv = o.get("id");
        if (idv != null && idv.isNull() == null) {
            JSONString s = idv.isString();
            if (s == null || !ElementIdRegistry.isValidId(s.stringValue())) {
                issues.add(Issue.of(IssueCode.ID_INVALID, "Argument '" + where + ".id' is not a valid ElementId: "
                        + idv + ".", "Use 1-32 characters: a letter, then letters, digits or '_' (e.g. R_load).")
                        .elements(spec.subject));
            } else {
                spec.id = s.stringValue();
                spec.subject = spec.id;
            }
        }
        JSONValue tv = o.get("type");
        JSONString ts = tv == null ? null : tv.isString();
        if (ts == null) {
            issues.add(Issue.of(IssueCode.INVALID_VALUE, "Argument '" + where + ".type' is required.",
                    "Pass a catalogue type name (listTypes).").elements(spec.subject));
            return null;
        }
        spec.type = cat.find(ts.stringValue());
        if (spec.type == null) {
            issues.add(Issue.of(IssueCode.UNKNOWN_TYPE, "Argument '" + where + ".type' names no catalogue type or alias: '"
                    + Catalogue.clipName(ts.stringValue()) + "'.", "Closest names: " + String.join(", ",
                            cat.closestNames(ts.stringValue(), Catalogue.HINT_NAMES)) + ".").elements(spec.subject));
            return null;
        }
        double[] start = CellGeometry.readPoint(o.get("start"), where + ".start", lattice, issues, spec.subject);
        double[] end = null;
        JSONValue ev = o.get("end");
        if (ev != null && ev.isNull() == null) {
            end = CellGeometry.readPoint(ev, where + ".end", lattice, issues, spec.subject);
        } else if (start != null) {
            end = new double[] { start[0] + spec.type.dx, start[1] + spec.type.dy };
            if (Math.max(Math.abs(end[0]), Math.abs(end[1])) > CellGeometry.MAX_CELLS) {
                issues.add(Issue.of(IssueCode.INVALID_VALUE, "The default end of " + spec.subject + " (start + "
                        + spec.type.type + " default size) is outside ±4096 cells (" + where + ").",
                        "Give an explicit end within -4096 to 4096 cells.").elements(spec.subject));
                end = null;
            }
        }
        if (start != null && end != null) {
            spec.x1 = start[0];
            spec.y1 = start[1];
            spec.x2 = end[0];
            spec.y2 = end[1];
            if (spec.x1 == spec.x2 && spec.y1 == spec.y2) {
                issues.add(Issue.of(IssueCode.ZERO_LENGTH, "Element " + spec.subject + " has end = start.",
                        "Give an end point different from start, or omit end for the default size.")
                        .elements(spec.subject).at(spec.x1, spec.y1));
            }
        }
        JSONValue pv = o.get("properties");
        if (pv != null && pv.isNull() == null) {
            JSONObject props = pv.isObject();
            if (props == null) {
                issues.add(Issue.of(IssueCode.INVALID_VALUE, "Argument '" + where + ".properties' must be an object.",
                        "Pass {key: value} with keys from describeType.").elements(spec.subject));
            } else {
                readProperties(props, where + ".properties", spec.type, null, true, spec.given, issues, spec.subject);
            }
        }
        spec.flags = readFlags(o.get("flags"), where + ".flags", issues, spec.subject);
        JSONValue dv = o.get("description");
        if (dv != null && dv.isNull() == null) {
            spec.description = readDescription(dv, where + ".description", issues, spec.subject);
        }
        return issues.size() == errors ? spec : null;
    }

    /**
     * Validates a property map against a type ([SP_AGA_03_03]): unknown keys are
     * {@code unknown_property}; read-only keys are {@code invalid_value} for {@code set} and
     * accepted and ignored otherwise; values are parsed strictly.
     *
     * @param extra       additional keys of an existing element (its own exported keys), or null
     * @param acceptReadOnly true for add/import (ignore read-only keys), false for {@code set}
     * @param out         receives key → value in the element's JSON format
     */
    static void readProperties(JSONObject props, String where, Catalogue.TypeInfo type,
            Map<String, Catalogue.PropertyInfo> extra, boolean acceptReadOnly, Map<String, Object> out,
            List<Issue> issues, String subject) {
        for (String key : props.keySet()) {
            Catalogue.PropertyInfo info = type == null ? null : type.property(key);
            if (info == null && extra != null) {
                info = extra.get(key);
            }
            if (info == null) {
                issues.add(Issue.of(IssueCode.UNKNOWN_PROPERTY, "Property '" + key + "' is not a property of "
                        + (type == null ? "this element" : type.type) + " (" + where + ").",
                        "Valid keys: " + (type == null ? "(none)" : type.propertyKeys()) + ".").elements(subject));
                continue;
            }
            if (info.readOnly) {
                if (!acceptReadOnly) {
                    issues.add(Issue.of(IssueCode.INVALID_VALUE, "Property '" + key + "' of " + type.type
                            + " is read-only (" + where + ").",
                            "It follows the element's geometry or another property; set the controlling key instead (writable keys: "
                                    + type.writableKeys() + ").").elements(subject));
                }
                continue;
            }
            String[] problem = new String[1];
            Object value = PropertyValues.parse(info, props.get(key), problem);
            if (value == null) {
                issues.add(Issue.of(IssueCode.INVALID_VALUE, "Argument '" + where + "." + key + "' " + problem[0] + ".",
                        info.unit != null ? "Use a number or a string such as \"4.7k" + info.unit + "\"."
                                : "Use a " + info.kind + " value (describeType lists the kinds).").elements(subject));
                continue;
            }
            out.put(key, value);
        }
    }

    /** Reads optional raw element flags (0 ≤ flags < 2^31); null when absent or invalid. */
    static Integer readFlags(JSONValue v, String where, List<Issue> issues, String subject) {
        if (v == null || v.isNull() != null) {
            return null;
        }
        JSONNumber n = v.isNumber();
        double d = n == null ? -1 : n.doubleValue();
        if (n == null || d != Math.rint(d) || d < 0 || d > Integer.MAX_VALUE) {
            issues.add(Issue.of(IssueCode.INVALID_VALUE, "Argument '" + where + "' must be an integer from 0 to 2^31-1.",
                    "Pass the raw flag bits as a whole number, or omit flags.").elements(subject));
            return null;
        }
        return (int) d;
    }

    /** Reads a description (string of at most 1000 chars); null when invalid. */
    static String readDescription(JSONValue v, String where, List<Issue> issues, String subject) {
        JSONString s = v == null ? null : v.isString();
        if (s == null || s.stringValue().length() > PropertyValues.MAX_TEXT) {
            issues.add(Issue.of(IssueCode.INVALID_VALUE, "Argument '" + where + "' must be a string of at most "
                    + PropertyValues.MAX_TEXT + " characters.", "Pass a shorter description text.").elements(subject));
            return null;
        }
        return s.stringValue();
    }

    /**
     * @return the full property set of a new element: the TypeInfo defaults of every writable key,
     *         overridden by the given values
     */
    static LinkedHashMap<String, Object> withDefaults(Spec spec) {
        LinkedHashMap<String, Object> props = new LinkedHashMap<>();
        for (Catalogue.PropertyInfo p : spec.type.properties) {
            if (!p.readOnly) {
                props.put(p.key, p.def);
            }
        }
        props.putAll(spec.given);
        return props;
    }

    /** @return the JSON v2 element object of a spec (the factory's input) */
    static JSONObject toJsonElement(Spec spec) {
        JSONObject e = new JSONObject();
        e.put("type", new JSONString(spec.type.type));
        if (spec.description != null) {
            e.put("description", new JSONString(spec.description));
        }
        JSONObject props = new JSONObject();
        for (Map.Entry<String, Object> p : withDefaults(spec).entrySet()) {
            props.put(p.getKey(), Catalogue.jsonValue(p.getValue()));
        }
        e.put("properties", props);
        JSONObject pins = new JSONObject();
        pins.put("_startpoint", pinAt(spec.x1, spec.y1));
        pins.put("_endpoint", pinAt(spec.x2, spec.y2));
        e.put("pins", pins);
        e.put("_flags", new JSONNumber(spec.flags != null ? spec.flags : spec.type.defaultFlags));
        return e;
    }

    private static JSONObject pinAt(double x, double y) {
        JSONObject pos = new JSONObject();
        pos.put("x", new JSONNumber(CellGeometry.toPx(x)));
        pos.put("y", new JSONNumber(CellGeometry.toPx(y)));
        JSONObject pin = new JSONObject();
        pin.put("position", pos);
        return pin;
    }

    /** A validated AgentCircuit: its specs in order and the JSON v2 text to load. */
    static final class Converted {
        final List<Spec> specs = new ArrayList<>();
        /** IDs of the specs in order (supplied or generated). */
        final List<String> ids = new ArrayList<>();
        String json;
    }

    /**
     * Validates an AgentCircuit and converts it to JSON v2 text. IDs: supplied IDs are kept; a
     * spec without one gets {@code <idPrefix><n>} exactly as the document's registry generates it
     * after the content replacement resets the counters — every supplied ID raises its counter
     * first, then the generated numbers skip present IDs ([SP_AGA_03_02]).
     *
     * @return the conversion, or null when {@code issues} received errors
     */
    static Converted convertCircuit(JSONObject circuit, Catalogue cat, List<Issue> issues) {
        int errors = issues.size();
        JSONValue ev = circuit.get("elements");
        JSONArray elements = ev == null ? null : ev.isArray();
        if (elements == null) {
            issues.add(Issue.of(IssueCode.INVALID_VALUE, "Argument 'circuit.elements' must be a list of ElementSpecs.",
                    "Pass {elements: [...], simulation?, scopes?}."));
            return null;
        }
        if (elements.size() > MAX_ELEMENTS) {
            issues.add(Issue.of(IssueCode.INVALID_VALUE, "Argument 'circuit.elements' has " + elements.size()
                    + " entries.", "Pass at most " + MAX_ELEMENTS + " elements."));
            return null;
        }
        Converted out = new Converted();
        Set<String> supplied = new HashSet<>();
        for (int i = 0; i < elements.size(); i++) {
            Spec spec = parseSpec(elements.get(i), "circuit.elements[" + i + "]", i, CellGeometry.IMPORT_LATTICE, cat, issues);
            if (spec == null) {
                continue;
            }
            if (spec.id != null && !supplied.add(spec.id)) {
                issues.add(Issue.of(IssueCode.ID_TAKEN, "Element ID " + spec.id + " is used twice in the circuit.",
                        "Give every element its own ID, or omit id to generate one.").elements(spec.id));
                continue;
            }
            out.specs.add(spec);
        }
        JSONValue sv = circuit.get("simulation");
        JSONObject simulation = null;
        if (sv != null && sv.isNull() == null) {
            simulation = sv.isObject();
            if (simulation == null) {
                issues.add(Issue.of(IssueCode.INVALID_VALUE, "Argument 'circuit.simulation' must be an object.",
                        "Pass the keys of the JSON v2 simulation object, e.g. {\"time_step\": \"5 us\"}."));
            }
        }
        if (issues.size() > errors) {
            return null;
        }
        // Generated IDs, as the registry would issue them after a counter reset
        Map<String, Integer> counters = new HashMap<>();
        for (String id : supplied) {
            raise(counters, id);
        }
        Set<String> present = new HashSet<>(supplied);
        for (Spec spec : out.specs) {
            String id = spec.id;
            if (id == null) {
                String prefix = ElementIdRegistry.lettersOnly(spec.type.idPrefix);
                Integer c = counters.get(prefix);
                int n = c == null ? 1 : c + 1;
                while (present.contains(prefix + n)) {
                    n++;
                }
                counters.put(prefix, n);
                id = prefix + n;
                present.add(id);
            }
            out.ids.add(id);
        }
        JSONArray scopes = convertScopes(circuit.get("scopes"), present, issues);
        if (issues.size() > errors) {
            return null;
        }

        JSONObject root = new JSONObject();
        JSONObject schema = new JSONObject();
        schema.put("format", new JSONString("circuitjs"));
        schema.put("version", new JSONString(JsonCircuitFormat.FORMAT_VERSION));
        root.put("schema", schema);
        if (simulation != null) {
            root.put("simulation", simulation);
        }
        JSONObject elms = new JSONObject();
        for (int i = 0; i < out.specs.size(); i++) {
            elms.put(out.ids.get(i), toJsonElement(out.specs.get(i)));
        }
        root.put("elements", elms);
        if (scopes != null && scopes.size() > 0) {
            root.put("scopes", scopes);
        }
        out.json = root.toString();
        return out;
    }

    private static void raise(Map<String, Integer> counters, String id) {
        String prefix = ElementIdRegistry.counterPrefix(id);
        if (prefix == null) {
            return;
        }
        String digits = id.substring(prefix.length());
        if (digits.length() > 9) {
            return;
        }
        int n = Integer.parseInt(digits);
        Integer c = counters.get(prefix);
        if (c == null || c < n) {
            counters.put(prefix, n);
        }
    }

    /** {@code scopes: {element, quantity?}[]} → JSON v2 scopes (one plot when a quantity is given). */
    private static JSONArray convertScopes(JSONValue v, Set<String> ids, List<Issue> issues) {
        if (v == null || v.isNull() != null) {
            return null;
        }
        JSONArray list = v.isArray();
        if (list == null || list.size() > MAX_SCOPES) {
            issues.add(Issue.of(IssueCode.INVALID_VALUE, "Argument 'circuit.scopes' must be a list of at most "
                    + MAX_SCOPES + " entries.", "Pass [{element, quantity?}] with one entry per scope view."));
            return null;
        }
        JSONArray out = new JSONArray();
        for (int i = 0; i < list.size(); i++) {
            String where = "circuit.scopes[" + i + "]";
            JSONObject s = list.get(i).isObject();
            JSONString el = s == null || s.get("element") == null ? null : s.get("element").isString();
            if (el == null) {
                issues.add(Issue.of(IssueCode.INVALID_VALUE, "Argument '" + where + ".element' is required.",
                        "Pass the ElementId of the element to view."));
                continue;
            }
            if (!ids.contains(el.stringValue())) {
                issues.add(Issue.of(IssueCode.UNKNOWN_ELEMENT, "Argument '" + where + ".element' names no element of the circuit: '"
                        + el.stringValue() + "'.", "Use an ID from circuit.elements.").elements(el.stringValue()));
                continue;
            }
            int value = -1;
            JSONValue qv = s.get("quantity");
            if (qv != null && qv.isNull() == null) {
                value = scopeValue(qv.isString() == null ? null : qv.isString().stringValue());
                if (value < 0) {
                    issues.add(Issue.of(IssueCode.INVALID_VALUE, "Argument '" + where + ".quantity' is not one of the allowed values.",
                            "Use one of: voltage, current, power."));
                    continue;
                }
            }
            JSONObject scope = new JSONObject();
            scope.put("element", el);
            if (value >= 0) {
                JSONObject plot = new JSONObject();
                plot.put("element", el);
                plot.put("value", new JSONNumber(value));
                JSONArray plots = new JSONArray();
                plots.set(0, plot);
                scope.put("plots", plots);
            }
            out.set(out.size(), scope);
        }
        return out;
    }

    /** @return the scope value ({@code Scope.VAL_*}) of a quantity name, or -1 */
    static int scopeValue(String quantity) {
        if ("voltage".equals(quantity)) {
            return Scope.VAL_VOLTAGE;
        }
        if ("current".equals(quantity)) {
            return Scope.VAL_CURRENT;
        }
        if ("power".equals(quantity)) {
            return Scope.VAL_POWER;
        }
        return -1;
    }

    /** @return the quantity name of a scope value, or null when it is none of the three */
    static String scopeQuantity(int value) {
        switch (value) {
            case Scope.VAL_VOLTAGE:
                return "voltage";
            case Scope.VAL_CURRENT:
                return "current";
            case Scope.VAL_POWER:
                return "power";
            default:
                return null;
        }
    }
}
