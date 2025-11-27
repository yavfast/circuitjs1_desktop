package com.lushprojects.circuitjs1.client;

import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.ui.TextArea;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;
import com.lushprojects.circuitjs1.client.dialog.Editable;
import com.lushprojects.circuitjs1.client.element.CircuitElm;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Vector;

public class CustomLogicModel implements Editable, SimulationContextAware {

    static int FLAG_SCHMITT = 1;
    static HashMap<String, CustomLogicModel> modelMap;

    int flags;
    String name;
    public String[] inputs;
    public String[] outputs;
    public String infoText;
    String rules;
    public Vector<String> rulesLeft, rulesRight;
    public boolean dumped;
    public boolean triState;
    private CircuitDocument circuitDocument;

    public static CustomLogicModel getModelWithName(String name) {
        if (modelMap == null)
            modelMap = new HashMap<String, CustomLogicModel>();
        CustomLogicModel lm = modelMap.get(name);
        if (lm != null)
            return lm;
        lm = new CustomLogicModel();
        lm.name = name;
        lm.infoText = (name.equals("default")) ? "custom logic" : name;
        modelMap.put(name, lm);
        return lm;
    }

    public static CustomLogicModel getModelWithNameOrCopy(String name, CustomLogicModel oldmodel) {
        if (modelMap == null)
            modelMap = new HashMap<String, CustomLogicModel>();
        CustomLogicModel lm = modelMap.get(name);
        if (lm != null)
            return lm;
        lm = new CustomLogicModel(oldmodel);
        lm.name = name;
        lm.infoText = name;
        modelMap.put(name, lm);
        return lm;
    }

