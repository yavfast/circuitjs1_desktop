package com.lushprojects.circuitjs1.client;

import static com.lushprojects.circuitjs1.client.CirSim.console;

import com.google.gwt.user.client.Window;
import com.lushprojects.circuitjs1.client.element.CapacitorElm;
import com.lushprojects.circuitjs1.client.element.ChipElm;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.CurrentElm;
import com.lushprojects.circuitjs1.client.element.GraphicElm;
import com.lushprojects.circuitjs1.client.element.GroundElm;
import com.lushprojects.circuitjs1.client.element.InductorElm;
import com.lushprojects.circuitjs1.client.element.LabeledNodeElm;
import com.lushprojects.circuitjs1.client.element.LogicInputElm;
import com.lushprojects.circuitjs1.client.element.RailElm;
import com.lushprojects.circuitjs1.client.element.ScopeElm;
import com.lushprojects.circuitjs1.client.element.VCCSElm;
import com.lushprojects.circuitjs1.client.element.VoltageElm;
import com.lushprojects.circuitjs1.client.element.WireElm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

public class CircuitSimulator extends BaseCirSimDelegate {

    public double t; // TODO: tick ???

    // current timestep (time between iterations)
    public double timeStep;
    // maximum timestep (== timeStep unless we reduce it because of trouble
    // converging)
    public double maxTimeStep;
    public double minTimeStep;
    // accumulated time since we incremented timeStepCount
    double timeStepAccum;
    // incremented each time we advance t by maxTimeStep
    public int timeStepCount;

    public double minFrameRate = 20;
    public boolean adjustTimeStep;

    public final ArrayList<CircuitElm> elmList = new ArrayList<>(256);

    CircuitElm[] elmArr;
    ScopeElm[] scopeElmArr;
    double[][] circuitMatrix;
    double[][] origMatrix;
    double[] circuitRightSide;
    double[] lastNodeVoltages;
    double[] nodeVoltages;
    double[] origRightSide;
    RowInfo[] circuitRowInfo;
    int[] circuitPermute;
    boolean simRunning;
    boolean circuitNonLinear;
    int voltageSourceCount;
    int circuitMatrixSize;
    int circuitMatrixFullSize;
    boolean circuitNeedsMap;

    public final ArrayList<CircuitNode> nodeList = new ArrayList<>(128);

    // map points to node numbers
    private final HashMap<Point, NodeMapEntry> nodeMap = new HashMap<>(128);

    // info about each wire and its neighbors, used to calculate wire currents
    private final ArrayList<WireInfo> wireInfoList = new ArrayList<>(256);

    final ArrayList<Point> postDrawList = new ArrayList<>(64);
    final ArrayList<Point> badConnectionList = new ArrayList<>(64);

    private CircuitElm[] voltageSources;


    public CircuitSimulator(CirSim cirSim) {
        super(cirSim);
    }

    int locateElm(CircuitElm elm) {
        return elmList.indexOf(elm);
    }

    public CircuitElm getElm(int n) {
        return elmList.get(n);
    }


    int countSelected() {
        int count = 0;
        for (CircuitElm ce : elmList) {
            if (ce.isSelected()) {
                count++;
            }
        }
        return count;
    }

    void deleteUnusedScopeElms() {
        // Remove any scopeElms for elements that no longer exist
        for (int i = elmList.size() - 1; i >= 0; i--) {
            CircuitElm ce = elmList.get(i);
            if (ce instanceof ScopeElm && (((ScopeElm) ce).elmScope.needToRemove())) {
                ce.delete();
                elmList.remove(i);

                // need to rebuild scopeElmArr
                cirSim.needAnalyze();
            }
        }

    }

    // find groups of nodes connected by wire equivalents and map them to the same node.  this speeds things
    // up considerably by reducing the size of the matrix.  We do this for wires, labeled nodes, and ground.
    // The actual node we map to is not assigned yet.  Instead we map to the same NodeMapEntry.
    void calculateWireClosure() {
        LabeledNodeElm.resetNodeList();
        GroundElm.resetNodeList();

        nodeMap.clear();
        wireInfoList.clear();

        for (int i = 0; i < elmList.size(); i++) {
            CircuitElm ce = elmList.get(i);
            if (!ce.isRemovableWire()) {
                continue;
            }
            ce.hasWireInfo = false;
            wireInfoList.add(new WireInfo(ce));
            Point p0 = ce.getPost(0);
            NodeMapEntry cn = nodeMap.get(p0);

            // what post are we connected to
            Point p1 = ce.getConnectedPost();
            if (p1 == null) {
                // no connected post (true for labeled node the first time it's encountered, or ground)
                if (cn == null) {
                    cn = new NodeMapEntry();
                    nodeMap.put(p0, cn);
                }
                continue;
            }
            NodeMapEntry cn2 = nodeMap.get(p1);
            if (cn != null && cn2 != null) {
                // merge nodes; go through map and change all keys pointing to cn2 to point to cn
                for (Map.Entry<Point, NodeMapEntry> entry : nodeMap.entrySet()) {
                    if (entry.getValue() == cn2) {
                        entry.setValue(cn);
                    }
                }
                continue;
            }
            if (cn != null) {
                nodeMap.put(p1, cn);
                continue;
            }
            if (cn2 != null) {
                nodeMap.put(p0, cn2);
                continue;
            }
            // new entry
            cn = new NodeMapEntry();
            nodeMap.put(p0, cn);
            nodeMap.put(p1, cn);
        }

//	console("got " + (groupCount-mergeCount) + " groups with " + nodeMap.size() + " nodes " + mergeCount);
    }

