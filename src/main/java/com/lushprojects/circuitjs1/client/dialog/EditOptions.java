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

package com.lushprojects.circuitjs1.client.dialog;

import com.google.gwt.storage.client.Storage;
import com.google.gwt.user.client.Window;
import com.lushprojects.circuitjs1.client.OptionsManager;
import com.lushprojects.circuitjs1.client.element.AudioOutputElm;
import com.lushprojects.circuitjs1.client.Checkbox;
import com.lushprojects.circuitjs1.client.Choice;
import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.Color;
import com.lushprojects.circuitjs1.client.util.Locale;

public class EditOptions implements Editable {
    CirSim sim;

    public EditOptions(CirSim s) {
        sim = s;
    }

    public EditInfo getEditInfo(int n) {
        if (n == 0)
            return new EditInfo("Time step size (s)", sim.simulator.maxTimeStep, 0, 0);
        if (n == 1)
            return new EditInfo("Range for voltage color (V)",
                    CircuitElm.voltageRange, 0, 0);
        if (n == 2) {
            EditInfo ei = new EditInfo("Change Language", 0, -1, -1);
            ei.choice = new Choice();
            ei.choice.add("(no change)");
            ei.choice.add("Čeština");
            ei.choice.add("Dansk");
            ei.choice.add("Deutsch");
            ei.choice.add("English");
            ei.choice.add("Español");
            ei.choice.add("Français");
            ei.choice.add("Italiano");
            ei.choice.add("Norsk bokmål");
            ei.choice.add("Polski");
            ei.choice.add("Português");
            ei.choice.add("\u0420\u0443\u0441\u0441\u043a\u0438\u0439"); // Russian
            ei.choice.add("\u4e2d\u6587 (\u4e2d\u56fd\u5927\u9646)"); // Chinese
            ei.choice.add("\u4e2d\u6587 (\u53f0\u6e7e)"); // Chinese (tw)
            ei.choice.add("日本語"); // Japanese
            return ei;
        }

        if (n == 3) {
            EditInfo ei = new EditInfo("Positive Color", CircuitElm.positiveColor.getHexValue());
            ei.isColor = true;
            return ei;
        }

        if (n == 4) {
            EditInfo ei = new EditInfo("Negative Color", CircuitElm.negativeColor.getHexValue());
            ei.isColor = true;
            return ei;
        }

        if (n == 5) {
            EditInfo ei = new EditInfo("Neutral Color", CircuitElm.neutralColor.getHexValue());
            ei.isColor = true;
            return ei;
        }

        if (n == 6) {
            EditInfo ei = new EditInfo("Selection Color", CircuitElm.selectColor.getHexValue());
            ei.isColor = true;
            return ei;
        }

        if (n == 7) {
            EditInfo ei = new EditInfo("Current Color", CircuitElm.currentColor.getHexValue());
            ei.isColor = true;
            return ei;
        }

        if (n == 8)
            return new EditInfo("# of Decimal Digits (short format)", CircuitElm.shortDecimalDigits);
        if (n == 9)
            return new EditInfo("# of Decimal Digits (long format)", CircuitElm.decimalDigits);
        if (n == 10) {
            EditInfo ei = new EditInfo("", 0, -1, -1);
            ei.checkbox = new Checkbox("Developer Mode", sim.circuitInfo.developerMode);
            return ei;
        }
        if (n == 11)
            return new EditInfo("Minimum Target Frame Rate", sim.simulator.minFrameRate);
        if (n == 12)
            return new EditInfo("Mouse Wheel Sensitivity", sim.circuitEditor.wheelSensitivity);
        if (n == 13) {
            EditInfo ei = new EditInfo("", 0, -1, -1);
            ei.checkbox = new Checkbox("Auto-Adjust Timestep", sim.simulator.adjustTimeStep);
            return ei;
        }
        if (n == 14 && sim.simulator.adjustTimeStep)
            return new EditInfo("Minimum time step size (s)", sim.simulator.minTimeStep, 0, 0);

        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 0 && ei.value > 0) {
            sim.simulator.maxTimeStep = ei.value;

            // if timestep changed manually, prompt before changing it again
            AudioOutputElm.okToChangeTimeStep = false;
        }
        if (n == 1 && ei.value > 0)
            CircuitElm.voltageRange = ei.value;
        if (n == 2) {
            int lang = ei.choice.getSelectedIndex();
            if (lang == 0)
                return;
            String langString = null;
            switch (lang) {
                // Czech is csx instead of cs because we are not ready to use it automatically yet
                case 1:
                    langString = "csx";
                    break;
                case 2:
                    langString = "da";
                    break;
                case 3:
                    langString = "de";
                    break;
                case 4:
                    langString = "en";
                    break;
                case 5:
                    langString = "es";
                    break;
                case 6:
                    langString = "fr";
                    break;
                case 7:
                    langString = "it";
                    break;
                case 8:
                    langString = "nb";
                    break;
                case 9:
                    langString = "pl";
                    break;
                case 10:
                    langString = "pt";
                    break;
                case 11:
                    langString = "ru";
                    break;
                case 12:
                    langString = "zh";
                    break;
                case 13:
                    langString = "zh-tw";
                    break;
                case 14:
                    langString = "ja";
                    break;
            }
            if (langString == null) {
                return;
            }
            OptionsManager.setOptionInStorage("language", langString);
            if (Window.confirm(Locale.LS("Must restart to set language. Restart now?"))) {
                Window.Location.reload();
            }
        }
        if (n == 3) {
            CircuitElm.positiveColor = setColor("positiveColor", ei, Color.green);
            CircuitElm.setColorScale();
        }
        if (n == 4) {
            CircuitElm.negativeColor = setColor("negativeColor", ei, Color.red);
            CircuitElm.setColorScale();
        }
        if (n == 5) {
            CircuitElm.neutralColor = setColor("neutralColor", ei, Color.gray);
            CircuitElm.setColorScale();
        }
        if (n == 6)
            CircuitElm.selectColor = setColor("selectColor", ei, Color.cyan);
        if (n == 7)
            CircuitElm.currentColor = setColor("currentColor", ei, Color.yellow);
        if (n == 8)
            CircuitElm.setDecimalDigitsShort((int) ei.value, true);
        if (n == 9)
            CircuitElm.setDecimalDigits((int) ei.value, true);
        if (n == 10)
            sim.setDeveloperMode(ei.checkbox.getState());
        if (n == 11 && ei.value > 0)
            sim.simulator.minFrameRate = ei.value;
        if (n == 12 && ei.value > 0) {
            sim.circuitEditor.wheelSensitivity = ei.value;
            OptionsManager.setOptionInStorage("wheelSensitivity", Double.toString(sim.circuitEditor.wheelSensitivity));
        }
        if (n == 13) {
            sim.simulator.adjustTimeStep = ei.checkbox.getState();
            ei.newDialog = true;
        }
        if (n == 14 && ei.value > 0)
            sim.simulator.minTimeStep = ei.value;
    }

    Color setColor(String name, EditInfo ei, Color def) {
        String val = ei.textf.getText();
        if (val.isEmpty()) {
            val = def.getHexValue();
        }
        OptionsManager.setOptionInStorage(name, val);
        return new Color(val);
    }
};
