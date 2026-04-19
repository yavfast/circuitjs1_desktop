# Module Analysis: io-framework

> **Path:** `src/main/java/com/lushprojects/circuitjs1/client/io/` (incl. `text/` and `json/`)
> **Layer:** 2 (composite SCC-B)
> **Analyzed:** 2026-04-18
> **Files:** 12 source files across 3 packages, 0 test files

## Purpose

Plugin framework for serialising a `CircuitDocument` to / from string payloads.
Exposes a small contract (`CircuitFormat` + `CircuitExporter` + `CircuitImporter`)
and a static `CircuitFormatRegistry` that looks formats up by id, by file
extension, or by content sniff. Two concrete formats ship in-tree:

1. **Legacy Falstad text format** (`io/text/`) — line-based `DumpType x y x2 y2 flags …`
   records produced by the original Falstad simulator, described in `docs/EXPORT_OLD.md`
   and `docs/EXPORT_CJS.md`. Default format (`DEFAULT_FORMAT_ID = "text"`).
2. **Modern JSON v2.0 format** (`io/json/`) — self-documenting object with
   `schema`, `simulation`, `elements`, `nodes`, `scopes`, `adjustables` sections,
   element IDs as object keys, SI-unit strings (`"10 kOhm"`) instead of raw
   numbers, and optional simulation-state round-tripping.

Two helper utilities live under `io/json/`:

- **`UnitParser`** — SI-prefix aware string → `double` parser used across the
  JSON importer and by a dozen element classes when reading property maps.
- **`CircuitElementFactory`** — explicit JSON-type-name → element-constructor
  registry with ~150 entries (one per concrete `*Elm`). Only the JSON importer
  uses it; the text importer bypasses the factory and goes through
  `CircuitElmCreator` (root-level).

## Sub-units

Four sub-units as declared in `queue.yaml`:

1. **`io-interfaces`** — `CircuitFormat.java`, `CircuitFormatRegistry.java`,
   `CircuitExporter.java`, `CircuitImporter.java` (pure framework surface).
2. **`io-text`** — `text/TextCircuitFormat.java`,
   `text/TextCircuitExporter.java`, `text/TextCircuitImporter.java`.
3. **`io-json`** — `json/JsonCircuitFormat.java`,
   `json/JsonCircuitExporter.java`, `json/JsonCircuitImporter.java`,
   `json/CircuitElementFactory.java`, `json/UnitParser.java`.
4. **`io-orchestrators`** — conceptual: the framework has no dedicated
   orchestrator class. `CircuitExporter` / `CircuitImporter` are *interfaces*;
   the real orchestration is performed by `CircuitLoader` and `ActionManager`
   at the root layer, driven through `CircuitFormatRegistry`.

> **Note on project_structure.md.** The onboard project_structure.md lists
> `UnitParser` and `CircuitElementFactory` under `client/io/` (4-file count). In
> the code they sit in `client/io/json/`, making the actual split `io/` = 4
> files, `io/text/` = 3, `io/json/` = 5. Total 12 matches the brief.

---

## Key Entities

### `io-interfaces`

#### `CircuitFormat`
- **Type:** interface
- **File:** `src/main/java/com/lushprojects/circuitjs1/client/io/CircuitFormat.java:26`
- **Fields:** none (interface)
- **Abstract methods:** `getId()`, `getName()`, `getFileExtensions()`,
  `getMimeType()`, `getVersion()`, `createExporter()`, `createImporter()`.
- **Default methods:** `getDefaultExtension()` returns first extension (:50);
  `supportsExtension(String)` tolerates leading-dot presence/absence (:84).
- **Invariants:**
  - `getId()` must be unique within the registry (used as `Map` key).
  - `getFileExtensions()` must be non-null; extensions include the leading dot.

#### `CircuitExporter`
- **Type:** interface
- **File:** `src/main/java/com/lushprojects/circuitjs1/client/io/CircuitExporter.java:30`
- **Abstract methods:** `export(CircuitDocument) : String`,
  `getFormat() : CircuitFormat`.
- **Default methods:**
  - `exportSelection(doc, selection)` (:45) — default falls back to `export(doc)`.
  - `export(doc, includeState)` (:57) — default ignores `includeState`. Only
    `JsonCircuitExporter` honours the state flag.

#### `CircuitImporter`
- **Type:** interface
- **File:** `src/main/java/com/lushprojects/circuitjs1/client/io/CircuitImporter.java:27`
- **Constants (import flags, bitmask):**
  | Flag | Value | Meaning |
  |---|---:|---|
  | `RC_RETAIN` | 1 | Keep current elements/state (paste / merge) |
  | `RC_SUBCIRCUITS` | 2 | Parse only composite-model (`.`) definitions |
  | `RC_NO_CENTER` | 4 | Do not recentre viewport after load |
  | `RC_KEEP_TITLE` | 8 | Preserve document title |
- **Abstract methods:** `importCircuit(data, doc, flags)`, `canImport(data)`,
  `getFormat()`.
- **Default method:** `importCircuit(data, doc)` → flags = 0 (:54).

