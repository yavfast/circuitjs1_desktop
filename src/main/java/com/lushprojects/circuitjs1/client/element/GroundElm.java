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

import com.lushprojects.circuitjs1.client.Choice;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public class GroundElm extends CircuitElm {
    static int lastSymbolType = 0;
    int symbolType;
    private Point ptemp;

    // this is needed for old subcircuits which have GroundElm dumped
    final int FLAG_OLD_STYLE = 1;

    public GroundElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy);
        symbolType = lastSymbolType;
    }

    public GroundElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
                     StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f);
        if (st.hasMoreTokens()) {
            try {
                symbolType = Integer.parseInt(st.nextToken());
            } catch (Exception e) {
            }
        }
    }

    @Override
    protected String getIdPrefix() {
        return "GND";
    }

    public String dump() {
        return dumpValues(super.dump(), symbolType);
    }

    int getDumpType() {
        return 'g';
    }

    public int getPostCount() {
        return 1;
    }

    public void draw(Graphics g) {
        setVoltageColor(g, 0);
        Point p1 = geom().getPoint1();
        Point p2 = geom().getPoint2();
        drawThickLine(g, p1, p2);
        if (symbolType == 0) {
            int i;
            for (i = 0; i != 3; i++) {
                int a = 10 - i * 4;
                int b = i * 5; // -10;
                interpPoint2(p1, p2, ps1, ps2, 1 + b / getDn(), a);
                drawThickLine(g, ps1, ps2);
            }
        } else if (symbolType == 1) {
            interpPoint2(p1, p2, ps1, ps2, 1, 10);
            drawThickLine(g, ps1, ps2);
            int i;
            for (i = 0; i <= 2; i++) {
                if (ptemp == null) ptemp = new Point();
                interpPoint(ps1, ps2, ptemp, i / 2.);
                drawThickLine(g, ptemp.x, ptemp.y,
                        (int) (ptemp.x - 5 * getDpx1() + 8 * getDx() / getDn()),
                        (int) (ptemp.y + 8 * getDy() / getDn() - 5 * getDpy1()));
            }
        } else if (symbolType == 2) {
            interpPoint2(p1, p2, ps1, ps2, 1, 10);
            drawThickLine(g, ps1, ps2);
            int ps3x = (int) (p2.x + 10 * getDx() / getDn());
            int ps3y = (int) (p2.y + 10 * getDy() / getDn());
            drawThickLine(g, ps1.x, ps1.y, ps3x, ps3y);
            drawThickLine(g, ps2.x, ps2.y, ps3x, ps3y);
        } else {
            interpPoint2(p1, p2, ps1, ps2, 1, 10);
            drawThickLine(g, ps1, ps2);
        }
        interpPoint(p1, p2, ps2, 1 + 11. / getDn());
        doDots(g);
        setBbox(p1, ps2, 11);
        drawPosts(g);
    }

    void setOldStyle() {
        flags |= FLAG_OLD_STYLE;
    }

    boolean isOldStyle() {
        return (flags & FLAG_OLD_STYLE) != 0;
    }

    public int getVoltageSourceCount() {
        return (isOldStyle()) ? 1 : 0;
    }

    public void stamp() {
        if (isOldStyle())
            simulator().stampVoltageSource(0, getNode(0), voltSource, 0);
    }

    public void setCurrent(int x, double c) {
        current = isOldStyle() ? -c : c;
    }

    public boolean isWireEquivalent() {
        return true;
    }

    public boolean isRemovableWire() {
        return true;
    }

    static Point firstGround;

    public static void resetNodeList() {
        firstGround = null;
    }

    public Point getConnectedPost() {
        if (firstGround != null)
            return firstGround;
        firstGround = geom().getPoint1();
        return null;
    }

    //	void setCurrent(int x, double c) { current = -c; }
    double getVoltageDiff() {
        return 0;
    }

    public void getInfo(String arr[]) {
        arr[0] = "ground";
        arr[1] = "I = " + getCurrentText(getCurrent());
    }

    public boolean hasGroundConnection(int n1) {
        return true;
    }

    public int getShortcut() {
        return 'g';
    }

    public EditInfo getEditInfo(int n) {
        if (n == 0) {
            EditInfo ei = new EditInfo("Symbol", 0);
            ei.choice = new Choice();
            ei.choice.add("Earth");
            ei.choice.add("Chassis");
            ei.choice.add("Signal");
            ei.choice.add("Common");
            ei.choice.select(symbolType);
            return ei;
        }
        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 0)
            lastSymbolType = symbolType = ei.choice.getSelectedIndex();
    }

    @Override
    public double getCurrentIntoNode(int n) {
        return -current;
    }

    @Override
    public String getJsonTypeName() {
        return "Ground";
    }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        String symbolName;
        switch (symbolType) {
            case 1: symbolName = "chassis"; break;
            case 2: symbolName = "signal"; break;
            case 3: symbolName = "common"; break;
            default: symbolName = "earth"; break;
        }
        props.put("symbol", symbolName);
        return props;
    }

    @Override
    public String[] getJsonPinNames() {
        return new String[] { "gnd" };
    }

    @Override
    public void applyJsonProperties(java.util.Map<String, Object> props) {
        super.applyJsonProperties(props);
        
        // Parse symbol type
        String symbol = getJsonString(props, "symbol", "earth");
        switch (symbol) {
            case "chassis": symbolType = 1; break;
            case "signal": symbolType = 2; break;
            case "common": symbolType = 3; break;
            default: symbolType = 0; break;
        }
    }
}
