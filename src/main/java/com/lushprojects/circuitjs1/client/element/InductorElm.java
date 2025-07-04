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
import com.lushprojects.circuitjs1.client.Inductor;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public class InductorElm extends CircuitElm {
    Inductor ind;
    public double inductance;
    double initialCurrent;

    public InductorElm(int xx, int yy) {
        super(xx, yy);
        ind = new Inductor(simUi);
        inductance = 1;
        ind.setup(inductance, current, flags);
    }

    public InductorElm(int xa, int ya, int xb, int yb, int f,
                       StringTokenizer st) {
        super(xa, ya, xb, yb, f);
        ind = new Inductor(simUi);
        inductance = new Double(st.nextToken()).doubleValue();
        current = new Double(st.nextToken()).doubleValue();
        try {
            initialCurrent = new Double(st.nextToken()).doubleValue();
        } catch (Exception e) {
        }
        ind.setup(inductance, current, flags);
    }

    int getDumpType() {
        return 'l';
    }

    public String dump() {
        return super.dump() + " " + inductance + " " + current + " " + initialCurrent;
    }

    public void setPoints() {
        super.setPoints();
        calcLeads(32);
    }

    public void draw(Graphics g) {
        double v1 = volts[0];
        double v2 = volts[1];
        int i;
        int hs = 8;
        setBbox(point1, point2, hs);
        draw2Leads(g);
        setPowerColor(g, false);
        drawCoil(g, 8, lead1, lead2, v1, v2);
        if (simUi.menuManager.showValuesCheckItem.getState()) {
            String s = getShortUnitText(inductance, "H");
            drawValues(g, s, hs);
        }
        doDots(g);
        drawPosts(g);
    }

    public void reset() {
        volts[0] = volts[1] = curcount = 0;
        current = initialCurrent;
        ind.resetTo(initialCurrent);
    }

    public void stamp() {
        ind.stamp(nodes[0], nodes[1]);
    }

    public void startIteration() {
        ind.startIteration(volts[0] - volts[1]);
    }

    public boolean nonLinear() {
        return ind.nonLinear();
    }

    void calculateCurrent() {
        double voltdiff = volts[0] - volts[1];
        current = ind.calculateCurrent(voltdiff);
    }

    public void doStep() {
        double voltdiff = volts[0] - volts[1];
        ind.doStep(voltdiff);
    }

    public void getInfo(String arr[]) {
        arr[0] = "inductor";
        getBasicInfo(arr);
        arr[3] = "L = " + getUnitText(inductance, "H");
        arr[4] = "P = " + getUnitText(getPower(), "W");
    }

    public EditInfo getEditInfo(int n) {
        if (n == 0)
            return new EditInfo("Inductance (H)", inductance, 1e-2, 10);
        if (n == 1) {
            EditInfo ei = new EditInfo("", 0, -1, -1);
            ei.checkbox = new Checkbox("Trapezoidal Approximation",
                    ind.isTrapezoidal());
            return ei;
        }
        if (n == 2)
            return new EditInfo("Initial Current (on Reset) (A)", initialCurrent);
        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 0 && ei.value > 0)
            inductance = ei.value;
        if (n == 1) {
            if (ei.checkbox.getState())
                flags &= ~Inductor.FLAG_BACK_EULER;
            else
                flags |= Inductor.FLAG_BACK_EULER;
        }
        if (n == 2)
            initialCurrent = ei.value;
        ind.setup(inductance, current, flags);
    }

    public int getShortcut() {
        return 'L';
    }

    public double getInductance() {
        return inductance;
    }

    void setInductance(double l) {
        inductance = l;
        ind.setup(inductance, current, flags);
    }
}
