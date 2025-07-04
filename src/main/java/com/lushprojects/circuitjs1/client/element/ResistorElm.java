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

import com.google.gwt.canvas.dom.client.CanvasGradient;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;
import com.lushprojects.circuitjs1.client.util.Locale;

public class ResistorElm extends CircuitElm {
    public double resistance;

    public ResistorElm(int xx, int yy) {
        super(xx, yy);
        resistance = 1000;
    }

    public ResistorElm(int xa, int ya, int xb, int yb, int f,
                       StringTokenizer st) {
        super(xa, ya, xb, yb, f);
        resistance = new Double(st.nextToken()).doubleValue();
    }

    int getDumpType() {
        return 'r';
    }

    public String dump() {
        return super.dump() + " " + resistance;
    }

    Point ps3, ps4;

    public void setPoints() {
        super.setPoints();
        calcLeads(32);
        ps3 = new Point();
        ps4 = new Point();
    }

    public void draw(Graphics g) {
        int segments = 16;
        int i;
        int ox = 0;
        //int hs = sim.euroResistorCheckItem.getState() ? 6 : 8;
        int hs = 6;
        double v1 = volts[0];
        double v2 = volts[1];
        setBbox(point1, point2, hs);
        draw2Leads(g);

        //   double segf = 1./segments;
        double len = distance(lead1, lead2);
        g.context.save();
        g.context.setLineWidth(3.0);
        g.context.transform(((double) (lead2.x - lead1.x)) / len, ((double) (lead2.y - lead1.y)) / len, -((double) (lead2.y - lead1.y)) / len, ((double) (lead2.x - lead1.x)) / len, lead1.x, lead1.y);
        if (simUi.menuManager.voltsCheckItem.getState()) {
            CanvasGradient grad = g.context.createLinearGradient(0, 0, len, 0);
            grad.addColorStop(0, getVoltageColor(g, v1).getHexValue());
            grad.addColorStop(1.0, getVoltageColor(g, v2).getHexValue());
            g.context.setStrokeStyle(grad);
        } else
            setPowerColor(g, true);
        if (dn < 30)
            hs = 2;
        if (!simUi.menuManager.euroResistorCheckItem.getState()) {
            g.context.beginPath();
            g.context.moveTo(0, 0);
            for (i = 0; i < 4; i++) {
                g.context.lineTo((1 + 4 * i) * len / 16, hs);
                g.context.lineTo((3 + 4 * i) * len / 16, -hs);
            }
            g.context.lineTo(len, 0);
            g.context.stroke();

        } else {
            g.context.strokeRect(0, -hs, len, 2.0 * hs);
        }
        g.context.restore();
        if (simUi.menuManager.showValuesCheckItem.getState()) {
            String s = getShortUnitText(resistance, "");
            drawValues(g, s, hs + 2);
        }
        doDots(g);
        drawPosts(g);
    }

    void calculateCurrent() {
        current = (volts[0] - volts[1]) / resistance;
        //System.out.print(this + " res current set to " + current + "\n");
    }

    public void stamp() {
        simulator.stampResistor(nodes[0], nodes[1], resistance);
    }

    public void getInfo(String arr[]) {
        arr[0] = "resistor";
        getBasicInfo(arr);
        arr[3] = "R = " + getUnitText(resistance, Locale.ohmString);
        arr[4] = "P = " + getUnitText(getPower(), "W");
    }

    @Override
    public String getScopeText(int v) {
        return Locale.LS("resistor") + ", " + getUnitText(resistance, Locale.ohmString);
    }

    public EditInfo getEditInfo(int n) {
        // ohmString doesn't work here on linux
        if (n == 0)
            return new EditInfo("Resistance (ohms)", resistance, 0, 0);
        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
        resistance = (ei.value <= 0) ? 1e-9 : ei.value;
    }

    public int getShortcut() {
        return 'r';
    }

    double getResistance() {
        return resistance;
    }

    void setResistance(double r) {
        resistance = r;
    }
}
