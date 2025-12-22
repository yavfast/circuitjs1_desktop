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

package com.lushprojects.circuitjs1.client.element;

import com.lushprojects.circuitjs1.client.CircuitDocument;

import com.google.gwt.user.client.Window;
import com.lushprojects.circuitjs1.client.Checkbox;
import com.lushprojects.circuitjs1.client.CircuitMath;
import com.lushprojects.circuitjs1.client.CircuitSimulator;
import com.lushprojects.circuitjs1.client.CustomLogicModel;
import com.lushprojects.circuitjs1.client.Graphics;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.StringTokenizer;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;

public class CustomTransformerElm extends CircuitElm {
    public static final int FLAG_FLIP = 1;
    int flip;

    private static boolean DEBUG_RESIZE_HANDLES = false;

    private static native void console(String text)
    /*-{
        console.log(text);
    }-*/;

    private static final int NODE_GAP = 8;
    private static final int HANDLE_CORNER_COUNT = 4;

    private static final class Node {
        final Point point = new Point(0, 0);
        final Point tap = new Point(0, 0);

        boolean tapNode;
        boolean movable;

        int offsetOverride = -1;
        double offsetComputed;

        double current;
        double currentCount;
    }

    private static final class Winding {
        // first node index of this winding (second node = startNode+1)
        int startNode;
        double inductance;
        double turns;
        double polarity = 1;

        double current;
        double currentCount;
        double curSourceValue;
    }

    // Backward-compatible counters used by dump/JSON/state.
    int coilCount, nodeCount;

    // number of primary coils
    int primaryCoils;

    private Node[] nodeData = new Node[0];
    private Winding[] windings = new Winding[0];

    // Nodes that can be dragged (tap handles). Includes tap nodes from '+' and
    // independent primary-side nodes (left side) except the two extremes.
    private Node[] movableNodes = new Node[0];

    Point ptCore[];
    String description;
    double inductance, couplingCoef;
    boolean needDots;

    Point dots[];
    int width;

    private int minCoilWidth() {
        return 16;
    }

    private int minLen() {
        return 32;
    }

    private int minTapSeg() {
        return 16;
    }

    private int stackGapCount(int stackNodes, int stackCoils) {
        // The max offset comes from the offset used *at* node positions, so the gap after the last
        // node is not included.
        return max(0, stackNodes - stackCoils - 1);
    }

    private int stackMaxOffset(int stackNodes, int stackCoils, int coilWidth) {
        if (stackNodes <= 0 || stackCoils <= 0)
            return 0;
        return stackCoils * coilWidth + stackGapCount(stackNodes, stackCoils) * NODE_GAP;
    }

    private int getPrimaryNodes() {
        if (primaryCoils >= windings.length)
            return nodeData.length;
        if (primaryCoils <= 0)
            return 0;
        return windings[primaryCoils].startNode;
    }

    private int getPrimaryMaxOffset(int coilWidth) {
        return stackMaxOffset(getPrimaryNodes(), primaryCoils, coilWidth);
    }

    private int getSecondaryMaxOffset(int coilWidth) {
        int primaryNodes = getPrimaryNodes();
        return stackMaxOffset(nodeCount - primaryNodes, coilCount - primaryCoils, coilWidth);
    }

    private int requiredCoilWidthForThickness(int targetThickness) {
        if (coilCount <= 0)
            return width;

        int primaryNodes = getPrimaryNodes();
        int primaryCoilsLocal = primaryCoils;
        int secondaryNodes = nodeCount - primaryNodes;
        int secondaryCoilsLocal = coilCount - primaryCoils;

        int reqPrimary = 0;
        if (primaryCoilsLocal > 0) {
            int gaps = stackGapCount(primaryNodes, primaryCoilsLocal) * NODE_GAP;
            int avail = max(0, targetThickness - gaps);
            reqPrimary = (avail + primaryCoilsLocal - 1) / primaryCoilsLocal;
        }

        int reqSecondary = 0;
        if (secondaryCoilsLocal > 0) {
            int gaps = stackGapCount(secondaryNodes, secondaryCoilsLocal) * NODE_GAP;
            int avail = max(0, targetThickness - gaps);
            reqSecondary = (avail + secondaryCoilsLocal - 1) / secondaryCoilsLocal;
        }

        // Overall rendered thickness is max(primaryThickness(width), secondaryThickness(width)).
        // For a given target thickness, the controlling side is the one that reaches the target
        // with the *smaller* per-coil width (usually the side with more coils/gaps). Using max()
        // here would overshoot badly when one side has fewer coils.
        if (reqPrimary > 0 && reqSecondary > 0)
            return min(reqPrimary, reqSecondary);
        return max(reqPrimary, reqSecondary);
    }

    public CustomTransformerElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy);
        inductance = 1;
        width = 32;
        noDiagonal = true;
        couplingCoef = .999;
        description = "1,1:1";
        parseDescription(description);

        // Fixed nominal size on creation (no resize while adding).
        int nominalLen = 32;
        int nominalCoilWidth = 32;
        width = nominalCoilWidth;
        x2 = x + nominalLen;
        y2 = y;
        setPoints();
    }

    public CustomTransformerElm(CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f,
                                StringTokenizer st) {
        super(circuitDocument, xa, ya, xb, yb, f);
        // Start with a default coil width; we'll reconcile with saved height after parsing.
        width = 32;
        // Saved y-span represents the overall rendered thickness (max stack offset).
        int savedThickness = abs(yb - ya);
        inductance = parseDouble(st.nextToken());
        couplingCoef = parseDouble(st.nextToken());
        String str = st.nextToken();
        description = CustomLogicModel.unescape(str);
        int savedCoilCount = parseInt(st.nextToken());
        double[] savedCoilCurrents = new double[savedCoilCount];
        for (int i = 0; i != savedCoilCount; i++)
            savedCoilCurrents[i] = parseDouble(st.nextToken());
        noDiagonal = true;
        parseDescription(description);

        // Restore saved coil currents if possible (backward-compatible).
        int lim = Math.min(savedCoilCurrents.length, windings.length);
        for (int i = 0; i < lim; i++)
            windings[i].current = savedCoilCurrents[i];

        // Optional extra geometry fields (backward-compatible): tap overrides.
        // Format: <tapOverrideCount> [<nodeIndex> <offset> ...]
        if (st.hasMoreTokens()) {
            int cnt = parseInt(st.nextToken());
            if (cnt > 0) {
                for (int i = 0; i < cnt && st.hasMoreTokens(); i++) {
                    int ni = parseInt(st.nextToken());
                    if (!st.hasMoreTokens())
                        break;
                    int off = parseInt(st.nextToken());
                    if (ni >= 0 && ni < nodeCount) {
                        nodeData[ni].offsetOverride = max(0, off);
                    }
                }
            }
        }

        // Apply saved thickness (if any) by deriving the shared per-coil width.
        if (savedThickness > 0) {
            int minCoilWidth = minCoilWidth();
            width = max(minCoilWidth, requiredCoilWidthForThickness(savedThickness));
            setPoints();
        }
    }

    @Override
    public boolean isFixedSizeOnCreate() {
        return true;
    }

    @Override
    int getNumHandles() {
        return HANDLE_CORNER_COUNT + (movableNodes == null ? 0 : movableNodes.length);
    }

    @Override
    public Point getHandlePoint(int n) {
        int x1 = Math.min(x, x2);
        int x3 = Math.max(x, x2);
        int y1 = Math.min(y, y2);
        int y3 = Math.max(y, y2);
        if (n == 0)
            return new Point(x1, y1);
        if (n == 1)
            return new Point(x3, y1);
        if (n == 2)
            return new Point(x3, y3);
        if (n == 3)
            return new Point(x1, y3);

        int ti = n - HANDLE_CORNER_COUNT;
        if (ti >= 0 && movableNodes != null && ti < movableNodes.length)
            return movableNodes[ti].point;

        return super.getHandlePoint(n);
    }

    public void drag(int xx, int yy) {
        int sx = circuitEditor().snapGrid(xx);
        int sy = circuitEditor().snapGrid(yy);

        // Horizontal only: x-axis sets length, y deviation sets coil spacing.
        width = max(minCoilWidth(), abs(sy - y));
        x2 = sx;
        y2 = sy;
        setPoints();
    }

    @Override
    public void movePoint(int n, int dx, int dy) {
        // 4-corner resizing with minimum nominal size.
        int minLen = minLen();
        int minCoilWidth = minCoilWidth();

        if (DEBUG_RESIZE_HANDLES && n < HANDLE_CORNER_COUNT) {
            console("[CustomTransformerElm] movePoint begin n=" + n +
                    " dx=" + dx + " dy=" + dy +
                    " x=" + x + " y=" + y + " x2=" + x2 + " y2=" + y2 +
                    " flip=" + flip + " width=" + width);
        }

        // Tap handle(s): slide a movable node along the winding stack direction.
        if (moveTapHandleIfAny(n, dy))
            return;

        if (n >= HANDLE_CORNER_COUNT) {
            super.movePoint(n, dx, dy);
            return;
        }

        // Resize by moving the relevant rectangle edges. This avoids relying on
        // opposite-corner selection, which can behave unexpectedly when y/y2 are
        // interpreted as baseline+thickness.
        int x1 = Math.min(x, x2);
        int x3 = Math.max(x, x2);
        int y1 = Math.min(y, y2);
        int y3 = Math.max(y, y2);

        boolean moveLeft = (n == 0 || n == 3);
        boolean moveRight = (n == 1 || n == 2);
        boolean moveTop = (n == 0 || n == 1);
        boolean moveBottom = (n == 2 || n == 3);

        int nx1 = x1;
        int nx2 = x3;
        int ny1 = y1;
        int ny2 = y3;

        if (moveLeft)
            nx1 = circuitEditor().snapGrid(x1 + dx);
        if (moveRight)
            nx2 = circuitEditor().snapGrid(x3 + dx);
        if (moveTop)
            ny1 = circuitEditor().snapGrid(y1 + dy);
        if (moveBottom)
            ny2 = circuitEditor().snapGrid(y3 + dy);

        // Minimum overall thickness depends on how many coils/nodes are on each side.
        int minThickness = max(getPrimaryMaxOffset(minCoilWidth), getSecondaryMaxOffset(minCoilWidth));

        if (nx2 - nx1 < minLen) {
            if (moveLeft && !moveRight)
                nx1 = nx2 - minLen;
            else
                nx2 = nx1 + minLen;
        }
        if (ny2 - ny1 < minThickness) {
            if (moveTop && !moveBottom)
                ny1 = ny2 - minThickness;
            else
                ny2 = ny1 + minThickness;
        }

        x = nx1;
        x2 = nx2;

        int targetThickness = abs(ny2 - ny1);
        width = max(minCoilWidth, requiredCoilWidthForThickness(targetThickness));

        // Baseline is on the "inner" edge; which edge that is depends on flip.
        // flip=1 draws outward towards negative y (so baseline is bottom edge).
        // flip=-1 draws outward towards positive y (so baseline is top edge).
        y = (flip == 1) ? ny2 : ny1;
        setPoints();

        if (DEBUG_RESIZE_HANDLES && n < HANDLE_CORNER_COUNT) {
            console("[CustomTransformerElm] movePoint end n=" + n +
                    " nx1=" + nx1 + " nx2=" + nx2 + " ny1=" + ny1 + " ny2=" + ny2 +
                    " => x=" + x + " y=" + y + " x2=" + x2 + " y2=" + y2 +
                    " flip=" + flip + " width=" + width);
        }
    }

    private boolean moveTapHandleIfAny(int handleIndex, int dy) {
        if (handleIndex < HANDLE_CORNER_COUNT || movableNodes == null || nodeData == null)
            return false;

        int ti = handleIndex - HANDLE_CORNER_COUNT;
        if (ti < 0 || ti >= movableNodes.length)
            return false;

        Node node = movableNodes[ti];
        if (node == null)
            return false;

        // For this element, point2.y is constrained to point1.y, so the stack direction is vertical.
        // Convert desired screen Y to internal offset.
        int desiredY = circuitEditor().snapGrid(node.point.y + dy);
        int baseY = point1.y;
        double desiredHoff = baseY - desiredY;
        double denom = (flip != 0) ? flip : 1;
        int desiredOff = (int) Math.round(desiredHoff / denom);
        node.offsetOverride = max(0, desiredOff);
        setPoints();
        return true;
    }

    int getDumpType() {
        return 406;
    }

    public String dump() {
        String s = dumpValues(super.dump(), inductance, couplingCoef, escape(description), coilCount) + " ";
        for (int i = 0; i < windings.length; i++)
            s += dumpValue(windings[i].current) + " "; // TODO:

        // Optional tap overrides (backward-compatible): only write if any are set.
        int cnt = 0;
        for (int i = 0; i < nodeCount; i++)
            if (nodeData[i].offsetOverride >= 0)
                cnt++;
        if (cnt > 0) {
            s += dumpValue(cnt) + " ";
            for (int i = 0; i < nodeCount; i++) {
                if (nodeData[i].offsetOverride >= 0)
                    s += dumpValue(i) + " " + dumpValue(nodeData[i].offsetOverride) + " ";
            }
        }
        return s;
    }

    void parseDescription() {
        parseDescription(description);
    }

    boolean parseDescription(String desc) {
        // a number indicates a coil (number = turns ratio to base inductance coil)
        // (negative number = reverse polarity)
        // : separates primary and secondary
        // , separates two coils
        // + separates two connected coils (tapped)
        StringTokenizer st = new StringTokenizer(desc, ",:+", true);

        // count windings/nodes
        int newCoilCount = 0;
        int newNodeCount = 0;
        while (st.hasMoreTokens()) {
            String s = st.nextToken();
            if ("+".equals(s))
                newNodeCount--;
            if (",".equals(s) || "+".equals(s) || ":".equals(s))
                continue;
            newNodeCount += 2;
            newCoilCount++;
        }

        // Preserve currents where possible when description didn't change coil count.
        double[] oldCoilCurrents = null;
        if (windings != null && windings.length > 0) {
            oldCoilCurrents = new double[windings.length];
            for (int i = 0; i < windings.length; i++)
                oldCoilCurrents[i] = windings[i].current;
        }

        Node[] newNodes = new Node[newNodeCount];
        for (int i = 0; i < newNodeCount; i++)
            newNodes[i] = new Node();

        Winding[] newWindings = new Winding[newCoilCount];

        // start over
        st = new StringTokenizer(desc, ",:+", true);
        int nodeNum = 0;
        int coilNum = 0;
        primaryCoils = 0;
        boolean secondary = false;
        needDots = false;
        while (true) {
            String tok = st.nextToken();
            double n;
            try {
                n = Double.parseDouble(tok);
            } catch (Exception e) {
                return false;
            }
            if (n == 0)
                return false;

            Winding w = new Winding();
            w.startNode = nodeNum;
            w.turns = Math.abs(n);
            w.inductance = n * n * inductance;
            w.polarity = 1;
            if (n < 0) {
                w.polarity = -1;
                needDots = true;
            }
            // Each coil starts at the current node index; no per-node marker needed.
            newWindings[coilNum] = w;

            nodeNum += 2;
            coilNum++;
            if (!secondary)
                primaryCoils = coilNum;
            if (!st.hasMoreTokens())
                break;

            tok = st.nextToken();
            if (",".equals(tok))
                continue;
            if ("+".equals(tok)) {
                nodeNum--;
                if (nodeNum >= 0 && nodeNum < newNodeCount)
                    newNodes[nodeNum].tapNode = true;
                continue;
            }
            if (":".equals(tok)) {
                if (secondary)
                    return false;
                secondary = true;
                continue;
            }
            return false;
        }

        nodeData = newNodes;
        windings = newWindings;
        nodeCount = nodeData.length;
        coilCount = windings.length;

        if (oldCoilCurrents != null && oldCoilCurrents.length == windings.length) {
            for (int i = 0; i < windings.length; i++)
                windings[i].current = oldCoilCurrents[i];
        }

        rebuildMovableNodes();
        allocNodes();
        setPoints();
        xformMatrix = null;
        return true;
    }

    private void rebuildMovableNodes() {
        if (nodeData == null || nodeData.length == 0) {
            movableNodes = new Node[0];
            return;
        }

        int primaryNodes = getPrimaryNodes();
        int cnt = 0;
        for (int i = 0; i < nodeData.length; i++) {
            boolean movable = nodeData[i].tapNode;
            if (!movable && i > 0 && i < primaryNodes - 1)
                movable = true;
            nodeData[i].movable = movable;
            if (movable)
                cnt++;
        }

        Node[] arr = new Node[cnt];
        int k = 0;
        for (int i = 0; i < nodeData.length; i++) {
            if (nodeData[i].movable)
                arr[k++] = nodeData[i];
        }
        movableNodes = arr;
    }

    private int[] snapshotOffsetOverrides() {
        if (nodeData == null)
            return null;
        int[] saved = new int[nodeData.length];
        for (int i = 0; i < nodeData.length; i++)
            saved[i] = nodeData[i].offsetOverride;
        return saved;
    }

    private void restoreOffsetOverrides(int[] saved) {
        if (saved == null || nodeData == null)
            return;
        int lim = Math.min(saved.length, nodeData.length);
        for (int i = 0; i < lim; i++)
            nodeData[i].offsetOverride = saved[i];
    }

    boolean isTrapezoidal() {
        return (flags & Inductor.FLAG_BACK_EULER) == 0;
    }

    public void draw(Graphics g) {
        int i;

        // Core center used to orient winding curvature inward.
        double coreCx = 0, coreCy = 0;
        for (i = 0; i != 4; i++) {
            coreCx += ptCore[i].x;
            coreCy += ptCore[i].y;
        }
        coreCx /= 4;
        coreCy /= 4;

        // draw taps
        for (i = 0; i != getPostCount(); i++) {
            setVoltageColor(g, getNodeVoltage(i));
            drawThickLine(g, nodeData[i].point, nodeData[i].tap);
        }

        // draw coils
        for (i = 0; i != coilCount; i++) {
            int n = windings[i].startNode;
            setVoltageColor(g, getNodeVoltage(n));
            setPowerColor(g, windings[i].current * (getNodeVoltage(n) - getNodeVoltage(n + 1)));

            // Make the coil "bulge" face the core (inward), independent of winding order/flip.
            Point a = nodeData[n].tap;
            Point b = nodeData[n + 1].tap;
            double mx = (a.x + b.x) / 2.0;
            double my = (a.y + b.y) / 2.0;
            double dxl = b.x - a.x;
            double dyl = b.y - a.y;
            double len = Math.sqrt(dxl * dxl + dyl * dyl);
            if (len < 1) {
                dxl = 1;
                dyl = 0;
                len = 1;
            }

            // unit perpendicular to coil segment; positive hs bulges towards (px,py)
            double px = -dyl / len;
            double py = dxl / len;
            double toCoreX = coreCx - mx;
            double toCoreY = coreCy - my;
            // In CircuitElm.drawCoil(), positive hs maps to one side of the local +Y axis after
            // internal coordinate transforms. Empirically, the inward-facing direction is the
            // opposite of the geometric (px,py) dot-product test.
            int hs = (px * toCoreX + py * toCoreY >= 0) ? -6 : 6;

            drawCoil(g, hs, a, b, getNodeVoltage(n), getNodeVoltage(n + 1));
            if (dots != null) {
                g.setColor(needsHighlight() ? selectColor() : elementColor());
                g.fillOval(dots[i].x - 2, dots[i].y - 2, 5, 5);
            }
        }

        // winding labels (turns)
        g.save();
        g.setFont(unitsFont());
        g.setColor(needsHighlight() ? selectColor() : foregroundColor());
        for (i = 0; i != coilCount; i++) {
            int n = windings[i].startNode;
            String label = shortFormat(windings[i].turns) + "T";
            Point a = nodeData[n].tap;
            Point b = nodeData[n + 1].tap;
            double mx = (a.x + b.x) / 2.0;
            double my = (a.y + b.y) / 2.0;
            double dxl = b.x - a.x;
            double dyl = b.y - a.y;
            double len = Math.sqrt(dxl * dxl + dyl * dyl);
            if (len < 1) {
                dxl = 1;
                dyl = 0;
                len = 1;
            }

            // unit perpendicular to coil segment
            double px = -dyl / len;
            double py = dxl / len;

            // choose side that points away from the core center
            double toCoreX = coreCx - mx;
            double toCoreY = coreCy - my;
            if (px * toCoreX + py * toCoreY > 0) {
                px = -px;
                py = -py;
            }

            int lx = (int) Math.round(mx + px * 12);
            int ly = (int) Math.round(my + py * 12);
            drawCenteredText(g, label, lx, ly, true);
        }
        g.restore();

        g.setColor(needsHighlight() ? selectColor() : elementColor());

        // draw core
        for (i = 0; i != 2; i++) {
            drawThickLine(g, ptCore[i], ptCore[i + 2]);
        }

        // draw coil currents
        for (i = 0; i != coilCount; i++) {
            windings[i].currentCount = updateDotCount(windings[i].current, windings[i].currentCount);
            int ni = windings[i].startNode;
            drawDots(g, nodeData[ni].tap, nodeData[ni + 1].tap, windings[i].currentCount);
        }

        // draw tap currents
        for (i = 0; i != nodeCount; i++) {
            nodeData[i].currentCount = updateDotCount(nodeData[i].current, nodeData[i].currentCount);
            drawDots(g, nodeData[i].point, nodeData[i].tap, nodeData[i].currentCount);
        }

        drawPosts(g);
        setBbox(nodeData[0].point, nodeData[nodeCount - 1].point, 0);
        adjustBbox(ptCore[0], ptCore[3]);
        adjustBbox(new Point(x, y), new Point(x2, y2));
    }

    public void setPoints() {
        super.setPoints();
        // Keep the resize handle diagonal (y2) but constrain the rendered axis.
        point2.y = point1.y;
        dx = point2.x - point1.x;
        dy = point2.y - point1.y;
        dn = Math.sqrt(dx * dx + dy * dy);
        if (dn < 1)
            dn = 1;
        dpx1 = dy / dn;
        dpy1 = -dx / dn;
        dsign = (dy == 0) ? sign(dx) : sign(dy);
        flip = hasFlag(FLAG_FLIP) ? -1 : 1;
        int i;
        int primaryNodes = getPrimaryNodes();
        dn = Math.abs(point1.x - point2.x);
        if (dn < 1)
            dn = 1;
        double ce = .5 - 12 / dn;
        double cd = .5 - 2 / dn;
        double maxWidth = 0;

        // Pass 1: compute default offsets (from inner edge) for each node.
        {
            int c = 0;
            double offset = 0;
            for (i = 0; i != nodeCount; i++) {
                if (i == primaryNodes)
                    offset = 0;
                nodeData[i].offsetComputed = offset;
                maxWidth = Math.max(maxWidth, offset);
                int nn = c < coilCount ? windings[c].startNode : -1;
                if (nn == i) {
                    c++;
                    offset += width;
                } else {
                    offset += NODE_GAP;
                }
            }
        }

        // Apply movable-node overrides with ordering constraints within each stack.
        int segMin = minTapSeg();
        if (segMin < 1)
            segMin = 1;

        int primaryInternalStart = 1;
        int primaryInternalEnd = primaryNodes - 1;

        // Primary stack
        if (primaryNodes > 0) {
            // Forward pass
            for (i = 0; i < primaryNodes; i++) {
                boolean movable = nodeData[i].tapNode;
                if (!movable && i >= primaryInternalStart && i < primaryInternalEnd)
                    movable = true;

                if (movable && nodeData[i].offsetOverride >= 0)
                    nodeData[i].offsetComputed = nodeData[i].offsetOverride;

                if (i > 0 && movable)
                    nodeData[i].offsetComputed = Math.max(nodeData[i].offsetComputed, nodeData[i - 1].offsetComputed + segMin);
            }
            // Backward pass
            for (i = primaryNodes - 2; i >= 0; i--) {
                boolean movable = nodeData[i].tapNode;
                if (!movable && i >= primaryInternalStart && i < primaryInternalEnd)
                    movable = true;
                if (movable)
                    nodeData[i].offsetComputed = Math.min(nodeData[i].offsetComputed, nodeData[i + 1].offsetComputed - segMin);
            }
        }

        // Secondary stack
        if (primaryNodes < nodeCount) {
            for (i = primaryNodes; i < nodeCount; i++) {
                boolean movable = nodeData[i].tapNode;
                if (movable && nodeData[i].offsetOverride >= 0)
                    nodeData[i].offsetComputed = nodeData[i].offsetOverride;
                if (i > primaryNodes && movable)
                    nodeData[i].offsetComputed = Math.max(nodeData[i].offsetComputed, nodeData[i - 1].offsetComputed + segMin);
            }
            for (i = nodeCount - 2; i >= primaryNodes; i--) {
                boolean movable = nodeData[i].tapNode;
                if (movable)
                    nodeData[i].offsetComputed = Math.min(nodeData[i].offsetComputed, nodeData[i + 1].offsetComputed - segMin);
            }
        }

        // Pass 2: generate points using the final offsets.
        for (i = 0; i != nodeCount; i++) {
            double offset = nodeData[i].offsetComputed;
            if (i == primaryNodes - 1 || i == nodeCount - 1)
                offset = maxWidth;
            if (offset < 0)
                offset = 0;
            if (offset > maxWidth)
                offset = maxWidth;

            // Make the perpendicular direction independent of whether the element was drawn
            // left-to-right or right-to-left (dsign captures that).
            double hoff = offset * flip * dsign;
            interpPoint(point1, point2, nodeData[i].point, i < primaryNodes ? 0 : 1, hoff);
            interpPoint(point1, point2, nodeData[i].tap, i < primaryNodes ? ce : 1 - ce, hoff);
        }

        // Keep the persisted handle rectangle in sync with the actually rendered thickness.
        // The element is drawn from baseline y outward by maxWidth*flip.
        int thick = (int) Math.round(maxWidth);
        if (thick < 1)
            thick = 1;
        y2 = y - thick * flip;

        ptCore = newPointArray(4);
        for (i = 0; i != 4; i += 2) {
            double h = (i == 2) ? maxWidth * flip * dsign : 0;
            interpPoint(point1, point2, ptCore[i], cd, h);
            interpPoint(point1, point2, ptCore[i + 1], 1 - cd, h);
        }

        if (needDots) {
            dots = new Point[coilCount];
            double dotp = Math.abs(7. / width);
            for (i = 0; i != coilCount; i++) {
                int n = windings[i].startNode;
                dots[i] = interpPoint(nodeData[n].tap, nodeData[n + 1].tap, windings[i].polarity > 0 ? dotp : 1 - dotp, i < primaryCoils ? -7 : 7);
            }
        } else
            dots = null;
    }

    public Point getPost(int n) {
        return nodeData[n].point;
    }

    public int getPostCount() {
        return nodeCount;
    }

    public void reset() {
        for (int i = 0; i != coilCount; i++) {
            windings[i].current = 0;
            windings[i].curSourceValue = 0;
            windings[i].currentCount = 0;
        }
        for (int i = 0; i != nodeCount; i++) {
            setNodeVoltageDirect(i, 0);
            nodeData[i].current = 0;
            nodeData[i].currentCount = 0;
        }
    }

    double xformMatrix[][];

    public void stamp() {
        // equations for transformer:
        //   v1 = L1  di1/dt + M12  di2/dt + M13 di3/dt + ...
        //   v2 = M21 di1/dt + L2 di2/dt   + M23 di3/dt + ...
        //   v3 = ... (one row for each coil)
        // we invert that to get:
        //   di1/dt = a1 v1 + a2 v2 + ...
        //   di2/dt = a3 v1 + a4 v2 + ...
        // integrate di1/dt using trapezoidal approx and we get:
        //   i1(t2) = i1(t1) + dt/2 (i1(t1) + i1(t2))
        //          = i1(t1) + a1 dt/2 v1(t1) + a2 dt/2 v2(t1) + ... +
        //                     a1 dt/2 v1(t2) + a2 dt/2 v2(t2) + ...
        // the norton equivalent of this for i1 is:
        //  a. current source, I = i1(t1) + a1 dt/2 v1(t1) + a2 dt/2 v2(t1) + ...
        //  b. resistor, G = a1 dt/2
        //  c. current source controlled by voltage v2, G = a2 dt/2
        // and for i2:
        //  a. current source, I = i2(t1) + a3 dt/2 v1(t1) + a4 dt/2 v2(t1) + ...
        //  b. resistor, G = a3 dt/2
        //  c. current source controlled by voltage v2, G = a4 dt/2
        //
        // For backward euler, the current source value is just i1(t1) and we use
        // dt instead of dt/2 for the resistor and VCCS.
        xformMatrix = new double[coilCount][coilCount];
        int i;
        // fill diagonal
        for (i = 0; i != coilCount; i++)
            xformMatrix[i][i] = windings[i].inductance;
        int j;
        // fill off-diagonal
        for (i = 0; i != coilCount; i++)
            for (j = 0; j != i; j++)
                xformMatrix[i][j] = xformMatrix[j][i] = couplingCoef * Math.sqrt(windings[i].inductance * windings[j].inductance) * windings[i].polarity * windings[j].polarity;

        CircuitMath.invertMatrix(xformMatrix, coilCount);

        CircuitSimulator simulator = simulator();
        double ts = isTrapezoidal() ? simulator.timeStep / 2 : simulator.timeStep;
        for (i = 0; i != coilCount; i++)
            for (j = 0; j != coilCount; j++) {
                // multiply in dt/2 (or dt for backward euler)
                xformMatrix[i][j] *= ts;
                int ni = windings[i].startNode;
                int nj = windings[j].startNode;
                if (i == j)
                    simulator.stampConductance(getNode(ni), getNode(ni + 1), xformMatrix[i][i]);
                else
                    simulator.stampVCCurrentSource(getNode(ni), getNode(ni + 1), getNode(nj), getNode(nj + 1), xformMatrix[i][j]);
            }
        for (i = 0; i != nodeCount; i++)
            simulator.stampRightSide(getNode(i));
    }

    public void startIteration() {
        int i;
        for (i = 0; i != coilCount; i++) {
            double val = windings[i].current;
            if (isTrapezoidal()) {
                int j;
                for (j = 0; j != coilCount; j++) {
                    int n = windings[j].startNode;
                    double voltdiff = getNodeVoltage(n) - getNodeVoltage(n + 1);
                    val += voltdiff * xformMatrix[i][j];
                }
            }
            windings[i].curSourceValue = val;
        }
    }

    public void doStep() {
        CircuitSimulator simulator = simulator();
        for (int i = 0; i != coilCount; i++) {
            int n = windings[i].startNode;
            simulator.stampCurrentSource(getNode(n), getNode(n + 1), windings[i].curSourceValue);
        }
    }

    void calculateCurrent() {
        int i;
        for (i = 0; i != nodeCount; i++)
            nodeData[i].current = 0;
        for (i = 0; i != coilCount; i++) {
            double val = windings[i].curSourceValue;
            if (xformMatrix != null) {
                int j;
                for (j = 0; j != coilCount; j++) {
                    int n = windings[j].startNode;
                    double voltdiff = getNodeVoltage(n) - getNodeVoltage(n + 1);
                    val += voltdiff * xformMatrix[i][j];
                }
            }
            windings[i].current = val;
            int ni = windings[i].startNode;
            nodeData[ni].current += val;
            nodeData[ni + 1].current -= val;
        }
    }

    @Override
    public double getCurrentIntoNode(int n) {
        return -nodeData[n].current;
    }

    public void getInfo(String arr[]) {
        arr[0] = "transformer (custom)";
        arr[1] = "L = " + getUnitText(inductance, "H");
        int i;
        for (i = 0; i != coilCount; i++) {
            if (2 + i * 2 >= arr.length)
                break;
            int ni = windings[i].startNode;
            arr[2 + i * 2] = "Vd" + (i + 1) + " = " + getVoltageText(getNodeVoltage(ni) - getNodeVoltage(ni + 1));
            arr[3 + i * 2] = "I" + (i + 1) + " = " + getCurrentText(windings[i].current);
        }
    }

    public boolean getConnection(int n1, int n2) {
        int i;
        for (i = 0; i != coilCount; i++)
            if (comparePair(n1, n2, windings[i].startNode, windings[i].startNode + 1))
                return true;
        return false;
    }

    public EditInfo getEditInfo(int n) {
        if (n == 0)
            return new EditInfo("Base Inductance (H)", inductance, .01, 5);
        if (n == 1) {
            EditInfo ei = new EditInfo(EditInfo.makeLink("customtransformer.html", "Description"), 0, -1, -1);
            ei.text = description;
            ei.disallowSliders();
            return ei;
        }
        if (n == 2)
            return new EditInfo("Coupling Coefficient", couplingCoef, 0, 1).
                    setDimensionless();
        if (n == 3) {
            EditInfo ei = new EditInfo("", 0, -1, -1);
            ei.checkbox = new Checkbox("Trapezoidal Approximation",
                    isTrapezoidal());
            return ei;
        }
        return null;
    }

    public void setEditValue(int n, EditInfo ei) {
        if (n == 0 && ei.value > 0) {
            inductance = ei.value;
            int[] saved = snapshotOffsetOverrides();
            parseDescription();
            // Preserve any dragged node offsets when the description didn't change.
            restoreOffsetOverrides(saved);
            setPoints();
        }
        if (n == 1) {
            String s = ei.textf.getText();
            if (s == null)
                s = "";
            // Only re-parse if the description text actually changed.
            if (!s.equals(description)) {
                if (!parseDescription(s)) {
                    parseDescription(description);
                    Window.alert("Parse error in description");
                } else {
                    description = s;
                }
                setPoints();
            }
        }
        if (n == 2 && ei.value > 0 && ei.value < 1) {
            couplingCoef = ei.value;
            int[] saved = snapshotOffsetOverrides();
            parseDescription();
            restoreOffsetOverrides(saved);
            setPoints();
        }
        if (n == 3) {
            if (ei.checkbox.getState())
                flags &= ~Inductor.FLAG_BACK_EULER;
            else
                flags |= Inductor.FLAG_BACK_EULER;
            int[] saved = snapshotOffsetOverrides();
            parseDescription();
            restoreOffsetOverrides(saved);
            setPoints();
        }
    }

    public void flipX(int c2, int count) {
        flags ^= FLAG_FLIP;
        super.flipX(c2, count);
    }

    public void flipY(int c2, int count) {
        flags ^= FLAG_FLIP;
        super.flipY(c2, count);
    }

    // vertical not supported
    public boolean canFlipXY() {
        return false;
    }

    @Override
    public String getJsonTypeName() {
        return "CustomTransformer";
    }

    @Override
    public java.util.Map<String, Object> getJsonProperties() {
        java.util.Map<String, Object> props = super.getJsonProperties();
        props.put("inductance", getUnitText(inductance, "H"));
        props.put("coupling_coefficient", couplingCoef);
        props.put("description", description);

        // Persist any dragged tap offsets (both '+' taps and independent primary-side taps).
        if (nodeData != null) {
            java.util.List<java.util.Map<String, Object>> tapOffsets = new java.util.ArrayList<>();
            for (int i = 0; i < nodeData.length; i++) {
                if (nodeData[i].offsetOverride >= 0) {
                    java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
                    entry.put("node", i);
                    entry.put("offset", nodeData[i].offsetOverride);
                    tapOffsets.add(entry);
                }
            }
            if (!tapOffsets.isEmpty())
                props.put("tap_offsets", tapOffsets);
        }

        props.put("trapezoidal", isTrapezoidal());
        return props;
    }

    @Override
    public void applyJsonProperties(java.util.Map<String, Object> props) {
        super.applyJsonProperties(props);

        inductance = com.lushprojects.circuitjs1.client.io.json.UnitParser.parse(
                getJsonString(props, "inductance", "4 H"));

        couplingCoef = getJsonDouble(props, "coupling_coefficient", 0.999);
        if (couplingCoef <= 0 || couplingCoef >= 1)
            couplingCoef = 0.999;

        String oldDescription = description;
        description = getJsonString(props, "description", description);

        boolean trap = getJsonBoolean(props, "trapezoidal", isTrapezoidal());
        if (trap)
            flags &= ~Inductor.FLAG_BACK_EULER;
        else
            flags |= Inductor.FLAG_BACK_EULER;

        // Rebuild element from description first (allocates nodes and clears overrides)
        if (!parseDescription(description)) {
            // Keep current instance usable even if imported JSON had a bad description.
            parseDescription(oldDescription);
            description = oldDescription;
        }

        // Restore tap offsets
        Object tapObj = (props == null) ? null : props.get("tap_offsets");
        if (tapObj instanceof java.util.List) {
            java.util.List<?> list = (java.util.List<?>) tapObj;
            for (Object o : list) {
                if (o instanceof java.util.Map) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> m = (java.util.Map<String, Object>) o;
                    int node = getJsonInt(m, "node", -1);
                    int offset = getJsonInt(m, "offset", -1);
                    if (nodeData != null && node >= 0 && node < nodeData.length && offset >= 0)
                        nodeData[node].offsetOverride = offset;
                }
            }
        }

        setPoints();
    }

    @Override
    public java.util.Map<String, Object> getJsonState() {
        java.util.Map<String, Object> state = super.getJsonState();
        for (int i = 0; i < coilCount; i++)
            state.put("coilCurrent" + i, windings[i].current);
        return state;
    }

    @Override
    public void applyJsonState(java.util.Map<String, Object> state) {
        super.applyJsonState(state);
        for (int i = 0; i < coilCount; i++) {
            if (state.containsKey("coilCurrent" + i))
                windings[i].current = ((Number) state.get("coilCurrent" + i)).doubleValue();
        }
    }
}
