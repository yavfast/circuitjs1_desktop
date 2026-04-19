# Implementation Plan: Source Elements  {#PL_ESRC}

> **Code:** PL_ESRC
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_ESRC](./elements-sources.concept.md)
> **Specification:** [SP_ESRC](./elements-sources.sp.md)
> **Depends on plans:** [PL_ELB](./element-base.plan.md), [PL_WFM](./waveforms.plan.md)
> **Used by plans:** —
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-sources.md](../.dev_flow/onboard/analysis/domain-core__cat-sources.md)
>
> Retrospective plan covering the 11-element source catalog.

## Goal

Stable catalog of voltage/current source elements with pluggable waveform
strategies for 2-post and 1-post (rail) variants, plus bespoke
`CurrentElm` and `SweepElm`.

## Progress

- [x] Phase 1 — VoltageElm branch base + waveform plug-in delegation [DONE]
- [x] Phase 2 — DC/AC 2-post voltage source subclasses [DONE]
- [x] Phase 3 — RailElm + waveform rail subclasses (AC, Square, Noise) [DONE]
- [x] Phase 4 — VarRailElm + Adjustable binding [DONE]
- [x] Phase 5 — ExtVoltageElm JS bridge [DONE]
- [x] Phase 6 — CurrentElm with broken-path guard [DONE]
- [x] Phase 7 — SweepElm custom frequency accumulator [DONE]

## Phases

### All phases [DONE]

Catalog fully implemented per SP_ESRC. See per-element file:line
citations in the specification table.

## Backlog

Issues surfaced by the analysis:

1. **VoltageElm is non-abstract** despite being the branch base — can be
   instantiated directly. Consider making abstract or documenting.
2. **DCVoltageElm / ACVoltageElm contribute almost nothing** — exist only
   to bind default `WF_*` for UI creation and re-brand JSON type name.
   Factory keys on `.class`; subclass split has no other purpose.
3. **NoiseElm waveform=6 in dump with dead `'n'` reader** in
   `CirSim.createCe` — history fossil. Either re-enable unique dump
   type or delete legacy reader.
4. **VarRailElm repurposes `waveformInstance.frequency`** as slider
   voltage. Named field would be safer.
5. **ExtVoltageElm uses WF_AC as placeholder** to dodge DC `doStep`
   short-circuit. Waveform instance leaks into dump (waveform=1) and
   info panel. Element doesn't actually need a waveform.
6. **CurrentElm doesn't explicitly override `getVoltageSourceCount`** —
   inherits default 0; a future default change would break it silently.
7. **CurrentElm.broken mutated externally** by `analyzeCircuit` via
   `setBroken` — timing contract not documented in-class.
8. **SweepElm.draw uses `w = 2` hard-coded "static icon"** — originally
   encoded time-varying animation. Code around it looks like it expects
   w to vary.
9. **SweepElm.getVoltage() not overridden** — default returns 0, which
   is incorrect. `getVoltageDiff()` is overridden correctly; anyone
   calling `elm.getVoltage()` gets 0.
10. **VarRailElm.doStep always updates** even when slider hasn't moved.
    Minor CPU waste; a dirty bit would skip the call.
11. **Dump-type allocation is magic-numbered** — 172, 170, 418, `'v'`
    (118), `'R'` (82), `'i'` (105). No central registry; same issue as
    element-base.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial retrospective plan |
