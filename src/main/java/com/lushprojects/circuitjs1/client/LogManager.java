package com.lushprojects.circuitjs1.client;

import com.google.gwt.user.client.ui.ScrollPanel;
import com.google.gwt.user.client.ui.VerticalPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.HorizontalPanel;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.Widget;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.event.dom.client.MouseDownEvent;
import com.google.gwt.event.dom.client.MouseDownHandler;
import com.google.gwt.i18n.client.DateTimeFormat;
import com.google.gwt.core.client.Scheduler;

import java.util.Date;
import java.util.ArrayList;

public class LogManager extends BaseCirSimDelegate {

    public static final int MIN_LOG_PANEL_WIDTH = 200;
    public static final int MAX_LOG_PANEL_WIDTH = 800;
    public static final int MIN_LOG_PANEL_HEIGHT = 400;

    public VerticalPanel logPanel;
    private HorizontalPanel controlPanel;
    private Label headerLabel;
    public ScrollPanel logScrollPanel;
    public VerticalPanel logEntriesPanel;
    public Button clearLogsButton;
    public Button saveLogsButton;
    public Label logCountLabel;
    public ArrayList<String> logEntries;
    public HTML resizeHandle;
    public boolean isResizing;
    public int startX;
    public int startWidth;

    public int logPanelWidth = 400;
    public int logPanelHeight = 800;

    // File logging fields
    private String currentLogFileName;
    private String sessionStartTime;
    private String logDirectory = "/tmp/circuit/logs/";
    private DateTimeFormat fileNameFormat;
    private DateTimeFormat logEntryFormat;
    private boolean fileLoggingEnabled = true;

    // NEW: Asynchronous logging fields
    private final ArrayList<String> logWriteQueue = new ArrayList<>();
    private boolean isWriting = false;
    private Scheduler.RepeatingCommand logWriteCommand;
    private static final int LOG_WRITE_DELAY_MS = 100; // Batch writes every 100ms
    private static final int MAX_QUEUE_SIZE = 1000; // Maximum queued entries before forcing write

    // NEW: Asynchronous UI update fields
    private final ArrayList<String> uiUpdateQueue = new ArrayList<>();
    private Scheduler.RepeatingCommand uiUpdateCommand;
    private boolean isUpdatingUI = false;
    private static final int UI_UPDATE_DELAY_MS = 50; // Update UI every 50ms
    private static final int MAX_UI_QUEUE_SIZE = 500; // Maximum UI updates queued before forcing update

    // AI_THINK: Track scheduled commands to allow cancellation
    private boolean logWriteScheduled = false;
    private boolean uiUpdateScheduled = false;

    protected LogManager(CirSim cirSim) {
        super(cirSim);
        logEntries = new ArrayList<>();
        isResizing = false;
        initializeFileLogging();
        initializeLogPanel();
        setupResizeHandlers();
    }

    // NEW: Initialize file logging system
    private void initializeFileLogging() {
        fileNameFormat = DateTimeFormat.getFormat("yyyyMMdd_HHmmss");
        logEntryFormat = DateTimeFormat.getFormat("HH:mm:ss.SSS");

        // Initialize asynchronous logging commands
        initializeAsyncLogTimer();

        // Initialize asynchronous UI update commands
        initializeAsyncUITimer();

        startNewSession();

        // Create log directory if it doesn't exist
        createLogDirectory();

        // Add application start log entry
        addLogEntry("CircuitJS1 Desktop application started");
    }

    // NEW: Initialize async logging timer for batched writes
    private void initializeAsyncLogTimer() {
        logWriteCommand = () -> {
            flushLogQueue();
            return false; // Do not repeat automatically
        };
    }

    // NEW: Initialize async UI update timer for batched UI updates
    private void initializeAsyncUITimer() {
        uiUpdateCommand = () -> {
            flushUIUpdateQueue();
            return false; // Do not repeat automatically
        };
    }

    // NEW: Add log entry to async write queue
    private void queueLogEntry(String logEntry) {
        if (!fileLoggingEnabled) {
            return;
        }

        synchronized (logWriteQueue) {
            logWriteQueue.add(logEntry);

            // Force immediate write if queue is getting too large
            if (logWriteQueue.size() >= MAX_QUEUE_SIZE) {
                flushLogQueue();
                return;
            }

            // Schedule batched write if not already scheduled
            if (!isWriting && logWriteQueue.size() == 1) {
                scheduleLogWrite();
            }
        }
    }

