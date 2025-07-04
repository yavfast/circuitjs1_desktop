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

import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public class LatchElm extends ChipElm {
    final int FLAG_STATE = 2;
    final int FLAG_NO_EDGE = 4;

    public LatchElm(int xx, int yy) {
        super(xx, yy);
        flags |= FLAG_STATE;
        setupPins();
    }

    public LatchElm(int xa, int ya, int xb, int yb, int f,
                    StringTokenizer st) {
        super(xa, ya, xb, yb, f, st);

        // add FLAG_STATE flag to old latches so their state gets saved
        if ((flags & FLAG_STATE) == 0) {
            flags |= FLAG_STATE;
            setupPins();
        }
    }

    String getChipName() {
        return "Latch";
    }

    boolean needsBits() {
        return true;
    }

    boolean isEdgeTriggered() {
        return (flags & FLAG_NO_EDGE) == 0;
    }

    int loadPin;

    void setupPins() {
        sizeX = 2;
        sizeY = bits + 1;
        pins = new Pin[getPostCount()];
        int i;
        for (i = 0; i != bits; i++)
            pins[i] = new Pin(bits - 1 - i, SIDE_W, "I" + i);
        for (i = 0; i != bits; i++) {
            pins[i + bits] = new Pin(bits - 1 - i, SIDE_E, "O");
            pins[i + bits].output = true;
            pins[i + bits].state = (flags & FLAG_STATE) != 0;
        }
        pins[loadPin = bits * 2] = new Pin(bits, SIDE_W, "Ld");
        allocNodes();
    }

    boolean lastLoad = false;

    void execute() {
        int i;
        if (pins[loadPin].value && (!isEdgeTriggered() || !lastLoad))
            for (i = 0; i != bits; i++)
                pins[i + bits].value = pins[i].value;
        lastLoad = pins[loadPin].value;
    }

    public int getVoltageSourceCount() {
        return bits;
    }

    public int getPostCount() {
        return bits * 2 + 1;
    }

    int getDumpType() {
        return 168;
    }

    public EditInfo getChipEditInfo(int n) {
        if (n == 0)
            return new EditInfo("# of Bits", bits, 1, 1).setDimensionless();
        if (n == 1)
            return EditInfo.createCheckbox("Edge Triggered", isEdgeTriggered());
        return null;
    }

    public void setChipEditValue(int n, EditInfo ei) {
        if (n == 0 && ei.value >= 2 && bits != (int) ei.value) {
            bits = (int) ei.value;
            setupPins();
            setPoints();
        }
        if (n == 1)
            flags = ei.changeFlagInverted(flags, FLAG_NO_EDGE);
    }

}
    
