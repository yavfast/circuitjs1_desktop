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
import com.lushprojects.circuitjs1.client.CircuitEditor;
import com.lushprojects.circuitjs1.client.CircuitElmCreator;
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
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.TransistorModel;
import com.lushprojects.circuitjs1.client.dialog.ControlsDialog;
import com.lushprojects.circuitjs1.client.element.AudioInputElm;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.DataInputElm;
import com.lushprojects.circuitjs1.client.io.CircuitFormat;
import com.lushprojects.circuitjs1.client.io.CircuitImporter;

/**
 * Imports circuit from the original CircuitJS1 text format.
 * Parses lines representing simulation options, model definitions, and circuit elements.
 * 
 * Format structure:
 * - Line 1: Options header starting with '$'
 * - Model definitions (!, 34, 32, 38, .)
 * - Circuit elements (type x y x2 y2 flags [params] [# description])
 * - Scope configurations ('o')
 * - Adjustable elements
 * - Hints ('h')
 */
public class TextCircuitImporter implements CircuitImporter {

    private final TextCircuitFormat format;

    public TextCircuitImporter(TextCircuitFormat format) {
        this.format = format;
    }

    @Override
    public void importCircuit(String data, CircuitDocument document, int flags) {
        if (data == null || data.isEmpty()) {
            return;
        }

        // Reset circuit state unless retaining
        if ((flags & RC_RETAIN) == 0) {
            resetCircuitState(document);
        }

        // Parse circuit data
        boolean isSubcircuitMode = (flags & RC_SUBCIRCUITS) != 0;
        parseCircuitLines(data, document, isSubcircuitMode, flags);

        // Finalize loading
        finalizeCircuitLoading(document, flags);
    }

    @Override
    public boolean canImport(String data) {
        if (data == null || data.isEmpty()) {
            return false;
        }
        
        // Text format starts with '$' (options line) or element type
        String trimmed = data.trim();
        if (trimmed.startsWith("$")) {
            return true;
        }
        
        // Check if first non-empty line looks like an element
        String[] lines = trimmed.split("[\r\n]+", 2);
        if (lines.length > 0) {
            String firstLine = lines[0].trim();
            // Element lines start with a letter or number
            if (!firstLine.isEmpty()) {
                char firstChar = firstLine.charAt(0);
                // Valid element types: letters (a-z, A-Z) or numbers
                return Character.isLetter(firstChar) || Character.isDigit(firstChar);
            }
        }
        
        return false;
    }

