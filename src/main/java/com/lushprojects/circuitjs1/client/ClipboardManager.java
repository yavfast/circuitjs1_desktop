package com.lushprojects.circuitjs1.client;

import com.google.gwt.storage.client.Storage;

public class ClipboardManager extends BaseCirSimDelegate {

    String clipboard;

    protected ClipboardManager(CirSim cirSim) {
        super(cirSim);
    }

    void writeClipboardToStorage() {
        Storage stor = Storage.getLocalStorageIfSupported();
        if (stor == null)
            return;
        stor.setItem("circuitClipboard", clipboard);
    }

    void readClipboardFromStorage() {
        Storage stor = Storage.getLocalStorageIfSupported();
        if (stor == null)
            return;
        clipboard = stor.getItem("circuitClipboard");
    }

    void doCut() {
        String clipData = "";
        CircuitSimulator simulator = simulator();
        for (int i = simulator.elmList.size() - 1; i >= 0; i--) {
            CircuitElm ce = simulator.elmList.get(i);
            // ScopeElms don't cut-paste well because their reference to a parent
            // elm by number get's messed up in the dump. For now we will just ignore them
            // until I can be bothered to come up with something better
            if (circuitEditor().willDelete(ce) && !(ce instanceof ScopeElm)) {
                clipData += ce.dump() + "\n";
            }
        }
        clipboard = clipData;
        writeClipboardToStorage();
    }

    void doCopy() {
        clipboard = circuitEditor().copyOfSelectedElms();
        writeClipboardToStorage();
    }

    boolean hasClipboardData() {
        if (clipboard == null || clipboard.isEmpty()) {
            readClipboardFromStorage();
        }
        return clipboard != null && !clipboard.isEmpty();
    }

    String getClipboard() {
        return hasClipboardData() ? clipboard : null;
    }

}
