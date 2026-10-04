package com.lushprojects.circuitjs1.client.agent;

import com.lushprojects.circuitjs1.client.CustomLogicModel;
import com.lushprojects.circuitjs1.client.DiodeModel;
import com.lushprojects.circuitjs1.client.io.ModelSpecCodec;

import java.util.ArrayList;
import java.util.List;

/**
 * [SP_AGA_03_03] "Model names": the session model catalogues a text property can name
 * ({@code CircuitElm.getJsonModelCatalogue}: {@code diode}, {@code zener}, {@code transistor},
 * {@code logic}, {@code subcircuit}). Agent paths accept a name only when the catalogue lists it
 * (or an earlier {@code defineModel} / {@code models} entry of the same call defines it), so a
 * typo is rejected instead of simulating with a fallback model and registering a new
 * session-wide entry (the element's own fallback, kept for user loads).
 */
final class ModelNames {

    /** Definitions of the call being validated (session ∪ batch), or null outside a validation. */
    private static ModelOps.Scope pending;

    private ModelNames() {
    }

    /**
     * Makes the new definitions of {@code scope} visible to {@link #exists} until
     * {@link #endScope()} (the validation of one batch or import).
     */
    static void beginScope(ModelOps.Scope scope) {
        pending = scope;
    }

    static void endScope() {
        pending = null;
    }

    /**
     * [SP_AGA_01_13] The logic {@code default} entry exists before any name check or listing,
     * created as the editor creates it.
     */
    static void ensureDefaults() {
        CustomLogicModel.ensureDefault();
    }

    /** @return the model kind of a property's catalogue ({@code zener} → {@code diode}), or null */
    static String kindOf(String catalogue) {
        if ("diode".equals(catalogue) || "zener".equals(catalogue)) {
            return ModelSpecCodec.DIODE;
        }
        if (ModelSpecCodec.isKind(catalogue)) {
            return catalogue;
        }
        return null;
    }

    /** @return the words naming the catalogue in messages ("diode", "zener diode", "logic") */
    static String label(String catalogue) {
        return "zener".equals(catalogue) ? "zener diode" : String.valueOf(kindOf(catalogue));
    }

    /**
     * @return the names an agent may choose from, as {@code listModels} orders them (built-in
     *         first, then by name): the listed entries of the catalogue's kind; zener: only models
     *         with a breakdown voltage. Internal entries never.
     */
    static List<String> list(String catalogue) {
        List<String> names = new ArrayList<>();
        String kind = kindOf(catalogue);
        if (kind == null) {
            return names;
        }
        ensureDefaults();
        for (Object e : ModelSpecCodec.listed(kind)) {
            if ("zener".equals(catalogue) && ((DiodeModel) e).breakdownVoltage <= 0) {
                continue;
            }
            names.add(ModelSpecCodec.nameOf(e));
        }
        return names;
    }

    /**
     * @return true when {@code name} is one of {@link #list} now, or a new model of the call being
     *         validated (a zener takes only models with a breakdown voltage)
     */
    static boolean exists(String catalogue, String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String kind = kindOf(catalogue);
        if (kind == null) {
            return true;
        }
        if (pending != null) {
            ModelSpecCodec.Definition d = pending.pending(kind, name);
            if (d != null) {
                return !"zener".equals(catalogue) || (d.diode() != null && d.diode().breakdownVoltage > 0);
            }
            return pending.listed(catalogue).contains(name);
        }
        return list(catalogue).contains(name);
    }

    /** @return the hint of an unknown model name: the available names */
    static String hint(String catalogue) {
        List<String> names = list(catalogue);
        return "Available " + label(catalogue) + " models: " + String.join(", ", names)
                + " (describeType lists them as the key's choices; define a new one with defineModel or the circuit's models).";
    }
}
