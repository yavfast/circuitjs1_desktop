package com.lushprojects.circuitjs1.client.solver;

/**
 * [SP_SLV_01_08] [SP_SLV_02_07] [SP_SLV_02_08] Sparse LU of one reduced system: left-looking
 * Gilbert–Peierls factorization (structural reach by depth-first search, as CSparse {@code cs_lu}),
 * threshold partial pivoting with the paired row preferred, refactorization on the stored reach and
 * pivot sequence with a per-step pivot check, and the solve. The factors live apart from the
 * system's values: a failed factorization leaves the system intact.
 *
 * <p>L is unit lower triangular; its column {@code k} stores the pivot row of step {@code k} first
 * (value 1), then the other structurally reached rows that were not yet pivotal, as row indices of
 * the reduced system. U's column {@code k} stores the earlier steps it depends on, in the
 * topological order of the reach, then the pivot (step {@code k}) last. Entries reached by the
 * structure are stored even when their value is 0, so a refactorization never loses a position.
 */
public final class SparseLu {

    /** Absolute singularity threshold, the dense kernel's ({@code CircuitMath.lu_factor}). */
    public static final double SINGULAR_PIVOT = 1e-14;
    /** Threshold of the partial pivoting and of the refactorization's pivot check. */
    public static final double PIVOT_TOLERANCE = 1e-3;

    private int m = -1;
    private int[] lp;
    private int[] li;
    private double[] lx;
    private int[] up;
    private int[] ui;
    private double[] ux;
    /** Row chosen as pivot at each step. */
    private int[] pivotRow;
    /** Step at which a row became pivotal, -1 while not pivotal (factorization work). */
    private int[] pinv;
    /** Reduced column eliminated at each step. */
    private int[] stepCol;
    /** {@code patternVersion} of the last full factorization; -1 when there is none. */
    private int fullBuiltFor = -1;
    private boolean valid;
    private int lnz;
    private int unz;

    // work arrays; x is all zero between calls (z is written before it is read)
    private double[] x;
    private int[] xi;
    private int[] stackP;
    private int[] marks;
    private double[] z;

    /** True when the factors describe the values of the last successful (re)factorization. */
    public boolean isValid() {
        return valid;
    }

    /** {@code patternVersion} of the last full factorization, -1 when none. */
    public int fullBuiltFor() {
        return fullBuiltFor;
    }

    /** nnz(L) + nnz(U) of the last full factorization (unit diagonal of L included). */
    public int factorNonZeros() {
        return fullBuiltFor < 0 ? 0 : lnz + unz;
    }

    /** Marks the factors as not describing the current values (they stay for refactorization). */
    public void invalidate() {
        valid = false;
    }

    /** Drops the factors: the next factorization is a full one. */
    public void reset() {
        valid = false;
        fullBuiltFor = -1;
    }

    private void ensureWork(int n) {
        if (m == n && x != null) {
            return;
        }
        m = n;
        x = new double[n];
        xi = new int[2 * n];
        stackP = new int[n];
        marks = new int[n];
        for (int i = 0; i < n; i++) {
            marks[i] = -1;
        }
        z = new double[n];
        pivotRow = new int[n];
        pinv = new int[n];
        stepCol = new int[n];
        lp = new int[n + 1];
        up = new int[n + 1];
        li = new int[0];
        lx = new double[0];
        ui = new int[0];
        ux = new double[0];
    }

