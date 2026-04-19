# Dependency Layers

Computed from [dependency_graph.md](dependency_graph.md) after collapsing
strongly connected components. Layer 0 contains leaves (no internal project
dependencies); higher layers depend only on lower layers.

Analysis order is **bottom-up**: Layer 0 first, then Layer 1, and so on.
Within a layer, modules may be analyzed in parallel.

---

## Layer 0 — Leaf utilities

No dependencies on other project packages (except the one flagged inversion).

| Module | Path | Files | Notes |
|---|---|---:|---|
| **util** | `client/util/` | 3 | `Locale`, `Log`, `PerfMonitor`. `PerfMonitor` imports `element/BaseCircuitElm` — flagged inversion (break-candidate, not blocking). |
| **root-utils** | `client/` (subset) | ~17 | Geometry/math/rendering primitives that never reach back to higher-layer packages: `Point`, `Rectangle`, `IntPair`, `Polygon`, `Color`, `Font`, `Graphics`, `CircuitMath`, `StringTokenizer`, `RandomUtils`, `FFT`, `Expr`, `ExprParser`, `ExprState`, `PlatformUtils`, `GWTUtils`, `CircuitConst`. Treated as a single logical "math-and-primitives" module even though files live at the client root. |

Parallelizable: yes (two modules, independent).

---

## Layer 1 — Reusable UI primitives

Depends only on Layer 0.

| Module | Path | Files | Notes |
|---|---|---:|---|
| **ui-tabs** | `client/ui/tabs/` | 2 | `TabBarPanel`, `TabWidget`. Tiny reusable component. |
| **root-widgets** | `client/` (subset) | ~5 | Small GWT-wrapped controls that wrap the browser DOM and depend only on geometry/util: `Checkbox`, `CheckboxMenuItem`, `CheckboxAlignedMenuItem`, `Choice`, `Scrollbar`. |

Parallelizable: yes.

---

## Layer 2 — Domain core (SCC-A) + IO framework (SCC-B)

Both are circular-at-package-level SCCs; they are documented and analyzed
as collapsed units, with ordered sub-steps (see below). Both sit at the
same layer because:
- `io` depends on `element` (1 edge) and `dialog` (1 edge), but there are no
  edges from domain core to io — so io is at the same or higher layer.
  Flattening simplifies analysis: we treat io as Layer 2, alongside domain
  core, and note the io → domain-core one-way dependency.

### 2.a — Domain core (SCC-A)

One logical "circuit domain" module covering elements, waveforms, and edit
dialogs.

| Sub-unit | Path | Files | Analysis order |
|---|---|---:|---:|
| Element base classes | `client/element/` (selection) | 11 | 1 |
| Waveforms | `client/element/waveform/` | 9 | 2 |
| Non-element shared models | `client/` (subset) | 4 | 3 |
| Concrete elements | `client/element/` (remainder) | ~135 | 4 (bulk) |
| Dialog base + `Editable` / `EditInfo` | `client/dialog/` (subset) | 3 | 5 |
| Concrete dialogs | `client/dialog/` (remainder) | ~27 | 6 |

> Element base classes: `CircuitElm`, `BaseCircuitElm`, `ChipElm`,
> `CompositeElm`, `CustomCompositeElm`, `CustomCompositeChipElm`,
> `CustomLogicElm`, `SwitchElm`, `GraphicElm`, `ElmGeometry`, `Inductor`.
>
> Non-element shared models (at client root but semantically element-layer):
> `DiodeModel`, `TransistorModel`, `CustomLogicModel`, `CustomCompositeModel`.
>
> Concrete elements will be analyzed as **categories** — groups of variants
> sharing a base class — rather than one-by-one. See queue.yaml for group
> list.

Parallelizable within sub-unit 4 (concrete elements): yes (by category
group).

### 2.b — IO framework (SCC-B)

| Sub-unit | Path | Files | Analysis order |
|---|---|---:|---:|
| Framework interfaces | `client/io/` (selection) | 3 | 1 |
| Text format | `client/io/text/` | 3 | 2 |
| JSON format | `client/io/json/` | 5 | 2 (parallel with text) |
| Orchestrators (Exporter/Importer) | `client/io/` (remainder) | 1-2 | 3 |

Parallelizable: text + json can run in parallel at step 2.

---

## Layer 3 — Application shell

Depends on all lower layers. ~49 files remain at `client/` root after Layer
0–2 extractions.

| Sub-unit | Files | Representative classes |
|---|---:|---|
| Simulator core | 6 | `CirSim`, `BaseCirSim`, `BaseCirSimDelegate`, `CircuitSimulator`, `SimulationContextAware`, `Diode` (numerical, not UI) |
| Circuit state & netlist | 12 | `CircuitDocument`, `DocumentManager`, `CircuitInfo`, `CircuitLoader`, `CircuitElmCreator`, `CircuitNode`, `CircuitNodeLink`, `CircuitUtils`, `FindPathInfo`, `NodeMapEntry`, `WireInfo`, `RowInfo` |
| Editor & interaction | 11 | `CircuitEditor`, `CircuitEditorEventHandler`, `CircuitRenderer`, `MouseMode`, `MyCommand`, `ActionManager`, `MenuManager`, `Toolbar`, `ClipboardManager`, `ClipboardCallback`, `UndoManager` |
| Options/settings | 4 | `OptionsManager`, `DisplaySettings`, `ColorSettings`, `QueryParameters` |
| Scopes | 5 | `Scope`, `ScopeManager`, `ScopePlot`, `ScopeCheckBox`, `ScopePopupMenu` |
| Sliders/adjustables | 2 | `Adjustable`, `AdjustableManager` |
| Logging/Dialog glue | 3 | `LogManager`, `DialogManager`, `ExtListEntry` |
| File I/O glue | 3 | `LoadFile`, `SRAMLoadFile`, `ImportFromDropbox` |
| App entry | 1 | `circuitjs1` (GWT `EntryPoint`) |
| SliderDialog (root-level) | 1 | `SliderDialog` |

Parallelizable: yes, sub-units are mostly independent (except that "Editor &
interaction" and "Circuit state" reference each other — may need combined
analysis).

---

## Layer Summary

| Layer | Logical modules | File count |
|---:|---|---:|
| 0 | util, root-utils | ~20 |
| 1 | ui-tabs, root-widgets | ~7 |
| 2 | domain core (SCC-A), io framework (SCC-B) | ~185 + ~11 = ~196 |
| 3 | application shell (9 sub-units) | ~49 (estimated) |
| — | unassigned / empty | 0 (`options/`, `ui/` empty) |
| | **Total** | **~275** |

## Recommended Processing Granularity

- **Layer 0, 1:** per-module analysis (5 modules total).
- **Layer 2 domain core:** analyzed as **one module** with six ordered sub-
  analyses (see 2.a above); concrete elements bucketed by category (see
  queue.yaml). Avoids 135 near-duplicate per-file analyses.
- **Layer 2 io framework:** four sub-units.
- **Layer 3:** nine sub-units, most analyzable in parallel.

**Net deliverables (doc phase):** roughly 18–22 concept/spec/plan triples
(not 275). This matches the intent of the onboard pipeline — one triple per
*logical module*, not per file.
