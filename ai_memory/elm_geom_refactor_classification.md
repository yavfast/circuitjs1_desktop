# Elm geometry refactor — classification (phase B preparation)

Generated: 2025-12-24

## Summary

This document captures a quick classification of geometry usages found by a repo scan and indicates immediate migration recommendations (for the next migration batch).

## Categories

### A) Write-derived (assignments to `dn`, `dsign`, `dpx1`, `dpy1`)
- `TriacElm` — assigns `dn = abs(dx|dy)` and may collapse endpoints (`x2 = x`, `y2 = y`). (high priority)
- `SCRElm` — similar: assigns `dn = abs(dx|dy)` and collapses endpoints when too short. (high priority)
- `CustomTransformerElm` — computes and assigns `dn`, `dpx1`, `dpy1`, `dsign` and uses custom endpoint logic. (high priority)
- `TransformerElm` — computes/assigns `dn/dpx1/dpy1/dsign` (medium priority)
  - **Status:** saved-endpoint axis-alignment now uses `geom().setEndpoints(...)` (initial migration done).
- `PotElm` — assigns `dn = distance(point1, point2)` (medium)
- `TransistorElm` — flips/mutates `dsign` in `setPoints()` (medium)
- `ScopeElm` & some others compute defaults (but often in initialization) — review for endpoint-write semantics.

### B) Direct endpoint writes (assignments to `x/x2/y/y2`)
- `ScopeElm` — `x2 = x + 128; y2 = y + 64` (default size initialization)
- `TriacElm` / `SCRElm` — `x2 = x; y2 = y` in short-handle cases
- `TappedTransformerElm` — multiple `x2 = x` / `y2 = y` cases for taps
- `CustomTransformerElm` / `TransformerElm` — set endpoints (e.g., `x2 = x + nominalLen`)
  - **Status:** `CustomTransformerElm` constructor now uses `geom().setEndpoints(...)` for default sizing (initial migration done).
- `ChipElm.drag(...)` — already migrated to `setEndpoints(...)` (done)

### C) Read-only / getter usage (low-risk, candidates for existing getters)
- Many elements already use `getDn()/getDx()/getDy()` and `point1/point2/lead1/lead2` in drawing logic. Examples: `TransistorElm`, `OpAmpElm`, `Gate` family, `Relay*`, `MosfetElm`, `TestPointElm`, `VoltageElm`, `ProbeElm`, `AmmeterElm`, `WattmeterElm`, `ScopeElm` (partial), etc.
- These are lower priority: prefer to keep using getters; refactor when deprecating fields.

## Preliminary priorities (batch 1 candidates)
1. `TriacElm` / `SCRElm` — because they both actively mutate `dn` and endpoints (high-risk for geometry invariants).
2. `CustomTransformerElm` / `TransformerElm` / `TappedTransformerElm` — non-trivial pin/endpoint logic; migrate carefully.
3. `ScopeElm` — consolidate default-sizing into `ElmGeometry` or into canonical constructor flow.
4. `PotElm` / `TransistorElm` — migrate derived assignments into geometry hook or document as allowed overrides.

## Recommended per-file actions (short notes)
- TriacElm: extract the axis-aligned length correction into an explicit hook (e.g., `owner.adjustDerivedGeometry(geom)`), or have `ElmGeometry` provide `normalizeAxisAlignedIfNeeded(owner)`; replace direct endpoint collapse logic with invoking `geom().setEndpoints(...)`.
- SCRElm: similar to Triac.
- TappedTransformer/Transformer/CustomTransformer: centralize their derived computations in `ElmGeometry` where possible, or provide an `override` hook for transformer-specific geometry after `geom.updatePointsFromEndpoints()`.
- ScopeElm: replace literal `x2=x+128` assignments with a canonical sizing helper `geom().setDefaultSize(w,h)` or call `setEndpoints(x,y,x+w,y+h)`.
- PotElm: move `dn` assignment into `setPoints()` hook that runs after `geom.updatePointsFromEndpoints()` or into `ElmGeometry` if generic.

## Next tasks
- For each high-priority file, prepare a small change set: extract the derived calculation into a local helper or `ElmGeometry` method and switch callers to use getters / `geom()` accessors.
- Add a quick unit/smoke test for each migrated element to validate drag/flip/selection behavior.


---

(End of classification snapshot)
