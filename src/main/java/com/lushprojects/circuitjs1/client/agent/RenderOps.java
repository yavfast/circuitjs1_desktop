package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.core.client.GWT;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;
import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.CircuitRenderer;
import com.lushprojects.circuitjs1.client.DocumentScope;

/**
 * [SP_AGA_02_08] {@code render}: an image of the whole circuit of one document, drawn offscreen
 * ({@link CircuitRenderer.OffscreenImage}) whatever the viewport or the active tab.
 * <ol>
 * <li><b>Validation</b> (synchronous): {@code format} (svg | png, default png), {@code scale}
 *     (0.25..4, default 1), {@code includeScopes} (default false).</li>
 * <li><b>Vector exporter.</b> An SVG render first loads {@code canvas2svg.js} asynchronously
 *     ({@link CirSim#loadCanvas2Svg}); a load failure completes with {@code render_failed} and
 *     never shows an alert.</li>
 * <li><b>Draw slices</b> ([SP_AGA_03_08] R1). Each slice binds the document through
 *     {@code DocumentScope} (its own options and colours apply; the visible tab's session state
 *     comes back exactly) and draws elements until the slice deadline ({@link Slices}); the next
 *     slice runs after one frame of another free-running visible tab. A small circuit takes one
 *     slice. An image wider or taller than {@link #MAX_SIDE} pixels or above
 *     {@link CircuitRenderer#OFFSCREEN_MAX_AREA} is not drawn: {@code invalid_value} naming
 *     {@code scale}.</li>
 * <li><b>Encode</b>, outside the scope, after one more frame: PNG asynchronously
 *     ({@code canvas.toBlob}, through {@link AgentJsBridge#encodePng}; no data → {@code render_failed},
 *     never a dialog), SVG by serializing the canvas2svg context.</li>
 * </ol>
 * A document closed before the image is complete gives {@code unknown_document}; an exception
 * gives {@code internal_error} and reaches the global handler ([SP_AGA_03_10]). The completion is
 * called exactly once. Served while the document is busy (SP_AGA_02 class table): the draw slices
 * interleave with the run's slices.
 */
final class RenderOps {

    private static final String[] FORMATS = { "svg", "png" };
    static final double MIN_SCALE = 0.25;
    static final double MAX_SCALE = 4;
    /** Largest image side in pixels (browser canvas limits leave room above it). */
    static final int MAX_SIDE = 16384;

    private final CirSim sim;
    private final CircuitDocument doc;
    private final AgentApi.Completion done;
    private final String format;
    private final double scale;
    private final boolean includeScopes;
    private CircuitRenderer.OffscreenImage image;
    private boolean completed;

    private RenderOps(AgentApi.Call call, AgentApi.Completion done, String format, double scale, boolean includeScopes) {
        this.sim = call.sim;
        this.doc = call.doc;
        this.done = done;
        this.format = format;
        this.scale = scale;
        this.includeScopes = includeScopes;
    }

    static void register(AgentApi api) {
        api.registerAsync("render", AgentApi.DocPolicy.OPTIONAL, AgentApi.BusyPolicy.SERVED, RenderOps::start);
    }

    /** The {@code render} contract: validates, then loads the exporter (SVG) and draws. */
    static void start(final AgentApi.Call call, final AgentApi.Completion done) {
        AgentArgs a = call.args;
        String format = a.optEnum("format", FORMATS, "png");
        double scale = a.optNumber("scale", MIN_SCALE, MAX_SCALE, 1);
        boolean includeScopes = a.optBool("includeScopes", false);
        if (a.failed()) {
            done.complete(a.failure());
            return;
        }
        final RenderOps r = new RenderOps(call, done, format, scale, includeScopes);
        if (!r.isSvg()) {
            Slices.afterVisibleFrame(r.sim, r.doc, r::slice);
            return;
        }
        r.sim.loadCanvas2Svg(() -> Slices.afterVisibleFrame(r.sim, r.doc, r::slice),
                () -> r.complete(OperationResult.failure(Issue.of(IssueCode.RENDER_FAILED,
                        "The vector exporter (canvas2svg.js) could not be loaded.",
                        "Retry with format \"png\"."))));
    }

