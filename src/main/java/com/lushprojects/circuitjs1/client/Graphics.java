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

public class Graphics {

    static boolean isFullScreen = false;

    private final Context2d context;
    private int currentFontSize;
    private int savedFontSize;

    public Graphics(Context2d context) {
        this.context = context;
        currentFontSize = 12;
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
        currentFontSize = savedFontSize;
    }

    public void save() {
        context.save();
        savedFontSize = currentFontSize;
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
	        context.ellipse(x, y, rx, ry, ro, sa, ea, ccw);
	    }
	}-*/;


    public void drawString(String s, int x, int y) {
        context.fillText(s, x, y);
    }

    public double measureWidth(String s) {
        return context.measureText(s).getWidth();
    }

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

    public void setFont(Font f) {
        if (f != null) {
            context.setFont(f.fontname);
            currentFontSize = f.size;
        }
    }

    public int getFontSize() {
        return currentFontSize;
    }

    public void setTextBaseline(Context2d.TextBaseline baseline) {
        context.setTextBaseline(baseline);
    }

    public void setTextAlign(Context2d.TextAlign align) {
        context.setTextAlign(align);
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
        context.moveTo(x, y);
    }

    public void fill() {
        context.fill();
    }

    public CanvasGradient createLinearGradient(double x0, double y0, double x1, double y1) {
        return context.createLinearGradient(x0, y0, x1, y1);
    }

    public void setStrokeStyle(FillStrokeStyle strokeStyle) {
        context.setStrokeStyle(strokeStyle);
    }

    public void setStrokeStyle(String strokeStyleColor) {
        context.setStrokeStyle(strokeStyleColor);
    }

    public void strokeRect(double x, double y, double w, double h) {
        context.strokeRect(x, y, w, h);
    }

    public void bezierCurveTo(double cp1x, double cp1y, double cp2x, double cp2y, double x, double y) {
        context.bezierCurveTo(cp1x, cp1y, cp2x, cp2y, x, y);
    }

}
