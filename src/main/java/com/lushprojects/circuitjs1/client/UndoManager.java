package com.lushprojects.circuitjs1.client;

import com.google.gwt.storage.client.Storage;

import java.util.Vector;

public class UndoManager extends BaseCirSimDelegate {

    class UndoItem {
        public String dump;
        public double scale, transform4, transform5;

        UndoItem(String d) {
            dump = d;
            scale = renderer.transform[0];
            transform4 = renderer.transform[4];
            transform5 = renderer.transform[5];
        }
    }

    Vector<UndoItem> undoStack;
    Vector<UndoItem> redoStack;

    String recovery;


    protected UndoManager(CirSim cirSim) {
        super(cirSim);
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
        String s = cirSim.dumpCircuit();
        if (!undoStack.isEmpty() && s.compareTo(undoStack.lastElement().dump) == 0)
            return;
        undoStack.add(new UndoItem(s));
    }

    void doUndo() {
        if (undoStack.isEmpty())
            return;
        redoStack.add(new UndoItem(cirSim.dumpCircuit()));
        UndoItem ui = undoStack.remove(undoStack.size() - 1);
        loadUndoItem(ui);
    }

    void doRedo() {
        if (redoStack.isEmpty())
            return;
        undoStack.add(new UndoItem(cirSim.dumpCircuit()));
        UndoItem ui = redoStack.remove(redoStack.size() - 1);
        loadUndoItem(ui);
    }

    void loadUndoItem(UndoItem ui) {
        cirSim.readCircuit(ui.dump, CirSim.RC_NO_CENTER);
        renderer.transform[0] = renderer.transform[3] = ui.scale;
        renderer.transform[4] = ui.transform4;
        renderer.transform[5] = ui.transform5;
    }

    void writeRecoveryToStorage() {
        CirSim.console("write recovery");
        String s = cirSim.dumpCircuit();
        OptionsManager.setOptionInStorage("circuitRecovery", s);
    }

    void readRecovery() {
        recovery = OptionsManager.getOptionFromStorage("circuitRecovery", null);
    }



}
