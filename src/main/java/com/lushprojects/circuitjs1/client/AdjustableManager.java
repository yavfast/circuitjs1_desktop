package com.lushprojects.circuitjs1.client;

import com.lushprojects.circuitjs1.client.dialog.SlidersDialog;
import com.lushprojects.circuitjs1.client.element.CircuitElm;

import java.util.ArrayList;

public class AdjustableManager extends BaseCirSimDelegate {

    public final ArrayList<Adjustable> adjustables;

    protected AdjustableManager(BaseCirSim cirSim) {
        super(cirSim);
        adjustables = new ArrayList<>();
    }

    public ArrayList<Adjustable> getAdjustables() {
        return adjustables;
    }

    void addAdjustable(StringTokenizer st) {
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

    String dump() {
        String dump = "";
        for (int i = 0; i < adjustables.size(); i++) {
            Adjustable adj = adjustables.get(i);
            dump += "38 " + adj.dump() + "\n";
        }
        return dump;
    }

    void createSliders() {
        for (int i = 0; i < adjustables.size(); i++) {
            if (!adjustables.get(i).createSlider()) {
                adjustables.remove(i--);
            }
        }
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
                CirSim cirSim = (CirSim) this.cirSim;
                adj.deleteSlider();
                adjustables.remove(i);
            }
        }
    }

    public void setMouseElm(CircuitElm ce) {
        for (Adjustable item: adjustables) {
            item.setMouseElm(ce);
        }
    }

    // reorder adjustables so that items with sliders come first in the list, followed by items that reference them.
    // this simplifies the UI code, and also makes it much easier to dump/undump the adjustables list, since we will
    // always be undumping the adjustables with sliders first, then the adjustables that reference them.
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
