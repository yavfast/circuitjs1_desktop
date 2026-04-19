# Module Analysis: domain-core / shared-models

> **Path:** `src/main/java/com/lushprojects/circuitjs1/client/` (4 `*Model.java` files at client root)
> **Layer:** 2 (semantically domain-core; located at client root for historical reasons)
> **Analyzed:** 2026-04-18
> **Files:** 4 source files, 0 test files

## Purpose

Parameter-only model objects referenced by multiple element instances. They let
the user share physics parameters (diode saturation current, breakdown voltage,
transistor Gummel-Poon parameters) across many element instances, and — for the
two `Custom*` models — define user-authored logic truth-tables and user-authored
subcircuits that can be re-used like library parts.

All four classes share the same pattern:

- Static global `HashMap<String, T> modelMap` — the process-wide catalog,
  keyed by model name.
- `getModelWithName(String)` / `getModelWithNameOrCopy(String, T)` factory
  entry points that return an existing entry or create a new one.
- `dump()` produces a single token-delimited line for persistence; the
  corresponding `undumpModel(StringTokenizer)` parses it back.
- Read-only `builtIn` models seeded at first access
  (`createModelMap` / `initModelMap`) plus a `dumped` flag used by the exporter
  to avoid emitting the same catalog entry twice.
- Models are bound to elements by *name* (`modelName` is what the element
  stores; the element resolves `modelName → model` at construction / reload).

## Key Entities

### DiodeModel
- File: `src/main/java/com/lushprojects/circuitjs1/client/DiodeModel.java`
- Implements: `Editable`, `Comparable<DiodeModel>`, `SimulationContextAware`.
- Physical fields
  (`DiodeModel.java:18-23`):
  - `saturationCurrent` (IS)
  - `seriesResistance`
  - `emissionCoefficient` (N)
  - `breakdownVoltage` (zener, >0 enables Zener branch)
  - UI-only hints: `forwardVoltage`, `forwardCurrent`
  - Derived cache: `vscale`, `vdcoef`, `fwdrop` (populated in `updateModel()`
    at `DiodeModel.java:319`).
- Metadata flags: `flags` (only `FLAGS_SIMPLE = 1`), `readOnly`, `builtIn`,
  `oldStyle`, `internal`, `dumped`.
- **Thermal voltage constant** `vt = 0.025865` (hard-coded SPICE 27 °C,
  `DiodeModel.java:33`) is the only physical constant — not pulled from a
  shared registry.
- Built-in catalog seeded in `createModelMap()` (`DiodeModel.java:81-114`):
  `spice-default`, `default`, `default-zener`, `old-default-led`,
  `default-led`, `1N5711`, `1N5712`, `1N34`, `1N4004`, `1N4148`,
  `x2n2646-emitter`, plus internal diodes for TL431 / LM317 macro models
  loaded via `loadInternalModel`.
- Persistence: `dump()` emits token-type `"34 "` (`DiodeModel.java:327`).
  Consumed by `TextCircuitImporter.java:263` via `DiodeModel.undumpModel(st)`.
- Legacy bridge: `getModelWithParameters(fwdrop, zvoltage)` synthesises an
  implicit model from the old `fwdrop` / `zvoltage` parameters of pre-model
  diode elements (`DiodeModel.java:130-161`).

### TransistorModel
- File: `src/main/java/com/lushprojects/circuitjs1/client/TransistorModel.java`
- Implements: `Editable`, `Comparable<TransistorModel>`,
  `SimulationContextAware`.
- Gummel-Poon parameters (`TransistorModel.java:19-20`): `satCur` (IS),
  `invRollOffF` (1/IKF), `BEleakCur` (ISE), `leakBEemissionCoeff` (NE),
  `invRollOffR` (1/IKR), `BCleakCur` (ISC), `leakBCemissionCoeff` (NC),
  `emissionCoeffF` (NF), `emissionCoeffR` (NR), `invEarlyVoltF` (1/VAF),
  `invEarlyVoltR` (1/VAR), `betaR` (BR). Forward beta is **not** on the
  model — it lives on the `TransistorElm` instance.
- Built-in catalog: `default`, `spice-default`, plus internal
  `~lm324v2-*`, `~tl431ed-*`, `~lm317-*` entries used by built-in macro
  models (`TransistorModel.java:64-95`).
- Persistence: `dump()` emits token-type `"32 "` (`TransistorModel.java:256`).
  Consumed by `TextCircuitImporter.java:267`.
