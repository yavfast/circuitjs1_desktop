# Dependency Graph (Package Level)

Edges extracted from `import com.lushprojects.circuitjs1.client.*` statements
across all 275 Java files in `src/main/java/com/lushprojects/circuitjs1/client/`.

Counts indicate the number of *import statements* between packages, not the
number of files involved. Higher counts signal strong coupling.

> Granularity: **package**. Per-file granularity is tractable only for the
> `client/` root (73 files), where logical clusters are themselves treated as
> sub-units in Layer decomposition. The 146 `element/*Elm.java` files are
> handled as one module because they are near-identical variants deriving from
> `CircuitElm` / `ChipElm` / `CompositeElm` / `SwitchElm`.

## Directed Edges (A → B: A imports B)

| Source → Target | Edge weight | Notes |
|---|---:|---|
| `element` → `dialog` | 104 | Elements open edit dialogs ⚠ circular |
| `<root>` → `element` | 68 | Simulator/editor construct elements |
| `element` → `util` | 44 | Locale strings, logging |
| `<root>` → `dialog` | 41 | Top-level commands open dialogs |
| `dialog` → `util` | 19 | Locale strings |
| `<root>` → `util` | 17 | Locale strings, logging |
| `dialog` → `element` | 16 | Edit dialogs introspect element state ⚠ circular |
| `element/waveform` → `element` | 15 | Waveforms know about voltage-source elements |
| `element` → `element/waveform` | 12 | Voltage-source elements own waveforms ⚠ circular |
| `element/waveform` → `dialog` | 9 | Waveform editors open dialogs |
| `io/text` → `io` | 7 | Text format implements framework |
| `io/json` → `io` | 7 | JSON format implements framework |
| `<root>` → `io` | 6 | Simulator invokes import/export |
| `io/text` → `element` | 4 | Text I/O reads/writes element state |
| `io/json` → `element` | 2 | JSON I/O reads/writes element state |
| `util` → `element` | 1 | `PerfMonitor` references `BaseCircuitElm` ⚠ circular |
| `<root>` → `ui/tabs` | 1 | `Scope`/`OptionsDialog` uses tab widget |
| `io/text` → `dialog` | 1 | Single cross-cut edge |
| `io` → `io/text` | 1 | Registry touches text subpackage ⚠ circular |
| `io` → `io/json` | 1 | Registry touches json subpackage ⚠ circular |
| `io` → `element` | 1 | Factory uses element base types |
| `element/waveform` → `util` | 1 | Locale strings |

## Circular Dependencies

Five cycles detected. They block clean topological layering and must be
flagged for manual resolution — see `issues.md`:

1. **`element` ↔ `dialog`** (104 + 16 edges) — strongest coupling in codebase.
   Elements embed edit UI logic; dialogs introspect element state. These two
   packages form a single logical subsystem.
2. **`element` ↔ `element/waveform`** (12 + 15 edges). Parent-child cycle —
   voltage-source elements own `Waveform` instances while `Waveform`
   implementations reach back for context. Treat as one logical unit.
3. **`io` ↔ `io/text`**, **`io` ↔ `io/json`** (1 edge each way). Registry in
   `io/` references concrete sub-packages for default registration; the
   sub-packages implement the framework. A textbook registration-pattern
   cycle.
4. **`util` ↔ `element`** (1 edge: `util/PerfMonitor` → `BaseCircuitElm`). A
   single violating import that could be broken by moving `PerfMonitor` to
   `client/` root or injecting the element reference. Flagged but not
   layer-breaking if we consolidate util/element.

## Cycle Resolution for Layering

To produce monotonic layers, strongly connected components are **collapsed**
into single nodes:

- **SCC-A: `{element, element/waveform, dialog}`** — collapsed "domain core"
- **SCC-B: `{io, io/text, io/json}`** — collapsed "io framework"
- **SCC-C: `{util, element (via PerfMonitor)}`** — the single
  `util → element` edge is weak and cross-layer. Treated as a **break-
  candidate**: for layering we place `util` at Layer 0 and mark
  `PerfMonitor → BaseCircuitElm` as a known inversion (issue #1 in
  `issues.md`).

## Collapsed Package Graph (acyclic)

```
                   ┌────────────────┐
                   │ <root> client/ │  (Layer 3)
                   └────────┬───────┘
                            │
            ┌───────────────┼────────────────┐
            ▼               ▼                ▼
   ┌────────────────┐  ┌──────────┐   ┌────────────┐
   │ io framework   │  │ SCC-A    │   │ ui/tabs    │
   │ {io,text,json} │  │ domain   │   │            │
   │  (Layer 2)     │  │ core     │   │ (Layer 1)  │
   └───────┬────────┘  │ (Layer 2)│   └─────┬──────┘
           │           └─────┬────┘         │
           └─────────────────┤               │
                             ▼               │
                      ┌──────────────┐       │
                      │ util (L0)    │◀──────┘
                      └──────────────┘
```

Cross-package edges after collapse:

| From (layer) | To (layer) |
|---|---|
| `<root>` (L3) → domain core (L2) |
| `<root>` (L3) → io framework (L2) |
| `<root>` (L3) → ui/tabs (L1) |
| `<root>` (L3) → util (L0) |
| io framework (L2) → domain core (L2) | *lateral, same layer — element only, no back edge* |
| io framework (L2) → util (L0) | *(transitively via dialog)* |
| domain core (L2) → util (L0) |
| ui/tabs (L1) → util (L0) | *(only if present — currently no import edge)* |

## Intra-SCC Sub-structure (for analysis ordering)

### SCC-A — domain core

Although circular at package level, a clear *conceptual* ordering exists:

1. `element/CircuitElm` and base classes (`BaseCircuitElm`, `ChipElm`,
   `CompositeElm`, `SwitchElm`, `GraphicElm`, `ElmGeometry`, `Inductor`) —
   shared contract surface.
2. `element/waveform/*` — self-contained signal generators.
3. `element/*Elm.java` (~135 concrete elements) — variants of the base classes.
4. `dialog/Editable`, `dialog/EditInfo`, `dialog/Dialog` (base interfaces).
5. `dialog/EditDialog` and concrete dialogs.

### SCC-B — io framework

1. `io/CircuitFormat`, `io/CircuitFormatRegistry`, `io/UnitParser`,
   `io/CircuitElementFactory` (framework interfaces).
2. `io/text/*` and `io/json/*` concrete formats (parallelizable with each
   other).
3. `io/CircuitExporter`, `io/CircuitImporter` (orchestrators — depend on the
   registry).

## Raw Data

Extracted via:

```bash
grep -rn '^import com\.lushprojects\.circuitjs1\.client\.'
  src/main/java/com/lushprojects/circuitjs1/client/
```

and bucketed by source package → target package.
