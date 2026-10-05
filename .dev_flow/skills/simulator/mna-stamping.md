---
skill: mna-stamping
domain: simulator
topics: [mna, stamping, circuit-simulator, row-info, simplify-matrix, sanitize]
source: onboard
updated: 2026-10-05
---

# MNA Stamping

## Context

The simulator in `CircuitSimulator.java` (1844 LOC) implements
Modified Nodal Analysis. Every element contributes to the matrix `A`
and right-side `B` via a small set of `stamp*` primitives exposed by
`CircuitSimulator`. `INTERNALS.md` (lines 1-112) gives the MNA primer;
this skill catalogs the **project-specific API surface** — what exists,
where it is, and the quirks.

## Key concepts

**Two-phase contract on every `CircuitElm`:**
- `stamp()` — called once per topology (after `analyzeCircuit`).
  Stamps the linear/constant part of the element.
- `doStep()` — called once per Newton sub-iteration. Stamps the
  linearized nonlinear part (diodes, transistors) or updates B for
  companion-model dynamic elements (inductors, capacitors).

**Storage (since PL_SLV).** The primitives write into the document's
`solver/LinearSystem` (a slot store during assembly; after the row
reduction, the dense table or the sparse pattern — a stamp outside the
sparse pattern grows it before the next factorization). `RowInfo` lives in
`client/solver/`. See `solver-performance.md`.

**Stamping primitives (`CircuitSimulator.java`).** All pass through
`sanitizeStampValue` (L1043) which clamps to ±1e12, coerces NaN to 0,
and sets `converged = false` on out-of-range — a silent robustness
net that can mask element bugs.

| Primitive | Line | Effect |
|---|---:|---|
| `stampMatrix(i, j, x)` | 1062 | `A[i][j] += x`; honors `circuitRowInfo[].mapRow/mapCol` after simplify; folds into B when column is ROW_CONST |
| `stampRightSide(i, x)` | 1083 | `B[i] += x` |
| `stampRightSide(i)` | 1096 | Mark row as `rsChanges = true` (dynamic B updated in doStep) |
| `stampNonLinear(i)` | 1103 | Mark row as `lsChanges = true` (A updated every Newton iteration) |
| `stampResistor(n1, n2, r)` | 1146 | Classic 4-corner ±1/r stamp; guards r=0/NaN/Inf |
| `stampConductance(n1, n2, g)` | 1161 | Same, conductance input (used by linearized nonlinear) |
| `stampVoltageSource(n1, n2, vs, v)` | 1118 | Extra VS row/col: KVL `[-1,+1]`, B[vn]=v |
| `stampVoltageSource(n1, n2, vs)` | 1130 | Same, marks B[vn] dynamic for `updateVoltageSource` |
| `updateVoltageSource(n1, n2, vs, v)` | — | Per-step B update (waveforms, chip outputs) |
| `stampVCCS` / `stampVCVS` / `stampCCCS` | 1171 / 1111 / 1185 | Controlled sources |
| `stampCurrentSource(n1, n2, i)` | 1178 | B-only |

**Row simplification.** `LinearSystem.reduce` (formerly `simplifyMatrix`)
collapses rows that resolve to a single unknown, storing the solution as
`RowInfo.ROW_CONST` and back-referencing via `mapRow`/`mapCol`. Crucial perf
for digital / wire-heavy circuits. The snapshot of the reduced system is
restored before each Newton iteration (rhs always, matrix when nonlinear).

**Node assignment runs first** (`preStampCircuit`): wire closure
(L190) → ground pick (L356) → `makeNodeList` (L417) → VS slot allocation
(L580) → unconnected-node repair (1e8 Ω tie to GND, L555). Wires are
**not given MNA rows** — their currents are reconstructed via
`calcWireCurrents` (L1231) after the solve.

**Voltage-source index.** Every element returning `getVoltageSourceCount()
> 0` gets one or more `vs` indices assigned via `setVoltageSource(n, vs)`
before `stamp()`. Extra matrix row is at index `nodeList.size() + vs`.

## Usage in this project

- Linear elements (resistor, capacitor, inductor) stamp entirely in
  `stamp()` — capacitors/inductors use **companion models**: a resistor
  + current source derived from Δt (see `Inductor.stamp` at
  `element/Inductor.java:65`, trapezoidal vs backward-Euler via
  `FLAG_BACK_EULER = 2`).
- Nonlinear elements (diode, transistor) mark rows with `stampNonLinear`
  in `stamp()` and re-stamp the linearization each `doStep()` — see
  `Diode.doStep` at `Diode.java:158` (Shockley + Zener + Newton limiting).
- `updateVoltageSource` is used by `ChipElm.doStep`
  (`ChipElm.java:335`) to push output pin values per step without
  full re-stamp.
- `validateCircuit` (L956) runs after `preStampCircuit`; failures trigger
  `stop()` or warn-and-recover under `nonConvergenceRecoveryEnabled`.

## Pitfalls

1. **Do not throw from `stamp`/`doStep`** (RULE_ERR_001, RULE_ERR_002).
   Signal failures via `simulator.stop(msg, ce)` or set `converged =
   false`; the Newton loop relies on it.
