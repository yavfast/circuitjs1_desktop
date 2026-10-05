/*
    Copyright (C) Paul Falstad and Iain Sharp

    This file is part of CircuitJS1.

    CircuitJS1 is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 2 of the License, or
    (at your option) any later version.

    CircuitJS1 is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with CircuitJS1.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.lushprojects.circuitjs1.client.io;

import com.google.gwt.user.client.Window;
import com.lushprojects.circuitjs1.client.CircuitConst;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.CircuitEditor;
import com.lushprojects.circuitjs1.client.CircuitRenderer;
import com.lushprojects.circuitjs1.client.CircuitSimulator;
import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.ColorSettings;
import com.lushprojects.circuitjs1.client.MenuManager;
import com.lushprojects.circuitjs1.client.ScopeManager;
import com.lushprojects.circuitjs1.client.dialog.ControlsDialog;
import com.lushprojects.circuitjs1.client.element.AudioInputElm;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.DataInputElm;

/**
 * Format-independent steps of loading a circuit into a document: reset before parsing
 * and post-processing after it. Every {@link CircuitImporter} calls both so that text
 * and JSON loads leave the document in the same state.
 */
public final class ImportLifecycle {

    private ImportLifecycle() {
    }

    /**
     * [SP_AGA_06_01] item 25 / [SP_AGA_03_11] "No dialogs": what a text or JSON import does with
     * the parser message of a logic model whose rules do not parse. A user load (file open, paste,
     * import dialog, session restore, undo/redo — no import report and not agent origin) alerts it,
     * as the editor always did; an agent path never alerts (the caller reports it).
     *
     * @return true when the message was alerted (a user load); false when the caller reports it
     */
    public static boolean alertRuleErrorOnUserLoad(CircuitDocument document, ImportReport report, String error) {
        if (report == null && (document == null || !document.isAgentOrigin())) {
            Window.alert(error);
            return true;
        }
        return false;
    }

    /**
     * Reset circuit state to default values.
     */
    public static void resetCircuitState(CircuitDocument document) {
        CirSim cirSim = document.getCirSim();
        CircuitSimulator simulator = document.simulator;
        CircuitEditor circuitEditor = document.circuitEditor;
        MenuManager menuManager = cirSim.menuManager;
        ScopeManager scopeManager = document.scopeManager;
        CircuitRenderer renderer = document.getRenderer();

        // [SP_AGA_04_01] A content replacement by the user (load, import, new blank circuit) seals
        // an open agent transaction first. Not for an undo/redo restore (the undo sealed already)
        // nor for an agent mutation's own import or rollback (agent origin).
        if (!document.isRestoringElementIds() && !document.isAgentOrigin()) {
            // [SP_AGA_04_02] a user content replacement ends an agent run of the document first
            document.cancelAgentRun();
            if (document.undoManager.sealTransaction()) {
                // the Undo item names the sealed entry at once (it read the open transaction as
                // plain "Undo"; a later refresh would change the visible label unprompted)
                cirSim.enableUndoRedo();
            }
        }

        // Clear any previous simulation stop/error so the newly loaded circuit can run.
        document.clearError();
        simulator.clearStopState();

        // Clear existing elements
        circuitEditor.clearMouseElm();
        for (int i = 0; i < simulator.elmList.size(); i++) {
            CircuitElm element = simulator.elmList.get(i);
            element.delete();
        }

        // Reset simulation parameters
        simulator.t = simulator.timeStepAccum = 0;
        simulator.elmList.clear();
        // [SP_AGA_03_02] Content replacement starts a new ID lifetime (counters reset); an
        // undo/redo restore keeps the current one.
        if (!document.isRestoringElementIds()) {
            document.resetElementIds();
            // [SP_AGA_01_12] Replacing the content clears the open-mark set; an undo/redo restore
            // puts the snapshot's marks back after loading.
            document.clearOpenMarks();
        }
        document.adjustableManager.reset();
        renderer.setHintType(-1);
        simulator.maxTimeStep = 5e-6;
        simulator.minTimeStep = 50e-12;
        simulator.lastIterTime = 0;

        // Reset menu states
        menuManager.dotsCheckItem.setState(false);
        menuManager.smallGridCheckItem.setState(false);
        menuManager.powerCheckItem.setState(false);
        menuManager.voltsCheckItem.setState(true);
        menuManager.showValuesCheckItem.setState(true);

        // Reset UI components
        circuitEditor.setGrid();
        cirSim.timeStepBar.setValue(ControlsDialog.timeStepToPosition(5e-6));
        cirSim.controlsDialog.updateTimeStepLabel();
        cirSim.speedBar.setValue(117);
        cirSim.currentBar.setValue(50);
        cirSim.powerBar.setValue(50);
        ColorSettings.get().setVoltageRange(5);
        scopeManager.setScopeCount(0);
    }

    /**
     * Finalize circuit loading with post-processing.
     */
    public static void finalizeCircuitLoading(CircuitDocument document, int flags) {
        finalizeCircuitLoading(document, flags, null);
    }

    /**
     * Finalize circuit loading with post-processing; ID warnings also go to {@code report}
     * ([SP_AGA_03_04], may be null).
     */
    public static void finalizeCircuitLoading(CircuitDocument document, int flags, ImportReport report) {
        CirSim cirSim = document.getCirSim();
        CircuitSimulator simulator = document.simulator;
        CircuitRenderer renderer = document.getRenderer();

        // [SP_AGA_03_02] Every element gets its ID before anything else can ask for one:
        // supplied (JSON keys) and restored (undo) IDs raise their counters first, then elements
        // without one get generated IDs in element order (text lines, pastes, auto-wires).
        // The ids_regenerated warnings are logged and, for a reporting caller, returned too.
        for (String warning : document.settleElementIds()) {
            CirSim.console("[WARN] ids_regenerated: " + warning);
            if (report != null) {
                report.addForKey(ImportReport.IDS_REGENERATED, ImportReport.Severity.WARNING, warning,
                        null);
            }
        }

        cirSim.setPowerBarEnable();
        cirSim.enableItems();

        // Create sliders for adjustable elements. Also on paste (RC_RETAIN): pasted elements with a
        // built-in slider or control button need theirs; createSliders() rebuilds existing rows.
        document.adjustableManager.createSliders();

        cirSim.needAnalyze();

        if ((flags & CircuitConst.RC_NO_CENTER) == 0) {
            renderer.centreCircuit();
        }

        if ((flags & CircuitConst.RC_SUBCIRCUITS) != 0) {
            simulator.updateModels();
        }

        // Clear caches to save memory
        AudioInputElm.clearCache();
        DataInputElm.clearCache();

        cirSim.setSlidersDialogHeight();
    }
}
