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

import com.google.gwt.canvas.dom.client.Context2d;
import com.lushprojects.circuitjs1.client.Font;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.StringTokenizer;

public class WattmeterElm extends CircuitElm {
    int width;
    int voltSources[];
    double currents[];
    double curcounts[];

    public WattmeterElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy);
        setup();
    }

    public WattmeterElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
            StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f);
        width = Integer.parseInt(st.nextToken());
        setup();
    }

    void setup() {
        voltSources = new int[2];
        currents = new double[2];
        curcounts = new double[2];
    }

    public String dump() {
        return dumpValues(super.dump(), width);
    }

    public int getVoltageSourceCount() {
        return 2;
    }

    public int getDumpType() {
        return 420;
    }

    public int getPostCount() {
        return 4;
    }

    public void drag(int xx, int yy) {
        xx = circuitEditor().snapGrid(xx);
        yy = circuitEditor().snapGrid(yy);
        int w1 = max(circuitEditor().gridSize, abs(yy - getY()));
        int w2 = max(circuitEditor().gridSize, abs(xx - getX()));
        if (w1 > w2) {
            xx = getX();
            width = w2;
        } else {
            yy = getY();
            width = w1;
        }
        setEndpoints(getX(), getY(), xx, yy);
        setPoints();
    }

    Point posts[];
    Point inner[];
    int maxTextLen;
    private Point ptemp1, ptemp2, ptemp3, ptemp4;
    private Point ptempR1, ptempR2, ptempR3, ptempR4;

    public void setPoints() {
        super.setPoints();
        double dn = getDn();

        // This element seems to use raw coord diffs for orientation.
        // We can get them via geom() or getters.
        int dx = getDx();
        int dy = getDy();
        int ds = (dy == 0) ? sign(dx) : -sign(dy);

        // get 2 more terminals
        if (ptemp1 == null)
            ptemp1 = new Point();
        if (ptemp2 == null)
            ptemp2 = new Point();
        interpPoint(geom().getPoint1(), geom().getPoint2(), ptemp1, 0, -width * ds);
        interpPoint(geom().getPoint1(), geom().getPoint2(), ptemp2, 1, -width * ds);

        // get stubs
        int sep = circuitEditor().gridSize;
        if (ptemp3 == null)
            ptemp3 = new Point();
        if (ptemp4 == null)
            ptemp4 = new Point();
        interpPoint(geom().getPoint1(), geom().getPoint2(), ptemp3, sep / dn);
        interpPoint(geom().getPoint1(), geom().getPoint2(), ptemp4, 1 - sep / dn);
        Point p5 = ptemp3;
        Point p6 = ptemp4;
        Point p7 = new Point();
        Point p8 = new Point();
        interpPoint(ptemp1, ptemp2, p7, sep / dn);
        interpPoint(ptemp1, ptemp2, p8, 1 - sep / dn);

        // we number the posts like this because we want the lower-numbered
        // points to be on the bottom, so that if some of them are unconnected
        // (which is often true) then the bottom ones will get automatically
        // attached to ground.
        posts = new Point[] { ptemp1, ptemp2, geom().getPoint1(), geom().getPoint2() };
        inner = new Point[] { p7, p8, p5, p6 };

        // get rectangle
        if (ptempR1 == null)
            ptempR1 = new Point();
        if (ptempR2 == null)
            ptempR2 = new Point();
        if (ptempR3 == null)
            ptempR3 = new Point();
        if (ptempR4 == null)
            ptempR4 = new Point();
        interpPoint(geom().getPoint1(), geom().getPoint2(), ptempR1, sep / dn, ds * sep);
        interpPoint(geom().getPoint1(), geom().getPoint2(), ptempR2, 1 - sep / dn, ds * sep);
        interpPoint(geom().getPoint1(), geom().getPoint2(), ptempR3, sep / dn, -ds * (sep + width));
        interpPoint(geom().getPoint1(), geom().getPoint2(), ptempR4, 1 - sep / dn, -ds * (sep + width));
        rectPointsX = new int[] { ptempR1.x, ptempR2.x, ptempR4.x, ptempR3.x };
        rectPointsY = new int[] { ptempR1.y, ptempR2.y, ptempR4.y, ptempR3.y };

        center = interpPoint(ptempR1, ptempR4, .5);
        maxTextLen = max(abs(ptempR1.x - ptempR4.x) - 5, 5);
    }

    int rectPointsX[], rectPointsY[];
    Point center;

    public Point getPost(int n) {
        return posts[n];
    }

    public void stamp() {
        // zero-valued voltage sources from 0 to 1 and 2 to 3, so we can measure current
        simulator().stampVoltageSource(getNode(0), getNode(1), voltSources[0], 0);
        simulator().stampVoltageSource(getNode(2), getNode(3), voltSources[1], 0);
    }

    public void setVoltageSource(int j, int vs) {
        voltSources[j] = vs;
    }

    public void draw(Graphics g) {
        int i;
        for (i = 0; i != 2; i++)
            curcounts[i] = updateDotCount(currents[i], curcounts[i]);
        double flip = 1;
        for (i = 0; i != 4; i++) {
            setVoltageColor(g, getNodeVoltage(i));
            drawThickLine(g, posts[i], inner[i]);
            drawDots(g, posts[i], inner[i], curcounts[i / 2] * flip);
            flip *= -1;
        }

        g.setColor(needsHighlight() ? selectColor() : elementColor());
        drawThickPolygon(g, rectPointsX, rectPointsY, 4);

        setBbox(posts[0].x, posts[0].y, posts[3].x, posts[3].y);
        drawPosts(g);

        String str = getUnitText(getPower(), "W");
        g.save();
        int fsize = 15;
        int w;
        // adjust font size to fit
        while (true) {
            g.setFont(new Font("SansSerif", 0, fsize));
            w = (int) g.measureWidth(str);
            if (w < maxTextLen)
                break;
            fsize--;
        }
        g.setColor(foregroundColor());
        g.setTextBaseline(Context2d.TextBaseline.MIDDLE);
        g.drawString(str, center.x - w / 2, center.y);
        g.restore();
    }

    public double getPower() {
        return getVoltageDiff() * getCurrent();
    }

    public void setCurrent(int vn, double c) {
        currents[vn == voltSources[0] ? 0 : 1] = c;
    }

    public double getCurrentIntoNode(int n) {
        if (n % 2 == 0)
            return -currents[n / 2];
        else
            return currents[n / 2];
    }

    public boolean getConnection(int n1, int n2) {
        return (n1 / 2) == (n2 / 2);
    }

    public boolean hasGroundConnection(int n1) {
        return false;
    }

    public void getInfo(String arr[]) {
        arr[0] = "wattmeter";
        getBasicInfo(arr);
        arr[3] = "P = " + getUnitText(getPower(), "W");
    }

    public boolean canViewInScope() {
        return true;
    }

    public double getCurrent() {
        return currents[1];
    }

    double getVoltageDiff() {
        return getNodeVoltage(2) - getNodeVoltage(0);
    }

    public boolean canFlipX() {
        return false;
    }

    public boolean canFlipY() {
        return false;
    }

    @Override
    public String getJsonTypeName() {
        return "Wattmeter";
    }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        props.put("width", width);
        return props;
    }

    @Override
    public String[] getJsonPinNames() {
        return new String[] { "I1+", "I1-", "V+", "V-" };
    }
}
