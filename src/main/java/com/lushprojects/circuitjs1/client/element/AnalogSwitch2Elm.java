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
import com.lushprojects.circuitjs1.client.StringTokenizer;

public class AnalogSwitch2Elm extends AnalogSwitchElm {
    public AnalogSwitch2Elm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy);
    }

    public AnalogSwitch2Elm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
                            StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f, st);
    }

    Point swposts[], swpoles[], ctlPoint;

    public void setPoints() {
        super.setPoints();
        calcLeads(32);
        adjustLeadsToGrid(isFlippedX(), isFlippedY());
        swposts = newPointArray(2);
        swpoles = newPointArray(2);
        interpPoint2(lead1, lead2, swpoles[0], swpoles[1], 1, openhs);
        interpPoint2(point1, point2, swposts[0], swposts[1], 1, openhs);
        ctlPoint = interpPoint(lead1, lead2, .5, openhs);
    }

    public int getPostCount() {
        return 4;
    }

    public void draw(Graphics g) {
        setBbox(point1, point2, openhs);

        // draw first lead
        setVoltageColor(g, volts[0]);
        drawThickLine(g, point1, lead1);

        // draw second lead
        setVoltageColor(g, volts[1]);
        drawThickLine(g, swpoles[0], swposts[0]);

        // draw third lead
        setVoltageColor(g, volts[2]);
        drawThickLine(g, swpoles[1], swposts[1]);

        // draw switch
        g.setColor(elementColor());
        int position = (open) ? 1 : 0;
        drawThickLine(g, lead1, swpoles[position]);

        updateDotCount();
        drawDots(g, point1, lead1, curcount);
        drawDots(g, swpoles[position], swposts[position], curcount);
        drawPosts(g);
    }

    public Point getPost(int n) {
        return (n == 0) ? point1 : (n == 3) ? ctlPoint : swposts[n - 1];
    }

    int getDumpType() {
        return 160;
    }

    void calculateCurrent() {
        if (open)
            current = (volts[0] - volts[2]) / r_on;
        else
            current = (volts[0] - volts[1]) / r_on;
    }

    public void stamp() {
        CircuitSimulator simulator = simulator();
        simulator().stampNonLinear(nodes[0]);
        simulator().stampNonLinear(nodes[1]);
        simulator().stampNonLinear(nodes[2]);
        if (needsPulldown()) {
            simulator().stampResistor(nodes[1], 0, r_off);
            simulator().stampResistor(nodes[2], 0, r_off);
        }
    }

    public void doStep() {
        CircuitSimulator simulator = simulator();
        open = (volts[3] < threshold);
        if (hasFlag(FLAG_INVERT))
            open = !open;
        if (open) {
            simulator().stampResistor(nodes[0], nodes[2], r_on);
            if (!needsPulldown())
                simulator().stampResistor(nodes[0], nodes[1], r_off);
        } else {
            simulator().stampResistor(nodes[0], nodes[1], r_on);
            if (!needsPulldown())
                simulator().stampResistor(nodes[0], nodes[2], r_off);
        }
    }

    public boolean getConnection(int n1, int n2) {
        if (n1 == 3 || n2 == 3)
            return false;
        if (needsPulldown())
            return comparePair(n1, n2, 0, open ? 2 : 1);
        return true;
    }

    public boolean hasGroundConnection(int n) {
        return needsPulldown() && n != 3;
    }

    public void getInfo(String arr[]) {
        arr[0] = "analog switch (SPDT)";
        arr[1] = "I = " + getCurrentDText(getCurrent());
    }

    public double getCurrentIntoNode(int n) {
        if (n == 0)
            return -current;
        int position = (open) ? 1 : 0;
        if (n == position + 1)
            return current;
        return 0;
    }

    @Override
    public String getJsonTypeName() {
        return "AnalogSwitch2";
    }

    @Override
    public String[] getJsonPinNames() {
        return new String[] { "common", "out1", "out2", "control" };
    }
}