    // generate info we need to calculate wire currents.  Most other elements calculate currents using
    // the voltage on their terminal nodes.  But wires have the same voltage at both ends, so we need
    // to use the neighbors' currents instead.  We used to treat wires as zero voltage sources to make
    // this easier, but this is very inefficient, since it makes the matrix 2 rows bigger for each wire.
    // We create a list of WireInfo objects instead to help us calculate the wire currents instead,
    // so we make the matrix less complex, and we only calculate the wire currents when we need them
    // (once per frame, not once per subiteration).  We need the WireInfos arranged in the correct order,
    // each one containing a list of neighbors and which end to use (since one end may be ready before
    // the other)
    boolean calcWireInfo() {
        int moved = 0;

        for (int i = 0; i != wireInfoList.size(); i++) {
            WireInfo wi = wireInfoList.get(i);
            CircuitElm wire = wi.wire;
            CircuitNode cn1 = nodeList.get(wire.getNode(0));  // both ends of wire have same node #
            int j;

            Vector<CircuitElm> neighbors0 = new Vector<>();
            Vector<CircuitElm> neighbors1 = new Vector<>();

            // assume each end is ready (except ground nodes which have one end)
            // labeled nodes are treated as having 2 terminals, see below
            boolean isReady0 = true, isReady1 = !(wire instanceof GroundElm);

            // go through elements sharing a node with this wire (may be connected indirectly
            // by other wires, but at least it's faster than going through all elements)
            for (j = 0; j != cn1.links.size(); j++) {
                CircuitNodeLink cnl = cn1.links.get(j);
                CircuitElm ce = cnl.elm;
                if (ce == wire) {
                    continue;
                }
                Point pt = ce.getPost(cnl.num);

                // is this a wire that doesn't have wire info yet?  If so we can't use it yet.
                // That would create a circular dependency.  So that side isn't ready.
                boolean notReady = (ce.isRemovableWire() && !ce.hasWireInfo);

                // which post does this element connect to, if any?
                if (pt.x == wire.x && pt.y == wire.y) {
                    neighbors0.add(ce);
                    if (notReady) {
                        isReady0 = false;
                    }
                } else if (wire.getPostCount() > 1) {
                    Point p2 = wire.getConnectedPost();
                    if (pt.x == p2.x && pt.y == p2.y) {
                        neighbors1.add(ce);
                        if (notReady) {
                            isReady1 = false;
                        }
                    }
                } else if (ce instanceof LabeledNodeElm && wire instanceof LabeledNodeElm &&
                        ((LabeledNodeElm) ce).text.equals(((LabeledNodeElm) wire).text)) {
                    // ce and wire are both labeled nodes with matching labels.  treat them as neighbors
                    neighbors1.add(ce);
                    if (notReady) {
                        isReady1 = false;
                    }
                }
            }

            // does one of the posts have all information necessary to calculate current?
            if (isReady0) {
                wi.neighbors = neighbors0;
                wi.post = 0;
                wire.hasWireInfo = true;
                moved = 0;
            } else if (isReady1) {
                wi.neighbors = neighbors1;
                wi.post = 1;
                wire.hasWireInfo = true;
                moved = 0;
            } else {
                // no, so move to the end of the list and try again later
                wireInfoList.add(wireInfoList.remove(i--));
                moved++;
                if (moved > wireInfoList.size() * 2) {
                    cirSim.stop("wire loop detected", wire);
                    return false;
                }
            }
        }

        return true;
    }

    // find or allocate ground node
    void setGroundNode(boolean subcircuit) {
        boolean gotGround = false;
        boolean gotRail = false;
        CircuitElm volt = null;

        //System.out.println("ac1");
        // look for voltage or ground element
        for (CircuitElm ce : elmList) {
            if (ce instanceof GroundElm) {
                gotGround = true;

                // set ground node to 0
                NodeMapEntry nme = nodeMap.get(ce.getPost(0));
                nme.node = 0;
                break;
            }
            if (ce instanceof RailElm) {
                gotRail = true;
            }
            if (volt == null && ce instanceof VoltageElm) {
                volt = ce;
            }
        }

        // if no ground, and no rails, then the voltage elm's first terminal
        // is ground (but not for subcircuits)
        if (!subcircuit && !gotGround && volt != null && !gotRail) {
            CircuitNode cn = new CircuitNode();
            Point pt = volt.getPost(0);
            nodeList.add(cn);

            // update node map
            NodeMapEntry cln = nodeMap.get(pt);
            if (cln != null) {
                cln.node = 0;
            } else {
                nodeMap.put(pt, new NodeMapEntry(0));
            }
        } else {
            // otherwise allocate extra node for ground
            CircuitNode cn = new CircuitNode();
            nodeList.add(cn);
        }
    }

    public CircuitNode getCircuitNode(int n) {
        if (n >= nodeList.size()) {
            return null;
        }
        return nodeList.get(n);
    }


    // make list of nodes
    void makeNodeList() {
        int j;
        int vscount = 0;
        for (CircuitElm ce : elmList) {
            int inodes = ce.getInternalNodeCount();
            int ivs = ce.getVoltageSourceCount();
            int posts = ce.getPostCount();

            // allocate a node for each post and match posts to nodes
            for (j = 0; j != posts; j++) {
                Point pt = ce.getPost(j);
                NodeMapEntry cln = nodeMap.get(pt);

                // is this node not in map yet?  or is the node number unallocated?
                // (we don't allocate nodes before this because changing the allocation order
                // of nodes changes circuit behavior and breaks backward compatibility;
                // the code below to connect unconnected nodes may connect a different node to ground)
                if (cln == null || cln.node == -1) {
                    CircuitNode cn = new CircuitNode();
                    CircuitNodeLink cnl = new CircuitNodeLink();
                    cnl.num = j;
                    cnl.elm = ce;
                    cn.links.add(cnl);
                    ce.setNode(j, nodeList.size());
                    if (cln != null) {
                        cln.node = nodeList.size();
                    } else {
                        nodeMap.put(pt, new NodeMapEntry(nodeList.size()));
                    }
                    nodeList.add(cn);
                } else {
                    int n = cln.node;
                    CircuitNodeLink cnl = new CircuitNodeLink();
                    cnl.num = j;
                    cnl.elm = ce;
                    getCircuitNode(n).links.add(cnl);
                    ce.setNode(j, n);
                    // if it's the ground node, make sure the node voltage is 0,
                    // cause it may not get set later
                    if (n == 0) {
                        ce.setNodeVoltage(j, 0);
                    }
                }
            }
            for (j = 0; j != inodes; j++) {
                CircuitNode cn = new CircuitNode();
                cn.internal = true;
                CircuitNodeLink cnl = new CircuitNodeLink();
                cnl.num = j + posts;
                cnl.elm = ce;
                cn.links.add(cnl);
                ce.setNode(cnl.num, nodeList.size());
                nodeList.add(cn);
            }

            // also count voltage sources so we can allocate array
            vscount += ivs;
        }

        voltageSources = new CircuitElm[vscount];
    }

