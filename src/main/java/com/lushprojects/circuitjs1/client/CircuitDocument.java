package com.lushprojects.circuitjs1.client;

public class CircuitDocument {

    public final CircuitInfo circuitInfo;
    public final CircuitSimulator simulator;
    public final ScopeManager scopeManager;
    public final UndoManager undoManager;
    public final AdjustableManager adjustableManager;
    public final CircuitEditor circuitEditor;
    public final CircuitLoader circuitLoader;

    CircuitDocument(BaseCirSim cirSim) {
        circuitInfo = new CircuitInfo(cirSim);
        simulator = new CircuitSimulator(cirSim);
        scopeManager = new ScopeManager(cirSim);
        undoManager = new UndoManager(cirSim);
        adjustableManager = new AdjustableManager(cirSim);
        circuitEditor = new CircuitEditor(cirSim);
        circuitLoader = new CircuitLoader(cirSim);
        initDefaultUIState();
    }

    void initDefaultUIState() {
        dots = true;
        volts = true;
        power = false;
        showValues = true;
        smallGrid = false;
        speedValue = 117;
        currentValue = 50;
        powerValue = 50;
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

    // UI State
    boolean dots, volts, power, showValues, smallGrid;
    int speedValue = 117, currentValue = 50, powerValue = 50;
    double[] transform = new double[6]; // Store view transform (zoom/pan)

    void saveUIState(MenuManager menuManager, CirSim cirSim) {
        dots = menuManager.dotsCheckItem.getState();
        volts = menuManager.voltsCheckItem.getState();
        power = menuManager.powerCheckItem.getState();
        showValues = menuManager.showValuesCheckItem.getState();
        smallGrid = menuManager.smallGridCheckItem.getState();
        
        speedValue = cirSim.speedBar.getValue();
        currentValue = cirSim.currentBar.getValue();
        powerValue = cirSim.powerBar.getValue();
        
        // Save view transform
        System.arraycopy(cirSim.renderer.transform, 0, transform, 0, 6);
    }

    void restoreUIState(MenuManager menuManager, CirSim cirSim) {
        menuManager.dotsCheckItem.setState(dots);
        menuManager.voltsCheckItem.setState(volts);
        menuManager.powerCheckItem.setState(power);
        menuManager.showValuesCheckItem.setState(showValues);
        menuManager.smallGridCheckItem.setState(smallGrid);
        
        cirSim.speedBar.setValue(speedValue);
        cirSim.currentBar.setValue(currentValue);
        cirSim.powerBar.setValue(powerValue);
        
        // Restore view transform
        if (transform[0] != 0) {
             System.arraycopy(transform, 0, cirSim.renderer.transform, 0, 6);
        } else {
             // Reset to default if no saved transform
             cirSim.renderer.centreCircuit();
        }
        
        // Trigger side effects
        if (smallGrid) {
            cirSim.circuitEditor().setGrid();
        }
        cirSim.setPowerBarEnable();
        
        // Restore sliders
        adjustableManager.updateSliders();
    }
}
