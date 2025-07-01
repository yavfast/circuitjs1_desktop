package com.lushprojects.circuitjs1.client;

import com.lushprojects.circuitjs1.client.element.CircuitElm;

import java.util.Vector;

class WireInfo {
    CircuitElm wire;
    Vector<CircuitElm> neighbors;
    int post;

    WireInfo(CircuitElm w) {
        wire = w;
    }
}