    final ArrayList<Integer> unconnectedNodes = new ArrayList<>();
    final ArrayList<CircuitElm> nodesWithGroundConnection = new ArrayList<>();
    int nodesWithGroundConnectionCount;

    void findUnconnectedNodes() {
        int i, j;

        // determine nodes that are not connected indirectly to ground.
        // all nodes must be connected to ground somehow, or else we
        // will get a matrix error.
        boolean[] closure = new boolean[nodeList.size()];
        boolean changed = true;
        unconnectedNodes.clear();
        nodesWithGroundConnection.clear();
        closure[0] = true;
        while (changed) {
            changed = false;
            for (CircuitElm ce : elmList) {
                if (ce instanceof WireElm) {
                    continue;
                }
                // loop through all ce's nodes to see if they are connected
                // to other nodes not in closure
                boolean hasGround = false;
                for (j = 0; j < ce.getConnectionNodeCount(); j++) {
                    boolean hg = ce.hasGroundConnection(j);
                    if (hg) {
                        hasGround = true;
                    }
                    if (!closure[ce.getConnectionNode(j)]) {
                        if (hg) {
                            closure[ce.getConnectionNode(j)] = changed = true;
                        }
                        continue;
                    }
                    int k;
                    for (k = 0; k != ce.getConnectionNodeCount(); k++) {
                        if (j == k) {
                            continue;
                        }
                        int kn = ce.getConnectionNode(k);
                        if (ce.getConnection(j, k) && !closure[kn]) {
                            closure[kn] = true;
                            changed = true;
                        }
                    }
                }
                if (hasGround) {
                    nodesWithGroundConnection.add(ce);
                }
            }
            if (changed) {
                continue;
            }

            // connect one of the unconnected nodes to ground with a big resistor, then try again
            for (i = 0; i != nodeList.size(); i++) {
                if (!closure[i] && !getCircuitNode(i).internal) {
                    unconnectedNodes.add(i);
                    console("node " + i + " unconnected");
//		    stampResistor(0, i, 1e8);   // do this later in connectUnconnectedNodes()
                    closure[i] = true;
                    changed = true;
                    break;
                }
            }
        }
    }

    // take list of unconnected nodes, which we identified earlier, and connect them to ground
    // with a big resistor.  otherwise we will get matrix errors.  The resistor has to be big,
    // otherwise circuits like 555 Square Wave will break
    void connectUnconnectedNodes() {
        for (int n : unconnectedNodes) {
            stampResistor(0, n, 1e8);
        }
    }

    // do the rest of the pre-stamp circuit analysis
    boolean preStampCircuit(boolean subcircuit) {
        nodeList.clear();

        calculateWireClosure();
        setGroundNode(subcircuit);

        // allocate nodes and voltage sources
        makeNodeList();

        if (!calcWireInfo()) {
            return false;
        }
        nodeMap.clear(); // done with this

        int vscount = 0;
        circuitNonLinear = false;

        // determine if circuit is nonlinear.  also set voltage sources
        for (CircuitElm ce : elmList) {
            if (ce.nonLinear()) {
                circuitNonLinear = true;
            }
            int ivs = ce.getVoltageSourceCount();
            for (int j = 0; j != ivs; j++) {
                voltageSources[vscount] = ce;
                ce.setVoltageSource(j, vscount++);
            }
        }
        voltageSourceCount = vscount;

        // show resistance in voltage sources if there's only one.
        // can't use voltageSourceCount here since that counts internal voltage sources, like the one in GroundElm
        boolean gotVoltageSource = false;
        cirSim.circuitInfo.showResistanceInVoltageSources = true;
        for (CircuitElm ce : elmList) {
            if (ce instanceof VoltageElm) {
                if (gotVoltageSource) {
                    cirSim.circuitInfo.showResistanceInVoltageSources = false;
                } else {
                    gotVoltageSource = true;
                }
            }
        }

        findUnconnectedNodes();
        if (!validateCircuit()) {
            return false;
        }

        nodesWithGroundConnectionCount = nodesWithGroundConnection.size();
        // only need this for validation
        nodesWithGroundConnection.clear();

        timeStep = maxTimeStep;
        needsStamp = true;

        cirSim.callAnalyzeHook();
        return true;
    }

    // do pre-stamping and then stamp circuit
    void preStampAndStampCircuit() {
        int i;

        // preStampCircuit returns false if there's an error.  It can return false if we have capacitor loops
        // but we just need to try again in that case.  Try again 10 times to avoid infinite loop.
        for (i = 0; i != 10; i++) {
            if (preStampCircuit(false) || stopMessage != null) {
                break;
            }
        }
        if (stopMessage != null) {
            return;
        }
//        if (i == 10) {
//            cirSim.stop("failed to stamp circuit", null);
//            return;
//        }

        stampCircuit();
    }

