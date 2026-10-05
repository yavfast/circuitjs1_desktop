package com.lushprojects.circuitjs1.client.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** [SP_SLV_01_07] Maximum transversal. */
class TransversalTest {

    /** Every row k gets a column colPerm[k] holding a structural entry; colPerm is a permutation. */
    private static void assertValidPairing(CscPattern a, Transversal t) {
        assertFalse(t.structurallySingular);
        boolean[] seen = new boolean[a.m];
        for (int k = 0; k < a.m; k++) {
            int c = t.colPerm[k];
            assertFalse(seen[c], "column paired twice");
            seen[c] = true;
            assertTrue(a.slotOf(k, c) >= 0, "row " + k + " paired with column " + c + " without an entry");
        }
    }

    @Test
    void zeroDiagonalVoltageSourceRowsArePaired() {
        TestMatrices.Triplets t = TestMatrices.grid2d(6, 1);
        CscPattern a = t.csc();
        // the source rows have no diagonal
        assertEquals(-1, a.slotOf(a.m - 1, a.m - 1));
        assertValidPairing(a, Transversal.compute(a));
    }

    @Test
    void capturedAndGeneratedMatricesArePaired() {
        for (String r : new String[] { "grid_22", "cladder_500", "dladder_500", "dgrid_14" }) {
            CscPattern a = TestMatrices.resource(r).csc();
            assertValidPairing(a, Transversal.compute(a));
        }
        int singular = 0;
        for (int seed = 1; seed <= 20; seed++) {
            CscPattern a = TestMatrices.randNet(100 + 13 * seed, seed).csc();
            Transversal tr = Transversal.compute(a);
            if (tr.structurallySingular) {
                // e.g. seed 9: the rail source and a VCVS output on the same node; dense agrees
                assertEquals(null, TestMatrices.denseSolve(a, new double[a.m]));
                singular++;
            } else {
                assertValidPairing(a, tr);
            }
        }
        assertTrue(singular <= 2);
    }

    @Test
    void permutedIdentityNeedsAugmentingPaths() {
        // a cyclic shift with an extra entry that the cheap pass takes first
        int m = 5;
        TestMatrices.Triplets t = new TestMatrices.Triplets(m);
        for (int k = 0; k < m; k++) {
            t.add((k + 1) % m, k, 1);
        }
        t.add(0, 0, 9); // column 0 grabs row 0 cheaply; column 4 then needs a path
        t.add(2, 4, 0); // a structural zero still counts
        CscPattern a = t.csc();
        assertValidPairing(a, Transversal.compute(a));
    }

    @Test
    void emptyColumnIsStructurallySingular() {
        TestMatrices.Triplets t = new TestMatrices.Triplets(4);
        t.add(0, 0, 1);
        t.add(1, 1, 1);
        t.add(2, 1, 1);
        t.add(3, 3, 1);
        t.add(2, 3, 1);
        Transversal tr = Transversal.compute(t.csc());
        assertTrue(tr.structurallySingular);
        assertEquals(2, tr.unmatchedCol);
    }

    @Test
    void twoColumnsSharingOneRowIsSingular() {
        TestMatrices.Triplets t = new TestMatrices.Triplets(3);
        t.add(0, 0, 1);
        t.add(1, 1, 1);
        t.add(1, 2, 1);
        t.add(2, 0, 1);
        Transversal tr = Transversal.compute(t.csc());
        assertTrue(tr.structurallySingular);
        assertNotEquals(-1, tr.unmatchedCol);
    }

    @Test
    void emptySystem() {
        Transversal tr = Transversal.compute(new TestMatrices.Triplets(0).csc());
        assertFalse(tr.structurallySingular);
        assertEquals(0, tr.colPerm.length);
    }
}
