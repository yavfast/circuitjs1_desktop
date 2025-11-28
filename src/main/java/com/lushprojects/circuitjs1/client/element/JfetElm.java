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

import com.lushprojects.circuitjs1.client.Diode;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.Polygon;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;
import com.lushprojects.circuitjs1.client.util.Locale;

public class JfetElm extends MosfetElm {
    // Gate-Source diode (p-n junction)
    Diode diodeGS;
    // Gate-Drain diode (p-n junction)
    Diode diodeGD;
    double gateCurrentGS, gateCurrentGD;

    JfetElm(int xx, int yy, boolean pnpflag) {
        super(xx, yy, pnpflag);
        noDiagonal = true;
        diodeGS = new Diode();
        diodeGS.setupForDefaultModel();
        diodeGD = new Diode();
        diodeGD.setupForDefaultModel();
    }

    public JfetElm(int xa, int ya, int xb, int yb, int f,
                   StringTokenizer st) {
        super(xa, ya, xb, yb, f, st);
        noDiagonal = true;
        diodeGS = new Diode();
        diodeGS.setupForDefaultModel();
        diodeGD = new Diode();
        diodeGD.setupForDefaultModel();
    }

    public void reset() {
        super.reset();
        diodeGS.reset();
        diodeGD.reset();
    }

    Polygon gatePoly;
    Polygon arrowPoly;
    Point gatePt;
    double curcountgs, curcountgd, curcounts, curcountd;

    public void draw(Graphics g) {
        setBbox(point1, point2, hs);
        setVoltageColor(g, volts[1]);
        drawThickLine(g, src[0], src[1]);
        drawThickLine(g, src[1], src[2]);
        setVoltageColor(g, volts[2]);
        drawThickLine(g, drn[0], drn[1]);
        drawThickLine(g, drn[1], drn[2]);
        setVoltageColor(g, volts[0]);
        drawThickLine(g, point1, gatePt);
        g.fillPolygon(arrowPoly);
        setPowerColor(g, true);
        g.fillPolygon(gatePoly);
        
        // Total gate current is sum of both diode currents
        double totalGateCurrent = gateCurrentGS + gateCurrentGD;
        curcountd = updateDotCount(-ids, curcountd);
        curcountgs = updateDotCount(gateCurrentGS, curcountgs);
        curcountgd = updateDotCount(gateCurrentGD, curcountgd);
        // Source current: channel current + gate-source diode current
        curcounts = updateDotCount(-totalGateCurrent - ids, curcounts);
        if (curcountd != 0 || curcounts != 0) {
            drawDots(g, src[0], src[1], curcounts);
            drawDots(g, src[1], src[2], addCurCount(curcounts, 8));
            drawDots(g, drn[0], drn[1], -curcountd);
            drawDots(g, drn[1], drn[2], -addCurCount(curcountd, 8));
            drawDots(g, point1, gatePt, curcountgs + curcountgd);
        }
        drawPosts(g);
    }

    public double getCurrentIntoNode(int n) {
        double totalGateCurrent = gateCurrentGS + gateCurrentGD;
        if (n == 0)
            return -totalGateCurrent;
        if (n == 1)
            return gateCurrentGS + ids;  // Source: channel current + GS diode
        return -ids + gateCurrentGD;     // Drain: channel current + GD diode
    }

    public void setPoints() {
        super.setPoints();

        // find the coordinates of the various points we need to draw
        // the JFET.
        int hs2 = hs * dsign;
        src = newPointArray(3);
        drn = newPointArray(3);
        interpPoint2(point1, point2, src[0], drn[0], 1, -hs2);
        interpPoint2(point1, point2, src[1], drn[1], 1, -hs2 / 2);
        interpPoint2(point1, point2, src[2], drn[2], 1 - 10 / dn, -hs2 / 2);

        gatePt = interpPoint(point1, point2, 1 - 14 / dn);

        Point ra[] = newPointArray(4);
        interpPoint2(point1, point2, ra[0], ra[1], 1 - 13 / dn, hs);
        interpPoint2(point1, point2, ra[2], ra[3], 1 - 10 / dn, hs);
        gatePoly = createPolygon(ra[0], ra[1], ra[3], ra[2]);
        if (pnp == -1) {
            Point x = interpPoint(gatePt, point1, 18 / dn);
            arrowPoly = calcArrow(gatePt, x, 8, 3);
        } else
            arrowPoly = calcArrow(point1, gatePt, 8, 3);
    }

