package com.lushprojects.circuitjs1.client;

import com.lushprojects.circuitjs1.client.element.CircuitElm;

import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

class WireInfo {
    CircuitElm wire;
    List<CircuitElm> neighbors;
    int post;

    WireInfo(CircuitElm w) {
        wire = w;
    }
}
