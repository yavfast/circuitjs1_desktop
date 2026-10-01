package com.lushprojects.circuitjs1.client;

import com.lushprojects.circuitjs1.client.dialog.SlidersDialog;

import java.util.function.Supplier;

/**
 * [SP_AGA_03_08] The one entry through which an operation touches a target document that may not
 * be the visible tab. Mechanism per PL_AGA_DEC_01: a scoped silent bind.
 * <ol>
 * <li>Save the bound (visible) document's UI state from the session widgets, and the renderer's
 *     circuit area (centring the target recomputes it from the target's scope count).</li>
 * <li>Swap the bound document by field ({@code DocumentManager.swapActiveSilently}) — never
 *     {@code bindDocument}, which restarts the visible tab's loop timer.</li>
 * <li>Detach the session sliders dialog, so the target's slider rebuilds cannot touch it.</li>
 * <li>Apply the target's options, its view transform (or centre it when it has none) and hint.</li>
 * <li>Run the operation.</li>
 * <li>Save the target's UI state and hint, swap back, re-apply the bound document's options,
 *     transform, hint and circuit area, re-attach the sliders dialog and refresh the derived
 *     session widgets (time-step bar, power bar, Undo/Redo, edit items, Save item, window title).
 *     The swap back and the re-attach always run, also when an earlier step throws.</li>
 * </ol>
 * When the target already is the bound document, the operation runs directly. Nested scopes
 * work: each level saves and restores what was bound when it was entered.
 * <p>
 * It lives at the client root so user paths (closed-tab dump, session save) use it without
 * importing the agent package; the agent contracts call it from {@code client/agent/}.
 * Phase 8 of PL_AGA completes the session-coupled path list and adds rendering.
 */
public final class DocumentScope {

    private DocumentScope() {
    }

    /**
     * Runs {@code op} with {@code target} bound and returns its value. The visible tab, its
     * simulation loop, sliders and session widgets are left unchanged (R1); the target's own
     * state is updated as if it were active (R2). An exception from {@code op} propagates after
     * the bind is undone.
     *
     * @param target an open document
     */
    public static <T> T call(CirSim sim, CircuitDocument target, Supplier<T> op) {
        CircuitDocument bound = sim.getActiveDocument();
        if (target == bound) {
            return op.get();
        }
        if (!sim.documentManager.getDocuments().contains(target)) {
            throw new IllegalArgumentException("document is not open");
        }
        MenuManager mm = sim.menuManager;
        CircuitRenderer renderer = sim.renderer;
        SlidersDialog sliders = sim.slidersDialog;
        boolean wasDetached = sliders != null && sliders.isDetached();
        boolean saveAllowed = sim.isSaveAllowed();
        // setCircuitArea() replaces the rectangle, so keeping the reference restores it exactly.
        Rectangle circuitArea = renderer.circuitArea;

        bound.saveUIState(mm, sim);
        sim.documentManager.swapActiveSilently(target);
        try {
            if (sliders != null) {
                sliders.setDetached(true);
            }
            target.applyOptionWidgets(mm, sim);
            target.applyViewState(sim, true);
            return op.get();
        } finally {
            try {
                target.saveUIState(mm, sim);
            } finally {
                sim.documentManager.swapActiveSilently(bound);
                try {
                    renderer.circuitArea = circuitArea;
                    bound.applyOptionWidgets(mm, sim);
                    bound.applyViewState(sim, false);
                    sim.refreshSessionWidgets(saveAllowed);
                } finally {
                    if (sliders != null) {
                        sliders.setDetached(wasDetached);
                    }
                }
            }
        }
    }

    /** Runs {@code op} with {@code target} bound; see {@link #call}. */
    public static void run(CirSim sim, CircuitDocument target, Runnable op) {
        call(sim, target, () -> {
            op.run();
            return null;
        });
    }
}
