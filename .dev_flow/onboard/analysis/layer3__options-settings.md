# Module Analysis: layer3 / options-settings

> **Path:** src/main/java/com/lushprojects/circuitjs1/client/ (4 files)
> **Layer:** 3 (application shell — user-preferences concept)
> **Analyzed:** 2026-04-19
> **Files:** OptionsManager.java (195 LOC), DisplaySettings.java (146 LOC),
> ColorSettings.java (427 LOC), QueryParameters.java (58 LOC) — 826 LOC total

## Summary (3 lines)

`OptionsManager` is a stateless facade over `gwt.storage.Storage` (localStorage)
exposing typed get/set for the global key/value preference catalog; every
persisted user preference in the simulator — colors, numeric formats, locale,
menu flags, modded UI knobs, even stored subcircuits — flows through it.
`DisplaySettings` wraps a `MenuManager` to expose the ten boolean draw-mode
flags as a read API for renderers and caches the two `decimalDigits`
preferences loaded from storage; `ColorSettings` is a singleton palette
(background/foreground/element/select/+/-/neutral/current/post) that
precomputes a 201-entry voltage color scale and applies a printable-mode
override at read time. `QueryParameters` parses `window.location.search` at
startup so `?key=value` URL overrides (locale, colors, euroResistors,
hideMenu…) can seed the in-memory state before localStorage is consulted.

## Purpose

The `options-settings` sub-unit implements the single **"user preferences"**
concept of CircuitJS1: a tri-layer resolution pipeline (URL query string →
localStorage → in-memory defaults), plus the typed accessors that the rest
of the codebase uses to read/write those prefs.

It is the application-shell counterpart to the UI `EditOptions`
form documented in `domain-core__dialog-edit.md` — `EditOptions` writes
into this sub-unit via `OptionsManager.setOptionInStorage(...)` and the
`ColorSettings` / `DisplaySettings` setters.

Three responsibilities:

1. **Persistence primitive** (`OptionsManager`) — null-safe String/bool/int/
   double wrappers over GWT `Storage` + cookie-style key namespacing.
2. **Typed preference caches** (`DisplaySettings`, `ColorSettings`) — turn
   stored strings into live `int` / `Color` / `boolean` fields that
   `CircuitElm` subclasses and the renderer consult every frame.
3. **URL override ingestion** (`QueryParameters`) — hoist `?param=value`
   into a `HashMap` so `CircuitInfo.loadQueryParameters()` and
   `circuitjs1.onModuleLoad` can pre-seed prefs before any `CirSim` field
   is read.

## Per-file analysis

### OptionsManager.java (195 LOC)

**Class:** `public class OptionsManager` — all methods `static`, no state.
Header comment: "Centralized manager for localStorage operations"
(`OptionsManager.java:5-8`).

#### Low-level storage handle

| Method | Purpose |
|---|---|
| `getLocalStorage()` → `Storage` | `Storage.getLocalStorageIfSupported()` (`:15-17`) — may return null (private browsing, legacy browsers). |
| `hasLocalStorage()` → `boolean` | `Storage.isLocalStorageSupported()` (`:23-25`). Not used in other files (see Issues). |

#### Typed accessors (null-safe, default-on-missing)

| Getter | Signature | Default path |
|---|---|---|
| `getOptionFromStorage(key, defVal)` | `String → String` (`:33-42`) | Returns `defVal` if storage unavailable OR key absent. |
| `getBoolOptionFromStorage(key, defVal)` | `String → bool` (`:90-96`) | Strict `"true".equals(res)` — any other value → `false` (the fix comment at `:93` notes a prior `==` bug). |
| `getIntOptionFromStorage(key, defVal)` | `String → int` (`:104-114`) | Catches `NumberFormatException`, returns default. |
| `getDoubleOptionFromStorage(key, defVal)` | `String → double` (`:122-132`) | Same pattern as int. |

#### Typed setters (coerce → String)

| Setter | Signature | Notes |
|---|---|---|
| `setOptionInStorage(key, String)` | `:49-55` | Base setter; logs `"Save option: key = val"` through `CirSim.console` on every write. |
| `setOptionInStorage(key, boolean)` | `:62-64` | Writes literal `"true"` / `"false"`. |
| `setOptionInStorage(key, int)` | `:71-73` | `String.valueOf`. |
| `setOptionInStorage(key, double)` | `:80-82` | `String.valueOf` — locale-sensitive? GWT uses C locale, so OK. |

