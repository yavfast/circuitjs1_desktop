package com.lushprojects.circuitjs1.client;

public class CircuitInfo extends BaseCirSimDelegate {

    String filePath;
    String fileName;
    String lastFileName;

    boolean unsavedChanges;
    boolean savedFlag;
    boolean dcAnalysisFlag;
    boolean developerMode = true;
    boolean showResistanceInVoltageSources;
    boolean hideInfoBox;
    boolean hideMenu = false;
    boolean euroSetting;
    boolean euroGates = false;
    boolean printable = false;
    boolean convention = true;
    boolean euroRes = false;
    boolean usRes = false;
    boolean running = true;
    boolean hideSidebar = false;
    boolean noEditing = false;
    boolean mouseWheelEdit = false;

    String startCircuit = null;
    String startLabel = null;
    String startCircuitText = null;
    String startCircuitLink = null;
    String positiveColor = null;
    String negativeColor = null;
    String neutralColor = null;
    String selectColor = null;
    String currentColor = null;

    String mouseModeReq = null;

    protected CircuitInfo(CirSim cirSim) {
        super(cirSim);
    }

    void loadQueryParameters() {
        QueryParameters qp = new QueryParameters();

        try {
            String cct = qp.getValue("cct");
            if (cct != null)
                startCircuitText = cct.replace("%24", "$");
            if (startCircuitText == null)
                startCircuitText = CirSim.getElectronStartCircuitText();
            String ctz = qp.getValue("ctz");
            if (ctz != null)
                startCircuitText = cirSim.decompress(ctz);
            startCircuit = qp.getValue("startCircuit");
            startLabel = qp.getValue("startLabel");
            startCircuitLink = qp.getValue("startCircuitLink");
            euroRes = qp.getBooleanValue("euroResistors", false);
            euroGates = qp.getBooleanValue("IECGates", OptionsManager.getBoolOptionFromStorage("euroGates", cirSim.weAreInGermany()));
            usRes = qp.getBooleanValue("usResistors", false);
            running = qp.getBooleanValue("running", true);
            hideSidebar = qp.getBooleanValue("hideSidebar", false);
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
            if (euroRes)
                euroSetting = true;
            else if (!usRes) {
                euroSetting = OptionsManager.getBoolOptionFromStorage("euroResistors", !cirSim.weAreInUS(true));
            }

        } catch (Exception e) {
            CirSim.console(e.toString());
        }
    }
}
