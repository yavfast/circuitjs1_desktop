# Module Analysis: circuit-state (application-shell sub-unit)

> **Path:** `src/main/java/com/lushprojects/circuitjs1/client/` (12 root-level files)
> **Layer:** 3 (application shell; sits above `element/`, `io/`, `dialog/`, `ui/tabs/`)
> **Analyzed:** 2026-04-19
> **Files:** 12 source files, 0 test files
> **Required context:** `.dev_flow/onboard/analysis/domain-core__element-base.md`,
> `.dev_flow/onboard/analysis/io-framework.md`

## Purpose

The `circuit-state` sub-unit is the **per-document state container** of the
CircuitJS1 desktop shell. It aggregates three tightly-coupled concerns:

1. **Document model** — one `CircuitDocument` per open tab, owning a full
   simulator/editor/loader/undo/scope stack plus persisted UI state and a
   per-document element-id counter; `DocumentManager` manages the multi-tab
   lifecycle, session persistence, and active-document switching;
   `CircuitInfo` carries document-scoped flags and URL-query parameters
   (title, file path, rendering options, start-circuit).
2. **Netlist graph** — the `CircuitNode` / `CircuitNodeLink` / `NodeMapEntry`
   triple plus the `WireInfo` / `RowInfo` / `FindPathInfo` helpers — the
   data structures consumed by `CircuitSimulator.preStampCircuit` (which
   lives outside this 12-file set but is the sole author/reader of most of
   these records).
3. **Element creation** — `CircuitElmCreator` is the legacy-text-format
   element factory (counterpart to `io.json.CircuitElementFactory`).
   `CircuitUtils` collects two element-predicate helpers used by the
   editor.

The module sits at Layer 3: every file imports `element.CircuitElm` (or a
subclass), `CircuitDocument` is referenced from ~every element as its
"owner context" (see `domain-core__element-base.md` field table), and
`CircuitLoader` drives the `io/` framework described in `io-framework.md`.

## Confirmed split:
`CircuitDocument` is the **per-tab document model**, owning all per-tab
simulator/editor state; `CircuitInfo` is **not** an analysis snapshot — it is
the per-document flag-bag + file-path / URL-parameter record (fields:
`fileName`, `filePath`, `unsavedChanges`, `dcAnalysisFlag`, `euroRes`,
`hideMenu`, `startCircuit`, `startCircuitText`, `mouseModeReq`, color
overrides, …). The "analysis snapshot" semantics the brief suspected live
on `CircuitSimulator` (`nodeList`, `voltageSources`, `circuitRowInfo`),
not `CircuitInfo`.

---

## Per-file Key Entities

### `CircuitDocument` (per-tab document root)

- **File:** `src/main/java/com/lushprojects/circuitjs1/client/CircuitDocument.java:10`
- **Type:** `public class` (non-final; no subclasses in-tree).
- **Package-private constructor** (`CircuitDocument(BaseCirSim)` line 57):
  only `DocumentManager.createDocument()` may instantiate.
- **Owns (public final fields, lines 15-23):**
  - `cirSim: BaseCirSim` (line 13) — back-pointer to the shell.
  - `circuitInfo: CircuitInfo` (per-document flags/paths).
  - `simulator: CircuitSimulator` (MNA solver; external to this sub-unit).
  - `scopeManager: ScopeManager` (scopes).
  - `undoManager: UndoManager` (undo stack).
  - `adjustableManager: AdjustableManager` (sliders).
  - `circuitEditor: CircuitEditor` (selection, drag, mouse mode).
  - `circuitLoader: CircuitLoader` (sub-unit; see below).
  - `simulationLoop: SimulationLoop` (inner class; ~60 FPS `Timer`).
  - `logBuffer: LogBuffer` (inner class; 100-line ring buffer).
- **Per-document element IDs** (SP_AGA_03_02, PL_AGA Phase 2):
  `elementIdRegistry: ElementIdRegistry` (client root; letters-only
  per-prefix counters that never decrease within one content lifetime).
  - `nextElementId(String prefix)` returns `prefix + n`, n = counter + 1,
    skipping IDs present in the document.
  - `raiseIdCounter(id)` — counter = max(counter, number of `id`).
  - `resetElementIds()` — content replacement (`ImportLifecycle.resetCircuitState`
    unless an undo restore is running; `CirSim.clearCircuit`).
  - `settleElementIds()` — called by `ImportLifecycle.finalizeCircuitLoading`:
    restored undo IDs by index, then valid unique supplied IDs raise counters,
    then missing IDs are generated in element order; returns `ids_regenerated`
    warnings.
  - `begin/endElementIdRestore(ids)` — bracket of `UndoManager.loadUndoItem`;
    `getDumpedElementIds()` — the `elementIds` of an undo entry.
- **Simulation control:**
  - `isRunning`, `isActive`, `errorMessage`, `stopElm` (private lines 25-28).
  - `setSimRunning(boolean)` (line 106) — refuses to start when
    `errorMessage != null`; mirrors into `simulator.simRunning`.
  - `stop(String msg, CircuitElm)` (line 130) — sets error + stops loop.
  - `clearError()` (line 136).
  - `updateSimulationLoop()` (line 122) — starts/stops `SimulationLoop`
    based on `isRunning && isActive`.
