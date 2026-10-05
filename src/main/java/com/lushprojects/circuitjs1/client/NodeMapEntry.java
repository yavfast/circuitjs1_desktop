package com.lushprojects.circuitjs1.client;

import java.util.ArrayList;

class NodeMapEntry {
    int node;
    /**
     * The node-map keys that point at this entry while the wire closure is built
     * ({@code CircuitSimulator.calculateWireClosure}); a merge re-points only the smaller group,
     * so the closure stays near-linear in the number of wires. Null outside the closure.
     */
    ArrayList<Point> closureKeys;

    NodeMapEntry() {
        node = -1;
    }

    NodeMapEntry(int n) {
        node = n;
    }
}
