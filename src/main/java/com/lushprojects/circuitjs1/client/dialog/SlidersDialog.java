package com.lushprojects.circuitjs1.client.dialog;

import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.user.client.ui.DialogBox;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.VerticalPanel;
import com.lushprojects.circuitjs1.client.Scrollbar;

public class SlidersDialog extends DialogBox {
    private VerticalPanel panel;

    public SlidersDialog() {
        super(false, false); // autoHide, modal
        setText("Adjustable Sliders");
        panel = new VerticalPanel();
        setWidget(panel);
        setStyleName("sliders-dialog");
        getElement().getStyle().setProperty("overflowY", "auto");
    }

    public void addSlider(Label label, Scrollbar slider) {
        panel.add(label);
        panel.add(slider);
    }

    public void removeSlider(Label label, Scrollbar slider) {
        panel.remove(label);
        panel.remove(slider);
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