- **Listener interfaces (lines 46-52):**
  - `SimulationStateListener.onSimulationStateChanged(running, errMsg)`
  - `SimulationUpdateListener.onSimulationUpdate()`
  - Backed by `stateListeners` / `updateListeners` lists (lines 54-55).
- **`SimulationLoop` inner class (lines 150-204):** single GWT `Timer`
  scheduled every 16 ms; `update()` performs: (a) `simulator.analyzeCircuit()`
  if `circuitInfo.dcAnalysisFlag`, (b) `simulator.preStampAndStampCircuit()`
  if `simulator.needsStamp` (tolerates incomplete stamps), (c)
  `simulator.runCircuit(false)`. Exceptions are caught, logged to
  `logBuffer`, and call `stop(...)`.
- **UI state persisted per-tab (lines 299-348):**
  - `dots`, `volts`, `power`, `showValues`, `smallGrid` (booleans).
  - `speedValue=117`, `currentValue=50`, `powerValue=50`.
  - `transform[6]: double[]` — view zoom/pan matrix (copy of
    `cirSim.renderer.transform`).
  - `saveUIState(menuManager, cirSim)` (line 303), `restoreUIState(...)` (line 318).
- **Accessor pass-throughs to cirSim:** `getRenderer()`, `getDisplaySettings()`,
  `getDialogManager()`, `getCirSim()` (lines 273-296).
- **`dispose()` (line 350):** stops the `SimulationLoop` (only leak guard).

### `DocumentManager` (multi-tab lifecycle)

- **File:** `src/main/java/com/lushprojects/circuitjs1/client/DocumentManager.java:15`
- **Owned by** `BaseCirSim` (instantiated in `BaseCirSim.java:32`).
- **State:**
  - `documents: List<CircuitDocument>` (line 25).
  - `activeDocument: CircuitDocument` (line 26).
  - `closedTabsHistory: Stack<String>` (line 27) — dump strings of closed
    tabs, for "Restore last closed tab".
  - `listeners: List<DocumentManagerListener>` (line 28).
  - `saveTimer: Timer` (line 29) — 1-second debounce for
    `saveSession()`.
- **Listener interface `DocumentManagerListener` (lines 17-22):**
  `onDocumentAdded`, `onDocumentRemoved`, `onActiveDocumentChanged`,
  `onDocumentTitleChanged`. Implemented by `ui/tabs/TabBarPanel`
  (cross-ref `.dev_flow/onboard/analysis/ui-tabs.md`, `TabBarPanel.java:19`).
- **Lifecycle API:**
  - `createDocument()` (line 48) — new doc, appends to list, fires
    `onDocumentAdded`, does **not** set active.
  - `closeDocument(doc)` (line 55) — pushes the document's current dump
    (via `cirSim.actionManager.dumpCircuit()`, temporarily switching
    active context if the doc is inactive) onto `closedTabsHistory`,
    calls `doc.dispose()`, removes, selects nearest neighbor or spawns a
    blank doc.
  - `restoreLastClosedTab()` (line 93) — pops history, creates new doc,
    loads via `circuitLoader.readCircuit`, seeds undo.
  - `setInitialDocument(doc)` (line 109) — **does not** fire listeners;
    used at startup to install the first doc before the tab bar exists.
  - `setActiveDocument(doc)` (line 114) — saves old doc's UI state,
    calls `cirSim.bindDocument(doc)`, restores new doc's UI state, fires
    `onActiveDocumentChanged`, schedules canvas focus via 1 ms Timer
    (line 148-155) so keyboard shortcuts work immediately.
- **Session persistence (LocalStorage):**
  - `saveSession()` (line 214) — stores a JSON array under
    `"circuitjs_tabs_session"`, each entry `{title, fileName?, filePath?,
    lastFileName?, data, active?}`. Debounced via `scheduleSave()` on
    any doc add/remove/active-change/title-change.
  - `restoreSession()` (line 266) — parses the stored JSON; reuses the
    initial blank doc if possible; invokes `setActiveDocument` +
    `doc.circuitLoader.readCircuit(data)` for each entry. Falls back
    silently on JSON parse failure.
- **Helpers:**
  - `getTabTitle(doc)` (line 166) — `fileName || "Untitled"` + `"*"` if
    `unsavedChanges`. Consumed by `TabBarPanel.getTabTitle()`.
  - `notifyTitleChanged(doc)` (line 177) — called by `CirSim.setUnsavedChanges`.

### `CircuitInfo` (per-document flag bag + URL params)

- **File:** `src/main/java/com/lushprojects/circuitjs1/client/CircuitInfo.java:3`
- **Base class:** `BaseCirSimDelegate` — gives access to `cirSim`,
  `circuitDocument`.
- **Not** an analysis snapshot. Three field groups:
  1. **File identity:** `filePath`, `fileName`, `lastFileName` (all
     `String`, package-private).
  2. **Document state flags (public booleans):** `unsavedChanges`,
     `savedFlag`, `dcAnalysisFlag`, `developerMode`,
     `showResistanceInVoltageSources` (set by `CircuitSimulator.preStampCircuit`
     line 596), `hideInfoBox`, `hideMenu`, `euroSetting`, `euroGates`,
     `printable`, `convention`, `euroRes`, `usRes`, `running`, `noEditing`,
     `mouseWheelEdit`.
  3. **URL/startup options:** `startCircuit`, `startLabel`,
     `startCircuitText`, `startCircuitLink`; color overrides
     (`positiveColor`, `negativeColor`, `neutralColor`, `selectColor`,
     `currentColor`); `mouseModeReq`.
- **Entry point:** `loadQueryParameters()` (line 71) — reads
  `QueryParameters` (`cct`, `ctz`, `startCircuit`, `startLabel`,
  `euroResistors`, `IECGates`, `usResistors`, `running`, `hideMenu`,
  `whiteBackground`, `conventionalCurrent`, `editable`, `mouseWheelEdit`,
  five color overrides, `mouseMode`, `hideInfoBox`) with
  `OptionsManager`-backed defaults. `euroSetting` is derived (line 111-116).

### `CircuitLoader` (drives io-framework)

- **File:** `src/main/java/com/lushprojects/circuitjs1/client/CircuitLoader.java:17`
- **Base class:** `BaseCirSimDelegate implements CircuitConst`.
- **Role:** Thin adapter that routes raw circuit-data strings through
  `CircuitFormatRegistry` into the active document (see
  `io-framework.md` §"Format registration mechanism").
- **API:**
  - `readCircuit(String data, int flags)` (line 34) — auto-detects format
    via `CircuitFormatRegistry.detectFormatOrDefault(data)`, creates an
    importer, delegates to `importer.importCircuit(data, getActiveDocument(), flags)`.
  - `readCircuit(String data, String formatId, int flags)` (line 60) —
    explicit-format variant; falls back to default on unknown id.
  - `readCircuit(String text)` (line 77) — flags = 0.
  - `readSetupFile(String str, String title)` (line 165) — fetches
    `circuits/<str>` via `RequestBuilder`, stores `pendingSetupTitle`,
    delegates to `loadFileFromURL`.
  - `loadFileFromURL(String url)` (line 178) — HTTP GET, then
    `readCircuit(text, CircuitConst.RC_KEEP_TITLE)` on success; applies
    `pendingSetupTitle` to `circuitInfo.fileName`.
- **Static menu plumbing:**
  - `loadSetupList(CirSim, boolean openDefault)` (line 83) — fetches
    `setuplist.txt` and calls `processSetupList`.
  - `processSetupList(CirSim, String, boolean)` (line 117) — builds the
    "Circuits" menu bar; supports `+` (submenu push), `-` (submenu pop),
    `#` (comment), `>` (default circuit). Each leaf line becomes a
    `MenuItem` dispatching `MyCommand("circuits", "setup <file> <title>")`.
- **Flags accepted** (all from `CircuitImporter`): `RC_RETAIN`,
  `RC_SUBCIRCUITS`, `RC_NO_CENTER`, `RC_KEEP_TITLE`. See
  `io-framework.md` for semantics.
- **Cross-ref:** the loader is invoked by `ActionManager.dumpCircuit*`
  (for round-trip tests), `UndoManager`, `DocumentManager.restoreSession`,
  `LoadFile`, `ImportFromDropbox`, `CustomCompositeElm`, and several
  dialogs (`ExportAsJsonDialog`, `ExportAsTextDialog`,
  `EditCompositeModelDialog`) — see the table in `io-framework.md`
  §"Used by".

### `CircuitElmCreator` (factory from dump-type → *Elm)

- **File:** `src/main/java/com/lushprojects/circuitjs1/client/CircuitElmCreator.java:5`
- **Type:** `public class` with two static entry points. Stateless.
- **`createCe(CircuitDocument doc, int tint, int x1, int y1, int x2, int y2, int f, StringTokenizer st)` (line 7):**
  - Giant `switch(tint)` mapping the legacy text-format dump-type code to
    `new XxxElm(doc, x1, y1, x2, y2, f, st)`. Used by
    `TextCircuitImporter.createStandardElement` (see `io-framework.md`).
  - Codes are single-char for legacy types (`'r'` Resistor, `'c'` Capacitor,
    `'s'` Switch, `'w'` Wire, `'x'` Text, …) and integer `≥ 150` for modern
    types (gate families 150-154, flip-flops 155-156, analog/logic
    chips 157-197, AM/FM/diac/triac 200-206, `LabeledNodeElm=207`,
    `CustomLogicElm=208`, `PolarCapacitor=209`, opamps/VCVS/VCCS 212-215,
    opto/composite/subcircuit 402-410, new mechanicals/motors/switches
    414-430). Returns `null` for unknown codes.
  - Total registered types: ~150, matching the JSON factory count
    documented in `io-framework.md`.
- **`constructElement(CircuitDocument doc, String name, int x1, int y1)` (line 280):**
  - Class-name → `(CircuitDocument, int, int)` constructor dispatch (the
    subset of elements that have the two-arg "create at (x1,y1)"
    constructor). Includes legacy aliases (`DecadeElm↔RingCounterElm`,
    `UserDefinedLogicElm↔CustomLogicElm`, `NDarlingtonElm↔DarlingtonElm`,
    `NMosfetElm↔MosfetElm`, etc.) to keep old saved shortcuts/subcircuits
    loading.
  - Special case: `"CustomCompositeElm:<modelname>"` → `new
    CustomCompositeElm(doc, x1, y1, name)` (lines 562-566).
  - Callers: `CompositeElm.loadComposite()` (see
    `domain-core__element-base.md` §CompositeElm); editor "new element"
    actions; subcircuit materialization.
