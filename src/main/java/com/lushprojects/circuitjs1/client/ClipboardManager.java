package com.lushprojects.circuitjs1.client;

import com.lushprojects.circuitjs1.client.io.CircuitContentTest;

/**
 * Manages clipboard operations with system clipboard integration
 * Provides copy/paste functionality for circuit elements
 */
public class ClipboardManager {

    private final BaseCirSim cirSim;
    private String internalClipboard = "";
    private boolean hasSystemClipboardSupport = false;

    public ClipboardManager(BaseCirSim cirSim) {
        this.cirSim = cirSim;
        this.hasSystemClipboardSupport = checkClipboardSupport();
    }

    /**
     * Check if browser supports modern Clipboard API
     */
    private native boolean checkClipboardSupport() /*-{
        // the page window's clipboard ($wnd): the module frame's document never has the focus, and
        // the Clipboard API refuses to read for an unfocused document
        var nav = $wnd.navigator;
        return !!(nav.clipboard && nav.clipboard.writeText && nav.clipboard.readText);
    }-*/;

    /**
     * Copy selected elements to clipboard
     */
    public void doCopy() {
        String circuitData = cirSim.getActiveDocument().circuitEditor.copyOfSelectedElms();
        setClipboard(circuitData);
    }

    /**
     * Cut selected elements to clipboard
     */
    public void doCut() {
        String circuitData = cirSim.getActiveDocument().circuitEditor.copyOfSelectedElms();
        setClipboard(circuitData);
    }

    /**
     * Set data to clipboard (both system and internal)
     */
    public void setClipboard(String data) {
        if (data == null || data.isEmpty()) {
            return;
        }

        // Always store in internal clipboard as fallback
        internalClipboard = data;

        // Try to write to system clipboard
        CirSim.console("hasSystemClipboardSupport: " + hasSystemClipboardSupport);
        if (hasSystemClipboardSupport) {
            writeToSystemClipboard(data);
        } else {
            // Fallback: try legacy methods
            tryLegacyClipboardWrite(data);
        }
    }

