# Module Analysis: domain-core / element / cat-electromechanical

> **Path:** src/main/java/com/lushprojects/circuitjs1/client/element/ (6 concrete files)
> **Base:** `CircuitElm` (DCMotor, ThreePhaseMotor, RelayElm, RelayCoilElm, RelayContactElm); `ChipElm` (TimeDelayRelayElm)
> **Layer:** 2 (SCC-A)
> **Analyzed:** 2026-04-18
> **Files:** 6 concrete classes; 0 test files

## Purpose

The `electromechanical` category packages elements whose state straddles
the **electrical ↔ mechanical** boundary — they own one or more
electrical nodes (coil/armature/stator) coupled to a non-electrical state
variable integrated in software:

- **Rotor angle + angular velocity** (DC motor, 3-phase motor) —
  mechanical inertia integrated as a companion inductor (`J` = moment of
  inertia acts as an equivalent inductance for the MNA stamp), friction
  as a parallel resistor (`b` = `Nms/rad`).
- **Relay armature position** (`d_position ∈ [0,1]`, `i_position ∈
  {0,1,2}`) — driven by the coil current crossing `onCurrent` /
  `offCurrent` thresholds, timed by `switchingTime`.
- **I²t thermal latch** shared with `MotorProtectionSwitchElm` (see
  `domain-core__cat-switches.md`) — same pattern: physical integrator
  controlling electrical stamp.

Two stamping strategies coexist:

1. **Internal companion-network** (`DCMotorElm`, `ThreePhaseMotorElm`,
   `RelayElm`, `RelayCoilElm`) — the element allocates internal nodes
   (`getInternalNodeCount() > 0`) and stamps a mini sub-netlist of
   resistors / inductors / voltage sources / controlled sources that
   reproduces the physics inside the global MNA matrix.
2. **State-controlled resistor** (`RelayContactElm`,
   `TimeDelayRelayElm`) — each step restamps a 2-terminal resistor
   (`r_on` or `r_off`) driven by state computed elsewhere
   (`setPosition` from a linked coil) or computed in `stepFinished`
   (time-delay latch).

The relay family splits the same physical device along a "model
boundary" seam: `RelayElm` is an **integrated** coil+contacts element
(one symbol, one Java class); `RelayCoilElm + RelayContactElm` are the
**split-style** schematic pair linked by a shared `String label` — same
pattern as `MotorProtectionSwitchElm` → `RelayContactElm`.

## Per-element catalog

| Element | Extends | Posts | Internal nodes | V-sources | Electrical state | Mechanical state | Dump-type | File:lines |
|---|---|---|---|---|---|---|---|---|
| `DCMotorElm` | `CircuitElm` | 2 (a, b) | 4 | 2 | `ind.current` (armature), L=`inductance`, R=`resistance` | `angle` (rad), `speed` (rad/s), `coilCurrent`, `inertiaCurrent`, `J` as Inductor; `b` as resistor; `Kb` back-EMF, `K` torque-const; `gearRatio`, `tau` | 415 | `DCMotorElm.java:15-345` |
| `ThreePhaseMotorElm` | `CircuitElm` | 6 (U1/U2/V1/V2/W1/W2) | 7 | 2 | `coilCurrents[5]` (3 stator + 2 rotor dq), `xformMatrix[5][5]` inverted inductance matrix | `angle`, `speed` (manual Euler integration), `filteredSpeed` (display), `J`, `b` | 427 | `ThreePhaseMotorElm.java:17-516` |
| `RelayElm` (integrated) | `CircuitElm` | `2 + 3*poleCount` (pole, NC, NO × n; coil+, coil-) | 1 (coil series-R internal) | 0 (relay is nonlinear resistor-based) | `coilCurrent`, inductor `ind`, `coilR`; per-pole `switchCurrent[]` via `r_on`/`r_off` | `d_position ∈ [0,1]`, `i_position ∈ {0,1,2}`, `onState`, `switchingTime` | 178 | `RelayElm.java:43-667` |
| `RelayCoilElm` (split coil) | `CircuitElm` | 2 (coil+, coil-) | 1 (series-R internal) | 0 | `coilCurrent`, `avgCurrent` (1 kHz RC avg), inductor `ind`, `coilR` | `state ∈ {0,1,2,3}` FSM, `switchPosition ∈ {0,1}`, `lastTransition`, `switchingTimeOn/Off`, `type ∈ {NORMAL, ON_DELAY, OFF_DELAY, LATCHING}`; pushes `setPosition` into all `RelayContactElm` sharing `label` | 425 | `RelayCoilElm.java:36-506` |
| `RelayContactElm` (split contact) | `CircuitElm` | 2 (common, NO/NC) | 0 | 0 | `switchCurrent`, `r_on`/`r_off` resistor | `i_position ∈ {0,1}` driven externally by `setPosition(pos, type)` from coil; `FLAG_NORMALLY_CLOSED = 2` inverts; `FLAG_IEC = 4` IEC symbol; `type` copied from coil for delay glyph | 426 | `RelayContactElm.java:42-357` |
| `TimeDelayRelayElm` | `ChipElm` | 4 (Vin, gnd, in, out) | 0 | 0 | `onResistance`/`offResistance` between `in`↔`out` via `stampResistor`; 10 kΩ `vinResistance` Vin↔gnd | `poweredState` (Vcoil > 2.5 V sensor), `onState` (gated by `onDelay`/`offDelay`), `lastTransition` (absolute `simulator().t`) | 414 | `TimeDelayRelayElm.java:28-185` |

