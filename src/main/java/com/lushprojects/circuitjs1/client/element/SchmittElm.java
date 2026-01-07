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
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.StringTokenizer;

public class SchmittElm extends InvertingSchmittElm {
    private Point[] triPoints;

    public SchmittElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy);
    }

    public SchmittElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
            StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f, st);
    }

    int getDumpType() {
        return 182;
    }

    double lastOutputVoltage;

    public void startIteration() {
        lastOutputVoltage = getNodeVoltage(1);
    }

    public void doStep() {
        double out;
        if (state) {// Output is high
            if (getNodeVoltage(0) > upperTrigger)// Input voltage high enough to set output high
            {
                state = false;
                out = logicOnLevel;
            } else {
                out = logicOffLevel;
            }
        } else {// Output is low
            if (getNodeVoltage(0) < lowerTrigger)// Input voltage low enough to set output low
            {
                state = true;
                out = logicOffLevel;
            } else {
                out = logicOnLevel;
            }
        }

        CircuitSimulator simulator = simulator();
        double maxStep = slewRate * simulator.timeStep * 1e9;
        out = Math.max(Math.min(lastOutputVoltage + maxStep, out), lastOutputVoltage - maxStep);
        simulator.updateVoltageSource(0, getNode(1), voltSource, out);
    }

    public void draw(Graphics g) {
        drawPosts(g);
        draw2Leads(g);
        g.setColor(needsHighlight() ? selectColor() : elementColor());
        drawThickPolygon(g, gatePoly);
        g.setLineWidth(2);
        drawPolygon(g, symbolPoly);
        g.setLineWidth(1);
        ;
        curcount = updateDotCount(current, curcount);
        drawDots(g, geom().getLead2(), geom().getPoint2(), curcount);
    }

    public void setPoints() {
        super.setPoints();
        double dn = getDn();
        int hs = 16;
        int ww = 16;
        if (ww > dn / 2)
            ww = (int) (dn / 2);
        // geom() initializes leads
        interpPoint(geom().getPoint1(), geom().getPoint2(), geom().getLead1(), .5 - ww / dn);
        interpPoint(geom().getPoint1(), geom().getPoint2(), geom().getLead2(), .5 + (ww - 3) / dn);
        if (triPoints == null)
            triPoints = newPointArray(3);
        interpPoint2(geom().getLead1(), geom().getLead2(), triPoints[0], triPoints[1], 0, hs);
        interpPoint(geom().getPoint1(), geom().getPoint2(), triPoints[2], .5 + (ww - 5) / dn);
        gatePoly = createPolygon(triPoints);
    }

    public void getInfo(String arr[]) {
        arr[0] = "Schmitt Trigger~"; // ~ is for localization
    }

    @Override
    public double getCurrentIntoNode(int n) {
        if (n == 1)
            return current;
        return 0;
    }

    @Override
    public String getJsonTypeName() {
        return "Schmitt";
    }
}
