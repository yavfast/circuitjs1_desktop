package com.lushprojects.circuitjs1.client;

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
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.Window;
import com.lushprojects.circuitjs1.client.dialog.Dialog;
import com.lushprojects.circuitjs1.client.dialog.ExportAsLocalFileDialog;
import com.lushprojects.circuitjs1.client.dialog.ScrollValuePopup;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.ScopeElm;
import com.lushprojects.circuitjs1.client.util.Locale;

public class ActionManager extends BaseCirSimDelegate {

    protected ActionManager(CirSim cirSim) {
        super(cirSim);
    }

    public void onPreviewNativeEvent(Event.NativePreviewEvent e) {
        int cc = e.getNativeEvent().getCharCode();
        int t = e.getTypeInt();
        int code = e.getNativeEvent().getKeyCode();

        if (cirSim.dialogIsShowing()) {
            ScrollValuePopup scrollValuePopup = circuitEditor().scrollValuePopup;
            if (scrollValuePopup != null && scrollValuePopup.isShowing() &&
                    (t & Event.ONKEYDOWN) != 0) {
                if (code == KEY_ESCAPE || code == KEY_SPACE)
                    scrollValuePopup.close(false);
                if (code == KEY_ENTER)
                    scrollValuePopup.close(true);
            }

            // process escape/enter for dialogs
            // multiple edit dialogs could be displayed at once, pick the one in front
            Dialog dlg = dialogManager().getShowingDialog();
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
            if (cc == '/' && menuManager().shortcuts['/'] == null) {
                menuPerformed("key", "search");
                e.cancel();
            }
        }

        // all other shortcuts are ignored when editing disabled
        if (menuManager().noEditCheckItem.getState())
            return;

        if ((t & Event.ONKEYDOWN) != 0) {
            if (code == KEY_BACKSPACE || code == KEY_DELETE) {
                ScopeManager scopeManager = scopeManager();
                if (scopeManager.scopeSelected != -1) {
                    // Treat DELETE key with scope selected as "remove scope", not delete
                    scopeManager.scopes[scopeManager.scopeSelected].setElm(null);
                    scopeManager.scopeSelected = -1;
                } else {
                    circuitEditor().menuElm = null;
                    circuitEditor().pushUndo();
                    circuitEditor().doDelete(true);
                    e.cancel();
                }
            }
            if (code == KEY_ESCAPE) {
                circuitEditor().setMouseMode("Select");
                cirSim.updateToolbar();
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
                    String cmd = (cirSim.circuitInfo.filePath != null) ? "save" : "saveas";
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
                String c = menuManager().shortcuts[cc];
                e.cancel();
                if (c == null)
                    return;
                circuitEditor().setMouseMode(c);
                cirSim.updateToolbar();
            }
            if (cc == 32) {
                circuitEditor().setMouseMode("Select");
                cirSim.updateToolbar();
                e.cancel();
            }
        }
    }

    public void menuPerformed(String menu, String item) {
        MenuManager menuManager = menuManager();
        DialogManager dialogManager = dialogManager();
        CircuitEditor circuitEditor = circuitEditor();
        ScopeManager scopeManager = scopeManager();

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
            cirSim.loadFileInput.click();
        }
        if (item == "newwindow") {
            //Window.open(Document.get().getURL(), "_blank", "");
            //Maybe this can help with lags:
            CirSim.executeJS("nw.Window.open('circuitjs.html', {new_instance: true, mixed_context: false});");
        }
        if (item == "save") {
            if (cirSim.circuitInfo.filePath != null)
                CirSim.nodeSave(cirSim.circuitInfo.filePath, dumpCircuit());
            else
                CirSim.nodeSaveAs(dumpCircuit(), cirSim.getLastFileName());
            cirSim.setUnsavedChanges(false);
        }

        if (item == "saveas") {
            CirSim.nodeSaveAs(dumpCircuit(), cirSim.getLastFileName());
            cirSim.setUnsavedChanges(false);
        }

