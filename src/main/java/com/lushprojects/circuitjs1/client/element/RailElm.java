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


import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.TextMeasurer;
import com.lushprojects.circuitjs1.client.element.waveform.*;

public class RailElm extends VoltageElm {
    private final Point railLead = new Point();

    public RailElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy, Waveform.WF_DC);

    }

    RailElm(CircuitDocument circuitDocument, int xx, int yy, int wf) {
        super(circuitDocument, xx, yy, wf);
    }

    /** Creates a rail with the given waveform (JSON import of waveform rails without their own class). */
    public static RailElm createRail(CircuitDocument circuitDocument, int x, int y, int wf) {
        return new RailElm(circuitDocument, x, y, wf);
    }

    public RailElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
                   StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f, st);
    }


    public static final int FLAG_CLOCK = 1;

    int getDumpType() {
        return 'R';
    }

    public int getPostCount() {
        return 1;
    }

    public void setPoints() {
        super.setPoints();
        ElmGeometry geom = geom();
        Point point1 = geom.getPoint1();
        Point point2 = geom.getPoint2();
        double dn = getDn();
        double w = (waveformInstance != null && waveformInstance.hasCircle()) ? CIRCLE_SIZE : 0;
        interpPoint(point1, point2, railLead, leadFraction(w, dn));
    }

    String getRailText() {
        return null;
    }

    /**
     * The end of the rail's lead: before the text of {@link #getRailText} (half its width in the
     * units font), before the waveform circle, or the end point.
     */
    private void railLeadOf(TextMeasurer m, Point out) {
        ElmGeometry geom = geom();
        String rt = getRailText();
        double w;
        if (rt != null) {
            // [SP_AGA_03_13] measured in the font the rail label is drawn in
            w = m.measureWidth(rt, unitsFont()) / 2;
        } else {
            w = (waveformInstance != null && waveformInstance.hasCircle()) ? CIRCLE_SIZE : 0;
        }
        double dn = getDn();
        if (w > dn * .8)
            w = dn * .8;
        interpPoint(geom.getPoint1(), geom.getPoint2(), out, 1 - w / dn);
    }

    /**
     * [SP_AGA_03_13] The label shown at the end of the rail instead of a waveform circle (a DC
     * rail's voltage, "CLK", a file name), or null.
     */
    String railLabel() {
        return waveformInstance == null ? null : waveformInstance.getRailLabel(this);
    }

    /**
     * [SP_AGA_03_13] the rail label beyond the lead in the units font, or else the frequency
     * beside the waveform circle (when values are shown)
     */
    @Override
    public void layoutTexts(TextLayout out, boolean highlighted) {
        String label = railLabel();
        if (label != null) {
            Point lead = new Point();
            railLeadOf(out.measurer(), lead);
            layoutLabel(out, label, geom().getPoint1(), lead, unitsFont(), 0);
        } else {
            layoutWaveformValue(out, 0);
        }
    }

    public void draw(Graphics g) {
        ElmGeometry geom = geom();
        Point point1 = geom.getPoint1();
        Point point2 = geom.getPoint2();
        PaintingTextLayout t = paintTexts(g);
        railLeadOf(t.measurer(), railLead);
        setBbox(point1, point2, CIRCLE_SIZE);
        setVoltageColor(g, getNodeVoltage(0));
        drawThickLine(g, point1, railLead);
        drawRail(g, t);
        drawPosts(g);
        curcount = updateDotCount(-current, curcount);
        if (circuitEditor().dragElm != this)
            drawDots(g, point1, railLead, curcount);
    }

    /** Draws the label (group 0), or the waveform circle with its frequency (group 0). */
    void drawRail(Graphics g, PaintingTextLayout t) {
        if (railLabel() != null) {
            g.setColor(needsHighlight() ? selectColor() : foregroundColor());
            setPowerColor(g, false);
            t.paint(0);
        } else {
            drawWaveform(g, geom().getPoint2(), t, 0);
        }
    }

    double getVoltageDiff() {
        return getNodeVoltage(0);
    }

    public void stamp() {
        waveformInstance.stampRail(this);
    }

    public void doStep() {
        if (!waveformInstance.isDC())
            simulator().updateVoltageSource(0, getNode(0), voltSource, getVoltage());
    }

    public boolean hasGroundConnection(int n1) {
        return true;
    }

    public int getShortcut() {
        return 'V';
    }

//    void drawHandles(Graphics g, Color c) {
//    	g.setColor(c);
//		g.fillRect(x-3, y-3, 7, 7);
//    }

    @Override
    public String getJsonTypeName() {
        return waveformInstance.getJsonRailTypeName();
    }

    @Override
    public String[] getJsonPinNames() {
        return new String[] { "output" };
    }

    // single post: the two-post source aliases of VoltageElm do not apply
    @Override
    public java.util.Map<String, Integer> getJsonPinAliases() {
        return java.util.Collections.emptyMap();
    }
}
