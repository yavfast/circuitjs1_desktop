package com.lushprojects.circuitjs1.client;

public class BaseCirSimDelegate {

    final CirSim cirSim;

    protected BaseCirSimDelegate(CirSim cirSim) {
        this.cirSim = cirSim;

    }

    CircuitSimulator simulator() {
        return cirSim.simulator;
    }

    CircuitRenderer renderer() {
        return cirSim.renderer;
    }

    CircuitEditor circuitEditor() {
        return cirSim.circuitEditor;
    }

    MenuManager menuManager() {
        return cirSim.menuManager;
    }

    UndoManager undoManager() {
        return cirSim.undoManager;
    }

    ScopeManager scopeManager() {
        return cirSim.scopeManager;
    }

    DialogManager dialogManager() {
        return cirSim.dialogManager;
    }

    ActionManager actionManager() {
        return cirSim.actionManager;
    }
}
