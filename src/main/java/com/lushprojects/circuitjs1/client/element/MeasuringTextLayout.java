package com.lushprojects.circuitjs1.client.element;

import com.lushprojects.circuitjs1.client.TextMeasurer;

import java.util.ArrayList;
import java.util.List;

/**
 * [SP_AGA_03_13] The measuring layout of {@code checkLayout}: records each placement of the
 * element being laid out ({@link #setOwner}) with its ink box from the canvas metrics, and draws
 * nothing. Transient placements (made only for a highlighted element) are dropped; it has no
 * draw-time effect (no bounding box is widened).
 */
public final class MeasuringTextLayout implements TextLayout {

    /** One measured placement: owner, placement and ink box {x1, y1, x2, y2} in circuit pixels. */
    public static final class Measured {
        public final CircuitElm owner;
        public final TextPlacement placement;
        public final double x1, y1, x2, y2;

        Measured(CircuitElm owner, TextPlacement placement, double x1, double y1, double x2, double y2) {
            this.owner = owner;
            this.placement = placement;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
        }
    }

    private final TextMeasurer measurer;
    private final List<Measured> measured = new ArrayList<>();
    private final double[] ink = new double[5];
    private CircuitElm owner;

    public MeasuringTextLayout(TextMeasurer measurer) {
        this.measurer = measurer;
    }

    /** Sets the element whose {@code layoutTexts} adds the next placements. */
    public void setOwner(CircuitElm owner) {
        this.owner = owner;
    }

    @Override
    public void add(TextPlacement p) {
        if (p.transientText) {
            return;
        }
        measurer.measureInk(p.text, p.font, p.align.canvas, p.baseline.canvas, ink);
        measured.add(new Measured(owner, p, p.x - ink[0], p.y - ink[2], p.x + ink[1], p.y + ink[3]));
    }

    @Override
    public TextMeasurer measurer() {
        return measurer;
    }

    /** @return the measured placements in layout order (element order, then layout order) */
    public List<Measured> getMeasured() {
        return measured;
    }
}
