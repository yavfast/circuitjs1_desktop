package com.lushprojects.circuitjs1.client;

import com.google.gwt.canvas.client.Canvas;
import com.google.gwt.canvas.dom.client.Context2d;
import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.user.client.Timer;
import com.lushprojects.circuitjs1.client.element.CapacitorElm;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.InductorElm;
import com.lushprojects.circuitjs1.client.element.ResistorElm;
import com.lushprojects.circuitjs1.client.util.Locale;
import com.lushprojects.circuitjs1.client.util.PerfMonitor;

import java.util.Arrays;

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

    int hintType = -1, hintItem1, hintItem2;

    public double[] transform = new double[6];
    Rectangle circuitArea;

    double scopeHeightFraction = 0.2;

    public CircuitRenderer(CirSim cirSim) {
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
            cirSim.setCanvasSize();
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
    final static int FAST_TIMER = 16;

    final Timer timer = new Timer() {
        public void run() {
            updateCircuit();
        }
    };

    void startTimer() {
        timer.scheduleRepeating(FAST_TIMER);
    }

    void stopTimer() {
        timer.cancel();
    }

    void repaint() {
        if (!needsRepaint) {
            needsRepaint = true;
            Scheduler.get().scheduleFixedDelay(() -> {
                updateCircuit();
                needsRepaint = false;
                return false;
            }, FAST_TIMER);
        }
    }

    private final PerfMonitor perfmon = new PerfMonitor();

    public void updateCircuit() {
        perfmon.reset();
        perfmon.startContext("updateCircuit()");

        checkCanvasSize();

        CircuitSimulator simulator = simulator();
        boolean wasAnalyzed = needsAnalysis;
        if (needsAnalysis || cirSim.circuitInfo.dcAnalysisFlag) {
            perfmon.startContext("analyzeCircuit()");
            simulator().analyzeCircuit();
            needsAnalysis = false;
            perfmon.stopContext();
        }

        if (simulator().needsStamp && simulator().simRunning) {
            perfmon.startContext("stampCircuit()");
            try {
                simulator().preStampAndStampCircuit();
            } catch (Exception e) {
                simulator().stop("Exception in stampCircuit()", null);
                GWT.log("Exception in stampCircuit", e);
            }
            perfmon.stopContext();
        }

        if (simulator().stopElm != null && simulator().stopElm != circuitEditor().mouseElm) {
//            simulator().stopElm.setMouseElm(true);
        }

        scopeManager().setupScopes();

        Graphics graphics = new Graphics(canvasContext);
        setupFrame(graphics);

        if (simulator().simRunning) {
            if (simulator().needsStamp) {
                CirSim.console("needsStamp while simRunning?");
            }

            perfmon.startContext("runCircuit()");
            try {
                simulator().runCircuit(wasAnalyzed);
            } catch (Exception e) {
                CirSim.console("exception in runCircuit " + e);
            }
            perfmon.stopContext();
        }

        updateSimulationTimers(simulator);

        perfmon.startContext("graphics");
        drawCircuit(graphics, simulator);
        perfmon.stopContext(); // graphics

        if (simulator().stopElm != null && simulator().stopElm != circuitEditor().mouseElm) {
//            simulator().stopElm.setMouseElm(false);
        }

        frameCount++;

        if (cirSim.circuitInfo.dcAnalysisFlag) {
            cirSim.circuitInfo.dcAnalysisFlag = false;
            needsAnalysis = true;
        }

        lastFrameTimeMillis = lastTimeMillis;
        perfmon.stopContext(); // updateCircuit

        if (cirSim.circuitInfo.developerMode) {
            drawDeveloperInfo(graphics, perfmon);
        }

        if (cirSim.menuManager.mouseModeCheckItem.getState()) {
            drawMouseMode(graphics);
        }

        cirSim.callUpdateHook();
    }

    private void setupFrame(Graphics graphics) {
        if (cirSim.menuManager.printableCheckItem.getState()) {
            CircuitElm.backgroundColor = Color.black;
            CircuitElm.elementColor = Color.black;
            graphics.setColor(Color.white);
            canvas.getElement().getStyle().setBackgroundColor("#fff");
        } else {
            CircuitElm.backgroundColor = Color.white;
            CircuitElm.elementColor = Color.lightGray;
            graphics.setColor(Color.black);
            canvas.getElement().getStyle().setBackgroundColor("#000");
        }
        graphics.fillRect(0, 0, canvasWidth, canvasHeight);
    }

    private void updateSimulationTimers(CircuitSimulator simulator) {
        long sysTime = System.currentTimeMillis();
        if (simulator().simRunning) {
            if (lastTimeMillis != 0) {
                int timeDelta = (int) (sysTime - lastTimeMillis);
                double currentSpeed = cirSim.currentBar.getValue();
                currentSpeed = java.lang.Math.exp(currentSpeed / 3.5 - 14.2);
                CircuitElm.currentMult = 1.7 * timeDelta * currentSpeed;
                if (!cirSim.menuManager.conventionCheckItem.getState())
                    CircuitElm.currentMult = -CircuitElm.currentMult;
            }
            lastTimeMillis = sysTime;
        } else {
            lastTimeMillis = 0;
        }

        if (sysTime - lastSecondTimeMillis >= 1000) {
            framesPerSecond = frameCount;
            stepsPerSecond = simulator().steps;
            frameCount = 0;
            simulator().steps = 0;
            lastSecondTimeMillis = sysTime;
        }

        CircuitElm.powerMult = Math.exp(cirSim.powerBar.getValue() / 4.762 - 7);
    }

    private void drawCircuit(Graphics graphics, CircuitSimulator simulator) {
        graphics.setFont(CircuitElm.unitsFont);
        graphics.setLineCap(Context2d.LineCap.ROUND);

        if (cirSim.menuManager.noEditCheckItem.getState()) {
            graphics.drawLock(20, 30);
        }

        graphics.setColor(Color.white);

        double scale = CirSim.devicePixelRatio();
        canvasContext.setTransform(transform[0] * scale, 0, 0, transform[3] * scale, transform[4] * scale, transform[5] * scale);

        drawElements(graphics, simulator);
        drawHandles(graphics);
        drawBadConnections(graphics, simulator);
        drawSelectionAndCursor(graphics);

        canvasContext.setTransform(scale, 0, 0, scale, 0, 0);

        drawBottomArea(graphics);
    }

    private void drawElements(Graphics graphics, CircuitSimulator simulator) {
        perfmon.startContext("elm.draw()");
        for (CircuitElm ce: simulator().elmList) {
            if (cirSim.menuManager.powerCheckItem.getState()) {
                graphics.setColor(Color.gray);
            }
            ce.draw(graphics);
        }
        perfmon.stopContext();

        CircuitEditor circuitEditor = circuitEditor();
        if (circuitEditor().mouseMode != MouseMode.DRAG_ROW && circuitEditor().mouseMode != MouseMode.DRAG_COLUMN) {
            for (Point item: simulator().postDrawList) {
                CircuitElm.drawPost(graphics, item);
            }
        }

        if (circuitEditor().tempMouseMode == MouseMode.DRAG_ROW ||
                circuitEditor().tempMouseMode == MouseMode.DRAG_COLUMN ||
                circuitEditor().tempMouseMode == MouseMode.DRAG_POST ||
                circuitEditor().tempMouseMode == MouseMode.DRAG_SELECTED) {
            for (CircuitElm ce: simulator().elmList) {
                if (ce != circuitEditor().mouseElm || circuitEditor().tempMouseMode != MouseMode.DRAG_POST) {
                    graphics.setColor(Color.gray);
                    graphics.fillOval(ce.x - 3, ce.y - 3, 7, 7);
                    graphics.fillOval(ce.x2 - 3, ce.y2 - 3, 7, 7);
                } else {
                    ce.drawHandles(graphics, CircuitElm.selectColor);
                }
            }
        }
    }

    private void drawHandles(Graphics graphics) {
        CircuitEditor circuitEditor = circuitEditor();
        if (circuitEditor().tempMouseMode == MouseMode.SELECT && circuitEditor().mouseElm != null) {
            circuitEditor().mouseElm.drawHandles(graphics, CircuitElm.selectColor);
        }

        if (circuitEditor().dragElm != null && (circuitEditor().dragElm.x != circuitEditor().dragElm.x2 || circuitEditor().dragElm.y != circuitEditor().dragElm.y2)) {
            circuitEditor().dragElm.draw(graphics);
            circuitEditor().dragElm.drawHandles(graphics, CircuitElm.selectColor);
        }
    }

    private void drawBadConnections(Graphics graphics, CircuitSimulator simulator) {
        for (int i = 0; i != simulator().badConnectionList.size(); i++) {
            Point cn = simulator().badConnectionList.get(i);
            graphics.setColor(Color.red);
            graphics.fillOval(cn.x - 3, cn.y - 3, 7, 7);
        }
    }

    private void drawSelectionAndCursor(Graphics graphics) {
        CircuitEditor circuitEditor = circuitEditor();
        if (circuitEditor().selectedArea != null) {
            graphics.setColor(CircuitElm.selectColor);
            graphics.drawRect(circuitEditor().selectedArea.x, circuitEditor().selectedArea.y, circuitEditor().selectedArea.width, circuitEditor().selectedArea.height);
        }

        if (cirSim.menuManager.crossHairCheckItem.getState() && circuitEditor().mouseCursorX >= 0
                && circuitEditor().mouseCursorX <= circuitArea.width && circuitEditor().mouseCursorY <= circuitArea.height) {
            graphics.setColor(Color.gray);
            int x = circuitEditor().snapGrid(inverseTransformX(circuitEditor().mouseCursorX));
            int y = circuitEditor().snapGrid(inverseTransformY(circuitEditor().mouseCursorY));
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
        graphics.drawString("Steprate/iter: " + CircuitElm.showFormat(stepsPerSecond / cirSim.getIterCount()), 10, height += increment);
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
        graphics.drawString(Locale.LS("Mode: ") + cirSim.menuManager.classToLabelMap.get(circuitEditor().mouseModeStr), 10, 29);
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
            if (cirSim.circuitInfo.hideInfoBox)
                infoBoxHeight = 0;
        }
        if (simulator.stopMessage != null && circuitArea.height > canvasHeight - 30)
            infoBoxHeight = 30;

        g.setColor(cirSim.menuManager.printableCheckItem.getState() ? "#eee" : "#111");
        g.fillRect(infoBoxStartX, circuitArea.height - infoBoxHeight, circuitArea.width, canvasHeight - circuitArea.height + infoBoxHeight);
        g.setFont(CircuitElm.unitsFont);

        int currentScopeCount = (simulator.stopMessage != null) ? 0 : scopeManager.scopeCount;

        Scope.clearCursorInfo();
        for (int i = 0; i < currentScopeCount; i++)
            scopeManager.scopes[i].selectScope(circuitEditor.mouseCursorX, circuitEditor.mouseCursorY);
        if (simulator.scopeElmArr != null)
            for (int i = 0; i < simulator.scopeElmArr.length; i++)
                simulator.scopeElmArr[i].selectScope(circuitEditor.mouseCursorX, circuitEditor.mouseCursorY);

        for (int i = 0; i < currentScopeCount; i++)
            scopeManager.scopes[i].draw(g);

        if (circuitEditor.mouseWasOverSplitter) {
            g.setColor(CircuitElm.selectColor);
            g.setLineWidth(4.0);
            g.drawLine(0, circuitArea.height - 2, circuitArea.width, circuitArea.height - 2);
            g.setLineWidth(1.0);
        }
        g.setColor(CircuitElm.backgroundColor);

        if (simulator.stopMessage != null) {
            g.drawString(simulator.stopMessage, 10, canvasHeight - 10);
        } else if (!cirSim.circuitInfo.hideInfoBox) {
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
                infoLines[0] = Locale.LS(infoLines[0]);
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
            infoLines[lineIdx++] = badNodes + ((badNodes == 1) ? Locale.LS(" bad connection") : Locale.LS(" bad connections"));
        }
        if (cirSim.circuitInfo.savedFlag) {
            infoLines[lineIdx++] = "(saved)";
        }

        int x = leftX + 5;
        if (scopeCount != 0) {
            x = scopeManager().scopes[scopeCount - 1].rightEdge() + 20;
        }

        int yBase = circuitArea.height;
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
                if (!(c1 instanceof InductorElm) || !(c2 instanceof CapacitorElm)) return null;
                InductorElm ie = (InductorElm) c1;
                CapacitorElm ce = (CapacitorElm) c2;
                return Locale.LS("res.f = ") + CircuitElm.getUnitText(1 / (2 * Math.PI * Math.sqrt(ie.inductance *
                        ce.capacitance)), "Hz");
            }
            case CircuitConst.HINT_RC: {
                if (!(c1 instanceof ResistorElm) || !(c2 instanceof CapacitorElm)) return null;
                ResistorElm re = (ResistorElm) c1;
                CapacitorElm ce = (CapacitorElm) c2;
                return "RC = " + CircuitElm.getUnitText(re.resistance * ce.capacitance,
                        "s");
            }
            case CircuitConst.HINT_3DB_C: {
                if (!(c1 instanceof ResistorElm) || !(c2 instanceof CapacitorElm)) return null;
                ResistorElm re = (ResistorElm) c1;
                CapacitorElm ce = (CapacitorElm) c2;
                return Locale.LS("f.3db = ") +
                        CircuitElm.getUnitText(1 / (2 * Math.PI * re.resistance * ce.capacitance), "Hz");
            }
            case CircuitConst.HINT_3DB_L: {
                if (!(c1 instanceof ResistorElm) || !(c2 instanceof InductorElm)) return null;
                ResistorElm re = (ResistorElm) c1;
                InductorElm ie = (InductorElm) c2;
                return Locale.LS("f.3db = ") +
                        CircuitElm.getUnitText(re.resistance / (2 * Math.PI * ie.inductance), "Hz");
            }
            case CircuitConst.HINT_TWINT: {
                if (!(c1 instanceof ResistorElm) || !(c2 instanceof CapacitorElm)) return null;
                ResistorElm re = (ResistorElm) c1;
                CapacitorElm ce = (CapacitorElm) c2;
                return Locale.LS("fc = ") +
                        CircuitElm.getUnitText(1 / (2 * Math.PI * re.resistance * ce.capacitance), "Hz");
            }
            default:
                return null;
        }
    }

    void centreCircuit() {
        if (simulator().elmList == null)  // avoid exception if called during initialization
            return;

        Rectangle bounds = getCircuitBounds();
        setCircuitArea();

        double scale = 1.0;
        int effectiveCircuitHeight = circuitArea.height;

        // If there's no scope and the window isn't very wide, don't use the full circuit area for centering.
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
        if (simulator().elmList.isEmpty()) {
            return null;
        }

        for (CircuitElm ce : simulator().elmList) {
            // Centered text causes problems when trying to center the circuit, so we special-case it here
            if (!ce.isCenteredText()) {
                minx = Math.min(ce.x, Math.min(ce.x2, minx));
                maxx = Math.max(ce.x, Math.max(ce.x2, maxx));
            }
            miny = Math.min(ce.y, Math.min(ce.y2, miny));
            maxy = Math.max(ce.y, Math.max(ce.y2, maxy));
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

            if (cirSim.menuManager.printableCheckItem.getState()) {
                CircuitElm.backgroundColor = Color.black;
                CircuitElm.elementColor = Color.black;
                graphics.setColor(Color.white);
            } else {
                CircuitElm.backgroundColor = Color.white;
                CircuitElm.elementColor = Color.lightGray;
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
            for (CircuitElm elm : simulator().elmList) {
                elm.draw(graphics);
            }
            for (Point post : simulator().postDrawList) {
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
        if (bounds == null) return exportCanvas; // Return empty canvas if no bounds

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
        if (bounds == null) return ""; // Return empty string if no bounds

        int widthMargin = 140;
        int heightMargin = 100;
        int w = (bounds.width + widthMargin);
        int h = (bounds.height + heightMargin);
        Context2d context = CirSim.createSVGContext(w, h);
        drawCircuitInContext(context, CirSim.CAC_SVG, bounds, w, h);
        return CirSim.getSerializedSVG(context);
    }
}
