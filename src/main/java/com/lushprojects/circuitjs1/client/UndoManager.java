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

    Vector<UndoItem> undoStack;
    Vector<UndoItem> redoStack;

    String recovery;

    public UndoManager(BaseCirSim cirSim, CircuitDocument circuitDocument) {
        super(cirSim, circuitDocument);
        undoStack = new Vector<>();
        redoStack = new Vector<>();
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
        UndoItem ui = redoStack.remove(redoStack.size() - 1);
        loadUndoItem(ui);
    }

    void loadUndoItem(UndoItem ui) {
        getActiveDocument().circuitLoader.readCircuit(ui.dump, CircuitConst.RC_NO_CENTER);
        CircuitRenderer renderer = renderer();
        renderer.transform[0] = renderer.transform[3] = ui.scale;
        renderer.transform[4] = ui.transform4;
        renderer.transform[5] = ui.transform5;
    }

    void writeRecoveryToStorage() {
        CirSim.console("write recovery");
        String s = actionManager().dumpCircuit();
        OptionsManager.setOptionInStorage("circuitRecovery", s);
    }

    void readRecovery() {
        recovery = OptionsManager.getOptionFromStorage("circuitRecovery", null);
    }

}
