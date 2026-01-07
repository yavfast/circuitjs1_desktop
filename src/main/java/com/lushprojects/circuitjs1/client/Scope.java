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

import com.google.gwt.canvas.client.Canvas;
import com.google.gwt.canvas.dom.client.Context2d;
import com.google.gwt.event.dom.client.MouseWheelEvent;
import com.lushprojects.circuitjs1.client.dialog.ScopePropertiesDialog;
import com.lushprojects.circuitjs1.client.element.AudioOutputElm;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.LogicOutputElm;
import com.lushprojects.circuitjs1.client.element.OutputElm;
import com.lushprojects.circuitjs1.client.element.ProbeElm;
import com.lushprojects.circuitjs1.client.element.TransistorElm;
import com.lushprojects.circuitjs1.client.element.WireElm;
import com.lushprojects.circuitjs1.client.util.Locale;

import java.util.Vector;

public class Scope extends BaseCirSimDelegate {
    private static final int FLAG_YELM = 32;

    // bunch of other flags go here, see getFlags()
    private static final int FLAG_IVALUE = 2048; // Flag to indicate if IVALUE is included in dump
    private static final int FLAG_PLOTS = 4096; // new-style dump with multiple plots
    private static final int FLAG_PERPLOTFLAGS = 1 << 18; // new-new style dump with plot flags
    private static final int FLAG_PERPLOT_MAN_SCALE = 1 << 19; // new-new style dump with manual included in each plot
    private static final int FLAG_MAN_SCALE = 16;
    private static final int FLAG_DIVISIONS = 1 << 21; // dump manDivisions
    private static final int FLAG_TRIGGER = 1 << 22; // dump trigger settings
    private static final int FLAG_HISTORY = 1 << 23; // dump history/persistence settings
    // other flags go here too, see getFlags()

    public static final int TRIG_MODE_AUTO = 0;
    public static final int TRIG_MODE_NORMAL = 1;
    public static final int TRIG_MODE_SINGLE = 2;

    public static final int TRIG_SLOPE_RISING = 0;
    public static final int TRIG_SLOPE_FALLING = 1;

    public static final int HISTORY_CAPTURE_MANUAL = 0;
    public static final int HISTORY_CAPTURE_ON_TRIGGER = 1;

    public static final int VAL_POWER = 7;
    public static final int VAL_POWER_OLD = 1;
    public static final int VAL_VOLTAGE = 0;
    public static final int VAL_CURRENT = 3;
    public static final int VAL_IB = 1;
    public static final int VAL_IC = 2;
    public static final int VAL_IE = 3;
    public static final int VAL_VBE = 4;
    public static final int VAL_VBC = 5;
    public static final int VAL_VCE = 6;
    public static final int VAL_R = 2;
    public static final int UNITS_V = 0;
    public static final int UNITS_A = 1;
    public static final int UNITS_W = 2;
    public static final int UNITS_OHMS = 3;
    public static final int UNITS_COUNT = 4;
    public static final double[] multa = {2.0, 2.5, 2.0};
    public static final int V_POSITION_STEPS = 200;
    public static final double MIN_MAN_SCALE = 1e-9;

    private int scopePointCount = 128;
    private FFT fft;
    public int position;
    // speed is sim timestep units per pixel
    public int speed;
    int stackCount; // number of scopes in this column
    private String text;
    public Rectangle rect;
    private boolean manualScale;
    public boolean showI, showV, showScale, showMax, showMin, showFreq;
    public boolean plot2d;
    public boolean plotXY;
    public boolean maxScale;

    public boolean logSpectrum;
    public boolean showFFT, showNegative, showRMS, showAverage, showDutyCycle, showElmInfo;
    public Vector<ScopePlot> plots, visiblePlots;
    private int draw_ox, draw_oy;
    private Canvas imageCanvas;
    private Graphics graphics;
    private int alphaCounter = 0;
    // scopeTimeStep to check if sim timestep has changed from previous value when redrawing
    private double scopeTimeStep;
    private final double[] scale; // Max value to scale the display to show - indexed for each value of UNITS - e.g. UNITS_V, UNITS_A etc.
    private final boolean[] reduceRange;
    private double scaleX, scaleY;  // for X-Y plots
    private double wheelDeltaY;
    int selectedPlot;
    private ScopePropertiesDialog properties;
    private double gridStepX, gridStepY;
    private double maxValue, minValue;
    public int manDivisions; // Number of vertical divisions when in manual mode
    private static int lastManDivisions;
    private boolean drawGridLines;
    private boolean somethingSelected;

    private static double cursorTime;
    private static int cursorUnits;
    private static Scope cursorScope;

    // Trigger/timebase (time-domain only)
    private boolean triggerEnabled = false;
    private int triggerMode = TRIG_MODE_AUTO;
    private int triggerSlope = TRIG_SLOPE_RISING;
    private double triggerLevel = 0.0;
    private double triggerHoldoff = 0.0; // seconds
    private double triggerPosition = 0.25; // fraction of screen used as pre-trigger
    private int triggerSource = 0; // index in visiblePlots

    private boolean singleTriggered = false;
    private int singleFrozenStartIndex = 0;
    private double singleFrozenRightEdgeTime = 0.0;
    private double singleFrozenTriggerTime = 0.0;
    private double lastTriggerTime = -1;
    private int lastTriggeredStartIndex = -1;

    // Current draw cycle timebase; null means rolling/free-run
    private Integer timeBaseStartIndexOverride = null;
    private double timeBaseRightEdgeTime = 0.0;
    private double timeBaseTriggerTime = 0.0;

    // Last displayed timebase start index (used to freeze display in NORMAL/SINGLE when no trigger is found)
    private int lastDisplayedStartIndex = 0;

    // History / persistence overlay (time-domain only)
    static class HistoryFrame {
        final int width;
        final double[] minValues;
        final double[] maxValues;
        final String color;

        HistoryFrame(int width, double[] minValues, double[] maxValues, String color) {
            this.width = width;
            this.minValues = minValues;
            this.maxValues = maxValues;
            this.color = color;
        }
    }

    private boolean historyEnabled = false;
    private int historyDepth = 8;
    private int historyCaptureMode = HISTORY_CAPTURE_ON_TRIGGER;
    private int historySource = 0; // index in visiblePlots
    private final Vector<HistoryFrame> historyFrames = new Vector<>();
    private int lastHistoryCapturedStartIndex = -1;

    // Triggered display frame (time-domain only). Used for stable display in NORMAL/SINGLE modes.
    static class TriggerFrame {
        final int width;
        final int plotCount;
        final double[][] minValues;
        final double[][] maxValues;
        final int startIndex;
        final double rightEdgeTime;
        final double triggerTime;

        TriggerFrame(int width, int plotCount, double[][] minValues, double[][] maxValues,
                     int startIndex, double rightEdgeTime, double triggerTime) {
            this.width = width;
            this.plotCount = plotCount;
            this.minValues = minValues;
            this.maxValues = maxValues;
            this.startIndex = startIndex;
            this.rightEdgeTime = rightEdgeTime;
            this.triggerTime = triggerTime;
        }
    }

    private TriggerFrame triggerFrame;

    private boolean shouldUseTriggerFrameForDisplay() {
        if (!triggerEnabled || !isTriggerAvailable()) {
            return false;
        }
        if (triggerMode != TRIG_MODE_NORMAL && triggerMode != TRIG_MODE_SINGLE) {
            return false;
        }
        return triggerFrame != null && triggerFrame.width == rect.width && triggerFrame.plotCount == plots.size();
    }

    private int getPlotIndex(ScopePlot plot) {
        for (int i = 0; i < plots.size(); i++) {
            if (plots.get(i) == plot) {
                return i;
            }
        }
        return -1;
    }

    private void captureTriggerFrame(int startIndex, double rightEdgeTime, double triggerTime) {
        int width = rect.width;
        if (width < 2 || plots == null || plots.isEmpty()) {
            triggerFrame = null;
            return;
        }

        int plotCount = plots.size();
        double[][] min = new double[plotCount][];
        double[][] max = new double[plotCount][];

        int mask = scopePointCount - 1;
        for (int pi = 0; pi < plotCount; pi++) {
            ScopePlot p = plots.get(pi);
            if (p == null || p.minValues == null || p.maxValues == null) {
                min[pi] = null;
                max[pi] = null;
                continue;
            }
            double[] mn = new double[width];
            double[] mx = new double[width];
            for (int i = 0; i < width; i++) {
                int ip = (startIndex + i) & mask;
                mn[i] = p.minValues[ip];
                mx[i] = p.maxValues[ip];
            }
            min[pi] = mn;
            max[pi] = mx;
        }

        triggerFrame = new TriggerFrame(width, plotCount, min, max, startIndex, rightEdgeTime, triggerTime);
    }

    public Scope(BaseCirSim s, CircuitDocument circuitDocument) {
        super(s, circuitDocument);
        scale = new double[UNITS_COUNT];
        reduceRange = new boolean[UNITS_COUNT];
        manDivisions = lastManDivisions;

        rect = new Rectangle(0, 0, 1, 1);
        imageCanvas = Canvas.createIfSupported();
        graphics = new Graphics(imageCanvas.getContext2d());
        allocImage();
        initialize();
    }

    void showCurrent(boolean b) {
        showI = b;
        if (b && !showingVoltageAndMaybeCurrent()) {
            setValue(0);
        }
        calcVisiblePlots();
    }

    void showVoltage(boolean b) {
        showV = b;
        if (b && !showingVoltageAndMaybeCurrent()) {
            setValue(0);
        }
        calcVisiblePlots();
    }

    void showMax(boolean b) {
        showMax = b;
    }

    void showScale(boolean b) {
        showScale = b;
    }

    void showMin(boolean b) {
        showMin = b;
    }

    void showFreq(boolean b) {
        showFreq = b;
    }

    void showFFT(boolean b) {
        showFFT = b;
        if (!showFFT) {
            fft = null;
        }
    }

    public void setManualScale(boolean value, boolean roundup) {
        if (value != manualScale) {
            clear2dView();
        }
        manualScale = value;
        for (ScopePlot p : plots) {
            if (!p.manScaleSet) {
                p.manScale = getManScaleFromMaxScale(p.units, roundup);
                p.manVPosition = 0;
                p.manScaleSet = true;
            }
        }
    }

    public void resetGraph() {
        resetGraph(false);
    }

    public void resetGraph(boolean full) {
        scopePointCount = 1;
        while (scopePointCount <= rect.width) {
            scopePointCount *= 2;
        }
        if (plots == null) {
            plots = new Vector<>();
        }
        showNegative = false;
        for (ScopePlot plot : plots) {
            plot.reset(scopePointCount, speed, full);
        }
        clearHistory();
        triggerFrame = null;
        rearmSingleTrigger();
        lastDisplayedStartIndex = 0;
        calcVisiblePlots();
        scopeTimeStep = simulator().maxTimeStep;
        allocImage();
    }

    public boolean isTriggerAvailable() {
        return !(showFFT || plot2d || plotXY);
    }

    public boolean isHistoryAvailable() {
        return !(showFFT || plot2d || plotXY);
    }

    public boolean isTriggerEnabled() {
        return triggerEnabled;
    }

    public void setTriggerEnabled(boolean enabled) {
        triggerEnabled = enabled;
        if (!enabled) {
            timeBaseStartIndexOverride = null;
            timeBaseRightEdgeTime = simulator().t;
            triggerFrame = null;
            rearmSingleTrigger();
        }
    }