- **`readDescription(CircuitElm ce, StringTokenizer st)` (line 256):**
  - Scans remaining tokens for a `#`-prefixed comment, extracts the
    tail of the original string via `st.getStartTokenIdx()` /
    `st.getOriginalString()`, and calls `ce.setDescription(...)`.
    Used uniformly by element constructors after parsing required
    parameters.

### `CircuitNode` / `CircuitNodeLink` (netlist graph primitives)

- **`CircuitNode`** `src/main/java/com/lushprojects/circuitjs1/client/CircuitNode.java:24`
  - Fields: `links: ArrayList<CircuitNodeLink>` (edges to elements
    touching this node) + `internal: boolean` (true for element-internal
    nodes, e.g. extra MNA nodes for tri-state logic, op-amps, inductor
    companion models).
  - Created by `CircuitSimulator.makeNodeList()` (lines 417-477) and
    `setGroundNode(subcircuit)` (lines 391, 404). Stored in
    `CircuitSimulator.nodeList: ArrayList<CircuitNode>` (the primary
    node table indexed by global node number; `nodeList.get(0)` is
    always ground).
- **`CircuitNodeLink`** `src/main/java/com/lushprojects/circuitjs1/client/CircuitNodeLink.java:24`
  - Fields: `num: int` (which post of the element — 0..postCount + internalNodeCount - 1);
    `elm: CircuitElm`.
  - One created per element-post during `makeNodeList()`.

### `NodeMapEntry` (wire-closure merge entry)

- **File:** `src/main/java/com/lushprojects/circuitjs1/client/NodeMapEntry.java:3`
  (package-private).
- **Fields:** `node: int` (the global node number assigned during
  `makeNodeList`; `-1` = not yet assigned). Two constructors: `()` → -1,
  `(int n)` → n.
- **Usage:** `CircuitSimulator.nodeMap: HashMap<Point, NodeMapEntry>`
  keyed on post `Point`. `calculateWireClosure()` (CircuitSimulator:190)
  groups wire-equivalent posts by sharing a **single** `NodeMapEntry`
  reference — when two wires meet, one entry is redirected to the other
  (lines 222-226); callers that later look up either endpoint get the
  same node number. This is the wire-merge optimization that keeps the
  MNA matrix small.

### `WireInfo` (wire-current-calc metadata)

- **File:** `src/main/java/com/lushprojects/circuitjs1/client/WireInfo.java:7`
  (package-private).
- **Fields:** `wire: CircuitElm`, `neighbors: List<CircuitElm>`,
  `post: int`.
- **Role:** `CircuitSimulator.calcWireInfo()` (CircuitSimulator:264)
  builds a `WireInfo` for every `isRemovableWire()` element. Since wires
  have been collapsed into one MNA node (identical voltage at both ends),
  their current cannot be read from `V/R`; instead we compute it once per
  frame from the currents of all **other** elements attached at the
  chosen `post`. `wire.hasWireInfo` tracks whether the dependency
  resolver has assigned `neighbors` yet — the resolver iterates up to
  `2 × wireInfoList.size()` times to handle dependent-wire ordering
  (lines 267-349). Non-convergence path uses `singularStabilizersActive`
  fallback.

### `RowInfo` (MNA matrix row metadata)

- **Confirmed:** matrix row/column metadata, **not** UI row.
- **File:** `src/main/java/com/lushprojects/circuitjs1/client/RowInfo.java:23`
  (package-private).
- **Constants:** `ROW_NORMAL = 0`, `ROW_CONST = 1`.
- **Fields:** `type: int`, `mapCol: int`, `mapRow: int`, `value: double`,
  `rsChanges: boolean` (right-side changes), `lsChanges: boolean` (left-side
  changes), `dropRow: boolean`.
- **Role:** `CircuitSimulator.circuitRowInfo: RowInfo[]` (allocated in
  `stampCircuit()` at matrix size `nodeList.size() - 1 + voltageSourceCount`,
  lines 659-671). The matrix simplifier (`stampCircuit` / the
  Gaussian-pre-elimination pass at lines 756-870) uses `RowInfo` to
  track: (a) rows whose value is constant and can be eliminated
  (`ROW_CONST` + `dropRow = true`, lines 812-822); (b) which rows' left
  or right sides are data-dependent on the current timestep (so matrix
  stamping can re-stamp only the minimum required); (c) how simplified
  rows/cols map back to original matrix coordinates (`mapRow`/`mapCol`).

### `FindPathInfo` (graph-search result + static validator)

- **File:** `src/main/java/com/lushprojects/circuitjs1/client/FindPathInfo.java:15`
- **Type constants:** `INDUCT=1`, `VOLTAGE=2`, `SHORT=3`, `CAP_V=4`.
- **Instance fields:** `simulator: CircuitSimulator`, `visited: boolean[]`
  (size = `simulator.nodeList.size()`), `dest: int`, `firstElm: CircuitElm`,
  `type: int`.
