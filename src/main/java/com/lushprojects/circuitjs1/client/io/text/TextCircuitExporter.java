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

package com.lushprojects.circuitjs1.client.io.text;

import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.CircuitRenderer;
import com.lushprojects.circuitjs1.client.CircuitSimulator;
import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.ColorSettings;
import com.lushprojects.circuitjs1.client.CustomCompositeModel;
import com.lushprojects.circuitjs1.client.CustomLogicModel;
import com.lushprojects.circuitjs1.client.DiodeModel;
import com.lushprojects.circuitjs1.client.MenuManager;
import com.lushprojects.circuitjs1.client.Scope;
import com.lushprojects.circuitjs1.client.ScopeManager;
import com.lushprojects.circuitjs1.client.TransistorModel;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.io.CircuitExporter;
import com.lushprojects.circuitjs1.client.io.CircuitFormat;

import java.util.List;

/**
 * Exports circuit in the original CircuitJS1 text format.
 * Each line represents either a simulation option, model definition, or circuit element.
 * 
 * Format structure:
 * - Line 1: Options header starting with '$'
 * - Model definitions (!, 34, 32, 38, .)
 * - Circuit elements (type x y x2 y2 flags [params] [# description])
 * - Scope configurations ('o')
 * - Adjustable elements
 * - Hints ('h')
 */
public class TextCircuitExporter implements CircuitExporter {

    private final TextCircuitFormat format;

    public TextCircuitExporter(TextCircuitFormat format) {
        this.format = format;
    }

    @Override
    public String export(CircuitDocument document) {
        // Clear model dump flags to ensure all models are exported
        CustomLogicModel.clearDumpedFlags();
        CustomCompositeModel.clearDumpedFlags();
        DiodeModel.clearDumpedFlags();
        TransistorModel.clearDumpedFlags();

        StringBuilder dump = new StringBuilder(4096);

        // 1. Export simulation options header
        dump.append(dumpOptions(document)).append("\n");

        // 2. Export elements with their models
        CircuitSimulator simulator = document.simulator;
        for (int i = 0; i < simulator.elmList.size(); i++) {
            CircuitElm ce = simulator.elmList.get(i);
            
            // Export model if element has one
            String modelDump = ce.dumpModel();
            if (modelDump != null && !modelDump.isEmpty()) {
                dump.append(modelDump).append("\n");
            }
            
            // Export element
            dump.append(CircuitElm.dumpElm(ce)).append("\n");
        }

        // 3. Export scope configurations
        ScopeManager scopeManager = document.scopeManager;
        for (int i = 0; i < scopeManager.getScopeCount(); i++) {
            Scope scope = scopeManager.getScope(i);
            String scopeDump = scope.dump();
            if (scopeDump != null) {
                dump.append(scopeDump).append("\n");
            }
        }

        // 4. Export adjustable elements
        String adjustableDump = document.adjustableManager.dump();
        if (adjustableDump != null && !adjustableDump.isEmpty()) {
            dump.append(adjustableDump);
        }

        // 5. Export hints
        CircuitRenderer renderer = document.getRenderer();
        if (renderer.getHintType() != -1) {
            dump.append(dumpHint(renderer)).append("\n");
        }

        return dump.toString();
    }

    @Override
    public String exportSelection(CircuitDocument document, List<CircuitElm> selection) {
        if (selection == null || selection.isEmpty()) {
            return "";
        }

        // Clear model dump flags
        CustomLogicModel.clearDumpedFlags();
        CustomCompositeModel.clearDumpedFlags();
        DiodeModel.clearDumpedFlags();
        TransistorModel.clearDumpedFlags();

        StringBuilder dump = new StringBuilder(2048);

        // Export only selected elements
        for (CircuitElm ce : selection) {
            // Export model if element has one
            String modelDump = ce.dumpModel();
            if (modelDump != null && !modelDump.isEmpty()) {
                dump.append(modelDump).append("\n");
            }

            // Export element
            dump.append(CircuitElm.dumpElm(ce)).append("\n");
        }

        return dump.toString();
    }

    /**
     * Dump simulation options in text format.
     * Format: $ flags maxTimeStep iterCount currentBar voltageRange powerBar minTimeStep
     */
    private String dumpOptions(CircuitDocument document) {
        CirSim cirSim = document.getCirSim();
        MenuManager menuManager = cirSim.menuManager;
        CircuitSimulator simulator = document.simulator;

        // Build flags bitmask
        int flags = 0;
        flags |= menuManager.dotsCheckItem.getState() ? 1 : 0;      // Bit 0: Show dots
        flags |= menuManager.smallGridCheckItem.getState() ? 2 : 0; // Bit 1: Small grid
        flags |= menuManager.voltsCheckItem.getState() ? 0 : 4;     // Bit 2: Hide volts (inverted)
        flags |= menuManager.powerCheckItem.getState() ? 8 : 0;     // Bit 3: Show power
        flags |= menuManager.showValuesCheckItem.getState() ? 0 : 16; // Bit 4: Hide values (inverted)
        // Bit 5 (32): Linear scale in afilter (not used here)
        flags |= simulator.adjustTimeStep ? 64 : 0;                 // Bit 6: Auto time step

        return CircuitElm.dumpValues(
                "$",
                flags,
                simulator.maxTimeStep,
                cirSim.getIterCount(),
                cirSim.currentBar.getValue(),
                ColorSettings.get().getVoltageRange(),
                cirSim.powerBar.getValue(),
                simulator.minTimeStep
        );
    }

    /**
     * Dump hint in text format.
     * Format: h hintType hintItem1 hintItem2
     */
    private String dumpHint(CircuitRenderer renderer) {
        return CircuitElm.dumpValues(
                "h",
                renderer.getHintType(),
                renderer.getHintItem1(),
                renderer.getHintItem2()
        );
    }

    @Override
    public CircuitFormat getFormat() {
        return format;
    }
}
