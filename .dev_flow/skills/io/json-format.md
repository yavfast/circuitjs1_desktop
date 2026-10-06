---
skill: json-format
domain: io
topics: [json, schema-v2, circuit-element-factory, unit-parser, bounds, auto-wires]
source: onboard
updated: 2026-10-06
---

# JSON v2 Format (written as 2.1)

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
  "schema":      { "format": "circuitjs", "version": "2.1" },
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

**Element IDs** — the element keys — are the document's registry IDs (`CircuitElm.getElementId()`, SP_AGA_03_02; since PL_AGA Phase 2, replacing the exporter's own global counter `R1, C2, W3`). Import keeps valid unique keys as IDs (content replacement only; a paste generates new ones), and the keys stay stable across exports.

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

**Pin names are part of the format.** `CircuitElm.applyJsonPinPositions` places posts by pin name (key order only as a fallback), so renaming a pin changes how files load. Renamed pins keep their old names as import aliases via `CircuitElm.getJsonPinAliases()`: since 2.1 (2026-10-03, SP_AGA_DEC_06) voltage sources are `minus`/`plus`, current sources `in`/`out`, the ohmmeter `com`/`probe`, the transformer `p1`/`s1`/`p2`/`s2` (2.0 `pri1`..`sec2` → posts 0..3); 2.0 `positive`/`negative` and `probe+`/`probe-` load as post 0/1 with their old meaning. Op-amps (`in-`/`in+` now constant) have no alias. Readers accept any `2.x`.

## Pitfalls

1. **Every exported property must be applied back** (RULE_ARCH_010). The factory builds elements with the `(doc, x, y)` constructor and then calls `applyJsonProperties`; the base is a no-op. Until 2026-09-30 ~70 classes exported keys nobody read (labeled-node names, chip bits, expressions, model names). A static "has an apply override" check is not enough — single keys can still be missed; verify with `npm run test:live` roundtrip. (`current_value` of adjustables needs no restore: the slider takes the element's restored value.)
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
7. **`bounds` are not endpoints.** They are the bounding box (informational). Writing them into `setEndpoints` turned every element into its box diagonal and disconnected the circuit (fixed 2026-09-30); geometry comes from the pins / `_startpoint` / `_endpoint`.
8. **Detection order** puts text before JSON in
   `CircuitFormatRegistry.detectFormat` (LinkedHashMap order). JSON
   blobs start with `{`, text's `canImport` rejects that prefix, so the
   fallback is benign — but do not reorder registration without
   verifying.
9. **Import flags have one definition: `CircuitConst.RC_*`** (`CircuitImporter.RC_*` alias it). A second set with `RC_NO_CENTER`/`RC_SUBCIRCUITS` swapped made undo load an empty circuit (fixed 2026-09-30).
10. **Values are serialized losslessly** with `CircuitElm.getJsonUnitText` (not the display `getUnitText`, whose precision follows the display setting). `_flags` is always written. Lists are JSON arrays and arrive as `java.util.List`.
11. **Pin keys in `state` must be unique** — chips name Q and Q-bar both "Q"; keys get a `~` (line-over) prefix and a `_<index>` suffix on collision.
12. **JSON 2.2 carries a `models` section** (SP_AGA_03_12; `JsonCircuitImporter` reads `root.models`, the dependency closure comes from `io/ModelDependencies`, shared with `getCircuit`/`usedBy`). With `RC_SUBCIRCUITS` the importer takes the subcircuit entries and their dependencies. In-circuit scopes travel as the `Scope` element's `scope` property and are applied after all elements exist (`io/json/JsonScopeCodec`). Before 2.2 (until PL_AGA Phase 14) JSON had no model definitions, so a front end that needs `.model`/`.subckt` no longer has to go through the text path (first found 2026-10-02, [automation/circuit-experiment-language](../automation/circuit-experiment-language.md)).

## References

- `.dev_flow/onboard/analysis/io-framework.md` §"io-json",
  §"CircuitElementFactory creation rules", §"Issues / Questions"
- `docs/EXPORT_CJS.md` — full JSON v2 spec
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