### Per-element behavior notes

- **`DCMotorElm`** — classical DC-motor state-space
  (`V - Ri - L·di/dt = Kb·ω` ; `J·dω/dt + b·ω = K·i`) reified as two
  coupled sub-circuits sharing the MNA matrix:
  1. Electrical loop: `ind` (armature L, trapezoidal/BE companion) in
     series with `resistance` in series with a controlled voltage
     source (back-EMF `= Kb · ω`) between `getNode(0)` and `getNode(1)`
     (`DCMotorElm.java:118-142`).
  2. Mechanical loop: `indInertia` (`J` as inductance, giving `τ =
     J·dω/dt` ↔ `V = L·di/dt`) in series with friction resistor `b`
     and a voltage source (armature torque `= K · i_armature`) between
     internal nodes 4↔5↔0 (`DCMotorElm.java:133-141`).
  - `coilCurrent` (armature i) and `inertiaCurrent` (ω) are the two
    inductor currents; `speed = inertiaCurrent` (`DCMotorElm.java:178`).
  - `angle += speed · Δt` in `startIteration()` (line 148).
  - Controlled sources: `doStep()` calls `updateVoltageSource` twice,
    writing `coilCurrent * K` for the torque source and
    `inertiaCurrent * Kb` for the back-EMF source (lines 164-172).
  - Only one external voltage source index (`voltSources[0]`) carries
    the observable terminal current; `voltSources[1]` is internal.
  - `setCircuitDocument` rewires both inductors' simulator reference
    — critical for correct reset/load (line 294-298).
- **`ThreePhaseMotorElm`** — 3-phase induction motor using `dq`-frame
  equivalent (2 rotor windings after Park transform, 3 stator windings);
  `coilCount = 5` (line 137). Implements a **coupled-inductor matrix**
  directly in `stamp()` via `CircuitMath.invertMatrix` (line 184) —
  same technique as `CustomTransformerElm` but with:
  - 5×5 mutual-coupling matrix `xformMatrix`, coupling coefficients
    derived from `Lm / sqrt(Ls · Lr2)` (line 170) with 3-phase
    symmetric pattern (`k0, -k0/2, k0·√3/2` etc., lines 171-175).
  - Rotor bars grounded through `1.5 · Rr` each (lines 156-157).
  - **Rotor-EMF controlled voltage sources** (`vs1value`/`vs2value`
    computed in `startIteration()`, lines 239-241) — the
    speed-dependent cross-coupling that makes induction work. Updated
    via `updateVoltageSource` in `doStep()` (lines 253-254).
  - **Torque equation** (line 234): `τ = Zp · √3/2 · Lm · ((i_b-i_c)·i_d
    - √3·i_a·i_e)` with `Zp=2` pole-pairs.
  - Speed Euler-integrated in `startIteration()`: `speed += Δt · (τ -
    b·ω) / J` (line 236). **Not** done via an inertia inductor like
    `DCMotorElm`; this is a deliberate departure — the 3-phase coupled
    matrix already stresses the solver.
  - `calculateCurrent()` recomputes each coil current from stored
    source values + voltage diffs multiplied by the inverted inductance
    matrix (lines 257-280).
  - `getConnection(n1, n2) == true` for all post pairs (line 286-288)
    — all 6 motor posts are treated as electrically connected for
    BFS purposes.
  - `filteredSpeed` is a display-only low-pass (`0.98·prev + 0.02·now`,
    line 366) to stabilize the RPM readout.
- **`RelayElm`** (integrated) — coil + N pole positions (1..4) fused
  into one element with `2 + 3·poleCount` posts. Two stamping models:
  - **Coil side (Pattern A, companion-network):** inductor `ind`
    stamped between `nCoil1` and `nCoil3` (internal), plus
    `coilR`-series resistor `nCoil3`↔`nCoil2` (lines 340-343).
  - **Contact side (Pattern B, state-controlled resistor):** each
    pole stamps `r_on` to the selected throw and `r_off` to the other
    every `doStep()` (lines 416-421). `nonLinear() == true` (line
    409) so the matrix re-stamps each Newton iteration.
  - **Two operating models** (live together):
    - **New model** (`switchingTime > 0`, default 5 ms): hysteresis
      `onCurrent`/`offCurrent` thresholds transition via intermediate
      `i_position == 2`; `d_position` interpolates linearly at
      `±Δt/switchingTime` (`RelayElm.java:350-383`).
    - **Old model** (`switchingTime == 0`): heuristic formula
      `d_position = (p·pmult)² - 1.3` where `p = I/onCurrent`; no
      proper hysteresis — the EditInfo dialog offers a "Use New
      Model" migration button (lines 466-473, 517-525).
  - `getConnection(n1, n2)` returns true only within the same
    pole-triplet (`n1/3 == n2/3`, line 545-547) — coil and contacts
    are electrically isolated.
  - Shortcut `'R'` (line 549-551); ID prefix `"K"` (standard relay
    schematic convention, line 127).
  - Layout flags: `FLAG_SWAP_COIL`, `FLAG_SHOW_BOX`,
    `FLAG_BOTH_SIDES_COIL`, `FLAG_FLIP`.
- **`RelayCoilElm`** (split coil) — coil-only half of the split-style
  relay, electrically identical to the coil part of `RelayElm` (same
  `ind` + `coilR` topology, 2 posts + 1 internal node) but adds:
  - **FSM** `state ∈ {0=off, 1=turning-on, 2=on, 3=turning-off}`
    driven by `avgCurrent` (1 kHz exponential smoothing on `|coilCurrent|`,
    line 289-290) versus `onCurrent`/`offCurrent`. State 1→2 requires
    `t - lastTransition > switchingTimeOn`; state 3→0 requires `>
    switchingTimeOff` (lines 293-321).
  - **4 type modes**: `TYPE_NORMAL`, `TYPE_ON_DELAY` (only `on`
    delayed; off is instant), `TYPE_OFF_DELAY` (only `off` delayed;
    on is instant), `TYPE_LATCHING` (each `on`-pulse toggles
    `switchPosition = 1 - switchPosition`, line 303-304).
  - `stamp()` (line 267) sets `switchingTimeOn`/`switchingTimeOff`
    based on `type`, then calls `setSwitchPositions()` to broadcast
    initial state to all linked contacts.
  - **Cross-element coupling** via label: `setSwitchPositions()`
    (lines 333-343) iterates `elmList` (delivered through
    `setParentList(Vector)` override, lines 327-331 — note this
    overrides the `CircuitElm` hook differently than base, using
    a `Vector` instead of `ArrayList`), finds every `RelayContactElm`
    with `s2.label.equals(label)`, and pushes
    `s2.setPosition(1 - switchPosition, type)`.
- **`RelayContactElm`** (split contact) — contact-only half. Pure
  2-post resistor with state-controlled `r_on`/`r_off`:
  - `stamp()` stamps only `stampNonLinear` on both nodes (line
    222-226); actual resistor stamped in `doStep()` (line 233-235).
  - `nonLinear() == true` (line 229-231) because `i_position` can
    change mid-Newton when a linked coil is also iterating.
  - `setPosition(i_position_, type_)` (lines 195-198) is the coupling
    entry point — accepts raw position and type from the coil, applies
    `FLAG_NORMALLY_CLOSED` inversion (`1 - i_position_`), stores `type`
    for the IEC symbol choice.
  - Uses `swpoles[3]` / `swposts[3]` but only 2 posts are exposed
    (`getPostCount() == 2`) — the third point (`swposts[2]`,
    `swpoles[2]`) is a display-only NC/NO stub drawn as reference.
  - Drawing: IEC symbol adds an arc + vertical bracket when `type ∈
    {ON_DELAY, OFF_DELAY}` (lines 123-145) — the arc direction flips
    with the type to indicate delay direction.
  - Posts: `common` (index 0), `no`/`nc` (index 1) —
    `getJsonPinNames == ["common", "no"]` regardless of
    `FLAG_NORMALLY_CLOSED` (minor inconsistency — the pin is "nc"
    electrically when the flag is set).
- **`TimeDelayRelayElm`** — `ChipElm`-based time-delay relay with 4
  pins: **Vin / gnd** (sense) and **in / out** (switched path).
  - Coil-sense: fixed 10 kΩ resistor Vin↔gnd (line 83); the element
    samples `V(Vin) - V(gnd) > 2.5 V` in `stepFinished()` (line 95).
  - Switched path: `stampNonLinear` on in/out nodes (lines 84-85),
    then `doStep()` stamps `onResistance` or `offResistance` between
    them (line 90). Default `onResistance = 1 Ω`, `offResistance =
    10 MΩ`.
  - **Delay logic** (lines 93-100): `poweredState` tracks the raw
    sense; `onState` follows after `lastTransition + (on|off)Delay`
    elapses (`onDelay` when powering on, `offDelay` when powering
    off). `offDelay == 0` by default → turn-off is immediate;
    `onDelay == 1 s` default.
  - `isDigitalChip() == false` (line 110-113) — no
    logic-voltage/threshold UI in the edit dialog.
  - Overrides `getChipEditInfo`/`setChipEditValue` (not the base
    `getEditInfo` / `setEditValue`) because `ChipElm` reserves the
    first rows for chip-common settings.

## Motor models

Both motors share the same design pattern: reify the mechanical DOF as
a companion element plugged into the same MNA matrix, then add
controlled sources for the back-EMF / torque coupling.

### DC motor — state-space to MNA

```
armature loop (electrical):
   V_ab = R·i + L·di/dt + Kb·ω

mechanical loop (mapped to MNA):
   J·dω/dt + b·ω = K·i             ⟺   an inductor J with current ω,
                                          in parallel with resistor b,
                                          fed by voltage source V = K·i

internal node layout:
   node 0 (ext) ── L(ind) ── node 2 ── R(resistance) ── node 3 ── VS(back-EMF=Kb·ω) ── node 1 (ext)
   node 4 ── L(indInertia, L=J) ── node 5 ── R(b) ── gnd
                                          ↑
                                      VS(torque=K·i_armature)  from node 4 to gnd
```

- `K` and `Kb` are independent EditInfo rows but physically should be
  equal in SI units (both = torque per ampere / volt·s per rad); the
  UI allows tuning them separately for pedagogy.
