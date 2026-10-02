# IO Framework  {#C_IOF}

> **Code:** C_IOF
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-10-02
> **Author:** OnboardDocGen
>
> **Depends on:** [C_ELB](./element-base.concept.md) (element types), [C_UTL](./util-locale-log.concept.md), [C_MDS](./math-dsp.concept.md) (StringTokenizer)
> **Used by:** simulator persistence (ActionManager, CircuitLoader), dialog-export / dialog-import UI, CustomCompositeElm (subcircuit inlining)
> **Spike:** —
> **Specification:** [SP_IOF](./io-framework.sp.md)
> **Plan:** [io-framework.plan.md](./io-framework.plan.md)
>
> Pluggable serialisation framework that converts a `CircuitDocument` to / from string payloads through a registry of named formats. Two concrete formats ship in-tree: the legacy Falstad line-based text dump and the modern self-describing JSON v2.0 document. This concept groups the framework contract, the text dialect, and the JSON dialect into a single module because they share one public entry point (`CircuitFormatRegistry`) and one domain boundary (circuit serialisation).

> Backing analysis: [io-framework.md](../.dev_flow/onboard/analysis/io-framework.md)
> Legacy format specs: [EXPORT_OLD.md](./EXPORT_OLD.md) (text), [EXPORT_CJS.md](./EXPORT_CJS.md) (JSON v2.0)

## 1. Philosophy  {#C_IOF_01}

### 1.1. Core Principle  {#C_IOF_01_01}

Circuit persistence must be pluggable: adding a new serialisation dialect should not require touching simulator core, dialogs, or loader glue. The framework codifies the textbook plugin-registration pattern — a small stable contract (`CircuitFormat`), a static registry (`CircuitFormatRegistry`), and one static-init block that wires concrete formats into the registry at class-load time. Callers look formats up by id, by file extension, or by content sniff (`canImport`). Legacy Falstad-compatible output and the modern JSON dialect are parallel variants of this single concept, not separate subsystems.

The registration cycle (`io` ↔ `io/text`, `io` ↔ `io/json`) is accepted as the deliberate implementation detail of this pattern: the conceptual dependency (dialects implement the framework contract) is acyclic; the physical back-edge exists only because `CircuitFormatRegistry`'s static block instantiates concrete formats. A service-locator or reflective discovery layer would eliminate the cycle, but GWT's compile-time mode does not support either cleanly.

### 1.2. Design Constraints  {#C_IOF_01_02}

