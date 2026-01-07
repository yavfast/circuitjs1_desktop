package com.lushprojects.circuitjs1.client.element;

import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.Rectangle;

/**
 * Geometry/transform helper for {@link CircuitElm}.
 *
 * HARD MODE: this class is the single source of truth for element geometry.
 * Do not read/write geometry via {@link CircuitElm} fields.
 */
public final class ElmGeometry {

    private final CircuitElm owner;

    // Endpoints (grid coordinates)
    private int x1, y1, x2, y2;

    // Canonical point objects (stable references)
    private final Point point1 = new Point();
    private final Point point2 = new Point();
    private Point lead1 = point1;
    private Point lead2 = point2;

    private final Rectangle boundingBox = new Rectangle();

    // Derived geometry
    private int dx, dy, dsign;
    private double dn;
    private double dpx1, dpy1;

    ElmGeometry(CircuitElm owner) {
        this.owner = owner;
    }

    // ======== Accessors ========

    public int getX1() {
        return x1;
    }

    public int getY1() {
        return y1;
    }

    public int getX2() {
        return x2;
    }

    public int getY2() {
        return y2;
    }

    public int getDx() {
        return dx;
    }

    public int getDy() {
        return dy;
    }

    public int getDsign() {
        return dsign;
    }

    public double getDn() {
        return dn;
    }

    public double getDpx1() {
        return dpx1;
    }

    public double getDpy1() {
        return dpy1;
    }

    public Point getPoint1() {
        return point1;
    }

    public Point getPoint2() {
        return point2;
    }

    public Point getLead1() {
        return lead1;
    }

    public Point getLead2() {
        return lead2;
    }

    /**
     * Replace the lead point reference. Needed for elements that must ensure
     * leads are not aliased to endpoints.
     */
    public void setLead1(Point lead1) {
        this.lead1 = lead1;
    }

    /**
     * Replace the lead point reference. Needed for elements that must ensure
     * leads are not aliased to endpoints.
     */
    public void setLead2(Point lead2) {
        this.lead2 = lead2;
    }

    public Rectangle getBoundingBox() {
        return boundingBox;
    }

    // ======== Core recompute ========

    public void updatePointsFromEndpoints() {
        dx = x2 - x1;
        dy = y2 - y1;
        dn = Math.sqrt((double) dx * (double) dx + (double) dy * (double) dy);

        if (dn == 0) {
            dpx1 = 0;
            dpy1 = 0;
        } else {
            dpx1 = dy / dn;
            dpy1 = -dx / dn;
        }

        dsign = (dy == 0) ? BaseCircuitElm.sign(dx) : BaseCircuitElm.sign(dy);

        point1.x = x1;
        point1.y = y1;
        point2.x = x2;
        point2.y = y2;

        // Allow owner to adjust derived geometry.
        owner.adjustDerivedGeometry(this);

        // Ensure leads always exist.
        if (lead1 == null) {
            lead1 = point1;
        }
        if (lead2 == null) {
            lead2 = point2;
        }
    }

    /**
     * Recompute derived perpendicular unit vector, dn and dsign from current dx/dy.
     * Optionally enforce a minimum dn (useful for elements that require min length).
     */
    public void recomputeDerivedWithMinDn(double minDn) {
        dn = Math.sqrt(dx * dx + dy * dy);
        if (dn < minDn) dn = minDn;
        if (dn == 0) {
            dpx1 = 0;
            dpy1 = 0;
        } else {
            dpx1 = dy / dn;
            dpy1 = -dx / dn;
        }
        dsign = (dy == 0) ? BaseCircuitElm.sign(dx) : BaseCircuitElm.sign(dy);

    }