- `gearRatio` affects only the drawing (visible rotor pattern angle)
  — it does **not** alter the physics (line 209-225).
- `tau` is declared + dumped (line 21, 62, 76) but **never read after
  init** — reserved for future static-friction parameterization per
  the class-level comment (line 21).

### 3-phase induction motor — coupled-inductor matrix

- 3 stator coils (U, V, W) + 2 rotor coils (d, q equivalent after
  Park transform) → 5×5 inductance matrix.
- Coupling coefficients encode the 120° electrical offset:
  - `L_Ud = Lm/√(Ls·Lr)`
  - `L_Vd = -½·Lm/√(...)`
  - `L_Vq = +½√3·Lm/√(...)`
  - `L_Wd = -½·Lm/√(...)`
  - `L_Wq = -½√3·Lm/√(...)`
- Matrix inverted once per `stamp()`, multiplied by `Δt`, then stamped
  as conductances (diagonal) and VCCS sources (off-diagonal) using
  `stampConductance` / `stampVCCurrentSource`. Same technique as
  `CustomTransformerElm`.
- `startIteration()` manually Euler-integrates
  `ω += Δt·(τ - b·ω)/J` and updates the speed-dependent rotor-EMF
  voltage sources (`vs1value`, `vs2value`).
- Torque from electromagnetic coupling: `τ = Zp · √3/2 · Lm ·
  ((i_V - i_W)·i_rotor_d - √3·i_U·i_rotor_q)` — canonical induction
  motor torque expression.
- `Zp = 2` is hard-coded (line 135) — number of pole pairs is not
  user-editable.

### Motor physics quick reference

| Quantity | DC (`DCMotorElm`) | 3-phase (`ThreePhaseMotorElm`) |
|---|---|---|
| Back-EMF | `Kb·ω` (stamped VS) | `vs1,vs2 = f(ω, Lm, Lr, i)` (stamped VS) |
| Torque | `K·i_armature` (stamped VS → indInertia) | `τ = Zp·√3/2·Lm·(...)` (integrated in code) |
| Inertia `J` | Companion inductor `indInertia` | Manual Euler `ω += Δt(τ-bω)/J` |
| Friction `b` | Stamped resistor to ground | Subtracted in Euler step |
| Angle `θ` | `θ += ω·Δt` in `startIteration` | `θ += ω·Δt` in `startIteration` |
| Pole-pairs | n/a | `Zp = 2` hard-coded |
| Flip allowed | Yes (default) | `canFlipX() == canFlipY() == false` (ThreePhase:458-464) |

## Coil/contact coupling by label

The split-style relay pattern — same mechanism used by
`MotorProtectionSwitchElm` → `RelayContactElm` (see
`domain-core__cat-switches.md` §"MotorProtectionSwitchElm").

### Label-linking mechanics

- Both `RelayCoilElm` and `RelayContactElm` carry a `String label`
  field, user-editable via the last EditInfo row
  (`RelayCoilElm.java:388-390`, `RelayContactElm.java:261-262`).
- `label` is serialized via `CustomLogicModel.escape`/`unescape`
  (whitespace-safe) in both dumps
  (`RelayCoilElm.java:128`, `RelayContactElm.java:97`).
- **Coupling direction is one-way** (coil → contact):
  `RelayCoilElm.setSwitchPositions()` (lines 333-343) walks the
  parent `elmList`, matches `instanceof RelayContactElm` with
  `s2.label.equals(label)`, and calls
  `s2.setPosition(1 - switchPosition, type)`.
- `setSwitchPositions()` is invoked in two places:
  1. `stamp()` (line 282) — initial sync after analysis.
  2. `startIteration()` (line 323-324) when `oldSwitchPosition !=
     switchPosition` — only on actual position changes.
- **Note:** `RelayCoilElm.setParentList(Vector)` (lines 327-331)
  **shadows** the base `setParentList(ArrayList<CircuitElm>)` signature
  — the type is `Vector<CircuitElm>`, which means it is **not** called
  by the same path as CompositeElm children. In practice the coil
  relies on the simulator's element list (accessed at the same call
  site as `MotorProtectionSwitchElm.setSwitchPositions` — through
  `circuitDocument.simulator.elmList` or similar); any direct reader
  should verify how `elmList` gets populated for this element.
- **Label visibility:** drawn adjacent to the coil (`RelayCoilElm.java:159-167`)
  and above/beside each contact (`RelayContactElm.java:113-121`);
  users identify the coupling visually via matching label text.
- **NC/NO inversion** happens at the contact, not the coil:
  `RelayContactElm.setPosition(i_position_, type_)` applies
  `isNormallyClosed() ? (1 - i_position_) : i_position_` (line 196)
  — so one coil can drive a mix of NC and NO contacts sharing the
  same label.

### Comparison: integrated `RelayElm` vs split `RelayCoilElm + RelayContactElm`

