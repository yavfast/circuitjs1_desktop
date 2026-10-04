package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.json.client.JSONBoolean;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONString;
import com.google.gwt.json.client.JSONValue;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.util.UnitValues;

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
     * Parses {@code <number>[ ][prefix][unit]} fully, optionally inside one pair of double quotes;
     * {@code unit} may be null (no unit allowed). The strict number syntax is
     * {@link UnitValues#parse}; the quote rule is the agent's own.
     *
     * @return the value, or null when the string is not of that form
     */
    static Double parseUnitString(String text, String unit) {
        String s = unquote(text);
        return s == null ? null : UnitValues.parse(s, unit);
    }

    /**
     * [SP_AGA_03_03] One pair of surrounding double quotes is tolerated: agent hosts sometimes
     * send a number-or-string argument JSON-encoded twice ({@code "\"10 ms\""}).
     *
     * @return the trimmed text without that pair, or null for null
     */
    static String unquote(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
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
