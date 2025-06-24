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

package com.lushprojects.circuitjs1.client;

// GWT conversion (c) 2015 by Iain Sharp

// For information about the theory behind this, see Electronic Circuit & System Simulation Methods by Pillage
// or https://github.com/sharpie7/circuitjs1/blob/master/INTERNALS.md

import static com.google.gwt.event.dom.client.KeyCodes.KEY_A;
import static com.google.gwt.event.dom.client.KeyCodes.KEY_BACKSPACE;
import static com.google.gwt.event.dom.client.KeyCodes.KEY_C;
import static com.google.gwt.event.dom.client.KeyCodes.KEY_D;
import static com.google.gwt.event.dom.client.KeyCodes.KEY_DELETE;
import static com.google.gwt.event.dom.client.KeyCodes.KEY_ENTER;
import static com.google.gwt.event.dom.client.KeyCodes.KEY_ESCAPE;
import static com.google.gwt.event.dom.client.KeyCodes.KEY_N;
import static com.google.gwt.event.dom.client.KeyCodes.KEY_O;
import static com.google.gwt.event.dom.client.KeyCodes.KEY_P;
import static com.google.gwt.event.dom.client.KeyCodes.KEY_S;
import static com.google.gwt.event.dom.client.KeyCodes.KEY_SPACE;
import static com.google.gwt.event.dom.client.KeyCodes.KEY_V;
import static com.google.gwt.event.dom.client.KeyCodes.KEY_X;
import static com.google.gwt.event.dom.client.KeyCodes.KEY_Y;
import static com.google.gwt.event.dom.client.KeyCodes.KEY_Z;

import com.google.gwt.canvas.client.Canvas;
import com.google.gwt.canvas.dom.client.Context2d;
import com.google.gwt.core.client.Callback;
import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.core.client.JsArray;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.core.client.ScriptInjector;
import com.google.gwt.dom.client.CanvasElement;
import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.MetaElement;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.dom.client.NodeList;
import com.google.gwt.dom.client.Style.Overflow;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.event.dom.client.ContextMenuEvent;
import com.google.gwt.event.dom.client.ContextMenuHandler;
import com.google.gwt.event.dom.client.DoubleClickEvent;
import com.google.gwt.event.dom.client.DoubleClickHandler;
import com.google.gwt.event.dom.client.MouseDownEvent;
import com.google.gwt.event.dom.client.MouseDownHandler;
import com.google.gwt.event.dom.client.MouseEvent;
import com.google.gwt.event.dom.client.MouseMoveEvent;
import com.google.gwt.event.dom.client.MouseMoveHandler;
import com.google.gwt.event.dom.client.MouseOutEvent;
import com.google.gwt.event.dom.client.MouseOutHandler;
import com.google.gwt.event.dom.client.MouseUpEvent;
import com.google.gwt.event.dom.client.MouseUpHandler;
import com.google.gwt.event.dom.client.MouseWheelEvent;
import com.google.gwt.event.dom.client.MouseWheelHandler;
import com.google.gwt.event.logical.shared.ResizeEvent;
import com.google.gwt.event.logical.shared.ResizeHandler;
import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;
import com.google.gwt.http.client.URL;
import com.google.gwt.i18n.client.DateTimeFormat;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.storage.client.Storage;
import com.google.gwt.user.client.Command;
import com.google.gwt.user.client.DOM;
import com.google.gwt.user.client.Element;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.Event.NativePreviewEvent;
import com.google.gwt.user.client.Event.NativePreviewHandler;
import com.google.gwt.user.client.EventListener;
import com.google.gwt.user.client.Timer;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.Window.ClosingEvent;
import com.google.gwt.user.client.Window.Navigator;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.CellPanel;
import com.google.gwt.user.client.ui.DockLayoutPanel;
import com.google.gwt.user.client.ui.Frame;
import com.google.gwt.user.client.ui.HorizontalPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.MenuBar;
import com.google.gwt.user.client.ui.MenuItem;
import com.google.gwt.user.client.ui.PopupPanel;
import com.google.gwt.user.client.ui.RootLayoutPanel;
import com.google.gwt.user.client.ui.RootPanel;
import com.google.gwt.user.client.ui.ScrollPanel;
import com.google.gwt.user.client.ui.VerticalPanel;
import com.google.gwt.user.client.ui.Widget;
import com.lushprojects.circuitjs1.client.util.Locale;

import java.util.Date;
import java.util.HashMap;
import java.util.Vector;