| Axis | `RelayElm` (integrated) | `RelayCoilElm + RelayContactElm` (split) |
|---|---|---|
| Schematic layout | Single symbol with box around coil + poles | Two independent symbols, linked by label text |
| Post count | `2 + 3·poleCount` (dynamic) | `2` (coil) + `2·N` (N separate contacts) |
| Coupling mechanism | Direct Java field access within one class | `label` string match across `elmList` |
| Switching FSM | Simple `onState` + `d_position` interpolation (`RelayElm.java:350-383`) | 4-state FSM + `avgCurrent` smoothing + 4 type modes (`RelayCoilElm.java:293-321`) |
| Delay modes | Single `switchingTime` (symmetric) | `TYPE_NORMAL`, `TYPE_ON_DELAY`, `TYPE_OFF_DELAY`, `TYPE_LATCHING` |
| Visual | One box, multiple poles stacked | Coil box drawn alone; each contact drawn as NO/NC switch at its own location |
| Use case | Simple schematic pedagogy, max 4 poles, all poles at same location | Industrial ladder-logic-style diagrams with contacts distributed across the drawing |
| Dump types | 178 | 425 (coil) + 426 (contact) |

### Same pattern in `MotorProtectionSwitchElm`

Per `domain-core__cat-switches.md:239-241, 256-267`:
`MotorProtectionSwitchElm` (dump type 428) uses the **identical
labeling mechanism** to drive `RelayContactElm` peers — when the I²t
latch blows, `setSwitchPositions()` walks the element list and calls
`RelayContactElm.setPosition(...)` on every contact sharing its
`label`. This gives three "controller" elements that can drive
contacts: `RelayCoilElm` (current-driven), `MotorProtectionSwitchElm`
(I²t-driven). `RelayElm` does not participate in the label system —
its contacts are direct Java array members.

## Time-delay relay timing mechanics

### `TimeDelayRelayElm` (chip-style, single-element)

- **Sense input:** `Vin` ↔ `gnd` with fixed 10 kΩ internal resistor
  (`vinResistance`, line 32). The 2.5 V threshold (line 95) is
  hard-coded to match the default digital-logic midpoint.
- **Timing** (all in `stepFinished()`, lines 93-100):
  1. Sample `poweredState = (V_vin - V_gnd) > 2.5`.
  2. On transition (edge), record `lastTransition = simulator().t`.
  3. Gate `onState = poweredState` once `t > lastTransition +
     (poweredState ? onDelay : offDelay)`.
- **Output stamp:** `doStep()` stamps `onResistance` when `onState`
  else `offResistance` between `in` and `out` (line 90). `nonLinear()
  == true` (line 77-79) — ensures Newton loop re-evaluates on state
  changes.
- **Defaults:** `onDelay = 1 s`, `offDelay = 0 s` (instantaneous
  release), `onResistance = 1 Ω`, `offResistance = 10 MΩ`.

### `RelayCoilElm` delay modes (type flag)

- `TYPE_NORMAL` — symmetric `switchingTime` for both transitions.
- `TYPE_ON_DELAY` — `switchingTimeOn = switchingTime`,
  `switchingTimeOff = 0` (on-delay is the "slow close" ladder
  symbol).
- `TYPE_OFF_DELAY` — `switchingTimeOff = switchingTime`,
  `switchingTimeOn = 0` (off-delay "slow open").
- `TYPE_LATCHING` — each on-pulse toggles `switchPosition = 1 -
  switchPosition` (line 303-304); releases do not flip back. Acts as
  a T flip-flop.
- The `type` field is pushed into `RelayContactElm.setPosition(pos,
  type)` so contacts can draw the IEC-specific arc symbol (timer,
  line 123-145 of `RelayContactElm`).

### Timing comparison

| Property | `TimeDelayRelayElm` | `RelayCoilElm` (ON_DELAY / OFF_DELAY) |
|---|---|---|
| Coil modeling | 10 kΩ sense resistor (no coil inductance) | Full `L`/`R` coil with Inductor companion |
| Delay trigger | Voltage threshold (2.5 V) | Current threshold (`onCurrent`/`offCurrent`, default 20 mA / 15 mA) |
| Smoothing | None (raw `V_coil`) | 1 kHz exponential avg (`avgCurrent`, line 289-290) |
| Output | Single 2-terminal resistor `in`↔`out` | Arbitrary number of `RelayContactElm` peers by label |
| State | `onState`, `poweredState`, `lastTransition` | `state ∈ {0..3}`, `switchPosition`, `lastTransition` |
| Dump-type | 414 | 425 (+ 426 per contact) |
| Base class | `ChipElm` (uses chip dialog API) | `CircuitElm` |

## Validation rules

- **`DCMotorElm.setEditValue` guards all values > 0** (lines 273-290)
  — `inductance=0` would nuke the companion model; `J=0` would
  freeze rotor; `b=0` would permit runaway ω; `K=0` / `Kb=0` would
  decouple mechanical from electrical.
- **`DCMotorElm` updates companion inductor on edit** — changing
  `inductance` (line 275) or `J` (line 285) reinstantiates the
  `Inductor.setup(...)` with the current running current to preserve
  continuity.
- **`ThreePhaseMotorElm.setEditValue` clamps coupling coefficient**
  to `(0, 1)` (line 446) — `Lm` stored as `coef · √(Ls·Lr)` keeps
  the matrix positive-definite.
- **`RelayElm` poleCount clamp:** EditInfo row 5 is `[1, 4]`
  (line 477); JSON import clamps to `[1, 4]` (lines 625-627).
