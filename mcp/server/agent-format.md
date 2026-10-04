# CircuitJS1 agent format (toolsVersion 1.0)

How an agent describes, edits, checks and measures circuits through the `circuit_*` tools of the CircuitJS1 MCP server. Every tool returns an OperationResult (below). The Agent API underneath is the same in every document; the tools add no circuit logic.

## 1. Documents

- Each open tab is a document with a handle `d1`, `d2`, … (never reused in a session). `circuit_documents` lists, creates, activates and closes them.
- Every tool takes `doc`. Absent means the **active** (visible) document. Pass `doc` to work in a background document: building, running, rendering and saving there never switch or disturb the user's tab.
- Only `circuit_documents activate`, `create` with `activate: true`, `circuit_file open` with `activate: true`, and closing the active document change the visible tab.

## 2. Coordinates: grid cells

- All coordinates are **grid cells**: 1 cell = 16 editor pixels, whatever the user's grid setting. x grows to the right, y grows **downwards**. Range ±4096.
- **Authoring lattice: half cells.** In `add`, `move` and `by` every coordinate is a multiple of 0.5. Anything else is `off_lattice`; input is never snapped or rounded.
- **Imports** accept any multiple of 1/16 cell (whole pixels), so a circuit read back with `circuit_get` re-imports unchanged. Legacy example circuits drawn at odd pixels read as fractional cells; that is exact, not an error.
- `end = start` is `zero_length`; so is an `end` that puts the posts of a multi-post element on one point (a box element with no width or height). An element that sets its own end (a `CustomTransformer` takes its height from `description`; a potentiometer, SCR or triac snaps the end onto its axis) applies it and reports `value_adjusted` with the effective end. An element the editor places only horizontally or vertically (transistors, gates, chips, op-amps, the tapped transformer, ...) rejects an `end` that is neither on the row nor on the column of `start` with `not_axis_aligned`; `Transformer` and `CustomTransformer` take a diagonal `end` (the corner of their box). Derived posts may lie on half cells even for whole-cell points: wire to the record's `posts`.
- Two posts connect when they are at the **same point**. A post lying on the middle of a wire does not connect (`post_on_wire_body`): end the wire at the post, or split it into two wires that meet there.

## 3. Element types (`circuit_types`)

- `circuit_types {}` lists the index; `circuit_types {"type": "Resistor"}` returns the TypeInfo. Names and aliases both work (`DCVoltage` → `VoltageSourceDC`, `Zener` → `ZenerDiode`).
- `geometry`:
  - `two_point`: posts at `start` and `end` (resistor, capacitor, wire, sources).
  - `single`: one post at `start`; `end` only orients the symbol (ground, labelled node, rails).
  - `derived`: posts computed from the two points (transistors, op-amps, chips). `derivedPostsAtDefault` gives each pin's offset from `start` at `defaultSize`.
- `pins` are in post order. Pin names are unique per element (a repeated name gets `_2`, `_3`: `Q`, `Q_2`).
- `defaultSize` (`end − start` of a freshly placed element) and `derivedPostsAtDefault` describe 16-grid documents. The `posts` of an element record are always authoritative: read them back after placing.
- `properties`: `{key, kind, default, unit?, label?, sliderMin?, sliderMax?, readOnly?, choices?}`. `kind` is `quantity` (a unit string such as `"1 kOhm"`), `number`, `bool` or `text`. Slider values are typical-range hints, never limits. `choices` (diode, zener and transistor `model`; `CustomLogic` and `Subcircuit` `model_name`) lists the model names the session holds now; any other name is `invalid_value` (define a new model first: §6 Models).
- `circuit_types {"models": "diode"}` lists the session's models of a kind (`diode`, `transistor`, `logic`, `subcircuit`; `"all"` = every kind), built-in first, then by name; `{"models": "diode", "model": "1N4148"}` returns one (`unknown_model` when the kind has no such model). A record is `{kind, name, builtIn, parameters?, inputs?, outputs?, rules?, info?, showLabel?, pins?, usedBy}`; `usedBy` lists `{doc, ids}` of the open documents whose elements use it. `type` with `models`, `model` without `models`, and `model` with `"all"` are -32602.
- `quantities`: the element quantities (`voltage`, `current`, `power`) an `{element, quantity}` probe or reading accepts — all three or none (transformers, chips and op-amps have none: probe their posts or nets).
- Named nets: a `LabeledNode` with the same `label` text joins its net with every other node of that text. `Ground` defines `gnd`.

