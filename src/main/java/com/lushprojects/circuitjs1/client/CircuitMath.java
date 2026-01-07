package com.lushprojects.circuitjs1.client;

public class CircuitMath {

    // Diagnostics for singular-matrix failures in lu_factor().
    // This is only meaningful when lu_factor() returns false.
    private static volatile int lastLuFailColumn = -1;
    private static volatile int lastLuFailRow = -1;
    private static volatile double lastLuFailPivotAbs = 0.0;

    public static int getLastLuFailColumn() {
        return lastLuFailColumn;
    }

    public static int getLastLuFailRow() {
        return lastLuFailRow;
    }

    public static double getLastLuFailPivotAbs() {
        return lastLuFailPivotAbs;
    }

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
        // Reset diagnostics for this run.
        lastLuFailColumn = -1;
        lastLuFailRow = -1;
        lastLuFailPivotAbs = 0.0;

        // Early exit for edge cases.
        // A 0x0 matrix is a valid case (everything simplified to constants).
        if (n < 0) return false;
        if (n == 0) return true;
        if (n == 1) {
            ipvt[0] = 0;
            if (a[0][0] == 0.0) {
                lastLuFailColumn = 0;
                lastLuFailRow = 0;
                lastLuFailPivotAbs = 0.0;
                return false;
            }
            return true;
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
                lastLuFailColumn = j;
                lastLuFailRow = largestRow;
                lastLuFailPivotAbs = largest;
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
                    lastLuFailColumn = j;
                    lastLuFailRow = j;
                    lastLuFailPivotAbs = Math.abs(pivot);
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
        double[] b = new double[n];
        double[][] inva = new double[n][n];

        // solve for each column of identity matrix
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                b[j] = 0;
            }
            b[i] = 1;

            lu_solve(a, n, ipvt, b);

