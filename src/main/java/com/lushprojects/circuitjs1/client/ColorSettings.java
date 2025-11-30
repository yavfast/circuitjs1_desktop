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

    // Voltage range for color scale
    private double voltageRange = 5;

    // Number of colors in the scale (odd so ground = gray)
    private static final int COLOR_SCALE_COUNT = 201;

    // Color scale for voltage visualization
    private Color[] colorScale;

    // Display colors
    private Color backgroundColor;
    private Color foregroundColor;  // Color for text and UI elements (contrasts with background)
    private Color elementColor;
    private Color selectColor;
    private Color positiveColor;
    private Color negativeColor;
    private Color neutralColor;
    private Color currentColor;
    private Color postColor;  // Color for connection posts (contrasts with background)

    // Printable mode flag (white background, black elements, no gradients)
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
        elementColor = Color.green;
        postColor = Color.gray;  // Gray posts on black background
        // Initialize color scale with default colors
        updateColorScale();
    }

    /**
     * Get the singleton instance
     */
    public static ColorSettings getInstance() {
        if (instance == null) {
            instance = new ColorSettings();
        }
        return instance;
    }
    
    /**
     * Shorthand for getInstance()
     */
    public static ColorSettings get() {
        return getInstance();
    }

    /**
     * Initialize color scale based on current positive/negative/neutral colors.
     * Should be called after changing any of the voltage colors.
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
     * Get color for a given voltage value.
     * @param volts The voltage value
     * @return Color representing the voltage on the color scale
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
     * Get color for a given power value.
     * @param power The power value (already multiplied by powerMult)
     * @return Color representing the power on the color scale
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

    // Getters and setters

    public double getVoltageRange() {
        return voltageRange;
    }

    public void setVoltageRange(double voltageRange) {
        this.voltageRange = voltageRange;
    }

    public Color getBackgroundColor() {
        if (printable) {
            return Color.white;
        }
        return backgroundColor;
    }

    public void setBackgroundColor(Color backgroundColor) {
        this.backgroundColor = backgroundColor;
    }

    public Color getForegroundColor() {
        if (printable) {
            return Color.black;  // Black text on white background
        }
        return foregroundColor;
    }

    public void setForegroundColor(Color foregroundColor) {
        this.foregroundColor = foregroundColor;
    }

    public Color getElementColor() {
        if (printable) {
            return Color.black;
        }
        return elementColor;
    }

    public void setElementColor(Color elementColor) {
        this.elementColor = elementColor;
    }

    public Color getSelectColor() {
        if (printable) {
            return Color.blue;
        }
        return selectColor;
    }

    public void setSelectColor(Color selectColor) {
        this.selectColor = selectColor;
    }

    public Color getPositiveColor() {
        if (printable) {
            return Color.black;
        }
        return positiveColor;
    }

    public void setPositiveColor(Color positiveColor) {
        this.positiveColor = positiveColor;
    }

    public Color getNegativeColor() {
        if (printable) {
            return Color.black;
        }
        return negativeColor;
    }

    public void setNegativeColor(Color negativeColor) {
        this.negativeColor = negativeColor;
    }

    public Color getNeutralColor() {
        if (printable) {
            return Color.black;
        }
        return neutralColor;
    }

    public void setNeutralColor(Color neutralColor) {
        this.neutralColor = neutralColor;
    }

    public Color getCurrentColor() {
        if (printable) {
            return Color.black;
        }
        return currentColor;
    }

    public void setCurrentColor(Color currentColor) {
        this.currentColor = currentColor;
    }

    public Color getPostColor() {
        if (printable) {
            return Color.gray;  // Gray posts on white background
        }
        return postColor;
    }

    public void setPostColor(Color postColor) {
        this.postColor = postColor;
    }

    public int getColorScaleCount() {
        return COLOR_SCALE_COUNT;
    }

    /**
     * Check if printable mode is enabled.
     * @return true if printable mode is active
     */
    public boolean isPrintable() {
        return printable;
    }

    /**
     * Set printable mode (white background, black elements, no gradients).
     * This does not modify individual color settings, just overrides getters.
     * @param printable true for printable mode
     */
    public void setPrintable(boolean printable) {
        this.printable = printable;
    }
}
