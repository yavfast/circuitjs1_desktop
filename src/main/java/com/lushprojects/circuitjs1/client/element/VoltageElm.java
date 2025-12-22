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

import com.lushprojects.circuitjs1.client.Choice;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;
import com.lushprojects.circuitjs1.client.element.waveform.Waveform;

import java.util.Map;

public class VoltageElm extends CircuitElm {
    public int waveform;
    public Waveform waveformInstance;

    static final double defaultPulseDuty = 1 / PI_2;

    VoltageElm(CircuitDocument circuitDocument, int xx, int yy, int wf) {
        super(circuitDocument, xx, yy);
        waveform = wf;
        createWaveformInstance();
        reset();
    }

    public static VoltageElm createWithWaveform(CircuitDocument circuitDocument, int x, int y, int wf) {
        return new VoltageElm(circuitDocument, x, y, wf);
    }

    public VoltageElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
                      StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f);
        waveform = Waveform.WF_DC;
        try {
            waveform = parseInt(st.nextToken());
            createWaveformInstance();
            waveformInstance.frequency = parseDouble(st.nextToken());
            waveformInstance.maxVoltage = parseDouble(st.nextToken());
            waveformInstance.bias = parseDouble(st.nextToken());
            waveformInstance.phaseShift = parseDouble(st.nextToken());
            waveformInstance.dutyCycle = parseDouble(st.nextToken());
        } catch (Exception e) {
            createWaveformInstance();
        }
        if ((flags & Waveform.FLAG_COS) != 0) {
            flags &= ~Waveform.FLAG_COS;
            waveformInstance.phaseShift = PI / 2;
        }

        // old circuit files have the wrong duty cycle for pulse waveforms (wasn't configurable in the past)
        if ((flags & Waveform.FLAG_PULSE_DUTY) == 0 && waveformInstance.isPulse()) {
            waveformInstance.dutyCycle = defaultPulseDuty;
        }

        reset();
    }

    @Override
    protected String getIdPrefix() {
        return "V";
    }

    int getDumpType() {
        return 'v';
    }

    public String dump() {
        // set flag so we know if duty cycle is correct for pulse waveforms
        if (waveformInstance.isPulse()) {
            flags |= Waveform.FLAG_PULSE_DUTY;
        } else {
            flags &= ~Waveform.FLAG_PULSE_DUTY;
        }

        return dumpValues(getDumpType(), x, y, x2, y2, flags, waveform, waveformInstance.frequency, waveformInstance.maxVoltage, waveformInstance.bias, waveformInstance.phaseShift, waveformInstance.dutyCycle);
        // VarRailElm adds text at the end
    }

    public void reset() {
        if (waveformInstance != null) {
            waveformInstance.freqTimeZero = 0;
        }
        curcount = 0;
    }

    void createWaveformInstance() {
        waveformInstance = Waveform.create(waveform, waveformInstance);
        // Normalize invalid/legacy values so waveform and instance stay in sync.
        waveform = waveformInstance.getType();
    }

    int getVoltageSource() {
        return voltSource;
    }

    public void stamp() {
        waveformInstance.stamp(this);
    }

    public void doStep() {
        waveformInstance.doStep(this);
    }

    public void stepFinished() {
        waveformInstance.stepFinished(this);
    }

    public double getVoltage() {
        return waveformInstance.getVoltage(this);
    }

    static final int CIRCLE_SIZE = 17;

    public void setPoints() {
        super.setPoints();
        calcLeads(waveformInstance.usesShortLeads() ? 8 : CIRCLE_SIZE * 2);
    }

    public void draw(Graphics g) {
        setBbox(x, y, x2, y2);
        draw2Leads(g);
        if (waveformInstance.isDC()) {
            setVoltageColor(g, getNodeVoltage(0));
            setPowerColor(g, false);
            interpPoint2(lead1, lead2, ps1, ps2, 0, 10);
            drawThickLine(g, ps1, ps2);
            setVoltageColor(g, getNodeVoltage(1));
            setPowerColor(g, false);
            int hs = 16;
            setBbox(point1, point2, hs);
            interpPoint2(lead1, lead2, ps1, ps2, 1, hs);
            drawThickLine(g, ps1, ps2);
            if (displaySettings().showValues()) {
                String s = getVoltageText(getVoltage());
                drawValues(g, s, hs);
            }
        } else {
            setBbox(point1, point2, CIRCLE_SIZE);
            interpPoint(lead1, lead2, ps1, .5);
            drawWaveform(g, ps1);
            String inds;
            if (waveformInstance.bias > 0 || (waveformInstance.bias == 0 && waveformInstance.isPulse())) {
                inds = "+";
            } else {
                inds = "*";
            }
            g.setColor(foregroundColor());
            g.setFont(unitsFont());
            Point plusPoint = interpPoint(point1, point2, (dn / 2 + CIRCLE_SIZE + 4) / dn, 10 * dsign);
            plusPoint.y += 4;
            int w = (int) g.measureWidth(inds);
            g.drawString(inds, plusPoint.x - w / 2, plusPoint.y);
        }
        updateDotCount();
        if (circuitEditor().dragElm != this) {
            if (waveformInstance.isDC()) {
                drawDots(g, point1, point2, curcount);
            } else {
                drawDots(g, point1, lead1, curcount);
                drawDots(g, point2, lead2, -curcount);
            }
        }
        drawPosts(g);
    }

    public void drawWaveform(Graphics g, Point center) {
        g.setColor(needsHighlight() ? selectColor() : neutralColor());
        setPowerColor(g, false);
        int xc = center.x;
        int yc = center.y;
        if (waveformInstance.hasCircle()) {
            drawThickCircle(g, xc, yc, CIRCLE_SIZE);
        }
        adjustBbox(xc - CIRCLE_SIZE, yc - CIRCLE_SIZE, xc + CIRCLE_SIZE, yc + CIRCLE_SIZE);
        waveformInstance.draw(g, center, this);
        if (displaySettings().showValues() && waveformInstance.showFrequency()) {
            String s = getShortUnitText(waveformInstance.frequency, "Hz");
            if (dx == 0 || dy == 0) {
                drawValues(g, s, CIRCLE_SIZE);
            }
        }
    }

    public int getVoltageSourceCount() {
        return 1;
    }

    public double getPower() {
        return -getVoltageDiff() * current;
    }

    double getVoltageDiff() {
        return getNodeVoltage(1) - getNodeVoltage(0);
    }

    public void getInfo(String[] arr) {
        arr[1] = "I = " + getCurrentText(getCurrent());
        arr[2] = ((this instanceof RailElm) ? "V = " : "Vd = ") + getVoltageText(getVoltageDiff());
        waveformInstance.getInfo(this, arr, 3);
        int i = 3;
        while (i < arr.length && arr[i] != null) i++;
        if (i < arr.length)
            arr[i] = "P = " + getUnitText(getPower(), "W");
    }

    public EditInfo getEditInfo(int n) {
        if (n == 1) {
            EditInfo ei = new EditInfo("Waveform", waveform, -1, -1);
            ei.choice = new Choice();
            ei.choice.add("D/C");
            ei.choice.add("A/C");
            ei.choice.add("Square Wave");
            ei.choice.add("Triangle");
            ei.choice.add("Sawtooth");
            ei.choice.add("Pulse");
            ei.choice.add("Noise");
            ei.choice.select(waveform);
            return ei;
        }
        return waveformInstance.getEditInfo(this, n);
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 1) {
            int oldWaveform = waveform;
            Waveform oldInstance = waveformInstance;

            waveform = ei.choice.getSelectedIndex();
            if (waveform != oldWaveform) {
                ei.newDialog = true;
            }

            createWaveformInstance();

            // Waveform-specific defaults based on trait changes.
            if (waveformInstance.isDC() && (oldInstance == null || !oldInstance.isDC())) {
                waveformInstance.bias = 0;
            }

            // change duty cycle if we're changing to or from pulse
            if (waveformInstance.isPulse() && (oldInstance == null || !oldInstance.isPulse())) {
                waveformInstance.dutyCycle = defaultPulseDuty;
            } else if (oldInstance != null && oldInstance.isPulse() && !waveformInstance.isPulse()) {
                waveformInstance.dutyCycle = .5;
            }

            setPoints();
            return;
        }
        waveformInstance.setEditValue(this, n, ei);
    }

    @Override
    public String getJsonTypeName() {
        return waveformInstance.getJsonTypeName();
    }

    @Override
    public Map<String, Object> getJsonProperties() {
        Map<String, Object> props = super.getJsonProperties();
        waveformInstance.getJsonProperties(this, props);
        return props;
    }

    @Override
    public String[] getJsonPinNames() {
        return new String[] { "positive", "negative" };
    }

    @Override
    public void applyJsonProperties(java.util.Map<String, Object> properties) {
        super.applyJsonProperties(properties);
        waveformInstance.applyJsonProperties(this, properties);
    }
}
