package com.lushprojects.circuitjs1.client.dialog;

import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.CheckBox;
import com.google.gwt.user.client.ui.FlexTable;
import com.google.gwt.user.client.ui.HasHorizontalAlignment;
import com.google.gwt.user.client.ui.HorizontalPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.VerticalPanel;
import com.lushprojects.circuitjs1.client.McpServerStatus;
import com.lushprojects.circuitjs1.client.OptionsManager;
import com.lushprojects.circuitjs1.client.util.Locale;

import java.util.List;
import java.util.function.Consumer;

/**
 * [SP_MCP_02_04] Server info for the user: the in-app MCP server's status, instance ID, URLs, the
 * copyable connect command and the count of handled tool calls, plus the editable server
 * preferences of [SP_MCP_01_01] (enabled, base port, listening address) with "Save", which apply
 * at the next app start.
 * <p>
 * Reads the session {@link McpServerStatus} only (no dependency on the agent package) and follows
 * its changes while it is shown, so the call counter counts up live. Opened through
 * {@code DialogManager.showMcpServerDialog()}.
 */
public class McpServerDialog extends Dialog {

    private final McpServerStatus status;
    private final Consumer<String> copier;
    private final Runnable statusListener = this::refreshStatus;

    private final Label statusValue = new Label();
    private final Label instanceValue = new Label();
    private final VerticalPanel urlsPanel = new VerticalPanel();
    private final TextBox commandBox = new TextBox();
    private final Button copyButton;
    private final Label toolCallsValue = new Label();

    private final CheckBox enabledBox;
    private final TextBox portBox = new TextBox();
    private final TextBox hostBox = new TextBox();
    private final Label messageLabel = new Label();
    /** True while the keyboard focus is in a settings field (Enter saves only then). */
    private boolean settingsFocused;

    /**
     * @param status the session's MCP server status (read; listened to while the dialog shows)
     * @param copier writes a text to the system clipboard (the "Copy" button)
     */
    public McpServerDialog(McpServerStatus status, Consumer<String> copier) {
        super();
        this.status = status;
        this.copier = copier;
        closeOnEnter = false;
        setText(Locale.LS("MCP Server"));

        VerticalPanel vp = new VerticalPanel();
        setWidget(vp);

        FlexTable info = new FlexTable();
        info.getElement().setId("mcpServerInfo");
        int row = 0;
        info.setText(row, 0, Locale.LS("Status:"));
        info.setWidget(row++, 1, statusValue);
        info.setText(row, 0, Locale.LS("Instance ID:"));
        info.setWidget(row++, 1, instanceValue);
        info.setText(row, 0, Locale.LS("URLs:"));
        info.setWidget(row++, 1, urlsPanel);
        info.setText(row, 0, Locale.LS("Tool calls in this run:"));
        info.setWidget(row++, 1, toolCallsValue);
        vp.add(info);

        // [SP_MCP_02_04] one copyable command line
        vp.add(new Label(Locale.LS("Connect Claude Code with:")));
        HorizontalPanel cmd = new HorizontalPanel();
        commandBox.setReadOnly(true);
        commandBox.setVisibleLength(60);
        commandBox.getElement().setId("mcpServerCommand");
        commandBox.addClickHandler(event -> commandBox.selectAll());
        commandBox.addFocusHandler(event -> commandBox.selectAll());
        cmd.add(commandBox);
        cmd.add(copyButton = new Button(Locale.LS("Copy")));
        copyButton.addClickHandler(event -> {
            String text = commandBox.getText();
            if (!text.isEmpty()) {
                commandBox.setFocus(true);
                commandBox.selectAll();
                this.copier.accept(text);
            }
        });
        vp.add(cmd);

        // [SP_MCP_01_01] editable settings; they apply at the next start
        Label settings = new Label(Locale.LS("Settings (apply at the next start)"));
        settings.addStyleName("topSpace");
        vp.add(settings);
        McpServerStatus.Prefs prefs = McpServerStatus.readPrefs();
        FlexTable form = new FlexTable();
        enabledBox = new CheckBox(Locale.LS("Enabled"));
        enabledBox.setValue(prefs.enabled);
        form.setWidget(0, 0, enabledBox);
        form.setText(1, 0, Locale.LS("Base port:"));
        portBox.setText(String.valueOf(prefs.port));
        portBox.setVisibleLength(8);
        form.setWidget(1, 1, portBox);
        form.setText(2, 0, Locale.LS("Listening address:"));
        hostBox.setText(prefs.host);
        hostBox.setVisibleLength(24);
        form.setWidget(2, 1, hostBox);
        vp.add(form);
        enabledBox.addFocusHandler(event -> settingsFocused = true);
        enabledBox.addBlurHandler(event -> settingsFocused = false);
        portBox.addFocusHandler(event -> settingsFocused = true);
        portBox.addBlurHandler(event -> settingsFocused = false);
        hostBox.addFocusHandler(event -> settingsFocused = true);
        hostBox.addBlurHandler(event -> settingsFocused = false);
        messageLabel.setWordWrap(true);
        messageLabel.setWidth("420px");
        messageLabel.getElement().setId("mcpServerMessage");
        vp.add(messageLabel);

        HorizontalPanel hp = new HorizontalPanel();
        hp.setWidth("100%");
        hp.setStyleName("topSpace");
        hp.setHorizontalAlignment(HasHorizontalAlignment.ALIGN_LEFT);
        Button saveButton = new Button(Locale.LS("Save"));
        saveButton.addClickHandler(event -> save());
        hp.add(saveButton);
        hp.setHorizontalAlignment(HasHorizontalAlignment.ALIGN_RIGHT);
        Button closeButton = new Button(Locale.LS("Close"));
        closeButton.addClickHandler(event -> closeDialog());
        hp.add(closeButton);
        vp.add(hp);

        refreshStatus();
    }

