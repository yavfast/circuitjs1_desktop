package com.lushprojects.circuitjs1.client;

import com.google.gwt.core.client.GWT;
import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.ui.MenuBar;
import com.google.gwt.user.client.ui.MenuItem;
import com.lushprojects.circuitjs1.client.dialog.ControlsDialog;
import com.lushprojects.circuitjs1.client.element.AudioInputElm;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.DataInputElm;
import com.lushprojects.circuitjs1.client.util.Locale;

public class CircuitLoader extends BaseCirSimDelegate implements CircuitConst {

    protected CircuitLoader(BaseCirSim cirSim, CircuitDocument circuitDocument) {
        super(cirSim, circuitDocument);
    }

    public void readCircuit(String circuitData, int flags) {
        if ((flags & RC_RETAIN) == 0) {
            resetCircuitState();
        }

        boolean isSubcircuitMode = (flags & RC_SUBCIRCUITS) != 0;
        parseCircuitLines(circuitData, isSubcircuitMode, flags);

        finalizeCircuitLoading(flags);
    }

    /**
     * Public convenience method that calls readCircuit with default flags
     */
    public void readCircuit(String text) {
        readCircuit(text, 0);
    }

    /**
     * Resets circuit state to default values when not retaining current state
     */
    private void resetCircuitState() {
        CircuitSimulator simulator = simulator();
        CircuitEditor circuitEditor = circuitEditor();
        MenuManager menuManager = menuManager();
        ScopeManager scopeManager = scopeManager();

        // Clear existing elements
        circuitEditor().clearMouseElm();
        for (int elementIndex = 0; elementIndex != simulator().elmList.size(); elementIndex++) {
            CircuitElm circuitElement = simulator().elmList.get(elementIndex);
            circuitElement.delete();
        }

        // Reset simulation parameters
        simulator().t = simulator().timeStepAccum = 0;
        simulator().elmList.clear();
        getActiveDocument().adjustableManager.reset();
        cirSim.renderer.hintType = -1;
        simulator().maxTimeStep = 5e-6;
        simulator().minTimeStep = 50e-12;
        simulator().lastIterTime = 0;

        // Reset menu states
        menuManager.dotsCheckItem.setState(false);
        menuManager.smallGridCheckItem.setState(false);
        menuManager.powerCheckItem.setState(false);
        menuManager.voltsCheckItem.setState(true);
        menuManager.showValuesCheckItem.setState(true);

        // Reset UI components
        circuitEditor().setGrid();
        CirSim cirSim = (CirSim) this.cirSim;
        cirSim.timeStepBar.setValue(ControlsDialog.timeStepToPosition(5e-6));
        cirSim.controlsDialog.updateTimeStepLabel();
        cirSim.speedBar.setValue(117);
        cirSim.currentBar.setValue(50);
        cirSim.powerBar.setValue(50);
        CircuitElm.voltageRange = 5;
        scopeManager.scopeCount = 0;
    }

    /**
     * Parses circuit data line by line and creates circuit elements
     */
    private void parseCircuitLines(String circuitData, boolean isSubcircuitMode, int flags) {
        String[] lines = circuitData.split("[\r\n]+");

        for (String line : lines) {
            if (line.trim().isEmpty()) {
                continue;
            }

            StringTokenizer stringTokenizer = new StringTokenizer(line, " +\t\n\r\f");
            if (stringTokenizer.hasMoreTokens()) {
                processCircuitLine(stringTokenizer, isSubcircuitMode, flags);
            }
        }
    }

