package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONNull;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;
import com.google.gwt.json.client.JSONValue;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.CircuitNode;
import com.lushprojects.circuitjs1.client.CircuitNodeLink;
import com.lushprojects.circuitjs1.client.DocumentScope;
import com.lushprojects.circuitjs1.client.element.CircuitElm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [SP_AGA_02_07] {@code read}: instant readings of the target document at its current simulated
 * time, and the ProbeSpec target resolution of [SP_AGA_01_09] shared with the runs of PL_AGA
 * Phase 7 ({@link ProbeTarget}).
 * <p>
 * Values come from the document's own analysed node data (the nets of {@link Connectivity}) and
 * its elements, never from the session-wide label registry ([SP_AGA_03_08]). A net that does not
 * exist is {@code unknown_net} and yields no value — there is no 0 V fallback.
 * <p>
 * Element quantities: an element <em>defines</em> voltage, current and power when it has at
 * least one post and declares itself viewable in a scope ({@code canViewInScope()}: the element
 * presents single voltage/current/power values, as its scope plots do). The values are the
 * element's own definitions: {@code getReportedVoltageDiff()} (post 0 minus post 1 unless the
 * element overrides it), {@code getCurrent()} and {@code getPower()}. Elements without posts,
 * chips and other multi-terminal elements that are not scope-viewable define none of them
 * ({@code invalid_value} naming the type).
 */
final class Readings {

    /** Maximum number of targets of one read. */
    static final int MAX_TARGETS = 100;

    static final String[] QUANTITIES = { "voltage", "current", "power" };

    private Readings() {
    }

    static void register(AgentApi api) {
        api.register("read", AgentApi.DocPolicy.OPTIONAL, AgentApi.BusyPolicy.SERVED, Readings::read);
    }

    /** One resolved ProbeSpec: what to sample and how it is named. */
    static final class ProbeTarget {
        enum Kind { NET, POST, ELEMENT }

        final String name;
        final Kind kind;
        /** Simulator node of a NET target. */
        final int node;
        final CircuitElm elm;
        /** Post index of a POST target. */
        final int post;
        /** voltage, current or power (ELEMENT); voltage otherwise. */
        final String quantity;

        ProbeTarget(String name, Kind kind, int node, CircuitElm elm, int post, String quantity) {
            this.name = name;
            this.kind = kind;
            this.node = node;
            this.elm = elm;
            this.post = post;
            this.quantity = quantity;
        }

        /** @return {@code V}, {@code A} or {@code W} */
        String unit() {
            return "current".equals(quantity) ? "A" : "power".equals(quantity) ? "W" : "V";
        }

        /** @return the value at the document's current simulated time */
        double value(CircuitDocument doc) {
            switch (kind) {
                case NET:
                    return netVoltage(doc, node);
                case POST:
                    return elm.getNode(post) == 0 ? 0 : elm.getNodeVoltage(post);
                default:
                    if ("current".equals(quantity)) {
                        return elm.getCurrent();
                    }
                    if ("power".equals(quantity)) {
                        return elm.getPower();
                    }
                    return elm.getReportedVoltageDiff();
            }
        }
    }

    /** @return the voltage of simulator node {@code node} relative to ground */
    static double netVoltage(CircuitDocument doc, int node) {
        if (node <= 0 || node >= doc.simulator.nodeList.size()) {
            return 0;
        }
        CircuitNode cn = doc.simulator.nodeList.get(node);
        if (cn.links.isEmpty()) {
            return 0;
        }
        CircuitNodeLink l = cn.links.get(0);
        return l.elm.getNodeVoltage(l.num);
    }

    /** @return true when {@code elm} defines voltage, current and power (see the class comment) */
    static boolean definesQuantities(CircuitElm elm) {
        return elm.getPostCount() >= 1 && elm.canViewInScope();
    }

    /**
     * Resolves one ProbeSpec. Problems are added to {@code issues}; the result is null then.
     *
     * @param where argument path for messages, e.g. {@code targets[2]}
     */
    static ProbeTarget resolve(JSONValue v, String where, Connectivity.Nets nets, Map<String, CircuitElm> byId,
            List<Issue> issues) {
        JSONObject o = v == null ? null : v.isObject();
        if (o == null) {
            issues.add(invalid(where, "must be a ProbeSpec object", "Pass {net}, {post} or {element, quantity?}."));
            return null;
        }
        String net = string(o, "net", where, issues);
        String post = string(o, "post", where, issues);
        String element = string(o, "element", where, issues);
        String name = string(o, "name", where, issues);
        String quantity = string(o, "quantity", where, issues);
        int given = (net != null ? 1 : 0) + (post != null ? 1 : 0) + (element != null ? 1 : 0);
        if (given != 1) {
            issues.add(invalid(where, "must give exactly one of net, post or element", "Pass one target per ProbeSpec."));
            return null;
        }
        if (quantity != null && element == null) {
            issues.add(invalid(where + ".quantity", "is only allowed with element", "Remove quantity, or probe an element."));
            return null;
        }
        if (quantity != null && !contains(QUANTITIES, quantity)) {
            issues.add(invalid(where + ".quantity", "is not one of the allowed values", "Use one of: voltage, current, power."));
            return null;
        }
        if (net != null) {
            Connectivity.Net n = nets.find(net);
            if (n == null) {
                issues.add(Connectivity.unknownNet(net, where + ".net", nets));
                return null;
            }
            return new ProbeTarget(name != null ? name : net, ProbeTarget.Kind.NET, n.node, null, -1, "voltage");
        }
        if (post != null) {
            String id = OpenMarks.elementOf(post);
            CircuitElm elm = id == null ? null : byId.get(id);
            if (elm == null) {
                issues.add(CircuitView.unknownElement(id == null ? post : id, where + ".post"));
                return null;
            }
            String[] pins = PinNames.of(elm);
            String pin = OpenMarks.resolvePin(post, pins);
            if (pin == null) {
                issues.add(Issue.of(IssueCode.UNKNOWN_POST, "Argument '" + where + ".post' names no post of " + id + ": '"
                        + post + "'.", "Pins of " + id + ": " + String.join(", ", pins) + ".").elements(id));
                return null;
            }
            int index = 0;
            while (!pins[index].equals(pin)) {
                index++;
            }
            return new ProbeTarget(name != null ? name : post, ProbeTarget.Kind.POST, -1, elm, index, "voltage");
        }
        CircuitElm elm = byId.get(element);
        if (elm == null) {
            issues.add(CircuitView.unknownElement(element, where + ".element"));
            return null;
        }
        String q = quantity != null ? quantity : "voltage";
        if (!definesQuantities(elm)) {
            issues.add(Issue.of(IssueCode.INVALID_VALUE, "Element " + element + " of type " + elm.getJsonTypeName()
                    + " defines no " + q + " (" + where + ").", "Probe one of its posts or nets instead.").elements(element));
            return null;
        }
        return new ProbeTarget(name != null ? name : element, ProbeTarget.Kind.ELEMENT, -1, elm, -1, q);
    }

