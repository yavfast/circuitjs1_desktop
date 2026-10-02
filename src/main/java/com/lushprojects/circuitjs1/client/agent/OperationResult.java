package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONBoolean;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * [SP_AGA_01_08] Result of every contract: {@code ok}, contract-specific {@code data}, issues
 * (at most {@link #MAX_ISSUES}, errors first), {@code truncatedIssues} and, on a successful
 * mutating contract, {@code connectivity}; on every mutating contract (also a rejected one),
 * {@code transaction} ({@link AgentTransaction}).
 */
public final class OperationResult {

    /** Maximum number of issues listed in a result; the rest is counted in truncatedIssues. */
    public static final int MAX_ISSUES = 50;

    private final boolean ok;
    private final JSONObject data;
    private final List<Issue> issues = new ArrayList<>();
    /** [SP_AGA_01_06] ConnectivityDelta of a successful mutating contract, or null. */
    private JSONObject connectivity;
    /** [SP_AGA_01_08] {open, pendingEdits} of the target document's transaction, or null. */
    private JSONObject transaction;

    private OperationResult(boolean ok, JSONObject data) {
        this.ok = ok;
        this.data = data;
    }

    /** Successful result with contract data (may be null for contracts without output). */
    public static OperationResult success(JSONObject data) {
        return new OperationResult(true, data);
    }

    /**
     * Rejected result. {@code ok = false} carries at least one error, so the first issue must be
     * an error; further issues may follow.
     */
    public static OperationResult failure(Issue error, Issue... more) {
        if (error == null || error.getSeverity() != Issue.Severity.ERROR) {
            throw new IllegalArgumentException("a failed result needs an error issue");
        }
        OperationResult r = new OperationResult(false, null);
        r.issues.add(error);
        for (Issue i : more) {
            r.issues.add(i);
        }
        return r;
    }

    /** Rejected result from a list of issues that contains at least one error. */
    public static OperationResult failure(List<Issue> issues) {
        OperationResult r = new OperationResult(false, null);
        boolean hasError = false;
        for (Issue i : issues) {
            r.issues.add(i);
            hasError |= i.getSeverity() == Issue.Severity.ERROR;
        }
        if (!hasError) {
            throw new IllegalArgumentException("a failed result needs an error issue");
        }
        return r;
    }

    /** Adds an issue (a warning/info of a successful result, or a further reason of a failure). */
    public OperationResult addIssue(Issue issue) {
        issues.add(issue);
        return this;
    }

    /** Sets the ConnectivityDelta ([SP_AGA_01_08]: present on every successful mutating contract). */
    public OperationResult setConnectivity(JSONObject delta) {
        connectivity = delta;
        return this;
    }

    /** Sets the transaction state ([SP_AGA_01_08]: present on every mutating contract). */
    public OperationResult setTransaction(JSONObject state) {
        transaction = state;
        return this;
    }

    public boolean isOk() {
        return ok;
    }

    public JSONObject getData() {
        return data;
    }

    /** @return all issues, uncapped, in insertion order */
    public List<Issue> getIssues() {
        return issues;
    }

    public JSONObject toJson() {
        // Stable order: errors first, then warnings, then info; each group keeps insertion order.
        List<Issue> sorted = new ArrayList<>();
        for (int rank = 0; rank <= 2; rank++) {
            for (Issue i : issues) {
                if (i.getSeverity().rank() == rank) {
                    sorted.add(i);
                }
            }
        }
        JSONArray list = new JSONArray();
        int n = Math.min(sorted.size(), MAX_ISSUES);
        for (int i = 0; i < n; i++) {
            list.set(i, sorted.get(i).toJson());
        }

        JSONObject o = new JSONObject();
        o.put("ok", JSONBoolean.getInstance(ok));
        if (data != null) {
            o.put("data", data);
        }
        o.put("issues", list);
        o.put("truncatedIssues", new JSONNumber(sorted.size() - n));
        if (connectivity != null) {
            o.put("connectivity", connectivity);
        }
        if (transaction != null) {
            o.put("transaction", transaction);
        }
        return o;
    }

    /** @return the result serialized as a JSON string (the value crossing the JS boundary) */
    public String toJsonString() {
        return toJson().toString();
    }
}