    /**
     * Reset circuit state to default values.
     */
    private void resetCircuitState(CircuitDocument document) {
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
     * Parse circuit data line by line.
     */
    private void parseCircuitLines(String data, CircuitDocument document, 
                                   boolean isSubcircuitMode, int flags) {
        String[] lines = data.split("[\r\n]+");

        for (String line : lines) {
            if (line.trim().isEmpty()) {
                continue;
            }

            StringTokenizer tokenizer = new StringTokenizer(line, " +\t\n\r\f");
            if (tokenizer.hasMoreTokens()) {
                processCircuitLine(tokenizer, document, isSubcircuitMode, flags);
            }
        }
    }

    /**
     * Process a single line of circuit data.
     */
    private void processCircuitLine(StringTokenizer tokenizer, CircuitDocument document,
                                    boolean isSubcircuitMode, int flags) {
        String type = tokenizer.nextToken();
        
        // Convert digit characters to numbers
        int typeId = type.charAt(0);
        if (typeId >= '0' && typeId <= '9') {
            typeId = CircuitElm.parseInt(type);
        }

        try {
            // In subcircuit mode, only process composite model definitions
            if (isSubcircuitMode && typeId != '.') {
                return;
            }

            // Handle special elements (scopes, hints, options)
            if (handleSpecialElements(tokenizer, document, typeId, flags)) {
                return;
            }

            // Handle model definitions
            if (handleModelDefinitions(tokenizer, document, typeId)) {
                return;
            }

            // Create standard circuit element
            createStandardElement(tokenizer, document, typeId);

        } catch (Exception e) {
            CirSim.console("Exception while parsing: " + tokenizer.getOriginalString());
        }
    }

    /**
     * Handle special circuit elements (scopes, hints, options).
     */
    private boolean handleSpecialElements(StringTokenizer tokenizer, CircuitDocument document,
                                          int typeId, int flags) {
        ScopeManager scopeManager = document.scopeManager;
        CirSim cirSim = document.getCirSim();

        switch (typeId) {
            case 'o': // Scope
                Scope scope = new Scope(cirSim, document);
                int scopeCount = scopeManager.getScopeCount();
                scope.position = scopeCount;
                scope.undump(tokenizer);
                scopeManager.setScope(scopeCount, scope);
                scopeManager.setScopeCount(scopeCount + 1);
                return true;

            case 'h': // Hint
                readHint(tokenizer, document);
                return true;

            case '$': // Options
                readOptions(tokenizer, document, flags);
                return true;

            case '!': // Custom logic model
                CustomLogicModel.undumpModel(tokenizer);
                return true;

            case '%':
            case '?':
            case 'B':
                // Ignore afilter-specific data
                return true;

            default:
                return false;
        }
    }

    /**
     * Handle model definitions (diode, transistor, adjustable, composite).
     */
    private boolean handleModelDefinitions(StringTokenizer tokenizer, CircuitDocument document,
                                           int typeId) {
        switch (typeId) {
            case 34: // Diode model
                DiodeModel.undumpModel(tokenizer);
                return true;

            case 32: // Transistor model
                TransistorModel.undumpModel(tokenizer);
                return true;

            case 38: // Adjustable element
                document.adjustableManager.addAdjustable(tokenizer);
                return true;

            case '.': // Custom composite model
                CustomCompositeModel.undumpModel(tokenizer);
                return true;

            default:
                return false;
        }
    }

    /**
     * Create a standard circuit element from parsed data.
     */
    private void createStandardElement(StringTokenizer tokenizer, CircuitDocument document,
                                       int typeId) {
        // Parse element coordinates and flags
        int startX = CircuitElm.parseInt(tokenizer.nextToken());
        int startY = CircuitElm.parseInt(tokenizer.nextToken());
        int endX = CircuitElm.parseInt(tokenizer.nextToken());
        int endY = CircuitElm.parseInt(tokenizer.nextToken());
        int elementFlags = CircuitElm.parseInt(tokenizer.nextToken());

        // Create the circuit element
        CircuitElm element = CircuitElmCreator.createCe(
                document, typeId, startX, startY, endX, endY, elementFlags, tokenizer);
        
        if (element == null) {
            CirSim.console("Unrecognized element type: " + tokenizer.getOriginalString());
            return;
        }

        // Parse description from remaining tokens
        CircuitElmCreator.readDescription(element, tokenizer);

        // Initialize element with document (important for elements that override setCircuitDocument
        // to initialize internal components like diodes in MosfetElm)
        element.setCircuitDocument(document);

        // Add element to simulation
        element.setPoints();
        document.simulator.elmList.add(element);
    }

    /**
     * Read hint from tokenizer.
     */
    private void readHint(StringTokenizer tokenizer, CircuitDocument document) {
        CircuitRenderer renderer = document.getRenderer();
        renderer.setHintType(CircuitElm.parseInt(tokenizer.nextToken()));
        renderer.setHintItem1(CircuitElm.parseInt(tokenizer.nextToken()));
        renderer.setHintItem2(CircuitElm.parseInt(tokenizer.nextToken()));
    }

    /**
     * Read simulation options from tokenizer.
     */
    private void readOptions(StringTokenizer tokenizer, CircuitDocument document, int importFlags) {
        CirSim cirSim = document.getCirSim();
        CircuitSimulator simulator = document.simulator;
        CircuitEditor circuitEditor = document.circuitEditor;
        MenuManager menuManager = cirSim.menuManager;

        int flags = CircuitElm.parseInt(tokenizer.nextToken());

        // When retaining, only update small grid setting
        if ((importFlags & RC_RETAIN) != 0) {
            if ((flags & 2) != 0) {
                menuManager.smallGridCheckItem.setState(true);
            }
            return;
        }

        // Apply all options
        menuManager.dotsCheckItem.setState((flags & 1) != 0);
        menuManager.smallGridCheckItem.setState((flags & 2) != 0);
        menuManager.voltsCheckItem.setState((flags & 4) == 0);
        menuManager.powerCheckItem.setState((flags & 8) == 8);
        menuManager.showValuesCheckItem.setState((flags & 16) == 0);

        simulator.adjustTimeStep = (flags & 64) != 0;
        simulator.maxTimeStep = simulator.timeStep = CircuitElm.parseDouble(tokenizer.nextToken());
        cirSim.timeStepBar.setValue(ControlsDialog.timeStepToPosition(simulator.maxTimeStep));
        cirSim.controlsDialog.updateTimeStepLabel();

        double sp = CircuitElm.parseDouble(tokenizer.nextToken());
        int sp2 = (int) (Math.log(10 * sp) * 24 + 61.5);
        cirSim.speedBar.setValue(sp2);
        cirSim.currentBar.setValue(CircuitElm.parseInt(tokenizer.nextToken()));
        ColorSettings.get().setVoltageRange(CircuitElm.parseDouble(tokenizer.nextToken()));

        try {
            cirSim.powerBar.setValue(CircuitElm.parseInt(tokenizer.nextToken()));
            simulator.minTimeStep = CircuitElm.parseDouble(tokenizer.nextToken());
        } catch (Exception e) {
            // Ignore missing optional parameters
        }

        circuitEditor.setGrid();
    }

    /**
     * Finalize circuit loading with post-processing.
     */
    private void finalizeCircuitLoading(CircuitDocument document, int flags) {
        CirSim cirSim = document.getCirSim();
        CircuitSimulator simulator = document.simulator;
        CircuitRenderer renderer = document.getRenderer();

        cirSim.setPowerBarEnable();
        cirSim.enableItems();

        if ((flags & RC_RETAIN) == 0) {
            // Create sliders for adjustable elements
            document.adjustableManager.createSliders();
        }

        cirSim.needAnalyze();

        if ((flags & RC_NO_CENTER) == 0) {
            renderer.centreCircuit();
        }

        if ((flags & RC_SUBCIRCUITS) != 0) {
            simulator.updateModels();
        }

        // Clear caches to save memory
        AudioInputElm.clearCache();
        DataInputElm.clearCache();

        cirSim.setSlidersDialogHeight();
    }

    @Override
    public CircuitFormat getFormat() {
        return format;
    }
}
