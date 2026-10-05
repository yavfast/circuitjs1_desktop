package com.lushprojects.circuitjs1.client.solver;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** [SP_SLV_01_07] Minimum-degree ordering. */
class MinimumDegreeTest {

    private static int[] identity(int m) {
        int[] p = new int[m];
        for (int i = 0; i < m; i++) {
            p[i] = i;
        }
        return p;
    }

    private static void assertPermutation(int[] order, int m) {
        assertEquals(m, order.length);
        boolean[] seen = new boolean[m];
        for (int v : order) {
            assertFalse(seen[v]);
            seen[v] = true;
        }
    }

    @Test
    void hubIsEliminatedLast() {
        // arrow matrix: vertex 0 connected to all others; eliminating it first fills everything
        int m = 30;
        TestMatrices.Triplets t = new TestMatrices.Triplets(m);
        for (int i = 0; i < m; i++) {
            t.add(i, i, 4);
            if (i > 0) {
                t.add(0, i, -1);
                t.add(i, 0, -1);
            }
        }
        int[] order = MinimumDegree.order(t.csc(), identity(m));
        assertPermutation(order, m);
        // the last two vertices tie at degree 1, so the hub is one of them
        assertTrue(order[m - 1] == 0 || order[m - 2] == 0);
    }

    @Test
    void tridiagonalHasNoFill() {
        int m = 200;
        TestMatrices.Triplets t = new TestMatrices.Triplets(m);
        for (int i = 0; i < m; i++) {
            t.add(i, i, 3);
            if (i + 1 < m) {
                t.add(i, i + 1, -1);
                t.add(i + 1, i, -1);
            }
        }
        CscPattern a = t.csc();
        SymbolicAnalysis s = SymbolicAnalysis.build(a, 0);
        assertPermutation(s.order, m);
        SparseLu lu = new SparseLu();
        assertEquals(null, lu.factor(a, s, 0));
        // L and U together hold exactly the entries of A (no fill): diagonal counted in both
        assertEquals(a.nnz() + m, lu.factorNonZeros());
    }

    @Test
    void deterministic() {
        CscPattern a = TestMatrices.randNet(400, 5).csc();
        Transversal tr = Transversal.compute(a);
        assertArrayEquals(MinimumDegree.order(a, tr.colPerm), MinimumDegree.order(a, tr.colPerm));
    }

    @Test
    void gridFillStaysNearLinear() {
        CscPattern a = TestMatrices.resource("grid_22").csc();
        SymbolicAnalysis s = SymbolicAnalysis.build(a, 0);
        SparseLu lu = new SparseLu();
        assertEquals(null, lu.factor(a, s, 0));
        // a 22 × 22 grid: dense would be m² ≈ 233 000; minimum degree keeps it far below
        assertTrue(lu.factorNonZeros() < 20 * a.nnz(), "fill " + lu.factorNonZeros());
    }
}