#### Catalog ops

- `removeOptionFromStorage(key)` (`:138-143`) — drop a single key.
- `clearAllOptions()` (`:148-153`) — nukes the entire localStorage domain
  (used by "reset all options" flows).
- `getStorageLength()` / `getStorageKey(int)` (`:159-165`, `:172-178`) —
  enumeration API used by `CustomCompositeModel.java:46-52` to iterate
  `"subcircuit:*"` entries.
- `hasOption(key)` (`:185-187`) — thin wrapper: `getOptionFromStorage(key,null)!=null`.
- `getPrefixedKey(optionPrefix, key)` (`:189-194`) — builds `"prefix.key"`
  for scoped namespaces; used only by `dialog/Dialog.java` to persist
  dialog `pos` and `collapsed` per-dialog (`Dialog.java:121,130,356,369`).

### DisplaySettings.java (146 LOC)

**Class:** `public class DisplaySettings` — hybrid: holds a `menuManager`
reference for *instance* getters, but also maintains two *static* number-
format caches. Constructed once by `MenuManager` at boot.

#### Static number-format cache

| Field | Type | Default | Key |
|---|---|---|---|
| `decimalDigits` | `int` | `3` (`:30`) | `"decimalDigits"` |
| `shortDecimalDigits` | `int` | `1` (`:31`) | `"decimalDigitsShort"` |
| `numberFormatsLoaded` | `boolean` | lazy flag (`:15`) | — |

- `ensureNumberFormatsLoaded()` (`:23-27`) — lazy bootstrap.
- `reloadNumberFormatsFromStorage()` (`:29-33`) — public entry for
  refresh-after-reset flows.
- `setDecimalDigits(num, save)` (`:45-54`) / `setDecimalDigitsShort(num, save)`
  (`:56-65`) — clamps `<0` to 0; persists via `OptionsManager` iff `save`.

#### Instance boolean getters (delegated to `MenuManager` check items)

All ten getters follow the pattern `menuManager != null && item != null && item.getState()`, so they are safe during early-boot reads:

| Getter | `MenuManager` field | Line |
|---|---|---|
| `showDots()` | `dotsCheckItem` | `:70-73` |
| `showVoltage()` | `voltsCheckItem` | `:78-81` |
| `showPower()` | `powerCheckItem` | `:86-89` |
| `showValues()` | `showValuesCheckItem` | `:94-97` |
| `euroResistors()` | `euroResistorCheckItem` | `:102-105` |
| `euroGates()` | `euroGatesCheckItem` | `:110-113` |
| `printableMode()` | `printableCheckItem` | `:118-121` |
| `showConductance()` | `conductanceCheckItem` | `:126-129` |
| `smallGrid()` | `smallGridCheckItem` | `:134-137` |
| `showCrossHair()` | `crossHairCheckItem` | `:142-145` |

These are *read-only views*; the source of truth is the GWT check-menu
item's state, which `MenuManager` initialises from localStorage at
`:285-349` (see integration table below).

### ColorSettings.java (427 LOC)

**Class:** `public class ColorSettings` — lazy singleton (`instance` at
`:14`, double getter API `getInstance()` `:93-98` and `get()` shorthand
`:105-107`). Private ctor at `:71-85` initialises defaults.

#### Fields

| Field | Type | Default (normal mode) | Purpose |
|---|---|---|---|
| `voltageRange` | `double` | `5` (`:20`) | Half-range ±V that spans full color scale. |
| `COLOR_SCALE_COUNT` | `int` const | `201` (`:26`) | Odd so 0V sits at index 100. |
| `colorScale` | `Color[]` | built by `updateColorScale()` | Precomputed gradient. |
| `backgroundColor` | `Color` | `Color.black` (`:79`) | Canvas/UI bg. |
| `foregroundColor` | `Color` | `Color.white` (`:80`) | Text/UI overlays. |
| `elementColor` | `Color` | `Color.gray` (`:81`) | Default element stroke. |
| `selectColor` | `Color` | `Color.cyan` (`:77`) | Highlighted element. |
| `positiveColor` | `Color` | `Color.green` (`:74`) | `+V` end of scale. |
| `negativeColor` | `Color` | `Color.red` (`:75`) | `-V` end of scale. |
| `neutralColor` | `Color` | `Color.gray` (`:76`) | Midpoint (0V). |
| `currentColor` | `Color` | `Color.yellow` (`:78`) | Flow dots. |
| `postColor` | `Color` | `Color.gray` (`:82`) | Terminals/posts. |
| `printable` | `boolean` | `false` (`:69`) | View-mode override flag. |

