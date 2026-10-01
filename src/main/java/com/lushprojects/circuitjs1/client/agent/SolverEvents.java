package com.lushprojects.circuitjs1.client.agent;

import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.CircuitSimulator;
import com.lushprojects.circuitjs1.client.element.CircuitElm;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * [SP_AGA_03_06] Solver warnings and stops as issues. The document's simulator keeps every
 * warning and stop since its last analysis as an ordered event list with the untranslated
 * message key ({@link CircuitSimulator#getSolverEvents()}); this class maps the keys to codes by
 * prefix, gives the severities and names the culprit element. {@code Diagnostics.events}, the
 * {@code source_or_wire_loop} connectivity rule and (PL_AGA Phase 7) the run's issues read it.
 */
final class SolverEvents {

    private static final String[] PREFIXES = {
        "Singular matrix!",
        "Voltage source/wire loop with no resistance!",
        "Path to ground with no resistance!",
        "wire loop detected",
        "Convergence failed!",
        "Failed to analyze circuit",
        "Matrix error",
    };
    private static final IssueCode[] CODES = {
        IssueCode.SINGULAR_MATRIX,
        IssueCode.SOURCE_OR_WIRE_LOOP,
        IssueCode.GROUND_PATH_NO_RESISTANCE,
        IssueCode.WIRE_LOOP,
        IssueCode.CONVERGENCE_FAILED,
        IssueCode.ANALYSIS_FAILED,
        IssueCode.MATRIX_ERROR,
    };

    private SolverEvents() {
    }

    /** @return the code of a message key: by prefix, else {@code solver_stop}/{@code solver_warning} */
    static IssueCode codeOf(String key, boolean stop) {
        if (key != null) {
            for (int i = 0; i < PREFIXES.length; i++) {
                if (key.startsWith(PREFIXES[i])) {
                    return CODES[i];
                }
            }
        }
        return stop ? IssueCode.SOLVER_STOP : IssueCode.SOLVER_WARNING;
    }

    /**
     * Severity: a stop is an error; as a warning, every code is an error except {@code wire_loop}
     * and {@code solver_warning} (node voltages stay valid).
     */
    static Issue.Severity severityOf(IssueCode code, boolean stop) {
        if (stop) {
            return Issue.Severity.ERROR;
        }
        return code == IssueCode.WIRE_LOOP || code == IssueCode.SOLVER_WARNING ? Issue.Severity.WARNING
                : Issue.Severity.ERROR;
    }

    /** @return the issue of one solver message (key may be null for a stop without key) */
    static Issue toIssue(String key, String text, boolean stop, CircuitElm culprit) {
        IssueCode code = codeOf(key, stop);
        String english = key != null ? key : text != null ? text : "Solver stopped.";
        String message = (stop ? "Solver stopped: " : "Solver warning: ") + english;
        if (!message.endsWith(".") && !message.endsWith("!") && !message.endsWith(")")) {
            message += ".";
        }
        Issue issue = Issue.of(code, severityOf(code, stop), message, hintFor(code));
        String id = culprit == null ? null : culprit.getElementId();
        if (id != null && !id.isEmpty()) {
            issue.elements(id);
        }
        return issue;
    }

    static Issue toIssue(CircuitSimulator.SolverEvent e) {
        return toIssue(e.key, e.text, e.stop, e.culprit);
    }

    /** [SP_AGA_01_11] {@code events}: the document's events in order, each code once. */
    static List<Issue> events(CircuitDocument doc) {
        List<Issue> out = new ArrayList<>();
        Set<IssueCode> seen = new LinkedHashSet<>();
        for (CircuitSimulator.SolverEvent e : doc.simulator.getSolverEvents()) {
            IssueCode code = codeOf(e.key, e.stop);
            if (seen.add(code)) {
                out.add(toIssue(e));
            }
        }
        return out;
    }

    /**
     * @return the culprits of every event with {@code code} since the last analysis, in order
     *         (null for an event without culprit), each once
     */
    static List<CircuitElm> culprits(CircuitDocument doc, IssueCode code) {
        List<CircuitElm> out = new ArrayList<>();
        for (CircuitSimulator.SolverEvent e : doc.simulator.getSolverEvents()) {
            if (codeOf(e.key, e.stop) == code && !out.contains(e.culprit)) {
                out.add(e.culprit);
            }
        }
        return out;
    }

    /**
     * [SP_AGA_01_11] {@code stop}: the stop state as a solver issue, or null. A stop raised
     * outside the solver (an exception in the stepping loop) is {@code solver_stop}.
     */
    static Issue stopIssue(CircuitDocument doc) {
        CircuitSimulator sim = doc.simulator;
        if (sim.getStopMessage() != null) {
            return toIssue(sim.getStopKey(), sim.getStopMessage(), true, sim.getStopElm());
        }
        if (doc.getErrorMessage() != null) {
            return toIssue(doc.getErrorMessage(), doc.getErrorMessage(), true, doc.getStopElm());
        }
        return null;
    }

    /** [SP_AGA_01_11] {@code warning}: the most recent solver warning since the last analysis, or null. */
    static Issue lastWarning(CircuitDocument doc) {
        CircuitSimulator.SolverEvent e = doc.simulator.getLastWarningEvent();
        return e == null ? null : toIssue(e);
    }

    private static String hintFor(IssueCode code) {
        switch (code) {
            case SINGULAR_MATRIX:
                return "Check for floating parts, ideal sources in parallel or a missing ground; getConnectivity lists them.";
            case SOURCE_OR_WIRE_LOOP:
                return "Add resistance to the loop or remove the wire that shorts the source.";
            case GROUND_PATH_NO_RESISTANCE:
                return "Add resistance between the rail or logic input and ground.";
            case WIRE_LOOP:
                return "Remove the redundant wire; only wire currents are approximated.";
            case CONVERGENCE_FAILED:
                return "Reduce the maximum time step, add series resistance or check the named element's parameters.";
            case ANALYSIS_FAILED:
                return "Check for capacitor loops or other degenerate structures; getConnectivity lists connection problems.";
            case MATRIX_ERROR:
                return "Check for floating or unconnected parts; getConnectivity lists them.";
            case SOLVER_STOP:
                return "Fix the cause named in the message, then reset the simulation.";
            default:
                return "See the message; the simulation continues.";
        }
    }
}
