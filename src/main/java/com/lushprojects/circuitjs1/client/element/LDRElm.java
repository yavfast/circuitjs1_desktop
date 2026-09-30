package com.lushprojects.circuitjs1.client.element;

import com.lushprojects.circuitjs1.client.CircuitDocument;

import com.google.gwt.canvas.dom.client.CanvasGradient;
import com.google.gwt.event.dom.client.MouseWheelEvent;
import com.google.gwt.event.dom.client.MouseWheelHandler;
import com.lushprojects.circuitjs1.client.CustomLogicModel;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.Polygon;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;
import com.lushprojects.circuitjs1.client.util.Locale;

/*Bill Collis - June 2015 */

public class LDRElm extends CircuitElm implements HasBuiltInSlider, MouseWheelHandler {
    // Edit item driven by the Sliders dialog (see HasBuiltInSlider).
    static final int EDIT_POSITION = 1;
    // Slider range of the original scrollbar mapping; at 1.0 the resistance would drop to ~9 ohms.
    static final double MIN_POSITION = .0001;
    static final double MAX_POSITION = .9901;

    double position; // of the slider 0.0001 to 0.9901
    double resistance; // based upon slider position
    double minLux, maxLux;
    double lux;

    String sliderText;

    // constructor - when initially created
    public LDRElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy);
        // setup();
        minLux = 0.1; // dark
        maxLux = 10000; // sunlight
        position = .34;

        lux = LuxFromSliderPos();
        resistance = calcResistance(lux);
        sliderText = Locale.LS("Light Brightness");
    }

    // constructor - when read in from file
    public LDRElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
            StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f);
        minLux = 0.1; // dark
        maxLux = 10000; // sunlight
        position = parseDouble(st.nextToken());
        lux = LuxFromSliderPos();
        resistance = calcResistance(lux);
        sliderText = CustomLogicModel.unescape(st.nextToken());
    }

    // void setup() {
    // }

    public int getPostCount() {
        return 2;
    }

    int getDumpType() {
        return 374;
    } // LDR

    // data for file saving - make sure it matches order of items in file input
    // constructor
    public String dump() {
        return dumpValues(super.dump(), position, CustomLogicModel.escape(sliderText));
    }

    public int getBuiltInSliderItem() {
        return EDIT_POSITION;
    }

    public String getBuiltInSliderText() {
        return sliderText;
    }

    Point ps3, ps4;

    // called straight after constructor when txt file is loaded
    public void setPoints() {
        super.setPoints();
        calcLeads(32);
        lux = LuxFromSliderPos();
        resistance = calcResistance(lux);
        ps3 = new Point();
        ps4 = new Point();
    }

    Polygon arrowPoly;

    public void draw(Graphics g) { // used Resistor draw
        // int segments = 16;
        int i;
        // int ox = 0;
        int hs = 6; // width
        double v1 = getNodeVoltage(0);
        double v2 = getNodeVoltage(1);
        setBbox(geom().getPoint1(), geom().getPoint2(), hs); // the two points that are there when the device is being
                                                             // created
        draw2Leads(g); // from point1 to lead1 and lead1 to point2 (lead1&2 are on the body)
        setPowerColor(g, true);
        double len = distance(geom().getLead1(), geom().getLead2());
        g.save();
        g.setLineWidth(3.0);
        g.transform(((double) (geom().getLead2().x - geom().getLead1().x)) / len,
                ((double) (geom().getLead2().y - geom().getLead1().y)) / len,
                -((double) (geom().getLead2().y - geom().getLead1().y)) / len,
                ((double) (geom().getLead2().x - geom().getLead1().x)) / len, geom().getLead1().x, geom().getLead1().y);
        CanvasGradient grad = g.createLinearGradient(0, 0, len, 0);
        grad.addColorStop(0, getVoltageColor(g, v1).getHexValue());
        grad.addColorStop(1.0, getVoltageColor(g, v2).getHexValue());
        g.setStrokeStyle(grad);
        if (!displaySettings().euroResistors()) {
            g.beginPath();
            g.moveTo(0, 0);
            for (i = 0; i < 4; i++) {
                g.lineTo((1 + 4 * i) * len / 16, hs);
                g.lineTo((3 + 4 * i) * len / 16, -hs);
            }
            g.lineTo(len, 0);
            g.stroke();

        } else {
            g.strokeRect(0, -hs, len, 2.0 * hs); // draw the box for the euro resistor
        }

        g.beginPath(); // thermistor symbol lines 0 is in the middle of the left handside of the
                       // resistor box
        // upper arrow
        g.moveTo(-8, 26); // arrow1 start (y,x coordinates from center?)
        g.lineTo(8, 12); // arrow end point
        g.moveTo(2, 12); // arrow 1 head
        g.lineTo(8, 12); // arrow end point
        g.lineTo(8, 18);
        g.moveTo(12, 26); // arrow2 start (y,x coordinates from center?)
        g.lineTo(26, 12); // arrow end point
        g.moveTo(20, 12); // arrow 1 head
        g.lineTo(26, 12); // arrow end point
        g.lineTo(26, 18);

        g.stroke();

        g.restore();
        if (displaySettings().showValues()) {
            lux = LuxFromSliderPos();
            resistance = calcResistance(lux);
            String s = getShortUnitText(resistance, "");
            drawValues(g, s + "\u03A9", hs);
        }
        doDots(g);
        drawPosts(g);
    }

    void calculateCurrent() {
        current = (getNodeVoltage(0) - getNodeVoltage(1)) / resistance;
    }

    public void stamp() {
        lux = LuxFromSliderPos();
        resistance = calcResistance(lux);
        simulator().stampResistor(getNode(0), getNode(1), resistance);
    }

    public void getInfo(String arr[]) {
        arr[0] = "photoresistor";
        arr[1] = "I = " + getCurrentDText(current); // getBasicInfo(arr);
        arr[2] = "Vd = " + getVoltageDText(getVoltageDiff());
        arr[3] = "R = " + getUnitText(resistance, Locale.ohmString);
        arr[4] = "P = " + getUnitText(getPower(), "W");
    }

    public EditInfo getEditInfo(int n) {
        if (n == 0) {
            EditInfo ei = new EditInfo("Slider Text", 0, -1, -1);
            ei.text = sliderText;
            return ei;
        }
        if (n == EDIT_POSITION)
            return new EditInfo("Position", position, MIN_POSITION, MAX_POSITION).setDimensionless();
        return null;
    }

    // component edited
    public void setEditValue(int n, EditInfo ei) {
        if (n == 0) {
            String text = ei.textf.getText();
            if (!text.equals(sliderText)) {
                sliderText = text;
                if (circuitDocument != null)
                    circuitDocument.adjustableManager.ensureBuiltInSlider(this, true);
            }
        }
        if (n == EDIT_POSITION)
            position = clampPosition(ei.value);
        lux = LuxFromSliderPos();
        resistance = calcResistance(lux);
    }

    static double clampPosition(double p) {
        if (Double.isNaN(p))
            return MIN_POSITION;
        return Math.max(MIN_POSITION, Math.min(MAX_POSITION, p));
    }

    public void onMouseWheel(MouseWheelEvent e) {
        if (circuitDocument != null)
            circuitDocument.adjustableManager.onBuiltInSliderWheel(this, e);
    }

    double calcResistance(double lux) // knowing the lux
    {
        // double loglux = Math.log10(lux);
        // double slope = -1.4;
        // double intercept = 7.1;
        // double logR = (loglux-intercept)/slope;

        // return Math.round(Math.pow(10, logR));
        double r = (maxLux - lux + 1) * 10;

        r = Math.round(r);
        return r;
    }

    double LuxFromSliderPos() // knowing slider position etc
    {
        return maxLux * position + minLux;
    }

    @Override
    public String getJsonTypeName() {
        return "LDR";
    }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        props.put("position", position);
        props.put("lux", lux);
        props.put("resistance", getJsonUnitText(resistance, "Ohm"));
        props.put("min_lux", minLux);
        props.put("max_lux", maxLux);
        props.put("slider_text", sliderText);
        return props;
    }

    @Override
    public void applyJsonProperties(java.util.Map<String, Object> properties) {
        super.applyJsonProperties(properties);
        minLux = getJsonDouble(properties, "min_lux", minLux);
        maxLux = getJsonDouble(properties, "max_lux", maxLux);
        position = clampPosition(getJsonDouble(properties, "position", position));
        sliderText = getJsonString(properties, "slider_text", sliderText);
        // "lux" and "resistance" are derived from position; recompute instead of reading.
        lux = LuxFromSliderPos();
        resistance = calcResistance(lux);
    }

    @Override
    public String[] getJsonPinNames() {
        return new String[] { "a", "b" };
    }
}
