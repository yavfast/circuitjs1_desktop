package com.lushprojects.circuitjs1.client.element.waveform;

import com.lushprojects.circuitjs1.client.element.VoltageElm;
import com.lushprojects.circuitjs1.client.element.RailElm;
import com.lushprojects.circuitjs1.client.element.VarRailElm;

import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.RandomUtils;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;
import com.lushprojects.circuitjs1.client.util.Locale;

public class NoiseWaveform extends Waveform {
    @Override
    public int getType() {
        return VoltageElm.WF_NOISE;
    }

    @Override
    public double getVoltage(VoltageElm elm) {
        return elm.noiseValue;
    }

    @Override
    public void draw(Graphics g, Point center, VoltageElm elm) {
        g.setColor(elm.needsHighlight() ? elm.selectColor() : elm.foregroundColor());
        elm.setPowerColor(g, false);
        elm.drawLabeledNode(g, Locale.LS("Noise"), elm.point1, elm.lead1);
    }

    @Override
    public void getInfo(VoltageElm elm, String[] arr, int i) {
        arr[0] = "noise gen";
    }

    @Override
    public EditInfo getEditInfo(VoltageElm elm, int n) {
        if (n == 0) return new EditInfo("Max Voltage", elm.maxVoltage, -20, 20, "V");
        if (n == 2) return new EditInfo("DC Offset (V)", elm.bias, -20, 20, "V");
        return null;
    }

    @Override
    public void setEditValue(VoltageElm elm, int n, EditInfo ei) {
        if (n == 0) elm.maxVoltage = ei.value;
        if (n == 2) elm.bias = ei.value;
    }

    @Override
    public void getJsonProperties(VoltageElm elm, java.util.Map<String, Object> props) {
        props.put("max_voltage", elm.getUnitText(elm.maxVoltage, "V"));
        if (elm.bias != 0) {
            props.put("dc_offset", elm.getUnitText(elm.bias, "V"));
        }
    }

    @Override
    public void stepFinished(VoltageElm elm) {
        elm.noiseValue = (RandomUtils.getRandom().nextDouble() * 2 - 1) * elm.maxVoltage + elm.bias;
    }

    @Override
    public String getJsonTypeName() {
        return "VoltageSourceNoise";
    }
}