    private boolean isSvg() {
        return "svg".equals(format);
    }

    /** One draw slice; schedules the next one or the encoding. */
    private void slice() {
        if (completed) {
            return;
        }
        if (!isOpen()) {
            return;
        }
        Throwable failure = null;
        boolean finished = false;
        Slices.begin("render", doc);
        final double deadline = Slices.renderDeadline();
        try {
            finished = DocumentScope.call(sim, doc, () -> {
                if (image == null) {
                    image = sim.renderer.startOffscreen(isSvg(), scale, includeScopes, MAX_SIDE);
                }
                return image.step(deadline);
            });
        } catch (Throwable t) {
            failure = t;
        } finally {
            Slices.end("render", doc);
        }
        if (failure != null) {
            fail(failure);
            return;
        }
        if (!finished) {
            Slices.afterVisibleFrame(sim, doc, this::slice);
            return;
        }
        if (image.isTooLarge()) {
            complete(OperationResult.failure(Issue.of(IssueCode.INVALID_VALUE,
                    "Argument 'scale': the image would be " + image.width + " x " + image.height
                            + " px, above the limit of " + MAX_SIDE + " px per side or "
                            + CircuitRenderer.OFFSCREEN_MAX_AREA / 1000000 + " megapixels.",
                    "Use a lower scale.")));
            return;
        }
        if (doc == sim.getActiveDocument()) {
            // the visible tab's next frame recomputes the bounding boxes the image draw measured
            sim.repaint();
        }
        Slices.afterVisibleFrame(sim, doc, this::encode);
    }

    /**
     * The final unit of the render, a slice of its own: SVG serialization, or the start of the
     * asynchronous PNG encoding. A document closed meanwhile gives {@code unknown_document}.
     */
    private void encode() {
        if (completed) {
            return;
        }
        if (!isOpen()) {
            return;
        }
        Slices.begin("render", doc);
        try {
            if (isSvg()) {
                String text = image.getSvgText();
                complete(success(text));
                return;
            }
            AgentJsBridge.encodePng(image.getCanvas().getCanvasElement(), base64 -> {
                if (completed || !isOpen()) {
                    return;
                }
                if (base64 == null) {
                    // the browser could not encode the image: an issue, never a dialog
                    complete(OperationResult.failure(Issue.of(IssueCode.RENDER_FAILED,
                            "The browser could not encode the " + image.width + " x " + image.height + " px PNG image.",
                            "Retry with a lower scale or with format \"svg\".")));
                } else {
                    complete(success(base64));
                }
            });
        } catch (Throwable t) {
            fail(t);
        } finally {
            Slices.end("render", doc);
        }
    }

    /** @return true while the document is open; otherwise completes with {@code unknown_document} */
    private boolean isOpen() {
        if (sim.documentManager.getDocuments().contains(doc)) {
            return true;
        }
        complete(OperationResult.failure(DocumentHandles.unknown(sim, DocumentHandles.of(doc))));
        return false;
    }

    private OperationResult success(String content) {
        JSONObject data = new JSONObject();
        data.put("format", new JSONString(format));
        data.put("width", new JSONNumber(image.width));
        data.put("height", new JSONNumber(image.height));
        data.put("content", new JSONString(content));
        return OperationResult.success(data);
    }

    private void fail(Throwable t) {
        OperationResult r = OperationResult.failure(Issue.of(IssueCode.INTERNAL_ERROR,
                "Internal error during the render: " + t.getMessage(), "Report the error; retry the render."));
        try {
            // shown and logged as before (RULE_ERR_004)
            GWT.reportUncaughtException(t);
        } finally {
            complete(r);
        }
    }

    /** Invokes the completion exactly once. */
    private void complete(OperationResult r) {
        if (completed) {
            return;
        }
        completed = true;
        done.complete(r);
    }
}
