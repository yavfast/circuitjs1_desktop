package com.lushprojects.circuitjs1.client;

/**
 * Centralized storage for color settings used throughout the circuit simulator.
 * This is a singleton that holds all display colors including voltage scale colors,
 * selection colors, and element colors.
 * 
 * Usage:
 * - ColorSettings.get() returns the singleton instance
 * - Use colorSettings().xxx() from CircuitElm subclasses for convenience
 */
public class ColorSettings {

    private static ColorSettings instance;

    /**
     * Half-range (±) used to map voltages onto the color scale.
     * For example, voltageRange=5 maps [-5V..+5V] across the full scale.
     */
    private double voltageRange = 5;

    /**
     * Number of entries in the voltage/power color scale.
     * Kept odd so that 0V (neutral) sits exactly in the middle.
     */
    private static final int COLOR_SCALE_COUNT = 201;

    /**
     * Precomputed scale used by {@link #getVoltageColor(double)} and {@link #getPowerColor(double)}.
     */
    private Color[] colorScale;

    // ========== Display Colors ==========
    /** Background of the schematic canvas and UI (normal mode). */
    private Color backgroundColor;

    /**
     * Foreground text color used for UI labels and text overlays.
     * This is the primary “text color” that should contrast with {@link #backgroundColor}.
     */
    private Color foregroundColor;

    /** Default stroke color for elements when not highlighted and not voltage-colored. */
    private Color elementColor;

    /** Stroke/text color used when an element is selected or highlighted. */
    private Color selectColor;

    /** Positive-side color for the voltage gradient scale (used when showVoltage is enabled). */
    private Color positiveColor;

    /** Negative-side color for the voltage gradient scale (used when showVoltage is enabled). */
    private Color negativeColor;

    /** Neutral/midpoint color for the voltage gradient scale (typically used around 0V). */
    private Color neutralColor;

    /** Color used for current dots/animations (when showDots is enabled). */
    private Color currentColor;

    /** Color used for connection posts/terminals and related markers. */
    private Color postColor;

    /**
     * Printable mode flag.
     * When enabled, getters return simplified colors (white background, black elements, no gradients)
     * without mutating the stored color fields.
     */
    private boolean printable = false;

    private ColorSettings() {
        colorScale = new Color[COLOR_SCALE_COUNT];
        // Set defaults (normal mode - black background)
        positiveColor = Color.green;
        negativeColor = Color.red;
        neutralColor = Color.gray;
        selectColor = Color.cyan;
        currentColor = Color.yellow;
        backgroundColor = Color.black;
        foregroundColor = Color.white;  // White text on black background
        elementColor = Color.gray;
        postColor = Color.gray;  // Gray posts on black background
        // Initialize color scale with default colors
        updateColorScale();
    }

    /**
     * Returns the singleton {@link ColorSettings} instance.
     * This class stores global color preferences for the current runtime.
     *
     * @return the singleton instance
     */
    public static ColorSettings getInstance() {
        if (instance == null) {
            instance = new ColorSettings();
        }
        return instance;
    }
    
    /**
     * Shorthand for {@link #getInstance()}.
     *
     * @return the singleton instance
     */
    public static ColorSettings get() {
        return getInstance();
    }

    /**
     * Rebuilds the internal voltage/power color scale using the current
     * {@link #positiveColor}, {@link #negativeColor}, and {@link #neutralColor}.
     * <p>
     * Call this after changing any of those three colors to ensure gradients update.
     */
    public void updateColorScale() {
        if (positiveColor == null)
            positiveColor = Color.green;
        if (negativeColor == null)
            negativeColor = Color.red;
        if (neutralColor == null)
            neutralColor = Color.gray;

        for (int i = 0; i != COLOR_SCALE_COUNT; i++) {
            double v = i * 2. / COLOR_SCALE_COUNT - 1;
            if (v < 0) {
                colorScale[i] = new Color(neutralColor, negativeColor, -v);
            } else {
                colorScale[i] = new Color(neutralColor, positiveColor, v);
            }
        }
    }

    /**
     * Returns a color from the voltage scale for the provided node voltage.
     * <p>
     * The mapping uses {@link #voltageRange} so that voltages in [-range..+range] span the full scale.
     * Values outside the range are clamped.
     *
     * @param volts the voltage value
     * @return a color representing the voltage on the color scale
     */
    public Color getVoltageColor(double volts) {
        if (printable) {
            return Color.black;
        }
        int c = (int) ((volts + voltageRange) * (COLOR_SCALE_COUNT - 1) / (voltageRange * 2));
        if (c < 0) {
            c = 0;
        }
        if (c >= COLOR_SCALE_COUNT) {
            c = COLOR_SCALE_COUNT - 1;
        }
        return colorScale[c];
    }

    /**
     * Returns a color from the power scale for the provided power value.
     * <p>
     * This method uses the same precomputed color scale as voltage, but maps the value
     * around the center index.
     *
     * @param power the power value (already multiplied by powerMult)
     * @return a color representing the power on the color scale
     */
    public Color getPowerColor(double power) {
        if (printable) {
            return Color.black;
        }
        int i = (int) ((COLOR_SCALE_COUNT / 2) + (COLOR_SCALE_COUNT / 2) * -power);
        if (i < 0) {
            i = 0;
        }
        if (i >= COLOR_SCALE_COUNT) {
            i = COLOR_SCALE_COUNT - 1;
        }
        return colorScale[i];
    }

