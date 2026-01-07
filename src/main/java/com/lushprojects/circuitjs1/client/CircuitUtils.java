package com.lushprojects.circuitjs1.client;

import com.lushprojects.circuitjs1.client.dialog.EditInfo;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.PotElm;
import com.lushprojects.circuitjs1.client.element.WireElm;

public class CircuitUtils {

    static boolean canSplit(CircuitElm ce) {
        if (!(ce instanceof WireElm))
            return false;
        WireElm we = (WireElm) ce;
        if (we.getX() == we.getX2() || we.getY() == we.getY2())
            return true;
        return false;
    }

    // check if the user can create sliders for this element
    static boolean sliderItemEnabled(CircuitElm elm) {
        int i;

        // prevent confusion
        if (elm instanceof PotElm)
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
