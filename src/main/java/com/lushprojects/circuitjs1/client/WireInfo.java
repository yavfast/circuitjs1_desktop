package com.lushprojects.circuitjs1.client;

import com.lushprojects.circuitjs1.client.element.CircuitElm;

import java.util.List;

class WireInfo {
    CircuitElm wire;
    List<CircuitElm> neighbors;
    int post;

    WireInfo(CircuitElm w) {
        wire = w;
    }
}
