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
import com.lushprojects.circuitjs1.client.element.waveform.Waveform;

public class NoiseElm extends RailElm {
    public NoiseElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy, Waveform.WF_NOISE);
    }

    public NoiseElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
                    StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f, st);
        waveform = Waveform.WF_NOISE;
    }

    // dump this class as a RailElm.  The 'n' dump type is still used in CirSim.createCe to read old files
//	int getDumpType() { return 'n'; }
    public int getShortcut() {
        return 0;
    }

    @Override
    public String getJsonTypeName() {
        return "NoiseSource";
    }
}
