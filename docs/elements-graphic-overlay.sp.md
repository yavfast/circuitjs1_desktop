# Graphic Overlay Elements — Specification  {#SP_EGR}

> **Code:** SP_EGR
> **Status:** draft
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_EGR](./elements-graphic-overlay.concept.md)
> **Depends on specs:** [SP_ELB](./element-base.sp.md), [SP_UTL](./util-locale-log.sp.md), [SP_GEO](./geometry.sp.md), [SP_RND](./rendering-primitives.sp.md)
> **Used by specs:** renderer, editor, file I/O glue
> **Plan:** [elements-graphic-overlay.plan.md](./elements-graphic-overlay.plan.md)
>
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-graphic-and-magnetics.md](../.dev_flow/onboard/analysis/domain-core__cat-graphic-and-magnetics.md)
> (graphic cluster only)
>
> Dense catalog of three canvas-decoration elements with zero netlist
> participation.

## 01. Element Catalog  {#SP_EGR_01}

> Implements: [C_EGR_02](./elements-graphic-overlay.concept.md#C_EGR_02)

### 01_01. Element Table  {#SP_EGR_01_01}

| Element | Extends | Posts | V-src | Int.nodes | Dump | Params |
|---|---|---|---|---|---|---|
| `BoxElm` | `GraphicElm` | 0 | 0 | 0 | `'b'` 98 | geometry (x, y, x2, y2) |
| `TextElm` | `GraphicElm` | 0 | 0 | 0 | `'x'` 120 | `text` (String, \n-split), `size` (pt), `FLAG_BAR=2`, `FLAG_ESCAPE=4` |
| `LineElm` | `GraphicElm` | 0 | 0 | 0 | 423 | geometry |

Invariants:
- `getPostCount() == 0` inherited from GraphicElm.
- `stamp()`, `doStep()`, `startIteration()`, `stepFinished()` are never
  invoked meaningfully (simulator only iterates elements with posts).
- Persistence uses standard `super.dump()` for geometry; TextElm adds
  `size text`.

### 01_02. TextElm Dynamic Content Semantics  {#SP_EGR_01_02}

- Text is **static** — no runtime interpolation of node voltages,
  currents or time.
- `\n` splits into lines; each line goes through `Locale.LS()`.
- `FLAG_BAR` draws a horizontal overline above each line (used for
  negated signal names).
- `FLAG_ESCAPE` is a dump-format marker; `dump()` force-sets it.
- If live-value labels are needed use `ProbeElm`, `OutputElm`,
  `TestPointElm`, `AmmeterElm` etc.

### 01_03. BoxElm Rendering  {#SP_EGR_01_03}

- Dashed outline via `g.setLineDash(16, 6)`; restored after.
- Hit-test only tests the four edges (`getMouseDistance`) —
  interior clicks fall through.

## 02. Contracts  {#SP_EGR_02}

### 02_01. Creation validation  {#SP_EGR_02_01}

| Element | Minimum size | Failure mode |
|---|---|---|
| BoxElm | 32 px in either axis | `creationFailed() == true` → element dropped |
| LineElm | 16 px Euclidean | `creationFailed() == true` → element dropped |
| TextElm | — (no minimum) | never rejected |

### 02_02. Draw contract  {#SP_EGR_02_02}

Input:
| Parameter | Type | Required | Constraints |
|-----------|------|----------|-------------|
| Graphics g | reference | yes | active canvas |

Output: canvas side effect only.

Processing logic (TextElm):

    lines = split(text, "\n")
    max_w = 0
    FOR i, line in lines:
        s = Locale.LS(line)
        g.drawString(s, x, y + i*spacing)
        IF FLAG_BAR: draw overline above line
        max_w = max(max_w, width(s))
    geom().setX2(x + max_w); geom().setY2(y + lines·spacing)   # mutates geometry

## 03. Validation Rules  {#SP_EGR_03}

### 03_01. Input Validation  {#SP_EGR_03_01}

- BoxElm/LineElm reject sub-minimum drag silently (Issue #9).
- TextElm: no numeric validation on `size` — any dialog value accepted.
- FLAG_ESCAPE force-set on dump so round-trip is stable.

## 04. State Transitions  {#SP_EGR_04}

### 04_01. Lifecycle  {#SP_EGR_04_01}

    [placing]  --creationFailed--> [dropped]
    [placing]  --ok-->             [placed]
    [placed]   --draw-->           [placed (geometry maybe mutated — TextElm)]
    [placed]   --setEditValue-->   [placed]

## 05. Verification Criteria  {#SP_EGR_05}

### 05_01. Functional Expectations  {#SP_EGR_05_01}

| Contract | Scenario | Input | Expected |
|---|---|---|---|
| BoxElm | drag 40×40 | — | created, dashed outline drawn |
| BoxElm | drag 20×40 | — | creationFailed → dropped |
| TextElm | "hello\nworld" | — | two lines rendered |
| TextElm | FLAG_BAR on "Q" | — | overline above "Q" |
| LineElm | drag 8 px | — | creationFailed → dropped |

### 05_02. Invariant Checks  {#SP_EGR_05_02}

| Invariant | Verification |
|---|---|
| postCount==0 | assertion |
| never called: stamp, doStep | spy in test harness |
| round-trip dump | snapshot + reload match |

### 05_03. Integration Scenarios  {#SP_EGR_05_03}

| Scenario | Preconditions | Steps | Expected |
|---|---|---|---|
| Overlay in JSON | save + reload | — | text, size, flags preserved |
| BoxElm over element | box drawn over resistor | click interior | resistor receives click (hit-test edge-only) |

### 05_04. Edge Cases and Boundaries  {#SP_EGR_05_04}

| Case | Input | Expected |
|---|---|---|
| TextElm empty text | "" | no text drawn; bbox min |
| TextElm `${V0}` literal | — | renders literally, no substitution |
| LineElm collinear with element | — | drawn on top (z-order) |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
