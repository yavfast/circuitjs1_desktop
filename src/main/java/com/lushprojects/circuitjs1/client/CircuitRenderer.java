package com.lushprojects.circuitjs1.client;

import com.google.gwt.canvas.client.Canvas;
import com.google.gwt.canvas.dom.client.Context2d;
import com.google.gwt.core.client.Duration;
import com.google.gwt.core.client.Scheduler;
import com.lushprojects.circuitjs1.client.element.CapacitorElm;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.InductorElm;
import com.lushprojects.circuitjs1.client.element.ResistorElm;
import com.lushprojects.circuitjs1.client.util.Locale;
import com.lushprojects.circuitjs1.client.util.PerfMonitor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CircuitRenderer extends BaseCirSimDelegate {

    private Canvas canvas;
    private Context2d canvasContext;

    // canvas width/height in px (before device pixel ratio scaling)
    public int canvasWidth, canvasHeight;

    public boolean needsAnalysis;

    private long lastTimeMillis = 0;
    private long lastFrameTimeMillis;
    private long lastSecondTimeMillis = 0;
    private int frameCount = 0;
    private int framesPerSecond = 0;
    private int stepsPerSecond = 0;

    private Font unitsFont;

    // Per-renderer (per-CirSim) draw scaling factors. These used to be static on CircuitElm.
    // Keeping them here avoids global state when multiple CirSim instances exist.
    private double currentMult = 0.0;
    private double powerMult = 0.0;

    public double getCurrentMult() {
        return currentMult;
    }

    public double getPowerMult() {
        return powerMult;
    }

    public Font getUnitsFont() {
        if (unitsFont == null) {
            unitsFont = new Font("SansSerif", 0, 12);
        }
        return unitsFont;
    }

    public void setUnitsFont(Font unitsFont) {
        this.unitsFont = unitsFont;
    }

    int hintType = -1, hintItem1, hintItem2;

    // Public getters/setters for hint fields (used by export/import)
    public int getHintType() { return hintType; }
    public void setHintType(int type) { hintType = type; }
    public int getHintItem1() { return hintItem1; }
    public void setHintItem1(int item) { hintItem1 = item; }
    public int getHintItem2() { return hintItem2; }
    public void setHintItem2(int item) { hintItem2 = item; }

    public double[] transform = new double[6];
    Rectangle circuitArea;

    double scopeHeightFraction = 0.2;

    public CircuitRenderer(BaseCirSim cirSim) {
        super(cirSim);
    }

    public void needsAnalysis() {
        needsAnalysis = true;
    }

    public void reset() {
        needsAnalysis = false;
    }

    public long getLastFrameTime() {
        return lastFrameTimeMillis;
    }

    public Canvas initCanvas() {
        if (canvas == null) {
            canvas = Canvas.createIfSupported();
            if (canvas != null) {
                canvasContext = canvas.getContext2d();
            }
        }
        return canvas;
    }

    void setCanvasSize(int width, int height) {
        if (canvas != null) {
            canvas.setWidth(width + "px");
            canvas.setHeight(height + "px");
            canvasWidth = width;
            canvasHeight = height;
            float scale = CirSim.devicePixelRatio();
            canvas.setCoordinateSpaceWidth((int) (width * scale));
            canvas.setCoordinateSpaceHeight((int) (height * scale));
        }
    }

    public Canvas getCanvas() {
        return canvas;
    }

    void checkCanvasSize() {
        if (canvas.getCoordinateSpaceWidth() != (int) (canvasWidth * CirSim.devicePixelRatio())) {
            cirSim.setCanvasSize(0, 0);
        }
    }

    void setCircuitArea() {
        int height = canvasHeight;
        int width = canvasWidth;
        int scopesHeight = (int) ((double) height * scopeHeightFraction);
        if (scopeManager().scopeCount == 0) {
            scopesHeight = 0;
        }
        circuitArea = new Rectangle(0, 0, width, height - scopesHeight);
    }

    void zoomCircuit(double zoomIncrement) {
        zoomCircuit(zoomIncrement, false);
    }

    void zoomCircuit(double zoomIncrement, boolean fromMenu) {
        double oldScale = transform[0];
        double zoomFactor = zoomIncrement * 0.01;
        double newScale = Math.max(oldScale + zoomFactor, 0.2);
        newScale = Math.min(newScale, 2.5);
        setCircuitScale(newScale, fromMenu);
    }

    void setCircuitScale(double newScale, boolean fromMenu) {
        int zoomCenterX = !fromMenu ? circuitEditor().mouseCursorX : circuitArea.width / 2;
        int zoomCenterY = !fromMenu ? circuitEditor().mouseCursorY : circuitArea.height / 2;
        int gridX = inverseTransformX(zoomCenterX);
        int gridY = inverseTransformY(zoomCenterY);
        transform[0] = transform[3] = newScale;

        // adjust translation to keep center of screen constant
        // inverse transform = (x - t4) / t0
        transform[4] = zoomCenterX - gridX * newScale;
        transform[5] = zoomCenterY - gridY * newScale;
    }

    // convert screen coordinates to grid coordinates by inverting circuit transform
    int inverseTransformX(double x) {
        return (int) ((x - transform[4]) / transform[0]);
    }

    int inverseTransformY(double y) {
        return (int) ((y - transform[5]) / transform[3]);
    }

    // convert grid coordinates to screen coordinates
    public int transformX(double x) {
        return (int) ((x * transform[0]) + transform[4]);
    }

    public int transformY(double y) {
        return (int) ((y * transform[3]) + transform[5]);
    }

    private boolean needsRepaint = false;

    // Timer removed, simulation loop is now in CircuitDocument

    void repaint() {
        if (!needsRepaint) {
            needsRepaint = true;
            Scheduler.get().scheduleDeferred(() -> {
                render();
                needsRepaint = false;
            });
        }
    }

    private final PerfMonitor perfmon = new PerfMonitor();

    public void resetTimers() {
        lastTimeMillis = 0;
        lastSecondTimeMillis = 0;
        frameCount = 0;
        framesPerSecond = 0;
        stepsPerSecond = 0;
    }

    public void render() {
        perfmon.reset();
        perfmon.startContext("render()");

        checkCanvasSize();

        CircuitSimulator simulator = simulator();

        // Simulation logic moved to CircuitDocument

        if (simulator.stopElm != null && simulator.stopElm != circuitEditor().mouseElm) {
            // simulator().stopElm.setMouseElm(true);
        }

        scopeManager().setupScopes();

        Graphics graphics = new Graphics(canvasContext);
        setupFrame(graphics);

        // Simulation run logic moved to CircuitDocument

        updateSimulationTimers(simulator);

        perfmon.startContext("graphics");
        drawCircuit(graphics, simulator);
        perfmon.stopContext(); // graphics

        if (simulator.stopElm != null && simulator.stopElm != circuitEditor().mouseElm) {
            // simulator.stopElm.setMouseElm(false);
        }

        frameCount++;

        lastFrameTimeMillis = lastTimeMillis;
        perfmon.stopContext(); // render

        if (circuitInfo().developerMode) {
            drawDeveloperInfo(graphics, perfmon);
        }

        if (cirSim.menuManager.mouseModeCheckItem.getState()) {
            drawMouseMode(graphics);
        }

        CirSim cirSim = (CirSim) this.cirSim;
        cirSim.callUpdateHook();
    }

    private void setupFrame(Graphics graphics) {
        ColorSettings cs = ColorSettings.get();
        if (cirSim.menuManager.printableCheckItem.getState()) {
            cs.setPrintable(true);
            graphics.setColor(Color.white);
            canvas.getElement().getStyle().setBackgroundColor("#fff");
        } else {
            cs.setPrintable(false);
            graphics.setColor(Color.black);
            canvas.getElement().getStyle().setBackgroundColor("#000");
        }
        graphics.fillRect(0, 0, canvasWidth, canvasHeight);
    }

    private void updateSimulationTimers(CircuitSimulator simulator) {
        CirSim cirSim = (CirSim) this.cirSim;
        long sysTime = System.currentTimeMillis();
        if (simulator.simRunning) {
            if (lastTimeMillis != 0) {
                int timeDelta = (int) (sysTime - lastTimeMillis);
                double currentSpeed = cirSim.currentBar.getValue();
                currentSpeed = java.lang.Math.exp(currentSpeed / 3.5 - 14.2);
                currentMult = 1.7 * timeDelta * currentSpeed;
                if (!cirSim.menuManager.conventionCheckItem.getState()) {
                    currentMult = -currentMult;
                }
            }
            lastTimeMillis = sysTime;
        } else {
            lastTimeMillis = 0;
        }

        if (sysTime - lastSecondTimeMillis >= 1000) {
            framesPerSecond = frameCount;
            stepsPerSecond = simulator.steps;
            frameCount = 0;
            simulator.steps = 0;
            lastSecondTimeMillis = sysTime;
        }

        powerMult = Math.exp(cirSim.powerBar.getValue() / 4.762 - 7);
    }

    private void drawCircuit(Graphics graphics, CircuitSimulator simulator) {
        graphics.setFont(getUnitsFont());
        graphics.setLineCap(Context2d.LineCap.ROUND);

        if (cirSim.menuManager.noEditCheckItem.getState()) {
            graphics.drawLock(20, 30);
        }

        graphics.setColor(Color.white);

        double scale = CirSim.devicePixelRatio();
        canvasContext.setTransform(transform[0] * scale, 0, 0, transform[3] * scale, transform[4] * scale,
                transform[5] * scale);

        drawElements(graphics, simulator);
        drawHandles(graphics);
        drawBadConnections(graphics, simulator);
        drawSelectionAndCursor(graphics);

        canvasContext.setTransform(scale, 0, 0, scale, 0, 0);

        drawBottomArea(graphics);
    }

    private void drawElements(Graphics graphics, CircuitSimulator simulator) {
        perfmon.startContext("elm.draw()");
        for (CircuitElm ce : simulator.elmList) {
            if (cirSim.menuManager.powerCheckItem.getState()) {
                graphics.setColor(Color.gray);
            }
            boolean isStopErrorElm = simulator.stopMessage != null && simulator.stopElm == ce;
            if (isStopErrorElm) {
                graphics.pushForcedColor(Color.red);
            }
            ce.draw(graphics);
            if (isStopErrorElm) {
                graphics.popForcedColor();
            }
        }
        perfmon.stopContext();

        CircuitEditor circuitEditor = circuitEditor();
        if (circuitEditor.mouseMode != MouseMode.DRAG_ROW && circuitEditor.mouseMode != MouseMode.DRAG_COLUMN) {
            for (Point pt : simulator.postDrawList) {
                // Find voltage at this point from any element connected to it
                double voltage = 0;
                for (CircuitElm ce : simulator.elmList) {
                    int posts = ce.getPostCount();
                    for (int j = 0; j < posts; j++) {
                        if (ce.getPost(j).equals(pt)) {
                            voltage = ce.getPostVoltage(j);
                            break;
                        }
                    }
                }
                graphics.setColor(ColorSettings.get().getVoltageColor(voltage));
                graphics.fillOval(pt.x - 3, pt.y - 3, 7, 7);
            }
        }

        if (circuitEditor.tempMouseMode == MouseMode.DRAG_ROW ||
                circuitEditor.tempMouseMode == MouseMode.DRAG_COLUMN ||
                circuitEditor.tempMouseMode == MouseMode.DRAG_POST ||
                circuitEditor.tempMouseMode == MouseMode.DRAG_SELECTED) {
            for (CircuitElm ce : simulator.elmList) {
                if (ce != circuitEditor.mouseElm || circuitEditor.tempMouseMode != MouseMode.DRAG_POST) {
                    graphics.setColor(Color.gray);
                    graphics.fillOval(ce.getX() - 3, ce.getY() - 3, 7, 7);
                    graphics.fillOval(ce.getX2() - 3, ce.getY2() - 3, 7, 7);
                } else {
                    ce.drawHandles(graphics, ColorSettings.get().getSelectColor());
                }
            }
        }
    }

    private void drawHandles(Graphics graphics) {
        CircuitEditor circuitEditor = circuitEditor();
        if (circuitEditor.tempMouseMode == MouseMode.SELECT && circuitEditor.mouseElm != null) {
            circuitEditor.mouseElm.drawHandles(graphics, ColorSettings.get().getSelectColor());
        }

        if (circuitEditor.dragElm != null && (circuitEditor.dragElm.getX() != circuitEditor.dragElm.getX2()
            || circuitEditor.dragElm.getY() != circuitEditor.dragElm.getY2())) {
            circuitEditor.dragElm.draw(graphics);
            circuitEditor.dragElm.drawHandles(graphics, ColorSettings.get().getSelectColor());
        }
    }

    private void drawBadConnections(Graphics graphics, CircuitSimulator simulator) {
        for (int i = 0; i != simulator.badConnectionList.size(); i++) {
            Point cn = simulator.badConnectionList.get(i);
            graphics.setColor(Color.red);
            graphics.fillOval(cn.x - 3, cn.y - 3, 7, 7);
        }
    }

    private void drawSelectionAndCursor(Graphics graphics) {
        CircuitEditor circuitEditor = circuitEditor();
        if (circuitEditor.selectedArea != null) {
            graphics.setColor(ColorSettings.get().getSelectColor());
            graphics.drawRect(circuitEditor.selectedArea.x, circuitEditor.selectedArea.y,
                    circuitEditor.selectedArea.width, circuitEditor.selectedArea.height);
        }

        if (cirSim.menuManager.crossHairCheckItem.getState() && circuitEditor.mouseCursorX >= 0
                && circuitEditor.mouseCursorX <= circuitArea.width
                && circuitEditor.mouseCursorY <= circuitArea.height) {
            graphics.setColor(Color.gray);
            int x = circuitEditor.snapGrid(inverseTransformX(circuitEditor.mouseCursorX));
            int y = circuitEditor.snapGrid(inverseTransformY(circuitEditor.mouseCursorY));
            graphics.drawLine(x, inverseTransformY(0), x, inverseTransformY(circuitArea.height));
            graphics.drawLine(inverseTransformX(0), y, inverseTransformX(circuitArea.width), y);
        }
    }

    private void drawDeveloperInfo(Graphics graphics, PerfMonitor perfmon) {
        int height = 45;
        int increment = 15;
        graphics.setColor(Color.white);
        graphics.drawString("Framerate: " + CircuitElm.showFormat(framesPerSecond), 10, height);
        graphics.drawString("Steprate: " + CircuitElm.showFormat(stepsPerSecond), 10, height += increment);
        graphics.drawString("Steprate/iter: " + CircuitElm.showFormat(stepsPerSecond / cirSim.getIterCount()), 10,
                height += increment);
        graphics.drawString("iterc: " + CircuitElm.showFormat(cirSim.getIterCount()), 10, height += increment);
        graphics.drawString("Frames: " + frameCount, 10, height += increment);

        height += (increment * 2);

        String perfmonResult = PerfMonitor.buildString(perfmon).toString();
        String[] splits = perfmonResult.split("\n");
        for (int x = 0; x < splits.length; x++) {
            graphics.drawString(splits[x], 10, height + (increment * x));
        }
    }

    private void drawMouseMode(Graphics graphics) {
        if (cirSim.menuManager.printableCheckItem.getState())
            graphics.setColor(Color.black);
        else
            graphics.setColor(Color.white);
        graphics.drawString(Locale.LS("Mode: ") + cirSim.menuManager.classToLabelMap.get(circuitEditor().mouseModeStr),
                10, 29);

        CircuitSimulator simulator = simulator();
        if (simulator.stopMessage != null && !simulator.stopMessage.isEmpty()) {
            // Show stop/error message near top-left where users expect it.
            // Use the existing select color (theme-consistent) to distinguish it from the mode label.
            graphics.setColor(ColorSettings.get().getSelectColor());
            graphics.drawString(simulator.stopMessage, 10, 44);
        } else if (simulator.warningMessage != null && !simulator.warningMessage.isEmpty()) {
            graphics.setColor(ColorSettings.get().getSelectColor());
            graphics.drawString(simulator.warningMessage, 10, 44);
        }
    }

    void drawBottomArea(Graphics g) {
        int infoBoxStartX = 0;
        int infoBoxHeight = 0;

        CircuitSimulator simulator = simulator();
        CircuitEditor circuitEditor = circuitEditor();
        ScopeManager scopeManager = scopeManager();

        if (simulator.stopMessage == null && scopeManager.scopeCount == 0) {
            infoBoxStartX = Math.max(canvasWidth - CirSim.INFO_WIDTH, 0);
            int h0 = (int) (canvasHeight * scopeHeightFraction);
            infoBoxHeight = (circuitEditor.mouseElm == null) ? 70 : h0;
            if (circuitInfo().hideInfoBox)
                infoBoxHeight = 0;
        }
        if (simulator.stopMessage != null && circuitArea.height > canvasHeight - 30)
            infoBoxHeight = 30;

        g.setColor(cirSim.menuManager.printableCheckItem.getState() ? "#eee" : "#111");
        g.fillRect(infoBoxStartX, circuitArea.height - infoBoxHeight, circuitArea.width,
                canvasHeight - circuitArea.height + infoBoxHeight);
        g.setFont(getUnitsFont());

        // Keep scopes visible even when the simulator is stopped (e.g. convergence failure).
        int currentScopeCount = scopeManager.scopeCount;

        Scope.clearCursorInfo();
        for (int i = 0; i < currentScopeCount; i++)
            scopeManager.scopes[i].selectScope(circuitEditor.mouseCursorX, circuitEditor.mouseCursorY);
        if (simulator.scopeElmArr != null)
            for (int i = 0; i < simulator.scopeElmArr.length; i++)
                simulator.scopeElmArr[i].selectScope(circuitEditor.mouseCursorX, circuitEditor.mouseCursorY);

        for (int i = 0; i < currentScopeCount; i++)
            scopeManager.scopes[i].draw(g);

        if (circuitEditor.mouseWasOverSplitter) {
            g.setColor(ColorSettings.get().getSelectColor());
            g.setLineWidth(4.0);
            g.drawLine(0, circuitArea.height - 2, circuitArea.width, circuitArea.height - 2);
            g.setLineWidth(1.0);
        }
        g.setColor(ColorSettings.get().getBackgroundColor());

        if (!circuitInfo().hideInfoBox) {
            drawInfoBox(g, infoBoxStartX, currentScopeCount);
        }
    }

    private void drawInfoBox(Graphics graphics, int leftX, int scopeCount) {
        CircuitSimulator simulator = simulator();
        CircuitEditor circuitEditor = circuitEditor();
        String[] infoLines = new String[10];

        CircuitElm mouseElm = circuitEditor.mouseElm;
        if (mouseElm != null) {
            int mousePost = circuitEditor.mousePost;
            if (mousePost == -1) {
                mouseElm.getInfo(infoLines);
                // Add element ID to the header
                String id = mouseElm.hasElementId() ? mouseElm.getElementId() : null; // no ID creation from draw code
                if (id != null && !id.isEmpty()) {
                    infoLines[0] = "[" + id + "] " + Locale.LS(infoLines[0]);
                } else {
                    infoLines[0] = Locale.LS(infoLines[0]);
                }
                if (infoLines[1] != null) {
                    infoLines[1] = Locale.LS(infoLines[1]);
                }
            } else {
                infoLines[0] = "V = " + CircuitElm.getUnitText(mouseElm.getPostVoltage(mousePost), "V");
            }
        } else {
            infoLines[0] = "t = " + CircuitElm.getTimeText(simulator().t);
            double timeRate = 160 * cirSim.getIterCount() * simulator.timeStep;
            if (timeRate >= .1) {
                infoLines[0] += " (" + CircuitElm.showFormat(timeRate) + "x)";
            }
            infoLines[1] = Locale.LS("time step = ") + CircuitElm.getTimeText(simulator.timeStep);
        }

        int lineIdx = 0;
        while (infoLines[lineIdx] != null) {
            lineIdx++;
        }

        if (hintType != -1) {
            String hint = getHint();
            if (hint == null) {
                hintType = -1;
            } else {
                infoLines[lineIdx++] = hint;
            }
        }

        int badNodes = simulator.badConnectionList.size();
        if (badNodes > 0) {
            infoLines[lineIdx++] = badNodes
                    + ((badNodes == 1) ? Locale.LS(" bad connection") : Locale.LS(" bad connections"));
        }
        int x = leftX + 5;
        if (scopeCount != 0) {
            x = scopeManager().scopes[scopeCount - 1].rightEdge() + 20;
        }

        // When no scopes, info box is drawn at bottom-right with fixed height
        // Calculate yBase so text appears inside the info box area
        int yBase;
        if (scopeCount == 0) {
            // Info box starts at canvasHeight - infoBoxHeight, where infoBoxHeight is approximately 20% of canvas
            // We need to draw text starting from there
            int h0 = (int) (canvasHeight * scopeHeightFraction);
            int infoBoxHeight = (circuitEditor.mouseElm == null) ? 70 : h0;
            yBase = canvasHeight - infoBoxHeight;
        } else {
            yBase = circuitArea.height;
        }
        graphics.setColor(ColorSettings.get().getForegroundColor());
        for (lineIdx = 0; infoLines[lineIdx] != null; lineIdx++) {
            graphics.drawString(infoLines[lineIdx], x, yBase + 15 * (lineIdx + 1));
        }
    }

    String getHint() {
        CircuitElm c1 = simulator().getElm(hintItem1);
        CircuitElm c2 = simulator().getElm(hintItem2);
        if (c1 == null || c2 == null) {
            return null;
        }

        switch (hintType) {
            case CircuitConst.HINT_LC: {
                if (!(c1 instanceof InductorElm) || !(c2 instanceof CapacitorElm))
                    return null;
                InductorElm ie = (InductorElm) c1;
                CapacitorElm ce = (CapacitorElm) c2;
                return Locale.LS("res.f = ") + CircuitElm.getUnitText(1 / (2 * Math.PI * Math.sqrt(ie.inductance *
                        ce.capacitance)), "Hz");
            }
            case CircuitConst.HINT_RC: {
                if (!(c1 instanceof ResistorElm) || !(c2 instanceof CapacitorElm))
                    return null;
                ResistorElm re = (ResistorElm) c1;
                CapacitorElm ce = (CapacitorElm) c2;
                return "RC = " + CircuitElm.getUnitText(re.resistance * ce.capacitance,
                        "s");
            }
            case CircuitConst.HINT_3DB_C: {
                if (!(c1 instanceof ResistorElm) || !(c2 instanceof CapacitorElm))
                    return null;
                ResistorElm re = (ResistorElm) c1;
                CapacitorElm ce = (CapacitorElm) c2;
                return Locale.LS("f.3db = ") +
                        CircuitElm.getUnitText(1 / (2 * Math.PI * re.resistance * ce.capacitance), "Hz");
            }
            case CircuitConst.HINT_3DB_L: {
                if (!(c1 instanceof ResistorElm) || !(c2 instanceof InductorElm))
                    return null;
                ResistorElm re = (ResistorElm) c1;
                InductorElm ie = (InductorElm) c2;
                return Locale.LS("f.3db = ") +
                        CircuitElm.getUnitText(re.resistance / (2 * Math.PI * ie.inductance), "Hz");
            }
            case CircuitConst.HINT_TWINT: {
                if (!(c1 instanceof ResistorElm) || !(c2 instanceof CapacitorElm))
                    return null;
                ResistorElm re = (ResistorElm) c1;
                CapacitorElm ce = (CapacitorElm) c2;
                return Locale.LS("fc = ") +
                        CircuitElm.getUnitText(1 / (2 * Math.PI * re.resistance * ce.capacitance), "Hz");
            }
            default:
                return null;
        }
    }

    public void centreCircuit() {
        if (simulator().elmList == null) // avoid exception if called during initialization
            return;

        Rectangle bounds = getCircuitBounds();
        setCircuitArea();

        double scale = 1.0;
        int effectiveCircuitHeight = circuitArea.height;

        // If there's no scope and the window isn't very wide, don't use the full
        // circuit area for centering.
        if (scopeManager().scopeCount == 0 && circuitArea.width < 800) {
            effectiveCircuitHeight -= (int) ((double) effectiveCircuitHeight * scopeHeightFraction);
        }

        if (bounds != null) {
            // Add some space on edges because bounds calculation is not perfect
            scale = Math.min(circuitArea.width / (double) (bounds.width + 140),
                    effectiveCircuitHeight / (double) (bounds.height + 100));
        }
        scale = Math.min(scale, 1.5); // Limit scale for large windows

        // Calculate transform to fill most of the screen
        transform[0] = transform[3] = scale;
        transform[1] = transform[2] = 0;
        if (bounds != null) {
            transform[4] = (circuitArea.width - bounds.width * scale) / 2 - bounds.x * scale;
            transform[5] = (effectiveCircuitHeight - bounds.height * scale) / 2 - bounds.y * scale;
        } else {
            transform[4] = transform[5] = 0;
        }
    }

    Rectangle getCircuitBounds() {
        int minx = 30000, maxx = -30000, miny = 30000, maxy = -30000;
        CircuitSimulator simulator = simulator();
        if (simulator.elmList.isEmpty()) {
            return null;
        }

        for (CircuitElm ce : simulator.elmList) {
            // Centered text causes problems when trying to center the circuit, so we
            // special-case it here
            if (!ce.isCenteredText()) {
                minx = Math.min(ce.getX(), Math.min(ce.getX2(), minx));
                maxx = Math.max(ce.getX(), Math.max(ce.getX2(), maxx));
            }
            miny = Math.min(ce.getY(), Math.min(ce.getY2(), miny));
            maxy = Math.max(ce.getY(), Math.max(ce.getY2(), maxy));
        }

        if (minx > maxx) // No elements found with bounds
            return null;

        return new Rectangle(minx, miny, maxx - minx, maxy - miny);
    }

    void drawCircuitInContext(Context2d context, int type, Rectangle bounds, int w, int h) {
        Graphics graphics = new Graphics(context);
        graphics.setTransform(1, 0, 0, 1, 0, 0);
        double[] oldTransform = Arrays.copyOf(transform, 6);

        // Save original settings
        boolean originalPrintableState = cirSim.menuManager.printableCheckItem.getState();
        boolean originalDotsState = cirSim.menuManager.dotsCheckItem.getState();

        try {
            double scale = 1.0;
            boolean isPrint = (type == CirSim.CAC_PRINT);
            if (isPrint) {
                cirSim.menuManager.printableCheckItem.setState(true);
            }

            ColorSettings cs = ColorSettings.get();
            if (cirSim.menuManager.printableCheckItem.getState()) {
                cs.setPrintable(true);
                graphics.setColor(Color.white);
            } else {
                cs.setPrintable(false);
                graphics.setColor(Color.black);
            }
            graphics.fillRect(0, 0, w, h);
            cirSim.menuManager.dotsCheckItem.setState(false);

            int widthMargin = 140;
            int heightMargin = 100;
            scale = Math.min(w / (double) (bounds.width + widthMargin), h / (double) (bounds.height + heightMargin));

            // ScopeElms need the transform array to be updated
            transform[0] = transform[3] = scale;
            transform[4] = -(bounds.x - widthMargin / 2.0);
            transform[5] = -(bounds.y - heightMargin / 2.0);

            graphics.scale(scale, scale);
            graphics.translate(transform[4], transform[5]);
            graphics.setLineCap(Context2d.LineCap.ROUND);

            CircuitSimulator simulator = simulator();
            for (CircuitElm elm : simulator.elmList) {
                elm.draw(graphics);
            }
            for (Point post : simulator.postDrawList) {
                CircuitElm.drawPost(graphics, post);
            }

        } finally {
            // Restore everything
            cirSim.menuManager.printableCheckItem.setState(originalPrintableState);
            cirSim.menuManager.dotsCheckItem.setState(originalDotsState);
            transform = oldTransform;
        }
    }

    public Canvas getCircuitAsCanvas(int type) {
        Canvas exportCanvas = Canvas.createIfSupported();
        Rectangle bounds = getCircuitBounds();
        if (bounds == null)
            return exportCanvas; // Return empty canvas if no bounds

        int widthMargin = 140;
        int heightMargin = 100;
        int w = (bounds.width * 2 + widthMargin);
        int h = (bounds.height * 2 + heightMargin);
        exportCanvas.setCoordinateSpaceWidth(w);
        exportCanvas.setCoordinateSpaceHeight(h);

        Context2d context = exportCanvas.getContext2d();
        drawCircuitInContext(context, type, bounds, w, h);
        return exportCanvas;
    }

    public String getCircuitAsSVG() {
        Rectangle bounds = getCircuitBounds();
        if (bounds == null)
            return ""; // Return empty string if no bounds

        int widthMargin = 140;
        int heightMargin = 100;
        int w = (bounds.width + widthMargin);
        int h = (bounds.height + heightMargin);
        Context2d context = CirSim.createSVGContext(w, h);
        drawCircuitInContext(context, CirSim.CAC_SVG, bounds, w, h);
        return CirSim.getSerializedSVG(context);
    }

    // ---------------------------------------------------------------- offscreen image ([SP_AGA_02_08])

    /** Margin around the circuit bounds of an offscreen image, in circuit pixels (one grid cell). */
    public static final int OFFSCREEN_MARGIN = 16;
    /** Restarts of an offscreen image after its document's element list changed; then it completes. */
    private static final int OFFSCREEN_MAX_RESTARTS = 5;
    /** Largest offscreen image area in pixels ([SP_AGA_02_08]: 40 megapixels). */
    public static final long OFFSCREEN_MAX_AREA = 40000000L;
    /** Pixels of background filled per band (a large raster image is filled over several steps). */
    private static final int OFFSCREEN_FILL_BAND_PIXELS = 1000000;

    /** 1 x 1 canvas the measure pass draws into (bounding boxes only; session-scoped, reused). */
    private Context2d measureContext;

    /**
     * Starts an offscreen image of the bound document ({@link OffscreenImage}). Call it, and every
     * {@link OffscreenImage#step}, while the document is bound.
     *
     * @param svg     true: SVG through canvas2svg (the caller makes sure it is loaded)
     * @param maxSide largest width or height in pixels; a larger image (or one above
     *                {@link #OFFSCREEN_MAX_AREA}) is not drawn
     */
    public OffscreenImage startOffscreen(boolean svg, double scale, boolean includeScopes, int maxSide) {
        return new OffscreenImage(svg, scale, includeScopes, maxSide);
    }

    /**
     * [SP_AGA_02_08] An offscreen image of one document: its whole circuit — the circuit bounds
     * (element endpoints and bounding boxes) plus {@link #OFFSCREEN_MARGIN} on every side, times
     * {@code scale}, whatever the viewport — drawn into a detached canvas (PNG) or a canvas2svg
     * context (SVG). With {@code includeScopes} the document's scope panel follows below the
     * circuit, at the scopes' rectangles as last laid out (by the visible tab's frames, or by
     * {@code DocumentScope} after each operation on another document).
     * <p>
     * <b>Steps</b> ([SP_AGA_03_08] R1 slices), each until its deadline, checked between units:
     * <ol>
     * <li><b>Measure</b>: every element is drawn into a 1 x 1 scratch canvas, which settles the
     *     bounding boxes (text extents; a document never drawn has endpoint boxes only), so the
     *     image size is known before its canvas exists.</li>
     * <li><b>Allocate</b> the canvas once, in a step of its own (a large raster canvas costs one
     *     indivisible unit), then <b>fill</b> the background in bands.</li>
     * <li><b>Draw</b> the elements in list order, then the posts, bad connections and scopes.</li>
     * </ol>
     * When the element list is no longer the one the image started with, it measures again (at
     * most {@link #OFFSCREEN_MAX_RESTARTS} times) and keeps its canvas when the size is unchanged;
     * simulation progress between steps is drawn as found.
     * <p>
     * <b>Session state.</b> What a step reads or writes is restored exactly at its end, so the
     * visible tab does not change by a pixel: the view transform (values, not the array), the Show
     * Current item (images are drawn without current dots), the printable colour mode, the
     * current-dot multiplier (0 while drawing, so no element's dot position advances) and the live
     * draw state of every scope drawn (see {@code Scope.beginOffscreenDraw}).
     */
    public final class OffscreenImage {
        private final boolean svg;
        private final double scale;
        private final boolean includeScopes;
        private final int maxSide;

        public int width, height;
        private int circuitHeight;
        private List<CircuitElm> elms;
        private Rectangle area;
        private final List<Scope> scopes = new ArrayList<>();
        private Rectangle panel;
        private Canvas canvas;
        private Context2d context;
        private Graphics graphics;
        /** Next element of the measure pass; equal to the list size once it is complete. */
        private int measured;
        /** Next element to draw into the image. */
        private int next;
        /** Rows of the background filled so far. */
        private int filledRows;
        /** True once the posts, bad connections and scopes (drawn after the last element) are drawn. */
        private boolean tailDrawn;
        /** True once the image is sized (and its canvas allocated) after the measure pass. */
        private boolean laidOut;
        private int restarts;
        private boolean tooLarge;
        private boolean done;

        private OffscreenImage(boolean svg, double scale, boolean includeScopes, int maxSide) {
            this.svg = svg;
            this.scale = scale;
            this.includeScopes = includeScopes;
            this.maxSide = maxSide;
            startMeasure();
        }

        private void startMeasure() {
            elms = new ArrayList<>(simulator().elmList);
            measured = 0;
            next = 0;
            filledRows = 0;
            tailDrawn = false;
            laidOut = false;
        }

        /**
         * Sizes the image from the settled bounds and the scope panel and allocates its canvas.
         *
         * @return true when a canvas was allocated (not reused, not too large)
         */
        private boolean layout() {
            area = offscreenBounds();
            scopes.clear();
            panel = null;
            ScopeManager sm = scopeManager();
            if (includeScopes) {
                int x1 = Integer.MAX_VALUE, y1 = Integer.MAX_VALUE, x2 = Integer.MIN_VALUE, y2 = Integer.MIN_VALUE;
                for (int i = 0; i < sm.scopeCount; i++) {
                    Rectangle r = sm.scopes[i].rect;
                    if (r == null || r.width <= 1 || r.height <= 1) {
                        continue;
                    }
                    scopes.add(sm.scopes[i]);
                    x1 = Math.min(x1, r.x);
                    y1 = Math.min(y1, r.y);
                    x2 = Math.max(x2, r.x + r.width);
                    y2 = Math.max(y2, r.y + r.height);
                }
                if (!scopes.isEmpty()) {
                    panel = new Rectangle(x1, y1, x2 - x1, y2 - y1);
                }
            }
            int m = OFFSCREEN_MARGIN;
            int circW = area == null ? 2 * m : area.width + 2 * m;
            int circH = area == null ? 2 * m : area.height + 2 * m;
            int w = (int) Math.ceil(circW * scale);
            circuitHeight = (int) Math.ceil(circH * scale);
            int h = circuitHeight;
            if (panel != null) {
                w = Math.max(w, (int) Math.ceil(panel.width * scale));
                h += (int) Math.ceil(panel.height * scale);
            }
            if (w > maxSide || h > maxSide || (long) w * h > OFFSCREEN_MAX_AREA) {
                width = w;
                height = h;
                tooLarge = done = true;
                canvas = null;
                context = null;
                return false;
            }
            // a restart keeps a raster canvas of the same size (an SVG context is cheap to recreate
            // and would keep the elements drawn before)
            boolean reuse = !svg && context != null && w == width && h == height;
            width = w;
            height = h;
            filledRows = 0;
            if (reuse) {
                return false;
            }
            if (svg) {
                canvas = null;
                context = CirSim.createSVGContext(width, height);
            } else {
                canvas = Canvas.createIfSupported();
                canvas.setCoordinateSpaceWidth(width);
                canvas.setCoordinateSpaceHeight(height);
                context = canvas.getContext2d();
            }
            graphics = new Graphics(context);
            return true;
        }

        /** @return true when the image was not drawn because it exceeds the size or area limit */
        public boolean isTooLarge() {
            return tooLarge;
        }

        /** @return true once the image is complete (or too large) */
        public boolean isDone() {
            return done;
        }

        /**
         * Draws the next part of the image; call it while its document is bound.
         *
         * @param deadline {@code Duration.currentTimeMillis()} value after which no further unit
         *                 (an element draw, a band, the canvas allocation) is started in this step
         * @return true once the image is complete
         */
        public boolean step(double deadline) {
            if (done) {
                return true;
            }
            if (restarts < OFFSCREEN_MAX_RESTARTS && !elms.equals(simulator().elmList)) {
                // the document changed between steps: measure its present elements again
                restarts++;
                startMeasure();
            }
            if (measured < elms.size()) {
                drawElements(deadline, true);
                // the canvas allocation starts a step of its own
                return false;
            }
            if (!laidOut) {
                boolean fresh = layout();
                laidOut = true;
                if (done) {
                    return true;
                }
                if (fresh) {
                    // the canvas allocation (with its first band) is a unit of its own
                    fillBands(deadline, true);
                    return false;
                }
            }
            if (filledRows < height) {
                fillBands(deadline, false);
                if (filledRows < height || Duration.currentTimeMillis() >= deadline) {
                    return false;
                }
            }
            drawElements(deadline, false);
            if (!tailDrawn) {
                return false;
            }
            done = true;
            return true;
        }

        /** Fills background bands until the deadline; {@code one}: a single band (the allocation). */
        private void fillBands(double deadline, boolean one) {
            int band = Math.max(1, OFFSCREEN_FILL_BAND_PIXELS / Math.max(1, width));
            graphics.setColor(cirSim.menuManager.printableCheckItem.getState() ? Color.white : Color.black);
            do {
                int rows = Math.min(band, height - filledRows);
                graphics.fillRect(0, filledRows, width, rows);
                filledRows += rows;
            } while (!one && filledRows < height && Duration.currentTimeMillis() < deadline);
        }

        /** Draws elements until the deadline: into the scratch canvas (measure) or into the image. */
        private void drawElements(double deadline, boolean measure) {
            MenuManager mm = cirSim.menuManager;
            ColorSettings cs = ColorSettings.get();
            double[] savedTransform = Arrays.copyOf(transform, 6);
            boolean savedDots = mm.dotsCheckItem.getState();
            boolean savedPrintable = cs.isPrintable();
            double savedCurrentMult = currentMult;
            Context2d ctx;
            if (measure) {
                if (measureContext == null) {
                    Canvas c = Canvas.createIfSupported();
                    c.setCoordinateSpaceWidth(1);
                    c.setCoordinateSpaceHeight(1);
                    measureContext = c.getContext2d();
                }
                ctx = measureContext;
            } else {
                ctx = context;
            }
            Graphics g = measure ? new Graphics(ctx) : graphics;
            Scope.beginOffscreenDraw();
            try {
                cs.setPrintable(mm.printableCheckItem.getState());
                mm.dotsCheckItem.setState(false);
                currentMult = 0;

                double s = measure ? 1 : scale;
                double tx = measure || area == null ? OFFSCREEN_MARGIN : OFFSCREEN_MARGIN - area.x;
                double ty = measure || area == null ? OFFSCREEN_MARGIN : OFFSCREEN_MARGIN - area.y;
                // the renderer transform matches the image (ScopeElm draws in transformed pixels)
                transform[0] = transform[3] = s;
                transform[1] = transform[2] = 0;
                transform[4] = tx * s;
                transform[5] = ty * s;

                CircuitSimulator simulator = simulator();
                ctx.save();
                g.scale(s, s);
                g.translate(tx, ty);
                g.setFont(getUnitsFont());
                g.setLineCap(Context2d.LineCap.ROUND);
                do {
                    int i = measure ? measured : next;
                    if (i >= elms.size()) {
                        break;
                    }
                    CircuitElm ce = elms.get(i);
                    if (measure) {
                        measured++;
                    } else {
                        next++;
                    }
                    if (mm.powerCheckItem.getState()) {
                        g.setColor(Color.gray);
                    }
                    boolean isStopErrorElm = simulator.stopMessage != null && simulator.stopElm == ce;
                    if (isStopErrorElm) {
                        g.pushForcedColor(Color.red);
                    }
                    ce.draw(g);
                    if (isStopErrorElm) {
                        g.popForcedColor();
                    }
                } while (Duration.currentTimeMillis() < deadline);
                if (!measure && next >= elms.size()) {
                    for (Point post : simulator.postDrawList) {
                        CircuitElm.drawPost(g, post);
                    }
                    for (int i = 0; i != simulator.badConnectionList.size(); i++) {
                        Point cn = simulator.badConnectionList.get(i);
                        g.setColor(Color.red);
                        g.fillOval(cn.x - 3, cn.y - 3, 7, 7);
                    }
                }
                ctx.restore();

                if (!measure && next >= elms.size()) {
                    if (panel != null) {
                        ctx.save();
                        g.scale(scale, scale);
                        g.translate(-panel.x, circuitHeight / scale - panel.y);
                        for (Scope scope : scopes) {
                            scope.draw(g);
                        }
                        ctx.restore();
                    }
                    tailDrawn = true;
                }
            } finally {
                Scope.endOffscreenDraw();
                System.arraycopy(savedTransform, 0, transform, 0, 6);
                mm.dotsCheckItem.setState(savedDots);
                cs.setPrintable(savedPrintable);
                currentMult = savedCurrentMult;
            }
        }

        /** @return the detached canvas of a PNG image (null for SVG or a too-large image) */
        public Canvas getCanvas() {
            return canvas;
        }

        /** @return the SVG text of a complete SVG image; reads the context only (any scope) */
        public String getSvgText() {
            return CirSim.getSerializedSVG(context);
        }
    }

    /** @return the union of every element's endpoints and bounding box, or null without elements */
    Rectangle offscreenBounds() {
        CircuitSimulator simulator = simulator();
        if (simulator.elmList.isEmpty()) {
            return null;
        }
        int x1 = Integer.MAX_VALUE, y1 = Integer.MAX_VALUE, x2 = Integer.MIN_VALUE, y2 = Integer.MIN_VALUE;
        for (CircuitElm ce : simulator.elmList) {
            x1 = Math.min(x1, Math.min(ce.getX(), ce.getX2()));
            y1 = Math.min(y1, Math.min(ce.getY(), ce.getY2()));
            x2 = Math.max(x2, Math.max(ce.getX(), ce.getX2()));
            y2 = Math.max(y2, Math.max(ce.getY(), ce.getY2()));
            Rectangle b = ce.getBoundingBox();
            if (b != null && b.width > 0 && b.height > 0) {
                x1 = Math.min(x1, b.x);
                y1 = Math.min(y1, b.y);
                x2 = Math.max(x2, b.x + b.width);
                y2 = Math.max(y2, b.y + b.height);
            }
        }
        return new Rectangle(x1, y1, x2 - x1, y2 - y1);
    }
}
