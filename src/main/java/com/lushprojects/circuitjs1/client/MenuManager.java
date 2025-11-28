package com.lushprojects.circuitjs1.client;

import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.user.client.Command;
import com.google.gwt.user.client.DOM;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.ui.MenuBar;
import com.google.gwt.user.client.ui.MenuItem;
import com.google.gwt.user.client.ui.PopupPanel;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.ScopeElm;
import com.lushprojects.circuitjs1.client.util.Locale;

import java.util.HashMap;
import java.util.Vector;

public class MenuManager extends BaseCirSimDelegate {

    MenuItem aboutItem;
    MenuItem helpItem;
    MenuItem licenseItem;
    MenuItem showLogsItem;
    // MenuItem testItem;
    MenuItem aboutCircuitsItem;
    MenuItem aboutCircuitsPLItem;
    MenuItem closeItem;
    //CheckboxMenuItem fullscreenCheckItem;
    MenuItem importFromLocalFileItem, importFromTextItem, exportAsUrlItem, exportAsLocalFileItem, exportAsTextItem, exportAsJsonItem,
            printItem, recoverItem, saveFileItem, openLastClosedTabItem;
    //MenuItem importFromDropboxItem;
    MenuItem undoItem, redoItem, cutItem, copyItem, pasteItem, selectAllItem, optionsItem, flipXItem, flipYItem, flipXYItem, modItem;
    MenuBar optionsMenuBar;

    public CheckboxMenuItem dotsCheckItem;
    public CheckboxMenuItem voltsCheckItem;
    public CheckboxMenuItem powerCheckItem;
    public CheckboxMenuItem smallGridCheckItem;
    public CheckboxMenuItem crossHairCheckItem;
    public CheckboxMenuItem showValuesCheckItem;
    public CheckboxMenuItem conductanceCheckItem;
    public CheckboxMenuItem euroResistorCheckItem;
    public CheckboxMenuItem euroGatesCheckItem;
    public CheckboxMenuItem printableCheckItem;
    public CheckboxMenuItem conventionCheckItem;
    public CheckboxMenuItem noEditCheckItem;
    public CheckboxMenuItem mouseWheelEditCheckItem;
    public CheckboxMenuItem toolbarCheckItem;
    public CheckboxMenuItem mouseModeCheckItem;

    MenuBar elmMenuBar;
    MenuItem elmEditMenuItem;
    MenuItem elmCutMenuItem;
    MenuItem elmCopyMenuItem;
    MenuItem elmDeleteMenuItem;
    MenuItem elmScopeMenuItem;
    MenuItem elmFloatScopeMenuItem;
    MenuItem elmAddScopeMenuItem;
    MenuItem elmSplitMenuItem;
    MenuItem elmSliderMenuItem;
    MenuItem elmFlipXMenuItem, elmFlipYMenuItem, elmFlipXYMenuItem;
    MenuItem elmSwapMenuItem;
    MenuItem stackAllItem;
    MenuItem unstackAllItem;
    MenuItem combineAllItem;
    MenuItem separateAllItem;
    MenuBar mainMenuBar;
    MenuBar selectScopeMenuBar;
    Vector<MenuItem> selectScopeMenuItems;
    MenuBar subcircuitMenuBar[];
    MenuItem scopeRemovePlotMenuItem;
    MenuItem scopeSelectYMenuItem;
    ScopePopupMenu scopePopupMenu;

    PopupPanel contextPanel = null;

    MenuBar menuBar;
    MenuBar fileMenuBar;
    MenuBar drawMenuBar;
    MenuBar circuitsMenuBar;


    public Vector<CheckboxMenuItem> mainMenuItems = new Vector<>();
    public Vector<String> mainMenuItemNames = new Vector<>();

    boolean isMac;
    String ctrlMetaKey;

    public String[] shortcuts = new String[127];

    int menuPlot = -1;

    final HashMap<String, String> classToLabelMap = new HashMap<>();

    protected MenuManager(BaseCirSim cirSim) {
        super(cirSim);

        mainMenuBar = new MenuBar(true);
        menuBar = new MenuBar();

        scopePopupMenu = new ScopePopupMenu(cirSim);

        String os = Window.Navigator.getPlatform();
        isMac = (os.toLowerCase().contains("mac"));
        ctrlMetaKey = (isMac) ? Locale.LS("Cmd-") : Locale.LS("Ctrl-");
    }

    void initMainMenuBar() {
        initFileMenuBar();
        initEditMenuBar();
        initDrawMenuBar();
        initScopesMenuBar();
        initOptionsMenuBar();
        initCircuitsMenuBar();
        initHelpMenuBar();

        mainMenuBar.setAutoOpen(true);

        composeMainMenu(mainMenuBar, 0);
        composeMainMenu(drawMenuBar, 1);
    }

    void initFileMenuBar() {
        fileMenuBar = new MenuBar(true);
        fileMenuBar.addItem(menuItemWithShortcut("doc-new", "New Tab", Locale.LS(ctrlMetaKey + "T"),
                new MyCommand("file", "newtab")));
        fileMenuBar.addItem(menuItemWithShortcut("window", "New Window...", Locale.LS(ctrlMetaKey + "N"),
                new MyCommand("file", "newwindow")));
        fileMenuBar.addItem(iconMenuItem("doc-new", "New Blank Circuit", new MyCommand("file", "newblankcircuit")));
        
        // Add Open Last Closed Tab
        openLastClosedTabItem = menuItemWithShortcut("back-in-time", "Open Last Closed Tab", Locale.LS(ctrlMetaKey + "Shift-T"),
                new MyCommand("file", "openlastclosedtab"));
        fileMenuBar.addItem(openLastClosedTabItem);
        
        importFromLocalFileItem = menuItemWithShortcut("folder", "Open File...", Locale.LS(ctrlMetaKey + "O"),
                new MyCommand("file", "importfromlocalfile"));
        importFromLocalFileItem.setEnabled(LoadFile.isSupported());
        fileMenuBar.addItem(importFromLocalFileItem);
        importFromTextItem = iconMenuItem("doc-text", "Import From Text...", new MyCommand("file", "importfromtext"));
        fileMenuBar.addItem(importFromTextItem);
        //importFromDropboxItem = iconMenuItem("dropbox", "Import From Dropbox...", new MyCommand("file", "importfromdropbox"));
        //fileMenuBar.addItem(importFromDropboxItem);
        //if (isElectron()) {
        saveFileItem = fileMenuBar.addItem(menuItemWithShortcut("floppy", "Save", Locale.LS(ctrlMetaKey + "S"),
                new MyCommand("file", "save")));
        fileMenuBar.addItem(iconMenuItem("floppy", "Save As...", new MyCommand("file", "saveas")));
	/*} else {
	    exportAsLocalFileItem = menuItemWithShortcut("floppy", "Save As...", Locale.LS(ctrlMetaKey + "S"),
		    new MyCommand("file","exportaslocalfile"));
	    exportAsLocalFileItem.setEnabled(ExportAsLocalFileDialog.downloadIsSupported());
	    fileMenuBar.addItem(exportAsLocalFileItem);
	}*/
        exportAsUrlItem = iconMenuItem("export", "Export As Link...", new MyCommand("file", "exportasurl"));
        fileMenuBar.addItem(exportAsUrlItem);
        exportAsTextItem = iconMenuItem("export", "Export As Text...", new MyCommand("file", "exportastext"));
        fileMenuBar.addItem(exportAsTextItem);
        exportAsJsonItem = iconMenuItem("export", "Export As JSON...", new MyCommand("file", "exportasjson"));
        fileMenuBar.addItem(exportAsJsonItem);
        fileMenuBar.addItem(iconMenuItem("image", "Export As Image...", new MyCommand("file", "exportasimage")));
        fileMenuBar.addItem(iconMenuItem("image", "Copy Circuit Image to Clipboard", new MyCommand("file", "copypng")));
        fileMenuBar.addItem(iconMenuItem("image", "Export As SVG...", new MyCommand("file", "exportassvg")));
        fileMenuBar.addItem(iconMenuItem("microchip", "Create Subcircuit...", new MyCommand("file", "createsubcircuit")));
        fileMenuBar.addItem(iconMenuItem("magic", "Find DC Operating Point", new MyCommand("file", "dcanalysis")));
        recoverItem = iconMenuItem("back-in-time", "Recover Auto-Save", new MyCommand("file", "recover"));
        recoverItem.setEnabled(getActiveDocument().undoManager.recovery != null);
        fileMenuBar.addItem(recoverItem);
        printItem = menuItemWithShortcut("print", "Print...", Locale.LS(ctrlMetaKey + "P"), new MyCommand("file", "print"));
        fileMenuBar.addItem(printItem);
        fileMenuBar.addSeparator();
        fileMenuBar.addItem(iconMenuItem("resize-full-alt", "Toggle Full Screen", new MyCommand("view", "fullscreen")));
        fileMenuBar.addSeparator();
        fileMenuBar.addItem(iconMenuItem("exit", "Exit",
                new Command() {
                    public void execute() {
                        undoManager().writeRecoveryToStorage();
                        CirSim.executeJS("nw.Window.get().close(true)");
                    }
                }));
        /*
        aboutItem = iconMenuItem("info-circled", "About...", (Command)null);
        fileMenuBar.addItem(aboutItem);
        aboutItem.setScheduledCommand(new MyCommand("file","about"));
        */

        menuBar.addItem(Locale.LS("File"), fileMenuBar);
    }

