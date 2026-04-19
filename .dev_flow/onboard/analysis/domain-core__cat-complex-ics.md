# Module Analysis: domain-core / cat-complex-ics

> **Path:** src/main/java/com/lushprojects/circuitjs1/client/element/ (7 files)
> **Layer:** 2 (SCC-A) — concrete elements
> **Base:** `ChipElm` (all 7 extend it; see `domain-core__element-base.md`)
> **Analyzed:** 2026-04-18

## Purpose

The `complex-ics` category groups seven concrete `ChipElm` subclasses that model
**data-conversion, routing, display, and memory** building blocks sitting one
abstraction layer above primitive gates/flip-flops:

- **ADC / DAC** — analog ↔ digital conversion with a configurable resolution
  and an external V+ reference pin (mixed-signal; `isDigitalChip() == false`).
- **Multiplexer / DeMultiplexer** — parametric N-to-1 / 1-to-N switches driven
  by `selectBitCount` select lines; Mux optionally adds inverted output and
  strobe (enable) pin.
- **SevenSegDecoderElm** — pure combinational BCD→7-seg decoder with a
  hardcoded 16-row truth table (hex-capable).
- **SevenSegElm** — passive **display-only** element (7 / 14 / 16 segments,
  optional DP/colon) with optional LED diode physics per segment.
- **SRAMElm** — addressable static RAM with persistent contents dumped inline
  and editable via textarea / file-load.

All seven reuse ChipElm's sample-inputs → `execute()` → push-outputs loop
(except DAC, SevenSegElm, SRAM which override `doStep()` / `stamp()` directly
because they need analog stamping or internal voltage-source routing).

## Per-element catalog

| Element | Extends | Pins (postCount) | V-sources | Mem/State | Dump-type | Key params | File:lines |
|---|---|---|---|---|---|---|---|
| `ADCElm` | ChipElm | `bits + 2` (N out D0..Dn-1, In, V+) | `bits` | inherited pin values | `167` | `bits` (inherited on ChipElm), V+ pin as reference | ADCElm.java:27-113 |
| `DACElm` | ChipElm | `bits + 2` (N in D0..Dn-1, O, V+) | `1` | none beyond inputs | `166` | `bits`, V+ pin as reference | DACElm.java:27-112 |
| `DeMultiplexerElm` | ChipElm | `2^selectBitCount + selectBitCount + 1` | `2^selectBitCount` | pin values | `185` | `selectBitCount` (1..6) | DeMultiplexerElm.java:29-127 |
| `MultiplexerElm` | ChipElm | `2^sbc + sbc + 1 + [invQ] + [STR]` | 1 or 2 (+invQ) | pin values | `184` | `selectBitCount` (1..8), `FLAG_INVERTED_OUTPUT=2`, `FLAG_STROBE=4` | MultiplexerElm.java:29-175 |
| `SevenSegDecoderElm` | ChipElm | 11 or 12 (with BI) | `7` | pin values | `197` | `FLAG_ENABLE=2` (BI pin), `FLAG_BLANK_F=4` | SevenSegDecoderElm.java:28-172 |
| `SevenSegElm` | ChipElm | `segmentCount` (+1 if diodes) | **0** (passive) | `Diode[] diodes` companion state | `157` | `baseSegmentCount∈{7,14,16}`, `extraSegment∈{NONE,DP,COLON}`, `diodeDirection∈{-1,0,1}` | SevenSegElm.java:33-434 |
| `SRAMElm` | ChipElm | `2 + addressBits + dataBits` | `dataBits` | `HashMap<Integer,Integer> map`, `address`, `internalNodes` | `413` | `addressBits` (2..16), `dataBits` (2..16), contents text | SRAMElm.java:33-297 |

All seven override `getChipName()` and `getJsonTypeName()`. Only four set
`isDigitalChip()==false` (ADC, DAC, SevenSegElm). Only SRAM overrides
`nonLinear() → true`.

## Conversion numerics (ADC / DAC)

