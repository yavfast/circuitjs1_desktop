# Implementation Plan: Scope Visualization  {#PL_SCP}

> **Code:** PL_SCP
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_SCP](./scope-visualization.concept.md)
> **Specification:** [SP_SCP](./scope-visualization.sp.md)
> **Depends on plans:** PL_ELB, PL_RND, PL_MDS, PL_UTL, PL_DOC
> **Used by plans:** PL_EDI, PL_MEN, PL_DSP

## Goal

Deliver the oscilloscope subsystem — time-domain, XY/2D, FFT, trigger, history, stats — with a document-level manager and a floating-scope bridge through `ScopeElm.elmScope`.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Ring buffer size | power of two | fast mod via `& mask` |
| Aggregation | min/max per pixel column | correct visual for fast signals |
| AC coupling | IIR high-pass, speed-scaled | cheap per-sample |
| Trigger | snapshot (TriggerFrame) | stable display without back-pressure on the simulator |
| Stats | delegate to `CircuitMath` | shared with other readouts |
| Layout | fixed 20-slot array | predictable memory, simple menu indexing |

## Progress

- [x] Phase 1 — ScopePlot ring buffer + AC coupling
- [x] Phase 2 — Scope core (rect, visibility flags, resetGraph)
- [x] Phase 3 — ScopeManager layout (setupScopes)
- [x] Phase 4 — Trigger state machine (findTriggerIndex, TriggerFrame, updateTimeBaseForDraw)
- [x] Phase 5 — History capture + overlay
- [x] Phase 6 — 2D/XY mode (imageCanvas persistence, alpha fade)
- [x] Phase 7 — FFT path (drawFFT, grid, log/linear)
- [x] Phase 8 — Stats (RMS/Average/DutyCycle/Frequency via CircuitMath)
- [x] Phase 9 — Cursor + info texts
- [x] Phase 10 — Dump/undump (FLAG_PLOTS + legacy branches)
- [x] Phase 11 — ScopePopupMenu + ScopeCheckBox glue
- [x] Phase 12 — ScopeElm docking bridge

## Backlog

From `.dev_flow/onboard/analysis/layer3__scopes.md` §Issues:

- **Fixed Scope[20] cap** — silently drops scopes beyond.
- **String-identity menu keys** (`handleMenu` uses `==`).
- **Static cursor state** (`cursorTime/cursorUnits/cursorScope`, `lastManDivisions`) — multi-document coexistence broken.
- **2731-line Scope class** — prime split candidate (render / trigger / buffer / persistence).
- **FLAG_PLOTS legacy/new split** duplicated in `undump`.
- **`text != ""` object-identity comparison** (Scope.java:2033).
- **History frame allocation** per capture; not pooled.
- **Visibility mismatch** on `ScopePlot` fields (public/package mix).
- **Trigger re-arm flag consistency** — easy to de-sync `triggerFrame=null` vs `singleTriggered`.
- **Trigger search cost** O(maxSearch) per frame per scope.
- **`ScopeCheckBox.setValue` override redundant** w/ GWT default.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