#### `CircuitFormatRegistry`
- **Type:** final-use class with static state
- **File:** `src/main/java/com/lushprojects/circuitjs1/client/io/CircuitFormatRegistry.java:33`
- **Fields:**
  | Field | Type | Purpose |
  |---|---|---|
  | `formats` | `static final Map<String, CircuitFormat>` (LinkedHashMap) | insertion-order map, id → format |
  | `DEFAULT_FORMAT_ID` | `static final String = "text"` | fallback / legacy default |
- **Invariants:**
  - Class-init block (lines 40–44) registers `TextCircuitFormat` then
    `JsonCircuitFormat`. Text is registered first, so it wins ties and is the
    default.
  - Iteration order is insertion order (LinkedHashMap) — detection probes Text
    before JSON.
  - All API is `static`; there is no instance.

### `io-text`

#### `TextCircuitFormat`
- **Type:** class implements `CircuitFormat`
- **File:** `src/main/java/com/lushprojects/circuitjs1/client/io/text/TextCircuitFormat.java:30`
- **Metadata:** id `"text"`, name `"CircuitJS1 Text Format"`, extensions
  `.txt`, `.circuitjs`; MIME `text/plain`; version `"1.0"`.

#### `TextCircuitExporter`
- **Type:** class implements `CircuitExporter`
- **File:** `src/main/java/com/lushprojects/circuitjs1/client/io/text/TextCircuitExporter.java:52`
- **Fields:** `final TextCircuitFormat format`.
- **Behaviour:** builds a `StringBuilder(4096)` and writes (in order):
  1. `$ flags maxTimeStep iterCount currentBar voltageRange powerBar minTimeStep`
     options line (`dumpOptions`, :146).
  2. For each element: optional model dump (`ce.dumpModel()`) then
     `CircuitElm.dumpElm(ce)` (:73–86).
  3. Scope dumps via `Scope.dump()` (:89–96).
  4. `AdjustableManager.dump()` (:99).
  5. Hint line `h hintType hintItem1 hintItem2` when active (:104–108).
- **Invariants:** clears all four model-dump flag registers at the top of
  `export()` and `exportSelection()` so each run emits a self-contained file
  (:62–66, :119–123).

#### `TextCircuitImporter`
- **Type:** class implements `CircuitImporter`
- **File:** `src/main/java/com/lushprojects/circuitjs1/client/io/text/TextCircuitImporter.java:56`
- **Top-level flow:**
  - `importCircuit` (:64): optional `resetCircuitState`, then
    `parseCircuitLines`, then `finalizeCircuitLoading`.
  - `canImport` (:83): `true` if data trims to start with `$`, a letter, or a
    digit.
- **Line dispatch** (`processCircuitLine`, :180–213, three cascading switches):
  | Token | Handler | Meaning |
  |---|---|---|
  | `o` | `new Scope(...).undump(...)` | Oscilloscope config |
  | `h` | `readHint` | Hint annotation |
  | `$` | `readOptions` | Simulation options |
  | `!` | `CustomLogicModel.undumpModel` | Custom logic gate model |
  | `%`, `?`, `B` | ignored (afilter legacy) | — |
  | `34` | `DiodeModel.undumpModel` | Diode model |
  | `32` | `TransistorModel.undumpModel` | Transistor model |
  | `38` | `adjustableManager.addAdjustable` | Slider |
  | `.` | `CustomCompositeModel.undumpModel` | Composite (subcircuit) model |
  | any other | `createStandardElement` | Delegates to `CircuitElmCreator.createCe` |
- **Subcircuit mode** (`RC_SUBCIRCUITS`): short-circuits everything except
  `.` lines (:192).
- **Reset semantics** (`resetCircuitState`, :112): clears error/stop state,
  deletes all elements, resets timings, menu states, viewport controls, scope
  count, voltage range to 5 V, current/power/speed sliders to 50/50/117.
- **Finalisation** (:374): `setPowerBarEnable`, `enableItems`, slider creation,
  `needAnalyze`, optional `centreCircuit`, `updateModels` in subcircuit mode,
  `AudioInputElm.clearCache()`, `DataInputElm.clearCache()`,
  `setSlidersDialogHeight`.

### `io-json`

#### `JsonCircuitFormat`
- **Type:** class implements `CircuitFormat`
- **File:** `src/main/java/com/lushprojects/circuitjs1/client/io/json/JsonCircuitFormat.java:38`
- **Constants:** `FORMAT_ID = "json"`, `FORMAT_NAME = "CircuitJS JSON"`,
  `FORMAT_VERSION = "2.0"`, `EXTENSIONS = { ".json", ".circuitjs.json" }`,
  `MIME_TYPE = "application/json"`.

#### `JsonCircuitExporter`
- **Type:** class implements `CircuitExporter`
- **File:** `src/main/java/com/lushprojects/circuitjs1/client/io/json/JsonCircuitExporter.java:54`
- **Fields:**
  | Field | Type | Purpose |
  |---|---|---|
  | `format` | `JsonCircuitFormat` | back-reference |
  | `elementCounter` | `int` | per-export id generator |
  | `elementIds` | `Map<CircuitElm, String>` | memoised id per element |
  | `includeState` | `boolean` | toggled by 2-arg `export` |
  | `pinsByLocation` | `Map<String, List<PinInfo>>` | `"x,y"` → pins for node/connection detection |
