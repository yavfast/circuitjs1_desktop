package com.lushprojects.circuitjs1.client;

import com.google.gwt.i18n.client.DateTimeFormat;
import com.google.gwt.core.client.Scheduler;

import java.util.Date;
import java.util.ArrayList;

public class LogManager extends BaseCirSimDelegate {

    public final ArrayList<String> logEntries;

    // File logging fields
    private String currentLogFileName;
    private String sessionStartTime;
    private String logDirectory = "/tmp/circuit/logs/";
    private DateTimeFormat fileNameFormat;
    private DateTimeFormat logEntryFormat;
    private boolean fileLoggingEnabled = true;

    // Asynchronous logging fields
    private final ArrayList<String> logWriteQueue = new ArrayList<>();
    private boolean isWriting = false;
    private Scheduler.RepeatingCommand logWriteCommand;
    private static final int LOG_WRITE_DELAY_MS = 100; // Batch writes every 100ms
    private static final int MAX_QUEUE_SIZE = 1000; // Maximum queued entries before forcing write

    // AI_THINK: Track scheduled commands to allow cancellation
    private boolean logWriteScheduled = false;

    protected LogManager(CirSim cirSim) {
        super(cirSim);
        logEntries = new ArrayList<>();
        initializeFileLogging();
    }

    // Initialize file logging system
    private void initializeFileLogging() {
        fileNameFormat = DateTimeFormat.getFormat("yyyyMMdd_HHmmss");
        logEntryFormat = DateTimeFormat.getFormat("HH:mm:ss.SSS");

        // Initialize asynchronous logging commands
        initializeAsyncLogTimer();

        startNewSession();

        // Create log directory if it doesn't exist
        createLogDirectory();

        // Add application start log entry
        addLogEntry("CircuitJS1 Desktop application started");
    }

    // Initialize async logging timer for batched writes
    private void initializeAsyncLogTimer() {
        logWriteCommand = () -> {
            flushLogQueue();
            return false; // Do not repeat automatically
        };
    }

    // Add log entry to async write queue
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

    // Schedule async log write with timer
    private void scheduleLogWrite() {
        if (logWriteCommand != null && !isWriting) {
            Scheduler.get().scheduleFixedDelay(logWriteCommand, LOG_WRITE_DELAY_MS);
            logWriteScheduled = true;
        }
    }

    // Flush log queue to file asynchronously
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

    // Write multiple log entries asynchronously using native methods
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

    // Check for pending writes after async operation completes
    private void checkForPendingWrites() {
        synchronized (logWriteQueue) {
            if (!logWriteQueue.isEmpty() && !isWriting) {
                scheduleLogWrite();
            }
        }
    }

    // GWT-compatible async operation waiting utility
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

    // Functional interface for boolean conditions (GWT-compatible)
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }

    // Force immediate flush of all pending log entries
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

    // Enhanced writeLogToFile for async operation
    private void writeLogToFileAsync(String logEntry) {
        queueLogEntry(logEntry);
    }

    // Create log directory using native methods
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

    // Write log entry to file using native methods
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

    // Save all logs to file using native methods
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

    // Download logs as file for browser environment
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

    public void addLogEntry(String message) {
        // Create timestamped log entry
        String timestamp = logEntryFormat.format(new Date());
        String logEntry = "[" + timestamp + "] " + message;

        // Add to memory collection
        logEntries.add(logEntry);

        // Write to file asynchronously to avoid blocking UI
        writeLogToFileAsync(logEntry);
    }

    public void clearLogs() {
        // Flush any pending writes and UI updates before clearing
        forceFlushLogs();

        logEntries.clear();
        updateLogCount();
    }

    // Cleanup method to call before application shutdown
    public void shutdown() {
        // Cancel scheduled commands by setting flags
        logWriteScheduled = false;

        // Force flush all remaining log entries and UI updates
        forceFlushLogs();

        // Add shutdown log entry
        String shutdownMessage = "=== CircuitJS1 session ended at " +
            DateTimeFormat.getFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " ===";

        // Write shutdown message directly (bypass queue since we're shutting down)
        if (fileLoggingEnabled) {
            writeLogToFile(shutdownMessage);
        }
    }

    // Get file write queue status for monitoring
    public int getQueueSize() {
        synchronized (logWriteQueue) {
            return logWriteQueue.size();
        }
    }

    // Check if write operations are in progress
    public boolean isWriteInProgress() {
        return isWriting;
    }

    // Add log entry with specific log level
    public void addLogEntry(String message, String level) {
        String levelPrefix = "[" + level.toUpperCase() + "] ";
        addLogEntry(levelPrefix + message);
    }

    // Convenience methods for different log levels
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
    }

    public void saveLogsToFile() {
        // Force flush any pending writes before saving
        forceFlushLogs();
        saveAllLogsToFile();
    }

    // Start a new logging session with a unique filename
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

    // Get current log file path for external access
    public String getCurrentLogFilePath() {
        if (fileLoggingEnabled && currentLogFileName != null) {
            return logDirectory + currentLogFileName;
        }
        return null;
    }

    // Check if file logging is available
    public boolean isFileLoggingEnabled() {
        return fileLoggingEnabled;
    }

    // Set file logging state
    public void setFileLoggingEnabled(boolean enabled) {
        this.fileLoggingEnabled = enabled;
    }

}
