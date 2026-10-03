package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONValue;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.CircuitEditor;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.element.CircuitElm;

import java.util.List;
import java.util.function.Supplier;

/**
 * [SP_AGA_01_01] Cells and CellPoints, and the geometry rules of [SP_AGA_03_01].
 * <ul>
 * <li>One cell is {@link #CELL_PX} = 16 editor pixels, whatever the grid setting.</li>
 * <li>Edits ({@code add}, {@code move}, {@code by}) use the half-cell lattice; imports accept
 *     every whole pixel (1/16 cell), so a circuit read with {@code getCircuit} re-imports
 *     unchanged.</li>
 * <li>Input geometry is never snapped or rounded: an off-lattice value is {@code off_lattice}.</li>
 * <li>Geometry computed inside an agent operation runs with the editor grid pinned to the target
 *     document's own grid option ({@link #withPinnedGrid}).</li>
 * </ul>
 */
final class CellGeometry {

    /** Editor pixels per cell. */
    static final int CELL_PX = 16;
    /** Coordinates lie within ±MAX_CELLS. */
    static final double MAX_CELLS = 4096;
    /** Lattice of agent edits: multiples of 0.5 cell. */
    static final double EDIT_LATTICE = 0.5;
    /** Lattice of imports: multiples of 1/16 cell (whole pixels). */
    static final double IMPORT_LATTICE = 1.0 / CELL_PX;

    private CellGeometry() {
    }

    /** @return the exact pixel value of a cell coordinate on the import lattice */
    static int toPx(double cells) {
        return (int) Math.round(cells * CELL_PX);
    }

    /** @return the exact cell value of an editor pixel coordinate (a multiple of 1/16) */
    static double toCells(int px) {
        return px / (double) CELL_PX;
    }

    /** @return true when {@code v} is a whole multiple of {@code lattice} (0.5 or 1/16; exact in binary) */
    static boolean onLattice(double v, double lattice) {
        double q = v / lattice;
        return q == Math.rint(q);
    }

    /** @return {x, y} as a CellPoint JSON object, from editor pixels */
    static JSONObject cellPoint(int xPx, int yPx) {
        JSONObject o = new JSONObject();
        o.put("x", new JSONNumber(toCells(xPx)));
        o.put("y", new JSONNumber(toCells(yPx)));
        return o;
    }

    /**
     * Reads a CellPoint argument and checks its range and lattice.
     *
     * @param value   the JSON value ({x, y})
     * @param where   argument path for messages, e.g. {@code edits[0].element.start}
     * @param lattice {@link #EDIT_LATTICE} or {@link #IMPORT_LATTICE}
     * @param issues  receives {@code invalid_value} / {@code off_lattice}
     * @param subject element ID or {@code #<i>} the issue names (may be null)
     * @return {x, y} in cells, or null when invalid
     */
    static double[] readPoint(JSONValue value, String where, double lattice, List<Issue> issues, String subject) {
        JSONObject o = value == null ? null : value.isObject();
        if (o == null) {
            issues.add(withSubject(Issue.of(IssueCode.INVALID_VALUE,
                    "Argument '" + where + "' must be a CellPoint {x, y}.",
                    "Pass the point in cells, e.g. {\"x\": 2, \"y\": 3}."), subject));
            return null;
        }
        double[] p = new double[2];
        String[] axes = { "x", "y" };
        for (int i = 0; i < 2; i++) {
            JSONValue v = o.get(axes[i]);
            JSONNumber n = v == null ? null : v.isNumber();
            if (n == null || Double.isNaN(n.doubleValue()) || Double.isInfinite(n.doubleValue())) {
                issues.add(withSubject(Issue.of(IssueCode.INVALID_VALUE,
                        "Argument '" + where + "." + axes[i] + "' must be a number.",
                        "Pass the coordinate in cells as a JSON number."), subject));
                return null;
            }
            double c = n.doubleValue();
            if (Math.abs(c) > MAX_CELLS) {
                issues.add(withSubject(Issue.of(IssueCode.INVALID_VALUE,
                        "Argument '" + where + "." + axes[i] + "' is out of range.",
                        "Use a coordinate from -4096 to 4096 cells."), subject));
                return null;
            }
            if (!onLattice(c, lattice)) {
                issues.add(withSubject(Issue.of(IssueCode.OFF_LATTICE,
                        "Argument '" + where + "." + axes[i] + "' = " + c + " is not a multiple of "
                                + latticeText(lattice) + " cell.",
                        lattice == EDIT_LATTICE ? "Use a multiple of 0.5 cell, e.g. " + Math.rint(c * 2) / 2 + "."
                                : "Use whole pixels: a multiple of 1/16 cell."), subject));
                return null;
            }
            p[i] = c;
        }
        return p;
    }

    static String latticeText(double lattice) {
        return lattice == EDIT_LATTICE ? "0.5" : "1/16";
    }

    static Issue withSubject(Issue issue, String subject) {
        return subject == null ? issue : issue.elements(subject);
    }

