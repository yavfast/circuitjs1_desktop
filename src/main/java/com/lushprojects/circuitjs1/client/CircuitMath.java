package com.lushprojects.circuitjs1.client;

public class CircuitMath {

    public static boolean isConverged(double v1, double v2) {
        double delta = Math.abs(v2 - v1);
        double mean = Math.abs((v1 + v2) / 2.0);
        if (mean > 0.0) {
            double e = delta / mean;
            return e < 0.01; // < 1%
        }
        return true;
    }

    // Solves the set of n linear equations using a LU factorization
    // previously performed by lu_factor.  On input, b[0..n-1] is the right
    // hand side of the equations, and on output, contains the solution.
    public static void lu_solve(double[][] a, int n, int[] ipvt, double[] b) {
        if (n <= 0) return;

        int i, j;

        // Forward substitution with row interchanges
        for (i = 0; i < n; i++) {
            int row = ipvt[i];
            if (row != i) {
                double swap = b[row];
                b[row] = b[i];
                b[i] = swap;
            }

            // Forward substitution using the lower triangular matrix
            double sum = b[i];
            for (j = 0; j < i; j++) {
                sum -= a[i][j] * b[j];
            }
            b[i] = sum;
        }

        // Back substitution using the upper triangular matrix
        for (i = n - 1; i >= 0; i--) {
            double sum = b[i];
            for (j = i + 1; j < n; j++) {
                sum -= a[i][j] * b[j];
            }
            b[i] = sum / a[i][i];
        }
    }

    // factors a matrix into upper and lower triangular matrices by
    // gaussian elimination.  On entry, a[0..n-1][0..n-1] is the
    // matrix to be factored.  ipvt[] returns an integer vector of pivot
    // indices, used in the lu_solve() routine.
    public static boolean lu_factor(double[][] a, int n, int[] ipvt) {
        // Early exit for edge cases
        if (n <= 0) return false;
        if (n == 1) {
            ipvt[0] = 0;
            return a[0][0] != 0.0;
        }

        // Use Crout's method with partial pivoting
        for (int j = 0; j < n; j++) {
            // Calculate upper and lower triangular elements for this column
            for (int i = 0; i < n; i++) {
                double sum = a[i][j];
                int k_max = (i < j) ? i : j;
                if (k_max > 0) {
                    double[] row_i = a[i];
                    for (int k = 0; k < k_max; k++) {
                        sum -= row_i[k] * a[k][j];
                    }
                    a[i][j] = sum;
                }
            }

            // Find pivot for this column
            double largest = 0.0;
            int largestRow = j;

            for (int i = j; i < n; i++) {
                double abs = a[i][j];
                if (abs < 0.0) {
                    abs = -abs;
                }
                if (abs > largest) {
                    largest = abs;
                    largestRow = i;
                }
            }

            // Check for near-zero pivot (singular matrix)
            if (largest < 1e-14) {
                return false;
            }

            // Perform row interchange if necessary
            if (largestRow != j) {
                double[] temp = a[j];
                a[j] = a[largestRow];
                a[largestRow] = temp;
            }

            // Store pivot information
            ipvt[j] = largestRow;

            // Scale the lower triangular elements by the pivot
            if (j < n - 1) {
                double pivot = a[j][j];
                // Check for singularity again after pivot
                if (Math.abs(pivot) < 1e-14) {
                    return false;
                }
                double pivotInv = 1.0 / pivot;
                for (int i = j + 1; i < n; i++) {
                    a[i][j] *= pivotInv;
                }
            }
        }

        return true;
    }

    public static void invertMatrix(double[][] a, int n) {
        int[] ipvt = new int[n];
        lu_factor(a, n, ipvt);
        int i, j;
        double[] b = new double[n];
        double[][] inva = new double[n][n];

        // solve for each column of identity matrix
        for (i = 0; i != n; i++) {
            for (j = 0; j != n; j++) {
                b[j] = 0;
            }
            b[i] = 1;
            lu_solve(a, n, ipvt, b);
            for (j = 0; j != n; j++) {
                inva[j][i] = b[j];
            }
        }

        // return in original matrix
        for (i = 0; i != n; i++) {
            for (j = 0; j != n; j++) {
                a[i][j] = inva[i][j];
            }
        }
    }

}
