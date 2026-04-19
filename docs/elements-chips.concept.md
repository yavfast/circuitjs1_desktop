# Complex IC Elements  {#C_ECH}

> **Code:** C_ECH
> **Status:** draft
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** OnboardDocGen
>
> **Depends on:** [C_ELB](./element-base.concept.md) (ChipElm base), [C_UTL](./util-locale-log.concept.md), [C_GEO](./geometry.concept.md), [C_RND](./rendering-primitives.concept.md)
> **Used by:** circuit element factory, renderer, file I/O glue
> **Spike:** —
> **Specification:** [SP_ECH](./elements-chips.sp.md)
> **Plan:** [elements-chips.plan.md](./elements-chips.plan.md)
>
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-complex-ics.md](../.dev_flow/onboard/analysis/domain-core__cat-complex-ics.md)
>
> The `complex-ics` concept covers seven concrete `ChipElm` subclasses that
> model data-conversion (ADC/DAC), routing (Mux/DeMux), display
> (SevenSegDecoder, SevenSeg), and memory (SRAM) IC building blocks. They sit
> one abstraction layer above primitive gates/flip-flops and share the
> ChipElm pin/execute/stamp contract with element-specific extensions.

## 1. Philosophy  {#C_ECH_01}

### 1.1. Core Principle  {#C_ECH_01_01}

The category packages mid-level digital/mixed-signal IC abstractions — ADC,
DAC, multiplexer, demultiplexer, 7-segment decoder, 7-segment display and
SRAM — as a **uniform family of ChipElm subclasses**. Each element inherits
the chip-envelope rendering, pin-side layout, input-sampling `execute()` /
`doStep()` hook, and JSON/text dump protocol; the only per-element work is
the physics-specific override (analog stamping, table lookup, address
decode, segment geometry, memory map mutation).

The guiding principle is: **one contract, many hooks**. Elements that need
only boolean logic (Mux, DeMux, SevenSegDecoder) use the built-in
`execute()` loop; elements that need analog stamping (DAC), per-diode
nonlinearity (SevenSeg with LEDs) or resistor-network switching (SRAM OE
gate) override `doStep()` directly and opt into `nonLinear() == true`.

### 1.2. Design Constraints  {#C_ECH_01_02}

- Every element emits a stable dump-type code so text-format persistence is
  version-compatible (codes 157, 166, 167, 184, 185, 197, 413).
- Mixed-signal ICs (ADC, DAC, SevenSegElm) declare `isDigitalChip()==false`
  to suppress the High-Logic-Voltage edit row; ADC/DAC use an explicit `V+`
  post instead, creating a **latent duality** with ChipElm's `highVoltage`
  field (a known issue).
- SRAM is the only element with persistent editable user state; its dump
  embeds a run-length encoded `{address: values…}` stream terminated by
  numeric sentinels (`-1` = run end, `-2` = EOF).
- SevenSegElm is the only chip with `getVoltageSourceCount() == 0` — it is
  a **display-only** ChipElm that participates in the netlist only via its
  optional per-segment LED diode companions.

## 2. Domain Model  {#C_ECH_02}

### 2.1. Key Entities  {#C_ECH_02_01}

- **`ADCElm`** — N-bit analog-to-digital converter; `bits+2` posts; reads
  reference from `V+` post; `bits` voltage sources, one per bit output.
- **`DACElm`** — N-bit digital-to-analog; `bits+2` posts; samples inputs by
  voltage threshold, drives one analog output voltage source.
- **`MultiplexerElm`** / **`DeMultiplexerElm`** — parametric N-to-1 / 1-to-N
  switches driven by `selectBitCount` select lines. Mux optionally adds
  inverted output (`FLAG_INVERTED_OUTPUT`) and strobe enable (`FLAG_STROBE`).
- **`SevenSegDecoderElm`** — pure combinational BCD→7-seg decoder with a
  hardcoded 16-row hex-capable truth table and optional blanking flags.
- **`SevenSegElm`** — passive 7/14/16-segment display element with optional
  DP/colon and optional common-anode/common-cathode LED diode physics.
- **`SRAMElm`** — addressable static RAM with per-address HashMap backing,
  OE-gated bi-directional data bus, and inline editable contents dump.

All seven extend `ChipElm`; all seven share the Pin/Post layout mechanism
and editor dialog framework from element-base.

