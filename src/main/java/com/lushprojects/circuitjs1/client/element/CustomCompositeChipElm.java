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
        // Owners (CustomCompositeElm, the model editor) set size and pins right after
        // construction. A standalone chip (e.g. from JSON import) has no pins; give it a
        // minimal body so it stays visible and selectable.
        if (pins == null)
            sizeX = sizeY = 1;
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

    /** [SP_AGA_03_13] Not covered yet (PL_AGA Phase 16b): this class still draws text outside layoutTexts. */
    @Override
    public boolean textLayoutCovered() {
        return false;
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
        // no pins yet (standalone chip): report no posts so pin loops never index a null array
        return pins == null ? 0 : pins.length;
    }

    // Drawing helper only: it has no dump type, so a standalone instance is skipped by the
    // text exporter/undo instead of throwing from getDumpType().
    public String dump() {
        return null;
    }

    @Override
    public boolean hasDumpLine() {
        return false;
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

    // [SP_AGA_03_03] keys getJsonProperties() writes only when they differ from these defaults
    @Override
    public java.util.Map<String, Object> getJsonConditionalProperties() {
        java.util.Map<String, Object> props = super.getJsonConditionalProperties();
        props.put("label", "");
        return props;
    }

    @Override
    public void applyJsonProperties(java.util.Map<String, Object> properties) {
        super.applyJsonProperties(properties);
        label = getJsonString(properties, "label", label);
    }
}

