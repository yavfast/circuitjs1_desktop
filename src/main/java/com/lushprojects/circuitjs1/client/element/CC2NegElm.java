package com.lushprojects.circuitjs1.client.element;

import com.lushprojects.circuitjs1.client.CircuitDocument;

public class CC2NegElm extends CC2Elm {
    public CC2NegElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy, -1);
    }

    public Class getDumpClass() {
        return CC2Elm.class;
    }
}