- **Role:** Depth-first search over the `CircuitNode` graph looking for a
  path from a source node back to `dest` through edges that satisfy
  the type constraint:
  - `INDUCT` — exclude `CurrentElm`; also filter parallel inductors by
    matching current (±1e-10) so DC-series-inductor groups share a
    loop.
  - `VOLTAGE` — only follow `isWireEquivalent()` or `VoltageElm` or
    `GroundElm` (detects zero-resistance voltage loops).
  - `SHORT` — only `isWireEquivalent()` (detects shorted capacitors).
  - `CAP_V` — `isWireEquivalent()` + `isIdealCapacitor()` + `VoltageElm`
    (detects capacitor-voltage loops that need stabilising series
    resistance).
  - Traversal honours `hasGroundConnection(j)` as implicit edges to
    node 0, and iterates `simulator.nodesWithGroundConnection` when
    visiting node 0 (lines 60-66).
- **`findPath(int n1)`** (line 39) + **`checkElm(int n1, CircuitElm)`**
  (line 70) — mutually recursive DFS; O(nodes × elements-per-node).
- **`public static boolean validateElement(CircuitSimulator, CircuitElm)`
  (line 135):** the entry point called by
  `CircuitSimulator.validateCircuit` (outside this 12-file set). Detects
  and remediates:
  - `InductorElm` with no current path → `ce.reset()` (clears current).
  - `CurrentElm` with no current path → `cur.setBroken(true)`.
  - `VCCSElm` with no output-current path → `broken=true`.
  - 2-post `VoltageElm` with wire/voltage loop → `simulator.stop(...)` or
    non-convergence warn + `singularStabilizersActive=true`.
  - `RailElm` / `LogicInputElm` with zero-resistance path to ground →
    same stop/warn.
  - `CapacitorElm`: short → `shorted()`; cap-voltage loop →
    `setSeriesResistance(0.1)` + return false to force re-stamp.

### `CircuitUtils` (static helpers)

- **File:** `src/main/java/com/lushprojects/circuitjs1/client/CircuitUtils.java:8`
- `canSplit(CircuitElm)` (line 10) — true iff the element is a
  horizontal or vertical `WireElm` (can be broken at a midpoint when the
  user drops another element on top of it).
- `sliderItemEnabled(CircuitElm)` (line 20) — false for `PotElm`;
  otherwise scans `elm.getEditInfo(i)` for any `ei.canCreateAdjustable()`.
  Used to gate the "Make Slider" menu item.

---

## Netlist construction flow (where the graph primitives fit)

Even though `CircuitSimulator` lives outside these 12 files, it is the
sole consumer and constructor of `CircuitNode` / `CircuitNodeLink` /
`NodeMapEntry` / `WireInfo` / `RowInfo`, so the flow belongs here for
onboard continuity.

```
CircuitSimulator.preStampCircuit(subcircuit)                    [CircuitSimulator.java:562]
├── 1. nodeList.clear()
│
├── 2. calculateWireClosure()                                   [:190]
│      • For each isRemovableWire() elm:
│        - wireInfoList.add(new WireInfo(ce))                   [WireInfo created]
│        - getPost(0) / getConnectedPost() looked up in nodeMap
│        - Create / merge NodeMapEntry so all wire-equiv posts
│          share one NodeMapEntry reference                     [NodeMapEntry created]
│      • Result: nodeMap<Point,NodeMapEntry> with merged groups
│      • No CircuitNode allocated yet.
│
├── 3. setGroundNode(subcircuit)                                [:356]
│      • Ground/rail detection; nodeList[0] reserved for ground
│      • All GroundElm posts get NodeMapEntry.node = 0.
│
├── 4. makeNodeList()                                           [:417]
│      • For each elm post:
│        - If NodeMapEntry is new or unassigned →
│            new CircuitNode, allocate node = nodeList.size(),
│            create CircuitNodeLink{num=j, elm=ce}, add to
│            CircuitNode.links, ce.setNode(j, n), nodeList.add()
│        - Else reuse existing node, add new CircuitNodeLink.
│      • For each internal node: same but cn.internal=true.
│      • Populates voltageSources[] with capacity = Σ getVoltageSourceCount().
│
├── 5. calcWireInfo()                                           [:264]
│      • For each WireInfo wi:
│        - Walk cn.links of wi.wire's node
│        - Partition neighbors by post 0/post 1 coordinates
│        - Pick side whose neighbors are all hasWireInfo-ready
│        - Store wi.neighbors + wi.post; mark wire.hasWireInfo
│      • Returns false if circular (stop or non-conv recover).
│
├── 6. nodeMap.clear()    (no longer needed — nodes fully indexed)
│
├── 7. Non-linear / voltage-source accounting                   [:580-605]
│      • Set circuitNonLinear = any ce.nonLinear()
│      • Assign VS indices: ce.setVoltageSource(j, vscount++)
│      • Side effect: circuitInfo.showResistanceInVoltageSources
│
├── 8. findUnconnectedNodes()                                   [:484]
│      • BFS closure from node 0 using hasGroundConnection + getConnection
│      • Populates unconnectedNodes[] and nodesWithGroundConnection[]
│
├── 9. validateCircuit()  →  FindPathInfo.validateElement(sim,ce)  [FindPathInfo:135]
│      • Runs DFS for INDUCT/VOLTAGE/SHORT/CAP_V per element type
│      • May stop(), warn(+stabilisers), or force re-stamp
│
└── 10. needsStamp = true

CircuitSimulator.stampCircuit()                                 [:658]
├── matrixSize = nodeList.size() - 1 + voltageSourceCount
├── circuitRowInfo = new RowInfo[matrixSize]  (all ROW_NORMAL)  [RowInfo created]
├── connectUnconnectedNodes() — 1e8 Ω to ground for each        [:555]
├── ce.stamp() for every element (MNA contributions)
└── Matrix simplification pass                                  [:756-870]
    • Constant columns flipped to ROW_CONST + dropRow=true
    • rsChanges / lsChanges tracked to skip re-stamping invariant rows
```

