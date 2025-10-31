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
import com.lushprojects.circuitjs1.client.CircuitSimulator;
import com.lushprojects.circuitjs1.client.Color;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;
import com.lushprojects.circuitjs1.client.util.Locale;

public class CapacitorElm extends CircuitElm {

    public static final int FLAG_BACK_EULER = 2;
    public static final int FLAG_RESISTANCE = 4;

    public double capacitance;
    double compResistance;
    double voltDiff;
    double seriesResistance;
    double initialVoltage;
    int capNode2;
    Point[] plate1;
    Point[] plate2;

    public CapacitorElm(int xx, int yy) {
        super(xx, yy);
        capacitance = 1e-5;
        initialVoltage = 1e-3;
        seriesResistance = 1e-3;
    }

    public CapacitorElm(int xa, int ya, int xb, int yb, int f, StringTokenizer st) {
        super(xa, ya, xb, yb, f);
        capacitance = parseDouble(st.nextToken());
        voltDiff = parseDouble(st.nextToken());
        initialVoltage = parseDouble(st.tryNextToken(), 1e-3);
        if ((flags & FLAG_RESISTANCE) != 0) {
            seriesResistance = parseDouble(st.tryNextToken(), 1e-3);
        }
    }

    boolean isTrapezoidal() {
        return (flags & FLAG_BACK_EULER) == 0;
    }

    public void reset() {
        super.reset();
        current = curcount = curSourceValue = 0;
        // put small charge on caps when reset to start oscillators
        voltDiff = initialVoltage;
    }

    public void shorted() {
        super.reset();
        voltDiff = current = curcount = curSourceValue = 0;
    }

    int getDumpType() {
        return 'c';
    }

    public String dump() {
        flags |= FLAG_RESISTANCE;
        return dumpValues(super.dump(), capacitance, voltDiff, initialVoltage, seriesResistance);
    }

    // used for PolarCapacitorElm
    Point[] platePoints;

    public void setPoints() {
        super.setPoints();
        double f = (dn / 2 - 4) / dn;
        // calc leads
        lead1 = interpPoint(point1, point2, f);
        lead2 = interpPoint(point1, point2, 1 - f);
        // calc plates
        plate1 = newPointArray(2);
        plate2 = newPointArray(2);
        interpPoint2(point1, point2, plate1[0], plate1[1], f, 12);
        interpPoint2(point1, point2, plate2[0], plate2[1], 1 - f, 12);
    }

    public void draw(Graphics g) {
        int hs = 12;
        setBbox(point1, point2, hs);

        // draw first lead and plate
        setVoltageColor(g, volts[0]);
        drawThickLine(g, point1, lead1);
        setPowerColor(g, false);
        drawThickLine(g, plate1[0], plate1[1]);
        if (simUi.menuManager.powerCheckItem.getState())
            g.setColor(Color.gray);

        // draw second lead and plate
        setVoltageColor(g, volts[1]);
        drawThickLine(g, point2, lead2);
        setPowerColor(g, false);
        if (platePoints == null) {
            drawThickLine(g, plate2[0], plate2[1]);
        } else {
            for (int i = 0; i != platePoints.length - 1; i++)
                drawThickLine(g, platePoints[i], platePoints[i + 1]);
        }

        updateDotCount();
        if (circuitEditor().dragElm != this) {
            drawDots(g, point1, lead1, curcount);
            drawDots(g, point2, lead2, -curcount);
        }

        drawPosts(g);

        if (simUi.menuManager.showValuesCheckItem.getState()) {
            String s = getShortUnitText(capacitance, "F");
            drawValues(g, s, hs);
        }
    }

