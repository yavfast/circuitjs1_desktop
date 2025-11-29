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

import com.lushprojects.circuitjs1.client.Checkbox;
import com.lushprojects.circuitjs1.client.CircuitSimulator;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public class MonostableElm extends ChipElm {

    //Used to detect rising edge
    private boolean prevInputValue = false;
    private boolean retriggerable = false;
    private boolean triggered = false;
    private double lastRisingEdge = 0;
    private double delay = 0.01;

    public MonostableElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy);
        reset();
    }

    public MonostableElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
                         StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f, st);
        retriggerable = new Boolean(st.nextToken()).booleanValue();
        delay = parseDouble(st.nextToken());
        reset();
    }

    String getChipName() {
        return "Monostable";
    }

    void setupPins() {
        sizeX = 2;
        sizeY = 2;
        pins = new Pin[getPostCount()];
        pins[0] = new Pin(0, SIDE_W, "");
        pins[0].clock = true;
        pins[1] = new Pin(0, SIDE_E, "Q");
        pins[1].output = true;
        pins[2] = new Pin(1, SIDE_E, "Q");
        pins[2].output = true;
        pins[2].lineOver = true;
    }

    public void reset() {
        super.reset();
        pins[2].value = true;
        triggered = prevInputValue = false;
    }

    public int getPostCount() {
        return 3;
    }

    public int getVoltageSourceCount() {
        return 2;
    }

    void execute() {
        CircuitSimulator simulator = simulator();
        if (pins[0].value && prevInputValue != pins[0].value && (retriggerable || !triggered)) {
            lastRisingEdge = simulator().t;
            pins[1].value = true;
            pins[2].value = false;
            triggered = true;
        }

        if (triggered && simulator().t > lastRisingEdge + delay) {
            pins[1].value = false;
            pins[2].value = true;
            triggered = false;
        }
        prevInputValue = pins[0].value;
    }

    public String dump() {
        return dumpValues(super.dump(), retriggerable, delay);
    }

    int getDumpType() {
        return 194;
    }

    public EditInfo getChipEditInfo(int n) {
        if (n == 0) {
            EditInfo ei = new EditInfo("", 0, -1, -1);
            ei.checkbox = new Checkbox("Retriggerable", retriggerable);
            return ei;
        }
        if (n == 1) {
            EditInfo ei = new EditInfo("Period (s)", delay, 0.001, 0.1);
            return ei;
        }
        return super.getChipEditInfo(n);
    }

    public void setChipEditValue(int n, EditInfo ei) {
        if (n == 0) {
            retriggerable = ei.checkbox.getState();
        }
        if (n == 1) {
            delay = ei.value;
        }
        super.setChipEditValue(n, ei);
    }

    @Override
    public String getJsonTypeName() {
        return "Monostable";
    }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        props.put("delay", getUnitText(delay, "s"));
        props.put("retriggerable", retriggerable);
        return props;
    }

    @Override
    public java.util.Map<String, Object> getJsonState() {
        java.util.Map<String, Object> state = super.getJsonState();
        state.put("prevInputValue", prevInputValue);
        state.put("triggered", triggered);
        state.put("lastRisingEdge", lastRisingEdge);
        return state;
    }

    @Override
    public void applyJsonState(java.util.Map<String, Object> state) {
        super.applyJsonState(state);
        if (state.containsKey("prevInputValue"))
            prevInputValue = (Boolean) state.get("prevInputValue");
        if (state.containsKey("triggered"))
            triggered = (Boolean) state.get("triggered");
        if (state.containsKey("lastRisingEdge"))
            lastRisingEdge = ((Number) state.get("lastRisingEdge")).doubleValue();
    }
}
