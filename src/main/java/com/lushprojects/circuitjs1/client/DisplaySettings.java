package com.lushprojects.circuitjs1.client;

/**
 * Centralized access to display settings for circuit elements.
 * Provides access to rendering options without requiring direct reference to MenuManager.
 * Used by CircuitElm and subclasses to determine how to draw elements.
 */
public class DisplaySettings {

    private final MenuManager menuManager;

    // Number formatting options. These are global display preferences (stored in OptionsManager).
    private static int decimalDigits;
    private static int shortDecimalDigits;
    private static boolean numberFormatsLoaded;

    public DisplaySettings(MenuManager menuManager) {
        this.menuManager = menuManager;
        // Preserve legacy behavior: make sure formatting prefs are available early.
        ensureNumberFormatsLoaded();
    }

    private static void ensureNumberFormatsLoaded() {
        if (!numberFormatsLoaded) {
            reloadNumberFormatsFromStorage();
        }
    }

    public static void reloadNumberFormatsFromStorage() {
        decimalDigits = OptionsManager.getIntOptionFromStorage("decimalDigits", 3);
        shortDecimalDigits = OptionsManager.getIntOptionFromStorage("decimalDigitsShort", 1);
        numberFormatsLoaded = true;
    }

    public static int getDecimalDigits() {
        ensureNumberFormatsLoaded();
        return decimalDigits;
    }

    public static int getShortDecimalDigits() {
        ensureNumberFormatsLoaded();
        return shortDecimalDigits;
    }

    public static void setDecimalDigits(int num, boolean save) {
        if (num < 0) {
            num = 0;
        }
        decimalDigits = num;
        numberFormatsLoaded = true;
        if (save) {
            OptionsManager.setOptionInStorage("decimalDigits", Integer.toString(num));
        }
    }

    public static void setDecimalDigitsShort(int num, boolean save) {
        if (num < 0) {
            num = 0;
        }
        shortDecimalDigits = num;
        numberFormatsLoaded = true;
        if (save) {
            OptionsManager.setOptionInStorage("decimalDigitsShort", Integer.toString(num));
        }
    }

    /**
     * Whether to show current flow dots animation
     */
    public boolean showDots() {
        return menuManager != null && menuManager.dotsCheckItem != null 
                && menuManager.dotsCheckItem.getState();
    }

    /**
     * Whether to show voltage colors on elements
     */
    public boolean showVoltage() {
        return menuManager != null && menuManager.voltsCheckItem != null 
                && menuManager.voltsCheckItem.getState();
    }

    /**
     * Whether to show power dissipation colors
     */
    public boolean showPower() {
        return menuManager != null && menuManager.powerCheckItem != null 
                && menuManager.powerCheckItem.getState();
    }

    /**
     * Whether to show component values (resistance, capacitance, etc.)
     */
    public boolean showValues() {
        return menuManager != null && menuManager.showValuesCheckItem != null 
                && menuManager.showValuesCheckItem.getState();
    }

    /**
     * Whether to use European-style resistor symbols (rectangle)
     */
    public boolean euroResistors() {
        return menuManager != null && menuManager.euroResistorCheckItem != null 
                && menuManager.euroResistorCheckItem.getState();
    }

    /**
     * Whether to use European-style logic gate symbols (IEC)
     */
    public boolean euroGates() {
        return menuManager != null && menuManager.euroGatesCheckItem != null 
                && menuManager.euroGatesCheckItem.getState();
    }

    /**
     * Whether in printable/white background mode
     */
    public boolean printableMode() {
        return menuManager != null && menuManager.printableCheckItem != null 
                && menuManager.printableCheckItem.getState();
    }

    /**
     * Whether to show conductance
     */
    public boolean showConductance() {
        return menuManager != null && menuManager.conductanceCheckItem != null 
                && menuManager.conductanceCheckItem.getState();
    }

    /**
     * Whether to use small grid
     */
    public boolean smallGrid() {
        return menuManager != null && menuManager.smallGridCheckItem != null 
                && menuManager.smallGridCheckItem.getState();
    }

    /**
     * Whether to show crosshair cursor
     */
    public boolean showCrossHair() {
        return menuManager != null && menuManager.crossHairCheckItem != null 
                && menuManager.crossHairCheckItem.getState();
    }
}