### ADCElm (ADCElm.java:59-68)

- `imax = (1 << bits) - 1` — full-scale code (e.g. 255 for 8 bits).
- Output code: `val = imax * V(In) / V(V+)`; cast to int, clamped to `[0, imax]`.
- **No rounding** — explicit comment `// if we round, the half-flash doesn't work`
  (line 61).
- Reference voltage is read **from the V+ post**, not from ChipElm's
  `highVoltage` field; resolution therefore tracks the externally-supplied
  reference (V+ = V_ref_full).
- Bit-pin mapping: LSB at top-left of east side (`pins[i] = new Pin(bits-1-i, SIDE_E, "Di")`);
  pin index `i` encodes bit `i` (LSB=0). MSB visually on top, LSB at bottom.
- Per-bit pin is an independent voltage source (`getVoltageSourceCount() == bits`).

### DACElm (DACElm.java:45-67)

- Input codes are sampled by **voltage threshold** (`getThreshold()` from
  ChipElm, default midway between 0 and highVoltage): bit `i` = 1 iff
  `V(Di) > threshold`.
- Output analog voltage: `v = ival * V(V+) / ((1<<bits) - 1)`.
- Single voltage source drives the "O" pin (`getVoltageSourceCount() == 1`);
  updated every `doStep()` via `simulator().updateVoltageSource(0, getNode(bits), pins[bits].voltSource, v)`.
- DAC overrides `doStep()` directly (line 58) — it **does not use** the
  `execute()` hook, because ChipElm's built-in `doStep` would only propagate
  boolean pin values, not an analog voltage.
- Both ADC and DAC declare `isDigitalChip() == false` (line 84 / 83) so the
  "High Logic Voltage" edit row is suppressed; instead, the **V+ pin is the
  user-facing reference**.
- Open question noted in the source (ADCElm.java:82, DACElm.java:81):
  > "there's already a V+ pin, how does that relate to high logic voltage?
  > figure out later".

## Mux / Demux selection logic

### MultiplexerElm.execute() (MultiplexerElm.java:112-124)

1. Decode select bits (little-endian: `S0` = LSB): `selectedValue = Σ(pins[outputCount+i].value << i)`.
2. Forward `pins[selectedValue].value` to the Q output (`pins[outputPin]`).
3. If `FLAG_STROBE` set and STR pin is high → force output to `false`
   (active-high disable — strobe asserted blanks output).
4. If `FLAG_INVERTED_OUTPUT` set → `pins[outputPin+1].value = !val` written
   to a second voltage source (`getVoltageSourceCount()` returns 2).

Configurable `selectBitCount` in [1, 6] (Demux; ctor default 2, line 50) or
[1, 8] (Mux edit bound). Each toggle rebuilds pins via `setupPins` + re-lays out
via `setPoints`.

### DeMultiplexerElm.execute() (DeMultiplexerElm.java:89-98)

1. Decode select value the same way (little-endian, `S0` = LSB).
2. Clear **all** outputs (`pins[i].value = false` for `i in [0, outputCount)`).
3. Route the `Q` input to the selected output: `pins[val].value = pins[qPin].value`.
4. No enable / strobe line (`hasReset()` is hardcoded `false`, line 34).

Every output is a separate voltage source
(`getVoltageSourceCount() == outputCount == 1<<selectBitCount`).

### Enable / strobe differences

| Feature | MultiplexerElm | DeMultiplexerElm |
|---|---|---|
| Enable/strobe pin | Optional via `FLAG_STROBE` | **None** |
| Active level | Strobe HIGH → blank output | n/a |
| Inverted output | Optional via `FLAG_INVERTED_OUTPUT` (adds `/Q`, bubble, lineOver) | n/a |
| Max select bits | 8 | 6 |

## Seven-segment rendering

### SevenSegDecoderElm: BCD → 7-seg LUT (SevenSegDecoderElm.java:30-47)

