# User Preferences — Tri-Layer Resolution Registry  {#C_USR}

> **Code:** C_USR
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard-doc-gen
>
> **Depends on:** [C_UTL](./util-locale-log.concept.md), C_PLT (platform/localStorage), [C_RND](./rendering-primitives.concept.md)
> **Used by:** [C_MEN](./menus-actions.concept.md), [C_EDI](./canvas-editor.concept.md), [C_UND](./commands-undo.concept.md), [C_SCP](./scope-visualization.concept.md), [C_EIC](./edit-info-contract.concept.md)
> **Spike:** —
> **Specification:** [SP_USR](./user-preferences.sp.md)
> **Plan:** [user-preferences.plan.md](./user-preferences.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/layer3__options-settings.md`.
>
> The single "user preferences" concept: a tri-layer pipeline (URL query → localStorage → in-memory defaults) plus typed accessors. `OptionsManager` is a stateless facade over `gwt.storage.Storage`; `DisplaySettings` exposes numeric format prefs and ten MenuManager-backed boolean views; `ColorSettings` is a singleton palette with a precomputed 201-entry voltage scale and a printable-mode override; `QueryParameters` is a one-shot URL parser.

## 1. Philosophy  {#C_USR_01}

### 1.1. Core Principle  {#C_USR_01_01}

Every persisted user preference flows through `OptionsManager`, giving a single chokepoint for storage side-effects (logging every write). Typed caches (`ColorSettings`, `DisplaySettings`) translate stored strings into live `int`/`Color`/`boolean` fields that hot paths read every frame. URL parameters seed the in-memory state before `localStorage` is consulted, allowing shareable simulator configurations.

### 1.2. Design Constraints  {#C_USR_01_02}

- **No central key registry.** Every key is a string literal scattered across 10+ files (known smell).
- **String-valued storage.** Typed setters coerce via `String.valueOf`; readers tolerate missing keys by returning a provided default.
- **Printable-mode is a view override, not a mutation.** `ColorSettings` getters branch on `printable` to return hard-coded B/W substitutes; setters always write the normal-mode field.
- **Tri-layer merge.** URL wins when present, else `localStorage`, else hard-coded default.
- **Inconsistent boolean encoding.** `OptionsManager` writes `"true"`/`"false"`; `Dialog` writes `"1"`/`"0"` for `collapsed`; `QueryParameters` accepts both.

## 2. Domain Model  {#C_USR_02}

### 2.1. Key Entities  {#C_USR_02_01}

```
OptionsManager (all static, stateless facade over gwt.storage.Storage)
  getOption* / setOption* / removeOption / clearAllOptions
  getStorageLength / getStorageKey(i)     -- enumeration (subcircuit:*)
  hasOption(k) / getPrefixedKey(prefix,k)

DisplaySettings
  static int decimalDigits, shortDecimalDigits
  instance: MenuManager menuManager (read-only views of 10 check items)

ColorSettings (singleton)
  Color[] colorScale = new Color[201]         -- 0V at index 100
  double voltageRange = 5
  backgroundColor, foregroundColor, elementColor, selectColor,
  positiveColor, negativeColor, neutralColor, currentColor, postColor
  boolean printable   -- view-mode override

QueryParameters
  HashMap<String,String> map  -- parsed from window.location.search
```

### 2.2. Data Flows  {#C_USR_02_02}

```
Boot:
  circuitjs1.onModuleLoad:
    new QueryParameters()  -- reads location.search once
    lang = qp.getValue("lang")
         ?: OptionsManager.getOptionFromStorage("language", null)
         ?: navigator.languages[0] || navigator.language || "en-US"

  CirSim.init:
    CircuitInfo.loadQueryParameters()  -- merges per-field qp ?? storage
    CirSim.setColors()                 -- ColorSettings.setXxx from storage
    MenuManager.initOptionsMenuBar()   -- CheckboxMenuItem.setState from storage
    DisplaySettings.reloadNumberFormatsFromStorage()

Runtime read (hot path):
  ColorSettings.get().getVoltageColor(v)
  DisplaySettings.instance.showDots()
  DisplaySettings.getDecimalDigits()

Runtime write:
  Menu toggle               -> OptionsManager.setOptionInStorage(k, newVal)
  EditOptions.setEditValue  -> ColorSettings.setXxx + OptionsManager.set(...)
                            -> DisplaySettings.setDecimalDigits[Short](n, save=true)
  Dialog close              -> OptionsManager.setOptionInStorage(prefixed.pos, "x,y")
```

## 3. Mechanisms  {#C_USR_03}

### 3.1. Core Algorithm  {#C_USR_03_01}

**Voltage colour scale.** `updateColorScale()` fills 201 entries where index *i* maps to parameter `v = i*2/201 − 1 ∈ (−1,1)`; `v<0` blends `neutral→negative` with `|v|`, else `neutral→positive`. `getVoltageColor(volts)` clamps `(volts + voltageRange) * 200 / (2*voltageRange)` to `[0,200]`. In printable mode returns `Color.black` unconditionally.

**Power colour.** `getPowerColor(p)` maps `[-1,1]` to index via `N/2 + N/2 * (-power)` — positive power lights the red/negative side of the scale.

**Typed getters with default.** `getIntOptionFromStorage(key, def)` catches `NumberFormatException` silently; same for double. `getBoolOptionFromStorage(k, def)` requires strict `"true".equals(res)`.

**URL parse.** Ctor of `QueryParameters` splits `window.location.search` on `&`, then each pair on `=`; URL-decodes each value. Keys are not decoded, and bare flags (no `=`) crash.

### 3.2. Edge Cases  {#C_USR_03_02}

- `Dialog.collapsed` writes `"1"/"0"` — would read back as `false` via `getBoolOptionFromStorage` (mismatch); `QueryParameters.getBooleanValue` accepts both.
- `getBooleanValue` uses `val == "1"` reference equality — works only by intern accident.
- No storage keys for `backgroundColor`, `foregroundColor`, `elementColor`, `postColor` — they revert on reload.
- `clearAllOptions()` nukes the entire localStorage domain (used by "reset all options").
- `?foo&bar=baz` crashes `QueryParameters` via `ArrayIndexOutOfBoundsException`.

## 4. Integration Points  {#C_USR_04}

### 4.1. Dependencies  {#C_USR_04_01}

- **[C_UTL](./util-locale-log.concept.md)** — `Locale.LS` strings used by dialogs that mutate prefs.
- **C_PLT** — `gwt.storage.Storage`, `com.google.gwt.http.client.URL.decode`, `window.location.search`.
- **[C_RND](./rendering-primitives.concept.md)** — `Color` blending (`new Color(c1, c2, frac)`) used by `updateColorScale`.

### 4.2. API Surface  {#C_USR_04_02}

- `OptionsManager`: typed get/set over `Storage`, enumeration, `getPrefixedKey`.
- `DisplaySettings`: `getDecimalDigits/Short`, 10 boolean view getters, `reloadNumberFormatsFromStorage`.
- `ColorSettings`: palette getters/setters (printable-aware), `getVoltageColor/getPowerColor`, `updateColorScale`, `isPrintable/setPrintable`.
- `QueryParameters`: `getValue`, `getBooleanValue`.

Key consumers: `CirSim` (boot sequencer), `CircuitInfo` (URL overrides), `MenuManager` (check items), `EditOptions` + `ModDialog` (UI), `Dialog` (per-dialog pos/collapsed), `CustomCompositeModel` (subcircuit enumeration), `UndoManager` (`circuitRecovery`), `Scope` (`scopeDefaults`).

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
