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
import com.lushprojects.circuitjs1.client.util.Locale;

public class CircuitLoader extends BaseCirSimDelegate implements CircuitConst {

    protected CircuitLoader(CirSim cirSim) {
        super(cirSim);
    }

    void readCircuit(byte[] b, int flags) {
        CircuitSimulator simulator = simulator();
        CircuitEditor circuitEditor = circuitEditor();
        MenuManager menuManager = menuManager();
        ScopeManager scopeManager = scopeManager();

        int len = b.length;
        if ((flags & RC_RETAIN) == 0) {
            circuitEditor.clearMouseElm();
            for (int i = 0; i != simulator.elmList.size(); i++) {
                CircuitElm ce = simulator.elmList.get(i);
                ce.delete();
            }
            simulator.t = simulator.timeStepAccum = 0;
            simulator.elmList.removeAllElements();
            cirSim.renderer.hintType = -1;
            simulator.maxTimeStep = 5e-6;
            simulator.minTimeStep = 50e-12;
            menuManager.dotsCheckItem.setState(false);
            menuManager.smallGridCheckItem.setState(false);
            menuManager.powerCheckItem.setState(false);
            menuManager.voltsCheckItem.setState(true);
            menuManager.showValuesCheckItem.setState(true);
            circuitEditor.setGrid();
            cirSim.speedBar.setValue(117); // 57
            cirSim.currentBar.setValue(50);
            cirSim.powerBar.setValue(50);
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
                        Scope sc = new Scope(cirSim);
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
                        cirSim.adjustableManager.addAdjustable(st);
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
                    CirSim.console("exception while undumping " + ee);
                    break;
                }
                break;
            }
            p += l;

        }
        cirSim.setPowerBarEnable();
        cirSim.enableItems();
        if ((flags & RC_RETAIN) == 0) {
            // create sliders as needed
            cirSim.adjustableManager.createSliders();
        }
//	if (!retain)
        //    handleResize(); // for scopes
        cirSim.needAnalyze();
        if ((flags & RC_NO_CENTER) == 0)
            renderer().centreCircuit();
        if ((flags & RC_SUBCIRCUITS) != 0)
            simulator.updateModels();

        AudioInputElm.clearCache();  // to save memory
        DataInputElm.clearCache();  // to save memory
    }

    void readHint(StringTokenizer st) {
        cirSim.renderer.hintType = new Integer(st.nextToken()).intValue();
        cirSim.renderer.hintItem1 = new Integer(st.nextToken()).intValue();
        cirSim.renderer.hintItem2 = new Integer(st.nextToken()).intValue();
    }

    void readOptions(StringTokenizer st, int importFlags) {
        CircuitSimulator simulator = simulator();
        CircuitEditor circuitEditor = circuitEditor();
        MenuManager menuManager = menuManager();

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
        cirSim.speedBar.setValue(sp2);
        cirSim.currentBar.setValue(new Integer(st.nextToken()).intValue());
        CircuitElm.voltageRange = new Double(st.nextToken()).doubleValue();

        try {
            cirSim.powerBar.setValue(new Integer(st.nextToken()).intValue());
            simulator.minTimeStep = Double.parseDouble(st.nextToken());
        } catch (Exception e) {
        }
        circuitEditor.setGrid();
    }

    void readCircuit(String text, int flags) {
        readCircuit(text.getBytes(), flags);
        if ((flags & RC_KEEP_TITLE) == 0)
            cirSim.titleLabel.setText(null);
        cirSim.setSlidersPanelHeight();
    }

    void readCircuit(String text) {
        readCircuit(text.getBytes(), 0);
        cirSim.titleLabel.setText(null);
        cirSim.setSlidersPanelHeight();
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

    void processSetupList(byte[] b, boolean openDefault) {
        MenuBar circuitsMenuBar = menuManager().circuitsMenuBar;

        int len = b.length;
        MenuBar[] stack = new MenuBar[6];
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
                    CircuitInfo circuitInfo = circuitInfo();
                    if (file.equals(circuitInfo.startCircuit) && circuitInfo.startLabel == null) {
                        circuitInfo.startLabel = title;
                        cirSim.titleLabel.setText(title);
                        cirSim.setSlidersPanelHeight();
                    }
                    if (first && circuitInfo.startCircuit == null) {
                        circuitInfo.startCircuit = file;
                        circuitInfo.startLabel = title;
                        if (openDefault && simulator().stopMessage == null)
                            readSetupFile(circuitInfo.startCircuit, circuitInfo.startLabel);
                    }
                }
            }
            p += l;
        }
    }

    void readSetupFile(String str, String title) {
        System.out.println(str);
        // don't avoid caching here, it's unnecessary and makes offline PWA's not work
        String url = GWT.getModuleBaseURL() + "circuits/" + str; // +"?v="+random.nextInt();
        loadFileFromURL(url);
        if (title != null)
            cirSim.titleLabel.setText(title);
        cirSim.setSlidersPanelHeight();
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
                    if (response.getStatusCode() == Response.SC_OK) {
                        String text = response.getText();
                        readCircuit(text, CircuitConst.RC_KEEP_TITLE);
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
