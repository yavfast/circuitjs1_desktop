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

import com.google.gwt.canvas.dom.client.Context2d;
import com.lushprojects.circuitjs1.client.Choice;
import com.lushprojects.circuitjs1.client.CircuitSimulator;
import com.lushprojects.circuitjs1.client.CustomLogicModel;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;
import com.lushprojects.circuitjs1.client.util.Locale;

import java.util.Vector;

public class RelayCoilElm extends CircuitElm {
    double inductance;
    Inductor ind;
    String label;
    double onCurrent, offCurrent;
    Point[] coilPosts, coilLeads;
    Point[] outline = newPointArray(4);
    Point[] extraPoints;
    double coilCurrent, coilCurCount;
    double avgCurrent;

    // fractional position, between 0 and 1 inclusive
    double d_position;

    // integer position, can be 0 (off), 1 (on), 2 (in between)
    int i_position;

    double coilR;

    // time to switch in seconds
    double switchingTime;
    double switchingTimeOn, switchingTimeOff;
    double lastTransition;

    int openhs;

    // 0 = waiting for onCurrent
    // 1 = waiting to turn on
    // 2 = waiting for offCurrent
    // 3 = waiting to turn off
    int state;
    int switchPosition;

    public static final int TYPE_NORMAL = 0;
    public static final int TYPE_ON_DELAY = 1;
    public static final int TYPE_OFF_DELAY = 2;
    public static final int TYPE_LATCHING = 3;
    int type;

    final int nSwitch0 = 0;
    final int nSwitch1 = 1;
    final int nSwitch2 = 2;
    int nCoil1, nCoil2, nCoil3;
    double currentOffset1, currentOffset2;

