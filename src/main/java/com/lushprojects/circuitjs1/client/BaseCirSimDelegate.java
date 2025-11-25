package com.lushprojects.circuitjs1.client;

public class BaseCirSimDelegate {

    final BaseCirSim cirSim;

    CircuitDocument circuitDocument;

    protected BaseCirSimDelegate(BaseCirSim cirSim) {
        this(cirSim, null);
    }

    protected BaseCirSimDelegate(BaseCirSim cirSim, CircuitDocument circuitDocument) {
        this.cirSim = cirSim;
        this.circuitDocument = circuitDocument;
    }

    public void setCircuitDocument(CircuitDocument circuitDocument) {
        this.circuitDocument = circuitDocument;
    }

    public CircuitDocument getActiveDocument() {
        if (circuitDocument != null) {
            return circuitDocument;
        }
        return cirSim.getActiveDocument();
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
