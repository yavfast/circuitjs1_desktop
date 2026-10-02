package com.lushprojects.circuitjs1.client;

import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Session state of the in-app MCP server (C_MCP, SP_MCP_04_01): its lifecycle state, the failure
 * or disable reason, the instance ID, the listening address and URLs, and the count of handled
 * tool calls. One per session, on {@link CirSim}; the server reports changes through
 * {@code agent.AgentJsBridge}, and the info dialog (PL_MCP Phase 3) reads it from here, so the
 * dialog does not depend on the agent package.
 * <p>
 * Also holds the server preferences of [SP_MCP_01_01] (keys, defaults, validation), which apply
 * at the next app start.
 */
public final class McpServerStatus {

    /** [SP_MCP_04_01] server lifecycle states, with their wire names. */
    public enum State {
        DISABLED("disabled"), STARTING("starting"), LISTENING("listening"), FAILED("failed"), STOPPED("stopped");

        private final String wire;

        State(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        static State fromWire(String s) {
            for (State st : values()) {
                if (st.wire.equals(s)) {
                    return st;
                }
            }
            return null;
        }
    }

    // [SP_MCP_01_01] preference keys and defaults
    public static final String PREF_ENABLED = "mcpServerEnabled";
    public static final String PREF_PORT = "mcpServerPort";
    public static final String PREF_PORT_RANGE = "mcpServerPortRange";
    public static final String PREF_HOST = "mcpServerHost";
    public static final boolean DEFAULT_ENABLED = true;
    public static final int DEFAULT_PORT = 7311;
    public static final int DEFAULT_PORT_RANGE = 20;
    public static final String DEFAULT_HOST = "0.0.0.0";

    /** Validated server preferences ([SP_MCP_01_01]); an invalid stored value falls back to its default. */
    public static final class Prefs {
        public final boolean enabled;
        public final int port;
        public final int portRange;
        public final String host;
        /** Keys whose stored value was invalid and replaced by the default. */
        public final List<String> invalidKeys;

        Prefs(boolean enabled, int port, int portRange, String host, List<String> invalidKeys) {
            this.enabled = enabled;
            this.port = port;
            this.portRange = portRange;
            this.host = host;
            this.invalidKeys = Collections.unmodifiableList(invalidKeys);
        }
    }

    private State state = State.STOPPED;
    private String reason;
    private String instanceId;
    private String host;
    private int port;
    private final List<String> urls = new ArrayList<>();
    private int toolCalls;

    /** Reads the server preferences from storage and validates them. */
    public static Prefs readPrefs() {
        List<String> invalid = new ArrayList<>();
        String enabledRaw = OptionsManager.getOptionFromStorage(PREF_ENABLED, null);
        boolean enabled = DEFAULT_ENABLED;
        if (enabledRaw != null) {
            if ("true".equals(enabledRaw) || "false".equals(enabledRaw)) {
                enabled = "true".equals(enabledRaw);
            } else {
                invalid.add(PREF_ENABLED);
            }
        }
        int port = readInt(PREF_PORT, DEFAULT_PORT, invalid);
        if (!isValidPort(port)) {
            invalid.add(PREF_PORT);
            port = DEFAULT_PORT;
        }
        int range = readInt(PREF_PORT_RANGE, DEFAULT_PORT_RANGE, invalid);
        if (!isValidPortRange(port, range)) {
            invalid.add(PREF_PORT_RANGE);
            range = isValidPortRange(port, DEFAULT_PORT_RANGE) ? DEFAULT_PORT_RANGE : 65535 - port + 1;
        }
        String host = OptionsManager.getOptionFromStorage(PREF_HOST, DEFAULT_HOST);
        if (!isValidHost(host)) {
            invalid.add(PREF_HOST);
            host = DEFAULT_HOST;
        }
        return new Prefs(enabled, port, range, host.trim(), invalid);
    }

    private static int readInt(String key, int defVal, List<String> invalid) {
        String raw = OptionsManager.getOptionFromStorage(key, null);
        if (raw == null) {
            return defVal;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            invalid.add(key);
            return defVal;
        }
    }

    /** [SP_MCP_01_01] base port 1024..65535 */
    public static boolean isValidPort(int port) {
        return port >= 1024 && port <= 65535;
    }

    /** [SP_MCP_01_01] range 1..100 and {@code port + range − 1 ≤ 65535} */
    public static boolean isValidPortRange(int port, int range) {
        return range >= 1 && range <= 100 && port + range - 1 <= 65535;
    }