    /**
     * Processes a single line of circuit data
     */
    private void processCircuitLine(StringTokenizer stringTokenizer, boolean isSubcircuitMode, int flags) {
        String type = stringTokenizer.nextToken();
        // Convert digit characters to numbers
        int typeIdentifier = type.charAt(0);
        if (typeIdentifier >= '0' && typeIdentifier <= '9') {
            typeIdentifier = CircuitElm.parseInt(type);
        }

        try {
            if (isSubcircuitMode && typeIdentifier != '.') {
                return;
            }

            // Handle special circuit elements
            if (handleSpecialElements(stringTokenizer, typeIdentifier, flags)) {
                return;
            }

            // Handle model definitions
            if (handleModelDefinitions(stringTokenizer, typeIdentifier)) {
                return;
            }

            // Create standard circuit element
            createStandardCircuitElement(stringTokenizer, typeIdentifier);

        } catch (Exception exception) {
            CirSim.console("Exception while undumping: " + stringTokenizer.getOriginalString());
        }
    }

    /**
     * Handles special circuit elements (scopes, hints, options, etc.)
     */
    private boolean handleSpecialElements(StringTokenizer stringTokenizer, int typeIdentifier, int flags) {
        ScopeManager scopeManager = scopeManager();

        switch (typeIdentifier) {
            case 'o': // Scope
                Scope scope = new Scope(cirSim, getActiveDocument());
                scope.position = scopeManager.scopeCount;
                scope.undump(stringTokenizer);
                scopeManager.scopes[scopeManager.scopeCount++] = scope;
                return true;

            case 'h': // Hint
                readHint(stringTokenizer);
                return true;

            case '$': // Options
                readOptions(stringTokenizer, flags);
                return true;

            case '!': // Custom logic model
                CustomLogicModel.undumpModel(stringTokenizer);
                return true;

            case '%':
            case '?':
            case 'B':
                // ignore afilter-specific stuff
                return true;

            default:
                return false;
        }
    }

    /**
     * Handles model definitions (diode, transistor, adjustable, composite)
     */
    private boolean handleModelDefinitions(StringTokenizer stringTokenizer, int typeIdentifier) {
        switch (typeIdentifier) {
            case 34: // Diode model
                DiodeModel.undumpModel(stringTokenizer);
                return true;

            case 32: // Transistor model
                TransistorModel.undumpModel(stringTokenizer);
                return true;

            case 38: // Adjustable element
                getActiveDocument().adjustableManager.addAdjustable(stringTokenizer);
                return true;

            case '.': // Custom composite model
                CustomCompositeModel.undumpModel(stringTokenizer);
                return true;

            default:
                return false;
        }
    }

    /**
     * Creates a standard circuit element from parsed data
     */
    private void createStandardCircuitElement(StringTokenizer stringTokenizer, int typeIdentifier) {
        // Parse element coordinates and flags
        int startX = CircuitElm.parseInt(stringTokenizer.nextToken());
        int startY = CircuitElm.parseInt(stringTokenizer.nextToken());
        int endX = CircuitElm.parseInt(stringTokenizer.nextToken());
        int endY = CircuitElm.parseInt(stringTokenizer.nextToken());
        int elementFlags = CircuitElm.parseInt(stringTokenizer.nextToken());

        // Create the circuit element
        CircuitElm newCircuitElement = CircuitElmCreator.createCe(typeIdentifier, startX, startY, endX, endY,
                elementFlags, stringTokenizer);
        if (newCircuitElement == null) {
            CirSim.console("unrecognized dump type: " + stringTokenizer.getOriginalString());
            return;
        }

        // Parse description from remaining tokens
        CircuitElmCreator.readDescription(newCircuitElement, stringTokenizer);

        // Add element to simulation
        newCircuitElement.setCircuitDocument(getActiveDocument());
        newCircuitElement.setPoints();
        simulator().elmList.add(newCircuitElement);
    }

    /**
     * Finalizes circuit loading with post-processing steps
     */
    private void finalizeCircuitLoading(int flags) {
        CirSim cirSim = (CirSim) this.cirSim;
        cirSim.setPowerBarEnable();
        cirSim.enableItems();

        if ((flags & RC_RETAIN) == 0) {
            // create sliders as needed
            getActiveDocument().adjustableManager.createSliders();
        }

        cirSim.needAnalyze();

        if ((flags & RC_NO_CENTER) == 0) {
            renderer().centreCircuit();
        }

        if ((flags & RC_SUBCIRCUITS) != 0) {
            simulator().updateModels();
        }

        if ((flags & RC_KEEP_TITLE) == 0) {
            // TODO:
        }

        // Clear caches to save memory
        AudioInputElm.clearCache();
        DataInputElm.clearCache();

        cirSim.setSlidersDialogHeight();
    }

