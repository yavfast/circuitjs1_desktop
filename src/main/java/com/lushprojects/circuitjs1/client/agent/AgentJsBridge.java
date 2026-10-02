package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.CanvasElement;
import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONBoolean;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;
import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.DocumentScope;

/**
 * JSNI adapter (RULE_ARCH_008) exporting the Agent API as
 * {@code window.CircuitJS1Agent = {call(op, argsJson) → resultJson, callAsync(op, argsJson, callback),
 * reportError(message)}}, separate from the {@code window.CircuitJS1} scripting global. It also
 * carries diagnostics for the live harness that are not contracts: {@code debugViewState()} (session
 * view state), {@code debugDocState(handle)} (undo/redo depth, modified flag, open marks and editor
 * grid of a document, its agent transaction and Undo/Redo menu labels), {@code debugFailNextMutation()}
 * (the next importCircuit/applyEdits throws after its first change, to exercise the rollback guard
 * of SP_AGA_03_10) and {@code debugSetIdleSealMs(ms)} (the idle time after which an open agent
 * transaction is sealed, 300 s by default; 0 restores it — for the idle-seal check of SP_AGA_05_04),
 * {@code debugAgentOriginPush(handle)} (an editor undo push under agent origin, which must neither
 * push nor seal), {@code debugFailNextUndoLoad()} (the next undo/redo load throws after loading,
 * to check that the stacks and the document are restored) and {@code debugFailNextRunSlice()} (the
 * next slice of an agent run throws inside its document scope, to check that the run ends with
 * {@code internal_error}, reaches the global handler and still calls back — PL_AGA Phase 7).
 * PL_AGA Phase 8 adds {@code debugSetSliceProbe(fn)} ({@code fn(op, doc, phase)} before and after
 * every run/render slice, to sample the R1 state and time the slices), {@code debugSessionState()}
 * (the session UI that R1 protects), {@code debugCanvasPixels()} (renders the visible tab now and
 * returns its canvas as a PNG data URL), {@code debugClosedTabs()} (the closed-tab dumps) and
 * {@code debugFailNextSvgLoad()} (the next load of the vector exporter fails, for
 * {@code render_failed}); {@code debugDocState} also returns the document's UI state, title and
 * file name/path. PL_AGA Phase 9 adds {@code debugCircuitTest(text)} (the side-effect-free circuit
 * test of SP_AGA_03_09 with its line counts).
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
            }),
            debugDocState: $entry(function(handle) {
                return @com.lushprojects.circuitjs1.client.agent.AgentJsBridge::debugDocState(Lcom/lushprojects/circuitjs1/client/CirSim;Ljava/lang/String;)(sim, handle == null ? null : String(handle));
            }),
            debugFailNextMutation: $entry(function() {
                @com.lushprojects.circuitjs1.client.agent.Mutation::armForcedFailure()();
            }),
            debugSetIdleSealMs: $entry(function(ms) {
                @com.lushprojects.circuitjs1.client.UndoManager::setIdleSealMs(I)(ms | 0);
            }),
            debugAgentOriginPush: $entry(function(handle) {
                return @com.lushprojects.circuitjs1.client.agent.AgentJsBridge::debugAgentOriginPush(Lcom/lushprojects/circuitjs1/client/CirSim;Ljava/lang/String;)(sim, handle == null ? null : String(handle));
            }),
            debugFailNextUndoLoad: $entry(function() {
                @com.lushprojects.circuitjs1.client.UndoManager::armFailNextLoad()();
            }),
            debugFailNextRunSlice: $entry(function() {
                @com.lushprojects.circuitjs1.client.agent.RunController::armForcedFailure()();
            }),
            // PL_AGA Phase 8 diagnostics (R1/R2 checks and render)
            debugSetSliceProbe: $entry(function(fn) {
                @com.lushprojects.circuitjs1.client.agent.AgentJsBridge::setSliceProbe(Lcom/google/gwt/core/client/JavaScriptObject;)(typeof fn === 'function' ? fn : null);
            }),
            debugSessionState: $entry(function() {
                return sim.@com.lushprojects.circuitjs1.client.CirSim::getSessionStateJson()();
            }),
            debugCanvasPixels: $entry(function() {
                return sim.@com.lushprojects.circuitjs1.client.CirSim::getCanvasPixelsForDebug()();
            }),
            debugClosedTabs: $entry(function() {
                return @com.lushprojects.circuitjs1.client.agent.AgentJsBridge::debugClosedTabs(Lcom/lushprojects/circuitjs1/client/CirSim;)(sim);
            }),
            debugFailNextSvgLoad: $entry(function() {
                @com.lushprojects.circuitjs1.client.CirSim::armCanvas2SvgLoadFailure()();
            }),
            // PL_AGA Phase 9 diagnostic: the circuit-content test of SP_AGA_03_09 on a string
            debugCircuitTest: $entry(function(text) {
                return @com.lushprojects.circuitjs1.client.agent.AgentJsBridge::debugCircuitTest(Ljava/lang/String;)(text == null ? null : String(text));
            })
        };
    }-*/;

    /** Harness diagnostic: observes every run/render slice boundary ({@link Slices}); null removes it. */
    private static void setSliceProbe(final JavaScriptObject fn) {
        Slices.setProbe(fn == null ? null : (op, doc, phase) -> invokeSliceProbe(fn, op, doc, phase));
    }

    private static native void invokeSliceProbe(JavaScriptObject fn, String op, String doc, String phase) /*-{
        fn(op, doc, phase);
    }-*/;

    /**
     * Harness diagnostic: the [SP_AGA_03_09] circuit test of {@code text} as a JSON string
     * ({@code circuit}, {@code kind} and the line counts of {@link CircuitContentTest.Result}).
     */
    private static String debugCircuitTest(String text) {
        return CircuitContentTest.test(text).toJson().toString();
    }

    /** Harness diagnostic: the closed-tab dumps, oldest first, as a JSON array string. */
    private static String debugClosedTabs(CirSim sim) {
        JSONArray a = new JSONArray();
        for (String dump : sim.documentManager.getClosedTabDumps()) {
            a.set(a.size(), new JSONString(dump));
        }
        return a.toString();
    }

    /** Receives the base64 PNG of {@link #encodePng}, or null when the encoder gave no data. */
    interface Base64Callback {
        void onEncoded(String base64);
    }

    /**
     * [SP_AGA_02_08] Encodes a detached canvas as PNG off the event loop ({@code canvas.toBlob},
     * then a {@code FileReader}); a browser without {@code toBlob} encodes synchronously. The
     * callback runs once, on a task of its own, through {@code $entry}.
     */
    static native void encodePng(CanvasElement canvas, Base64Callback callback) /*-{
        var done = $entry(function(text) {
            callback.@com.lushprojects.circuitjs1.client.agent.AgentJsBridge.Base64Callback::onEncoded(Ljava/lang/String;)(text);
        });
        var fromUrl = function(url) {
            var comma = url ? url.indexOf(',') : -1;
            return comma < 0 ? null : url.substring(comma + 1);
        };
        if (typeof canvas.toBlob !== 'function') {
            var url = canvas.toDataURL('image/png');
            setTimeout(function() { done(fromUrl(url)); }, 0);
            return;
        }
        canvas.toBlob(function(blob) {
            if (!blob) { done(null); return; }
            var reader = new FileReader();
            reader.onload = function() { done(fromUrl(reader.result)); };
            reader.onerror = function() { done(null); };
            reader.readAsDataURL(blob);
        }, 'image/png');
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

    /** Harness diagnostic: per-document state the contracts do not expose yet (null handle: active). */
    private static String debugDocState(CirSim sim, String handle) {
        CircuitDocument doc = handle == null ? sim.getActiveDocument() : DocumentHandles.find(sim, handle);
        if (doc == null) {
            return null;
        }
        JSONObject o = new JSONObject();
        o.put("doc", new JSONString(DocumentHandles.of(doc)));
        o.put("undo", new JSONNumber(doc.undoManager.getUndoDepth()));
        o.put("redo", new JSONNumber(doc.undoManager.getRedoDepth()));
        o.put("modified", JSONBoolean.getInstance(doc.circuitInfo.isModified()));
        JSONArray marks = new JSONArray();
        for (String m : doc.getOpenMarks()) {
            marks.set(marks.size(), new JSONString(m));
        }
        o.put("openMarks", marks);
        o.put("gridSize", new JSONNumber(doc.circuitEditor.gridSize));
        o.put("agentOrigin", JSONBoolean.getInstance(doc.isAgentOrigin()));
        o.put("transaction", AgentTransaction.toJson(doc));
        // [SP_AGA_03_08] R2 fields: the document's own UI state (with file name and path) and title
        o.put("ui", doc.getUIStateJson());
        o.put("title", new JSONString(sim.documentManager.getTabTitle(doc)));
        JSONArray logs = new JSONArray();
        for (String line : doc.logBuffer.getLogs()) {
            logs.set(logs.size(), new JSONString(line));
        }
        o.put("logCount", new JSONNumber(logs.size()));
        o.put("logs", logs);
        return o.toString();
    }

    /**
     * Harness diagnostic: an editor undo push requested while the document is marked agent
     * origin (as an editor path reused inside an agent mutation would); it must neither push nor
     * seal ([SP_AGA_04_01]). Returns false for an unknown handle.
     */
    private static boolean debugAgentOriginPush(CirSim sim, String handle) {
        final CircuitDocument doc = handle == null ? sim.getActiveDocument() : DocumentHandles.find(sim, handle);
        if (doc == null) {
            return false;
        }
        DocumentScope.run(sim, doc, () -> {
            doc.setAgentOrigin(true);
            try {
                doc.circuitEditor.pushUndo();
            } finally {
                doc.setAgentOrigin(false);
            }
        });
        return true;
    }

    private static void reportError(String message) {
        GWT.reportUncaughtException(new RuntimeException("Agent: " + message));
    }
}
