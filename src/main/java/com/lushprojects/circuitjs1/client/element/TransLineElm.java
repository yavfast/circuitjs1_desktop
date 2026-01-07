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
import com.lushprojects.circuitjs1.client.Color;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;
import com.lushprojects.circuitjs1.client.util.Locale;

public class TransLineElm extends CircuitElm {
    double delay, imped;
    double voltageL[], voltageR[];
    int lenSteps, ptr, width;
    int lastStepCount;

    public TransLineElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy);
        delay = 1000 * simulator().maxTimeStep;
        imped = 75;
        noDiagonal = true;
        reset();
    }

    public TransLineElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
            StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f);
        delay = parseDouble(st.nextToken());
        imped = parseDouble(st.nextToken());
        width = parseInt(st.nextToken());
        // next slot is for resistance (losses), which is not implemented
        st.nextToken();
        noDiagonal = true;
        reset();
    }

    int getDumpType() {
        return 171;
    }

    public int getPostCount() {
        return 4;
    }

    public int getInternalNodeCount() {
        return 2;
    }

    public String dump() {
        return dumpValues(super.dump(), delay, imped, width, 0.);
    }

    public void drag(int xx, int yy) {
        xx = circuitEditor().snapGrid(xx);
        yy = circuitEditor().snapGrid(yy);
        int w1 = max(circuitEditor().gridSize, abs(yy - getY()));
        int w2 = max(circuitEditor().gridSize, abs(xx - getX()));
        if (w1 > w2) {
            xx = getX();
            width = w2;
        } else {
            yy = getY();
            width = w1;
        }
        setEndpoints(getX(), getY(), xx, yy);
        setPoints();
    }

    Point posts[], inner[];
    private Point p3tmp, p4tmp, p5tmp, p6tmp, p7tmp, p8tmp;

    public void reset() {
        if (simulator().maxTimeStep == 0)
            return;
        lenSteps = (int) (delay / simulator().maxTimeStep);
        System.out.println(lenSteps + " steps");
        if (lenSteps > 100000)
            voltageL = voltageR = null;
        else {
            voltageL = new double[lenSteps];
            voltageR = new double[lenSteps];
        }
        ptr = 0;
        super.reset();
        lastStepCount = 0;
    }

    public void setPoints() {
        super.setPoints();
        int dx = getDx();
        int dy = getDy();
        int ds = (dy == 0) ? sign(dx) : -sign(dy);
        if (p3tmp == null)
            p3tmp = new Point();
        if (p4tmp == null)
            p4tmp = new Point();
        if (p5tmp == null)
            p5tmp = new Point();
        if (p6tmp == null)
            p6tmp = new Point();
        if (p7tmp == null)
            p7tmp = new Point();
        if (p8tmp == null)
            p8tmp = new Point();
        interpPoint(geom().getPoint1(), geom().getPoint2(), p3tmp, 0, -width * ds);
        interpPoint(geom().getPoint1(), geom().getPoint2(), p4tmp, 1, -width * ds);
        int sep = circuitEditor().gridSize / 2;
        interpPoint(geom().getPoint1(), geom().getPoint2(), p5tmp, 0, -(width / 2 - sep) * ds);
        interpPoint(geom().getPoint1(), geom().getPoint2(), p6tmp, 1, -(width / 2 - sep) * ds);
        interpPoint(geom().getPoint1(), geom().getPoint2(), p7tmp, 0, -(width / 2 + sep) * ds);
        interpPoint(geom().getPoint1(), geom().getPoint2(), p8tmp, 1, -(width / 2 + sep) * ds);

        // we number the posts like this because we want the lower-numbered
        // points to be on the bottom, so that if some of them are unconnected
        // (which is often true) then the bottom ones will get automatically
        // attached to ground.
        if (posts == null)
            posts = new Point[4];
        if (inner == null)
            inner = new Point[4];
        posts[0] = p3tmp;
        posts[1] = p4tmp;
        posts[2] = geom().getPoint1();
        posts[3] = geom().getPoint2();
        inner[0] = p7tmp;
        inner[1] = p8tmp;
        inner[2] = p5tmp;
        inner[3] = p6tmp;
    }

    public void draw(Graphics g) {
        double dn = getDn();
        setBbox(posts[0], posts[3], 0);
        int segments = (int) (dn / 2);
        int ix0 = ptr - 1 + lenSteps;
        double segf = 1. / segments;
        int i;
        g.setColor(Color.darkGray);
        g.fillRect(inner[2].x, inner[2].y,
                inner[1].x - inner[2].x + 2, inner[1].y - inner[2].y + 2);
        for (i = 0; i != 4; i++) {
            setVoltageColor(g, getNodeVoltage(i));
            drawThickLine(g, posts[i], inner[i]);
        }
        if (voltageL != null) {
            for (i = 0; i != segments; i++) {
                int ix1 = (ix0 - lenSteps * i / segments) % lenSteps;
                int ix2 = (ix0 - lenSteps * (segments - 1 - i) / segments) % lenSteps;
                double v = (voltageL[ix1] + voltageR[ix2]) / 2;
                setVoltageColor(g, v);
                interpPoint(inner[0], inner[1], ps1, i * segf);
                interpPoint(inner[2], inner[3], ps2, i * segf);
                g.drawLine(ps1.x, ps1.y, ps2.x, ps2.y);
                interpPoint(inner[2], inner[3], ps1, (i + 1) * segf);
                drawThickLine(g, ps1, ps2);
            }
        }
        setVoltageColor(g, getNodeVoltage(0));
        drawThickLine(g, inner[0], inner[1]);
        drawPosts(g);

        curCount1 = updateDotCount(-current1, curCount1);
        curCount2 = updateDotCount(current2, curCount2);
        if (circuitEditor().dragElm != this) {
            drawDots(g, posts[0], inner[0], curCount1);
            drawDots(g, posts[2], inner[2], -curCount1);
            drawDots(g, posts[1], inner[1], -curCount2);
            drawDots(g, posts[3], inner[3], curCount2);
        }
    }

    int voltSource1, voltSource2;
    double current1, current2, curCount1, curCount2;

    public void setVoltageSource(int n, int v) {
        if (n == 0)
            voltSource1 = v;
        else
            voltSource2 = v;
    }

    public void setCurrent(int v, double c) {
        if (v == voltSource1)
            current1 = c;
        else
            current2 = c;
    }

    public void stamp() {
        CircuitSimulator simulator = simulator();
        simulator.stampVoltageSource(getNode(4), getNode(0), voltSource1);
        simulator.stampVoltageSource(getNode(5), getNode(1), voltSource2);
        simulator.stampResistor(getNode(2), getNode(4), imped);
        simulator.stampResistor(getNode(3), getNode(5), imped);
    }

    public void startIteration() {
        // calculate voltages, currents sent over wire
        if (voltageL == null) {
            simulator().stop("Transmission line delay too large!", this);
            return;
        }
        double v0 = getNodeVoltage(0);
        double v1 = getNodeVoltage(1);
        double v2 = getNodeVoltage(2);
        double v3 = getNodeVoltage(3);
        double v4 = getNodeVoltage(4);
        double v5 = getNodeVoltage(5);
        voltageL[ptr] = v2 - v0 + v2 - v4;
        voltageR[ptr] = v3 - v1 + v3 - v5;
        // System.out.println(v2 + " " + v0 + " " + (v2-v0) + " " + (imped*current1) + "
        // " + voltageL[ptr]);
        /*
         * System.out.println("sending fwd  " + currentL[ptr] + " " + current1);
         * System.out.println("sending back " + currentR[ptr] + " " + current2);
         */
        // System.out.println("sending back " + voltageR[ptr]);
    }

    public void doStep() {
        if (voltageL == null) {
            simulator().stop("Transmission line delay too large!", this);
            return;
        }
        int nextPtr = (ptr + 1) % lenSteps;
        CircuitSimulator simulator = simulator();
        simulator.updateVoltageSource(getNode(4), getNode(0), voltSource1, -voltageR[nextPtr]);
        simulator.updateVoltageSource(getNode(5), getNode(1), voltSource2, -voltageL[nextPtr]);
        if (Math.abs(getNodeVoltage(0)) > 1e-5 || Math.abs(getNodeVoltage(1)) > 1e-5) {
            simulator().stop("Need to ground transmission line!", this);
            return;
        }
    }

    public void stepFinished() {
        if (simulator().timeStepCount == lastStepCount)
            return;
        lastStepCount = simulator().timeStepCount;
        ptr = (ptr + 1) % lenSteps;
    }

    public Point getPost(int n) {
        return posts[n];
    }

    // double getVoltageDiff() { return getNodeVoltage(0); }
    public int getVoltageSourceCount() {
        return 2;
    }

    public boolean hasGroundConnection(int n1) {
        return false;
    }

    public boolean getConnection(int n1, int n2) {
        return false;
        /*
         * if (comparePair(n1, n2, 0, 1))
         * return true;
         * if (comparePair(n1, n2, 2, 3))
         * return true;
         * return false;
         */
    }

    public void getInfo(String arr[]) {
        arr[0] = "transmission line";
        arr[1] = getUnitText(imped, Locale.ohmString);
        // use velocity factor for RG-58 cable (65%)
        arr[2] = "length = " + getUnitText(.65 * 2.9979e8 * delay, "m");
        arr[3] = "delay = " + getUnitText(delay, "s");
    }

    public EditInfo getEditInfo(int n) {
        if (n == 0)
            return new EditInfo("Delay (s)", delay, 0, 0);
        if (n == 1)
            return new EditInfo("Impedance (ohms)", imped, 0, 0);
        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 0 && ei.value > 0) {
            delay = ei.value;
            reset();
        }
        if (n == 1 && ei.value > 0) {
            imped = ei.value;
            reset();
        }
    }

    public double getCurrentIntoNode(int n) {
        if (n == 0)
            return current1;
        if (n == 2)
            return -current1;
        if (n == 3)
            return -current2;
        return current2;
    }

    public boolean canFlipX() {
        int _dy = getDy();
        return _dy == 0;
    }

    public boolean canFlipY() {
        int _dx = getDx();
        return _dx == 0;
    }

    @Override
    public String getJsonTypeName() {
        return "TransmissionLine";
    }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        props.put("delay", getUnitText(delay, "s"));
        props.put("impedance", getUnitText(imped, "Ohm"));
        props.put("width", width);
        return props;
    }

    @Override
    public String[] getJsonPinNames() {
        return new String[] { "gnd_in", "gnd_out", "sig_in", "sig_out" };
    }

    @Override
    public java.util.Map<String, Object> getJsonState() {
        java.util.Map<String, Object> state = super.getJsonState();
        if (state == null) {
            state = new java.util.LinkedHashMap<>();
        }
        // Export transmission line state
        state.put("ptr", ptr);
        if (Double.isFinite(current1)) {
            state.put("current1", current1);
        }
        if (Double.isFinite(current2)) {
            state.put("current2", current2);
        }
        // Export delay line buffers if not too large
        if (voltageL != null && lenSteps <= 1000) {
            java.util.List<Double> vL = new java.util.ArrayList<>();
            java.util.List<Double> vR = new java.util.ArrayList<>();
            for (int i = 0; i < lenSteps; i++) {
                vL.add(voltageL[i]);
                vR.add(voltageR[i]);
            }
            state.put("voltageL", vL);
            state.put("voltageR", vR);
        }
        return state;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void applyJsonState(java.util.Map<String, Object> stateMap) {
        super.applyJsonState(stateMap);
        if (stateMap != null) {
            ptr = getJsonInt(stateMap, "ptr", 0);
            current1 = getJsonDouble(stateMap, "current1", 0);
            current2 = getJsonDouble(stateMap, "current2", 0);
            // Restore delay line buffers
            Object vLObj = stateMap.get("voltageL");
            Object vRObj = stateMap.get("voltageR");
            if (vLObj instanceof java.util.List && vRObj instanceof java.util.List) {
                java.util.List<Double> vL = (java.util.List<Double>) vLObj;
                java.util.List<Double> vR = (java.util.List<Double>) vRObj;
                if (voltageL != null && voltageL.length == vL.size()) {
                    for (int i = 0; i < vL.size(); i++) {
                        voltageL[i] = vL.get(i);
                        voltageR[i] = vR.get(i);
                    }
                }
            }
        }
    }
}