    /**
     * Full factorization (step F) of {@code a} in the order of {@code s}.
     *
     * @return null on success, else the report of the failed step (the factors are then dropped)
     */
    public SingularityReport factor(CscPattern a, SymbolicAnalysis s, int patternVersion) {
        final int n = a.m;
        ensureWork(n);
        valid = false;
        fullBuiltFor = -1;
        if (s.structurallySingular) {
            return new SingularityReport(s.unmatchedCol, -1, 0, true);
        }
        final int[] ap = a.colStart;
        final int[] ai = a.rowIndex;
        final double[] ax = a.values;
        int cap = Math.max(4 * ap[n] + n, 16);
        if (li.length < cap) {
            li = new int[cap];
            lx = new double[cap];
            ui = new int[cap];
            ux = new double[cap];
        }
        final double[] x = this.x;
        final int[] xi = this.xi;
        final int[] stackP = this.stackP;
        final int[] marks = this.marks;
        final int[] pinv = this.pinv;
        for (int i = 0; i < n; i++) {
            pinv[i] = -1;
            marks[i] = -1;
        }
        int lnz = 0;
        int unz = 0;
        for (int k = 0; k < n; k++) {
            lp[k] = lnz;
            up[k] = unz;
            if (lnz + n > li.length) {
                int c = 2 * li.length + n;
                li = growInt(li, c);
                lx = growDouble(lx, c);
            }
            if (unz + n > ui.length) {
                int c = 2 * ui.length + n;
                ui = growInt(ui, c);
                ux = growDouble(ux, c);
            }
            final int[] li = this.li;
            final double[] lx = this.lx;
            final int col = s.colPerm[s.order[k]];
            final int paired = s.order[k];
            stepCol[k] = col;
            // structural reach of column col through the L columns built so far
            int top = n;
            for (int p = ap[col]; p < ap[col + 1]; p++) {
                int r0 = ai[p];
                if (marks[r0] == k) {
                    continue;
                }
                int head = 0;
                xi[0] = r0;
                while (head >= 0) {
                    int j = xi[head];
                    int jj = pinv[j];
                    if (marks[j] != k) {
                        marks[j] = k;
                        stackP[head] = jj < 0 ? 0 : lp[jj] + 1;
                    }
                    boolean doneNode = true;
                    if (jj >= 0) {
                        int pend = lp[jj + 1];
                        for (int pp = stackP[head]; pp < pend; pp++) {
                            int i = li[pp];
                            if (marks[i] == k) {
                                continue;
                            }
                            stackP[head] = pp + 1;
                            xi[++head] = i;
                            doneNode = false;
                            break;
                        }
                    }
                    if (doneNode) {
                        head--;
                        xi[--top + n] = j;
                    }
                }
            }
            // sparse triangular solve in topological order
            for (int p = ap[col]; p < ap[col + 1]; p++) {
                x[ai[p]] = ax[p];
            }
            for (int p = top; p < n; p++) {
                int j = xi[p + n];
                int jj = pinv[j];
                if (jj < 0) {
                    continue;
                }
                double xj = x[j];
                if (xj == 0) {
                    continue;
                }
                for (int pp = lp[jj] + 1; pp < lp[jj + 1]; pp++) {
                    x[li[pp]] -= lx[pp] * xj;
                }
            }
            // pivot search over the reached rows not yet pivotal; U gets the pivotal ones
            int ipiv = -1;
            double candMax = 0;
            boolean anyCand = false;
            for (int p = top; p < n; p++) {
                int i = xi[p + n];
                if (pinv[i] < 0) {
                    double t = Math.abs(x[i]);
                    if (!anyCand || t > candMax) {
                        candMax = t;
                        ipiv = i;
                        anyCand = true;
                    }
                } else {
                    ui[unz] = pinv[i];
                    ux[unz++] = x[i];
                }
            }
            if (!(candMax >= SINGULAR_PIVOT)) {
                for (int p = top; p < n; p++) {
                    x[xi[p + n]] = 0;
                }
                this.lnz = lnz;
                this.unz = unz;
                return new SingularityReport(col, anyCand ? ipiv : -1, anyCand ? candMax : 0, false);
            }
            if (pinv[paired] < 0 && marks[paired] == k && Math.abs(x[paired]) >= PIVOT_TOLERANCE * candMax) {
                ipiv = paired;
            }
            double pivot = x[ipiv];
            ui[unz] = k;
            ux[unz++] = pivot;
            pinv[ipiv] = k;
            pivotRow[k] = ipiv;
            li[lnz] = ipiv;
            lx[lnz++] = 1;
            for (int p = top; p < n; p++) {
                int i = xi[p + n];
                if (pinv[i] < 0) {
                    li[lnz] = i;
                    lx[lnz++] = x[i] / pivot;
                }
                x[i] = 0;
            }
        }
        lp[n] = lnz;
        up[n] = unz;
        this.lnz = lnz;
        this.unz = unz;
        fullBuiltFor = patternVersion;
        valid = true;
        return null;
    }

