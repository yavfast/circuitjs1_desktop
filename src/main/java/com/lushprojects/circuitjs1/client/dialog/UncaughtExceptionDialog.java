package com.lushprojects.circuitjs1.client.dialog;

import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.HorizontalPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.TextArea;
import com.google.gwt.user.client.ui.VerticalPanel;

/**
 * Non-blocking dialog used to display uncaught exceptions.
 * Replaces browser-level alerts which block DevTools automation.
 */
public class UncaughtExceptionDialog extends Dialog {

    private static UncaughtExceptionDialog instance;

    private final Label header;
    private final TextArea details;

    private UncaughtExceptionDialog() {
        super(false, false);
        setGlassEnabled(false);
        setModal(false);
        closeOnEnter = true;

        VerticalPanel vp = new VerticalPanel();
        vp.setWidth("520px");
        setWidget(vp);

        setText("Uncaught exception");

        header = new Label();
        vp.add(header);

        details = new TextArea();
        details.setWidth("510px");
        details.setHeight("320px");
        details.setReadOnly(true);
        vp.add(details);

        HorizontalPanel buttons = new HorizontalPanel();
        buttons.setWidth("100%");
        Button close = new Button("Close");
        close.addClickHandler(e -> closeDialog());
        buttons.add(close);
        vp.add(buttons);
    }

    public static void show(Throwable e, String stackTrace) {
        if (instance == null) {
            instance = new UncaughtExceptionDialog();
        }
        instance.update(e, stackTrace);
        instance.center();
        instance.show();
    }

    private void update(Throwable e, String stackTrace) {
        String msg = (e == null) ? "(null)" : e.getMessage();
        String type = (e == null) ? "Throwable" : e.getClass().getName();
        header.setText(type + ": " + msg);

        StringBuilder sb = new StringBuilder();
        sb.append(type);
        if (msg != null && msg.length() > 0) {
            sb.append(": ").append(msg);
        }
        sb.append("\n\n");
        if (stackTrace != null && stackTrace.length() > 0) {
            sb.append(stackTrace);
        }
        details.setText(sb.toString());
    }

    @Override
    protected String getOptionPrefix() {
        return "UncaughtExceptionDialog";
    }
}
