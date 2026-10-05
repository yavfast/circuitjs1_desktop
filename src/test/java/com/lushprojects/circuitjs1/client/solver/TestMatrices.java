package com.lushprojects.circuitjs1.client.solver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.lushprojects.circuitjs1.client.CircuitMath;

/**
 * Test matrices for the solver kernel: the spike's captured post-reduction matrices
 * ({@code src/test/resources/solver/*.json}: {@code {n, I, J, V}} triplets) and a Java port of the
 * spike's MNA-like generators ({@code .dev_flow/cache/sparse-spike/matgen.mjs}), plus norms.
 */
final class TestMatrices {

    private TestMatrices() {
    }

    /** Triplets of an n × n system with its right-hand side. */
    static final class Triplets {
        final int n;
        final List<int[]> ij = new ArrayList<>();
        final List<Double> v = new ArrayList<>();
        double[] b;

        Triplets(int n) {
            this.n = n;
        }

        void add(int i, int j, double x) {
            if (i >= 0 && j >= 0) {
                ij.add(new int[] { i, j });
                v.add(x);
            }
        }

        CscPattern csc() {
            int c = ij.size();
            int[] r = new int[c];
            int[] cl = new int[c];
            double[] x = new double[c];
            for (int k = 0; k < c; k++) {
                r[k] = ij.get(k)[0];
                cl[k] = ij.get(k)[1];
                x[k] = v.get(k);
            }
            return CscPattern.fromTriplets(n, r, cl, x, c);
        }
    }

    // ---- captured matrices

    static Triplets resource(String name) {
        try (InputStream in = TestMatrices.class.getResourceAsStream("/solver/" + name + ".json")) {
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int n = (int) Double.parseDouble(field(s, "n"));
            String[] is = array(s, "I");
            String[] js = array(s, "J");
            String[] vs = array(s, "V");
            Triplets t = new Triplets(n);
            for (int k = 0; k < is.length; k++) {
                t.add(Integer.parseInt(is[k].trim()), Integer.parseInt(js[k].trim()), Double.parseDouble(vs[k].trim()));
            }
            return t;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String field(String s, String key) {
        int p = s.indexOf("\"" + key + "\":") + key.length() + 3;
        int e = p;
        while (e < s.length() && s.charAt(e) != ',' && s.charAt(e) != '}') {
            e++;
        }
        return s.substring(p, e).trim();
    }

    private static String[] array(String s, String key) {
        int p = s.indexOf("\"" + key + "\":[") + key.length() + 4;
        int e = s.indexOf(']', p);
        return s.substring(p, e).split(",");
    }

    // ---- generators (port of matgen.mjs)

    /** Deterministic PRNG (mulberry32, as matgen.mjs). */
    static final class Rng {
        private int s;

        Rng(int seed) {
            s = seed;
        }

        double next() {
            s += 0x6D2B79F5;
            int t = s;
            t = (t ^ (t >>> 15)) * (t | 1);
            t ^= t + (t ^ (t >>> 7)) * (t | 61);
            return ((t ^ (t >>> 14)) & 0xffffffffL) / 4294967296.0;
        }

        double logU(double lo, double hi) {
            return Math.exp(Math.log(lo) + next() * (Math.log(hi) - Math.log(lo)));
        }
    }

    /** MNA builder: node rows first, then voltage-source rows (zero diagonal). Node -1 = ground. */
    static final class Mna {
        final int nodes;
        int vs;
        final List<int[]> ij = new ArrayList<>();
        final List<Double> v = new ArrayList<>();
        final List<double[]> srcs = new ArrayList<>();

        Mna(int nodes) {
            this.nodes = nodes;
        }

        void add(int i, int j, double x) {
            if (i >= 0 && j >= 0) {
                ij.add(new int[] { i, j });
                v.add(x);
            }
        }

        void g(int a, int b, double g) {
            add(a, a, g);
            add(b, b, g);
            add(a, b, -g);
            add(b, a, -g);
        }

        int vsrc(int a, int b, double volts) {
            int k = nodes + vs++;
            add(k, a, -1);
            add(k, b, 1);
            add(a, k, 1);
            add(b, k, -1);
            srcs.add(new double[] { k, volts });
            return k;
        }

        void vcvs(int a, int b, int c, int d, double gain) {
            int k = vsrc(a, b, 0);
            add(k, c, gain);
            add(k, d, -gain);
        }

        Triplets done() {
            Triplets t = new Triplets(nodes + vs);
            t.ij.addAll(ij);
            t.v.addAll(v);
            t.b = new double[t.n];
            for (double[] s : srcs) {
                t.b[(int) s[0]] += s[1];
            }
            return t;
        }
    }

    static Triplets grid2d(int n, int seed) {
        Rng r = new Rng(seed);
        Mna m = new Mna(n * n);
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                if (i + 1 < n) {
                    m.g(j * n + i, j * n + i + 1, r.logU(1e-4, 1e-2));
                }
                if (j + 1 < n) {
                    m.g(j * n + i, (j + 1) * n + i, r.logU(1e-4, 1e-2));
                }
            }
        }
        m.g(n * n - 1, -1, 1e-3);
        m.vsrc(-1, 0, 5);
        m.vsrc(n + 1, (n >> 1) * n + (n >> 1), 1);
        return m.done();
    }