- **Hardcoded 16-row `boolean[16][7]` table `symbols`** mapping hex digit →
  segments a..g (line 30). Covers 0..9 and A..F.
- Input decoding (line 112-117): `I3 I2 I1 I0` weighted 8/4/2/1, big-endian on
  the pin list (`I3` = pin index 7, `I0` = pin index 10).
- Blanking rules:
  - `FLAG_ENABLE` (=2) adds a `BI` bubble input (pin 11). Active-low:
    when BI is 0, all outputs are driven to 0 (lines 119-123).
  - `FLAG_BLANK_F` (=4) also blanks when input == 0b1111 (to avoid showing
    `F` in BCD-only mode).
- Outputs a..g are 7 independent voltage sources
  (`getVoltageSourceCount() == 7`).

### SevenSegElm: display-only renderer (SevenSegElm.java)

- **Extends ChipElm** (not GraphicElm): it still owns pins and needs a post
  layout, but `getVoltageSourceCount() == 0` (line 340) — never acts as a
  voltage source.
- Three geometry LUTs for segment lines (x1,y1,x2,y2 tuples, scaled by cell
  spacing `spx`/`spy`):
  - `display7[]` (SevenSegElm.java:159-168) — 7 straight segments.
  - `display14[]` (SevenSegElm.java:187-202) — 14 segments incl. diagonals.
  - `display16[]` (SevenSegElm.java:169-186) — 16 segments.
- Rendering pipeline (SevenSegElm.java:239-281):
  1. Draw chip envelope via `drawChip`.
  2. Two-pass draw of segments: pass 0 = diagonals, pass 1 = straights
     (so straights overlap diagonals) — `drawSegment` builds a hexagonal
     fill via `interpPoint2` offsets.
  3. Optional DP or colon (`drawDecimal` — a rotated square) at an offset
     right of the grid.
- Three drive modes (`diodeDirection`):
  - `0` — pure logic inputs; segment lights red if `pins[i].value`, else
    darkred (dark mode) / lightgray (printable). No common pin.
  - `1` — **common cathode**: extra `gnd` pin; per-segment `Diode[]`
    companions stamped at `stamp()` (line 206-222) with
    `DiodeModel.getModelWithName("default-led")`.
  - `-1` — **common anode**: extra `Vcc` pin; diode orientation reversed
    in `stamp()`.
- With diodes active:
  - `nonLinear() == true` (line 235) — forces Newton iteration.
  - `doStep()` calls each diode's `doStep(diodeDirection * (Vseg - Vcommon))`.
  - `calculateCurrent()` (line 283-299) recomputes `pins[i].current` from
    each diode; `pins[commonPin].current = -Σ(segment currents)`.
  - `stepFinished()` clamps the common-pin current to ±1e12 A and flags
    the simulator non-converged on NaN/overflow (line 301-313) —
    defensive: a direct LED-to-rail short would otherwise explode.
  - `setColor()` scales brightness to current: `w = 255*(1 + 0.2*log(|I|/10mA))`
    (line 315-334) — 10 mA ≈ full red; printable mode draws white→red.
- Backward-compat layout: for the default 7-seg + no DP + no diodes case,
  pins b..g wrap from west to **south side** (line 109-112) to preserve
  old saved-circuit geometry; other modes split evenly between west and east.

## SRAM state model and persistence

### Runtime model (SRAMElm.java:33-97)

- Storage: `HashMap<Integer, Integer> map` — sparse (only written addresses
  stored). Default values for unwritten cells = `0` (line 255).
- Internal nodes: `dataBits` internal nodes (line 227-229) act as the RAM's
  "true" output; each is tied to an internal voltage source, bridged to the
  external D-pin via a resistor whose value switches on OE (line 263):
  - OE asserted (active-low: `V(OE) < threshold`) **and** not in write mode
    → 1 Ω (driven).
  - Otherwise → 1e8 Ω (Hi-Z, lets external bus drive the pin).
