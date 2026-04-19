# Implementation Plan: Geometry Primitives  {#PL_GEO}

> **Code:** PL_GEO
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_GEO](./geometry.concept.md)
> **Specification:** [SP_GEO](./geometry.sp.md)
> **Depends on plans:** none
> **Used by plans:** [PL_RND](./rendering-primitives.plan.md), higher-layer plans
>
> Retrospective plan covering the as-built GWT-compatible geometry types.

## Goal

Document the as-built state of `Point`, `Rectangle`, `IntPair`, `Polygon` and
record modernization backlog items (AWT-compatibility quirks, missing
`getBounds`, `IntPair` dead-code concern).

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Language | Java 17 source | project-wide |
| Client compile target | GWT 2.12 | AWT unavailable under GWT — types are hand-ported |
| Integer plane | `int` coordinates | screen-space pixels; matches AWT idiom |
| Source of Rectangle/Polygon | OpenJDK 6 ports | preserve AWT semantics with zero code-rewrite risk |

## Progress

- [DONE] Phase 1 — Point
- [DONE] Phase 2 — Rectangle (OpenJDK 6 port)
- [DONE] Phase 3 — Polygon (OpenJDK 6 port, minus bounds tracking)
- [DONE] Phase 4 — IntPair
- [backlog] Phase 5 — Reconcile dead code & missing features

## Phases

### Phase 1 — Point (`client/Point.java`) [DONE]

Implements: [SP_GEO_01_01](./geometry.sp.md#SP_GEO_01_01), [SP_GEO_02_01](./geometry.sp.md#SP_GEO_02_01)

Shipped: three constructors, `setLocation`, `move`, `equals`, `hashCode`,
`toString`.

### Phase 2 — Rectangle (`client/Rectangle.java`) [DONE]

Implements: [SP_GEO_01_02](./geometry.sp.md#SP_GEO_01_02), [SP_GEO_02_02](./geometry.sp.md#SP_GEO_02_02), [SP_GEO_02_03](./geometry.sp.md#SP_GEO_02_03), [SP_GEO_02_04](./geometry.sp.md#SP_GEO_02_04)

Shipped: `setBounds`, `translate`, `contains(int,int)`, `contains(Rectangle)`,
`intersects`, `union`, `equals`, `toString`. AWT empty-rect semantics.

### Phase 3 — Polygon (`client/Polygon.java`) [DONE]

Implements: [SP_GEO_01_04](./geometry.sp.md#SP_GEO_01_04), [SP_GEO_02_05](./geometry.sp.md#SP_GEO_02_05)

Shipped: `addPoint` with power-of-2 growth. Note: `updateBounds` call is
commented out; no `getBounds()`.

### Phase 4 — IntPair (`client/IntPair.java`) [DONE]

Implements: [SP_GEO_01_03](./geometry.sp.md#SP_GEO_01_03), [SP_GEO_02_06](./geometry.sp.md#SP_GEO_02_06)

Shipped: immutable, `Objects.hash`-based value equality.

## Backlog

Items transcribed from the analysis "Issues" section:

- **Polygon has no `getBounds()`** — AWT has it; `updateBounds` is commented
  out. Consumers compute bounds manually. Add if demand grows.
- **`IntPair` is only used in `CompositeElm`** — borderline dead code; could
  be inlined to `int[2]` for hot paths.
- **Polygon growth scheme is implicit** — `Integer.highestOneBit` is subtle;
  add a unit test / comment for the next reader.
- **No module-level README** — this plan serves as the reference.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure |
