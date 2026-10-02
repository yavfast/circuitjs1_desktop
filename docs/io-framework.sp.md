# IO Framework — Specification  {#SP_IOF}

> **Code:** SP_IOF
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-10-02
>
> **Concept:** [C_IOF](./io-framework.concept.md)
> **Depends on specs:** [SP_ELB](./element-base.sp.md), [SP_UTL](./util-locale-log.sp.md), [SP_MDS](./math-dsp.sp.md)
> **Used by specs:** simulator-persistence, dialog-export, dialog-import
> **Plan:** [io-framework.plan.md](./io-framework.plan.md)
>
> Defines the contract for circuit serialisation: the `CircuitFormat` plugin interface, the static registry, the two concrete dialects (Falstad text, JSON v2.0), the element-creation factory, and the SI-aware `UnitParser`. Cross-references [EXPORT_OLD.md](./EXPORT_OLD.md) (text grammar) and [EXPORT_CJS.md](./EXPORT_CJS.md) (JSON v2.0 schema).

> Backing analysis: [io-framework.md](../.dev_flow/onboard/analysis/io-framework.md)

## 01. Data Structures  {#SP_IOF_01}

> Implements: [C_IOF_02](./io-framework.concept.md#C_IOF_02)

### 01_01. CircuitFormat  {#SP_IOF_01_01}

Descriptor for a serialisation dialect. Pure value — no instance state.

Fields (abstract-method return values):
| Field | Type | Required | Default | Constraints | Description |
|-------|------|----------|---------|-------------|-------------|
| id | String | yes | — | unique within registry | Registry key |
| name | String | yes | — | human-readable | Display name |
| fileExtensions | String[] | yes | — | non-null; each starts with `.` | Associated extensions |
| mimeType | String | yes | — | RFC-compliant | MIME type |
| version | String | yes | — | informational (text) / enforced prefix `"2."` (JSON) | Format version |
| defaultExtension | String | derived | first of `fileExtensions` | — | Convenience |

Invariants:
- `id` is unique within `CircuitFormatRegistry`.
- `supportsExtension(x)` tolerates presence/absence of the leading dot.
- `createExporter()` and `createImporter()` return fresh instances each call (exporter/importer carry per-call state).

### 01_02. CircuitFormatRegistry entry  {#SP_IOF_01_02}

Internal registry shape: `{ id -> CircuitFormat }` backed by `LinkedHashMap` so iteration order equals registration order.

Fields:
| Field | Type | Required | Default | Constraints | Description |
|-------|------|----------|---------|-------------|-------------|
| formats | static Map<String, CircuitFormat> | yes | `{}` (populated by static block) | LinkedHashMap | Id -> format |
| DEFAULT_FORMAT_ID | static String | yes | `"text"` | must match a registered id or `getDefault()` returns null | Fallback format id |

Invariants:
- Static initialiser registers `TextCircuitFormat` first, `JsonCircuitFormat` second.
- All API is static; no instance exists.
- `getByExtension(filename)` preserves compound extensions like `.circuit.json` within a 10-char window (`prevDot` scan).

### 01_03. Import flags (bitmask)  {#SP_IOF_01_03}

| Name | Value | Meaning |
|------|------:|---------|
| RC_RETAIN | 1 | Keep current elements/state (paste / merge) |
| RC_NO_CENTER | 2 | Do not recentre viewport after load |
| RC_SUBCIRCUITS | 4 | Parse only composite-model (`.`) definitions |
| RC_KEEP_TITLE | 8 | Preserve document title |

Single source of truth: `CircuitConst.RC_*`; `CircuitImporter.RC_*` are aliases of it. (Until 2026-09-30 `CircuitImporter` declared its own values with `RC_SUBCIRCUITS`/`RC_NO_CENTER` swapped, so undo/redo loaded an empty circuit and paste added nothing — audit PL_AUDIT_20260930_173830 ITEM-01.)

### 01_04. JSON document schema (v2.0)  {#SP_IOF_01_04}

Root object shape (written by `JsonCircuitExporter`, accepted by `JsonCircuitImporter`):

| Key | Required | Description |
|-----|----------|-------------|
| schema | yes | `{ format: "circuitjs", version: "2.0" }` — gate checked by `validateSchema` |
| simulation | yes | time steps (SI strings), display booleans, voltage range string, `current_speed`, `power_brightness`, `auto_time_step` (always written since 2026-10-02; the importer keeps the target document's setting when an older file lacks it) |
| elements | yes | `{ "<element-id>": ElementEntry, ... }` — key = the element's registry ID `CircuitElm.getElementId()` ([SP_AGA_03_02](./agent-api.sp.md#SP_AGA_03_02)) |
| nodes | optional | `{ "N1": { connections: ["<id>.<pin>", ...] }, ... }` for `(x,y)` where >=3 pins coincide |
| scopes | optional | list of `{ display-flags, plot_mode, trigger?, history?, scales, manual_scale?, plots[] }` |
| adjustables | optional | list of `{ element, edit_item, label, min_value, max_value, current_value, shared_slider? }` |
| state | conditional | emitted only when `includeState=true` |

Element entry (`ElementEntry`):
| Key | Required | Description |
|-----|----------|-------------|
| type | yes | JSON type name (factory key: `Resistor`, `Capacitor`, `PolarCapacitor`, `DCVoltage`, `ACVoltage`, `MosfetN`/`NMosfet`, ...) |
| description | optional | free-text description |
| bounds | optional | AABB or variant from EXPORT_CJS.md; single-terminal elements require it for the second endpoint |
| properties | optional | key -> SI-string or scalar |
| pins | optional | `{ "p1": {x,y,connected_to?}, ... }`; pseudo-pins `_startpoint` / `_endpoint` prioritised |
| _flags | optional | integer bitmask passed to `applyJsonFlags` |
| state | optional | runtime state snapshot (includeState only) |

Element keys: the exporter writes each element's registry ID (`<prefix><n>`, counted per prefix within the document, e.g. `R1, R2, C1, W1`); it does not generate IDs itself. A repeated ID (a defect) is written as `<id>_2`, `<id>_3`, … with a `[WARN] JSON export: repeated element ID` console line, so no element is dropped. Prefixes come from `CircuitElm.getIdPrefix()`:

| Element class | Prefix |
|---------------|--------|
| ResistorElm | R |
| CapacitorElm | C |
| InductorElm | L |
| DiodeElm / ZenerElm | D / Z |
| LEDElm | LED |
| WireElm | W |
| GroundElm | GND |
| VoltageElm (and subclasses) | V |
| CurrentElm | I |
| OpAmpElm | U |
| MosfetElm / JfetElm | M |
| SwitchElm | SW |
| RelayElm | K |
| TransformerElm | T |
| other (incl. transistors → `TRA`) | first three letters of the JSON type name, digits dropped, upper-cased (`E` if none) |

### 01_05. Text format line grammar  {#SP_IOF_01_05}

Per [EXPORT_OLD.md](./EXPORT_OLD.md) and [EXPORT_CJS.md](./EXPORT_CJS.md).

Document layout:
1. Options header: `$ flags maxTimeStep iterCount currentBar voltageRange powerBar minTimeStep`.
2. Zero or more element records: `DumpType x y x2 y2 flags [extra tokens] [# description]`.
3. Zero or more scope lines (`o ...`).
4. Zero or more adjustable lines (`38 ...`).
5. Zero or more model definitions (`!`, `34`, `32`, `.`).
6. Optional hint line (`h hintType hintItem1 hintItem2`).

Line dispatch (`TextCircuitImporter.processCircuitLine`):
| Token | Handler |
|-------|---------|
| `o` | `Scope.undump` |
| `h` | `readHint` |
| `$` | `readOptions` |
| `!` | `CustomLogicModel.undumpModel` |
| `%`, `?`, `B` | ignored (afilter legacy) |
| `34` | `DiodeModel.undumpModel` |
| `32` | `TransistorModel.undumpModel` |
| `38` | `AdjustableManager.addAdjustable` |
| `.` | `CustomCompositeModel.undumpModel` |
| other | `CircuitElmCreator.createCe` |

`$` max step: a value that does not parse to a positive finite number (garbled, ≤ 0, NaN, infinite) falls back to 5 µs with a console line. The time-step bar is moved to the nearest position with `setValueWithoutCommand`, so the file's max step is kept exactly (no re-quantisation to the bar table, [SP_AGA_06_01](./agent-api.sp.md#SP_AGA_06_01) item 18). Lines are split on single line breaks (`\r\n|\n|\r`, blank lines skipped), so reported line numbers match the source text.

### 01_06. UnitParser grammar  {#SP_IOF_01_06}

Informal EBNF:
```
value     := [ws] number [ws] [unit]
number    := [ sign ] digit+ [ '.' digit+ ] [ ('e'|'E') [sign] digit+ ]
sign      := '+' | '-'
unit      := prefix? unit-name? | prefix
prefix    := 'f' | 'p' | 'n' | 'u' | 'μ' | 'm' | '' | 'k' | 'K' | 'M' | 'G' | 'T'
unit-name := 'Ohm'|'Ω'|'ohm'|'ohms'|'F'|'f'|'farad'|'farads'
           | 'H'|'henry'|'henries'
           | 'V'|'v'|'volt'|'volts'
           | 'A'|'a'|'amp'|'amps'|'ampere'|'amperes'
           | 'W'|'w'|'watt'|'watts'
           | 'Hz'|'hz'|'hertz'
           | 's'|'sec'|'second'|'seconds'
```

SI prefix multipliers: `f=1e-15, p=1e-12, n=1e-9, u=μ=1e-6, m=1e-3, (none)=1, k=K=1e3, M=1e6, G=1e9, T=1e12`.

Invariant: `parse(String)` returns `double` and defaults to `0.0` on parse failure (silent). Use `parseValue(value, defaultValue)` when 0 must be distinguished from failure — it flips to default when result is 0 and input is not literally zero.

### 01_07. CircuitElementFactory registry entry  {#SP_IOF_01_07}

Shape: `JSON_TYPE_TO_CONSTRUCTOR: Map<String, ElementConstructor>` where `ElementConstructor = (CircuitDocument, int x, int y) -> CircuitElm`.

Invariants:
- `init()` is idempotent (guarded by `initialized` boolean); first call triggered by `createFromJson` (lazy; not from a static block).
- Every registered element class exposes a `(CircuitDocument, int x, int y)` constructor. Waveform voltage sources use lambdas that call `VoltageElm.createWithWaveform(doc, x, y, WF_*)`.
- Multiple aliases may map to the same constructor (historical rename compat).
- Factory registry is code-driven (manual `register(...)` call in `init()`); adding an element requires editing `io/json/CircuitElementFactory.java`.
- `XNORGate` is not registered (there is no XNOR element; mapping it to `XorGateElm` imported an XNOR as a plain XOR); such an entry is reported as an unknown type.
- `createDefault(jsonType, doc, x, y)` creates a default element with its placement constructor (both endpoints at `(x, y)`, no JSON properties), as the editor does before dragging; it is not added to any list; null for an unknown type or a failing constructor.

## 02. Contracts  {#SP_IOF_02}

### 02_01. registerFormat  {#SP_IOF_02_01}

Purpose: insert or replace a `CircuitFormat` entry in the registry. Invoked by the static initialiser and available at runtime for third-party formats.

Input:
| Parameter | Type | Required | Constraints |
|-----------|------|----------|-------------|
| format | CircuitFormat | yes | non-null; `format.getId()` non-empty |

Output: void (idempotent — existing entry with same id is overwritten).

Errors: none raised; caller errors (null, empty id) go unchecked — flagged.

Processing logic:
    FUNCTION register(format):
        formats.put(format.getId(), format)

### 02_02. detectFormat  {#SP_IOF_02_02}

Purpose: choose a format by sniffing the payload content.

Input:
| Parameter | Type | Required | Constraints |
|-----------|------|----------|-------------|
| data | String | yes | may be empty |

Output:
| Field | Type | Description |
|-------|------|-------------|
| format | CircuitFormat | first format whose `canImport(data)` returns true; `null` if none |

Errors: exceptions thrown by individual `canImport` calls are caught and treated as `false` (silent).

Processing logic:
    FUNCTION detectFormat(data):
        FOR EACH format IN formats.values():      // insertion order: text, json
            TRY:
                importer = format.createImporter()
                IF importer.canImport(data): RETURN format
            CATCH any: CONTINUE
        RETURN null

### 02_03. export  {#SP_IOF_02_03}

Purpose: serialise a `CircuitDocument` to a string using a chosen format.

Input:
| Parameter | Type | Required | Constraints |
|-----------|------|----------|-------------|
| doc | CircuitDocument | yes | non-null |
| includeState | boolean | no (default false) | only JSON exporter honours it |

Output:
| Field | Type | Description |
|-------|------|-------------|
| data | String | format-specific payload |

Errors:
| Code | Condition | Guidance |
|------|-----------|----------|
| EXPORT_FAIL | upstream element `dumpElm` throws | text exporter does not catch; log bubbles to caller |

Processing logic (text): reset `clearDumpedFlags` on model registries; emit options line; for each element emit `dumpModel` (if any) then `dumpElm`; emit scopes; emit `AdjustableManager.dump`; emit hint if active.

Processing logic (JSON): reset `elementIds` and `usedIds`; key each element by `elm.getElementId()`, suffixing a repeated ID `_2`, `_3`, … with a console warning; build `pinsByLocation`; emit `schema`, `simulation` (`auto_time_step` always written, `true` or `false`), `elements`, `nodes` (coincidence >=3), `scopes`, `adjustables`; optionally emit `state`; pretty-print via `formatJson` / `isSimpleBlock`. `exportSimulation(doc)` returns the `simulation` object alone.

### 02_04. importCircuit  {#SP_IOF_02_04}

Purpose: mutate a `CircuitDocument` from a string payload.

Input:
| Parameter | Type | Required | Constraints |
|-----------|------|----------|-------------|
| data | String | yes | — |
| doc | CircuitDocument | yes | non-null |
| flags | int | no (default 0) | OR of RC_* |
| report | ImportReport | no (null) | overload `importCircuit(data, doc, flags, report)`; null = do not collect |

Output: void; errors and warnings are logged to `CirSim.console` and, when a report is passed, added to it as `{code, severity, message, line | key}` ([SP_AGA_03_04](./agent-api.sp.md#SP_AGA_03_04)). The interface default ignores the report; the text and JSON importers override it. User loads pass none.

Errors:
| Code | Condition | Report code (severity) | Guidance |
|------|-----------|------------------------|----------|
| SCHEMA_REJECT (JSON) | empty data, root not an object, `schema.format != "circuitjs"` or `version` not `"2.*"` | `import_schema_invalid` (ERROR) | abort import; document untouched beyond any reset already performed |
| JSON_PARSE_FAIL | `JSONParser.parseStrict` throws | `import_schema_invalid` (ERROR) | abort import |
| UNKNOWN_ELEMENT | factory returns null for `type`; JSON entry not an object or without `type`; text line of unknown type | `import_element_skipped` (ERROR) | log + skip element |
| ELEMENT_FAIL | an exception while loading one JSON element, or later in the JSON import | `import_element_skipped` (ERROR) | log + skip element / abort |
| TEXT_LINE_FAIL | any exception inside `processCircuitLine` | `import_element_skipped` (ERROR, at the line) | log `"Exception while parsing: " + line`; continue |
| WIRE_TARGET_MISSING (JSON) | `connected_to` names no element or no pin | `import_wire_skipped` (WARNING) | skip that auto-wire |
| SCOPE_LIMIT | more scopes than `getMaxScopes()` | `scope_limit` (WARNING) | ignore the rest |
| SETTING_INVALID (JSON) | `time_step`, `min_time_step` or `voltage_range` not positive | `import_setting_invalid` (WARNING) | keep the default |
| GEOMETRY_ADJUSTED (JSON) | single-post element without `_endpoint` takes its end point from `bounds`; `p1`/`p2` override the pin geometry | `import_geometry_adjusted` (WARNING) | apply and continue |
| IDS_REGENERATED | invalid or repeated element ID replaced, or undo-restore count mismatch (`settleElementIds`) | `ids_regenerated` (WARNING) | generated ID used |
| SILENT_UNIT_FAIL | `UnitParser.parse` cannot resolve | — | returns `0.0` — caller must use default-aware `parseValue` to detect |

A text import with a report also records one model-catalogue restorer before each `!`, `34`, `32`, `.` line; a caller that rejects the import (`report.hasErrors()`) runs `report.restoreModels()` (newest change first) so the session catalogues are as before ([SP_AGA_06_01](./agent-api.sp.md#SP_AGA_06_01) item 15).

Processing logic (text):
    FUNCTION importCircuit(data, doc, flags):
        IF NOT (flags & RC_RETAIN): resetCircuitState()     // ImportLifecycle, see SP_IOF_04_01
        parseCircuitLines(data, flags)                      // split on single line breaks; line numbers for the report
        finalizeCircuitLoading(flags, report)               // settleElementIds first

Processing logic (JSON):
    FUNCTION importCircuit(data, doc, flags):
        root = JSONParser.parseStrict(data)
        IF NOT validateSchema(root): RETURN
        IF NOT (flags & RC_RETAIN): resetCircuitState()     // shared ImportLifecycle reset
        IF flags & RC_SUBCIRCUITS: RETURN                   // JSON carries no model definitions
        IF NOT (flags & RC_RETAIN): parseSimulation(root.simulation)   // invalid values -> import_setting_invalid
        parseElements(root.elements)                        // via CircuitElementFactory; keys become IDs only without RC_RETAIN
        createAutoWires(root.elements)                      // synthesise WireElm by connected_to
        parseScopes(root.scopes)
        parseAdjustables(root.adjustables)                  // current_value NOT applied (flagged)
        adjustableManager.createSliders()
        simulator.needAnalyze()
        reapplyBounds(root.elements)                        // idempotency pass after analysis

### 02_05. canImport  {#SP_IOF_02_05}

Purpose: cheap content sniff used by `detectFormat`.

Input: `data: String`.

Output: `boolean`.

Processing logic (text): `trim(data)` starts with `$` OR a letter OR a digit.

Processing logic (JSON): `trim(data)` starts with `{`, strict-parses, passes `validateSchema`.

### 02_06. CircuitElementFactory.createFromJson  {#SP_IOF_02_06}

Purpose: construct a concrete `CircuitElm` from a JSON element entry.

Input:
| Parameter | Type | Required | Constraints |
|-----------|------|----------|-------------|
| jsonType | String | yes | must be a registered key |
| elementJson | JSONObject | yes | shape per SP_IOF_01_04 |
| document | CircuitDocument | yes | target doc |

Output: `CircuitElm` (or `null` if type unknown).

Processing logic:
    FUNCTION createFromJson(jsonType, elementJson, document):
        ensureInitialized()
        ctor = JSON_TYPE_TO_CONSTRUCTOR.get(jsonType)
        IF ctor == null: log "Unknown type"; RETURN null
        (x1,y1,x2,y2) = derivePinsFromJson(elementJson)   // _startpoint/_endpoint preferred
        elm = ctor.create(document, x1, y1)
        elm.setEndpoints(x1, y1, x2, y2)
        IF bounds present AND single-terminal: patch (x2,y2) from bounds.oppositeCorner
        applyJsonFlags(elm, elementJson._flags)
        applyJsonPinPositions(elm, elementJson.pins)       // multi-terminal only
        applyJsonProperties(elm, elementJson.properties)
        IF elementJson.state: applyJsonState(elm, elementJson.state)
        elm.setDescription(elementJson.description)
        elm.finalizeJsonImport()
        IF bounds present: re-apply bounds + setBbox        // idempotency
        RETURN elm

## 03. Validation Rules  {#SP_IOF_03}

### 03_01. Input Validation  {#SP_IOF_03_01}

- Text `canImport`: trimmed data starts with `$` / letter / digit. Structural validity is NOT verified — misclassification possible for arbitrary text.
- JSON `canImport`: trimmed starts with `{`, `JSONParser.parseStrict` succeeds, `schema.format == "circuitjs"`, `schema.version` starts with `"2."`.
- No semver comparator; `3.x` is rejected outright.
- `UnitParser.parse` treats unparseable input as `0.0` (silent). Callers that care must use `parseValue(obj, defaultValue)`.
- Text importer wraps each line in try/catch; per-line parse failures are logged but do not abort the document.
- JSON importer logs and continues on: malformed root, missing element `type`, unknown element type, bounds / pin / property application errors.
- JSON `schema.version` check does not support ranges — future `3.x` bump requires code change.

## 04. State Transitions  {#SP_IOF_04}

### 04_01. Lifecycle  {#SP_IOF_04_01}

Framework objects are stateless across calls; per-export/per-import objects carry transient state. The registry is static and initialised exactly once.

State diagram (registry):
    [unloaded] --class-init()--> [formats={text, json}]     // no further transitions expected

`resetCircuitState` (both formats, only without `RC_RETAIN`), in order: unless an undo/redo restore or agent origin is running, end the document's agent run and seal its open agent transaction ([SP_AGA_04_01](./agent-api.sp.md#SP_AGA_04_01)); clear errors and stop state; delete the elements; unless an undo/redo restore is running, reset the element ID counters (new content lifetime) and clear the open marks; reset adjustables, time steps, menu flags, bars, voltage range and scope count. `finalizeCircuitLoading` first calls `settleElementIds` (restored and supplied IDs raise their counters, invalid or repeated IDs are dropped, the rest are generated in element order; `ids_regenerated` warnings logged and reported), then sliders, `needAnalyze`, centring unless `RC_NO_CENTER`, models in subcircuit mode, cache clears.

State diagram (JSON import session):
    [idle] --parseStrict()--> [parsed]
          --validateSchema()--> [schema-ok]
          --resetCircuitState?()--> [empty-or-retained]
          --parseElements()--> [elements-created]
          --createAutoWires()--> [wired]
          --parseScopes/parseAdjustables()--> [ui-restored]
          --needAnalyze()--> [analysed]
          --reapplyBounds()--> [finalised]

Transition rules:
| From | To | Condition | Side effects |
|------|----|-----------|-------------|
| idle | parsed | strict parse succeeds | none |
| parsed | schema-ok | `schema.format == "circuitjs"` AND `version` prefix `2.` | none |
| schema-ok | empty-or-retained | `flags & RC_RETAIN` == 0 | delete elements, clear adjustables, reset scope count |
| * | aborted | parse or schema fails | log to `CirSim.console`; doc may be partially reset |

Text import session transitions mirror JSON except: no schema stage; both use the same `resetCircuitState` and `finalizeCircuitLoading` (above); the JSON importer defers centring until after its bounds pass.

## 05. Verification Criteria  {#SP_IOF_05}

### 05_01. Functional Expectations  {#SP_IOF_05_01}

| Contract | Scenario | Input | Expected outcome |
|----------|----------|-------|------------------|
| register | idempotent upsert | new format with existing id | replaces map entry; no exception |
| detectFormat | JSON payload | `{"schema":{"format":"circuitjs","version":"2.0"},...}` | returns `JsonCircuitFormat` |
| detectFormat | Falstad dump | `$ 1 0.000005 ...` | returns `TextCircuitFormat` |
| detectFormat | garbage | empty string | returns `null` |
| export | JSON happy path | doc with 1 resistor | schema-stamped document, `elements.R1.type == "Resistor"` |
| importCircuit | JSON round-trip | exporter output | doc geometry + element count preserved |
| importCircuit | JSON schema reject | `schema.version = "3.0"` | logs; doc unchanged past reset |
| importCircuit | Text unknown line | `"QQQ unrelated"` | logs exception; other lines succeed |
| UnitParser.parse | `"10 kOhm"` | — | `10000.0` |
| UnitParser.parse | `"1 mH"` | — | `0.001` |
| UnitParser.parse | garbage | `"abc"` | `0.0` |
| createFromJson | known type | `{"type":"Resistor",...}` | `ResistorElm` instance |
| createFromJson | unknown type | `{"type":"Flux"}` | `null`, log |

### 05_02. Invariant Checks  {#SP_IOF_05_02}

| Invariant | Verification method |
|-----------|---------------------|
| Registry iteration = insertion order | build map; assert `getAllFormats()` order |
| `DEFAULT_FORMAT_ID` resolves | assert `getDefault() == getById("text")` |
| JSON export -> import is idempotent on geometry | run export twice across an import; compare element bounds |
| `CircuitElementFactory.init()` runs at most once | spy on `initialized` flag; second call is no-op |
| Every registered JSON type has a `(doc,x,y)` constructor | compile-time (method references) |

### 05_03. Integration Scenarios  {#SP_IOF_05_03}

| Scenario | Preconditions | Steps | Expected result |
|----------|--------------|-------|-----------------|
| Save -> Load | non-empty doc | ActionManager.dumpCircuit -> CircuitLoader.readCircuit | semantic equivalence (modulo auto-wire synthesis) |
| Paste selection | doc with prior state | exportSelection -> importCircuit(flags=RC_RETAIN) | existing elements retained; pasted elements appended |
| Subcircuit model inline | subcircuit dump | importCircuit(flags=RC_SUBCIRCUITS) | only `.` lines parsed; other lines ignored |
| Dropbox import | .json file | readCircuit with auto-detect | JSON importer selected |

### 05_04. Edge Cases and Boundaries  {#SP_IOF_05_04}

| Case | Input | Expected behavior |
|------|-------|-------------------|
| Extension with leading dot | `.json` | registered format matches |
| Compound extension | `file.circuit.json` | `getByExtension` matches `.circuit.json` within 10-char window |
| Adjustable `current_value` | present in JSON | read but NOT applied — slider takes default (FLAGGED) |
| `connected_to` without coincident pins | two pins refer by name | `createAutoWires` synthesises `WireElm` (non-idempotent at export) |
| `"10 Hz"` UnitParser | — | strips `"H"` first, remainder `"z"` no prefix, multiplier 1.0 (positional) |
| Future `schema.version = "3.0"` | JSON | rejected (no semver) |
| Text options line missing tail | pre-modern dump | try/catch on `minTimeStep`/`powerBar` tolerates |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version derived from .dev_flow/onboard/analysis/io-framework.md |
| 2026-10-02 | PL_AGA Phase 10 propagate: JSON element keys = registry IDs (per-prefix, duplicates suffixed) and `getIdPrefix` table; `auto_time_step` always written, `exportSimulation`; `importCircuit` report overload with report-code column; JSON keys as IDs only without `RC_RETAIN`; 5 µs max-step fallback, time-step bar without its command, single-line-break split; `XNORGate` unregistered, `createDefault`; shared `resetCircuitState` steps (seal, ID reset, open marks) and `settleElementIds` in finalize; model-catalogue restore. |
