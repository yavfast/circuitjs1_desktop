# Sequential Logic Elements  {#C_ELS}

> **Code:** C_ELS
> **Status:** draft
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** OnboardDocGen
>
> **Depends on:** [C_ELB](./element-base.concept.md) (especially ChipElm), [C_GEO](./geometry.concept.md), [C_RND](./rendering-primitives.concept.md)
> **Used by:** element factory, editor
> **Spike:** —
> **Specification:** [SP_ELS](./elements-logic-sequential.sp.md)
> **Plan:** [elements-logic-sequential.plan.md](./elements-logic-sequential.plan.md)
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-logic-sequential.md](../.dev_flow/onboard/analysis/domain-core__cat-logic-sequential.md)
>
> State-holding digital elements — latches, flip-flops, counters, shift
> registers, sequence generators, monostables, and the 555 timer. All
> extend `ChipElm` and reuse its pin-grid / `execute()` / `lastClock`
> plumbing.

## 1. Philosophy  {#C_ELS_01}

### 1.1. Core Principle  {#C_ELS_01_01}

Every element extends `ChipElm`. Inputs are sampled by `ChipElm.doStep()`
against the chip's logic threshold; outputs are written via
`pins[i].value = …` inside `execute()`. Edge-triggered elements detect
transitions by comparing `pins[clk].value` to either `ChipElm.lastClock`
(canonical) or a per-element mirror (`clockstate` / `prevInputValue` /
`loadState`).

### 1.2. Design Constraints  {#C_ELS_01_02}

- Reset semantics: asynchronous R/CLR checked **after** the clock-edge
  block in `execute()`, overriding clocked transitions.
- Output voltage snapshot persisted via `pins[i].state = true` marker
  read by `ChipElm.dump()`. JSON additionally serializes `last_clock`
  and other per-element state.
- First timestep after load: flip-flops / ring counters set
  `justLoaded = true` and skip `execute()` once, to avoid treating
  all-zero initial voltages as an asynchronous reset.
- `HalfAdderElm` / `FullAdderElm` are purely combinational (no state,
  no clock); they sit in this folder for code proximity only and should
  move to combinational category (see plan backlog).

## 2. Domain Model  {#C_ELS_02}

### 2.1. Key Entities  {#C_ELS_02_01}

Per-element name list (full catalog in SP_ELS):

- Latch: `LatchElm`
- Flip-flops: `DFlipFlopElm`, `JKFlipFlopElm`, `TFlipFlopElm`
- Counters: `CounterElm`, `Counter2Elm`, `RingCounterElm`
- Shift registers: `PisoShiftElm`, `SipoShiftElm`
- Sequence generator: `SeqGenElm`
- Timing: `MonostableElm`, `TimerElm` (555)
- Combinational (misfiled): `HalfAdderElm`, `FullAdderElm`

Total: 14 files (12 truly sequential + 2 combinational).

### 2.2. Data Flows  {#C_ELS_02_02}

Canonical clocked `execute()`:

    if pins[clk].value && !lastClock:      // rising edge
        perform clocked transition
    if async_reset_active:                  // overrides clocked output
        clear outputs
    lastClock = pins[clk].value

## 3. Mechanisms  {#C_ELS_03}

### 3.1. Core Algorithms  {#C_ELS_03_01}

**Edge-detection idioms:**

1. `ChipElm.lastClock` + clock pin — default for D/JK/T flip-flops,
   CounterElm, Counter2Elm, RingCounterElm, LatchElm.
2. Per-element boolean mirror (`clockstate` / `prevInputValue` /
   `loadState`) — SeqGenElm, PisoShiftElm, SipoShiftElm, MonostableElm
   (Edward Calver contributions that don't use `ChipElm.lastClock`).

Polarity: JK uses `FLAG_POSITIVE_EDGE`; Counter uses `FLAG_NEGATIVE_EDGE`
with XOR-style check `pins[0].value != neg && lastClock == neg`.

**State encoding:**

- Boolean Q/Q̄ — stored in `pins[i].value`; Q̄ maintained as `!Q`.
- Integer counter (implicit) — `CounterElm` reads N-bit value out of
  `pins[lastBit-i].value`, increments/decrements, writes back.
- One-hot index — `RingCounterElm` scans output pins for current high,
  advances modulo bits, rewrites.
- Bit array — `SipoShiftElm` shifts pin values directly.
  `PisoShiftElm` uses `boolean[] data` + circular `dataIndex`.
- Int[] packed bit-stream — `SeqGenElm` with `bitCount`, `bitPosition`.
- Wall-clock double — `MonostableElm.lastRisingEdge` in `simulator().t`.

**Dump persistence:** `pins[i].state = true` tells `ChipElm.dump()` to
write the output voltage. This is the only way sequential state
survives save/reload (the `lastClock` mirror is not persisted to text
dump, only to JSON).

**555 Timer (`TimerElm`):** Non-digital (`isDigitalChip()=false`,
`nonLinear()=true`). `stamp()` places a 5 kΩ / 10 kΩ voltage divider
holding CTL at ⅔ Vcc. `startIteration` implements comparator logic
against CTL voltage. `doStep` stamps output pull-up (1 Ω to Vcc) when
high or DIS-to-ground (10 Ω) + output-to-ground (1 Ω) when low.
`calculateCurrent` manually computes currents from Ohm's law.

**MonostableElm:** Reads `pins[0].value` as digital; fires until
`sim.t > lastRisingEdge + delay`. Retriggerable vs non-retriggerable
mode gates whether edges during the active pulse restart the timer.

### 3.2. Edge Cases  {#C_ELS_03_02}

- `SeqGenElm` silently swallows `NoSuchElementException` on corrupted
  dumps (bitCount=0, constant-zero output).
- `SeqGenElm.setChipEditValue` integer-divides `bitCount/Integer.SIZE`
  — a 7-bit sequence allocates 0 ints (bug — see plan backlog).
- `RingCounterElm` auto-recovers when all outputs are zero by forcing
  `pins[2].value = true`.
- `Counter2Elm.CLR` is evaluated outside the clock-edge block — async
  in practice despite 74163-style claim.
- `LatchElm` supports both edge-triggered (`FLAG_NO_EDGE` clear) and
  level-sensitive (transparent latch) modes.

## 4. Integration Points  {#C_ELS_04}

### 4.1. Dependencies  {#C_ELS_04_01}

- [C_ELB](./element-base.concept.md) — `ChipElm` and its `Pin` inner
  class, `lastClock`, `writeOutput()`, `writeBits()`, `readBits()`,
  `highVoltage`, `getNodeVoltage()`, `setNodeVoltageDirect()`.
- `CircuitSimulator.t` — MonostableElm timer reference.
- GWT `TextArea` — SeqGen bitstream editor only.

### 4.2. API Surface  {#C_ELS_04_02}

Dump-types: 155 DFlipFlop, 156 JKFlipFlop, 163 RingCounter, 164 Counter,
165 TimerElm, 168 LatchElm, 186 PisoShift, 188 SeqGen, 189 SipoShift,
193 TFlipFlop, 194 Monostable, 195 HalfAdder, 196 FullAdder, 421
Counter2. Every class overrides `getJsonTypeName` and most override
`getJsonProperties` / `getJsonState`.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
