# Element Base — Abstract Circuit-Element Contract  {#C_ELB}

> **Code:** C_ELB
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard-doc-gen
>
> **Depends on:** [C_UTL](./util-locale-log.concept.md), [C_GEO](./geometry.concept.md), [C_RND](./rendering-primitives.concept.md), C_EIC (edit-info-contract, pending)
> **Used by:** — (will be filled by higher layers)
> **Spike:** —
> **Specification:** [SP_ELB](./element-base.sp.md)
> **Plan:** [element-base.plan.md](./element-base.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/domain-core__element-base.md` (11 files in `client/element/`).
>
> The abstract contract that every circuit component (~135 concrete `*Elm` classes) implements. It unifies four responsibilities — simulation, topology, rendering/editing, persistence — behind a single base class so the simulator core, the editor, and the I/O framework can iterate over a heterogeneous element catalog through one polymorphic surface.

## 1. Philosophy  {#C_ELB_01}

### 1.1. Core Principle  {#C_ELB_01_01}

An *element* is simultaneously:

1. **A simulation participant** — it stamps entries into the MNA matrix (`stamp()`), runs per-timestep updates (`doStep()`, `startIteration()`), and consumes post-solve node voltages (`setNodeVoltage()`, `setCurrent()`).
2. **A UI participant** — it renders itself (`draw()`), accepts drag/flip/move gestures, exposes dialog rows (`getEditInfo()`), and fills info panes (`getInfo()`).
3. **A persistence participant** — it round-trips through the legacy text format (`dump()` / `getDumpType()`) and the JSON format (`getJsonTypeName`, `getJsonProperties`, `getJsonState`).

All three roles live on one class (`CircuitElm`) because the editor, simulator, and exporter all hold heterogeneous collections of elements and expect one uniform API.

### 1.2. Design Constraints  {#C_ELB_01_02}

- **Single state vector.** Endpoints, leads and bounding-box are stored in `ElmGeometry` (owned via `geom()`), not as loose fields — all geometry mutations funnel through the same object.
- **Default-override pattern.** Every concern has a default no-op / sensible default; subclasses override only what differs. ~50 override points exist.
- **Stamp-once / step-many.** `stamp()` runs once per topology analysis; `doStep()` may run many times per timestep (Newton iterations).
- **Persistence must be declared.** `getDumpType()` throws `IllegalStateException` if not overridden (nominally-abstract via GWT workaround).

## 2. Domain Model  {#C_ELB_02}

### 2.1. Key Entities  {#C_ELB_02_01}

Class hierarchy:

```
BaseCircuitElm              (pure static-utility helper; formatting, geometry math, thick-line drawing)
  └── CircuitElm [abstract] implements dialog.Editable        (1955 LOC; root)
        ├── GraphicElm        (0 posts; TextElm, BoxElm, LineElm, AntennaElm)
        ├── SwitchElm         (SPST base; ~8 switch/relay variants)
        ├── ChipElm [abstract]              (multi-pin IC base; ~50 concrete chips)
        │     ├── CustomCompositeChipElm     (render-only shell for subcircuits)
        │     └── CustomLogicElm             (user-programmable logic; dump type 208)
        └── CompositeElm [abstract]          (element-of-elements; dump type 410)
              └── CustomCompositeElm         (user-defined subcircuit instance)

Helpers (not in hierarchy):
  ElmGeometry   — owned by every CircuitElm via geom()
  Inductor      — Norton-companion helper used by InductorElm, TransformerElm,
                  CustomTransformerElm, DCMotorElm, ThreePhaseMotorElm, RelayCoilElm
```

Per-element state on `CircuitElm`: `geom`, `elementId`, `circuitDocument`, `flags`, `voltSource`, `nodeStates[]`, `current`, `curcount`, `selected`, `noDiagonal`, `hasWireInfo`, `ps1/ps2`. Nested: `NodeState {node, voltage}` and `Pin` (named-pin facade over `getPost/getNode/getPostVoltage/getCurrentIntoNode`).

### 2.2. Data Flows  {#C_ELB_02_02}

The simulator holds the heterogeneous element collection. For each analysis cycle it walks the list, assigning nodes and voltage-source indices, then stamps every element. Each timestep iterates `startIteration → doStep → (matrix solve) → setNodeVoltage → setCurrent → stepFinished`. Reset walks the list and calls `reset()` on each.

The editor and renderer run on the same collection, using `getPost`, `getMouseDistance`, `getBoundingBox`, `draw`, `drawPosts`, `drawHandles`, and `getEditInfo`.

## 3. Mechanisms  {#C_ELB_03}

### 3.1. Core Algorithm  {#C_ELB_03_01}

**Full lifecycle of an element:**

1. **Construction** — subclass constructor calls super(...), `getDefaultFlags()`, `allocNodes()`, and (for placed elements) `setPoints()`.
2. **Analysis pass** (once per topology change):
   - `allocNodes()` — size `nodeStates[]` to `postCount + internalNodeCount`.
   - `setNode(p, n)` — map each local post p to a global node n.
   - `setVoltageSource(n, v)` — for each voltage source the element owns.
   - `stamp()` — contribute to MNA matrix (stamp resistors, voltage sources, etc.).
   - `nonLinear()` — declared true if the element requires Newton iteration.
3. **Per-timestep loop** (continuous):
   - `startIteration()` — prepare any step-begin state (e.g. inductor companion current).
   - `doStep()` — during Newton iteration, update time-varying sources or re-linearize nonlinear stamps.
   - Matrix solve happens outside the element (in `CircuitSimulator`).
   - `setNodeVoltage(n, c)` — solver pushes solved node voltages back; triggers `calculateCurrent()` by default.
   - `setCurrent(vn, c)` — solver pushes voltage-source currents back, keyed by the vsn previously assigned.
   - `stepFinished()` — post-convergence hook (e.g. NoiseWaveform latches new random sample here).
4. **Reset** — `reset()` clears node voltages + `curcount`; subclasses extend to clear custom state (ChipElm clears pin values + `lastClock`, CompositeElm forwards to children, Inductor calls `resetTo(0)`).
5. **Delete** — `delete()` clears mouse refs and deletes any bound sliders.

**Override-points summary** (50+ protected hooks, grouped by concern):

- **Simulation** — `stamp`, `doStep`, `startIteration`, `stepFinished`, `nonLinear`, `reset`, `setNode`, `setVoltageSource`, `setCurrent`, `setNodeVoltage`, `calculateCurrent`, `getCurrent`, `getCurrentIntoNode`, `getPower`, `getVoltageDiff`, `getPostCount`, `getInternalNodeCount`, `getVoltageSourceCount`.
- **Topology** — `getPost`, `getConnectedPost`, `getConnectionNode(Count)`, `getConnection`, `hasGroundConnection`, `isWireEquivalent`, `isRemovableWire`, `isIdealCapacitor`, `canViewInScope`, `canFlipX/Y/XY`.
- **Drawing** — `draw`, `drawPosts`, `drawHandles`, `isCenteredText`, `getVoltageColor`, `setPowerColor`, `getScopeValue/Units`, `canShowValueInScope`.
- **Editing** — `getEditInfo`, `setEditValue`, `getInfo`, `getBasicInfo`, `getScopeText`, `getShortcut`, `needsShortcut`, `doAdjust/setupAdjust`, `updateModels`, `dumpModel`.
- **Persistence (text)** — `getDumpType` (required), `getDumpClass`, `dump`.
- **Persistence (JSON)** — `getJsonTypeName`, `getJsonProperties`, `getJsonState`, `getJsonPinNames` (deprecated), `getJsonPinPosition` (deprecated), `getJsonStartPoint`, `getJsonEndPoint`, `getJsonBounds`, `getJsonFlags`, `applyJsonProperties`, `applyJsonState`, `applyJsonPinPositions`, `applyJsonFlags`, `finalizeJsonImport`.
- **Geometry/interaction** — `setPoints`, `adjustDerivedGeometry`, `drag`, `move`, `movePoint`, `flipX/Y/XY`, `flipPosts`, `isFixedSizeOnCreate`, `dragFixedSize`, `getNumHandles`, `getHandlePoint`, `getMouseDistance`, `draggingDone`, `allowMove`, `creationFailed`.

### 3.2. Edge Cases  {#C_ELB_03_02}

- `getDumpType()` intentionally non-abstract (GWT compiler workaround for `OTAElm`); throws at runtime if not overridden.
- Default `setVoltageSource` writes into a single `voltSource` field — valid only for 0- or 1-source elements. Multi-source elements (ChipElm, CompositeElm, OpAmpElm, TransformerElm) must override.
- Default `getCurrentIntoNode(n)` handles two-terminal elements only; multi-port elements must override (ChipElm returns `pins[n].current`, CompositeElm sums child currents).
- `allocNodes()` is self-healing: `getNodeState(i)` reallocates on index out-of-bounds rather than throwing.
- `creationFailed()` = true when drag ends with zero size → caller deletes the element.
- `allowMove` scans `simulator().elmList` for overlap (O(N) per move).

## 4. Integration Points  {#C_ELB_04}

### 4.1. Dependencies  {#C_ELB_04_01}

- **[C_UTL](./util-locale-log.concept.md)** — `util.Locale` for SI-prefix localisation.
- **[C_GEO](./geometry.concept.md)** — Point/Rectangle/Polygon; `ElmGeometry` sits on top of these.
- **[C_RND](./rendering-primitives.concept.md)** — `Graphics`, `Color`, thick-line and polygon draw primitives.
- **[C_SHM](./shared-models.concept.md)** — `CustomLogicModel.escape/unescape` used as dump helpers.
- **C_EIC (edit-info-contract, pending)** — `dialog.Editable`/`dialog.EditInfo`; the strongest cross-package coupling (104 imports).
- **client/ root-level** — `CircuitDocument`, `CircuitSimulator`, `CircuitEditor`, `CircuitElmCreator`, `CircuitNode`, `CircuitNodeLink`, `CirSim.console`.

### 4.2. API Surface  {#C_ELB_04_02}

What `element-base` exposes to the rest of the system:

- The `CircuitElm` polymorphic surface (simulation / topology / draw / edit / persistence — see SP_ELB).
- `BaseCircuitElm` static helpers — numeric/unit formatting, integer math, geometry helpers, thick-line drawing.
- `ElmGeometry` mutation API (`setEndpoints`, `translate`, `dragTo`, `movePoint`, `flipX/Y/XY`, `calcLeads`, `adjustLeadsToGrid`).
- `Inductor` helper contract (`setup`, `reset`, `stamp`, `startIteration`, `doStep`, `calculateCurrent`).
- `ChipElm.Pin` model and `fixName` prefix parser (`/`, `#`, `CLK:`, `INV:`).
- `CompositeElm.loadComposite` / `dumpElements` sub-netlist machinery.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
