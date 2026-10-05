package com.lushprojects.circuitjs1.client.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * [SP_SLV_05_01] Kernel rows: linear accuracy, refactorization on value-only changes, growth
 * fallback, structural and numeric singularity, agreement with the dense kernel, m = 0 and m = 1.
 */
class SparseLuTest {

    private static final String[] CAPTURED = { "grid_22", "cladder_500", "dladder_500", "dgrid_14" };

    @Test
    void linearAccuracyOnCapturedMatrices() {
        for (String r : CAPTURED) {
            CscPattern a = TestMatrices.resource(r).csc();
            double[] b = TestMatrices.rhsFor(a);
            double[] x = TestMatrices.sparseSolve(a, b);
            double res = TestMatrices.relResidual(a, x, b);
            assertTrue(res <= 1e-12, r + " residual " + res);
        }
    }

    @Test
    void linearAccuracyGridAndLadder() {
        // SP_SLV_05_01 "Linear accuracy": the 45² grid and the 2000-node RC ladder, relative residual
        TestMatrices.Triplets[] cases = { TestMatrices.grid2d(45, 1), TestMatrices.ladder(2000, 2) };
        for (TestMatrices.Triplets t : cases) {
            CscPattern a = t.csc();
            double[] x = TestMatrices.sparseSolve(a, t.b);
            double res = TestMatrices.relResidual(a, x, t.b);
            assertTrue(res <= 1e-12, "n=" + t.n + " residual " + res);
        }
    }

    @Test
    void refactorNeedsTheFactoredPattern() {
        CscPattern a = TestMatrices.ladder(50, 2).csc();
        SparseLu lu = new SparseLu();
        assertNull(lu.factor(a, SymbolicAnalysis.build(a, 7), 7));
        assertFalse(lu.refactor(a, 8));
        assertTrue(lu.refactor(a, 7));
    }

    @Test
    void linearAccuracyOnGeneratedMatrices() {
        TestMatrices.Triplets[] cases = { TestMatrices.grid2d(45, 1), TestMatrices.ladder(2000, 2),
                TestMatrices.randNet(1000, 3), TestMatrices.randNet(2000, 4) };
        for (TestMatrices.Triplets t : cases) {
            CscPattern a = t.csc();
            double[] x = TestMatrices.sparseSolve(a, t.b);
            double be = TestMatrices.backwardError(a, x, t.b);
            assertTrue(be <= 1e-13, "n=" + t.n + " backward error " + be);
        }
    }

    @Test
    void refactorizationAcceptedOnValueOnlyChanges() {
        // diode-like Newton change: node diagonals change, the pattern stays
        TestMatrices.Triplets t = TestMatrices.ladder(1000, 2);
        CscPattern a = t.csc();
        SymbolicAnalysis s = SymbolicAnalysis.build(a, 3);
        SparseLu lu = new SparseLu();
        assertNull(lu.factor(a, s, 3));
        assertEquals(3, lu.fullBuiltFor());
        TestMatrices.Rng r = new TestMatrices.Rng(9);
        for (int iter = 0; iter < 20; iter++) {
            CscPattern a2 = a.copy();
            for (int c = 0; c < 1000; c++) {
                int p = a2.slotOf(c, c);
                if (r.next() < 0.3) {
                    a2.values[p] += Math.exp(Math.log(1e-12) + r.next() * Math.log(1e12));
                }
            }
            assertTrue(lu.refactor(a2, 3), "refactor rejected at iteration " + iter);
            double[] x = t.b.clone();
            lu.solve(x);
            assertTrue(TestMatrices.backwardError(a2, x, t.b) <= 1e-13);
        }
    }

