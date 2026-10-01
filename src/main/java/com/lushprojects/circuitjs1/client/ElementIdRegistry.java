package com.lushprojects.circuitjs1.client;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * [SP_AGA_03_02] Per-document element ID counters: the single source of generated element IDs.
 *
 * <p>Owned by {@link CircuitDocument} and reached only through its methods
 * ({@code nextElementId}, {@code raiseIdCounter}, {@code resetElementIds},
 * {@code settleElementIds}). Counters are keyed by a letters-only prefix; within one content
 * lifetime ([SP_AGA_04_03]) they never decrease, and every ID with a counter that enters the
 * document raises its counter.
 */
public final class ElementIdRegistry {

    /** Maximum length of an ElementId (pattern {@code ^[A-Za-z][A-Za-z0-9_]{0,31}$}, SP_AGA_01_02). */
    public static final int MAX_ID_LENGTH = 32;

    /**
     * Numbers with more digits than this do not raise a counter (they would overflow it).
     * Generation still skips such IDs while present, so they cannot be duplicated.
     */
    private static final int MAX_COUNTER_DIGITS = 9;

    private final Map<String, Integer> counters = new HashMap<>();

    /**
     * IDs to give the elements of the next import, in element order (set by an undo/redo restore,
     * consumed by {@link CircuitDocument#settleElementIds()}); null when no restore is pending.
     */
    private String[] pendingRestore;

    /** Starts a new content lifetime: all counters go back to zero. */
    void reset() {
        counters.clear();
    }

    /** counter[prefix] = max(counter[prefix], number) when {@code id} has a counter. */
    void raise(String id) {
        String prefix = counterPrefix(id);
        if (prefix == null) {
            return;
        }
        String digits = id.substring(prefix.length());
        if (digits.length() > MAX_COUNTER_DIGITS) {
            return;
        }
        int number = Integer.parseInt(digits);
        Integer current = counters.get(prefix);
        if (current == null || current < number) {
            counters.put(prefix, number);
        }
    }

    /**
     * Generates {@code <prefix><n>} with n = counter[prefix] + 1, skipping every n whose ID is in
     * {@code present}, and advances the counter to the issued n.
     *
     * @param prefix letters-only ID prefix (other characters are dropped; empty becomes "E")
     * @param present IDs currently present in the document (not modified)
     */
    String next(String prefix, Set<String> present) {
        prefix = lettersOnly(prefix);
        Integer current = counters.get(prefix);
        int n = (current == null) ? 1 : current + 1;
        String id = prefix + n;
        while (present.contains(id)) {
            n++;
            id = prefix + n;
        }
        counters.put(prefix, n);
        return id;
    }

    void setPendingRestore(String[] ids) {
        pendingRestore = ids;
    }

    boolean hasPendingRestore() {
        return pendingRestore != null;
    }

    /** @return the pending restore IDs (or null) and clears them */
    String[] takePendingRestore() {
        String[] ids = pendingRestore;
        pendingRestore = null;
        return ids;
    }

    /** @return true when {@code id} matches the ElementId pattern {@code ^[A-Za-z][A-Za-z0-9_]{0,31}$} */
    public static boolean isValidId(String id) {
        if (id == null || id.isEmpty() || id.length() > MAX_ID_LENGTH || !isAsciiLetter(id.charAt(0))) {
            return false;
        }
        for (int i = 1; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!isAsciiLetter(c) && !(c >= '0' && c <= '9') && c != '_') {
                return false;
            }
        }
        return true;
    }

    /**
     * @return the counter prefix (group 1 of {@code ^([A-Za-z]+)([0-9]+)$}) of {@code id}, or null
     *         when the ID has no counter
     */
    public static String counterPrefix(String id) {
        if (id == null) {
            return null;
        }
        int i = 0;
        while (i < id.length() && isAsciiLetter(id.charAt(i))) {
            i++;
        }
        if (i == 0 || i == id.length()) {
            return null;
        }
        for (int j = i; j < id.length(); j++) {
            char c = id.charAt(j);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        return id.substring(0, i);
    }

    /**
     * Letters-only form of an ID prefix: characters outside A–Z/a–z are dropped; an empty result
     * becomes "E" so that a generated ID always matches the ElementId pattern.
     */
    public static String lettersOnly(String prefix) {
        StringBuilder sb = new StringBuilder();
        if (prefix != null) {
            for (int i = 0; i < prefix.length(); i++) {
                char c = prefix.charAt(i);
                if (isAsciiLetter(c)) {
                    sb.append(c);
                }
            }
        }
        return sb.length() == 0 ? "E" : sb.toString();
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }
}
