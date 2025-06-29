package com.lushprojects.circuitjs1.client;

/**
 * Manages clipboard operations with system clipboard integration
 * Provides copy/paste functionality for circuit elements
 */
public class ClipboardManager {

    private final CirSim cirSim;
    private String internalClipboard = "";
    private boolean hasSystemClipboardSupport = false;

    public ClipboardManager(CirSim cirSim) {
        this.cirSim = cirSim;
        this.hasSystemClipboardSupport = checkClipboardSupport();
    }

    /**
     * Check if browser supports modern Clipboard API
     */
    private native boolean checkClipboardSupport() /*-{
        return !!(navigator.clipboard && navigator.clipboard.writeText && navigator.clipboard.readText);
    }-*/;

    /**
     * Copy selected elements to clipboard
     */
    public void doCopy() {
        String circuitData = cirSim.circuitEditor.copyOfSelectedElms();
        setClipboard(circuitData);
    }

    /**
     * Cut selected elements to clipboard
     */
    public void doCut() {
        String circuitData = cirSim.circuitEditor.copyOfSelectedElms();
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
        if (hasSystemClipboardSupport) {
            writeToSystemClipboard(data);
        } else {
            // Fallback: try legacy methods
            tryLegacyClipboardWrite(data);
        }
    }

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
        if (hasSystemClipboardSupport) {
            readFromSystemClipboard(new ClipboardCallback() {
                @Override
                public void onSuccess(String data) {
                    if (data != null && !data.isEmpty() && isCircuitData(data)) {
                        // Update internal clipboard with system data
                        internalClipboard = data;
                        cirSim.circuitEditor.doPaste(data);
                    } else {
                        // Fallback to internal clipboard
                        cirSim.circuitEditor.doPaste(internalClipboard);
                    }
                }

                @Override
                public void onError(String error) {
                    // Fallback to internal clipboard
                    cirSim.circuitEditor.doPaste(internalClipboard);
                }
            });
        } else {
            // Use internal clipboard directly
            cirSim.circuitEditor.doPaste(internalClipboard);
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
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(data).then(function() {
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
        if (navigator.clipboard && navigator.clipboard.readText) {
            navigator.clipboard.readText().then(function(text) {
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
     * Check if data looks like circuit data
     */
    private boolean isCircuitData(String data) {
        if (data == null || data.isEmpty()) {
            return false;
        }

        // Simple heuristic: circuit data typically starts with $ and contains circuit elements
        return data.startsWith("$") ||
               data.contains("$ ") ||
               data.contains("r ") ||  // resistor
               data.contains("c ") ||  // capacitor
               data.contains("l ") ||  // inductor
               data.contains("w ");    // wire
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
