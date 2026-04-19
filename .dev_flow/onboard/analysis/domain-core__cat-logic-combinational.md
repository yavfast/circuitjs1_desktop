# Module Analysis: domain-core / element-categories / logic-combinational

> **Path:** src/main/java/com/lushprojects/circuitjs1/client/element/ (10 files, 1843 LOC total)
> **Layer:** 2 (SCC-A)
> **Analyzed:** 2026-04-18

## Purpose

Discrete-logic element family: the **stateless gates** (AND/OR/NAND/NOR/XOR +
Inverter), the **Schmitt trigger** hysteretic variant, the **tri-state
buffer** (for bus-driving), the **delay buffer** (propagation-delay
simulation), and the **clock source** (logic square-wave rail).

Unlike the `chips` category (which extends `ChipElm` and packages all
digital behaviour into `execute()` + a pin grid), these elements are
**drawn as standalone gate symbols** and — except for `ClockElm` — extend
`CircuitElm` directly. They all share one simulation pattern:

- 1 voltage-source output stamped to ground (`stampVoltageSource`),
- inputs are **pure voltage probes** (`getNodeVoltage` only, no current
  path — `getConnection` returns false, `hasGroundConnection` true only
  for the output node),
- per-step the element thresholds each input at `highVoltage/2`,
  computes a boolean function, and `updateVoltageSource()`s the output
  to `0` or `highVoltage`.

`ClockElm` is the outlier: it's a `RailElm` (voltage source with a square
waveform), parked in this category because its **role** is a digital
clock signal, not because its implementation is logic-based.

`InvertingSchmittElm` is included in this listing even though the task
brief named 10 files — it is the dedicated Schmitt-trigger gate element
and sits alongside the 10 per the `ls` listing. The 10 named in the
brief cover the combinational core; InvertingSchmitt is the 11th file
in the same subfolder group.

## Per-element catalog

| Element | Extends | Posts (in→out) | V-sources | Internal nodes | Dump-type | Parameters | File:lines |
|---|---|---|---|---|---|---|---|
| **GateElm** (abstract) | CircuitElm | N+1 (N in, 1 out) | 1 | 0 | — | `inputCount`, `highVoltage`, `FLAG_SMALL`, `FLAG_SCHMITT`, `FLAG_INVERT_INPUTS` | GateElm.java:32-386 |
| **AndGateElm** | GateElm | N+1 | 1 | 0 | `150` | (inherited) | AndGateElm.java:28-128 |
| **NandGateElm** | AndGateElm | N+1 | 1 | 0 | `151` | (inherited, `isInverting()=true`) | NandGateElm.java:26-54 |
| **OrGateElm** | GateElm | N+1 | 1 | 0 | `152` | (inherited) | OrGateElm.java:28-152 |
| **NorGateElm** | OrGateElm | N+1 | 1 | 0 | `153` | (inherited, `isInverting()=true`) | NorGateElm.java:26-54 |
| **XorGateElm** | OrGateElm | N+1 | 1 | 0 | `154` | (inherited; hides `FLAG_INVERT_INPUTS` row) | XorGateElm.java:27-72 |
| **InverterElm** | CircuitElm | 2 (in, out) | 1 | 0 | `'I'` (73) | `slewRate` (V/ns), `highVoltage` | InverterElm.java:31-207 |
| **InvertingSchmittElm** | CircuitElm | 2 (in, out) | 1 | 0 | `183` | `slewRate`, `lowerTrigger`, `upperTrigger`, `logicOnLevel`, `logicOffLevel` | InvertingSchmittElm.java:33-248 |
| **TriStateElm** | CircuitElm | 3 (in, out, enable) | 1 | 1 | `180` | `r_on`, `r_off`, `r_off_ground`, `highVoltage`; `FLAG_FLIP`, `FLAG_FLIP_X`, `FLAG_FLIP_Y` | TriStateElm.java:32-293 |
| **DelayBufferElm** | CircuitElm | 2 (in, out) | 1 | 0 | `422` | `delay` (s), `threshold` (V), `highVoltage` | DelayBufferElm.java:32-203 |
| **ClockElm** | RailElm | 1 (out) | 1 | 0 | `'R'` (82, inherited) | waveform fixed to `WF_SQUARE`; `maxVoltage=2.5`, `bias=2.5`, `frequency=100 Hz`; sets `RailElm.FLAG_CLOCK=1` | ClockElm.java:25-46 |

