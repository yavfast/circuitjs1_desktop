package com.lushprojects.circuitjs1.client.element.waveform;

import com.lushprojects.circuitjs1.client.element.VoltageElm;
import com.lushprojects.circuitjs1.client.element.RailElm;
import com.lushprojects.circuitjs1.client.element.VarRailElm;

import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public class SawtoothWaveform extends Waveform {
    @Override
    public int getType() {
        return Waveform.WF_SAWTOOTH;
    }

    @Override
    public double getVoltage(VoltageElm elm) {
        if (elm.circuitDocument.circuitInfo.dcAnalysisFlag) {
            return bias;
        }
        return bias + (w(elm) % VoltageElm.PI_2) * (maxVoltage / Math.PI) - maxVoltage;
    }

    @Override
    public void draw(Graphics g, Point center, VoltageElm elm) {
        int xc = center.x;
        int yc = center.y;
        int wl = 8;
        VoltageElm.drawThickLine(g, xc, yc - wl, xc - wl, yc);
        VoltageElm.drawThickLine(g, xc, yc - wl, xc, yc + wl);
        VoltageElm.drawThickLine(g, xc, yc + wl, xc + wl, yc);
    }

    @Override
    public void getInfo(VoltageElm elm, String[] arr, int i) {
        arr[0] = "sawtooth gen";
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
        return null;
    }

    @Override
    public void setEditValue(VoltageElm elm, int n, EditInfo ei) {
        if (n == 0) maxVoltage = ei.value;
        if (n == 2) bias = ei.value;
        if (n == 3) frequency = ei.value;
        if (n == 4) phaseShift = ei.value * Math.PI / 180;
    }

    @Override
    public String getJsonTypeName() {
        return "VoltageSourceSawtooth";
    }
}
