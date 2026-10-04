/*    
    Copyright (C) Paul Falstad and Iain Sharp
    
    This file is part of CircuitJS1.

    CircuitJS1 is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 2 of the License, or
    (at your option) any later version.

    CircuitJS1 is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with CircuitJS1.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.lushprojects.circuitjs1.client;

import com.google.gwt.canvas.dom.client.CanvasGradient;
import com.google.gwt.canvas.dom.client.Context2d;
import com.google.gwt.canvas.dom.client.FillStrokeStyle;
import com.google.gwt.dom.client.CanvasElement;

/**
 * Wrapper of a canvas 2-D context. It skips a font, text alignment or baseline that is already in
 * effect (tracked across save/restore); state set on the context behind its back is not seen. In
 * particular, resizing the canvas resets the context state: make a new Graphics after a resize.
 */
public class Graphics implements TextMeasurer {

    static boolean isFullScreen = false;

    private final Context2d context;
    private int currentFontSize;
    /** Canvas font string in effect (set through {@link #setFont}); null when unknown. */
    private String currentFontName;
    /** Alignment and baseline in effect (set through this wrapper); null when unknown. */
    private Context2d.TextAlign measureAlign;
    private Context2d.TextBaseline measureBaseline;

    /**
     * The text state each open {@link #save()} keeps (as the context keeps its own): a stack in
     * plain arrays, {@code saveDepth} entries deep (no allocation per save on the draw path).
     */
    private int saveDepth;
    private int[] savedSizes = new int[16];
    private String[] savedNames = new String[16];
    private Context2d.TextAlign[] savedAligns = new Context2d.TextAlign[16];
    private Context2d.TextBaseline[] savedBaselines = new Context2d.TextBaseline[16];
    /** Saves beyond {@link #MAX_SAVE_DEPTH}: their text state is unknown on restore. */
    private int lostSaves;
    private static final int MAX_SAVE_DEPTH = 1024;

    // Rendering override used for error highlighting (e.g., stopped element).
    // When active, all stroke/fill colors are forced to the provided color.
    private int forcedColorDepth = 0;
    private String forcedColorHex = null;

    // Track last styles set via this wrapper so we can restore them when
    // forced-color highlighting is disabled.
    private boolean lastStrokeIsObject = false;
    private FillStrokeStyle lastStrokeStyleObject = null;
    private String lastStrokeStyleString = "#000";
    private String lastFillStyleString = "#000";

    /**
     * One reusable object of the drawing code kept with these graphics (the element package keeps
     * its painting text layout here, so drawing allocates none per element); opaque to this class.
     */
    private Object paintCache;

    public Object getPaintCache() {
        return paintCache;
    }

    public void setPaintCache(Object o) {
        paintCache = o;
    }

    public Graphics(Context2d context) {
        this.context = context;
        currentFontSize = 12;
    }

    private boolean isColorForced() {
        return forcedColorDepth > 0 && forcedColorHex != null;
    }

    private void applyForcedColor() {
        if (forcedColorHex == null) {
            return;
        }
        context.setStrokeStyle(forcedColorHex);
        context.setFillStyle(forcedColorHex);
    }

    private void restoreTrackedStyles() {
        if (lastStrokeIsObject && lastStrokeStyleObject != null) {
            context.setStrokeStyle(lastStrokeStyleObject);
        } else if (lastStrokeStyleString != null) {
            context.setStrokeStyle(lastStrokeStyleString);
        }
        if (lastFillStyleString != null) {
            context.setFillStyle(lastFillStyleString);
        }
    }

    /**
     * Force all subsequent color/stroke style changes to use the given color.
     * Call {@link #popForcedColor()} to restore prior styles.
     */
    public void pushForcedColor(Color color) {
        if (color == null) {
            return;
        }
        forcedColorDepth++;
        forcedColorHex = color.getHexValue();
        applyForcedColor();
    }

    /**
     * Disable forced-color rendering (paired with {@link #pushForcedColor(Color)}).
     */
    public void popForcedColor() {
        if (forcedColorDepth <= 0) {
            forcedColorDepth = 0;
            forcedColorHex = null;
            return;
        }
        forcedColorDepth--;
        if (forcedColorDepth == 0) {
            forcedColorHex = null;
            restoreTrackedStyles();
        }
    }

    public Context2d getContext() {
        return context;
    }

    public void setColor(Color color) {
        if (color != null) {
            setColor(color.getHexValue());
        }
    }

    public void setColor(String color) {
        if (color == null) {
            return;
        }
        lastStrokeIsObject = false;
        lastStrokeStyleObject = null;
        lastStrokeStyleString = color;
        lastFillStyleString = color;
        if (isColorForced()) {
            applyForcedColor();
            return;
        }
        context.setStrokeStyle(color);
        context.setFillStyle(color);
    }

