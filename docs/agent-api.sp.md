# Agent API — Specification  {#SP_AGA}

> **Code:** SP_AGA
> **Status:** draft
> **Created:** 2026-10-01
> **Updated:** 2026-10-05
>
> **Concept:** [C_AGA](./agent-api.concept.md)
> **Depends on:** [SP_DOC](./document-model.sp.md), [SP_UND](./commands-undo.sp.md), [SP_SIM](./simulator-engine.sp.md), [SP_IOF](./io-framework.sp.md), [SP_EIC](./edit-info-contract.sp.md), [SP_FBR](./browser-file-bridge.sp.md) (existing mechanisms this spec changes or consumes)
> **Used by:** [SP_MCP](./mcp-server.sp.md), [SP_AGS](./agent-skill.sp.md), [SP_SLV](./linear-solver.sp.md)
> **Plan:** [agent-api.plan.md](./agent-api.plan.md)
>
> This document defines the data structures, operations, validation rules, lifecycles and verification criteria of the Agent API. The API is the transport-free surface through which agents build, edit, inspect, run, measure, debug and checkpoint circuits in any open document. Read it to implement the API, or to map MCP tools onto it. It is split into structures (§01), operations (§02), rules including the per-document routing changes to existing mechanisms (§03), lifecycles (§04), checks (§05) and rollback (§06).

## Contents