    @Test
    void growthFallback() {
        // the spike's dbg_refactor case: every diagonal and half of the other entries scaled by
        // 0.5..1.5 reject the stored pivot sequence; a full factorization then solves accurately
        int rejected = 0;
        for (int n : new int[] { 500, 1000, 2000 }) {
            TestMatrices.Triplets t = TestMatrices.randNet(n, 3);
            CscPattern a = t.csc();
            SymbolicAnalysis s = SymbolicAnalysis.build(a, 0);
            SparseLu lu = new SparseLu();
            assertNull(lu.factor(a, s, 0));
            CscPattern a2 = a.copy();
            TestMatrices.Rng r = new TestMatrices.Rng(7);
            for (int c = 0; c < a2.m; c++) {
                for (int p = a2.colStart[c]; p < a2.colStart[c + 1]; p++) {
                    if (a2.rowIndex[p] == c || r.next() < 0.5) {
                        a2.values[p] *= 0.5 + r.next();
                    }
                }
            }
            boolean ok = lu.refactor(a2, 0);
            if (!ok) {
                rejected++;
                assertFalse(lu.isValid());
                assertNull(lu.factor(a2, s, 0));
            }
            double[] x = t.b.clone();
            lu.solve(x);
            double res = TestMatrices.relResidual(a2, x, t.b);
            assertTrue(res <= 1e-8, "n=" + n + " residual " + res + " refactor " + ok);
        }
        assertTrue(rejected > 0, "no refactorization was rejected");
    }

    @Test
    void structuralSingularity() {
        TestMatrices.Triplets t = new TestMatrices.Triplets(4);
        t.add(0, 0, 1);
        t.add(1, 1, 1);
        t.add(2, 1, 1);
        t.add(3, 3, 1);
        t.add(2, 3, 1);
        CscPattern a = t.csc();
        SparseLu lu = new SparseLu();
        SingularityReport rep = lu.factor(a, SymbolicAnalysis.build(a, 0), 0);
        assertNotNull(rep);
        assertTrue(rep.structural);
        assertEquals(2, rep.column);
        assertEquals(-1, rep.row);
        assertEquals(0, rep.pivotAbs);
        assertFalse(lu.isValid());
        assertEquals(-1, lu.fullBuiltFor());
    }

