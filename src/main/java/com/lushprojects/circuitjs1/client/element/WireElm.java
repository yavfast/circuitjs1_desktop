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

import com.lushprojects.circuitjs1.client.Checkbox;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public class WireElm extends CircuitElm {
    public WireElm(int xx, int yy) {
        super(xx, yy);
    }

    public WireElm(int xa, int ya, int xb, int yb, int f,
                   StringTokenizer st) {
        super(xa, ya, xb, yb, f);
    }

    static final int FLAG_SHOWCURRENT = 1;
    static final int FLAG_SHOWVOLTAGE = 2;

    public void draw(Graphics g) {
        setVoltageColor(g, volts[0]);
        drawThickLine(g, point1, point2);
        doDots(g);
        setBbox(point1, point2, 3);
        String s = "";
        if (mustShowCurrent()) {
            s = getShortUnitText(Math.abs(getCurrent()), "A");
        }
        if (mustShowVoltage()) {
            s = (!s.isEmpty() ? s + " " : "") + getShortUnitText(volts[0], "V");
        }
        drawValues(g, s, 4);
        drawPosts(g);
    }

    public void stamp() {
//	    sim.stampVoltageSource(nodes[0], nodes[1], voltSource, 0);
    }

    boolean mustShowCurrent() {
        return (flags & FLAG_SHOWCURRENT) != 0;
    }

    boolean mustShowVoltage() {
        return (flags & FLAG_SHOWVOLTAGE) != 0;
    }

    //	int getVoltageSourceCount() { return 1; }
    public void getInfo(String[] arr) {
        arr[0] = "wire";
        arr[1] = "I = " + getCurrentDText(getCurrent());
        arr[2] = "V = " + getVoltageText(volts[0]);
    }

    int getDumpType() {
        return 'w';
    }

    public double getPower() {
        return 0;
    }

    double getVoltageDiff() {
        return volts[0];
    }

    public boolean isWireEquivalent() {
        return true;
    }

    public boolean isRemovableWire() {
        return true;
    }

    public EditInfo getEditInfo(int n) {
        if (n == 0) {
            EditInfo ei = new EditInfo("", 0, -1, -1);
            ei.checkbox = new Checkbox("Show Current", mustShowCurrent());
            return ei;
        }
        if (n == 1) {
            EditInfo ei = new EditInfo("", 0, -1, -1);
            ei.checkbox = new Checkbox("Show Voltage", mustShowVoltage());
            return ei;
        }
        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 0) {
            if (ei.checkbox.getState())
                flags |= FLAG_SHOWCURRENT;
            else
                flags &= ~FLAG_SHOWCURRENT;
        }
        if (n == 1) {
            if (ei.checkbox.getState())
                flags |= FLAG_SHOWVOLTAGE;
            else
                flags &= ~FLAG_SHOWVOLTAGE;
        }
    }

    public int getShortcut() {
        return 'w';
    }

    public int getMouseDistance(int gx, int gy) {
        int thresh = 10;
        int d2 = lineDistanceSq(x, y, x2, y2, gx, gy);
        if (d2 <= thresh * thresh)
            return d2;
        return -1;
    }

    @Override
    public String getJsonTypeName() {
        return "Wire";
    }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        if (mustShowCurrent()) {
            props.put("show_current", true);
        }
        if (mustShowVoltage()) {
            props.put("show_voltage", true);
        }
        return props;
    }

    @Override
    public String[] getJsonPinNames() {
        return new String[] { "a", "b" };
    }

    @Override
    public void applyJsonProperties(java.util.Map<String, Object> props) {
        super.applyJsonProperties(props);
        
        // Parse show current flag
        if (getJsonBoolean(props, "show_current", false)) {
            flags |= FLAG_SHOWCURRENT;
        }
        
        // Parse show voltage flag
        if (getJsonBoolean(props, "show_voltage", false)) {
            flags |= FLAG_SHOWVOLTAGE;
        }
    }
}
