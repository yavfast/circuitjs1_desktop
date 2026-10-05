# Netlist Graph  {#C_NET}

> **Code:** C_NET
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard
>
> **Depends on:** [C_ELB](./element-base.concept.md), [C_GEO](./geometry.concept.md)
> **Used by:** —
> **Spike:** —
> **Specification:** [SP_NET](./netlist-graph.sp.md)
> **Plan:** [netlist-graph.plan.md](./netlist-graph.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/layer3__circuit-state.md`
> §"Per-file key entities" (CircuitNode / CircuitNodeLink / NodeMapEntry
> / WireInfo / RowInfo / FindPathInfo).
>
> The solver-facing graph primitives: node table, node–element link
> records, wire-closure merge entries, post-solve wire-current
> metadata, MNA matrix row metadata, and the DFS validator that
> detects inductor/voltage-loop/short/cap-V pathologies.

## 1. Philosophy  {#C_NET_01}

### 1.1. Core Principle  {#C_NET_01_01}

All six types are plain data carriers consumed by
`CircuitSimulator.preStampCircuit` / `stampCircuit`. They share a
single lifecycle: allocated during analysis, consulted during stamping
and Newton iteration, discarded/cleared on the next analyze pass. No
encapsulation on purpose — the simulator is the sole author/reader and
inlines field access for speed.

### 1.2. Design Constraints  {#C_NET_01_02}

- `nodeList.get(0)` is always ground.
- Wires do **not** receive MNA rows; their currents are reconstructed
  post-solve via `WireInfo`.
- `NodeMapEntry` is a mutable `int`-box used as a reference token so
  multiple `Point`s can share one node-number slot during wire closure.
- `FindPathInfo.validateElement` is **validate-and-repair**: it mutates
  elements (reset inductors, mark current sources broken, add series
  resistance to capacitors).

## 2. Domain Model  {#C_NET_02}

### 2.1. Key Entities  {#C_NET_02_01}

- **CircuitNode** — one entry per global MNA node. Fields: `links:
  ArrayList<CircuitNodeLink>` (edges to every element post touching
  this node) and `internal: boolean` (true for element-internal extra
  nodes such as op-amp midpoints, inductor companion-model nodes).
- **CircuitNodeLink** — `num: int` (which post of the element, 0..
  postCount+internalNodeCount−1) + `elm: CircuitElm`. One per
  element-post.
- **NodeMapEntry** (package-private) — mutable `int node` wrapper
  (−1 = unassigned). Key-shared across `HashMap<Point, NodeMapEntry>`
  so wire-equivalent posts point at the same record.
- **WireInfo** (package-private) — `wire: CircuitElm`,
  `neighbors: List<CircuitElm>`, `post: int` (0 or 1). Built once per
  analysis; supports post-solve wire-current reconstruction.
- **RowInfo** (`client/solver/`, public since PL_SLV) — matrix row metadata:
  `type: int` (`ROW_NORMAL=0` / `ROW_CONST=1`), `mapCol`, `mapRow`,
  `value`, `rsChanges`, `lsChanges`, `dropRow`. One per unknown in
  `LinearSystem.rowInfo()` ([C_SLV](./linear-solver.concept.md)).
- **FindPathInfo** — DFS workspace + static validator.
  Instance: `visited: boolean[]`, `dest: int`, `firstElm: CircuitElm`,
  `type: int` (INDUCT=1, VOLTAGE=2, SHORT=3, CAP_V=4).

### 2.2. Data Flows  {#C_NET_02_02}

```
CircuitSimulator.preStampCircuit(subcircuit):
  1. calculateWireClosure     → creates NodeMapEntry per wire-equiv group
  2. setGroundNode            → node 0 bound (new CircuitNode or reused)
  3. makeNodeList             → for each elm.post:
                                 new CircuitNode if entry unassigned;
                                 new CircuitNodeLink attached; elm.setNode(j,n)
  4. calcWireInfo             → for each removable wire:
                                 WireInfo{post,neighbors} resolved via
                                 readiness ordering (≤ 2*size passes)
  5. nodeMap.clear            (no longer needed)
  6. nonlinear detect + VS slots
  7. findUnconnectedNodes     → BFS from node 0
  8. validateCircuit          → FindPathInfo.validateElement per elm

CircuitSimulator.stampCircuit():
  matrixSize = nodeList.size() - 1 + voltageSourceCount
  linearSystem.beginStamp(matrixSize, …)  (RowInfo per unknown, all ROW_NORMAL)
  connectUnconnectedNodes     (1e8 Ω to GND)
  ce.stamp() for all
  linearSystem.reduce()       → set ROW_CONST + dropRow; populate mapRow/mapCol

Per frame:
  Newton doStep uses rsChanges/lsChanges on RowInfo to skip invariant rows
  After solve: calcWireCurrents walks WireInfo.neighbors
```

## 3. Mechanisms  {#C_NET_03}

### 3.1. Core Algorithm  {#C_NET_03_01}

**Wire closure** (`CircuitSimulator.calculateWireClosure`, L190). For
each `isRemovableWire()` element, look up both posts in `nodeMap`. Cases:
- Neither present: allocate one shared `NodeMapEntry(-1)` and install
  under both points.
- One present: install that entry under the other point (merge).
- Both present to **different** entries: redirect one entry's `node` to
  the other — all `Point`s that previously pointed at the redirected
  entry now transitively share the surviving entry. This is the O(n)
  merge walk; Union-Find would be cheaper but the current code uses
  simple overwrite.

**Node allocation** (`makeNodeList`, L417). For each element post: if
`NodeMapEntry.node == -1`, allocate `n = nodeList.size()`, append a new
`CircuitNode`, set `entry.node = n`. Append a new `CircuitNodeLink{num=j,
elm=ce}` to `CircuitNode.links`. Call `elm.setNode(j, n)`. Internal
nodes append after externals with `internal=true`.

**Wire-current reconstruction** (`calcWireInfo`, L264). Topological
readiness: a wire is "ready" when all its neighbor elements on one side
are non-wire or already-ready wires. Iterate up to `2 * wireInfoList.size()`
passes until every wire is resolved; `wire.hasWireInfo=true` tracks
completion. Circular wire-only loops fail the scan — `warn` (recovery)
or `stop`.

**Matrix simplification drives `RowInfo`**. `stampCircuit` L755 scans
rows: a row with no `rsChanges`/`lsChanges`/`dropRow` and exactly one
non-constant non-zero term becomes `ROW_CONST` with precomputed `value`;
the row is marked `dropRow`; subsequent `stampMatrix` on that column
folds into B via `value`. `mapRow`/`mapCol` back-references let stamping
code still pretend the matrix has its original size.

**DFS validation** (`FindPathInfo.findPath` + `checkElm`, mutually
recursive). Start from one post of the element under test; recurse
over `CircuitNode.links` following edges that satisfy the type filter:
- **INDUCT** — skip `CurrentElm`; match parallel-inductor current within
  1e-10 for grouping.
- **VOLTAGE** — only `isWireEquivalent()` / `VoltageElm` / `GroundElm`.
- **SHORT** — only `isWireEquivalent()`.
- **CAP_V** — `isWireEquivalent()` + `isIdealCapacitor()` + `VoltageElm`.

Implicit ground edges honored via `hasGroundConnection(j)`; when visiting
node 0, iterate `simulator.nodesWithGroundConnection`.

Static entry `validateElement(sim, ce)`:
- `InductorElm` with no current path → `ce.reset()` (clears current).
- `CurrentElm` with no current path → `setBroken(true)`.
- `VCCSElm` with no output path → `broken=true`.
- 2-post `VoltageElm` / `RailElm` / `LogicInputElm` in zero-resistance
  loop → `stop` (strict) or `warn + singularStabilizersActive=true`
  (recovery).
- `CapacitorElm`: shorted → `shorted()`; cap-voltage loop →
  `setSeriesResistance(0.1)` + return false (triggers re-stamp).

### 3.2. Edge Cases  {#C_NET_03_02}

- **Unassigned RowInfo `mapCol`/`mapRow`** default to 0 (no sentinel).
  Simplification code must populate before any stamping uses them.
- **WireInfo.post** is only ever 0 or 1 — silently assumes 2-post wires.
- **CircuitNode fields are public**; external code holds references by
  index into `nodeList` but must not mutate `links` (simulator-only).
- **Circular wire dependency** — `calcWireInfo` fails after
  `2*wireInfoList.size()` passes; in recovery mode, stabilizers
  engage instead of stopping.
- **FindPathInfo mutates inputs**: `validateElement` both validates
  and repairs; callers must understand this dual role.

## 4. Integration Points  {#C_NET_04}

### 4.1. Dependencies  {#C_NET_04_01}

- [C_ELB](./element-base.concept.md) — `CircuitElm.getPost`,
  `isRemovableWire`, `isWireEquivalent`, `isIdealCapacitor`,
  `hasGroundConnection`, `nonLinear`, `getVoltageSourceCount`,
  `getInternalNodeCount`.
- [C_GEO](./geometry.concept.md) — `Point` (keys for `nodeMap`).

### 4.2. API Surface  {#C_NET_04_02}

- `CircuitNode.links`, `CircuitNode.internal` (public fields).
- `CircuitNodeLink.num`, `.elm`.
- `NodeMapEntry(node)`, `.node` (package-private).
- `WireInfo(wire)`, `.wire`, `.neighbors`, `.post`, `hasWireInfo` flag
  on the wire element.
- `RowInfo` constants `ROW_NORMAL`, `ROW_CONST`; fields `type`, `mapCol`,
  `mapRow`, `value`, `rsChanges`, `lsChanges`, `dropRow`.
- `FindPathInfo.validateElement(CircuitSimulator, CircuitElm) → boolean`
  (sole public entry).

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