#### Core algorithms

- `updateColorScale()` (`:115-131`) — rebuilds `colorScale[]`; each slot
  `i` maps parameter `v = i*2/COLOR_SCALE_COUNT - 1` ∈ (-1,1); `v<0`
  blends `neutral→negative` with `|v|`, else `neutral→positive`. Uses
  constructor `new Color(c1, c2, frac)` (`:126,128`).
- `getVoltageColor(double volts)` (`:142-154`) — clamps `(volts+range)*
  (N-1)/(range*2)` to `[0, N-1]`. Printable short-circuits to `Color.black`.
- `getPowerColor(double power)` (`:165-177`) — maps `[−1,1]` power to
  index via `N/2 + N/2 * (−power)` (note: the `-power` inverts sign so
  positive power lights the red/negative side of the scale).

#### Printable mode override (render-time, not mutation)

Every color getter checks `printable` and returns a hard-coded alt:

| Getter | Normal → | Printable → | Line |
|---|---|---|---|
| `getBackgroundColor()` | field | `Color.white` | `:205-210` |
| `getForegroundColor()` | field | `Color.black` | `:227-232` |
| `getElementColor()` | field | `Color.black` | `:249-254` |
| `getSelectColor()` | field | `Color.blue` | `:271-276` |
| `getPositiveColor()` | field | `Color.black` | `:293-298` |
| `getNegativeColor()` | field | `Color.black` | `:316-321` |
| `getNeutralColor()` | field | `Color.black` | `:339-344` |
| `getCurrentColor()` | field | `Color.black` | `:362-367` |
| `getPostColor()` | field | `Color.gray` | `:384-389` |
| `getVoltageColor(v)` / `getPowerColor(p)` | scale lookup | `Color.black` | `:143-145, :166-168` |

Setters never branch on `printable` — they always write the normal-mode
field. `isPrintable()` / `setPrintable(b)` at `:414-426` toggle the flag;
callers must invoke `updateColorScale()` themselves after changing
pos/neg/neutral (documented at `:302-304,324-326,346-348`).

### QueryParameters.java (58 LOC)

**Class:** `public class QueryParameters` — stateful, one-shot parser.

- Ctor (`:29-39`) reads `window.location.search` via JSNI
  `getQueryString()` (`:54-57`), strips leading `?`, splits on `&` then
  `=`, URL-decodes each value (`URL.decode(pair[1])`), stuffs into
  `HashMap<String,String> map` (`:27`).
- `getValue(key)` (`:41-43`) — raw string lookup, may return `null`.
- `getBooleanValue(key, def)` (`:46-52`) — `"1"` (identity check!, see
  Issues) or case-insensitive `"true"` → `true`; absent → `def`.

The class is instantiated twice per boot:
`circuitjs1.onModuleLoad` for `?lang=` (`circuitjs1.java:87-90`) and
`CircuitInfo.loadQueryParameters()` for all the circuit-level overrides
(`CircuitInfo.java:71-109`).

## Options lifecycle: URL → localStorage → edit → persist

