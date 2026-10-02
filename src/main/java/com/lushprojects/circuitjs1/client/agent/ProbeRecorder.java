package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;

/**
 * [SP_AGA_01_09] [SP_AGA_03_07] The recording of one probe during a run: streaming statistics
 * over every sample and a bucket-decimated series.
 * <ul>
 * <li><b>Exact statistics.</b> {@code samples}, {@code tStart}, {@code tEnd}, {@code min},
 *     {@code max}, {@code peakToPeak} and {@code final} are kept over all samples; {@code mean} and
 *     {@code rms} are time-weighted over the sample intervals (trapezoidal), because the time step
 *     is adaptive.</li>
 * <li><b>Series.</b> {@code B = floor(maxPoints / 2)} buckets, each holding its min and max sample
 *     with their times; when all are used, adjacent buckets merge pairwise and the bucket width
 *     doubles. Up to {@code 2B} samples are kept raw first (each its own point). The series lists
 *     per bucket its min and max sample in time order, so it never exceeds {@code maxPoints}.</li>
 * <li><b>Shape statistics.</b> {@code frequency}, {@code dutyCycle} and {@code riseTime} need the
 *     final mean and span, so they are computed at the end from a second, finer series of the same
 *     kind ({@link #SHAPE_BUCKETS} buckets): exact for runs of up to {@code 2 × SHAPE_BUCKETS}
 *     samples, from the min/max envelope beyond.</li>
 * <li><b>Output.</b> Values (stats and series {@code v}) carry 6 significant digits; times
 *     ({@code t}, {@code tStart}, {@code tEnd}) carry {@link #TIME_DIGITS}, so a short window late
 *     in simulated time keeps distinct time stamps.</li>
 * </ul>
 * Non-finite samples (NaN or infinity from the solver) are counted, not recorded.
 */
final class ProbeRecorder {

    /** Buckets of the series the shape statistics are computed from. */
    static final int SHAPE_BUCKETS = 4096;
    /** Significant digits of emitted values ([SP_AGA_03_07]). */
    static final int VALUE_DIGITS = 6;
    /** Significant digits of emitted times. */
    static final int TIME_DIGITS = 9;

    final Readings.ProbeTarget target;
    private final Decimator series;
    private final Decimator shape;

    private int samples;
    private int nonFinite;
    private double tStart, tEnd, min, max, last;
    private double lastT;
    /** Time integrals of v and v² over the sample intervals. */
    private double integral, integralSq;
    /** Arithmetic sums, used when all samples share one time. */
    private double sum, sumSq;

    ProbeRecorder(Readings.ProbeTarget target, int maxPoints) {
        this.target = target;
        this.series = new Decimator(Math.max(1, maxPoints / 2));
        this.shape = new Decimator(SHAPE_BUCKETS);
    }