    // stamp the matrix, meaning populate the matrix as required to simulate the circuit (for all linear elements, at least).
    // this gets called after something changes in the circuit, and also when auto-adjusting timestep
    void stampCircuit() {
        int i;
        int matrixSize = nodeList.size() - 1 + voltageSourceCount;
        circuitMatrix = new double[matrixSize][matrixSize];
        circuitRightSide = new double[matrixSize];
        nodeVoltages = new double[nodeList.size() - 1];
        if (lastNodeVoltages == null || lastNodeVoltages.length != nodeVoltages.length) {
            lastNodeVoltages = new double[nodeList.size() - 1];
        }
        origMatrix = new double[matrixSize][matrixSize];
        origRightSide = new double[matrixSize];
        circuitMatrixSize = circuitMatrixFullSize = matrixSize;
        circuitRowInfo = new RowInfo[matrixSize];
        circuitPermute = new int[matrixSize];
        for (i = 0; i != matrixSize; i++) {
            circuitRowInfo[i] = new RowInfo();
        }
        circuitNeedsMap = false;

        connectUnconnectedNodes();

        // stamp linear circuit elements
        for (CircuitElm ce : elmList) {
            ce.setParentList(elmList);
            ce.stamp();
        }

        if (!simplifyMatrix(matrixSize)) {
            return;
        }

        // check if we called stop()
        if (circuitMatrix == null) {
            return;
        }

        // if a matrix is linear, we can do the lu_factor here instead of
        // needing to do it every frame
        if (!circuitNonLinear) {
            if (!CircuitMath.lu_factor(circuitMatrix, circuitMatrixSize, circuitPermute)) {
                cirSim.stop("Singular matrix!", null);
                return;
            }
        }

        // copy elmList to an array to avoid a bunch of calls to canCast() when doing simulation
        elmArr = new CircuitElm[elmList.size()];
        int scopeElmCount = 0;
        for (i = 0; i != elmList.size(); i++) {
            elmArr[i] = elmList.get(i);
            if (elmArr[i] instanceof ScopeElm) {
                scopeElmCount++;
            }
        }

        // copy ScopeElms to an array to avoid a second pass over entire list of elms during simulation
        scopeElmArr = new ScopeElm[scopeElmCount];
        int j = 0;
        for (i = 0; i != elmList.size(); i++) {
            if (elmArr[i] instanceof ScopeElm) {
                scopeElmArr[j++] = (ScopeElm) elmArr[i];
            }
        }

        needsStamp = false;
    }

    // simplify the matrix; this speeds things up quite a bit, especially for digital circuits.
    // or at least it did before we added wire removal
    boolean simplifyMatrix(int matrixSize) {
        int i, j;
        for (i = 0; i != matrixSize; i++) {
            int qp = -1;
            double qv = 0;
            RowInfo re = circuitRowInfo[i];
	    /*System.out.println("row " + i + " " + re.lsChanges + " " + re.rsChanges + " " +
			       re.dropRow);*/

            //if (qp != -100) continue;   // uncomment this line to disable matrix simplification for debugging purposes

            if (re.lsChanges || re.dropRow || re.rsChanges) {
                continue;
            }
            double rsadd = 0;

            // see if this row can be removed
            for (j = 0; j != matrixSize; j++) {
                double q = circuitMatrix[i][j];
                if (circuitRowInfo[j].type == RowInfo.ROW_CONST) {
                    // keep a running total of const values that have been
                    // removed already
                    rsadd -= circuitRowInfo[j].value * q;
                    continue;
                }
                // ignore zeroes
                if (q == 0) {
                    continue;
                }
                // keep track of first nonzero element that is not ROW_CONST
                if (qp == -1) {
                    qp = j;
                    qv = q;
                    continue;
                }
                // more than one nonzero element?  give up
                break;
            }
            if (j == matrixSize) {
                if (qp == -1) {
                    // probably a singular matrix, try disabling matrix simplification above to check this
                    cirSim.stop("Matrix error", null);
                    return false;
                }
                RowInfo elt = circuitRowInfo[qp];
                // we found a row with only one nonzero nonconst entry; that value
                // is a constant
                if (elt.type != RowInfo.ROW_NORMAL) {
                    System.out.println("type already " + elt.type + " for " + qp + "!");
                    continue;
                }
                elt.type = RowInfo.ROW_CONST;
//		console("ROW_CONST " + i + " " + rsadd);
                elt.value = (circuitRightSide[i] + rsadd) / qv;
                circuitRowInfo[i].dropRow = true;
                // find first row that referenced the element we just deleted
                for (j = 0; j != i; j++) {
                    if (circuitMatrix[j][qp] != 0) {
                        break;
                    }
                }
                // start over just before that
                i = j - 1;
            }
        }
        //System.out.println("ac7");

        // find size of new matrix
        int nn = 0;
        for (i = 0; i != matrixSize; i++) {
            RowInfo elt = circuitRowInfo[i];
            if (elt.type == RowInfo.ROW_NORMAL) {
                elt.mapCol = nn++;
                //System.out.println("col " + i + " maps to " + elt.mapCol);
                continue;
            }
            if (elt.type == RowInfo.ROW_CONST) {
                elt.mapCol = -1;
            }
        }

        // make the new, simplified matrix
        int newsize = nn;
        double[][] newmatx = new double[newsize][newsize];
        double[] newrs = new double[newsize];
        int ii = 0;
        for (i = 0; i != matrixSize; i++) {
            RowInfo rri = circuitRowInfo[i];
            if (rri.dropRow) {
                rri.mapRow = -1;
                continue;
            }
            newrs[ii] = circuitRightSide[i];
            rri.mapRow = ii;
            //System.out.println("Row " + i + " maps to " + ii);
            for (j = 0; j != matrixSize; j++) {
                RowInfo ri = circuitRowInfo[j];
                if (ri.type == RowInfo.ROW_CONST) {
                    newrs[ii] -= ri.value * circuitMatrix[i][j];
                } else {
                    newmatx[ii][ri.mapCol] += circuitMatrix[i][j];
                }
            }
            ii++;
        }

//	console("old size = " + matrixSize + " new size = " + newsize);

        circuitMatrix = newmatx;
        circuitRightSide = newrs;
        matrixSize = circuitMatrixSize = newsize;
        for (i = 0; i != matrixSize; i++) {
            origRightSide[i] = circuitRightSide[i];
        }
        for (i = 0; i != matrixSize; i++) {
            for (j = 0; j != matrixSize; j++) {
                origMatrix[i][j] = circuitMatrix[i][j];
            }
        }
        circuitNeedsMap = true;
        return true;
    }

