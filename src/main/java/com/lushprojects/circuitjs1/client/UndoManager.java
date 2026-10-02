package com.lushprojects.circuitjs1.client;

import com.google.gwt.user.client.Timer;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.io.CircuitFormatRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.Vector;
import java.util.function.Supplier;

/**
 * Document-scoped undo/redo history (full-snapshot, text-dump based) and the state of the
 * document's agent transaction ([SP_AGA_01_10], [SP_AGA_04_01]).
 * <p>
 * The transaction lives here, not in {@code client/agent/}, because every seal trigger except
 * {@code checkpoint} is a user path at the client root (a user undo push, a user undo/redo, a
 * user save, a user content replacement) or the idle timer, and the transaction's entry is
 * always the newest entry of this manager's undo stack. The agent package opens and continues
 * the transaction ({@code agent/AgentTransaction}) and names checkpoints ({@code agent/HistoryOps}).
 */
public class UndoManager extends BaseCirSimDelegate {

    /** [SP_AGA_04_01] Comment of an automatically sealed agent transaction. */
    public static final String AUTO_SEAL_COMMENT = "agent edits (auto)";

    /** [SP_AGA_04_01] Default idle time after which an open transaction is sealed (300 s). */
    static final int DEFAULT_IDLE_SEAL_MS = 300000;

    /**
     * Idle seal time of every document (session-wide). Changed only by the live harness
     * diagnostic {@code CircuitJS1Agent.debugSetIdleSealMs(ms)}; 0 or less restores the default.
     */
    private static int idleSealMs = DEFAULT_IDLE_SEAL_MS;

    /**
     * One undo/redo snapshot: the circuit text plus the undo entry extension of [SP_AGA_01_10].
     * The content fields ({@code dump}, {@code viewTransform}, {@code elementIds},
     * {@code openMarks}, the element endpoints) describe the state the entry restores and are
     * captured from the document when the entry is created. The label fields ({@code comment},
     * {@code checkpointId}, {@code auto}) name the change that followed that state: they are set
     * when an agent transaction is sealed, and the entry created by an undo/redo copies them from
     * the popped entry.
     * <p>
     * Public so that an agent snapshot ({@code agent/DocumentSnapshot}) can hold one: the
     * pre-mutation state of an agent transaction is pushed as such an entry.
     */
    public class UndoItem {
        public final String dump;
        /**
         * [SP_AGA_01_10] viewTransform: the snapshot document's own view scale and offset
         * {@code {scale, tx, ty}}; scale 0 when the document had no view yet.
         */
        public final double[] viewTransform;
        /** IDs of the snapshot's elements in snapshot (dump) order ([SP_AGA_01_10] elementIds). */
        public final String[] elementIds;
        /** Open marks of the snapshot ([SP_AGA_01_10] openMarks, SP_AGA_01_12). */
        public final String[] openMarks;
        /**
         * The two defining points {@code x1, y1, x2, y2} of element i (aligned with
         * {@link #elementIds}). A text reload rewrites some of them (an axis-aligned
         * transformer's text constructor synthesizes the diagonal corner); the restore puts back
         * every point the reload changed.
         */
        private final int[][] endpoints;
        /** Checkpoint comment; null for user edits ([SP_AGA_01_10] comment). */
        String comment;
        /** Present on sealed agent entries ([SP_AGA_01_10] checkpointId). */
        String checkpointId;
        /** True when sealed automatically ([SP_AGA_01_10] auto). */
        boolean auto;

        /**
         * Captures the current state of this undo manager's own document: dump and IDs both come
         * from {@code getActiveDocument()} of the delegate, which resolves to that document even
         * when another document is the session's active one.
         */
        UndoItem() {
            CircuitDocument document = getActiveDocument();
            dump = CircuitFormatRegistry.getDefault().createExporter().export(document);
            elementIds = document.getDumpedElementIds();
            openMarks = document.getOpenMarks();
            // The document's own view: the renderer holds it while the document is bound,
            // its saved UI state otherwise.
            double[] t = cirSim.getActiveDocument() == document ? renderer().transform : document.transform;
            viewTransform = new double[] { t[0], t[4], t[5] };
            endpoints = new int[elementIds.length][];
            int i = 0;
            for (CircuitElm elm : document.simulator.elmList) {
                if (elm.hasDumpLine() && i < endpoints.length) {
                    endpoints[i++] = new int[] { elm.getX(), elm.getY(), elm.getX2(), elm.getY2() };
                }
            }
        }

