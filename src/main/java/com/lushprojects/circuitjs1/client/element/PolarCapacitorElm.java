package com.lushprojects.circuitjs1.client.element;

import com.lushprojects.circuitjs1.client.CircuitDocument;

import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public class PolarCapacitorElm extends CapacitorElm {
    double maxNegativeVoltage;

    public PolarCapacitorElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy);
        maxNegativeVoltage = 1;
    }

    public PolarCapacitorElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
            StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f, st);
        maxNegativeVoltage = parseDouble(st.nextToken());
    }

    int getDumpType() {
        return 209;
    }

    public String dump() {
        return dumpValues(super.dump(), maxNegativeVoltage);
    }

    Point plusPoint;

    public void setPoints() {
        super.setPoints();
        double dn = getDn();
        int dsign = getDsign();
        double f = (dn / 2 - 4) / dn;
        int i;
        platePoints = newPointArray(14);
        int maxI = platePoints.length - 1;
        double midI = maxI / 2.;
        for (i = 0; i <= maxI; i++) {
            double q = (i - midI) * .9 / midI;
            platePoints[i] = interpPoint(plate2[0], plate2[1], i / (double) maxI, 5 * (1 - Math.sqrt(1 - q * q)));
        }
        plusPoint = interpPoint(geom().getPoint1(), geom().getPoint2(), f - 8 / dn, -10 * dsign);
        if (getY2() > getY())
            plusPoint.y += 4;
        if (getY() > getY2())
            plusPoint.y += 3;
    }

    public void draw(Graphics g) {
        super.draw(g);
        g.setColor(foregroundColor());
        g.setFont(unitsFont());
        int w = (int) g.measureWidth("+");
        g.drawString("+", plusPoint.x - w / 2, plusPoint.y);
    }

    public void getInfo(String[] arr) {
        super.getInfo(arr);
        arr[0] = "capacitor (polarized)";
    }

    public EditInfo getEditInfo(int n) {
        if (n == 4) {
            return new EditInfo("Max Reverse Voltage", maxNegativeVoltage, 0, 0);
        }
        return super.getEditInfo(n);
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 4 && ei.value >= 0) {
            maxNegativeVoltage = ei.value;
        }
        super.setEditValue(n, ei);
    }

    public void stepFinished() {
        if (getVoltageDiff() < 0 && getVoltageDiff() < -maxNegativeVoltage) {
            simulator().stop("capacitor exceeded max reverse voltage", this);
        }
        super.stepFinished();
    }

    public int getShortcut() {
        return 'C';
    }

    @Override
    public String getJsonTypeName() {
        return "PolarCapacitor";
    }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        props.put("max_reverse_voltage", getUnitText(maxNegativeVoltage, "V"));
        return props;
    }

    @Override
    public String[] getJsonPinNames() {
        return new String[] { "positive", "negative" };
    }
}
