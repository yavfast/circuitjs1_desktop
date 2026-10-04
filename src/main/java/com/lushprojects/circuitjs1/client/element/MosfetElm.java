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

import com.lushprojects.circuitjs1.client.Checkbox;
import com.lushprojects.circuitjs1.client.CircuitSimulator;

import com.lushprojects.circuitjs1.client.Diode;
import com.lushprojects.circuitjs1.client.Font;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.Polygon;
import com.lushprojects.circuitjs1.client.Scope;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;
import com.lushprojects.circuitjs1.client.util.Locale;

public class MosfetElm extends CircuitElm {
    int pnp;
    int FLAG_PNP = 1;
    int FLAG_SHOWVT = 2;
    int FLAG_DIGITAL = 4;
    int FLAG_FLIP = 8;
    int FLAG_HIDE_BULK = 16;
    int FLAG_BODY_DIODE = 32;
    int FLAG_BODY_TERMINAL = 64;
    int FLAGS_GLOBAL = (FLAG_HIDE_BULK | FLAG_DIGITAL);
    int bodyTerminal;

    double vt;
    // beta = 1/(RdsON*(Vgs-Vt))
    double beta;
    static int globalFlags;

    /**
     * @return the session-wide MOSFET display flags (digital symbol, hidden bulk), which every
     *         MOSFET adopts in setPoints and a text load sets from the loaded MOSFETs
     */
    public static int getGlobalFlags() {
        return globalFlags;
    }

    /** Restores the session-wide MOSFET display flags (background-document scope, agent rollback). */
    public static void setGlobalFlags(int flags) {
        globalFlags = flags;
    }

    // "digital" is the session-wide display setting (globalFlags), copied onto every MOSFET in
    // setPoints: exported, but not applied per element from the key
    @Override
    public java.util.Set<String> getJsonReadOnlyProperties() {
        java.util.Set<String> keys = super.getJsonReadOnlyProperties();
        keys.add("digital");
        return keys;
    }
    Diode diodeB1, diodeB2;
    double diodeCurrent1, diodeCurrent2, bodyCurrent;
    double curcount_body1, curcount_body2;
    static double lastBeta;

    MosfetElm(CircuitDocument circuitDocument, int xx, int yy, boolean pnpflag) {
        super(circuitDocument, xx, yy);
        pnp = (pnpflag) ? -1 : 1;
        flags = (pnpflag) ? FLAG_PNP : 0;
        flags |= FLAG_BODY_DIODE;
        noDiagonal = true;
        setupDiodes();
        beta = getDefaultBeta();
        vt = getDefaultThreshold();
    }

