# Module Analysis: domain-core / element / cat-switches

> **Path:** src/main/java/com/lushprojects/circuitjs1/client/element/ (8 concrete switch files)
> **Base:** `SwitchElm` — analyzed in `domain-core__element-base.md`
> **Layer:** 2 (SCC-A)
> **Analyzed:** 2026-04-18
> **Files:** 8 concrete classes; 0 test files

## Purpose

The `switches` category extends the abstract `SwitchElm` SPST base with
concrete multi-pole / multi-throw variants, momentary pushbuttons,
make-before-break (MBB) topology, a crossbar, a latching/trip motor
protection relay, and two **voltage-controlled analog** switches
(`AnalogSwitchElm` / `AnalogSwitch2Elm`) that do **not** extend `SwitchElm`
at all.

Two stamping strategies coexist in this category:

1. **Mechanical / wire-equivalent switches** (`SwitchElm` family) — the
   simulator collapses closed poles into single nodes during wire-closure
   analysis (`isWireEquivalent() == true` when closed) **or** stamps a
   **0 V voltage source** between the connected post pair. Open poles are
   simply absent from the stamp — no resistor, no "big R".
2. **Analog (voltage-controlled) switches** (`AnalogSwitchElm` /
   `AnalogSwitch2Elm`) — stamp a resistor `r_on` or `r_off` (default 20 Ω
   vs 1e10 Ω) every step, with a `threshold` comparison on the control
   pin. `nonLinear() == true` so the matrix is re-stamped each iteration
   and the open/closed decision can flip mid-Newton.

`MotorProtectionSwitchElm` is a third, mixed case: it is a 3-phase fuse
that **latches** into a blown state when `I²t` heat exceeds a threshold
and drives **remote** `RelayContactElm`s via the `label`-linked mechanism.

## Per-element catalog

| Element | Extends | Poles | Throws | `posCount` / state | V-sources | `getPostCount()` | Dump-type | Shortcut | File:lines |
|---|---|---|---|---|---|---|---|---|---|
| `SwitchElm` (base) | `CircuitElm` | 1 | 1 (SPST) | 2 (0=closed, 1=open) | 0 | 2 | `'s'` (115) | `'s'` | `SwitchElm.java:33-349` |
| `Switch2Elm` | `SwitchElm` | 1 | `throwCount` (2..10; default 2 → SPDT) | `throwCount` (or 3 w/ center-off legacy) | 1 (0 when center-off) | `1 + throwCount` | `'S'` (83) | `'S'` | `Switch2Elm.java:32-280` |
| `DPDTSwitchElm` | `SwitchElm` | `poleCount` (2..10; default 2 → DPDT) | 2 | 2 | `poleCount` | `3 * poleCount` | 429 | 0 (none) | `DPDTSwitchElm.java:32-297` |
| `PushSwitchElm` | `SwitchElm` | 1 | 1 | 2 | 0 (inherits) | 2 | (shares SwitchElm dump via `getDumpClass`) | 0 | `PushSwitchElm.java:24-41` |
| `MBBSwitchElm` | `SwitchElm` | 1 | 2 | **4** (0=A, 1=A+B, 2=B, 3=B+A) | 1 or 2 (dynamic: `both`→2) | 3 | 416 | 0 | `MBBSwitchElm.java:31-246` |
| `CrossSwitchElm` | `SwitchElm` | 2 (fixed, crossed) | 2 (straight vs swap) | 2 (0=straight, 1=cross) | 2 | 4 | 430 | 0 | `CrossSwitchElm.java:31-278` |
| `MotorProtectionSwitchElm` | `CircuitElm` (**not** SwitchElm) | 3 (3-phase) | 1 | latch: `blown` bool + per-phase `heats[]` | 0 | 6 | 428 | 0 | `MotorProtectionSwitchElm.java:36-358` |
| `AnalogSwitchElm` | `CircuitElm` | 1 | 1 | `open` bool (from Vc vs threshold) | 0 | 3 (in, out, **ctl**) | 159 | 0 | `AnalogSwitchElm.java:31-282` |
| `AnalogSwitch2Elm` | `AnalogSwitchElm` | 1 | 2 (SPDT) | `open` bool | 0 | 4 (common, out1, out2, **ctl**) | 160 | 0 | `AnalogSwitch2Elm.java:29-168` |