    public int getTriggerMode() {
        return triggerMode;
    }

    public void setTriggerMode(int mode) {
        triggerMode = mode;
        if (triggerMode != TRIG_MODE_NORMAL && triggerMode != TRIG_MODE_SINGLE) {
            triggerFrame = null;
        }
        rearmSingleTrigger();
    }

    public int getTriggerSlope() {
        return triggerSlope;
    }

    public void setTriggerSlope(int slope) {
        triggerSlope = slope;
    }

    public double getTriggerLevel() {
        return triggerLevel;
    }

    public void setTriggerLevel(double level) {
        triggerLevel = level;
    }

    public double getTriggerHoldoff() {
        return triggerHoldoff;
    }

    public void setTriggerHoldoff(double holdoffSeconds) {
        triggerHoldoff = Math.max(0, holdoffSeconds);
    }

    public double getTriggerPosition() {
        return triggerPosition;
    }

    public void setTriggerPosition(double posFraction) {
        triggerPosition = Math.max(0, Math.min(1, posFraction));
    }

    public int getTriggerSource() {
        return triggerSource;
    }

    public void setTriggerSource(int idx) {
        triggerSource = idx;
    }

    public void rearmSingleTrigger() {
        singleTriggered = false;
        singleFrozenStartIndex = 0;
        singleFrozenRightEdgeTime = 0.0;
        singleFrozenTriggerTime = 0.0;
        lastTriggeredStartIndex = -1;
        lastTriggerTime = -1;
        if (triggerMode == TRIG_MODE_SINGLE) {
            triggerFrame = null;
        }
    }

    public boolean isHistoryEnabled() {
        return historyEnabled;
    }

    public void setHistoryEnabled(boolean enabled) {
        historyEnabled = enabled;
        if (!enabled) {
            clearHistory();
        }
    }

    public int getHistoryDepth() {
        return historyDepth;
    }

    public void setHistoryDepth(int depth) {
        historyDepth = Math.max(1, Math.min(64, depth));
        trimHistoryToDepth();
    }

    public int getHistoryCaptureMode() {
        return historyCaptureMode;
    }

    public void setHistoryCaptureMode(int mode) {
        historyCaptureMode = mode;
    }

    public int getHistorySource() {
        return historySource;
    }

    public void setHistorySource(int idx) {
        historySource = idx;
    }

    public void clearHistory() {
        historyFrames.clear();
        lastHistoryCapturedStartIndex = -1;
    }

    public void captureHistoryNow() {
        if (!historyEnabled || !isHistoryAvailable()) {
            return;
        }
        ScopePlot p = getHistorySourcePlot();
        if (p == null) {
            return;
        }
        captureHistoryFrame(p, getPlotStartIndex(p));
    }

    private void trimHistoryToDepth() {
        while (historyFrames.size() > historyDepth) {
            historyFrames.remove(0);
        }
    }

    private ScopePlot getTriggerSourcePlot() {
        if (visiblePlots.isEmpty()) {
            return null;
        }
        int idx = triggerSource;
        if (idx < 0 || idx >= visiblePlots.size()) {
            idx = 0;
        }
        return visiblePlots.get(idx);
    }

    private ScopePlot getHistorySourcePlot() {
        if (visiblePlots.isEmpty()) {
            return null;
        }
        int idx = historySource;
        if (idx < 0 || idx >= visiblePlots.size()) {
            idx = 0;
        }
        return visiblePlots.get(idx);
    }

    private int getPlotStartIndex(ScopePlot plot) {
        if (timeBaseStartIndexOverride != null) {
            return timeBaseStartIndexOverride;
        }
        return plot.startIndex(rect.width);
    }

    private double getTimeBaseRightEdgeTime() {
        if (timeBaseStartIndexOverride != null) {
            return timeBaseRightEdgeTime;
        }
        return simulator().t;
    }

    private int getTriggerPixelX() {
        int width = rect.width;
        if (width <= 0) {
            return 0;
        }
        int pre = (int) Math.round(width * triggerPosition);
        if (pre < 0) pre = 0;
        if (pre > width - 1) pre = width - 1;
        return pre;
    }

    private double getTimeBaseTriggerTime() {
        return timeBaseTriggerTime;
    }

    private int findTriggerIndex(ScopePlot plot, int pre, int post) {
        if (plot == null || plot.sampleValues == null) {
            return -1;
        }
        int width = rect.width;
        if (width < 4) {
            return -1;
        }
        int mask = scopePointCount - 1;
        int ptr = plot.ptr;
        int maxSearch = Math.min(scopePointCount - 2, Math.max(width * 4, width + post + 2));

        double dt = simulator().maxTimeStep * speed;
        for (int stepsBack = post; stepsBack < maxSearch; stepsBack++) {
            int cur = (ptr - stepsBack) & mask;
            int prev = (cur - 1) & mask;
            double a = plot.sampleValues[prev];
            double b = plot.sampleValues[cur];
            boolean crossed;
            if (triggerSlope == TRIG_SLOPE_FALLING) {
                crossed = (a > triggerLevel && b <= triggerLevel);
            } else {
                crossed = (a < triggerLevel && b >= triggerLevel);
            }
            if (!crossed) {
                continue;
            }
            double triggerTime = simulator().t - stepsBack * dt;
            if (triggerHoldoff > 0 && lastTriggerTime >= 0 && (triggerTime - lastTriggerTime) < triggerHoldoff) {
                continue;
            }
            lastTriggerTime = triggerTime;
            return cur;
        }
        return -1;
    }

    private void updateTimeBaseForDraw() {
        // If trigger mode is NORMAL (waiting) or SINGLE (armed), and no trigger is found,
        // we should keep displaying the previous frame rather than rolling/free-running.
        final Integer prevStartIndexOverride = timeBaseStartIndexOverride;
        final double prevRightEdgeTime = timeBaseRightEdgeTime;
        final double prevTriggerTime = timeBaseTriggerTime;
        final int prevDisplayedStartIndex = lastDisplayedStartIndex;

        timeBaseStartIndexOverride = null;
        timeBaseRightEdgeTime = simulator().t;
        timeBaseTriggerTime = simulator().t;

        ScopePlot plot = null;
        if (triggerEnabled && isTriggerAvailable() && !visiblePlots.isEmpty()) {
            plot = getTriggerSourcePlot();
        }

        int width = rect.width;

        if (plot != null) {
            int pre = getTriggerPixelX();
            int post = width - pre;
            int mask = scopePointCount - 1;

            double dt = simulator().maxTimeStep * speed;

            if (triggerMode == TRIG_MODE_SINGLE && singleTriggered) {
                // Freeze the timebase as captured at the trigger moment.
                if (triggerFrame != null) {
                    timeBaseStartIndexOverride = triggerFrame.startIndex;
                    timeBaseRightEdgeTime = triggerFrame.rightEdgeTime;
                    timeBaseTriggerTime = triggerFrame.triggerTime;
                } else {
                    timeBaseStartIndexOverride = singleFrozenStartIndex;
                    timeBaseRightEdgeTime = singleFrozenRightEdgeTime;
                    timeBaseTriggerTime = singleFrozenTriggerTime;
                }
            } else {
                int triggerIndex = findTriggerIndex(plot, pre, post);
                if (triggerIndex >= 0) {
                    int startIndex = (triggerIndex - pre) & mask;
                    timeBaseStartIndexOverride = startIndex;
                    lastTriggeredStartIndex = startIndex;

                    int endIndex = (startIndex + width - 1) & mask;
                    int stepsBackEnd = (plot.ptr - endIndex) & mask;
                    timeBaseRightEdgeTime = simulator().t - stepsBackEnd * dt;
                    timeBaseTriggerTime = timeBaseRightEdgeTime - post * dt;

                    if (triggerMode == TRIG_MODE_NORMAL || triggerMode == TRIG_MODE_SINGLE) {
                        // Capture a stable display frame so the ring buffer overwrites don't change the display.
                        captureTriggerFrame(startIndex, timeBaseRightEdgeTime, timeBaseTriggerTime);
                    }

                    if (historyEnabled && historyCaptureMode == HISTORY_CAPTURE_ON_TRIGGER) {
                        if (lastHistoryCapturedStartIndex != startIndex) {
                            captureHistoryFrame(getHistorySourcePlot(), startIndex);
                            lastHistoryCapturedStartIndex = startIndex;
                        }
                    }

                    if (triggerMode == TRIG_MODE_SINGLE) {
                        singleTriggered = true;
                        singleFrozenStartIndex = startIndex;
                        singleFrozenRightEdgeTime = timeBaseRightEdgeTime;
                        singleFrozenTriggerTime = timeBaseTriggerTime;
                    }
                } else if (triggerMode == TRIG_MODE_NORMAL || triggerMode == TRIG_MODE_SINGLE) {
                    // no trigger found
                    // NORMAL: hold the last displayed frame until a trigger is found.
                    // SINGLE (armed): also hold the last displayed frame until the first trigger.
                    if (triggerFrame == null || triggerFrame.width != rect.width || triggerFrame.plotCount != plots.size()) {
                        // Initialize a stable frame from the current rolling window.
                        int startIndex = plot.startIndex(width);
                        double rightEdgeTime = simulator().t;
                        double triggerTime = rightEdgeTime - post * dt;
                        captureTriggerFrame(startIndex, rightEdgeTime, triggerTime);
                    }

                    if (triggerFrame != null) {
                        timeBaseStartIndexOverride = triggerFrame.startIndex;
                        timeBaseRightEdgeTime = triggerFrame.rightEdgeTime;
                        timeBaseTriggerTime = triggerFrame.triggerTime;
                    } else {
                        timeBaseStartIndexOverride = (prevStartIndexOverride != null) ? prevStartIndexOverride : prevDisplayedStartIndex;
                        timeBaseRightEdgeTime = prevRightEdgeTime;
                        timeBaseTriggerTime = prevTriggerTime;
                    }
                }
                // AUTO: fall back to rolling (null override)
            }
        }

        // Record the displayed start index so we can freeze from a rolling state next draw.
        ScopePlot recordPlot = (plot != null) ? plot : (!plots.isEmpty() ? plots.get(0) : null);
        if (recordPlot != null && width > 0) {
            lastDisplayedStartIndex = (timeBaseStartIndexOverride != null) ? timeBaseStartIndexOverride : recordPlot.startIndex(width);
        }
    }

    private void captureHistoryFrame(ScopePlot plot, int startIndex) {
        if (plot == null || plot.elm == null || rect.width < 2) {
            return;
        }
        int width = rect.width;
        if (plot.minValues == null || plot.maxValues == null) {
            return;
        }
        int mask = scopePointCount - 1;
        double[] min = new double[width];
        double[] max = new double[width];
        for (int i = 0; i < width; i++) {
            int ip = (startIndex + i) & mask;
            min[i] = plot.minValues[ip];
            max[i] = plot.maxValues[ip];
        }
        historyFrames.add(new HistoryFrame(width, min, max, plot.color));
        trimHistoryToDepth();
    }

    public void setManualScaleValue(int plotId, double d) {
        if (plotId >= visiblePlots.size()) {
            return; // Shouldn't happen, but just in case...
        }
        clear2dView();
        visiblePlots.get(plotId).manScale = d;
        visiblePlots.get(plotId).manScaleSet = true;
    }

    public double getScaleValue() {
        if (visiblePlots.isEmpty()) {
            return 0;
        }
        ScopePlot p = visiblePlots.get(0);
        return scale[p.units];
    }

    public String getScaleUnitsText() {
        if (visiblePlots.isEmpty()) {
            return "V";
        }
        ScopePlot p = visiblePlots.get(0);
        return getScaleUnitsText(p.units);
    }

    public static String getScaleUnitsText(int units) {
        switch (units) {
            case UNITS_A:
                return "A";
            case UNITS_OHMS:
                return Locale.ohmString;
            case UNITS_W:
                return "W";
            default:
                return "V";
        }
    }

    public void setManDivisions(int d) {
        manDivisions = lastManDivisions = d;
    }

    boolean active() {
        return !plots.isEmpty() && plots.get(0).elm != null;
    }

    void initialize() {
        resetGraph();
        scale[UNITS_W] = scale[UNITS_OHMS] = scale[UNITS_V] = 5;
        scale[UNITS_A] = .1;
        scaleX = 5;
        scaleY = .1;
        speed = 64;
        showMax = true;
        showV = showI = false;
        showScale = showFreq = manualScale = showMin = showElmInfo = false;
        showFFT = false;
        plot2d = false;
        if (!loadDefaults()) {
            // set showV and showI appropriately depending on what plots are present
            for (ScopePlot plot : plots) {
                if (plot.units == UNITS_V) {
                    showV = true;
                }
                if (plot.units == UNITS_A) {
                    showI = true;
                }
            }
        }
    }

    void calcVisiblePlots() {
        visiblePlots = new Vector<>();
        int vc = 0, ac = 0, oc = 0;
        if (!plot2d) {
            for (ScopePlot plot : plots) {
                if (plot.units == UNITS_V) {
                    if (showV) {
                        visiblePlots.add(plot);
                        plot.assignColor(vc++);
                    }
                } else if (plot.units == UNITS_A) {
                    if (showI) {
                        visiblePlots.add(plot);
                        plot.assignColor(ac++);
                    }
                } else {
                    visiblePlots.add(plot);
                    plot.assignColor(oc++);
                }
            }
        } else { // In 2D mode the visible plots are the first two plots
            for (int i = 0; (i < 2) && (i < plots.size()); i++) {
                visiblePlots.add(plots.get(i));
            }
        }
    }

    public void setRect(Rectangle r) {
        int w = this.rect.width;
        this.rect = r;
        if (this.rect.width != w) {
            resetGraph();
        }
    }

    int getWidth() {
        return rect.width;
    }

    int rightEdge() {
        return rect.x + rect.width;
    }

    public void setElm(CircuitElm ce) {
        plots = new Vector<>();
        if (ce instanceof TransistorElm) {
            setValue(VAL_VCE, ce);
        } else {
            setValue(0, ce);
        }
        initialize();
    }

    void addElm(CircuitElm ce) {
        if (ce instanceof TransistorElm) {
            addValue(VAL_VCE, ce);
        } else {
            addValue(0, ce);
        }
    }

    void setValue(int val) {
        if (plots.size() > 2 || plots.isEmpty()) {
            return;
        }
        CircuitElm ce = plots.firstElement().elm;
        if (plots.size() == 2 && plots.get(1).elm != ce) {
            return;
        }
        plot2d = plotXY = false;
        setValue(val, ce);
    }

    void addValue(int val, CircuitElm ce) {
        if (val == 0) {
            plots.add(new ScopePlot(cirSim, circuitDocument, ce, UNITS_V, VAL_VOLTAGE, getManScaleFromMaxScale(UNITS_V, false)));

            // create plot for current if applicable
            if (ce != null &&
                    cirSim.menuManager.dotsCheckItem.getState() &&
                    !(ce instanceof OutputElm ||
                            ce instanceof LogicOutputElm ||
                            ce instanceof AudioOutputElm ||
                            ce instanceof ProbeElm)) {
                plots.add(new ScopePlot(cirSim, circuitDocument, ce, UNITS_A, VAL_CURRENT, getManScaleFromMaxScale(UNITS_A, false)));
            }
        } else {
            int u = ce.getScopeUnits(val);
            plots.add(new ScopePlot(cirSim, circuitDocument, ce, u, val, getManScaleFromMaxScale(u, false)));
            if (u == UNITS_V) {
                showV = true;
            }
            if (u == UNITS_A) {
                showI = true;
            }
        }
        calcVisiblePlots();
        resetGraph();
    }

    void setValue(int val, CircuitElm ce) {
        plots = new Vector<>();
        addValue(val, ce);
    }

    void setValues(int val, int ival, CircuitElm ce, CircuitElm yelm) {
        if (ival > 0) {
            plots = new Vector<>();
            plots.add(new ScopePlot(cirSim, circuitDocument, ce, ce.getScopeUnits(val), val, getManScaleFromMaxScale(ce.getScopeUnits(val), false)));
            plots.add(new ScopePlot(cirSim, circuitDocument, ce, ce.getScopeUnits(ival), ival, getManScaleFromMaxScale(ce.getScopeUnits(ival), false)));
            return;
        }
        if (yelm != null) {
            plots = new Vector<>();
            plots.add(new ScopePlot(cirSim, circuitDocument, ce, ce.getScopeUnits(val), 0, getManScaleFromMaxScale(ce.getScopeUnits(val), false)));
            plots.add(new ScopePlot(cirSim, circuitDocument, yelm, ce.getScopeUnits(ival), 0, getManScaleFromMaxScale(ce.getScopeUnits(val), false)));
            return;
        }
        setValue(val);
    }

    public void setText(String s) {
        text = s;
    }

    public String getText() {
        return text;
    }

    public boolean showingValue(int v) {
        for (ScopePlot sp : plots) {
            if (sp.value != v) {
                return false;
            }
        }
        return true;
    }

    // returns true if we have a plot of voltage and nothing else (except current).
    // The default case is a plot of voltage and current, so we're basically checking if that case is true.
    boolean showingVoltageAndMaybeCurrent() {
        boolean gotv = false;
        for (ScopePlot sp : plots) {
            if (sp.value == VAL_VOLTAGE) {
                gotv = true;
            } else if (sp.value != VAL_CURRENT) {
                return false;
            }
        }
        return gotv;
    }


    void combine(Scope s) {
        plots = visiblePlots;
        plots.addAll(s.visiblePlots);
        s.plots.removeAllElements();
        calcVisiblePlots();
    }

    // separate this scope's plots into separate scopes and return them in arr[pos], arr[pos+1], etc.  return new length of array.
    int separate(Scope[] arr, int pos) {
        ScopePlot lastPlot = null;
        for (ScopePlot sp : visiblePlots) {
            if (pos >= arr.length) {
                return pos;
            }
            Scope s = new Scope(cirSim, getActiveDocument());
            if (lastPlot != null && lastPlot.elm == sp.elm && lastPlot.value == VAL_VOLTAGE && sp.value == VAL_CURRENT) {
                continue;
            }
            s.setValue(sp.value, sp.elm);
            s.position = pos;
            arr[pos++] = s;
            lastPlot = sp;
            s.setFlags(getFlags());
            s.setSpeed(speed);
        }
        return pos;
    }

    void removePlot(int plot) {
        if (plot < visiblePlots.size()) {
            ScopePlot p = visiblePlots.get(plot);
            plots.remove(p);
            calcVisiblePlots();
        }
    }

    /**
     * Calculates the grid size in pixels for 2D plots
     * @param width width of the plot area
     * @param height height of the plot area
     * @param manDivisions number of manual divisions
     * @return grid size in pixels
     */
    public static double calc2dGridPx(int width, int height, int manDivisions) {
        int m = Math.min(width, height);
        return ((double) (m) / 2) / ((double) (manDivisions) / 2 + 0.05);
    }

    // called for each timestep
    public void timeStep() {
        for (ScopePlot plot : plots) {
            plot.timeStep();
        }

        // For 2d plots we draw here rather than in the drawing routine
        if (plot2d && graphics != null && plots.size() >= 2) {
            double v = plots.get(0).lastValue;
            double yval = plots.get(1).lastValue;
            int x, y;
            if (!isManualScale()) {
                boolean newscale = false;
                while (v > scaleX || v < -scaleX) {
                    scaleX *= 2;
                    newscale = true;
                }
                while (yval > scaleY || yval < -scaleY) {
                    scaleY *= 2;
                    newscale = true;
                }
                if (newscale) {
                    clear2dView();
                }
                double xa = v / scaleX;
                double ya = yval / scaleY;
                x = (int) (rect.width * (1 + xa) * .499);
                y = (int) (rect.height * (1 - ya) * .499);
            } else {
                double gridPx = calc2dGridPx(rect.width, rect.height, manDivisions);
                x = (int) (rect.width * .499 + (v / plots.get(0).manScale) * gridPx + gridPx * manDivisions * (double) (plots.get(0).manVPosition) / (double) (V_POSITION_STEPS));
                y = (int) (rect.height * .499 - (yval / plots.get(1).manScale) * gridPx - gridPx * manDivisions * (double) (plots.get(1).manVPosition) / (double) (V_POSITION_STEPS));

            }
            drawTo(x, y);
        }
    }

    void drawTo(int x2, int y2) {
        if (draw_ox == -1) {
            draw_ox = x2;
            draw_oy = y2;
        }
        graphics.setStrokeStyle(ColorSettings.get().getForegroundColor().getHexValue());
        graphics.drawLine(draw_ox, draw_oy, x2, y2);
        draw_ox = x2;
        draw_oy = y2;
    }

    void clear2dView() {
        if (graphics != null) {
            // Use slightly different color from background for scope area
            if (ColorSettings.get().isPrintable()) {
                graphics.setColor("#eee");
            } else {
                graphics.setColor("#111");
            }
            graphics.fillRect(0, 0, rect.width - 1, rect.height - 1);
        }
        draw_ox = draw_oy = -1;
    }

    public void setMaxScale(boolean s) {
        // This procedure is added to set maxscale to an explicit value instead of just having a toggle
        // We call the toggle procedure first because it has useful side-effects and then set the value explicitly.
        maxScale();
        maxScale = s;
    }

    void maxScale() {
        if (plot2d) {
            double x = 1e-8;
            scale[UNITS_V] *= x;
            scale[UNITS_A] *= x;
            scale[UNITS_OHMS] *= x;
            scale[UNITS_W] *= x;
            scaleX *= x; // For XY plots
            scaleY *= x;
            return;
        }
        // toggle max scale.  This isn't on by default because, for the examples, we sometimes want two plots
        // matched to the same scale so we can show one is larger.  Also, for some fast-moving scopes
        // (like for AM detector), the amplitude varies over time but you can't see that if the scale is
        // constantly adjusting.  It's also nice to set the default scale to hide noise and to avoid
        // having the scale moving around a lot when a circuit starts up.
        maxScale = !maxScale;
        showNegative = false;
    }

    void drawFFTVerticalGridLines(Graphics g) {
        // Draw x-grid lines and label the frequencies in the FFT that they point to.
        int prevEnd = 0;
        int divs = 20;
        double maxFrequency = 1 / (simulator().maxTimeStep * speed * divs * 2);
        for (int i = 0; i < divs; i++) {
            int x = rect.width * i / divs;
            if (x < prevEnd) {
                continue;
            }
            String s = ((int) Math.round(i * maxFrequency)) + "Hz";
            int sWidth = (int) Math.ceil(g.measureWidth(s));
            prevEnd = x + sWidth + 4;
            if (i > 0) {
                g.setColor("#880000");
                g.drawLine(x, 0, x, rect.height);
            }
            g.setColor("#FF0000");
            g.drawString(s, x + 2, rect.height);
        }
    }

