package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONBoolean;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.DocumentScope;
import com.lushprojects.circuitjs1.client.ElementIdRegistry;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.Scope;
import com.lushprojects.circuitjs1.client.ScopeManager;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.io.CircuitFormat;
import com.lushprojects.circuitjs1.client.io.CircuitFormatRegistry;
import com.lushprojects.circuitjs1.client.io.json.JsonCircuitExporter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [SP_AGA_02_05] getCircuit — the circuit in agent form (ElementRecords of [SP_AGA_01_04]) — and
 * the non-file {@code exportCircuit} of [SP_AGA_02_14]. Both read the target document inside
 * {@code DocumentScope}: the simulation settings and the export's options come from the
 * document's own UI state (SP_AGA_03_08 R2), and the visible tab is not disturbed (R1).
 * <p>
 * PostRecord {@code net} names come from the document's own analysed nodes, named by the
 * {@link Connectivity} rules; a post is without {@code net} only when the document could not be
 * analysed.
 */
final class CircuitView {

    static final int DEFAULT_LIMIT = 200;
    static final int MAX_LIMIT = 500;

    private CircuitView() {
    }

    static void register(AgentApi api) {
        api.register("getCircuit", AgentApi.DocPolicy.OPTIONAL, AgentApi.BusyPolicy.SERVED, CircuitView::getCircuit);
        api.register("exportCircuit", AgentApi.DocPolicy.OPTIONAL, AgentApi.BusyPolicy.SERVED, CircuitView::exportCircuit);
    }

