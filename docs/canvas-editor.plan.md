# Implementation Plan: Canvas Editor  {#PL_EDI}

> **Code:** PL_EDI
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_EDI](./canvas-editor.concept.md)
> **Specification:** [SP_EDI](./canvas-editor.sp.md)
> **Depends on plans:** PL_ELB, PL_RND, PL_GEO, PL_UTL, PL_SIM, PL_DOC
> **Used by plans:** PL_UND, PL_MEN, PL_CLP
>
> Reverse-engineered plan; implementation is complete in `client/` (CircuitEditor, CircuitEditorEventHandler, CircuitRenderer, MouseMode).

## Goal

Deliver the interactive editor shell — event routing, dual-field mode machine, hit-testing, drag/creation/selection, and coalescing render pipeline — so the user can directly manipulate circuits over a single shared Canvas while multiple documents coexist.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Event registration | single static Canvas handler | keeps DOM wiring stable across tab switches |
| Mode representation | dual fields (`mouseMode` + `tempMouseMode`) | modifier keys change behaviour per-gesture |
| Drag-upgrade timing | 150 ms timer | prevents spurious moves on quick clicks |
| Render coalescing | scheduleDeferred + flag | decouples mutation from paint cadence |
| Transform storage | `double[6]` affine | direct handoff to Context2d.setTransform |

## Progress

- [x] Phase 1 — MouseMode enum
- [x] Phase 2 — CircuitEditor state + constructor
- [x] Phase 3 — Event handlers (onMouseDown/Move/Up/Wheel/Click/ContextMenu/DoubleClick)
- [x] Phase 4 — MouseMode state machine (modifier table + delayed upgrade)
- [x] Phase 5 — Hit-testing (findElm/findElmInScope/findElmByPost/mouseIsOverSplitter)
- [x] Phase 6 — Drag operations (dragAll/Row/Column/Selected/Post/Splitter/selectArea)
- [x] Phase 7 — CircuitEditorEventHandler multiplex
- [x] Phase 8 — CircuitRenderer (canvas/transform/timers)
- [x] Phase 9 — Render pipeline (setupFrame → drawCircuit → drawBottomArea)
- [x] Phase 10 — Export paths (drawCircuitInContext/getCircuitAsCanvas/SVG)

## Backlog

Items from `.dev_flow/onboard/analysis/layer3__editor-interaction.md` §Issues:

- **String identity comparisons in ActionManager** (relates here via menu→editor delegates).
- **Full-snapshot undo scales with circuit size** — handled in C_UND backlog.
- **`drawCircuitInContext` flips `printableCheckItem`/`dotsCheckItem`** during export; background timers during export see wrong state.
- **`mouseMode`+`tempMouseMode`+`mouseModeStr` triple** can drift; `setMouseMode(MouseMode)` does not sync `mouseModeStr`.
- **`dragElm` defensively deleted twice** (L767 and L789–791).
- **`CircuitRenderer.render` checks `simulator.stopElm` twice with empty bodies** — dead code.
- **Latent static state** — `Graphics.isFullScreen`, color caches may break multi-document mode.
- **No event-capture/priority model** — two simultaneous editors (pop-out tab) would require rewiring.
- **Comment at `CircuitEditor` L62–64** documents prior hover-ref global removal; similar patterns remain.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
