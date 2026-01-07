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
import com.lushprojects.circuitjs1.client.Diode;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.Polygon;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;
import com.lushprojects.circuitjs1.client.util.Locale;

public class DiacElm extends CircuitElm {
    // resistor from 0 to 2, 3
    // diodes from 2, 3 to 1
    double onresistance, offresistance, breakdown, holdcurrent;
    boolean state;
    Diode diode1, diode2;

    public DiacElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy);
        offresistance = 1e8;
        onresistance = 500;
        breakdown = 30;
        holdcurrent = .01;
        state = false;
        createDiodes();
    }

    public DiacElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
                   StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f);
        onresistance = parseDouble(st.nextToken());
        offresistance = parseDouble(st.nextToken());
        breakdown = parseDouble(st.nextToken());
        holdcurrent = parseDouble(st.nextToken());
        createDiodes();
    }

    void createDiodes() {
        diode1 = new Diode();
        diode2 = new Diode();
        diode1.setupForDefaultModel();
        diode2.setupForDefaultModel();
    }

    public boolean nonLinear() {
        return true;
    }

    int getDumpType() {
        return 203;
    }

    public String dump() {
        return dumpValues(super.dump(), onresistance, offresistance, breakdown, holdcurrent);
    }

    Polygon arrows[];
    Point plate1[], plate2[];
    private Point ptemp1, ptemp2, ptemp3;

    public void setPoints() {
        super.setPoints();
        calcLeads(16);

        Point p1 = geom().getPoint1();
        Point p2 = geom().getPoint2();
        Point l1 = geom().getLead1();
        Point l2 = geom().getLead2();

        if (plate1 == null) plate1 = newPointArray(2);
        if (plate2 == null) plate2 = newPointArray(2);
        interpPoint2(l1, l2, plate1[0], plate1[1], 0, 16);
        interpPoint2(l1, l2, plate2[0], plate2[1], 1, 16);

        if (arrows == null) arrows = new Polygon[2];
        if (ptemp1 == null) ptemp1 = new Point();
        if (ptemp2 == null) ptemp2 = new Point();
        if (ptemp3 == null) ptemp3 = new Point();

        for (int i = 0; i != 2; i++) {
            int sgn = -1 + i * 2;
            interpPoint(l1, l2, ptemp1, i);
            interpPoint(l1, l2, ptemp2, 1 - i, 16 * sgn);
            interpPoint(l1, l2, ptemp3, 1 - i, 0 * sgn);
            arrows[i] = createPolygon(ptemp1, ptemp2, ptemp3);
        }
    }

    public void draw(Graphics g) {
        double v1 = getNodeVoltage(0);
        double v2 = getNodeVoltage(1);
        Point p1 = geom().getPoint1();
        Point p2 = geom().getPoint2();
        setBbox(p1, p2, 6);
        draw2Leads(g);
        setVoltageColor(g, v1);
        setPowerColor(g, true);
        drawThickLine(g, plate1[0], plate1[1]);
        setVoltageColor(g, v2);
        setPowerColor(g, true);
        drawThickLine(g, plate2[0], plate2[1]);
        g.fillPolygon(arrows[0]);
        setVoltageColor(g, v1);
        setPowerColor(g, true);
        g.fillPolygon(arrows[1]);
        setPowerColor(g, true);
        doDots(g);
        drawPosts(g);
    }

    void calculateCurrent() {
        double r = (state) ? onresistance : offresistance;
        current = (getNodeVoltage(0) - getNodeVoltage(2)) / r + (getNodeVoltage(0) - getNodeVoltage(3)) / r;
    }

    public void startIteration() {
        double vd = getNodeVoltage(0) - getNodeVoltage(1);
        if (Math.abs(current) < holdcurrent) state = false;
        if (Math.abs(vd) > breakdown) state = true;
    }

    public void doStep() {
        CircuitSimulator simulator = simulator();
        double r = (state) ? onresistance : offresistance;
        simulator.stampResistor(getNode(0), getNode(2), r);
        simulator.stampResistor(getNode(0), getNode(3), r);
        diode1.doStep(getNodeVoltage(2) - getNodeVoltage(1));
        diode2.doStep(getNodeVoltage(1) - getNodeVoltage(3));
    }

    public void stamp() {
        CircuitSimulator simulator = simulator();
        simulator.stampNonLinear(getNode(0));
        simulator.stampNonLinear(getNode(1));
        diode1.stamp(getNode(2), getNode(1));
        diode2.stamp(getNode(1), getNode(3));
    }

    public int getInternalNodeCount() {
        return 2;
    }

    public void getInfo(String arr[]) {
        arr[0] = "DIAC";
        getBasicInfo(arr);
        arr[3] = state ? "on" : "off";
        arr[4] = "Ron = " + getUnitText(onresistance, Locale.ohmString);
        arr[5] = "Roff = " + getUnitText(offresistance, Locale.ohmString);
        arr[6] = "Vbrkdn = " + getUnitText(breakdown, "V");
        arr[7] = "Ihold = " + getUnitText(holdcurrent, "A");
        arr[8] = "P = " + getUnitText(getPower(), "W");
    }

    public EditInfo getEditInfo(int n) {
        if (n == 0)
            return new EditInfo("On resistance (ohms)", onresistance, 0, 0);
        if (n == 1)
            return new EditInfo("Off resistance (ohms)", offresistance, 0, 0);
        if (n == 2)
            return new EditInfo("Breakdown voltage (volts)", breakdown, 0, 0);
        if (n == 3)
            return new EditInfo("Hold current (amps)", holdcurrent, 0, 0);
        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
        if (ei.value > 0 && n == 0)
            onresistance = ei.value;
        if (ei.value > 0 && n == 1)
            offresistance = ei.value;
        if (ei.value > 0 && n == 2)
            breakdown = ei.value;
        if (ei.value > 0 && n == 3)
            holdcurrent = ei.value;
    }

    @Override
    public void setCircuitDocument(com.lushprojects.circuitjs1.client.CircuitDocument circuitDocument) {
        super.setCircuitDocument(circuitDocument);
        diode1.setSimulator(circuitDocument.simulator);
        diode2.setSimulator(circuitDocument.simulator);
    }

    @Override
    public String getJsonTypeName() {
        return "Diac";
    }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        props.put("on_resistance", getUnitText(onresistance, "Ohm"));
        props.put("off_resistance", getUnitText(offresistance, "Ohm"));
        props.put("breakdown_voltage", getUnitText(breakdown, "V"));
        props.put("holding_current", getUnitText(holdcurrent, "A"));
        return props;
    }

    @Override
    public String[] getJsonPinNames() {
        return new String[] { "mt1", "mt2" };
    }
}

