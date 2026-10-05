package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.json.client.JSONBoolean;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONValue;
import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.CircuitSimulator;
import com.lushprojects.circuitjs1.client.DocumentScope;

/**
 * [SP_AGA_02_09] {@code simControl}: free-running control and the time-step settings of one
 * document. Every action is rejected with {@code busy} during a run (SP_AGA_02 class table).
 * <ul>
 * <li>{@code run} / {@code stop} — the user's Run/Stop: the running flag of the document. Only the
 *     active document free-runs (the tab rule); on a background document the flag takes effect
 *     when its tab is activated. Like the user's Run, {@code run} does nothing in a stop state
 *     (the stop issue is returned; {@code reset} clears it). On the visible tab the session path
 *     runs (toolbar button, repaint, Select mode); a background document only changes its own
 *     flag, so the session toolbar and cursor are untouched ([SP_AGA_03_08] R1).</li>
 * <li>{@code reset} — the user's Reset ({@code resetAction}): simulated time 0, element state,
 *     scope histories and the stop state.</li>
 * <li>{@code configure} — the persistent settings analysis keeps: {@code maxTimeStep},
 *     {@code minTimeStep}, {@code autoTimeStep}. It never writes the transient current step; the
 *     analysis that follows starts the step at the new maximum. It is a mutating contract
 *     (the settings are part of the circuit text): it runs through {@link Mutation#run}, joins or
 *     opens the agent transaction, and returns {@code connectivity} and {@code transaction}.</li>
 * </ul>
 * Output: {@code {running, simTime, timeStep: {current, max, min, auto}}}.
 */
final class SimControlOps {

    private static final String[] ACTIONS = { "run", "stop", "reset", "configure" };

    private SimControlOps() {
    }

    static void register(AgentApi api) {
        api.registerMutatingWhen("simControl", AgentApi.DocPolicy.OPTIONAL, AgentApi.BusyPolicy.REJECTED,
                args -> args.raw("action") != null && args.raw("action").isString() != null
                        && "configure".equals(args.raw("action").isString().stringValue()),
                SimControlOps::simControl);
        RunController.register(api);
    }

    static OperationResult simControl(AgentApi.Call call) {
        AgentArgs a = call.args;
        String action = a.optEnum("action", ACTIONS, null);
        if (action == null && !a.has("action")) {
            a.invalid("action", "is required", "Use one of: " + String.join(", ", ACTIONS) + ".");
        }
        if (a.failed()) {
            return a.failure();
        }
        if ("configure".equals(action)) {
            return configure(call);
        }
        final CirSim sim = call.sim;
        final CircuitDocument doc = call.doc;
        final boolean visible = doc == sim.getActiveDocument();
        final String act = action;
        return DocumentScope.call(sim, doc, () -> {
            OperationResult r;
            if ("reset".equals(act)) {
                sim.resetAction();
                doc.ensureAnalysed();
                r = OperationResult.success(state(doc));
            } else {
                boolean run = "run".equals(act);
                if (run) {
                    // a stamp left by the last node analysis runs now, so its stop is reported
                    // (and blocks the run) as when the analysis stamped at once
                    doc.stampIfDeferred();
                }
                if (visible) {
                    sim.setSimRunning(run);
                } else if (!run || doc.simulator.getStopMessage() == null) {
                    // CirSim.setSimRunning without its session side effects (toolbar, cursor)
                    doc.setSimRunning(run);
                }
                r = OperationResult.success(state(doc));
                Issue stop = SolverEvents.stopIssue(doc);
                if (run && stop != null) {
                    // the user's Run does nothing in a stop state either
                    r.addIssue(stop);
                }
            }
            return r;
        });
    }

