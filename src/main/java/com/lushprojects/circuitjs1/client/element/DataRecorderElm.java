package com.lushprojects.circuitjs1.client.element;

import com.lushprojects.circuitjs1.client.CircuitDocument;

import com.google.gwt.i18n.client.DateTimeFormat;
import com.google.gwt.user.client.ui.Anchor;
import com.lushprojects.circuitjs1.client.CircuitSimulator;
import com.lushprojects.circuitjs1.client.Font;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;
import com.lushprojects.circuitjs1.client.util.Locale;

import java.util.Date;

public class DataRecorderElm extends CircuitElm {
    int dataCount, dataPtr;
    int lastTimeStepCount;
    double data[];
    boolean dataFull;

    public DataRecorderElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy);
        setDataCount(10240);
    }

    public DataRecorderElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
            StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f);
        setDataCount(Integer.parseInt(st.nextToken()));
    }

    public String dump() {
        return dumpValues(super.dump(), dataCount);
    }

    int getDumpType() {
        return 210;
    }

    public int getPostCount() {
        return 1;
    }

    public void reset() {
        dataPtr = 0;
        dataFull = false;
        lastTimeStepCount = 0;
    }

    public void setPoints() {
        super.setPoints();
        double dn = getDn();
        geom().setLead1(interpPoint(geom().getPoint1(), geom().getPoint2(), 1 - 8 / dn));
    }

    /** [SP_AGA_03_13] the "export" label beyond the stem; bold while highlighted */
    @Override
    public void layoutTexts(TextLayout out, boolean highlighted) {
        layoutLabel(out, Locale.LS("export"), geom().getPoint1(), geom().getLead1(),
                new Font("SansSerif", highlighted ? Font.BOLD : 0, 14), 0);
    }

    public void draw(Graphics g) {
        g.save();
        boolean selected = (needsHighlight());
        g.setColor(selected ? selectColor() : foregroundColor());
        setBbox(geom().getPoint1(), geom().getLead1(), 0);
        paintTexts(g).paint(0);
        setVoltageColor(g, getNodeVoltage(0));
        if (selected)
            g.setColor(selectColor());
        drawThickLine(g, geom().getPoint1(), geom().getLead1());
        drawPosts(g);
        g.restore();
    }

    double getVoltageDiff() {
        return getNodeVoltage(0);
    }

    public void getInfo(String arr[]) {
        arr[0] = "data export";
        arr[1] = "V = " + getVoltageText(getNodeVoltage(0));
        arr[2] = (dataFull ? dataCount : dataPtr) + "/" + dataCount;
    }

    public void stepFinished() {
        CircuitSimulator simulator = simulator();
        if (lastTimeStepCount == simulator.timeStepCount)
            return;
        data[dataPtr++] = getNodeVoltage(0);
        lastTimeStepCount = simulator.timeStepCount;
        if (dataPtr >= dataCount) {
            dataPtr = 0;
            dataFull = true;
        }
    }

    void setDataCount(int ct) {
        dataCount = ct;
        data = new double[dataCount];
        dataPtr = 0;
        dataFull = false;
    }

    static public final native String getBlobUrl(String data)
    /*-{
            var datain=[""];
            datain[0]=data;
    var oldblob = $doc.recorderBlob;
    // remove old blob if any.  We should do this when dialog is dismissed, but this is easier
    if (oldblob)
        URL.revokeObjectURL(oldblob);
            var blob=new Blob(datain, {type: 'text/plain' } );
            var url = URL.createObjectURL(blob);
            $doc.recorderBlob = url;
            return url;
    }-*/;

    public EditInfo getEditInfo(int n) {
        if (n == 0) {
            EditInfo ei = new EditInfo("# of Data Points", dataCount, -1, -1).setDimensionless();
            return ei;
        }
        if (n == 1) {
            EditInfo ei = new EditInfo("", 0, -1, -1);
            String dataStr = "# time step = " + simulator().timeStep + " sec\n";
            int i;
            if (dataFull) {
                for (i = 0; i != dataCount; i++)
                    dataStr += data[(i + dataPtr) % dataCount] + "\n";
            } else {
                for (i = 0; i != dataPtr; i++)
                    dataStr += data[i] + "\n";
            }
            String url = getBlobUrl(dataStr);
            Date date = new Date();
            DateTimeFormat dtf = DateTimeFormat.getFormat("yyyyMMdd-HHmm");
            String fname = "data-" + dtf.format(date) + ".circuitjs.txt";
            Anchor a = new Anchor(fname, url);
            a.getElement().setAttribute("Download", fname);
            ei.widget = a;
            return ei;
        }
        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 0 && ei.value > 0) {
            setDataCount((int) ei.value);
        }
        if (n == 1)
            return;
    }

    @Override
    public String getJsonTypeName() {
        return "DataRecorder";
    }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        props.put("data_points", dataCount);
        return props;
    }

    @Override
    public void applyJsonProperties(java.util.Map<String, Object> properties) {
        super.applyJsonProperties(properties);
        // Same validation as setEditValue; re-allocates the sample buffer.
        int ct = getJsonInt(properties, "data_points", dataCount);
        if (ct > 0 && ct != dataCount)
            setDataCount(ct);
    }

    @Override
    public String[] getJsonPinNames() {
        return new String[] { "input" };
    }
}