    void initEditMenuBar() {
        MenuBar m = new MenuBar(true);
        m.addItem(undoItem = menuItemWithShortcut("ccw", "Undo", Locale.LS(ctrlMetaKey + "Z"), new MyCommand("edit", "undo")));
        m.addItem(redoItem = menuItemWithShortcut("cw", "Redo", Locale.LS(ctrlMetaKey + "Y"), new MyCommand("edit", "redo")));
        m.addSeparator();
        m.addItem(cutItem = menuItemWithShortcut("scissors", "Cut", Locale.LS(ctrlMetaKey + "X"), new MyCommand("edit", "cut")));
        m.addItem(copyItem = menuItemWithShortcut("copy", "Copy", Locale.LS(ctrlMetaKey + "C"), new MyCommand("edit", "copy")));
        m.addItem(pasteItem = menuItemWithShortcut("paste", "Paste", Locale.LS(ctrlMetaKey + "V"), new MyCommand("edit", "paste")));
        pasteItem.setEnabled(false);

        m.addItem(menuItemWithShortcut("clone", "Duplicate", Locale.LS(ctrlMetaKey + "D"), new MyCommand("edit", "duplicate")));

        m.addSeparator();
        m.addItem(selectAllItem = menuItemWithShortcut("select-all", "Select All", Locale.LS(ctrlMetaKey + "A"), new MyCommand("edit", "selectAll")));
        m.addSeparator();
        m.addItem(menuItemWithShortcut("search", "Find Component...", "/", new MyCommand("edit", "search")));
        m.addItem(iconMenuItem("target", "Center Circuit", new MyCommand("edit", "centrecircuit")));
        m.addItem(menuItemWithShortcut("zoom-11", "Zoom 100%", "0", new MyCommand("zoom", "zoom100")));
        m.addItem(menuItemWithShortcut("zoom-in", "Zoom In", "+", new MyCommand("zoom", "zoomin")));
        m.addItem(menuItemWithShortcut("zoom-out", "Zoom Out", "-", new MyCommand("zoom", "zoomout")));
        m.addItem(flipXItem = iconMenuItem("flip-x", "Flip X", new MyCommand("edit", "flipx")));
        m.addItem(flipYItem = iconMenuItem("flip-y", "Flip Y", new MyCommand("edit", "flipy")));
        m.addItem(flipXYItem = iconMenuItem("flip-x-y", "Flip XY", new MyCommand("edit", "flipxy")));

        menuBar.addItem(Locale.LS("Edit"), m);
    }

    void initDrawMenuBar() {
        drawMenuBar = new MenuBar(true);
        drawMenuBar.setAutoOpen(true);

        menuBar.addItem(Locale.LS("Draw"), drawMenuBar);
    }

    void initScopesMenuBar() {
        MenuBar m = new MenuBar(true);
        m.addItem(stackAllItem = iconMenuItem("lines", "Stack All", new MyCommand("scopes", "stackAll")));
        m.addItem(unstackAllItem = iconMenuItem("columns", "Unstack All", new MyCommand("scopes", "unstackAll")));
        m.addItem(combineAllItem = iconMenuItem("object-group", "Combine All", new MyCommand("scopes", "combineAll")));
        m.addItem(separateAllItem = iconMenuItem("object-ungroup", "Separate All", new MyCommand("scopes", "separateAll")));
        menuBar.addItem(Locale.LS("Scopes"), m);
    }