    /** Records the value at simulated time {@code t} (times strictly increase). */
    void record(double t, double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            nonFinite++;
            return;
        }
        if (samples == 0) {
            tStart = t;
            min = max = v;
        } else {
            double dt = t - lastT;
            integral += (last + v) * 0.5 * dt;
            integralSq += (last * last + v * v) * 0.5 * dt;
            if (v < min) {
                min = v;
            }
            if (v > max) {
                max = v;
            }
        }
        sum += v;
        sumSq += v * v;
        samples++;
        tEnd = lastT = t;
        last = v;
        series.add(t, v);
        shape.add(t, v);
    }

    /** @return the number of non-finite samples left out */
    int nonFiniteCount() {
        return nonFinite;
    }

    /** @return the ProbeResult JSON object */
    JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("name", new JSONString(target.name));
        o.put("unit", new JSONString(target.unit()));
        o.put("stats", stats());
        JSONObject ser = new JSONObject();
        double[][] pts = series.points();
        ser.put("t", numbers(pts[0], TIME_DIGITS));
        ser.put("v", numbers(pts[1], VALUE_DIGITS));
        o.put("series", ser);
        return o;
    }

    private JSONObject stats() {
        JSONObject s = new JSONObject();
        s.put("samples", new JSONNumber(samples));
        if (samples == 0) {
            // nothing was recorded (recordFrom after the run's end, or a run that took no step)
            return s;
        }
        double span = tEnd - tStart;
        double mean = span > 0 ? integral / span : sum / samples;
        double meanSq = span > 0 ? integralSq / span : sumSq / samples;
        double p2p = max - min;
        s.put("tStart", num(tStart, TIME_DIGITS));
        s.put("tEnd", num(tEnd, TIME_DIGITS));
        s.put("min", num(min, VALUE_DIGITS));
        s.put("max", num(max, VALUE_DIGITS));
        s.put("mean", num(mean, VALUE_DIGITS));
        s.put("rms", num(Math.sqrt(Math.max(0, meanSq)), VALUE_DIGITS));
        s.put("peakToPeak", num(p2p, VALUE_DIGITS));
        s.put("final", num(last, VALUE_DIGITS));
        double[][] pts = shape.points();
        Double freq = frequency(pts[0], pts[1], mean, p2p);
        if (freq != null) {
            s.put("frequency", num(freq, VALUE_DIGITS));
            s.put("dutyCycle", num(dutyCycle(pts[0], pts[1], mean), VALUE_DIGITS));
        }
        Double rise = riseTime(pts[0], pts[1], min, max);
        if (rise != null) {
            s.put("riseTime", num(rise, VALUE_DIGITS));
        }
        return s;
    }

    // ---------------------------------------------------------------- shape statistics

    /**
     * Mean rate of rising crossings of {@code mean} with a hysteresis of 5 % of the span: a rising
     * crossing is counted when the signal passes {@code mean + h/2} after having been below
     * {@code mean − h/2}; its time is interpolated at {@code mean + h/2}.
     *
     * @return crossings per second, or null with fewer than 2 rising crossings
     */
    static Double frequency(double[] t, double[] v, double mean, double p2p) {
        if (p2p <= 0 || t.length < 2) {
            return null;
        }
        double h = 0.05 * p2p;
        double hi = mean + h / 2;
        double lo = mean - h / 2;
        boolean armed = v[0] < lo;
        int count = 0;
        double first = 0, lastCross = 0;
        for (int i = 1; i < t.length; i++) {
            if (v[i] < lo) {
                armed = true;
            } else if (armed && v[i] >= hi) {
                double tc = cross(t[i - 1], v[i - 1], t[i], v[i], hi);
                if (count == 0) {
                    first = tc;
                }
                lastCross = tc;
                count++;
                armed = false;
            }
        }
        if (count < 2 || lastCross <= first) {
            return null;
        }
        return (count - 1) / (lastCross - first);
    }

    /** Fraction of the recorded time with the value above {@code mean} (linear between points). */
    static double dutyCycle(double[] t, double[] v, double mean) {
        double above = 0;
        double total = t[t.length - 1] - t[0];
        if (total <= 0) {
            return v[0] > mean ? 1 : 0;
        }
        for (int i = 1; i < t.length; i++) {
            double dt = t[i] - t[i - 1];
            boolean a = v[i - 1] > mean;
            boolean b = v[i] > mean;
            if (a && b) {
                above += dt;
            } else if (a != b) {
                double tc = cross(t[i - 1], v[i - 1], t[i], v[i], mean);
                above += a ? tc - t[i - 1] : t[i] - tc;
            }
        }
        return above / total;
    }

    /**
     * Time from 10 % to 90 % of the {@code min → max} span on the first rising transition: the
     * signal rises through the 10 % level from below and reaches the 90 % level before falling
     * back below 10 %.
     *
     * @return the rise time, or null when there is no such transition
     */
    static Double riseTime(double[] t, double[] v, double min, double max) {
        double span = max - min;
        if (span <= 0 || t.length < 2) {
            return null;
        }
        double lo = min + 0.1 * span;
        double hi = min + 0.9 * span;
        boolean below = v[0] <= lo;
        double tLo = Double.NaN;
        for (int i = 1; i < t.length; i++) {
            if (v[i] <= lo) {
                below = true;
                tLo = Double.NaN;
                continue;
            }
            if (below && Double.isNaN(tLo)) {
                tLo = cross(t[i - 1], v[i - 1], t[i], v[i], lo);
            }
            if (!Double.isNaN(tLo) && v[i] >= hi) {
                double tHi = v[i - 1] >= hi ? t[i - 1] : cross(t[i - 1], v[i - 1], t[i], v[i], hi);
                return Math.max(0, tHi - tLo);
            }
        }
        return null;
    }

    /** @return the time where the segment (t0,v0)–(t1,v1) reaches {@code level} (clamped) */
    private static double cross(double t0, double v0, double t1, double v1, double level) {
        if (v1 == v0) {
            return t1;
        }
        double f = (level - v0) / (v1 - v0);
        f = Math.max(0, Math.min(1, f));
        return t0 + f * (t1 - t0);
    }

    // ---------------------------------------------------------------- output helpers

    private static JSONArray numbers(double[] values, int digits) {
        JSONArray a = new JSONArray();
        for (int i = 0; i < values.length; i++) {
            a.set(i, num(values[i], digits));
        }
        return a;
    }

    private static JSONNumber num(double v, int digits) {
        return new JSONNumber(round(v, digits));
    }

    /**
     * Rounds to {@code digits} significant digits. The result is the double nearest to the
     * decimal value, so it prints with at most that many digits.
     */
    static double round(double v, int digits) {
        if (v == 0 || Double.isNaN(v) || Double.isInfinite(v)) {
            return v;
        }
        int exp = (int) Math.floor(Math.log10(Math.abs(v)));
        int k = digits - 1 - exp;
        double scaled = v * Math.pow(10, k);
        if (Double.isInfinite(scaled) || Double.isNaN(scaled)) {
            return v;
        }
        long n = Math.round(scaled);
        // decimal exponent parsing keeps the result correctly rounded
        return Double.parseDouble(n + "e" + (-k));
    }

    // ---------------------------------------------------------------- decimation

    /**
     * [SP_AGA_03_07] Min/max bucket decimation with pairwise merging; the first {@code 2B} samples
     * are kept raw.
     */
    static final class Decimator {
        private final int buckets;
        private final double[] rawT;
        private final double[] rawV;
        private int rawN;
        private boolean bucketed;
        private double t0;
        private double width;
        private final double[] minT, minV, maxT, maxV;
        private final boolean[] used;

        Decimator(int buckets) {
            this.buckets = buckets;
            rawT = new double[2 * buckets];
            rawV = new double[2 * buckets];
            minT = new double[buckets];
            minV = new double[buckets];
            maxT = new double[buckets];
            maxV = new double[buckets];
            used = new boolean[buckets];
        }

        void add(double t, double v) {
            if (!bucketed) {
                if (rawN < rawT.length) {
                    rawT[rawN] = t;
                    rawV[rawN] = v;
                    rawN++;
                    return;
                }
                toBuckets(t);
            }
            put(t, v);
        }

        /** Switches from raw samples to buckets wide enough for the raw span and {@code tNext}. */
        private void toBuckets(double tNext) {
            bucketed = true;
            t0 = rawT[0];
            width = Math.max((tNext - t0) / buckets * (1 + 1e-9), Double.MIN_VALUE);
            for (int i = 0; i < rawN; i++) {
                put(rawT[i], rawV[i]);
            }
        }

        private void put(double t, double v) {
            int idx = index(t);
            while (idx >= buckets) {
                merge();
                idx = index(t);
            }
            if (!used[idx]) {
                used[idx] = true;
                minT[idx] = maxT[idx] = t;
                minV[idx] = maxV[idx] = v;
                return;
            }
            if (v < minV[idx]) {
                minV[idx] = v;
                minT[idx] = t;
            }
            if (v > maxV[idx]) {
                maxV[idx] = v;
                maxT[idx] = t;
            }
        }

        private int index(double t) {
            double f = Math.floor((t - t0) / width);
            return f < 0 ? 0 : f >= buckets ? buckets : (int) f;
        }

        /** Adjacent buckets merge pairwise; the bucket width doubles. */
        private void merge() {
            for (int i = 0; i < buckets; i++) {
                if (!used[i]) {
                    continue;
                }
                int j = i / 2;
                if (j == i) {
                    continue;
                }
                double mnT = minT[i], mnV = minV[i], mxT = maxT[i], mxV = maxV[i];
                used[i] = false;
                if (!used[j]) {
                    used[j] = true;
                    minT[j] = mnT;
                    minV[j] = mnV;
                    maxT[j] = mxT;
                    maxV[j] = mxV;
                    continue;
                }
                if (mnV < minV[j]) {
                    minV[j] = mnV;
                    minT[j] = mnT;
                }
                if (mxV > maxV[j]) {
                    maxV[j] = mxV;
                    maxT[j] = mxT;
                }
            }
            width *= 2;
        }

        /** @return {t[], v[]} of the series in time order */
        double[][] points() {
            if (!bucketed) {
                double[] t = new double[rawN];
                double[] v = new double[rawN];
                System.arraycopy(rawT, 0, t, 0, rawN);
                System.arraycopy(rawV, 0, v, 0, rawN);
                return new double[][] { t, v };
            }
            int n = 0;
            for (int i = 0; i < buckets; i++) {
                if (used[i]) {
                    n += minT[i] == maxT[i] ? 1 : 2;
                }
            }
            double[] t = new double[n];
            double[] v = new double[n];
            int k = 0;
            for (int i = 0; i < buckets; i++) {
                if (!used[i]) {
                    continue;
                }
                if (minT[i] == maxT[i]) {
                    t[k] = minT[i];
                    v[k++] = minV[i];
                } else if (minT[i] < maxT[i]) {
                    t[k] = minT[i];
                    v[k++] = minV[i];
                    t[k] = maxT[i];
                    v[k++] = maxV[i];
                } else {
                    t[k] = maxT[i];
                    v[k++] = maxV[i];
                    t[k] = minT[i];
                    v[k++] = minV[i];
                }
            }
            return new double[][] { t, v };
        }
    }
}
