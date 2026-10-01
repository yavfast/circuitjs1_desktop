package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.JavaScriptObject;
import com.lushprojects.circuitjs1.client.CirSim;

/**
 * JSNI adapter (RULE_ARCH_008) exporting the Agent API as
 * {@code window.CircuitJS1Agent = {call(op, argsJson) → resultJson, callAsync(op, argsJson, callback),
 * reportError(message)}}, separate from the {@code window.CircuitJS1} scripting global. It also
 * carries {@code debugViewState()}, a diagnostic for the live harness that is not a contract.
 * <p>
 * Every entry point is wrapped in {@code $entry}: an unexpected Java exception reaches the
 * global uncaught-exception handler (RULE_ERR_004) and the call returns {@code undefined}, which
 * callers map to {@code internal_error}. {@code reportError} lets a JS caller (the MCP server)
 * pass its own unexpected exception to the same handler.
 */
public final class AgentJsBridge {

    private AgentJsBridge() {
    }

    /** Installs {@code window.CircuitJS1Agent}; called from {@code CirSim.setupJSInterface()}. */
    public static void install(CirSim sim) {
        installNative(new AgentApi(sim), sim);
    }

    private static native void installNative(AgentApi api, CirSim sim) /*-{
        // Arguments may be passed as a JSON string (the contract) or, for convenience, as an object.
        var toArgs = function(a) {
            if (a === undefined || a === null) return null;
            if (typeof a === 'object') return JSON.stringify(a);
            return String(a);
        };
        $wnd.CircuitJS1Agent = {
            call: $entry(function(op, argsJson) {
                return api.@com.lushprojects.circuitjs1.client.agent.AgentApi::call(Ljava/lang/String;Ljava/lang/String;)(
                    op == null ? null : String(op), toArgs(argsJson));
            }),
            callAsync: $entry(function(op, argsJson, callback) {
                var cb = @com.lushprojects.circuitjs1.client.agent.AgentJsBridge::wrapCallback(Lcom/google/gwt/core/client/JavaScriptObject;)(callback);
                api.@com.lushprojects.circuitjs1.client.agent.AgentApi::callAsync(Ljava/lang/String;Ljava/lang/String;Lcom/lushprojects/circuitjs1/client/agent/AgentApi$ResultCallback;)(
                    op == null ? null : String(op), toArgs(argsJson), cb);
            }),
            reportError: $entry(function(message) {
                @com.lushprojects.circuitjs1.client.agent.AgentJsBridge::reportError(Ljava/lang/String;)(String(message));
            }),
            // Diagnostic for the live harness, not a contract: session view state as a JSON string.
            debugViewState: $entry(function() {
                return sim.@com.lushprojects.circuitjs1.client.CirSim::getViewStateJson()();
            })
        };
    }-*/;

    private static AgentApi.ResultCallback wrapCallback(final JavaScriptObject fn) {
        if (fn == null) {
            return null;
        }
        return resultJson -> invokeCallback(fn, resultJson);
    }

    private static native void invokeCallback(JavaScriptObject fn, String resultJson) /*-{
        if (typeof fn === 'function') fn(resultJson);
    }-*/;

    private static void reportError(String message) {
        GWT.reportUncaughtException(new RuntimeException("Agent: " + message));
    }
}