    public void stamp() {
        super.stamp();
        // JFET has two gate p-n junctions: Gate-Source and Gate-Drain
        // For n-JFET (pnp=1): diodes conduct when gate is positive relative to S or D
        //   - Diode GS: anode=gate(0), cathode=source(1)
        //   - Diode GD: anode=gate(0), cathode=drain(2)
        // For p-JFET (pnp=-1): diodes conduct when gate is negative relative to S or D
        //   - Diode GS: anode=source(1), cathode=gate(0)
        //   - Diode GD: anode=drain(2), cathode=gate(0)
        if (pnp == 1) {
            // n-JFET: gate positive conducts
            diodeGS.stamp(nodes[0], nodes[1]);
            diodeGD.stamp(nodes[0], nodes[2]);
        } else {
            // p-JFET: gate negative conducts (source/drain positive)
            diodeGS.stamp(nodes[1], nodes[0]);
            diodeGD.stamp(nodes[2], nodes[0]);
        }
    }

    public void doStep() {
        super.doStep();
        // Calculate gate-source and gate-drain voltages
        // For n-JFET: positive Vgs means forward bias
        // For p-JFET: negative Vgs means forward bias (multiply by pnp to normalize)
        double vgs = volts[0] - volts[1];  // Gate - Source voltage
        double vgd = volts[0] - volts[2];  // Gate - Drain voltage
        
        // Diode models expect positive voltage for forward bias
        // n-JFET (pnp=1): forward bias when Vg > Vs, so use vgs directly
        // p-JFET (pnp=-1): forward bias when Vg < Vs, so negate: -(vgs) = Vs - Vg
        diodeGS.doStep(pnp * vgs);
        diodeGD.doStep(pnp * vgd);
    }

    void calculateCurrent() {
        double vgs = volts[0] - volts[1];
        double vgd = volts[0] - volts[2];
        // Calculate diode currents (positive = current into gate)
        // For n-JFET: current flows into gate when forward biased
        // For p-JFET: current flows out of gate when forward biased, so multiply by pnp
        gateCurrentGS = pnp * diodeGS.calculateCurrent(pnp * vgs);
        gateCurrentGD = pnp * diodeGD.calculateCurrent(pnp * vgd);
    }

    boolean showBulk() {
        return false;
    }

    int getDumpType() {
        return 'j';
    }

    // these values are taken from Hayes+Horowitz p155
    double getDefaultThreshold() {
        return -4;
    }

    double getDefaultBeta() {
        return .00125;
    }

    double getBackwardCompatibilityBeta() {
        return getDefaultBeta();
    }

    public void getInfo(String arr[]) {
        getFetInfo(arr, "JFET");
    }

    public EditInfo getEditInfo(int n) {
        if (n < 2)
            return super.getEditInfo(n);
        return null;
    }

    public boolean getConnection(int n1, int n2) {
        return true;
    }

    @Override
    public String getScopeText(int v) {
        return Locale.LS(((pnp == -1) ? "p-" : "n-") + "JFET");
    }

    @Override
    public void setCircuitDocument(com.lushprojects.circuitjs1.client.CircuitDocument circuitDocument) {
        super.setCircuitDocument(circuitDocument);
        diodeGS.setSimulator(circuitDocument.simulator);
        diodeGD.setSimulator(circuitDocument.simulator);
    }

    @Override
    public String getJsonTypeName() {
        return pnp == 1 ? "NJFET" : "PJFET";
    }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put("threshold_voltage", getUnitText(Math.abs(vt), "V"));
        props.put("beta", beta);
        return props;
    }

    @Override
    public String[] getJsonPinNames() {
        return new String[] { "gate", "source", "drain" };
    }

    @Override
    public Point getJsonEndPoint() {
        // For JFET, point2 is not at any pin - it's a reference point
        // for calculating source and drain positions
        return new Point(x2, y2);
    }

    @Override
    public void applyJsonProperties(java.util.Map<String, Object> props) {
        // Note: Don't call super here as JFET has different property handling than MOSFET
        // Parse threshold voltage
        vt = com.lushprojects.circuitjs1.client.io.json.UnitParser.parse(
            getJsonString(props, "threshold_voltage", "4 V"));
        // Make negative for JFET
        vt = -Math.abs(vt);
        
        // Parse beta
        beta = getJsonDouble(props, "beta", getDefaultBeta());
    }
}
