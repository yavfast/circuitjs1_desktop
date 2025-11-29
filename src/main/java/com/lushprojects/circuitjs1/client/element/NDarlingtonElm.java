package com.lushprojects.circuitjs1.client.element;

import com.lushprojects.circuitjs1.client.CircuitDocument;

public class NDarlingtonElm extends DarlingtonElm {


    public NDarlingtonElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy, false);
    }


    public Class getDumpClass() {
        return DarlingtonElm.class;
    }
}
