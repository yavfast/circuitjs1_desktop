# Diagnostics: issue codes and symptoms

Every issue has `code`, `severity`, `message`, `hint` and, where known, the `elements`, `posts` and `at` involved. Read the hint first; this table adds the usual cause in CircuitJS1 and the call that confirms the fix.
- **Rejected calls.** `ok: false` (an `isError` tool result) means nothing changed.
- **Accepted calls with problems.** `ok: true` results can still carry error issues: connectivity errors left by an edit, solver errors met by a run. Those make the circuit, or that run's measurements, untrustworthy until fixed.

## Contents

- Connectivity issues (every mutation and `circuit_connectivity`)
- Solver issues (runs, `circuit_diagnostics`)
- Run end reasons
- Operation errors and warnings: references and values; geometry and IDs; imports; documents, history and runs; files and output
- Symptoms without an issue code

## Connectivity issues

| Code | Severity | Typical cause | Fix | Confirm with |
|---|---|---|---|---|
| `dangling_post` | error (warning on one-post elements) | A wire end or part post misses every other post by a cell or half a cell; a post placed on a wire body; a pin you did not wire (e.g. a 555 `ctl`) | Move the post onto the other post (read `posts` from `circuit_get`), add the missing wire, or `markOpen` a post that stays unconnected on purpose | `connectivity.cleared` of the edit; `circuit_connectivity` |
| `post_on_wire_body` | error | A T-junction drawn by ending a part on the middle of a wire | Split the wire into two wires that meet at the post, or end the wire there | `circuit_connectivity` |
| `isolated_group` | error | A part or subcircuit with no path to ground: missing ground wire, an input coupled only through a capacitor | Connect the group to ground or to the rest of the circuit (add a load or bias resistor); `markOpen` its posts if it is unused | `circuit_connectivity` (`implicitGround`, issues) |
| `source_or_wire_loop` | error | A voltage source shorted by a wire, two voltage sources in parallel, or a loop of sources and wires without resistance; runs go on but currents are meaningless (a shorted 5 V source reads 5e12 A) | Remove the short, or add a series `Resistor` | `circuit_connectivity`; a short `circuit_run` with `reset: true` without this issue |
| `overlapping_elements` | warning | The same part added twice on the same points (a repeated `add`), or collinear wires that overlap | Delete the duplicate; shorten the overlapping wire | `circuit_get` |
| `no_ground` | warning | No `Ground` element, and either a voltage source is present (the simulator then assumes ground at the first voltage source's post, so voltages may be referenced to a node you did not choose) or no element references ground internally. A circuit referenced only through rails, logic inputs, gates or chips needs no `Ground` and raises nothing | Add a `Ground` at the reference node; never add one you cannot connect | `circuit_connectivity` → `implicitGround: false` |
| `bad_connection` | warning | A post touches another element's body without meeting a post (usually with `post_on_wire_body`) | Move the post onto the other element's post, or away from its body | `circuit_connectivity` |
| `symbol_overlap` | warning | A wire drawn through a part's symbol (e.g. a `Ground` laid along a rail wire), two parts drawn over each other, or a junction post placed on a part's body or lead instead of at its post. `at` is the centre of the symbol that is crossed | Move one of them, or route the wire around the symbol: wires and symbols may meet only at posts; turn a `Ground` or label so it hangs off the wire end | `connectivity.cleared` of the `move` (moving the crossing wire or part keeps the key while it still overlaps); `circuit_connectivity` |
| `reserved_label` | warning | A label text `gnd`, starting with `$` or with `label:` | Rename the label | `circuit_connectivity` net names |
| `current_source_no_path` | warning | A `CurrentSource` in series with another current source, or left open (e.g. on the unselected throw of a switch): the simulator puts 100 MΩ in its place, so it drives no current | Give it a resistive return path; of two current sources in series, replace one by a `Resistor` | `circuit_connectivity` |
| `single_label` | info | A label used by only one node: normal for a probe label | Nothing, unless you meant to join two places (check the spelling) | — |

## Solver issues

They appear in run results, in `circuit_diagnostics` (`events`, `stop`, `warning`) and, for loops and paths found when the circuit is analysed (`source_or_wire_loop`, `ground_path_no_resistance`, `wire_loop`), in the connectivity report. The simulator recovers from most of them and keeps running, so a run can end with `span_reached` and still carry them: treat the run's numbers as invalid while any error below is present. The culprit element is in `elements`.

| Code | Severity | Typical cause | Fix | Confirm with |
|---|---|---|---|---|
| `singular_matrix` | error | A node whose voltage the circuit does not fix, or fixes twice: ideal parts in conflict, a subcircuit joined to the rest only through ideal sources | Clear the connectivity errors first; give every node a resistive DC path; add a small series resistance between ideal parts | `circuit_run` with `reset: true` and no solver issues |
| `source_or_wire_loop` | error | As in the connectivity table | As above | As above |
| `ground_path_no_resistance` | error | A `Rail` or `LogicInput` connected to ground through wires (or voltage sources) only | Remove the short, or add a series resistance | `circuit_connectivity` |
| `convergence_failed` | error | A nonlinear part (diode, transistor, MOSFET, gate) far outside sensible values, a time step too large for a fast edge, an unbounded positive-feedback loop | Check the named element's values and its bias; reduce the time step (`circuit_sim configure`); add series resistance at switching nodes | `circuit_diagnostics` (`recovering: false`, no event) after a reset run |
| `analysis_failed` | error | The circuit could not be analysed (an element threw while building the matrix) | Read the message and `circuit_diagnostics {"log": {"since": 0}}`; replace or delete the named element | `circuit_run` with `reset: true` |
| `matrix_error` | error | A degenerate matrix met while simplifying it: the same family of causes as `singular_matrix`, or extreme values (0 Ω, 0 F) | As for `singular_matrix`; use realistic, non-zero values | `circuit_run` with `reset: true` |
| `wire_loop` | warning | A loop made only of wires: node voltages stay valid, only the wire currents are approximated | Remove the redundant wire if wire currents matter | `circuit_connectivity` |
| `solver_stop` | error | Any other stop of the solver; also an exception during a run (with `internal_error`) | Read the message and the stop issue in `circuit_diagnostics`; fix, then run with `reset: true` (a stopped document does not run without it) | `circuit_diagnostics` → `stopped: false` |
| `solver_warning` | warning | Any other solver warning; also a probe sample that was not finite (dropped) | Read the message; check the named probe and element | The next run |

## Run end reasons

Every `reason` is `ok: true`. These four also add a warning issue with the same code.

| Code | Severity | Typical cause | Fix | Confirm with |
|---|---|---|---|---|
| `budget_exhausted` | warning | `span` / time step means more steps than fit in `budgetMs` (see the simulation reference for step rates) | Continue with another run without `reset` (it resumes at `tEnd`), raise `budgetMs` (≤ 120000), shorten the span, or raise the time step deliberately | `reason: "span_reached"` |
| `settle_timeout` | warning | The circuit oscillates or is driven by an AC source, or its slowest τ exceeds `settle.maxSpan`, or `tolerance` is too tight | Use a span run and read `stats.mean`; raise `maxSpan`; loosen `tolerance` | `reason: "settled"` |
| `stop_trigger` | warning | A `StopTrigger` element in the circuit fired; the run ended after that step | Intended: read the data up to `tEnd`. Otherwise change or delete the trigger | The run's `tEnd` |
| `cancelled` | warning | The user edited the document during the run; partial data is returned | Re-run with `reset: true` once the user is done; work in a background document | A complete run |

## Operation errors and warnings

All are errors (the call was rejected) unless marked otherwise.

### References and values

| Code | Severity | Typical cause | Fix | Confirm with |
|---|---|---|---|---|
| `not_ready` | error | The app is still starting | Wait a few seconds and retry | `circuit_documents {"action": "list"}` |
| `unknown_document` | error | The handle was closed or never existed | List documents; create one | `circuit_documents {"action": "list"}` |
| `unknown_type` | error | A misspelt type name; the hint lists the closest names | Use a name from the hint or the index | `circuit_types {"filter": "<word>"}` |
| `unknown_element` | error | An ID that does not exist (deleted, renamed, or from another document) | Re-read the IDs | `circuit_get` |
| `unknown_post` | error | A wrong pin name in a PostRef (`R1.pin3`, `Q1.b`) | Use the pin names of the type, or `<id>.#<index>` | `circuit_types {"type": "..."}` → `pins`; `circuit_get` → `posts` |
| `unknown_net` | error | A probe on a misspelt label, or on a `$k` name that changed after an edit | Probe a labelled net, or re-read the net names | `circuit_connectivity` → `nets` |
| `unknown_property` | error | A key the type does not have; the hint lists the valid keys | Use a listed key | `circuit_types {"type": "..."}` → `properties` |
| `unknown_checkpoint` | error | A `checkpointId` that is not in the undo stack (already undone or never made) | Pick one from the list | `circuit_history {"action": "list"}` |
| `invalid_value` | error | A unit string that does not parse (`"4.7 kOhms"`; units are case-sensitive), an argument out of range (`budgetMs`, `scale`, Σ `maxPoints` > 2000), two probes with the same name, a `set` of a read-only key, a run on an empty document, a diode or transistor `model` the app does not hold (the hint lists the available ones), an element probe on a type without `quantities` | Follow the message and hint: fix the value, name each probe, add elements first | The same call again |
| `value_adjusted` | warning | The element clamped or overrode the value (e.g. a `Transformer` `coupling` above 1 is clamped), or replaced the `end` you gave by its own (`CustomTransformer`, `Potentiometer`); the message carries the effective value | Use the effective value or end, or set the controlling key | `circuit_get {"ids": [...], "detail": "full"}` |

### Geometry and IDs

| Code | Severity | Typical cause | Fix | Confirm with |
|---|---|---|---|---|
| `off_lattice` | error | A coordinate in `add`/`move`/`by` that is not a multiple of 0.5 cell, or an imported one not a multiple of 1/16 | Use whole cells for new parts | The same edit again |
| `zero_length` | error | `end` equal to `start`, or an `end` that puts a multi-post part's posts on one point (a box with no width or height) | Omit `end` (default size) or give an end that spans the part (`defaultSize` of `circuit_types`) | The same edit again |
| `not_axis_aligned` | error | A diagonal `end` (neither on the row nor on the column of `start`) for a part the editor places only horizontally or vertically (transistor, gate, chip, op-amp, tapped transformer, ...) | Give `end` = `start` + `defaultSize` of `circuit_types`, or omit `end` | The same edit again |
| `id_invalid` | error | An ID that does not match `^[A-Za-z][A-Za-z0-9_]{0,31}$` (starts with a digit, has `-` or spaces) | Use letters, digits and `_` | The same edit again |
| `id_taken` | error | An `add` with an ID already in the document (often a repeated batch) | Choose another ID, or omit `id`; check whether the first batch was already applied | `circuit_get` |
| `ids_regenerated` | warning | Imported JSON keys that are not valid or unique IDs; an undo whose element count changed | Re-read the IDs before using them | `circuit_get` |
| `scope_removed` | info | A deleted element had scope views; they were removed | Nothing; re-add with `addScope` if needed | `circuit_get` → `scopes` |
| `scope_limit` | error (`addScope`); warning (import) | More than 20 scope views | Remove views with `removeScope` | `circuit_get` → `scopes` |

### Imports

| Code | Severity | Typical cause | Fix | Confirm with |
|---|---|---|---|---|
| `import_schema_invalid` | error | A JSON v2 text without `schema: {"format": "circuitjs", "version": "2.x"}` or failing the schema | Pass an AgentCircuit object instead, or fix the schema block | `circuit_import` again |
| `import_element_skipped` | error | An element the factory cannot create, or an unknown or unparseable line in a text circuit; the whole import is rejected | Fix or remove the named element or line | `circuit_import` again |
| `import_wire_skipped` | warning | A JSON auto-wire (`connected_to`) whose target is missing | Add the missing wire with an `add` edit | `circuit_connectivity` |
| `import_setting_invalid` | warning | A `simulation` setting that is not valid; its default was kept | Fix the key or value (keys of the JSON v2 `simulation` object) | `circuit_get` → `simulation` |
| `import_geometry_adjusted` | warning | An element re-applied its geometry differently (bounds, swapped points) | Read the element's `posts` back and wire to those | `circuit_get` |

### Documents, history and runs

| Code | Severity | Typical cause | Fix | Confirm with |
|---|---|---|---|---|
| `busy` | error | Another run is going on in that document (yours or another client's) | Wait for it to end, or work in another document | `circuit_documents {"action": "list"}` → `busy` |
| `unsaved_changes` | error | Closing a modified document without `discardChanges` | Ask the user; save first or pass `discardChanges: true` | `circuit_documents {"action": "list"}` |
| `nothing_to_undo` | error | Fewer undo entries than `steps` | Lower `steps` | `circuit_history {"action": "list"}` |
| `nothing_to_redo` | error | Fewer redo entries than `steps` | Lower `steps` | `circuit_history {"action": "list"}` |
| `internal_error` | error | An unexpected exception; the change was rolled back | Report the message to the user; retry once; read the log | `circuit_diagnostics {"log": {"since": 0, "limit": 20}}` |

### Files and output

| Code | Severity | Typical cause | Fix | Confirm with |
|---|---|---|---|---|
| `file_unavailable` | error | A browser build of the app: there is no file system | Use `circuit_file {"action": "export"}` and hand the text to the user | — |
| `file_not_allowed` | error | A relative path, an extension other than `.txt`/`.json`, a file over 10 MB, content that is not a circuit, or overwriting a non-circuit file | Use an absolute `.txt`/`.json` path; never overwrite other files | `circuit_file` again |
| `file_not_found` | error | The file, or its parent directory, does not exist (open) | Check the path with the user | `circuit_file {"action": "open"}` again |
| `file_error` | error | A system failure: a missing parent directory on save (never created), no permission, the target changed during the save | Choose an existing directory; retry | `circuit_file {"action": "save"}` again |
| `no_path` | error | `save` without `path` on a document that was never saved | Give an absolute `path` | `circuit_file {"action": "save", "path": "..."}` |
| `render_failed` | error | The SVG exporter could not load, or the image could not be encoded | Retry with `format: "png"` | `circuit_render` |
| `result_too_large` | error (server) | An SVG render or an export over the 60 000-character tool-result limit | `png` or a lower `scale`; `save` to a file, or read with `circuit_get` pages | The same tool with the smaller form |

## Symptoms without an issue code

**A flat 0 V trace.**
- **Floating node.** Floating nodes read 0 V instead of failing: look for `dangling_post` or `isolated_group` in `circuit_connectivity`.
- **Wrong net.** The probe names an unlabelled `$k` net that is not the node you meant: label the node and probe the label.
- **Source off.** The source is at 0: check `max_voltage`, a `LogicInput` at `position: 0`, a `Switch` at `"open"`.
- **Polarity.** A diode, LED or source drawn the other way round: compare with a pattern, or `circuit_read` both ends.
- **Window.** The probe records from `recordFrom`: check `stats.samples` and `tStart`/`tEnd`.
- **Current source idle.** A `current_source_no_path` warning: the source has no return path and drives 0 A.

**An oscillator that never starts.**
- **Start state.** A run with `reset: true` starts from initial conditions, which can be a balanced state that never tips; a fresh import starts from the state saved in the circuit instead. Compare both, and break the symmetry (a slightly different value in one branch, an initial capacitor voltage).
- **Too short.** The run does not reach the first cycle: the first 555 cycle is longer than the rest, and a relaxation oscillator first charges from 0 V. Run longer, with `recordFrom` after the start.
- **Too coarse.** The time step is too large for the edges: reduce it.
- **Missing parts.** The 555 `rst` is not at the supply, or `ctl` is left open (`dangling_post`).

**A result that changes with the time step.**
- **Step too large.** With `autoTimeStep` the step halves only when Newton iteration fails; there is no error control. Halve `maxTimeStep` and re-run with `reset: true`; keep the step at which the measurement stops changing (within 1 %).
- **Rule of thumb.** The step should be at most 1/50 of the smallest RC or L/R constant and of the period of the highest frequency of interest.

**A slow run.**
- **Steps.** `steps = span / time step`. Small circuits run at roughly 10 000–60 000 steps per second, large transistor circuits at 1 000–2 000. A run of 100 000 steps takes seconds; a million is too many.
- **Fixes.** Raise the time step within the rule above, shorten the span, or chain runs without `reset`.
- **Convergence.** A circuit that keeps failing to converge drops to under 100 steps per second: look for `convergence_failed` and `recovering: true` in `circuit_diagnostics`.
- **Visible tab.** A free-running visible tab slows other runs: `circuit_sim {"action": "stop"}` on it if the user agrees.