### Behavior notes (per element)

- **`Switch2Elm`** — `throwCount` configurable (2..10) via EditInfo row 2
  (`Switch2Elm.java:204`). `FLAG_CENTER_OFF = 1` kept only for backwards
  compat and only when `throwCount == 2` (`Switch2Elm.java:234-236`); when
  active it adds a third off-position where no voltage source is stamped
  (`getVoltageSourceCount()` returns 0 — `Switch2Elm.java:153-155`).
  `link` integer (0 = no link; 1..100) ganged-toggle across document
  (`Switch2Elm.java:157-170`).
- **`DPDTSwitchElm`** — `poleCount` configurable (2..10) via EditInfo
  row 0 (`DPDTSwitchElm.java:225-226`); nodes laid out in triples
  (pole, throwA, throwB) so `getPost(n)` uses `n/3` + `n%3`
  (`DPDTSwitchElm.java:175-181`). `flip()` helper nudges endpoints by
  `3 * openhs` during X/Y flipping so the pole column stays on the grid
  (`DPDTSwitchElm.java:248-258`).
- **`PushSwitchElm`** — literally a 17-line subclass that only overrides
  the constructor to pass `mm = true` (momentary) and `getDumpClass()`
  to reuse SwitchElm's persistence path (`PushSwitchElm.java:25-41`).
  Returns SPST behavior from the base; momentary release is handled by
  `SwitchElm.mouseUp()` calling `toggle()` (`SwitchElm.java:196-199`).
- **`MBBSwitchElm`** — rotates through 4 positions in
  {throw1, both, throw2, both} (`MBBSwitchElm.java:87`), matching real
  make-before-break: position 1 and 3 connect **both** throws before the
  wiper fully leaves. Stamps 2 voltage sources when `both == true`
  (`MBBSwitchElm.java:163-167`) and reports `getVoltageSourceCount()`
  dynamically (`MBBSwitchElm.java:171-174`). `link` group toggles peers
  (`MBBSwitchElm.java:176-189`).
- **`CrossSwitchElm`** — **2×2 crossbar**, not a general NxM grid. Two
  poles, two throws each; position 0 wires pole[i]→throw[i] (straight),
  position 1 wires pole[0]→throw[1] and pole[1]→throw[0] (crossed)
  (`CrossSwitchElm.java:214-223`). Uses 2 voltage sources, one per pole
  (`getVoltageSourceCount() == 2`, `CrossSwitchElm.java:225-227`).
  Per-pole `currents[]` and `curcounts[]` (not inherited scalar).
- **`MotorProtectionSwitchElm`** — three independent phases
  (L1/L2/L3), each a resistor of `resistance` (default 0.0613 Ω) with
  per-phase `heats[]` integrator. Every `startIteration()` accumulates
  `heat += i²·Δt`, dissipates `Δt·i2t/3`, and latches `blown = true`
  when any phase exceeds `i2t` (`MotorProtectionSwitchElm.java:232-254`).
  Blown state stamps `blownResistance = 1e9 Ω` instead
  (`MotorProtectionSwitchElm.java:269-273`). On blow/reset it walks the
  document and calls `RelayContactElm.setPosition(...)` on every relay
  contact sharing its `label` (`setSwitchPositions` —
  `MotorProtectionSwitchElm.java:256-267`). `nonLinear() == true`
  because the blown/not-blown decision is state-dependent.