Notes:

- GateElm is **never instantiated directly**; `getDumpType()` is
  implemented only by leaf subclasses.
- `NandGateElm extends AndGateElm` and `NorGateElm extends OrGateElm`
  — negation is realised by overriding `isInverting()` to `true`,
  which (a) flips the computed output in `GateElm.doStep()` (line 261)
  and (b) draws the output bubble (`drawThickCircle` at `pcircle`,
  GateElm.java:201-202).
- `XorGateElm extends OrGateElm` — the XOR/NOR bubble-curve is drawn
  inside `OrGateElm.drawGatePolygon` guarded by
  `this instanceof XorGateElm` (OrGateElm.java:64-70). A subtle
  "parent knows about child" coupling.
- `ClockElm.getDumpClass() == RailElm.class` (ClockElm.java:34-36),
  which means clocks are serialised using the rail `'R'` token plus
  the `FLAG_CLOCK` bit — there is no dedicated clock dump code.

## GateElm base contract — how concrete gates plug in

`GateElm` (abstract, GateElm.java:32) factors out everything shared by
N-input gates. Subclasses provide exactly three overrides:

| Override point | Return / behaviour | Example |
|---|---|---|
| `abstract boolean calcFunction()` | AND/OR/XOR fold of `getInput(i)` for `i = 0..inputCount-1` | `AndGateElm.calcFunction` line 108-114: `f=true; f &= getInput(i)` |
| `abstract String getGateName()` | short name for `getInfo` | `"AND gate"` |
| `boolean isInverting()` | default `false`; override to `true` for N*/bubble variants | `NandGateElm.isInverting` line 36-38 |

Optional overrides: `getGateText()` (European-symbol letter, e.g. `"&"`
for AND, `"≥1"` for OR, `"=1"` for XOR), `drawGatePolygon(g)` (shape of
the gate body), `setPoints()` (to add inverter bubble geometry),
`getLeadAdjustment(int)` (for OR-style staggered input leads,
OrGateElm.java:76-86).

### Fixed structural contract inherited from GateElm

- **Posts:** `getPostCount() == inputCount + 1` (line 215-217); inputs
  are posts `0..inputCount-1`, output is post `inputCount`.
- **Voltage sources:** 1 (line 225-227), stamped to the output post.
- **`getConnection(n1,n2) == false`** (line 322-325) — inputs are
  high-impedance probes.
- **`hasGroundConnection(inputCount) == true`** (line 327-329) — the
  output is a grounded voltage source.
- **`getCurrentIntoNode(n)`** returns `current` only at the output post
  (line 331-335); inputs draw no current.
- **Dump format:** `super.dump() + inputCount + lastOutputVoltage + highVoltage`
  (line 89-91). Note: `lastOutputVoltage` is read as
  `getNodeVoltage(inputCount)` — the last settled output voltage — so
  the dump can restore state without re-computing from inputs.
- **State restore:** `setupVolts()` (line 139-146) seeds input node
  voltages to whatever keeps `lastOutput` stable across reload, because
  the gate only remembers its last output, not individual inputs.

### Oscillation damping

`GateElm.doStep()` (line 259-283) runs per Newton iteration. It counts
consecutive output flips (`lastOutput == !f`); after `oscillationCount > 50`
it randomly holds the previous output (`RandomUtils.getRand(10) > 5`) to
break feedback loops. This is an Atanua-inspired workaround for the fact
that a pure combinational loop with zero delay has no stable fixed point.
The counter resets when `lastTime != simulator.t` (i.e. once per real
timestep, not per Newton pass).

