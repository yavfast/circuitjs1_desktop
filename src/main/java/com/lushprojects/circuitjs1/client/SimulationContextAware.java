package com.lushprojects.circuitjs1.client;

/**
 * Marker interface for edit dialogs and other helpers that need access to the
 * circuit document context for updating simulation state.
 */
public interface SimulationContextAware {
    void setSimulationContext(CircuitDocument circuitDocument);
}