2. **`sanitizeStampValue` silences your bugs.** A stamp of `1e300` or
   NaN becomes `1e12` or `0` with `converged = false`, giving a
   "successful" solve that is actually clamped noise. Log
   `simulator-core` issue §10.3.
3. **Do not cache `nodeVoltages[i]` references.** The array is re-built
   whenever `circuitNeedsMap` flips or after `resetSolverState` (commit
   `a488ebb`). Always re-read via `simulator.getNodeVoltages(i)`.
4. **Wires are invisible to the matrix.** If a new element relies on
   KCL through a wire, make sure posts are shared — do not stamp across
   a wire post.
5. **`stampResistor(0, 0, r)` is a no-op** (both nodes == 0 / ground).
   Intentional, used for the 1e8 Ω unconnected-node repair.
6. **Row-simplification assumes row constancy.** If an element forgets
   to call `stampRightSide(i)` / `stampNonLinear(i)` when it actually
   updates B or A in `doStep`, the simplifier drops those rows and the
   element stops working after the first frame.
7. **The stamp was cubic** (PL_AGA backlog "importCircuit scales", fixed
   2026-10-05; PL_SLV made the stamp sparse above 64 reduced unknowns). Before
   PL_SLV `stampCircuit` allocated two dense `m × m` matrices and LU-factored
   a linear circuit at once — O(m³) in the node count (2000 unconnected
   resistors: ~200 s). `preStampCircuit` (wire closure, node
   allocation, `findUnconnectedNodes`, validation) is near-linear and is all
   that connectivity, PostRecord nets and an agent mutation need: use
   `CircuitDocument.ensureNodesAnalysed()` there and `ensureAnalysed()` only
   where the stamp matters (readings — `CurrentElm.stamp` sets `current` —,
   diagnostics events of the stamp, runs). `analyseNodes()` marks the
   allocation of the current analysis so the next `preStampAndStampCircuit`
   stamps without allocating again (same order of effects: validation,
   `timeStep = maxTimeStep`, analysis hook, stamp); any `preStampCircuit`
   call, `resetSolverState` and a new analysis clear the mark. A `stamp()`
   that writes a field the drawing or a reading shows must set the same
   value in `applyStampedValues()` (called by `analyseNodes`): today
   `CurrentElm.current` and `PotElm.resistance1/2` (SP_AGA §03_13 "Pure
   layout"); without it `checkLayout`/`render` after an agent edit showed
   no current-source value (9 examples differed). Not covered: a relay
   coil's stamp sets its contacts' positions through the element list only
   the stamp receives — doing it earlier would change the topology the
   stamp sees for contacts listed before the coil. Contracts that show the
   stamp's state without stepping call `CircuitDocument.stampIfDeferred()`
   (`simControl run`, `render`); a stamp exception in a frame or scripted
   step sets `noteAnalysisFailed()` (it used to happen only inside
   `ensureAnalysed`). `buildCompositeReadOnly` keeps the allocation mark when
   the node counts match (else the onanalyze hook fired twice).
8. **Keep analysis passes linear.** Per-element scans inside per-node,
   per-post or per-group loops were the import's other hotspots:
   `findUnconnectedNodes` re-ran a full element pass per unconnected group
   (now one BFS over a CSR connection graph, same seeds, groups and
   `unconnectedNodes` order; `nodesWithGroundConnection` lists each element
   once — its users only test emptiness and membership), the wire closure
   re-pointed merged entries by scanning the whole node map (now the smaller
   key group), `calcWireInfo` scanned every link of a wire's node for every
   wire — 2000 Ground elements on the ground node took 1.7 s (since PL_SLV P6
   a per-node index of the links by post point, link order kept), `makePostDrawList` tested each lone post against every
   bounding box and `CircuitRenderer.drawElements` scanned every element per
   drawn post each frame. Index with `util/BoxGrid` (candidates in index
   order, then the original exact test) or a map, so results and their
   order stay those of the scan. Opt-in `import_cost` live scenario checks
   the growth (time(2N)/time(N) ≤ 3); opt-in `frame_cost` checks the
   visible frame (idle ≤ 2.3 per doubling: ≈ 67 µs per element after the
   post-owner map, 1420 ms at 1000 elements before it).

9. **Matrix size and solver speed** — measured costs, the unpaired `mapRow`/`mapCol` after simplify, and the Newton-varying stamp pattern (`AnalogSwitchElm`, VCCS/CCCS) are in `solver-performance.md`; read it before changing `client/solver/` (`LinearSystem.reduce`/`factor`, `SparseLu`), `lu_factor` or `stampCircuit`.

## References

- `.dev_flow/onboard/analysis/layer3__simulator-core.md` §4, §8.1
- `INTERNALS.md` lines 1-112 (MNA primer, companion models)
- `src/main/java/com/lushprojects/circuitjs1/client/CircuitSimulator.java`
  — stamp primitives at lines 1043-1185
- Rules: RULE_ERR_001, RULE_ERR_002
- Sibling skill: `newton-raphson-loop.md`, `time-step-control.md`
