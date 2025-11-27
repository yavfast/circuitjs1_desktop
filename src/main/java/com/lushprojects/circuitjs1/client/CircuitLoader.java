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
import com.lushprojects.circuitjs1.client.io.CircuitFormat;
import com.lushprojects.circuitjs1.client.io.CircuitFormatRegistry;
import com.lushprojects.circuitjs1.client.io.CircuitImporter;
import com.lushprojects.circuitjs1.client.util.Locale;

public class CircuitLoader extends BaseCirSimDelegate implements CircuitConst {

    protected CircuitLoader(BaseCirSim cirSim, CircuitDocument circuitDocument) {
        super(cirSim, circuitDocument);
    }

    /**
     * Read circuit from data string with specified flags.
     * Auto-detects the format and delegates to appropriate importer.
     * 
     * @param circuitData Raw circuit data (text or JSON)
     * @param flags Import flags (RC_RETAIN, RC_SUBCIRCUITS, etc.)
     */
    public void readCircuit(String circuitData, int flags) {
        if (circuitData == null || circuitData.isEmpty()) {
            return;
        }

        // Auto-detect format or use default
        CircuitFormat format = CircuitFormatRegistry.detectFormatOrDefault(circuitData);
        CircuitImporter importer = format.createImporter();
        
        // Delegate to importer
        importer.importCircuit(circuitData, getActiveDocument(), flags);
    }

    /**
     * Read circuit from data string with specified format.
     * 
     * @param circuitData Raw circuit data
     * @param formatId Format identifier (e.g., "text", "json")
     * @param flags Import flags
     */
    public void readCircuit(String circuitData, String formatId, int flags) {
        if (circuitData == null || circuitData.isEmpty()) {
            return;
        }

        CircuitFormat format = CircuitFormatRegistry.getById(formatId);
        if (format == null) {
            format = CircuitFormatRegistry.getDefault();
        }
        
        CircuitImporter importer = format.createImporter();
        importer.importCircuit(circuitData, getActiveDocument(), flags);
    }

    /**
     * Public convenience method that calls readCircuit with default flags.
     */
    public void readCircuit(String text) {
        readCircuit(text, 0);
    }

    // ========== Static methods for loading setup list ==========

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
