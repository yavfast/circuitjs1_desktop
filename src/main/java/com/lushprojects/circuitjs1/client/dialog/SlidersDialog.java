package com.lushprojects.circuitjs1.client.dialog;

import com.google.gwt.core.client.Scheduler;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.HasHorizontalAlignment;
import com.google.gwt.user.client.ui.HasVerticalAlignment;
import com.google.gwt.user.client.ui.HorizontalPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.RootLayoutPanel;
import com.google.gwt.user.client.ui.VerticalPanel;
import com.google.gwt.user.client.ui.Widget;
import com.lushprojects.circuitjs1.client.Scrollbar;

public class SlidersDialog extends Dialog {
    private final VerticalPanel panel;

    public SlidersDialog() {
        super(false, false);
        setText("Adjustable Sliders");
        panel = new VerticalPanel();
        setWidget(panel);
        
        getElement().getStyle().setProperty("overflowY", "auto");
    }

    @Override
    public void show() {
        super.show();
        // If position wasn't restored from storage, apply fallback: position to the right below Controls
        if (!isPositionRestored()) {
            Scheduler.get().scheduleDeferred(() -> {
                applyFallbackPosition();
            });
        }
    }

    private void applyFallbackPosition() {
        int mainWidth = RootLayoutPanel.get().getOffsetWidth();
        int dialogWidth = getOffsetWidth();
        if (dialogWidth <= 0) return;
        
        int left = mainWidth - dialogWidth - 20;
        int top = 50; // Below the Controls dialog (which is at top ~80-100 pixels)
        
        setPopupPosition(left, top);
    }

    @Override
    protected String getOptionPrefix() {
        return "SlidersDialog";
    }

    public Widget addSlider(Label titleLabel, Label valueLabel, Scrollbar slider, Button editAdjustableButton, Button editElementButton) {
        VerticalPanel row = new VerticalPanel();
        row.setWidth("100%");
        
        HorizontalPanel titlePanel = new HorizontalPanel();
        titlePanel.setVerticalAlignment(HasVerticalAlignment.ALIGN_MIDDLE);
        titlePanel.setWidth("100%");
        titlePanel.add(titleLabel);
        titlePanel.add(valueLabel);
        titlePanel.setCellHorizontalAlignment(valueLabel, HasHorizontalAlignment.ALIGN_RIGHT);

        HorizontalPanel controlPanel = new HorizontalPanel();
        controlPanel.setVerticalAlignment(HasVerticalAlignment.ALIGN_MIDDLE);
        controlPanel.setSpacing(5);
        controlPanel.setWidth("100%");
        
        slider.setWidth("100%");
        
        controlPanel.add(slider);
        controlPanel.add(editAdjustableButton);
        controlPanel.add(editElementButton);
        controlPanel.setCellWidth(slider, "100%");

        row.add(titlePanel);
        row.add(controlPanel);
        
        panel.add(row);
        return row;
    }

    // Adds a single-widget row (e.g. an element control button) below the sliders.
    public Widget addWidgetRow(Widget widget) {
        panel.add(widget);
        return widget;
    }

    public void removeSlider(Widget row) {
        panel.remove(row);
    }

    public void clear() {
        panel.clear();
    }

    public boolean isEmpty() {
        return panel.getWidgetCount() == 0;
    }

    public void setMaxHeight(int height) {
        getElement().getStyle().setPropertyPx("maxHeight", height);
    }
}
