package com.lushprojects.circuitjs1.client.solver;

/**
 * [SP_SLV_01_11] What the sparse path keeps from one stamp to the next within one engine analysis:
 * the reduction maps it belongs to, the positions added by pattern growth, and the last symbolic
 * analysis with the pattern it was built for. Reused by {@link LinearSystem#selectPath} only while
 * the analysis and the maps are unchanged.
 */
final class CarriedState {
    /** Engine analysis ({@code analysisCount}) it belongs to. */
    final int forAnalysis;
    /** Reduction maps of the stamp that produced it. */
    final int[] mapRow;
    final int[] mapCol;
    /** Positions added by pattern growth (reduced row, reduced column). */
    int[] extraRow = new int[8];
    int[] extraCol = new int[8];
    int extraCount;
    /** Last symbolic analysis, and the pattern structure it was built for. */
    SymbolicAnalysis symbolic;
    int[] symbolicColStart;
    int[] symbolicRowIndex;

    CarriedState(int forAnalysis, int[] mapRow, int[] mapCol) {
        this.forAnalysis = forAnalysis;
        this.mapRow = mapRow;
        this.mapCol = mapCol;
    }

    void addExtra(int r, int c) {
        if (extraCount == extraRow.length) {
            int[] a = new int[2 * extraCount];
            int[] b = new int[2 * extraCount];
            System.arraycopy(extraRow, 0, a, 0, extraCount);
            System.arraycopy(extraCol, 0, b, 0, extraCount);
            extraRow = a;
            extraCol = b;
        }
        extraRow[extraCount] = r;
        extraCol[extraCount] = c;
        extraCount++;
    }

    /** True when {@code p} has exactly the structure the carried symbolic analysis was built for. */
    boolean symbolicMatches(CscPattern p) {
        if (symbolic == null || symbolicColStart == null || symbolicColStart.length != p.colStart.length
                || symbolicRowIndex.length != p.nnz()) {
            return false;
        }
        for (int i = 0; i < symbolicColStart.length; i++) {
            if (symbolicColStart[i] != p.colStart[i]) {
                return false;
            }
        }
        for (int i = 0; i < symbolicRowIndex.length; i++) {
            if (symbolicRowIndex[i] != p.rowIndex[i]) {
                return false;
            }
        }
        return true;
    }
}