        if (item == "importfromtext") {
            dialogManager.showImportFromTextDialog();
        }
    	/*if (item=="importfromdropbox") {
    		dialogShowing = new ImportFromDropboxDialog(this);
    	}*/
        if (item == "exportasurl") {
            doExportAsUrl();
            cirSim.setUnsavedChanges(false);
        }
    	/*if (item=="exportaslocalfile") {
    		doExportAsLocalFile();
    		unsavedChanges = false;
    	}*/
        if (item == "exportastext") {
            doExportAsText();
            cirSim.setUnsavedChanges(false);
        }
        if (item == "exportasimage")
            doExportAsImage();
        if (item == "copypng") {
            doImageToClipboard();
            if (menuManager.contextPanel != null)
                menuManager.contextPanel.hide();
        }
        if (item == "exportassvg")
            cirSim.doExportAsSVG();
        if (item == "createsubcircuit")
            doCreateSubcircuit();
        if (item == "dcanalysis")
            cirSim.doDCAnalysis();
        if (item == "print")
            cirSim.doPrint();
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
            circuitEditor.doEditOptions();
        if (item == "devtools")
            CirSim.toggleDevTools();
        if (item == "undo")
            circuitEditor.doUndo();
        if (item == "redo")
            circuitEditor.doRedo();

        // if the mouse is hovering over an element, and a shortcut key is pressed, operate on that element (treat it like a context menu item selection)
        if (menu == "key" && circuitEditor.mouseElm != null) {
            circuitEditor.menuElm = circuitEditor.mouseElm;
            menu = "elm";
        }
        if (menu != "elm")
            circuitEditor.menuElm = null;

        if (item == "cut") {
            circuitEditor.doCut();
        }
        if (item == "copy") {
            circuitEditor.doCopy();
        }
        if (item == "paste") {
            // Try to paste from system clipboard first, then fallback to internal
            circuitEditor.cirSim.clipboardManager.doPasteFromSystem();
        }
        if (item == "duplicate") {
            circuitEditor.doDuplicate();
        }
        if (item == "flip")
            circuitEditor.doFlip();
        if (item == "split")
            circuitEditor.doSplit(circuitEditor.menuElm);
        if (item == "selectAll")
            circuitEditor.doSelectAll();

        if (item == "centrecircuit") {
            circuitEditor.pushUndo();
            renderer().centreCircuit();
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
            scopeManager().stackAll();
        if (item == "unstackAll")
            scopeManager().unstackAll();
        if (item == "combineAll")
            scopeManager().combineAll();
        if (item == "separateAll")
            scopeManager().separateAll();
        if (item == "zoomin")
            renderer().zoomCircuit(20, true);
        if (item == "zoomout")
            renderer().zoomCircuit(-20, true);
        if (item == "zoom100")
            renderer().setCircuitScale(1, true);
        if (menu == "elm" && item == "edit")
            circuitEditor.doEditElementOptions(circuitEditor().menuElm);
        if (item == "delete") {
            if (menu != "elm")
                circuitEditor().menuElm = null;
            circuitEditor.pushUndo();
            circuitEditor.doDelete(true);
        }
        if (item == "sliders")
            circuitEditor.doSliders(circuitEditor().menuElm);

        if (item == "viewInScope" && circuitEditor().menuElm != null) {
            scopeManager().addScope(circuitEditor().menuElm);
        }

        if (item == "viewInFloatScope" && circuitEditor().menuElm != null) {
            ScopeElm newScope = new ScopeElm(circuitEditor.snapGrid(circuitEditor().menuElm.x + 50), circuitEditor.snapGrid(circuitEditor().menuElm.y + 50));
            simulator().elmList.addElement(newScope);
            newScope.setScopeElm(circuitEditor().menuElm);

            // need to rebuild scopeElmArr
            cirSim.needAnalyze();
        }

        if (item.startsWith("addToScope") && circuitEditor().menuElm != null) {
            int n = Integer.parseInt(item.substring(10));
            scopeManager().addToScope(n, circuitEditor().menuElm);
            scopeManager().scopeMenuSelected = -1;
        }

        if (menu == "scopepop") {
            circuitEditor.pushUndo();
            Scope s;
            if (scopeManager().menuScope != -1)
                s = scopeManager().scopes[scopeManager().menuScope];
            else
                s = ((ScopeElm) circuitEditor.mouseElm).elmScope;

            if (item == "dock") {
                scopeManager().dockScope(circuitEditor.mouseElm);
                circuitEditor.doDelete(false);
            }
            if (item == "undock") {
                CircuitElm elm = s.getElm();
                ScopeElm newScope = new ScopeElm(circuitEditor.snapGrid(elm.x + 50), circuitEditor.snapGrid(elm.y + 50));
                scopeManager().undockScope(newScope);

                cirSim.needAnalyze();      // need to rebuild scopeElmArr
            }
            if (item == "remove")
                s.setElm(null);  // setupScopes() will clean this up
            if (item == "removeplot")
                s.removePlot(menuManager.menuPlot);
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

            simulator().deleteUnusedScopeElms();
        }
        if (menu == "circuits" && item.indexOf("setup ") == 0) {
            circuitEditor.pushUndo();
            int sp = item.indexOf(' ', 6);
            cirSim.circuitLoader.readSetupFile(item.substring(6, sp), item.substring(sp + 1));
        }
        if (item == "newblankcircuit") {
            circuitEditor.pushUndo();
            cirSim.circuitLoader.readSetupFile("blank.txt", "Blank Circuit");
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

            cirSim.updateToolbar();

        }
        if (item == "fullscreen") {
            if (!Graphics.isFullScreen) {
                Graphics.viewFullScreen();
                cirSim.setSlidersPanelHeight();
            } else {
                Graphics.exitFullScreen();
                renderer().centreCircuit();
                cirSim.setSlidersPanelHeight();
            }
        }

        cirSim.repaint();
    }

