package com.lushprojects.circuitjs1.client.element;

import com.lushprojects.circuitjs1.client.CircuitElmCreator;
import com.lushprojects.circuitjs1.client.CustomLogicModel;
import com.lushprojects.circuitjs1.client.StringTokenizer;

import java.util.HashSet;
import java.util.Set;

/**
 * [SP_AGA_01_13] "Inner references" / [SP_AGA_03_11] "Dependencies": a static, side-effect-free
 * parse of a subcircuit model's node list and element dumps. It reports the class name of every
 * node-list line that {@link CircuitElmCreator#constructElement} cannot create, and the model-name
 * field of every element dump that names a session model (diode family, transistor,
 * {@code CustomLogic}, nested {@code Subcircuit}) — without building an element and without any
 * catalogue access.
 * <p>
 * The i-th node-list line ({@code \r}-separated, class name then nodes) belongs to the i-th
 * element dump (space-separated, escaped, the element's dump without its type and coordinates:
 * flags first), as {@link CompositeElm#loadComposite} pairs them. Where the model-name field sits
 * follows the element constructors that read those dumps:
 * <ul>
 * <li>{@code DiodeElm}, {@code LEDElm}, {@code ZenerElm}, {@code VaractorElm}: the first field
 *     when {@link DiodeElm#FLAG_MODEL} is set (a legacy forward-drop dump names no model);</li>
 * <li>{@code TransistorElm} ({@code N}/{@code P}): the fifth field (after pnp, Vbc, Vbe, beta),
 *     when present;</li>
 * <li>{@code CustomLogicElm}: the first field, or the second when
 *     {@link ChipElm#FLAG_CUSTOM_VOLTAGE} is set (the high voltage comes first);</li>
 * <li>{@code CustomCompositeElm}: the first field; the dumps of its own elements follow, and are
 *     scanned against the nested model's node list when {@link Resolver} gives it.</li>
 * </ul>
 * A dump that is shorter than its node list, or a field that does not parse, ends the scan of
 * that list quietly: the caller's trial build reports such content.
 */
public final class CompositeModelScan {

    /** Receives what the scan finds. */
    public interface Listener {
        /**
         * A model-name field.
         *
         * @param catalogue the element's model catalogue as {@code getJsonModelCatalogue} names
         *                  it: {@code diode}, {@code zener}, {@code transistor}, {@code logic} or
         *                  {@code subcircuit}
         */
        void reference(String catalogue, String name);

        /** A node-list class name that no element class has. */
        void unknownClass(String className);
    }

    /** Gives the node list of a nested subcircuit model, so its element dumps are scanned too. */
    public interface Resolver {
        /** @return the node list of the subcircuit model {@code name}, or null (not scanned further) */
        String nodeList(String name);
    }

    private static final String LINE_DELIMITERS = " +\t\n\r\f";

    private CompositeModelScan() {
    }

    /**
     * Scans one model.
     *
     * @param nodeList the model's node list
     * @param elmDump  the model's element dumps (or an instance's), or null for the class names only
     * @param resolver nested models to descend into, or null (nested dumps are not scanned)
     */
    public static void scan(String nodeList, String elmDump, Resolver resolver, Listener listener) {
        StringTokenizer dumps = elmDump == null ? null : new StringTokenizer(elmDump, " ");
        scanList(nodeList, dumps, true, resolver, listener, new HashSet<String>());
    }

    private static void scanList(String nodeList, StringTokenizer dumps, boolean escape, Resolver resolver,
            Listener listener, Set<String> visiting) {
        if (nodeList == null) {
            return;
        }
        StringTokenizer lines = new StringTokenizer(nodeList, "\r");
        while (lines.hasMoreTokens()) {
            StringTokenizer line = new StringTokenizer(lines.nextToken(), LINE_DELIMITERS);
            if (!line.hasMoreTokens()) {
                continue;
            }
            String cls = line.nextToken();
            if (!CircuitElmCreator.isKnownClassName(cls)) {
                listener.unknownClass(cls);
            }
            if (dumps == null) {
                continue;
            }
            if (!dumps.hasMoreTokens()) {
                // fewer dumps than lines: the trial build reports it
                dumps = null;
                continue;
            }
            String dump = dumps.nextToken();
            if (escape) {
                dump = CustomLogicModel.unescape(dump);
            }
            StringTokenizer st = new StringTokenizer(dump, escape ? " " : "_");
            Integer flags = flags(st);
            if (flags != null) {
                element(cls, flags, st, resolver, listener, visiting);
            }
        }
    }

    /** Reads the model-name field of one element dump (its flags already read). */
    private static void element(String cls, int flags, StringTokenizer st, Resolver resolver, Listener listener,
            Set<String> visiting) {
        switch (cls) {
            case "DiodeElm":
            case "LEDElm":
            case "VaractorElm":
            case "ZenerElm":
                if ((flags & DiodeElm.FLAG_MODEL) != 0 && st.hasMoreTokens()) {
                    listener.reference("ZenerElm".equals(cls) ? "zener" : "diode", CustomLogicModel.unescape(st.nextToken()));
                }
                return;
            case "TransistorElm":
            case "NTransistorElm":
            case "PTransistorElm":
                // pnp, Vbc, Vbe, beta, then the model name (absent in old dumps: "default")
                for (int i = 0; i < 4; i++) {
                    if (!st.hasMoreTokens()) {
                        return;
                    }
                    st.nextToken();
                }
                if (st.hasMoreTokens()) {
                    listener.reference("transistor", CustomLogicModel.unescape(st.nextToken()));
                }
                return;
            case "CustomLogicElm":
                if ((flags & ChipElm.FLAG_CUSTOM_VOLTAGE) != 0 && st.hasMoreTokens()) {
                    st.nextToken();
                }
                if (st.hasMoreTokens()) {
                    listener.reference("logic", CustomLogicModel.unescape(st.nextToken()));
                }
                return;
            default:
                break;
        }
        if ("CustomCompositeElm".equals(cls) || cls.startsWith("CustomCompositeElm:")) {
            if (!st.hasMoreTokens()) {
                return;
            }
            String name = CustomLogicModel.unescape(st.nextToken());
            listener.reference("subcircuit", name);
            String nested = resolver == null || visiting.contains(name) ? null : resolver.nodeList(name);
            if (nested != null) {
                visiting.add(name);
                scanList(nested, st, (flags & CompositeElm.FLAG_ESCAPE) != 0, resolver, listener, visiting);
                visiting.remove(name);
            }
        }
    }

    private static Integer flags(StringTokenizer st) {
        if (!st.hasMoreTokens()) {
            return null;
        }
        try {
            return Integer.parseInt(st.nextToken());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
