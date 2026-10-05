package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.json.client.JSONValue;
import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.util.EchoText;

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

    /** Receives the result of an asynchronous contract; called exactly once. */
    interface Completion {
        void complete(OperationResult result);
    }

    /**
     * Handler of an asynchronous contract (run, render): completes now (a rejection) or later,
     * exactly once, through {@code done}.
     */
    interface AsyncHandler {
        void handle(Call call, Completion done);
    }

    /** Decides per call whether a contract is mutating (simControl: only {@code configure}). */
    interface MutatingWhen {
        boolean test(AgentArgs args);
    }

    private static final MutatingWhen NEVER = args -> false;
    private static final MutatingWhen ALWAYS = args -> true;

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
        /** Mutating column of the class table: the result carries {@code transaction}. */
        final MutatingWhen mutating;
        /** Exactly one of handler and asyncHandler is set. */
        final Handler handler;
        final AsyncHandler asyncHandler;

        Contract(DocPolicy docPolicy, BusyPolicy busyPolicy, MutatingWhen mutating, Handler handler,
                AsyncHandler asyncHandler) {
            this.docPolicy = docPolicy;
            this.busyPolicy = busyPolicy;
            this.mutating = mutating;
            this.handler = handler;
            this.asyncHandler = asyncHandler;
        }
    }

    private final CirSim sim;
    private final Map<String, Contract> contracts = new LinkedHashMap<>();

    public AgentApi(CirSim sim) {
        this.sim = sim;
        DocumentsOps.register(this);
        CatalogueOps.register(this);
        ModelOps.register(this);
        ImportOps.register(this);
        EditOps.register(this);
        CircuitView.register(this);
        Connectivity.register(this);
        LayoutOps.register(this);
        Readings.register(this);
        DiagnosticsOps.register(this);
        HistoryOps.register(this);
        SimControlOps.register(this);
        RenderOps.register(this);
        FileOps.register(this);
    }

    void register(String op, DocPolicy docPolicy, BusyPolicy busyPolicy, Handler handler) {
        contracts.put(op, new Contract(docPolicy, busyPolicy, NEVER, handler, null));
    }

    /**
     * Registers a mutating contract (SP_AGA_02 class table): every result, also a rejection,
     * carries the target document's {@code transaction} state ([SP_AGA_01_08]).
     */
    void registerMutating(String op, DocPolicy docPolicy, BusyPolicy busyPolicy, Handler handler) {
        contracts.put(op, new Contract(docPolicy, busyPolicy, ALWAYS, handler, null));
    }

    /**
     * Registers a contract that is mutating for some calls only (simControl {@code configure}):
     * the results of those calls carry {@code transaction} like {@link #registerMutating}.
     */
    void registerMutatingWhen(String op, DocPolicy docPolicy, BusyPolicy busyPolicy, MutatingWhen when,
            Handler handler) {
        contracts.put(op, new Contract(docPolicy, busyPolicy, when, handler, null));
    }

    /**
     * Registers an asynchronous contract ([SP_AGA_02] "Timing": run, render). It is served by
     * {@link #callAsync}; a synchronous {@link #call} of it is rejected with {@code invalid_value}.
     */
    void registerAsync(String op, DocPolicy docPolicy, BusyPolicy busyPolicy, AsyncHandler handler) {
        contracts.put(op, new Contract(docPolicy, busyPolicy, NEVER, null, handler));
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
     * Runs a contract and delivers its JSON result to {@code callback}. Synchronous contracts,
     * and asynchronous ones rejected by a common rule or argument check, call back before this
     * method returns; an accepted asynchronous contract ({@code run}, {@code render}) calls back
     * exactly once when it completes.
     */
    public void callAsync(String op, String argsJson, final ResultCallback callback) {
        Contract contract = op == null ? null : contracts.get(op);
        if (contract == null || contract.asyncHandler == null || !sim.isStartupCompleted()) {
            String result = call(op, argsJson);
            if (callback != null) {
                callback.onResult(result);
            }
            return;
        }
        Prepared p = prepare(contract, op, AgentArgs.parse(argsJson));
        if (p.failure != null) {
            deliver(callback, p.failure);
            return;
        }
        contract.asyncHandler.handle(p.call, result -> deliver(callback, result));
    }

    private static void deliver(ResultCallback callback, OperationResult result) {
        if (callback != null) {
            callback.onResult(result.toJsonString());
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
                    "Argument 'op' names no known operation: '" + EchoText.clip(op) + "'.",
                    "Use one of: " + String.join(", ", contracts.keySet()) + "."));
        }
        if (contract.asyncHandler != null) {
            return OperationResult.failure(Issue.of(IssueCode.INVALID_VALUE,
                    "Argument 'op': operation '" + op + "' completes asynchronously.",
                    "Call it through CircuitJS1Agent.callAsync(op, argsJson, callback)."));
        }
        AgentArgs args = AgentArgs.parse(argsJson);
        OperationResult result = dispatch(contract, op, args);
        if (contract.mutating.test(args)) {
            // a rejection never changes the transaction; the field reflects its state
            CircuitDocument target = targetOf(contract, args);
            if (target != null) {
                result.setTransaction(AgentTransaction.toJson(target));
            }
        }
        return result;
    }

    /** @return the document a call addresses, or null when it names no open document */
    private CircuitDocument targetOf(Contract contract, AgentArgs args) {
        JSONValue docArg = args.raw("doc");
        if (docArg != null) {
            return docArg.isString() == null ? null : DocumentHandles.find(sim, docArg.isString().stringValue());
        }
        return contract.docPolicy == DocPolicy.OPTIONAL ? sim.getActiveDocument() : null;
    }

    private OperationResult dispatch(Contract contract, String op, AgentArgs args) {
        Prepared p = prepare(contract, op, args);
        return p.failure != null ? p.failure : contract.handler.handle(p.call);
    }

    /** Outcome of the common rules: the call for the handler, or the rejection. */
    private static final class Prepared {
        final Call call;
        final OperationResult failure;

        Prepared(Call call, OperationResult failure) {
            this.call = call;
            this.failure = failure;
        }
    }

    /** Applies the common rules: arguments parsed, document resolved, busy policy. */
    private Prepared prepare(Contract contract, String op, AgentArgs args) {
        if (args.failed()) {
            return new Prepared(null, args.failure());
        }

        CircuitDocument doc = null;
        JSONValue docArg = args.raw("doc");
        if (docArg != null) {
            if (docArg.isString() == null) {
                args.invalid("doc", "must be a document handle string", "Use one of the open documents: "
                        + DocumentHandles.listOpen(sim) + ".");
                return new Prepared(null, args.failure());
            }
            String handle = docArg.isString().stringValue();
            doc = DocumentHandles.find(sim, handle);
            if (doc == null) {
                return new Prepared(null, OperationResult.failure(DocumentHandles.unknown(sim, handle)));
            }
        } else if (contract.docPolicy == DocPolicy.REQUIRED) {
            args.invalid("doc", "is required", "Pass one of the open documents: " + DocumentHandles.listOpen(sim) + ".");
            return new Prepared(null, args.failure());
        } else if (contract.docPolicy == DocPolicy.OPTIONAL) {
            doc = sim.getActiveDocument();
        }

        if (doc != null && contract.busyPolicy == BusyPolicy.REJECTED && doc.isAgentBusy()) {
            return new Prepared(null, OperationResult.failure(busyIssue(doc)));
        }
        return new Prepared(new Call(sim, op, args, doc), null);
    }

    /** @return the {@code busy} issue for a document that an agent run owns */
    static Issue busyIssue(CircuitDocument doc) {
        return Issue.of(IssueCode.BUSY,
                "Document " + DocumentHandles.of(doc) + " is busy with an agent run.",
                "Wait for the run to finish.");
    }
}