## 4. ElementSpec (input) and ElementRecord (output)

ElementSpec, for `circuit_import` and the `add` edit:

| Field | Required | Meaning |
|---|---|---|
| `id` | no | `^[A-Za-z][A-Za-z0-9_]{0,31}$`, unique in the document; default `<idPrefix><n>` (`R1`, `C2`, `GND1`) |
| `type` | yes | type name or alias |
| `start` | yes | `{x, y}` in cells |
| `end` | no | `{x, y}`; default `start + defaultSize` |
| `properties` | no | `{key: value}`; keys from the TypeInfo; missing keys take the type defaults |
| `flags` | no | raw flag bits (rarely needed) |
| `description` | no | text, ≤ 1000 chars |

Property values:
- `quantity` and `number`: a number, or a string that parses fully as a number with an optional SI prefix (`f p n u μ m k K M G T`) and an optional unit matching the property's `unit` (case-sensitive; `Ohm` and `Ω` both work): `4700`, `"4.7k"`, `"10 uF"`, `"5 V"`. Anything else is `invalid_value`.
- `bool`: `true` / `false`. `text`: a string ≤ 1000 chars.
- A key not in the type's list is `unknown_property`; the hint lists the valid keys. A `readOnly` key cannot be `set` (it follows the geometry or another key, e.g. a `LogicInput`'s `state` follows `position`); imports ignore it.
- A value the element clamps or adjusts is applied as adjusted and reported as a `value_adjusted` warning carrying the effective value.

ElementRecord, returned by `circuit_get` and the edit results: `{id, type, start, end, posts, properties, flags, description?}`.
- `posts`: `[{pin, index, at, net, open}]`, every post in post order with its position (cells) and net name; `open` marks a post declared intentionally unconnected.
- `properties` numbers come back as unit strings (`"4.7 kOhm"`). `detail: "concise"` (default) omits properties and flags equal to the type defaults; `"full"` lists them all.
- An ElementRecord is accepted wherever an ElementSpec is (its `posts` are ignored).

Example spec: `{"id": "R1", "type": "Resistor", "start": {"x": 0, "y": 0}, "end": {"x": 4, "y": 0}, "properties": {"resistance": "4.7k"}}`.

## 5. References

| Name | Form | Example |
|---|---|---|
| ElementId | as above | `R1`, `R_load` |
| PostRef | `<ElementId>.<PinName>` or `<ElementId>.#<index>` (0-based); outputs always use the pin name | `Q1.base`, `R1.#1` |
| NetName | `gnd`; a label text; `label:<text>` when the text is `gnd` or starts with `$` or `label:`; else `$<k>` for unlabelled nets | `out`, `$3` |
| CheckpointId | `cp<n>` | `cp2` |

`$<k>` numbers unlabelled nets by their smallest member PostRef, so the names do not depend on element order and survive a re-import, but they change when the circuit changes. Label nets you want to probe.

## 6. Building

### Whole circuit: `circuit_import`

`circuit` is an AgentCircuit object `{elements: ElementSpec[], simulation?, scopes?, models?}`, or a JSON v2 circuit text, or a legacy text circuit, both as strings. `models` (≤ 200 ModelSpec or ModelText entries, dependencies first) are defined before the elements (§6 Models). It replaces the document's circuit atomically and resets the simulation (time 0, initial element state). `simulation` uses the keys of the JSON v2 `simulation` object (`time_step`, `min_time_step`, …); `scopes` is `[{element, quantity?}]`. The result gives `{elements, ids}` and the connectivity delta. The resource `circuitjs://documents/{doc}/circuit` returns a document's circuit in exactly this importable form; `circuit_get` returns the models the circuit uses under `models` on its `offset: 0` page.