    public void stamp() {
        CircuitSimulator simulator = simulator();
        if (circuitDocument.circuitInfo.dcAnalysisFlag) {
            // when finding DC operating point, replace cap with a 100M resistor
            simulator.stampResistor(nodes[0], nodes[1], 1e8);
            curSourceValue = 0;
            capNode2 = 1;
            return;
        }

        // The capacitor model is between nodes 0 and capNode2.  For an
        // ideal capacitor, capNode2 is node 1.  If series resistance, capNode2 = 2
        // and we place a resistor between nodes 2 and 1.
        // 2 is an internal node, 0 and 1 are the capacitor terminals.
        capNode2 = (seriesResistance > 0) ? 2 : 1;

        // capacitor companion model using trapezoidal approximation
        // (Norton equivalent) consists of a current source in
        // parallel with a resistor.  Trapezoidal is more accurate
        // than backward euler but can cause oscillatory behavior
        // if RC is small relative to the timestep.
        if (isTrapezoidal()) {
            compResistance = simulator.timeStep / (2 * capacitance);
        } else {
            compResistance = simulator.timeStep / capacitance;
        }
        simulator.stampResistor(nodes[0], nodes[capNode2], compResistance);
        simulator.stampRightSide(nodes[0]);
        simulator.stampRightSide(nodes[capNode2]);
        if (seriesResistance > 0) {
            simulator.stampResistor(nodes[1], nodes[2], seriesResistance);
        }
    }

    public void startIteration() {
        if (isTrapezoidal()) {
            curSourceValue = -voltDiff / compResistance - current;
        } else {
            curSourceValue = -voltDiff / compResistance;
        }
    }

    public void stepFinished() {
        voltDiff = volts[0] - volts[capNode2];
        calculateCurrent();
    }

    public void setNodeVoltage(int n, double c) {
        // do not calculate current, that only gets done in stepFinished().  otherwise calculateCurrent() may get
        // called while stamping the circuit, which might discharge the cap (since we use that current to calculate
        // curSourceValue in startIteration)
        volts[n] = c;
    }

    void calculateCurrent() {
        double voltdiff = volts[0] - volts[capNode2];
        if (circuitDocument.circuitInfo.dcAnalysisFlag) {
            current = voltdiff / 1e8;
            return;
        }
        // we check compResistance because this might get called
        // before stamp(), which sets compResistance, causing
        // infinite current
        if (compResistance > 0)
            current = voltdiff / compResistance + curSourceValue;
    }

    double curSourceValue;

    public void doStep() {
        if (circuitDocument.circuitInfo.dcAnalysisFlag) {
            return;
        }
        simulator().stampCurrentSource(nodes[0], nodes[capNode2], curSourceValue);
    }

    public int getInternalNodeCount() {
        return (!circuitDocument.circuitInfo.dcAnalysisFlag && seriesResistance > 0) ? 1 : 0;
    }

    public void getInfo(String[] arr) {
        arr[0] = "capacitor";
        getBasicInfo(arr);
        arr[3] = "C = " + getUnitText(capacitance, "F");
        arr[4] = "P = " + getUnitText(getPower(), "W");
        //double v = getVoltageDiff();
        //arr[4] = "U = " + getUnitText(.5*capacitance*v*v, "J");
    }

    @Override
    public String getScopeText(int v) {
        return Locale.LS("capacitor") + ", " + getUnitText(capacitance, "F");
    }

    public EditInfo getEditInfo(int n) {
        switch (n) {
            case 0:
                return new EditInfo("Capacitance (F)", capacitance, 1e-6, 1e-3, "F");
            case 1:
                EditInfo ei = new EditInfo("", 0, -1, -1);
                ei.checkbox = new Checkbox("Trapezoidal Approximation", isTrapezoidal());
                return ei;
            case 2:
                return new EditInfo("Initial Voltage (on Reset)", initialVoltage, -1, -1, "V");
            case 3:
                return new EditInfo("Series Resistance (0 = infinite)", seriesResistance, 0, 0, "Ω");
        }
        // if you add more things here, check PolarCapacitorElm
        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
        switch (n) {
            case 0:
                capacitance = (ei.value > 0) ? ei.value : 1e-12;
                break;
            case 1:
                if (ei.checkbox.getState()) {
                    flags &= ~FLAG_BACK_EULER;
                } else {
                    flags |= FLAG_BACK_EULER;
                }
                break;
            case 2:
                initialVoltage = ei.value;
                break;
            case 3:
                seriesResistance = ei.value;
                break;
        }
    }

    public int getShortcut() {
        return 'c';
    }

    public double getCapacitance() {
        return capacitance;
    }

    public double getSeriesResistance() {
        return seriesResistance;
    }

    public void setCapacitance(double c) {
        capacitance = c;
    }

    public void setSeriesResistance(double c) {
        seriesResistance = c;
    }

    public boolean isIdealCapacitor() {
        return (seriesResistance == 0);
    }
}
