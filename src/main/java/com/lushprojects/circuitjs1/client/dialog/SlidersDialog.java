package com.lushprojects.circuitjs1.client.dialog;

import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.DialogBox;
import com.google.gwt.user.client.ui.HasHorizontalAlignment;
import com.google.gwt.user.client.ui.HasVerticalAlignment;
import com.google.gwt.user.client.ui.HorizontalPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.VerticalPanel;
import com.google.gwt.user.client.ui.Widget;
import com.lushprojects.circuitjs1.client.Scrollbar;
import com.lushprojects.circuitjs1.client.element.CircuitElm;

public class SlidersDialog extends DialogBox {
    private VerticalPanel panel;

    public SlidersDialog() {
        super(false, false); // autoHide, modal
        setText("Adjustable Sliders");
        panel = new VerticalPanel();
        setWidget(panel);
        
        getElement().getStyle().setProperty("overflowY", "auto");
    }

    public Widget addSlider(Label titleLabel, Label valueLabel, Scrollbar slider, Button editAdjustableButton, Button editElementButton) {
        VerticalPanel row = new VerticalPanel();
        row.setWidth("100%");
        
        HorizontalPanel hpanel = new HorizontalPanel();
        hpanel.setVerticalAlignment(HasVerticalAlignment.ALIGN_MIDDLE);
        hpanel.setSpacing(5);
        hpanel.setWidth("100%");
        
        slider.setWidth("100%");
        
        hpanel.add(slider);
        hpanel.add(editAdjustableButton);
        hpanel.add(editElementButton);
        hpanel.setCellWidth(slider, "100%");

        row.add(titleLabel);
        row.add(valueLabel);
        row.add(hpanel);
        row.setCellHorizontalAlignment(valueLabel, HasHorizontalAlignment.ALIGN_CENTER);
        
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
