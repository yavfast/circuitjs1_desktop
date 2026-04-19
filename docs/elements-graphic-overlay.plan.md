# Implementation Plan: Graphic Overlay Elements  {#PL_EGR}

> **Code:** PL_EGR
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_EGR](./elements-graphic-overlay.concept.md)
> **Specification:** [SP_EGR](./elements-graphic-overlay.sp.md)
> **Depends on plans:** [PL_ELB](./element-base.plan.md), [PL_UTL](./util-locale-log.plan.md), [PL_GEO](./geometry.plan.md), [PL_RND](./rendering-primitives.plan.md)
> **Used by plans:** renderer, editor, file I/O glue
>
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-graphic-and-magnetics.md](../.dev_flow/onboard/analysis/domain-core__cat-graphic-and-magnetics.md)
> (graphic cluster only)

## Goal

Document the three shipped graphic-overlay elements (BoxElm, TextElm,
LineElm) and capture small backlog items around live-value templating,
feedback on creation failure, and draw-time geometry mutation.

## Technology Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Base class | `GraphicElm` | Zero-post contract with standard persistence |
| Text escape | `CustomLogicModel.unescape` via FLAG_ESCAPE | Whitespace-safe dump |
| Min-size rejection | `creationFailed()` | Prevents accidental zero-size elements |
| Text bbox | mutated in `draw()` | Keeps selection rect in sync with font metrics |

## Progress

- [DONE] Phase 1 — BoxElm (dashed rectangle)
- [DONE] Phase 2 — TextElm (multi-line label + bar flag)
- [DONE] Phase 3 — LineElm (straight diagonal)
- [backlog] Phase 4 — UX and data-flow refinements

## Phases

### Phase 1 — BoxElm [DONE]

**Implements:** [SP_EGR_01_03](./elements-graphic-overlay.sp.md#SP_EGR_01_03)

- Dashed outline; edge-only hit test; min 32 px.

### Phase 2 — TextElm [DONE]

**Implements:** [SP_EGR_01_02](./elements-graphic-overlay.sp.md#SP_EGR_01_02)

- Multi-line; FLAG_BAR overline; FLAG_ESCAPE dump.

### Phase 3 — LineElm [DONE]

- Straight line; min 16 px.

## Backlog

Items deferred (from analysis Issues):

1. Consider live-value templating in TextElm (`${V0}` substitution) —
   analysis flags abandoned `FLAG_CENTER` evidence (Issue #8).
2. Show user feedback when BoxElm/LineElm creation fails due to min-size
   rejection (Issue #9).
3. Move TextElm bbox computation from `draw()` to `setPoints()` so
   draw-free geometry queries are consistent (Issue #12).
4. Harden dump-type dispatch so single-char codes (`'b'`, `'x'`) do
   not clash with token usage elsewhere (Issue #7).

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
