# Implementation Plan: Adjustable Sliders  {#PL_ADJ}

> **Code:** PL_ADJ
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_ADJ](./adjustable-sliders.concept.md)
> **Specification:** [SP_ADJ](./adjustable-sliders.sp.md)
> **Depends on plans:** PL_LUW, PL_ELB, PL_EIC, PL_IOF
> **Used by plans:** PL_EDI, PL_MEN

## Goal

Provide live `Scrollbar`-driven parameter binding from an element's `EditInfo` field, with share-slider fan-out, auto-binding for `VarRailElm`, and round-trip through record `38`.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Widget | GWT `Scrollbar` (HORIZONTAL, 0..100) | simple, consistent integer range |
| Mapping | linear min↔max | sufficient for all current EditInfo types |
| Manager scope | per `CircuitDocument` | matches simulator + undo |
| Serialization | record `38` | legacy CircuitJS format compatibility |
| Sharing | owner-first ordering | single-pass undump resolution |

## Progress

- [x] Phase 1 — Adjustable state + ctors
- [x] Phase 2 — createSlider / deleteSlider / addSliderToDialog
- [x] Phase 3 — getSliderValue / setSliderValue + re-entrancy guard
- [x] Phase 4 — execute / executeSlider fan-out
- [x] Phase 5 — AdjustableManager list + dedupe
- [x] Phase 6 — addMissingVarRailVoltageAdjustables
- [x] Phase 7 — reorderAdjustables + dump / addAdjustable(undump)
- [x] Phase 8 — deleteSliders(elm) on element deletion
- [x] Phase 9 — SliderDialog editor popup

## Backlog

From `.dev_flow/onboard/analysis/layer3__sliders.md` §Issues:

- **Hardcoded `F1` in dump** (Adjustable.java:250) — `flags` token diverges from runtime value.
- **Debug `CirSim.console("slidertext …")`** left in SliderDialog.java:181.
- **Vestigial fields** `barmax`, `noCommaFormat` in SliderDialog.
- **Fixed `EditInfo[10]`** cap — AIOOB for >10 edit items.
- **Shared-slider dropdown relies on reorder invariant** — `break` on first shared entry.
- **Exception-swallowing in undump ctor** — malformed records partially initialized.
- **No cast safety** for `(CirSim) this.cirSim` down-casts.
- **`VarRailElm` auto-bindings** synthesized on every load — not persisted as record `38`.
- **Naming confusion** `SliderDialog` (editor popup) vs `SlidersDialog` (host panel).

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