    /**
     * Copies a plain text (for example a command line shown in a dialog) to the system clipboard
     * only; the internal circuit clipboard used by Paste is left unchanged. Call from a user
     * gesture (a button click): the synchronous copy command is tried first, the asynchronous
     * Clipboard API second.
     */
    public void copyText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        copyTextToSystemClipboard(text);
    }

    // the page document ($doc), where the dialogs live, not the module frame's document
    private static native boolean copyTextToSystemClipboard(String text) /*-{
        var d = $doc;
        var ta = d.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.left = '-999999px';
        ta.style.top = '-999999px';
        d.body.appendChild(ta);
        var ok = false;
        try {
            ta.focus();
            ta.select();
            ok = d.execCommand('copy');
        } finally {
            // the off-screen textarea never stays, also when the copy command throws
            d.body.removeChild(ta);
        }
        var nav = $wnd.navigator;
        if (!ok && nav.clipboard && nav.clipboard.writeText) {
            nav.clipboard.writeText(text)['catch'](function(err) {
                console.error('Failed to copy to system clipboard: ', err);
            });
        }
        return ok;
    }-*/;

    /**
     * Get data from clipboard (internal only for synchronous access)
     */
    public String getClipboard() {
        return internalClipboard;
    }

    /**
     * Try to read from system clipboard asynchronously
     */
    public void readFromSystemClipboard(ClipboardCallback callback) {
        if (hasSystemClipboardSupport) {
            readFromSystemClipboardAsync(callback);
        } else {
            // Fallback to internal clipboard
            if (callback != null) {
                callback.onSuccess(internalClipboard);
            }
        }
    }

    /**
     * Enhanced paste method that tries system clipboard first
     */
    public void doPasteFromSystem() {
        if (!internalClipboard.isEmpty()) {
            cirSim.getActiveDocument().circuitEditor.doPaste(internalClipboard);
            return;
        }

        CirSim.console("hasSystemClipboardSupport: " + hasSystemClipboardSupport);
        if (hasSystemClipboardSupport) {
            readFromSystemClipboard(new ClipboardCallback() {
                @Override
                public void onSuccess(String data) {
                    if (data != null && !data.isEmpty() && isCircuitData(data)) {
                        internalClipboard = data;
                        cirSim.getActiveDocument().circuitEditor.doPaste(data);
                    }
                }

                @Override
                public void onError(String error) {
                    CirSim.console("System clipboard read failed: " + error);
                }
            });
        }
    }

    /**
     * Check if clipboard has data
     */
    public boolean hasClipboardData() {
        return !internalClipboard.isEmpty();
    }

    /**
     * Write to system clipboard using modern Clipboard API
     */
    private native void writeToSystemClipboard(String data) /*-{
        var nav = $wnd.navigator;
        if (nav.clipboard && nav.clipboard.writeText) {
            nav.clipboard.writeText(data).then(function() {
                console.log('Circuit data copied to system clipboard');
            })['catch'](function(err) {
                console.error('Failed to copy to system clipboard: ', err);
            });
        }
    }-*/;

    /**
     * Read from system clipboard asynchronously
     */
    private native void readFromSystemClipboardAsync(ClipboardCallback callback) /*-{
        var self = this;
        var nav = $wnd.navigator;
        if (nav.clipboard && nav.clipboard.readText) {
            nav.clipboard.readText().then(function(text) {
                callback.@com.lushprojects.circuitjs1.client.ClipboardCallback::onSuccess(Ljava/lang/String;)(text);
            })['catch'](function(err) {
                console.error('Failed to read from system clipboard: ', err);
                callback.@com.lushprojects.circuitjs1.client.ClipboardCallback::onError(Ljava/lang/String;)(err.toString());
            });
        } else {
            callback.@com.lushprojects.circuitjs1.client.ClipboardCallback::onError(Ljava/lang/String;)('Clipboard API not supported');
        }
    }-*/;

    /**
     * Try legacy clipboard methods (document.execCommand)
     */
    private native boolean tryLegacyClipboardWrite(String data) /*-{
        try {
            // Create temporary textarea element
            var textArea = document.createElement('textarea');
            textArea.value = data;
            textArea.style.position = 'fixed';
            textArea.style.left = '-999999px';
            textArea.style.top = '-999999px';
            document.body.appendChild(textArea);

            // Select and copy
            textArea.focus();
            textArea.select();
            var successful = document.execCommand('copy');

            // Clean up
            document.body.removeChild(textArea);

            if (successful) {
                console.log('Circuit data copied using legacy method');
                return true;
            }
        } catch (err) {
            console.error('Legacy clipboard copy failed: ', err);
        }
        return false;
    }-*/;

    /**
     * Whether text read from the system clipboard is a circuit to paste: the side-effect-free
     * circuit test of file contents ([SP_AGA_03_09]: a JSON v2 circuit, or text whose every line
     * is a circuit line with at least one element or options line). Prose is never pasted (the
     * old token heuristic accepted any text containing "r ", "c ", "l " or "w ").
     */
    private boolean isCircuitData(String data) {
        return data != null && !data.isEmpty() && CircuitContentTest.isCircuit(data);
    }

    /**
     * Clear clipboard data
     */
    public void clearClipboard() {
        internalClipboard = "";
        if (hasSystemClipboardSupport) {
            writeToSystemClipboard("");
        }
    }

    /**
     * Get clipboard content for debugging
     */
    public String getClipboardInfo() {
        return "Internal clipboard: " + (internalClipboard.isEmpty() ? "empty" : "has data") +
               ", System clipboard support: " + hasSystemClipboardSupport;
    }

    /**
     * Check if system clipboard is supported
     */
    public boolean hasSystemClipboardSupport() {
        return hasSystemClipboardSupport;
    }
}
