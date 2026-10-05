package com.lushprojects.circuitjs1.client.util;

/**
 * [SP_AGA_01_07] Bounded echo of client-supplied text (argument values, property names, IDs,
 * paths, label texts, model names, rule lines) in issue and problem messages: a message quotes at
 * most {@link #MAX} characters of such a value, then an ellipsis and the full length, so a
 * megabyte argument never comes back in full. Values up to the bound are quoted unchanged.
 */
public final class EchoText {

    /** Longest client value quoted as it is. */
    public static final int MAX = 64;
    /** Longest file path or file-system reason quoted as it is (paths are long but informative). */
    public static final int MAX_PATH = 256;

    private EchoText() {
    }

    /** @return {@code value} cut to {@link #MAX} characters, or "null" */
    public static String clip(String value) {
        return clip(value, MAX);
    }

    /** @return {@code value} cut to {@code max} characters plus "… (N chars)", or "null" */
    public static String clip(String value, int max) {
        if (value == null) {
            return "null";
        }
        if (value.length() <= max) {
            return value;
        }
        // never split a surrogate pair
        int cut = Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max;
        return value.substring(0, cut) + "… (" + value.length() + " chars)";
    }
}