## Logic-level threshold / output voltage

- **Default `highVoltage = 5.0 V`.** GateElm stores it as an instance
  field initialised from the static `GateElm.lastHighVoltage = 5`
  (line 39), which is mutated by the edit dialog so the **most recently
  edited gate's voltage becomes the default for new gates** (line 306).
  Both `InverterElm` and `TriStateElm` also seed from
  `GateElm.lastHighVoltage` (InverterElm.java:41, TriStateElm.java:50).
- **Logic-high threshold = `highVoltage * 0.5`.** `GateElm.getInput(x)`
  (line 245-252): `return getNodeVoltage(x) > highVoltage * .5`.
  InverterElm uses the same (InverterElm.java:138). DelayBufferElm uses
  an explicit `threshold` field defaulting to `2.5 V` (not tied to
  `highVoltage`; DelayBufferElm.java:128-129).
- **Schmitt thresholds** (GateElm Schmitt input mode, line 247-251):
  hysteresis window `[0.35·Vh, 0.55·Vh]`. The threshold the input is
  compared against depends on `inputStates[x]` (the previous classified
  level): `0.55·Vh` when currently low (needs to rise above), `0.35·Vh`
  when currently high (must fall below). Stored per-input in the
  `inputStates[]` array allocated by `setPoints()` (line 103).
- **Invert-inputs option** (`FLAG_INVERT_INPUTS`, bit 2): toggles bubble
  at each input; `getInput` flips its return based on the flag
  (line 246-251). XorGateElm hides this edit row
  (XorGateElm.java:53-58) — inverting individual inputs on XOR is
  ambiguous.
- **Output level:** `f ? highVoltage : 0` (GateElm.java:281). No
  mid-level output — the gate snaps between rails.

## Inverter vs. InvertingSchmitt distinctions

Both are 2-post, 1-voltage-source elements, but they implement
**different output shaping**:

| Feature | InverterElm | InvertingSchmittElm |
|---|---|---|
| Threshold | single: `highVoltage * 0.5` | hysteretic: `lowerTrigger` (1.66 V default) / `upperTrigger` (3.33 V default) |
| State memory | `lastOutputVoltage` (for slew limiting) | `state: boolean` (is output currently high?) |
| High/low rails | `highVoltage` / 0 | `logicOnLevel` / `logicOffLevel` (both user-editable) |
| Slew rate | `slewRate` V/ns, default 0.5 — clamps output step to `slewRate · timeStep · 1e9` | same formula (line 152-153) |
| Draw | triangle + bubble | triangle + bubble + Schmitt-hysteresis glyph (`symbolPoly` from `getSchmittPolygon(1, .3f)`, line 118) |
| Dump type | `'I'` (73) | `183` |

**Note:** The non-inverting Schmitt trigger is built via GateElm's
`FLAG_SCHMITT` option on a single-input gate — there is no separate
`SchmittElm`. The `InvertingSchmittElm` exists because the
inverter-with-hysteresis combo has distinct triggers, output-rail
parameters, and symbology that don't cleanly fit GateElm's parameter set.

## TriState Hi-Z realisation

`TriStateElm` (TriStateElm.java:32) is the only element here that
uses a **resistor-network model** instead of direct voltage driving.
Structure:

- 3 external posts: `0` = input, `1` = output, `2` = enable
  (TriStateElm.java:156-158 comment; `getPost` line 213-215).
- 1 internal node: `3` (TriStateElm.java:205-207
  `getInternalNodeCount() == 1`).
- 1 voltage source on the **internal** node 3, not the output
  (`stampVoltageSource(0, getNode(3), voltSource)`, line 164).

Topology: `VS(node3) — R(r_on | r_off) — node1 (output) — Rpulldown — ground`.

**Per-step stamping** (`doStep`, line 169-182):

1. `open = getNodeVoltage(2) < highVoltage * 0.5` — enable input below
   threshold ⇒ buffer disabled.
