package com.lushprojects.circuitjs1.client;

import com.lushprojects.circuitjs1.client.dialog.EditInfo;
import com.lushprojects.circuitjs1.client.dialog.Editable;
import com.lushprojects.circuitjs1.client.util.Locale;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Vector;

public class TransistorModel implements Editable, Comparable<TransistorModel>, SimulationContextAware {

    static HashMap<String, TransistorModel> modelMap;

    int flags;
    public String name, description;
    public double satCur, invRollOffF, BEleakCur, leakBEemissionCoeff, invRollOffR, BCleakCur, leakBCemissionCoeff;
    public double emissionCoeffF, emissionCoeffR, invEarlyVoltF, invEarlyVoltR, betaR;

    public boolean dumped;
    public boolean readOnly;
    public boolean builtIn;
    boolean internal;
    private CircuitDocument circuitDocument;
    /**
     * [SP_AGA_03_04] "Model catalogues": captures catalogue entry {@code name} as it is now and
     * returns the action that puts it back — the entry's current values are re-applied in place
     * (elements keep their model object), or an entry that does not exist yet is removed again.
     * The text importer records one restorer before it undumps a model line; a caller that
     * rejects the import runs them.
     */
    public static Runnable entryRestorer(final String name) {
        createModelMap();
        final TransistorModel old = modelMap == null ? null : modelMap.get(name);
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

    TransistorModel(String d, double sc) {
        description = d;
        satCur = sc;
        emissionCoeffF = emissionCoeffR = 1;
        leakBEemissionCoeff = 1.5;
        leakBCemissionCoeff = 2;
        betaR = 1;
        updateModel();
    }

    static TransistorModel getModelWithName(String name) {
        createModelMap();
        TransistorModel lm = modelMap.get(name);
        if (lm != null)
            return lm;
        lm = new TransistorModel();
        lm.name = name;
        modelMap.put(name, lm);
        return lm;
    }

    /**
     * [SP_AGA_03_03] "Model names": true when the session catalogue holds an entry with this
     * name (built-in, internal, or loaded from a model line); never creates one.
     */
    public static boolean hasModel(String name) {
        createModelMap();
        return name != null && modelMap.containsKey(name);
    }

    /**
     * [SP_AGA_03_03] Removes the entry {@code name} when it is {@code fallback}, a non-built-in
     * copy an element's fallback registered for a name the catalogue lacked.
     */
    public static void removeFallback(String name, TransistorModel fallback) {
        createModelMap();
        if (fallback != null && !fallback.builtIn && modelMap.get(name) == fallback) {
            modelMap.remove(name);
        }
    }

    public static TransistorModel getModelWithNameOrCopy(String name, TransistorModel oldmodel) {
        createModelMap();
        TransistorModel lm = modelMap.get(name);
        if (lm != null)
            return lm;
        if (oldmodel == null) {
            CirSim.console("model not found: " + name);
            return getDefaultModel();
        }
        lm = new TransistorModel(oldmodel);
        lm.name = name;
        modelMap.put(name, lm);
        return lm;
    }

    static void createModelMap() {
        if (modelMap != null)
            return;
        modelMap = new HashMap<String, TransistorModel>();
        addDefaultModel("default", new TransistorModel("default", 1e-13));
        addDefaultModel("spice-default", new TransistorModel("spice-default", 1e-16));

        // for LM324v2 OpAmpRealElm
        loadInternalModel("xlm324v2-qpi 0 1.01e-16 333.3333333333333 0 1.5 0 0 2 1 1 0.0034482758620689655 0 1");
        loadInternalModel("xlm324v2-qpi 0 1.01e-16 333.3333333333333 0 1.5 0 0 2 1 1 0.0034482758620689655 0 1");
        loadInternalModel("xlm324v2-qpa 0 1.01e-16 333.3333333333333 0 1.5 0 0 2 1 1 0.004081632653061225 0 1");
        loadInternalModel("xlm324v2-qnq 0 1e-16 200 0 1.5 0 0 2 1 1 0 0 1");
        loadInternalModel("xlm324v2-qpq 0 1e-16 333.3333333333333 0 1.5 0 0 2 1 1 0 0 1");

        // for TL431
        loadInternalModel("~tl431ed-qn_ed 0 1e-16 0 0 1.5 0 0 2 1 1 0.0125 0.02 1");
        loadInternalModel("~tl431ed-qn_ed-A1.2 0 1.2e-16 0 0 1.5 0 0 2 1 1 0.0125 0.02 1");
        loadInternalModel("~tl431ed-qn_ed-A2.2 0 2.2000000000000002e-16 0 0 1.5 0 0 2 1 1 0.0125 0.02 1");
        loadInternalModel("~tl431ed-qn_ed-A0.5 0 5e-17 0 0 1.5 0 0 2 1 1 0.0125 0.02 1");
        loadInternalModel("~tl431ed-qp_ed 0 1e-16 0 0 1.5 0 0 2 1 1 0.014285714285714285 0.025 1");
        loadInternalModel("~tl431ed-qn_ed-A5 0 5e-16 0 0 1.5 0 0 2 1 1 0.0125 0.02 1");

        // for LM317
        loadInternalModel("~lm317-qpl-A0.1 0 1e-17 0 0 1.5 0 0 2 1 1 0.02 0 1");
        loadInternalModel("~lm317-qnl-A0.2 0 2e-17 0 0 1.5 0 0 2 1 1 0.01 0 1");
        loadInternalModel("~lm317-qpl-A0.2 0 2e-17 0 0 1.5 0 0 2 1 1 0.02 0 1");
        loadInternalModel("~lm317-qnl-A2 0 2e-16 0 0 1.5 0 0 2 1 1 0.01 0 1");
        loadInternalModel("~lm317-qpl-A2 0 2e-16 0 0 1.5 0 0 2 1 1 0.02 0 1");
        loadInternalModel("~lm317-qnl-A5 0 5e-16 0 0 1.5 0 0 2 1 1 0.01 0 1");
        loadInternalModel("~lm317-qnl-A50 0 5e-15 0 0 1.5 0 0 2 1 1 0.01 0 1");

    }

    static void addDefaultModel(String name, TransistorModel dm) {
        modelMap.put(name, dm);
        dm.readOnly = dm.builtIn = true;
        dm.name = name;
    }

    static TransistorModel getDefaultModel() {
        return getModelWithName("default");
    }

    public static void clearDumpedFlags() {
        if (modelMap == null)
            return;
        Iterator<Map.Entry<String, TransistorModel>> it = modelMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, TransistorModel> pair = it.next();
            pair.getValue().dumped = false;
        }
    }