- [01. Data Structures](#SP_AGA_01) — cells, IDs, handles, pin names, element specs/records, nets, issues, results, probes, transactions, open marks, models
- [02. Contracts](#SP_AGA_02) — catalogue, documents, import/edit, inspect, readings, render, simulation, runs, diagnostics, history, files, models, layout check
- [03. Validation Rules](#SP_AGA_03) — geometry, identity, properties, atomicity, issue rules, sizing, document routing, files, error reporting, models, text layout
- [04. State Transitions](#SP_AGA_04) — agent transaction, document run state, element ID lifetime
- [05. Verification Criteria](#SP_AGA_05) — functional expectations, invariants, integration scenarios, edge cases
- [06. Reversibility](#SP_AGA_06) — rollback of each behavioural change
- [07. Design Decisions](#SP_AGA_DEC) — edit batch shape, property keys, file operations, model definitions, text layout

## 01. Data Structures  {#SP_AGA_01}

> Implements: [C_AGA_02](./agent-api.concept.md#C_AGA_02)

Notation: `int`, `number` (IEEE double), `string`, `bool`, `T[]` (ordered list), `map<K,V>`, `T?` (optional). Every structure is a plain JSON-representable value; no structure carries a live object reference.

### 01_01. Cell and CellPoint  {#SP_AGA_01_01}

A **cell** is a number of grid cells.
- **Pixel size.** `CELL_PX = 16` editor pixels, a fixed value; coordinates never depend on any grid setting. Placement and moves size grid-dependent elements by the target document's own grid option, and the catalogue is measured at 16 ([§03_01](#SP_AGA_03_01)).
- **Lattice.** The lattice step is `0.5` cell (8 px).
- **Axes.** They follow the editor: x grows rightwards, y grows downwards.

CellPoint fields:
| Field | Type | Required | Default | Constraints | Description |
|-------|------|----------|---------|-------------|-------------|
| x | number | yes | — | within ±4096; `add`/`move`: multiple of 0.5; import: multiple of 1/16 | column |
| y | number | yes | — | within ±4096; `add`/`move`: multiple of 0.5; import: multiple of 1/16 | row |

Conversions: input `px = cell × 16` (exact). Output `cell = px / 16`, exact — a multiple of 1/16, because editor coordinates are whole pixels. Import accepts every value it can produce, so a circuit read with `getCircuit` re-imports unchanged — except an axis-bound element that a legacy file draws diagonally, which re-import rejects with `not_axis_aligned` ([§03_01](#SP_AGA_03_01)); agent-authored edits stay on the half-cell lattice.

### 01_02. ElementId, DocumentHandle, PostRef, CheckpointId, NetName  {#SP_AGA_01_02}

| Name | Type | Constraints | Description |
|------|------|-------------|-------------|
| ElementId | string | `^[A-Za-z][A-Za-z0-9_]{0,31}$`; unique within a document | Stable element identity ([§04_03](#SP_AGA_04_03)) |
| DocumentHandle | string | `^d[1-9][0-9]*$`; unique per app session, never reused | Identity of one open document; assigned when the document is created |
| PinName | string | unique within one element ([§03_02](#SP_AGA_03_02)) | Agent-facing pin name |
| PostRef | string | `<ElementId>.<PinName>` or `<ElementId>.#<index>` (0-based post index) | One post of one element; outputs always use the PinName form |
| CheckpointId | string | `^cp[1-9][0-9]*$`; unique within a document, never reused | Identity of one sealed agent transaction |
| NetName | string | `gnd`, a label text, `label:<text>`, or `$<k>` (`k` = 1-based rank of the unlabelled net by its smallest member PostRef) | Name of a net ([§01_06](#SP_AGA_01_06)) |

### 01_03. ElementSpec (input)  {#SP_AGA_01_03}

Describes one element to create. An ElementRecord ([§01_04](#SP_AGA_01_04)) is accepted wherever an ElementSpec is: `posts` is ignored. This lets a circuit read with `getCircuit` be imported back unchanged.

| Field | Type | Required | Default | Constraints | Description |
|-------|------|----------|---------|-------------|-------------|
| id | ElementId | no | generated `<idPrefix><n>` | unique in document | Agent-chosen ID |
| type | string | yes | — | a catalogue type name or alias | Element type |
| start | CellPoint | yes | — | on lattice | First defining point |
| end | CellPoint | no | `start + defaultSize` | on lattice; `end ≠ start` | Second defining point. For `single` types it sets orientation/lead; for others it is the second defining point |
| properties | map<string, number \| string \| bool> | no | type defaults | keys from the type's property list | Numbers or unit strings (`"4.7k"`, `"10 uF"`) |
| flags | int | no | type default flags | 0 ≤ flags < 2^31 | Raw element flag bits (orientation and options not exposed as properties) |
| description | string | no | — | ≤ 1000 chars | Description text |

### 01_04. ElementRecord (output)  {#SP_AGA_01_04}

| Field | Type | Description |
|-------|------|-------------|
| id | ElementId | Element identity |
| type | string | Canonical catalogue type name |
| start | CellPoint | First defining point (exact cells) |
| end | CellPoint | Second defining point (exact cells) |
| posts | PostRecord[] | Every post in post order; authoritative for the actual pin set of configurable elements |
| properties | map<string, number \| string \| bool> | Current values; numeric values as unit strings in the JSON property format (`"1 kOhm"`) |
| flags | int | Current flag bits |
| description | string? | Description text, if any |

PostRecord: `{pin: PinName, index: int, at: CellPoint, net: NetName, open: bool}`; `open` = the post carries an open mark ([§01_12](#SP_AGA_01_12)).

The `concise` detail level omits `properties` that equal the type defaults and omits `flags` equal to the type default.

### 01_05. TypeInfo  {#SP_AGA_01_05}

| Field | Type | Description |
|-------|------|-------------|
| type | string | Canonical type name: the element's JSON type name |
| aliases | string[] | Other factory keys creating the same type |
| dumpCode | string | Legacy text-format dump type |
| idPrefix | string | Prefix of generated IDs ([§03_02](#SP_AGA_03_02)) |
| geometry | `"single"` \| `"two_point"` \| `"derived"` | `single`: one post at `start`, `end` orients it; `two_point`: posts at `start` and `end`; `derived`: posts computed from the two points. Elements without posts (text, box, line, scope views placed on the canvas) are `derived` with empty `pins` |
| pins | PinName[] | Pin names in post order at the default configuration (configurable elements may change them; see ElementRecord.posts) |
| defaultSize | {dx: number, dy: number} | `end − start` of a freshly placed element, in cells. "Freshly placed" means created by an editor drag of 4 cells to the right from (0, 0), or of (4, 4) when the element is not created by the horizontal drag; elements that size themselves (text, transformers) report their own resulting size |
| derivedPostsAtDefault | map<PinName, CellPoint> | For `derived`: each pin's offset from `start` when placed with `defaultSize` |
| quantities | string[] | `voltage`, `current`, `power` when `{element, quantity}` probes and readings are accepted for the type, else empty |
| properties | PropertyInfo[] | Editable properties ([§03_03](#SP_AGA_03_03) for the key source) |
| defaultFlags | int | Flags of a freshly placed element (after the default placement drag, which can set orientation bits) |

PropertyInfo:
- **Shape.** `{key: string, kind: "quantity" | "number" | "bool" | "text", default: number | string | bool, unit: string?, label: string?, sliderMin: number?, sliderMax: number?, readOnly: bool?}`.
- **`kind`.** It is `"quantity"` when the default is a unit string. `unit` is then its unit suffix (`Ohm`, `F`, `H`, `V`, `A`, `Hz`, `s`, …).
- **`label`, `sliderMin`, `sliderMax`.** These come from the matching editable-parameter entry ([§02_01](#SP_AGA_02_01)). Matching is one-to-one: a key whose default equals the value of exactly one entry gets that entry, and an entry matched by more than one key is given to none of them; `bool` keys are not matched. `label` is the entry's English name (untranslated, markup removed). `sliderMin`/`sliderMax` are the slider seeds of that entry — a hint of a typical range, never a validity limit; they are omitted when the entry has sliders disabled or carries a degenerate pair (min = max, including (−1, −1) and (0, 0)).
- **`choices`.** `string[]?` — for a key that names a session model (diode, zener, transistor `model`; custom logic and subcircuit `model_name`), the names accepted now: the listed entries of the matching kind ([§01_13](#SP_AGA_01_13); internal entries never), sorted as `listModels` sorts them ([§03_03](#SP_AGA_03_03) Model names, [§03_11](#SP_AGA_03_11)).
- **Element-declared `label`.** An element may declare a key's label when its dialog row shows a derived value (a transformer's dialog shows N1/N2 while `ratio` stores N2/N1); such a key gets no slider seeds.
- **`readOnly`.** `true` for a key the element exports but derives from its geometry or state rather than applying it from the property (for example a transformer's orientation keys, or a LogicInput's `state`, which follows `position`). `set` of a read-only key is `invalid_value`; an import accepts read-only keys and ignores them, so a circuit read with `getCircuit` re-imports unchanged.
- **Defaults.** They are the element's built-in defaults: the catalogue is measured against a session scratch document with default options, with the element classes' remembered last-used values (model names, gate options, ground symbol and similar) at their initial values. They never reflect the user's latest choices in the editor. Text defaults are in English (an element placed by `add` in a localized UI keeps English default texts such as slider captions). `add` applies these defaults to every property its spec does not give ([§02_04](#SP_AGA_02_04)).

Index form (`listTypes`): `{type, aliases, pins, geometry, summary: string}` per type; `summary` = the element's menu label in English (untranslated, whatever the UI language). A factory key whose fresh instance cannot be created or placed is left out of the catalogue.

### 01_06. Net and ConnectivityReport  {#SP_AGA_01_06}

> **Criticality:** critical

Net:
| Field | Type | Description |
|-------|------|-------------|
| name | NetName | `gnd` for the ground node (also when labelled); else the lexicographically smallest label text on the net, written `label:<text>` when the text is `gnd`, starts with `$` or starts with `label:`; else `$<k>`, where the unlabelled nets are numbered 1, 2, … in order of their smallest member PostRef, so the names do not depend on element order and survive a re-import (a net whose only members are wire posts is ranked by its smallest wire PostRef) |
| posts | PostRef[] | Member posts of all elements except elements of type `Wire`, sorted (ground and labelled nodes are members) |
| wires | int | Number of `Wire` elements in the net |
| labels | string[] | Label texts on the net |

ConnectivityReport:
| Field | Type | Description |
|-------|------|-------------|
| nets | Net[] | Sorted by name |
| issues | Issue[] | Connectivity issues ([§03_05](#SP_AGA_03_05)) |
| implicitGround | bool | The simulator assumed ground at a voltage-source post |
| analysed | bool | false only when analysis could not run (issues explain why) |
| truncated | bool | true when nets or issues were cut by [§03_07](#SP_AGA_03_07) |

ConnectivityDelta (in mutation results): `{added: Issue[], cleared: Issue[], errorCount: int, warningCount: int}`.
- `added` and `cleared` compare the issue sets before and after the operation by issue key ([§01_07](#SP_AGA_01_07)).
- `errorCount` and `warningCount` are the totals after the operation.
- `added` and `cleared` list at most 50 issues each; `truncatedAdded`/`truncatedCleared` (int) give the number left out, and the full set is read with `getConnectivity`.

### 01_07. Issue  {#SP_AGA_01_07}

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| code | IssueCode | yes | Stable code ([§03_05](#SP_AGA_03_05), [§03_06](#SP_AGA_03_06), [§03_13](#SP_AGA_03_13)) |
| severity | `"error"` \| `"warning"` \| `"info"` | yes | `error`: the operation was rejected, or the circuit cannot be simulated meaningfully |
| message | string | yes | One-sentence English description. A client-supplied value it quotes (operation, handle, type, property key, value, ID, post, net or reading name, checkpoint, model name or field, path, JSON element key, label text) is bounded: up to 64 characters as given, longer ones cut to 64 plus `… (N chars)`; file paths and file-system reasons up to 256. At most 1000 characters |
| elements | ElementId[] | no | Involved elements |
| posts | PostRef[] | no | Involved posts |
| at | CellPoint? | no | Location |
| hint | string | yes | One actionable fix suggestion |
| key | string | yes | `code` + sorted `elements` + sorted `posts` + `at`; identifies the same issue across reports |

### 01_08. OperationResult  {#SP_AGA_01_08}

Every contract returns an OperationResult carrying contract-specific `data`.

| Field | Type | Description |
|-------|------|-------------|
| ok | bool | true when the operation was applied/answered; false when it was rejected |
| data | object? | Contract output (shapes in §02) |
| issues | Issue[] | When `ok = false`: the rejection reasons. When `ok = true`: warnings/info, plus problems found by a successful operation (a run's solver issues). At most 50 entries, errors first |
| truncatedIssues | int | Number of issues left out of `issues` (0 when none) |
| connectivity | ConnectivityDelta? | Present on every successful mutating contract |
| transaction | {open: bool, pendingEdits: int}? | Present on every mutating contract |

Invariants:
- `ok = false` ⇒ the document is unchanged ([§03_04](#SP_AGA_03_04)), and `issues` contains at least one `error`.
- Connectivity errors left by a successful edit, and solver issues met during a run, do not make `ok` false.

### 01_09. ProbeSpec, ProbeResult  {#SP_AGA_01_09}

ProbeSpec, used by `run` and by instant readings (`read`):
| Field | Type | Required | Default | Constraints | Description |
|-------|------|----------|---------|-------------|-------------|
| name | string | no | the target string | unique within the call | Result key |
| net | NetName | one of net/post/element | — | existing net | Net voltage relative to ground |
| post | PostRef | one of net/post/element | — | existing post | Post voltage relative to ground |
| element | ElementId | one of net/post/element | — | existing element | Element quantity |
| quantity | `"voltage"` \| `"current"` \| `"power"` | with `element` | `"voltage"` | — | Element quantity as the element defines it |

Element quantities follow the element's own definitions:
- `voltage` is the voltage difference the element reports, as its type defines it: for most two-post elements post 0 minus post 1; for voltage and current sources post 1 minus post 0 (`plus` minus `minus`, `out` minus `in`).
- `current` is the element's reported current; for a BJT the collector current (positive into `collector`), for a MOSFET/JFET the drain current: the current into the `drain` terminal, channel plus the body or gate-drain junction ([SP_AGA_DEC_08](#SP_AGA_DEC_08)). Both are positive into the terminal whatever the polarity, so a conducting PNP, PMOS or P-JFET reads a negative current. For a BJT `voltage` is post 0 minus post 1 (base − collector); for a MOSFET/JFET `drain` minus `source` (negative for a conducting P-channel device).
- `power` is the element's reported power.
- An element that defines no such quantity yields `invalid_value` naming the element type.

ProbeResult:
| Field | Type | Description |
|-------|------|-------------|
| name | string | Probe name |
| unit | `"V"` \| `"A"` \| `"W"` | Quantity unit |
| stats | ProbeStats | Over the recorded window |
| series | {t: number[], v: number[]} | Decimated series ([§03_07](#SP_AGA_03_07)); `t` in seconds of simulated time; at most `maxPoints` points |

ProbeStats: `{samples: int, tStart, tEnd, min, max, mean, rms, peakToPeak, final: number, frequency: number?, dutyCycle: number?, riseTime: number?}`. `mean` and `rms` are time-weighted over sample intervals (the time step is adaptive).
- `frequency` — mean rate of rising crossings of `mean`, with hysteresis of 5 % of `peakToPeak`. Absent when there are fewer than 2 crossings.
- `dutyCycle` — fraction of recorded time with the value above `mean`. Absent when `frequency` is absent.
- `riseTime` — time from 10 % to 90 % of the `min→max` span on the first rising transition. Absent when no such transition exists.
- A probe that recorded no sample reports `stats: {samples: 0}` only and an empty `series`.

### 01_10. AgentTransaction, undo entry extension, CheckpointRecord  {#SP_AGA_01_10}

AgentTransaction (per document, at most one, in memory):
| Field | Type | Description |
|-------|------|-------------|
| pendingEdits | int | Successful mutating calls since it opened |
| lastAgentMutationAt | number | Wall-clock time (ms) of the last agent mutation |

The transaction's undo entry is always the newest entry of the document's undo stack. Nothing else pushes while a transaction is open ([§04_01](#SP_AGA_04_01)).

Undo entry extension (added to every undo entry, user or agent):
| Field | Type | Default | Description |
|-------|------|---------|-------------|
| elementIds | ElementId[] | — | IDs of the snapshot's elements in snapshot order |
| openMarks | PostRef[] | [] | Open marks of the snapshot |
| viewTransform | number[3] | — | The snapshot document's own view scale/offset (replaces the session renderer transform) |
| comment | string? | absent | Checkpoint comment; absent for user edits |
| checkpointId | CheckpointId? | absent | Present on sealed agent entries |
| auto | bool | false | true when sealed automatically |

When undo pops entry E and restores it, the new redo entry holds the state being left: its circuit text, `elementIds`, `openMarks` and `viewTransform` are the current ones, and its `comment`, `checkpointId` and `auto` are copied from E (so the redo label shows E's comment). Redo is symmetric: the new undo entry holds the state being left plus the popped redo entry's `comment`, `checkpointId` and `auto`.

CheckpointRecord (history view): `{checkpointId?, comment?, auto: bool, kind: "agent" | "user", position: int}`. `position` 0 is the entry the next undo (or redo) applies.

### 01_11. Diagnostics  {#SP_AGA_01_11}

| Field | Type | Description |
|-------|------|-------------|
| stopped | bool | The solver is in a stop state |
| stop | Issue? | Stop state as a solver issue ([§03_06](#SP_AGA_03_06)), with the culprit element ID |
| warning | Issue? | The most recent solver warning |
| events | Issue[] | Every solver warning and stop raised since the document's last analysis, in order, each code once ([§03_06](#SP_AGA_03_06)) |
| recovering | bool | The non-convergence recovery is currently engaged for this document (a panic level or singular-matrix stabilisers active), not merely enabled ([§03_06](#SP_AGA_03_06)) |
| lastImport | Issue[] | Issues of the most recent import into this document |
| simTime | number | Simulated time (s) |
| running | bool | Free-running state |
| timeStep | {current, max, min: number, auto: bool} | Time-step settings |
| solver | SolverInfo | The solver of the document ([SP_SLV_01_10](./linear-solver.sp.md#SP_SLV_01_10)): `mode`, `override?`, `effectiveMode`, `path?`, `fullSize`, `size`, `nonZeros`, `factorNonZeros`, `symbolicCount`, `fullFactorCount`, `refactorCount`; always present |
| log | {entries: {seq: int, text: string}[], cursor: int, gap: bool}? | Present when requested ([§02_11](#SP_AGA_02_11)) |

### 01_12. Open marks  {#SP_AGA_01_12}

The open-mark set is per document: a set of PostRefs (PinName form) the agent has declared intentionally unconnected.
- **Persistence.** It lives in memory and is captured in every undo entry (`openMarks`). It is not written to circuit files.
- **Deletion.** Deleting an element removes its marks.
- **Content replacement.** Replacing the document's content clears the set.

### 01_13. ModelSpec, ModelText and ModelRecord  {#SP_AGA_01_13}

A model is a named, reusable parameter set held in a session catalogue, shared by every document ([§03_11](#SP_AGA_03_11)). Four kinds exist — the kinds the app lets a user create; MOSFET, JFET, op-amp and other elements keep their parameters on the element and have no model.

| Kind | Catalogue | Used by (element key) | Model line (first token) |
|------|-----------|-----------------------|--------------------------|
| `diode` | diode models | `Diode`, `LED`, `Varactor` (`model`); `ZenerDiode` (`model`, models with `breakdown_voltage` > 0 only) | `34` |
| `transistor` | BJT models | `TransistorNPN`, `TransistorPNP` (`model`) | `32` |
| `logic` | custom logic models | `CustomLogic` (`model_name`) | `!` |
| `subcircuit` | composite models | `Subcircuit` (`model_name`) | `.` |

Catalogue entries:
- **Built-in.** `builtIn` is true for the diode and transistor entries the app creates at start-up (their `builtIn` flag) and for the composite entries whose lowercase `builtin` field is set (the `default` stub and the internal chip parts). No logic entry is built-in: `builtIn` is false for every logic model. The logic `default` model is an ordinary entry that the editor creates lazily when a `CustomLogic` element first uses it; the Agent API ensures it exists, created exactly as the editor creates it (inputs `A`, `B`, outputs `C`, `D`, no rules, info `custom logic`), before any name check and before any listing. So it is always listed (with `builtIn: false`), and a definition named `default` is accepted only when identical to that entry, otherwise `name_taken` ([§03_11](#SP_AGA_03_11)).
- **Internal.** Entries with the internal flag (diode, transistor and composite entries the editor hides: the parts of built-in chip models such as `~lm317-dz` or `xlm324v2-qpi`, and superseded entries such as `old-default-led`) are never listed, never accepted as a `model`/`model_name` value or as `from`, and their names are always `name_taken`.
- **Growth only.** No Agent API contract removes an entry: a catalogue only grows during a session. Only the editor's own subcircuit delete removes an entry.

ModelSpec (input of the `defineModel` edit and of AgentCircuit `models`; also the output form of [§02_05](#SP_AGA_02_05) and of JSON `models`, [§03_12](#SP_AGA_03_12)):

| Field | Required | Meaning |
|-------|----------|---------|
| `kind` | yes | `diode` \| `transistor` \| `logic` \| `subcircuit` |
| `name` | yes | ModelName: `^[A-Za-z0-9][A-Za-z0-9_.+-]{0,39}$`. Names are create-only ([§03_11](#SP_AGA_03_11)) |
| `from` | no | `diode` and `transistor` only: name of a listed model of the same kind, or of one defined earlier in the same batch or `models` list, whose parameters are the starting values (default: the kind's `default` model). An unknown name, an internal entry or a model of another kind is `unknown_model` naming `from`; `from` with `logic` or `subcircuit` is `invalid_value`. Not allowed in files ([§03_12](#SP_AGA_03_12)) |
| `parameters` | `diode`, `transistor` | Map of the keys of the value table below; a key not in the table is `unknown_property` naming `parameters.<key>` |
| `inputs`, `outputs` | `logic` (yes) | Pin names, 1..32 each, unique within the model across `inputs` and `outputs` (compared as given), each matching `^[A-Za-z0-9/#:_+-]{1,8}$` |
| `rules` | `logic` (yes) | Rule lines, 1..256, each at most 100 chars |
| `info` | `logic` (no) | Info text, at most 200 chars (default: the name) |
| `source` | `subcircuit` (yes) | `{doc: DocumentHandle}` — the document whose whole circuit becomes the model. Input only; not allowed in files ([§03_12](#SP_AGA_03_12)) |
| `showLabel` | `subcircuit` (no) | `bool`, default true |

A field that the kind does not take is `invalid_value` naming it.

Parameter values (`diode`, `transistor`):
- **Input.** A value is a number or a string as a property value of the given kind ([§03_03](#SP_AGA_03_03)): a `quantity` key takes an optional SI prefix and its unit, a `number` key takes no unit. The four transistor keys marked ∞ also accept the string `"inf"` (infinite; stored as an inverse of 0). A value outside its constraint is `invalid_value` naming `parameters.<key>`.
- **Output.** A `quantity` key is a unit string written by the element unit formatter (`getJsonUnitText`), which is lossless (RULE_STYLE_010); a `number` key is a JSON number; an infinite value is `"inf"`.

| Kind | Key | SPICE | Value kind | Unit | Constraint |
|------|-----|-------|------------|------|------------|
| diode | `saturation_current` | IS | quantity | A | > 0 |
| diode | `series_resistance` | RS | quantity | Ohm | ≥ 0 |
| diode | `emission_coefficient` | N | number | — | > 0 |
| diode | `breakdown_voltage` | BV | quantity | V | ≥ 0 (> 0 makes a zener model); negative is `invalid_value` |
| diode | `forward_voltage` | — | quantity | V | > 0; simple and record forms below |
| diode | `forward_current` | — | quantity | A | > 0; only together with `forward_voltage` |
| transistor | `saturation_current` | IS | quantity | A | > 0 |
| transistor | `beta_reverse` | BR | number | — | > 0 |
| transistor | `emission_coefficient_forward` | NF | number | — | > 0 |
| transistor | `emission_coefficient_reverse` | NR | number | — | > 0 |
| transistor | `leakage_be_current` | ISE | quantity | A | ≥ 0 |
| transistor | `leakage_bc_current` | ISC | quantity | A | ≥ 0 |
| transistor | `leakage_be_emission` | NE | number | — | > 0 |
| transistor | `leakage_bc_emission` | NC | number | — | > 0 |
| transistor | `early_voltage_forward` | VAF | quantity | V | > 0 or `"inf"` (∞) |
| transistor | `early_voltage_reverse` | VAR | quantity | V | > 0 or `"inf"` (∞) |
| transistor | `knee_current_forward` | IKF | quantity | A | > 0 or `"inf"` (∞) |
| transistor | `knee_current_reverse` | IKR | quantity | A | > 0 or `"inf"` (∞) |

Transistor keys not given take the values of `from`; an Early voltage or knee current not given and not taken from `from` is infinite. Forward beta is the element's `beta` property, not a model parameter.

Diode forms (keys not given take the values of `from`):
- **Core form.** Any of the four core keys `saturation_current`, `series_resistance`, `emission_coefficient`, `breakdown_voltage`, without `forward_voltage`/`forward_current`. The model is not simple (flags 0, stored forward current 0).
- **Simple form.** `forward_voltage` and `forward_current` (both required), optionally with `saturation_current` and `breakdown_voltage`: the emission coefficient is solved so that the diode drops `forward_voltage` at `forward_current` with the saturation current kept and series resistance 0 — exactly the editor's "Create New Simple Model" (flags SIMPLE, stored forward current = `forward_current`).
- **Record form.** All four core keys present, with or without `forward_voltage`/`forward_current`: the core values are taken as given; `forward_current` (which then still requires `forward_voltage` and `series_resistance` = 0) marks the model simple and is its stored forward current; `forward_voltage` is derived and is only checked: it must equal the drop the core values give at `forward_current` (as a record states it) within 1e-9 relative, otherwise `invalid_value` naming `parameters.forward_voltage` ("forward_voltage is derived here; omit emission_coefficient and series_resistance to use the simple form"). A `forward_current` with a non-zero `series_resistance` is `invalid_value` naming `parameters.forward_current`. This is the form records and `getCircuit` emit, so a record re-imports to the same model line.
- **Errors.** `forward_current` without `forward_voltage` is `invalid_value` naming `parameters.forward_current`; `forward_voltage` without `forward_current`, and `forward_voltage` together with `emission_coefficient` or `series_resistance` when not all four core keys are present, are `invalid_value` naming `parameters.forward_voltage`.
- **Records.** A diode record always carries the four core keys; `forward_voltage` (the editor's derived drop at `forward_current`) and `forward_current` appear only for a simple model.

Logic models:
- **Rules.** The custom-logic rule syntax of the editor (`left=right`; left: `0 1 ? + -` and pattern letters, length between the input count and inputs + outputs; right: length = output count (`0 1 _` or a pattern letter; other characters read as 0); `#` comment lines and blank lines ignored). Rules are validated before anything is registered; a bad line is `invalid_value` naming `rules[<i>]` with the reason, never an alert.
- **Pin markup.** Pin names use the chip pin markup of the editor: a leading `/` (overbar) or `#` (bubble), `CLK:` (clock mark) and `INV:` (bubble) anywhere, and a name equal to `clk` in any case (drawn as a clock mark without text). The element's PinNames are the names after markup removal, made unique by [§03_02](#SP_AGA_03_02) (`Q` and `/Q` give `Q`, `Q_2`). A name that is empty after markup removal (`CLK`, `/`, `INV:`) is `invalid_value` naming `inputs[<i>]`/`outputs[<i>]`; a clock input is written with text, such as `CLK:C`.

Subcircuit source:
- **Build.** The source document's whole circuit becomes the model the way the editor's "Create Subcircuit" builds it: external pins are its non-internal labelled nodes (the pin side follows the label direction); wires, labels, ground, graphics and scopes are not part of the model; ground becomes the model's ground node. Pins are ordered by label text (case-insensitive), as the editor orders them, and sized as its pin-layout dialog computes them; the element's pins are `pin1`..`pinN` in that order. The model's stored circuit (`modelCircuit`, what the editor's "Load Model Circuit" opens) is the source document's own circuit dump.
- **Read-only source.** The build reads the source and never changes it: its selection is ignored (always the whole circuit); it is analysed for the build by a node allocation only — the subcircuit allocation (no ground at a voltage source), without the element validation of an analysis (which would reset an inductor that has no current path or mark a current source broken), without the time-step reset and without the analysis hook — and its normal node allocation is restored afterwards the same way, which gives every element its previous node and voltage-source numbers, so a stamped source keeps its stamp. Its simulated time, time step, stop state, solver events, node voltages and element states are unchanged, and when it is the active document its simulation state is unchanged (R1 of [§03_08](#SP_AGA_03_08)). The editor's "Create Subcircuit" keeps its own analysis (with element validation).
- **Errors.** An unknown handle is `unknown_document`; a busy source document ([§03_08](#SP_AGA_03_08)) is `busy`. Each of the following is `invalid_value` naming `source` with the reason as text, never an alert or dialog: the source is the target document of the call ("a subcircuit cannot be built from the document it is defined in"); no external pin ("device has no external inputs/outputs"); a label on ground ("node <label> can't be connected to ground"); a labelled node no element uses ("node <label> is not used"); unconnected internal nodes ("some nodes are unconnected": a group of internal nodes — not labelled, used by an element of the model — that has no path to the ground node, found as the simulator finds unconnected nodes; when no element of the source has a ground connection, the first such group is tolerated, as by the editor, so one floating part in a source without ground is accepted and a second one is not; the agent build decides exactly as the editor's "Create Subcircuit" on the same circuit); node allocation of the source fails ("the node allocation of the source circuit failed (its wire analysis reports an error: <stop message>)"; reachable only with non-convergence recovery off, where a wire loop stops the analysis); two labels with different texts on one node ("labels <a> and <b> are on one node" — the editor's build silently keeps the first, the agent path rejects it); a Subcircuit in the source whose model is, directly or through other subcircuit models, the model being defined ("a Subcircuit inside uses the model being defined, <name> (recursion)"; with create-only names this needs the name to exist already, which is `name_taken` first; the check is kept as a guard). The built model's inner references are checked as for a ModelText (Inner references, below).

ModelText (`{kind, name, modelText}`; input of AgentCircuit `models`, output of [§02_05](#SP_AGA_02_05) and JSON `models` for any kind):
- **Name.** Any non-empty string not starting with `~` (names the ModelName pattern rejects, such as the editor's `fwdrop=0.8`, travel this way).
- **Line.** `modelText` is exactly one model line of the text format (no line break; a single trailing newline is dropped). Its first token must be the kind's token of the table above and its unescaped name token must equal `name`; otherwise `invalid_value` naming `modelText`. The remaining fields are parsed as the text importer parses them, without any catalogue write; a field that does not parse is `invalid_value`. Integer fields must be decimal integers (the importer's lenient 0-on-failure is not applied). A logic line's rules are validated by the same rule parser as a ModelSpec's rules (`invalid_value` with the line and reason, never an alert), after the create-only identity test: a line identical to an existing entry is accepted even when its rules do not parse; the other ModelSpec limits (pin-name pattern, pin and rule-line counts, line and info lengths) do not apply to a logic ModelText.
- **Inner references.** When a subcircuit ModelText, or a subcircuit built from a `source`, is validated, the class names of its node list must name element classes the composite builder can create, and the model-name fields of its element dumps (diode family, transistor, `CustomLogic`, nested `Subcircuit`) must resolve against the session catalogues plus the earlier entries of the same `models` list or batch (`models` lists dependencies first, [§03_11](#SP_AGA_03_11) Dependencies); otherwise `invalid_value` naming `modelText` (or `source`) with the reason "inner model <name> unknown" (or "unknown element class <class> in the node list"). Any existing entry resolves a name, internal entries included (built-in chip parts); a logic entry that an element line of the same content created as a fallback does not. A nested `Subcircuit` that is the model itself is a recursion (reason as in Errors, above). The name check is a static parse of the node list and element dumps (nested dumps against the nested model's node list) that runs before any trial build; the trial build constructs the model's elements once in the target document, with the earlier definitions of the call registered for it and restorers for every catalogue entry it creates or rewrites, so validation leaves the catalogues unchanged. Any exception while building the model for validation is `invalid_value` ("the model does not load (<message>)"), never `internal_error`, and leaves the catalogues unchanged. The check runs after the create-only identity test: an entry identical to an existing one is accepted unchecked.
- **Pins.** A subcircuit ModelText with no pin (an empty pin list) is `invalid_value` naming `modelText` ("a subcircuit needs at least one pin"), as a source without labels is; an identical existing entry is accepted.
- **Text content.** A new subcircuit model line (`.`) of `importCircuit` text content gets the same static check (inner references, element classes, recursion) and the pin check, against the session catalogues as the content's earlier model lines leave them; a problem is an `invalid_value` error item at its line and the line is not loaded (the trial build is not run: an element line that fails to load is `import_element_skipped`). `openFile` and user loads load such lines as the editor does.

ModelRecord (output of `listModels` and of `defineModel` results): `{kind, name, builtIn: bool, existing?: true, parameters?: map, inputs?, outputs?, rules?, info?, showLabel?, pins?: [{pin: PinName, label: string, side: "N" | "S" | "W" | "E"}], usedBy: [{doc: DocumentHandle, ids: ElementId[]}]}`.
- **`parameters`.** Diode and transistor: the keys of the value table in the output form above (diode: the record form).
- **`inputs`, `outputs`, `rules`, `info`.** Logic: as stored, pin names with their markup; `rules` split into lines.
- **`showLabel`, `pins`.** Subcircuit: one entry per element pin in pin order; `side` is the chip side the pin is drawn on (north, south, west, east).
- **`existing`.** Present (true) in a `defineModel` result when the definition was identical to an existing entry and changed nothing ([§03_11](#SP_AGA_03_11)).
- **`usedBy`.** One entry per open document that uses the model, with the IDs of its elements that reference it directly or through a subcircuit model ([§03_11](#SP_AGA_03_11) Dependencies); a Subcircuit element is listed for every model its subcircuit model depends on.

## 02. Contracts  {#SP_AGA_02}

Common rules for every contract:
- **Document.** `doc: DocumentHandle?` — absent means the active document. An unknown handle yields `unknown_document`. The contract acts on that document only ([§03_08](#SP_AGA_03_08)).
- **Result.** Every call returns OperationResult ([§01_08](#SP_AGA_01_08)).
- **Timing.** Contracts are synchronous, except `run` ([§02_10](#SP_AGA_02_10)) and `render` ([§02_08](#SP_AGA_02_08)), which complete asynchronously.
- **Readiness.** A contract called before application start-up completed returns `not_ready`.
- **Argument ranges.** Any argument outside its stated range, enumeration or list cardinality returns `invalid_value` naming the argument; nothing is applied.
- **Modified flag.** Every successful mutating contract, and every successful `undo`, `redo` and `restoreCheckpoint`, sets the target document's modified flag.

Contract classes:
| Contract | Mutating (returns `connectivity` + `transaction`) | Opens/continues the agent transaction | Served while the document is busy |
|----------|------|------|------|
| listTypes, describeType, listDocuments, getCircuit, getConnectivity, checkLayout, read, render, getDiagnostics, getHistory, exportCircuit | no | no | yes |
| createDocument, activateDocument | no | no | yes |
| importCircuit, applyEdits, openFile into a handle | yes | yes | no (`busy`) |
| openFile into `new` | no (creates a document) | no (user-load semantics) | yes (another document) |
| simControl `configure` | yes | yes (time-step settings are part of the circuit text) | no |
| simControl `run`/`stop`/`reset`/`solver`, run | no | no | no |
| checkpoint, undo, redo, restoreCheckpoint | no (they seal or restore) | no | no |
| saveFile | no | no (seals) | yes |
| closeDocument | no | no | only with `discardChanges` (cancels the run) |

### 02_01. listTypes / describeType  {#SP_AGA_02_01}

Purpose: read the element catalogue (session scope).

Input: `listTypes(filter: string?)` — case-insensitive substring of type name, alias or summary. `describeType(type: string)` — a type name or alias.

Output: `listTypes` → `data.types: TypeIndexEntry[]` sorted by `type`; `describeType` → `data: TypeInfo`.

Errors:
| Code | Condition | Guidance |
|------|-----------|----------|
| unknown_type | Name not a type or alias | `hint` lists up to 5 closest names by edit distance |

Processing logic:

    FUNCTION buildCatalogue():                                  # once per session, lazily
        pin editor grid size to 16 for the duration
        FOR each key IN element factory registry:
            elm ← factory.create(key, owner = session scratch document with default options, at (0,0))   # never inserted into any element list; last-used class values at their initial values
            canonical ← elm JSON type name; IF key ≠ canonical: add key to aliases[canonical]   # independent of registry order
            IF canonical already measured: CONTINUE
            place elm at its default size (§01_05; fixed-size elements keep their own size, as in the editor); record posts (agent pin names, §03_02), dump code, id prefix, flags
            keys ← exported property keys of elm ∪ elm's declared conditional property keys (§03_03)
            FOR each key: default, kind, unit from the exported/declared default value;
                label/sliderMin/sliderMax from the editable-parameter entry whose value equals the default, used only when exactly one entry matches
        restore grid size; cache result

### 02_02. Documents  {#SP_AGA_02_02}

Purpose: list and manage open documents.

| Contract | Input | Output (`data`) |
|----------|-------|-----------------|
| listDocuments | — | `documents: {doc, title, active: bool, modified: bool, running: bool, busy: bool, elementCount: int, filePath: string?}[]` |
| createDocument | `title: string?` (1..200 chars, not only whitespace; shown as the tab title until the document has a file name), `activate: bool = false` | `{doc}` |
| activateDocument | `doc` (required) | `{doc}` |
| closeDocument | `doc` (required), `discardChanges: bool = false` | `{doc, replacement: DocumentHandle?}` |

- **Closing the last tab.** Closing the last document follows the existing tab rule: a blank replacement document is created. Its handle is returned as `replacement`.
- **Tab changes.** Only `activateDocument`, `createDocument(activate: true)`, `openFile(activate: true)` (with `into: "new"` or a handle) and closing the active document change the visible tab. Closing the active document activates the tab the existing tab rule selects; closing a background document leaves the visible tab unchanged ([§03_08](#SP_AGA_03_08)).

Errors:
| Code | Condition | Guidance |
|------|-----------|----------|
| unknown_document | Handle not open | `hint` lists open handles |
| unsaved_changes | `closeDocument` on a modified document without `discardChanges` | Save first or pass `discardChanges` |
| busy | `closeDocument` while a run is in progress on it, unless `discardChanges` | Wait for the run, or pass `discardChanges` (the run ends `cancelled`) |

### 02_03. importCircuit  {#SP_AGA_02_03}

Purpose: replace a document's circuit in one call.

Input:
| Parameter | Type | Required | Constraints |
|-----------|------|----------|-------------|
| doc | DocumentHandle? | no | — |
| circuit | AgentCircuit \| string | yes | An AgentCircuit object (`elements` ≤ 5000, `scopes` ≤ 20), a JSON v2 circuit text, or a legacy text circuit (string ≤ 10 MB) |

AgentCircuit (coordinates may be any multiple of 1/16 cell, [§01_01](#SP_AGA_01_01)): `{elements: (ElementSpec | ElementRecord)[], simulation: map<string, number|string|bool>?, scopes: {element: ElementId, quantity: "voltage"|"current"|"power"?}[]?, models: (ModelSpec | ModelText)[]?}`

`models` ([§01_13](#SP_AGA_01_13), at most 200 entries, each a ModelSpec or a ModelText; the `models` section of a JSON v2 text has no count limit, [§03_12](#SP_AGA_03_12)) are validated with the elements and defined before the elements are created, in array order (dependencies first), under the rules of [§03_11](#SP_AGA_03_11): a name that exists is accepted unchanged when the entry is identical and is otherwise `name_taken`; a rejected import removes every entry it created. `simulation` keys are those of the JSON v2 `simulation` object.

Output: `data: {elements: int, ids: ElementId[]?}`; `ids` is present when `elements` ≤ 200 (otherwise read them with `getCircuit`); `connectivity` = delta against the replaced circuit. The simulation state is reset: simulated time is 0 and element states are initial.

Errors: any validation code of [§03](#SP_AGA_03) for any element (`elements` names the offending spec as `#<i>` when it has no ID); `import_schema_invalid` (JSON text failing schema validation); `import_element_skipped` (an element the factory could not create — the import is rejected); `busy`; for a `models` entry or a model line ([§03_11](#SP_AGA_03_11)): `name_taken`, `unknown_model` (`from`), `invalid_value`, and for a subcircuit `source` `unknown_document` and `busy` (the source document).

Processing logic:

    FUNCTION importCircuit(doc, circuit):
        IF doc busy: RETURN error busy
        form ← detect(circuit)                                 # AgentCircuit | JSON v2 | text
        names ← session catalogue names (listed entries, per kind)
        IF form ∈ {AgentCircuit, JSON v2}:
            FOR each models entry m in order:                  # §01_13, §03_11; JSON: `from`/`source` → invalid_value
                validate m (shape, kind fields, values, rules, ModelText line; a subcircuit `source` is built
                    detached from its read-only source document: unknown_document, busy, invalid_value)
                IF m.name ∈ names[m.kind] or defined earlier in models: identical ? mark m existing : error name_taken
                names[m.kind] ← names[m.kind] ∪ {m.name}
        IF form = AgentCircuit: validate all element specs (§03_01–03_03); model references against names (session ∪ models)
        IF any error: RETURN ok=false (nothing applied)          # no snapshot taken, catalogues untouched
        IF form = AgentCircuit: convert to JSON v2 (cells×16, _startpoint/_endpoint pins, `models` kept)
        snapshot ← capture(doc)                                # circuit text + elementIds + openMarks + scopes + viewTransform
        restorers ← []
        TRY
            FOR each models entry m not marked existing, in order:
                restorers.push(entryRestorer(m.kind, m.name)); define m in its kind's catalogue
            load the content into doc through the format registry, with grid size pinned to the grid option the content
                selects (§03_01); a JSON `models` section is already defined (its entries are now identical); text model
                lines follow the same identical-or-name_taken rule, each created name pushing its restorer first;
                JSON and text element model references resolve against the catalogues as now defined;
                the importers report every skipped or failed line/element (§03_04)
            IF any error issue: rollback(); RETURN ok=false         # undo/redo stacks untouched
        ON exception t: rollback(); report t to the global uncaught-exception handler (§03_10); RETURN ok=false with internal_error
        reset ID counters; assign IDs (§03_02); clear open marks
        openOrContinueTransaction(doc, snapshot)               # §04_01; pushes `snapshot` and clears redo only when opening
        analyse doc; RETURN ok=true with ids and connectivity delta

        rollback(): run restorers in reverse order (each removes the entry its name created); restore(doc, snapshot)

### 02_04. applyEdits  {#SP_AGA_02_04}

> **Criticality:** critical

Purpose: apply an ordered batch of incremental edits atomically ([SP_AGA_DEC_01](#SP_AGA_DEC_01)).

Input: `doc?`, `edits: Edit[]` (1 ≤ length ≤ 200).

Edit (discriminated by `op`):
| op | Fields | Effect |
|----|--------|--------|
| add | `element: ElementSpec` | Create an element through the element factory; properties not given take the TypeInfo defaults ([§01_05](#SP_AGA_01_05)) |
| move | `id` + one of: `start` (and optional `end`), or `by: {dx, dy}` | `start` + `end`: set both defining points. `start` alone: translate so `start` lands there. `by`: translate both points; `dx`/`dy` are multiples of 0.5 |
| delete | `id` | Remove the element, its scope views and its open marks |
| set | `id`, `properties: map`, `flags: int?` | Patch properties (keys not given keep their value) |
| describe | `id`, `description: string` | Set description text |
| addScope | `element: ElementId`, `quantity?` | Add an on-screen scope view. The view plots what the editor's scope plots: for a P-channel FET `current` is the channel current from source to drain (positive when conducting) and `voltage` source minus drain, the opposite sign of the `read`/probe quantities ([SP_AGA_DEC_08](#SP_AGA_DEC_08)) |
| removeScope | `element: ElementId` | Remove scope views showing the element |
| markOpen | `posts: PostRef[]`, `open: bool = true` | Add posts to (or remove them from) the open-mark set |
| defineModel | `model: ModelSpec` ([§01_13](#SP_AGA_01_13)) | Register a new session model under the rules of [§03_11](#SP_AGA_03_11) (create-only; a definition identical to an existing entry changes nothing and succeeds); later edits of the batch may reference it. A batch of only `defineModel` edits still opens or continues the transaction and sets the modified flag |

Output: `data: {applied: int, created: ElementId[], elements: ElementRecord[], truncated: int, models?: ModelRecord[]}` (`models`: one record per `defineModel` of the batch, in edit order, present when the batch has any; an identical redefinition is reported with `existing: true`). `elements` holds the records of created, moved or `set` elements in edit order, at most 50, so derived post positions and geometry changes are visible; `truncated` is the number of records left out (read them with `getCircuit`).

Errors: validation codes of [§03](#SP_AGA_03); `unknown_element`; `unknown_post`; `busy`; `scope_limit` (no free scope slot); for `defineModel` ([§03_11](#SP_AGA_03_11)): `name_taken`, `unknown_model` (`from`), `invalid_value`, and for a subcircuit `source` `unknown_document` and `busy` (the source document).

Processing logic:

    FUNCTION applyEdits(doc, edits):
        IF doc busy: RETURN error busy
        names ← session catalogue names (listed entries, per kind)
        validate the batch in order against a model of doc's element set and of names, with earlier edits visible to later ones
            (an add's id is usable by a later edit; a deleted id is not;
             an add or set that gives a CustomLogic a model_name gives later edits of the batch that model's PinNames;
             a defineModel is validated as in §01_13 — a subcircuit `source` is built detached from its read-only source
             document (unknown_document, busy, invalid_value) — and its name, when new, joins names for later edits;
             a name already in names is accepted only when identical (marked existing), else name_taken)
        IF any error: RETURN ok=false (nothing applied)        # no snapshot taken, catalogues untouched
        before ← connectivityIssues(doc); snapshot ← capture(doc); restorers ← []
        WITH agent origin marked (editor undo pushes suppressed, §04_01) AND grid size pinned to doc's grid option (§03_01):
            TRY apply edits in order; a defineModel not marked existing first does
                    restorers.push(entryRestorer(kind, name)), then defines its model
            ON exception t: run restorers in reverse order; restore(doc, snapshot);
                            report t to the global uncaught-exception handler (§03_10);
                            RETURN ok=false with issue internal_error        # undo/redo stacks untouched
        openOrContinueTransaction(doc, snapshot)               # pushes `snapshot` and clears redo only when opening
        analyse doc synchronously (also when free-running); RETURN data, delta(before, after)

`set` semantics:
1. Compute `merged ← current exported properties ⊕ declared conditional properties at their current values ⊕ patch`.
2. Apply `merged` through the element's JSON property application.
3. Re-run the element's geometry and node allocation.
4. Read back every property key of the element. When a property selects a different canonical type (a switch made momentary, an inverting gate), the record's `type` follows the new type and the ID stays.
5. Report mismatches: a patched key whose value differs from the parsed request, or an unpatched *writable* key whose value changed (a read-only key follows another key or the geometry and is not reported), yields a `value_adjusted` warning naming the key and the effective value.

`delete` of an element shown in scope views adds one `scope_removed` info issue listing the removed views.

### 02_05. getCircuit  {#SP_AGA_02_05}

Purpose: read the circuit in agent form.

Input: `doc?`, `detail: "concise" | "full" = "concise"`, `ids: ElementId[]?` (subset), `offset: int = 0`, `limit: int = 200` (≤ 500).

Output: `data: {elements: ElementRecord[], total: int, nextOffset: int?, simulation: map, scopes: {element, quantity}[], models: (ModelSpec | ModelText)[]?, modelsTruncated: int?}`.
- **`models`.** Present only on the page with `offset = 0` (and empty there when the document uses no non-built-in model). It holds the document's models ([§03_11](#SP_AGA_03_11) Dependencies: the transitive closure of the non-built-in models its elements reference, including those the element dumps of its subcircuit models reference), dependencies first, otherwise in the record order of `getCircuit` (first referencing element by ID), each once.
- **Form.** A diode, transistor or logic model is a ModelSpec when its ModelSpec passes the validation of [§01_13](#SP_AGA_01_13) and applying it reproduces the entry's model line exactly; otherwise, and always for a subcircuit model (whose ModelSpec `source` is input-only), it is a ModelText. Values are lossless (RULE_STYLE_010).
- **Structured properties.** Element properties whose value is a list or an object (the `Scope` element's `scope`, [§03_12](#SP_AGA_03_12)) are not agent properties: records omit them.
- **Cap.** At most 200 entries, the AgentCircuit `models` cap; `modelsTruncated` (present when > 0) is the number left out, all of which `exportCircuit` JSON carries ([§03_12](#SP_AGA_03_12)).
- **Round trip.** The returned form re-imports into a fresh session unchanged, and into the same session with every model entry identical (no catalogue change). Exception: a logic model whose stored rules do not parse (the editor keeps such rules after its alert) is returned as ModelText and re-imports only where an identical entry exists; elsewhere it is `invalid_value` ([§01_13](#SP_AGA_01_13) Line).

Ordering:
- IDs of the form `<letters><digits>` come first, ordered by letters, then by numeric value.
- All other IDs follow in lexicographic order.

Errors: `unknown_element` (an ID in `ids`).

### 02_06. getConnectivity  {#SP_AGA_02_06}

Purpose: full connectivity report.

Input: `doc?`, `includeNets: bool = true`, `netFilter: NetName[]?` (1..1000 names).

Output: `data: ConnectivityReport`; with `netFilter`, `nets` holds only the named nets (issues stay complete).

Processing logic:

    FUNCTION getConnectivity(doc):
        ensure doc's analysis is current (analyse doc synchronously when pending)
        group posts by simulator node; name nets (§01_06); compute issues (§03_05); apply caps (§03_07)

Errors: `unknown_net` (a name in `netFilter`).

### 02_07. read  {#SP_AGA_02_07}

Purpose: instant readings at the current simulated time.

Input: `doc?`, `targets: ProbeSpec[]` (1..100).

Output: `data: {t: number, values: {name, value: number | null, unit}[]}`. `value` is `null` only for a non-finite reading (the solver produced NaN or infinity), which also adds a `solver_warning`; it is never `null` for a target that does not exist.

Errors: `unknown_net` (the net name is not present; no 0 V reading is returned); `unknown_post`; `unknown_element`; `invalid_value` (quantity not defined by the element).

### 02_08. render  {#SP_AGA_02_08}

Purpose: an image of the whole circuit of one document.

Input: `doc?`, `format: "svg" | "png" = "png"`, `scale: number = 1` (0.25..4), `includeScopes: bool = false`.

Output: `data: {format, width: int, height: int, content: string}`.
- **Content.** SVG text, or PNG as base64.
- **Area.** Drawn offscreen for the given document. It covers the circuit bounds (element endpoints, bounding boxes and every drawn text, so labels are not clipped) plus a 1-cell margin, whatever the viewport or the active tab. An empty document gives a blank 32×32 image.
- **Look.** Printable colours on white, whatever the session's Printable option, no current dots, the target's selection highlight as it is. Drawing an image never changes a scope's graph, time base, trigger or scale.
- **Texts.** The elements paint the placements of their layout method ([§03_13](#SP_AGA_03_13)) with the document's options, each in the font it names. For an element that is not highlighted (not selected, not hovered, not being dragged), a `checkLayout` text box, times `scale` and shifted by the image origin, is where the image shows that text. A highlighted element may draw transient texts, such as transistor pin letters, which `checkLayout` never checks.
- **Completion.** Asynchronous: the first SVG render loads the vector exporter and then completes. A load failure produces an issue and never a modal alert.

Errors: `render_failed` (the vector exporter could not load; `hint`: retry with `png`); `invalid_value` naming `scale` (the image would exceed 16384 px on a side or 40 megapixels in area); `render_failed` also when the browser cannot encode the image (no dialog); `unknown_document` (the document was closed while rendering).

### 02_09. simControl  {#SP_AGA_02_09}

Purpose: free-running control and time-step settings.

Input: `doc?`, `action: "run" | "stop" | "reset" | "configure" | "solver"`, `settings: {maxTimeStep: number|string?, minTimeStep: number|string?, autoTimeStep: bool?}?` (for `configure`), `mode: "auto" | "dense" | "sparse" | "session"` (for `solver`).

Output: `data: {running: bool, simTime: number, timeStep: {current, max, min, auto}}`; `solver` adds `solver: SolverInfo` ([§01_11](#SP_AGA_01_11)), the state after the change.

Action rules:
- **`run`.** Free-running advances only the active document, which is the existing tab rule. `run` on a background document sets its running flag, which takes effect when it becomes active.
- **`reset`.** Sets simulated time to 0, clears element state and the scope views' histories, and clears the stop state.
- **`configure`.** Changes the persistent settings, the values analysis keeps. It never writes the transient current step; the analysis that follows restarts the current step at the new maximum, as after a user change.
- **`solver`.** Sets the document's solver-mode override ([SP_SLV_02_11](./linear-solver.sp.md#SP_SLV_02_11)); `session` clears it. Not mutating (no transaction, history or modified flag) and never saved. The call does not stamp: a change of the effective mode re-stamps the system (stamp only) at its next frame, run or stamping reading.

Errors: `invalid_value` (non-positive or unparseable step; `min > max`; `configure` naming no setting; `mode` absent or not one of the four values with `solver`; `mode` with another action; `settings` with `solver`); `busy` (during a run).

### 02_10. run  {#SP_AGA_02_10}

> **Criticality:** critical

Purpose: advance simulated time of one document under agent control, with probes ([C_AGA_03_04](./agent-api.concept.md#C_AGA_03_04)). Works on background documents too.

Input:
| Parameter | Type | Required | Constraints |
|-----------|------|----------|-------------|
| doc | DocumentHandle? | no | — |
| mode | `"span"` \| `"settle"` | no (`"span"`) | — |
| span | number \| string | for `span` | > 0 s; unit strings allowed (`"20 ms"`, also inside one pair of quotes — [§03_03](#SP_AGA_03_03)) |
| settle | {tolerance: number = 1e-4, window: number?, maxSpan: number = 1} | for `settle` | `tolerance` > 0 (V); `window` (s) default = 50 × max time step; `maxSpan` (s) > 0 |
| budgetMs | int | no (10000) | 100..120000 |
| probes | ProbeSpec[] | no | ≤ 16 |
| recordFrom | number | no (run start) | Absolute simulated time (s) from which probes record |
| maxPoints | int | no (200) | 10..2000 per probe; Σ over probes ≤ 2000 |
| reset | bool | no (false) | Reset before running |

Output `data`:
| Field | Type | Description |
|-------|------|-------------|
| reason | `"span_reached"` \| `"settled"` \| `"settle_timeout"` \| `"solver_stop"` \| `"stop_trigger"` \| `"budget_exhausted"` \| `"cancelled"` | Why the run ended |
| tStart, tEnd | number | Simulated time span covered (s) |
| steps | int | Timesteps taken |
| wallMs | int | Wall time used |
| probes | ProbeResult[] | One per ProbeSpec |

Result rules:
- `ok = true` for every `reason`; argument errors give `ok = false`.
- `issues` carries the solver events present when the run started (for a run with `reset`: after the reset) plus those raised during it ([§03_06](#SP_AGA_03_06)), each code once.
- `solver_stop` adds the stop issue (severity `error`).
- `budget_exhausted`, `settle_timeout` and `cancelled` add a `warning` with the same code as the reason.
- **Stop trigger.** A stop-trigger element that fires during a run ends it after that timestep with `reason = stop_trigger` and a `warning` issue `stop_trigger` naming the element; as in free-running, the document's running flag is cleared ([SP_AGA_DEC_05](#SP_AGA_DEC_05)).
- **First sample.** Probes record only solved states: when the run starts on a circuit that is not yet solved (no timestep finished since the last analysis — after an import, an edit, a reset or an option change), the first sample is taken after the first timestep, never from unsolved node voltages; `stats.tStart` is then one step after `data.tStart`, and `samples = steps`, else `steps + 1`.
- **Determinism.** With `reset: true`, the same circuit and arguments give the same result, except for circuits whose elements draw random numbers (noise sources), which use the session's unseeded generator.
- An exception inside a slice ends the run with `reason = solver_stop` and stops the document as a free-running exception does (the stop issue plus an `internal_error` issue); the document needs a reset before it runs again.
- A non-finite probe sample (NaN or infinity from the solver) is not recorded; the run adds one `solver_warning` issue naming the probe.
- `steps` counts every timestep that advanced simulated time, forced non-converged steps included; probes sample each of them.

Errors: `busy` (another run on the document); `invalid_value` (also naming `doc` when the document has no elements); probe target errors as in [§02_07](#SP_AGA_02_07), checked before starting.

Processing logic:

    ASYNC FUNCTION run(doc, args):
        validate args and probe targets; IF errors: RETURN ok=false
        IF NOT args.reset AND doc in stop state: RETURN ok=true, reason=solver_stop, steps=0, with the stop issue
        mark doc busy                                        # §04_02: free-run loop skips busy documents
        TRY (the whole loop below; FINALLY clear busy)
        IF args.reset: reset(doc)
        analyse doc if pending; tEnd ← now + span  (settle: now + maxSpan)
        IF doc is solved (a timestep finished since the last analysis): FOR each probe: record(simTime, value) when simTime ≥ recordFrom
        clear a stop-trigger record left by free-running
        LOOP:
            sliceStart ← wallNow()
            WHILE wallNow() − sliceStart < 20 ms:
                advance doc's simulator one timestep without repaint
                FOR each probe: record(simTime, value) when simTime ≥ recordFrom
                collect a new solver warning, if any
                IF doc stop state: reason ← solver_stop; EXIT LOOP
                IF a stop-trigger element fired in this step: reason ← stop_trigger; clear doc running flag; EXIT LOOP
                IF simTime ≥ tEnd: reason ← span_reached (settle mode: settle_timeout); EXIT LOOP
                IF mode = settle AND settled(window, tolerance): reason ← settled; EXIT LOOP
                IF wallNow() − runStart ≥ budgetMs: reason ← budget_exhausted; EXIT LOOP
            IF cancel requested (§04_02): reason ← cancelled; EXIT LOOP
            yield through a zero-delay macrotask; repaint doc once if it is the active document
        ON exception t: reason ← solver_stop; add issue internal_error; pass t to the global uncaught-exception handler (§03_10)
        FINALLY clear busy
        RETURN reason, times, steps, probe results

`settled(window, tol)`: at least `window` seconds of simulated time have elapsed since the run started, and over the last `window` seconds every net voltage's `max − min` < `tol`. The check may run over chunks of `window / 8` and cover up to one chunk more than `window` (never looser).

### 02_11. getDiagnostics  {#SP_AGA_02_11}

Purpose: solver state, last import issues and the session log.

Input: `doc?`, `log: {since: int = 0, limit: int = 50 (1..500)}?`.

Output: `data: Diagnostics` ([§01_11](#SP_AGA_01_11)).

Log reading rules:
- **Source.** The session log, which holds every document's messages. Each entry carries a sequence number `seq` that grows by 1 for every message the session logs, starting at 1.
- **Window.** With `log`, the reply returns entries with `seq > since`, oldest first, at most `limit` of them.
- **Cursor.** `cursor` is the last returned `seq`, or `since` when nothing was returned.
- **Gap.** `gap` is true when entries after `since` were already evicted from the log's retained window.

### 02_12. checkpoint  {#SP_AGA_02_12}

Purpose: seal the open agent transaction as a named undo entry.

Input: `doc?`, `comment: string` (1..120 chars, single line).

Output: `data: {checkpointId: CheckpointId?, noChanges: bool}`.

Processing logic:

    FUNCTION checkpoint(doc, comment):
        IF no open transaction: RETURN ok=true, noChanges=true
        entry ← newest undo entry of doc
        IF entry circuit text = current text AND entry.elementIds = current IDs AND entry.openMarks = current marks:
            remove entry; close transaction; RETURN noChanges=true
        entry.comment ← comment; entry.checkpointId ← nextCheckpointId(doc); entry.auto ← false
        close transaction; RETURN checkpointId

Errors: `invalid_value` (empty, too long, or multi-line comment); `busy`.

### 02_13. History: getHistory / undo / redo / restoreCheckpoint  {#SP_AGA_02_13}

| Contract | Input | Output (`data`) |
|----------|-------|-----------------|
| getHistory | `doc?`, `limit: int = 20` (1..150) | `{undo: CheckpointRecord[], redo: CheckpointRecord[], openTransaction: bool}` |
| undo | `doc?`, `steps: int = 1` (1..50) | `{undone: int}` |
| redo | `doc?`, `steps: int = 1` (1..50) | `{redone: int}` |
| restoreCheckpoint | `doc?`, `checkpointId` | `{undone: int}` — pops undo entries down to and including that checkpoint's entry, restoring the state before its transaction; `undone` = entries popped |

Rules:
- **Auto-seal.** An open transaction is sealed automatically before `undo` and `restoreCheckpoint` (`auto = true`, comment `"agent edits (auto)"`).
- **Restore.** Restoring an entry restores its circuit text, `elementIds`, `openMarks` and `viewTransform` ([§03_02](#SP_AGA_03_02)).

Errors: `nothing_to_undo`, `nothing_to_redo` (fewer entries than `steps`; nothing is undone); `unknown_checkpoint` (not in the undo stack); `busy`.

### 02_14. Files: openFile / saveFile / exportCircuit  {#SP_AGA_02_14}

| Contract | Input | Output (`data`) |
|----------|-------|-----------------|
| openFile | `path: string`, `into: "new" | DocumentHandle = "new"`, `activate: bool = false` | `{doc, elements: int}` |
| saveFile | `doc?`, `path: string?` (absent = document's current path), `format: "text" | "json"?` (default from extension: `.json` → json, else text) | `{path, bytes: int}` |
| exportCircuit | `doc?`, `format: "text" | "json" = "json"` | `{content: string}` (JSON element keys = element IDs) |

Rules:
- **File access.** `openFile` and `saveFile` access the file system through a new path-based read/write adapter ([§03_09](#SP_AGA_03_09)) and obey the file rules there.
- **Opening into a document.** `openFile` with a handle behaves as `importCircuit` into that document (agent transaction), except for model lines and JSON `models`: those load like a user load and may overwrite session model entries, with restorers on rejection ([§03_11](#SP_AGA_03_11)). With `"new"` it creates a document, loads the file like a user load (the undo history is reset and seeded with the loaded state) and opens no transaction.
- **After a successful `openFile`.** The document's file path and title are set, and its modified flag is cleared; this clear is applied last and takes precedence over the common modified-flag rule.
- **Rejected `openFile`.** A file whose content is not a circuit ([§03_09](#SP_AGA_03_09) circuit test: `file_not_allowed`) or fails to load (any `error` import issue, [§03_04](#SP_AGA_03_04)) is rejected: with `into: "new"` no document is created and the visible tab does not change; with a handle the document is unchanged. Issues follow the no-content-disclosure rule of [§03_09](#SP_AGA_03_09).
- **Before saving.** `saveFile` seals the open transaction automatically.
- **After a successful `saveFile`.** The file path and title are set, and the modified flag is cleared.

Errors: `file_unavailable` (no desktop runtime); `file_not_allowed` ([§03_09](#SP_AGA_03_09)); `file_not_found`; `file_error` (read/write failure; the message carries the system reason); `no_path` (save without a path on a never-saved document); `busy` (`openFile` into a busy document).

### 02_15. listModels  {#SP_AGA_02_15}

Purpose: list the session's models ([§01_13](#SP_AGA_01_13)).

Input: `kind?: "diode" | "transistor" | "logic" | "subcircuit"`, `name?: string` (requires `kind`; `name` without `kind` is `invalid_value` naming `name`). `doc` is not an argument: the call is session-scoped, like `listTypes`.

Output: `data: {models: ModelRecord[]}`.
- **Without `name`.** Every listed (non-internal) entry of the kind, or of all kinds when `kind` is absent; ordered by kind (`diode`, `transistor`, `logic`, `subcircuit`), then built-in first, then by name in code-point order. No cap (bounded by the catalogues).
- **With `name`.** That one entry; `unknown_model` naming `name` when the kind has no such entry or the entry is internal.
- **`usedBy`.** Covers every open document and counts references through subcircuit models ([§03_11](#SP_AGA_03_11) Dependencies).

Errors: `invalid_value`; `unknown_model`.

### 02_16. checkLayout  {#SP_AGA_02_16}

Purpose: on-demand drawing check of the texts: values, labels, chip pin names and element captions crossed by wires or leads, lying over symbols, or touching each other ([§03_13](#SP_AGA_03_13); [SP_AGA_DEC_09](#SP_AGA_DEC_09), [SP_AGA_DEC_10](#SP_AGA_DEC_10)). It is a separate call: no mutation delta and no `getConnectivity` computes or lists its issues.

Input: `doc?`, `includeBoxes: bool = false`.

Output: `data: {issues: Issue[], texts: int, truncated: bool, boxes?: TextBox[]}`.
- **`issues`.** The codes of the [§03_13](#SP_AGA_03_13) table, with the Issue shape and keys of [§01_07](#SP_AGA_01_07). Sorted by severity, then by key. At most 100 issues are listed; `truncated` is then true.
- **`texts`.** The number of texts the rules checked. Live and not-covered texts are not counted.
- **`boxes`** (only with `includeBoxes`). TextBox fields:

  | Field | Type | Description |
  |-------|------|-------------|
  | element | ElementId | The owner |
  | text | string | The string, cut at 32 characters with `…` |
  | box | `{x1, y1, x2, y2}` | Ink box in cells, rounded outwards to whole pixels (exact 1/16 multiples) |
  | anchor | CellPoint | The placement's anchor point, unrounded, in cells |
  | align | `"left"` \| `"center"` \| `"right"` | Horizontal alignment at the anchor |
  | baseline | `"alphabetic"` \| `"middle"` \| `"top"` \| `"bottom"` | Vertical alignment at the anchor |
  | font | string | The canvas font string of the placement (for example `normal 12px sans-serif`) |
  | live | bool | The text shows a simulated quantity ([§03_13](#SP_AGA_03_13)) |

  Element list order, then layout order. Live texts are included and marked. Transient texts drawn only for a highlighted element are not included. At most 2000 boxes are listed; `truncated` is then true.

Processing logic:

    FUNCTION checkLayout(doc, includeBoxes):
        within the document scope of doc (its options apply, §03_08):
            FOR each element in list order:
                IF the element's class is not covered: count it for text_not_covered; CONTINUE
                element.layoutTexts(measuring layout, highlighted = false)   // no drawing, no state change
            bodies, pairs ← symbol_overlap body model and reported pairs of doc (§03_05)
            apply the rules of §03_13 to the checked texts; one issue per element pair
        cap issues and boxes; return

- **Not highlighted.** Every element is laid out as not selected, not hovered and not being dragged, whatever the editor shows. The selection and the mouse therefore never change the result.
- **Read-only.** It changes nothing: no drawing, no analysis, no bounding box, no undo entry, no modified flag. It is served while the document is busy (an agent run): layout reads only element properties, geometry and options.
- **Cost.** One canvas `measureText` per text (2–5 µs measured headless, 2026-10-04), with no draw; in the draft-compiled build the layout itself (value formatting, placements) costs about 20 µs per text and the body model about 8 µs per element. Measured in PL_AGA Phase 16a: 6.4 ms at 100 and 97 ms at 2500 elements of the measurement mix ([§05_01](#SP_AGA_05_01) cost row). No element bound applies. For a background document, the scoped bind ([§03_08](#SP_AGA_03_08); 0.6 ms on average, 3 ms worst) comes on top.
- **MCP.** Tool `circuit_layout` (read-only, idempotent; argument `includeBoxes?`), a new tool of [SP_MCP §02_02](./mcp-server.sp.md#SP_MCP_02_02), with `toolsVersion` 1.2. A result over the MCP size limit is re-executed with `includeBoxes: false` ([SP_MCP §03_04](./mcp-server.sp.md#SP_MCP_03_04)).

Errors: only the common ones of [§02](#SP_AGA_02): `unknown_document` (Document rule) and `not_ready` (Readiness rule).

## 03. Validation Rules  {#SP_AGA_03}

### 03_01. Geometry  {#SP_AGA_03_01}

- **Lattice.** Coordinates are within ±4096. In `add`, `move` and `by` they are multiples of 0.5; in `importCircuit` and `openFile` content they are multiples of 1/16 (whole pixels). A coordinate off the lattice that applies is `off_lattice` (error); fractional pixel values in JSON v2 text are detected as `off_lattice` before any conversion to whole pixels.
- **Zero length.** `end = start` gives `zero_length` (error).
- **Axis-bound elements.** For an element the editor places only horizontally or vertically (`noDiagonal`; Transformer and CustomTransformer excepted, their `end` being a box corner), an `add`, a `move` with `start` and `end`, or an AgentCircuit import whose `end` is neither on the row nor on the column of `start` gives `not_axis_aligned` (error). Elements the editor lets the user place diagonally (for example the wattmeter and three-phase motor) stay accepted; their posts then lie off the lattice.
- **Collapsed posts.** An `add`, a `move` with `start` and `end`, or an AgentCircuit import that puts an element's posts on fewer distinct points than its default placement gives `zero_length` (error).
- **Replaced end.** When the element replaces a supplied `end` with its own (CustomTransformer, potentiometer, SCR, triac), the edit applies and reports `value_adjusted` (warning) with the effective end.
- **No adjustment.** Input geometry is never snapped or rounded.
- **Pinned grid size.** Geometry computation inside Agent API operations runs with the editor grid size pinned to the target document's own grid option — 16, or 8 when that document's options select the small grid — (with its mask and rounding values) and restored afterwards; the catalogue is always measured at 16. An import pins the option the loaded content selects: after its options line for legacy text, `display.small_grid` for JSON v2, and 16 for AgentCircuit or JSON without that setting. TypeInfo `defaultSize` and `derivedPostsAtDefault` describe 16-grid documents only; ElementRecord `posts` is authoritative. The user's current display preference of another tab never applies, and the document's own option is the one a later reload applies, so posts do not move on undo or reload. The element classes that size themselves from the editor grid size are the potentiometer, SCR, triac, tapped transformer, transmission line, wattmeter and real op-amp (its rail posts) elements; the pin applies to every element class, so a class that starts reading the grid size later is covered too. User paths (user undo, reload) keep today's behaviour and size those elements by the user's grid preference.

### 03_02. Identity and pin names  {#SP_AGA_03_02}

> **Criticality:** critical

**Pin names**
- An element's PinName list comes from its JSON pin names.
- Characters outside `[A-Za-z0-9_~+-]` become `_`, and an empty name becomes `pin<i>` (1-based).
- A name that repeats within the element gets `_<k>` on its k-th occurrence, for k ≥ 2 (for example `Q`, `Q_2`).
- The same names appear in TypeInfo, ElementRecord, PostRef and issues.
- Polar names state the real polarity ([SP_AGA_DEC_06](#SP_AGA_DEC_06)): two-post voltage sources `minus`, `plus` (post 1 is driven `voltage` above post 0); current sources `in`, `out` (the current leaves the source at `out`, the arrow head); the ohmmeter `com`, `probe`; the transformer `p1`, `s1`, `p2`, `s2` (windings p1–p2 and s1–s2, `p1`/`s1` in phase; JSON 2.0 `pri1`, `pri2`, `sec1`, `sec2` are import aliases of posts 0..3); op-amps `in-`, `in+`, `out` whatever `swap_inputs` (which moves the drawing, not the electrical role); FETs ([SP_AGA_DEC_08](#SP_AGA_DEC_08)) `gate`, `source`, `drain` for N-channel and `gate`, `drain`, `source` for P-channel (`PMOS`, `PJFET`: post 1 is the drain), plus `body` as post 3 of a MOSFET with `body_terminal`. The superseded names (`positive`/`negative` of sources, `probe+`/`probe-`) are not accepted in PostRefs. Op-amps are the exception: an op-amp with `swap_inputs` used the same two names with crossed meaning, so an old reference `in+` now names the real non-inverting input instead of failing.

**ID form**
- A supplied `id` must match the ElementId pattern, otherwise `id_invalid`. It must be unused in the document, otherwise `id_taken`.
- An ID matching `^([A-Za-z]+)([0-9]+)$` has the *counter prefix* group 1 and the *number* group 2. Any other ID has no counter.

**Generated IDs**
- A generated ID is `<idPrefix><n>`, where `n` = counter[`idPrefix`] + 1. An `n` whose ID is already present is skipped (`n` increments until free).
- `idPrefix` is letters only: the element's own prefix where it defines one (`R`, `C`, `L`, `W`, `GND`, `V`, `I`, `D`, `LED`, `Z`, `U`, `M`, `K`, `T`, `SW`); otherwise the first three letters (A–Z, digits dropped) of the type name, upper-cased. The runtime default before the Agent API took the first three characters, digits included (`CC2`); dropping digits is behaviour change 10 of [§06_01](#SP_AGA_06_01).
- Within one call, every supplied or restored ID raises its counter before any ID is generated.

**Counters**
- Counters are per document. Within one content lifetime ([§04_03](#SP_AGA_04_03)) they never decrease.
- Whenever an ID with a counter enters the document, by any means, `counter[prefix] ← max(counter[prefix], number)`. The ways in: an add, an import, an undo/redo restore, a paste, or a load.

**One scheme**
- The runtime ID registry is the single source of element IDs. The JSON exporter uses it for element keys, replacing its own global counter, and the existing scripting global uses it for its ID lookups.

**Content replacement (import, open into a document, clear)**
- Counters reset first.
- AgentCircuit and JSON v2 content keep their IDs. A JSON key that does not match the ElementId pattern or repeats is replaced by a generated ID with an `ids_regenerated` warning; elements created by auto-wires receive generated IDs.
- Legacy text content receives generated IDs in file order.

**Undo/redo restore**
- After the circuit text is restored, element `i` receives `elementIds[i]`.
- When the restored element count differs from the `elementIds` length, IDs are regenerated in order, an `ids_regenerated` warning is written to the session log, and the warning is returned in the issues of the undo/redo contract that caused it.

**Paste, duplicate and subcircuit expansion**
- These create elements with generated IDs.

### 03_03. Properties  {#SP_AGA_03_03}

- **Key source.** A type's property keys are the keys its fresh instance exports, plus its declared conditional keys.
  - Conditional keys are keys an element exports only when they differ from their default, such as a capacitor's series resistance.
  - Every such element declares each conditional key with its default value. This is a new element contract consumed only by the catalogue and `set`.
- **Unknown keys.** Keys outside the type's PropertyInfo list are rejected with `unknown_property`; `hint` lists the valid keys.
- **`quantity` and `number` values.**
  - A value is either a number or a string that parses fully as a number with an optional SI prefix and an optional unit suffix matching `unit`. Matching is case-sensitive, except that `Ohm` and `Ω` are both accepted for resistance.
  - The same string may be wrapped in one pair of double quotes (`"\"10 ms\""`, as some agent hosts double-encode number-or-string arguments); the quotes are dropped. This rule covers every number-or-string argument (properties, run `span`/`recordFrom`/`settle`, time steps).
  - Anything else is `invalid_value`. The parser's "0 on failure" result is never taken as a value.
- **Model names.** A `model` (diode family, transistor) or `model_name` (`CustomLogic`, `Subcircuit`) value in `add`, `set` and `importCircuit` content must name a listed entry of the matching kind ([§01_13](#SP_AGA_01_13); for `ZenerDiode` one with `breakdown_voltage` > 0), a model defined by an earlier `defineModel` of the same batch, an entry of the AgentCircuit or JSON `models` of the same content, or a model defined by a model line of the same text content; otherwise `invalid_value` naming the key, `hint` listing the available names (at most 40, then a count and listModels), and the name is never registered (no session copy, no fallback entry). `openFile` loads such a file as the editor does and reports `value_adjusted` (warning) per element instead: a diode or transistor keeps its fallback model (its temporary catalogue entry removed), a `CustomLogic` keeps the session model of that name that the editor's load creates — from a text line an empty model (inputs `A`, `B`, outputs `C`, `D`, no rules; before behaviour change 26 of [§06_01](#SP_AGA_06_01) such a line threw and was skipped), from JSON a copy of its previous model — as in a user load, a `Subcircuit` keeps the editor's fallback; an `openFile` + `saveFile` round trip therefore writes what the element kept. User file loads are unchanged.
- **`bool` and `text` values.** `bool` takes `true`/`false` only. `text` takes strings of at most 1000 chars.
- **Ranges.** The Agent API declares no validity ranges of its own. A value the element itself clamps or adjusts is applied as adjusted and reported with `value_adjusted` (warning) carrying the effective value; slider seeds are never used as limits.

### 03_04. Atomicity  {#SP_AGA_03_04}

> **Criticality:** critical

- **Validate first.** A whole batch, or a whole AgentCircuit import, is validated before the first change.
- **Text and JSON imports.** Content that can only be validated by loading it is loaded after a snapshot. Any loading error restores the snapshot (circuit text, element IDs, open marks, scope views, view transform, document UI state).
- **Import reporting.** The text and JSON importers report to their caller every item they skipped, failed or adjusted, with its line number (text) or element key/index (JSON); a line whose parsing throws counts as failed. Before the Agent API they only wrote console messages — behaviour change 14 of [§06_01](#SP_AGA_06_01); user loads still pass no report. Codes and severities:

  | Item | Code | Severity |
  |---|---|---|
  | Element the factory cannot create; unknown or unparseable line | `import_element_skipped` | error (rejects the import) |
  | Element naming an unknown model ([§03_03](#SP_AGA_03_03)) | `invalid_value` (importCircuit) / `value_adjusted` (openFile) | error / warning |
  | Scope beyond the 20 slots | `scope_limit` | warning |
  | Auto-wire whose target is missing | `import_wire_skipped` | warning |
  | Invalid simulation setting (kept at its default) | `import_setting_invalid` | warning |
  | Geometry re-applied differently (bounds, `p1`/`p2`) | `import_geometry_adjusted` | warning |
  | JSON element key not a valid or unique ElementId (a new ID is generated) | `ids_regenerated` | warning |

- **Model catalogues.** Text model lines and JSON `models` entries record restorers for the session catalogue entries (diode, transistor, custom logic, composite models) they create (agent `importCircuit`) or create or replace (`openFile`, user loads with a report), the logic entries that a text `CustomLogic` line naming an unknown model creates, and the diode entries that a legacy diode line with a forward drop creates or rewrites (`fwdrop=…`); a model line in the same content for a name such an element line created defines that model (it is not an existing entry); a rejected import runs them in reverse, so no other document sees a model change.
- **Failure during application.** A failure while applying restores the pre-call snapshot, and the result is `ok = false` with `internal_error`.
- **No side effects on rejection.** `ok = false` never leaves a transaction opened by that call and never leaves an undo entry added by that call.

### 03_05. Connectivity issue rules  {#SP_AGA_03_05}

> **Criticality:** critical

These rules are computed on every `getConnectivity` and for the delta of every mutation.

| Code | Severity | Rule |
|------|----------|------|
| dangling_post | error; warning when the post belongs to a one-post element | A post position (wire ends included) that coincides with no other element's post position. Wire closure merges both ends of a wire into one node, so the rule is geometric, not per net. The issue reports that post. Posts with an open mark are exempt |
| post_on_wire_body | error | A post that lies strictly inside a wire segment (on the segment, not at an end) and is not in that wire's net |
| overlapping_elements | warning | Two elements of the same type with identical defining points (either order); or two wires that are collinear and overlap by more than a point |
| no_ground | warning | No ground element, and either the simulator assumes ground at a voltage source (`implicitGround`: no ground, no rail, a voltage source) or no post is referenced to ground by its own element (`hasGroundConnection`: rails, logic inputs, gates, chip outputs, op-amp outputs, one-post sources). A circuit referenced only through rails, logic inputs, gates or chips needs no ground element and raises nothing. `implicitGround` reports whether the simulator assumed one |
| isolated_group | error | Nodes the simulator found unconnected to ground (it would tie them through 100 MΩ). One issue per group, listing its posts. A group whose posts all carry open marks is exempt |
| bad_connection | warning | A post the analysis lists as touching another element's body |
| symbol_overlap | warning | Wires and symbols meet only at posts. Computed from geometry only (posts and defining points, never the draw-time bounding box), in pixels (16 px = 1 cell). Every element except wires, graphic and post-less elements has a *symbol* region and *lines* (leads, drawn like wires), by its geometry kind: `two_point` — line post0→post1, symbol = band of half-width 6 px around the middle 40 px of the segment, never within 8 px of a post; `single` — line start→end (the stem), symbol = disc of radius 8 px around end (text not modelled); `derived` — symbol = axis-aligned rectangle spanning the posts (in a dimension where the posts span less than 16 px, i.e. lie in one row or column, also the defining points); a dimension wider than 16 px shrinks by 8 px per side, one of at most 16 px (a pot's wiper or a switch's control post one cell off the body) becomes a band of ±6 px around the element's axis (the defining points' midpoint, clamped to the posts' span) and is sampled only on that centre line; a start post alone on its side more than 40 px before the other posts (a long transistor base or FET gate) is a lead line and the rectangle starts 40 px before the nearest other post. Reported, one issue per unordered element pair (`elements` = both IDs sorted), by the first of: (1) a post of A strictly inside B's symbol or closer than 2 px to the inside of one of B's lines, not one of B's posts; (2) a wire sample (every 4 px, none within 4 px of a wire end) strictly inside B's symbol; (3) a sample of A's lines (as for wires), of a single's end or of an 8 px lattice of a derived rectangle strictly inside B's symbol. Samples within 2 px of one of B's posts are exempt. `at` is the centre of B's symbol (two_point: the middle, single: end, derived: the rectangle's centre), so the key stays the same while A moves and still overlaps. Wire–wire and lead crossings raise nothing |
| single_label | info | A label text used by exactly one labelled node |
| reserved_label | warning | A label text equal to `gnd`, starting with `$` or starting with `label:`; its net is named `label:<text>` |
| source_or_wire_loop | error | The last analysis reported a voltage-source/wire loop, as a stop or as a recovery-mode warning ([§03_06](#SP_AGA_03_06)) |
| ground_path_no_resistance | error | The last analysis reported a path with no resistance from a rail or logic input to ground, as a stop or a recovery-mode warning ([§03_06](#SP_AGA_03_06)); the culprit is in `elements` |
| wire_loop | warning | The last analysis reported a loop made only of wires ([§03_06](#SP_AGA_03_06)): wire currents are approximated, node voltages stay valid |
| current_source_no_path | warning | A current source (an ohmmeter excepted) for which the analysis found no current path — open, or in series with another current source; the simulator stamps 100 MΩ in its place, so it drives no current |

The text layout issues `text_overlap` and `text_not_covered` are not connectivity issues. Only the separate `checkLayout` call reports them ([§02_16](#SP_AGA_02_16), [§03_13](#SP_AGA_03_13)).

### 03_06. Solver and operation issue codes  {#SP_AGA_03_06}

**Solver messages and codes**
- The simulator keeps the untranslated message key of every warning and stop next to the translated text (before the Agent API it stored only the translated text — behaviour change 11 of [§06_01](#SP_AGA_06_01)). Codes are matched by prefix on that key:

  | Key prefix | Code |
  |---|---|
  | "Singular matrix!" | `singular_matrix` |
  | "Voltage source/wire loop with no resistance!" | `source_or_wire_loop` |
  | "Path to ground with no resistance!" | `ground_path_no_resistance` |
  | "wire loop detected" | `wire_loop` |
  | "Convergence failed!" | `convergence_failed` |
  | "Failed to analyze circuit" | `analysis_failed` |
  | "Matrix error" | `matrix_error` |

- Any other stop gives `solver_stop`. Any other warning gives `solver_warning`.
- **Event list.** The document keeps every warning and stop since its last analysis as an ordered list of `{code, culprit}`, cleared when analysis starts. `Diagnostics.events`, the run's issues and the `source_or_wire_loop` connectivity rule read it; the single current-warning slot is not used for them.

**Recovery mode**
- The simulator's non-convergence recovery is enabled (fixed in code). Under it, the loop, singular-matrix, path and matrix conditions arrive as warnings while simulation continues. A stop still occurs for unrecovered failures.
- Under recovery, a timestep that the simulator forces through without convergence raises a `convergence_failed` event naming the first non-converged element (before the Agent API it wrote only a console line — behaviour change 12 of [§06_01](#SP_AGA_06_01)). A run reports it once.

**Severities and culprit**
- A code reached as a stop is `error`.
- `source_or_wire_loop`, `ground_path_no_resistance`, `singular_matrix`, `matrix_error`, `analysis_failed` and `convergence_failed` are `error` also when they arrive as warnings, because the results are not physically meaningful.
- `wire_loop` as a warning is `warning`: only wire currents are approximated, node voltages stay valid.
- `solver_warning` is `warning`.
- The culprit element is in `elements` when the simulator names one.

**Operation codes**
- All are `error` unless marked otherwise: `not_ready`, `unknown_document`, `unknown_type`, `unknown_element`, `unknown_post`, `unknown_net`, `unknown_property`, `unknown_checkpoint`, `invalid_value`, `value_adjusted` (warning), `off_lattice`, `zero_length`, `not_axis_aligned`, `id_invalid`, `id_taken`, `ids_regenerated` (warning), `scope_removed` (info), `reserved_label` (warning), `busy`, `scope_limit`, `import_schema_invalid`, `import_element_skipped`, `import_wire_skipped` (warning), `import_setting_invalid` (warning), `import_geometry_adjusted` (warning), `nothing_to_undo`, `nothing_to_redo`, `unsaved_changes`, `render_failed`, `file_unavailable`, `file_not_allowed`, `file_not_found`, `file_error`, `no_path`, `internal_error`.
- Model codes ([§03_11](#SP_AGA_03_11)), all `error`: `unknown_model` (`from` or a `listModels` name that names no listed entry), `name_taken` (a definition whose name exists with a different definition, or names an internal entry).
- Run end causes are reported as warnings with the codes `budget_exhausted`, `settle_timeout`, `stop_trigger` and `cancelled`.

### 03_07. Sizing caps and decimation  {#SP_AGA_03_07}

**Probe recording**
- Streaming statistics are kept exactly over all samples.
- The series uses `B = ⌊maxPoints / 2⌋` buckets. Each bucket holds its min and max sample with their times.
- When all buckets are used, adjacent buckets merge pairwise and the bucket width doubles.
- The emitted series lists, per bucket, its min and max samples in time order, so it never exceeds `maxPoints` points and keeps the extremes of the shape.
- Values are emitted with 6 significant digits; times (`t`, `tStart`, `tEnd`) with 9.
- `frequency`, `dutyCycle` and `riseTime` are computed from a second bucket series of the same kind with 4096 buckets: exact up to 8192 samples, at bucket resolution beyond.

**Other caps**
- `getCircuit` pages at `limit`.
- `getConnectivity` lists at most 200 nets and 100 issues, and sets `truncated: true` when it cuts; `netFilter` reads the rest.
- `getDiagnostics` returns at most `limit` log entries; each entry's text is cut at 500 characters.

### 03_08. Document routing  {#SP_AGA_03_08}

> **Criticality:** critical

Every contract acts on its resolved document only, whether or not that document is active.

**Requirement R1 — no disturbance of the active tab.** An operation on a non-active document leaves the following unchanged at every repaint during the call and after it, apart from the active document's own free-running progress. Timing bounds on that progress:
- **Synchronous contracts.** The call's only effect on the active tab is its own execution time on the single event loop.
- **`run` and `render`.** At least one active-tab free-run frame passes between consecutive slices, also between slices of different concurrent operations (a `run` and a `render`, or runs on two documents), and no slice exceeds 20 ms plus one indivisible unit of work: one timestep for `run`; one element's draw or the image canvas allocation for `render`. A unit that alone exceeds the bound (a pathological timestep, a very large image) is not split, and the slice is the only one that exceeds it, except that a `render` restarted because the document's element list changed between its slices allocates again when the image size changed. The completion of an operation (result encoding and the reply, measured at a few milliseconds) runs as one task after the last slice. During a background `run` on the reference fixture (a 10-element RC circuit), the active tab's simulated time per wall second stays at or above 50 % of its idle baseline.
- **Sliders dialog.** It is never rebuilt or visibly redrawn by the call.

The protected state:
- the visible tab;
- the active document's circuit, simulation and view;
- the session UI: menu check items, the time-step, speed, current and power bars, voltage range and colour settings, the sliders dialog and its control rows, the renderer's hint and view transform.

**Requirement R2 — the target behaves as if it were active.** The target document's own state is updated exactly as the same user action on it as the active tab would update it: circuit, options, saved UI state (small grid, dots, speed, bars, voltage range), view transform, sliders/adjustables, hint, modified flag, file name/path, tab title, log buffer and closed-tab history. Exporting a non-active document takes its options from that document's saved UI state, as the existing per-document dump does.

**Session-coupled paths that must satisfy R1/R2.** These are known from code review; the plan's prototype confirms the list:
- the circuit text/JSON exporters' options section;
- load reset defaults and options application;
- adjustable sliders and control rows;
- hint;
- centring on load;
- undo/redo view restore;
- the analysis request;
- the stop state;
- the modified-flag, file-name and title setters;
- simulator console messages;
- the closed-tab dump on close;
- rendering.

**View transform source.** For the active document, the "document's own view transform" is the session renderer's transform. For a non-active document it is that document's saved view transform.

**Mechanism.** How R1/R2 are achieved is [SP_AGA_DEC_04](#SP_AGA_DEC_04): a scoped silent bind of the target document, explicit per-path routing, or a hybrid of the two — resolved as the scoped silent bind (A).

**Free-running loop.** It advances the active running document as before and skips a busy document ([§04_02](#SP_AGA_04_02)).

**Net names and readings.** These come from the target document's own analysed node data, never from the session-wide label registry (which keeps the labels of whichever document was analysed last).

**Tab changes.** No contract switches the visible tab except `activateDocument`, `createDocument(activate: true)`, `openFile(activate: true)` and closing the active document ([§02_02](#SP_AGA_02_02)).

### 03_09. File rules  {#SP_AGA_03_09}

[SP_AGA_DEC_03](#SP_AGA_DEC_03), [SP_MCP_DEC_03](./mcp-server.sp.md#SP_MCP_DEC_03).
- **Adapter.** Path-based reads and writes go through one new adapter for the desktop runtime's file system. It is placed with the existing file-bridge adapters (RULE_ARCH_008). Without the desktop runtime every file contract returns `file_unavailable`.
- **Allowed files.** `path` must be absolute and end in `.txt` or `.json` (case-insensitive). Otherwise: `file_not_allowed`.
- **Reading.** `openFile` reads at most 10 MB. A larger file is rejected with `file_not_allowed`. A leading byte-order mark is dropped.
- **Links and directories.** Symbolic links are followed; the resolved file must also end in `.txt` or `.json`. A dangling link or a missing parent directory gives `file_not_found` for `openFile` and `file_error` for `saveFile`; parent directories are never created. `saveFile` writes to the path it checked: when the resolved target changed between the overwrite check and the write, the save is refused with `file_error`.
- **Circuit test.** A file's content *is a circuit* when:
  - JSON: it parses as JSON and passes the JSON circuit schema validation (`schema.format = "circuitjs"`, `schema.version` starting with `2.`);
  - text: every non-empty line is a line type the text importer recognizes — options (`$`), a known element dump type, scope, hint, adjustable, model, or the lines it deliberately ignores (`%`, `?`, `B`) — with zero unknown lines, and at least one element or options line is present. An element line also carries four whole-number coordinates and whole-number flags after its dump type, so a prose line that happens to start with a dump-type letter is not an element line.
  - The test is a side-effect-free parse: it creates no elements and no documents and writes no model catalogue.

  Format detection by first character alone is not a circuit test.
- **Overwriting.** `saveFile` may overwrite an existing file only when the file is empty (whitespace only counts as empty) or its content is a circuit. Otherwise, and for an existing file over 10 MB: `file_not_allowed`.
- **No content disclosure.** A rejected or failed `openFile` returns one issue per code and severity with counts and line numbers only; issue text never quotes file content and names no element keys.
- **Writing.** The adapter writes to a staging file in the same directory, flushes it to disk, and then renames it over the target; on failure it removes only a staging file it created itself.

### 03_10. Error reporting  {#SP_AGA_03_10}

- **No exceptions for domain failures.** Domain failures are results (`ok = false`), never exceptions.
- **Unexpected exceptions.** An exception caught by the batch-rollback guard of `applyEdits`/`importCircuit` is passed to the application's global uncaught-exception handler after the snapshot is restored, so it is shown and logged as before (RULE_ERR_004). The caller receives `internal_error` carrying the exception message.

### 03_11. Model rules  {#SP_AGA_03_11}

> **Criticality:** critical

- **Scope.** Model catalogues are session-wide, as in the editor: a defined model appears in every document's choices and in the editor's model lists. It is saved inside every circuit file that uses it (text model lines; JSON `models` section, [§03_12](#SP_AGA_03_12)) and comes back with that file. An agent-defined subcircuit model is not written to the browser storage (`subcircuit:` keys); it lives for the session and in the files that use it. Catalogues only grow during a session ([§01_13](#SP_AGA_01_13)).
- **Create-only names.** A definition — a `defineModel` edit, an AgentCircuit or JSON `models` entry of `importCircuit`, a text model line of `importCircuit` content — whose name exists in its kind's catalogue (built-in or user, compared exactly) is accepted without any change when it is identical to the entry, and is otherwise `name_taken`; a name of an internal entry is always `name_taken`. For a `models` entry or a model line of `importCircuit` content, the `name_taken` hint reads "open the file with `openFile` to load its models as the editor does, or rename the model". No Agent API contract changes an existing entry: to change a model, an agent defines a new name and `set`s its elements' `model`/`model_name` to it. Names never alias: a model is registered under one key.
- **Identical.** A definition is identical to an entry when the kind's text model line it produces equals the entry's model line character for character: the same name, flags and serialized values (every field the kind's `dump()` writes, so canonical numbers compare exactly). For a subcircuit `source` the line is that of the model built from the source. A subcircuit's line includes its elements' saved state (capacitor voltages, inductor currents, logic states), so after the source document has been simulated a new build from it is usually no longer identical to the entry built before (`name_taken` for the same name).
- **Loads that re-apply model lines.** Undo/redo, reopening a closed tab, session restore, and any user text or JSON load (file open, paste, import dialog) re-apply the model lines and `models` entries they carry to the session entry of that name, overwriting it — editor behaviour, unchanged; `openFile` loads like a user load and does the same ([§03_12](#SP_AGA_03_12)). Because agent names are create-only and `defineModel` and `importCircuit` never redefine, an agent never causes such an overwrite through them: the content they leave in undo entries carries the same model lines as the session entries. One legacy exception on the agent path: a legacy diode element line without a model name (`d … <fwdrop>`) resolves to the entry `fwdrop=<value>` and rewrites an existing entry of that name whose values differ (the editor's legacy conversion); a rejected import restores it.
- **Editor edits.** An agent never edits an existing entry, so an open editor model dialog is unaffected by agent calls. Agent-defined models are ordinary entries: the user can edit them in the editor's model dialogs, and such an edit affects every document that uses the model (editor behaviour).
- **Atomicity.** `defineModel` and `models` are part of the batch or import: if any edit of the batch or any part of the import is rejected, nothing is defined (validation precedes every catalogue write); if application fails afterwards, every entry the call created is removed by running its restorers in reverse order ([§02_03](#SP_AGA_02_03), [§02_04](#SP_AGA_02_04), [§03_04](#SP_AGA_03_04)). An identical definition changed nothing and needs no restorer; no other document sees an intermediate state.
- **Undo.** `undo` does not remove a defined model: the undo entries of the document carry the model lines of the models its circuit uses, so undo restores the elements together with the models that circuit had; a model created and no longer used stays in the session catalogue (harmless: names are create-only).
- **References.** `model` (diode family, transistor) and `model_name` (`CustomLogic`, `Subcircuit`) must name a listed model of the matching kind ([§03_03](#SP_AGA_03_03) Model names); an unknown `model_name` is `invalid_value` and never registers a model. `choices` lists the accepted names for all four keys.
- **Dependencies.** The models of a document are the transitive closure of the non-built-in models referenced by its elements and by the element dumps of the subcircuit models those elements use (a subcircuit model may use diode, transistor, logic and other subcircuit models). `getCircuit`, JSON `models` and the text exporter list them dependencies first; `usedBy` ([§01_13](#SP_AGA_01_13)) counts a reference through a subcircuit model as a use by the Subcircuit element.
- **No dialogs.** No agent path opens an alert or dialog: logic rules (ModelSpec and ModelText) and subcircuit sources are validated without the editor's alert paths; the editor keeps its alerts for its own dialogs.

### 03_12. Models in JSON v2 files  {#SP_AGA_03_12}

- **Version and section.** The JSON exporter always writes schema version `2.2` (readers accept any `2.x`) and a top-level `models` array only when the circuit uses a non-built-in model. The array holds the circuit's models ([§03_11](#SP_AGA_03_11) Dependencies), dependencies first, each a ModelSpec or a ModelText chosen as `getCircuit` chooses ([§02_05](#SP_AGA_02_05)), without a cap. A 2.1 file, or a 2.2 file without `models`, loads as before.
- **Full definitions.** Files carry full definitions: `from` and `source` in a file's entry make that entry invalid.
- **Agent path.** `importCircuit` of JSON text validates the section before any change and applies the create-only rule (identical entry accepted, otherwise `name_taken`); an invalid entry is `invalid_value` and rejects the import ([§02_03](#SP_AGA_02_03)).
- **User loads and `openFile`.** The importer defines the entries before the elements with the text importer's behaviour for model lines: an entry overwrites the session entry of that name, and when the load is rejected (`openFile`, [§03_04](#SP_AGA_03_04)) every entry it created or replaced is restored. An invalid entry is skipped with a console/log message and no alert; elements that name it take the fallback of an unknown model. `openFile` reports each skipped entry as `value_adjusted` (warning) naming `models[<i>]`, and each element that names it ([§03_03](#SP_AGA_03_03)). A logic entry whose rules do not parse loads the rules before the bad line, as a text `!` line does: a user load (no import report, not agent origin: file open, paste, import dialog, session restore, undo/redo) alerts the parser's message as the text load does ([§06_01](#SP_AGA_06_01) item 25); `openFile` reports it as `value_adjusted`; on the agent path it is `invalid_value` (a new entry) or accepted as identical.
- **Paste and subcircuits-only import.** A paste (`RC_RETAIN`) defines the entries as a user load does. "Import subcircuits only" (`RC_SUBCIRCUITS`) imports only the `subcircuit` entries and the entries they depend on, and no elements; otherwise it behaves as a text subcircuits-only import (the load is finalized the same way).
- **Count.** The `models` section of a JSON v2 text has no count limit, also on the agent path; the limit of 200 applies to AgentCircuit `models` ([§02_03](#SP_AGA_02_03)).
- **In-circuit scopes.** The `Scope` element (text `403`) carries its scope settings in the property `scope`, shaped as a `scopes` entry with element IDs. It is a structured value: agent records omit it ([§02_05](#SP_AGA_02_05)) and an AgentCircuit cannot set it.
- **Versions.** JSON 2.x is in development and carries no compatibility promise between its versions (resolved by the developer, 2026-10-04); no behaviour of other builds with 2.2 files is specified.

### 03_13. Text layout  {#SP_AGA_03_13}

How `checkLayout` ([§02_16](#SP_AGA_02_16)) finds the texts and judges them. Mechanism and timing were resolved by the developer: [SP_AGA_DEC_09](#SP_AGA_DEC_09) (a separate layout function measured with the canvas) and [SP_AGA_DEC_10](#SP_AGA_DEC_10) (a separate call on demand). Delivery is staged as PL_AGA Phases 16a and 16b.

| Code | Severity | Rule |
|------|----------|------|
| text_overlap | warning | A checked text of one element meets another element by rule 1, 2 or 3 below. One issue per unordered element pair |
| text_not_covered | info | Elements of a class whose texts are not laid out yet; their texts are not checked. One issue per class, listing the first 20 of its element IDs in ID order, without `at`. Its key is the code plus those 20 IDs |

These codes come only from `checkLayout`, never from `getConnectivity` or a mutation delta.

**Layout and paint**
- **One placement, two uses.** Every class that draws text has one layout method, `layoutTexts(TextLayout out, boolean highlighted)`. It places each string the element draws. A placement holds:
  - the string and its font;
  - the anchor point in circuit pixels;
  - the horizontal alignment and the baseline;
  - an over-bar flag and a live flag;
  - a `group` number.
- **Painting.** `draw()` paints exactly those placements through a painting layout, and has no other text site.
  - Each group is painted at the point of the draw order where the class drew those texts before. For example, a chip interleaves each pin's name with that pin's drawing, and a probe or a source paints its marks between shapes.
  - The check measures them through a measuring layout and draws nothing; it ignores groups.
- **Shared helpers.** `drawValues`, `drawLabeledNode`, `drawCenteredText` and the chip pin-name placement are split the same way: a layout helper computes the placement, and the painting layout draws it. The painting layout keeps today's draw-time effects (widening the bounding box for the text). The measuring layout has none.
- **Explicit fonts.** Every placement names its font; no text inherits the graphics state.
  - Before PL_AGA Phase 16a, many texts took whatever font was last set:
    - the label texts of `LabeledNode` and `Rail`;
    - the centred texts of gates, inverters, delay buffers, the ohmmeter and the ammeter;
    - switch, relay and three-phase-motor labels;
    - the pin letters of transistors, Darlingtons, SCRs and triacs.
  - The op-amp, comparator, OTA and real op-amp elements left their sign font set. The image's text font therefore depended on the previous element and, offscreen, on where a slice ended. This is a rendering defect, fixed by Phase 16a step 0 ([§06_01](#SP_AGA_06_01)).
- **No rotation.** No element draws rotated text, so a placement has no rotation. A text that an element draws inside a translate or scale gives its anchor and font size in circuit pixels.
- **Pure layout.** Layout reads only:
  - the element's properties and geometry;
  - the results of the last analysis that the drawing shows: a current source's stamped current (its value is shown only when the analysis found a current path) and a potentiometer's stamped resistances;
  - the editor's plot axes (an output or a probe shows `X`/`Y` while it is a plot axis);
  - the document options (Show Values, European resistors);
  - the session language;
  - its `highlighted` argument.

  It writes no field and no other state. Points that `draw()` wrote into fields for its texts are computed as locals in layout, or in `setPoints`: the source mark point of `VoltageElm`, the sign point of `ProbeElm`, the label point of `RelayContactElm`.
- **Highlight.** `highlighted` is the element's `needsHighlight()` or its being dragged: it is selected or hovered, its scope is hovered, or it is the plot's Y element, or it is being placed (drag-created) or dragged. A font that is bold while highlighted (an output's, an audio output's or a data recorder's label) is therefore bold during a drag-create too. A placement made only when `highlighted` is true is *transient*, for example the pin letters of transistors and MOSFETs. Transient placements are never checked or listed. A text whose font depends on the highlight, such as the bold label of a selected data recorder, is checked in its unhighlighted form. `checkLayout` always passes false.
- **Simulated state in fonts.** A font that changes with simulated state is laid out by `checkLayout` as for the unfired state, for example the bold label of a fired stop trigger (`StopTriggerElm`: `needsHighlight() || stopped`). The painting layout passes the actual state.

**Text and box**
- **Text.** One non-transient placement whose string is not empty or whitespace. The element that placed it is its *owner*.
- **Box.** The axis-aligned rectangle around the string's ink, in editor pixels. It uses the canvas text metrics (`actualBoundingBoxLeft`, `…Right`, `…Ascent`, `…Descent`) of one shared measuring context, with the placement's font, alignment and baseline, at the anchor. The over-bar is not part of the box.
- **Session dependence.** Boxes depend on the session's fonts and language, as the drawing does. Keys are compared within one session.
- **Live text.** A placement that shows a simulated quantity is marked live by its layout, and the rules skip it:
  - readings of the ammeter, ohmmeter, voltmeter (probe), test point, wattmeter and decimal display;
  - logic output levels;
  - the sweep generator's present frequency;
  - an output's voltage;
  - a wire's current or voltage.
- **Text elements.** The strings of a text element are texts like any other.
- **Scope elements.** In-circuit scope elements and scope panels are outside the check.

**Coverage**
- **Text site.** A call of `drawString(`, `drawValues(`, `drawLabeledNode(`, `drawCenteredText(` or `fillText` in an element class's source, outside the layout classes. Only calls count: method declarations do not. The paint wrappers in `CircuitElm` count as layout classes until PL_AGA Phase 16b removes them. `PotElm`'s own `drawValues` overload is removed or renamed when `PotElm` is converted.
- **Covered.** A class with a text site reports `textLayoutCovered()` false, and its elements give `text_not_covered`. The class becomes covered only when every text it draws goes through `layoutTexts` and no text site is left. A class with no text site of its own inherits its parent's coverage. A class with no text site anywhere in its hierarchy draws no text and is covered. An element that draws through a delegate element (a subcircuit, `CustomCompositeElm`, through its chip) takes the delegate's layout and coverage.
- **Enforcement.** The live harness's static check `text_sites` fails any class that has a text site and does not report itself not covered.
- **Overridden `draw()`.** A class that overrides `draw()` without calling `super.draw()` must also override `layoutTexts`, or report itself not covered. Examples: `JfetElm` of `MosfetElm`; `Switch2Elm`, `CrossSwitchElm`, `MBBSwitchElm` and `DPDTSwitchElm` of `SwitchElm`. `text_sites` checks this rule too.
- **Stages.** After PL_AGA Phase 16a, the classes that 16a does not convert report `text_not_covered`. At the end of Phase 16b no class does, the paint wrappers (`drawValues`, `drawLabeledNode`, `drawCenteredText` as text sites) are gone. `text_sites` runs in the default harness run (inside `agent_layout`) from Phase 16a on.

**Obstacles**
- Of every element: the symbol region and the lines of the `symbol_overlap` body model ([§03_05](#SP_AGA_03_05)). A wire is one line.
- **Approximate body model.** That model is approximate, not the drawn outline: a band around a two-point symbol, a disc for a one-post symbol, a shrunk rectangle for a derived one. "Cannot drift from the drawing" holds for the texts only. Calibration may flag a text near an op-amp triangle or a chip outline that the drawing clears, or miss one that touches the real outline.
- Graphic elements (text, box, line) and post-less elements are no obstacles, as in `symbol_overlap`.
- A `single` element that has at least one checked text has no symbol region here: its texts stand for its symbol (rule 3). Its stem ends where its own first text box, grown by `PAD`, begins (the text sits at the end of the stem).

**Rules.** `PAD` = 1 px, half the 2 px stroke of wires and leads. For a checked text of element A and an element B ≠ A:
1. **Over a symbol.** The box grown by `PAD` shares an interior point with B's symbol region.
2. **Crossed.** A line of B (a wire, or a lead or stem of B) has a point strictly inside the box grown by `PAD`.
3. **Text on text.** The box and a checked text box of B, each grown by `PAD`, overlap with a positive area. Texts closer than 2 px read as one.

**Exempt**
- A's own symbol, lines and other texts: a label's stem, a chip's pin names inside its own body, a value text next to its own leads.
- A pair that `symbol_overlap` reports. `checkLayout` computes that geometry-only rule itself, so the exemption does not depend on an earlier `getConnectivity`.
- Rule 3 between two text elements: lines of text elements stacked into a paragraph sit closer than 2 px by design.

**Reporting**
- **One per pair.** One `text_overlap` per unordered element pair, from the first finding in rule order (1, 2, 3). Within one rule, the texts of the element with the smaller ID come first, then the texts in layout order. "Smaller" means earlier in the order `elements` is sorted in: string order by UTF-16 code units, so `R10` comes before `R2`.
- **Fields.** `elements` holds both IDs, sorted. `at` is the centre of the found text's box, in cells, at 1/16-cell (whole-pixel) resolution: the centre is rounded to whole pixels. The key is built as in [§01_07](#SP_AGA_01_07). It stays the same while B moves and still meets the same text.
- **Message.** It names the text (cut at 32 characters plus `… (N chars)`), its owner and B: `Text "10nF" of C1 is crossed by W7.`, `… lies over the symbol of U1.`, `… overlaps the text "VREF" of L2.`
- **Hint.** "Give the text clear space: a value text sits beside the middle of its symbol (above a horizontal part, right of a vertical part, left of a vertical source); keep wires and other parts at least one cell from it, move or flip the part, or shorten the label."

**Agreement with the image.** `render` paints the same placements ([§02_08](#SP_AGA_02_08)). For an element that is not highlighted, every text box, times `scale` and shifted by the image origin, is where the image shows that text.

## 04. State Transitions  {#SP_AGA_04}

### 04_01. Agent transaction  {#SP_AGA_04_01}

> **Criticality:** critical

State diagram:

    [none] --agent mutation (ok)--> [open] --agent mutation (ok)--> [open]
    [open] --checkpoint(comment)--> [none]   (entry gets comment + checkpointId)
    [open] --auto-seal trigger--> [none]     (entry gets "agent edits (auto)", auto=true)

Transition rules:
| From | To | Condition | Side effects |
|------|----|-----------|-------------|
| none | open | First successful agent mutation of the document | If the newest undo entry equals the pre-mutation state and carries no comment (for example the entry seeded after a load), it becomes the transaction's entry; otherwise push the pre-mutation snapshot as a new entry. Clear redo |
| open | open | Further successful agent mutation | `pendingEdits += 1`; no new undo entry |
| open | none | `checkpoint` | As [§02_12](#SP_AGA_02_12) |
| open | none | A user edit of the same document requests an undo push | Seal with auto comment; then the user edit pushes its own entry |
| open | none | Any save of the document (user or agent), `undo`, `restoreCheckpoint`, user undo/redo, content replacement by the user | Seal with auto comment first |
| open | none | No agent mutation for 300 s (wall clock) | Seal with auto comment |
| open | (dropped) | Document closed | The open transaction is discarded with the document |

**Sealed transaction without net change.** When a user edit requests an undo push and the newest undo entry is a sealed agent entry (sealed by that push or earlier: idle, save, content replacement) whose state equals the state being pushed, that entry is dropped and the user edit pushes its own entry in its place, so the user edit always has its own entry and label. The dropped entry's `checkpointId` leaves the undo stack; a later `restoreCheckpoint` with it returns `unknown_checkpoint`.

**Agent origin.** While an Agent API mutation executes, the document is marked *agent origin*. Undo pushes requested by editor code paths reused inside the mutation are suppressed, because the transaction already holds the pre-mutation entry, and they do not count as user edits.

**Menu labels.** The undo/redo menu entries display `Undo: <comment>` or `Redo: <comment>` when the entry they would apply carries a comment.

### 04_02. Document run state  {#SP_AGA_04_02}

    [idle] --run()--> [busy] --run ends--> [idle]
    [busy] --cancel request--> run ends with reason "cancelled" at the next slice boundary

**Free-running.** While a document is `busy`, the free-running loop does not step it, so the run owns stepping. The document's running flag is unchanged and resumes effect after the run.

**Agent calls while busy.** Served or rejected (`busy`) as the contract class table of [§02](#SP_AGA_02) states.

**Cancel requests.** These raise a cancel request before they take effect:
- a user edit (any canvas press counts, since the editor pushes undo on every press; a slider change counts), user undo/redo, or a user run/stop/reset of the busy document, also through the legacy `CircuitJS1` scripting calls (`stepSimulation`, `resetSimulation`, `setSimRunning`);
- `closeDocument(discardChanges: true)`;
- closing the tab.

The user action then proceeds after the run has ended at its next slice boundary; user and script events arrive between slices. A legacy script call made from the user's own `ontimestep` hook during a run of the visible document is the exception: it runs inside the slice, as it already runs inside a free-running step. Activating the tab is not a cancel.

### 04_03. Element ID lifetime  {#SP_AGA_04_03}

A **content lifetime** begins when a document is created or its content is replaced (import, open into the document, clear). It ends at the next replacement or at close.

    [assigned] --move/set/describe--> [assigned]
    [assigned] --undo/redo restoring it--> [assigned]   (same ID; counters raised per §03_02)
    [assigned] --delete--> [retired]                    (its number is never reissued in this content lifetime)
    [any] --content replacement--> new content lifetime: counters reset; IDs per §03_02

## 05. Verification Criteria  {#SP_AGA_05}

### 05_01. Functional Expectations  {#SP_AGA_05_01}

| Contract | Scenario | Input | Expected outcome |
|----------|----------|-------|------------------|
| listTypes | filter | `filter: "mosfet"` | Contains `NMOS` and `PMOS` (matched through their aliases); each type once |
| describeType | Resistor | `"Resistor"` | `geometry=two_point`, pins of length 2, property `resistance` kind `quantity` unit `Ohm` |
| describeType | NPN transistor | `"TransistorNPN"` | `geometry=derived`, 3 pins, `derivedPostsAtDefault` has 3 entries |
| describeType | Capacitor conditional keys | `"Capacitor"` | properties include `capacitance`, `initial_voltage`, `series_resistance`, `back_euler` |
| describeType | flip-flop pin names | D flip-flop type | pins unique; the second `Q`-text pin is `Q_2` |
| describeType | alias | `"Zener"` | TypeInfo of `ZenerDiode`, `aliases` contains `Zener` |
| describeType | unknown | `"Resistr"` | `unknown_type`, hint contains `Resistor` |
| listDocuments / createDocument | create in background | `createDocument()` | New handle; active tab unchanged; listed with `active=false` |
| closeDocument | unsaved | modified document, no flag | `unsaved_changes` |
| closeDocument | last document | only one open, `discardChanges` | ok; `replacement` handle returned |
| importCircuit | RC divider in cells | 4 ElementSpecs incl. Ground, whole cells | `ok`, IDs as supplied/generated, `connectivity.errorCount = 0` |
| importCircuit | off-lattice point | `start.x = 3.3` | `ok=false`, `off_lattice`, document unchanged |
| importCircuit | round trip | `getCircuit(full)` output re-imported | identical records |
| importCircuit | broken text | legacy text with an unknown dump code | `ok=false`, `import_element_skipped`; document unchanged (snapshot restored) |
| applyEdits | add + set in one batch | add `R_load`, then set `R_load.resistance="4.7k"` | `ok`; record shows `resistance="4.7 kOhm"` |
| applyEdits | partial set keeps others | Capacitor with `series_resistance=1`, then set only `capacitance` | `series_resistance` still 1 |
| applyEdits | dangling wire end | add a wire from R1's post whose far end touches nothing | delta `added` contains `dangling_post` naming the wire's far post (although the wire's node also holds R1's post) |
| applyEdits | invalid in batch | 3 edits, the second with an unknown property | `ok=false`, `unknown_property`, no edit applied, no undo entry |
| applyEdits | post on wire body | wire (0,0)-(4,0), resistor posted at (2,0) | `post_on_wire_body` (error) |
| applyEdits | move by | `move` `by: {dx: 2, dy: 0}` | start and end shift by 2 cells; record returned |
| applyEdits | move by off lattice | `by: {dx: 0.3, dy: 0}` | `off_lattice` |
| applyEdits | describe | `describe` with text | record `description` equals text |
| applyEdits | markOpen | mark the dangling post open | delta `cleared` contains its `dangling_post`; post record `open=true` |
| applyEdits | add/removeScope | addScope then removeScope on R1 | Scope view count +1 then −1 |
| applyEdits | delete in scope | delete an element with a scope view | `scope_removed` info issue |
| getConnectivity | netFilter | `netFilter: ["out"]` | only net `out` in `nets`; all issues present |
| getConnectivity | labelled ground | ground net carrying label `0V` | net name `gnd`, `labels` contains `0V` |
| read | unknown label | `net:"vout"` absent | `unknown_net`, no value |
| simControl | configure | `maxTimeStep: "1 us"` | `timeStep.max = 1e-6` after the next analysis |
| simControl | invalid | `minTimeStep` > `maxTimeStep` | `invalid_value` |
| run | span | RC (τ = 1 ms), `span="5 ms"`, probe on the capacitor net | `reason=span_reached`; `stats.final` within 1 % of 0.993 × source |
| run | settle | DC divider | `reason=settled` before `maxSpan` |
| run | shorted source | voltage source shorted by a wire (warning raised at edit-time analysis) | ends `span_reached` or `solver_stop`; `issues` contains `source_or_wire_loop` (error) naming the source |
| run | forced non-convergence | circuit that does not converge under recovery | `issues` contains `convergence_failed` (error) naming an element |
| getConnectivity | parallel wires | two wires in parallel between the same posts | `wire_loop` (warning) at most; no error |
| getConnectivity | reserved label | label text `gnd` on a non-ground net | net named `label:gnd`; `reserved_label` warning |
| saveFile | overwrite prose text | existing `…/notes.txt` with plain prose | `file_not_allowed`; file untouched |
| importCircuit | legacy round trip | `getCircuit(full)` of an example with odd-pixel coordinates, re-imported | `ok`; identical records |
| applyEdits | delta cap | batch creating 80 wires, each with one end on an existing post and the other end free | `connectivity.added` has 50 entries, `truncatedAdded = 30` |
| run | budget | huge span, `budgetMs=200` | `reason=budget_exhausted`, `wallMs` ≤ 200 + one slice |
| run | background document | run on a non-active document | completes; active tab unchanged |
| run | points cap | 2 probes × `maxPoints=1500` | `invalid_value` (Σ > 2000) |
| getDiagnostics | log cursor | `since` = last cursor | only newer entries; `cursor` advances |
| checkpoint | named | after 3 edits, comment "add filter" | `checkpointId=cp1`; `getHistory.undo[0].comment = "add filter"` |
| checkpoint | nothing | no open transaction | `noChanges=true`, undo stack unchanged |
| undo / redo | round trip | undo 1 then redo 1 | states and IDs equal before/after; redo entry carries the comment |
| undo | nothing | empty stack, `steps=1` | `nothing_to_undo` |
| restoreCheckpoint | two checkpoints | cp1, cp2; restore cp1 | circuit equals the pre-cp1 state; IDs equal that state's IDs |
| render | png, background | non-active document | width/height cover its circuit bounds + margin |
| saveFile | json | absolute path `…/x.json` | file loads back via `openFile` with identical element IDs |
| saveFile | not allowed | path `…/notes.md` | `file_not_allowed` |
| saveFile | overwrite foreign | existing non-circuit `…/a.txt` | `file_not_allowed`; file untouched |
| openFile | missing | absent path | `file_not_found` |
| exportCircuit | json keys | — | keys equal `getCircuit` IDs |
| defineModel | LED with a forward voltage | `{kind:"diode", name:"led-green-2v1", parameters:{forward_voltage:"2.1 V", forward_current:"20 mA"}}` then `add` LED with `model:"led-green-2v1"` in the same batch | ok; the LED drops 2.1 V ± 0.02 V at 20 mA in a run; `describeType LED` `choices` contains the name |
| defineModel | name taken | a built-in name (`1N4148`) with other parameters, or a name defined earlier with other values | `name_taken`; nothing applied; catalogues unchanged |
| defineModel | internal name | `{kind:"diode", name:"old-default-led", …}` | `name_taken`; `listModels` still does not list it |
| defineModel | identical redefinition | the same ModelSpec as an earlier successful batch, in a second batch with an `add` using it | ok; `models[0].existing = true`; the entry's model line is unchanged |
| defineModel | batch rollback | `defineModel` + an `add` with an unknown type | `unknown_type`; the model is not in `listModels` afterwards |
| defineModel | rollback on exception | `defineModel` + `add`, with `debugFailNextMutation()` armed | `internal_error`; the model is not in `listModels`; document unchanged |
| defineModel | bad `from` | `from:"nope"`; `from:"old-default-led"` (internal); a transistor name as `from` of a diode; `from` on a `logic` model | `unknown_model` naming `from` for the first three; `invalid_value` for the last |
| defineModel | diode input errors | `forward_current` without `forward_voltage`; `forward_voltage` with `emission_coefficient` only; `breakdown_voltage:"-5 V"`; `emission_coefficient:"2 V"` (unit on a number key); a key `foo` | `invalid_value` naming `parameters.<key>` for each; `unknown_property` for `foo` |
| defineModel | diode record re-import | the `parameters` of a simple-form model's ModelRecord (four core keys + `forward_voltage`/`forward_current`) under a new name | ok; the new model line equals the original's except the name (flags SIMPLE, same forward current) |
| defineModel | transistor | `{kind:"transistor", name:"bjt-lowbeta", from:"default", parameters:{early_voltage_forward:"100 V"}}` | ok; `listModels` record shows `early_voltage_forward` 100 V and the other keys of `default` |
| defineModel | transistor infinite | `early_voltage_forward:"inf"`, `knee_current_forward:"inf"` | ok; record shows `"inf"` for both; model line has inverse 0 |
| defineModel | logic | inputs `[A,B]`, outputs `[Y]`, rules `["11=1","??=0"]` (AND) | ok; a CustomLogic with `model_name` set computes AND in a run |
| defineModel | logic bad rule | rule `"1=11"` | `invalid_value` naming `rules[0]`; no alert or dialog opened; nothing registered |
| defineModel | logic limits | 33 inputs; a pin name of 9 chars; duplicate input names; a pin name `CLK` (empty after markup); 257 rules; a rule line of 101 chars; `info` of 201 chars | `invalid_value` naming the field (`inputs`, `inputs[<i>]`, `rules`, `rules[<i>]`, `info`); nothing registered |
| defineModel | logic pin names | inputs `[D, CLK:C]`, outputs `[Q, /Q]`, a CustomLogic using it | element pins `D`, `C`, `Q`, `Q_2`; the record keeps `CLK:C` and `/Q` |
| defineModel | subcircuit | `source:{doc}` of a document holding an RC with labels `in`, `out` | ok; record pins `pin1`↔`in`, `pin2`↔`out`; a `Subcircuit` with that `model_name` behaves as the RC; the source's selection, circuit text and simulated time are unchanged |
| defineModel | subcircuit source errors | `source` an unknown handle; a source during an agent run; the target document itself; a source with no labels; a label on ground; a label no element uses; unconnected internal nodes (a floating part in a grounded source; two floating parts in a source without ground); two labels `a`, `b` on one node | `unknown_document`; `busy`; `invalid_value` naming `source` with the reason for each of the others; no alert or dialog; source document unchanged. One floating part in a source without ground is accepted; on all three unconnected-node sources the editor's "Create Subcircuit" decides the same (it alerts "Some nodes are unconnected!" where the agent rejects) |
| importCircuit | ModelText errors | ModelText with two lines; first token `32` with `kind:"diode"`; name token ≠ `name`; `name:"~x"`; a logic line whose rules hold `1=11` | `invalid_value` naming `modelText` (or `name`); no alert; nothing registered |
| importCircuit | models rollback | AgentCircuit `models` [new `A`, `B` = an existing name with other values] | `name_taken`; `A` not in `listModels`; document unchanged |
| importCircuit / getCircuit | round trip with models | a circuit with a defined diode model | `getCircuit` returns it under `models`; importing that form into a new document after the model is gone from the session (fresh app) restores it |
| importCircuit | same-session re-import | the `getCircuit` form of a document using defined diode, logic and subcircuit models, imported into a new document of the same session | ok; every entry identical (no catalogue change); elements equal |
| getCircuit | ModelText fallback | a document using an editor-made diode model `fwdrop=0.8` and a logic model with a 9-char pin name | both emitted as ModelText; the form re-imports in the same session (identical) |
| getCircuit | dependency closure | a Subcircuit whose model's elements use a defined diode model | `models` lists the diode before the subcircuit; present on the `offset = 0` page only; `listModels` diode `usedBy` lists the Subcircuit's ID |
| listModels | kinds and order | no arguments, in a fresh session where no CustomLogic was used | entries of all four kinds, ordered by kind, built-in first, then by name; no internal entry (`~lm317-dz`, `old-default-led`); logic `default` listed with `builtIn: false`, inputs `A`, `B`, outputs `C`, `D`, no rules |
| defineModel | logic `default` | `{kind:"logic", name:"default", inputs:[A,B], outputs:[Y], rules:["11=1"]}` in a fresh session | `name_taken` (the ensured `default` entry differs) |
| listModels | name | `kind:"diode", name:"1N4148"`; `name` without `kind`; `kind:"diode", name:"nope"` | one record, `builtIn: true`; `invalid_value` naming `name`; `unknown_model` |
| undo | defined model kept | batch `defineModel` + `add` using it, then `undo`, then `redo` | elements gone then back; the model is listed throughout and its model line never changes |
| set | unknown model_name | `CustomLogic` `model_name:"nope"` | `invalid_value` with the available names; no model registered |
| openFile | unknown logic model | a text file, and a JSON file, whose CustomLogic names a model the file does not define | ok for both; `value_adjusted` for that element; text: an empty model (inputs `A`, `B`, outputs `C`, `D`) is created under that name; JSON: a copy of the element's previous model |
| importCircuit | legacy text, CustomLogic naming an unknown model | text content with a CustomLogic line naming `nope` and no `!` line | `invalid_value` naming the element; `listModels {kind:"logic", name:"nope"}` → `unknown_model` |
| importCircuit | legacy text with a differing model line | text content with `! default …` whose rules differ from the session's `default` | `name_taken` with the hint "open the file with `openFile` to load its models as the editor does, or rename the model"; catalogues unchanged |
| importCircuit | unknown inner model | a subcircuit ModelText whose element dumps hold a `CustomLogic` naming `nope` | `invalid_value` naming `modelText` ("inner model nope unknown"); catalogues unchanged |
| importCircuit | subcircuit with no pin | a subcircuit ModelText, and a legacy text `.` line, with an empty pin list | `invalid_value` ("a subcircuit needs at least one pin"); nothing registered; catalogues unchanged |
| defineModel | diode record mismatch | record form with `forward_voltage` 1 % off the derived drop; record form with `forward_current` and `series_resistance:"1 Ohm"` | `invalid_value` naming `parameters.forward_voltage` with the simple-form hint; `invalid_value` naming `parameters.forward_current` |
| exportCircuit / saveFile json | models section | a document using a defined model | JSON 2.2 with `models`; reopening the file defines the model |
| user load (JSON) | invalid models entry | a JSON 2.2 file whose entry has `kind:"foo"`, or `from` | loads; a console/log message; the elements naming it fall back; no alert |
| user paste (JSON) | models in a paste | paste of a JSON 2.2 fragment with a `models` entry the session lacks | the entry is defined; the pasted element uses it; one undo removes the elements (the entry stays) |
| user import (JSON) | subcircuits only | "Import subcircuits only" of a JSON 2.2 file with a subcircuit model that uses a diode model, plus other entries and elements | the subcircuit and diode entries are defined; no other entry and no element is imported |
| checkLayout | text crossed by a wire (live series T8) | Capacitor `C1` (0,0)→(0,4) `capacitance:"10 nF"`; Wire `W1` (1,−1)→(1,5), one cell right of the capacitor through its value text | one `text_overlap` (warning), `elements` [`C1`, `W1`], message contains `10nF` and "crossed"; `at` = centre of that text's box (right of the capacitor's middle); `getConnectivity` and the import's delta list no `text_overlap` |
| checkLayout | key stability | the T8 fixture; `move W1 by {dx:3}`, then `checkLayout`; `move W1 by {dx:−3}`, `checkLayout`; `move W1 by {dy:0.5}`, `checkLayout` | none; then the same key as before; then still that key (`at` follows the text, not the wire); no mutation delta contains `text_overlap` |
| checkLayout | clean value texts | `R1` Resistor (0,0)→(4,0) `resistance:"4.7k"` and a wire (−3,0)→(0,0); `C1` Capacitor (4,0)→(4,4); a vertical wire (7,−2)→(7,6), three cells right of `C1` | no issue |
| checkLayout | label on label (live series T9) | LabeledNodes pointing up: `L1` (0,0)→(0,−1) `label:"VOUT_MAIN"`, `L2` (2,0)→(2,−1) `label:"VREF_MAIN"`; again with `L2` at (5,0)→(5,−1) | one `text_overlap` [`L1`, `L2`], message "overlaps the text"; five cells apart: none |
| checkLayout | label text across a lead (live series T3) | `L1` LabeledNode (0,0)→(1,0) `label:"input"`; `R1` Resistor (2,−6)→(2,2), whose lower lead passes through the text | one `text_overlap` [`L1`, `R1`], "crossed by R1"; `getConnectivity` has no `symbol_overlap` for the pair |
| checkLayout | text over a symbol | `L1` as above; Ground `G1` (2,−1)→(2,0), whose symbol lies in the text | one `text_overlap` [`G1`, `L1`], "lies over the symbol of G1" |
| checkLayout | chip pin names | `DFlipFlop` `U1` with every pin wired two cells straight out; then a wire across `U1`'s body | first: no issue, and `includeBoxes` lists one box per pin name, inside `U1`'s body; second: no `text_overlap` for the pair (`getConnectivity` reports its `symbol_overlap`) |
| checkLayout | own drawing exempt | a 2-cell vertical capacitor with value `"4.7 uF"`; labels on 1-cell stems in four directions; an op-amp with its `+`/`−` signs | no issue |
| checkLayout | live texts | `Output` with `show_voltage:true`, `Probe`, `Ammeter` and a wire showing its current, each with its reading across another wire; `run` 5 ms; `checkLayout` again with `includeBoxes` | no `text_overlap`; equal results before and after the run; the readings are listed with `live: true` and `texts` does not count them (in Phase 16a, the classes not converted yet give `text_not_covered` instead) |
| checkLayout | values hidden | the T8 fixture as legacy text whose options line hides values (flag 16) | no issue; the SVG `render` has no `10nF` text |
| checkLayout | highlight does not count | the T8 fixture plus a `TransistorNPN` with a wire past its pin-letter positions, an `Output` and a `DataRecorder`; `checkLayout includeBoxes` with nothing highlighted; again with the transistor hovered (its pin letters drawn) and with the Output and the DataRecorder selected. Both are set through the harness-only `debugSetHighlight(handle, id, "hover" \| "select")`, which sets the editor's hovered element or the element's selected flag in that document and repaints. The SVG `render` shows the pin letters while hovered | equal issue keys and equal `boxes` in all three reads; no box for a pin letter |
| render | explicit fonts | a `LabeledNode`, a `Gate`, an `Inverter`, a `Switch` with a label and a `TransistorNPN`, each listed once after an `OpAmp` and once before it; SVG and PNG renders, and the visible tab; PNG at one scale without forced slice breaks and with the harness-only `debugRenderSliceElements(1)` (a slice break after every element) | each SVG text's font equals its placement's `font`, whatever the element order and the slices; the label and centred texts use the units font; the two PNGs have equal pixels |
| checkLayout | layout equals drawing, every type | one default element of every catalogue type (as the `synth` scenario does) in four directions, plus option variants: `MosfetElm` with show-Vt, `PotElm` with its show-values flag, switches and relays with labels, `TextElm` with two lines and an over-bar, `Output` with `show_voltage`, a wire showing its current; the examples `555int.txt`, `counter.txt` and `alu74181.txt`. SVG `render` and `checkLayout includeBoxes`, nothing highlighted | for every SVG `<text>` there is a box with the same string. Its `anchor` equals the SVG `x`/`y` within 0.5 px, after the SVG group transforms are applied. Its `align` and `baseline` match the SVG attributes through the canvas2svg mapping (`war/canvas2svg.js`):
- `left`/`center`/`right` ↔ `start`/`middle`/`end`;
- `middle` ↔ `central`, `top` ↔ `text-before-edge`, `bottom` ↔ `text-after-edge`.

Its `font` equals the SVG font by parsed style, weight, size and family. Equal counts per element. A type with SVG texts and no placements reports `text_not_covered`. At the end of PL_AGA Phase 16b no type does |
| checkLayout | text_not_covered | Phase 16a: a `PolarCapacitor` (not converted) with a wire through its value text; the value comes from the covered `CapacitorElm` layout, but the class has its own text site. After Phase 16b: a `Resistor` with the harness-only `debugForceNotCovered("Resistor")` and a wire through its value text | one `text_not_covered` (info) naming the class and the element's ID, no `at`; no `text_overlap` for that element's texts |
| checkLayout | background = visible | the fixtures of the rows above, imported into a background document and into the visible document, read again after the visible tab has drawn | equal issue keys and boxes |
| checkLayout | busy document | `checkLayout` while an agent `run` is in progress on the document | served; the same result as after the run, except the live boxes |
| checkLayout | caps | 120 crossings (120 resistors with a wire through each value text); `includeBoxes` on 2100 resistors | 100 issues listed, `truncated: true`; 2000 boxes listed, `truncated: true` |
| checkLayout | example corpus | every bundled example (`agent_connect_all`, `CIRCUITS=all`, which also calls `checkLayout`) | each example that reports `text_overlap` is in the calibration list of [PL_AGA Phase 16a](./agent-api.plan.md#PL_AGA_P16A) (updated by 16b), and its render shows each reported text crossed or overlapped, except the body-model findings marked as such in that list ([§03_13](#SP_AGA_03_13) Obstacles); no other example reports one; the clean examples of `agent_overlap` report none unless listed there |
| checkLayout | cost | 100 and 2500 elements of the measurement mix (Resistor, Capacitor, LabeledNode, TransistorNPN, OpAmp), headless, on the visible document (no bind); then the same on a background document | visible: median of 5 runs ≤ 8 ms at 100 and ≤ 120 ms at 2500 elements (measured in PL_AGA Phase 16a: 6.4 ms and 97 ms; draft-compiled build); background: the same plus the scoped bind (≤ 3 ms; measured 5.9 ms and 94 ms). `importCircuit`, `applyEdits` and `getConnectivity` on these circuits stay within 10 % of their median before Phase 16 (5 runs, same machine and Chromium) |

### 05_02. Invariant Checks  {#SP_AGA_05_02}

| Invariant | Verification method |
|-----------|-------------------|
| `ok=false` ⇒ document unchanged and session model catalogues unchanged | Compare circuit text, IDs, open marks and the model line of every catalogue entry before/after for every error case in §05_01 (the logic `default` entry, ensured by the first model-related call, exists before the first sample) |
| IDs survive undo/redo | Create, edit, undo, redo; IDs of all elements equal at each step |
| No duplicate IDs after restore | Import R1..R5, undo, redo, add a resistor without ID → `R6` |
| One ID scheme | `exportCircuit(json)` keys = `getCircuit` IDs = the scripting global's IDs |
| Labels are per document | Same label text in two documents: nets are not joined; readings differ per document |
| A user edit never merges into an agent entry | Agent edit (open transaction) → user edit → one undo restores the post-agent state |
| Agent-origin pushes never auto-seal | An agent `delete` that reuses editor delete code leaves the transaction open |
| Background operations never switch tabs | Edit, run and render a non-active document; the active tab is unchanged |
| R1 — no disturbance | Setup: the active tab free-runs the reference fixture with its own small-grid, sliders, hint and bar settings. On a background document, one after another: `importCircuit`, `checkpoint` (so that `undo` reverts only the edit), `applyEdits`, `undo`, `run` (2 s), `render`, `exportCircuit`, `openFile` into it, `closeDocument`. Sample the R1 list after each synchronous call and at every slice boundary of `run`/`render`. Pass: every sample equals the pre-call value; the active tab's simulated time per wall second during the `run` is ≥ 50 % of its idle baseline; no sliders-dialog rebuild is observed |
| R2 — target as if active | Apply the same operation sequence to background document X and to an identical document Y while Y is active. Pass: X and Y end with equal circuit text, saved UI state, view transform, adjustables, hint, title, modified flag and file path. Known difference: a scope's auto-range scales (fields 5–6 of an `o` line) are rewritten whenever the visible tab draws, so the check masks them; the log buffer is compared without the `Save option: SlidersDialog.*` lines, which the visible tab's sliders dialog writes into its own document's log; a `run` in the sequence uses a simulated `span` so both documents step identically |
| No 0-V fallback | `read`/probe of a missing net never returns a value |
| Pin names unique | For every catalogue type, `pins` has no duplicates |
| Grid preference of other tabs has no effect | With Small Grid on in the active tab, `applyEdits add` of a potentiometer in a background document without the small-grid option gives the same posts as with it off; undo/redo and reload keep those posts |
| `checkLayout` changes no state ([§02_16](#SP_AGA_02_16)) | `checkLayout` on the visible document (running, Show Current on, an in-circuit scope) and on a background document: the circuit text (scope lines unmasked), every element's bounding box and current-dot position, the scopes' graph state, the undo depth, the modified flag and the R1 list ([§03_08](#SP_AGA_03_08)) are equal before and after the call |
| Drawing paints the layout ([§03_13](#SP_AGA_03_13) Coverage) | The static harness check `text_sites` scans `element/` for `drawString(`, `drawValues(`, `drawLabeledNode(`, `drawCenteredText(` and `fillText` outside the layout classes. It fails unless every class with such a site reports `textLayoutCovered()` false. It also fails a class that overrides `draw()` without `super.draw()` and inherits `layoutTexts` while its parent has texts. It runs in the default harness run inside `agent_layout` since PL_AGA Phase 16a (also as the scenario `text_sites`). The "layout equals drawing" row of [§05_01](#SP_AGA_05_01) checks the result |

### 05_03. Integration Scenarios  {#SP_AGA_05_03}

| Scenario | Preconditions | Steps | Expected result |
|----------|--------------|-------|-----------------|
| Agent builds while user watches | Two documents, user on d1 | Agent `createDocument` → import into d2 → run d2 | d1 stays visible and unchanged; d2 holds the circuit and its run results |
| User undoes agent work | Agent checkpoint "add stage 2" | User presses Ctrl+Z | Menu showed "Undo: add stage 2"; circuit returns to the pre-checkpoint state; `getHistory` reflects it |
| Free-running during edits | d1 running and active | Agent `applyEdits` on d1 | The reply's connectivity reflects the edit; free-running continues |
| Run owns stepping | d1 running and active | Agent `run` on d1 | The free-run loop does not step d1 during the run; free-running continues after |
| User interrupts a run | Agent run in progress on d1 | User edits d1 | The run ends `cancelled` with partial probe data; the user edit applies after |
| Legacy circuit inspection | Load an example `.txt` | `getCircuit` | Every element has an ID; coordinates in cells; reloading the same file gives the same IDs |

### 05_04. Edge Cases and Boundaries  {#SP_AGA_05_04}

| Case | Input | Expected behavior |
|------|-------|-------------------|
| Batch size limit | 201 edits | `invalid_value` |
| Supplied ID matching generated form | add `R7` to an empty document, then add a resistor without ID | generated ID is `R8` |
| Idle seal | Open transaction, 300 s without agent mutation | Entry sealed with auto comment |
| Scope slots full | 20 views exist, `addScope` | `scope_limit` |
| Closing document during run | Run in progress, `closeDocument(discardChanges: true)` | Run returns `cancelled`; document closed |
| Settle never reached | Oscillator, `mode=settle` | `settle_timeout` after `maxSpan` |
| Legacy coordinates off lattice | Example drawn at odd pixels | Cells reported exactly (fractional); `move` input must still be on the lattice |
| Single-post orientation | Ground with `end` one cell below `start` | Ground drawn pointing down; one post at `start` |
| Checkpoint after ID-only change | Delete + re-add an element with a new ID and the same geometry | Not `noChanges` (IDs differ) |

## 06. Reversibility  {#SP_AGA_06}

### 06_01. Rollback Strategy  {#SP_AGA_06_01}

| Aspect | Rollback approach |
|--------|-------------------|
| Data/state changes | Undo entry extension fields live in memory only. JSON element keys were already free-form IDs, and the text format is untouched. The one file format change is the JSON `models` section with schema version 2.2 ([§03_12](#SP_AGA_03_12)) |
| Behaviour changes (each revertible on its own) | (1) The JSON exporter takes keys from the runtime registry, so exported keys change from one global counter (`R1, C2, W3`) to per-prefix numbering. (2) Readings and net names use the document's own analysis instead of the session label registry. (3) Undo menu labels. (4) Background-document operations satisfying R1/R2 by the mechanism of SP_AGA_DEC_04 ([§03_08](#SP_AGA_03_08)). (5) Offscreen per-document render. (6) The free-run loop skips busy documents. (7) Grid size pinned during agent geometry. (8) The declared conditional property contract on elements. (9) The path-based file adapter. (10) Letters-only ID prefixes. (11) Untranslated message keys and the per-document solver event list. (12) The `convergence_failed` event under recovery. (13) Background close without tab switch and per-document console routing. (14) Importers report skipped/failed items to the caller with codes. (15) Model catalogue entries restored on a rejected import. (16) New documents (agent `createDocument`, user new tab) start with the blank-circuit time-step defaults instead of a zero maximum step, which the time-step bar turned into 1 ps. (17) The user's `ontimestep` hook is not called for steps of a document other than the visible one (background runs), as the `onanalyze` hook already is. (18) Setting the time-step bar from code (text import, tab activation) no longer fires its command, so a document's maximum time step is kept exactly instead of being re-quantised to the bar's 1-2-5 table (capped at 10 µs); this also applies to user file loads (audit BL-D01). (19) `TransistorElm` reports its collector current as its current (element `current`, JS API `getCurrent`). (20) The 555 timer declares its always-conducting internal paths (Vcc–ctl–ground, out to Vcc/ground) for the ground closure, so `isolated_group` is no longer raised for its `out`/`ctl`. (21) Format bits that `dump()` sets are part of the element's flags from construction (`getDumpFlags`; JSON `_flags`, TypeInfo `defaultFlags`), so add and import agree. (22) User JSON saves are written as version 2.2 and carry the `models` the circuit uses ([§03_12](#SP_AGA_03_12)). (23) User JSON loads (open, paste, subcircuits-only import) define the `models` entries they carry, as text loads do with model lines. (24) Agent paths reject an unknown `model_name` (`CustomLogic`, `Subcircuit`) instead of the editor's session copy or fallback; user loads and `openFile` keep the editor's behaviour ([§03_03](#SP_AGA_03_03)). (25) `CustomLogicModel` rule parsing returns its first error instead of alerting; the editor's model dialog and user loads alert that message as before; agent paths never alert: `openFile` reports `value_adjusted` (warning) and loads the rules before the bad line, a new `!` line in `importCircuit` text content is `invalid_value`; a rejection restores the entry. (26) User text loads keep a `CustomLogic` line with an unknown model (an empty model is created under that name) instead of skipping it after an exception. (27) `importCircuit` text and JSON content no longer overwrite a session model whose line differs: `name_taken` (`openFile` keeps overwriting, as a user open does). (28) The editor's "Create Subcircuit" sizes its node arrays after the subcircuit node allocation instead of before it: a circuit whose subcircuit allocation has more nodes than its last analysis (a voltage source and no Ground, so the normal allocation grounds the source's first terminal) no longer indexes past the arrays (an index error or a wrong "is not used" / "unconnected" result). (29) The system clipboard is used through the page window: a paste reads it there and Copy/Cut also write the OS clipboard in browsers (before: the module frame, which never has the focus, used it, so the read and `writeText` failed in browsers). A system-clipboard paste takes text only when it passes the circuit test of [§03_09](#SP_AGA_03_09) (a JSON v2 circuit, or text whose every line is a circuit line); before, a token heuristic accepted any text containing "r ", "c ", "l " or "w " and rejected JSON without them. (30) A P-channel FET (`PMOS`, `PJFET`) names post 1 `drain` and post 2 `source` (before: `source`, `drain`, which put the body diode of a PMOS wired by its names in forward bias); a MOSFET's and JFET's reported current (JS API `getCurrent`, agent `current`) is the drain terminal current positive into the drain, and its voltage difference (JS API `voltageDiff`, agent `voltage`) is drain minus source (before: the channel current from post 2 to post 1 and post 2 minus post 1, the same values for N-channel without a conducting junction); the scope still plots the channel current and post 2 minus post 1; a JFET computes its gate junction currents (before: always 0 in pin currents and dots) ([SP_AGA_DEC_08](#SP_AGA_DEC_08)). (31) Relay coils and SPICE-style CCCS/CCVS receive the element list before their stamp again (their `setParentList` took a `Vector` after the element list became an `ArrayList`, so it was never called): `latchingrelay.txt` and `ujtosc.txt` simulate instead of stopping with an exception in `stamp()` (audit BL-D02), and a SPICE-style controlled source uses the voltage source across its inputs. (32) Agent mutations, `getConnectivity`, `getCircuit` and `openFile` make the node analysis current without stamping the matrix (dense, cubic in the node count; PL_AGA backlog "importCircuit scales"); the stamp runs at the next run, reading, `getDiagnostics`, reset, `simControl run`, `render` or free-running frame (`simControl run` and `render` stamp only what a node analysis left, so a document no agent call analysed keeps its state). What the stamp shows in the drawing (a current source's current, a potentiometer's resistances, §03_13) is set by the node analysis as the stamp sets it; not the contact positions a relay coil's stamp sets (they keep their loaded positions until the stamp; they differ only when the saved coil state and contact positions disagree). A solver stop or warning raised by the stamp itself (an element `stamp()` exception, `Matrix error`, `Singular matrix!`) and a stamp exception (`analysis_failed`) appear at that next stamp instead of right after the mutation — every agent result that reports them (`getDiagnostics`, `run`, `read`, `simControl run`, `render`, and `getConnectivity` once a frame or step stamped) stamps first or reads the stamped state, so they are unchanged; only the visible tab's own canvas shows them from its next stamp. (33) While a background document is bound, the session Undo/Redo items keep showing the visible document's history (before: an agent edit, checkpoint or undo of the background document wrote its labels until the scope ended), and a user content replacement that seals the visible document's agent transaction updates the Undo item at once (before: the label changed at the next refresh, e.g. during a later background operation). (34) Issue messages quote client-supplied values bounded ([§01_07](#SP_AGA_01_07); before: in full — a 1 MB property key, ID, path or label text came back in the message, and type and model names were cut to 64 characters plus `…` without the length); the importers' report messages and the model-definition problems clip element keys, type, pin and model names the same way; the `unknown_element` of `circuit.scopes[].element` names a valid unknown ID in `elements` (and its key) as before, an invalid one as `#?` (before: the raw value, also 1 MB); the hint of an unknown model name lists at most 40 names (built-in first), then `… (N in all; listModels lists them)`; a `text_overlap` message cuts a text at 32 characters plus `… (N chars)` (before: `…`; the checkLayout TextBox `text` keeps `…`) |
| Artifacts | The Agent API module and its export through the clustered native boundary; no persistent artifacts |
| Dependent modules | [SP_MCP](./mcp-server.sp.md) and [SP_AGS](./agent-skill.sp.md) depend on it; removing the Agent API removes the MCP tool set |
| Model definitions ([§03_11](#SP_AGA_03_11), [§03_12](#SP_AGA_03_12)) | Removing `defineModel`/`listModels` and the AgentCircuit `models` key leaves the catalogues as the editor makes them. The JSON `models` section can be dropped by reverting the exporter and importer (items 22, 23) alone. JSON 2.x is in development and carries no compatibility promise between its versions (resolved by the developer, 2026-10-04) |
| External contracts | The existing scripting global keeps its documented methods; its element IDs come from the registry (same format) |
| Layout check ([§02_16](#SP_AGA_02_16), [§03_13](#SP_AGA_03_13)) | Removing `checkLayout` removes the check; no other contract reports its issues. Once `circuit_layout` is released (toolsVersion 1.2), removing it is a breaking change and bumps `toolsVersion` MAJOR ([SP_MCP §06_01](./mcp-server.sp.md#SP_MCP_06_01)). The layout/paint split of the element text drawing changes no pixel after the explicit-font baseline: the PL_AGA Phase 16a/16b pixel comparison, the "layout equals drawing" row and the `render_text`/`xfmr_draw` scenarios check it. Each class's split can be reverted on its own; the class then reports itself not covered |
| Switch throw and pole counts (defect fix, PL_AGA Phase 16a) | Before: a `Switch2Elm` line whose throw-count token is not a number (`mr-crossbar.txt` has `false`) loaded with 0 throws, because the element's integer parser returns its default instead of throwing; `draw()` then read `swposts[-1]` and every render and frame of that circuit failed (`render` gave `internal_error`). A `DPDTSwitchElm` line without a pole-count token, or with one that is not a number, loaded with 0 poles the same way. Now: a throw count that is not a number keeps the default 2 and a count below 2 becomes 2; a pole count that is missing, not a number or below 1 is 2. The text export of such lines changes accordingly (`… 0 0` → `… 0 2`). Also: an offscreen render whose element draw throws restores the session's measuring context in a `finally`, so later images keep their size and origin. Revertible on its own |
| Explicit text fonts (defect fix, PL_AGA Phase 16a step 0; [§03_13](#SP_AGA_03_13)) | Before: label, centred, switch, relay and motor texts and transistor pin letters drew in whatever font the previous element or the slice start had set, so the same circuit could show different fonts on screen, in the image and between image slices. Now: every text site sets its font, the units font where it inherited one before. Its pixel changes are reviewed and accepted once, and the Phase 16 pixel baseline is taken after it. Revertible on its own (the old inheritance comes back) |

## 07. Design Decisions  {#SP_AGA_DEC}

### DEC_01 — One batch edit contract or one contract per edit kind?  {#SP_AGA_DEC_01}

> **Status:** resolved
> **Date:** 2026-10-01

**Question:** Should incremental edits be one ordered, atomic batch contract with typed entries, or separate add/move/delete/set contracts?

**Options considered:**
| Option | Consequence |
|--------|-------------|
| A — one `applyEdits` batch with typed entries | One atomic unit, one connectivity delta, one round trip for a multi-element change; MCP projects it to one tool |
| B — separate contracts per edit kind | Simpler schemas; multi-element changes need many calls and are not atomic |

**Decision:** A — one `applyEdits` batch (confirmed by the developer).
**Rationale:** Atomic multi-element edits and a single delta match the agent loop and the tool-consolidation guidance; the batch also bounds undo snapshot cost.
**Rejected because:** B — non-atomic sequences can leave half-wired circuits and multiply round trips.

### DEC_02 — Which property keys does the agent use?  {#SP_AGA_DEC_02}

> **Status:** resolved
> **Date:** 2026-10-01

**Question:** Should properties be addressed by JSON property keys or by editable-parameter labels?

**Options considered:**
| Option | Consequence |
|--------|-------------|
| A — JSON property keys plus declared conditional keys | Stable ASCII keys already in the file format and round-trip rule (RULE_ARCH_010); patch by merge-then-apply |
| B — editable-parameter labels | Covers every dialog field; labels are human text, translated, and not stable |

**Decision:** A — JSON property keys, with labels and slider hints attached from matching editable-parameter entries; elements clamp values themselves, reported as `value_adjusted`.
**Rationale:** Settled by the existing JSON format and RULE_ARCH_010; labels are display text.
**Rejected because:** B — translated, unstable identifiers.

### DEC_03 — Where does file I/O live?  {#SP_AGA_DEC_03}

> **Status:** resolved
> **Date:** 2026-10-01

**Question:** Should open/save by path be Agent API contracts or done by the MCP server around content-only contracts?

**Options considered:**
| Option | Consequence |
|--------|-------------|
| A — Agent API contracts over a new path-based file adapter | Document path, title and modified flag stay consistent with the UI; one place enforces the file rules |
| B — server reads/writes files; Agent API only imports/exports content | Agent API stays I/O-free; the document never learns its path or saved state |

**Decision:** A — Agent API contracts. Today's desktop "save" is a browser download and "open" a file picker, so neither takes a path; a new adapter is required either way.
**Rationale:** Only the Agent API can update the document's path, title and modified flag consistently.
**Rejected because:** B — saved documents would stay marked unsaved and untitled.

### DEC_04 — By which mechanism do background-document operations meet R1/R2?  {#SP_AGA_DEC_04}

> **Status:** resolved
> **Date:** 2026-10-01

**Question:** Many load, undo, export and rendering paths write session UI state or resolve the active document ([§03_08](#SP_AGA_03_08)). How should operations on a non-active document satisfy R1 (no disturbance of the active tab) and R2 (the target behaves as if active)?

**Options considered:**
| Option | Consequence |
|--------|-------------|
| A — scoped silent bind: save the active tab's UI state, bind the target document without tab-change events or repaint, run the operation on today's code paths, save the target's UI state, rebind the original and restore its UI state | Reuses the tab-switch save/restore that exists; every session-coupled path is covered at once; a run would bind per slice, which costs a UI-state swap every slice |
| B — explicit routing: give each session-coupled path a document parameter | Exact and cheap at runtime; touches many paths, and every missed path is a disturbance bug |
| C — hybrid: A for loads, undo/redo, export and render; B only for the stepping path of runs | A's coverage where paths are many; no per-slice UI swap during runs |

**Decision:** A — scoped silent bind for every operation, including each `run`/`render` slice. The plan adds four conditions: a field-swap bind instead of `bindDocument`, the session sliders dialog detached while bound, a hint per document, and a slice yield that waits for one active-tab frame ([PL_AGA_DEC_01](./agent-api.plan.md#PL_AGA_DEC_01)).
**Rationale:** The prototype passed every R1 sample and the R2 comparison of the reduced sequence at 97.5 % of the active tab's idle rate and 0.6 ms per bind. C measured more background throughput but leaves stepping-time session reads unrouted ([PL_AGA Phase 0 result](./agent-api.plan.md#PL_AGA_P0)).
**Resolved by:** the developer, 2026-10-01, after the PL_AGA Phase 0 prototype.

### DEC_05 — Does a stop-trigger element end an agent run?  {#SP_AGA_DEC_05}

> **Status:** resolved (delegated)
> **Date:** 2026-10-02

**Question:** A stop-trigger element stops free-running when its condition holds; today it only clears the running flag, so an agent `run` (which owns stepping and ignores the flag) continues past it.

**Options considered:**
| Option | Consequence |
|--------|-------------|
| A — end the run with a new reason `stop_trigger` and clear the running flag | Matches the element's purpose; an agent can measure time-to-trigger; one more reason value |
| B — keep running; document that stop triggers affect free-running only | No contract change; a circuit built to stop at a condition runs to the end of the span under agent control |

**Decision:** A.
**Rationale:** The element exists to stop the simulation; a run is a simulation of the same document, and the agent sees the reason and the element in the result.
**Resolved by:** main under `Autonomy: full` (task_E_AGT), 2026-10-02, on a finding of the circuit-language research spike; presented to the developer at the next stop.

### DEC_06 — What are the pin names of polar elements?  {#SP_AGA_DEC_06}

> **Status:** resolved (delegated)
> **Date:** 2026-10-03

**Question:** Voltage sources named post 0 `positive` and post 1 `negative`, but the solver drives post 1 above post 0 (a +5 V DC source measures +5 V at `negative`); the ohmmeter's `probe+`/`probe-` were swapped the same way; op-amps with `swap_inputs` named the inverting input `in+`. The names are part of the JSON v2 format, whose importer places posts by pin name.

**Options considered:**
| Option | Consequence |
|--------|-------------|
| A — swap the existing names | Same words, but every existing JSON 2.0 file flips its sources unless the importer knows the file version |
| B — new names that differ from the old ones, old names kept as import aliases with their old meaning | Correct names; old files load unchanged in new builds; new files load correctly in old builds through the key-order fallback; old PostRefs fail loudly with `unknown_post` instead of meaning the wrong terminal |
| C — keep the names and document the trap | Agents and users keep misreading polarity |

**Decision:** B. Voltage sources `minus`/`plus`, current sources `in`/`out`, the ohmmeter `com`/`probe`, op-amps always `in-`/`in+`/`out`; the polarised capacitor (`positive`/`negative`, verified correct) is unchanged. The JSON importer maps `positive`→post 0 and `negative`→post 1 for voltage and current sources, `probe+`→post 0 and `probe-`→post 1 for the ohmmeter. Op-amps get no alias (the old and new names are the same words): a 2.0 file's swapped op-amp keeps its geometry (placed by `_startpoint`/`_endpoint`), but its saved `state.pins` input voltages are read crossed — a transient initial-state difference only. JSON files are written as schema version `2.1` (readers accept any `2.x`).
**Rationale:** Correct names without a silent meaning change for any file or agent reference.
**Resolved by:** main under the developer's instruction "Назви пінів потрібно зробити як буде правильно" (2026-10-03), after measurement of every polar element.

### DEC_07 — How do agents create component models?  {#SP_AGA_DEC_07}

> **Status:** resolved (delegated)
> **Date:** 2026-10-04

**Question:** Agents could not make a green LED drop more than the red default (live series T2): the API accepted only existing model names. The developer asked that agents create new models of every component the app lets a user model. Where does the definition live, and what may it change?

**Options considered:**
| Option | Consequence |
|--------|-------------|
| A — a `defineModel` edit op (batch-atomic with the elements that use it) plus `listModels`, strictly create-only names (an identical re-definition is accepted unchanged; to change a model an agent defines a new name and `set`s its elements) | Model and usage land or fail together; `defineModel` and `importCircuit` never change an existing entry, so no other tab changes behaviour through them; `openFile` loads model lines like a user file open and may overwrite, as the editor does; one new op, one read op |
| B — a separate `circuit_models` tool with free create/update/delete | Cross-document side effects (catalogues are session-wide and transistor/logic models are read live); no atomicity with the elements |
| C — only through a text import with model lines | Already possible but replaces the whole circuit; no validation without dialogs |

**Decision:** A, for all four model kinds (diode, transistor, logic, subcircuit); the AgentCircuit and the JSON format carry the models a circuit uses.
**Rationale:** Same capability as the editor's model dialogs, without leaking into other documents (`defineModel` and `importCircuit` never change an existing entry; `openFile` loads model lines like a user file open and may overwrite, as the editor does), and a circuit read or saved by an agent re-imports with its models.
**Amended:** 2026-10-04 (model review round 1) — the first version let `replace` redefine a model no other open document used; dropped because undo entries, closed tabs and session restore re-apply the model lines they carry, so a redefined entry could still be overwritten or reach other documents. Names are now strictly create-only ([§03_11](#SP_AGA_03_11)).
**Resolved by:** main under the developer's instruction "Агенти повинні мати можливість через mcp створювати нові моделі тих компонентів, якщо це передбачено у застосунку" (2026-10-04).

### DEC_08 — Which posts of a P-channel FET are its source and drain?  {#SP_AGA_DEC_08}

> **Status:** resolved (delegated)
> **Date:** 2026-10-04

**Question:** The live agent series (T10 re-run) wired a PMOS by its pin names — `source` at 12 V, gate at 12 V (off), `drain` through 1 kΩ to ground — and read 11.43 V at the drain: 11.4 mA flowed through the body diode. `PMOS` and `PJFET` named post 1 `source` and post 2 `drain`, like the N-channel types, while the element treats post 2 as a P-channel source. Measured on HEAD a71ea1c (posts at default size, `start` (0, 0)):

| Type (variant) | Drawn labels post 1 / 2 | Body tie | Solver's normal-operation source; `getFetInfo` Vgs reference | Names before | Names now | Off by names (drain) before → now | `current` on, by names |
|---|---|---|---|---|---|---|---|
| `NMOS` (body diode, default) | S / D | post 1 | post 1 | `gate`, `source`, `drain` | unchanged | 12.00 V → 12.00 V (load to 12 V) | +11.94 mA (into the drain) |
| `NMOS` `body_terminal` | S / D, B | post 3 `body` | post 1 | `gate`, `source`, `drain`, `body` | unchanged | 12.00 V → 12.00 V | +11.94 mA |
| `PMOS` (body diode, default) | D / S | post 2 | post 2 | `gate`, `source`, `drain` | `gate`, `drain`, `source` | **11.43 V** → 0.0003 V | −11.94 mA |
| `PMOS` `body_diode: false` | D / S | — (symmetric) | post 2 | `gate`, `source`, `drain` | `gate`, `drain`, `source` | 0.0001 V → 0.0001 V | −11.94 mA |
| `PMOS` `body_terminal` | D / S, B | post 3 `body` (symmetric) | post 2 | `gate`, `source`, `drain`, `body` | `gate`, `drain`, `source`, `body` | 0.0003 V → 0.0003 V | −11.94 mA |
| `PMOS` digital symbol / hidden bulk (session-wide display flags) | D / S | — (no body diode) | post 2 | as default | as default | symmetric (by code) | as above |
| `NJFET` | none (symmetric drawing) | — | post 1 | `gate`, `source`, `drain` | unchanged | 12.00 V → 12.00 V (gate −6 V) | +9.16 mA at Vgs = 0 |
| `PJFET` | none (symmetric drawing) | — | post 2 | `gate`, `source`, `drain` | `gate`, `drain`, `source` | 0.0003 V → 0.0003 V (gate 18 V) | −9.16 mA at Vgs = 0 |
| `TransistorNPN` / `TransistorPNP`, `DarlingtonPNP` (confirmation) | B, C, E | — | — | `base`, `collector`, `emitter` | unchanged | collector at the load rail; PNP on: −11.34 mA into the collector | Darlington: no element quantities (composite) |

The channel equations are symmetric (the solver swaps source and drain by voltage), so the names decide only the body tie of a PMOS with its default body diode, the meaning of `current`/`voltage`, and agreement with the drawn labels. Depletion devices (negative `threshold_voltage`) use the same posts. Before the fix the P-channel `current` was the channel current from post 2 to post 1 and `voltage` post 2 minus post 1, which matched the old names; with the names swapped both had to change sign to keep their meaning.

**Options considered:**
| Option | Consequence |
|--------|-------------|
| A — swap the P-channel names; `current` is the drain terminal current positive into the drain, `voltage` drain minus source, for both polarities | Names agree with the drawing, the body tie and the solver; one sign rule for BJTs and FETs; a JSON 2.x file with a PMOS/PJFET reads its `state.pins` voltages crossed (a transient initial state only; positions and `connected_to` come from the file itself) |
| B — keep the names, move the body tie to post 1 for PMOS | Changes the simulation of every existing PMOS circuit and contradicts the drawn D/S labels |
| C — keep everything, document the trap | Agents wiring by names keep shorting the supply through the body diode |

**Decision:** A. `PMOS` and `PJFET` name post 1 `drain` and post 2 `source`; N-channel names are unchanged. `MosfetElm.getCurrent()` (inherited by JFETs) is the current into the drain terminal — channel plus the body diode (MOSFET) or gate-drain junction (JFET) at the drain — and `getVoltageDiff()` is drain minus source; the scope keeps plotting the channel current (Ids / Isd as the info panel) and post 2 minus post 1, so user scopes do not change. JFET gate junction currents, which were never computed, now are (they enter the drain current and the pin currents). No import aliases and no schema version change: JSON 2.x carries no compatibility promise between its versions (developer, 2026-10-04).
**Rationale:** The names must be the electrically and visually correct terminals; a terminal current with one sign rule lets an agent see body-diode conduction that the channel current hid (−5.7 nA reported while 11.4 mA flowed).
**Resolved by:** main under the developer's instruction (2026-10-04) to swap the names directly without compatibility code, after measurement of every FET variant (live scenario `pin_names`).

### DEC_09 — Where do the text boxes of `text_overlap` come from?  {#SP_AGA_DEC_09}

> **Status:** resolved
> **Date:** 2026-10-04

**Question:** In the live agent series, agents drew value texts crossed by wires (T8: a 555 timer's ground wire through the capacitor's `10nF`), label texts over leads (T3) and labels whose texts touched (T9). The agents also skipped the render-look step of the skill (T7, and the re-runs of T8 and T9 said so). So, as with `symbol_overlap`, the tools must report the problem. Texts exist only at draw time: `drawValues` placement, `drawLabeledNode`, chip pin names and per-element `drawCenteredText`/`drawString` calls, in about 50 element classes. Their width needs font metrics. How does the checker get the text boxes without changing state, the same in a background and in the visible document?

**Options considered:**
| Option | Accuracy | Cost | Background = visible | Upkeep |
|--------|----------|------|----------------------|--------|
| A — text pass: the elements' own draw code into a 1 × 1 scratch canvas; the graphics wrapper records each string's ink box; draw state and bounding boxes saved and put back | Exact (same code as the image) | One draw per element, about 60 µs (measured) | Yes, with the bounding boxes put back | Draw side effects must be contained; live texts need an element-level declaration |
| B — placement model: the checker re-implements each family's placement and measures with canvas `measureText` | Approximate; two copies of every placement rule, so drift is silent | About 5 µs per text (measured), no drawing | Yes | High |
| C — separate layout function: every text-drawing class gets a layout method that returns the placements; `draw()` paints them and the check measures them with canvas `measureText` | Exact for every class that has the method; one source of truth, so no drift; a class without it is reported, never guessed | About 5 µs per text, no drawing, no state touched | Yes (geometry, properties and options only) | One refactor of about 50 classes' text drawing (shared helpers split into layout and paint) |
| D — render only: report from the measure pass of `render` | Exact | None extra | Yes | Agents that skip the render never see it |

**Decision:** C. A separate function computes the text boxes with the canvas measurement (`measureText`), as specified in [§03_13](#SP_AGA_03_13):
- the shared helpers and every class's own `drawString` sites are split into layout (placements) and paint;
- `draw()` = layout + paint; `checkLayout` = layout + measure;
- a class without the layout method declares itself not covered (`text_not_covered`);
- a harness check compares SVG texts with the boxes of every type.

Also resolved (delegated, proposals accepted): live-reading texts are marked live and left out of the rules; the strings of text elements are texts.
**Rationale:** The layout method makes the drawing and the check share one placement, so they cannot drift. The check draws nothing and changes no state, and its cost is a canvas measurement per text.
**Resolved by:** the developer, 2026-10-04: "Зробити окрему функцію, яка буде це розраховувати. Наче, у canvas вже є така" ("Make a separate function that computes it. I think canvas already has one"). The layout/paint split that keeps placement and drawing from drifting apart was set by the coordinator from that answer.
**Amended:** 2026-10-04 (design review round 1, lead):
- Delivery is staged: Phase 16a, then 16b.
- Every placement names its font (fixes the inherited-font defect).
- Layout takes a `highlighted` input, and transient texts are never checked.
- Placements carry draw-order groups; there is no rotation.
- Coverage is enforced by the static check `text_sites`.

### DEC_10 — When is `text_overlap` computed?  {#SP_AGA_DEC_10}

> **Status:** resolved
> **Date:** 2026-10-04

**Question:** The connectivity rules run on every `getConnectivity` and twice per mutation (the delta compares before and after). Where does the text check run, and how is its cost bounded? `importCircuit` of 2500 elements already takes about 51 s (PL_AGA backlog).

**Options considered:**
| Option | Consequence |
|--------|-------------|
| A — every `getConnectivity` and mutation delta, with an element bound and an info issue above it | A crossing shows in the reply of the edit that made it; every edit pays the check |
| B — `getConnectivity` only | The delta and the full report disagree on the issue set |
| C — a time budget per pass | Issues appear and vanish with machine load; unstable keys |
| D — `render` only | Agents that skip the render never see it |
| E — a separate read-only call on demand (`checkLayout`, MCP tool `circuit_layout`) | Edits, imports and connectivity reads pay nothing. The agent calls it before reporting, as the skill's checklist says; with C of DEC_09 it is cheap enough to need no element bound |

**Decision:** E. `checkLayout` ([§02_16](#SP_AGA_02_16)) is the only producer of `text_overlap` and `text_not_covered`. No mutation delta and no `getConnectivity` computes them. It has no element bound (about 5 µs per text), and its output is capped at 100 issues and 2000 boxes. The MCP surface is a new tool `circuit_layout`, so `toolsVersion` goes to 1.2. The skill's checklist calls it before every report, together with the render look.
**Rationale:** The check costs nothing unless it is asked for. A separate tool is easier to find than a flag on another tool, and every MCP tool maps to one contract.
**Resolved by:** the developer, 2026-10-04: "Окремим викликом, при необхідності" ("As a separate call, when needed"). The MCP surface (a new tool rather than an argument of an existing read-only tool) was recommended by the designer.

## Changelog

| Date | Change |
|------|--------|
| 2026-10-01 | Initial version |
| 2026-10-02 | PL_AGA Phase 10 propagate: behaviour-change notes in §03_02, §03_04, §03_06 and §03_08 restated as implemented (pre-Agent-API behaviour named with its §06_01 item); the §06_01 items are documented in JS_API.md, EXPORT_CJS.md and the C_DOC, C_UND, C_IOF and C_APC concepts/specs |
| 2026-10-03 | Axis-bound elements: new code `not_axis_aligned` (§03_01, code list) |
| 2026-10-03 | Live-verify fixes: TypeInfo `quantities`, PropertyInfo `choices` and element-declared labels; collapsed posts and replaced ends (§03_01); transformer pin names `p1/s1/p2/s2`; model names (§03_03, §03_04); render area includes text and always uses printable colours on white |
| 2026-10-03 | §03_03: unit strings may be wrapped in one pair of double quotes (eval finding) |
| 2026-10-03 | Defect batch: §03_05 codes `ground_path_no_resistance`, `wire_loop`, `current_source_no_path`; BJT/FET `current` and BJT `voltage`; `set` reports only writable keys; LogicInput `state` read-only; §06_01 items 19–21 |
| 2026-10-03 | Polar pin names corrected (SP_AGA_DEC_06): sources `minus`/`plus`, current sources `in`/`out`, ohmmeter `com`/`probe`, op-amp inputs fixed; source `voltage` sign stated |
| 2026-10-04 | Live agent series: §03_05 `symbol_overlap` (geometry-only body model); `no_ground` only without a ground element when the simulator assumes ground at a voltage source or no element references ground internally (circuits referenced through rails, logic inputs, gates or chips need none) |
| 2026-10-02 | Fix round: `stop_trigger` run reason (SP_AGA_DEC_05), first probe sample after the first solved step, determinism qualified for noise sources, §06_01 item 18 time-step bar no longer re-quantises the maximum step |
| 2026-10-02 | PL_AGA Phase 9: `openFile` applies the circuit test; element lines need whole-number coordinates and flags; BOM, links, parent directories, whitespace-only and over-size overwrite rules; rejected-open issues aggregated per code; review: `file_not_found`/`file_error` per contract for links and directories, save refused when the resolved target changed after the check, staging file flushed before rename and only its own staging file removed |
| 2026-10-02 | PL_AGA Phase 8: render area includes bounding boxes, empty-document image, printable look and scope state untouched, `scale` size cap and close-while-rendering errors; R2 check masks scope auto-range fields and uses a simulated span; R1 slice bound is 20 ms plus one indivisible unit of work (timestep, element draw, image canvas allocation); one frame between slices of concurrent operations; 40-megapixel image cap; encode failure is `render_failed` |
| 2026-10-02 | PL_AGA Phase 7: `run` on an empty document is `invalid_value` naming `doc`; empty probe stats; time output digits and the shape-statistics series (§03_07); a canvas press cancels a run (§04_02); §06_01 item 17 `ontimestep` guard; review: configure current-step wording, empty configure, slice exception stops the document, non-finite samples, steps count forced steps, settle chunks, slider and legacy-script cancels |
| 2026-10-02 | §04_01: a sealed agent transaction without net change is dropped by the next user edit push (PL_AGA Phase 6 review) |
| 2026-10-01 | §06_01 item 16: blank-circuit time-step defaults for new documents (PL_AGA Phase 6) |
| 2026-10-01 | `read` null for non-finite values, wire-only net ranking, `netFilter` range, `recovering` = engaged (PL_AGA Phase 5 review) |
| 2026-10-01 | `$<k>` net names ranked by smallest member PostRef instead of the simulator node index (PL_AGA Phase 5: node indices follow element order and broke the round-trip rows) |
| 2026-10-01 | Catalogue: built-in defaults from a scratch document, one-to-one English labels, slider-seed sentinels, `readOnly` keys, `add` applies TypeInfo defaults (PL_AGA Phase 3 review) |
| 2026-10-01 | TypeInfo: defaultSize defined by a 4-cell editor drag, post-less elements are `derived`, `summary` in English, unmeasurable keys omitted (PL_AGA Phase 3) |
| 2026-10-01 | createDocument `title` range and meaning stated (PL_AGA Phase 1 review) |
| 2026-10-01 | SP_AGA_DEC_04 resolved: A (scoped silent bind) with the conditions of PL_AGA_DEC_01, after the PL_AGA Phase 0 prototype |
| 2026-10-01 | DEC_04 trigger aligned with the plan's reduced prototype sequence (plan review) |
| 2026-10-01 | Review round 5: R1 holds during calls with test rows for R1/R2 and the DEC_04 trigger tied to them; grid option consistent across sections; openFile activate/rejection/modified-flag precedence |
| 2026-10-01 | Review round 4: §03_08 restated as requirements R1/R2 with mechanism decision DEC_04 (open), slider seeds no longer limits, modified flag on undo/redo, issue and id caps, import skip codes, model catalogue restore on rejection, document grid option for agent geometry, `openFile activate`, JSON key regeneration |
| 2026-10-01 | Review round 3: load/undo/save routing to the target document's UI state, modified flag, 1/16 lattice for imports, import caps, alias rule, run exception and stop handling, importer reporting, side-effect-free circuit test, common argument-range rule, dedupe reuse, restoreCheckpoint definition |
| 2026-10-01 | Review round 2: geometric dangling rule, circuit test for overwrites, letters-only prefixes and call-level counter raising, message keys and event list, convergence event under recovery, wire_loop as warning, undo/redo extension rule, transaction opened only on success, contract class table, close routing, 1/16-cell import, delta and result caps, reserved labels |
| 2026-10-01 | Review round 1: per-document routing (§03_08), file adapter and rules (§03_09), error reporting (§03_10), unique pin names, recovery-mode issue mapping, conditional property keys, pinned grid size, counter raising on restore, open marks (§01_12), async render, run/user interaction, verification gaps |
| 2026-10-04 | Agent model definitions (SP_AGA_DEC_07): §01_13 ModelSpec/ModelRecord, §02_04 `defineModel`, §02_15 `listModels`, §02_03/§02_05 `models`, §03_11 model rules, §03_12 JSON 2.2 `models`, codes `unknown_model`/`name_taken`/`model_in_use`/`model_read_only`, `choices` for `model_name` |
| 2026-10-04 | Model definitions review round 1: create-only (no replace), identical re-definition accepted, ModelText fallback, dependency closure, pseudo-code, error cases, subcircuit source rules, codec in L2 |
| 2026-10-04 | Model definitions review round 2: CustomLogic text line with an unknown model kept (§06_01 item 26), subcircuit inner references validated, `openFile` overwrite stated in DEC_07, `importCircuit` `name_taken` hint (§06_01 item 27), diode record-form checks, logic `default` always present, subcircuit identity includes element state, §03_04 restorer wording, paste and subcircuits-only rows |
| 2026-10-04 | Phase 11 review: `from` may name an earlier entry of the same call; diode form errors name `parameters.forward_voltage` / `parameters.forward_current`; ModelText integers are strict; `models` order = getCircuit record order; a `defineModel`-only batch opens the transaction; §03_04 restorers cover legacy `fwdrop` diode entries, and a model line after its element in the same content defines the model |
| 2026-10-04 | Phase 12 (custom logic models): §06_01 item 25 — user loads keep the alert, agent paths never alert (`openFile` `value_adjusted`, `importCircuit` text `invalid_value`); §01_13 logic pins unique across `inputs` and `outputs`, rule right side = output count (other characters read as 0), the ModelSpec limits do not apply to a logic ModelText; §02_04 a `CustomLogic` `model_name` set in a batch gives later edits that model's PinNames; §02_05 Round trip / §01_13 Line: a logic ModelText whose rules do not parse is accepted only as identical to an existing entry |
| 2026-10-04 | Phase 13 (subcircuit models): §06_01 item 28 (editor Create Subcircuit node arrays); §01_13 Read-only source restated as a node allocation without element validation, the normal allocation restored and the stamp kept; reason texts for recursion, unknown element class, trial-build failure and node-allocation failure; inner references accept internal entries, run after the identity test, nested dumps scanned, trial build in the target document; a subcircuit ModelText with no pin is `invalid_value`; new `.` lines of `importCircuit` text content get the static and pin checks; §05_01 row "subcircuit with no pin" |
| 2026-10-04 | Phase 14 (JSON 2.2 `models`): §03_12 `openFile` reports skipped entries and the elements naming them as `value_adjusted`; a logic entry with rules that do not parse alerts on a user load as a text `!` line does (item 25), `value_adjusted` on `openFile`; no count limit for a JSON text's `models` (§02_03: 200 for AgentCircuit); the `Scope` element's structured `scope` property, omitted from agent records (§02_05); subcircuits-only JSON import behaves as the text one; §06_01 item 29 system clipboard through the page window, paste only of content passing the circuit test; developer decision: JSON 2.x is in development and carries no compatibility promise between its versions (resolved by the developer, 2026-10-04) — the older-reader requirement and its §05_01 row are dropped |
| 2026-10-04 | Phase 15 (skill and documentation for models): no contract change; the skill (SP_AGS §02_03/§02_04/§02_05/§05_03) and docs/JS_API.md (`defineModel`, `listModels`, `models` of import, `getCircuit` and files) now document §01_13, §02_15, §03_11 and §03_12 |
| 2026-10-04 | Phase 15 follow-up: §01_13 Subcircuit source errors and the §05_01 source-errors row state when "some nodes are unconnected" fires (a group of used internal nodes without a path to ground; in a source with no ground-connected element the first group is tolerated, as by the editor); verified equal to the editor's Create Subcircuit (`agent_models_sub` `unconnected_*`). No code change |
| 2026-10-04 | P-channel FET pin names corrected (SP_AGA_DEC_08): `PMOS`/`PJFET` post 1 `drain`, post 2 `source`; FET `current` is the drain terminal current positive into the drain, `voltage` drain minus source; §01_09, §03_02, §06_01 item 30 |
| 2026-10-04 | Text overlap design (live agent series T3, T8, T9): §03_05 codes `text_overlap` (warning) and `text_check_skipped` (info); §03_13 text boxes (text pass, live texts, obstacles, rules, exemptions, reporting, 1000-element bound); §02_08 Texts; §05_01 text rows, §05_02 invariant "a connectivity read changes no state", §06_01 rollback row; SP_AGA_DEC_09 (text pass) and SP_AGA_DEC_10 (every analysis with an element bound), both proposed |
| 2026-10-04 | Text overlap redesign after the developer resolved SP_AGA_DEC_09 (separate layout function, canvas `measureText`) and SP_AGA_DEC_10 (separate call on demand): new §02_16 `checkLayout` (MCP `circuit_layout`, toolsVersion 1.2) in the read-only contract class; `text_overlap`/`text_check_skipped` rows removed from §03_05 (layout issues are reported only by `checkLayout`; `text_check_skipped` dropped, new `text_not_covered` info); §03_13 rewritten as text layout (layout/paint split, live placements, not-covered classes); §02_08 Texts; §05_01 text rows now `checkLayout` (incl. every-type layout = drawing, busy, caps); §05_02 `checkLayout` changes no state, drawing paints the layout; §06_01 row |
| 2026-10-04 | Text layout design review round 1 (lead: staged Phases 16a/16b): §03_13 code table (`text_overlap`, `text_not_covered`) cited from §01_07 `code`; every placement names its font (inherited-font defect, §06_01 row); `highlighted` layout input, transient texts never checked, §02_08 and §03_13 agreement for elements not highlighted; draw-order groups; no rotation; coverage by the static check `text_sites` (classes with text sites report not covered; `draw()` overrides without `super.draw()` must override `layoutTexts`); obstacles stated as the approximate `symbol_overlap` body model; `at` resolution, ID order for "smaller"; §02_16 TextBox anchor/align/baseline/font, errors through the common rules, bind cost, MCP size reduction; §05_01 rows highlight, explicit fonts, every-type with option variants and SVG attribute comparison, `text_not_covered`, cost tolerance; §05_02 `text_sites`; §06_01 MAJOR bump on removing a released tool |
| 2026-10-04 | Text layout design review round 2: `text_not_covered` key (code + first 20 IDs in ID order); §03_13 `highlighted` = `needsHighlight()` or dragged, fonts from simulated state laid out unfired (stop trigger), text sites count calls only (paint wrappers are layout classes until 16b, PotElm overload renamed), inherited coverage; §05_01 `text_not_covered` 16a fixture is a PolarCapacitor, highlight set by `debugSetHighlight`, font slices compared with `debugRenderSliceElements(1)`, SVG alignment/baseline/font compared through the canvas2svg mapping |
| 2026-10-04 | PL_AGA Phase 16a implementation decisions (lead, delegated): §03_13 Obstacles — a texted `single` element's stem ends at its own text box grown by PAD; Exempt — rule 3 not between two text elements; Coverage — an element drawing through a delegate (subcircuit → chip) takes its layout and coverage; Pure layout — stamped analysis values (current source, potentiometer) and the editor's plot axes; Highlight — bold-while-highlighted fonts also during drag-create; §02_16 Cost and §05_01 cost row re-based to the draft-compiled build (≤ 8 ms at 100, ≤ 120 ms at 2500; measured 6.4 / 97 ms visible, 5.9 / 94 ms background); §05_01 corpus row excepts the marked body-model findings; §06_01 row: `Switch2Elm` throw-count defect fix; §05_02 `text_sites` runs by default inside `agent_layout` |
| 2026-10-05 | PL_AGA backlog "importCircuit scales" (fix task): §06_01 items 31 (relay/CCCS/CCVS element list, audit BL-D02), 32 (node analysis without the stamp for mutations and connectivity; stamped drawing values set by the node analysis; `simControl run` and `render` stamp what it left) and 33 (session Undo/Redo labels from the visible document; refreshed when a user import seals) |
| 2026-10-05 | PL_AGA backlog "Bounded echo" (fix task): §01_07 `message` bounds quoted client values (64 characters + `… (N chars)`, paths 256, message ≤ 1000); §06_01 item 34 (also: model-name hints list at most 40 names) |
| 2026-10-05 | Spike solver-defects fix task: §02_10 `issues` of a run with `reset` start after the reset (the run no longer stamps before the reset, which factored a linear circuit twice) |
| 2026-10-05 | PL_SLV Phase 5 (SP_SLV_02_11, 02_12): §02_09 action `solver` with `mode`, its output block and errors; §01_11 Diagnostics field `solver`; contract-class table row gains `solver` |