2. `resistance = open ? r_off : r_on` (default `1e10 Ω` off, `0.1 Ω` on).
3. `stampResistor(node3, node1, resistance)` — rebuilds every step.
4. `stampResistor(node1, ground, r_off_ground)` if non-zero — the
   pulldown so that a disabled tri-state with nothing else driving the
   bus floats toward 0 V instead of showing junk. Default `1e8 Ω` in
   constructor (line 46), but the dump-reading constructor defaults
   `r_off_ground = 0` (line 57) — pulldown off by default when loaded
   from file.
5. `updateVoltageSource(0, node3, voltSource, input > highVoltage/2 ? highVoltage : 0)`.

Because the resistance switches between two discrete values, the element
declares `nonLinear() == true` (line 152-154) and also calls
`stampNonLinear(node3)` / `stampNonLinear(node1)` in `stamp()`
(line 163-167) so the solver re-stamps the matrix every Newton iteration.

`calculateCurrent()` (line 134-143) reconstructs the output current as
`I = (V(node3) − V(node1)) / resistance − V(node1)/r_off_ground` —
current flowing from the internal voltage source to the output, minus
the leakage through the pulldown.

`getConnection == false` (line 227-229) — no current path between
external posts from the analyser's perspective;
`hasGroundConnection(1) == true` only for the output
(line 231-233).

## DelayBuffer propagation-delay mechanism

Despite the task brief suggesting a "ring buffer of past samples",
`DelayBufferElm` (DelayBufferElm.java:32) implements a much simpler
**edge-timeout gate**, not a queue. Logic (line 126-136):

```
boolean inState  = V(in)  > threshold;
boolean outState = V(out) > threshold;
if (inState != outState) {
    if (sim.t >= delayEndTime)
        outState = inState;          // delay expired, propagate
} else {
    delayEndTime = sim.t + delay;    // stable — arm next delay
}
updateVoltageSource(out, outState ? highVoltage : 0);
```

Behaviour:

- While input and output agree, the element continually re-arms
  `delayEndTime = now + delay` (line 134). This means any input edge
  starts a fresh countdown.
- When a fresh edge creates a disagreement, the output is *held* at
  its current state until `sim.t` crosses `delayEndTime`, at which
  point the output snaps to match the input.
- **Consequence:** a glitch shorter than `delay` propagates only if it
  happens to straddle the `delayEndTime` boundary. There is no FIFO /
  queue — only one pending edge at a time.
- Dump: `delay threshold highVoltage` (line 57).

Compared to a true ring-buffer delay (which would sample the input on a
regular grid and replay the trace `delay` seconds later), this scheme
is cheap (O(1) state) and faithful for stable signals but approximate
around fast pulses.

## ClockElm: logic clock source (why it sits in logic-combinational)

`ClockElm` (ClockElm.java:25-46) is trivially small — 3 lines of actual
behaviour in the constructor:

```java
super(circuitDocument, xx, yy, Waveform.WF_SQUARE);
waveformInstance.maxVoltage = 2.5;
waveformInstance.bias = 2.5;       // ⇒ output swings 0 .. 5 V
waveformInstance.frequency = 100;  // Hz
flags |= FLAG_CLOCK;               // RailElm.FLAG_CLOCK = 1
```

It **extends `RailElm`**, which extends `VoltageElm` (the waveform-driven
source base — see `cat-sources.md`). The actual square-wave generation
is delegated to `SquareWaveform` via the `Waveform` strategy.

**Why it lives in logic-combinational, not sources:**

1. **Semantic role:** it produces a clean 0 ↔ `highVoltage` square wave
   biased so both rails are valid logic levels — the output is intended
   to feed flip-flops, counters, and gates, not drive analog circuitry.
2. **`FLAG_CLOCK` flag** (RailElm.java:48): set by `ClockElm` so it can
   be distinguished from a generic square-wave rail; used elsewhere
   (CircuitDocument/editor) for clock-driven behaviour.
