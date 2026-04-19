# Module Analysis: domain-core / element-base

> **Path:** src/main/java/com/lushprojects/circuitjs1/client/element/ (11 base files)
> **Layer:** 2 (SCC-A)
> **Analyzed:** 2026-04-18
> **Files:** 11 source files, 0 test files

## Purpose

The `element-base` sub-unit defines the **element type system** of CircuitJS1 —
the abstract contract that every circuit component (≈135 concrete `*Elm`
classes) implements. These 11 files constitute the complete surface area that
the simulator core (`CirSim`, `CircuitSimulator`), the editor
(`CircuitEditor`, `CircuitRenderer`), and the I/O framework (`io/text`,
`io/json`) use to interact with circuit components.

The module packages four concerns:

1. **Simulation contract** — `stamp()`, `doStep()`, `startIteration()`,
   `stepFinished()`, `setCurrent()`, `setNode()`, `setVoltageSource()`,
   `nonLinear()`, `reset()` (MNA-style stamp-and-solve).
2. **Topology contract** — `getPostCount`, `getPost(n)`,
   `getInternalNodeCount`, `getConnection`, `hasGroundConnection`,
   `getVoltageSourceCount`, `isWireEquivalent`.
3. **Rendering + editing contract** — `draw(Graphics)`, `drawPosts`,
   `drawValues`, `setPoints`, `setBbox`, `getEditInfo(n)`,
   `setEditValue(n, ei)`, `getInfo(arr)`, `getShortcut`.
4. **Persistence contract** — `dump()`, `getDumpType()`, `getDumpClass()`,
   plus JSON counterparts (`getJsonTypeName`, `getJsonProperties`,
   `getJsonState`, `getJsonPinNames`, `applyJsonProperties`,
   `applyJsonState`, `finalizeJsonImport`).

Core MNA theory (stamp matrix, companion models for capacitors/inductors,
linearization for nonlinear devices) is summarized in `INTERNALS.md`.

## Class hierarchy

```
BaseCircuitElm                          (pure static-utility helper; not
  └── CircuitElm  [abstract]             instantiated)
        │   implements dialog.Editable
        │
        ├── GraphicElm                  (no-post drawing-only base
        │                                 → TextElm, BoxElm, LineElm, …)
        │
        ├── SwitchElm                   (SPST switch base
        │                                 → Switch2Elm, DPDTSwitchElm,
        │                                    PushSwitchElm, LogicInputElm,
        │                                    MBBSwitchElm, CrossSwitchElm,
        │                                    MotorProtectionSwitchElm,
        │                                    PhotoSwitch-like, RelayContactElm)
        │
        ├── ChipElm       [abstract]    (multi-pin IC base; setupPins abstract
        │     │                          → ~50 gate/FF/chip classes)
        │     │
        │     ├── CustomCompositeChipElm   (concrete shell used by
        │     │                             CustomCompositeElm to render pins)
        │     │
        │     └── CustomLogicElm          (user-programmable logic chip —
        │                                  extends ChipElm, not CompositeElm)
        │
        └── CompositeElm  [abstract]    (element-of-elements base
              │                          → dump type 410, nested sub-netlist)
              │
              └── CustomCompositeElm    (concrete subcircuit instance;
                                         owns a CustomCompositeChipElm for
                                         rendering; dump type 410)

(not in hierarchy — helpers owned by CircuitElm)
  ElmGeometry       final class owned by every CircuitElm via `geom()`
  Inductor          plain class — physics helper used by InductorElm,
                    TransformerElm, CustomTransformerElm, DCMotorElm,
                    TappedTransformerElm, ThreePhaseMotorElm, RelayCoilElm
```

Note `BaseCircuitElm` is **not abstract** — it is a bag of `static` helpers
(formatting, geometry math, thick-line drawing) that happens to be the
superclass of `CircuitElm` for `CircuitElm.java:50` (`extends BaseCircuitElm`)
and for shared constants (`CURRENT_TOO_FAST`, `THICK_LINE_WIDTH`, `PI`,
`SCALE_*`). It has a package-private no-op constructor and is never
directly instantiated outside the subclass chain.

## Key Entities

### CircuitElm  (root abstract base)

- **Type:** `public abstract class CircuitElm extends BaseCircuitElm
  implements com.lushprojects.circuitjs1.client.dialog.Editable`
- **File:** src/main/java/com/lushprojects/circuitjs1/client/element/CircuitElm.java:50
- **Size:** 1955 LOC; the single largest base in the project.
- **Declared abstract:** yes. However `getDumpType()` is **intentionally
  non-abstract** (line 320-327) and throws `IllegalStateException` by
  default to work around a GWT compiler bug affecting `OTAElm`. Subclasses
  are expected to override it as if it were abstract.

#### Fields (every concrete element inherits this state)

| Field | Type | Visibility | Purpose |
|---|---|---|---|
| `geom` | `ElmGeometry` | `private transient final` | **Single source of truth** for geometry (endpoints, leads, bounding box). Accessed via `geom()`. Lines 53-78. |
| `elementId` | `String` | `private` | Unique per-document ID (e.g. `R1`, `C2`). Lazily generated via `generateElementId()`. |
| `circuitDocument` | `CircuitDocument` | `public` | Parent document; source of simulator, editor, renderer, display settings. Line 125. |
| `flags` | `int` | `public` | Per-element bit flags (set via `getDefaultFlags()` at construction). Line 127. |
| `voltSource` | `int` | `public` | Voltage-source index assigned by simulator (single-source default). Line 129. Overridden in multi-source elements. |
| `description` | `String` | `private` | User-authored trailing `# comment` from the dump line. |
| `lastHandleGrabbed` | `int` | package-private | Drag handle UI state (-1 = none). Line 133. |
| `nodeStates[]` | `NodeState[]` | `private transient` | Canonical per-node state (`node`, `voltage`). Allocated by `allocNodes()` to size `getPostCount() + getInternalNodeCount()`. Lines 142-146. |
| `current` | `double` | `public` | Current through element (set by simulator via `setCurrent(vn,c)` or computed by subclasses). Line 148. |
| `curcount` | `double` | `public` | Accumulated phase for the moving current dot animation. Line 148. |
| `noDiagonal` | `boolean` | `public` | If true, element is constrained to horizontal or vertical placement (enforced by `ElmGeometry.dragTo`/`movePoint`). Line 151. |
| `selected` | `boolean` | `public` | Editor selection state. Line 153. |
| `hasWireInfo` | `boolean` | `public` | Used by `calcWireInfo()` in wire-closure optimization. Line 155. |
| `ps1`, `ps2` | `Point` | `protected final` | Per-element scratch points (reused across `setPoints` calls). Lines 158-159. |

Nested: `static final class NodeState { int node; double voltage; }` (line 142)
and `public final class Pin { int index; String name; … }` (line 1416) — a
**named-pin façade** built over `getPost(i)` / `getNode(i)` /
`getPostVoltage(i)` / `getCurrentIntoNode(i)` to support the JSON/JS API.

#### Override points (contract extension for ~135 concrete elements)

Grouped by concern. File:line refers to the default implementation.

**Lifecycle / construction**
- `getDefaultFlags()` → `int`  —  line 334  (default 0)
- `allocNodes()`  —  line 373  (rarely overridden; allocates NodeState[])
- `getIdPrefix()` → `String`  —  line 311  (for `generateElementId()`)

**Simulation — stamping**
- `stamp()` → `void`  —  line 578  (called once per circuit analysis; no-op default)
- `doStep()` → `void`  —  line 582  (called once per timestep; no-op default)
- `startIteration()` → `void`  —  line 592  (called each timestep before Newton iteration)
- `stepFinished()` → `void`  —  line 1326  (called after timestep converges)
- `nonLinear()` → `boolean`  —  line 889  (default false; forces iterative solve when true)
- `reset()` → `void`  —  line 551  (clears volts + curcount; subclasses extend to clear internal state)

