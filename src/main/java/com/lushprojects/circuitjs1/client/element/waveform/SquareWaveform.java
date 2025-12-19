package com.lushprojects.circuitjs1.client.element.waveform;

import com.lushprojects.circuitjs1.client.element.VoltageElm;
import com.lushprojects.circuitjs1.client.element.RailElm;
import com.lushprojects.circuitjs1.client.element.VarRailElm;

import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public class SquareWaveform extends Waveform {
    @Override
    public int getType() {
        return Waveform.WF_SQUARE;
    }

    @Override
    public double getVoltage(VoltageElm elm) {
        if (elm.circuitDocument.circuitInfo.dcAnalysisFlag) {
            return bias;
        }
        return bias + ((w(elm) % VoltageElm.PI_2 > (VoltageElm.PI_2 * dutyCycle)) ? -maxVoltage : maxVoltage);
    }

    @Override
    public void draw(Graphics g, Point center, VoltageElm elm) {
        int xc = center.x;
        int yc = center.y;
        int wl = 8;
        int xc2 = (int) (wl * 2 * dutyCycle - wl + xc);
        xc2 = Math.max(xc - wl + 3, Math.min(xc + wl - 3, xc2));
        VoltageElm.drawThickLine(g, xc - wl, yc - wl, xc - wl, yc);
        VoltageElm.drawThickLine(g, xc - wl, yc - wl, xc2, yc - wl);
        VoltageElm.drawThickLine(g, xc2, yc - wl, xc2, yc + wl);
        VoltageElm.drawThickLine(g, xc + wl, yc + wl, xc2, yc + wl);
        VoltageElm.drawThickLine(g, xc + wl, yc, xc + wl, yc + wl);
    }

    @Override
    public void drawRail(Graphics g, RailElm elm) {
        if ((elm.flags & RailElm.FLAG_CLOCK) != 0) {
            elm.drawRailText(g, "CLK");
        } else {
            super.drawRail(g, elm);
        }
    }

    @Override
    public void getInfo(VoltageElm elm, String[] arr, int i) {
        arr[0] = "square wave gen";
        arr[i++] = "f = " + elm.getUnitText(frequency, "Hz");
        arr[i++] = "Vmax = " + elm.getVoltageText(maxVoltage);
        if (bias != 0) {
            arr[i++] = "Voff = " + elm.getVoltageText(bias);
        }
    }

    @Override
    public EditInfo getEditInfo(VoltageElm elm, int n) {
        if (n == 0) return new EditInfo("Max Voltage", maxVoltage, -20, 20, "V");
        if (n == 2) return new EditInfo("DC Offset (V)", bias, -20, 20, "V");
        if (n == 3) return new EditInfo("Frequency (Hz)", frequency, 4, 500, "Hz");
        if (n == 4) return new EditInfo("Phase Offset (degrees)", phaseShift * 180 / Math.PI, -180, 180).setDimensionless();
        if (n == 5) return new EditInfo("Duty Cycle", dutyCycle * 100, 0, 100).setDimensionless();
        return null;
    }

    @Override
    public void setEditValue(VoltageElm elm, int n, EditInfo ei) {
        if (n == 0) maxVoltage = ei.value;
        if (n == 2) bias = ei.value;
        if (n == 3) frequency = ei.value;
        if (n == 4) phaseShift = ei.value * Math.PI / 180;
        if (n == 5) dutyCycle = ei.value * .01;
    }

    @Override
    public void getJsonProperties(VoltageElm elm, java.util.Map<String, Object> props) {
        super.getJsonProperties(elm, props);
        props.put("duty_cycle", dutyCycle);
    }

    @Override
    public String getJsonTypeName() {
        return "VoltageSourceSquare";
    }

    @Override
    public String getJsonRailTypeName() {
        return "SquareRail";
    }
}