- `updateModel()` is empty (`TransistorModel.java:251`) — Gummel-Poon
  coefficients are computed per-step inside `TransistorElm.doStep`.

### CustomLogicModel
- File: `src/main/java/com/lushprojects/circuitjs1/client/CustomLogicModel.java`
- Implements: `Editable`, `SimulationContextAware` — **not** `Comparable`.
- Fields (`CustomLogicModel.java:19-27`): `inputs[]`, `outputs[]`
  (comma-separated pin-name lists), free-form `infoText`, user-authored
  `rules` (truth-table source). After parsing, normalised into
  `rulesLeft` / `rulesRight` vectors and a `triState` flag.
- **Rule grammar** (`parseRules`, `CustomLogicModel.java:176-232`): one rule
  per line, `LEFT = RIGHT`. Left side must have at least `inputs.length`
  digits and at most `inputs.length + outputs.length`; right side must have
  exactly `outputs.length`. Left-side characters: `0` / `1` (fixed),
  `?` (don't care), `+` / `-` (edge), `a-z` (variable — first use
  captures, subsequent uses are capitalised to mean "same value"). `_` on
  the right marks a tri-state output.
- Error reporting goes through `Window.alert` — validation is only run on
  edit, not on `undump`, so imported rules are trusted.
- Persistence: `dump()` emits token-type `"! "` (`CustomLogicModel.java:239`).
  Consumed by `TextCircuitImporter.java:242`.
- Flags: `FLAG_SCHMITT = 1` (edit dialog case for it is disabled — see the
  commented-out block at `CustomLogicModel.java:138-145`).
- Helpers `escape` / `unescape` (`CustomLogicModel.java:243-288`) are the
  project-wide dump-value escape routines reused by all four models (they
  also turn `null` / empty into `\0`).

### CustomCompositeModel
- File: `src/main/java/com/lushprojects/circuitjs1/client/CustomCompositeModel.java`
- Implements: `Comparable<CustomCompositeModel>` only — **not** `Editable` or
  `SimulationContextAware`. Its UI is driven via `EditCompositeModelDialog`,
  which operates on the `extList` directly.
- Fields (`CustomCompositeModel.java:13-22`): `flags` (only
  `FLAG_SHOW_LABEL = 1`), `sizeX` / `sizeY` (chip bounding rectangle in
  grid units), `name`, `nodeList` (space-separated pin-to-node mapping),
  `extList` (`Vector<ExtListEntry>` — one entry per external pin, see
  `ExtListEntry.java:5-21`: `name`, `node`, `pos`, `side` where `side`
  uses `ChipElm.SIDE_W` etc.), `elmDump` (raw text-format dump of every
  element inside the subcircuit), `modelCircuit` (full circuit text used
  when the user opens the subcircuit for editing).
- `sequenceNumber` is a process-wide counter bumped on every mutation —
  consumers (e.g. `CustomCompositeElm`) use it to detect stale model
  bindings.
- **Local-storage persistence** (`initModelMap`, `CustomCompositeModel.java:32-67`):
  on first access the map is seeded with a minimal `default` stub and
  every `subcircuit:*` key from browser local storage via `OptionsManager`.
  `setSaved(true/false)` writes or removes the `subcircuit:<name>` key.
  This is the only model that persists independently of the enclosing
  circuit file.
- Built-in macros `~LM317-v2` and `~TL431` are injected at the end of
  `initModelMap` via `loadInternalModels()` (`CustomCompositeModel.java:219-231`)
  as huge pre-baked dump strings.
- Persistence: `dump()` emits token-type `". "`
  (`CustomCompositeModel.java:197`). Consumed by
  `TextCircuitImporter.java:275`.
- `undumpModel` (`CustomCompositeModel.java:115-131`) has special behaviour:
  if a model already has a local `modelCircuit` loaded, the incoming dump
  is ignored — i.e. the locally-stored subcircuit wins over whatever is in
  the file being opened.

## Separation: `Diode.java` vs `DiodeElm.java` vs `DiodeModel.java`

Three distinct concerns, one per file:

