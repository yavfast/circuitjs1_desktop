# Module Analysis: domain-core / cat-logic-sequential

> **Path:** src/main/java/com/lushprojects/circuitjs1/client/element/ (14 files)
> **Layer:** 3 (depends on element-base, specifically ChipElm)
> **Analyzed:** 2026-04-18
> **Files:** 14 source files, 0 test files

## Purpose

The `logic-sequential` category groups the **state-holding digital elements**
of CircuitJS1 — the 14 concrete `*Elm` classes that either remember prior
inputs across time-steps (latches, flip-flops, counters, shift registers,
sequence generators) or whose outputs depend on a time reference
(monostables, the 555 timer). All 14 classes extend `ChipElm`, which
supplies the multi-pin IC surface (pins, flip/rotate, voltage-source
stamping, `doStep` → `execute()`, `lastClock` slot) described in
`domain-core__element-base.md`.

Two of the files — `HalfAdderElm` and `FullAdderElm` — are purely
combinational (no state, no clock, no edge detection). They are included
here only because their source files happen to sit next to the sequential
ICs; see the re-categorization note below.

Shared mechanics across the category:

1. **ChipElm inheritance for IC plumbing.** Pin layout in `setupPins()`,
   dump = `super.dump() + bits + highVoltage + outputVoltages`, input
   sampling and output-source updates handled by `ChipElm.doStep()`.
2. **`execute()` body implements the sequential logic.** Inputs are read
   from `pins[i].value`; outputs are written via `pins[i].value = …` or
   `writeOutput(i, v)`.
3. **Edge detection via `lastClock`.** `ChipElm.lastClock` is the canonical
   previous-clock sample for every chip; sequential elements combine it
   with `pins[clock].value` to detect the rising/falling edge. Elements
   with multiple edge-sensitive inputs (PisoShift, SipoShift, SeqGen,
   Monostable) keep their own `clockstate` / `prevInputValue` /
   `loadState` booleans instead of reusing `lastClock`.
4. **Reset semantics.** Asynchronous reset (R/CLR) pins are checked
   **after** the clock edge in `execute()` — they override the clocked
   transition on the same timestep. `reset()` additionally clears
   voltage state and any internal counters/indices.

## Per-element catalog

| Element | Extends | Inputs (pins) | Outputs (pins) | Internal state | Dump type | File:lines |
|---|---|---|---|---|---|---|
| LatchElm | ChipElm | bits × I + Ld (load) | bits × O | `lastLoad` bool; per-output `pins[i].state` persisted in dump | 168 | LatchElm.java:27-129 |
| DFlipFlopElm | ChipElm | D, CLK, optional R/S | Q, /Q | `lastClock`, `justLoaded` | 155 | DFlipFlopElm.java:28-202 |
| JKFlipFlopElm | ChipElm | J, CLK, K, optional R | Q, /Q | `lastClock`, `justLoaded` | 156 | JKFlipFlopElm.java:28-189 |
| TFlipFlopElm | ChipElm | T, CLK, optional R/S | Q, /Q | `lastClock` | 193 | TFlipFlopElm.java:28-160 |
| CounterElm | ChipElm | CLK, R, optional U/D | bits × Q (MSB-first) | `lastClock`; counter value **encoded in `pins[i].value`** | 164 | CounterElm.java:29-201 |
| Counter2Elm | ChipElm | CLK, CLR, EnP, EnT, LOAD, bits × I | bits × Q, RCO (ripple-carry-out) | `lastClock`, `carry` | 421 | Counter2Elm.java:28-190 |
| RingCounterElm | ChipElm | CLK, R, optional CE (clock-inhibit) | bits × Q (one-hot) | `lastClock`; one-hot index in `pins[i].value`; `justLoaded` | 163 | RingCounterElm.java:28-177 |
| SeqGenElm | ChipElm | CLK, optional R | Q | `int[] data`, `int bitCount`, `int bitPosition`, `boolean clockstate` | 188 | SeqGenElm.java:33-257 |
| PisoShiftElm | ChipElm | LD, CLK, SER (new-bhvr), bits × D | Q | `boolean[] data`, `int dataIndex` (circular), `clockState`, `loadState` | 186 | PisoShiftElm.java:29-186 |
| SipoShiftElm | ChipElm | D, CLK | bits × Q | `clockstate`; shift register **encoded in `pins[i].value`** | 189 | SipoShiftElm.java:29-138 |
| MonostableElm | ChipElm | CLK (trigger) | Q, /Q | `prevInputValue`, `triggered`, `lastRisingEdge: double`, `delay: double`, `retriggerable: bool` | 194 | MonostableElm.java:29-162 |
| TimerElm (555) | ChipElm | DIS, TRIG, THRES, VCC, CTL, RST, optional GND | OUT | `out`, `triggerSuppressed` (both bool); **no `lastClock`** (pure analog comparator) | 165 | TimerElm.java:28-270 |
| HalfAdderElm | ChipElm | A, B | S, C | **none** (combinational) | 195 | HalfAdderElm.java:26-78 |
| FullAdderElm | ChipElm | bits × A, bits × B, Cin | bits × S, Cout | **none** (combinational ripple-carry per-timestep) | 196 | FullAdderElm.java:27-125 |

