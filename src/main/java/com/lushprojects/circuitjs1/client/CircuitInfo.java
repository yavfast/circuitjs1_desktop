package com.lushprojects.circuitjs1.client;

public class CircuitInfo extends BaseCirSimDelegate {

    /** Path to the current circuit file */
    String filePath;
    /** Name of the current circuit file */
    String fileName;
    /** Name of the last saved file */
    String lastFileName;

    /** Flag indicating if there are unsaved changes */
    public boolean unsavedChanges;
    /** Flag indicating if the file has been saved */
    public boolean savedFlag;
    /** Flag for DC analysis mode */
    public boolean dcAnalysisFlag;
    /** Flag for developer mode */
    public boolean developerMode = false;
    /** Flag to show resistance in voltage sources */
    public boolean showResistanceInVoltageSources;
    /** Flag to hide the information box */
    public boolean hideInfoBox;
    /** Flag to hide the menu */
    public boolean hideMenu = false;
    /** Flag for European resistor style */
    public boolean euroSetting;
    /** Flag for European-style logic gates */
    public boolean euroGates = false;
    /** Flag for printable version */
    public boolean printable = false;
    /** Flag for conventional current direction */
    public boolean convention = true;
    /** Flag for European resistor style */
    public boolean euroRes = false;
    /** Flag for US resistor style */
    public boolean usRes = false;
    /** Flag indicating if the simulation is running */
    public boolean running = false;
    /** Flag to disable editing */
    public boolean noEditing = false;
    /** Flag to enable editing with the mouse wheel */
    public boolean mouseWheelEdit = false;

    /** Initial circuit to load */
    String startCircuit = null;
    /** Initial label for the circuit */
    String startLabel = null;
    /** Initial circuit definition as text */
    String startCircuitText = null;
    /** Link to the initial circuit */
    String startCircuitLink = null;
    /** Color for positive voltage */
    String positiveColor = null;
    /** Color for negative voltage */
    String negativeColor = null;
    /** Color for neutral voltage/ground */
    String neutralColor = null;
    /** Color for selected components */
    String selectColor = null;
    /** Color for current flow visualization */
    String currentColor = null;

    /** Requested mouse mode */
    String mouseModeReq = null;

    protected CircuitInfo(CirSim cirSim) {
        super(cirSim);
    }

    void loadQueryParameters() {
        QueryParameters qp = new QueryParameters();

        try {
            String cct = qp.getValue("cct");
            if (cct != null) {
                startCircuitText = cct.replace("%24", "$");
            }
            if (startCircuitText == null) {
                startCircuitText = CirSim.getElectronStartCircuitText();
            }
            String ctz = qp.getValue("ctz");
            if (ctz != null) {
                startCircuitText = cirSim.decompress(ctz);
            }
            startCircuit = qp.getValue("startCircuit");
            startLabel = qp.getValue("startLabel");
            startCircuitLink = qp.getValue("startCircuitLink");
            euroRes = qp.getBooleanValue("euroResistors", false);
            euroGates = qp.getBooleanValue("IECGates", OptionsManager.getBoolOptionFromStorage("euroGates", cirSim.weAreInGermany()));
            usRes = qp.getBooleanValue("usResistors", false);
            running = qp.getBooleanValue("running", false);
            hideMenu = qp.getBooleanValue("hideMenu", false);
            printable = qp.getBooleanValue("whiteBackground", OptionsManager.getBoolOptionFromStorage("whiteBackground", false));
            convention = qp.getBooleanValue("conventionalCurrent",
                    OptionsManager.getBoolOptionFromStorage("conventionalCurrent", true));
            noEditing = !qp.getBooleanValue("editable", true);
            mouseWheelEdit = qp.getBooleanValue("mouseWheelEdit", OptionsManager.getBoolOptionFromStorage("mouseWheelEdit", true));
            positiveColor = qp.getValue("positiveColor");
            negativeColor = qp.getValue("negativeColor");
            neutralColor = qp.getValue("neutralColor");
            selectColor = qp.getValue("selectColor");
            currentColor = qp.getValue("currentColor");
            mouseModeReq = qp.getValue("mouseMode");
            hideInfoBox = qp.getBooleanValue("hideInfoBox", false);

            euroSetting = false;
            if (euroRes) {
                euroSetting = true;
            } else if (!usRes) {
                euroSetting = OptionsManager.getBoolOptionFromStorage("euroResistors", !cirSim.weAreInUS(true));
            }

        } catch (Exception e) {
            CirSim.console(e.toString());
        }
    }
}