- **Output sections** (`export` :67–107):
  1. `schema` — `{ format: "circuitjs", version: "2.0" }`.
  2. `simulation` — time steps (SI-formatted), display booleans, voltage
     range string, `current_speed`, `power_brightness`, optional
     `auto_time_step`.
  3. `elements` — keyed by generated id (prefix from type name, e.g. R1, C1,
     Q1, D1, LED1, W1, GND1, V1, I1, U1). Each element writes
     `{ type, description?, bounds, properties?, pins?, _flags?, state? }`.
  4. `nodes` — any `(x,y)` where ≥ 3 pins coincide becomes `N1…Nn` with a
     `connections` array of `"<id>.<pin>"` references.
  5. `scopes` — display flags, plot_mode, optional `trigger`, optional
     `history`, `scales`, optional `manual_scale`, `plots[]` (each plot has
     `element`, `units` name, `value` VAL_* int, `color`, `scale?`,
     `v_position`, `ac_coupled?`).
  6. `adjustables` — element reference, `edit_item`, `label`,
     `min_value`/`max_value`/`current_value`, optional `shared_slider` index.
- **Pretty-printer** `formatJson` (:711) + `isSimpleBlock` (:783) — single-pass
  indenter that keeps leaf objects/arrays on one line.
- **ID prefix table** (:621): `Resistor→R`, `Capacitor→C`, `Inductor→L`,
  `Transistor*→Q`, `Diode→D`, `LED→LED`, `Wire→W`, `Ground→GND`,
  `VoltageSource|DCVoltage→V`, `CurrentSource→I`, `OpAmp→U`, fallback: first
  3 chars of type name.
- **SI formatter** `formatWithUnit` (:667) — picks a prefix in
  `[f,p,n,u,m,(none),k,M,G,T]` whose range covers `abs(value)`; emits integer
  when exact, else ≤ 4 decimals with trailing zeros stripped.

#### `JsonCircuitImporter`
- **Type:** class implements `CircuitImporter`
- **File:** `src/main/java/com/lushprojects/circuitjs1/client/io/json/JsonCircuitImporter.java:37`
- **Fields:**
  | Field | Type | Purpose |
  |---|---|---|
  | `format` | `JsonCircuitFormat` | back-reference |
  | `importedElements` | `Map<String, CircuitElm>` | id → element, for cross-refs (scopes, adjustables, auto-wires) |
- **Flow** (`importCircuit` :48):
  1. Parse JSON strictly (`JSONParser.parseStrict`); abort on failure.
  2. `validateSchema` (:867): must have `schema.format == "circuitjs"` and
     `schema.version` starting with `"2."`.
  3. Unless `RC_RETAIN`, `resetCircuitState` (:151) — light-weight variant: no
     menu / bar resets (trusts `simulation` block to set them).
  4. `parseSimulation` — SI-aware via `UnitParser.parse` for
     `time_step`, `min_time_step`, `voltage_range`; numeric fallbacks.
  5. `parseElements` — `CircuitElementFactory.createFromJson`,
     `elm.setElementId(id)`, apply p1/p2 / bounds if present, `setPoints()`,
     `setCircuitDocument(doc)`, add to `simulator.elmList`.
  6. `createAutoWires` — for each `pin.connected_to`, build a `WireElm`
     between the pin location and the target pin location; skip duplicates via
     `"x1,y1-x2,y2"` keys.
  7. `parseScopes` with 3-level fallback if `element` ref is unknown (use
     first plot, else first imported element).
  8. `parseAdjustables` — builds the `Adjustable` entries but defers
     `current_value` (slider creation sets default).
  9. `adjustableManager.createSliders()`, `needAnalyze()`.
  10. Post-pass re-applies explicit `bounds` after analysis for exact geometry.
- **`canImport`** (:895): trims, must start with `{`, parses strictly, then
  runs `validateSchema`.

#### `CircuitElementFactory`
- **Type:** static factory
- **File:** `src/main/java/com/lushprojects/circuitjs1/client/io/json/CircuitElementFactory.java:40`
- **Fields:**
  | Field | Type | Purpose |
  |---|---|---|
  | `JSON_TYPE_TO_CONSTRUCTOR` | `static Map<String, ElementConstructor>` | name → lambda |
  | `initialized` | `static boolean` | lazy init guard |
- **`ElementConstructor`** functional interface (`CircuitDocument, x, y → CircuitElm`) at line 47.
- **Registration rules** (`init()` :65):
  - All ~150 entries are explicit `register("JsonName", Class::new)` calls.
  - Waveform voltage sources use a lambda that calls
    `VoltageElm.createWithWaveform(doc, x, y, WF_*)` (:88–104), one per
    waveform kind.
  - Multiple aliases are registered for historical rename compatibility:
    `MosfetN`/`NMosfet`, `MosfetP`/`PMosfet`, `JfetN`/`NJFET`, etc.
  - `VoltageSourceVar` is aliased to `WF_DC` (legacy variable waveform).
  - `init()` is idempotent (guarded by `initialized`). First call is triggered
    by `createFromJson`, not by a static-init block — `CirSim.init` explicitly
    notes this lazy policy (CirSim.java:204).
