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

import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.Polygon;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public class TriStateElm extends CircuitElm {
    double resistance, r_on, r_off, r_off_ground, highVoltage;

    // Unfortunately we need all three flags to keep track of flipping.
    // FLAG_FLIP_X/Y affect the rounding direction if the elm is an odd grid length.
    // FLAG_FLIP does not.
    final int FLAG_FLIP = 1;
    final int FLAG_FLIP_X = 2;
    final int FLAG_FLIP_Y = 4;

    public TriStateElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy);
        r_on = 0.1;
        r_off = 1e10;
        r_off_ground = 1e8;
        noDiagonal = true;

        // copy defaults from last gate edited
        highVoltage = GateElm.lastHighVoltage;
    }

    public TriStateElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f, StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f);
        r_on = 0.1;
        r_off = 1e10;
        r_off_ground = 0;
        noDiagonal = true;
        highVoltage = 5;
        try {
            r_on = parseDouble(st.nextToken());
            r_off = parseDouble(st.nextToken());
            r_off_ground = parseDouble(st.nextToken());
            highVoltage = parseDouble(st.nextToken());
        } catch (Exception e) {
        }

    }

    public String dump() {
        return dumpValues(super.dump(), r_on, r_off, r_off_ground, highVoltage);
    }

    int getDumpType() {
        return 180;
    }

    boolean open;

    Point ps, point3, lead3;

    Point gatePoints[];

    Polygon gatePoly;

    public void setPoints() {
        super.setPoints();
        double dn = getDn();
        int len = 32;
        calcLeads(len);
        adjustLeadsToGrid((flags & FLAG_FLIP_X) != 0, (flags & FLAG_FLIP_Y) != 0);

        if (ps == null) {
            ps = new Point();
        }
        int hs = 16;

        int ww = 16;
        if (ww > dn / 2)
            ww = (int) (dn / 2);
        if (gatePoints == null || gatePoints.length != 3) {
            gatePoints = newPointArray(3);
        }
        interpPoint2(geom().getLead1(), geom().getLead2(), gatePoints[0], gatePoints[1], 0, hs + 2);
        interpPoint(geom().getLead1(), geom().getLead2(), gatePoints[2], .5 + (ww - 2) / (double) len);
        gatePoly = createPolygon(gatePoints);

        int sign = ((flags & FLAG_FLIP) == 0) ? -1 : 1;
        if (point3 == null) {
            point3 = new Point();
        }
        if (lead3 == null) {
            lead3 = new Point();
        }
        interpPoint(geom().getLead1(), geom().getLead2(), point3, .5, sign * hs);
        interpPoint(geom().getLead1(), geom().getLead2(), lead3, .5, sign * hs / 2);
    }

    public void draw(Graphics g) {
        int hs = 16;
        setBbox(geom().getPoint1(), geom().getPoint2(), hs);

        draw2Leads(g);

        g.setColor(elementColor());
        drawThickPolygon(g, gatePoly);
        setVoltageColor(g, getNodeVoltage(2));
        drawThickLine(g, point3, lead3);
        curcount = updateDotCount(current, curcount);
        drawDots(g, geom().getLead2(), geom().getPoint2(), curcount);
        drawPosts(g);
    }

    void calculateCurrent() {
        // current from node 3 to node 1
        double current31 = (getNodeVoltage(3) - getNodeVoltage(1)) / resistance;

        // current from node 1 through pulldown
        double current10 = (r_off_ground == 0) ? 0 : getNodeVoltage(1) / r_off_ground;

        // output current is difference of these
        current = current31 - current10;
    }

    public double getCurrentIntoNode(int n) {
        if (n == 1)
            return current;
        return 0;
    }

    // we need this to be able to change the matrix for each step
    public boolean nonLinear() {
        return true;
    }

    // node 0: input
    // node 1: output
    // node 2: control input
    // node 3: internal node
    // there is a voltage source connected to node 3, and a resistor (r_off or r_on)
    // from node 3 to 1.
    // then there is a pulldown resistor from node 1 to ground.
    public void stamp() {
        simulator().stampVoltageSource(0, getNode(3), voltSource);
        simulator().stampNonLinear(getNode(3));
        simulator().stampNonLinear(getNode(1));
    }

    public void doStep() {
        open = (getNodeVoltage(2) < highVoltage * .5);
        resistance = (open) ? r_off : r_on;
        simulator().stampResistor(getNode(3), getNode(1), resistance);

        // Add pulldown resistor for output, so that disabled tristate has output near
        // ground if nothing
        // else is driving the output. Otherwise people get confused.
        if (r_off_ground > 0)
            simulator().stampResistor(getNode(1), 0, r_off_ground);

        simulator().updateVoltageSource(0, getNode(3), voltSource,
                getNodeVoltage(0) > highVoltage * .5 ? highVoltage : 0);
    }

    public void drag(int xx, int yy) {
        // use mouse to select which side the buffer enable should be on
        boolean flip = (xx < getX()) == (yy < getY());

        xx = circuitEditor().snapGrid(xx);
        yy = circuitEditor().snapGrid(yy);
        if (abs(getX() - xx) < abs(getY() - yy))
            xx = getX();
        else {
            flip = !flip;
            yy = getY();
        }
        flags = flip ? (flags | FLAG_FLIP) : (flags & ~FLAG_FLIP);
        setEndpoints(getX(), getY(), xx, yy);
        setPoints();
    }

    public int getPostCount() {
        return 3;
    }

    public int getInternalNodeCount() {
        return 1;
    }

    public int getVoltageSourceCount() {
        return 1;
    }

    public Point getPost(int n) {
        return (n == 0) ? geom().getPoint1() : (n == 1) ? geom().getPoint2() : point3;
    }

    public void getInfo(String arr[]) {
        arr[0] = "tri-state buffer";
        arr[1] = open ? "open" : "closed";
        arr[2] = "Vd = " + getVoltageDText(getVoltageDiff());
        arr[3] = "I = " + getCurrentDText(getCurrent());
        arr[4] = "Vc = " + getVoltageText(getNodeVoltage(2));
    }

    // there is no current path through the input, but there
    // is an indirect path through the output to ground.
    public boolean getConnection(int n1, int n2) {
        return false;
    }

    public boolean hasGroundConnection(int n1) {
        return (n1 == 1);
    }

    public EditInfo getEditInfo(int n) {
        if (n == 0)
            return new EditInfo("On Resistance (ohms)", r_on, 0, 0);
        if (n == 1)
            return new EditInfo("Off Resistance (ohms)", r_off, 0, 0);
        if (n == 2)
            return new EditInfo("Output Pulldown Resistance (ohms)", r_off_ground, 0, 0);
        if (n == 3)
            return new EditInfo("High Logic Voltage", highVoltage, 1, 10);
        return null;
    }

    public void setEditValue(int n, EditInfo ei) {

        if (n == 0 && ei.value > 0)
            r_on = ei.value;
        if (n == 1 && ei.value > 0)
            r_off = ei.value;
        if (n == 2 && ei.value > 0)
            r_off_ground = ei.value;
        if (n == 3)
            highVoltage = GateElm.lastHighVoltage = ei.value;
    }

    public void flipX(int c2, int count) {
        flags ^= FLAG_FLIP | FLAG_FLIP_X;
        super.flipX(c2, count);
    }

    public void flipY(int c2, int count) {
        flags ^= FLAG_FLIP | FLAG_FLIP_Y;
        super.flipY(c2, count);
    }

    public void flipXY(int c2, int count) {
        flags ^= FLAG_FLIP;
        super.flipXY(c2, count);
    }

    @Override
    public String getJsonTypeName() {
        return "TriStateBuffer";
    }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        props.put("on_resistance", getUnitText(r_on, "Ohm"));
        props.put("off_resistance", getUnitText(r_off, "Ohm"));
        props.put("pulldown_resistance", getUnitText(r_off_ground, "Ohm"));
        props.put("high_voltage", getUnitText(highVoltage, "V"));
        return props;
    }

    @Override
    public String[] getJsonPinNames() {
        return new String[] { "in", "out", "enable" };
    }
}