3. **Persistence via RailElm:** `getDumpClass() == RailElm.class` means
   a clock is serialised as `R x1 y1 x2 y2 flags waveform freq maxV bias phase duty`
   with `FLAG_CLOCK` set. The `'R'` dump-token plus the clock-flag is
   the bit pattern that round-trips a ClockElm through save/load.
4. **Categorisation:** in menus the element appears under the **Logic
   Gates / Digital** group even though its structural parent is the
   voltage-source family — the category reflects the **user-facing
   intent** (a clock for digital circuits), not the class hierarchy.

## N-input logic semantics (input aggregation)

All GateElm subclasses aggregate N inputs via a simple fold. `getInput(i)`
returns the boolean logic value of the i-th input (with Schmitt hysteresis
and `INVERT_INPUTS` applied); `calcFunction()` folds:

| Gate | Fold | File:line |
|---|---|---|
| AND | `f = true; for i: f &= getInput(i)` | AndGateElm.java:108-114 |
| OR | `f = false; for i: f \|= getInput(i)` | OrGateElm.java:132-138 |
| XOR | `f = false; for i: f ^= getInput(i)` | XorGateElm.java:45-51 |
| NAND | same as AND, then `isInverting()` ⇒ `f = !f` in `doStep` | inherited |
| NOR | same as OR, then `!f` | inherited |

`inputCount` is user-editable (1–8, GateElm.java:286-288). Changing it
calls `allocNodes(); setupVolts(); setPoints()` (line 300-303) —
reallocates node storage, seeds consistent inputs, rebuilds geometry.
Pins are laid out vertically, centred on the element body: `hs * i0`
where `i0 = -inputCount/2 .. +inputCount/2` skipping zero for even
counts (GateElm.java:118-131).

### Layering of gate threshold logic

```
getNodeVoltage(i)                    ← raw analog voltage on input post
   │
   ▼
compare against highVoltage * 0.5     ← simple threshold
  or against [0.35..0.55] * highVoltage with inputStates[i] ← Schmitt hysteresis
   │
   ▼
XOR with FLAG_INVERT_INPUTS?          ← per-input bubble
   │
   ▼
getInput(i) : boolean                 ← seen by calcFunction
   │
   ▼
AND/OR/XOR fold of all inputs         ← calcFunction()
   │
   ▼
f = isInverting() ? !f : f            ← N→N* inversion (NAND/NOR)
   │
   ▼
oscillation damping (RandomUtils)     ← 50+ consecutive flips ⇒ hold
   │
   ▼
updateVoltageSource(out, f ? highVoltage : 0)
```

## Validation rules

- `GateElm` constructor parses `inputCount` from the dump; no lower-bound
  check in the dump ctor, but `setEditValue` enforces `ei.value >= 1`
  (line 299). Minimum effective N is 1.
- `highVoltage` accepts `[1,10]` V through the edit dialog
  (GateElm.java:290). No clamp when loading from dump.
- `InverterElm` / `InvertingSchmittElm` / `DelayBufferElm` swallow
  parse errors from their dump constructors in bare `catch (Exception e)`
  blocks — missing tokens silently fall back to the in-constructor
  defaults. Means older dump files forward-compatible; means malformed
  dumps don't error.
- `TriStateElm` clamps `r_on`, `r_off`, `r_off_ground` to `> 0` in
  `setEditValue` (line 249-254); dump ctor sets `r_off_ground = 0`
  which disables the pulldown. This is an asymmetry between
  "new" and "loaded" tri-states.
- `InvertingSchmittElm.setEditValue` auto-sorts the two triggers so
  `upperTrigger >= lowerTrigger` (line 201-207) — user can't create
  an inverted hysteresis window.
- `GateElm.stamp()` stamps exactly one voltage source at
  `getNode(inputCount)`; there is no consistency check that
  `getVoltageSourceCount()` really returns 1 (it is hard-coded, so
  safe).
