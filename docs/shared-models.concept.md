# Shared Element Models — Catalog of Shared Parameter Objects  {#C_SHM}

> **Code:** C_SHM
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard-doc-gen
>
> **Depends on:** [C_ELB](./element-base.concept.md), [C_UTL](./util-locale-log.concept.md), C_EIC (edit-info-contract, pending)
> **Used by:** — (will be filled by higher layers)
> **Spike:** —
> **Specification:** [SP_SHM](./shared-models.sp.md)
> **Plan:** [shared-models.plan.md](./shared-models.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/domain-core__shared-models.md` (4 files at `client/` root).
>
> Parameter-only model objects (`DiodeModel`, `TransistorModel`, `CustomLogicModel`, `CustomCompositeModel`) that multiple element instances can share by name. They let users define physics parameters (IS, N, Rs, Vbr, Gummel-Poon), truth-table logic, and user-authored subcircuits once, then reuse them across many elements.

## 1. Philosophy  {#C_SHM_01}

### 1.1. Core Principle  {#C_SHM_01_01}

All four models share one pattern — the **model-catalog pattern**:

- A static `HashMap<String, T> modelMap` serves as a process-wide catalog, keyed by name.
- `getModelWithName(String)` / `getModelWithNameOrCopy(String, T)` are the factory entry points — return an existing entry or create a new one.
- Built-in entries seed the catalog at first access (`createModelMap` / `initModelMap`).
- `dump()` emits a single token-prefixed line for persistence; `undumpModel(StringTokenizer)` parses it back.
- A `dumped` flag lets the exporter avoid emitting the same catalog entry twice (reset via `clearDumpedFlags()`).
- Elements hold a **name** (`modelName`), not a model reference, so round-tripping through save/load continues to resolve correctly.

### 1.2. Design Constraints  {#C_SHM_01_02}

- **Three-way split for diodes:** `DiodeModel` (parameters) / `Diode` (numerical solver, MNA stamp) / `DiodeElm` (UI element). The solver is reusable — `TransistorElm` contains two `Diode` instances for its two PN junctions.
- **No equivalent split for BJTs** — `TransistorModel` is parameters-only, but the Gummel-Poon solver is inlined in `TransistorElm.doStep`. No `Transistor.java` helper class exists.
- **`CustomCompositeModel` is the outlier** — it does not implement `Editable`/`SimulationContextAware`, is edited via a dedicated dialog, and persists independently in browser local-storage (`subcircuit:<name>` keys).
- **Model rebind on edit** via `SimulationContextAware.setSimulationContext` — edits invoke `circuitDocument.simulator.updateModels()`, letting every consumer re-read parameters at the next step.

## 2. Domain Model  {#C_SHM_02}

### 2.1. Key Entities  {#C_SHM_02_01}

```
(all four at client/ root)
DiodeModel               — Editable, Comparable<DiodeModel>, SimulationContextAware
TransistorModel          — Editable, Comparable<TransistorModel>, SimulationContextAware
CustomLogicModel         — Editable, SimulationContextAware (NOT Comparable)
CustomCompositeModel     — Comparable<CustomCompositeModel> only
                           (edited via EditCompositeModelDialog)

Related (for the diode three-way split):
client/Diode.java        — numerical solver (Newton-Raphson voltage-limiting + MNA stamp)
element/DiodeElm.java    — UI element; holds modelName + DiodeModel ref + Diode instance
```

Each model owns:
- Physical parameters (or rule source / netlist source).
- Metadata flags (`readOnly`, `builtIn`, `oldStyle`, `internal`, `dumped`).
- A derived cache (`vscale`, `vdcoef`, `fwdrop` for DiodeModel; empty for TransistorModel since Gummel-Poon is computed in the elm).

### 2.2. Data Flows  {#C_SHM_02_02}

```
   new / undump                     getModelWithName
   ─────────────►  modelMap entry  ◄─────────── Elm.modelName resolution
                         │   ▲
      edit dialog        │   │  (catalog rebuild on text import)
          │              ▼   │
          └──► setEditValue → updateModel() → simulator.updateModels()
                                              (propagates to every consumer)

   dump for save:  clearDumpedFlags() → Elm.dump() triggers model.dump() lazily
                                         (exporter sets dumped=true after first emit)

   CustomCompositeModel also:
     localStorage "subcircuit:<name>"  ←setSaved(true)── model
                                       →setSaved(false)→ removed
```

## 3. Mechanisms  {#C_SHM_03}

### 3.1. Core Algorithm  {#C_SHM_03_01}

**Lookup/create:** `getModelWithName(name)` returns the map entry or creates a minimal default under that name. `getModelWithNameOrCopy(name, template)` returns existing or copies `template` fields to a new map entry (used when an element edits its model and wants to branch off).

**Dump / undump:** each dump line starts with a token-prefix (`"34 "` diode, `"32 "` transistor, `"! "` custom-logic, `". "` custom-composite) so the text importer can dispatch. `clearDumpedFlags()` runs before each export pass to ensure only referenced, undumped models are emitted.

**Model propagation:** `SimulationContextAware.setSimulationContext(CircuitDocument)` wires the document; on `setEditValue`, the model calls `updateModel()` (recomputing derived caches where applicable) and then `circuitDocument.simulator.updateModels()` to trigger a global re-bind.

**Diode three-way split:** `DiodeElm` holds a `DiodeModel model` field (parameters) + an internal `Diode` instance (solver). On construction / reload, the elm resolves `modelName → model` via `DiodeModel.getModelWithNameOrCopy` and calls `diode.setup(model)`. Per-step stamping/stepping happens on the `Diode`, not the `DiodeElm`.

**CustomLogicModel rule parser:** one rule per line, `LEFT = RIGHT`. Left-side chars: `0`/`1` literal, `?` don't-care, `+`/`-` edge, `a–z` capture, `A–Z` match-saved. Right-side: `0`/`1`/`a–z`/`_` (tri-state). Validation only on edit (via `Window.alert`); `undump` trusts stored rules.

**CustomCompositeModel local-storage:** `initModelMap` seeds from browser `OptionsManager` (every `subcircuit:*` key). `setSaved(true)` writes, `setSaved(false)` removes.

### 3.2. Edge Cases  {#C_SHM_03_02}

- `DiodeModel.breakdownVoltage` coerced to absolute value; negative IS/N allowed by API (will NaN later).
- `TransistorModel` does no input validation; 1/VAF, 1/VAR, 1/IKF, 1/IKR can divide by zero.
- `CustomLogicModel` copy-constructor **aliases** `rulesLeft/rulesRight` Vectors (not deep-copied) — safe only because `parseRules` always reassigns on next edit.
- `CustomCompositeModel.undumpModel` ignores incoming dump if a local `modelCircuit` exists (local wins over file).
- Model rename leaves old key in `modelMap` — the model becomes reachable under both names (intentional back-compat).
- Token codes (`34`, `32`, `!`, `.`) have no central registry; collision with future element dump tokens is possible.
- `TransistorModel` is **not** referenced by `MosfetElm`/`JfetElm` — those elements use their own local parameters (despite `project_structure.md` overstating this coupling).

## 4. Integration Points  {#C_SHM_04}

### 4.1. Dependencies  {#C_SHM_04_01}

- **[C_ELB](./element-base.concept.md)** — `CircuitElm.parseInt`, `CircuitElm.showFormat` (statics); consumers `DiodeElm`, `TransistorElm`, `CustomLogicElm`, `CustomCompositeElm`.
- **[C_UTL](./util-locale-log.concept.md)** — `util.Locale` (DiodeModel, TransistorModel).
- **[C_EIC](./edit-info-contract.concept.md)** — `dialog.EditInfo`, `dialog.Editable` (all except CustomCompositeModel).
- **client/ root** — `CircuitDocument`, `CirSim`, `StringTokenizer`, `OptionsManager` (CustomComposite only for local-storage), `SimulationContextAware` interface, `ExtListEntry` (CustomComposite only).
- **GWT** — `Window` + `TextArea` (CustomLogicModel only).

### 4.2. API Surface  {#C_SHM_04_02}

Per-model catalog API:
- `getModelWithName(String)` — lookup or lazy-create.
- `getModelWithNameOrCopy(String, T)` — lookup or clone-from-template.
- `getModelList(...)` — sorted `Vector<T>` for dialog choice lists; hides `internal` models.
- `clearDumpedFlags()` — reset export-state.
- `dump()` / `undumpModel(StringTokenizer)` — serialization.

Legacy bridges:
- `DiodeModel.getModelWithParameters(fwdrop, zvoltage)` — synthesises implicit model from pre-model elements.

Shared helpers exposed project-wide:
- `CustomLogicModel.escape(String)` / `CustomLogicModel.unescape(String)` — reused by **all four** models and by `CircuitElm`/`CompositeElm` dump paths (28 referencing files). Handles `null`/empty as `\0`.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
