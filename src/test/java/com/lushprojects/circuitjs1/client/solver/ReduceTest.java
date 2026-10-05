package com.lushprojects.circuitjs1.client.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * [SP_SLV_05_01] "reduce — Bit identity": {@link LinearSystem#reduce} against the engine's
 * {@code simplifyMatrix} as it stood before PL_SLV (copied verbatim below as the oracle, with the
 * engine's fields turned into parameters, its warn/stop into the return value, the unreachable
 * "type already" console line removed and a restart counter added), on generated
 * stamp sequences with duplicated positions, zero stamps, {@code lsChanges}/{@code rsChanges} rows,
 * cascading constants (restarts) and matrix errors. Values, right sides, constants and maps must
 * be equal bit for bit (the sign of zero included).
 */
class ReduceTest {

    /** The oracle's result. */
    static final class Oracle {
        boolean ok;
        double[][] matrix;
        double[] rightSide;
        int size;
        boolean needsMap;
    }

    /** Restarts to an earlier row in the last oracle run. */
    static int restarts;

    // ---- verbatim copy of CircuitSimulator.simplifyMatrix (commit e531346), fields as parameters
    static Oracle simplifyMatrix(int matrixSize, double[][] circuitMatrixIn, double[] circuitRightSideIn, RowInfo[] circuitRowInfo) {
        Oracle o = new Oracle();
        restarts = 0;
        double[][] circuitMatrix = circuitMatrixIn;
        double[] circuitRightSide = circuitRightSideIn;
        double[][] origMatrix = new double[matrixSize][matrixSize];
        double[] origRightSide = new double[matrixSize];

        int i, j;
        // Iterate through each row of the matrix to find opportunities for
        // simplification.
        for (i = 0; i < matrixSize; i++) {
            int pivotColumnIndex = -1; // Index of the first non-zero, non-constant element in the row.
            double pivotValue = 0; // Value of the first non-zero, non-constant element.
            RowInfo rowInfo = circuitRowInfo[i];
            // Skip rows that are already simplified, marked for dropping, or have changing
            // right-hand sides.
            if (rowInfo.lsChanges || rowInfo.dropRow || rowInfo.rsChanges) {
                continue;
            }
            double rightSideAdjustment = 0; // Accumulator for adjustments to the right-hand side of the equation.

            // Scan the row to see if it can be simplified.
            // A row can be simplified if it contains exactly one non-zero element
            // corresponding to a non-constant variable.
            for (j = 0; j < matrixSize; j++) {
                double elementValue = circuitMatrix[i][j];
                // If the element corresponds to a known constant, adjust the right-hand side.
                if (circuitRowInfo[j].type == RowInfo.ROW_CONST) {
                    rightSideAdjustment -= circuitRowInfo[j].value * elementValue;
                    continue;
                }
                if (elementValue == 0) {
                    continue;
                }
                // If this is the first non-zero element found, record its position and value.
                if (pivotColumnIndex == -1) {
                    pivotColumnIndex = j;
                    pivotValue = elementValue;
                    continue;
                }
                // If more than one non-zero element is found, this row cannot be simplified at
                // this time.
                break;
            }

            // If the loop completed, it means we found a row that can be simplified (j ==
            // matrixSize).
            if (j == matrixSize) {
                if (pivotColumnIndex == -1) {
                    // This should not happen in a valid circuit. It might indicate a singular
                    // matrix.
                    o.ok = false;
                    return o;
                }
                RowInfo pivotRowInfo = circuitRowInfo[pivotColumnIndex];
                // We've found a row with a single unknown. We can solve for this unknown.
                if (pivotRowInfo.type != RowInfo.ROW_NORMAL) {
                    // This case should ideally not be reached if logic is correct.
                    continue;
                }
                // Mark the variable as a constant and calculate its value.
                pivotRowInfo.type = RowInfo.ROW_CONST;
                pivotRowInfo.value = (circuitRightSide[i] + rightSideAdjustment) / pivotValue;
                circuitRowInfo[i].dropRow = true; // Mark the current row to be removed from the matrix.

                // Now that we have a new constant, we need to re-check previous rows.
                // Find the first row that referenced the element we just turned into a
                // constant.
                for (j = 0; j != i; j++) {
                    if (circuitMatrix[j][pivotColumnIndex] != 0) {
                        break;
                    }
                }
                // Restart the main loop from just before that row to apply the new
                // simplification.
                if (j < i) {
                    restarts++;
                }
                i = j - 1;
            }
        }

        // Create the new, smaller matrix by removing the simplified rows and columns.
        int newSizeCounter = 0; // Counter for the size of the new matrix.
        for (i = 0; i < matrixSize; i++) {
            RowInfo rowInfo = circuitRowInfo[i];
            if (rowInfo.type == RowInfo.ROW_NORMAL) {
                rowInfo.mapCol = newSizeCounter++; // Map old column index to new column index.
            } else {
                rowInfo.mapCol = -1; // Mark constant columns.
            }
        }

        int newMatrixSize = newSizeCounter;
        if (newMatrixSize == matrixSize) {
            // No simplification was possible, no need to rebuild the matrix.
            // Still need to snapshot the base matrix/right side for nonlinear sub-iterations.
            System.arraycopy(circuitRightSide, 0, origRightSide, 0, matrixSize);
            for (i = 0; i < matrixSize; i++) {
                System.arraycopy(circuitMatrix[i], 0, origMatrix[i], 0, matrixSize);
            }
            o.ok = true;
            o.matrix = circuitMatrix;
            o.rightSide = circuitRightSide;
            o.size = matrixSize;
            return o;
        }

        double[][] newCircuitMatrix = new double[newMatrixSize][newMatrixSize];
        double[] newRightSide = new double[newMatrixSize];
        int newRowIndex = 0; // Row index for the new matrix.
        for (i = 0; i < matrixSize; i++) {
            RowInfo currentRowInfo = circuitRowInfo[i];
            if (currentRowInfo.dropRow) {
                currentRowInfo.mapRow = -1;
                continue;
            }
            newRightSide[newRowIndex] = circuitRightSide[i];
            currentRowInfo.mapRow = newRowIndex;
            for (j = 0; j != matrixSize; j++) {
                RowInfo columnRowInfo = circuitRowInfo[j];
                if (columnRowInfo.type == RowInfo.ROW_CONST) {
                    // Adjust the right-hand side with the value of the constant.
                    newRightSide[newRowIndex] -= columnRowInfo.value * circuitMatrix[i][j];
                } else {
                    // Copy the matrix element to its new position.
                    newCircuitMatrix[newRowIndex][columnRowInfo.mapCol] += circuitMatrix[i][j];
                }
            }
            newRowIndex++;
        }

        o.ok = true;
        o.matrix = newCircuitMatrix;
        o.rightSide = newRightSide;
        o.size = newMatrixSize;
        o.needsMap = true;
        return o;
    }
    // ---- end of the verbatim copy

    private static void assertBits(double expected, double actual, String what) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual),
                what + ": expected " + expected + " got " + actual);
    }

    /** One generated stamp sequence, applied to the oracle's dense tables and to the store. */
    private static int runCase(int seed) {
        TestMatrices.Rng r = new TestMatrices.Rng(seed);
        int n = 1 + (int) Math.floor(r.next() * 40);
        double[][] dense = new double[n][n];
        double[] rhsDense = new double[n];
        RowInfo[] infoOracle = new RowInfo[n];
        for (int i = 0; i < n; i++) {
            infoOracle[i] = new RowInfo();
        }
        LinearSystem ls = new LinearSystem();
        ls.beginStamp(n, 1);
        Stamper st = (i, j, x) -> {
            dense[i][j] += x;
            ls.addEntry(i, j, x);
        };
        Stamper rh = (i, j, x) -> {
            rhsDense[i] += x;
            ls.addRhs(i, x);
        };
        if (seed % 2 == 0) {
            mnaStamps(r, n, st, rh);
        } else {
            randomStamps(r, n, dense, st, rh);
        }
        double marked = seed % 2 == 0 ? 0.05 : 0.2;
        for (int i = 0; i < n; i++) {
            double u = r.next() * 0.2 / marked;
            if (u < 0.1) {
                infoOracle[i].lsChanges = true;
                ls.markNonLinear(i);
            } else if (u < 0.2) {
                infoOracle[i].rsChanges = true;
                ls.markRightSideChanges(i);
            }
        }
        Oracle o = simplifyMatrix(n, dense, rhsDense, infoOracle);
        boolean ok = ls.reduce();
        assertEquals(o.ok, ok, "seed " + seed + " matrix error");
        if (!ok) {
            return -1;
        }
        ls.selectPath();
        RowInfo[] info = ls.rowInfo();
        assertEquals(o.size, ls.size(), "seed " + seed + " m");
        for (int i = 0; i < n; i++) {
            assertEquals(infoOracle[i].type, info[i].type, "seed " + seed + " type " + i);
            assertBits(infoOracle[i].value, info[i].value, "seed " + seed + " value " + i);
            assertEquals(infoOracle[i].mapCol, info[i].mapCol, "seed " + seed + " mapCol " + i);
            assertEquals(infoOracle[i].dropRow, info[i].dropRow, "seed " + seed + " dropRow " + i);
            if (o.needsMap) {
                assertEquals(infoOracle[i].mapRow, info[i].mapRow, "seed " + seed + " mapRow " + i);
            } else {
                assertEquals(i, info[i].mapRow, "seed " + seed + " identity mapRow " + i);
            }
        }
        for (int i = 0; i < o.size; i++) {
            assertBits(o.rightSide[i], ls.rightSideValue(i), "seed " + seed + " rhs " + i);
            for (int j = 0; j < o.size; j++) {
                assertBits(o.matrix[i][j], ls.assembledValue(i, j), "seed " + seed + " A " + i + "," + j);
            }
        }
        return n - o.size;
    }

    interface Stamper {
        void add(int i, int j, double x);
    }

    /** Arbitrary sparse stamps: repeated positions, zeros, exact cancellations. */
    private static void randomStamps(TestMatrices.Rng r, int n, double[][] dense, Stamper st, Stamper rh) {
        int stamps = (int) Math.floor(r.next() * n * 4);
        double density = 0.05 + r.next() * 0.3;
        for (int k = 0; k < stamps; k++) {
            int i = (int) Math.floor(r.next() * n);
            int j = r.next() < 0.3 ? i : (int) Math.floor(r.next() * n);
            if (r.next() > density && j != i && r.next() < 0.5) {
                continue;
            }
            double x;
            double u = r.next();
            if (u < 0.08) {
                x = 0;
            } else if (u < 0.12) {
                x = -dense[i][j]; // cancels to an exact zero
            } else if (u < 0.5) {
                x = Math.floor(r.next() * 9) - 4; // small integers: exact cancellations
            } else {
                x = (r.next() * 2 - 1) * Math.exp(r.next() * 10 - 5);
            }
            st.add(i, j, x);
            if (r.next() < 0.3) {
                rh.add(i, 0, r.next() < 0.2 ? 0 : (r.next() * 2 - 1) * 10);
            }
        }
    }

    /**
     * MNA-shaped stamps as the engine's primitives make them: conductances between nodes or to
     * ground, voltage sources (grounded ones reduce, and their node's constant cascades through
     * the restarts), current sources, and a zero stamp now and then.
     */
    private static void mnaStamps(TestMatrices.Rng r, int n, Stamper st, Stamper rh) {
        int vs = Math.min(n - 1, (int) Math.floor(r.next() * (n / 3 + 1)));
        int nodes = n - vs;
        // a tree first (every node tied to an earlier node or to ground), then extra parts
        int parts = nodes + (int) Math.floor(r.next() * nodes);
        for (int k = 0; k < parts; k++) {
            int a = k < nodes ? k : (int) Math.floor(r.next() * (nodes + 1)) - 1; // -1 = ground
            int b = k < nodes ? (int) Math.floor(r.next() * (k + 1)) - 1 : (int) Math.floor(r.next() * (nodes + 1)) - 1;
            double g = r.next() < 0.3 ? 1 : Math.exp(r.next() * 8 - 4);
            if (a >= 0) {
                st.add(a, a, g);
            }
            if (b >= 0 && b != a) {
                st.add(b, b, g);
            }
            if (a >= 0 && b >= 0 && a != b) {
                st.add(a, b, -g);
                st.add(b, a, -g);
            }
            if (r.next() < 0.15 && a >= 0) {
                rh.add(a, 0, (r.next() * 2 - 1) * 3);
            }
            if (r.next() < 0.05 && a >= 0) {
                st.add(a, a, 0);
            }
        }
        for (int v = 0; v < vs; v++) {
            int row = nodes + v;
            int a = r.next() < 0.6 ? -1 : (int) Math.floor(r.next() * nodes);
            int b = (int) Math.floor(r.next() * nodes);
            if (a >= 0) {
                st.add(row, a, -1);
            }
            st.add(row, b, 1);
            if (a >= 0) {
                st.add(a, row, 1);
            }
            st.add(b, row, -1);
            rh.add(row, 0, Math.floor(r.next() * 10) - 2);
        }
    }

    @Test
    void bitIdentityWithTodaysSimplifyMatrix() {
        int reducedCases = 0;
        int errors = 0;
        int withRestarts = 0;
        for (int seed = 1; seed <= 500; seed++) {
            int red = runCase(seed);
            if (restarts > 0) {
                withRestarts++;
            }
            if (red > 0) {
                reducedCases++;
            } else if (red < 0) {
                errors++;
            }
        }
        // the generators must exercise reductions (with restarts) and matrix errors
        assertTrue(reducedCases > 150, "only " + reducedCases + " cases reduced");
        assertTrue(errors > 10, "only " + errors + " matrix errors");
        assertTrue(withRestarts > 20, "only " + withRestarts + " cases restarted");
    }

    @Test
    void stampsAfterReductionFollowTheMaps() {
        // a reduced system: row 0 has the single unknown 0 (becomes a constant); stamps after
        // reduction into a constant column are the caller's (constColumn), others are mapped
        LinearSystem ls = new LinearSystem();
        ls.beginStamp(3, 1);
        ls.addEntry(0, 0, 2);
        ls.addRhs(0, 4);
        ls.addEntry(1, 1, 1);
        ls.addEntry(1, 2, 1);
        ls.addEntry(2, 1, 1);
        ls.addEntry(2, 2, -1);
        ls.addEntry(1, 0, 1);
        assertTrue(ls.reduce());
        ls.selectPath();
        assertEquals(2, ls.size());
        assertEquals(RowInfo.ROW_CONST, ls.rowInfo()[0].type);
        assertEquals(2.0, ls.rowInfo()[0].value);
        // the constant moved to the right side of row 1: 0 - 1 * 2
        assertEquals(-2.0, ls.rightSideValue(0));
        assertTrue(ls.constColumn(0) != null);
        ls.addEntry(2, 2, 0.5);
        assertEquals(-0.5, ls.assembledValue(1, 1));
        // dropped row 0: ignored
        ls.addEntry(0, 1, 7);
        ls.addRhs(0, 7);
        assertEquals(-2.0, ls.rightSideValue(0));
    }

    @Test
    void emptyReducedSystem() {
        LinearSystem ls = new LinearSystem();
        ls.beginStamp(2, 1);
        ls.addEntry(0, 0, 1);
        ls.addRhs(0, 3);
        ls.addEntry(1, 0, 1);
        ls.addEntry(1, 1, 2);
        assertTrue(ls.reduce());
        ls.selectPath();
        assertEquals(0, ls.size());
        assertEquals(null, ls.factor());
        assertEquals(0, ls.solve().length);
        assertEquals(3.0, ls.rowInfo()[0].value);
        assertEquals(-1.5, ls.rowInfo()[1].value);
    }

    @Test
    void matrixErrorDropsTheSystem() {
        LinearSystem ls = new LinearSystem();
        ls.beginStamp(2, 1);
        ls.addEntry(0, 0, 1);
        ls.addEntry(1, 1, 0); // a row with only a zero entry
        assertTrue(!ls.reduce());
        assertTrue(!ls.hasSystem());
        // stamps without a system are ignored
        ls.addEntry(0, 0, 1);
        ls.addRhs(0, 1);
    }

    @Test
    void oneUnknown() {
        LinearSystem ls = new LinearSystem();
        ls.beginStamp(1, 1);
        ls.addEntry(0, 0, 4);
        ls.markNonLinear(0);
        assertTrue(ls.reduce());
        ls.selectPath();
        assertEquals(1, ls.size());
        ls.restoreSnapshot(true);
        ls.addRhs(0, 2);
        assertEquals(null, ls.factor());
        assertEquals(0.5, ls.solve()[0]);
    }
}