## Edge-detection common pattern

All clocked sequential elements in this category share one of two
idioms, both built on **previous-state tracking**:

1. **`ChipElm.lastClock` + clock pin.** Defaults to rising-edge
   (`pin.value && !lastClock`), with per-element inversion flags for
   falling-edge or negative-edge-triggered variants. `lastClock` is
   updated at the end of `execute()` so the next call sees the current
   sample as "previous".
2. **Per-element boolean mirror** for elements that have separate
   LOAD/trigger edges in addition to CLK, or that were contributed
   later (by Edward Calver) and do not use `ChipElm.lastClock`:
   - `SeqGenElm.clockstate` (SeqGenElm.java:41, update at :146/:152)
   - `PisoShiftElm.clockState` + `PisoShiftElm.loadState`
     (PisoShiftElm.java:34-35, update at :126/:141)
   - `SipoShiftElm.clockstate` (SipoShiftElm.java:32, update at :100)
   - `MonostableElm.prevInputValue` (MonostableElm.java:32, update at :96)

Canonical rising-edge snippet (DFlipFlopElm.java:124 / TFlipFlopElm.java:90 /
CounterElm.java:151):

```java
if (pins[clk].value && !lastClock) {
    // … clocked logic …
}
lastClock = pins[clk].value;
```

JKFlipFlop inverts the polarity based on `FLAG_POSITIVE_EDGE`
(JKFlipFlopElm.java:98-102), and CounterElm does so via
`FLAG_NEGATIVE_EDGE` with an XOR-style check
(CounterElm.java:150-151: `pins[0].value != neg && lastClock == neg`).

**Latch distinction (level-sensitive vs edge-triggered):** `LatchElm`
supports **both** modes via `FLAG_NO_EDGE`. When edge-triggered
(default), the load gate only copies inputs on the rising edge of Ld
(`pins[loadPin].value && !lastLoad`); when not edge-triggered, it copies
as long as Ld is high (transparent latch). LatchElm.java:80-86.

## State model per element

- **Boolean Q/Q̄** — DFlipFlopElm, TFlipFlopElm, JKFlipFlopElm,
  MonostableElm: state carried in the output `pins[i].value`, with Q̄
  maintained as `!Q`. No separate fields needed.
- **Integer counter (implicit, encoded in output pins)** — CounterElm
  reads the N-bit counter value out of `pins[lastBit - i].value`
  (CounterElm.java:160-165), increments/decrements, then writes back
  (line 172-173). Counter2Elm does the same (Counter2Elm.java:127-141)
  and additionally keeps an explicit `boolean carry` field for RCO.
- **One-hot index (implicit)** — RingCounterElm scans `pins[i+2].value`
  to locate the currently-high output (RingCounterElm.java:112-114),
  advances modulo `bits`, and rewrites the array (line 116-121). No
  integer index field.
- **Bit-array** — SipoShiftElm stores the shift register directly in
  `pins[DATA_PIN_INDEX + i].value` (no shadow array); PisoShiftElm
  keeps a **`boolean[] data` + circular `dataIndex`** so the output can
  be rotated without physically moving bits (PisoShiftElm.java:32-34).
