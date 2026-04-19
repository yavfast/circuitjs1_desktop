# Rendering Primitives — Specification  {#SP_RND}

> **Code:** SP_RND
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_RND](./rendering-primitives.concept.md)
> **Depends on specs:** [SP_GEO](./geometry.sp.md)
> **Used by specs:** element-base, renderer, scope, dialog (populated at higher layers)
> **Plan:** [rendering-primitives.plan.md](./rendering-primitives.plan.md)
>
> Data structures, canvas API groupings, and rendering invariants for
> `Color`, `Font`, `Graphics`.
>
> Backing analysis: [.dev_flow/onboard/analysis/root-utils.md](../.dev_flow/onboard/analysis/root-utils.md)

## 01. Data Structures  {#SP_RND_01}

> Implements: [C_RND_02](./rendering-primitives.concept.md#C_RND_02)

### 01_01. Color  {#SP_RND_01_01}

Fields:
| Field | Type | Required | Default | Constraints | Description |
|-------|------|----------|---------|-------------|-------------|
| r, g, b | `private int` | yes | 0 | 0..255 when set via RGB ctor | color channels |
| colorText | `private String` | no | `null` | `#RRGGBB` or CSS name or empty | literal form, bypasses RGB |

Named constants: `white, lightGray, gray, dark_gray/darkGray, black, red,
pink, orange, yellow, green, magenta, cyan, blue`; plus `NONE = new Color("")`.

Invariants: if `colorText != null && !matches(#RRGGBB)`, then `r=g=b=0`.

### 01_02. Font  {#SP_RND_01_02}

Fields: package-private `final String fontname` (prebuilt CSS short-form),
`final int size`. Constants: `NORMAL=0`, `BOLD=1`, `ITALIC=2`.

Constructor `Font(String name, int style, int size)` builds CSS like
`"bold italic 12px sans-serif"`. Maps `"SansSerif"` → `"sans-serif"` via
`==` (interning quirk).

### 01_03. Graphics  {#SP_RND_01_03}

Fields:
| Field | Type | Purpose |
|-------|------|---------|
| `context` | `final Context2d` | canvas 2D context |
| `currentFontSize` | `int` | current font size (for save/restore) |
| `savedFontSize` | `int` | stored by `save()` |
| `forcedColorDepth` | `int` | ≥ 0; `>0` ⇒ overrides active |
| `forcedColorHex` | `String` | active forced color |
| `lastStrokeStyleObject`, `lastStrokeStyleString`, `lastFillStyleString`, `lastStrokeIsObject` | tracking | last-requested style, flushed on final `popForcedColor` |
| `isFullScreen` | `public static boolean` | global fullscreen flag |

Invariants:
- `forcedColorDepth >= 0`.
- While `forcedColorDepth > 0`, canvas stroke/fill carry `forcedColorHex`
  while `last*` fields track the caller's intent.
- `save`/`restore` mirror only `currentFontSize`; other state goes through
  `Context2d.save/restore`.

## 02. Contracts  {#SP_RND_02}

API groups (abstract — see analysis for full method list):

### 02_01. Attribute setters  {#SP_RND_02_01}

`setColor(Color)`, `setColor(String)`, `setStrokeStyle(FillStrokeStyle|String)`,
`setFont(Font)`, `save()`, `restore()`, `pushForcedColor(hex)`,
`popForcedColor()`.

### 02_02. Shape primitives  {#SP_RND_02_02}

`drawLine(int,int,int,int)`, `drawLine(Point,Point)`, `drawRect`, `fillRect`,
`drawCircle`, `fillOval`, `arc(x,y,r,start,end)`, `ellipse(...)` (JSNI),
`drawPolyline(int[],int[],int)` (**always closes path**),
`fillPolygon(Polygon)`, `drawLock()`.

Validation: `drawPolyline` no-ops on `n<=0`, null arrays, NaN coords.
`fillPolygon` no-ops on null, `npoints<3`, NaN, all-same-point.
`ellipse` guards `rx>=0 && ry>=0`.

### 02_03. Text and images  {#SP_RND_02_03}

`drawString(String, x, y)`, `measureWidth(String)`,
`drawImage(CanvasElement, double, double)`.

### 02_04. Paths and transforms  {#SP_RND_02_04}

`beginPath`, `moveTo`, `lineTo`, `bezierCurveTo`, `closePath`, `stroke`,
`fill`, `clipRect`, `setTransform`, `transform`, `scale`, `translate`.

### 02_05. Fullscreen and utilities  {#SP_RND_02_05}

`viewFullScreen` / `exitFullScreen` (JSNI with browser-prefix fallbacks).
Static `distanceSq(int,int,int,int)` → int squared distance.
Static `setLineDash(int a, int b)` (JSNI).

### 02_06. Color constructors  {#SP_RND_02_06}

- `Color(String)` — if 7 chars starting `#`, parse hex; else store verbatim.
- `Color(int r, int g, int b)` — direct.
- `Color(Color c1, Color c2, double mix)` — linear blend.

Accessors: `getRed`, `getGreen`, `getBlue`, `getHexValue` (prefers
`colorText`; else zero-padded `#rrggbb`).

## 03. Validation Rules  {#SP_RND_03}

- `Color("#RRGGBB")` requires exact 7 chars and `#` prefix.
- `drawPolyline`/`fillPolygon` null/NaN/degenerate guards (see 02_02).
- `ellipse` negative-radius guard.
- `Graphics` assumes GWT single-thread; no locking.

## 04. State Transitions  {#SP_RND_04}

### 04_01. Forced-color stack  {#SP_RND_04_01}

    [normal, depth=0] --push--> [forced, depth=1..N]
    [forced, depth=N] --push--> [forced, depth=N+1]
    [forced, depth=N>1] --pop--> [forced, depth=N-1]
    [forced, depth=1] --pop--> [normal, depth=0, last* flushed to canvas]

### 04_02. save/restore (font-size only)  {#SP_RND_04_02}

`save()` stores `currentFontSize` into `savedFontSize` and calls
`Context2d.save()`. `restore()` calls `Context2d.restore()` and restores
`currentFontSize = savedFontSize`.

## 05. Verification Criteria  {#SP_RND_05}

### 05_01. Functional Expectations  {#SP_RND_05_01}

| Contract | Scenario | Input | Expected outcome |
|----------|----------|-------|------------------|
| setColor under forced | push red, setColor blue | — | canvas stroke/fill = red; last* records blue |
| popForcedColor | final pop after setColor blue | — | canvas stroke/fill flushed to blue |
| fillPolygon | npoints < 3 | triangle with 2 points | no-op |
| Color("#ff8800") | hex parse | — | r=255,g=136,b=0 |
| Color("red") | CSS name | — | colorText="red", r=g=b=0 |

### 05_02. Invariant Checks  {#SP_RND_05_02}

| Invariant | Verification method |
|-----------|--------------------|
| `forcedColorDepth >= 0` | assert after each push/pop |
| Color hex parse contract | inspect getRed/Green/Blue vs. input |

### 05_03. Integration Scenarios  {#SP_RND_05_03}

| Scenario | Preconditions | Steps | Expected result |
|----------|--------------|-------|-----------------|
| Error highlight | element in error | push red → draw element → pop | element painted red once; next element uses its own color |
| Fullscreen toggle | canvas attached | viewFullScreen → exitFullScreen | `isFullScreen` mirrors browser state best-effort |

### 05_04. Edge Cases and Boundaries  {#SP_RND_05_04}

| Case | Input | Expected behavior |
|------|-------|-------------------|
| drawPolyline with NaN | any NaN coord | no-op |
| fillOval vs drawCircle precision | same input | different due to 3.14159 vs Math.PI |
| Font name `==` | non-interned `"SansSerif"` string | fallback path (mostly safe due to literal interning) |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure |
