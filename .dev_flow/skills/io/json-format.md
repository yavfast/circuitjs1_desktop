---
skill: json-format
domain: io
topics: [json, schema-v2, circuit-element-factory, unit-parser, bounds, auto-wires]
source: onboard
updated: 2026-04-18
---

# JSON v2.0 Format

## Context

The modern JSON format is a self-documenting alternative to the legacy
text dump. It is **not** used for undo snapshots (too verbose) but
**is** the preferred export for sharing/archiving. `docs/EXPORT_CJS.md`
is the authoritative spec (Ukrainian, ~1500 lines). This skill covers
the framework-level schema and the project-specific quirks that bite on
round-trip.

## Key concepts

**Top-level structure** (`JsonCircuitExporter.export`, `.java:67-107`):

```json
{
  "schema":      { "format": "circuitjs", "version": "2.0" },
  "simulation":  { "time_step": "5 us", "max_time_step": "5 us",
                   "voltage_range": "5 V", "current_speed": 50,
                   "power_brightness": 50, "auto_time_step": true, ... },
  "elements":    { "R1": { "type": "Resistor", "bounds": {...},
                           "properties": {...}, "pins": {...},
                           "_flags": 0, "state": {...} } },
  "nodes":       { "N1": { "connections": ["R1.0", "C1.0"] } },
  "scopes":      [ { "element": "R1", "plots": [...], ... } ],
  "adjustables": [ { "element": "R1", "edit_item": 0,
                     "min_value": 0, "max_value": 1e6,
                     "current_value": 1000 } ]
}
```

**Element IDs** are generated on export by prefix rule
(`JsonCircuitExporter.java:621`): `Resistor → R`, `Capacitor → C`,
`Inductor → L`, `Transistor* → Q`, `Diode → D`, `LED → LED`, `Wire → W`,
`Ground → GND`, `VoltageSource`/`DCVoltage → V`, `CurrentSource → I`,
`OpAmp → U`; fallback is first 3 chars of type name. IDs are per-export,
not stable across exports.

**Values use SI-unit strings.** Exporter uses `formatWithUnit` (L667)
emitting `"10 kOhm"`, `"1.5 uF"`, `"3.3 V"`, `"5 us"`. Importer parses
via `io/json/UnitParser.parse` (`UnitParser.java:34`). **Prefixes**:
`f p n u μ m (none) k K M G T`. **Unit suffixes stripped**: `Ohm`, `Ω`,
`ohm(s)`, `F`, `farad(s)`, `H`, `henry/henries`, `V`, `volt(s)`, `A`,
`amp(s)`, `W`, `watt(s)`, `Hz`, `hertz`, `s`, `sec(ond)(s)`.

**Factory registration** (`CircuitElementFactory.init()`,
`.java:65-288`). Every element type is registered explicitly via
`register("JsonName", YourElm::new)`. ~150 entries. `init()` is lazy
(`ensureInitialized()`), not a static initializer — `CirSim.java:204`
documents the deliberate policy. **Rename aliases** coexist:
`MosfetN`/`NMosfet`, `MosfetP`/`PMosfet`, `JfetN`/`NJFET`.

**Creation pipeline** (`createFromJson`, L314):
1. `ensureInitialized()` → lookup constructor; log + return null on miss.
2. Derive `(x1,y1)` / `(x2,y2)` from `pins` (prefer `_startpoint` /
   `_endpoint` pseudo-pins; else first two real pins; fallback copies).
3. `constructor.create(document, x1, y1)` → `elm.setEndpoints(...)`.
4. Restore from `bounds` for single-terminal elements (L405).
5. Apply: `_flags` → pin positions → properties → state → description.
6. `finalizeJsonImport()` hook.
7. Re-apply `bounds` + `setBbox` **post-finalise** (L483-510) — the
   deliberate idempotency dance because analysis may rebuild geometry.

**Auto-wire synthesis** (`JsonCircuitImporter.createAutoWires`): for
each pin with `connected_to` referencing another pin, create a `WireElm`
between the coordinates unless a matching key `"x1,y1-x2,y2"` (unordered)
already exists. **This can create wires that were not in the original
circuit** — see Pitfalls.

