package com.lushprojects.circuitjs1.client.agent;

import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.TextMeasurer;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.MeasuringTextLayout;
import com.lushprojects.circuitjs1.client.element.TextElm;
import com.lushprojects.circuitjs1.client.element.TextPlacement;
import com.lushprojects.circuitjs1.client.util.EchoText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [SP_AGA_03_13] The text layout check of {@code checkLayout}: the texts come from the elements'
 * own layout methods ({@link CircuitElm#layoutTexts}, not highlighted), measured with the canvas
 * metrics through a {@link MeasuringTextLayout}; nothing is drawn and no state changes.
 *
 * <p>A <em>checked text</em> is a placement of a covered element that is not live, not transient
 * and has a visible character. Obstacles are the {@code symbol_overlap} body model
 * ({@link SymbolOverlap.Model}): symbol regions (a band around a two-point symbol, a disc for a
 * one-post symbol, a rectangle for a derived one) and lines (wires, leads, stems). A single element
 * with a checked text has no symbol region here (its texts stand for its symbol), and its stem ends
 * where its own text box, grown by PAD, begins. Rules, with
 * {@link #PAD} = 1 px, for a checked text of element A and an element B other than A:
 * <ol>
 * <li>over a symbol: the box grown by PAD shares an interior point with B's symbol region;</li>
 * <li>crossed: a line of B has a point strictly inside the box grown by PAD;</li>
 * <li>text on text: the box and a checked text box of B, each grown by PAD, overlap with a
 *     positive area.</li>
 * </ol>
 * A pair that {@code symbol_overlap} reports is exempt, and so is rule 3 between two text elements
 * (graphic text lines stacked into a paragraph sit closer than 2 px by design). One {@code text_overlap} per unordered
 * pair, from the first finding in rule order; within a rule the texts of the element with the
 * smaller ID (UTF-16 order) come first, then the texts in layout order. Its {@code at} is the centre
 * of the found text's box, at whole pixels. Elements of a class that is not covered
 * ({@link CircuitElm#textLayoutCovered}) give one {@code text_not_covered} per class instead.
 * A 64 px spatial hash keeps the cost near linear.
 */
final class TextOverlap {

    /** Half the 2 px stroke of wires and leads. */
    static final double PAD = 1;
    /** Characters of a text quoted in a message or a TextBox. */
    static final int TEXT_CUT = 32;
    /** Element IDs listed by one text_not_covered issue. */
    static final int MAX_NOT_COVERED_IDS = 20;
    static final int BUCKET = 64;
    static final int MAX_BUCKETS = 1 << 18;

    static final String HINT = "Give the text clear space: a value text sits beside the middle of its symbol"
            + " (above a horizontal part, right of a vertical part, left of a vertical source); keep wires and"
            + " other parts at least one cell from it, move or flip the part, or shorten the label.";

    /** Harness diagnostic: JSON type names reported as not covered whatever their class says. */
    private static final Set<String> forcedNotCovered = new HashSet<>();

    private TextOverlap() {
    }

    /** Harness diagnostic ({@code debugForceNotCovered}): null clears the list. */
    static void forceNotCovered(String jsonType) {
        if (jsonType == null) {
            forcedNotCovered.clear();
        } else {
            forcedNotCovered.add(jsonType);
        }
    }

    /** @return true when the element's texts go through its layout method */
    static boolean isCovered(CircuitElm elm) {
        return elm.textLayoutCovered() && (forcedNotCovered.isEmpty() || !forcedNotCovered.contains(elm.getJsonTypeName()));
    }

    /** The outcome of one check. */
    static final class Result {
        /** Every non-transient placement of the covered elements with a visible character (also live ones). */
        final List<MeasuringTextLayout.Measured> texts = new ArrayList<>();
        /** text_overlap then text_not_covered issues (unsorted). */
        final List<Issue> issues = new ArrayList<>();
        /** Texts the rules checked (not live, covered). */
        int checked;
    }

    /** Runs the check on the elements of the bound document. */
    static Result check(List<CircuitElm> elms, TextMeasurer measurer) {
        Result result = new Result();
        MeasuringTextLayout out = new MeasuringTextLayout(measurer);
        Map<Object, List<CircuitElm>> notCovered = new LinkedHashMap<>();
        for (CircuitElm elm : elms) {
            if (!isCovered(elm)) {
                Object cls = elm.getClass();
                List<CircuitElm> l = notCovered.get(cls);
                if (l == null) {
                    l = new ArrayList<>();
                    notCovered.put(cls, l);
                }
                l.add(elm);
                continue;
            }
            out.setOwner(elm);
            elm.layoutTexts(out, false);
        }
        List<MeasuringTextLayout.Measured> checked = new ArrayList<>();
        Set<CircuitElm> withText = new HashSet<>();
        for (MeasuringTextLayout.Measured m : out.getMeasured()) {
            if (!m.placement.hasInk()) {
                continue;
            }
            result.texts.add(m);
            if (!m.placement.isLive()) {
                checked.add(m);
                withText.add(m.owner);
            }
        }
        result.checked = checked.size();
        if (!checked.isEmpty()) {
            rules(elms, checked, withText, result.issues);
        }
        for (List<CircuitElm> l : notCovered.values()) {
            result.issues.add(notCoveredIssue(l));
        }
        return result;
    }

    // ---------------------------------------------------------------- obstacles

    private static final int BAND = 0;
    private static final int DISC = 1;
    private static final int RECT = 2;
    private static final int LINE = 3;
    private static final int TEXT = 4;

    /** One obstacle: a symbol region, a line or a checked text, with its bounding box. */
    private static final class Obstacle {
        final CircuitElm elm;
        final int kind;
        final double[] g;
        final MeasuringTextLayout.Measured text;
        double bx0, by0, bx1, by1;
        int stamp;

        Obstacle(CircuitElm elm, int kind, double[] g, MeasuringTextLayout.Measured text) {
            this.elm = elm;
            this.kind = kind;
            this.g = g;
            this.text = text;
        }
    }

    /** Dense spatial hash of the obstacles ({@link #BUCKET} px buckets, doubled while too many). */
    private static final class Grid {
        final double x0, y0;
        final int size, nx, ny;
        final List<Obstacle>[] buckets;
        int stamp;

        @SuppressWarnings("unchecked")
        Grid(List<Obstacle> all) {
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (Obstacle o : all) {
                minX = Math.min(minX, o.bx0);
                minY = Math.min(minY, o.by0);
                maxX = Math.max(maxX, o.bx1);
                maxY = Math.max(maxY, o.by1);
            }
            int s = BUCKET;
            while (((long) ((maxX - minX) / s) + 1) * ((long) ((maxY - minY) / s) + 1) > MAX_BUCKETS) {
                s *= 2;
            }
            x0 = minX;
            y0 = minY;
            size = s;
            nx = (int) ((maxX - minX) / s) + 1;
            ny = (int) ((maxY - minY) / s) + 1;
            buckets = new List[nx * ny];
            for (Obstacle o : all) {
                int ix1 = ix(o.bx1), iy1 = iy(o.by1);
                for (int ix = ix(o.bx0); ix <= ix1; ix++) {
                    for (int iy = iy(o.by0); iy <= iy1; iy++) {
                        List<Obstacle> l = buckets[iy * nx + ix];
                        if (l == null) {
                            l = new ArrayList<>();
                            buckets[iy * nx + ix] = l;
                        }
                        l.add(o);
                    }
                }
            }
        }

        // (plain arithmetic: these run for every obstacle and query)
        private int ix(double x) {
            double d = (x - x0) / size;
            if (d <= 0) {
                return 0;
            }
            int i = (int) d;
            return i < nx ? i : nx - 1;
        }

        private int iy(double y) {
            double d = (y - y0) / size;
            if (d <= 0) {
                return 0;
            }
            int i = (int) d;
            return i < ny ? i : ny - 1;
        }

        /** Adds to {@code out} the obstacles whose box meets the rectangle, each once. */
        void near(double x1, double y1, double x2, double y2, List<Obstacle> out) {
            stamp++;
            int ix2 = ix(x2), iy2 = iy(y2), iy1 = iy(y1);
            for (int ix = ix(x1); ix <= ix2; ix++) {
                for (int iy = iy1; iy <= iy2; iy++) {
                    List<Obstacle> l = buckets[iy * nx + ix];
                    if (l == null) {
                        continue;
                    }
                    for (int k = 0, n = l.size(); k < n; k++) {
                        Obstacle o = l.get(k);
                        if (o.stamp != stamp && o.bx1 >= x1 && o.bx0 <= x2 && o.by1 >= y1 && o.by0 <= y2) {
                            o.stamp = stamp;
                            out.add(o);
                        }
                    }
                }
            }
        }
    }

    private static Obstacle line(CircuitElm elm, double x1, double y1, double x2, double y2) {
        Obstacle o = new Obstacle(elm, LINE, new double[] { x1, y1, x2, y2 }, null);
        o.bx0 = Math.min(x1, x2);
        o.by0 = Math.min(y1, y2);
        o.bx1 = Math.max(x1, x2);
        o.by1 = Math.max(y1, y2);
        return o;
    }

    /** @return the symbol region of a body as an obstacle, or null (no region) */
    private static Obstacle symbol(SymbolOverlap.Body b) {
        switch (b.kind) {
            case SymbolOverlap.TWO_POINT: {
                if ((b.ux == 0 && b.uy == 0) || !(b.tMax > b.tMin)) {
                    return null;
                }
                double hw = SymbolOverlap.TWO_POINT_HALF_WIDTH;
                Obstacle o = new Obstacle(b.elm, BAND, new double[] { b.ax, b.ay, b.ux, b.uy, b.tMin, b.tMax, hw }, null);
                o.bx0 = o.by0 = Double.MAX_VALUE;
                o.bx1 = o.by1 = -Double.MAX_VALUE;
                for (double t : new double[] { b.tMin, b.tMax }) {
                    for (double s : new double[] { -hw, hw }) {
                        double x = b.ax + b.ux * t - b.uy * s, y = b.ay + b.uy * t + b.ux * s;
                        o.bx0 = Math.min(o.bx0, x);
                        o.by0 = Math.min(o.by0, y);
                        o.bx1 = Math.max(o.bx1, x);
                        o.by1 = Math.max(o.by1, y);
                    }
                }
                return o;
            }
            case SymbolOverlap.SINGLE: {
                double r = SymbolOverlap.SYMBOL_RADIUS;
                Obstacle o = new Obstacle(b.elm, DISC, new double[] { b.cx, b.cy, r }, null);
                o.bx0 = b.cx - r;
                o.by0 = b.cy - r;
                o.bx1 = b.cx + r;
                o.by1 = b.cy + r;
                return o;
            }
            default: {
                if (!(b.maxX > b.minX) || !(b.maxY > b.minY)) {
                    return null;
                }
                Obstacle o = new Obstacle(b.elm, RECT, new double[] { b.minX, b.minY, b.maxX, b.maxY }, null);
                o.bx0 = b.minX;
                o.by0 = b.minY;
                o.bx1 = b.maxX;
                o.by1 = b.maxY;
                return o;
            }
        }
    }

    // ---------------------------------------------------------------- rules

    private static void rules(List<CircuitElm> elms, List<MeasuringTextLayout.Measured> checked,
            Set<CircuitElm> withText, List<Issue> issues) {
        SymbolOverlap.Model model = SymbolOverlap.model(elms);
        Map<CircuitElm, List<MeasuringTextLayout.Measured>> own = new HashMap<>();
        for (MeasuringTextLayout.Measured m : checked) {
            List<MeasuringTextLayout.Measured> l = own.get(m.owner);
            if (l == null) {
                l = new ArrayList<>();
                own.put(m.owner, l);
            }
            l.add(m);
        }
        List<Obstacle> all = new ArrayList<>();
        for (SymbolOverlap.Body b : model.bodies) {
            boolean texted = b.kind == SymbolOverlap.SINGLE && withText.contains(b.elm);
            if (!texted) {
                Obstacle s = symbol(b);
                if (s != null) {
                    all.add(s);
                }
            }
            for (double[] l : b.lines) {
                double[] seg = texted ? stemBeforeText(l, own.get(b.elm)) : l;
                if (seg != null) {
                    all.add(line(b.elm, seg[0], seg[1], seg[2], seg[3]));
                }
            }
        }
        for (CircuitElm w : model.wires) {
            Point a = w.getPost(0), z = w.getPost(1);
            if (a != null && z != null && (a.x != z.x || a.y != z.y)) {
                all.add(line(w, a.x, a.y, z.x, z.y));
            }
        }
        for (MeasuringTextLayout.Measured m : checked) {
            // the text box grown by PAD (rule 3 grows both boxes; the spatial query must find it)
            Obstacle o = new Obstacle(m.owner, TEXT, null, m);
            o.bx0 = m.x1 - PAD;
            o.by0 = m.y1 - PAD;
            o.bx1 = m.x2 + PAD;
            o.by1 = m.y2 + PAD;
            all.add(o);
        }
        Grid grid = new Grid(all);

        // the texts of the element with the smaller ID first, then layout order (a stable sort)
        List<MeasuringTextLayout.Measured> order = new ArrayList<>(checked);
        Collections.sort(order, (p, q) -> p.owner.getElementId().compareTo(q.owner.getElementId()));

        // the obstacles near each text, once (the rules then run in order over them)
        List<List<Obstacle>> near = new ArrayList<>(order.size());
        for (MeasuringTextLayout.Measured m : order) {
            List<Obstacle> c = new ArrayList<>();
            grid.near(m.x1 - PAD, m.y1 - PAD, m.x2 + PAD, m.y2 + PAD, c);
            near.add(c);
        }
        Map<String, Issue> found = new LinkedHashMap<>();
        for (int rule = 1; rule <= 3; rule++) {
            for (int i = 0, n = order.size(); i < n; i++) {
                MeasuringTextLayout.Measured m = order.get(i);
                double x1 = m.x1 - PAD, y1 = m.y1 - PAD, x2 = m.x2 + PAD, y2 = m.y2 + PAD;
                List<Obstacle> c = near.get(i);
                for (int k = 0, nc = c.size(); k < nc; k++) {
                    Obstacle o = c.get(k);
                    if (o.elm == m.owner || !matches(rule, o, x1, y1, x2, y2)) {
                        continue;
                    }
                    if (rule == 3 && m.owner instanceof TextElm && o.elm instanceof TextElm) {
                        // lines of text elements stacked into a paragraph are one annotation
                        continue;
                    }
                    String pair = SymbolOverlap.pairKey(m.owner, o.elm);
                    if (found.containsKey(pair) || model.reports(m.owner, o.elm)) {
                        continue;
                    }
                    found.put(pair, overlapIssue(rule, m, o));
                }
            }
        }
        issues.addAll(found.values());
    }

    /** @return true when obstacle {@code o} meets the grown text box by rule {@code rule} */
    private static boolean matches(int rule, Obstacle o, double x1, double y1, double x2, double y2) {
        switch (rule) {
            case 1:
                return (o.kind == BAND && bandMeets(o.g, x1, y1, x2, y2))
                        || (o.kind == DISC && discMeets(o.g, x1, y1, x2, y2))
                        || (o.kind == RECT && x1 < o.g[2] && x2 > o.g[0] && y1 < o.g[3] && y2 > o.g[1]);
            case 2:
                return o.kind == LINE && segmentInside(o.g, x1, y1, x2, y2);
            default:
                // both boxes grown by PAD (a TEXT obstacle's box is stored grown)
                return o.kind == TEXT && x1 < o.bx1 && x2 > o.bx0 && y1 < o.by1 && y2 > o.by0;
        }
    }

    /**
     * Separating axes: the box and the band (from a along u over (tMin, tMax), half-width hw)
     * share an interior point when their projections overlap with a positive length on x, y, u and
     * the band's normal.
     */
    private static boolean bandMeets(double[] b, double x1, double y1, double x2, double y2) {
        double ax = b[0], ay = b[1], ux = b[2], uy = b[3], t0 = b[4], t1 = b[5], hw = b[6];
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double t : new double[] { t0, t1 }) {
            for (double s : new double[] { -hw, hw }) {
                double x = ax + ux * t - uy * s, y = ay + uy * t + ux * s;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        if (!(x1 < maxX && x2 > minX && y1 < maxY && y2 > minY)) {
            return false;
        }
        double tLo = Double.MAX_VALUE, tHi = -Double.MAX_VALUE, sLo = Double.MAX_VALUE, sHi = -Double.MAX_VALUE;
        for (double x : new double[] { x1, x2 }) {
            for (double y : new double[] { y1, y2 }) {
                double px = x - ax, py = y - ay;
                double t = px * ux + py * uy;
                double s = -px * uy + py * ux;
                tLo = Math.min(tLo, t);
                tHi = Math.max(tHi, t);
                sLo = Math.min(sLo, s);
                sHi = Math.max(sHi, s);
            }
        }
        return tLo < t1 && tHi > t0 && sLo < hw && sHi > -hw;
    }

    /**
     * The stem of a single element whose texts stand for its symbol ends where its own first
     * text box, grown by {@link #PAD}, begins: the text sits at the end of the stem.
     *
     * @return the stem part before the texts, or null when nothing of it is left
     */
    private static double[] stemBeforeText(double[] l, List<MeasuringTextLayout.Measured> texts) {
        double tEnd = 1;
        double dx = l[2] - l[0], dy = l[3] - l[1];
        for (MeasuringTextLayout.Measured m : texts) {
            double lo = 0, hi = 1;
            double[] p = { l[0], l[1] };
            double[] d = { dx, dy };
            double[] min = { m.x1 - PAD, m.y1 - PAD };
            double[] max = { m.x2 + PAD, m.y2 + PAD };
            boolean miss = false;
            for (int k = 0; k < 2 && !miss; k++) {
                if (d[k] == 0) {
                    miss = p[k] < min[k] || p[k] > max[k];
                    continue;
                }
                double ta = (min[k] - p[k]) / d[k], tb = (max[k] - p[k]) / d[k];
                lo = Math.max(lo, Math.min(ta, tb));
                hi = Math.min(hi, Math.max(ta, tb));
            }
            if (!miss && lo <= hi) {
                tEnd = Math.min(tEnd, lo);
            }
        }
        if (!(tEnd > 0)) {
            return null;
        }
        return new double[] { l[0], l[1], l[0] + dx * tEnd, l[1] + dy * tEnd };
    }

    /** The disc (cx, cy, r) and the box share an interior point: the box is closer than r. */
    private static boolean discMeets(double[] d, double x1, double y1, double x2, double y2) {
        double dx = Math.max(Math.max(x1 - d[0], 0), d[0] - x2);
        double dy = Math.max(Math.max(y1 - d[1], 0), d[1] - y2);
        return dx * dx + dy * dy < d[2] * d[2];
    }

    /** The segment has a point strictly inside the box (open interval clipping). */
    private static boolean segmentInside(double[] l, double x1, double y1, double x2, double y2) {
        double lo = 0, hi = 1;
        double[] p = { l[0], l[1] };
        double[] d = { l[2] - l[0], l[3] - l[1] };
        double[] min = { x1, y1 };
        double[] max = { x2, y2 };
        for (int k = 0; k < 2; k++) {
            if (d[k] == 0) {
                if (!(p[k] > min[k] && p[k] < max[k])) {
                    return false;
                }
                continue;
            }
            double ta = (min[k] - p[k]) / d[k], tb = (max[k] - p[k]) / d[k];
            lo = Math.max(lo, Math.min(ta, tb));
            hi = Math.min(hi, Math.max(ta, tb));
        }
        return lo < hi;
    }

    // ---------------------------------------------------------------- reporting

    /**
     * @return the string cut at {@link #TEXT_CUT} characters with an ellipsis: the TextBox
     *         {@code text} of checkLayout ([SP_AGA_02_16]); messages quote through {@link EchoText}
     */
    static String cut(String s) {
        return s.length() <= TEXT_CUT ? s : s.substring(0, TEXT_CUT) + "…";
    }

    private static Issue overlapIssue(int rule, MeasuringTextLayout.Measured m, Obstacle o) {
        String a = m.owner.getElementId(), b = o.elm.getElementId();
        String what = "Text \"" + EchoText.clip(m.placement.text, TEXT_CUT) + "\" of " + a;
        String message;
        switch (rule) {
            case 1:
                message = what + " lies over the symbol of " + b + ".";
                break;
            case 2:
                message = what + " is crossed by " + b + ".";
                break;
            default:
                message = what + " overlaps the text \"" + EchoText.clip(o.text.placement.text, TEXT_CUT) + "\" of " + b + ".";
                break;
        }
        String[] ids = a.compareTo(b) <= 0 ? new String[] { a, b } : new String[] { b, a };
        // the centre of the text's box (rounded outwards to whole pixels), at whole pixels
        double cx = (Math.floor(m.x1) + Math.ceil(m.x2)) / 2, cy = (Math.floor(m.y1) + Math.ceil(m.y2)) / 2;
        return Issue.of(IssueCode.TEXT_OVERLAP, message, HINT).elements(ids)
                .at(CellGeometry.toCells((int) Math.round(cx)), CellGeometry.toCells((int) Math.round(cy)));
    }

    private static Issue notCoveredIssue(List<CircuitElm> elms) {
        List<String> ids = new ArrayList<>();
        for (CircuitElm e : elms) {
            ids.add(e.getElementId());
        }
        Collections.sort(ids);
        List<String> first = ids.subList(0, Math.min(MAX_NOT_COVERED_IDS, ids.size()));
        CircuitElm e0 = elms.get(0);
        String cls = e0.getClass().getSimpleName();
        String message = "Texts of " + ids.size() + " " + e0.getJsonTypeName() + " element" + (ids.size() == 1 ? "" : "s")
                + " (class " + cls + ") are not laid out yet, so they are not checked: " + String.join(", ", first)
                + (ids.size() > first.size() ? ", …" : "") + ".";
        return Issue.of(IssueCode.TEXT_NOT_COVERED, message,
                "Look at the render (circuit_render) for these elements' texts.")
                .elements(first.toArray(new String[0]));
    }

    /** @return the TextPlacement alignment as a TextBox value */
    static String align(TextPlacement p) {
        return p.getAlign().json;
    }

    /** @return the TextPlacement baseline as a TextBox value */
    static String baseline(TextPlacement p) {
        return p.getBaseline().json;
    }
}
