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
import com.lushprojects.circuitjs1.client.Font;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.Rectangle;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public class LogicInputElm extends SwitchElm {
    final int FLAG_TERNARY = 1;
    final int FLAG_NUMERIC = 2;
    double hiV, loV;

    public LogicInputElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy, false);
        hiV = 5;
        loV = 0;

    }

    public LogicInputElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
            StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f, st);
        try {
            hiV = parseDouble(st.nextToken());
            loV = parseDouble(st.nextToken());
        } catch (Exception e) {
            hiV = 5;
            loV = 0;
        }
        if (isTernary())
            posCount = 3;
    }

    boolean isTernary() {
        return (flags & FLAG_TERNARY) != 0;
    }

    boolean isNumeric() {
        return (flags & (FLAG_TERNARY | FLAG_NUMERIC)) != 0;
    }

    int getDumpType() {
        return 'L';
    }

    public String dump() {
        return dumpValues(super.dump(), hiV, loV);
    }

    public int getPostCount() {
        return 1;
    }

    public void setPoints() {
        super.setPoints();
        if (geom().getLead1() == null || geom().getLead1() == geom().getPoint1()
                || geom().getLead1() == geom().getPoint2()) {
            geom().setLead1(new Point());
        }
        double dn = getDn();
        interpPoint(geom().getPoint1(), geom().getPoint2(), geom().getLead1(), 1 - 12 / dn);
    }

    public void draw(Graphics g) {
        g.save();
        Font f = new Font("SansSerif", Font.BOLD, 20);
        g.setFont(f);
        g.setColor(needsHighlight() ? selectColor() : foregroundColor());
        String s = position == 0 ? "L" : "H";
        if (isNumeric())
            s = "" + position;
        setBbox(geom().getPoint1(), geom().getLead1(), 0);
        drawCenteredText(g, s, getX2(), getY2(), true);
        setVoltageColor(g, getNodeVoltage(0));
        drawThickLine(g, geom().getPoint1(), geom().getLead1());
        updateDotCount();
        drawDots(g, geom().getPoint1(), geom().getLead1(), -curcount);
        drawPosts(g);
        g.restore();
    }

    public Rectangle getSwitchRect() {
        return new Rectangle(getX2() - 10, getY2() - 10, 20, 20);
    }

    public void setCurrent(int vs, double c) {
        current = c;
    }

    void calculateCurrent() {
    }

    public void stamp() {
        simulator().stampVoltageSource(0, getNode(0), voltSource);
    }

    public boolean isWireEquivalent() {
        return false;
    }

    public boolean isRemovableWire() {
        return false;
    }

    public void doStep() {
        double v = (position == 0) ? loV : hiV;
        if (isTernary())
            v = loV + position * (hiV - loV) * .5;
        simulator().updateVoltageSource(0, getNode(0), voltSource, v);
    }

    public int getVoltageSourceCount() {
        return 1;
    }

    double getVoltageDiff() {
        return getNodeVoltage(0);
    }

    public void getInfo(String arr[]) {
        arr[0] = "logic input";
        arr[1] = (position == 0) ? "low" : "high";
        if (isNumeric())
            arr[1] = "" + position;
        arr[1] += " (" + getVoltageText(getNodeVoltage(0)) + ")";
        arr[2] = "I = " + getCurrentText(getCurrent());
    }

    public boolean hasGroundConnection(int n1) {
        return true;
    }

    public EditInfo getEditInfo(int n) {
        if (n == 0) {
            EditInfo ei = new EditInfo("", 0, 0, 0);
            ei.checkbox = new Checkbox("Momentary Switch", momentary);
            return ei;
        }
        if (n == 1)
            return new EditInfo("High Logic Voltage", hiV, 10, -10);
        if (n == 2)
            return new EditInfo("Low Voltage", loV, 10, -10);
        if (n == 3) {
            EditInfo ei = new EditInfo("", 0, 0, 0);
            ei.checkbox = new Checkbox("Numeric", isNumeric());
            return ei;
        }
        if (n == 4) {
            EditInfo ei = new EditInfo("", 0, 0, 0);
            ei.checkbox = new Checkbox("Ternary", isTernary());
            return ei;
        }
        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 0)
            momentary = ei.checkbox.getState();
        if (n == 1)
            hiV = ei.value;
        if (n == 2)
            loV = ei.value;
        if (n == 3) {
            if (ei.checkbox.getState())
                flags |= FLAG_NUMERIC;
            else
                flags &= ~FLAG_NUMERIC;
        }
        if (n == 4) {
            if (ei.checkbox.getState())
                flags |= FLAG_TERNARY;
            else
                flags &= ~FLAG_TERNARY;
            posCount = (isTernary()) ? 3 : 2;
        }
    }

    public int getShortcut() {
        return 'i';
    }

    public double getCurrentIntoNode(int n) {
        return current;
    }

    @Override
    public String getJsonTypeName() {
        return "LogicInput";
    }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        props.put("high_voltage", getUnitText(hiV, "V"));
        props.put("low_voltage", getUnitText(loV, "V"));
        props.put("ternary", isTernary());
        props.put("numeric", isNumeric());
        return props;
    }

    @Override
    public String[] getJsonPinNames() {
        return new String[] { "output" };
    }
}
