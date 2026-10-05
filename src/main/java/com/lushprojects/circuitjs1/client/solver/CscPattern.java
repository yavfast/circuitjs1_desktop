package com.lushprojects.circuitjs1.client.solver;

/**
 * [SP_SLV_01_04] Compressed-column pattern of a square matrix with its values: column {@code c}
 * holds the rows {@code rowIndex[colStart[c] .. colStart[c+1]-1]} in ascending order, aligned with
 * {@code values}. A position of the pattern is a slot; a slot may hold the value 0 (a structural
 * entry). The arrays are plain Java arrays (plain JS arrays under GWT).
 */
public final class CscPattern {

    /** Matrix size (rows = columns). */
    public final int m;
    /** Column starts, length {@code m + 1}. */
    public final int[] colStart;
    /** Row of each slot, ascending within a column. */
    public final int[] rowIndex;
    /** Value of each slot. */
    public final double[] values;

    private CscPattern(int m, int[] colStart, int[] rowIndex, double[] values) {
        this.m = m;
        this.colStart = colStart;
        this.rowIndex = rowIndex;
        this.values = values;
    }

    /** Number of slots. */
    public int nnz() {
        return colStart[m];
    }

    /**
     * Builds a pattern from {@code count} triplets. Duplicated positions are summed in triplet
     * order, starting from 0 ({@code 0 + v1 + v2 ...}).
     */
    public static CscPattern fromTriplets(int m, int[] rows, int[] cols, double[] vals, int count) {
        int[] cnt = new int[m + 1];
        for (int k = 0; k < count; k++) {
            cnt[cols[k] + 1]++;
        }
        for (int c = 0; c < m; c++) {
            cnt[c + 1] += cnt[c];
        }
        // bucket the triplets by column, keeping their order
        int[] next = new int[m];
        System.arraycopy(cnt, 0, next, 0, m);
        int[] order = new int[count];
        for (int k = 0; k < count; k++) {
            order[next[cols[k]]++] = k;
        }
        // per column: sort rows (insertion sort on the column bucket; columns are short), merge
        int[] colStart = new int[m + 1];
        int[] rowIndex = new int[count];
        double[] values = new double[count];
        int[] colRows = new int[16];
        int[] colTrip = new int[16];
        int nz = 0;
        for (int c = 0; c < m; c++) {
            colStart[c] = nz;
            int len = cnt[c + 1] - cnt[c];
            if (colRows.length < len) {
                colRows = new int[len * 2];
                colTrip = new int[len * 2];
            }
            for (int p = 0; p < len; p++) {
                int k = order[cnt[c] + p];
                // stable insertion by row
                int q = p;
                while (q > 0 && colRows[q - 1] > rows[k]) {
                    colRows[q] = colRows[q - 1];
                    colTrip[q] = colTrip[q - 1];
                    q--;
                }
                colRows[q] = rows[k];
                colTrip[q] = k;
            }
            for (int p = 0; p < len; p++) {
                int r = colRows[p];
                double v = vals == null ? 0 : vals[colTrip[p]];
                if (nz > colStart[c] && rowIndex[nz - 1] == r) {
                    values[nz - 1] += v;
                } else {
                    rowIndex[nz] = r;
                    values[nz] = 0;
                    values[nz] += v;
                    nz++;
                }
            }
        }
        colStart[m] = nz;
        if (nz < count) {
            int[] ri = new int[nz];
            double[] va = new double[nz];
            System.arraycopy(rowIndex, 0, ri, 0, nz);
            System.arraycopy(values, 0, va, 0, nz);
            rowIndex = ri;
            values = va;
        }
        return new CscPattern(m, colStart, rowIndex, values);
    }

    /** Wraps already compressed arrays (rows ascending and unique within each column). */
    public static CscPattern of(int m, int[] colStart, int[] rowIndex, double[] values) {
        return new CscPattern(m, colStart, rowIndex, values);
    }

    /** Slot of {@code (row, col)}, or -1 when the position is not in the pattern. O(log d). */
    public int slotOf(int row, int col) {
        int lo = colStart[col];
        int hi = colStart[col + 1] - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int r = rowIndex[mid];
            if (r < row) {
                lo = mid + 1;
            } else if (r > row) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    /** Same pattern, values copied. */
    public CscPattern copy() {
        double[] v = new double[values.length];
        System.arraycopy(values, 0, v, 0, values.length);
        return new CscPattern(m, colStart, rowIndex, v);
    }

    /** {@code y := A x} (dense vectors of length m). */
    public void multiply(double[] x, double[] y) {
        for (int i = 0; i < m; i++) {
            y[i] = 0;
        }
        for (int c = 0; c < m; c++) {
            double xc = x[c];
            for (int p = colStart[c]; p < colStart[c + 1]; p++) {
                y[rowIndex[p]] += values[p] * xc;
            }
        }
    }
}
