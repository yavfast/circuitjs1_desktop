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
import com.google.gwt.core.client.ScriptInjector;
import com.google.gwt.dom.client.CanvasElement;
import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.MetaElement;
import com.google.gwt.dom.client.NodeList;
import com.google.gwt.dom.client.Style.Overflow;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.event.dom.client.ContextMenuEvent;
import com.google.gwt.event.logical.shared.ResizeEvent;
import com.google.gwt.event.logical.shared.ResizeHandler;
import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;
import com.google.gwt.http.client.URL;
import com.google.gwt.i18n.client.DateTimeFormat;
import com.google.gwt.storage.client.Storage;
import com.google.gwt.user.client.DOM;
import com.google.gwt.user.client.Element;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.Event.NativePreviewEvent;
import com.google.gwt.user.client.Event.NativePreviewHandler;
import com.google.gwt.user.client.EventListener;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.Window.ClosingEvent;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.CellPanel;
import com.google.gwt.user.client.ui.DockLayoutPanel;
import com.google.gwt.user.client.ui.Frame;
import com.google.gwt.user.client.ui.HorizontalPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.MenuBar;
import com.google.gwt.user.client.ui.MenuItem;
import com.google.gwt.user.client.ui.RootLayoutPanel;
import com.google.gwt.user.client.ui.RootPanel;
import com.google.gwt.user.client.ui.ScrollPanel;
import com.google.gwt.user.client.ui.VerticalPanel;
import com.google.gwt.user.client.ui.Widget;
import com.lushprojects.circuitjs1.client.util.Locale;

import java.util.Date;
import java.util.HashMap;

public class CirSim implements NativePreviewHandler {

    static final int HINT_LC = 1;
    static final int HINT_RC = 2;
    static final int HINT_3DB_C = 3;
    static final int HINT_TWINT = 4;
    static final int HINT_3DB_L = 5;

    static int MENU_BAR_HEIGHT = 30;
    static final int TOOLBAR_HEIGHT = 40;
    static int VERTICAL_PANEL_WIDTH = 166; // default

    static final int INFO_WIDTH = 160;

    LogManager logManager = new LogManager(this);

    CircuitSimulator simulator = new CircuitSimulator(this);
    CircuitRenderer renderer = new CircuitRenderer(this);

    ScopeManager scopeManager = new ScopeManager(this);
    ClipboardManager clipboardManager = new ClipboardManager(this);
    DialogManager dialogManager = new DialogManager(this);
    MenuManager menuManager = new MenuManager(this);
    UndoManager undoManager = new UndoManager(this);
    AdjustableManager adjustableManager = new AdjustableManager(this);
    CircuitEditor circuitEditor = new CircuitEditor(this);

    Button resetButton;
    Button runStopButton;
    Button dumpMatrixButton;

    Label powerLabel;
    Label titleLabel;
    Scrollbar speedBar;
    Scrollbar currentBar;
    Scrollbar powerBar;

    Element sidePanelCheckboxLabel;


    CircuitElm menuElm;

    boolean savedFlag;
    boolean dcAnalysisFlag;
    // boolean useBufferedImage;

    double t; // TODO: tick ???

    int pause = 10;
    int menuPlot = -1;
    int hintType = -1, hintItem1, hintItem2;

    boolean developerMode;

    boolean showResistanceInVoltageSources;
    boolean hideInfoBox;

    static boolean unsavedChanges;
    static String filePath;
    static String fileName;
    static String lastFileName;

    HashMap<String, String> classToLabelMap = new HashMap<>();
    Toolbar toolbar;

    DockLayoutPanel layoutPanel;
    VerticalPanel verticalPanel;
    VerticalPanel verticalPanel2;
    ScrollPanel slidersPanel;
    CellPanel buttonPanel;

    boolean hideMenu = false;

    LoadFile loadFileInput;
    Frame iFrame = null;

    static Button absResetBtn;
    static Button absRunStopBtn;

    boolean euroSetting;
    boolean euroGates = false;


    @Deprecated
    static CirSim theSim;

    static native float devicePixelRatio() /*-{
        return window.devicePixelRatio;
    }-*/;

    void redrawCanvasSize() {
        layoutPanel.setWidgetSize(menuManager.menuBar, MENU_BAR_HEIGHT);
        if (MENU_BAR_HEIGHT < 30)
            menuManager.menuBar.addStyleName("modSmallMenuBar");
        else
            menuManager.menuBar.removeStyleName("modSmallMenuBar");
        setCanvasSize();
        repaint();
    }

