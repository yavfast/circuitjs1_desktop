# Geometry Primitives  {#C_GEO}

> **Code:** C_GEO
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard
>
> **Depends on:** none (Layer 0 leaf)
> **Used by:** [C_RND](./rendering-primitives.concept.md), element-base (CircuitElm, ElmGeometry), editor (CircuitEditor), renderer (CircuitRenderer)
> **Spike:** —
> **Specification:** [SP_GEO](./geometry.sp.md)
> **Plan:** [geometry.plan.md](./geometry.plan.md)
>
> GWT-compatible value/collection types for integer-plane geometry: `Point`,
> `Rectangle`, `IntPair`, `Polygon`. Reimplements a minimal subset of
> `java.awt.*` because GWT cannot compile AWT classes.

## 1. Philosophy  {#C_GEO_01}

### 1.1. Core Principle  {#C_GEO_01_01}

The original Falstad applet used `java.awt.Point`/`Rectangle`/`Polygon`. GWT
compiles Java to JS but cannot emulate AWT, so these types are
hand-ported — mostly verbatim from OpenJDK 6 — and live at the client root as
plain integer value types used pervasively for element placement, bounding
boxes, hit testing, and drawing.

### 1.2. Design Constraints  {#C_GEO_01_02}

- Integer-plane only (no `double` coordinates at this layer).
- GWT-safe: no reflection, no AWT, no threading.
- Mutable value types (`Point`, `Rectangle`, `Polygon`) — allocations are
  hot, so APIs mutate in place (`move`, `setBounds`, `translate`, `addPoint`).
- `IntPair` is immutable; used as a map key / pair carrier.

## 2. Domain Model  {#C_GEO_02}

### 2.1. Key Entities  {#C_GEO_02_01}

- **Point** — `public int x, y`. Constructors, `setLocation`, `move(dx,dy)`,
  `equals/hashCode/toString`. Hashing `41*(41+x)+y`.
- **Rectangle** — `public int x, y, width, height`. Port of OpenJDK 6 AWT
  with AWT "empty rectangle" semantics (`width|height < 0` → empty).
  Methods: `setBounds`, `translate`, `contains(int,int)`, `contains(Rectangle)`,
  `intersects`, `union` (non-mutating, clamps overflow to `Integer.MAX_VALUE`).
- **IntPair** — immutable `(int first, int second)` with value equality.
- **Polygon** — dynamic int arrays `xpoints[]`, `ypoints[]` with `npoints`
  counter. `addPoint` doubles capacity (power-of-2) via `Integer.highestOneBit`
  when exhausted. Unlike AWT, does **not** maintain a bounding box.

### 2.2. Data Flows  {#C_GEO_02_02}

Element geometry flows: elements own `Point` endpoints and an `ElmGeometry`
that computes bounds/post positions → `Rectangle` returned for hit testing,
clipping, and scope/redraw invalidation. `Polygon` is built transiently by
elements that draw filled shapes (arrows, triangles) and passed to
`Graphics.fillPolygon`.

## 3. Mechanisms  {#C_GEO_03}

### 3.1. Core Algorithm  {#C_GEO_03_01}

- `Rectangle.contains`/`intersects`/`union` use AWT's long-arithmetic trick
  to handle `x + width` overflow without intermediate `long` casts escaping.
- `Rectangle.union` on empty rects returns a copy of the non-empty operand.
- `Polygon.addPoint` grows arrays lazily: when full, reallocates to next
  power of 2 via `Integer.highestOneBit(cap) << 1`.

### 3.2. Edge Cases  {#C_GEO_03_02}

- Negative width/height rectangles are treated as "empty"; `contains` /
  `intersects` short-circuit.
- `Polygon` starts empty; `MIN_LENGTH=4` initial capacity.
- `IntPair` is hashed via `Objects.hash(first, second)`.
- Overflow in `Rectangle.union` clamps to `Integer.MAX_VALUE`.

## 4. Integration Points  {#C_GEO_04}

### 4.1. Dependencies  {#C_GEO_04_01}

- `IntPair`: `java.util.Objects` only.
- `Point`, `Rectangle`, `Polygon`: no imports.
- No project-internal dependencies.

### 4.2. API Surface  {#C_GEO_04_02}

- Public mutable fields on `Point`, `Rectangle`, `Polygon` (AWT-compatible).
- `Rectangle` query API (`contains`, `intersects`, `union`).
- `Polygon.addPoint` for incremental polyline / polygon building.
- `IntPair.getFirst/getSecond` for composite keys (used only by
  `CompositeElm`).

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure |
