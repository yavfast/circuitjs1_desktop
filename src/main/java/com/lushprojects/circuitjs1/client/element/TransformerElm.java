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
import com.lushprojects.circuitjs1.client.CircuitSimulator;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public class TransformerElm extends CircuitElm {
    double inductance, ratio, couplingCoef;
    Point ptEnds[], ptCoil[], ptCore[];
    double current[], curcount[];
    Point dots[];
    int width, polarity, flip;
    public static final int FLAG_REVERSE = 4;
    public static final int FLAG_VERTICAL = 8;
    public static final int FLAG_FLIP = 16;

    public TransformerElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy);
        inductance = 4;
        ratio = polarity = 1;
        width = 32;
        noDiagonal = true;
        couplingCoef = .999;
        current = new double[2];
        curcount = new double[2];

        // Fixed nominal size on creation (no resize while adding).
        int grid = circuitEditor().gridSize;
        int nominalLen = grid * 8;
        int nominalWidth = grid * 4;
        flags &= ~FLAG_VERTICAL;
        width = nominalWidth;
        x2 = x + nominalLen;
        y2 = y + nominalWidth;
        setPoints();
    }

    public TransformerElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
                          StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f);
        if (hasFlag(FLAG_VERTICAL))
            width = -max(32, abs(xb - xa));
        else
            width = max(32, abs(yb - ya));

        // If the saved endpoints are axis-aligned (no diagonal), synthesize the
        // diagonal corner used by resize handles from the spacing.
        if (hasFlag(FLAG_VERTICAL)) {
            if (x2 == x)
                x2 = x + abs(width);
        } else {
            if (y2 == y)
                y2 = y + abs(width);
        }

        inductance = parseDouble(st.nextToken());
        ratio = parseDouble(st.nextToken());
        current = new double[2];
        curcount = new double[2];
        current[0] = parseDouble(st.nextToken());
        current[1] = parseDouble(st.nextToken());
        couplingCoef = parseDouble(st.nextToken(), .999);
        noDiagonal = true;
        polarity = (hasFlag(FLAG_REVERSE)) ? -1 : 1;
    }

    @Override
    protected String getIdPrefix() {
        return "T";
    }

    @Override
    public boolean isFixedSizeOnCreate() {
        return true;
    }

    @Override
    int getNumHandles() {
        return 4;
    }

    @Override
    public Point getHandlePoint(int n) {
        // Handles are the 4 rectangle corners. The underlying data for this element
        // stores (x,y) and (x2,y2) as diagonal corners (used for length + spacing).
        if (hasFlag(FLAG_VERTICAL)) {
            switch (n) {
                case 0:
                    return new Point(x, y);
                case 1:
                    return new Point(x, y2);
                case 2:
                    return new Point(x2, y2);
                case 3:
                    return new Point(x2, y);
                default:
                    return super.getHandlePoint(n);
            }
        }

        switch (n) {
            case 0:
                return new Point(x, y);
            case 1:
                return new Point(x2, y);
            case 2:
                return new Point(x2, y2);
            case 3:
                return new Point(x, y2);
            default:
                return super.getHandlePoint(n);
        }
    }

    public void drag(int xx, int yy) {
        int sx = circuitEditor().snapGrid(xx);
        int sy = circuitEditor().snapGrid(yy);

        // Keep the end point diagonal:
        // - major axis chooses orientation
        // - minor axis controls winding spacing (width)
        if (abs(sx - x) > abs(sy - y)) {
            flags &= ~FLAG_VERTICAL;
        } else {
            flags |= FLAG_VERTICAL;
        }

        if (hasFlag(FLAG_VERTICAL))
            width = -max(32, abs(sx - x));
        else
            width = max(32, abs(sy - y));

        x2 = sx;
        y2 = sy;
        setPoints();
    }

    @Override
    public void movePoint(int n, int dx, int dy) {
        // 4-corner resizing with minimum nominal size. Do not change orientation.
        int minLen = 32;
        int minWidth = 32;

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

        if (hasFlag(FLAG_VERTICAL)) {
            // Keep rectangle ordered: x <= x2, y <= y2
            if (n == 0 || n == 1) {
                if (fx - mx < minWidth)
                    mx = fx - minWidth;
            } else {
                if (mx - fx < minWidth)
                    mx = fx + minWidth;
            }
            if (n == 0 || n == 3) {
                if (fy - my < minLen)
                    my = fy - minLen;
            } else {
                if (my - fy < minLen)
                    my = fy + minLen;
            }

            int nx1 = (n == 0 || n == 1) ? mx : fx;
            int nx2 = (n == 0 || n == 1) ? fx : mx;
            int ny1 = (n == 0 || n == 3) ? my : fy;
            int ny2 = (n == 0 || n == 3) ? fy : my;

            x = nx1;
            y = ny1;
            x2 = nx2;
            y2 = ny2;

            width = -max(minWidth, abs(x2 - x));
        } else {
            // Horizontal
            if (n == 0 || n == 3) {
                if (fx - mx < minLen)
                    mx = fx - minLen;
            } else {
                if (mx - fx < minLen)
                    mx = fx + minLen;
            }
            if (n == 0 || n == 1) {
                if (fy - my < minWidth)
                    my = fy - minWidth;
            } else {
                if (my - fy < minWidth)
                    my = fy + minWidth;
            }

            int nx1 = (n == 0 || n == 3) ? mx : fx;
            int nx2 = (n == 0 || n == 3) ? fx : mx;
            int ny1 = (n == 0 || n == 1) ? my : fy;
            int ny2 = (n == 0 || n == 1) ? fy : my;

            x = nx1;
            y = ny1;
            x2 = nx2;
            y2 = ny2;

            width = max(minWidth, abs(y2 - y));
        }

        setPoints();
    }

    int getDumpType() {
        return 'T';
    }

    public String dump() {
        return dumpValues(super.dump(), inductance, ratio, current[0], current[1], couplingCoef);
    }

    boolean isTrapezoidal() {
        return (flags & Inductor.FLAG_BACK_EULER) == 0;
    }

    public void draw(Graphics g) {
        int i;
        for (i = 0; i != 4; i++) {
            setVoltageColor(g, volts[i]);
            drawThickLine(g, ptEnds[i], ptCoil[i]);
        }
        for (i = 0; i != 2; i++) {
            setPowerColor(g, current[i] * (volts[i] - volts[i + 2]));
            int csign = dsign * (i == 1 ? -6 * polarity : 6) * flip;
            if (hasFlag(FLAG_VERTICAL))
                csign *= -1;
            drawCoil(g, csign, ptCoil[i], ptCoil[i + 2], volts[i], volts[i + 2]);
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
        String[] turnsLabels = new String[] {"1T", shortFormat(Math.abs(ratio)) + "T"};
        for (i = 0; i != 2; i++) {
            Point a = ptCoil[i];
            Point b = ptCoil[i + 2];
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
            drawCenteredText(g, turnsLabels[i], lx, ly, true);
        }
        g.restore();

        g.setColor(needsHighlight() ? selectColor() : elementColor());
        for (i = 0; i != 2; i++) {
            drawThickLine(g, ptCore[i], ptCore[i + 2]);
            if (dots != null)
                g.fillOval(dots[i].x - 2, dots[i].y - 2, 5, 5);
            curcount[i] = updateDotCount(current[i], curcount[i]);
        }
        for (i = 0; i != 2; i++) {
            drawDots(g, ptEnds[i], ptCoil[i], curcount[i]);
            drawDots(g, ptCoil[i], ptCoil[i + 2], curcount[i]);
            drawDots(g, ptEnds[i + 2], ptCoil[i + 2], -curcount[i]);
        }

        drawPosts(g);
        setBbox(ptEnds[0], ptEnds[polarity == 1 ? 3 : 1], 0);
        adjustBbox(new Point(x, y), new Point(x2, y2));
    }

    public void setPoints() {
        super.setPoints();
        // Keep the resize handle diagonal (x2/y2) but constrain the rendered axis.
        if (hasFlag(FLAG_VERTICAL))
            point2.x = point1.x;
        else
            point2.y = point1.y;
        dx = point2.x - point1.x;
        dy = point2.y - point1.y;
        dn = Math.sqrt(dx * dx + dy * dy);
        if (dn < 1)
            dn = 1;
        dpx1 = dy / dn;
        dpy1 = -dx / dn;
        dsign = (dy == 0) ? sign(dx) : sign(dy);
        ptEnds = newPointArray(4);
        ptCoil = newPointArray(4);
        ptCore = newPointArray(4);
        ptEnds[0] = point1;
        ptEnds[1] = point2;
        flip = hasFlag(FLAG_FLIP) ? -1 : 1;
        interpPoint(point1, point2, ptEnds[2], 0, -dsign * width * flip);
        interpPoint(point1, point2, ptEnds[3], 1, -dsign * width * flip);
        double ce = .5 - 12 / dn;
        double cd = .5 - 2 / dn;
        int i;
        for (i = 0; i != 4; i += 2) {
            interpPoint(ptEnds[i], ptEnds[i + 1], ptCoil[i], ce);
            interpPoint(ptEnds[i], ptEnds[i + 1], ptCoil[i + 1], 1 - ce);
            interpPoint(ptEnds[i], ptEnds[i + 1], ptCore[i], cd);
            interpPoint(ptEnds[i], ptEnds[i + 1], ptCore[i + 1], 1 - cd);
        }
        if (polarity == -1) {
            int vsign = (hasFlag(FLAG_VERTICAL)) ? -1 : 1;
            dots = new Point[2];
            double dotp = Math.abs(7. / width);
            dots[0] = interpPoint(ptCoil[0], ptCoil[2], dotp, -7 * dsign * vsign * flip);
            dots[1] = interpPoint(ptCoil[3], ptCoil[1], dotp, -7 * dsign * vsign * flip);
            Point x = ptEnds[1];
            ptEnds[1] = ptEnds[3];
            ptEnds[3] = x;
            x = ptCoil[1];
            ptCoil[1] = ptCoil[3];
            ptCoil[3] = x;
        } else
            dots = null;
    }

    public Point getPost(int n) {
        return ptEnds[n];
    }

    public int getPostCount() {
        return 4;
    }

    public void reset() {
        // need to set current-source values here in case one of the nodes is node 0.  In that case
        // calculateCurrent() may get called (from setNodeVoltage()) when analyzing circuit, before
        // startIteration() gets called
        current[0] = current[1] = volts[0] = volts[1] = volts[2] =
                volts[3] = curcount[0] = curcount[1] = curSourceValue1 = curSourceValue2 = 0;
    }

    double a1, a2, a3, a4;

    public void stamp() {
        // equations for transformer:
        //   v1 = L1 di1/dt + M  di2/dt
        //   v2 = M  di1/dt + L2 di2/dt
        // we invert that to get:
        //   di1/dt = a1 v1 + a2 v2
        //   di2/dt = a3 v1 + a4 v2
        // integrate di1/dt using trapezoidal approx and we get:
        //   i1(t2) = i1(t1) + dt/2 (i1(t1) + i1(t2))
        //          = i1(t1) + a1 dt/2 v1(t1) + a2 dt/2 v2(t1) +
        //                     a1 dt/2 v1(t2) + a2 dt/2 v2(t2)
        // the norton equivalent of this for i1 is:
        //  a. current source, I = i1(t1) + a1 dt/2 v1(t1) + a2 dt/2 v2(t1)
        //  b. resistor, G = a1 dt/2
        //  c. current source controlled by voltage v2, G = a2 dt/2
        // and for i2:
        //  a. current source, I = i2(t1) + a3 dt/2 v1(t1) + a4 dt/2 v2(t1)
        //  b. resistor, G = a3 dt/2
        //  c. current source controlled by voltage v2, G = a4 dt/2
        //
        // For backward euler,
        //
        //   i1(t2) = i1(t1) + a1 dt v1(t2) + a2 dt v2(t2)
        //
        // So the current source value is just i1(t1) and we use
        // dt instead of dt/2 for the resistor and VCCS.
        //
        // first winding goes from node 0 to 2, second is from 1 to 3
        double l1 = inductance;
        double l2 = inductance * ratio * ratio;
        double m = couplingCoef * Math.sqrt(l1 * l2);
        // build inverted matrix
        double deti = 1 / (l1 * l2 - m * m);
        double ts = isTrapezoidal() ? simulator().timeStep / 2 : simulator().timeStep;
        a1 = l2 * deti * ts; // we multiply dt/2 into a1..a4 here
        a2 = -m * deti * ts;
        a3 = -m * deti * ts;
        a4 = l1 * deti * ts;

        CircuitSimulator simulator = simulator();
        simulator.stampConductance(nodes[0], nodes[2], a1);
		simulator.stampVCCurrentSource(nodes[0], nodes[2], nodes[1], nodes[3], a2);
		simulator.stampVCCurrentSource(nodes[1], nodes[3], nodes[0], nodes[2], a3);
		simulator.stampConductance(nodes[1], nodes[3], a4);
		simulator.stampRightSide(nodes[0]);
		simulator.stampRightSide(nodes[1]);
		simulator.stampRightSide(nodes[2]);
		simulator.stampRightSide(nodes[3]);
    }

    public void startIteration() {
        double voltdiff1 = volts[0] - volts[2];
        double voltdiff2 = volts[1] - volts[3];
        if (isTrapezoidal()) {
            curSourceValue1 = voltdiff1 * a1 + voltdiff2 * a2 + current[0];
            curSourceValue2 = voltdiff1 * a3 + voltdiff2 * a4 + current[1];
        } else {
            curSourceValue1 = current[0];
            curSourceValue2 = current[1];
        }
    }

    double curSourceValue1, curSourceValue2;

    public void doStep() {
        CircuitSimulator simulator = simulator();
        simulator.stampCurrentSource(nodes[0], nodes[2], curSourceValue1);
		simulator.stampCurrentSource(nodes[1], nodes[3], curSourceValue2);
    }

    void calculateCurrent() {
        double voltdiff1 = volts[0] - volts[2];
        double voltdiff2 = volts[1] - volts[3];
        current[0] = voltdiff1 * a1 + voltdiff2 * a2 + curSourceValue1;
        current[1] = voltdiff1 * a3 + voltdiff2 * a4 + curSourceValue2;
    }

    @Override
    public double getCurrentIntoNode(int n) {
        if (n < 2)
            return -current[n];
        return current[n - 2];
    }

    public void getInfo(String arr[]) {
        arr[0] = "transformer";
        arr[1] = "L = " + getUnitText(inductance, "H");
        arr[2] = "Ratio = 1:" + ratio;
        arr[3] = "Vd1 = " + getVoltageText(volts[0] - volts[2]);
        arr[4] = "Vd2 = " + getVoltageText(volts[1] - volts[3]);
        arr[5] = "I1 = " + getCurrentText(current[0]);
        arr[6] = "I2 = " + getCurrentText(current[1]);
    }

    public boolean getConnection(int n1, int n2) {
        if (comparePair(n1, n2, 0, 2))
            return true;
        if (comparePair(n1, n2, 1, 3))
            return true;
        return false;
    }

    public EditInfo getEditInfo(int n) {
        if (n == 0)
            return new EditInfo("Primary Inductance (H)", inductance, .01, 5);
        if (n == 1)
            return new EditInfo("Ratio (N1/N2)", 1 / ratio, 1, 10).setDimensionless();
        if (n == 2)
            return new EditInfo("Coupling Coefficient", couplingCoef, 0, 1).
                    setDimensionless();
        if (n == 3) {
            EditInfo ei = new EditInfo("", 0, -1, -1);
            ei.checkbox = new Checkbox("Trapezoidal Approximation",
                    isTrapezoidal());
            return ei;
        }
        if (n == 4) {
            EditInfo ei = new EditInfo("", 0, -1, -1);
            ei.checkbox = new Checkbox("Swap Secondary Polarity",
                    polarity == -1);
            return ei;
        }
        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 0 && ei.value > 0)
            inductance = ei.value;
        if (n == 1 && ei.value > 0)
            ratio = 1 / ei.value;
        if (n == 2 && ei.value > 0 && ei.value < 1)
            couplingCoef = ei.value;
        if (n == 3) {
            if (ei.checkbox.getState())
                flags &= ~Inductor.FLAG_BACK_EULER;
            else
                flags |= Inductor.FLAG_BACK_EULER;
        }
        if (n == 4) {
            polarity = (ei.checkbox.getState()) ? -1 : 1;
            if (ei.checkbox.getState())
                flags |= FLAG_REVERSE;
            else
                flags &= ~FLAG_REVERSE;
            setPoints();
        }
    }

    public int getShortcut() {
        return 'T';
    }

    public void flipX(int c2, int count) {
        if (hasFlag(FLAG_VERTICAL))
            flags ^= FLAG_FLIP;
        super.flipX(c2, count);
    }

    public void flipY(int c2, int count) {
        if (!hasFlag(FLAG_VERTICAL))
            flags ^= FLAG_FLIP;
        super.flipY(c2, count);
    }

    public void flipXY(int xmy, int count) {
        flags ^= FLAG_VERTICAL;
        width *= -1;
        super.flipXY(xmy, count);
    }

    @Override
    public String getJsonTypeName() {
        return "Transformer";
    }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        props.put("inductance", getUnitText(inductance, "H"));
        props.put("ratio", ratio);
        props.put("coupling", couplingCoef);
        if (polarity == -1) {
            props.put("reverse_polarity", true);
        }
        // Orientation flags
        if (hasFlag(FLAG_VERTICAL)) {
            props.put("vertical", true);
        }
        if (hasFlag(FLAG_FLIP)) {
            props.put("flip", true);
        }
        return props;
    }

    @Override
    public String[] getJsonPinNames() {
        return new String[] { "pri1", "pri2", "sec1", "sec2" };
    }

    /**
     * Returns pin positions for JSON export.
     * Uses ptEnds[] which contains actual post positions.
     */
    @Override
    public Point getJsonPinPosition(int pinIndex) {
        if (pinIndex < 0 || pinIndex >= 4 || ptEnds == null) {
            return null;
        }
        return ptEnds[pinIndex];
    }

    /**
     * Sets transformer geometry from JSON pin positions.
     * Width is fixed at 32 for consistent display.
     */
    @Override
    public void applyJsonPinPositions(java.util.Map<String, java.util.Map<String, Integer>> pins) {
        // x, y, x2, y2 вже встановлені через bounds
        // Встановлюємо фіксований width для нормального зображення
        width = 32;
    }

    @Override
    public void applyJsonProperties(java.util.Map<String, Object> props) {
        super.applyJsonProperties(props);
        
        // Parse inductance
        inductance = com.lushprojects.circuitjs1.client.io.json.UnitParser.parse(
            getJsonString(props, "inductance", "4 H"));
        
        // Parse ratio
        ratio = getJsonDouble(props, "ratio", 1);
        
        // Parse coupling coefficient
        couplingCoef = getJsonDouble(props, "coupling", 0.999);
        if (couplingCoef <= 0 || couplingCoef >= 1) couplingCoef = 0.999;
        
        // Parse polarity
        if (getJsonBoolean(props, "reverse_polarity", false)) {
            polarity = -1;
            flags |= FLAG_REVERSE;
        } else {
            polarity = 1;
        }
        
        // Note: orientation flags (FLAG_VERTICAL, FLAG_FLIP) and width
        // are set by applyJsonPinPositions() based on actual pin coordinates
    }

    @Override
    public java.util.Map<String, Object> getJsonState() {
        java.util.Map<String, Object> state = super.getJsonState();
        if (state == null) {
            state = new java.util.LinkedHashMap<>();
        }
        // Export winding currents
        if (Double.isFinite(current[0])) {
            state.put("current_primary", current[0]);
        }
        if (Double.isFinite(current[1])) {
            state.put("current_secondary", current[1]);
        }
        return state.isEmpty() ? null : state;
    }

    @Override
    public void applyJsonState(java.util.Map<String, Object> state) {
        super.applyJsonState(state);
        if (state != null) {
            current[0] = getJsonDouble(state, "current_primary", 0);
            current[1] = getJsonDouble(state, "current_secondary", 0);
        }
    }
}
