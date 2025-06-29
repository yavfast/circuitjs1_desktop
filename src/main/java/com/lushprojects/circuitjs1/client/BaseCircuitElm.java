package com.lushprojects.circuitjs1.client;

import com.google.gwt.i18n.client.NumberFormat;
import com.google.gwt.storage.client.Storage;
import com.lushprojects.circuitjs1.client.util.Locale;

public class BaseCircuitElm {

    static final int CURRENT_TOO_FAST = 100;
    static final double THICK_LINE_WIDTH = 2.0;
    static final double pi = Math.PI;

    static final int SCALE_AUTO = 0;
    static final int SCALE_1 = 1;
    static final int SCALE_M = 2;
    static final int SCALE_MU = 3;

    static NumberFormat showFormat, shortFormat, fixedFormat;
    static int decimalDigits, shortDecimalDigits;

    /**
     * Default constructor for BaseCircuitElm.
     * Creates a new instance of the base circuit element utility class.
     */
    BaseCircuitElm() {

    }

    /**
     * Sets the number of decimal digits for number formatting.
     *
     * @param num the number of decimal digits to display
     * @param sf true for short format, false for normal format
     * @param save true to save the setting to local storage
     */
    static void setDecimalDigits(int num, boolean sf, boolean save) {
        if (sf)
            shortDecimalDigits = num;
        else
            decimalDigits = num;

        String s = "####.";
        int ct = num;
        for (; ct > 0; ct--)
            s += '#';
        NumberFormat nf = NumberFormat.getFormat(s);
        if (sf)
            shortFormat = nf;
        else
            showFormat = nf;

        if (save) {
            Storage stor = Storage.getLocalStorageIfSupported();
            if (stor != null)
                stor.setItem(sf ? "decimalDigitsShort" : "decimalDigits", Integer.toString(num));
        }

        if (!sf) {
            s = "####.";
            ct = num;
            for (; ct > 0; ct--)
                s += '0';
            fixedFormat = NumberFormat.getFormat(s);
        }
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
    static boolean comparePair(int x1, int x2, int y1, int y2) {
        return ((x1 == y1 && x2 == y2) || (x1 == y2 && x2 == y1));
    }

    /**
     * Returns the absolute value of an integer.
     *
     * @param x the integer value
     * @return the absolute value of x
     */
    static int abs(int x) {
        return x < 0 ? -x : x;
    }

    /**
     * Returns the sign of an integer.
     *
     * @param x the integer value
     * @return -1 if x < 0, 0 if x == 0, 1 if x > 0
     */
    static int sign(int x) {
        return (x < 0) ? -1 : (x == 0) ? 0 : 1;
    }

    /**
     * Returns the minimum of two integers.
     *
     * @param a first integer
     * @param b second integer
     * @return the smaller of a and b
     */
    static int min(int a, int b) {
        return (a < b) ? a : b;
    }

    /**
     * Returns the maximum of two integers.
     *
     * @param a first integer
     * @param b second integer
     * @return the larger of a and b
     */
    static int max(int a, int b) {
        return (a > b) ? a : b;
    }

    /**
     * Calculates the Euclidean distance between two points.
     *
     * @param p1 first point
     * @param p2 second point
     * @return the distance between the two points
     */
    static double distance(Point p1, Point p2) {
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
    static int lineDistanceSq(int xa, int ya, int xb, int yb, int gx, int gy) {
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
    static Point[] newPointArray(int n) {
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
    static Point interpPoint(Point a, Point b, double f) {
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
    static void interpPoint(Point a, Point b, Point c, double f) {
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
    static void interpPoint(Point a, Point b, Point c, double f, double g) {
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
    static Point interpPoint(Point a, Point b, double f, double g) {
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
    static void interpPoint2(Point a, Point b, Point c, Point d, double f, double g) {
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
    static double addCurCount(double c, double a) {
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
    static Polygon calcArrow(Point a, Point b, double al, double aw) {
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
    static Polygon createPolygon(Point a, Point b, Point c) {
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
    static Polygon createPolygon(Point a, Point b, Point c, Point d) {
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
    static Polygon createPolygon(Point[] a) {
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
    static void drawThickLine(Graphics g, int x, int y, int x2, int y2) {
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
    static void drawThickLine(Graphics g, Point pa, Point pb) {
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
    static void drawThickPolygon(Graphics g, int[] xs, int[] ys, int c) {
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
    static void drawThickPolygon(Graphics g, Polygon p) {
        drawThickPolygon(g, p.xpoints, p.ypoints, p.npoints);
    }

    /**
     * Draws a polyline from a Polygon object with normal line width.
     *
     * @param g Graphics context
     * @param p Polygon to draw
     */
    static void drawPolygon(Graphics g, Polygon p) {
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
    static void drawThickCircle(Graphics g, int cx, int cy, int ri) {
        g.setLineWidth(THICK_LINE_WIDTH);
        g.context.beginPath();
        g.context.arc(cx, cy, ri * .98, 0, 2 * Math.PI);
        g.context.stroke();
        g.setLineWidth(1.0);
    }

    /**
     * Formats voltage value with unit and returns absolute value as text.
     *
     * @param v voltage value
     * @return formatted voltage string with appropriate unit prefix (e.g., "5.2 mV")
     */
    static String getVoltageDText(double v) {
        return getUnitText(Math.abs(v), "V");
    }

    /**
     * Formats voltage value with unit as text.
     *
     * @param v voltage value
     * @return formatted voltage string with appropriate unit prefix (e.g., "5.2 mV")
     */
    static String getVoltageText(double v) {
        return getUnitText(v, "V");
    }

    /**
     * Formats time value with appropriate units (seconds, minutes:seconds, hours:minutes:seconds).
     *
     * @param v time value in seconds
     * @return formatted time string
     */
    static String getTimeText(double v) {
        if (v >= 60) {
            double h = Math.floor(v / 3600);
            v -= 3600 * h;
            double m = Math.floor(v / 60);
            v -= 60 * m;
            if (h == 0)
                return m + ":" + ((v >= 10) ? "" : "0") + showFormat.format(v);
            return h + ":" + ((m >= 10) ? "" : "0") + m + ":" + ((v >= 10) ? "" : "0") + showFormat.format(v);
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
    static String format(double v, boolean sf) {
        return (sf ? shortFormat : showFormat).format(v);
    }

    /**
     * Formats a value with unit using normal format.
     *
     * @param v numeric value
     * @param u unit string
     * @return formatted string with appropriate unit prefix
     */
    static String getUnitText(double v, String u) {
        return getUnitText(v, u, false);
    }

    /**
     * Formats a value with unit using short format.
     *
     * @param v numeric value
     * @param u unit string
     * @return formatted string with appropriate unit prefix (short format)
     */
    static String getShortUnitText(double v, String u) {
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
    static String getSIPrefix(int magnitude) {
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
    static String getCurrentText(double i) {
        return getUnitText(i, "A");
    }

    /**
     * Formats current value with unit and returns absolute value as text.
     *
     * @param i current value in amperes
     * @return formatted current string with appropriate unit prefix (e.g., "2.5 mA")
     */
    static String getCurrentDText(double i) {
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
    static String getUnitTextWithScale(double val, String utext, int scale) {
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
    static String getUnitTextWithScale(double val, String utext, int scale, boolean fixed) {
        if (Math.abs(val) > 1e12)
            return getUnitText(val, utext);
        NumberFormat nf = fixed ? fixedFormat : showFormat;
        if (scale == SCALE_1)
            return nf.format(val) + " " + utext;
        if (scale == SCALE_M)
            return nf.format(1e3 * val) + " m" + utext;
        if (scale == SCALE_MU)
            return nf.format(1e6 * val) + " " + Locale.muString + utext;
        return getUnitText(val, utext);
    }



}
