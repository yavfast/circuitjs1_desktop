package com.lushprojects.circuitjs1.client.element;

import com.google.gwt.i18n.client.NumberFormat;
import com.lushprojects.circuitjs1.client.DisplaySettings;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.Polygon;
import com.lushprojects.circuitjs1.client.util.Locale;

public class BaseCircuitElm {

    public static final double PI = Math.PI;
    public static final double PI_2 = Math.PI * 2.0;

    static final int CURRENT_TOO_FAST = 100;
    static final double THICK_LINE_WIDTH = 2.0;

    static final int SCALE_AUTO = 0;
    static final int SCALE_1 = 1;
    static final int SCALE_M = 2;
    static final int SCALE_MU = 3;

    /**
     * Default constructor for BaseCircuitElm.
     * Creates a new instance of the base circuit element utility class.
     */
    BaseCircuitElm() {

    }

    /**
     * Formats a number using short decimal digit settings without fixed decimal places.
     *
     * @param value the number to format
     * @return formatted number string using short format
     */
    public static String shortFormat(double value) {
        return formatNumber(value, DisplaySettings.getShortDecimalDigits(), false);
    }

    /**
     * Formats a number using standard decimal digit settings without fixed decimal places.
     *
     * @param value the number to format
     * @return formatted number string using standard format
     */
    public static String showFormat(double value) {
        return formatNumber(value, DisplaySettings.getDecimalDigits(), false);
    }

    /**
     * Formats a number using standard decimal digit settings with fixed decimal places.
     *
     * @param value the number to format
     * @return formatted number string with fixed decimal places
     */
    public static String fixedFormat(double value) {
        return formatNumber(value, DisplaySettings.getDecimalDigits(), true);
    }

    /**
     * Formats a number using either fixed or short format based on the parameter.
     *
     * @param value the number to format
     * @param fixed true for fixed format, false for short format
     * @return formatted number string
     */
    public static String numFormat(double value, boolean fixed) {
        return fixed ? fixedFormat(value) : shortFormat(value);
    }

    /**
     * Formats a number with specified decimal places and optional fixed decimal setting.
     * This is the main formatting function that handles the actual number formatting logic.
     *
     * @param value the number to format
     * @param decimalPlaces number of decimal places to show (minimum 0)
     * @param fixedDecimal true to always show all decimal places, false to omit trailing zeros
     * @return formatted number string
     */
    public static String formatNumber(double value, int decimalPlaces, boolean fixedDecimal) {
        if (decimalPlaces < 0) {
            decimalPlaces = 0;
        }

        // Calculate multiplier for rounding (10^decimalPlaces)
        long multiplier = 1;
        for (int i = 0; i < decimalPlaces; i++) {
            multiplier *= 10;
        }

        long rounded = Math.round(value * multiplier);
        long integerPart = rounded / multiplier;
        long decimalPart = Math.abs(rounded % multiplier);

        // Return only integer part if no decimal places are needed or if value is whole number (for non-fixed)
        if (decimalPlaces == 0 || (!fixedDecimal && decimalPart == 0)) {
            return String.valueOf(integerPart);
        }

        String decimalStr = String.valueOf(decimalPart);

        // Pad with leading zeros to match decimal places (e.g., for 0.01)
        while (decimalStr.length() < decimalPlaces) {
            decimalStr = "0" + decimalStr;
        }

        // For non-fixed format, remove trailing zeros
        if (!fixedDecimal) {
            while (decimalStr.length() > 1 && decimalStr.endsWith("0")) {
                decimalStr = decimalStr.substring(0, decimalStr.length() - 1);
            }
        }

        return integerPart + "." + decimalStr;
    }

    /**
     * Formats a number with specified decimal places and optional fixed decimal setting.
     *
     * @param value the number to format
     * @param decimalPlaces number of decimal places to show (minimum 0)
     * @return formatted number string
     */
    public static String formatNumber(double value, int decimalPlaces) {
        return formatNumber(value, decimalPlaces, true);
    }

    /**
     * Formats a number with 2 decimal places (backward compatibility).
     *
     * @param value the number to format
     * @return formatted number string with 2 decimal places
     */
    public static String formatNumber(double value) {
        return formatNumber(value, 2);
    }

