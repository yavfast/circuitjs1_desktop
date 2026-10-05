package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * [SP_AGA_01_07] One problem reported by a contract: code, severity, a one-sentence message, one
 * actionable hint, optional involved elements/posts/location, and a key that identifies the same
 * issue across reports.
 */
public final class Issue {

    public enum Severity {
        ERROR("error", 0), WARNING("warning", 1), INFO("info", 2);

        private final String wire;
        private final int rank;

        Severity(String wire, int rank) {
            this.wire = wire;
            this.rank = rank;
        }

        public String wire() {
            return wire;
        }

        /** Sort rank: errors first, then warnings, then info. */
        int rank() {
            return rank;
        }
    }

    private final IssueCode code;
    private final Severity severity;
    private final String message;
    private final String hint;
    private final List<String> elements = new ArrayList<>();
    private final List<String> posts = new ArrayList<>();
    private boolean hasAt;
    private double atX, atY;
    /** {@link #key()}, computed once (the builders below clear it). */
    private String key;

    private Issue(IssueCode code, Severity severity, String message, String hint) {
        this.code = code;
        this.severity = severity;
        this.message = message;
        this.hint = hint;
    }

    /** Creates an issue with the code's default severity. */
    public static Issue of(IssueCode code, String message, String hint) {
        return new Issue(code, code.defaultSeverity(), message, hint);
    }

    /** Creates an issue with an explicit severity (for codes whose severity depends on context). */
    public static Issue of(IssueCode code, Severity severity, String message, String hint) {
        return new Issue(code, severity, message, hint);
    }

    /** Adds involved element IDs; returns this issue. */
    public Issue elements(String... ids) {
        Collections.addAll(elements, ids);
        key = null;
        return this;
    }

    /** Adds involved posts (PostRef strings); returns this issue. */
    public Issue posts(String... refs) {
        Collections.addAll(posts, refs);
        key = null;
        return this;
    }

    /** Sets the location in cells; returns this issue. */
    public Issue at(double x, double y) {
        hasAt = true;
        atX = x;
        atY = y;
        key = null;
        return this;
    }

    public IssueCode getCode() {
        return code;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getMessage() {
        return message;
    }

    /** @return the fix hint, or null */
    public String getHint() {
        return hint;
    }

    /**
     * @return the issue key: code + sorted elements + sorted posts + at, as
     *         {@code code|E1,E2|E1.a,E2.b|x,y} (empty segments stay empty)
     */
    public String key() {
        if (key == null) {
            StringBuilder sb = new StringBuilder(code.code());
            sb.append('|').append(join(sorted(elements))).append('|').append(join(sorted(posts))).append('|');
            if (hasAt) {
                sb.append(formatNumber(atX)).append(',').append(formatNumber(atY));
            }
            key = sb.toString();
        }
        return key;
    }

    /** @return {@code values} sorted (a copy when it has more than one entry) */
    private static List<String> sorted(List<String> values) {
        if (values.size() < 2) {
            return values;
        }
        List<String> copy = new ArrayList<>(values);
        Collections.sort(copy);
        return copy;
    }

    JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("code", new JSONString(code.code()));
        o.put("severity", new JSONString(severity.wire()));
        o.put("message", new JSONString(message));
        if (!elements.isEmpty()) {
            o.put("elements", toArray(elements));
        }
        if (!posts.isEmpty()) {
            o.put("posts", toArray(posts));
        }
        if (hasAt) {
            JSONObject at = new JSONObject();
            at.put("x", new JSONNumber(atX));
            at.put("y", new JSONNumber(atY));
            o.put("at", at);
        }
        o.put("hint", new JSONString(hint));
        o.put("key", new JSONString(key()));
        return o;
    }

    private static JSONArray toArray(List<String> values) {
        JSONArray a = new JSONArray();
        for (int i = 0; i < values.size(); i++) {
            a.set(i, new JSONString(values.get(i)));
        }
        return a;
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    // Cell coordinates are multiples of 1/16, so whole values print without a fraction.
    static String formatNumber(double v) {
        // the digits of Long.toString for a whole value; an int needs no emulated long or rint
        if (Math.abs(v) < Integer.MAX_VALUE && v == (int) v) {
            return Integer.toString((int) v);
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }
}
