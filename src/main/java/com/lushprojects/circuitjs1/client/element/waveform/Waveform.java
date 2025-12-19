package com.lushprojects.circuitjs1.client.element.waveform;

import com.lushprojects.circuitjs1.client.element.VoltageElm;
import com.lushprojects.circuitjs1.client.element.RailElm;
import com.lushprojects.circuitjs1.client.element.VarRailElm;

import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public abstract class Waveform {
    public abstract int getType();

    public abstract double getVoltage(VoltageElm elm);

    public abstract void draw(Graphics g, Point center, VoltageElm elm);

    public void drawRail(Graphics g, RailElm elm) {
        elm.drawWaveform(g, elm.point2);
    }

    public abstract void getInfo(VoltageElm elm, String[] arr, int i);

    public abstract EditInfo getEditInfo(VoltageElm elm, int n);

    public abstract void setEditValue(VoltageElm elm, int n, EditInfo ei);

    public abstract String getJsonTypeName();

    public void getJsonProperties(VoltageElm elm, java.util.Map<String, Object> props) {
        props.put("max_voltage", elm.getUnitText(elm.maxVoltage, "V"));
        if (elm.bias != 0) {
            props.put("dc_offset", elm.getUnitText(elm.bias, "V"));
        }
        props.put("frequency", elm.getUnitText(elm.frequency, "Hz"));
        if (elm.phaseShift != 0) {
            props.put("phase_shift", elm.phaseShift * 180 / Math.PI);
        }
    }

    public String getJsonRailTypeName() {
        return "Rail";
    }

    public boolean isDC() {
        return false;
    }

    public boolean isPulse() {
        return false;
    }

    public void stamp(VoltageElm elm) {
        elm.simulator().stampVoltageSource(elm.nodes[0], elm.nodes[1], elm.voltSource);
    }

    public void stampRail(RailElm elm) {
        elm.simulator().stampVoltageSource(0, elm.nodes[0], elm.voltSource);
    }

    public void stepFinished(VoltageElm elm) {
    }

    protected double w(VoltageElm elm) {
        return elm.w();
    }
}