- **`AnalogSwitchElm`** — 3 posts: `in` (0), `out` (1), `control` (2).
  `open = (Vc < threshold)` optionally inverted by `FLAG_INVERT`
  (`AnalogSwitchElm.java:180-182`). `doStep()` stamps a single resistor
  `getNode(0)↔getNode(1)` with value `r_off` (default 1e10) or `r_on`
  (default 20) (`AnalogSwitchElm.java:187-189`). Optional pulldown
  `FLAG_PULLDOWN` stamps `r_off` from each data pin to ground so the
  open-switch case does not leave floating nodes
  (`AnalogSwitchElm.java:172-176`). `nonLinear() == true`
  (`AnalogSwitchElm.java:160-162`). `getConnection(0,2)` and
  `getConnection(1,2)` return **false** — control pin is isolated from
  the data path (`AnalogSwitchElm.java:211-215`).
- **`AnalogSwitch2Elm`** — 4 posts: common (0), out1 (1), out2 (2),
  control (3); SPDT analog variant. Stamps **both** resistor paths
  simultaneously — `r_on` to the active throw, `r_off` to the inactive
  (unless pulldown is enabled, in which case only the active path is
  stamped and the pulldowns ground each throw) —
  `AnalogSwitch2Elm.java:117-131`.

## Manual vs analog (voltage-controlled) switches