- Pin layout (setupPins, line 107-129):
  - Pin 0: `WE` (west, lineOver) — active-low write enable.
  - Pin 1: `OE` (east, lineOver) — active-low output enable.
  - Pins 2..2+addressBits-1: `A0..An-1` (west; index encodes MSB-first).
  - Pins 2+addressBits..end: `D0..Dn-1` (east, `output = true`).
- **nonLinear() always true** (line 99) — because the switched-resistor
  OE stamp changes per-step, forcing iterative analysis.
- Every timestep (doStep, line 243-265):
  1. Sample WE (active-low) and OE (active-low; OE only effective when not
     writing).
  2. Build address MSB-first from A-pins.
  3. `data = map.get(address)` (null → 0); push each bit to its internal
     voltage source (5 V for 1, 0 V for 0) — **hardcoded 5 V**, not
     `highVoltage`.
  4. Re-stamp OE resistor.
- Write path (stepFinished, line 267-279): when WE asserted, sample D-pin
  voltages (`> threshold`) and `map.put(address, data)` — writes happen at
  step end (data latches after solver settles).

### Persistence

- **Inline dump** — contents serialized with the `dump()` output itself
  (SRAMElm.java:73-97). Encoding:
  - Header: `super.dump() + addressBits + dataBits`.
  - Run-length body: for each populated contiguous run starting at address
    `a`, emit `a v0 v1 v2 … -1`; terminate overall list with `-2`.
  - Empty cells are skipped; holes break runs (line 87-93).
- Reconstruction (ctor, line 46-71): parse `addressBits`, `dataBits`, then
  repeatedly read `a`, then values until `-1`, until outer token is `-2`
  (actually the parser exits on first `a < 0`, which catches `-2` too).
- UI editor (getChipEditInfo n=2, line 140-171): multi-line textarea
  `"addr: val val val\n"` — up to 8 values per line on export; parsed via
  `parseNumber` supporting `0x`, `0b`, decimal prefixes (line 182-188).
- UI loader (n=3, line 172-178): `SRAMLoadFile` file picker (when
  `SRAMLoadFile.isSupported()` — browser upload). Uses a static
  `contentsOverride: String` shuttle (line 37) so the load-file callback can
  pre-seed the textarea on next open.
- Address/data widths both capped at **16 bits** (line 191-200) — 64 k × 64 k
  max, but HashMap sparse storage keeps memory bounded to touched cells.

## Validation rules

| Element | Rule | File:line |
|---|---|---|
| ADCElm | `ei.value >= 2` for bits change (silently drops lower) | ADCElm.java:95 |
| DACElm | `ei.value >= 2` for bits change | DACElm.java:94 |
| DeMultiplexerElm | `selectBitCount` clamped to 1..6; invalid ignored silently | DeMultiplexerElm.java:107 |
| DeMultiplexerElm | `selectBitCount == 0` upgraded to 2 in `setupPins` (defensive) | DeMultiplexerElm.java:62 |
| DeMultiplexerElm | Ctor swallows parse failure for `selectBitCount` (empty catch) | DeMultiplexerElm.java:49-50 |
| MultiplexerElm | `selectBitCount` clamped to 1..6 in setter (UI range 1..8) — **mismatch** | MultiplexerElm.java:132, 143 |
| MultiplexerElm | Ctor swallows token parse failure; default=2 | MultiplexerElm.java:51-54 |
| SevenSegElm | ctor: swallows parse errors → defaults via `setDefaults()` | SevenSegElm.java:61-66 |
| SevenSegElm | `stepFinished` clamps common-pin current to ±1e12 A; marks non-converged on NaN | SevenSegElm.java:301-313 |
| SRAMElm | `addressBits`/`dataBits` clamped to [2, 16] | SRAMElm.java:191, 196 |
| SRAMElm | Textarea parse silently swallows malformed lines (per-line try/catch) | SRAMElm.java:206-218 |
| SRAMElm | Dump loop silently swallows parse failures (whole contents may be lost) | SRAMElm.java:52-70 |