    @Override
    protected String getOptionPrefix() {
        return "mcp.server";
    }

    @Override
    public void show() {
        super.show();
        status.addListener(statusListener);
        refreshStatus();
    }

    @Override
    public void hide(boolean autoClosed) {
        status.removeListener(statusListener);
        super.hide(autoClosed);
    }

    /**
     * Enter saves only while the focus is in a settings field (the dialog stays open to show the
     * result): Enter on a focused button or right after opening writes nothing. Escape closes.
     */
    @Override
    public void enterPressed() {
        if (settingsFocused) {
            save();
        }
    }

    @Override
    void apply() {
        save();
    }

    /** Shows the current status; also run on every status change while the dialog is shown. */
    private void refreshStatus() {
        statusValue.setText(status.describe());
        String id = status.getInstanceId();
        instanceValue.setText(id == null || status.getState() != McpServerStatus.State.LISTENING ? "\u2014" : id);
        urlsPanel.clear();
        List<String> urls = status.getState() == McpServerStatus.State.LISTENING ? status.getUrls() : null;
        if (urls == null || urls.isEmpty()) {
            urlsPanel.add(new Label("\u2014"));
        } else {
            for (String u : urls) {
                urlsPanel.add(new Label(u));
            }
        }
        String command = urls == null ? null : status.getConnectCommand();
        // set only on a change: a status change per tool call must not clear a selection
        String commandText = command == null ? "" : command;
        if (!commandText.equals(commandBox.getText())) {
            commandBox.setText(commandText);
        }
        commandBox.setEnabled(command != null);
        copyButton.setEnabled(command != null);
        toolCallsValue.setText(String.valueOf(status.getToolCalls()));
    }

    /**
     * [SP_MCP_02_04] "Save": validates the settings with the [SP_MCP_01_01] constraints and writes
     * them to the preferences; an invalid value is reported and nothing is written.
     *
     * @return true when the settings were written
     */
    boolean save() {
        String portText = portBox.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            port = -1;
        }
        if (!McpServerStatus.isValidPort(port)) {
            showMessage(Locale.LS("Base port must be a whole number from 1024 to 65535."));
            portBox.setFocus(true);
            return false;
        }
        int range = McpServerStatus.readPrefs().portRange;  // not edited here
        if (!McpServerStatus.isValidPortRange(port, range)) {
            showMessage(Locale.LS("Base port is too high for the port range: the last port must not exceed 65535.")
                    + " (" + port + " + " + range + " \u2212 1)");
            portBox.setFocus(true);
            return false;
        }
        String host = hostBox.getText().trim();
        if (!McpServerStatus.isValidHost(host)) {
            showMessage(Locale.LS("Listening address must be an IPv4 or IPv6 address, or localhost."));
            hostBox.setFocus(true);
            return false;
        }
        McpServerStatus.Prefs prefs = McpServerStatus.readPrefs();
        boolean enabled = enabledBox.getValue();
        if (prefs.invalidKeys.isEmpty() && enabled == prefs.enabled && port == prefs.port && host.equals(prefs.host)) {
            showMessage(Locale.LS("Nothing to save: the settings are unchanged."));
            return false;
        }
        OptionsManager.setOptionInStorage(McpServerStatus.PREF_ENABLED, enabled);
        OptionsManager.setOptionInStorage(McpServerStatus.PREF_PORT, port);
        OptionsManager.setOptionInStorage(McpServerStatus.PREF_HOST, host);
        portBox.setText(String.valueOf(port));
        hostBox.setText(host);
        showMessage(Locale.LS("Saved. The settings apply at the next start of the app."));
        return true;
    }

    private void showMessage(String text) {
        messageLabel.setText(text);
    }
}