| File | Role | Key types it holds |
|---|---|---|
| `client/DiodeModel.java` | **Parameter set** — name + physical parameters (IS, N, Rs, Vbr). No nodes, no current, no geometry. | `DiodeModel.saturationCurrent`, `vscale`, `vdcoef`, `fwdrop` |
| `client/Diode.java` | **Numerical solver** — Newton-Raphson voltage-limiting and MNA stamping for a single PN junction. Needs a `DiodeModel` to know the parameters and a `CircuitSimulator` to stamp into. | `Diode.setup(DiodeModel)` (`Diode.java:36`), `Diode.stamp(int n0, int n1)` (`Diode.java:151`), `Diode.doStep(double)` (`Diode.java:158`). Reused by non-diode elements that contain a PN junction — e.g. `TransistorElm` constructs two `Diode` instances (`element/TransistorElm.java:52,60`). |
| `element/DiodeElm.java` | **UI element** — placement on canvas, drawing, click/drag handling, the `EditInfo` surface for model selection and dialog glue. Holds a reference to a `DiodeModel` (`DiodeElm.java:44`) and delegates stepping to an internal `Diode` instance. Subclassed by `ZenerElm`, `LEDElm`, `VaractorElm`. | `model`, `modelName`, `newModelCreated(DiodeModel)` (`DiodeElm.java:244`) |

Equivalent split for BJTs: `TransistorModel` (parameters) →
`element/TransistorElm` (UI + solver, subclassed by `NTransistorElm`). No
separate `Transistor.java` helper; the Gummel-Poon step is inlined in
`TransistorElm.doStep` (`element/TransistorElm.java:347-475`).

## Public Contracts

### Catalog API

| Method | Purpose | Notes |
|---|---|---|
| `DiodeModel.getModelWithName(String)` | Return or lazily create a named diode model. | Also lazy-initialises the default catalog. |
| `DiodeModel.getModelWithNameOrCopy(String, DiodeModel)` | Return existing or **clone** the supplied model under a new name. | Used when an element edits its model and wants to branch off. |
| `DiodeModel.getModelWithParameters(double fwdrop, double zvoltage)` | Legacy bridge — synthesise a model from old-style fwdrop/zvoltage. | Matches existing entries by tolerance to avoid duplicates. |
| `DiodeModel.getModelList(boolean zener)` | Sorted `Vector<DiodeModel>` for dialog choice lists; hides `internal` and (if `zener`) non-Zener entries. | |
| Same pair for `TransistorModel`, `CustomLogicModel`, `CustomCompositeModel`. | | `CustomCompositeModel.getModelWithName` does **not** auto-create. |

### Serialization (text format)

All four emit a single space-separated line; the token code is the first
field:

| Model | Token code | Dump site | Import site |
|---|---|---|---|
| `CustomLogicModel` | `"! "` | `CustomLogicModel.java:239` | `TextCircuitImporter.java:242` |
| `TransistorModel` | `"32 "` | `TransistorModel.java:256` | `TextCircuitImporter.java:267` |
| `DiodeModel` | `"34 "` | `DiodeModel.java:327` | `TextCircuitImporter.java:263` |
| `CustomCompositeModel` | `". "` | `CustomCompositeModel.java:197` | `TextCircuitImporter.java:275` |

The exporter `TextCircuitExporter` (`io/text/TextCircuitExporter.java:63-66,
120-123`) calls `clearDumpedFlags()` on all four before a dump run so only
models actually referenced by the circuit (and not yet emitted) are written.

### Editable contract

Three of the four implement `dialog.Editable` (`DiodeModel`,
`TransistorModel`, `CustomLogicModel`) and expose their fields via
`getEditInfo(int n)` / `setEditValue(int n, EditInfo)`. `CustomCompositeModel`
is edited by `EditCompositeModelDialog` through direct field access on the
`extList`.

### SimulationContextAware propagation

`DiodeModel`, `TransistorModel`, `CustomLogicModel` each hold a nullable
`circuitDocument` set via `setSimulationContext(CircuitDocument)`. After an
edit, `setEditValue` triggers `circuitDocument.simulator.updateModels()` so
every element referencing the catalog entry re-reads parameters at the next
step (`DiodeModel.java:295-298`, `TransistorModel.java:241-244`,
`CustomLogicModel.java:166-168`). `CustomCompositeModel` has no such hook
— its consumers rebuild through `sequenceNumber`.

### Copy / edit semantics

`getModelWithNameOrCopy` performs a **shallow copy via copy-constructor** of
primitive / String fields. Vectors held by `CustomLogicModel`
(`rulesLeft`, `rulesRight`) are **aliased**, not deep-copied
(`CustomLogicModel.java:74-82`) — any edit on the copy's rules will also
mutate the original's parsed rule lists until `parseRules()` reassigns new
vectors. Not obviously a bug (because `parseRules` always reassigns on the
next edit), but a latent sharing hazard.

