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

import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.ui.Button;
import com.lushprojects.circuitjs1.client.Checkbox;
import com.lushprojects.circuitjs1.client.Choice;
import com.lushprojects.circuitjs1.client.CircuitMath;
import com.lushprojects.circuitjs1.client.CustomLogicModel;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.Polygon;
import com.lushprojects.circuitjs1.client.Scope;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.TransistorModel;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;
import com.lushprojects.circuitjs1.client.util.Locale;

import java.util.Vector;

public class TransistorElm extends CircuitElm {
    // node 0 = base
    // node 1 = collector
    // node 2 = emitter
    public int pnp;
    double beta;
    //	double fgain, inv_fgain;
    double gmin;
    String modelName;
    TransistorModel model;
    static String lastModelName = "default";
    final int FLAG_FLIP = 1;
    final int FLAG_CIRCLE = 2;
    final int FLAGS_GLOBAL = FLAG_CIRCLE;
    static int globalFlags;
    int badIters;

    TransistorElm(CircuitDocument circuitDocument, int xx, int yy, boolean pnpflag) {
        super(circuitDocument, xx, yy);
        pnp = (pnpflag) ? -1 : 1;
        beta = 100;
        modelName = lastModelName;
        setup();
    }

    public TransistorElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f, StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f);
        pnp = parseInt(st.nextToken());
        beta = 100;

        // Text-format circuits may contain saved operating-point hints (Vbc, Vbe) and optional model name.
        // These values are not required for correctness and can be stale across versions.
        // Parse them defensively and avoid forcing node voltages from them.
        if (st.hasMoreTokens()) {
            // dump() writes (Vbase - Vcollector) then (Vbase - Vemitter)
            lastvbc = parseDouble(st.nextToken());
        }
        if (st.hasMoreTokens()) {
            lastvbe = parseDouble(st.nextToken());
        }
        if (st.hasMoreTokens()) {
            try {
                beta = parseDouble(st.nextToken());
            } catch (Exception e) {
                beta = 100;
            }
        }
        if (st.hasMoreTokens()) {
            try {
                modelName = CustomLogicModel.unescape(st.nextToken());
            } catch (Exception e) {
                modelName = "default";
            }
        } else {
            modelName = "default";
        }
        globalFlags = flags & (FLAGS_GLOBAL);
        setup();
    }

    void setup() {
        model = TransistorModel.getModelWithNameOrCopy(modelName, model);
        modelName = model.name;   // in case we couldn't find that model
        vcrit = vt * Math.log(vt / (Math.sqrt(2) * model.satCur));
        noDiagonal = true;
    }

    public boolean nonLinear() {
        return true;
    }

    public void reset() {
        setNodeVoltageDirect(0, 0);
        setNodeVoltageDirect(1, 0);
        setNodeVoltageDirect(2, 0);
        lastvbc = lastvbe = curcount_c = curcount_e = curcount_b = 0;
        badIters = 0;
    }

    int getDumpType() {
        return 't';
    }

    public String dump() {
        return dumpValues(super.dump(), pnp, (getNodeVoltage(0) - getNodeVoltage(1)), (getNodeVoltage(0) - getNodeVoltage(2)), beta, escape(modelName));
    }

    public void updateModels() {
        setup();
    }

    public String dumpModel() {
        if (model.builtIn || model.dumped)
            return null;
        return model.dump();
    }


    double ic, ie, ib, curcount_c, curcount_e, curcount_b;

    Polygon rectPoly, arrowPoly;
    Point circleCenter;

    boolean hasCircle() {
        return (globalFlags & FLAG_CIRCLE) != 0;
    }

    public void draw(Graphics g) {
        // pick up global flags changes
        if ((flags & FLAGS_GLOBAL) != globalFlags)
            setPoints();

        ElmGeometry geom = geom();
        Point point1 = geom.getPoint1();
        Point point2 = geom.getPoint2();
        setBbox(point1, point2, 16);
        if (hasCircle()) {
            g.setColor(neutralColor());
            drawThickCircle(g, circleCenter.x, circleCenter.y, 20);
        }
        setPowerColor(g, true);
        // draw collector
        setVoltageColor(g, getNodeVoltage(1));
        drawThickLine(g, coll[0], coll[1]);
        // draw emitter
        setVoltageColor(g, getNodeVoltage(2));
        drawThickLine(g, emit[0], emit[1]);
        // draw arrow
        g.setColor(elementColor());
        g.fillPolygon(arrowPoly);
        // draw base
        setVoltageColor(g, getNodeVoltage(0));
        if (displaySettings().showPower()) {
            g.setColor(neutralColor());
        }
        drawThickLine(g, point1, base);
        // draw dots
        curcount_b = updateDotCount(-ib, curcount_b);
        drawDots(g, base, point1, curcount_b);
        curcount_c = updateDotCount(-ic, curcount_c);
        drawDots(g, coll[1], coll[0], curcount_c);
        curcount_e = updateDotCount(-ie, curcount_e);
        drawDots(g, emit[1], emit[0], curcount_e);
        // draw base rectangle
        setVoltageColor(g, getNodeVoltage(0));
        setPowerColor(g, true);
        g.fillPolygon(rectPoly);

        int dy = getDy();
        int dx = getDx();
        if ((needsHighlight() || circuitEditor().dragElm == this) && dy == 0) {
            g.setColor(foregroundColor());
// IES
//		g.setFont(unitsFont);
            int ds = sign(dx);
            g.drawString("B", base.x - 10 * ds, base.y - 5);
            g.drawString("C", coll[0].x - 3 + 9 * ds, coll[0].y + 4); // x+6 if ds=1, -12 if -1
            g.drawString("E", emit[0].x - 3 + 9 * ds, emit[0].y + 4);
        }
        drawPosts(g);
    }

    public Point getPost(int n) {
        return (n == 0) ? geom().getPoint1() : (n == 1) ? coll[0] : emit[0];
    }

    public int getPostCount() {
        return 3;
    }

    public double getPower() {
        return (getNodeVoltage(0) - getNodeVoltage(2)) * ib + (getNodeVoltage(1) - getNodeVoltage(2)) * ic;
    }

    Point rect[], coll[], emit[], base;

    public void setPoints() {
        // these flags apply to all transistors
        flags &= ~FLAGS_GLOBAL;
        flags |= globalFlags;

        super.setPoints();
        ElmGeometry geom = geom();
        Point point1 = geom.getPoint1();
        Point point2 = geom.getPoint2();
        int hs = 16;
        int dsignLocal = getDsign();
        if ((flags & FLAG_FLIP) != 0)
            dsignLocal = -dsignLocal;
        int hs2 = hs * dsignLocal * pnp;
        double dn = getDn();
        // calc collector, emitter posts
        coll = newPointArray(2);
        emit = newPointArray(2);
        interpPoint2(point1, point2, coll[0], emit[0], 1, hs2);
        // calc rectangle edges
        rect = newPointArray(4);
        interpPoint2(point1, point2, rect[0], rect[1], 1 - 16 / dn, hs);
        interpPoint2(point1, point2, rect[2], rect[3], 1 - 13 / dn, hs);
        // calc points where collector/emitter leads contact rectangle
        interpPoint2(point1, point2, coll[1], emit[1], 1 - 13 / dn, 6 * dsignLocal * pnp);
        // calc point where base lead contacts rectangle
        base = new Point();
        interpPoint(point1, point2, base, 1 - 16 / dn);
        // rectangle
        rectPoly = createPolygon(rect[0], rect[2], rect[3], rect[1]);

        // arrow
        if (pnp == 1)
            arrowPoly = calcArrow(emit[1], emit[0], 8, 4);
        else {
            // For PNP, arrow points from emitter post toward the base rectangle
            // Use same position as emit[1] for proper alignment
            arrowPoly = calcArrow(emit[0], emit[1], 8, 4);
        }

        if (circleCenter == null)
            circleCenter = new Point();
        interpPoint(base, point2, circleCenter, .5);
    }

    /**
     * Hook to allow transistor-specific derived geometry tweaks in a centralized place.
     * If transistors need to adjust `dn`/`dsign` for visual/layout reasons, do it here
     * rather than directly in `setPoints()`; this aids future encapsulation into `ElmGeometry`.
     */
    @Override
    protected void adjustDerivedGeometry(ElmGeometry geom) {
        // Ensure a minimum length for visual stability (avoids negative interpolants in setPoints).
        geom.recomputeDerivedWithMinDn(16);
    }

    static final double leakage = 1e-13; // 1e-6;
    // Electron thermal voltage at SPICE's default temperature of 27 C (300.15 K):
    static final double vt = 0.025865;
    double vcrit;
    double lastvbc, lastvbe;

    double limitStep(double vnew, double vold) {
        double arg;

        if (vnew > vcrit && Math.abs(vnew - vold) > (vt + vt)) {
            if (vold > 0) {
                arg = 1 + (vnew - vold) / vt;
                if (arg > 0) {
                    vnew = vold + vt * Math.log(arg);
                } else {
                    vnew = vcrit;
                }
            } else {
                vnew = vt * Math.log(vnew / vt);
            }
            simulator().converged = false;
            //System.out.println(vnew + " " + oo + " " + vold);
        }
        return (vnew);
    }

    public void stamp() {
        simulator().stampNonLinear(getNode(0));
        simulator().stampNonLinear(getNode(1));
        simulator().stampNonLinear(getNode(2));
    }

    public void doStep() {
        double vbc = pnp * (getNodeVoltage(0) - getNodeVoltage(1)); // typically negative
        double vbe = pnp * (getNodeVoltage(0) - getNodeVoltage(2)); // typically positive
        if (!CircuitMath.isConverged(vbc, lastvbc) || !CircuitMath.isConverged(vbe, lastvbe)) {
            System.out.println("Convergence failed: vbc=" + vbc + ", lastvbc=" + lastvbc + ", vbe=" + vbe + ", lastvbe=" + lastvbe);
            simulator().converged = false;
        }

        // To prevent a possible singular matrix, put a tiny conductance in parallel
        // with each P-N junction.
//	    gmin = leakage * 0.01;
        gmin = 1e-12;

        if (simulator().subIterations > 100 && badIters < 5) {
            // if we have trouble converging, put a conductance in parallel with all P-N junctions.
            // Gradually increase the conductance value for each iteration.
            gmin = Math.exp(-9 * Math.log(10) * (1 - simulator().subIterations / 300.));
            if (gmin > .1)
                gmin = .1;
        }

        //System.out.print("T " + vbc + " " + vbe + "\n");
        vbc = limitStep(vbc, lastvbc);
        vbe = limitStep(vbe, lastvbe);
        lastvbc = vbc;
        lastvbe = vbe;

        /*
         *   dc model paramters (from Spice 3f5, bjtload.c)
         */
        double csat = model.satCur;
        double oik = model.invRollOffF;
        double c2 = model.BEleakCur;
        double vte = model.leakBEemissionCoeff * vt;
        double oikr = model.invRollOffR;
        double c4 = model.BCleakCur;
        double vtc = model.leakBCemissionCoeff * vt;

//          double rbpr=model.minBaseResist;
//          double rbpi=model.baseResist-rbpr;
//          double xjrb=model.baseCurrentHalfResist;

        double vtn = vt * model.emissionCoeffF;
        double evbe, cbe, gbe, cben, gben, evben, evbc, cbc, gbc, cbcn, gbcn, evbcn;
        double qb, dqbdve, dqbdvc, q2, sqarg, arg;
        if (vbe > -5 * vtn) {
            evbe = Math.exp(vbe / vtn);
            cbe = csat * (evbe - 1) + gmin * vbe;
            gbe = csat * evbe / vtn + gmin;
            if (c2 == 0) {
                cben = 0;
                gben = 0;
            } else {
                evben = Math.exp(vbe / vte);
                cben = c2 * (evben - 1);
                gben = c2 * evben / vte;
            }
        } else {
            gbe = -csat / vbe + gmin;
            cbe = gbe * vbe;
            gben = -c2 / vbe;
            cben = gben * vbe;
        }
        vtn = vt * model.emissionCoeffR;
        if (vbc > -5 * vtn) {
            evbc = Math.exp(vbc / vtn);
            cbc = csat * (evbc - 1) + gmin * vbc;
            gbc = csat * evbc / vtn + gmin;
            if (c4 == 0) {
                cbcn = 0;
                gbcn = 0;
            } else {
                evbcn = Math.exp(vbc / vtc);
                cbcn = c4 * (evbcn - 1);
                gbcn = c4 * evbcn / vtc;
            }
        } else {
            gbc = -csat / vbc + gmin;
            cbc = gbc * vbc;
            gbcn = -c4 / vbc;
            cbcn = gbcn * vbc;
        }
        /*
         *   determine base charge terms
         */
        double q1 = 1 / (1 - model.invEarlyVoltF * vbc - model.invEarlyVoltR * vbe);
        if (oik == 0 && oikr == 0) {
            qb = q1;
            dqbdve = q1 * qb * model.invEarlyVoltR;
            dqbdvc = q1 * qb * model.invEarlyVoltF;
        } else {
            q2 = oik * cbe + oikr * cbc;
            arg = Math.max(0, 1 + 4 * q2);
            sqarg = 1;
            if (arg != 0) sqarg = Math.sqrt(arg);
            qb = q1 * (1 + sqarg) / 2;
            dqbdve = q1 * (qb * model.invEarlyVoltR + oik * gbe / sqarg);
            dqbdvc = q1 * (qb * model.invEarlyVoltF + oikr * gbc / sqarg);
        }

        // Numerical safety: protect against stale parameters or extreme operating points
        // causing NaNs/Infs which would corrupt the circuit matrix.
        if (Double.isNaN(qb) || Double.isInfinite(qb) || Math.abs(qb) < 1e-30) {
            qb = 1;
        }
        if (Double.isNaN(dqbdve) || Double.isInfinite(dqbdve)) {
            dqbdve = 0;
        }
        if (Double.isNaN(dqbdvc) || Double.isInfinite(dqbdvc)) {
            dqbdvc = 0;
        }

        double cc = 0;
        double cex = cbe;
        double gex = gbe;
        /*
         *   determine dc incremental conductances
         */
        cc = cc + (cex - cbc) / qb - cbc / model.betaR - cbcn;
        double cb = cbe / beta + cben + cbc / model.betaR + cbcn;

        // get currents
        ic = pnp * cc;
        ib = pnp * cb;
        ie = pnp * (-cc - cb);
	    
/*            double gx=rbpr+rbpi/qb;   // base resistance commented out for now
            if(xjrb != 0) {
                double arg1=Math.max(cb/xjrb,1e-9);
                double arg2=(-1+Math.sqrt(1+14.59025*arg1))/2.4317/Math.sqrt(arg1);
                arg1=Math.tan(arg2);
                gx=rbpr+3*rbpi*(arg1-arg2)/arg2/arg1/arg1;
            }
            if(gx != 0) gx=1/gx;*/
        double gpi = gbe / beta + gben;
        double gmu = gbc / model.betaR + gbcn;
        double go = (gbc + (cex - cbc) * dqbdvc / qb) / qb;
        double gm = (gex - (cex - cbc) * dqbdve / qb) / qb - go;

        if (Double.isNaN(gpi) || Double.isInfinite(gpi)) {
            gpi = 0;
        }
        if (Double.isNaN(gmu) || Double.isInfinite(gmu)) {
            gmu = 0;
        }
        if (Double.isNaN(go) || Double.isInfinite(go)) {
            go = 0;
        }
        if (Double.isNaN(gm) || Double.isInfinite(gm)) {
            gm = 0;
        }

        double ceqbe = pnp * (cc + cb - vbe * (gm + go + gpi) + vbc * go);
        double ceqbc = pnp * (-cc + vbe * (gm + go) - vbc * (gmu + go));

        if (Double.isNaN(ceqbe) || Double.isInfinite(ceqbe)) {
            ceqbe = 0;
        }
        if (Double.isNaN(ceqbc) || Double.isInfinite(ceqbc)) {
            ceqbc = 0;
        }

        if (Double.isInfinite(ib) || Double.isNaN(ic))
            simulator().stop("infinite transistor current", this);

        // stamp matrix.
        // Node 0 is the base, node 1 the collector, node 2 the emitter.
        simulator().stampMatrix(getNode(1), getNode(1), gmu + go);
        simulator().stampMatrix(getNode(1), getNode(0), -gmu + gm);
        simulator().stampMatrix(getNode(1), getNode(2), -gm - go);
        simulator().stampMatrix(getNode(0), getNode(0), gpi + gmu);
        simulator().stampMatrix(getNode(0), getNode(2), -gpi);
        simulator().stampMatrix(getNode(0), getNode(1), -gmu);
        simulator().stampMatrix(getNode(2), getNode(0), -gpi - gm);
        simulator().stampMatrix(getNode(2), getNode(1), -go);
        simulator().stampMatrix(getNode(2), getNode(2), gpi + gm + go);

        /*
         *  load current excitation vector (right side)
         */
        simulator().stampRightSide(getNode(0), -ceqbe - ceqbc);
        simulator().stampRightSide(getNode(1), ceqbc);
        simulator().stampRightSide(getNode(2), ceqbe);

    }

    @Override
    public String getScopeText(int x) {
        String t = "";
        switch (x) {
            case Scope.VAL_IB:
                t = "Ib";
                break;
            case Scope.VAL_IC:
                t = "Ic";
                break;
            case Scope.VAL_IE:
                t = "Ie";
                break;
            case Scope.VAL_VBE:
                t = "Vbe";
                break;
            case Scope.VAL_VBC:
                t = "Vbc";
                break;
            case Scope.VAL_VCE:
                t = "Vce";
                break;
            case Scope.VAL_POWER:
                t = "P";
                break;
        }
        return Locale.LS("transistor") + ", " + t;
    }

    public void getInfo(String arr[]) {
        arr[0] = Locale.LS("transistor") + " (" + ((pnp == -1) ? "PNP" : "NPN") + ", " + model.name + ", \u03b2=" + showFormat(beta) + ")";
        double vbc = getNodeVoltage(0) - getNodeVoltage(1);
        double vbe = getNodeVoltage(0) - getNodeVoltage(2);
        double vce = getNodeVoltage(1) - getNodeVoltage(2);
        if (vbc * pnp > .2)
            arr[1] = vbe * pnp > .2 ? "saturation" : "reverse active";
        else
            arr[1] = vbe * pnp > .2 ? "fwd active" : "cutoff";
        arr[1] = Locale.LS(arr[1]);
        arr[2] = "Ic = " + getCurrentText(ic);
        arr[3] = "Ib = " + getCurrentText(ib);
        arr[4] = "Vbe = " + getVoltageText(vbe);
        arr[5] = "Vbc = " + getVoltageText(vbc);
        arr[6] = "Vce = " + getVoltageText(vce);
        arr[7] = "P = " + getUnitText(getPower(), "W");
    }

    public double getScopeValue(int x) {
        switch (x) {
            case Scope.VAL_IB:
                return ib;
            case Scope.VAL_IC:
                return ic;
            case Scope.VAL_IE:
                return ie;
            case Scope.VAL_VBE:
                return getNodeVoltage(0) - getNodeVoltage(2);
            case Scope.VAL_VBC:
                return getNodeVoltage(0) - getNodeVoltage(1);
            case Scope.VAL_VCE:
                return getNodeVoltage(1) - getNodeVoltage(2);
            case Scope.VAL_POWER:
                return getPower();
        }
        return 0;
    }

    public int getScopeUnits(int x) {
        switch (x) {
            case Scope.VAL_IB:
            case Scope.VAL_IC:
            case Scope.VAL_IE:
                return Scope.UNITS_A;
            case Scope.VAL_POWER:
                return Scope.UNITS_W;
            default:
                return Scope.UNITS_V;
        }
    }

    Vector<TransistorModel> models;

    public EditInfo getEditInfo(int n) {
        if (n == 0)
            return new EditInfo("Beta/hFE", beta, 10, 1000).
                    setDimensionless();
        if (n == 1) {
            EditInfo ei = new EditInfo("", 0, -1, -1);
            ei.checkbox = new Checkbox("Swap E/C", (flags & FLAG_FLIP) != 0);
            return ei;
        }
        if (n == 2) {
            EditInfo ei = EditInfo.createCheckbox("Draw Circle", hasCircle());
            return ei;
        }
        if (n == 3) {
            EditInfo ei = new EditInfo("Model", 0, -1, -1);
            models = TransistorModel.getModelList();
            ei.choice = new Choice();
            int i;
            for (i = 0; i != models.size(); i++) {
                TransistorModel dm = models.get(i);
                ei.choice.add(dm.getDescription());
                if (dm == model)
                    ei.choice.select(i);
            }
            return ei;
        }
        if (n == 4) {
            EditInfo ei = new EditInfo("", 0, -1, -1);
            ei.button = new Button(Locale.LS("Create New Model"));
            return ei;
        }
        if (n == 5) {
            if (model.readOnly)
                return null;
            EditInfo ei = new EditInfo("", 0, -1, -1);
            ei.button = new Button(Locale.LS("Edit Model"));
            return ei;
        }
        return null;
    }

    public void newModelCreated(TransistorModel tm) {
        model = tm;
        modelName = model.name;
        setup();
    }


    public void setEditValue(int n, EditInfo ei) {
        if (n == 0) {
            beta = ei.value;
            setup();
        }
        if (n == 1) {
            if (ei.checkbox.getState())
                flags |= FLAG_FLIP;
            else
                flags &= ~FLAG_FLIP;
            setPoints();
        }
        if (n == 2) {
            globalFlags = ei.changeFlag(globalFlags, FLAG_CIRCLE);
            return;
        }
        if (n == 3) {
            model = models.get(ei.choice.getSelectedIndex());
            modelName = model.name;
            setup();
            ei.newDialog = true;
            return;
        }
        if (n == 4) {
            TransistorModel newModel = new TransistorModel(model);
            circuitDocument.getDialogManager().showEditTransistorModelDialog(newModel, this);
            return;
        }
        if (n == 5) {
            if (model.readOnly) {
                // probably never reached
                Window.alert(Locale.LS("This model cannot be modified.  Change the model name to allow customization."));
                return;
            }
            circuitDocument.getDialogManager().showEditTransistorModelDialog(model, null);
            return;
        }
    }

    void setBeta(double b) {
        beta = b;
        setup();
    }

    public void stepFinished() {
        // stop for huge currents that make simulator act weird
        if (Math.abs(ic) > 1e12 || Math.abs(ib) > 1e12)
            simulator().stop("max current exceeded", this);

        // if we needed to add a conductance to all junctions, this was a bad iteration.
        // If we have 5 of those in a row, give up
        if (simulator().subIterations > 100)
            badIters++;
        else
            badIters = 0;
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

    void setFlipped(boolean flip) {
        if (((flags & FLAG_FLIP) != 0) != flip)
            flags ^= FLAG_FLIP;
    }

    public boolean canViewInScope() {
        return true;
    }

    public double getCurrentIntoNode(int n) {
        if (n == 0)
            return -ib;
        if (n == 1)
            return -ic;
        return -ie;
    }

    @Override
    public String getJsonTypeName() {
        return pnp == 1 ? "TransistorNPN" : "TransistorPNP";
    }

    @Override
    public String[] getJsonPinNames() {
        return new String[] {"base", "collector", "emitter"};
    }

    @Override
    public Point getJsonEndPoint() {
        // For transistor, point2 is not at any pin - it's a reference point
        // for calculating collector and emitter positions
        return new Point(getX2(), getY2());
    }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        if (!"default".equals(modelName)) {
            props.put("model", modelName);
        }
        if (beta != 100) {
            props.put("beta", beta);
        }
        return props;
    }

    @Override
    public void applyJsonProperties(java.util.Map<String, Object> properties) {
        super.applyJsonProperties(properties);
        beta = getJsonDouble(properties, "beta", 100);
        String model = getJsonString(properties, "model", "default");
        if (model != null && !model.isEmpty()) {
            modelName = model;
            setup();
        }
    }

    @Override
    public java.util.Map<String, Object> getJsonState() {
        java.util.Map<String, Object> state = super.getJsonState();
        if (state == null) {
            state = new java.util.LinkedHashMap<>();
        }
        // Export transistor currents
        if (Double.isFinite(ib)) {
            state.put("ib", ib);
        }
        if (Double.isFinite(ic)) {
            state.put("ic", ic);
        }
        if (Double.isFinite(ie)) {
            state.put("ie", ie);
        }
        return state.isEmpty() ? null : state;
    }

    @Override
    public void applyJsonState(java.util.Map<String, Object> state) {
        super.applyJsonState(state);
        if (state != null) {
            ib = getJsonDouble(state, "ib", 0);
            ic = getJsonDouble(state, "ic", 0);
            ie = getJsonDouble(state, "ie", 0);
        }
    }
}
