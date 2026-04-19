# Waveforms — Specification  {#SP_WFM}

> **Code:** SP_WFM
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_WFM](./waveforms.concept.md)
> **Depends on specs:** [SP_ELB](./element-base.sp.md), [SP_UTL](./util-locale-log.sp.md), [SP_RND](./rendering-primitives.sp.md), [SP_EIC](./edit-info-contract.sp.md)
> **Used by specs:** — (will be filled by higher layers)
> **Plan:** [waveforms.plan.md](./waveforms.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/domain-core__waveforms.md`.
>
> Defines the `Waveform` base + 8 concrete subclasses in `client/element/waveform/`, the parameter-model, dump/JSON formats, and per-type edit-slot maps.

## 01. Data Structures  {#SP_WFM_01}

> Implements: [C_WFM_02](./waveforms.concept.md#C_WFM_02)

### 01_01. Waveform (abstract base)  {#SP_WFM_01_01}

File: `Waveform.java:11`. Shared parameter model (public fields):

| Field | Type | Default | Constraints | Description |
|-------|------|---------|-------------|-------------|
| `frequency` | `double` | 40.0 Hz | Edit 4…500 Hz | Hz; repurposed by VarWaveform as current output V |
| `maxVoltage` | `double` | 5.0 V | Edit −20…20 V | Amplitude (peak / DC / ± noise / Var upper) |
| `freqTimeZero` | `double` | 0 | — | Time origin for `w(elm)`; reset in `VoltageElm.reset()` |
| `bias` | `double` | 0 V | −20…20 V | DC offset; Var uses as min-voltage bound |
| `phaseShift` | `double` | 0 rad | UI ±180° | Stored radians, shown degrees |
| `dutyCycle` | `double` | 0.5 | [0, 1] | UI 0–100%; meaningful for Square/Pulse |
| `noiseValue` | `double` | 0 | — | Scratch state for NoiseWaveform |

Waveform-type ordinals (written to text dump): `WF_DC=0`, `WF_AC=1`, `WF_SQUARE=2`, `WF_TRIANGLE=3`, `WF_SAWTOOTH=4`, `WF_PULSE=5`, `WF_NOISE=6`, `WF_VAR=7`.

Flag bits packed into `VoltageElm.flags`: `FLAG_COS=2` (legacy cosine → auto-translated to `phaseShift=π/2`), `FLAG_PULSE_DUTY=4` (asserts dumped `dutyCycle` is authoritative).

Invariants:
- `waveformInstance.getType() == VoltageElm.waveform` (enforced by `createWaveformInstance()`).
- `dutyCycle ∈ [0, 1]`.
- `phaseShift` stored in radians internally.

### 01_02. Concrete subclasses  {#SP_WFM_01_02}

| Class | Ordinal | Used params | Key override |
|-------|---------|-------------|--------------|
| `DCWaveform` | 0 | maxV, bias | `isDC=true`, `hasCircle=false`; value-fixed stamp |
| `ACWaveform` | 1 | maxV, bias, freq, phase | `getVoltage = sin(w)·maxV + bias`; JSON rail `ACRail` |
| `SquareWaveform` | 2 | all 5 | Symmetric square; JSON rail `SquareRail`; `CLK` label when `RailElm.FLAG_CLOCK` |
| `TriangleWaveform` | 3 | maxV, bias, freq, phase | Piecewise-linear |
| `SawtoothWaveform` | 4 | maxV, bias, freq, phase | Ramp |
| `PulseWaveform` | 5 | all 5 | Asymmetric (low = bias); `isPulse=true` |
| `NoiseWaveform` | 6 | maxV, bias | `showFrequency=false`; `stepFinished` latches new sample |
| `VarWaveform` | 7 | bias (min), maxV, sliderText | `usesShortLeads=true`, `showFrequency=false`; reads slider; JSON rail `VariableRail` |

## 02. Contracts  {#SP_WFM_02}

> Implements: [C_WFM_03](./waveforms.concept.md#C_WFM_03)

### 02_01. Abstract methods  {#SP_WFM_02_01}

| Method | Purpose |
|--------|---------|
| `int getType()` | Returns `WF_*` ordinal |
| `double getVoltage(VoltageElm)` | Per-step V(t); short-circuits on `dcAnalysisFlag` returning `bias` |
| `void draw(Graphics, Point center, VoltageElm)` | Icon inside source circle |
| `void getInfo(VoltageElm, String[] arr, int i)` | Info-bubble rows starting at index `i` |
| `EditInfo getEditInfo(VoltageElm, int n)` | Dialog row n (slot 1 reserved for type chooser) |
| `void setEditValue(VoltageElm, int n, EditInfo)` | Apply dialog edit |
| `String getJsonTypeName()` | JSON element-type (`VoltageSourceAC`, etc.) |

### 02_02. Overridable defaults  {#SP_WFM_02_02}

| Method | Default | Purpose |
|--------|---------|---------|
| `drawRail(Graphics, RailElm)` | draws waveform at `geom().getPoint2()` | Rail rendering |
| `getJsonProperties(Map)` | writes max_voltage, dc_offset (non-zero), frequency, phase_shift (non-zero deg), duty_cycle | JSON export |
| `applyJsonProperties(Map)` | mirror | JSON import |
| `copyFrom(Waveform)` | copies 7 shared fields | Type-change preservation |
| `getJsonRailTypeName()` | `"Rail"` | AC→`ACRail`, Square→`SquareRail`, Var→`VariableRail` |
| `isDC()` | false | DC short-circuits stamp path |
| `isPulse()` | false | Type-change duty-cycle handling |
| `stamp(VoltageElm)` | free voltage source between node 0 and 1 | DC overrides with pinned value |
| `stampRail(RailElm)` | ground-to-node stamp | DC overrides with static value |
| `doStep(VoltageElm)` | `updateVoltageSource(...)` unless `isDC()` | Per-step value update |
| `hasCircle()` | true | DC returns false |
| `showFrequency()` | true | Noise/Var return false |
| `usesShortLeads()` | mirrors `isDC()` | Var forces true |
| `stepFinished(VoltageElm)` | no-op | Noise latches new sample |
| `w(VoltageElm)` (protected) | `2π·(t − freqTimeZero)·frequency + phaseShift` | Angular-time helper |

### 02_03. Edit-slot map  {#SP_WFM_02_03}

| Waveform | n=0 | n=2 | n=3 | n=4 | n=5 |
|----------|-----|-----|-----|-----|-----|
| DC | Voltage | DC Offset | — | — | — |
| AC | Max V | DC Offset | Freq | Phase | — |
| Square | Max V | DC Offset | Freq | Phase | Duty% |
| Pulse | Max V | DC Offset | Freq | Phase | Duty% |
| Triangle | Max V | DC Offset | Freq | Phase | — |
| Sawtooth | Max V | DC Offset | Freq | Phase | — |
| Noise | Max V | DC Offset | — | — | — |
| Var | Min V (bias) | Max V | Slider Text | — | — |

Slot `n=1` is reserved by `VoltageElm` for the waveform-type chooser.

### 02_04. Serialization  {#SP_WFM_02_04}

Text dump shape (written by `VoltageElm.dump()`):

```
<dumpType> <x1> <y1> <x2> <y2> <flags> <waveform> <frequency> <maxVoltage> <bias> <phaseShift> <dutyCycle>
```

- `dumpType` per-subclass of the owning element (`'v'` for VoltageElm, overridden by DC/AC/etc.).
- All 5 trailing params always emitted, regardless of which waveform.
- `FLAG_COS` cleared on load, re-translated to `phaseShift=π/2`.
- `FLAG_PULSE_DUTY` absence + pulse type → `dutyCycle = defaultPulseDuty = 1/(2π)`.

JSON: symmetric `getJsonProperties`/`applyJsonProperties`. Only non-default values written: `max_voltage`, `dc_offset` (!= 0), `frequency` (not DC/Noise/Var), `phase_shift` in degrees (!= 0, not DC/Noise/Var), `duty_cycle` (Square/Pulse only).

JSON element-type names: `VoltageSourceDC`, `VoltageSourceAC`, `VoltageSourceSquare`, `VoltageSourceTriangle`, `VoltageSourceSawtooth`, `VoltageSourcePulse`, `VoltageSourceNoise`, `VoltageSourceVar`.

## 03. Validation Rules  {#SP_WFM_03}

### 03_01. Factory and parsing  {#SP_WFM_03_01}

- `Waveform.create(type, old)` defaults to `DCWaveform` on unknown type or `WF_VAR` (defensive fall-through).
- `VoltageElm(StringTokenizer)` ctor wraps parsing in try/catch and re-creates `waveformInstance` on exception.
- `FLAG_COS`: if set, cleared and `phaseShift := π/2`.
- `FLAG_PULSE_DUTY`: if not set and waveform is pulse, `dutyCycle := 1/(2π)`.
- `createWaveformInstance()` re-syncs integer `waveform` field with `waveformInstance.getType()` (out-of-range silently normalized).
- `VarWaveform.getEditInfo` guards `elm instanceof VarRailElm`; returns null for all rows otherwise.

### 03_02. Type-change normalization  {#SP_WFM_03_02}

- Switching **into** DC → `bias = 0`.
- Switching **into** Pulse → `dutyCycle = 1/(2π)`.
- Switching **out of** Pulse → `dutyCycle = 0.5`.

### 03_03. Edit UI ranges  {#SP_WFM_03_03}

- `maxVoltage`/`bias`: ±20 V
- `frequency`: 4…500 Hz
- `phaseShift`: −180…180 °
- `dutyCycle`: 0…100 %

## 04. State Transitions  {#SP_WFM_04}

### 04_01. NoiseWaveform sample-and-hold  {#SP_WFM_04_01}

```
  [step N]                        [step N+1]
  getVoltage → return noiseValue  getVoltage → return noiseValue (new)
          │                                 ▲
          ▼                                 │
     (Newton loop reads the same value)     │
          │                                 │
     stepFinished: noiseValue :=  rand·maxV+bias
          └──────────────────────────┘
```

### 04_02. VarWaveform read-write  {#SP_WFM_04_02}

`getVoltage` reads `slider.getValue()` each call and writes result back into `frequency` (field repurposed as current output voltage).

### 04_03. DCWaveform no evolution  {#SP_WFM_04_03}

Stamp done once with fixed value; `doStep` skipped via `isDC()` guard.

## 05. Verification Criteria  {#SP_WFM_05}

### 05_01. Functional Expectations  {#SP_WFM_05_01}

| Contract | Scenario | Input | Expected |
|----------|----------|-------|----------|
| `getVoltage` | AC @ t=0, f=60Hz, maxV=5 | bias=0, phase=0 | 0 (sin 0) |
| `getVoltage` | Pulse high | w mod 2π < 2π·duty | maxV+bias |
| `getVoltage` | Pulse low | w mod 2π ≥ 2π·duty | bias |
| DC-analysis short-circuit | dcAnalysisFlag=true | any periodic | returns `bias` |
| `create(7, old)` | legacy fallthrough | old = anything | returns `DCWaveform` |
| `create(999, old)` | unknown | — | returns `DCWaveform` |
| round-trip | dump → undump | any waveform | equal parameter bag |

### 05_02. Invariant Checks  {#SP_WFM_05_02}

| Invariant | Verification |
|-----------|--------------|
| `waveformInstance.getType() == waveform` | assert after `createWaveformInstance()` |
| `dutyCycle ∈ [0,1]` | UI clamps; internal use |
| Slot 1 never returned by subclass | static check on `getEditInfo` |

### 05_03. Integration Scenarios  {#SP_WFM_05_03}

| Scenario | Preconditions | Steps | Expected |
|----------|---------------|-------|----------|
| FLAG_COS legacy load | old dump with FLAG_COS | parse | phaseShift = π/2, flag cleared |
| Type change AC → DC | element with AC waveform | user picks DC | bias reset to 0, `isDC=true`, `doStep` no-ops |
| Noise DC op-point | DC analysis pass | `getVoltage` | returns latched `noiseValue` (not `bias`) — see backlog |

### 05_04. Edge Cases  {#SP_WFM_05_04}

| Case | Input | Expected |
|------|-------|----------|
| Truncated dump line | missing tokens | waveformInstance default-created |
| VarWaveform w/o VarRailElm | plain VoltageElm | getEditInfo returns null for all |
| Mid-run frequency edit | user changes freq | phase discontinuity (freqTimeZero not reset) |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
