package com.lushprojects.circuitjs1.client.agent;

import com.lushprojects.circuitjs1.client.DiodeModel;
import com.lushprojects.circuitjs1.client.TransistorModel;

import java.util.ArrayList;
import java.util.List;

/**
 * [SP_AGA_03_03] "Model names": the session model catalogues a text property can name
 * ({@code CircuitElm.getJsonModelCatalogue}). Agent paths accept a name only when the catalogue
 * holds it, so a typo is rejected instead of simulating with the default model and registering a
 * new session-wide entry (the element's own fallback, kept for user loads).
 */
final class ModelNames {

    private ModelNames() {
    }

    /**
     * @return the names an agent may choose from, in catalogue order: the entries the user's model
     *         menu lists (internal entries left out); zener: only models with a breakdown voltage
     */
    static List<String> list(String catalogue) {
        List<String> names = new ArrayList<>();
        if ("diode".equals(catalogue) || "zener".equals(catalogue)) {
            for (DiodeModel dm : DiodeModel.getModelList("zener".equals(catalogue))) {
                names.add(dm.name);
            }
        } else if ("transistor".equals(catalogue)) {
            for (TransistorModel tm : TransistorModel.getModelList()) {
                names.add(tm.name);
            }
        }
        return names;
    }

    /**
     * @return true when {@code name} is one of {@link #list} now: the accepted set equals the
     *         TypeInfo {@code choices} (a zener takes only models with a breakdown voltage)
     */
    static boolean exists(String catalogue, String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (!"diode".equals(catalogue) && !"zener".equals(catalogue) && !"transistor".equals(catalogue)) {
            return true;
        }
        return list(catalogue).contains(name);
    }

    /** @return the hint of an unknown model name: the available names */
    static String hint(String catalogue) {
        List<String> names = list(catalogue);
        return "Available " + ("transistor".equals(catalogue) ? "transistor" : "diode") + " models: "
                + String.join(", ", names) + " (describeType lists them as the key's choices; a legacy text import may define more with model lines).";
    }
}
