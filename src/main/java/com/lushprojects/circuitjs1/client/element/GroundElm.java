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

import com.lushprojects.circuitjs1.client.Choice;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public class GroundElm extends CircuitElm {
    static int lastSymbolType = 0;
    int symbolType;

    // this is needed for old subcircuits which have GroundElm dumped
    final int FLAG_OLD_STYLE = 1;

    public GroundElm(int xx, int yy) {
        super(xx, yy);
        symbolType = lastSymbolType;
    }

    public GroundElm(int xa, int ya, int xb, int yb, int f,
                     StringTokenizer st) {
        super(xa, ya, xb, yb, f);
        if (st.hasMoreTokens()) {
            try {
                symbolType = Integer.parseInt(st.nextToken());
            } catch (Exception e) {
            }
        }
    }

    public String dump() {
        return super.dump() + " " + symbolType;
    }

    int getDumpType() {
        return 'g';
    }

    public int getPostCount() {
        return 1;
    }

    public void draw(Graphics g) {
        setVoltageColor(g, 0);
        drawThickLine(g, point1, point2);
        if (symbolType == 0) {
            int i;
            for (i = 0; i != 3; i++) {
                int a = 10 - i * 4;
                int b = i * 5; // -10;
                interpPoint2(point1, point2, ps1, ps2, 1 + b / dn, a);
                drawThickLine(g, ps1, ps2);
            }
        } else if (symbolType == 1) {
            interpPoint2(point1, point2, ps1, ps2, 1, 10);
            drawThickLine(g, ps1, ps2);
            int i;
            for (i = 0; i <= 2; i++) {
                Point p = interpPoint(ps1, ps2, i / 2.);
                drawThickLine(g, p.x, p.y, (int) (p.x - 5 * dpx1 + 8 * dx / dn), (int) (p.y + 8 * dy / dn - 5 * dpy1));
            }
        } else if (symbolType == 2) {
            interpPoint2(point1, point2, ps1, ps2, 1, 10);
            drawThickLine(g, ps1, ps2);
            int ps3x = (int) (point2.x + 10 * dx / dn);
            int ps3y = (int) (point2.y + 10 * dy / dn);
            drawThickLine(g, ps1.x, ps1.y, ps3x, ps3y);
            drawThickLine(g, ps2.x, ps2.y, ps3x, ps3y);
        } else {
            interpPoint2(point1, point2, ps1, ps2, 1, 10);
            drawThickLine(g, ps1, ps2);
        }
        interpPoint(point1, point2, ps2, 1 + 11. / dn);
        doDots(g);
        setBbox(point1, ps2, 11);
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
            simulator.stampVoltageSource(0, nodes[0], voltSource, 0);
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
        firstGround = point1;
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
}
