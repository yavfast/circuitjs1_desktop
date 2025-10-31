package com.lushprojects.circuitjs1.client;

public class CircuitDocument {

    public final CircuitInfo circuitInfo;
    public final CircuitSimulator simulator;
    public final ScopeManager scopeManager;
    public final UndoManager undoManager;
    public final AdjustableManager adjustableManager;
    public final CircuitEditor circuitEditor;
    public final CircuitLoader circuitLoader;

    CircuitDocument(CirSim cirSim) {
        circuitInfo = new CircuitInfo(cirSim);
        simulator = new CircuitSimulator(cirSim);
        scopeManager = new ScopeManager(cirSim);
        undoManager = new UndoManager(cirSim);
        adjustableManager = new AdjustableManager(cirSim);
        circuitEditor = new CircuitEditor(cirSim);
        circuitLoader = new CircuitLoader(cirSim);
    }

    CircuitInfo getCircuitInfo() {
        return circuitInfo;
    }

    CircuitSimulator getSimulator() {
        return simulator;
    }

    ScopeManager getScopeManager() {
        return scopeManager;
    }

    UndoManager getUndoManager() {
        return undoManager;
    }

    AdjustableManager getAdjustableManager() {
        return adjustableManager;
    }

    CircuitEditor getCircuitEditor() {
        return circuitEditor;
    }

    CircuitLoader getCircuitLoader() {
        return circuitLoader;
    }
}