    /**
     * Compares two pairs of integers to see if they match in any order.
     *
     * @param x1 first element of first pair
     * @param x2 second element of first pair
     * @param y1 first element of second pair
     * @param y2 second element of second pair
     * @return true if pairs match in any order (x1,x2) == (y1,y2) or (x1,x2) == (y2,y1)
     */
    public static boolean comparePair(int x1, int x2, int y1, int y2) {
        return ((x1 == y1 && x2 == y2) || (x1 == y2 && x2 == y1));
    }

    /**
     * Returns the absolute value of an integer.
     *
     * @param x the integer value
     * @return the absolute value of x
     */
    public static int abs(int x) {
        return x < 0 ? -x : x;
    }

    /**
     * Returns the sign of an integer.
     *
     * @param x the integer value
     * @return -1 if x < 0, 0 if x == 0, 1 if x > 0
     */
    public static int sign(int x) {
        return (x < 0) ? -1 : (x == 0) ? 0 : 1;
    }

    /**
     * Returns the minimum of two integers.
     *
     * @param a first integer
     * @param b second integer
     * @return the smaller of a and b
     */
    public static int min(int a, int b) {
        return (a < b) ? a : b;
    }

    /**
     * Returns the maximum of two integers.
     *
     * @param a first integer
     * @param b second integer
     * @return the larger of a and b
     */
    public static int max(int a, int b) {
        return (a > b) ? a : b;
    }

    /**
     * Calculates the Euclidean distance between two points.
     *
     * @param p1 first point
     * @param p2 second point
     * @return the distance between the two points
     */
    public static double distance(Point p1, Point p2) {
        double x = p1.x - p2.x;
        double y = p1.y - p2.y;
        return Math.sqrt(x * x + y * y);
    }

    /**
     * Calculates the squared perpendicular distance from a point to a line segment.
     * Uses the formula for point-to-line distance.
     *
     * @param xa x-coordinate of line start point
     * @param ya y-coordinate of line start point
     * @param xb x-coordinate of line end point
     * @param yb y-coordinate of line end point
     * @param gx x-coordinate of the point
     * @param gy y-coordinate of the point
     * @return squared distance from point (gx,gy) to line segment (xa,ya)-(xb,yb)
     */
    public static int lineDistanceSq(int xa, int ya, int xb, int yb, int gx, int gy) {
        int dtop = (yb - ya) * gx - (xb - xa) * gy + xb * ya - yb * xa;
        int dbot = (yb - ya) * (yb - ya) + (xb - xa) * (xb - xa);
        return dtop * dtop / dbot;
    }

    /**
     * Creates a new array of Point objects, all initialized to new Point instances.
     *
     * @param n the size of the array to create
     * @return array of n initialized Point objects
     */
    public static Point[] newPointArray(int n) {
        Point[] a = new Point[n];
        while (n > 0) {
            a[--n] = new Point();
        }
        return a;
    }

    /**
     * Calculates a point at fraction f between two points using linear interpolation.
     *
     * @param a starting point
     * @param b ending point
     * @param f fraction between points (0.0 = point a, 1.0 = point b)
     * @return new Point at the interpolated position
     */
    public static Point interpPoint(Point a, Point b, double f) {
        Point p = new Point();
        interpPoint(a, b, p, f);
        return p;
    }

    /**
     * Calculates a point at fraction f between two points using linear interpolation.
     * Result is stored in the provided point c.
     *
     * @param a starting point
     * @param b ending point
     * @param c output point to store the result
     * @param f fraction between points (0.0 = point a, 1.0 = point b)
     */
    public static void interpPoint(Point a, Point b, Point c, double f) {
        c.x = (int) Math.floor(a.x * (1 - f) + b.x * f + .48);
        c.y = (int) Math.floor(a.y * (1 - f) + b.y * f + .48);
    }

    /**
     * Returns a point fraction f along the line between a and b and offset perpendicular by g
     *
     * @param a 1st Point
     * @param b 2nd Point
     * @param c 1st Point
     * @param f Fraction along line
     * @param g Fraction perpendicular to line
     *          Returns interpolated point in c
     */
    public static void interpPoint(Point a, Point b, Point c, double f, double g) {
        int gx = b.y - a.y;
        int gy = a.x - b.x;
        g /= Math.sqrt(gx * gx + gy * gy);
        c.x = (int) Math.floor(a.x * (1 - f) + b.x * f + g * gx + .48);
        c.y = (int) Math.floor(a.y * (1 - f) + b.y * f + g * gy + .48);
    }