### Incremental: `circuit_edit`

`edits` is an ordered batch of 1..200 edits, applied atomically: if any edit is invalid, nothing is applied. Later edits see earlier ones (an `add`ed id can be `set` in the same batch; a deleted id cannot be used).

| op | Fields | Effect |
|---|---|---|
| `add` | `element: ElementSpec` | create an element |
| `move` | `id` + `start` (and optional `end`), or `id` + `by: {dx, dy}` | `start` + `end`: set both points; `start` alone: translate so `start` lands there; `by`: translate by half-cell multiples |
| `delete` | `id` | remove the element, its scope views and its open marks |
| `set` | `id`, `properties`, `flags?` | patch: keys not given keep their value |
| `describe` | `id`, `description` | set the description text |
| `addScope` | `element`, `quantity?` (`voltage`, `current`, `power`) | add an on-screen scope view (20 slots) |
| `removeScope` | `element` | remove that element's scope views |
| `markOpen` | `posts: PostRef[]`, `open?` (default true) | declare posts intentionally unconnected (exempts them from `dangling_post` and `isolated_group`) |
| `defineModel` | `model: ModelSpec` | register a new session model; later edits of the batch may use its name |

The result gives `{applied, created, elements, truncated, models?}`: the records of created, moved and `set` elements (at most 50), so derived post positions are visible at once, and one model record per `defineModel` (`existing: true` when an identical model already existed).

### Models

A model is a named parameter set shared by every document of the session and saved in the files that use it. Diode-family elements (`Diode`, `LED`, `Varactor`, `ZenerDiode`) and `TransistorNPN`/`TransistorPNP` name one by `model`; `CustomLogic` and `Subcircuit` by `model_name`. Setting a `CustomLogic`'s `model_name` (in `add` or `set`) gives it that model's posts; later edits of the same batch already see them.

- **Create-only names.** A definition whose name exists is accepted unchanged when it is identical (same model line) and is otherwise `name_taken`; internal names are always taken. To change a model, define a new name and `set` the elements' `model` to it. Nothing removes a model; `undo` keeps it.
- **ModelSpec** `{kind, name, from?, parameters}` for `diode` and `transistor`: `name` matches `^[A-Za-z0-9][A-Za-z0-9_.+-]{0,39}$`; `from` names a listed model of the same kind whose values are the start (default `default`; unknown: `unknown_model`). Parameters are numbers or unit strings (`quantity` keys take an SI prefix and the unit, `number` keys none); an unknown key is `unknown_property`, a value out of range `invalid_value` naming `parameters.<key>`.
  - diode: `saturation_current` (A, > 0), `series_resistance` (Ohm, ≥ 0), `emission_coefficient` (number, > 0), `breakdown_voltage` (V, ≥ 0; > 0 makes a zener model), `forward_voltage` (V) with `forward_current` (A). The simple form `{forward_voltage: "2.1 V", forward_current: "20 mA"}` (optionally `saturation_current`, `breakdown_voltage`) solves the emission coefficient so the diode drops exactly that voltage at that current, as the editor's simple model does. With all four core keys `forward_voltage` is derived and only checked (the form records return).
  - transistor: `saturation_current` (A), `beta_reverse`, `emission_coefficient_forward`, `emission_coefficient_reverse`, `leakage_be_current` (A), `leakage_bc_current` (A), `leakage_be_emission`, `leakage_bc_emission`, `early_voltage_forward` (V), `early_voltage_reverse` (V), `knee_current_forward` (A), `knee_current_reverse` (A); the last four also take `"inf"` (infinite). Forward beta is the element's `beta` property.