| Axis | Manual (`SwitchElm` family) | Analog (`AnalogSwitch*Elm`) |
|---|---|---|
| Trigger | Mouse click (editor `doSwitch` → `toggle()`) or `mouseUp` for momentary | Voltage on control pin vs `threshold` |
| State variable | `int position` (discrete) | `boolean open` (continuous Vc gate) |
| Stamp | 0 V voltage source (if wire-equivalent) **or** wire-closure collapse | Resistor (`r_on` / `r_off`) every step |
| `nonLinear()` | false | **true** (re-decide each Newton iteration) |
| `isWireEquivalent()` | true for `Switch2Elm`, `DPDTSwitchElm`, `CrossSwitchElm`, `MBBSwitchElm`; base `SwitchElm` only when closed | false |
| `isRemovableWire()` | only base `SwitchElm` when closed; multi-pole variants return **false** (optimization too complex — #646 comment at `Switch2Elm.java:183`, `CrossSwitchElm.java:241`, `DPDTSwitchElm.java:213`, `MBBSwitchElm.java:200`) | false |
| Control pin | none | Post index 2 (`AnalogSwitchElm`) or 3 (`AnalogSwitch2Elm`) |
| Floating-node mitigation | n/a (open switch → no matrix contribution) | `FLAG_PULLDOWN` stamps `r_off` to ground (`AnalogSwitchElm.java:172-176`, `AnalogSwitch2Elm.java:111-114`) |
| Voltage-source count | 0..poleCount depending on topology | 0 (pure resistive) |

## Stamping strategy

Three distinct patterns emerge — none of them use the classic
"swap 0 Ω ↔ big R" approach across the board:

### Pattern A — wire-closure + voltage-source-zero (SwitchElm family)

- **Base `SwitchElm`** (`SwitchElm.java:234-244`): contributes nothing
  to the stamp. Instead it reports `isWireEquivalent() == true` and
  `getConnection(n1,n2) == true` **when closed**, so the simulator's
  wire-closure pass merges the two posts into a single global node. When
  open, it reports **false** for both, and the simulator treats the
  posts as independent nodes with no element between them (a gap — the
  classic "infinite resistance" without actually stamping a large
  resistor).
- **Multi-pole variants** (`Switch2Elm`, `DPDTSwitchElm`,
  `CrossSwitchElm`, `MBBSwitchElm`): each pole stamps a **0 V voltage
  source** between `common` and the selected throw
  (`Switch2Elm.java:147-151`, `DPDTSwitchElm.java:194-198`,
  `CrossSwitchElm.java:214-223`, `MBBSwitchElm.java:160-167`). This
  forces the two nodes to equal voltage without collapsing them
  topologically — necessary because the element owns `voltageSources[]`
  array indices allocated by the simulator and because `isRemovableWire`
  is forced to false.
- **Why voltage source, not 0 Ω resistor?** A 0 Ω resistor is ill-
  conditioned for MNA; a voltage source enforces `V_a - V_b = 0` via a
  Lagrange-multiplier row and carries the current cleanly as the
  voltage-source current, which is how `setCurrent(vn, c)` routes it
  back to the per-pole `currents[]` arrays (`CrossSwitchElm.java:183-188`,
  `DPDTSwitchElm.java:163-168`, `MBBSwitchElm.java:142-148`).

### Pattern B — resistor pair (AnalogSwitchElm family)

- Every step `doStep()` stamps a single resistor between data posts,
  value selected by the control-pin threshold crossing
  (`AnalogSwitchElm.java:186-189`). `r_on = 20 Ω` (default),
  `r_off = 1e10 Ω` (default). No topology rewrite.
- `nonLinear()` forces the matrix to be re-stamped every Newton
  iteration so the open/closed flip can happen mid-solve.
- `AnalogSwitch2Elm` stamps **two** resistors simultaneously (one
  active, one inactive) so both throw branches are always represented
  — avoids the topology-rewrite that a true SPDT would need
  (`AnalogSwitch2Elm.java:122-130`).

### Pattern C — latching resistor (MotorProtectionSwitchElm)

- Three resistors per phase (one per L_n+ / L_n- pair), each stamped
  as either `resistance` (~60 mΩ) or `blownResistance = 1e9 Ω`
  (`MotorProtectionSwitchElm.java:269-273`). No topology rewrite; just
  resistor value swap driven by the latched `blown` bool.
- `nonLinear() == true` because `blown` can flip mid-simulation from
  heat accumulation (`MotorProtectionSwitchElm.java:224-226`).

### Summary

- There is **no** naive "swap 0 Ω / big R" strategy anywhere. The
  engineering trade-offs made:
  - Mechanical switches with a single wire-like post pair → let the
    wire-closure optimizer collapse the node graph.
  - Mechanical multi-pole switches → stamp 0 V voltage sources so
    per-pole currents can be recovered.
  - Voltage-controlled analog switches → stamp a finite-but-large
    `r_off` and accept the ill-conditioning (compensated by
    `FLAG_PULLDOWN` where floating nets are a concern).
  - Fuse/latch elements → stamp a large resistor (`1e9`) while blown.

## User input wiring (how click events reach the switch)

Click-to-toggle flow (`CircuitEditor.java`):

1. `onMouseDown` captures mouse → grid coords via `renderer().inverseTransformX/Y` (`CircuitEditor.java:664-665`).
2. `doSwitch(gridX, gridY)` is called first (`CircuitEditor.java:666-669`) — if it returns true, no further editor action runs and `didSwitch = true`.
3. `doSwitch(...)` (`CircuitEditor.java:874-890`):
   - Requires `mouseElm instanceof SwitchElm` — **only** `SwitchElm`
     subclasses qualify. `AnalogSwitchElm` / `AnalogSwitch2Elm` /
     `MotorProtectionSwitchElm` are **not** click-toggleable (they are
     voltage-controlled or latching).
   - Hit-tests `switchElm.getSwitchRect().contains(x, y)` — the rect
     is element-specific (`SwitchElm.java:183-189`, override in
     `Switch2Elm.java:129-132`, `DPDTSwitchElm.java:170-173`,
     `CrossSwitchElm.java:190-193`, `MBBSwitchElm.java:130-132`).
   - Calls `switchElm.toggle()`.
   - If `momentary`, stashes the element into `heldSwitchElm` so that
     `onMouseUp` can re-toggle it back (`CircuitEditor.java:759-763`
     → `heldSwitchElm.mouseUp()` → `SwitchElm.mouseUp()` →
     `toggle()` if momentary, `SwitchElm.java:196-199`).
   - Calls `cirSim.needAnalyze()` (unless the element is a
     `LogicInputElm`) and `cirSim.setUnsavedChanges(true)`.
4. `CircuitEditor.heldSwitchElm` is a single-slot cursor-hold tracker
   declared at `CircuitEditor.java:96`.

Keyboard shortcut (`getShortcut()`):

- `SwitchElm.getShortcut() == 's'` (`SwitchElm.java:280-282`) — places a
  new SPST switch when the user types `s` with empty cursor.
- `Switch2Elm.getShortcut() == 'S'` (`Switch2Elm.java:238-240`) — shift-s
  places an SPDT switch.
- Every other variant returns 0 (no shortcut): `PushSwitchElm.java:33`,
  `DPDTSwitchElm.java:244`, `MBBSwitchElm.java:226`,
  `CrossSwitchElm.java:252`. Users must pick them from the menu.

No right-click toggle; right-click is global to `CircuitEditor`
(context menu). No keyboard toggle of existing switches observed in
this category.

**Ganged / linked switches (software interlock):**

- `SwitchElm.toggle()` (`SwitchElm.java:207-220`) iterates every element
  in the simulator's `elmList` and forwards `simpleToggle()` to other
  `SwitchElm`s with the same `label` — `FLAG_LABEL = 4` enables this.
- `Switch2Elm` adds a numeric `link` group (0 = none, 1..100); peers
  copy `position` directly (`Switch2Elm.java:157-170`).
- `MBBSwitchElm` uses its own numeric `link` (`MBBSwitchElm.java:176-189`).
- `MotorProtectionSwitchElm.setSwitchPositions()` pushes `blown`/clear
  state to every `RelayContactElm` with a matching `label`
  (`MotorProtectionSwitchElm.java:256-267`) — an asymmetric,
  cross-element coupling (not switch↔switch).

## Validation rules

- **`SwitchElm.calculateCurrent()` forces `current = 0` when open**
  (`SwitchElm.java:191-194`) — avoids stale current ghosts after opening
  a closed switch mid-simulation.
- **`Switch2Elm.setEditValue` clamps `throwCount >= 2`** and disables
  `momentary` whenever `throwCount > 2` (`Switch2Elm.java:222-225`) —
  prevents meaningless "momentary 3PDT" combos.
- **`DPDTSwitchElm.setEditValue` clamps `poleCount >= 2`** and calls
  `allocNodes() + setPoints()` to re-grow the per-pole arrays
  (`DPDTSwitchElm.java:233-237`).
- **`MBBSwitchElm.getVoltageSourceCount()` mutates `both`** as a side
  effect (`MBBSwitchElm.java:171-174`). This is called by
  `CirSim.analyzeCircuit` **before** `stamp()`, so `both` is guaranteed
  current when `stamp()` runs. Brittle but correct given the ordering
  invariant documented in `domain-core__element-base.md`.
- **`MotorProtectionSwitchElm.reset()` zeros heats and clears blown**
  (`MotorProtectionSwitchElm.java:82-88`), then re-syncs linked relay
  contacts via `setSwitchPositions()`.
- **`MotorProtectionSwitchElm.canFlipX/Y == false`**
  (`MotorProtectionSwitchElm.java:307-313`) — the element's 3-phase
  layout is fixed-size (posts at `getX() + i*48`, `getY() + 192`,
  `MotorProtectionSwitchElm.java:96-99`) and would break if flipped.
- **`AnalogSwitchElm.setEditValue`** guards `r_on > 0` and `r_off > 0`
  (`AnalogSwitchElm.java:244-247`) — prevents degenerate 0 Ω or
  negative values that would blow up MNA.
- **Dump-type "true"/"false" → position translation** in the
  `SwitchElm` text loader (`SwitchElm.java:59-65`) — legacy dumps used
  `true`/`false`; `LogicInputElm` inverts the mapping because it
  semantically means "high rail" rather than "closed".
- **`Switch2Elm.hasCenterOff()` requires `throwCount == 2`** — center-off
  is only meaningful for SPDT; the boolean is silently ignored otherwise
  (`Switch2Elm.java:234-236`).

## State Transitions

### Mechanical switch state machine

```
SwitchElm         :  position ∈ {0=closed, 1=open}           (posCount=2)
Switch2Elm        :  position ∈ {0..throwCount-1} [+center?]  (posCount=throwCount[+1])
DPDTSwitchElm     :  position ∈ {0, 1}                        (posCount=2; all poles move together)
CrossSwitchElm    :  position ∈ {0=straight, 1=crossed}       (posCount=2)
MBBSwitchElm      :  position ∈ {0=A, 1=A+B, 2=B, 3=B+A}      (posCount=4)
PushSwitchElm     :  position toggles on mouseDown, toggles back on mouseUp (momentary)

toggle()  -> simpleToggle()  -> position = (position + 1) % posCount
                              + propagate to label/link-linked peers
mouseUp() -> toggle() ONLY if momentary (base SwitchElm; Switch2Elm inherits)
```

### Analog switch state machine

```
AnalogSwitchElm  :  open = (V_ctrl < threshold) ^ FLAG_INVERT   (re-evaluated every doStep)
AnalogSwitch2Elm :  open = (V_ctrl < threshold) ^ FLAG_INVERT   (selects out1 vs out2)
```

### Motor protection state machine

```
MotorProtectionSwitchElm:
  blown ∈ {false, true}                                    (latching)
  heats[0..2]  : I²t integrator per phase
  startIteration():
    heat[j] += i[j]² · Δt                                  (Joule heating)
    heat[j] -= Δt · i2t / 3                                (dissipation; full i2t in 3s)
    if any heat[j] > i2t  → blown = true                   (one-way latch until reset)
  blown flip triggers setSwitchPositions() which pushes the new state
       to every RelayContactElm sharing the same `label`.
  reset() clears heats + blown.
```

## Integration Points

### Depends on

- **Base contract** (`domain-core/element-base`): `CircuitElm`
  lifecycle (`stamp`, `doStep`, `nonLinear`, `startIteration`,
  `calculateCurrent`, `getConnection`, `hasGroundConnection`,
  `isWireEquivalent`, `isRemovableWire`, `getPostCount`, `getPost`,
  `getVoltageSourceCount`, `setVoltageSource`, `setCurrent`,
  `getCurrentIntoNode`, `setPoints`, `draw`, flip/flipX/Y/XY,
  `getEditInfo`, `setEditValue`, `getShortcut`, `dump`/`getDumpType`,
  `getJsonTypeName`/`getJsonProperties`/`getJsonState`/
  `applyJsonProperties`/`applyJsonState`/`getJsonPinNames`).
- **`ElmGeometry`** — all point/lead math goes through `geom()`.
- **`CircuitSimulator`** — `stampVoltageSource`, `stampResistor`,
  `stampNonLinear`, `stampResistor`, `timeStep`, `elmList`.
- **`CircuitEditor`** — `doSwitch` / `heldSwitchElm` pathway
  (`CircuitEditor.java:96,666-669,759-763,874-890`).
- **`dialog.EditInfo`** — dialog row carriers (row 0 momentary
  checkbox, row 1 IEC symbol, row 2 label — common to the `SwitchElm`
  family; variants append additional rows).
- **`Checkbox`** (client root-level widget) — used directly by
  `SwitchElm.getEditInfo(0)` for the Momentary checkbox.
- **`RelayContactElm`** — `MotorProtectionSwitchElm` invokes
  `RelayContactElm.setPosition(int, int)` on linked contacts using the
  shared `label`.
- **`LogicInputElm`** — referenced by `SwitchElm(xb,yb,f,st)`
  constructor (`SwitchElm.java:61,63`) and by
  `CircuitEditor.doSwitch` (`CircuitEditor.java:886`) as a `SwitchElm`
  subclass that does **not** trigger `needAnalyze()` on toggle (it
  simply updates its output rail).
- **`CustomLogicModel.escape/unescape`** — label serialization in
  `MotorProtectionSwitchElm` (`MotorProtectionSwitchElm.java:66,75`)
  and via inherited `SwitchElm.dump()`.

### Used by

- **`CircuitElmCreator`** (dump-type dispatch) — constructors for
  `'s'`, `'S'`, 159, 160, 416, 428, 429, 430.
- **`io.json.CircuitElementFactory`** — JSON type-name dispatch for
  `"Switch"` / `"PushSwitch"` / `"SPDTSwitch"` / `"DPDTSwitch"` /
  `"MBBSwitch"` / `"CrossSwitch"` / `"MotorProtectionSwitch"` /
  `"AnalogSwitch"` / `"AnalogSwitch2"`.
- **`MenuManager`** / **`Toolbar`** — palette menu entries for each
  switch variant.

### External deps

- `com.google.gwt.canvas.dom.client.Context2d` — text alignment
  (`SwitchElm.java:24`, `MotorProtectionSwitchElm.java:24-25`).
- JDK `java.util.{Map, LinkedHashMap}` for JSON.

## Issues / Questions

1. **Inconsistent "switch" abstraction.** `AnalogSwitchElm` and
   `MotorProtectionSwitchElm` extend `CircuitElm` **directly** — they
   are semantically switches but do not participate in
   `CircuitEditor.doSwitch()` because they are not `SwitchElm`. Users
   cannot click them to toggle. Consider an `AbstractSwitchElm`
   interface (`boolean canClickToggle()`, `void toggle()`, `Rectangle
   getSwitchRect()`) that both families could implement; today the
   coupling is via `instanceof SwitchElm`.
2. **`MBBSwitchElm.getVoltageSourceCount()` has a side effect.** It
   mutates `both` (`MBBSwitchElm.java:171-174`). This relies on
   `getVoltageSourceCount()` being called before `stamp()` each
   analysis cycle. If a future refactor caches or batches these calls,
   `stamp()` could see stale `both`. Moving the assignment to
   `startIteration()` or a dedicated `updateState()` hook would remove
   the trap.
3. **`isRemovableWire()` forced to false on every multi-pole variant**
   with identical comment "see #646" (`Switch2Elm.java:182`,
   `DPDTSwitchElm.java:212`, `CrossSwitchElm.java:240`,
   `MBBSwitchElm.java:197-199`). Worth investigating whether a
   generalized N-node wire-closure optimization could subsume these
   (pointed out by the `MBBSwitchElm` comment explicitly:
   "calcWireClosure() doesn't handle that case").
4. **Magic dump types scattered across the catalog** (159, 160, 416,
   428, 429, 430, 's', 'S') — no central registry. Two new switch
   types since then (`MBBSwitchElm = 416`, `MotorProtectionSwitchElm =
   428`, `DPDTSwitchElm = 429`, `CrossSwitchElm = 430`) sit in an
   adjacent range; a `SwitchDumpTypes` constants file would prevent
   collisions.
5. **`MotorProtectionSwitchElm` hard-coded geometry.** Posts at
   `getX() + i*48` / `getY() + 192` (`MotorProtectionSwitchElm.java:96-99`)
   assume a specific grid size. If the editor's grid snap changes
   (currently 8 or 16), the switch becomes off-grid on the 8-grid
   setting. `canFlipX/Y == false` is a hint that the layout isn't
   parametric.
6. **`AnalogSwitch2Elm.calculateCurrent()` divides by `r_on` even when
   open** (`AnalogSwitch2Elm.java:99-103`) — for the open branch, the
   actual stamped resistor is the per-branch `r_off` (or absent in
   pulldown mode), so the reported current is wrong magnitude when
   `r_off != r_on`. The current reported to the info panel may not
   match the branch current.
7. **`Switch2Elm.flipX/Y/XY` reflects `position` as
   `posCount - 1 - position`** (`Switch2Elm.java:242-255`), but
   `DPDTSwitchElm.flip()` uses `1 - position` (fixed posCount=2)
   (`DPDTSwitchElm.java:257`). Inconsistent: for `DPDTSwitchElm` with
   future `posCount != 2` the formula would be wrong. Not exercised
   today because `DPDTSwitchElm`'s `posCount` is fixed at 2, but
   fragile.
8. **`PushSwitchElm` shadows `SwitchElm`'s shortcut with 0.** The base
   returns `'s'`; the push variant suppresses it. There's no
   standalone keyboard shortcut to place a push switch, so users must
   place an SPST and tick the Momentary checkbox — or use the menu.
9. **`CrossSwitchElm` comment "optimizing out this element is too
   complicated to be worth it (see #646)"** is copy-pasted from
   `Switch2Elm` without adapting reasoning; may be inherited mistake
   vs deliberate. Worth validating.
10. **Analog-switch pulldown is always to ground (`getNode(...) , 0`)**
    (`AnalogSwitchElm.java:173-174`) — no option to pull down to a
    user-specified rail. Real CMOS analog switches may want
    mid-rail bias. Minor feature gap.
11. **`SwitchElm.getJsonTypeName()` overloaded to return two different
    types from the same class** (`"Switch"` vs `"PushSwitch"` based on
    `momentary`, `SwitchElm.java:285-287`). `PushSwitchElm.java:38-40`
    also returns `"PushSwitch"` — so two different Java classes may
    serialize to the same JSON type and round-trip to a single
    canonical class. Import may lose the subclass identity
    (behaviorally equivalent, but worth a note for JSON consumers).
12. **`MotorProtectionSwitchElm.applyJsonState` no null-guard.** It
    dereferences `state` without the `if (state != null)` guard that
    `SwitchElm.applyJsonState` uses (`SwitchElm.java:342-348` vs
    `MotorProtectionSwitchElm.java:345-357`). Inconsistency.

## Concept boundary: single "switch-elements" concept (base + variants)

A single `switch-elements` concept covering `SwitchElm` (base) plus the
8 variants is the right granularity:

- All `SwitchElm` subclasses share the same editor click pathway
  (`doSwitch` → `toggle` → `mouseUp` → `heldSwitchElm`) and the same
  label/link ganging pattern.
- The stamping strategies (Pattern A/B/C above) are deterministic by
  element class; they do not leak into the base contract.
- `AnalogSwitchElm` / `AnalogSwitch2Elm` are conceptually switches
  (the user picks them from the "Passive Components → Switches" menu
  group) even though they extend `CircuitElm` directly. Include them
  in the concept, with a note that they are **voltage-controlled**
  rather than user-clickable.
- `MotorProtectionSwitchElm` is the odd-one-out: 3-phase latch with
  `I²t` physics, cross-element labeled coupling to `RelayContactElm`.
  Include it in the concept with a sub-note: "couples to the relay
  family via shared `label`" so readers know to cross-reference the
  relay concept.

If finer granularity is ever required, the three clean cuts are:

1. **switch-mechanical** — `SwitchElm` (base), `Switch2Elm`,
   `DPDTSwitchElm`, `PushSwitchElm`, `MBBSwitchElm`, `CrossSwitchElm`
   (user-clickable, wire-equivalent / voltage-source-zero stamping).
2. **switch-analog** — `AnalogSwitchElm`, `AnalogSwitch2Elm`
   (voltage-controlled, resistor stamping, `nonLinear() == true`).
3. **switch-protection** — `MotorProtectionSwitchElm` (latch+trip
   with I²t physics; couples to `relay-family` via shared label).

The dependency graph does not force this split — all 9 files are in
the same package with identical layer/SCC classification.