    /**
     * Returns a point fraction f along the line between a and b and offset perpendicular by g
     *
     * @param a 1st Point
     * @param b 2nd Point
     * @param f Fraction along line
     * @param g Fraction perpendicular to line
     * @return Interpolated point
     */
    public static Point interpPoint(Point a, Point b, double f, double g) {
        Point p = new Point();
        interpPoint(a, b, p, f, g);
        return p;
    }


    /**
     * Calculates two points fraction f along the line between a and b and offest perpendicular by +/-g
     *
     * @param a 1st point (In)
     * @param b 2nd point (In)
     * @param c 1st point (Out)
     * @param d 2nd point (Out)
     * @param f Fraction along line
     * @param g Fraction perpendicular to line
     */
    public static void interpPoint2(Point a, Point b, Point c, Point d, double f, double g) {
        int gx = b.y - a.y;
        int gy = a.x - b.x;
        g /= Math.sqrt(gx * gx + gy * gy);
        c.x = (int) Math.floor(a.x * (1 - f) + b.x * f + g * gx + .48);
        c.y = (int) Math.floor(a.y * (1 - f) + b.y * f + g * gy + .48);
        d.x = (int) Math.floor(a.x * (1 - f) + b.x * f - g * gx + .48);
        d.y = (int) Math.floor(a.y * (1 - f) + b.y * f - g * gy + .48);
    }

    /**
     * Adds current values with overflow protection.
     * If current is already at maximum/minimum threshold, returns the threshold value.
     *
     * @param c current value
     * @param a value to add
     * @return sum of c and a, or threshold value if c is at limits
     */
    public static double addCurCount(double c, double a) {
        if (c == CURRENT_TOO_FAST || c == -CURRENT_TOO_FAST)
            return c;
        return c + a;
    }

    /**
     * Creates an arrow polygon pointing from point a to point b.
     *
     * @param a starting point of arrow
     * @param b ending point of arrow (arrow head location)
     * @param al arrow length
     * @param aw arrow width
     * @return Polygon representing the arrow shape
     */
    public static Polygon calcArrow(Point a, Point b, double al, double aw) {
        Polygon poly = new Polygon();
        Point p1 = new Point();
        Point p2 = new Point();
        int adx = b.x - a.x;
        int ady = b.y - a.y;
        double l = Math.sqrt(adx * adx + ady * ady);
        poly.addPoint(b.x, b.y);
        interpPoint2(a, b, p1, p2, 1 - al / l, aw);
        poly.addPoint(p1.x, p1.y);
        poly.addPoint(p2.x, p2.y);
        return poly;
    }

    /**
     * Creates a triangle polygon from three points.
     *
     * @param a first vertex
     * @param b second vertex
     * @param c third vertex
     * @return Polygon with three vertices
     */
    public static Polygon createPolygon(Point a, Point b, Point c) {
        Polygon p = new Polygon();
        p.addPoint(a.x, a.y);
        p.addPoint(b.x, b.y);
        p.addPoint(c.x, c.y);
        return p;
    }

    /**
     * Creates a quadrilateral polygon from four points.
     *
     * @param a first vertex
     * @param b second vertex
     * @param c third vertex
     * @param d fourth vertex
     * @return Polygon with four vertices
     */
    public static Polygon createPolygon(Point a, Point b, Point c, Point d) {
        Polygon p = new Polygon();
        p.addPoint(a.x, a.y);
        p.addPoint(b.x, b.y);
        p.addPoint(c.x, c.y);
        p.addPoint(d.x, d.y);
        return p;
    }

    /**
     * Creates a polygon from an array of points.
     *
     * @param a array of points to form the polygon vertices
     * @return Polygon with vertices from the point array
     */
    public static Polygon createPolygon(Point[] a) {
        Polygon p = new Polygon();
        for (Point point : a) {
            p.addPoint(point.x, point.y);
        }
        return p;
    }