    // ========== Getters and Setters ==========

    /**
     * Returns the configured voltage range used for voltage-to-color mapping.
     *
     * @return half-range (±) in volts
     */
    public double getVoltageRange() {
        return voltageRange;
    }

    /**
     * Sets the voltage range used for voltage-to-color mapping.
     *
     * @param voltageRange half-range (±) in volts
     */
    public void setVoltageRange(double voltageRange) {
        this.voltageRange = voltageRange;
    }

    /**
     * Returns the background color.
     * In printable mode this is overridden to white.
     *
     * @return background color
     */
    public Color getBackgroundColor() {
        if (printable) {
            return Color.white;
        }
        return backgroundColor;
    }

    /**
     * Sets the background color used in normal mode.
     *
     * @param backgroundColor background color
     */
    public void setBackgroundColor(Color backgroundColor) {
        this.backgroundColor = backgroundColor;
    }

    /**
     * Returns the foreground text/UI color.
     * In printable mode this is overridden to black.
     *
     * @return foreground color
     */
    public Color getForegroundColor() {
        if (printable) {
            return Color.black;  // Black text on white background
        }
        return foregroundColor;
    }

    /**
     * Sets the foreground text/UI color used in normal mode.
     *
     * @param foregroundColor foreground color
     */
    public void setForegroundColor(Color foregroundColor) {
        this.foregroundColor = foregroundColor;
    }

    /**
     * Returns the default element stroke color.
     * In printable mode this is overridden to black.
     *
     * @return element color
     */
    public Color getElementColor() {
        if (printable) {
            return Color.black;
        }
        return elementColor;
    }

    /**
     * Sets the default element stroke color used in normal mode.
     *
     * @param elementColor element color
     */
    public void setElementColor(Color elementColor) {
        this.elementColor = elementColor;
    }

    /**
     * Returns the selection/highlight color.
     * In printable mode this is overridden to blue.
     *
     * @return selection color
     */
    public Color getSelectColor() {
        if (printable) {
            return Color.blue;
        }
        return selectColor;
    }

    /**
     * Sets the selection/highlight color used in normal mode.
     *
     * @param selectColor selection color
     */
    public void setSelectColor(Color selectColor) {
        this.selectColor = selectColor;
    }

    /**
     * Returns the positive-side color used to build the voltage scale.
     * In printable mode this is overridden to black.
     *
     * @return positive color
     */
    public Color getPositiveColor() {
        if (printable) {
            return Color.black;
        }
        return positiveColor;
    }

    /**
     * Sets the positive-side color used to build the voltage scale.
     * Call {@link #updateColorScale()} afterwards.
     *
     * @param positiveColor positive color
     */
    public void setPositiveColor(Color positiveColor) {
        this.positiveColor = positiveColor;
    }

    /**
     * Returns the negative-side color used to build the voltage scale.
     * In printable mode this is overridden to black.
     *
     * @return negative color
     */
    public Color getNegativeColor() {
        if (printable) {
            return Color.black;
        }
        return negativeColor;
    }

    /**
     * Sets the negative-side color used to build the voltage scale.
     * Call {@link #updateColorScale()} afterwards.
     *
     * @param negativeColor negative color
     */
    public void setNegativeColor(Color negativeColor) {
        this.negativeColor = negativeColor;
    }

    /**
     * Returns the neutral/midpoint color used to build the voltage scale.
     * In printable mode this is overridden to black.
     *
     * @return neutral color
     */
    public Color getNeutralColor() {
        if (printable) {
            return Color.black;
        }
        return neutralColor;
    }

    /**
     * Sets the neutral/midpoint color used to build the voltage scale.
     * Call {@link #updateColorScale()} afterwards.
     *
     * @param neutralColor neutral color
     */
    public void setNeutralColor(Color neutralColor) {
        this.neutralColor = neutralColor;
    }

    /**
     * Returns the color used for current dots/animations.
     * In printable mode this is overridden to black.
     *
     * @return current color
     */
    public Color getCurrentColor() {
        if (printable) {
            return Color.black;
        }
        return currentColor;
    }

    /**
     * Sets the color used for current dots/animations in normal mode.
     *
     * @param currentColor current color
     */
    public void setCurrentColor(Color currentColor) {
        this.currentColor = currentColor;
    }

    /**
     * Returns the post/terminal color.
     * In printable mode this is overridden to gray.
     *
     * @return post color
     */
    public Color getPostColor() {
        if (printable) {
            return Color.gray;  // Gray posts on white background
        }
        return postColor;
    }

    /**
     * Sets the post/terminal color used in normal mode.
     *
     * @param postColor post color
     */
    public void setPostColor(Color postColor) {
        this.postColor = postColor;
    }

    /**
     * Returns the number of entries in the internal color scale.
     *
     * @return color scale size
     */
    public int getColorScaleCount() {
        return COLOR_SCALE_COUNT;
    }

    /**
     * Returns whether printable mode is enabled.
     *
     * @return true if printable mode is active
     */
    public boolean isPrintable() {
        return printable;
    }

    /**
     * Enables/disables printable mode (white background, black elements, no gradients).
     * This does not modify individual color fields; it only changes what the getters return.
     *
     * @param printable true to enable printable mode
     */
    public void setPrintable(boolean printable) {
        this.printable = printable;
    }
}