    public void clipRect(int x, int y, int width, int height) {
        context.beginPath();
        context.rect(x, y, width, height);
        context.clip();
    }

    public void restore() {
        context.restore();
        // [SP_AGA_03_13] nested save/restore pairs restore the text state of their own save
        if (lostSaves > 0) {
            lostSaves--;
            currentFontName = null;
            measureAlign = null;
            measureBaseline = null;
        } else if (saveDepth > 0) {
            saveDepth--;
            currentFontSize = savedSizes[saveDepth];
            currentFontName = savedNames[saveDepth];
            measureAlign = savedAligns[saveDepth];
            measureBaseline = savedBaselines[saveDepth];
        } else {
            currentFontName = null;
            measureAlign = null;
            measureBaseline = null;
        }
    }

    /** @return the number of open {@link #save()} calls (the depth {@link #restoreTo} returns to) */
    public int getSaveDepth() {
        return saveDepth + lostSaves;
    }

    /** Restores until only {@code depth} saves are open (after a draw that threw mid-way). */
    public void restoreTo(int depth) {
        while (getSaveDepth() > depth) {
            restore();
        }
    }

    public void save() {
        context.save();
        if (saveDepth >= MAX_SAVE_DEPTH) {
            lostSaves++; // an unbalanced save somewhere must not grow without bound
            return;
        }
        if (saveDepth == savedSizes.length) {
            int n = saveDepth * 2;
            savedSizes = java.util.Arrays.copyOf(savedSizes, n);
            savedNames = java.util.Arrays.copyOf(savedNames, n);
            savedAligns = java.util.Arrays.copyOf(savedAligns, n);
            savedBaselines = java.util.Arrays.copyOf(savedBaselines, n);
        }
        savedSizes[saveDepth] = currentFontSize;
        savedNames[saveDepth] = currentFontName;
        savedAligns[saveDepth] = measureAlign;
        savedBaselines[saveDepth] = measureBaseline;
        saveDepth++;
    }


    public void fillRect(int x, int y, int width, int height) {
        context.fillRect(x, y, width, height);
    }

    public void drawRect(int x, int y, int width, int height) {
        context.strokeRect(x, y, width, height);
    }

    public void fillOval(int x, int y, int width, int height) {
        context.beginPath();
        context.arc(x + width / 2.0, y + height / 2.0, width / 2.0, 0, 2.0 * 3.14159); // TODO: radius?
        context.closePath();
        context.fill();
    }

    public void drawCircle(int cx, int cy, double radius) {
        context.beginPath();
        context.arc(cx, cy, radius, 0, 2.0 * Math.PI);
        context.stroke();
    }

    public void arc(double x, double y, double radius, double startAngle, double endAngle) {
        context.arc(x, y, radius, startAngle, endAngle);
    }

    public void arc(double x, double y, double radius, double startAngle, double endAngle, boolean anticlockwise) {
        context.arc(x, y, radius, startAngle, endAngle, anticlockwise);
    }

    public final native void ellipse(double x, double y, double rx, double ry, double ro, double sa, double ea, boolean ccw) /*-{
	    if (rx >= 0 && ry >= 0) {
	        var ctx = this.@com.lushprojects.circuitjs1.client.Graphics::context;
	        ctx.ellipse(x, y, rx, ry, ro, sa, ea, ccw);
	    }
	}-*/;


    public void drawString(String s, int x, int y) {
        context.fillText(s, x, y);
        if (textExtent != null) {
            addTextExtent(context, s, x, y, textExtent);
        }
    }

    /** {@link #drawString(String, int, int)} at a fractional anchor ([SP_AGA_03_13] placements). */
    public void drawString(String s, double x, double y) {
        context.fillText(s, x, y);
        if (textExtent != null) {
            addTextExtent(context, s, x, y, textExtent);
        }
    }

    /**
     * {minX, minY, maxX, maxY} in device pixels of the strings drawn since
     * {@link #trackTextExtent()}, or null when not tracking.
     */
    private double[] textExtent;

    /**
     * [SP_AGA_02_08] Starts recording the device-pixel extent of every string drawn through this
     * wrapper (its ink box under the context's current transform, alignment and baseline). The
     * offscreen image measures with it, so labels drawn outside an element's bounding box (value
     * labels) are not cropped.
     */
    public void trackTextExtent() {
        textExtent = new double[] { Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY };
    }

    /** @return {minX, minY, maxX, maxY} in device pixels of the tracked strings, or null when none */
    public double[] getTextExtent() {
        return textExtent == null || textExtent[0] > textExtent[2] ? null : textExtent;
    }

