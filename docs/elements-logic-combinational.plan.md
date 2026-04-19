# Implementation Plan: Combinational Logic Elements  {#PL_ELC}

> **Code:** PL_ELC
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_ELC](./elements-logic-combinational.concept.md)
> **Specification:** [SP_ELC](./elements-logic-combinational.sp.md)
> **Depends on plans:** [PL_ELB](./element-base.plan.md), [PL_WFM](./waveforms.plan.md)
> **Used by plans:** —
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-logic-combinational.md](../.dev_flow/onboard/analysis/domain-core__cat-logic-combinational.md)

## Goal

Stable catalog of discrete-logic elements drawn as standalone symbols
(not ChipElm) — gates, inverters, tri-state, delay buffer, clock.

## Progress

- [x] Phase 1 — GateElm abstract base + AndGateElm, OrGateElm, XorGateElm [DONE]
- [x] Phase 2 — NandGateElm, NorGateElm (isInverting=true variants) [DONE]
- [x] Phase 3 — InverterElm + slew limiting [DONE]
- [x] Phase 4 — InvertingSchmittElm state machine + hysteresis [DONE]
- [x] Phase 5 — TriStateElm resistor-network Hi-Z [DONE]
- [x] Phase 6 — DelayBufferElm edge-timeout [DONE]
- [x] Phase 7 — ClockElm (RailElm subclass with FLAG_CLOCK) [DONE]

## Phases

### All phases [DONE]

Catalog fully implemented per SP_ELC.

## Backlog

Issues surfaced by the analysis:

1. **`OrGateElm.drawGatePolygon` tests `this instanceof XorGateElm`** —
   parent knows about child (OCP violation). `XorGateElm.drawGatePolygon`
   should override instead.
2. **`GateElm.lastHighVoltage` is static** — per-JVM mutable state;
   editing one gate's voltage silently becomes default for new gates.
   Same for `lastSchmitt`.
3. **`TriStateElm` ctor-from-dump sets `r_off_ground=0`** vs fresh-ctor
   `1e8` — round-trip is not identity.
4. **`DelayBufferElm` is NOT a queue** — one-pending-edge model can
   absorb pulses shorter than `delay`. Misleading name; document
   explicitly or implement a ring-buffer variant.
5. **`GateElm.oscillationCount` uses `RandomUtils`** — non-deterministic;
   replay divergence; hostile to test harnesses.
6. **ClockElm mis-categorized structurally** — persisted as `RailElm`,
   distinguished only by `FLAG_CLOCK` bit. Stripping flags silently
   converts clocks to plain rails. No dedicated dump type; no
   future-proofing for clock-specific parameters.
7. **Parameter-storage inconsistency** — GateElm stores `highVoltage`,
   InvertingSchmittElm stores `logicOnLevel`/`logicOffLevel` (two
   rails), DelayBufferElm stores `highVoltage` + `threshold`. Three
   different parameter schemas. Cross-element chains require per-element
   setup.
8. **GateElm dumps last output voltage (raw volts)** rather than boolean
   state — editing `highVoltage` in a saved dump can flip load state.
9. **Only InverterElm overrides `startIteration()`** — other elements
   read `lastOutputVoltage` mid-solve inside `doStep`. Subtle timing
   inconsistency.
10. **No standalone NotGateElm** — users pick InverterElm (which adds
    slew limiting) or 1-input NAND/NOR. Not a bug, but worth noting.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial retrospective plan |
