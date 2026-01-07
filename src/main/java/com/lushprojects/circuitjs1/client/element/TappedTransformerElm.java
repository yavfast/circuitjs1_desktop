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

import com.lushprojects.circuitjs1.client.Checkbox;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public class TappedTransformerElm extends CircuitElm {
    double inductance, ratio, couplingCoef;
    double primaryResistance, secondaryResistance1, secondaryResistance2;
    int flip;
    public static final int FLAG_FLIP = 1;
    // Coil spacing magnitude (pixels). The secondary full height is 2*spacing.
    int spacing = 32;
    // Tap position along the secondary (pixels from inner end), 0..2*spacing.
    int tapPos = 32;
    Point[] ptEnds;
    Point[] ptCoil;
    Point[] ptCore;
    double[] current;
    double[] curcount;

    public TappedTransformerElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy);
        inductance = 4;
        ratio = 1;
        noDiagonal = true;
        couplingCoef = .99;
        primaryResistance = 0.1;
        secondaryResistance1 = 0.1;
        secondaryResistance2 = 0.1;
        current = new double[4];
        curcount = new double[4];
        voltdiff = new double[3];
        curSourceValue = new double[3];
        a = new double[9];

        // Fixed nominal size on creation (no resize while adding).
        int nominalLen = 32;
        int nominalSpacing = 16;
        spacing = nominalSpacing;
        tapPos = nominalSpacing;
        setEndpoints(getX(), getY(), getX() + nominalLen, getY());
        setPoints();
    }

    public TappedTransformerElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
            StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f);
        inductance = parseDouble(st.nextToken());
        ratio = parseDouble(st.nextToken());
        current = new double[4];
        curcount = new double[4];
        current[0] = parseDouble(st.nextToken());
        current[1] = parseDouble(st.nextToken());
        current[2] = parseDouble(st.nextToken());
        couplingCoef = parseDouble(st.nextToken(), .99);
        primaryResistance = 0.1;
        secondaryResistance1 = 0.1;
        secondaryResistance2 = 0.1;
        voltdiff = new double[3];
        curSourceValue = new double[3];
        noDiagonal = true;
        a = new double[9];

        // Optional extra geometry fields (backward-compatible).
        if (st.hasMoreTokens()) {
            spacing = max(8, parseInt(st.nextToken()));
        }
        if (st.hasMoreTokens()) {
            tapPos = max(0, parseInt(st.nextToken()));
        }

        // Optional winding resistances (backward-compatible).
        if (st.hasMoreTokens()) {
            primaryResistance = parseDouble(st.nextToken(), primaryResistance);
        }
        if (st.hasMoreTokens()) {
            secondaryResistance1 = parseDouble(st.nextToken(), secondaryResistance1);
        }
        if (st.hasMoreTokens()) {
            secondaryResistance2 = parseDouble(st.nextToken(), secondaryResistance2);
        }
    }

    int getDumpType() {
        return 169;
    }

    public String dump() {
        return dumpValues(super.dump(), inductance, ratio, current[0], current[1], current[2], couplingCoef,
                spacing, tapPos, primaryResistance, secondaryResistance1, secondaryResistance2);
    }

    @Override
    public int getInternalNodeCount() {
        // One internal node per inductor segment to model series winding resistance.
        // pri: node0 -> int0 -> node1
        // sec1: node2 -> int1 -> node3
        // sec2: node3 -> int2 -> node4
        return 3;
    }

    @Override
    public boolean isFixedSizeOnCreate() {
        return true;
    }

    @Override
    int getNumHandles() {
        // 4 corners + tap handle
        return 5;
    }

    private int minSpacing() {
        return circuitEditor().gridSize * 4;
    }

    private int minLen() {
        return 32;
    }

    private int minTapSeg() {
        return circuitEditor().gridSize * 2;
    }

    @Override
    public Point getHandlePoint(int n) {
        if (ptEnds == null) {
            return super.getHandlePoint(n);
        }
        // Rectangle defined by point1->point2 axis and the full secondary offset
        // (2*spacing).
        int hs = spacing * flip;
        int outerOff = -hs * 2;
        if (n == 0) {
            return new Point(geom().getPoint1().x, geom().getPoint1().y);
        }
        if (n == 1) {
            return new Point(geom().getPoint2().x, geom().getPoint2().y);
        }
        if (n == 2) {
            return interpPoint(geom().getPoint1(), geom().getPoint2(), 1, outerOff);
        }
        if (n == 3) {
            return interpPoint(geom().getPoint1(), geom().getPoint2(), 0, outerOff);
        }
        if (n == 4) {
            // Tap post
            return ptEnds[3];
        }
        return super.getHandlePoint(n);
    }

    @Override
    public void movePoint(int n, int dx, int dy) {
        // Handles:
        // 0 = inner start (point1), 1 = inner end (point2),
        // 2 = outer end, 3 = outer start, 4 = tap slider
        int minLen = minLen();
        int minSpacing = minSpacing();

        if (n == 4) {
            // Slide tap along the secondary winding direction.
            boolean vertical = (geom().getPoint1().x == geom().getPoint2().x);
            int newTap = vertical ? (tapPos - dx * flip) : (tapPos + dy * flip);
            int segMin = minTapSeg();
            int maxTap = max(segMin, 2 * spacing - segMin);
            tapPos = max(segMin, min(maxTap, newTap));
            setPoints();
            return;
        }

        Point moved = getHandlePoint(n);
        if (moved == null) {
            return;
        }

        int opp;
        switch (n) {
            case 0:
                opp = 2;
                break;
            case 1:
                opp = 3;
                break;
            case 2:
                opp = 0;
                break;
            case 3:
                opp = 1;
                break;
            default:
                super.movePoint(n, dx, dy);
                return;
        }

        Point fixed = getHandlePoint(opp);
        if (fixed == null) {
            return;
        }

        int mx = circuitEditor().snapGrid(moved.x + dx);
        int my = circuitEditor().snapGrid(moved.y + dy);
        int fx = fixed.x;
        int fy = fixed.y;

        int newX = getX();
        int newY = getY();
        int newX2 = getX2();
        int newY2 = getY2();

        boolean vertical = (geom().getPoint1().x == geom().getPoint2().x);

        if (!vertical) {
            // Horizontal element
            int fullMin = 2 * minSpacing;

            if (n == 0) {
                // inner start moved, outer end fixed
                if (fx - mx < minLen)
                    mx = fx - minLen;
                if ((fy - my) * flip < fullMin)
                    my = fy - flip * fullMin;
                newX = mx;
                newY = my;
                newX2 = fx;
                newY2 = my;
                spacing = max(minSpacing, abs(fy - my) / 2);
            } else if (n == 2) {
                // outer end moved, inner start fixed
                if (mx - fx < minLen)
                    mx = fx + minLen;
                if ((my - fy) * flip < fullMin)
                    my = fy + flip * fullMin;
                newX = fx;
                newY = fy;
                newX2 = mx;
                newY2 = fy;
                spacing = max(minSpacing, abs(my - fy) / 2);
            } else if (n == 1) {
                // inner end moved, outer start fixed
                if (mx - fx < minLen)
                    mx = fx + minLen;
                if ((fy - my) * flip < fullMin)
                    my = fy - flip * fullMin;
                newX = fx;
                newY = my;
                newX2 = mx;
                newY2 = my;
                spacing = max(minSpacing, abs(fy - my) / 2);
            } else if (n == 3) {
                // outer start moved, inner end fixed
                if (fx - mx < minLen)
                    mx = fx - minLen;
                if ((my - fy) * flip < fullMin)
                    my = fy + flip * fullMin;
                newX = mx;
                newY = fy;
                newX2 = fx;
                newY2 = fy;
                spacing = max(minSpacing, abs(my - fy) / 2);
            }
        } else {
            // Vertical element
            int fullMin = 2 * minSpacing;

            if (n == 0) {
                // inner top moved, outer bottom fixed
                if (fy - my < minLen)
                    my = fy - minLen;
                if ((mx - fx) * flip < fullMin)
                    mx = fx + flip * fullMin;
                newX = mx;
                newY = my;
                newX2 = mx;
                newY2 = fy;
                spacing = max(minSpacing, abs(mx - fx) / 2);
            } else if (n == 2) {
                // outer bottom moved, inner top fixed
                if (my - fy < minLen)
                    my = fy + minLen;
                if ((fx - mx) * flip < fullMin)
                    mx = fx - flip * fullMin;
                newX = fx;
                newY = fy;
                newX2 = fx;
                newY2 = my;
                spacing = max(minSpacing, abs(fx - mx) / 2);
            } else if (n == 1) {
                // inner bottom moved, outer top fixed
                if (my - fy < minLen)
                    my = fy + minLen;
                if ((mx - fx) * flip < fullMin)
                    mx = fx + flip * fullMin;
                newX = mx;
                newY = fy;
                newX2 = mx;
                newY2 = my;
                spacing = max(minSpacing, abs(mx - fx) / 2);
            } else if (n == 3) {
                // outer top moved, inner bottom fixed
                if (fy - my < minLen)
                    my = fy - minLen;
                if ((fx - mx) * flip < fullMin)
                    mx = fx - flip * fullMin;
                newX = fx;
                newY = my;
                newX2 = fx;
                newY2 = fy;
                spacing = max(minSpacing, abs(fx - mx) / 2);
            }
        }

        // Keep tap inside valid range.
        int segMin = minTapSeg();
        tapPos = max(segMin, min(max(segMin, 2 * spacing - segMin), tapPos));
        // Consolidate explicit endpoint updates through ElmGeometry to ensure derived
        // fields are recalculated.
        setEndpoints(newX, newY, newX2, newY2);
        setPoints();
    }

    public void draw(Graphics g) {
        int i;
        for (i = 0; i != 5; i++) {
            setVoltageColor(g, getNodeVoltage(i));
            drawThickLine(g, ptEnds[i], ptCoil[i]);
        }
        for (i = 0; i != 4; i++) {
            if (i == 1)
                continue;
            setPowerColor(g, current[i] * (getNodeVoltage(i) - getNodeVoltage(i + 1)));
            drawCoil(g, i > 1 ? -6 * flip : 6 * flip,
                    ptCoil[i], ptCoil[i + 1], getNodeVoltage(i), getNodeVoltage(i + 1));
        }

        // winding labels (turns)
        g.save();
        g.setFont(unitsFont());
        g.setColor(needsHighlight() ? selectColor() : foregroundColor());
        double coreCx = 0, coreCy = 0;
        for (i = 0; i != 4; i++) {
            coreCx += ptCore[i].x;
            coreCy += ptCore[i].y;
        }
        coreCx /= 4;
        coreCy /= 4;
        double halfSecondaryTurns = Math.abs(ratio) / 2.0;
        for (i = 0; i != 4; i++) {
            if (i == 1)
                continue;
            String label;
            if (i == 0)
                label = "1T";
            else
                label = shortFormat(halfSecondaryTurns) + "T";
            Point a = ptCoil[i];
            Point b = ptCoil[i + 1];
            double mx = (a.x + b.x) / 2.0;
            double my = (a.y + b.y) / 2.0;
            double dxl = b.x - a.x;
            double dyl = b.y - a.y;
            double len = Math.sqrt(dxl * dxl + dyl * dyl);
            if (len < 1) {
                dxl = 1;
                dyl = 0;
                len = 1;
            }

            // unit perpendicular to coil segment
            double px = -dyl / len;
            double py = dxl / len;

            // choose side that points away from the core center
            double toCoreX = coreCx - mx;
            double toCoreY = coreCy - my;
            if (px * toCoreX + py * toCoreY > 0) {
                px = -px;
                py = -py;
            }

            int lx = (int) Math.round(mx + px * 12);
            int ly = (int) Math.round(my + py * 12);
            drawCenteredText(g, label, lx, ly, true);
        }
        g.restore();

        g.setColor(needsHighlight() ? selectColor() : elementColor());
        for (i = 0; i != 4; i += 2) {
            drawThickLine(g, ptCore[i], ptCore[i + 1]);
        }
        for (i = 0; i != 4; i++)
            curcount[i] = updateDotCount(current[i], curcount[i]);

        // primary dots
        drawDots(g, ptEnds[0], ptCoil[0], curcount[0]);
        drawDots(g, ptCoil[0], ptCoil[1], curcount[0]);
        drawDots(g, ptCoil[1], ptEnds[1], curcount[0]);

        // secondary dots
        drawDots(g, ptEnds[2], ptCoil[2], curcount[1]);
        drawDots(g, ptCoil[2], ptCoil[3], curcount[1]);
        drawDots(g, ptCoil[3], ptEnds[3], curcount[3]);
        drawDots(g, ptCoil[3], ptCoil[4], curcount[2]);
        drawDots(g, ptCoil[4], ptEnds[4], curcount[2]);

        drawPosts(g);
        setBbox(ptEnds[0], ptEnds[4], 0);
    }

    public void setPoints() {
        super.setPoints();
        flip = hasFlag(FLAG_FLIP) ? -1 : 1;
        int hs = max(minSpacing(), spacing) * flip;
        spacing = abs(hs);
        int segMin = minTapSeg();
        tapPos = max(segMin, min(max(segMin, 2 * spacing - segMin), tapPos));
        int tapOff = -tapPos * flip;
        if (ptEnds == null || ptEnds.length != 5)
            ptEnds = newPointArray(5);
        if (ptCoil == null || ptCoil.length != 5)
            ptCoil = newPointArray(5);
        if (ptCore == null)
            ptCore = newPointArray(4);
        ptEnds[0] = geom().getPoint1();
        ptEnds[2] = geom().getPoint2();
        interpPoint(geom().getPoint1(), geom().getPoint2(), ptEnds[1], 0, -hs * 2);
        interpPoint(geom().getPoint1(), geom().getPoint2(), ptEnds[3], 1, tapOff);
        interpPoint(geom().getPoint1(), geom().getPoint2(), ptEnds[4], 1, -hs * 2);
        double dn = getDn();
        double ce = .5 - 12 / dn;
        double cd = .5 - 2 / dn;
        interpPoint(ptEnds[0], ptEnds[2], ptCoil[0], ce);
        interpPoint(ptEnds[0], ptEnds[2], ptCoil[1], ce, -hs * 2);
        interpPoint(ptEnds[0], ptEnds[2], ptCoil[2], 1 - ce);
        interpPoint(ptEnds[0], ptEnds[2], ptCoil[3], 1 - ce, tapOff);
        interpPoint(ptEnds[0], ptEnds[2], ptCoil[4], 1 - ce, -hs * 2);
        for (int i = 0; i != 2; i++) {
            int b = -hs * i * 2;
            interpPoint(ptEnds[0], ptEnds[2], ptCore[i], cd, b);
            interpPoint(ptEnds[0], ptEnds[2], ptCore[i + 2], 1 - cd, b);
        }
    }

    public Point getPost(int n) {
        return ptEnds[n];
    }

    public int getPostCount() {
        return 5;
    }

    public void reset() {
        current[0] = current[1] = current[2] = current[3] = 0;
        setNodeVoltageDirect(0, 0);
        setNodeVoltageDirect(1, 0);
        setNodeVoltageDirect(2, 0);
        setNodeVoltageDirect(3, 0);
        setNodeVoltageDirect(4, 0);
        setNodeVoltageDirect(5, 0);
        setNodeVoltageDirect(6, 0);
        setNodeVoltageDirect(7, 0);
        curcount[0] = curcount[1] = curcount[2] = 0;
        // need to set current-source values here in case one of the nodes is node 0. In
        // that case
        // calculateCurrent() may get called (from setNodeVoltage()) when analyzing
        // circuit, before
        // startIteration() gets called
        curSourceValue[0] = curSourceValue[1] = curSourceValue[2] = 0;
    }

    double a[];

    public void stamp() {
        // equations for transformer:
        // v1 = L1 di1/dt + M1 di2/dt + M1 di3/dt
        // v2 = M1 di1/dt + L2 di2/dt + M2 di3/dt
        // v3 = M1 di1/dt + M2 di2/dt + L2 di3/dt
        // we invert that to get:
        // di1/dt = a1 v1 + a2 v2 + a3 v3
        // di2/dt = a4 v1 + a5 v2 + a6 v3
        // di3/dt = a7 v1 + a8 v2 + a9 v3
        // integrate di1/dt using trapezoidal approx and we get:
        // i1(t2) = i1(t1) + dt/2 (i1(t1) + i1(t2))
        // = i1(t1) + a1 dt/2 v1(t1)+a2 dt/2 v2(t1)+a3 dt/2 v3(t1) +
        // a1 dt/2 v1(t2)+a2 dt/2 v2(t2)+a3 dt/2 v3(t2)
        // the norton equivalent of this for i1 is:
        // a. current source, I = i1(t1) + a1 dt/2 v1(t1) + a2 dt/2 v2(t1)
        // + a3 dt/2 v3(t1)
        // b. resistor, G = a1 dt/2
        // c. current source controlled by voltage v2, G = a2 dt/2
        // d. current source controlled by voltage v3, G = a3 dt/2
        // and similarly for i2, i3
        //
        // Model winding resistance as series resistors placed on one end of each inductor segment.
        int priInt = getNode(5);
        int secInt1 = getNode(6);
        int secInt2 = getNode(7);

        if (primaryResistance > 0) {
            simulator().stampResistor(getNode(0), priInt, primaryResistance);
        } else {
            simulator().stampConductance(getNode(0), priInt, 1e8);
        }
        if (secondaryResistance1 > 0) {
            simulator().stampResistor(getNode(2), secInt1, secondaryResistance1);
        } else {
            simulator().stampConductance(getNode(2), secInt1, 1e8);
        }
        if (secondaryResistance2 > 0) {
            simulator().stampResistor(getNode(3), secInt2, secondaryResistance2);
        } else {
            simulator().stampConductance(getNode(3), secInt2, 1e8);
        }

        // first winding inductor goes from priInt to node 1, second is from secInt1->node3 and secInt2->node4
        double l1 = inductance;
        // second winding is split in half, so each part has half the turns;
        // we square the 1/2 to divide by 4
        double l2 = inductance * ratio * ratio / 4;
        double m1 = couplingCoef * Math.sqrt(l1 * l2);
        // mutual inductance between two halves of the second winding
        // is equal to self-inductance of either half (slightly less
        // because the coupling is not perfect)
        double m2 = couplingCoef * l2;
        // load pre-inverted matrix
        a[0] = l2 + m2;
        a[1] = a[2] = a[3] = a[6] = -m1;
        a[4] = a[8] = (l1 * l2 - m1 * m1) / (l2 - m2);
        a[5] = a[7] = (m1 * m1 - l1 * m2) / (l2 - m2);
        int i;
        double det = l1 * (l2 + m2) - 2 * m1 * m1;
        for (i = 0; i != 9; i++)
            a[i] *= (isTrapezoidal() ? simulator().timeStep / 2 : simulator().timeStep) / det;
        simulator().stampConductance(priInt, getNode(1), a[0]);
        simulator().stampVCCurrentSource(priInt, getNode(1), secInt1, getNode(3), a[1]);
        simulator().stampVCCurrentSource(priInt, getNode(1), secInt2, getNode(4), a[2]);

        simulator().stampVCCurrentSource(secInt1, getNode(3), priInt, getNode(1), a[3]);
        simulator().stampConductance(secInt1, getNode(3), a[4]);
        simulator().stampVCCurrentSource(secInt1, getNode(3), secInt2, getNode(4), a[5]);

        simulator().stampVCCurrentSource(secInt2, getNode(4), priInt, getNode(1), a[6]);
        simulator().stampVCCurrentSource(secInt2, getNode(4), secInt1, getNode(3), a[7]);
        simulator().stampConductance(secInt2, getNode(4), a[8]);

        for (i = 0; i != 5; i++)
            simulator().stampRightSide(getNode(i));
        simulator().stampRightSide(priInt);
        simulator().stampRightSide(secInt1);
        simulator().stampRightSide(secInt2);
    }

    boolean isTrapezoidal() {
        return (flags & Inductor.FLAG_BACK_EULER) == 0;
    }

    public void startIteration() {
        voltdiff[0] = getNodeVoltage(5) - getNodeVoltage(1);
        voltdiff[1] = getNodeVoltage(6) - getNodeVoltage(3);
        voltdiff[2] = getNodeVoltage(7) - getNodeVoltage(4);
        int i, j;
        for (i = 0; i != 3; i++) {
            curSourceValue[i] = current[i];
            if (isTrapezoidal())
                for (j = 0; j != 3; j++)
                    curSourceValue[i] += a[i * 3 + j] * voltdiff[j];
        }
    }

    double curSourceValue[], voltdiff[];

    public void doStep() {
        simulator().stampCurrentSource(getNode(5), getNode(1), curSourceValue[0]);
        simulator().stampCurrentSource(getNode(6), getNode(3), curSourceValue[1]);
        simulator().stampCurrentSource(getNode(7), getNode(4), curSourceValue[2]);
    }

    void calculateCurrent() {
        voltdiff[0] = getNodeVoltage(5) - getNodeVoltage(1);
        voltdiff[1] = getNodeVoltage(6) - getNodeVoltage(3);
        voltdiff[2] = getNodeVoltage(7) - getNodeVoltage(4);
        int i, j;
        for (i = 0; i != 3; i++) {
            current[i] = curSourceValue[i];
            for (j = 0; j != 3; j++)
                current[i] += a[i * 3 + j] * voltdiff[j];
        }
        // calc current of tap wire
        current[3] = current[1] - current[2];
    }

    public void getInfo(String arr[]) {
        arr[0] = "transformer";
        arr[1] = "L = " + getUnitText(inductance, "H");
        arr[2] = "Ratio = 1:" + ratio;
        // arr[3] = "I1 = " + getCurrentText(current1);
        arr[3] = "Vd1 = " + getVoltageText(getNodeVoltage(0) - getNodeVoltage(2));
        // arr[5] = "I2 = " + getCurrentText(current2);
        arr[4] = "Vd2 = " + getVoltageText(getNodeVoltage(1) - getNodeVoltage(3));
    }

    @Override
    public double getCurrentIntoNode(int n) {
        if (n == 0)
            return -current[0];
        if (n == 1)
            return current[0];
        if (n == 2)
            return -current[1];
        if (n == 3)
            return current[3];
        return current[2];
    }

    public boolean getConnection(int n1, int n2) {
        if (comparePair(n1, n2, 0, 1))
            return true;
        if (comparePair(n1, n2, 2, 3))
            return true;
        if (comparePair(n1, n2, 3, 4))
            return true;
        if (comparePair(n1, n2, 2, 4))
            return true;
        return false;
    }

    public EditInfo getEditInfo(int n) {
        if (n == 0)
            return new EditInfo("Primary Inductance (H)", inductance, .01, 5);
        if (n == 1)
            return new EditInfo("Ratio (N1/N2)", 1 / ratio, 1, 10).setDimensionless();
        if (n == 2)
            return new EditInfo("Coupling Coefficient", couplingCoef, 0, 1).setDimensionless();
        if (n == 3)
            return new EditInfo("Primary Resistance (Ohms)", primaryResistance, 0, 0);
        if (n == 4)
            return new EditInfo("Secondary Resistance 1 (Ohms)", secondaryResistance1, 0, 0);
        if (n == 5)
            return new EditInfo("Secondary Resistance 2 (Ohms)", secondaryResistance2, 0, 0);
        if (n == 6) {
            EditInfo ei = new EditInfo("", 0, -1, -1);
            ei.checkbox = new Checkbox("Trapezoidal Approximation",
                    isTrapezoidal());
            return ei;
        }
        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 0 && ei.value > 0)
            inductance = ei.value;
        if (n == 1 && ratio > 0)
            ratio = 1 / ei.value;
        if (n == 2 && ei.value > 0 && ei.value < 1)
            couplingCoef = ei.value;
        if (n == 3 && ei.value >= 0)
            primaryResistance = ei.value;
        if (n == 4 && ei.value >= 0)
            secondaryResistance1 = ei.value;
        if (n == 5 && ei.value >= 0)
            secondaryResistance2 = ei.value;
        if (n == 6) {
            if (ei.checkbox.getState())
                flags &= ~Inductor.FLAG_BACK_EULER;
            else
                flags |= Inductor.FLAG_BACK_EULER;
        }
    }

    @Override
    public boolean setPropertyValue(String property, double value) {
        if (property == null) {
            return false;
        }
        switch (property) {
            case "primary_resistance":
                if (value < 0) return false;
                primaryResistance = value;
                return true;
            case "secondary_resistance_1":
                if (value < 0) return false;
                secondaryResistance1 = value;
                return true;
            case "secondary_resistance_2":
                if (value < 0) return false;
                secondaryResistance2 = value;
                return true;
            case "coupling_coefficient":
                if (value <= 0 || value >= 1) return false;
                couplingCoef = value;
                return true;
            default:
                return false;
        }
    }

    public void flipX(int c2, int count) {
        flags ^= FLAG_FLIP;
        super.flipX(c2, count);
    }

    public void flipY(int c2, int count) {
        flags ^= FLAG_FLIP;
        super.flipY(c2, count);
    }

    public void flipXY(int c2, int count) {
        flags ^= FLAG_FLIP;
        super.flipXY(c2, count);
    }

    @Override
    public String getJsonTypeName() {
        return "TappedTransformer";
    }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        props.put("primary_inductance", getUnitText(inductance, "H"));
        props.put("ratio", ratio);
        props.put("coupling_coefficient", couplingCoef);
        props.put("primary_resistance", getUnitText(primaryResistance, "Ohm"));
        props.put("secondary_resistance_1", getUnitText(secondaryResistance1, "Ohm"));
        props.put("secondary_resistance_2", getUnitText(secondaryResistance2, "Ohm"));
        props.put("trapezoidal", isTrapezoidal());
        return props;
    }

    @Override
    public void applyJsonProperties(java.util.Map<String, Object> props) {
        super.applyJsonProperties(props);
        inductance = com.lushprojects.circuitjs1.client.io.json.UnitParser.parse(
                getJsonString(props, "primary_inductance", "4 H"));
        ratio = getJsonDouble(props, "ratio", 1);
        couplingCoef = getJsonDouble(props, "coupling_coefficient", 0.99);
        if (couplingCoef <= 0 || couplingCoef >= 1) couplingCoef = 0.99;

        primaryResistance = getJsonDouble(props, "primary_resistance", 0.1);
        secondaryResistance1 = getJsonDouble(props, "secondary_resistance_1", 0.1);
        secondaryResistance2 = getJsonDouble(props, "secondary_resistance_2", 0.1);
        if (primaryResistance < 0) primaryResistance = 0.1;
        if (secondaryResistance1 < 0) secondaryResistance1 = 0.1;
        if (secondaryResistance2 < 0) secondaryResistance2 = 0.1;

        boolean trap = getJsonBoolean(props, "trapezoidal", isTrapezoidal());
        if (trap) flags &= ~Inductor.FLAG_BACK_EULER; else flags |= Inductor.FLAG_BACK_EULER;
    }

    @Override
    public String[] getJsonPinNames() {
        return new String[] { "pri1", "pri2", "sec1", "tap", "sec2" };
    }

    @Override
    public java.util.Map<String, Object> getJsonState() {
        java.util.Map<String, Object> state = super.getJsonState();
        state.put("current0", current[0]);
        state.put("current1", current[1]);
        state.put("current2", current[2]);
        state.put("current3", current[3]);
        return state;
    }

    @Override
    public void applyJsonState(java.util.Map<String, Object> state) {
        super.applyJsonState(state);
        if (state.containsKey("current0"))
            current[0] = ((Number) state.get("current0")).doubleValue();
        if (state.containsKey("current1"))
            current[1] = ((Number) state.get("current1")).doubleValue();
        if (state.containsKey("current2"))
            current[2] = ((Number) state.get("current2")).doubleValue();
        if (state.containsKey("current3"))
            current[3] = ((Number) state.get("current3")).doubleValue();
    }
}
