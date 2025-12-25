# Elm geometry refactor – Phase A inventory notes

This file captures early reconnaissance findings to guide later phases of the `ElmGeometry`/`CircuitElm` geometry refactor.

## Centralized endpoint mutation

- **JSON import (fixed)**: `CircuitElm.applyJsonPinPositions(...)` used to write `x/y/x2/y2` directly.
  - This is now routed through `CircuitElm.setEndpoints(...)` (which delegates to `ElmGeometry.setEndpoints(...)`).
  - `_startpoint` and `_endpoint` sentinel pins are still respected (they indicate `point1/point2` do not correspond to the first/second pin).
- **Post-import recompute**: `CircuitElm.finalizeJsonImport()` now calls `initBoundingBox()` before `setPoints()` to avoid stale bounding boxes after endpoints are updated by import.

- **Legacy direct-write tolerance**: `ElmGeometry.ensureUpToDate()` is called from `CircuitElm.geom()` to recompute derived geometry if some code still mutates `x/y/x2/y2` directly.

## Known “special” `setPoints()` behaviors

These are important because they intentionally modify derived geometry values (especially `dn` / `dsign`) beyond the straight-line endpoint calculation:

- `SCRElm.setPoints()`:
  - If gate-fix flag is enabled, **overwrites `dn`** based on axis alignment.
  - May also set `x2 = x; y2 = y; return;` when gate lead is too short.
- `TriacElm.setPoints()`:
  - **Forces `dn = abs(dx)` or `abs(dy)`** (axis-aligned length), and similarly may collapse endpoints (`x2=x; y2=y`) when too short.
- `TransistorElm.setPoints()`:
  - Flips **`dsign = -dsign`** based on `FLAG_FLIP`.
- `TransformerElm.setPoints()` / `CustomTransformerElm.setPoints()`:
  - Recompute `dx/dy/dn/dpx1/dpy1/dsign` after `super.setPoints()` to support “diagonal handle but axis-aligned render” semantics.

Note: `ElmGeometry.calcLeads(...)` intentionally uses `owner.dn` to preserve these legacy subclass tweaks.

## Direct endpoint writes (still present)

These remain for later phases to migrate to the canonical setter:

- `TriacElm` — may set `x2 = x` / `y2 = y` (collapsing endpoints) and sets `dn = abs(dx)` / `abs(dy)` in `setPoints()`.
- `SCRElm` — similar behavior to Triac: assigns `dn = abs(dx|dy)` and collapses endpoints when needed (`x2 = x`, `y2 = y`).
- `TappedTransformerElm` — contains several cases that set `x2 = x` or `y2 = y` when winding taps are aligned.
- `TransformerElm` / `CustomTransformerElm` — compute and **write** derived values (`dn`, `dpx1`, `dpy1`, `dsign`) and set endpoints (e.g., `x2 = x + nominalLen`, `y2 = y`).
- `ScopeElm` — initialization uses `x2 = x + 128` and `y2 = y + 64` for default sizing.
- `PotElm` — assigns element-specific derived `dn = distance(point1, point2)` (write-derived).
- `TransistorElm` — flips or mutates `dsign` in `setPoints()` for flip semantics (element-specific derived write).

Additional migration completed:

- `ChipElm.drag(...)` now uses `setEndpoints(...)` instead of `y=y2=...` / `x2=...`.

## Next candidates for Phase B/C

- Identify other *write sites* to `x/y/x2/y2` (especially in flip/drag/move and JSON import overrides).
- Identify elements that recompute derived values (`dn/dsign/dpx1/dpy1`) and decide which belong in `ElmGeometry` versus staying as element-specific geometry rules.