        /** A copy of {@code content}'s state carrying {@code labels}' comment, checkpoint ID and auto flag. */
        private UndoItem(UndoItem content, UndoItem labels) {
            dump = content.dump;
            viewTransform = content.viewTransform;
            elementIds = content.elementIds;
            openMarks = content.openMarks;
            endpoints = content.endpoints;
            copyLabels(labels);
        }

        private void copyLabels(UndoItem labels) {
            comment = labels.comment;
            checkpointId = labels.checkpointId;
            auto = labels.auto;
        }

        /** True when restoring this entry would recreate {@code other}'s circuit, IDs and open marks. */
        public boolean sameContent(UndoItem other) {
            return dump.equals(other.dump) && Arrays.equals(elementIds, other.elementIds)
                    && Arrays.equals(openMarks, other.openMarks);
        }

        public String getComment() {
            return comment;
        }

        public String getCheckpointId() {
            return checkpointId;
        }

        public boolean isAuto() {
            return auto;
        }
    }

    /**
     * Maximum number of undo snapshots kept per document. Each snapshot is a full circuit
     * dump, so the history is capped and the oldest snapshots are dropped first.
     */
    static final int MAX_UNDO_DEPTH = 150;

    Vector<UndoItem> undoStack;
    Vector<UndoItem> redoStack;

    // [SP_AGA_01_10] AgentTransaction (at most one per document, in memory)
    private boolean transactionOpen;
    /** Successful agent mutations since the transaction opened. */
    private int pendingEdits;
    /** Wall-clock time (ms) of the last agent mutation. */
    private double lastAgentMutationAt;
    /** Last checkpoint number issued in this document; IDs {@code cp<n>} are never reused. */
    private int checkpointCounter;
    /** Seals the open transaction after the idle time ([SP_AGA_04_01]); created on first use. */
    private Timer idleTimer;

    public UndoManager(BaseCirSim cirSim, CircuitDocument circuitDocument) {
        super(cirSim, circuitDocument);
        undoStack = new Vector<>();
        redoStack = new Vector<>();
    }

    void clearStacks() {
        undoStack.removeAllElements();
        redoStack.removeAllElements();
    }

    /**
     * Reset undo/redo history for the current document and seed it with the current circuit.
     * Intended for use after opening/loading a circuit so Undo applies to edits within the
     * loaded circuit (and doesn't jump back to a previous/empty pre-load state).
     */
    void resetAndSeedFromCurrentCircuit() {
        // a user load replaces the content: the open transaction is sealed first ([SP_AGA_04_01])
        sealTransaction();
        tentative = null;
        clearStacks();
        pushUndo();
    }

    /** @return the number of undo entries (diagnostics) */
    public int getUndoDepth() {
        return undoStack.size();
    }

    /** @return the number of redo entries (diagnostics) */
    public int getRedoDepth() {
        return redoStack.size();
    }

    boolean hasUndoStack() {
        return !undoStack.isEmpty();
    }

    boolean hasRedoStack() {
        return !redoStack.isEmpty();
    }

    /** @return undo entry {@code position} (0 = the entry the next undo applies) */
    public UndoItem getUndoEntry(int position) {
        return undoStack.get(undoStack.size() - 1 - position);
    }

    /** @return redo entry {@code position} (0 = the entry the next redo applies) */
    public UndoItem getRedoEntry(int position) {
        return redoStack.get(redoStack.size() - 1 - position);
    }

