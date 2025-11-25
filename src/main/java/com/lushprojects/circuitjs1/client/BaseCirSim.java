package com.lushprojects.circuitjs1.client;

import com.google.gwt.i18n.client.DateTimeFormat;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.LabeledNodeElm;
import com.lushprojects.circuitjs1.client.element.TransistorElm;

import java.util.Date;

public class BaseCirSim {

    public final LogManager logManager = new LogManager(this);
    public final CircuitRenderer renderer = new CircuitRenderer(this);
    public final ClipboardManager clipboardManager = new ClipboardManager(this);
    public final DialogManager dialogManager = new DialogManager(this);
    public final MenuManager menuManager = new MenuManager(this);
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

    public CircuitDocument getActiveDocument() {
        return activeDocument;
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

    void repaint() {
        renderer.repaint();
    }

    public void needAnalyze() {
        activeDocument.circuitInfo.dcAnalysisFlag = true; // Trigger analysis in next update
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
        simulator.t = simulator.timeStepAccum = 0;
        simulator.timeStepCount = 0;
        for (int i = 0; i != simulator.elmList.size(); i++)
            simulator.elmList.get(i).reset();

        ScopeManager scopeManager = getActiveDocument().scopeManager;
        for (int i = 0; i != scopeManager.scopeCount; i++)
            scopeManager.scopes[i].resetGraph(true);

        repaint();
    }

    public void allowSave(boolean b) {
        if (menuManager.saveFileItem != null)
            menuManager.saveFileItem.setEnabled(b);
    }

    public void setUnsavedChanges(boolean hasChanges) {
        activeDocument.circuitInfo.unsavedChanges = hasChanges;
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

    void enableUndoRedo() {
        UndoManager undoManager = getActiveDocument().undoManager;
        menuManager.redoItem.setEnabled(undoManager.hasRedoStack());
        menuManager.undoItem.setEnabled(undoManager.hasUndoStack());
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

    String getLabelTextForClass(String cls) {
        return menuManager.classToLabelMap.get(cls);
    }

    // For debugging
    void dumpNodelist() {
        CircuitNode nd;
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
                s = s + " " + e.nodes[j];
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