    /**
     * Draws a thick line between two coordinate points.
     *
     * @param g Graphics context
     * @param x starting x-coordinate
     * @param y starting y-coordinate
     * @param x2 ending x-coordinate
     * @param y2 ending y-coordinate
     */
    public static void drawThickLine(Graphics g, int x, int y, int x2, int y2) {
        g.setLineWidth(THICK_LINE_WIDTH);
        g.drawLine(x, y, x2, y2);
        g.setLineWidth(1.0);
    }

    /**
     * Draws a thick line between two Point objects.
     *
     * @param g Graphics context
     * @param pa starting point
     * @param pb ending point
     */
    public static void drawThickLine(Graphics g, Point pa, Point pb) {
        g.setLineWidth(THICK_LINE_WIDTH);
        g.drawLine(pa.x, pa.y, pb.x, pb.y);
        g.setLineWidth(1.0);
    }

    /**
     * Draws a thick polyline using coordinate arrays.
     *
     * @param g Graphics context
     * @param xs array of x-coordinates
     * @param ys array of y-coordinates
     * @param c number of points
     */
    public static void drawThickPolygon(Graphics g, int[] xs, int[] ys, int c) {
        g.setLineWidth(THICK_LINE_WIDTH);
        g.drawPolyline(xs, ys, c);
        g.setLineWidth(1.0);
    }

    /**
     * Draws a thick polyline from a Polygon object.
     *
     * @param g Graphics context
     * @param p Polygon to draw
     */
    public static void drawThickPolygon(Graphics g, Polygon p) {
        drawThickPolygon(g, p.xpoints, p.ypoints, p.npoints);
    }

    /**
     * Draws a polyline from a Polygon object with normal line width.
     *
     * @param g Graphics context
     * @param p Polygon to draw
     */
    public static void drawPolygon(Graphics g, Polygon p) {
        g.drawPolyline(p.xpoints, p.ypoints, p.npoints);
    }

    /**
     * Draws a thick circle with specified center and radius.
     *
     * @param g Graphics context
     * @param cx center x-coordinate
     * @param cy center y-coordinate
     * @param ri radius
     */
    public static void drawThickCircle(Graphics g, int cx, int cy, int ri) {
        g.setLineWidth(THICK_LINE_WIDTH);
        g.drawCircle(cx, cy, ri * 0.98);
        g.setLineWidth(1.0);
    }

    /**
     * Formats voltage value with unit and returns absolute value as text.
     *
     * @param v voltage value
     * @return formatted voltage string with appropriate unit prefix (e.g., "5.2 mV")
     */
    public static String getVoltageDText(double v) {
        return getUnitText(Math.abs(v), "V");
    }

    /**
     * Formats voltage value with unit as text.
     *
     * @param v voltage value
     * @return formatted voltage string with appropriate unit prefix (e.g., "5.2 mV")
     */
    public static String getVoltageText(double v) {
        return getUnitText(v, "V");
    }

    /**
     * Formats time value with appropriate units (seconds, minutes:seconds, hours:minutes:seconds).
     *
     * @param v time value in seconds
     * @return formatted time string
     */
    public static String getTimeText(double v) {
        if (v >= 60) {
            double h = Math.floor(v / 3600);
            v -= 3600 * h;
            double m = Math.floor(v / 60);
            v -= 60 * m;
            if (h == 0)
                return m + ":" + ((v >= 10) ? "" : "0") + showFormat(v);
            return h + ":" + ((m >= 10) ? "" : "0") + m + ":" + ((v >= 10) ? "" : "0") + showFormat(v);
        }
        return getUnitText(v, "s");
    }

    /**
     * Formats a numeric value using either short or normal format.
     *
     * @param v value to format
     * @param sf true for short format, false for normal format
     * @return formatted number string
     */
    public static String format(double v, boolean sf) {
        return sf ? shortFormat(v) : showFormat(v);
    }

    /**
     * Formats a value with unit using normal format.
     *
     * @param v numeric value
     * @param u unit string
     * @return formatted string with appropriate unit prefix
     */
    public static String getUnitText(double v, String u) {
        return getUnitText(v, u, false);
    }

    /**
     * Formats a value with unit using short format.
     *
     * @param v numeric value
     * @param u unit string
     * @return formatted string with appropriate unit prefix (short format)
     */
    public static String getShortUnitText(double v, String u) {
        return getUnitText(v, u, true);
    }