    void readHint(StringTokenizer st) {
        cirSim.renderer.hintType = CircuitElm.parseInt(st.nextToken());
        cirSim.renderer.hintItem1 = CircuitElm.parseInt(st.nextToken());
        cirSim.renderer.hintItem2 = CircuitElm.parseInt(st.nextToken());
    }

    void readOptions(StringTokenizer st, int importFlags) {
        CircuitSimulator simulator = simulator();
        CircuitEditor circuitEditor = circuitEditor();
        MenuManager menuManager = menuManager();
        CirSim cirSim = (CirSim) this.cirSim;

        int flags = CircuitElm.parseInt(st.nextToken());

        if ((importFlags & RC_RETAIN) != 0) {
            // need to set small grid if pasted circuit uses it
            if ((flags & 2) != 0)
                menuManager.smallGridCheckItem.setState(true);
            return;
        }

        menuManager.dotsCheckItem.setState((flags & 1) != 0);
        menuManager.smallGridCheckItem.setState((flags & 2) != 0);
        menuManager.voltsCheckItem.setState((flags & 4) == 0);
        menuManager.powerCheckItem.setState((flags & 8) == 8);
        menuManager.showValuesCheckItem.setState((flags & 16) == 0);
        
        simulator.adjustTimeStep = (flags & 64) != 0;
        simulator.maxTimeStep = simulator.timeStep = CircuitElm.parseDouble(st.nextToken());
        cirSim.timeStepBar.setValue(ControlsDialog.timeStepToPosition(simulator.maxTimeStep));
        cirSim.controlsDialog.updateTimeStepLabel();
        
        double sp = CircuitElm.parseDouble(st.nextToken());
        int sp2 = (int) (Math.log(10 * sp) * 24 + 61.5);
        // int sp2 = (int) (Math.log(sp)*24+1.5);
        cirSim.speedBar.setValue(sp2);
        cirSim.currentBar.setValue(CircuitElm.parseInt(st.nextToken()));
        CircuitElm.voltageRange = CircuitElm.parseDouble(st.nextToken());

        try {
            cirSim.powerBar.setValue(CircuitElm.parseInt(st.nextToken()));
            simulator.minTimeStep = CircuitElm.parseDouble(st.nextToken());
        } catch (Exception e) {
            // Ignore missing optional parameters
        }
        circuitEditor.setGrid();
    }

    public static void loadSetupList(CirSim cirSim, final boolean openDefault) {
        String url;
        url = GWT.getModuleBaseURL() + "setuplist.txt"; // +"?v="+random.nextInt();
        CirSim.console("Loading setup list from: " + url);
        RequestBuilder requestBuilder = new RequestBuilder(RequestBuilder.GET, url);
        try {
            requestBuilder.sendRequest(null, new RequestCallback() {
                public void onError(Request request, Throwable exception) {
                    Window.alert(Locale.LS("Can't load circuit list!"));
                    GWT.log("File Error Response", exception);
                }

                public void onResponseReceived(Request request, Response response) {
                    CirSim.console("Setup list response: " + response.getStatusCode());
                    // processing goes here
                    if (response.getStatusCode() == Response.SC_OK || response.getStatusCode() == 0) {
                        String text = response.getText();
                        if (text != null && !text.isEmpty()) {
                            processSetupList(cirSim, text, openDefault);
                        } else {
                            CirSim.console("Setup list empty");
                        }
                        // end or processing
                    } else {
                        Window.alert(Locale.LS("Can't load circuit list!"));
                        GWT.log("Bad file server response:" + response.getStatusText());
                    }
                }
            });
        } catch (RequestException e) {
            GWT.log("failed file reading", e);
        }
    }

    static void processSetupList(CirSim cirSim, String text, boolean openDefault) {
        MenuBar circuitsMenuBar = cirSim.menuManager.circuitsMenuBar;

        String[] lines = text.split("\r\n|\n|\r");
        MenuBar[] stack = new MenuBar[6];
        int stackptr = 0;
        stack[stackptr++] = circuitsMenuBar;
        
        for (String line : lines) {
            if (line.isEmpty() || line.charAt(0) == '#')
                continue;
            else if (line.charAt(0) == '+') {
                // MenuBar n = new Menu(line.substring(1));
                MenuBar n = new MenuBar(true);
                n.setAutoOpen(true);
                circuitsMenuBar.addItem(Locale.LS(line.substring(1)), n);
                circuitsMenuBar = stack[stackptr++] = n;
            } else if (line.charAt(0) == '-') {
                if (stackptr > 1)
                    circuitsMenuBar = stack[--stackptr - 1];
            } else {
                int i = line.indexOf(' ');
                if (i > 0) {
                    String title = Locale.LS(line.substring(i + 1));
                    boolean first = false;
                    if (line.charAt(0) == '>')
                        first = true;
                    String file = line.substring(first ? 1 : 0, i);
                    circuitsMenuBar.addItem(new MenuItem(title,
                            new MyCommand("circuits", "setup " + file + " " + title)));

                    // TODO:
                    CircuitInfo circuitInfo = cirSim.getActiveDocument().circuitInfo;
                    if (file.equals(circuitInfo.startCircuit) && circuitInfo.startLabel == null) {
                        circuitInfo.startLabel = title;
                        cirSim.setSlidersDialogHeight();
                    }
                    if (first && circuitInfo.startCircuit == null) {
                        circuitInfo.startCircuit = file;
                        circuitInfo.startLabel = title;
                        if (openDefault && cirSim.getActiveDocument().simulator.stopMessage == null)
                            cirSim.getActiveDocument().circuitLoader.readSetupFile(circuitInfo.startCircuit, circuitInfo.startLabel);
                    }
                }
            }
        }
    }

    void readSetupFile(String str, String title) {
        System.out.println(str);
        // don't avoid caching here, it's unnecessary and makes offline PWA's not work
        String url = GWT.getModuleBaseURL() + "circuits/" + str; // +"?v="+random.nextInt();
        loadFileFromURL(url);
        CirSim cirSim = (CirSim) this.cirSim;
        cirSim.setSlidersDialogHeight();
        circuitInfo().filePath = null;
        circuitInfo().fileName = null;
        cirSim.setUnsavedChanges(false);
    }

    void loadFileFromURL(String url) {
        RequestBuilder requestBuilder = new RequestBuilder(RequestBuilder.GET, url);

        try {
            requestBuilder.sendRequest(null, new RequestCallback() {
                public void onError(Request request, Throwable exception) {
                    Window.alert(Locale.LS("Can't load circuit!"));
                    GWT.log("File Error Response", exception);
                }

                public void onResponseReceived(Request request, Response response) {
                    if (response.getStatusCode() == Response.SC_OK || response.getStatusCode() == 0) {
                        String text = response.getText();
                        readCircuit(text, CircuitConst.RC_KEEP_TITLE);
                        CirSim cirSim = (CirSim) CircuitLoader.this.cirSim;
                        cirSim.allowSave(false);
                        circuitInfo().filePath = null;
                        circuitInfo().fileName = null;
                        cirSim.setUnsavedChanges(false);
                    } else {
                        Window.alert(Locale.LS("Can't load circuit!"));
                        GWT.log("Bad file server response:" + response.getStatusText());
                    }
                }
            });
        } catch (RequestException e) {
            GWT.log("failed file reading", e);
        }

    }

}
