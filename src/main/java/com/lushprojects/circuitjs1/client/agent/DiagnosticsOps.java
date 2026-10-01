package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONBoolean;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;
import com.google.gwt.json.client.JSONValue;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.CircuitSimulator;
import com.lushprojects.circuitjs1.client.DocumentScope;
import com.lushprojects.circuitjs1.client.LogManager;

import java.util.List;

/**
 * [SP_AGA_02_11] getDiagnostics: the solver state of the target document ([SP_AGA_01_11]), the
 * issues of its last agent import and, on request, a window of the session log read by
 * sequence number.
 * <ul>
 * <li>{@code stopped}/{@code stop}: the simulator's stop state, or a stop the stepping loop raised
 *     for an exception ({@code solver_stop}).</li>
 * <li>{@code warning}: the most recent warning event since the last analysis; {@code events}: every
 *     warning and stop since then, in order, each code once ({@link SolverEvents}).</li>
 * <li>{@code recovering}: the non-convergence recovery is engaged for the document now (a panic
 *     level above 0 or the singular-matrix stabilisers active, [SP_AGA_01_11]).</li>
 * <li>{@code log}: entries with {@code seq > since}, oldest first, at most {@code limit}, each cut
 *     at 500 characters; {@code gap} when entries after {@code since} were evicted.</li>
 * </ul>
 * The document's analysis is made current first, so the events describe the present circuit.
 */
final class DiagnosticsOps {

    static final int DEFAULT_LOG_LIMIT = 50;
    static final int MAX_LOG_LIMIT = 500;
    /** [SP_AGA_03_07] Each log entry's text is cut at 500 characters. */
    static final int MAX_LOG_CHARS = 500;

    private DiagnosticsOps() {
    }

    static void register(AgentApi api) {
        api.register("getDiagnostics", AgentApi.DocPolicy.OPTIONAL, AgentApi.BusyPolicy.SERVED, DiagnosticsOps::getDiagnostics);
    }

    static OperationResult getDiagnostics(AgentApi.Call call) {
        JSONValue logArg = call.args.raw("log");
        int since = 0;
        int limit = DEFAULT_LOG_LIMIT;
        boolean wantLog = logArg != null;
        if (wantLog) {
            JSONObject lo = logArg.isObject();
            if (lo == null) {
                call.args.invalid("log", "must be an object {since?, limit?}", "Pass log: {since: <cursor>, limit: 50}.");
            } else {
                since = intField(call.args, lo, "since", 0, Integer.MAX_VALUE, 0);
                limit = intField(call.args, lo, "limit", 1, MAX_LOG_LIMIT, DEFAULT_LOG_LIMIT);
            }
        }
        if (call.args.failed()) {
            return call.args.failure();
        }
        final CircuitDocument doc = call.doc;
        final int fSince = since;
        final int fLimit = limit;
        return DocumentScope.call(call.sim, doc, () -> {
            doc.ensureAnalysed();
            CircuitSimulator sim = doc.simulator;
            JSONObject data = new JSONObject();
            Issue stop = SolverEvents.stopIssue(doc);
            data.put("stopped", JSONBoolean.getInstance(stop != null));
            if (stop != null) {
                data.put("stop", stop.toJson());
            }
            Issue warning = SolverEvents.lastWarning(doc);
            if (warning != null) {
                data.put("warning", warning.toJson());
            }
            data.put("events", issues(SolverEvents.events(doc)));
            data.put("recovering", JSONBoolean.getInstance(sim.isRecoveryEngaged()));
            JSONArray lastImport = doc.getLastImportIssues();
            data.put("lastImport", lastImport != null ? lastImport : new JSONArray());
            data.put("simTime", new JSONNumber(sim.t));
            data.put("running", JSONBoolean.getInstance(doc.isRunning()));
            JSONObject ts = new JSONObject();
            ts.put("current", new JSONNumber(sim.timeStep));
            ts.put("max", new JSONNumber(sim.maxTimeStep));
            ts.put("min", new JSONNumber(sim.minTimeStep));
            ts.put("auto", JSONBoolean.getInstance(sim.adjustTimeStep));
            data.put("timeStep", ts);
            if (wantLog) {
                data.put("log", log(call.sim.logManager, fSince, fLimit));
            }
            return OperationResult.success(data);
        });
    }

    private static JSONObject log(LogManager lm, int since, int limit) {
        LogManager.LogWindow w = lm.getLogsSince(since, limit, MAX_LOG_CHARS);
        JSONArray entries = new JSONArray();
        for (int i = 0; i < w.seqs.size(); i++) {
            JSONObject e = new JSONObject();
            e.put("seq", new JSONNumber(w.seqs.get(i)));
            e.put("text", new JSONString(w.texts.get(i)));
            entries.set(i, e);
        }
        JSONObject o = new JSONObject();
        o.put("entries", entries);
        o.put("cursor", new JSONNumber(w.cursor));
        o.put("gap", JSONBoolean.getInstance(w.gap));
        return o;
    }

    private static JSONArray issues(List<Issue> list) {
        JSONArray a = new JSONArray();
        for (int i = 0; i < list.size(); i++) {
            a.set(i, list.get(i).toJson());
        }
        return a;
    }

    private static int intField(AgentArgs args, JSONObject o, String key, int min, int max, int def) {
        JSONValue v = o.get(key);
        if (v == null || v.isNull() != null) {
            return def;
        }
        JSONNumber n = v.isNumber();
        if (n == null || n.doubleValue() != Math.rint(n.doubleValue())) {
            args.invalid("log." + key, "must be an integer", "Pass log." + key + " as a whole JSON number.");
            return def;
        }
        if (n.doubleValue() < min || n.doubleValue() > max) {
            args.invalid("log." + key, "is out of range", "Use a value from " + min + " to " + max + ".");
            return def;
        }
        return (int) n.doubleValue();
    }
}