- **`createFromJson(jsonType, elementJson, document)`** (:314) creation rules:
  1. `ensureInitialized()` → lookup constructor; log + return null on miss.
  2. Derive `(x1, y1)` and `(x2, y2)` from `pins`:
     - Prefer `_startpoint` / `_endpoint` pseudo-pins.
     - Otherwise use the first two real pins.
     - If only one real pin, `(x2, y2) = (x1, y1)`; caller may patch from
       `bounds` for single-terminal elements like Rail/Ground.
  3. Call `constructor.create(document, x1, y1)`.
  4. `elm.setEndpoints(x1, y1, x2, y2)`.
  5. Restore from explicit `bounds` for single-terminal elements (:405) —
     opposite corner relative to (x1,y1).
  6. `applyJsonFlags(_flags)` (:436) → `applyJsonPinPositions(pinsMap)` for
     multi-terminal elements (:442) → `applyJsonProperties(propsMap)` (:454)
     → `applyJsonState(stateMap)` if present (:461) → `setDescription`
     (:469) → `finalizeJsonImport()` (:475).
  7. Re-apply explicit `bounds` + `setBbox` one more time post-finalise so
     export/import is idempotent (:483–510).

#### `UnitParser`
- **Type:** static utility
- **File:** `src/main/java/com/lushprojects/circuitjs1/client/io/json/UnitParser.java:34`
- **Fields:**
  | Field | Value | Purpose |
  |---|---|---|
  | `SI_PREFIXES` | `Map<String, Double>` | `f=1e-15 … T=1e12`, plus `""=1`, both `u` and `μ`, both `k` and `K` |
  | `UNIT_NAMES` | `String[]` | Known unit suffixes stripped before prefix lookup: `Ohm`, `Ω`, `ohm(s)`, `F`, `f`, `farad(s)`, `H`, `henry/henries`, `V`, `v`, `volt(s)`, `A`/`a`/`amp(s)`/`ampere(s)`, `W`/`w`/`watt(s)`, `Hz`/`hz`/`hertz`, `s`/`sec`/`second(s)` |
- **Invariants:** always returns a `double` (defaults to 0 on parse failure —
  callers that care about distinguishing 0 from failure must supply a default
  via `parseValue(value, defaultValue)` which flips to the default when the
  result is 0 and the input is not literally zero).

---

## Public Contracts

Grouped by sub-unit.

### `io-interfaces`

- `CircuitFormat` plugin contract — every format provides id / display name /
  extensions / mime / version plus two factory methods
  (`createExporter` / `createImporter`). A format is a pure descriptor; state
  lives on the exporter/importer instance.
- `CircuitFormatRegistry` lookup / registration API:
  - `register(format)` — idempotent upsert by id.
  - `unregister(id)`, `isRegistered(id)`.
  - `getById(id)`, `getDefault()` (text).
  - `getByExtension(filename)` — uses `getExtension(filename)` which
    preserves compound extensions like `.circuit.json` (prevDot within 10
    chars).
  - `detectFormat(data)` — probes importers with `canImport`; catches
    exceptions silently.
  - `detectFormatOrDefault(data)` — falls back to text.
  - `createExporter(id)` / `createImporter(id)` — convenience; return `null`
    if id absent.
  - `getAllFormats()` — iteration order matches registration order.
- `CircuitExporter` — `export(doc)` mandatory; optional
  `export(doc, includeState)` and `exportSelection(doc, selection)`.
- `CircuitImporter` — `importCircuit(data, doc, flags)` mandatory;
  `canImport(data)` used by the registry for auto-detection.

### `io-text`

- `TextCircuitExporter.export(CircuitDocument)` produces the classic Falstad
  dump documented in `docs/EXPORT_OLD.md` and (for the modern sub-dialect)
  `docs/EXPORT_CJS.md`.
- `exportSelection` writes only the selected elements (no options / scopes /
  hints) so copy/paste survives a round trip.
- `TextCircuitImporter` dispatches to:
  - `Scope.undump`, `CustomLogicModel.undumpModel`,
    `DiodeModel.undumpModel`, `TransistorModel.undumpModel`,
    `CustomCompositeModel.undumpModel`.
  - `CircuitElmCreator.createCe(document, typeId, …, tokenizer)` — the
    *actual* text-format element factory (root package, outside the io
    module). The io module is **not** self-contained for the text path.
- Private `readOptions` / `readHint` parse the options & hint lines.

### `io-json`

- `JsonCircuitExporter.export(doc)` / `export(doc, includeState)` /
  `exportSelection(doc, selection)` all produce schema-stamped documents.
  `exportSelection` writes only `schema` + `elements` (no simulation, nodes,
  scopes, or adjustables).
- `JsonCircuitImporter.importCircuit(data, doc, flags)` — uses
  `CircuitConst.RC_RETAIN` (duplicated constant — importers each reference
  the same int literal through different type names).
- `CircuitElementFactory`:
  - `createFromJson(jsonType, elementJson, document)` — main entry.
  - `getAllJsonTypeNames()` — list of known type strings (for diagnostics /
    future UI discovery).
  - `isKnownType(jsonType)`.
  - `jsonObjectToMap(JSONObject)` / `jsonValueToObject(JSONValue)` —
    **public static** helpers; reused by element classes that parse nested
    JSON in their `applyJsonProperties` implementations.
