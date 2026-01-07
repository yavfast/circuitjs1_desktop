package com.lushprojects.circuitjs1.client.element.waveform;

import com.lushprojects.circuitjs1.client.element.VoltageElm;

import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public class ACWaveform extends Waveform {
    @Override
    public int getType() {
        return Waveform.WF_AC;
    }

    @Override
    public double getVoltage(VoltageElm elm) {
        if (elm.circuitDocument.circuitInfo.dcAnalysisFlag) {
            return bias;
        }
        return Math.sin(w(elm)) * maxVoltage + bias;
    }

    @Override
    public void draw(Graphics g, Point center, VoltageElm elm) {
        int xc = center.x;
        int yc = center.y;
        int wl = 8;
        int xl = 10;
        g.beginPath();
        g.setLineWidth(3.0);
        for (int i = -xl; i <= xl; i++) {
            int yy = yc + (int) (.95 * Math.sin(i * Math.PI / xl) * wl);
            if (i == -xl) {
                g.moveTo(xc + i, yy);
            } else {
                g.lineTo(xc + i, yy);
            }
        }
        g.stroke();
        g.setLineWidth(1.0);
    }

    @Override
    public void getInfo(VoltageElm elm, String[] arr, int i) {
        arr[0] = "A/C source";
        arr[i++] = "f = " + VoltageElm.getUnitText(frequency, "Hz");
        arr[i++] = "Vmax = " + VoltageElm.getVoltageText(maxVoltage);
        if (bias == 0) {
            arr[i++] = "V(rms) = " + VoltageElm.getVoltageText(maxVoltage / 1.41421356);
        }
        if (bias != 0) {
            arr[i++] = "Voff = " + VoltageElm.getVoltageText(bias);
        } else if (frequency > 500) {
            arr[i++] = "wavelength = " + VoltageElm.getUnitText(2.9979e8 / frequency, "m");
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
        return "VoltageSourceAC";
    }

    @Override
    public String getJsonRailTypeName() {
        return "ACRail";
    }
}