    /** @return the position of the undo entry sealed as {@code checkpointId}, or -1 */
    public int findCheckpoint(String checkpointId) {
        for (int p = 0; p < undoStack.size(); p++) {
            if (checkpointId.equals(getUndoEntry(p).checkpointId)) {
                return p;
            }
        }
        return -1;
    }

    /** @return the comment of the entry the next undo applies, or null ([SP_AGA_04_01] menu labels) */
    String getUndoComment() {
        return undoStack.isEmpty() ? null : undoStack.lastElement().comment;
    }

    /** @return the comment of the entry the next redo applies, or null */
    String getRedoComment() {
        return redoStack.isEmpty() ? null : redoStack.lastElement().comment;
    }

    /**
     * Captures the current state of this manager's document as an entry that is not pushed
     * (an agent mutation's pre-call snapshot, [SP_AGA_03_04]). Call it while the document is
     * bound, so the options line and the view transform are the document's own.
     */
    public UndoItem captureState() {
        return new UndoItem();
    }

    /**
     * Puts the document back into the state of {@code item} exactly as an undo does: circuit
     * text, element IDs, open marks, view transform and element endpoints. The stacks are not
     * touched.
     */
    public void restoreState(UndoItem item) {
        loadUndoItem(item);
    }

    void pushUndo() {
        // [SP_AGA_04_01] "Agent origin": editor paths reused inside an agent mutation do not push
        // (and never seal); the agent transaction holds the pre-mutation entry.
        CircuitDocument document = getActiveDocument();
        if (document != null && document.isAgentOrigin()) {
            return;
        }
        // a gesture's tentative entry goes first (it holds the state before the gesture)
        resolveTentativePush();
        pushUserEntry(new UndoItem());
    }

    /**
     * Pushes a user entry. A user edit never merges into an agent entry: an open transaction is
     * sealed first ([SP_AGA_04_01]). When the newest entry is a sealed agent entry equal to
     * {@code item} (a transaction that changed nothing in net, sealed now or earlier by the idle
     * timer, a save, a clear or a user load), it is dropped, so the user edit always gets an entry
     * of its own and Ctrl+Z reverts the user edit under the user's own label. The dropped entry
     * restores exactly the state {@code item} restores, so no restorable state is lost.
     */
    private void pushUserEntry(UndoItem item) {
        sealTransaction();
        if (!undoStack.isEmpty()) {
            UndoItem newest = undoStack.lastElement();
            if (newest.checkpointId != null && newest.sameContent(item)) {
                undoStack.remove(undoStack.size() - 1);
            }
        }
        redoStack.removeAllElements();
        if (!undoStack.isEmpty() && item.sameContent(undoStack.lastElement()))
            return;
        undoStack.add(item);
        trimToMaxDepth(undoStack);
    }

    /**
     * The state before a mouse gesture, held back while an agent transaction is open
     * ({@link #pushUndoForGesture}); null when none is pending.
     */
    private UndoItem tentative;

    /**
     * True from a mouse press ({@link #pushUndoForGesture}) to its mouse-up ({@link #endGesture}):
     * the user holds the mouse button and the gesture may still change the circuit.
     */
    private boolean gestureActive;

    /**
     * The undo push of a mouse press ({@code CircuitEditor.onMouseDown}): every press pushes the
     * state before the gesture, also for a select click, a pan or a rubber band that change
     * nothing. While an agent transaction is open the push is held back as tentative, so a press
     * that changes nothing does not seal the transaction; {@link #resolveTentativePush} decides
     * at the end of the gesture. Without a transaction this is {@link #pushUndo}.
     */
    void pushUndoForGesture() {
        CircuitDocument document = getActiveDocument();
        if (document != null && document.isAgentOrigin()) {
            return;
        }
        resolveTentativePush();
        gestureActive = true;
        if (transactionOpen) {
            tentative = new UndoItem();
        } else {
            pushUndo();
        }
    }

    /** The mouse gesture is over (mouse-up): its tentative entry is resolved. */
    void endGesture() {
        resolveTentativePush();
        gestureActive = false;
    }

