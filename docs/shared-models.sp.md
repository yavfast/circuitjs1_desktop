# Shared Element Models — Specification  {#SP_SHM}

> **Code:** SP_SHM
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_SHM](./shared-models.concept.md)
> **Depends on specs:** [SP_ELB](./element-base.sp.md), [SP_UTL](./util-locale-log.sp.md), [SP_EIC](./edit-info-contract.sp.md)
> **Used by specs:** — (will be filled by higher layers)
> **Plan:** [shared-models.plan.md](./shared-models.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/domain-core__shared-models.md`.
>
> Defines the 4 shared-model classes at `client/` root: `DiodeModel`, `TransistorModel`, `CustomLogicModel`, `CustomCompositeModel`.

## 01. Data Structures  {#SP_SHM_01}

> Implements: [C_SHM_02](./shared-models.concept.md#C_SHM_02)

### 01_01. DiodeModel  {#SP_SHM_01_01}

File: `client/DiodeModel.java`. Implements: `Editable`, `Comparable<DiodeModel>`, `SimulationContextAware`.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `saturationCurrent` | `double` | built-in per entry | IS (amps) |
| `seriesResistance` | `double` | 0 | Rs (ohms) |
| `emissionCoefficient` | `double` | built-in | N |
| `breakdownVoltage` | `double` | 0 | Zener Vbr (>0 enables Zener branch); abs-coerced |
| `forwardVoltage` | `double` | — | UI-only hint |
| `forwardCurrent` | `double` | — | UI-only hint |
| `vscale`, `vdcoef`, `fwdrop` | `double` | derived | Populated by `updateModel()` |
| `flags` | `int` | 0 | `FLAGS_SIMPLE = 1` |
| `readOnly`, `builtIn`, `oldStyle`, `internal`, `dumped` | `boolean` | — | Metadata |
| `name` | `String` | — | Map key |

Constants: `vt = 0.025865` (SPICE thermal voltage at 27 °C).

Built-in catalog (seeded in `createModelMap`): `spice-default`, `default`, `default-zener`, `old-default-led`, `default-led`, `1N5711`, `1N5712`, `1N34`, `1N4004`, `1N4148`, `x2n2646-emitter`, plus internal diodes for TL431/LM317 macros.

Dump token: `"34 "`.

### 01_02. TransistorModel  {#SP_SHM_01_02}

File: `client/TransistorModel.java`. Implements: `Editable`, `Comparable<TransistorModel>`, `SimulationContextAware`.

Gummel-Poon parameters:

| Field | Type | SPICE name |
|-------|------|------------|
| `satCur` | `double` | IS |
| `invRollOffF` | `double` | 1/IKF |
| `BEleakCur` | `double` | ISE |
| `leakBEemissionCoeff` | `double` | NE |
| `invRollOffR` | `double` | 1/IKR |
| `BCleakCur` | `double` | ISC |
| `leakBCemissionCoeff` | `double` | NC |
| `emissionCoeffF` | `double` | NF |
| `emissionCoeffR` | `double` | NR |
| `invEarlyVoltF` | `double` | 1/VAF |
| `invEarlyVoltR` | `double` | 1/VAR |
| `betaR` | `double` | BR |

Note: forward beta is **not** on the model — it lives on each `TransistorElm` instance.

Built-in catalog: `default`, `spice-default`, plus internal macro-support entries (`~lm324v2-*`, `~tl431ed-*`, `~lm317-*`).

Dump token: `"32 "`. `updateModel()` is empty (coefficients computed per-step in `TransistorElm.doStep`).

### 01_03. CustomLogicModel  {#SP_SHM_01_03}

File: `client/CustomLogicModel.java`. Implements: `Editable`, `SimulationContextAware` (not `Comparable`).

| Field | Type | Description |
|-------|------|-------------|
| `inputs` | `String[]` | Comma-separated input pin names |
| `outputs` | `String[]` | Comma-separated output pin names |
| `infoText` | `String` | Free-form description |
| `rules` | `String` | User-authored truth-table source |
| `rulesLeft`, `rulesRight` | `Vector<String>` | Parsed rule vectors |
| `triState` | `boolean` | True if any rule emits `_` |
| `flags` | `int` | `FLAG_SCHMITT = 1` (dialog case disabled) |

Rule grammar (parsed by `parseRules`):
- Left side ≥ `inputs.length` chars, ≤ `inputs.length + outputs.length`.
- Left-side chars: `0`/`1` fixed, `?` don't-care, `+`/`-` rising/falling edge, `a–z` capture var, `A–Z` compare saved var.
- Right side exactly `outputs.length` chars: `0`/`1` literal, `a–z` pattern var, `_` tri-state.

Dump token: `"! "`. Validation only on edit (`Window.alert` on error); `undump` trusts stored rules.

Helpers: `escape(String)` / `unescape(String)` — project-wide dump-value escape routines reused by all four models (treat `null`/empty as `\0`).

### 01_04. CustomCompositeModel  {#SP_SHM_01_04}

File: `client/CustomCompositeModel.java`. Implements: `Comparable<CustomCompositeModel>` only.

| Field | Type | Description |
|-------|------|-------------|
| `flags` | `int` | `FLAG_SHOW_LABEL = 1` |
| `sizeX`, `sizeY` | `int` | Chip bounding rect (grid units) |
| `name` | `String` | Map key |
| `nodeList` | `String` | Space-separated pin-to-node mapping |
| `extList` | `Vector<ExtListEntry>` | External pins (`name`, `node`, `pos`, `side`) |
| `elmDump` | `String` | Raw text dump of every element inside the subcircuit |
| `modelCircuit` | `String` | Full circuit text used when opening subcircuit for editing |
| `sequenceNumber` | `int` | Process-wide counter; bumped on mutation (consumers detect stale bindings) |
| `dumped`, `builtIn`, `internal` | `boolean` | Metadata |

`ExtListEntry` fields (`ExtListEntry.java:5-21`): `name`, `node`, `pos`, `side` (uses `ChipElm.SIDE_W`/`SIDE_E`/`SIDE_N`/`SIDE_S`).

Built-in macros `~LM317-v2`, `~TL431` (injected via `loadInternalModels()`).

Dump token: `". "`.

Invariants:
- `modelMap` keyed by name; rename leaves old key reachable (intentional).
- `sequenceNumber` monotonically increases per instance on mutation.
- Local-storage key pattern: `subcircuit:<name>`.

## 02. Contracts  {#SP_SHM_02}

> Implements: [C_SHM_03](./shared-models.concept.md#C_SHM_03)

### 02_01. Catalog API  {#SP_SHM_02_01}

| Method | Signature | Purpose |
|--------|-----------|---------|
| `getModelWithName` | `static T getModelWithName(String)` | Return existing or lazy-create |
| `getModelWithNameOrCopy` | `static T getModelWithNameOrCopy(String, T template)` | Return existing or clone template under new name |
| `getModelList` | `static Vector<T> getModelList(...)` | Sorted list for dialog choice; hides `internal` |
| `clearDumpedFlags` | `static void clearDumpedFlags()` | Reset export state |
| `getModelWithParameters` | (DiodeModel only) `static DiodeModel getModelWithParameters(double fwdrop, double zvoltage)` | Legacy pre-model bridge |

Note: `CustomCompositeModel.getModelWithName` does **not** auto-create (returns null if missing).

### 02_02. Serialization  {#SP_SHM_02_02}

Dump format: `<token> <fields...>` single-line.

| Model | Token | Dump site | Import site |
|-------|-------|-----------|-------------|
| CustomLogicModel | `"! "` | `CustomLogicModel.java:239` | `TextCircuitImporter.java:242` |
| TransistorModel | `"32 "` | `TransistorModel.java:256` | `TextCircuitImporter.java:267` |
| DiodeModel | `"34 "` | `DiodeModel.java:327` | `TextCircuitImporter.java:263` |
| CustomCompositeModel | `". "` | `CustomCompositeModel.java:197` | `TextCircuitImporter.java:275` |

The exporter calls `clearDumpedFlags()` on all four before each dump run so only models actually referenced by the circuit (and not yet emitted) are written.

### 02_03. Editable contract  {#SP_SHM_02_03}

`DiodeModel`, `TransistorModel`, `CustomLogicModel` implement `Editable` and expose fields via `getEditInfo(int n)` / `setEditValue(int n, EditInfo)`. `CustomCompositeModel` is edited by `EditCompositeModelDialog` through direct field access on `extList`.

### 02_04. SimulationContextAware  {#SP_SHM_02_04}

`DiodeModel`, `TransistorModel`, `CustomLogicModel` hold a nullable `circuitDocument` set via `setSimulationContext(CircuitDocument)`. After edit, `setEditValue` triggers `circuitDocument.simulator.updateModels()` so every referencing element re-reads parameters.

`CustomCompositeModel` has no such hook — consumers detect changes via `sequenceNumber`.

### 02_05. Copy semantics  {#SP_SHM_02_05}

`getModelWithNameOrCopy` performs a shallow copy-constructor of primitive/String fields. For `CustomLogicModel`, Vectors `rulesLeft`/`rulesRight` are **aliased** (not deep-copied) — safe only because `parseRules` always reassigns on the next edit.

## 03. Validation Rules  {#SP_SHM_03}

### 03_01. Input validation  {#SP_SHM_03_01}

- **DiodeModel**: `breakdownVoltage` → `Math.abs(...)`. No other range checks (negative IS/N allowed; NaN risk).
- **TransistorModel**: no validation. UI passes `1 / ei.value` for VAF/VAR/IKF/IKR without zero-guard.
- **CustomLogicModel**: `parseRules` validates grammar line-by-line; errors via `Window.alert`; parsing aborts on first invalid line; not run on `undump`.
- **CustomCompositeModel**: no validation on `nodeList`/`elmDump`. Malformed model surfaces only when `CustomCompositeElm` tries to instantiate.

## 04. State Transitions  {#SP_SHM_04}

### 04_01. Catalog lifecycle  {#SP_SHM_04_01}

```
  [not in map] --getModelWithName--> [in map, dumped=false]
                 |                         │
                 └--undumpModel------------┘
                                            │
                                  model.dump() → [dumped=true]
                                            │
                                  clearDumpedFlags() → [dumped=false]

  edit dialog:  setEditValue → updateModel() → simulator.updateModels()
                                                  (consumers re-read next step)
```

### 04_02. CustomCompositeModel local-storage  {#SP_SHM_04_02}

```
  [in map, unsaved]  --setSaved(true)-->  [localStorage "subcircuit:<name>"]
                     <--setSaved(false)-- (removed from storage)
```

### 04_03. Consumer rebind  {#SP_SHM_04_03}

Element's `modelName` changes → element calls `Model.getModelWithNameOrCopy(modelName, currentModel)` (DiodeElm, TransistorElm, CustomLogicElm) or `getModelWithName` without copy (CustomCompositeElm).

## 05. Verification Criteria  {#SP_SHM_05}

### 05_01. Functional Expectations  {#SP_SHM_05_01}

| Contract | Scenario | Input | Expected |
|----------|----------|-------|----------|
| `getModelWithName` | existing | `"default"` | returns existing entry |
| `getModelWithName` | unknown | new name | creates minimal default entry |
| `getModelWithNameOrCopy` | clone | (newName, template) | new entry with template field values |
| `dump/undump` | round-trip | any model | equal field values after parse |
| `clearDumpedFlags` | post-export | — | all models' `dumped = false` |
| `DiodeModel.breakdownVoltage` | negative input | −5 | stored as `5` |

### 05_02. Invariant Checks  {#SP_SHM_05_02}

| Invariant | Verification |
|-----------|--------------|
| `modelMap` keyed by name | key equality check |
| `CustomCompositeModel.sequenceNumber` increases on mutation | counter assertion |
| `CustomLogicModel` rule vectors reassigned by `parseRules` | identity check after edit |

### 05_03. Integration Scenarios  {#SP_SHM_05_03}

| Scenario | Preconditions | Steps | Expected |
|----------|---------------|-------|----------|
| Model edit propagation | 3 diodes share `"1N4148"` | user edits saturationCurrent | all 3 reflect new value next step |
| Rename leaves old key | model renamed A→B | lookup A and B | both resolve to same model |
| CustomComposite local-storage wins | local has `modelCircuit`, file has dump | open file | local content preserved |
| BJT model not used by MOSFET | MosfetElm in circuit | inspect imports | no `TransistorModel` reference |

### 05_04. Edge Cases  {#SP_SHM_05_04}

| Case | Input | Expected |
|------|-------|----------|
| Token collision | future element dump type `32` | silent importer mis-dispatch |
| Shallow rule-Vector copy | edit copy's rules before `parseRules` | original's Vectors corrupted |
| Malformed rules on import | bad grammar in stored model | `Window.alert` per line on next edit (not on import) |
| TransistorModel div-by-zero | VAF = 0 in UI | stored as infinity; downstream NaN risk |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