    /**
     * Resolves a list of ProbeSpecs; names must be unique within the call. When the document's
     * analysis failed, every target is rejected with {@code analysis_failed}.
     *
     * @return the targets, or null when any failed (issues added)
     */
    static List<ProbeTarget> resolveAll(JSONArray specs, String where, Connectivity.Nets nets, Map<String, CircuitElm> byId,
            List<Issue> issues) {
        if (!nets.analysed) {
            // no target is read from stale or default node voltages (no silent 0 V)
            issues.add(Issue.of(IssueCode.ANALYSIS_FAILED, "No reading is possible: the circuit could not be analysed.",
                    "Fix the problem getConnectivity and getDiagnostics report, then read again."));
            return null;
        }
        List<ProbeTarget> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int before = issues.size();
        for (int i = 0; i < specs.size(); i++) {
            ProbeTarget t = resolve(specs.get(i), where + "[" + i + "]", nets, byId, issues);
            if (t != null && !names.add(t.name)) {
                issues.add(invalid(where + "[" + i + "].name", "repeats the name '" + t.name + "'",
                        "Give each target a unique name."));
            }
            out.add(t);
        }
        return issues.size() > before ? null : out;
    }

    /** {@code read(targets)} → {@code {t, values: [{name, value, unit}]}}. */
    static OperationResult read(AgentApi.Call call) {
        final JSONArray targets = call.args.optArray("targets", 1, MAX_TARGETS);
        if (targets == null && !call.args.has("targets")) {
            call.args.invalid("targets", "is required", "Pass 1 to " + MAX_TARGETS + " ProbeSpecs.");
        }
        if (call.args.failed()) {
            return call.args.failure();
        }
        final CircuitDocument doc = call.doc;
        return DocumentScope.call(call.sim, doc, () -> {
            Connectivity.Report report = Connectivity.analyse(doc);
            List<Issue> issues = new ArrayList<>();
            List<ProbeTarget> resolved = resolveAll(targets, "targets", report.nets, CircuitView.byId(doc), issues);
            if (resolved == null) {
                return OperationResult.failure(issues);
            }
            List<Issue> warnings = new ArrayList<>();
            JSONArray values = new JSONArray();
            for (ProbeTarget t : resolved) {
                double v = t.value(doc);
                JSONObject o = new JSONObject();
                o.put("name", new JSONString(t.name));
                if (Double.isNaN(v) || Double.isInfinite(v)) {
                    // JSON has no NaN/Infinity; the value is reported as null with a warning
                    o.put("value", JSONNull.getInstance());
                    warnings.add(Issue.of(IssueCode.SOLVER_WARNING, "Reading '" + t.name + "' is not a finite number.",
                            "Check getDiagnostics for solver problems."));
                } else {
                    o.put("value", new JSONNumber(v));
                }
                o.put("unit", new JSONString(t.unit()));
                values.set(values.size(), o);
            }
            JSONObject data = new JSONObject();
            data.put("t", new JSONNumber(doc.simulator.t));
            data.put("values", values);
            OperationResult r = OperationResult.success(data);
            for (Issue w : warnings) {
                r.addIssue(w);
            }
            return r;
        });
    }

    // ---------------------------------------------------------------- helpers

    private static String string(JSONObject o, String key, String where, List<Issue> issues) {
        JSONValue v = o.get(key);
        if (v == null || v.isNull() != null) {
            return null;
        }
        if (v.isString() == null) {
            issues.add(invalid(where + "." + key, "must be a string", "Pass " + key + " as a JSON string."));
            return null;
        }
        return v.isString().stringValue();
    }

    private static Issue invalid(String where, String problem, String hint) {
        return Issue.of(IssueCode.INVALID_VALUE, "Argument '" + where + "' " + problem + ".", hint);
    }

    private static boolean contains(String[] values, String v) {
        for (String s : values) {
            if (s.equals(v)) {
                return true;
            }
        }
        return false;
    }
}