    public MosfetElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
            StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f);
        pnp = ((f & FLAG_PNP) != 0) ? -1 : 1;
        noDiagonal = true;
        setupDiodes();
        vt = getDefaultThreshold();
        beta = getBackwardCompatibilityBeta();
        try {
            vt = parseDouble(st.nextToken());
            beta = parseDouble(st.nextToken());
        } catch (Exception e) {
        }
        globalFlags = flags & (FLAGS_GLOBAL);
        allocNodes(); // make sure nodeStates has the right number of elements when hasBodyTerminal()
                      // is true
    }

    @Override
    public String getIdPrefix() {
        return "M";
    }

    // set up body diodes
    void setupDiodes() {
        // diode from node 1 to body terminal
        diodeB1 = new Diode(simulator());
        diodeB1.setupForDefaultModel();
        // diode from node 2 to body terminal
        diodeB2 = new Diode(simulator());
        diodeB2.setupForDefaultModel();
    }

    @Override
    public void setCircuitDocument(com.lushprojects.circuitjs1.client.CircuitDocument doc) {
        super.setCircuitDocument(doc);
        diodeB1.setSimulator(simulator());
        diodeB2.setSimulator(simulator());
    }

    double getDefaultThreshold() {
        return 1.5;
    }

    // default beta for new elements
    double getDefaultBeta() {
        return lastBeta == 0 ? getBackwardCompatibilityBeta() : lastBeta;
    }

    // default for elements in old files with no configurable beta. JfetElm
    // overrides this.
    // Not sure where this value came from, but the ZVP3306A has a beta of about
    // .027. Power MOSFETs have much higher betas (like 80 or more)
    double getBackwardCompatibilityBeta() {
        return .02;
    }

    public boolean nonLinear() {
        return true;
    }

    boolean drawDigital() {
        return (flags & FLAG_DIGITAL) != 0;
    }

    boolean showBulk() {
        return (flags & (FLAG_DIGITAL | FLAG_HIDE_BULK)) == 0;
    }

    boolean hasBodyTerminal() {
        return (flags & FLAG_BODY_TERMINAL) != 0 && doBodyDiode();
    }

    boolean doBodyDiode() {
        return (flags & FLAG_BODY_DIODE) != 0 && showBulk();
    }

    public void reset() {
        lastv1 = lastv2 = 0;
        setNodeVoltageDirect(0, 0);
        setNodeVoltageDirect(1, 0);
        setNodeVoltageDirect(2, 0);
        curcount = 0;
        curcount_body1 = curcount_body2 = 0;
        diodeB1.reset();
        diodeB2.reset();
        if (doBodyDiode())
            setNodeVoltageDirect(bodyTerminal, 0);
    }

    public String dump() {
        return dumpValues(super.dump(), vt, beta);
    }

    int getDumpType() {
        return 'f';
    }

    final int hs = 16;

    /**
     * [SP_AGA_03_13] group 0: the threshold voltage right of the end point ("Show Vt"); group 1:
     * the pin letters G, S, D (and B), transient: placed only while highlighted
     */
    @Override
    public void layoutTexts(TextLayout out, boolean highlighted) {
        Font f = unitsFont();
        if ((flags & FLAG_SHOWVT) != 0)
            layoutCentered(out, "" + (vt * pnp), geom().getX2() + 2, geom().getY2(), false, f, 0);
        if (highlighted) {
            int dx = getDx();
            int dy = getDy();
            // make fiddly adjustments to pin label locations depending on orientation
            int dsx = sign(dx);
            int dsyn = dy == 0 ? 0 : 1;
            out.add(new TextPlacement("G", f, gate[1].x - (dx < 0 ? -2 : 12), gate[1].y + ((dy > 0) ? -5 : 12)).group(1).transientText(true));
            out.add(new TextPlacement(pnp == -1 ? "D" : "S", f, src[0].x - 3 + 9 * (dsx - dsyn * pnp), src[0].y + 4).group(1).transientText(true));
            out.add(new TextPlacement(pnp == -1 ? "S" : "D", f, drn[0].x - 3 + 9 * (dsx - dsyn * pnp), drn[0].y + 4).group(1).transientText(true));
            if (hasBodyTerminal())
                out.add(new TextPlacement("B", f, body[0].x - 3 + 9 * (dsx - dsyn * pnp), body[0].y + 4).group(1).transientText(true));
        }
    }

    public void draw(Graphics g) {
        // pick up global flags changes
        if ((flags & FLAGS_GLOBAL) != globalFlags)
            setPoints();

        setBbox(geom().getPoint1(), geom().getPoint2(), hs);

        // draw source/drain terminals
        setVoltageColor(g, getNodeVoltage(1));
        drawThickLine(g, src[0], src[1]);
        setVoltageColor(g, getNodeVoltage(2));
        drawThickLine(g, drn[0], drn[1]);

        // draw line connecting source and drain
        int segments = 6;
        int i;
        setPowerColor(g, true);
        boolean power = displaySettings().showPower();
        double segf = 1. / segments;
        boolean enhancement = vt > 0 && showBulk();
        for (i = 0; i != segments; i++) {
            if ((i == 1 || i == 4) && enhancement)
                continue;
            double v = getNodeVoltage(1) + (getNodeVoltage(2) - getNodeVoltage(1)) * i / segments;
            if (!power)
                setVoltageColor(g, v);
            interpPoint(src[1], drn[1], ps1, i * segf);
            interpPoint(src[1], drn[1], ps2, (i + 1) * segf);
            drawThickLine(g, ps1, ps2);
        }

        // draw little extensions of that line
        if (!power)
            setVoltageColor(g, getNodeVoltage(1));
        drawThickLine(g, src[1], src[2]);
        if (!power)
            setVoltageColor(g, getNodeVoltage(2));
        drawThickLine(g, drn[1], drn[2]);

        // draw bulk connection
        if (showBulk()) {
            setVoltageColor(g, getNodeVoltage(bodyTerminal));
            if (!hasBodyTerminal())
                drawThickLine(g, pnp == -1 ? drn[0] : src[0], body[0]);
            drawThickLine(g, body[0], body[1]);
        }

        // draw arrow
        if (!drawDigital()) {
            setVoltageColor(g, getNodeVoltage(bodyTerminal));
            g.fillPolygon(arrowPoly);
        }
        if (power) {
            g.setColor(neutralColor());
        }

        // draw gate
        setVoltageColor(g, getNodeVoltage(0));
        drawThickLine(g, geom().getPoint1(), gate[1]);
        drawThickLine(g, gate[0], gate[2]);
        if (drawDigital() && pnp == -1)
            drawThickCircle(g, pcircle.x, pcircle.y, pcircler);

        PaintingTextLayout t = paintTexts(g);
        if ((flags & FLAG_SHOWVT) != 0) {
            g.setColor(foregroundColor());
            t.paint(0);
        }
        curcount = updateDotCount(-ids, curcount);
        drawDots(g, src[0], src[1], curcount);
        drawDots(g, src[1], drn[1], curcount);
        drawDots(g, drn[1], drn[0], curcount);

        if (showBulk()) {
            curcount_body1 = updateDotCount(diodeCurrent1, curcount_body1);
            curcount_body2 = updateDotCount(diodeCurrent2, curcount_body2);
            drawDots(g, src[0], body[0], -curcount_body1);
            drawDots(g, body[0], drn[0], curcount_body2);
        }

        // label pins when highlighted
        if (needsHighlight() || circuitEditor().dragElm == this) {
            g.setColor(foregroundColor());
            t.paint(1);
        }

        drawPosts(g);
    }

    // post 0 = gate, 1 = source for NPN, 2 = drain for NPN, 3 = body (if present)
    // for PNP, 1 is drain, 2 is source
    public Point getPost(int n) {
        return (n == 0) ? geom().getPoint1() : (n == 1) ? src[0] : (n == 2) ? drn[0] : body[0];
    }

    /** @return the post of the drain: 2 for n-channel, 1 for p-channel (see getPost) */
    int drainPost() {
        return pnp == -1 ? 1 : 2;
    }

    /** @return the post of the source: 1 for n-channel, 2 for p-channel (see getPost) */
    int sourcePost() {
        return pnp == -1 ? 2 : 1;
    }

    /**
     * [SP_AGA_DEC_08] The element's reported current is the drain current: the current into the
     * drain terminal (channel plus the body or gate-drain diode at the drain), positive into the
     * drain whatever the polarity, so negative for a conducting p-channel device (as a PNP's
     * collector current). {@code ids} is the channel current from post 2 to post 1 (Ids of an
     * n-channel, Isd of a p-channel device); the scope keeps plotting it (getScopeValue).
     */
    @Override
    public double getCurrent() {
        return -getCurrentIntoNode(drainPost());
    }

    public double getPower() {
        return ids * (getNodeVoltage(2) - getNodeVoltage(1))
                - diodeCurrent1 * (getNodeVoltage(1) - getNodeVoltage(bodyTerminal))
                - diodeCurrent2 * (getNodeVoltage(2) - getNodeVoltage(bodyTerminal));
    }

    public int getPostCount() {
        return hasBodyTerminal() ? 4 : 3;
    }

    int pcircler;

    // points for source and drain (these are swapped on PNP mosfets)
    Point src[], drn[];

    // points for gate, body, and the little circle on PNP mosfets
    Point gate[], body[], pcircle;
    Polygon arrowPoly;

    public void setPoints() {
        super.setPoints();
        double dn = getDn();
        int dsign = getDsign();

        // these two flags apply to all mosfets
        flags &= ~FLAGS_GLOBAL;
        flags |= globalFlags;

        // find the coordinates of the various points we need to draw
        // the MOSFET.
        int hs2 = hs * dsign;
        if ((flags & FLAG_FLIP) != 0)
            hs2 = -hs2;
        src = newPointArray(3);
        drn = newPointArray(3);
        interpPoint2(geom().getPoint1(), geom().getPoint2(), src[0], drn[0], 1, -hs2);
        interpPoint2(geom().getPoint1(), geom().getPoint2(), src[1], drn[1], 1 - 22 / dn, -hs2);
        interpPoint2(geom().getPoint1(), geom().getPoint2(), src[2], drn[2], 1 - 22 / dn, -hs2 * 4 / 3);

        gate = newPointArray(3);
        interpPoint2(geom().getPoint1(), geom().getPoint2(), gate[0], gate[2], 1 - 28 / dn, hs2 / 2); // was 1-20/dn
        interpPoint(gate[0], gate[2], gate[1], .5);

        if (showBulk()) {
            body = newPointArray(2);
            interpPoint(src[0], drn[0], body[0], .5);
            interpPoint(src[1], drn[1], body[1], .5);
        }

        if (!drawDigital()) {
            if (pnp == 1) {
                if (!showBulk())
                    arrowPoly = calcArrow(src[1], src[0], 10, 4);
                else
                    arrowPoly = calcArrow(body[0], body[1], 12, 5);
            } else {
                if (!showBulk())
                    arrowPoly = calcArrow(drn[0], drn[1], 12, 5);
                else
                    arrowPoly = calcArrow(body[1], body[0], 12, 5);
            }
        } else if (pnp == -1) {
            interpPoint(geom().getPoint1(), geom().getPoint2(), gate[1], 1 - 36 / dn);
            int dist = (dsign < 0) ? 32 : 31;
            pcircle = interpPoint(geom().getPoint1(), geom().getPoint2(), 1 - dist / dn);
            pcircler = 3;
        }
    }

    /**
     * Hook for MOSFETs to allow element-specific derived geometry tweaks if
     * required.
     * Prefer adjusting derived values here instead of mutating fields in
     * `setPoints()`.
     */
    @Override
    protected void adjustDerivedGeometry(ElmGeometry geom) {
        // No-op for now — placeholder for future transistor/mosfet visual/layout
        // tweaks.
    }

    double lastv1, lastv2;
    double ids;
    int mode = 0;
    double gm = 0;

    public void stamp() {
        CircuitSimulator simulator = simulator();
        simulator.stampNonLinear(getNode(1));
        simulator.stampNonLinear(getNode(2));

        if (hasBodyTerminal())
            bodyTerminal = 3;
        else
            bodyTerminal = (pnp == -1) ? 2 : 1;

        if (doBodyDiode()) {
            if (pnp == -1) {
                // pnp: diodes conduct when S or D are higher than body
                diodeB1.stamp(getNode(1), getNode(bodyTerminal));
                diodeB2.stamp(getNode(2), getNode(bodyTerminal));
            } else {
                // npn: diodes conduct when body is higher than S or D
                diodeB1.stamp(getNode(bodyTerminal), getNode(1));
                diodeB2.stamp(getNode(bodyTerminal), getNode(2));
            }
        }
    }

    boolean nonConvergence(double last, double now) {
        double diff = Math.abs(last - now);

        // high beta MOSFETs are more sensitive to small differences, so we are more
        // strict about convergence testing
        if (beta > 1)
            diff *= 100;

        // difference of less than 10mV is fine
        if (diff < .01)
            return false;
        // larger differences are fine if value is large
        if (simulator().subIterations > 10 && diff < Math.abs(now) * .001)
            return false;
        // if we're having trouble converging, get more lenient
        if (simulator().subIterations > 100 && diff < .01 + (simulator().subIterations - 100) * .0001)
            return false;
        return true;
    }

    public void stepFinished() {
        calculate(true);

        // fix current if body is connected to source or drain
        if (bodyTerminal == 1)
            diodeCurrent1 = -diodeCurrent2;
        if (bodyTerminal == 2)
            diodeCurrent2 = -diodeCurrent1;
    }

    public void doStep() {
        calculate(false);
    }

    double lastv0;

    // this is called in doStep to stamp the matrix, and also called in
    // stepFinished() to calculate the current
    void calculate(boolean finished) {
        double vs[] = new double[3];
        vs[0] = getNodeVoltage(0);
        vs[1] = getNodeVoltage(1);
        vs[2] = getNodeVoltage(2);
        if (!finished) {
            double maxDelta = (simulator().getConvergencePanicLevel() > 0) ? 5.0 : 0.5;
            // limit voltage changes per iteration
            if (vs[1] > lastv1 + maxDelta)
                vs[1] = lastv1 + maxDelta;
            if (vs[1] < lastv1 - maxDelta)
                vs[1] = lastv1 - maxDelta;
            if (vs[2] > lastv2 + maxDelta)
                vs[2] = lastv2 + maxDelta;
            if (vs[2] < lastv2 - maxDelta)
                vs[2] = lastv2 - maxDelta;
        }

        int source = 1;
        int drain = 2;

        // if source voltage > drain (for NPN), swap source and drain
        // (opposite for PNP)
        if (pnp * vs[1] > pnp * vs[2]) {
            source = 2;
            drain = 1;
        }
        int gate = 0;
        double vgs = vs[gate] - vs[source];
        double vds = vs[drain] - vs[source];
        if (!finished
                && (nonConvergence(lastv1, vs[1]) || nonConvergence(lastv2, vs[2]) || nonConvergence(lastv0, vs[0])))
            simulator().converged = false;
        lastv0 = vs[0];
        lastv1 = vs[1];
        lastv2 = vs[2];
        double realvgs = vgs;
        double realvds = vds;
        vgs *= pnp;
        vds *= pnp;
        ids = 0;
        gm = 0;
        double Gds = 0;
        // Use simulator-provided gmin in recovery mode to help avoid singularities/non-convergence.
        double gmin = Math.max(1e-8, simulator().getExtraConvergenceGmin());
        if (vgs < vt) {
            // should be all zero, but that causes a singular matrix,
            // so instead we treat it as a large resistor
            Gds = gmin;
            ids = vds * Gds;
            mode = 0;
        } else if (vds < vgs - vt) {
            // linear
            ids = beta * ((vgs - vt) * vds - vds * vds * .5);
            gm = beta * vds;
            Gds = beta * (vgs - vds - vt);
            mode = 1;
        } else {
            // saturation; Gds = 0
            gm = beta * (vgs - vt);
            // use very small Gds to avoid nonconvergence
            Gds = gmin;
            ids = .5 * beta * (vgs - vt) * (vgs - vt) + (vds - (vgs - vt)) * Gds;
            mode = 2;
        }

        if (doBodyDiode()) {
            diodeB1.doStep(pnp * (getNodeVoltage(bodyTerminal) - getNodeVoltage(1)));
            diodeCurrent1 = diodeB1.calculateCurrent(pnp * (getNodeVoltage(bodyTerminal) - getNodeVoltage(1))) * pnp;
            diodeB2.doStep(pnp * (getNodeVoltage(bodyTerminal) - getNodeVoltage(2)));
            diodeCurrent2 = diodeB2.calculateCurrent(pnp * (getNodeVoltage(bodyTerminal) - getNodeVoltage(2))) * pnp;
        } else
            diodeCurrent1 = diodeCurrent2 = 0;

        double ids0 = ids;

        // flip ids if we swapped source and drain above
        if (source == 2 && pnp == 1 ||
                source == 1 && pnp == -1)
            ids = -ids;

        if (finished)
            return;

        double rs = -pnp * ids0 + Gds * realvds + gm * realvgs;
        CircuitSimulator simulator = simulator();
        simulator.stampMatrix(getNode(drain), getNode(drain), Gds);
        simulator.stampMatrix(getNode(drain), getNode(source), -Gds - gm);
        simulator.stampMatrix(getNode(drain), getNode(gate), gm);

        simulator.stampMatrix(getNode(source), getNode(drain), -Gds);
        simulator.stampMatrix(getNode(source), getNode(source), Gds + gm);
        simulator.stampMatrix(getNode(source), getNode(gate), -gm);

        simulator.stampRightSide(getNode(drain), rs);
        simulator.stampRightSide(getNode(source), -rs);
    }

    void getFetInfo(String arr[], String n) {
        arr[0] = Locale.LS(((pnp == -1) ? "p-" : "n-") + n);
        arr[0] += " (Vt=" + getVoltageText(pnp * vt);
        arr[0] += ", \u03b2=" + beta + ")";
        arr[1] = ((pnp == 1) ? "Ids = " : "Isd = ") + getCurrentText(ids);
        arr[2] = "Vgs = " + getVoltageText(getNodeVoltage(0) - getNodeVoltage(pnp == -1 ? 2 : 1));
        arr[3] = ((pnp == 1) ? "Vds = " : "Vsd = ") + getVoltageText(getNodeVoltage(2) - getNodeVoltage(1));
        arr[4] = Locale.LS((mode == 0) ? "off" : (mode == 1) ? "linear" : "saturation");
        arr[5] = "gm = " + getUnitText(gm, "A/V");
        arr[6] = "P = " + getUnitText(getPower(), "W");
        if (showBulk())
            arr[7] = "Ib = " + getUnitText(bodyTerminal == 1 ? -diodeCurrent1
                    : bodyTerminal == 2 ? diodeCurrent2 : -pnp * (diodeCurrent1 + diodeCurrent2), "A");
    }

    public void getInfo(String arr[]) {
        getFetInfo(arr, "MOSFET");
    }

    @Override
    public String getScopeText(int v) {
        return Locale.LS(((pnp == -1) ? "p-" : "n-") + "MOSFET");
    }

    public boolean canViewInScope() {
        return true;
    }

    /** [SP_AGA_DEC_08] drain minus source (Vds) for both polarities */
    @Override
    double getVoltageDiff() {
        return getNodeVoltage(drainPost()) - getNodeVoltage(sourcePost());
    }

    // the scope keeps its pre-SP_AGA_DEC_08 values: channel current ids (Ids / Isd, as the info
    // panel) and, for every other value but power, post 2 minus post 1 (Vds / Vsd), as the base
    // class did with the old getVoltageDiff
    @Override
    public double getScopeValue(int x) {
        if (x == Scope.VAL_CURRENT) {
            return ids;
        }
        if (x == Scope.VAL_POWER) {
            return getPower();
        }
        return getNodeVoltage(2) - getNodeVoltage(1);
    }

    public boolean getConnection(int n1, int n2) {
        return !(n1 == 0 || n2 == 0);
    }

    public EditInfo getEditInfo(int n) {
        if (n == 0)
            return new EditInfo("Threshold Voltage", pnp * vt, .01, 5);
        if (n == 1)
            return new EditInfo(EditInfo.makeLink("mosfet-beta.html", "Beta"), beta, .01, 5);
        if (n == 2) {
            EditInfo ei = new EditInfo("", 0, -1, -1);
            ei.checkbox = new Checkbox("Show Bulk", showBulk());
            return ei;
        }
        if (n == 3) {
            EditInfo ei = new EditInfo("", 0, -1, -1);
            ei.checkbox = new Checkbox("Swap D/S", (flags & FLAG_FLIP) != 0);
            return ei;
        }
        if (n == 4 && !showBulk()) {
            EditInfo ei = new EditInfo("", 0, -1, -1);
            ei.checkbox = new Checkbox("Digital Symbol", drawDigital());
            return ei;
        }
        if (n == 4 && showBulk()) {
            EditInfo ei = new EditInfo("", 0, -1, -1);
            ei.checkbox = new Checkbox("Simulate Body Diode", (flags & FLAG_BODY_DIODE) != 0);
            return ei;
        }
        if (n == 5 && doBodyDiode()) {
            EditInfo ei = new EditInfo("", 0, -1, -1);
            ei.checkbox = new Checkbox("Body Terminal", (flags & FLAG_BODY_TERMINAL) != 0);
            return ei;
        }

        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 0)
            vt = pnp * ei.value;
        if (n == 1 && ei.value > 0)
            beta = lastBeta = ei.value;
        if (n == 2) {
            globalFlags = (!ei.checkbox.getState()) ? (globalFlags | FLAG_HIDE_BULK)
                    : (globalFlags & ~(FLAG_HIDE_BULK | FLAG_DIGITAL));
            // setPoints();
            ei.newDialog = true;
        }
        if (n == 3) {
            flags = (ei.checkbox.getState()) ? (flags | FLAG_FLIP) : (flags & ~FLAG_FLIP);
            // setPoints();
        }
        if (n == 4 && !showBulk()) {
            globalFlags = (ei.checkbox.getState()) ? (globalFlags | FLAG_DIGITAL) : (globalFlags & ~FLAG_DIGITAL);
            // setPoints();
        }
        if (n == 4 && showBulk()) {
            flags = ei.changeFlag(flags, FLAG_BODY_DIODE);
            ei.newDialog = true;
        }
        if (n == 5) {
            flags = ei.changeFlag(flags, FLAG_BODY_TERMINAL);
        }

        // lots of different cases where the body terminal might have gotten
        // removed/added so just do this all the time
        allocNodes();
        setPoints();
    }

    public double getCurrentIntoNode(int n) {
        if (n == 0)
            return 0;
        if (n == 3)
            return -diodeCurrent1 - diodeCurrent2;
        if (n == 1)
            return ids + diodeCurrent1;
        return -ids + diodeCurrent2;
    }

    public void flipX(int c2, int count) {
        if (getX() == getX2())
            flags ^= FLAG_FLIP;
        super.flipX(c2, count);
    }

    public void flipY(int c2, int count) {
        if (getY() == getY2())
            flags ^= FLAG_FLIP;
        super.flipY(c2, count);
    }

    public void flipXY(int xmy, int count) {
        flags ^= FLAG_FLIP;
        super.flipXY(xmy, count);
    }

    @Override
    public String getJsonTypeName() {
        return pnp == 1 ? "NMOS" : "PMOS";
    }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        // vt as stored by the text format (polarity-independent; negative = depletion device).
        // abs() lost the sign of depletion devices; pnp scaling on import flipped PMOS.
        props.put("threshold_voltage", getJsonUnitText(vt, "V"));
        props.put("beta", beta);
        if (drawDigital()) {
            props.put("digital", true);
        }
        if (doBodyDiode()) {
            props.put("body_diode", true);
        }
        if (hasBodyTerminal()) {
            props.put("body_terminal", true);
        }
        return props;
    }

    // [SP_AGA_03_03] keys getJsonProperties() writes only when they differ from these defaults
    @Override
    public java.util.Map<String, Object> getJsonConditionalProperties() {
        java.util.Map<String, Object> props = super.getJsonConditionalProperties();
        props.put("digital", false);
        props.put("body_diode", false);
        props.put("body_terminal", false);
        return props;
    }

    // [SP_AGA_DEC_08] post 1 is the source of an n-channel and the drain of a p-channel device
    // (the drawn S/D labels, the body tie of stamp(), the source of getFetInfo's Vgs)
    @Override
    public String[] getJsonPinNames() {
        String p1 = pnp == -1 ? "drain" : "source";
        String p2 = pnp == -1 ? "source" : "drain";
        if (hasBodyTerminal()) {
            return new String[] { "gate", p1, p2, "body" };
        }
        return new String[] { "gate", p1, p2 };
    }

    @Override
    public Point getJsonEndPoint() {
        // For MOSFET, point2 is not at any pin - it's a reference point
        // for calculating source and drain positions
        return new Point(getX2(), getY2());
    }

    @Override
    public void applyJsonProperties(java.util.Map<String, Object> properties) {
        super.applyJsonProperties(properties);
        vt = getJsonDouble(properties, "threshold_voltage", vt);
        beta = getJsonDouble(properties, "beta", getDefaultBeta());
        // a present false clears the bit; FLAG_DIGITAL is overridden by the session-wide MOSFET
        // display setting on the next setPoints (globalFlags)
        applyJsonFlagProperty(properties, "digital", FLAG_DIGITAL);
        applyJsonFlagProperty(properties, "body_diode", FLAG_BODY_DIODE);
        applyJsonFlagProperty(properties, "body_terminal", FLAG_BODY_TERMINAL);
    }

    @Override
    public java.util.Map<String, Object> getJsonState() {
        java.util.Map<String, Object> state = super.getJsonState();
        if (state == null) {
            state = new java.util.LinkedHashMap<>();
        }
        // Export drain-source current
        if (Double.isFinite(ids)) {
            state.put("ids", ids);
        }
        return state;
    }

    @Override
    public void applyJsonState(java.util.Map<String, Object> state) {
        super.applyJsonState(state);
        if (state != null) {
            ids = getJsonDouble(state, "ids", 0);
        }
    }
}
