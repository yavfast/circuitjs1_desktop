# Source Elements — Specification  {#SP_ESRC}

> **Code:** SP_ESRC
> **Status:** draft
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_ESRC](./elements-sources.concept.md)
> **Depends on specs:** [SP_ELB](./element-base.sp.md), [SP_WFM](./waveforms.sp.md), [SP_GEO](./geometry.sp.md)
> **Used by specs:** io-framework, editor
> **Plan:** [elements-sources.plan.md](./elements-sources.plan.md)
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-sources.md](../.dev_flow/onboard/analysis/domain-core__cat-sources.md)
>
> Catalog and contracts for the 11 source elements.

## 01. Data Structures  {#SP_ESRC_01}

### 01_01. Per-element catalog  {#SP_ESRC_01_01}

| Element | Extends | Posts | V-src | Waveform / WF_* | Dump-type | Parameters | File:lines |
|---|---|---|---|---|---|---|---|
| VoltageElm (branch base) | CircuitElm | 2 | 1 | dynamic via `waveform` field | `'v'` (118) | waveform, freq, maxV, bias, phase, duty | VoltageElm.java:33-307 |
| DCVoltageElm | VoltageElm | 2 | 1 | WF_DC | `'v'` (via getDumpClass=VoltageElm.class) | maxV, bias | DCVoltageElm.java:26-43 |
| ACVoltageElm | VoltageElm | 2 | 1 | WF_AC | `'v'` (via getDumpClass=VoltageElm.class) | maxV, bias, freq, phase | ACVoltageElm.java:25-38 |
| RailElm | VoltageElm | 1 | 1 | WF_DC default | `'R'` (82) | inherited | RailElm.java:30-143 |
| ACRailElm | RailElm | 1 | 1 | WF_AC | `'R'` (via getDumpClass=RailElm.class) | maxV, bias, freq, phase | ACRailElm.java:25-42 |
| SquareRailElm | RailElm | 1 | 1 | WF_SQUARE | `'R'` (via getDumpClass=RailElm.class) | maxV, bias, freq, phase, duty | SquareRailElm.java:25-42 |
| VarRailElm | RailElm | 1 | 1 | WF_DC (frequency repurposed as slider value) | 172 | bias=min, maxV=max, frequency=slider, sliderText | VarRailElm.java:34-244 |
| ExtVoltageElm | RailElm | 1 | 1 | WF_AC placeholder; overrides getVoltage | 418 | name + inherited | ExtVoltageElm.java:31-105 |
| NoiseElm | RailElm | 1 | 1 | WF_NOISE | `'R'` (waveform=6 discriminator) | maxV, bias | NoiseElm.java:27-48 |
| CurrentElm | CircuitElm (not VoltageElm) | 2 | 0 | — | `'i'` (105) | currentValue | CurrentElm.java:30-174 |
| SweepElm | CircuitElm (not VoltageElm) | 1 | 1 | — (custom phase accumulator) | 170 | minF, maxF, maxV, sweepTime, FLAG_LOG=1, FLAG_BIDIR=2 | SweepElm.java:30-310 |

