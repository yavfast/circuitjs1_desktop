package com.lushprojects.circuitjs1.client;

import java.util.Vector;

public class UndoManager extends BaseCirSimDelegate {

    class UndoItem {
        public String dump;
        public double scale;
        public double transform4;
        public double transform5;

        UndoItem(String d) {
            dump = d;
            CircuitRenderer renderer = renderer();
            scale = renderer.transform[0];
            transform4 = renderer.transform[4];
            transform5 = renderer.transform[5];
        }
    }

    /**
     * Maximum number of undo snapshots kept per document. Each snapshot is a full circuit
     * dump, so the history is capped and the oldest snapshots are dropped first.
     */
    static final int MAX_UNDO_DEPTH = 150;

    Vector<UndoItem> undoStack;
    Vector<UndoItem> redoStack;

    String recovery;

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

    boolean hasUndoStack() {
        return !undoStack.isEmpty();
    }

    boolean hasRedoStack() {
        return !redoStack.isEmpty();
    }

    void pushUndo() {
        redoStack.removeAllElements();
        String s = actionManager().dumpCircuit();
        if (!undoStack.isEmpty() && s.compareTo(undoStack.lastElement().dump) == 0)
            return;
        undoStack.add(new UndoItem(s));
        trimToMaxDepth(undoStack);
    }

    void doUndo() {
        if (undoStack.isEmpty())
            return;
        redoStack.add(new UndoItem(actionManager().dumpCircuit()));
        UndoItem ui = undoStack.remove(undoStack.size() - 1);
        loadUndoItem(ui);
    }

    void doRedo() {
        if (redoStack.isEmpty())
            return;
        undoStack.add(new UndoItem(actionManager().dumpCircuit()));
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
        getActiveDocument().circuitLoader.readCircuit(ui.dump, CircuitConst.RC_NO_CENTER);
        CircuitRenderer renderer = renderer();
        renderer.transform[0] = renderer.transform[3] = ui.scale;
        renderer.transform[4] = ui.transform4;
        renderer.transform[5] = ui.transform5;
    }

    void writeRecoveryToStorage() {
        String s = actionManager().dumpCircuit();
        OptionsManager.setOptionInStorage("circuitRecovery", s);
    }

    void readRecovery() {
        recovery = OptionsManager.getOptionFromStorage("circuitRecovery", null);
    }

}