- `UnitParser`:
  - `parse(String)` — number with optional prefix+unit; returns 0 on failure.
  - `parseValue(Object)` / `parseValue(Object, double defaultValue)`.
  - `parseInt(Object [, int default])`, `parseBoolean(Object [, boolean default])`,
    `parseString(Object [, String default])` — generic type coercion used by
    element `applyJsonProperties` methods.

### UnitParser grammar (informal)

```
value    := [ws] number [ws] [unit]
number   := [ sign ] digit+ [ '.' digit+ ] [ ('e'|'E') [sign] digit+ ]
sign     := '+' | '-'
unit     := prefix? unit-name? | prefix
prefix   := 'f' | 'p' | 'n' | 'u' | 'μ' | 'm' | '' | 'k' | 'K' | 'M' | 'G' | 'T'
unit-name:= 'Ohm'|'Ω'|'ohm'|'ohms'|'F'|'f'|'farad'|'farads'|'H'|'henry'|'henries'
          | 'V'|'v'|'volt'|'volts'|'A'|'a'|'amp'|'amps'|'ampere'|'amperes'
          | 'W'|'w'|'watt'|'watts'|'Hz'|'hz'|'hertz'
          | 's'|'sec'|'second'|'seconds'
```

Parsing algorithm (`parse`, `findUnitStart`, `extractMultiplier`):
1. Trim whitespace; try `Double.parseDouble` shortcut (handles plain numbers).
2. Split at first non-numeric character (after optional sign, digits, `.`,
   exponent).
3. Strip the first matching `UNIT_NAMES` suffix from the tail (greedy, but
   only first match because of the `break` at line 181). Ambiguity:
   `"10 H"` → strips `"H"` (OK). `"10 mH"` → strips `"H"`, remainder `"m"`
   → prefix 1e-3 (OK). `"1 Hz"` is risky because `"z"` is not a suffix and
   `"Hz"` is checked before `"H"` in the list, so `"10 Hz"` → strips `"Hz"`,
   remainder empty → multiplier 1 (OK). `"10 nF"` → strips `"F"`, remainder
   `"n"` → 1e-9 (OK).
4. If the remainder exactly matches a prefix key, use its multiplier.
5. Otherwise use the multiplier of the first character if it is a known
   prefix; else default to 1.0.

There is **no stringify counterpart** in `UnitParser`. The round-trip formatter
is `JsonCircuitExporter.formatWithUnit(double, String)` (JsonCircuitExporter.java:667).

### CircuitElementFactory creation rules

1. **Registration is code-driven, not reflective.** Every type must be added
   to `init()` manually (`CircuitElementFactory.java:65–288`).
2. **Constructor signature is uniform.** All element classes expose a
   `(CircuitDocument, int x, int y)` constructor. Voltage sources deviate and
   are adapted by lambdas calling `VoltageElm.createWithWaveform`.
3. **Only a single `(x, y)` goes to the constructor.** The second endpoint is
   set via `setEndpoints` after construction. The factory intentionally does
   *not* rely on multi-arg constructors because the same element type may have
   different pin shapes per instance.
4. **Descriptive type names** differ from the single-character text-format
   dump type (e.g. `"Resistor"` vs. `'r'` / 114). JSON type names are chosen
   to be self-documenting and stable across UI renames.
5. **Aliases coexist** to support old exports after factory-side renames
   (`NMosfet`, `MosfetN`). The same constructor is used — the extra entry just
   adds a synonym.
6. **Properties, flags, state, pin positions, description** are applied in a
   fixed order after `setEndpoints`; `finalizeJsonImport()` is the last hook
   before `bounds` is re-applied.

---

## Validation Rules

- **Text `canImport`** (`TextCircuitImporter.java:83`): trimmed string starts
  with `$` or with a letter/digit (element tag). Does not verify structure.
  Result: misclassification is possible for non-circuit text that coincidentally
  starts with a letter — `detectFormat` probes text first because of LinkedHashMap
  order.
- **JSON `canImport`** (`JsonCircuitImporter.java:895`): trims, must start with
  `{`, strict parse succeeds, `validateSchema` passes.
- **JSON schema validation** (`validateSchema` :867): requires
  `schema.format == "circuitjs"` and `schema.version` prefix `"2."`. No
  semver range support; future `3.x` is rejected.
- **Parse error handling:**
  - Text importer wraps each line in try/catch and logs
    `"Exception while parsing: " + line` to `CirSim.console`
    (TextCircuitImporter.java:210). Other lines continue.
  - JSON importer logs to `CirSim.console` on malformed root, missing `type`,
    unknown element type (factory), and per-element application failures
    (bounds, pin positions, properties). It continues with the rest of the
    document.
- **Version compatibility:** text format version `"1.0"` is informational only
  (never checked at import). JSON insists on `2.*`.
- **Missing optional parameters** (text options line): `TextCircuitImporter.readOptions`
  wraps `minTimeStep` and `powerBar` in a try/catch to tolerate older dumps
  (line 363).

---

## State Transitions

### Text import session state

- Reset-or-retain branch in `importCircuit` (line 70). `resetCircuitState`
  clears error/stop state, elements, timings, menu flags, viewport sliders,
  voltage range, scopes.
