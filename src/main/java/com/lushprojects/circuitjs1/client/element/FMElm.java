/*    
    Copyright (C) Paul Falstad and Iain Sharp
    
    This file is part of CircuitJS1.

    CircuitJS1 is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 2 of the License, or
    (at your option) any later version.

    CircuitJS1 is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with CircuitJS1.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.lushprojects.circuitjs1.client.element;

import com.lushprojects.circuitjs1.client.CircuitDocument;

// contributed by Edward Calver

import com.lushprojects.circuitjs1.client.CircuitSimulator;
import com.lushprojects.circuitjs1.client.Color;
import com.lushprojects.circuitjs1.client.Font;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public class FMElm extends CircuitElm {
    static final int FLAG_COS = 2;
    double carrierfreq, signalfreq, maxVoltage, freqTimeZero, deviation;
    double lasttime = 0;
    double funcx = 0;

    public FMElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy);
        deviation = 200;
        maxVoltage = 5;
        carrierfreq = 800;
        signalfreq = 40;
        reset();
    }

    public FMElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
                 StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f);
        carrierfreq = parseDouble(st.nextToken());
        signalfreq = parseDouble(st.nextToken());
        maxVoltage = parseDouble(st.nextToken());
        deviation = parseDouble(st.nextToken());
        if ((flags & FLAG_COS) != 0) {
            flags &= ~FLAG_COS;
        }
        reset();
    }

    int getDumpType() {
        return 201;
    }

    public String dump() {
        return dumpValues(super.dump(), carrierfreq, signalfreq, maxVoltage, deviation);
    }

    public void reset() {
        freqTimeZero = 0;
        curcount = 0;
    }

    public int getPostCount() {
        return 1;
    }

    public void stamp() {
        simulator().stampVoltageSource(0, nodes[0], voltSource);
    }

    public void doStep() {
        simulator().updateVoltageSource(0, nodes[0], voltSource, getVoltage());
    }

    double getVoltage() {
        CircuitSimulator simulator = simulator();
        double deltaT = simulator().t - lasttime;
        lasttime = simulator().t;
        double signalamplitude = Math.sin((2 * pi * (simulator().t - freqTimeZero)) * signalfreq);
        funcx += deltaT * (carrierfreq + (signalamplitude * deviation));
        double w = 2 * pi * funcx;
        return Math.sin(w) * maxVoltage;
    }

    final int circleSize = 17;

    public void draw(Graphics g) {
        setBbox(point1, point2, circleSize);
        setVoltageColor(g, volts[0]);
        drawThickLine(g, point1, lead1);

        Font f = new Font("SansSerif", 0, 12);
        g.setFont(f);
        g.setColor(needsHighlight() ? selectColor() : foregroundColor());
        setPowerColor(g, false);
        double v = getVoltage();
        String s = "FM";
        drawCenteredText(g, s, x2, y2, true);
        drawWaveform(g, point2);
        drawPosts(g);
        curcount = updateDotCount(-current, curcount);
        if (circuitEditor().dragElm != this)
            drawDots(g, point1, lead1, curcount);
    }

    void drawWaveform(Graphics g, Point center) {
        g.setColor(needsHighlight() ? selectColor() : neutralColor());
        setPowerColor(g, false);
        int xc = center.x;
        int yc = center.y;
        drawThickCircle(g, xc, yc, circleSize);
        int wl = 8;
        adjustBbox(xc - circleSize, yc - circleSize,
                xc + circleSize, yc + circleSize);
    }


    public void setPoints() {
        super.setPoints();
        lead1 = interpPoint(point1, point2, 1 - circleSize / dn);
    }

    double getVoltageDiff() {
        return volts[0];
    }

    public boolean hasGroundConnection(int n1) {
        return true;
    }

    public int getVoltageSourceCount() {
        return 1;
    }

    public double getPower() {
        return -getVoltageDiff() * current;
    }

    public void getInfo(String arr[]) {

        arr[0] = "FM Source";
        arr[1] = "I = " + getCurrentText(getCurrent());
        arr[2] = "V = " +
                getVoltageText(getVoltageDiff());
        arr[3] = "cf = " + getUnitText(carrierfreq, "Hz");
        arr[4] = "sf = " + getUnitText(signalfreq, "Hz");
        arr[5] = "dev =" + getUnitText(deviation, "Hz");
        arr[6] = "Vmax = " + getVoltageText(maxVoltage);
    }

    public EditInfo getEditInfo(int n) {
        if (n == 0)
            return new EditInfo("Max Voltage", maxVoltage, -20, 20);
        if (n == 1)
            return new EditInfo("Carrier Frequency (Hz)", carrierfreq, 4, 500);
        if (n == 2)
            return new EditInfo("Signal Frequency (Hz)", signalfreq, 4, 500);
        if (n == 3)
            return new EditInfo("Deviation (Hz)", deviation, 4, 500);

        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 0)
            maxVoltage = ei.value;
        if (n == 1)
            carrierfreq = ei.value;
        if (n == 2)
            signalfreq = ei.value;
        if (n == 3)
            deviation = ei.value;
    }

    @Override
    public String getJsonTypeName() {
        return "FMSource";
    }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        props.put("carrier_frequency", getUnitText(carrierfreq, "Hz"));
        props.put("signal_frequency", getUnitText(signalfreq, "Hz"));
        props.put("max_voltage", getUnitText(maxVoltage, "V"));
        props.put("deviation", getUnitText(deviation, "Hz"));
        return props;
    }

    @Override
    public String[] getJsonPinNames() {
        return new String[] { "output" };
    }
}
