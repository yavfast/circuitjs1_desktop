package com.lushprojects.circuitjs1.client.element;

import com.lushprojects.circuitjs1.client.Font;
import com.lushprojects.circuitjs1.client.TextMeasurer;

/**
 * [SP_AGA_03_13] Sink of the placements of {@link CircuitElm#layoutTexts}. Two implementations:
 * {@link PaintingTextLayout} (what {@code draw()} paints) and {@link MeasuringTextLayout} (what
 * {@code checkLayout} measures). A layout method may measure widths for its arithmetic (a
 * left-aligned value computed from its width) through {@link #measureWidth}; it writes no field.
 */
public interface TextLayout {

    /** Adds one placement, in layout order. */
    void add(TextPlacement p);

    /** @return the measurer of this layout (the session's measuring canvas) */
    TextMeasurer measurer();

    /** @return the advance width of {@code s} in font {@code f}, in pixels */
    default double measureWidth(String s, Font f) {
        return measurer().measureWidth(s, f);
    }
}
