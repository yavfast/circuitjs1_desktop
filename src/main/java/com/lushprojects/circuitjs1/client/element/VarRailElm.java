/*    
    Copyright (C) Paul Falstad and Iain Sharp
    
    This file is part of CircuitJS1.

    CircuitJS1 is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 2 of the License, or
    (at your option) any later version.

    CircuitJS1 is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with CircuitJS1.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.lushprojects.circuitjs1.client.element;

import com.lushprojects.circuitjs1.client.Adjustable;
import com.lushprojects.circuitjs1.client.CircuitDocument;

import com.google.gwt.event.dom.client.MouseWheelEvent;
import com.google.gwt.event.dom.client.MouseWheelHandler;
import com.google.gwt.user.client.ui.Label;
import com.lushprojects.circuitjs1.client.Scrollbar;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;
import com.lushprojects.circuitjs1.client.element.waveform.Waveform;
import com.lushprojects.circuitjs1.client.util.Locale;

public class VarRailElm extends RailElm implements MouseWheelHandler {
    public Scrollbar slider;
    public Label label;
    public String sliderText;

    private static final int EDIT_MIN_VOLTAGE = 0;
    private static final int EDIT_MAX_VOLTAGE = 1;
    private static final int EDIT_SLIDER_TEXT = 2;
    public static final int EDIT_VOLTAGE = 3;

    public VarRailElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy, Waveform.WF_DC);
        sliderText = "Voltage";
        waveformInstance.frequency = waveformInstance.maxVoltage;
        createSlider();
    }

    public VarRailElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
                      StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f, st);
        sliderText = st.nextToken();
        while (st.hasMoreTokens())
            sliderText += ' ' + st.nextToken();
        sliderText = sliderText.replaceAll("%2[bB]", "+");
        createSlider();
    }

    public String dump() {
        return dumpValues(super.dump(), sliderText.replaceAll("\\+", "%2B"));
    }

    int getDumpType() {
        return 172;
    }

    void createSlider() {
        // Variable rails are conceptually a DC source whose voltage is controlled by a UI slider.
        // Old text-format files historically used a special waveform index for variable rails; after
        // waveform refactors that index can be misinterpreted.
        //
        // Keep the legacy parameters parsed by VoltageElm (bias=min, maxVoltage=max, frequency=current)
        // but treat the waveform itself as DC.
        waveform = Waveform.WF_DC;
        createWaveformInstance();

        // UI for VarRail voltage is managed via the standard Sliders dialog (Adjustable).
        // We intentionally do not create a separate vertical-panel slider here to avoid duplicates.
//	    sim.verticalPanel.validate();
    }

    public void ensureVoltageAdjustable(boolean refreshSliders) {
        if (circuitDocument == null || circuitDocument.adjustableManager == null) {
            return;
        }

        Adjustable adj = circuitDocument.adjustableManager.findAdjustable(this, EDIT_VOLTAGE);
        if (adj == null) {
            adj = new Adjustable(cirSim(), this, EDIT_VOLTAGE);
            circuitDocument.adjustableManager.adjustables.add(adj);
        }
        adj.sliderText = sliderText;
        adj.minValue = waveformInstance.bias;
        adj.maxValue = waveformInstance.maxVoltage;

        if (refreshSliders) {
            circuitDocument.adjustableManager.updateSliders();
        }
    }

    @Override
    public EditInfo getEditInfo(int n) {
        if (waveformInstance == null) {
            return null;
        }

        // Variable rails expose min/max range + slider label. The actual voltage is controlled by the slider.
        if (n == EDIT_MIN_VOLTAGE) {
            return new EditInfo("Min Voltage", waveformInstance.bias, -20, 20, "V").disallowSliders();
        }
        if (n == EDIT_MAX_VOLTAGE) {
            return new EditInfo("Max Voltage", waveformInstance.maxVoltage, -20, 20, "V").disallowSliders();
        }
        if (n == EDIT_SLIDER_TEXT) {
            EditInfo ei = new EditInfo("Slider Text", 0, -1, -1);
            ei.text = sliderText;
            ei.disallowSliders();
            return ei;
        }
        if (n == EDIT_VOLTAGE) {
            // The current output voltage. This is what the Sliders dialog controls.
            // Use the configured min/max range so the adjustable slider matches the rail limits.
            return new EditInfo("Voltage", waveformInstance.frequency, waveformInstance.bias, waveformInstance.maxVoltage, "V");
        }
        return null;
    }

    @Override
    public void setEditValue(int n, EditInfo ei) {
        if (waveformInstance == null) {
            return;
        }

        if (n == EDIT_MIN_VOLTAGE) {
            waveformInstance.bias = ei.value;
        } else if (n == EDIT_MAX_VOLTAGE) {
            waveformInstance.maxVoltage = ei.value;
        } else if (n == EDIT_SLIDER_TEXT) {
            sliderText = ei.textf.getText();
            if (label != null) {
                label.setText(Locale.LS(sliderText));
            }
        } else if (n == EDIT_VOLTAGE) {
            waveformInstance.frequency = ei.value;
        }

        // Keep range sane.
        if (waveformInstance.maxVoltage < waveformInstance.bias) {
            double t = waveformInstance.maxVoltage;
            waveformInstance.maxVoltage = waveformInstance.bias;
            waveformInstance.bias = t;
        }

        // Sync the UI slider position to the current voltage.
        if (slider != null) {
            double min = waveformInstance.bias;
            double max = waveformInstance.maxVoltage;
            int value = 0;
            if (max != min) {
                value = (int) ((waveformInstance.frequency - min) * 100 / (max - min));
            }
            slider.setValue(value);
        }

        // Keep the adjustable slider (if present) in sync with range/label changes.
        if (n == EDIT_MIN_VOLTAGE || n == EDIT_MAX_VOLTAGE || n == EDIT_SLIDER_TEXT) {
            ensureVoltageAdjustable(true);
        }
    }

    @Override
    public double getVoltage() {
        if (waveformInstance == null) {
            return 0;
        }

        // Preserve legacy meaning of parameters for VarRail:
        // - bias: min voltage
        // - maxVoltage: max voltage
        // - frequency: current slider voltage
        // The current output voltage is stored in waveformInstance.frequency and is controlled
        // by the standard Adjustable/Sliders dialog.
        return waveformInstance.frequency;
    }

    @Override
    public void doStep() {
        // Even though the underlying waveform is DC, the slider can change the output voltage.
        // Ensure the voltage source is updated every simulation step.
        simulator().updateVoltageSource(0, getNode(0), voltSource, getVoltage());
    }

    public void delete() {
        if (label != null) {
            cirSim().removeWidgetFromVerticalPanel(label);
        }
        if (slider != null) {
            cirSim().removeWidgetFromVerticalPanel(slider);
        }

        if (circuitDocument != null && circuitDocument.adjustableManager != null) {
            circuitDocument.adjustableManager.deleteSliders(this);
        }
        super.delete();
    }

    public int getShortcut() {
        return 0;
    }

    public void setMouseElm(boolean v) {
        super.setMouseElm(v);
        if (slider != null)
            slider.draw();
    }

    public void onMouseWheel(MouseWheelEvent e) {
        if (slider != null)
            slider.onMouseWheel(e);
    }

    @Override
    public String getJsonTypeName() {
        return "VariableRail";
    }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        props.put("slider_text", sliderText);
        return props;
    }

    @Override
    public void applyJsonProperties(java.util.Map<String, Object> properties) {
        super.applyJsonProperties(properties);
        sliderText = getJsonString(properties, "slider_text", sliderText);
        if (label != null) {
            label.setText(Locale.LS(sliderText));
        }
    }
}
