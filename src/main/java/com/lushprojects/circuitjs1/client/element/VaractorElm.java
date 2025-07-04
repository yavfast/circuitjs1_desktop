package com.lushprojects.circuitjs1.client.element;

import com.lushprojects.circuitjs1.client.Color;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public class VaractorElm extends DiodeElm {
    double baseCapacitance;

    public VaractorElm(int xx, int yy) {
        super(xx, yy);
        baseCapacitance = 4e-12;
    }

    public VaractorElm(int xa, int ya, int xb, int yb, int f,
                       StringTokenizer st) {
        super(xa, ya, xb, yb, f, st);
        capvoltdiff = new Double(st.nextToken()).doubleValue();
        baseCapacitance = new Double(st.nextToken()).doubleValue();
    }

    int getDumpType() {
        return 176;
    }

    public void getInfo(String arr[]) {
        super.getInfo(arr);
        arr[0] = "varactor";
        arr[5] = "C = " + getUnitText(capacitance, "F");
    }

    double capacitance, capCurrent;

    // DiodeElm.lastvoltdiff = volt diff from last iteration
    // capvoltdiff = volt diff from last timestep
    double compResistance, capvoltdiff;
    Point plate1[], plate2[];

    public void stepFinished() {
        capvoltdiff = volts[0] - volts[1];
    }

    void calculateCurrent() {
        super.calculateCurrent();
        current += capCurrent;
    }

    public void reset() {
        super.reset();
        capvoltdiff = 0;
    }

    public String dump() {
        return super.dump() + " " + capvoltdiff + " " + baseCapacitance;
    }

    public void setPoints() {
        super.setPoints();
        double platef = .6;
        Point pa[] = newPointArray(2);
        interpPoint2(lead1, lead2, pa[0], pa[1], 0, hs);
        interpPoint2(lead1, lead2, cathode[0], cathode[1], platef, hs);
        Point arrowPoint = interpPoint(lead1, lead2, platef);
        poly = createPolygon(pa[0], pa[1], arrowPoint);
        // calc plates
        plate1 = newPointArray(2);
        plate2 = newPointArray(2);
        interpPoint2(lead1, lead2, plate1[0], plate1[1], platef, hs);
        interpPoint2(lead1, lead2, plate2[0], plate2[1], 1, hs);
    }


    public void draw(Graphics g) {
        // draw leads and diode arrow
        drawDiode(g);

        // draw first plate
        setVoltageColor(g, volts[0]);
        setPowerColor(g, false);
        drawThickLine(g, plate1[0], plate1[1]);
        if (simUi.menuManager.powerCheckItem.getState())
            g.setColor(Color.gray);

        // draw second plate
        setVoltageColor(g, volts[1]);
        setPowerColor(g, false);
        drawThickLine(g, plate2[0], plate2[1]);

        doDots(g);
        drawPosts(g);
    }

    public void stamp() {
        super.stamp();
        simulator.stampVoltageSource(nodes[0], nodes[2], voltSource);
        simulator.stampNonLinear(nodes[2]);
    }

    public void startIteration() {
        super.startIteration();
        // capacitor companion model using trapezoidal approximation
        // (Thevenin equivalent) consists of a voltage source in
        // series with a resistor
        double c0 = baseCapacitance;
        if (capvoltdiff > 0)
            capacitance = c0;
        else
            capacitance = c0 / Math.pow(1 - capvoltdiff / model.fwdrop, .5);
        compResistance = simulator.timeStep / (2 * capacitance);
        voltSourceValue = -capvoltdiff - capCurrent * compResistance;
    }

    public void doStep() {
        super.doStep();
        simulator.stampResistor(nodes[2], nodes[1], compResistance);
        simulator.updateVoltageSource(nodes[0], nodes[2], voltSource,
                voltSourceValue);
    }

    public EditInfo getEditInfo(int n) {
        if (n == 1)
            return new EditInfo("Capacitance @ 0V (F)", baseCapacitance, 10, 1000);
        return super.getEditInfo(n);
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 1) {
            baseCapacitance = ei.value;
            return;
        }
        super.setEditValue(n, ei);
    }

    public int getShortcut() {
        return 0;
    }

    public void setCurrent(int x, double c) {
        capCurrent = c;
    }

    double voltSourceValue;

    public int getVoltageSourceCount() {
        return 1;
    }

    public int getInternalNodeCount() {
        return 1;
    }
}
    
