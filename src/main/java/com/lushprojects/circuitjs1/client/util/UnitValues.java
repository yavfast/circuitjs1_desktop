package com.lushprojects.circuitjs1.client.util;

/**
 * [SP_AGA_03_03] Strict parsing of a number written with an optional SI prefix and an optional
 * unit suffix ({@code "4.7k"}, {@code "10 uF"}, {@code "2.1 V"}, {@code "1e-16 A"}). Anything that
 * is not exactly of that form is rejected (null) — unlike the lenient JSON {@code UnitParser},
 * whose "0 on failure" result must never be taken as a value.
 * <p>
 * The value is computed from the decimal text ({@code <mantissa>e<exponent + prefix>}), so it is
 * the correctly rounded double of what was written and agrees with the lossless JSON unit text
 * ({@code getJsonUnitText}, RULE_STYLE_010). Stateless (L0): used by the agent property values
 * and by the model codec ({@code io/ModelSpecCodec}).
 */
public final class UnitValues {

    private static final String[] PREFIXES = { "f", "p", "n", "u", "μ", "m", "k", "K", "M", "G", "T" };
    private static final int[] PREFIX_EXP = { -15, -12, -9, -6, -6, -3, 3, 3, 6, 9, 12 };

    private UnitValues() {
    }

    /**
     * Parses {@code <number>[ ][prefix][unit]} fully; {@code unit} may be null (no unit allowed,
     * a prefix alone still is). Matching of the unit is case-sensitive, except that {@code Ohm}
     * and {@code Ω} are both accepted for resistance.
     *
     * @return the finite value, or null when the text is not of that form
     */
    public static Double parse(String text, String unit) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        int i = 0;
        int n = s.length();
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            i++;
        }
        int digits = 0;
        while (i < n && Character.isDigit(s.charAt(i))) {
            i++;
            digits++;
        }
        if (i < n && s.charAt(i) == '.') {
            i++;
            while (i < n && Character.isDigit(s.charAt(i))) {
                i++;
                digits++;
            }
        }
        if (digits == 0) {
            return null;
        }
        String mantissa = s.substring(0, i);
        int exp = 0;
        // an exponent needs digits after 'e' ("5e3"); "5 e" is not one
        if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            int j = i + 1;
            if (j < n && (s.charAt(j) == '+' || s.charAt(j) == '-')) {
                j++;
            }
            int k = j;
            while (k < n && Character.isDigit(s.charAt(k))) {
                k++;
            }
            if (k > j) {
                try {
                    exp = Integer.parseInt(s.substring(i + 1, k).replace("+", ""));
                } catch (NumberFormatException e) {
                    return null;
                }
                i = k;
            }
        }
        String rest = s.substring(i).trim();
        int prefixExp;
        if (rest.isEmpty() || matchesUnit(rest, unit)) {
            prefixExp = 0;
        } else {
            prefixExp = Integer.MIN_VALUE;
            for (int p = 0; p < PREFIXES.length; p++) {
                String pre = PREFIXES[p];
                if (rest.startsWith(pre)) {
                    String tail = rest.substring(pre.length());
                    if (tail.isEmpty() || matchesUnit(tail, unit)) {
                        prefixExp = PREFIX_EXP[p];
                        break;
                    }
                }
            }
            if (prefixExp == Integer.MIN_VALUE) {
                return null;
            }
        }
        double d;
        try {
            // decimal exponent arithmetic keeps the result correctly rounded ("4.7k" = 4700)
            d = Double.parseDouble(mantissa + "e" + (exp + prefixExp));
        } catch (NumberFormatException e) {
            return null;
        }
        return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
    }

    private static boolean matchesUnit(String s, String unit) {
        if (unit == null) {
            return false;
        }
        if (s.equals(unit)) {
            return true;
        }
        return ("Ohm".equals(unit) && s.equals("Ω")) || ("Ω".equals(unit) && s.equals("Ohm"));
    }
}