- **GWT-safe.** No `java.io.*` / `javax.*`; everything is pure string manipulation so it compiles to JS.
- **Stateless framework objects.** `CircuitFormat` is a pure descriptor; state lives on per-export / per-import instances.
- **Two round-trip guarantees.** (a) `TextCircuitExporter.exportSelection` -> `TextCircuitImporter` preserves copy/paste. (b) `JsonCircuitExporter.export` -> `JsonCircuitImporter.importCircuit` preserves geometry via a two-pass `bounds` application around `needAnalyze`.
- **Permissive parsing.** Unknown element types / malformed properties log to `CirSim.console` and the import continues; a single bad line never aborts a document. A caller that needs the outcome passes an `ImportReport`: every skipped, failed or adjusted item is added with a stable code (`import_element_skipped`, `import_schema_invalid`, `import_wire_skipped`, `import_setting_invalid`, `import_geometry_adjusted`, `scope_limit`, `ids_regenerated`), a severity and its location (text line number or JSON element key) ([SP_AGA_03_04](./agent-api.sp.md#SP_AGA_03_04)). User loads pass none and behave as before.
- **Version policy asymmetric.** Text has an emitted but unchecked version `"1.0"`. JSON requires `schema.version` prefix `"2."` and rejects future `3.x` — no semver comparator.
- **Default format is text.** `DEFAULT_FORMAT_ID = "text"` — text is registered first and wins auto-detection ties.

## 2. Domain Model  {#C_IOF_02}

### 2.1. Key Entities  {#C_IOF_02_01}

**Framework surface (`io/`)**
- `CircuitFormat` — descriptor: id, display name, extensions, mime, version, factory methods `createExporter()` / `createImporter()`.
- `CircuitExporter` — `export(doc)` mandatory; optional `export(doc, includeState)` and `exportSelection(doc, selection)`.
- `CircuitImporter` — `importCircuit(data, doc, flags)` + `canImport(data)`, and the overload `importCircuit(data, doc, flags, ImportReport)` (the default ignores the report; the text and JSON importers override it; a null report equals the three-argument call). Flags are a bitmask: `RC_RETAIN=1`, `RC_NO_CENTER=2`, `RC_SUBCIRCUITS=4`, `RC_KEEP_TITLE=8` (single source `CircuitConst.RC_*`).
- `ImportReport` — items `{code, severity (ERROR/WARNING/INFO), message, line, key}`; `hasErrors()` lets the caller reject the import. A text import also records one model-catalogue restorer per diode / transistor / custom-logic / composite model entry it creates or replaces; a rejecting caller runs `restoreModels()` so no other document sees the change ([SP_AGA_06_01](./agent-api.sp.md#SP_AGA_06_01) item 15).
- `ImportLifecycle` — the format-independent `resetCircuitState(doc)` and `finalizeCircuitLoading(doc, flags [, report])` both importers call.
- `CircuitFormatRegistry` — insertion-ordered `LinkedHashMap<String, CircuitFormat>` with static API: `register`, `unregister`, `getById`, `getByExtension`, `getDefault`, `detectFormat`, `detectFormatOrDefault`, `createExporter`, `createImporter`.

**Legacy text dialect (`io/text/`)**
- `TextCircuitFormat` — id `"text"`, extensions `.txt` / `.circuitjs`.
- `TextCircuitExporter` — emits `$ options` header, per-element `dumpElm` lines, scope dumps, `AdjustableManager.dump`, optional hint line.
- `TextCircuitImporter` — three-cascade dispatch over line tokens (`o`, `h`, `$`, `!`, `%`, `?`, `B`, `34`, `32`, `38`, `.`, else default). Element construction goes through root-package `CircuitElmCreator.createCe` (not through `CircuitElementFactory`).

**JSON v2.0 dialect (`io/json/`)**
- `JsonCircuitFormat` — id `"json"`, extensions `.json` / `.circuitjs.json`, version `"2.0"`.
- `JsonCircuitExporter` — six-section document: `schema`, `simulation`, `elements`, `nodes`, `scopes`, `adjustables`; element keys are the document's registry IDs (`CircuitElm.getElementId()`, per-prefix numbering such as `R1, R2, C1`, [SP_AGA_03_02](./agent-api.sp.md#SP_AGA_03_02)), so a key names the same element as the scripting global and the Agent API; a repeated ID (a defect) is exported as `<id>_2`, `<id>_3`, … with a console warning; `simulation.auto_time_step` is always written; SI-formatter picks prefix in `[f,p,n,u,m,(none),k,M,G,T]`.
- `JsonCircuitImporter` — strict JSON parse -> `validateSchema` -> optional reset -> parse sections -> auto-wire synthesis -> scope / adjustable restoration -> two-pass `bounds` around `needAnalyze`.
- `CircuitElementFactory` — lazy-initialised map of ~150 `"JsonType" -> ElementConstructor` lambdas with historical aliases (`MosfetN`/`NMosfet`, `MosfetP`/`PMosfet`, `JfetN`/`NJFET`, `VoltageSourceVar`->`WF_DC`).
- `UnitParser` — SI-prefix aware `"10 kOhm"` parser; used by the JSON importer and by ~13 element classes' `applyJsonProperties`.

### 2.2. Data Flows  {#C_IOF_02_02}

Export flow:
```
caller -> registry.getById(id).createExporter()
       -> exporter.export(doc [, includeState])
       -> String
```

Import flow:
```
caller -> registry.detectFormatOrDefault(data).createImporter()
       -> importer.importCircuit(data, doc, flags)
       -> mutates doc in place; logs errors to CirSim.console
```

Registration flow (class init):
```
first touch of CircuitFormatRegistry
       -> static block: register(new TextCircuitFormat())
                        register(new JsonCircuitFormat())
       -> map = {"text": ..., "json": ...}
```

## 3. Mechanisms  {#C_IOF_03}

### 3.1. Core Algorithm  {#C_IOF_03_01}

**Format detection.** `detectFormat(data)` iterates `formats` in insertion order (text first) and returns the first whose `canImport` accepts the payload. Text's `canImport` is permissive (trim starts with `$`, letter, or digit); JSON's is strict (starts with `{`, parses, passes `validateSchema`). The permissive-first order is safe in practice because real JSON starts with `{` and fails text's letter/digit gate.

**Element creation (JSON).** The factory drives a uniform construction protocol: lookup constructor by JSON type name -> derive `(x1,y1),(x2,y2)` from pins (`_startpoint`/`_endpoint` pseudo-pins preferred, else first two real pins, else duplicate of the only pin) -> `constructor.create(doc, x1, y1)` -> `setEndpoints` -> apply `_flags` -> apply pin positions -> apply properties -> apply `state` -> `setDescription` -> `finalizeJsonImport()` -> re-apply `bounds`/`setBbox` (idempotency pass).

**Element creation (text).** The text importer does not use `CircuitElementFactory`; it delegates per-line element construction to the root-package `CircuitElmCreator.createCe`, which maps numeric `DumpType` codes to element classes via a large switch.

**SI round-trip.** Export: `formatWithUnit` picks the prefix whose range covers `abs(value)`, emits integer-when-exact or <=4 decimals with trailing zeros stripped. Import: `UnitParser.parse` shortcuts on plain `Double.parseDouble`; else splits at first non-numeric char, strips the first matching unit suffix (greedy on first hit — positional), resolves the remainder as prefix.

**Bounds idempotency.** Explicit `bounds` in the JSON are applied twice — once during element creation, once post-`needAnalyze` — because analysis may rebuild geometry. This is the export/import round-trip contract.

### 3.2. Edge Cases  {#C_IOF_03_02}

- **Schema rejection.** JSON `schema.version` must start with `"2."`; anything else aborts import with a console log. No migration path.
- **Unknown element type.** Factory returns `null`; importer logs and skips that element. Document continues.
- **Malformed options line (text).** `TextCircuitImporter.readOptions` wraps `minTimeStep` and `powerBar` in try/catch for pre-modern dumps. A `$` max step that does not parse to a positive finite number (garbled, ≤ 0, NaN, infinite) falls back to 5 µs.
- **Time-step bar.** Text `readOptions` and JSON `parseSimulation` move the time-step bar to the nearest position with `setValueWithoutCommand`, so the file's own max step is kept exactly instead of being re-quantised to the bar table ([SP_AGA_06_01](./agent-api.sp.md#SP_AGA_06_01) item 18). Invalid JSON `time_step`, `min_time_step` or `voltage_range` values are ignored (defaults kept) and reported as `import_setting_invalid`.
- **Content replacement.** `ImportLifecycle.resetCircuitState` (every import without `RC_RETAIN`) first ends an agent run of the document and seals its open agent transaction — not during an undo/redo restore nor for an agent mutation's own import (agent origin). Except during an undo/redo restore it also resets the document's element ID counters and clears its open marks. `finalizeCircuitLoading` then settles the IDs (`settleElementIds`): restored and supplied IDs raise their counters, invalid or repeated ones are dropped, and elements without an ID get generated ones; replaced IDs are logged as `ids_regenerated` warnings (and added to the report).
- **JSON keys as IDs.** A content replacement keeps the JSON element keys as element IDs; a paste (`RC_RETAIN`) gives the pasted elements generated IDs, the keys only resolve references inside the payload.
- **Model catalogues.** Text model lines (`!`, `34`, `32`, `.`) write into session-wide catalogues; with a report the importer records restorers first, so a rejected import leaves the catalogues as they were.
- **Auto-wire synthesis.** When pins reference `connected_to` without coincident coordinates, the importer inserts `WireElm`s keyed by undirected `"x1,y1-x2,y2"` strings to avoid duplicates. Not idempotent at export — a subsequent round-trip will emit the synthetic wire.
- **UnitParser suffix order.** `UNIT_NAMES` order matters: `"H"` appears before `"Hz"` in the stripping pass, so `"10 Hz"` is stripped as `"H"` leaving `"z"` (no prefix match) — benign because callers know the target unit, but strictly positional.
- **Adjustable `current_value` loss.** JSON importer reads the value but cannot apply it because sliders do not yet exist; post-creation defaults win.

## 4. Integration Points  {#C_IOF_04}

### 4.1. Dependencies  {#C_IOF_04_01}

- **[C_ELB] element-base** — every concrete `*Elm`, the `CircuitElm` base, `WireElm` (for auto-wires), `AudioInputElm` / `DataInputElm` (cache clears), `element.waveform.Waveform`.
- **[C_UTL] util** — general helpers; root-package `CircuitConst` is the single source of the `RC_*` flags (`CircuitImporter.RC_*` alias it).
- **[C_MDS] math-dsp** — `StringTokenizer` for text-line scanning.
- **Root-package collaborators (non-concept):** `CircuitDocument`, `CircuitSimulator`, `CircuitEditor`, `CirSim`, `CircuitRenderer`, `ColorSettings`, `MenuManager`, `Scope`, `ScopeManager`, `ScopePlot`, `Adjustable`, `AdjustableManager`, `CheckboxMenuItem`, `Point`, `CustomCompositeModel`, `CustomLogicModel`, `DiodeModel`, `TransistorModel`, and — critically for the text path — `CircuitElmCreator`.
- **External:** `com.google.gwt.json.client.*` (JSON dialect only).

### 4.2. API Surface  {#C_IOF_04_02}

Stable public surface consumed outside `io/*`:
- `CircuitFormatRegistry.{getById, getByExtension, getDefault, detectFormat, detectFormatOrDefault, createExporter, createImporter}` — used by `ActionManager.dumpCircuit*` and `CircuitLoader.readCircuit`.
- `CircuitExporter.export(doc [, includeState])` and `CircuitImporter.importCircuit(data, doc, flags [, report])` — the format-agnostic entry points; `ImportReport` is consumed by the Agent API (L3), importers never import the agent package.
- `CircuitImporter.RC_*` flag family — referenced by `CustomCompositeElm` (`RC_SUBCIRCUITS`), UndoManager / LoadFile (`RC_RETAIN`, `RC_NO_CENTER`, `RC_KEEP_TITLE`).
- `UnitParser.{parse, parseValue, parseInt, parseBoolean, parseString}` — consumed directly by ~13 element classes' `applyJsonProperties`.
- `CircuitElementFactory.{createFromJson, getAllJsonTypeNames, isKnownType, jsonObjectToMap, jsonValueToObject}` — consumed only by `JsonCircuitImporter` plus element classes that parse nested JSON.

Round-trip test dialogs (`ExportAsJsonDialog`, `ExportAsTextDialog`) exercise the full export -> re-import cycle on every open.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version derived from .dev_flow/onboard/analysis/io-framework.md |
| 2026-10-02 | PL_AGA Phase 10 propagate: flag values corrected (`RC_NO_CENTER=2`, `RC_SUBCIRCUITS=4`); `importCircuit(..., ImportReport)` overload, `ImportReport` (codes, model restorers) and `ImportLifecycle` entities; JSON keys from the runtime ID registry (per-prefix, duplicates suffixed), `auto_time_step` always written; edge cases for the 5 µs max-step fallback, time-step bar without its command, content replacement (seal, ID reset, open marks, ID settle), JSON keys as IDs, model-catalogue restore. |