    private static OperationResult configure(AgentApi.Call call) {
        AgentArgs a = call.args;
        final CircuitDocument doc = call.doc;
        JSONValue sv = a.raw("settings");
        JSONObject so = sv == null ? null : sv.isObject();
        Double max = null;
        Double min = null;
        Boolean auto = null;
        if (so == null) {
            a.invalid("settings", sv == null ? "is required for configure" : "must be an object",
                    "Pass settings: {maxTimeStep?, minTimeStep?, autoTimeStep?}.");
        } else {
            max = step(a, so, "maxTimeStep");
            min = step(a, so, "minTimeStep");
            JSONValue av = so.get("autoTimeStep");
            if (av != null && av.isNull() == null) {
                if (av.isBoolean() == null) {
                    a.invalid("settings.autoTimeStep", "must be true or false", "Pass autoTimeStep as a JSON boolean.");
                } else {
                    auto = av.isBoolean().booleanValue();
                }
            }
            if (!a.failed() && max == null && min == null && auto == null) {
                a.invalid("settings", "names no setting", "Pass at least one of maxTimeStep, minTimeStep, autoTimeStep.");
            }
        }
        if (!a.failed()) {
            double effMax = max != null ? max : doc.simulator.maxTimeStep;
            double effMin = min != null ? min : doc.simulator.minTimeStep;
            if (effMin > effMax) {
                a.invalid(min != null ? "settings.minTimeStep" : "settings.maxTimeStep", "gives minTimeStep " + effMin
                        + " s above maxTimeStep " + effMax + " s", "Keep minTimeStep at most maxTimeStep.");
            }
        }
        if (a.failed()) {
            return a.failure();
        }
        final Double fMax = max;
        final Double fMin = min;
        final Boolean fAuto = auto;
        final CirSim sim = call.sim;
        OperationResult result = Mutation.run(sim, doc, ctx -> {
            CircuitSimulator s = doc.simulator;
            if (fMax != null) {
                s.maxTimeStep = fMax;
            }
            if (fMin != null) {
                s.minTimeStep = fMin;
            }
            if (fAuto != null) {
                s.adjustTimeStep = fAuto;
            }
            ctx.checkForcedFailure("in simControl configure");
            // the analysis keeps the settings and starts the current step at the new maximum
            Mutation.finish(ctx);
            return OperationResult.success(state(doc));
        });
        if (doc == sim.getActiveDocument() && sim.controlsDialog != null && sim.timeStepBar != null) {
            // the visible tab's time-step bar shows the new maximum (no bar command: no re-quantising)
            sim.controlsDialog.syncTimeStepBar();
        }
        return result;
    }

    /** @return a positive time step from {@code settings.<key>}, or null when absent/invalid */
    private static Double step(AgentArgs a, JSONObject so, String key) {
        JSONValue v = so.get(key);
        if (v == null || v.isNull() != null) {
            return null;
        }
        Double d = PropertyValues.parseNumber(v, "s");
        if (d == null || d <= 0) {
            a.invalid("settings." + key, d == null ? "is not a number or unit string" : "must be greater than 0",
                    "Pass a positive time step, e.g. 1e-6 or \"1 us\".");
            return null;
        }
        return d;
    }

    /** {@code {running, simTime, timeStep}} of the document. */
    static JSONObject state(CircuitDocument doc) {
        JSONObject data = new JSONObject();
        data.put("running", JSONBoolean.getInstance(doc.isRunning()));
        data.put("simTime", new JSONNumber(doc.simulator.t));
        data.put("timeStep", timeStep(doc.simulator));
        return data;
    }

    /** {@code {current, max, min, auto}} of the simulator's time-step settings. */
    static JSONObject timeStep(CircuitSimulator sim) {
        JSONObject ts = new JSONObject();
        ts.put("current", new JSONNumber(sim.timeStep));
        ts.put("max", new JSONNumber(sim.maxTimeStep));
        ts.put("min", new JSONNumber(sim.minTimeStep));
        ts.put("auto", JSONBoolean.getInstance(sim.adjustTimeStep));
        return ts;
    }
}
