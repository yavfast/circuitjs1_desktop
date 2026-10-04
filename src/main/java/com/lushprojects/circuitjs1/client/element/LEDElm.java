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

import com.lushprojects.circuitjs1.client.Color;
import com.lushprojects.circuitjs1.client.DiodeModel;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;
import com.lushprojects.circuitjs1.client.util.Locale;

public class LEDElm extends DiodeElm {
    double colorR, colorG, colorB, maxBrightnessCurrent;
    static String lastLEDModelName = "default-led";

    public LEDElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy);
        modelName = lastLEDModelName;
        setup();
        maxBrightnessCurrent = .01;
        colorR = 1;
        colorG = colorB = 0;
    }

    public LEDElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
            StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f, st);
        if ((f & (FLAG_MODEL | FLAG_FWDROP)) == 0) {
            final double fwdrop = 2.1024259;
            model = DiodeModel.getModelWithParameters(fwdrop, 0);
            modelName = model.name;
            // CirSim.console("model name wparams = " + modelName);
            setup();
        }
        colorR = parseDouble(st.nextToken());
        colorG = parseDouble(st.nextToken());
        colorB = parseDouble(st.nextToken());
        maxBrightnessCurrent = parseDouble(st.nextToken(), .01);
    }

    @Override
    public String getIdPrefix() {
        return "LED";
    }

    int getDumpType() {
        return 162;
    }

    public String dump() {
        return dumpValues(super.dump(), colorR, colorG, colorB, maxBrightnessCurrent);
    }

    // Emission arrows of the IEC 60617 / GOST 2.730 LED symbol, beside the diode body
    Point arrowTail[], arrowHead[];

    public void setPoints() {
        super.setPoints();
        Point lead1 = geom().getLead1();
        Point lead2 = geom().getLead2();
        arrowTail = newPointArray(2);
        arrowHead = newPointArray(2);
        for (int i = 0; i != 2; i++) {
            double f = .2 + .45 * i;
            interpPoint(lead1, lead2, arrowTail[i], f, -(hs + 3));
            interpPoint(lead1, lead2, arrowHead[i], f + .45, -(hs + 12));
        }
    }

    // Brightness 0..255 from the current relative to maxBrightnessCurrent (logarithmic)
    double brightness() {
        double w = current / maxBrightnessCurrent;
        if (w > 0)
            w = 255 * (1 + .2 * Math.log(w));
        if (w > 255)
            w = 255;
        if (w < 0)
            w = 0;
        return w;
    }

    // The standard LED symbol: diode triangle and cathode bar (polarity visible) plus two emission
    // arrows; a lit LED fills the outlined triangle with its colour scaled by brightness.
    public void draw(Graphics g) {
        drawDiode(g);
        double w = brightness();
        boolean lit = w > 32 && !needsHighlight();
        Color litColor = new Color((int) (colorR * w), (int) (colorG * w), (int) (colorB * w));
        if (lit) {
            g.setColor(litColor);
            g.fillPolygon(poly);
            // outline keeps a dim or pale lit triangle visible on any background
            setVoltageColor(g, getNodeVoltage(0));
            setPowerColor(g, true);
            drawThickPolygon(g, poly);
            setVoltageColor(g, getNodeVoltage(1));
            setPowerColor(g, true);
            drawThickLine(g, cathode[0], cathode[1]);
        }
        g.setColor(needsHighlight() ? selectColor() : foregroundColor());
        for (int i = 0; i != 2; i++) {
            drawThickLine(g, arrowTail[i], arrowHead[i]);
            g.fillPolygon(calcArrow(arrowTail[i], arrowHead[i], 5, 3));
        }
        adjustBbox(arrowHead[0], arrowHead[1]);
        doDots(g);
        drawPosts(g);
    }

    public void getInfo(String[] arr) {
        super.getInfo(arr);
        if (model.oldStyle)
            arr[0] = "LED";
        else
            arr[0] = Locale.LS("LED") + " (" + modelName + ")";
    }

    public EditInfo getEditInfo(int n) {
        if (n == 0)
            return new EditInfo("Red Value (0-1)", colorR, 0, 1).setDimensionless();
        if (n == 1)
            return new EditInfo("Green Value (0-1)", colorG, 0, 1).setDimensionless();
        if (n == 2)
            return new EditInfo("Blue Value (0-1)", colorB, 0, 1).setDimensionless();
        if (n == 3)
            return new EditInfo("Max Brightness Current (A)", maxBrightnessCurrent, 0, .1);
        return super.getEditInfo(n - 4);
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 0)
            colorR = ei.value;
        if (n == 1)
            colorG = ei.value;
        if (n == 2)
            colorB = ei.value;
        if (n == 3)
            maxBrightnessCurrent = ei.value;
        super.setEditValue(n - 4, ei);
    }

    public int getShortcut() {
        return 'l';
    }

    void setLastModelName(String n) {
        lastLEDModelName = n;
    }

    @Override
    public String getJsonTypeName() {
        return "LED";
    }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        props.put("color_r", colorR);
        props.put("color_g", colorG);
        props.put("color_b", colorB);
        props.put("max_brightness_current", getJsonUnitText(maxBrightnessCurrent, "A"));
        return props;
    }

    @Override
    public void applyJsonProperties(java.util.Map<String, Object> properties) {
        super.applyJsonProperties(properties);
        colorR = getJsonDouble(properties, "color_r", 1.0);
        colorG = getJsonDouble(properties, "color_g", 0);
        colorB = getJsonDouble(properties, "color_b", 0);
        maxBrightnessCurrent = getJsonDouble(properties, "max_brightness_current", .01);
    }
}
