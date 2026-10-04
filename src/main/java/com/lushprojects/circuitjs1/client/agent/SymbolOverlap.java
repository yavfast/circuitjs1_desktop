package com.lushprojects.circuitjs1.client.agent;

import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.GraphicElm;
import com.lushprojects.circuitjs1.client.element.WireElm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * [SP_AGA_03_05] {@code symbol_overlap}: wires or leads running through a symbol, and posts lying
 * on a foreign symbol or lead, from element geometry only (defining points and posts, never the
 * draw-time bounding box), so a never-drawn background document and the visible tab give the same
 * issues. Pixels throughout (16 px = 1 cell).
 *
 * <p>Every element except wires, graphic elements and post-less elements has a <em>symbol</em>
 * (a region) and <em>lines</em> (its leads, drawn like wires), by the catalogue geometry kind of
 * the instance ({@link Catalogue#geometryOf}):
 * <ul>
 * <li>{@code two_point}: line post0→post1; symbol = the band of half-width 6 px around the middle
 *     40 px of the segment ({@link #SYMBOL_HALF_LENGTH} each side of the centre), never closer than
 *     8 px to a post (CircuitJS draws the symbol in the middle and leads to the posts).</li>
 * <li>{@code single}: line start→end (the stem); symbol = the disc of radius
 *     {@value #SYMBOL_RADIUS} px around end. Text is not modelled.</li>
 * <li>{@code derived}: symbol = the axis-aligned rectangle spanning the posts (in a dimension where
 *     the posts span less than {@value #MIN_SHRINK_SPAN} px, i.e. lie in one row or column, also
 *     the defining points). A dimension wider than 16 px shrinks by {@value #LEAD_PX} px per side;
 *     a dimension of at most 16 px (a pot's wiper or a switch's control post one cell off the body)
 *     becomes a band of ±{@value #FLAT_GROW} px around the element's axis (the defining points'
 *     midpoint, clamped to the posts' span) and is sampled only on that centre line. When the
 *     start post stands alone on its side more
 *     than {@value #LEAD_REACH} px before the other posts (a transistor's base or a FET's gate
 *     drawn long), the stretch is a lead line and the rectangle starts {@value #LEAD_REACH} px
 *     before the nearest other post.</li>
 * </ul>
 * Rules (one issue per unordered element pair, the first rule below that finds it):
 * <ol>
 * <li>a post of A inside the symbol of B, or on a line of B (closer than
 *     {@value #ON_LINE_PX} px), when it is not one of B's posts;</li>
 * <li>a wire sample (every {@value #SAMPLE_STEP} px, none within {@value #SAMPLE_STEP} px of a
 *     wire end) inside the symbol of B;</li>
 * <li>a sample of A's lines (as wires) or of A's symbol (the end of a single, a
 *     {@value #LATTICE} px lattice of a derived rectangle) inside the symbol of B.</li>
 * </ol>
 * Samples within {@value #OWN_POST_TOLERANCE} px of one of B's posts are connections, not
 * overlaps. Crossing leads raise nothing, like crossing wires. Containment tests are strict.
 * The issue's {@code at} is the centre of B's symbol (two_point: the middle, single: end, derived:
 * the rectangle's centre), so its key stays stable while the other element moves.
 */
final class SymbolOverlap {

    static final int LEAD_PX = 8;
    static final double SYMBOL_HALF_LENGTH = 20;
    static final double TWO_POINT_HALF_WIDTH = 6;
    static final double SYMBOL_RADIUS = 8;
    static final int MIN_SHRINK_SPAN = 16;
    static final int FLAT_GROW = 6;
    static final int LEAD_REACH = 40;
    static final double ON_LINE_PX = 2;
    /** Wire and line sample spacing; also the part of each line end without samples. */
    static final int SAMPLE_STEP = 4;
    /** Lattice of derived symbol samples. */
    static final int LATTICE = 8;
    /** A sample this close to a post of the symbol's own element is a connection, not an overlap. */
    static final double OWN_POST_TOLERANCE = 2;
    /** Spatial hash bucket size, and the largest bucket array. */
    static final int BUCKET = 64;
    static final int MAX_BUCKETS = 1 << 18;

    static final int TWO_POINT = 0;
    static final int SINGLE = 1;
    static final int DERIVED = 2;

    private SymbolOverlap() {
    }

    /** The symbol and lines of one non-wire element (also the obstacles of {@link TextOverlap}). */
    static final class Body {
        final CircuitElm elm;
        final int kind;
        final Point[] posts;
        /** Lines {x1, y1, x2, y2}: leads, drawn like wires. */
        final List<double[]> lines = new ArrayList<>();
        // two_point: symbol band along a->b
        double ax, ay, ux, uy, tMin, tMax;
        // single: symbol disc
        double cx, cy;
        // centre of the symbol: the stable point ("at") of the issues it takes part in
        double centreX, centreY;
        // derived: symbol rectangle, and whether each dimension shrank (else a band around the axis)
        double minX, minY, maxX, maxY;
        boolean shrankX, shrankY;
        // bounding box of symbol and lines (spatial hash)
        double bx0 = Double.MAX_VALUE, by0 = Double.MAX_VALUE, bx1 = -Double.MAX_VALUE, by1 = -Double.MAX_VALUE;

        Body(CircuitElm elm, int kind, Point[] posts) {
            this.elm = elm;
            this.kind = kind;
            this.posts = posts;
        }

        void addLine(double x1, double y1, double x2, double y2) {
            if (x1 != x2 || y1 != y2) {
                lines.add(new double[] { x1, y1, x2, y2 });
                extend(Math.min(x1, x2) - ON_LINE_PX, Math.min(y1, y2) - ON_LINE_PX,
                        Math.max(x1, x2) + ON_LINE_PX, Math.max(y1, y2) + ON_LINE_PX);
            }
        }

        void extend(double x0, double y0, double x1, double y1) {
            bx0 = Math.min(bx0, x0);
            by0 = Math.min(by0, y0);
            bx1 = Math.max(bx1, x1);
            by1 = Math.max(by1, y1);
        }

        /** @return true when (x, y) lies strictly inside the symbol */
        boolean inSymbol(double x, double y) {
            switch (kind) {
                case TWO_POINT: {
                    double px = x - ax, py = y - ay;
                    double t = px * ux + py * uy;
                    return t > tMin && t < tMax && Math.abs(px * uy - py * ux) < TWO_POINT_HALF_WIDTH;
                }
                case SINGLE: {
                    double dx = x - cx, dy = y - cy;
                    return dx * dx + dy * dy < SYMBOL_RADIUS * SYMBOL_RADIUS;
                }
                default:
                    return x > minX && x < maxX && y > minY && y < maxY;
            }
        }

        /** @return true when (x, y) is closer than {@link #ON_LINE_PX} to the inside of one of the lines */
        boolean onLine(double x, double y) {
            for (double[] l : lines) {
                double dx = l[2] - l[0], dy = l[3] - l[1];
                double len = Math.sqrt(dx * dx + dy * dy);
                double px = x - l[0], py = y - l[1];
                double t = (px * dx + py * dy) / len;
                if (t > 0 && t < len && Math.abs(px * dy - py * dx) / len < ON_LINE_PX) {
                    return true;
                }
            }
            return false;
        }

        /** @return true when (x, y) is within {@code tol} px of one of the element's own posts */
        boolean nearOwnPost(double x, double y, double tol) {
            for (Point p : posts) {
                double dx = x - p.x, dy = y - p.y;
                if (dx * dx + dy * dy <= tol * tol) {
                    return true;
                }
            }
            return false;
        }

        boolean isOwnPost(Point q) {
            for (Point p : posts) {
                if (p.x == q.x && p.y == q.y) {
                    return true;
                }
            }
            return false;
        }

        /** @return the samples of the element's drawing: its lines (as wires) and its symbol */
        List<double[]> samples() {
            List<double[]> out = new ArrayList<>();
            for (double[] l : lines) {
                sampleLine(l[0], l[1], l[2], l[3], out);
            }
            if (kind == SINGLE) {
                out.add(new double[] { cx, cy });
            } else if (kind == DERIVED) {
                double[] xs = shrankX ? lattice(minX, maxX) : new double[] { (minX + maxX) / 2 };
                double[] ys = shrankY ? lattice(minY, maxY) : new double[] { (minY + maxY) / 2 };
                for (double x : xs) {
                    for (double y : ys) {
                        out.add(new double[] { x, y });
                    }
                }
            }
            return out;
        }

        private static double[] lattice(double lo, double hi) {
            int n = (int) Math.floor((hi - lo) / LATTICE) + 1;
            boolean addHi = lo + (n - 1) * LATTICE < hi;
            double[] v = new double[addHi ? n + 1 : n];
            for (int i = 0; i < n; i++) {
                v[i] = lo + i * LATTICE;
            }
            if (addHi) {
                v[n] = hi;
            }
            return v;
        }
    }

    /** Samples a line every {@link #SAMPLE_STEP} px, none within {@link #SAMPLE_STEP} px of an end. */
    private static void sampleLine(double x1, double y1, double x2, double y2, List<double[]> out) {
        double len = Math.hypot(x2 - x1, y2 - y1);
        if (len <= 2 * SAMPLE_STEP) {
            return;
        }
        double ux = (x2 - x1) / len, uy = (y2 - y1) / len;
        for (double t = SAMPLE_STEP; t <= len - SAMPLE_STEP; t += SAMPLE_STEP) {
            out.add(new double[] { x1 + ux * t, y1 + uy * t });
        }
    }

    /** @return the symbol and lines of {@code elm}, or null when it has none (wire, graphic, no posts) */
    private static Body bodyOf(CircuitElm elm) {
        if (elm instanceof WireElm || elm instanceof GraphicElm) {
            return null;
        }
        int pc = elm.getPostCount();
        if (pc <= 0) {
            return null;
        }
        Point[] posts = new Point[pc];
        for (int j = 0; j < pc; j++) {
            posts[j] = elm.getPost(j);
            if (posts[j] == null) {
                return null;
            }
        }
        int x1 = elm.getX(), y1 = elm.getY(), x2 = elm.getX2(), y2 = elm.getY2();
        String geometry = Catalogue.geometryOf(posts, x1, y1, x2, y2);
        if ("two_point".equals(geometry)) {
            Body b = new Body(elm, TWO_POINT, posts);
            Point a = posts[0], z = posts[1];
            double len = Math.hypot(z.x - a.x, z.y - a.y);
            if (len > 0) {
                b.ax = a.x;
                b.ay = a.y;
                b.ux = (z.x - a.x) / len;
                b.uy = (z.y - a.y) / len;
            }
            b.tMin = Math.max(LEAD_PX, len / 2 - SYMBOL_HALF_LENGTH);
            b.tMax = Math.min(len - LEAD_PX, len / 2 + SYMBOL_HALF_LENGTH);
            b.centreX = (a.x + z.x) / 2.0;
            b.centreY = (a.y + z.y) / 2.0;
            b.addLine(a.x, a.y, z.x, z.y);
            b.extend(Math.min(a.x, z.x) - TWO_POINT_HALF_WIDTH, Math.min(a.y, z.y) - TWO_POINT_HALF_WIDTH,
                    Math.max(a.x, z.x) + TWO_POINT_HALF_WIDTH, Math.max(a.y, z.y) + TWO_POINT_HALF_WIDTH);
            return b;
        }
        if ("single".equals(geometry)) {
            Body b = new Body(elm, SINGLE, posts);
            b.cx = x2;
            b.cy = y2;
            b.centreX = x2;
            b.centreY = y2;
            b.addLine(x1, y1, x2, y2);
            b.extend(x2 - SYMBOL_RADIUS, y2 - SYMBOL_RADIUS, x2 + SYMBOL_RADIUS, y2 + SYMBOL_RADIUS);
            return b;
        }
        return derivedBody(elm, posts, x1, y1, x2, y2);
    }

    private static Body derivedBody(CircuitElm elm, Point[] posts, int x1, int y1, int x2, int y2) {
        Body b = new Body(elm, DERIVED, posts);
        // a lead post: the start post alone on its side of the start->end axis, far from the others
        boolean alongX = Math.abs(x2 - x1) >= Math.abs(y2 - y1);
        int sign = alongX ? Integer.signum(x2 - x1) : Integer.signum(y2 - y1);
        int leadIndex = -1;
        double nearest = Double.MAX_VALUE;
        for (int j = 0; j < posts.length; j++) {
            if (posts[j].x == x1 && posts[j].y == y1 && leadIndex < 0) {
                leadIndex = j;
            }
        }
        if (leadIndex >= 0 && sign != 0 && posts.length > 1) {
            int start = alongX ? x1 : y1;
            for (int j = 0; j < posts.length; j++) {
                if (j != leadIndex) {
                    double d = sign * ((alongX ? posts[j].x : posts[j].y) - start);
                    nearest = Math.min(nearest, d);
                }
            }
            if (!(nearest > LEAD_REACH)) {
                leadIndex = -1;
            }
        } else {
            leadIndex = -1;
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int j = 0; j < posts.length; j++) {
            if (j == leadIndex) {
                continue;
            }
            minX = Math.min(minX, posts[j].x);
            maxX = Math.max(maxX, posts[j].x);
            minY = Math.min(minY, posts[j].y);
            maxY = Math.max(maxY, posts[j].y);
        }
        if (leadIndex >= 0) {
            // the symbol starts LEAD_REACH px before the nearest other post; the rest is a lead line
            double edge = (alongX ? x1 : y1) + sign * (nearest - LEAD_REACH);
            int e = (int) Math.round(edge);
            if (alongX) {
                minX = Math.min(minX, e);
                maxX = Math.max(maxX, e);
                minY = Math.min(minY, y1);
                maxY = Math.max(maxY, y1);
                b.addLine(x1, y1, e, y1);
            } else {
                minY = Math.min(minY, e);
                maxY = Math.max(maxY, e);
                minX = Math.min(minX, x1);
                maxX = Math.max(maxX, x1);
                b.addLine(x1, y1, x1, e);
            }
        }
        // posts in one column or row (a chip with pins on one side): the defining points give the depth
        if (maxX - minX < MIN_SHRINK_SPAN) {
            minX = Math.min(minX, Math.min(x1, x2));
            maxX = Math.max(maxX, Math.max(x1, x2));
        }
        if (maxY - minY < MIN_SHRINK_SPAN) {
            minY = Math.min(minY, Math.min(y1, y2));
            maxY = Math.max(maxY, Math.max(y1, y2));
        }
        // wider than 2 * LEAD_PX: shrink by LEAD_PX per side; thinner (posts in one row, a pot's
        // wiper or a switch's control post one cell off the body): a band around the axis
        b.shrankX = maxX - minX > 2 * LEAD_PX;
        b.shrankY = maxY - minY > 2 * LEAD_PX;
        if (b.shrankX) {
            b.minX = minX + LEAD_PX;
            b.maxX = maxX - LEAD_PX;
        } else {
            double c = Math.max(minX, Math.min(maxX, (x1 + x2) / 2.0));
            b.minX = c - FLAT_GROW;
            b.maxX = c + FLAT_GROW;
        }
        if (b.shrankY) {
            b.minY = minY + LEAD_PX;
            b.maxY = maxY - LEAD_PX;
        } else {
            double c = Math.max(minY, Math.min(maxY, (y1 + y2) / 2.0));
            b.minY = c - FLAT_GROW;
            b.maxY = c + FLAT_GROW;
        }
        b.centreX = (b.minX + b.maxX) / 2;
        b.centreY = (b.minY + b.maxY) / 2;
        b.extend(b.minX, b.minY, b.maxX, b.maxY);
        return b;
    }

    /**
     * Spatial hash of the bodies: a dense array of buckets over the bodies' extent,
     * {@link #BUCKET} px wide (doubled while the array would exceed {@link #MAX_BUCKETS}).
     */
    private static final class Grid {
        final double x0, y0;
        final int size, nx, ny;
        final List<Body>[] buckets;

        @SuppressWarnings("unchecked")
        Grid(List<Body> bodies) {
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (Body b : bodies) {
                minX = Math.min(minX, b.bx0);
                minY = Math.min(minY, b.by0);
                maxX = Math.max(maxX, b.bx1);
                maxY = Math.max(maxY, b.by1);
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
            for (Body b : bodies) {
                int ix1 = ix(b.bx1), iy1 = iy(b.by1);
                for (int ix = ix(b.bx0); ix <= ix1; ix++) {
                    for (int iy = iy(b.by0); iy <= iy1; iy++) {
                        List<Body> l = buckets[iy * nx + ix];
                        if (l == null) {
                            l = new ArrayList<>();
                            buckets[iy * nx + ix] = l;
                        }
                        l.add(b);
                    }
                }
            }
        }

        private int ix(double x) {
            return (int) ((x - x0) / size);
        }

        private int iy(double y) {
            return (int) ((y - y0) / size);
        }

        /** @return the bodies whose bucket holds (x, y), or null */
        List<Body> at(double x, double y) {
            if (x < x0 || y < y0) {
                return null;
            }
            int ix = ix(x), iy = iy(y);
            return ix < nx && iy < ny ? buckets[iy * nx + ix] : null;
        }
    }

    /**
     * Adds the {@code symbol_overlap} issues of {@code elms}: one per unordered element pair,
     * IDs sorted, ordered by pair.
     */
    static void check(List<CircuitElm> elms, List<Issue> issues) {
        issues.addAll(findHits(elms, new ArrayList<Body>(), new ArrayList<CircuitElm>()).values());
    }

    /**
     * [SP_AGA_03_13] The body model of a document (the obstacles of the text layout check) and
     * the element pairs {@code symbol_overlap} reports (exempt from {@code text_overlap}).
     */
    static final class Model {
        final List<Body> bodies = new ArrayList<>();
        final List<CircuitElm> wires = new ArrayList<>();
        /** {@link #pairKey} → whether {@link #check} reports the pair (computed on demand). */
        private final Map<String, Boolean> reported = new HashMap<>();

        /**
         * @return true when {@link #check} reports {@code symbol_overlap} for the pair: the rules of
         *     {@link #findHits} applied to these two elements only (the spatial hash there only
         *     narrows the candidates, so the answer is the same)
         */
        boolean reports(CircuitElm a, CircuitElm b) {
            String key = pairKey(a, b);
            Boolean r = reported.get(key);
            if (r == null) {
                Body ba = a instanceof WireElm ? null : bodyOf(a), bb = b instanceof WireElm ? null : bodyOf(b);
                r = pairOverlaps(a, ba, b, bb) || pairOverlaps(b, bb, a, ba);
                reported.put(key, r);
            }
            return r;
        }
    }

    /** @return the body model of {@code elms}; the pairs {@link #check} reports come from {@link Model#reports} */
    static Model model(List<CircuitElm> elms) {
        Model m = new Model();
        for (CircuitElm elm : elms) {
            if (elm instanceof WireElm) {
                m.wires.add(elm);
                continue;
            }
            Body b = bodyOf(elm);
            if (b != null) {
                m.bodies.add(b);
            }
        }
        return m;
    }

    /**
     * The rules of {@link #findHits} that let element {@code a} (its posts, its wire or its drawing)
     * meet the symbol or lines of {@code b}: rule 1 (a post of a), rule 2 (a as a wire), rule 3 (a's
     * body samples). {@code ba}/{@code bb}: their bodies, or null (wire, graphic, no posts).
     */
    private static boolean pairOverlaps(CircuitElm a, Body ba, CircuitElm b, Body bb) {
        if (bb == null || a == b) {
            return false;
        }
        for (int j = 0; j < a.getPostCount(); j++) {
            Point p = a.getPost(j);
            if (p != null && !bb.isOwnPost(p) && (bb.inSymbol(p.x, p.y) || bb.onLine(p.x, p.y))) {
                return true;
            }
        }
        List<double[]> samples = new ArrayList<>();
        if (a instanceof WireElm) {
            Point p = a.getPost(0), z = a.getPost(1);
            if (p != null && z != null) {
                sampleLine(p.x, p.y, z.x, z.y, samples);
            }
        } else if (ba != null) {
            samples = ba.samples();
        }
        for (double[] s : samples) {
            if (bb.inSymbol(s[0], s[1]) && !bb.nearOwnPost(s[0], s[1], OWN_POST_TOLERANCE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The rules of {@link #check}: fills {@code bodies} and {@code wires} and returns the issue of
     * each overlapping pair by {@link #pairKey}, ordered by pair.
     */
    private static Map<String, Issue> findHits(List<CircuitElm> elms, List<Body> bodies, List<CircuitElm> wires) {
        for (CircuitElm elm : elms) {
            if (elm instanceof WireElm) {
                wires.add(elm);
                continue;
            }
            Body b = bodyOf(elm);
            if (b != null) {
                bodies.add(b);
            }
        }
        if (bodies.isEmpty()) {
            return new TreeMap<>();
        }
        Grid grid = new Grid(bodies);
        // one issue per pair: the first rule that finds the pair names it
        Map<String, Issue> hits = new TreeMap<>();

        // 1. a post of A inside the symbol or on a line of B, not one of B's posts. B is never a
        // wire, so the post_on_wire_body issue of the same post never coincides with this one.
        for (CircuitElm a : elms) {
            int pc = a.getPostCount();
            String[] pins = null;
            for (int j = 0; j < pc; j++) {
                Point p = a.getPost(j);
                List<Body> near = p == null ? null : grid.at(p.x, p.y);
                if (near == null) {
                    continue;
                }
                for (Body b : near) {
                    if (b.elm == a || b.isOwnPost(p) || !(b.inSymbol(p.x, p.y) || b.onLine(p.x, p.y))) {
                        continue;
                    }
                    String pair = pairKey(a, b.elm);
                    if (hits.containsKey(pair)) {
                        continue;
                    }
                    if (pins == null) {
                        pins = PinNames.of(a);
                    }
                    String ref = Connectivity.postRef(a, pins, j);
                    hits.put(pair, issue(a, b, "Post " + ref + " at " + cells(p.x, p.y) + " lies on "
                            + (b.inSymbol(p.x, p.y) ? "the symbol" : "a lead") + " of " + describe(b)
                            + " without being one of its posts."));
                }
            }
        }

        // 2. a wire through a symbol
        List<double[]> samples = new ArrayList<>();
        for (CircuitElm w : wires) {
            Point a = w.getPost(0), z = w.getPost(1);
            if (a == null || z == null) {
                continue;
            }
            samples.clear();
            sampleLine(a.x, a.y, z.x, z.y, samples);
            for (double[] s : samples) {
                List<Body> near = grid.at(s[0], s[1]);
                if (near == null) {
                    continue;
                }
                for (Body b : near) {
                    if (!b.inSymbol(s[0], s[1]) || b.nearOwnPost(s[0], s[1], OWN_POST_TOLERANCE)) {
                        continue;
                    }
                    String pair = pairKey(w, b.elm);
                    if (!hits.containsKey(pair)) {
                        hits.put(pair, issue(w, b, "Wire " + w.getElementId() + " runs through the symbol of "
                                + describe(b) + "."));
                    }
                }
            }
        }

        // 3. the drawing (lines, symbol) of A through the symbol of B
        for (Body a : bodies) {
            for (double[] s : a.samples()) {
                List<Body> near = grid.at(s[0], s[1]);
                if (near == null) {
                    continue;
                }
                for (Body b : near) {
                    if (b == a || !b.inSymbol(s[0], s[1]) || b.nearOwnPost(s[0], s[1], OWN_POST_TOLERANCE)) {
                        continue;
                    }
                    String pair = pairKey(a.elm, b.elm);
                    if (!hits.containsKey(pair)) {
                        hits.put(pair, issue(a.elm, b, a.elm.getElementId() + " (" + a.elm.getJsonTypeName()
                                + ") runs through the symbol of " + describe(b) + "."));
                    }
                }
            }
        }
        return hits;
    }

    /**
     * @return the issue of the pair (a, b.elm); {@code at} is the centre of b's symbol, so the
     *     issue key stays the same while a moves and the pair still overlaps
     */
    private static Issue issue(CircuitElm a, Body b, String message) {
        String ia = a.getElementId(), ib = b.elm.getElementId();
        String[] ids = ia.compareTo(ib) <= 0 ? new String[] { ia, ib } : new String[] { ib, ia };
        return Issue.of(IssueCode.SYMBOL_OVERLAP, message,
                "Move one of them, or route the wire around the symbol; wires and symbols may only meet at posts.")
                .elements(ids).at(CellGeometry.toCells((int) Math.round(b.centreX)), CellGeometry.toCells((int) Math.round(b.centreY)));
    }

    static String pairKey(CircuitElm a, CircuitElm b) {
        String ia = a.getElementId(), ib = b.getElementId();
        return ia.compareTo(ib) <= 0 ? ia + "|" + ib : ib + "|" + ia;
    }

    /** @return "R1 (Resistor, symbol at (2, 0))": the point is the issue's {@code at} */
    private static String describe(Body b) {
        return b.elm.getElementId() + " (" + b.elm.getJsonTypeName() + ", symbol at " + cells(b.centreX, b.centreY) + ")";
    }

    private static String cells(double x, double y) {
        return "(" + fmt(CellGeometry.toCells((int) Math.round(x))) + ", " + fmt(CellGeometry.toCells((int) Math.round(y))) + ")";
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }
}
