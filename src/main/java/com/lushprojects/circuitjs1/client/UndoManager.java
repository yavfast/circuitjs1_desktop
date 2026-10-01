package com.lushprojects.circuitjs1.client;

import com.lushprojects.circuitjs1.client.io.CircuitFormatRegistry;
import java.util.Arrays;
import java.util.Vector;

public class UndoManager extends BaseCirSimDelegate {

    /**
     * One undo/redo snapshot: the circuit text plus the undo entry extension of [SP_AGA_01_10].
     * Every field describes the state the entry restores and is captured from the current
     * document when the entry is created. Implemented so far: {@code elementIds}, {@code openMarks}.
     * Still to come (PL_AGA Phase 6): {@code viewTransform} (replacing the session renderer
     * transform trio below), and the label fields {@code comment}, {@code checkpointId},
     * {@code auto}, which the entry created by an undo/redo copies from the popped entry.
     * <p>
     * Public so that an agent snapshot ({@code agent/DocumentSnapshot}) can hold one: the
     * pre-mutation state of an agent transaction is pushed as such an entry (Phase 6).
     */
    public class UndoItem {
        public String dump;
        public double scale;
        public double transform4;
        public double transform5;
        /** IDs of the snapshot's elements in snapshot (dump) order ([SP_AGA_01_10] elementIds). */
        public final String[] elementIds;
        /** Open marks of the snapshot ([SP_AGA_01_10] openMarks, SP_AGA_01_12). */
        public final String[] openMarks;

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
            CircuitRenderer renderer = renderer();
            scale = renderer.transform[0];
            transform4 = renderer.transform[4];
            transform5 = renderer.transform[5];
        }

        /** True when restoring this entry would recreate {@code other}'s circuit and IDs. */
        boolean sameContent(UndoItem other) {
            return dump.equals(other.dump) && Arrays.equals(elementIds, other.elementIds)
                    && Arrays.equals(openMarks, other.openMarks);
        }
    }

    /**
     * Maximum number of undo snapshots kept per document. Each snapshot is a full circuit
     * dump, so the history is capped and the oldest snapshots are dropped first.
     */
    static final int MAX_UNDO_DEPTH = 150;

    Vector<UndoItem> undoStack;
    Vector<UndoItem> redoStack;

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
     * text, element IDs, open marks and view transform. The stacks are not touched.
     */
    public void restoreState(UndoItem item) {
        loadUndoItem(item);
    }

    void pushUndo() {
        // [SP_AGA_04_01] "Agent origin": editor paths reused inside an agent mutation do not push;
        // the agent transaction holds the pre-mutation entry.
        CircuitDocument document = getActiveDocument();
        if (document != null && document.isAgentOrigin()) {
            return;
        }
        redoStack.removeAllElements();
        UndoItem item = new UndoItem();
        if (!undoStack.isEmpty() && item.sameContent(undoStack.lastElement()))
            return;
        undoStack.add(item);
        trimToMaxDepth(undoStack);
    }

    void doUndo() {
        if (undoStack.isEmpty())
            return;
        redoStack.add(new UndoItem());
        UndoItem ui = undoStack.remove(undoStack.size() - 1);
        loadUndoItem(ui);
    }

    void doRedo() {
        if (redoStack.isEmpty())
            return;
        undoStack.add(new UndoItem());
        trimToMaxDepth(undoStack);
        UndoItem ui = redoStack.remove(redoStack.size() - 1);
        loadUndoItem(ui);
    }

    /** Drops the oldest snapshots (bottom of the stack) until at most MAX_UNDO_DEPTH remain. */
    private static void trimToMaxDepth(Vector<UndoItem> stack) {
        int excess = stack.size() - MAX_UNDO_DEPTH;
        if (excess > 0) {
            stack.subList(0, excess).clear();
        }
    }

    void loadUndoItem(UndoItem ui) {
        // [SP_AGA_03_02] "Undo/redo restore": the import keeps the content lifetime (no counter
        // reset) and gives element i the snapshot's elementIds[i], raising the counters; a count
        // mismatch regenerates the IDs in order with an ids_regenerated warning in the session
        // log (Phase 6: also returned in the issues of the agent undo/redo contract).
        CircuitDocument document = getActiveDocument();
        document.beginElementIdRestore(ui.elementIds);
        try {
            document.circuitLoader.readCircuit(ui.dump, CircuitConst.RC_NO_CENTER);
        } finally {
            document.endElementIdRestore();
        }
        document.setOpenMarks(ui.openMarks);
        CircuitRenderer renderer = renderer();
        renderer.transform[0] = renderer.transform[3] = ui.scale;
        renderer.transform[4] = ui.transform4;
        renderer.transform[5] = ui.transform5;
    }

}