```
     ┌─────────────────────────────────────────────────────────┐
     │  ?lang, ?positiveColor, ?euroResistors, ?hideMenu, …    │
     │  (window.location.search)                               │
     └────────────────────────┬────────────────────────────────┘
                              │ parsed at boot
                              ▼
               QueryParameters.map  (HashMap<String,String>)
                              │
        ┌─────────────────────┼──────────────────────────────────────┐
        │                                                            │
        ▼ circuitjs1.java:87-90                                      ▼ CircuitInfo.java:71-119
   ?lang → language seed                             ?param → CircuitInfo fields
        │                                                            │
        │ fallback per-field:                                        │
        │   qp.getValue(k) ?: OptionsManager.getOptionFromStorage(k) │
        │                                                            │
        ▼                                                            ▼
   localStorage  ←─  setOptionInStorage(k,v)  ←─  EditOptions dialog (setEditValue)
   via Storage API       (OptionsManager)            dialog/EditOptions.java:178,209,224
        │
        │ at boot, after URL pass:
        ▼
   DisplaySettings.reloadNumberFormatsFromStorage()   (DisplaySettings.java:29-33)
   CirSim.setColors() reads pos/neg/neutral/select/currentColor
     (CirSim.java:380-388) → ColorSettings.setXxx()   (CirSim.java:391-408)
   MenuManager wires each CheckItem.setState(...)
     from getBoolOptionFromStorage                    (MenuManager.java:285-349)
        │
        ▼
   runtime reads:
     DisplaySettings.getDecimalDigits() / instance.showDots() / …
     ColorSettings.get().getVoltageColor(v) / .getElementColor() / …
        │
        ▼
   user toggles menu / opens EditOptions dialog
        │
        ▼
   menu click  → OptionsManager.setOptionInStorage(k, newVal)
                   (MenuManager.java:285,289,300,307,315,326,334,349)
   dialog OK   → EditOptions.setEditValue(n, ei)
                   (dialog/EditOptions.java:178,209,224 + setColor helper)
                 → ColorSettings.setXxx() + updateColorScale()
                 → DisplaySettings.setDecimalDigits[Short](n, save=true)
```

## Keys catalog (LocalStorage)

The following is the complete list of keys observed in the codebase; grouped by owning sub-unit.

### Number formatting (`DisplaySettings`)

| Key | Type | Default | Writer | Reader |
|---|---|---|---|---|
| `decimalDigits` | int | `3` | `DisplaySettings:52` (via `EditOptions` n=8) | `DisplaySettings:30` |
| `decimalDigitsShort` | int | `1` | `DisplaySettings:63` (via `EditOptions` n=7) | `DisplaySettings:31` |

### Colors (`ColorSettings`, persisted as hex strings)

| Key | Writer | Reader |
|---|---|---|
| `positiveColor` | `EditOptions.setColor` | `CirSim.java:380` |
| `negativeColor` | `EditOptions.setColor` | `CirSim.java:382` |
| `neutralColor` | `EditOptions.setColor` | `CirSim.java:384` |
| `selectColor` | `EditOptions.setColor` | `CirSim.java:386` |
| `currentColor` | `EditOptions.setColor` | `CirSim.java:388` |
| `alternativeColor` | — (bootstrap flag) | `CirSim.java:392` |

### Display flags (`MenuManager` check items, persisted as `"true"/"false"`)

| Key | Default | Line (pair of set+read) |
|---|---|---|
| `toolbar` | `true` | `MenuManager.java:285,289` |
| `showMouseMode` | `true` | `:293,296` |
| `crossHair` | `false` | `:300,303` |
| `euroResistors` | locale-dependent | `:307` + `CircuitInfo.java:115` |
| `euroGates` | locale-dependent | `:315` + `CircuitInfo.java:92` |
| `whiteBackground` | `false` | `:326` + `CircuitInfo.java:97` |
| `conventionalCurrent` | `true` | `:334` + `CircuitInfo.java:99` |
| `mouseWheelEdit` | `true` | `:349` + `CircuitInfo.java:102` |

### Locale, input, recovery, scope

| Key | Type | Default | Used by |
|---|---|---|---|
| `language` | string | `null` | `circuitjs1.java:90` (read), `EditOptions.java:178` (write) |
| `wheelSensitivity` | double | `"1"` | `CircuitEditor.java:111` (read), `EditOptions.java:209` (write) |
| `circuitRecovery` | string (circuit dump) | — | `UndoManager.java:91,95` |
| `scopeDefaults` | `"1 <flags> <speed>"` | — | `Scope.java:2485,2490` |
| `shortcuts` | string | `null` | `MenuManager.java:967,972` |

### Subcircuits (enumerated)

| Key | Writer | Reader |
|---|---|---|
| `subcircuit:<name>` | `CustomCompositeModel.java:160,162` | `CustomCompositeModel.java:46-52,154` |

### Modded UI bundle (`MOD_*` — all in `ModDialog`)