### 2.2. Data Flows  {#C_ECH_02_02}

Per-step flow (typical digital chip — Mux/DeMux/SevenSegDecoder):

    simulator.doStep → ChipElm.doStep → samplePins → execute() → writeOutputs

Analog variants (ADCElm, DACElm, SRAMElm, SevenSegElm-with-diodes):

    simulator.doStep → ChipElm.doStep OR override → stampResistor/updateVoltageSource
                                                     → diode.doStep (SevenSegElm)
                                                     → map.put(addr, data) @ stepFinished (SRAM)

Persistence:

    dump: super.dump() [+ element-specific tokens]      # file save
    applyJsonProperties: restore typed props             # JSON load
    applyJsonState: restore SRAM map / ADC bit state     # JSON load

## 3. Mechanisms  {#C_ECH_03}

### 3.1. Core Algorithm  {#C_ECH_03_01}

**ADC** — `val = imax * V(In) / V(V+)`; cast int, clamp `[0, imax]`; no
rounding (intentional per source comment to keep half-flash working).

**DAC** — sample bits by threshold; `v = ival * V(V+) / ((1<<bits)-1)`;
override `doStep()` to `updateVoltageSource` on the `O` pin.

**Mux** — little-endian decode select bits; forward `pins[sel].value` to Q;
apply optional strobe-blank and optional `/Q` inversion.

**DeMux** — clear all outputs, route Q to `pins[sel]`; no enable line.

**SevenSegDecoder** — look up 7-bit row in hardcoded `boolean[16][7]`;
apply `BI` blanking (active-low) and optional `F`-blank flag.

**SevenSeg display** — two-pass segment draw (diagonals, then straights);
optional diodes stamped in `stamp()` and driven via
`diode.doStep(dir * (Vseg - Vcommon))`; brightness `w = 255*(1 +
0.2*log(|I|/10mA))`; non-converged guard on NaN.

**SRAM** — every step, decode address MSB-first, read `map.get(addr)` (0 if
null), push bits to internal voltage sources at hardcoded 5 V; re-stamp OE
variable resistor (1 Ω when OE asserted, 1e8 Ω otherwise); on
`stepFinished`, sample D-pins by threshold and `map.put(addr, data)` if WE
asserted.

### 3.2. Edge Cases  {#C_ECH_03_02}

- Mux/DeMux `selectBitCount==0` legacy dump → defensive upgrade to 2.
- SevenSegElm shorted LED path → clamp common-pin current to ±1e12 A and
  flag simulator non-converged on NaN/overflow.
- SRAM malformed textarea contents → per-line try/catch swallows; dump
  corruption silently yields empty map on load.
- All seven ctors swallow token parse failures with empty catch blocks —
  partial dump corruption silently yields default parameter values.
- `MultiplexerElm` edit range declares max 8 but setter clamps to 6 —
  documented range inconsistency.

## 4. Integration Points  {#C_ECH_04}

### 4.1. Dependencies  {#C_ECH_04_01}

- [C_ELB](./element-base.concept.md) — `ChipElm`, `CircuitElm`, `Pin`,
  `setupPins`, `execute`, `drawChip`, `highVoltage`, `cspc`, side constants.
- [C_UTL](./util-locale-log.concept.md) — `StringTokenizer`, `Locale` for
  labels, logging for debug.
- [C_GEO](./geometry.concept.md) — `Point`, `interpPoint2`, bounding-box
  helpers.
- [C_RND](./rendering-primitives.concept.md) — `Graphics`, `Color`,
  `Polygon`, rotated-square helpers for SevenSegElm DP/colon.
- `DiodeModel.getModelWithName("default-led")` — SevenSegElm LED physics.
- `SRAMLoadFile` (IO framework) — optional file-picker for SRAM contents.

### 4.2. API Surface  {#C_ECH_04_02}

The concept exposes seven concrete element classes dispatched by
`CircuitElementFactory` on dump-type code. Each class:

- honours the ChipElm Pin/post contract (`setupPins`, `getChipName`,
  `getPostCount`).
- declares `getVoltageSourceCount()` appropriate to its output cardinality.
- implements `getChipEditInfo` / `setChipEditValue` for dialog rows.
- emits `getJsonTypeName()` for cross-format interop.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
