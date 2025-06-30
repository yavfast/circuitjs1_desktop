package com.lushprojects.circuitjs1.client;

import com.google.gwt.core.client.GWT;

/**
 * Platform-specific utilities for handling system operations
 * Provides cross-platform solutions for URL opening and system integration
 */
public class PlatformUtils {

    /**
     * Safely open a URL using platform-appropriate methods
     * Handles the UnsupportedOperationException on Linux systems
     *
     * @param url The URL to open
     * @return true if URL was opened successfully, false otherwise
     */
    public static boolean openURL(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }

        try {
            // First try the JavaScript window.open approach (works in NW.js)
            if (openURLWithJavaScript(url)) {
                return true;
            }

            // AI_TODO: Add native Java Desktop API as fallback
            // This would handle cases where JavaScript approach fails

        } catch (Exception e) {
            GWT.log("Failed to open URL: " + url + " - " + e.getMessage());

            // AI_THINK: Could implement command-line fallback here
            // For Linux: xdg-open, for macOS: open, for Windows: start
            return openURLWithSystemCommand(url);
        }

        return false;
    }

    /**
     * Open URL using JavaScript window.open (works in NW.js environment)
     */
    private static native boolean openURLWithJavaScript(String url) /*-{
        try {
            // Check if we're in NW.js environment
            if (typeof nw !== 'undefined' && nw.Shell) {
                nw.Shell.openExternal(url);
                return true;
            }

            // Fallback to regular window.open
            if (typeof window !== 'undefined') {
                var win = window.open(url, '_blank');
                return win != null;
            }

            return false;
        } catch (e) {
            console.error('JavaScript URL opening failed:', e);
            return false;
        }
    }-*/;

    /**
     * Fallback method using system commands
     * AI_TODO: Implement native system command execution
     */
    private static boolean openURLWithSystemCommand(String url) {
        // AI_THINK: This would require platform detection and system command execution
        // Could be implemented using ProcessBuilder in a separate utility class
        GWT.log("System command URL opening not yet implemented for: " + url);
        return false;
    }

    /**
     * Get platform information for debugging
     */
    public static native String getPlatformInfo() /*-{
        try {
            var info = {
                userAgent: navigator.userAgent || 'unknown',
                platform: navigator.platform || 'unknown',
                isNWJS: typeof nw !== 'undefined',
                hasShell: typeof nw !== 'undefined' && typeof nw.Shell !== 'undefined'
            };
            return JSON.stringify(info);
        } catch (e) {
            return 'Platform detection failed: ' + e.message;
        }
    }-*/;

    /**
     * Check if external URL opening is supported
     */
    public static boolean isURLOpeningSupported() {
        try {
            String platformInfo = getPlatformInfo();
            return platformInfo.contains("isNWJS\":true") ||
                   platformInfo.contains("hasShell\":true");
        } catch (Exception e) {
            return false;
        }
    }
}