    // NEW: Schedule async log write with timer
    private void scheduleLogWrite() {
        if (logWriteCommand != null && !isWriting) {
            Scheduler.get().scheduleFixedDelay(logWriteCommand, LOG_WRITE_DELAY_MS);
            logWriteScheduled = true;
        }
    }

    // NEW: Flush log queue to file asynchronously
    private void flushLogQueue() {
        if (!fileLoggingEnabled || logWriteQueue.isEmpty()) {
            return;
        }

        synchronized (logWriteQueue) {
            if (isWriting || logWriteQueue.isEmpty()) {
                return;
            }

            isWriting = true;

            // Create a copy of the queue for async processing
            ArrayList<String> entriesToWrite = new ArrayList<String>(logWriteQueue);
            logWriteQueue.clear();

            // Write entries asynchronously
            writeLogEntriesAsync(entriesToWrite);
        }
    }

    // NEW: Write multiple log entries asynchronously using native methods
    private native void writeLogEntriesAsync(ArrayList<String> entries) /*-{
        var self = this;
        setTimeout(function() {
            try {
                if (!self.@com.lushprojects.circuitjs1.client.LogManager::fileLoggingEnabled) {
                    self.@com.lushprojects.circuitjs1.client.LogManager::isWriting = false;
                    return;
                }

                if ($wnd.nw && $wnd.nw.require) {
                    var fs = $wnd.nw.require('fs');
                    var logDir = self.@com.lushprojects.circuitjs1.client.LogManager::logDirectory;
                    var fileName = self.@com.lushprojects.circuitjs1.client.LogManager::currentLogFileName;

                    // Convert Java ArrayList to JavaScript array
                    var jsEntries = [];
                    for (var i = 0; i < entries.@java.util.ArrayList::size()(); i++) {
                        jsEntries.push(entries.@java.util.ArrayList::get(I)(i));
                    }

                    // Batch write all entries at once
                    var logContent = jsEntries.join('\n') + '\n';

                    // Use async write to avoid blocking the UI thread
                    fs.appendFile(logDir + fileName, logContent, 'utf8', function(err) {
                        if (err) {
                            console.log('Error writing to log file: ' + err.message);
                        }

                        // Mark writing as complete
                        self.@com.lushprojects.circuitjs1.client.LogManager::isWriting = false;

                        // Check if there are more entries to write
                        self.@com.lushprojects.circuitjs1.client.LogManager::checkForPendingWrites()();
                    });
                } else {
                    self.@com.lushprojects.circuitjs1.client.LogManager::isWriting = false;
                }
            } catch (e) {
                console.log('Error in async log write: ' + e.message);
                self.@com.lushprojects.circuitjs1.client.LogManager::isWriting = false;
            }
        }, 0); // Use setTimeout to make it truly asynchronous
    }-*/;

    // NEW: Check for pending writes after async operation completes
    private void checkForPendingWrites() {
        synchronized (logWriteQueue) {
            if (!logWriteQueue.isEmpty() && !isWriting) {
                scheduleLogWrite();
            }
        }
    }

    // NEW: GWT-compatible async operation waiting utility
    private void waitForAsyncOperation(final Runnable onComplete, final BooleanSupplier condition, int timeoutMs) {
        final int[] waitCount = {0};
        final int maxWaitCount = timeoutMs / 10; // 10ms intervals

        Scheduler.RepeatingCommand waitCommand = () -> {
            if (condition.getAsBoolean() || waitCount[0] >= maxWaitCount) {
                onComplete.run();
                return false; // Stop repeating
            }
            waitCount[0]++;
            return true; // Continue repeating
        };

        Scheduler.get().scheduleFixedPeriod(waitCommand, 10); // Check every 10ms
    }