    /**
     * Calculates the SI unit magnitude (power of 10) for a given value.
     * Returns the nearest multiple of 3 that represents the appropriate SI prefix.
     *
     * @param v numeric value to analyze
     * @return magnitude as power of 10 (multiple of 3)
     */
    private static int getMagnitude(double v) {
        double va = Math.abs(v);

        // Calculate the order of magnitude (power of 10)
        double log10 = Math.log10(va);
        return (int) Math.floor(log10 / 3) * 3; // Round down to nearest multiple of 3
    }

    /**
     * Internal method to format values with units and appropriate SI prefixes.
     * Automatically selects appropriate prefix (p, n, μ, m, k, M, G) based on magnitude.
     *
     * @param v numeric value
     * @param u unit string
     * @param sf true for short format (no spaces), false for normal format
     * @return formatted string with appropriate unit prefix
     */
    private static String getUnitText(double v, String u, boolean sf) {
        String sp = sf ? "" : " ";
        double va = Math.abs(v);

        // Handle zero and very small values
        if (va < 1e-14) {
            return "0" + sp + u;
        }

        // Handle very large values with scientific notation
        if (va > 1e14) {
            return NumberFormat.getFormat("#.##E000").format(v) + sp + u;
        }

        // Get magnitude using dedicated function
        int magnitude = getMagnitude(v);

        // Calculate multiplier: 10^(-magnitude)
        double multiplier = Math.pow(10, -magnitude);

        // Get prefix using mathematical calculation
        String prefix = getSIPrefix(magnitude);

        double scaledValue = v * multiplier;
        return format(scaledValue, sf) + sp + prefix + u;
    }

    /**
     * Returns SI prefix string based on power of 10 magnitude.
     * Uses mathematical calculation instead of array lookup.
     *
     * @param magnitude power of 10 (must be multiple of 3 in range [-12, 9])
     * @return SI prefix string
     */
    public static String getSIPrefix(int magnitude) {
        switch (magnitude) {
            case -12: return "p";  // pico
            case -9:  return "n";  // nano
            case -6:  return Locale.muString; // micro
            case -3:  return "m";  // milli
            case 0:   return "";   // base unit
            case 3:   return "k";  // kilo
            case 6:   return "M";  // mega
            case 9:   return "G";  // giga
            case 12:  return "T";  // tera
            default:  return "";   // fallback to base unit
        }
    }

    /**
     * Formats current value with unit as text.
     *
     * @param i current value in amperes
     * @return formatted current string with appropriate unit prefix (e.g., "2.5 mA")
     */
    public static String getCurrentText(double i) {
        return getUnitText(i, "A");
    }

    /**
     * Formats current value with unit and returns absolute value as text.
     *
     * @param i current value in amperes
     * @return formatted current string with appropriate unit prefix (e.g., "2.5 mA")
     */
    public static String getCurrentDText(double i) {
        return getUnitText(Math.abs(i), "A");
    }

    /**
     * Formats a value with unit using a specific scale.
     *
     * @param val numeric value
     * @param utext unit string
     * @param scale scale constant (SCALE_AUTO, SCALE_1, SCALE_M, SCALE_MU)
     * @return formatted string with specified scale
     */
    public static String getUnitTextWithScale(double val, String utext, int scale) {
        return getUnitTextWithScale(val, utext, scale, false);
    }

    /**
     * Formats a value with unit using a specific scale and formatting option.
     *
     * @param val numeric value
     * @param utext unit string
     * @param scale scale constant (SCALE_AUTO, SCALE_1, SCALE_M, SCALE_MU)
     * @param fixed true to use fixed-point formatting, false for normal formatting
     * @return formatted string with specified scale and format
     */
    public static String getUnitTextWithScale(double val, String utext, int scale, boolean fixed) {
        if (Math.abs(val) > 1e12)
            return getUnitText(val, utext);
        if (scale == SCALE_1) {
            return numFormat(val, fixed) + " " + utext;
        }
        if (scale == SCALE_M) {
            return numFormat(1e3 * val, fixed) + " m" + utext;
        }
        if (scale == SCALE_MU) {
            return numFormat(1e6 * val, fixed) + " " + Locale.muString + utext;
        }
        return getUnitText(val, utext);
    }



}
