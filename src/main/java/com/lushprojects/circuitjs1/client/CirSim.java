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
import com.google.gwt.core.client.JsArrayString;
import com.google.gwt.core.client.ScriptInjector;
import com.google.gwt.dom.client.CanvasElement;
import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.MetaElement;
import com.google.gwt.dom.client.NodeList;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ContextMenuEvent;
import com.google.gwt.event.logical.shared.ResizeEvent;
import com.google.gwt.event.logical.shared.ResizeHandler;
import com.google.gwt.http.client.URL;
import com.google.gwt.user.client.DOM;
import com.google.gwt.user.client.Element;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.Event.NativePreviewEvent;
import com.google.gwt.user.client.Event.NativePreviewHandler;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.Window.ClosingEvent;
import com.google.gwt.user.client.ui.DockLayoutPanel;
import com.google.gwt.user.client.ui.Frame;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.MenuBar;
import com.google.gwt.user.client.ui.RootLayoutPanel;
import com.google.gwt.user.client.ui.RootPanel;
import com.google.gwt.user.client.ui.Widget;
import com.lushprojects.circuitjs1.client.dialog.ControlsDialog;
import com.lushprojects.circuitjs1.client.dialog.SlidersDialog;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.ExtVoltageElm;
import com.lushprojects.circuitjs1.client.element.LabeledNodeElm;
import com.lushprojects.circuitjs1.client.io.json.CircuitElementFactory;
import com.lushprojects.circuitjs1.client.ui.tabs.TabBarPanel;
import com.lushprojects.circuitjs1.client.util.Locale;

public class CirSim extends BaseCirSim implements NativePreviewHandler {

    public static int MENU_BAR_HEIGHT = 30;
    public static int TOOLBAR_HEIGHT = 40;
    public static int TAB_BAR_HEIGHT = 28;
    public static int INFO_WIDTH = 160;

    Toolbar toolbar;
    TabBarPanel tabBarPanel;

    DockLayoutPanel layoutPanel;

    public ControlsDialog controlsDialog;
    public SlidersDialog slidersDialog;

    public Label powerLabel;
    public Scrollbar speedBar;
    public Scrollbar currentBar;
    public Scrollbar powerBar;
    public Scrollbar timeStepBar;

    Frame iFrame = null;

    private static CirSim theSim;

    private final CircuitDocument.SimulationStateListener stateListener = new CircuitDocument.SimulationStateListener() {
        @Override
        public void onSimulationStateChanged(boolean isRunning, String errorMessage) {
            if (toolbar != null) {
                toolbar.updateRunStopButton();
            }
        }
    };

    static native float devicePixelRatio() /*-{
        return window.devicePixelRatio;
    }-*/;

    void redrawCanvasSize() {
        layoutPanel.setWidgetSize(menuManager.menuBar, MENU_BAR_HEIGHT);
        if (MENU_BAR_HEIGHT < 30)
            menuManager.menuBar.addStyleName("modSmallMenuBar");
        else
            menuManager.menuBar.removeStyleName("modSmallMenuBar");
        setCanvasSize(0, 0);
        repaint();
    }

    native boolean isMobile(Element element) /*-{
        if (!element)
            return false;
        var style = getComputedStyle(element);
        return style.display != 'none';
    }-*/;

    public void setCanvasSize(int width, int height) {
        if (width == 0 || height == 0) {
            width = RootLayoutPanel.get().getOffsetWidth();
            height = RootLayoutPanel.get().getOffsetHeight();

            height = height - (getActiveDocument().circuitInfo.hideMenu ? 0 : MENU_BAR_HEIGHT);
            height -= TAB_BAR_HEIGHT;

            if (menuManager.toolbarCheckItem.getState()) {
                height -= TOOLBAR_HEIGHT;
            }
        }

        super.setCanvasSize(width, height);
    }

    native String decompress(String dump) /*-{
        return $wnd.LZString.decompressFromEncodedURIComponent(dump);
    }-*/;

    public static void executeJS(String js) {
        ScriptInjector.fromString(js)
                .setWindow(ScriptInjector.TOP_WINDOW)
                .inject();
    }

    public static native float getDefaultScale() /*-{
		try {
			var scrWidth = $wnd.screen.width;
			var defaultScale = 1.0;
			// TODO:
			return defaultScale;
		} catch (e) {
			console.warn('Failed to get screen info:', e);
			return 1.0; // Fallback for any errors
		}
	}-*/;