    /**
     * Refactorization (step R) of {@code a}, whose pattern must be the one of the last full
     * factorization: the stored reach and pivot rows, new values. Step {@code k} is accepted when
     * its pivot is at least {@link #SINGULAR_PIVOT} and at least {@link #PIVOT_TOLERANCE} times the
     * largest candidate of the step.
     *
     * @param patternVersion version of {@code a}'s pattern; a refactorization needs the pattern of
     *                       the last full factorization
     * @return true when every step was accepted; false asks for a full factorization
     */
    public boolean refactor(CscPattern a, int patternVersion) {
        final int n = a.m;
        if (fullBuiltFor < 0 || fullBuiltFor != patternVersion || n != m) {
            return false;
        }
        valid = false;
        final int[] ap = a.colStart;
        final int[] ai = a.rowIndex;
        final double[] ax = a.values;
        final double[] x = this.x;
        final int[] lp = this.lp;
        final int[] li = this.li;
        final double[] lx = this.lx;
        final int[] up = this.up;
        final int[] ui = this.ui;
        final double[] ux = this.ux;
        final int[] prow = this.pivotRow;
        for (int k = 0; k < n; k++) {
            int col = stepCol[k];
            for (int p = ap[col]; p < ap[col + 1]; p++) {
                x[ai[p]] = ax[p];
            }
            int ue = up[k + 1] - 1;
            for (int p = up[k]; p < ue; p++) {
                int jj = ui[p];
                double xj = x[prow[jj]];
                ux[p] = xj;
                if (xj == 0) {
                    continue;
                }
                for (int pp = lp[jj] + 1; pp < lp[jj + 1]; pp++) {
                    x[li[pp]] -= lx[pp] * xj;
                }
            }
            int r = prow[k];
            double pivot = x[r];
            double pabs = Math.abs(pivot);
            double candMax = pabs;
            for (int pp = lp[k] + 1; pp < lp[k + 1]; pp++) {
                double t = Math.abs(x[li[pp]]);
                if (t > candMax) {
                    candMax = t;
                }
            }
            boolean ok = pabs >= SINGULAR_PIVOT && pabs >= PIVOT_TOLERANCE * candMax;
            for (int p = up[k]; p < ue; p++) {
                x[prow[ui[p]]] = 0;
            }
            x[r] = 0;
            if (!ok) {
                for (int pp = lp[k] + 1; pp < lp[k + 1]; pp++) {
                    x[li[pp]] = 0;
                }
                return false;
            }
            ux[ue] = pivot;
            for (int pp = lp[k] + 1; pp < lp[k + 1]; pp++) {
                int i = li[pp];
                lx[pp] = x[i] / pivot;
                x[i] = 0;
            }
        }
        valid = true;
        return true;
    }

    /**
     * Solves with the last successful factorization. On input {@code b} is the right-hand side by
     * reduced row; on output it holds the solution by reduced column. Cost O(nnz(L) + nnz(U)).
     */
    public void solve(double[] b) {
        final int n = m;
        if (n <= 0) {
            return;
        }
        final double[] z = this.z;
        final int[] lp = this.lp;
        final int[] li = this.li;
        final double[] lx = this.lx;
        final int[] up = this.up;
        final int[] ui = this.ui;
        final double[] ux = this.ux;
        for (int k = 0; k < n; k++) {
            double zk = b[pivotRow[k]];
            z[k] = zk;
            if (zk != 0) {
                for (int p = lp[k] + 1; p < lp[k + 1]; p++) {
                    b[li[p]] -= lx[p] * zk;
                }
            }
        }
        for (int k = n - 1; k >= 0; k--) {
            int ue = up[k + 1] - 1;
            double zk = z[k] / ux[ue];
            z[k] = zk;
            if (zk != 0) {
                for (int p = up[k]; p < ue; p++) {
                    z[ui[p]] -= ux[p] * zk;
                }
            }
        }
        for (int k = 0; k < n; k++) {
            b[stepCol[k]] = z[k];
        }
    }

    private static int[] growInt(int[] a, int cap) {
        int[] b = new int[cap];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }

    private static double[] growDouble(double[] a, int cap) {
        double[] b = new double[cap];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }
}
