package com.lushprojects.circuitjs1.client.element;

import com.google.gwt.canvas.dom.client.Context2d;
import com.lushprojects.circuitjs1.client.Color;
import com.lushprojects.circuitjs1.client.Font;

/**
 * [SP_AGA_03_13] One string an element draws: what {@link CircuitElm#layoutTexts} places and
 * both layouts use: {@link PaintingTextLayout} paints it, {@link MeasuringTextLayout} measures
 * it. A placement names its font (no text inherits the graphics state), its anchor in circuit
 * pixels, its alignment and baseline, and has no rotation.
 * <ul>
 * <li><b>group</b>: the point of the element's draw order where {@code draw()} paints it.</li>
 * <li><b>live</b>: the text shows a simulated quantity (a meter reading); the rules skip it.</li>
 * <li><b>transient</b>: placed only for a highlighted element (pin letters); never checked or
 *     listed.</li>
 * <li><b>colour</b>: set only where the element paints the text in a colour of its own (else the
 *     colour the element has set before the group is painted).</li>
 * <li><b>over-bar</b>: a line above the text (an inverted signal name); not part of the box.</li>
 * <li><b>bounding-box widening</b>: the draw-time effect of the old helpers (the painting layout
 *     widens the element's bounding box by it; the measuring layout has none).</li>
 * </ul>
 */
public final class TextPlacement {

    /** Horizontal alignment at the anchor. */
    public enum Align {
        LEFT("left", Context2d.TextAlign.LEFT),
        CENTER("center", Context2d.TextAlign.CENTER),
        RIGHT("right", Context2d.TextAlign.RIGHT);

        public final String json;
        final Context2d.TextAlign canvas;

        Align(String json, Context2d.TextAlign canvas) {
            this.json = json;
            this.canvas = canvas;
        }

        public Context2d.TextAlign canvas() {
            return canvas;
        }
    }

    /** Vertical alignment at the anchor. */
    public enum Baseline {
        ALPHABETIC("alphabetic", Context2d.TextBaseline.ALPHABETIC),
        MIDDLE("middle", Context2d.TextBaseline.MIDDLE),
        TOP("top", Context2d.TextBaseline.TOP),
        BOTTOM("bottom", Context2d.TextBaseline.BOTTOM);

        public final String json;
        final Context2d.TextBaseline canvas;

        Baseline(String json, Context2d.TextBaseline canvas) {
            this.json = json;
            this.canvas = canvas;
        }

        public Context2d.TextBaseline canvas() {
            return canvas;
        }
    }

    public final String text;
    public final Font font;
    public final double x, y;
    Align align = Align.LEFT;
    Baseline baseline = Baseline.ALPHABETIC;
    int group;
    boolean live;
    boolean transientText;
    Color color;
    boolean overBar;
    double overBarX1, overBarX2, overBarY;
    boolean widen;
    int widenX1, widenY1, widenX2, widenY2;

    /**
     * @param text the string
     * @param font its font (required)
     * @param x    anchor x in circuit pixels
     * @param y    anchor y in circuit pixels
     */
    public TextPlacement(String text, Font font, double x, double y) {
        if (font == null) {
            throw new IllegalArgumentException("A text placement names its font");
        }
        this.text = text == null ? "" : text;
        this.font = font;
        this.x = x;
        this.y = y;
    }

    public TextPlacement align(Align a) {
        align = a;
        return this;
    }

    public TextPlacement baseline(Baseline b) {
        baseline = b;
        return this;
    }

    public TextPlacement group(int g) {
        group = g;
        return this;
    }

    public TextPlacement live(boolean l) {
        live = l;
        return this;
    }

    /** Marks the placement transient: made only for a highlighted element, never checked. */
    public TextPlacement transientText(boolean t) {
        transientText = t;
        return this;
    }

    public TextPlacement color(Color c) {
        color = c;
        return this;
    }

    /** A line from (x1, y) to (x2, y) drawn after the text in the current colour. */
    public TextPlacement overBar(double x1, double x2, double y) {
        overBar = true;
        overBarX1 = x1;
        overBarX2 = x2;
        overBarY = y;
        return this;
    }

    /** The rectangle by which painting widens the owner's bounding box (draw-time only). */
    public TextPlacement widenBbox(int x1, int y1, int x2, int y2) {
        widen = true;
        widenX1 = x1;
        widenY1 = y1;
        widenX2 = x2;
        widenY2 = y2;
        return this;
    }

    public Align getAlign() {
        return align;
    }

    public Baseline getBaseline() {
        return baseline;
    }

    public int getGroup() {
        return group;
    }

    public boolean isLive() {
        return live;
    }

    public boolean isTransient() {
        return transientText;
    }

    public boolean hasOverBar() {
        return overBar;
    }

    /** @return true when the string has a visible character (not empty, not only whitespace) */
    public boolean hasInk() {
        return !text.trim().isEmpty();
    }
}