- Parsing is streaming line-by-line; partial success allowed (errors logged).
- Finalisation (`finalizeCircuitLoading`, :374) runs once per import:
  slider creation, `needAnalyze`, optional recenter, model update for
  subcircuit mode, cache clears, slider-dialog resize.
- No error accumulation object — errors go to `CirSim.console`.

### JSON import session state

- Held on importer instance via `importedElements` map, rebuilt per
  `importCircuit`.
- Reset path is lighter than text (`resetCircuitState` :151): deletes
  elements, clears adjustables, resets scope count. Other state (menu flags,
  voltage range) is assumed to be overwritten by `parseSimulation`.
- Bounds are applied *twice* — once during element creation, once after
  `needAnalyze` — because analysis may rebuild element geometry. This is a
  deliberate idempotency guarantee for export/import round-trips.
- Scope plot restoration defers `setSpeed` until after plot/display/trigger
  restoration so plot buffers size correctly.
- Adjustable value restoration is **deferred-with-loss**: `current_value` is
  not re-applied because sliders don't exist yet; the UI slider takes its
  default on creation (comment at JsonCircuitImporter.java:812).
- Auto-wire generation may create `WireElm`s that were not present in the
  source document whenever `connected_to` references appear without
  coincident pin coordinates. Duplicates are suppressed by undirected
  `"x1,y1-x2,y2"` keys.

### Export session state

- `JsonCircuitExporter` resets `elementCounter` and `elementIds` at the start
  of every `export*` call (:73). `pinsByLocation` is built per call.
- `TextCircuitExporter` calls `clearDumpedFlags` on four model classes so
  each export is stateless regardless of prior dumps.

---

## Integration Points

### Depends on

**`io-interfaces` (core):**
- `com.lushprojects.circuitjs1.client.CircuitDocument` (imported by all
  three `io/*.java` files that reference it; the interface `CircuitFormat`
  itself is document-agnostic).
- `com.lushprojects.circuitjs1.client.element.CircuitElm` (only in
  `CircuitExporter.exportSelection`).

**`io-text`:**
- Root-package classes: `CircuitDocument`, `CircuitRenderer`,
  `CircuitSimulator`, `CirSim`, `ColorSettings`, `CustomCompositeModel`,
  `CustomLogicModel`, `DiodeModel`, `MenuManager`, `Scope`, `ScopeManager`,
  `TransistorModel`, `StringTokenizer`, `CircuitEditor`, `CircuitElmCreator`.
- `element/CircuitElm` plus `element/AudioInputElm`, `element/DataInputElm`
  (cache clears).
- `dialog/ControlsDialog` (for `timeStepToPosition`).

**`io-json`:**
- `com.google.gwt.json.client.*` (`JSONParser`, `JSONObject`, `JSONArray`,
  `JSONValue`, `JSONString`, `JSONNumber`, `JSONBoolean`, `JSONNull`).
- Root: `Adjustable`, `AdjustableManager`, `CheckboxMenuItem`,
  `CircuitConst` (for `RC_RETAIN`), `CircuitDocument`, `CircuitEditor`,
  `CircuitSimulator`, `CirSim`, `ColorSettings`, `MenuManager`, `Point`,
  `Scope`, `ScopeManager`, `ScopePlot`.
- `element.CircuitElm`, `element.WireElm` (auto-wire synthesis), **every**
  concrete `*Elm` registered in `CircuitElementFactory.init()`.
- `element.waveform.Waveform` (via `VoltageElm.createWithWaveform`
  lambdas).

### Used by (outside `io/*`)

Discovered via Grep on imports / call sites. Text and JSON callers are
virtually always routed through the registry rather than the concrete
classes:

