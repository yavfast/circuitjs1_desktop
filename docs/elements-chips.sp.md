# Complex IC Elements — Specification  {#SP_ECH}

> **Code:** SP_ECH
> **Status:** draft
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_ECH](./elements-chips.concept.md)
> **Depends on specs:** [SP_ELB](./element-base.sp.md), [SP_UTL](./util-locale-log.sp.md), [SP_GEO](./geometry.sp.md), [SP_RND](./rendering-primitives.sp.md)
> **Used by specs:** circuit element factory, renderer, file I/O glue
> **Plan:** [elements-chips.plan.md](./elements-chips.plan.md)
>
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-complex-ics.md](../.dev_flow/onboard/analysis/domain-core__cat-complex-ics.md)
>
> Dense catalog, per-element contracts, stamping strategies and validation
> rules for the seven Complex IC elements (ADC, DAC, Mux, DeMux,
> SevenSegDecoder, SevenSeg, SRAM).

## 01. Element Catalog  {#SP_ECH_01}

> Implements: [C_ECH_02](./elements-chips.concept.md#C_ECH_02)

### 01_01. Element Table  {#SP_ECH_01_01}

| Element | Extends | Pins (postCount) | V-sources | Mem/State | Dump-type | Key params |
|---|---|---|---|---|---|---|
| `ADCElm` | ChipElm | `bits + 2` (D0..Dn-1, In, V+) | `bits` | pin values | 167 | `bits`, V+ pin reference |
| `DACElm` | ChipElm | `bits + 2` (D0..Dn-1, O, V+) | 1 | none | 166 | `bits`, V+ pin reference |
| `DeMultiplexerElm` | ChipElm | `2^selectBitCount + selectBitCount + 1` | `2^selectBitCount` | pin values | 185 | `selectBitCount` (1..6) |
| `MultiplexerElm` | ChipElm | `2^sbc + sbc + 1 [+invQ] [+STR]` | 1 or 2 | pin values | 184 | `selectBitCount` (1..6/8 mismatch), `FLAG_INVERTED_OUTPUT=2`, `FLAG_STROBE=4` |
| `SevenSegDecoderElm` | ChipElm | 11 or 12 (+BI) | 7 | pin values | 197 | `FLAG_ENABLE=2`, `FLAG_BLANK_F=4` |
| `SevenSegElm` | ChipElm | `segmentCount` (+1 if diodes) | **0** | `Diode[]` | 157 | `baseSegmentCount ∈ {7,14,16}`, `extraSegment ∈ {NONE,DP,COLON}`, `diodeDirection ∈ {-1,0,1}` |
| `SRAMElm` | ChipElm | `2 + addressBits + dataBits` | `dataBits` | `HashMap<Integer,Integer>`, address, internal nodes | 413 | `addressBits` (2..16), `dataBits` (2..16), contents |

Invariants:
- All seven extend `ChipElm` and use `setupPins()` + `execute()` / overridden
  `doStep()`.
- `isDigitalChip()==false` for ADC/DAC/SevenSegElm — suppresses the
  High-Logic-Voltage edit row.
- Only `SRAMElm` and `SevenSegElm` (with diodes) declare
  `nonLinear() == true`.
- `SevenSegElm.getVoltageSourceCount() == 0` — passive display-only.

### 01_02. Conversion Numerics (ADC/DAC)  {#SP_ECH_01_02}

- **ADC:** `imax = (1 << bits) - 1`; `val = imax * V(In) / V(V+)`; int cast,
  clamp `[0, imax]`; no rounding; LSB pin at bottom, MSB at top on east side.
- **DAC:** bit `i = V(Di) > getThreshold()`; `v = ival * V(V+) / ((1<<bits)-1)`;
  single voltage source updated in overridden `doStep()`.

### 01_03. Mux/DeMux Decode  {#SP_ECH_01_03}

- Little-endian select decoding: `sel = Σ(pins[outCount + i].value << i)`.
- Mux: forward `pins[sel].value` to Q; strobe blanks output (active-high);
  `/Q` added via second voltage source when `FLAG_INVERTED_OUTPUT` set.
- DeMux: clear all outputs, route input to `pins[sel]`.

### 01_04. SRAM State Model  {#SP_ECH_01_04}

- Storage: sparse `HashMap<Integer,Integer> map`; unwritten cells = 0.
- Internal nodes: one per `dataBits`; bridged to external D-pin via
  OE-gated resistor (1 Ω driven, 1e8 Ω Hi-Z).
- Read: push 5 V per '1' bit to internal source (**hardcoded 5 V**, not
  `highVoltage`).
- Write: on `stepFinished`, if WE asserted, sample D-pins by threshold and
  `map.put(addr, data)`.

### 01_05. SevenSeg Rendering  {#SP_ECH_01_05}

- Three geometry LUTs: `display7[]` (7 straight), `display14[]` (14 incl.
  diagonals), `display16[]` (16 with fills).
- Two-pass draw: diagonals first, then straights.
- Optional DP/colon rendered as rotated square offset right of grid.
- Drive modes:
  - `diodeDirection==0` — pure logic (red/darkred fill).
  - `diodeDirection==1` — common-cathode LED, gnd pin added.
  - `diodeDirection==-1` — common-anode LED, Vcc pin added.

## 02. Contracts  {#SP_ECH_02}

### 02_01. Per-element Stamping Contract  {#SP_ECH_02_01}

Purpose: define how each element participates in MNA stamping.

| Element | Stamp strategy | Voltage sources | Newton-nonlinear? |
|---|---|---|---|
| ADCElm | `bits` one-per-bit VS stamped once in ChipElm.stamp | bits | no |
| DACElm | 1 VS; update every doStep | 1 | no |
| MultiplexerElm | 1 VS (+1 if invQ); update via execute | 1–2 | no |
| DeMultiplexerElm | `2^sbc` VS; route per select | 2^sbc | no |
| SevenSegDecoderElm | 7 VS; update via execute | 7 | no |
| SevenSegElm | no VS; optional diodes via stampNonLinear + doStep | 0 | yes (if diodes) |
| SRAMElm | `dataBits` internal VS + per-bit switched resistor | dataBits | yes |

### 02_02. Persistence  {#SP_ECH_02_02}

Inputs (all dumps):
| Parameter | Type | Required | Constraints |
|-----------|------|----------|-------------|
| super.dump() | string | yes | ChipElm prefix (type, x, y, flags) |

Per-element appended tokens:
- ADC/DAC: `bits` (via ChipElm dump of highVoltage + bits).
- Mux/DeMux: `selectBitCount`.
- SevenSeg: `baseSegmentCount extraSegment diodeDirection`.
- SRAM: `addressBits dataBits` + runs `addr v0 v1 … -1` … terminator `-2`.

Errors:
| Code | Condition | Guidance |
|------|-----------|----------|
| PARSE_FAIL_SWALLOW | empty catch on tokens | defaults applied silently |
| SRAM_DUMP_CORRUPT | parseNumber fails mid-run | remainder of map discarded |

## 03. Validation Rules  {#SP_ECH_03}

### 03_01. Input Validation  {#SP_ECH_03_01}

- ADC/DAC: `bits >= 2` required; lower values silently rejected.
- DeMultiplexerElm: `selectBitCount` clamped to 1..6; 0 upgraded to 2.
- MultiplexerElm: UI declares 1..8 but setter enforces ≤ 6 — **known
  inconsistency** (Issue #3).
- SevenSegElm: bad tokens fall through to `setDefaults()`.
- SRAMElm: `addressBits`/`dataBits` clamped `[2, 16]`; textarea parse
  swallows malformed lines per-line; negative map values would corrupt dump
  (no guard).
- SevenSegElm diode update: `stepFinished` clamps common-pin current to
  ±1e12 A; marks `simulator.converged = false` on NaN.
- All seven ctors swallow dump parse failures silently.

## 04. State Transitions  {#SP_ECH_04}

### 04_01. Lifecycle  {#SP_ECH_04_01}

    [fresh]   --ctor-->   [configured]
    [configured] --stamp--> [stamped]
    [stamped] --doStep/execute--> [stamped']
    [stamped'] --stepFinished--> [stamped] | [SRAM: map updated]
    [any]     --setChipEditValue--> [configured]  (triggers re-analyze)
    [any]     --reset()--> [configured]  (clears pins/diodes/SRAM state reset as applicable)

## 05. Verification Criteria  {#SP_ECH_05}

### 05_01. Functional Expectations  {#SP_ECH_05_01}

| Contract | Scenario | Input | Expected outcome |
|----------|----------|-------|------------------|
| ADCElm | V(In)=2.5, V(V+)=5, bits=8 | analog | code=127 (floor, no round) |
| DACElm | input=0b10101010, bits=8, V(V+)=5 | digital | O ≈ 3.33 V |
| MultiplexerElm | sel=0b10, in[2]=high | digital | Q=high |
| MultiplexerElm | strobe asserted | — | Q forced low |
| DeMultiplexerElm | sel=3, Q=high | digital | out[3]=high, others low |
| SevenSegDecoderElm | inputs=0b0001 | digital | segment map matches `1` |
| SevenSegDecoderElm | BI asserted | — | all segments low |
| SevenSegElm | common-cathode, seg high | diode=default-led | current > 0, brightness>0 |
| SRAMElm | WE=0, D=0xA5 → WE=1, addr=7 | digital | map.get(7) == 0xA5 |
| SRAMElm | OE deasserted | digital | D-pin high-Z, external drive ok |

### 05_02. Invariant Checks  {#SP_ECH_05_02}

| Invariant | Verification method |
|-----------|-------------------|
| SevenSegElm.VoltageSourceCount == 0 | unit test on getVoltageSourceCount |
| SRAM map sparse | HashMap size ≤ writes performed |
| Mux sel clamp | property test on setChipEditValue(n=1, value=8) |

### 05_03. Integration Scenarios  {#SP_ECH_05_03}

| Scenario | Preconditions | Steps | Expected result |
|----------|--------------|-------|-----------------|
| ADC→DAC round-trip | 8-bit ADC → 8-bit DAC, V+=5 V common | ramp V(In) 0..5 | V(O) tracks V(In) within ±LSB |
| SRAM save/load | fill 10 cells, save file, load | round-trip dump | all 10 cells restored |
| Mux cascade | 2×4-to-1 feeding 2-to-1 | stable selects | output matches expected 8-to-1 logic |

### 05_04. Edge Cases and Boundaries  {#SP_ECH_05_04}

| Case | Input | Expected behavior |
|------|-------|-------------------|
| SRAM negative map value | user types `-5` in textarea | dump corruption (no guard) — DO NOT write |
| SevenSegElm LED shorted to rail | diode direct short | current clamped ±1e12, non-converged flagged |
| Mux with sbc=7 via UI | setEditValue(7) | silently clamped to 6 |
| SRAM contentsOverride | two SRAMs + one upload | **static leak** — other SRAM picks up text |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
