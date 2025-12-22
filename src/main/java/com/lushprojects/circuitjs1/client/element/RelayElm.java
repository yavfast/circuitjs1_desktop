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

import com.google.gwt.user.client.ui.Button;
import com.lushprojects.circuitjs1.client.Checkbox;
import com.lushprojects.circuitjs1.client.Choice;
import com.lushprojects.circuitjs1.client.CircuitSimulator;
import com.lushprojects.circuitjs1.client.Color;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;
import com.lushprojects.circuitjs1.client.util.Locale;

// 0 = switch
// 1 = switch end 1
// 2 = switch end 2
// ...
// 3n   = coil
// 3n+1 = coil
// 3n+2 = end of coil resistor

public class RelayElm extends CircuitElm {
    final int FLAG_SWAP_COIL = 1;
    final int FLAG_SHOW_BOX = 2;
    final int FLAG_BOTH_SIDES_COIL = 4;
    final int FLAG_FLIP = 8;

    double inductance;
    Inductor ind;
    double r_on, r_off, onCurrent, offCurrent;
    Point coilPosts[], coilLeads[], swposts[][], swpoles[][], ptSwitch[];
    Point lines[];
    Point outline[] = newPointArray(4);
    double coilCurrent, switchCurrent[], coilCurCount, switchCurCount[];

    // fractional position, between 0 and 1 inclusive
    double d_position;

    // integer position, can be 0 (off), 1 (on), 2 (in between)
    int i_position;

    double coilR;

    // time to switch in seconds, or 0 for old model where switching time was not constant
    double switchingTime;

    int poleCount;
    int openhs, dflip;
    boolean onState;
    final int nSwitch0 = 0;
    final int nSwitch1 = 1;
    final int nSwitch2 = 2;
    int nCoil1, nCoil2, nCoil3;
    double currentOffset1, currentOffset2;

