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

import com.google.gwt.user.client.Window;
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
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.io.CircuitFormat;
import com.lushprojects.circuitjs1.client.io.CircuitImporter;
import com.lushprojects.circuitjs1.client.io.ImportLifecycle;
import com.lushprojects.circuitjs1.client.io.ImportReport;
import com.lushprojects.circuitjs1.client.io.ModelSpecCodec;

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

    /** Token delimiters of a circuit line (also used by the circuit-content test, SP_AGA_03_09). */
    public static final String DELIMITERS = " +\t\n\r\f";
    /** Line breaks the text is split on, one line each (also used by the circuit-content test). */
    public static final String LINE_BREAKS = "\r\n|\n|\r";

    private final TextCircuitFormat format;

    /** Report of the running import ([SP_AGA_03_04]); null for user loads. */
    private ImportReport report;
    /** 1-based number of the line being processed (for the report). */
    private int lineNumber;
    /** Elements of this load whose model name was unresolved when created, with their lines. */
    private final java.util.List<CircuitElm> unresolvedModels = new java.util.ArrayList<>();
    private final java.util.List<Integer> unresolvedLines = new java.util.ArrayList<>();

    public TextCircuitImporter(TextCircuitFormat format) {
        this.format = format;
    }

    @Override
    public void importCircuit(String data, CircuitDocument document, int flags) {
        importCircuit(data, document, flags, null);
    }

    @Override
    public void importCircuit(String data, CircuitDocument document, int flags, ImportReport report) {
        if (data == null || data.isEmpty()) {
            return;
        }
        this.report = report;
        if (report != null) {
            // [SP_AGA_03_04] logic entries that elements' fallbacks create are restored on rejection
            CustomLogicModel.beginFallbackRecording(report::addModelRestorer);
            DiodeModel.beginFallbackRecording(report::addModelRestorer);
        }
        try {
            importLines(data, document, flags);
        } finally {
            if (report != null) {
                CustomLogicModel.endFallbackRecording();
                DiodeModel.endFallbackRecording();
            }
            this.report = null;
        }
    }

    private void importLines(String data, CircuitDocument document, int flags) {

        // Reset circuit state unless retaining
        if ((flags & RC_RETAIN) == 0) {
            ImportLifecycle.resetCircuitState(document);
        }

        // Parse circuit data
        boolean isSubcircuitMode = (flags & RC_SUBCIRCUITS) != 0;
        unresolvedModels.clear();
        unresolvedLines.clear();
        parseCircuitLines(data, document, isSubcircuitMode, flags);
        // [SP_AGA_03_03] "Model names": a model defined anywhere in the content resolves; only
        // names neither the session nor the content define are reported (a report is given)
        java.util.List<CircuitElm> still = new java.util.ArrayList<>();
        java.util.List<Integer> stillLines = new java.util.ArrayList<>();
        java.util.List<String> stillNames = new java.util.ArrayList<>();
        for (int i = 0; i < unresolvedModels.size(); i++) {
            CircuitElm elm = unresolvedModels.get(i);
            String name = elm.getUnresolvedModelName();
            if (!elm.retryUnresolvedModel() && report != null) {
                still.add(elm);
                stillLines.add(unresolvedLines.get(i));
                stillNames.add(name);
            }
        }
        unresolvedModels.clear();
        unresolvedLines.clear();

        // Finalize loading
        ImportLifecycle.finalizeCircuitLoading(document, flags, report);

        // reported after the IDs are settled, so each item names its element
        for (int i = 0; i < still.size(); i++) {
            report.addUnresolvedModel("line " + stillLines.get(i), stillNames.get(i), stillLines.get(i),
                    still.get(i).getElementId());
        }
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
     * Parse circuit data line by line.
     */
    private void parseCircuitLines(String data, CircuitDocument document, 
                                   boolean isSubcircuitMode, int flags) {
        // Split on single line breaks so that reported line numbers match the source text
        String[] lines = data.split(LINE_BREAKS);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            lineNumber = i + 1;
            if (line.trim().isEmpty()) {
                continue;
            }

            StringTokenizer tokenizer = new StringTokenizer(line, DELIMITERS);
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
        
        // Convert digit characters to numbers (shared with the circuit-content test, SP_AGA_03_09)
        int typeId = CircuitElmCreator.dumpTypeId(type);

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
            CirSim.console("Exception while parsing: " + tokenizer.getOriginalString() + " (" + e + ")");
            // A line whose parsing throws counts as failed (SP_AGA_03_04)
            reportItem(ImportReport.ELEMENT_SKIPPED, ImportReport.Severity.ERROR,
                    "line " + lineNumber + ": the line could not be parsed");
        }
    }

    private void reportItem(String code, ImportReport.Severity severity, String message) {
        if (report != null) {
            report.addAtLine(code, severity, message, lineNumber);
        }
    }

    /**
     * Before a model line is undumped, records how to restore the model catalogue entry it names
     * as it is now ([SP_AGA_03_04] "Model catalogues"); only when a report is collected.
     */
    private void recordModelEntry(StringTokenizer tokenizer, int typeId) {
        if (report == null) {
            return;
        }
        StringTokenizer st = new StringTokenizer(tokenizer.getOriginalString(), DELIMITERS);
        st.nextToken(); // line type
        if (!st.hasMoreTokens()) {
            return;
        }
        String name = CustomLogicModel.unescape(st.nextToken());
        switch (typeId) {
            case 34:
                report.addModelRestorer(DiodeModel.entryRestorer(name));
                break;
            case 32:
                report.addModelRestorer(TransistorModel.entryRestorer(name));
                break;
            case '!':
                report.addModelRestorer(CustomLogicModel.entryRestorer(name));
                break;
            case '.':
                report.addModelRestorer(CustomCompositeModel.entryRestorer(name));
                break;
            default:
                break;
        }
    }

    /**
     * [SP_AGA_03_11] "Create-only names" on a report that asks for them (agent
     * {@code importCircuit}): a model line whose name exists in its catalogue never overwrites the
     * entry — it is a no-op when the line it produces equals the entry's model line, and a
     * {@code name_taken} error item otherwise (always for an internal entry). A line whose fields
     * do not parse throws, which fails the line.
     *
     * @return true when the line must not be undumped
     */
    private boolean keepExistingModel(StringTokenizer tokenizer, int typeId) {
        if (report == null || !report.isCreateOnlyModels()) {
            return false;
        }
        String kind = ModelSpecCodec.kindOfLineType(typeId);
        StringTokenizer st = new StringTokenizer(tokenizer.getOriginalString(), DELIMITERS);
        st.nextToken(); // line type
        String name = CustomLogicModel.unescape(st.nextToken());
        Object entry = ModelSpecCodec.entry(kind, name);
        // an entry an element line of this content created (a CustomLogic fallback, a legacy
        // fwdrop diode) is not a session entry: the model line defines it as usual
        if (entry == null || ModelSpecCodec.isFallbackName(kind, name)) {
            return false;
        }
        String line = ModelSpecCodec.normalizedLine(kind, name, st);
        if (!ModelSpecCodec.isInternal(entry) && line.equals(ModelSpecCodec.lineOf(entry))) {
            return true;
        }
        reportItem(ImportReport.NAME_TAKEN, ImportReport.Severity.ERROR, "line " + lineNumber + ": the " + kind
                + " model '" + name + "' exists in the session with a different definition");
        return true;
    }

    /**
     * [SP_AGA_06_01] item 25 / [SP_AGA_03_11] "No dialogs": a logic model line whose rules do not
     * parse loads as before (the rules before the bad line apply). A user load alerts the parser's
     * message as before; an agent path never alerts — an agent load carries a report (an error on
     * {@code importCircuit} content: the import is rejected and the entry restored; a warning on
     * {@code openFile}), and agent mutations, undo/redo and rollbacks mark the document agent
     * origin. Either way the problem goes to the console.
     */
    private void reportRuleError(StringTokenizer tokenizer, CircuitDocument document, String error) {
        if (error == null) {
            return;
        }
        if (report == null && (document == null || !document.isAgentOrigin())) {
            // the editor's behaviour for user loads (file open, paste, import, session restore, undo/redo)
            Window.alert(error);
            return;
        }
        StringTokenizer st = new StringTokenizer(tokenizer.getOriginalString(), DELIMITERS);
        st.nextToken(); // line type
        String name = st.hasMoreTokens() ? CustomLogicModel.unescape(st.nextToken()) : "";
        CustomLogicModel model = CustomLogicModel.findEntry(name);
        int ruleLine = model == null ? -1 : model.getRuleErrorLine();
        String message = "line " + lineNumber + ": the rules of logic model '" + name + "' do not parse"
                + (ruleLine >= 0 ? " at rule line " + (ruleLine + 1) : "") + " (" + error + ")";
        CirSim.console("Text import: " + message);
        if (report != null) {
            report.addModelLineProblem(message, "Fix the rule line: left=right, one left character per input "
                    + "(0 1 ? + - or a pattern letter) up to one per pin, one right character per output (0 1 _ or a pattern letter).",
                    lineNumber);
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
                int scopeCount = scopeManager.getScopeCount();
                if (scopeCount >= scopeManager.getMaxScopes()) {
                    CirSim.console("Text import: ignoring scope beyond the limit of " + scopeManager.getMaxScopes());
                    reportItem(ImportReport.SCOPE_LIMIT, ImportReport.Severity.WARNING,
                            "line " + lineNumber + ": scope beyond the limit of " + scopeManager.getMaxScopes() + " ignored");
                    return true;
                }
                Scope scope = new Scope(cirSim, document);
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
                if (keepExistingModel(tokenizer, typeId)) {
                    return true;
                }
                recordModelEntry(tokenizer, typeId);
                reportRuleError(tokenizer, document, CustomLogicModel.undumpModel(tokenizer));
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
                if (keepExistingModel(tokenizer, typeId)) {
                    return true;
                }
                recordModelEntry(tokenizer, typeId);
                DiodeModel.undumpModel(tokenizer);
                return true;

            case 32: // Transistor model
                if (keepExistingModel(tokenizer, typeId)) {
                    return true;
                }
                recordModelEntry(tokenizer, typeId);
                TransistorModel.undumpModel(tokenizer);
                return true;

            case 38: // Adjustable element
                document.adjustableManager.addAdjustable(tokenizer);
                return true;

            case '.': // Custom composite model
                if (keepExistingModel(tokenizer, typeId)) {
                    return true;
                }
                recordModelEntry(tokenizer, typeId);
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
            reportItem(ImportReport.ELEMENT_SKIPPED, ImportReport.Severity.ERROR,
                    "line " + lineNumber + ": unknown element type");
            return;
        }

        // [SP_AGA_03_03] "Model names": decided after the last line (a model line may follow)
        if (report != null && element.getUnresolvedModelName() != null) {
            unresolvedModels.add(element);
            unresolvedLines.add(lineNumber);
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
        double maxStep = CircuitElm.parseDouble(tokenizer.nextToken());
        if (!(maxStep > 0) || Double.isInfinite(maxStep)) {
            // a missing, garbled, non-positive or infinite step would never advance time; the bar
            // command used to overwrite it, so it falls back to the blank-circuit default instead
            CirSim.console("Text import: ignoring invalid time step " + maxStep + "; using 5 us");
            maxStep = 5e-6;
        }
        simulator.maxTimeStep = simulator.timeStep = maxStep;
        // [SP_AGA_06_01 item 18] the bar shows the nearest position without running its command,
        // which would re-quantise the file's own step to the bar's table (audit BL-D01)
        cirSim.timeStepBar.setValueWithoutCommand(ControlsDialog.timeStepToPosition(simulator.maxTimeStep));
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

    @Override
    public CircuitFormat getFormat() {
        return format;
    }
}