    private static native void addTextExtent(Context2d ctx, String s, double x, double y, double[] ext) /*-{
        var m = ctx.measureText(s);
        var l = x - (m.actualBoundingBoxLeft || 0), r = x + (m.actualBoundingBoxRight || m.width);
        var t = y - (m.actualBoundingBoxAscent || 0), b = y + (m.actualBoundingBoxDescent || 0);
        var tr = ctx.getTransform ? ctx.getTransform() : null;
        var xs = [l, r, r, l], ys = [t, t, b, b];
        for (var i = 0; i < 4; i++) {
            var px = xs[i], py = ys[i];
            if (tr) {
                var qx = tr.a * px + tr.c * py + tr.e;
                py = tr.b * px + tr.d * py + tr.f;
                px = qx;
            }
            if (px < ext[0]) ext[0] = px;
            if (py < ext[1]) ext[1] = py;
            if (px > ext[2]) ext[2] = px;
            if (py > ext[3]) ext[3] = py;
        }
    }-*/;

    public double measureWidth(String s) {
        return context.measureText(s).getWidth();
    }

    /**
     * [SP_AGA_03_13] {@link TextMeasurer}: sets the font only when it differs from the one last
     * set (a measuring instance keeps its font between calls).
     */
    @Override
    public double measureWidth(String s, Font f) {
        applyFont(f);
        return context.measureText(s).getWidth();
    }

    @Override
    public void measureInk(String s, Font f, Context2d.TextAlign align, Context2d.TextBaseline baseline, double[] out) {
        applyFont(f);
        if (align != measureAlign) {
            context.setTextAlign(align);
            measureAlign = align;
        }
        if (baseline != measureBaseline) {
            context.setTextBaseline(baseline);
            measureBaseline = baseline;
        }
        inkMetrics(context, s, out);
    }

    private void applyFont(Font f) {
        if (f != null && !f.fontname.equals(currentFontName)) {
            setFont(f);
        }
    }

    private static native void inkMetrics(Context2d ctx, String s, double[] out) /*-{
        var m = ctx.measureText(s);
        out[0] = m.actualBoundingBoxLeft || 0;
        out[1] = m.actualBoundingBoxRight === undefined ? m.width : m.actualBoundingBoxRight;
        out[2] = m.actualBoundingBoxAscent || 0;
        out[3] = m.actualBoundingBoxDescent || 0;
        out[4] = m.width;
    }-*/;

    public void setLineWidth(double width) {
        context.setLineWidth(width);
    }

    public double getLineWidth() {
        return context.getLineWidth();
    }

    public void setAlpha(double alpha) {
        context.setGlobalAlpha(alpha);
    }

    public void drawLine(int x1, int y1, int x2, int y2) {
        context.beginPath();
        context.moveTo(x1, y1);
        context.lineTo(x2, y2);
        context.stroke();
    }

    public void drawLine(Point x1, Point x2) {
        context.beginPath();
        context.moveTo(x1.x, x1.y);
        context.lineTo(x2.x, x2.y);
        context.stroke();
    }

