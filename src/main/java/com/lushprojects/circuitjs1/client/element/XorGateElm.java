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

import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public class XorGateElm extends OrGateElm {
    public XorGateElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy);
    }

    public XorGateElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
                      StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f, st);
    }

    String getGateName() {
        return "XOR gate";
    }

    String getGateText() {
        return "=1";
    }

    boolean calcFunction() {
        int i;
        boolean f = false;
        for (i = 0; i != inputCount; i++)
            f ^= getInput(i);
        return f;
    }

    public EditInfo getEditInfo(int n) {
        // no invert inputs option
        if (n == 3)
            return null;
        return super.getEditInfo(n);
    }

    int getDumpType() {
        return 154;
    }

    public int getShortcut() {
        return '4';
    }

    @Override
    public String getJsonTypeName() {
        return isInverting() ? "XNORGate" : "XORGate";
    }
}