**Simulation — node/voltage source accounting**
- `getPostCount()` → `int`  —  line 893  (default 2)
- `getInternalNodeCount()` → `int`  —  line 863  (default 0)
- `getVoltageSourceCount()` → `int`  —  line 857  (default 0)
- `setNode(int p, int n)` → `void`  —  line 869  (stores global node# for post p)
- `setVoltageSource(int n, int v)` → `void`  —  line 876  (default assigns to the single `voltSource` field)
- `setCurrent(int vn, double c)` → `void`  —  line 563  (sets `current`; vn matches a prior `setVoltageSource`)
- `setNodeVoltage(int n, double c)` → `void`  —  line 606  (invokes `calculateCurrent()`)
- `calculateCurrent()` → `void`  —  line 622  (package-private; default no-op; override to recompute current from node voltages)
- `getCurrent()` → `double`  —  line 568  (simple 1-/2-terminal default)
- `getCurrentIntoNode(int n)` → `double`  —  line 1330  (default returns ±current for 2-terminal; override for multi-port)
- `getPower()` → `double`  —  line 1188  (default `Vd * I`)
- `getVoltageDiff()` → `double`  —  line 885  (default `V0 - V1`)

**Topology**
- `getPost(int n)` → `Point`  —  line 903  (default point1/point2)
- `getConnectedPost()` → `Point`  —  line 910  (wire-closure optimization)
- `getConnectionNodeCount()` → `int`  —  line 1208  (default = getPostCount)
- `getConnectionNode(int n)` → `int`  —  line 1215  (diverges for LabeledNodeElm)
- `getConnection(int n1, int n2)` → `boolean`  —  line 1221  (default true; overridden in ChipElm/SwitchElm-open)
- `hasGroundConnection(int n1)` → `boolean`  —  line 1226  (default false)
- `isWireEquivalent()` → `boolean`  —  line 1231  (default false)
- `isRemovableWire()` → `boolean`  —  line 1236  (default false)
- `isIdealCapacitor()` → `boolean`  —  line 1240  (default false)
- `canViewInScope()` → `boolean`  —  line 1244  (default `postCount ≤ 2`)
- `canFlipX()` / `canFlipY()` / `canFlipXY()` → `boolean`  —  lines 1248-1258

**Geometry / interaction**
- `setPoints()` → `void`  —  line 627  (recomputes derived geometry; subclasses override to place internal points/leads)
- `adjustDerivedGeometry(ElmGeometry)` → `void`  —  line 64  (hook inside `ElmGeometry.updatePointsFromEndpoints()`)
- `drag(int xx, int yy)`  —  line 689  (default delegates to `geom().dragTo`; ChipElm overrides)
- `move(int dx, int dy)`  —  line 693
- `creationFailed()` → `boolean`  —  line 699
- `allowMove(int dx, int dy)` → `boolean`  —  line 718
- `movePoint(int n, int dx, int dy)`  —  line 736
- `flipX(int,int)` / `flipY(int,int)` / `flipXY(int,int)` / `flipPosts()`  —  lines 740-754
- `isFixedSizeOnCreate()` → `boolean`  —  line 800
- `dragFixedSize(int,int)`  —  line 808
- `getNumHandles()` → `int`  —  line 781  (default `min(postCount, 2)`)
- `getHandlePoint(int n)` → `Point`  —  line 792
- `getMouseDistance(int,int)` → `int`  —  line 1311
- `draggingDone()` → `void`  —  line 1308

**Drawing**
- `draw(Graphics g)` → `void`  —  line 558  (default no-op; **every visible element overrides this**)
- `drawPosts(Graphics g)`  —  line 756
- `drawHandles(Graphics, Color)`  —  line 812
- `isCenteredText()` → `boolean`  —  line 951  (TextElm overrides)
- `getVoltageColor(Graphics, double)` → `Color`  —  line 1143
- `setPowerColor(Graphics, boolean)`  —  line 1158
- `getScopeValue(int)` → `double`  —  line 1192
- `getScopeUnits(int)` → `int`  —  line 1196
- `canShowValueInScope(int)` → `boolean`  —  line 1272

**Editing / dialog**
- `getEditInfo(int n)` → `EditInfo`  —  line 1200  (default null; build EditInfo per row)
- `setEditValue(int n, EditInfo ei)` → `void`  —  line 1204  (default no-op)
- `getInfo(String[] arr)` → `void`  —  line 1128  (fills lower-right info pane)
- `getBasicInfo(String[])` → `int`  —  line 1131  (helper for 2-terminal elements)
- `getScopeText(int v)` → `String`  —  line 1137
- `getShortcut()` → `int`  —  line 1296  (keyboard shortcut char; 0 = none)
- `needsShortcut()` → `boolean`  —  line 1292
- `doAdjust()` / `setupAdjust()`  —  lines 1121-1125
- `updateModels()` → `void`  —  line 1323  (for elements bound to named models)
- `dumpModel()` → `String`  —  line 1315  (models embedded in the dump line)

**Persistence — legacy text format**
- `getDumpType()` → `int`  —  line 320  (**must** be overridden; throws otherwise)
- `getDumpClass()` → `Class<? extends CircuitElm>`  —  line 330  (legacy — returns `getClass()`)
- `dump()` → `String`  —  line 419  (default: `type x1 y1 x2 y2 flags`)

**Persistence — JSON format**
- `getJsonTypeName()` → `String`  —  line 1534  (default: class name w/o `Elm` suffix)
- `getJsonProperties()` → `Map<String,Object>`  —  line 1550  (default empty map)
- `getJsonPropertiesAsArray()` → `String[]`  —  line 1558
- `setPropertyValue(String, double)` → `boolean`  —  line 1578  (JS/UI property setter)
- `getJsonPinNames()` → `String[]`  —  line 1592 (deprecated; prefer `getPins()`)
- `getJsonPinPosition(int)` → `Point`  —  line 1615 (deprecated)
- `getJsonStartPoint()` → `Point`  —  line 1627  (override when point1 ≠ first-pin)
- `getJsonEndPoint()` → `Point`  —  line 1646  (override when point2 ≠ last-pin)
- `getJsonState()` → `Map<String,Object>`  —  line 1673
- `applyJsonState(Map)` → `void`  —  line 1713
- `getJsonBounds()` → `Map<String,Integer>`  —  line 1750
- `getJsonFlags()` → `int`  —  line 1764
- `applyJsonProperties(Map)` → `void`  —  line 1786
- `applyJsonPinPositions(Map)` → `void`  —  line 1797
- `applyJsonFlags(int)` → `void`  —  line 1866
- `finalizeJsonImport()` → `void`  —  line 1875
- `getPins()` / `getPin(int)` / `getPinByName(String)`  —  lines 1456-1501

**Lifecycle / disposal**
- `delete()` → `void`  —  line 585  (clears mouse ref; deletes sliders)
- `setMouseElm(boolean)`  —  line 1300
- `setParentList(ArrayList<CircuitElm>)`  —  line 572  (CompositeElm wires this; default no-op)

#### Invariants

- `nodeStates.length == getPostCount() + getInternalNodeCount()` after
  `allocNodes()`. Must be re-allocated if post/internal counts change
  (done inside `allocNodes()`).
- **Endpoint writes flow through `geom()`** — `x/y/x2/y2` are no longer fields.
  All mutation uses `geom().setEndpoints(...)`, `geom().translate(...)`,
  `geom().dragTo(...)`, etc. `ensureGeometryUpdated()` (line 74) is a
  compatibility shim from earlier refactors.
- If `getDumpType()` is not overridden, `dump()` throws
  `IllegalStateException` — every concrete `CircuitElm` must override.
- `getConnection(n1,n2)` defaults to `true` for every post-pair — fine for
  wires/resistors/capacitors, but misleading for chips/switches/diodes,
  which must override.
- `setVoltageSource(n,v)` default stores into the single `voltSource` field
  — correct only for 0- or 1-source elements. Multi-source elements
  (`ChipElm`, `CompositeElm`, `OpAmpElm`, etc.) must override.
- `setCurrent(vn, c)` default assumes the element's current is exactly the
  vsn'th voltage-source current; multi-source elements must route `c` to
  the correct internal current field by `vn`.
- `canViewInScope()` is a soft convention: post-count > 2 usually returns
  false unless the element overrides (ChipElm outputs, ScopeElm itself).

### BaseCircuitElm

- **Type:** `public class` (package-constructor; utility base for `CircuitElm`)
- **File:** src/main/java/com/lushprojects/circuitjs1/client/element/BaseCircuitElm.java:10
- **Role:** A grab-bag of **static helpers** that every `CircuitElm`
  subclass accesses without a receiver (because `CircuitElm extends
  BaseCircuitElm`). There is no instance state.
- **Constants:**
  - `PI`, `PI_2` (double shortcuts)
  - `CURRENT_TOO_FAST = 100` — sentinel for the "dots moving too fast"
    branch in `CircuitElm.drawDots`
  - `THICK_LINE_WIDTH = 2.0`
  - `SCALE_AUTO = 0`, `SCALE_1 = 1`, `SCALE_M = 2`, `SCALE_MU = 3` —
    unit-scaling mode enum
- **Static helpers (by family):**
  - Number/unit formatting: `shortFormat`, `showFormat`, `fixedFormat`,
    `numFormat`, `formatNumber(…)` (three overloads), `format(v, sf)`,
    `getUnitText`, `getShortUnitText`, `getUnitTextWithScale`,
    `getSIPrefix`, `getVoltageText`/`getVoltageDText`,
    `getCurrentText`/`getCurrentDText`, `getTimeText`.
  - Integer math: `abs`, `sign`, `min`, `max`, `comparePair`.
  - Geometry: `distance(Point,Point)`, `lineDistanceSq(…)`,
    `newPointArray(n)`, `interpPoint(…)` (3 overloads), `interpPoint2(…)`,
    `calcArrow(…)`, `createPolygon(…)` (3 overloads).
  - Drawing: `drawThickLine(Graphics, int×4)` and
    `drawThickLine(Graphics, Point, Point)`, `drawThickPolygon`,
    `drawPolygon`, `drawThickCircle`.
  - Current helper: `addCurCount(c, a)` (saturates at `CURRENT_TOO_FAST`).
- **Cross-layer quirk:** `util/PerfMonitor` imports `BaseCircuitElm` **solely**
  to use `formatNumber(float,int)`. This is the single `util → element`
  edge documented in the dependency graph.

### ChipElm (multi-pin IC base)

- **Type:** `public abstract class ChipElm extends CircuitElm`
- **File:** src/main/java/com/lushprojects/circuitjs1/client/element/ChipElm.java:32
- **Size:** 752 LOC.
- **Role:** Base for all multi-pin ICs (gates, flip-flops, counters,
  multiplexers, adders, shift registers, 7-seg, etc.). Most concrete chips
  implement one abstract method (`setupPins`) plus one abstract getter
  (`getVoltageSourceCount`) and an `execute()` hook.
- **Fields:**
  - `csize`, `cspc`, `cspc2` — integer pixel constants (cell size, cell
    spacing, cell-spacing × 2). Driven by `setSize(int)`.
  - `bits` — configurable bit-width (counters, shift registers…); default
    `defaultBitCount() == 4`.
  - `highVoltage` — logic-high rail (default 5.0 V; customizable via
    `FLAG_CUSTOM_VOLTAGE`).
  - `sizeX`, `sizeY`, `flippedSizeX`, `flippedSizeY` — logical pin-grid
    dimensions.
  - `rectPointsX[]`, `rectPointsY[]` — four corners of the chip outline.
  - `pins[]` — `Pin` objects (see nested class below).
  - `lastClock` — previous clock sample for edge detection.
  - `labelX`, `labelY` — chip label center.
- **Flag constants:**
  - `FLAG_SMALL = 1`
  - `FLAG_FLIP_X = 1 << 10`
  - `FLAG_FLIP_Y = 1 << 11`
  - `FLAG_FLIP_XY = 1 << 12`
  - `FLAG_CUSTOM_VOLTAGE = 1 << 13`
- **Side constants (pin placement):**
  - `SIDE_N = 0`, `SIDE_S = 1`, `SIDE_W = 2`, `SIDE_E = 3`
  - `sideFlipXY[] = {SIDE_W, SIDE_E, SIDE_N, SIDE_S}` (remap for XY flip)
- **Override points specific to ChipElm:**
  - `setupPins()` — **abstract**; subclasses populate `pins[]`,
    `sizeX/sizeY`, and call `fixName()` on pins with leading `/` (line-over),
    `#` (bubble), `CLK:` (clock), `INV:` (bubble).
  - `getVoltageSourceCount()` — **abstract**; returns the output count.
  - `execute()` — empty default; override to implement the chip's
    combinational/sequential logic (reads `pins[i].value` for inputs,
    writes `pins[i].value` for outputs).
  - `needsBits()` / `defaultBitCount()` / `hasCustomVoltage()` /
    `isDigitalChip()` / `getThreshold()` / `getChipName()` — policy hooks.
  - `drawLabel(Graphics, int x, int y)` — chip name overlay.
  - `getChipEditInfo(int n)` / `setChipEditValue(int n, EditInfo ei)` —
    element-specific dialog rows (ChipElm reserves row 0 for High Logic
    Voltage when `isDigitalChip()` is true).
- **Built-in simulation contract (ChipElm):**
  - `stamp()` — for every output pin, `simulator.stampVoltageSource(0, getNode(i), p.voltSource)`. Validates count against `getVoltageSourceCount()`.
  - `doStep()` — sample all **input** pins into `p.value`, call `execute()`, then push every **output** pin's value as a voltage source via `simulator.updateVoltageSource(0, getNode(i), p.voltSource, p.value ? highVoltage : 0)`.
  - `reset()` — clears all `pins[i].value` + `curcount` + node voltages; resets `lastClock`.
  - `getCurrentIntoNode(int n)` — returns `pins[n].current` (each output has its own current).
  - `setCurrent(int x, double c)` — routes `c` to the pin whose `voltSource == x`.
  - `setVoltageSource(int j, int vs)` — assigns `vs` to the j-th output pin.
  - `getConnection(n1, n2)` — returns **false** (chip pins are not mutually connected).
  - `hasGroundConnection(n1)` — returns **true** for output pins (they act as voltage sources referenced to ground).
- **Nested `public class Pin`:**
  - Fields: `Point post, stub, textloc`; `int pos, side, side0, voltSource, bubbleX, bubbleY`; `String text`; `boolean lineOver, bubble, clock, output, value, state, selected`; `double curcount, current`; `int[] clockPointsX, clockPointsY`.
  - `setPoint(int px, int py, int dx, int dy, int dax, int day, int sx, int sy)` — places post/stub/text according to side + flip flags.
  - `overlaps(int p, int s)` / `toGrid(int p, int s)` — collision detection when the user drags pins around.
  - `fixName()` — parses `/`, `#`, `CLK:`, `INV:` prefixes in the pin label.
- **Static bit-pack helpers:** `writeBits(boolean[])` and
  `readBits(StringTokenizer, boolean[])` — pack/unpack boolean arrays as
  space-separated 32-bit integers in the dump format.
- **Dump format:** `super.dump() + bits + highVoltage + volt[0] … volt[n-1]`
  (only pins with `state==true` contribute voltages).
- **JSON overrides:** `getJsonTypeName()` uses `getChipName()` stripped of
  spaces/hyphens; `getJsonPinNames()` maps `pins[i].text`;
  `getJsonState()` serializes `pins[i].value` under `outputs` +
  `last_clock`.

### CompositeElm (element-of-elements base)

- **Type:** `public abstract class CompositeElm extends CircuitElm`
- **File:** src/main/java/com/lushprojects/circuitjs1/client/element/CompositeElm.java:31
- **Purpose:** Implements a circuit element whose internals are a small
  sub-netlist of ordinary elements. The sub-netlist is stored as a
  string (`model.nodeList`) plus a dump of each child element; at
  construction time `loadComposite()` rebuilds the sub-netlist and hooks
  up `CircuitNodeLink`s.
- **Nested `protected static class VoltageSourceRecord`:** maps
  `(elm, vsNumForElement, vsNode)` so `setVoltageSource(n, v)` can forward
  to the correct child.
- **Fields:**
  - `compElmList: ArrayList<CircuitElm>` — child elements
  - `compNodeList: ArrayList<CircuitNode>` — internal node graph
  - `numPosts`, `numNodes` — external/total node counts
  - `nodes: int[]` — local-index → global node map
  - `posts: Point[]` — external post positions (subclasses fill)
  - `voltageSources: ArrayList<VoltageSourceRecord>`
  - `connectionMap`, `groundConnectionMap` — cached BFS results
  - `FLAG_ESCAPE = 1` — enable `CustomLogicModel.escape`-based dumping
    so composites can nest safely
- **Contract implementation (forwards to children):**
  - `stamp()` — calls `ce.setParentList(compElmList); ce.stamp()` for each child
  - `startIteration()` — forwards to each child
  - `doStep()` — forwards to each child
  - `stepFinished()` — forwards to each child
  - `reset()` — forwards to each child
  - `nonLinear()` — true if **any** child is nonlinear
  - `getPower()` — sum of child powers
  - `delete()` — deletes each child, then self
  - `setNodeVoltage(n, c)` — propagates to every child linked at internal node n
  - `setNode(p, n)` — maps external post → global node, then propagates
  - `setVoltageSource(n, v)` — delegates via `VoltageSourceRecord`
  - `setCurrent(vsn, c)` — routes by matching `vsNode`
  - `getCurrentIntoNode(n)` — sums child currents into that node
  - `getConnection(n1, n2)` — **BFS** through child connections
    (`getConnectionSlow`), memoized via `connectionMap`
  - `hasGroundConnection(n1)` — BFS, memoized via `groundConnectionMap`
- **Topology:** `getPostCount() == numPosts`,
  `getInternalNodeCount() == numNodes - numPosts`,
  `getPost(n) == posts[n]`.
- **Persistence:** `dump()` = `super.dump() + dumpElements()` where each
  child dump is escaped and has its `x1 y1 x2 y2` coordinates stripped
  (they are irrelevant inside a composite). `dumpWithMask(int)` allows
  selective dumping (bit mask over children).
- **JSON:** `getJsonState()` / `applyJsonState()` add a `subElements`
  array preserving per-child state.
- **Caveats:** `canViewInScope()` returns false; the default
  `CircuitElmCreator.constructElement(...)` is used to rebuild children,
  which means composites can only reference element types the factory
  knows about.

### CustomCompositeElm

- **Type:** `public class CustomCompositeElm extends CompositeElm`
- **File:** src/main/java/com/lushprojects/circuitjs1/client/element/CustomCompositeElm.java:21
- **Dump type:** `410` (line 254-256).
- **Role:** A **user-defined subcircuit instance**. Resolves a
  `CustomCompositeModel` by name from a global registry and uses it to
  populate the netlist plus pin list.
- **Composition hack:** Because Java lacks multiple inheritance,
  `CustomCompositeElm` cannot extend both `CompositeElm` and `ChipElm`.
  Instead it **owns** a `CustomCompositeChipElm chip` that it delegates
  all rendering to — see `draw(Graphics)` (line 91) which copies post
  voltages into the chip, calls `chip.draw(g)`, and then copies the
  chip's bounding box back into its own geometry.
- **Fields:** `modelName`, `chip`, `postCount`, `inputCount`,
  `outputCount`, `model: CustomCompositeModel`, static `lastModelName`
  (remembers the last model the user created from UI), `FLAG_SMALL = 2`.
- **Lifecycle:** `updateModels(StringTokenizer)` looks up the model,
  builds `externalNodes[]` from `model.extList`, calls
  `loadComposite()`, `allocNodes()`, then `setPoints()`. The chip is
  re-created every `setPoints()` call (fresh `CustomCompositeChipElm`).
- **Editing:** `getEditInfo` exposes (row 0) model-name chooser,
  (row 1) Edit Pin Layout button, (row 2) Load Model Circuit button.
- **JSON:** `getJsonTypeName()` returns `"Subcircuit"`;
  `getJsonProperties` adds `model_name`, `input_count`, `output_count`.

### CustomCompositeChipElm

- **Type:** `public class CustomCompositeChipElm extends ChipElm`
- **File:** src/main/java/com/lushprojects/circuitjs1/client/element/CustomCompositeChipElm.java:29
- **Role:** A concrete `ChipElm` used purely for rendering of
  `CustomCompositeElm`. Never participates in the netlist directly
  (`getVoltageSourceCount() == 0`). Exposes:
  - `allocPins(int n)` / `setPin(int n, int p, int s, String t)` /
    `setPins(Pin[])` / `setLabel(String)`
  - `needsBits()` returns false; `setupPins()` is a no-op.
  - `drawLabel()` draws `label` centered.

### CustomLogicElm

- **Type:** `public class CustomLogicElm extends ChipElm`  (dump type `208`)
- **File:** src/main/java/com/lushprojects/circuitjs1/client/element/CustomLogicElm.java:12
- **Role:** A user-programmable combinational/sequential logic block
  defined by a `CustomLogicModel` (lists of input names, output names,
  `rulesLeft[]` pattern strings and `rulesRight[]` output patterns).
- **Pattern language (executed in `execute()`):** `0`/`1` literal,
  `?` don't-care, `+`/`-` rising/falling edge, `a`–`z` save a variable
  value at pin j, `A`–`Z` compare against saved value. Output pattern:
  `0`/`1` constant, `a`–`z` pattern variable, `_` high-impedance.
- **Tri-state support:** when any rule emits `_` the model is marked
  `triState`, and `getInternalNodeCount() == outputCount`. `stamp()`
  adds `stampNonLinear` pairs; `doStep()` stamps a
  `1e8 Ω` (hi-Z) / `1e-3 Ω` (driven) resistor between the internal
  source node and the external output node.
- **Dump:** inherits ChipElm dump but appends the escaped model name
  and then each output voltage (can't use ChipElm's default because pin
  count isn't known until the model is resolved).

### SwitchElm  (SPST base)

- **Type:** `public class SwitchElm extends CircuitElm`  (dump type `'s'` == 115)
- **File:** src/main/java/com/lushprojects/circuitjs1/client/element/SwitchElm.java:33
- **Role:** SPST switch, base for `Switch2Elm`, `DPDTSwitchElm`,
  `PushSwitchElm`, `LogicInputElm`, `MBBSwitchElm`, `CrossSwitchElm`,
  `RelayContactElm`, `MotorProtectionSwitchElm`.
- **Fields:** `momentary` (boolean), `position` (0 = closed, 1 = open),
  `posCount` (number of discrete positions), `label` (for linked-switch
  grouping). Flags `FLAG_IEC = 2`, `FLAG_LABEL = 4`.
- **Override points:** `toggle()` / `simpleToggle()` / `mouseUp()`
  handle user clicks; subclasses override `simpleToggle` for multi-pole
  behavior.
- **Stamp contract:** SwitchElm itself does not override `stamp()`;
  instead the simulator treats closed switches as wires
  (`isWireEquivalent() == position == 0`, `isRemovableWire() == position == 0`,
  `getConnection(n1, n2) == position == 0`). That is: a closed switch
  collapses its posts into a single node during wire-closure analysis;
  an open switch has no connection.
- **Labeled-switch linking:** If `label != null`, `toggle()` also
  toggles every other `SwitchElm` in the same document with the same
  label — cheap global coupling.
- **Shortcut:** `'s'`.

### GraphicElm (drawing-only base)

- **Type:** `public class GraphicElm extends CircuitElm`
- **File:** src/main/java/com/lushprojects/circuitjs1/client/element/GraphicElm.java:26
- **Role:** Superclass for annotation elements that have **no electrical
  role**: `TextElm`, `BoxElm`, `LineElm`, `AntennaElm`.
- **Contract:** `getPostCount()` returns **0**. `getJsonStartPoint()` and
  `getJsonEndPoint()` are forced to always emit coordinates (because
  there are no pins to derive them from).
- **All simulation methods inherit the `CircuitElm` no-op defaults** —
  no `stamp`, no `doStep`, no node allocation (post+internal = 0).

### ElmGeometry

- **Type:** `public final class ElmGeometry`
- **File:** src/main/java/com/lushprojects/circuitjs1/client/element/ElmGeometry.java:12
- **Role:** **Single source of truth** for element geometry. Each
  `CircuitElm` owns one instance via `geom()` and every endpoint /
  lead / bounding-box read or write is funnelled through it.
- **Fields (all package-private):**
  - `x1, y1, x2, y2` — grid-coordinate endpoints
  - `point1, point2` — canonical `Point` objects (stable references);
    lead aliases (`lead1`, `lead2`) default to these and may be
    replaced via `setLead1`/`setLead2`
  - `boundingBox` — `Rectangle` for selection hit-testing
  - Derived: `dx, dy, dsign, dn, dpx1, dpy1` — recomputed by
    `updatePointsFromEndpoints()`
- **Owner hook:** every geometry update calls
  `owner.adjustDerivedGeometry(this)` before returning — the sole
  extension point for elements that need to tweak `dn`/`dpx1`/`dpy1`
  (e.g. some transformer variants).
- **Public API (selection):**
  - `setEndpoints(x1, y1, x2, y2)`, `setX2(int)`, `setY2(int)`,
    `translate(int dx, int dy)`, `dragTo(int xx, int yy)`,
    `movePoint(int n, int dx, int dy)`, `flipX/Y/XY(int)`,
    `flipPosts()`, `dragFixedSize(int, int)`,
    `calcLeads(int len)`, `adjustLeadsToGrid(boolean, boolean)`,
    `initBoundingBox()`, `setBbox(…)`, `adjustBbox(…)`,
    `isZeroSize()`, `getHandlePoint(int)`, `getMouseDistanceSq(int,int)`.
- **Invariants:** after `updatePointsFromEndpoints()`:
  - `point1.{x,y} == (x1,y1)`, `point2.{x,y} == (x2,y2)`
  - `dn == sqrt(dx² + dy²)` (possibly clamped to `minDn` via
    `recomputeDerivedWithMinDn`)
  - When `dn > 0`: `dpx1 = dy/dn`, `dpy1 = -dx/dn` (perpendicular unit vector)
  - `lead1` and `lead2` are never null (default to `point1`/`point2`)
  - `noDiagonal` constraint is enforced by `dragTo` and `movePoint` by
    collapsing the smaller delta to zero.

### Inductor (physics helper)

- **Type:** `public class Inductor`  (**not** a subclass of `CircuitElm` —
  a delegatee)
- **File:** src/main/java/com/lushprojects/circuitjs1/client/element/Inductor.java:24
- **Role:** Implements the two-terminal **Norton companion model** of an
  inductor using either trapezoidal or backward-Euler integration.
  Owned by elements that need per-coil physics: `InductorElm`,
  `TransformerElm`, `CustomTransformerElm`, `TappedTransformerElm`,
  `DCMotorElm`, `ThreePhaseMotorElm`, `RelayCoilElm`.
- **Flags:** `FLAG_BACK_EULER = 2` (absence = trapezoidal).
- **State:** `n0`, `n1` (node indices assigned at `stamp`); `flags`;
  `inductance`; `compResistance` (companion resistor value);
  `current` (inductor current, converged); `curSourceValue` (companion
  current source value that gets re-stamped each step).
- **Contract:**
  - `setup(ic, cr, f)` — record inductance, initial current, flags
  - `reset()` / `resetTo(c)` — zero or force a particular current
  - `stamp(n0, n1)` — set `compResistance = 2L/Δt` (trapz) or
    `L/Δt` (back Euler); stamp the resistor and mark both nodes as
    right-side (so simulator will push current-source updates later).
  - `startIteration(voltdiff)` — update `curSourceValue = v/R + I`
    (trapezoidal) or `I` (backward Euler).
  - `doStep(voltdiff)` — `simulator.stampCurrentSource(n0, n1, curSourceValue)`.
  - `calculateCurrent(voltdiff)` — recompute `I = v/R + curSourceValue`
    (guards against `compResistance == 0` for the pre-stamp edge case).
- **Rationale:** kept out of `CircuitElm` hierarchy to allow an element
  to own multiple inductor instances (e.g. `TransformerElm` owns two
  — primary + secondary).

## Public Contracts

Below is the lifecycle contract, ordered by simulator call sequence. File
references apply to the **default** implementation on `CircuitElm`; each
line also lists the concrete override sites inside these 11 files.

### Simulation lifecycle

#### Analysis (once per circuit topology change)

- `allocNodes()` — `CircuitElm.java:373`. Called from constructors and
  whenever post/internal count changes (e.g. `CustomLogicElm.setupPins`).
- `setNode(int p, int n)` — `CircuitElm.java:869`
  (CompositeElm overrides to forward into `compNodeList` — `CompositeElm.java:392`).
- `setVoltageSource(int n, int v)` — `CircuitElm.java:876`
  (ChipElm override: `ChipElm.java:306`; CompositeElm override:
  `CompositeElm.java:432`).
- `stamp()` — `CircuitElm.java:578` (no-op default).
  ChipElm: stamp voltage sources for outputs (`ChipElm.java:318`).
  CompositeElm: forward to all children (`CompositeElm.java:368`).
  CustomLogicElm: tri-state handling (`CustomLogicElm.java:126`).
  Inductor (helper): `stampResistor` + `stampRightSide` (`Inductor.java:65`).
- `nonLinear()` — `CircuitElm.java:889`
  (CompositeElm: any-child-nonlinear — `CompositeElm.java:199`;
  CustomLogicElm: `hasTriState()` — `CustomLogicElm.java:116`;
  Inductor: always false).

#### Time-step loop (once per Δt)

- `startIteration()` — `CircuitElm.java:592`
  (CompositeElm forwards — `CompositeElm.java:376`;
  Inductor `startIteration(voltdiff)` — `Inductor.java:86`).
- Newton iteration (nonlinear only):
  - `doStep()` — `CircuitElm.java:582`
    (ChipElm: sample inputs → `execute()` → update voltage sources —
    `ChipElm.java:335`;
    CompositeElm forwards — `CompositeElm.java:381`;
    CustomLogicElm: tri-state resistors — `CustomLogicElm.java:141`;
    Inductor `doStep(voltdiff)` — `Inductor.java:102`).
- Matrix solve (done by simulator).
- `setNodeVoltage(int n, double c)` — `CircuitElm.java:606`
  (CompositeElm propagates — `CompositeElm.java:405`).
  Invokes `calculateCurrent()` inside (default no-op).
- `setCurrent(int vn, double c)` — `CircuitElm.java:563`
  (ChipElm routes by `voltSource` match — `ChipElm.java:407`;
  CompositeElm routes by `vsNode` match — `CompositeElm.java:441`).
- `stepFinished()` — `CircuitElm.java:1326`
  (CompositeElm forwards — `CompositeElm.java:386`).

#### Reset (user presses Reset button or loads a circuit)

- `reset()` — `CircuitElm.java:551`: clears all NodeState voltages and
  `curcount`.
  ChipElm: also clears pin values + `lastClock` (`ChipElm.java:352`).
  CompositeElm: forwards to children (`CompositeElm.java:334`).
  Inductor: `resetTo(0)` or `resetTo(c)` (`Inductor.java:54`).

### Topology contract

| Method | Default | Called by |
|---|---|---|
| `getPostCount()` | 2 | simulator during analysis (CirSim.analyzeCircuit) |
| `getPost(int n)` | point1/point2 | renderer, editor hit-test |
| `getConnectedPost()` | point2 | wire-closure optimization |
| `getInternalNodeCount()` | 0 | node allocation |
| `getVoltageSourceCount()` | 0 | simulator assigns vs indices |
| `getConnectionNode(int n)` | `getNode(n)` | BFS for connectivity |
| `getConnection(int n1, int n2)` | true | connectivity analysis |
| `hasGroundConnection(int n1)` | false | ground-reference analysis |
| `isWireEquivalent()` | false | optimization |
| `isRemovableWire()` | false | optimization |
| `isIdealCapacitor()` | false | matrix optimization |

### Drawing contract

- `draw(Graphics g)` — `CircuitElm.java:558` (no-op default; every
  visible element overrides). `Graphics` is the GWT canvas façade from
  `client/Graphics.java`.
- `drawPosts(Graphics g)` — `CircuitElm.java:756` — default draws
  filled 7×7 ovals at each post location, but only when the element is
  being dragged or needs highlight. In practice `CircuitRenderer`
  handles post drawing for the main pass.
- `drawValues(Graphics g, String s, double hs)` — `CircuitElm.java:974`:
  draws component value labels alongside the element body, with
  `RailElm`/`SweepElm`/`VoltageElm` positioning special-cased.
- `drawLabeledNode(Graphics, String, Point, Point)` — draws the
  labeled-node identifier with optional line-over for `/NAME`.
- `draw2Leads(Graphics g)` — convenience helper that colors each lead
  segment by its node voltage.
- `doDots(Graphics g)` — current-dot animation via `updateDotCount`
  and `drawDots`.
- `drawCoil(Graphics, int hs, Point p1, Point p2, double v1, double v2)` —
  coil glyph used by inductors/transformers.
- `drawCenteredText(Graphics, String, int, int, boolean)`,
  `drawPost(Graphics, Point)`, `setVoltageColor(...)`,
  `setPowerColor(...)`, `setConductanceColor(...)`,
  `getVoltageColor(...)`, `getSchmittPolygon(...)` —
  rendering primitives shared across subclasses.

### Editing contract

- `getEditInfo(int n)` — produces the n-th row of the edit dialog
  (see `dialog/EditInfo`). Returning `null` terminates the row list.
- `setEditValue(int n, EditInfo ei)` — pulls the user's value back
  into the element. Called by `EditDialog` on OK / apply.
- `getInfo(String[] arr)` — fills an up-to-20-slot string array that
  populates the lower-right element-info panel.
- `getShortcut()` — ASCII code of the creation keyboard shortcut
  (0 = none). For example `SwitchElm.getShortcut() == 's'`.
- **Bidirectional element ↔ dialog coupling:** CircuitElm imports
  `com.lushprojects.circuitjs1.client.dialog.{EditInfo, Editable}`
  directly (line 44-45), which is the dominant element→dialog edge
  weight (104 imports) in the project's dependency graph.

### Persistence contract

#### Legacy text format

- `getDumpType()` — required; returns the element's type code (char
  < 127 → single-char token; else numeric token).
- `dump()` — default emits `type x1 y1 x2 y2 flags` via `dumpValues`.
  Subclasses extend by appending tokens (e.g. `SwitchElm.dump()` adds
  position + momentary + optional label).
- Type codes observed in these 11 files:
  - `SwitchElm` → `'s'` (115)
  - `CustomLogicElm` → `208`
  - `CustomCompositeElm` → `410`
- `dumpElm(CircuitElm)` — static; appends ` # description` suffix when
  a description is present.
- `dumpValues(Object...)` / `dumpArray(Object[])` / `dumpValue(int|double|boolean)` —
  token-building helpers. `dumpValue(double)` switches between decimal
  and scientific notation based on magnitude.
- `escape(String)` / `unescape(String)` — delegate to
  `CustomLogicModel.escape/unescape` for composite nesting.
- Re-construction happens in `CircuitElmCreator` (outside this
  module) which dispatches on the dump type to a per-type
  constructor signature `(CircuitDocument, int xa, int ya, int xb,
  int yb, int f, StringTokenizer st)`.

#### JSON format

- `getJsonTypeName()` — element-type key in JSON (defaults to class
  name minus `Elm`, e.g. `ResistorElm` → `"Resistor"`).
- `getJsonProperties()` / `applyJsonProperties(Map)` — symmetric
  property bag. Values may be numeric or unit-prefixed strings
  (`"10 kOhm"`); `getJsonDouble` parses both via
  `io.json.UnitParser`.
- `getJsonState()` / `applyJsonState(Map)` — simulation state
  (per-pin voltage + current, plus per-element extras). CompositeElm
  adds a `subElements` list; ChipElm adds `outputs` + `last_clock`.
- `getJsonPinNames()` / `getJsonPinPosition(int)` (both deprecated in
  favor of `getPins()`/`Pin.getPosition()`) — pin name + absolute
  coordinate for JSON export.
- `getJsonStartPoint()` / `getJsonEndPoint()` — emit `_startpoint` /
  `_endpoint` only when the element's geometry does not match pin 0 /
  pin 1 (e.g. `OpAmpElm` has a `_startpoint` because its mid-body
  anchor is not at a pin).
- `applyJsonPinPositions(Map)` — inverse of `getJsonPinPosition`;
  detects `_startpoint`/`_endpoint` sentinels.
- `finalizeJsonImport()` — post-import hook (default:
  `initBoundingBox() + setPoints()`).

## Validation Rules

- `nodeStates` is lazily allocated via `getNodeState(i)`; index out of
  bounds triggers `allocNodes()` (never throws IOOBE). Line 386-391.
- `getDumpType()` throws `IllegalStateException` unless overridden
  — the type system's enforcement mechanism for "must implement
  persistence". Line 320-327.
- `ChipElm.stamp()` logs `"voltage source count does not match number
  of outputs"` when `getVoltageSourceCount() != sum(pins[i].output)` —
  runtime consistency check, not a hard failure. Line 329.
- `ChipElm.writeOutput(n, v)` logs `"pin n is not an output!"` if a
  non-output pin is written. Line 383.
- `ChipElm.setVoltageSource(j, vs)` prints `"setVoltageSource failed
  for <elm>"` when `j` exceeds the output count. Line 315.
- `CompositeElm.loadComposite()` throws `IllegalArgumentException`
  when an external node listed in `externalNodes[]` is not found in
  `compNodeHash`. Line 153.
- `Inductor.calculateCurrent()` guards against `compResistance == 0`
  (pre-stamp state) to avoid infinite-current propagation. Line 97.
- `ElmGeometry.movePoint()` refuses to collapse the element to zero
  length — if the move would set `x1==x2 && y1==y2`, the move is
  reverted. Line 264-269.
- `CircuitElm.allowMove(int, int)` rejects moves that would place the
  element exactly on top of an existing one (checked by walking
  `simulator().elmList`). Line 718.
- `CircuitElm.creationFailed()` returns true when the drag ends with
  zero size — caller deletes the element. Line 699.
- `getHandleGrabbedClose(...)` returns `-1` if the element is too
  small to accept handle grabs. Line 830.

## State Transitions

Canonical element lifecycle:

```
          construct
             │          ┌─────────────────┐
             ▼          ▼                 │
      ┌──────────────┐  │                 │
      │ constructed  │  │  user drag      │
      └──────┬───────┘  │                 │
             │          │                 │
             │ drag     │                 │
             ▼          │                 │
      ┌──────────────┐  │                 │
      │  placed      │─ user edit ────────┘
      └──────┬───────┘
             │
             │ CirSim.analyzeCircuit
             ▼
      ┌──────────────────────────┐
      │ analyzed                 │
      │  allocNodes → setNode(*) │
      │  setVoltageSource(*)     │
      │  stamp()                 │
      └───────────┬──────────────┘
                  │
                  │  per timestep:
                  ▼
      ┌──────────────────────────┐    nonLinear?
      │ startIteration()         │─────────┐
      └───────────┬──────────────┘         │
                  │                        │  Newton loop:
                  ▼                        │  doStep → solve →
              (solve MNA)                  │  setNodeVoltage
                  │                        │       │
                  ▼                        │       ▼
      ┌──────────────────────────┐         │  calculateCurrent
      │ setNodeVoltage(*)        │◄────────┘       │
      │ setCurrent(*)            │                 │
      │ calculateCurrent()       │◄────────────────┘
      └───────────┬──────────────┘
                  │
                  ▼
      ┌──────────────────────────┐
      │ stepFinished()           │
      └───────────┬──────────────┘
                  │
                  │ (next step or reset)
                  ▼
               reset()
                  │
                  ▼
             analyzed (re-enter)

      delete()  ──► removed from document
```

Ordering guarantees observed in the base class:

- `setNode(p, n)` is always called **before** `stamp()`.
- `setVoltageSource(n, v)` is called **before** `stamp()` for elements
  that report `getVoltageSourceCount() > 0`.
- `stamp()` is called **once per topology**; `doStep()` may be called
  multiple times per timestep (Newton iterations).
- `setCurrent(vn, c)` is called **after** the matrix solve; the
  `vn` argument is the same value previously passed to
  `setVoltageSource(n, vn)`.
- `setNodeVoltage(n, c)` triggers `calculateCurrent()` synchronously
  (CompositeElm's override propagates into children).
- `setNodeVoltageDirect(n, c)` (line 617) is the **UI/state-loading**
  variant that skips `calculateCurrent()` — used by JSON import and
  some drawing paths.
- `stepFinished()` runs last, after all nodes/currents are settled.
- `reset()` may be called any time the user clicks Reset or loads a
  new circuit.

## Integration Points

### Depends on

- **root-utils (Layer 0, same package `client/`):**
  - `Point`, `Rectangle`, `Polygon`, `IntPair` — geometry
  - `Graphics`, `Color`, `Font` — rendering
  - `StringTokenizer` — dump parsing
  - `Random` (GWT) — dot animation
  - `com.google.gwt.*` — canvas, i18n, core, user
- **util (Layer 0):** `util.Locale` (via `BaseCircuitElm.getSIPrefix`).
- **client/ root-level (Layer 3 cross-layer — violates strict layering
  because elements need everything from the simulator core):**
  - `CircuitDocument` — owner/context (simulator, editor, renderer)
  - `CircuitSimulator` — `stampResistor`, `stampVoltageSource`,
    `stampCurrentSource`, `updateVoltageSource`, `stampRightSide`,
    `stampNonLinear`, `timeStep`
  - `CircuitEditor` — `snapGrid`, `dragElm`, `mouseMode`, `plotYElm`
  - `CircuitElmCreator` — used by `CompositeElm.loadComposite` to
    rebuild children
  - `CircuitNode`, `CircuitNodeLink` — internal sub-netlist
  - `CirSim` — `console` (error logging)
  - `CustomLogicModel`, `CustomCompositeModel` — named model registries
  - `ColorSettings`, `DisplaySettings` — color/voltage display config
  - `Scope` — constants `VAL_CURRENT`, `VAL_POWER`, `UNITS_A`,
    `UNITS_W`, `UNITS_V`
  - `MouseMode` — DRAG_ROW / DRAG_COLUMN enum
  - `ExtListEntry`, `Checkbox`, `Choice` — (only in
    CustomCompositeElm/SwitchElm)
- **dialog (Layer 2, same SCC):**
  - `dialog.Editable` (marker interface implemented by `CircuitElm`)
  - `dialog.EditInfo` — edit-dialog row carrier
- **io.json (Layer 2):** `io.json.UnitParser.parse(String)` via
  `CircuitElm.getJsonDouble` (line 1902).

### Used by

- **Every concrete `*Elm`** in `client/element/` (~135 classes) — the
  whole element catalog derives from `CircuitElm`,
  `ChipElm`, `CompositeElm`, `SwitchElm`, or `GraphicElm`.
- **`client/element/waveform/`** — waveforms reference `CircuitElm`
  for context (voltage-source elements own `Waveform` instances and
  the waveforms reach back for `getNodeVoltage`, etc.).
- **Simulator core** (`CirSim`, `CircuitSimulator`, `CircuitMath`,
  `CircuitLoader`, `CircuitDocument`) — iterates all elements through
  the stamp/doStep/setCurrent/setNodeVoltage contract.
- **Editor** (`CircuitEditor`, `CircuitRenderer`) — renders all
  elements via `draw`/`drawPosts`/`drawHandles`; hit-tests via
  `getMouseDistance`/`getBoundingBox`; drives edit via `getEditInfo`.
- **I/O framework** (`io/`, `io/text/`, `io/json/`) —
  `CircuitElementFactory` creates via dump-type dispatch;
  `TextCircuitExporter`/`Importer` call `dump()` and
  `CircuitElmCreator`; `JsonCircuitExporter`/`Importer` call
  `getJsonTypeName/Properties/State/PinNames/PinPosition` and
  `applyJson*`.
- **Dialog subsystem** (`dialog/EditDialog`, etc.) — wraps
  `Editable.getEditInfo(n)` / `setEditValue(n, ei)` into a UI form.
- **JS bridge** — `CircuitElm.addJSMethods()` (line 1372) and
  `getInfoJS`, `getVoltageJS`, `setPropertyJS` expose the contract
  via JSNI.

### External deps

- GWT: `Context2d`, `CanvasGradient`, `JsArrayString`,
  `JavaScriptObject`, `NumberFormat`, `Random`, `Window`, `Button`.
- JDK: `java.util.{ArrayList, HashMap, LinkedHashMap, Vector, Map,
  Math}`.
- No JDK threading, no reflection.

## Existing Documentation

- **INTERNALS.md** — project-level overview of MNA solver, companion
  models for L/C, nonlinear Newton iteration, and the
  `stamp()`/`doStep()`/`nonLinear()` contract. Required reading for
  anyone writing a new element.
- **docs/elements.md** — catalog of all ~135 visible element classes
  grouped by menu category (Basic, Passive, Active, Logic, Chips,
  Subcircuits, etc.). Cross-reference the class names here against
  the override points above.
- **Javadoc inside these 11 files:**
  - `BaseCircuitElm` — every static helper has javadoc
  - `CircuitElm` — javadoc on `NodeState`, `Pin`, `setDescription`,
    `setElementId`/`getElementId`, `getJsonTypeName`, JSON state
    helpers, `setNodeVoltageDirect`, `adjustDerivedGeometry`,
    `ensureGeometryUpdated`, `setCircuitDocument`.
  - `ElmGeometry` — file-level "HARD MODE: this class is the single
    source of truth for element geometry. Do not read/write geometry
    via CircuitElm fields." (line 6-10) + per-method javadoc.
  - `Inductor` — inline comment on companion-model trapezoidal vs
    backward-Euler integration (line 66-70).
  - `CompositeElm` — block comment at top-of-file describing the
    composite-element pattern and subclass contract (line 18-29).
  - `ChipElm`, `SwitchElm`, `GraphicElm`, `CustomCompositeElm`,
    `CustomCompositeChipElm`, `CustomLogicElm` — comments are sparse
    but present at decisive decision points (flag bits, pin-prefix
    parsing in `Pin.fixName`).

## Issues / Questions

1. **`getDumpType()` is nominally-abstract-but-actually-concrete.** The
   GWT-compiler workaround (throws `IllegalStateException` if called
   on the base) defeats the type system's usual "subclass must
   implement abstract method" check. Any subclass that forgets to
   override will compile but fail at dump time. (`CircuitElm.java:320-327`)
2. **Bidirectional `element ↔ dialog` coupling.** `CircuitElm` imports
   `dialog.EditInfo` / `dialog.Editable` directly (line 44-45). The
   dependency graph documents this as the strongest coupling in the
   codebase (104+16 edges). The `Editable` marker interface lives in
   `dialog/` but is implemented by every element — a cleaner layering
   would move `Editable` + `EditInfo` into `element/` (or a neutral
   `contract/` package) and invert the edge.
3. **`CircuitElm` is a god-class (1955 LOC).** It mixes lifecycle,
   geometry (delegated to `ElmGeometry`), drawing primitives, edit-dialog
   wiring, JSON export, JSON import, JS/JSNI bridge, Pin model, and
   ID management. The `ElmGeometry` extraction is a step in the right
   direction; further extraction candidates: `ElmPersistence` (dump +
   JSON), `ElmRendering` (all draw helpers + color helpers), `ElmPins`
   (Pin inner class + `getPins`/`getPin`/`getPinByName`).
4. **`voltSource` field is single-source-assumption.** The default
   `setVoltageSource(n, v)` overwrites a single `public int voltSource`
   — works only for 0- or 1-source elements. Multi-source elements
   (`ChipElm`, `OpAmpElm`, `TransformerElm`, `CompositeElm`) must
   override. Forgetting the override is a silent bug.
5. **`BaseCircuitElm.formatNumber` vs. util.** The `util/PerfMonitor`
   → `element/BaseCircuitElm` edge exists solely because
   `formatNumber` lives on `BaseCircuitElm`. Moving the formatting
   helpers to `util/` (proposed as issue #1 in `util.md`) would cut
   the cross-layer edge.
6. **`Inductor` is not a CircuitElm but sits in the package.** As a
   pure helper it violates the "files in element/ are elements"
   convention. Candidates: rename to `InductorHelper` / relocate to
   a neutral `physics/` sub-package. Same concern applies to
   `ElmGeometry` (though that one is tightly coupled to CircuitElm).
7. **`ChipElm.setVoltageSource` prints to stdout** (`System.out.println`
   at line 315) — would be logged to GWT console via `CirSim.console`
   in every other similar error path. Inconsistent.
8. **Dump format drift between `dump()` and `dumpWithMask`**. The
   regex `[A-Za-z0-9]+ 0 0 0 0 ` (CompositeElm.java:214, 232) assumes
   the child's dump type token is alphanumeric and is followed by
   four `0`s. Type tokens that include punctuation (e.g. `'s'`,
   `' '`) may fall through; while `dumpType < 127 → char` already
   enforces ASCII, non-alphanumeric chars (like `'s'` or `' '`)
   still match `[A-Za-z0-9]+` incorrectly (they do not), so this is
   a potential regression path when new element types are added with
   non-alphanumeric single-char tokens.
9. **`CustomCompositeElm.getDumpType()` returns 410 and
   `CustomLogicElm.getDumpType()` returns 208** as magic numbers with
   no central registry. A `DumpTypes` constants file would be
   clearer and prevent collisions when new element types are added.
10. **`CustomCompositeElm.updateModels()` silently returns if the
    model is not in the registry** (`CustomCompositeElm.java:180`) —
    the element ends up half-initialized. No user-visible error.
11. **`ElmGeometry.calcLeads(int len)`** creates new `Point` objects
    lazily to avoid aliasing with `point1`/`point2`, but the check
    `lead1 == null || lead1 == p1` (line 345) uses reference
    equality, which is correct but brittle if anything else assigns
    `lead1` externally.
12. **`CircuitElm.getCurrentIntoNode(int n)` special-cases `getPostCount() == 2`**
    (line 1330-1336) — two-port elements return `-current` for post 0
    and `+current` otherwise. Multi-port elements must override;
    `ChipElm` does (`pins[n].current`), `CompositeElm` does (sums
    child currents), but any new multi-post element that forgets to
    override will report wrong currents into all non-zero posts.
13. **`CircuitElm.allowMove` uses the global `simulator().elmList`**
    (line 724) — overlap detection is O(N) per move. Acceptable at
    current scales (hundreds of elements) but will not scale.
14. **Deprecated methods still in use:** `getJsonPinNames()`,
    `getJsonPinPosition(int)`. Replacement (`getPins()` +
    `Pin.getPosition()`) is present but not fully adopted across
    concrete elements.

## Suggested Concept Boundaries

A **single `element-contract` concept** covering all 11 files is the
right granularity for this onboard pipeline. Rationale:

- All 11 files collaborate around the same lifecycle — splitting them
  would force each concept to restate the simulation ordering
  guarantees.
- The override-point table (above) is the single most valuable output
  for subclass authors; it is meaningful only when all 11 classes are
  in view.
- `ElmGeometry` and `Inductor` are helpers owned-by-`CircuitElm`; they
  have no independent concept.
- The composite branch (`CompositeElm` / `CustomCompositeElm` /
  `CustomCompositeChipElm`) is a variation on the contract, not a
  different contract.

If finer granularity is required for downstream concept generation, the
clean cuts are:

1. **element-contract** (core: `CircuitElm`, `BaseCircuitElm`,
   `ElmGeometry`) — the contract every subclass must honor.
2. **element-composite** (`CompositeElm`, `CustomCompositeElm`,
   `CustomCompositeChipElm`) — subcircuit composition pattern.
3. **element-chip** (`ChipElm`, `CustomLogicElm`) — the multi-pin IC
   base + its user-programmable extension. Note that
   `CustomCompositeChipElm` belongs here if split, not with
   `CompositeElm`, because it is structurally a chip.
4. **element-variants** (`SwitchElm`, `GraphicElm`, `Inductor`) — base
   variants that are neither chips nor composites.

The dependency graph does not require this split — all 11 files are
within the same SCC.
