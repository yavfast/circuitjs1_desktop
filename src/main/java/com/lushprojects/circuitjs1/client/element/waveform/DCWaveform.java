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
        return Waveform.WF_DC;
    }

    @Override
    public double getVoltage(VoltageElm elm) {
        return maxVoltage + bias;
    }

    @Override
    public void draw(Graphics g, Point center, VoltageElm elm) {
        // DC is drawn differently in VoltageElm.draw(), but drawWaveform is called for
        // non-DC.
        // However, RailElm might call drawWaveform for DC if not handled.
    }

    @Override
    public boolean hasCircle() {
        // DC sources are drawn battery-style (no waveform circle).
        return false;
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

        // RailElm draws from its post to a derived lead point (railLead). Using VoltageElm lead points
        // can collapse the lead and misplace the label.
        elm.drawRailText(g, s);
    }

    @Override
    public void getInfo(VoltageElm elm, String[] arr, int i) {
        arr[0] = "voltage source";
        if (elm.current != 0 && elm.circuitDocument.circuitInfo.showResistanceInVoltageSources) {
            arr[i] = "(R = " + elm.getUnitText(maxVoltage / elm.current, Locale.ohmString) + ")";
        }
    }

    @Override
    public EditInfo getEditInfo(VoltageElm elm, int n) {
        if (n == 0) {
            return new EditInfo("Voltage", maxVoltage, -20, 20, "V");
        }
        if (n == 2) {
            return new EditInfo("DC Offset (V)", bias, -20, 20, "V");
        }
        return null;
    }

    @Override
    public void setEditValue(VoltageElm elm, int n, EditInfo ei) {
        if (n == 0) {
            maxVoltage = ei.value;
        }
        if (n == 2) {
            bias = ei.value;
        }
    }

    @Override
    public void stamp(VoltageElm elm) {
        elm.simulator().stampVoltageSource(elm.getNode(0), elm.getNode(1), elm.voltSource, elm.getVoltage());
    }

    @Override
    public void stampRail(RailElm elm) {
        elm.simulator().stampVoltageSource(0, elm.getNode(0), elm.voltSource, elm.getVoltage());
    }

    @Override
    public void getJsonProperties(VoltageElm elm, java.util.Map<String, Object> props) {
        props.put("max_voltage", elm.getUnitText(maxVoltage, "V"));
        if (bias != 0) {
            props.put("dc_offset", elm.getUnitText(bias, "V"));
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
