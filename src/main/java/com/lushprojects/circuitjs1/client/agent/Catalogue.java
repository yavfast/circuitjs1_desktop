package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONBoolean;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;
import com.google.gwt.json.client.JSONValue;
import com.lushprojects.circuitjs1.client.BaseCirSim;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.CircuitEditor;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.LastUsedValues;
import com.lushprojects.circuitjs1.client.io.json.CircuitElementFactory;
import com.lushprojects.circuitjs1.client.io.json.UnitParser;
import com.lushprojects.circuitjs1.client.util.Locale;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * [SP_AGA_02_01] The element catalogue: one {@link TypeInfo} ([SP_AGA_01_05]) per canonical JSON
 * type name, measured from the element factory. Session-scoped (cached on {@code BaseCirSim},
 * RULE_ARCH_006) and built once, on first use.
 * <p>
 * Measurement: every factory key is created through {@link CircuitElementFactory} with a
 * session scratch document (default options, never listed, bound or shown) as owner and is
 * never inserted into any element list (no ID is generated, no slider or scope is created).
 * Defaults are the built-in ones: the element classes' last-used values are reset to their
 * initial values, and translation is suspended so labels, summaries and text defaults are
 * English, for the duration of the build. Keys producing the same canonical name are aliases of it,
 * whatever their registration order. The instance measured for a type is the one of the key
 * equal to the canonical name (the key JSON import uses), else of the first key in sorted order.
 * It is placed the way the editor places an element — dragged out from (0,0), or kept at its
 * own size when it is fixed-size on creation — with the editor grid pinned to 16 px
 * ([SP_AGA_03_01]).
 */
public final class Catalogue {

    /** Editor pixels per cell ([SP_AGA_01_01]). */
    static final int CELL_PX = 16;

    /**
     * Default placement: a drag of this many cells to the right of the start point (parts are
     * 3-4 cells long); a type whose creation fails on a straight drag (a box needs a height) is
     * dragged the same length diagonally.
     */
    static final int DEFAULT_DRAG_CELLS = 4;

    /** Number of closest names listed in an {@code unknown_type} hint. */
    static final int HINT_NAMES = 5;

    /** Upper bound of editable-parameter entries scanned per element. */
    private static final int MAX_EDIT_ITEMS = 64;

    /** Unit suffixes of JSON quantity strings, longer suffixes before their one-letter tails. */
    private static final String[] UNITS = { "Ohm", "Ω", "Hz", "F", "H", "V", "A", "W", "s", "m" };

    /** SI prefixes of JSON quantity strings ({@code UnitParser}). */
    private static final String[] PREFIXES = { "", "f", "p", "n", "u", "μ", "m", "k", "K", "M", "G", "T" };

