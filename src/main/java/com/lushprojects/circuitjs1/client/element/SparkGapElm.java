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

import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.Polygon;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;
import com.lushprojects.circuitjs1.client.util.Locale;

public class SparkGapElm extends CircuitElm {
    double resistance, onresistance, offresistance, breakdown, holdcurrent;
    boolean state;

    public SparkGapElm(int xx, int yy) {
        super(xx, yy);
        offresistance = 1e9;
        onresistance = 1e3;
        breakdown = 1e3;
        holdcurrent = 0.001;
        state = false;
    }

    public SparkGapElm(int xa, int ya, int xb, int yb, int f,
                       StringTokenizer st) {
        super(xa, ya, xb, yb, f);
        onresistance = parseDouble(st.nextToken());
        offresistance = parseDouble(st.nextToken());
        breakdown = parseDouble(st.nextToken());
        holdcurrent = parseDouble(st.nextToken());
    }

    public boolean nonLinear() {
        return true;
    }

    int getDumpType() {
        return 187;
    }

    public String dump() {
        return dumpValues(super.dump(), onresistance, offresistance, breakdown, holdcurrent);
    }

    Polygon arrow1, arrow2;

    public void setPoints() {
        super.setPoints();
        int dist = 16;
        int alen = 8;
        calcLeads(dist + alen);
        Point p1 = interpPoint(point1, point2, (dn - alen) / (2 * dn));
        arrow1 = calcArrow(point1, p1, alen, alen);
        p1 = interpPoint(point1, point2, (dn + alen) / (2 * dn));
        arrow2 = calcArrow(point2, p1, alen, alen);
    }

    public void draw(Graphics g) {
        int i;
        double v1 = volts[0];
        double v2 = volts[1];
        setBbox(point1, point2, 8);
        draw2Leads(g);
        setVoltageColor(g, volts[0]);
        setPowerColor(g, true);
        g.fillPolygon(arrow1);
        setVoltageColor(g, volts[1]);
        setPowerColor(g, true);
        g.fillPolygon(arrow2);
        if (state)
            doDots(g);
        drawPosts(g);
    }

    void calculateCurrent() {
        double vd = volts[0] - volts[1];
        current = vd / resistance;
    }

    public void reset() {
        super.reset();
        state = false;
    }

    public void startIteration() {
        if (Math.abs(current) < holdcurrent)
            state = false;
        double vd = volts[0] - volts[1];
        if (Math.abs(vd) > breakdown)
            state = true;
    }

    public void doStep() {
        resistance = (state) ? onresistance : offresistance;
        simulator().stampResistor(nodes[0], nodes[1], resistance);
    }

    public void stamp() {
        simulator().stampNonLinear(nodes[0]);
        simulator().stampNonLinear(nodes[1]);
    }

    public void getInfo(String arr[]) {
        arr[0] = "spark gap";
        getBasicInfo(arr);
        arr[3] = state ? "on" : "off";
        arr[4] = "Ron = " + getUnitText(onresistance, Locale.ohmString);
        arr[5] = "Roff = " + getUnitText(offresistance, Locale.ohmString);
        arr[6] = "Vbreakdown = " + getUnitText(breakdown, "V");
    }

    public EditInfo getEditInfo(int n) {
        // ohmString doesn't work here on linux
        if (n == 0)
            return new EditInfo("On resistance (ohms)", onresistance, 0, 0);
        if (n == 1)
            return new EditInfo("Off resistance (ohms)", offresistance, 0, 0);
        if (n == 2)
            return new EditInfo("Breakdown voltage", breakdown, 0, 0);
        if (n == 3)
            return new EditInfo("Holding current (A)", holdcurrent, 0, 0);
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
    public String getJsonTypeName() {
        return "SparkGap";
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
        return new String[] { "a", "b" };
    }

    @Override
    public java.util.Map<String, Object> getJsonState() {
        java.util.Map<String, Object> state = super.getJsonState();
        state.put("state", state);
        state.put("resistance", resistance);
        return state;
    }

    @Override
    public void applyJsonState(java.util.Map<String, Object> state) {
        super.applyJsonState(state);
        if (state.containsKey("state"))
            this.state = (Boolean) state.get("state");
        if (state.containsKey("resistance"))
            resistance = ((Number) state.get("resistance")).doubleValue();
    }
}