- **`RelayElm.reset` preserves `onState` deliberately** (line 332-334
  comment: "if we don't, Relay Flip-Flop gets left in a weird state
  on reset") — same idiom repeated in
  `RelayCoilElm.reset` (line 260-262) and `RelayContactElm.reset`
  (line 217-219).
- **`RelayElm` "Use New Model" migration** — EditInfo row 4 shows a
  migration button when `switchingTime == 0`, setting `switchingTime
  = 5e-3` and `ei.newDialog = true` to refresh the dialog
  (lines 466-473, 517-525).
- **`TimeDelayRelayElm.setChipEditValue` guards `onResistance > 0`
  and `offResistance > 0`** but **not** `onDelay`/`offDelay` (lines
  139-148) — negative delays would make `t > lastTransition +
  delay` fire immediately, effectively bypassing the gate (possible
  but arguably a feature, not a bug).
- **Label de-duplication — none.** If two `RelayCoilElm` instances
  share the same label, both will push `setPosition` to the same
  `RelayContactElm`; last-write-wins per iteration. No validation
  warns the user.
- **`RelayContactElm.applyJsonProperties` handles `normally_closed`
  and `iec_symbol` as `Boolean` only** (lines 318-333) — a legacy
  string `"true"`/`"false"` would be ignored. `RelayElm` uses
  `getJsonBoolean` helper (line 660) which tolerates both.
- **`RelayContactElm.getJsonPinNames() == {"common", "no"}` regardless
  of `FLAG_NORMALLY_CLOSED`** (line 338) — misleading when the flag
  is set; consumers cannot distinguish NC from NO via JSON pin names
  alone (they must read `normally_closed` property).

## State Transitions

### DC motor per-step

```
startIteration:
   ind.startIteration(V01 - V23)           # L armature companion refresh
   indInertia.startIteration(V45 - V5g)    # L inertia companion refresh
   angle += speed * Δt                     # mechanical integration

doStep (inside Newton loop):
   updateVS(node4→gnd, voltSources[1], coilCurrent * K)   # torque source
   updateVS(node3→node1, voltSources[0], inertiaCurrent * Kb)  # back-EMF
   ind.doStep / indInertia.doStep                         # current sources

calculateCurrent (after solve):
   coilCurrent     = ind.calculateCurrent(...)
   inertiaCurrent  = indInertia.calculateCurrent(...)
   speed           = inertiaCurrent                       # ω IS the inertia current

reset:
   ind.reset + indInertia.reset; coilCurrent = inertiaCurrent = 0
   (angle & speed NOT reset — persist through simulator reset)
```

### 3-phase motor per-step

```
stamp (once per analysis):
   invert xformMatrix[5][5]
   stamp 5×5 L-matrix as (stampConductance diag) + (stampVCCurrentSource off-diag) with dt scaling
   stamp Rs on 3 stator + 1.5·Rr on 2 rotor bars
   stampVoltageSource for vs1 and vs2 (rotor EMF placeholders)

startIteration:
   coilCurSourceValues[i] = coilCurrents[i]  (companion source values)
   τ = Zp·√3/2·Lm·((i1-i2)·i3 - √3·i0·i4)
   speed += Δt · (τ - b·speed) / J
   angle += speed · Δt
   vs1value = -Zp·speed·(Lm·√3/2·(i1-i2) + 1.5·Lr·i4)
   vs2value = +Zp·speed·(3/2·Lm·i0 + 1.5·Lr·i3)

doStep:
   5× stampCurrentSource(coilNodes[j*2], coilNodes[j*2+1], coilCurSourceValues[j])
   updateVoltageSource(n002→gnd, voltSources[0], -vs1value)
   updateVoltageSource(n006→gnd, voltSources[1], -vs2value)

calculateCurrent (after solve):
   for each coil i:
      v = coilCurSourceValues[i] + Σ_j  voltdiff_j · xformMatrix[i][j]
      coilCurrents[i] = v
```

### RelayElm per-step (new model)

```
startIteration (new model, switchingTime > 0):
   ind.startIteration(V_coil)
   abs_i = |coilCurrent|
   if onState:
      if abs_i < offCurrent:  onState=false, i_position=2       # begin drop-out
      else:                    d_position += Δt/switchingTime; clamp @ 1
   else:
      if abs_i > onCurrent:   onState=true,  i_position=2       # begin pick-up
      else:                    d_position -= Δt/switchingTime; clamp @ 0

doStep (re-evaluated each Newton iter because nonLinear=true):
   ind.doStep(V_coil)
   for p in 0..poleCount:
      stampResistor(common, NO, i_position==0 ? r_on : r_off)
      stampResistor(common, NC, i_position==1 ? r_on : r_off)

calculateCurrent:
   coilCurrent = ind.calculateCurrent(V_coil)
   if i_position == 2:  switchCurrent[p] = 0
   else:                switchCurrent[p] = (V0 - V_selected) / r_on
```

### RelayCoilElm state machine

```
state ∈ {0=wait-on, 1=turning-on, 2=on/wait-off, 3=turning-off}

avgCurrent = exp(-Δt·1000) · avgCurrent + (1-exp(-Δt·1000)) · |coilCurrent|
(≈ 1 ms RC low-pass)

state 0 (idle-off):
   if avgCurrent > onCurrent: state=1, lastTransition=t

state 1 (pick-up debounce):
   if avgCurrent < offCurrent: state=0  (glitch, abort)
   elif t - lastTransition > switchingTimeOn:
      state=2
      if type==LATCHING: switchPosition ^= 1
      else:              switchPosition = 1

state 2 (held-on):
   if avgCurrent < offCurrent: state=3, lastTransition=t

state 3 (drop-out debounce):
   if avgCurrent > onCurrent: state=2  (glitch, abort)
   elif t - lastTransition > switchingTimeOff:
      state=0
      if type != LATCHING: switchPosition = 0

on every switchPosition change:
   setSwitchPositions()  →  for each RelayContactElm with matching label:
                              s2.setPosition(1 - switchPosition, type)
```

### TimeDelayRelayElm state machine

```
stepFinished (executed once per timestep after solve):
   new_powered = (V_Vin - V_gnd) > 2.5
   if new_powered != poweredState:
      lastTransition = t
      poweredState = new_powered
   if t > lastTransition + (poweredState ? onDelay : offDelay):
      onState = poweredState

doStep (each Newton iter):
   stampResistor(in, out, onState ? onResistance : offResistance)
```

## Integration Points

### Depends on

- **Base contract** (`domain-core/element-base`):
  - `CircuitElm` — all relay/motor bases use the stamp/doStep/
    `nonLinear`/`startIteration`/`calculateCurrent`/`reset` lifecycle.
  - `ChipElm` — `TimeDelayRelayElm` uses `setupPins`, `getChipName`,
    `getChipEditInfo`, `isDigitalChip`.
  - `Inductor` — **heavily** reused: `DCMotorElm` owns 2, `RelayElm`
    + `RelayCoilElm` own 1 each. All use
    `Inductor.FLAG_BACK_EULER` (backward Euler chosen over
    trapezoidal for mechanical-coupling stability).
- **Simulator core:**
  - `CircuitSimulator.stampResistor`, `stampVoltageSource`,
    `stampNonLinear`, `stampConductance`, `stampVCCurrentSource`,
    `stampCurrentSource`, `updateVoltageSource`, `stampRightSide`,
    `timeStep`, `t`.
  - `CircuitMath.invertMatrix` — used only by `ThreePhaseMotorElm`
    for the 5×5 mutual-coupling inversion.
- **Cross-element:**
  - `RelayCoilElm` / `MotorProtectionSwitchElm` → `RelayContactElm`
    via label match — the only cross-element direct-coupling pattern
    in the whole element catalog outside of `CompositeElm`.
- **`CustomLogicModel.escape`/`unescape`** — label serialization for
  `RelayCoilElm` (line 128), `RelayContactElm` (line 97).
- **`dialog.EditInfo`** — all elements; `RelayCoilElm` uses
  `Choice` for type (line 369-376); `RelayElm` uses `Choice` for
  coil style (line 486-491) and `Button` for "Use New Model"
  migration (line 470).
- **`Context2d`** — text alignment for rotated labels
  (`RelayCoilElm.java:164`, `RelayContactElm.java:118`,
  `ThreePhaseMotorElm.java:357`).
- **`io.json.UnitParser.parse`** — used by `RelayElm` and
  `RelayCoilElm` to parse `"200 mH"`-style property strings.

### Used by

- **`CircuitElmCreator`** (dump-type dispatch): 178 (RelayElm),
  414 (TimeDelayRelayElm), 415 (DCMotorElm), 425 (RelayCoilElm),
  426 (RelayContactElm), 427 (ThreePhaseMotorElm).
- **`io.json.CircuitElementFactory`** — `"DCMotor"`,
  `"ThreePhaseMotor"`, `"Relay"`, `"RelayCoil"`, `"RelayContact"`,
  `"TimeDelayRelay"`.
- **`MotorProtectionSwitchElm`** (cat-switches) — reaches into
  `RelayContactElm.setPosition(...)` by `label`; no other reverse
  dependency observed.
- **`MenuManager`** / **`Toolbar`** — palette entries under
  "Active Components → Relay" and "Active Components → Motor"
  categories.

### External deps

- `com.google.gwt.canvas.dom.client.Context2d` — text alignment
  for rotated labels.
- `com.google.gwt.user.client.ui.Button` — migration button in
  `RelayElm` EditInfo (line 24 import).
- JDK `java.util.{Map, LinkedHashMap, Vector}` (the last only in
  `RelayCoilElm.java:34` for `setParentList`).

## Issues

1. **`RelayCoilElm.setParentList(Vector<CircuitElm>)` signature mismatch.**
   `CircuitElm.setParentList` (element-base line 572) takes
   `ArrayList<CircuitElm>`. `RelayCoilElm.java:329-331` declares
   `Vector<CircuitElm>` — this **shadows** the base method instead of
   overriding it. Needs confirmation that `elmList` is actually
   populated for this element; otherwise `setSwitchPositions()` would
   NPE on `elmList.size()` at runtime.
2. **Label-coupling has no de-duplication or user-visible warning.**
   Two coils with the same label silently share contacts; last-write
   wins per iteration. A linted "unique-label" pass during
   `analyzeCircuit` would be inexpensive.
3. **`DCMotorElm` EditInfo does not expose `tau`** though it is
   dumped/parsed (`DCMotorElm.java:62, 76`). Dead field — comment at
   line 21 says "reserved for static-friction parameterization".
   Consider removing from dump to avoid drifting the format.
4. **`DCMotorElm` does not reset `angle`/`speed`** in `reset()` (lines
   110-116) — the rotor keeps its last position across a simulator
   reset. Intentional? Inconsistent with, e.g., `RelayCoilElm.reset`
   zeroing `coilCurrent`.
5. **`ThreePhaseMotorElm` hard-codes `Zp = 2` pole-pairs** (line 135)
   — not user-editable, so the motor always has 2 pole pairs
   (4-pole). Real industrial motors range 1..8+.
6. **`ThreePhaseMotorElm` speed integration uses forward Euler in
   `startIteration`** (line 236), decoupled from the coupled-inductor
   MNA integration (which uses BE-companion). Numerically asymmetric —
   stability depends on `Δt` being small relative to mechanical time
   constant `J/b`.
7. **`TimeDelayRelayElm` 2.5 V threshold hard-coded** (line 95). Not
   exposed as EditInfo. A 12 V coil on this element would not trigger
   until V > 2.5 V and the user cannot change that.
8. **`TimeDelayRelayElm.offDelay` has no `> 0` guard** (line 144)
   unlike the resistance guards — negative `offDelay` would fire
   drop-out instantly (same for `onDelay`). Silent anti-feature.
9. **`RelayElm` embeds the same `Inductor`+`coilR` pattern as
   `RelayCoilElm`** but without the 4-state FSM and without label
   coupling — two slightly divergent coil models in the codebase.
   `RelayElm` would benefit from delegating to a shared
   `RelayCoilPhysics` helper (analogous to the `Inductor` helper).
10. **Duplicate `drawThickerLine` helper** in `DCMotorElm.java:230-234`
    and `ThreePhaseMotorElm.java:369-373` — copy-paste. Candidate for
    a static utility on `BaseCircuitElm`.
11. **Duplicate `interpPointFix` helper** in `DCMotorElm.java:236-241`
    and `ThreePhaseMotorElm.java:375-380` — copy-paste.
12. **`RelayContactElm` JSON pin name "no" is wrong when
    `FLAG_NORMALLY_CLOSED`** (line 338) — should be "nc" in that case.
13. **`RelayElm` "old model" math has no physical basis** (lines
    385-406, `d_position = abs(p·pmult)² - 1.3`) — comment
    acknowledges "magic value to balance operate speed with reset
    speed not at all realistically". Kept for backward compatibility;
    hidden behind `switchingTime == 0` which triggers the migration
    button.
14. **`ThreePhaseMotorElm.reset` does NOT reset `angle`/`speed`**
    (lines 120-125) — same inconsistency as `DCMotorElm` #4. Only
    `filteredSpeed` and some arrays are cleared.
15. **Magic dump types 178, 414, 415, 425, 426, 427** — no central
    registry. Adjacent to switch dump types 428/429/430
    (`MotorProtectionSwitchElm`, `DPDTSwitchElm`, `CrossSwitchElm`);
    collision risk when adding new types.
16. **`RelayCoilElm.avgCurrent` smoothing factor (1 kHz, line 289)
    is hard-coded** — a short `switchingTime ≪ 1 ms` would be
    dominated by the smoothing, not by the user's setting.
17. **`RelayElm` `setupPoles()` can skip `switchCurrent` reallocation**
    when `switchCurrent.length == poleCount` (line 135), but it does
    not clear existing entries — a pole-count decrease keeps stale
    currents in the higher indices until a reset.

## Concept boundary: single "electromechanical-elements" concept

A single `electromechanical-elements` concept covering these 6 files is
the right granularity:

- All 6 share the **electromechanical-coupling pattern** — an
  electrical stamp driven by an integrated non-electrical state (rotor
  angle / relay armature / delay timer).
- Three of the six (`RelayCoilElm`, `RelayContactElm`,
  `MotorProtectionSwitchElm`-as-cross-ref) cooperate via the
  **label-linking pattern**; splitting them would force two concepts
  to restate the coupling.
- `RelayElm` vs `RelayCoilElm + RelayContactElm` is a single
  "integrated vs split model-boundary" decision for the same physical
  device class — fits one concept, not two.
- `TimeDelayRelayElm` is conceptually the same family ("a relay with
  timing") even though it extends `ChipElm` — group it under the
  same concept with a note about the divergent base class.
- `DCMotorElm` and `ThreePhaseMotorElm` share the
  "mechanical-state-as-companion-inductor" motif at the level of the
  DC motor and diverge (manual Euler speed integration) for the 3-
  phase one — but the **integration-with-MNA** pattern is common.

If finer granularity is ever required, the clean cuts are:

1. **motors** — `DCMotorElm`, `ThreePhaseMotorElm` (rotor physics,
   back-EMF / torque coupling, inertia integration).
2. **relays-integrated** — `RelayElm` (one-symbol, up to 4 poles).
3. **relays-split** — `RelayCoilElm` + `RelayContactElm` +
   label-coupling pattern (shared with `MotorProtectionSwitchElm`).
4. **timers** — `TimeDelayRelayElm` (chip-based threshold timer).

But the dependency graph and the shared physics/label patterns keep
the single-concept split the more natural choice. Cross-reference the
`switch-elements` concept (cat-switches) for
`MotorProtectionSwitchElm` which uses the same label-driven
`RelayContactElm.setPosition(...)` coupling.
