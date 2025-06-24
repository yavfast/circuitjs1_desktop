package com.lushprojects.circuitjs1.client;

public class BaseCirSimDelegate {

    final CirSim cirSim;
    final CircuitSimulator simulator;
    final CircuitRenderer renderer;

    protected BaseCirSimDelegate(CirSim cirSim) {
        this.cirSim = cirSim;
        this.simulator = cirSim.simulator;
        this.renderer = cirSim.renderer;
    }
}