- `ClockElm` overrides `getDumpClass()` but **not** `getDumpType()` —
  relies on inherited `RailElm.getDumpType() == 'R'` (82). If a future
  refactor changes `RailElm`'s dump type, clocks go with it.

## State transitions

All gates are stateless at the logic level (output is a pure function of
inputs), but several elements carry auxiliary state:

- `GateElm.lastOutput` — last settled output, used only to seed
  `setupVolts()` after load and to feed oscillation detection.
  Not part of the logic state machine.
- `GateElm.inputStates[i]` — per-input Schmitt hysteresis latch.
- `GateElm.oscillationCount`, `lastTime` — oscillation-damping state;
  reset on any output-state change that isn't a flip.
- `InvertingSchmittElm.state` — Schmitt output latch (true = high).
  Transitions: `state=true` when `V(in) < lowerTrigger`; `state=false`
  when `V(in) > upperTrigger`. Also carries `lastOutputVoltage` via
  `getNodeVoltage(1)` read at the top of `doStep` for slew clamp.
- `InverterElm.lastOutputVoltage` — captured in `startIteration()`
  (line 132-134), used as the anchor for the slew-rate clamp. This is
  the **only** element in the category that overrides
  `startIteration()`.
- `TriStateElm.open` — boolean mirror of the current enable state;
  drives which resistance value is stamped this step; also shown in
  `getInfo()`.
- `DelayBufferElm.delayEndTime` — absolute sim-time when the next
  output edge is allowed to propagate. Reset whenever `in == out`.
- `ClockElm` — no per-step state of its own; all state is in the owned
  `SquareWaveform` (see `waveforms.md`).

## Integration points

### Depends on

- **element-base:** `CircuitElm` (all 10 extend it, directly or via
  `GateElm` / `RailElm`); `ElmGeometry` (via `geom()`); `BaseCircuitElm`
  static helpers (`interpPoint`, `createPolygon`, `drawThickPolygon`,
  `drawDots`, `calcLeads`, `sign`).
- **sources category (indirect):** `ClockElm → RailElm → VoltageElm`
  and its `Waveform` strategy (`WF_SQUARE`); `SquareWaveform` in
  `element/waveform/`.
- **client/ root-level:** `CircuitDocument`, `CircuitSimulator`
  (`stampVoltageSource`, `updateVoltageSource`, `stampResistor`,
  `stampNonLinear`, `timeStep`, `.t`), `RandomUtils` (oscillation
  damping), `Graphics`/`Point`/`Polygon`.
- **dialog:** `dialog.EditInfo` for the editor rows.
- **io.json:** `io.json.UnitParser` via `GateElm.applyJsonProperties`
  (line 370-371) for parsing `"5 V"`-style strings.

### Used by

- **ChipElm / CustomLogicElm** — no direct dependency, but they share
  the same logic-high convention (`highVoltage`) so a gate and a chip
  can interoperate at the same rail voltage.
- **CircuitElmCreator / CircuitLoader** — dispatch on dump types
  `'I'` (73), `150..154`, `180`, `183`, `422`, and `'R'`+`FLAG_CLOCK`
  to construct these elements.
- **Scope / Editor** — all are scope-viewable
  (postCount = 2 or 3 trips the default gate and tri-state, N+1 for
  gates doesn't by default but override is implicit in category
  convention — `canViewInScope()` on 3-post TriState is inherited and
  returns false; GateElm with inputCount ≥ 2 likewise returns false by
  the `postCount ≤ 2` rule unless overridden).

## Issues / Questions

1. **`OrGateElm.drawGatePolygon` tests `this instanceof XorGateElm`**
   (OrGateElm.java:64-70). The parent knows about the child — classical
   open/closed violation. A cleaner design would have
   `XorGateElm.drawGatePolygon` override and call `super` or reshape.
2. **`GateElm.lastHighVoltage` is static** (line 39). Mutating the
   edit-dialog value affects **future** gates across the whole JVM
   session — global mutable state. Same pattern applies to
   `GateElm.lastSchmitt` (line 40). Makes deterministic tests hard.
