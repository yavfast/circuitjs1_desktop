package com.lushprojects.circuitjs1.client.solver;

import com.lushprojects.circuitjs1.client.CircuitMath;

/**
 * [C_SLV_04_02] [SP_SLV_02] The MNA system of one document: the system store the engine's stamping
 * primitives write into, the row reduction over it (today's {@code simplifyMatrix}, same decisions
 * in the same order), the reduced system with its snapshot, and the solve path.
 *
 * <p>Lifecycle per stamp ([SP_SLV_04_01]): {@link #beginStamp} (ASSEMBLING) → stamps through
 * {@link #addEntry} / {@link #addRhs} → {@link #reduce} (REDUCED) → {@link #selectPath} →
 * {@link #factor} / {@link #solve} per iteration, with {@link #restoreSnapshot} before each
 * iteration's stamps. {@link #drop} releases everything (stop, reset).
 *
 * <p>The dense path ([SP_SLV_01_06]) computes exactly as the engine did before this class: the
 * reduced values go to an m × m table, {@code CircuitMath.lu_factor} / {@code lu_solve} factor and
 * solve, and the Newton restore copies the snapshot tables.
 */
public final class LinearSystem {

    private static final int PHASE_NONE = 0;
    private static final int PHASE_ASSEMBLING = 1;
    private static final int PHASE_REDUCED = 2;

    /** Systems up to this size keep a copy of the assembled values for the singular dump. */
    private static final int DUMP_MAX_SIZE = 12;

    private int phase = PHASE_NONE;

    // ---- [SP_SLV_01_03] system store of the current stamp
    private int n;
    private RowInfo[] rowInfo;
    private double[] rhs;
    private int slotCount;
    private int[] slotRow = new int[64];
    private int[] slotCol = new int[64];
    private double[] slotVal = new double[64];
    /** Open-addressing table: slot index + 1 per bucket, 0 = empty. */
    private int[] table = new int[128];

    // ---- [SP_SLV_01_04] reduced system of the current stamp
    private int m;
    private CscPattern reduced;
    private double[] reducedRhs;

    // ---- [SP_SLV_01_06] dense workspace
    private double[][] matrix;
    private double[][] origMatrix;
    private double[] rightSide;
    private double[] origRightSide;
    private int[] permute;
    /** Copy of the assembled values taken before each factorization when m ≤ 12. */
    private double[][] preFactor;
    /** The table holds factors (not assembled values) since the last restore. */
    private boolean factored;

    /** Full system size n of the current stamp. */
    public int fullSize() {
        return n;
    }

    /** Reduced size m of the current stamp (0 before reduction). */
    public int size() {
        return m;
    }

    /** Per-unknown records of the current stamp (null when there is no stamp). */
    public RowInfo[] rowInfo() {
        return rowInfo;
    }

    /**
     * True when a reduced system exists — the engine's "a circuit is stamped" sentinel
     * ([SP_SLV_02_10], formerly {@code circuitMatrix != null}).
     */
    public boolean hasSystem() {
        return phase == PHASE_REDUCED;
    }

    /** Releases every solver structure ({@code stop}, {@code resetSolverState}). */
    public void drop() {
        phase = PHASE_NONE;
        n = 0;
        m = 0;
        slotCount = 0;
        slotRow = new int[64];
        slotCol = new int[64];
        slotVal = new double[64];
        table = new int[128];
        rowInfo = null;
        rhs = null;
        reduced = null;
        reducedRhs = null;
        releaseDense();
    }

    private void releaseDense() {
        matrix = null;
        origMatrix = null;
        rightSide = null;
        origRightSide = null;
        permute = null;
        preFactor = null;
        factored = false;
    }

    // =====================================================================================
    // [SP_SLV_02_01] beginStamp

    /**
     * Starts the system of a new stamp of {@code n} unknowns: an empty store, fresh row records.
     *
     * @param analysis the engine's analysis counter (the carried state and counters are per
     *                 engine analysis)
     */
    public void beginStamp(int n, int analysis) {
        this.n = n;
        rowInfo = new RowInfo[n];
        for (int i = 0; i < n; i++) {
            rowInfo[i] = new RowInfo();
        }
        rhs = new double[n];
        // keep the slot arrays; size the table for the previous stamp's slot count
        int want = 128;
        while (want < 2 * Math.max(slotCount, 32)) {
            want <<= 1;
        }
        if (table.length != want) {
            table = new int[want];
        } else {
            for (int k = 0; k < want; k++) {
                table[k] = 0;
            }
        }
        slotCount = 0;
        m = 0;
        reduced = null;
        reducedRhs = null;
        releaseDense();
        phase = PHASE_ASSEMBLING;
    }

