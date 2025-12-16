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

import com.lushprojects.circuitjs1.client.CircuitSimulator;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.Polygon;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;
import com.lushprojects.circuitjs1.client.util.Locale;

public class DelayBufferElm extends CircuitElm {
    double delay, threshold, highVoltage;

    public DelayBufferElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy);
        noDiagonal = true;
        threshold = 2.5;
        highVoltage = 5;
    }

    public DelayBufferElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
                          StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f);
        noDiagonal = true;
        delay = Double.parseDouble(st.nextToken());
        threshold = 2.5;
        highVoltage = 5;
        try {
            threshold = Double.parseDouble(st.nextToken());
            highVoltage = Double.parseDouble(st.nextToken());
        } catch (Exception e) {
        }
    }

    public String dump() {
        return dumpValues(super.dump(), delay, threshold, highVoltage);
    }

    int getDumpType() {
        return 422;
    }

    Point center;

    public void draw(Graphics g) {
        drawPosts(g);
        draw2Leads(g);
        g.setColor(needsHighlight() ? selectColor() : elementColor());
        drawThickPolygon(g, gatePoly);
        if (displaySettings().euroGates())
            drawCenteredText(g, "1", center.x, center.y - 6, true);
        curcount = updateDotCount(current, curcount);
        drawDots(g, lead2, point2, curcount);
    }

    Polygon gatePoly;

    public void setPoints() {
        super.setPoints();
        int hs = 16;
        int ww = 16 - 2;
        if (ww > dn / 2)
            ww = (int) (dn / 2);
        lead1 = interpPoint(point1, point2, .5 - ww / dn);
        lead2 = interpPoint(point1, point2, .5 + ww / dn);

        if (displaySettings().euroGates()) {
            Point pts[] = newPointArray(4);
            Point l2 = interpPoint(point1, point2, .5 + (ww - 5) / dn);
            interpPoint2(lead1, l2, pts[0], pts[1], 0, hs);
            interpPoint2(lead1, l2, pts[3], pts[2], 1, hs);
            gatePoly = createPolygon(pts);
            center = interpPoint(lead1, l2, .5);
        } else {
            Point triPoints[] = newPointArray(3);
            interpPoint2(lead1, lead2, triPoints[0], triPoints[1], 0, hs);
            triPoints[2] = interpPoint(point1, point2, .5 + ww / dn);
            gatePoly = createPolygon(triPoints);
        }
        setBbox(point1, point2, hs);
    }

    public int getVoltageSourceCount() {
        return 1;
    }

    public void stamp() {
        simulator().stampVoltageSource(0, nodes[1], voltSource);
    }

    double delayEndTime;

    public void doStep() {
        CircuitSimulator simulator = simulator();
        boolean inState = volts[0] > threshold;
        boolean outState = volts[1] > threshold;
        if (inState != outState) {
            if (simulator().t >= delayEndTime)
                outState = inState;
        } else
            delayEndTime = simulator().t + delay;
        simulator().updateVoltageSource(0, nodes[1], voltSource, outState ? highVoltage : 0);
    }

    double getVoltageDiff() {
        return volts[0];
    }

    public void getInfo(String arr[]) {
        arr[0] = Locale.LS("buffer");
        arr[1] = Locale.LS("delay = ") + getUnitText(delay, "s");
        arr[2] = "Vi = " + getVoltageText(volts[0]);
        arr[3] = "Vo = " + getVoltageText(volts[1]);
    }

    public EditInfo getEditInfo(int n) {
        if (n == 0)
            return new EditInfo("Delay (s)", delay, 0, 0);
        if (n == 1)
            return new EditInfo("Threshold (V)", threshold, 0, 0);
        if (n == 2)
            return new EditInfo("High Logic Voltage", highVoltage, 0, 0);
        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 0)
            delay = ei.value;
        if (n == 1)
            threshold = ei.value;
        if (n == 2)
            highVoltage = ei.value;
    }

    // there is no current path through the inverter input, but there
    // is an indirect path through the output to ground.
    public boolean getConnection(int n1, int n2) {
        return false;
    }

    public boolean hasGroundConnection(int n1) {
        return (n1 == 1);
    }

    @Override
    public double getCurrentIntoNode(int n) {
        if (n == 1)
            return current;
        return 0;
    }

    @Override
    public String getJsonTypeName() { return "DelayBuffer"; }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        props.put("delay", getUnitText(delay, "s"));
        props.put("threshold", getUnitText(threshold, "V"));
        props.put("high_voltage", getUnitText(highVoltage, "V"));
        return props;
    }

    @Override
    public String[] getJsonPinNames() {
        return new String[] {"in", "out"};
    }
}