    /**
     * Runs {@code op} with the editor grid of {@code doc} pinned to the document's own grid option
     * ([SP_AGA_03_01] "Pinned grid size"): 16 px, or 8 px when its options select the small grid,
     * with the matching mask and rounding. Afterwards the grid is derived again from the option
     * the document has then: an import may have changed it (the content selects the grid), and
     * the editor grid must always match the document's own option. Call it while {@code doc} is
     * bound (inside {@code DocumentScope}), where the session grid option is the document's own;
     * the grid preference of another tab never applies.
     */
    static <T> T withPinnedGrid(CircuitDocument doc, Supplier<T> op) {
        CircuitEditor editor = doc.circuitEditor;
        editor.setGrid();
        try {
            return op.get();
        } finally {
            editor.setGrid();
        }
    }

    /**
     * [SP_AGA_03_01] "Collapsed posts" and "End kept": checks an element just placed by
     * {@code add}, a {@code move} with start and end, or an AgentCircuit import.
     * <ul>
     * <li>Posts that the type keeps on distinct points at its default placement and that now
     *     coincide (a box element given a zero-width or zero-height box) give {@code zero_length}
     *     (error): the call is rejected.</li>
     * <li>A supplied end that is neither horizontal nor vertical from the start of an element
     *     the editor places only on an axis ({@link CircuitElm#isAxisBound}) gives
     *     {@code not_axis_aligned} (error): such an element would be drawn rotated, with its
     *     derived posts off the lattice.</li>
     * <li>A supplied end that the element replaced by one of its own (it derives its size from
     *     its properties or snaps it to its axis) gives {@code value_adjusted} (warning) with the
     *     effective end, since input geometry is never adjusted silently.</li>
     * </ul>
     *
     * @param end the end the call supplied, in cells, or null (default size, or a move by start)
     * @return the error, or null; the warning, if any, is added to {@code warnings} (also when an
     *         error is returned: the caller rejects with {@link #rejection})
     */
    static Issue checkPlacement(CircuitElm elm, Catalogue.TypeInfo type, double[] end, String subject,
            List<Issue> warnings) {
        if (end != null && elm.isAxisBound() && end[0] != toCells(elm.getX()) && end[1] != toCells(elm.getY())) {
            String hint = "This type is placed only horizontally or vertically: give an end on the row or column of start";
            if (type != null) {
                hint += ", e.g. start + defaultSize (" + num(type.dx) + ", " + num(type.dy) + ") from describeType";
            }
            return Issue.of(IssueCode.NOT_AXIS_ALIGNED, "The end " + pointText(CellGeometry.toPx(end[0]), CellGeometry.toPx(end[1]))
                    + " of " + subject + " is neither horizontal nor vertical from its start " + pointText(elm.getX(), elm.getY()) + ".",
                    hint + ".").elements(subject).at(toCells(elm.getX()), toCells(elm.getY()));
        }
        int n = elm.getPostCount();
        Point[] posts = new Point[n];
        for (int i = 0; i < n; i++) {
            posts[i] = elm.getPost(i);
        }
        int distinct = Catalogue.distinctPoints(posts);
        // the replaced end is reported also when the call is rejected for collapsed posts
        if (end != null && (toCells(elm.getX2()) != end[0] || toCells(elm.getY2()) != end[1])) {
            warnings.add(Issue.of(IssueCode.VALUE_ADJUSTED, subject + ".end is " + pointText(elm.getX2(), elm.getY2())
                    + " instead of the requested (" + num(end[0]) + ", " + num(end[1]) + ").",
                    "The element derives its end from its own size rules; use the effective end and read the posts from the record.")
                    .elements(subject));
        }
        if (type != null && type.postsDistinct && n > 1 && distinct < n) {
            return Issue.of(IssueCode.ZERO_LENGTH, "The geometry of " + subject + " puts its " + n + " posts on "
                    + distinct + (distinct == 1 ? " point" : " points") + " (start " + pointText(elm.getX(), elm.getY())
                    + ", end " + pointText(elm.getX2(), elm.getY2()) + ").",
                    "Give an end that spans the element along its axis, e.g. start + defaultSize (" + num(type.dx) + ", "
                            + num(type.dy) + ") from describeType; a box element needs a width and a height.")
                    .elements(subject).at(toCells(elm.getX()), toCells(elm.getY()));
        }
        return null;
    }

    /** @return the issues of a placement rejection: the error first, then the warnings so far */
    static List<Issue> rejection(Issue error, List<Issue> warnings) {
        List<Issue> all = new java.util.ArrayList<>();
        all.add(error);
        for (Issue w : warnings) {
            if (w.getCode() == IssueCode.VALUE_ADJUSTED) {
                all.add(w);
            }
        }
        return all;
    }

    private static String pointText(int xPx, int yPx) {
        return "(" + num(toCells(xPx)) + ", " + num(toCells(yPx)) + ")";
    }

    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
