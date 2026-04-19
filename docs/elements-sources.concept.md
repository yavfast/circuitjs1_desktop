# Source Elements  {#C_ESRC}

> **Code:** C_ESRC
> **Status:** draft
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** OnboardDocGen
>
> **Depends on:** [C_ELB](./element-base.concept.md), [C_WFM](./waveforms.concept.md), [C_GEO](./geometry.concept.md), [C_RND](./rendering-primitives.concept.md)
> **Used by:** element factory, editor, JS bridge (ExtVoltageElm), sliders manager (VarRailElm)
> **Spike:** —
> **Specification:** [SP_ESRC](./elements-sources.sp.md)
> **Plan:** [elements-sources.plan.md](./elements-sources.plan.md)
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-sources.md](../.dev_flow/onboard/analysis/domain-core__cat-sources.md)
>
> Voltage- and current-source element family. Voltage sources are
> waveform-driven via a `Waveform` strategy plugged into `VoltageElm`
> (or its single-terminal flavour `RailElm`). `CurrentElm` and `SweepElm`
> are non-waveform members with bespoke stamping.

## 1. Philosophy  {#C_ESRC_01}

### 1.1. Core Principle  {#C_ESRC_01_01}

All voltage sources delegate time-domain behaviour to a `Waveform` plug-in
owned by `VoltageElm`. Concrete subclasses are almost trivial wrappers
that pick a default `WF_*` constant, re-brand the JSON type name, and
register a keyboard shortcut. Rails are 1-post (pin-to-ground) variants
sharing the same waveform machinery. Non-waveform members (`CurrentElm`,
`SweepElm`) inject energy directly into the MNA RHS.

### 1.2. Design Constraints  {#C_ESRC_01_02}

- Exactly 1 voltage source per element except `CurrentElm` (0) and
  `SweepElm` (1 with bespoke accumulator).
- Rail subclasses inherit the 1-post topology from `RailElm`; 2-post
  voltage sources inherit from `VoltageElm`.
- DC sources short-circuit `doStep` and are stamped with a fixed value
  at `stamp()` time (`stampVoltageSource(n0, n1, vs, V)`).
- `dumpClass` aliasing: subclasses that only differ by `WF_*` return the
  parent class from `getDumpClass()` so they round-trip through the
  parent's text-dump parser.

## 2. Domain Model  {#C_ESRC_02}

### 2.1. Key Entities  {#C_ESRC_02_01}

Per-element name list (full catalog in SP_ESRC):

- 2-post voltage: `VoltageElm` (branch base), `DCVoltageElm`,
  `ACVoltageElm`
- 1-post rails: `RailElm`, `ACRailElm`, `SquareRailElm`, `VarRailElm`,
  `ExtVoltageElm`, `NoiseElm`
- Current source: `CurrentElm`
- Frequency sweep: `SweepElm`

Total: 11 concrete elements.

### 2.2. Data Flows  {#C_ESRC_02_02}

Waveform delegation (every `VoltageElm` lifecycle hook):

    ctor -> createWaveformInstance(WF_*)
    stamp() -> waveformInstance.stamp(this)
    doStep() -> waveformInstance.doStep(this)
    stepFinished() -> waveformInstance.stepFinished(this)
    getVoltage() -> waveformInstance.getVoltage(this)
    draw() -> battery glyph (DC) | waveformInstance.draw(g,c,this) (else)
    edit slot 1 -> recreate strategy via Waveform.create(type, old)

Rail differences: `stamp()` calls `waveformInstance.stampRail(this)`;
`doStep()` is skipped when the waveform is DC.

Current source (`CurrentElm`): `stamp()` checks `broken` flag — stamps a
`1e8 Ω` resistor if no current path exists, else
`stampCurrentSource(n0, n1, currentValue)`.

## 3. Mechanisms  {#C_ESRC_03}

### 3.1. Core Algorithms  {#C_ESRC_03_01}

**Waveform plug-in (see C_WFM):** `Waveform` strategy owns the
time-dependent value. 8 waveform variants: DC, AC, Square, Triangle,
Sawtooth, Pulse, Noise, Var. `VoltageElm` keeps an `int waveform` mirror
in sync with `waveformInstance.getType()`.

**Rail-vs-full source:** `RailElm` shrinks `VoltageElm` to 1 post by
overriding `getPostCount()=1`, `getVoltageDiff()=getNodeVoltage(0)`,
`hasGroundConnection(0)=true`, and delegating to
`waveformInstance.stampRail` / `drawRail`.

**DC fast path:** For `WF_DC`, the waveform's `stamp/stampRail` uses the
value-fixed `stampVoltageSource(n0, n1, vs, V)` form; `doStep` is
skipped; `getVoltage` returns `maxVoltage + bias` with no time dependence.

**SweepElm custom accumulator:** Lives outside the Waveform system.
Maintains its own `frequency`, `freqTime` (phase accumulator), `fadd` /
`fmul` (per-step coefficients), and `dir` (+1 / -1 for bidirectional
sweeps). `startIteration` computes `v = sin(freqTime)·maxV`, advances
phase, and updates frequency (linear fadd, log fmul).

**Adjustable slider (VarRailElm):** Creates `Adjustable` entry in
`circuitDocument.adjustableManager` keyed by `(this, EDIT_VOLTAGE=3)`;
repurposes `waveformInstance.frequency` as the current slider value.

### 3.2. Edge Cases  {#C_ESRC_03_02}

- Legacy `FLAG_COS=2` → cleared on load, `phaseShift = π/2`.
- `FLAG_PULSE_DUTY=4` — when absent on pulse waveform, `dutyCycle` is
  coerced to `1/(2π)` (legacy default).
- On waveform-type change: into DC → zero bias; into Pulse →
  `dutyCycle = defaultPulseDuty`; out of Pulse → `dutyCycle = 0.5`.
- `VarRailElm.setEditValue` auto-swaps bias/maxVoltage if max < bias.
- `CurrentElm.broken` — set by analyzeCircuit when there is no current
  path; `stamp()` then stamps 100 MΩ stand-in to keep matrix non-singular.
- `SweepElm` clamps minF/maxF to `1/(8·Δt)` (Nyquist-ish stability).
- `ExtVoltageElm.setVoltage` ignores NaN.

## 4. Integration Points  {#C_ESRC_04}

### 4.1. Dependencies  {#C_ESRC_04_01}

- [C_ELB](./element-base.concept.md) — `CircuitElm`, `ElmGeometry`,
  `BaseCircuitElm`.
- [C_WFM](./waveforms.concept.md) — `Waveform` strategy and 8 subclasses.
- Adjustable subsystem (`adjustableManager`) — VarRailElm only.
- GWT `Scrollbar` / `Label` — VarRailElm only.
- `CustomLogicModel.escape/unescape` — ExtVoltageElm.
- `io.json.UnitParser` — CurrentElm `current` property.

### 4.2. API Surface  {#C_ESRC_04_02}

Dump-types: `'v'` (118) VoltageElm, `'R'` (82) RailElm family, `'i'`
(105) CurrentElm, `170` SweepElm, `172` VarRailElm, `418` ExtVoltageElm.
JSON type names cover `VoltageSource{DC,AC,Square,Triangle,Sawtooth,
Pulse,Noise,Var}`, `ACRail`, `SquareRail`, `VariableRail`,
`ExternalVoltage`, `CurrentSource`, `SweepGenerator`, `NoiseSource`.
JS bridge `ExtVoltageElm.setVoltage(v)` is the primary external-write
path.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