## Document lifecycle

```
BaseCirSim ctor                                                [BaseCirSim.java:32]
 └── documentManager.createDocument()
      └── new CircuitDocument(cirSim)                          [CircuitDocument.java:57]
           ├── circuitInfo, simulator, scopeManager,
           │   undoManager, adjustableManager, circuitEditor,
           │   circuitLoader, simulationLoop, logBuffer
           ├── initDefaultUIState()                            [line 231]
           └── updateSimulationLoop()  (no-op: not active/running)

documentManager.setInitialDocument(doc)  → cirSim.bindDocument(doc)

----- user opens new tab -----
documentManager.createDocument()                               [DocumentManager.java:48]
 └── notifyDocumentAdded → TabBarPanel.onDocumentAdded → TabWidget
documentManager.setActiveDocument(newDoc)                      [:114]
 ├── oldDoc.saveUIState(...)                                   [CircuitDocument.java:303]
 ├── cirSim.bindDocument(newDoc)
 ├── newDoc.restoreUIState(...)                                [:318]
 ├── notifyActiveDocumentChanged (tab bar toggles activeTab style)
 ├── cirSim.setUnsavedChanges(newDoc.circuitInfo.unsavedChanges)
 ├── cirSim.needAnalyze()
 └── Timer(1ms).run: canvas.setFocus(true)

----- loading a circuit into the active doc -----
circuitLoader.readCircuit(data, flags)                          [CircuitLoader.java:34]
 └── CircuitFormatRegistry.detectFormatOrDefault(data)
      ├── probe text canImport → probe json canImport
      └── fallback: text
 └── format.createImporter().importCircuit(data, activeDoc, flags)
      • Text path → CircuitElmCreator.createCe (legacy)
      • JSON path → io.json.CircuitElementFactory (modern)

----- user switches tab -----
(same as setActiveDocument path above)

----- user closes tab -----
documentManager.closeDocument(doc)                              [:55]
 ├── push cirSim.actionManager.dumpCircuit() → closedTabsHistory
 │     (temporarily switches active doc if needed)
 ├── doc.dispose() → simulationLoop.stop()                     [CircuitDocument.java:350]
 ├── documents.remove(doc)
 ├── notifyDocumentRemoved → TabBarPanel removes tab
 └── if closed == active: select nearest / create blank
 └── scheduleSave() (1s debounce)

----- user presses "Restore last closed tab" -----
documentManager.restoreLastClosedTab()                          [:93]
 ├── dump = closedTabsHistory.pop()
 ├── createDocument + setActiveDocument
 ├── newDoc.circuitLoader.readCircuit(dump)
 └── newDoc.undoManager.resetAndSeedFromCurrentCircuit()

----- session save/restore (LocalStorage) -----
scheduleSave() → (1s) → saveSession()                           [:214]
 • Serialises each doc's dump + file paths to "circuitjs_tabs_session"
restoreSession() on startup                                     [:266]
 • Parse JSON, reuse initial blank doc for first entry,
   createDocument + setActiveDocument + readCircuit for rest.
```

## Multi-document architecture

- **Ownership tree:**
  `BaseCirSim` ─owns─▶ `DocumentManager` ─owns─▶ `List<CircuitDocument>`
  ─each owns→ {`CircuitInfo`, `CircuitSimulator`, `ScopeManager`,
  `UndoManager`, `AdjustableManager`, `CircuitEditor`, `CircuitLoader`,
  `SimulationLoop`, `LogBuffer`, element-id counter map, UI-state block}.
- **Shared-singleton resources** (held on `cirSim`, not on doc):
  `renderer`, `displaySettings`, `menuManager`, `dialogManager`,
  `actionManager`, `controlsDialog`, `speedBar`/`currentBar`/`powerBar`,
  the canvas. `CircuitDocument.saveUIState` / `restoreUIState` is the
  mechanism that swaps this shared UI to match the active doc.
- **Active-document protocol:** everything that operates on the current
  circuit goes through `cirSim.getActiveDocument()` (e.g.
  `BaseCirSim.java:51`, `CircuitLoader.getActiveDocument()`). Elements
  hold a reference to **their** `CircuitDocument` via
  `CircuitElm.circuitDocument` (public field; see
  `domain-core__element-base.md`) — so an element stays bound to its
  document even when another tab is active.
- **Tab-bar binding:** `ui.tabs.TabBarPanel` registers itself as
  `DocumentManagerListener`; see `ui-tabs.md` §TabBarPanel. User clicks
  on tabs call `documentManager.setActiveDocument` / `closeDocument`;
  model changes call the listener methods to rebuild tab widgets.
- **Session persistence:** per-tab via
  `LocalStorage["circuitjs_tabs_session"]` (DocumentManager `saveSession`
  / `restoreSession`); debounced (1s) on every add/remove/title
  change/active change.