    /**
     * Runs an agent operation of this document (bound) that may change the circuit or its
     * history ([SP_AGA_04_01] "A user edit never merges into an agent entry"). When the user holds
     * the mouse button, the gesture is split around the operation: its tentative entry is
     * resolved first (the part of the gesture done so far gets its own entry when it changed the
     * circuit), and afterwards — also when the operation fails or throws — the rest of the gesture
     * is armed as a new tentative entry holding the post-operation state, so the mouse-up gives
     * that part its own entry instead of leaving it inside the agent's transaction.
     * <p>
     * The re-arm is skipped when the operation left nothing for the rest of the gesture to merge
     * into: no transaction is open, the state equals the state before the operation (a rejected
     * edit, a {@code noChanges} checkpoint) and the newest entry is a user entry (the gesture's
     * own). The gesture then stays one undo step.
     */
    public <T> T splitGesture(Supplier<T> op) {
        if (!gestureActive) {
            return op.get();
        }
        resolveTentativePush();
        UndoItem before = new UndoItem();
        try {
            return op.get();
        } finally {
            // the operation may have closed the document's gesture (discardTransaction)
            if (gestureActive) {
                UndoItem after = new UndoItem();
                boolean userEntryOnTop = !undoStack.isEmpty() && undoStack.lastElement().checkpointId == null;
                if (transactionOpen || !after.sameContent(before) || !userEntryOnTop) {
                    tentative = after;
                }
            }
        }
    }

    /** @return true while the user holds the mouse button in a gesture of this document */
    public boolean isGestureActive() {
        return gestureActive;
    }

    /**
     * Ends a tentative gesture push: when the circuit (text, IDs or open marks) changed since the
     * press, the open transaction is sealed and the pre-gesture state is pushed above the agent
     * entry (order: agent entry, then the user's entry); when nothing changed the entry is
     * dropped and the transaction stays open. Called at mouse-up, before any other push, and
     * around agent operations of the document ({@link #splitGesture}).
     */
    private void resolveTentativePush() {
        UndoItem t = tentative;
        if (t == null) {
            return;
        }
        tentative = null;
        if (new UndoItem().sameContent(t)) {
            return;
        }
        pushUserEntry(t);
    }

    /** User undo (menu, Ctrl+Z): seals an open agent transaction first ([SP_AGA_04_01]). */
    void doUndo() {
        resolveTentativePush();
        sealTransaction();
        if (undoStack.isEmpty())
            return;
        undo(1);
    }

    /** User redo (menu, Ctrl+Y): seals an open agent transaction first ([SP_AGA_04_01]). */
    void doRedo() {
        resolveTentativePush();
        sealTransaction();
        if (redoStack.isEmpty())
            return;
        redo(1);
    }

    /**
     * Pops {@code steps} undo entries (there must be at least that many) and restores the last
     * one popped. Each popped entry E moves to the redo stack as the state being left, carrying
     * E's comment, checkpoint ID and auto flag ([SP_AGA_01_10] move rule); only the final state
     * is loaded. Does not seal: the callers do.
     *
     * @return the {@code ids_regenerated} warning of the restore, or null
     */
    public String undo(int steps) {
        return move(undoStack, redoStack, steps, false);
    }

    /** Symmetric to {@link #undo}: pops {@code steps} redo entries and restores the last one. */
    public String redo(int steps) {
        return move(redoStack, undoStack, steps, true);
    }

