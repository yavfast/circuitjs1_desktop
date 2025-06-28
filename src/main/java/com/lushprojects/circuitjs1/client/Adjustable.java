package com.lushprojects.circuitjs1.client;

import com.google.gwt.user.client.Command;
import com.google.gwt.user.client.ui.Label;
import com.lushprojects.circuitjs1.client.util.Locale;

import java.util.Vector;

// values with sliders
public class Adjustable extends BaseCirSimDelegate implements Command {
    CircuitElm elm;
    double minValue, maxValue;
    int flags;
    String sliderText;

    // null if this Adjustable has its own slider, non-null if it's sharing another one.
    Adjustable sharedSlider;

    final int FLAG_SHARED = 1;

    // index of value in getEditInfo() list that this slider controls
    int editItem;

    Label label;
    Scrollbar slider;
    boolean settingValue;

    Adjustable(CirSim cirSim, CircuitElm ce, int item) {
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
    Adjustable(StringTokenizer st, CirSim sim) {
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
                sharedSlider = ano == -1 ? null : sim.adjustableManager.adjustables.get(ano);
            }
            sliderText = CustomLogicModel.unescape(st.nextToken());
        } catch (Exception ex) {
        }
        try {
            elm = sim.getElm(e);
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
        if (sliderText.length() == 0)
            return false;
        double value = ei.value;
        createSlider(value);
        return true;
    }

    void createSlider(double value) {
        cirSim.addWidgetToVerticalPanel(label = new Label(Locale.LS(sliderText)));
        label.addStyleName("topSpace");
        int intValue = (int) ((value - minValue) * 100 / (maxValue - minValue));
        cirSim.addWidgetToVerticalPanel(slider = new Scrollbar(Scrollbar.HORIZONTAL, intValue, 1, 0, 101, this, elm));
    }

    void setSliderValue(double value) {
        if (sharedSlider != null) {
            sharedSlider.setSliderValue(value);
            return;
        }
        int intValue = (int) ((value - minValue) * 100 / (maxValue - minValue));
        settingValue = true; // don't recursively set value again in execute()
        slider.setValue(intValue);
        settingValue = false;
    }

    public void execute() {
        if (settingValue)
            return;
        int i;
        Vector<Adjustable> adjustables = cirSim.adjustableManager.adjustables;
        for (i = 0; i != adjustables.size(); i++) {
            Adjustable adj = adjustables.get(i);
            if (adj == this || adj.sharedSlider == this)
                adj.executeSlider();
        }
    }

    void executeSlider() {
        renderer().analyzeFlag = true;
        EditInfo ei = elm.getEditInfo(editItem);
        ei.value = getSliderValue();
        elm.setEditValue(editItem, ei);
        cirSim.repaint();
    }

    double getSliderValue() {
        double val = sharedSlider == null ? slider.getValue() : sharedSlider.slider.getValue();
        return minValue + (maxValue - minValue) * val / 100;
    }

    void deleteSlider(CirSim sim) {
        try {
            sim.removeWidgetFromVerticalPanel(label);
            sim.removeWidgetFromVerticalPanel(slider);
        } catch (Exception e) {
        }
    }

    void setMouseElm(CircuitElm e) {
        if (slider != null)
            slider.draw();
    }

    boolean sliderBeingShared() {
        Vector<Adjustable> adjustables = cirSim.adjustableManager.adjustables;
        for (int i = 0; i != adjustables.size(); i++) {
            Adjustable adj = adjustables.get(i);
            if (adj.sharedSlider == this)
                return true;
        }
        return false;
    }

    String dump() {
        int ano = -1;
        if (sharedSlider != null)
            ano = cirSim.adjustableManager.adjustables.indexOf(sharedSlider);

        return simulator().locateElm(elm) + " F1 " + editItem + " " + minValue + " " + maxValue + " " + ano + " " +
                CustomLogicModel.escape(sliderText);
    }

}
