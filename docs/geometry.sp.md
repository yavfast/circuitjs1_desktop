# Geometry Primitives — Specification  {#SP_GEO}

> **Code:** SP_GEO
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_GEO](./geometry.concept.md)
> **Depends on specs:** — (Layer 0)
> **Used by specs:** [SP_RND](./rendering-primitives.sp.md), element-base, editor, renderer
> **Plan:** [geometry.plan.md](./geometry.plan.md)
>
> Data structures and behavioral contracts for `Point`, `Rectangle`, `IntPair`,
> `Polygon`.
>
> Backing analysis: [.dev_flow/onboard/analysis/root-utils.md](../.dev_flow/onboard/analysis/root-utils.md)

## 01. Data Structures  {#SP_GEO_01}

> Implements: [C_GEO_02](./geometry.concept.md#C_GEO_02)

### 01_01. Point  {#SP_GEO_01_01}

Fields:
| Field | Type | Required | Default | Constraints | Description |
|-------|------|----------|---------|-------------|-------------|
| x | `public int` | yes | 0 | — | X coordinate |
| y | `public int` | yes | 0 | — | Y coordinate |

Constructors: `Point()`, `Point(int,int)`, `Point(Point)`.
Methods: `setLocation(Point)`, `move(int dx, int dy)`, `equals`,
`hashCode` = `41*(41+x)+y`, `toString`.

### 01_02. Rectangle  {#SP_GEO_01_02}

Fields: `public int x, y, width, height`.
Invariants: `width < 0 || height < 0` denotes an "empty" rectangle; query
methods short-circuit on that condition.

### 01_03. IntPair  {#SP_GEO_01_03}

Fields: `private final int first, second`. Immutable. Equality and hash
by value (`Objects.hash`).

### 01_04. Polygon  {#SP_GEO_01_04}

Fields: `public int npoints`, `public int[] xpoints`, `public int[] ypoints`.
Constant `MIN_LENGTH = 4`.
Invariants: `xpoints.length == ypoints.length`; `npoints <= xpoints.length`.

## 02. Contracts  {#SP_GEO_02}

### 02_01. Point.move(dx, dy)  {#SP_GEO_02_01}

Purpose: Translate the point in-place. Logic: `x += dx; y += dy`.

### 02_02. Rectangle.contains(int, int)  {#SP_GEO_02_02}

Returns `true` iff `(X,Y)` falls inside a non-empty rect; uses long-arithmetic
safe against `x+width` overflow. Returns `false` for negative-dim rectangles.

### 02_03. Rectangle.intersects(Rectangle)  {#SP_GEO_02_03}

AWT-compatible rectangle intersection with empty-rect short-circuit.

### 02_04. Rectangle.union(Rectangle)  {#SP_GEO_02_04}

Returns a new `Rectangle` spanning both; if one is empty, returns a copy of
the other. Clamps overflow to `Integer.MAX_VALUE`.

### 02_05. Polygon.addPoint(int, int)  {#SP_GEO_02_05}

Appends `(x,y)`; on capacity exhaustion, reallocates both arrays to
`Integer.highestOneBit(cap) << 1`.

### 02_06. IntPair.getFirst / getSecond  {#SP_GEO_02_06}

Plain accessors. No validation; immutable.

## 03. Validation Rules  {#SP_GEO_03}

- `Rectangle` methods treat `width < 0 || height < 0` as empty.
- `Rectangle.union` clamps overflow.
- `Polygon.addPoint` grows by power-of-2.
- No coordinate-range checks; `int` overflow is caller's responsibility.

## 04. State Transitions  {#SP_GEO_04}

N/A for `Point`, `Rectangle`, `IntPair` (stateless value types).
`Polygon` grows monotonically: capacity only increases via `addPoint`.

## 05. Verification Criteria  {#SP_GEO_05}

### 05_01. Functional Expectations  {#SP_GEO_05_01}

| Contract | Scenario | Input | Expected outcome |
|----------|----------|-------|------------------|
| contains | inside | rect(0,0,10,10), (5,5) | true |
| contains | empty rect | rect(0,0,-1,-1), any | false |
| union | one empty | empty ∪ r | copy of r |
| addPoint | exceed capacity | 5th point into MIN_LENGTH=4 | array resized to 8 |

### 05_02. Invariant Checks  {#SP_GEO_05_02}

| Invariant | Verification method |
|-----------|--------------------|
| `xpoints.length == ypoints.length` | assert after each `addPoint` |
| `IntPair` value equality | hash/equals symmetric with manually constructed pair |

### 05_03. Integration Scenarios  {#SP_GEO_05_03}

| Scenario | Preconditions | Steps | Expected result |
|----------|--------------|-------|-----------------|
| Element hit test | `ElmGeometry` returns `Rectangle` | mouse x,y | contains returns true/false |
| Polygon fill | element builds Polygon, passes to Graphics | fillPolygon | canvas fill executed |

### 05_04. Edge Cases and Boundaries  {#SP_GEO_05_04}

| Case | Input | Expected behavior |
|------|-------|-------------------|
| Negative-dim rect | `intersects` with any | false |
| Polygon < 3 points | `fillPolygon` | skipped by Graphics (see SP_RND) |
| Polygon.getBounds | — | N/A (not implemented; consumers compute manually) |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure |
