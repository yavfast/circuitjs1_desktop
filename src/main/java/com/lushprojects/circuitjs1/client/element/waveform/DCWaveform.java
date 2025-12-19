package com.lushprojects.circuitjs1.client.element.waveform;

import com.lushprojects.circuitjs1.client.element.VoltageElm;
import com.lushprojects.circuitjs1.client.element.RailElm;
import com.lushprojects.circuitjs1.client.element.VarRailElm;

import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;
import com.lushprojects.circuitjs1.client.util.Locale;

public class DCWaveform extends Waveform {
    @Override
    public int getType() {
        return VoltageElm.WF_DC;
    }

    @Override
    public double getVoltage(VoltageElm elm) {
        return elm.maxVoltage + elm.bias;
    }

    @Override
    public void draw(Graphics g, Point center, VoltageElm elm) {
        // DC is drawn differently in VoltageElm.draw(), but drawWaveform is called for non-DC.
        // However, RailElm might call drawWaveform for DC if not handled.
    }

    @Override
    public void drawRail(Graphics g, RailElm elm) {
        g.setColor(elm.needsHighlight() ? elm.selectColor() : elm.foregroundColor());
        elm.setPowerColor(g, false);
        double v = elm.getVoltage();
        String s;
        if (Math.abs(v) < 1)
            s = elm.showFormat(v) + " V";
        else
            s = elm.getShortUnitText(v, "V");
        if (v > 0)
            s = "+" + s;
        elm.drawLabeledNode(g, s, elm.point1, elm.lead1);
    }

    @Override
    public void getInfo(VoltageElm elm, String[] arr, int i) {
        arr[0] = "voltage source";
        if (elm.current != 0 && elm.circuitDocument.circuitInfo.showResistanceInVoltageSources) {
            arr[i] = "(R = " + elm.getUnitText(elm.maxVoltage / elm.current, Locale.ohmString) + ")";
        }
    }

    @Override
    public EditInfo getEditInfo(VoltageElm elm, int n) {
        if (n == 0) {
            return new EditInfo("Voltage", elm.maxVoltage, -20, 20, "V");
        }
        if (n == 2) {
            return new EditInfo("DC Offset (V)", elm.bias, -20, 20, "V");
        }
        return null;
    }

    @Override
    public void setEditValue(VoltageElm elm, int n, EditInfo ei) {
        if (n == 0) {
            elm.maxVoltage = ei.value;
        }
        if (n == 2) {
            elm.bias = ei.value;
        }
    }

    @Override
    public void stamp(VoltageElm elm) {
        elm.simulator().stampVoltageSource(elm.nodes[0], elm.nodes[1], elm.voltSource, elm.getVoltage());
    }

    @Override
    public void stampRail(RailElm elm) {
        elm.simulator().stampVoltageSource(0, elm.nodes[0], elm.voltSource, elm.getVoltage());
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
        return "VoltageSourceDC";
    }

    @Override
    public boolean isDC() {
        return true;
    }
}
