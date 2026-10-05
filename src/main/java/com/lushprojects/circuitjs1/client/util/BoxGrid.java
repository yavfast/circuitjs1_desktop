package com.lushprojects.circuitjs1.client.util;

import java.util.Arrays;

/**
 * A static spatial index of integer boxes (inclusive bounds), for the "which boxes may touch
 * this point / this box" questions of the circuit analysis and the connectivity checks, which
 * were element-by-element scans (quadratic in the circuit size).
 * <p>
 * The boxes are bucketed into a dense array of square buckets over their extent
 * ({@link #BUCKET} px, doubled while the array would exceed {@link #MAX_BUCKETS}); a box wider
 * or taller than {@link #MAX_SPAN} buckets goes to a list returned by every query. An empty box
 * (a maximum below its minimum) is never returned. Queries return a <b>superset</b> of the boxes that touch the query (every box
 * whose buckets overlap it), as box indices in ascending order without duplicates: the caller
 * applies its exact test, so results and their order equal those of a full scan in index order.
 * Stateless apart from the index itself (L0).
 */
public final class BoxGrid {

    /** Initial bucket size in pixels. */
    static final int BUCKET = 64;
    /** Upper bound of the bucket array size. */
    static final int MAX_BUCKETS = 1 << 18;
    /** A box spanning more buckets than this along an axis is returned by every query. */
    static final int MAX_SPAN = 64;

    private final int count;
    /** Origin of the bucket array (doubles: exact for int coordinates, no emulated long). */
    private final double x0, y0;
    private final int size, nx, ny;
    /** CSR bucket lists: the boxes of bucket b are {@code items[start[b] .. start[b + 1])}. */
    private final int[] start;
    private final int[] items;
    /** Boxes returned by every query, ascending. */
    private final int[] everywhere;
    /** Query scratch: the stamp of the query that last returned a box (dedupe). */
    private final int[] seen;
    private int stamp;

    /**
     * @param minX inclusive bounds of box i at index i; arrays of at least {@code count} entries
     */
    public BoxGrid(int count, int[] minX, int[] minY, int[] maxX, int[] maxY) {
        this.count = count;
        double lx = Double.MAX_VALUE, ly = Double.MAX_VALUE, hx = -Double.MAX_VALUE, hy = -Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            if (maxX[i] < minX[i] || maxY[i] < minY[i]) {
                continue;
            }
            lx = Math.min(lx, minX[i]);
            ly = Math.min(ly, minY[i]);
            hx = Math.max(hx, maxX[i]);
            hy = Math.max(hy, maxY[i]);
        }
        if (lx > hx) {
            lx = ly = hx = hy = 0;
        }
        int s = BUCKET;
        while ((Math.floor((hx - lx) / s) + 1) * (Math.floor((hy - ly) / s) + 1) > MAX_BUCKETS) {
            s *= 2;
        }
        x0 = lx;
        y0 = ly;
        size = s;
        nx = (int) ((hx - lx) / s) + 1;
        ny = (int) ((hy - ly) / s) + 1;
        // pass 1: bucket sizes and the boxes kept apart
        int[] big = new int[count];
        int bigCount = 0;
        int[] counts = new int[nx * ny + 1];
        for (int i = 0; i < count; i++) {
            if (isEmpty(minX[i], minY[i], maxX[i], maxY[i])) {
                continue;
            }
            if (isBig(minX[i], minY[i], maxX[i], maxY[i])) {
                big[bigCount++] = i;
                continue;
            }
            int ix1 = ix(maxX[i]), iy1 = iy(maxY[i]);
            for (int iy = iy(minY[i]); iy <= iy1; iy++) {
                for (int ix = ix(minX[i]); ix <= ix1; ix++) {
                    counts[iy * nx + ix + 1]++;
                }
            }
        }
        everywhere = Arrays.copyOf(big, bigCount);
        start = new int[nx * ny + 1];
        for (int b = 0; b < nx * ny; b++) {
            start[b + 1] = start[b] + counts[b + 1];
        }
        // pass 2: fill in index order, so every bucket list is ascending
        items = new int[start[nx * ny]];
        int[] fill = new int[nx * ny];
        for (int i = 0; i < count; i++) {
            if (isEmpty(minX[i], minY[i], maxX[i], maxY[i]) || isBig(minX[i], minY[i], maxX[i], maxY[i])) {
                continue;
            }
            int ix1 = ix(maxX[i]), iy1 = iy(maxY[i]);
            for (int iy = iy(minY[i]); iy <= iy1; iy++) {
                for (int ix = ix(minX[i]); ix <= ix1; ix++) {
                    int b = iy * nx + ix;
                    items[start[b] + fill[b]++] = i;
                }
            }
        }
        seen = new int[count];
    }

    private static boolean isEmpty(int minX, int minY, int maxX, int maxY) {
        return maxX < minX || maxY < minY;
    }

    private boolean isBig(int minX, int minY, int maxX, int maxY) {
        return ((double) maxX - minX) / size >= MAX_SPAN
                || ((double) maxY - minY) / size >= MAX_SPAN;
    }

    private int ix(double x) {
        return (int) ((x - x0) / size);
    }

    private int iy(double y) {
        return (int) ((y - y0) / size);
    }

    /** @return the boxes that may contain point (x, y), ascending */
    public int[] at(int x, int y) {
        return in(x, y, x, y);
    }

    /** @return the boxes that may touch the box [minX, maxX] × [minY, maxY] (inclusive), ascending */
    public int[] in(int minX, int minY, int maxX, int maxY) {
        double ax = Math.max(minX, x0), ay = Math.max(minY, y0);
        double bx = Math.min(maxX, x0 + (double) nx * size - 1), by = Math.min(maxY, y0 + (double) ny * size - 1);
        if (ax > bx || ay > by) {
            return everywhere.length == 0 ? everywhere : Arrays.copyOf(everywhere, everywhere.length);
        }
        if (++stamp == Integer.MAX_VALUE) {
            Arrays.fill(seen, 0);
            stamp = 1;
        }
        int ix0 = ix(ax), ix1 = ix(bx), iy0 = iy(ay), iy1 = iy(by);
        int n = everywhere.length;
        for (int iy = iy0; iy <= iy1; iy++) {
            for (int ix = ix0; ix <= ix1; ix++) {
                int b = iy * nx + ix;
                n += start[b + 1] - start[b];
            }
        }
        int[] out = new int[n];
        int k = 0;
        for (int i : everywhere) {
            seen[i] = stamp;
            out[k++] = i;
        }
        boolean single = ix0 == ix1 && iy0 == iy1 && everywhere.length == 0;
        for (int iy = iy0; iy <= iy1; iy++) {
            for (int ix = ix0; ix <= ix1; ix++) {
                int b = iy * nx + ix;
                for (int e = start[b]; e < start[b + 1]; e++) {
                    int i = items[e];
                    if (seen[i] != stamp) {
                        seen[i] = stamp;
                        out[k++] = i;
                    }
                }
            }
        }
        if (k < out.length) {
            out = Arrays.copyOf(out, k);
        }
        if (!single) {
            // one bucket is ascending already; a merge of several (or of the wide boxes) is not
            Arrays.sort(out);
        }
        return out;
    }

    /** @return the number of boxes indexed */
    public int size() {
        return count;
    }
}
