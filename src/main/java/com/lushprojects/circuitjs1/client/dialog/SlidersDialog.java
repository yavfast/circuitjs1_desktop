package com.lushprojects.circuitjs1.client.dialog;

import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.HasHorizontalAlignment;
import com.google.gwt.user.client.ui.HasVerticalAlignment;
import com.google.gwt.user.client.ui.HorizontalPanel;
import com.google.gwt.user.client.ui.Label;
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