Fixed structural contract from `VoltageElm`:
- Posts: 2; Pin names: `{minus, plus}` (post 1 is driven `voltage` above post 0; [SP_AGA_DEC_06](./agent-api.sp.md#SP_AGA_DEC_06); JSON 2.0 `positive`/`negative` are import aliases for post 0/1). `CurrentElm`: `{in, out}` (current leaves at `out`); `OhmMeterElm`: `{com, probe}`; `RailElm` and subclasses: `{output}`.
- `getVoltageSourceCount() == 1`; dump type `'v'` (118);
  `getIdPrefix() == "V"`.
- Voltage-diff convention: `V(1) − V(0)`; power: `-Vd · I`.
- Dump always writes 5 waveform params (freq, maxV, bias, phase, duty)
  regardless of type.

## 02. Contracts  {#SP_ESRC_02}

### 02_01. Waveform-driven source  {#SP_ESRC_02_01}

- `createWaveformInstance()` builds strategy via
  `Waveform.create(type, old)` and syncs `waveform` int to
  `waveformInstance.getType()`.
- All lifecycle hooks (`stamp/doStep/stepFinished/getVoltage/draw/
  getInfo/getEditInfo/setEditValue/getJsonTypeName/
  getJsonProperties/applyJsonProperties`) delegate to the strategy.
- Editor slot 1 is a waveform chooser (8 choices); setting it recreates
  the strategy and resets bias/duty on trait change.

### 02_02. RailElm (1-post)  {#SP_ESRC_02_02}

- `getPostCount()=1`; `getVoltageDiff() = getNodeVoltage(0)`;
  `hasGroundConnection(0) = true`.
- `stamp()` calls `waveformInstance.stampRail(this)`.
- `doStep()` updates only when `!waveformInstance.isDC()`.
- `draw()` fully overridden to render rail label + lead + glyph via
  `waveformInstance.drawRail`.
- Pin names: `{output}`. `FLAG_CLOCK=1` reused by `ClockElm`
  (different category) for the square-rail CLK label.

### 02_03. VarRailElm  {#SP_ESRC_02_03}

- Waveform forced to WF_DC (via `createSlider`); `frequency` field
  repurposed as current slider value.
- `getVoltage()` returns `waveformInstance.frequency` directly.
- `doStep()` always updates the voltage source (slider can move anytime).
- Binds to Adjustable keyed by `(this, EDIT_VOLTAGE=3)`; syncs
  minValue/maxValue/sliderText.
- `delete()` removes label/slider widgets + calls
  `adjustableManager.deleteSliders(this)`.

### 02_04. CurrentElm  {#SP_ESRC_02_04}

- `stamp()`: if `broken`, `stampResistor(n0, n1, 1e8)` and zero current;
  else `stampCurrentSource(n0, n1, currentValue)`.
- No `doStep` / `stepFinished` (current is constant across analysis).

### 02_05. SweepElm  {#SP_ESRC_02_05}

- `stamp()`: `stampVoltageSource(0, node0, vs)` (free, per-step-updated).
- `setParams()`: linear `fadd = dir·Δt·(maxF−minF)/sweepTime`, `fmul=1`;
  log `fadd=0`, `fmul=(maxF/minF)^(dir·Δt/sweepTime)`.
- `startIteration()`: `v = sin(freqTime)·maxV`; advance phase
  `freqTime += 2π·frequency·Δt`; update frequency by fmul+fadd; flip
  `dir` at boundaries when FLAG_BIDIR, else wrap.
- `doStep()`: `updateVoltageSource(0, node0, vs, v)`.
- `reset()`: frequency = minF, phase = 0, dir = 1.
- JSON state preserves `frequency`, `freqTime`, `dir`, `v` so sweep
  resumes mid-cycle after import.

## 03. Validation Rules  {#SP_ESRC_03}

- `VoltageElm` dump-ctor wraps token parsing in try/catch; falls back
  to `createWaveformInstance()` with whatever parsed (tolerates
  truncated legacy dumps).
- Legacy `FLAG_COS=2` → cleared, `phaseShift=π/2`.
- `FLAG_PULSE_DUTY=4` — if absent on pulse waveform, coerce `dutyCycle`
  to `1/(2π) ≈ 0.159`.
- On waveform-type change: DC→zero bias; Pulse→`defaultPulseDuty`;
  out-of-Pulse→0.5.
- `VarRailElm.setEditValue` auto-swaps bias/maxVoltage if max < bias.
- `VarRailElm.getEditInfo` returns null when `waveformInstance == null`
  (load-time race guard).
- `CurrentElm.stamp` broken-path stamp (100 MΩ); ctor-from-dump default
  `currentValue = 0.01 A` on parse error.
- `SweepElm.setEditValue` clamps minF / maxF to `1/(8·timeStep)`.
- `SweepElm.startIteration` re-runs `setParams()` when `timeStep` changes.
- `ExtVoltageElm.setVoltage` ignores NaN.

## 04. State Transitions  {#SP_ESRC_04}

Waveform type editor-slot-1 change:

| From | To | Side effects |
|------|----|---------------|
| any | DC | `bias=0` |
| non-Pulse | Pulse | `dutyCycle=defaultPulseDuty` |
| Pulse | non-Pulse | `dutyCycle=0.5` |
| any | any | recreate strategy via `Waveform.create` |

## 05. Verification Criteria  {#SP_ESRC_05}

### 05_01. Functional Expectations  {#SP_ESRC_05_01}

| Element | Scenario | Expected |
|---------|----------|----------|
| DCVoltageElm | 12V DC across R | steady I = V/R |
| ACVoltageElm | 1V 60Hz sine | `V = sin(2π·60·t)` |
| RailElm | DC against ground | stamp once, no per-step update |
| VarRailElm | slider change | `doStep` updates VS every step |
| CurrentElm | no path | broken=true → 100MΩ stand-in |
| SweepElm | log sweep minF→maxF | frequency multiplicatively advances |
| NoiseElm | random samples | `noiseValue` latched per step |

### 05_02. Invariant Checks  {#SP_ESRC_05_02}

- `getVoltageSourceCount()` is always 1 for voltage-driven sources,
  0 for CurrentElm.
- Dump format carries 5 waveform params regardless of actual type.
- VarRailElm Adjustable entry exists for the lifetime of the element.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