    void drawFFT(Graphics g) {
        if (fft == null || fft.getSize() != scopePointCount) {
            fft = new FFT(scopePointCount);
        }
        double[] real = new double[scopePointCount];
        double[] imag = new double[scopePointCount];
        ScopePlot plot = (visiblePlots.size() == 0) ? plots.firstElement() : visiblePlots.firstElement();
        double[] maxV = plot.maxValues;
        double[] minV = plot.minValues;
        int ptr = plot.ptr;
        for (int i = 0; i < scopePointCount; i++) {
            int ii = (ptr - i + scopePointCount) & (scopePointCount - 1);
            // need to average max and min or else it could cause average of function to be > 0, which
            // produces spike at 0 Hz that hides rest of spectrum
            real[i] = .5 * (maxV[ii] + minV[ii]);
            imag[i] = 0;
        }
        fft.fft(real, imag, true);
        double maxM = 1e-8;
        for (int i = 0; i < scopePointCount / 2; i++) {
            double m = fft.magnitude(real[i], imag[i]);
            if (m > maxM) {
                maxM = m;
            }
        }
        int prevX = 0;
        g.setColor("#FF0000");
        if (!logSpectrum) {
            int prevHeight = 0;
            int y = (rect.height - 1) - 12;
            for (int i = 0; i < scopePointCount / 2; i++) {
                int x = 2 * i * rect.width / scopePointCount;
                // rect.width may be greater than or less than scopePointCount/2,
                // so x may be greater than or equal to prevX.
                double magnitude = fft.magnitude(real[i], imag[i]);
                int height = (int) ((magnitude * y) / maxM);
                if (x != prevX) {
                    g.drawLine(prevX, y - prevHeight, x, y - height);
                }
                prevHeight = height;
                prevX = x;
            }
        } else {
            int y0 = 5;
            int prevY = 0;
            double ymult = rect.height / 10.;
            double val0 = Math.log(scale[plot.units]) * ymult;
            for (int i = 0; i < scopePointCount / 2; i++) {
                int x = 2 * i * rect.width / scopePointCount;
                // rect.width may be greater than or less than scopePointCount/2,
                // so x may be greater than or equal to prevX.
                double val = Math.log(fft.magnitude(real[i], imag[i]));
                int y = y0 - (int) (val * ymult - val0);
                if (x != prevX) {
                    g.drawLine(prevX, prevY, x, y);
                }
                prevY = y;
                prevX = x;
            }
        }
    }

    void drawSettingsWheel(Graphics graphics) {
        final int outR = 8;
        final int inR = 5;
        final int inR45 = 4;
        final int outR45 = 6;
        if (showSettingsWheel()) {
            graphics.save();
            if (cursorInSettingsWheel()) {
                graphics.setColor(ColorSettings.get().getSelectColor());
            } else {
                graphics.setColor(Color.dark_gray);
            }
            graphics.translate(rect.x + 18, rect.y + rect.height - 18);
            CircuitElm.drawThickCircle(graphics, 0, 0, inR);
            CircuitElm.drawThickLine(graphics, -outR, 0, -inR, 0);
            CircuitElm.drawThickLine(graphics, outR, 0, inR, 0);
            CircuitElm.drawThickLine(graphics, 0, -outR, 0, -inR);
            CircuitElm.drawThickLine(graphics, 0, outR, 0, inR);
            CircuitElm.drawThickLine(graphics, -outR45, -outR45, -inR45, -inR45);
            CircuitElm.drawThickLine(graphics, outR45, -outR45, inR45, -inR45);
            CircuitElm.drawThickLine(graphics, -outR45, outR45, -inR45, inR45);
            CircuitElm.drawThickLine(graphics, outR45, outR45, inR45, inR45);
            graphics.restore();
        }
    }

    void draw2d(Graphics graphics) {
        Context2d imageContext = this.graphics.getContext();
        if (imageContext == null) {
            return;
        }
        graphics.save();
        graphics.translate(rect.x, rect.y);
        graphics.clipRect(0, 0, rect.width, rect.height);

        alphaCounter++;

        if (alphaCounter > 2) {
            // fade out plot
            alphaCounter = 0;
            imageContext.setGlobalAlpha(0.01);
            imageContext.setFillStyle(ColorSettings.get().getBackgroundColor().getHexValue());
            imageContext.fillRect(0, 0, rect.width, rect.height);
            imageContext.setGlobalAlpha(1.0);
        }

        graphics.drawImage(imageContext.getCanvas(), 0.0, 0.0);
        graphics.setColor(ColorSettings.get().getBackgroundColor());
        graphics.fillOval(draw_ox - 2, draw_oy - 2, 5, 5);
        // Axis
        graphics.setColor(ColorSettings.get().getPositiveColor());
        graphics.drawLine(0, rect.height / 2, rect.width - 1, rect.height / 2);
        if (!plotXY) {
            graphics.setColor(Color.yellow);
        }
        graphics.drawLine(rect.width / 2, 0, rect.width / 2, rect.height - 1);
        if (isManualScale()) {
            double gridPx = calc2dGridPx(rect.width, rect.height, manDivisions);
            graphics.setColor("#404040");
            for (int i = -manDivisions; i <= manDivisions; i++) {
                if (i != 0) {
                    graphics.drawLine((int) (gridPx * i) + rect.width / 2, 0, (int) (gridPx * i) + rect.width / 2, rect.height);
                }
                graphics.drawLine(0, (int) (gridPx * i) + rect.height / 2, rect.width, (int) (gridPx * i) + rect.height / 2);
            }
        }
        textY = 10;
        graphics.setColor(ColorSettings.get().getBackgroundColor());
        if (text != null) {
            drawInfoText(graphics, text);
        }
        if (showScale && plots.size() >= 2 && isManualScale()) {
            ScopePlot px = plots.get(0);
            String sx = px.getUnitText(px.manScale);
            ScopePlot py = plots.get(1);
            String sy = py.getUnitText(py.manScale);
            drawInfoText(graphics, "X=" + sx + "/div, Y=" + sy + "/div");
        }
        graphics.restore();
        drawSettingsWheel(graphics);
        CircuitEditor circuitEditor = circuitEditor();
        if (!cirSim.dialogIsShowing() && rect.contains(circuitEditor().mouseCursorX, circuitEditor().mouseCursorY) && plots.size() >= 2) {
            double gridPx = calc2dGridPx(rect.width, rect.height, manDivisions);
            String[] info = new String[2];
            ScopePlot px = plots.get(0);
            ScopePlot py = plots.get(1);
            double xValue;
            double yValue;
            if (isManualScale()) {
                xValue = px.manScale * ((double) (circuitEditor().mouseCursorX - rect.x - rect.width / 2) / gridPx - manDivisions * px.manVPosition / (double) (V_POSITION_STEPS));
                yValue = py.manScale * ((double) (-circuitEditor.mouseCursorY + rect.y + rect.height / 2) / gridPx - manDivisions * py.manVPosition / (double) (V_POSITION_STEPS));
            } else {
                xValue = ((double) (circuitEditor().mouseCursorX - rect.x) / (0.499 * (double) (rect.width)) - 1.0) * scaleX;
                yValue = -((double) (circuitEditor().mouseCursorY - rect.y) / (0.499 * (double) (rect.height)) - 1.0) * scaleY;
            }
            info[0] = px.getUnitText(xValue);
            info[1] = py.getUnitText(yValue);

            drawCursorInfo(graphics, info, 2, circuitEditor().mouseCursorX, true);

        }
    }


    boolean showSettingsWheel() {
        return rect.height > 100 && rect.width > 100;
    }

    boolean cursorInSettingsWheel() {
        CircuitEditor circuitEditor = circuitEditor();
        return showSettingsWheel() &&
                circuitEditor.mouseCursorX >= rect.x &&
                circuitEditor.mouseCursorX <= rect.x + 36 &&
                circuitEditor.mouseCursorY >= rect.y + rect.height - 36 &&
                circuitEditor.mouseCursorY <= rect.y + rect.height;
    }

    // does another scope have something selected?
    void checkForSelectionElsewhere() {
        // if mouse is here, then selection is already set by checkForSelection()
        if (cursorScope == this) {
            return;
        }

        if (cursorScope == null || visiblePlots.isEmpty()) {
            selectedPlot = -1;
            return;
        }

        // find a plot with same units as selected plot
        for (int i = 0; i != visiblePlots.size(); i++) {
            ScopePlot p = visiblePlots.get(i);
            if (p.units == cursorUnits) {
                selectedPlot = i;
                return;
            }
        }

        // default if we can't find anything with matching units
        selectedPlot = 0;
    }

    public void draw(Graphics graphics) {
        if (plots.isEmpty()) {
            return;
        }

        // reset if timestep changed
        if (scopeTimeStep != simulator().maxTimeStep) {
            scopeTimeStep = simulator().maxTimeStep;
            resetGraph();
        }


        if (plot2d) {
            draw2d(graphics);
            return;
        }

        drawSettingsWheel(graphics);
        graphics.save();
        graphics.setColor(Color.red);
        graphics.translate(rect.x, rect.y);
        graphics.clipRect(0, 0, rect.width, rect.height);

        updateTimeBaseForDraw();

        if (showFFT) {
            drawFFTVerticalGridLines(graphics);
            drawFFT(graphics);
        }

        for (int i = 0; i != UNITS_COUNT; i++) {
            reduceRange[i] = false;
            if (maxScale && !manualScale) {
                scale[i] = 1e-4;
            }
        }

        somethingSelected = false;  // is one of our plots selected?

        ScopeManager scopeManager = scopeManager();

        for (ScopePlot plot : visiblePlots) {
            calcPlotScale(plot);
            if (scopeManager.scopeSelected == -1 && plot.elm != null && plot.elm.isMouseElm()) {
                somethingSelected = true;
            }
            reduceRange[plot.units] = true;
        }

        boolean sel = scopeManager.scopeMenuIsSelected(this);

        checkForSelectionElsewhere();
        if (selectedPlot >= 0) {
            somethingSelected = true;
        }

        drawGridLines = true;
        boolean allPlotsSameUnits = true;
        for (int i = 1; i < visiblePlots.size(); i++) {
            if (visiblePlots.get(i).units != visiblePlots.get(0).units) {
                allPlotsSameUnits = false; // Don't draw horizontal grid lines unless all plots are in same units
            }
        }

        if ((allPlotsSameUnits || showMax || showMin) && !visiblePlots.isEmpty()) {
            calcMaxAndMin(visiblePlots.firstElement().units);
        }

        // draw volt plots on top (last), then current plots underneath, then everything else
        for (int i = 0; i != visiblePlots.size(); i++) {
            if (visiblePlots.get(i).units > UNITS_A && i != selectedPlot) {
                drawPlot(graphics, visiblePlots.get(i), allPlotsSameUnits, false, sel);
            }
        }
        for (int i = 0; i != visiblePlots.size(); i++) {
            if (visiblePlots.get(i).units == UNITS_A && i != selectedPlot) {
                drawPlot(graphics, visiblePlots.get(i), allPlotsSameUnits, false, sel);
            }
        }
        for (int i = 0; i != visiblePlots.size(); i++) {
            if (visiblePlots.get(i).units == UNITS_V && i != selectedPlot) {
                drawPlot(graphics, visiblePlots.get(i), allPlotsSameUnits, false, sel);
            }
        }
        // draw selection on top.  only works if selection chosen from scope
        if (selectedPlot >= 0 && selectedPlot < visiblePlots.size()) {
            drawPlot(graphics, visiblePlots.get(selectedPlot), allPlotsSameUnits, true, sel);
        }

        drawInfoTexts(graphics);

        graphics.restore();

        drawCursor(graphics);

        if (plots.get(0).ptr > 5 && !manualScale) {
            for (int i = 0; i != UNITS_COUNT; i++) {
                if (scale[i] > 1e-4 && reduceRange[i]) {
                    scale[i] /= 2;
                }
            }
        }

        if ((properties != null) && properties.isShowing()) {
            properties.refreshDraw();
        }

    }


