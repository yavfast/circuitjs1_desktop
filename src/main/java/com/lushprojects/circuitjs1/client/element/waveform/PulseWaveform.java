package com.lushprojects.circuitjs1.client.element.waveform;

import com.lushprojects.circuitjs1.client.element.VoltageElm;

import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public class PulseWaveform extends Waveform {
    @Override
    public int getType() {
        return Waveform.WF_PULSE;
    }

    @Override
    public double getVoltage(VoltageElm elm) {
        if (elm.circuitDocument.circuitInfo.dcAnalysisFlag) {
            return bias;
        }
        return ((w(elm) % VoltageElm.PI_2) < (VoltageElm.PI_2 * dutyCycle)) ? maxVoltage + bias : bias;
    }

    @Override
    public void draw(Graphics g, Point center, VoltageElm elm) {
        int xc = center.x;
        int yc = center.y;
        int wl = 8;
        yc += wl / 2;
        VoltageElm.drawThickLine(g, xc - wl, yc - wl, xc - wl, yc);
        VoltageElm.drawThickLine(g, xc - wl, yc - wl, xc - wl / 2, yc - wl);
        VoltageElm.drawThickLine(g, xc - wl / 2, yc - wl, xc - wl / 2, yc);
        VoltageElm.drawThickLine(g, xc - wl / 2, yc, xc + wl, yc);
    }

    @Override
    public void getInfo(VoltageElm elm, String[] arr, int i) {
        arr[0] = "pulse gen";
        arr[i++] = "f = " + VoltageElm.getUnitText(frequency, "Hz");
        arr[i++] = "Vmax = " + VoltageElm.getVoltageText(maxVoltage);
        if (bias != 0) {
            arr[i++] = "Voff = " + VoltageElm.getVoltageText(bias);
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
    public boolean isPulse() {
        return true;
    }

    @Override
    public void getJsonProperties(VoltageElm elm, java.util.Map<String, Object> props) {
        super.getJsonProperties(elm, props);
        props.put("duty_cycle", dutyCycle);
    }

    @Override
    public String getJsonTypeName() {
        return "VoltageSourcePulse";
    }
}
