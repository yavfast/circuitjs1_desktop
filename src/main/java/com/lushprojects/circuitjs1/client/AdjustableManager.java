package com.lushprojects.circuitjs1.client;

import com.google.gwt.event.dom.client.MouseWheelEvent;
import com.google.gwt.user.client.ui.Widget;
import com.lushprojects.circuitjs1.client.dialog.EditInfo;
import com.lushprojects.circuitjs1.client.dialog.SlidersDialog;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.HasBuiltInSlider;
import com.lushprojects.circuitjs1.client.element.HasControlWidget;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

public class AdjustableManager extends BaseCirSimDelegate {

    public final ArrayList<Adjustable> adjustables;

    // Sliders-dialog rows of HasControlWidget elements, rebuilt with the sliders.
    private final HashMap<CircuitElm, Widget> controlRows = new HashMap<>();

    public AdjustableManager(BaseCirSim cirSim, CircuitDocument circuitDocument) {
        super(cirSim, circuitDocument);
        adjustables = new ArrayList<>();
    }

    public ArrayList<Adjustable> getAdjustables() {
        return adjustables;
    }

    public void addAdjustable(StringTokenizer st) {
        CirSim cirSim = (CirSim) this.cirSim;
        Adjustable adj = new Adjustable(st, cirSim);
        if (adj.elm != null) {
            adjustables.add(adj);
        }
    }

    public Adjustable findAdjustable(CircuitElm elm, int item) {
        for (int i = 0; i < adjustables.size(); i++) {
            Adjustable a = adjustables.get(i);
            if (a.elm == elm && a.editItem == item) {
                return a;
            }
        }
        return null;
    }

    public String dump() {
        String dump = "";
        for (int i = 0; i < adjustables.size(); i++) {
            Adjustable adj = adjustables.get(i);
            dump += "38 " + adj.dump() + "\n";
        }
        return dump;
    }

    public void createSliders() {
        dedupeAdjustables();
        addMissingBuiltInAdjustables();
        dedupeAdjustables();
        for (int i = 0; i < adjustables.size(); i++) {
            if (!adjustables.get(i).createSlider()) {
                Adjustable removed = adjustables.remove(i--);
                unlinkShared(removed, false);
            }
        }
        // Adjustables detached above (their shared slider was dropped) still need a slider.
        for (int i = 0; i < adjustables.size(); i++) {
            Adjustable adj = adjustables.get(i);
            if (adj.sharedSlider == null && adj.slider == null && !adj.createSlider()) {
                adjustables.remove(i--);
            }
        }
        createControlRows();
    }

