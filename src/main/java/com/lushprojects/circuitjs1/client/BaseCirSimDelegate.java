package com.lushprojects.circuitjs1.client;

public class BaseCirSimDelegate {

    final CirSim cirSim;
    final CircuitSimulator simulator;
    final CircuitRenderer renderer;
    final CircuitEditor circuitEditor;
    final MenuManager menuManager;
    final UndoManager undoManager;
    final ScopeManager scopeManager;

    protected BaseCirSimDelegate(CirSim cirSim) {
        this.cirSim = cirSim;
        this.simulator = cirSim.simulator;
        this.renderer = cirSim.renderer;
        this.circuitEditor = cirSim.circuitEditor;
        this.menuManager = cirSim.menuManager;
        this.undoManager = cirSim.undoManager;
        this.scopeManager = cirSim.scopeManager;
    }
}
