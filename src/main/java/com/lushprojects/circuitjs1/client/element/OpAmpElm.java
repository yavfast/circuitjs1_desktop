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

import com.lushprojects.circuitjs1.client.Font;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.Polygon;
import com.lushprojects.circuitjs1.client.RandomUtils;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public class OpAmpElm extends CircuitElm {
    int opsize, opheight, opwidth, opaddtext;
    double maxOut, minOut, gain, gbw;
    boolean reset;
    final int FLAG_SWAP = 1;
    final int FLAG_SMALL = 2;
    final int FLAG_LOWGAIN = 4;
    final int FLAG_GAIN = 8;

    public OpAmpElm(int xx, int yy) {
        super(xx, yy);
        noDiagonal = true;
        maxOut = 15;
        minOut = -15;
        gbw = 1e6;
        flags = FLAG_GAIN; // need to do this before setSize()
        gain = 100000;
        setSize(simUi.menuManager.smallGridCheckItem.getState() ? 1 : 2);
    }

    public OpAmpElm(int xa, int ya, int xb, int yb, int f,
                    StringTokenizer st) {
        super(xa, ya, xb, yb, f);
        maxOut = 15;
        minOut = -15;
        // GBW has no effect in this version of the simulator, but we
        // retain it to keep the file format the same
        gbw = 1e6;
        try {
            maxOut = new Double(st.nextToken()).doubleValue();
            minOut = new Double(st.nextToken()).doubleValue();
            gbw = new Double(st.nextToken()).doubleValue();
            volts[0] = new Double(st.nextToken()).doubleValue();
            volts[1] = new Double(st.nextToken()).doubleValue();
            gain = new Double(st.nextToken()).doubleValue();
        } catch (Exception e) {
        }
        noDiagonal = true;
        setSize((f & FLAG_SMALL) != 0 ? 1 : 2);
        setGain();
    }

    void setGain() {
        if ((flags & FLAG_GAIN) != 0)
            return;

        // gain of 100000 breaks e-amp-dfdx.txt
        // gain was 1000, but it broke amp-schmitt.txt
        gain = ((flags & FLAG_LOWGAIN) != 0) ? 1000 : 100000;
    }

    public String dump() {
        flags |= FLAG_GAIN;
        return super.dump() + " " + maxOut + " " + minOut + " " + gbw + " " + volts[0] + " " + volts[1] + " " + gain;
    }

    public boolean nonLinear() {
        return true;
    }

    public void draw(Graphics g) {
        setBbox(point1, point2, opheight * 2);
        setVoltageColor(g, volts[0]);
        drawThickLine(g, in1p[0], in1p[1]);
        setVoltageColor(g, volts[1]);
        drawThickLine(g, in2p[0], in2p[1]);
        setVoltageColor(g, volts[2]);
        drawThickLine(g, lead2, point2);
        g.setColor(needsHighlight() ? selectColor : elementColor);
        setPowerColor(g, true);
        drawThickPolygon(g, triangle);
        g.setFont(plusFont);
        drawCenteredText(g, "-", textp[0].x, textp[0].y - 2, true);
        drawCenteredText(g, "+", textp[1].x, textp[1].y, true);
        curcount = updateDotCount(current, curcount);
        drawDots(g, point2, lead2, curcount);
        drawPosts(g);
    }

    public double getPower() {
        return volts[2] * current;
    }

    Point in1p[], in2p[], textp[];
    Polygon triangle;
    Font plusFont;

    void setSize(int s) {
        opsize = s;
        opheight = 8 * s;
        opwidth = 13 * s;
        flags = (flags & ~FLAG_SMALL) | ((s == 1) ? FLAG_SMALL : 0);
    }

    public void setPoints() {
        super.setPoints();
        if (dn > 150 && this == circuitEditor.dragElm)
            setSize(2);
        int ww = opwidth;
        if (ww > dn / 2)
            ww = (int) (dn / 2);
        calcLeads(ww * 2);
        int hs = opheight * dsign;
        if ((flags & FLAG_SWAP) != 0)
            hs = -hs;
        in1p = newPointArray(2);
        in2p = newPointArray(2);
        textp = newPointArray(2);
        interpPoint2(point1, point2, in1p[0], in2p[0], 0, hs);
        interpPoint2(lead1, lead2, in1p[1], in2p[1], 0, hs);
        interpPoint2(lead1, lead2, textp[0], textp[1], .2, hs);
        Point tris[] = newPointArray(2);
        interpPoint2(lead1, lead2, tris[0], tris[1], 0, hs * 2);
        triangle = createPolygon(tris[0], tris[1], lead2);
        plusFont = new Font("SansSerif", 0, opsize == 2 ? 14 : 10);
    }

    public int getPostCount() {
        return 3;
    }

    public Point getPost(int n) {
        return (n == 0) ? in1p[0] : (n == 1) ? in2p[0] : point2;
    }

    public int getVoltageSourceCount() {
        return 1;
    }

    public void getInfo(String arr[]) {
        arr[0] = "op-amp";
        arr[1] = "V+ = " + getVoltageText(volts[1]);
        arr[2] = "V- = " + getVoltageText(volts[0]);
        // sometimes the voltage goes slightly outside range, to make
        // convergence easier.  so we hide that here.
        double vo = Math.max(Math.min(volts[2], maxOut), minOut);
        arr[3] = "Vout = " + getVoltageText(vo);
        arr[4] = "Iout = " + getCurrentText(-current);
        arr[5] = "range = " + getVoltageText(minOut) + " to " +
                getVoltageText(maxOut);
    }

    double lastvd;

    public void stamp() {
        int vn = simulator.nodeList.size() + voltSource;
        simulator.stampNonLinear(vn);
        simulator.stampMatrix(nodes[2], vn, 1);
    }

    public void doStep() {
        double vd = volts[1] - volts[0];
        double midpoint = (maxOut + minOut) * .5;
        if (Math.abs(lastvd - vd) > .1)
            simulator.converged = false;
        else if (volts[2] > maxOut + .1 || volts[2] < minOut - .1)
            simulator.converged = false;
        double x = 0;
        int vn = simulator.nodeList.size() + voltSource;
        double dx = 0;
        double maxAdj = maxOut - midpoint;
        double minAdj = minOut - midpoint;
        if (vd >= maxAdj / gain && (lastvd >= 0 || RandomUtils.getRand(4) == 1)) {
            dx = 1e-4;
            x = maxOut - dx * maxAdj / gain;
        } else if (vd <= minAdj / gain && (lastvd <= 0 || RandomUtils.getRand(4) == 1)) {
            dx = 1e-4;
            x = minOut - dx * minAdj / gain;
        } else {
            dx = gain;
            x = midpoint;
        }
        //System.out.println("opamp " + vd + " " + volts[2] + " " + dx + " "  + x + " " + lastvd + " " + sim.converged);

        // newton-raphson
        simulator.stampMatrix(vn, nodes[0], dx);
        simulator.stampMatrix(vn, nodes[1], -dx);
        simulator.stampMatrix(vn, nodes[2], 1);
        simulator.stampRightSide(vn, x);

        lastvd = vd;
	    /*if (sim.converged)
	      System.out.println((volts[1]-volts[0]) + " " + volts[2] + " " + initvd);*/
    }

    // there is no current path through the op-amp inputs, but there
    // is an indirect path through the output to ground.
    public boolean getConnection(int n1, int n2) {
        return false;
    }

    public boolean hasGroundConnection(int n1) {
        return (n1 == 2);
    }

    double getVoltageDiff() {
        return volts[2] - volts[1];
    }

    int getDumpType() {
        return 'a';
    }

    public EditInfo getEditInfo(int n) {
        if (n == 0)
            return new EditInfo("Max Output (V)", maxOut, 1, 20);
        if (n == 1)
            return new EditInfo("Min Output (V)", minOut, -20, 0);
        if (n == 2)
            return new EditInfo("Gain", gain, 10, 1000000);
        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 0)
            maxOut = ei.value;
        if (n == 1)
            minOut = ei.value;
        if (n == 2 && ei.value > 0)
            gain = ei.value;
    }

    public int getShortcut() {
        return 'a';
    }

    @Override
    public double getCurrentIntoNode(int n) {
        if (n == 2)
            return -current;
        return 0;
    }

    public void flipX(int c2, int count) {
        if (dx == 0)
            flags ^= FLAG_SWAP;
        super.flipX(c2, count);
    }

    public void flipY(int c2, int count) {
        if (dy == 0)
            flags ^= FLAG_SWAP;
        super.flipY(c2, count);
    }

    public void flipXY(int xmy, int count) {
        flags ^= FLAG_SWAP;
        super.flipXY(xmy, count);
    }

}
