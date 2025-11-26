package com.lushprojects.circuitjs1.client.ui.tabs;

import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.PushButton;
import com.lushprojects.circuitjs1.client.CircuitDocument;

public class TabWidget extends Composite {

    private final CircuitDocument document;
    private final FlowPanel panel;
    private final Label titleLabel;
    private final Label statusLabel;
    private final Label closeButton;
    private boolean isActive;

    public interface TabListener {
        void onTabSelected(CircuitDocument document);

        void onTabClosed(CircuitDocument document);
    }

    public TabWidget(CircuitDocument document, TabListener listener) {
        this.document = document;
        this.panel = new FlowPanel();

        panel.setStyleName("tabWidget");

        statusLabel = new Label();
        statusLabel.setStyleName("tabStatus");
        panel.add(statusLabel);

        titleLabel = new Label();
        titleLabel.setStyleName("tabTitle");
        panel.add(titleLabel);

        closeButton = new Label("X");
        closeButton.setStyleName("tabCloseBtn");
        closeButton.addClickHandler(new ClickHandler() {
            @Override
            public void onClick(ClickEvent event) {
                event.stopPropagation();
                listener.onTabClosed(document);
            }
        });
        panel.add(closeButton);

        panel.addDomHandler(new ClickHandler() {
            @Override
            public void onClick(ClickEvent event) {
                listener.onTabSelected(document);
            }
        }, ClickEvent.getType());

        initWidget(panel);
    }

    public void setTitle(String title) {
        titleLabel.setText(title);
        titleLabel.setTitle(title); // Tooltip
    }

    public void setStatus(boolean isRunning, boolean hasError) {
        if (hasError) {
            statusLabel.setText("⚠");  // Warning triangle
            statusLabel.setTitle("Error");
            statusLabel.setStyleName("tabStatus error");
            statusLabel.setVisible(true);
        } else if (isRunning) {
            statusLabel.setText("●");  // Filled circle (process indicator)
            statusLabel.setTitle("Running");
            statusLabel.setStyleName("tabStatus running");
            statusLabel.setVisible(true);
        } else {
            statusLabel.setText("");
            statusLabel.setTitle("");
            statusLabel.setStyleName("tabStatus");
            statusLabel.setVisible(false);  // Hide when stopped
        }
    }

    public void setActive(boolean active) {
        this.isActive = active;
        if (active) {
            panel.addStyleName("activeTab");
        } else {
            panel.removeStyleName("activeTab");
        }
    }

    public CircuitDocument getDocument() {
        return document;
    }
}
