package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.json.client.JSONBoolean;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.UndoManager;

/**
 * [SP_AGA_04_01] The agent side of a document's agent transaction ([SP_AGA_01_10]).
 * <p>
 * The state (open flag, pendingEdits, last mutation time, checkpoint counter, idle timer) and
 * the seals live on the document's {@link UndoManager}: every seal trigger but
 * {@code checkpoint} is a client-root user path (user undo push, user undo/redo, user save, user
 * content replacement) or the idle timer, and those must not import this package. This class
 * opens or continues the transaction when an agent mutation succeeds and reports its state in
 * the {@code transaction} field of every mutating contract ([SP_AGA_01_08]).
 * <p>
 * Hooks for later phases: {@code simControl configure} (Phase 7) opens/continues through
 * {@link Mutation}; {@code saveFile} (Phase 9) seals with {@code doc.undoManager.sealTransaction()}
 * before writing.
 */
final class AgentTransaction {

    private AgentTransaction() {
    }

    /**
     * A successful agent mutation of {@code doc} (bound): opens the transaction, pushing
     * {@code preMutation} unless the newest undo entry already holds that state without a
     * comment, or continues it. Refreshes the Undo/Redo items of the bound document.
     */
    static void onMutation(CirSim sim, CircuitDocument doc, UndoManager.UndoItem preMutation) {
        doc.undoManager.noteAgentMutation(preMutation);
        sim.enableUndoRedo();
    }

    /** @return {@code {open, pendingEdits}} of {@code doc}'s transaction ([SP_AGA_01_08] transaction) */
    static JSONObject toJson(CircuitDocument doc) {
        JSONObject o = new JSONObject();
        o.put("open", JSONBoolean.getInstance(doc.undoManager.isTransactionOpen()));
        o.put("pendingEdits", new JSONNumber(doc.undoManager.getPendingEdits()));
        return o;
    }
}