    void initOptionsMenuBar() {
        CirSim cirSim = (CirSim) this.cirSim;
        MenuBar m;
        optionsMenuBar = m = new MenuBar(true);
        menuBar.addItem(Locale.LS("Options"), optionsMenuBar);
        m.addItem(dotsCheckItem = new CheckboxMenuItem(cirSim, Locale.LS("Show Current")));
        dotsCheckItem.setState(true);
        m.addItem(voltsCheckItem = new CheckboxMenuItem(cirSim, Locale.LS("Show Voltage"),
                new Command() {
                    public void execute() {
                        if (voltsCheckItem.getState())
                            powerCheckItem.setState(false);
                        cirSim.setPowerBarEnable();
                    }
                }));
        voltsCheckItem.setState(true);
        m.addItem(powerCheckItem = new CheckboxMenuItem(cirSim, Locale.LS("Show Power"),
                new Command() {
                    public void execute() {
                        if (powerCheckItem.getState())
                            voltsCheckItem.setState(false);
                        cirSim.setPowerBarEnable();
                    }
                }));
        m.addItem(showValuesCheckItem = new CheckboxMenuItem(cirSim, Locale.LS("Show Values")));
        showValuesCheckItem.setState(true);
        //m.add(conductanceCheckItem = getCheckItem(LS("Show Conductance")));
        m.addItem(smallGridCheckItem = new CheckboxMenuItem(cirSim, Locale.LS("Small Grid"),
                new Command() {
                    public void execute() {
                        circuitEditor().setGrid();
                    }
                }));
        m.addItem(toolbarCheckItem = new CheckboxMenuItem(cirSim, Locale.LS("Toolbar"),
                new Command() {
                    public void execute() {
                        OptionsManager.setOptionInStorage("toolbar", toolbarCheckItem.getState());
                        cirSim.setToolbar();
                    }
                }));
        toolbarCheckItem.setState(OptionsManager.getBoolOptionFromStorage("toolbar", true));
        m.addItem(mouseModeCheckItem = new CheckboxMenuItem(cirSim, Locale.LS("Show Mode"),
                new Command() {
                    public void execute() {
                        OptionsManager.setOptionInStorage("showMouseMode", mouseModeCheckItem.getState());
                    }
                }));
        mouseModeCheckItem.setState(OptionsManager.getBoolOptionFromStorage("showMouseMode", true));
        m.addItem(crossHairCheckItem = new CheckboxMenuItem(cirSim, Locale.LS("Show Cursor Cross Hairs"),
                new Command() {
                    public void execute() {
                        OptionsManager.setOptionInStorage("crossHair", crossHairCheckItem.getState());
                    }
                }));
        crossHairCheckItem.setState(OptionsManager.getBoolOptionFromStorage("crossHair", false));
        m.addItem(euroResistorCheckItem = new CheckboxMenuItem(cirSim, Locale.LS("European Resistors"),
                new Command() {
                    public void execute() {
                        OptionsManager.setOptionInStorage("euroResistors", euroResistorCheckItem.getState());
                        cirSim.toolbar.setEuroResistors(euroResistorCheckItem.getState());
                    }
                }));
        euroResistorCheckItem.setState(circuitInfo().euroSetting);
        m.addItem(euroGatesCheckItem = new CheckboxMenuItem(cirSim, Locale.LS("IEC Gates"),
                new Command() {
                    public void execute() {
                        OptionsManager.setOptionInStorage("euroGates", euroGatesCheckItem.getState());
                        CircuitSimulator simulator = simulator();
                        for (int i = 0; i != simulator.elmList.size(); i++)
                            simulator.elmList.get(i).setPoints();
                    }
                }));
        euroGatesCheckItem.setState(circuitInfo().euroGates);
        m.addItem(printableCheckItem = new CheckboxMenuItem(cirSim, Locale.LS("White Background"),
                new Command() {
                    public void execute() {
                        scopeManager().updateScopes();
                        OptionsManager.setOptionInStorage("whiteBackground", printableCheckItem.getState());
                    }
                }));
        printableCheckItem.setState(circuitInfo().printable);

        m.addItem(conventionCheckItem = new CheckboxMenuItem(cirSim, Locale.LS("Conventional Current Motion"),
                new Command() {
                    public void execute() {
                        OptionsManager.setOptionInStorage("conventionalCurrent", conventionCheckItem.getState());
                        String cc = CircuitElm.currentColor.getHexValue();
                        // change the current color if it hasn't changed from the default
                        if (cc.equals("#ffff00") || cc.equals("#00ffff"))
                            CircuitElm.currentColor = conventionCheckItem.getState() ? Color.yellow : Color.cyan;
                    }
                }));
        conventionCheckItem.setState(circuitInfo().convention);
        m.addItem(noEditCheckItem = new CheckboxMenuItem(cirSim, Locale.LS("Disable Editing")));
        noEditCheckItem.setState(circuitInfo().noEditing);

        m.addItem(mouseWheelEditCheckItem = new CheckboxMenuItem(cirSim, Locale.LS("Edit Values With Mouse Wheel"),
                new Command() {
                    public void execute() {
                        OptionsManager.setOptionInStorage("mouseWheelEdit", mouseWheelEditCheckItem.getState());
                    }
                }));
        mouseWheelEditCheckItem.setState(circuitInfo().mouseWheelEdit);

        m.addItem(new CheckboxAlignedMenuItem(Locale.LS("Shortcuts..."), new MyCommand("options", "shortcuts")));
        m.addItem(new CheckboxAlignedMenuItem(Locale.LS("Subcircuits..."), new MyCommand("options", "subcircuits")));
        m.addItem(optionsItem = new CheckboxAlignedMenuItem(Locale.LS("Other Options..."), new MyCommand("options", "other")));
        m.addItem(modItem = new CheckboxAlignedMenuItem("Modification Setup...", new MyCommand("options", "modsetup")));
        modItem.addStyleName("modItem");
        if (cirSim.isElectron())
            m.addItem(new CheckboxAlignedMenuItem(Locale.LS("Toggle Dev Tools"), new MyCommand("options", "devtools")));
    }