    // make list of posts we need to draw.  posts shared by 2 elements should be hidden, all
    // others should be drawn.  We can't use the node list for this purpose anymore because wires
    // have the same node number at both ends.
    void makePostDrawList() {
        HashMap<Point, Integer> postCountMap = new HashMap<>();
        int j;
        for (CircuitElm ce : elmList) {
            int posts = ce.getPostCount();
            for (j = 0; j != posts; j++) {
                Point pt = ce.getPost(j);
                postCountMap.compute(pt, (k, v) -> (v == null) ? 1 : v + 1);
            }
        }

        postDrawList.clear();
        badConnectionList.clear();
        for (Map.Entry<Point, Integer> entry : postCountMap.entrySet()) {
            if (entry.getValue() != 2) {
                postDrawList.add(entry.getKey());
            }

            // look for bad connections, posts not connected to other elements which intersect
            // other elements' bounding boxes
            if (entry.getValue() == 1) {
                boolean bad = false;
                Point cn = entry.getKey();
                for (j = 0; j != elmList.size() && !bad; j++) {
                    CircuitElm ce = elmList.get(j);
                    if (ce instanceof GraphicElm) {
                        continue;
                    }
                    // does this post intersect elm's bounding box?
                    if (!ce.boundingBox.contains(cn.x, cn.y)) {
                        continue;
                    }
                    int k;
                    // does this post belong to the elm?
                    int pc = ce.getPostCount();
                    for (k = 0; k != pc; k++) {
                        if (ce.getPost(k).equals(cn)) {
                            break;
                        }
                    }
                    if (k == pc) {
                        bad = true;
                    }
                }
                if (bad) {
                    badConnectionList.add(cn);
                }
            }
        }
    }

    String stopMessage;
    CircuitElm stopElm;

    boolean validateCircuit() {
        for (CircuitElm ce : elmList) {
            // look for inductors with no current path
            if (ce instanceof InductorElm) {
                FindPathInfo fpi = new FindPathInfo(this, FindPathInfo.INDUCT, ce, ce.getNode(1));
                if (!fpi.findPath(ce.getNode(0))) {
                    ce.reset();
                }
            }
            // look for current sources with no current path
            if (ce instanceof CurrentElm) {
                CurrentElm cur = (CurrentElm) ce;
                FindPathInfo fpi = new FindPathInfo(this, FindPathInfo.INDUCT, ce, ce.getNode(1));
                cur.setBroken(!fpi.findPath(ce.getNode(0)));
            }

            if (ce instanceof VCCSElm) {
                VCCSElm cur = (VCCSElm) ce;
                FindPathInfo fpi = new FindPathInfo(this, FindPathInfo.INDUCT, ce, cur.getOutputNode(0));
                cur.broken = cur.hasCurrentOutput() && !fpi.findPath(cur.getOutputNode(1));
            }

            // look for voltage source or wire loops.  we do this for voltage sources
            if (ce.getPostCount() == 2) {
                if (ce instanceof VoltageElm) {
                    FindPathInfo fpi = new FindPathInfo(this, FindPathInfo.VOLTAGE, ce, ce.getNode(1));
                    if (fpi.findPath(ce.getNode(0))) {
                        cirSim.stop("Voltage source/wire loop with no resistance!", ce);
                        return false;
                    }
                }
            }

            // look for path from rail to ground
            if (ce instanceof RailElm || ce instanceof LogicInputElm) {
                FindPathInfo fpi = new FindPathInfo(this, FindPathInfo.VOLTAGE, ce, ce.getNode(0));
                if (fpi.findPath(0)) {
                    cirSim.stop("Path to ground with no resistance!", ce);
                    return false;
                }
            }

            // look for shorted caps, or caps w/ voltage but no R
            if (ce instanceof CapacitorElm) {
                FindPathInfo fpi = new FindPathInfo(this, FindPathInfo.SHORT, ce, ce.getNode(1));
                if (fpi.findPath(ce.getNode(0))) {
                    console(ce + " shorted");
                    ((CapacitorElm) ce).shorted();
                } else {
                    fpi = new FindPathInfo(this, FindPathInfo.CAP_V, ce, ce.getNode(1));
                    if (fpi.findPath(ce.getNode(0))) {
                        // loop of ideal capacitors; set a small series resistance to avoid
                        // oscillation in case one of them has voltage on it
                        ((CapacitorElm) ce).setSeriesResistance(.1);

                        // return false to re-stamp the circuit
                        return false;
                    }
                }
            }
        }
        return true;
    }

    boolean needsStamp;

    // analyze the circuit when something changes, so it can be simulated.
    // Most of this has been moved to preStampCircuit() so it can be avoided if the simulation is stopped.
    void analyzeCircuit() {
        stopMessage = null;
        stopElm = null;
        if (elmList.isEmpty()) {
            postDrawList.clear();
            badConnectionList.clear();
            return;
        }
        makePostDrawList();

        needsStamp = true;
    }

    // stamp value x in row i, column j, meaning that a voltage change
    // of dv in node j will increase the current into node i by x dv.
    // (Unless i or j is a voltage source node.)
    public void stampMatrix(int i, int j, double x) {
        if (i > 0 && j > 0) {
            if (circuitNeedsMap) {
                i = circuitRowInfo[i - 1].mapRow;
                RowInfo ri = circuitRowInfo[j - 1];
                if (ri.type == RowInfo.ROW_CONST) {
                    //System.out.println("Stamping constant " + i + " " + j + " " + x);
                    circuitRightSide[i] -= x * ri.value;
                    return;
                }
                j = ri.mapCol;
                //System.out.println("stamping " + i + " " + j + " " + x);
            } else {
                i--;
                j--;
            }
            circuitMatrix[i][j] += x;
        }
    }

    // stamp value x on the right side of row i, representing an
    // independent current source flowing into node i
    public void stampRightSide(int i, double x) {
        if (i > 0) {
            if (circuitNeedsMap) {
                i = circuitRowInfo[i - 1].mapRow;
                //System.out.println("stamping " + i + " " + x);
            } else {
                i--;
            }
            circuitRightSide[i] += x;
        }
    }

    // indicate that the value on the right side of row i changes in doStep()
    public void stampRightSide(int i) {
        //System.out.println("rschanges true " + (i-1));
        if (i > 0) {
            circuitRowInfo[i - 1].rsChanges = true;
        }
    }

