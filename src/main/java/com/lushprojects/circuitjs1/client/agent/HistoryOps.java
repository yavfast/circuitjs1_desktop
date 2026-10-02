package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.core.client.GWT;
import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONBoolean;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;
import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.DocumentScope;
import com.lushprojects.circuitjs1.client.UndoManager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * [SP_AGA_02_12] checkpoint and [SP_AGA_02_13] getHistory / undo / redo / restoreCheckpoint over
 * the target document's undo history ({@link UndoManager}).
 * <ul>
 * <li>checkpoint seals the open transaction with the agent's comment, or drops its entry when it
 *     equals the current state (circuit text, element IDs and open marks): {@code noChanges}.</li>
 * <li>undo and restoreCheckpoint seal an open transaction automatically first; nothing is undone
 *     when fewer entries exist than requested ({@code nothing_to_undo}, {@code nothing_to_redo})
 *     or the checkpoint is not in the undo stack ({@code unknown_checkpoint}).</li>
 * <li>A restore applies the entry's circuit text, element IDs (counters raised), open marks, view
 *     transform and element endpoints; an ID count mismatch is returned as {@code ids_regenerated}.
 *     Each successful undo, redo and restore sets the document's modified flag ([SP_AGA_02]).</li>
 * </ul>
 * Every operation runs inside {@code DocumentScope} (a background document is restored without
 * switching tabs) with the editor grid pinned to the document's own option.
 */
final class HistoryOps {

    /** [SP_AGA_02_12] Maximum checkpoint comment length. */
    static final int MAX_COMMENT = 120;
    static final int DEFAULT_HISTORY_LIMIT = 20;
    static final int MAX_HISTORY_LIMIT = 150;
    static final int MAX_STEPS = 50;

    private HistoryOps() {
    }

    static void register(AgentApi api) {
        api.register("checkpoint", AgentApi.DocPolicy.OPTIONAL, AgentApi.BusyPolicy.REJECTED, HistoryOps::checkpoint);
        api.register("getHistory", AgentApi.DocPolicy.OPTIONAL, AgentApi.BusyPolicy.SERVED, HistoryOps::getHistory);
        api.register("undo", AgentApi.DocPolicy.OPTIONAL, AgentApi.BusyPolicy.REJECTED, HistoryOps::undo);
        api.register("redo", AgentApi.DocPolicy.OPTIONAL, AgentApi.BusyPolicy.REJECTED, HistoryOps::redo);
        api.register("restoreCheckpoint", AgentApi.DocPolicy.OPTIONAL, AgentApi.BusyPolicy.REJECTED, HistoryOps::restoreCheckpoint);
    }

    // ---------------------------------------------------------------- checkpoint

    static OperationResult checkpoint(AgentApi.Call call) {
        String comment = call.args.requireString("comment");
        if (comment != null) {
            if (comment.isEmpty()) {
                call.args.invalid("comment", "is empty", "Pass a comment of 1 to " + MAX_COMMENT + " characters.");
            } else if (comment.length() > MAX_COMMENT) {
                call.args.invalid("comment", "is longer than " + MAX_COMMENT + " characters", "Shorten the comment.");
            } else if (comment.indexOf('\n') >= 0 || comment.indexOf('\r') >= 0) {
                call.args.invalid("comment", "spans several lines", "Pass a single-line comment.");
            }
        }
        if (call.args.failed()) {
            return call.args.failure();
        }
        final CircuitDocument doc = call.doc;
        final CirSim sim = call.sim;
        return DocumentScope.call(sim, doc, () -> doc.undoManager.splitGesture(() -> {
            UndoManager um = doc.undoManager;
            JSONObject data = new JSONObject();
            if (!um.isTransactionOpen()) {
                data.put("noChanges", JSONBoolean.getInstance(true));
                return OperationResult.success(data);
            }
            UndoManager.UndoItem current = um.captureState();
            if (um.getUndoDepth() > 0 && um.getUndoEntry(0).sameContent(current)) {
                um.dropTransactionEntry();
                sim.enableUndoRedo();
                data.put("noChanges", JSONBoolean.getInstance(true));
                return OperationResult.success(data);
            }
            String id = um.seal(comment, false);
            sim.enableUndoRedo();
            if (id != null) {
                data.put("checkpointId", new JSONString(id));
            }
            data.put("noChanges", JSONBoolean.getInstance(id == null));
            return OperationResult.success(data);
        }));
    }

    // ---------------------------------------------------------------- getHistory

    static OperationResult getHistory(AgentApi.Call call) {
        int limit = call.args.optInt("limit", 1, MAX_HISTORY_LIMIT, DEFAULT_HISTORY_LIMIT);
        if (call.args.failed()) {
            return call.args.failure();
        }
        UndoManager um = call.doc.undoManager;
        boolean open = um.isTransactionOpen();
        JSONArray undo = new JSONArray();
        for (int p = 0; p < Math.min(limit, um.getUndoDepth()); p++) {
            // the open transaction's entry is the newest undo entry; it has no checkpoint yet
            undo.set(p, record(um.getUndoEntry(p), p, open && p == 0));
        }
        JSONArray redo = new JSONArray();
        for (int p = 0; p < Math.min(limit, um.getRedoDepth()); p++) {
            redo.set(p, record(um.getRedoEntry(p), p, false));
        }
        JSONObject data = new JSONObject();
        data.put("undo", undo);
        data.put("redo", redo);
        data.put("openTransaction", JSONBoolean.getInstance(open));
        return OperationResult.success(data);
    }