    public RelayCoilElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy);
        ind = new Inductor(simulator());
        inductance = .2;
        ind.setup(inductance, 0, Inductor.FLAG_BACK_EULER);
        noDiagonal = true;
        onCurrent = .02;
        offCurrent = .015;
        state = 0;
        label = "label";
        coilR = 20;
        switchingTime = 5e-3;
        coilCurrent = coilCurCount = 0;
        setupPoles();
    }

    public RelayCoilElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
            StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f);
        label = CustomLogicModel.unescape(st.nextToken());
        inductance = parseDouble(st.nextToken());
        coilCurrent = parseDouble(st.nextToken());
        onCurrent = parseDouble(st.nextToken());
        coilR = parseDouble(st.nextToken());
        offCurrent = parseDouble(st.nextToken());
        switchingTime = Double.parseDouble(st.nextToken());
        type = Integer.parseInt(st.nextToken());
        state = Integer.parseInt(st.nextToken());
        switchPosition = Integer.parseInt(st.nextToken());
        noDiagonal = true;
        ind = new Inductor(simulator());
        ind.setup(inductance, coilCurrent, Inductor.FLAG_BACK_EULER);
        setupPoles();
        allocNodes();
    }

    void setupPoles() {
        nCoil1 = 0;
        nCoil2 = nCoil1 + 1;
        nCoil3 = nCoil1 + 2;
    }

    int getDumpType() {
        return 425;
    }

    public String dump() {
        return dumpValues(super.dump(), CustomLogicModel.escape(label),
                inductance, coilCurrent, onCurrent, coilR, offCurrent, switchingTime, type, state, switchPosition);
    }

    public void draw(Graphics g) {
        int i;
        for (i = 0; i != 2; i++) {
            setVoltageColor(g, getNodeVoltage(nCoil1 + i));
            drawThickLine(g, coilLeads[i], coilPosts[i]);
        }
        setPowerColor(g, coilCurrent * (getNodeVoltage(nCoil1) - getNodeVoltage(nCoil2)));

        // draw rectangle
        g.setColor(needsHighlight() ? selectColor() : elementColor());
        drawThickLine(g, outline[0], outline[1]);
        drawThickLine(g, outline[1], outline[2]);
        drawThickLine(g, outline[2], outline[3]);
        drawThickLine(g, outline[3], outline[0]);

        if (type == TYPE_LATCHING) {
            for (i = 0; i != 3; i++)
                drawThickLine(g, extraPoints[i], extraPoints[i + 1]);
        } else if (type == TYPE_ON_DELAY) {
            drawThickLine(g, extraPoints[1], extraPoints[2]);
            drawThickLine(g, extraPoints[0], extraPoints[2]);
            drawThickLine(g, extraPoints[1], extraPoints[3]);
        } else if (type == TYPE_OFF_DELAY) {
            g.fillRect(extraPoints[0].x, extraPoints[0].y, extraPoints[2].x - extraPoints[0].x,
                    extraPoints[2].y - extraPoints[0].y);
        }

        g.setColor(needsHighlight() ? selectColor() : foregroundColor());
        if (getX() == getX2())
            g.drawString(label, outline[2].x + 10, (getY() + getY2()) / 2 + 4);
        else {
            g.save();
            g.setTextAlign(Context2d.TextAlign.CENTER);
            g.drawString(label, (getX() + getX2()) / 2, outline[1].y + 15);
            g.restore();
        }

        coilCurCount = updateDotCount(coilCurrent, coilCurCount);

        if (coilCurCount != 0) {
            drawDots(g, coilPosts[0], coilLeads[0], coilCurCount);
            // drawDots(g, coilLeads[0], coilLeads[1], addCurCount(coilCurCount,
            // currentOffset1));
            drawDots(g, coilLeads[1], coilPosts[1], addCurCount(coilCurCount, currentOffset2));
        }

        drawPosts(g);
        setBbox(outline[0], outline[2], 0);
        adjustBbox(coilPosts[0], coilPosts[1]);

        // this never gets called for subcircuits
        // setSwitchPositions();
    }

    public double getCurrentIntoNode(int n) {
        if (n == 0)
            return -coilCurrent;
        return coilCurrent;
    }

    public void setPoints() {
        super.setPoints();
        double dn = getDn();
        int dsign = getDsign();
        setupPoles();
        allocNodes();
        openhs = -dsign * 16;

        // coil
        if (coilPosts == null)
            coilPosts = newPointArray(2);
        if (coilLeads == null)
            coilLeads = newPointArray(2);

        int boxSize;
        coilPosts[0] = geom().getPoint1();
        coilPosts[1] = geom().getPoint2();
        boxSize = 32;

        // outline
        double boxWScale = Math.min(0.4, 12.0 / dn);
        interpPoint(geom().getPoint1(), geom().getPoint2(), coilLeads[0], 0.5 - boxWScale);
        interpPoint(geom().getPoint1(), geom().getPoint2(), coilLeads[1], 0.5 + boxWScale);
        interpPoint(geom().getPoint1(), geom().getPoint2(), outline[0], 0.5 - boxWScale, -boxSize * dsign);
        interpPoint(geom().getPoint1(), geom().getPoint2(), outline[1], 0.5 + boxWScale, -boxSize * dsign);
        interpPoint(geom().getPoint1(), geom().getPoint2(), outline[3], 0.5 - boxWScale, +boxSize * dsign);
        interpPoint(geom().getPoint1(), geom().getPoint2(), outline[2], 0.5 + boxWScale, +boxSize * dsign);

        currentOffset1 = distance(coilPosts[0], coilLeads[0]);
        currentOffset2 = currentOffset1 + distance(coilLeads[0], coilLeads[1]);

        if (extraPoints == null)
            extraPoints = newPointArray(4);

        if (type == TYPE_LATCHING) {
            interpPoint(coilLeads[0], coilLeads[1], extraPoints[0], .3, 8);
            interpPoint(coilLeads[0], coilLeads[1], extraPoints[1], .3, 0);
            interpPoint(coilLeads[0], coilLeads[1], extraPoints[2], .7, 0);
            interpPoint(coilLeads[0], coilLeads[1], extraPoints[3], .7, -8);
        } else {
            extraPoints[0] = outline[0];
            extraPoints[3] = outline[1];
            // interpPoint(coilLeads[0], coilLeads[1], extraPoints[0], 0, -boxSize);
            interpPoint(coilLeads[0], coilLeads[1], extraPoints[1], 0, -boxSize + 12);
            interpPoint(coilLeads[0], coilLeads[1], extraPoints[2], 1, -boxSize + 12);
            // interpPoint(coilLeads[0], coilLeads[1], extraPoints[3], 1, -boxSize);
        }
    }

    public Point getPost(int n) {
        return coilPosts[n];
    }

    public int getPostCount() {
        return 2;
    }

    public int getInternalNodeCount() {
        return 1;
    }

    public void reset() {
        super.reset();
        ind.reset();
        coilCurrent = coilCurCount = 0;
        d_position = i_position = 0;
        avgCurrent = 0;

        // preserve onState because if we don't, Relay Flip-Flop gets left in a weird
        // state on reset.
        // onState = false;
    }

    double a1, a2, a3, a4;

    public void stamp() {
        // inductor from coil post 1 to internal node
        ind.stamp(getNode(nCoil1), getNode(nCoil3));
        // resistor from internal node to coil post 2
        simulator().stampResistor(getNode(nCoil3), getNode(nCoil2), coilR);

        if (type == TYPE_ON_DELAY) {
            switchingTimeOn = switchingTime;
            switchingTimeOff = 0;
        } else if (type == TYPE_OFF_DELAY) {
            switchingTimeOff = switchingTime;
            switchingTimeOn = 0;
        } else {
            switchingTimeOff = switchingTimeOn = switchingTime;
        }
        setSwitchPositions();
    }

    public void startIteration() {
        CircuitSimulator simulator = simulator();
        ind.startIteration(getNodeVoltage(nCoil1) - getNodeVoltage(nCoil3));
        double absCurrent = Math.abs(coilCurrent);
        double a = Math.exp(-simulator.timeStep * 1e3);
        avgCurrent = a * avgCurrent + (1 - a) * absCurrent;
        int oldSwitchPosition = switchPosition;

        if (state == 0) {
            if (avgCurrent > onCurrent) {
                lastTransition = simulator().t;
                state = 1;
            }
        } else if (state == 1) {
            if (avgCurrent < offCurrent)
                state = 0;
            else if (simulator().t - lastTransition > switchingTimeOn) {
                state = 2;
                if (type == TYPE_LATCHING)
                    switchPosition = 1 - switchPosition;
                else
                    switchPosition = 1;
            }
        } else if (state == 2) {
            if (avgCurrent < offCurrent) {
                lastTransition = simulator().t;
                state = 3;
            }
        } else if (state == 3) {
            if (avgCurrent > onCurrent)
                state = 2;
            else if (simulator().t - lastTransition > switchingTimeOff) {
                state = 0;
                if (type != TYPE_LATCHING)
                    switchPosition = 0;
            }
        }

        if (oldSwitchPosition != switchPosition)
            setSwitchPositions();
    }

    Vector<CircuitElm> elmList;

    public void setParentList(Vector<CircuitElm> list) {
        elmList = list;
    }

    void setSwitchPositions() {
        int i;
        for (i = 0; i != elmList.size(); i++) {
            Object o = elmList.elementAt(i);
            if (o instanceof RelayContactElm) {
                RelayContactElm s2 = (RelayContactElm) o;
                if (s2.label.equals(label))
                    s2.setPosition(1 - switchPosition, type);
            }
        }
    }

    public void doStep() {
        double voltdiff = getNodeVoltage(nCoil1) - getNodeVoltage(nCoil3);
        ind.doStep(voltdiff);
    }

    void calculateCurrent() {
        double voltdiff = getNodeVoltage(nCoil1) - getNodeVoltage(nCoil3);
        coilCurrent = ind.calculateCurrent(voltdiff);
    }

    public void getInfo(String arr[]) {
        arr[0] = Locale.LS("relay");
        if (i_position == 0)
            arr[0] += " (" + Locale.LS("off") + ")";
        else if (i_position == 1)
            arr[0] += " (" + Locale.LS("on") + ")";
        int ln = 1;
        arr[ln++] = Locale.LS("coil I") + " = " + getCurrentDText(coilCurrent);
        arr[ln++] = Locale.LS("coil Vd") + " = " +
                getVoltageDText(getNodeVoltage(nCoil1) - getNodeVoltage(nCoil2));
    }

    public EditInfo getEditInfo(int n) {
        if (n == 0) {
            EditInfo ei = new EditInfo("Type", 0);
            ei.choice = new Choice();
            ei.choice.add("Normal");
            ei.choice.add("On Delay");
            ei.choice.add("Off Delay");
            ei.choice.add("Latching");
            ei.choice.select(type);
            return ei;
        }
        if (n == 1)
            return new EditInfo("Inductance (H)", inductance, 0, 0);
        if (n == 2)
            return new EditInfo("On Current (A)", onCurrent, 0, 0);
        if (n == 3)
            return new EditInfo("Off Current (A)", offCurrent, 0, 0);
        if (n == 4)
            return new EditInfo("Coil Resistance (ohms)", coilR, 0, 0);
        if (n == 5)
            return new EditInfo("Switching Time (s)", switchingTime, 0, 0);
        if (n == 6)
            return new EditInfo("Label (for linking)", label);
        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 0) {
            type = ei.choice.getSelectedIndex();
            setPoints();
        }
        if (n == 1 && ei.value > 0) {
            inductance = ei.value;
            ind.setup(inductance, coilCurrent, Inductor.FLAG_BACK_EULER);
        }
        if (n == 2 && ei.value > 0)
            onCurrent = ei.value;
        if (n == 3 && ei.value > 0)
            offCurrent = ei.value;
        if (n == 4 && ei.value > 0)
            coilR = ei.value;
        if (n == 5 && ei.value > 0)
            switchingTime = ei.value;
        if (n == 6)
            label = ei.textf.getText();
    }

    public boolean getConnection(int n1, int n2) {
        return true;
    }

    @Override
    public void setCircuitDocument(CircuitDocument circuitDocument) {
        super.setCircuitDocument(circuitDocument);
        ind.setSimulator(circuitDocument.simulator);
    }

    @Override
    public String getJsonTypeName() {
        return "RelayCoil";
    }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        props.put("label", label);
        props.put("inductance", getUnitText(inductance, "H"));
        props.put("on_current", getUnitText(onCurrent, "A"));
        props.put("off_current", getUnitText(offCurrent, "A"));
        props.put("coil_resistance", getUnitText(coilR, "Ohm"));
        props.put("switching_time", getUnitText(switchingTime, "s"));
        String[] typeNames = { "normal", "on_delay", "off_delay", "latching" };
        props.put("type", typeNames[type]);
        return props;
    }

    @Override
    public void applyJsonProperties(java.util.Map<String, Object> props) {
        super.applyJsonProperties(props);

        if (props.containsKey("label")) {
            label = String.valueOf(props.get("label"));
        }

        inductance = com.lushprojects.circuitjs1.client.io.json.UnitParser.parse(
                getJsonString(props, "inductance", "200 mH"));
        ind.setup(inductance, coilCurrent, Inductor.FLAG_BACK_EULER);

        onCurrent = com.lushprojects.circuitjs1.client.io.json.UnitParser.parse(
                getJsonString(props, "on_current", "20 mA"));
        offCurrent = com.lushprojects.circuitjs1.client.io.json.UnitParser.parse(
                getJsonString(props, "off_current", "15 mA"));
        coilR = com.lushprojects.circuitjs1.client.io.json.UnitParser.parse(
                getJsonString(props, "coil_resistance", "20 Ohm"));
        switchingTime = com.lushprojects.circuitjs1.client.io.json.UnitParser.parse(
                getJsonString(props, "switching_time", "5 ms"));

        if (props.containsKey("type")) {
            String typeStr = String.valueOf(props.get("type"));
            if ("normal".equals(typeStr))
                type = TYPE_NORMAL;
            else if ("on_delay".equals(typeStr))
                type = TYPE_ON_DELAY;
            else if ("off_delay".equals(typeStr))
                type = TYPE_OFF_DELAY;
            else if ("latching".equals(typeStr))
                type = TYPE_LATCHING;
        }

        setupPoles();
    }

    @Override
    public String[] getJsonPinNames() {
        return new String[] { "coil+", "coil-" };
    }

    @Override
    public java.util.Map<String, Object> getJsonState() {
        java.util.Map<String, Object> state = super.getJsonState();
        state.put("coilCurrent", coilCurrent);
        state.put("d_position", d_position);
        state.put("i_position", i_position);
        state.put("avgCurrent", avgCurrent);
        return state;
    }

    @Override
    public void applyJsonState(java.util.Map<String, Object> state) {
        super.applyJsonState(state);
        if (state.containsKey("coilCurrent"))
            coilCurrent = ((Number) state.get("coilCurrent")).doubleValue();
        if (state.containsKey("d_position"))
            d_position = ((Number) state.get("d_position")).doubleValue();
        if (state.containsKey("i_position"))
            i_position = ((Number) state.get("i_position")).intValue();
        if (state.containsKey("avgCurrent"))
            avgCurrent = ((Number) state.get("avgCurrent")).doubleValue();
    }
}
