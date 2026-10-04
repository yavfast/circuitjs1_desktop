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
    /**
     * [SP_AGA_03_04] "Model catalogues": captures catalogue entry {@code name} as it is now and
     * returns the action that puts it back — the entry's current values are re-applied in place
     * (elements keep their model object), or an entry that does not exist yet is removed again.
     * The text importer records one restorer before it undumps a model line; a caller that
     * rejects the import runs them.
     */
    public static Runnable entryRestorer(final String name) {
        if (modelMap == null) {
            modelMap = new HashMap<String, CustomLogicModel>();
        }
        final CustomLogicModel old = modelMap == null ? null : modelMap.get(name);
        if (old == null) {
            return () -> {
                if (modelMap != null) {
                    modelMap.remove(name);
                }
            };
        }
        final boolean wasDumped = old.dumped;
        final String line = old.dump();
        old.dumped = wasDumped;
        if (line == null || line.isEmpty()) {
            return () -> modelMap.put(name, old);
        }
        return () -> {
            StringTokenizer st = new StringTokenizer(line, " +\t\n\r\f");
            st.nextToken(); // line type
            st.nextToken(); // name
            boolean dumped = old.dumped;
            old.undump(st);
            old.dumped = dumped;
            modelMap.put(name, old);
        };
    }

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
        recordFallbackEntry(name);
        if (oldmodel == null) {
            // [SP_AGA_06_01] item 26: a text CustomLogic line naming an unknown model gets an
            // empty model (inputs A, B, outputs C, D, no rules) instead of failing in the copy
            // constructor, so the line loads as the editor's element does
            return getModelWithName(name);
        }
        lm = new CustomLogicModel(oldmodel);
        lm.name = name;
        lm.infoText = name;
        modelMap.put(name, lm);
        return lm;
    }

    public static void clearDumpedFlags() {
        if (modelMap == null)
            return;
        Iterator<Map.Entry<String, CustomLogicModel>> it = modelMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, CustomLogicModel> pair = it.next();
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
        // a model line of the content defines the name (no longer an element's fallback)
        if (fallbackNames != null) {
            fallbackNames.remove(name);
        }
    }

    // ------------------------------------------------------------------ [SP_AGA_03_04] fallback entries

    /** Receives a restorer for every entry an element's fallback creates; null when not recording. */
    private static java.util.function.Consumer<Runnable> fallbackSink;
    /** Names whose entry an element's fallback created during the current recording. */
    private static java.util.Set<String> fallbackNames;

    /**
     * [SP_AGA_03_04] "Model catalogues": while an import with a report runs, every entry that a
     * {@code CustomLogic} element's fallback creates for a name the catalogue lacked (an empty
     * model for a text line, a copy of the previous model for JSON) is reported to {@code sink}
     * as the restorer that removes it again, taken before the entry is created; the names are
     * remembered, so later elements naming them are unresolved too ({@link #isUnresolved}).
     */
    public static void beginFallbackRecording(java.util.function.Consumer<Runnable> sink) {
        fallbackSink = sink;
        fallbackNames = new java.util.HashSet<String>();
    }

    /** Ends {@link #beginFallbackRecording}. */
    public static void endFallbackRecording() {
        fallbackSink = null;
        fallbackNames = null;
    }

    private static void recordFallbackEntry(String name) {
        if (fallbackSink != null) {
            fallbackSink.accept(entryRestorer(name));
            fallbackNames.add(name);
        }
    }

    /**
     * @return true when an element's fallback created the entry {@code name} during the current
     *         recording (a model line for it in the same content is a definition, not a conflict)
     */
    public static boolean isFallbackName(String name) {
        return fallbackNames != null && fallbackNames.contains(name);
    }

    /**
     * [SP_AGA_03_03] "Model names": true when {@code name} names no entry of the catalogue, or an
     * entry that an element's fallback created during the current recording (and no model line
     * has defined since).
     */
    public static boolean isUnresolved(String name) {
        if (name == null) {
            return false;
        }
        if (modelMap == null || !modelMap.containsKey(name)) {
            return true;
        }
        return fallbackNames != null && fallbackNames.contains(name);
    }

    // ------------------------------------------------------------------ [SP_AGA_01_13] agent models

    /**
     * [SP_AGA_01_13] The Agent API ensures the logic {@code default} entry exists, created exactly
     * as the editor creates it when a {@code CustomLogic} element first uses it.
     */
    public static void ensureDefault() {
        getModelWithName("default");
    }

    /** @return the catalogue entry of that name, or null; never creates one */
    public static CustomLogicModel findEntry(String name) {
        if (modelMap == null || name == null)
            return null;
        return modelMap.get(name);
    }

    /** @return every catalogue entry (unordered) */
    public static java.util.List<CustomLogicModel> entries() {
        if (modelMap == null)
            modelMap = new HashMap<String, CustomLogicModel>();
        return new java.util.ArrayList<CustomLogicModel>(modelMap.values());
    }

    public String getName() {
        return name;
    }

    /** @return the rules text as stored (lines separated by newlines) */
    public String getRules() {
        return rules;
    }

    /**
     * [SP_AGA_03_11] "Identical": the model line {@link #dump()} writes (rules with a trailing
     * newline), without marking the entry dumped and without changing the stored rules.
     */
    public String modelLine() {
        return lineOf(name, flags, arrayToList(inputs), arrayToList(outputs), infoText, rules);
    }

    /** @return the model line of the given fields, as {@link #dump()} writes it */
    public static String lineOf(String name, int flags, String inputs, String outputs, String infoText, String rules) {
        String r = rules == null ? "" : rules;
        if (!r.isEmpty() && !r.endsWith("\n")) {
            r += "\n";
        }
        return "! " + escape(name) + " " + flags + " " + escape(inputs) + " " +
                escape(outputs) + " " + escape(infoText) + " " + escape(r);
    }

    /**
     * The model line a model line's fields produce when the text importer loads them, without
     * any catalogue write and without parsing the rules: the fields after the name of a
     * {@code !} line, read as {@link #undump} reads them.
     *
     * @throws RuntimeException when a field is missing
     */
    public static String normalizedLine(String name, StringTokenizer st) {
        int flags = CircuitElm.parseInt(st.nextToken());
        // the same list round trip as undump + dump ("A,B," loads as A, B)
        String inputs = arrayToList(listToArray(unescape(st.nextToken())));
        String outputs = arrayToList(listToArray(unescape(st.nextToken())));
        String info = unescape(st.nextToken());
        String rules = unescape(st.nextToken());
        return lineOf(name, flags, inputs, outputs, info, rules);
    }

    void undump(StringTokenizer st) {
        flags = CircuitElm.parseInt(st.nextToken());
        inputs = listToArray(unescape(st.nextToken()));
        outputs = listToArray(unescape(st.nextToken()));
        infoText = unescape(st.nextToken());
        rules = unescape(st.nextToken());
        parseRules();
    }

    static String arrayToList(String arr[]) {
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

    static String[] listToArray(String arr) {
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
        return modelLine();
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
