# Sequential Logic Elements — Specification  {#SP_ELS}

> **Code:** SP_ELS
> **Status:** draft
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_ELS](./elements-logic-sequential.concept.md)
> **Depends on specs:** [SP_ELB](./element-base.sp.md)
> **Used by specs:** io-framework, editor
> **Plan:** [elements-logic-sequential.plan.md](./elements-logic-sequential.plan.md)
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-logic-sequential.md](../.dev_flow/onboard/analysis/domain-core__cat-logic-sequential.md)
>
> Catalog and contracts for the 12 sequential-logic elements (+ 2
> misfiled combinational adders).

## 01. Data Structures  {#SP_ELS_01}

### 01_01. Per-element catalog  {#SP_ELS_01_01}

| Element | Extends | Inputs (pins) | Outputs (pins) | Internal state | Dump type | File:lines |
|---|---|---|---|---|---|---|
| LatchElm | ChipElm | bits × I + Ld | bits × O | lastLoad bool; per-output pins[i].state | 168 | LatchElm.java:27-129 |
| DFlipFlopElm | ChipElm | D, CLK, optional R/S | Q, /Q | lastClock, justLoaded | 155 | DFlipFlopElm.java:28-202 |
| JKFlipFlopElm | ChipElm | J, CLK, K, optional R | Q, /Q | lastClock, justLoaded | 156 | JKFlipFlopElm.java:28-189 |
| TFlipFlopElm | ChipElm | T, CLK, optional R/S | Q, /Q | lastClock | 193 | TFlipFlopElm.java:28-160 |
| CounterElm | ChipElm | CLK, R, optional U/D | bits × Q (MSB-first) | lastClock; counter value in pins[i].value | 164 | CounterElm.java:29-201 |
| Counter2Elm | ChipElm | CLK, CLR, EnP, EnT, LOAD, bits × I | bits × Q, RCO | lastClock, carry | 421 | Counter2Elm.java:28-190 |
| RingCounterElm | ChipElm | CLK, R, optional CE | bits × Q (one-hot) | lastClock; one-hot in pins[i].value; justLoaded | 163 | RingCounterElm.java:28-177 |
| SeqGenElm | ChipElm | CLK, optional R | Q | int[] data, bitCount, bitPosition, clockstate | 188 | SeqGenElm.java:33-257 |
| PisoShiftElm | ChipElm | LD, CLK, SER (new-bhvr), bits × D | Q | boolean[] data, dataIndex (circular), clockState, loadState | 186 | PisoShiftElm.java:29-186 |
| SipoShiftElm | ChipElm | D, CLK | bits × Q | clockstate; shift reg in pins[i].value | 189 | SipoShiftElm.java:29-138 |
| MonostableElm | ChipElm | CLK (trigger) | Q, /Q | prevInputValue, triggered, lastRisingEdge (double), delay (double), retriggerable | 194 | MonostableElm.java:29-162 |
| TimerElm (555) | ChipElm | DIS, TRIG, THRES, VCC, CTL, RST, optional GND | OUT | out, triggerSuppressed; no lastClock (analog comparator) | 165 | TimerElm.java:28-270 |
| HalfAdderElm | ChipElm | A, B | S, C | none (combinational) | 195 | HalfAdderElm.java:26-78 |
| FullAdderElm | ChipElm | bits × A, bits × B, Cin | bits × S, Cout | none (combinational ripple-carry in single timestep) | 196 | FullAdderElm.java:27-125 |

## 02. Contracts  {#SP_ELS_02}

### 02_01. Canonical rising-edge detection  {#SP_ELS_02_01}

    if (pins[clk].value && !lastClock) {
        // clocked logic
    }
    // async reset check (overrides clocked output)
    lastClock = pins[clk].value;

Polarity variants: JKFlipFlop `FLAG_POSITIVE_EDGE`, Counter `FLAG_NEGATIVE_EDGE`
(XOR-style check).

### 02_02. LatchElm edge vs level  {#SP_ELS_02_02}

- Default: edge-triggered. Copy inputs to outputs only when
  `pins[loadPin].value && !lastLoad`.
- `FLAG_NO_EDGE`: transparent latch. Copy as long as Ld is high.

### 02_03. Counter variants  {#SP_ELS_02_03}

- CounterElm — synchronous up/down; modulus via dialog (0 = natural
  2^bits roll); async reset pin with configurable polarity
  (`invertreset` flag).
- Counter2Elm — 74161/163-style: `EnP`/`EnT` enables, synchronous
  parallel LOAD, RCO = `carry && pins[ent].value`. CLR claimed
  synchronous but actually evaluated unconditionally (async in practice).
- RingCounterElm — one-hot shifter, default 10 bits; optional CE
  clock-inhibit (bits≥3); auto-recovers when all bits zero.

### 02_04. Shift registers  {#SP_ELS_02_04}

- PisoShift — circular `boolean[] data` + `dataIndex`. LOAD rising-edge
  copies pins[D…] into data, sets `dataIndex=0` (new bhvr) or `-1`
  (legacy "skip first bit"). CLK rotates. Optional SER pin fills vacated
  slot when `FLAG_NEW_BEHAVIOR`.
