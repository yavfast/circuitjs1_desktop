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
    
    /**
     * Calculates the average value of a signal from scope data.
     */
    public static double calculateAverage(int width, int ipa, int scopePointCount, double[] minV, double[] maxV) {
        double avg = 0;
        if (width == 0) return 0;
        for (int i = 0; i != width; i++) {
            int ip = (i + ipa) & (scopePointCount - 1);
            avg += minV[ip] + maxV[ip];
        }
        return avg / (width * 2.0);
    }

    public static class FreqData {
        public double freq;
        public double periodstd;
        public int periodct;
    }

    /**
     * Calculates the frequency of a signal from scope data.
     */
    public static FreqData calculateFrequency(int width, int ipa, int scopePointCount, double[] maxV, double avg, double timeStep, int speed) {
        int state = 0;
        double thresh = avg * .05;
        int i;
        int oi = 0;
        double avperiod = 0;
        int periodct = -1;
        double avperiod2 = 0;
        // count period lengths
        for (i = 0; i != width; i++) {
            int ip = (i + ipa) & (scopePointCount - 1);
            double q = maxV[ip] - avg;
            int os = state;
            if (q < thresh) {
                state = 1;
            } else if (q > -thresh) {
                state = 2;
            }
            if (state == 2 && os == 1) {
                int pd = i - oi;
                oi = i;
                // short periods can't be counted properly
                if (pd < 12) {
                    continue;
                }
                // skip first period, it might be too short
                if (periodct >= 0) {
                    avperiod += pd;
                    avperiod2 += pd * pd;
                }
                periodct++;
            }
        }

        FreqData res = new FreqData();
        if (periodct > 0) {
            avperiod /= periodct;
            avperiod2 /= periodct;
            res.periodstd = Math.sqrt(avperiod2 - avperiod * avperiod);
            res.freq = 1 / (avperiod * timeStep * speed);
            res.periodct = periodct;
        } else {
            res.freq = 0;
            res.periodstd = 0;
            res.periodct = 0;
        }

        // don't show freq if standard deviation is too great
        if (res.periodct < 1 || res.periodstd > 2) {
            res.freq = 0;
        }
        return res;
    }

    public static class WaveformMetrics {
        public double rms;
        public double average;
        public boolean valid;
    }

    /**
     * Calculates RMS and average values of a signal over full cycles.
     */
    public static WaveformMetrics calculateWaveformMetrics(int width, int ipa, int scopePointCount, double[] maxV, double[] minV, double mid) {
        int i;
        int state = -1;

        // skip zeroes
        for (i = 0; i != width; i++) {
            int ip = (i + ipa) & (scopePointCount - 1);
            if (maxV[ip] != 0) {
                if (maxV[ip] > mid) {
                    state = 1;
                }
                break;
            }
        }
        int firstState = -state;
        int start = i;
        int end = 0;
        int waveCount = 0;
        double endRmsAvg = 0;
        double endAvg = 0;
        double rmsAvg = 0;
        double avg = 0;
        for (; i != width; i++) {
            int ip = (i + ipa) & (scopePointCount - 1);
            boolean sw = false;

            // switching polarity?
            if (state == 1) {
                if (maxV[ip] < mid) {
                    sw = true;
                }
            } else if (minV[ip] > mid) {
                sw = true;
            }

            if (sw) {
                state = -state;

                // completed a full cycle?
                if (firstState == state) {
                    if (waveCount == 0) {
                        start = i;
                        firstState = state;
                        rmsAvg = 0;
                        avg = 0;
                    }
                    waveCount++;
                    end = i;
                    endRmsAvg = rmsAvg;
                    endAvg = avg;
                }
            }
            if (waveCount > 0) {
                double m = (maxV[ip] + minV[ip]) * .5;
                rmsAvg += m * m;
                avg += m;
            }
        }

        WaveformMetrics res = new WaveformMetrics();
        if (waveCount > 1 && end > start) {
            res.rms = Math.sqrt(endRmsAvg / (end - start));
            res.average = endAvg / (end - start);
            res.valid = true;
        } else {
            res.valid = false;
        }
        return res;
    }

    public static class DutyCycleInfo {
        public int dutyCycle; // in percent
        public boolean valid;
    }

    /**
     * Calculates the duty cycle of a signal.
     */
    public static DutyCycleInfo calculateDutyCycle(int width, int ipa, int scopePointCount, double[] maxV, double[] minV, double mid) {
        int i;
        int state = -1;

        // skip zeroes
        for (i = 0; i != width; i++) {
            int ip = (i + ipa) & (scopePointCount - 1);
            if (maxV[ip] != 0) {
                if (maxV[ip] > mid) {
                    state = 1;
                }
                break;
            }
        }
        int firstState = 1;
        int start = i;
        int end = 0;
        int waveCount = 0;
        int dutyLen = 0;
        int middle = 0;
        for (; i != width; i++) {
            int ip = (i + ipa) & (scopePointCount - 1);
            boolean sw = false;

            // switching polarity?
            if (state == 1) {
                if (maxV[ip] < mid) {
                    sw = true;
                }
            } else if (minV[ip] > mid) {
                sw = true;
            }

            if (sw) {
                state = -state;

                // completed a full cycle?
                if (firstState == state) {
                    if (waveCount == 0) {
                        start = end = i;
                    } else {
                        end = start;
                        start = i;
                        dutyLen = end - middle;
                    }
                    waveCount++;
                } else {
                    middle = i;
                }
            }
        }
        DutyCycleInfo res = new DutyCycleInfo();
        if (waveCount > 1 && end > start) {
            res.dutyCycle = 100 * dutyLen / (end - start);
            res.valid = true;
        } else {
            res.valid = false;
        }
        return res;
    }

}
