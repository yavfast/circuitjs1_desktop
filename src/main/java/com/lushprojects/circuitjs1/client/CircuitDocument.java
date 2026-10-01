package com.lushprojects.circuitjs1.client;

import com.google.gwt.user.client.Timer;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CircuitDocument {

    // Reference to parent CirSim (needed for export/import operations)
    final BaseCirSim cirSim;

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

    /**
     * Session-unique number of this document, assigned at creation and never reused
     * (the agent handle is {@code d<number>}, SP_AGA_01_02).
     */
    private final int documentNumber;

    /** Tab title used while the document has no file name (null: "Untitled"). */
    private String displayTitle;

    /**
     * True while an agent operation that owns this document (a run, Phase 7 of PL_AGA) is in
     * progress; contracts that are not served while busy are rejected (SP_AGA_02 class table).
     */
    private boolean agentBusy;

    /** [SP_AGA_03_02] Element ID counters of this document (one content lifetime, SP_AGA_04_03). */
    private final ElementIdRegistry elementIdRegistry = new ElementIdRegistry();

    /**
     * Generates the next ID for {@code prefix} ([SP_AGA_03_02] "Generated IDs"): counter + 1,
     * skipping IDs present in the document. Used for elements that enter the document without an
     * ID outside an import (editor placement, scope undock) and as the lazy fallback of
     * {@link CircuitElm#getElementId()}.
     */
    public String nextElementId(String prefix) {
        return elementIdRegistry.next(prefix, presentElementIds());
    }

    /** Raises the counter of {@code id}'s prefix to its number (no-op for IDs without a counter). */
    public void raiseIdCounter(String id) {
        elementIdRegistry.raise(id);
    }

    /** Starts a new content lifetime ([SP_AGA_04_03]): all ID counters reset. */
    public void resetElementIds() {
        elementIdRegistry.reset();
    }

    /**
     * True between {@link #beginElementIdRestore} and {@link #endElementIdRestore}: the running
     * import is an undo/redo restore, which keeps the content lifetime (counters are not reset).
     */
    public boolean isRestoringElementIds() {
        return elementIdRegistry.hasPendingRestore();
    }

    /**
     * Announces that the next import restores an undo/redo snapshot whose elements carry
     * {@code ids} in element order ([SP_AGA_03_02] "Undo/redo restore").
     */
    void beginElementIdRestore(String[] ids) {
        elementIdRegistry.setPendingRestore(ids != null ? ids : new String[0]);
    }

    /** Ends an undo/redo restore (drops the restore IDs if no import consumed them). */
    void endElementIdRestore() {
        elementIdRegistry.takePendingRestore();
    }

    /**
     * Gives every element of the document a valid, unique ID after an import, paste or restore
     * ([SP_AGA_03_02]). Called once per import by {@code ImportLifecycle.finalizeCircuitLoading}.
     * <ol>
     * <li>Undo/redo restore: element i receives the snapshot's ID i; when the counts differ all IDs
     *     are regenerated in order.</li>
     * <li>Every present ID that is valid and not repeated raises its counter — all of them before
     *     any ID is generated. An invalid or repeated ID is dropped.</li>
     * <li>Elements without an ID (text lines, pasted elements, auto-wires, dropped IDs) receive
     *     generated IDs in element order.</li>
     * </ol>
     *
     * @return one {@code ids_regenerated} message per replaced or regenerated ID set (empty when
     *         nothing was replaced); the caller logs them
     */
    public List<String> settleElementIds() {
        List<String> warnings = new ArrayList<>();
        List<CircuitElm> elements = simulator.elmList;
        String[] restore = elementIdRegistry.takePendingRestore();
        if (restore != null) {
            if (restore.length == elements.size()) {
                for (int i = 0; i < restore.length; i++) {
                    elements.get(i).setElementId(restore[i]);
                }
            } else {
                warnings.add("undo/redo restored " + elements.size() + " elements but the snapshot holds "
                        + restore.length + " IDs; element IDs regenerated in order");
                for (CircuitElm elm : elements) {
                    elm.setElementId(null);
                }
            }
        }
        Set<String> present = new HashSet<>();
        List<CircuitElm> replaced = new ArrayList<>();
        List<String> replacedIds = new ArrayList<>();
        for (CircuitElm elm : elements) {
            if (!elm.hasElementId()) {
                continue;
            }
            String id = elm.getElementId();
            if (ElementIdRegistry.isValidId(id) && present.add(id)) {
                elementIdRegistry.raise(id);
            } else {
                replaced.add(elm);
                replacedIds.add(id);
                elm.setElementId(null);
            }
        }
        for (CircuitElm elm : elements) {
            if (!elm.hasElementId()) {
                String id = elementIdRegistry.next(elm.getIdPrefix(), present);
                elm.setElementId(id);
                present.add(id);
            }
        }
        for (int i = 0; i < replaced.size(); i++) {
            String old = replacedIds.get(i);
            warnings.add("element key '" + old + "' is " + (ElementIdRegistry.isValidId(old) ? "repeated" : "not a valid ID")
                    + "; replaced by " + replaced.get(i).getElementId());
        }
        return warnings;
    }

    /** IDs currently assigned to the document's elements (elements without an ID are skipped). */
    private Set<String> presentElementIds() {
        Set<String> ids = new HashSet<>();
        for (CircuitElm elm : simulator.elmList) {
            if (elm.hasElementId()) {
                ids.add(elm.getElementId());
            }
        }
        return ids;
    }

    /**
     * IDs of the elements that the text dump writes, in dump order: the {@code elementIds} of an
     * undo entry ([SP_AGA_01_10]). Element i of the restored snapshot is the i-th of them.
     */
    public String[] getDumpedElementIds() {
        List<String> ids = new ArrayList<>();
        for (CircuitElm elm : simulator.elmList) {
            if (elm.hasDumpLine()) {
                ids.add(elm.getElementId());
            }
        }
        return ids.toArray(new String[0]);
    }

    public interface SimulationStateListener {
        void onSimulationStateChanged(boolean isRunning, String errorMessage);
    }

    public interface SimulationUpdateListener {
        void onSimulationUpdate();
    }

    private final List<SimulationStateListener> stateListeners = new ArrayList<>();
    private final List<SimulationUpdateListener> updateListeners = new ArrayList<>();

    CircuitDocument(BaseCirSim cirSim) {
        this(cirSim, cirSim.allocateDocumentNumber());
    }

    /**
     * Scratch document ([SP_AGA_01_05] "Defaults"): default options, document number 0 (no
     * handle is consumed), never added to {@code DocumentManager}, never bound and never shown.
     * The agent catalogue uses it as the owner of the elements it measures.
     */
    static CircuitDocument createScratch(BaseCirSim cirSim) {
        CircuitDocument doc = new CircuitDocument(cirSim, 0);
        // The blank-circuit simulation defaults (ImportLifecycle.resetCircuitState)
        doc.simulator.maxTimeStep = doc.simulator.timeStep = 5e-6;
        doc.simulator.minTimeStep = 50e-12;
        doc.circuitEditor.gridSize = 16;
        doc.circuitEditor.gridMask = -16;
        doc.circuitEditor.gridRound = 7;
        return doc;
    }

    private CircuitDocument(BaseCirSim cirSim, int documentNumber) {
        this.cirSim = cirSim;
        this.documentNumber = documentNumber;
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

    /** @return the session-unique number of this document (assigned at creation, never reused) */
    public int getDocumentNumber() {
        return documentNumber;
    }

    /** @return the tab title used while the document has no file name, or null */
    public String getDisplayTitle() {
        return displayTitle;
    }

    /**
     * Sets the tab title used while the document has no file name. The caller refreshes the tab
     * through {@code DocumentManager.notifyTitleChanged}.
     */
    public void setDisplayTitle(String title) {
        displayTitle = title;
    }

    /** @return true while an agent operation owns this document (see {@link #setAgentBusy}) */
    public boolean isAgentBusy() {
        return agentBusy;
    }

    /** Marks the document as owned by a running agent operation (set and cleared by the agent run). */
    public void setAgentBusy(boolean busy) {
        agentBusy = busy;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public CircuitElm getStopElm() {
        return stopElm;
    }

    public void setSimRunning(boolean running) {
        // If there's an active error message, do not allow starting the
        // simulation, but still allow stopping it so the user can recover.
        if (errorMessage != null && running)
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
                            boolean stamped = simulator.preStampAndStampCircuit();
                            if (!stamped) {
                                // Stamp did not complete; keep loop running and try again next tick.
                                notifyUpdateListeners();
                                return;
                            }
                        } catch (Exception e) {
                            logBuffer.log("Exception in stampCircuit(): " + e.getMessage());
                            CircuitDocument.this.stop("Exception in stampCircuit(): " + e.getMessage(), null);
                        }
                    }

                    // 3. Run Circuit
                    simulator.runCircuit(false); // wasAnalyzed?

                } catch (Exception e) {
                    logBuffer.log("Exception in simulation: " + e.getMessage());
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
        /** Per-document log cap; the oldest messages are dropped first. */
        static final int MAX_LOG_BUFFER_ENTRIES = 100;

        private final List<String> logs = new ArrayList<>();

        public void log(String message) {
            logs.add(message);
            if (logs.size() > MAX_LOG_BUFFER_ENTRIES) {
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

    /**
     * Get the CircuitRenderer (owned by CirSim but accessed through document).
     */
    public CircuitRenderer getRenderer() {
        return cirSim.renderer;
    }

    /**
     * Get display settings (rendering options like show dots, voltage colors, etc.).
     */
    public DisplaySettings getDisplaySettings() {
        return cirSim.displaySettings;
    }

    /**
     * Get DialogManager for showing dialogs.
     */
    public DialogManager getDialogManager() {
        return cirSim.dialogManager;
    }

    /**
     * Get CirSim for export/import operations.
     */
    public CirSim getCirSim() {
        return (CirSim) cirSim;
    }

    // UI State
    // Defaults match a freshly reset circuit (ImportLifecycle.resetCircuitState)
    boolean dots, volts = true, power, showValues = true, smallGrid;
    int speedValue = 117, currentValue = 50, powerValue = 50;
    double voltageRange = 5; // ColorSettings holds one session-wide value; each document keeps its own
    double[] transform = new double[6]; // Store view transform (zoom/pan)
    // Renderer hint (the "h" line). The renderer holds one session-wide hint; each document keeps
    // its own, so a tab switch or a background operation never shows another document's hint.
    int hintType = -1, hintItem1, hintItem2;

    /**
     * Stores the session widgets' current values (options, bars, voltage range, view transform,
     * hint) as this document's UI state. Call it while this document is bound.
     */
    void saveUIState(MenuManager menuManager, CirSim cirSim) {
        dots = menuManager.dotsCheckItem.getState();
        volts = menuManager.voltsCheckItem.getState();
        power = menuManager.powerCheckItem.getState();
        showValues = menuManager.showValuesCheckItem.getState();
        smallGrid = menuManager.smallGridCheckItem.getState();

        speedValue = cirSim.speedBar.getValue();
        currentValue = cirSim.currentBar.getValue();
        powerValue = cirSim.powerBar.getValue();
        voltageRange = ColorSettings.get().getVoltageRange();

        // Save view transform
        System.arraycopy(cirSim.renderer.transform, 0, transform, 0, 6);

        hintType = cirSim.renderer.getHintType();
        hintItem1 = cirSim.renderer.getHintItem1();
        hintItem2 = cirSim.renderer.getHintItem2();
    }

    /**
     * Puts this document's saved simulation/display options back into the session widgets
     * that the exporters read (menu checkboxes, speed/current/power bars, voltage range).
     * Used on tab activation and when dumping an inactive document.
     */
    void applyOptionWidgets(MenuManager menuManager, CirSim cirSim) {
        menuManager.dotsCheckItem.setState(dots);
        menuManager.voltsCheckItem.setState(volts);
        menuManager.powerCheckItem.setState(power);
        menuManager.showValuesCheckItem.setState(showValues);
        menuManager.smallGridCheckItem.setState(smallGrid);

        cirSim.speedBar.setValue(speedValue);
        cirSim.currentBar.setValue(currentValue);
        cirSim.powerBar.setValue(powerValue);
        ColorSettings.get().setVoltageRange(voltageRange);
    }

    void restoreUIState(MenuManager menuManager, CirSim cirSim) {
        applyOptionWidgets(menuManager, cirSim);

        // Update time step bar to match this document's simulator
        cirSim.controlsDialog.updateTimeStepBar();

        // The circuit area depends on this document's scope count. Recompute it on every activation:
        // centring does so only for a document without a saved transform, and setupScopes only
        // when this document's own scope count changed, so a scope-less tab left it full height.
        cirSim.renderer.setCircuitArea();
        applyViewState(cirSim, true);

        // Trigger side effects
        if (smallGrid) {
            circuitEditor.setGrid();
        }
        cirSim.setPowerBarEnable();

        // Restore sliders
        adjustableManager.updateSliders();
    }

    /**
     * Puts this document's saved view transform and hint into the session renderer.
     *
     * @param centreIfUnset when no transform was saved yet, centre the circuit (tab activation and
     *                      a first background bind); false copies the saved transform as is
     */
    void applyViewState(CirSim cirSim, boolean centreIfUnset) {
        if (transform[0] != 0 || !centreIfUnset) {
            System.arraycopy(transform, 0, cirSim.renderer.transform, 0, 6);
        } else {
            // Reset to default if no saved transform
            cirSim.renderer.centreCircuit();
        }
        cirSim.renderer.setHintType(hintType);
        cirSim.renderer.setHintItem1(hintItem1);
        cirSim.renderer.setHintItem2(hintItem2);
    }

    public void dispose() {
        simulationLoop.stop();
    }
}
