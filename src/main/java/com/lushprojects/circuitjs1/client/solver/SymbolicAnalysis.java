package com.lushprojects.circuitjs1.client.solver;

/**
 * [SP_SLV_01_07] Symbolic analysis of one pattern (step S of [SP_SLV_02_07]): the column pairing
 * of the maximum transversal and the fill-reducing order of the paired system. Step {@code s}
 * eliminates reduced column {@code colPerm[order[s]]} and prefers row {@code order[s]} as pivot.
 * Immutable after creation.
 */
public final class SymbolicAnalysis {

    /** {@code colPerm[k]}: column paired with row {@code k} (null when structurally singular). */
    public final int[] colPerm;
    /** {@code order[s]}: paired index eliminated at step {@code s} (null when singular). */
    public final int[] order;
    /** {@code patternVersion} this analysis was built for. */
    public final int builtFor;
    /** True when the transversal found fewer than m pairs. */
    public final boolean structurallySingular;
    /** Lowest unmatched reduced column when singular, else -1. */
    public final int unmatchedCol;

    private SymbolicAnalysis(int[] colPerm, int[] order, int builtFor, int unmatchedCol) {
        this.colPerm = colPerm;
        this.order = order;
        this.builtFor = builtFor;
        this.unmatchedCol = unmatchedCol;
        this.structurallySingular = unmatchedCol >= 0;
    }

    /** Builds the analysis of {@code a}'s pattern for {@code patternVersion}. */
    public static SymbolicAnalysis build(CscPattern a, int patternVersion) {
        Transversal t = Transversal.compute(a);
        if (t.structurallySingular) {
            return new SymbolicAnalysis(null, null, patternVersion, t.unmatchedCol);
        }
        return new SymbolicAnalysis(t.colPerm, MinimumDegree.order(a, t.colPerm), patternVersion, -1);
    }
}
