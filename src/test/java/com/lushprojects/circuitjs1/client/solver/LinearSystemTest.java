package com.lushprojects.circuitjs1.client.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * [SP_SLV_05_01] [SP_SLV_05_02] [SP_SLV_05_04] {@link LinearSystem} on both paths: path choice and
 * the threshold, dense/sparse agreement, pattern growth, the carried state across stamps of one
 * engine analysis, counters, singular reports and the snapshot.
 */
class LinearSystemTest {

    /**
     * Stamps an MNA ladder: series conductances, a nonlinear shunt per node, a grounded source at
     * node 0 (its row reduces node 0 to a constant, so m = nodes). Columns of constants are never
     * stamped after reduction (the engine folds them first).
     */
    private static void stampLadder(LinearSystem ls, int nodes, double shunt, boolean switchClosed) {
        int vs = nodes;
        for (int k = 0; k + 1 < nodes; k++) {
            conductance(ls, k, k + 1, 1e-2);
        }
        for (int k = 0; k < nodes; k++) {
            ls.addEntry(k, k, shunt);
            ls.markNonLinear(k);
        }
        ls.addEntry(vs, 0, 1);
        ls.addEntry(0, vs, -1);
        ls.addRhs(vs, 5);
        if (switchClosed) {
            conductance(ls, 1, nodes - 1, 0.05);
        }
    }

    private static void conductance(LinearSystem ls, int a, int b, double g) {
        ls.addEntry(a, a, g);
        ls.addEntry(b, b, g);
        ls.addEntry(a, b, -g);
        ls.addEntry(b, a, -g);
    }

    private static double[] solveWith(SolverMode mode, int nodes) {
        LinearSystem ls = new LinearSystem();
        ls.beginStamp(nodes + 1, 1);
        stampLadder(ls, nodes, 1e-3, false);
        assertTrue(ls.reduce());
        ls.selectPath(mode);
        assertNull(ls.factor());
        return ls.solve().clone();
    }

    @Test
    void pathChoiceAndThreshold() {
        for (int m : new int[] { 63, 64, 65 }) {
            LinearSystem ls = new LinearSystem();
            ls.beginStamp(m + 1, 1);
            stampLadder(ls, m, 1e-3, false);
            assertTrue(ls.reduce());
            assertEquals(m, ls.size());
            ls.selectPath(SolverMode.AUTO);
            assertEquals(m <= LinearSystem.DENSE_MAX_SIZE ? SolvePath.DENSE : SolvePath.SPARSE, ls.path());
        }
        LinearSystem ls = new LinearSystem();
        ls.beginStamp(201, 1);
        stampLadder(ls, 200, 1e-3, false);
        ls.reduce();
        ls.selectPath(SolverMode.DENSE);
        assertEquals(SolvePath.DENSE, ls.path());
    }

    @Test
    void denseAndSparseAgree() {
        double[] d = solveWith(SolverMode.DENSE, 120);
        double[] s = solveWith(SolverMode.SPARSE, 120);
        for (int i = 0; i < d.length; i++) {
            assertEquals(d[i], s[i], 1e-12 * Math.max(1, Math.abs(d[i])));
        }
    }