    /** [SP_MCP_01_01] an IPv4 or IPv6 literal, or {@code localhost} */
    public static boolean isValidHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim();
        if (h.equals("localhost")) {
            return true;
        }
        if (isIPv4(h)) {
            return true;
        }
        return isIPv6(h);
    }

    private static boolean isIPv4(String h) {
        if (!h.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) {
            return false;
        }
        for (String part : h.split("\\.")) {
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    /**
     * An IPv6 literal without brackets or zone: hex groups of 1–4 digits separated by colons, at
     * most one {@code ::}, eight groups in all ({@code ::} standing for at least one), and an
     * optional IPv4 tail counting as two groups.
     */
    static boolean isIPv6(String h) {
        if (h.isEmpty() || h.length() > 45 || h.indexOf(':') < 0) {
            return false;
        }
        int dbl = h.indexOf("::");
        if (dbl >= 0 && h.indexOf("::", dbl + 1) >= 0) {
            return false;
        }
        String head = dbl >= 0 ? h.substring(0, dbl) : h;
        String tail = dbl >= 0 ? h.substring(dbl + 2) : "";
        int groups = 0;
        String[] parts = (head.isEmpty() ? new String[0] : head.split(":", -1));
        String[] tailParts = (tail.isEmpty() ? new String[0] : tail.split(":", -1));
        String[][] sides = { parts, tailParts };
        for (int side = 0; side < 2; side++) {
            String[] ps = sides[side];
            for (int i = 0; i < ps.length; i++) {
                String g = ps[i];
                boolean last = (dbl >= 0 ? side == 1 : side == 0) && i == ps.length - 1;
                if (last && g.indexOf('.') >= 0) {
                    if (!isIPv4(g)) {
                        return false;
                    }
                    groups += 2;
                } else if (g.matches("^[0-9A-Fa-f]{1,4}$")) {
                    groups++;
                } else {
                    return false;
                }
            }
        }
        return dbl >= 0 ? groups <= 7 : groups == 8;
    }

    /**
     * Applies a status reported by the server (the object of {@code CircuitJS1Mcp.start}'s
     * callback).
     *
     * @return the previous state
     */
    public State update(JSONObject s) {
        State previous = state;
        State next = State.fromWire(stringOf(s, "state"));
        if (next != null) {
            state = next;
        }
        reason = stringOf(s, "reason");
        instanceId = stringOf(s, "instanceId");
        host = stringOf(s, "host");
        port = (int) numberOf(s, "port");
        toolCalls = (int) numberOf(s, "toolCalls");
        urls.clear();
        JSONArray a = s.get("urls") == null ? null : s.get("urls").isArray();
        if (a != null) {
            for (int i = 0; i < a.size(); i++) {
                JSONString u = a.get(i).isString();
                if (u != null) {
                    urls.add(u.stringValue());
                }
            }
        }
        return previous;
    }

    /** Sets a state decided on the app side (no server script, no desktop runtime). */
    public void set(State state, String reason) {
        this.state = state;
        this.reason = reason;
    }

    private static String stringOf(JSONObject o, String key) {
        JSONString v = o.get(key) == null ? null : o.get(key).isString();
        return v == null ? null : v.stringValue();
    }

    private static double numberOf(JSONObject o, String key) {
        JSONNumber v = o.get(key) == null ? null : o.get(key).isNumber();
        return v == null ? 0 : v.doubleValue();
    }

    public State getState() {
        return state;
    }

    public String getReason() {
        return reason;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public List<String> getUrls() {
        return Collections.unmodifiableList(urls);
    }

    public int getToolCalls() {
        return toolCalls;
    }

    /** The status line of [SP_MCP_02_04]: {@code listening}, {@code failed: <reason>} or {@code disabled}. */
    public String describe() {
        if (state == State.FAILED && reason != null) {
            return state.wire() + ": " + reason;
        }
        return state.wire();
    }

    /** The status as JSON (harness diagnostic {@code CircuitJS1Agent.debugMcpStatus()}). */
    public String toJson() {
        JSONObject o = new JSONObject();
        o.put("state", new JSONString(state.wire()));
        if (reason != null) {
            o.put("reason", new JSONString(reason));
        }
        if (instanceId != null) {
            o.put("instanceId", new JSONString(instanceId));
        }
        if (host != null) {
            o.put("host", new JSONString(host));
        }
        o.put("port", new JSONNumber(port));
        JSONArray a = new JSONArray();
        for (String u : urls) {
            a.set(a.size(), new JSONString(u));
        }
        o.put("urls", a);
        o.put("toolCalls", new JSONNumber(toolCalls));
        return o.toString();
    }
}