3. **Dump ctor of `TriStateElm` sets `r_off_ground = 0`** (line 57)
   whereas the fresh-element ctor sets it to `1e8` (line 46). A saved
   and reloaded tri-state behaves differently from a freshly placed
   one. Round-trip is not identity.
4. **`DelayBufferElm` is not a queue.** The one-edge-at-a-time model
   documented above (line 126-136) misnames itself: a pulse of width
   shorter than `delay` may be absorbed entirely. If downstream code
   assumes proper FIFO behaviour (e.g. glitch-free shift-register
   chains) this will surprise users.
5. **`GateElm.oscillationCount` uses `RandomUtils`** (line 272).
   Non-deterministic simulation — replays can diverge. Probably OK in
   an interactive simulator but hostile to test harnesses.
6. **`ClockElm` is persisted as a RailElm.** Only the `FLAG_CLOCK` bit
   distinguishes it. If user code or importers strip flags they silently
   convert clocks to plain rails. No separate dump type → no
   future-proofing for clock-specific parameters.
7. **Parameter-storage inconsistency:** GateElm stores `highVoltage`,
   InverterElm stores `highVoltage` + `slewRate`, InvertingSchmittElm
   stores `logicOnLevel` / `logicOffLevel` (two rails, not one
   rail + ground), DelayBufferElm stores `highVoltage` + `threshold`.
   Three different parameter schemas in one category makes cross-element
   behaviour (e.g. a chain Gate → Buffer → Schmitt) hard to keep
   coherent — user must set voltages on every element.
8. **GateElm dumps the last output voltage rather than the boolean
   state** (line 90: `getNodeVoltage(inputCount)`). On reload,
   `lastOutput = lastOutputVoltage > highVoltage * .5` (line 68) — if
   the user edits `highVoltage` downward in the saved dump file by hand,
   the output state may flip on load.
9. **`InverterElm` is the only element in the category that overrides
   `startIteration()`** (line 132-134). Why doesn't `InvertingSchmittElm`
   or `GateElm` also use this hook for slew-rate / oscillation state
   capture? The Schmitt variant reads `getNodeVoltage(1)` inside
   `doStep()` instead — works but timing is subtly different (value is
   mid-solve rather than pre-solve).
10. **No `NotGateElm`.** A 1-input AND/OR/NAND/NOR configuration would
    serve the purpose, but the project uses the standalone
    `InverterElm` (`'I'` dump type) because it also provides slew-rate
    limiting, which GateElm does not.

## Concept boundary: single "combinational-logic" concept

All 10 (11 with `InvertingSchmittElm`) files collaborate around a
**single design intent**: stamp a 1-output voltage source whose value
is `highVoltage` or `0` (or mid-rail during slew), driven by a
thresholded Boolean function of one or more input-node voltages.
They share:

- the rail-stamping pattern (`stampVoltageSource` + `updateVoltageSource`),
- the `highVoltage / 2` threshold convention,
- the "no current through inputs" invariant
  (`getConnection == false`, `hasGroundConnection(out) == true`,
  `getCurrentIntoNode` only returns current at the output),
- the serialise-the-last-output-voltage restoration trick.

`ClockElm` is a deliberate outlier — included **by role**, not by
structure — and fits as a producer side of the same logic-signal
protocol.

**Single concept: `combinational-logic`** covering all 10 (+ Schmitt)
files. The clean internal cut, if finer granularity were ever required:

1. **gates** (GateElm + 5 leaves) — N-input fold pattern.
2. **inverters-schmitt** (InverterElm + InvertingSchmittElm) — 1-input
   inverting with optional hysteresis + slew.
3. **tri-state** (TriStateElm) — resistor-network Hi-Z.
4. **timing** (DelayBufferElm + ClockElm) — time-driven logic signal
   generators.

But the dependency graph does not force this split: all 11 files are
within the same SCC, rely on the same set of simulator entry points, and
are created by the same `CircuitElmCreator` dump dispatch. The category
behaves as one concept.