## Integration points

### Depends on

- **element-base (this package):**
  - `ChipElm` — parent class; `Pin`, `pins[]`, `setupPins`, `execute`,
    `sizeX/sizeY`, `writeOutput`, `getNodeVoltage`, `getThreshold`,
    `highVoltage`, `cspc`, `drawChip`, `hasFlag`, side constants.
  - `CircuitElm` — inherited: `simulator()`, `getNode(i)`, `flags`,
    `allocNodes`, `dumpValues`, `interpPoint2`, `min`, `max`,
    `displaySettings`, `cirSim`, `setPoints`.
- **root-utils (client/):**
  - `StringTokenizer`, `Point`, `Color`, `Graphics`, `Choice`, `Checkbox`,
    `Button`, `TextArea`.
- **simulator core (client/):**
  - `CircuitSimulator.updateVoltageSource`, `stampVoltageSource`,
    `stampNonLinear`, `stampResistor` — used by DAC, SRAM, SevenSegElm
    (diode path).
- **physics helpers (client/):**
  - `Diode`, `DiodeModel.getModelWithName("default-led")` — SevenSegElm only.
- **I/O (client/):**
  - `SRAMLoadFile` — SRAMElm only; opt-in via `isSupported()`.
- **dialog (Layer 2):**
  - `dialog.EditInfo` — edit-dialog row carrier.

### Used by

- `CircuitElementFactory` — dispatch by dump types 157/166/167/184/185/197/413.
- `CircuitRenderer` — draws via inherited ChipElm `drawChip` + overridden
  `draw` on SevenSegElm.
- `io/text` text serializer — inline dump parsing relies on each element's
  `dump()`.

### External deps

- GWT: `com.google.gwt.user.client.ui.{Button, TextArea}` (SRAMElm only).
- JDK: `java.util.HashMap` (SRAMElm), `java.util.Map` (JSON props),
  `Math.hypot`, `Math.log`.
- No threads; no reflection.

## Issues / Questions

1. **ADC/DAC reference-voltage duality.** Each class carries the explicit
   comment "there's already a V+ pin, how does that relate to high logic
   voltage? figure out later" (ADCElm.java:82, DACElm.java:81). ChipElm's
   `highVoltage` field is effectively dead for these elements since
   `isDigitalChip()==false` suppresses the edit row, but it still participates
   in `dump()` via the ChipElm base dump. Aliasing between the two is a
   latent bug source.
2. **DACElm has no ADCElm-style output dump.** DAC inherits ChipElm's
   `dump()` but stores nothing per-step; the output voltage is recomputed
   every `doStep` from live node voltages, which is fine. But **ADCElm** also
   inherits `dump()` — its output states are saved via ChipElm's
   `pin.state`-based voltage dump (ChipElm.java:349 summary in element-base.md).
   Round-trip preservation of ADC output across save/load was not verified
   from the read range.
3. **MultiplexerElm range inconsistency.** Edit info declares max 8
   (`new EditInfo("# of Select Bits", selectBitCount, 1, 8)`, line 132) but
   setter enforces ≤ 6 (line 143). Users who drag the spinner to 7 or 8 see
   no effect. Also: 1..8 select bits would mean up to 256 inputs — almost
   certainly unintentional as a soft cap.
4. **SRAM hardcoded 5 V output.** doStep pushes `5` V for a logic 1 regardless
   of `highVoltage` (SRAMElm.java:259). Breaks consistency with 3.3 V / 1.8 V
   logic circuits the rest of the chip library supports.
5. **SRAM `contentsOverride` is a static global.** (`public static String
   contentsOverride = null`, line 37) — cross-instance coupling with
   file-upload callback. If two SRAMs exist and one triggers the loader, the
   next `getEditInfo(2)` on **either** one picks up the text. No instance
   keying.
