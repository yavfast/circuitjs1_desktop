package com.lushprojects.circuitjs1.client;

public class CircuitUtils {

    static boolean canSplit(CircuitElm ce) {
        if (!(ce instanceof WireElm))
            return false;
        WireElm we = (WireElm) ce;
        if (we.x == we.x2 || we.y == we.y2)
            return true;
        return false;
    }

    // check if the user can create sliders for this element
    static boolean sliderItemEnabled(CircuitElm elm) {
        int i;

        // prevent confusion
        if (elm instanceof VarRailElm || elm instanceof PotElm)
            return false;

        for (i = 0; ; i++) {
            EditInfo ei = elm.getEditInfo(i);
            if (ei == null)
                return false;
            if (ei.canCreateAdjustable())
                return true;
        }
    }


}