    public static native void setSidebarAnimation(String duration, String speedcurve) /*-{
		var triggerLabel = $doc.querySelector(".triggerLabel");
		var sidebar = $doc.querySelector(".trigger+.triggerLabel+div");
		// property name | duration | timing function | delay
		var split = " "+duration+"ms "+speedcurve;
		triggerLabel.style.transition = (duration=="none") ? duration : "right"+split;
		sidebar.style.transition = (duration=="none") ? duration : "width"+split;
	}-*/;

    void modSetDefault() {
        double MOD_UIScale = OptionsManager.getDoubleOptionFromStorage("MOD_UIScale", getDefaultScale());
        String MOD_TopMenuBar = OptionsManager.getOptionFromStorage("MOD_TopMenuBar", "standart");
        boolean MOD_setPauseWhenWinUnfocused = OptionsManager.getBoolOptionFromStorage("MOD_setPauseWhenWinUnfocused",
                false);

        executeJS("document.body.style.zoom = " + MOD_UIScale + ";");

        if (MOD_TopMenuBar == "small") {
            MENU_BAR_HEIGHT = 20;
            redrawCanvasSize();
        }
    }

    CirSim() {
        super();
        theSim = this;
    }

    @Override
    void bindDocument(CircuitDocument document) {
        if (getActiveDocument() != null && stateListener != null) {
            getActiveDocument().removeStateListener(stateListener);
        }
        super.bindDocument(document);
        if (getActiveDocument() != null && stateListener != null) {
            getActiveDocument().addStateListener(stateListener);
        }
        if (toolbar != null) {
            toolbar.updateRunStopButton();
        }
    }