    // calculate maximum and minimum values for all plots of given units
    void calcMaxAndMin(int units) {
        maxValue = -1e8;
        minValue = 1e8;
        boolean useFrame = shouldUseTriggerFrameForDisplay();
        for (ScopePlot plot : visiblePlots) {
            if (plot.units != units) {
                continue;
            }

            if (useFrame) {
                int pi = getPlotIndex(plot);
                if (pi < 0 || triggerFrame.maxValues[pi] == null || triggerFrame.minValues[pi] == null) {
                    continue;
                }
                double[] maxV = triggerFrame.maxValues[pi];
                double[] minV = triggerFrame.minValues[pi];
                for (int i = 0; i != rect.width; i++) {
                    if (maxV[i] > maxValue) {
                        maxValue = maxV[i];
                    }
                    if (minV[i] < minValue) {
                        minValue = minV[i];
                    }
                }
                continue;
            }

            int ipa = getPlotStartIndex(plot);
            double[] maxV = plot.maxValues;
            double[] minV = plot.minValues;
            for (int i = 0; i != rect.width; i++) {
                int ip = (i + ipa) & (scopePointCount - 1);
                if (maxV[ip] > maxValue) {
                    maxValue = maxV[ip];
                }
                if (minV[ip] < minValue) {
                    minValue = minV[ip];
                }
            }
        }
    }

    // adjust scale of a plot
    void calcPlotScale(ScopePlot plot) {
        if (manualScale) {
            return;
        }
        boolean useFrame = shouldUseTriggerFrameForDisplay();
        int ipa = 0;
        double[] maxV;
        double[] minV;
        if (useFrame) {
            int pi = getPlotIndex(plot);
            if (pi < 0 || triggerFrame.maxValues[pi] == null || triggerFrame.minValues[pi] == null) {
                return;
            }
            maxV = triggerFrame.maxValues[pi];
            minV = triggerFrame.minValues[pi];
        } else {
            ipa = getPlotStartIndex(plot);
            maxV = plot.maxValues;
            minV = plot.minValues;
        }
        double max = 0;
        double gridMax = scale[plot.units];
        for (int i = 0; i != rect.width; i++) {
            if (useFrame) {
                if (maxV[i] > max) {
                    max = maxV[i];
                }
                if (minV[i] < -max) {
                    max = -minV[i];
                }
            } else {
                int ip = (i + ipa) & (scopePointCount - 1);
                if (maxV[ip] > max) {
                    max = maxV[ip];
                }
                if (minV[ip] < -max) {
                    max = -minV[ip];
                }
            }
        }
        // scale fixed at maximum?
        if (maxScale) {
            gridMax = Math.max(max, gridMax);
        } else {
            // adjust in powers of two
            while (max > gridMax) {
                gridMax *= 2;
            }
        }
        scale[plot.units] = gridMax;
    }

    /**
     * Calculates the time step for the scope grid X axis
     * @return grid step in seconds
     */
    public double calcGridStepX() {
        int multptr = 0;
        double gsx = 1e-15;

        double ts = simulator().maxTimeStep * speed;
        while (gsx < ts * 20) {
            gsx *= multa[(multptr++) % 3];
        }
        return gsx;
    }

    /**
     * Calculates the maximum value of the grid from the manual scale settings
     * @param manDivisions number of manual divisions
     * @param manScale manual scale value
     * @return max value
     */
    public static double getGridMaxFromManScale(int manDivisions, double manScale) {
        return ((double) (manDivisions) / 2 + 0.05) * manScale;
    }

    void drawPlot(Graphics g, ScopePlot plot, boolean allPlotsSameUnits, boolean selected, boolean allSelected) {
        if (plot.elm == null) {
            return;
        }
        int multptr = 0;
        int x = 0;
        final int maxy = (rect.height - 1) / 2;

        String color = (somethingSelected) ? "#A0A0A0" : plot.color;
        if (allSelected || (scopeManager().scopeSelected == -1 && plot.elm.isMouseElm())) {
            color = ColorSettings.get().getSelectColor().getHexValue();
        } else if (selected) {
            color = plot.color;
        }
        boolean useFrame = shouldUseTriggerFrameForDisplay();
        int ipa = 0;
        double[] maxV;
        double[] minV;
        if (useFrame) {
            int pi = getPlotIndex(plot);
            if (pi < 0 || triggerFrame.maxValues[pi] == null || triggerFrame.minValues[pi] == null) {
                return;
            }
            maxV = triggerFrame.maxValues[pi];
            minV = triggerFrame.minValues[pi];
        } else {
            ipa = getPlotStartIndex(plot);
            maxV = plot.maxValues;
            minV = plot.minValues;
        }
        double gridMax;
        double gridMid;
        double positionOffset;


        // Calculate the max value (positive) to show and the value at the mid point of the grid
        if (!isManualScale()) {
            gridMax = scale[plot.units];
            gridMid = 0;
            positionOffset = 0;
            if (allPlotsSameUnits) {
                // if we don't have overlapping scopes of different units, we can move zero around.
                // Put it at the bottom if the scope is never negative.
                double mx = gridMax;
                double mn = 0;
                if (maxScale) {
                    // scale is maxed out, so fix boundaries of scope at maximum and minimum.
                    mx = maxValue;
                    mn = minValue;
                } else if (showNegative || minValue < (mx + mn) * .5 - (mx - mn) * .55) {
                    mn = -gridMax;
                    showNegative = true;
                }
                gridMid = (mx + mn) * .5;
                gridMax = (mx - mn) * .55;  // leave space at top and bottom
            }
        } else {
            gridMid = 0;
            gridMax = getGridMaxFromManScale(manDivisions, plot.manScale);
            positionOffset = gridMax * 2.0 * (double) (plot.manVPosition) / (double) (V_POSITION_STEPS);
        }
        plot.plotOffset = -gridMid + positionOffset;

        plot.gridMult = maxy / gridMax;

        int minRangeLo = -10 - (int) (gridMid * plot.gridMult);
        int minRangeHi = 10 - (int) (gridMid * plot.gridMult);

        if (!isManualScale()) {
            gridStepY = 1e-8;
            while (gridStepY < 20 * gridMax / maxy) {
                gridStepY *= multa[(multptr++) % 3];
            }
        } else {
            gridStepY = plot.manScale;
        }

        String minorDiv = "#404040";
        String majorDiv = "#A0A0A0";
        if (ColorSettings.get().isPrintable()) {
            minorDiv = "#D0D0D0";
            majorDiv = "#808080";
        }
        if (allSelected) {
            majorDiv = ColorSettings.get().getSelectColor().getHexValue();
        }

        // Vertical (T) gridlines
        double ts = simulator().maxTimeStep * speed;
        gridStepX = calcGridStepX();

        boolean highlightCenter = !isManualScale();

        if (drawGridLines) {
            // horizontal gridlines

            // don't show hgridlines if lines are too close together (except for center line)
            boolean showHGridLines = (gridStepY != 0) && (isManualScale() || allPlotsSameUnits); // Will only show center line if we have mixed units
            for (int ll = -100; ll <= 100; ll++) {
                if (ll != 0 && !showHGridLines) {
                    continue;
                }
                int yl = maxy - (int) ((ll * gridStepY - gridMid) * plot.gridMult);
                if (yl < 0 || yl >= rect.height - 1) {
                    continue;
                }
                String col = ll == 0 && highlightCenter ? majorDiv : minorDiv;
                g.setColor(col);
                g.drawLine(0, yl, rect.width - 1, yl);
            }

            // vertical gridlines
            double rightEdgeTime = getTimeBaseRightEdgeTime();
            double tstart = rightEdgeTime - simulator().maxTimeStep * speed * rect.width;
            double tx = rightEdgeTime - (rightEdgeTime % gridStepX);

            for (int ll = 0; ; ll++) {
                double tl = tx - gridStepX * ll;
                int gx = (int) ((tl - tstart) / ts);
                if (gx < 0) {
                    break;
                }
                if (gx >= rect.width) {
                    continue;
                }
                if (tl < 0) {
                    continue;
                }
                String col = minorDiv;
                // first = 0;
                if (((tl + gridStepX / 4) % (gridStepX * 10)) < gridStepX) {
                    col = majorDiv;
                }
                g.setColor(col);
                g.drawLine(gx, 0, gx, rect.height - 1);
            }

            // Trigger marker (time-domain only)
            if (triggerEnabled && isTriggerAvailable() && timeBaseStartIndexOverride != null) {
                int trigX = getTriggerPixelX();
                g.save();
                g.setAlpha(0.6);
                g.setColor(majorDiv);
                g.drawLine(trigX, 0, trigX, rect.height - 1);
                g.restore();
            }
        }

        // only need gridlines drawn once
        drawGridLines = false;

        // History overlay (draw behind live trace)
        if (historyEnabled && isHistoryAvailable() && plot == getHistorySourcePlot() && !historyFrames.isEmpty()) {
            int frameCount = historyFrames.size();
            for (int fi = 0; fi < frameCount; fi++) {
                HistoryFrame f = historyFrames.get(fi);
                if (f == null || f.width != rect.width) {
                    continue;
                }
                double alpha = 0.08 + 0.32 * ((double) (fi + 1) / (double) (frameCount + 1));
                g.save();
                g.setAlpha(alpha);
                g.setColor(f.color);
                for (int i = 0; i != rect.width; i++) {
                    int minvy = (int) (plot.gridMult * (f.minValues[i] + plot.plotOffset));
                    int maxvy = (int) (plot.gridMult * (f.maxValues[i] + plot.plotOffset));
                    if (minvy <= maxy && minvy != maxvy) {
                        g.drawLine(x + i, maxy - minvy, x + i, maxy - maxvy);
                    }
                }
                g.restore();
            }
            g.setAlpha(1.0);
        }

        g.setColor(color);

        if (isManualScale()) {
            // draw zero point
            int y0 = maxy - (int) (plot.gridMult * plot.plotOffset);
            g.drawLine(0, y0, 8, y0);
            g.drawString("0", 0, y0 - 2);
        }

        int ox = -1, oy = -1;
        for (int i = 0; i != rect.width; i++) {
            double vmin;
            double vmax;
            if (useFrame) {
                vmin = minV[i];
                vmax = maxV[i];
            } else {
                int ip = (i + ipa) & (scopePointCount - 1);
                vmin = minV[ip];
                vmax = maxV[ip];
            }
            int minvy = (int) (plot.gridMult * (vmin + plot.plotOffset));
            int maxvy = (int) (plot.gridMult * (vmax + plot.plotOffset));
            if (minvy <= maxy) {
                if (minvy < minRangeLo || maxvy > minRangeHi) {
                    // we got a value outside min range, so we don't need to rescale later
                    reduceRange[plot.units] = false;
                    minRangeLo = -1000;
                    minRangeHi = 1000; // avoid triggering this test again
                }
                if (ox != -1) {
                    if (minvy == oy && maxvy == oy) {
                        continue;
                    }
                    g.drawLine(ox, maxy - oy, x + i, maxy - oy);
                    ox = oy = -1;
                }
                if (minvy == maxvy) {
                    ox = x + i;
                    oy = minvy;
                    continue;
                }
                g.drawLine(x + i, maxy - minvy, x + i, maxy - maxvy);
            }
        } // for (i=0...)
        if (ox != -1) {
            g.drawLine(ox, maxy - oy, x + rect.width - 1, maxy - oy); // Horizontal
        }

    }