- **Logic ModelSpec** `{kind: "logic", name, inputs, outputs, rules, info?}` for `CustomLogic` (`model_name`): a truth table with named pins (`info` default: the name).
  - `inputs`, `outputs`: 1–32 pin names each, unique as given, each `^[A-Za-z0-9/#:_+-]{1,8}$`. Markup as in the editor: a leading `/` (overbar) or `#` (bubble), `CLK:` (clock mark) and `INV:` (bubble) anywhere. The element's posts are the names without markup, made unique (`Q`, `/Q` → posts `Q`, `Q_2`); inputs are on the left side, outputs on the right, top to bottom. A name that is empty without its markup (`CLK`, `/`) is `invalid_value`: write a clock input as `CLK:C`.
  - `rules`: 1–256 lines of at most 100 characters, `left=right`, tried top to bottom; the first match sets the outputs, otherwise they keep their values. Left: one character per input in order, optionally followed by one per output (its present value): `0`, `1`, `?` (any), `+` (rising edge), `-` (falling edge) or a pattern letter (captures the pin; the same letter again must match it). Right: one character per output: `0`, `1`, `_` (high impedance) or a pattern letter of the left side. `#` lines and blank lines are comments; spaces are ignored; letters are case-insensitive. A line the editor's parser rejects is `invalid_value` naming `rules[<i>]` with its reason (never a dialog).
  - Examples: AND `{"kind": "logic", "name": "and2", "inputs": ["A", "B"], "outputs": ["Y"], "rules": ["11=1", "??=0"]}`; D latch `{"kind": "logic", "name": "dlatch", "inputs": ["D", "E"], "outputs": ["Q", "/Q"], "rules": ["01=01", "11=10", "??ab=ab"]}` (posts `D`, `E`, `Q`, `Q_2`; the last rule holds the outputs while `E` is low); a rising-edge D flip-flop takes `"inputs": ["D", "CLK:C"]` and `"rules": ["0+=01", "1+=10", "??ab=ab"]`.
- **ModelText** `{kind, name, modelText}`: exactly one model line of the text format, as `circuit_get` returns it for a model that has no exact ModelSpec (editor names such as `fwdrop=0.8`, logic models outside the ModelSpec limits such as a 9-character pin name, subcircuit models). A logic line's rules are checked by the same parser (`invalid_value` naming `modelText`). This build defines diode, transistor and logic models; a `subcircuit` entry is accepted only when the session already has an identical one.
- **Legacy text content** given to `circuit_import` follows the same rule: a model line whose name exists with a different definition is `name_taken` (open the file with `circuit_file open` to load its models as the editor does).
- Example batch: `[{"op": "defineModel", "model": {"kind": "diode", "name": "led-green-2v1", "parameters": {"forward_voltage": "2.1 V", "forward_current": "20 mA"}}}, {"op": "add", "element": {"type": "LED", "start": {"x": 0, "y": 0}, "properties": {"model": "led-green-2v1"}}}]`.

## 7. Checking: connectivity

Every successful mutation returns `connectivity: {added, cleared, errorCount, warningCount}`, the issues it added and cleared (at most 50 each; `truncatedAdded` / `truncatedCleared` count the rest). `circuit_connectivity` returns the full report `{nets: [{name, posts, wires, labels}], issues, implicitGround, analysed, truncated}` (at most 200 nets and 100 issues; `netFilter` reads named nets).

