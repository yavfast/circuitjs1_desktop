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

    /**
     * While detached, the dialog belongs to the visible tab only and ignores every change: rows
     * are built but not added, and clear/remove/show/hide/resize do nothing. Set by the app shell
     * while a background document is bound, so that document's slider rebuilds (load, import,
     * undo) cannot clear or refill the visible tab's sliders (PL_AGA_DEC_01). The background
     * document's rows are rebuilt when its tab is activated.
     */
    private boolean detached;

    public SlidersDialog() {
        super(false, false);
        setText("Adjustable Sliders");
        panel = new VerticalPanel();
        setWidget(panel);
        
        getElement().getStyle().setProperty("overflowY", "auto");
    }

    /** Detaches the dialog from slider changes (see {@link #isDetached()}); restore the previous value afterwards. */
    public void setDetached(boolean detached) {
        this.detached = detached;
    }

    /** @return true while slider changes are ignored because a background document is bound */
    public boolean isDetached() {
        return detached;
    }

    @Override
    public void show() {
        if (detached) {
            return;
        }
        super.show();
        // If position wasn't restored from storage, apply fallback: position to the right below Controls
        if (!isPositionRestored()) {
            Scheduler.get().scheduleDeferred(() -> {
                applyFallbackPosition();
            });
        }
    }

    @Override
    public void hide(boolean autoClosed) {
        if (detached) {
            return;
        }
        super.hide(autoClosed);
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
        
        if (!detached) {
            panel.add(row);
        }
        return row;
    }

    // Adds a single-widget row (e.g. an element control button) below the sliders.
    public Widget addWidgetRow(Widget widget) {
        if (!detached) {
            panel.add(widget);
        }
        return widget;
    }

    public void removeSlider(Widget row) {
        if (!detached) {
            panel.remove(row);
        }
    }

    public void clear() {
        if (!detached) {
            panel.clear();
        }
    }

    public boolean isEmpty() {
        return panel.getWidgetCount() == 0;
    }

    public void setMaxHeight(int height) {
        if (detached) {
            return;
        }
        getElement().getStyle().setPropertyPx("maxHeight", height);
    }
}