    static void clearCursorInfo() {
        cursorScope = null;
        cursorTime = -1;
    }

    public void selectScope(int mouseX, int mouseY) {
        if (!rect.contains(mouseX, mouseY)) {
            return;
        }
        if (plot2d || visiblePlots.isEmpty()) {
            cursorTime = -1;
        } else {
            cursorTime = getTimeBaseRightEdgeTime() - simulator().maxTimeStep * speed * (rect.x + rect.width - mouseX);
        }
        checkForSelection(mouseX, mouseY);
        cursorScope = this;
    }

    // find selected plot
    void checkForSelection(int mouseX, int mouseY) {
        if (cirSim.dialogIsShowing()) {
            return;
        }
        if (!rect.contains(mouseX, mouseY)) {
            selectedPlot = -1;
            return;
        }
        if (plots.isEmpty()) {
            selectedPlot = -1;
            return;
        }
        int ipa = getPlotStartIndex(plots.get(0));
        int ip = (mouseX - rect.x + ipa) & (scopePointCount - 1);
        int maxy = (rect.height - 1) / 2;
        int y = maxy;
        int bestdist = 10000;
        int best = -1;
        for (int i = 0; i != visiblePlots.size(); i++) {
            ScopePlot plot = visiblePlots.get(i);
            int maxvy = (int) (plot.gridMult * (plot.maxValues[ip] + plot.plotOffset));
            int dist = Math.abs(mouseY - (rect.y + y - maxvy));
            if (dist < bestdist) {
                bestdist = dist;
                best = i;
            }
        }
        selectedPlot = best;
        if (selectedPlot >= 0) {
            cursorUnits = visiblePlots.get(selectedPlot).units;
        }
    }

    void drawCursor(Graphics g) {
        if (cirSim.dialogIsShowing()) {
            return;
        }
        if (cursorScope == null) {
            return;
        }
        String[] info = new String[4];
        int cursorX = -1;
        int ct = 0;
        if (cursorTime >= 0) {
            cursorX = -(int) ((getTimeBaseRightEdgeTime() - cursorTime) / (simulator().maxTimeStep * speed) - rect.x - rect.width);
            if (cursorX >= rect.x) {
                int ipa = getPlotStartIndex(plots.get(0));
                int ip = (cursorX - rect.x + ipa) & (scopePointCount - 1);
                int maxy = (rect.height - 1) / 2;
                int y = maxy;
                if (!visiblePlots.isEmpty()) {
                    ScopePlot plot = visiblePlots.get(Math.max(selectedPlot, 0));
                    info[ct++] = plot.getUnitText(plot.maxValues[ip]);
                    int maxvy = (int) (plot.gridMult * (plot.maxValues[ip] + plot.plotOffset));
                    g.setColor(plot.color);
                    g.fillOval(cursorX - 2, rect.y + y - maxvy - 2, 5, 5);
                }
            }
        }

        // show FFT even if there's no plots (in which case cursorTime/cursorX will be invalid)
        if (showFFT && cursorScope == this) {
            double maxFrequency = 1 / (simulator().maxTimeStep * speed * 2);
            if (cursorX < 0) {
                cursorX = circuitEditor().mouseCursorX;
            }
            info[ct++] = CircuitElm.getUnitText(maxFrequency * (circuitEditor().mouseCursorX - rect.x) / rect.width, "Hz");
        } else if (cursorX < rect.x) {
            return;
        }

        if (!visiblePlots.isEmpty()) {
            if (triggerEnabled && isTriggerAvailable() && timeBaseStartIndexOverride != null && cursorScope == this) {
                double dt = cursorTime - getTimeBaseTriggerTime();
                info[ct++] = "dt=" + CircuitElm.getTimeText(dt);
            } else {
                info[ct++] = CircuitElm.getTimeText(cursorTime);
            }
        }

        if (cursorScope != this) {
            // don't show cursor info if not enough room, or stacked with selected one
            // (position == -1 for embedded scopes)
            if (rect.height < 40 || (position >= 0 && cursorScope.position == position)) {
                drawCursorInfo(g, null, 0, cursorX, false);
                return;
            }
        }
        drawCursorInfo(g, info, ct, cursorX, false);
    }

    void drawCursorInfo(Graphics graphics, String[] info, int ct, int x, Boolean drawY) {
        int szw = 0, szh = 15 * ct;
        for (int i = 0; i != ct; i++) {
            int w = (int) graphics.measureWidth(info[i]);
            if (w > szw) {
                szw = w;
            }
        }

        // Draw cursor lines using a visible highlight color (not background),
        // so they are visible on both dark and light themes.
        graphics.setColor(ColorSettings.get().getSelectColor());
        graphics.drawLine(x, rect.y, x, rect.y + rect.height);
        if (drawY) {
            graphics.drawLine(rect.x, circuitEditor().mouseCursorY, rect.x + rect.width, circuitEditor().mouseCursorY);
        }
        graphics.setColor(ColorSettings.get().getBackgroundColor());
        int bx = x;
        if (bx < szw / 2) {
            bx = szw / 2;
        }
        graphics.fillRect(bx - szw / 2, rect.y - szh, szw, szh);
        graphics.setColor(ColorSettings.get().getForegroundColor());
        for (int i = 0; i != ct; i++) {
            int w = (int) graphics.measureWidth(info[i]);
            graphics.drawString(info[i], bx - w / 2, rect.y - 2 - (ct - 1 - i) * 15);
        }

    }

    public boolean canShowRMS() {
        if (visiblePlots.isEmpty()) {
            return false;
        }
        ScopePlot plot = visiblePlots.firstElement();
        return (plot.units == Scope.UNITS_V || plot.units == Scope.UNITS_A);
    }

    // calc RMS and display it
    void drawRMS(Graphics g) {
        if (!canShowRMS()) {
            // needed for backward compatibility
            showRMS = false;
            showAverage = true;
            drawAverage(g);
            return;
        }
        ScopePlot plot = visiblePlots.firstElement();
        int startIndex = getPlotStartIndex(plot);
        double midpoint = (maxValue + minValue) / 2;
        CircuitMath.WaveformMetrics metrics = CircuitMath.calculateWaveformMetrics(rect.width, startIndex, scopePointCount, plot.maxValues, plot.minValues, midpoint);

        if (metrics.valid) {
            drawInfoText(g, plot.getUnitText(metrics.rms) + "rms");
        }
    }

    void drawScale(ScopePlot plot, Graphics g) {
        if (!isManualScale()) {
            if (gridStepY != 0 && (!(showV && showI))) {
                String vScaleText = " V=" + plot.getUnitText(gridStepY) + "/div";
                String h = "H=" + CircuitElm.getUnitText(gridStepX, "s") + "/div";
                if (triggerEnabled && isTriggerAvailable() && timeBaseStartIndexOverride != null) {
                    double dt = simulator().maxTimeStep * speed;
                    int pre = getTriggerPixelX();
                    double preT = pre * dt;
                    double postT = (rect.width - pre) * dt;
                    h += "  T=0:[-" + CircuitElm.getUnitText(preT, "s") + ",+" + CircuitElm.getUnitText(postT, "s") + "]";
                }
                drawInfoText(g, h + vScaleText);
            }
        } else {
            if (rect.y + rect.height <= textY + 5) {
                return;
            }
            double x = 0;
            String hs = "H=" + CircuitElm.getUnitText(gridStepX, "s") + "/div";
            g.drawString(hs, 0, textY);
            x += g.measureWidth(hs);
            final double bulletWidth = 17;
            for (int i = 0; i < visiblePlots.size(); i++) {
                ScopePlot p = visiblePlots.get(i);
                if (p != null) {
                    String s = p.getUnitText(p.manScale);
                    String vScaleText = "=" + s + "/div";
                    double vScaleWidth = g.measureWidth(vScaleText);
                    if (x + bulletWidth + vScaleWidth > rect.width) {
                        x = 0;
                        textY += 15;
                        if (rect.y + rect.height <= textY + 5) {
                            return;
                        }
                    }
                    g.setColor(p.color);
                    g.fillOval((int) x + 7, textY - 9, 8, 8);
                    x += bulletWidth;
                    g.setColor(ColorSettings.get().getBackgroundColor());
                    g.drawString(vScaleText, (int) x, textY);
                    x += vScaleWidth;
                }
            }
            textY += 15;
        }


    }

    void drawAverage(Graphics g) {
        ScopePlot plot = visiblePlots.firstElement();
        int startIndex = getPlotStartIndex(plot);
        double midpoint = (maxValue + minValue) / 2;
        CircuitMath.WaveformMetrics metrics = CircuitMath.calculateWaveformMetrics(rect.width, startIndex, scopePointCount, plot.maxValues, plot.minValues, midpoint);

        if (metrics.valid) {
            drawInfoText(g, plot.getUnitText(metrics.average) + Locale.LS(" average"));
        }
    }

    void drawDutyCycle(Graphics g) {
        ScopePlot plot = visiblePlots.firstElement();
        int startIndex = getPlotStartIndex(plot);
        double midpoint = (maxValue + minValue) / 2;
        CircuitMath.DutyCycleInfo info = CircuitMath.calculateDutyCycle(rect.width, startIndex, scopePointCount, plot.maxValues, plot.minValues, midpoint);

        if (info.valid) {
            drawInfoText(g, Locale.LS("Duty cycle ") + info.dutyCycle + "%");
        }
    }

    // calc frequency if possible and display it
    void drawFrequency(Graphics g) {
        ScopePlot plot = visiblePlots.firstElement();
        int ipa = getPlotStartIndex(plot);
        double[] minV = plot.minValues;
        double[] maxV = plot.maxValues;

        double avg = CircuitMath.calculateAverage(rect.width, ipa, scopePointCount, minV, maxV);
        CircuitMath.FreqData freqData = CircuitMath.calculateFrequency(rect.width, ipa, scopePointCount, maxV, avg, simulator().maxTimeStep, speed);

        if (freqData.frequency != 0) {
            drawInfoText(g, CircuitElm.getUnitText(freqData.frequency, "Hz"));
        }
    }

    void drawElmInfo(Graphics g) {
        String[] info = new String[1];
        getElm().getInfo(info);
        for (String s : info) {
            drawInfoText(g, s);
        }
    }

    int textY;

    void drawInfoText(Graphics g, String text) {
        if (rect.y + rect.height <= textY + 5) {
            return;
        }
        g.drawString(text, 0, textY);
        textY += 15;
    }

