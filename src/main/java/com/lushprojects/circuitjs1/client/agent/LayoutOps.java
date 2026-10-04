package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONBoolean;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.DocumentScope;
import com.lushprojects.circuitjs1.client.element.MeasuringTextLayout;
import com.lushprojects.circuitjs1.client.element.TextPlacement;

import java.util.ArrayList;
import java.util.List;

/**
 * [SP_AGA_02_16] {@code checkLayout {doc?, includeBoxes?}}: the on-demand drawing check of the
 * texts ([SP_AGA_03_13], {@link TextOverlap}), inside the document scope of {@code doc} (its
 * options apply, [SP_AGA_03_08]). Read-only: it lays out and measures, draws nothing and changes no
 * bounding box, undo entry or modified flag, so it is served while the document is busy. Output
 * {@code data: {issues, texts, truncated, boxes?}}: issues sorted by severity, then key, at most
 * {@link #MAX_ISSUES}; with {@code includeBoxes} the TextBoxes (owner, cut text, ink box in cells
 * rounded outwards to whole pixels, anchor, align, baseline, font, live) in element order, then
 * layout order, at most {@link #MAX_BOXES}; {@code truncated} when either list was cut. No
 * mutation delta and no getConnectivity computes these issues.
 */
final class LayoutOps {

    static final int MAX_ISSUES = 100;
    static final int MAX_BOXES = 2000;

    private LayoutOps() {
    }

    static void register(AgentApi api) {
        api.register("checkLayout", AgentApi.DocPolicy.OPTIONAL, AgentApi.BusyPolicy.SERVED, LayoutOps::checkLayout);
    }

    static OperationResult checkLayout(AgentApi.Call call) {
        final boolean includeBoxes = call.args.optBool("includeBoxes", false);
        if (call.args.failed()) {
            return call.args.failure();
        }
        final CircuitDocument doc = call.doc;
        return DocumentScope.call(call.sim, doc, () -> {
            TextOverlap.Result r = TextOverlap.check(doc.simulator.elmList, call.sim.renderer.getTextMeasurer());
            List<Issue> sorted = sortIssues(r.issues);
            boolean truncated = sorted.size() > MAX_ISSUES;
            JSONArray issues = new JSONArray();
            for (int i = 0; i < sorted.size() && i < MAX_ISSUES; i++) {
                issues.set(i, sorted.get(i).toJson());
            }
            JSONObject data = new JSONObject();
            data.put("issues", issues);
            data.put("texts", new JSONNumber(r.checked));
            if (includeBoxes) {
                JSONArray boxes = new JSONArray();
                for (MeasuringTextLayout.Measured m : r.texts) {
                    if (boxes.size() >= MAX_BOXES) {
                        truncated = true;
                        break;
                    }
                    boxes.set(boxes.size(), box(m));
                }
                data.put("boxes", boxes);
            }
            data.put("truncated", JSONBoolean.getInstance(truncated));
            return OperationResult.success(data);
        });
    }

    /** @return the issues by severity (errors, warnings, info), then by key */
    private static List<Issue> sortIssues(List<Issue> issues) {
        List<Issue> sorted = new ArrayList<>(issues);
        sorted.sort((a, b) -> {
            int s = a.getSeverity().rank() - b.getSeverity().rank();
            return s != 0 ? s : a.key().compareTo(b.key());
        });
        return sorted;
    }

    /** @return the TextBox of one measured placement ([SP_AGA_02_16]) */
    private static JSONObject box(MeasuringTextLayout.Measured m) {
        TextPlacement p = m.placement;
        JSONObject o = new JSONObject();
        o.put("element", new JSONString(m.owner.getElementId()));
        o.put("text", new JSONString(TextOverlap.cut(p.text)));
        JSONObject b = new JSONObject();
        b.put("x1", new JSONNumber(Math.floor(m.x1) / CellGeometry.CELL_PX));
        b.put("y1", new JSONNumber(Math.floor(m.y1) / CellGeometry.CELL_PX));
        b.put("x2", new JSONNumber(Math.ceil(m.x2) / CellGeometry.CELL_PX));
        b.put("y2", new JSONNumber(Math.ceil(m.y2) / CellGeometry.CELL_PX));
        o.put("box", b);
        JSONObject a = new JSONObject();
        a.put("x", new JSONNumber(p.x / CellGeometry.CELL_PX));
        a.put("y", new JSONNumber(p.y / CellGeometry.CELL_PX));
        o.put("anchor", a);
        o.put("align", new JSONString(TextOverlap.align(p)));
        o.put("baseline", new JSONString(TextOverlap.baseline(p)));
        o.put("font", new JSONString(p.font.getName()));
        o.put("live", JSONBoolean.getInstance(p.isLive()));
        return o;
    }
}
