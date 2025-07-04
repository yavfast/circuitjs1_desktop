package com.lushprojects.circuitjs1.client;

import static com.lushprojects.circuitjs1.client.CirSim.console;

import com.lushprojects.circuitjs1.client.element.CapacitorElm;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.CurrentElm;
import com.lushprojects.circuitjs1.client.element.GroundElm;
import com.lushprojects.circuitjs1.client.element.InductorElm;
import com.lushprojects.circuitjs1.client.element.LogicInputElm;
import com.lushprojects.circuitjs1.client.element.RailElm;
import com.lushprojects.circuitjs1.client.element.VCCSElm;
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

    public static boolean validateElement(CircuitSimulator simulator, CircuitElm ce) {
        // look for inductors with no current path
        if (ce instanceof InductorElm) {
            FindPathInfo fpi = new FindPathInfo(simulator, FindPathInfo.INDUCT, ce, ce.getNode(1));
            if (!fpi.findPath(ce.getNode(0))) {
                ce.reset();
            }
        }

        // look for current sources with no current path
        if (ce instanceof CurrentElm) {
            CurrentElm cur = (CurrentElm) ce;
            FindPathInfo fpi = new FindPathInfo(simulator, FindPathInfo.INDUCT, ce, ce.getNode(1));
            cur.setBroken(!fpi.findPath(ce.getNode(0)));
        }

        if (ce instanceof VCCSElm) {
            VCCSElm cur = (VCCSElm) ce;
            FindPathInfo fpi = new FindPathInfo(simulator, FindPathInfo.INDUCT, ce, cur.getOutputNode(0));
            cur.broken = cur.hasCurrentOutput() && !fpi.findPath(cur.getOutputNode(1));
        }

        // look for voltage source or wire loops.  we do this for voltage sources
        if (ce.getPostCount() == 2) {
            if (ce instanceof VoltageElm) {
                FindPathInfo fpi = new FindPathInfo(simulator, FindPathInfo.VOLTAGE, ce, ce.getNode(1));
                if (fpi.findPath(ce.getNode(0))) {
                    simulator.stop("Voltage source/wire loop with no resistance!", ce);
                    return false;
                }
            }
        }

        // look for path from rail to ground
        if (ce instanceof RailElm || ce instanceof LogicInputElm) {
            FindPathInfo fpi = new FindPathInfo(simulator, FindPathInfo.VOLTAGE, ce, ce.getNode(0));
            if (fpi.findPath(0)) {
                simulator.stop("Path to ground with no resistance!", ce);
                return false;
            }
        }

        // look for shorted caps, or caps w/ voltage but no R
        if (ce instanceof CapacitorElm) {
            FindPathInfo fpi = new FindPathInfo(simulator, FindPathInfo.SHORT, ce, ce.getNode(1));
            if (fpi.findPath(ce.getNode(0))) {
                console(ce + " shorted");
                ((CapacitorElm) ce).shorted();
            } else {
                fpi = new FindPathInfo(simulator, FindPathInfo.CAP_V, ce, ce.getNode(1));
                if (fpi.findPath(ce.getNode(0))) {
                    // loop of ideal capacitors; set a small series resistance to avoid
                    // oscillation in case one of them has voltage on it
                    ((CapacitorElm) ce).setSeriesResistance(.1);

                    // return false to re-stamp the circuit
                    return false;
                }
            }
        }

        return true;
    }
}
