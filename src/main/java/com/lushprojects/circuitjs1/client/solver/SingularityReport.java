package com.lushprojects.circuitjs1.client.solver;

/**
 * [SP_SLV_01_09] A failed factorization: the reduced column of the failed elimination step, the
 * row holding the largest candidate pivot and its magnitude (0 for a structural failure). The
 * engine fills {@link #unknown} (through {@code mapCol}) and {@link #variable} (it knows the nodes
 * and the voltage sources).
 */
public final class SingularityReport {

    /** Reduced column of the failed step, -1 when unknown. */
    public final int column;
    /** Reduced row of the largest candidate pivot, -1 when unknown or no candidate. */
    public final int row;
    /** Largest candidate pivot magnitude; 0 for a structural failure. */
    public final double pivotAbs;
    /** True when the system is structurally singular (no complete transversal). */
    public final boolean structural;
    /** Full-system index of the unknown of {@link #column}, -1 when unknown; set by the engine. */
    public int unknown = -1;
    /** Text naming the unknown, set by the engine. */
    public String variable;

    /** A report of the failed step; {@link #unknown} and {@link #variable} are filled later. */
    public SingularityReport(int column, int row, double pivotAbs, boolean structural) {
        this.column = column;
        this.row = row;
        this.pivotAbs = pivotAbs;
        this.structural = structural;
    }
}