    // =====================================================================================
    // [SP_SLV_02_02] assembly

    private static int hash(int r, int c) {
        // both products stay below 2^53 for any realistic size, so GWT's double arithmetic gives
        // the same bits as Java's int arithmetic
        int h = r * 0x45d9f3b + c * 0x119de1f;
        return h ^ (h >>> 15);
    }

    private int findOrCreateSlot(int r, int c) {
        int mask = table.length - 1;
        int b = hash(r, c) & mask;
        for (;;) {
            int t = table[b];
            if (t == 0) {
                break;
            }
            int s = t - 1;
            if (slotRow[s] == r && slotCol[s] == c) {
                return s;
            }
            b = (b + 1) & mask;
        }
        int s = slotCount++;
        if (s == slotRow.length) {
            int cap = 2 * s;
            slotRow = copyOf(slotRow, cap);
            slotCol = copyOf(slotCol, cap);
            slotVal = copyOf(slotVal, cap);
        }
        slotRow[s] = r;
        slotCol[s] = c;
        slotVal[s] = 0;
        table[b] = s + 1;
        if (2 * slotCount > table.length) {
            rehash(2 * table.length);
        }
        return s;
    }

    private void rehash(int cap) {
        table = new int[cap];
        int mask = cap - 1;
        for (int s = 0; s < slotCount; s++) {
            int b = hash(slotRow[s], slotCol[s]) & mask;
            while (table[b] != 0) {
                b = (b + 1) & mask;
            }
            table[b] = s + 1;
        }
    }

    /**
     * Column {@code c}'s record when the system is reduced and the column's unknown became a
     * constant; null otherwise. A matrix stamp into such a column moves to the right-hand side
     * ({@code subtractRhs}), as today.
     */
    public RowInfo constColumn(int c) {
        if (phase != PHASE_REDUCED) {
            return null;
        }
        RowInfo ri = rowInfo[c];
        return ri.type == RowInfo.ROW_CONST ? ri : null;
    }

    /**
     * {@code A[r][c] += x} (full-system indices, ground excluded; {@code x} already sanitized).
     * While assembling the slot is created on first use (value {@code 0 + x}); after reduction
     * the position is mapped, and the column must not be a constant ({@link #constColumn}).
     * A stamp into a dropped row, or without a system, is ignored.
     */
    public void addEntry(int r, int c, double x) {
        if (phase == PHASE_ASSEMBLING) {
            // the slot first: creating it may grow (replace) slotVal
            int s = findOrCreateSlot(r, c);
            slotVal[s] += x;
            return;
        }
        if (phase != PHASE_REDUCED) {
            return;
        }
        int r2 = rowInfo[r].mapRow;
        if (r2 < 0) {
            return;
        }
        matrix[r2][rowInfo[c].mapCol] += x;
    }

    /** {@code B[r] += x} (full-system row). */
    public void addRhs(int r, double x) {
        if (phase == PHASE_ASSEMBLING) {
            rhs[r] += x;
            return;
        }
        if (phase != PHASE_REDUCED) {
            return;
        }
        int r2 = rowInfo[r].mapRow;
        if (r2 < 0) {
            return;
        }
        rightSide[r2] += x;
    }

    /** {@code B[r] -= x} after reduction (a stamp folded from a constant column). */
    public void subtractRhs(int r, double x) {
        if (phase != PHASE_REDUCED) {
            return;
        }
        int r2 = rowInfo[r].mapRow;
        if (r2 < 0) {
            return;
        }
        rightSide[r2] -= x;
    }

    /** Marks row {@code r}'s right-hand side as changing in {@code doStep} (rsChanges). */
    public void markRightSideChanges(int r) {
        if (rowInfo != null) {
            rowInfo[r].rsChanges = true;
        }
    }

    /** Marks row {@code r}'s matrix entries as changing in {@code doStep} (lsChanges). */
    public void markNonLinear(int r) {
        if (rowInfo != null) {
            rowInfo[r].lsChanges = true;
        }
    }

    // =====================================================================================
    // [SP_SLV_02_04] reduce

