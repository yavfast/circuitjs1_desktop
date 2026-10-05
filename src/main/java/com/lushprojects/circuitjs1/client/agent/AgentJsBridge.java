package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.CanvasElement;
import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONBoolean;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONParser;
import com.google.gwt.json.client.JSONString;
import com.google.gwt.json.client.JSONValue;
import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.DocumentScope;
import com.lushprojects.circuitjs1.client.McpServerStatus;
import com.lushprojects.circuitjs1.client.Rectangle;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.io.CircuitContentTest;

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
 * {@code internal_error}, reaches the global handler and still calls back — PL_AGA Phase 7),
 * {@code debugFailNextStamp()} (the next matrix stamp of any document throws outside the
 * per-element guard, to check that a stamp failure in a frame marks the analysis failed —
 * import-scaling fix round).
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
 * <p>
 * PL_MCP Phase 1: {@link #startMcpServer} / {@link #stopMcpServer} start and stop the in-app MCP
 * server ({@code window.CircuitJS1Mcp} of {@code scripts/mcp-server.js}) and route its status
 * into {@link McpServerStatus}; {@code debugMcpStatus()} returns that status as JSON (harness
 * diagnostic).
 * <p>
 * PL_AGA Phase 16a adds {@code debugRenderSliceElements(n)} (an offscreen-render slice break
 * after every n elements, 0 = off, for the explicit-font pixel check of SP_AGA_05_01),
 * {@code debugForceNotCovered(type)} (elements of a JSON type give {@code text_not_covered};
 * null clears), {@code debugSetHighlight(handle, id, "hover" | "select" | null)} (the element
 * hovered or selected in its document, or cleared, then a repaint), and {@code debugDocState}
 * gains the element bounding boxes, current-dot positions and selected/hover flags
 * ({@code elements}) and a digest of the scope graphs ({@code scopeGraphs});
 * {@code debugFailNextOffscreenDraw()} makes the next offscreen element draw throw after leaving a
 * save and a transform on the graphics.
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
            debugFailNextStamp: $entry(function() {
                @com.lushprojects.circuitjs1.client.CircuitSimulator::armFailNextStamp()();
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
            }),
            // PL_AGA Phase 16a diagnostic: a forced offscreen-render slice break after every n elements
            debugRenderSliceElements: $entry(function(n) {
                @com.lushprojects.circuitjs1.client.CircuitRenderer::setForcedSliceElements(I)(n | 0);
            }),
            debugFailNextOffscreenDraw: $entry(function() {
                @com.lushprojects.circuitjs1.client.CircuitRenderer::armOffscreenDrawFailure()();
            }),
            // PL_AGA Phase 16a diagnostics of checkLayout: a JSON type reported as not covered
            // (null clears), and an element hovered/selected in its document (null clears)
            debugForceNotCovered: $entry(function(type) {
                @com.lushprojects.circuitjs1.client.agent.TextOverlap::forceNotCovered(Ljava/lang/String;)(type == null ? null : String(type));
            }),
            debugSetHighlight: $entry(function(handle, id, mode) {
                return @com.lushprojects.circuitjs1.client.agent.AgentJsBridge::debugSetHighlight(Lcom/lushprojects/circuitjs1/client/CirSim;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)(sim,
                    handle == null ? null : String(handle), id == null ? null : String(id), mode == null ? null : String(mode));
            }),
            // PL_MCP Phase 1 diagnostic: the MCP server status kept by the app (McpServerStatus)
            debugMcpStatus: $entry(function() {
                return sim.@com.lushprojects.circuitjs1.client.CirSim::mcpServerStatus.@com.lushprojects.circuitjs1.client.McpServerStatus::toJson()();
            })
        };
    }-*/;

    /**
     * [SP_MCP_02_05] startServer, called by the app's own start-up right after the Agent API
     * export (not through the single-slot loaded hook). Reads and validates the preferences of
     * [SP_MCP_01_01] and hands them to {@code CircuitJS1Mcp.start}, which decides
     * disabled / listening / failed and reports through {@link #onMcpStatus}.
     */
    public static void startMcpServer(CirSim sim) {
        McpServerStatus.Prefs prefs = McpServerStatus.readPrefs();
        if (!prefs.invalidKeys.isEmpty()) {
            sim.logManager.logWarning("MCP server: invalid preference value replaced by its default: "
                    + String.join(", ", prefs.invalidKeys));
        }
        startMcpServerNative(sim, prefs.enabled, prefs.port, prefs.portRange, prefs.host);
    }

    private static native void startMcpServerNative(CirSim sim, boolean enabled, int port, int portRange,
            String host) /*-{
        var mcp = $wnd.CircuitJS1Mcp;
        if (!mcp || typeof mcp.start !== 'function') {
            var desktop = typeof $wnd.require === 'function' && typeof $wnd.process === 'object' && $wnd.process !== null;
            @com.lushprojects.circuitjs1.client.agent.AgentJsBridge::onMcpScriptMissing(Lcom/lushprojects/circuitjs1/client/CirSim;Z)(sim, desktop);
            return;
        }
        var onStatus = $entry(function(status) {
            @com.lushprojects.circuitjs1.client.agent.AgentJsBridge::onMcpStatus(Lcom/lushprojects/circuitjs1/client/CirSim;Ljava/lang/String;)(sim, JSON.stringify(status));
        });
        var started = mcp.start($wnd.CircuitJS1Agent, {enabled: enabled, port: port, portRange: portRange, host: host}, onStatus);
        if (started && typeof started.then === 'function') {
            // an unexpected exception of the server start goes to the global handler (RULE_ERR_004)
            started.then(null, $entry(function(e) {
                @com.lushprojects.circuitjs1.client.agent.AgentJsBridge::reportError(Ljava/lang/String;)('MCP server start: ' + (e && e.stack ? e.stack : e));
            }));
        }
    }-*/;

    /**
     * [SP_MCP_02_05] stopServer: closes the listener and deletes the instance record. The server
     * also stops itself on window {@code unload}; the File → Exit command calls this first.
     */
    public static native void stopMcpServer() /*-{
        var mcp = $wnd.CircuitJS1Mcp;
        if (mcp && typeof mcp.stop === 'function')
            mcp.stop();
    }-*/;

    /** No server script on the page: a browser build is disabled; a desktop build lacks its bundle. */
    private static void onMcpScriptMissing(CirSim sim, boolean desktop) {
        if (desktop) {
            sim.mcpServerStatus.set(McpServerStatus.State.FAILED, "server script not loaded (scripts/mcp-server.js)");
            sim.logManager.logWarning("MCP server failed: " + sim.mcpServerStatus.getReason());
        } else {
            sim.mcpServerStatus.set(McpServerStatus.State.DISABLED, "no desktop runtime");
        }
    }

    /** Status callback of the server: updates {@link McpServerStatus} and logs the transitions. */
    private static void onMcpStatus(CirSim sim, String json) {
        JSONValue v = JSONParser.parseStrict(json);
        JSONObject s = v == null ? null : v.isObject();
        if (s == null) {
            return;
        }
        McpServerStatus status = sim.mcpServerStatus;
        McpServerStatus.State previous = status.update(s);
        McpServerStatus.State state = status.getState();
        if (state == previous) {
            return; // e.g. a tool-call count change
        }
        switch (state) {
        case LISTENING:
            sim.log("MCP server listening on " + String.join(", ", status.getUrls())
                    + " (instance " + status.getInstanceId() + ")");
            JSONArray removed = s.get("removedRecords") == null ? null : s.get("removedRecords").isArray();
            if (removed != null && removed.size() > 0) {
                sim.log("MCP server: removed " + removed.size() + " stale instance record(s)");
            }
            JSONString registryError = s.get("registryError") == null ? null : s.get("registryError").isString();
            if (registryError != null) {
                sim.logManager.logWarning("MCP server: instance record not written: " + registryError.stringValue());
            }
            break;
        case FAILED:
            sim.logManager.logWarning("MCP server failed: " + status.getReason());
            break;
        case DISABLED:
            sim.log("MCP server disabled: " + status.getReason());
            break;
        default:
            break;
        }
    }

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
        // [SP_AGA_05_02] draw-time element state: bounding box and current-dot position
        JSONArray elements = new JSONArray();
        for (CircuitElm elm : doc.simulator.elmList) {
            JSONObject e = new JSONObject();
            e.put("id", new JSONString(elm.getElementId()));
            Rectangle r = elm.getBoundingBox();
            if (r != null) {
                JSONArray bb = new JSONArray();
                bb.set(0, new JSONNumber(r.x));
                bb.set(1, new JSONNumber(r.y));
                bb.set(2, new JSONNumber(r.width));
                bb.set(3, new JSONNumber(r.height));
                e.put("bbox", bb);
            }
            e.put("curcount", new JSONNumber(elm.curcount));
            e.put("selected", JSONBoolean.getInstance(elm.isSelected()));
            e.put("hover", JSONBoolean.getInstance(doc.circuitEditor.getMouseElmRef() == elm));
            elements.set(elements.size(), e);
        }
        o.put("elements", elements);
        o.put("scopeGraphs", new JSONString(doc.scopeManager.debugGraphState()));
        return o.toString();
    }

    /**
     * Harness diagnostic (PL_AGA Phase 16a): sets the element {@code id} of a document hovered
     * ({@code "hover"}: the editor's hover highlight) or selected ({@code "select"}); null clears
     * both; then repaints. Returns false for an unknown handle or element.
     */
    private static boolean debugSetHighlight(CirSim sim, String handle, String id, String mode) {
        CircuitDocument doc = handle == null ? sim.getActiveDocument() : DocumentHandles.find(sim, handle);
        if (doc == null || id == null) {
            return false;
        }
        CircuitElm elm = CircuitView.byId(doc).get(id);
        if (elm == null) {
            return false;
        }
        if ("hover".equals(mode)) {
            elm.setMouseElm(true);
        } else if ("select".equals(mode)) {
            elm.setSelected(true);
        } else {
            elm.setSelected(false);
            elm.setMouseElm(false);
        }
        sim.repaint();
        return true;
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
