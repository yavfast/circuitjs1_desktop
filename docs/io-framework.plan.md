# Implementation Plan: IO Framework  {#PL_IOF}

> **Code:** PL_IOF
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_IOF](./io-framework.concept.md)
> **Specification:** [SP_IOF](./io-framework.sp.md)
> **Depends on plans:** [PL_ELB](./element-base.plan.md), [PL_UTL](./util-locale-log.plan.md), [PL_MDS](./math-dsp.plan.md)
> **Used by plans:** simulator-persistence, dialog-export, dialog-import
>
> Documents the already-shipped io-framework: pluggable format registry, legacy Falstad text dialect, and modern JSON v2.0 dialect with factory-based element creation. All phases are [DONE]; outstanding items are catalogued in Backlog.

> Backing analysis: [io-framework.md](../.dev_flow/onboard/analysis/io-framework.md)

## Goal

Provide a single pluggable serialisation surface (`CircuitFormatRegistry`) that supports round-tripping `CircuitDocument` through Falstad-compatible text and the self-describing JSON v2.0 schema, with shared SI-unit parsing and code-registered element construction.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| JSON parser | `com.google.gwt.json.client.*` (`JSONParser.parseStrict`) | GWT-native; no `java.io`/`javax` allowed in client code that compiles to JS |
| Text tokenising | root-package `StringTokenizer` (from math-dsp) | matches Falstad legacy behaviour character-for-character |
| File I/O glue | browser `FileReader` + Dropbox SDK, wired outside `io/*` | keeps io module string-only for GWT portability |
| Registration style | static initialiser in `CircuitFormatRegistry` | simple plugin pattern; accepted cycle `io` <-> `io/text` and `io` <-> `io/json` at package level |
| Factory style | code-driven `register("JsonType", Class::new)` in `CircuitElementFactory.init()` | GWT compile-time mode does not support `ServiceLoader` or reflective discovery |
| Default format | text (`DEFAULT_FORMAT_ID = "text"`) | backward compat with Falstad dumps and existing saved circuits |

## Progress

- [DONE] Phase 1 — Framework contract (`io/CircuitFormat`, `CircuitExporter`, `CircuitImporter`, `CircuitFormatRegistry`)
- [DONE] Phase 2 — Legacy text dialect (`io/text/*`)
- [DONE] Phase 3 — JSON v2.0 dialect (`io/json/JsonCircuitFormat`, `JsonCircuitExporter`, `JsonCircuitImporter`)
- [DONE] Phase 4 — `CircuitElementFactory` and `UnitParser` (`io/json/*`)
- [DONE] Phase 5 — Static registration wiring and caller integration (ActionManager / CircuitLoader)
- [backlog] Phase 6 — Naming drift resolution (PolarCapacitor vs PolarizedCapacitor, etc.)
- [backlog] Phase 7 — Adjustable `current_value` restoration
- [backlog] Phase 8 — Auto-wire idempotency at export
- [backlog] Phase 9 — Semver-aware JSON schema version gate
- [backlog] Phase 10 — Error surfacing: replace silent parse failures with collectable diagnostics

## Phases

### Phase 1 — Framework contract (`src/main/java/com/lushprojects/circuitjs1/client/io/`) [DONE]