| Key | Type | Default | Line |
|---|---|---|---|
| `MOD_UIScale` | float (string) | `getDefaultScale()` | `CirSim.java:171` / `ModDialog.java:172,183,190` |
| `MOD_TopMenuBar` | string `"standart"\|"small"` | `"standart"` | `CirSim.java:172` / `ModDialog.java:223,237` |
| `MOD_overlayingSidebar` | bool | `false` | `ModDialog.java:278,317,322` |
| `MOD_overlayingSBAnimation` | bool | `false` | `ModDialog.java:283,331,335` |
| `MOD_SBAnim_duration` | string | `null` | `ModDialog.java:287,300` |
| `MOD_SBAnim_SpeedCurve` | string | `null` | `ModDialog.java:288,308` |
| `MOD_showSidebaronStartup` | bool | `false` | `ModDialog.java:341,346` |
| `MOD_setPauseWhenWinUnfocused` | bool | `false` | `ModDialog.java:353,358` |

### Prefixed dialog geometry (`getPrefixedKey`)

| Pattern | Type | Purpose |
|---|---|---|
| `<prefix>.pos` | `"x,y"` string | Remembered position, `Dialog.java:121,131` |
| `<prefix>.collapsed` | `"0"/"1"` | Collapsed state, `Dialog.java:356,369` |

## URL query parameters catalog

All parsed through `QueryParameters` (single pass in `CircuitInfo.loadQueryParameters()` unless noted).

| Query key | Getter | Default | Consumer |
|---|---|---|---|
| `cct` | `getValue` | — | circuit text | `CircuitInfo.java:76` |
| `ctz` | `getValue` | — | circuit compressed | `:83` |
| `startCircuit` | `getValue` | — | initial circuit file | `:87` |
| `startLabel` | `getValue` | — | `:88` |
| `startCircuitLink` | `getValue` | — | `:89` |
| `euroResistors` | bool | `false` | `:90` |
| `IECGates` | bool | `getBoolOptionFromStorage("euroGates", weAreInGermany())` | `:91-92` |
| `usResistors` | bool | `false` | `:93` |
| `running` | bool | `false` | `:94` |
| `hideMenu` | bool | `false` | `:95` |
| `whiteBackground` | bool | `getBoolOptionFromStorage("whiteBackground", false)` | `:96-97` |
| `conventionalCurrent` | bool | `getBoolOptionFromStorage("conventionalCurrent", true)` | `:99` |
| `editable` | bool | `true` | `:100` (inverted) |
| `mouseWheelEdit` | bool | `getBoolOptionFromStorage("mouseWheelEdit", true)` | `:101-102` |
| `positiveColor` / `negativeColor` / `neutralColor` / `selectColor` / `currentColor` | string | — | `:103-107` |
| `mouseMode` | string | — | `:108` |
| `hideInfoBox` | bool | `false` | `:109` |
| `lang` | string | `null` | `circuitjs1.java:88` (separate early pass) |

URL overrides that match a storage key (`euroResistors`, `whiteBackground`,
etc.) intentionally layer: URL wins when present, otherwise the stored
value wins, otherwise hard-coded default.

## Public contracts

### `OptionsManager` (all static)

```java
Storage getLocalStorage();                         // nullable
boolean hasLocalStorage();
String  getOptionFromStorage(String k, String def);
boolean getBoolOptionFromStorage(String k, boolean def);
int     getIntOptionFromStorage(String k, int def);
double  getDoubleOptionFromStorage(String k, double def);
void    setOptionInStorage(String k, String  v);   // base; logs via CirSim.console
void    setOptionInStorage(String k, boolean v);
void    setOptionInStorage(String k, int     v);
void    setOptionInStorage(String k, double  v);
void    removeOptionFromStorage(String k);
void    clearAllOptions();
int     getStorageLength();
String  getStorageKey(int idx);
boolean hasOption(String k);
String  getPrefixedKey(String prefix, String key);  // "prefix.key", or "key" if prefix empty
```

### `DisplaySettings`

```java
// static numeric prefs
static int  getDecimalDigits();          // default 3
static int  getShortDecimalDigits();     // default 1
static void setDecimalDigits(int, boolean save);
static void setDecimalDigitsShort(int, boolean save);
static void reloadNumberFormatsFromStorage();

// instance boolean views of MenuManager check items
boolean showDots(), showVoltage(), showPower(), showValues(),
        euroResistors(), euroGates(), printableMode(),
        showConductance(), smallGrid(), showCrossHair();
```

### `ColorSettings` (singleton)

