# Implementation Plan: Rendering Primitives  {#PL_RND}

> **Code:** PL_RND
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_RND](./rendering-primitives.concept.md)
> **Specification:** [SP_RND](./rendering-primitives.sp.md)
> **Depends on plans:** [PL_GEO](./geometry.plan.md)
> **Used by plans:** higher-layer rendering plans
>
> Retrospective plan covering as-built GWT canvas façade and visual attribute
> types.

## Goal

Document the as-built `Color`, `Font`, `Graphics` and record modernization
backlog (God-class split, `fillOval` π constant, `Font.==`, etc.).

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Language | Java 17 source | project-wide |
| Client compile target | GWT 2.12 | AWT unavailable |
| Canvas API | GWT `Context2d` | only cross-browser option |
| Font representation | prebuilt CSS short-form string | direct pass-through to canvas `font` |
| Color blend | linear RGB mix constructor | used for animation/gradient effects |
| Fullscreen | JSNI with vendor prefixes | no standard GWT wrapper |

## Progress

- [DONE] Phase 1 — Color
- [DONE] Phase 2 — Font
- [DONE] Phase 3 — Graphics (attributes, primitives, text, paths, transforms, fullscreen)
- [backlog] Phase 4 — Modernization / cleanup

## Phases

### Phase 1 — Color (`client/Color.java`) [DONE]

Implements: [SP_RND_01_01](./rendering-primitives.sp.md#SP_RND_01_01), [SP_RND_02_06](./rendering-primitives.sp.md#SP_RND_02_06)

Shipped: 3 constructors, 13 named constants + `NONE`, accessors,
`getHexValue`.

### Phase 2 — Font (`client/Font.java`) [DONE]

Implements: [SP_RND_01_02](./rendering-primitives.sp.md#SP_RND_01_02)

Shipped: CSS short-form builder, style bitflags.

### Phase 3 — Graphics (`client/Graphics.java`) [DONE]

Implements: [SP_RND_01_03](./rendering-primitives.sp.md#SP_RND_01_03), [SP_RND_02_01](./rendering-primitives.sp.md#SP_RND_02_01)–[SP_RND_02_05](./rendering-primitives.sp.md#SP_RND_02_05), [SP_RND_04_01](./rendering-primitives.sp.md#SP_RND_04_01)–[SP_RND_04_02](./rendering-primitives.sp.md#SP_RND_04_02)

Shipped: full drawing API (~60 methods), forced-color stack, font-size
save/restore, JSNI fullscreen + ellipse + setLineDash.

## Backlog

Items transcribed from the analysis "Issues" section:

- **`Color(String)` silently accepts invalid input** — any non-`#RRGGBB`
  string leaves `r=g=b=0` without reporting. Add validation or a factory
  that logs on malformed input (`NONE` deliberately depends on this).
- **`Font` uses `==` on `String`** (`if (name == "SansSerif")`). Should be
  `.equals`.
- **`Graphics.fillOval` uses literal `3.14159`** instead of `Math.PI`
  (inconsistent with `drawCircle`).
- **`Graphics.drawPolyline` always closes the path** — semantic mismatch
  with its name; rename or split method.
- **`Graphics` is a God-class** (~430 LOC, 60+ methods) mixing forced-color
  state, lifecycle, primitives, text, images, fullscreen, transforms.
  Candidate for extraction (e.g. `ForcedColorStack`, `FullScreenBridge`).
- **`Graphics.isFullScreen` is a public mutable static** — no guarantee it
  matches actual browser state (user Esc invalidates it). Replace with a
  live query or event-driven update.
- **License/compliance audit** — most files carry GPL header; double-check
  any untagged ones.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure |
