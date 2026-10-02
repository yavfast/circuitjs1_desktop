package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.core.client.GWT;
import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.DocumentScope;

import java.util.ArrayList;
import java.util.List;

/**
 * The guarded execution of one agent mutation (importCircuit, applyEdits) on its target
 * document ([SP_AGA_03_04] atomicity, [SP_AGA_03_10] error reporting, [SP_AGA_04_01] agent
 * origin).
 * <ol>
 * <li>Everything runs inside {@code DocumentScope}, so a background document is changed without
 *     disturbing the visible tab (SP_AGA_03_08).</li>
 * <li>The pre-call {@link DocumentSnapshot} is captured first.</li>
 * <li>The document is marked agent origin (editor undo pushes suppressed) and the editor grid is
 *     pinned to the document's own option for the duration.</li>
 * <li>A {@link Rejected} thrown by the body is a domain rejection found while applying: the
 *     rollback hooks run, the snapshot is restored, and the result is {@code ok = false} with its
 *     issues. Any other exception restores the same way, is passed to the global uncaught-exception
 *     handler (RULE_ERR_004) and gives {@code internal_error} with the exception message.</li>
 * </ol>
 * A rejected mutation pushes no undo entry and opens no transaction. A successful one opens or
 * continues the document's agent transaction ({@link AgentTransaction}; opening pushes
 * {@link Context#snapshot}'s entry) and carries the ConnectivityDelta between the connectivity
 * before and after the body ([SP_AGA_01_08]).
 */
final class Mutation {

    /** The body of a mutation; returns the successful result. */
    interface Body {
        OperationResult apply(Context ctx);
    }

    /** What a body can use: the snapshot and the rollback hooks. */
    static final class Context {
        final CirSim sim;
        final CircuitDocument doc;
        final DocumentSnapshot snapshot;
        private final List<Runnable> rollbackHooks = new ArrayList<>();

        Context(CirSim sim, CircuitDocument doc, DocumentSnapshot snapshot) {
            this.sim = sim;
            this.doc = doc;
            this.snapshot = snapshot;
        }

        /** Registers an action run before the snapshot is restored (newest first). */
        void onRollback(Runnable hook) {
            rollbackHooks.add(hook);
        }

        /**
         * Harness-only fault injection ({@code CircuitJS1Agent.debugFailNextMutation()}): throws
         * once when armed. Bodies call it right after their first change to the document.
         */
        void checkForcedFailure(String where) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("debugFailNextMutation: forced failure " + where);
            }
        }
    }

    /** A rejection found while applying: rolled back like an exception, but not reported to the handler. */
    static final class Rejected extends RuntimeException {
        final List<Issue> issues;

        Rejected(List<Issue> issues) {
            super(issues.isEmpty() ? "rejected" : issues.get(0).getMessage());
            this.issues = issues;
        }

        Rejected(Issue issue) {
            this(single(issue));
        }

        private static List<Issue> single(Issue issue) {
            List<Issue> l = new ArrayList<>();
            l.add(issue);
            return l;
        }
    }

    /** Armed by the harness diagnostic {@code debugFailNextMutation()}; consumed once. */
    private static boolean failNext;

    private Mutation() {
    }

    /** Arms the forced failure of the next agent mutation (harness diagnostic, not a contract). */
    static void armForcedFailure() {
        failNext = true;
    }

    /** Runs {@code body} as one guarded mutation of {@code doc}. */
    static OperationResult run(CirSim sim, CircuitDocument doc, Body body) {
        // a user gesture in progress gets its own entries before and after the agent's change
        return DocumentScope.call(sim, doc, () -> doc.undoManager.splitGesture(() -> {
            // [SP_AGA_01_06] the delta compares the issue sets before and after the operation
            Connectivity.Report before = Connectivity.analyse(doc);
            Context ctx = new Context(sim, doc, DocumentSnapshot.capture(doc));
            doc.setAgentOrigin(true);
            try {
                OperationResult result = CellGeometry.withPinnedGrid(doc, () -> body.apply(ctx));
                if (result.isOk()) {
                    result.setConnectivity(Connectivity.delta(before, Connectivity.analyse(doc)));
                    // [SP_AGA_04_01] only a successful mutation opens or continues the transaction
                    // ([SP_AGA_03_04] "No side effects on rejection")
                    AgentTransaction.onMutation(sim, doc, ctx.snapshot.entry);
                }
                return result;
            } catch (Rejected r) {
                rollback(ctx);
                return OperationResult.failure(r.issues);
            } catch (Throwable t) {
                // an Error (stack overflow, assertion) is rolled back and reported the same way
                rollback(ctx);
                // Shown and logged as before (RULE_ERR_004); the caller gets the message
                GWT.reportUncaughtException(t);
                return OperationResult.failure(Issue.of(IssueCode.INTERNAL_ERROR,
                        "Internal error; the document was restored: " + t.getMessage(),
                        "Report the error; the call can be retried."));
            } finally {
                doc.setAgentOrigin(false);
            }
        }));
    }

    private static void rollback(Context ctx) {
        for (int i = ctx.rollbackHooks.size() - 1; i >= 0; i--) {
            ctx.rollbackHooks.get(i).run();
        }
        // the snapshot puts back the exact pre-call open-mark set (no pruning)
        ctx.snapshot.restore(ctx.sim, ctx.doc);
    }

    /**
     * Post-processing of a successful mutation: analyse the document now (also while it
     * free-runs, and including the node allocation, so records and the connectivity delta name
     * the nets of the changed circuit) and set its modified flag ([SP_AGA_02] "Modified flag").
     */
    static void finish(Context ctx) {
        // needAnalyze analyses at once when stopped (and repaints); a running document would
        // only flag it for its next frame — ensureAnalysed runs it here, then allocates nodes.
        ctx.sim.needAnalyze();
        ctx.doc.ensureAnalysed();
        ctx.sim.setUnsavedChanges(true);
    }
}