    // this is called twice, once for the Draw menu, once for the right mouse popup menu
    public void composeMainMenu(MenuBar mainMenuBar, int num) {
        mainMenuBar.addItem(getClassCheckItem(Locale.LS("Add Wire"), "WireElm"));
        mainMenuBar.addItem(getClassCheckItem(Locale.LS("Add Resistor"), "ResistorElm"));

        MenuBar passMenuBar = new MenuBar(true);
        passMenuBar.addItem(getClassCheckItem(Locale.LS("Add Capacitor"), "CapacitorElm"));
        passMenuBar.addItem(getClassCheckItem(Locale.LS("Add Capacitor (polarized)"), "PolarCapacitorElm"));
        passMenuBar.addItem(getClassCheckItem(Locale.LS("Add Inductor"), "InductorElm"));
        passMenuBar.addItem(getClassCheckItem(Locale.LS("Add Switch"), "SwitchElm"));
        passMenuBar.addItem(getClassCheckItem(Locale.LS("Add Push Switch"), "PushSwitchElm"));
        passMenuBar.addItem(getClassCheckItem(Locale.LS("Add SPDT Switch"), "Switch2Elm"));
        passMenuBar.addItem(getClassCheckItem(Locale.LS("Add DPDT Switch"), "DPDTSwitchElm"));
        passMenuBar.addItem(getClassCheckItem(Locale.LS("Add Make-Before-Break Switch"), "MBBSwitchElm"));
        passMenuBar.addItem(getClassCheckItem(Locale.LS("Add Potentiometer"), "PotElm"));
        passMenuBar.addItem(getClassCheckItem(Locale.LS("Add Transformer"), "TransformerElm"));
        passMenuBar.addItem(getClassCheckItem(Locale.LS("Add Tapped Transformer"), "TappedTransformerElm"));
        passMenuBar.addItem(getClassCheckItem(Locale.LS("Add Custom Transformer"), "CustomTransformerElm"));
        passMenuBar.addItem(getClassCheckItem(Locale.LS("Add Transmission Line"), "TransLineElm"));
        passMenuBar.addItem(getClassCheckItem(Locale.LS("Add Relay"), "RelayElm"));
        passMenuBar.addItem(getClassCheckItem(Locale.LS("Add Relay Coil"), "RelayCoilElm"));
        passMenuBar.addItem(getClassCheckItem(Locale.LS("Add Relay Contact"), "RelayContactElm"));
        passMenuBar.addItem(getClassCheckItem(Locale.LS("Add Photoresistor"), "LDRElm"));
        passMenuBar.addItem(getClassCheckItem(Locale.LS("Add Thermistor"), "ThermistorNTCElm"));
        passMenuBar.addItem(getClassCheckItem(Locale.LS("Add Memristor"), "MemristorElm"));
        passMenuBar.addItem(getClassCheckItem(Locale.LS("Add Spark Gap"), "SparkGapElm"));
        passMenuBar.addItem(getClassCheckItem(Locale.LS("Add Fuse"), "FuseElm"));
        passMenuBar.addItem(getClassCheckItem(Locale.LS("Add Crystal"), "CrystalElm"));
        passMenuBar.addItem(getClassCheckItem(Locale.LS("Add Cross Switch"), "CrossSwitchElm"));
        mainMenuBar.addItem(SafeHtmlUtils.fromTrustedString(CheckboxMenuItem.checkBoxHtml + Locale.LS("&nbsp;</div>Passive Components")), passMenuBar);

        MenuBar inputMenuBar = new MenuBar(true);
        inputMenuBar.addItem(getClassCheckItem(Locale.LS("Add Ground"), "GroundElm"));
        inputMenuBar.addItem(getClassCheckItem(Locale.LS("Add Voltage Source (2-terminal)"), "DCVoltageElm"));
        inputMenuBar.addItem(getClassCheckItem(Locale.LS("Add A/C Voltage Source (2-terminal)"), "ACVoltageElm"));
        inputMenuBar.addItem(getClassCheckItem(Locale.LS("Add Voltage Source (1-terminal)"), "RailElm"));
        inputMenuBar.addItem(getClassCheckItem(Locale.LS("Add A/C Voltage Source (1-terminal)"), "ACRailElm"));
        inputMenuBar.addItem(getClassCheckItem(Locale.LS("Add Square Wave Source (1-terminal)"), "SquareRailElm"));
        inputMenuBar.addItem(getClassCheckItem(Locale.LS("Add Clock"), "ClockElm"));
        inputMenuBar.addItem(getClassCheckItem(Locale.LS("Add A/C Sweep"), "SweepElm"));
        inputMenuBar.addItem(getClassCheckItem(Locale.LS("Add Variable Voltage"), "VarRailElm"));
        inputMenuBar.addItem(getClassCheckItem(Locale.LS("Add Antenna"), "AntennaElm"));
        inputMenuBar.addItem(getClassCheckItem(Locale.LS("Add AM Source"), "AMElm"));
        inputMenuBar.addItem(getClassCheckItem(Locale.LS("Add FM Source"), "FMElm"));
        inputMenuBar.addItem(getClassCheckItem(Locale.LS("Add Current Source"), "CurrentElm"));
        inputMenuBar.addItem(getClassCheckItem(Locale.LS("Add Noise Generator"), "NoiseElm"));
        inputMenuBar.addItem(getClassCheckItem(Locale.LS("Add Audio Input"), "AudioInputElm"));
        inputMenuBar.addItem(getClassCheckItem(Locale.LS("Add Data Input"), "DataInputElm"));
        inputMenuBar.addItem(getClassCheckItem(Locale.LS("Add External Voltage (JavaScript)"), "ExtVoltageElm"));

        mainMenuBar.addItem(SafeHtmlUtils.fromTrustedString(CheckboxMenuItem.checkBoxHtml + Locale.LS("&nbsp;</div>Inputs and Sources")), inputMenuBar);

        MenuBar outputMenuBar = new MenuBar(true);
        outputMenuBar.addItem(getClassCheckItem(Locale.LS("Add Analog Output"), "OutputElm"));
        outputMenuBar.addItem(getClassCheckItem(Locale.LS("Add LED"), "LEDElm"));
        outputMenuBar.addItem(getClassCheckItem(Locale.LS("Add Lamp"), "LampElm"));
        outputMenuBar.addItem(getClassCheckItem(Locale.LS("Add Text"), "TextElm"));
        outputMenuBar.addItem(getClassCheckItem(Locale.LS("Add Box"), "BoxElm"));
        outputMenuBar.addItem(getClassCheckItem(Locale.LS("Add Line"), "LineElm"));
        outputMenuBar.addItem(getClassCheckItem(Locale.LS("Add Labeled Node"), "LabeledNodeElm"));
        outputMenuBar.addItem(getClassCheckItem(Locale.LS("Add Voltmeter/Scope Probe"), "ProbeElm"));
        outputMenuBar.addItem(getClassCheckItem(Locale.LS("Add Ohmmeter"), "OhmMeterElm"));
        outputMenuBar.addItem(getClassCheckItem(Locale.LS("Add Ammeter"), "AmmeterElm"));
        outputMenuBar.addItem(getClassCheckItem(Locale.LS("Add Wattmeter"), "WattmeterElm"));
        outputMenuBar.addItem(getClassCheckItem(Locale.LS("Add Test Point"), "TestPointElm"));
        outputMenuBar.addItem(getClassCheckItem(Locale.LS("Add Decimal Display"), "DecimalDisplayElm"));
        outputMenuBar.addItem(getClassCheckItem(Locale.LS("Add LED Array"), "LEDArrayElm"));
        outputMenuBar.addItem(getClassCheckItem(Locale.LS("Add Data Export"), "DataRecorderElm"));
        outputMenuBar.addItem(getClassCheckItem(Locale.LS("Add Audio Output"), "AudioOutputElm"));
        outputMenuBar.addItem(getClassCheckItem(Locale.LS("Add Stop Trigger"), "StopTriggerElm"));
        outputMenuBar.addItem(getClassCheckItem(Locale.LS("Add DC Motor"), "DCMotorElm"));
        outputMenuBar.addItem(getClassCheckItem(Locale.LS("Add 3-Phase Motor"), "ThreePhaseMotorElm"));
        mainMenuBar.addItem(SafeHtmlUtils.fromTrustedString(CheckboxMenuItem.checkBoxHtml + Locale.LS("&nbsp;</div>Outputs and Labels")), outputMenuBar);

        MenuBar activeMenuBar = new MenuBar(true);
        activeMenuBar.addItem(getClassCheckItem(Locale.LS("Add Diode"), "DiodeElm"));
        activeMenuBar.addItem(getClassCheckItem(Locale.LS("Add Zener Diode"), "ZenerElm"));
        activeMenuBar.addItem(getClassCheckItem(Locale.LS("Add Transistor (bipolar, NPN)"), "NTransistorElm"));
        activeMenuBar.addItem(getClassCheckItem(Locale.LS("Add Transistor (bipolar, PNP)"), "PTransistorElm"));
        activeMenuBar.addItem(getClassCheckItem(Locale.LS("Add MOSFET (N-Channel)"), "NMosfetElm"));
        activeMenuBar.addItem(getClassCheckItem(Locale.LS("Add MOSFET (P-Channel)"), "PMosfetElm"));
        activeMenuBar.addItem(getClassCheckItem(Locale.LS("Add JFET (N-Channel)"), "NJfetElm"));
        activeMenuBar.addItem(getClassCheckItem(Locale.LS("Add JFET (P-Channel)"), "PJfetElm"));
        activeMenuBar.addItem(getClassCheckItem(Locale.LS("Add SCR"), "SCRElm"));
        activeMenuBar.addItem(getClassCheckItem(Locale.LS("Add DIAC"), "DiacElm"));
        activeMenuBar.addItem(getClassCheckItem(Locale.LS("Add TRIAC"), "TriacElm"));
        activeMenuBar.addItem(getClassCheckItem(Locale.LS("Add Darlington Pair (NPN)"), "NDarlingtonElm"));
        activeMenuBar.addItem(getClassCheckItem(Locale.LS("Add Darlington Pair (PNP)"), "PDarlingtonElm"));
        activeMenuBar.addItem(getClassCheckItem(Locale.LS("Add Varactor/Varicap"), "VaractorElm"));
        activeMenuBar.addItem(getClassCheckItem(Locale.LS("Add Tunnel Diode"), "TunnelDiodeElm"));
        activeMenuBar.addItem(getClassCheckItem(Locale.LS("Add Triode"), "TriodeElm"));
        activeMenuBar.addItem(getClassCheckItem(Locale.LS("Add Unijunction Transistor"), "UnijunctionElm"));
        mainMenuBar.addItem(SafeHtmlUtils.fromTrustedString(CheckboxMenuItem.checkBoxHtml + Locale.LS("&nbsp;</div>Active Components")), activeMenuBar);

        MenuBar activeBlocMenuBar = new MenuBar(true);
        activeBlocMenuBar.addItem(getClassCheckItem(Locale.LS("Add Op Amp (ideal, - on top)"), "OpAmpElm"));
        activeBlocMenuBar.addItem(getClassCheckItem(Locale.LS("Add Op Amp (ideal, + on top)"), "OpAmpSwapElm"));
        activeBlocMenuBar.addItem(getClassCheckItem(Locale.LS("Add Op Amp (real)"), "OpAmpRealElm"));
        activeBlocMenuBar.addItem(getClassCheckItem(Locale.LS("Add Analog Switch (SPST)"), "AnalogSwitchElm"));
        activeBlocMenuBar.addItem(getClassCheckItem(Locale.LS("Add Analog Switch (SPDT)"), "AnalogSwitch2Elm"));
        activeBlocMenuBar.addItem(getClassCheckItem(Locale.LS("Add Tristate Buffer"), "TriStateElm"));
        activeBlocMenuBar.addItem(getClassCheckItem(Locale.LS("Add Schmitt Trigger"), "SchmittElm"));
        activeBlocMenuBar.addItem(getClassCheckItem(Locale.LS("Add Schmitt Trigger (Inverting)"), "InvertingSchmittElm"));
        activeBlocMenuBar.addItem(getClassCheckItem(Locale.LS("Add Delay Buffer"), "DelayBufferElm"));
        activeBlocMenuBar.addItem(getClassCheckItem(Locale.LS("Add CCII+"), "CC2Elm"));
        activeBlocMenuBar.addItem(getClassCheckItem(Locale.LS("Add CCII-"), "CC2NegElm"));
        activeBlocMenuBar.addItem(getClassCheckItem(Locale.LS("Add Comparator (Hi-Z/GND output)"), "ComparatorElm"));
        activeBlocMenuBar.addItem(getClassCheckItem(Locale.LS("Add OTA (LM13700 style)"), "OTAElm"));
        activeBlocMenuBar.addItem(getClassCheckItem(Locale.LS("Add Voltage-Controlled Voltage Source (VCVS)"), "VCVSElm"));
        activeBlocMenuBar.addItem(getClassCheckItem(Locale.LS("Add Voltage-Controlled Current Source (VCCS)"), "VCCSElm"));
        activeBlocMenuBar.addItem(getClassCheckItem(Locale.LS("Add Current-Controlled Voltage Source (CCVS)"), "CCVSElm"));
        activeBlocMenuBar.addItem(getClassCheckItem(Locale.LS("Add Current-Controlled Current Source (CCCS)"), "CCCSElm"));
        activeBlocMenuBar.addItem(getClassCheckItem(Locale.LS("Add Optocoupler"), "OptocouplerElm"));
        activeBlocMenuBar.addItem(getClassCheckItem(Locale.LS("Add Time Delay Relay"), "TimeDelayRelayElm"));
        activeBlocMenuBar.addItem(getClassCheckItem(Locale.LS("Add LM317"), "CustomCompositeElm:~LM317-v2"));
        activeBlocMenuBar.addItem(getClassCheckItem(Locale.LS("Add TL431"), "CustomCompositeElm:~TL431"));
        activeBlocMenuBar.addItem(getClassCheckItem(Locale.LS("Add Motor Protection Switch"), "MotorProtectionSwitchElm"));
        activeBlocMenuBar.addItem(getClassCheckItem(Locale.LS("Add Subcircuit Instance"), "CustomCompositeElm"));
        mainMenuBar.addItem(SafeHtmlUtils.fromTrustedString(CheckboxMenuItem.checkBoxHtml + Locale.LS("&nbsp;</div>Active Building Blocks")), activeBlocMenuBar);

        MenuBar gateMenuBar = new MenuBar(true);
        gateMenuBar.addItem(getClassCheckItem(Locale.LS("Add Logic Input"), "LogicInputElm"));
        gateMenuBar.addItem(getClassCheckItem(Locale.LS("Add Logic Output"), "LogicOutputElm"));
        gateMenuBar.addItem(getClassCheckItem(Locale.LS("Add Inverter"), "InverterElm"));
        gateMenuBar.addItem(getClassCheckItem(Locale.LS("Add NAND Gate"), "NandGateElm"));
        gateMenuBar.addItem(getClassCheckItem(Locale.LS("Add NOR Gate"), "NorGateElm"));
        gateMenuBar.addItem(getClassCheckItem(Locale.LS("Add AND Gate"), "AndGateElm"));
        gateMenuBar.addItem(getClassCheckItem(Locale.LS("Add OR Gate"), "OrGateElm"));
        gateMenuBar.addItem(getClassCheckItem(Locale.LS("Add XOR Gate"), "XorGateElm"));
        mainMenuBar.addItem(SafeHtmlUtils.fromTrustedString(CheckboxMenuItem.checkBoxHtml + Locale.LS("&nbsp;</div>Logic Gates, Input and Output")), gateMenuBar);

        MenuBar chipMenuBar = new MenuBar(true);
        chipMenuBar.addItem(getClassCheckItem(Locale.LS("Add D Flip-Flop"), "DFlipFlopElm"));
        chipMenuBar.addItem(getClassCheckItem(Locale.LS("Add JK Flip-Flop"), "JKFlipFlopElm"));
        chipMenuBar.addItem(getClassCheckItem(Locale.LS("Add T Flip-Flop"), "TFlipFlopElm"));
        chipMenuBar.addItem(getClassCheckItem(Locale.LS("Add 7 Segment LED"), "SevenSegElm"));
        chipMenuBar.addItem(getClassCheckItem(Locale.LS("Add 7 Segment Decoder"), "SevenSegDecoderElm"));
        chipMenuBar.addItem(getClassCheckItem(Locale.LS("Add Multiplexer"), "MultiplexerElm"));
        chipMenuBar.addItem(getClassCheckItem(Locale.LS("Add Demultiplexer"), "DeMultiplexerElm"));
        chipMenuBar.addItem(getClassCheckItem(Locale.LS("Add SIPO shift register"), "SipoShiftElm"));
        chipMenuBar.addItem(getClassCheckItem(Locale.LS("Add PISO shift register"), "PisoShiftElm"));
        chipMenuBar.addItem(getClassCheckItem(Locale.LS("Add Counter"), "CounterElm"));
        chipMenuBar.addItem(getClassCheckItem(Locale.LS("Add Counter w/ Load"), "Counter2Elm"));
        chipMenuBar.addItem(getClassCheckItem(Locale.LS("Add Ring Counter"), "DecadeElm"));
        chipMenuBar.addItem(getClassCheckItem(Locale.LS("Add Latch"), "LatchElm"));
        chipMenuBar.addItem(getClassCheckItem(Locale.LS("Add Sequence generator"), "SeqGenElm"));
        chipMenuBar.addItem(getClassCheckItem(Locale.LS("Add Adder"), "FullAdderElm"));
        chipMenuBar.addItem(getClassCheckItem(Locale.LS("Add Half Adder"), "HalfAdderElm"));
        chipMenuBar.addItem(getClassCheckItem(Locale.LS("Add Custom Logic"), "UserDefinedLogicElm")); // don't change this, it will break people's saved shortcuts
        chipMenuBar.addItem(getClassCheckItem(Locale.LS("Add Static RAM"), "SRAMElm"));
        mainMenuBar.addItem(SafeHtmlUtils.fromTrustedString(CheckboxMenuItem.checkBoxHtml + Locale.LS("&nbsp;</div>Digital Chips")), chipMenuBar);

        MenuBar achipMenuBar = new MenuBar(true);
        achipMenuBar.addItem(getClassCheckItem(Locale.LS("Add 555 Timer"), "TimerElm"));
        achipMenuBar.addItem(getClassCheckItem(Locale.LS("Add Phase Comparator"), "PhaseCompElm"));
        achipMenuBar.addItem(getClassCheckItem(Locale.LS("Add DAC"), "DACElm"));
        achipMenuBar.addItem(getClassCheckItem(Locale.LS("Add ADC"), "ADCElm"));
        achipMenuBar.addItem(getClassCheckItem(Locale.LS("Add VCO"), "VCOElm"));
        achipMenuBar.addItem(getClassCheckItem(Locale.LS("Add Monostable"), "MonostableElm"));
        mainMenuBar.addItem(SafeHtmlUtils.fromTrustedString(CheckboxMenuItem.checkBoxHtml + Locale.LS("&nbsp;</div>Analog and Hybrid Chips")), achipMenuBar);

        if (subcircuitMenuBar == null)
            subcircuitMenuBar = new MenuBar[2];
        subcircuitMenuBar[num] = new MenuBar(true);
        mainMenuBar.addItem(SafeHtmlUtils.fromTrustedString(CheckboxMenuItem.checkBoxHtml + Locale.LS("&nbsp;</div>Subcircuits")), subcircuitMenuBar[num]);

        MenuBar otherMenuBar = new MenuBar(true);
        CheckboxMenuItem mi;
        otherMenuBar.addItem(mi = getClassCheckItem(Locale.LS("Drag All"), "DragAll"));
        mi.setShortcut(Locale.LS("(Alt-drag)"));
        otherMenuBar.addItem(mi = getClassCheckItem(Locale.LS("Drag Row"), "DragRow"));
        mi.setShortcut(Locale.LS("(A-S-drag)"));
        otherMenuBar.addItem(mi = getClassCheckItem(Locale.LS("Drag Column"), "DragColumn"));
        mi.setShortcut(isMac ? Locale.LS("(A-Cmd-drag)") : Locale.LS("(A-M-drag)"));
        otherMenuBar.addItem(getClassCheckItem(Locale.LS("Drag Selected"), "DragSelected"));
        otherMenuBar.addItem(mi = getClassCheckItem(Locale.LS("Drag Post"), "DragPost"));
        mi.setShortcut("(" + ctrlMetaKey + "drag)");

        mainMenuBar.addItem(SafeHtmlUtils.fromTrustedString(CheckboxMenuItem.checkBoxHtml + Locale.LS("&nbsp;</div>Drag")), otherMenuBar);

        mainMenuBar.addItem(mi = getClassCheckItem(Locale.LS("Select/Drag Sel"), "Select"));
        mi.setShortcut(Locale.LS("(space or Shift-drag)"));
    }