    public void init() {
        console("Start");
        
        // Note: CircuitElementFactory.init() is called lazily on first JSON import/export
        // to avoid initialization order issues with element constructors

        // Ensure stateListener is attached (it might have been skipped in constructor)
        if (getActiveDocument() != null) {
            getActiveDocument().removeStateListener(stateListener);
            getActiveDocument().addStateListener(stateListener);
        }

        // sets the meta tag to allow the css media queries to work
        MetaElement meta = Document.get().createMetaElement();
        meta.setName("viewport");
        meta.setContent("width=device-width");
        NodeList<com.google.gwt.dom.client.Element> node = Document.get().getElementsByTagName("head");
        node.getItem(0).appendChild(meta);

        CircuitElm.initClass(this);

        CircuitInfo circuitInfo = getActiveDocument().circuitInfo;
        circuitInfo.loadQueryParameters();

        UndoManager undoManager = getActiveDocument().undoManager;
        // undoManager.readRecovery();
        // if (circuitInfo.startCircuitText == null && undoManager.recovery != null) {
        // circuitInfo.startCircuitText = undoManager.recovery;
        // }

        layoutPanel = new DockLayoutPanel(Unit.PX);

        slidersDialog = new SlidersDialog();
        controlsDialog = new ControlsDialog(this);
        controlsDialog.show();

        Element topPanelCheckbox = DOM.createInputCheck();
        Element topPanelCheckboxLabel = DOM.createLabel();
        topPanelCheckbox.setId("toptrigger");
        topPanelCheckbox.addClassName("toptrigger");
        topPanelCheckboxLabel.addClassName("toptriggerlabel");
        topPanelCheckboxLabel.setAttribute("for", "toptrigger");

        menuManager.initMainMenuBar();
        MenuBar menuBar = menuManager.menuBar;

        menuManager.loadShortcuts();

        DOM.appendChild(layoutPanel.getElement(), topPanelCheckbox);
        DOM.appendChild(layoutPanel.getElement(), topPanelCheckboxLabel);

        toolbar = new Toolbar(this);
        toolbar.setEuroResistors(circuitInfo.euroSetting);

        tabBarPanel = new TabBarPanel(documentManager);

        // Now that UI is initialized, restore the UI state from the active document
        getActiveDocument().restoreUIState(menuManager, this);

        // boolean sessionRestored = documentManager.restoreSession();

        if (!circuitInfo.hideMenu)
            layoutPanel.addNorth(menuBar, MENU_BAR_HEIGHT);

        // add toolbar immediately after menuBar
        layoutPanel.addNorth(toolbar, TOOLBAR_HEIGHT);
        layoutPanel.addNorth(tabBarPanel, TAB_BAR_HEIGHT);

        menuBar.getElement().insertFirst(menuBar.getElement().getChild(1));
        menuBar.getElement().getFirstChildElement().setAttribute("onclick",
                "document.getElementsByClassName('toptrigger')[0].checked = false");

        RootLayoutPanel.get().add(layoutPanel);

        Canvas cv = renderer.initCanvas();
        if (cv == null) {
            RootPanel.get().add(new Label("Not working. You need a browser that supports the CANVAS element."));
            return;
        }

        // Use delegating event handler that routes events to activeDocument.circuitEditor
        // This enables proper multi-tab support where each tab has its own editor
        CircuitEditorEventHandler eventHandler = new CircuitEditorEventHandler(this);
        cv.addMouseDownHandler(eventHandler);
        cv.addMouseMoveHandler(eventHandler);
        cv.addMouseOutHandler(eventHandler);
        cv.addMouseUpHandler(eventHandler);
        cv.addClickHandler(eventHandler);
        cv.addDoubleClickHandler(eventHandler);
        cv.addMouseWheelHandler(eventHandler);

        doTouchHandlers(this, cv.getCanvasElement());
        cv.addDomHandler(eventHandler, ContextMenuEvent.getType());

        modSetDefault();

        Window.addResizeHandler(new ResizeHandler() {
            public void onResize(ResizeEvent event) {
                repaint();
                updateControlsDialogPosition();
                updateSlidersDialogPosition();
                setSlidersDialogHeight();
            }
        });

        setToolbar(); // calls setCanvasSize()
        layoutPanel.add(cv);

        if (LoadFile.isSupported()) {
            controlsDialog.panel.add(loadFileInput);
            loadFileInput.setVisible(false);
        }

        getActiveDocument().circuitEditor.setGrid();

        menuManager.initElmMenuBar();

        setColors(circuitInfo.positiveColor, circuitInfo.negativeColor, circuitInfo.neutralColor,
                circuitInfo.selectColor, circuitInfo.currentColor);

        boolean sessionRestored = documentManager.restoreSession();

        if (!sessionRestored) {
            CircuitLoader circuitLoader = getActiveDocument().circuitLoader;
            if (circuitInfo.startCircuitText != null) {
                CircuitLoader.loadSetupList(this, false);
                circuitLoader.readCircuit(circuitInfo.startCircuitText);
                setUnsavedChanges(false);
            } else {
                if (getActiveDocument().simulator.stopMessage == null && circuitInfo.startCircuitLink != null) {
                    circuitLoader.readCircuit("");
                    CircuitLoader.loadSetupList(this, false);
                    // ImportFromDropboxDialog.setSim(this);
                    // ImportFromDropboxDialog.doImportDropboxLink(startCircuitLink, false);
                } else {
                    circuitLoader.readCircuit("");
                    if (getActiveDocument().simulator.stopMessage == null && circuitInfo.startCircuit != null) {
                        CircuitLoader.loadSetupList(this, false);
                        circuitLoader.readSetupFile(circuitInfo.startCircuit, circuitInfo.startLabel);
                    } else
                        CircuitLoader.loadSetupList(this, true);
                }
            }
        } else {
            CircuitLoader.loadSetupList(this, false);
        }

        if (circuitInfo.mouseModeReq != null)
            actionManager.menuPerformed("main", circuitInfo.mouseModeReq);

        enableUndoRedo();
        enablePaste();
        enableDisableMenuItems();

        menuBar.addDomHandler(event -> {
            menuManager.doMainMenuChecks();
        }, ClickEvent.getType());

        Event.addNativePreviewHandler(this);

        Window.addWindowClosingHandler(new Window.ClosingHandler() {
            public void onWindowClosing(ClosingEvent event) {
                undoManager.writeRecoveryToStorage();
                // there is a bug in electron that makes it impossible to close the app if this
                // warning is given
                if (circuitInfo.unsavedChanges && !isElectron())
                    event.setMessage(Locale.LS("Are you sure?  There are unsaved changes."));
            }
        });
        setupJSInterface();

        resetAction();
        setSimRunning(circuitInfo.running);
        toolbar.updateRunStopButton();
    }