    void setMouseElm(CircuitElm ce) {
        circuitEditor.setMouseElm(ce);
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

        width = width - logManager.logPanelWidth;

        if (isSidePanelCheckboxChecked() && lstor.getItem("MOD_overlayingSidebar") == "false")
            width = width - VERTICAL_PANEL_WIDTH;
        if (menuManager.toolbarCheckItem.getState())
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

    boolean printable = false;
    boolean convention = true;
    boolean euroRes = false;
    boolean usRes = false;
    boolean running = true;
    boolean hideSidebar = false;
    boolean noEditing = false;
    boolean mouseWheelEdit = false;

    public void init() {
        console("Start");

        //sets the meta tag to allow the css media queries to work
        MetaElement meta = Document.get().createMetaElement();
        meta.setName("viewport");
        meta.setContent("width=device-width");
        NodeList<com.google.gwt.dom.client.Element> node = Document.get().getElementsByTagName("head");
        node.getItem(0).appendChild(meta);

        CircuitElm.initClass(this);
        undoManager.readRecovery();

        QueryParameters qp = new QueryParameters();
        String positiveColor = null;
        String negativeColor = null;
        String neutralColor = null;
        String selectColor = null;
        String currentColor = null;
        String mouseModeReq = null;

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
            euroGates = qp.getBooleanValue("IECGates", OptionsManager.getBoolOptionFromStorage("euroGates", weAreInGermany()));
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
        } catch (Exception e) {
        }

        euroSetting = false;
        if (euroRes)
            euroSetting = true;
        else if (usRes)
            euroSetting = false;
        else
            euroSetting = OptionsManager.getBoolOptionFromStorage("euroResistors", !weAreInUS(true));

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

        int width = (int) RootLayoutPanel.get().getOffsetWidth();
        VERTICAL_PANEL_WIDTH = 166; /* = width/5;
	if (VERTICAL_PANEL_WIDTH > 166)
	    VERTICAL_PANEL_WIDTH = 166;
	if (VERTICAL_PANEL_WIDTH < 128)
	    VERTICAL_PANEL_WIDTH = 128;*/

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

        menuManager.initMainMenuBar();
        MenuBar menuBar = menuManager.menuBar;

        menuManager.loadShortcuts();

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

        layoutPanel.addWest(logManager.logPanel, logManager.logPanelWidth);

        menuBar.getElement().insertFirst(menuBar.getElement().getChild(1));
        menuBar.getElement().getFirstChildElement().setAttribute("onclick", "document.getElementsByClassName('toptrigger')[0].checked = false");

        RootLayoutPanel.get().add(layoutPanel);

        Canvas cv = renderer.initCanvas();
        if (cv == null) {
            RootPanel.get().add(new Label("Not working. You need a browser that supports the CANVAS element."));
            return;
        }

        cv.addMouseDownHandler(circuitEditor);
        cv.addMouseMoveHandler(circuitEditor);
        cv.addMouseOutHandler(circuitEditor);
        cv.addMouseUpHandler(circuitEditor);
        cv.addClickHandler(circuitEditor);
        cv.addDoubleClickHandler(circuitEditor);
        cv.addMouseWheelHandler(circuitEditor);

        doTouchHandlers(this, cv.getCanvasElement());
        cv.addDomHandler(circuitEditor, ContextMenuEvent.getType());

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

        circuitEditor.setGrid();

        menuManager.initElmMenuBar();

        setColors(positiveColor, negativeColor, neutralColor, selectColor, currentColor);

        if (startCircuitText != null) {
            getSetupList(false);
            readCircuit(startCircuitText);
            setUnsavedChanges(false);
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

        menuBar.addDomHandler(event -> {
            menuManager.doMainMenuChecks();
        }, ClickEvent.getType());

        Event.addNativePreviewHandler(this);

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
        else if (OptionsManager.getBoolOptionFromStorage("alternativeColor", false))
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
            CircuitElm.currentColor = menuManager.conventionCheckItem.getState() ? Color.yellow : Color.cyan;

        CircuitElm.setColorScale();
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
        if (menuManager.toolbarCheckItem.getState())
            ih -= TOOLBAR_HEIGHT;
        if (ih < 0)
            ih = 0;
        slidersPanel.setHeight(ih + "px");
    }

    public void setSimRunning(boolean s) {
        if (s) {
            if (simulator.stopMessage != null)
                return;
            simulator.simRunning = true;
            runStopButton.setHTML(Locale.LSHTML("<strong>RUN</strong>&nbsp;/&nbsp;Stop"));
            runStopButton.setStylePrimaryName("topButton");
            renderer.startTimer();
        } else {
            simulator.simRunning = false;
            runStopButton.setHTML(Locale.LSHTML("Run&nbsp;/&nbsp;<strong>STOP</strong>"));
            runStopButton.setStylePrimaryName("topButton-red");
            renderer.stopTimer();
            renderer.repaint();
            // Ensure selection functionality works even when simulation is stopped
            circuitEditor.setMouseMode("Select");
        }
    }

    public boolean simIsRunning() {
        return simulator.simRunning;
    }

    void repaint() {
        renderer.repaint();
    }

    Color getBackgroundColor() {
        if (menuManager.printableCheckItem.getState())
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

    public static native void debugger() /*-{ debugger; }-*/;

    public static native void js_console(String text)
    /*-{
	    console.log(text);
	}-*/;

    public static void console(String text) {
        js_console(text);
        CirSim.theSim.logManager.addLogEntry(text);
    }

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
        if (menuManager.saveFileItem != null)
            menuManager.saveFileItem.setEnabled(b);
    }

    public void menuPerformed(String menu, String item) {
        if ((menu == "edit" || menu == "main" || menu == "scopes") && menuManager.noEditCheckItem.getState()) {
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
            circuitEditor.pushUndo();
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
            setUnsavedChanges(false);
        }

        if (item == "saveas") {
            nodeSaveAs(dumpCircuit(), getLastFileName());
            setUnsavedChanges(false);
        }

        if (item == "importfromtext") {
            dialogManager.showImportFromTextDialog();
        }
    	/*if (item=="importfromdropbox") {
    		dialogShowing = new ImportFromDropboxDialog(this);
    	}*/
        if (item == "exportasurl") {
            doExportAsUrl();
            setUnsavedChanges(false);
        }
    	/*if (item=="exportaslocalfile") {
    		doExportAsLocalFile();
    		unsavedChanges = false;
    	}*/
        if (item == "exportastext") {
            doExportAsText();
            setUnsavedChanges(false);
        }
        if (item == "exportasimage")
            doExportAsImage();
        if (item == "copypng") {
            doImageToClipboard();
            if (menuManager.contextPanel != null)
                menuManager.contextPanel.hide();
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
            circuitEditor.doRecover();

        if ((menu == "elm" || menu == "scopepop") && menuManager.contextPanel != null)
            menuManager.contextPanel.hide();
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
            circuitEditor.doEdit(new EditOptions(this));
        if (item == "devtools")
            toggleDevTools();
        if (item == "undo")
            circuitEditor.doUndo();
        if (item == "redo")
            circuitEditor.doRedo();

        // if the mouse is hovering over an element, and a shortcut key is pressed, operate on that element (treat it like a context menu item selection)
        if (menu == "key" && circuitEditor.mouseElm != null) {
            menuElm = circuitEditor.mouseElm;
            menu = "elm";
        }
        if (menu != "elm")
            menuElm = null;

        if (item == "cut") {
            circuitEditor.doCut();
        }
        if (item == "copy") {
            circuitEditor.doCopy();
        }
        if (item == "paste")
            circuitEditor.doPaste(null);
        if (item == "duplicate") {
            circuitEditor.doDuplicate();
        }
        if (item == "flip")
            circuitEditor.doFlip();
        if (item == "split")
            circuitEditor.doSplit(menuElm);
        if (item == "selectAll")
            circuitEditor.doSelectAll();

        if (item == "centrecircuit") {
            circuitEditor.pushUndo();
            renderer.centreCircuit();
        }
        if (item == "flipx") {
            circuitEditor.pushUndo();
            circuitEditor.flipX();
        }
        if (item == "flipy") {
            circuitEditor.pushUndo();
            circuitEditor.flipY();
        }
        if (item == "flipxy") {
            circuitEditor.pushUndo();
            circuitEditor.flipXY();
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
            circuitEditor.doEdit(menuElm);
        if (item == "delete") {
            if (menu != "elm")
                menuElm = null;
            circuitEditor.pushUndo();
            circuitEditor.doDelete(true);
        }
        if (item == "sliders")
            circuitEditor.doSliders(menuElm);

        if (item == "viewInScope" && menuElm != null) {
            scopeManager.addScope(menuElm);
        }

        if (item == "viewInFloatScope" && menuElm != null) {
            ScopeElm newScope = new ScopeElm(circuitEditor.snapGrid(menuElm.x + 50), circuitEditor.snapGrid(menuElm.y + 50));
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
            circuitEditor.pushUndo();
            Scope s;
            if (scopeManager.menuScope != -1)
                s = scopeManager.scopes[scopeManager.menuScope];
            else
                s = ((ScopeElm) circuitEditor.mouseElm).elmScope;

            if (item == "dock") {
                scopeManager.dockScope(circuitEditor.mouseElm);
                circuitEditor.doDelete(false);
            }
            if (item == "undock") {
                CircuitElm elm = s.getElm();
                ScopeElm newScope = new ScopeElm(circuitEditor.snapGrid(elm.x + 50), circuitEditor.snapGrid(elm.y + 50));
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

            simulator.deleteUnusedScopeElms();
        }
        if (menu == "circuits" && item.indexOf("setup ") == 0) {
            circuitEditor.pushUndo();
            int sp = item.indexOf(' ', 6);
            readSetupFile(item.substring(6, sp), item.substring(sp + 1));
        }
        if (item == "newblankcircuit") {
            circuitEditor.pushUndo();
            readSetupFile("blank.txt", "Blank Circuit");
        }

        //	if (ac.indexOf("setup ") == 0) {
        //	    pushUndo();
        //	    readSetupFile(ac.substring(6),
        //			  ((MenuItem) e.getSource()).getLabel());
        //	}

        // IES: Moved from itemStateChanged()
        if (menu == "main") {
            if (menuManager.contextPanel != null)
                menuManager.contextPanel.hide();

            circuitEditor.setMouseMode(item);

            updateToolbar();

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

    void setUnsavedChanges(boolean hasChanges) {
        unsavedChanges = hasChanges;
        changeWindowTitle(hasChanges);
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
        int f = (menuManager.dotsCheckItem.getState()) ? 1 : 0;
        f |= (menuManager.smallGridCheckItem.getState()) ? 2 : 0;
        f |= (menuManager.voltsCheckItem.getState()) ? 0 : 4;
        f |= (menuManager.powerCheckItem.getState()) ? 8 : 0;
        f |= (menuManager.showValuesCheckItem.getState()) ? 0 : 16;
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
        dump += adjustableManager.dump();
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
        MenuBar circuitsMenuBar = menuManager.circuitsMenuBar;

        int len = b.length;
        MenuBar stack[] = new MenuBar[6];
        int stackptr = 0;
        stack[stackptr++] = circuitsMenuBar;
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
                circuitsMenuBar.addItem(Locale.LS(line.substring(1)), n);
                circuitsMenuBar = stack[stackptr++] = n;
            } else if (line.charAt(0) == '-') {
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
        filePath = null;
        fileName = null;
        setUnsavedChanges(false);
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
                        filePath = null;
                        fileName = null;
                        setUnsavedChanges(false);
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

    void readCircuit(byte[] b, int flags) {
        int i;
        int len = b.length;
        if ((flags & RC_RETAIN) == 0) {
            circuitEditor.clearMouseElm();
            for (i = 0; i != simulator.elmList.size(); i++) {
                CircuitElm ce = getElm(i);
                ce.delete();
            }
            t = simulator.timeStepAccum = 0;
            simulator.elmList.removeAllElements();
            hintType = -1;
            simulator.maxTimeStep = 5e-6;
            simulator.minTimeStep = 50e-12;
            menuManager.dotsCheckItem.setState(false);
            menuManager.smallGridCheckItem.setState(false);
            menuManager.powerCheckItem.setState(false);
            menuManager.voltsCheckItem.setState(true);
            menuManager.showValuesCheckItem.setState(true);
            circuitEditor.setGrid();
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
                        adjustableManager.addAdjustable(st);
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
            adjustableManager.createSliders();
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
                menuManager.smallGridCheckItem.setState(true);
            return;
        }

        menuManager.dotsCheckItem.setState((flags & 1) != 0);
        menuManager.smallGridCheckItem.setState((flags & 2) != 0);
        menuManager.voltsCheckItem.setState((flags & 4) == 0);
        menuManager.powerCheckItem.setState((flags & 8) == 8);
        menuManager.showValuesCheckItem.setState((flags & 16) == 0);
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
        circuitEditor.setGrid();
    }

    void enableDisableMenuItems() {
        boolean canFlipX = true;
        boolean canFlipY = true;
        boolean canFlipXY = true;
        int selCount = simulator.countSelected();
        for (CircuitElm elm : simulator.elmList)
            if (elm.isSelected() || selCount == 0) {
                if (!elm.canFlipX())
                    canFlipX = false;
                if (!elm.canFlipY())
                    canFlipY = false;
                if (!elm.canFlipXY())
                    canFlipXY = false;
            }
        menuManager.cutItem.setEnabled(selCount > 0);
        menuManager.copyItem.setEnabled(selCount > 0);
        menuManager.flipXItem.setEnabled(canFlipX);
        menuManager.flipYItem.setEnabled(canFlipY);
        menuManager.flipXYItem.setEnabled(canFlipXY);
    }




    void setPowerBarEnable() {
        if (menuManager.powerCheckItem.getState()) {
            powerLabel.setStyleName("disabled", false);
            powerBar.enable();
        } else {
            powerLabel.setStyleName("disabled", true);
            powerBar.disable();
        }
    }

    void enableItems() {
    }

    void setToolbar() {
        layoutPanel.setWidgetHidden(toolbar, !menuManager.toolbarCheckItem.getState());
        executeJS("setAllAbsBtnsTopPos(\"" + getAbsBtnsTopPos() + "px\")");
        setSlidersPanelHeight();
        setCanvasSize();
    }

    void enableUndoRedo() {
        menuManager.redoItem.setEnabled(undoManager.hasRedoStack());
        menuManager.undoItem.setEnabled(undoManager.hasUndoStack());
    }

    void setMenuSelection() {
        if (menuElm != null) {
            if (menuElm.selected)
                return;
            circuitEditor.clearSelection();
            menuElm.setSelected(true);
        }
    }


    void enablePaste() {
        menuManager.pasteItem.setEnabled(clipboardManager.hasClipboardData());
    }


//    public void keyPressed(KeyEvent e) {}
//    public void keyReleased(KeyEvent e) {}

    boolean dialogIsShowing() {
        if (menuManager.contextPanel != null && menuManager.contextPanel.isShowing())
            return true;
        if (circuitEditor.scrollValuePopup != null && circuitEditor.scrollValuePopup.isShowing())
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
            ScrollValuePopup scrollValuePopup = circuitEditor.scrollValuePopup;
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
            if (cc == '/' && menuManager.shortcuts['/'] == null) {
                menuPerformed("key", "search");
                e.cancel();
            }
        }

        // all other shortcuts are ignored when editing disabled
        if (menuManager.noEditCheckItem.getState())
            return;

        if ((t & Event.ONKEYDOWN) != 0) {
            if (code == KEY_BACKSPACE || code == KEY_DELETE) {
                if (scopeManager.scopeSelected != -1) {
                    // Treat DELETE key with scope selected as "remove scope", not delete
                    scopeManager.scopes[scopeManager.scopeSelected].setElm(null);
                    scopeManager.scopeSelected = -1;
                } else {
                    menuElm = null;
                    circuitEditor.pushUndo();
                    circuitEditor.doDelete(true);
                    e.cancel();
                }
            }
            if (code == KEY_ESCAPE) {
                circuitEditor.setMouseMode("Select");
                updateToolbar();
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
                String c = menuManager.shortcuts[cc];
                e.cancel();
                if (c == null)
                    return;
                circuitEditor.setMouseMode(c);
                updateToolbar();
            }
            if (cc == 32) {
                circuitEditor.setMouseMode("Select");
                updateToolbar();
                e.cancel();
            }
        }
    }

    void updateToolbar() {
        toolbar.highlightButton(circuitEditor.mouseModeStr);
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
    void longPress() {
        menuManager.doPopupMenu();
    }

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
    void twoFingerTouch(int x, int y) {
        circuitEditor.twoFingerTouch(x, y);
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

    public void updateLogPanelWidth(int newWidth) {
        // AI_THINK: Calculate available height for the log panel based on current layout
        int totalHeight = (int) RootLayoutPanel.get().getOffsetHeight();
        int availableHeight = totalHeight - (hideMenu ? 0 : MENU_BAR_HEIGHT);
        if (menuManager.toolbarCheckItem.getState())
            availableHeight -= TOOLBAR_HEIGHT;

        // AI_TODO: Update layout panel size and notify LogManager with both dimensions
        layoutPanel.setWidgetSize(logManager.logPanel, newWidth);

        // Pass both width and available height to LogManager for proper sizing
        logManager.updatePanelSize(newWidth, availableHeight);

        setCanvasSize();
        repaint();
    }
}

