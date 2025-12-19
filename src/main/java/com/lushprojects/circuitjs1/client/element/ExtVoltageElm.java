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

import com.lushprojects.circuitjs1.client.CustomLogicModel;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;
import com.lushprojects.circuitjs1.client.element.waveform.Waveform;
import com.lushprojects.circuitjs1.client.util.Locale;

public class ExtVoltageElm extends RailElm {
    public ExtVoltageElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy, Waveform.WF_AC);
        name = "ext";
    }

    public ExtVoltageElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
                         StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f, st);
        name = CustomLogicModel.unescape(st.nextToken());
        waveform = Waveform.WF_AC;
    }

    String name;
    double voltage;

    public String dump() {
        return dumpValues(super.dump(), CustomLogicModel.escape(name));
    }

    void drawRail(Graphics g) {
        drawRailText(g, name);
    }

    public void setVoltage(double v) {
        if (!Double.isNaN(v)) voltage = v;
    }

    public String getName() {
        return name;
    }

    public double getVoltage() {
        return voltage;
    }

    int getDumpType() {
        return 418;
    }

    public int getShortcut() {
        return 0;
    }

    public EditInfo getEditInfo(int n) {
        if (n == 0) {
            EditInfo ei = new EditInfo("Name", 0, -1, -1);
            ei.text = name;
            return ei;
        }
        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 0)
            name = ei.textf.getText();
    }

    public void getInfo(String arr[]) {
        super.getInfo(arr);
        arr[0] = Locale.LS("ext. voltage") + " (" + name + ")";
    }

    @Override
    public String getJsonTypeName() {
        return "ExternalVoltage";
    }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        props.put("name", name);
        return props;
    }
}
