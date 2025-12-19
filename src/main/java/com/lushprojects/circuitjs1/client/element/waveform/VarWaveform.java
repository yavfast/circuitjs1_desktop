package com.lushprojects.circuitjs1.client.element.waveform;

import com.lushprojects.circuitjs1.client.element.VoltageElm;
import com.lushprojects.circuitjs1.client.element.RailElm;
import com.lushprojects.circuitjs1.client.element.VarRailElm;

import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public class VarWaveform extends Waveform {
    @Override
    public int getType() {
        return VoltageElm.WF_VAR;
    }

    @Override
    public double getVoltage(VoltageElm elm) {
        if (elm instanceof VarRailElm) {
            VarRailElm vrelm = (VarRailElm) elm;
            elm.frequency = vrelm.slider.getValue() * (elm.maxVoltage - elm.bias) / 100. + elm.bias;
            return elm.frequency;
        }
        return elm.maxVoltage + elm.bias;
    }

    @Override
    public void draw(Graphics g, Point center, VoltageElm elm) {
        // VarRailElm usually draws as a rail.
    }

    @Override
    public void getInfo(VoltageElm elm, String[] arr, int i) {
        arr[0] = "voltage source";
    }

    @Override
    public EditInfo getEditInfo(VoltageElm elm, int n) {
        if (elm instanceof VarRailElm) {
            VarRailElm vrelm = (VarRailElm) elm;
            if (n == 0) return new EditInfo("Min Voltage", elm.bias, -20, 20);
            if (n == 1) return new EditInfo("Max Voltage", elm.maxVoltage, -20, 20);
            if (n == 2) {
                EditInfo ei = new EditInfo("Slider Text", 0, -1, -1);
                ei.text = vrelm.sliderText;
                return ei;
            }
        }
        return null;
    }

    @Override
    public void setEditValue(VoltageElm elm, int n, EditInfo ei) {
        if (elm instanceof VarRailElm) {
            VarRailElm vrelm = (VarRailElm) elm;
            if (n == 0) elm.bias = ei.value;
            if (n == 1) elm.maxVoltage = ei.value;
            if (n == 2) {
                vrelm.sliderText = ei.textf.getText();
                vrelm.label.setText(com.lushprojects.circuitjs1.client.util.Locale.LS(vrelm.sliderText));
                vrelm.cirSim().setSlidersDialogHeight();
            }
        }
    }

    @Override
    public void getJsonProperties(VoltageElm elm, java.util.Map<String, Object> props) {
        props.put("max_voltage", elm.getUnitText(elm.maxVoltage, "V"));
        if (elm.bias != 0) {
            props.put("dc_offset", elm.getUnitText(elm.bias, "V"));
        }
    }

    @Override
    public String getJsonTypeName() {
        return "VoltageSourceVar";
    }

    @Override
    public String getJsonRailTypeName() {
        return "VariableRail";
    }
}
