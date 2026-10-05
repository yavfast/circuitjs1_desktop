package com.lushprojects.circuitjs1.client.solver;

/**
 * [SP_SLV_01_07] Maximum transversal (MC21-style depth-first augmenting paths with lookahead, as
 * CSparse {@code cs_maxtrans}): pairs every column with a row holding a structural entry, so the
 * paired system has a zero-free diagonal. Needed because voltage-source rows have zero diagonals
 * and the row reduction numbers rows and columns independently (solver-performance Pitfall 1).
 * The cheap first pass prefers the structural diagonal, then the largest magnitude.
 */
public final class Transversal {

    /** {@code colPerm[k]}: the column paired with row {@code k}; meaningful when not singular. */
    public final int[] colPerm;
    /** True when fewer than m pairs exist. */
    public final boolean structurallySingular;
    /** The lowest column without an augmenting path when singular, else -1. */
    public final int unmatchedCol;

    private Transversal(int[] colPerm, int unmatchedCol) {
        this.colPerm = colPerm;
        this.unmatchedCol = unmatchedCol;
        this.structurallySingular = unmatchedCol >= 0;
    }

    /** Computes the transversal of {@code a}. */
    public static Transversal compute(CscPattern a) {
        final int m = a.m;
        final int[] cs = a.colStart;
        final int[] ri = a.rowIndex;
        final double[] va = a.values;
        int[] rowOfCol = new int[m];
        int[] colOfRow = new int[m];
        for (int i = 0; i < m; i++) {
            rowOfCol[i] = -1;
            colOfRow[i] = -1;
        }
        // cheap assignment: the structural diagonal first, then the largest free entry
        for (int j = 0; j < m; j++) {
            int best = -1;
            double bv = -1;
            for (int p = cs[j]; p < cs[j + 1]; p++) {
                int i = ri[p];
                if (colOfRow[i] >= 0) {
                    continue;
                }
                double v = i == j ? Double.POSITIVE_INFINITY : Math.abs(va[p]);
                if (v > bv) {
                    bv = v;
                    best = i;
                }
            }
            if (best >= 0) {
                rowOfCol[j] = best;
                colOfRow[best] = j;
            }
        }
        // augmenting paths for the columns left unmatched
        int[] cheap = new int[m];
        int[] visited = new int[m];
        for (int j = 0; j < m; j++) {
            cheap[j] = cs[j];
            visited[j] = -1;
        }
        int[] stackJ = new int[m];
        int[] stackP = new int[m];
        for (int j0 = 0; j0 < m; j0++) {
            if (rowOfCol[j0] >= 0) {
                continue;
            }
            int head = 0;
            stackJ[0] = j0;
            stackP[0] = cs[j0];
            visited[j0] = j0;
            int found = lookahead(j0, cs, ri, colOfRow, cheap);
            while (found < 0 && head >= 0) {
                int j = stackJ[head];
                boolean advanced = false;
                int end = cs[j + 1];
                for (int p = stackP[head]; p < end; p++) {
                    int j2 = colOfRow[ri[p]];
                    if (j2 >= 0 && visited[j2] != j0) {
                        visited[j2] = j0;
                        stackP[head] = p + 1;
                        stackJ[++head] = j2;
                        stackP[head] = cs[j2];
                        advanced = true;
                        found = lookahead(j2, cs, ri, colOfRow, cheap);
                        break;
                    }
                }
                if (!advanced) {
                    stackP[head] = end;
                    head--;
                }
            }
            if (found < 0) {
                return new Transversal(null, j0);
            }
            int i = found;
            for (int k = head; k >= 0; k--) {
                int j = stackJ[k];
                int prev = rowOfCol[j];
                rowOfCol[j] = i;
                colOfRow[i] = j;
                i = prev;
            }
        }
        int[] colPerm = new int[m];
        for (int j = 0; j < m; j++) {
            colPerm[rowOfCol[j]] = j;
        }
        return new Transversal(colPerm, -1);
    }

    /** A free row of column {@code j} after its cheap pointer, or -1 (the pointer advances). */
    private static int lookahead(int j, int[] cs, int[] ri, int[] colOfRow, int[] cheap) {
        int end = cs[j + 1];
        for (int p = cheap[j]; p < end; p++) {
            if (colOfRow[ri[p]] < 0) {
                cheap[j] = p + 1;
                return ri[p];
            }
        }
        cheap[j] = end;
        return -1;
    }
}
