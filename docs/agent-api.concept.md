# Agent API  {#C_AGA}

> **Code:** C_AGA
> **Status:** draft
> **Created:** 2026-10-01
> **Updated:** 2026-10-01
> **Author:** main
> **Owner:** app-shell maintainers (layer L3: the document/session shell)
> **Complexity:** high
> **Criticality:** core
>
> **Depends on:** [C_DOC](./document-model.concept.md), [C_UND](./commands-undo.concept.md), [C_SIM](./simulator-engine.concept.md), [C_NET](./netlist-graph.concept.md), [C_EIC](./edit-info-contract.concept.md), [C_IOF](./io-framework.concept.md), [C_ELB](./element-base.concept.md), [C_GEO](./geometry.concept.md), [C_SCP](./scope-visualization.concept.md), [C_LOG](./session-logging.concept.md), [C_FBR](./browser-file-bridge.concept.md), [C_EPS](./elements-passives.concept.md), [C_APC](./app-controller.concept.md), [C_RND](./rendering-primitives.concept.md)
> **Used by:** [C_MCP](./mcp-server.concept.md), [C_AGS](./agent-skill.concept.md)
> **Epic:** [E_AGT](./agent-automation.epic.md)
> **Spike:** [mcp-agent-bridge.spike.md](./mcp-agent-bridge.spike.md)
> **Specification:** [SP_AGA](./agent-api.sp.md)
> **Plan:** [agent-api.plan.md](./agent-api.plan.md)
>
> The application-side operations an AI agent uses to create, edit, inspect, simulate, measure and debug circuits, independent of any transport. Read it before writing MCP tools ([C_MCP](./mcp-server.concept.md)) or the agent skill ([C_AGS](./agent-skill.concept.md)), or when touching element identity, undo labelling or label scoping. It defines the agent's view of a circuit (documents, stable element IDs, grid-cell geometry, nets) and the operations on it: edits, connectivity reports, bounded runs with probes, diagnostics and checkpoints.

## Contents