    /** [SP_AGA_01_05] PropertyInfo. */
    static final class PropertyInfo {
        String key;
        /** quantity | number | bool | text */
        String kind;
        /** Default as exported: a unit string (quantity), Double (number), Boolean or String. */
        Object def;
        String unit;
        String label;
        boolean hasSlider;
        double sliderMin, sliderMax;
        boolean readOnly;

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("key", new JSONString(key));
            o.put("kind", new JSONString(kind));
            o.put("default", jsonValue(def));
            if (unit != null) {
                o.put("unit", new JSONString(unit));
            }
            if (label != null) {
                o.put("label", new JSONString(label));
            }
            if (hasSlider) {
                o.put("sliderMin", new JSONNumber(sliderMin));
                o.put("sliderMax", new JSONNumber(sliderMax));
            }
            if (readOnly) {
                o.put("readOnly", JSONBoolean.getInstance(true));
            }
            return o;
        }
    }

    /** [SP_AGA_01_05] TypeInfo, plus the index summary (menu label). */
    static final class TypeInfo {
        String type;
        final List<String> aliases = new ArrayList<>();
        String dumpCode;
        String idPrefix;
        /** single | two_point | derived */
        String geometry;
        String[] pins;
        double dx, dy;
        /** For {@code derived}: pin → {x, y} offset from start in cells, in post order. */
        final Map<String, double[]> derivedPosts = new LinkedHashMap<>();
        final List<PropertyInfo> properties = new ArrayList<>();
        int defaultFlags;
        String summary;

        /** @return the property with this key, or null */
        PropertyInfo property(String key) {
            for (PropertyInfo p : properties) {
                if (p.key.equals(key)) {
                    return p;
                }
            }
            return null;
        }

        /** @return the property keys in catalogue order, comma-separated (for hints) */
        String propertyKeys() {
            return keys(false);
        }

        /** @return the keys {@code set} accepts (read-only keys left out), comma separated */
        String writableKeys() {
            return keys(true);
        }

        private String keys(boolean writableOnly) {
            StringBuilder sb = new StringBuilder();
            for (PropertyInfo p : properties) {
                if (writableOnly && p.readOnly) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(p.key);
            }
            return sb.length() == 0 ? "(none)" : sb.toString();
        }

        /** @return the index form {type, aliases, pins, geometry, summary} */
        JSONObject toIndexJson() {
            JSONObject o = new JSONObject();
            o.put("type", new JSONString(type));
            o.put("aliases", stringArray(aliases));
            o.put("pins", stringArray(pins));
            o.put("geometry", new JSONString(geometry));
            o.put("summary", new JSONString(summary));
            return o;
        }

        /** @return the full TypeInfo */
        JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("type", new JSONString(type));
            o.put("aliases", stringArray(aliases));
            o.put("dumpCode", new JSONString(dumpCode == null ? "" : dumpCode));
            o.put("idPrefix", new JSONString(idPrefix));
            o.put("geometry", new JSONString(geometry));
            o.put("pins", stringArray(pins));
            JSONObject size = new JSONObject();
            size.put("dx", new JSONNumber(dx));
            size.put("dy", new JSONNumber(dy));
            o.put("defaultSize", size);
            if ("derived".equals(geometry)) {
                JSONObject posts = new JSONObject();
                for (Map.Entry<String, double[]> e : derivedPosts.entrySet()) {
                    JSONObject at = new JSONObject();
                    at.put("x", new JSONNumber(e.getValue()[0]));
                    at.put("y", new JSONNumber(e.getValue()[1]));
                    posts.put(e.getKey(), at);
                }
                o.put("derivedPostsAtDefault", posts);
            }
            JSONArray props = new JSONArray();
            for (int i = 0; i < properties.size(); i++) {
                props.set(i, properties.get(i).toJson());
            }
            o.put("properties", props);
            o.put("defaultFlags", new JSONNumber(defaultFlags));
            return o;
        }
    }

    /** Types by canonical name, sorted by name. */
    private final TreeMap<String, TypeInfo> types = new TreeMap<>();
    /** Every type name and alias → its type. */
    private final Map<String, TypeInfo> byName = new HashMap<>();

    private Catalogue() {
    }

    /**
     * Measures every factory key ([SP_AGA_02_01] buildCatalogue). Called once per session by
     * {@code BaseCirSim.getAgentCatalogue()}.
     *
     * @param owner the scratch document that owns the measured elements
     */
    public static Catalogue build(BaseCirSim sim, CircuitDocument owner) {
        Catalogue cat = new Catalogue();
        CircuitEditor editor = owner.circuitEditor;
        int savedSize = editor.gridSize;
        int savedMask = editor.gridMask;
        int savedRound = editor.gridRound;
        HashMap<String, String> savedLocale = Locale.localizationMap;
        Locale.localizationMap = new HashMap<>();
        LastUsedValues savedLastUsed = LastUsedValues.resetToInitial();
        editor.gridSize = CELL_PX;
        editor.gridMask = -CELL_PX;
        editor.gridRound = CELL_PX / 2 - 1;
        List<CircuitElm> created = new ArrayList<>();
        try {
            List<String> keys = CircuitElementFactory.getAllJsonTypeNames();
            Collections.sort(keys);
            // canonical name -> (factory key -> fresh instance), keys in sorted order
            TreeMap<String, LinkedHashMap<String, CircuitElm>> byCanonical = new TreeMap<>();
            for (String key : keys) {
                CircuitElm elm = CircuitElementFactory.createDefault(key, owner, 0, 0);
                if (elm == null) {
                    continue;
                }
                created.add(elm);
                String canonical;
                try {
                    canonical = elm.getJsonTypeName();
                } catch (RuntimeException e) {
                    continue;
                }
                if (canonical == null || canonical.isEmpty()) {
                    continue;
                }
                LinkedHashMap<String, CircuitElm> group = byCanonical.get(canonical);
                if (group == null) {
                    group = new LinkedHashMap<>();
                    byCanonical.put(canonical, group);
                }
                group.put(key, elm);
            }
            for (Map.Entry<String, LinkedHashMap<String, CircuitElm>> g : byCanonical.entrySet()) {
                String canonical = g.getKey();
                LinkedHashMap<String, CircuitElm> group = g.getValue();
                CircuitElm elm = group.containsKey(canonical) ? group.get(canonical) : group.values().iterator().next();
                TypeInfo info;
                try {
                    info = measure(elm);
                } catch (RuntimeException e) {
                    // A type that cannot be placed at all is left out of the catalogue.
                    continue;
                }
                info.type = canonical;
                for (String key : group.keySet()) {
                    if (!key.equals(canonical)) {
                        info.aliases.add(key);
                    }
                }
                info.summary = summaryOf(sim, elm, group);
                cat.types.put(canonical, info);
            }
        } finally {
            editor.gridSize = savedSize;
            editor.gridMask = savedMask;
            editor.gridRound = savedRound;
            for (CircuitElm elm : created) {
                discard(elm);
            }
            savedLastUsed.restore();
            Locale.localizationMap = savedLocale;
        }
        for (TypeInfo info : cat.types.values()) {
            cat.byName.put(info.type, info);
        }
        for (TypeInfo info : cat.types.values()) {
            for (String alias : info.aliases) {
                if (!cat.byName.containsKey(alias)) {
                    cat.byName.put(alias, info);
                }
            }
        }
        return cat;
    }

    /** @return the type with this canonical name or alias, or null */
    TypeInfo find(String name) {
        return name == null ? null : byName.get(name);
    }

    /** @return the types whose name, an alias or summary contains {@code filter} (any case), sorted by type */
    List<TypeInfo> list(String filter) {
        List<TypeInfo> out = new ArrayList<>();
        String f = filter == null ? "" : filter.toLowerCase();
        for (TypeInfo info : types.values()) {
            if (f.isEmpty() || matches(info, f)) {
                out.add(info);
            }
        }
        return out;
    }

    /** @return the number of catalogue types */
    int size() {
        return types.size();
    }

    /**
     * @return up to {@code max} type names and aliases closest to {@code name} by edit distance
     *         (case-insensitive), nearest first, ties in name order
     */
    List<String> closestNames(String name, int max) {
        List<String> names = new ArrayList<>(byName.keySet());
        Collections.sort(names);
        final Map<String, Integer> dist = new HashMap<>();
        // the query is client text: bound it so the edit distances stay cheap (MCP is LAN-exposed)
        String q = name == null ? "" : clipName(name).toLowerCase();
        for (String n : names) {
            dist.put(n, editDistance(q, n.toLowerCase()));
        }
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted, (a, b) -> {
            int c = Integer.compare(dist.get(a), dist.get(b));
            return c != 0 ? c : a.compareTo(b);
        });
        return sorted.subList(0, Math.min(max, sorted.size()));
    }

    /** Longest client-supplied name echoed in an issue message or compared by edit distance. */
    static final int MAX_ECHOED_NAME = 64;

    /** @return {@code name} cut to {@link #MAX_ECHOED_NAME} characters (with an ellipsis when cut) */
    static String clipName(String name) {
        return name.length() > MAX_ECHOED_NAME ? name.substring(0, MAX_ECHOED_NAME) + "…" : name;
    }

    // ---------------------------------------------------------------- measurement

    private static TypeInfo measure(CircuitElm elm) {
        TypeInfo info = new TypeInfo();
        info.dumpCode = elm.getDumpCode();
        info.idPrefix = elm.getIdPrefix();

        place(elm);
        // Flags of the freshly placed element: a placement drag may set orientation bits
        // (tri-state flip, transformer vertical), and the default posts are measured with them.
        info.defaultFlags = elm.getJsonFlags();
        int x1 = elm.getX(), y1 = elm.getY();
        info.dx = (elm.getX2() - x1) / (double) CELL_PX;
        info.dy = (elm.getY2() - y1) / (double) CELL_PX;
        info.pins = PinNames.of(elm);

        int postCount = info.pins.length;
        Point[] posts = new Point[postCount];
        for (int i = 0; i < postCount; i++) {
            posts[i] = elm.getPost(i);
        }
        info.geometry = geometryOf(posts, x1, y1, elm.getX2(), elm.getY2());
        if ("derived".equals(info.geometry)) {
            for (int i = 0; i < postCount; i++) {
                if (posts[i] != null) {
                    info.derivedPosts.put(info.pins[i],
                            new double[] { (posts[i].x - x1) / (double) CELL_PX, (posts[i].y - y1) / (double) CELL_PX });
                }
            }
        }
        measureProperties(elm, info);
        return info;
    }

    /**
     * Drags the element out from its start point the way the editor's placement does: an element
     * that is fixed-size on creation keeps its constructor size (CircuitEditor.onMouseDrag).
     */
    private static void place(CircuitElm elm) {
        if (elm.isFixedSizeOnCreate()) {
            elm.dragFixedSize(elm.getX(), elm.getY());
            return;
        }
        int len = DEFAULT_DRAG_CELLS * CELL_PX;
        elm.drag(elm.getX() + len, elm.getY());
        if (elm.creationFailed()) {
            elm.drag(elm.getX() + len, elm.getY() + len);
        }
    }

    /**
     * {@code single}: one post at start; {@code two_point}: two posts at start and end;
     * {@code derived}: anything else (posts computed from the two points, or no posts).
     */
    private static String geometryOf(Point[] posts, int x1, int y1, int x2, int y2) {
        if (posts.length == 1 && at(posts[0], x1, y1)) {
            return "single";
        }
        if (posts.length == 2 && at(posts[0], x1, y1) && at(posts[1], x2, y2)) {
            return "two_point";
        }
        return "derived";
    }

    private static boolean at(Point p, int x, int y) {
        return p != null && p.x == x && p.y == y;
    }

    /** [SP_AGA_03_03] key source: exported keys of the fresh instance ∪ declared conditional keys. */
    private static void measureProperties(CircuitElm elm, TypeInfo info) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>(elm.getJsonProperties());
        for (Map.Entry<String, Object> e : elm.getJsonConditionalProperties().entrySet()) {
            if (!values.containsKey(e.getKey())) {
                values.put(e.getKey(), e.getValue());
            }
        }
        Set<String> readOnly = elm.getJsonReadOnlyProperties();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            PropertyInfo p = describeValue(e.getKey(), e.getValue());
            if (p == null) {
                // Structured values (lists, maps) are not agent properties.
                continue;
            }
            p.readOnly = readOnly.contains(p.key);
            info.properties.add(p);
        }
        matchEditEntries(info.properties, editEntries(elm));
    }

    /**
     * @return the PropertyInfo (kind, unit, default) of an exported property value, or null for
     *         a structured value (list, map), which is no agent property
     */
    static PropertyInfo describeValue(String key, Object value) {
        PropertyInfo p = new PropertyInfo();
        p.key = key;
        if (value instanceof Boolean) {
            p.kind = "bool";
            p.def = value;
        } else if (value instanceof Number) {
            p.kind = "number";
            p.def = ((Number) value).doubleValue();
        } else if (value instanceof String) {
            String s = (String) value;
            String unit = quantityUnit(s);
            p.kind = unit != null ? "quantity" : "text";
            p.unit = unit;
            p.def = s;
        } else {
            return null;
        }
        return p;
    }

    /** @return the unit suffix of a JSON quantity string ("4.7 kOhm" → "Ohm"), or null */
    static String quantityUnit(String s) {
        int sp = s.lastIndexOf(' ');
        if (sp <= 0 || sp == s.length() - 1) {
            return null;
        }
        String suffix = s.substring(sp + 1);
        try {
            Double.parseDouble(s.substring(0, sp));
        } catch (NumberFormatException e) {
            return null;
        }
        for (String unit : UNITS) {
            if (suffix.endsWith(unit)) {
                String prefix = suffix.substring(0, suffix.length() - unit.length());
                for (String pre : PREFIXES) {
                    if (pre.equals(prefix)) {
                        return unit;
                    }
                }
            }
        }
        // A compound unit without prefix handling ("0.6 V/us"): the whole suffix is the unit.
        return suffix.indexOf('/') > 0 ? suffix : null;
    }

    private static List<EditInfo> editEntries(CircuitElm elm) {
        List<EditInfo> list = new ArrayList<>();
        try {
            for (int n = 0; n < MAX_EDIT_ITEMS; n++) {
                EditInfo ei = elm.getEditInfo(n);
                if (ei == null) {
                    break;
                }
                list.add(ei);
            }
        } catch (RuntimeException e) {
            // keep the entries read so far; hints are optional
        }
        return list;
    }

    /**
     * label/sliderMin/sliderMax from the editable-parameter entries, one-to-one ([SP_AGA_01_05]):
     * a key whose default equals the value of exactly one entry gets it, unless another key
     * matches that entry too; {@code bool} keys are not matched.
     */
    private static void matchEditEntries(List<PropertyInfo> props, List<EditInfo> entries) {
        Map<PropertyInfo, EditInfo> candidate = new HashMap<>();
        Map<EditInfo, Integer> claims = new HashMap<>();
        for (PropertyInfo p : props) {
            if ("bool".equals(p.kind)) {
                continue;
            }
            EditInfo match = null;
            int count = 0;
            for (EditInfo ei : entries) {
                if (valueMatches(p, ei)) {
                    match = ei;
                    count++;
                }
            }
            if (count == 1) {
                candidate.put(p, match);
                Integer c = claims.get(match);
                claims.put(match, c == null ? 1 : c + 1);
            }
        }
        for (PropertyInfo p : props) {
            EditInfo ei = candidate.get(p);
            if (ei == null || claims.get(ei) != 1) {
                continue;
            }
            String label = plainText(ei.name);
            if (!label.isEmpty()) {
                p.label = label;
            }
            boolean numeric = "quantity".equals(p.kind) || "number".equals(p.kind);
            // no seeds when the entry has sliders disabled or a degenerate pair (min = max)
            if (numeric && ei.canCreateAdjustable() && ei.minVal != ei.maxVal) {
                p.hasSlider = true;
                p.sliderMin = ei.minVal;
                p.sliderMax = ei.maxVal;
            }
        }
    }

    /** Label text without markup: tags removed, common entities decoded, spaces collapsed. */
    static String plainText(String html) {
        if (html == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(html.length());
        boolean inTag = false;
        for (int i = 0; i < html.length(); i++) {
            char c = html.charAt(i);
            if (c == '<') {
                inTag = true;
            } else if (c == '>' && inTag) {
                inTag = false;
            } else if (!inTag) {
                sb.append(c);
            }
        }
        String t = sb.toString().replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&amp;", "&");
        return t.trim().replaceAll("\\s+", " ");
    }

    private static boolean valueMatches(PropertyInfo p, EditInfo ei) {
        boolean plainNumber = ei.checkbox == null && ei.choice == null && ei.text == null && ei.textArea == null
                && ei.button == null && ei.widget == null;
        switch (p.kind) {
            case "text":
                return ei.text != null && ei.text.equals(p.def);
            case "number":
                return plainNumber && sameNumber(ei.value, (Double) p.def);
            default: // quantity
                return plainNumber && sameNumber(ei.value, UnitParser.parse((String) p.def));
        }
    }

    private static boolean sameNumber(double a, double b) {
        if (a == b) {
            return true;
        }
        double scale = Math.max(Math.abs(a), Math.abs(b));
        return Math.abs(a - b) <= scale * 1e-12;
    }

    /** English menu label of the measured class, else of any other key's class, else the type name. */
    private static String summaryOf(BaseCirSim sim, CircuitElm measured, Map<String, CircuitElm> group) {
        String label = sim.getEnglishLabelForClass(simpleClassName(measured));
        for (CircuitElm elm : group.values()) {
            if (label != null) {
                break;
            }
            label = sim.getEnglishLabelForClass(simpleClassName(elm));
        }
        return label != null ? label : measured.getJsonTypeName();
    }

    private static String simpleClassName(CircuitElm elm) {
        String n = elm.getClass().getName();
        int dot = n.lastIndexOf('.');
        return dot >= 0 ? n.substring(dot + 1) : n;
    }

    private static void discard(CircuitElm elm) {
        try {
            elm.delete();
        } catch (RuntimeException e) {
            // never inserted anywhere: nothing else to release
        }
    }

    // ---------------------------------------------------------------- helpers

    private static boolean matches(TypeInfo info, String lowerFilter) {
        if (info.type.toLowerCase().contains(lowerFilter) || info.summary.toLowerCase().contains(lowerFilter)) {
            return true;
        }
        for (String alias : info.aliases) {
            if (alias.toLowerCase().contains(lowerFilter)) {
                return true;
            }
        }
        return false;
    }

    static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    static JSONValue jsonValue(Object v) {
        if (v instanceof Boolean) {
            return JSONBoolean.getInstance((Boolean) v);
        }
        if (v instanceof Number) {
            return new JSONNumber(((Number) v).doubleValue());
        }
        return new JSONString(String.valueOf(v));
    }

    private static JSONArray stringArray(List<String> values) {
        JSONArray a = new JSONArray();
        for (int i = 0; i < values.size(); i++) {
            a.set(i, new JSONString(values.get(i)));
        }
        return a;
    }

    private static JSONArray stringArray(String[] values) {
        JSONArray a = new JSONArray();
        for (int i = 0; i < values.length; i++) {
            a.set(i, new JSONString(values[i]));
        }
        return a;
    }
}