| Code | Severity | Meaning |
|---|---|---|
| `dangling_post` | error (warning for one-post elements) | a post (wire ends included) touches no other post |
| `post_on_wire_body` | error | a post lies inside a wire segment without being connected to it |
| `isolated_group` | error | nodes with no path to ground (the simulator would tie them through 100 MΩ) |
| `source_or_wire_loop` | error | a voltage source or wire loop with no resistance |
| `ground_path_no_resistance` | error | a rail or logic input connected to ground with no resistance |
| `wire_loop` | warning | a loop made only of wires (only wire currents are approximated) |
| `current_source_no_path` | warning | a current source with no current path (open, or in series with another current source): it drives no current |
| `overlapping_elements` | warning | two equal elements on the same points, or overlapping collinear wires |
| `no_ground` | warning | no `Ground` element, and either the simulator assumes ground at a voltage source (`implicitGround`) or no element references ground internally; a circuit referenced only through rails, logic inputs, gates or chips needs none |
| `bad_connection` | warning | a post touches another element's body |
| `symbol_overlap` | warning | a wire runs through a part's symbol, two symbols overlap, or a post lies on another part's symbol or lead; wires and symbols may meet only at posts (wire and lead crossings are fine) |
| `reserved_label` | warning | a label text `gnd`, `$…` or `label:…` |
| `single_label` | info | a label used by exactly one node |

Errors left by a successful edit do not reject it: fix them with further edits before running. Floating nodes read as 0 V in the simulator, so trust the report, not a reading.

## 8. Running and measuring

### `circuit_run`

| Argument | Default | Meaning |
|---|---|---|
| `mode` | `span` | `span` or `settle` |
| `span` | — (required in span mode) | simulated seconds, or a unit string (`"20 ms"`) |
| `settle` | `{tolerance: 1e-4, window: 50 × max step, maxSpan: 1}` | settled when every net voltage varies less than `tolerance` (V) over `window` (s); gives up after `maxSpan` (s); numbers or unit strings (`"0.1 mV"`, `"5 ms"`) |
| `budgetMs` | 10000 | wall-time bound, 100..120000 |
| `probes` | none | up to 16 ProbeSpecs |
| `recordFrom` | run start | absolute simulated time from which probes record |
| `maxPoints` | 200 | series points per probe, 10..2000; Σ over probes ≤ 2000 |
| `reset` | false | reset to initial conditions first |

ProbeSpec: exactly one of `{net}`, `{post}` or `{element, quantity}` (`voltage` default, `current`, `power`: the quantity the element reports, as its type defines it — `voltage` is post 0 minus post 1 for most two-post elements, but `plus` minus `minus` for a voltage source and `out` minus `in` for a current source; a BJT's `current` is its collector current, a MOSFET's its drain current; check the sign of `current` and `power` with a known case before relying on it), plus an optional `name`.

Result `data`: `{reason, tStart, tEnd, steps, wallMs, probes: [{name, unit, stats, series: {t, v}}]}`.
- `reason`: `span_reached`, `settled`, `settle_timeout`, `solver_stop`, `stop_trigger`, `budget_exhausted`, `cancelled`. Every reason is `ok: true`. `budget_exhausted`, `settle_timeout`, `stop_trigger` and `cancelled` add a warning with that code; `solver_stop` adds the stop issue (an error), and a document stopped by the solver needs `reset: true` before it runs again.
- **Stop trigger.** A StopTrigger element that fires during a run ends it after that step with `reason: "stop_trigger"` and a `stop_trigger` warning naming the element; the document's running flag is cleared, as in free-running.
- **First sample.** Probes record only solved states. After an import, an edit, a reset or an option change the first sample is taken after the first timestep, so `stats.tStart` is one step after `data.tStart` and `samples = steps`; on an already solved circuit `samples = steps + 1`.
- **Start state.** A fresh import starts from the state saved in the circuit; `reset: true` from initial conditions; a run without `reset` continues where the last run (or free-running) left off (`tStart` = previous `tEnd`). Name the start of every experiment: pass `reset` explicitly.
- **Determinism.** With `reset: true`, the same circuit and arguments give the same result, except for circuits with noise sources (unseeded). Draw Monte Carlo values yourself and apply them with `set` edits.
- `stats`: `{samples, tStart, tEnd, min, max, mean, rms, peakToPeak, final, frequency?, dutyCycle?, riseTime?}`; `mean` and `rms` are time-weighted. `frequency` counts rising crossings of `mean` (5 % hysteresis) and is absent with fewer than 2 crossings; `dutyCycle` is absent with it; `riseTime` (10 %→90 % of min→max on the first rising transition) is absent when there is none. A probe with no sample has `stats: {samples: 0}`.
- `series` keeps the minimum and maximum of each time bucket, so it never exceeds `maxPoints` and keeps the extremes. Values carry 6 significant digits, times 9. While the recorded window has at most `maxPoints` samples (steps) per probe the series is raw and all probes share the same `t`; beyond that each probe keeps its own min/max times, so series of different probes differ in times and length: record a short window to compare waveforms point by point.
- Solver problems during a run (`convergence_failed`, `singular_matrix`, …) are issues of a successful result: read them before trusting a waveform. A non-finite sample is dropped with a `solver_warning`.
- Runs on one document are exclusive (`busy`); a user edit of that document cancels the run (`cancelled`, with the partial data).
- The time step is the document's (`circuit_sim configure` sets it, in seconds or unit strings); with `autoTimeStep` it halves only on Newton failure (there is no error control), so accuracy rests on the step you choose.

