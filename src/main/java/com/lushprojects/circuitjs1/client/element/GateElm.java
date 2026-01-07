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
import com.lushprojects.circuitjs1.client.Polygon;
import com.lushprojects.circuitjs1.client.RandomUtils;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public abstract class GateElm extends CircuitElm {
    final int FLAG_SMALL = 1 << 0;
    final int FLAG_SCHMITT = 1 << 1;
    final int FLAG_INVERT_INPUTS = 1 << 2;
    int inputCount = 2;
    boolean lastOutput;
    double highVoltage;
    public static double lastHighVoltage = 5;
    static boolean lastSchmitt = false;

    public GateElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy);
        noDiagonal = true;
        inputCount = 2;

        // copy defaults from last gate edited
        highVoltage = lastHighVoltage;
        if (lastSchmitt)
            flags |= FLAG_SCHMITT;

        // Note: displaySettings() may not be available during factory registration
        // because circuitDocument is not yet set
        setSize(2); // default size
    }

    public GateElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
                   StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f);
        inputCount = parseInt(st.nextToken());
        double lastOutputVoltage = parseDouble(st.nextToken());
        noDiagonal = true;
        highVoltage = 5;
        try {
            highVoltage = parseDouble(st.nextToken());
        } catch (Exception e) {
        }
        lastOutput = lastOutputVoltage > highVoltage * .5;
        setSize((f & FLAG_SMALL) != 0 ? 1 : 2);
        allocNodes();
        setupVolts();
    }

    boolean isInverting() {
        return false;
    }

    int gsize, gwidth, gwidth2, gheight, hs2;

    void setSize(int s) {
        gsize = s;
        gwidth = 7 * s;
        gwidth2 = 14 * s;
        gheight = 8 * s;
        flags &= ~FLAG_SMALL;
        flags |= (s == 1) ? FLAG_SMALL : 0;
    }

    public String dump() {
        return dumpValues(super.dump(), inputCount, getNodeVoltage(inputCount), highVoltage);
    }

    Point inPosts[], inGates[];
    boolean inputStates[];
    int ww;

    public void setPoints() {
        super.setPoints();
        double dn = getDn();
        ElmGeometry geom = geom();
        Point point1 = geom.getPoint1();
        Point point2 = geom.getPoint2();
        inputStates = new boolean[inputCount];
        if (dn > 150 && this == circuitEditor().dragElm)
            setSize(2);
        int hs = gheight;
        int i;
        ww = gwidth2; // was 24
        if (ww > dn / 2)
            ww = (int) (dn / 2);
        if (isInverting() && ww + 8 > dn / 2)
            ww = (int) (dn / 2 - 8);
        calcLeads(ww * 2);
        Point lead1 = geom.getLead1();
        Point lead2 = geom.getLead2();
        inPosts = new Point[inputCount];
        inGates = new Point[inputCount];
        int i0 = -inputCount / 2;
        if (hasFlag(FLAG_INVERT_INPUTS))
            icircles = new Point[inputCount];
        else
            icircles = null;
        for (i = 0; i != inputCount; i++, i0++) {
            if (i0 == 0 && (inputCount & 1) == 0)
                i0++;
            double adj = getLeadAdjustment(i);
            inPosts[i] = interpPoint(point1, point2, 0, hs * i0);
            inGates[i] = interpPoint(lead1, lead2, icircles != null ? -8 / (ww * 2.) + adj : adj, hs * i0);
            if (icircles != null)
                icircles[i] = interpPoint(lead1, lead2, -4 / (ww * 2.), hs * i0);
        }
        hs2 = gwidth * (inputCount / 2 + 1);
        setBbox(point1, point2, hs2);
        if (hasSchmittInputs())
            schmittPoly = getSchmittPolygon(gsize, .47f);
    }

    // Restore state if loading from file or volts is reallocated.
    void setupVolts() {
        int i;
        // We don't remember all the inputs, just the last output.
        // Fill inputs with something that keeps output the same.
        for (i = 0; i != inputCount; i++) {
            setNodeVoltageDirect(i, (lastOutput ^ isInverting()) ? highVoltage : 0);
        }
    }

    double getLeadAdjustment(int ix) {
        return 0;
    }

    void createEuroGatePolygon() {
        ElmGeometry geom = geom();
        Point lead1 = geom.getLead1();
        Point lead2 = geom.getLead2();
        Point pts[] = newPointArray(4);
        interpPoint2(lead1, lead2, pts[0], pts[1], 0, hs2);
        interpPoint2(lead1, lead2, pts[3], pts[2], 1, hs2);
        gatePoly = createPolygon(pts);
    }

    String getGateText() {
        return null;
    }

    boolean useEuroGates() {
        return displaySettings().euroGates();
    }

    void drawGatePolygon(Graphics g) {
        drawThickPolygon(g, gatePoly);
    }

    public void draw(Graphics g) {
        ElmGeometry geom = geom();
        Point point1 = geom.getPoint1();
        Point point2 = geom.getPoint2();
        Point lead2 = geom.getLead2();
        int i;
        for (i = 0; i != inputCount; i++) {
            setVoltageColor(g, getNodeVoltage(i));
            drawThickLine(g, inPosts[i], inGates[i]);
        }
        setVoltageColor(g, getNodeVoltage(inputCount));
        drawThickLine(g, lead2, point2);
        g.setColor(needsHighlight() ? selectColor() : elementColor());
        if (useEuroGates()) {
            drawThickPolygon(g, gatePoly);
            if (centerTemp == null) centerTemp = new Point();
            interpPoint(point1, point2, centerTemp, .5);
            drawCenteredText(g, getGateText(), centerTemp.x, centerTemp.y - 6 * gsize, true);
        } else
            drawGatePolygon(g);
        g.setLineWidth(2);
        if (hasSchmittInputs())
            drawPolygon(g, schmittPoly);
        g.setLineWidth(1);
        if (linePoints != null)
            for (i = 0; i != linePoints.length - 1; i++)
                drawThickLine(g, linePoints[i], linePoints[i + 1]);
        if (isInverting())
            drawThickCircle(g, pcircle.x, pcircle.y, 3);
        if (icircles != null)
            for (i = 0; i != inputCount; i++)
                drawThickCircle(g, icircles[i].x, icircles[i].y, 3);
        curcount = updateDotCount(current, curcount);
        drawDots(g, lead2, point2, curcount);
        drawPosts(g);
    }

    Polygon gatePoly, schmittPoly;
    private Point centerTemp;
    Point pcircle, linePoints[], icircles[];

    public int getPostCount() {
        return inputCount + 1;
    }

    public Point getPost(int n) {
        if (n == inputCount)
            return geom().getPoint2();
        return inPosts[n];
    }

    public int getVoltageSourceCount() {
        return 1;
    }

    abstract String getGateName();

    public void getInfo(String arr[]) {
        arr[0] = getGateName();
        arr[1] = "Vout = " + getVoltageText(getNodeVoltage(inputCount));
        arr[2] = "Iout = " + getCurrentText(getCurrent());
    }

    public void stamp() {
        simulator().stampVoltageSource(0, getNode(inputCount), voltSource);
    }

    boolean hasSchmittInputs() {
        return (flags & FLAG_SCHMITT) != 0;
    }

    boolean getInput(int x) {
        boolean high = !hasFlag(FLAG_INVERT_INPUTS);
        if (!hasSchmittInputs())
            return (getNodeVoltage(x) > highVoltage * .5) ? high : !high;
        boolean res = getNodeVoltage(x) > highVoltage * (inputStates[x] ? .35 : .55);
        inputStates[x] = res;
        return res ? high : !high;
    }

    abstract boolean calcFunction();

    int oscillationCount;
    double lastTime;

    public void doStep() {
        boolean f = calcFunction();
        if (isInverting())
            f = !f;

        CircuitSimulator simulator = simulator();
        if (lastTime != simulator().t) {
            // detect oscillation (using same strategy as Atanua)
            if (lastOutput == !f) {
                if (oscillationCount++ > 50) {
                    // output is oscillating too much, randomly leave output the same
                    oscillationCount = 0;
                    if (RandomUtils.getRand(10) > 5)
                        f = lastOutput;
                }
            } else
                oscillationCount = 0;

            lastOutput = f;
            lastTime = simulator().t;
        }

        double res = f ? highVoltage : 0;
        simulator().updateVoltageSource(0, getNode(inputCount), voltSource, res);
    }

    public EditInfo getEditInfo(int n) {
        if (n == 0)
            return new EditInfo("# of Inputs", inputCount, 1, 8).
                    setDimensionless();
        if (n == 1)
            return new EditInfo("High Logic Voltage", highVoltage, 1, 10);
        if (n == 2)
            return EditInfo.createCheckbox("Schmitt Inputs", hasSchmittInputs());
        if (n == 3)
            return EditInfo.createCheckbox("Invert Inputs", hasFlag(FLAG_INVERT_INPUTS));
        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 0 && ei.value >= 1) {
            inputCount = (int) ei.value;
            allocNodes();
            setupVolts();
            setPoints();
        }
        if (n == 1)
            highVoltage = lastHighVoltage = ei.value;
        if (n == 2) {
            if (ei.checkbox.getState())
                flags |= FLAG_SCHMITT;
            else
                flags &= ~FLAG_SCHMITT;
            lastSchmitt = hasSchmittInputs();
            setPoints();
        }
        if (n == 3) {
            flags = ei.changeFlag(flags, FLAG_INVERT_INPUTS);
            setPoints();
        }
    }

    // there is no current path through the gate inputs, but there
    // is an indirect path through the output to ground.
    public boolean getConnection(int n1, int n2) {
        return false;
    }

    public boolean hasGroundConnection(int n1) {
        return (n1 == inputCount);
    }

    public double getCurrentIntoNode(int n) {
        if (n == inputCount)
            return current;
        return 0;
    }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        props.put("input_count", inputCount);
        props.put("high_voltage", getUnitText(highVoltage, "V"));
        if (hasSchmittInputs()) {
            props.put("schmitt", true);
        }
        if (hasFlag(FLAG_INVERT_INPUTS)) {
            props.put("invert_inputs", true);
        }
        return props;
    }

    @Override
    public String[] getJsonPinNames() {
        String[] names = new String[inputCount + 1];
        for (int i = 0; i < inputCount; i++) {
            names[i] = "in" + (i + 1);
        }
        names[inputCount] = "out";
        return names;
    }

    @Override
    public void applyJsonProperties(java.util.Map<String, Object> props) {
        super.applyJsonProperties(props);
        
        // Parse input count
        inputCount = getJsonInt(props, "input_count", 2);
        if (inputCount < 1) inputCount = 2;
        
        // Parse high voltage
        highVoltage = com.lushprojects.circuitjs1.client.io.json.UnitParser.parse(
            getJsonString(props, "high_voltage", "5 V"));
        
        // Parse schmitt inputs flag
        if (getJsonBoolean(props, "schmitt", false)) {
            flags |= FLAG_SCHMITT;
        }
        
        // Parse invert inputs flag
        if (getJsonBoolean(props, "invert_inputs", false)) {
            flags |= FLAG_INVERT_INPUTS;
        }
        
        allocNodes();
        setupVolts();
    }
}