- **Int[] packed bit-stream + position** — SeqGenElm stores the
  user-programmed sequence in `int[] data` (32-bits-per-int packing)
  with `bitCount` (logical length) and `bitPosition` (playhead)
  (SeqGenElm.java:38-41). `reset()` rewinds `bitPosition` to 0
  (SeqGenElm.java:121-124).
- **Wall-clock double** — MonostableElm tracks `lastRisingEdge` in
  `simulator().t` units and fires until `t > lastRisingEdge + delay`
  (MonostableElm.java:84-95). TimerElm uses analog node voltages
  directly and carries no time field.
- **Output-voltage snapshot in dump** — `pins[i].state = true` signals
  to `ChipElm.dump()` that this output's last voltage should be
  persisted. Latch sets this for every output (LatchElm.java:72), the
  flip-flops for their Q pin (DFlipFlopElm.java:69,
  JKFlipFlopElm.java:70, TFlipFlopElm.java:60), and CounterElm for
  every bit (CounterElm.java:78). This is the **only** way sequential
  state survives a save/reload round-trip — the `lastClock` mirror in
  ChipElm is not itself persisted into the text dump (though JSON adds
  `last_clock`).

## Counter variants (async ripple vs sync; modulus)

- **CounterElm (dump 164)** — **synchronous**, single-chip behavior.
  One shared clock increments/decrements an internal binary value on
  every edge, then writes all bits at once. Up/Down direction via
  optional U/D pin. Modulus set via dialog (0 = natural 2^bits roll);
  `value = (value + modulus) % modulus` (CounterElm.java:168-169).
  Async reset pin (R), polarity configurable via `invertreset` flag.
- **Counter2Elm (dump 421)** — modeled after the 74-series
  **synchronous counters** (74161/163). Adds `EnP`/`EnT` enables,
  synchronous parallel `LOAD`, and `RCO` (ripple-carry-out = `carry &&
  pins[ent].value`). The carry is latched so it stays high for a full
  cycle after reaching `realmod - 1` (Counter2Elm.java:142 & :160).
  `CLR` is synchronous (checked inside the clock-edge block) — but
  reading the code, `!pins[clr].value` is actually applied
  **unconditionally** every execute (Counter2Elm.java:163-168), which
  makes CLR **asynchronous** in practice. See Issues #1.
- **RingCounterElm (dump 163)** — Johnson/ring-mode one-hot shifter.
  Default bit-count 10 (`defaultBitCount()` at line 53). Optional
  `CE` clock-inhibit (only offered when `bits >= 3`), optional
  inverted reset. **Auto-recovers** if all bits are accidentally zero
  (RingCounterElm.java:124-128) — one of only two elements in the
  category that includes self-correcting logic.

**No async ripple counter is implemented in this category.** Async
ripple-counter behavior can be synthesized by chaining `TFlipFlopElm`
instances (that is the canonical ripple cell).

## SeqGen: programmable sequence generator

`SeqGenElm` is a **ROM-backed programmable bit-stream source**.

- Bit-stream packed into `int[] data` (32 bits per int), logical length
  `bitCount`. Edit dialog (SeqGenElm.java:178-186) presents a multi-line
  textarea; any non-`0`/`1` character in the text is ignored — allowing
  the user to paste spaces or commas into the sequence.
- `nextBit()` (line 126-140) advances the playhead on each rising
  clock edge. When `bitPosition >= bitCount`: if `FLAG_PLAY_ONCE`, the
  output stays low; otherwise wraps to `bitPosition = 0`.
- Optional `R` (reset) pin is gated by `FLAG_HAS_RESET`; raising R
  rewinds the playhead and holds it at 0 (line 143-147).
- Legacy-format upgrade: `FLAG_NEW_VERSION = 2` — the constructor at
  :56-67 detects old 8-bit dumps and bit-reverses them so the bitstream
  renders left-to-right.
- Persistence quirk: `dump()` writes `bitCount` followed by every int
  of `data` (line 163-170); `applyJsonState` additionally preserves
  `bitPosition` + `clockstate` so JSON round-trips the playhead too.

## PisoShift / SipoShift: shift register directions