    public RelayElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy);
        ind = new Inductor();
        inductance = .2;
        ind.setup(inductance, 0, Inductor.FLAG_BACK_EULER);
        noDiagonal = true;
        onCurrent = .02;
        offCurrent = .015;
        r_on = .05;
        r_off = 1e6;
        coilR = 20;
        switchingTime = 5e-3;
        coilCurrent = coilCurCount = 0;
        poleCount = 1;
        flags |= FLAG_SHOW_BOX | FLAG_BOTH_SIDES_COIL;
        setupPoles();
    }

    public RelayElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
                    StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f);
        poleCount = parseInt(st.nextToken());
        inductance = parseDouble(st.nextToken());
        coilCurrent = parseDouble(st.nextToken());
        r_on = parseDouble(st.nextToken());
        r_off = parseDouble(st.nextToken());
        onCurrent = parseDouble(st.nextToken());
        coilR = parseDouble(st.nextToken());
        try {
            offCurrent = onCurrent;
            switchingTime = 0;
            offCurrent = parseDouble(st.nextToken());
            switchingTime = Double.parseDouble(st.nextToken());
            d_position = i_position = Integer.parseInt(st.nextToken());
        } catch (Exception e) {
        }
        if (i_position == 1)
            onState = true;
        // intermediate state?
        if (i_position == 2)
            d_position = .5;
        noDiagonal = true;
        ind = new Inductor();
        ind.setup(inductance, coilCurrent, Inductor.FLAG_BACK_EULER);
        setupPoles();
        allocNodes();
    }

    @Override
    protected String getIdPrefix() {
        return "K";
    }

    void setupPoles() {
        nCoil1 = 3 * poleCount;
        nCoil2 = nCoil1 + 1;
        nCoil3 = nCoil1 + 2;
        if (switchCurrent == null || switchCurrent.length != poleCount) {
            switchCurrent = new double[poleCount];
            switchCurCount = new double[poleCount];
        }
    }

    int getDumpType() {
        return 178;
    }

    public String dump() {
        return dumpValues(super.dump(), poleCount,
                inductance, coilCurrent,
                r_on, r_off, onCurrent, coilR, offCurrent, switchingTime, i_position);
    }

    public void draw(Graphics g) {
        int i, p;
        for (i = 0; i != 2; i++) {
            setVoltageColor(g, getNodeVoltage(nCoil1 + i));
            drawThickLine(g, coilLeads[i], coilPosts[i]);
        }
        int x = ((flags & FLAG_SWAP_COIL) != 0) ? 1 : 0;
        setPowerColor(g, coilCurrent * (getNodeVoltage(nCoil1) - getNodeVoltage(nCoil2)));
        drawCoil(g, dflip * 6, coilLeads[x], coilLeads[1 - x],
                getNodeVoltage(nCoil1 + x), getNodeVoltage(nCoil2 - x));

        // draw rectangle
        if ((flags & FLAG_SHOW_BOX) != 0) {
            g.setColor(needsHighlight() ? selectColor() : elementColor());
            drawThickLine(g, outline[0], outline[1]);
            drawThickLine(g, outline[1], outline[2]);
            drawThickLine(g, outline[2], outline[3]);
            drawThickLine(g, outline[3], outline[0]);
        }

        // draw lines
        g.setColor(Color.darkGray);
        for (i = 0; i != poleCount; i++) {
            if (i == 0) {
                int off = ((flags & FLAG_BOTH_SIDES_COIL) == 0) ? 0 : 4;
                interpPoint(point1, point2, lines[i * 2], .5,
                        openhs * 2 + 5 * dflip - i * openhs * 3 + off);
            } else
                interpPoint(point1, point2, lines[i * 2], .5,
                        (int) (openhs * (-i * 3 + 3 - .5 + d_position)) + 5 * dflip);
            interpPoint(point1, point2, lines[i * 2 + 1], .5,
                    (int) (openhs * (-i * 3 - .5 + d_position)) - 5 * dflip);
            g.setLineDash(4, 4);
            g.drawLine(lines[i * 2].x, lines[i * 2].y, lines[i * 2 + 1].x, lines[i * 2 + 1].y);
            g.setLineDash(0, 0);
        }

        for (p = 0; p != poleCount; p++) {
            int po = p * 3;
            for (i = 0; i != 3; i++) {
                // draw lead
                setVoltageColor(g, getNodeVoltage(nSwitch0 + po + i));
                drawThickLine(g, swposts[p][i], swpoles[p][i]);
            }

            interpPoint(swpoles[p][1], swpoles[p][2], ptSwitch[p], d_position);
            //setVoltageColor(g, getNodeVoltage(nSwitch0));
            g.setColor(Color.lightGray);
            drawThickLine(g, swpoles[p][0], ptSwitch[p]);
            switchCurCount[p] = updateDotCount(switchCurrent[p],
                    switchCurCount[p]);
            drawDots(g, swposts[p][0], swpoles[p][0], switchCurCount[p]);

            if (i_position != 2)
                drawDots(g, swpoles[p][i_position + 1], swposts[p][i_position + 1],
                        switchCurCount[p]);
        }

        coilCurCount = updateDotCount(coilCurrent, coilCurCount);

        if (coilCurCount != 0) {
            drawDots(g, coilPosts[0], coilLeads[0], coilCurCount);
            drawDots(g, coilLeads[0], coilLeads[1], addCurCount(coilCurCount, currentOffset1));
            drawDots(g, coilLeads[1], coilPosts[1], addCurCount(coilCurCount, currentOffset2));
        }

        drawPosts(g);
        setBbox(outline[0], outline[2], 0);
        adjustBbox(coilPosts[0], coilPosts[1]);
        adjustBbox(swposts[0][0], swposts[0][1]);
    }

    public double getCurrentIntoNode(int n) {
        if (n < 3 * poleCount) {
            int p = n / 3;
            int k = n % 3;
            if (k == 0)
                return -switchCurrent[p];
            if (k == 1 + i_position)
                return switchCurrent[p];
            return 0;
        }
        if (n == 3 * poleCount)
            return -coilCurrent;
        return coilCurrent;
    }

    public void setPoints() {
        super.setPoints();
        setupPoles();
        allocNodes();
        dflip = hasFlag(FLAG_FLIP) ? -dsign : dsign;
        openhs = -dflip * 16;

        // switch
        calcLeads(32);
        swposts = new Point[poleCount][3];
        swpoles = new Point[poleCount][3];
        int i, j;
        for (i = 0; i != poleCount; i++) {
            for (j = 0; j != 3; j++) {
                swposts[i][j] = new Point();
                swpoles[i][j] = new Point();
            }
            interpPoint(lead1, lead2, swpoles[i][0], 0, -openhs * 3 * i);
            interpPoint(lead1, lead2, swpoles[i][1], 1, -openhs * 3 * i - openhs);
            interpPoint(lead1, lead2, swpoles[i][2], 1, -openhs * 3 * i + openhs);
            interpPoint(point1, point2, swposts[i][0], 0, -openhs * 3 * i);
            interpPoint(point1, point2, swposts[i][1], 1, -openhs * 3 * i - openhs);
            interpPoint(point1, point2, swposts[i][2], 1, -openhs * 3 * i + openhs);
        }

        // coil
        coilPosts = newPointArray(2);
        coilLeads = newPointArray(2);
        ptSwitch = newPointArray(poleCount);

        int x = ((flags & FLAG_SWAP_COIL) != 0) ? 1 : 0;
        int boxSize;
        if ((flags & FLAG_BOTH_SIDES_COIL) == 0) {
            interpPoint(point1, point2, coilPosts[0], x, openhs * 2);
            interpPoint(point1, point2, coilPosts[1], x, openhs * 3);
            interpPoint(point1, point2, coilLeads[0], .5, openhs * 2);
            interpPoint(point1, point2, coilLeads[1], .5, openhs * 3);
            boxSize = 56;
        } else {
            interpPoint(point1, point2, coilPosts[0], 0, openhs * 2);
            interpPoint(point1, point2, coilPosts[1], 1, openhs * 2);
            interpPoint(point1, point2, coilLeads[0], .5 - 16 / dn, openhs * 2);
            interpPoint(point1, point2, coilLeads[1], .5 + 16 / dn, openhs * 2);
            boxSize = 40;
        }

        // lines
        lines = newPointArray(poleCount * 2);

        // outline
        double boxWScale = Math.min(0.4, 25.0 / dn);
        interpPoint(point1, point2, outline[0], 0.5 - boxWScale, -boxSize * dflip);
        interpPoint(point1, point2, outline[1], 0.5 + boxWScale, -boxSize * dflip);
        interpPoint(point1, point2, outline[2], 0.5 + boxWScale, -(openhs * 3 * poleCount) - (24.0 * dflip));
        interpPoint(point1, point2, outline[3], 0.5 - boxWScale, -(openhs * 3 * poleCount) - (24.0 * dflip));

        currentOffset1 = distance(coilPosts[0], coilLeads[0]);
        currentOffset2 = currentOffset1 + distance(coilLeads[0], coilLeads[1]);
    }

    public Point getPost(int n) {
        if (n < 3 * poleCount)
            return swposts[n / 3][n % 3];
        return coilPosts[n - 3 * poleCount];
    }

    public int getPostCount() {
        return 2 + poleCount * 3;
    }

    public int getInternalNodeCount() {
        return 1;
    }

    public void reset() {
        super.reset();
        ind.reset();
        coilCurrent = coilCurCount = 0;
        int i;
        for (i = 0; i != poleCount; i++)
            switchCurrent[i] = switchCurCount[i] = 0;
        d_position = i_position = 0;

        // preserve onState because if we don't, Relay Flip-Flop gets left in a weird state on reset.
        // onState = false;
    }

    double a1, a2, a3, a4;

    public void stamp() {
        CircuitSimulator simulator = simulator();
        // inductor from coil post 1 to internal node
        ind.stamp(getNode(nCoil1), getNode(nCoil3));
        // resistor from internal node to coil post 2
        simulator().stampResistor(getNode(nCoil3), getNode(nCoil2), coilR);

        int i;
        for (i = 0; i != poleCount * 3; i++)
            simulator().stampNonLinear(getNode(nSwitch0 + i));
    }

    public void startIteration() {
        // using old model?
        if (switchingTime == 0) {
            startIterationOld();
            return;
        }
        ind.startIteration(getNodeVoltage(nCoil1) - getNodeVoltage(nCoil3));
        double absCurrent = Math.abs(coilCurrent);

        if (onState) {
            // on or turning on.  check if we need to turn off
            if (absCurrent < offCurrent) {
                // turning off, set switch to intermediate position
                onState = false;
                i_position = 2;
            } else {
                d_position += simulator().timeStep / switchingTime;
                if (d_position >= 1)
                    d_position = i_position = 1;
            }
        } else {
            // off or turning off.  check if we need to turn on
            if (absCurrent > onCurrent) {
                // turning on, set switch to intermediate position
                onState = true;
                i_position = 2;
            } else {
                d_position -= simulator().timeStep / switchingTime;
                if (d_position <= 0)
                    d_position = i_position = 0;
            }

        }
    }

    void startIterationOld() {
        ind.startIteration(getNodeVoltage(nCoil1) - getNodeVoltage(nCoil3));

        // magic value to balance operate speed with reset speed not at all realistically
        double magic = 1.3;
        double pmult = Math.sqrt(magic + 1);
        double c = onCurrent;
        double p = coilCurrent * pmult / c;
        d_position = Math.abs(p * p) - 1.3;
        if (d_position < 0)
            d_position = 0;
        if (d_position > 1)
            d_position = 1;
        if (d_position < .1)
            i_position = 0;
        else if (d_position > .9)
            i_position = 1;
        else
            i_position = 2;
        //System.out.println("ind " + this + " " + current + " " + voltdiff);
    }

    // we need this to be able to change the matrix for each step
    public boolean nonLinear() {
        return true;
    }

    public void doStep() {
        double voltdiff = getNodeVoltage(nCoil1) - getNodeVoltage(nCoil3);
        ind.doStep(voltdiff);
        CircuitSimulator simulator = simulator();
        for (int p = 0; p != poleCount * 3; p += 3) {
            simulator().stampResistor(getNode(nSwitch0 + p), getNode(nSwitch1 + p),
                    i_position == 0 ? r_on : r_off);
            simulator().stampResistor(getNode(nSwitch0 + p), getNode(nSwitch2 + p),
                    i_position == 1 ? r_on : r_off);
        }
    }

    void calculateCurrent() {
        double voltdiff = getNodeVoltage(nCoil1) - getNodeVoltage(nCoil3);
        coilCurrent = ind.calculateCurrent(voltdiff);

        // actually this isn't correct, since there is a small amount
        // of current through the switch when off
        int p;
        for (p = 0; p != poleCount; p++) {
            if (i_position == 2)
                switchCurrent[p] = 0;
            else
                switchCurrent[p] =
                        (getNodeVoltage(nSwitch0 + p * 3) - getNodeVoltage(nSwitch1 + p * 3 + i_position)) / r_on;
        }
    }

    public void getInfo(String arr[]) {
        arr[0] = Locale.LS("relay");
        if (i_position == 0)
            arr[0] += " (" + Locale.LS("off") + ")";
        else if (i_position == 1)
            arr[0] += " (" + Locale.LS("on") + ")";
        if (switchingTime == 0)
            arr[0] += " (" + Locale.LS("old model") + ")";
        int i;
        int ln = 1;
        for (i = 0; i != poleCount; i++)
            arr[ln++] = "I" + (i + 1) + " = " + getCurrentDText(switchCurrent[i]);
        arr[ln++] = Locale.LS("coil I") + " = " + getCurrentDText(coilCurrent);
        arr[ln++] = Locale.LS("coil Vd") + " = " +
            getVoltageDText(getNodeVoltage(nCoil1) - getNodeVoltage(nCoil2));
    }

    public EditInfo getEditInfo(int n) {
        if (n == 0)
            return new EditInfo("Inductance (H)", inductance, 0, 0);
        if (n == 1)
            return new EditInfo("On Resistance (ohms)", r_on, 0, 0);
        if (n == 2)
            return new EditInfo("Off Resistance (ohms)", r_off, 0, 0);
        if (n == 3)
            return new EditInfo("On Current (A)", onCurrent, 0, 0);
        if (n == 4) {
            if (switchingTime == 0) {
                // still using old model, so hide off current which won't work.
                // make button to switch to new model
                EditInfo ei = new EditInfo("", 0, -1, -1);
                ei.button = new Button(Locale.LS("Use New Model"));
                return ei;
            }
            return new EditInfo("Off Current (A)", offCurrent, 0, 0);
        }
        if (n == 5)
            return new EditInfo("Number of Poles", poleCount, 1, 4).
                    setDimensionless();
        if (n == 6)
            return new EditInfo("Coil Resistance (ohms)", coilR, 0, 0);
        if (n == 7) {
            int style = 1;
            if ((flags & FLAG_SWAP_COIL) != 0)
                style = 2;
            else if ((flags & FLAG_BOTH_SIDES_COIL) != 0)
                style = 0;
            EditInfo ei = new EditInfo("Coil Style", style, -1, -1);
            ei.choice = new Choice();
            ei.choice.add("Both Sides");
            ei.choice.add("Side 1");
            ei.choice.add("Side 2");
            ei.choice.select(style);
            return ei;
        }
        if (n == 8) {
            EditInfo ei = new EditInfo("", 0, -1, -1);
            ei.checkbox = new Checkbox("Show Box", (flags & FLAG_SHOW_BOX) != 0);
            return ei;
        }

        // show switching time only for new model, since it is meaningless for old one
        if (n == 9 && switchingTime > 0)
            return new EditInfo("Switching Time (s)", switchingTime, 0, 0);
        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 0 && ei.value > 0) {
            inductance = ei.value;
            ind.setup(inductance, coilCurrent, Inductor.FLAG_BACK_EULER);
        }
        if (n == 1 && ei.value > 0)
            r_on = ei.value;
        if (n == 2 && ei.value > 0)
            r_off = ei.value;
        if (n == 3 && ei.value > 0)
            onCurrent = ei.value;
        if (n == 4) {
            // this could be a button or a text box for off current
            if (ei.button != null) {
                // upgrading to new model
                switchingTime = 5e-3;
                ei.newDialog = true;
            } else if (ei.value > 0)
                offCurrent = ei.value;
        }
        if (n == 5 && ei.value >= 1) {
            poleCount = (int) ei.value;
            setPoints();
        }
        if (n == 6 && ei.value > 0)
            coilR = ei.value;
        if (n == 7) {
            int style = ei.choice.getSelectedIndex();
            final int styles[] = {FLAG_BOTH_SIDES_COIL, 0, FLAG_SWAP_COIL};
            flags &= ~(FLAG_SWAP_COIL | FLAG_BOTH_SIDES_COIL);
            flags |= styles[style];
            setPoints();
        }
        if (n == 8)
            flags = ei.changeFlag(flags, FLAG_SHOW_BOX);
        if (n == 9 && ei.value > 0)
            switchingTime = ei.value;
    }

    public boolean getConnection(int n1, int n2) {
        return (n1 / 3 == n2 / 3);
    }

    public int getShortcut() {
        return 'R';
    }

    public void flipX(int c2, int count) {
        if (dx == 0)
            flags ^= FLAG_FLIP;
        super.flipX(c2, count);
    }

    public void flipY(int c2, int count) {
        if (dy == 0)
            flags ^= FLAG_FLIP;
        super.flipY(c2, count);
    }

    public void flipXY(int xmy, int count) {
        flags ^= FLAG_FLIP;
        super.flipXY(xmy, count);
    }

    @Override
    public void setCircuitDocument(com.lushprojects.circuitjs1.client.CircuitDocument circuitDocument) {
        super.setCircuitDocument(circuitDocument);
        ind.setSimulator(circuitDocument.simulator);
    }

    @Override
    public String getJsonTypeName() {
        return "Relay";
    }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        props.put("inductance", getUnitText(inductance, "H"));
        props.put("on_resistance", getUnitText(r_on, "Ohm"));
        props.put("off_resistance", getUnitText(r_off, "Ohm"));
        props.put("on_current", getUnitText(onCurrent, "A"));
        props.put("off_current", getUnitText(offCurrent, "A"));
        props.put("coil_resistance", getUnitText(coilR, "Ohm"));
        props.put("poles", poleCount);
        if (switchingTime > 0) {
            props.put("switching_time", getUnitText(switchingTime, "s"));
        }
        return props;
    }

    @Override
    public void applyJsonProperties(java.util.Map<String, Object> props) {
        super.applyJsonProperties(props);
        
        com.lushprojects.circuitjs1.client.io.json.UnitParser parser = null;
        
        // Parse inductance
        inductance = com.lushprojects.circuitjs1.client.io.json.UnitParser.parse(
            getJsonString(props, "inductance", "0.2 H"));
        ind.setup(inductance, coilCurrent, Inductor.FLAG_BACK_EULER);
        
        // Parse resistances
        r_on = com.lushprojects.circuitjs1.client.io.json.UnitParser.parse(
            getJsonString(props, "on_resistance", "0.05 Ohm"));
        r_off = com.lushprojects.circuitjs1.client.io.json.UnitParser.parse(
            getJsonString(props, "off_resistance", "1 MOhm"));
        coilR = com.lushprojects.circuitjs1.client.io.json.UnitParser.parse(
            getJsonString(props, "coil_resistance", "20 Ohm"));
        
        // Parse currents
        onCurrent = com.lushprojects.circuitjs1.client.io.json.UnitParser.parse(
            getJsonString(props, "on_current", "20 mA"));
        offCurrent = com.lushprojects.circuitjs1.client.io.json.UnitParser.parse(
            getJsonString(props, "off_current", "15 mA"));
        
        // Parse pole count
        poleCount = getJsonInt(props, "poles", 1);
        if (poleCount < 1) poleCount = 1;
        if (poleCount > 4) poleCount = 4;
        
        // Parse switching time
        if (props.containsKey("switching_time")) {
            switchingTime = com.lushprojects.circuitjs1.client.io.json.UnitParser.parse(
                getJsonString(props, "switching_time", "5 ms"));
        }
        
        setupPoles();
    }

    @Override
    public java.util.Map<String, Object> getJsonState() {
        java.util.Map<String, Object> state = super.getJsonState();
        if (state == null) {
            state = new java.util.LinkedHashMap<>();
        }
        // Export relay state
        state.put("on", onState);
        state.put("position", i_position);
        if (Double.isFinite(d_position)) {
            state.put("d_position", d_position);
        }
        if (Double.isFinite(coilCurrent)) {
            state.put("coil_current", coilCurrent);
        }
        return state;
    }

    @Override
    public void applyJsonState(java.util.Map<String, Object> stateMap) {
        super.applyJsonState(stateMap);
        if (stateMap != null) {
            onState = getJsonBoolean(stateMap, "on", false);
            i_position = getJsonInt(stateMap, "position", 0);
            d_position = getJsonDouble(stateMap, "d_position", i_position);
            coilCurrent = getJsonDouble(stateMap, "coil_current", 0);
            ind.setup(inductance, coilCurrent, Inductor.FLAG_BACK_EULER);
        }
    }
}

