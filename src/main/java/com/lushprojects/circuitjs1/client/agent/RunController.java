package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.core.client.Duration;
import com.google.gwt.core.client.GWT;
import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;
import com.google.gwt.json.client.JSONValue;
import com.google.gwt.user.client.Timer;
import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.CircuitSimulator;
import com.lushprojects.circuitjs1.client.DocumentScope;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.util.EchoText;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * [SP_AGA_02_10] {@code run}: advances the simulated time of one document under agent control,
 * with probes, in slices that yield to the UI ([SP_AGA_04_02] run state).
 * <ol>
 * <li><b>Validation</b> (synchronous): arguments, probe targets ({@link Readings#resolveAll}) and
 *     the stop state. A rejection completes at once with {@code ok = false}; a document in a stop
 *     state without {@code reset} completes at once with {@code solver_stop} and no step.</li>
 * <li><b>Busy.</b> The document is marked busy ({@link CircuitDocument#setAgentBusy}) from the
 *     accepted call to the end: the free-running loop skips it, contracts not served while busy
 *     are rejected, and a user action or a close calls {@link #cancel()}.</li>
 * <li><b>Slices.</b> Each slice binds the document through {@code DocumentScope} and takes
 *     timesteps ({@link CircuitSimulator#runSteps}) until {@link Slices#SLICE_MS} minus
 *     {@link Slices#SLICE_MARGIN_MS} after the slice started, just before the scope is entered (the
 *     margin covers the bind and unbind, timer granularity and engine pauses), or an end condition
 *     holds; after every step the probes record. Between slices the run yields through the
 *     session-wide slice queue ({@link Slices#afterVisibleFrame}): at most one slice of any
 *     operation per frame of a free-running visible tab (PL_AGA_DEC_01 condition 4). The
 *     document is repainted once per slice when it is the visible tab.</li>
 * <li><b>First sample.</b> The run's start state is sampled only when the circuit is solved
 *     ({@link CircuitSimulator#isSolved}); after an import, an edit or a reset the first sample
 *     follows the first timestep, so {@code samples} is {@code steps + 1} or {@code steps}.</li>
 * <li><b>End conditions</b>, checked after every step in the spec's order: solver stop, a fired
 *     stop-trigger element ({@code stop_trigger}), {@code tEnd} reached ({@code span_reached};
 *     {@code settle_timeout} in settle mode), settled, budget used up; a cancel request ends the
 *     run at the slice boundary.</li>
 * <li><b>Exceptions.</b> An exception inside a slice ends the run with {@code solver_stop} and an
 *     {@code internal_error} issue, stops the document as the free-running loop does, and is passed
 *     to the global uncaught-exception handler ([SP_AGA_03_10], RULE_ERR_004).</li>
 * <li><b>Completion.</b> Busy is cleared, the free-run pacing restarts ({@code lastIterTime = 0},
 *     so a running document does not catch up the run's wall time) and the completion is invoked
 *     exactly once, whatever ended the run, on a zero-delay task of its own (a cancel arrives
 *     inside a user action).</li>
 * </ol>
 * Issues: the solver events present when the run started plus those raised during it, each code
 * once ([SP_AGA_03_06]); the stop issue for {@code solver_stop}; a warning with the reason's code
 * for {@code budget_exhausted}, {@code settle_timeout} and {@code cancelled}; for
 * {@code stop_trigger} a warning naming the element.
 */
final class RunController implements CircuitDocument.BusyOwner, CircuitSimulator.StepObserver {

    static final int MAX_PROBES = 16;
    static final int DEFAULT_MAX_POINTS = 200;
    static final int MIN_MAX_POINTS = 10;
    /** Upper bound of {@code maxPoints} per probe and of its sum over the probes. */
    static final int MAX_TOTAL_POINTS = 2000;
    static final int DEFAULT_BUDGET_MS = 10000;
    static final int MIN_BUDGET_MS = 100;
    static final int MAX_BUDGET_MS = 120000;
    static final double DEFAULT_TOLERANCE = 1e-4;
    static final double DEFAULT_MAX_SPAN = 1;
    /** Default settle window in maximum time steps. */
    static final int DEFAULT_WINDOW_STEPS = 50;

    private static final String[] MODES = { "span", "settle" };

    /** Why a run ended ([SP_AGA_02_10] {@code reason}). */
    enum Reason {
        SPAN_REACHED("span_reached"),
        SETTLED("settled"),
        SETTLE_TIMEOUT("settle_timeout"),
        SOLVER_STOP("solver_stop"),
        STOP_TRIGGER("stop_trigger"),
        BUDGET_EXHAUSTED("budget_exhausted"),
        CANCELLED("cancelled");

        final String wire;

        Reason(String wire) {
            this.wire = wire;
        }
    }

    /** Armed by the harness diagnostic {@code debugFailNextRunSlice()}; consumed once. */
    private static boolean failNextSlice;

    /** Arms a forced exception inside the next run slice (harness diagnostic, not a contract). */
    static void armForcedFailure() {
        failNextSlice = true;
    }

    static void register(AgentApi api) {
        api.registerAsync("run", AgentApi.DocPolicy.OPTIONAL, AgentApi.BusyPolicy.REJECTED, RunController::start);
    }

    // ---------------------------------------------------------------- configuration

    private final CirSim sim;
    private final CircuitDocument doc;
    private final AgentApi.Completion done;
    private final boolean settleMode;
    private final double span;
    private final double tolerance;
    private final double window;
    private final double maxSpan;
    private final int budgetMs;
    /** Absolute simulated time from which probes record; NaN: from the run start. */
    private final double recordFrom;
    private final boolean reset;
    private final List<ProbeRecorder> recorders;
    private final boolean wireCurrentsEachStep;
    /** Simulator nodes of the document's nets (the settle condition reads every net voltage). */
    private final int[] netNodes;

    // ---------------------------------------------------------------- state

    private final double runStart;
    private double sliceDeadline;
    private boolean started;
    private double tStart;
    private double tEnd;
    private int steps;
    private Reason reason;
    private boolean inSlice;
    private boolean cancelRequested;
    private boolean finished;
    private SettleDetector settle;
    private double recordStart;
    /** Solver events present at the start and raised during the run, in order. */
    private final List<CircuitSimulator.SolverEvent> events = new ArrayList<>();
    private Issue internalError;
    /** The stop-trigger element that ended the run ({@code stop_trigger}). */
    private CircuitElm stopTrigger;

    private RunController(AgentApi.Call call, AgentApi.Completion done, boolean settleMode, double span,
            double tolerance, double window, double maxSpan, int budgetMs, double recordFrom, boolean reset,
            List<ProbeRecorder> recorders, int[] netNodes) {
        this.sim = call.sim;
        this.doc = call.doc;
        this.done = done;
        this.settleMode = settleMode;
        this.span = span;
        this.tolerance = tolerance;
        this.window = window;
        this.maxSpan = maxSpan;
        this.budgetMs = budgetMs;
        this.recordFrom = recordFrom;
        this.reset = reset;
        this.recorders = recorders;
        this.netNodes = netNodes;
        boolean wires = false;
        for (ProbeRecorder r : recorders) {
            // element current/power probes read wire currents, which a frame may compute late
            wires |= r.target.kind == Readings.ProbeTarget.Kind.ELEMENT && !"voltage".equals(r.target.quantity);
        }
        this.wireCurrentsEachStep = wires;
        this.runStart = Duration.currentTimeMillis();
    }

    // ---------------------------------------------------------------- validation and start

    /** The {@code run} contract: validates, then starts the sliced run or completes at once. */
    static void start(final AgentApi.Call call, final AgentApi.Completion done) {
        AgentArgs a = call.args;
        String mode = a.optEnum("mode", MODES, "span");
        boolean settleMode = "settle".equals(mode);
        double span = 0;
        if (!settleMode) {
            if (!a.has("span")) {
                a.invalid("span", "is required in span mode", "Pass span as seconds or a unit string, e.g. \"20 ms\".");
            } else {
                span = positive(a, "span", a.raw("span"), "s");
            }
        }
        double tolerance = DEFAULT_TOLERANCE;
        double windowArg = Double.NaN;
        double maxSpan = DEFAULT_MAX_SPAN;
        JSONValue settleArg = a.raw("settle");
        if (settleArg != null) {
            JSONObject so = settleArg.isObject();
            if (so == null) {
                a.invalid("settle", "must be an object {tolerance?, window?, maxSpan?}", "Pass settle: {tolerance: 1e-4, maxSpan: 1}.");
            } else {
                if (present(so, "tolerance")) {
                    tolerance = positive(a, "settle.tolerance", so.get("tolerance"), "V");
                }
                if (present(so, "window")) {
                    windowArg = positive(a, "settle.window", so.get("window"), "s");
                }
                if (present(so, "maxSpan")) {
                    maxSpan = positive(a, "settle.maxSpan", so.get("maxSpan"), "s");
                }
            }
        }
        int budgetMs = a.optInt("budgetMs", MIN_BUDGET_MS, MAX_BUDGET_MS, DEFAULT_BUDGET_MS);
        final JSONArray probes = a.optArray("probes", 0, MAX_PROBES);
        double recordFrom = Double.NaN;
        if (a.has("recordFrom")) {
            Double rf = PropertyValues.parseNumber(a.raw("recordFrom"), "s");
            if (rf == null) {
                a.invalid("recordFrom", "must be a time in seconds", "Pass an absolute simulated time, e.g. 0.002 or \"2 ms\".");
            } else {
                recordFrom = rf;
            }
        }
        final int maxPoints = a.optInt("maxPoints", MIN_MAX_POINTS, MAX_TOTAL_POINTS, DEFAULT_MAX_POINTS);
        boolean reset = a.optBool("reset", false);
        int probeCount = probes == null ? 0 : probes.size();
        if (!a.failed() && probeCount * maxPoints > MAX_TOTAL_POINTS) {
            a.invalid("maxPoints", "gives " + probeCount + " x " + maxPoints + " = " + (probeCount * maxPoints)
                    + " points, above " + MAX_TOTAL_POINTS, "Keep maxPoints x probes at most " + MAX_TOTAL_POINTS + ".");
        }
        if (a.failed()) {
            done.complete(a.failure());
            return;
        }
        final CircuitDocument doc = call.doc;
        if (doc.simulator.elmList.isEmpty()) {
            done.complete(OperationResult.failure(Issue.of(IssueCode.INVALID_VALUE,
                    "Argument 'doc': document " + DocumentHandles.of(doc) + " has no elements to simulate.",
                    "Import or add a circuit first.")));
            return;
        }

        // probe targets and the nets, from the document's own analysis
        final List<Issue> issues = new ArrayList<>();
        final List<ProbeRecorder> recorders = new ArrayList<>();
        final List<Integer> nodes = new ArrayList<>();
        DocumentScope.run(call.sim, doc, () -> {
            // the probes need only the nets; a reset run resets and stamps in its first slice,
            // so stamping here too would LU-factor a linear circuit twice (O(m³) each)
            if (reset) {
                doc.ensureNodesAnalysed();
            } else {
                // [SP_SLV_02_09] a pending solver-mode change re-stamps here, before the run owns
                // the document (a reset run adopts it just before it starts, below)
                doc.ensureAnalysed();
            }
            Connectivity.Nets nets = Connectivity.nets(doc);
            if (probes != null && probes.size() > 0) {
                List<Readings.ProbeTarget> targets = Readings.resolveAll(probes, "probes", nets, CircuitView.byId(doc), issues);
                if (targets != null) {
                    for (Readings.ProbeTarget t : targets) {
                        recorders.add(new ProbeRecorder(t, maxPoints));
                    }
                }
            }
            if (nets.analysed) {
                for (Connectivity.Net n : nets.list) {
                    if (n.node > 0) {
                        nodes.add(n.node);
                    }
                }
            }
        });
        if (!issues.isEmpty()) {
            done.complete(OperationResult.failure(issues));
            return;
        }
        int[] netNodes = new int[nodes.size()];
        for (int i = 0; i < netNodes.length; i++) {
            netNodes[i] = nodes.get(i);
        }
        double window = Double.isNaN(windowArg) ? DEFAULT_WINDOW_STEPS * doc.simulator.maxTimeStep : windowArg;
        RunController run = new RunController(call, done, settleMode, span, tolerance, window, maxSpan, budgetMs,
                recordFrom, reset, recorders, netNodes);
        run.collectEvents();
        if (!reset && SolverEvents.stopIssue(doc) != null) {
            // [SP_AGA_02_10] a stopped document is not stepped without reset
            run.tStart = run.tEnd = doc.simulator.t;
            run.reason = Reason.SOLVER_STOP;
            run.finished = true;
            done.complete(run.safeResult());
            return;
        }
        if (reset) {
            // [SP_SLV_02_09] a pending solver-mode change applies to the reset stamp
            doc.consumeSolverRestamp(false);
        }
        doc.setAgentBusy(run);
        Slices.afterVisibleFrame(call.sim, doc, run::slice);
    }

    private static boolean present(JSONObject o, String key) {
        JSONValue v = o.get(key);
        return v != null && v.isNull() == null;
    }

    /** @return a positive number or unit string ({@code unit}); records invalid_value otherwise */
    private static double positive(AgentArgs a, String name, JSONValue v, String unit) {
        Double d = PropertyValues.parseNumber(v, unit);
        if (d == null || d <= 0) {
            a.invalid(name, d == null ? "is not a number or unit string" : "must be greater than 0",
                    "Pass a positive value, e.g. " + ("V".equals(unit) ? "1e-4 or \"0.1 mV\"." : "0.005 or \"5 ms\"."));
            return 1;
        }
        return d;
    }

    // ---------------------------------------------------------------- slices

    private void slice() {
        if (finished) {
            return;
        }
        if (!sim.documentManager.getDocuments().contains(doc)) {
            // closed without a cancel request reaching the run: never step a closed document
            reason = Reason.CANCELLED;
            finish();
            return;
        }
        // the slice budget starts before the scope entry, so the bind cost counts ([SP_AGA_03_08] R1)
        Slices.begin("run", doc);
        sliceDeadline = Slices.deadline();
        Throwable failure = null;
        inSlice = true;
        try {
            DocumentScope.run(sim, doc, this::sliceBody);
        } catch (Throwable t) {
            failure = t;
        } finally {
            inSlice = false;
            Slices.end("run", doc);
        }
        try {
            collectEvents();
        } catch (Throwable t) {
            if (failure == null) {
                failure = t;
            }
        }
        if (failure != null) {
            reason = Reason.SOLVER_STOP;
            internalError = Issue.of(IssueCode.INTERNAL_ERROR, "Internal error during the run: " + failure.getMessage(),
                    "Report the error; reset the simulation (simControl reset) and run again.");
            try {
                // the document stops as the free-running loop stops it for an exception
                doc.stop("Exception in simulation: " + failure.getMessage(), null);
                // shown and logged as before (RULE_ERR_004)
                GWT.reportUncaughtException(failure);
            } finally {
                finish();
            }
            return;
        }
        if (reason == null && cancelRequested) {
            reason = Reason.CANCELLED;
        }
        if (reason != null) {
            finish();
            return;
        }
        if (doc == sim.getActiveDocument()) {
            sim.repaint();
        }
        scheduleNextSlice();
    }

    private void sliceBody() {
        CircuitSimulator s = doc.simulator;
        if (!started) {
            started = true;
            if (reset) {
                // the user's Reset: time 0, element state, scope histories, stop state
                sim.resetAction();
            }
            doc.ensureAnalysed();
            // a trigger that fired before the run (free-running) does not end it
            doc.takeFiredStopTrigger();
            tStart = s.t;
            tEnd = tStart + (settleMode ? maxSpan : span);
            recordStart = Double.isNaN(recordFrom) ? tStart : recordFrom;
            if (settleMode) {
                settle = new SettleDetector(netNodes.length, window, tolerance);
            }
            // [SP_AGA_02_10] First sample: the state the run starts from, only when it is solved
            // (after an import, an edit or a reset the node voltages are not; the first timestep
            // gives the first sample)
            if (s.isSolved()) {
                sample(s.t);
            }
        }
        if (failNextSlice) {
            failNextSlice = false;
            throw new IllegalStateException("debugFailNextRunSlice: forced failure inside a run slice");
        }
        int idle = 0;
        while (reason == null && !cancelRequested) {
            if (!doc.ensureAnalysed() || stopped()) {
                reason = Reason.SOLVER_STOP;
                return;
            }
            double t0 = s.t;
            s.runSteps(this, wireCurrentsEachStep);
            if (reason != null) {
                return;
            }
            if (stopped()) {
                reason = Reason.SOLVER_STOP;
                return;
            }
            if (s.t >= tEnd) {
                // reached through a step forced past the end without convergence
                reason = settleMode ? Reason.SETTLE_TIMEOUT : Reason.SPAN_REACHED;
                return;
            }
            double now = Duration.currentTimeMillis();
            if (now - runStart >= budgetMs) {
                reason = Reason.BUDGET_EXHAUSTED;
                return;
            }
            if (now >= sliceDeadline) {
                return;
            }
            // a call that took no step (a re-stamp ended it): retry, but never spin a slice away
            idle = s.t == t0 ? idle + 1 : 0;
            if (idle >= 3) {
                return;
            }
        }
    }

    /** [SP_AGA_02_10] After every completed timestep: probes record, then the end conditions. */
    @Override
    public boolean afterStep() {
        steps++;
        double t = doc.simulator.t;
        sample(t);
        if (cancelRequested) {
            // a cancel raised inside the slice (e.g. by a hook): no further step of this document
            return false;
        }
        if (stopped()) {
            reason = Reason.SOLVER_STOP;
            return false;
        }
        // [SP_AGA_02_10] Stop trigger (SP_AGA_DEC_05): the element cleared the running flag
        CircuitElm trigger = doc.takeFiredStopTrigger();
        if (trigger != null) {
            stopTrigger = trigger;
            reason = Reason.STOP_TRIGGER;
            return false;
        }
        if (t >= tEnd) {
            reason = settleMode ? Reason.SETTLE_TIMEOUT : Reason.SPAN_REACHED;
            return false;
        }
        if (settle != null && settle.settled()) {
            reason = Reason.SETTLED;
            return false;
        }
        double now = Duration.currentTimeMillis();
        if (now - runStart >= budgetMs) {
            reason = Reason.BUDGET_EXHAUSTED;
            return false;
        }
        return now < sliceDeadline;
    }

    /**
     * [SP_AGA_02_10] A step forced through without convergence advanced simulated time: it counts
     * as a step and the probes sample it (the last stable solution). The stepping loop then ends
     * its call; the slice checks the end conditions.
     */
    @Override
    public void afterForcedStep() {
        steps++;
        sample(doc.simulator.t);
    }

    private void sample(double t) {
        if (t >= recordStart) {
            for (ProbeRecorder r : recorders) {
                r.record(t, r.target.value(doc));
            }
        }
        if (settle != null) {
            settle.begin(t);
            for (int i = 0; i < netNodes.length; i++) {
                settle.add(i, Readings.netVoltage(doc, netNodes[i]));
            }
        }
    }

    /** @return true in a stop state (solver stop or a stepping exception); cheap, checked every step */
    private boolean stopped() {
        return doc.simulator.getStopMessage() != null || doc.getErrorMessage() != null;
    }

    /** PL_AGA_DEC_01 condition 4: the next slice runs after one frame of another free-running visible tab. */
    private void scheduleNextSlice() {
        Slices.afterVisibleFrame(sim, doc, this::slice);
    }

    // ---------------------------------------------------------------- cancel and completion

    /**
     * [SP_AGA_04_02] Cancel request: between slices the run ends now with {@code cancelled}
     * (the caller's user action or close then finds the document idle); inside a slice it ends
     * at that slice's boundary.
     */
    @Override
    public void cancel() {
        if (finished) {
            return;
        }
        cancelRequested = true;
        if (!inSlice) {
            reason = Reason.CANCELLED;
            finish();
        }
    }

    /** Clears busy and invokes the completion, exactly once. */
    private void finish() {
        if (finished) {
            return;
        }
        // a pending slice continuation finds the run finished and does nothing
        finished = true;
        OperationResult result = null;
        Throwable failure = null;
        try {
            if (!started) {
                tStart = tEnd = doc.simulator.t;
            }
            result = safeResult();
            if (doc == sim.getActiveDocument()) {
                sim.repaint();
            }
        } catch (Throwable t) {
            failure = t;
        } finally {
            doc.setAgentBusy(null);
            // the free-run pacing restarts instead of catching up the run's wall time
            doc.simulator.lastIterTime = 0;
            // Delivered on a task of its own: a cancel comes from inside a user action (an edit, a
            // tab close), which must not run the caller's callback code in its midst.
            final OperationResult r = result != null ? result : internalFailure(failure);
            new Timer() {
                @Override
                public void run() {
                    done.complete(r);
                }
            }.schedule(0);
        }
        if (failure != null) {
            GWT.reportUncaughtException(failure);
        }
    }

    /**
     * @return the run's result; when building it throws, {@code internal_error} (the exception
     *         is passed to the global handler, [SP_AGA_03_10])
     */
    private OperationResult safeResult() {
        try {
            return result();
        } catch (Throwable t) {
            GWT.reportUncaughtException(t);
            return internalFailure(t);
        }
    }

    private static OperationResult internalFailure(Throwable t) {
        return OperationResult.failure(Issue.of(IssueCode.INTERNAL_ERROR,
                "Internal error while completing the run: " + (t == null ? "unknown" : t.getMessage()),
                "Report the error; the run can be retried."));
    }

    private void collectEvents() {
        for (CircuitSimulator.SolverEvent e : doc.simulator.getSolverEvents()) {
            if (!events.contains(e)) {
                events.add(e);
            }
        }
    }

    private OperationResult result() {
        JSONObject data = new JSONObject();
        data.put("reason", new JSONString(reason.wire));
        double tNow = started ? doc.simulator.t : tStart;
        data.put("tStart", new JSONNumber(tStart));
        data.put("tEnd", new JSONNumber(tNow));
        data.put("steps", new JSONNumber(steps));
        data.put("wallMs", new JSONNumber(Math.round(Duration.currentTimeMillis() - runStart)));
        JSONArray probes = new JSONArray();
        for (ProbeRecorder r : recorders) {
            probes.set(probes.size(), r.toJson());
        }
        data.put("probes", probes);
        OperationResult result = OperationResult.success(data);
        Set<IssueCode> seen = new LinkedHashSet<>();
        if (reason == Reason.SOLVER_STOP) {
            Issue stop = SolverEvents.stopIssue(doc);
            if (stop != null && seen.add(stop.getCode())) {
                result.addIssue(stop);
            }
        }
        for (CircuitSimulator.SolverEvent e : events) {
            if (seen.add(SolverEvents.codeOf(e.key, e.stop))) {
                result.addIssue(SolverEvents.toIssue(e));
            }
        }
        if (internalError != null) {
            result.addIssue(internalError);
        }
        switch (reason) {
            case BUDGET_EXHAUSTED:
                result.addIssue(Issue.of(IssueCode.BUDGET_EXHAUSTED, "The run used its wall-clock budget of " + budgetMs
                        + " ms before its end.", "Raise budgetMs (at most " + MAX_BUDGET_MS + ") or shorten the span."));
                break;
            case SETTLE_TIMEOUT:
                result.addIssue(Issue.of(IssueCode.SETTLE_TIMEOUT, "The circuit did not settle within maxSpan ("
                        + maxSpan + " s).", "Check for oscillation, or raise settle.tolerance or settle.maxSpan."));
                break;
            case STOP_TRIGGER:
                String id = stopTrigger.getElementId();
                result.addIssue(Issue.of(IssueCode.STOP_TRIGGER, "Stop trigger " + id + " stopped the simulation at t = "
                        + CircuitElm.getTimeText(tNow) + ".", "Change or remove the stop trigger to run past its condition; reset to run again from the start.")
                        .elements(id));
                break;
            case CANCELLED:
                result.addIssue(Issue.of(IssueCode.CANCELLED, "The run was cancelled by a user action on the document or its close.",
                        "Run again when the document is idle; the probe data covers the part before the cancel."));
                break;
            default:
                break;
        }
        for (ProbeRecorder r : recorders) {
            if (r.nonFiniteCount() > 0) {
                result.addIssue(Issue.of(IssueCode.SOLVER_WARNING, "Probe '" + EchoText.clip(r.target.name) + "' read " + r.nonFiniteCount()
                        + " non-finite values; they are left out of its stats and series.", "Check getDiagnostics for solver problems."));
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- settle detection

    /**
     * [SP_AGA_02_10] {@code settled(window, tol)}: at least {@code window} seconds of simulated time
     * since the run started and, over the last {@code window} seconds, every net voltage's
     * {@code max − min < tol}. The window is tracked in chunks of {@code window / 8} holding each
     * net's min and max; the check runs when a chunk closes and covers the newest closed chunks
     * that span at least {@code window} (so up to one chunk more than the window: never looser).
     */
    static final class SettleDetector {
        private static final int CHUNKS = 8;
        private final int nets;
        private final double window;
        private final double tolerance;
        private final double chunkWidth;
        /** Closed chunks, oldest first: start, end, min[], max[]. */
        private final List<double[]> closedMin = new ArrayList<>();
        private final List<double[]> closedMax = new ArrayList<>();
        private final List<Double> closedLen = new ArrayList<>();
        private double[] curMin;
        private double[] curMax;
        private double curStart = Double.NaN;
        private boolean settledNow;

        SettleDetector(int nets, double window, double tolerance) {
            this.nets = nets;
            this.window = window;
            this.tolerance = tolerance;
            this.chunkWidth = window / CHUNKS;
        }

        /** Starts the sample at time {@code t}; closes the current chunk when it is full. */
        void begin(double t) {
            if (Double.isNaN(curStart)) {
                open(t);
                return;
            }
            if (t - curStart >= chunkWidth) {
                closedMin.add(curMin);
                closedMax.add(curMax);
                closedLen.add(t - curStart);
                open(t);
                settledNow = check();
            }
        }

        void add(int net, double v) {
            if (v < curMin[net]) {
                curMin[net] = v;
            }
            if (v > curMax[net]) {
                curMax[net] = v;
            }
        }

        boolean settled() {
            return settledNow;
        }

        private void open(double t) {
            curStart = t;
            curMin = new double[nets];
            curMax = new double[nets];
            for (int i = 0; i < nets; i++) {
                curMin[i] = Double.POSITIVE_INFINITY;
                curMax[i] = Double.NEGATIVE_INFINITY;
            }
        }

        private boolean check() {
            // the newest closed chunks covering the window; older ones are dropped
            double covered = 0;
            int first = closedLen.size();
            while (first > 0 && covered < window) {
                first--;
                covered += closedLen.get(first);
            }
            for (int i = 0; i < first; i++) {
                closedMin.remove(0);
                closedMax.remove(0);
                closedLen.remove(0);
            }
            if (covered < window) {
                return false;
            }
            for (int n = 0; n < nets; n++) {
                double mn = Double.POSITIVE_INFINITY;
                double mx = Double.NEGATIVE_INFINITY;
                for (int c = 0; c < closedMin.size(); c++) {
                    mn = Math.min(mn, closedMin.get(c)[n]);
                    mx = Math.max(mx, closedMax.get(c)[n]);
                }
                if (!(mx - mn < tolerance)) {
                    return false;
                }
            }
            return true;
        }
    }
}