    private void dedupeAdjustables() {
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < adjustables.size(); i++) {
            Adjustable adj = adjustables.get(i);
            CircuitElm elm = adj.getElm();
            if (elm == null) {
                unlinkShared(adjustables.remove(i--), false);
                continue;
            }

            int elmIndex = simulator().locateElm(elm);
            if (elmIndex < 0) {
                unlinkShared(adjustables.remove(i--), false);
                continue;
            }

            int sharedIndex = adj.sharedSlider == null ? -1 : adjustables.indexOf(adj.sharedSlider);
            String key = elmIndex + ":" + adj.getEditItem() + ":" + sharedIndex;
            if (!seen.add(key)) {
                unlinkShared(adjustables.remove(i--), false);
            }
        }
    }

    private void addMissingBuiltInAdjustables() {
        for (CircuitElm ce : simulator().elmList) {
            if (ce instanceof HasBuiltInSlider
                    && findAdjustable(ce, ((HasBuiltInSlider) ce).getBuiltInSliderItem()) == null) {
                adjustables.add(newBuiltInAdjustable(ce));
            }
        }
    }

    private Adjustable newBuiltInAdjustable(CircuitElm ce) {
        HasBuiltInSlider bs = (HasBuiltInSlider) ce;
        Adjustable adj = new Adjustable((CirSim) cirSim, ce, bs.getBuiltInSliderItem());
        EditInfo ei = ce.getEditInfo(bs.getBuiltInSliderItem());
        if (ei != null && !Double.isNaN(ei.minVal) && !Double.isNaN(ei.maxVal)) {
            // Take the element's range even when it is empty (min == max), unlike the generic ctor.
            adj.minValue = Math.min(ei.minVal, ei.maxVal);
            adj.maxValue = Math.max(ei.minVal, ei.maxVal);
        }
        adj.sliderText = builtInSliderText(bs, ei);
        return adj;
    }

    private static String builtInSliderText(HasBuiltInSlider bs, EditInfo ei) {
        String text = bs.getBuiltInSliderText();
        if (text != null && !text.isEmpty())
            return text;
        // An empty label would drop the slider (Adjustable.createSlider); fall back to the item name.
        return (ei != null && ei.name != null && !ei.name.isEmpty()) ? ei.name : "Value";
    }

    /**
     * Returns the built-in adjustable of an element, creating it if missing, and refreshes its label.
     *
     * @param ce             a HasBuiltInSlider element of this document
     * @param refreshSliders rebuild the Sliders dialog afterwards
     * @return the adjustable, or null if the element has no built-in slider
     */
    public Adjustable ensureBuiltInSlider(CircuitElm ce, boolean refreshSliders) {
        if (!(ce instanceof HasBuiltInSlider)) {
            return null;
        }
        HasBuiltInSlider bs = (HasBuiltInSlider) ce;
        Adjustable adj = findAdjustable(ce, bs.getBuiltInSliderItem());
        if (adj == null) {
            adj = newBuiltInAdjustable(ce);
            adjustables.add(adj);
        } else {
            adj.sliderText = builtInSliderText(bs, ce.getEditInfo(bs.getBuiltInSliderItem()));
        }
        if (refreshSliders) {
            updateSliders();
        }
        return adj;
    }

    /** Forwards a mouse-wheel event over an element to the slider of its built-in adjustable. */
    public void onBuiltInSliderWheel(CircuitElm ce, MouseWheelEvent e) {
        if (!(ce instanceof HasBuiltInSlider)) {
            return;
        }
        Adjustable adj = findAdjustable(ce, ((HasBuiltInSlider) ce).getBuiltInSliderItem());
        if (adj != null) {
            adj.onMouseWheel(e);
        }
    }

    private void createControlRows() {
        SlidersDialog slidersDialog = ((CirSim) cirSim).slidersDialog;
        removeControlRows(slidersDialog);
        if (slidersDialog == null) {
            return;
        }
        for (CircuitElm ce : simulator().elmList) {
            if (!(ce instanceof HasControlWidget)) {
                continue;
            }
            Widget w = ((HasControlWidget) ce).createControlWidget();
            if (w != null) {
                controlRows.put(ce, slidersDialog.addWidgetRow(w));
            }
        }
        if (!controlRows.isEmpty() && !slidersDialog.isShowing()) {
            CirSim sim = (CirSim) cirSim;
            slidersDialog.show();
            sim.updateSlidersDialogPosition();
            sim.setSlidersDialogHeight();
        }
    }

    private void removeControlRows(SlidersDialog slidersDialog) {
        if (slidersDialog != null) {
            for (Widget row : controlRows.values()) {
                slidersDialog.removeSlider(row);
            }
        }
        controlRows.clear();
    }

    public void updateSliders() {
        clearSlidersDialog();
        createSliders();
    }

    public void reset() {
        adjustables.clear();
        clearSlidersDialog();
    }

    public void clearSlidersDialog() {
        CirSim cirSim = (CirSim) this.cirSim;
        SlidersDialog slidersDialog = cirSim.slidersDialog;
        if (slidersDialog != null) {
            slidersDialog.clear();
            slidersDialog.hide();
        }
        controlRows.clear();
    }

    // delete sliders for an element
    public void deleteSliders(CircuitElm elm) {
        int i;
        if (adjustables == null) {
            return;
        }
        for (i = adjustables.size() - 1; i >= 0; i--) {
            Adjustable adj = adjustables.get(i);
            if (adj.elm == elm) {
                adj.deleteSlider();
                adjustables.remove(i);
                unlinkShared(adj, true);
            }
        }
        Widget row = controlRows.remove(elm);
        SlidersDialog slidersDialog = ((CirSim) cirSim).slidersDialog;
        if (row != null && slidersDialog != null) {
            slidersDialog.removeSlider(row);
            if (slidersDialog.isEmpty()) {
                slidersDialog.hide();
            }
        }
    }

    /**
     * Detaches adjustables that share the slider of a removed adjustable, so they do not
     * keep a reference to a slider that no longer exists (NPE on the next edit).
     *
     * @param removed       the adjustable just removed from the list
     * @param createSliders give each detached adjustable its own slider now (outside createSliders())
     */
    public void unlinkShared(Adjustable removed, boolean createSliders) {
        for (int i = 0; i < adjustables.size(); i++) {
            Adjustable adj = adjustables.get(i);
            if (adj.sharedSlider == removed) {
                adj.sharedSlider = null;
                if (createSliders) {
                    adj.createSlider();
                }
            }
        }
    }

    public void setMouseElm(CircuitElm ce) {
        for (Adjustable item : adjustables) {
            item.setMouseElm(ce);
        }
    }

    // reorder adjustables so that items with sliders come first in the list,
    // followed by items that reference them.
    // this simplifies the UI code, and also makes it much easier to dump/undump the
    // adjustables list, since we will
    // always be undumping the adjustables with sliders first, then the adjustables
    // that reference them.
    public void reorderAdjustables() {
        ArrayList<Adjustable> newList = new ArrayList<>();
        ArrayList<Adjustable> oldList = adjustables;
        for (int i = 0; i < oldList.size(); i++) {
            Adjustable adj = oldList.get(i);
            if (adj.sharedSlider == null)
                newList.add(adj);
        }
        for (int i = 0; i < oldList.size(); i++) {
            Adjustable adj = oldList.get(i);
            if (adj.sharedSlider != null)
                newList.add(adj);
        }
        adjustables.clear();
        adjustables.addAll(newList);
    }

}