    public static void clearDumpedFlags() {
        if (modelMap == null)
            return;
        Iterator it = modelMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, CustomLogicModel> pair = (Map.Entry) it.next();
            pair.getValue().dumped = false;
        }
    }

    CustomLogicModel() {
        inputs = listToArray("A,B");
        outputs = listToArray("C,D");
        rulesLeft = new Vector<String>();
        rulesRight = new Vector<String>();
        rules = "";
    }

    CustomLogicModel(CustomLogicModel copy) {
        flags = copy.flags;
        inputs = copy.inputs;
        outputs = copy.outputs;
        infoText = copy.infoText;
        rules = copy.rules;
        rulesLeft = copy.rulesLeft;
        rulesRight = copy.rulesRight;
    }

    public static void undumpModel(StringTokenizer st) {
        String name = unescape(st.nextToken());
        CustomLogicModel model = getModelWithName(name);
        model.undump(st);
    }

    void undump(StringTokenizer st) {
        flags = CircuitElm.parseInt(st.nextToken());
        inputs = listToArray(unescape(st.nextToken()));
        outputs = listToArray(unescape(st.nextToken()));
        infoText = unescape(st.nextToken());
        rules = unescape(st.nextToken());
        parseRules();
    }

    String arrayToList(String arr[]) {
        if (arr == null)
            return "";
        if (arr.length == 0)
            return "";
        String x = arr[0];
        int i;
        for (i = 1; i < arr.length; i++)
            x += "," + arr[i];
        return x;
    }

    String[] listToArray(String arr) {
        return arr.split(",");
    }

    public EditInfo getEditInfo(int n) {
        if (n == 0) {
            EditInfo ei = new EditInfo("Inputs", 0, -1, -1);
            ei.text = arrayToList(inputs);
            return ei;
        }
        if (n == 1) {
            EditInfo ei = new EditInfo("Outputs", 0, -1, -1);
            ei.text = arrayToList(outputs);
            return ei;
        }
        if (n == 2) {
            EditInfo ei = new EditInfo("Info Text", 0, -1, -1);
            ei.text = infoText;
            return ei;
        }
        if (n == 3) {
            EditInfo ei = new EditInfo(EditInfo.makeLink("customlogic.html", "Definition"), 0, -1, -1);
            ei.textArea = new TextArea();
            ei.textArea.setVisibleLines(5);
            ei.textArea.setText(rules);
            return ei;
        }
        /*
         * not implemented
        if (n == 4) {
            EditInfo ei = new EditInfo("", 0, -1, -1);
            ei.checkbox = new Checkbox("Schmitt", (flags & FLAG_SCHMITT) != 0);
            return ei;
        }
        */
        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 0)
            inputs = listToArray(ei.textf.getText());
        if (n == 1)
            outputs = listToArray(ei.textf.getText());
        if (n == 2)
            infoText = ei.textf.getText();
        if (n == 3) {
            rules = ei.textArea.getText();
            parseRules();
        }
        if (n == 4) {
            if (ei.checkbox.getState())
                flags |= FLAG_SCHMITT;
            else
                flags &= ~FLAG_SCHMITT;
        }
        if (circuitDocument != null) {
            circuitDocument.simulator.updateModels();
        }
    }

    @Override
    public void setSimulationContext(CircuitDocument circuitDocument) {
        this.circuitDocument = circuitDocument;
    }

    void parseRules() {
        String[] lines = rules.split("\n");
        int i;
        rulesLeft = new Vector<>();
        rulesRight = new Vector<>();
        triState = false;
        for (i = 0; i != lines.length; i++) {
            String s = lines[i].toLowerCase();
            if (s.isEmpty() || s.startsWith("#"))
                continue;
            String[] s0 = s.replaceAll(" ", "").split("=");
            if (s0.length != 2) {
                Window.alert("Error on line " + (i + 1) + " of model description");
                return;
            }
            if (s0[0].length() < inputs.length) {
                Window.alert("Model must have >= " + (inputs.length) + " digits on left side");
                return;
            }
            if (s0[0].length() > inputs.length + outputs.length) {
                Window.alert("Model must have <= " + (inputs.length + outputs.length) + " digits on left side");
                return;
            }
            if (s0[1].length() != outputs.length) {
                Window.alert("Model must have " + (outputs.length) + " digits on right side");
                return;
            }
            String rl = s0[0];
            boolean[] used = new boolean[26];
            int j;
            String newRl = "";
            for (j = 0; j != rl.length(); j++) {
                char x = rl.charAt(j);
                if (x == '?' || x == '+' || x == '-' || x == '0' || x == '1') {
                    newRl += x;
                    continue;
                }
                if (x < 'a' || x > 'z') {
                    Window.alert("Error on line " + (i + 1) + " of model description");
                    return;
                }
                // if a letter appears twice, capitalize it the 2nd time so we can compare
                if (used[x - 'a']) {
                    newRl += (char) (x + 'A' - 'a');
                    continue;
                }
                used[x - 'a'] = true;
                newRl += x;
            }
            String rr = s0[1];
            if (rr.contains("_")) {
                triState = true;
            }
            rulesLeft.add(newRl);
            rulesRight.add(s0[1]);
        }
    }

    public String dump() {
        dumped = true;
        if (!rules.isEmpty() && !rules.endsWith("\n")) {
            rules += "\n";
        }
        return "! " + escape(name) + " " + flags + " " + escape(arrayToList(inputs)) + " " +
                escape(arrayToList(outputs)) + " " + escape(infoText) + " " + escape(rules);
    }

    public static String escape(String s) {
        if (s == null || s.isEmpty()) {
            return "\\0";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case ' ': sb.append("\\s"); break;
                case '+': sb.append("\\p"); break;
                case '=': sb.append("\\q"); break;
                case '#': sb.append("\\h"); break;
                case '&': sb.append("\\a"); break;
                case '\r': sb.append("\\r"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String unescape(String s) {
        if ("\\0".equals(s)) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 's': sb.append(' '); break;
                    case 'p': sb.append('+'); break;
                    case 'q': sb.append('='); break;
                    case 'h': sb.append('#'); break;
                    case 'a': sb.append('&'); break;
                    case '\\': sb.append('\\'); break;
                    default: sb.append(next); break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