## Validation Rules

- **Diode**: `breakdownVoltage` is coerced to its absolute value
  (`DiodeModel.java:293`). No other range checks — negative
  `saturationCurrent` or `emissionCoefficient` is allowed by the API (will
  later produce NaN in `updateModel()`).
- **Transistor**: no input validation; dividing by zero for VAF/VAR/IKF/IKR
  is possible because UI passes `1 / ei.value` without a guard
  (`TransistorModel.java:230-233`).
- **CustomLogic**: rule-string grammar validated line-by-line in
  `parseRules` (`CustomLogicModel.java:176-232`). Errors surfaced via
  `Window.alert`; parsing aborts on first invalid line. Not run on
  `undump` — imported circuits trust the stored rules.
- **CustomComposite**: no validation of nodeList or elmDump; a malformed
  stored subcircuit surfaces only when a `CustomCompositeElm` tries to
  instantiate it.

## State Transitions

```
  new / undump            getModelWithName
  ------------>  entry in modelMap  <------- Elm.modelName resolution
                      |   ^
      edit dialog     |   |  catalog rebuild on text import
         |            v   |
         +---->  setEditValue  --->  updateModel()  --->  simulator.updateModels()
                                                    (propagates to every consumer)

  dump for save:  clearDumpedFlags()  -->  Elm.dump() triggers model.dump() lazily
                                            (exporter sets dumped=true after first emit)

  CustomCompositeModel also:
     local storage "subcircuit:<name>"   <--setSaved(true)-- model
                                          --setSaved(false)-> removed
```

Consumer-side: when an element's `modelName` changes (via dialog or
re-import), the element re-binds by calling
`Model.getModelWithNameOrCopy(modelName, currentModel)` (e.g.
`element/DiodeElm.java:90`, `element/TransistorElm.java:102`,
`element/CustomLogicElm.java:64`). `CustomCompositeElm` uses
`getModelWithName` without the copy fallback (`element/CustomCompositeElm.java:178`).

## Integration Points

### Depends on

- `com.lushprojects.circuitjs1.client.dialog` — `EditInfo`, `Editable`
  (all except `CustomCompositeModel`).
- `com.lushprojects.circuitjs1.client.element.CircuitElm` — for static
  helpers `parseInt`, `showFormat` (`DiodeModel.java:236,340`,
  `CustomLogicModel.java:91`). This is the edge that places these models
  in the `domain-core` SCC despite living at client root.
- `client/` root: `CircuitDocument`, `CirSim`, `StringTokenizer`,
  `OptionsManager` (storage, CustomComposite only), `SimulationContextAware`
  interface, `ExtListEntry` (CustomComposite only).
- `util.Locale` — localised descriptions (`DiodeModel`, `TransistorModel`).
- `com.google.gwt.user.client.Window`, `TextArea` — only
  `CustomLogicModel` pulls GWT directly (for alert + rules text area).

### Used by

Confirmed by grep over `src/main/java/.../client/element/`:

| Model | Direct consumers |
|---|---|
| `DiodeModel` | `element/DiodeElm` (root; subclasses `ZenerElm`, `LEDElm`, `VaractorElm` inherit the model field), `element/LEDElm:50`, `element/ZenerElm:47`, `element/SevenSegElm:213` (uses built-in `default-led`), `element/LEDArrayElm:81` (built-in `default-led`). `element/TransistorElm` transitively via `client/Diode` but does *not* hold a `DiodeModel`. |
| `TransistorModel` | `element/TransistorElm` only (inherited by `NTransistorElm`). **Does not** reach `MosfetElm`/`JfetElm` — grep in `element/MosfetElm.java` returns no `TransistorModel` references, contradicting the summary in `project_structure.md:93`. Mosfet/Jfet elements use their own local parameters. |
| `CustomLogicModel` | `element/CustomLogicElm` (as `model` field, `element/CustomLogicElm.java:16`). Also used project-wide for its static `escape` / `unescape` dump helpers (28 referencing files, most only for string escaping — not real consumers of the model). |
| `CustomCompositeModel` | `element/CustomCompositeElm` (`element/CustomCompositeElm.java:26,178`). Also referenced by `dialog/EditCompositeModelDialog`, `dialog/SubcircuitDialog`, `MenuManager` (menu population), `CircuitSimulator.getCircuitAsComposite()` (`CircuitSimulator.java:1286`), `io/text/TextCircuitImporter`, `io/text/TextCircuitExporter`. |

