package com.lushprojects.circuitjs1.client.io.json;

import com.lushprojects.circuitjs1.client.CirSim;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.Scope;
import com.lushprojects.circuitjs1.client.ScopePlot;
import com.lushprojects.circuitjs1.client.element.CircuitElm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The JSON v2 form of one scope's settings: an entry of the top-level {@code scopes} section
 * (a docked scope) and the {@code scope} property of an in-circuit {@code Scope} element (text
 * {@code 403} line). Element references are element IDs; values are Java maps, lists, numbers,
 * booleans and strings as {@code getJsonProperties} carries them and the factory delivers them
 * ({@link CircuitElementFactory#jsonObjectToMap}).
 * <p>
 * One writer and one reader for both places, so a docked and an in-circuit scope keep the same
 * settings through a text → JSON → text round trip.
 */
public final class JsonScopeCodec {

    private JsonScopeCodec() {
    }

    /**
     * @param scope    the scope (its first plot names the scope's element)
     * @param position the {@code position} field written (the stack position, or the saved one of
     *                 an in-circuit scope)
     * @param idOf     the element ID of a circuit element, or null when it has none in the export
     * @return the settings, keys in the order of the {@code scopes} section
     */
    public static LinkedHashMap<String, Object> toMap(Scope scope, int position, Function<CircuitElm, String> idOf) {
        LinkedHashMap<String, Object> scopeObj = new LinkedHashMap<>();

        // Reference to main element
        String elmId = idOf.apply(scope.getElm());
        if (elmId != null) {
            scopeObj.put("element", elmId);
        }

        // Position in scope stack
        scopeObj.put("position", (double) position);

        // User label shown on the scope (text format: trailing token of the 'o' line)
        if (scope.getText() != null && !scope.getText().isEmpty()) {
            scopeObj.put("label", scope.getText());
        }

        // Time scale (speed)
        scopeObj.put("speed", (double) scope.speed);

        // Display options
        LinkedHashMap<String, Object> display = new LinkedHashMap<>();
        display.put("show_voltage", scope.showV);
        display.put("show_current", scope.showI);
        display.put("show_scale", scope.showScale);
        display.put("show_max", scope.showMax);
        display.put("show_min", scope.showMin);
        display.put("show_frequency", scope.showFreq);
        display.put("show_fft", scope.showFFT);
        display.put("show_rms", scope.showRMS);
        display.put("show_average", scope.showAverage);
        display.put("show_duty_cycle", scope.showDutyCycle);
        display.put("show_negative", scope.showNegative);
        display.put("show_element_info", scope.showElmInfo);
        scopeObj.put("display", display);

        // Plot modes
        LinkedHashMap<String, Object> plotMode = new LinkedHashMap<>();
        plotMode.put("plot_2d", scope.plot2d);
        plotMode.put("plot_xy", scope.plotXY);
        plotMode.put("max_scale", scope.maxScale);
        plotMode.put("log_spectrum", scope.logSpectrum);
        scopeObj.put("plot_mode", plotMode);

        // Trigger settings (optional)
        boolean dumpTrigger = scope.isTriggerEnabled()
                || scope.getTriggerMode() != Scope.TRIG_MODE_AUTO
                || scope.getTriggerSlope() != Scope.TRIG_SLOPE_RISING
                || scope.getTriggerLevel() != 0.0
                || scope.getTriggerHoldoff() != 0.0
                || scope.getTriggerPosition() != 0.25
                || scope.getTriggerSource() != 0;
        if (dumpTrigger) {
            LinkedHashMap<String, Object> trigger = new LinkedHashMap<>();
            trigger.put("enabled", scope.isTriggerEnabled());
            trigger.put("mode", (double) scope.getTriggerMode());
            trigger.put("slope", (double) scope.getTriggerSlope());
            trigger.put("level", scope.getTriggerLevel());
            trigger.put("holdoff", scope.getTriggerHoldoff());
            trigger.put("position", scope.getTriggerPosition());
            trigger.put("source", (double) scope.getTriggerSource());
            scopeObj.put("trigger", trigger);
        }

        // History settings (optional)
        boolean dumpHistory = scope.isHistoryEnabled()
                || scope.getHistoryDepth() != 8
                || scope.getHistoryCaptureMode() != Scope.HISTORY_CAPTURE_ON_TRIGGER
                || scope.getHistorySource() != 0;
        if (dumpHistory) {
            LinkedHashMap<String, Object> history = new LinkedHashMap<>();
            history.put("enabled", scope.isHistoryEnabled());
            history.put("depth", (double) scope.getHistoryDepth());
            history.put("capture_mode", (double) scope.getHistoryCaptureMode());
            history.put("source", (double) scope.getHistorySource());
            scopeObj.put("history", history);
        }

        // Scale settings for different units
        LinkedHashMap<String, Object> scales = new LinkedHashMap<>();
        scales.put("voltage", scope.getScale(Scope.UNITS_V));
        scales.put("current", scope.getScale(Scope.UNITS_A));
        scales.put("ohms", scope.getScale(Scope.UNITS_OHMS));
        scales.put("watts", scope.getScale(Scope.UNITS_W));
        scopeObj.put("scales", scales);

        // Manual scale settings
        if (scope.isManualScale()) {
            LinkedHashMap<String, Object> manualScale = new LinkedHashMap<>();
            manualScale.put("enabled", true);
            manualScale.put("divisions", (double) scope.manDivisions);
            scopeObj.put("manual_scale", manualScale);
        }

        // Plots (individual traces)
        List<Object> plotsArray = new ArrayList<>();
        java.util.Vector<ScopePlot> plots = scope.plots;
        if (plots != null) {
            for (int j = 0; j < plots.size(); j++) {
                ScopePlot plot = plots.get(j);
                if (plot == null || plot.getElm() == null) {
                    continue;
                }
                // Element reference for this plot; a plot of an element the export does not hold
                // (a selection) is left out, as Scope.dump leaves out a plot it cannot bind
                String plotElmId = idOf.apply(plot.getElm());
                if (plotElmId == null) {
                    continue;
                }
                LinkedHashMap<String, Object> plotObj = new LinkedHashMap<>();
                plotObj.put("element", plotElmId);

                // Units type (0=V, 1=A, 2=W, 3=Ohm)
                plotObj.put("units", unitsName(plot.units));

                // The value being plotted (e.g. VAL_VOLTAGE, VAL_CURRENT, etc)
                plotObj.put("value", (double) plot.getValue());

                // Color
                if (plot.color != null) {
                    plotObj.put("color", plot.color);
                }

                // Manual scale for this plot (skip Infinity values)
                if (Double.isFinite(plot.manScale)) {
                    plotObj.put("scale", plot.manScale);
                }
                plotObj.put("v_position", (double) plot.manVPosition);

                // AC coupling
                if (plot.isAcCoupled()) {
                    plotObj.put("ac_coupled", true);
                }

                plotsArray.add(plotObj);
            }
        }
        if (!plotsArray.isEmpty()) {
            scopeObj.put("plots", plotsArray);
        }
        return scopeObj;
    }

    /**
     * Applies settings written by {@link #toMap} to {@code scope}: its element becomes {@code elm}
     * (plots of another element are resolved by {@code resolve}; a plot naming an unknown one is
     * dropped), then labels, display, plot modes, trigger, scales, manual scale, plots and
     * history; the speed last, so it re-initializes the plot buffers with the restored settings.
     * {@code position} is applied as written; an in-circuit scope's caller overrides it.
     */
    @SuppressWarnings("unchecked")
    public static void apply(Scope scope, CircuitElm elm, Map<String, Object> json, Function<String, CircuitElm> resolve,
            CircuitDocument document) {
        CirSim cirSim = document.getCirSim();
        scope.setElm(elm);

        // Position
        Object posValue = json.get("position");
        if (posValue instanceof Number) {
            scope.position = ((Number) posValue).intValue();
        }

        Object labelValue = json.get("label");
        if (labelValue instanceof String) {
            scope.setText((String) labelValue);
        }

        // Speed (applied after plots/settings are restored)
        int scopeSpeed = getInt(json, "speed", scope.speed);

        // Display options
        Map<String, Object> display = map(json, "display");
        if (display != null) {
            scope.showV = getBoolean(display, "show_voltage", true);
            scope.showI = getBoolean(display, "show_current", false);
            scope.showScale = getBoolean(display, "show_scale", true);
            scope.showMax = getBoolean(display, "show_max", false);
            scope.showMin = getBoolean(display, "show_min", false);
            scope.showFreq = getBoolean(display, "show_frequency", false);
            scope.showFFT = getBoolean(display, "show_fft", false);
            scope.showRMS = getBoolean(display, "show_rms", false);
            scope.showAverage = getBoolean(display, "show_average", false);
            scope.showDutyCycle = getBoolean(display, "show_duty_cycle", false);
            scope.showNegative = getBoolean(display, "show_negative", false);
            scope.showElmInfo = getBoolean(display, "show_element_info", true);
        }

        // Plot modes
        Map<String, Object> plotMode = map(json, "plot_mode");
        if (plotMode != null) {
            scope.plot2d = getBoolean(plotMode, "plot_2d", false);
            scope.plotXY = getBoolean(plotMode, "plot_xy", false);
            scope.maxScale = getBoolean(plotMode, "max_scale", false);
            scope.logSpectrum = getBoolean(plotMode, "log_spectrum", false);
        }

        // Trigger settings: written only when not at the defaults, so an absent object (or key)
        // means the defaults, never what the scope held before
        Map<String, Object> trigger = map(json, "trigger");
        if (trigger == null) {
            trigger = new LinkedHashMap<>();
        }
        scope.setTriggerEnabled(getBoolean(trigger, "enabled", false));
        scope.setTriggerMode(getInt(trigger, "mode", Scope.TRIG_MODE_AUTO));
        scope.setTriggerSlope(getInt(trigger, "slope", Scope.TRIG_SLOPE_RISING));
        scope.setTriggerLevel(getDouble(trigger, "level", 0.0));
        scope.setTriggerHoldoff(getDouble(trigger, "holdoff", 0.0));
        scope.setTriggerPosition(getDouble(trigger, "position", 0.25));
        scope.setTriggerSource(getInt(trigger, "source", 0));

        // Scale settings for different units
        Map<String, Object> scales = map(json, "scales");
        if (scales != null) {
            scope.setScale(Scope.UNITS_V, getDouble(scales, "voltage", 5));
            scope.setScale(Scope.UNITS_A, getDouble(scales, "current", 1));
            scope.setScale(Scope.UNITS_OHMS, getDouble(scales, "ohms", 5));
            scope.setScale(Scope.UNITS_W, getDouble(scales, "watts", 5));
        }

        // Manual scale: written only when on, so an absent object means off — never the user's
        // saved scope defaults (scopeDefaults), which setElm() applied above
        Map<String, Object> manualScale = map(json, "manual_scale");
        boolean manual = manualScale != null && getBoolean(manualScale, "enabled", false);
        scope.setManualScale(manual, false);
        if (manual) {
            scope.setManDivisions(getInt(manualScale, "divisions", scope.manDivisions));
        }

        // Plots (individual traces)
        Object plotsValue = json.get("plots");
        if (plotsValue instanceof List) {
            java.util.Vector<ScopePlot> restoredPlots = new java.util.Vector<>();
            for (Object plotValue : (List<Object>) plotsValue) {
                if (!(plotValue instanceof Map)) {
                    continue;
                }
                Map<String, Object> plotJson = (Map<String, Object>) plotValue;

                // Plot element reference (optional; defaults to scope element). A reference that
                // names no element of the content drops the plot, as Scope.dump drops a plot whose
                // element has no line — never a trace of the wrong element
                CircuitElm plotElm = elm;
                Object plotElmValue = plotJson.get("element");
                if (plotElmValue instanceof String) {
                    plotElm = resolve.apply((String) plotElmValue);
                    if (plotElm == null) {
                        continue;
                    }
                }

                // Plot value (preferred). If missing, infer from units.
                int value = Scope.VAL_VOLTAGE;
                Object valueValue = plotJson.get("value");
                if (valueValue instanceof Number) {
                    value = ((Number) valueValue).intValue();
                } else if (plotJson.get("units") instanceof String) {
                    value = valueFromUnits((String) plotJson.get("units"));
                }

                int units = plotElm.getScopeUnits(value);
                ScopePlot sp = ScopePlot.create(cirSim, document, plotElm, units, value,
                        scope.getManScaleFromMaxScale(units, false));

                // Color
                if (plotJson.get("color") instanceof String) {
                    sp.color = (String) plotJson.get("color");
                }

                // Manual scale for this plot
                Object plotScaleValue = plotJson.get("scale");
                Object vPosValue = plotJson.get("v_position");
                if (plotScaleValue instanceof Number) {
                    int vPos = vPosValue instanceof Number ? ((Number) vPosValue).intValue() : 0;
                    sp.applyManualScale(((Number) plotScaleValue).doubleValue(), vPos);
                } else if (vPosValue instanceof Number) {
                    sp.manVPosition = ((Number) vPosValue).intValue();
                }

                // AC coupling
                if (getBoolean(plotJson, "ac_coupled", false)) {
                    sp.setAcCoupled(true);
                }

                restoredPlots.add(sp);
            }
            if (!restoredPlots.isEmpty()) {
                scope.plots = restoredPlots;
            }
        }

        // History settings: written only when not at the defaults (absent = the defaults)
        Map<String, Object> history = map(json, "history");
        if (history == null) {
            history = new LinkedHashMap<>();
        }
        scope.setHistoryEnabled(getBoolean(history, "enabled", false));
        scope.setHistoryDepth(getInt(history, "depth", 8));
        scope.setHistoryCaptureMode(getInt(history, "capture_mode", Scope.HISTORY_CAPTURE_ON_TRIGGER));
        scope.setHistorySource(getInt(history, "source", 0));

        // Apply speed last so it reinitializes plot buffers with restored settings.
        scope.setSpeed(scopeSpeed);
    }

    /** Convert units constant to string name. */
    static String unitsName(int units) {
        switch (units) {
            case Scope.UNITS_V:
                return "V";
            case Scope.UNITS_A:
                return "A";
            case Scope.UNITS_W:
                return "W";
            case Scope.UNITS_OHMS:
                return "Ω";
            default:
                return "V";
        }
    }

    private static int valueFromUnits(String units) {
        switch (units) {
            case "A":
                return Scope.VAL_CURRENT;
            case "W":
                return Scope.VAL_POWER;
            case "Ohm":
                return Scope.VAL_R;
            default:
                return Scope.VAL_VOLTAGE;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    private static boolean getBoolean(Map<String, Object> obj, String key, boolean defaultValue) {
        Object v = obj.get(key);
        return v instanceof Boolean ? (Boolean) v : defaultValue;
    }

    private static double getDouble(Map<String, Object> obj, String key, double defaultValue) {
        Object v = obj.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : defaultValue;
    }

    private static int getInt(Map<String, Object> obj, String key, int defaultValue) {
        Object v = obj.get(key);
        return v instanceof Number ? (int) ((Number) v).doubleValue() : defaultValue;
    }
}
