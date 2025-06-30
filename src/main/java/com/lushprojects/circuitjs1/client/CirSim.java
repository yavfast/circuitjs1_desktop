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

    static int MENU_BAR_HEIGHT = 30;
    static int TOOLBAR_HEIGHT = 40;
    static int VERTICAL_PANEL_WIDTH = 166; // default
    static int INFO_WIDTH = 160;

    final CircuitInfo circuitInfo = new CircuitInfo(this);
    final LogManager logManager = new LogManager(this);

    final CircuitSimulator simulator = new CircuitSimulator(this);
    final CircuitRenderer renderer = new CircuitRenderer(this);

    final ScopeManager scopeManager = new ScopeManager(this);
    final ClipboardManager clipboardManager = new ClipboardManager(this);
    final DialogManager dialogManager = new DialogManager(this);
    final MenuManager menuManager = new MenuManager(this);
    final UndoManager undoManager = new UndoManager(this);
    final AdjustableManager adjustableManager = new AdjustableManager(this);
    final CircuitEditor circuitEditor = new CircuitEditor(this);
    final ActionManager actionManager = new ActionManager(this);
    final CircuitLoader circuitLoader = new CircuitLoader(this);
    final LoadFile loadFileInput = new LoadFile(this);

    Toolbar toolbar;

    DockLayoutPanel layoutPanel;
    VerticalPanel verticalPanel;
    VerticalPanel verticalPanel2;
    ScrollPanel slidersPanel;
    CellPanel buttonPanel;

    Button resetButton;
    Button runStopButton;
    Label powerLabel;
    Label titleLabel;
    Scrollbar speedBar;
    Scrollbar currentBar;
    Scrollbar powerBar;

    Button absResetBtn;
    Button absRunStopBtn;

    Frame iFrame = null;

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
        int width = RootLayoutPanel.get().getOffsetWidth();
        int height = RootLayoutPanel.get().getOffsetHeight();
        height = height - (circuitInfo.hideMenu ? 0 : MENU_BAR_HEIGHT);

        if (isSidePanelCheckboxChecked() && lstor.getItem("MOD_overlayingSidebar") == "false")
            width = width - VERTICAL_PANEL_WIDTH;
        if (menuManager.toolbarCheckItem.getState())
            height -= TOOLBAR_HEIGHT;

        width = Math.max(width, 0);
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

    public void setLastFileName(String s) {
        // remember filename for use when saving a new file.
        // if s is null or automatically generated then just clear out old filename.
        if (s == null || s.startsWith("circuitjs-"))
            circuitInfo.lastFileName = null;
        else
            circuitInfo.lastFileName = s;
    }

    public String getLastFileName() {
        Date date = new Date();
        String fname;
        if (circuitInfo.lastFileName != null)
            fname = circuitInfo.lastFileName;
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

//    String baseURL = "http://www.falstad.com/circuit/";

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

        circuitInfo.loadQueryParameters();

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
        VERTICAL_PANEL_WIDTH = 166;

        verticalPanel = new VerticalPanel();
        slidersPanel = new ScrollPanel();
        verticalPanel2 = new VerticalPanel();

        verticalPanel.getElement().addClassName("verticalPanel");
        verticalPanel.getElement().setId("painel");
        Element sidePanelCheckbox = DOM.createInputCheck();
        Element sidePanelCheckboxLabel = DOM.createLabel();
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

        toolbar = new Toolbar(this);
        toolbar.setEuroResistors(circuitInfo.euroSetting);
        if (!circuitInfo.hideMenu)
            layoutPanel.addNorth(menuBar, MENU_BAR_HEIGHT);

        // add toolbar immediately after menuBar
        layoutPanel.addNorth(toolbar, TOOLBAR_HEIGHT);

        if (circuitInfo.hideSidebar)
            VERTICAL_PANEL_WIDTH = 0;
        else {
            DOM.appendChild(layoutPanel.getElement(), sidePanelCheckbox);
            DOM.appendChild(layoutPanel.getElement(), sidePanelCheckboxLabel);
            layoutPanel.addEast(verticalPanel, VERTICAL_PANEL_WIDTH);
        }

        // Only add log panel if developerMode is enabled
        if (circuitInfo.developerMode) {
            layoutPanel.addWest(logManager.logPanel, logManager.logPanelWidth);
        }

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

        if (LoadFile.isSupported()) {
            verticalPanel.add(loadFileInput);
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

        l = new Label(Locale.LS("Current Circuit:"));
        l.addStyleName("topSpace");
        l.addStyleName("sidePanelElm");
        titleLabel = new Label("Label");
        titleLabel.addStyleName("sidePanelElm");
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

        slidersPanel.getElement().getStyle().setOverflowX(Overflow.HIDDEN);
        slidersPanel.getElement().getStyle().setOverflowY(Overflow.SCROLL);

        circuitEditor.setGrid();

        menuManager.initElmMenuBar();

        setColors(circuitInfo.positiveColor, circuitInfo.negativeColor, circuitInfo.neutralColor,
                circuitInfo.selectColor, circuitInfo.currentColor);

        if (circuitInfo.startCircuitText != null) {
            circuitLoader.getSetupList(false);
            circuitLoader.readCircuit(circuitInfo.startCircuitText);
            setUnsavedChanges(false);
        } else {
            if (simulator.stopMessage == null && circuitInfo.startCircuitLink != null) {
                circuitLoader.readCircuit("");
                circuitLoader.getSetupList(false);
                //ImportFromDropboxDialog.setSim(this);
                //ImportFromDropboxDialog.doImportDropboxLink(startCircuitLink, false);
            } else {
                circuitLoader.readCircuit("");
                if (simulator.stopMessage == null && circuitInfo.startCircuit != null) {
                    circuitLoader.getSetupList(false);
                    circuitLoader.readSetupFile(circuitInfo.startCircuit, circuitInfo.startLabel);
                } else
                    circuitLoader.getSetupList(true);
            }
        }

        if (circuitInfo.mouseModeReq != null)
            actionManager.menuPerformed("main", circuitInfo.mouseModeReq);

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
                if (circuitInfo.unsavedChanges && !isElectron())
                    event.setMessage(Locale.LS("Are you sure?  There are unsaved changes."));
            }
        });
        setupJSInterface();

        setSimRunning(circuitInfo.running);
    }

    public void setDeveloperMode(boolean enabled) {
        if (circuitInfo.developerMode == enabled) {
            return;
        }

        circuitInfo.developerMode = enabled;

        boolean logPanelPresent = layoutPanel.getWidgetIndex(logManager.logPanel) != -1;
        if (enabled && !logPanelPresent) {
            layoutPanel.addWest(logManager.logPanel, logManager.logPanelWidth);
        } else if (!enabled && logPanelPresent) {
            layoutPanel.remove(logManager.logPanel);
        }

        setCanvasSize();
        repaint();
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

    void needAnalyze() {
        renderer.analyzeFlag = true;
        repaint();
        enableDisableMenuItems();
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
        renderer.analyzeFlag = true;
        if (simulator.t == 0)
            setSimRunning(true);
        simulator.t = simulator.timeStepAccum = 0;
        simulator.timeStepCount = 0;
        for (int i = 0; i != simulator.elmList.size(); i++)
            simulator.elmList.get(i).reset();
        for (int i = 0; i != scopeManager.scopeCount; i++)
            scopeManager.scopes[i].resetGraph(true);
        repaint();
    }

    static native void changeWindowTitle(boolean isCircuitChanged)/*-{
		var newTitle = "CircuitJS1 Desktop Mod";
		var filename = @com.lushprojects.circuitjs1.client.CirSim::theSim.@com.lushprojects.circuitjs1.client.CirSim::circuitInfo.@com.lushprojects.circuitjs1.client.CircuitInfo::fileName;
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
			// AI_THINK: Fixed to access circuitInfo fields through theSim instance instead of non-existent static fields
			@com.lushprojects.circuitjs1.client.CirSim::theSim.@com.lushprojects.circuitjs1.client.CirSim::circuitInfo.@com.lushprojects.circuitjs1.client.CircuitInfo::filePath = saveasInput.value;
			@com.lushprojects.circuitjs1.client.CirSim::theSim.@com.lushprojects.circuitjs1.client.CirSim::circuitInfo.@com.lushprojects.circuitjs1.client.CircuitInfo::fileName = saveasInput.files[0].name;
			@com.lushprojects.circuitjs1.client.CirSim::theSim.@com.lushprojects.circuitjs1.client.CirSim::circuitInfo.@com.lushprojects.circuitjs1.client.CircuitInfo::lastFileName = saveasInput.files[0].name;
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
        theSim.circuitInfo.savedFlag = true;
        theSim.repaint();
    }

    static void electronSaveCallback() {
        theSim.circuitInfo.savedFlag = true;
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
        theSim.loadFileInput.doLoadCallback(text, name);
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

    // JSInterface
    String dumpCircuit() {
        return actionManager.dumpCircuit();
    }

    // JSInterface
    public void importCircuitFromText(String circuitText, boolean subcircuitsOnly) {
        actionManager.importCircuitFromText(circuitText, subcircuitsOnly);
    }

    void allowSave(boolean b) {
        if (menuManager.saveFileItem != null)
            menuManager.saveFileItem.setEnabled(b);
    }

    void setUnsavedChanges(boolean hasChanges) {
        circuitInfo.unsavedChanges = hasChanges;
        changeWindowTitle(hasChanges);
    }

    static native void clipboardWriteImage(CanvasElement cv) /*-{
	cv.toBlob(function(blob) {
	    var promise = parent.navigator.clipboard.write([new ClipboardItem({ "image/png": blob })]);
	    promise.then(function(x) { console.log(x); });
	});
    }-*/;

    void setCircuitTitle(String s) {
        if (s != null)
            titleLabel.setText(s);
        setSlidersPanelHeight();
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

    void enablePaste() {
        menuManager.pasteItem.setEnabled(clipboardManager.hasClipboardData());
    }

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
        actionManager.onPreviewNativeEvent(e);
    }

    void updateToolbar() {
        toolbar.highlightButton(circuitEditor.mouseModeStr);
    }

    String getLabelTextForClass(String cls) {
        return menuManager.classToLabelMap.get(cls);
    }

    void createNewLoadFile() {
        // This is a hack to fix what IMHO is a bug in the <INPUT FILE element
        // reloading the same file doesn't create a change event so importing the same file twice
        // doesn't work unless you destroy the original input element and replace it with a new one
        int idx = verticalPanel.getWidgetIndex(loadFileInput);
        circuitInfo.filePath = loadFileInput.getPath();
        console("filePath: " + circuitInfo.filePath);
        circuitInfo.fileName = loadFileInput.getFileName();
        console("fileName: " + circuitInfo.fileName);
        if (circuitInfo.filePath != null)
            allowSave(true);
        changeWindowTitle(false);

        // TODO:
//        LoadFile newlf = new LoadFile(this);
//        verticalPanel.insert(newlf, idx);
//        verticalPanel.remove(idx + 1);
//        loadFileInput = newlf;
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
        circuitInfo.dcAnalysisFlag = true;
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
            CircuitElm ce = simulator.elmList.get(i);
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
            CircuitElm ce = simulator.elmList.get(i);
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
	        getTime: $entry(function() { return that.@com.lushprojects.circuitjs1.client.CirSim::simulator.@com.lushprojects.circuitjs1.client.CircuitSimulator::t; } ),
	        getTimeStep: $entry(function() { return that.@com.lushprojects.circuitjs1.client.CirSim::simulator.@com.lushprojects.circuitjs1.client.CircuitSimulator::timeStep; } ),
	        setTimeStep: $entry(function(ts) { that.@com.lushprojects.circuitjs1.client.CirSim::simulator.@com.lushprojects.circuitjs1.client.CircuitSimulator::timeStep = ts; } ), // don't use this, see #843
	        getMaxTimeStep: $entry(function() { return that.@com.lushprojects.circuitjs1.client.CirSim::simulator.@com.lushprojects.circuitjs1.client.CircuitSimulator::maxTimeStep; } ),
	        setMaxTimeStep: $entry(function(ts) { that.@com.lushprojects.circuitjs1.client.CirSim::simulator.@com.lushprojects.circuitjs1.client.CircuitSimulator::maxTimeStep =
                                                      that.@com.lushprojects.circuitjs1.client.CirSim::simulator.@com.lushprojects.circuitjs1.client.CircuitSimulator::timeStep = ts; } ),
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
        if (!circuitInfo.developerMode) {
            return;
        }

        // AI_THINK: Calculate available height for the log panel based on current layout
        int totalHeight = RootLayoutPanel.get().getOffsetHeight();
        int availableHeight = totalHeight - (circuitInfo.hideMenu ? 0 : MENU_BAR_HEIGHT);
        if (menuManager.toolbarCheckItem.getState())
            availableHeight -= TOOLBAR_HEIGHT;

        layoutPanel.setWidgetSize(logManager.logPanel, newWidth);
        logManager.updatePanelSize(newWidth, availableHeight);

        setCanvasSize();
        repaint();
    }
}

