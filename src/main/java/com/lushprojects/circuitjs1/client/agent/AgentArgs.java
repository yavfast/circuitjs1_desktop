package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONBoolean;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONParser;
import com.google.gwt.json.client.JSONString;
import com.google.gwt.json.client.JSONValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed reading of a contract's JSON arguments. Every violation of a stated type, range,
 * enumeration or list cardinality records an {@code invalid_value} issue naming the argument
 * ([SP_AGA_02] "Argument ranges"); the getter then returns its default. A handler reads all its
 * arguments, then returns {@link #failure()} when {@link #failed()} — nothing is applied.
 * An absent argument and an explicit JSON {@code null} both mean "use the default".
 */
public final class AgentArgs {

    private final JSONObject args;
    private final List<Issue> errors = new ArrayList<>();

    private AgentArgs(JSONObject args) {
        this.args = args;
    }

    /**
     * Parses the argument string of a call. Null or blank means no arguments. Anything other
     * than a JSON object records an {@code invalid_value} issue for {@code args}.
     */
    public static AgentArgs parse(String argsJson) {
        if (argsJson == null || argsJson.trim().isEmpty()) {
            return new AgentArgs(new JSONObject());
        }
        JSONValue v;
        try {
            v = JSONParser.parseStrict(argsJson);
        } catch (RuntimeException e) {
            // Malformed caller input is a domain failure, reported as a result (SP_AGA_03_10).
            AgentArgs a = new AgentArgs(new JSONObject());
            a.invalid("args", "is not valid JSON", "Pass the arguments as a JSON object string, e.g. \"{}\".");
            return a;
        }
        JSONObject o = v == null ? null : v.isObject();
        if (o == null) {
            AgentArgs a = new AgentArgs(new JSONObject());
            a.invalid("args", "must be a JSON object", "Pass the arguments as a JSON object string, e.g. \"{}\".");
            return a;
        }
        return new AgentArgs(o);
    }

    /** @return true when the argument is present and not JSON null */
    public boolean has(String name) {
        JSONValue v = args.get(name);
        return v != null && v.isNull() == null;
    }

    /** @return the raw argument value, or null when absent or JSON null */
    public JSONValue raw(String name) {
        return has(name) ? args.get(name) : null;
    }

    /** Optional string argument. */
    public String optString(String name, String def) {
        if (!has(name)) {
            return def;
        }
        JSONString s = args.get(name).isString();
        if (s == null) {
            invalid(name, "must be a string", "Pass " + name + " as a JSON string.");
            return def;
        }
        return s.stringValue();
    }

    /** Required string argument; records invalid_value when absent. */
    public String requireString(String name) {
        if (!has(name)) {
            invalid(name, "is required", "Pass " + name + " as a JSON string.");
            return null;
        }
        return optString(name, null);
    }

    /** Optional boolean argument. */
    public boolean optBool(String name, boolean def) {
        if (!has(name)) {
            return def;
        }
        JSONBoolean b = args.get(name).isBoolean();
        if (b == null) {
            invalid(name, "must be true or false", "Pass " + name + " as a JSON boolean.");
            return def;
        }
        return b.booleanValue();
    }

    /** Optional number argument within [min, max] (inclusive). */
    public double optNumber(String name, double min, double max, double def) {
        if (!has(name)) {
            return def;
        }
        JSONNumber n = args.get(name).isNumber();
        if (n == null || Double.isNaN(n.doubleValue()) || Double.isInfinite(n.doubleValue())) {
            invalid(name, "must be a number", "Pass " + name + " as a JSON number.");
            return def;
        }
        double v = n.doubleValue();
        if (v < min || v > max) {
            invalid(name, "is out of range", "Use a value from " + format(min) + " to " + format(max) + ".");
            return def;
        }
        return v;
    }

    /** Optional integer argument within [min, max] (inclusive). */
    public int optInt(String name, int min, int max, int def) {
        if (!has(name)) {
            return def;
        }
        JSONNumber n = args.get(name).isNumber();
        if (n == null || n.doubleValue() != Math.rint(n.doubleValue())) {
            invalid(name, "must be an integer", "Pass " + name + " as a whole JSON number.");
            return def;
        }
        double v = n.doubleValue();
        if (v < min || v > max) {
            invalid(name, "is out of range", "Use a value from " + min + " to " + max + ".");
            return def;
        }
        return (int) v;
    }

    /** Optional string argument restricted to an enumeration. */
    public String optEnum(String name, String[] allowed, String def) {
        String v = optString(name, null);
        if (v == null) {
            return def;
        }
        for (String a : allowed) {
            if (a.equals(v)) {
                return v;
            }
        }
        invalid(name, "is not one of the allowed values", "Use one of: " + String.join(", ", allowed) + ".");
        return def;
    }

    /**
     * Optional list argument with a cardinality range; returns null when absent or invalid.
     */
    public JSONArray optArray(String name, int minCount, int maxCount) {
        if (!has(name)) {
            return null;
        }
        JSONArray a = args.get(name).isArray();
        if (a == null) {
            invalid(name, "must be a list", "Pass " + name + " as a JSON array.");
            return null;
        }
        if (a.size() < minCount || a.size() > maxCount) {
            invalid(name, "has " + a.size() + " entries", "Pass " + minCount + " to " + maxCount + " entries.");
            return null;
        }
        return a;
    }

    /**
     * Records an {@code invalid_value} issue for an argument; also used by handlers for
     * cross-argument rules (e.g. min &gt; max).
     *
     * @param name    the argument name, quoted in the message
     * @param problem what is wrong, completing "Argument 'name' ..."
     * @param hint    one actionable fix
     */
    public void invalid(String name, String problem, String hint) {
        errors.add(Issue.of(IssueCode.INVALID_VALUE, "Argument '" + name + "' " + problem + ".", hint));
    }

    /** @return true when any argument was invalid */
    public boolean failed() {
        return !errors.isEmpty();
    }

    /** @return the rejection for the invalid arguments (only valid when {@link #failed()}) */
    public OperationResult failure() {
        return OperationResult.failure(errors);
    }

    private static String format(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }
}