**Depends on:** [PL_ELB] (element types referenced in `exportSelection`)
**Implements:** [SP_IOF_01_01](./io-framework.sp.md#SP_IOF_01_01), [SP_IOF_01_02](./io-framework.sp.md#SP_IOF_01_02), [SP_IOF_01_03](./io-framework.sp.md#SP_IOF_01_03), [SP_IOF_02_01](./io-framework.sp.md#SP_IOF_02_01), [SP_IOF_02_02](./io-framework.sp.md#SP_IOF_02_02)

What was created:
| Entity | Module | Purpose |
|--------|--------|---------|
| `CircuitFormat` | `io/` | Plugin descriptor interface |
| `CircuitExporter` | `io/` | Export contract with default `exportSelection` / `export(doc,includeState)` |
| `CircuitImporter` | `io/` | Import contract + `RC_*` flag constants |
| `CircuitFormatRegistry` | `io/` | Static `LinkedHashMap` registry + static init block wiring Text then JSON |

### Phase 2 — Legacy text dialect (`src/main/java/com/lushprojects/circuitjs1/client/io/text/`) [DONE]

**Depends on:** Phase 1, root-package `CircuitElmCreator`, `Scope`, model registries
**Implements:** [SP_IOF_01_05](./io-framework.sp.md#SP_IOF_01_05), [SP_IOF_02_03](./io-framework.sp.md#SP_IOF_02_03), [SP_IOF_02_04](./io-framework.sp.md#SP_IOF_02_04), [SP_IOF_02_05](./io-framework.sp.md#SP_IOF_02_05)

What was created:
| Entity | Module | Purpose |
|--------|--------|---------|
| `TextCircuitFormat` | `io/text/` | Descriptor for Falstad dump format |
| `TextCircuitExporter` | `io/text/` | `$`-header + per-element `dumpElm` + scopes + adjustables + optional hint |
| `TextCircuitImporter` | `io/text/` | Three-cascade line dispatch; `resetCircuitState`; `finalizeCircuitLoading` |

Notes:
- Authoritative grammar: `docs/EXPORT_OLD.md` (English) and `docs/EXPORT_CJS.md` (JSON v2.0 cross-reference for modern text sub-dialect).
- Element construction is delegated to `CircuitElmCreator.createCe` — text path does NOT use `CircuitElementFactory`.
- Per-line errors logged to `CirSim.console`; document continues.

### Phase 3 — JSON v2.0 dialect (`src/main/java/com/lushprojects/circuitjs1/client/io/json/`) [DONE]

**Depends on:** Phase 1, GWT JSON API, Phase 4 (factory, UnitParser)
**Implements:** [SP_IOF_01_04](./io-framework.sp.md#SP_IOF_01_04), [SP_IOF_02_03](./io-framework.sp.md#SP_IOF_02_03), [SP_IOF_02_04](./io-framework.sp.md#SP_IOF_02_04), [SP_IOF_02_05](./io-framework.sp.md#SP_IOF_02_05)

What was created:
| Entity | Module | Purpose |
|--------|--------|---------|
| `JsonCircuitFormat` | `io/json/` | Descriptor for JSON v2.0 |
| `JsonCircuitExporter` | `io/json/` | Six-section document (schema/simulation/elements/nodes/scopes/adjustables); SI formatter; pretty-printer |
| `JsonCircuitImporter` | `io/json/` | Strict parse + schema gate + section parsers + auto-wire + two-pass bounds idempotency |

Notes:
- Authoritative schema: `docs/EXPORT_CJS.md`.
- Element id generation: prefix table `{Resistor->R, Capacitor->C, ...}` else first 3 chars of type.
- `pinsByLocation` (keyed `"x,y"`) drives node detection (>=3 pins coincide).
- `includeState` gate for round-tripping runtime snapshots.

### Phase 4 — Factory + UnitParser (`src/main/java/com/lushprojects/circuitjs1/client/io/json/`) [DONE]

**Depends on:** [PL_ELB] (every concrete `*Elm`)
**Implements:** [SP_IOF_01_06](./io-framework.sp.md#SP_IOF_01_06), [SP_IOF_01_07](./io-framework.sp.md#SP_IOF_01_07), [SP_IOF_02_06](./io-framework.sp.md#SP_IOF_02_06)

What was created:
| Entity | Module | Purpose |
|--------|--------|---------|
| `CircuitElementFactory` | `io/json/` | ~150 `"JsonType" -> ElementConstructor` entries; lazy `ensureInitialized()`; `createFromJson` construction pipeline |
| `UnitParser` | `io/json/` | SI-prefix aware `String -> double`; helpers `parseValue`, `parseInt`, `parseBoolean`, `parseString` |

Notes:
- Factory aliases for historical renames (`MosfetN`/`NMosfet`, `MosfetP`/`PMosfet`, `JfetN`/`NJFET`, `VoltageSourceVar`->`WF_DC`).
- `UnitParser.jsonObjectToMap` / `jsonValueToObject` helpers reused by element `applyJsonProperties` implementations.
- `UnitParser` returns `0.0` on failure — silent default (flagged).

### Phase 5 — Caller integration [DONE]

**Depends on:** Phases 1-4
**Implements:** public API surface exposed by `CircuitFormatRegistry`

What was wired:
- `ActionManager.dumpCircuit(formatId)`, `dumpCircuitWithState(formatId)`, `dumpCircuit()` — route through `CircuitFormatRegistry.getById` / `.getDefault()`.
- `CircuitLoader.readCircuit` — auto-detect via `detectFormatOrDefault` or explicit `formatId`.
- `CircuitEditor`, `UndoManager`, `DocumentManager`, `LoadFile`, `ImportFromDropbox` — indirect via `readCircuit` / `dumpCircuit`.
- `ExportAsJsonDialog` / `ExportAsTextDialog` — round-trip self-test on open.
- `CustomCompositeElm` — calls `readCircuit` with `RC_SUBCIRCUITS`.
- `CirSim.init` (`:204`) — comment-documents the lazy-init policy for `CircuitElementFactory`.

## Backlog

Items deferred; sourced from analysis "Issues / Questions":

- **Naming drift between EXPORT_CJS.md and factory.** Doc advertises `PolarizedCapacitor`, `DCVoltageSource`, `ACVoltageSource`, `NMOSFET`, `PMOSFET`; factory uses `PolarCapacitor`, `DCVoltage`, `ACVoltage`, `MosfetN`/`NMosfet`, `MosfetP`/`PMosfet`. Decide canonical set; either update doc, or add aliases in `CircuitElementFactory.init()`, or rename element `getJsonTypeName()` outputs (invalidates existing saved JSON).
- **Auto-wire non-idempotency.** `JsonCircuitImporter.createAutoWires` synthesises `WireElm`s for `connected_to` references without coincident pins. A subsequent export emits those synthetic wires as real elements. Options: (a) tag synthetic wires and skip on re-export; (b) drop auto-wire synthesis and require explicit wires in source.
- **Adjustable `current_value` loss.** `JsonCircuitImporter.parseAdjustables` reads `current_value` but cannot apply — sliders are created later in `createSliders()` with default value. Fix: defer `current_value` to a post-`createSliders` pass (comment at `JsonCircuitImporter.java:812`).
- **No semver range on schema version.** Current check `version.startsWith("2.")` will reject future `3.x` outright; add a comparator or a migration hook (`tryMigrate(sourceVersion, targetVersion)`).
- **Silent parse errors in `UnitParser`.** `parse` defaults to `0.0`. Callers have to use `parseValue(obj, defaultValue)` to distinguish. Consider a `OptionalDouble` / `Result<Double>` or a side-channel error collector attached to the importer.
- **Duplicated `RC_RETAIN` constant.** `CircuitConst.RC_RETAIN` and `CircuitImporter.RC_RETAIN` both equal 1 with no shared source. Collapse to a single source.
- **Text `canImport` permissiveness.** Accepts any string starting with `$` / letter / digit; combined with registry insertion order this can misclassify arbitrary text. Consider a stricter header check (look for the options line or for a known first token).
- **Factory depends on every concrete element.** Adding a new element requires editing `io/json/CircuitElementFactory.java`. A GWT deferred-binding or annotation-driven SPI could eliminate the edit — tracked as a separate architectural spike.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version derived from .dev_flow/onboard/analysis/io-framework.md |
