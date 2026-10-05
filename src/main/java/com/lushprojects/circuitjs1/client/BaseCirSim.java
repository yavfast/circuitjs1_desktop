package com.lushprojects.circuitjs1.client;

import com.google.gwt.i18n.client.DateTimeFormat;
import com.lushprojects.circuitjs1.client.agent.Catalogue;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.LabeledNodeElm;
import com.lushprojects.circuitjs1.client.element.TransistorElm;
import com.lushprojects.circuitjs1.client.solver.SolverMode;

import java.util.Date;

public class BaseCirSim {

    public final LogManager logManager = new LogManager(this);
    public final CircuitRenderer renderer = new CircuitRenderer(this);
    public final ClipboardManager clipboardManager = new ClipboardManager(this);
    public final DialogManager dialogManager = new DialogManager(this);
    public final MenuManager menuManager = new MenuManager(this);
    public final DisplaySettings displaySettings = new DisplaySettings(menuManager);
    public final DocumentManager documentManager = new DocumentManager(this);
    public final LoadFile loadFileInput = new LoadFile(this);
    public final ActionManager actionManager = new ActionManager(this);

    private final CircuitDocument.SimulationUpdateListener updateListener = new CircuitDocument.SimulationUpdateListener() {
        @Override
        public void onSimulationUpdate() {
            renderer.render();
        }
    };

    private CircuitDocument activeDocument;

    // Last document number handed out; numbers are never reused in a session (SP_AGA_01_02).
    private int lastDocumentNumber;

    // Set once application start-up has completed (end of CirSim.init); see isStartupCompleted().
    private boolean startupCompleted;

    // [SP_AGA_02_01] Agent element catalogue: session-scoped (RULE_ARCH_006), built on first use.
    private Catalogue agentCatalogue;

    /** [SP_SLV_01_01] Preference key of the solver-mode session default. */
    public static final String SOLVER_MODE_OPTION = "solverMode";

    // [SP_SLV_01_01] solver-mode session default (RULE_ARCH_006: session scope), read on first use
    private SolverMode solverModeDefault;

    BaseCirSim() {
        CircuitDocument initialDocument = documentManager.createDocument();
        documentManager.setInitialDocument(initialDocument);
    }

    void bindDocument(CircuitDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("document must not be null");
        }

        if (activeDocument != null) {
            activeDocument.removeUpdateListener(updateListener);
            activeDocument.setActive(false);
        }
        activeDocument = document;
        activeDocument.addUpdateListener(updateListener);
        activeDocument.setActive(true);
        renderer.resetTimers();
    }

    /**
     * Silent bind: swaps the bound document by field only. Unlike {@link #bindDocument} it does not
     * call {@code setActive}, move listeners or reset the renderer timers, so the active tab's
     * simulation loop keeps running. Only {@code DocumentManager.swapActiveSilently} calls it, for
     * the scoped background-document bind (PL_AGA_DEC_01); the caller swaps back.
     */
    void swapDocumentSilently(CircuitDocument document) {
        if (document == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        activeDocument = document;
    }

    /** @return the next session-unique document number (1, 2, ...); called once per new document */
    int allocateDocumentNumber() {
        return ++lastDocumentNumber;
    }

    /**
     * @return true once application start-up has completed: the UI is built, the start-up
     *         document is bound and the JS interfaces are installed (end of {@code CirSim.init})
     */
    public boolean isStartupCompleted() {
        return startupCompleted;
    }

    void markStartupCompleted() {
        startupCompleted = true;
    }

    public CircuitDocument getActiveDocument() {
        return activeDocument;
    }

    /** @return the session's agent element catalogue, measured on first use ([SP_AGA_02_01]) */
    public Catalogue getAgentCatalogue() {
        if (agentCatalogue == null) {
            // Measured against a scratch document with default options, so neither the active
            // tab nor its options and grid preference take part; it is dropped after the build.
            CircuitDocument scratch = CircuitDocument.createScratch(this);
            try {
                agentCatalogue = Catalogue.build(this, scratch);
            } finally {
                scratch.dispose();
            }
        }
        return agentCatalogue;
    }

    /**
     * [SP_SLV_01_01] The solver-mode session default: the stored preference {@code solverMode}
     * (absent or unrecognized reads as {@code AUTO}).
     */
    public SolverMode getSolverModeDefault() {
        if (solverModeDefault == null) {
            solverModeDefault = SolverMode.fromStored(OptionsManager.getOptionFromStorage(SOLVER_MODE_OPTION, null));
        }
        return solverModeDefault;
    }

    /**
     * [SP_SLV_02_09] Sets the solver-mode session default and persists it. Every open document
     * without an override whose effective mode changes gets a pending re-stamp (stamp only, no
     * re-analysis); a document in an agent run keeps its path until the run has ended.
     */
    public void setSolverModeDefault(SolverMode mode) {
        SolverMode old = getSolverModeDefault();
        OptionsManager.setOptionInStorage(SOLVER_MODE_OPTION, mode.wire);
        if (old == mode) {
            return;
        }
        solverModeDefault = mode;
        for (CircuitDocument doc : documentManager.getDocuments()) {
            if (doc.getSolverOverride() == null) {
                doc.requestSolverRestamp();
            }
        }
    }

    public void setCanvasSize(int width, int height) {
        width = Math.max(width, 0);
        height = Math.max(height, 0);
        renderer.setCanvasSize(width, height);
        renderer.setCircuitArea();
        // recenter circuit in case canvas was hidden at startup
        if (renderer.transform[0] == 0) {
            renderer.centreCircuit();
        }
    }

    public void setLastFileName(String s) {
        // remember filename for use when saving a new file.
        // if s is null or automatically generated then just clear out old filename.
        if (s == null || s.startsWith("circuitjs-"))
            activeDocument.circuitInfo.lastFileName = null;
        else
            activeDocument.circuitInfo.lastFileName = s;
    }

    public String getLastFileName() {
        Date date = new Date();
        String fname;
        if (activeDocument.circuitInfo.lastFileName != null)
            fname = activeDocument.circuitInfo.lastFileName;
        else {
            DateTimeFormat dtf = DateTimeFormat.getFormat("yyyyMMdd-HHmmss");
            fname = "circuitjs-" + dtf.format(date) + ".txt";
        }
        return fname;
    }

    public void setDeveloperMode(boolean enabled) {
        if (activeDocument.circuitInfo.developerMode == enabled) {
            return;
        }

        activeDocument.circuitInfo.developerMode = enabled;
    }

    /** Requests a deferred repaint of the bound document on the session canvas. */
    public void repaint() {
        renderer.repaint();
    }

    public void needAnalyze() {
        activeDocument.circuitInfo.dcAnalysisFlag = true; // Trigger analysis in next update

        // When simulation is stopped, the SimulationLoop doesn't run, so analysis-derived
        // draw state (like post/node markers) can get stale after edits/deletes.
        // Rebuild it immediately so the canvas reflects the current circuit.
        if (!activeDocument.isRunning()) {
            activeDocument.simulator.analyzeCircuit();
            activeDocument.circuitInfo.dcAnalysisFlag = false;
        }
        repaint();
        enableDisableMenuItems();
    }

    public void stop(String message, CircuitElm ce) {
        activeDocument.simulator.stop(message, ce);
    }

    public void stop() {
        setSimRunning(false);
        renderer.reset();
    }

    public void setSimRunning(boolean isRunning) {
        if (isRunning) {
            if (activeDocument.simulator.stopMessage != null)
                return;
            activeDocument.setSimRunning(true);
        } else {
            activeDocument.setSimRunning(false);
            renderer.repaint();
            // Ensure selection functionality works even when simulation is stopped
            activeDocument.circuitEditor.setMouseMode("Select");
        }
    }

    public boolean simIsRunning() {
        return activeDocument.isRunning();
    }

    public void resetAction() {
        renderer.needsAnalysis();

        CircuitSimulator simulator = activeDocument.simulator;
        
        // If we got here after a convergence/singularity stop, clear the stop/error state
        // so the user can reset and restart using the UI.
        activeDocument.clearError();
        simulator.clearStopState();

        // Drop any transient solver state to avoid leaking matrix/voltages across resets.
        simulator.resetSolverState();

        simulator.t = simulator.timeStepAccum = 0;
        simulator.timeStepCount = 0;
        for (int i = 0; i != simulator.elmList.size(); i++)
            simulator.elmList.get(i).reset();

        ScopeManager scopeManager = getActiveDocument().scopeManager;
        for (int i = 0; i != scopeManager.scopeCount; i++)
            scopeManager.scopes[i].resetGraph(true);

        // Rebuild analysis-derived state (important when stopped after an error).
        needAnalyze();
    }

    public void allowSave(boolean b) {
        if (menuManager.saveFileItem != null)
            menuManager.saveFileItem.setEnabled(b);
    }

    public void setUnsavedChanges(boolean hasChanges) {
        activeDocument.circuitInfo.setModified(hasChanges);
    }

    void setCircuitTitle(String s) {
        // TODO:
    }

    void enableDisableMenuItems() {
        boolean canFlipX = true;
        boolean canFlipY = true;
        boolean canFlipXY = true;
        int selCount = activeDocument.simulator.countSelected();
        for (CircuitElm elm : activeDocument.simulator.elmList)
            if (elm.isSelected() || selCount == 0) {
                if (!elm.canFlipX())
                    canFlipX = false;
                if (!elm.canFlipY())
                    canFlipY = false;
                if (!elm.canFlipXY())
                    canFlipXY = false;
            }
        menuManager.cutItem.setEnabled(selCount > 0);
        menuManager.copyItem.setEnabled(selCount > 0);
        menuManager.flipXItem.setEnabled(canFlipX);
        menuManager.flipYItem.setEnabled(canFlipY);
        menuManager.flipXYItem.setEnabled(canFlipXY);
    }

    /**
     * Refreshes the Undo/Redo items (enabled state and labels) from the visible tab's document.
     * [SP_AGA_03_08] R1: while {@code DocumentScope} has a background document bound, the
     * session menu keeps showing the visible document's history (an agent edit, checkpoint or
     * undo of the background document refreshes it from there, not from the bound one).
     */
    public void enableUndoRedo() {
        if (menuManager == null || menuManager.undoItem == null || menuManager.redoItem == null) {
            return;
        }
        UndoManager undoManager = menuDocument().undoManager;
        menuManager.redoItem.setEnabled(undoManager.hasRedoStack());
        menuManager.undoItem.setEnabled(undoManager.hasUndoStack());
        menuManager.updateUndoRedoLabels(undoManager.getUndoComment(), undoManager.getRedoComment());
    }

    /** @return the document whose state the session menus show: the bound one here */
    protected CircuitDocument menuDocument() {
        return getActiveDocument();
    }

    void enablePaste() {
        menuManager.pasteItem.setEnabled(clipboardManager.hasClipboardData());
    }

    boolean dialogIsShowing() {
        if (menuManager.contextPanel != null && menuManager.contextPanel.isShowing())
            return true;
        if (activeDocument.circuitEditor.scrollValuePopup != null
                && activeDocument.circuitEditor.scrollValuePopup.isShowing())
            return true;
        if (dialogManager.dialogIsShowing()) {
            return true;
        }

        return false;
    }

    /** @return the untranslated element menu label for a class name (e.g. "ResistorElm"), or null */
    public String getEnglishLabelForClass(String cls) {
        return menuManager.classToEnglishLabelMap.get(cls);
    }

    String getLabelTextForClass(String cls) {
        return menuManager.classToLabelMap.get(cls);
    }

    // For debugging
    void dumpNodelist() {
        CircuitElm e;
        int i, j;
        String s;
        String cs;

        log("Elm list Dump");
        for (i = 0; i < activeDocument.simulator.elmList.size(); i++) {
            e = activeDocument.simulator.elmList.get(i);
            cs = e.getDumpClass().toString();
            int p = cs.lastIndexOf('.');
            cs = cs.substring(p + 1);
            if (cs == "WireElm")
                continue;
            if (cs == "LabeledNodeElm")
                cs = cs + " " + ((LabeledNodeElm) e).text;
            if (cs == "TransistorElm") {
                if (((TransistorElm) e).pnp == -1)
                    cs = "PTransistorElm";
                else
                    cs = "NTransistorElm";
            }
            s = cs;
            for (j = 0; j < e.getPostCount(); j++) {
                s = s + " " + e.getNode(j);
            }
            log(s);
        }
    }

    public void log(String text) {
        logManager.addLogEntry(text);
    }

    void doDCAnalysis() {
        activeDocument.circuitInfo.dcAnalysisFlag = true;
        resetAction();
    }

    public double getIterCount() {
        return 0; // Stub
    }

    void createNewLoadFile() {
        CircuitInfo circuitInfo = activeDocument.circuitInfo;

        circuitInfo.filePath = loadFileInput.getPath();
        log("filePath: " + circuitInfo.filePath);

        circuitInfo.fileName = loadFileInput.getFileName();
        log("fileName: " + circuitInfo.fileName);

        if (circuitInfo.filePath != null) {
            allowSave(true);
        }
    }

    void setMouseElm(CircuitElm ce) {
        activeDocument.circuitEditor.setMouseElm(ce);
    }

}