    @Test
    void patternGrowsAndCarriesOver() {
        int nodes = 100;
        LinearSystem ls = new LinearSystem();
        SolverInfo info = new SolverInfo();
        ls.beginStamp(nodes + 1, 7);
        stampLadder(ls, nodes, 1e-3, false);
        ls.reduce();
        ls.selectPath(SolverMode.SPARSE);
        // a Newton iteration: restore, stamp the open switch's state, factor
        ls.restoreSnapshot(true);
        assertNull(ls.factor());
        ls.fillInfo(info);
        assertEquals(1, info.symbolicCount);
        int nz0 = info.nonZeros;
        // the switch closes: a stamp outside the pattern grows it before the next factorization
        ls.restoreSnapshot(true);
        conductance(ls, 1, nodes - 1, 0.05);
        assertNull(ls.factor());
        ls.fillInfo(info);
        assertEquals(2, info.symbolicCount);
        assertEquals(nz0 + 2, info.nonZeros);
        double[] x1 = ls.solve().clone();
        // the snapshot gives the grown positions 0: open again, the values are the original ones
        ls.restoreSnapshot(true);
        assertEquals(0.0, ls.assembledValue(ls.rowInfo()[1].mapRow, ls.rowInfo()[nodes - 1].mapCol));
        assertNull(ls.factor());
        // (the stored pivots may fail the check once after the values change; the next
        // iteration with the same values refactors)
        ls.restoreSnapshot(true);
        assertNull(ls.factor());
        ls.fillInfo(info);
        assertEquals(2, info.symbolicCount);
        assertTrue(info.refactorCount > 0);
        // a re-stamp of the same engine analysis (a time-step change): same maps, the grown
        // positions are carried, the symbolic analysis is reused
        ls.beginStamp(nodes + 1, 7);
        stampLadder(ls, nodes, 1e-3, false);
        ls.reduce();
        ls.selectPath(SolverMode.SPARSE);
        ls.restoreSnapshot(true);
        conductance(ls, 1, nodes - 1, 0.05);
        assertNull(ls.factor());
        ls.fillInfo(info);
        assertEquals(2, info.symbolicCount);
        assertEquals(nz0 + 2, info.nonZeros);
        double[] x2 = ls.solve();
        for (int i = 0; i < x1.length; i++) {
            assertEquals(x1[i], x2[i], 1e-12 * Math.max(1, Math.abs(x1[i])));
        }
        // a new engine analysis: counters and carried state start over
        ls.beginStamp(nodes + 1, 8);
        stampLadder(ls, nodes, 1e-3, false);
        ls.reduce();
        ls.selectPath(SolverMode.SPARSE);
        ls.restoreSnapshot(true);
        assertNull(ls.factor());
        ls.fillInfo(info);
        assertEquals(1, info.symbolicCount);
        assertEquals(nz0, info.nonZeros);
    }

    @Test
    void pathChangeDiscardsCarriedState() {
        LinearSystem ls = new LinearSystem();
        SolverInfo info = new SolverInfo();
        for (SolverMode mode : new SolverMode[] { SolverMode.SPARSE, SolverMode.DENSE, SolverMode.SPARSE }) {
            ls.beginStamp(81, 3);
            stampLadder(ls, 80, 1e-3, false);
            ls.reduce();
            ls.selectPath(mode);
            ls.restoreSnapshot(true);
            assertNull(ls.factor());
        }
        ls.fillInfo(info);
        // two sparse stamps, each with its own symbolic analysis
        assertEquals(2, info.symbolicCount);
        assertEquals(3, info.fullFactorCount);
    }

    @Test
    void sparseSingularReportsMapTheUnknown() {
        // row 0 reduces (single unknown 0); the reduced column 0 is full unknown 1, made singular
        LinearSystem ls = new LinearSystem();
        ls.beginStamp(3, 1);
        ls.addEntry(0, 0, 2);
        ls.addRhs(0, 2);
        ls.addEntry(1, 1, 0);
        ls.addEntry(2, 1, 0);
        ls.addEntry(1, 2, 1);
        ls.addEntry(2, 2, 1);
        ls.markNonLinear(1);
        ls.markNonLinear(2);
        assertTrue(ls.reduce());
        ls.selectPath(SolverMode.SPARSE);
        ls.restoreSnapshot(true);
        SingularityReport rep = ls.factor();
        assertNotNull(rep);
        assertFalse(rep.structural);
        assertEquals(1, rep.unknown);
        // the system stays intact: the assembled values are still there
        assertEquals(1.0, ls.assembledValue(0, 1));
    }

    @Test
    void emptyAndDropped() {
        LinearSystem ls = new LinearSystem();
        SolverInfo info = new SolverInfo();
        ls.fillInfo(info);
        assertNull(info.path);
        ls.beginStamp(0, 1);
        assertTrue(ls.reduce());
        ls.selectPath(SolverMode.SPARSE);
        assertNull(ls.factor());
        assertEquals(0, ls.solve().length);
        ls.fillInfo(info);
        assertEquals(SolvePath.SPARSE, info.path);
        assertEquals(0, info.size);
        ls.drop();
        ls.fillInfo(info);
        assertNull(info.path);
        assertEquals(0, info.symbolicCount);
    }

    @Test
    void modeSpelling() {
        assertEquals(SolverMode.SPARSE, SolverMode.parse("sparse"));
        assertNull(SolverMode.parse("Sparse"));
        assertEquals(SolverMode.AUTO, SolverMode.fromStored(null));
        assertEquals(SolverMode.AUTO, SolverMode.fromStored("fast"));
        assertEquals(SolverMode.DENSE, SolverMode.fromStored("dense"));
    }
}