    void drawInfoTexts(Graphics g) {
        g.setColor(ColorSettings.get().getForegroundColor());
        textY = 10;

        if (visiblePlots.isEmpty()) {
            if (showElmInfo) {
                drawElmInfo(g);
            }
            return;
        }
        ScopePlot plot = visiblePlots.firstElement();
        if (showScale) {
            drawScale(plot, g);
        }
        if (showMax) {
            drawInfoText(g, "Max=" + plot.getUnitText(maxValue));
        }
        if (showMin) {
            int ym = rect.height - 5;
            g.drawString("Min=" + plot.getUnitText(minValue), 0, ym);
        }
        if (showRMS) {
            drawRMS(g);
        }
        if (showAverage) {
            drawAverage(g);
        }
        if (showDutyCycle) {
            drawDutyCycle(g);
        }
        String t = getScopeLabelOrText(true);
        if (t != null && t != "") {
            drawInfoText(g, t);
        }
        if (showFreq) {
            drawFrequency(g);
        }
        if (showElmInfo) {
            drawElmInfo(g);
        }
    }

    String getScopeText() {
        // stacked scopes?  don't show text
        if (stackCount != 1) {
            return null;
        }

        // multiple elms?  don't show text (unless one is selected)
        if (selectedPlot < 0 && getSingleElm() == null) {
            return null;
        }

        // no visible plots?
        if (visiblePlots.isEmpty()) {
            return null;
        }

        ScopePlot plot = visiblePlots.firstElement();
        if (selectedPlot >= 0 && visiblePlots.size() > selectedPlot) {
            plot = visiblePlots.get(selectedPlot);
        }
        if (plot.elm == null) {
            return "";
        } else {
            return plot.elm.getScopeText(plot.value);
        }
    }

    String getScopeLabelOrText() {
        return getScopeLabelOrText(false);
    }

    String getScopeLabelOrText(boolean forInfo) {
        String t = text;
        if (t == null) {
            // if we're drawing the info and showElmInfo is true, return null so we don't print redundant info.
            // But don't do that if we're getting the scope label to generate "Add to Existing Scope" menu.
            if (forInfo && showElmInfo) {
                return null;
            }
            t = getScopeText();
            if (t == null) {
                return "";
            }
            return Locale.LS(t);
        } else {
            return t;
        }
    }

    public void setSpeed(int sp) {
        if (sp < 1) {
            sp = 1;
        }
        if (sp > 1024) {
            sp = 1024;
        }
        speed = sp;
        resetGraph();
    }

    /**
     * Get scale value for the given units type.
     * @param units UNITS_V, UNITS_A, UNITS_OHMS, or UNITS_W
     * @return current scale value
     */
    public double getScale(int units) {
        if (units >= 0 && units < scale.length) {
            return scale[units];
        }
        return 1.0;
    }

    /**
     * Set scale value for the given units type.
     * @param units UNITS_V, UNITS_A, UNITS_OHMS, or UNITS_W
     * @param value new scale value
     */
    public void setScale(int units, double value) {
        if (units >= 0 && units < scale.length && value > 0) {
            scale[units] = value;
            // Keep scaleX/scaleY in sync for XY plots
            if (units == UNITS_V) {
                scaleX = value;
            } else if (units == UNITS_A) {
                scaleY = value;
            }
        }
    }

    void properties() {
        properties = cirSim.dialogManager.showScopePropertiesDialog(this);
    }

    void speedUp() {
        if (speed > 1) {
            speed /= 2;
            resetGraph();
        }
    }

    void slowDown() {
        if (speed < 1024) {
            speed *= 2;
        }
        resetGraph();
    }

    public void setPlotPosition(int plot, int v) {
        visiblePlots.get(plot).manVPosition = v;
    }

    // get scope element, returning null if there's more than one
    public CircuitElm getSingleElm() {
        CircuitElm elm = plots.get(0).elm;
        for (int i = 1; i < plots.size(); i++) {
            if (plots.get(i).elm != elm) {
                return null;
            }
        }
        return elm;
    }

    boolean canMenu() {
        return (plots.get(0).elm != null);
    }

    public boolean canShowResistance() {
        CircuitElm elm = getSingleElm();
        return elm != null && elm.canShowValueInScope(VAL_R);
    }

    public boolean isShowingVceAndIc() {
        return plot2d && plots.size() == 2 && plots.get(0).value == VAL_VCE && plots.get(1).value == VAL_IC;
    }

    int getFlags() {
        int flags = (showI ? 1 : 0) | (showV ? 2 : 0) |
                (showMax ? 0 : 4) |   // showMax used to be always on
                (showFreq ? 8 : 0) |
                // In this version we always dump manual settings using the PERPLOT format
                (isManualScale() ? (FLAG_MAN_SCALE | FLAG_PERPLOT_MAN_SCALE) : 0) |
                (plot2d ? 64 : 0) |
                (plotXY ? 128 : 0) | (showMin ? 256 : 0) | (showScale ? 512 : 0) |
                (showFFT ? 1024 : 0) | (maxScale ? 8192 : 0) | (showRMS ? 16384 : 0) |
                (showDutyCycle ? 32768 : 0) | (logSpectrum ? 65536 : 0) |
                (showAverage ? (1 << 17) : 0) | (showElmInfo ? (1 << 20) : 0);

        if (shouldDumpTriggerSettings()) {
            flags |= FLAG_TRIGGER;
        }
        if (shouldDumpHistorySettings()) {
            flags |= FLAG_HISTORY;
        }
        flags |= FLAG_PLOTS; // 4096
        int allPlotFlags = 0;
        for (ScopePlot p : plots) {
            allPlotFlags |= p.getPlotFlags();

        }
        // If none of our plots has a flag set we will use the old format with no plot flags, or
        // else we will set FLAG_PLOTFLAGS and include flags in all plots
        flags |= (allPlotFlags != 0) ? FLAG_PERPLOTFLAGS : 0; // (1<<18)

        if (isManualScale()) {
            flags |= FLAG_DIVISIONS;
        }
        return flags;
    }

    private boolean shouldDumpTriggerSettings() {
        return triggerEnabled ||
                triggerMode != TRIG_MODE_AUTO ||
                triggerSlope != TRIG_SLOPE_RISING ||
                triggerLevel != 0.0 ||
                triggerHoldoff != 0.0 ||
                triggerPosition != 0.25 ||
                triggerSource != 0;
    }

    private boolean shouldDumpHistorySettings() {
        return historyEnabled ||
                historyDepth != 8 ||
                historyCaptureMode != HISTORY_CAPTURE_ON_TRIGGER ||
                historySource != 0;
    }


    public String dump() {
        ScopePlot vPlot = plots.get(0);

        CircuitSimulator simulator = simulator();
        CircuitElm elm = vPlot.elm;
        if (elm == null) {
            return null;
        }
        int flags = getFlags();
        int eno = simulator.locateElm(elm);
        if (eno < 0) {
            return null;
        }
        String x = CircuitElm.dumpValues("o", eno,
                vPlot.scopePlotSpeed, vPlot.value,
                exportAsDecOrHex(flags, FLAG_PERPLOTFLAGS),
                scale[UNITS_V], scale[UNITS_A], position,
                plots.size());
        if ((flags & FLAG_DIVISIONS) != 0) {
            x += " " + CircuitElm.dumpValue(manDivisions);
        }
        for (int i = 0; i < plots.size(); i++) {
            ScopePlot p = plots.get(i);
            if ((flags & FLAG_PERPLOTFLAGS) != 0) {
                x += " " + Integer.toHexString(p.getPlotFlags()); // NB always export in Hex (no prefix)
            }
            if (i > 0) {
                x += " " + simulator.locateElm(p.elm) + " " + CircuitElm.dumpValue(p.value);
            }
            // dump scale if units are not V or A
            if (p.units > UNITS_A) {
                x += " " + CircuitElm.dumpValue(scale[p.units]);
            }
            if (isManualScale()) {// In this version we always dump manual settings using the PERPLOT format
                x += " " + CircuitElm.dumpValue(p.manScale) + " " + CircuitElm.dumpValue(p.manVPosition);
            }
        }

        if ((flags & FLAG_TRIGGER) != 0) {
            x += " " + (triggerEnabled ? 1 : 0);
            x += " " + triggerMode;
            x += " " + triggerSlope;
            x += " " + CircuitElm.dumpValue(triggerLevel);
            x += " " + CircuitElm.dumpValue(triggerHoldoff);
            x += " " + CircuitElm.dumpValue(triggerPosition);
            x += " " + triggerSource;
        }
        if ((flags & FLAG_HISTORY) != 0) {
            x += " " + (historyEnabled ? 1 : 0);
            x += " " + historyDepth;
            x += " " + historyCaptureMode;
            x += " " + historySource;
        }

        if (text != null) {
            x += " " + CustomLogicModel.escape(text);
        }
        return x;
    }