    /**
     * Today's {@code simplifyMatrix} over the store: rows with a single non-constant unknown turn
     * that unknown into a constant and are dropped, with today's restarts; then the maps and the
     * reduced system. The floating-point sums run in today's order (stamp order within a slot,
     * ascending column within a row), so for finite constants every value equals today's bit for
     * bit.
     *
     * @return false on today's "Matrix error" (a row with no non-constant entry); the system is
     *         then dropped and the engine warns or stops. Without a stamp in assembly (a stop
     *         dropped it) nothing happens and there is no system afterwards.
     */
    public boolean reduce() {
        if (phase != PHASE_ASSEMBLING) {
            return true;
        }
        final int n = this.n;
        final RowInfo[] info = rowInfo;
        final int sc = slotCount;
        // row-major order with ascending columns: counting sort by column, then stable by row
        int[] byCol = new int[sc];
        int[] colPtr = new int[n + 1];
        for (int s = 0; s < sc; s++) {
            colPtr[slotCol[s] + 1]++;
        }
        for (int c = 0; c < n; c++) {
            colPtr[c + 1] += colPtr[c];
        }
        int[] next = new int[n];
        System.arraycopy(colPtr, 0, next, 0, n);
        for (int s = 0; s < sc; s++) {
            byCol[next[slotCol[s]]++] = s;
        }
        int[] rowPtr = new int[n + 1];
        for (int s = 0; s < sc; s++) {
            rowPtr[slotRow[s] + 1]++;
        }
        for (int r = 0; r < n; r++) {
            rowPtr[r + 1] += rowPtr[r];
        }
        System.arraycopy(rowPtr, 0, next, 0, n);
        int[] byRow = new int[sc];
        for (int k = 0; k < sc; k++) {
            int s = byCol[k];
            byRow[next[slotRow[s]]++] = s;
        }
        // per column: slots in ascending row order (stable redistribution of byRow)
        System.arraycopy(colPtr, 0, next, 0, n);
        for (int k = 0; k < sc; k++) {
            int s = byRow[k];
            byCol[next[slotCol[s]]++] = s;
        }
        final double[] val = slotVal;

        // 1–3: the scan with restarts
        for (int i = 0; i < n; i++) {
            RowInfo ri = info[i];
            if (ri.lsChanges || ri.dropRow || ri.rsChanges) {
                continue;
            }
            int pivotCol = -1;
            double pivotValue = 0;
            double adjustment = 0;
            boolean second = false;
            for (int k = rowPtr[i]; k < rowPtr[i + 1]; k++) {
                int s = byRow[k];
                int c = slotCol[s];
                double v = val[s];
                RowInfo ci = info[c];
                if (ci.type == RowInfo.ROW_CONST) {
                    adjustment -= ci.value * v;
                    continue;
                }
                if (v == 0) {
                    continue;
                }
                if (pivotCol == -1) {
                    pivotCol = c;
                    pivotValue = v;
                    continue;
                }
                second = true;
                break;
            }
            if (second) {
                continue;
            }
            if (pivotCol == -1) {
                drop();
                return false;
            }
            RowInfo pivotInfo = info[pivotCol];
            if (pivotInfo.type != RowInfo.ROW_NORMAL) {
                // unreachable: a constant column is never a candidate (today logs "type already")
                continue;
            }
            pivotInfo.type = RowInfo.ROW_CONST;
            pivotInfo.value = (rhs[i] + adjustment) / pivotValue;
            ri.dropRow = true;
            // restart from the lowest earlier row with a non-zero value in the pivot column
            int j = i;
            for (int k = colPtr[pivotCol]; k < colPtr[pivotCol + 1]; k++) {
                int s = byCol[k];
                int r = slotRow[s];
                if (r >= i) {
                    break;
                }
                if (val[s] != 0) {
                    j = r;
                    break;
                }
            }
            i = j - 1;
        }

        // 4: numbering
        int mm = 0;
        for (int i = 0; i < n; i++) {
            RowInfo ri = info[i];
            if (ri.type == RowInfo.ROW_NORMAL) {
                ri.mapCol = mm++;
            } else {
                ri.mapCol = -1;
            }
        }
        int row = 0;
        for (int i = 0; i < n; i++) {
            RowInfo ri = info[i];
            ri.mapRow = ri.dropRow ? -1 : row++;
        }
        m = mm;

        // 5: the reduced system (kept rows ascending, slots of a row in ascending column)
        double[] rrhs = new double[mm];
        int[] tr = new int[sc];
        int[] tc = new int[sc];
        double[] tv = new double[sc];
        int tn = 0;
        for (int i = 0; i < n; i++) {
            RowInfo ri = info[i];
            if (ri.dropRow) {
                continue;
            }
            int r2 = ri.mapRow;
            rrhs[r2] = rhs[i];
            for (int k = rowPtr[i]; k < rowPtr[i + 1]; k++) {
                int s = byRow[k];
                RowInfo ci = info[slotCol[s]];
                if (ci.type == RowInfo.ROW_CONST) {
                    rrhs[r2] -= ci.value * val[s];
                } else {
                    tr[tn] = r2;
                    tc[tn] = ci.mapCol;
                    tv[tn] = val[s];
                    tn++;
                }
            }
        }
        reduced = CscPattern.fromTriplets(mm, tr, tc, tv, tn);
        reducedRhs = rrhs;
        phase = PHASE_REDUCED;
        return true;
    }

