package com.lushprojects.circuitjs1.client;

import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONBoolean;
import com.google.gwt.json.client.JSONNull;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;
import com.google.gwt.user.client.Timer;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
     * [SP_AGA_04_02] The agent operation that owns this document (a run) while it is in progress,
     * else null. While set the document is <em>busy</em>: contracts that are not served while
     * busy are rejected (SP_AGA_02 class table) and the free-running loop does not step it.
     */
    private BusyOwner busyOwner;

    /**
     * [SP_AGA_04_02] The owner of a busy document. A user action on the document (edit, undo/redo,
     * run/stop/reset, content replacement) and closing it call {@link #cancel()} before they take
     * effect.
     */
    public interface BusyOwner {
        /**
         * Requests the end of the operation with reason {@code cancelled}. Called between slices
         * (every user event is), the operation ends at once, so the user action that follows sees
         * an idle document; called from inside a slice, it ends at that slice's boundary.
         */
        void cancel();
    }

    /** [SP_AGA_03_02] Element ID counters of this document (one content lifetime, SP_AGA_04_03). */
    private final ElementIdRegistry elementIdRegistry = new ElementIdRegistry();

    /**
     * [SP_AGA_04_01] "Agent origin": true while an Agent API mutation of this document runs.
     * Undo pushes requested by editor paths reused inside the mutation are suppressed
     * ({@code UndoManager.pushUndo}); they do not count as user edits.
     */
    private boolean agentOrigin;

    /**
     * [SP_AGA_01_12] Open marks: PostRefs ({@code <ElementId>.<PinName>}) the agent declared
     * intentionally unconnected. In memory only, captured in every undo entry, never written to
     * circuit files; cleared when the content is replaced.
     */
    private final Set<String> openMarks = new LinkedHashSet<>();

    /**
     * Issues of the most recent agent import into this document, as Issue JSON objects
     * ([SP_AGA_01_11] {@code lastImport}); null before the first one.
     */
    private JSONArray lastImportIssues;

    /** @return true while an agent mutation of this document runs (see {@link #setAgentOrigin}) */
    public boolean isAgentOrigin() {
        return agentOrigin;
    }

    /** Marks or unmarks the running Agent API mutation of this document ([SP_AGA_04_01]). */
    public void setAgentOrigin(boolean agentOrigin) {
        this.agentOrigin = agentOrigin;
    }

    /** @return the open marks in insertion order */
    public String[] getOpenMarks() {
        return openMarks.toArray(new String[0]);
    }

    /** Replaces the open-mark set (undo/redo and snapshot restore). */
    public void setOpenMarks(String[] marks) {
        openMarks.clear();
        if (marks != null) {
            for (String m : marks) {
                openMarks.add(m);
            }
        }
    }

    /** @return true when {@code postRef} carries an open mark */
    public boolean hasOpenMark(String postRef) {
        return openMarks.contains(postRef);
    }

    /** Adds ({@code open}) or removes an open mark. */
    public void setOpenMark(String postRef, boolean open) {
        if (open) {
            openMarks.add(postRef);
        } else {
            openMarks.remove(postRef);
        }
    }

    /** Clears the open-mark set (content replacement, SP_AGA_01_12). */
    public void clearOpenMarks() {
        openMarks.clear();
    }

    /** @return the issues of the last agent import as Issue JSON objects, or null */
    public JSONArray getLastImportIssues() {
        return lastImportIssues;
    }

    public void setLastImportIssues(JSONArray issues) {
        lastImportIssues = issues;
    }

    /**
     * @return a copy of the element ID counters, for a snapshot that must leave the document
     *         unchanged when restored ([SP_AGA_03_04]); see {@link #restoreIdCounters}
     */
    public Map<String, Integer> captureIdCounters() {
        return elementIdRegistry.copyCounters();
    }

    /** Puts back counters taken by {@link #captureIdCounters}. */
    public void restoreIdCounters(Map<String, Integer> counters) {
        elementIdRegistry.setCounters(counters);
    }

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
        idRestoreWarning = null;
        elementIdRegistry.setPendingRestore(ids != null ? ids : new String[0]);
    }

    /** The ids_regenerated message of the last undo/redo restore, or null (see {@link #takeIdRestoreWarning}). */
    private String idRestoreWarning;

    /**
     * @return the {@code ids_regenerated} message of the undo/redo restore that just ran (the
     *         restored element count differed from the snapshot's ID count), or null; cleared
     */
    String takeIdRestoreWarning() {
        String w = idRestoreWarning;
        idRestoreWarning = null;
        return w;
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
                idRestoreWarning = "undo/redo restored " + elements.size() + " elements but the snapshot holds "
                        + restore.length + " IDs; element IDs regenerated in order";
                warnings.add(idRestoreWarning);
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
        // The blank-circuit simulation defaults (time step set by the constructor)
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
        // The blank-circuit time-step defaults (ImportLifecycle.resetCircuitState). Without them a
        // document created in the background dumped a zero time step, which a reload (undo/redo)
        // turned into the time-step bar's smallest position (1e-12).
        simulator.maxTimeStep = simulator.timeStep = 5e-6;
        simulator.minTimeStep = 50e-12;
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
        return busyOwner != null;
    }

    /**
     * Marks the document as owned by a running agent operation ({@code owner}), or idle again
     * (null). Set and cleared by the agent run only.
     */
    public void setAgentBusy(BusyOwner owner) {
        busyOwner = owner;
    }

    /**
     * [SP_AGA_04_02] Cancel request of a user action or a close: when an agent run owns this
     * document, it ends as {@code cancelled} before the caller's action proceeds. Does nothing
     * when the document is idle.
     */
    public void cancelAgentRun() {
        BusyOwner owner = busyOwner;
        if (owner != null) {
            owner.cancel();
        }
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

    /** The stop-trigger element that fired last and was not yet taken by an agent run, or null. */
    private CircuitElm firedStopTrigger;

    /**
     * Called by a stop-trigger element whose condition held for its delay: clears the running flag
     * (the free-running behaviour) and remembers the element, so an agent run of this document ends
     * after the current timestep ([SP_AGA_02_10] stop trigger, SP_AGA_DEC_05).
     */
    public void stopTriggerFired(CircuitElm elm) {
        firedStopTrigger = elm;
        setSimRunning(false);
    }

    /**
     * @return the stop-trigger element that fired since the last call, or null; the record is
     *         cleared (an agent run takes it at its start, to drop a free-running trigger, and after
     *         every timestep)
     */
    public CircuitElm takeFiredStopTrigger() {
        CircuitElm elm = firedStopTrigger;
        firedStopTrigger = null;
        return elm;
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
            afterFrame.clear();
        }

        /** One-shot actions run after the next frame ({@link #runAfterNextFrame}). */
        private final List<Runnable> afterFrame = new ArrayList<>();

        /** @return true while the loop timer is scheduled (the document is running and active) */
        public boolean isScheduled() {
            return isRunning && isActive;
        }

        /**
         * Runs {@code action} once, right after this loop's next frame (PL_AGA_DEC_01 condition 4:
         * a background run's next slice waits for one free-run frame of the visible tab). The
         * action is dropped when the loop stops before its next frame; callers keep a fallback.
         */
        public void runAfterNextFrame(Runnable action) {
            afterFrame.add(action);
        }

        private void update() {
            try {
                step();
            } finally {
                if (!afterFrame.isEmpty()) {
                    List<Runnable> actions = new ArrayList<>(afterFrame);
                    afterFrame.clear();
                    for (Runnable a : actions) {
                        a.run();
                    }
                }
            }
        }

        private void step() {
            if (busyOwner != null) {
                // [SP_AGA_04_02] the agent run owns stepping; it repaints the document per slice.
                // The running flag is unchanged and takes effect again after the run.
                return;
            }
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
     * Diagnostic for the live harness (not an API contract): this document's own UI state as
     * [SP_AGA_03_08] R2 compares it — options, bars, voltage range, view transform, hint, file name
     * and path. For the bound document they are the session widgets' values (its saved state is
     * refreshed from them first), for any other document its saved UI state.
     */
    public JSONObject getUIStateJson() {
        CirSim sim = getCirSim();
        if (sim.getActiveDocument() == this) {
            saveUIState(sim.menuManager, sim);
        }
        JSONObject o = new JSONObject();
        o.put("dots", JSONBoolean.getInstance(dots));
        o.put("volts", JSONBoolean.getInstance(volts));
        o.put("power", JSONBoolean.getInstance(power));
        o.put("showValues", JSONBoolean.getInstance(showValues));
        o.put("smallGrid", JSONBoolean.getInstance(smallGrid));
        o.put("speed", new JSONNumber(speedValue));
        o.put("current", new JSONNumber(currentValue));
        o.put("powerBar", new JSONNumber(powerValue));
        o.put("voltageRange", new JSONNumber(voltageRange));
        JSONArray t = new JSONArray();
        for (int i = 0; i < 6; i++) {
            t.set(i, new JSONNumber(transform[i]));
        }
        o.put("transform", t);
        o.put("hint", new JSONString(hintType + " " + hintItem1 + " " + hintItem2));
        o.put("fileName", circuitInfo.fileName == null ? JSONNull.getInstance() : new JSONString(circuitInfo.fileName));
        o.put("filePath", circuitInfo.filePath == null ? JSONNull.getInstance() : new JSONString(circuitInfo.filePath));
        return o;
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

        // Show this document's time step on the bar without running the bar's command, which
        // would re-quantise the step to the bar's table ([SP_AGA_06_01] item 18, audit BL-D01)
        cirSim.controlsDialog.syncTimeStepBar();

        // The circuit area depends on this document's scope count. Recompute it on every activation:
        // centring does so only for a document without a saved transform, and setupScopes only
        // when this document's own scope count changed, so a scope-less tab left it full height.
        cirSim.renderer.setCircuitArea();
        applyViewState(cirSim, true);

        // The editor grid follows this document's option in both directions (16 -> 8 and 8 -> 16);
        // calling it only for the small grid left an 8 px grid after the option was cleared.
        circuitEditor.setGrid();
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

    /**
     * Analyses this document's circuit now, also while it free-runs ([SP_AGA_02_04]: an agent
     * mutation returns the state of the analysed circuit). Call it while the document is bound.
     */
    public void analyzeNow() {
        simulator.analyzeCircuit();
        circuitInfo.dcAnalysisFlag = false;
    }

    /**
     * [SP_AGA_02_06] Makes this document's analysis current, also while it is stopped: runs a
     * pending analysis and the pending node allocation and stamp, exactly as the next
     * free-running frame would ({@link SimulationLoop}), so node indices, the isolated groups and
     * the solver event list describe the present circuit. Does nothing when both are current, or
     * when the stamp is pending only because a stop interrupted it (its node data is current).
     * Call it while the document is bound ({@code DocumentScope}): console lines and the analysis
     * hook go to the bound document.
     *
     * @return false when the current analysis threw (the document is then stopped with the
     *         exception message, as the free-running loop does); the failure is remembered until
     *         the next analysis starts, so the node data is never taken as current meanwhile
     */
    public boolean ensureAnalysed() {
        if (circuitInfo.dcAnalysisFlag) {
            analyzeNow();
        }
        if (isAnalysisFailed()) {
            return false;
        }
        if (!simulator.needsStamp || simulator.stopMessage != null || simulator.elmList.isEmpty()) {
            return true;
        }
        try {
            simulator.preStampAndStampCircuit();
            return true;
        } catch (Exception e) {
            // same handling as SimulationLoop.update
            failedAnalysis = simulator.getAnalysisCount();
            logBuffer.log("Exception in stampCircuit(): " + e.getMessage());
            stop("Exception in stampCircuit(): " + e.getMessage(), null);
            return false;
        }
    }

    /** {@code CircuitSimulator.getAnalysisCount()} of the analysis whose stamp threw, or -1. */
    private int failedAnalysis = -1;

    /** @return true when {@link #ensureAnalysed()} failed for the current analysis */
    public boolean isAnalysisFailed() {
        return failedAnalysis == simulator.getAnalysisCount();
    }

    public void dispose() {
        simulationLoop.stop();
        // [SP_AGA_04_01] the open agent transaction is discarded with the document
        undoManager.discardTransaction();
    }
}
