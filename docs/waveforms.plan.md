# Implementation Plan: Waveforms  {#PL_WFM}

> **Code:** PL_WFM
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_WFM](./waveforms.concept.md)
> **Specification:** [SP_WFM](./waveforms.sp.md)
> **Depends on plans:** [PL_ELB](./element-base.plan.md)
> **Used by plans:** — (will be filled by higher layers)
>
> Reverse-engineered plan for the 9 files in `client/element/waveform/`.

## Goal

Provide a strategy-pattern signal-generator family that `VoltageElm`/`RailElm` delegates per-step value computation, drawing, editing, stamping, and serialization to — enabling runtime waveform switching via a single `waveformInstance` field.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Language | Java 17 + GWT | Project baseline. |
| Pattern | Strategy with `Waveform` abstract base + factory | Runtime swap; shared parameter bag. |
| Factory | `Waveform.create(int type, Waveform old)` | Preserves shared fields via `copyFrom`. |
| Randomness | Singleton `RandomUtils.getRandom()` | Consistent across circuit; noise latched per step. |
| Phase model | Time-derived via `w(elm)` | No per-step accumulator; freqTimeZero anti-glitch origin. |

## Progress

- [x] Phase 1 — Waveform base + factory
- [x] Phase 2 — DCWaveform
- [x] Phase 3 — ACWaveform
- [x] Phase 4 — SquareWaveform + PulseWaveform
- [x] Phase 5 — TriangleWaveform + SawtoothWaveform
- [x] Phase 6 — NoiseWaveform
- [x] Phase 7 — VarWaveform (VarRailElm slider binding)

## Phases

### Phase 1 — Waveform base (`client/element/waveform/Waveform.java`) [DONE]

**Implements:** [SP_WFM_01_01](./waveforms.sp.md#SP_WFM_01_01), [SP_WFM_02](./waveforms.sp.md#SP_WFM_02)

Delivered: 7 shared fields, 7 abstract methods, 12 overridable defaults (`stamp`, `stampRail`, `doStep`, `drawRail`, `getJsonProperties`, `applyJsonProperties`, `copyFrom`, `getJsonRailTypeName`, `isDC`, `isPulse`, `hasCircle`, `showFrequency`, `usesShortLeads`, `stepFinished`), protected `w(elm)` helper, `WF_*` ordinals, `create(type, old)` factory with DC fallback.

### Phase 2 — DCWaveform [DONE]

Delivered: constant output `maxV + bias`; value-fixed stamp overload; no-op `draw`; rail label with sign; `isDC=true`, `hasCircle=false`; `getJsonProperties` omits freq/phase/duty.

### Phase 3 — ACWaveform [DONE]

Delivered: `sin(w)·maxV + bias`; DC short-circuit; `ACRail` JSON name; Vrms info (bias==0); wavelength info (f>500Hz).

### Phase 4 — Square + Pulse [DONE]

Delivered: symmetric Square (`bias ± maxV`), asymmetric Pulse (low=bias), duty-cycle editing, `isPulse` for Pulse, `SquareRail` JSON name + `CLK` label for RailElm.FLAG_CLOCK.

### Phase 5 — Triangle + Sawtooth [DONE]

Delivered: `triangleFunc(x)` piecewise-linear helper; sawtooth ramp `bias + (w mod 2π)·maxV/π − maxV`.

### Phase 6 — NoiseWaveform [DONE]

Delivered: `stepFinished` latches `rand·maxV + bias`; sample-and-hold via `noiseValue`; deterministic hashed-seed icon (xorshift seeded by elementId.hashCode).

### Phase 7 — VarWaveform [DONE]

Delivered: slider read (0–100 remapped to `[bias, maxV]`); `frequency` field repurposed as current output V; `usesShortLeads=true`; `showFrequency=false`; `VariableRail` JSON name; edit slots Min V / Max V / Slider Text; `VarRailElm`-only guard in getEditInfo.

## Backlog

Items deferred from current cycle (from `.dev_flow/onboard/analysis/domain-core__waveforms.md` §Issues):

- **`WF_VAR` factory fallthrough (#2).** `Waveform.create(WF_VAR, …)` silently returns `DCWaveform`. Any caller expecting `VarWaveform` gets a DC source without warning.
- **`NoiseWaveform` missing `dcAnalysisFlag` short-circuit (#6).** During DC op-point pass, noise injects last latched value (possibly non-zero) rather than returning `bias`.
- **`freqTimeZero` only resets on `reset()` (#4).** Mid-run frequency edits cause a one-shot phase jump. Document or reset on frequency-edit path.
- **`VarWaveform.frequency` field misuse (#1).** Storing current output voltage in `frequency` is confusing; rename to `currentValue` (or similar) would be clearer.
- **Dump always emits 5 params (#3).** Even DC/Noise carry unused `freq/phase/duty` slots; diff noise and bandwidth.
- **Edit slot numbering ad-hoc (#5).** Slots 0, 2, 3, 4, 5 used; slot 1 implicitly reserved. Not enforced — a subclass returning slot 1 would shadow the type chooser.
- **`doStep` `!isDC()` guard redundant for DC (#7).** DC already overrides stamp; the guard is asymmetric protection for a hypothetical future subclass that forgets to override.
- **No amplitude convention comment (#8).** AC=peak, Square/Sawtooth=peak-above-bias, Pulse=full-high; module-level table would reduce confusion.
- **`getJsonProperties` doesn't always call `super` (#9).** DC/Noise/Var write their own bag; Square/Pulse call super. Intentional but duplicates code.
- **Factory `switch` on int (#10).** Adding a new waveform requires editing 3 places. Registry/enum approach overkill for 8 types but worth noting.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
