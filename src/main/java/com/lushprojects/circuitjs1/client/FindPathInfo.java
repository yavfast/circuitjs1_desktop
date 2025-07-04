package com.lushprojects.circuitjs1.client;

import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.CurrentElm;
import com.lushprojects.circuitjs1.client.element.GroundElm;
import com.lushprojects.circuitjs1.client.element.InductorElm;
import com.lushprojects.circuitjs1.client.element.VoltageElm;

public class FindPathInfo {
    static final int INDUCT = 1;
    static final int VOLTAGE = 2;
    static final int SHORT = 3;
    static final int CAP_V = 4;

    CircuitSimulator simulator;
    boolean[] visited;
    int dest;
    CircuitElm firstElm;
    int type;

    // State object to help find loops in circuit subject to various conditions (depending on type_)
    // elm_ = source and destination element.  dest_ = destination node.
    FindPathInfo(CircuitSimulator simulator, int type_, CircuitElm elm_, int dest_) {
        this.simulator = simulator;
        dest = dest_;
        type = type_;
        firstElm = elm_;
        visited = new boolean[simulator.nodeList.size()];
    }

    // look through circuit for loop starting at node n1 of firstElm, for a path back to
    // dest node of firstElm
    boolean findPath(int n1) {
        if (n1 == dest) {
            return true;
        }

        // depth first search, don't need to revisit already visited nodes!
        if (visited[n1]) {
            return false;
        }

        visited[n1] = true;
        CircuitNode cn = simulator.getCircuitNode(n1);
        if (cn == null) {
            return false;
        }
        for (int i = 0; i != cn.links.size(); i++) {
            CircuitNodeLink cnl = cn.links.get(i);
            CircuitElm ce = cnl.elm;
            if (checkElm(n1, ce)) {
                return true;
            }
        }
        if (n1 == 0) {
            for (int i = 0; i < simulator.nodesWithGroundConnection.size(); i++) {
                if (checkElm(0, simulator.nodesWithGroundConnection.get(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    boolean checkElm(int n1, CircuitElm ce) {
        if (ce == firstElm) {
            return false;
        }
        if (type == INDUCT) {
            // inductors need a path free of current sources
            if (ce instanceof CurrentElm) {
                return false;
            }
        }
        if (type == VOLTAGE) {
            // when checking for voltage loops, we only care about voltage sources/wires/ground
            if (!(ce.isWireEquivalent() || ce instanceof VoltageElm || ce instanceof GroundElm)) {
                return false;
            }
        }
        // when checking for shorts, just check wires
        if (type == SHORT && !ce.isWireEquivalent()) {
            return false;
        }
        if (type == CAP_V) {
            // checking for capacitor/voltage source loops
            if (!(ce.isWireEquivalent() || ce.isIdealCapacitor() || ce instanceof VoltageElm)) {
                return false;
            }
        }
        if (n1 == 0) {
            // look for posts which have a ground connection;
            // our path can go through ground
            int j;
            for (j = 0; j != ce.getConnectionNodeCount(); j++) {
                if (ce.hasGroundConnection(j) && findPath(ce.getConnectionNode(j))) {
                    return true;
                }
            }
        }
        for (int j = 0; j < ce.getConnectionNodeCount(); j++) {
            if (ce.getConnectionNode(j) == n1) {
                if (ce.hasGroundConnection(j) && findPath(0)) {
                    return true;
                }
                if (type == INDUCT && ce instanceof InductorElm) {
                    // inductors can use paths with other inductors of matching current
                    double c = ce.getCurrent();
                    if (j == 0) {
                        c = -c;
                    }
                    if (Math.abs(c - firstElm.getCurrent()) > 1e-10) {
                        continue;
                    }
                }
                int k;
                for (k = 0; k != ce.getConnectionNodeCount(); k++) {
                    if (j == k) {
                        continue;
                    }
                    if (ce.getConnection(j, k) && findPath(ce.getConnectionNode(k))) {
                        //System.out.println("got findpath " + n1);
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