    // NEW: Functional interface for boolean conditions (GWT-compatible)
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }

    // NEW: Force immediate flush of all pending log entries
    public void forceFlushLogs() {
        if (logWriteScheduled) {
            logWriteScheduled = false;
        }
        flushLogQueue();

        // Wait for async write to complete (with timeout) using Scheduler
        waitForAsyncOperation(() -> {
            // Operation completed or timed out
        }, () -> !isWriting, 500); // Max 500ms wait
    }

    // NEW: Enhanced writeLogToFile for async operation
    private void writeLogToFileAsync(String logEntry) {
        queueLogEntry(logEntry);
    }

    // NEW: Create log directory using native methods
    private native void createLogDirectory() /*-{
        try {
            // For Electron/NW.js environment
            if ($wnd.nw && $wnd.nw.require) {
                var fs = $wnd.nw.require('fs');
                var path = $wnd.nw.require('path');

                // Try to create /tmp/circuit/logs directory
                var tmpDir = '/tmp/circuit/logs';
                try {
                    fs.mkdirSync(tmpDir, {recursive: true});
                    this.@com.lushprojects.circuitjs1.client.LogManager::logDirectory = tmpDir + '/';
                } catch (e) {
                    // If /tmp is not available, use app directory
                    var appDir = path.join(process.cwd(), 'logs');
                    fs.mkdirSync(appDir, {recursive: true});
                    this.@com.lushprojects.circuitjs1.client.LogManager::logDirectory = appDir + '/';
                }
            } else {
                // Browser environment - disable file logging
                this.@com.lushprojects.circuitjs1.client.LogManager::fileLoggingEnabled = false;
            }
        } catch (e) {
            console.log('Could not create log directory: ' + e.message);
            this.@com.lushprojects.circuitjs1.client.LogManager::fileLoggingEnabled = false;
        }
    }-*/;

    // NEW: Write log entry to file using native methods
    private native void writeLogToFile(String logEntry) /*-{
        try {
            if (!this.@com.lushprojects.circuitjs1.client.LogManager::fileLoggingEnabled) {
                return;
            }

            if ($wnd.nw && $wnd.nw.require) {
                var fs = $wnd.nw.require('fs');
                var logDir = this.@com.lushprojects.circuitjs1.client.LogManager::logDirectory;
                var fileName = this.@com.lushprojects.circuitjs1.client.LogManager::currentLogFileName;

                // Append log entry to file
                fs.appendFileSync(logDir + fileName, logEntry + '\n', 'utf8');
            }
        } catch (e) {
            console.log('Error writing to log file: ' + e.message);
        }
    }-*/;

    // NEW: Save all logs to file using native methods
    private native void saveAllLogsToFile() /*-{
        try {
            if (!this.@com.lushprojects.circuitjs1.client.LogManager::fileLoggingEnabled) {
                alert('File logging is not available in this environment.');
                return;
            }

            if ($wnd.nw && $wnd.nw.require) {
                var fs = $wnd.nw.require('fs');
                var logDir = this.@com.lushprojects.circuitjs1.client.LogManager::logDirectory;
                var fileName = this.@com.lushprojects.circuitjs1.client.LogManager::currentLogFileName;
                var logEntries = this.@com.lushprojects.circuitjs1.client.LogManager::logEntries;

                // Convert Java ArrayList to JavaScript array
                var jsLogEntries = [];
                for (var i = 0; i < logEntries.@java.util.ArrayList::size()(); i++) {
                    jsLogEntries.push(logEntries.@java.util.ArrayList::get(I)(i));
                }

                // Write all log entries to file
                var logContent = jsLogEntries.join('\n') + '\n';
                fs.writeFileSync(logDir + fileName, logContent, 'utf8');

                alert('Logs saved to: ' + logDir + fileName);
            } else {
                // Browser fallback - download as file
                this.@com.lushprojects.circuitjs1.client.LogManager::downloadLogsAsFile()();
            }
        } catch (e) {
            alert('Error saving logs: ' + e.message);
            console.log('Error saving logs: ' + e.message);
        }
    }-*/;

    // NEW: Download logs as file for browser environment
    private native void downloadLogsAsFile() /*-{
        try {
            var logEntries = this.@com.lushprojects.circuitjs1.client.LogManager::logEntries;
            var fileName = this.@com.lushprojects.circuitjs1.client.LogManager::currentLogFileName;

            // Convert Java ArrayList to JavaScript array
            var jsLogEntries = [];
            for (var i = 0; i < logEntries.@java.util.ArrayList::size()(); i++) {
                jsLogEntries.push(logEntries.@java.util.ArrayList::get(I)(i));
            }

            // Create downloadable content
            var logContent = jsLogEntries.join('\n');
            var blob = new Blob([logContent], {type: 'text/plain'});
            var url = URL.createObjectURL(blob);

            // Create download link
            var link = $doc.createElement('a');
            link.href = url;
            link.download = fileName;
            link.style.display = 'none';

            // Trigger download
            $doc.body.appendChild(link);
            link.click();
            $doc.body.removeChild(link);

            // Clean up
            URL.revokeObjectURL(url);

            alert('Log file downloaded: ' + fileName);
        } catch (e) {
            alert('Error downloading logs: ' + e.message);
        }
    }-*/;

    public void initializeLogPanel() {
        logPanel = new VerticalPanel();
        logPanel.setWidth(logPanelWidth + "px");
        // AI_TODO: Remove fixed height, let it be calculated dynamically
        // logPanel.setHeight(logPanelHeight + "px"); - Remove this line
        logPanel.getElement().addClassName("logPanel");
        GWTUtils.setStyles(logPanel,
            "position", "relative",
            "display", "flex",
            "flexDirection", "column",
            "boxSizing", "border-box",
            "height", "100%",
            "maxHeight", "100%"
        );

        headerLabel = new Label("Console Logs");
        headerLabel.getElement().addClassName("logHeader");
        headerLabel.setWidth("100%");
        GWTUtils.setStyles(headerLabel,
            "flexShrink", "0",
            "flexGrow", "0",
            "padding", "8px",
            "borderBottom", "1px solid #ccc",
            "backgroundColor", "#f5f5f5",
            "fontWeight", "bold",
            "textAlign", "center"
        );
        logPanel.add(headerLabel);

        controlPanel = new HorizontalPanel();
        controlPanel.setWidth("100%");
        GWTUtils.setStyles(controlPanel,
            "flexShrink", "0",
            "flexGrow", "0",
            "padding", "8px",
            "borderBottom", "1px solid #ccc",
            "boxSizing", "border-box",
            "backgroundColor", "#fafafa",
            "display", "flex",
            "justifyContent", "space-between",
            "alignItems", "center"
        );

        clearLogsButton = new Button("Clear Logs");
        clearLogsButton.addClickHandler(new ClickHandler() {
            public void onClick(ClickEvent event) {
                clearLogs();
            }
        });
        GWTUtils.setPadding(clearLogsButton, "4px 12px");

        saveLogsButton = new Button("Save Logs");
        saveLogsButton.addClickHandler(new ClickHandler() {
            public void onClick(ClickEvent event) {
                saveLogsToFile();
            }
        });
        GWTUtils.setPadding(saveLogsButton, "4px 12px");

        logCountLabel = new Label("0 entries");
        logCountLabel.getElement().addClassName("logCount");
        GWTUtils.setStyles(logCountLabel,
            "fontSize", "12px",
            "color", "666",
            "fontStyle", "italic"
        );

        controlPanel.add(clearLogsButton);
        controlPanel.add(saveLogsButton);
        controlPanel.add(logCountLabel);
        logPanel.add(controlPanel);

        logEntriesPanel = new VerticalPanel();
        logEntriesPanel.setWidth("100%");
        GWTUtils.setStyles(logEntriesPanel,
            "padding", "0",
            "boxSizing", "border-box"
        );
        // Remove any fixed height from logEntriesPanel
        GWTUtils.clearStyle(logEntriesPanel, "height");
        GWTUtils.clearStyle(logEntriesPanel, "maxHeight");
        GWTUtils.clearStyle(logEntriesPanel, "minHeight");

        logScrollPanel = new ScrollPanel();
        logScrollPanel.setWidget(logEntriesPanel);
        logScrollPanel.setWidth("100%");
        logScrollPanel.getElement().addClassName("logScrollPanel");
        // Ensure logScrollPanel fills all available space
        GWTUtils.setStyles(logScrollPanel,
            "flex", "1 1 auto",
            "overflowY", "auto",
            "overflowX", "hidden",
            "border", "1px solid #ddd",
            "margin", "0",
            "boxSizing", "border-box",
            "backgroundColor", "#ffffff"
        );
        // Remove any fixed height from logScrollPanel
        GWTUtils.clearStyle(logScrollPanel, "height");
        GWTUtils.clearStyle(logScrollPanel, "maxHeight");
        GWTUtils.clearStyle(logScrollPanel, "minHeight");
        GWTUtils.setFlexItem(logScrollPanel, "1", "1", "0");

        logPanel.add(logScrollPanel);

        resizeHandle = new HTML();
        resizeHandle.getElement().addClassName("logResizeHandle");
        GWTUtils.setStyles(resizeHandle,
            "position", "absolute",
            "right", "-5px",
            "top", "0px",
            "width", "10px",
            "height", "100%",
            "cursor", "ew-resize",
            "backgroundColor", "rgba(128, 128, 128, 0.3)",
            "borderRight", "2px solid #888",
            "zIndex", "1000"
        );
        resizeHandle.getElement().setTitle("Drag to resize log panel");

        logPanel.add(resizeHandle);
    }

    public void setupResizeHandlers() {
        resizeHandle.addMouseDownHandler(event -> {
            isResizing = true;
            startX = event.getClientX();
            startWidth = logPanelWidth;
            event.preventDefault();

            addDocumentMouseHandlers();
        });
    }

    public native void addDocumentMouseHandlers() /*-{
        var self = this;
        var mouseMoveHandler = function(e) {
            self.@com.lushprojects.circuitjs1.client.LogManager::handleMouseMove(II)(e.clientX, e.clientY);
        };
        var mouseUpHandler = function(e) {
            self.@com.lushprojects.circuitjs1.client.LogManager::handleMouseUp()();
            $doc.removeEventListener('mousemove', mouseMoveHandler);
            $doc.removeEventListener('mouseup', mouseUpHandler);
        };

        $doc.addEventListener('mousemove', mouseMoveHandler);
        $doc.addEventListener('mouseup', mouseUpHandler);
    }-*/;

    public void handleMouseMove(int clientX, int clientY) {
        if (isResizing) {
            int deltaX = clientX - startX;
            int newWidth = startWidth + deltaX;

            if (newWidth < MIN_LOG_PANEL_WIDTH) {
                newWidth = MIN_LOG_PANEL_WIDTH;
            } else if (newWidth > MAX_LOG_PANEL_WIDTH) {
                newWidth = MAX_LOG_PANEL_WIDTH;
            }

            logPanelWidth = newWidth;

            cirSim.updateLogPanelWidth(newWidth);
        }
    }

    public void handleMouseUp() {
        isResizing = false;
    }

    public void updatePanelWidth(int newWidth) {
        logPanelWidth = newWidth;
        logPanel.setWidth(newWidth + "px");

        updateInternalComponentsSize(newWidth);
        updateResizeHandlePosition();

        // AI_THINK: Recalculate height when width changes to prevent floating issues
        // Force height recalculation to maintain proper proportions
        int currentHeight = logPanel.getElement().getClientHeight();
        if (currentHeight > 0) {
            updatePanelHeight(currentHeight);
        }
    }

    // AI_THINK: Improved method to handle dynamic height calculation
    public void updatePanelHeight(int availableHeight) {
        if (logPanel == null) return;
        int headerHeight = getActualHeaderHeight();
        int controlPanelHeight = getActualControlPanelHeight();
        int margins = 32;
        logPanelHeight = Math.max(MIN_LOG_PANEL_HEIGHT, availableHeight - margins);
        GWTUtils.setStyles(logPanel,
            "height", logPanelHeight + "px",
            "maxHeight", logPanelHeight + "px",
            "minHeight", MIN_LOG_PANEL_HEIGHT + "px",
            "display", "flex",
            "flexDirection", "column"
        );
        if (logScrollPanel != null) {
            // Calculate available height for logScrollPanel
            int scrollPanelHeight = logPanelHeight - headerHeight - controlPanelHeight;
            if (scrollPanelHeight < 50) scrollPanelHeight = 50; // Minimum height
            // Set explicit height and remove flex styles
            GWTUtils.setStyles(logScrollPanel,
                "height", scrollPanelHeight + "px",
                "maxHeight", scrollPanelHeight + "px",
                "minHeight", scrollPanelHeight + "px",
                "overflowY", "auto",
                "overflowX", "hidden"
            );
            GWTUtils.clearStyle(logScrollPanel, "flex");
            if (logEntriesPanel != null) {
                GWTUtils.setStyles(logEntriesPanel,
                    "minHeight", "100%",
                    "height", "auto"
                );
            }
        }
    }

    // NEW: Helper method to get actual header height dynamically
    private int getActualHeaderHeight() {
        if (headerLabel == null) return 30; // Fallback value

        // Get actual rendered height or use computed height
        int actualHeight = headerLabel.getElement().getOffsetHeight();

        // If not yet rendered, calculate based on styling
        if (actualHeight <= 0) {
            // Calculate based on font size + padding
            String padding = headerLabel.getElement().getStyle().getPadding();
            int paddingValue = parsePaddingValue(padding, 5); // Default 5px

            // Estimate text height (typically 16-20px for normal text)
            int textHeight = 18;
            actualHeight = textHeight + (paddingValue * 2);
        }

        return Math.max(actualHeight, 20); // Minimum 20px
    }

    // NEW: Helper method to get actual control panel height dynamically
    private int getActualControlPanelHeight() {
        if (controlPanel == null) return 42; // Fallback value

        // Get actual rendered height
        int actualHeight = controlPanel.getElement().getOffsetHeight();

        // If not yet rendered, calculate based on content
        if (actualHeight <= 0) {
            // Get button height + padding
            int buttonHeight = (clearLogsButton != null) ?
                Math.max(clearLogsButton.getElement().getOffsetHeight(), 24) : 24;

            String padding = controlPanel.getElement().getStyle().getPadding();
            int paddingValue = parsePaddingValue(padding, 5); // Default 5px

            actualHeight = buttonHeight + (paddingValue * 2);
        }

        return Math.max(actualHeight, 32); // Minimum 32px
    }

    // NEW: Helper method to parse CSS padding values
    private int parsePaddingValue(String padding, int defaultValue) {
        if (padding == null || padding.isEmpty()) return defaultValue;

        try {
            // Remove 'px' suffix and parse
            String numericPart = padding.replaceAll("[^0-9]", "");
            if (!numericPart.isEmpty()) {
                return Integer.parseInt(numericPart);
            }
        } catch (NumberFormatException e) {
            // Ignore parsing errors, use default
        }

        return defaultValue;
    }

    // IMPROVED: Force recalculation of heights when needed
    public void recalculateHeight(int availableHeight) {
        // Force layout update to ensure accurate measurements
        if (logPanel != null) {
            // Trigger layout recalculation
            GWTUtils.setStyle(logPanel, "visibility", "hidden");
            logPanel.getElement().getOffsetHeight(); // Force layout
            GWTUtils.clearStyle(logPanel, "visibility");
        }

        // Now update with accurate measurements
        updatePanelHeight(availableHeight);
    }

    public void updatePanelSize() {
        if (cirSim.circuitInfo.developerMode) {
            cirSim.updateLogPanelWidth(logPanel.getElement().getClientWidth());
        }
    }

    public void updatePanelSize(int newWidth, int availableHeight) {
        updatePanelWidth(newWidth);
        updatePanelHeight(availableHeight);
    }

    public void updateInternalComponentsSize(int panelWidth) {
        if (logPanel == null) return;

        // Calculate effective width for internal components (accounting for padding/margins)
        int effectiveWidth = panelWidth - 10; // Account for panel margins

        for (int i = 0; i < logPanel.getWidgetCount(); i++) {
            Widget widget = logPanel.getWidget(i);

            if (widget == resizeHandle) continue;

            widget.setWidth("100%");

            if (widget instanceof HorizontalPanel) {
                HorizontalPanel hPanel = (HorizontalPanel) widget;
                hPanel.setWidth("100%");

                for (int j = 0; j < hPanel.getWidgetCount(); j++) {
                    Widget child = hPanel.getWidget(j);
                    if (child instanceof Button) {
                        continue;
                    } else if (child instanceof Label) {
                        GWTUtils.setStyles(child,
                            "flex", "1",
                            "textAlign", "right"
                        );
                    }
                }
            }
        }

        // Explicitly update logScrollPanel width
        if (logScrollPanel != null) {
            logScrollPanel.setWidth("100%");
            // Set explicit pixel width to ensure proper resizing
            GWTUtils.setStyle(logScrollPanel, "width", effectiveWidth + "px");

            // Update logEntriesPanel width as well
            if (logEntriesPanel != null) {
                logEntriesPanel.setWidth("100%");
                // Account for scroll panel padding and borders
                int entriesPanelWidth = effectiveWidth - 12; // Account for scroll panel margins and borders
                GWTUtils.setStyle(logEntriesPanel, "width", entriesPanelWidth + "px");

                // Update width of all log entry widgets
                for (int i = 0; i < logEntriesPanel.getWidgetCount(); i++) {
                    Widget logEntry = logEntriesPanel.getWidget(i);
                    logEntry.setWidth("100%");
                    GWTUtils.setStyle(logEntry, "width", (entriesPanelWidth - 10) + "px");
                }
            }
        }

        GWTUtils.setStyle(logPanel, "width", panelWidth + "px");
    }

    public void updateResizeHandlePosition() {
        if (resizeHandle != null) {
            GWTUtils.setStyle(resizeHandle, "right", "-5px");
        }
    }

    public void addLogEntry(String message) {
        // Create timestamped log entry
        String timestamp = logEntryFormat.format(new Date());
        String logEntry = "[" + timestamp + "] " + message;

        // Add to memory collection
        logEntries.add(logEntry);

        // Queue UI label for asynchronous addition to prevent blocking
        if (cirSim.circuitInfo.developerMode) {
            queueUIUpdate(logEntry);
        }

        // Write to file asynchronously to avoid blocking UI
        writeLogToFileAsync(logEntry);
    }

    Label createLogLabel(String logEntry) {
        // Create UI label for display
        Label logLabel = new Label(logEntry);
        logLabel.getElement().addClassName("logEntry");
        logLabel.setWidth("100%");

        // Style the log entry label
        GWTUtils.setStyles(logLabel,
                "padding", "4px 8px",
                "borderBottom", "1px solid #eee",
                "fontSize", "11px",
                "fontFamily", "monospace",
                "lineHeight", "1.4",
                "wordWrap", "break-word",
                "overflow", "hidden",
                "textOverflow", "ellipsis",
                "whiteSpace", "nowrap"
        );

        // Dynamic width calculation based on current panel width
        if (logEntriesPanel != null) {
            int availableWidth = logPanelWidth - 20; // Account for scroll panel and borders
            GWTUtils.setStyles(logLabel,
                    "width", "100%",
                    "maxWidth", availableWidth + "px",
                    "boxSizing", "border-box"
            );
        }

        return logLabel;
    }

    public void clearLogs() {
        // Flush any pending writes and UI updates before clearing
        forceFlushLogs();
        forceFlushUIUpdates();

        logEntries.clear();
        logEntriesPanel.clear();
        updateLogCount();
    }

    // NEW: Cleanup method to call before application shutdown
    public void shutdown() {
        // Cancel scheduled commands by setting flags
        logWriteScheduled = false;
        uiUpdateScheduled = false;

        // Force flush all remaining log entries and UI updates
        forceFlushLogs();
        forceFlushUIUpdates();

        // Add shutdown log entry
        String shutdownMessage = "=== CircuitJS1 session ended at " +
            DateTimeFormat.getFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " ===";

        // Write shutdown message directly (bypass queue since we're shutting down)
        if (fileLoggingEnabled) {
            writeLogToFile(shutdownMessage);
        }
    }

    // NEW: Get file write queue status for monitoring
    public int getQueueSize() {
        synchronized (logWriteQueue) {
            return logWriteQueue.size();
        }
    }

    // NEW: Get UI update queue status for monitoring
    public int getUIQueueSize() {
        synchronized (uiUpdateQueue) {
            return uiUpdateQueue.size();
        }
    }

    // NEW: Check if write operations are in progress
    public boolean isWriteInProgress() {
        return isWriting;
    }

    // NEW: Check if UI updates are in progress
    public boolean isUIUpdateInProgress() {
        return isUpdatingUI;
    }

    // NEW: Add log entry with specific log level
    public void addLogEntry(String message, String level) {
        String levelPrefix = "[" + level.toUpperCase() + "] ";
        addLogEntry(levelPrefix + message);
    }

    // NEW: Convenience methods for different log levels
    public void logInfo(String message) {
        addLogEntry(message, "INFO");
    }

    public void logWarning(String message) {
        addLogEntry(message, "WARN");
    }

    public void logError(String message) {
        addLogEntry(message, "ERROR");
    }

    public void logDebug(String message) {
        addLogEntry(message, "DEBUG");
    }

    public void updateLogCount() {
        int count = logEntries.size();
        logCountLabel.setText(count + " entries");
    }

    public void scrollToBottom() {
        logScrollPanel.scrollToBottom();
    }

    public void saveLogsToFile() {
        // Force flush any pending writes before saving
        forceFlushLogs();
        saveAllLogsToFile();
    }

    // NEW: Start a new logging session with a unique filename
    public void startNewSession() {
        // Initialize session start time for filename generation
        sessionStartTime = fileNameFormat.format(new Date());
        currentLogFileName = "circuit_log_" + sessionStartTime + ".txt";

        // Add session start entry but don't add to UI immediately
        String startMessage = "=== New CircuitJS1 session started at " +
            DateTimeFormat.getFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " ===";

        // Add to memory and queue for file writing
        logEntries.add(startMessage);
        if (fileLoggingEnabled) {
            queueLogEntry(startMessage);
        }
    }

    // NEW: Get current log file path for external access
    public String getCurrentLogFilePath() {
        if (fileLoggingEnabled && currentLogFileName != null) {
            return logDirectory + currentLogFileName;
        }
        return null;
    }

    // NEW: Check if file logging is available
    public boolean isFileLoggingEnabled() {
        return fileLoggingEnabled;
    }

    // NEW: Set file logging state
    public void setFileLoggingEnabled(boolean enabled) {
        this.fileLoggingEnabled = enabled;

        // Update save button state based on logging availability
        if (saveLogsButton != null) {
            saveLogsButton.setEnabled(enabled);
            saveLogsButton.setTitle(enabled ? "Save logs to file" : "File logging not available");
        }
    }

    // NEW: Add log label to async UI update queue
    private void queueUIUpdate(String logEntry) {
        synchronized (uiUpdateQueue) {
            uiUpdateQueue.add(logEntry);

            // Force immediate update if queue is getting too large
            if (uiUpdateQueue.size() >= MAX_UI_QUEUE_SIZE) {
                flushUIUpdateQueue();
                return;
            }

            // Schedule batched UI update if not already scheduled
            if (!isUpdatingUI && uiUpdateQueue.size() == 1) {
                scheduleUIUpdate();
            }
        }
    }

    // NEW: Schedule async UI update with timer
    private void scheduleUIUpdate() {
        if (uiUpdateCommand != null && !isUpdatingUI) {
            Scheduler.get().scheduleFixedDelay(uiUpdateCommand, UI_UPDATE_DELAY_MS);
            uiUpdateScheduled = true;
        }
    }

    // NEW: Flush UI update queue to panel asynchronously
    private void flushUIUpdateQueue() {
        if (uiUpdateQueue.isEmpty()) {
            return;
        }

        synchronized (uiUpdateQueue) {
            if (isUpdatingUI || uiUpdateQueue.isEmpty()) {
                return;
            }

            isUpdatingUI = true;

            // Create a copy of the queue for async processing
            ArrayList<String> labelsToAdd = new ArrayList<>(uiUpdateQueue);
            uiUpdateQueue.clear();

            // Add labels to UI in batches to prevent blocking
            addLabelsToUIAsync(labelsToAdd);
        }
    }

    // NEW: Add multiple labels to UI asynchronously
    private void addLabelsToUIAsync(ArrayList<String> labels) {
        // Use a timer to spread UI updates across multiple event loop cycles
        final int batchSize = 10; // Process 10 labels at a time
        final int[] currentIndex = {0};

        Scheduler.RepeatingCommand batchCommand = () -> {
            int endIndex = Math.min(currentIndex[0] + batchSize, labels.size());

            // Add batch of labels to UI
            for (int i = currentIndex[0]; i < endIndex; i++) {
                Label logLabel = createLogLabel(labels.get(i));
                logEntriesPanel.add(logLabel);
            }

            currentIndex[0] = endIndex;

            // Continue processing if there are more labels
            if (currentIndex[0] < labels.size()) {
                return true; // Continue repeating
            } else {
                // All labels processed, update UI state
                isUpdatingUI = false;
                updateLogCount();
                scrollToBottom();

                // Check if there are more UI updates pending
                checkForPendingUIUpdates();
                return false; // Stop repeating
            }
        };

        Scheduler.get().scheduleFixedDelay(batchCommand, 1); // Start processing immediately
    }

    // NEW: Check for pending UI updates after async operation completes
    private void checkForPendingUIUpdates() {
        synchronized (uiUpdateQueue) {
            if (!uiUpdateQueue.isEmpty() && !isUpdatingUI) {
                scheduleUIUpdate();
            }
        }
    }

    // NEW: Force immediate flush of all pending UI updates
    public void forceFlushUIUpdates() {
        if (uiUpdateScheduled) {
            uiUpdateScheduled = false;
        }
        flushUIUpdateQueue();

        // Wait for async UI update to complete using Scheduler
        waitForAsyncOperation(() -> {
            // UI updates completed or timed out
        }, () -> !isUpdatingUI, 500); // Max 500ms wait
    }
}