    @Test
    void numericSingularityWithIntactPattern() {
        // rows 1 and 2 identical: structurally fine, numerically singular
        TestMatrices.Triplets t = new TestMatrices.Triplets(4);
        double[][] d = { { 4, 1, 0, 0 }, { 1, 3, 1, 0 }, { 1, 3, 1, 0 }, { 0, 0, 1, 2 } };
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                if (d[i][j] != 0) {
                    t.add(i, j, d[i][j]);
                }
            }
        }
        CscPattern a = t.csc();
        assertTrue(!Transversal.compute(a).structurallySingular);
        SparseLu lu = new SparseLu();
        SingularityReport rep = lu.factor(a, SymbolicAnalysis.build(a, 0), 0);
        assertNotNull(rep);
        assertFalse(rep.structural);
        assertTrue(rep.pivotAbs < SparseLu.SINGULAR_PIVOT);
        assertTrue(rep.column >= 0);
        // the dense kernel agrees: singular
        assertNull(TestMatrices.denseSolve(a, new double[4]));
    }

    @Test
    void failedFactorizationLeavesWorkClean() {
        // a failure must not leave residue in the work arrays: the next factorization is exact
        TestMatrices.Triplets bad = new TestMatrices.Triplets(3);
        bad.add(0, 0, 1);
        bad.add(1, 0, 1);
        bad.add(0, 1, 1);
        bad.add(1, 1, 1);
        bad.add(2, 2, 1);
        CscPattern a = bad.csc();
        SparseLu lu = new SparseLu();
        assertNotNull(lu.factor(a, SymbolicAnalysis.build(a, 0), 0));
        CscPattern good = a.copy();
        good.values[good.slotOf(1, 1)] = 3;
        assertNull(lu.factor(good, SymbolicAnalysis.build(good, 1), 1));
        double[] b = { 1, 2, 3 };
        double[] x = b.clone();
        lu.solve(x);
        assertTrue(TestMatrices.relResidual(good, x, b) <= 1e-15);
    }

    @Test
    void agreesWithDenseKernelOnRandomSystems() {
        TestMatrices.Rng r = new TestMatrices.Rng(42);
        int compared = 0;
        int rejected = 0;
        for (int trial = 0; trial < 200; trial++) {
            int m = 1 + (int) Math.floor(r.next() * 80);
            TestMatrices.Triplets t = new TestMatrices.Triplets(m);
            // MNA-like: a random conductance net with ties to ground and a few source rows
            int nodes = Math.max(1, m - (int) Math.floor(r.next() * Math.min(5, m)));
            for (int a = 0; a < nodes; a++) {
                t.add(a, a, r.logU(1e-6, 1));
                int deg = (int) Math.floor(r.next() * 4);
                for (int k = 0; k < deg; k++) {
                    int b = (int) Math.floor(r.next() * nodes);
                    if (b != a) {
                        double g = r.logU(1e-4, 1e2);
                        t.add(a, a, g);
                        t.add(b, b, g);
                        t.add(a, b, -g);
                        t.add(b, a, -g);
                    }
                }
            }
            for (int k = nodes; k < m; k++) {
                int a = (int) Math.floor(r.next() * nodes);
                int b = (int) Math.floor(r.next() * nodes);
                t.add(k, a, 1);
                t.add(a, k, 1);
                if (b != a) {
                    t.add(k, b, -1);
                    t.add(b, k, -1);
                }
            }
            CscPattern a = t.csc();
            double[] rhs = new double[m];
            for (int i = 0; i < m; i++) {
                rhs[i] = r.next() * 2 - 1;
            }
            double[] xd = TestMatrices.denseSolve(a, rhs);
            if (xd == null || Transversal.compute(a).structurallySingular) {
                continue;
            }
            SparseLu lu = new SparseLu();
            SingularityReport rep = lu.factor(a, SymbolicAnalysis.build(a, 0), 0);
            if (rep != null) {
                // the sparse path is at least as strict: it may reject only a near-singular system
                assertTrue(!rep.structural && rep.pivotAbs < 1e-8, "trial " + trial + " pivot " + rep.pivotAbs);
                rejected++;
                continue;
            }
            double[] xs = rhs.clone();
            lu.solve(xs);
            double bes = TestMatrices.backwardError(a, xs, rhs);
            double bed = TestMatrices.backwardError(a, xd, rhs);
            assertTrue(bes <= 1e-13, "trial " + trial + " m=" + m + " sparse backward error " + bes);
            assertTrue(bed <= 1e-13, "trial " + trial + " m=" + m + " dense backward error " + bed);
            compared++;
        }
        assertTrue(compared >= 150, "only " + compared + " systems compared");
        // the dense kernel solved them all; the sparse path may reject only a rare near-singular one
        assertTrue(rejected <= 2, rejected + " systems rejected by the sparse path");
    }

    @Test
    void emptySystem() {
        CscPattern a = new TestMatrices.Triplets(0).csc();
        SparseLu lu = new SparseLu();
        assertNull(lu.factor(a, SymbolicAnalysis.build(a, 0), 0));
        assertTrue(lu.refactor(a, 0));
        lu.solve(new double[0]);
    }

    @Test
    void oneUnknown() {
        TestMatrices.Triplets t = new TestMatrices.Triplets(1);
        t.add(0, 0, 4);
        CscPattern a = t.csc();
        double[] x = TestMatrices.sparseSolve(a, new double[] { 2 });
        assertEquals(0.5, x[0]);
        assertEquals(0.5, TestMatrices.denseSolve(a, new double[] { 2 })[0]);
        TestMatrices.Triplets z = new TestMatrices.Triplets(1);
        z.add(0, 0, 0);
        CscPattern a0 = z.csc();
        SingularityReport rep = new SparseLu().factor(a0, SymbolicAnalysis.build(a0, 0), 0);
        assertNotNull(rep);
        assertEquals(0, rep.column);
        assertEquals(0, rep.row);
    }

    @Test
    void duplicateTripletsSumInOrder() {
        int[] rows = { 0, 0, 0 };
        int[] cols = { 0, 0, 0 };
        double[] vals = { 0.1, 0.2, 0.3 };
        CscPattern a = CscPattern.fromTriplets(1, rows, cols, vals, 3);
        assertEquals(((0 + 0.1) + 0.2) + 0.3, a.values[0]);
        assertEquals(1, a.nnz());
    }
}