    /**
     * Recompute derived geometry when the element prefers an axis-aligned rendered
     * length (dn = abs(dx)), e.g. for some transformer variants.
     * Updates owner aliases as a compatibility bridge.
     */
    public void recomputeDerivedAxisAlignedWithMinDn(double minDn) {
        dn = Math.abs(dx);
        if (dn < minDn) dn = minDn;
        if (dn == 0) {
            dpx1 = 0;
            dpy1 = 0;
        } else {
            dpx1 = dy / dn;
            dpy1 = -dx / dn;
        }
        dsign = (dy == 0) ? BaseCircuitElm.sign(dx) : BaseCircuitElm.sign(dy);

        point1.x = x1;
        point1.y = y1;
        point2.x = x2;
        point2.y = y2;
    }

    public void setEndpoints(int x1, int y1, int x2, int y2) {
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
        updatePointsFromEndpoints();
    }

    /**
     * Set the x2 endpoint (grid coord) and recompute derived geometry.
     * This is a small convenience method to support legacy callers that set
     * endpoints independently, e.g., TextElm. Prefer using {@link #setEndpoints}.
     */
    public void setX2(int x2) {
        this.x2 = x2;
        updatePointsFromEndpoints();
    }

    /**
     * Set the y2 endpoint (grid coord) and recompute derived geometry.
     * See {@link #setX2(int)} for intent.
     */
    public void setY2(int y2) {
        this.y2 = y2;
        updatePointsFromEndpoints();
    }

    public void translate(int dx, int dy) {
        x1 += dx;
        y1 += dy;
        x2 += dx;
        y2 += dy;
        boundingBox.translate(dx, dy);
        updatePointsFromEndpoints();
        owner.setPoints();
    }

    public void dragTo(int xx, int yy) {
        xx = owner.circuitEditor().snapGrid(xx);
        yy = owner.circuitEditor().snapGrid(yy);
        if (owner.noDiagonal) {
            if (Math.abs(x1 - xx) < Math.abs(y1 - yy)) {
                xx = x1;
            } else {
                yy = y1;
            }
        }
        x2 = xx;
        y2 = yy;
        updatePointsFromEndpoints();
        owner.setPoints();
    }

    public void movePoint(int n, int dx, int dy) {
        int oldx = x1;
        int oldy = y1;
        int oldx2 = x2;
        int oldy2 = y2;

        if (owner.noDiagonal) {
            if (x1 == x2) {
                dx = 0;
            } else {
                dy = 0;
            }
        }

        if (n == 0) {
            x1 += dx;
            y1 += dy;
        } else {
            x2 += dx;
            y2 += dy;
        }

        if (x1 == x2 && y1 == y2) {
            x1 = oldx;
            y1 = oldy;
            x2 = oldx2;
            y2 = oldy2;
        }
        updatePointsFromEndpoints();
        owner.setPoints();
    }

    public void flipX(int center2) {
        x1 = center2 - x1;
        x2 = center2 - x2;
        initBoundingBox();
        updatePointsFromEndpoints();
        owner.setPoints();
    }

    public void flipY(int center2) {
        y1 = center2 - y1;
        y2 = center2 - y2;
        initBoundingBox();
        updatePointsFromEndpoints();
        owner.setPoints();
    }

    public void flipXY(int xmy) {
        int nx = y1 + xmy;
        int ny = x1 - xmy;
        int nx2 = y2 + xmy;
        int ny2 = x2 - xmy;

        x1 = nx;
        y1 = ny;
        x2 = nx2;
        y2 = ny2;

        initBoundingBox();
        updatePointsFromEndpoints();
        owner.setPoints();
    }

    public void flipPosts() {
        int oldx = x1;
        int oldy = y1;
        x1 = x2;
        y1 = y2;
        x2 = oldx;
        y2 = oldy;
        updatePointsFromEndpoints();
        owner.setPoints();
    }

    public void dragFixedSize(int gridX, int gridY) {
        int deltaX = gridX - x1;
        int deltaY = gridY - y1;
        x1 += deltaX;
        y1 += deltaY;
        x2 += deltaX;
        y2 += deltaY;
        updatePointsFromEndpoints();
        owner.setPoints();
    }