- [1. Philosophy](#C_AGA_01) — why agents need their own operation set and which constraints bound it
- [2. Domain Model](#C_AGA_02) — documents, element IDs, grid cells, nets, transactions, probes and the operation result
- [3. Mechanisms](#C_AGA_03) — geometry, identity, connectivity report, bounded runs, transactions, diagnostics, edge cases
- [4. Integration Points](#C_AGA_04) — existing concepts it changes or uses, and the operation groups it exposes
- [5. Design Decisions](#C_AGA_DEC) — coordinates, identity, document addressing, undo grouping

## 1. Philosophy  {#C_AGA_01}

### 1.1. Core Principle  {#C_AGA_01_01}

An agent works by a closed loop of acting, observing the consequence and correcting. The application today offers a scripting global built for page embedding: it replaces the whole circuit on every import, edits properties of only four element types, reports topology by geometric adjacency instead of electrical nets, has no undo, addresses only the active tab, and reports solver failures only as a status string ([spike, Entry 2](./mcp-agent-bridge.spike.md)). An agent cannot close its loop on that surface.

The Agent API is the operation set built for the loop. It has three properties:
- **Every operation answers.** An operation returns what it did, the identities it touched, and every problem it found. No consequence is left in a log only.
- **Identities are stable.** An element keeps one identity from creation to deletion, through undo and JSON save/reload; reloading a legacy text file reassigns the same IDs deterministically.
- **Observation is electrical.** Connectivity is reported as nets and their members, and failures as coded issues tied to elements, not as pictures or free text.

### 1.2. Design Constraints  {#C_AGA_01_02}

- **Transport-free.** The Agent API knows nothing about MCP, HTTP or JSON-RPC. [C_MCP](./mcp-server.concept.md) projects it into tools. Tests and the existing scripting global can call the same operations.
- **One authority per concern.** Element construction goes through the existing element factories (RULE_ARCH_005). Parameter values go through the elements' file-format property contract (the keys the JSON format already reads and writes), with labels and slider hints taken from the universal editable-parameter contract of [C_EIC](./edit-info-contract.concept.md). Every element type is editable, not only the four the current setter covers.
- **Correct scope.** Element identity, net labels and agent transactions are per document; the catalogue is per session (RULE_ARCH_006).
- **Atomic operations.** An operation either applies completely or changes nothing and reports why.
- **The existing scripting global keeps working.** Its documented methods keep their behaviour, except that element IDs come from the single registry of [§3.2](#C_AGA_03_02).
- **Rollback.** Everything is additive except a set of behavioural changes to existing mechanisms, each revertible on its own and listed in [SP_AGA_06_01](./agent-api.sp.md#SP_AGA_06_01): among them one ID scheme for runtime and JSON export, per-document net labels, labelled undo entries, and document-parameterized snapshot, analysis and rendering. Removing the Agent API removes [C_MCP](./mcp-server.concept.md) with it; saved files stay loadable either way, because JSON element IDs are already map keys and the text format does not change.
- **Reuse.** The JSON element schema of [C_IOF](./io-framework.concept.md) is reused for whole-circuit import and element creation. The connectivity report builds on the node analysis of [C_SIM](./simulator-engine.concept.md)/[C_NET](./netlist-graph.concept.md) and does not re-derive nets. The scope buffers of [C_SCP](./scope-visualization.concept.md) are not reused for probes, because they hold per-pixel min/max values with no time axis.

**This concept IS:** the agent-facing operation set and the changes to identity, label scoping and undo labelling it needs.

**This concept IS NOT:**
- automatic layout or netlist-to-schematic conversion. The agent supplies coordinates ([C_AGA_DEC_01](#C_AGA_DEC_01)).
- frequency-domain analysis. The simulator is transient-only; frequency figures are measured from transient runs.
- arbitrary code execution.
- a chat UI.
- a fix for the drift of the existing scripting global beyond shared identity.

## 2. Domain Model  {#C_AGA_02}

### 2.1. Key Entities  {#C_AGA_02_01}

| Entity | Scope | Meaning |
|--------|-------|---------|
| **Document handle** | session | Stable identity of one open tab for the app session; every operation takes one, defaulting to the active document |
| **Element ID** | document | Human-readable identity (`R1`, `Q3`, or an agent-chosen name such as `R_load`), unique in its document, kept through edits, undo/redo, save and reload |
| **Grid cell** | — | The agent's coordinate unit: one cell equals the standard editor grid pitch. Agent-written positions lie on a half-cell lattice; positions read from existing circuits are exact (any multiple of 1/16 cell) |
| **Post** | element | A connection point of an element, identified as `elementId.pinName`, with a cell position |
| **Net** | document | A set of posts joined electrically (coincident posts, wires, same-named labels, ground). Named by its label, `gnd`, or a generated name |
| **Element type** | session | A catalogue entry: type name, pin names, how its posts derive from its two defining points, editable properties with units, defaults and slider hints |
| **Operation result** | — | `ok`, the affected element IDs, the resulting post positions, and a list of issues |
| **Issue** | — | `{code, severity, elements/posts, cell location, message, fix hint}`. Shared by connectivity, validation, import and solver diagnostics |
| **Connectivity report** | document | Nets with their members, plus connectivity issues ([§3.3](#C_AGA_03_03)) |
| **Agent transaction** | document | The open group of agent mutations since the last checkpoint |
| **Checkpoint** | document | A sealed transaction stored as one undo entry carrying the agent's comment |
| **Run** | document | A bounded advance of simulated time under the agent's control ([§3.4](#C_AGA_03_04)) |
| **Probe** | run | A sampled quantity (net voltage, element voltage/current/power, post voltage) recorded at every step of a run |
| **Probe result** | run | Statistics plus a size-bounded, time-stamped decimated series |

```
Session ── Element catalogue (types, pins, properties)
   │
   └── Document handle ──┬── Elements (by Element ID) ── Posts (cell positions)
                         ├── Nets (from analysis)  ◄── Connectivity report
                         ├── Agent transaction ── Checkpoints (undo history, commented)
                         ├── Runs ── Probes ── Probe results
                         └── Diagnostics (stop state, warnings, recent log)
```

### 2.2. Data Flows  {#C_AGA_02_02}

**Mutation flow.**
1. The agent calls an edit operation with a document handle and grid-cell geometry.
2. The input is validated against the catalogue. Invalid input is rejected whole, with issues.
3. The change is applied inside the document's open agent transaction.
4. The topology is re-analysed immediately, even while the simulation runs, so the reply is fresh.
5. The reply is an operation result plus the connectivity issues the change introduced or cleared.

**Observation flow.**
1. The agent asks for a connectivity report, an element/net reading, diagnostics, or a rendered image.
2. Nothing is mutated, and the open transaction is untouched.

**Run flow.**
1. The agent starts a run with a simulated-time span, a wall-clock budget and a probe list.
2. Free-running of that document pauses.
3. Simulated time advances in slices that yield to the UI between slices.
4. The probes record every step.
5. The run ends at the span, at a solver stop, or at the budget.
6. Free-running resumes if it was on.
7. The reply carries the final time, the stop state and its issues, and the probe results.

**Checkpoint flow.**
1. The agent seals the open transaction with a comment.
2. The circuit before the transaction becomes one undo entry labelled with that comment.
3. A new transaction opens empty.

## 3. Mechanisms  {#C_AGA_03}

### 3.1. Grid-cell geometry  {#C_AGA_03_01}

- **Coordinates.** The agent reads and writes positions in grid cells.
  - One cell equals the standard grid pitch. It is fixed and does not follow the user's small-grid display preference, so coordinates mean the same thing for every user.
  - Agent-written positions lie on a half-cell lattice, so circuits drawn on the fine grid stay addressable. Positions read from existing circuits are reported exactly and re-import unchanged.
  - Coordinates never depend on a grid setting. Element types that size themselves from the grid are sized by the target document's own grid option (the one a reload of that document applies), never by the display preference of another tab; the catalogue is measured on the standard grid.
  - A written position off that lattice is rejected, never silently snapped.
- **Element definition.** Every element is defined by two points, its start and end cells, as in the editor.
  - A two-terminal element's posts are those two points.
  - A multi-terminal element's posts derive from them by the element's own geometry rules.
  - Every placement result returns the resulting cell position of every post, so the agent learns where the derived pins of transistors, op-amps and chips actually landed.
- **Whole-circuit import.** It accepts the existing JSON circuit schema with element geometry in cells. It is the "build in one call" mode.
- **Incremental edits.** These cover:
  - adding elements and wires;
  - moving and deleting elements;
  - setting properties (patch semantics, unit-bearing values);
  - adding and removing on-screen scope views for the user.

  A batch of edits in one call is applied atomically.

### 3.2. Stable element identity  {#C_AGA_03_02}

> **Criticality:** critical

One ID registry per document is the only source of element IDs. The runtime scripting global, the JSON exporter and the Agent API all use it.

- **Creation.**
  - A new element receives `<prefix><n>`, where the prefix comes from the element type and `n` is the next free number for that prefix in the document.
  - The agent may supply its own ID instead, if it is unique in the document and matches the ID pattern.
  - IDs are never reused within a *content lifetime*: from document creation or content replacement (import, open, clear) to the next replacement. Counters are raised to cover every ID that enters the document, including IDs restored by undo, so a generated ID never duplicates a live one.
- **Undo/redo.** The undo snapshot carries the ID of every element alongside the circuit text, and restoring a snapshot restores those IDs.
- **Pin names.** Posts are addressed as `elementId.pinName`. Pin names are made unique within an element (a repeated name gets a numeric suffix), because some chips repeat pin texts.
- **JSON save/load.** The element keys are the IDs.
- **Text format load.** The legacy text format is unchanged. Loading it assigns IDs deterministically in file order, so reloading the same file yields the same IDs.
- **Paste, duplicate, subcircuit expansion.** These create new elements, so they receive new IDs.

### 3.3. Connectivity report  {#C_AGA_03_03}

> **Criticality:** critical

The simulator hides wiring mistakes: it ties unconnected nodes to ground through a large resistance and assumes ground at the first voltage source. Wrong geometry therefore reads as 0 V, not as an error. The connectivity report makes these mistakes explicit, and every mutation returns its delta.

| Issue code (concept names) | Condition |
|---|---|
| `dangling_post` | A post joined to no other post (wire ends included). The agent can mark intended open pins (e.g. an unused chip output) as open, which suppresses the issue for that post |
| `post_on_wire_body` | A post lies on a wire's interior, not on its end, so it is not connected |
| `overlapping_elements` | Two elements share both defining points, or two wires overlap collinearly |
| `no_ground` | No ground element; the simulator's implicit ground is in use |
| `isolated_group` | A connected group of nets has no path to ground |
| `source_or_wire_loop` | A voltage-source/wire loop with no resistance, reported by the simulator's analysis — as a stop, or as a warning while its non-convergence recovery keeps running |
| `single_label` | A label text used only once (informational) |
| `bad_connection` | A post touches another element's body, from the analysis' existing bad-connection list |
| `unknown_net` (operation error, not a connectivity issue) | A probe or reading names a net that does not exist. The operation is rejected instead of returning 0 V |

Nets are named by their label when a labelled node is on them, `gnd` for ground, and otherwise by a generated name. Generated names are stable only until the next topology change; the skill tells agents to label nets they need to track.

### 3.4. Bounded runs, probes and measurement  {#C_AGA_03_04}

- **Bounded run.** A run advances a given span of simulated time.
  - The document's time-step settings apply; the UI speed slider and wall-clock pacing do not, so the same circuit and span produce the same result (from a reset; circuits with noise sources excepted, since they draw from the session's unseeded random generator).
  - A wall-clock budget bounds the run.
  - The run executes in slices that yield to the UI between slices, so a long run never freezes the window.
  - While a run is in progress, mutations of that document are rejected as "busy". Reads stay allowed.
- **Operating point.** "Run until settled" ends when every net voltage changes less than a tolerance over a window, bounded by a maximum span. It stands in for a DC operating point in a transient-only simulator.
- **Any document.** Runs, renders and edits work on background documents. They never switch the visible tab or disturb the active tab's view and controls, and the target document changes exactly as if it were active. Many load, undo, export and rendering paths today assume the active document; how they are made to serve a background one is settled by a prototype in the plan (SP_AGA_DEC_04).
- **Probes.** A probe records its quantity at every step of a run with simulated time stamps. A probe result carries:
  - statistics: min, max, mean, RMS, peak-to-peak, final value, frequency, duty cycle, rise time;
  - a decimated series bounded by a point count the caller chooses within a cap.
- **Instant readings.** Net and element readings at the current instant are available outside runs too.
- **Rendering.** A render operation returns the whole circuit as an image, either vector or raster, returned as the operation's own result (unlike today's hook-based export), covering the circuit bounds rather than the visible viewport.

### 3.5. Agent transactions and checkpoints  {#C_AGA_03_05}

- **Collection.** Agent mutations collect in the document's open transaction.
- **Checkpoint.** A checkpoint with a comment seals the transaction into one undo entry labelled with that comment. A checkpoint with nothing pending changes nothing and reports so.
- **Reading and undoing history.** The agent reads the labelled undo history, undoes and redoes entries, and restores to a named checkpoint (an undo of several entries).
- **Visibility to the user.** The comment appears in the undo/redo menu entries, so the user sees what Ctrl+Z will revert.
- **Auto-sealing.** An open transaction is sealed automatically, with a generated comment, before any of these:
  - a user edit of the same document, so user and agent changes never merge into one undo entry;
  - any save of the document;
  - an undo or redo;
  - an idle period with no agent mutation.

  Closing the document discards the open transaction with the document.
- **No stuck state.** Sealing never depends on the agent remembering to do it.

### 3.6. Catalogue, diagnostics and documents  {#C_AGA_03_06}

- **Catalogue.** It is generated at runtime from the element contracts: type names, pins, geometry rules, editable properties, units, defaults, slider hints (from the editable-parameter contract), and the legacy dump code. It cannot drift from the code. It is listed as a compact index, with per-type detail on request.
- **Diagnostics.** These include:
  - the solver's stop state, with the culprit element ID;
  - the solver's current warning;
  - the issues of the last import;
  - recent log entries, read through a cursor so the agent fetches only new lines.
- **Documents.** Documents can be listed, created, activated and closed.
  - Operations on a background document do not switch the visible tab.
  - Readings by net name use the document's own analysis, never the session-wide label registry (which holds the labels of whichever document was analysed last), so a label in one tab never answers for another.

### 3.7. Edge Cases  {#C_AGA_03_07}

| Case | Behaviour |
|------|-----------|
| Unknown element type, pin or property key | Rejected; the issue lists the valid names |
| Property value unparseable | Rejected with the expected format; never silently defaulted. Values the element itself clamps are applied and reported as adjusted |
| Zero-length element, position off the half-cell lattice | Rejected |
| Supplied ID already used, or not matching the pattern | Rejected; existing IDs are untouched |
| Delete an element shown in a scope view | The scope view is removed with it and reported |
| Mutation while a run is in progress on that document | Rejected as busy |
| Operation on a closed or unknown document handle | Rejected with the list of open handles |
| Solver stops during a run | The run ends early; the reply carries the stop issue with the culprit ID and the probe data so far |
| Wall-clock budget exhausted | The run ends; the reply states the simulated time reached and that the budget was the cause |
| User undoes past an agent checkpoint | Allowed; the agent sees the new state and history on its next read |
| Import of text-format circuit | Accepted; IDs are assigned in file order and geometry is reported in exact cells (any fraction for legacy coordinates) |

## 4. Integration Points  {#C_AGA_04}

### 4.1. Dependencies  {#C_AGA_04_01}

| Concept | Use | Change it requires |
|---------|-----|--------------------|
| [C_DOC](./document-model.concept.md) | Documents, document lifecycle, the per-document element ID counters | ID registry replaces the per-prefix counters that reset only on clear; document handles; circuit export, analysis and stop state for a given (not only the active) document; the free-run loop skips a document that an agent run owns |
| [C_UND](./commands-undo.concept.md) | Full-snapshot undo stack | Snapshots carry element IDs, open marks, the document's own view transform and an optional comment; agent-origin pushes are suppressed; auto-seal hook before user edits |
| [C_SIM](./simulator-engine.concept.md) / [C_NET](./netlist-graph.concept.md) | Node analysis, stop state, warnings, bad-connection list, stepping | Expose node membership and the culprit element; a step path without per-step repaint for runs |
| [C_EIC](./edit-info-contract.concept.md) | Universal parameter contract | Read-only use: labels, units and slider hints matched to property keys |
| [C_IOF](./io-framework.concept.md) | JSON schema, element factory, import reporting | Import reports its issues to the caller; incremental additions create elements through the factory one by one (no append-import mode) |
| [C_EPS](./elements-passives.concept.md) | Labelled nodes join nets by name | None; net names come from each document's own analysis |
| [C_SCP](./scope-visualization.concept.md) | On-screen scope views | Add/remove a view by element ID |
| [C_RND](./rendering-primitives.concept.md) | Circuit rendering | Offscreen rendering of a given document |
| [C_LOG](./session-logging.concept.md) | Log buffer | Cursor-based reads |
| [C_FBR](./browser-file-bridge.concept.md) | File open/save on desktop | A new path-based read/write adapter: today's desktop save is a browser download and open is a file picker, neither takes a path. Agent file access is limited to circuit files (SP_MCP_DEC_03) |
| [C_APC](./app-controller.concept.md) | Scripting-global bridge (clustered native boundary, RULE_ARCH_008) | The Agent API is exported through the same clustered boundary; element IDs come from the registry |

### 4.2. API Surface  {#C_AGA_04_02}

These are operation groups; their contracts belong to the specification.
- **Catalogue:** list types, describe a type.
- **Documents:** list, create, activate, close, open/save by path.
- **Build and edit:** import a whole circuit; add elements and wires; move; delete; set properties; add/remove scope view. Each call is atomic and returns an operation result with its connectivity delta.
- **Inspect:** get the circuit (elements with IDs, properties, post cells), the connectivity report, instant readings, and a render.
- **Simulate:** start/stop free-running, reset, set time-step settings, run for a span, run until settled.
- **Measure:** probes on a run, with statistics and decimated series.
- **Debug:** diagnostics, log since cursor.
- **History:** checkpoint with comment, list history, undo, redo, restore to checkpoint.

## 5. Design Decisions  {#C_AGA_DEC}

### DEC_01 — How does the agent specify element placement?  {#C_AGA_DEC_01}

> **Status:** resolved
> **Date:** 2026-10-01

**Question:** Should the server lay out circuits from a netlist, or should the agent supply coordinates, and in which unit, through which operations? (Interview DEC_02 + DEC_05.)

**Options considered:**
| Option | Consequence |
|--------|-------------|
| A — netlist-first, server places on the grid and joins nets with label stubs | Correct by construction; needs a placement mechanism; drawings look label-heavy |
| B — full auto-layout with wire routing | Best drawings; a large, risky routing engine |
| C — the agent writes coordinates | No layout engine; the agent owns the geometry and wiring mistakes become likely |
| C.1 — grid cells, whole-circuit import + incremental edits | Coordinates in cells, not pixels; both build modes; every edit returns the connectivity delta |
| C.2 — pixels, whole JSON import only | Least new code; token-heavy and error-prone |
| C.3 — grid cells, incremental edits only | One input mode; many calls for large circuits |

**Decision:** C with C.1 — the agent writes coordinates, in grid cells, through whole-circuit import and incremental edits.
**Rationale:** The developer prefers the agent to control the drawing. Cells and the mandatory connectivity report ([§3.3](#C_AGA_03_03)) contain the error rate that prior art saw with pixel coordinates.
**Rejected because:** A — the developer does not want server-side layout. B — a routing engine is out of proportion. C.2 — pixels invite off-grid posts. C.3 — large circuits would take dozens of calls.

### DEC_02 — How are element identities kept stable?  {#C_AGA_DEC_02}

> **Status:** resolved
> **Date:** 2026-10-01

**Question:** Element IDs change after undo and reload, and JSON export uses its own scheme. How are they made stable? (Interview DEC_03.)

**Options considered:**
| Option | Consequence |
|--------|-------------|
| A — document-scoped ID registry carried through undo, persisted as JSON keys, assigned deterministically on text load | Text format unchanged; stable within a session and across JSON save/load |
| B — add an ID field to the text format too | Stable across text reloads; breaks compatibility of the legacy format |
| C — no stable IDs; address by index/snapshot | No work; the agent cannot reliably edit "the same" element |

**Decision:** A — document-scoped ID registry.
**Rationale:** It gives agents stable handles without touching the legacy text format shared with upstream Falstad.
**Rejected because:** B — format compatibility. C — the edit loop cannot work.

### DEC_03 — Which documents can the agent address?  {#C_AGA_DEC_03}

> **Status:** resolved
> **Date:** 2026-10-01

**Question:** Should operations target only the active tab, or any tab by handle? (Interview DEC_04.)

**Options considered:**
| Option | Consequence |
|--------|-------------|
| A — any tab by document handle, defaulting to the active one | The agent can work in its own tab without disturbing the user; operations on a background tab must leave the active tab's view and controls untouched (SP_AGA_03_08) |
| B — active tab only | Simpler; agent and user compete for one circuit; a tab switch breaks the agent's context |

**Decision:** A — any tab by document handle.
**Rationale:** It isolates agent work from the user's open circuits.
**Rejected because:** B — tab switches by the user would redirect the agent's edits.

### DEC_04 — How do agent edits enter the undo history?  {#C_AGA_DEC_04}

> **Status:** resolved
> **Date:** 2026-10-01

**Question:** Should each call be one undo step, should agent transactions group edits, or should agent edits bypass undo? (Interview DEC_06.)

**Options considered:**
| Option | Consequence |
|--------|-------------|
| A — one mutating call = one undo step | No state; history fills with small steps |
| B — agent transactions grouped into one step | Readable history; needs an open-transaction state |
| C — agent edits bypass undo | No way to revert agent mistakes |

**Decision:** B, with the developer's addition — transactions sealed by **checkpoints carrying the agent's comment**, shown in the history. Auto-sealing ([§3.5](#C_AGA_03_05)) removes the "forgotten open transaction" risk.
**Rationale:** The user sees agent work as named, revertible steps.
**Rejected because:** A — the history is noisy and unnamed. C — agent mistakes cannot be reverted.

## Changelog

| Date | Change |
|------|--------|
| 2026-10-01 | Initial version from the spike and the concept interview |
| 2026-10-01 | Spec review round 1: content-lifetime ID rule with counter raising, unique pin names, grid size pinned for agent geometry, recovery-mode warnings, any-document routing, path-based file adapter, issue code list aligned with SP_AGA |
