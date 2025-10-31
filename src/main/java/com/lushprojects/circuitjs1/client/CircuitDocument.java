package com.lushprojects.circuitjs1.client;

final class CircuitDocument {

    private final CircuitInfo circuitInfo;
    private final CircuitSimulator simulator;
    private final ScopeManager scopeManager;
    private final UndoManager undoManager;
    private final AdjustableManager adjustableManager;
    private final CircuitEditor circuitEditor;
    private final CircuitLoader circuitLoader;

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