            for (int j = 0; j < n; j++) {
                inva[j][i] = b[j];
            }
        }

        // return in original matrix
        for (int i = 0; i < n; i++) {
            System.arraycopy(inva[i], 0, a[i], 0, n);
        }
    }
    
    /**
     * Calculates the average value of a signal from scope data.
     */
    public static double calculateAverage(int width, int ipa, int scopePointCount, double[] minV, double[] maxV) {
        if (width == 0) return 0;

        double avg = 0;
        for (int i = 0; i < width; i++) {
            int ip = (i + ipa) & (scopePointCount - 1);
            avg += minV[ip] + maxV[ip];
        }
        return avg / (width * 2.0);
    }

    public static class FreqData {
        public double frequency;
        public double periodStandardDeviation;
        public int periodCount;
    }

    /**
     * Calculates the frequency of a signal from scope data.
     *
     * @param viewWidth          The number of samples visible in the scope view.
     * @param startIndex         The starting index in the ring buffer for the visible samples.
     * @param ringBufferSize     The total size of the scope's ring buffer.
     * @param maxValues          The array of maximum voltage/current values from the scope plot.
     * @param averageValue       The average value of the signal, used for thresholding.
     * @param simulationTimeStep The time step of the circuit simulation.
     * @param scopeSpeed         The speed setting of the scope.
     * @return A FreqData object containing the calculated frequency, standard deviation, and period count.
     */
    public static FreqData calculateFrequency(int viewWidth, int startIndex, int ringBufferSize, double[] maxValues, double averageValue, double simulationTimeStep, int scopeSpeed) {
        int crossingState = 0; // 1 for below threshold, 2 for above
        double threshold = averageValue * 0.05;
        int lastCrossingIndex = 0;
        double totalPeriodInSamples = 0;
        int periodCount = 0;
        double totalPeriodInSamplesSquared = 0;
        boolean isFirstEdgeFound = false;

        // count period lengths by detecting rising edges
        for (int sampleIndex = 0; sampleIndex != viewWidth; sampleIndex++) {
            int ringBufferIndex = (sampleIndex + startIndex) & (ringBufferSize - 1);
            double valueRelativeToAverage = maxValues[ringBufferIndex] - averageValue;
            int previousState = crossingState;

            if (valueRelativeToAverage < threshold) {
                crossingState = 1;
            } else if (valueRelativeToAverage > -threshold) {
                crossingState = 2;
            }

            // Check for a rising edge (transition from state 1 to 2)
            if (crossingState == 2 && previousState == 1) {
                if (isFirstEdgeFound) {
                    int periodDurationInSamples = sampleIndex - lastCrossingIndex;

                    // Short periods can't be counted properly, so filter them out.
                    if (periodDurationInSamples >= 12) {
                        totalPeriodInSamples += periodDurationInSamples;
                        totalPeriodInSamplesSquared += periodDurationInSamples * periodDurationInSamples;
                        periodCount++;
                    }
                } else {
                    isFirstEdgeFound = true;
                }
                lastCrossingIndex = sampleIndex;
            }
        }

        FreqData result = new FreqData();
        if (periodCount > 0) {
            double averagePeriod = totalPeriodInSamples / periodCount;
            double averagePeriodSquared = totalPeriodInSamplesSquared / periodCount;
            result.periodStandardDeviation = Math.sqrt(averagePeriodSquared - averagePeriod * averagePeriod);
            result.frequency = 1 / (averagePeriod * simulationTimeStep * scopeSpeed);
            result.periodCount = periodCount;
        } else {
            result.frequency = 0;
            result.periodStandardDeviation = 0;
            result.periodCount = 0;
        }

        // Don't show frequency if standard deviation is too great, as it indicates an unstable signal.
        if (result.periodCount < 1 || result.periodStandardDeviation > 2) {
            result.frequency = 0;
        }
        return result;
    }

    public static class WaveformMetrics {
        public double rms;
        public double average;
        public boolean valid;
    }

    /**
     * Calculates RMS and average values of a signal over a series of full, stable cycles.
     *
     * @param viewWidth      The number of samples visible in the scope view.
     * @param startIndex     The starting index in the ring buffer for the visible samples.
     * @param ringBufferSize The total size of the scope's ring buffer.
     * @param maxValues      The array of maximum voltage/current values from the scope plot.
     * @param minValues      The array of minimum voltage/current values from the scope plot.
     * @param midpoint       The vertical center of the waveform, used to detect zero crossings.
     * @return A WaveformMetrics object containing the calculated RMS and average values, and a validity flag.
     */
    public static WaveformMetrics calculateWaveformMetrics(int viewWidth, int startIndex, int ringBufferSize, double[] maxValues, double[] minValues, double midpoint) {
        int crossingState = 0; // 1 for below midpoint, 2 for above
        int previousState;

        int firstCycleStartIndex = -1;
        int lastCycleEndIndex = -1;
        int cyclesFound = 0;

        // Pass 1: Identify the boundaries of a block of full cycles.
        for (int i = 0; i < viewWidth; i++) {
            int bufferIndex = (i + startIndex) & (ringBufferSize - 1);
            double value = (maxValues[bufferIndex] + minValues[bufferIndex]) * 0.5;
            previousState = crossingState;

            if (value < midpoint) {
                crossingState = 1;
            } else {
                crossingState = 2;
            }

            // Detect a rising edge, which marks a cycle boundary.
            if (crossingState == 2 && previousState == 1) {
                if (firstCycleStartIndex == -1) {
                    // Found the start of the first potential cycle.
                    firstCycleStartIndex = i;
                } else {
                    // Found the end of a cycle.
                    lastCycleEndIndex = i;
                    cyclesFound++;
                }
            }
        }

        WaveformMetrics result = new WaveformMetrics();
        result.valid = false;

        // We need at least one full, stable cycle to perform the calculation.
        if (cyclesFound < 1) {
            return result;
        }

        double sumOfValues = 0;
        double sumOfSquares = 0;
        int samplesInCycles = lastCycleEndIndex - firstCycleStartIndex;

        if (samplesInCycles <= 0) {
            return result;
        }

        // Pass 2: Calculate metrics ONLY over the identified block of full cycles.
        for (int i = firstCycleStartIndex; i < lastCycleEndIndex; i++) {
            int bufferIndex = (i + startIndex) & (ringBufferSize - 1);
            double value = (maxValues[bufferIndex] + minValues[bufferIndex]) * 0.5;
            sumOfValues += value;
            sumOfSquares += value * value;
        }

        result.average = sumOfValues / samplesInCycles;
        result.rms = Math.sqrt(sumOfSquares / samplesInCycles);
        result.valid = true;

        return result;
    }

    public static class DutyCycleInfo {
        public int dutyCycle; // in percent
        public boolean valid;
    }

    /**
     * Calculates the duty cycle of a signal by averaging over complete cycles.
     *
     * @param viewWidth      The number of samples visible in the scope view.
     * @param startIndex     The starting index in the ring buffer for the visible samples.
     * @param ringBufferSize The total size of the scope's ring buffer.
     * @param maxValues      The array of maximum voltage/current values from the scope plot.
     * @param minValues      The array of minimum voltage/current values from the scope plot.
     * @param midpoint       The vertical center of the waveform, used to detect zero crossings.
     * @return A DutyCycleInfo object containing the calculated duty cycle and a validity flag.
     */
    public static DutyCycleInfo calculateDutyCycle(int viewWidth, int startIndex, int ringBufferSize, double[] maxValues, double[] minValues, double midpoint) {
        int crossingState = 0; // 1 for below midpoint, 2 for above
        int previousState;

        int[] risingEdges = new int[viewWidth];
        int[] fallingEdges = new int[viewWidth];
        int risingEdgeCount = 0;
        int fallingEdgeCount = 0;

        // Pass 1: Find all rising and falling edges.
        for (int i = 0; i < viewWidth; i++) {
            int bufferIndex = (i + startIndex) & (ringBufferSize - 1);
            double value = (maxValues[bufferIndex] + minValues[bufferIndex]) * 0.5;
            previousState = crossingState;

            if (value < midpoint) {
                crossingState = 1;
            } else {
                crossingState = 2;
            }

            if (previousState == 1 && crossingState == 2) {
                if (risingEdgeCount < viewWidth) risingEdges[risingEdgeCount++] = i;
            } else if (previousState == 2 && crossingState == 1) {
                if (fallingEdgeCount < viewWidth) fallingEdges[fallingEdgeCount++] = i;
            }
        }

        DutyCycleInfo result = new DutyCycleInfo();
        result.valid = false;

        if (risingEdgeCount == 0 || fallingEdgeCount == 0) {
            return result;
        }

        double totalPulseWidth = 0;
        double totalPeriod = 0;
        int completeCycles = 0;

        // Pass 2: Calculate average pulse width and period.
        // We iterate through rising edges to define the start of each period.
        for (int i = 0; i < risingEdgeCount - 1; i++) {
            int risingEdge1 = risingEdges[i];
            int risingEdge2 = risingEdges[i + 1];

            // Find the corresponding falling edge within this period.
            Integer correspondingFallingEdge = null;
            for (int j = 0; j < fallingEdgeCount; j++) {
                int fallingEdge = fallingEdges[j];
                if (fallingEdge > risingEdge1 && fallingEdge < risingEdge2) {
                    correspondingFallingEdge = fallingEdge;
                    break;
                }
            }

            if (correspondingFallingEdge != null) {
                totalPulseWidth += correspondingFallingEdge - risingEdge1;
                totalPeriod += risingEdge2 - risingEdge1;
                completeCycles++;
            }
        }

        if (completeCycles > 0) {
            double averagePulseWidth = totalPulseWidth / completeCycles;
            double averagePeriod = totalPeriod / completeCycles;

            if (averagePeriod > 0) {
                result.dutyCycle = (int) (100 * averagePulseWidth / averagePeriod);
                result.valid = true;
            }
        }

        return result;
    }

}
