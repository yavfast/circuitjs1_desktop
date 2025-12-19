package com.lushprojects.circuitjs1.client.element.waveform;

import com.lushprojects.circuitjs1.client.element.VoltageElm;
import com.lushprojects.circuitjs1.client.element.RailElm;
import com.lushprojects.circuitjs1.client.element.VarRailElm;

import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public class ACWaveform extends Waveform {
    @Override
    public int getType() {
        return VoltageElm.WF_AC;
    }

    @Override
    public double getVoltage(VoltageElm elm) {
        if (elm.circuitDocument.circuitInfo.dcAnalysisFlag) {
            return elm.bias;
        }
        return Math.sin(w(elm)) * elm.maxVoltage + elm.bias;
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
        arr[i++] = "f = " + elm.getUnitText(elm.frequency, "Hz");
        arr[i++] = "Vmax = " + elm.getVoltageText(elm.maxVoltage);
        if (elm.bias == 0) {
            arr[i++] = "V(rms) = " + elm.getVoltageText(elm.maxVoltage / 1.41421356);
        }
        if (elm.bias != 0) {
            arr[i++] = "Voff = " + elm.getVoltageText(elm.bias);
        } else if (elm.frequency > 500) {
            arr[i++] = "wavelength = " + elm.getUnitText(2.9979e8 / elm.frequency, "m");
        }
    }

    @Override
    public EditInfo getEditInfo(VoltageElm elm, int n) {
        if (n == 0) return new EditInfo("Max Voltage", elm.maxVoltage, -20, 20, "V");
        if (n == 2) return new EditInfo("DC Offset (V)", elm.bias, -20, 20, "V");
        if (n == 3) return new EditInfo("Frequency (Hz)", elm.frequency, 4, 500, "Hz");
        if (n == 4) return new EditInfo("Phase Offset (degrees)", elm.phaseShift * 180 / Math.PI, -180, 180).setDimensionless();
        return null;
    }

    @Override
    public void setEditValue(VoltageElm elm, int n, EditInfo ei) {
        if (n == 0) elm.maxVoltage = ei.value;
        if (n == 2) elm.bias = ei.value;
        if (n == 3) elm.frequency = ei.value;
        if (n == 4) elm.phaseShift = ei.value * Math.PI / 180;
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