    static Triplets ladder(int n, int seed) {
        Rng r = new Rng(seed);
        Mna m = new Mna(n);
        for (int k = 0; k + 1 < n; k++) {
            m.g(k, k + 1, 1e-2);
        }
        for (int k = 0; k < n; k++) {
            m.g(k, -1, r.logU(1e-6, 1e-1));
        }
        m.vsrc(-1, 0, 5);
        return m.done();
    }

    static Triplets randNet(int n, int seed) {
        Rng r = new Rng(seed);
        Mna m = new Mna(n);
        final int w = 12;
        for (int a = 0; a < n; a++) {
            int d = 1 + (int) Math.floor(r.next() * 2.5);
            for (int k = 0; k < d; k++) {
                int b = Math.min(n - 1, a + 1 + (int) Math.floor(r.next() * w));
                if (b != a) {
                    m.g(a, b, r.logU(1e-8, 1e2));
                }
            }
            if (r.next() < 0.05) {
                m.g(a, -1, r.logU(1e-6, 1e-1));
            }
        }
        int rail = n / 2;
        for (int a = 0; a < n; a++) {
            if (a != rail && r.next() < 0.05) {
                m.g(rail, a, r.logU(1e-5, 1e-2));
            }
        }
        m.vsrc(-1, rail, 5);
        m.vsrc(-1, 0, 1);
        for (int a = 0; a < n; a++) {
            if (r.next() < 0.05) {
                int b = Math.min(n - 1, a + 1 + (int) Math.floor(r.next() * w));
                if (b != a) {
                    m.vsrc(a, b, r.logU(0.1, 5));
                }
            }
        }
        for (int a = 0; a + 3 < n; a++) {
            if (r.next() < 0.01) {
                m.vcvs(a + 3, -1, a + 1, a + 2, 1e5);
            }
        }
        for (int a = 0; a < n; a += 97) {
            m.g(a, -1, 1e-8);
        }
        return m.done();
    }

    // ---- helpers

    /** b := A·xTrue for a known solution xTrue[i] = 1 + i mod 7 (a deterministic, non-trivial b). */
    static double[] rhsFor(CscPattern a) {
        double[] xt = new double[a.m];
        for (int i = 0; i < a.m; i++) {
            xt[i] = 1 + (i % 7) * 0.25;
        }
        double[] b = new double[a.m];
        a.multiply(xt, b);
        return b;
    }

    static double normInf(double[] v) {
        double m = 0;
        for (double x : v) {
            m = Math.max(m, Math.abs(x));
        }
        return m;
    }

    /** ‖A‖∞ (max row sum). */
    static double normInf(CscPattern a) {
        double[] rs = new double[a.m];
        for (int c = 0; c < a.m; c++) {
            for (int p = a.colStart[c]; p < a.colStart[c + 1]; p++) {
                rs[a.rowIndex[p]] += Math.abs(a.values[p]);
            }
        }
        return normInf(rs);
    }

    /** ‖Ax − b‖∞ / ‖b‖∞. */
    static double relResidual(CscPattern a, double[] x, double[] b) {
        double[] ax = new double[a.m];
        a.multiply(x, ax);
        for (int i = 0; i < a.m; i++) {
            ax[i] -= b[i];
        }
        return normInf(ax) / Math.max(normInf(b), Double.MIN_NORMAL);
    }

    /** Backward error ‖Ax − b‖ / (‖A‖·‖x‖ + ‖b‖), infinity norms. */
    static double backwardError(CscPattern a, double[] x, double[] b) {
        double[] ax = new double[a.m];
        a.multiply(x, ax);
        for (int i = 0; i < a.m; i++) {
            ax[i] -= b[i];
        }
        double d = normInf(a) * normInf(x) + normInf(b);
        return d == 0 ? 0 : normInf(ax) / d;
    }

    /** Factors with a fresh symbolic analysis; returns the solution or throws on a report. */
    static double[] sparseSolve(CscPattern a, double[] b) {
        SparseLu lu = new SparseLu();
        SingularityReport rep = lu.factor(a, SymbolicAnalysis.build(a, 0), 0);
        if (rep != null) {
            throw new AssertionError("singular at column " + rep.column + " pivot " + rep.pivotAbs);
        }
        double[] x = b.clone();
        lu.solve(x);
        return x;
    }

    /** The dense kernel's solution, or null when it reports singular. */
    static double[] denseSolve(CscPattern a, double[] b) {
        int n = a.m;
        double[][] d = new double[n][n];
        for (int c = 0; c < n; c++) {
            for (int p = a.colStart[c]; p < a.colStart[c + 1]; p++) {
                d[a.rowIndex[p]][c] += a.values[p];
            }
        }
        int[] ip = new int[n];
        if (!CircuitMath.lu_factor(d, n, ip)) {
            return null;
        }
        double[] x = b.clone();
        CircuitMath.lu_solve(d, n, ip, x);
        return x;
    }
}
