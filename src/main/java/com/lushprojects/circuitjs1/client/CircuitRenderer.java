package com.lushprojects.circuitjs1.client;

import com.google.gwt.canvas.client.Canvas;
import com.google.gwt.canvas.dom.client.Context2d;
import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.user.client.Timer;
import com.lushprojects.circuitjs1.client.util.Locale;
import com.lushprojects.circuitjs1.client.util.PerfMonitor;

import java.util.Arrays;

public class CircuitRenderer extends BaseCirSimDelegate {

    Canvas cv;
    Context2d cvcontext;

    // canvas width/height in px (before device pixel ratio scaling)
    int canvasWidth, canvasHeight;

    boolean analyzeFlag;

    long lastTime = 0;
    long lastFrameTime, secTime = 0;
    int frames = 0;
    int framerate = 0, steprate = 0;

    double[] transform = new double[6];
    Rectangle circuitArea;

    double scopeHeightFraction = 0.2;

    public CircuitRenderer(CirSim cirSim) {
        super(cirSim);
    }

    public Canvas initCanvas() {
        if (cv == null) {
            cv = Canvas.createIfSupported();
            if (cv != null) {
                cvcontext = cv.getContext2d();
            }
        }
        return cv;
    }

    void setCanvasSize(int width, int height) {
        if (cv != null) {
            cv.setWidth(width + "PX");
            cv.setHeight(height + "PX");
            canvasWidth = width;
            canvasHeight = height;
            float scale = CirSim.devicePixelRatio();
            cv.setCoordinateSpaceWidth((int) (width * scale));
            cv.setCoordinateSpaceHeight((int) (height * scale));
        }
    }

    void checkCanvasSize() {
        if (cv.getCoordinateSpaceWidth() != (int) (canvasWidth * CirSim.devicePixelRatio()))
            cirSim.setCanvasSize();
    }

    void setCircuitArea() {
        int height = canvasHeight;
        int width = canvasWidth;
        int h = (int) ((double) height * scopeHeightFraction);
        if (cirSim.scopeManager.scopeCount == 0) {
            h = 0;
        }
        circuitArea = new Rectangle(0, 0, width, height - h);
    }

    void zoomCircuit(double dy) {
        zoomCircuit(dy, false);
    }

    void zoomCircuit(double dy, boolean menu) {
        double newScale;
        double oldScale = transform[0];
        double val = dy * .01;
        newScale = Math.max(oldScale + val, .2);
        newScale = Math.min(newScale, 2.5);
        setCircuitScale(newScale, menu);
    }

    void setCircuitScale(double newScale, boolean menu) {
        int constX = !menu ? circuitEditor().mouseCursorX : circuitArea.width / 2;
        int constY = !menu ? circuitEditor().mouseCursorY : circuitArea.height / 2;
        int cx = inverseTransformX(constX);
        int cy = inverseTransformY(constY);
        transform[0] = transform[3] = newScale;

        // adjust translation to keep center of screen constant
        // inverse transform = (x-t4)/t0
        transform[4] = constX - cx * newScale;
        transform[5] = constY - cy * newScale;
    }


    // convert screen coordinates to grid coordinates by inverting circuit transform
    int inverseTransformX(double x) {
        return (int) ((x - transform[4]) / transform[0]);
    }

    int inverseTransformY(double y) {
        return (int) ((y - transform[5]) / transform[3]);
    }

    // convert grid coordinates to screen coordinates
    int transformX(double x) {
        return (int) ((x * transform[0]) + transform[4]);
    }

    int transformY(double y) {
        return (int) ((y * transform[3]) + transform[5]);
    }


    boolean needsRepaint;
    final int FASTTIMER = 16;

    final Timer timer = new Timer() {
        public void run() {
            updateCircuit();
        }
    };

    void startTimer() {
        timer.scheduleRepeating(FASTTIMER);
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
            }, FASTTIMER);
        }
    }

    public void updateCircuit() {
        PerfMonitor perfmon = new PerfMonitor();
        perfmon.startContext("updateCircuit()");

        checkCanvasSize();

        CircuitSimulator simulator = simulator();
// Analyze circuit
        boolean didAnalyze = analyzeFlag;
        if (analyzeFlag || cirSim.dcAnalysisFlag) {
            perfmon.startContext("analyzeCircuit()");
            simulator.analyzeCircuit();
            analyzeFlag = false;
            perfmon.stopContext();
        }

        // Stamp circuit
        if (simulator.needsStamp && simulator.simRunning) {
            perfmon.startContext("stampCircuit()");
            try {
                simulator.preStampAndStampCircuit();
            } catch (Exception e) {
                cirSim.stop("Exception in stampCircuit()", null);
                GWT.log("Exception in stampCircuit", e);
            }
            perfmon.stopContext();
        }

        if (simulator.stopElm != null && simulator.stopElm != circuitEditor().mouseElm)
            simulator.stopElm.setMouseElm(true);

        cirSim.scopeManager.setupScopes();

        Graphics g = new Graphics(cvcontext);

        if (cirSim.menuManager.printableCheckItem.getState()) {
            CircuitElm.whiteColor = Color.black;
            CircuitElm.lightGrayColor = Color.black;
            g.setColor(Color.white);
            cv.getElement().getStyle().setBackgroundColor("#fff");
        } else {
            CircuitElm.whiteColor = Color.white;
            CircuitElm.lightGrayColor = Color.lightGray;
            g.setColor(Color.black);
            cv.getElement().getStyle().setBackgroundColor("#000");
        }

        // Clear the frame
        g.fillRect(0, 0, canvasWidth, canvasHeight);

        // Run circuit
        if (simulator.simRunning) {
            if (simulator.needsStamp)
                CirSim.console("needsStamp while simRunning?");

            perfmon.startContext("runCircuit()");
            try {
                simulator.runCircuit(didAnalyze);
            } catch (Exception e) {
                CirSim.debugger();
                CirSim.console("exception in runCircuit " + e);
                e.printStackTrace();
            }
            perfmon.stopContext();
        }

        long sysTime = System.currentTimeMillis();
        if (simulator.simRunning) {
            if (lastTime != 0) {
                int inc = (int) (sysTime - lastTime);
                double c = cirSim.currentBar.getValue();
                c = java.lang.Math.exp(c / 3.5 - 14.2);
                CircuitElm.currentMult = 1.7 * inc * c;
                if (!cirSim.menuManager.conventionCheckItem.getState())
                    CircuitElm.currentMult = -CircuitElm.currentMult;
            }
            lastTime = sysTime;
        } else {
            lastTime = 0;
        }

        if (sysTime - secTime >= 1000) {
            framerate = frames;
            steprate = simulator.steps;
            frames = 0;
            simulator.steps = 0;
            secTime = sysTime;
        }

        CircuitElm.powerMult = Math.exp(cirSim.powerBar.getValue() / 4.762 - 7);

        perfmon.startContext("graphics");

        g.setFont(CircuitElm.unitsFont);

        g.context.setLineCap(Context2d.LineCap.ROUND);

        if (cirSim.menuManager.noEditCheckItem.getState())
            g.drawLock(20, 30);

        g.setColor(Color.white);

        // Set the graphics transform to deal with zoom and offset
        double scale = CirSim.devicePixelRatio();
        cvcontext.setTransform(transform[0] * scale, 0, 0, transform[3] * scale, transform[4] * scale, transform[5] * scale);

        // Draw each element
        perfmon.startContext("elm.draw()");
        for (int i = 0; i != simulator.elmList.size(); i++) {
            if (cirSim.menuManager.powerCheckItem.getState())
                g.setColor(Color.gray);

            cirSim.getElm(i).draw(g);
        }
        perfmon.stopContext();

        CircuitEditor circuitEditor = circuitEditor();
        // Draw posts normally
        if (circuitEditor.mouseMode != circuitEditor.MODE_DRAG_ROW && circuitEditor.mouseMode != circuitEditor.MODE_DRAG_COLUMN) {
            for (int i = 0; i != simulator.postDrawList.size(); i++)
                CircuitElm.drawPost(g, simulator.postDrawList.get(i));
        }

        // for some mouse modes, what matters is not the posts but the endpoints (which
        // are only the same for 2-terminal elements). We draw those now if needed
        if (circuitEditor.tempMouseMode == circuitEditor.MODE_DRAG_ROW ||
                circuitEditor.tempMouseMode == circuitEditor.MODE_DRAG_COLUMN ||
                circuitEditor.tempMouseMode == circuitEditor.MODE_DRAG_POST ||
                circuitEditor.tempMouseMode == circuitEditor.MODE_DRAG_SELECTED) {
            for (int i = 0; i != simulator.elmList.size(); i++) {
                CircuitElm ce = simulator.elmList.get(i);
                // ce.drawPost(g, ce.x , ce.y );
                // ce.drawPost(g, ce.x2, ce.y2);
                if (ce != circuitEditor.mouseElm || circuitEditor.tempMouseMode != circuitEditor.MODE_DRAG_POST) {
                    g.setColor(Color.gray);
                    g.fillOval(ce.x - 3, ce.y - 3, 7, 7);
                    g.fillOval(ce.x2 - 3, ce.y2 - 3, 7, 7);
                } else {
                    ce.drawHandles(g, CircuitElm.selectColor);
                }
            }
        }

        // draw handles for elm we're creating
        if (circuitEditor.tempMouseMode == circuitEditor.MODE_SELECT && circuitEditor.mouseElm != null) {
            circuitEditor.mouseElm.drawHandles(g, CircuitElm.selectColor);
        }

        // draw handles for elm we're dragging
        if (circuitEditor.dragElm != null && (circuitEditor.dragElm.x != circuitEditor.dragElm.x2 || circuitEditor.dragElm.y != circuitEditor.dragElm.y2)) {
            circuitEditor.dragElm.draw(g);
            circuitEditor.dragElm.drawHandles(g, CircuitElm.selectColor);
        }

        // draw bad connections. do this last so they will not be overdrawn.
        for (int i = 0; i != simulator.badConnectionList.size(); i++) {
            Point cn = simulator.badConnectionList.get(i);
            g.setColor(Color.red);
            g.fillOval(cn.x - 3, cn.y - 3, 7, 7);
        }

        // draw the selection rect
        if (circuitEditor.selectedArea != null) {
            g.setColor(CircuitElm.selectColor);
            g.drawRect(circuitEditor.selectedArea.x, circuitEditor.selectedArea.y, circuitEditor.selectedArea.width, circuitEditor.selectedArea.height);
        }

        // draw the crosshair cursor
        if (cirSim.menuManager.crossHairCheckItem.getState() && circuitEditor.mouseCursorX >= 0
                && circuitEditor.mouseCursorX <= circuitArea.width && circuitEditor.mouseCursorY <= circuitArea.height) {
            g.setColor(Color.gray);
            int x = circuitEditor.snapGrid(inverseTransformX(circuitEditor.mouseCursorX));
            int y = circuitEditor.snapGrid(inverseTransformY(circuitEditor.mouseCursorY));
            g.drawLine(x, inverseTransformY(0), x, inverseTransformY(circuitArea.height));
            g.drawLine(inverseTransformX(0), y, inverseTransformX(circuitArea.width), y);
        }

        // reset the graphics scale and translation
        cvcontext.setTransform(scale, 0, 0, scale, 0, 0);

        // draw the bottom area i.e. the scope and info section
        perfmon.startContext("drawBottomArea()");
        drawBottomArea(g);
        perfmon.stopContext();

        g.setColor(Color.white);

        perfmon.stopContext(); // graphics

        if (simulator.stopElm != null && simulator.stopElm != circuitEditor.mouseElm)
            simulator.stopElm.setMouseElm(false);

        frames++;

        // if we did DC analysis, we need to re-analyze the circuit with that flag
        // cleared.
        if (cirSim.dcAnalysisFlag) {
            cirSim.dcAnalysisFlag = false;
            analyzeFlag = true;
        }

        lastFrameTime = lastTime;

        perfmon.stopContext(); // updateCircuit

        if (cirSim.developerMode) {
            int height = 45;
            int increment = 15;
            g.drawString("Framerate: " + CircuitElm.showFormat.format(framerate), 10, height);
            g.drawString("Steprate: " + CircuitElm.showFormat.format(steprate), 10, height += increment);
            g.drawString("Steprate/iter: " + CircuitElm.showFormat.format(steprate / cirSim.getIterCount()), 10, height += increment);
            g.drawString("iterc: " + CircuitElm.showFormat.format(cirSim.getIterCount()), 10, height += increment);
            g.drawString("Frames: " + frames, 10, height += increment);

            height += (increment * 2);

            String perfmonResult = PerfMonitor.buildString(perfmon).toString();
            String[] splits = perfmonResult.split("\n");
            for (int x = 0; x < splits.length; x++) {
                g.drawString(splits[x], 10, height + (increment * x));
            }
        }

        // Add info about mouse mode in graphics
        if (cirSim.menuManager.mouseModeCheckItem.getState()) {
            if (cirSim.menuManager.printableCheckItem.getState()) g.setColor(Color.black);
            g.drawString(Locale.LS("Mode: ") + cirSim.classToLabelMap.get(circuitEditor.mouseModeStr), 10, 29);
        }

        // This should always be the last
        // thing called by updateCircuit();
        cirSim.callUpdateHook();
    }

    void drawBottomArea(Graphics g) {
        int leftX = 0;
        int h = 0;
        CircuitSimulator simulator = simulator();
        CircuitEditor circuitEditor = circuitEditor();
        if (simulator.stopMessage == null && cirSim.scopeManager.scopeCount == 0) {
            leftX = Math.max(canvasWidth - CirSim.INFO_WIDTH, 0);
            int h0 = (int) (canvasHeight * scopeHeightFraction);
            h = (circuitEditor.mouseElm == null) ? 70 : h0;
            if (cirSim.hideInfoBox)
                h = 0;
        }
        if (simulator.stopMessage != null && circuitArea.height > canvasHeight - 30)
            h = 30;
        g.setColor(cirSim.menuManager.printableCheckItem.getState() ? "#eee" : "#111");
        g.fillRect(leftX, circuitArea.height - h, circuitArea.width, canvasHeight - circuitArea.height + h);
        g.setFont(CircuitElm.unitsFont);
        int ct = cirSim.scopeManager.scopeCount;
        if (simulator.stopMessage != null)
            ct = 0;
        int i;
        Scope.clearCursorInfo();
        for (i = 0; i != ct; i++)
            cirSim.scopeManager.scopes[i].selectScope(circuitEditor.mouseCursorX, circuitEditor.mouseCursorY);
        if (simulator.scopeElmArr != null)
            for (i = 0; i != simulator.scopeElmArr.length; i++)
                simulator.scopeElmArr[i].selectScope(circuitEditor.mouseCursorX, circuitEditor.mouseCursorY);
        for (i = 0; i != ct; i++)
            cirSim.scopeManager.scopes[i].draw(g);
        if (circuitEditor.mouseWasOverSplitter) {
            g.setColor(CircuitElm.selectColor);
            g.setLineWidth(4.0);
            g.drawLine(0, circuitArea.height - 2, circuitArea.width, circuitArea.height - 2);
            g.setLineWidth(1.0);
        }
        g.setColor(CircuitElm.whiteColor);

        if (simulator.stopMessage != null) {
            g.drawString(simulator.stopMessage, 10, canvasHeight - 10);
        } else if (!cirSim.hideInfoBox) {
            // in JS it doesn't matter how big this is, there's no out-of-bounds exception
            String info[] = new String[10];
            if (circuitEditor.mouseElm != null) {
                if (circuitEditor.mousePost == -1) {
                    circuitEditor.mouseElm.getInfo(info);
                    info[0] = Locale.LS(info[0]);
                    if (info[1] != null)
                        info[1] = Locale.LS(info[1]);
                } else
                    info[0] = "V = " +
                            CircuitElm.getUnitText(circuitEditor.mouseElm.getPostVoltage(circuitEditor.mousePost), "V");
//		/* //shownodes
//		for (i = 0; i != mouseElm.getPostCount(); i++)
//		    info[0] += " " + mouseElm.nodes[i];
//		if (mouseElm.getVoltageSourceCount() > 0)
//		    info[0] += ";" + (mouseElm.getVoltageSource()+nodeList.size());
//		*/

            } else {
                info[0] = "t = " + CircuitElm.getTimeText(cirSim.t);
                double timerate = 160 * cirSim.getIterCount() * simulator.timeStep;
                if (timerate >= .1)
                    info[0] += " (" + CircuitElm.showFormat.format(timerate) + "x)";
                info[1] = Locale.LS("time step = ") + CircuitElm.getTimeText(simulator.timeStep);
            }
            if (cirSim.hintType != -1) {
                for (i = 0; info[i] != null; i++)
                    ;
                String s = getHint();
                if (s == null)
                    cirSim.hintType = -1;
                else
                    info[i] = s;
            }
            int x = leftX + 5;
            if (ct != 0)
                x = cirSim.scopeManager.scopes[ct - 1].rightEdge() + 20;
//	    x = max(x, canvasWidth*2/3);
            //  x=cv.getCoordinateSpaceWidth()*2/3;

            // count lines of data
            for (i = 0; info[i] != null; i++)
                ;
            int badnodes = simulator.badConnectionList.size();
            if (badnodes > 0)
                info[i++] = badnodes + ((badnodes == 1) ?
                        Locale.LS(" bad connection") : Locale.LS(" bad connections"));
            if (cirSim.savedFlag)
                info[i++] = "(saved)";

            int ybase = circuitArea.height - h;
            for (i = 0; info[i] != null; i++)
                g.drawString(info[i], x, ybase + 15 * (i + 1));
        }
    }

    String getHint() {
        CircuitElm c1 = cirSim.getElm(cirSim.hintItem1);
        CircuitElm c2 = cirSim.getElm(cirSim.hintItem2);
        if (c1 == null || c2 == null)
            return null;
        if (cirSim.hintType == cirSim.HINT_LC) {
            if (!(c1 instanceof InductorElm))
                return null;
            if (!(c2 instanceof CapacitorElm))
                return null;
            InductorElm ie = (InductorElm) c1;
            CapacitorElm ce = (CapacitorElm) c2;
            return Locale.LS("res.f = ") + CircuitElm.getUnitText(1 / (2 * Math.PI * Math.sqrt(ie.inductance *
                    ce.capacitance)), "Hz");
        }
        if (cirSim.hintType == cirSim.HINT_RC) {
            if (!(c1 instanceof ResistorElm))
                return null;
            if (!(c2 instanceof CapacitorElm))
                return null;
            ResistorElm re = (ResistorElm) c1;
            CapacitorElm ce = (CapacitorElm) c2;
            return "RC = " + CircuitElm.getUnitText(re.resistance * ce.capacitance,
                    "s");
        }
        if (cirSim.hintType == cirSim.HINT_3DB_C) {
            if (!(c1 instanceof ResistorElm))
                return null;
            if (!(c2 instanceof CapacitorElm))
                return null;
            ResistorElm re = (ResistorElm) c1;
            CapacitorElm ce = (CapacitorElm) c2;
            return Locale.LS("f.3db = ") +
                    CircuitElm.getUnitText(1 / (2 * Math.PI * re.resistance * ce.capacitance), "Hz");
        }
        if (cirSim.hintType == cirSim.HINT_3DB_L) {
            if (!(c1 instanceof ResistorElm))
                return null;
            if (!(c2 instanceof InductorElm))
                return null;
            ResistorElm re = (ResistorElm) c1;
            InductorElm ie = (InductorElm) c2;
            return Locale.LS("f.3db = ") +
                    CircuitElm.getUnitText(re.resistance / (2 * Math.PI * ie.inductance), "Hz");
        }
        if (cirSim.hintType == cirSim.HINT_TWINT) {
            if (!(c1 instanceof ResistorElm))
                return null;
            if (!(c2 instanceof CapacitorElm))
                return null;
            ResistorElm re = (ResistorElm) c1;
            CapacitorElm ce = (CapacitorElm) c2;
            return Locale.LS("fc = ") +
                    CircuitElm.getUnitText(1 / (2 * Math.PI * re.resistance * ce.capacitance), "Hz");
        }
        return null;
    }

    void centreCircuit() {
        if (simulator().elmList == null)  // avoid exception if called during initialization
            return;

        Rectangle bounds = getCircuitBounds();
        setCircuitArea();

        double scale = 1;
        int cheight = circuitArea.height;

        // if there's no scope, and the window isn't very wide, then don't use all of the circuit area when
        // centering, because the info in the corner might not get in the way.  We still want circuitArea to be the full
        // height though, to allow the user to put stuff there manually.
        if (cirSim.scopeManager.scopeCount == 0 && circuitArea.width < 800) {
            int h = (int) ((double) cheight * scopeHeightFraction);
            cheight -= h;
        }

        if (bounds != null)
            // add some space on edges because bounds calculation is not perfect
            scale = Math.min(circuitArea.width / (double) (bounds.width + 140),
                    cheight / (double) (bounds.height + 100));
        scale = Math.min(scale, 1.5); // Limit scale so we don't create enormous circuits in big windows

        // calculate transform so circuit fills most of screen
        transform[0] = transform[3] = scale;
        transform[1] = transform[2] = transform[4] = transform[5] = 0;
        if (bounds != null) {
            transform[4] = (circuitArea.width - bounds.width * scale) / 2 - bounds.x * scale;
            transform[5] = (cheight - bounds.height * scale) / 2 - bounds.y * scale;
        }
    }

    // get circuit bounds.  remember this doesn't use setBbox().  That is calculated when we draw
    // the circuit, but this needs to be ready before we first draw it, so we use this crude method
    Rectangle getCircuitBounds() {
        int minx = 30000, maxx = -30000, miny = 30000, maxy = -30000;
        CircuitSimulator simulator = simulator();
        for (int i = 0; i != simulator.elmList.size(); i++) {
            CircuitElm ce = cirSim.getElm(i);
            // centered text causes problems when trying to center the circuit,
            // so we special-case it here
            if (!ce.isCenteredText()) {
                minx = Math.min(ce.x, Math.min(ce.x2, minx));
                maxx = Math.max(ce.x, Math.max(ce.x2, maxx));
            }
            miny = Math.min(ce.y, Math.min(ce.y2, miny));
            maxy = Math.max(ce.y, Math.max(ce.y2, maxy));
        }
        if (minx > maxx)
            return null;
        return new Rectangle(minx, miny, maxx - minx, maxy - miny);
    }

    void drawCircuitInContext(Context2d context, int type, Rectangle bounds, int w, int h) {
        Graphics g = new Graphics(context);
        context.setTransform(1, 0, 0, 1, 0, 0);
        double oldTransform[] = Arrays.copyOf(transform, 6);

        double scale = 1;

        // turn on white background, turn off current display
        boolean p = cirSim.menuManager.printableCheckItem.getState();
        boolean c = cirSim.menuManager.dotsCheckItem.getState();
        boolean print = (type == cirSim.CAC_PRINT);
        if (print)
            cirSim.menuManager.printableCheckItem.setState(true);
        if (cirSim.menuManager.printableCheckItem.getState()) {
            CircuitElm.whiteColor = Color.black;
            CircuitElm.lightGrayColor = Color.black;
            g.setColor(Color.white);
        } else {
            CircuitElm.whiteColor = Color.white;
            CircuitElm.lightGrayColor = Color.lightGray;
            g.setColor(Color.black);
        }
        g.fillRect(0, 0, w, h);
        cirSim.menuManager.dotsCheckItem.setState(false);

        int wmargin = 140;
        int hmargin = 100;
        if (bounds != null)
            scale = Math.min(w / (double) (bounds.width + wmargin),
                    h / (double) (bounds.height + hmargin));

        // ScopeElms need the transform array to be updated
        transform[0] = transform[3] = scale;
        transform[4] = -(bounds.x - wmargin / 2);
        transform[5] = -(bounds.y - hmargin / 2);
        context.scale(scale, scale);
        context.translate(transform[4], transform[5]);
        context.setLineCap(Context2d.LineCap.ROUND);

        // draw elements
        CircuitSimulator simulator = simulator();
        for (int i = 0; i != simulator.elmList.size(); i++) {
            cirSim.getElm(i).draw(g);
        }
        for (int i = 0; i != simulator.postDrawList.size(); i++) {
            CircuitElm.drawPost(g, simulator.postDrawList.get(i));
        }

        // restore everything
        cirSim.menuManager.printableCheckItem.setState(p);
        cirSim.menuManager.dotsCheckItem.setState(c);
        transform = oldTransform;
    }

    public Canvas getCircuitAsCanvas(int type) {
        // create canvas to draw circuit into
        Canvas cv = Canvas.createIfSupported();
        Rectangle bounds = getCircuitBounds();

        // add some space on edges because bounds calculation is not perfect
        int wmargin = 140;
        int hmargin = 100;
        int w = (bounds.width * 2 + wmargin);
        int h = (bounds.height * 2 + hmargin);
        cv.setCoordinateSpaceWidth(w);
        cv.setCoordinateSpaceHeight(h);

        Context2d context = cv.getContext2d();
        drawCircuitInContext(context, type, bounds, w, h);
        return cv;
    }

    public String getCircuitAsSVG() {
        Rectangle bounds = getCircuitBounds();

        // add some space on edges because bounds calculation is not perfect
        int wmargin = 140;
        int hmargin = 100;
        int w = (bounds.width + wmargin);
        int h = (bounds.height + hmargin);
        Context2d context = CirSim.createSVGContext(w, h);
        drawCircuitInContext(context, CirSim.CAC_SVG, bounds, w, h);
        return CirSim.getSerializedSVG(context);
    }


}
