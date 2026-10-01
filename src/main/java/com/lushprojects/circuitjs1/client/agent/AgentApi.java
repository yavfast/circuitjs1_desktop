package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.json.client.JSONValue;
import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.CircuitDocument;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Transport-free Agent API (SP_AGA): dispatches an operation name to its contract handler and
 * applies the common rules of [SP_AGA_02] before the handler runs:
 * <ul>
 * <li>Readiness — {@code not_ready} until application start-up completed.</li>
 * <li>Arguments — parsed as a JSON object; malformed input is {@code invalid_value} for {@code args}.</li>
 * <li>Document — {@code doc} absent means the active document; an unknown handle is
 *     {@code unknown_document} with the open handles as hint.</li>
 * <li>Busy — contracts not served while the document is busy (class table) get {@code busy}.</li>
 * </ul>
 * One instance per session, created by {@link AgentJsBridge}.
 */
public final class AgentApi {

    /** Receives the JSON result of an asynchronous call. */
    public interface ResultCallback {
        void onResult(String resultJson);
    }

    /** How a contract uses the common {@code doc} argument. */
    enum DocPolicy {
        /** Session-scoped contract: {@code doc} is not used (but still validated when given). */
        NONE,
        /** {@code doc} optional; absent means the active document. */
        OPTIONAL,
        /** {@code doc} required. */
        REQUIRED
    }

    /** Busy column of the SP_AGA_02 contract class table. */
    enum BusyPolicy {
        /** Served while the document is busy. */
        SERVED,
        /** Rejected with {@code busy} while the document is busy. */
        REJECTED,
        /** The handler applies its own rule (closeDocument: only with discardChanges). */
        HANDLER
    }

    /** One contract handler. */
    interface Handler {
        OperationResult handle(Call call);
    }

    /** One dispatched call: arguments and the resolved target document. */
    static final class Call {
        final CirSim sim;
        final String op;
        final AgentArgs args;
        /** Resolved target document; null only for {@link DocPolicy#NONE} without {@code doc}. */
        final CircuitDocument doc;

        Call(CirSim sim, String op, AgentArgs args, CircuitDocument doc) {
            this.sim = sim;
            this.op = op;
            this.args = args;
            this.doc = doc;
        }
    }

    private static final class Contract {
        final DocPolicy docPolicy;
        final BusyPolicy busyPolicy;
        final Handler handler;

        Contract(DocPolicy docPolicy, BusyPolicy busyPolicy, Handler handler) {
            this.docPolicy = docPolicy;
            this.busyPolicy = busyPolicy;
            this.handler = handler;
        }
    }

    private final CirSim sim;
    private final Map<String, Contract> contracts = new LinkedHashMap<>();

    public AgentApi(CirSim sim) {
        this.sim = sim;
        DocumentsOps.register(this);
    }

    void register(String op, DocPolicy docPolicy, BusyPolicy busyPolicy, Handler handler) {
        contracts.put(op, new Contract(docPolicy, busyPolicy, handler));
    }

    /**
     * Runs a synchronous contract.
     *
     * @param op       contract name, e.g. {@code "listDocuments"}
     * @param argsJson arguments as a JSON object string; null or blank means none
     * @return the OperationResult as a JSON string ([SP_AGA_01_08])
     */
    public String call(String op, String argsJson) {
        return dispatch(op, argsJson).toJsonString();
    }

    /**
     * Runs a contract and delivers its JSON result to {@code callback}. Synchronous contracts
     * call back before this method returns; the asynchronous ones (run, render) arrive with
     * their phases and call back when they complete.
     */
    public void callAsync(String op, String argsJson, ResultCallback callback) {
        String result = call(op, argsJson);
        if (callback != null) {
            callback.onResult(result);
        }
    }

    OperationResult dispatch(String op, String argsJson) {
        if (!sim.isStartupCompleted()) {
            return OperationResult.failure(Issue.of(IssueCode.NOT_READY,
                    "The application has not finished starting up.",
                    "Retry the call after start-up has completed."));
        }
        Contract contract = op == null ? null : contracts.get(op);
        if (contract == null) {
            return OperationResult.failure(Issue.of(IssueCode.INVALID_VALUE,
                    "Argument 'op' names no known operation: '" + op + "'.",
                    "Use one of: " + String.join(", ", contracts.keySet()) + "."));
        }
        AgentArgs args = AgentArgs.parse(argsJson);
        if (args.failed()) {
            return args.failure();
        }

        CircuitDocument doc = null;
        JSONValue docArg = args.raw("doc");
        if (docArg != null) {
            if (docArg.isString() == null) {
                args.invalid("doc", "must be a document handle string", "Use one of the open documents: "
                        + DocumentHandles.listOpen(sim) + ".");
                return args.failure();
            }
            String handle = docArg.isString().stringValue();
            doc = DocumentHandles.find(sim, handle);
            if (doc == null) {
                return OperationResult.failure(DocumentHandles.unknown(sim, handle));
            }
        } else if (contract.docPolicy == DocPolicy.REQUIRED) {
            args.invalid("doc", "is required", "Pass one of the open documents: " + DocumentHandles.listOpen(sim) + ".");
            return args.failure();
        } else if (contract.docPolicy == DocPolicy.OPTIONAL) {
            doc = sim.getActiveDocument();
        }

        if (doc != null && contract.busyPolicy == BusyPolicy.REJECTED && doc.isAgentBusy()) {
            return OperationResult.failure(busyIssue(doc));
        }
        return contract.handler.handle(new Call(sim, op, args, doc));
    }

    /** @return the {@code busy} issue for a document that an agent run owns */
    static Issue busyIssue(CircuitDocument doc) {
        return Issue.of(IssueCode.BUSY,
                "Document " + DocumentHandles.of(doc) + " is busy with an agent run.",
                "Wait for the run to finish.");
    }
}
