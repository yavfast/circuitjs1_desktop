package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.core.client.Duration;
import com.google.gwt.user.client.Timer;
import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.CircuitDocument;

/**
 * [SP_AGA_03_08] R1 timing of the sliced asynchronous contracts ({@code run}, {@code render}).
 * <ul>
 * <li><b>Yield.</b> {@link #afterVisibleFrame} queues the next slice in one session-wide queue that
 *     releases at most one slice per active-tab frame (PL_AGA_DEC_01 condition 4, also across
 *     concurrent operations), round robin, with a fallback timer for a loop that stops meanwhile.</li>
 * <li><b>Probe.</b> {@link #begin}/{@link #end} bracket every slice (the document scope and the
 *     work inside it). They notify the harness diagnostic {@code CircuitJS1Agent.debugSetSliceProbe},
 *     which samples the R1 state at every slice boundary and times the slices; not a contract.</li>
 * </ul>
 */
final class Slices {

    /** Nominal slice length (ms), measured from before the scope entry ([SP_AGA_03_08] R1 timing). */
    static final int SLICE_MS = 20;
    /**
     * Part of the slice kept free for the unbind, timer granularity and engine pauses (ms). PL_AGA
     * Phase 8 measured 23–25 ms slices with 3 ms in the prototype and single 21–26 ms outliers
     * with 3 ms here; 5 ms gave 14–18 ms slices.
     */
    static final int SLICE_MARGIN_MS = 5;

    /** Longest wait for the visible tab's next frame before the next slice runs anyway (ms). */
    static final int FRAME_WAIT_FALLBACK_MS = 50;

    /** Observer of slice boundaries (harness diagnostic). */
    interface Probe {
        /**
         * @param op    contract name ({@code run}, {@code render})
         * @param doc   handle of the target document
         * @param phase {@code "begin"} before the scope is entered, {@code "end"} after it was left
         */
        void onSlice(String op, String doc, String phase);
    }

    private static Probe probe;

    private Slices() {
    }

    /**
     * Extra margin of a render slice (ms): one element draw is not interrupted, and the first draw
     * of an element class in a session can take several milliseconds (PL_AGA Phase 8 measured up to
     * 19 ms for the first JK flip-flop, headless); render throughput does not matter for R1.
     */
    static final int RENDER_EXTRA_MARGIN_MS = 5;

    /** @return the work deadline of a slice that starts now ({@code Duration.currentTimeMillis()} scale) */
    static double deadline() {
        return Duration.currentTimeMillis() + SLICE_MS - SLICE_MARGIN_MS;
    }

    /** @return the work deadline of a render slice that starts now (see {@link #RENDER_EXTRA_MARGIN_MS}) */
    static double renderDeadline() {
        return deadline() - RENDER_EXTRA_MARGIN_MS;
    }

    /** Sets the slice observer (null removes it). */
    static void setProbe(Probe p) {
        probe = p;
    }

    /** A slice of {@code op} on {@code doc} starts (before its document scope is entered). */
    static void begin(String op, CircuitDocument doc) {
        sliceRan = true;
        if (probe != null) {
            probe.onSlice(op, DocumentHandles.of(doc), "begin");
        }
    }

    /** A slice of {@code op} on {@code doc} ended (its document scope was left). */
    static void end(String op, CircuitDocument doc) {
        if (probe != null) {
            probe.onSlice(op, DocumentHandles.of(doc), "end");
        }
    }

    // ---------------------------------------------------------------- session-wide slice queue

    /** One waiting slice (or the final unit of a render): its target and its continuation. */
    private static final class Pending {
        final CircuitDocument target;
        final Runnable next;

        Pending(CircuitDocument target, Runnable next) {
            this.target = target;
            this.next = next;
        }
    }

    /** Waiting slices of every operation of the session, in arrival order (round robin). */
    private static final java.util.LinkedList<Pending> queue = new java.util.LinkedList<>();
    private static CirSim queueSim;
    /** True while a release (a frame wait or a zero-delay timer) is armed. */
    private static boolean armed;
    /** True when the continuation just run was a slice ({@link #begin} was called). */
    private static boolean sliceRan;

    /**
     * Queues {@code next} (one slice of an operation on {@code target}) in the session-wide slice
     * queue ([SP_AGA_03_08] R1, PL_AGA_DEC_01 condition 4). The queue releases at most one slice
     * per release, each on a task of its own; when the visible tab free-runs (and is not itself
     * busy with a run), a release waits for one of its frames (at most
     * {@link #FRAME_WAIT_FALLBACK_MS}), so one active-tab frame passes between any two slices,
     * also of different concurrent operations. An operation that queues its next slice from its
     * slice goes to the back of the queue, so concurrent operations take turns. A continuation
     * that turns out not to be a slice (its operation ended or its document was closed: it only
     * completes) does not use up the release; the next one runs at once. {@code next} must check
     * itself whether it is still wanted.
     */
    static void afterVisibleFrame(CirSim sim, CircuitDocument target, Runnable next) {
        queueSim = sim;
        queue.addLast(new Pending(target, next));
        arm();
    }

    private static void arm() {
        if (armed || queue.isEmpty()) {
            return;
        }
        armed = true;
        final boolean[] fired = { false };
        final Timer task = new Timer() {
            @Override
            public void run() {
                release();
            }
        };
        CircuitDocument visible = queueSim.getActiveDocument();
        if (!visible.simulationLoop.isScheduled() || visible.isAgentBusy()) {
            // no free-run frame to wait for: the visible tab is stopped or owned by a run
            task.schedule(0);
            return;
        }
        final Timer fallback = new Timer() {
            @Override
            public void run() {
                if (!fired[0]) {
                    fired[0] = true;
                    task.schedule(0);
                }
            }
        };
        visible.simulationLoop.runAfterNextFrame(() -> {
            if (!fired[0]) {
                fired[0] = true;
                fallback.cancel();
                // a task boundary after the frame, so the browser can paint and take input
                task.schedule(0);
            }
        });
        fallback.schedule(FRAME_WAIT_FALLBACK_MS);
    }

    /** Runs the next waiting slice (and before it any continuation that is not a slice). */
    private static void release() {
        armed = false;
        try {
            while (!queue.isEmpty()) {
                Pending p = queue.removeFirst();
                sliceRan = false;
                try {
                    p.next.run();
                } catch (Throwable t) {
                    // a continuation guards its own work; never let one starve the others
                    com.google.gwt.core.client.GWT.reportUncaughtException(t);
                }
                if (sliceRan) {
                    break;
                }
            }
        } finally {
            arm();
        }
    }

    /** @return the number of waiting slices (harness diagnostics) */
    static int queued() {
        return queue.size();
    }
}