All four are also called from `CircuitSimulator.java:1290-1292` /
`TextCircuitExporter.java:63-66` for `clearDumpedFlags()` — the export /
"circuit as composite" paths.

### External deps

- GWT: `com.google.gwt.user.client.Window` + `com.google.gwt.user.client.ui.TextArea`
  (only `CustomLogicModel`).
- Java stdlib: `HashMap`, `Vector`, `Iterator`, `Map`, `Collections`.

## Existing Documentation

No model-specific docs in `docs/`. Relevant cross-references:

- [docs/EXPORT_CJS.md](../../../docs/EXPORT_CJS.md) and
  [docs/EXPORT_OLD.md](../../../docs/EXPORT_OLD.md) — mention the token
  codes `"34 "`, `"32 "`, `"! "`, `". "` as part of the line-based text
  format.
- [docs/elements.md](../../../docs/elements.md) — catalogues the elements
  that consume these models (`DiodeElm`, `TransistorElm`,
  `CustomLogicElm`, `CustomCompositeElm`).
- In-app help page `customlogic.html` — linked from
  `CustomLogicModel.getEditInfo(3)` (`CustomLogicModel.java:132`).
- In-app help page `diodecalc.html` — linked from
  `DiodeModel.getEditInfo(n=3, non-simple)` (`DiodeModel.java:265`).

## Issues / Questions

1. **Global mutable catalogs.** All four classes use `static` maps keyed by
   name. Two open simulator documents on the same JVM (theoretically
   possible in GWT devmode) would share catalogs. For single-document GWT
   use this is fine but it is a smell worth surfacing.
2. **Model-rename propagation.** When the user renames a model via
   `setEditValue(0, …)` the old key is **not removed** from the map
   (`DiodeModel.java:274-277`, `TransistorModel.java:223-227`) — the model
   becomes reachable under both the old and new names. Existing elements
   still hold the reference; new imports with the old name will still
   resolve. Intentional back-compat, but easy to mistake for a bug.
3. **No validation on import.** `CustomLogicModel.undump` does not call
   `parseRules()`'s validation — it just calls `parseRules` which
   `Window.alert`s on error. Opening a circuit with a malformed model
   pops an alert per bad line. Consider silent logging instead.
4. **Shallow copy of rule vectors in `CustomLogicModel(CustomLogicModel)`**
   (`CustomLogicModel.java:74-82`) aliases `rulesLeft` / `rulesRight`.
   Works only because `parseRules` reassigns on every edit; any direct
   append would corrupt the original. Document or deep-copy.
5. **TextCircuitExporter** double-calls `clearDumpedFlags()` for all four
   models in two distinct methods (`io/text/TextCircuitExporter.java:63,120`).
   Intentional (two export paths) but worth checking neither leaves stale
   `dumped=true` behind.
6. **Dump-token collision risk.** `DiodeModel` uses `"34 "`,
   `TransistorModel` uses `"32 "` — these are also used as first tokens
   for elements in the text format. The importer dispatches by token
   string, so the codes must never collide with element tokens. Not
   guaranteed by any central registry; a new element with numeric id 32
   would clash silently.
7. **`project_structure.md:93` lists MOSFET / JFET as TransistorModel
   consumers** — grep on `element/MosfetElm.java` shows no such import.
   The document overstates coupling; correct today's fact is "only
   `TransistorElm` / `NTransistorElm`".

## Suggested Concept Boundaries

One concept, **`shared-element-models`**, covering all four classes:

- Shared registration / lookup API (`getModelWithName`,
  `getModelWithNameOrCopy`, `clearDumpedFlags`, per-model `modelMap`).
- Shared dump / undump protocol (token prefixes `34`, `32`, `!`, `.` +
  the `CustomLogicModel.escape/unescape` helpers used by all four).
- Shared `Editable` + `SimulationContextAware` wiring (three of four —
  `CustomCompositeModel` is the outlier and might warrant a sub-concept
  for "composite subcircuit model + local-storage persistence").

A follow-up concept may be useful:
**`diode-solver-split`** — documenting the three-way split
`DiodeModel` (parameters) / `Diode` (solver) / `DiodeElm` (UI) and the
absence of an equivalent split for BJTs, since `Diode` is reused inside
`TransistorElm` and potentially useful for similar reuse elsewhere.