    // indicate that the values on the left side of row i change in doStep()
    public void stampNonLinear(int i) {
        if (i > 0) {
            circuitRowInfo[i - 1].lsChanges = true;
        }
    }

    // control voltage source vs with voltage from n1 to n2 (must
    // also call stampVoltageSource())
    public void stampVCVS(int n1, int n2, double coef, int vs) {
        int vn = nodeList.size() + vs;
        stampMatrix(vn, n1, coef);
        stampMatrix(vn, n2, -coef);
    }

    // stamp independent voltage source #vs, from n1 to n2, amount v
    public void stampVoltageSource(int n1, int n2, int vs, double v) {
        int vn = nodeList.size() + vs;
        stampMatrix(vn, n1, -1);
        stampMatrix(vn, n2, 1);
        stampRightSide(vn, v);
        stampMatrix(n1, vn, 1);
        stampMatrix(n2, vn, -1);
    }

    // use this if the amount of voltage is going to be updated in doStep(), by updateVoltageSource()
    public void stampVoltageSource(int n1, int n2, int vs) {
        int vn = nodeList.size() + vs;
        stampMatrix(vn, n1, -1);
        stampMatrix(vn, n2, 1);
        stampRightSide(vn);
        stampMatrix(n1, vn, 1);
        stampMatrix(n2, vn, -1);
    }

    // update voltage source in doStep()
    public void updateVoltageSource(int n1, int n2, int vs, double v) {
        int vn = nodeList.size() + vs;
        stampRightSide(vn, v);
    }

    public void stampResistor(int n1, int n2, double r) {
        double r0 = 1 / r;
        if (Double.isNaN(r0) || Double.isInfinite(r0)) {
            System.out.print("bad resistance " + r + " " + r0 + "\n");
            int a = 0;
            a /= a;
        }
        stampMatrix(n1, n1, r0);
        stampMatrix(n2, n2, r0);
        stampMatrix(n1, n2, -r0);
        stampMatrix(n2, n1, -r0);
    }

    public void stampConductance(int n1, int n2, double r0) {
        stampMatrix(n1, n1, r0);
        stampMatrix(n2, n2, r0);
        stampMatrix(n1, n2, -r0);
        stampMatrix(n2, n1, -r0);
    }

    // specify that current from cn1 to cn2 is equal to voltage from vn1 to 2, divided by g
    public void stampVCCurrentSource(int cn1, int cn2, int vn1, int vn2, double g) {
        stampMatrix(cn1, vn1, g);
        stampMatrix(cn2, vn2, g);
        stampMatrix(cn1, vn2, -g);
        stampMatrix(cn2, vn1, -g);
    }

    public void stampCurrentSource(int n1, int n2, double i) {
        stampRightSide(n1, -i);
        stampRightSide(n2, i);
    }

    // stamp a current source from n1 to n2 depending on current through vs
    public void stampCCCS(int n1, int n2, int vs, double gain) {
        int vn = nodeList.size() + vs;
        stampMatrix(n1, vn, gain);
        stampMatrix(n2, vn, -gain);
    }

    // set node voltages given right side found by solving matrix
    void applySolvedRightSide(double[] rs) {
//	console("setvoltages " + rs);
        int j;
        for (j = 0; j != circuitMatrixFullSize; j++) {
            RowInfo ri = circuitRowInfo[j];
            double res;
            if (ri.type == RowInfo.ROW_CONST) {
                res = ri.value;
            } else {
                res = rs[ri.mapCol];
            }
            if (Double.isNaN(res)) {
                converged = false;
                break;
            }
            if (j < nodeList.size() - 1) {
                nodeVoltages[j] = res;
            } else {
                int ji = j - (nodeList.size() - 1);
                voltageSources[ji].setCurrent(ji, res);
            }
        }

        setNodeVoltages(nodeVoltages);
    }

    // set node voltages in each element given an array of node voltages
    void setNodeVoltages(double[] nv) {
        for (int j = 0; j != nv.length; j++) {
            double res = nv[j];
            CircuitNode cn = getCircuitNode(j + 1);
            for (int k = 0; k != cn.links.size(); k++) {
                CircuitNodeLink cnl = cn.links.get(k);
                cnl.elm.setNodeVoltage(cnl.num, res);
            }
        }
    }

    // we removed wires from the matrix to speed things up.  in order to display wire currents,
    // we need to calculate them now.
    void calcWireCurrents() {
        // for debugging
        //for (i = 0; i != wireInfoList.size(); i++)
        //   wireInfoList.get(i).wire.setCurrent(-1, 1.23);

        for (WireInfo wi : wireInfoList) {
            double cur = 0;
            Point p = wi.wire.getPost(wi.post);
            for (CircuitElm ce : wi.neighbors) {
                int n = ce.getNodeAtPoint(p.x, p.y);
                cur += ce.getCurrentIntoNode(n);
            }
            // get correct current polarity
            // (LabeledNodes may have wi.post == 1, in which case we flip the current sign)
            if (wi.post == 0 || (wi.wire instanceof LabeledNodeElm)) {
                wi.wire.setCurrent(-1, cur);
            } else {
                wi.wire.setCurrent(-1, -cur);
            }
        }
    }

    int countScopeElms() {
        int c = 0;
        for (CircuitElm elm : elmList) {
            if (elm instanceof ScopeElm) {
                c++;
            }
        }
        return c;
    }

    ScopeElm getNthScopeElm(int n) {
        for (CircuitElm elm : elmList) {
            if (elm instanceof ScopeElm) {
                n--;
                if (n < 0) {
                    return (ScopeElm) elm;
                }
            }
        }
        return null;
    }

    public void updateModels() {
        for (CircuitElm elm : elmList) {
            elm.updateModels();
        }
    }

    boolean isSelection() {
        for (CircuitElm elm : elmList) {
            if (elm.isSelected()) {
                return true;
            }
        }
        return false;
    }

    public CustomCompositeModel getCircuitAsComposite() {
        String nodeDump;
        String dump;
//	    String models = "";
        CustomLogicModel.clearDumpedFlags();
        DiodeModel.clearDumpedFlags();
        TransistorModel.clearDumpedFlags();
        @SuppressWarnings("unchecked") Vector<LabeledNodeElm>[] sideLabels = new Vector[]{
                new Vector<>(), new Vector<>(),
                new Vector<>(), new Vector<>()
        };
        Vector<ExtListEntry> extList = new Vector<>();
        boolean sel = isSelection();

        boolean[] used = new boolean[nodeList.size()];
        boolean[] extnodes = new boolean[nodeList.size()];

        // redo node allocation to avoid auto-assigning ground
        if (!preStampCircuit(true)) {
            return null;
        }

        // find all the labeled nodes, get a list of them, and create a node number map
        for (CircuitElm ce : elmList) {
            if (sel && !ce.isSelected()) {
                continue;
            }
            if (ce instanceof LabeledNodeElm) {
                LabeledNodeElm lne = (LabeledNodeElm) ce;
                if (lne.isInternal()) {
                    continue;
                }

                // already added to list?
                if (extnodes[ce.getNode(0)]) {
                    continue;
                }

                int side = ChipElm.SIDE_W;
                if (Math.abs(ce.dx) >= Math.abs(ce.dy) && ce.dx > 0) {
                    side = ChipElm.SIDE_E;
                }
                if (Math.abs(ce.dx) <= Math.abs(ce.dy) && ce.dy < 0) {
                    side = ChipElm.SIDE_N;
                }
                if (Math.abs(ce.dx) <= Math.abs(ce.dy) && ce.dy > 0) {
                    side = ChipElm.SIDE_S;
                }

                // create ext list entry for external nodes
                sideLabels[side].add(lne);
                extnodes[ce.getNode(0)] = true;
                if (ce.getNode(0) == 0) {
                    Window.alert("Node \"" + lne.text + "\" can't be connected to ground");
                    return null;
                }
            }
        }

        sideLabels[ChipElm.SIDE_W].sort((a, b) -> Integer.signum(a.y - b.y));
        sideLabels[ChipElm.SIDE_E].sort((a, b) -> Integer.signum(a.y - b.y));
        sideLabels[ChipElm.SIDE_N].sort((a, b) -> Integer.signum(a.x - b.x));
        sideLabels[ChipElm.SIDE_S].sort((a, b) -> Integer.signum(a.x - b.x));

        for (int side = 0; side < sideLabels.length; side++) {
            for (int pos = 0; pos < sideLabels[side].size(); pos++) {
                LabeledNodeElm lne = sideLabels[side].get(pos);
                ExtListEntry ent = new ExtListEntry(lne.text, lne.getNode(0), pos, side);
                extList.add(ent);
            }
        }

        // output all the elements
        StringBuilder nodeDumpBuilder = new StringBuilder();
        StringBuilder dumpBuilder = new StringBuilder();
        for (CircuitElm ce : elmList) {
            if (sel && !ce.isSelected()) {
                continue;
            }
            // don't need these elements dumped
            if (ce instanceof WireElm || ce instanceof LabeledNodeElm || ce instanceof ScopeElm) {
                continue;
            }
            if (ce instanceof GraphicElm || ce instanceof GroundElm) {
                continue;
            }
            int j;
            if (!nodeDumpBuilder.toString().isEmpty()) {
                nodeDumpBuilder.append("\r");
            }
            nodeDumpBuilder.append(ce.getClass().getSimpleName());
            for (j = 0; j != ce.getPostCount(); j++) {
                int n = ce.getNode(j);
                used[n] = true;
                nodeDumpBuilder.append(" ").append(n);
            }

            // save positions
            int x1 = ce.x;
            int y1 = ce.y;
            int x2 = ce.x2;
            int y2 = ce.y2;

            // set them to 0 so they're easy to remove
            ce.x = ce.y = ce.x2 = ce.y2 = 0;

            String tstring = ce.dump();
            tstring = tstring.replaceFirst("[A-Za-z0-9]+ 0 0 0 0 ", ""); // remove unused tint_x1 y1 x2 y2 coords for internal components

            // restore positions
            ce.x = x1;
            ce.y = y1;
            ce.x2 = x2;
            ce.y2 = y2;
            if (!dumpBuilder.toString().isEmpty()) {
                dumpBuilder.append(" ");
            }
            dumpBuilder.append(CustomLogicModel.escape(tstring));
        }
        dump = dumpBuilder.toString();
        nodeDump = nodeDumpBuilder.toString();

        for (ExtListEntry ent : extList) {
            if (!used[ent.node]) {
                Window.alert("Node \"" + ent.name + "\" is not used!");
                return null;
            }
        }

        boolean first = true;
        for (int q : unconnectedNodes) {
            if (!extnodes[q] && used[q]) {
                if (nodesWithGroundConnectionCount == 0 && first) {
                    first = false;
                    continue;
                }
                Window.alert("Some nodes are unconnected!");
                return null;
            }
        }

        CustomCompositeModel ccm = new CustomCompositeModel();
        ccm.nodeList = nodeDump;
        ccm.elmDump = dump;
        ccm.extList = extList;
        return ccm;
    }


    public boolean converged; // TODO: Add checkConverged()
    public int subIterations;

    boolean dumpMatrix;
    long lastIterTime;
    int steps = 0;

