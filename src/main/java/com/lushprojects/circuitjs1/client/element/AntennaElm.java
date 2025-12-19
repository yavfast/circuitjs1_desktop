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
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public class AntennaElm extends RailElm {
    public AntennaElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy, WF_AC);
    }

    public AntennaElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
                      StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f, st);
        waveform = WF_AC;
    }

    double fmphase;

    void drawRail(Graphics g) {
        drawRailText(g, "Ant");
    }

    double getVoltage() {
        CircuitSimulator simulator = simulator();
        double fm = 3 * Math.sin(fmphase);
        return Math.sin(2 * PI * simulator().t * 3000) * (1.3 + Math.sin(2 * PI * simulator().t * 12)) * 3 +
                Math.sin(2 * PI * simulator().t * 2710) * (1.3 + Math.sin(2 * PI * simulator().t * 13)) * 3 +
                Math.sin(2 * PI * simulator().t * 2433) * (1.3 + Math.sin(2 * PI * simulator().t * 14)) * 3 + fm;
    }

    public void stepFinished() {
        CircuitSimulator simulator = simulator();
        fmphase += 2 * PI * (2200 + Math.sin(2 * PI * simulator().t * 13) * 100) * simulator().timeStep;
    }

    int getDumpType() {
        return 'A';
    }

    public int getShortcut() {
        return 0;
    }

    public void getInfo(String arr[]) {
        super.getInfo(arr);
        arr[0] = "Antenna (amplified)";
    }

    public EditInfo getEditInfo(int n) {
        return null;
    }

    @Override
    public String getJsonTypeName() {
        return "Antenna";
    }
}
