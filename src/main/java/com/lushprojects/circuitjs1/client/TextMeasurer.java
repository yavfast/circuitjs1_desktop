package com.lushprojects.circuitjs1.client;

import com.google.gwt.canvas.dom.client.Context2d;

/**
 * [SP_AGA_03_13] Text measurement with the canvas metrics: the advance width of a string and its
 * ink extents around an anchor ({@code measureText}, {@code actualBoundingBox*}). Implemented by
 * {@link Graphics}: over the context it paints into, and over the session's 1 x 1 measuring
 * canvas ({@link CircuitRenderer#getTextMeasurer()}) for layouts that draw nothing.
 */
public interface TextMeasurer {

    /** @return the advance width of {@code s} in font {@code f}, in pixels */
    double measureWidth(String s, Font f);

    /**
     * Ink extents of {@code s} drawn in font {@code f} at an anchor with the given alignment and
     * baseline: {@code out[0]} left, {@code out[1]} right, {@code out[2]} ascent and
     * {@code out[3]} descent, distances in pixels from the anchor (the canvas
     * {@code actualBoundingBoxLeft/Right/Ascent/Descent}), and {@code out[4]} the advance width.
     */
    void measureInk(String s, Font f, Context2d.TextAlign align, Context2d.TextBaseline baseline, double[] out);
}