    // =====================================================================================
    // [SP_SLV_02_05] selectPath

    /** Chooses the solve path of this stamp and builds its workspace (P3: dense only). */
    public void selectPath() {
        if (phase != PHASE_REDUCED) {
            return;
        }
        buildDense();
    }

    private void buildDense() {
        final int mm = m;
        matrix = new double[mm][mm];
        origMatrix = new double[mm][mm];
        rightSide = new double[mm];
        origRightSide = new double[mm];
        permute = new int[mm];
        preFactor = mm <= DUMP_MAX_SIZE ? new double[mm][mm] : null;
        factored = false;
        CscPattern a = reduced;
        for (int c = 0; c < mm; c++) {
            for (int p = a.colStart[c]; p < a.colStart[c + 1]; p++) {
                matrix[a.rowIndex[p]][c] += a.values[p];
            }
        }
        System.arraycopy(reducedRhs, 0, rightSide, 0, mm);
        System.arraycopy(rightSide, 0, origRightSide, 0, mm);
        for (int i = 0; i < mm; i++) {
            System.arraycopy(matrix[i], 0, origMatrix[i], 0, mm);
        }
    }

    // =====================================================================================
    // [SP_SLV_01_05] snapshot

    /**
     * Restores the constant part before an iteration's stamps: the right-hand side always, the
     * matrix only for a nonlinear circuit (a linear one keeps its factors).
     */
    public void restoreSnapshot(boolean nonLinear) {
        if (phase != PHASE_REDUCED) {
            return;
        }
        System.arraycopy(origRightSide, 0, rightSide, 0, m);
        if (nonLinear) {
            for (int i = 0; i < m; i++) {
                System.arraycopy(origMatrix[i], 0, matrix[i], 0, m);
            }
            factored = false;
        }
    }

    // =====================================================================================
    // [SP_SLV_02_07] factor / [SP_SLV_02_08] solve

    /**
     * Factors the current values.
     *
     * @return null on success; else the singularity report (the {@code unknown} is mapped, the
     *         engine names the {@code variable})
     */
    public SingularityReport factor() {
        if (phase != PHASE_REDUCED) {
            return new SingularityReport(-1, -1, 0, false);
        }
        final int mm = m;
        if (preFactor != null) {
            for (int i = 0; i < mm; i++) {
                System.arraycopy(matrix[i], 0, preFactor[i], 0, mm);
            }
        }
        factored = true;
        if (CircuitMath.lu_factor(matrix, mm, permute)) {
            return null;
        }
        SingularityReport rep = new SingularityReport(CircuitMath.getLastLuFailColumn(), CircuitMath.getLastLuFailRow(),
                CircuitMath.getLastLuFailPivotAbs(), false);
        rep.unknown = unknownOfColumn(rep.column);
        return rep;
    }

    /**
     * Solves with the last successful factorization.
     *
     * @return the solution by reduced column (valid until the next solver call)
     */
    public double[] solve() {
        CircuitMath.lu_solve(matrix, m, permute, rightSide);
        return rightSide;
    }

    /** Full-system index of the unknown of reduced column {@code col} (through mapCol), or -1. */
    public int unknownOfColumn(int col) {
        if (col < 0 || rowInfo == null) {
            return -1;
        }
        for (int j = 0; j < n; j++) {
            RowInfo ri = rowInfo[j];
            if (ri.type == RowInfo.ROW_NORMAL && ri.mapCol == col) {
                return j;
            }
        }
        return -1;
    }

    // =====================================================================================
    // [SP_SLV_02_03] dumpSystem

    /**
     * The assembled value at reduced position {@code (r, c)} of the current iteration — never a
     * partially factored one: before a factorization the table itself, after one the copy taken
     * before it (m ≤ 12), else the snapshot (a linear circuit's matrix is its snapshot).
     */
    public double assembledValue(int r, int c) {
        if (!factored) {
            return matrix[r][c];
        }
        return preFactor != null ? preFactor[r][c] : origMatrix[r][c];
    }

    /** Dense path: the current right-hand side at reduced row {@code r} (tests). */
    double rightSideValue(int r) {
        return rightSide[r];
    }

    // =====================================================================================

    private static int[] copyOf(int[] a, int cap) {
        int[] b = new int[cap];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }

    private static double[] copyOf(double[] a, int cap) {
        double[] b = new double[cap];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }
}
