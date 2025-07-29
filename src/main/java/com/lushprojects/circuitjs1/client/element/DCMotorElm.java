package com.lushprojects.circuitjs1.client.element;

import com.lushprojects.circuitjs1.client.Color;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;
import com.lushprojects.circuitjs1.client.util.Locale;

// based on https://ctms.engin.umich.edu/CTMS/index.php?example=MotorPosition&section=SystemModeling


public class DCMotorElm extends CircuitElm {

    Inductor ind, indInertia;
    // Electrical parameters
    double resistance, inductance;
    // Electro-mechanical parameters
    double K, Kb, J, b, gearRatio, tau; //tau reserved for static friction parameterization  
    public double angle;
    public double speed;


    double coilCurrent;
    double inertiaCurrent;
    int[] voltSources = new int[2];

    public DCMotorElm(int xx, int yy) {
        super(xx, yy);
        ind = new Inductor(simUi);
        indInertia = new Inductor(simUi);
        inductance = .5;
        resistance = 1;
        angle = pi / 2;
        speed = 0;
        K = 0.15;
        b = 0.05;
        J = 0.02;
        Kb = 0.15;
        gearRatio = 1;
        tau = 0;
        ind.setup(inductance, 0, Inductor.FLAG_BACK_EULER);
        indInertia.setup(J, 0, Inductor.FLAG_BACK_EULER);

    }

    public DCMotorElm(int xa, int ya, int xb, int yb, int f,
                      StringTokenizer st) {
        super(xa, ya, xb, yb, f);
        angle = pi / 2;
        speed = 0;
        //read:
        // inductance; resistance, K, Kb, J, b, gearRatio, tau
        inductance = parseDouble(st.nextToken());
        resistance = parseDouble(st.nextToken());
        K = parseDouble(st.nextToken());
        Kb = parseDouble(st.nextToken());
        J = parseDouble(st.nextToken());
        b = parseDouble(st.nextToken());
        gearRatio = parseDouble(st.nextToken());
        tau = parseDouble(st.nextToken());

        ind = new Inductor(simUi);
        indInertia = new Inductor(simUi);
        ind.setup(inductance, 0, Inductor.FLAG_BACK_EULER);
        indInertia.setup(J, 0, Inductor.FLAG_BACK_EULER);
    }

    int getDumpType() {
        return 415;
    }

    public String dump() {
        // dump: inductance; resistance, K, Kb, J, b, gearRatio, tau
        return dumpValues(super.dump(), inductance, resistance, K, Kb, J, b, gearRatio, tau);
    }

    public double getAngle() {
        return (angle);
    }

    Point motorCenter;

    public void setPoints() {
        super.setPoints();
        calcLeads(36);
        motorCenter = interpPoint(point1, point2, .5);
        allocNodes();
    }

    public int getPostCount() {
        return 2;
    }

    public int getInternalNodeCount() {
        return 4;
    }

    public int getVoltageSourceCount() {
        return 2;
    }

    public void setVoltageSource(int n, int v) {
        voltSources[n] = v;
    }

    public void reset() {
        super.reset();
        ind.reset();
        indInertia.reset();
        coilCurrent = 0;
        inertiaCurrent = 0;
    }

    public void stamp() {
        // stamp a bunch of internal parts to help us simulate the motor.  It would be better to simulate this mini-circuit in code to reduce
        // the size of the matrix.

        //nodes[0] nodes [1] are the external nodes
        //Electrical part:
        // inductor from motor nodes[0] to internal nodes[2]
        ind.stamp(nodes[0], nodes[2]);
        // resistor from internal nodes[2] to internal nodes[3] // motor post 2
        simulator.stampResistor(nodes[2], nodes[3], resistance);
        // Back emf voltage source from internal nodes[3] to external nodes [1]
        simulator.stampVoltageSource(nodes[3], nodes[1], voltSources[0]); //

        //Mechanical part:
        // inertia inductor from internal nodes[4] to internal nodes[5]
        indInertia.stamp(nodes[4], nodes[5]);
        // resistor from  internal nodes[5] to  ground
        simulator.stampResistor(nodes[5], 0, b);
        // Voltage Source from  internal nodes[4] to ground
        //System.out.println("doing stamp voltage");
        simulator.stampVoltageSource(nodes[4], 0, voltSources[1]);
        //System.out.println("doing stamp voltage "+voltSource);
    }

    public void startIteration() {
        ind.startIteration(volts[0] - volts[2]);
        indInertia.startIteration(volts[4] - volts[5]);
        // update angle:
        angle = angle + speed * simulator.timeStep;
    }

    /*  boolean hasGroundConnection(int n1) {
	if (n1==4|n1==5) return true;
	else return false;
    }
    boolean getConnection(int n1, int n2) { 
	if((n1==0&n2==2)|(n1==2&n2==3)|(n1==1&n2==3)|(n1==4&n2==5))
	    return true;
	else
	    return false;
    }
     */

    public void doStep() {
        simulator.updateVoltageSource(nodes[4], 0, voltSources[1],
                coilCurrent * K);
        simulator.updateVoltageSource(nodes[3], nodes[1], voltSources[0],
                inertiaCurrent * Kb);
        ind.doStep(volts[0] - volts[2]);
        indInertia.doStep(volts[4] - volts[5]);
    }

