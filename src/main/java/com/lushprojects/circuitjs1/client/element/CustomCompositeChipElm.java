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
import com.lushprojects.circuitjs1.client.Graphics;

// concrete subclass of ChipElm that can be used by other elements (like CustomCompositeElm) to draw chips.
// CustomCompositeElm can't be a subclass of both ChipElm and CompositeElm.
public class CustomCompositeChipElm extends ChipElm {
    String label;

    public CustomCompositeChipElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy);
        setSize(2);
    }

    boolean needsBits() {
        return false;
    }

    void setupPins() {
    }

    public int getVoltageSourceCount() {
        return 0;
    }

    void setPins(Pin p[]) {
        pins = p;
    }

    public void allocPins(int n) {
        pins = new Pin[n];
    }

    public void setPin(int n, int p, int s, String t) {
        pins[n] = new Pin(p, s, t);
        pins[n].fixName();
    }

    public void setLabel(String text) {
        label = text;
    }

    void drawLabel(Graphics g, int x, int y) {
        if (label == null)
            return;
        g.save();
        g.setTextBaseline(Context2d.TextBaseline.MIDDLE);
        g.setTextAlign(Context2d.TextAlign.CENTER);
        g.drawString(label, x, y);
        g.restore();
    }

    public int getPostCount() {
        return pins == null ? 1 : pins.length;
    }

    @Override
    public String getJsonTypeName() { return "CustomCompositeChip"; }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        if (label != null)
            props.put("label", label);
        return props;
    }
}