- **PisoShiftElm (Parallel-In Serial-Out, dump 186)** —
  `setupPins` places bits × parallel `D` inputs on the north side,
  one serial `Q` output on the east, plus `LD` (load) and `CLK`.
  Internal storage is a **circular boolean array** `data` with `dataIndex`
  as the read pointer (PisoShiftElm.java:32-34). LOAD rising-edge
  copies `pins[D…]` into `data` and sets `dataIndex = 0` (in new
  behavior) or `-1` (legacy — causes the first CLK after LOAD to
  increment to 0 before reading, effectively "skip the first bit" —
  preserved for backward compatibility with old dumps). CLK rising-edge
  rotates the circular array. **Optional `SER` serial-input** fills
  the vacated slot with the SER pin value when `FLAG_NEW_BEHAVIOR`
  is set (line 145).
- **SipoShiftElm (Serial-In Parallel-Out, dump 189)** — dual of PISO.
  `setupPins` places one serial `D` input on the west, bits × parallel
  `Q` outputs on the north. On each CLK rising edge, shift:
  `pins[DATA + i + 1].value = pins[DATA + i].value` for i from bits-2
  down to 0, then `pins[2].value = pins[0].value`
  (SipoShiftElm.java:102-105). **No load/reset pins** — fully serial.
  Initial contents are restored from the dump line via `readBits` at
  construction.

## Monostable / Timer: pseudo-analog elements

These two elements are the **outliers** of the category. They do not
use `lastClock`; they read node voltages as continuous signals and
apply threshold logic.

### MonostableElm (dump 194)