    void setColors(String positiveColor, String negativeColor, String neutralColor, String selectColor,
            String currentColor) {
        if (positiveColor == null)
            positiveColor = OptionsManager.getOptionFromStorage("positiveColor", null);
        if (negativeColor == null)
            negativeColor = OptionsManager.getOptionFromStorage("negativeColor", null);
        if (neutralColor == null)
            neutralColor = OptionsManager.getOptionFromStorage("neutralColor", null);
        if (selectColor == null)
            selectColor = OptionsManager.getOptionFromStorage("selectColor", null);
        if (currentColor == null)
            currentColor = OptionsManager.getOptionFromStorage("currentColor", null);

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
    // don't feel like rewriting this in java. Anyway, java doesn't let us create
    // mouse
    // events and dispatch them.
    public native static void doTouchHandlers(CirSim sim, CanvasElement cv) /*-{
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

    public void setSlidersDialogHeight() {
        if (slidersDialog == null)
            return;
        int ih = RootLayoutPanel.get().getOffsetHeight() - 80;
        if (ih < 100)
            ih = 100;
        slidersDialog.setMaxHeight(ih);
    }

    public void setSimRunning(boolean isRunning) {
        super.setSimRunning(isRunning);
        toolbar.updateRunStopButton();
    }

    public Color getBackgroundColor() {
        if (menuManager.printableCheckItem.getState())
            return Color.white;
        return Color.black;
    }

    public static native void debugger() /*-{ debugger; }-*/;

    public static native void js_console(String text)
    /*-{
        console.log(text);
    }-*/;

    public static void console(String s) {
        if (theSim != null && theSim.getActiveDocument() != null) {
            theSim.getActiveDocument().logBuffer.log(s);
        }
        GWT.log(s);
    }

    public double getIterCount() {
        int speedValue = speedBar.getValue();
        if (speedValue == 0)
            return 0;

        return 0.1 * Math.exp((speedValue - 61) / 24.0);

    }

    public void setUnsavedChanges(boolean hasChanges) {
        super.setUnsavedChanges(hasChanges);
        changeWindowTitle(hasChanges);
        if (documentManager != null && getActiveDocument() != null) {
            documentManager.notifyTitleChanged(getActiveDocument());
        }
    }

    static native void changeWindowTitle(boolean isCircuitChanged)/*-{
		var newTitle = "CircuitJS1 Desktop Mod";
		var filename = @com.lushprojects.circuitjs1.client.CirSim::theSim.@com.lushprojects.circuitjs1.client.CirSim::getCircuitInfoFileName()();
		var changed = (isCircuitChanged) ? "*" : "";
		if (filename!=null) {
			$doc.title = changed+filename+" - "+newTitle;
		} else {
			$doc.title = newTitle;
		}
	}-*/;

    static native void nodeSave(String path, String dump) /*-{
		// Use standard download approach instead of nw.js file system
		try {
			var blob = new Blob([dump], {type: 'text/plain;charset=utf-8'});
			var url = $wnd.URL.createObjectURL(blob);
			var link = $doc.createElement('a');
			link.href = url;
			link.download = path.substring(path.lastIndexOf('/') + 1).substring(path.lastIndexOf('\\') + 1) || 'circuit.txt';
			link.style.display = 'none';
			$doc.body.appendChild(link);
			link.click();
			$doc.body.removeChild(link);
			$wnd.URL.revokeObjectURL(url);
			console.log("File download initiated!");
		} catch (e) {
			console.warn('Failed to download file:', e);
		}
    }-*/;

    static native void nodeSaveAs(String dump, String fileName) /*-{
		// Use standard download with file picker dialog fallback
		try {
			var blob = new Blob([dump], {type: 'text/plain;charset=utf-8'});
			var url = $wnd.URL.createObjectURL(blob);
			var link = $doc.createElement('a');
			link.href = url;
			link.download = fileName || 'circuit.txt';
			link.style.display = 'none';
			$doc.body.appendChild(link);
			link.click();
			$doc.body.removeChild(link);
			$wnd.URL.revokeObjectURL(url);

			// Update circuit info for browser environment
			@com.lushprojects.circuitjs1.client.CirSim::theSim.@com.lushprojects.circuitjs1.client.CirSim::setCircuitInfoFileName(Ljava/lang/String;)(fileName);
			@com.lushprojects.circuitjs1.client.CirSim::theSim.@com.lushprojects.circuitjs1.client.CirSim::setCircuitInfoLastFileName(Ljava/lang/String;)(fileName);

			if ($wnd.CircuitJS1 && $wnd.CircuitJS1.allowSave) {
				$wnd.CircuitJS1.allowSave(true);
			}
			@com.lushprojects.circuitjs1.client.CirSim::changeWindowTitle(Z)(false);
			console.log("File download completed: " + fileName);
		} catch (e) {
			console.warn('Failed to download file:', e);
		}
    }-*/;

    // JSInterface
    static void electronSaveAsCallback(String s) {
        s = s.substring(s.lastIndexOf('/') + 1);
        s = s.substring(s.lastIndexOf('\\') + 1);
        theSim.setCircuitTitle(s);
        theSim.allowSave(true);
        theSim.getActiveDocument().circuitInfo.savedFlag = true;
        theSim.repaint();
    }

    // JSInterface
    static void electronSaveCallback() {
        theSim.getActiveDocument().circuitInfo.savedFlag = true;
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

    // JSInterface
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
        if (documentManager != null && getActiveDocument() != null) {
            documentManager.notifyTitleChanged(getActiveDocument());
        }
    }

    static native void clipboardWriteImage(CanvasElement cv) /*-{
	cv.toBlob(function(blob) {
	    var promise = parent.navigator.clipboard.write([new ClipboardItem({ "image/png": blob })]);
	    promise.then(function(x) { console.log(x); });
	});
    }-*/;

    public void setPowerBarEnable() {
        if (menuManager.powerCheckItem.getState()) {
            powerLabel.setStyleName("disabled", false);
            powerBar.enable();
        } else {
            powerLabel.setStyleName("disabled", true);
            powerBar.disable();
        }
    }

    public void enableItems() {
    }

    void setToolbar() {
        layoutPanel.setWidgetHidden(toolbar, !menuManager.toolbarCheckItem.getState());
        setSlidersDialogHeight();
        updateControlsDialogPosition();
        setCanvasSize(0, 0);
    }

    public void onPreviewNativeEvent(NativePreviewEvent e) {
        actionManager.onPreviewNativeEvent(e);
    }

    void updateToolbar() {
        toolbar.highlightButton(getActiveDocument().circuitEditor.mouseModeStr);
    }

    void createNewLoadFile() {
        // This is a hack to fix what IMHO is a bug in the <INPUT FILE element
        // reloading the same file doesn't create a change event so importing the same
        // file twice
        // doesn't work unless you destroy the original input element and replace it
        // with a new one
        int idx = controlsDialog.panel.getWidgetIndex(loadFileInput);

        super.createNewLoadFile();

        changeWindowTitle(false);
        if (documentManager != null && getActiveDocument() != null) {
            documentManager.notifyTitleChanged(getActiveDocument());
        }
    }

    void updateSlidersDialogPosition() {
        if (slidersDialog == null || !slidersDialog.isShowing() || controlsDialog.isPositionRestored())
            return;
        int mainWidth = RootLayoutPanel.get().getOffsetWidth();
        int dialogWidth = slidersDialog.getOffsetWidth();
        int left = mainWidth - dialogWidth - 20;
        int top;
        if (controlsDialog != null && controlsDialog.isShowing())
            top = controlsDialog.getAbsoluteTop() + controlsDialog.getOffsetHeight();
        else
            top = 50;
        slidersDialog.setPopupPosition(left, top);
    }

    void updateControlsDialogPosition() {
        if (controlsDialog == null || !controlsDialog.isShowing() || controlsDialog.isPositionRestored())
            return;
        int mainWidth = RootLayoutPanel.get().getOffsetWidth();
        int dialogWidth = controlsDialog.getOffsetWidth();
        int left = mainWidth - dialogWidth - 20;
        controlsDialog.setPopupPosition(left, 80);
    }

    public void addWidgetToVerticalPanel(Widget w) {
        // This method is now deprecated for sliders.
        // Sliders should be added via addSliderToDialog.
        // For other widgets, it adds to the main vertical panel.
        if (iFrame != null) {
            int i = controlsDialog.panel.getWidgetIndex(iFrame);
            controlsDialog.panel.insert(w, i);
        } else {
            // Do nothing, as verticalPanel2 is removed.
        }
    }

    public void removeWidgetFromVerticalPanel(Widget w) {
        // This method is now deprecated for sliders.
        // Sliders should be removed via removeSliderFromDialog.
        controlsDialog.panel.remove(w);
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

    public static final int CAC_PRINT = 0;
    public static final int CAC_IMAGE = 1;
    public static final int CAC_SVG = 2;

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
        int node = LabeledNodeElm.getByName(name);
        if (node <= 0)
            return 0;
        // subtract one because ground is not included in nodeVoltages[]
        return getActiveDocument().simulator.getNodeVoltages(node - 1);
    }

    // JSInterface
    void setExtVoltage(String name, double v) {
        int i;
        for (i = 0; i != getActiveDocument().simulator.elmList.size(); i++) {
            CircuitElm ce = getActiveDocument().simulator.elmList.get(i);
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
        for (i = 0; i != getActiveDocument().simulator.elmList.size(); i++) {
            CircuitElm ce = getActiveDocument().simulator.elmList.get(i);
            ce.addJSMethods();
            arr.push(ce.getJavaScriptObject());
        }
        return arr;
    }

    // JSInterface
    void twoFingerTouch(int x, int y) {
        getActiveDocument().circuitEditor.twoFingerTouch(x, y);
    }

    // JSInterface
    void zoomCircuit(double dy) {
        renderer.zoomCircuit(dy);
    }

    // JSInterface
    double getTime() {
        return getActiveDocument().simulator.t;
    }

    // JSInterface
    double getTimeStep() {
        return getActiveDocument().simulator.timeStep;
    }

    // JSInterface
    void setTimeStep(double ts) {
        getActiveDocument().simulator.timeStep = ts;
    }

    // JSInterface
    double getMaxTimeStep() {
        return getActiveDocument().simulator.maxTimeStep;
    }

    // JSInterface
    void setMaxTimeStep(double ts) {
        getActiveDocument().simulator.maxTimeStep = ts;
        getActiveDocument().simulator.timeStep = ts;
    }

    // JSInterface - Extended API for JSON export/import
    String exportAsJson() {
        return actionManager.dumpCircuit("json");
    }

    // JSInterface - Export JSON with simulation state (voltages, currents, internal state)
    String exportAsJsonWithState() {
        return actionManager.dumpCircuitWithState("json");
    }

    // JSInterface - Import from JSON
    void importFromJson(String json) {
        importCircuitFromText(json, false);
    }

    // JSInterface - Get element by index
    JavaScriptObject getElementByIndex(int index) {
        CircuitSimulator simulator = getActiveDocument().simulator;
        if (index < 0 || index >= simulator.elmList.size()) {
            return null;
        }
        CircuitElm ce = simulator.elmList.get(index);
        ce.addJSMethods();
        return ce.getJavaScriptObject();
    }

    // JSInterface - Get element count
    int getElementCount() {
        return getActiveDocument().simulator.elmList.size();
    }

    // JSInterface - Clear circuit
    void clearCircuit() {
        CircuitDocument doc = getActiveDocument();
        CircuitSimulator simulator = doc.simulator;
        CircuitEditor circuitEditor = doc.circuitEditor;
        ScopeManager scopeManager = doc.scopeManager;
        
        // Clear mouse element reference first
        circuitEditor.clearMouseElm();
        
        // Delete all elements properly
        for (int i = 0; i < simulator.elmList.size(); i++) {
            CircuitElm element = simulator.elmList.get(i);
            element.delete();
        }
        
        // Clear the list
        simulator.elmList.clear();
        
        // Reset scope count
        scopeManager.setScopeCount(0);
        
        // Clear adjustables
        doc.adjustableManager.reset();
        
        // Reset simulation state
        simulator.t = simulator.timeStepAccum = 0;
        simulator.lastIterTime = 0;
        
        needAnalyze();
        // Force redraw
        repaint();
    }

    // JSInterface - Reset simulation
    void resetSimulation() {
        CircuitSimulator simulator = getActiveDocument().simulator;
        // Reset time but keep circuit intact
        simulator.t = simulator.timeStepAccum = 0;
        simulator.lastIterTime = 0;
        // Reset elements
        for (int i = 0; i < simulator.elmList.size(); i++) {
            CircuitElm ce = simulator.elmList.get(i);
            ce.reset();
        }
        needAnalyze();
    }

    // JSInterface - Step simulation once
    void stepSimulation() {
        CircuitDocument doc = getActiveDocument();
        CircuitSimulator simulator = doc.simulator;
        
        // Ensure we're stopped
        boolean wasRunning = doc.isRunning();
        if (wasRunning) {
            setSimRunning(false);
        }
        
        // Analyze if needed
        if (doc.circuitInfo.dcAnalysisFlag) {
            simulator.analyzeCircuit();
            doc.circuitInfo.dcAnalysisFlag = false;
        }
        
        // Stamp if needed
        if (simulator.needsStamp) {
            try {
                simulator.preStampAndStampCircuit();
            } catch (Exception e) {
                stop("Exception in stampCircuit()", null);
                return;
            }
        }
        
        // Force one iteration
        simulator.lastIterTime = System.currentTimeMillis() - 1000; // Force iteration
        simulator.runCircuit(true);
        renderer.repaint();
    }

    // JSInterface - Get simulation info
    JavaScriptObject getSimInfo() {
        CircuitSimulator simulator = getActiveDocument().simulator;
        return createSimInfoObject(
            simulator.t,
            simulator.timeStep,
            simulator.maxTimeStep,
            getActiveDocument().isRunning(),
            simulator.stopMessage != null ? simulator.stopMessage : "",
            simulator.elmList.size()
        );
    }

    private native JavaScriptObject createSimInfoObject(double time, double timeStep, double maxTimeStep, 
            boolean running, String stopMessage, int elementCount) /*-{
        return {
            time: time,
            timeStep: timeStep,
            maxTimeStep: maxTimeStep,
            running: running,
            stopMessage: stopMessage,
            elementCount: elementCount
        };
    }-*/;

    // JSInterface - Get scope count
    int getScopeCount() {
        return getActiveDocument().scopeManager.getScopeCount();
    }

    // JSInterface - Get scope info
    JavaScriptObject getScopeInfo(int index) {
        ScopeManager scopeManager = getActiveDocument().scopeManager;
        if (index < 0 || index >= scopeManager.getScopeCount()) {
            return null;
        }
        Scope scope = scopeManager.getScope(index);
        if (scope == null || scope.plots == null || scope.plots.isEmpty()) {
            return null;
        }
        
        ScopePlot plot = scope.plots.get(0);
        CircuitElm elm = plot.getElm();
        String elmType = elm != null ? elm.getClass().getSimpleName() : "";
        
        return createScopeInfoObject(
            index,
            elmType,
            scope.showV,
            scope.showI,
            scope.showFFT,
            scope.speed,
            scope.plots.size()
        );
    }

    private native JavaScriptObject createScopeInfoObject(int index, String elementType,
            boolean showVoltage, boolean showCurrent, boolean showFFT, int speed, int plotCount) /*-{
        return {
            index: index,
            elementType: elementType,
            showVoltage: showVoltage,
            showCurrent: showCurrent,
            showFFT: showFFT,
            speed: speed,
            plotCount: plotCount
        };
    }-*/;

    // JSInterface - Get scope data
    JavaScriptObject getScopeData(int scopeIndex, int plotIndex) {
        ScopeManager scopeManager = getActiveDocument().scopeManager;
        if (scopeIndex < 0 || scopeIndex >= scopeManager.getScopeCount()) {
            return null;
        }
        Scope scope = scopeManager.getScope(scopeIndex);
        if (scope == null || scope.plots == null || plotIndex < 0 || plotIndex >= scope.plots.size()) {
            return null;
        }
        
        ScopePlot plot = scope.plots.get(plotIndex);
        if (plot.minValues == null || plot.maxValues == null) {
            return null;
        }
        
        // Convert arrays to JS arrays
        return createScopeDataObject(plot.minValues, plot.maxValues, plot.ptr, plot.scopePointCount, plot.units);
    }

    private native JavaScriptObject createScopeDataObject(double[] minValues, double[] maxValues, 
            int ptr, int pointCount, int units) /*-{
        return {
            minValues: minValues,
            maxValues: maxValues,
            ptr: ptr,
            pointCount: pointCount,
            units: units
        };
    }-*/;

    // JSInterface - Delete element by index
    boolean deleteElementByIndex(int index) {
        CircuitSimulator simulator = getActiveDocument().simulator;
        if (index < 0 || index >= simulator.elmList.size()) {
            return false;
        }
        CircuitElm ce = simulator.elmList.get(index);
        simulator.elmList.remove(ce);
        ce.delete();
        needAnalyze();
        return true;
    }

    // JSInterface
    String getCircuitInfoFileName() {
        return getActiveDocument().circuitInfo.fileName;
    }

    // JSInterface
    void setCircuitInfoFileName(String fileName) {
        getActiveDocument().circuitInfo.fileName = fileName;
        if (documentManager != null && getActiveDocument() != null) {
            documentManager.notifyTitleChanged(getActiveDocument());
        }
    }

    // JSInterface
    void setCircuitInfoLastFileName(String fileName) {
        getActiveDocument().circuitInfo.lastFileName = fileName;
    }

    // JSInterface - Get log entries
    JsArrayString getLogs() {
        JsArrayString arr = (JsArrayString) JsArrayString.createArray();
        for (String entry : logManager.logEntries) {
            arr.push(entry);
        }
        return arr;
    }

    // JSInterface - Get last N log entries
    JsArrayString getLastLogs(int count) {
        JsArrayString arr = (JsArrayString) JsArrayString.createArray();
        int start = Math.max(0, logManager.logEntries.size() - count);
        for (int i = start; i < logManager.logEntries.size(); i++) {
            arr.push(logManager.logEntries.get(i));
        }
        return arr;
    }

    // JSInterface - Get log count
    int getLogCount() {
        return logManager.logEntries.size();
    }

    // JSInterface - Add log entry
    void addLog(String message) {
        logManager.addLogEntry(message);
    }

    // JSInterface - Clear logs
    void clearLogs() {
        logManager.logEntries.clear();
    }

    native void setupJSInterface() /*-{
	    var that = this;
	    $wnd.CircuitJS1 = {
	        // Simulation control
	        setSimRunning: $entry(function(run) { that.@com.lushprojects.circuitjs1.client.CirSim::setSimRunning(Z)(run); } ),
	        isRunning: $entry(function() { return that.@com.lushprojects.circuitjs1.client.CirSim::simIsRunning()(); } ),
	        getTime: $entry(function() { return that.@com.lushprojects.circuitjs1.client.CirSim::getTime()(); } ),
	        getTimeStep: $entry(function() { return that.@com.lushprojects.circuitjs1.client.CirSim::getTimeStep()(); } ),
	        setTimeStep: $entry(function(ts) { that.@com.lushprojects.circuitjs1.client.CirSim::setTimeStep(D)(ts); } ),
	        getMaxTimeStep: $entry(function() { return that.@com.lushprojects.circuitjs1.client.CirSim::getMaxTimeStep()(); } ),
	        setMaxTimeStep: $entry(function(ts) { that.@com.lushprojects.circuitjs1.client.CirSim::setMaxTimeStep(D)(ts); } ),
	        resetSimulation: $entry(function() { that.@com.lushprojects.circuitjs1.client.CirSim::resetSimulation()(); } ),
	        stepSimulation: $entry(function() { that.@com.lushprojects.circuitjs1.client.CirSim::stepSimulation()(); } ),
	        getSimInfo: $entry(function() { return that.@com.lushprojects.circuitjs1.client.CirSim::getSimInfo()(); } ),
	        
	        // Node and voltage access
	        getNodeVoltage: $entry(function(n) { return that.@com.lushprojects.circuitjs1.client.CirSim::getLabeledNodeVoltage(Ljava/lang/String;)(n); } ),
	        setExtVoltage: $entry(function(n, v) { that.@com.lushprojects.circuitjs1.client.CirSim::setExtVoltage(Ljava/lang/String;D)(n, v); } ),
	        
	        // Element access
	        getElements: $entry(function() { return that.@com.lushprojects.circuitjs1.client.CirSim::getJSElements()(); } ),
	        getElementCount: $entry(function() { return that.@com.lushprojects.circuitjs1.client.CirSim::getElementCount()(); } ),
	        getElementByIndex: $entry(function(i) { return that.@com.lushprojects.circuitjs1.client.CirSim::getElementByIndex(I)(i); } ),
	        deleteElementByIndex: $entry(function(i) { return that.@com.lushprojects.circuitjs1.client.CirSim::deleteElementByIndex(I)(i); } ),
	        
	        // Circuit export/import - Text format
	        exportCircuit: $entry(function() { return that.@com.lushprojects.circuitjs1.client.CirSim::dumpCircuit()(); } ),
	        importCircuit: $entry(function(circuit, subcircuitsOnly) { return that.@com.lushprojects.circuitjs1.client.CirSim::importCircuitFromText(Ljava/lang/String;Z)(circuit, subcircuitsOnly); }),
	        
	        // Circuit export/import - JSON format
	        exportAsJson: $entry(function() { return that.@com.lushprojects.circuitjs1.client.CirSim::exportAsJson()(); } ),
	        exportAsJsonWithState: $entry(function() { return that.@com.lushprojects.circuitjs1.client.CirSim::exportAsJsonWithState()(); } ),
	        importFromJson: $entry(function(json) { that.@com.lushprojects.circuitjs1.client.CirSim::importFromJson(Ljava/lang/String;)(json); } ),
	        
	        // Circuit management
	        clearCircuit: $entry(function() { that.@com.lushprojects.circuitjs1.client.CirSim::clearCircuit()(); } ),
	        
	        // SVG export
	        getCircuitAsSVG: $entry(function() { return that.@com.lushprojects.circuitjs1.client.CirSim::doExportAsSVGFromAPI()(); } ),
	        
	        // Scope access
	        getScopeCount: $entry(function() { return that.@com.lushprojects.circuitjs1.client.CirSim::getScopeCount()(); } ),
	        getScopeInfo: $entry(function(i) { return that.@com.lushprojects.circuitjs1.client.CirSim::getScopeInfo(I)(i); } ),
	        getScopeData: $entry(function(scopeIdx, plotIdx) { return that.@com.lushprojects.circuitjs1.client.CirSim::getScopeData(II)(scopeIdx, plotIdx); } ),
	        
	        // Logging
	        getLogs: $entry(function() { return that.@com.lushprojects.circuitjs1.client.CirSim::getLogs()(); } ),
	        getLastLogs: $entry(function(count) { return that.@com.lushprojects.circuitjs1.client.CirSim::getLastLogs(I)(count); } ),
	        getLogCount: $entry(function() { return that.@com.lushprojects.circuitjs1.client.CirSim::getLogCount()(); } ),
	        addLog: $entry(function(msg) { that.@com.lushprojects.circuitjs1.client.CirSim::addLog(Ljava/lang/String;)(msg); } ),
	        clearLogs: $entry(function() { that.@com.lushprojects.circuitjs1.client.CirSim::clearLogs()(); } ),
	        
	        // Canvas
			redrawCanvasSize: $entry(function() { return that.@com.lushprojects.circuitjs1.client.CirSim::redrawCanvasSize()(); } ),
			
			// Permissions
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

}