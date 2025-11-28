/*
    Copyright (C) Paul Falstad and Iain Sharp

    This file is part of CircuitJS1.

    CircuitJS1 is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 2 of the License, or
    (at your option) any later version.

    CircuitJS1 is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with CircuitJS1.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.lushprojects.circuitjs1.client.io.json;

import java.util.HashMap;
import java.util.Map;

/**
 * Utility class for parsing values with SI unit prefixes.
 * 
 * Examples:
 *   "10 kOhm" -> 10000.0
 *   "100 nF" -> 1e-7
 *   "5 V" -> 5.0
 *   "2.5 mA" -> 0.0025
 */
public class UnitParser {

    private static final Map<String, Double> SI_PREFIXES = new HashMap<>();

    static {
        // SI prefixes from femto to tera
        SI_PREFIXES.put("f", 1e-15);  // femto
        SI_PREFIXES.put("p", 1e-12);  // pico
        SI_PREFIXES.put("n", 1e-9);   // nano
        SI_PREFIXES.put("u", 1e-6);   // micro (using 'u' instead of 'μ')
        SI_PREFIXES.put("μ", 1e-6);   // micro (unicode)
        SI_PREFIXES.put("m", 1e-3);   // milli
        SI_PREFIXES.put("", 1.0);     // no prefix
        SI_PREFIXES.put("k", 1e3);    // kilo
        SI_PREFIXES.put("K", 1e3);    // kilo (alternate)
        SI_PREFIXES.put("M", 1e6);    // mega
        SI_PREFIXES.put("G", 1e9);    // giga
        SI_PREFIXES.put("T", 1e12);   // tera
    }

    // Common unit names (for stripping from input)
    private static final String[] UNIT_NAMES = {
        "Ohm", "Ω", "ohm", "ohms",
        "F", "f", "farad", "farads",
        "H", "henry", "henries",
        "V", "v", "volt", "volts",
        "A", "a", "amp", "amps", "ampere", "amperes",
        "W", "w", "watt", "watts",
        "Hz", "hz", "hertz",
        "s", "sec", "second", "seconds"
    };

    /**
     * Parses a value with optional SI prefix and unit.
     * 
     * @param input String like "10 kOhm", "100 nF", "5 V", etc.
     * @return The numeric value in base units
     */
    public static double parse(String input) {
        if (input == null || input.trim().isEmpty()) {
            return 0;
        }

        String str = input.trim();
        
        // Try to parse as plain number first
        try {
            return Double.parseDouble(str);
        } catch (NumberFormatException e) {
            // Continue with unit parsing
        }

        // Split into number and unit parts
        int unitStart = findUnitStart(str);
        if (unitStart == -1) {
            // No valid number found
            return 0;
        }

        String numberPart = str.substring(0, unitStart).trim();
        String unitPart = str.substring(unitStart).trim();

        // Parse the number
        double value;
        try {
            value = Double.parseDouble(numberPart);
        } catch (NumberFormatException e) {
            return 0;
        }

        // Extract prefix from unit part
        double multiplier = extractMultiplier(unitPart);
        
        return value * multiplier;
    }

    /**
     * Finds the starting index of the unit part in the string.
     * Returns the index of the first non-numeric character after the number.
     */
    private static int findUnitStart(String str) {
        int i = 0;
        
        // Skip leading whitespace
        while (i < str.length() && Character.isWhitespace(str.charAt(i))) {
            i++;
        }

        // Check for optional sign
        if (i < str.length() && (str.charAt(i) == '+' || str.charAt(i) == '-')) {
            i++;
        }

        // Skip digits and decimal point
        boolean hasDigit = false;
        boolean hasDecimal = false;
        while (i < str.length()) {
            char c = str.charAt(i);
            if (Character.isDigit(c)) {
                hasDigit = true;
                i++;
            } else if (c == '.' && !hasDecimal) {
                hasDecimal = true;
                i++;
            } else if ((c == 'e' || c == 'E') && hasDigit) {
                // Scientific notation
                i++;
                if (i < str.length() && (str.charAt(i) == '+' || str.charAt(i) == '-')) {
                    i++;
                }
                while (i < str.length() && Character.isDigit(str.charAt(i))) {
                    i++;
                }
            } else {
                break;
            }
        }

        if (!hasDigit) {
            return -1;
        }

        // Skip whitespace between number and unit
        while (i < str.length() && Character.isWhitespace(str.charAt(i))) {
            i++;
        }

        return i;
    }

    /**
     * Extracts the SI multiplier from a unit string.
     * 
     * @param unitPart String like "kOhm", "nF", "mA", etc.
     * @return The multiplier (e.g., 1000 for "k", 1e-9 for "n")
     */
    private static double extractMultiplier(String unitPart) {
        if (unitPart == null || unitPart.isEmpty()) {
            return 1.0;
        }

        // Strip known unit names from the end
        String remaining = unitPart;
        for (String unit : UNIT_NAMES) {
            if (remaining.endsWith(unit)) {
                remaining = remaining.substring(0, remaining.length() - unit.length());
                break;
            }
        }

        remaining = remaining.trim();

        // Check for SI prefix
        if (remaining.isEmpty()) {
            return 1.0;
        }

        // Try to match the prefix
        Double multiplier = SI_PREFIXES.get(remaining);
        if (multiplier != null) {
            return multiplier;
        }

        // Check first character as prefix
        String firstChar = remaining.substring(0, 1);
        multiplier = SI_PREFIXES.get(firstChar);
        if (multiplier != null) {
            return multiplier;
        }

        return 1.0;
    }

    /**
     * Parses a value from a generic Object.
     * Handles Number, String, and null values.
     * 
     * @param value The value to parse
     * @return The numeric value
     */
    public static double parseValue(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            return parse((String) value);
        }
        return 0;
    }

    /**
     * Parses a value with a default fallback.
     * 
     * @param value The value to parse
     * @param defaultValue Value to return if parsing fails
     * @return The numeric value or default
     */
    public static double parseValue(Object value, double defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        double result = parseValue(value);
        return (result == 0 && !isZeroValue(value)) ? defaultValue : result;
    }

    private static boolean isZeroValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue() == 0;
        }
        if (value instanceof String) {
            String str = ((String) value).trim();
            return str.equals("0") || str.startsWith("0 ");
        }
        return false;
    }

    /**
     * Parses an integer value.
     */
    public static int parseInt(Object value) {
        return (int) parseValue(value);
    }

    /**
     * Parses an integer value with default.
     */
    public static int parseInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return (int) parseValue(value, defaultValue);
    }

    /**
     * Parses a boolean value.
     */
    public static boolean parseBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            String str = ((String) value).toLowerCase();
            return "true".equals(str) || "yes".equals(str) || "1".equals(str);
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        return false;
    }

    /**
     * Parses a boolean value with default.
     */
    public static boolean parseBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return parseBoolean(value);
    }

    /**
     * Gets a string value safely.
     */
    public static String parseString(Object value) {
        if (value == null) {
            return null;
        }
        return value.toString();
    }

    /**
     * Gets a string value with default.
     */
    public static String parseString(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String str = value.toString();
        return str.isEmpty() ? defaultValue : str;
    }
}
