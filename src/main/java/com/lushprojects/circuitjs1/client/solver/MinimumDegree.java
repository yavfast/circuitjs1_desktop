package com.lushprojects.circuitjs1.client.solver;

/**
 * [SP_SLV_01_07] Fill-reducing elimination order: minimum degree on an explicit elimination
 * graph (no quotient graph, no supervariables; AMD is the plan's backlog replacement for very large
 * systems). Ties go to the vertex that entered its degree bucket last, so the order is
 * deterministic.
 */
public final class MinimumDegree {

    private MinimumDegree() {
    }

    /**
     * Orders the vertices of the symmetrized pattern of the paired system {@code B}, where column
     * {@code k} of {@code B} is column {@code colPerm[k]} of {@code a}: vertex {@code k} stands for
     * row {@code k} and its paired column.
     *
     * @return {@code order[s]} = vertex eliminated at step {@code s}
     */
    public static int[] order(CscPattern a, int[] colPerm) {
        final int m = a.m;
        final int[] cs = a.colStart;
        final int[] ri = a.rowIndex;
        // undirected adjacency of B + B^T without self loops, deduplicated
        int[] cnt = new int[m];
        for (int k = 0; k < m; k++) {
            int c = colPerm[k];
            for (int p = cs[c]; p < cs[c + 1]; p++) {
                int i = ri[p];
                if (i != k) {
                    cnt[i]++;
                    cnt[k]++;
                }
            }
        }
        int[][] adj = new int[m][];
        int[] size = new int[m];
        for (int v = 0; v < m; v++) {
            adj[v] = new int[cnt[v]];
        }
        int[] mark = new int[m];
        for (int v = 0; v < m; v++) {
            mark[v] = -1;
        }
        for (int k = 0; k < m; k++) {
            int c = colPerm[k];
            for (int p = cs[c]; p < cs[c + 1]; p++) {
                int i = ri[p];
                if (i != k) {
                    adj[i][size[i]++] = k;
                    adj[k][size[k]++] = i;
                }
            }
        }
        for (int v = 0; v < m; v++) {
            int[] l = adj[v];
            int n = 0;
            for (int t = 0; t < size[v]; t++) {
                int u = l[t];
                if (mark[u] != v) {
                    mark[u] = v;
                    l[n++] = u;
                }
            }
            size[v] = n;
        }
        // degree buckets as doubly linked lists
        int[] head = new int[m + 1];
        int[] next = new int[m];
        int[] prev = new int[m];
        int[] deg = new int[m];
        for (int d = 0; d <= m; d++) {
            head[d] = -1;
        }
        for (int v = 0; v < m; v++) {
            deg[v] = size[v];
            push(v, head, next, prev, deg);
        }
        boolean[] done = new boolean[m];
        int[] order = new int[m];
        int[] nb = new int[m];
        int stamp = m;
        for (int v = 0; v < m; v++) {
            mark[v] = -1;
        }
        int minD = 0;
        for (int s = 0; s < m; s++) {
            while (head[minD] < 0) {
                minD++;
            }
            int v = head[minD];
            unlink(v, head, next, prev, deg);
            done[v] = true;
            order[s] = v;
            int nn = size[v];
            System.arraycopy(adj[v], 0, nb, 0, nn);
            adj[v] = null;
            // each neighbour u: adj(u) := (adj(u) \ {v}) ∪ (nb \ {u})
            for (int x = 0; x < nn; x++) {
                int u = nb[x];
                stamp++;
                int[] l = adj[u];
                int n = 0;
                for (int t = 0; t < size[u]; t++) {
                    int w = l[t];
                    if (w != v) {
                        mark[w] = stamp;
                        l[n++] = w;
                    }
                }
                int extra = 0;
                for (int y = 0; y < nn; y++) {
                    int w = nb[y];
                    if (w != u && mark[w] != stamp) {
                        extra++;
                    }
                }
                if (n + extra > l.length) {
                    int[] g = new int[Math.max(n + extra, 2 * l.length)];
                    System.arraycopy(l, 0, g, 0, n);
                    l = g;
                    adj[u] = l;
                }
                for (int y = 0; y < nn; y++) {
                    int w = nb[y];
                    if (w != u && mark[w] != stamp) {
                        mark[w] = stamp;
                        l[n++] = w;
                    }
                }
                size[u] = n;
                if (deg[u] != n) {
                    unlink(u, head, next, prev, deg);
                    deg[u] = n;
                    push(u, head, next, prev, deg);
                }
                if (n < minD) {
                    minD = n;
                }
            }
        }
        return order;
    }

    private static void push(int v, int[] head, int[] next, int[] prev, int[] deg) {
        int d = deg[v];
        int h = head[d];
        next[v] = h;
        prev[v] = -1;
        if (h >= 0) {
            prev[h] = v;
        }
        head[d] = v;
    }

    private static void unlink(int v, int[] head, int[] next, int[] prev, int[] deg) {
        int p = prev[v];
        int n = next[v];
        if (p >= 0) {
            next[p] = n;
        } else {
            head[deg[v]] = n;
        }
        if (n >= 0) {
            prev[n] = p;
        }
    }
}
