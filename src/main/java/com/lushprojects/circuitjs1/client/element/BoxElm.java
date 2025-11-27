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

import com.lushprojects.circuitjs1.client.Color;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Rectangle;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public class BoxElm extends GraphicElm {

    public BoxElm(int xx, int yy) {
        super(xx, yy);
        x2 = xx;
        y2 = yy;
        setBbox(x, y, x2, y2);
    }

    public BoxElm(int xa, int ya, int xb, int yb, int f,
                  StringTokenizer st) {
        super(xa, ya, xb, yb, f);
        x2 = xb;
        y2 = yb;
        setBbox(x, y, x2, y2);
    }

    public String dump() {
        return super.dump();
    }

    int getDumpType() {
        return 'b';
    }

    public void drag(int xx, int yy) {
        x2 = xx;
        y2 = yy;
    }

    public boolean creationFailed() {
        return Math.abs(x2 - x) < 32 || Math.abs(y2 - y) < 32;
    }

    public void draw(Graphics g) {
        //g.setColor(needsHighlight() ? selectColor : lightGrayColor);
        g.setColor(needsHighlight() ? selectColor : Color.GRAY);
        setBbox(x, y, x2, y2);
        g.setLineDash(16, 6);
        if (x < x2 && y < y2)
            g.drawRect(x, y, x2 - x, y2 - y);
        else if (x > x2 && y < y2)
            g.drawRect(x2, y, x - x2, y2 - y);
        else if (x < x2 && y > y2)
            g.drawRect(x, y2, x2 - x, y - y2);
        else
            g.drawRect(x2, y2, x - x2, y - y2);
        g.setLineDash(0, 0);
    }

    public EditInfo getEditInfo(int n) {
        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
    }

    public void getInfo(String arr[]) {
    }

    @Override
    public int getShortcut() {
        return 0;
    }

    public int getMouseDistance(int gx, int gy) {
        int thresh = 10;
        int dx1 = Math.abs(gx - x);
        int dy1 = Math.abs(gy - y);
        int dx2 = Math.abs(gx - x2);
        int dy2 = Math.abs(gy - y2);
        if (Math.abs(dx1) < thresh)
            return dx1 * dx1;
        if (Math.abs(dx2) < thresh)
            return dx2 * dx2;
        if (Math.abs(dy1) < thresh)
            return dy1 * dy1;
        if (Math.abs(dy2) < thresh)
            return dy2 * dy2;
        return -1;
    }

    public void selectRect(Rectangle r, boolean add) {
        if (r.contains(boundingBox))
            selected = true;
        else if (!add)
            selected = false;
    }

    @Override
    public String getJsonTypeName() {
        return "Box";
    }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        props.put("x", x);
        props.put("y", y);
        props.put("x2", x2);
        props.put("y2", y2);
        return props;
    }
}

