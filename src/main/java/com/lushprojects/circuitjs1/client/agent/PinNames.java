package com.lushprojects.circuitjs1.client.agent;

import com.lushprojects.circuitjs1.client.element.CircuitElm;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * [SP_AGA_03_02] "Pin names": the agent-facing PinName list of an element, one name per post,
 * unique within the element. Built from the element's JSON pin names; the file formats keep
 * their own pin names. Used by TypeInfo, ElementRecord, PostRef and issues.
 */
public final class PinNames {

    private PinNames() {
    }

    /** @return the PinName of every post of {@code elm}, indexed by post (0-based) */
    @SuppressWarnings("deprecation")
    public static String[] of(CircuitElm elm) {
        return fromJsonNames(elm.getJsonPinNames(), Math.max(0, elm.getPostCount()));
    }

    /**
     * Applies the PinName rules to raw JSON pin names.
     * <ul>
     * <li>characters outside {@code [A-Za-z0-9_~+-]} become {@code _};</li>
     * <li>a missing or empty name becomes {@code pin<i>} (1-based post number);</li>
     * <li>the k-th occurrence (k &ge; 2) of a name gets {@code _<k>}, e.g. {@code Q}, {@code Q_2};
     *     k is raised further if that form is itself one of the element's names.</li>
     * </ul>
     *
     * @param jsonNames raw names (may be null or shorter than {@code postCount})
     * @param postCount number of posts; the result has exactly this length
     */
    public static String[] fromJsonNames(String[] jsonNames, int postCount) {
        String[] names = new String[postCount];
        Set<String> reserved = new HashSet<>();
        for (int i = 0; i < postCount; i++) {
            String raw = (jsonNames != null && i < jsonNames.length) ? jsonNames[i] : null;
            names[i] = sanitize(raw, i);
            reserved.add(names[i]);
        }
        Map<String, Integer> occurrences = new HashMap<>();
        Set<String> assigned = new HashSet<>();
        for (int i = 0; i < postCount; i++) {
            String base = names[i];
            Integer seen = occurrences.get(base);
            int occurrence = (seen == null) ? 1 : seen + 1;
            occurrences.put(base, occurrence);
            if (occurrence == 1) {
                assigned.add(base);
                continue;
            }
            int k = occurrence;
            String candidate = base + "_" + k;
            while (reserved.contains(candidate) || assigned.contains(candidate)) {
                k++;
                candidate = base + "_" + k;
            }
            names[i] = candidate;
            assigned.add(candidate);
        }
        return names;
    }

    /** Sanitized form of one raw name for post {@code index} (0-based). */
    static String sanitize(String raw, int index) {
        if (raw == null || raw.isEmpty()) {
            return "pin" + (index + 1);
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '~' || c == '+' || c == '-';
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }
}
