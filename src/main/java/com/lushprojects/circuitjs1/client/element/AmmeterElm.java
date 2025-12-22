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
    
    AmmeterElm by Bill Collis
    
*/

package com.lushprojects.circuitjs1.client.element;

import com.lushprojects.circuitjs1.client.CircuitDocument;

import com.lushprojects.circuitjs1.client.Choice;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.Polygon;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;
import com.lushprojects.circuitjs1.client.util.Locale;

public class AmmeterElm extends CircuitElm {

    int meter;
    int scale;
    final int AM_VOL = 0;
    final int AM_RMS = 1;
    int zerocount = 0;
    double rmsI = 0, total, count;
    double maxI = 0, lastMaxI;
    double minI = 0, lastMinI;
    double selectedValue = 0;

    double currents[];
    boolean increasingI = true, decreasingI = true;

    public AmmeterElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy);
        flags = FLAG_SHOWCURRENT | FLAG_CIRCLE;
        scale = SCALE_AUTO;
    }

    public AmmeterElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
                      StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f);
        scale = SCALE_AUTO;
        meter = Integer.parseInt(st.nextToken());
        try {
            scale = Integer.parseInt(st.nextToken());
        } catch (Exception e) {
        }
    }

    public String dump() {
        return dumpValues(super.dump(), meter, scale);
    }

    public String getMeter() {
        switch (meter) {
            case AM_VOL:
                return "I";
            case AM_RMS:
                return "Irms";
        }
        return "";
    }

    public void setPoints() {
        super.setPoints();
        mid = interpPoint(point1, point2, 0.6);
        center = interpPoint(point1, point2, 0.5);
        arrowPoly = calcArrow(point1, mid, 14, 7);
    }

    Point center;
    Point mid;
    static final int FLAG_SHOWCURRENT = 1;
    static final int FLAG_CIRCLE = 2;  // Add this line

    public void stepFinished() {
        count++;//how many counts are in a cycle    
        total += current * current; //sum of squares
        if (current > maxI && increasingI) {
            maxI = current;
            increasingI = true;
            decreasingI = false;
        }
        if (current < maxI && increasingI) {//change of direction I now going down - at start of waveform
            lastMaxI = maxI; //capture last maximum
            //capture time between
            minI = current; //track minimum value
            increasingI = false;
            decreasingI = true;

            //rms data
            total = total / count;
            rmsI = Math.sqrt(total);
            if (Double.isNaN(rmsI))
                rmsI = 0;
            count = 0;
            total = 0;

        }
        if (current < minI && decreasingI) { //I going down, track minimum value
            minI = current;
            increasingI = false;
            decreasingI = true;
        }

        if (current > minI && decreasingI) { //change of direction I now going up
            lastMinI = minI; //capture last minimum

            maxI = current;
            increasingI = true;
            decreasingI = false;

            //rms data
            total = total / count;
            rmsI = Math.sqrt(total);
            if (Double.isNaN(rmsI))
                rmsI = 0;
            count = 0;
            total = 0;


        }
        //need to zero the rms value if it stays at 0 for a while
        if (current == 0) {
            zerocount++;
            if (zerocount > 5) {
                total = 0;
                rmsI = 0;
                maxI = 0;
                minI = 0;
            }
        } else {
            zerocount = 0;
        }
        switch (meter) {
            case AM_VOL:
                selectedValue = current;
                break;
            case AM_RMS:
                selectedValue = rmsI;
                break;
        }
    }

    Polygon arrowPoly;

    public void draw(Graphics graphics) {
        super.draw(graphics);//BC required for highlighting
        setVoltageColor(graphics, getNodeVoltage(0));
        double width = 4;
        if (!drawAsCircle()) {
            drawThickLine(graphics, point1, point2);
            graphics.fillPolygon(arrowPoly);
        } else {
            graphics.setColor(needsHighlight() ? selectColor() : elementColor());
            drawThickCircle(graphics, center.x, center.y, circleSize);
            drawCenteredText(graphics, "A", center.x, center.y, true);

            calcLeads(circleSize * 2);
            setVoltageColor(graphics, getNodeVoltage(0));
            drawThickLine(graphics, point1, lead1);
            drawThickLine(graphics, lead2, point2);

            graphics.setColor(foregroundColor());
            graphics.setFont(unitsFont());
            double len = circleSize * 2;
            Point plusPoint = interpPoint(point1, point2, (dn / 2 - len / 2 - 4) / dn, -10 * dsign);
            if (y2 > y)
                plusPoint.y += 4;
            if (y > y2)
                plusPoint.y += 3;
            int w = (int) graphics.measureWidth("+");
            graphics.drawString("+", plusPoint.x - w / 2, plusPoint.y);
            width = circleSize;
        }

        doDots(graphics);
        setBbox(point1, point2, width);
        String s = "A";
        switch (meter) {
            case AM_VOL:
                s = getUnitTextWithScale(getCurrent(), "A", scale);
                break;
            case AM_RMS:
                s = getUnitTextWithScale(rmsI, "A(rms)", scale);
                break;
        }

        drawValues(graphics, s, width);
        drawPosts(graphics);
    }

    final int circleSize = 12;

    int getDumpType() {
        return 370;
    }

    public void stamp() {
        simulator().stampVoltageSource(getNode(0), getNode(1), voltSource, 0);
    }

    boolean mustShowCurrent() {
        return (flags & FLAG_SHOWCURRENT) != 0;
    }

    public int getVoltageSourceCount() {
        return 1;
    }

    public void getInfo(String arr[]) {
        arr[0] = "Ammeter";
        switch (meter) {
            case AM_VOL:
                arr[1] = "I = " + getUnitText(current, "A");
                break;
            case AM_RMS:
                arr[1] = "Irms = " + getUnitText(rmsI, "A");
                break;
        }
    }

    public double getPower() {
        return 0;
    }

    double getVoltageDiff() {
        return getNodeVoltage(0);
    }

    // do not optimize out, even though isWireEquivalent() is true
    // (because we need current calculated every timestep)    
    public boolean isWireEquivalent() {
        return true;
    }

    boolean drawAsCircle() {
        return (flags & FLAG_CIRCLE) != 0;
    }

    public EditInfo getEditInfo(int n) {
        if (n == 0) {
            EditInfo ei = new EditInfo("Value", selectedValue, -1, -1);
            ei.choice = new Choice();
            ei.choice.add("Current");
            ei.choice.add("RMS Current");
            ei.choice.select(meter);
            return ei;
        }
        if (n == 1) {
            EditInfo ei = new EditInfo("Scale", 0);
            ei.choice = new Choice();
            ei.choice.add("Auto");
            ei.choice.add("A");
            ei.choice.add("mA");
            ei.choice.add(Locale.muString + "A");
            ei.choice.select(scale);
            return ei;
        }
        if (n == 2) {
            return EditInfo.createCheckbox("Circular Symbol", drawAsCircle());
        }
        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 0)
            meter = ei.choice.getSelectedIndex();
        if (n == 1)
            scale = ei.choice.getSelectedIndex();
        if (n == 2)
            flags = ei.changeFlag(flags, FLAG_CIRCLE);
    }

    @Override
    public String getJsonTypeName() {
        return "Ammeter";
    }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        props.put("mode", meter == AM_VOL ? "current" : "rms");
        props.put("scale", scale == SCALE_AUTO ? "auto" : scale == 1 ? "A" : scale == 2 ? "mA" : "uA");
        props.put("circular_symbol", drawAsCircle());
        return props;
    }

    @Override
    public String[] getJsonPinNames() {
        return new String[] {"in+", "in-"};
    }
}