    /**
     * Moves {@code steps} entries from {@code from} to {@code to} and loads the last one. When
     * the load throws, both stacks and the document's state are put back as they were and the
     * exception propagates.
     */
    private String move(Vector<UndoItem> from, Vector<UndoItem> to, int steps, boolean trimTo) {
        UndoItem current = new UndoItem();
        Vector<UndoItem> fromBefore = new Vector<>(from);
        Vector<UndoItem> toBefore = new Vector<>(to);
        UndoItem leaving = current;
        UndoItem target = null;
        for (int i = 0; i < steps; i++) {
            target = from.remove(from.size() - 1);
            to.add(new UndoItem(leaving, target));
            leaving = target;
        }
        if (trimTo) {
            trimToMaxDepth(to);
        }
        try {
            if (failNextLoad) {
                failNextLoad = false;
                loadUndoItem(target);
                throw new IllegalStateException("debugFailNextUndoLoad: forced failure after the undo/redo load");
            }
            return loadUndoItem(target);
        } catch (RuntimeException e) {
            from.clear();
            from.addAll(fromBefore);
            to.clear();
            to.addAll(toBefore);
            try {
                loadUndoItem(current);
            } catch (RuntimeException rollbackFailure) {
                // the original failure propagates; the failed reload is logged and attached to it
                cirSim.logManager.logError("Undo/redo rollback: reloading the state before the move failed: "
                        + rollbackFailure.getMessage());
                e.addSuppressed(rollbackFailure);
            }
            throw e;
        }
    }

    /** Armed by the harness diagnostic {@code CircuitJS1Agent.debugFailNextUndoLoad()}; consumed once. */
    private static boolean failNextLoad;

    /**
     * Harness diagnostic: the next undo/redo load (user or agent) throws after loading, to
     * exercise the stack and state restore of {@link #move}.
     */
    public static void armFailNextLoad() {
        failNextLoad = true;
    }

    /** Drops the oldest snapshots (bottom of the stack) until at most MAX_UNDO_DEPTH remain. */
    private static void trimToMaxDepth(Vector<UndoItem> stack) {
        int excess = stack.size() - MAX_UNDO_DEPTH;
        if (excess > 0) {
            stack.subList(0, excess).clear();
        }
    }

    /** @return the {@code ids_regenerated} warning of the restore, or null */
    String loadUndoItem(UndoItem ui) {
        // [SP_AGA_03_02] "Undo/redo restore": the import keeps the content lifetime (no counter
        // reset) and gives element i the snapshot's elementIds[i], raising the counters; a count
        // mismatch regenerates the IDs in order with an ids_regenerated warning in the session
        // log, returned here for the issues of the agent undo/redo contract.
        CircuitDocument document = getActiveDocument();
        document.beginElementIdRestore(ui.elementIds);
        try {
            document.circuitLoader.readCircuit(ui.dump, CircuitConst.RC_NO_CENTER);
        } finally {
            document.endElementIdRestore();
        }
        document.setOpenMarks(ui.openMarks);
        restoreEndpoints(document, ui);
        if (ui.viewTransform[0] != 0) {
            double[] t = cirSim.getActiveDocument() == document ? renderer().transform : document.transform;
            t[0] = t[3] = ui.viewTransform[0];
            t[4] = ui.viewTransform[1];
            t[5] = ui.viewTransform[2];
        }
        return document.takeIdRestoreWarning();
    }

    /**
     * Puts back every element endpoint the text reload changed, when the reload recreated the
     * snapshot's elements one to one (same count and IDs).
     */
    private static void restoreEndpoints(CircuitDocument document, UndoItem ui) {
        List<CircuitElm> elms = document.simulator.elmList;
        if (elms.size() != ui.elementIds.length) {
            return;
        }
        for (int i = 0; i < elms.size(); i++) {
            CircuitElm elm = elms.get(i);
            int[] p = ui.endpoints[i];
            if (p == null || !ui.elementIds[i].equals(elm.getElementId())) {
                continue;
            }
            if (p[0] != elm.getX() || p[1] != elm.getY() || p[2] != elm.getX2() || p[3] != elm.getY2()) {
                elm.setEndpoints(p[0], p[1], p[2], p[3]);
                elm.setPoints();
            }
        }
    }

    // ---------------------------------------------------------------- agent transaction

    /** @return true while an agent transaction of this document is open ([SP_AGA_04_01]) */
    public boolean isTransactionOpen() {
        return transactionOpen;
    }

    /** @return successful agent mutations since the open transaction opened (0 when none is open) */
    public int getPendingEdits() {
        return transactionOpen ? pendingEdits : 0;
    }

