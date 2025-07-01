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

// 3 nodes, 2 internal nodes
// 1 = MT1, 0 = MT2, 2 = gate
// 3 = internal node between MT1 and MT2 (mtinode)
// 1,3 = variable resistor
// 3,0 = back-to-back diodes
// 2,1 = resistor
// MT1 and MT2 are nodes 1 and 0 (instead of 0 and 1) so that MT1 will be at the bottom when drawn bottom-to-top

import com.lushprojects.circuitjs1.client.Diode;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.Polygon;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public class TriacElm extends CircuitElm {
    final int mt1node = 1;
    final int mt2node = 0;
    final int gnode = 2;
    final int mtinode = 3;

    Diode diode03, diode30;
    boolean state;

    public TriacElm(int xx, int yy) {
        super(xx, yy);
        setDefaults();
        setup();
    }

    public TriacElm(int xa, int ya, int xb, int yb, int f,
                    StringTokenizer st) {
        super(xa, ya, xb, yb, f);
        setDefaults();
        triggerI = Double.parseDouble(st.nextToken());
        holdingI = Double.parseDouble(st.nextToken());
        cresistance = Double.parseDouble(st.nextToken());
        state = Boolean.parseBoolean(st.nextToken());
        setup();
    }

    void setDefaults() {
        holdingI = .0082;
        triggerI = .01;
        cresistance = 100;
    }

    void setup() {
        diode03 = new Diode(simUi);
        diode03.setupForDefaultModel();
        diode30 = new Diode(simUi);
        diode30.setupForDefaultModel();
    }

    public boolean nonLinear() {
        return true;
    }

    public void reset() {
        volts[mt1node] = volts[mt2node] = volts[gnode] = 0;
        diode03.reset();
        diode30.reset();
        curcount_1 = curcount_2 = curcount_g = 0;
    }

    int getDumpType() {
        return 206;
    }

    public String dump() {
        return super.dump() + " " + triggerI + " " + holdingI + " " + cresistance + " " + state;
    }

    double i1, i2, ig, curcount_1, curcount_2, curcount_g;
    double cresistance, triggerI, holdingI;

    final int hs = 8;
    Polygon poly;
    Point cathode[], gate[];

    Polygon arrows[];
    Point plate1[], plate2[];

    public void setPoints() {
        super.setPoints();
        int dir = 0;
        if (abs(dx) > abs(dy)) {
            dir = -sign(dx) * sign(dy);
            dn = abs(dx);
            point2.y = point1.y;
        } else {
            dir = sign(dy) * sign(dx);
            dn = abs(dy);
            point2.x = point1.x;
        }
        if (dir == 0)
            dir = 1;

        calcLeads(16);

        plate1 = newPointArray(2);
        plate2 = newPointArray(2);
        gate = newPointArray(2);
        interpPoint2(lead1, lead2, plate1[0], plate1[1], 0, 16);
        interpPoint2(lead1, lead2, plate2[0], plate2[1], 1, 16);

        arrows = new Polygon[2];

        int i;
        for (i = 0; i != 2; i++) {
            int sgn = -1 + i * 2;
            Point p1 = interpPoint(lead1, lead2, i, 8 * sgn);
            Point p2 = interpPoint(lead1, lead2, 1 - i, 16 * sgn);
            Point p3 = interpPoint(lead1, lead2, 1 - i, 0 * sgn);
            arrows[i] = createPolygon(p1, p2, p3);
        }

        int gatelen = simUi.circuitEditor.gridSize;
        double leadlen = (dn - 16) / 2;
        gatelen += leadlen % simUi.circuitEditor.gridSize;
        if (leadlen < gatelen) {
            x2 = x;
            y2 = y;
            return;
        }
        interpPoint(lead2, point2, gate[0], gatelen / leadlen, gatelen * dir);
        interpPoint(lead2, point2, gate[1], gatelen / leadlen, simUi.circuitEditor.gridSize * 2 * dir);

    }

    public void draw(Graphics g) {
        double v1 = volts[0];
        double v2 = volts[1];
        setBbox(point1, point2, 6);
        adjustBbox(gate[0], gate[1]);

        draw2Leads(g);
        setVoltageColor(g, v1);
        setPowerColor(g, true);
        drawThickLine(g, plate1[0], plate1[1]);
        setVoltageColor(g, v2);
        setPowerColor(g, true);
        drawThickLine(g, plate2[0], plate2[1]);
        g.fillPolygon(arrows[0]);
        setVoltageColor(g, v1);
        setPowerColor(g, true);
        g.fillPolygon(arrows[1]);
        setVoltageColor(g, volts[gnode]);

        drawThickLine(g, lead2, gate[0]);
        drawThickLine(g, gate[0], gate[1]);

        curcount_1 = updateDotCount(i1, curcount_1);
        curcount_2 = updateDotCount(i2, curcount_2);
        curcount_g = updateDotCount(ig, curcount_g);
        if (simUi.circuitEditor.dragElm != this) {
            drawDots(g, point1, lead2, curcount_2);
            drawDots(g, point2, lead2, curcount_1);
            drawDots(g, gate[1], gate[0], curcount_g);
            drawDots(g, gate[0], lead2, curcount_g + distance(gate[1], gate[0]));
        }

        if ((needsHighlight() || simUi.circuitEditor.dragElm == this) && point1.x == point2.x && point2.y > point1.y) {
            g.setColor(whiteColor);
            int ds = sign(dx);
            g.drawString("MT1", lead2.x + ((ds < 0) ? 5 : -30), lead2.y + 12);
            g.drawString("MT2", lead1.x + 5, lead1.y - 4); // x+6 if ds=1, -12 if -1
            g.drawString("G", gate[0].x, gate[0].y + 12);
        }

        drawPosts(g);
    }

    public Point getPost(int n) {
        return (n == 0) ? point1 : (n == 1) ? point2 : gate[1];
    }

    @Override
    public double getCurrentIntoNode(int n) {
        if (n == 0)
            return -i2;
        if (n == 1)
            return -i1;
        return -ig;
    }

    public int getPostCount() {
        return 3;
    }

    public int getInternalNodeCount() {
        return 1;
    }

    double aresistance;

    public void stamp() {
        simulator.stampNonLinear(nodes[mt1node]);
        simulator.stampNonLinear(nodes[mt2node]);
        simulator.stampNonLinear(nodes[gnode]);
        simulator.stampNonLinear(nodes[mtinode]);
        simulator.stampResistor(nodes[gnode], nodes[mt1node], cresistance);
        diode03.stamp(nodes[mt2node], nodes[mtinode]);
        diode30.stamp(nodes[mtinode], nodes[mt2node]);
    }

    public void startIteration() {
        if (Math.abs(i2) < holdingI)
            state = false;
        if (Math.abs(ig) > triggerI)
            state = true;
        aresistance = (state) ? .01 : 10e5;
    }

    public void doStep() {
        diode03.doStep(volts[mt2node] - volts[mtinode]);
        diode30.doStep(volts[mtinode] - volts[mt2node]);
        simulator.stampResistor(nodes[mtinode], nodes[mt1node], aresistance);
    }

    public void getInfo(String arr[]) {
        arr[0] = "TRIAC";
        arr[1] = (state) ? "on" : "off";
        arr[2] = "Vmt2mt1 = " + getVoltageText(volts[mt2node] - volts[mt1node]);
        arr[3] = "Imt1 = " + getCurrentText(i1);
        arr[4] = "Imt2 = " + getCurrentText(i2);
        arr[5] = "Ig = " + getCurrentText(ig);
        arr[6] = "P = " + getUnitText(getPower(), "W");
    }

    void calculateCurrent() {
        // aresistance can be 0 on startup
        if (aresistance == 0)
            i2 = 0;
        else
            i2 = (volts[mtinode] - volts[mt1node]) / aresistance;
        ig = -(volts[mt1node] - volts[gnode]) / cresistance;
        i1 = -i2 - ig;
    }

    public double getPower() {
        return (volts[mt2node] - volts[mt1node]) * i2 + (volts[gnode] - volts[mt1node]) * ig;
    }

    public EditInfo getEditInfo(int n) {
        if (n == 0)
            return new EditInfo("Trigger Current (A)", triggerI, 0, 0);
        if (n == 1)
            return new EditInfo("Holding Current (A)", holdingI, 0, 0);
        if (n == 2)
            return new EditInfo("Gate-MT1 Resistance (ohms)", cresistance, 0, 0);
        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 0 && ei.value > 0)
            triggerI = ei.value;
        if (n == 1 && ei.value > 0)
            holdingI = ei.value;
        if (n == 2 && ei.value > 0)
            cresistance = ei.value;
    }

    public boolean canViewInScope() {
        return true;
    }

    double getVoltageDiff() {
        return volts[mt2node] - volts[mt1node];
    }

    public double getCurrent() {
        return i2;
    } // for scope
}