### `circuit_read`

Instant values at the current simulated time, without stepping: `targets` of 1..100 ProbeSpecs → `{t, values: [{name, value, unit}]}`. A missing net, post or element is an error, never a 0 V reading; `value` is `null` only for a non-finite solver value.

### `circuit_sim`

`run` / `stop` set free-running (only the visible tab advances); `reset` sets time 0 and initial state; `configure` sets `{maxTimeStep, minTimeStep, autoTimeStep}`. Free-running is for the user to watch; measure with `circuit_run`.

### `circuit_diagnostics`

`{stopped, stop?, warning?, events, recovering, lastImport, simTime, running, timeStep}`; `log: {since, limit}` adds `{entries: [{seq, text}], cursor, gap}` of the session log.

## 9. Rendering (`circuit_render`)

- `format`: `png` (default; the image is a separate image part, `data.content` reads `"<image>"`) or `svg` (text).
- `scale`: 0.25..4. The image covers every element and every text it draws (value labels included) plus a 1-cell margin, at 16 px per cell times `scale`. An image over 16384 px on a side or 40 megapixels is `invalid_value` naming `scale`. An empty document gives a blank 32 × 32 image.
- Printable colours on a white background (whatever the user's Printable option), no current dots; drawing never changes a scope's graph. `includeScopes: true` adds the scope panels.
- An SVG text over the tool-result limit is `result_too_large`: use `png` or a lower `scale`. If the vector exporter cannot load, `render_failed`: retry with `png`.

## 10. History and checkpoints

- All successful agent mutations since the last checkpoint form one open **transaction**: one undo entry for the user. `transaction: {open, pendingEdits}` is on every mutating result.
- `circuit_checkpoint {comment}` seals it as a named entry (the user sees "Undo: <comment>") and returns `checkpointId`; with nothing changed, `noChanges: true`.
- The transaction is also sealed automatically (comment "agent edits (auto)") by a user edit of the same document, any save, `undo`, `restore`, and 300 s without agent mutations. A checkpoint after such a seal has nothing to seal (`noChanges: true`) and cannot rename the auto entry: checkpoint right after the edits, before long measurement runs.
- `circuit_history`: `list`, `undo` / `redo` (`steps`), `restore` (`checkpointId`: back to the state before that checkpoint). Element IDs survive undo and redo; a deleted ID's number is not reused.

## 11. Files (`circuit_file`)

- `open` reads a file into a new document (`into: "new"`, the default) or into an open document (`into: "d2"`, replacing its circuit like an import). `save` writes the document (`path` absent: its current path; `format` from the extension unless given). `export` returns the content as `text` or `json` (default `json`, element keys = element IDs) without touching files.
- **File rules.** The path must be absolute and end in `.txt` or `.json` (case-insensitive), after following symbolic links too. Reads are at most 10 MB, and the content must be a circuit (JSON with `schema.format = "circuitjs"` and version `2.x`, or text whose every line is a circuit line). `save` overwrites an existing file only when it is empty or a circuit. Parent directories are never created. Violations are `file_not_allowed`; a missing file `file_not_found`; system failures `file_error`; saving a never-saved document without `path` `no_path`; a browser build has no files (`file_unavailable`). A rejected open names line numbers and counts only, never file content.

## 12. Results and limits

OperationResult: `{ok, data?, issues, truncatedIssues, connectivity?, transaction?}`.
- `ok: false` (an MCP `isError` result) means the call was rejected and **nothing changed**; `issues` holds at least one error saying why, each with a `hint`.
- `ok: true` results may carry warnings and info issues, and the problems a successful operation found (solver issues of a run, connectivity errors left by an edit).
- Issues: `{code, severity, message, elements?, posts?, at?, hint, key}`; at most 50 per list, errors first. `key` identifies the same issue across reports.
- Bad tool arguments (wrong type, missing required field, unknown argument, unknown enumeration value) are JSON-RPC errors -32602 naming the field; range and format problems are `invalid_value` results with a hint.

Tool results stay within 60 000 characters of text:
- `circuit_get` over the limit is re-read with `detail: "concise"`, then with a halved `limit`; continue at `data.nextOffset`.
- `circuit_connectivity` over the limit is re-read with `includeNets: false`; read nets with `netFilter`.
- `circuit_diagnostics` halves the log `limit`; continue from `log.cursor`.
- A reduced result's text starts with a note naming the reduced arguments; `structuredContent` is the result of that reduced call.
- An SVG render or an export over the limit is `result_too_large` (`isError`).
- Runs and edits are never repeated; their caps keep them within the limit.
- Resources (`circuitjs://…`) are returned whole.

## 13. Issue codes

Operation errors: `not_ready`, `unknown_document`, `unknown_type`, `unknown_element`, `unknown_post`, `unknown_net`, `unknown_property`, `unknown_checkpoint`, `unknown_model` (a `from` or `circuit_types` `model` that names no listed model), `name_taken` (a model name that exists with a different definition, or an internal one), `invalid_value`, `off_lattice`, `zero_length`, `not_axis_aligned`, `id_invalid`, `id_taken`, `busy`, `scope_limit`, `import_schema_invalid`, `import_element_skipped`, `nothing_to_undo`, `nothing_to_redo`, `unsaved_changes`, `render_failed`, `file_unavailable`, `file_not_allowed`, `file_not_found`, `file_error`, `no_path`, `internal_error`, `result_too_large` (server).

Warnings and info: `value_adjusted`, `ids_regenerated`, `import_wire_skipped`, `import_setting_invalid`, `import_geometry_adjusted`, `scope_limit` (import), `reserved_label`, `scope_removed` (info); run ends `budget_exhausted`, `settle_timeout`, `stop_trigger`, `cancelled`.

Solver codes (in run issues, `circuit_diagnostics` events and stops): `singular_matrix`, `source_or_wire_loop`, `ground_path_no_resistance`, `matrix_error`, `analysis_failed` and `convergence_failed` are errors (results not physically meaningful); `wire_loop` is a warning (only wire currents are approximated); `solver_stop` (other stops, error) and `solver_warning` (other warnings). The simulator recovers from non-convergence and keeps going, so these arrive while a run continues; the culprit element is in `elements`.

## 14. Workflow

1. `circuit_types` for the types, pins and property keys you need.
2. Build with `circuit_import` (whole circuit) or `circuit_edit` (incremental), in a background document if the user is working.
3. Read the connectivity delta; fix every error; `circuit_connectivity` for the full report.
4. `circuit_run` with `reset: true`, a `span`, probes on labelled nets and a `budgetMs`.
5. Check `reason` and the issues, then the probe stats; fix and repeat.
6. `circuit_checkpoint` after each working step; `circuit_render` to show the result; `circuit_file save` to keep it.
