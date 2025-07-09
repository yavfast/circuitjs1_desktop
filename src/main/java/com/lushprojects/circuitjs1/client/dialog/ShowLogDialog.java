package com.lushprojects.circuitjs1.client.dialog;

import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.HasHorizontalAlignment;
import com.google.gwt.user.client.ui.HorizontalPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.TextArea;
import com.google.gwt.user.client.ui.VerticalPanel;
import com.google.gwt.user.client.Timer;
import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.LogManager;
import com.lushprojects.circuitjs1.client.util.Locale;

public class ShowLogDialog extends Dialog {

    VerticalPanel vp;
    CirSim sim;
    TextArea textArea;
    Label statusLabel;
    Timer refreshTimer;

    public ShowLogDialog(CirSim asim) {
        super();
        closeOnEnter = false;
        sim = asim;
        setGlassEnabled(false);
        setModal(false);

        Button okButton, clearButton, saveButton, refreshButton, copyButton;

        vp = new VerticalPanel();
        setWidget(vp);
        setText(Locale.LS("Application Logs"));

        // Status label showing log info
        statusLabel = new Label();
        vp.add(statusLabel);

        // Button panel
        HorizontalPanel hp1 = new HorizontalPanel();
        hp1.setWidth("100%");
        hp1.setStyleName("topSpace");
        vp.add(hp1);

        // Group for refresh and clear buttons
        HorizontalPanel refreshClearGroup = new HorizontalPanel();
        refreshClearGroup.add(refreshButton = new Button(Locale.LS("Refresh")));
        refreshClearGroup.add(clearButton = new Button(Locale.LS("Clear Logs")));
        hp1.add(refreshClearGroup);

        vp.add(new Label(Locale.LS("Recent log entries:")));

        // Text area for displaying logs
        textArea = new TextArea();
        textArea.setWidth("400px");
        textArea.setHeight("800px");
        vp.add(textArea);

        // Second row of buttons
        HorizontalPanel hp2 = new HorizontalPanel();
        hp2.setWidth("100%");
        hp2.setStyleName("topSpace");
        vp.add(hp2);

        // Group for copy and save buttons
        HorizontalPanel copySaveGroup = new HorizontalPanel();
        copySaveGroup.add(copyButton = new Button(Locale.LS("Copy to Clipboard")));
        copySaveGroup.add(saveButton = new Button(Locale.LS("Save to File")));
        hp2.add(copySaveGroup);

        // Close button on the right
        hp2.setHorizontalAlignment(HasHorizontalAlignment.ALIGN_RIGHT);
        hp2.add(okButton = new Button(Locale.LS("Close")));

        // Button event handlers
        okButton.addClickHandler(event -> closeDialog());

        refreshButton.addClickHandler(event -> {
            updateLogContent();
            updateStatusLabel();
        });

        clearButton.addClickHandler(event -> {
            if (com.google.gwt.user.client.Window.confirm(Locale.LS("Are you sure you want to clear all logs?"))) {
                sim.logManager.clearLogs();
                updateLogContent();
                updateStatusLabel();
            }
        });

        copyButton.addClickHandler(event -> {
            textArea.setFocus(true);
            textArea.selectAll();
            copyToClipboard();
            textArea.setSelectionRange(0, 0);
        });

        saveButton.addClickHandler(event -> {
            sim.logManager.saveLogsToFile();
        });

        // Auto-refresh timer (every 2 seconds)
        refreshTimer = new Timer() {
            @Override
            public void run() {
                updateStatusLabel();
                updateLogContent();
            }
        };

        // Start auto-refresh
        refreshTimer.scheduleRepeating(500);
    }

    @Override
    protected String getOptionPrefix() {
        return "ShowLogDialog";
    }

    private int lastLogSize = 0;

    // Update the log content in the text area
    private void updateLogContent() {
        LogManager logManager = sim.logManager;

        int logSize = logManager.logEntries.size();
        if (lastLogSize == logSize) {
            return;
        }

        lastLogSize = logSize;

        StringBuilder sb = new StringBuilder(16 * 1024);

        // Show recent entries (last 100 to avoid performance issues)
        int startIndex = Math.max(0, logSize - 100);

        for (int i = startIndex; i < logSize; i++) {
            sb.append(logManager.logEntries.get(i)).append("\n");
        }

        textArea.setText(sb.toString());

        // Auto-scroll to bottom to show latest entries
        textArea.getElement().setScrollTop(textArea.getElement().getScrollHeight());
    }

    // Update status label with log information
    private void updateStatusLabel() {
        LogManager logManager = sim.logManager;
        if (logManager != null) {
            int totalEntries = logManager.logEntries.size();
            int queueSize = logManager.getQueueSize();
            boolean writeInProgress = logManager.isWriteInProgress();
            String logFilePath = logManager.getCurrentLogFilePath();

            StringBuilder status = new StringBuilder();
            status.append(Locale.LS("Total entries: ")).append(totalEntries);

            if (queueSize > 0) {
                status.append(" | ").append(Locale.LS("Pending writes: ")).append(queueSize);
            }

            if (writeInProgress) {
                status.append(" | ").append(Locale.LS("Writing..."));
            }

            if (logFilePath != null) {
                status.append(" | ").append(Locale.LS("File: ")).append(logFilePath);
            } else {
                status.append(" | ").append(Locale.LS("File logging disabled"));
            }

            statusLabel.setText(status.toString());
        } else {
            statusLabel.setText(Locale.LS("Log manager not available"));
        }
    }

    @Override
    protected void onDetach() {
        // Stop the refresh timer when dialog is closed
        if (refreshTimer != null) {
            refreshTimer.cancel();
        }
        super.onDetach();
    }

    // Native method for clipboard operations
    private static native boolean copyToClipboard() /*-{
        return $doc.execCommand('copy');
    }-*/;
}