- SipoShift — shifts pin values directly: `pins[DATA+i+1].value =
  pins[DATA+i].value` from i=bits-2 down; `pins[2].value = pins[0].value`.
  No load/reset pins.

### 02_05. SeqGenElm  {#SP_ELS_02_05}

- Bit-stream packed into `int[] data` (32 bits per int), logical length
  `bitCount`.
- `nextBit()` advances on rising CLK; wraps or stays low (per
  `FLAG_PLAY_ONCE`).
- Optional R pin via `FLAG_HAS_RESET` rewinds `bitPosition`.
- Legacy `FLAG_NEW_VERSION=2` — ctor bit-reverses old 8-bit dumps.
- JSON state preserves `bitPosition` + `clockstate`.

### 02_06. MonostableElm  {#SP_ELS_02_06}

- Rising edge on CLK sets `triggered=true`, `lastRisingEdge=simulator().t`.
- Fires until `sim.t > lastRisingEdge + delay`.
- Retriggerable mode: rising edge while triggered restarts timer.
- `delay` in seconds, default 0.01.
- Still a digital chip (`isDigitalChip()=true` default).

### 02_07. TimerElm (555)  {#SP_ELS_02_07}

- `isDigitalChip()=false`; `nonLinear()=true`.
- `stamp()`: 5 kΩ VCC→CTL + 10 kΩ CTL→GND (divider holds CTL at ⅔ Vcc).
- `startIteration()` comparator logic:
  - If `V(THRES) > V(CTL)`: out=false.
  - If `½(V(CTL)+Vgnd) > V(TRIG)`: out=true.
  - If `V(RST) < 0.7V above ground`: out=false, `triggerSuppressed=true`.
- `doStep()` stamps output driver: pull-up Vcc→OUT via 1 Ω when high;
  DIS→GND via 10 Ω and OUT→GND via 1 Ω when low.
- `calculateCurrent()` manually computes currents from Ohm's law for
  each resistor segment.
- Optional ground pin (`FLAG_GROUND`).

## 03. Validation Rules  {#SP_ELS_03}

- LatchElm / CounterElm / PisoShiftElm / RingCounterElm — `setChipEditValue`
  rejects `bits < 2` (`< 3` for ring counter).
- `SeqGenElm` ctor clamps `bitCount ≤ data.length * Integer.SIZE`.
- `SeqGenElm` swallows `NoSuchElementException` on corrupted dumps →
  bitCount=0, constant zero output.
- `MonostableElm` / `TimerElm` do not validate `delay > 0`.
- DFlipFlopElm / JKFlipFlopElm / RingCounterElm set `justLoaded=true`
  in load-from-dump ctor and skip `execute()` on first timestep to
  avoid misinterpreting initial zeros as async reset.
- `Counter2Elm` orders `setupPins(); setPoints(); allocNodes();` to
  keep pin indices fresh.

## 04. State Transitions  {#SP_ELS_04}

### 04_01. D flip-flop  {#SP_ELS_04_01}

| From Q | Rising edge with D | To Q |
|--------|--------------------|------|
| 0 | 0 | 0 |
| 0 | 1 | 1 |
| 1 | 0 | 0 |
| 1 | 1 | 1 |

Async R sets Q=0; async S sets Q=1. Q̄ = !Q maintained.

### 04_02. JK flip-flop  {#SP_ELS_04_02}

| J | K | Q' |
|---|---|----|
| 0 | 0 | Q |
| 0 | 1 | 0 |
| 1 | 0 | 1 |
| 1 | 1 | !Q |

### 04_03. Monostable  {#SP_ELS_04_03}

| State | Event | Next |
|-------|-------|------|
| idle | rising edge | triggered, start timer |
| triggered (retriggerable) | rising edge | restart timer |
| triggered (non-retriggerable) | rising edge | ignore |
| triggered | `t > lastRisingEdge + delay` | idle |

## 05. Verification Criteria  {#SP_ELS_05}

### 05_01. Functional Expectations  {#SP_ELS_05_01}

| Element | Scenario | Expected |
|---------|----------|----------|
| DFlipFlop | clock pulse with D=1 | Q=1, /Q=0 |
| TFlipFlop | two clock pulses with T=1 | Q returns to start |
| JKFlipFlop | J=K=1, two clocks | Q toggles each edge |
| CounterElm 4-bit | 16 clocks | wraps to 0 |
| Counter2Elm | LOAD high, CLK | loads parallel inputs |
| RingCounterElm | N clocks (N=bits) | one-hot returns to start |
| PisoShiftElm | LOAD then shift | outputs bits serially |
| SipoShiftElm | shift N bits | parallel outputs hold them |
| SeqGenElm | FLAG_PLAY_ONCE | stops at bitCount |
| MonostableElm | trigger | output high for `delay` seconds |
| TimerElm | classic 555 astable | oscillates per R/C timing |
| HalfAdderElm | A=B=1 | S=0, C=1 |
| FullAdderElm | N-bit add with carry | ripple-propagated sum in one timestep |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
