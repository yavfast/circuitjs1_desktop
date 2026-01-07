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

// Silicon-Controlled Rectifier
// 3 nodes, 1 internal node
// 0 = anode, 1 = cathode, 2 = gate
// 0, 3 = variable resistor
// 3, 1 = diode
// 2, 1 = 50 ohm resistor

import com.lushprojects.circuitjs1.client.Diode;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.Polygon;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public class SCRElm extends CircuitElm {
    final int anode = 0;
    final int cnode = 1;
    final int gnode = 2;
    final int inode = 3;
    final int FLAG_GATE_FIX = 1;
    Diode diode;
    int dir;

    public SCRElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy);
        setDefaults();
        flags |= FLAG_GATE_FIX;
        setup();
    }

    public SCRElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
            StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f);
        setDefaults();
        try {
            lastvac = parseDouble(st.nextToken());
            lastvag = parseDouble(st.nextToken());
            setNodeVoltageDirect(anode, 0);
            setNodeVoltageDirect(cnode, -lastvac);
            setNodeVoltageDirect(gnode, -lastvag);
            triggerI = parseDouble(st.nextToken());
            holdingI = parseDouble(st.nextToken());
            gresistance = parseDouble(st.nextToken());
        } catch (Exception e) {
        }
        setup();
    }

    void setDefaults() {
        gresistance = 50;
        holdingI = .0082;
        triggerI = .01;
    }

    void setup() {
        diode = new Diode();
        diode.setupForDefaultModel();
        aresistance = 1; // to avoid divide by zero
    }

    public boolean nonLinear() {
        return true;
    }

    public void reset() {
        setNodeVoltageDirect(anode, 0);
        setNodeVoltageDirect(cnode, 0);
        setNodeVoltageDirect(gnode, 0);
        diode.reset();
        lastvag = lastvac = curcount_a = curcount_c = curcount_g = 0;
    }

    int getDumpType() {
        return 177;
    }

    public String dump() {
        return dumpValues(super.dump(), (getNodeVoltage(anode) - getNodeVoltage(cnode)),
                (getNodeVoltage(anode) - getNodeVoltage(gnode)), triggerI, holdingI, gresistance);
    }

    double ia, ic, ig, curcount_a, curcount_c, curcount_g;
    double lastvac, lastvag;
    double gresistance, triggerI, holdingI;

    final int hs = 8;
    Polygon poly;
    Point cathode[], gate[];

    boolean applyGateFix() {
        return (flags & FLAG_GATE_FIX) != 0;
    }

    public void setPoints() {
        super.setPoints();
        dir = 0;
        int dx = getDx();
        int dy = getDy();
        if (abs(dx) > abs(dy)) {
            dir = -sign(dx) * sign(dy);

            // If gate-fix is enabled, align endpoints via ElmGeometry (updates dn/derived).
            // Otherwise, keep light-touch behavior
            if (applyGateFix()) {
                geom().setEndpoints(getX(), getY(), getX2(), getY());
            } else {
                geom().getPoint2().y = geom().getPoint1().y;
            }
        } else {
            dir = sign(dy) * sign(dx);
            if (applyGateFix()) {
                geom().setEndpoints(getX(), getY(), getX(), getY2());
            } else {
                geom().getPoint2().x = geom().getPoint1().x;
            }
        }
        if (dir == 0)
            dir = 1;
        calcLeads(16);
        cathode = newPointArray(2);
        Point pa[] = newPointArray(2);
        interpPoint2(geom().getLead1(), geom().getLead2(), pa[0], pa[1], 0, hs);
        interpPoint2(geom().getLead1(), geom().getLead2(), cathode[0], cathode[1], 1, hs);
        poly = createPolygon(pa[0], pa[1], geom().getLead2());

        gate = newPointArray(2);
        double leadlen = (getDn() - 16) / 2;
        int gatelen = circuitEditor().gridSize;
        gatelen += leadlen % circuitEditor().gridSize;
        if (leadlen < gatelen) {
            geom().setEndpoints(getX(), getY(), getX(), getY());
            return;
        }
        interpPoint(geom().getLead2(), geom().getPoint2(), gate[0], gatelen / leadlen, gatelen * dir);
        interpPoint(geom().getLead2(), geom().getPoint2(), gate[1], gatelen / leadlen,
                circuitEditor().gridSize * 2 * dir);
        gate[1].x = circuitEditor().snapGrid(gate[1].x);
        gate[1].y = circuitEditor().snapGrid(gate[1].y);
    }

    public void draw(Graphics g) {
        setBbox(geom().getPoint1(), geom().getPoint2(), hs);
        adjustBbox(gate[0], gate[1]);

        double v1 = getNodeVoltage(anode);
        double v2 = getNodeVoltage(cnode);

        draw2Leads(g);

        // draw arrow thingy
        setVoltageColor(g, v1);
        setPowerColor(g, true);
        g.fillPolygon(poly);

        setVoltageColor(g, getNodeVoltage(gnode));
        drawThickLine(g, geom().getLead2(), gate[0]);
        drawThickLine(g, gate[0], gate[1]);

        // draw thing arrow is pointing to
        setVoltageColor(g, v2);
        setPowerColor(g, true);
        drawThickLine(g, cathode[0], cathode[1]);

        curcount_a = updateDotCount(ia, curcount_a);
        curcount_c = updateDotCount(ic, curcount_c);
        curcount_g = updateDotCount(ig, curcount_g);
        if (circuitEditor().dragElm != this) {
            drawDots(g, geom().getPoint1(), geom().getLead2(), curcount_a);
            drawDots(g, geom().getPoint2(), geom().getLead2(), curcount_c);
            drawDots(g, gate[1], gate[0], curcount_g);
            drawDots(g, gate[0], geom().getLead2(), curcount_g + distance(gate[1], gate[0]));
        }

        int _dx = getDx();
        if ((needsHighlight() || circuitEditor().dragElm == this) && geom().getPoint1().x == geom().getPoint2().x
                && geom().getPoint2().y > geom().getPoint1().y) {
            g.setColor(foregroundColor());
            int ds = sign(_dx);
            g.drawString("C", geom().getLead2().x + ((ds < 0) ? 5 : -15), geom().getLead2().y + 12);
            g.drawString("A", geom().getLead1().x + 5, geom().getLead1().y - 4); // x+6 if ds=1, -12 if -1
            g.drawString("G", gate[0].x, gate[0].y + 12);
        }

        drawPosts(g);
    }

    public double getCurrentIntoNode(int n) {
        if (n == anode)
            return -ia;
        if (n == cnode)
            return -ic;
        return -ig;
    }

    public Point getPost(int n) {
        return (n == 0) ? geom().getPoint1() : (n == 1) ? geom().getPoint2() : gate[1];
    }

    // ...
    // if point1 and point2 are in line, then we don't know which way the gate
    // is pointed and flip won't work. fix this

    public int getPostCount() {
        return 3;
    }

    public int getInternalNodeCount() {
        return 1;
    }

    public double getPower() {
        return (getNodeVoltage(anode) - getNodeVoltage(gnode)) * ia
                + (getNodeVoltage(cnode) - getNodeVoltage(gnode)) * ic;
    }

    double aresistance;

    public void stamp() {
        simulator().stampNonLinear(getNode(anode));
        simulator().stampNonLinear(getNode(cnode));
        simulator().stampNonLinear(getNode(gnode));
        simulator().stampNonLinear(getNode(inode));
        simulator().stampResistor(getNode(gnode), getNode(cnode), gresistance);
        diode.stamp(getNode(inode), getNode(cnode));
    }

    public void doStep() {
        double vac = getNodeVoltage(anode) - getNodeVoltage(cnode); // typically negative
        double vag = getNodeVoltage(anode) - getNodeVoltage(gnode); // typically positive
        if (Math.abs(vac - lastvac) > .01 ||
                Math.abs(vag - lastvag) > .01)
            simulator().converged = false;
        lastvac = vac;
        lastvag = vag;
        diode.doStep(getNodeVoltage(inode) - getNodeVoltage(cnode));
        double icmult = 1 / triggerI;
        double iamult = 1 / holdingI - icmult;
        // System.out.println(icmult + " " + iamult);
        aresistance = (-icmult * ic + ia * iamult > 1) ? .0105 : 10e5;
        // System.out.println(vac + " " + vag + " " + sim.converged + " " + ic + " " +
        // ia + " " + aresistance + " " + getNodeVoltage(inode) + " " +
        // getNodeVoltage(gnode) + " " + getNodeVoltage(anode));
        simulator().stampResistor(getNode(anode), getNode(inode), aresistance);
    }

    public void getInfo(String arr[]) {
        arr[0] = "SCR";
        double vac = getNodeVoltage(anode) - getNodeVoltage(cnode);
        double vag = getNodeVoltage(anode) - getNodeVoltage(gnode);
        double vgc = getNodeVoltage(gnode) - getNodeVoltage(cnode);
        arr[1] = "Ia = " + getCurrentText(ia);
        arr[2] = "Ig = " + getCurrentText(ig);
        arr[3] = "Vac = " + getVoltageText(vac);
        arr[4] = "Vag = " + getVoltageText(vag);
        arr[5] = "Vgc = " + getVoltageText(vgc);
        arr[6] = "P = " + getUnitText(getPower(), "W");
    }

    void calculateCurrent() {
        ig = (getNodeVoltage(gnode) - getNodeVoltage(cnode)) / gresistance;
        ia = (getNodeVoltage(anode) - getNodeVoltage(inode)) / aresistance;
        ic = -ig - ia;
    }

    public EditInfo getEditInfo(int n) {
        if (n == 0)
            return new EditInfo("Trigger Current (A)", triggerI, 0, 0);
        if (n == 1)
            return new EditInfo("Holding Current (A)", holdingI, 0, 0);
        if (n == 2)
            return new EditInfo("Gate Resistance (ohms)", gresistance, 0, 0);
        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 0 && ei.value > 0)
            triggerI = ei.value;
        if (n == 1 && ei.value > 0)
            holdingI = ei.value;
        if (n == 2 && ei.value > 0)
            gresistance = ei.value;
    }

    // if point1 and point2 are in line, then we don't know which way the gate
    // is pointed and flip won't work. fix this
    void fixEnds() {
        Point pt = new Point();
        interpPoint(geom().getPoint1(), geom().getPoint2(), pt, 1, circuitEditor().gridSize * dir);
        setEndpoints(getX(), getY(), pt.x, pt.y);
    }

    public void flipX(int c2, int count) {
        fixEnds();
        super.flipX(c2, count);
    }

    public void flipY(int c2, int count) {
        fixEnds();
        super.flipY(c2, count);
    }

    public void flipXY(int c2, int count) {
        fixEnds();
        super.flipXY(c2, count);
    }

    @Override
    public void setCircuitDocument(com.lushprojects.circuitjs1.client.CircuitDocument circuitDocument) {
        super.setCircuitDocument(circuitDocument);
        diode.setSimulator(circuitDocument.simulator);
    }

    @Override
    public String getJsonTypeName() {
        return "SCR";
    }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        props.put("trigger_current", getUnitText(triggerI, "A"));
        props.put("holding_current", getUnitText(holdingI, "A"));
        props.put("gate_resistance", getUnitText(gresistance, "Ohm"));
        return props;
    }

    @Override
    public String[] getJsonPinNames() {
        return new String[] { "anode", "cathode", "gate" };
    }

    @Override
    public java.util.Map<String, Object> getJsonState() {
        java.util.Map<String, Object> state = super.getJsonState();
        if (state == null) {
            state = new java.util.LinkedHashMap<>();
        }
        // Export SCR state
        if (Double.isFinite(lastvac)) {
            state.put("lastvac", lastvac);
        }
        if (Double.isFinite(lastvag)) {
            state.put("lastvag", lastvag);
        }
        if (Double.isFinite(aresistance)) {
            state.put("aresistance", aresistance);
        }
        return state.isEmpty() ? null : state;
    }

    @Override
    public void applyJsonState(java.util.Map<String, Object> state) {
        super.applyJsonState(state);
        if (state != null) {
            lastvac = getJsonDouble(state, "lastvac", 0);
            lastvag = getJsonDouble(state, "lastvag", 0);
            aresistance = getJsonDouble(state, "aresistance", 1);
        }
    }
}