public class CirSim implements MouseDownHandler, MouseMoveHandler, MouseUpHandler,
        ClickHandler, DoubleClickHandler, ContextMenuHandler, NativePreviewHandler,
        MouseOutHandler, MouseWheelHandler {

    static final int HINT_LC = 1;
    static final int HINT_RC = 2;
    static final int HINT_3DB_C = 3;
    static final int HINT_TWINT = 4;
    static final int HINT_3DB_L = 5;

    static final int MODE_ADD_ELM = 0;
    static final int MODE_DRAG_ALL = 1;
    static final int MODE_DRAG_ROW = 2;
    static final int MODE_DRAG_COLUMN = 3;
    static final int MODE_DRAG_SELECTED = 4;
    static final int MODE_DRAG_POST = 5;
    static final int MODE_SELECT = 6;
    static final int MODE_DRAG_SPLITTER = 7;

    static int MENU_BAR_HEIGHT = 30;
    static final int TOOLBAR_HEIGHT = 40;
    static int VERTICAL_PANEL_WIDTH = 166; // default
    static final int POST_GRAB_SQ = 25;
    static final int MIN_POST_GRAB_SIZE = 256;

    static final int INFO_WIDTH = 160;

    CircuitSimulator simulator = new CircuitSimulator(this);
    CircuitRenderer renderer = new CircuitRenderer(this);

    ScopeManager scopeManager = new ScopeManager(this);
    ClipboardManager clipboardManager = new ClipboardManager(this);
    DialogManager dialogManager = new DialogManager(this);

    Button resetButton;
    Button runStopButton;
    Button dumpMatrixButton;
    MenuItem aboutItem;
    MenuItem helpItem;
    MenuItem licenseItem;
    // MenuItem testItem;
    MenuItem aboutCircuitsItem;
    MenuItem aboutCircuitsPLItem;
    MenuItem closeItem;
    //CheckboxMenuItem fullscreenCheckItem;
    MenuItem importFromLocalFileItem, importFromTextItem, exportAsUrlItem, exportAsLocalFileItem, exportAsTextItem,
            printItem, recoverItem, saveFileItem;
    //MenuItem importFromDropboxItem;
    MenuItem undoItem, redoItem, cutItem, copyItem, pasteItem, selectAllItem, optionsItem, flipXItem, flipYItem, flipXYItem, modItem;
    MenuBar optionsMenuBar;
    CheckboxMenuItem dotsCheckItem;
    CheckboxMenuItem voltsCheckItem;
    CheckboxMenuItem powerCheckItem;
    CheckboxMenuItem smallGridCheckItem;
    CheckboxMenuItem crossHairCheckItem;
    CheckboxMenuItem showValuesCheckItem;
    CheckboxMenuItem conductanceCheckItem;
    CheckboxMenuItem euroResistorCheckItem;
    CheckboxMenuItem euroGatesCheckItem;
    CheckboxMenuItem printableCheckItem;
    CheckboxMenuItem conventionCheckItem;
    CheckboxMenuItem noEditCheckItem;
    CheckboxMenuItem mouseWheelEditCheckItem;
    CheckboxMenuItem toolbarCheckItem;
    CheckboxMenuItem mouseModeCheckItem;
    Label powerLabel;
    Label titleLabel;
    Scrollbar speedBar;
    Scrollbar currentBar;
    Scrollbar powerBar;
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
    boolean hideMenu = false;
    MenuBar selectScopeMenuBar;
    Vector<MenuItem> selectScopeMenuItems;
    MenuBar subcircuitMenuBar[];
    MenuItem scopeRemovePlotMenuItem;
    MenuItem scopeSelectYMenuItem;
    ScopePopupMenu scopePopupMenu;
    Element sidePanelCheckboxLabel;

    String lastCursorStyle;
    boolean mouseWasOverSplitter = false;

    PopupPanel contextPanel = null;

    int mouseMode = MODE_SELECT;
    int tempMouseMode = MODE_SELECT;

    String mouseModeStr = "Select";

    int dragGridX, dragGridY, dragScreenX, dragScreenY, initDragGridX, initDragGridY;
    boolean dragging;

    long mouseDownTime;
    long zoomTime;
    int mouseCursorX = -1;
    int mouseCursorY = -1;
    Rectangle selectedArea;
    int gridSize, gridMask, gridRound;
    boolean savedFlag;
    boolean dcAnalysisFlag;
    // boolean useBufferedImage;

    boolean isMac;
    String ctrlMetaKey;

    double t; // TODO: tick ???

    int pause = 10;
    int menuPlot = -1;
    int hintType = -1, hintItem1, hintItem2;

    Vector<Adjustable> adjustables;
    // Vector setupList;
    CircuitElm dragElm, menuElm;

    CircuitElm mouseElm = null;
    boolean didSwitch = false;
    int mousePost = -1;
    CircuitElm plotXElm, plotYElm;
    int draggingPost;

    SwitchElm heldSwitchElm;

    double wheelSensitivity = 1;
    boolean developerMode;

    boolean showResistanceInVoltageSources;
    boolean hideInfoBox;


    // Class dumpTypes[], shortcuts[];
    String shortcuts[];
    String recovery;
    Vector<UndoItem> undoStack, redoStack;
    static boolean unsavedChanges;
    static String filePath;
    static String fileName;
    static String lastFileName;
    HashMap<String, String> classToLabelMap;
    Toolbar toolbar;

    DockLayoutPanel layoutPanel;
    MenuBar menuBar;
    MenuBar fileMenuBar;
    VerticalPanel verticalPanel;
    VerticalPanel verticalPanel2;
    ScrollPanel slidersPanel;
    CellPanel buttonPanel;
    private boolean mouseDragging;

    Vector<CheckboxMenuItem> mainMenuItems = new Vector<CheckboxMenuItem>();
    Vector<String> mainMenuItemNames = new Vector<String>();

    LoadFile loadFileInput;
    Frame iFrame = null;

    static Button absResetBtn;
    static Button absRunStopBtn;

    @Deprecated
    static CirSim theSim;

    final Timer timer = new Timer() {
        public void run() {
            renderer.updateCircuit();
        }
    };
    final int FASTTIMER = 16;

    static native float devicePixelRatio() /*-{
        return window.devicePixelRatio;
    }-*/;

    void redrawCanvasSize() {
        layoutPanel.setWidgetSize(menuBar, MENU_BAR_HEIGHT);
        if (MENU_BAR_HEIGHT < 30) menuBar.addStyleName("modSmallMenuBar");
        else menuBar.removeStyleName("modSmallMenuBar");
        setCanvasSize();
        repaint();
    }

    native boolean isMobile(Element element) /*-{
        if (!element)
            return false;
        var style = getComputedStyle(element);
        return style.display != 'none';
    }-*/;

    public void setCanvasSize() {
        Storage lstor = Storage.getLocalStorageIfSupported();

        int width, height;
        width = (int) RootLayoutPanel.get().getOffsetWidth();
        height = (int) RootLayoutPanel.get().getOffsetHeight();
        height = height - (hideMenu ? 0 : MENU_BAR_HEIGHT);

        if (isSidePanelCheckboxChecked() && lstor.getItem("MOD_overlayingSidebar") == "false")
            width = width - VERTICAL_PANEL_WIDTH;
        if (toolbarCheckItem.getState())
            height -= TOOLBAR_HEIGHT;

        width = Math.max(width, 0);   // avoid exception when setting negative width
        height = Math.max(height, 0);

        renderer.setCanvasSize(width, height);

        renderer.setCircuitArea();

        // recenter circuit in case canvas was hidden at startup
        if (renderer.transform[0] == 0)
            renderer.centreCircuit();
    }

    native String decompress(String dump) /*-{
        return $wnd.LZString.decompressFromEncodedURIComponent(dump);
    }-*/;

    public static void executeJS(String js) {
        ScriptInjector.fromString(js)
                .setWindow(ScriptInjector.TOP_WINDOW)
                .inject();
    }

    // this code is taken from original ExportAsLocalFileDialog.java:

    public static void setLastFileName(String s) {
        // remember filename for use when saving a new file.
        // if s is null or automatically generated then just clear out old filename.
        if (s == null || s.startsWith("circuitjs-"))
            lastFileName = null;
        else
            lastFileName = s;
    }

    public String getLastFileName() {
        Date date = new Date();
        String fname;
        if (lastFileName != null)
            fname = lastFileName;
        else {
            DateTimeFormat dtf = DateTimeFormat.getFormat("yyyyMMdd-HHmmss");
            fname = "circuitjs-" + dtf.format(date) + ".txt";
        }
        return fname;
    }

    static native float getDefaultScale() /*-{
		$wnd.nw.Screen.Init();
		var dwidth = $wnd.nw.Screen.screens[0].bounds.width;
		var defaultScale;
		if (dwidth >= 1960)
			defaultScale = 1.6; // 2-0.4 and etc.
		else if (dwidth >= 1752 && dwidth < 1960)
			defaultScale = 1.1; // -0.4
		else if (dwidth >= 1600 && dwidth < 1752)
			defaultScale = 0.7; // -0.3
		else if (dwidth >= 1460 && dwidth < 1600)
			defaultScale = 0.3; // -0.2
		else if (dwidth >= 1200 && dwidth < 1460)
			defaultScale = -0.1; // -0.1
		else if (dwidth < 1200)
			defaultScale = -0.3;
		return defaultScale;
	}-*/;

    public static native void setSidebarAnimation(String duration, String speedcurve) /*-{
		var triggerLabel = $doc.querySelector(".triggerLabel");
		var sidebar = $doc.querySelector(".trigger+.triggerLabel+div");
		// property name | duration | timing function | delay
		var split = " "+duration+"ms "+speedcurve;
		triggerLabel.style.transition = (duration=="none") ? duration : "right"+split;
		sidebar.style.transition = (duration=="none") ? duration : "width"+split;
	}-*/;

    static int getAbsBtnsTopPos() {
        Storage lstor = Storage.getLocalStorageIfSupported();
        int top = 50;
        if (lstor.getItem("MOD_TopMenuBar") == "small") top -= 11;
        if (lstor.getItem("toolbar") != "false") top += TOOLBAR_HEIGHT;
        return top;
    }

    void modSetDefault() {

        Storage lstor = Storage.getLocalStorageIfSupported();
        // KEYS:
        String MOD_UIScale = lstor.getItem("MOD_UIScale");
        String MOD_TopMenuBar = lstor.getItem("MOD_TopMenuBar");
        String MOD_absBtnTheme = lstor.getItem("MOD_absBtnTheme");
        String MOD_absBtnIcon = lstor.getItem("MOD_absBtnIcon");
        String MOD_hideAbsBtns = lstor.getItem("MOD_hideAbsBtns");
        String MOD_overlayingSidebar = lstor.getItem("MOD_overlayingSidebar");
        String MOD_showSidebaronStartup = lstor.getItem("MOD_showSidebaronStartup");
        String MOD_overlayingSBAnimation = lstor.getItem("MOD_overlayingSBAnimation");
        String MOD_SBAnim_duration = lstor.getItem("MOD_SBAnim_duration");
        String MOD_SBAnim_SpeedCurve = lstor.getItem("MOD_SBAnim_SpeedCurve");
        String MOD_setPauseWhenWinUnfocused = lstor.getItem("MOD_setPauseWhenWinUnfocused");

        if (MOD_UIScale == null) {
            lstor.setItem("MOD_UIScale", Float.toString(getDefaultScale()));
            executeJS("nw.Window.get().zoomLevel = " + getDefaultScale());
        } else executeJS("nw.Window.get().zoomLevel = " + MOD_UIScale);
        if (MOD_TopMenuBar == null) lstor.setItem("MOD_TopMenuBar", "standart");
        else if (MOD_TopMenuBar == "small") {
            MENU_BAR_HEIGHT = 20;
            redrawCanvasSize();
        }
        if (MOD_absBtnTheme == null) lstor.setItem("MOD_absBtnTheme", "default");
        else if (MOD_absBtnTheme == "classic") {
            absRunStopBtn.removeStyleName("modDefaultRunStopBtn");
            absRunStopBtn.addStyleName("gwt-Button");
            absRunStopBtn.addStyleName("modClassicButton");
            absResetBtn.removeStyleName("modDefaultResetBtn");
            absResetBtn.addStyleName("gwt-Button");
            absResetBtn.addStyleName("modClassicButton");
        }
        if (MOD_absBtnIcon == null) lstor.setItem("MOD_absBtnIcon", "stop");
        else if (MOD_absBtnIcon == "pause") {
            absRunStopBtn.getElement().setInnerHTML("&#xE802;");
        }
        if (MOD_hideAbsBtns == null) lstor.setItem("MOD_hideAbsBtns", "false");
        else if (MOD_hideAbsBtns == "true") {
            absRunStopBtn.setVisible(false);
            absResetBtn.setVisible(false);
        }
        if (MOD_overlayingSidebar == null) lstor.setItem("MOD_overlayingSidebar", "false");
        if (MOD_showSidebaronStartup == null) lstor.setItem("MOD_showSidebaronStartup", "false");
        else if (MOD_showSidebaronStartup == "true")
            executeJS("document.getElementById(\"trigger\").checked = true");
        if (MOD_SBAnim_duration == null || MOD_SBAnim_SpeedCurve == null) {
            lstor.setItem("MOD_SBAnim_duration", "500");
            lstor.setItem("MOD_SBAnim_SpeedCurve", "ease");
            //if (lstor.getItem("MOD_overlayingSBAnimation")) setSidebarAnimation("500","ease");
        }
        if (MOD_overlayingSBAnimation == null) lstor.setItem("MOD_overlayingSBAnimation", "false");
        if (MOD_overlayingSidebar == "true" && MOD_overlayingSBAnimation == "true") {
            setSidebarAnimation(lstor.getItem("MOD_SBAnim_duration"), lstor.getItem("MOD_SBAnim_SpeedCurve"));
        } else setSidebarAnimation("none", "");
        if (MOD_setPauseWhenWinUnfocused == null)
            lstor.setItem("MOD_setPauseWhenWinUnfocused", "true");
    }

    CirSim() {
        theSim = this;
    }

    String startCircuit = null;
    String startLabel = null;
    String startCircuitText = null;
    String startCircuitLink = null;
//    String baseURL = "http://www.falstad.com/circuit/";

    public void init() {

        //sets the meta tag to allow the css media queries to work
        MetaElement meta = Document.get().createMetaElement();
        meta.setName("viewport");
        meta.setContent("width=device-width");
        NodeList<com.google.gwt.dom.client.Element> node = Document.get().getElementsByTagName("head");
        node.getItem(0).appendChild(meta);


        boolean printable = false;
        boolean convention = true;
        boolean euroRes = false;
        boolean usRes = false;
        boolean running = true;
        boolean hideSidebar = false;
        boolean noEditing = false;
        boolean mouseWheelEdit = false;
        MenuBar m;

        CircuitElm.initClass(this);
        readRecovery();

        QueryParameters qp = new QueryParameters();
        String positiveColor = null;
        String negativeColor = null;
        String neutralColor = null;
        String selectColor = null;
        String currentColor = null;
        String mouseModeReq = null;
        boolean euroGates = false;

        try {
            //baseURL = applet.getDocumentBase().getFile();
            // look for circuit embedded in URL
            //		String doc = applet.getDocumentBase().toString();
            String cct = qp.getValue("cct");
            if (cct != null)
                startCircuitText = cct.replace("%24", "$");
            if (startCircuitText == null)
                startCircuitText = getElectronStartCircuitText();
            String ctz = qp.getValue("ctz");
            if (ctz != null)
                startCircuitText = decompress(ctz);
            startCircuit = qp.getValue("startCircuit");
            startLabel = qp.getValue("startLabel");
            startCircuitLink = qp.getValue("startCircuitLink");
            euroRes = qp.getBooleanValue("euroResistors", false);
            euroGates = qp.getBooleanValue("IECGates", getOptionFromStorage("euroGates", weAreInGermany()));
            usRes = qp.getBooleanValue("usResistors", false);
            running = qp.getBooleanValue("running", true);
            hideSidebar = qp.getBooleanValue("hideSidebar", false);
            hideMenu = qp.getBooleanValue("hideMenu", false);
            printable = qp.getBooleanValue("whiteBackground", getOptionFromStorage("whiteBackground", false));
            convention = qp.getBooleanValue("conventionalCurrent",
                    getOptionFromStorage("conventionalCurrent", true));
            noEditing = !qp.getBooleanValue("editable", true);
            mouseWheelEdit = qp.getBooleanValue("mouseWheelEdit", getOptionFromStorage("mouseWheelEdit", true));
            positiveColor = qp.getValue("positiveColor");
            negativeColor = qp.getValue("negativeColor");
            neutralColor = qp.getValue("neutralColor");
            selectColor = qp.getValue("selectColor");
            currentColor = qp.getValue("currentColor");
            mouseModeReq = qp.getValue("mouseMode");
            hideInfoBox = qp.getBooleanValue("hideInfoBox", false);
        } catch (Exception e) {
        }

        boolean euroSetting = false;
        if (euroRes)
            euroSetting = true;
        else if (usRes)
            euroSetting = false;
        else
            euroSetting = getOptionFromStorage("euroResistors", !weAreInUS(true));

        String os = Navigator.getPlatform();
        isMac = (os.toLowerCase().contains("mac"));
        ctrlMetaKey = (isMac) ? Locale.LS("Cmd-") : Locale.LS("Ctrl-");

        shortcuts = new String[127];

        RootLayoutPanel.get().add(absResetBtn = new Button("&#8634;",
                new ClickHandler() {
                    public void onClick(ClickEvent event) {
                        resetAction();
                    }
                }));

        RootLayoutPanel.get().add(absRunStopBtn = new Button("&#xE800;",
                new ClickHandler() {
                    public void onClick(ClickEvent event) {
                        setSimRunning(!simIsRunning());
                        executeJS("SetBtnsStyle()");
                    }
                }));

        absResetBtn.setStyleName("btn-top-pos reset-btn reset-btn-pos modDefaultResetBtn");
        absRunStopBtn.setStyleName("btn-top-pos run-stop-btn run-stop-btn-pos modDefaultRunStopBtn");
        absResetBtn.getElement().setTitle("Reset");
        absRunStopBtn.getElement().setTitle("Run/Stop");

        layoutPanel = new DockLayoutPanel(Unit.PX);

        fileMenuBar = new MenuBar(true);
        fileMenuBar.addItem(menuItemWithShortcut("window", "New Window...", Locale.LS(ctrlMetaKey + "N"),
                new MyCommand("file", "newwindow")));
        fileMenuBar.addItem(iconMenuItem("doc-new", "New Blank Circuit", new MyCommand("file", "newblankcircuit")));
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
        fileMenuBar.addItem(iconMenuItem("image", "Export As Image...", new MyCommand("file", "exportasimage")));
        fileMenuBar.addItem(iconMenuItem("image", "Copy Circuit Image to Clipboard", new MyCommand("file", "copypng")));
        fileMenuBar.addItem(iconMenuItem("image", "Export As SVG...", new MyCommand("file", "exportassvg")));
        fileMenuBar.addItem(iconMenuItem("microchip", "Create Subcircuit...", new MyCommand("file", "createsubcircuit")));
        fileMenuBar.addItem(iconMenuItem("magic", "Find DC Operating Point", new MyCommand("file", "dcanalysis")));
        recoverItem = iconMenuItem("back-in-time", "Recover Auto-Save", new MyCommand("file", "recover"));
        recoverItem.setEnabled(recovery != null);
        fileMenuBar.addItem(recoverItem);
        printItem = menuItemWithShortcut("print", "Print...", Locale.LS(ctrlMetaKey + "P"), new MyCommand("file", "print"));
        fileMenuBar.addItem(printItem);
        fileMenuBar.addSeparator();
        fileMenuBar.addItem(iconMenuItem("resize-full-alt", "Toggle Full Screen", new MyCommand("view", "fullscreen")));
        fileMenuBar.addSeparator();
        fileMenuBar.addItem(iconMenuItem("exit", "Exit",
                new Command() {
                    public void execute() {
                        executeJS("nw.Window.get().close(true)");
                    }
                }));
	/*
	aboutItem = iconMenuItem("info-circled", "About...", (Command)null);
	fileMenuBar.addItem(aboutItem);
	aboutItem.setScheduledCommand(new MyCommand("file","about"));
	*/
        int width = (int) RootLayoutPanel.get().getOffsetWidth();
        VERTICAL_PANEL_WIDTH = 166; /* = width/5;
	if (VERTICALPANELWIDTH > 166)
	    VERTICALPANELWIDTH = 166;
	if (VERTICALPANELWIDTH < 128)
	    VERTICALPANELWIDTH = 128;*/

        menuBar = new MenuBar();
        menuBar.addItem(Locale.LS("File"), fileMenuBar);
        verticalPanel = new VerticalPanel();
        slidersPanel = new ScrollPanel();
        verticalPanel2 = new VerticalPanel();

        verticalPanel.getElement().addClassName("verticalPanel");
        verticalPanel.getElement().setId("painel");
        Element sidePanelCheckbox = DOM.createInputCheck();
        sidePanelCheckboxLabel = DOM.createLabel();
        sidePanelCheckboxLabel.addClassName("triggerLabel");
        sidePanelCheckbox.setId("trigger");
        sidePanelCheckboxLabel.setAttribute("for", "trigger");
        sidePanelCheckbox.addClassName("trigger");
        Event.sinkEvents(sidePanelCheckbox, Event.ONCLICK);
        Event.setEventListener(sidePanelCheckbox, new EventListener() {
            public void onBrowserEvent(Event event) {
                if (Event.ONCLICK == event.getTypeInt()) {
                    Storage lstor = Storage.getLocalStorageIfSupported();
                    scopeManager.setupScopes();
                    executeJS("SetBtnsStyle();");
                    setCanvasSize();
                    if (lstor.getItem("MOD_overlayingSidebar") == "false") {
                        if (isSidePanelCheckboxChecked())
                            renderer.transform[4] -= VERTICAL_PANEL_WIDTH / 2;
                        else
                            renderer.transform[4] += VERTICAL_PANEL_WIDTH / 2;
                    }
                }
            }
        });
        Element topPanelCheckbox = DOM.createInputCheck();
        Element topPanelCheckboxLabel = DOM.createLabel();
        topPanelCheckbox.setId("toptrigger");
        topPanelCheckbox.addClassName("toptrigger");
        topPanelCheckboxLabel.addClassName("toptriggerlabel");
        topPanelCheckboxLabel.setAttribute("for", "toptrigger");

        // make buttons side by side if there's room
        buttonPanel = (VERTICAL_PANEL_WIDTH == 166) ? new HorizontalPanel() : new VerticalPanel();

        m = new MenuBar(true);
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
        m.addItem(iconMenuItem("target", weAreInUS(false) ? "Center Circuit" : "Centre Circuit", new MyCommand("edit", "centrecircuit")));
        m.addItem(menuItemWithShortcut("zoom-11", "Zoom 100%", "0", new MyCommand("zoom", "zoom100")));
        m.addItem(menuItemWithShortcut("zoom-in", "Zoom In", "+", new MyCommand("zoom", "zoomin")));
        m.addItem(menuItemWithShortcut("zoom-out", "Zoom Out", "-", new MyCommand("zoom", "zoomout")));
        m.addItem(flipXItem = iconMenuItem("flip-x", "Flip X", new MyCommand("edit", "flipx")));
        m.addItem(flipYItem = iconMenuItem("flip-y", "Flip Y", new MyCommand("edit", "flipy")));
        m.addItem(flipXYItem = iconMenuItem("flip-x-y", "Flip XY", new MyCommand("edit", "flipxy")));
        menuBar.addItem(Locale.LS("Edit"), m);

        MenuBar drawMenuBar = new MenuBar(true);
        drawMenuBar.setAutoOpen(true);

        menuBar.addItem(Locale.LS("Draw"), drawMenuBar);

        m = new MenuBar(true);
        m.addItem(stackAllItem = iconMenuItem("lines", "Stack All", new MyCommand("scopes", "stackAll")));
        m.addItem(unstackAllItem = iconMenuItem("columns", "Unstack All", new MyCommand("scopes", "unstackAll")));
        m.addItem(combineAllItem = iconMenuItem("object-group", "Combine All", new MyCommand("scopes", "combineAll")));
        m.addItem(separateAllItem = iconMenuItem("object-ungroup", "Separate All", new MyCommand("scopes", "separateAll")));
        menuBar.addItem(Locale.LS("Scopes"), m);

        optionsMenuBar = m = new MenuBar(true);
        menuBar.addItem(Locale.LS("Options"), optionsMenuBar);
        m.addItem(dotsCheckItem = new CheckboxMenuItem(Locale.LS("Show Current")));
        dotsCheckItem.setState(true);
        m.addItem(voltsCheckItem = new CheckboxMenuItem(Locale.LS("Show Voltage"),
                new Command() {
                    public void execute() {
                        if (voltsCheckItem.getState())
                            powerCheckItem.setState(false);
                        setPowerBarEnable();
                    }
                }));
        voltsCheckItem.setState(true);
        m.addItem(powerCheckItem = new CheckboxMenuItem(Locale.LS("Show Power"),
                new Command() {
                    public void execute() {
                        if (powerCheckItem.getState())
                            voltsCheckItem.setState(false);
                        setPowerBarEnable();
                    }
                }));
        m.addItem(showValuesCheckItem = new CheckboxMenuItem(Locale.LS("Show Values")));
        showValuesCheckItem.setState(true);
        //m.add(conductanceCheckItem = getCheckItem(LS("Show Conductance")));
        m.addItem(smallGridCheckItem = new CheckboxMenuItem(Locale.LS("Small Grid"),
                new Command() {
                    public void execute() {
                        setGrid();
                    }
                }));
        m.addItem(toolbarCheckItem = new CheckboxMenuItem(Locale.LS("Toolbar"),
                new Command() {
                    public void execute() {
                        setOptionInStorage("toolbar", toolbarCheckItem.getState());
                        setToolbar();
                    }
                }));
        toolbarCheckItem.setState(getOptionFromStorage("toolbar", true));
        m.addItem(mouseModeCheckItem = new CheckboxMenuItem(Locale.LS("Show Mode"),
                new Command() {
                    public void execute() {
                        setOptionInStorage("showMouseMode", mouseModeCheckItem.getState());
                    }
                }));
        mouseModeCheckItem.setState(getOptionFromStorage("showMouseMode", true));
        m.addItem(crossHairCheckItem = new CheckboxMenuItem(Locale.LS("Show Cursor Cross Hairs"),
                new Command() {
                    public void execute() {
                        setOptionInStorage("crossHair", crossHairCheckItem.getState());
                    }
                }));
        crossHairCheckItem.setState(getOptionFromStorage("crossHair", false));
        m.addItem(euroResistorCheckItem = new CheckboxMenuItem(Locale.LS("European Resistors"),
                new Command() {
                    public void execute() {
                        setOptionInStorage("euroResistors", euroResistorCheckItem.getState());
                        toolbar.setEuroResistors(euroResistorCheckItem.getState());
                    }
                }));
        euroResistorCheckItem.setState(euroSetting);
        m.addItem(euroGatesCheckItem = new CheckboxMenuItem(Locale.LS("IEC Gates"),
                new Command() {
                    public void execute() {
                        setOptionInStorage("euroGates", euroGatesCheckItem.getState());
                        int i;
                        for (i = 0; i != simulator.elmList.size(); i++)
                            getElm(i).setPoints();
                    }
                }));
        euroGatesCheckItem.setState(euroGates);
        m.addItem(printableCheckItem = new CheckboxMenuItem(Locale.LS("White Background"),
                new Command() {
                    public void execute() {
                        scopeManager.updateScopes();
                        setOptionInStorage("whiteBackground", printableCheckItem.getState());
                    }
                }));
        printableCheckItem.setState(printable);

        m.addItem(conventionCheckItem = new CheckboxMenuItem(Locale.LS("Conventional Current Motion"),
                new Command() {
                    public void execute() {
                        setOptionInStorage("conventionalCurrent", conventionCheckItem.getState());
                        String cc = CircuitElm.currentColor.getHexValue();
                        // change the current color if it hasn't changed from the default
                        if (cc.equals("#ffff00") || cc.equals("#00ffff"))
                            CircuitElm.currentColor = conventionCheckItem.getState() ? Color.yellow : Color.cyan;
                    }
                }));
        conventionCheckItem.setState(convention);
        m.addItem(noEditCheckItem = new CheckboxMenuItem(Locale.LS("Disable Editing")));
        noEditCheckItem.setState(noEditing);

        m.addItem(mouseWheelEditCheckItem = new CheckboxMenuItem(Locale.LS("Edit Values With Mouse Wheel"),
                new Command() {
                    public void execute() {
                        setOptionInStorage("mouseWheelEdit", mouseWheelEditCheckItem.getState());
                    }
                }));
        mouseWheelEditCheckItem.setState(mouseWheelEdit);

        m.addItem(new CheckboxAlignedMenuItem(Locale.LS("Shortcuts..."), new MyCommand("options", "shortcuts")));
        m.addItem(new CheckboxAlignedMenuItem(Locale.LS("Subcircuits..."), new MyCommand("options", "subcircuits")));
        m.addItem(optionsItem = new CheckboxAlignedMenuItem(Locale.LS("Other Options..."), new MyCommand("options", "other")));
        m.addItem(modItem = new CheckboxAlignedMenuItem("Modification Setup...", new MyCommand("options", "modsetup")));
        modItem.addStyleName("modItem");
        if (isElectron())
            m.addItem(new CheckboxAlignedMenuItem(Locale.LS("Toggle Dev Tools"), new MyCommand("options", "devtools")));

        mainMenuBar = new MenuBar(true);
        mainMenuBar.setAutoOpen(true);
        composeMainMenu(mainMenuBar, 0);
        composeMainMenu(drawMenuBar, 1);
        loadShortcuts();

        DOM.appendChild(layoutPanel.getElement(), topPanelCheckbox);
        DOM.appendChild(layoutPanel.getElement(), topPanelCheckboxLabel);

        toolbar = new Toolbar();
        toolbar.setEuroResistors(euroSetting);
        if (!hideMenu)
            layoutPanel.addNorth(menuBar, MENU_BAR_HEIGHT);

        // add toolbar immediately after menuBar
        layoutPanel.addNorth(toolbar, TOOLBAR_HEIGHT);

        if (hideSidebar)
            VERTICAL_PANEL_WIDTH = 0;
        else {
            DOM.appendChild(layoutPanel.getElement(), sidePanelCheckbox);
            DOM.appendChild(layoutPanel.getElement(), sidePanelCheckboxLabel);
            layoutPanel.addEast(verticalPanel, VERTICAL_PANEL_WIDTH);
        }
        menuBar.getElement().insertFirst(menuBar.getElement().getChild(1));
        menuBar.getElement().getFirstChildElement().setAttribute("onclick", "document.getElementsByClassName('toptrigger')[0].checked = false");
        RootLayoutPanel.get().add(layoutPanel);

        Canvas cv = renderer.initCanvas();
        if (cv == null) {
            RootPanel.get().add(new Label("Not working. You need a browser that supports the CANVAS element."));
            return;
        }

        modSetDefault();

        Window.addResizeHandler(new ResizeHandler() {
            public void onResize(ResizeEvent event) {
                repaint();
                setSlidersPanelHeight();
            }
        });

        setToolbar(); // calls setCanvasSize()
        layoutPanel.add(cv);
        verticalPanel.add(buttonPanel);
        buttonPanel.addStyleName("sidePanelElm");
        buttonPanel.add(resetButton = new Button(Locale.LS("Reset")));
        resetButton.addClickHandler(new ClickHandler() {
            public void onClick(ClickEvent event) {
                resetAction();
            }
        });
        resetButton.setStylePrimaryName("topButton");
        buttonPanel.add(runStopButton = new Button(Locale.LSHTML("<Strong>RUN</Strong>&nbsp;/&nbsp;Stop")));
        runStopButton.addClickHandler(new ClickHandler() {
            public void onClick(ClickEvent event) {
                setSimRunning(!simIsRunning());
            }
        });


/*
	dumpMatrixButton = new Button("Dump Matrix");
	dumpMatrixButton.addClickHandler(new ClickHandler() {
	    public void onClick(ClickEvent event) { dumpMatrix = true; }});
	verticalPanel.add(dumpMatrixButton);// IES for debugging
*/


        if (LoadFile.isSupported()) {
            verticalPanel.add(loadFileInput = new LoadFile(this));
            loadFileInput.addStyleName("sidePanelElm");
            setSlidersPanelHeight();
        }

        Label l;
        verticalPanel.add(l = new Label(Locale.LS("Simulation Speed")));
        l.addStyleName("topSpace");
        l.addStyleName("sidePanelElm");

        // was max of 140
        verticalPanel.add(speedBar = new Scrollbar(Scrollbar.HORIZONTAL, 3, 1, 0, 260));
        speedBar.addStyleName("sidePanelElm");

        verticalPanel.add(l = new Label(Locale.LS("Current Speed")));
        l.addStyleName("topSpace");
        l.addStyleName("sidePanelElm");

        currentBar = new Scrollbar(Scrollbar.HORIZONTAL, 50, 1, 1, 100);
        verticalPanel.add(currentBar);
        currentBar.addStyleName("sidePanelElm");

        verticalPanel.add(powerLabel = new Label(Locale.LS("Power Brightness")));
        powerLabel.addStyleName("topSpace");
        powerLabel.addStyleName("sidePanelElm");

        verticalPanel.add(powerBar = new Scrollbar(Scrollbar.HORIZONTAL,
                50, 1, 1, 100));
        powerBar.addStyleName("sidePanelElm");
        setPowerBarEnable();

        //	verticalPanel.add(new Label(""));
        //        Font f = new Font("SansSerif", 0, 10);
        l = new Label(Locale.LS("Current Circuit:"));
        l.addStyleName("topSpace");
        l.addStyleName("sidePanelElm");
        //        l.setFont(f);
        titleLabel = new Label("Label");
        titleLabel.addStyleName("sidePanelElm");
        //        titleLabel.setFont(f);
        verticalPanel.add(l);
        verticalPanel.add(titleLabel);

        Label sab;
        sab = new Label(Locale.LS("Sliders and buttons") + ":");
        sab.addStyleName("sabLabel");
        verticalPanel.add(sab);

        verticalPanel.add(slidersPanel);
        slidersPanel.add(verticalPanel2);
        verticalPanel2.addStyleName("sidePanelvp2");
        verticalPanel2.setWidth("150px");

        //slidersPanel.setAlwaysShowScrollBars(true);
        slidersPanel.getElement().getStyle().setOverflowX(Overflow.HIDDEN);
        slidersPanel.getElement().getStyle().setOverflowY(Overflow.SCROLL);

        setGrid();
        adjustables = new Vector<Adjustable>();
        //	setupList = new Vector();
        undoStack = new Vector<UndoItem>();
        redoStack = new Vector<UndoItem>();


        //	cv.setBackground(Color.black);
        //	cv.setForeground(Color.lightGray);

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
                        scopeManager.scopeMenuSelected = currentItem;
                        break;
                    case Event.ONMOUSEOUT:
                        scopeManager.scopeMenuSelected = -1;
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

        scopePopupMenu = new ScopePopupMenu();

        setColors(positiveColor, negativeColor, neutralColor, selectColor, currentColor);
        setWheelSensitivity();

        if (startCircuitText != null) {
            getSetupList(false);
            readCircuit(startCircuitText);
            unsavedChanges = false;
            changeWindowTitle(unsavedChanges);
        } else {
            if (simulator.stopMessage == null && startCircuitLink != null) {
                readCircuit("");
                getSetupList(false);
                //ImportFromDropboxDialog.setSim(this);
                //ImportFromDropboxDialog.doImportDropboxLink(startCircuitLink, false);
            } else {
                readCircuit("");
                if (simulator.stopMessage == null && startCircuit != null) {
                    getSetupList(false);
                    readSetupFile(startCircuit, startLabel);
                } else
                    getSetupList(true);
            }
        }

        if (mouseModeReq != null)
            menuPerformed("main", mouseModeReq);

        enableUndoRedo();
        enablePaste();
        enableDisableMenuItems();
        setSlidersPanelHeight();
        cv.addMouseDownHandler(this);
        cv.addMouseMoveHandler(this);
        cv.addMouseOutHandler(this);
        cv.addMouseUpHandler(this);
        cv.addClickHandler(this);
        cv.addDoubleClickHandler(this);
        doTouchHandlers(this, cv.getCanvasElement());
        cv.addDomHandler(this, ContextMenuEvent.getType());
        menuBar.addDomHandler(new ClickHandler() {
            public void onClick(ClickEvent event) {
                doMainMenuChecks();
            }
        }, ClickEvent.getType());
        Event.addNativePreviewHandler(this);
        cv.addMouseWheelHandler(this);

        Window.addWindowClosingHandler(new Window.ClosingHandler() {
            public void onWindowClosing(ClosingEvent event) {
                // there is a bug in electron that makes it impossible to close the app if this warning is given
                if (unsavedChanges && !isElectron())
                    event.setMessage(Locale.LS("Are you sure?  There are unsaved changes."));
            }
        });
        setupJSInterface();

        setSimRunning(running);
    }

    void setColors(String positiveColor, String negativeColor, String neutralColor, String selectColor, String currentColor) {
        Storage stor = Storage.getLocalStorageIfSupported();
        if (stor != null) {
            if (positiveColor == null)
                positiveColor = stor.getItem("positiveColor");
            if (negativeColor == null)
                negativeColor = stor.getItem("negativeColor");
            if (neutralColor == null)
                neutralColor = stor.getItem("neutralColor");
            if (selectColor == null)
                selectColor = stor.getItem("selectColor");
            if (currentColor == null)
                currentColor = stor.getItem("currentColor");
        }

        if (positiveColor != null)
            CircuitElm.positiveColor = new Color(URL.decodeQueryString(positiveColor));
        else if (getOptionFromStorage("alternativeColor", false))
            CircuitElm.positiveColor = Color.blue;

        if (negativeColor != null)
            CircuitElm.negativeColor = new Color(URL.decodeQueryString(negativeColor));
        if (neutralColor != null)
            CircuitElm.neutralColor = new Color(URL.decodeQueryString(neutralColor));

        if (selectColor != null)
            CircuitElm.selectColor = new Color(URL.decodeQueryString(selectColor));
        else
            CircuitElm.selectColor = Color.cyan;

        if (currentColor != null)
            CircuitElm.currentColor = new Color(URL.decodeQueryString(currentColor));
        else
            CircuitElm.currentColor = conventionCheckItem.getState() ? Color.yellow : Color.cyan;

        CircuitElm.setColorScale();
    }

    void setWheelSensitivity() {
        wheelSensitivity = 1;
        try {
            Storage stor = Storage.getLocalStorageIfSupported();
            wheelSensitivity = Double.parseDouble(stor.getItem("wheelSensitivity"));
        } catch (Exception e) {
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

    boolean getOptionFromStorage(String key, boolean val) {
        Storage stor = Storage.getLocalStorageIfSupported();
        if (stor == null)
            return val;
        String s = stor.getItem(key);
        if (s == null)
            return val;
        return s == "true";
    }

    void setOptionInStorage(String key, boolean val) {
        Storage stor = Storage.getLocalStorageIfSupported();
        if (stor == null)
            return;
        stor.setItem(key, val ? "true" : "false");
    }

    // save shortcuts to local storage
    void saveShortcuts() {
        Storage stor = Storage.getLocalStorageIfSupported();
        if (stor == null)
            return;
        String str = "1";
        int i;
        // format: version;code1=ClassName;code2=ClassName;etc
        for (i = 0; i != shortcuts.length; i++) {
            String sh = shortcuts[i];
            if (sh == null)
                continue;
            str += ";" + i + "=" + sh;
        }
        stor.setItem("shortcuts", str);
    }

    // load shortcuts from local storage
    void loadShortcuts() {
        Storage stor = Storage.getLocalStorageIfSupported();
        if (stor == null)
            return;
        String str = stor.getItem("shortcuts");
        if (str == null)
            return;
        String keys[] = str.split(";");

        // clear existing shortcuts
        int i;
        for (i = 0; i != shortcuts.length; i++)
            shortcuts[i] = null;

        // clear shortcuts from menu
        for (i = 0; i != mainMenuItems.size(); i++) {
            CheckboxMenuItem item = mainMenuItems.get(i);
            // stop when we get to drag menu items
            if (item.getShortcut().length() > 1)
                break;
            item.setShortcut("");
        }

        // go through keys (skipping version at start)
        for (i = 1; i < keys.length; i++) {
            String arr[] = keys[i].split("=");
            if (arr.length != 2)
                continue;
            int c = Integer.parseInt(arr[0]);
            String className = arr[1];
            shortcuts[c] = className;

            // find menu item and fix it
            int j;
            for (j = 0; j != mainMenuItems.size(); j++) {
                if (mainMenuItemNames.get(j) == className) {
                    CheckboxMenuItem item = mainMenuItems.get(j);
                    item.setShortcut(Character.toString((char) c));
                    break;
                }
            }
        }
    }

    // install touch handlers
    // don't feel like rewriting this in java.  Anyway, java doesn't let us create mouse
    // events and dispatch them.
    native static void doTouchHandlers(CirSim sim, CanvasElement cv) /*-{
	// Set up touch events for mobile, etc
	var lastTap;
	var tmout;
	var lastScale;

	cv.addEventListener("touchstart", function (e) {
        	mousePos = getTouchPos(cv, e);
  		var touch = e.touches[0];

  		var etype = "mousedown";
  		lastScale = 1;
  		clearTimeout(tmout);
  		e.preventDefault();

  		if (e.timeStamp-lastTap < 300) {
     		    etype = "dblclick";
  		} else {
  		    tmout = setTimeout(function() {
  		        sim.@com.lushprojects.circuitjs1.client.CirSim::longPress()();
  		    }, 500);
  		}
  		lastTap = e.timeStamp;

  		var touch1 = e.touches[0];
  		var touch2 = e.touches[e.touches.length-1];
  		lastScale = Math.hypot(touch1.clientX-touch2.clientX, touch1.clientY-touch2.clientY);
  		var mouseEvent = new MouseEvent(etype, {
    			clientX: .5*(touch1.clientX+touch2.clientX),
    			clientY: .5*(touch1.clientY+touch2.clientY)
  		});
  		cv.dispatchEvent(mouseEvent);
  		if (e.touches.length > 1)
  		    sim.@com.lushprojects.circuitjs1.client.CirSim::twoFingerTouch(II)(mouseEvent.clientX, mouseEvent.clientY - cv.getBoundingClientRect().y);
	}, false);
	cv.addEventListener("touchend", function (e) {
  		var mouseEvent = new MouseEvent("mouseup", {});
  		e.preventDefault();
  		clearTimeout(tmout);
  		cv.dispatchEvent(mouseEvent);
	}, false);
	cv.addEventListener("touchmove", function (e) {
  		e.preventDefault();
  		clearTimeout(tmout);
  		var touch1 = e.touches[0];
  		var touch2 = e.touches[e.touches.length-1];
	        if (e.touches.length > 1) {
  		    var newScale = Math.hypot(touch1.clientX-touch2.clientX, touch1.clientY-touch2.clientY);
	            sim.@com.lushprojects.circuitjs1.client.CirSim::zoomCircuit(D)(40*(Math.log(newScale)-Math.log(lastScale)));
	            lastScale = newScale;
	        }
  		var mouseEvent = new MouseEvent("mousemove", {
    			clientX: .5*(touch1.clientX+touch2.clientX),
    			clientY: .5*(touch1.clientY+touch2.clientY)
  		});
  		cv.dispatchEvent(mouseEvent);
	}, false);

	// Get the position of a touch relative to the canvas
	function getTouchPos(canvasDom, touchEvent) {
  		var rect = canvasDom.getBoundingClientRect();
  		return {
    			x: touchEvent.touches[0].clientX - rect.left,
    			y: touchEvent.touches[0].clientY - rect.top
  		};
	}

    }-*/;

    boolean shown = false;

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
        selectScopeMenuItems = new Vector<MenuItem>();
        for (int i = 0; i < scopeManager.scopeCount; i++) {
            String s, l;
            s = Locale.LS("Scope") + " " + Integer.toString(i + 1);
            l = scopeManager.scopes[i].getScopeLabelOrText();
            if (l != "")
                s += " (" + SafeHtmlUtils.htmlEscape(l) + ")";
            selectScopeMenuItems.add(new MenuItem(s, new MyCommand("elm", "addToScope" + Integer.toString(i))));
        }
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

    public void setSlidersPanelHeight() {
        int i;
        int cumheight = 0;
        for (i = 0; i < verticalPanel.getWidgetIndex(slidersPanel); i++) {
            if (verticalPanel.getWidget(i) != loadFileInput) {
                cumheight = cumheight + verticalPanel.getWidget(i).getOffsetHeight();
                if (verticalPanel.getWidget(i).getStyleName().contains("topSpace"))
                    cumheight += 12;
            }
        }
        int ih = RootLayoutPanel.get().getOffsetHeight() - MENU_BAR_HEIGHT - cumheight;
        if (toolbarCheckItem.getState())
            ih -= TOOLBAR_HEIGHT;
        if (ih < 0)
            ih = 0;
        slidersPanel.setHeight(ih + "px");
    }


    CheckboxMenuItem getClassCheckItem(String s, String t) {
        if (classToLabelMap == null)
            classToLabelMap = new HashMap<String, String>();
        classToLabelMap.put(t, s);

        // try {
        //   Class c = Class.forName(t);
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
                shortcut += (char) elm.getShortcut();
                if (shortcuts[elm.getShortcut()] != null && !shortcuts[elm.getShortcut()].equals(t))
                    console("already have shortcut for " + (char) elm.getShortcut() + " " + elm);
                shortcuts[elm.getShortcut()] = t;
            }
            elm.delete();
        }
//    	else
//    		GWT.log("Coudn't create class: "+t);
        //	} catch (Exception ee) {
        //	    ee.printStackTrace();
        //	}
        if (shortcut == "")
            mi = new CheckboxMenuItem(s);
        else
            mi = new CheckboxMenuItem(s, shortcut);
        mi.setScheduledCommand(new MyCommand("main", t));
        mainMenuItems.add(mi);
        mainMenuItemNames.add(t);
        return mi;
    }

    public void setSimRunning(boolean s) {
        if (s) {
            if (simulator.stopMessage != null)
                return;
            simulator.simRunning = true;
            runStopButton.setHTML(Locale.LSHTML("<strong>RUN</strong>&nbsp;/&nbsp;Stop"));
            runStopButton.setStylePrimaryName("topButton");
            timer.scheduleRepeating(FASTTIMER);
        } else {
            simulator.simRunning = false;
            runStopButton.setHTML(Locale.LSHTML("Run&nbsp;/&nbsp;<strong>STOP</strong>"));
            runStopButton.setStylePrimaryName("topButton-red");
            timer.cancel();
            repaint();
        }
    }

    public boolean simIsRunning() {
        return simulator.simRunning;
    }

    boolean needsRepaint;

    void repaint() {
        if (!needsRepaint) {
            needsRepaint = true;
            Scheduler.get().scheduleFixedDelay(new Scheduler.RepeatingCommand() {
                public boolean execute() {
                    renderer.updateCircuit();
                    needsRepaint = false;
                    return false;
                }
            }, FASTTIMER);
        }
    }

    Color getBackgroundColor() {
        if (printableCheckItem.getState())
            return Color.white;
        return Color.black;
    }

    native boolean isSidePanelCheckboxChecked() /*-{
		return $doc.getElementById("trigger").checked;
    }-*/;

//    public void toggleSwitch(int n) {
//	int i;
//	for (i = 0; i != elmList.size(); i++) {
//	    CircuitElm ce = getElm(i);
//	    if (ce instanceof SwitchElm) {
//		n--;
//		if (n == 0) {
//		    ((SwitchElm) ce).toggle();
//		    analyzeFlag = true;
//		    cv.repaint();
//		    return;
//		}
//	    }
//	}
//    }

    void needAnalyze() {
        renderer.analyzeFlag = true;
        repaint();
        enableDisableMenuItems();
    }

    public CircuitElm getElm(int n) {
        if (n >= simulator.elmList.size())
            return null;
        return simulator.elmList.elementAt(n);
    }

    public Adjustable findAdjustable(CircuitElm elm, int item) {
        int i;
        for (i = 0; i != adjustables.size(); i++) {
            Adjustable a = adjustables.get(i);
            if (a.elm == elm && a.editItem == item)
                return a;
        }
        return null;
    }

    public static native void console(String text)
    /*-{
	    console.log(text);
	}-*/;

    public static native void debugger() /*-{ debugger; }-*/;


    void stop(String s, CircuitElm ce) {
        simulator.stopMessage = Locale.LS(s);
        simulator.circuitMatrix = null;  // causes an exception
        simulator.stopElm = ce;

        setSimRunning(false);
        renderer.analyzeFlag = false;
    }


    double getIterCount() {
        // IES - remove interaction
        if (speedBar.getValue() == 0)
            return 0;

        return .1 * Math.exp((speedBar.getValue() - 61) / 24.);

    }

    public void resetAction() {
        int i;
        renderer.analyzeFlag = true;
        if (t == 0)
            setSimRunning(true);
        t = simulator.timeStepAccum = 0;
        simulator.timeStepCount = 0;
        for (i = 0; i != simulator.elmList.size(); i++)
            getElm(i).reset();
        for (i = 0; i != scopeManager.scopeCount; i++)
            scopeManager.scopes[i].resetGraph(true);
        repaint();
    }

    static native void changeWindowTitle(boolean isCircuitChanged)/*-{
		var newTitle = "CircuitJS1 Desktop Mod";
		var filename = @com.lushprojects.circuitjs1.client.CirSim::fileName;
		var changed = (isCircuitChanged) ? "*" : "";
		if (filename!=null) $doc.title = changed+filename+" - "+newTitle;
		else $doc.title = $wnd.nw.App.manifest.window.title;
	}-*/;

    static native void nodeSave(String path, String dump) /*-{
		var fs = $wnd.nw.require('fs');
		fs.writeFile(path, dump, function(err) {
			if(err) {
						return console.log(err);
					}
			console.log("The file was saved!");
			});
    }-*/;

    static native void nodeSaveAs(String dump, String fileName) /*-{
		var saveasInput = $doc.createElement("input");
		saveasInput.setAttribute('type', 'file');
		saveasInput.setAttribute('nwsaveas', fileName);
		saveasInput.style = "display:none";
		$doc.body.appendChild(saveasInput);
		saveasInput.click();
		saveasInput.addEventListener('cancel', function(){
		// oncancel don't work. The element will not be deleted but we can still work with this
		// https://github.com/nwjs/nw.js/issues/7658
			saveasInput.remove()
		});
		saveasInput.addEventListener('change', function(){
			@com.lushprojects.circuitjs1.client.CirSim::filePath = saveasInput.value;
			@com.lushprojects.circuitjs1.client.CirSim::fileName = saveasInput.files[0].name;
			@com.lushprojects.circuitjs1.client.CirSim::lastFileName = saveasInput.files[0].name;
			@com.lushprojects.circuitjs1.client.CirSim::nodeSave(Ljava/lang/String;Ljava/lang/String;)(saveasInput.value, dump);
			console.log(saveasInput.value);
			console.log(saveasInput.files[0].name);
			if (saveasInput.value!=null) $wnd.CircuitJS1.allowSave(true);
			saveasInput.remove();
			@com.lushprojects.circuitjs1.client.CirSim::changeWindowTitle(Z)(false);
		});
    }-*/;

    // JSInterface
    static void electronSaveAsCallback(String s) {
        s = s.substring(s.lastIndexOf('/') + 1);
        s = s.substring(s.lastIndexOf('\\') + 1);
        theSim.setCircuitTitle(s);
        theSim.allowSave(true);
        theSim.savedFlag = true;
        theSim.repaint();
    }

    static void electronSaveCallback() {
        theSim.savedFlag = true;
        theSim.repaint();
    }

    static native void electronSaveAs(String dump) /*-{
        $wnd.showSaveDialog().then(function (file) {
            if (file.canceled)
            	return;
            $wnd.saveFile(file, dump);
            @com.lushprojects.circuitjs1.client.CirSim::electronSaveAsCallback(Ljava/lang/String;)(file.filePath.toString());
        });
    }-*/;

    static native void electronSave(String dump) /*-{
        $wnd.saveFile(null, dump);
        @com.lushprojects.circuitjs1.client.CirSim::electronSaveCallback()();
    }-*/;

    static void electronOpenFileCallback(String text, String name) {
        LoadFile.doLoadCallback(text, name);
        theSim.allowSave(true);
    }

    static native void electronOpenFile() /*-{
        $wnd.openFile(function (text, name) {
            @com.lushprojects.circuitjs1.client.CirSim::electronOpenFileCallback(Ljava/lang/String;Ljava/lang/String;)(text, name);
        });
    }-*/;

    static native void toggleDevTools() /*-{
        $wnd.toggleDevTools();
    }-*/;

    static native boolean isElectron() /*-{
        return ($wnd.openFile != undefined);
    }-*/;

    static native String getElectronStartCircuitText() /*-{
    	return $wnd.startCircuitText;
    }-*/;

    void allowSave(boolean b) {
        if (saveFileItem != null)
            saveFileItem.setEnabled(b);
    }

    public void menuPerformed(String menu, String item) {
        if ((menu == "edit" || menu == "main" || menu == "scopes") && noEditCheckItem.getState()) {
            Window.alert(Locale.LS("Editing disabled.  Re-enable from the Options menu."));
            return;
        }
        if (item == "help")
            dialogManager.showHelpDialog();
        if (item == "license")
            dialogManager.showLicenseDialog();
        if (item == "about")
            dialogManager.showAboutBox();
        if (item == "modsetup")
            dialogManager.showModDialog();
        if (item == "importfromlocalfile") {
            pushUndo();
            loadFileInput.click();
        }
        if (item == "newwindow") {
            //Window.open(Document.get().getURL(), "_blank", "");
            //Maybe this can help with lags:
            executeJS("nw.Window.open('circuitjs.html', {new_instance: true, mixed_context: false});");
        }
        if (item == "save") {
            if (filePath != null) nodeSave(filePath, dumpCircuit());
            else nodeSaveAs(dumpCircuit(), getLastFileName());
            unsavedChanges = false;
            changeWindowTitle(unsavedChanges);
        }

        if (item == "saveas") {
            nodeSaveAs(dumpCircuit(), getLastFileName());
            unsavedChanges = false;
            changeWindowTitle(unsavedChanges);
        }

        if (item == "importfromtext") {
            dialogManager.showImportFromTextDialog();
        }
    	/*if (item=="importfromdropbox") {
    		dialogShowing = new ImportFromDropboxDialog(this);
    	}*/
        if (item == "exportasurl") {
            doExportAsUrl();
            unsavedChanges = false;
        }
    	/*if (item=="exportaslocalfile") {
    		doExportAsLocalFile();
    		unsavedChanges = false;
    	}*/
        if (item == "exportastext") {
            doExportAsText();
            unsavedChanges = false;
        }
        if (item == "exportasimage")
            doExportAsImage();
        if (item == "copypng") {
            doImageToClipboard();
            if (contextPanel != null)
                contextPanel.hide();
        }
        if (item == "exportassvg")
            doExportAsSVG();
        if (item == "createsubcircuit")
            doCreateSubcircuit();
        if (item == "dcanalysis")
            doDCAnalysis();
        if (item == "print")
            doPrint();
        if (item == "recover")
            doRecover();

        if ((menu == "elm" || menu == "scopepop") && contextPanel != null)
            contextPanel.hide();
        if (menu == "options" && item == "shortcuts") {
            dialogManager.showShortcutsDialog();
        }
        if (menu == "options" && item == "subcircuits") {
            dialogManager.showSubcircuitDialog();
        }
        if (item == "search") {
            dialogManager.showSearchDialog();
        }
        if (menu == "options" && item == "other")
            doEdit(new EditOptions(this));
        if (item == "devtools")
            toggleDevTools();
        if (item == "undo")
            doUndo();
        if (item == "redo")
            doRedo();

        // if the mouse is hovering over an element, and a shortcut key is pressed, operate on that element (treat it like a context menu item selection)
        if (menu == "key" && mouseElm != null) {
            menuElm = mouseElm;
            menu = "elm";
        }
        if (menu != "elm")
            menuElm = null;

        if (item == "cut") {
            doCut();
        }
        if (item == "copy") {
            doCopy();
        }
        if (item == "paste")
            doPaste(null);
        if (item == "duplicate") {
            doDuplicate();
        }
        if (item == "flip")
            doFlip();
        if (item == "split")
            doSplit(menuElm);
        if (item == "selectAll")
            doSelectAll();
        //	if (e.getSource() == exitItem) {
        //	    destroyFrame();
        //	    return;
        //	}

        if (item == "centrecircuit") {
            pushUndo();
            renderer.centreCircuit();
        }
        if (item == "flipx") {
            pushUndo();
            flipX();
        }
        if (item == "flipy") {
            pushUndo();
            flipY();
        }
        if (item == "flipxy") {
            pushUndo();
            flipXY();
        }
        if (item == "stackAll")
            scopeManager.stackAll();
        if (item == "unstackAll")
            scopeManager.unstackAll();
        if (item == "combineAll")
            scopeManager.combineAll();
        if (item == "separateAll")
            scopeManager.separateAll();
        if (item == "zoomin")
            renderer.zoomCircuit(20, true);
        if (item == "zoomout")
            renderer.zoomCircuit(-20, true);
        if (item == "zoom100")
            renderer.setCircuitScale(1, true);
        if (menu == "elm" && item == "edit")
            doEdit(menuElm);
        if (item == "delete") {
            if (menu != "elm")
                menuElm = null;
            pushUndo();
            doDelete(true);
        }
        if (item == "sliders")
            doSliders(menuElm);

        if (item == "viewInScope" && menuElm != null) {
            scopeManager.addScope(menuElm);
        }

        if (item == "viewInFloatScope" && menuElm != null) {
            ScopeElm newScope = new ScopeElm(snapGrid(menuElm.x + 50), snapGrid(menuElm.y + 50));
            simulator.elmList.addElement(newScope);
            newScope.setScopeElm(menuElm);

            // need to rebuild scopeElmArr
            needAnalyze();
        }

        if (item.startsWith("addToScope") && menuElm != null) {
            int n = Integer.parseInt(item.substring(10));
            scopeManager.addToScope(n, menuElm);
            scopeManager.scopeMenuSelected = -1;
        }

        if (menu == "scopepop") {
            pushUndo();
            Scope s;
            if (scopeManager.menuScope != -1)
                s = scopeManager.scopes[scopeManager.menuScope];
            else
                s = ((ScopeElm) mouseElm).elmScope;

            if (item == "dock") {
                scopeManager.dockScope(mouseElm);
                doDelete(false);
            }
            if (item == "undock") {
                CircuitElm elm = s.getElm();
                ScopeElm newScope = new ScopeElm(snapGrid(elm.x + 50), snapGrid(elm.y + 50));
                scopeManager.undockScope(newScope);

                needAnalyze();      // need to rebuild scopeElmArr
            }
            if (item == "remove")
                s.setElm(null);  // setupScopes() will clean this up
            if (item == "removeplot")
                s.removePlot(menuPlot);
            if (item == "speed2")
                s.speedUp();
            if (item == "speed1/2")
                s.slowDown();
//    		if (item=="scale")
//    			scopes[menuScope].adjustScale(.5);
            if (item == "maxscale")
                s.maxScale();
            if (item == "stack")
                scopeManager.stackScope(scopeManager.menuScope);
            if (item == "unstack")
                scopeManager.unstackScope(scopeManager.menuScope);
            if (item == "combine")
                scopeManager.combineScope(scopeManager.menuScope);
            if (item == "selecty")
                s.selectY();
            if (item == "reset")
                s.resetGraph(true);
            if (item == "properties")
                s.properties();
            deleteUnusedScopeElms();
        }
        if (menu == "circuits" && item.indexOf("setup ") == 0) {
            pushUndo();
            int sp = item.indexOf(' ', 6);
            readSetupFile(item.substring(6, sp), item.substring(sp + 1));
        }
        if (item == "newblankcircuit") {
            pushUndo();
            readSetupFile("blank.txt", "Blank Circuit");
        }

        //	if (ac.indexOf("setup ") == 0) {
        //	    pushUndo();
        //	    readSetupFile(ac.substring(6),
        //			  ((MenuItem) e.getSource()).getLabel());
        //	}

        // IES: Moved from itemStateChanged()
        if (menu == "main") {
            if (contextPanel != null)
                contextPanel.hide();
            //	MenuItem mmi = (MenuItem) mi;
            //		int prevMouseMode = mouseMode;
            setMouseMode(MODE_ADD_ELM);
            String s = item;
            if (s.length() > 0)
                mouseModeStr = s;
            if (s.compareTo("DragAll") == 0)
                setMouseMode(MODE_DRAG_ALL);
            else if (s.compareTo("DragRow") == 0)
                setMouseMode(MODE_DRAG_ROW);
            else if (s.compareTo("DragColumn") == 0)
                setMouseMode(MODE_DRAG_COLUMN);
            else if (s.compareTo("DragSelected") == 0)
                setMouseMode(MODE_DRAG_SELECTED);
            else if (s.compareTo("DragPost") == 0)
                setMouseMode(MODE_DRAG_POST);
            else if (s.compareTo("Select") == 0)
                setMouseMode(MODE_SELECT);

            updateToolbar();

            //		else if (s.length() > 0) {
            //			try {
            //				addingClass = Class.forName(s);
            //			} catch (Exception ee) {
            //				ee.printStackTrace();
            //			}
            //		}
            //		else
            //			setMouseMode(prevMouseMode);
            tempMouseMode = mouseMode;
        }
        if (item == "fullscreen") {
            if (!Graphics.isFullScreen) {
                Graphics.viewFullScreen();
                setSlidersPanelHeight();
            } else {
                Graphics.exitFullScreen();
                renderer.centreCircuit();
                setSlidersPanelHeight();
            }
        }

        repaint();
    }

    void setUnsavedChanges() {
        unsavedChanges = true;
        changeWindowTitle(unsavedChanges);
    }

    void doEdit(Editable eable) {
        clearSelection();
        pushUndo();
        dialogManager.showEditDialog(eable);
    }

    void doSliders(CircuitElm ce) {
        clearSelection();
        pushUndo();
        dialogManager.showSliderDialog(ce);
    }


    void doExportAsUrl() {
        String dump = dumpCircuit();
        dialogManager.showExportAsUrlDialog(dump);
    }

    void doExportAsText() {
        String dump = dumpCircuit();
        dialogManager.showExportAsTextDialog(dump);
    }

    void doExportAsImage() {
        dialogManager.showExportAsImageDialog(CAC_IMAGE);
    }

    private static native void clipboardWriteImage(CanvasElement cv) /*-{
	cv.toBlob(function(blob) {
	    var promise = parent.navigator.clipboard.write([new ClipboardItem({ "image/png": blob })]);
	    promise.then(function(x) { console.log(x); });
	});
    }-*/;

    void doImageToClipboard() {
        Canvas cv = renderer.getCircuitAsCanvas(CAC_IMAGE);
        clipboardWriteImage(cv.getCanvasElement());
    }

    void doCreateSubcircuit() {
        dialogManager.showEditCompositeModelDialog(null);
    }

    /*
    void doExportAsLocalFile() {
    	String dump = dumpCircuit();
    	dialogShowing = new ExportAsLocalFileDialog(dump);
    	dialogShowing.show();
    }
*/
    public void importCircuitFromText(String circuitText, boolean subcircuitsOnly) {
        int flags = subcircuitsOnly ? (CirSim.RC_SUBCIRCUITS | CirSim.RC_RETAIN) : 0;
        if (circuitText != null) {
            readCircuit(circuitText, flags);
            allowSave(false);
            filePath = null;
            fileName = null;
            changeWindowTitle(false);
        }
    }

    String dumpOptions() {
        int f = (dotsCheckItem.getState()) ? 1 : 0;
        f |= (smallGridCheckItem.getState()) ? 2 : 0;
        f |= (voltsCheckItem.getState()) ? 0 : 4;
        f |= (powerCheckItem.getState()) ? 8 : 0;
        f |= (showValuesCheckItem.getState()) ? 0 : 16;
        // 32 = linear scale in afilter
        f |= simulator.adjustTimeStep ? 64 : 0;
        String dump = "$ " + f + " " +
                simulator.maxTimeStep + " " + getIterCount() + " " +
                currentBar.getValue() + " " + CircuitElm.voltageRange + " " +
                powerBar.getValue() + " " + simulator.minTimeStep + "\n";
        return dump;
    }

    String dumpCircuit() {
        int i;
        CustomLogicModel.clearDumpedFlags();
        CustomCompositeModel.clearDumpedFlags();
        DiodeModel.clearDumpedFlags();
        TransistorModel.clearDumpedFlags();

        String dump = dumpOptions();

        for (i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm ce = simulator.elmList.get(i);
            String m = ce.dumpModel();
            if (m != null && !m.isEmpty())
                dump += m + "\n";
            dump += ce.dump() + "\n";
        }
        for (i = 0; i != scopeManager.scopeCount; i++) {
            String d = scopeManager.scopes[i].dump();
            if (d != null)
                dump += d + "\n";
        }
        for (i = 0; i != adjustables.size(); i++) {
            Adjustable adj = adjustables.get(i);
            dump += "38 " + adj.dump() + "\n";
        }
        if (hintType != -1)
            dump += "h " + hintType + " " + hintItem1 + " " +
                    hintItem2 + "\n";
        return dump;
    }

    void getSetupList(final boolean openDefault) {

        String url;
        url = GWT.getModuleBaseURL() + "setuplist.txt"; // +"?v="+random.nextInt();
        RequestBuilder requestBuilder = new RequestBuilder(RequestBuilder.GET, url);
        try {
            requestBuilder.sendRequest(null, new RequestCallback() {
                public void onError(Request request, Throwable exception) {
                    Window.alert(Locale.LS("Can't load circuit list!"));
                    GWT.log("File Error Response", exception);
                }

                public void onResponseReceived(Request request, Response response) {
                    // processing goes here
                    if (response.getStatusCode() == Response.SC_OK) {
                        String text = response.getText();
                        processSetupList(text.getBytes(), openDefault);
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

    void processSetupList(byte b[], final boolean openDefault) {
        int len = b.length;
        MenuBar currentMenuBar;
        MenuBar stack[] = new MenuBar[6];
        int stackptr = 0;
        currentMenuBar = new MenuBar(true);
        currentMenuBar.setAutoOpen(true);
        menuBar.addItem(Locale.LS("Circuits"), currentMenuBar);

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
        h.addItem(aboutCircuitsItem = iconMenuItem("link", "About Circuits",
                new Command() {
                    public void execute() {
                        executeJS("nw.Shell.openExternal('https://www.falstad.com/circuit/e-index.html')");
                    }
                }));
        h.addItem(aboutCircuitsPLItem = iconMenuItem("link", "About Circuits (Polish ver.)",
                new Command() {
                    public void execute() {
                        executeJS("nw.Shell.openExternal('https://www.falstad.com/circuit/polish/e-index.html');");
                    }
                }));

        menuBar.addItem(Locale.LS("Help"), h);

        stack[stackptr++] = currentMenuBar;
        int p;
        for (p = 0; p < len; ) {
            int l;
            for (l = 0; l != len - p; l++)
                if (b[l + p] == '\n' || b[l + p] == '\r') {
                    l++;
                    break;
                }
            String line = new String(b, p, l - 1);
            if (line.isEmpty() || line.charAt(0) == '#')
                ;
            else if (line.charAt(0) == '+') {
                //	MenuBar n = new Menu(line.substring(1));
                MenuBar n = new MenuBar(true);
                n.setAutoOpen(true);
                currentMenuBar.addItem(Locale.LS(line.substring(1)), n);
                currentMenuBar = stack[stackptr++] = n;
            } else if (line.charAt(0) == '-') {
                currentMenuBar = stack[--stackptr - 1];
            } else {
                int i = line.indexOf(' ');
                if (i > 0) {
                    String title = Locale.LS(line.substring(i + 1));
                    boolean first = false;
                    if (line.charAt(0) == '>')
                        first = true;
                    String file = line.substring(first ? 1 : 0, i);
                    currentMenuBar.addItem(new MenuItem(title,
                            new MyCommand("circuits", "setup " + file + " " + title)));
                    if (file.equals(startCircuit) && startLabel == null) {
                        startLabel = title;
                        titleLabel.setText(title);
                        setSlidersPanelHeight();
                    }
                    if (first && startCircuit == null) {
                        startCircuit = file;
                        startLabel = title;
                        if (openDefault && simulator.stopMessage == null)
                            readSetupFile(startCircuit, startLabel);
                    }
                }
            }
            p += l;
        }
    }

    void readCircuit(String text, int flags) {
        readCircuit(text.getBytes(), flags);
        if ((flags & RC_KEEP_TITLE) == 0)
            titleLabel.setText(null);
        setSlidersPanelHeight();
    }

    void readCircuit(String text) {
        readCircuit(text.getBytes(), 0);
        titleLabel.setText(null);
        setSlidersPanelHeight();
    }

    void setCircuitTitle(String s) {
        if (s != null)
            titleLabel.setText(s);
        setSlidersPanelHeight();
    }

    void readSetupFile(String str, String title) {
        System.out.println(str);
        // don't avoid caching here, it's unnecessary and makes offline PWA's not work
        String url = GWT.getModuleBaseURL() + "circuits/" + str; // +"?v="+random.nextInt();
        loadFileFromURL(url);
        if (title != null)
            titleLabel.setText(title);
        setSlidersPanelHeight();
        unsavedChanges = false;
        filePath = null;
        fileName = null;
        changeWindowTitle(unsavedChanges);
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
                    if (response.getStatusCode() == Response.SC_OK) {
                        String text = response.getText();
                        readCircuit(text, RC_KEEP_TITLE);
                        allowSave(false);
                        unsavedChanges = false;
                        filePath = null;
                        fileName = null;
                        changeWindowTitle(unsavedChanges);
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

    static final int RC_RETAIN = 1;
    static final int RC_NO_CENTER = 2;
    static final int RC_SUBCIRCUITS = 4;
    static final int RC_KEEP_TITLE = 8;

    void readCircuit(byte b[], int flags) {
        int i;
        int len = b.length;
        if ((flags & RC_RETAIN) == 0) {
            clearMouseElm();
            for (i = 0; i != simulator.elmList.size(); i++) {
                CircuitElm ce = getElm(i);
                ce.delete();
            }
            t = simulator.timeStepAccum = 0;
            simulator.elmList.removeAllElements();
            hintType = -1;
            simulator.maxTimeStep = 5e-6;
            simulator.minTimeStep = 50e-12;
            dotsCheckItem.setState(false);
            smallGridCheckItem.setState(false);
            powerCheckItem.setState(false);
            voltsCheckItem.setState(true);
            showValuesCheckItem.setState(true);
            setGrid();
            speedBar.setValue(117); // 57
            currentBar.setValue(50);
            powerBar.setValue(50);
            CircuitElm.voltageRange = 5;
            scopeManager.scopeCount = 0;
            simulator.lastIterTime = 0;
        }
        boolean subs = (flags & RC_SUBCIRCUITS) != 0;
        //cv.repaint();
        int p;
        for (p = 0; p < len; ) {
            int l;
            int linelen = len - p; // IES - changed to allow the last line to not end with a delim.
            for (l = 0; l != len - p; l++)
                if (b[l + p] == '\n' || b[l + p] == '\r') {
                    linelen = l++;
                    if (l + p < b.length && b[l + p] == '\n')
                        l++;
                    break;
                }
            String line = new String(b, p, linelen);
            StringTokenizer st = new StringTokenizer(line, " +\t\n\r\f");
            while (st.hasMoreTokens()) {
                String type = st.nextToken();
                int tint = type.charAt(0);
                try {
                    if (subs && tint != '.')
                        continue;
                    if (tint == 'o') {
                        Scope sc = new Scope(this);
                        sc.position = scopeManager.scopeCount;
                        sc.undump(st);
                        scopeManager.scopes[scopeManager.scopeCount++] = sc;
                        break;
                    }
                    if (tint == 'h') {
                        readHint(st);
                        break;
                    }
                    if (tint == '$') {
                        readOptions(st, flags);
                        break;
                    }
                    if (tint == '!') {
                        CustomLogicModel.undumpModel(st);
                        break;
                    }
                    if (tint == '%' || tint == '?' || tint == 'B') {
                        // ignore afilter-specific stuff
                        break;
                    }
                    // do not add new symbols here without testing export as link

                    // if first character is a digit then parse the type as a number
                    if (tint >= '0' && tint <= '9')
                        tint = new Integer(type).intValue();

                    if (tint == 34) {
                        DiodeModel.undumpModel(st);
                        break;
                    }
                    if (tint == 32) {
                        TransistorModel.undumpModel(st);
                        break;
                    }
                    if (tint == 38) {
                        Adjustable adj = new Adjustable(st, this);
                        if (adj.elm != null)
                            adjustables.add(adj);
                        break;
                    }
                    if (tint == '.') {
                        CustomCompositeModel.undumpModel(st);
                        break;
                    }
                    int x1 = new Integer(st.nextToken()).intValue();
                    int y1 = new Integer(st.nextToken()).intValue();
                    int x2 = new Integer(st.nextToken()).intValue();
                    int y2 = new Integer(st.nextToken()).intValue();
                    int f = new Integer(st.nextToken()).intValue();

                    CircuitElm newce = CircuitElmCreator.createCe(tint, x1, y1, x2, y2, f, st);
                    if (newce == null) {
                        System.out.println("unrecognized dump type: " + type);
                        break;
                    }
		    /*
		     * debug code to check if allocNodes() is called in constructor.  It gets called in
		     * setPoints() but that doesn't get called for subcircuits.
		    double vv[] = newce.volts;
		    int vc = newce.getPostCount() + newce.getInternalNodeCount();
		    if (vv.length != vc)
			console("allocnodes not called! " + tint);
		     */
                    newce.setPoints();
                    simulator.elmList.addElement(newce);
                } catch (Exception ee) {
                    ee.printStackTrace();
                    console("exception while undumping " + ee);
                    break;
                }
                break;
            }
            p += l;

        }
        setPowerBarEnable();
        enableItems();
        if ((flags & RC_RETAIN) == 0) {
            // create sliders as needed
            for (i = 0; i < adjustables.size(); i++) {
                if (!adjustables.get(i).createSlider(this))
                    adjustables.remove(i--);
            }
        }
//	if (!retain)
        //    handleResize(); // for scopes
        needAnalyze();
        if ((flags & RC_NO_CENTER) == 0)
            renderer.centreCircuit();
        if ((flags & RC_SUBCIRCUITS) != 0)
            simulator.updateModels();

        AudioInputElm.clearCache();  // to save memory
        DataInputElm.clearCache();  // to save memory
    }

    // delete sliders for an element
    void deleteSliders(CircuitElm elm) {
        int i;
        if (adjustables == null)
            return;
        for (i = adjustables.size() - 1; i >= 0; i--) {
            Adjustable adj = adjustables.get(i);
            if (adj.elm == elm) {
                adj.deleteSlider(this);
                adjustables.remove(i);
            }
        }
    }

    void readHint(StringTokenizer st) {
        hintType = new Integer(st.nextToken()).intValue();
        hintItem1 = new Integer(st.nextToken()).intValue();
        hintItem2 = new Integer(st.nextToken()).intValue();
    }

    void readOptions(StringTokenizer st, int importFlags) {
        int flags = new Integer(st.nextToken()).intValue();

        if ((importFlags & RC_RETAIN) != 0) {
            // need to set small grid if pasted circuit uses it
            if ((flags & 2) != 0)
                smallGridCheckItem.setState(true);
            return;
        }

        dotsCheckItem.setState((flags & 1) != 0);
        smallGridCheckItem.setState((flags & 2) != 0);
        voltsCheckItem.setState((flags & 4) == 0);
        powerCheckItem.setState((flags & 8) == 8);
        showValuesCheckItem.setState((flags & 16) == 0);
        simulator.adjustTimeStep = (flags & 64) != 0;
        simulator.maxTimeStep = simulator.timeStep = new Double(st.nextToken()).doubleValue();
        double sp = new Double(st.nextToken()).doubleValue();
        int sp2 = (int) (Math.log(10 * sp) * 24 + 61.5);
        //int sp2 = (int) (Math.log(sp)*24+1.5);
        speedBar.setValue(sp2);
        currentBar.setValue(new Integer(st.nextToken()).intValue());
        CircuitElm.voltageRange = new Double(st.nextToken()).doubleValue();

        try {
            powerBar.setValue(new Integer(st.nextToken()).intValue());
            simulator.minTimeStep = Double.parseDouble(st.nextToken());
        } catch (Exception e) {
        }
        setGrid();
    }

    int snapGrid(int x) {
        return (x + gridRound) & gridMask;
    }

    boolean doSwitch(int x, int y) {
        if (mouseElm == null || !(mouseElm instanceof SwitchElm))
            return false;
        SwitchElm se = (SwitchElm) mouseElm;
        if (!se.getSwitchRect().contains(x, y))
            return false;
        se.toggle();
        if (se.momentary)
            heldSwitchElm = se;
        if (!(se instanceof LogicInputElm))
            needAnalyze();
        unsavedChanges = true;
        changeWindowTitle(unsavedChanges);
        return true;
    }

    int locateElm(CircuitElm elm) {
        int i;
        for (i = 0; i != simulator.elmList.size(); i++)
            if (elm == simulator.elmList.elementAt(i))
                return i;
        return -1;
    }

    public void mouseDragged(MouseMoveEvent e) {
        // ignore right mouse button with no modifiers (needed on PC)
        if (e.getNativeButton() == NativeEvent.BUTTON_RIGHT) {
            if (!(e.isMetaKeyDown() ||
                    e.isShiftKeyDown() ||
                    e.isControlKeyDown() ||
                    e.isAltKeyDown()))
                return;
        }

        if (tempMouseMode == MODE_DRAG_SPLITTER) {
            dragSplitter(e.getX(), e.getY());
            return;
        }
        int gx = renderer.inverseTransformX(e.getX());
        int gy = renderer.inverseTransformY(e.getY());
        if (!renderer.circuitArea.contains(e.getX(), e.getY()))
            return;
        boolean changed = false;
        if (dragElm != null)
            dragElm.drag(gx, gy);
        boolean success = true;
        switch (tempMouseMode) {
            case MODE_DRAG_ALL:
                dragAll(e.getX(), e.getY());
                break;
            case MODE_DRAG_ROW:
                dragRow(snapGrid(gx), snapGrid(gy));
                changed = true;
                break;
            case MODE_DRAG_COLUMN:
                dragColumn(snapGrid(gx), snapGrid(gy));
                changed = true;
                break;
            case MODE_DRAG_POST:
                if (mouseElm != null) {
                    dragPost(snapGrid(gx), snapGrid(gy), e.isShiftKeyDown());
                    changed = true;
                }
                break;
            case MODE_SELECT:
                if (mouseElm == null)
                    selectArea(gx, gy, e.isShiftKeyDown());
                else if (!noEditCheckItem.getState()) {
                    // wait short delay before dragging.  This is to fix problem where switches were accidentally getting
                    // dragged when tapped on mobile devices
                    if (System.currentTimeMillis() - mouseDownTime < 150)
                        return;

                    tempMouseMode = MODE_DRAG_SELECTED;
                    changed = success = dragSelected(gx, gy);
                }
                break;
            case MODE_DRAG_SELECTED:
                changed = success = dragSelected(gx, gy);
                break;

        }
        dragging = true;
        if (success) {
            dragScreenX = e.getX();
            dragScreenY = e.getY();
            //	    console("setting dragGridx in mousedragged");
            dragGridX = renderer.inverseTransformX(dragScreenX);
            dragGridY = renderer.inverseTransformY(dragScreenY);
            if (!(tempMouseMode == MODE_DRAG_SELECTED && onlyGraphicsElmsSelected())) {
                dragGridX = snapGrid(dragGridX);
                dragGridY = snapGrid(dragGridY);
            }
        }
        if (changed) {
            writeRecoveryToStorage();
            unsavedChanges = true;
            changeWindowTitle(unsavedChanges);
        }

        repaint();
    }

    void dragSplitter(int x, int y) {
        double h = (double) renderer.canvasHeight;
        if (h < 1)
            h = 1;
        double scopeHeightFraction = 1.0 - (((double) y) / h);
        if (scopeHeightFraction < 0.1)
            scopeHeightFraction = 0.1;
        if (scopeHeightFraction > 0.9)
            scopeHeightFraction = 0.9;
        renderer.scopeHeightFraction = scopeHeightFraction;
        renderer.setCircuitArea();
        repaint();
    }

    void dragAll(int x, int y) {
        int dx = x - dragScreenX;
        int dy = y - dragScreenY;
        if (dx == 0 && dy == 0)
            return;
        renderer.transform[4] += dx;
        renderer.transform[5] += dy;
        dragScreenX = x;
        dragScreenY = y;
    }

    void dragRow(int x, int y) {
        int dy = y - dragGridY;
        if (dy == 0)
            return;
        int i;
        for (i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm ce = simulator.elmList.get(i);
            if (ce.y == dragGridY)
                ce.movePoint(0, 0, dy);
            if (ce.y2 == dragGridY)
                ce.movePoint(1, 0, dy);
        }
        removeZeroLengthElements();
    }

    void dragColumn(int x, int y) {
        int dx = x - dragGridX;
        if (dx == 0)
            return;
        int i;
        for (i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm ce = simulator.elmList.get(i);
            if (ce.x == dragGridX)
                ce.movePoint(0, dx, 0);
            if (ce.x2 == dragGridX)
                ce.movePoint(1, dx, 0);
        }
        removeZeroLengthElements();
    }

    boolean onlyGraphicsElmsSelected() {
        if (mouseElm != null && !(mouseElm instanceof GraphicElm))
            return false;
        int i;
        for (i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm ce = getElm(i);
            if (ce.isSelected() && !(ce instanceof GraphicElm))
                return false;
        }
        return true;
    }

    boolean dragSelected(int x, int y) {
        boolean me = false;
        int i;
        if (mouseElm != null && !mouseElm.isSelected())
            mouseElm.setSelected(me = true);

        if (!onlyGraphicsElmsSelected()) {
            //	    console("Snapping x and y");
            x = snapGrid(x);
            y = snapGrid(y);
        }

        int dx = x - dragGridX;
        //  	console("dx="+dx+"dragGridx="+dragGridX);
        int dy = y - dragGridY;
        if (dx == 0 && dy == 0) {
            // don't leave mouseElm selected if we selected it above
            if (me)
                mouseElm.setSelected(false);
            return false;
        }
        boolean allowed = true;

        // check if moves are allowed
        for (i = 0; allowed && i != simulator.elmList.size(); i++) {
            CircuitElm ce = getElm(i);
            if (ce.isSelected() && !ce.allowMove(dx, dy))
                allowed = false;
        }

        if (allowed) {
            for (i = 0; i != simulator.elmList.size(); i++) {
                CircuitElm ce = getElm(i);
                if (ce.isSelected())
                    ce.move(dx, dy);
            }
            needAnalyze();
        }

        // don't leave mouseElm selected if we selected it above
        if (me)
            mouseElm.setSelected(false);

        return allowed;
    }

    void dragPost(int x, int y, boolean all) {
        if (draggingPost == -1) {
            draggingPost =
                    (Graphics.distanceSq(mouseElm.x, mouseElm.y, x, y) >
                            Graphics.distanceSq(mouseElm.x2, mouseElm.y2, x, y)) ? 1 : 0;
        }
        int dx = x - dragGridX;
        int dy = y - dragGridY;
        if (dx == 0 && dy == 0)
            return;

        if (all) {
            // go through all elms
            int i;
            for (i = 0; i != simulator.elmList.size(); i++) {
                CircuitElm e = simulator.elmList.get(i);

                // which post do we move?
                int p = 0;
                if (e.x == dragGridX && e.y == dragGridY)
                    p = 0;
                else if (e.x2 == dragGridX && e.y2 == dragGridY)
                    p = 1;
                else
                    continue;
                e.movePoint(p, dx, dy);
            }
        } else
            mouseElm.movePoint(draggingPost, dx, dy);
        needAnalyze();
    }

    void doFlip() {
        menuElm.flipPosts();
        needAnalyze();
    }

    void doSplit(CircuitElm ce) {
        int x = snapGrid(renderer.inverseTransformX(menuX));
        int y = snapGrid(renderer.inverseTransformY(menuY));
        if (ce == null || !(ce instanceof WireElm))
            return;
        if (ce.x == ce.x2)
            x = ce.x;
        else
            y = ce.y;

        // don't create zero-length wire
        if (x == ce.x && y == ce.y || x == ce.x2 && y == ce.y2)
            return;

        WireElm newWire = new WireElm(x, y);
        newWire.drag(ce.x2, ce.y2);
        ce.drag(x, y);
        simulator.elmList.addElement(newWire);
        needAnalyze();
    }

    void selectArea(int x, int y, boolean add) {
        int x1 = Math.min(x, initDragGridX);
        int x2 = Math.max(x, initDragGridX);
        int y1 = Math.min(y, initDragGridY);
        int y2 = Math.max(y, initDragGridY);
        selectedArea = new Rectangle(x1, y1, x2 - x1, y2 - y1);
        int i;
        for (i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm ce = getElm(i);
            ce.selectRect(selectedArea, add);
        }
        enableDisableMenuItems();
    }

    void enableDisableMenuItems() {
        boolean canFlipX = true;
        boolean canFlipY = true;
        boolean canFlipXY = true;
        int selCount = countSelected();
        for (CircuitElm elm : simulator.elmList)
            if (elm.isSelected() || selCount == 0) {
                if (!elm.canFlipX())
                    canFlipX = false;
                if (!elm.canFlipY())
                    canFlipY = false;
                if (!elm.canFlipXY())
                    canFlipXY = false;
            }
        cutItem.setEnabled(selCount > 0);
        copyItem.setEnabled(selCount > 0);
        flipXItem.setEnabled(canFlipX);
        flipYItem.setEnabled(canFlipY);
        flipXYItem.setEnabled(canFlipXY);
    }

    void setMouseElm(CircuitElm ce) {
        if (ce != mouseElm) {
            if (mouseElm != null)
                mouseElm.setMouseElm(false);
            if (ce != null)
                ce.setMouseElm(true);
            mouseElm = ce;
            int i;
            for (i = 0; i < adjustables.size(); i++)
                adjustables.get(i).setMouseElm(ce);
        }
    }

    void removeZeroLengthElements() {
        int i;
        boolean changed = false;
        for (i = simulator.elmList.size() - 1; i >= 0; i--) {
            CircuitElm ce = getElm(i);
            if (ce.x == ce.x2 && ce.y == ce.y2) {
                simulator.elmList.removeElementAt(i);
                ce.delete();
                changed = true;
            }
        }
        needAnalyze();
    }

    boolean mouseIsOverSplitter(int x, int y) {
        boolean isOverSplitter;
        if (scopeManager.scopeCount == 0)
            return false;
        isOverSplitter = ((x >= 0) && (x < renderer.circuitArea.width) &&
                (y >= renderer.circuitArea.height - 5) && (y < renderer.circuitArea.height));
        if (isOverSplitter != mouseWasOverSplitter) {
            if (isOverSplitter)
                setCursorStyle("cursorSplitter");
            else
                setMouseMode(mouseMode);
        }
        mouseWasOverSplitter = isOverSplitter;
        return isOverSplitter;
    }

    public void onMouseMove(MouseMoveEvent e) {
        e.preventDefault();
        mouseCursorX = e.getX();
        mouseCursorY = e.getY();
        if (mouseDragging) {
            mouseDragged(e);
            return;
        }
        mouseSelect(e);
        scopeManager.scopeMenuSelected = -1;
    }


    // need to break this out into a separate routine to handle selection,
    // since we don't get mouse move events on mobile
    public void mouseSelect(MouseEvent<?> e) {
        //	The following is in the original, but seems not to work/be needed for GWT
        //    	if (e.getNativeButton()==NativeEvent.BUTTON_LEFT)
        //	    return;
        CircuitElm newMouseElm = null;
        mouseCursorX = e.getX();
        mouseCursorY = e.getY();
        int sx = e.getX();
        int sy = e.getY();
        int gx = renderer.inverseTransformX(sx);
        int gy = renderer.inverseTransformY(sy);
        // 	console("Settingd draggridx in mouseEvent");
        dragGridX = snapGrid(gx);
        dragGridY = snapGrid(gy);
        dragScreenX = sx;
        dragScreenY = sy;
        draggingPost = -1;
        int i;
        //	CircuitElm origMouse = mouseElm;

        mousePost = -1;
        plotXElm = plotYElm = null;

        if (mouseIsOverSplitter(sx, sy)) {
            setMouseElm(null);
            return;
        }

        if (renderer.circuitArea.contains(sx, sy)) {
            if (mouseElm != null && (mouseElm.getHandleGrabbedClose(gx, gy, POST_GRAB_SQ, MIN_POST_GRAB_SIZE) >= 0)) {
                newMouseElm = mouseElm;
            } else {
                int bestDist = 100000000;
                for (i = 0; i != simulator.elmList.size(); i++) {
                    CircuitElm ce = getElm(i);
                    if (ce.boundingBox.contains(gx, gy)) {
                        int dist = ce.getMouseDistance(gx, gy);
                        if (dist >= 0 && dist < bestDist) {
                            bestDist = dist;
                            newMouseElm = ce;
                        }
                    }
                } // for
            }
        }
        scopeManager.scopeSelected = -1;
        if (newMouseElm == null) {
            for (i = 0; i != scopeManager.scopeCount; i++) {
                Scope s = scopeManager.scopes[i];
                if (s.rect.contains(sx, sy)) {
                    newMouseElm = s.getElm();
                    if (s.plotXY) {
                        plotXElm = s.getXElm();
                        plotYElm = s.getYElm();
                    }
                    scopeManager.scopeSelected = i;
                }
            }
            //	    // the mouse pointer was not in any of the bounding boxes, but we
            //	    // might still be close to a post
            for (i = 0; i != simulator.elmList.size(); i++) {
                CircuitElm ce = getElm(i);
                if (mouseMode == MODE_DRAG_POST) {
                    if (ce.getHandleGrabbedClose(gx, gy, POST_GRAB_SQ, 0) > 0) {
                        newMouseElm = ce;
                        break;
                    }
                }
                int j;
                int jn = ce.getPostCount();
                for (j = 0; j != jn; j++) {
                    Point pt = ce.getPost(j);
                    //   int dist = Graphics.distanceSq(x, y, pt.x, pt.y);
                    if (Graphics.distanceSq(pt.x, pt.y, gx, gy) < 26) {
                        newMouseElm = ce;
                        mousePost = j;
                        break;
                    }
                }
            }
        } else {
            mousePost = -1;
            // look for post close to the mouse pointer
            for (i = 0; i != newMouseElm.getPostCount(); i++) {
                Point pt = newMouseElm.getPost(i);
                if (Graphics.distanceSq(pt.x, pt.y, gx, gy) < 26)
                    mousePost = i;
            }
        }
        repaint();
        setMouseElm(newMouseElm);
    }


    public void onContextMenu(ContextMenuEvent e) {
        e.preventDefault();
        if (!dialogIsShowing()) {
            menuClientX = e.getNativeEvent().getClientX();
            menuClientY = e.getNativeEvent().getClientY();
            doPopupMenu();
        }
    }

    @SuppressWarnings("deprecation")
    void doPopupMenu() {
        if (noEditCheckItem.getState() || dialogIsShowing())
            return;
        menuElm = mouseElm;
        scopeManager.menuScope = -1;
        menuPlot = -1;
        int x, y;
        if (scopeManager.scopeSelected != -1) {
            if (scopeManager.scopes[scopeManager.scopeSelected].canMenu()) {
                scopeManager.menuScope = scopeManager.scopeSelected;
                menuPlot = scopeManager.scopes[scopeManager.scopeSelected].selectedPlot;
                scopePopupMenu.doScopePopupChecks(false, scopeManager.canStackScope(scopeManager.scopeSelected), scopeManager.canCombineScope(scopeManager.scopeSelected),
                        scopeManager.canUnstackScope(scopeManager.scopeSelected), scopeManager.scopes[scopeManager.scopeSelected]);
                contextPanel = new PopupPanel(true);
                contextPanel.add(scopePopupMenu.getMenuBar());
                y = Math.max(0, Math.min(menuClientY, renderer.canvasHeight - 160));
                contextPanel.setPopupPosition(menuClientX, y);
                contextPanel.show();
            }
        } else if (mouseElm != null) {
            if (!(mouseElm instanceof ScopeElm)) {
                elmScopeMenuItem.setEnabled(mouseElm.canViewInScope());
                elmFloatScopeMenuItem.setEnabled(mouseElm.canViewInScope());
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
                contextPanel = new PopupPanel(true);
                contextPanel.add(elmMenuBar);
                contextPanel.setPopupPosition(menuClientX, menuClientY);
                contextPanel.show();
            } else {
                ScopeElm s = (ScopeElm) mouseElm;
                if (s.elmScope.canMenu()) {
                    menuPlot = s.elmScope.selectedPlot;
                    scopePopupMenu.doScopePopupChecks(true, false, false, false, s.elmScope);
                    contextPanel = new PopupPanel(true);
                    contextPanel.add(scopePopupMenu.getMenuBar());
                    contextPanel.setPopupPosition(menuClientX, menuClientY);
                    contextPanel.show();
                }
            }
        } else {
            doMainMenuChecks();
            contextPanel = new PopupPanel(true);
            contextPanel.add(mainMenuBar);
            x = Math.max(0, Math.min(menuClientX, renderer.canvasWidth - 400));
            y = Math.max(0, Math.min(menuClientY, renderer.canvasHeight - 450));
            contextPanel.setPopupPosition(x, y);
            contextPanel.show();
        }
    }

    void longPress() {
        doPopupMenu();
    }

    void twoFingerTouch(int x, int y) {
        tempMouseMode = MODE_DRAG_ALL;
        dragScreenX = x;
        dragScreenY = y;
    }

    //    public void mouseClicked(MouseEvent e) {
    public void onClick(ClickEvent e) {
        e.preventDefault();
//    	//IES - remove inteaction
////	if ( e.getClickCount() == 2 && !didSwitch )
////	    doEditMenu(e);
//	if (e.getNativeButton() == NativeEvent.BUTTON_LEFT) {
//	    if (mouseMode == MODE_SELECT || mouseMode == MODE_DRAG_SELECTED)
//		clearSelection();
//	}
        if ((e.getNativeButton() == NativeEvent.BUTTON_MIDDLE))
            scrollValues(e.getNativeEvent().getClientX(), e.getNativeEvent().getClientY(), 0);
    }

    public void onDoubleClick(DoubleClickEvent e) {
        e.preventDefault();
        //   	if (!didSwitch && mouseElm != null)
        if (mouseElm != null && !(mouseElm instanceof SwitchElm) && !noEditCheckItem.getState())
            doEdit(mouseElm);
    }

//    public void mouseEntered(MouseEvent e) {
//    }

    public void onMouseOut(MouseOutEvent e) {
        mouseCursorX = -1;
    }

    void clearMouseElm() {
        scopeManager.scopeSelected = -1;
        setMouseElm(null);
        plotXElm = plotYElm = null;
    }

    int menuClientX, menuClientY;
    int menuX, menuY;

    public void onMouseDown(MouseDownEvent e) {
//    public void mousePressed(MouseEvent e) {
        e.preventDefault();

        // make sure canvas has focus, not stop button or something else, so all shortcuts work
        renderer.cv.setFocus(true);

        simulator.stopElm = null; // if stopped, allow user to select other elements to fix circuit
        menuX = menuClientX = e.getX();
        menuY = menuClientY = e.getY();
        mouseDownTime = System.currentTimeMillis();

        // maybe someone did copy in another window?  should really do this when
        // window receives focus
        enablePaste();

        if (e.getNativeButton() != NativeEvent.BUTTON_LEFT && e.getNativeButton() != NativeEvent.BUTTON_MIDDLE)
            return;

        // set mouseElm in case we are on mobile
        mouseSelect(e);

        mouseDragging = true;
        didSwitch = false;

        if (mouseWasOverSplitter) {
            tempMouseMode = MODE_DRAG_SPLITTER;
            return;
        }
        if (e.getNativeButton() == NativeEvent.BUTTON_LEFT) {
//	    // left mouse
            tempMouseMode = mouseMode;
            if (e.isAltKeyDown() && e.isMetaKeyDown())
                tempMouseMode = MODE_DRAG_COLUMN;
            else if (e.isAltKeyDown() && e.isShiftKeyDown())
                tempMouseMode = MODE_DRAG_ROW;
            else if (e.isShiftKeyDown())
                tempMouseMode = MODE_SELECT;
            else if (e.isAltKeyDown())
                tempMouseMode = MODE_DRAG_ALL;
            else if (e.isControlKeyDown() || e.isMetaKeyDown())
                tempMouseMode = MODE_DRAG_POST;
        } else
            tempMouseMode = MODE_DRAG_ALL;


        if (noEditCheckItem.getState())
            tempMouseMode = MODE_SELECT;

        if (!(dialogIsShowing()) && ((scopeManager.scopeSelected != -1 && scopeManager.scopes[scopeManager.scopeSelected].cursorInSettingsWheel()) ||
                (scopeManager.scopeSelected == -1 && mouseElm instanceof ScopeElm && ((ScopeElm) mouseElm).elmScope.cursorInSettingsWheel()))) {
            if (noEditCheckItem.getState())
                return;
            Scope s;
            if (scopeManager.scopeSelected != -1)
                s = scopeManager.scopes[scopeManager.scopeSelected];
            else
                s = ((ScopeElm) mouseElm).elmScope;
            s.properties();
            clearSelection();
            mouseDragging = false;
            return;
        }

        int gx = renderer.inverseTransformX(e.getX());
        int gy = renderer.inverseTransformY(e.getY());
        if (doSwitch(gx, gy)) {
            // do this BEFORE we change the mouse mode to MODE_DRAG_POST!  Or else logic inputs
            // will add dots to the whole circuit when we click on them!
            didSwitch = true;
            return;
        }

        // IES - Grab resize handles in select mode if they are far enough apart and you are on top of them
        if (tempMouseMode == MODE_SELECT && mouseElm != null && !noEditCheckItem.getState() &&
                mouseElm.getHandleGrabbedClose(gx, gy, POST_GRAB_SQ, MIN_POST_GRAB_SIZE) >= 0 &&
                !anySelectedButMouse())
            tempMouseMode = MODE_DRAG_POST;

        if (tempMouseMode != MODE_SELECT && tempMouseMode != MODE_DRAG_SELECTED)
            clearSelection();

        pushUndo();
        initDragGridX = gx;
        initDragGridY = gy;
        dragging = true;
        if (tempMouseMode != MODE_ADD_ELM)
            return;
//
        int x0 = snapGrid(gx);
        int y0 = snapGrid(gy);
        if (!renderer.circuitArea.contains(e.getX(), e.getY()))
            return;

        try {
            dragElm = CircuitElmCreator.constructElement(mouseModeStr, x0, y0);
        } catch (Exception ex) {
            debugger();
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
            mainMenuItems.get(i).setState(s == mouseModeStr);

            // Code to disable draw menu items when cct is not editable, but no used in this version as it
            // puts up a dialog box instead (see menuPerformed).
            //if (s.length() > 3 && s.substring(s.length()-3)=="Elm")
            //mainMenuItems.get(i).setEnabled(!noEditCheckItem.getState());
        }
        stackAllItem.setEnabled(scopeManager.scopeCount > 1 && scopeManager.scopes[scopeManager.scopeCount - 1].position > 0);
        unstackAllItem.setEnabled(scopeManager.scopeCount > 1 && scopeManager.scopes[scopeManager.scopeCount - 1].position != scopeManager.scopeCount - 1);
        combineAllItem.setEnabled(scopeManager.scopeCount > 1);
        separateAllItem.setEnabled(scopeManager.scopeCount > 0);

        // also update the subcircuit menu if necessary
        if (lastSubcircuitMenuUpdate != CustomCompositeModel.sequenceNumber)
            composeSubcircuitMenu();
    }


    public void onMouseUp(MouseUpEvent e) {
        e.preventDefault();
        mouseDragging = false;

        // click to clear selection
        if (tempMouseMode == MODE_SELECT && selectedArea == null)
            clearSelection();

        // cmd-click = split wire
        if (tempMouseMode == MODE_DRAG_POST && draggingPost == -1)
            doSplit(mouseElm);

        tempMouseMode = mouseMode;
        selectedArea = null;
        dragging = false;
        boolean circuitChanged = false;
        if (heldSwitchElm != null) {
            heldSwitchElm.mouseUp();
            heldSwitchElm = null;
            circuitChanged = true;
        }
        if (dragElm != null) {
            // if the element is zero size then don't create it
            // IES - and disable any previous selection
            if (dragElm.creationFailed()) {
                dragElm.delete();
                if (mouseMode == MODE_SELECT || mouseMode == MODE_DRAG_SELECTED)
                    clearSelection();
            } else {
                simulator.elmList.addElement(dragElm);
                dragElm.draggingDone();
                circuitChanged = true;
                writeRecoveryToStorage();
            }
            dragElm = null;
        }
        if (circuitChanged) {
            needAnalyze();
            pushUndo();
            unsavedChanges = true;
            changeWindowTitle(unsavedChanges);
        }
        if (dragElm != null)
            dragElm.delete();
        dragElm = null;
        repaint();
    }

    public void onMouseWheel(MouseWheelEvent e) {
        e.preventDefault();

        // once we start zooming, don't allow other uses of mouse wheel for a while
        // so we don't accidentally edit a resistor value while zooming
        boolean zoomOnly = System.currentTimeMillis() < zoomTime + 1000;

        if (noEditCheckItem.getState() || !mouseWheelEditCheckItem.getState())
            zoomOnly = true;

        if (!zoomOnly)
            scrollValues(e.getNativeEvent().getClientX(), e.getNativeEvent().getClientY(), e.getDeltaY());

        if (mouseElm instanceof MouseWheelHandler && !zoomOnly)
            ((MouseWheelHandler) mouseElm).onMouseWheel(e);
        else if (scopeManager.scopeSelected != -1 && !zoomOnly)
            scopeManager.scopes[scopeManager.scopeSelected].onMouseWheel(e);
        else if (!dialogIsShowing()) {
            mouseCursorX = e.getX();
            mouseCursorY = e.getY();
            renderer.zoomCircuit(-e.getDeltaY() * wheelSensitivity, false);
            zoomTime = System.currentTimeMillis();
        }
        repaint();
    }

    void setPowerBarEnable() {
        if (powerCheckItem.getState()) {
            powerLabel.setStyleName("disabled", false);
            powerBar.enable();
        } else {
            powerLabel.setStyleName("disabled", true);
            powerBar.disable();
        }
    }

    ScrollValuePopup scrollValuePopup;

    void scrollValues(int x, int y, int deltay) {
        if (mouseElm != null && !dialogIsShowing() && scopeManager.scopeSelected == -1)
            if (mouseElm instanceof ResistorElm || mouseElm instanceof CapacitorElm || mouseElm instanceof InductorElm) {
                scrollValuePopup = new ScrollValuePopup(x, y, deltay, mouseElm, this);
                unsavedChanges = true;
                changeWindowTitle(unsavedChanges);
            }
    }

    void enableItems() {
    }

    void setGrid() {
        gridSize = (smallGridCheckItem.getState()) ? 8 : 16;
        gridMask = ~(gridSize - 1);
        gridRound = gridSize / 2 - 1;
    }

    void setToolbar() {
        layoutPanel.setWidgetHidden(toolbar, !toolbarCheckItem.getState());
        executeJS("setAllAbsBtnsTopPos(\"" + getAbsBtnsTopPos() + "px\")");
        setSlidersPanelHeight();
        setCanvasSize();
    }

    void pushUndo() {
        redoStack.removeAllElements();
        String s = dumpCircuit();
        if (undoStack.size() > 0 &&
                s.compareTo(undoStack.lastElement().dump) == 0)
            return;
        undoStack.add(new UndoItem(s));
        enableUndoRedo();
        savedFlag = false;
    }

    void doUndo() {
        if (undoStack.size() == 0)
            return;
        redoStack.add(new UndoItem(dumpCircuit()));
        UndoItem ui = undoStack.remove(undoStack.size() - 1);
        loadUndoItem(ui);
        enableUndoRedo();
        unsavedChanges = true;
        changeWindowTitle(unsavedChanges);
    }

    void doRedo() {
        if (redoStack.size() == 0)
            return;
        undoStack.add(new UndoItem(dumpCircuit()));
        UndoItem ui = redoStack.remove(redoStack.size() - 1);
        loadUndoItem(ui);
        enableUndoRedo();
        unsavedChanges = true;
        changeWindowTitle(unsavedChanges);
    }

    void loadUndoItem(UndoItem ui) {
        readCircuit(ui.dump, RC_NO_CENTER);
        renderer.transform[0] = renderer.transform[3] = ui.scale;
        renderer.transform[4] = ui.transform4;
        renderer.transform[5] = ui.transform5;
    }

    void doRecover() {
        pushUndo();
        readCircuit(recovery);
        allowSave(false);
        recoverItem.setEnabled(false);
        filePath = null;
        fileName = null;
        changeWindowTitle(unsavedChanges);
    }

    void enableUndoRedo() {
        redoItem.setEnabled(redoStack.size() > 0);
        undoItem.setEnabled(undoStack.size() > 0);
    }

    void setMouseMode(int mode) {
        mouseMode = mode;
        if (mode == MODE_ADD_ELM) {
            setCursorStyle("cursorCross");
        } else {
            setCursorStyle("cursorPointer");
        }
    }

    void setCursorStyle(String s) {
        if (lastCursorStyle != null)
            renderer.cv.removeStyleName(lastCursorStyle);
        renderer.cv.addStyleName(s);
        lastCursorStyle = s;
    }


    void setMenuSelection() {
        if (menuElm != null) {
            if (menuElm.selected)
                return;
            clearSelection();
            menuElm.setSelected(true);
        }
    }

    int countSelected() {
        int count = 0;
        for (CircuitElm ce : simulator.elmList)
            if (ce.isSelected())
                count++;
        return count;
    }

    class FlipInfo {
        public int cx, cy, count;
    }

    FlipInfo prepareFlip() {
        int i;
        pushUndo();
        setMenuSelection();
        int minx = 30000, maxx = -30000;
        int miny = 30000, maxy = -30000;
        int count = countSelected();
        for (i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm ce = getElm(i);
            if (ce.isSelected() || count == 0) {
                minx = Math.min(ce.x, Math.min(ce.x2, minx));
                maxx = Math.max(ce.x, Math.max(ce.x2, maxx));
                miny = Math.min(ce.y, Math.min(ce.y2, miny));
                maxy = Math.max(ce.y, Math.max(ce.y2, maxy));
            }
        }
        FlipInfo fi = new FlipInfo();
        fi.cx = (minx + maxx) / 2;
        fi.cy = (miny + maxy) / 2;
        fi.count = count;
        return fi;
    }

    void flipX() {
        FlipInfo fi = prepareFlip();
        int center2 = fi.cx * 2;
        for (CircuitElm ce : simulator.elmList) {
            if (ce.isSelected() || fi.count == 0)
                ce.flipX(center2, fi.count);
        }
        needAnalyze();
    }

    void flipY() {
        FlipInfo fi = prepareFlip();
        int center2 = fi.cy * 2;
        for (CircuitElm ce : simulator.elmList) {
            if (ce.isSelected() || fi.count == 0)
                ce.flipY(center2, fi.count);
        }
        needAnalyze();
    }

    void flipXY() {
        FlipInfo fi = prepareFlip();
        int xmy = snapGrid(fi.cx - fi.cy);
        console("xmy " + xmy + " grid " + gridSize + " " + fi.cx + " " + fi.cy);
        for (CircuitElm ce : simulator.elmList) {
            if (ce.isSelected() || fi.count == 0)
                ce.flipXY(xmy, fi.count);
        }
        needAnalyze();
    }

    void doCut() {
        int i;
        pushUndo();
        setMenuSelection();

        clipboardManager.doCut();

        doDelete(true);
        enablePaste();
    }


    void writeRecoveryToStorage() {
        console("write recovery");
        Storage stor = Storage.getLocalStorageIfSupported();
        if (stor == null)
            return;
        String s = dumpCircuit();
        stor.setItem("circuitRecovery", s);
    }

    void readRecovery() {
        Storage stor = Storage.getLocalStorageIfSupported();
        if (stor == null)
            return;
        recovery = stor.getItem("circuitRecovery");
    }


    void deleteUnusedScopeElms() {
        // Remove any scopeElms for elements that no longer exist
        for (int i = simulator.elmList.size() - 1; i >= 0; i--) {
            CircuitElm ce = getElm(i);
            if (ce instanceof ScopeElm && (((ScopeElm) ce).elmScope.needToRemove())) {
                ce.delete();
                simulator.elmList.removeElementAt(i);

                // need to rebuild scopeElmArr
                needAnalyze();
            }
        }

    }

    void doDelete(boolean pushUndoFlag) {
        int i;
        if (pushUndoFlag)
            pushUndo();
        boolean hasDeleted = false;

        for (i = simulator.elmList.size() - 1; i >= 0; i--) {
            CircuitElm ce = getElm(i);
            if (willDelete(ce)) {
                if (ce.isMouseElm())
                    setMouseElm(null);
                ce.delete();
                simulator.elmList.removeElementAt(i);
                hasDeleted = true;
            }
        }
        if (hasDeleted) {
            deleteUnusedScopeElms();
            needAnalyze();
            writeRecoveryToStorage();
            unsavedChanges = true;
            changeWindowTitle(unsavedChanges);
        }
    }

    boolean willDelete(CircuitElm ce) {
        // Is this element in the list to be deleted.
        // This changes the logic from the previous version which would initially only
        // delete selected elements (which could include the mouseElm) and then delete the
        // mouseElm if there were no selected elements. Not really sure this added anything useful
        // to the user experience.
        //
        // BTW, the old logic could also leave mouseElm pointing to a deleted element.
        return ce.isSelected() || ce.isMouseElm();
    }

    String copyOfSelectedElms() {
        String r = dumpOptions();
        CustomLogicModel.clearDumpedFlags();
        CustomCompositeModel.clearDumpedFlags();
        DiodeModel.clearDumpedFlags();
        TransistorModel.clearDumpedFlags();

        r += simulator.dumpSelectedItems();

        return r;
    }

    void doCopy() {
        // clear selection when we're done if we're copying a single element using the context menu
        boolean clearSel = (menuElm != null && !menuElm.selected);

        setMenuSelection();

        clipboardManager.doCopy();

        if (clearSel)
            clearSelection();

        enablePaste();
    }

    void enablePaste() {
        pasteItem.setEnabled(clipboardManager.hasClipboardData());
    }

    void doDuplicate() {
        String s;
        setMenuSelection();
        s = copyOfSelectedElms();
        doPaste(s);
    }

    void doPaste(String dump) {
        if (dump == null) {
            dump = clipboardManager.getClipboard();
            if (dump == null) {
                return;
            }
        }


        pushUndo();
        clearSelection();
        int i;
        Rectangle oldbb = null;

        // get old bounding box
        for (i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm ce = getElm(i);
            Rectangle bb = ce.getBoundingBox();
            if (oldbb != null)
                oldbb = oldbb.union(bb);
            else
                oldbb = bb;
        }

        // add new items
        int oldsz = simulator.elmList.size();
        int flags = RC_RETAIN;

        // don't recenter circuit if we're going to paste in place because that will change the transform
//	if (mouseCursorX > 0 && circuitArea.contains(mouseCursorX, mouseCursorY))

        // in fact, don't ever recenter circuit, unless old circuit was empty
        if (oldsz > 0)
            flags |= RC_NO_CENTER;

        readCircuit(dump, flags);

        // select new items and get their bounding box
        Rectangle newbb = null;
        for (i = oldsz; i != simulator.elmList.size(); i++) {
            CircuitElm ce = getElm(i);
            ce.setSelected(true);
            Rectangle bb = ce.getBoundingBox();
            if (newbb != null)
                newbb = newbb.union(bb);
            else
                newbb = bb;
        }

        if (oldbb != null && newbb != null /*&& oldbb.intersects(newbb)*/) {
            // find a place on the edge for new items
            int dx = 0, dy = 0;
            int spacew = renderer.circuitArea.width - oldbb.width - newbb.width;
            int spaceh = renderer.circuitArea.height - oldbb.height - newbb.height;

            if (!oldbb.intersects(newbb)) {
                // old coordinates may be really far away so move them to same origin as current circuit
                dx = snapGrid(oldbb.x - newbb.x);
                dy = snapGrid(oldbb.y - newbb.y);
            }

            if (spacew > spaceh) {
                dx = snapGrid(oldbb.x + oldbb.width - newbb.x + gridSize);
            } else {
                dy = snapGrid(oldbb.y + oldbb.height - newbb.y + gridSize);
            }

            // move new items near the mouse if possible
            if (mouseCursorX > 0 && renderer.circuitArea.contains(mouseCursorX, mouseCursorY)) {
                int gx = renderer.inverseTransformX(mouseCursorX);
                int gy = renderer.inverseTransformY(mouseCursorY);
                int mdx = snapGrid(gx - (newbb.x + newbb.width / 2));
                int mdy = snapGrid(gy - (newbb.y + newbb.height / 2));
                for (i = oldsz; i != simulator.elmList.size(); i++) {
                    if (!getElm(i).allowMove(mdx, mdy))
                        break;
                }
                if (i == simulator.elmList.size()) {
                    dx = mdx;
                    dy = mdy;
                }
            }

            // move the new items
            for (i = oldsz; i != simulator.elmList.size(); i++) {
                CircuitElm ce = getElm(i);
                ce.move(dx, dy);
            }

            // center circuit
            //	handleResize();
        }
        needAnalyze();
        writeRecoveryToStorage();
        unsavedChanges = true;
        changeWindowTitle(unsavedChanges);
    }

    void clearSelection() {
        int i;
        for (i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm ce = getElm(i);
            ce.setSelected(false);
        }
        enableDisableMenuItems();
    }

    void doSelectAll() {
        int i;
        for (i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm ce = getElm(i);
            ce.setSelected(true);
        }
        enableDisableMenuItems();
    }

    boolean anySelectedButMouse() {
        for (int i = 0; i != simulator.elmList.size(); i++)
            if (getElm(i) != mouseElm && getElm(i).selected)
                return true;
        return false;
    }

//    public void keyPressed(KeyEvent e) {}
//    public void keyReleased(KeyEvent e) {}

    boolean dialogIsShowing() {
        if (contextPanel != null && contextPanel.isShowing())
            return true;
        if (scrollValuePopup != null && scrollValuePopup.isShowing())
            return true;
        if (dialogManager.dialogIsShowing()) {
            return true;
        }

        return false;
    }

    public void onPreviewNativeEvent(NativePreviewEvent e) {
        int cc = e.getNativeEvent().getCharCode();
        int t = e.getTypeInt();
        int code = e.getNativeEvent().getKeyCode();
        if (dialogIsShowing()) {
            if (scrollValuePopup != null && scrollValuePopup.isShowing() &&
                    (t & Event.ONKEYDOWN) != 0) {
                if (code == KEY_ESCAPE || code == KEY_SPACE)
                    scrollValuePopup.close(false);
                if (code == KEY_ENTER)
                    scrollValuePopup.close(true);
            }

            // process escape/enter for dialogs
            // multiple edit dialogs could be displayed at once, pick the one in front
            Dialog dlg = dialogManager.getShowingDialog();
            if (dlg != null && dlg.isShowing() && (t & Event.ONKEYDOWN) != 0) {
                if (code == KEY_ESCAPE)
                    dlg.closeDialog();
                if (code == KEY_ENTER)
                    dlg.enterPressed();
            }
            return;
        }

        if ((t & Event.ONKEYPRESS) != 0) {
            if (cc == '-') {
                menuPerformed("key", "zoomout");
                e.cancel();
            }
            if (cc == '+' || cc == '=') {
                menuPerformed("key", "zoomin");
                e.cancel();
            }
            if (cc == '0') {
                menuPerformed("key", "zoom100");
                e.cancel();
            }
            if (cc == '/' && shortcuts['/'] == null) {
                menuPerformed("key", "search");
                e.cancel();
            }
        }

        // all other shortcuts are ignored when editing disabled
        if (noEditCheckItem.getState())
            return;

        if ((t & Event.ONKEYDOWN) != 0) {
            if (code == KEY_BACKSPACE || code == KEY_DELETE) {
                if (scopeManager.scopeSelected != -1) {
                    // Treat DELETE key with scope selected as "remove scope", not delete
                    scopeManager.scopes[scopeManager.scopeSelected].setElm(null);
                    scopeManager.scopeSelected = -1;
                } else {
                    menuElm = null;
                    pushUndo();
                    doDelete(true);
                    e.cancel();
                }
            }
            if (code == KEY_ESCAPE) {
                setMouseMode(MODE_SELECT);
                mouseModeStr = "Select";
                updateToolbar();
                tempMouseMode = mouseMode;
                e.cancel();
            }

            if (e.getNativeEvent().getCtrlKey() || e.getNativeEvent().getMetaKey()) {
                if (code == KEY_C) {
                    menuPerformed("key", "copy");
                    e.cancel();
                }
                if (code == KEY_X) {
                    menuPerformed("key", "cut");
                    e.cancel();
                }
                if (code == KEY_V) {
                    menuPerformed("key", "paste");
                    e.cancel();
                }
                if (code == KEY_Z) {
                    menuPerformed("key", "undo");
                    e.cancel();
                }
                if (code == KEY_Y) {
                    menuPerformed("key", "redo");
                    e.cancel();
                }
                if (code == KEY_D) {
                    menuPerformed("key", "duplicate");
                    e.cancel();
                }
                if (code == KEY_A) {
                    menuPerformed("key", "selectAll");
                    e.cancel();
                }
                if (code == KEY_P) {
                    menuPerformed("key", "print");
                    e.cancel();
                }
                if (code == KEY_N) {
                    menuPerformed("key", "newwindow");
                    e.cancel();
                }
                if (code == KEY_S) {
                    String cmd = (filePath != null) ? "save" : "saveas";
                    menuPerformed("key", cmd);
                    e.cancel();
                }
                if (code == KEY_O) {
                    menuPerformed("key", "importfromlocalfile");
                    e.cancel();
                }
            }
        }
        if ((t & Event.ONKEYPRESS) != 0) {
            if (cc > 32 && cc < 127) {
                String c = shortcuts[cc];
                e.cancel();
                if (c == null)
                    return;
                setMouseMode(MODE_ADD_ELM);
                mouseModeStr = c;
                updateToolbar();
                tempMouseMode = mouseMode;
            }
            if (cc == 32) {
                setMouseMode(MODE_SELECT);
                mouseModeStr = "Select";
                updateToolbar();
                tempMouseMode = mouseMode;
                e.cancel();
            }
        }
    }

    void updateToolbar() {
        //toolbar.setModeLabel(classToLabelMap.get(mouseModeStr));
        toolbar.highlightButton(mouseModeStr);
    }

    String getLabelTextForClass(String cls) {
        return classToLabelMap.get(cls);
    }

    void createNewLoadFile() {
        // This is a hack to fix what IMHO is a bug in the <INPUT FILE element
        // reloading the same file doesn't create a change event so importing the same file twice
        // doesn't work unless you destroy the original input element and replace it with a new one
        int idx = verticalPanel.getWidgetIndex(loadFileInput);
        filePath = loadFileInput.getPath();
        console("filePath: " + filePath);
        fileName = loadFileInput.getFileName();
        console("fileName: " + fileName);
        if (filePath != null)
            allowSave(true);
        changeWindowTitle(false);
        LoadFile newlf = new LoadFile(this);
        verticalPanel.insert(newlf, idx);
        verticalPanel.remove(idx + 1);
        loadFileInput = newlf;
    }

    void addWidgetToVerticalPanel(Widget w) {
        if (iFrame != null) {
            int i = verticalPanel.getWidgetIndex(iFrame);
            verticalPanel.insert(w, i);
        } else
            verticalPanel2.add(w);
    }

    void removeWidgetFromVerticalPanel(Widget w) {
        verticalPanel2.remove(w);
    }


    native boolean weAreInUS(boolean orCanada) /*-{
    try {
	l = navigator.languages ? navigator.languages[0] : (navigator.language || navigator.userLanguage) ;
    	if (l.length > 2) {
    		l = l.slice(-2).toUpperCase();
    		return (l == "US" || (l=="CA" && orCanada));
    	} else {
    		return 0;
    	}

    } catch (e) { return 0;
    }
    }-*/;

    native boolean weAreInGermany() /*-{
    try {
	l = navigator.languages ? navigator.languages[0] : (navigator.language || navigator.userLanguage) ;
	return (l.toUpperCase().startsWith("DE"));
    } catch (e) { return 0;
    }
    }-*/;

    // For debugging
    void dumpNodelist() {

        CircuitNode nd;
        CircuitElm e;
        int i, j;
        String s;
        String cs;
//
//	for(i=0; i<nodeList.size(); i++) {
//	    s="Node "+i;
//	    nd=nodeList.get(i);
//	    for(j=0; j<nd.links.size();j++) {
//		s=s+" " + nd.links.get(j).num + " " +nd.links.get(j).elm.getDumpType();
//	    }
//	    console(s);
//	}
        console("Elm list Dump");
        for (i = 0; i < simulator.elmList.size(); i++) {
            e = simulator.elmList.get(i);
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
            console(s);
        }
    }

    native void printCanvas(CanvasElement cv) /*-{
		var img    = cv.toDataURL("image/png");
		var iframe = $doc.createElement("iframe");
		iframe.src = img;
		iframe.style = "display:none";
		$doc.body.appendChild(iframe);
		var contentWindow = iframe.contentWindow;
		contentWindow.print();
		contentWindow.addEventListener('afterprint', function(){iframe.remove()});
	}-*/;

    void doDCAnalysis() {
        dcAnalysisFlag = true;
        resetAction();
    }

    void doPrint() {
        Canvas cv = renderer.getCircuitAsCanvas(CAC_PRINT);
        printCanvas(cv.getCanvasElement());
    }

    boolean loadedCanvas2SVG = false;

    boolean initializeSVGScriptIfNecessary(final String followupAction) {
        // load canvas2svg if we haven't already
        if (!loadedCanvas2SVG) {
            ScriptInjector.fromUrl("canvas2svg.js").setCallback(new Callback<Void, Exception>() {
                public void onFailure(Exception reason) {
                    Window.alert("Can't load canvas2svg.js.");
                }

                public void onSuccess(Void result) {
                    loadedCanvas2SVG = true;
                    if (followupAction.equals("doExportAsSVG")) {
                        doExportAsSVG();
                    } else if (followupAction.equals("doExportAsSVGFromAPI")) {
                        doExportAsSVGFromAPI();
                    }
                }
            }).inject();
            return false;
        }
        return true;
    }

    void doExportAsSVG() {
        if (!initializeSVGScriptIfNecessary("doExportAsSVG")) {
            return;
        }
        dialogManager.showExportAsImageDialog(CAC_SVG);
    }

    public void doExportAsSVGFromAPI() {
        if (!initializeSVGScriptIfNecessary("doExportAsSVGFromAPI")) {
            return;
        }
        String svg = renderer.getCircuitAsSVG();
        callSVGRenderedHook(svg);
    }

    static final int CAC_PRINT = 0;
    static final int CAC_IMAGE = 1;
    static final int CAC_SVG = 2;


    // create SVG context using canvas2svg
    native static Context2d createSVGContext(int w, int h) /*-{
	    return new C2S(w, h);
	}-*/;

    native static String getSerializedSVG(Context2d context) /*-{
	    return context.getSerializedSvg();
	}-*/;

    // JSInterface
    double getLabeledNodeVoltage(String name) {
        Integer node = LabeledNodeElm.getByName(name);
        if (node == null || node == 0)
            return 0;
        // subtract one because ground is not included in nodeVoltages[]
        return simulator.nodeVoltages[node.intValue() - 1];
    }

    // JSInterface
    void setExtVoltage(String name, double v) {
        int i;
        for (i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm ce = getElm(i);
            if (ce instanceof ExtVoltageElm) {
                ExtVoltageElm eve = (ExtVoltageElm) ce;
                if (eve.getName().equals(name))
                    eve.setVoltage(v);
            }
        }
    }

    native JsArray<JavaScriptObject> getJSArray() /*-{ return []; }-*/;

    // JSInterface
    JsArray<JavaScriptObject> getJSElements() {
        int i;
        JsArray<JavaScriptObject> arr = getJSArray();
        for (i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm ce = getElm(i);
            ce.addJSMethods();
            arr.push(ce.getJavaScriptObject());
        }
        return arr;
    }

    // JSInterface
    void zoomCircuit(double dy) {
        renderer.zoomCircuit(dy);
    }

    native void setupJSInterface() /*-{
	    var that = this;
	    $wnd.CircuitJS1 = {
	        setSimRunning: $entry(function(run) { that.@com.lushprojects.circuitjs1.client.CirSim::setSimRunning(Z)(run); } ),
	        getTime: $entry(function() { return that.@com.lushprojects.circuitjs1.client.CirSim::t; } ),
	        getTimeStep: $entry(function() { return that.@com.lushprojects.circuitjs1.client.CircuitSimulator::timeStep; } ),
	        setTimeStep: $entry(function(ts) { that.@com.lushprojects.circuitjs1.client.CircuitSimulator::timeStep = ts; } ), // don't use this, see #843
	        getMaxTimeStep: $entry(function() { return that.@com.lushprojects.circuitjs1.client.CircuitSimulator::maxTimeStep; } ),
	        setMaxTimeStep: $entry(function(ts) { that.@com.lushprojects.circuitjs1.client.CircuitSimulator::maxTimeStep =
                                                      that.@com.lushprojects.circuitjs1.client.CircuitSimulator::timeStep = ts; } ),
	        isRunning: $entry(function() { return that.@com.lushprojects.circuitjs1.client.CirSim::simIsRunning()(); } ),
	        getNodeVoltage: $entry(function(n) { return that.@com.lushprojects.circuitjs1.client.CirSim::getLabeledNodeVoltage(Ljava/lang/String;)(n); } ),
	        setExtVoltage: $entry(function(n, v) { that.@com.lushprojects.circuitjs1.client.CirSim::setExtVoltage(Ljava/lang/String;D)(n, v); } ),
	        getElements: $entry(function() { return that.@com.lushprojects.circuitjs1.client.CirSim::getJSElements()(); } ),
	        getCircuitAsSVG: $entry(function() { return that.@com.lushprojects.circuitjs1.client.CirSim::doExportAsSVGFromAPI()(); } ),
	        exportCircuit: $entry(function() { return that.@com.lushprojects.circuitjs1.client.CirSim::dumpCircuit()(); } ),
	        importCircuit: $entry(function(circuit, subcircuitsOnly) { return that.@com.lushprojects.circuitjs1.client.CirSim::importCircuitFromText(Ljava/lang/String;Z)(circuit, subcircuitsOnly); }),
			redrawCanvasSize: $entry(function() { return that.@com.lushprojects.circuitjs1.client.CirSim::redrawCanvasSize()(); } ),
			allowSave: $entry(function(b) { return that.@com.lushprojects.circuitjs1.client.CirSim::allowSave(Z)(b);})
	    };
	    var hook = $wnd.oncircuitjsloaded;
	    if (hook)
	    	hook($wnd.CircuitJS1);
	}-*/;

    native void callUpdateHook() /*-{
	    var hook = $wnd.CircuitJS1.onupdate;
	    if (hook)
	    	hook($wnd.CircuitJS1);
	}-*/;

    native void callAnalyzeHook() /*-{
        var hook = $wnd.CircuitJS1.onanalyze;
        if (hook)
            hook($wnd.CircuitJS1);
    }-*/;


    native void callTimeStepHook() /*-{
	    var hook = $wnd.CircuitJS1.ontimestep;
	    if (hook)
	    	hook($wnd.CircuitJS1);
	}-*/;

    native void callSVGRenderedHook(String svgData) /*-{
		var hook = $wnd.CircuitJS1.onsvgrendered;
		if (hook)
			hook($wnd.CircuitJS1, svgData);
	}-*/;

    class UndoItem {
        public String dump;
        public double scale, transform4, transform5;

        UndoItem(String d) {
            dump = d;
            scale = renderer.transform[0];
            transform4 = renderer.transform[4];
            transform5 = renderer.transform[5];
        }
    }

}

