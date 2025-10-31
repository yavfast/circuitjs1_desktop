package com.lushprojects.circuitjs1.client;

public class BaseCirSimDelegate {

    final BaseCirSim cirSim;

    protected BaseCirSimDelegate(BaseCirSim cirSim) {
        this.cirSim = cirSim;

    }

    public CircuitDocument getActiveDocument() {
        return cirSim.activeDocument;
    }

    CircuitSimulator simulator() {
        return getActiveDocument().simulator;
    }

    CircuitRenderer renderer() {
        return cirSim.renderer;
    }

    CircuitEditor circuitEditor() {
        return getActiveDocument().circuitEditor;
    }

    MenuManager menuManager() {
        return cirSim.menuManager;
    }

    UndoManager undoManager() {
        return getActiveDocument().undoManager;
    }

    ScopeManager scopeManager() {
        return getActiveDocument().scopeManager;
    }

    DialogManager dialogManager() {
        return cirSim.dialogManager;
    }

    ActionManager actionManager() {
        return cirSim.actionManager;
    }

    CircuitInfo circuitInfo() {
        return getActiveDocument().circuitInfo;
    }
}
