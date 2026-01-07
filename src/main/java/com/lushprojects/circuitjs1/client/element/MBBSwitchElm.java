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
import com.lushprojects.circuitjs1.client.Rectangle;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public class MBBSwitchElm extends SwitchElm {
    int link;
    int voltSources[];
    double currents[];
    double curcounts[];
    boolean both;

    public MBBSwitchElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy, false);
        setup();
    }

    void setup() {
        noDiagonal = true;
        voltSources = new int[2];
        currents = new double[2];
        curcounts = new double[3];
    }

    public MBBSwitchElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
            StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f, st);
        link = parseInt(st.nextToken());
        setup();
    }

    int getDumpType() {
        return 416;
    }

    public String dump() {
        return dumpValues(super.dump(), link);
    }

    final int openhs = 16;
    Point swposts[], swpoles[];

    public void setPoints() {
        super.setPoints();
        calcLeads(32);
        if (swposts == null || swposts.length != 2) {
            swposts = newPointArray(2);
        }
        if (swpoles == null || swpoles.length != 4) {
            swpoles = newPointArray(4);
        }
        int i;
        for (i = 0; i != 2; i++) {
            int hs = -openhs * (i - (2 - 1) / 2);
            if (i == 0)
                hs = openhs;
            interpPoint(geom().getLead1(), geom().getLead2(), swpoles[i], 1, hs);
            interpPoint(geom().getPoint1(), geom().getPoint2(), swposts[i], 1, hs);
        }

        // 4 positions (pole 1, both, pole 2, both)
        posCount = 4;
    }

    public void draw(Graphics g) {

        setBbox(geom().getPoint1(), geom().getPoint2(), openhs);
        adjustBbox(swposts[0], swposts[1]);

        // draw first lead
        setVoltageColor(g, getNodeVoltage(0));
        drawThickLine(g, geom().getPoint1(), geom().getLead1());

        // draw other leads
        int i;
        for (i = 0; i != 2; i++) {
            setVoltageColor(g, getNodeVoltage(i + 1));
            drawThickLine(g, swpoles[i], swposts[i]);
        }

        // draw switch
        if (!needsHighlight())
            g.setColor(foregroundColor());
        if (both || position == 0)
            drawThickLine(g, geom().getLead1(), swpoles[0]);
        if (both || position == 2)
            drawThickLine(g, geom().getLead1(), swpoles[1]);

        // draw current
        for (i = 0; i != 2; i++) {
            curcounts[i] = updateDotCount(currents[i], curcounts[i]);
            drawDots(g, swpoles[i], swposts[i], curcounts[i]);
        }
        curcounts[2] = updateDotCount(currents[0] + currents[1], curcounts[2]);
        drawDots(g, geom().getPoint1(), geom().getLead1(), curcounts[2]);
        drawPosts(g);
    }

    public double getCurrentIntoNode(int n) {
        if (n == 0)
            return -currents[0] - currents[1];
        return currents[n - 1];
    }

    public Rectangle getSwitchRect() {
        return new Rectangle(geom().getLead1()).union(new Rectangle(swpoles[0])).union(new Rectangle(swpoles[1]));
    }

    public Point getPost(int n) {
        return (n == 0) ? geom().getPoint1() : swposts[n - 1];
    }

    public int getPostCount() {
        return 3;
    }

    public void setCurrent(int vn, double c) {
        // set current for voltage source vn to c
        if (vn == voltSources[0])
            currents[both ? 0 : position / 2] = c;
        else if (vn == voltSources[1])
            currents[1] = c;
    }

    void calculateCurrent() {
        // make sure current of unconnected pole is zero
        if (!both)
            currents[1 - (position / 2)] = 0;
    }

    public void setVoltageSource(int n, int v) {
        voltSources[n] = v;
    }

    public void stamp() {
        CircuitSimulator simulator = simulator();
        int vs = 0;
        if (both || position == 0)
            simulator.stampVoltageSource(getNode(0), getNode(1), voltSources[vs++], 0);
        if (both || position == 2)
            simulator.stampVoltageSource(getNode(0), getNode(2), voltSources[vs++], 0);
    }

    // connection is implemented by voltage source with voltage = 0.
    // need two for both loads connected, otherwise one.
    public int getVoltageSourceCount() {
        both = (position == 1 || position == 3);
        return (both) ? 2 : 1;
    }

    public void toggle() {
        super.toggle();
        if (link != 0) {
            CircuitSimulator simulator = simulator();
            for (int i = 0; i != simulator.elmList.size(); i++) {
                Object o = simulator.elmList.get(i);
                if (o instanceof MBBSwitchElm) {
                    MBBSwitchElm s2 = (MBBSwitchElm) o;
                    if (s2.link == link)
                        s2.position = position;
                }
            }
        }
    }

    public boolean getConnection(int n1, int n2) {
        if (both)
            return true;
        return comparePair(n1, n2, 0, 1 + position / 2);
    }

    // do not optimize out, even though isWireEquivalent() is true (because it may
    // have 3 nodes to merge
    // and calcWireClosure() doesn't handle that case)
    public boolean isRemovableWire() {
        return false;
    }

    public boolean isWireEquivalent() {
        return true;
    }

    public void getInfo(String arr[]) {
        arr[0] = "switch (" + (link == 0 ? "S" : "D") + "PDT, MBB)";
        arr[1] = "I = " + getCurrentDText(getCurrent());
    }

    public EditInfo getEditInfo(int n) {
        if (n == 1)
            return new EditInfo("Switch Group", link, 0, 100).setDimensionless();
        return super.getEditInfo(n);
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 1) {
            link = (int) ei.value;
        } else
            super.setEditValue(n, ei);
    }

    public int getShortcut() {
        return 0;
    }

    @Override
    public String getJsonTypeName() {
        return "MBBSwitch";
    }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        props.put("link_group", link);
        return props;
    }

    @Override
    public String[] getJsonPinNames() {
        return new String[] { "common", "throw1", "throw2" };
    }
}