    public static Vector<TransistorModel> getModelList() {
        createModelMap();
        Vector<TransistorModel> vector = new Vector<TransistorModel>();
        Iterator<Map.Entry<String, TransistorModel>> it = modelMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, TransistorModel> pair = it.next();
            TransistorModel tm = pair.getValue();
            if (tm.internal)
                continue;
            if (!vector.contains(tm))
                vector.add(tm);
        }
        Collections.sort(vector);
        return vector;
    }

    public int compareTo(TransistorModel dm) {
        return name.compareTo(dm.name);
    }

    public String getDescription() {
        if (description == null || description.equals(name))
            return name;
        return name + " (" + Locale.LS(description) + ")";
    }

    public TransistorModel() {
        updateModel();
    }

    public TransistorModel(TransistorModel copy) {
        flags = copy.flags;
        satCur = copy.satCur;
        invRollOffF = copy.invRollOffF;
        BEleakCur = copy.BEleakCur;
        leakBEemissionCoeff = copy.leakBEemissionCoeff;
        invRollOffR = copy.invRollOffR;
        BCleakCur = copy.BCleakCur;
        leakBCemissionCoeff = copy.leakBCemissionCoeff;
        emissionCoeffF = copy.emissionCoeffF;
        emissionCoeffR = copy.emissionCoeffR;
        invEarlyVoltF = copy.invEarlyVoltF;
        invEarlyVoltR = copy.invEarlyVoltR;
        betaR = copy.betaR;
        updateModel();
    }

    static void loadInternalModel(String s) {
        StringTokenizer st = new StringTokenizer(s);
        TransistorModel tm = undumpModel(st);
        tm.builtIn = tm.internal = true;
    }

    public static TransistorModel undumpModel(StringTokenizer st) {
        String name = CustomLogicModel.unescape(st.nextToken());
        TransistorModel dm = TransistorModel.getModelWithName(name);
        dm.undump(st);
        return dm;
    }

    void undump(StringTokenizer st) {
        flags = Integer.parseInt(st.nextToken());

        satCur = Double.parseDouble(st.nextToken());
        invRollOffF = Double.parseDouble(st.nextToken());
        BEleakCur = Double.parseDouble(st.nextToken());
        leakBEemissionCoeff = Double.parseDouble(st.nextToken());
        invRollOffR = Double.parseDouble(st.nextToken());
        BCleakCur = Double.parseDouble(st.nextToken());
        leakBCemissionCoeff = Double.parseDouble(st.nextToken());
        emissionCoeffF = Double.parseDouble(st.nextToken());
        emissionCoeffR = Double.parseDouble(st.nextToken());
        invEarlyVoltF = Double.parseDouble(st.nextToken());
        invEarlyVoltR = Double.parseDouble(st.nextToken());
        betaR = Double.parseDouble(st.nextToken());

        updateModel();
    }

    public EditInfo getEditInfo(int n) {
        if (n == 0) {
            EditInfo ei = new EditInfo("Model Name", 0);
            ei.text = name == null ? "" : name;
            return ei;
        }
        if (n == 1) return new EditInfo("Transport Saturation Current (IS)", satCur);
        if (n == 2) return new EditInfo("Reverse Beta (BR)", betaR);
        if (n == 3) return new EditInfo("Forward Early Voltage (VAF)", 1 / invEarlyVoltF);
        if (n == 4) return new EditInfo("Reverse Early Voltage (VAR)", 1 / invEarlyVoltR);
        if (n == 5)
            return new EditInfo("Corner For Forward Beta High Current Roll-Off (IKF)", 1 / invRollOffF);
        if (n == 6)
            return new EditInfo("Corner For Reverse Beta High Current Roll-Off (IKR)", 1 / invRollOffR);
        if (n == 7)
            return new EditInfo("Forward Current Emission Coefficient (NF)", emissionCoeffF);
        if (n == 8)
            return new EditInfo("Reverse Current Emission Coefficient (NR)", emissionCoeffR);
        if (n == 9) return new EditInfo("B-E Leakage Saturation Current (ISE)", BEleakCur);
        if (n == 10)
            return new EditInfo("B-E Leakage Emission Coefficient (NE)", leakBEemissionCoeff);
        if (n == 11) return new EditInfo("B-C Leakage Saturation Current (ISC)", BCleakCur);
        if (n == 12)
            return new EditInfo("B-C Leakage Emission Coefficient (NC)", leakBCemissionCoeff);
        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 0) {
            name = ei.textf.getText();
            if (!name.isEmpty())
                modelMap.put(name, this);
        }
        if (n == 1) satCur = ei.value;
        if (n == 2) betaR = ei.value;
        if (n == 3) invEarlyVoltF = 1 / ei.value;
        if (n == 4) invEarlyVoltR = 1 / ei.value;
        if (n == 5) invRollOffF = 1 / ei.value;
        if (n == 6) invRollOffR = 1 / ei.value;
        if (n == 7) emissionCoeffF = ei.value;
        if (n == 8) emissionCoeffR = ei.value;
        if (n == 9) BEleakCur = ei.value;
        if (n == 10) leakBEemissionCoeff = ei.value;
        if (n == 11) BCleakCur = ei.value;
        if (n == 12) leakBCemissionCoeff = ei.value;
        updateModel();
        if (circuitDocument != null) {
            circuitDocument.simulator.updateModels();
        }
    }

    @Override
    public void setSimulationContext(CircuitDocument circuitDocument) {
        this.circuitDocument = circuitDocument;
    }

    void updateModel() {
    }

    public String dump() {
        dumped = true;
        return modelLine();
    }

    // ------------------------------------------------------------------ [SP_AGA_01_13] agent models

    /** @return true for an entry the editor hides (parts of built-in chips) */
    public boolean isInternal() {
        return internal;
    }

    /** @return the model flags */
    public int getFlags() {
        return flags;
    }

    /** @return the catalogue entry of that name (built-in, internal or user), or null; never creates one */
    public static TransistorModel findEntry(String name) {
        createModelMap();
        return name == null ? null : modelMap.get(name);
    }

    /** @return every catalogue entry, internal ones included (unordered) */
    public static java.util.List<TransistorModel> entries() {
        createModelMap();
        return new java.util.ArrayList<TransistorModel>(modelMap.values());
    }

    /** @return a copy of {@code base} under {@code name} that is not in the catalogue */
    public static TransistorModel createDetached(String name, TransistorModel base) {
        TransistorModel tm = base == null ? new TransistorModel() : new TransistorModel(base);
        tm.name = name;
        return tm;
    }

    /**
     * Parses the fields of a model line after its name (as {@link #undump} does) into a model
     * that is not in the catalogue.
     *
     * @throws RuntimeException when a field is missing or does not parse
     */
    public static TransistorModel undumpDetached(String name, StringTokenizer st) {
        TransistorModel tm = new TransistorModel();
        tm.name = name;
        tm.undump(st);
        return tm;
    }

    /** Registers a detached model as a new user entry under its name (create-only callers check the name). */
    public static void defineEntry(TransistorModel tm) {
        createModelMap();
        tm.readOnly = tm.builtIn = tm.internal = false;
        modelMap.put(tm.name, tm);
    }

    /**
     * [SP_AGA_03_11] "Identical": the model line {@link #dump()} writes, without marking the
     * entry dumped.
     */
    public String modelLine() {
        return "32 " + CustomLogicModel.escape(name) + " " + flags + " " +
                satCur + " " + invRollOffF + " " + BEleakCur + " " + leakBEemissionCoeff + " " + invRollOffR + " " +
                BCleakCur + " " + leakBCemissionCoeff + " " + emissionCoeffF + " " + emissionCoeffR + " " + invEarlyVoltF + " " + invEarlyVoltR + " " + betaR;
    }
}