    public void calcLeads(int len) {
        double effectiveDn = dn;

        Point p1 = point1;
        Point p2 = point2;

        if (effectiveDn < len || len == 0) {
            lead1 = p1;
            lead2 = p2;
            return;
        }

        if (effectiveDn == 0) {
            lead1 = p1;
            lead2 = p2;
        } else {
            // Allocate lead points once; reuse thereafter.
            if (lead1 == null || lead1 == p1) {
                lead1 = new Point();
            }
            if (lead2 == null || lead2 == p2) {
                lead2 = new Point();
            }

            BaseCircuitElm.interpPoint(p1, p2, lead1, (effectiveDn - len) / (2 * effectiveDn));
            BaseCircuitElm.interpPoint(p1, p2, lead2, (effectiveDn + len) / (2 * effectiveDn));
        }
    }

    public void adjustLeadsToGrid(boolean flipX, boolean flipY) {
        Point p1 = point1;
        Point p2 = point2;
        int cx = (p1.x + p2.x) / 2;
        int cy = (p1.y + p2.y) / 2;

        int roundx = (flipX) ? 1 : -1;
        int roundy = (flipY) ? 1 : -1;

        int adjx = owner.circuitEditor().snapGrid(cx + roundx) - cx;
        int adjy = owner.circuitEditor().snapGrid(cy + roundy) - cy;

        // Ensure we don't accidentally move endpoints when leads are aliased.
        if (lead1 == null || lead1 == p1) {
            lead1 = new Point(p1);
        }
        if (lead2 == null || lead2 == p2) {
            lead2 = new Point(p2);
        }

        lead1.move(adjx, adjy);
        lead2.move(adjx, adjy);
    }

    public void initBoundingBox() {
        // Bounds are used for selection; initialize to endpoint rectangle.
        boundingBox.setBounds(
                BaseCircuitElm.min(x1, x2),
                BaseCircuitElm.min(y1, y2),
                BaseCircuitElm.abs(x2 - x1) + 1,
                BaseCircuitElm.abs(y2 - y1) + 1);
    }

    public boolean isZeroSize() {
        return x1 == x2 && y1 == y2;
    }

    public Point getHandlePoint(int n) {
        if (n == 0) {
            return new Point(x1, y1);
        }
        return new Point(x2, y2);
    }

    public int getMouseDistanceSq(int gx, int gy) {
        if (owner.getPostCount() == 0) {
            return com.lushprojects.circuitjs1.client.Graphics.distanceSq(gx, gy, (x2 + x1) / 2, (y2 + y1) / 2);
        }
        return BaseCircuitElm.lineDistanceSq(x1, y1, x2, y2, gx, gy);
    }

    public void setBbox(int x1, int y1, int x2, int y2) {
        if (x1 > x2) {
            int q = x1;
            x1 = x2;
            x2 = q;
        }
        if (y1 > y2) {
            int q = y1;
            y1 = y2;
            y2 = q;
        }
        boundingBox.setBounds(x1, y1, x2 - x1 + 1, y2 - y1 + 1);
    }

    public void setBbox(Point p1, Point p2, double w) {
        setBbox(p1.x, p1.y, p2.x, p2.y);
        int dpx = (int) (dpx1 * w);
        int dpy = (int) (dpy1 * w);
        adjustBbox(p1.x + dpx, p1.y + dpy, p1.x - dpx, p1.y - dpy);
    }

    public void adjustBbox(int x1, int y1, int x2, int y2) {
        if (x1 > x2) {
            int q = x1;
            x1 = x2;
            x2 = q;
        }
        if (y1 > y2) {
            int q = y1;
            y1 = y2;
            y2 = q;
        }
        x1 = BaseCircuitElm.min(boundingBox.x, x1);
        y1 = BaseCircuitElm.min(boundingBox.y, y1);
        x2 = BaseCircuitElm.max(boundingBox.x + boundingBox.width, x2);
        y2 = BaseCircuitElm.max(boundingBox.y + boundingBox.height, y2);
        boundingBox.setBounds(x1, y1, x2 - x1, y2 - y1);
    }

    public void adjustBbox(Point p1, Point p2) {
        adjustBbox(p1.x, p1.y, p2.x, p2.y);
    }
}
