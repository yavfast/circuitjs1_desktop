package com.lushprojects.circuitjs1.client;

import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.user.client.Command;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Widget;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;
import com.lushprojects.circuitjs1.client.dialog.SlidersDialog;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.util.Locale;

import java.util.ArrayList;

// values with sliders
public class Adjustable extends BaseCirSimDelegate implements Command {
    CircuitElm elm;
    
    public CircuitElm getElm() {
        return elm;
    }
    
    public double minValue;
    public double maxValue;
    int flags;
    public String sliderText;

    // null if this Adjustable has its own slider, non-null if it's sharing another one.
    public Adjustable sharedSlider;

    final int FLAG_SHARED = 1;

    // index of value in getEditInfo() list that this slider controls
    int editItem;
    
    public int getEditItem() {
        return editItem;
    }

    public Label label, valueLabel;
    Scrollbar slider;
    Button editAdjustableButton, editElementButton;
    Widget row;
    boolean settingValue;

    public Adjustable(CirSim cirSim, CircuitElm ce, int item) {
        super(cirSim);
        minValue = 1;
        maxValue = 1000;
        flags = 0;
        elm = ce;
        editItem = item;
        EditInfo ei = ce.getEditInfo(editItem);
        if (ei != null && ei.maxVal > 0) {
            minValue = ei.minVal;
            maxValue = ei.maxVal;
        }
    }

    // undump
    public Adjustable(StringTokenizer st, CirSim sim) {
        super(sim);
        int e = Integer.parseInt(st.nextToken());
        if (e == -1)
            return;
        try {
            String ei = st.nextToken();

            // forgot to dump a "flags" field in the initial code, so we have to do this to support backward compatibility
            if (ei.startsWith("F")) {
                flags = Integer.parseInt(ei.substring(1));
                ei = st.nextToken();
            }

            editItem = Integer.parseInt(ei);
            minValue = Double.parseDouble(st.nextToken());
            maxValue = Double.parseDouble(st.nextToken());
            if ((flags & FLAG_SHARED) != 0) {
                int ano = Integer.parseInt(st.nextToken());
                sharedSlider = ano == -1 ? null : getActiveDocument().adjustableManager.adjustables.get(ano);
            }
            sliderText = CustomLogicModel.unescape(st.nextToken());
        } catch (Exception ex) {
        }
        try {
            elm = simulator().getElm(e);
        } catch (Exception ex) {
        }
    }

    boolean createSlider() {
        if (elm == null)
            return false;
        EditInfo ei = elm.getEditInfo(editItem);
        if (ei == null)
            return false;
        if (sharedSlider != null)
            return true;
        if (sliderText.isEmpty())
            return false;
        double value = ei.value;
        createSlider(value);
        return true;
    }

    public void createSlider(double value) {
        label = new Label(Locale.LS(sliderText));
        label.addStyleName("topSpace");
        valueLabel = new Label();
        valueLabel.addStyleName("topSpace");
        int intValue = (int) ((value - minValue) * 100 / (maxValue - minValue));
        slider = new Scrollbar(cirSim, Scrollbar.HORIZONTAL, intValue, 1, 0, 100, this, elm);

        editAdjustableButton = new Button("\u2699"); // Gear icon
        editAdjustableButton.addClickHandler(new ClickHandler() {
            public void onClick(ClickEvent event) {
                circuitEditor().doSliders(elm);
            }
        });

        editElementButton = new Button("\u270E"); // Pencil icon
        editElementButton.addClickHandler(new ClickHandler() {
            public void onClick(ClickEvent event) {
                circuitEditor().doEditElementOptions(elm);
            }
        });
        
        updateValueLabel();

        row = addSliderToDialog(label, valueLabel, slider, editAdjustableButton, editElementButton);
    }

    public Widget addSliderToDialog(Label titleLabel, Label valueLabel, Scrollbar slider, Button btn1, Button btn2) {
        CirSim cirSim = (CirSim) this.cirSim;
        SlidersDialog slidersDialog = cirSim.slidersDialog;
        if (slidersDialog == null) return null;
        Widget row = slidersDialog.addSlider(titleLabel, valueLabel, slider, btn1, btn2);
        if (!slidersDialog.isShowing()) {
            slidersDialog.show();
            cirSim.updateSlidersDialogPosition();
            cirSim.setSlidersDialogHeight();
        }
        return row;
    }

    public void removeSliderFromDialog(Widget row) {
        CirSim cirSim = (CirSim) this.cirSim;
        SlidersDialog slidersDialog = cirSim.slidersDialog;
        if (slidersDialog == null) return;
        slidersDialog.removeSlider(row);
        if (slidersDialog.isEmpty()) {
            slidersDialog.hide();
        }
    }

    public void setSliderValue(double value) {
        if (sharedSlider != null) {
            sharedSlider.setSliderValue(value);
            return;
        }
        int intValue = (int) ((value - minValue) * 100 / (maxValue - minValue));
        settingValue = true; // don't recursively set value again in execute()
        slider.setValue(intValue);
        updateValueLabel();
        settingValue = false;
    }

    public void execute() {
        if (settingValue) {
            return;
        }
        ArrayList<Adjustable> adjustables = getActiveDocument().adjustableManager.adjustables;
        for (int i = 0; i != adjustables.size(); i++) {
            Adjustable adj = adjustables.get(i);
            if (adj == this || adj.sharedSlider == this) {
                adj.executeSlider();
            }
        }
    }

    void executeSlider() {
        renderer().needsAnalysis();
        EditInfo ei = elm.getEditInfo(editItem);
        ei.value = getSliderValue();
        elm.setEditValue(editItem, ei);
        updateValueLabel();
        cirSim.repaint();
    }

    void updateValueLabel() {
        if (valueLabel == null) return;
        EditInfo ei = elm.getEditInfo(editItem);
        String valueString;
        if (ei != null && ei.unit != null) {
            valueString = CircuitElm.getUnitText(ei.value, ei.unit);
        } else {
            // format to 2 decimal places
            valueString = String.valueOf(Math.round(getSliderValue() * 100) / 100.0);
        }
        valueLabel.setText(valueString);
    }

    public double getSliderValue() {
        double val = sharedSlider == null ? slider.getValue() : sharedSlider.slider.getValue();
        return minValue + (maxValue - minValue) * val / 100;
    }

    public void deleteSlider() {
        if (row == null) {
            return;
        }
        removeSliderFromDialog(row);
    }

    void setMouseElm(CircuitElm e) {
        if (slider != null)
            slider.draw();
    }

    public boolean sliderBeingShared() {
        ArrayList<Adjustable> adjustables = getActiveDocument().adjustableManager.adjustables;
        for (int i = 0; i != adjustables.size(); i++) {
            Adjustable adj = adjustables.get(i);
            if (adj.sharedSlider == this)
                return true;
        }
        return false;
    }

    String dump() {
        int ano = -1;
        if (sharedSlider != null) {
            ano = getActiveDocument().adjustableManager.adjustables.indexOf(sharedSlider);
        }

        return simulator().locateElm(elm) + " F1 " + editItem + " " + minValue + " " + maxValue + " " + ano + " " +
                CustomLogicModel.escape(sliderText);
    }

}