    /** [SP_AGA_01_10] CheckpointRecord of one entry. */
    private static JSONObject record(UndoManager.UndoItem e, int position, boolean openTransactionEntry) {
        JSONObject o = new JSONObject();
        if (e.getCheckpointId() != null) {
            o.put("checkpointId", new JSONString(e.getCheckpointId()));
        }
        if (e.getComment() != null) {
            o.put("comment", new JSONString(e.getComment()));
        }
        o.put("auto", JSONBoolean.getInstance(e.isAuto()));
        boolean agent = e.getCheckpointId() != null || openTransactionEntry;
        o.put("kind", new JSONString(agent ? "agent" : "user"));
        o.put("position", new JSONNumber(position));
        return o;
    }

    // ---------------------------------------------------------------- undo / redo / restore

    static OperationResult undo(AgentApi.Call call) {
        final int steps = call.args.optInt("steps", 1, MAX_STEPS, 1);
        if (call.args.failed()) {
            return call.args.failure();
        }
        final CircuitDocument doc = call.doc;
        return splittingGesture(call.sim, doc, () -> {
            if (doc.undoManager.getUndoDepth() < steps) {
                return OperationResult.failure(Issue.of(IssueCode.NOTHING_TO_UNDO,
                        "Document " + DocumentHandles.of(doc) + " has " + doc.undoManager.getUndoDepth()
                                + " undo entries; " + steps + " requested. Nothing was undone.",
                        "Call getHistory to see the available entries."));
            }
            return restore(call.sim, doc, steps, true, "undone");
        });
    }

    static OperationResult redo(AgentApi.Call call) {
        final int steps = call.args.optInt("steps", 1, MAX_STEPS, 1);
        if (call.args.failed()) {
            return call.args.failure();
        }
        final CircuitDocument doc = call.doc;
        return splittingGesture(call.sim, doc, () -> {
            if (doc.undoManager.getRedoDepth() < steps) {
                return OperationResult.failure(Issue.of(IssueCode.NOTHING_TO_REDO,
                        "Document " + DocumentHandles.of(doc) + " has " + doc.undoManager.getRedoDepth()
                                + " redo entries; " + steps + " requested. Nothing was redone.",
                        "Call getHistory to see the available entries."));
            }
            return restore(call.sim, doc, steps, false, "redone");
        });
    }

    static OperationResult restoreCheckpoint(AgentApi.Call call) {
        String id = call.args.requireString("checkpointId");
        if (call.args.failed()) {
            return call.args.failure();
        }
        final CircuitDocument doc = call.doc;
        return splittingGesture(call.sim, doc, () -> {
            int position = doc.undoManager.findCheckpoint(id);
            if (position < 0) {
                List<String> known = new ArrayList<>();
                for (int p = 0; p < doc.undoManager.getUndoDepth(); p++) {
                    String cp = doc.undoManager.getUndoEntry(p).getCheckpointId();
                    if (cp != null) {
                        known.add(cp);
                    }
                }
                return OperationResult.failure(Issue.of(IssueCode.UNKNOWN_CHECKPOINT,
                        "Checkpoint '" + id + "' is not in the undo history of " + DocumentHandles.of(doc) + ".",
                        known.isEmpty() ? "The undo history holds no checkpoint; call getHistory."
                                : "Use one of: " + String.join(", ", known) + "."));
            }
            // the auto-seal names the open transaction's entry (position 0) and moves no entry
            return restore(call.sim, doc, position + 1, true, "undone");
        });
    }

    /**
     * Runs a history operation with a user gesture of the document split around it
     * ({@link UndoManager#splitGesture}): the gesture's entries before and after the operation
     * stay the user's. Binds the document only when the user holds the mouse button in it.
     */
    private static OperationResult splittingGesture(CirSim sim, CircuitDocument doc, Supplier<OperationResult> op) {
        if (!doc.undoManager.isGestureActive()) {
            return op.get();
        }
        return DocumentScope.call(sim, doc, () -> doc.undoManager.splitGesture(op));
    }

    /**
     * Seals an open transaction (undo only), then pops {@code steps} entries and restores the
     * state of the last one.
     */
    private static OperationResult restore(final CirSim sim, final CircuitDocument doc, final int steps,
            final boolean isUndo, final String countKey) {
        return DocumentScope.call(sim, doc, () -> CellGeometry.withPinnedGrid(doc, () -> {
            UndoManager um = doc.undoManager;
            if (isUndo) {
                um.sealTransaction();
            }
            String warning;
            try {
                warning = isUndo ? um.undo(steps) : um.redo(steps);
            } catch (Throwable t) {
                // the undo manager put both stacks and the document back (RULE_ERR_004)
                GWT.reportUncaughtException(t);
                sim.enableUndoRedo();
                return OperationResult.failure(Issue.of(IssueCode.INTERNAL_ERROR,
                        "Internal error; the document and its history were restored: " + t.getMessage(),
                        "Report the error; the call can be retried."));
            }
            sim.needAnalyze();
            sim.setUnsavedChanges(true);
            sim.enableUndoRedo();
            JSONObject data = new JSONObject();
            data.put(countKey, new JSONNumber(steps));
            OperationResult result = OperationResult.success(data);
            if (warning != null) {
                result.addIssue(Issue.of(IssueCode.IDS_REGENERATED, warning,
                        "Element IDs of this document changed; call getCircuit for the new IDs."));
            }
            return result;
        }));
    }
}
