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
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.element.waveform.*;

public class RailElm extends VoltageElm {
    public RailElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy, Waveform.WF_DC);

    }

    RailElm(CircuitDocument circuitDocument, int xx, int yy, int wf) {
        super(circuitDocument, xx, yy, wf);
    }

    public RailElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
                   StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f, st);
    }


    public static final int FLAG_CLOCK = 1;

    int getDumpType() {
        return 'R';
    }

    public int getPostCount() {
        return 1;
    }

    public void setPoints() {
        super.setPoints();
        lead1 = interpPoint(point1, point2, 1 - CIRCLE_SIZE / dn);
    }

    String getRailText() {
        return null;
    }

    public void draw(Graphics g) {
        String rt = getRailText();
        double w = rt == null ? CIRCLE_SIZE : g.measureWidth(rt) / 2;
        if (w > dn * .8)
            w = dn * .8;
        lead1 = interpPoint(point1, point2, 1 - w / dn);
        setBbox(point1, point2, CIRCLE_SIZE);
        setVoltageColor(g, getNodeVoltage(0));
        drawThickLine(g, point1, lead1);
        drawRail(g);
        drawPosts(g);
        curcount = updateDotCount(-current, curcount);
        if (circuitEditor().dragElm != this)
            drawDots(g, point1, lead1, curcount);
    }

    void drawRail(Graphics g) {
        waveformInstance.drawRail(g, this);
    }

    public void drawRailText(Graphics g, String s) {
        g.setColor(needsHighlight() ? selectColor() : foregroundColor());
        setPowerColor(g, false);
        drawLabeledNode(g, s, point1, lead1);
    }

    double getVoltageDiff() {
        return getNodeVoltage(0);
    }

    public void stamp() {
        waveformInstance.stampRail(this);
    }

    public void doStep() {
        if (!waveformInstance.isDC())
            simulator().updateVoltageSource(0, getNode(0), voltSource, getVoltage());
    }

    public boolean hasGroundConnection(int n1) {
        return true;
    }

    public int getShortcut() {
        return 'V';
    }

//    void drawHandles(Graphics g, Color c) {
//    	g.setColor(c);
//		g.fillRect(x-3, y-3, 7, 7);
//    }

    @Override
    public String getJsonTypeName() {
        return waveformInstance.getJsonRailTypeName();
    }

    @Override
    public String[] getJsonPinNames() {
        return new String[] { "output" };
    }
}
