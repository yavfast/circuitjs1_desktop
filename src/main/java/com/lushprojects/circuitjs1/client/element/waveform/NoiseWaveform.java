package com.lushprojects.circuitjs1.client.element.waveform;

import com.lushprojects.circuitjs1.client.element.VoltageElm;
import com.lushprojects.circuitjs1.client.element.CircuitElm;

import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.RandomUtils;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

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
        return true;
    }

    @Override
    public boolean showFrequency() {
        return false;
    }

    @Override
    public void draw(Graphics g, Point center, VoltageElm elm) {
        g.setColor(elm.needsHighlight() ? VoltageElm.selectColor() : CircuitElm.foregroundColor());
        elm.setPowerColor(g, false);

        // Static pseudo-noise icon inside the circle.
        // Must be deterministic so it doesn't change on redraw (e.g., mouse move).
        final int xc = center.x;
        final int yc = center.y;
        final int wl = 8;
        final int xl = 10;

        final int seed = elm.getElementId().hashCode();

        g.beginPath();
        g.setLineWidth(2.0);
        for (int i = -xl; i <= xl; i++) {
            double n = iconNoise(seed, i);
            int yy = yc + (int) (0.5 * n * wl);
            if (i == -xl) {
                g.moveTo(xc + i, yy);
            } else {
                g.lineTo(xc + i, yy);
            }
        }
        g.stroke();
        g.setLineWidth(1.0);
    }

    private static double iconNoise(int seed, int i) {
        int x = seed ^ (i * 0x9E3779B9);
        x ^= (x << 13);
        x ^= (x >>> 17);
        x ^= (x << 5);
        // map to [-1, 1]
        return ((x & 0x7fffffff) / (double) 0x7fffffff) * 2 - 1;
    }

    @Override
    public void getInfo(VoltageElm elm, String[] arr, int i) {
        arr[0] = "noise gen";
    }

    @Override
    public EditInfo getEditInfo(VoltageElm elm, int n) {
        if (n == 0)
            return new EditInfo("Max Voltage", maxVoltage, -20, 20, "V");
        if (n == 2)
            return new EditInfo("DC Offset (V)", bias, -20, 20, "V");
        return null;
    }

    @Override
    public void setEditValue(VoltageElm elm, int n, EditInfo ei) {
        if (n == 0)
            maxVoltage = ei.value;
        if (n == 2)
            bias = ei.value;
    }

    @Override
    public void getJsonProperties(VoltageElm elm, java.util.Map<String, Object> props) {
        props.put("max_voltage", VoltageElm.getUnitText(maxVoltage, "V"));
        if (bias != 0) {
            props.put("dc_offset", VoltageElm.getUnitText(bias, "V"));
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