    void calculateCurrent() {
        coilCurrent = ind.calculateCurrent(volts[0] - volts[2]);
        inertiaCurrent = indInertia.calculateCurrent(volts[4] - volts[5]);
//	current = (volts[2]-volts[3])/resistance;
        speed = inertiaCurrent;
    }
//    public double getCurrent() { current = (volts[2]-volts[3])/resistance; return current; }

    public void setCurrent(int vn, double c) {
        if (vn == voltSources[0])
            current = c;
    }

    public void draw(Graphics g) {

        int cr = 18;
        int hs = 8;
        setBbox(point1, point2, cr);
        draw2Leads(g);
        //getCurrent();
        doDots(g);
        setPowerColor(g, true);
        Color cc = new Color((int) (165), (int) (165), (int) (165));
        g.setColor(cc);
        g.fillOval(motorCenter.x - (cr), motorCenter.y - (cr), (cr) * 2, (cr) * 2);
        cc = new Color((int) (10), (int) (10), (int) (10));

        g.setColor(cc);
        double angleAux = Math.round(angle * 300.0) / 300.0;
        g.fillOval(motorCenter.x - (int) (cr / 2.2), motorCenter.y - (int) (cr / 2.2), (int) (2 * cr / 2.2), (int) (2 * cr / 2.2));

        g.setColor(cc);
        interpPointFix(lead1, lead2, ps1, 0.5 + .28 * Math.cos(angleAux * gearRatio), .28 * Math.sin(angleAux * gearRatio));
        interpPointFix(lead1, lead2, ps2, 0.5 - .28 * Math.cos(angleAux * gearRatio), -.28 * Math.sin(angleAux * gearRatio));

        drawThickerLine(g, ps1, ps2);
        interpPointFix(lead1, lead2, ps1, 0.5 + .28 * Math.cos(angleAux * gearRatio + pi / 3), .28 * Math.sin(angleAux * gearRatio + pi / 3));
        interpPointFix(lead1, lead2, ps2, 0.5 - .28 * Math.cos(angleAux * gearRatio + pi / 3), -.28 * Math.sin(angleAux * gearRatio + pi / 3));

        drawThickerLine(g, ps1, ps2);

        interpPointFix(lead1, lead2, ps1, 0.5 + .28 * Math.cos(angleAux * gearRatio + 2 * pi / 3), .28 * Math.sin(angleAux * gearRatio + 2 * pi / 3));
        interpPointFix(lead1, lead2, ps2, 0.5 - .28 * Math.cos(angleAux * gearRatio + 2 * pi / 3), -.28 * Math.sin(angleAux * gearRatio + 2 * pi / 3));

        drawThickerLine(g, ps1, ps2);

        drawPosts(g);
    }

    static void drawThickerLine(Graphics g, Point pa, Point pb) {
        g.setLineWidth(6.0);
        g.drawLine(pa.x, pa.y, pb.x, pb.y);
        g.setLineWidth(1.0);
    }

    void interpPointFix(Point a, Point b, Point c, double f, double g) {
        int gx = b.y - a.y;
        int gy = a.x - b.x;
        c.x = (int) Math.round(a.x * (1 - f) + b.x * f + g * gx);
        c.y = (int) Math.round(a.y * (1 - f) + b.y * f + g * gy);
    }


    public void getInfo(String arr[]) {
        arr[0] = "DC Motor";
        getBasicInfo(arr);
        arr[3] = Locale.LS("speed") + " = " + getUnitText(60 * Math.abs(speed) / (2 * Math.PI), Locale.LS("RPM"));
        arr[4] = "L = " + getUnitText(inductance, "H");
        arr[5] = "R = " + getUnitText(resistance, Locale.ohmString);
        arr[6] = "P = " + getUnitText(getPower(), "W");
    }

    public EditInfo getEditInfo(int n) {

        if (n == 0)
            return new EditInfo("Armature inductance (H)", inductance, 0, 0);
        if (n == 1)
            return new EditInfo("Armature Resistance (ohms)", resistance, 0, 0);
        if (n == 2)
            return new EditInfo("Torque constant (Nm/A)", K, 0, 0);
        if (n == 3)
            return new EditInfo("Back emf constant (Vs/rad)", Kb, 0, 0);
        if (n == 4)
            return new EditInfo("Moment of inertia (Kg.m^2)", J, 0, 0);
        if (n == 5)
            return new EditInfo("Friction coefficient (Nms/rad)", b, 0, 0);
        if (n == 6)
            return new EditInfo("Gear Ratio", gearRatio, 0, 0);
        return null;
    }

    public void setEditValue(int n, EditInfo ei) {

        if (ei.value > 0 & n == 0) {
            inductance = ei.value;
            ind.setup(inductance, current, Inductor.FLAG_BACK_EULER);
        }
        if (ei.value > 0 & n == 1)
            resistance = ei.value;
        if (ei.value > 0 & n == 2)
            K = ei.value;
        if (ei.value > 0 & n == 3)
            Kb = ei.value;
        if (ei.value > 0 & n == 4) {
            J = ei.value;
            indInertia.setup(J, inertiaCurrent, Inductor.FLAG_BACK_EULER);
        }
        if (ei.value > 0 & n == 5)
            b = ei.value;
        if (ei.value > 0 & n == 6)
            gearRatio = ei.value;
    }
}
