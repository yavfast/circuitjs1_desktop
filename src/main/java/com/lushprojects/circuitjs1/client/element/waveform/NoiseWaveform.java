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
        return Waveform.WF_NOISE;
    }

    @Override
    public double getVoltage(VoltageElm elm) {
        return noiseValue;
    }

    @Override
    public boolean hasCircle() {
        return false;
    }

    @Override
    public boolean showFrequency() {
        return false;
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
        if (n == 0) return new EditInfo("Max Voltage", maxVoltage, -20, 20, "V");
        if (n == 2) return new EditInfo("DC Offset (V)", bias, -20, 20, "V");
        return null;
    }

    @Override
    public void setEditValue(VoltageElm elm, int n, EditInfo ei) {
        if (n == 0) maxVoltage = ei.value;
        if (n == 2) bias = ei.value;
    }

    @Override
    public void getJsonProperties(VoltageElm elm, java.util.Map<String, Object> props) {
        props.put("max_voltage", elm.getUnitText(maxVoltage, "V"));
        if (bias != 0) {
            props.put("dc_offset", elm.getUnitText(bias, "V"));
        }
    }

    @Override
    public void stepFinished(VoltageElm elm) {
        noiseValue = (RandomUtils.getRandom().nextDouble() * 2 - 1) * maxVoltage + bias;
    }

    @Override
    public String getJsonTypeName() {
        return "VoltageSourceNoise";
    }
}
