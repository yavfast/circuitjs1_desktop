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

public class VCOElm extends ChipElm {
    public VCOElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy);
    }

    public VCOElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
                  StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f, st);
    }

    String getChipName() {
        return "VCO";
    }

    void setupPins() {
        sizeX = 2;
        sizeY = 4;
        pins = new Pin[6];
        pins[0] = new Pin(0, SIDE_W, "Vi");
        pins[1] = new Pin(3, SIDE_W, "Vo");
        pins[1].output = true;
        pins[2] = new Pin(0, SIDE_E, "C");
        pins[3] = new Pin(1, SIDE_E, "C");
        pins[4] = new Pin(2, SIDE_E, "R1");
        pins[4].output = true;
        pins[5] = new Pin(3, SIDE_E, "R2");
        pins[5].output = true;
    }

    public boolean nonLinear() {
        return true;
    }

    public void stamp() {
        CircuitSimulator simulator = simulator();
        // output pin
        simulator.stampVoltageSource(0, getNode(1), pins[1].voltSource);
        // attach Vi to R1 pin so its current is proportional to Vi
        simulator.stampVoltageSource(getNode(0), getNode(4), pins[4].voltSource, 0);
        // attach 5V to R2 pin so we get a current going
        simulator.stampVoltageSource(0, getNode(5), pins[5].voltSource, 5);
        // put resistor across cap pins to give current somewhere to go
        // in case cap is not connected
        simulator.stampResistor(getNode(2), getNode(3), cResistance);
        simulator.stampNonLinear(getNode(2));
        simulator.stampNonLinear(getNode(3));
    }

    final double cResistance = 1e6;
    double cCurrent;
    int cDir;

    public void doStep() {
        double vc = getNodeVoltage(3) - getNodeVoltage(2);
        double vo = getNodeVoltage(1);
        int dir = (vo < 2.5) ? 1 : -1;
        // switch direction of current through cap as we oscillate
        if (vo < 2.5 && vc > 4.5) {
            vo = 5;
            dir = -1;
        }
        if (vo > 2.5 && vc < .5) {
            vo = 0;
            dir = 1;
        }

        CircuitSimulator simulator = simulator();
        // generate output voltage
		simulator.updateVoltageSource(0, getNode(1), pins[1].voltSource, vo);
        // now we set the current through the cap to be equal to the
        // current through R1 and R2, so we can measure the voltage
        // across the cap
        int cur1 = simulator().nodeList.size() + pins[4].voltSource;
        int cur2 = simulator().nodeList.size() + pins[5].voltSource;
        simulator.stampMatrix(getNode(2), cur1, dir);
        simulator.stampMatrix(getNode(2), cur2, dir);
        simulator.stampMatrix(getNode(3), cur1, -dir);
        simulator.stampMatrix(getNode(3), cur2, -dir);
        cDir = dir;
    }

    // can't do this in calculateCurrent() because it's called before
    // we get pins[4].current and pins[5].current, which we need
    void computeCurrent() {
        double c = cDir * (pins[4].current + pins[5].current) +
                (getNodeVoltage(3) - getNodeVoltage(2)) / cResistance;
        pins[2].current = -c;
        pins[3].current = c;
        pins[0].current = -pins[4].current;
    }

    public void draw(Graphics g) {
        computeCurrent();
        drawChip(g);
    }

    public int getPostCount() {
        return 6;
    }

    public int getVoltageSourceCount() {
        return 3;
    }

    int getDumpType() {
        return 158;
    }

    @Override
    boolean isDigitalChip() {
        return false;
    }

    @Override
    public String getJsonTypeName() { return "VCO"; }
}
