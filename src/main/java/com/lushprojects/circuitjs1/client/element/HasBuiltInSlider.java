package com.lushprojects.circuitjs1.client.element;

/**
 * An element whose main control value is always offered as a slider in the Sliders dialog
 * (potentiometer position, LDR brightness, thermistor temperature, variable rail voltage).
 * AdjustableManager creates the Adjustable for {@link #getBuiltInSliderItem()} when it is missing,
 * so the slider follows the document (tab switch, load, paste, undo) like any other adjustable.
 */
public interface HasBuiltInSlider {
    /** Index of the edit item (see {@code getEditInfo}) the slider controls. */
    int getBuiltInSliderItem();

    /** Label shown above the slider. */
    String getBuiltInSliderText();
}
