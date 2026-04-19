# Graphic Overlay Elements  {#C_EGR}

> **Code:** C_EGR
> **Status:** draft
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** OnboardDocGen
>
> **Depends on:** [C_ELB](./element-base.concept.md) (`GraphicElm` base), [C_UTL](./util-locale-log.concept.md), [C_GEO](./geometry.concept.md), [C_RND](./rendering-primitives.concept.md)
> **Used by:** circuit renderer, editor, file I/O glue
> **Spike:** —
> **Specification:** [SP_EGR](./elements-graphic-overlay.sp.md)
> **Plan:** [elements-graphic-overlay.plan.md](./elements-graphic-overlay.plan.md)
>
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-graphic-and-magnetics.md](../.dev_flow/onboard/analysis/domain-core__cat-graphic-and-magnetics.md)
> (graphic cluster only — the analysis recommended splitting the file into
> two concepts; this is the graphic half).
>
> Three canvas-only decoration elements — `BoxElm`, `TextElm`, `LineElm` —
> that extend `GraphicElm` and have **zero electrical participation**: no
> posts, no voltage sources, no internal nodes, no `stamp()` / `doStep()`.
> They persist with the document and render through the standard renderer.

## 1. Philosophy  {#C_EGR_01}

### 1.1. Core Principle  {#C_EGR_01_01}

Graphic overlays exist so users can annotate schematics with labels,
group boxes and connector lines **without** touching the netlist. The
category documents how an element can be "in the document" yet have
zero electrical semantics — a contract the renderer and persistence
layers already accommodate via `GraphicElm`.

### 1.2. Design Constraints  {#C_EGR_01_02}

- `getPostCount() == 0` for all three — simulator analysis skips them.
- `GraphicElm` forces `getJsonStartPoint()` / `getJsonEndPoint()` to
  always emit `(x, y)` / `(x2, y2)` because there are no pins to derive
  coordinates from.
- No `getNodeVoltage()` access path — their `draw()` relies solely on
  geometry and base color helpers.
- Persistence uses the **standard** `super.dump()` = `type x1 y1 x2 y2
  flags`; TextElm appends `size text` tokens.
- Creation is rejected below minimum size: BoxElm 32 px, LineElm 16 px.

## 2. Domain Model  {#C_EGR_02}

### 2.1. Key Entities  {#C_EGR_02_01}

- **`BoxElm`** — dashed rectangle; hit-test on edges only (click-through
  interior); dump type `'b'` (98).
- **`TextElm`** — multi-line text label; `\n` splits lines; `FLAG_BAR=2`
  draws overline; `FLAG_ESCAPE=4` selects new-style escape via
  `CustomLogicModel.unescape`; dump type `'x'` (120); shortcut `t`.
- **`LineElm`** — arbitrary diagonal line; dump type `423`.

All three share `GraphicElm` — a pass-through `CircuitElm` subclass that
forwards constructors and overrides `getPostCount() → 0`.

### 2.2. Data Flows  {#C_EGR_02_02}

Render-only flow:

    renderer → elm.draw(Graphics) → geometry-driven paint (no sim state read)

Persistence:

    dump: type x1 y1 x2 y2 flags [size text for TextElm]
    JSON: { type, x, y, x2, y2, flags, text?, size?, draw_bar? }

## 3. Mechanisms  {#C_EGR_03}

### 3.1. Core Algorithm  {#C_EGR_03_01}

**BoxElm.draw():** set dash `(16, 6)`, draw rectangle, restore solid;
`creationFailed()` rejects < 32 px.

**TextElm.draw():** split text on `\n`; each line passes through
`Locale.LS(s)` (translation); drawn via `g.drawString`. Optional
overline drawn above each line when `FLAG_BAR` set. Final lines
**write back** into geometry (`geom().setX2`, `setY2`) so
selection/move tracks rendered extent.

**LineElm.draw():** straight line between `(x, y)` and `(x2, y2)`;
`creationFailed()` rejects < 16 px Euclidean.

### 3.2. Edge Cases  {#C_EGR_03_02}

- TextElm does **not** substitute live values — `${V}` / `{V0}` / etc.
  are literal text. Dynamic labels are served by ProbeElm / OutputElm.
- Too-small drag silently drops the element via `creationFailed()` —
  no user feedback.
- BoxElm hit test is edge-only; interior clicks fall through to
  elements drawn beneath.
- TextElm legacy dump used space-joined tokens with `%2b→+` escape;
  `FLAG_ESCAPE` selects new-style.

## 4. Integration Points  {#C_EGR_04}

### 4.1. Dependencies  {#C_EGR_04_01}

- [C_ELB](./element-base.concept.md) — `GraphicElm` base; standard
  dump/JSON surface.
- [C_GEO](./geometry.concept.md) — endpoints, bounding box.
- [C_RND](./rendering-primitives.concept.md) — `Graphics`, `Font`,
  `Color`; line-dash helper.
- `CustomLogicModel.escape/unescape` — TextElm text escape.
- `Locale.LS` — TextElm translation.

### 4.2. API Surface  {#C_EGR_04_02}

- Standard element lifecycle: ctor, `draw`, `dump`, `applyJsonProperties`.
- No electrical API (no stamp, no doStep, no getConnection that matters).
- EditInfo:
  - BoxElm, LineElm: none (geometry via drag/move).
  - TextElm: `text` (multi-line), `size` (pt), `FLAG_BAR` checkbox.
- Shortcut: `t` (TextElm); BoxElm and LineElm have no shortcut.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