    void runCircuit(boolean didAnalyze) {
        if (circuitMatrix == null || elmList.isEmpty()) {
            circuitMatrix = null;
            return;
        }
        //int maxIter = getIterCount();
        boolean debugprint = dumpMatrix;
        dumpMatrix = false;
        long steprate = (long) (160 * cirSim.getIterCount());
        long tm = System.currentTimeMillis();
        long lit = lastIterTime;
        if (lit == 0) {
            lastIterTime = tm;
            return;
        }

        // Check if we don't need to run simulation (for very slow simulation speeds).
        // If the circuit changed, do at least one iteration to make sure everything is consistent.
        if (1000 >= steprate * (tm - lastIterTime) && !didAnalyze) {
            return;
        }

        boolean delayWireProcessing = cirSim.scopeManager.canDelayWireProcessing();

        int timeStepCountAtFrameStart = timeStepCount;

        // keep track of iterations completed without convergence issues
        int goodIterations = 100;

        int frameTimeLimit = (int) (1000 / minFrameRate);

        for (int iter = 1; ; iter++) {
            if (goodIterations >= 3 && timeStep < maxTimeStep) {
                // things are going well, double the time step
                timeStep = Math.min(timeStep * 2, maxTimeStep);
                console("timestep up = " + timeStep + " at " + t);
                stampCircuit();
                goodIterations = 0;
            }

            int i, j, subiter;
            for (i = 0; i != elmArr.length; i++) {
                elmArr[i].startIteration();
            }
            steps++;
            int subiterCount = (adjustTimeStep && timeStep / 2 > minTimeStep) ? 100 : 5000;
            for (subiter = 0; subiter != subiterCount; subiter++) {
                converged = true;
                subIterations = subiter;
//		if (t % .030 < .002 && timeStep > 1e-6)  // force nonconvergence for debugging
//		    converged = false;
                for (i = 0; i != circuitMatrixSize; i++) {
                    circuitRightSide[i] = origRightSide[i];
                }
                if (circuitNonLinear) {
                    for (i = 0; i != circuitMatrixSize; i++) {
                        for (j = 0; j != circuitMatrixSize; j++) {
                            circuitMatrix[i][j] = origMatrix[i][j];
                        }
                    }
                }
                for (i = 0; i != elmArr.length; i++) {
                    elmArr[i].doStep();
                }
                if (stopMessage != null) {
                    return;
                }
                boolean printit = debugprint;
                debugprint = false;
                if (circuitMatrixSize < 8) {
                    // we only need this for debugging purposes, so skip it for large matrices
                    for (j = 0; j != circuitMatrixSize; j++) {
                        for (i = 0; i != circuitMatrixSize; i++) {
                            double x = circuitMatrix[i][j];
                            if (Double.isNaN(x) || Double.isInfinite(x)) {
                                cirSim.stop("nan/infinite matrix!", null);
                                console("circuitMatrix " + i + " " + j + " is " + x);
                                return;
                            }
                        }
                    }
                }
                if (printit) {
                    StringBuilder xBuilder = new StringBuilder();
                    for (j = 0; j != circuitMatrixSize; j++) {
                        for (i = 0; i != circuitMatrixSize; i++) {
                            xBuilder.append(circuitMatrix[j][i]).append(",");
                        }
                        xBuilder.append("\n");
                        console(xBuilder.toString());
                    }
                    console("done");
                }
                if (circuitNonLinear) {
                    // stop if converged (elements check for convergence in doStep())
                    if (converged && subiter > 0) {
                        break;
                    }
                    if (!CircuitMath.lu_factor(circuitMatrix, circuitMatrixSize, circuitPermute)) {
                        cirSim.stop("Singular matrix!", null);
                        return;
                    }
                }
                CircuitMath.lu_solve(circuitMatrix, circuitMatrixSize, circuitPermute, circuitRightSide);
                applySolvedRightSide(circuitRightSide);
                if (!circuitNonLinear) {
                    break;
                }
            }
            if (subiter == subiterCount) {
                // convergence failed
                goodIterations = 0;
                if (adjustTimeStep) {
                    timeStep /= 2;
                    console("timestep down to " + timeStep + " at " + t);
                }
                if (timeStep < minTimeStep || !adjustTimeStep) {
                    console("convergence failed after " + subiter + " iterations");
                    cirSim.stop("Convergence failed!", null);
                    break;
                }
                // we reduced the timestep.  reset circuit state to the way it was at start of iteration
                setNodeVoltages(lastNodeVoltages);
                stampCircuit();
                continue;
            }
            if (subiter > 5 || timeStep < maxTimeStep) {
                console("converged after " + subiter + " iterations, timeStep = " + timeStep);
            }
            if (subiter < 3) {
                goodIterations++;
            } else {
                goodIterations = 0;
            }
            this.t += timeStep;
            timeStepAccum += timeStep;
            if (timeStepAccum >= maxTimeStep) {
                timeStepAccum -= maxTimeStep;
                timeStepCount++;
            }
            for (i = 0; i != elmArr.length; i++) {
                elmArr[i].stepFinished();
            }
            if (!delayWireProcessing) {
                calcWireCurrents();
            }
            for (i = 0; i != cirSim.scopeManager.scopeCount; i++) {
                cirSim.scopeManager.scopes[i].timeStep();
            }
            for (i = 0; i != scopeElmArr.length; i++) {
                scopeElmArr[i].stepScope();
            }
            cirSim.callTimeStepHook();
            // save last node voltages so we can restart the next iteration if necessary
            System.arraycopy(nodeVoltages, 0, lastNodeVoltages, 0, lastNodeVoltages.length);
//	    console("set lastrightside at " + t + " " + lastNodeVoltages);

            tm = System.currentTimeMillis();
            lit = tm;
            // Check whether enough time has elapsed to perform an *additional* iteration after
            // those we have already completed.  But limit total computation time to 50ms (20fps) by default
            if ((long) (timeStepCount - timeStepCountAtFrameStart) * 1000 >= steprate * (tm - lastIterTime) || (tm - cirSim.renderer.lastFrameTime > frameTimeLimit)) {
                break;
            }
            if (!simRunning) {
                break;
            }
        } // for (iter = 1; ; iter++)
        lastIterTime = lit;
        if (delayWireProcessing) {
            calcWireCurrents();
        }
//	System.out.println((System.currentTimeMillis()-lastFrameTime)/(double) iter);
    }

    String dumpSelectedItems() {
        StringBuilder data = new StringBuilder();
        for (int i = elmList.size() - 1; i >= 0; i--) {
            CircuitElm ce = elmList.get(i);
            String m = ce.dumpModel();
            if (m != null && !m.isEmpty()) {
                data.append(m).append("\n");
            }
            // See notes on do cut why we don't copy ScopeElms.
            if (ce.isSelected() && !(ce instanceof ScopeElm)) {
                data.append(ce.dump()).append("\n");
            }
        }
        return data.toString();
    }

}

