package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.json.client.JSONBoolean;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONString;
import com.google.gwt.json.client.JSONValue;
import com.lushprojects.circuitjs1.client.element.CircuitElm;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * [SP_AGA_03_03] Property values: strict parsing of agent input and comparison of read-back
 * values.
 * <ul>
 * <li>{@code quantity} / {@code number}: a JSON number, or a string that parses fully as a number
 *     with an optional SI prefix and an optional unit suffix matching the property's unit
 *     (case-sensitive; {@code Ohm} and {@code Ω} both accepted for resistance). Anything else
 *     is {@code invalid_value} — the JSON parser's "0 on failure" is never taken as a value.</li>
 * <li>{@code bool}: {@code true}/{@code false} only. {@code text}: a string of at most 1000 chars.</li>
 * </ul>
 * Parsed values are handed to the element in its own JSON property format: a quantity as a
 * lossless unit string ({@code getJsonUnitText}, RULE_STYLE_010), a number as a Double.
 */
final class PropertyValues {

    /** Maximum length of a text property or description. */
    static final int MAX_TEXT = 1000;

    private static final String[] PREFIXES = { "f", "p", "n", "u", "μ", "m", "k", "K", "M", "G", "T" };
    private static final int[] PREFIX_EXP = { -15, -12, -9, -6, -6, -3, 3, 3, 6, 9, 12 };

    private PropertyValues() {
    }

    /**
     * Parses an agent value for {@code info}.
     *
     * @return the value in the element's JSON format (String, Double or Boolean), or null when
     *         the value is invalid (the reason is in {@code problem[0]})
     */
    static Object parse(Catalogue.PropertyInfo info, JSONValue v, String[] problem) {
        switch (info.kind) {
            case "bool": {
                JSONBoolean b = v == null ? null : v.isBoolean();
                if (b == null) {
                    problem[0] = "must be true or false";
                    return null;
                }
                return b.booleanValue();
            }
            case "text": {
                JSONString s = v == null ? null : v.isString();
                if (s == null) {
                    problem[0] = "must be a string";
                    return null;
                }
                if (s.stringValue().length() > MAX_TEXT) {
                    problem[0] = "is longer than " + MAX_TEXT + " characters";
                    return null;
                }
                return s.stringValue();
            }
            default: { // quantity, number
                Double d = parseNumber(v, info.unit);
                if (d == null) {
                    problem[0] = "must be a number" + (info.unit != null ? " or a unit string such as \"4.7 k"
                            + info.unit + "\"" : "") + " (got " + v + ")";
                    return null;
                }
                return toElementValue(info, d);
            }
        }
    }

    /** @return the value in the element's JSON property format for a parsed number */
    static Object toElementValue(Catalogue.PropertyInfo info, double d) {
        if ("quantity".equals(info.kind) && info.unit != null && info.unit.indexOf('/') < 0) {
            return CircuitElm.getJsonUnitText(d, info.unit);
        }
        // number, or a compound unit ("V/us") that the elements read as a plain number
        return d;
    }

    /** @return the number given as a JSON number or a strict unit string, or null */
    static Double parseNumber(JSONValue v, String unit) {
        if (v == null) {
            return null;
        }
        JSONNumber n = v.isNumber();
        if (n != null) {
            double d = n.doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
        }
        JSONString s = v.isString();
        return s == null ? null : parseUnitString(s.stringValue(), unit);
    }

    /**
     * Parses {@code <number>[ ][prefix][unit]} fully; {@code unit} may be null (no unit allowed).
     *
     * @return the value, or null when the string is not of that form
     */
    static Double parseUnitString(String text, String unit) {
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

    /**
     * @return true when two property values (in the element's JSON format) denote the same value:
     *         numbers and quantity strings by their numeric value, everything else by equality
     */
    static boolean same(Object a, Object b, String unit) {
        if (a == null || b == null) {
            return a == b;
        }
        Double da = numeric(a, unit);
        Double db = numeric(b, unit);
        if (da != null && db != null) {
            return da.doubleValue() == db.doubleValue();
        }
        return a.equals(b);
    }

    private static Double numeric(Object v, String unit) {
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof String && unit != null) {
            return parseUnitString((String) v, unit);
        }
        return null;
    }

    /**
     * [SP_AGA_02_04] {@code set} step 1 source: the element's current properties — its exported
     * keys plus its declared conditional keys at their current values (a conditional key that is
     * not exported is at its default).
     */
    static LinkedHashMap<String, Object> current(CircuitElm elm) {
        LinkedHashMap<String, Object> props = new LinkedHashMap<>(elm.getJsonProperties());
        for (Map.Entry<String, Object> e : elm.getJsonConditionalProperties().entrySet()) {
            if (!props.containsKey(e.getKey())) {
                props.put(e.getKey(), e.getValue());
            }
        }
        return props;
    }

    /** @return a property value as shown in records and issues ("4.7 kOhm", 3, true) */
    static String display(Object v) {
        if (v instanceof Double) {
            double d = (Double) v;
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                return Long.toString((long) d);
            }
        }
        return String.valueOf(v);
    }
}
