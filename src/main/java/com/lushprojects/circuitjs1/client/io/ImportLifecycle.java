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
     * Reset circuit state to default values.
     */
    public static void resetCircuitState(CircuitDocument document) {
        CirSim cirSim = document.getCirSim();
        CircuitSimulator simulator = document.simulator;
        CircuitEditor circuitEditor = document.circuitEditor;
        MenuManager menuManager = cirSim.menuManager;
        ScopeManager scopeManager = document.scopeManager;
        CircuitRenderer renderer = document.getRenderer();

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
        CirSim cirSim = document.getCirSim();
        CircuitSimulator simulator = document.simulator;
        CircuitRenderer renderer = document.getRenderer();

        cirSim.setPowerBarEnable();
        cirSim.enableItems();

        if ((flags & CircuitConst.RC_RETAIN) == 0) {
            // Create sliders for adjustable elements
            document.adjustableManager.createSliders();
        }

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
