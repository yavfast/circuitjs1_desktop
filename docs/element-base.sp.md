# Element Base — Specification  {#SP_ELB}

> **Code:** SP_ELB
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_ELB](./element-base.concept.md)
> **Depends on specs:** [SP_UTL](./util-locale-log.sp.md), [SP_GEO](./geometry.sp.md), [SP_RND](./rendering-primitives.sp.md), [SP_MDS](./shared-models.sp.md), [SP_EIC](./edit-info-contract.sp.md)
> **Used by specs:** — (will be filled by higher layers)
> **Plan:** [element-base.plan.md](./element-base.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/domain-core__element-base.md`.
>
> Defines the fields, contract methods, constants, and state transitions of the 11 base files in `client/element/`: `BaseCircuitElm`, `CircuitElm`, `ChipElm`, `CompositeElm`, `CustomCompositeElm`, `CustomCompositeChipElm`, `CustomLogicElm`, `SwitchElm`, `GraphicElm`, `ElmGeometry`, `Inductor`.

## 01. Data Structures  {#SP_ELB_01}

> Implements: [C_ELB_02](./element-base.concept.md#C_ELB_02)

### 01_01. CircuitElm (fields)  {#SP_ELB_01_01}

Root abstract base (`extends BaseCircuitElm implements dialog.Editable`). File: `CircuitElm.java:50`. 1955 LOC.

| Field | Type | Required | Default | Constraints | Description |
|-------|------|----------|---------|-------------|-------------|
| `geom` | `ElmGeometry` | yes | new instance | private transient final | Single source of truth for geometry. Access via `geom()`. |
| `elementId` | `String` | yes | lazily generated | unique per document | Document-scoped id (e.g. `R1`, `C2`). |
| `circuitDocument` | `CircuitDocument` | yes | injected | — | Owner context (simulator, editor, renderer, settings). |
| `flags` | `int` | yes | `getDefaultFlags()` | bitfield | Per-element bit flags. |
| `voltSource` | `int` | yes | `-1` | single-source assumption | Default vsn assignment target. |
| `description` | `String` | no | null | — | User's trailing `# comment` on dump line. |
| `lastHandleGrabbed` | `int` | yes | `-1` | — | Drag-handle UI state. |
| `nodeStates[]` | `NodeState[]` | yes | size = postCount + internalNodeCount | lazy (allocNodes) | Per-node `{node, voltage}`. |
| `current` | `double` | yes | 0 | — | Element-through current. |
| `curcount` | `double` | yes | 0 | accumulated | Dot-animation phase. |
| `noDiagonal` | `boolean` | no | false | — | Horizontal/vertical only. |
| `selected` | `boolean` | no | false | — | Editor selection state. |
| `hasWireInfo` | `boolean` | no | false | — | Wire-closure optimization flag. |
| `ps1`, `ps2` | `Point` | yes | new | scratch, reused | Per-element reusable scratch points. |

Nested: `static final class NodeState { int node; double voltage; }`; `public final class Pin` (named-pin facade).

### 01_02. ChipElm (fields)  {#SP_ELB_01_02}

`public abstract class ChipElm extends CircuitElm`. File: `ChipElm.java:32`. 752 LOC.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `csize`, `cspc`, `cspc2` | `int` | from `setSize(int)` | Cell size / spacing pixel constants. |
| `bits` | `int` | `defaultBitCount() == 4` | Configurable bit-width. |
| `highVoltage` | `double` | 5.0 | Logic-high rail (custom via FLAG_CUSTOM_VOLTAGE). |
| `sizeX`, `sizeY`, `flippedSizeX`, `flippedSizeY` | `int` | from `setupPins()` | Logical pin-grid dims. |
| `rectPointsX[]`, `rectPointsY[]` | `int[]` | 4 corners | Chip outline. |
| `pins[]` | `Pin[]` | from `setupPins()` | Pin array. |
| `lastClock` | `boolean` | false | Previous clock sample (edge detection). |
| `labelX`, `labelY` | `int` | — | Chip label center. |

Nested `public class Pin`: `Point post, stub, textloc`; `int pos, side, side0, voltSource, bubbleX, bubbleY`; `String text`; `boolean lineOver, bubble, clock, output, value, state, selected`; `double curcount, current`; `int[] clockPointsX, clockPointsY`.

### 01_03. SwitchElm (fields)  {#SP_ELB_01_03}

`public class SwitchElm extends CircuitElm`. Dump type `'s'` (115). File: `SwitchElm.java:33`.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `position` | `int` | 0 | 0 = closed, 1 = open. |
| `posCount` | `int` | 2 | Discrete position count. |
| `momentary` | `boolean` | false | Spring-return on mouseUp. |
| `label` | `String` | null | Linked-switch group. |

### 01_04. GraphicElm (fields)  {#SP_ELB_01_04}

`public class GraphicElm extends CircuitElm`. Drawing-only (no electrical role). `getPostCount() == 0`. Subclassed by TextElm, BoxElm, LineElm, AntennaElm.

### 01_05. ElmGeometry (fields)  {#SP_ELB_01_05}

`public final class ElmGeometry`. File: `ElmGeometry.java:12`. Package-private fields:

| Field | Type | Description |
|-------|------|-------------|
| `x1, y1, x2, y2` | `int` | Grid-coordinate endpoints. |
| `point1, point2` | `Point` | Canonical endpoint objects (stable refs). |
| `lead1, lead2` | `Point` | Lead points: separate objects from point1/point2 (never aliased), set to the post positions by value on every geometry update until the element computes them. |
| `boundingBox` | `Rectangle` | Selection hit-test rect. |
| `dx, dy, dsign, dn, dpx1, dpy1` | `int/double` | Derived; recomputed by `updatePointsFromEndpoints()`. |

### 01_06. Inductor (fields)  {#SP_ELB_01_06}

`public class Inductor` — companion-model helper, not a `CircuitElm` subclass. File: `Inductor.java:24`.

| Field | Type | Description |
|-------|------|-------------|
| `n0`, `n1` | `int` | Node indices (assigned at `stamp`). |
| `flags` | `int` | `FLAG_BACK_EULER = 2`; absence = trapezoidal. |
| `inductance` | `double` | L in henries. |
| `compResistance` | `double` | Companion resistor value (`2L/Δt` trapz or `L/Δt` back-Euler). |
| `current` | `double` | Inductor current (converged). |
| `curSourceValue` | `double` | Companion current-source value re-stamped each step. |

Invariants:
- `nodeStates.length == getPostCount() + getInternalNodeCount()` after `allocNodes()`.
- All geometry writes go through `geom()`; `x1/y1/x2/y2` are no longer direct fields on `CircuitElm`.
- `point1.{x,y} == (x1,y1)`, `point2.{x,y} == (x2,y2)` after `updatePointsFromEndpoints()`.
- `dn == sqrt(dx² + dy²)` (possibly clamped to `minDn`).
- `lead1`, `lead2` are never null.

## 02. Contracts  {#SP_ELB_02}

> Implements: [C_ELB_03](./element-base.concept.md#C_ELB_03)

### 02_01. Simulation contract  {#SP_ELB_02_01}

| Method | Default | Purpose |
|--------|---------|---------|
| `getPostCount()` | 2 | External-facing post count. |
| `getInternalNodeCount()` | 0 | Hidden internal nodes (e.g. CustomLogicElm tri-state). |
| `getVoltageSourceCount()` | 0 | Voltage sources this element owns. |
| `allocNodes()` | size nodeStates[] | Called by ctor + on topology change. |
| `setNode(int p, int n)` | store in nodeStates[p].node | Called pre-stamp. |
| `setVoltageSource(int n, int v)` | assign single `voltSource` field | Multi-source elements must override. |
| `stamp()` | no-op | Contribute MNA entries. Called once per analysis. |
| `nonLinear()` | false | Force Newton iteration. |
| `startIteration()` | no-op | Pre-step hook. |
| `doStep()` | no-op | Per-Newton-iteration update. |
| `setNodeVoltage(int n, double c)` | store + call `calculateCurrent()` | Solver push-back. |
| `setNodeVoltageDirect(int n, double c)` | store only | Skip `calculateCurrent()` (JSON import, drawing). |
| `setCurrent(int vn, double c)` | `current = c` | Solver push-back of vsn current. |
| `calculateCurrent()` | no-op | Recompute current from node voltages. |
| `stepFinished()` | no-op | Post-convergence hook. |
| `reset()` | clear volts + curcount | Reset user action. |
| `getCurrent()` | `current` | — |
| `getCurrentIntoNode(int n)` | ±current for 2-terminal | Multi-port must override. |
| `getPower()` | `Vd * I` | — |
| `getVoltageDiff()` | `V0 - V1` | — |

### 02_02. Topology contract  {#SP_ELB_02_02}

| Method | Default | Called by |
|--------|---------|-----------|
| `getPost(int n)` | point1/point2 | Renderer, hit-test. |
| `getConnectedPost()` | point2 | Wire-closure opt. |
| `getConnectionNode(int n)` | `getNode(n)` | BFS. |
| `getConnection(int n1, int n2)` | true | Connectivity analysis. |
| `hasGroundConnection(int n1)` | false | Ground-ref analysis. |
| `isWireEquivalent()` | false | Optimization. |
| `isRemovableWire()` | false | Optimization. |
| `isIdealCapacitor()` | false | Matrix optimization. |
| `canViewInScope()` | `postCount ≤ 2` | Scope binding. |
| `canFlipX/Y/XY()` | true/true/true | Editor flip support. |

### 02_03. Drawing contract  {#SP_ELB_02_03}

| Method | Default | Purpose |
|--------|---------|---------|
| `draw(Graphics g)` | no-op | Every visible element overrides. |
| `drawPosts(Graphics g)` | 7×7 ovals when highlighted | Post markers. |
| `drawHandles(Graphics, Color)` | — | Drag handles. |
| `isCenteredText()` | false | TextElm override. |
| `getVoltageColor(Graphics, double)` | palette lookup | Voltage-colored line. |
| `setPowerColor/setVoltageColor/setConductanceColor` | — | Color state. |
| `drawValues(Graphics, String, double)` | — | Value-label draw. |
| `getScopeValue(int)` / `getScopeUnits(int)` | current | Scope plotting. |

### 02_04. Editing contract  {#SP_ELB_02_04}

| Method | Default | Purpose |
|--------|---------|---------|
| `getEditInfo(int n)` | null | n-th dialog row; null terminates. |
| `setEditValue(int n, EditInfo ei)` | no-op | Pull user value back. |
| `getInfo(String[] arr)` | — | Fill info-pane (up to 20 slots). |
| `getShortcut()` | 0 | Creation keyboard shortcut. |
| `getBasicInfo(String[])` | — | Helper for 2-terminal. |
| `updateModels()` | no-op | For elements bound to named models. |

### 02_05. Persistence contract  {#SP_ELB_02_05}

Legacy text:
- `getDumpType()` — **required**; throws `IllegalStateException` if not overridden.
- `dump()` — default `type x1 y1 x2 y2 flags`; subclasses append tokens.
- `getDumpClass()` — returns `getClass()` (legacy).
- `dumpValues(Object...)` / `dumpValue(int|double|boolean)` — token helpers.
- `escape/unescape(String)` — delegate to `CustomLogicModel`.

JSON:
- `getJsonTypeName()` — defaults to class-name minus `Elm`.
- `getJsonProperties()` / `applyJsonProperties(Map)` — symmetric property bag.
- `getJsonState()` / `applyJsonState(Map)` — simulation state.
- `getJsonPinNames()` / `getJsonPinPosition(int)` — **deprecated**; prefer `getPins()`/`Pin.getPosition()`.
- `getJsonStartPoint()` / `getJsonEndPoint()` — emitted only when geometry != pin0/pin1.
- `getJsonBounds()`, `getJsonFlags()`, `applyJsonFlags(int)`, `applyJsonPinPositions(Map)`, `finalizeJsonImport()`.

## 03. Validation Rules  {#SP_ELB_03}

### 03_01. Input Validation  {#SP_ELB_03_01}

- `getDumpType()` throws `IllegalStateException` unless overridden (`CircuitElm.java:320-327`).
- `ChipElm.stamp()` logs `"voltage source count does not match number of outputs"` on mismatch (`ChipElm.java:329`).
- `ChipElm.writeOutput(n, v)` logs `"pin n is not an output!"` on non-output write.
- `ChipElm.setVoltageSource(j, vs)` logs `"setVoltageSource failed for <elm>"` when `j` exceeds output count.
- `CompositeElm.loadComposite()` throws `IllegalArgumentException` when external node not in `compNodeHash`.
- `Inductor.calculateCurrent()` guards `compResistance == 0` (pre-stamp).
- `ElmGeometry.movePoint()` rejects zero-length collapse.
- `CircuitElm.allowMove()` rejects exact-overlap with existing element.
- `CircuitElm.creationFailed()` = true → caller deletes on zero-size drag.
- `nodeStates[]` is self-healing via `getNodeState(i)` reallocation.

## 04. State Transitions  {#SP_ELB_04}

### 04_01. Element lifecycle  {#SP_ELB_04_01}

State diagram:

```
  constructed ──drag──> placed ──analyze──> analyzed
                         ▲                     │
                         │                     │  per timestep:
                         │                     ▼
                    user-edit          startIteration
                         ▲                     │
                         │                     ▼
                         │           (Newton loop if nonLinear)
                         │                     │
                         │                     ▼
                         │             doStep → solve
                         │                     │
                         │                     ▼
                         │           setNodeVoltage / setCurrent / calculateCurrent
                         │                     │
                         │                     ▼
                         │              stepFinished
                         │                     │
                         │                     ▼
                         └──reset──── (next step) ──┐
                                                    │
                              delete ──────────> removed
```

| From | To | Condition | Side effects |
|------|----|-----------|--------------|
| constructed | placed | user drag ends, size > 0 | `setPoints()` runs |
| placed | analyzed | CirSim.analyzeCircuit | `allocNodes → setNode* → setVoltageSource* → stamp` |
| analyzed | per-step | simulator tick | `startIteration`, then per-iteration `doStep` |
| any | analyzed | reset pressed | `reset()` clears state |
| any | removed | delete | `delete()` fires; dependents cleaned |

Ordering guarantees:
- `setNode` always before `stamp`.
- `setVoltageSource` always before `stamp` for elements with `voltageSourceCount > 0`.
- `stamp` runs once per topology; `doStep` may run many times per timestep.
- `setCurrent(vn, c)`: `vn` matches prior `setVoltageSource(n, vn)`.
- `stepFinished` runs last in the step.

## 05. Verification Criteria  {#SP_ELB_05}

### 05_01. Functional Expectations  {#SP_ELB_05_01}

| Contract | Scenario | Input | Expected outcome |
|----------|----------|-------|------------------|
| `dump/undump` | round-trip | element dump line | re-parsed element equals original (same flags, geometry, params) |
| `stamp/doStep` | linear element | R=1kΩ between two nodes | MNA row reflects 1/R conductance entries |
| `nonLinear` | diode | — | returns true; simulator uses Newton iteration |
| `setCurrent(vn,c)` | ChipElm | vn matches an output pin's voltSource | `pins[i].current == c` for that i |
| `getDumpType` | base-only element | no override | throws `IllegalStateException` |

### 05_02. Invariant Checks  {#SP_ELB_05_02}

| Invariant | Verification |
|-----------|--------------|
| `nodeStates.length == postCount + internalNodeCount` | assert after `allocNodes` |
| All geometry reads/writes via `geom()` | static analysis — no direct x1/y1 writes outside `ElmGeometry` |
| `voltSource` single-assignment | check `voltageSourceCount ≤ 1` for elements that don't override `setVoltageSource` |
| `point1/point2` stable refs | identity unchanged across `setEndpoints` |

### 05_03. Integration Scenarios  {#SP_ELB_05_03}

| Scenario | Preconditions | Steps | Expected |
|----------|---------------|-------|----------|
| Composite netlist | CompositeElm with 3 children | analyze → stamp | `stamp()` forwards to each child; `nonLinear()` true iff any child is |
| Switch open | `position = 1` | connectivity BFS | `getConnection(n1,n2) == false`, not wire-equivalent |
| ChipElm output | pin marked output | `stamp + doStep` | stamps voltage source; `doStep` writes `p.value ? highVoltage : 0` |
| Inductor trapezoidal | L=1mH, Δt=10μs | `stamp + startIteration + doStep` | `compResistance = 2L/Δt = 200Ω`; `curSourceValue` updates each step |

### 05_04. Edge Cases and Boundaries  {#SP_ELB_05_04}

| Case | Input | Expected |
|------|-------|----------|
| Zero-size drag | x1==x2 && y1==y2 | `creationFailed() == true`; element discarded |
| Missing dump token | truncated line | try/catch in ctor; default-initialized where possible |
| Out-of-range `getNodeState(i)` | i >= nodeStates.length | `allocNodes()` re-runs; no IOOBE |
| Multi-source element w/o override | 2 voltage sources | silent bug — only the last one retained in `voltSource` |

## 06. Constants  {#SP_ELB_06}

BaseCircuitElm: `PI`, `PI_2`, `CURRENT_TOO_FAST = 100`, `THICK_LINE_WIDTH = 2.0`, `SCALE_AUTO = 0`, `SCALE_1 = 1`, `SCALE_M = 2`, `SCALE_MU = 3`.

ChipElm flag bits: `FLAG_SMALL = 1`, `FLAG_FLIP_X = 1<<10`, `FLAG_FLIP_Y = 1<<11`, `FLAG_FLIP_XY = 1<<12`, `FLAG_CUSTOM_VOLTAGE = 1<<13`.

ChipElm side constants: `SIDE_N = 0`, `SIDE_S = 1`, `SIDE_W = 2`, `SIDE_E = 3`. `sideFlipXY[] = {W, E, N, S}`.

SwitchElm flags: `FLAG_IEC = 2`, `FLAG_LABEL = 4`. Shortcut `'s'`.

CompositeElm: `FLAG_ESCAPE = 1`.

CustomCompositeElm: `FLAG_SMALL = 2`. Dump type `410`.

CustomLogicElm: dump type `208`.

Inductor: `FLAG_BACK_EULER = 2`.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