- `execute()` checks `pins[0].value` (which is sampled by `ChipElm.doStep`
  against the chip's logic threshold), then compares `simulator().t`
  against `lastRisingEdge + delay` to decide when to release the
  one-shot pulse (MonostableElm.java:82-97).
- Retriggerable mode: if high on rising edge, the timer is restarted
  **regardless** of whether already triggered; non-retriggerable
  ignores edges while `triggered == true`.
- `delay` is a user-editable double in seconds (default 0.01s). JSON
  state round-trips `prevInputValue`, `triggered`, `lastRisingEdge`.
- **Still a digital chip** (`isDigitalChip()` inherits default `true`),
  so input sampling and output driving go through `ChipElm`'s standard
  `highVoltage` threshold.

### TimerElm (555) (dump 165)

- `isDigitalChip()` overridden to **false** (line 100-103). This means
  `ChipElm`'s standard digital-input sampling does **not** apply — the
  element reads actual analog node voltages via `getNodeVoltage(…)`.
- `nonLinear() == true` (line 80-82), forcing Newton iteration.
- `stamp()` places a **two-resistor voltage divider** between VCC
  and ground that holds `ctl` at ⅔ Vcc (line 108-109): a 5 kΩ from VCC
  to CTL and a 10 kΩ from CTL to ground (not the textbook three equal
  5 kΩ divider — this is a minor simplification). `stampNonLinear` on
  DIS/OUT/VCC forces re-stamping each Newton step.
- `startIteration()` implements the **comparator logic** as pure
  threshold checks against the CTL voltage (line 138-158):
  - Threshold comparator: if `V(THRES) > V(CTL)` → out = false
  - Trigger comparator: if `½(V(CTL) + Vgnd) > V(TRIG)` → out = true
  - Reset override: if `V(RST) < 0.7 V above ground` → out = false
    (and the trigger is *suppressed* — stored in `triggerSuppressed`
    so that when RST releases, trigger does not re-fire spuriously).
- `doStep()` stamps the output pull-up (`Vcc → OUT` via 1 Ω) when
  high, the DIS-to-ground via 10 Ω when low, and the output-to-ground
  via 1 Ω when low. This is the "output driver" — a poor man's BJT
  pair rendered as two resistors.
- `calculateCurrent()` **manually computes currents** for each of
  VCC / CTL / DIS / OUT / GND pins based on the resistor values
  stamped in `stamp()`/`doStep()` (line 118-133). OUT current is given
  to the element by the solver; the others are computed from Ohm's
  law. This is unusual — most ChipElm subclasses do not override
  `calculateCurrent()`.
- Optional ground pin (`FLAG_GROUND`, default on) allows the 555 to
  reference a non-zero ground; without it, `ground = node 0`.

## HalfAdderElm / FullAdderElm: combinational (re-categorize note)

**Both elements are purely combinational** — no state, no clock, no
edge detection, no `lastClock` usage, no `reset()` override.

- `HalfAdderElm.execute()` (line 66-70):

  ```java
  pins[0].value = pins[2].value ^ pins[3].value;  // S = A ⊕ B
  pins[1].value = pins[2].value && pins[3].value; // C = A · B
  ```

- `FullAdderElm.execute()` (line 79-88) performs N-bit **ripple-carry
  addition within a single timestep** — the for-loop at line 82-86
  propagates the carry through all bit positions in one `execute()`
  call. There is no per-bit time-step delay, so this is a
  combinational N-bit adder with zero latency. Not a ripple cascade
  across time.

**Recommendation: move both adders to the `cat-logic-combinational`
category** (alongside gate and mux chips). The only reason to keep
them here would be structural similarity to `ChipElm` plumbing — but
that applies to literally every chip in the codebase.

## Validation Rules

- `LatchElm` / `CounterElm` / `PisoShiftElm` / `RingCounterElm`
  guard against `bits < 2` (or `< 3` for ring counter) in
  `setChipEditValue` before accepting a new bit-count.
- `SeqGenElm.SeqGenElm(..., StringTokenizer)` clamps `bitCount` to
  `data.length * Integer.SIZE` to recover from truncated dumps
  (line 80-81).
- `SeqGenElm` silently swallows `NoSuchElementException` when the
  dump is corrupted (line 75-77) — the element is left with
  `bitCount = 0`, producing a constant-zero output.
- `MonostableElm`/`TimerElm` do not validate `delay > 0`; a
  zero/negative delay makes the one-shot fire for only one timestep
  (effectively a clock-edge doubler).
- `DFlipFlopElm` / `JKFlipFlopElm` / `RingCounterElm` set the
  `justLoaded = true` flag in the load-from-dump constructor and
  **skip execute()** on the first timestep (DFlipFlopElm.java:103-106,
  JKFlipFlopElm.java:93-96, RingCounterElm.java:102-105). Rationale in
  the comments: initial node voltages right after load are all zeroes,
  which could misinterpret as an async reset. This is the category's
  approach to the "initial condition" problem.
- `Counter2Elm.stamp()`-time pin names are resolved by integer indices
  (`clk, clr, enp, ent, rco, load`) set up in `setupPins`; if
  `setupPins` is called after `setChipEditValue` with a new `bits`
  but before `allocNodes`, the indices go stale. The code orders
  `setupPins(); setPoints(); allocNodes();` to avoid this
  (Counter2Elm.java:106-109).

## Integration Points

### Depends on

- `element-base` — specifically `ChipElm` (all 14 elements) and its
  `Pin` nested class, `lastClock` field, `writeOutput()`, `writeBits()`,
  `readBits()`, `highVoltage`, `getNodeVoltage()`, `setNodeVoltageDirect()`.
- `CircuitDocument` — constructor argument; source of
  `simulator()`, used by `ChipElm.doStep()` and by `TimerElm.stamp()`
  (which directly calls `simulator().stampResistor/stampNonLinear`).
- `CircuitSimulator` — `t` (wall-clock time) used by `MonostableElm`
  for delay timing.
- `StringTokenizer` — constructor overload for loading from text dump.
- `dialog.EditInfo` / `Checkbox` — edit-dialog rows.
- `util.Locale` — translated display names for `CounterElm` / `Counter2Elm`.
- GWT `TextArea` — SeqGen bitstream editor only.

### Used by

- `io.text.CircuitLoader` (via `CircuitElmCreator`) — dispatches by
  dump type 155/156/163/164/165/168/186/188/189/193/194/195/196/421.
- `io.json.JsonCircuitImporter/Exporter` — every class overrides
  `getJsonTypeName` and most override `getJsonProperties` /
  `getJsonState`.
- Menu registry (`CircuitElmCreator` / UI) — keyboard/menu categorization.

### External deps

None beyond what `ChipElm` already pulls in (GWT UI primitives, JDK
`Map`/`ArrayList`/`Integer.parseInt`, `Math`).

## Issues / Questions

1. **Counter2Elm's CLR is effectively asynchronous.** The comment and
   74-series reference implies synchronous CLR, but the code evaluates
   `!pins[clr].value` **outside** the `if (pins[clk].value && !lastClock)`
   block (Counter2Elm.java:163-168). So CLR zeros the outputs every
   `execute()` regardless of clock edge. Either the behavior is wrong
   (doesn't match 74163) or the comment/chip name ("74161-style") is
   aspirational.
2. **No metastability modeled.** None of the flip-flops inject
   setup/hold violations or Q-undefined states even though
   setup/hold timing is a central property of real sequential logic.
   Given the simulator's intended audience (education), this is
   likely deliberate but should be flagged.
3. **Dead-time on reset is inconsistent.** `DFlipFlopElm.reset()` and
   `TFlipFlopElm.reset()` explicitly set `pins[2].value = true` and
   `setNodeVoltageDirect(2, highVoltage)` — directly forcing Q̄ high.
   `JKFlipFlopElm.reset()` does **not** — it relies on the inherited
   `ChipElm.reset()` which clears `pins[i].value` to false. The
   `justLoaded` defer-one-step hack then re-derives Q̄ from !Q.
   Inconsistent initial-condition policy.
4. **`TimerElm.stamp()` uses a 5 k / 10 k divider** (2/3 Vcc at CTL)
   rather than the textbook 3 × 5 kΩ divider (5 k / 10 k instead of
   5 k / 5 k / 5 k). The ratios produce the correct 2/3 V, but the
   absolute impedance of the lower leg is half what a real 555 has.
   This matters only if the user connects something to CTL.
5. **`SeqGenElm.setChipEditValue` integer-divides `bitCount /
   Integer.SIZE` when allocating the data array** (line 206) — rounds
   down, so a 7-bit sequence actually reserves 0 ints and will throw
   `ArrayIndexOutOfBoundsException` on the next write. Should be
   `(bitCount + 31) / 32`.
6. **Two adders are mis-filed in this category.** See the
   re-categorize recommendation above; the analyzer's catalog assumes
   they belong here but they are combinational.
7. **`PisoShiftElm` legacy `dataIndex = -1` sentinel** is a
   compatibility crutch. Worth noting in any refactor — removing it
   silently changes dump-round-trip behavior for old circuits.
8. **`CounterElm` has no LOAD pin** — only increment/decrement via
   clock. Users wanting parallel-load counter behavior must use
   Counter2Elm (which *does* have LOAD).
9. **`RingCounterElm` auto-recovery** (force `pins[2].value = true`
   when all outputs are low, line 124-128) is undocumented user-facing
   behavior; silent "self-healing" can surprise a student trying to
   simulate a stuck-at fault.
10. **`MonostableElm` uses `simulator().t` directly** — not safe if
    the simulator time base is ever reset mid-run without a full
    element reset. (`TimerElm` avoids this by being purely
    voltage-driven.)

## Concept boundary

**Single concept `sequential-logic`** covering 12 of the 14 files:

- Flip-flops: DFlipFlopElm, JKFlipFlopElm, TFlipFlopElm
- Latch: LatchElm
- Counters: CounterElm, Counter2Elm, RingCounterElm
- Shift registers: PisoShiftElm, SipoShiftElm
- Sequence generator: SeqGenElm
- Timing: MonostableElm, TimerElm

**Move to `combinational-logic` concept:** HalfAdderElm, FullAdderElm.

If finer granularity is needed:

1. `sequential-clocked` — flip-flops + latch + counters + shift
   registers + SeqGen (9 files). Share the `lastClock`/`clockstate`
   edge-detection idiom and are pure digital.
2. `sequential-timing` — MonostableElm + TimerElm (2 files). Depend
   on `simulator().t` or analog comparators; TimerElm further
   participates in nonlinear Newton iteration. Conceptually distinct
   from "clocked" sequential logic and closer to analog modeling.
3. `combinational-arithmetic` — HalfAdderElm + FullAdderElm, best
   merged into the broader `cat-logic-combinational` category
   (gates, mux, decoder, etc.).

The dominant unifying abstraction across all 12 sequential elements is
**"ChipElm + state snapshot in `pins[i].value` + edge detector on a
previous-state boolean"** — that is the irreducible contract worth
extracting if the codebase ever gets a `SequentialChipElm` base class.