    public void undump(StringTokenizer st) {
        initialize();
        int e = CircuitElm.parseInt(st.nextToken());
        if (e == -1) {
            return;
        }
        CircuitElm ce = simulator().getElm(e);
        setElm(ce);
        speed = CircuitElm.parseInt(st.nextToken());
        int value = CircuitElm.parseInt(st.nextToken());

        // fix old value for VAL_POWER which doesn't work for transistors (because it's the same as VAL_IB)
        if (!(ce instanceof TransistorElm) && value == VAL_POWER_OLD) {
            value = VAL_POWER;
        }

        int flags = importDecOrHex(st.nextToken());
        scale[UNITS_V] = CircuitElm.parseDouble(st.nextToken());
        scale[UNITS_A] = CircuitElm.parseDouble(st.nextToken());
        if (scale[UNITS_V] == 0) {
            scale[UNITS_V] = .5;
        }
        if (scale[UNITS_A] == 0) {
            scale[UNITS_A] = 1;
        }
        scaleX = scale[UNITS_V];
        scaleY = scale[UNITS_A];
        scale[UNITS_OHMS] = scale[UNITS_W] = scale[UNITS_V];
        text = null;
        boolean plot2dFlag = (flags & 64) != 0;
        boolean hasPlotFlags = (flags & FLAG_PERPLOTFLAGS) != 0;
        if ((flags & FLAG_PLOTS) != 0) {
            // new-style dump
            try {
                position = CircuitElm.parseInt(st.nextToken());
                int sz = CircuitElm.parseInt(st.nextToken());
                manDivisions = 8;
                if ((flags & FLAG_DIVISIONS) != 0) {
                    manDivisions = lastManDivisions = CircuitElm.parseInt(st.nextToken());
                }
                int i;
                int u = ce.getScopeUnits(value);
                if (u > UNITS_A) {
                    scale[u] = CircuitElm.parseDouble(st.nextToken());
                }
                setValue(value);
                // setValue(0) creates an extra plot for current, so remove that
                while (plots.size() > 1) {
                    plots.removeElementAt(1);
                }

                int plotFlags = 0;
                for (i = 0; i != sz; i++) {
                    if (hasPlotFlags) {
                        plotFlags = CircuitElm.parseInt(st.nextToken(), 16); // Import in hex (no prefix)
                    }
                    if (i != 0) {
                        int ne = CircuitElm.parseInt(st.nextToken());
                        int val = CircuitElm.parseInt(st.nextToken());
                        CircuitElm elm = simulator().getElm(ne);
                        u = elm.getScopeUnits(val);
                        if (u > UNITS_A) {
                            scale[u] = CircuitElm.parseDouble(st.nextToken());
                        }
                        plots.add(new ScopePlot(cirSim, circuitDocument, elm, u, val, getManScaleFromMaxScale(u, false)));
                    }
                    ScopePlot p = plots.get(i);
                    p.acCoupled = (plotFlags & ScopePlot.FLAG_AC) != 0;
                    if ((flags & FLAG_PERPLOT_MAN_SCALE) != 0) {
                        p.manScaleSet = true;
                        p.manScale = CircuitElm.parseDouble(st.nextToken());
                        p.manVPosition = CircuitElm.parseInt(st.nextToken());
                    }
                }

                // Optional extensions (must come before optional custom label text)
                try {
                    if ((flags & FLAG_TRIGGER) != 0 && st.hasMoreTokens()) {
                        triggerEnabled = CircuitElm.parseInt(st.nextToken()) != 0;
                        triggerMode = CircuitElm.parseInt(st.nextToken());
                        triggerSlope = CircuitElm.parseInt(st.nextToken());
                        triggerLevel = CircuitElm.parseDouble(st.nextToken());
                        triggerHoldoff = CircuitElm.parseDouble(st.nextToken());
                        triggerPosition = CircuitElm.parseDouble(st.nextToken());
                        triggerSource = CircuitElm.parseInt(st.nextToken());
                        rearmSingleTrigger();
                    }
                    if ((flags & FLAG_HISTORY) != 0 && st.hasMoreTokens()) {
                        historyEnabled = CircuitElm.parseInt(st.nextToken()) != 0;
                        historyDepth = CircuitElm.parseInt(st.nextToken());
                        historyCaptureMode = CircuitElm.parseInt(st.nextToken());
                        historySource = CircuitElm.parseInt(st.nextToken());
                        trimHistoryToDepth();
                    }
                } catch (Exception ignored) {
                }

                while (st.hasMoreTokens()) {
                    if (text == null) {
                        text = st.nextToken();
                    } else {
                        text += " " + st.nextToken();
                    }
                }
            } catch (Exception ee) {
            }
        } else {
            // old-style dump
            CircuitElm yElm = null;
            int ivalue = 0;
            manDivisions = 8;
            try {
                position = CircuitElm.parseInt(st.nextToken());
                int ye = -1;
                if ((flags & FLAG_YELM) != 0) {
                    ye = CircuitElm.parseInt(st.nextToken());
                    if (ye != -1) {
                        yElm = simulator().getElm(ye);
                    }
                    // sinediode.txt has yElm set to something even though there's no xy plot...?
                    if (!plot2dFlag) {
                        yElm = null;
                    }
                }
                if ((flags & FLAG_IVALUE) != 0) {
                    ivalue = CircuitElm.parseInt(st.nextToken());
                }

                // Optional extensions (must come before optional custom label text)
                try {
                    if ((flags & FLAG_TRIGGER) != 0 && st.hasMoreTokens()) {
                        triggerEnabled = CircuitElm.parseInt(st.nextToken()) != 0;
                        triggerMode = CircuitElm.parseInt(st.nextToken());
                        triggerSlope = CircuitElm.parseInt(st.nextToken());
                        triggerLevel = CircuitElm.parseDouble(st.nextToken());
                        triggerHoldoff = CircuitElm.parseDouble(st.nextToken());
                        triggerPosition = CircuitElm.parseDouble(st.nextToken());
                        triggerSource = CircuitElm.parseInt(st.nextToken());
                        rearmSingleTrigger();
                    }
                    if ((flags & FLAG_HISTORY) != 0 && st.hasMoreTokens()) {
                        historyEnabled = CircuitElm.parseInt(st.nextToken()) != 0;
                        historyDepth = CircuitElm.parseInt(st.nextToken());
                        historyCaptureMode = CircuitElm.parseInt(st.nextToken());
                        historySource = CircuitElm.parseInt(st.nextToken());
                        trimHistoryToDepth();
                    }
                } catch (Exception ignored) {
                }

                while (st.hasMoreTokens()) {
                    if (text == null) {
                        text = st.nextToken();
                    } else {
                        text += " " + st.nextToken();
                    }
                }
            } catch (Exception ee) {
            }
            setValues(value, ivalue, simulator().getElm(e), yElm);
        }
        if (text != null) {
            text = CustomLogicModel.unescape(text);
        }
        plot2d = plot2dFlag;
        setFlags(flags);
    }

    void setFlags(int flags) {
        showI = (flags & 1) != 0;
        showV = (flags & 2) != 0;
        showMax = (flags & 4) == 0;
        showFreq = (flags & 8) != 0;
        manualScale = (flags & FLAG_MAN_SCALE) != 0;
        plotXY = (flags & 128) != 0;
        showMin = (flags & 256) != 0;
        showScale = (flags & 512) != 0;
        showFFT((flags & 1024) != 0);
        maxScale = (flags & 8192) != 0;
        showRMS = (flags & 16384) != 0;
        showDutyCycle = (flags & 32768) != 0;
        logSpectrum = (flags & 65536) != 0;
        showAverage = (flags & (1 << 17)) != 0;
        showElmInfo = (flags & (1 << 20)) != 0;
    }

    public void saveAsDefault() {
        if (!OptionsManager.hasLocalStorage()) {
            return;
        }
        ScopePlot vPlot = plots.get(0);
        int flags = getFlags();

        // store current scope settings as default.  1 is a version code
        OptionsManager.setOptionInStorage("scopeDefaults", "1 " + flags + " " + vPlot.scopePlotSpeed);
        CirSim.console("saved defaults " + flags);
    }

    boolean loadDefaults() {
        String str = OptionsManager.getOptionFromStorage("scopeDefaults", null);
        if (str == null) {
            return false;
        }
        String arr[] = str.split(" ");
        int flags = Integer.parseInt(arr[1]);
        setFlags(flags);
        speed = Integer.parseInt(arr[2]);
        return true;
    }

    void allocImage() {
        if (imageCanvas != null) {
            imageCanvas.setWidth(rect.width + "PX");
            imageCanvas.setHeight(rect.height + "PX");
            imageCanvas.setCoordinateSpaceWidth(rect.width);
            imageCanvas.setCoordinateSpaceHeight(rect.height);
            clear2dView();
        }
    }

    public void handleMenu(String mi, boolean state) {
        if (mi == "maxscale") {
            maxScale();
        }
        if (mi == "showvoltage") {
            showVoltage(state);
        }
        if (mi == "showcurrent") {
            showCurrent(state);
        }
        if (mi == "showscale") {
            showScale(state);
        }
        if (mi == "showpeak") {
            showMax(state);
        }
        if (mi == "shownegpeak") {
            showMin(state);
        }
        if (mi == "showfreq") {
            showFreq(state);
        }
        if (mi == "showfft") {
            showFFT(state);
        }
        if (mi == "logspectrum") {
            logSpectrum = state;
        }
        if (mi == "showrms") {
            showRMS = state;
        }
        if (mi == "showaverage") {
            showAverage = state;
        }
        if (mi == "showduty") {
            showDutyCycle = state;
        }
        if (mi == "showelminfo") {
            showElmInfo = state;
        }
        if (mi == "showpower") {
            setValue(VAL_POWER);
        }
        if (mi == "showib") {
            setValue(VAL_IB);
        }
        if (mi == "showic") {
            setValue(VAL_IC);
        }
        if (mi == "showie") {
            setValue(VAL_IE);
        }
        if (mi == "showvbe") {
            setValue(VAL_VBE);
        }
        if (mi == "showvbc") {
            setValue(VAL_VBC);
        }
        if (mi == "showvce") {
            setValue(VAL_VCE);
        }
        if (mi == "showvcevsic") {
            plot2d = true;
            plotXY = false;
            setValues(VAL_VCE, VAL_IC, getElm(), null);
            resetGraph();
        }

        if (mi == "showvvsi") {
            plot2d = state;
            plotXY = false;
            resetGraph();
        }
        if (mi == "manualscale") {
            setManualScale(state, true);
        }
        if (mi == "plotxy") {
            plotXY = plot2d = state;
            if (plot2d) {
                plots = visiblePlots;
            }
            if (plot2d && plots.size() == 1) {
                selectY();
            }
            resetGraph();
        }
        if (mi == "showresistance") {
            setValue(VAL_R);
        }
    }

//    void select() {
//    	sim.setMouseElm(elm);
//    	if (plotXY) {
//    		sim.plotXElm = elm;
//    		sim.plotYElm = yElm;
//    	}
//    }

    void selectY() {
        CircuitElm yElm = (plots.size() == 2) ? plots.get(1).elm : null;
        int e = (yElm == null) ? -1 : simulator().locateElm(yElm);
        int firstE = e;
        while (true) {
            for (e++; e < simulator().elmList.size(); e++) {
                CircuitElm ce = simulator().elmList.get(e);
                if ((ce instanceof OutputElm || ce instanceof ProbeElm) &&
                        ce != plots.get(0).elm) {
                    yElm = ce;
                    if (plots.size() == 1) {
                        plots.add(new ScopePlot(cirSim, circuitDocument, yElm, UNITS_V));
                    } else {
                        plots.get(1).elm = yElm;
                        plots.get(1).units = UNITS_V;
                    }
                    return;
                }
            }
            if (firstE == -1) {
                return;
            }
            e = firstE = -1;
        }
        // not reached
    }

    void onMouseWheel(MouseWheelEvent e) {
        wheelDeltaY += e.getDeltaY() * circuitEditor().wheelSensitivity;
        if (wheelDeltaY > 5) {
            slowDown();
            wheelDeltaY = 0;
        }
        if (wheelDeltaY < -5) {
            speedUp();
            wheelDeltaY = 0;
        }
    }

    public CircuitElm getElm() {
        if (selectedPlot >= 0 && visiblePlots.size() > selectedPlot) {
            return visiblePlots.get(selectedPlot).elm;
        }
        return visiblePlots.size() > 0 ? visiblePlots.get(0).elm : plots.get(0).elm;
    }

    boolean viewingWire() {
        for (ScopePlot plot : plots) {
            if (plot.elm instanceof WireElm) {
                return true;
            }
        }
        return false;
    }

    CircuitElm getXElm() {
        return getElm();
    }

    CircuitElm getYElm() {
        if (plots.size() == 2) {
            return plots.get(1).elm;
        }
        return null;
    }

    boolean needToRemove() {
        boolean ret = true;
        boolean removed = false;
        CircuitSimulator simulator = simulator();
        for (int i = 0; i != plots.size(); i++) {
            ScopePlot plot = plots.get(i);
            if (simulator.locateElm(plot.elm) < 0) {
                plots.remove(i--);
                removed = true;
            } else {
                ret = false;
            }
        }
        if (removed) {
            calcVisiblePlots();
        }
        return ret;
    }

    public boolean isManualScale() {
        return manualScale;
    }

    public double getManScaleFromMaxScale(int units, boolean roundUp) {
        // When the user manually switches to manual scale (and we don't already have a setting) then
        // call with "roundUp=true" to get a "sensible" suggestion for the scale. When importing from
        // a legacy file then call with "roundUp=false" to stay as close as possible to the old presentation
        double s = scale[units];
        if (units > UNITS_A) {
            s = 0.5 * s;
        }
        if (roundUp) {
            return ScopePropertiesDialog.nextHighestScale((2 * s) / (double) (manDivisions));
        } else {
            return (2 * s) / (double) (manDivisions);
        }
    }

    static String exportAsDecOrHex(int v, int thresh) {
        // If v>=thresh then export as hex value prefixed by "x", else export as decimal
        // Allows flags to be exported as dec if in an old value (for compatibility) or in hex if new value
        if (v >= thresh) {
            return "x" + Integer.toHexString(v);
        } else {
            return Integer.toString(v);
        }
    }

    static int importDecOrHex(String s) {
        if (s.charAt(0) == 'x') {
            return Integer.parseInt(s.substring(1), 16);
        } else {
            return Integer.parseInt(s);
        }
    }
}
