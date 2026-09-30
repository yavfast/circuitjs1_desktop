package com.lushprojects.circuitjs1.client.element;

import com.google.gwt.user.client.ui.Widget;

/**
 * An element that places a control (e.g. the audio output "Play" button) in the Sliders dialog of
 * its document. AdjustableManager rebuilds these rows together with the sliders.
 */
public interface HasControlWidget {
    /** Creates a fresh control widget; called each time the dialog is rebuilt. May return null. */
    Widget createControlWidget();
}