```java
static ColorSettings getInstance();
static ColorSettings get();             // alias

// voltage/power mapping
double  getVoltageRange();  void setVoltageRange(double);
Color   getVoltageColor(double v);
Color   getPowerColor(double p);
int     getColorScaleCount();
void    updateColorScale();             // call after setting pos/neg/neutral

// palette getters/setters (get() honors printable)
Color   getBackgroundColor(); void setBackgroundColor(Color);
Color   getForegroundColor(); void setForegroundColor(Color);
Color   getElementColor();    void setElementColor(Color);
Color   getSelectColor();     void setSelectColor(Color);
Color   getPositiveColor();   void setPositiveColor(Color);
Color   getNegativeColor();   void setNegativeColor(Color);
Color   getNeutralColor();    void setNeutralColor(Color);
Color   getCurrentColor();    void setCurrentColor(Color);
Color   getPostColor();       void setPostColor(Color);

// view-mode override
boolean isPrintable();  void setPrintable(boolean);
```

### `QueryParameters`

```java
QueryParameters();                           // parses window.location.search
String  getValue(String key);                // nullable
boolean getBooleanValue(String key, boolean def);
```

## Integration points

| Consumer | Role | References |
|---|---|---|
| `CirSim` | Boot sequencer: reads `MOD_*`, hydrates `ColorSettings` from storage, runs URL→storage merge. | `CirSim.java:171-172, 221, 375-411` |
| `circuitjs1` | Entry point; instantiates `QueryParameters` for `?lang` before any other pref is read. | `circuitjs1.java:87-90` |
| `CircuitInfo` | URL-override holder; `loadQueryParameters()` seeds boolean/string overrides using `qp.getBooleanValue(k, getBoolOptionFromStorage(k,def))` merging URL and stored prefs. | `CircuitInfo.java:71-119` |
| `MenuManager` | Wires each `CheckMenuItem` to `setOptionInStorage` on change and `setState(getBoolOptionFromStorage(...))` on create. Owns all display-flag keys. Also shortcuts. | `MenuManager.java:285-349, 967, 972` |
| `EditOptions` (dialog) | UI over these prefs — writes `language`, `wheelSensitivity`, colors, decimal digits. | `dialog/EditOptions.java:178, 209, 224` (+ setColor helper) |
| `ModDialog` | Owns the `MOD_*` bundle. | `dialog/ModDialog.java:172-358` |
| `Dialog` | Uses `getPrefixedKey` to persist per-dialog pos/collapsed. | `dialog/Dialog.java:121-131, 356-370` |
| `CircuitEditor` | Reads `wheelSensitivity` at init. | `CircuitEditor.java:111` |
| `CustomCompositeModel` | Enumerates `subcircuit:*` keys via `getStorageLength`/`getStorageKey`. | `CustomCompositeModel.java:46-52, 154, 160, 162` |
| `UndoManager` | Stores last-known-good circuit under `circuitRecovery`. | `UndoManager.java:91, 95` |
| `Scope` | Persists scope defaults. | `Scope.java:2485, 2490` |
| `CircuitElm` subclasses | Read renderer flags/colors every paint via `DisplaySettings` instance and `ColorSettings.get()`. | (ubiquitous) |

## Concept boundary: "user preferences"

Exactly **one** bounded context lives here — a runtime registry of
user-level preferences. It is characterised by:

- **Identity**: key-per-preference (string), no compound aggregates.
- **Invariants**: Every preference has a String representation and an
  in-code default. Readers tolerate missing keys; writers are idempotent.
- **Lifetime**: process + localStorage domain. URL overrides are
  process-lifetime only; they are *not* written back to storage unless
  passed through the same setter a menu/dialog would use.
- **Ownership**: `OptionsManager` owns persistence; `ColorSettings` and
  `DisplaySettings` own typed in-memory shape; `QueryParameters` owns
  URL ingestion. Nothing else in the sub-unit holds preference state.
- **Out of scope**: circuit model state (elements, nodes) — those belong
  to `CircuitDocument` / `CircuitSimulator`. Dialog geometry (`pos`,
  `collapsed`) reuses the registry but is structurally a separate
  concern (per-dialog UI state) layered on top via `getPrefixedKey`.

## Issues & risks

