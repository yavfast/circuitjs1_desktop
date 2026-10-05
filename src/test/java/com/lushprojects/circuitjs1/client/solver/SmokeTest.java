package com.lushprojects.circuitjs1.client.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.lushprojects.circuitjs1.client.CircuitMath;
import org.junit.jupiter.api.Test;

/** [PL_SLV_P1] Proves the JUnit wiring: the GWT-free dense kernel runs on the plain JVM. */
class SmokeTest {

    @Test
    void denseKernelSolvesTwoByTwo() {
        double[][] a = { { 2, 1 }, { 1, 3 } };
        double[] b = { 3, 5 };
        int[] ip = new int[2];
        assertEquals(true, CircuitMath.lu_factor(a, 2, ip));
        CircuitMath.lu_solve(a, 2, ip, b);
        assertEquals(0.8, b[0], 1e-15);
        assertEquals(1.4, b[1], 1e-15);
    }
}