    /**
     * [SP_AGA_04_01] A successful agent mutation of this document: opens the transaction or
     * continues it. Opening reuses the newest undo entry when it equals the pre-mutation state
     * and carries no comment (for example the entry seeded after a load); otherwise
     * {@code preMutation} is pushed. Opening clears the redo stack. Continuing adds no entry.
     *
     * @param preMutation the state captured before the mutation ({@link #captureState})
     */
    public void noteAgentMutation(UndoItem preMutation) {
        if (transactionOpen) {
            pendingEdits++;
        } else {
            UndoItem newest = undoStack.isEmpty() ? null : undoStack.lastElement();
            if (newest == null || newest.comment != null || !newest.sameContent(preMutation)) {
                undoStack.add(preMutation);
                trimToMaxDepth(undoStack);
            }
            redoStack.removeAllElements();
            transactionOpen = true;
            pendingEdits = 1;
        }
        lastAgentMutationAt = System.currentTimeMillis();
        scheduleIdleSeal(idleSealMs);
    }

    /**
     * Seals the open transaction automatically ({@code "agent edits (auto)"}, auto = true) —
     * the seal triggers of [SP_AGA_04_01] other than {@code checkpoint}. Does nothing when no
     * transaction is open.
     *
     * @return true when a transaction was sealed
     */
    public boolean sealTransaction() {
        return seal(AUTO_SEAL_COMMENT, true) != null;
    }

    /**
     * Seals the open transaction: its entry (the newest undo entry) gets {@code comment}, the
     * next checkpoint ID and {@code auto}.
     *
     * @return the checkpoint ID, or null when no transaction was open
     */
    public String seal(String comment, boolean auto) {
        if (!transactionOpen) {
            return null;
        }
        closeTransaction();
        if (undoStack.isEmpty()) {
            return null;
        }
        UndoItem entry = undoStack.lastElement();
        entry.comment = comment;
        entry.checkpointId = "cp" + (++checkpointCounter);
        entry.auto = auto;
        return entry.checkpointId;
    }

    /**
     * [SP_AGA_02_12] {@code checkpoint} found nothing to keep: the transaction's entry equals the
     * current state, so it is removed and the transaction closes without a checkpoint.
     */
    public void dropTransactionEntry() {
        if (!transactionOpen) {
            return;
        }
        closeTransaction();
        if (!undoStack.isEmpty()) {
            undoStack.remove(undoStack.size() - 1);
        }
    }

    /** Document closed: the open transaction is discarded with it ([SP_AGA_04_01]). */
    void discardTransaction() {
        tentative = null;
        gestureActive = false;
        closeTransaction();
    }

    private void closeTransaction() {
        transactionOpen = false;
        pendingEdits = 0;
        if (idleTimer != null) {
            idleTimer.cancel();
        }
    }

    private void scheduleIdleSeal(int delayMs) {
        if (idleTimer == null) {
            idleTimer = new Timer() {
                @Override
                public void run() {
                    onIdleTimer();
                }
            };
        }
        idleTimer.schedule(Math.max(1, delayMs));
    }

    /** [SP_AGA_04_01] No agent mutation for the idle time (wall clock): seal automatically. */
    private void onIdleTimer() {
        if (!transactionOpen) {
            return;
        }
        double idle = System.currentTimeMillis() - lastAgentMutationAt;
        if (idle < idleSealMs) {
            // a timer fired early (or the idle time was raised meanwhile): wait for the rest
            scheduleIdleSeal((int) Math.ceil(idleSealMs - idle));
            return;
        }
        sealTransaction();
        // the menu labels show the new comment when this document is the visible one
        if (cirSim.menuManager != null && cirSim.menuManager.undoItem != null) {
            cirSim.enableUndoRedo();
        }
    }

    /**
     * Harness diagnostic ({@code CircuitJS1Agent.debugSetIdleSealMs}): sets the idle seal time of
     * every document for transactions that open or continue afterwards; 0 or less restores 300 s.
     */
    public static void setIdleSealMs(int ms) {
        idleSealMs = ms > 0 ? ms : DEFAULT_IDLE_SEAL_MS;
    }
}
