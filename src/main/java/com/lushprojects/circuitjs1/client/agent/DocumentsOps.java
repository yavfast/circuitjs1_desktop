package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONBoolean;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;
import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.DocumentManager;

import java.util.List;

/**
 * [SP_AGA_02_02] Documents: listDocuments, createDocument, activateDocument, closeDocument, all
 * through {@link DocumentManager}. Only activateDocument, createDocument(activate: true) and
 * closing the active document change the visible tab; a background document is closed without
 * a tab switch (its closed-tab dump runs inside the client-root {@code DocumentScope}).
 */
final class DocumentsOps {

    /** Upper bound for a createDocument title. */
    static final int MAX_TITLE_LENGTH = 200;

    private DocumentsOps() {
    }

    static void register(AgentApi api) {
        api.register("listDocuments", AgentApi.DocPolicy.NONE, AgentApi.BusyPolicy.SERVED, DocumentsOps::listDocuments);
        api.register("createDocument", AgentApi.DocPolicy.NONE, AgentApi.BusyPolicy.SERVED, DocumentsOps::createDocument);
        api.register("activateDocument", AgentApi.DocPolicy.REQUIRED, AgentApi.BusyPolicy.SERVED, DocumentsOps::activateDocument);
        api.register("closeDocument", AgentApi.DocPolicy.REQUIRED, AgentApi.BusyPolicy.HANDLER, DocumentsOps::closeDocument);
    }

    static OperationResult listDocuments(AgentApi.Call call) {
        DocumentManager dm = call.sim.documentManager;
        CircuitDocument active = dm.getActiveDocument();
        List<CircuitDocument> docs = dm.getDocuments();
        JSONArray list = new JSONArray();
        for (int i = 0; i < docs.size(); i++) {
            CircuitDocument doc = docs.get(i);
            JSONObject o = new JSONObject();
            o.put("doc", new JSONString(DocumentHandles.of(doc)));
            o.put("title", new JSONString(dm.getDocumentTitle(doc)));
            o.put("active", JSONBoolean.getInstance(doc == active));
            o.put("modified", JSONBoolean.getInstance(doc.circuitInfo.isModified()));
            o.put("running", JSONBoolean.getInstance(doc.isRunning()));
            o.put("busy", JSONBoolean.getInstance(doc.isAgentBusy()));
            o.put("elementCount", new JSONNumber(doc.simulator.elmList.size()));
            String path = doc.circuitInfo.getFilePath();
            if (path != null) {
                o.put("filePath", new JSONString(path));
            }
            list.set(i, o);
        }
        JSONObject data = new JSONObject();
        data.put("documents", list);
        return OperationResult.success(data);
    }

    static OperationResult createDocument(AgentApi.Call call) {
        String title = call.args.optString("title", null);
        boolean activate = call.args.optBool("activate", false);
        if (title != null && (title.trim().isEmpty() || title.length() > MAX_TITLE_LENGTH)) {
            call.args.invalid("title", "must have 1 to " + MAX_TITLE_LENGTH + " characters",
                    "Pass a short tab title, or omit title.");
        }
        if (call.args.failed()) {
            return call.args.failure();
        }
        DocumentManager dm = call.sim.documentManager;
        // A new document is blank like a user "New Tab"; creating it touches no session UI
        // except adding its (inactive) tab.
        CircuitDocument doc = dm.createDocument();
        if (title != null) {
            doc.setDisplayTitle(title);
            dm.notifyTitleChanged(doc);
        }
        if (activate) {
            dm.setActiveDocument(doc);
        }
        return OperationResult.success(handleData(doc));
    }

    static OperationResult activateDocument(AgentApi.Call call) {
        call.sim.documentManager.setActiveDocument(call.doc);
        return OperationResult.success(handleData(call.doc));
    }

    static OperationResult closeDocument(AgentApi.Call call) {
        boolean discard = call.args.optBool("discardChanges", false);
        if (call.args.failed()) {
            return call.args.failure();
        }
        CircuitDocument doc = call.doc;
        if (doc.isAgentBusy() && !discard) {
            return OperationResult.failure(Issue.of(IssueCode.BUSY,
                    "Document " + DocumentHandles.of(doc) + " is busy with an agent run.",
                    "Wait for the run to finish, or pass discardChanges: true to cancel it."));
        }
        if (doc.circuitInfo.isModified() && !discard) {
            return OperationResult.failure(Issue.of(IssueCode.UNSAVED_CHANGES,
                    "Document " + DocumentHandles.of(doc) + " has unsaved changes.",
                    "Save it first, or pass discardChanges: true."));
        }
        // A busy document closed with discardChanges ends its run as cancelled (PL_AGA Phase 7
        // adds the run; until then no document is busy).
        DocumentManager dm = call.sim.documentManager;
        boolean last = dm.getDocuments().size() == 1;
        String handle = DocumentHandles.of(doc);
        dm.closeDocument(doc);

        JSONObject data = new JSONObject();
        data.put("doc", new JSONString(handle));
        if (last) {
            // The existing tab rule replaces the last document with a blank one.
            data.put("replacement", new JSONString(DocumentHandles.of(dm.getActiveDocument())));
        }
        return OperationResult.success(data);
    }

    private static JSONObject handleData(CircuitDocument doc) {
        JSONObject data = new JSONObject();
        data.put("doc", new JSONString(DocumentHandles.of(doc)));
        return data;
    }
}
