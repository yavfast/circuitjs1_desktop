# Agent API — Specification  {#SP_AGA}

> **Code:** SP_AGA
> **Status:** draft
> **Created:** 2026-10-01
> **Updated:** 2026-10-01
>
> **Concept:** [C_AGA](./agent-api.concept.md)
> **Depends on:** [SP_DOC](./document-model.sp.md), [SP_UND](./commands-undo.sp.md), [SP_SIM](./simulator-engine.sp.md), [SP_IOF](./io-framework.sp.md), [SP_EIC](./edit-info-contract.sp.md), [SP_FBR](./browser-file-bridge.sp.md) (existing mechanisms this spec changes or consumes)
> **Used by:** [SP_MCP](./mcp-server.sp.md), [SP_AGS](./agent-skill.sp.md)
> **Plan:** [agent-api.plan.md](./agent-api.plan.md)
>
> This document defines the data structures, operations, validation rules, lifecycles and verification criteria of the Agent API. The API is the transport-free surface through which agents build, edit, inspect, run, measure, debug and checkpoint circuits in any open document. Read it to implement the API, or to map MCP tools onto it. It is split into structures (§01), operations (§02), rules including the per-document routing changes to existing mechanisms (§03), lifecycles (§04), checks (§05) and rollback (§06).

## Contents