    CheckboxMenuItem getClassCheckItem(String s, String t) {
        classToLabelMap.put(t, s);

        String shortcut = "";
        CircuitElm elm = null;
        try {
            elm = CircuitElmCreator.constructElement(t, 0, 0);
        } catch (Exception e) {
        }
        CheckboxMenuItem mi;
        //  register(c, elm);
        if (elm != null) {
            if (elm.needsShortcut()) {
                int sc = elm.getShortcut();
                shortcut += (char) sc;
                String[] shortcuts = this.shortcuts;
                if (shortcuts[sc] != null && !shortcuts[sc].equals(t)) {
                    CirSim.console("already have shortcut for " + (char) sc + " " + elm);
                }
                shortcuts[sc] = t;
            }
            elm.delete();
        }

        if (shortcut == "")
            mi = new CheckboxMenuItem(cirSim, s);
        else
            mi = new CheckboxMenuItem(cirSim, s, shortcut);
        mi.setScheduledCommand(new MyCommand("main", t));
        mainMenuItems.add(mi);
        mainMenuItemNames.add(t);
        return mi;
    }

    void composeSubcircuitMenu() {
        if (subcircuitMenuBar == null)
            return;
        int mi;

        // there are two menus to update: the one in the Draw menu, and the one in the right mouse menu
        for (mi = 0; mi != 2; mi++) {
            MenuBar menu = subcircuitMenuBar[mi];
            menu.clearItems();
            Vector<CustomCompositeModel> list = CustomCompositeModel.getModelList();
            int i;
            for (i = 0; i != list.size(); i++) {
                String name = list.get(i).name;
                menu.addItem(getClassCheckItem(Locale.LS("Add ") + name, "CustomCompositeElm:" + name));
            }
        }
        lastSubcircuitMenuUpdate = CustomCompositeModel.sequenceNumber;
    }