    /**
     * {@code getCircuit(detail?, ids?, offset?, limit?)} → {@code {elements, total, nextOffset?,
     * simulation, scopes, models?, modelsTruncated?}} ({@code models} on the {@code offset = 0} page).
     */
    static OperationResult getCircuit(AgentApi.Call call) {
        String detail = call.args.optEnum("detail", new String[] { "concise", "full" }, "concise");
        JSONArray ids = call.args.optArray("ids", 0, AgentCircuitConverter.MAX_ELEMENTS);
        int offset = call.args.optInt("offset", 0, Integer.MAX_VALUE, 0);
        int limit = call.args.optInt("limit", 1, MAX_LIMIT, DEFAULT_LIMIT);
        if (call.args.failed()) {
            return call.args.failure();
        }
        CircuitDocument doc = call.doc;
        Map<String, CircuitElm> byId = byId(doc);
        List<CircuitElm> selected = new ArrayList<>();
        if (ids != null) {
            List<Issue> unknown = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                JSONString s = ids.get(i).isString();
                CircuitElm elm = s == null ? null : byId.get(s.stringValue());
                if (elm == null) {
                    unknown.add(unknownElement(s == null ? String.valueOf(ids.get(i)) : s.stringValue(), "ids[" + i + "]"));
                } else if (!selected.contains(elm)) {
                    selected.add(elm);
                }
            }
            if (!unknown.isEmpty()) {
                return OperationResult.failure(unknown);
            }
        } else {
            selected.addAll(doc.simulator.elmList);
        }
        sortById(selected);
        final boolean full = "full".equals(detail);
        final Catalogue cat = call.sim.getAgentCatalogue();
        final List<CircuitElm> page = selected.subList(Math.min(offset, selected.size()),
                Math.min(selected.size(), Math.min(offset, selected.size()) + limit));
        final int total = selected.size();
        final int end = Math.min(offset, total) + page.size();
        return DocumentScope.call(call.sim, doc, () -> {
            // PostRecord.net comes from the document's own analysed nodes
            // no net names from stale node indices when the analysis failed
            Connectivity.Nets nets = doc.ensureAnalysed() ? Connectivity.nets(doc) : null;
            JSONArray list = new JSONArray();
            for (int i = 0; i < page.size(); i++) {
                list.set(i, record(page.get(i), doc, cat, full, nets));
            }
            JSONObject data = new JSONObject();
            data.put("elements", list);
            data.put("total", new JSONNumber(total));
            if (end < total) {
                data.put("nextOffset", new JSONNumber(end));
            }
            data.put("simulation", new JsonCircuitExporter(null).exportSimulation(doc));
            data.put("scopes", scopes(doc));
            if (offset == 0) {
                // [SP_AGA_02_05] the document's models, on the first page only
                ModelOps.DocumentModels models = ModelOps.documentModels(doc);
                data.put("models", models.models);
                if (models.truncated > 0) {
                    data.put("modelsTruncated", new JSONNumber(models.truncated));
                }
            }
            return OperationResult.success(data);
        });
    }

    /** {@code exportCircuit(format = "json")} → {@code {content}}; JSON element keys are the element IDs. */
    static OperationResult exportCircuit(AgentApi.Call call) {
        String format = call.args.optEnum("format", new String[] { "text", "json" }, "json");
        if (call.args.failed()) {
            return call.args.failure();
        }
        final CircuitFormat f = CircuitFormatRegistry.getById(format);
        return DocumentScope.call(call.sim, call.doc, () -> {
            JSONObject data = new JSONObject();
            data.put("content", new JSONString(f.createExporter().export(call.doc)));
            return OperationResult.success(data);
        });
    }

    // ---------------------------------------------------------------- records

    /**
     * [SP_AGA_01_04] The ElementRecord of {@code elm}. {@code concise} omits properties equal to
     * the type defaults and flags equal to the type default flags. {@code nets} names the posts'
     * nets (null: no {@code net} fields).
     */
    static JSONObject record(CircuitElm elm, CircuitDocument doc, Catalogue cat, boolean full, Connectivity.Nets nets) {
        String id = elm.getElementId();
        String typeName = elm.getJsonTypeName();
        Catalogue.TypeInfo type = cat.find(typeName);
        JSONObject r = new JSONObject();
        r.put("id", new JSONString(id));
        r.put("type", new JSONString(type != null ? type.type : typeName));
        r.put("start", CellGeometry.cellPoint(elm.getX(), elm.getY()));
        r.put("end", CellGeometry.cellPoint(elm.getX2(), elm.getY2()));

        String[] pins = PinNames.of(elm);
        JSONArray posts = new JSONArray();
        for (int i = 0; i < pins.length; i++) {
            Point p = elm.getPost(i);
            JSONObject post = new JSONObject();
            post.put("pin", new JSONString(pins[i]));
            post.put("index", new JSONNumber(i));
            if (p != null) {
                post.put("at", CellGeometry.cellPoint(p.x, p.y));
            }
            String net = nets == null ? null : nets.nameOf(elm.getNode(i));
            if (net != null) {
                post.put("net", new JSONString(net));
            }
            post.put("open", JSONBoolean.getInstance(doc.hasOpenMark(id + "." + pins[i])));
            posts.set(i, post);
        }
        r.put("posts", posts);

        JSONObject props = new JSONObject();
        for (Map.Entry<String, Object> e : PropertyValues.current(elm).entrySet()) {
            Catalogue.PropertyInfo info = type == null ? null : type.property(e.getKey());
            Catalogue.PropertyInfo shape = info != null ? info : Catalogue.describeValue(e.getKey(), e.getValue());
            if (shape == null) {
                continue; // structured value (list, map): not an agent property
            }
            if (!full && info != null && PropertyValues.same(e.getValue(), info.def, info.unit)) {
                continue;
            }
            props.put(e.getKey(), Catalogue.jsonValue(e.getValue()));
        }
        r.put("properties", props);
        int flags = elm.getJsonFlags();
        if (full || type == null || flags != type.defaultFlags) {
            r.put("flags", new JSONNumber(flags));
        }
        String desc = elm.getDescription();
        if (desc != null && !desc.isEmpty()) {
            r.put("description", new JSONString(desc));
        }
        return r;
    }

    /** {@code {element, quantity?}} of every docked scope view, in slot order. */
    static JSONArray scopes(CircuitDocument doc) {
        ScopeManager sm = doc.scopeManager;
        JSONArray list = new JSONArray();
        for (int i = 0; i < sm.getScopeCount(); i++) {
            Scope s = sm.getScope(i);
            if (s == null || s.plots == null || s.plots.isEmpty() || s.plots.get(0).getElm() == null) {
                continue;
            }
            CircuitElm elm = s.plots.get(0).getElm();
            JSONObject o = new JSONObject();
            o.put("element", new JSONString(elm.getElementId()));
            String q = AgentCircuitConverter.scopeQuantity(s.plots.get(0).getValue());
            if (q != null) {
                o.put("quantity", new JSONString(q));
            }
            list.set(list.size(), o);
        }
        return list;
    }

    // ---------------------------------------------------------------- helpers

    /** @return the document's elements by ID */
    static Map<String, CircuitElm> byId(CircuitDocument doc) {
        Map<String, CircuitElm> map = new HashMap<>();
        for (CircuitElm elm : doc.simulator.elmList) {
            map.put(elm.getElementId(), elm);
        }
        return map;
    }

    static Issue unknownElement(String id, String where) {
        return Issue.of(IssueCode.UNKNOWN_ELEMENT, "Argument '" + where + "' names no element of the document: '" + id + "'.",
                "getCircuit lists the element IDs.").elements(ElementIdRegistry.isValidId(id) ? id : "#?");
    }

    /**
     * [SP_AGA_02_05] Ordering: IDs of the form {@code <letters><digits>} first, by letters, then by
     * numeric value; all other IDs after them, in lexicographic order.
     */
    static void sortById(List<CircuitElm> elms) {
        final Map<CircuitElm, String> ids = new HashMap<>();
        for (CircuitElm e : elms) {
            ids.put(e, e.getElementId());
        }
        Collections.sort(elms, new Comparator<CircuitElm>() {
            @Override
            public int compare(CircuitElm a, CircuitElm b) {
                return compareIds(ids.get(a), ids.get(b));
            }
        });
    }

    static int compareIds(String a, String b) {
        String pa = ElementIdRegistry.counterPrefix(a);
        String pb = ElementIdRegistry.counterPrefix(b);
        if (pa != null && pb != null) {
            int c = pa.compareTo(pb);
            if (c != 0) {
                return c;
            }
            String da = a.substring(pa.length());
            String db = b.substring(pb.length());
            // numeric value of arbitrarily long digit strings: compare without leading zeros
            String na = stripZeros(da);
            String nb = stripZeros(db);
            if (na.length() != nb.length()) {
                return na.length() - nb.length();
            }
            c = na.compareTo(nb);
            return c != 0 ? c : da.compareTo(db);
        }
        if (pa != null) {
            return -1;
        }
        if (pb != null) {
            return 1;
        }
        return a.compareTo(b);
    }

    private static String stripZeros(String digits) {
        int i = 0;
        while (i < digits.length() - 1 && digits.charAt(i) == '0') {
            i++;
        }
        return digits.substring(i);
    }
}
