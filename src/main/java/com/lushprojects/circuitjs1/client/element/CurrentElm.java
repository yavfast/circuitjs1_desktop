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

import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.Polygon;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public class CurrentElm extends CircuitElm {
    double currentValue;
    boolean broken;

    public CurrentElm(int xx, int yy) {
        super(xx, yy);
        currentValue = .01;
    }

    public CurrentElm(int xa, int ya, int xb, int yb, int f,
                      StringTokenizer st) {
        super(xa, ya, xb, yb, f);
        try {
            currentValue = new Double(st.nextToken()).doubleValue();
        } catch (Exception e) {
            currentValue = .01;
        }
    }

    public String dump() {
        return super.dump() + " " + currentValue;
    }

    int getDumpType() {
        return 'i';
    }

    Polygon arrow;
    Point ashaft1, ashaft2, center;

    public void setPoints() {
        super.setPoints();
        calcLeads(26);
        ashaft1 = interpPoint(lead1, lead2, .25);
        ashaft2 = interpPoint(lead1, lead2, .6);
        center = interpPoint(lead1, lead2, .5);
        Point p2 = interpPoint(lead1, lead2, .75);
        arrow = calcArrow(center, p2, 4, 4);
    }

    public void draw(Graphics g) {
        int cr = 12;
        draw2Leads(g);
        setVoltageColor(g, (volts[0] + volts[1]) / 2);
        setPowerColor(g, false);

        drawThickCircle(g, center.x, center.y, cr);
        drawThickLine(g, ashaft1, ashaft2);

        g.fillPolygon(arrow);
        setBbox(point1, point2, cr);
        doDots(g);
        if (simUi.menuManager.showValuesCheckItem.getState() && current != 0) {
            String s = getShortUnitText(current, "A");
            if (dx == 0 || dy == 0)
                drawValues(g, s, cr);
        }
        drawPosts(g);
    }

    // analyzeCircuit determines if current source has a path or if it's broken
    public void setBroken(boolean b) {
        broken = b;
    }

    // we defer stamping current sources until we can tell if they have a current path or not
    public void stamp() {
        if (broken) {
            // no current path; stamping a current source would cause a matrix error.
            simulator.stampResistor(nodes[0], nodes[1], 1e8);
            current = 0;
        } else {
            // ok to stamp a current source
            simulator.stampCurrentSource(nodes[0], nodes[1], currentValue);
            current = currentValue;
        }
    }

    public EditInfo getEditInfo(int n) {
        if (n == 0)
            return new EditInfo("Current (A)", currentValue, 0, .1);
        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
        currentValue = ei.value;
    }

    public void getInfo(String arr[]) {
        arr[0] = "current source";
        int i = getBasicInfo(arr);
        arr[i++] = "P = " + getUnitText(getPower(), "W");
    }

    double getVoltageDiff() {
        return volts[1] - volts[0];
    }

    public double getPower() {
        return -getVoltageDiff() * current;
    }
}
