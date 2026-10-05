package com.lushprojects.circuitjs1.client.agent;

import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.util.EchoText;

import java.util.List;

/**
 * [SP_AGA_01_02] DocumentHandle {@code d<n>}: {@code n} is the document's session-unique number,
 * assigned by {@code BaseCirSim} when the document is created (every document, including the
 * start-up one and user-created tabs) and never reused.
 */
public final class DocumentHandles {

    static final String PREFIX = "d";

    private DocumentHandles() {
    }

    /** @return the handle of an open document */
    public static String of(CircuitDocument doc) {
        return PREFIX + doc.getDocumentNumber();
    }

    /** @return the open document with this handle, or null (also for a malformed handle) */
    public static CircuitDocument find(CirSim sim, String handle) {
        if (handle == null || !handle.matches("^d[1-9][0-9]*$")) {
            return null;
        }
        for (CircuitDocument doc : sim.documentManager.getDocuments()) {
            if (handle.equals(of(doc))) {
                return doc;
            }
        }
        return null;
    }

    /** @return the handles of all open documents in tab order, comma-separated */
    public static String listOpen(CirSim sim) {
        List<CircuitDocument> docs = sim.documentManager.getDocuments();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(of(docs.get(i)));
        }
        return sb.toString();
    }

    /** @return the unknown_document issue for a handle, with the open handles as hint */
    static Issue unknown(CirSim sim, String handle) {
        return Issue.of(IssueCode.UNKNOWN_DOCUMENT,
                "No open document has the handle '" + EchoText.clip(handle) + "'.",
                "Use one of the open documents: " + listOpen(sim) + ".");
    }
}
