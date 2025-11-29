package com.lushprojects.circuitjs1.client.element;

import com.lushprojects.circuitjs1.client.CircuitDocument;

public class PDarlingtonElm extends DarlingtonElm {

    public PDarlingtonElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy, true);
    }

    public Class getDumpClass() {
        return DarlingtonElm.class;
    }
}
