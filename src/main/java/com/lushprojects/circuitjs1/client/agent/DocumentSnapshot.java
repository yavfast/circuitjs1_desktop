package com.lushprojects.circuitjs1.client.agent;

import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.UndoManager;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.MosfetElm;

import java.util.HashMap;
import java.util.Map;

/**
 * [SP_AGA_03_04] The pre-call state of an agent mutation: what {@code ok = false} must leave
 * unchanged.
 * <ul>
 * <li>{@link #entry}: an undo entry of the document — circuit text (with options, scopes,
 *     adjustables and hint), element IDs, open marks and view transform. Restoring it is an undo
 *     load. It is also the entry the agent transaction pushes as its pre-mutation state
 *     (PL_AGA Phase 6), so a snapshot never needs a second capture.</li>
 * <li>Each element's two defining points by ID: a text reload rewrites some of them (a
 *     horizontal transformer's text constructor synthesizes the diagonal corner), so the restore
 *     puts back every point that the reload changed.</li>
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
    private final Map<String, int[]> endpoints;
    private final Map<String, Integer> idCounters;
    private final boolean modified;
    private final int mosfetGlobalFlags;

    private DocumentSnapshot(CircuitDocument doc) {
        entry = doc.undoManager.captureState();
        endpoints = new HashMap<>();
        for (CircuitElm elm : doc.simulator.elmList) {
            endpoints.put(elm.getElementId(), new int[] { elm.getX(), elm.getY(), elm.getX2(), elm.getY2() });
        }
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
        if (doc.simulator.elmList.size() == endpoints.size()) {
            for (CircuitElm elm : doc.simulator.elmList) {
                int[] p = endpoints.get(elm.getElementId());
                if (p != null && (p[0] != elm.getX() || p[1] != elm.getY() || p[2] != elm.getX2() || p[3] != elm.getY2())) {
                    elm.setEndpoints(p[0], p[1], p[2], p[3]);
                    elm.setPoints();
                }
            }
        }
        doc.restoreIdCounters(idCounters);
        sim.setUnsavedChanges(modified);
    }
}
