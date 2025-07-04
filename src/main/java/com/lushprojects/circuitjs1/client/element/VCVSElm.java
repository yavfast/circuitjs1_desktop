/*    
    Copyright (C) Paul Falstad
    
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

import com.lushprojects.circuitjs1.client.ExprState;
import com.lushprojects.circuitjs1.client.StringTokenizer;

public class VCVSElm extends VCCSElm {
    public VCVSElm(int xa, int ya, int xb, int yb, int f,
                   StringTokenizer st) {
        super(xa, ya, xb, yb, f, st);
    }

    public VCVSElm(int xx, int yy) {
        super(xx, yy);
    }

    void setupPins() {
        sizeX = 2;
        sizeY = inputCount > 2 ? inputCount : 2;
        pins = new Pin[inputCount + 2];
        int i;
        for (i = 0; i != inputCount; i++)
            pins[i] = new Pin(i, SIDE_W, Character.toString((char) ('A' + i)));
        pins[inputCount] = new Pin(0, SIDE_E, "V+");
        pins[inputCount].output = true;
        pins[inputCount + 1] = new Pin(1, SIDE_E, "V-");
        lastVolts = new double[inputCount];
        exprState = new ExprState(inputCount);
        allocNodes();
    }

    String getChipName() {
        return "VCVS";
    }

    public void stamp() {
        int vn = pins[inputCount].voltSource + simUi.simulator.nodeList.size();
        simUi.simulator.stampNonLinear(vn);
        simUi.simulator.stampVoltageSource(nodes[inputCount + 1], nodes[inputCount], pins[inputCount].voltSource);
    }

    public void doStep() {
        int i;
        // converged yet?
        double convergeLimit = getConvergeLimit();
        for (i = 0; i != inputCount; i++) {
            if (Math.abs(volts[i] - lastVolts[i]) > convergeLimit)
                simUi.simulator.converged = false;
//        	if (Double.isNaN(volts[i]))
//        	    volts[i] = 0;
        }
        int vn = pins[inputCount].voltSource + simUi.simulator.nodeList.size();
        if (expr != null) {
            // calculate output
            for (i = 0; i != inputCount; i++)
                exprState.values[i] = volts[i];
            exprState.t = simulator.t;
            double v0 = expr.eval(exprState);
            if (Math.abs(volts[inputCount] - volts[inputCount + 1] - v0) > Math.abs(v0) * .01 && simUi.simulator.subIterations < 100)
                simUi.simulator.converged = false;
            double rs = v0;

            // calculate and stamp output derivatives
            for (i = 0; i != inputCount; i++) {
                double dv = volts[i] - lastVolts[i];
                if (Math.abs(dv) < 1e-6)
                    dv = 1e-6;
                exprState.values[i] = volts[i];
                double v = expr.eval(exprState);
                exprState.values[i] = volts[i] - dv;
                double v2 = expr.eval(exprState);
                double dx = (v - v2) / dv;
                if (Math.abs(dx) < 1e-6)
                    dx = sign(dx, 1e-6);
//        	    if (sim.subIterations > 1)
//        		sim.console("ccedx " + i + " " + dx + " v " + v + " v2 " + v2 + " dv " + dv + " lv " + lastVolts[i] + " " + volts[i] + " " + sim.subIterations + " " + sim.t);
                simUi.simulator.stampMatrix(vn, nodes[i], -dx);
                // adjust right side
                rs -= dx * volts[i];
                exprState.values[i] = volts[i];
            }
            simUi.simulator.stampRightSide(vn, rs);
        }

        for (i = 0; i != inputCount; i++)
            lastVolts[i] = volts[i];
    }

    public void stepFinished() {
        exprState.updateLastValues(volts[inputCount] - volts[inputCount + 1]);
    }

    public int getPostCount() {
        return inputCount + 2;
    }

    public int getVoltageSourceCount() {
        return 1;
    }

    public int getDumpType() {
        return 212;
    }

    public boolean hasCurrentOutput() {
        return false;
    }

    public void setCurrent(int vn, double c) {
        if (pins[inputCount].voltSource == vn) {
            pins[inputCount].current = c;
            pins[inputCount + 1].current = -c;
        }
    }

}