    public void composeSelectScopeMenu(MenuBar sb) {
        sb.clearItems();
        selectScopeMenuItems = new Vector<>();
        ScopeManager scopeManager = scopeManager();
        for (int i = 0; i < scopeManager.scopeCount; i++) {
            String s, l;
            s = Locale.LS("Scope") + " " + Integer.toString(i + 1);
            l = scopeManager.scopes[i].getScopeLabelOrText();
            if (l != "")
                s += " (" + SafeHtmlUtils.htmlEscape(l) + ")";
            selectScopeMenuItems.add(new MenuItem(s, new MyCommand("elm", "addToScope" + Integer.toString(i))));
        }
        CircuitSimulator simulator = simulator();
        int c = simulator.countScopeElms();
        for (int j = 0; j < c; j++) {
            String s, l;
            s = Locale.LS("Undocked Scope") + " " + Integer.toString(j + 1);
            l = simulator.getNthScopeElm(j).elmScope.getScopeLabelOrText();
            if (l != "")
                s += " (" + SafeHtmlUtils.htmlEscape(l) + ")";
            selectScopeMenuItems.add(new MenuItem(s, new MyCommand("elm", "addToScope" + Integer.toString(scopeManager.scopeCount + j))));
        }
        for (MenuItem mi : selectScopeMenuItems)
            sb.addItem(mi);
    }