1. **No central key registry / no type safety.** Every key is a string
   literal scattered across 10+ files. A typo like `"decimaldigits"` vs
   `"decimalDigits"` would silently resolve to the default forever. There
   is no constants class, no enum, no schema. (`OptionsManager.java:33,49`
   consumers across `MenuManager`, `EditOptions`, `ModDialog`, `CirSim`,
   `CircuitEditor`, `Scope`, `UndoManager`, `CustomCompositeModel`.)

2. **Inconsistent boolean representations.**
   - `OptionsManager.setOptionInStorage(key, boolean)` writes
     `"true"/"false"` (`:62-63`).
   - `Dialog.java:370` writes `"1"/"0"` for `collapsed`.
   - `QueryParameters.getBooleanValue` accepts `"1"` *or*
     `"true"` (`:51`).
   - Reader `getBoolOptionFromStorage` only accepts `"true"` (`:93`).
   So a value written as `"1"` via `Dialog.collapsed` would be read back
   as `false` if ever routed through `getBoolOptionFromStorage`.

3. **String identity bug in `QueryParameters.getBooleanValue`.**
   `val == "1"` (`:51`) — reference equality, works only because
   `URL.decode` may intern the short string. Should be `"1".equals(val)`.

4. **`QueryParameters` constructor is fragile.**
   - No try/catch around `split("=")` — a bare `?foo&bar=baz` makes
     `pair[1]` throw `ArrayIndexOutOfBoundsException` (`:36`).
   - Does not handle repeat keys (silently overwrites).
   - `URL.decode` on `pair[1]` only — keys are not decoded.

5. **Silent coercion.** `getIntOptionFromStorage` / `getDoubleOption…`
   swallow `NumberFormatException` with no log. A corrupt value returns
   the default but the user never learns about it.

6. **Logging on every set.** `setOptionInStorage(...)` logs
   `"Save option: k = v"` via `CirSim.console` (`:53`). Colors, decimal
   digits, and every menu toggle produce console noise, including
   secrets-free but still verbose subcircuit dumps (`subcircuit:*`).

7. **Duplicate/near-duplicate keys and typos.**
   - `"decimalDigits"` vs `"decimalDigitsShort"` — consistent.
   - `"MOD_TopMenuBar"` default `"standart"` (misspelling of
     "standard", `CirSim.java:172` + `ModDialog.java:223`).
   - Display-flag key `"whiteBackground"` stores the state that
     `DisplaySettings.printableMode()` exposes and `ColorSettings.setPrintable`
     applies — three names for one boolean ("whiteBackground",
     "printable", "printableMode"), easy to mis-align.
   - `euroGates` URL param is spelled `IECGates` (`CircuitInfo.java:91`)
     while the storage key is `euroGates` (`MenuManager.java:315`) — each
     must be updated in lock-step.

8. **`DisplaySettings` hybrid (static + instance) state.** Static
   `decimalDigits`/`shortDecimalDigits` coexist with instance
   `menuManager`-delegated getters — unusual shape; tests can't sandbox
   either side cleanly, and `reloadNumberFormatsFromStorage()` is the
   only reset path.

9. **Unused API surface.** `hasLocalStorage()` is defined but not called
   anywhere outside `OptionsManager` itself; callers check null-return
   of `getLocalStorage` instead. `ColorSettings.postColor` is read
   (`getPostColor`) but never written through any persisted key — it's
   effectively a hard-coded constant that pretends to be configurable.

10. **`ColorSettings` has no persistence of its own.** It holds state but
    does not load/save; callers (`CirSim.setColors`, `EditOptions.setColor`)
    must remember to pair every setter with an
    `OptionsManager.setOptionInStorage(...)` call. This is the #1 place
    for future "forgot to persist" bugs.

11. **No `backgroundColor` / `foregroundColor` / `elementColor` /
    `postColor` storage keys.** They live only as defaults; once changed
    they cannot survive a reload. Compare with
    `positive/negative/neutral/select/currentColor` which are persisted.

12. **Race: `DisplaySettings` static loaded eagerly from ctor, but
    `EditOptions.setEditValue` writes via `setDecimalDigits(..., true)` —
    consistent as long as the static cache is always the source of
    truth.** Any direct `OptionsManager.setOptionInStorage("decimalDigits",…)`
    bypass (e.g. in tests) would de-sync the cache and require a call to
    `reloadNumberFormatsFromStorage()`.
