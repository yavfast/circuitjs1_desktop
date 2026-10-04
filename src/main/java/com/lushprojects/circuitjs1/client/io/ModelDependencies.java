package com.lushprojects.circuitjs1.client.io;

import com.lushprojects.circuitjs1.client.CustomCompositeModel;
import com.lushprojects.circuitjs1.client.ElementIdRegistry;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.CompositeModelScan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [SP_AGA_03_11] "Dependencies": the models a circuit uses — the non-built-in session models its
 * elements reference through their model-name keys ({@code model}, {@code model_name}) and,
 * through subcircuit models, the models the element dumps of those subcircuit models reference —
 * each once, dependencies first.
 * <p>
 * One closure for every writer of model definitions: the agent's {@code getCircuit}
 * ([SP_AGA_02_05] {@code models}, capped) and usage scan ({@code usedBy}), and the JSON v2
 * {@code models} section ([SP_AGA_03_12], uncapped). Lives in {@code io/} (L2) so the JSON format
 * can use it; no catalogue is written.
 */
public final class ModelDependencies {

    private ModelDependencies() {
    }

    /** One model reference: its kind ({@link ModelSpecCodec#KINDS}) and name. */
    public static final class Ref {
        public final String kind;
        public final String name;

        public Ref(String kind, String name) {
            this.kind = kind;
            this.name = name;
        }
    }

    /**
     * @return the models an element references directly through its model-name keys
     *         ({@code model}, {@code model_name}; exported or declared conditional properties)
     */
    public static List<Ref> refsOf(CircuitElm elm) {
        List<Ref> refs = new ArrayList<>();
        Map<String, Object> props = null;
        Map<String, Object> conditional = null;
        for (String key : new String[] { "model", "model_name" }) {
            String kind = ModelSpecCodec.kindOfCatalogue(elm.getJsonModelCatalogue(key));
            if (kind == null) {
                continue;
            }
            if (props == null) {
                props = elm.getJsonProperties();
            }
            Object v = props.get(key);
            if (v == null) {
                if (conditional == null) {
                    conditional = elm.getJsonConditionalProperties();
                }
                v = conditional.get(key);
            }
            if (v instanceof String && !((String) v).isEmpty()) {
                refs.add(new Ref(kind, (String) v));
            }
        }
        return refs;
    }

    /**
     * The models the element dumps of the subcircuit model {@code name} reference directly (its own
     * node list and dumps; nested subcircuit models by name — {@link #closure} descends into
     * them), in dump order. Empty when there is no such model.
     */
    public static List<Ref> innerRefs(String name, Map<String, List<Ref>> cache) {
        List<Ref> cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        List<Ref> out = new ArrayList<>();
        cache.put(name, out);
        CustomCompositeModel m = CustomCompositeModel.findEntry(name);
        if (m != null) {
            out.addAll(innerRefsOf(m));
        }
        return out;
    }

    /** @return the models the element dumps of {@code m} reference directly, in dump order */
    public static List<Ref> innerRefsOf(CustomCompositeModel m) {
        final List<Ref> out = new ArrayList<>();
        if (m == null || m.nodeList == null) {
            return out;
        }
        CompositeModelScan.scan(m.nodeList, m.elmDump, null, new CompositeModelScan.Listener() {
            @Override
            public void reference(String catalogue, String n) {
                String kind = ModelSpecCodec.kindOfCatalogue(catalogue);
                if (kind != null && n != null && !n.isEmpty()) {
                    out.add(new Ref(kind, n));
                }
            }

            @Override
            public void unknownClass(String className) {
            }
        });
        return out;
    }

    /**
     * {@code r} and every model it depends on through subcircuit models, dependencies first
     * (post-order), each once; {@code seen} holds the keys already visited (it also stops a cycle);
     * {@code cache} keeps the inner references read per model.
     */
    public static void closure(Ref r, List<String> seen, List<Ref> out, Map<String, List<Ref>> cache) {
        String key = r.kind + "\u0000" + r.name;
        if (seen.contains(key)) {
            return;
        }
        seen.add(key);
        if (ModelSpecCodec.SUBCIRCUIT.equals(r.kind)) {
            for (Ref inner : innerRefs(r.name, cache)) {
                closure(inner, seen, out, cache);
            }
        }
        out.add(r);
    }

    /** @return the models an element uses: its direct references and their closure, each once */
    public static List<Ref> usedModels(CircuitElm elm, Map<String, List<Ref>> cache) {
        List<Ref> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Ref r : refsOf(elm)) {
            closure(r, seen, out, cache);
        }
        return out;
    }

    /**
     * [SP_AGA_03_11] "Dependencies" of a circuit: the non-built-in catalogue entries that
     * {@code elms} (in the order given) use, each once, dependencies first, otherwise in order of
     * first use. Names no catalogue holds are left out.
     */
    public static List<Ref> circuitModels(List<CircuitElm> elms) {
        List<String> seen = new ArrayList<>();
        List<Ref> ordered = new ArrayList<>();
        Map<String, List<Ref>> cache = new HashMap<>();
        for (CircuitElm elm : elms) {
            for (Ref r : refsOf(elm)) {
                closure(r, seen, ordered, cache);
            }
        }
        List<Ref> out = new ArrayList<>();
        for (Ref r : ordered) {
            Object entry = ModelSpecCodec.entry(r.kind, r.name);
            if (entry != null && !ModelSpecCodec.isBuiltIn(entry)) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * [SP_AGA_02_05] "Ordering" of element records: by element ID
     * ({@link ElementIdRegistry#compareIds}). "First use" of a model follows this order, so the
     * JSON {@code models} section and {@code getCircuit} list the models alike.
     */
    public static void sortById(List<CircuitElm> elms) {
        final Map<CircuitElm, String> ids = new HashMap<>();
        for (CircuitElm e : elms) {
            ids.put(e, e.getElementId());
        }
        Collections.sort(elms, new Comparator<CircuitElm>() {
            @Override
            public int compare(CircuitElm a, CircuitElm b) {
                return ElementIdRegistry.compareIds(ids.get(a), ids.get(b));
            }
        });
    }
}
