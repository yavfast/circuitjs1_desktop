# Rendering Primitives: Color, Font, Graphics  {#C_RND}

> **Code:** C_RND
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard
>
> **Depends on:** [C_GEO](./geometry.concept.md)
> **Used by:** element-base (CircuitElm draw), CircuitRenderer, Scope/ScopePlot, dialog rendering
> **Spike:** —
> **Specification:** [SP_RND](./rendering-primitives.sp.md)
> **Plan:** [rendering-primitives.plan.md](./rendering-primitives.plan.md)
>
> GWT-compatible façade over the HTML `<canvas>` `Context2d` — `Color`, `Font`,
> and the `Graphics` drawing API. Replaces AWT `java.awt.Graphics` for the
> simulator's canvas rendering.

## 1. Philosophy  {#C_RND_01}

### 1.1. Core Principle  {#C_RND_01_01}

The simulator draws every element onto a single HTML5 canvas. `Graphics`
centralizes that surface so elements can remain AWT-idiomatic
(`g.drawLine`, `g.fillOval`, `g.setColor`) while the implementation calls
`Context2d` under the hood. `Color` and `Font` model visual attributes in a
GWT-safe form (hex / CSS-short-form strings).

### 1.2. Design Constraints  {#C_RND_01_02}

- GWT-only (`Context2d` is the sole drawing primitive; no AWT, no Swing).
- `Graphics` holds per-canvas mutable state: forced-color stack, font-size
  save/restore, stroke/fill style tracking. Not thread-safe (single-threaded
  GWT).
- `Color` is effectively immutable post-construction but fields are `private`
  (not `final`).
- `Font` builds the CSS short-form string once at construction.

## 2. Domain Model  {#C_RND_02}

### 2.1. Key Entities  {#C_RND_02_01}

- **Color** — RGB + optional `colorText` (hex or CSS name). Constructors
  cover `#RRGGBB` parse, direct `(r,g,b)`, and linear-blend `(c1, c2, mix)`.
  13 named-color constants plus `NONE = new Color("")` (sentinel for
  "unset/invisible").
- **Font** — `(fontname, size)` where `fontname` is a prebuilt CSS
  short-form (e.g. `"bold italic 12px sans-serif"`). Bitflags: `NORMAL=0`,
  `BOLD=1`, `ITALIC=2`.
- **Graphics** — façade over `Context2d`. Owns forced-color stack
  (`forcedColorDepth`, `forcedColorHex`), font-size save/restore pair,
  last-style tracking fields. Static `isFullScreen` flag.

### 2.2. Data Flows  {#C_RND_02_02}

Renderer / element `draw(Graphics g)` → `g.setColor(...)` / `setFont(...)` /
`drawLine/fillOval/drawString/...` → each call translates to a `Context2d`
operation. Forced-color push temporarily overrides all stroke/fill
operations with a single hex (used to paint failing elements red).

## 3. Mechanisms  {#C_RND_03}

### 3.1. Core Algorithm  {#C_RND_03_01}

Forced-color stack: `pushForcedColor(hex)` increments `forcedColorDepth` and
stashes the hex. While `depth > 0`, every `setColor`/`setStrokeStyle` call
records the *requested* style into `last*` fields but writes the forced
hex to the canvas. `popForcedColor` decrements; on transition `1 → 0`, the
tracked `last*` style is flushed back to the canvas so subsequent drawing
resumes with the caller's intended color.

Drawing primitives are pass-throughs to `beginPath/moveTo/lineTo/stroke`,
`fillText`, `measureText`, `drawImage`, etc. A JSNI `ellipse` implements
canvas-ellipse when not in the std. API; fullscreen helpers cover
browser-prefixed APIs.

### 3.2. Edge Cases  {#C_RND_03_02}

- `Color("#RRGGBB")` parses 7-char hex; any other input stores `colorText`
  verbatim with `r=g=b=0`. `NONE` exploits this.
- `Font` compares name with `==` (string-interning accident; should be
  `.equals`).
- `Graphics.drawPolyline` always `closePath()`s — actually draws a closed
  polygon outline despite the name.
- `Graphics.fillPolygon` skips null/ <3 points / all-NaN / all-same-point.
- `Graphics.fillOval` uses literal `3.14159` (not `Math.PI`) for its arc —
  minor inconsistency with `drawCircle`.

## 4. Integration Points  {#C_RND_04}

### 4.1. Dependencies  {#C_RND_04_01}

- [C_GEO](./geometry.concept.md) — `Point`, `Polygon` are passed to drawing
  methods.
- External GWT: `com.google.gwt.canvas.dom.client.Context2d`,
  `CanvasGradient`, `FillStrokeStyle`, `com.google.gwt.dom.client.CanvasElement`.
- JSNI browser APIs: `requestFullscreen` (+ vendor prefixes), canvas
  `ellipse`, `setLineDash`.

### 4.2. API Surface  {#C_RND_04_02}

- **Attributes:** `setColor(Color | String)`, `setStrokeStyle(...)`,
  `setFont`/`save`/`restore` (font size), `pushForcedColor`/`popForcedColor`.
- **Primitives:** `drawLine` (int×4 and `Point,Point`), `drawRect`,
  `fillRect`, `drawCircle`, `fillOval`, `arc`, `ellipse`, `drawPolyline`,
  `fillPolygon`, `drawString`, `measureWidth`, `drawImage`, `drawLock`.
- **Paths:** `beginPath`, `moveTo`, `lineTo`, `bezierCurveTo`, `closePath`,
  `stroke`, `fill`, `clipRect`.
- **Transforms:** `setTransform`, `transform`, `scale`, `translate`.
- **Fullscreen:** `viewFullScreen`, `exitFullScreen`, static `isFullScreen`.
- **Static:** `distanceSq(int,int,int,int)`, `setLineDash(int,int)`.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure |
