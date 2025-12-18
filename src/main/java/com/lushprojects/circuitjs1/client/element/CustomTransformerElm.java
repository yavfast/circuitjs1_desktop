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
    double coilCurrents[], coilInductances[], coilCurCounts[], coilCurSourceValues[], coilPolarities[], coilTurns[];
    double nodeCurrents[], nodeCurCounts[];
    public static final int FLAG_FLIP = 1;
    int flip;

    // node number n of first node of each coil (second node = n+1)
    int coilNodes[];

    int coilCount, nodeCount;

    // number of primary coils
    int primaryCoils;

    Point nodePoints[], nodeTaps[], ptCore[];
    String description;
    double inductance, couplingCoef;
    boolean needDots;

    Point dots[];
    int width;

    private static final int NODE_GAP = 16;

    // Nodes that were created by a "+" tap connection in the description.
    boolean[] isTapNode;
    int[] tapNodeIndices;

    // Nodes that can be dragged (tap handles). Includes tap nodes from '+' and
    // independent primary-side nodes (left side) except the two extremes.
    int[] movableNodeIndices;

    // Optional per-node position override along the winding stack direction.
    // Value is in the same units as the internal offset (pixels from the inner edge).
    int[] nodeOffsetOverride;
    double[] nodeOffsetComputed;

    private int minCoilWidth() {
        // Match TappedTransformerElm philosophy: allow smaller spacing than the old grid*4.
        // Keep a sensible floor so coils remain visible.
        return max(16, circuitEditor().gridSize * 2);
    }

    private int minLen() {
        return 32;
    }

    private int minTapSeg() {
        return circuitEditor().gridSize * 2;
    }

    private void rebuildTapNodeIndices() {
        if (isTapNode == null) {
            tapNodeIndices = new int[0];
            return;
        }
        int cnt = 0;
        for (int i = 0; i < isTapNode.length; i++)
            if (isTapNode[i])
                cnt++;
        tapNodeIndices = new int[cnt];
        int k = 0;
        for (int i = 0; i < isTapNode.length; i++)
            if (isTapNode[i])
                tapNodeIndices[k++] = i;
    }

    private void rebuildMovableNodeIndices() {
        int primaryNodes = getPrimaryNodes();
        if (nodeCount <= 0) {
            movableNodeIndices = new int[0];
            return;
        }

        int cnt = 0;
        for (int i = 0; i < nodeCount; i++) {
            boolean movable = (isTapNode != null && i < isTapNode.length && isTapNode[i]);
            // Independent taps on the left (primary) side: allow dragging all internal
            // nodes except the first/last of the primary stack.
            if (!movable && i > 0 && i < primaryNodes - 1)
                movable = true;
            if (movable)
                cnt++;
        }

        movableNodeIndices = new int[cnt];
        int k = 0;
        for (int i = 0; i < nodeCount; i++) {
            boolean movable = (isTapNode != null && i < isTapNode.length && isTapNode[i]);
            if (!movable && i > 0 && i < primaryNodes - 1)
                movable = true;
            if (movable)
                movableNodeIndices[k++] = i;
        }
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
        return (primaryCoils == coilCount) ? nodeCount : coilNodes[primaryCoils];
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
        inductance = 4;
        width = 32;
        noDiagonal = true;
        couplingCoef = .999;
        description = "1,1:1";
        parseDescription(description);

        // Fixed nominal size on creation (no resize while adding).
        int grid = circuitEditor().gridSize;
        int nominalLen = grid * 8;
        int nominalCoilWidth = grid * 4;
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
        coilCount = parseInt(st.nextToken());
        int i;
        coilCurrents = new double[coilCount];
        for (i = 0; i != coilCount; i++)
            coilCurrents[i] = parseDouble(st.nextToken());
        noDiagonal = true;
        parseDescription(description);

        // Optional extra geometry fields (backward-compatible): tap overrides.
        // Format: <tapOverrideCount> [<nodeIndex> <offset> ...]
        if (st.hasMoreTokens()) {
            int cnt = parseInt(st.nextToken());
            if (cnt > 0) {
                if (nodeOffsetOverride == null || nodeOffsetOverride.length != nodeCount) {
                    nodeOffsetOverride = new int[nodeCount];
                    for (i = 0; i < nodeCount; i++)
                        nodeOffsetOverride[i] = -1;
                }
                for (i = 0; i < cnt && st.hasMoreTokens(); i++) {
                    int ni = parseInt(st.nextToken());
                    if (!st.hasMoreTokens())
                        break;
                    int off = parseInt(st.nextToken());
                    if (ni >= 0 && ni < nodeCount) {
                        nodeOffsetOverride[ni] = max(0, off);
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
        return 4 + (movableNodeIndices == null ? 0 : movableNodeIndices.length);
    }

    @Override
    public Point getHandlePoint(int n) {
        int x1 = Math.min(x, x2);
        int x3 = Math.max(x, x2);
        int y1 = Math.min(y, y2);
        int y3 = Math.max(y, y2);
        switch (n) {
            case 0:
                return new Point(x1, y1);
            case 1:
                return new Point(x3, y1);
            case 2:
                return new Point(x3, y3);
            case 3:
                return new Point(x1, y3);
            default:
                if (movableNodeIndices != null && nodePoints != null) {
                    int ti = n - 4;
                    if (ti >= 0 && ti < movableNodeIndices.length) {
                        int ni = movableNodeIndices[ti];
                        if (ni >= 0 && ni < nodeCount)
                            return nodePoints[ni];
                    }
                }
                return super.getHandlePoint(n);
        }
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

        // Tap handle(s): slide a movable node along the winding stack direction.
        if (n >= 4 && movableNodeIndices != null && nodePoints != null) {
            int ti = n - 4;
            if (ti >= 0 && ti < movableNodeIndices.length) {
                int ni = movableNodeIndices[ti];
                if (ni >= 0 && ni < nodeCount) {
                    if (nodeOffsetOverride == null || nodeOffsetOverride.length != nodeCount) {
                        nodeOffsetOverride = new int[nodeCount];
                        for (int j = 0; j < nodeCount; j++)
                            nodeOffsetOverride[j] = -1;
                    }

                    // For this element, point2.y is constrained to point1.y, so the stack direction
                    // is vertical. Convert desired screen Y to internal offset.
                    int desiredY = circuitEditor().snapGrid(nodePoints[ni].y + dy);
                    int baseY = point1.y;
                    double desiredHoff = baseY - desiredY;
                    double denom = flip;
                    if (denom == 0)
                        denom = 1;
                    int desiredOff = (int) Math.round(desiredHoff / denom);
                    nodeOffsetOverride[ni] = max(0, desiredOff);
                    setPoints();
                    return;
                }
            }
        }

        Point moved = getHandlePoint(n);
        if (moved == null) {
            return;
        }

        int opp;
        switch (n) {
            case 0:
                opp = 2;
                break;
            case 1:
                opp = 3;
                break;
            case 2:
                opp = 0;
                break;
            case 3:
                opp = 1;
                break;
            default:
                super.movePoint(n, dx, dy);
                return;
        }

        Point fixed = getHandlePoint(opp);
        if (fixed == null) {
            return;
        }

        int mx = circuitEditor().snapGrid(moved.x + dx);
        int my = circuitEditor().snapGrid(moved.y + dy);
        int fx = fixed.x;
        int fy = fixed.y;

        // Minimum overall thickness depends on how many coils/nodes are on each side.
        int minThickness = max(getPrimaryMaxOffset(minCoilWidth), getSecondaryMaxOffset(minCoilWidth));

        // Clamp length and spacing; keep rectangle ordered (x <= x2, y <= y2).
        if (n == 0 || n == 3) {
            if (fx - mx < minLen)
                mx = fx - minLen;
        } else {
            if (mx - fx < minLen)
                mx = fx + minLen;
        }
        if (n == 0 || n == 1) {
            if (fy - my < minThickness)
                my = fy - minThickness;
        } else {
            if (my - fy < minThickness)
                my = fy + minThickness;
        }

        int nx1 = (n == 0 || n == 3) ? mx : fx;
        int nx2 = (n == 0 || n == 3) ? fx : mx;
        int ny1 = (n == 0 || n == 1) ? my : fy;
        int ny2 = (n == 0 || n == 1) ? fy : my;

        x = nx1;
        x2 = nx2;

        // Baseline is on the "inner" edge; which edge that is depends on flip.
        // flip=1 draws outward towards negative y; flip=-1 draws outward towards positive y.
        y = (flip == 1) ? ny2 : ny1;

        int targetThickness = abs(ny2 - ny1);
        width = max(minCoilWidth, requiredCoilWidthForThickness(targetThickness));
        setPoints();
    }

    int getDumpType() {
        return 406;
    }

    public String dump() {
        String s = dumpValues(super.dump(), inductance, couplingCoef, escape(description), coilCount) + " ";
        for (int i = 0; i < coilCount; i++) {
            s += dumpValue(coilCurrents[i]) + " "; // TODO:
        }

        // Optional tap overrides (backward-compatible): only write if any are set.
        int cnt = 0;
        if (nodeOffsetOverride != null) {
            for (int i = 0; i < nodeCount; i++)
                if (nodeOffsetOverride[i] >= 0)
                    cnt++;
        }
        if (cnt > 0) {
            s += dumpValue(cnt) + " ";
            for (int i = 0; i < nodeCount; i++) {
                if (nodeOffsetOverride[i] >= 0) {
                    s += dumpValue(i) + " " + dumpValue(nodeOffsetOverride[i]) + " ";
                }
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

        // count coils/nodes
        coilCount = nodeCount = 0;
        while (st.hasMoreTokens()) {
            String s = st.nextToken();
            if (s == "+")
                nodeCount--;
            if (s == "," || s == "+" || s == ":")
                continue;
            nodeCount += 2;
            coilCount++;
        }

        coilNodes = new int[coilCount];
        coilInductances = new double[coilCount];
        coilTurns = new double[coilCount];
        // save coil currents if possible (needed for undumping)
        if (coilCurrents == null || coilCurrents.length != coilCount)
            coilCurrents = new double[coilCount];
        coilCurCounts = new double[coilCount];
        coilCurSourceValues = new double[coilCount];
        coilPolarities = new double[coilCount];
        nodePoints = newPointArray(nodeCount);
        nodeTaps = newPointArray(nodeCount);
        nodeCurrents = new double[nodeCount];
        nodeCurCounts = new double[nodeCount];

        isTapNode = new boolean[nodeCount];
        nodeOffsetOverride = new int[nodeCount];
        nodeOffsetComputed = new double[nodeCount];
        for (int ii = 0; ii < nodeCount; ii++)
            nodeOffsetOverride[ii] = -1;

        // start over
        st = new StringTokenizer(desc, ",:+", true);
        int nodeNum = 0;
        int coilNum = 0;
        primaryCoils = 0;
        boolean secondary = false;
        needDots = false;
        while (true) {
            String tok = st.nextToken();
            double n = 0;
            try {
                n = Double.parseDouble(tok);
            } catch (Exception e) {
                return false;
            }
            if (n == 0)
                return false;
            // create new coil
            coilNodes[coilNum] = nodeNum;
            coilTurns[coilNum] = Math.abs(n);
            coilInductances[coilNum] = n * n * inductance;
            coilPolarities[coilNum] = 1;
            if (n < 0) {
                coilPolarities[coilNum] = -1;
                needDots = true;
            }
            nodeNum += 2;
            coilNum++;
            if (!secondary)
                primaryCoils = coilNum;
            if (!st.hasMoreTokens())
                break;
            tok = st.nextToken();
            if (tok == ",")
                continue;
            if (tok == "+") {
                nodeNum--;
                if (nodeNum >= 0 && nodeNum < nodeCount)
                    isTapNode[nodeNum] = true;
                continue;
            }
            if (tok == ":") {
                // switch to secondary
                if (secondary)
                    return false;
                secondary = true;
                continue;
            }
            return false;
        }
        allocNodes();
        rebuildTapNodeIndices();
        rebuildMovableNodeIndices();
        setPoints();
        xformMatrix = null;
        return true;
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
            setVoltageColor(g, volts[i]);
            drawThickLine(g, nodePoints[i], nodeTaps[i]);
        }

        // draw coils
        for (i = 0; i != coilCount; i++) {
            int n = coilNodes[i];
            setVoltageColor(g, volts[n]);
            setPowerColor(g, coilCurrents[i] * (volts[n] - volts[n + 1]));

            // Make the coil "bulge" face the core (inward), independent of winding order/flip.
            Point a = nodeTaps[n];
            Point b = nodeTaps[n + 1];
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

            drawCoil(g, hs, a, b, volts[n], volts[n + 1]);
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
            int n = coilNodes[i];
            String label = shortFormat(coilTurns[i]) + "T";
            Point a = nodeTaps[n];
            Point b = nodeTaps[n + 1];
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
            coilCurCounts[i] = updateDotCount(coilCurrents[i], coilCurCounts[i]);
            int ni = coilNodes[i];
            drawDots(g, nodeTaps[ni], nodeTaps[ni + 1], coilCurCounts[i]);
        }

        // draw tap currents
        for (i = 0; i != nodeCount; i++) {
            nodeCurCounts[i] = updateDotCount(nodeCurrents[i], nodeCurCounts[i]);
            drawDots(g, nodePoints[i], nodeTaps[i], nodeCurCounts[i]);
        }

        drawPosts(g);
        setBbox(nodePoints[0], nodePoints[nodeCount - 1], 0);
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
        int primaryNodes = (primaryCoils == coilCount) ? nodeCount : coilNodes[primaryCoils];
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
                nodeOffsetComputed[i] = offset;
                maxWidth = Math.max(maxWidth, offset);
                int nn = c < coilCount ? coilNodes[c] : -1;
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
                boolean movable = (isTapNode != null && i < isTapNode.length && isTapNode[i]);
                if (!movable && i >= primaryInternalStart && i < primaryInternalEnd)
                    movable = true;

                if (movable && nodeOffsetOverride != null && nodeOffsetOverride[i] >= 0)
                    nodeOffsetComputed[i] = nodeOffsetOverride[i];

                if (i > 0 && movable)
                    nodeOffsetComputed[i] = Math.max(nodeOffsetComputed[i], nodeOffsetComputed[i - 1] + segMin);
            }
            // Backward pass
            for (i = primaryNodes - 2; i >= 0; i--) {
                boolean movable = (isTapNode != null && i < isTapNode.length && isTapNode[i]);
                if (!movable && i >= primaryInternalStart && i < primaryInternalEnd)
                    movable = true;
                if (movable)
                    nodeOffsetComputed[i] = Math.min(nodeOffsetComputed[i], nodeOffsetComputed[i + 1] - segMin);
            }
        }

        // Secondary stack
        if (primaryNodes < nodeCount) {
            for (i = primaryNodes; i < nodeCount; i++) {
                boolean movable = (isTapNode != null && i < isTapNode.length && isTapNode[i]);
                if (movable && nodeOffsetOverride != null && nodeOffsetOverride[i] >= 0)
                    nodeOffsetComputed[i] = nodeOffsetOverride[i];
                if (i > primaryNodes && movable)
                    nodeOffsetComputed[i] = Math.max(nodeOffsetComputed[i], nodeOffsetComputed[i - 1] + segMin);
            }
            for (i = nodeCount - 2; i >= primaryNodes; i--) {
                boolean movable = (isTapNode != null && i < isTapNode.length && isTapNode[i]);
                if (movable)
                    nodeOffsetComputed[i] = Math.min(nodeOffsetComputed[i], nodeOffsetComputed[i + 1] - segMin);
            }
        }

        // Pass 2: generate points using the final offsets.
        for (i = 0; i != nodeCount; i++) {
            double offset = nodeOffsetComputed[i];
            if (i == primaryNodes - 1 || i == nodeCount - 1)
                offset = maxWidth;
            if (offset < 0)
                offset = 0;
            if (offset > maxWidth)
                offset = maxWidth;

            // Make the perpendicular direction independent of whether the element was drawn
            // left-to-right or right-to-left (dsign captures that).
            double hoff = offset * flip * dsign;
            interpPoint(point1, point2, nodePoints[i], i < primaryNodes ? 0 : 1, hoff);
            interpPoint(point1, point2, nodeTaps[i], i < primaryNodes ? ce : 1 - ce, hoff);
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
                int n = coilNodes[i];
                dots[i] = interpPoint(nodeTaps[n], nodeTaps[n + 1], coilPolarities[i] > 0 ? dotp : 1 - dotp, i < primaryCoils ? -7 : 7);
            }
        } else
            dots = null;
    }

    public Point getPost(int n) {
        return nodePoints[n];
    }

    public int getPostCount() {
        return nodeCount;
    }

    public void reset() {
        int i;
        for (i = 0; i != coilCount; i++)
            coilCurrents[i] = coilCurSourceValues[i] = coilCurCounts[i] = 0;
        for (i = 0; i != nodeCount; i++)
            volts[i] = nodeCurrents[i] = nodeCurCounts[i] = 0;
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
            xformMatrix[i][i] = coilInductances[i];
        int j;
        // fill off-diagonal
        for (i = 0; i != coilCount; i++)
            for (j = 0; j != i; j++)
                xformMatrix[i][j] = xformMatrix[j][i] = couplingCoef * Math.sqrt(coilInductances[i] * coilInductances[j]) * coilPolarities[i] * coilPolarities[j];

        CircuitMath.invertMatrix(xformMatrix, coilCount);

        CircuitSimulator simulator = simulator();
        double ts = isTrapezoidal() ? simulator.timeStep / 2 : simulator.timeStep;
        for (i = 0; i != coilCount; i++)
            for (j = 0; j != coilCount; j++) {
                // multiply in dt/2 (or dt for backward euler)
                xformMatrix[i][j] *= ts;
                int ni = coilNodes[i];
                int nj = coilNodes[j];
                if (i == j)
                    simulator.stampConductance(nodes[ni], nodes[ni + 1], xformMatrix[i][i]);
                else
                    simulator.stampVCCurrentSource(nodes[ni], nodes[ni + 1], nodes[nj], nodes[nj + 1], xformMatrix[i][j]);
            }
        for (i = 0; i != nodeCount; i++)
            simulator.stampRightSide(nodes[i]);
    }

    public void startIteration() {
        int i;
        for (i = 0; i != coilCount; i++) {
            double val = coilCurrents[i];
            if (isTrapezoidal()) {
                int j;
                for (j = 0; j != coilCount; j++) {
                    int n = coilNodes[j];
                    double voltdiff = volts[n] - volts[n + 1];
                    val += voltdiff * xformMatrix[i][j];
                }
            }
            coilCurSourceValues[i] = val;
        }
    }

    public void doStep() {
        CircuitSimulator simulator = simulator();
        for (int i = 0; i != coilCount; i++) {
            int n = coilNodes[i];
            simulator.stampCurrentSource(nodes[n], nodes[n + 1], coilCurSourceValues[i]);
        }
    }

    void calculateCurrent() {
        int i;
        for (i = 0; i != nodeCount; i++)
            nodeCurrents[i] = 0;
        for (i = 0; i != coilCount; i++) {
            double val = coilCurSourceValues[i];
            if (xformMatrix != null) {
                int j;
                for (j = 0; j != coilCount; j++) {
                    int n = coilNodes[j];
                    double voltdiff = volts[n] - volts[n + 1];
                    val += voltdiff * xformMatrix[i][j];
                }
            }
            coilCurrents[i] = val;
            int ni = coilNodes[i];
            nodeCurrents[ni] += val;
            nodeCurrents[ni + 1] -= val;
        }
    }

    @Override
    public double getCurrentIntoNode(int n) {
        return -nodeCurrents[n];
    }

    public void getInfo(String arr[]) {
        arr[0] = "transformer (custom)";
        arr[1] = "L = " + getUnitText(inductance, "H");
        int i;
        for (i = 0; i != coilCount; i++) {
            if (2 + i * 2 >= arr.length)
                break;
            int ni = coilNodes[i];
            arr[2 + i * 2] = "Vd" + (i + 1) + " = " + getVoltageText(volts[ni] - volts[ni + 1]);
            arr[3 + i * 2] = "I" + (i + 1) + " = " + getCurrentText(coilCurrents[i]);
        }
    }

    public boolean getConnection(int n1, int n2) {
        int i;
        for (i = 0; i != coilCount; i++)
            if (comparePair(n1, n2, coilNodes[i], coilNodes[i] + 1))
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
            int[] saved = nodeOffsetOverride;
            parseDescription();
            // Preserve any dragged node offsets when the description didn't change.
            if (saved != null && nodeOffsetOverride != null) {
                int lim = Math.min(saved.length, nodeOffsetOverride.length);
                for (int i = 0; i < lim; i++)
                    nodeOffsetOverride[i] = saved[i];
                setPoints();
            }
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
            int[] saved = nodeOffsetOverride;
            parseDescription();
            if (saved != null && nodeOffsetOverride != null) {
                int lim = Math.min(saved.length, nodeOffsetOverride.length);
                for (int i = 0; i < lim; i++)
                    nodeOffsetOverride[i] = saved[i];
                setPoints();
            }
        }
        if (n == 3) {
            if (ei.checkbox.getState())
                flags &= ~Inductor.FLAG_BACK_EULER;
            else
                flags |= Inductor.FLAG_BACK_EULER;
            int[] saved = nodeOffsetOverride;
            parseDescription();
            if (saved != null && nodeOffsetOverride != null) {
                int lim = Math.min(saved.length, nodeOffsetOverride.length);
                for (int i = 0; i < lim; i++)
                    nodeOffsetOverride[i] = saved[i];
                setPoints();
            }
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
        if (nodeOffsetOverride != null) {
            java.util.List<java.util.Map<String, Object>> tapOffsets = new java.util.ArrayList<>();
            for (int i = 0; i < nodeOffsetOverride.length; i++) {
                if (nodeOffsetOverride[i] >= 0) {
                    java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
                    entry.put("node", i);
                    entry.put("offset", nodeOffsetOverride[i]);
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
            if (nodeOffsetOverride == null || nodeOffsetOverride.length != nodeCount) {
                nodeOffsetOverride = new int[nodeCount];
                for (int i = 0; i < nodeCount; i++)
                    nodeOffsetOverride[i] = -1;
            }
            for (Object o : list) {
                if (o instanceof java.util.Map) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> m = (java.util.Map<String, Object>) o;
                    int node = getJsonInt(m, "node", -1);
                    int offset = getJsonInt(m, "offset", -1);
                    if (node >= 0 && node < nodeOffsetOverride.length && offset >= 0)
                        nodeOffsetOverride[node] = offset;
                }
            }
        }

        setPoints();
    }

    @Override
    public java.util.Map<String, Object> getJsonState() {
        java.util.Map<String, Object> state = super.getJsonState();
        for (int i = 0; i < coilCount; i++)
            state.put("coilCurrent" + i, coilCurrents[i]);
        return state;
    }

    @Override
    public void applyJsonState(java.util.Map<String, Object> state) {
        super.applyJsonState(state);
        for (int i = 0; i < coilCount; i++) {
            if (state.containsKey("coilCurrent" + i))
                coilCurrents[i] = ((Number) state.get("coilCurrent" + i)).doubleValue();
        }
    }
}