- [01. Data Structures](#SP_AGA_01) — cells, IDs, handles, pin names, element specs/records, nets, issues, results, probes, transactions, open marks
- [02. Contracts](#SP_AGA_02) — catalogue, documents, import/edit, inspect, readings, render, simulation, runs, diagnostics, history, files
- [03. Validation Rules](#SP_AGA_03) — geometry, identity, properties, atomicity, issue rules, sizing, document routing, files, error reporting
- [04. State Transitions](#SP_AGA_04) — agent transaction, document run state, element ID lifetime
- [05. Verification Criteria](#SP_AGA_05) — functional expectations, invariants, integration scenarios, edge cases
- [06. Reversibility](#SP_AGA_06) — rollback of each behavioural change
- [07. Design Decisions](#SP_AGA_DEC) — edit batch shape, property keys, file operations

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

Conversions: input `px = cell × 16` (exact). Output `cell = px / 16`, exact — a multiple of 1/16, because editor coordinates are whole pixels. Import accepts every value it can produce, so a circuit read with `getCircuit` re-imports unchanged; agent-authored edits stay on the half-cell lattice.

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
| properties | PropertyInfo[] | Editable properties ([§03_03](#SP_AGA_03_03) for the key source) |
| defaultFlags | int | Flags of a freshly placed element (after the default placement drag, which can set orientation bits) |

PropertyInfo:
- **Shape.** `{key: string, kind: "quantity" | "number" | "bool" | "text", default: number | string | bool, unit: string?, label: string?, sliderMin: number?, sliderMax: number?, readOnly: bool?}`.
- **`kind`.** It is `"quantity"` when the default is a unit string. `unit` is then its unit suffix (`Ohm`, `F`, `H`, `V`, `A`, `Hz`, `s`, …).
- **`label`, `sliderMin`, `sliderMax`.** These come from the matching editable-parameter entry ([§02_01](#SP_AGA_02_01)). Matching is one-to-one: a key whose default equals the value of exactly one entry gets that entry, and an entry matched by more than one key is given to none of them; `bool` keys are not matched. `label` is the entry's English name (untranslated, markup removed). `sliderMin`/`sliderMax` are the slider seeds of that entry — a hint of a typical range, never a validity limit; they are omitted when the entry has sliders disabled or carries a degenerate pair (min = max, including (−1, −1) and (0, 0)).
- **`readOnly`.** `true` for a key the element exports but derives from its geometry or state rather than applying it from the property (for example a transformer's orientation keys). `set` of a read-only key is `invalid_value`; an import accepts read-only keys and ignores them, so a circuit read with `getCircuit` re-imports unchanged.
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
| code | IssueCode | yes | Stable code ([§03_05](#SP_AGA_03_05), [§03_06](#SP_AGA_03_06)) |
| severity | `"error"` \| `"warning"` \| `"info"` | yes | `error`: the operation was rejected, or the circuit cannot be simulated meaningfully |
| message | string | yes | One-sentence English description |
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
- `voltage` is the voltage difference the element reports: post 0 minus post 1 for two-post elements, and the element's own definition for others.
- `current` is the element's reported current.
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
| log | {entries: {seq: int, text: string}[], cursor: int, gap: bool}? | Present when requested ([§02_11](#SP_AGA_02_11)) |

### 01_12. Open marks  {#SP_AGA_01_12}

The open-mark set is per document: a set of PostRefs (PinName form) the agent has declared intentionally unconnected.
- **Persistence.** It lives in memory and is captured in every undo entry (`openMarks`). It is not written to circuit files.
- **Deletion.** Deleting an element removes its marks.
- **Content replacement.** Replacing the document's content clears the set.

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
| listTypes, describeType, listDocuments, getCircuit, getConnectivity, read, render, getDiagnostics, getHistory, exportCircuit | no | no | yes |
| createDocument, activateDocument | no | no | yes |
| importCircuit, applyEdits, openFile into a handle | yes | yes | no (`busy`) |
| openFile into `new` | no (creates a document) | no (user-load semantics) | yes (another document) |
| simControl `configure` | yes | yes (time-step settings are part of the circuit text) | no |
| simControl `run`/`stop`/`reset`, run | no | no | no |
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

AgentCircuit (coordinates may be any multiple of 1/16 cell, [§01_01](#SP_AGA_01_01)): `{elements: (ElementSpec | ElementRecord)[], simulation: map<string, number|string|bool>?, scopes: {element: ElementId, quantity: "voltage"|"current"|"power"?}[]?}`. `simulation` keys are those of the JSON v2 `simulation` object.

Output: `data: {elements: int, ids: ElementId[]?}`; `ids` is present when `elements` ≤ 200 (otherwise read them with `getCircuit`); `connectivity` = delta against the replaced circuit. The simulation state is reset: simulated time is 0 and element states are initial.

Errors: any validation code of [§03](#SP_AGA_03) for any element (`elements` names the offending spec as `#<i>` when it has no ID); `import_schema_invalid` (JSON text failing schema validation); `import_element_skipped` (an element the factory could not create — the import is rejected); `busy`.

Processing logic:

    FUNCTION importCircuit(doc, circuit):
        IF doc busy: RETURN error busy
        form ← detect(circuit)                                 # AgentCircuit | JSON v2 | text
        IF form = AgentCircuit: validate all specs (§03_01–03_03); convert to JSON v2 (cells×16, _startpoint/_endpoint pins)
        snapshot ← capture(doc)                                # circuit text + elementIds + openMarks + scopes + viewTransform
        load the content into doc through the format registry, with grid size pinned to the grid option the content selects (§03_01); the importers report every skipped or failed line/element to the caller (§03_04)
        IF any error issue: restore(doc, snapshot); RETURN ok=false        # undo/redo stacks untouched
        reset ID counters; assign IDs (§03_02); clear open marks
        openOrContinueTransaction(doc, snapshot)               # §04_01; pushes `snapshot` and clears redo only when opening
        analyse doc; RETURN ok=true with ids and connectivity delta

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
| addScope | `element: ElementId`, `quantity?` | Add an on-screen scope view |
| removeScope | `element: ElementId` | Remove scope views showing the element |
| markOpen | `posts: PostRef[]`, `open: bool = true` | Add posts to (or remove them from) the open-mark set |

Output: `data: {applied: int, created: ElementId[], elements: ElementRecord[], truncated: int}`. `elements` holds the records of created, moved or `set` elements in edit order, at most 50, so derived post positions and geometry changes are visible; `truncated` is the number of records left out (read them with `getCircuit`).

Errors: validation codes of [§03](#SP_AGA_03); `unknown_element`; `unknown_post`; `busy`; `scope_limit` (no free scope slot).

Processing logic:

    FUNCTION applyEdits(doc, edits):
        IF doc busy: RETURN error busy
        validate the batch in order against a model of doc's element set, with earlier edits visible to later ones
            (an add's id is usable by a later edit; a deleted id is not)
        IF any error: RETURN ok=false (nothing applied)
        before ← connectivityIssues(doc); snapshot ← capture(doc)
        WITH agent origin marked (editor undo pushes suppressed, §04_01) AND grid size pinned to doc's grid option (§03_01):
            TRY apply edits in order
            ON exception t: restore(doc, snapshot); report t to the global uncaught-exception handler (§03_10);
                            RETURN ok=false with issue internal_error        # undo/redo stacks untouched
        openOrContinueTransaction(doc, snapshot)               # pushes `snapshot` and clears redo only when opening
        analyse doc synchronously (also when free-running); RETURN data, delta(before, after)

`set` semantics:
1. Compute `merged ← current exported properties ⊕ declared conditional properties at their current values ⊕ patch`.
2. Apply `merged` through the element's JSON property application.
3. Re-run the element's geometry and node allocation.
4. Read back every property key of the element. When a property selects a different canonical type (a switch made momentary, an inverting gate), the record's `type` follows the new type and the ID stays.
5. Report mismatches: a patched key whose value differs from the parsed request, or an unpatched key whose value changed, yields a `value_adjusted` warning naming the key and the effective value.

`delete` of an element shown in scope views adds one `scope_removed` info issue listing the removed views.

### 02_05. getCircuit  {#SP_AGA_02_05}

Purpose: read the circuit in agent form.

Input: `doc?`, `detail: "concise" | "full" = "concise"`, `ids: ElementId[]?` (subset), `offset: int = 0`, `limit: int = 200` (≤ 500).

Output: `data: {elements: ElementRecord[], total: int, nextOffset: int?, simulation: map, scopes: {element, quantity}[]}`.

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
- **Area.** Drawn offscreen for the given document. It covers the circuit bounds (element endpoints and bounding boxes, so labels are not clipped) plus a 1-cell margin, whatever the viewport or the active tab. An empty document gives a blank 32×32 image.
- **Look.** The session's printable colours, no current dots, the target's selection highlight as it is. Drawing an image never changes a scope's graph, time base, trigger or scale.
- **Completion.** Asynchronous: the first SVG render loads the vector exporter and then completes. A load failure produces an issue and never a modal alert.

Errors: `render_failed` (the vector exporter could not load; `hint`: retry with `png`); `invalid_value` naming `scale` (the image would exceed 16384 px on a side or 40 megapixels in area); `render_failed` also when the browser cannot encode the image (no dialog); `unknown_document` (the document was closed while rendering).

### 02_09. simControl  {#SP_AGA_02_09}

Purpose: free-running control and time-step settings.

Input: `doc?`, `action: "run" | "stop" | "reset" | "configure"`, `settings: {maxTimeStep: number|string?, minTimeStep: number|string?, autoTimeStep: bool?}?` (for `configure`).

Output: `data: {running: bool, simTime: number, timeStep: {current, max, min, auto}}`.

Action rules:
- **`run`.** Free-running advances only the active document, which is the existing tab rule. `run` on a background document sets its running flag, which takes effect when it becomes active.
- **`reset`.** Sets simulated time to 0, clears element state and the scope views' histories, and clears the stop state.
- **`configure`.** Changes the persistent settings, the values analysis keeps. It never writes the transient current step; the analysis that follows restarts the current step at the new maximum, as after a user change.

Errors: `invalid_value` (non-positive or unparseable step; `min > max`; `configure` naming no setting); `busy` (during a run).

### 02_10. run  {#SP_AGA_02_10}

> **Criticality:** critical

Purpose: advance simulated time of one document under agent control, with probes ([C_AGA_03_04](./agent-api.concept.md#C_AGA_03_04)). Works on background documents too.

Input:
| Parameter | Type | Required | Constraints |
|-----------|------|----------|-------------|
| doc | DocumentHandle? | no | — |
| mode | `"span"` \| `"settle"` | no (`"span"`) | — |
| span | number \| string | for `span` | > 0 s; unit strings allowed (`"20 ms"`) |
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
- `issues` carries the solver events present when the run started plus those raised during it ([§03_06](#SP_AGA_03_06)), each code once.
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
- **Opening into a document.** `openFile` with a handle behaves as `importCircuit` into that document (agent transaction). With `"new"` it creates a document, loads the file like a user load (the undo history is reset and seeded with the loaded state) and opens no transaction.
- **After a successful `openFile`.** The document's file path and title are set, and its modified flag is cleared; this clear is applied last and takes precedence over the common modified-flag rule.
- **Rejected `openFile`.** A file whose content is not a circuit ([§03_09](#SP_AGA_03_09) circuit test: `file_not_allowed`) or fails to load (any `error` import issue, [§03_04](#SP_AGA_03_04)) is rejected: with `into: "new"` no document is created and the visible tab does not change; with a handle the document is unchanged. Issues follow the no-content-disclosure rule of [§03_09](#SP_AGA_03_09).
- **Before saving.** `saveFile` seals the open transaction automatically.
- **After a successful `saveFile`.** The file path and title are set, and the modified flag is cleared.

Errors: `file_unavailable` (no desktop runtime); `file_not_allowed` ([§03_09](#SP_AGA_03_09)); `file_not_found`; `file_error` (read/write failure; the message carries the system reason); `no_path` (save without a path on a never-saved document); `busy` (`openFile` into a busy document).

## 03. Validation Rules  {#SP_AGA_03}

### 03_01. Geometry  {#SP_AGA_03_01}

- **Lattice.** Coordinates are within ±4096. In `add`, `move` and `by` they are multiples of 0.5; in `importCircuit` and `openFile` content they are multiples of 1/16 (whole pixels). A coordinate off the lattice that applies is `off_lattice` (error); fractional pixel values in JSON v2 text are detected as `off_lattice` before any conversion to whole pixels.
- **Zero length.** `end = start` gives `zero_length` (error).
- **No adjustment.** Input geometry is never snapped or rounded.
- **Pinned grid size.** Geometry computation inside Agent API operations runs with the editor grid size pinned to the target document's own grid option — 16, or 8 when that document's options select the small grid — (with its mask and rounding values) and restored afterwards; the catalogue is always measured at 16. An import pins the option the loaded content selects: after its options line for legacy text, `display.small_grid` for JSON v2, and 16 for AgentCircuit or JSON without that setting. TypeInfo `defaultSize` and `derivedPostsAtDefault` describe 16-grid documents only; ElementRecord `posts` is authoritative. The user's current display preference of another tab never applies, and the document's own option is the one a later reload applies, so posts do not move on undo or reload. The element classes that size themselves from the editor grid size are the potentiometer, SCR, triac, tapped transformer, transmission line, wattmeter and real op-amp (its rail posts) elements; the pin applies to every element class, so a class that starts reading the grid size later is covered too. User paths (user undo, reload) keep today's behaviour and size those elements by the user's grid preference.

### 03_02. Identity and pin names  {#SP_AGA_03_02}

> **Criticality:** critical

**Pin names**
- An element's PinName list comes from its JSON pin names.
- Characters outside `[A-Za-z0-9_~+-]` become `_`, and an empty name becomes `pin<i>` (1-based).
- A name that repeats within the element gets `_<k>` on its k-th occurrence, for k ≥ 2 (for example `Q`, `Q_2`).
- The same names appear in TypeInfo, ElementRecord, PostRef and issues.

**ID form**
- A supplied `id` must match the ElementId pattern, otherwise `id_invalid`. It must be unused in the document, otherwise `id_taken`.
- An ID matching `^([A-Za-z]+)([0-9]+)$` has the *counter prefix* group 1 and the *number* group 2. Any other ID has no counter.

**Generated IDs**
- A generated ID is `<idPrefix><n>`, where `n` = counter[`idPrefix`] + 1. An `n` whose ID is already present is skipped (`n` increments until free).
- `idPrefix` is letters only: the element's own prefix where it defines one (`R`, `C`, `L`, `W`, `GND`, `V`, `I`, `D`, `LED`, `Z`, `U`, `M`, `K`, `T`, `SW`); otherwise the first three letters (A–Z, digits dropped) of the type name, upper-cased. Today's runtime default takes the first three characters, digits included (`CC2`); dropping digits is a behaviour change ([§06_01](#SP_AGA_06_01)).
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
  - Anything else is `invalid_value`. The parser's "0 on failure" result is never taken as a value.
- **`bool` and `text` values.** `bool` takes `true`/`false` only. `text` takes strings of at most 1000 chars.
- **Ranges.** The Agent API declares no validity ranges of its own. A value the element itself clamps or adjusts is applied as adjusted and reported with `value_adjusted` (warning) carrying the effective value; slider seeds are never used as limits.

### 03_04. Atomicity  {#SP_AGA_03_04}

> **Criticality:** critical

- **Validate first.** A whole batch, or a whole AgentCircuit import, is validated before the first change.
- **Text and JSON imports.** Content that can only be validated by loading it is loaded after a snapshot. Any loading error restores the snapshot (circuit text, element IDs, open marks, scope views, view transform, document UI state).
- **Import reporting.** The text and JSON importers report to their caller every item they skipped, failed or adjusted, with its line number (text) or element key/index (JSON); a line whose parsing throws counts as failed. Today they only write console messages — a behaviour change ([§06_01](#SP_AGA_06_01)). Codes and severities:

  | Item | Code | Severity |
  |---|---|---|
  | Element the factory cannot create; unknown or unparseable line | `import_element_skipped` | error (rejects the import) |
  | Scope beyond the 20 slots | `scope_limit` | warning |
  | Auto-wire whose target is missing | `import_wire_skipped` | warning |
  | Invalid simulation setting (kept at its default) | `import_setting_invalid` | warning |
  | Geometry re-applied differently (bounds, `p1`/`p2`) | `import_geometry_adjusted` | warning |
  | JSON element key not a valid or unique ElementId (a new ID is generated) | `ids_regenerated` | warning |

- **Model catalogues.** A text import records the session model catalogue entries (diode, transistor, custom logic, composite models) it creates or replaces; a rejected import restores them, so no other document sees a model change.
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
| no_ground | warning | The document has no ground element. `implicitGround` reports whether the simulator assumed one |
| isolated_group | error | Nodes the simulator found unconnected to ground (it would tie them through 100 MΩ). One issue per group, listing its posts. A group whose posts all carry open marks is exempt |
| bad_connection | warning | A post the analysis lists as touching another element's body |
| single_label | info | A label text used by exactly one labelled node |
| reserved_label | warning | A label text equal to `gnd`, starting with `$` or starting with `label:`; its net is named `label:<text>` |
| source_or_wire_loop | error | The last analysis reported a voltage-source/wire loop, as a stop or as a recovery-mode warning ([§03_06](#SP_AGA_03_06)) |

### 03_06. Solver and operation issue codes  {#SP_AGA_03_06}

**Solver messages and codes**
- The simulator keeps the untranslated message key of every warning and stop next to the translated text (today it stores only the translated text — a behaviour change, [§06_01](#SP_AGA_06_01)). Codes are matched by prefix on that key:

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
- Under recovery, a timestep that the simulator forces through without convergence raises a `convergence_failed` event naming the first non-converged element (today it writes only a console line — a behaviour change, [§06_01](#SP_AGA_06_01)). A run reports it once.

**Severities and culprit**
- A code reached as a stop is `error`.
- `source_or_wire_loop`, `ground_path_no_resistance`, `singular_matrix`, `matrix_error`, `analysis_failed` and `convergence_failed` are `error` also when they arrive as warnings, because the results are not physically meaningful.
- `wire_loop` as a warning is `warning`: only wire currents are approximated, node voltages stay valid.
- `solver_warning` is `warning`.
- The culprit element is in `elements` when the simulator names one.

**Operation codes**
- All are `error` unless marked otherwise: `not_ready`, `unknown_document`, `unknown_type`, `unknown_element`, `unknown_post`, `unknown_net`, `unknown_property`, `unknown_checkpoint`, `invalid_value`, `value_adjusted` (warning), `off_lattice`, `zero_length`, `id_invalid`, `id_taken`, `ids_regenerated` (warning), `scope_removed` (info), `reserved_label` (warning), `busy`, `scope_limit`, `import_schema_invalid`, `import_element_skipped`, `import_wire_skipped` (warning), `import_setting_invalid` (warning), `import_geometry_adjusted` (warning), `nothing_to_undo`, `nothing_to_redo`, `unsaved_changes`, `render_failed`, `file_unavailable`, `file_not_allowed`, `file_not_found`, `file_error`, `no_path`, `internal_error`.
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

**Free-running loop.** It advances the active running document as today and skips a busy document ([§04_02](#SP_AGA_04_02)).

**Net names and readings.** These come from the target document's own analysed node data, never from the session-wide label registry (which today keeps the labels of whichever document was analysed last).

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

### 05_02. Invariant Checks  {#SP_AGA_05_02}

| Invariant | Verification method |
|-----------|-------------------|
| `ok=false` ⇒ document unchanged | Compare circuit text, IDs and open marks before/after for every error case in §05_01 |
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
| Data/state changes | Undo entry extension fields live in memory only. No file format changes: JSON element keys were already free-form IDs, and the text format is untouched |
| Behaviour changes (each revertible on its own) | (1) The JSON exporter takes keys from the runtime registry, so exported keys change from one global counter (`R1, C2, W3`) to per-prefix numbering. (2) Readings and net names use the document's own analysis instead of the session label registry. (3) Undo menu labels. (4) Background-document operations satisfying R1/R2 by the mechanism of SP_AGA_DEC_04 ([§03_08](#SP_AGA_03_08)). (5) Offscreen per-document render. (6) The free-run loop skips busy documents. (7) Grid size pinned during agent geometry. (8) The declared conditional property contract on elements. (9) The path-based file adapter. (10) Letters-only ID prefixes. (11) Untranslated message keys and the per-document solver event list. (12) The `convergence_failed` event under recovery. (13) Background close without tab switch and per-document console routing. (14) Importers report skipped/failed items to the caller with codes. (15) Model catalogue entries restored on a rejected import. (16) New documents (agent `createDocument`, user new tab) start with the blank-circuit time-step defaults instead of a zero maximum step, which the time-step bar turned into 1 ps. (17) The user's `ontimestep` hook is not called for steps of a document other than the visible one (background runs), as the `onanalyze` hook already is. (18) Setting the time-step bar from code (text import, tab activation) no longer fires its command, so a document's maximum time step is kept exactly instead of being re-quantised to the bar's 1-2-5 table (capped at 10 µs); this also applies to user file loads (audit BL-D01) |
| Artifacts | The Agent API module and its export through the clustered native boundary; no persistent artifacts |
| Dependent modules | [SP_MCP](./mcp-server.sp.md) and [SP_AGS](./agent-skill.sp.md) depend on it; removing the Agent API removes the MCP tool set |
| External contracts | The existing scripting global keeps its documented methods; its element IDs come from the registry (same format) |

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

## Changelog

| Date | Change |
|------|--------|
| 2026-10-01 | Initial version |
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