    void doExportAsUrl() {
        String dump = dumpCircuit();
        dialogManager().showExportAsUrlDialog(dump);
    }

    void doExportAsText() {
        String dump = dumpCircuit();
        dialogManager().showExportAsTextDialog(dump);
    }

    void doExportAsImage() {
        dialogManager().showExportAsImageDialog(CirSim.CAC_IMAGE);
    }

    void doImageToClipboard() {
        Canvas cv = renderer().getCircuitAsCanvas(CirSim.CAC_IMAGE);
        CirSim.clipboardWriteImage(cv.getCanvasElement());
    }

    void doCreateSubcircuit() {
        cirSim.dialogManager.showEditCompositeModelDialog(null);
    }

    void doExportAsLocalFile() {
    	String dump = dumpCircuit();
    	Dialog dialogShowing = new ExportAsLocalFileDialog(dump);
    	dialogShowing.show();
    }

    public void importCircuitFromText(String circuitText, boolean subcircuitsOnly) {
        int flags = subcircuitsOnly ? (CircuitConst.RC_SUBCIRCUITS | CircuitConst.RC_RETAIN) : 0;
        if (circuitText != null) {
            cirSim.circuitLoader.readCircuit(circuitText, flags);
            cirSim.allowSave(false);
            cirSim.circuitInfo.filePath = null;
            cirSim.circuitInfo.fileName = null;
            CirSim.changeWindowTitle(false);
        }
    }

    String dumpOptions() {
        MenuManager menuManager = menuManager();
        CircuitSimulator simulator = simulator();
        int f = (menuManager.dotsCheckItem.getState()) ? 1 : 0;
        f |= (menuManager.smallGridCheckItem.getState()) ? 2 : 0;
        f |= (menuManager.voltsCheckItem.getState()) ? 0 : 4;
        f |= (menuManager.powerCheckItem.getState()) ? 8 : 0;
        f |= (menuManager.showValuesCheckItem.getState()) ? 0 : 16;
        // 32 = linear scale in afilter
        f |= simulator.adjustTimeStep ? 64 : 0;
        String dump = "$ " + f + " " +
                simulator.maxTimeStep + " " + cirSim.getIterCount() + " " +
                cirSim.currentBar.getValue() + " " + CircuitElm.voltageRange + " " +
                cirSim.powerBar.getValue() + " " + simulator.minTimeStep + "\n";
        return dump;
    }

    public String dumpCircuit() {
        int i;
        CustomLogicModel.clearDumpedFlags();
        CustomCompositeModel.clearDumpedFlags();
        DiodeModel.clearDumpedFlags();
        TransistorModel.clearDumpedFlags();

        String dump = dumpOptions();

        CircuitSimulator simulator = simulator();
        for (i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm ce = simulator.elmList.get(i);
            String m = ce.dumpModel();
            if (m != null && !m.isEmpty())
                dump += m + "\n";
            dump += ce.dump() + "\n";
        }
        ScopeManager scopeManager = scopeManager();
        for (i = 0; i != scopeManager.scopeCount; i++) {
            String d = scopeManager.scopes[i].dump();
            if (d != null)
                dump += d + "\n";
        }
        dump += cirSim.adjustableManager.dump();

        if (renderer().hintType != -1)
            dump += "h " + renderer().hintType + " " + renderer().hintItem1 + " " +
                    renderer().hintItem2 + "\n";
        return dump;
    }




}