    void initElmMenuBar() {
        elmMenuBar = new MenuBar(true);
        elmMenuBar.setAutoOpen(true);
        selectScopeMenuBar = new MenuBar(true) {
            @Override

            // when mousing over scope menu item, select associated scope
            public void onBrowserEvent(Event event) {
                int currentItem = -1;
                int i;
                for (i = 0; i != selectScopeMenuItems.size(); i++) {
                    MenuItem item = selectScopeMenuItems.get(i);
                    if (DOM.isOrHasChild(item.getElement(), DOM.eventGetTarget(event))) {
                        //MenuItem found here
                        currentItem = i;
                    }
                }
                switch (DOM.eventGetType(event)) {
                    case Event.ONMOUSEOVER:
                        scopeManager().scopeMenuSelected = currentItem;
                        break;
                    case Event.ONMOUSEOUT:
                        scopeManager().scopeMenuSelected = -1;
                        break;
                }
                super.onBrowserEvent(event);
            }
        };

        elmMenuBar.addItem(elmEditMenuItem = new MenuItem(Locale.LS("Edit..."), new MyCommand("elm", "edit")));
        elmMenuBar.addItem(elmScopeMenuItem = new MenuItem(Locale.LS("View in New Scope"), new MyCommand("elm", "viewInScope")));
        elmMenuBar.addItem(elmFloatScopeMenuItem = new MenuItem(Locale.LS("View in New Undocked Scope"), new MyCommand("elm", "viewInFloatScope")));
        elmMenuBar.addItem(elmAddScopeMenuItem = new MenuItem(Locale.LS("Add to Existing Scope"), new MyCommand("elm", "addToScope0")));
        elmMenuBar.addItem(elmCutMenuItem = new MenuItem(Locale.LS("Cut"), new MyCommand("elm", "cut")));
        elmMenuBar.addItem(elmCopyMenuItem = new MenuItem(Locale.LS("Copy"), new MyCommand("elm", "copy")));
        elmMenuBar.addItem(elmDeleteMenuItem = new MenuItem(Locale.LS("Delete"), new MyCommand("elm", "delete")));
        elmMenuBar.addItem(new MenuItem(Locale.LS("Duplicate"), new MyCommand("elm", "duplicate")));
        elmMenuBar.addItem(elmSwapMenuItem = new MenuItem(Locale.LS("Swap Terminals"), new MyCommand("elm", "flip")));
        elmMenuBar.addItem(elmFlipXMenuItem = new MenuItem(Locale.LS("Flip X"), new MyCommand("elm", "flipx")));
        elmMenuBar.addItem(elmFlipYMenuItem = new MenuItem(Locale.LS("Flip Y"), new MyCommand("elm", "flipy")));
        elmMenuBar.addItem(elmFlipXYMenuItem = new MenuItem(Locale.LS("Flip XY"), new MyCommand("elm", "flipxy")));
        elmMenuBar.addItem(elmSplitMenuItem = menuItemWithShortcut("", "Split Wire", Locale.LS(ctrlMetaKey + "click"), new MyCommand("elm", "split")));
        elmMenuBar.addItem(elmSliderMenuItem = new MenuItem(Locale.LS("Sliders..."), new MyCommand("elm", "sliders")));
    }

    MenuBar initCircuitsMenuBar() {
        circuitsMenuBar = new MenuBar(true);
        circuitsMenuBar.setAutoOpen(true);
        menuBar.addItem(Locale.LS("Circuits"), circuitsMenuBar);
        return circuitsMenuBar;
    }

    void initHelpMenuBar() {
        MenuBar h = new MenuBar(true);
        helpItem = iconMenuItem("book-open", "User Guide", (Command) null);
        h.addItem(helpItem);
        helpItem.setScheduledCommand(new MyCommand("file", "help"));
        licenseItem = iconMenuItem("license", "License", (Command) null);
        h.addItem(licenseItem);
        licenseItem.setScheduledCommand(new MyCommand("file", "license"));
        aboutItem = iconMenuItem("info-circled", "About...", (Command) null);
        h.addItem(aboutItem);
        aboutItem.setScheduledCommand(new MyCommand("file", "about"));
        h.addSeparator();
        // Add Show Logs menu item
        showLogsItem = iconMenuItem("doc-text", "Show Logs...", new MyCommand("file", "showlogs"));
        h.addItem(showLogsItem);
        h.addSeparator();
        h.addItem(aboutCircuitsItem = iconMenuItem("link", "About Circuits",
                new Command() {
                    public void execute() {
                        CirSim.executeJS("nw.Shell.openExternal('https://www.falstad.com/circuit/e-index.html')");
                    }
                }));
        h.addItem(aboutCircuitsPLItem = iconMenuItem("link", "About Circuits (Polish ver.)",
                new Command() {
                    public void execute() {
                        CirSim.executeJS("nw.Shell.openExternal('https://www.falstad.com/circuit/polish/e-index.html');");
                    }
                }));

        menuBar.addItem(Locale.LS("Help"), h);
    }

    void clearShortcuts() {
        // clear shortcuts from menu
        for (int i = 0; i != mainMenuItems.size(); i++) {
            CheckboxMenuItem item = mainMenuItems.get(i);
            // stop when we get to drag menu items
            if (item.getShortcut().length() > 1)
                break;
            item.setShortcut("");
        }
    }

    void setShortcut(String className, int code) {
        // find menu item and fix it
        int j;
        for (j = 0; j != mainMenuItems.size(); j++) {
            if (mainMenuItemNames.get(j) == className) {
                CheckboxMenuItem item = mainMenuItems.get(j);
                item.setShortcut(Character.toString((char) code));
                break;
            }
        }
    }

    MenuItem menuItemWithShortcut(String icon, String text, String shortcut, MyCommand cmd) {
        final String edithtml = "<div style=\"white-space:nowrap\"><div style=\"display:inline-block;width:100%;\"><i class=\"cirjsicon-";
        String nbsp = "&nbsp;";
        if (icon == "") nbsp = "";
        String sn = edithtml + icon + "\"></i>" + nbsp + Locale.LS(text) + "</div>" + shortcut + "</div>";
        return new MenuItem(SafeHtmlUtils.fromTrustedString(sn), cmd);
    }

    MenuItem iconMenuItem(String icon, String text, Command cmd) {
        String icoStr = "<i class=\"cirjsicon-" + icon + "\"></i>&nbsp;" + Locale.LS(text); //<i class="cirjsicon-"></i>&nbsp;
        return new MenuItem(SafeHtmlUtils.fromTrustedString(icoStr), cmd);
    }

