# Implementation Plan: Sequential Logic Elements  {#PL_ELS}

> **Code:** PL_ELS
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_ELS](./elements-logic-sequential.concept.md)
> **Specification:** [SP_ELS](./elements-logic-sequential.sp.md)
> **Depends on plans:** [PL_ELB](./element-base.plan.md)
> **Used by plans:** —
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-logic-sequential.md](../.dev_flow/onboard/analysis/domain-core__cat-logic-sequential.md)

## Goal

Stable catalog of 12 sequential-logic elements (plus 2 combinational
adders tracked here) built on the `ChipElm` pin grid + `lastClock`
edge detection + state-snapshot-in-pins idiom.

## Progress

- [x] Phase 1 — LatchElm (edge + transparent modes) [DONE]
- [x] Phase 2 — D / JK / T flip-flops with async R/S [DONE]
- [x] Phase 3 — CounterElm + Counter2Elm + RingCounterElm [DONE]
- [x] Phase 4 — PisoShiftElm + SipoShiftElm [DONE]
- [x] Phase 5 — SeqGenElm (int[]-packed bit stream) [DONE]
- [x] Phase 6 — MonostableElm (simulator.t based) [DONE]
- [x] Phase 7 — TimerElm (555 comparator + output driver) [DONE]
- [x] Phase 8 — HalfAdderElm + FullAdderElm (combinational, misfiled) [DONE]

## Phases

### All phases [DONE]

Catalog fully implemented per SP_ELS.

## Backlog

Issues surfaced by the analysis:

1. **Counter2Elm CLR is effectively asynchronous** despite 74161/163
   claim. `!pins[clr].value` evaluated outside the clock-edge block.
   Either the behavior is wrong or the chip-name comment is aspirational.
2. **No metastability modeling** — no setup/hold violation injection,
   no Q-undefined states. Deliberate for educational simulator but
   should be documented.
3. **Reset initial-condition inconsistency** — DFlipFlop/TFlipFlop
   explicitly force `pins[2].value=true` and direct-set Q̄; JKFlipFlop
   relies on inherited `ChipElm.reset()` + justLoaded deferral.
4. **TimerElm uses 5 k / 10 k divider** (2/3 Vcc at CTL) rather than
   textbook 3 × 5 kΩ. Absolute impedance of lower leg half what real
   555 has.
5. **SeqGenElm.setChipEditValue integer-divides `bitCount/Integer.SIZE`**
   — rounds down; a 7-bit sequence reserves 0 ints and throws AIOOBE on
   next write. Should be `(bitCount + 31) / 32`.
6. **HalfAdderElm and FullAdderElm mis-filed** — purely combinational;
   should move to the combinational-logic category.
7. **PisoShiftElm legacy `dataIndex=-1` sentinel** — compatibility
   crutch; removing it silently changes round-trip for old circuits.
8. **CounterElm has no LOAD pin** — parallel-load counter requires
   Counter2Elm.
9. **RingCounterElm auto-recovery** — force `pins[2].value=true` when
   all outputs low. Undocumented; silent "self-healing" surprises
   students debugging stuck-at faults.
10. **MonostableElm uses `simulator().t` directly** — not safe if time
    base is reset mid-run without full element reset. Compare
    TimerElm which is voltage-driven only.
11. **ClockElm mis-categorized structurally** (cross-cat with combinational)
    — called out in that category's backlog.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial retrospective plan |