**Validation** (`validateSchema`, L867): rejects anything whose
`schema.format` is not `"circuitjs"` or `schema.version` does not start
with `"2."`. No semver range; future `3.x` rejects.

## Usage in this project

- JSON export: menu File > Export As JSON → `JsonCircuitExporter.export`.
- JSON export with simulation state: `includeState = true` path adds
  a `state` map per element (node voltages, pin currents, element-
  specific extras). Only `JsonCircuitExporter` honors `includeState`
  (default impls ignore it, `CircuitExporter.java:57`).
- Selection export: `exportSelection` writes only `schema` + `elements`
  — no simulation/nodes/scopes/adjustables.
- `UnitParser` is consumed **by element classes** (~13 of them:
  `OpAmpElm`, `CurrentElm`, `LampElm`, `GateElm`, `JfetElm`, `PotElm`,
  `RelayElm`, `RelayCoilElm`, `RelayContactElm`, `TransformerElm`,
  `TappedTransformerElm`, `CustomTransformerElm`, base
  `CircuitElm.applyJsonProperties` at `CircuitElm.java:1902`). Removing
  `UnitParser` from `io/json/` breaks those elements — it is an
  intentional downstream dependency.

## Pitfalls

1. **Adjustables lose `current_value` on round-trip.** Comment at
   `JsonCircuitImporter.java:812`: sliders are created after adjustable
   parsing and take their defaults; the loaded `current_value` is
   silently dropped. Document or fix.
2. **Auto-wire non-idempotency.** A circuit with two elements sharing
   a pin location via `connected_to` but **no explicit wire** imports
   with a synthetic wire, re-exports *with* the wire. Re-importing is
   stable but the source-to-source round-trip is not.
3. **JSON type-name vs factory-key drift.** `getJsonTypeName()` must
   exactly match a `CircuitElementFactory.init()` key (or alias). New
   element → add to factory (RULE_ARCH_005) **and** ensure the emit
   name matches. `EXPORT_CJS.md` lists aspirational names
   (`PolarizedCapacitor`, `NMOSFET`, `ACVoltageSource`) that are
   **not** in the current factory — do not rely on them.
4. **`UnitParser.parse` returns 0 on failure.** For fields where "0"
   is a valid value, use `parseValue(Object, defaultValue)` to
   distinguish.
5. **`UnitParser` unit-stripping is positional.** `"1 Hz"` strips `"H"`
   first (index 8 before `"Hz"` at 9 in `UNIT_NAMES`), remainder `"z"`,
   multiplier 1. Benign because the caller already knows the unit, but
   the parser is not round-trip with arbitrary strings.
6. **Schema validation has no forward-compat.** `"3.0"` is rejected.
   When bumping major, provide a converter.
7. **Bounds restoration runs twice** — once during element creation,
   once post-finalise (L483-510) — do not rely on `getBoundingBox()`
   between those steps.
8. **Detection order** puts text before JSON in
   `CircuitFormatRegistry.detectFormat` (LinkedHashMap order). JSON
   blobs start with `{`, text's `canImport` rejects that prefix, so the
   fallback is benign — but do not reorder registration without
   verifying.
9. **Do not use `CircuitConst.RC_RETAIN` vs `CircuitImporter.RC_RETAIN`
   inconsistently** — both are `1`, but there is no shared declaration
   (issue #7). Pick one in new code.

## References

- `.dev_flow/onboard/analysis/io-framework.md` §"io-json",
  §"CircuitElementFactory creation rules", §"Issues / Questions"
- `docs/EXPORT_CJS.md` — full JSON v2.0 spec
- `src/main/java/com/lushprojects/circuitjs1/client/io/json/JsonCircuitExporter.java`
  L54, L67, L621, L667
- `src/main/java/com/lushprojects/circuitjs1/client/io/json/JsonCircuitImporter.java`
  L37, L48, L151, L812, L867
- `src/main/java/com/lushprojects/circuitjs1/client/io/json/CircuitElementFactory.java`
  L40, L65, L314
- `src/main/java/com/lushprojects/circuitjs1/client/io/json/UnitParser.java`
  L34
- Rules: RULE_ARCH_004, RULE_ARCH_005, RULE_NAMING_010, RULE_ERR_003,
  RULE_TEST_003
- Sibling skill: `text-format.md`, `elements/element-authoring.md`
