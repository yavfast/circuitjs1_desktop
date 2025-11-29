package com.lushprojects.circuitjs1.client.element;

import com.lushprojects.circuitjs1.client.CircuitDocument;

public class PJfetElm extends JfetElm {
    public PJfetElm(CircuitDocument circuitDocument, int xx, int yy) {
        super(circuitDocument, xx, yy, true);
    }

    public Class getDumpClass() {
        return JfetElm.class;
    }
}
