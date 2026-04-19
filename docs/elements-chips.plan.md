# Implementation Plan: Complex IC Elements  {#PL_ECH}

> **Code:** PL_ECH
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_ECH](./elements-chips.concept.md)
> **Specification:** [SP_ECH](./elements-chips.sp.md)
> **Depends on plans:** [PL_ELB](./element-base.plan.md), [PL_UTL](./util-locale-log.plan.md), [PL_GEO](./geometry.plan.md), [PL_RND](./rendering-primitives.plan.md)
> **Used by plans:** circuit element factory, renderer, file I/O glue
>
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-complex-ics.md](../.dev_flow/onboard/analysis/domain-core__cat-complex-ics.md)
>
> Seven concrete ChipElm subclasses shipped in production; this plan
> documents the completed phases and backlog issues lifted from the
> analysis `Issues` section.

## Goal

Capture the implementation state of the seven Complex IC elements so
follow-up refactors (dump-type registry, SRAM guards, Mux range
reconciliation) can be tracked and scoped without re-discovering context.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Base class | `ChipElm` | Shared pin layout, chip-envelope render, edit dialog |
| Reference-voltage duality (ADC/DAC) | separate V+ post | Mirrors real chips; but leaves `highVoltage` aliased (see backlog) |
| SRAM backing store | sparse `HashMap<Integer,Integer>` | Bounded memory regardless of address width |
| SRAM OE gating | variable resistor (1 Ω / 1e8 Ω) | Hi-Z via soft-switch; avoids topology rewrite |
| SevenSegElm drive | optional Diode companions | Accurate LED brightness via Newton |
| Dump-type codes | numeric 157/166/167/184/185/197/413 | Legacy compatibility |

## Progress

- [DONE] Phase 1 — ADCElm / DACElm (data converters)
- [DONE] Phase 2 — MultiplexerElm / DeMultiplexerElm (routing)
- [DONE] Phase 3 — SevenSegDecoderElm (combinational LUT)
- [DONE] Phase 4 — SevenSegElm (display + optional diodes)
- [DONE] Phase 5 — SRAMElm (memory + persistence + file-upload loader)
- [backlog] Phase 6 — cross-cutting cleanups (see Backlog below)

## Phases

### Phase 1 — Data Converters (`element/ADCElm.java`, `element/DACElm.java`) [DONE]

**Implements:** [SP_ECH_01_02](./elements-chips.sp.md#SP_ECH_01_02)

- ADC: per-bit voltage sources, integer-cast code, no rounding.
- DAC: threshold-based input sampling, single analog voltage source
  overridden in `doStep()`.

### Phase 2 — Routing (Mux/DeMux) [DONE]

**Implements:** [SP_ECH_01_03](./elements-chips.sp.md#SP_ECH_01_03)

- Configurable `selectBitCount`; pin rebuild on change.
- Mux: optional strobe + inverted output flags.

### Phase 3 — SevenSegDecoderElm [DONE]

**Implements:** [SP_ECH_02_01](./elements-chips.sp.md#SP_ECH_02_01)

- Hardcoded 16-row hex truth table; active-low BI; FLAG_BLANK_F for `F`.

### Phase 4 — SevenSegElm (display) [DONE]

**Implements:** [SP_ECH_01_05](./elements-chips.sp.md#SP_ECH_01_05)

- Three segment LUTs; two-pass draw.
- Diode companions for LED mode; non-convergence guard.

### Phase 5 — SRAMElm [DONE]

**Implements:** [SP_ECH_01_04](./elements-chips.sp.md#SP_ECH_01_04)

- HashMap storage; run-length dump with `-1`/`-2` sentinels.
- Textarea + file-picker loaders via `SRAMLoadFile`.

## Backlog

Items deferred from the current cycle (from analysis Issues):

1. ADC/DAC V+ vs `highVoltage` duality — decide authoritative source and
   consolidate; currently aliased (Issue #1).
2. Verify ADC output round-trips through ChipElm `dump()` / `applyJsonState`
   (Issue #2).
3. Reconcile MultiplexerElm select-bit range: UI declares 1..8 but setter
   enforces ≤ 6 (Issue #3).
4. Replace SRAM hardcoded 5 V output with `highVoltage` for 3.3 V / 1.8 V
   consistency (Issue #4).
5. Remove `SRAMElm.contentsOverride` static global; key by instance
   (Issue #5).
6. Guard SRAM dump against negative values in `map` that would collide with
   `-1`/`-2` sentinels (Issue #6).
7. Audit `SevenSegElm.setPinCount` ordering: `allocNodes; setupPins;
   setPoints` (Issue #7).
8. Address SevenSegElm backward-compat layout breakage on option edits
   (Issue #8).
9. Consider pure-BCD mode for SevenSegDecoderElm (blank on A..F; Issue #9).
10. Add strobe/inverted output to DeMultiplexerElm for Mux/DeMux symmetry
    (Issue #10).
11. Replace empty-catch ctor swallowing with telemetry / non-fatal warn
    (Issue #11).
12. Re-run SevenSegElm `stamp()` on `diodeDirection` change — currently
    requires external re-analysis (Issue #12).
13. Optimize SRAM internal-node count — doubles MNA matrix for large
    `dataBits` (Issue #13).
14. Reconcile `SevenSegDecoderElm.setChipEditValue` fall-through for n=0
    vs n=1 (Issue #14).

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
