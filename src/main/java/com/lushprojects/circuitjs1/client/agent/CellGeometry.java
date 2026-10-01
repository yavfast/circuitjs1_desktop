package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONValue;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.CircuitEditor;

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
}
