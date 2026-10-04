package com.lushprojects.circuitjs1.client.agent;

import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONBoolean;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;
import com.lushprojects.circuitjs1.client.CircuitDocument;
import com.lushprojects.circuitjs1.client.CircuitSimulator;
import com.lushprojects.circuitjs1.client.DocumentScope;
import com.lushprojects.circuitjs1.client.Point;
import com.lushprojects.circuitjs1.client.element.CircuitElm;
import com.lushprojects.circuitjs1.client.element.CurrentElm;
import com.lushprojects.circuitjs1.client.element.GroundElm;
import com.lushprojects.circuitjs1.client.element.LabeledNodeElm;
import com.lushprojects.circuitjs1.client.element.OhmMeterElm;
import com.lushprojects.circuitjs1.client.element.RailElm;
import com.lushprojects.circuitjs1.client.element.VoltageElm;
import com.lushprojects.circuitjs1.client.element.WireElm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Connectivity of one document from its own analysed node data ([SP_AGA_01_06], [SP_AGA_03_05],
 * [SP_AGA_02_06]):
 * <ul>
 * <li>{@link Nets} — the posts grouped by simulator node, named {@code gnd}, by the smallest label
 *     text (escaped as {@code label:<text>} when reserved) or {@code $<k>} (k numbers the
 *     unlabelled nets by their smallest member PostRef, see {@link #nets}). Net names never come
 *     from the session-wide label registry ([SP_AGA_03_08] "Net names and readings"): label
 *     texts are read from the document's own label elements and their analysed nodes.</li>
 * <li>{@link Report} — the nets plus the issues of the connectivity rules table.</li>
 * <li>{@link #delta} — the ConnectivityDelta of a mutation, by issue key, with the 50-entry caps.</li>
 * </ul>
 * Every entry expects the target document bound ({@code DocumentScope}); {@link #analyse} makes
 * its analysis current first ({@link CircuitDocument#ensureAnalysed()}).
 */
final class Connectivity {

    /** [SP_AGA_03_07] getConnectivity caps. */
    static final int MAX_NETS = 200;
    static final int MAX_REPORT_ISSUES = 100;
    /** [SP_AGA_01_06] ConnectivityDelta caps of {@code added} and {@code cleared}. */
    static final int MAX_DELTA = 50;
    /** Maximum number of names in {@code netFilter}. */
    static final int MAX_FILTER = 1000;

    private Connectivity() {
    }

    static void register(AgentApi api) {
        api.register("getConnectivity", AgentApi.DocPolicy.OPTIONAL, AgentApi.BusyPolicy.SERVED, Connectivity::getConnectivity);
    }

    // ---------------------------------------------------------------- nets

    /** One net: a simulator node with at least one external post. */
    static final class Net {
        final int node;
        String name;
        final List<String> posts = new ArrayList<>();
        int wires;
        /** Smallest PostRef of a wire end in the net (orders nets that have only wires). */
        String firstWireRef;
        final TreeSet<String> labels = new TreeSet<>();

        Net(int node) {
            this.node = node;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("name", new JSONString(name));
            o.put("posts", strings(posts));
            o.put("wires", new JSONNumber(wires));
            o.put("labels", strings(new ArrayList<>(labels)));
            return o;
        }
    }

    /** The nets of a document's last analysis. */
    static final class Nets {
        /** false when the node data does not describe the current elements (issues explain why). */
        boolean analysed = true;
        /** Sorted by name. */
        final List<Net> list = new ArrayList<>();
        final Map<Integer, Net> byNode = new HashMap<>();
        /** Canonical names and the label aliases (a label text, {@code label:<text>}). */
        final Map<String, Net> byName = new HashMap<>();

        /** @return the NetName of simulator node {@code node}, or null when unknown */
        String nameOf(int node) {
            Net n = analysed ? byNode.get(node) : null;
            return n == null ? null : n.name;
        }

        /** @return the net named {@code name} (canonical name or label alias), or null */
        Net find(String name) {
            return name == null ? null : byName.get(name);
        }
    }

    /** @return true when a label text must be written {@code label:<text>} ([SP_AGA_01_06]) */
    static boolean isReservedLabel(String text) {
        return "gnd".equals(text) || text.startsWith("$") || text.startsWith("label:");
    }

    /** @return the post-to-node grouping and names of the document's current analysis */
    static Nets nets(CircuitDocument doc) {
        Nets nets = new Nets();
        if (doc.isAnalysisFailed()) {
            // the stamp of the current analysis threw: node indices are stale
            nets.analysed = false;
            return nets;
        }
        CircuitSimulator sim = doc.simulator;
        int nodeCount = sim.nodeList.size();
        for (CircuitElm elm : sim.elmList) {
            int pc = elm.getPostCount();
            if (pc <= 0) {
                continue;
            }
            String[] pins = PinNames.of(elm);
            boolean wire = elm instanceof WireElm;
            for (int j = 0; j < pc; j++) {
                int node = elm.getNode(j);
                if (node < 0 || node >= nodeCount) {
                    nets.analysed = false;
                    continue;
                }
                Net net = nets.byNode.get(node);
                if (net == null) {
                    net = new Net(node);
                    nets.byNode.put(node, net);
                }
                if (wire) {
                    if (j == 0) {
                        net.wires++;
                    }
                    String ref = postRef(elm, pins, j);
                    if (net.firstWireRef == null || ref.compareTo(net.firstWireRef) < 0) {
                        net.firstWireRef = ref;
                    }
                } else {
                    net.posts.add(postRef(elm, pins, j));
                }
                if (elm instanceof LabeledNodeElm && j == 0) {
                    net.labels.add(((LabeledNodeElm) elm).text);
                }
            }
        }
        if (!nets.analysed) {
            nets.byNode.clear();
            return nets;
        }
        List<Net> unnamed = new ArrayList<>();
        for (Net net : nets.byNode.values()) {
            Collections.sort(net.posts);
            if (net.node == 0) {
                net.name = "gnd";
            } else if (!net.labels.isEmpty()) {
                String text = net.labels.first();
                net.name = isReservedLabel(text) ? "label:" + text : text;
            } else {
                unnamed.add(net);
                continue;
            }
            nets.list.add(net);
            nets.byName.put(net.name, net);
        }
        // $<k>: k numbers the unlabelled nets in the order of their smallest member PostRef, not
        // the simulator's node index, which follows the element order and so would rename nets
        // when a getCircuit output (listed by ID) is imported back (SP_AGA_05_01 round trips).
        Collections.sort(unnamed, (a, b) -> orderKey(a).compareTo(orderKey(b)));
        for (int i = 0; i < unnamed.size(); i++) {
            Net net = unnamed.get(i);
            net.name = "$" + (i + 1);
            nets.list.add(net);
            nets.byName.put(net.name, net);
        }
        // label aliases never shadow a canonical name
        for (Net net : nets.list) {
            for (String text : net.labels) {
                if (!isReservedLabel(text) && !nets.byName.containsKey(text)) {
                    nets.byName.put(text, net);
                }
                if (!nets.byName.containsKey("label:" + text)) {
                    nets.byName.put("label:" + text, net);
                }
            }
        }
        Collections.sort(nets.list, (a, b) -> a.name.compareTo(b.name));
        return nets;
    }

    private static String orderKey(Net net) {
        return !net.posts.isEmpty() ? net.posts.get(0) : net.firstWireRef != null ? net.firstWireRef : "";
    }

    static String postRef(CircuitElm elm, String[] pins, int j) {
        return elm.getElementId() + "." + (j < pins.length ? pins[j] : "#" + j);
    }

    // ---------------------------------------------------------------- report

    /** A ConnectivityReport ([SP_AGA_01_06]) before capping. */
    static final class Report {
        final Nets nets;
        /** All issues, errors first (uncapped). */
        final List<Issue> issues;
        final boolean implicitGround;

        Report(Nets nets, List<Issue> issues, boolean implicitGround) {
            this.nets = nets;
            this.issues = issues;
            this.implicitGround = implicitGround;
        }

        int count(Issue.Severity severity) {
            int n = 0;
            for (Issue i : issues) {
                if (i.getSeverity() == severity) {
                    n++;
                }
            }
            return n;
        }

        /** @param nets the nets to list (null: all, cut at {@link #MAX_NETS}) */
        JSONObject toJson(boolean includeNets, List<Net> nets) {
            boolean truncated = false;
            JSONArray list = new JSONArray();
            if (includeNets) {
                List<Net> shown = nets != null ? nets : this.nets.list;
                int n = nets != null ? shown.size() : Math.min(shown.size(), MAX_NETS);
                truncated = n < shown.size();
                for (int i = 0; i < n; i++) {
                    list.set(i, shown.get(i).toJson());
                }
            }
            JSONArray issueList = new JSONArray();
            int n = Math.min(issues.size(), MAX_REPORT_ISSUES);
            truncated |= n < issues.size();
            for (int i = 0; i < n; i++) {
                issueList.set(i, issues.get(i).toJson());
            }
            JSONObject o = new JSONObject();
            o.put("nets", list);
            o.put("issues", issueList);
            o.put("implicitGround", JSONBoolean.getInstance(implicitGround));
            o.put("analysed", JSONBoolean.getInstance(this.nets.analysed));
            o.put("truncated", JSONBoolean.getInstance(truncated));
            return o;
        }
    }

    /** One post of one element at its position. */
    private static final class PostAt {
        final CircuitElm elm;
        final int index;
        final String ref;
        final Point at;
        final int node;

        PostAt(CircuitElm elm, int index, String ref, Point at, int node) {
            this.elm = elm;
            this.index = index;
            this.ref = ref;
            this.at = at;
            this.node = node;
        }
    }

    /**
     * Makes the document's analysis current and computes its ConnectivityReport. The document
     * must be bound.
     */
    static Report analyse(CircuitDocument doc) {
        boolean ran = doc.ensureAnalysed();
        Nets nets = nets(doc);
        List<Issue> issues = new ArrayList<>();
        if (!ran || !nets.analysed) {
            nets.analysed = false;
            nets.list.clear();
            nets.byName.clear();
            nets.byNode.clear();
            String why = doc.getErrorMessage() != null ? doc.getErrorMessage() : "the node data does not match the circuit";
            issues.add(Issue.of(IssueCode.ANALYSIS_FAILED, "The circuit could not be analysed: " + why + ".",
                    "Fix the reported problem (getDiagnostics shows the solver state), then read the connectivity again."));
        }
        boolean implicitGround = rules(doc, nets, issues);
        return new Report(nets, sortBySeverity(issues), implicitGround);
    }

    /**
     * [SP_AGA_03_05] The connectivity issue rules, in table order.
     *
     * @return whether the simulator assumed ground at a voltage-source post
     */
    private static boolean rules(CircuitDocument doc, Nets nets, List<Issue> issues) {
        CircuitSimulator sim = doc.simulator;
        List<CircuitElm> elms = sim.elmList;
        Map<Point, List<PostAt>> byPoint = new LinkedHashMap<>();
        List<PostAt> all = new ArrayList<>();
        List<CircuitElm> wires = new ArrayList<>();
        boolean gotGround = false;
        boolean gotRail = false;
        boolean gotVoltage = false;
        boolean gotGroundReference = false;
        boolean anyPost = false;
        for (CircuitElm elm : elms) {
            if (elm instanceof GroundElm) {
                gotGround = true;
            } else if (elm instanceof RailElm) {
                gotRail = true;
            }
            if (elm instanceof VoltageElm) {
                gotVoltage = true;
            }
            if (elm instanceof WireElm) {
                wires.add(elm);
            }
            int pc = elm.getPostCount();
            String[] pins = pc > 0 ? PinNames.of(elm) : null;
            for (int j = 0; j < pc; j++) {
                Point p = elm.getPost(j);
                if (p == null) {
                    continue;
                }
                anyPost = true;
                // rails, logic inputs, gates, chips and op-amp outputs are referenced to ground internally
                gotGroundReference |= elm.hasGroundConnection(j);
                PostAt pa = new PostAt(elm, j, postRef(elm, pins, j), p, nets.analysed ? elm.getNode(j) : -1);
                all.add(pa);
                List<PostAt> list = byPoint.get(p);
                if (list == null) {
                    list = new ArrayList<>();
                    byPoint.put(p, list);
                }
                list.add(pa);
            }
        }
        // setGroundNode: no ground, no rail, a voltage source -> its first post is ground
        boolean implicitGround = !gotGround && !gotRail && gotVoltage;

        // dangling_post: geometric — a post position no other element's post shares
        for (List<PostAt> list : byPoint.values()) {
            CircuitElm owner = list.get(0).elm;
            boolean alone = true;
            for (PostAt pa : list) {
                alone &= pa.elm == owner;
            }
            if (!alone) {
                continue;
            }
            for (PostAt pa : list) {
                if (doc.hasOpenMark(pa.ref)) {
                    continue;
                }
                boolean onePost = pa.elm.getPostCount() == 1;
                issues.add(Issue.of(IssueCode.DANGLING_POST, onePost ? Issue.Severity.WARNING : Issue.Severity.ERROR,
                        "Post " + pa.ref + " at " + cells(pa.at) + " connects to no other element.",
                        "Connect it to another element's post, or mark it open (markOpen) if it stays unconnected on purpose.")
                        .elements(pa.elm.getElementId()).posts(pa.ref).at(cell(pa.at.x), cell(pa.at.y)));
            }
        }

        // post_on_wire_body: strictly inside a wire segment, not in that wire's net
        for (CircuitElm w : wires) {
            Point a = w.getPost(0);
            Point b = w.getPost(1);
            if (a == null || b == null || a.equals(b)) {
                continue;
            }
            int minX = Math.min(a.x, b.x), maxX = Math.max(a.x, b.x);
            int minY = Math.min(a.y, b.y), maxY = Math.max(a.y, b.y);
            int wireNode = nets.analysed ? w.getNode(0) : -2;
            for (PostAt pa : all) {
                Point p = pa.at;
                if (pa.elm == w || p.x < minX || p.x > maxX || p.y < minY || p.y > maxY) {
                    continue;
                }
                if (!strictlyInside(a, b, p) || (nets.analysed && pa.node == wireNode)) {
                    continue;
                }
                issues.add(Issue.of(IssueCode.POST_ON_WIRE_BODY,
                        "Post " + pa.ref + " at " + cells(p) + " lies on the body of wire " + w.getElementId()
                                + " but is not connected to it.",
                        "Split the wire at the post (two wires meeting there), or move the post off the wire.")
                        .elements(pa.elm.getElementId(), w.getElementId()).posts(pa.ref).at(cell(p.x), cell(p.y)));
            }
        }

        // overlapping_elements: same type and defining points (either order); overlapping collinear wires
        Map<String, List<CircuitElm>> sameSpot = new LinkedHashMap<>();
        for (CircuitElm elm : elms) {
            if (elm instanceof WireElm) {
                continue;
            }
            int x1 = elm.getX(), y1 = elm.getY(), x2 = elm.getX2(), y2 = elm.getY2();
            boolean swap = x1 > x2 || (x1 == x2 && y1 > y2);
            String k = elm.getJsonTypeName() + "|" + (swap ? x2 + "," + y2 + "," + x1 + "," + y1 : x1 + "," + y1 + "," + x2 + "," + y2);
            List<CircuitElm> l = sameSpot.get(k);
            if (l == null) {
                l = new ArrayList<>();
                sameSpot.put(k, l);
            }
            l.add(elm);
        }
        for (List<CircuitElm> l : sameSpot.values()) {
            if (l.size() < 2) {
                continue;
            }
            String[] ids = idsOf(l);
            issues.add(Issue.of(IssueCode.OVERLAPPING_ELEMENTS, "Elements " + String.join(", ", ids) + " of type "
                    + l.get(0).getJsonTypeName() + " lie on the same points.", "Delete the duplicate or move it.")
                    .elements(ids).at(cell(l.get(0).getX()), cell(l.get(0).getY())));
        }
        for (int i = 0; i < wires.size(); i++) {
            for (int j = i + 1; j < wires.size(); j++) {
                if (wiresOverlap(wires.get(i), wires.get(j))) {
                    String ia = wires.get(i).getElementId(), ib = wires.get(j).getElementId();
                    issues.add(Issue.of(IssueCode.OVERLAPPING_ELEMENTS, "Wires " + ia + " and " + ib + " overlap.",
                            "Delete one of them or shorten it so the wires only meet at their ends.").elements(ia, ib));
                }
            }
        }

        // no_ground: no Ground element, and either the simulator assumes ground at a voltage source
        // or no post is referenced to ground by its own element (a circuit referenced only through
        // rails, logic inputs, gates or chips needs no Ground element)
        if (anyPost && !gotGround && (implicitGround || !gotGroundReference)) {
            issues.add(Issue.of(IssueCode.NO_GROUND, "The circuit has no ground element"
                    + (implicitGround ? "; the simulator assumes ground at the first post of the first voltage source." : "."),
                    "Add a Ground element at the reference node."));
        }

        // isolated_group: nodes the simulator tied to ground through 100 MOhm, one issue per group
        if (nets.analysed) {
            Map<Integer, List<PostAt>> groups = new LinkedHashMap<>();
            Map<Integer, List<PostAt>> wireOnly = new HashMap<>();
            for (PostAt pa : all) {
                int g = sim.getUnconnectedGroup(pa.node);
                if (g < 0) {
                    continue;
                }
                Map<Integer, List<PostAt>> target = pa.elm instanceof WireElm ? wireOnly : groups;
                List<PostAt> l = target.get(g);
                if (l == null) {
                    l = new ArrayList<>();
                    target.put(g, l);
                }
                l.add(pa);
            }
            // a group of wires only lists the wire posts
            for (Map.Entry<Integer, List<PostAt>> e : wireOnly.entrySet()) {
                if (!groups.containsKey(e.getKey())) {
                    groups.put(e.getKey(), e.getValue());
                }
            }
            for (List<PostAt> l : groups.values()) {
                boolean allOpen = true;
                Set<String> refs = new TreeSet<>();
                Set<String> ids = new TreeSet<>();
                for (PostAt pa : l) {
                    allOpen &= doc.hasOpenMark(pa.ref);
                    refs.add(pa.ref);
                    ids.add(pa.elm.getElementId());
                }
                if (allOpen) {
                    continue;
                }
                issues.add(Issue.of(IssueCode.ISOLATED_GROUP, refs.size() + " post(s) have no path to ground ("
                        + first(refs, 6) + "); the simulator ties them to ground through 100 MOhm.",
                        "Connect the group to the rest of the circuit or to ground, or mark its posts open if it is unused.")
                        .elements(ids.toArray(new String[0])).posts(refs.toArray(new String[0])));
            }
        }

        // bad_connection: a lone post the analysis found inside another element's body
        for (Point p : sim.getBadConnections()) {
            List<PostAt> list = byPoint.get(p);
            if (list == null) {
                continue;
            }
            for (PostAt pa : list) {
                issues.add(Issue.of(IssueCode.BAD_CONNECTION, "Post " + pa.ref + " at " + cells(p)
                        + " touches the body of another element without connecting to it.",
                        "Move the post onto the other element's post, or away from its body.")
                        .elements(pa.elm.getElementId()).posts(pa.ref).at(cell(p.x), cell(p.y)));
            }
        }

        // symbol_overlap: wires through symbols, overlapping symbols, posts on a foreign symbol or lead
        SymbolOverlap.check(elms, issues);

        // single_label / reserved_label
        Map<String, List<CircuitElm>> labels = new LinkedHashMap<>();
        for (CircuitElm elm : elms) {
            if (elm instanceof LabeledNodeElm) {
                String text = ((LabeledNodeElm) elm).text;
                List<CircuitElm> l = labels.get(text);
                if (l == null) {
                    l = new ArrayList<>();
                    labels.put(text, l);
                }
                l.add(elm);
            }
        }
        for (Map.Entry<String, List<CircuitElm>> e : labels.entrySet()) {
            if (e.getValue().size() == 1) {
                issues.add(Issue.of(IssueCode.SINGLE_LABEL, "Label '" + e.getKey() + "' is used by only one labelled node.",
                        "Add a second labelled node with the same text to connect the two places, or remove it.")
                        .elements(e.getValue().get(0).getElementId()));
            }
            if (isReservedLabel(e.getKey())) {
                issues.add(Issue.of(IssueCode.RESERVED_LABEL, "Label text '" + e.getKey() + "' is reserved; its net is named 'label:"
                        + e.getKey() + "'.", "Rename the label; use a Ground element for ground.")
                        .elements(idsOf(e.getValue())));
            }
        }

        // source_or_wire_loop: reported by the last analysis, as a stop or a recovery-mode warning
        for (CircuitElm culprit : SolverEvents.culprits(doc, IssueCode.SOURCE_OR_WIRE_LOOP)) {
            Issue issue = Issue.of(IssueCode.SOURCE_OR_WIRE_LOOP, "The last analysis found a voltage source/wire loop with no resistance"
                    + (culprit != null ? " at " + culprit.getElementId() + "." : "."),
                    "Add resistance to the loop or remove the wire that shorts the source.");
            if (culprit != null) {
                issue.elements(culprit.getElementId());
            }
            issues.add(issue);
        }

        // ground_path_no_resistance / wire_loop: also found by the analysis itself (rail or logic
        // input validation, wire-current ordering), so they are static like source_or_wire_loop
        Set<String> seen = new HashSet<>();
        for (CircuitSimulator.SolverEvent e : sim.getSolverEvents()) {
            IssueCode code = SolverEvents.codeOf(e.key, e.stop);
            if (code != IssueCode.GROUND_PATH_NO_RESISTANCE && code != IssueCode.WIRE_LOOP) {
                continue;
            }
            String culprit = e.culprit != null ? e.culprit.getElementId() : null;
            if (!seen.add(code + "|" + culprit)) {
                continue;
            }
            String message = code == IssueCode.WIRE_LOOP
                    ? "The last analysis found a loop made only of wires" + (culprit != null ? " at " + culprit : "")
                            + "; wire currents are approximated."
                    : "The last analysis found a path with no resistance from " + (culprit != null ? culprit : "a rail or logic input")
                            + " to ground.";
            Issue issue = Issue.of(code, SolverEvents.severityOf(code, e.stop), message, SolverEvents.hintFor(code));
            if (culprit != null) {
                issue.elements(culprit);
            }
            issues.add(issue);
        }

        // current_source_no_path: a current source the analysis found without a current path
        // (open, or in series with another current source) is replaced by 100 MOhm and drives
        // no current. A warning: the rest of the circuit simulates correctly (a source on an
        // unselected switch throw is legitimate). An ohmmeter with open probes is a valid reading.
        if (nets.analysed) {
            for (CircuitElm elm : elms) {
                if (elm instanceof CurrentElm && !(elm instanceof OhmMeterElm) && ((CurrentElm) elm).isBroken()) {
                    String id = elm.getElementId();
                    issues.add(Issue.of(IssueCode.CURRENT_SOURCE_NO_PATH, "Current source " + id
                            + " has no current path (an open circuit, or in series with another current source); the"
                            + " simulator replaces it with 100 MOhm, so it drives no current.",
                            "Close its loop through a resistive path; for two current sources in series, replace one by a resistor.")
                            .elements(id));
                }
            }
        }
        return implicitGround;
    }

    /** @return true when {@code p} lies on segment a-b, not at an end */
    private static boolean strictlyInside(Point a, Point b, Point p) {
        long dx = b.x - a.x, dy = b.y - a.y;
        long px = p.x - a.x, py = p.y - a.y;
        if (dx * py - dy * px != 0) {
            return false;
        }
        long dot = dx * px + dy * py;
        return dot > 0 && dot < dx * dx + dy * dy;
    }

    /** @return true when two wires are collinear and share more than one point */
    private static boolean wiresOverlap(CircuitElm w1, CircuitElm w2) {
        Point a = w1.getPost(0), b = w1.getPost(1), c = w2.getPost(0), d = w2.getPost(1);
        if (a == null || b == null || c == null || d == null || a.equals(b) || c.equals(d)) {
            return false;
        }
        if (Math.max(c.x, d.x) < Math.min(a.x, b.x) || Math.min(c.x, d.x) > Math.max(a.x, b.x)
                || Math.max(c.y, d.y) < Math.min(a.y, b.y) || Math.min(c.y, d.y) > Math.max(a.y, b.y)) {
            return false;
        }
        long dx = b.x - a.x, dy = b.y - a.y;
        if (dx * (c.y - a.y) - dy * (c.x - a.x) != 0 || dx * (d.y - a.y) - dy * (d.x - a.x) != 0) {
            return false;
        }
        long len = dx * dx + dy * dy;
        long tc = dx * (c.x - a.x) + dy * (c.y - a.y);
        long td = dx * (d.x - a.x) + dy * (d.y - a.y);
        long lo = Math.max(0, Math.min(tc, td));
        long hi = Math.min(len, Math.max(tc, td));
        return lo < hi;
    }

    private static List<Issue> sortBySeverity(List<Issue> issues) {
        List<Issue> sorted = new ArrayList<>(issues.size());
        for (int rank = 0; rank <= 2; rank++) {
            for (Issue i : issues) {
                if (i.getSeverity().rank() == rank) {
                    sorted.add(i);
                }
            }
        }
        return sorted;
    }

    // ---------------------------------------------------------------- delta

    /**
     * [SP_AGA_01_06] ConnectivityDelta: issues of {@code after} whose key is not in {@code before}
     * ({@code added}) and the reverse ({@code cleared}), errors first, at most {@link #MAX_DELTA}
     * each, with the error and warning totals of {@code after}.
     */
    static JSONObject delta(Report before, Report after) {
        Set<String> beforeKeys = keys(before.issues);
        Set<String> afterKeys = keys(after.issues);
        List<Issue> added = new ArrayList<>();
        for (Issue i : after.issues) {
            if (!beforeKeys.contains(i.key())) {
                added.add(i);
            }
        }
        List<Issue> cleared = new ArrayList<>();
        for (Issue i : before.issues) {
            if (!afterKeys.contains(i.key())) {
                cleared.add(i);
            }
        }
        JSONObject o = new JSONObject();
        o.put("added", capped(added));
        o.put("cleared", capped(cleared));
        o.put("errorCount", new JSONNumber(after.count(Issue.Severity.ERROR)));
        o.put("warningCount", new JSONNumber(after.count(Issue.Severity.WARNING)));
        o.put("truncatedAdded", new JSONNumber(Math.max(0, added.size() - MAX_DELTA)));
        o.put("truncatedCleared", new JSONNumber(Math.max(0, cleared.size() - MAX_DELTA)));
        return o;
    }

    private static Set<String> keys(List<Issue> issues) {
        Set<String> keys = new HashSet<>();
        for (Issue i : issues) {
            keys.add(i.key());
        }
        return keys;
    }

    private static JSONArray capped(List<Issue> issues) {
        JSONArray a = new JSONArray();
        for (int i = 0; i < issues.size() && i < MAX_DELTA; i++) {
            a.set(i, issues.get(i).toJson());
        }
        return a;
    }

    // ---------------------------------------------------------------- contract

    /** [SP_AGA_02_06] {@code getConnectivity(includeNets = true, netFilter?)} → ConnectivityReport. */
    static OperationResult getConnectivity(AgentApi.Call call) {
        final boolean includeNets = call.args.optBool("includeNets", true);
        final JSONArray filter = call.args.optArray("netFilter", 1, MAX_FILTER);
        if (filter != null) {
            for (int i = 0; i < filter.size(); i++) {
                if (filter.get(i).isString() == null) {
                    call.args.invalid("netFilter[" + i + "]", "must be a net name string", "getConnectivity lists the net names.");
                }
            }
        }
        if (call.args.failed()) {
            return call.args.failure();
        }
        final CircuitDocument doc = call.doc;
        return DocumentScope.call(call.sim, doc, () -> {
            Report report = analyse(doc);
            List<Net> shown = null;
            if (filter != null) {
                List<Issue> unknown = new ArrayList<>();
                Set<Net> picked = new HashSet<>();
                for (int i = 0; i < filter.size(); i++) {
                    String name = filter.get(i).isString().stringValue();
                    Net net = report.nets.find(name);
                    if (net == null) {
                        unknown.add(unknownNet(name, "netFilter[" + i + "]", report.nets));
                    } else {
                        picked.add(net);
                    }
                }
                if (!unknown.isEmpty()) {
                    return OperationResult.failure(unknown);
                }
                shown = new ArrayList<>();
                for (Net net : report.nets.list) {
                    if (picked.contains(net)) {
                        shown.add(net);
                    }
                }
            }
            return OperationResult.success(report.toJson(includeNets, shown));
        });
    }

    /** @return the {@code unknown_net} issue for {@code name} */
    static Issue unknownNet(String name, String where, Nets nets) {
        StringBuilder some = new StringBuilder();
        for (int i = 0; i < nets.list.size() && i < 8; i++) {
            some.append(i > 0 ? ", " : "").append(nets.list.get(i).name);
        }
        return Issue.of(IssueCode.UNKNOWN_NET, "Argument '" + where + "' names no net of the document: '" + name + "'.",
                nets.list.isEmpty() ? "The document has no analysed nets; getConnectivity explains why."
                        : "Net names are gnd, label texts, label:<text> or $<k>; for example " + some
                                + ". getConnectivity lists them all.");
    }

    // ---------------------------------------------------------------- helpers

    private static double cell(int px) {
        return CellGeometry.toCells(px);
    }

    private static String cells(Point p) {
        return "(" + fmt(cell(p.x)) + ", " + fmt(cell(p.y)) + ")";
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }

    private static String[] idsOf(List<CircuitElm> elms) {
        String[] ids = new String[elms.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = elms.get(i).getElementId();
        }
        return ids;
    }

    private static String first(Set<String> values, int n) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String v : values) {
            if (i == n) {
                sb.append(", ...");
                break;
            }
            sb.append(i > 0 ? ", " : "").append(v);
            i++;
        }
        return sb.toString();
    }

    private static JSONArray strings(List<String> values) {
        JSONArray a = new JSONArray();
        for (int i = 0; i < values.size(); i++) {
            a.set(i, new JSONString(values.get(i)));
        }
        return a;
    }
}
