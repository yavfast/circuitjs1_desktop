package com.lushprojects.circuitjs1.client.agent;

import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.UndoManager;
import com.lushprojects.circuitjs1.client.element.MosfetElm;

import java.util.Map;

/**
 * [SP_AGA_03_04] The pre-call state of an agent mutation: what {@code ok = false} must leave
 * unchanged.
 * <ul>
 * <li>{@link #entry}: an undo entry of the document — circuit text (with options, scopes,
 *     adjustables and hint), element IDs, open marks, view transform and element endpoints (a
 *     text reload rewrites some endpoints, which the undo load puts back). Restoring it is an
 *     undo load. It is also the entry the agent transaction pushes as its pre-mutation state
 *     ({@link AgentTransaction}), so a snapshot never needs a second capture.</li>
 * <li>The ID counters (an import resets them; a restore must not leave them lower than before),
 *     the modified flag and the session-wide MOSFET display flags (a text load of a MOSFET
 *     rewrites them).</li>
 * </ul>
 * The session model catalogue entries a text import changes are restored by the import's
 * {@code ImportReport} before the snapshot ({@link Mutation}).
 * <p>
 * Capture and restore run while the document is bound ({@code DocumentScope}), so the options
 * line and the view transform are the document's own.
 */
final class DocumentSnapshot {

    /** The undo entry of the pre-call state. */
    final UndoManager.UndoItem entry;
    private final Map<String, Integer> idCounters;
    private final boolean modified;
    private final int mosfetGlobalFlags;

    private DocumentSnapshot(CircuitDocument doc) {
        entry = doc.undoManager.captureState();
        idCounters = doc.captureIdCounters();
        modified = doc.circuitInfo.isModified();
        mosfetGlobalFlags = MosfetElm.getGlobalFlags();
    }

    /** Captures the state of {@code doc}, which must be bound. */
    static DocumentSnapshot capture(CircuitDocument doc) {
        return new DocumentSnapshot(doc);
    }

    /** Puts {@code doc} (bound) back into the captured state; the undo/redo stacks are untouched. */
    void restore(CirSim sim, CircuitDocument doc) {
        MosfetElm.setGlobalFlags(mosfetGlobalFlags);
        doc.undoManager.restoreState(entry);
        MosfetElm.setGlobalFlags(mosfetGlobalFlags);
        doc.restoreIdCounters(idCounters);
        sim.setUnsavedChanges(modified);
    }
}
