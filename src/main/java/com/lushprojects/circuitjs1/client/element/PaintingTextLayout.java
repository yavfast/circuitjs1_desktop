package com.lushprojects.circuitjs1.client.element;

import com.google.gwt.canvas.dom.client.Context2d;
import com.lushprojects.circuitjs1.client.Font;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.TextMeasurer;


/**
 * [SP_AGA_03_13] The painting layout: collects an element's placements and paints them, one
 * {@code group} at a time, at the points of its draw order where {@code draw()} asks for them.
 * Painting a placement sets its font (and its colour where it has one) on the graphics, which
 * keep them afterwards as the old helpers left them; it sets the alignment and baseline for each
 * string and puts the canvas defaults back after the group (the graphics skip a value already in
 * effect); it widens the owner's bounding box by the
 * placement's widening rectangle (the old helpers' draw-time effect) and draws the over-bar.
 * Widths for the placement arithmetic come from the graphics it paints with (the same canvas font
 * metrics as the session measurer of the measuring layout, so painting and checking compute the
 * same anchors); the graphics skip a font already in effect.
 */
public final class PaintingTextLayout implements TextLayout {

    private CircuitElm owner;
    private final Graphics g;
    /** The placements in layout order ({@code count} of them; null while there is none). */
    private TextPlacement[] placements;
    private int count;

    private PaintingTextLayout(CircuitElm owner, Graphics g) {
        this.owner = owner;
        this.g = g;
    }

    /**
     * An empty painting layout for {@code owner} over {@code g}: the one kept with the graphics,
     * reset (drawing allocates no layout per element). It stays valid until the next element asks
     * for one on the same graphics, so an element paints its groups before it draws another
     * element (a subcircuit draws its chip, which asks for its own).
     */
    static PaintingTextLayout of(CircuitElm owner, Graphics g) {
        handedOut++;
        Object c = g.getPaintCache();
        PaintingTextLayout t;
        if (c instanceof PaintingTextLayout) {
            t = (PaintingTextLayout) c;
            for (int i = 0; i < t.count; i++) {
                t.placements[i] = null;
            }
            t.count = 0;
            t.owner = owner;
        } else {
            t = new PaintingTextLayout(owner, g);
            g.setPaintCache(t);
        }
        t.generation = handedOut;
        return t;
    }

    /** Layouts handed out by {@link #of} so far (any graphics; drawing is single-threaded). */
    private static int handedOut;
    /** The {@link #handedOut} count when this layout was handed to its present owner. */
    private int generation;

    /**
     * Fails loudly when another element took this layout after its owner got it: the owner would
     * paint the other element's placements.
     */
    private void checkOwner() {
        if (generation != handedOut) {
            throw new IllegalStateException("The painting text layout of " + owner.getClass().getSimpleName()
                    + " was taken by another element before its texts were painted");
        }
    }

    @Override
    public void add(TextPlacement p) {
        if (placements == null) {
            placements = new TextPlacement[4];
        } else if (count == placements.length) {
            placements = java.util.Arrays.copyOf(placements, count * 2);
        }
        placements[count++] = p;
    }

    @Override
    public TextMeasurer measurer() {
        return g;
    }

    @Override
    public double measureWidth(String s, Font f) {
        return g.measureWidth(s, f);
    }

    /** Paints the placements of one group, in layout order. */
    public void paint(int group) {
        checkOwner();
        boolean painted = false;
        for (int i = 0; i < count; i++) {
            TextPlacement p = placements[i];
            if (p.group == group) {
                paintOne(p);
                painted = true;
            }
        }
        if (painted) {
            restoreDefaults();
        }
    }

    /** Paints every placement, in layout order (the paint wrappers of the shared helpers). */
    public void paintAll() {
        checkOwner();
        for (int i = 0; i < count; i++) {
            paintOne(placements[i]);
        }
        if (count > 0) {
            restoreDefaults();
        }
    }

    /** The canvas defaults, which the other drawing assumes (skipped when already in effect). */
    private void restoreDefaults() {
        g.setTextAlign(Context2d.TextAlign.START);
        g.setTextBaseline(Context2d.TextBaseline.ALPHABETIC);
    }

    private void paintOne(TextPlacement p) {
        g.setFont(p.font);
        if (p.color != null) {
            g.setColor(p.color);
        }
        // left is painted as the canvas default "start" (the same for left-to-right text, and the
        // same SVG text-anchor); the wrapper skips what is already in effect
        g.setTextAlign(p.align == TextPlacement.Align.LEFT ? Context2d.TextAlign.START : p.align.canvas);
        g.setTextBaseline(p.baseline.canvas);
        g.drawString(p.text, p.x, p.y);
        if (p.widen) {
            owner.adjustBbox(p.widenX1, p.widenY1, p.widenX2, p.widenY2);
        }
        if (p.overBar) {
            g.drawLine((int) p.overBarX1, (int) p.overBarY, (int) p.overBarX2, (int) p.overBarY);
        }
    }
}
