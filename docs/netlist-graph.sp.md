# Netlist Graph — Specification  {#SP_NET}

> **Code:** SP_NET
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_NET](./netlist-graph.concept.md)
> **Depends on specs:** [SP_ELB](./element-base.sp.md), [SP_GEO](./geometry.sp.md)
> **Used by specs:** —
> **Plan:** [netlist-graph.plan.md](./netlist-graph.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/layer3__circuit-state.md`.

## 01. Data Structures  {#SP_NET_01}

> Implements: [C_NET_02](./netlist-graph.concept.md#C_NET_02)

### 01_01. CircuitNode  {#SP_NET_01_01}

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| links | ArrayList<CircuitNodeLink> | yes | new | edges to element posts |
| internal | boolean | yes | false | true for element-internal nodes |

Invariants:
- `nodeList.get(0).internal == false` and represents ground.
- `links` is appended by `makeNodeList` only.

### 01_02. CircuitNodeLink  {#SP_NET_01_02}

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| num | int | yes | post index 0..postCount+internalNodeCount-1 |
| elm | CircuitElm | yes | element owning the post |

### 01_03. NodeMapEntry  {#SP_NET_01_03}

Package-private.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| node | int | -1 | global node index; -1 = unassigned |

Constructors: `NodeMapEntry()` → -1; `NodeMapEntry(int n)` → n.

### 01_04. WireInfo  {#SP_NET_01_04}

Package-private.

| Field | Type | Description |
|-------|------|-------------|
| wire | CircuitElm | the wire element |
| neighbors | List<CircuitElm> | non-wire neighbors on chosen side |
| post | int | 0 or 1 — which end's neighbors we read |

Plus `CircuitElm.hasWireInfo: boolean` flag (set when resolved).

### 01_05. RowInfo  {#SP_NET_01_05}

Package-private. Constants: `ROW_NORMAL=0`, `ROW_CONST=1`.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| type | int | ROW_NORMAL | row category |
| mapCol | int | 0 | reduced column index (no sentinel) |
| mapRow | int | 0 | reduced row index |
| value | double | 0 | pre-solved value when ROW_CONST |
| rsChanges | boolean | false | right-side re-stamped per Newton iter |
| lsChanges | boolean | false | left-side re-stamped per Newton iter |
| dropRow | boolean | false | row is eliminated by simplification |

Invariant: simplification must set `mapRow`/`mapCol` before any
stamping uses them (no sentinel to detect misuse).

### 01_06. FindPathInfo  {#SP_NET_01_06}

Type constants: `INDUCT=1`, `VOLTAGE=2`, `SHORT=3`, `CAP_V=4`.

| Field | Type | Description |
|-------|------|-------------|
| simulator | CircuitSimulator | back-ref for node lookup |
| visited | boolean[] | size = nodeList.size() |
| dest | int | target node |
| firstElm | CircuitElm | element under validation |
| type | int | filter mode |

## 02. Contracts  {#SP_NET_02}

### 02_01. Wire closure  {#SP_NET_02_01}

Purpose: collapse wire-equivalent posts into shared `NodeMapEntry`.

Processing:
    FOR each removable-wire elm:
        a = nodeMap.get(elm.getPost(0)); b = nodeMap.get(elm.getPost(1))
        IF a==null AND b==null:
            entry = new NodeMapEntry(); nodeMap.put(both, entry)
        ELSIF a==null: nodeMap.put(post0, b)
        ELSIF b==null: nodeMap.put(post1, a)
        ELSIF a != b:
            // merge: redirect every key pointing at b to point at a
            FOR each (k,v) in nodeMap: IF v==b: nodeMap.put(k, a)

### 02_02. Node allocation  {#SP_NET_02_02}

Purpose: assign each post to a `CircuitNode`.

Processing:
    FOR each elm:
        FOR each post j in elm:
            p = elm.getPost(j)
            entry = nodeMap.getOrCreate(p)
            IF entry.node == -1:
                entry.node = nodeList.size()
                cn = new CircuitNode(); cn.internal = false
                nodeList.add(cn)
            cn = nodeList.get(entry.node)
            cn.links.add(new CircuitNodeLink(j, elm))
            elm.setNode(j, entry.node)
        FOR each internal node i:
            (same, but cn.internal = true)

### 02_03. calcWireInfo readiness ordering  {#SP_NET_02_03}

Processing:
    maxPasses = 2 * wireInfoList.size()
    FOR pass in 1..maxPasses:
        progress = false
        FOR each wi in wireInfoList not yet resolved:
            partition wi.wire's node links by post 0 vs post 1
            pick side whose neighbors are all non-wire OR already hasWireInfo
            IF chosen: wi.post = side; wi.neighbors = list; wi.wire.hasWireInfo = true; progress=true
        IF NOT progress: return false       // circular
    return true

### 02_04. FindPathInfo.validateElement  {#SP_NET_02_04}

Purpose: validate + repair one element.

Processing:
    SWITCH ce:
      InductorElm:
        fp = new FindPathInfo(sim, INDUCT, ce, ce.nodes[1])
        IF NOT fp.findPath(ce.nodes[0]): ce.reset(); return true
      CurrentElm:
        fp = INDUCT without elm-match; IF no path: ce.setBroken(true); return true
      VCCSElm:
        fp over output-current path; IF none: ce.broken = true
      2-post VoltageElm:
        fp = VOLTAGE; IF loop found: sim.stop("Voltage source/wire loop", ce) OR warn + singularStabilizersActive=true
      RailElm / LogicInputElm:
        fp = SHORT to ground; same stop/warn behavior
      CapacitorElm:
        fp = SHORT: IF shorted: ce.shorted(); return true
        fp = CAP_V: IF cap-V loop: ce.setSeriesResistance(0.1); return false
      default: return true

## 03. Validation Rules  {#SP_NET_03}

- `makeNodeList` is the only writer of `CircuitNode.links`.
- `nodeMap` is cleared after `calcWireInfo`.
- `circuitRowInfo.length == circuitMatrixFullSize`.
- `validateElement` may call `sim.stop` (strict) or `sim.warn` +
  enable stabilizers (recovery) — never throws.

## 04. State Transitions  {#SP_NET_04}

Per analyze pass:

    [empty] --calculateWireClosure--> [nodeMap merged]
    [nodeMap merged] --makeNodeList--> [nodeList built]
    [nodeList built] --calcWireInfo--> [wires resolved]
    [wires resolved] --validateCircuit--> [ready-to-stamp]
    [ready-to-stamp] --stampCircuit + simplifyMatrix--> [RowInfo finalized]

## 05. Verification Criteria  {#SP_NET_05}

### 05_01. Functional Expectations  {#SP_NET_05_01}

| Contract | Scenario | Expected |
|----------|----------|----------|
| wire closure | 3 wires meeting | single shared NodeMapEntry |
| makeNodeList | simple RC | 2 nodes (+ ground), links populated |
| calcWireInfo | linear wire chain | neighbors resolved in ≤ n passes |
| validateElement (inductor no path) | floating inductor | ce.reset() called; returns true |
| validateElement (cap loop) | cap in voltage loop | seriesResistance=0.1; returns false (re-stamp) |

### 05_02. Invariant Checks  {#SP_NET_05_02}

| Invariant | Verification |
|-----------|-------------|
| node 0 ground | nodeList.get(0) after setGroundNode |
| circular wires detected | calcWireInfo returns false after 2n passes |
| RowInfo ROW_CONST implies dropRow | for each i: type==ROW_CONST ⇒ dropRow |
| WireInfo.post ∈ {0,1} | assert per entry |

### 05_03. Integration Scenarios  {#SP_NET_05_03}

| Scenario | Steps | Expected |
|----------|-------|----------|
| Complex wire mesh | solver runs | matrix size matches unique non-wire nodes + 1 (ground) |
| Floating subcircuit | analyze | unconnected nodes list non-empty, repaired at stamp |
| Cap-voltage loop | load pathological ckt | seriesResistance inserted, sim runs |

### 05_04. Edge Cases  {#SP_NET_05_04}

| Case | Expected |
|------|----------|
| Wire with 3+ posts | WireInfo.post assumption breaks (current limitation) |
| RowInfo unread before stamping | mapCol/mapRow==0, may misroute |
| Empty elmList | analyze completes, matrix 0×0 |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