- **Undo scope:** per-document. `UndoManager` is instantiated once per
  `CircuitDocument`; cross-tab operations never cross undo stacks.
- **Simulation loop scope:** per-document; each tab has its own
  `SimulationLoop` GWT `Timer`. Only the **active** tab runs (gated by
  `isActive && isRunning`); inactive tabs are paused but retain solver
  state.

## Public contracts

- **Creation:** `new CircuitDocument(...)` is package-private;
  `DocumentManager.createDocument()` is the only legal entry.
- **Element creation (text path):**
  `CircuitElmCreator.createCe(doc, tint, x1, y1, x2, y2, flags, st)` must
  return a fully-constructed `CircuitElm` — constructor responsible for
  consuming **all** type-specific tokens and calling
  `CircuitElmCreator.readDescription(ce, st)` at the tail.
- **Element creation (by class-name):** `constructElement(doc, name, x, y)`
  supports only the subset of elements with a `(doc, int, int)`
  constructor. Callers (composite loader, editor's "new" action) must
  check for `null` and handle aliases.
- **Loader invariants:**
  - `readCircuit(null|"")` returns without side effects.
  - All `readCircuit` variants operate on `getActiveDocument()` — the
    caller must have already set the intended doc active.
  - `RC_RETAIN` preserves existing elements (paste/merge). Default
    behavior is full reset.
- **SimulationLoop invariants:** runs iff `isRunning && isActive`;
  `stop(msg, elm)` sets error and un-runs; `setSimRunning(true)` is a
  no-op while `errorMessage != null`.
- **DocumentManager listener contract:**
  - `setInitialDocument` does **not** fire `onDocumentAdded` /
    `onActiveDocumentChanged` (bootstrap path).
  - `closeDocument` on the last document always spawns a replacement
    blank document (invariant: `documents` is never empty after startup).
- **Netlist graph:** `CircuitNode.links` is only populated inside
  `CircuitSimulator.makeNodeList`; external readers (`FindPathInfo`)
  treat it as read-only. `CircuitSimulator.getCircuitNode(n)` is
  null-safe for `n >= nodeList.size()`.
- **FindPathInfo.validateElement** is the only public entry — the
  instance constructor and `findPath`/`checkElm` are package-private
  and called transitively.

## Integration points

### Depends on
- **Layer 0/1 (`client/*` root):** `BaseCirSim`, `BaseCirSimDelegate`,
  `CirSim`, `CircuitSimulator`, `CircuitRenderer`, `CircuitEditor`,
  `ScopeManager`, `UndoManager`, `AdjustableManager`, `ActionManager`,
  `MenuManager`, `DialogManager`, `DisplaySettings`, `OptionsManager`,
  `QueryParameters`, `StringTokenizer`, `CircuitConst`, `LabeledNodeElm`
  (via simulator), `Point`, `MyCommand`.
- **`element/`:** `CircuitElm`, `WireElm`, `PotElm`, `CapacitorElm`,
  `CurrentElm`, `GroundElm`, `InductorElm`, `LogicInputElm`, `RailElm`,
  `VCCSElm`, `VoltageElm`, plus every concrete `*Elm` in
  `CircuitElmCreator.createCe` / `constructElement` (~150 classes).
- **`io/`:** `CircuitFormat`, `CircuitFormatRegistry`, `CircuitImporter`
  (`CircuitLoader` only). Text path reaches back into `CircuitElmCreator`
  — this is the text-format's in-module factory.
- **`dialog/`:** `EditInfo` (in `CircuitUtils.sliderItemEnabled`).
- **`util/`:** `Locale` (setup list menu i18n).
- **GWT:** `Timer`, `RequestBuilder`/`Request`/`RequestCallback`,
  `MenuBar`/`MenuItem`, `Window`, `Storage`, `JSONObject`/`JSONArray`/
  `JSONParser`/`JSONString`/`JSONValue`, `GWT`.

### Used by
- **`element/`:** every concrete element holds `circuitDocument:
  CircuitDocument` (see `domain-core__element-base.md` fields table).
- **`ui/tabs/`:** `TabBarPanel` implements `DocumentManagerListener`;
  see `ui-tabs.md`.
- **`io/text/`:** `TextCircuitImporter.createStandardElement` calls
  `CircuitElmCreator.createCe`; see `io-framework.md`.
- **`io/json/`:** `JsonCircuitImporter` takes `document` references
  from `CircuitLoader.getActiveDocument()`.
- **Every `BaseCirSimDelegate` descendant** (`CircuitInfo`,
  `CircuitLoader`, `CircuitSimulator`, `CircuitEditor`, `UndoManager`,
  `ScopeManager`, `AdjustableManager`) is bound to a
  `CircuitDocument` at construction.

## Issues / Questions

1. **`CircuitDocument` constructor is package-private but the class is
   public.** Creation is gated only by convention; no factory interface.
   `DocumentManager.createDocument` is the sole intended path.
2. **`CircuitInfo` is a flag soup.** 20+ booleans + paths + colors +
   start-circuit parameters + query-parameter loader all in one class.
   Separating into `DocumentFileInfo` (filePath/fileName/unsavedChanges),
   `DocumentDisplayPrefs` (color strings + euro/us/convention flags), and
   `DocumentStartupOptions` (URL-param fields) would tighten the model.
   The brief's "analysis snapshot" hypothesis is wrong — there is no
   snapshot here.
3. **`CircuitElmCreator` is a dual factory** (legacy-int `createCe` vs.
   class-name `constructElement`) with partially overlapping type sets
   (`constructElement` is the smaller set — no gates like `AndGateElm`?
   actually it does register them; but doesn't cover every `createCe`
   type). Adding a new element still requires editing two files here
   **and** `io.json.CircuitElementFactory` — a known plugin-registration
   trade-off, also flagged in `io-framework.md` §Issues #8.
4. **`CircuitElmCreator.createCe` returns null for unknown types** with
   no logging; callers in `TextCircuitImporter` catch and warn, but
   swallowing here makes diagnosis harder.
5. **`DocumentManager.saveSession` switches active doc by raw field
   assignment + `bindDocument` to avoid triggering UI refresh** (lines
   244-250), bypassing `setActiveDocument` and its listeners. Fragile:
   any future logic that needs listener notification on temp-switches
   will silently skip this path. The comment on line 240-242
   acknowledges this.
6. **`restoreLastClosedTab` resets the undo stack instead of restoring
   it.** Re-opening a closed tab does not bring back undo history —
   users may expect otherwise.
7. **`CircuitInfo.showResistanceInVoltageSources` is written by the
   simulator** (`CircuitSimulator.java:596`) — a mutation from a
   solver-layer file into a display-prefs field. Consider moving to
   `DisplaySettings` or computing on demand.
8. **`CircuitLoader.processSetupList` directly mutates
   `cirSim.getActiveDocument().circuitInfo.startCircuit` / `.startLabel`
   inside a static method** (lines 149-159). Coupling the menu-builder
   to the active document at load time is confusing; the start-circuit
   state belongs on a global "startup options" singleton, not on every
   document's `CircuitInfo`.
9. **`FindPathInfo.validateElement` mutates elements as a side effect**
   (resets inductors, flags current sources broken, sets cap series
   resistance). The method signature (`boolean validate`) hides the
   mutation. Callers must understand it is both "validate **and**
   repair".
10. **`CircuitNode` / `CircuitNodeLink` have no encapsulation:** all
    fields public and mutated directly by `CircuitSimulator`. The graph
    is effectively free-form; mistakes would be hard to trace.
11. **`NodeMapEntry` is a mutable `int` wrapper** used as a reference
    token for "aliased nodes". Once `makeNodeList` runs, the pointer
    identity is discarded. Replacing with Union-Find would be clearer
    and cheaper than the O(n) merge walk at
    `CircuitSimulator.java:222-226`.
12. **`WireInfo.post` is `0` or `1` only** — silently assumes wires have
    exactly 2 posts. Any future multi-post wire-equivalent element
    would break.
13. **`RowInfo.mapCol` / `mapRow` are never initialised** in the ctor;
    they default to 0 and rely on the simplification code to set them
    before use. A sentinel (-1) would make bugs easier to catch.
14. **`CircuitUtils` is almost empty** (2 static methods). Either fold
    into `CircuitElm` / `CircuitEditor` or expand its role.
15. **`CircuitElmCreator.createCe` gap between codes 189→193, 197→200,
    201→203, 203→206, 216→350, 374→400** leaves room for new types but
    is undocumented; `io-framework.md` §Issue #9 makes the same point
    for JSON type IDs.

## Suggested concept boundaries

Three concepts are the natural cuts for downstream concept generation:

### 1. `document-model`  (per-tab state + multi-tab lifecycle)
- `CircuitDocument`, `DocumentManager`, `CircuitInfo`,
  `CircuitLoader`, `CircuitUtils`
- Rationale: all five collaborate around the per-document state story.
  `CircuitLoader` sits here (not with `io/`) because it is a
  `BaseCirSimDelegate` owned by `CircuitDocument`; the format registry
  itself is covered by the `io-framework` concept. `CircuitUtils`'s two
  helpers are editor-adjacent predicates tied to a per-document element.

### 2. `netlist-graph`  (solver-facing graph primitives)
- `CircuitNode`, `CircuitNodeLink`, `NodeMapEntry`, `WireInfo`,
  `RowInfo`, `FindPathInfo`
- Rationale: all six are data carriers + DFS validator consumed by
  `CircuitSimulator.preStampCircuit` / `stampCircuit`. They share one
  lifecycle (allocated/cleared per analysis pass) and one reader
  (simulator + `FindPathInfo`). Natural companion concept to the future
  `simulator-core` concept that documents `CircuitSimulator`
  (outside the 12-file scope).

### 3. `element-creator`  (text-dump → *Elm factory)
- `CircuitElmCreator`
- Rationale: stands alone as the legacy-text counterpart to
  `io.json.CircuitElementFactory`. The class-name dispatcher
  (`constructElement`) and the description-reader (`readDescription`)
  are bundled with it. Both documents (`io-framework.md` and the future
  `element-catalog` concept) need to reference it, so a thin dedicated
  concept makes the cross-references tractable.

The module split matches the brief's split (document-model /
netlist-graph / element-creator) and is acyclic at the concept level
even though `CircuitElmCreator` references element types that in turn
reference `CircuitDocument` (which lives in `document-model`).