    void doPopupMenu() {
        CirSim.console("doPopupMenu called");
        CirSim.console("noEditCheckItem state: " + noEditCheckItem.getState());
        CirSim.console("dialogIsShowing: " + cirSim.dialogIsShowing());

        if (noEditCheckItem.getState() || cirSim.dialogIsShowing()) {
            CirSim.console("Popup menu blocked - editing disabled or dialog showing");
            return;
        }

        CircuitElm mouseElm = circuitEditor().mouseElm;
        circuitEditor().menuElm = mouseElm;

        int menuClientX = circuitEditor().menuClientX;
        int menuClientY = circuitEditor().menuClientY;

        CirSim.console("Menu position: " + menuClientX + ", " + menuClientY);
        CirSim.console("mouseElm: " + (mouseElm != null ? mouseElm.getClass().getSimpleName() : "null"));

        ScopeManager scopeManager = scopeManager();
        scopeManager.menuScope = -1;
        menuPlot = -1;
        int x, y;

        if (scopeManager.scopeSelected != -1) {
            CirSim.console("Scope selected: " + scopeManager.scopeSelected);
            if (scopeManager.scopes[scopeManager.scopeSelected].canMenu()) {
                scopeManager.menuScope = scopeManager.scopeSelected;
                menuPlot = scopeManager.scopes[scopeManager.scopeSelected].selectedPlot;
                scopePopupMenu.doScopePopupChecks(false, scopeManager.canStackScope(scopeManager.scopeSelected), scopeManager.canCombineScope(scopeManager.scopeSelected),
                        scopeManager.canUnstackScope(scopeManager.scopeSelected), scopeManager.scopes[scopeManager.scopeSelected]);

                if (contextPanel != null && contextPanel.isShowing()) {
                    contextPanel.hide();
                }

                contextPanel = new PopupPanel(true);
                contextPanel.add(scopePopupMenu.getMenuBar());
                y = Math.max(0, Math.min(menuClientY, renderer().canvasHeight - 160));
                contextPanel.setPopupPosition(menuClientX, y);
                contextPanel.show();
                CirSim.console("Scope popup menu shown");
            } else {
                CirSim.console("Scope cannot show menu");
            }
        } else if (mouseElm != null) {
            CirSim.console("Element menu for: " + mouseElm.getClass().getSimpleName());
            if (!(mouseElm instanceof ScopeElm)) {
                elmScopeMenuItem.setEnabled(mouseElm.canViewInScope());
                elmFloatScopeMenuItem.setEnabled(mouseElm.canViewInScope());
                CircuitSimulator simulator = simulator();
                if ((scopeManager.scopeCount + simulator.countScopeElms()) <= 1) {
                    elmAddScopeMenuItem.setCommand(new MyCommand("elm", "addToScope0"));
                    elmAddScopeMenuItem.setSubMenu(null);
                    elmAddScopeMenuItem.setEnabled(mouseElm.canViewInScope() && (scopeManager.scopeCount + simulator.countScopeElms()) > 0);
                } else {
                    composeSelectScopeMenu(selectScopeMenuBar);
                    elmAddScopeMenuItem.setCommand(null);
                    elmAddScopeMenuItem.setSubMenu(selectScopeMenuBar);
                    elmAddScopeMenuItem.setEnabled(mouseElm.canViewInScope());
                }
                elmEditMenuItem.setEnabled(mouseElm.getEditInfo(0) != null);
                elmSwapMenuItem.setEnabled(mouseElm.getPostCount() == 2);
                elmSplitMenuItem.setEnabled(CircuitUtils.canSplit(mouseElm));
                elmSliderMenuItem.setEnabled(CircuitUtils.sliderItemEnabled(mouseElm));
                boolean canFlipX = mouseElm.canFlipX();
                boolean canFlipY = mouseElm.canFlipY();
                boolean canFlipXY = mouseElm.canFlipXY();
                for (CircuitElm elm : simulator.elmList)
                    if (elm.isSelected()) {
                        if (!elm.canFlipX())
                            canFlipX = false;
                        if (!elm.canFlipY())
                            canFlipY = false;
                        if (!elm.canFlipXY())
                            canFlipXY = false;
                    }
                elmFlipXMenuItem.setEnabled(canFlipX);
                elmFlipYMenuItem.setEnabled(canFlipY);
                elmFlipXYMenuItem.setEnabled(canFlipXY);

                // Закриваємо попереднє контекстне меню, якщо воно існує
                if (contextPanel != null && contextPanel.isShowing()) {
                    contextPanel.hide();
                }

                contextPanel = new PopupPanel(true);
                contextPanel.add(elmMenuBar);
                contextPanel.setPopupPosition(menuClientX, menuClientY);
                contextPanel.show();
                CirSim.console("Element popup menu shown");
            } else {
                ScopeElm s = (ScopeElm) mouseElm;
                if (s.elmScope.canMenu()) {
                    menuPlot = s.elmScope.selectedPlot;
                    scopePopupMenu.doScopePopupChecks(true, false, false, false, s.elmScope);

                    // Закриваємо попереднє контекстне меню, якщо воно існує
                    if (contextPanel != null && contextPanel.isShowing()) {
                        contextPanel.hide();
                    }

                    contextPanel = new PopupPanel(true);
                    contextPanel.add(scopePopupMenu.getMenuBar());
                    contextPanel.setPopupPosition(menuClientX, menuClientY);
                    contextPanel.show();
                    CirSim.console("ScopeElm popup menu shown");
                } else {
                    CirSim.console("ScopeElm cannot show menu");
                }
            }
        } else {
            CirSim.console("Main popup menu");
            doMainMenuChecks();

            if (contextPanel != null && contextPanel.isShowing()) {
                contextPanel.hide();
            }

            contextPanel = new PopupPanel(true);
            contextPanel.add(mainMenuBar);
            x = Math.max(0, Math.min(menuClientX, renderer().canvasWidth - 400));
            y = Math.max(0, Math.min(menuClientY, renderer().canvasHeight - 450));
            contextPanel.setPopupPosition(x, y);
            contextPanel.show();
            CirSim.console("Main popup menu shown at: " + x + ", " + y);
        }
    }

    static int lastSubcircuitMenuUpdate;

    // check/uncheck/enable/disable menu items as appropriate when menu bar clicked on, or when
    // right mouse menu accessed.  also displays shortcuts as a side effect
    void doMainMenuChecks() {
        int c = mainMenuItems.size();
        int i;
        for (i = 0; i < c; i++) {
            String s = mainMenuItemNames.get(i);
            mainMenuItems.get(i).setState(s == circuitEditor().mouseModeStr);

            // Code to disable draw menu items when cct is not editable, but no used in this version as it
            // puts up a dialog box instead (see menuPerformed).
            //if (s.length() > 3 && s.substring(s.length()-3)=="Elm")
            //mainMenuItems.get(i).setEnabled(!noEditCheckItem.getState());
        }

        if (openLastClosedTabItem != null) {
            openLastClosedTabItem.setEnabled(cirSim.documentManager.hasClosedTabs());
        }

        ScopeManager scopeManager = scopeManager();

        stackAllItem.setEnabled(scopeManager.scopeCount > 1 && scopeManager.scopes[scopeManager.scopeCount - 1].position > 0);
        unstackAllItem.setEnabled(scopeManager.scopeCount > 1 && scopeManager.scopes[scopeManager.scopeCount - 1].position != scopeManager.scopeCount - 1);
        combineAllItem.setEnabled(scopeManager.scopeCount > 1);
        separateAllItem.setEnabled(scopeManager.scopeCount > 0);

        // also update the subcircuit menu if necessary
        if (lastSubcircuitMenuUpdate != CustomCompositeModel.sequenceNumber)
            composeSubcircuitMenu();
    }

    // save shortcuts to local storage
    public void saveShortcuts() {
        String str = "1";
        int i;
        // format: version;code1=ClassName;code2=ClassName;etc
        for (i = 0; i != shortcuts.length; i++) {
            String sh = shortcuts[i];
            if (sh == null)
                continue;
            str += ";" + i + "=" + sh;
        }
        OptionsManager.setOptionInStorage("shortcuts", str);
    }

    // load shortcuts from local storage
    void loadShortcuts() {
        String str = OptionsManager.getOptionFromStorage("shortcuts", null);
        if (str == null)
            return;
        String keys[] = str.split(";");

        // clear existing shortcuts
        int i;
        for (i = 0; i != shortcuts.length; i++)
            shortcuts[i] = null;

        clearShortcuts();

        // go through keys (skipping version at start)
        for (i = 1; i < keys.length; i++) {
            String arr[] = keys[i].split("=");
            if (arr.length != 2)
                continue;
            int c = Integer.parseInt(arr[0]);
            String className = arr[1];
            shortcuts[c] = className;

            setShortcut(className, c);
        }
    }


}
