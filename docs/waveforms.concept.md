# Waveforms — Signal Generators for Voltage-Source Elements  {#C_WFM}

> **Code:** C_WFM
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard-doc-gen
>
> **Depends on:** [C_ELB](./element-base.concept.md), [C_RND](./rendering-primitives.concept.md), [C_UTL](./util-locale-log.concept.md), C_EIC (edit-info-contract, pending)
> **Used by:** — (will be filled by higher layers)
> **Spike:** —
> **Specification:** [SP_WFM](./waveforms.sp.md)
> **Plan:** [waveforms.plan.md](./waveforms.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/domain-core__waveforms.md` (9 files in `client/element/waveform/`).
>
> Strategy-pattern signal generators that supply `V(t)` to voltage/current-source elements. Each concrete waveform encapsulates one shape (DC, AC, Square, Triangle, Sawtooth, Pulse, Noise, Var) and owns its own draw, edit, JSON, and (optionally) stamping behaviour.

## 1. Philosophy  {#C_WFM_01}

### 1.1. Core Principle  {#C_WFM_01_01}

`VoltageElm` (and `RailElm`) owns **one** `Waveform waveformInstance` and delegates per-step voltage computation, icon drawing, info-pane rows, dialog rows, stamping, JSON typing, and JSON properties to it. This lets the element swap behaviour at runtime via a single field assignment instead of branching on a 7-way `switch` in every method.

The base class `Waveform` (9 files total, 8 concrete subclasses) defines seven abstract methods and supplies overridable defaults for a dozen more.

### 1.2. Design Constraints  {#C_WFM_01_02}

- **No per-step phase accumulator** — periodic generators compute `w(elm) = 2π·(simulator.t − freqTimeZero)·frequency + phaseShift` from absolute simulator time every call.
- **DC analysis fast-path** — every periodic `getVoltage` short-circuits on `circuitInfo.dcAnalysisFlag` and returns `bias` for the DC-operating-point pass (Noise is the exception — see backlog).
- **Parameter bag is shared across types.** Type change via `Waveform.create(type, old)` preserves all 7 shared fields; only truly incompatible params reset on type transition (Pulse↔non-Pulse duty cycle).
- **Cross-package cycle tolerated.** Waveforms reach back into `VoltageElm`/`RailElm` for static helpers and occasionally narrow-cast to `VarRailElm` for slider access. Cycle exists at compile time via types + statics, not at runtime ownership.

## 2. Domain Model  {#C_WFM_02}

### 2.1. Key Entities  {#C_WFM_02_01}

Class hierarchy:

```
Waveform [abstract]
├── DCWaveform        (WF_DC = 0)
├── ACWaveform        (WF_AC = 1)
├── SquareWaveform    (WF_SQUARE = 2)
├── TriangleWaveform  (WF_TRIANGLE = 3)
├── SawtoothWaveform  (WF_SAWTOOTH = 4)
├── PulseWaveform     (WF_PULSE = 5)
├── NoiseWaveform     (WF_NOISE = 6)
└── VarWaveform       (WF_VAR = 7; partial-strategy, glued to VarRailElm)
```

`Waveform.create(type, old)` is the factory, dispatching on integer `type` and calling `copyFrom(old)` to preserve shared parameters across type changes. `WF_VAR` is legacy — `create` returns a `DCWaveform` for it; `VarWaveform` is only instantiated directly by `VarRailElm`.

### 2.2. Data Flows  {#C_WFM_02_02}

```
              stamp()                doStep()        getVoltage()     stepFinished()
VoltageElm ─────────► Waveform ─────────► Waveform ────────► Waveform ─────────► Waveform
              stamp                updateVS          w(elm) + shape    (noise latch)
```

`VoltageElm.draw()` queries `hasCircle()`, then calls `draw(g, center, this)`; `RailElm.draw()` calls `drawRail(g, this)`. Editing: `VoltageElm.getEditInfo(n)` reserves slot 1 for the waveform-type chooser, delegates all other slots to `waveformInstance.getEditInfo`. JSON: `getJsonTypeName()` supplies the element-type label (`VoltageSourceAC`, etc.); rail variants use `getJsonRailTypeName()`.

## 3. Mechanisms  {#C_WFM_03}

### 3.1. Core Algorithm  {#C_WFM_03_01}

Per-step voltage computation uses `w(elm) = 2π·(simulator.t − freqTimeZero)·frequency + phaseShift`. Each concrete type post-processes differently:

- **DC**: `maxVoltage + bias` (time-independent; `stamp` uses value-fixed `stampVoltageSource` overload so no per-step update).
- **AC**: `sin(w) · maxVoltage + bias`; DC-analysis early-out returns `bias`.
- **Square**: `bias ± maxVoltage` flipping at `dutyCycle · 2π`; symmetric (low rail = `bias − maxVoltage`).
- **Pulse**: `((w mod 2π) < 2π·dutyCycle) ? maxVoltage+bias : bias`; asymmetric (low rail = `bias`, not `bias − maxVoltage`).
- **Triangle**: piecewise-linear `triangleFunc(w mod 2π) · maxVoltage + bias`.
- **Sawtooth**: ramp `bias + (w mod 2π)·maxVoltage/π − maxVoltage`.
- **Noise**: `noiseValue` latched in `stepFinished` from `RandomUtils.getRandom().nextDouble()·2 − 1` scaled+biased. Sample-and-hold over the Newton loop.
- **Var**: reads `vrelm.slider.getValue()` (0–100) and remaps to `[bias, maxVoltage]`, writing back into `frequency` (unusual repurpose).

Default `doStep(VoltageElm)` calls `updateVoltageSource(n0, n1, voltSource, getVoltage())` unless `isDC()` returns true.

### 3.2. Edge Cases  {#C_WFM_03_02}

- **`freqTimeZero` anti-glitch** reset only on `VoltageElm.reset()`; mid-run frequency changes cause one-shot phase jump.
- **Legacy `FLAG_COS`** auto-migrates to `phaseShift = π/2` on load; one-way migration.
- **`FLAG_PULSE_DUTY`** absence → `dutyCycle` coerced to `1/(2π)` (legacy default).
- **Truncated dump line** → `VoltageElm` ctor try/catch falls back to default-constructed waveform.
- **Unknown type in `Waveform.create`** → fallback to `DCWaveform`.
- **`VarWaveform` with non-VarRailElm** → `getEditInfo` returns null for all rows.
- **NoiseWaveform deterministic icon** via xorshift hashed by `elementId.hashCode()` so icon doesn't flicker.

## 4. Integration Points  {#C_WFM_04}

### 4.1. Dependencies  {#C_WFM_04_01}

- **[C_ELB](./element-base.concept.md)** — compile-time types `VoltageElm`, `RailElm`, `VarRailElm`, `CircuitElm`; reaches back for statics (`getUnitText`, `drawThickLine`, `PI`, `PI_2`, color helpers).
- **[C_RND](./rendering-primitives.concept.md)** — `Graphics`, `Point`.
- **[C_UTL](./util-locale-log.concept.md)** — `util.Locale` (DCWaveform only); `RandomUtils` (NoiseWaveform).
- **[C_EIC](./edit-info-contract.concept.md)** — `dialog.EditInfo` (imported by all 9 files).

### 4.2. API Surface  {#C_WFM_04_02}

Abstract methods: `getType`, `getVoltage`, `draw`, `getInfo`, `getEditInfo`, `setEditValue`, `getJsonTypeName`.

Overridable concretes: `drawRail`, `getJsonProperties`, `applyJsonProperties`, `copyFrom`, `getJsonRailTypeName`, `isDC`, `isPulse`, `stamp`, `stampRail`, `doStep`, `hasCircle`, `showFrequency`, `usesShortLeads`, `stepFinished`, `w` (protected helper).

Factory: `Waveform.create(int type, Waveform old)`.

The cross-package cycle `element ↔ element/waveform` is documented (12 + 15 edges per `dependency_graph.md`); it is compile-time (types + statics), not runtime ownership.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
