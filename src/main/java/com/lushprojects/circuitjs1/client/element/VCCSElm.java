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

import com.google.gwt.user.client.Window;
import com.lushprojects.circuitjs1.client.CustomLogicModel;
import com.lushprojects.circuitjs1.client.Expr;
import com.lushprojects.circuitjs1.client.ExprParser;
import com.lushprojects.circuitjs1.client.ExprState;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;
import com.lushprojects.circuitjs1.client.util.Locale;

public class VCCSElm extends ChipElm {
    double gain;
    int inputCount;
    Expr expr;
    ExprState exprState;
    String exprString;
    public boolean broken;

    public VCCSElm(int xa, int ya, int xb, int yb, int f,
                   StringTokenizer st) {
        super(xa, ya, xb, yb, f, st);
        inputCount = Integer.parseInt(st.nextToken());
        exprString = CustomLogicModel.unescape(st.nextToken());
        parseExpr();
        setupPins();
    }

    public VCCSElm(int xx, int yy) {
        super(xx, yy);
        inputCount = 2;
        exprString = ".1*(a-b)";
        parseExpr();
        setupPins();
    }

    public String dump() {
        return super.dump() + " " + inputCount + " " + CustomLogicModel.escape(exprString);
    }

    double lastVolts[];

    void setupPins() {
        sizeX = 2;
        sizeY = inputCount > 2 ? inputCount : 2;
        pins = new Pin[inputCount + 2];
        int i;
        for (i = 0; i != inputCount; i++)
            pins[i] = new Pin(i, SIDE_W, Character.toString((char) ('A' + i)));
        pins[inputCount] = new Pin(0, SIDE_E, "C+");
        pins[inputCount + 1] = new Pin(1, SIDE_E, "C-");
        lastVolts = new double[inputCount];
        exprState = new ExprState(inputCount);
        allocNodes();
    }

    String getChipName() {
        return "VCCS~";
    } // ~ is for localization

    public boolean nonLinear() {
        return true;
    }

    @Override
    boolean isDigitalChip() {
        return false;
    }

    public void stamp() {
        simUi.simulator.stampNonLinear(nodes[inputCount]);
        simUi.simulator.stampNonLinear(nodes[inputCount + 1]);
    }

    double sign(double a, double b) {
        return a > 0 ? b : -b;
    }

    double getConvergeLimit() {
        // get maximum change in voltage per step when testing for convergence.  be more lenient over time
        if (simUi.simulator.subIterations < 10)
            return .001;
        if (simUi.simulator.subIterations < 200)
            return .01;
        return .1;
    }

    public boolean hasCurrentOutput() {
        return true;
    }

    public int getOutputNode(int n) {
        return nodes[n + inputCount];
    }

    public void doStep() {
        int i;

        // no current path?  give up
        if (broken) {
            pins[inputCount].current = 0;
            pins[inputCount + 1].current = 0;
            // avoid singular matrix errors
            simUi.simulator.stampResistor(nodes[inputCount], nodes[inputCount + 1], 1e8);
            return;
        }

        // converged yet?
        double convergeLimit = getConvergeLimit();
        for (i = 0; i != inputCount; i++) {
            if (Math.abs(volts[i] - lastVolts[i]) > convergeLimit) {
                simUi.simulator.converged = false;
//        	    sim.console("vcvs " + nodes + " " + i + " " + volts[i] + " " + lastVolts[i] + " " + sim.subIterations);
            }
//        	if (Double.isNaN(volts[i]))
//        	    volts[i] = 0;
        }
        if (expr != null) {
            // calculate output
            for (i = 0; i != inputCount; i++)
                exprState.values[i] = volts[i];
            exprState.t = simulator.t;
            double v0 = -expr.eval(exprState);
//        	if (Math.abs(volts[inputCount]-v0) > Math.abs(v0)*.01 && sim.subIterations < 100)
//        	    sim.converged = false;
            double rs = v0;

            // calculate and stamp output derivatives
            for (i = 0; i != inputCount; i++) {
                double dv = volts[i] - lastVolts[i];
                if (Math.abs(dv) < 1e-6)
                    dv = 1e-6;
                exprState.values[i] = volts[i];
                double v = -expr.eval(exprState);
                exprState.values[i] = volts[i] - dv;
                double v2 = -expr.eval(exprState);
                double dx = (v - v2) / dv;
                if (Math.abs(dx) < 1e-6)
                    dx = sign(dx, 1e-6);
                simUi.simulator.stampVCCurrentSource(nodes[inputCount], nodes[inputCount + 1], nodes[i], 0, dx);
                //if (sim.subIterations > 1)
                //sim.console("ccedx " + i + " " + dx + " " + sim.subIterations + " " + sim.t);
                // adjust right side
                rs -= dx * volts[i];
                exprState.values[i] = volts[i];
            }
//        	sim.console("ccers " + rs);
            simUi.simulator.stampCurrentSource(nodes[inputCount], nodes[inputCount + 1], rs);
            pins[inputCount].current = -v0;
            pins[inputCount + 1].current = v0;
        }

        for (i = 0; i != inputCount; i++)
            lastVolts[i] = volts[i];
    }

    public void stepFinished() {
        exprState.updateLastValues(pins[inputCount].current);
    }

    public void draw(Graphics g) {
        drawChip(g);
    }

    public int getPostCount() {
        return inputCount + 2;
    }

    public int getVoltageSourceCount() {
        return 0;
    }

    int getDumpType() {
        return 213;
    }

    public boolean getConnection(int n1, int n2) {
        return comparePair(inputCount, inputCount + 1, n1, n2);
    }

    public boolean hasGroundConnection(int n1) {
        return false;
    }

    public EditInfo getChipEditInfo(int n) {
        if (n == 0) {
            EditInfo ei = new EditInfo(EditInfo.makeLink("customfunction.html", "Output Function"), 0, -1, -1);
            ei.text = exprString;
            ei.disallowSliders();
            return ei;
        }
        if (n == 1)
            return new EditInfo("# of Inputs", inputCount, 1, 8).
                    setDimensionless();
        return null;
    }

    public void setChipEditValue(int n, EditInfo ei) {
        if (n == 0) {
            exprString = ei.textf.getText();
            parseExpr();
            return;
        }
        if (n == 1) {
            if (ei.value < 0 || ei.value > 8)
                return;
            inputCount = (int) ei.value;
            setupPins();
            allocNodes();
            setPoints();
        }
    }

    void setExpr(String expr) {
        exprString = expr;
        parseExpr();
    }

    void parseExpr() {
        ExprParser parser = new ExprParser(exprString);
        expr = parser.parseExpression();
        String err = parser.gotError();
        if (err != null)
            Window.alert(Locale.LS("Parse error in expression") + ": " + exprString + ": " + err);
    }

    public void getInfo(String arr[]) {
        super.getInfo(arr);
        int i;
        for (i = 0; arr[i] != null; i++) ;
        arr[i] = "I = " + getCurrentText(pins[inputCount].current);
    }

    public void reset() {
        super.reset();
        exprState.reset();
    }
}

