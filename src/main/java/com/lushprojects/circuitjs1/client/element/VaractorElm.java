package com.lushprojects.circuitjs1.client.element;

import com.lushprojects.circuitjs1.client.CircuitDocument;

import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public class VaractorElm extends DiodeElm {
    double baseCapacitance;

    public VaractorElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy);
        baseCapacitance = 4e-12;
    }

    public VaractorElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
            StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f, st);
        capvoltdiff = parseDouble(st.nextToken());
        baseCapacitance = parseDouble(st.nextToken());
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
        capvoltdiff = getNodeVoltage(0) - getNodeVoltage(1);
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
        return dumpValues(super.dump(), capvoltdiff, baseCapacitance);
    }

    private Point[] pa;
    private Point arrowPoint;

    public void setPoints() {
        super.setPoints();
        double platef = .6;
        if (pa == null)
            pa = newPointArray(2);
        interpPoint2(geom().getLead1(), geom().getLead2(), pa[0], pa[1], 0, hs);
        interpPoint2(geom().getLead1(), geom().getLead2(), cathode[0], cathode[1], platef, hs);
        if (arrowPoint == null)
            arrowPoint = new Point();
        interpPoint(geom().getLead1(), geom().getLead2(), arrowPoint, platef);
        poly = createPolygon(pa[0], pa[1], arrowPoint);
        // calc plates
        if (plate1 == null)
            plate1 = newPointArray(2);
        if (plate2 == null)
            plate2 = newPointArray(2);
        interpPoint2(geom().getLead1(), geom().getLead2(), plate1[0], plate1[1], platef, hs);
        interpPoint2(geom().getLead1(), geom().getLead2(), plate2[0], plate2[1], 1, hs);
    }

    public void draw(Graphics g) {
        // draw leads and diode arrow
        drawDiode(g);

        // draw first plate
        setVoltageColor(g, getNodeVoltage(0));
        setPowerColor(g, false);
        drawThickLine(g, plate1[0], plate1[1]);
        if (displaySettings().showPower()) {
            g.setColor(neutralColor());
        }

        // draw second plate
        setVoltageColor(g, getNodeVoltage(1));
        setPowerColor(g, false);
        drawThickLine(g, plate2[0], plate2[1]);

        doDots(g);
        drawPosts(g);
    }

    public void stamp() {
        super.stamp();
        simulator().stampVoltageSource(getNode(0), getNode(2), voltSource);
        simulator().stampNonLinear(getNode(2));
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
        compResistance = simulator().timeStep / (2 * capacitance);
        voltSourceValue = -capvoltdiff - capCurrent * compResistance;
    }

    public void doStep() {
        super.doStep();
        simulator().stampResistor(getNode(2), getNode(1), compResistance);
        simulator().updateVoltageSource(getNode(0), getNode(2), voltSource,
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

    @Override
    public String getJsonTypeName() {
        return "Varactor";
    }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        props.put("base_capacitance", getUnitText(baseCapacitance, "F"));
        return props;
    }

    @Override
    public String[] getJsonPinNames() {
        return new String[] { "anode", "cathode" };
    }

    @Override
    public java.util.Map<String, Object> getJsonState() {
        java.util.Map<String, Object> state = super.getJsonState();
        state.put("capvoltdiff", capvoltdiff);
        state.put("capacitance", capacitance);
        state.put("capCurrent", capCurrent);
        return state;
    }

    @Override
    public void applyJsonState(java.util.Map<String, Object> state) {
        super.applyJsonState(state);
        if (state.containsKey("capvoltdiff"))
            capvoltdiff = ((Number) state.get("capvoltdiff")).doubleValue();
        if (state.containsKey("capacitance"))
            capacitance = ((Number) state.get("capacitance")).doubleValue();
        if (state.containsKey("capCurrent"))
            capCurrent = ((Number) state.get("capCurrent")).doubleValue();
    }
}
