package com.lushprojects.circuitjs1.client;

import com.google.gwt.user.client.Timer;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import java.util.ArrayList;
import java.util.List;

public class CircuitDocument {

    public final CircuitInfo circuitInfo;
    public final CircuitSimulator simulator;
    public final ScopeManager scopeManager;
    public final UndoManager undoManager;
    public final AdjustableManager adjustableManager;
    public final CircuitEditor circuitEditor;
    public final CircuitLoader circuitLoader;
    public final SimulationLoop simulationLoop;
    public final LogBuffer logBuffer;

    private boolean isRunning = false; // Start stopped, user must explicitly start simulation
    private boolean isActive = false;
    private String errorMessage = null;
    private CircuitElm stopElm = null;

    public interface SimulationStateListener {
        void onSimulationStateChanged(boolean isRunning, String errorMessage);
    }

    public interface SimulationUpdateListener {
        void onSimulationUpdate();
    }

    private final List<SimulationStateListener> stateListeners = new ArrayList<>();
    private final List<SimulationUpdateListener> updateListeners = new ArrayList<>();

    CircuitDocument(BaseCirSim cirSim) {
        circuitInfo = new CircuitInfo(cirSim, this);
        simulator = new CircuitSimulator(cirSim, this);
        scopeManager = new ScopeManager(cirSim, this);
        undoManager = new UndoManager(cirSim, this);
        adjustableManager = new AdjustableManager(cirSim, this);
        circuitEditor = new CircuitEditor(cirSim, this);
        circuitLoader = new CircuitLoader(cirSim, this);
        simulationLoop = new SimulationLoop();
        logBuffer = new LogBuffer();
        initDefaultUIState();
        updateSimulationLoop();
    }

    public void setActive(boolean active) {
        if (isActive == active) return;
        isActive = active;
        updateSimulationLoop();
    }

    public void addStateListener(SimulationStateListener listener) {
        stateListeners.add(listener);
    }

    public void removeStateListener(SimulationStateListener listener) {
        stateListeners.remove(listener);
    }

    public void addUpdateListener(SimulationUpdateListener listener) {
        updateListeners.add(listener);
    }

    public void removeUpdateListener(SimulationUpdateListener listener) {
        updateListeners.remove(listener);
    }

    public boolean isRunning() {
        return isRunning;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public CircuitElm getStopElm() {
        return stopElm;
    }

    public void setSimRunning(boolean running) {
        if (errorMessage != null)
            return; // Cannot start if there is an error
        if (isRunning == running)
            return;

        isRunning = running;
        simulator.simRunning = running;

        updateSimulationLoop();

        notifyStateChanged();
    }

    private void updateSimulationLoop() {
        if (isRunning && isActive) {
            simulationLoop.start();
        } else {
            simulationLoop.stop();
        }
    }

    public void stop(String message, CircuitElm elm) {
        errorMessage = message;
        stopElm = elm;
        setSimRunning(false);
    }

    public void clearError() {
        errorMessage = null;
        stopElm = null;
        notifyStateChanged();
    }

    private void notifyStateChanged() {
        for (SimulationStateListener listener : stateListeners) {
            if (listener != null) {
                listener.onSimulationStateChanged(isRunning, errorMessage);
            }
        }
    }

    public class SimulationLoop {
        private final Timer timer = new Timer() {
            @Override
            public void run() {
                update();
            }
        };

        public void start() {
            timer.scheduleRepeating(16); // ~60 FPS
        }

        public void stop() {
            timer.cancel();
        }

        private void update() {
            if (isRunning) {
                try {
                    // Logic copied/adapted from CircuitRenderer.updateCircuit

                    // 1. Analyze if needed
                    if (circuitInfo.dcAnalysisFlag) { // needsAnalysis is tracked in Renderer, but maybe should be here?
                        // For now, let's assume we handle dcAnalysisFlag here
                        simulator.analyzeCircuit();
                        circuitInfo.dcAnalysisFlag = false;
                    }

                    // 2. Stamp if needed
                    if (simulator.needsStamp) {
                        try {
                            simulator.preStampAndStampCircuit();
                        } catch (Exception e) {
                            CircuitDocument.this.stop("Exception in stampCircuit()", null);
                            // GWT.log("Exception in stampCircuit", e); // TODO: Use LogBuffer
                        }
                    }

                    // 3. Run Circuit
                    simulator.runCircuit(false); // wasAnalyzed?

                } catch (Exception e) {
                    CircuitDocument.this.stop("Exception in simulation: " + e.getMessage(), null);
                }
            }

            notifyUpdateListeners();
        }
    }

    private void notifyUpdateListeners() {
        for (SimulationUpdateListener listener : updateListeners) {
            listener.onSimulationUpdate();
        }
    }

    public static class LogBuffer {
        private final List<String> logs = new ArrayList<>();

        public void log(String message) {
            logs.add(message);
            if (logs.size() > 100) {
                logs.remove(0);
            }
        }

        public List<String> getLogs() {
            return new ArrayList<>(logs);
        }

        public void clear() {
            logs.clear();
        }
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
            circuitEditor.setGrid();
        }
        cirSim.setPowerBarEnable();

        // Restore sliders
        adjustableManager.updateSliders();
    }

    public void dispose() {
        simulationLoop.stop();
    }
}