6. **SRAM run-length dump is brittle.** Uses numeric sentinels `-1` (run end)
   and `-2` (EOF); any legitimate negative value written to map would break
   round-trip. Not a live issue because only values in `[0, 2^dataBits - 1]`
   are stored, but there is no guard and `setChipEditValue` accepts any
   `parseNumber` result — a user typing `-5` in the textarea would corrupt
   the dump.
7. **SevenSegElm `setPinCount` calls `allocNodes` before `setupPins`.**
   (Line 411-413) — order is `allocNodes; setupPins; setPoints`. This means
   `allocNodes` runs with possibly-stale `pins[]` references, but works
   because allocNodes uses `getPostCount()` (which reads `pinCount`, set on
   line 405/408). Subtle but correct.
8. **SevenSegElm special-case "backwardCompatibility" layout** (line 109-112)
   — pins b..g flow south only when the user is in the default 7-seg,
   no-DP, no-diode configuration. Any edit nudges pins to west/east layout
   with different world coordinates, potentially misaligning existing
   connections on saved circuits when the user changes *any* of the three
   options.
9. **SevenSegDecoderElm constants have no hex-mode toggle.** The table always
   covers 0..F; `FLAG_BLANK_F` is a per-symbol suppression for `F` only —
   a user wanting a pure-BCD (blank on A..F) decoder has no option.
10. **DeMultiplexerElm has no inverted / strobe flags**, unlike Multiplexer.
    Asymmetry between the two "duals" — the Demux is strictly pass-through
    with no output enable / high-Z mode.
11. **All seven ctors swallow token parse failures with empty catch blocks.**
    Partial dump corruption silently yields default values (bits=4, etc.),
    which is forgiving but hides data loss.
12. **SevenSegElm stamps diodes only at `stamp()` time.** Toggling
    `diodeDirection` live rebuilds pins but does not re-run `stamp()` —
    `CirSim` must re-analyze the circuit for the change to take effect.
    Setter does not trigger re-analysis (line 391-396) — bug candidate.
13. **SRAM `getInternalNodeCount() == dataBits`** doubles the nodeStates
    allocation (line 227). The D-pin and the internal-mirror node are linked
    by the variable resistor; for large `dataBits` this doubles the solver
    matrix size per RAM.
14. **SevenSegDecoderElm.setChipEditValue n=0 returns early without calling
    super** (line 145-150), but n=1 does (line 152-153 implicit fall-through
    to `super.setChipEditValue(n, ei)`). Slight inconsistency — for n=0 the
    super's chip-level settings (High Logic Voltage) are skipped. Acceptable
    because n=0 is exclusively the Blank-Pin checkbox, but may be confusing.

## Concept boundary

**A single `complex-integrated-circuits` concept** is the right granularity.
Rationale:

- All 7 files share the same contract (ChipElm subclass, `setupPins` +
  `execute`/`doStep` + `getVoltageSourceCount` + `getDumpType` + JSON props
  pattern), with the only axis of variation being which hook they use.
- The subgroup split (mixed-signal / routing / display / memory) is
  semantic, not structural — they do not form independent type hierarchies
  or dependency clusters; they all depend on the same ChipElm surface.
- The catalog table above is the load-bearing output; splitting it across
  concepts forces cross-referencing for essentially the same contract.
- Shared analytical patterns (reference-voltage duality, hardcoded 5 V in
  SRAM, flag-based pin additions, empty-catch tokenizing) are best
  documented once per category rather than fragmented across concepts.

If finer granularity is ever needed, candidate cuts are:

1. `ic-data-converters` (ADC, DAC) — mixed-signal, V+ reference pin.
2. `ic-routing` (Multiplexer, DeMultiplexer) — parametric select-bit logic.
3. `ic-display` (SevenSegDecoder, SevenSeg) — one combinational + one
   passive-with-diodes pair.
4. `ic-memory` (SRAM) — the only element with persistent editable state and
   custom serialization.

But the 4-way split would replicate the same ChipElm-contract preamble four
times; keeping the single category is the pragmatic choice.