    public void drawPolyline(int[] xPoints, int[] yPoints, int n) {
        if (n <= 0 || xPoints == null || yPoints == null) return;
        // guard against invalid coordinates
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(xPoints[i]) || Double.isNaN(yPoints[i])) return;
        }
        context.beginPath();
        for (int i = 0; i < n; i++) {
            if (i == 0) {
                context.moveTo(xPoints[i], yPoints[i]);
            } else {
                context.lineTo(xPoints[i], yPoints[i]);
            }
        }
        context.closePath();
        context.stroke();
    }


    public void fillPolygon(Polygon p) {
        if (p == null || p.npoints < 3 || p.xpoints == null || p.ypoints == null) return;
        // Check for any invalid coordinates
        boolean allSame = true;
        int x0 = p.xpoints[0], y0 = p.ypoints[0];
        for (int i = 0; i < p.npoints; i++) {
            int xi = p.xpoints[i], yi = p.ypoints[i];
            if (Double.isNaN(xi) || Double.isNaN(yi)) return;
            if (xi != x0 || yi != y0) allSame = false;
        }
        if (allSame) return;

        context.beginPath();
        for (int i = 0; i < p.npoints; i++) {
            if (i == 0) {
                context.moveTo(p.xpoints[i], p.ypoints[i]);
            } else {
                context.lineTo(p.xpoints[i], p.ypoints[i]);
            }
        }
        context.closePath();
        context.fill();
    }

    public void setLineCap(Context2d.LineCap lineCap) {
        context.setLineCap(lineCap);
    }

    /**
     * Sets the font; a font equal to the one in effect (set through this wrapper since the last
     * {@link #restore()}) is not set again (parsing a canvas font string is not free).
     */
    public void setFont(Font f) {
        if (f != null) {
            if (!f.fontname.equals(currentFontName)) {
                context.setFont(f.fontname);
                currentFontName = f.fontname;
            }
            currentFontSize = f.size;
        }
    }

    public int getFontSize() {
        return currentFontSize;
    }

    /** Sets the text baseline unless it is already in effect (known since the last restore). */
    public void setTextBaseline(Context2d.TextBaseline baseline) {
        if (baseline != measureBaseline) {
            context.setTextBaseline(baseline);
            measureBaseline = baseline;
        }
    }

    /** Sets the text alignment unless it is already in effect (known since the last restore). */
    public void setTextAlign(Context2d.TextAlign align) {
        if (align != measureAlign) {
            context.setTextAlign(align);
            measureAlign = align;
        }
    }

    public void drawLock(int x, int y) {
        context.save();
        setColor(new Color(209, 75, 75));
        context.setLineWidth(3);
        fillRect(x, y, 30, 20);
        context.beginPath();
        context.moveTo(x + 15 - 10, y);
        context.lineTo(x + 15 - 10, y - 4);
        context.arc(x + 15, y - 4, 10, -3.1415, 0);
        context.lineTo(x + 15 + 10, y);
        context.stroke();
        context.restore();
    }

    public static int distanceSq(int x1, int y1, int x2, int y2) {
        x2 -= x1;
        y2 -= y1;
        return x2 * x2 + y2 * y2;
    }

    public void setLineDash(int a, int b) {
        setLineDash(context, a, b);
    }

    native static void setLineDash(Context2d context, int a, int b) /*-{
       if (a == 0)
           context.setLineDash([]);
       else
           context.setLineDash([a, b]);
   }-*/;


    public static void viewFullScreen() {
        requestFullScreen();
        isFullScreen = true;
    }

    private native static void requestFullScreen() /*-{
	   var element = $doc.documentElement;

	   if (element.requestFullscreen) {
	     element.requestFullscreen();
	   } else if (element.mozRequestFullScreen) {
	     element.mozRequestFullScreen();
	   } else if (element.webkitRequestFullscreen) {
	     element.webkitRequestFullscreen();
	   } else if (element.msRequestFullscreen) {
	     element.msRequestFullscreen();
	   }
	 }-*/;

    public static void exitFullScreen() {
        requestExitFullScreen();
        isFullScreen = false;
    }

    private native static void requestExitFullScreen() /*-{
	   var d = $doc;

	   if (d.exitFullscreen) {
	     d.exitFullscreen();
	   } else if (d.mozExitFullScreen) {
	     d.mozExitFullScreen();
	   } else if (d.webkitExitFullscreen) {
	     d.webkitExitFullscreen();
	   } else if (d.msExitFullscreen) {
	     d.msExitFullscreen();
	   }
	 }-*/;

    public void setTransform(double m11, double m12, double m21, double m22, double dx, double dy) {
        context.setTransform(m11, m12, m21, m22, dx, dy);
    }

    public void transform(double m11, double m12, double m21, double m22, double dx, double dy) {
        context.transform(m11, m12, m21, m22, dx, dy);
    }

    public void scale(double x, double y) {
        context.scale(x, y);
    }

    public void translate(double x, double y) {
        context.translate(x, y);
    }

    public void drawImage(CanvasElement image, double dx, double dy) {
        context.drawImage(image, dx, dy);
    }

    public void beginPath() {
        context.beginPath();
    }

    public void closePath() {
        context.closePath();
    }

    public void stroke() {
        context.stroke();
    }

    public void moveTo(double x, double y) {
        context.moveTo(x, y);
    }

    public void lineTo(double x, double y) {
        context.lineTo(x, y);
    }

    public void fill() {
        context.fill();
    }

    public CanvasGradient createLinearGradient(double x0, double y0, double x1, double y1) {
        return context.createLinearGradient(x0, y0, x1, y1);
    }

    public void setStrokeStyle(FillStrokeStyle strokeStyle) {
        lastStrokeIsObject = true;
        lastStrokeStyleObject = strokeStyle;
        if (isColorForced()) {
            applyForcedColor();
            return;
        }
        context.setStrokeStyle(strokeStyle);
    }

    public void setStrokeStyle(String strokeStyleColor) {
        if (strokeStyleColor == null) {
            return;
        }
        lastStrokeIsObject = false;
        lastStrokeStyleObject = null;
        lastStrokeStyleString = strokeStyleColor;
        if (isColorForced()) {
            applyForcedColor();
            return;
        }
        context.setStrokeStyle(strokeStyleColor);
    }

    public void strokeRect(double x, double y, double w, double h) {
        context.strokeRect(x, y, w, h);
    }

    public void bezierCurveTo(double cp1x, double cp1y, double cp2x, double cp2y, double x, double y) {
        context.bezierCurveTo(cp1x, cp1y, cp2x, cp2y, x, y);
    }

}