| Caller | File | Usage |
|---|---|---|
| `ActionManager` | `client/ActionManager.java:552–583` | `dumpCircuit(formatId)`, `dumpCircuitWithState(formatId)`, `dumpCircuit()` — all via `CircuitFormatRegistry.getById` / `.getDefault()` |
| `CircuitLoader` | `client/CircuitLoader.java:42–71` | `readCircuit` with auto-detect (`detectFormatOrDefault`) or explicit `formatId` |
| `CirSim` | `client/CirSim.java:204` (comment) | Declares the lazy-init policy for `CircuitElementFactory` |
| `CircuitEditor`, `UndoManager`, `DocumentManager`, `LoadFile`, `ImportFromDropbox` | client/* | Call `circuitLoader.readCircuit(...)` or `actionManager.dumpCircuit(...)` (indirect) |
| `dialog/ExportAsJsonDialog` | :81 | `circuitDocument.circuitLoader.readCircuit(s1)` — round-trip test |
| `dialog/ExportAsTextDialog` | :76 | same — round-trip test |
| `dialog/ImportFromDropboxDialog`, `dialog/EditCompositeModelDialog` | — | call `readCircuit` |
| `element/CustomCompositeElm` | — | calls `readCircuit` with `RC_SUBCIRCUITS` for model inlining |

**`UnitParser` is used directly by element classes** (inside `applyJsonProperties`):
`OpAmpElm`, `CurrentElm`, `LampElm`, `GateElm`, `JfetElm`, `PotElm`, `RelayElm`,
`RelayCoilElm`, `RelayContactElm`, `TransformerElm`, `TappedTransformerElm`,
`CustomTransformerElm`, and the base `CircuitElm.applyJsonProperties` helper
(`CircuitElm.java:1902`). This is an intentional downstream dependency:
elements know about SI-unit strings, which means removing `UnitParser` from
`io/json/` would break ~13 element classes.

**`CircuitElementFactory` is used only by `JsonCircuitImporter`** (plus the
`CirSim.init` comment). The text path bypasses it and uses `CircuitElmCreator`
at the root package.

### External deps

- **GWT JSON API** (`com.google.gwt.json.client.*`) — only in `io/json/`.
- **Browser FileReader / Dropbox** — not used directly here; file-loading glue
  lives in `LoadFile`, `ImportFromDropbox`, and `ImportFromDropboxDialog` at
  the root package. The io module consumes strings, nothing else.
- No `java.io.*`, no `javax.*` — everything is pure string manipulation and
  framework objects so GWT can compile to JS.

### Cross-references to existing docs

- `docs/EXPORT_OLD.md` — authoritative spec for the legacy text dump format
  (Falstad original). Relevant lines in text importer/exporter map directly to
  tables in this doc.
- `docs/EXPORT_CJS.md` — authoritative spec for the JSON v2.0 format (in
  Ukrainian). Explicitly mirrors the structure produced by `JsonCircuitExporter`
  (`schema`, `simulation`, `nodes`, `elements`, `scopes`, `adjustables`,
  optional `state`). The export doc is **ahead of the code in places**: it
  lists properties like `schema.name`, `schema.description`, `schema.created`,
  `schema.author`, and `schema.include_state` that the current exporter does
  *not* emit. See "Issues / Questions" below.

---

## Format registration mechanism (SCC-B cycle)

The `io ↔ io/text` and `io ↔ io/json` cycles come from one piece of code: the
**static initialiser in `CircuitFormatRegistry`**.

```java
// CircuitFormatRegistry.java:40-44
static {
    // Register built-in formats
    register(new TextCircuitFormat());
    register(new JsonCircuitFormat());
}
```

Timeline:

1. First access to any `CircuitFormatRegistry` static member triggers the
   class initialiser.
2. The initialiser `new TextCircuitFormat()` — which imports back into the
   `io` package for `CircuitFormat`, `CircuitExporter`, `CircuitImporter`
   (text package files import from the parent, creating the 7-edge count
   in the dependency graph).
3. Same for `JsonCircuitFormat`.
4. After the static block the registry holds `{"text": text, "json": json}`
   in insertion order.

Consequences:

- **No explicit registration call is required**. `ActionManager.dumpCircuit`
  and `CircuitLoader.readCircuit` simply touch `CircuitFormatRegistry.getById`
  and both formats become available.
- `DEFAULT_FORMAT_ID = "text"` is a **string constant**, not a reference to
  the format instance. Default lookup resolves through the same map; removing
  text without adding a replacement would return `null` from `getDefault()`.
- Registration is **mutable at runtime** (`register` / `unregister` are
  public). Any caller can inject a third format — no use of this in-tree.
- Because the registry *imports* the concrete sub-packages, the SCC cycle is
  real at package level. It is the canonical textbook plugin-registration
  cycle and cannot be eliminated without a service-locator or
  reflective-discovery layer that GWT's compiled-time mode does not naturally
  support.
- `CircuitElementFactory.init()` is deliberately *not* called here. Its lazy
  initialisation is guarded by `ensureInitialized()` and first triggered on
  import, as noted in `CirSim.java:204`.

---

## Existing Documentation

- **`docs/EXPORT_OLD.md`** (English, 600+ lines): line-by-line element
  record grammar. First line = `$` options header with bitmask. Each element
  line = `DumpType x y x2 y2 flags [extra] [# desc]`. This is the ground
  truth for `TextCircuitExporter` and `TextCircuitImporter`.
- **`docs/EXPORT_CJS.md`** (Ukrainian, ~1500 lines): authoritative JSON v2.0
  spec — schema block, simulation block, positioning rules (pin coordinates
  determine rotation), `bounds` variants (AABB / circle / centered rotated
  rect), per-element examples, pin structure, scopes, adjustables, and the
  `state` section for simulation-snapshot round-trips. The current exporter
  implements a strict subset (no `schema.name|description|created|author|include_state`
  metadata, no rotation-aware bounds, no circle bounds).
- **In-source javadoc:** every interface and class in `io/*` has a header
  comment stating intent. `TextCircuitExporter` and `TextCircuitImporter`
  include a numbered "format structure" description at the class level.
  `JsonCircuitExporter` documents each sub-section in comments at the section
  call-sites inside `export`.

---

## Issues / Questions

1. **Versioning story.**
   - Text format version `"1.0"` is emitted nowhere (there is no
     version line). `TextCircuitImporter` never checks the version.
   - JSON requires `schema.version` `"2.*"` — no semver comparator. Any
     future `3.x` rejects without a migration.
   - No converter from text → JSON or JSON → text exists in-tree;
     cross-format interop goes through a full import + re-export.

2. **Format detection order.**
   - `detectFormat` iterates LinkedHashMap order: text first (because it was
     registered first in the static block). The text `canImport` is
     permissive — it accepts any string starting with `$` / letter / digit.
     If a JSON blob accidentally began with whitespace + a letter, text
     would win. Practically JSON blobs start with `{` and trigger the `false`
     branch in text's `canImport` via the initial `$`/letter/digit check —
     so the fall-through ordering is benign but brittle.
   - `CircuitLoader.readCircuit` uses `detectFormatOrDefault`, i.e. falls
     back to text. `CircuitFormatRegistry.getByExtension` also falls back to
     text when the extension is unknown.

3. **Schema drift between exporter and factory.**
   - Factory registers `"VoltageSourceDC/AC/Square/Triangle/Sawtooth/Pulse/Noise/Var"`
     as aliases mapped through `VoltageElm.createWithWaveform`. The exporter
     uses `elm.getJsonTypeName()` — whatever each element returns. A mismatch
     between the JSON type name emitted and the factory key makes the element
     un-importable (logs "Unknown element type").
   - Similarly, MOSFET and JFET registrations include `MosfetN` and
     `NMosfet` pairs; whichever alias the exporter chooses is fine, but any
     new element type must be added to `init()` **and** emit the same name
     from `getJsonTypeName()`.
   - `EXPORT_CJS.md` advertises types such as `PolarizedCapacitor`,
     `DCVoltageSource`, `ACVoltageSource`, `NMOSFET`, `PMOSFET` — none of which
     are registered (factory uses `PolarCapacitor`, `DCVoltage`, `ACVoltage`,
     `MosfetN`/`NMosfet`, `MosfetP`/`PMosfet`). The doc describes the
     *intended* future naming; the implementation uses internal shorter names.

4. **Lossy round-trip for adjustables.**
   - `current_value` is imported into the JSON but not applied to the created
     slider (see comment in `JsonCircuitImporter.java:812`). After import, the
     slider takes the default value the element was created with, not the
     saved one.

5. **Auto-wire generation is not idempotent at export.**
   - `JsonCircuitImporter.createAutoWires` may create `WireElm`s that were
     not present in the original circuit (when two elements share a pin
     location via `connected_to` but the user's circuit had no explicit
     wire). Re-exporting the document will then emit those synthetic wires.

6. **`UnitParser` unit ambiguity.**
   - `"H"` (henry) is stripped before any prefix probe. `"1 H"` → 1.0 OK.
     `"1 mH"` → strips `"H"`, remainder `"m"` → 1e-3 OK. But `"1 Hz"` →
     strips `"Hz"` (before `"H"`? — `"Hz"` is at index 9 of UNIT_NAMES, `"H"`
     at index 8, so **`"H"` is tested first** and `"1 Hz"` → strips `"H"`,
     remainder `"z"` → no prefix match → returns value 1, *losing* the Hz
     implication. This is benign because the caller already knows the target
     unit, but the parser's unit-stripping is strictly positional.

7. **Duplicated flag constant.** `CircuitConst.RC_RETAIN` is consulted by
   the JSON importer (:71), while `CircuitImporter.RC_RETAIN` is consulted by
   the text importer (:71). Both are the integer `1` — there is no shared
   source-of-truth declaration.

8. **`CircuitElementFactory` depends on every concrete element.** Adding a
   new element requires editing a file in the `io/` tree. This is a known
   plugin-registration trade-off. A SPI-style mechanism
   (`ServiceLoader` / GWT's deferred binding) would eliminate the edit but
   adds compile-time complexity.

---

## Suggested Concept Boundaries

Recommended: **one composite "io-framework" concept with three sub-concept
sections**. Reasons:

- The three packages share one domain boundary (circuit serialisation) and
  one public entry point (`CircuitFormatRegistry`). Splitting into three
  concepts would force every dependent doc to triple-reference the same
  contract.
- The SCC-B cycle is an implementation detail of the registration pattern —
  the conceptual dependency (`io/text` and `io/json` implement the `io`
  contract) is already acyclic.
- Legacy text and modern JSON are *parallel* variants of the same concept;
  having them in one concept doc preserves the symmetry and lets cross-format
  invariants (version policy, `canImport` order, round-trip guarantees) live
  in one place.

Sub-sections inside the single concept:

1. **Format plugin contract** (`CircuitFormat`, `CircuitFormatRegistry`,
   `CircuitExporter`, `CircuitImporter`, the `RC_*` flag family, the
   static-init registration pattern, auto-detection rules). This is the
   stable surface.
2. **Legacy text dialect** (`io/text`) — cross-references `EXPORT_OLD.md`
   as the format spec and lists the root-package dependencies that make it
   non-self-contained (notably `CircuitElmCreator` for elements and the
   four `*Model.undumpModel` entry points).
3. **JSON v2.0 dialect** (`io/json`) — cross-references `EXPORT_CJS.md`,
   documents the `CircuitElementFactory` registry, the `UnitParser` grammar,
   and the state/bounds idempotency dance.

Alternative (not recommended): four concepts per the queue.yaml sub-unit
split. This would over-fragment: `io-orchestrators` has no classes of its
own (`CircuitExporter`/`CircuitImporter` are already in `io-interfaces`),
and the two format concepts would each need a "how the framework calls me"
section duplicating content from the framework concept.
