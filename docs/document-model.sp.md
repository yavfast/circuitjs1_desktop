# Document Model — Specification  {#SP_DOC}

> **Code:** SP_DOC
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_DOC](./document-model.concept.md)
> **Depends on specs:** [SP_SIM](./simulator-engine.sp.md), [SP_ELB](./element-base.sp.md), [SP_IOF](./io-framework.sp.md), [SP_NET](./netlist-graph.sp.md)
> **Used by specs:** —
> **Plan:** [document-model.plan.md](./document-model.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/layer3__circuit-state.md`.

## 01. Data Structures  {#SP_DOC_01}

> Implements: [C_DOC_02](./document-model.concept.md#C_DOC_02)

### 01_01. CircuitDocument  {#SP_DOC_01_01}

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| cirSim | BaseCirSim | yes | ctor-arg | back-pointer |
| circuitInfo | CircuitInfo | yes | new | per-doc flags / paths |
| simulator | CircuitSimulator | yes | new | MNA engine |
| scopeManager | ScopeManager | yes | new | scope traces |
| undoManager | UndoManager | yes | new | undo stack |
| adjustableManager | AdjustableManager | yes | new | sliders |
| circuitEditor | CircuitEditor | yes | new | selection / drag / mouse |
| circuitLoader | CircuitLoader | yes | new | format adapter |
| simulationLoop | SimulationLoop | yes | new | 16 ms Timer |
| logBuffer | LogBuffer | yes | new | 100-line ring |
| elementIdRegistry | ElementIdRegistry | yes | new | per-prefix element ID counters + pending undo-restore IDs (SP_AGA_03_02); reset on content replacement |
| isRunning, isActive | boolean | — | false | loop gate |
| errorMessage | String | — | null | stop reason |
| stopElm | CircuitElm | — | null | offending element |
| dots, volts, power, showValues, smallGrid | boolean | — | true/true/false/false/false | UI toggles |
| speedValue, currentValue, powerValue | int | — | 117, 50, 50 | scrollbar values |
| transform | double[6] | — | identity | renderer matrix |

Invariants:
- Constructor package-private; only `DocumentManager.createDocument` may call.
- `setSimRunning(true)` no-op while `errorMessage != null`.
- `SimulationLoop` runs iff `isRunning && isActive`.

### 01_02. DocumentManager  {#SP_DOC_01_02}

| Field | Type | Description |
|-------|------|-------------|
| documents | List<CircuitDocument> | never empty post-startup |
| activeDocument | CircuitDocument | current tab |
| closedTabsHistory | Stack<String> | dump strings; at most `MAX_CLOSED_TABS` (20), oldest dropped |
| listeners | List<DocumentManagerListener> | tab bar hooks |
| saveTimer | Timer | 1 s debounce |

### 01_03. CircuitInfo  {#SP_DOC_01_03}

File identity: `filePath`, `fileName`, `lastFileName` (package-private strings).
Modified state: private `modified`, read via `isModified()`, written via `setModified()` (UI code calls `CirSim.setUnsavedChanges`, which also refreshes title and tab marker). `DocumentManager.hasModifiedDocuments()` drives the unload prompt.
Flags (public booleans): `dcAnalysisFlag`,
`developerMode`, `showResistanceInVoltageSources`, `hideInfoBox`, `hideMenu`,
`euroSetting`, `euroGates`, `printable`, `convention`, `euroRes`, `usRes`,
`running`, `noEditing`, `mouseWheelEdit`.
Startup options: `startCircuit`, `startLabel`, `startCircuitText`,
`startCircuitLink`, `positiveColor`, `negativeColor`, `neutralColor`,
`selectColor`, `currentColor`, `mouseModeReq`.

### 01_04. ExtListEntry  {#SP_DOC_01_04}

DTO produced by subcircuit/composite parsing.
Fields: `name: String`, `node: int`, `pos: int`, `side: int`.

## 02. Contracts  {#SP_DOC_02}

### 02_01. DocumentManager.createDocument  {#SP_DOC_02_01}

Processing:
    doc = new CircuitDocument(cirSim)
    documents.add(doc)
    doc.initDefaultUIState()
    notifyDocumentAdded(doc)
    return doc

Does **not** call `setActiveDocument`.

### 02_02. DocumentManager.setActiveDocument  {#SP_DOC_02_02}

Processing:
    if activeDocument != null: activeDocument.saveUIState(mm, cirSim)
    cirSim.bindDocument(doc)
    activeDocument = doc
    doc.restoreUIState(mm, cirSim)
    notifyActiveDocumentChanged(doc)
    cirSim.setUnsavedChanges(doc.circuitInfo.isModified())
    cirSim.needAnalyze()
    Timer(1 ms).run { canvas.setFocus(true) }

### 02_03. DocumentManager.closeDocument  {#SP_DOC_02_03}

Processing:
    tempSwitch = (doc != activeDocument)
    if tempSwitch: activeDocument = doc; cirSim.bindDocument(doc)
    dump = cirSim.actionManager.dumpCircuit()
    closedTabsHistory.push(dump)
    trim closedTabsHistory to MAX_CLOSED_TABS (drop oldest)
    if tempSwitch: restore previous active via bindDocument
    doc.dispose()                     // stops SimulationLoop
    documents.remove(doc)
    notifyDocumentRemoved(doc)
    if doc was active:
        if documents.empty: createDocument + setActiveDocument
        else: setActiveDocument(nearest neighbor)
    scheduleSave()

### 02_04. saveSession / restoreSession  {#SP_DOC_02_04}

`saveSession`:
    arr = []
    for d in documents: arr.push({title, fileName?, filePath?, lastFileName?, data: dump, active: d==activeDocument})
    localStorage["circuitjs_tabs_session"] = JSON.stringify(arr)

Inactive docs require temporary active-swap (raw assignment +
`bindDocument`, bypassing listeners) to produce `dump`.

`restoreSession`: parse JSON; reuse initial blank doc for first entry;
`createDocument + setActiveDocument + circuitLoader.readCircuit(data)`
for rest. Parse failure → silent fallback.

### 02_05. CircuitLoader.readCircuit  {#SP_DOC_02_05}

Overloads:
- `readCircuit(data, flags)` — auto-detect via
  `CircuitFormatRegistry.detectFormatOrDefault(data)` → `importer.importCircuit(data, getActiveDocument(), flags)`.
- `readCircuit(data, formatId, flags)` — explicit format; unknown id → default.
- `readCircuit(text)` — flags = 0.

Flags: `RC_RETAIN`, `RC_SUBCIRCUITS`, `RC_NO_CENTER`, `RC_KEEP_TITLE`.
`null|""` input → no-op.

### 02_06. CircuitElmCreator  {#SP_DOC_02_06}

`createCe(doc, tint, x1, y1, x2, y2, flags, st)` — legacy-int dump-type
dispatch. Covers:
- Legacy char codes: `'r'` Resistor, `'c'` Capacitor, `'s'` Switch,
  `'w'` Wire, `'x'` Text, etc.
- Modern int codes: gate families 150–154, flip-flops 155–156,
  analog/logic chips 157–197, AM/FM/diac/triac 200–206,
  `LabeledNodeElm=207`, `CustomLogicElm=208`, `PolarCapacitor=209`,
  opamps/VCVS/VCCS 212–215, composite/subcircuit 402–410,
  mechanicals/motors/switches 414–430.
- Returns `null` for unknown codes.

`constructElement(doc, name, x1, y1)` — class-name dispatcher for the
two-arg constructor subset. Handles aliases
(`DecadeElm↔RingCounterElm`, `UserDefinedLogicElm↔CustomLogicElm`,
`NDarlingtonElm↔DarlingtonElm`, `NMosfetElm↔MosfetElm`). Special case:
`"CustomCompositeElm:<model>"`.

`readDescription(ce, st)` — extract `#`-prefixed trailing comment.

## 03. Validation Rules  {#SP_DOC_03}

- Constructor visibility: `CircuitDocument` ctor package-private.
- `RC_RETAIN` preserves existing elements; default resets.
- All `readCircuit` paths operate on `getActiveDocument()`.
- `closeDocument` must never leave `documents` empty.

## 04. State Transitions  {#SP_DOC_04}

CircuitDocument lifecycle:

    [created] --initDefaultUIState--> [ready]
    [ready] --setSimRunning(true)--> [running]
    [running] --stop(msg,ce)--> [error]
    [error] --clearError--> [ready]
    [ready|running|error] --dispose--> [disposed]

Tab selection: `[inactive] --setActiveDocument--> [active]`.

## 05. Verification Criteria  {#SP_DOC_05}

### 05_01. Functional Expectations  {#SP_DOC_05_01}

| Contract | Scenario | Expected |
|----------|----------|----------|
| createDocument | new tab | fires onDocumentAdded, not onActiveDocumentChanged |
| setActiveDocument | click tab | UI state swaps, canvas refocuses |
| closeDocument (last) | close only tab | blank doc appears |
| restoreLastClosedTab | undo-close | prior circuit restored, undo stack empty |
| saveSession | after edit | localStorage updated after 1 s |
| readCircuit(text) | valid dump | elements populated |

### 05_02. Invariant Checks  {#SP_DOC_05_02}

| Invariant | Verification |
|-----------|-------------|
| documents never empty | after every closeDocument, list size ≥ 1 |
| only DocMgr constructs | reflection check on CircuitDocument constructor |
| inactive tabs paused | inactive doc's SimulationLoop.isRunning() == false |
| 1 s debounce | multiple rapid edits produce one saveSession |

### 05_03. Integration Scenarios  {#SP_DOC_05_03}

| Scenario | Steps | Expected |
|----------|-------|----------|
| Session round-trip | open 3 tabs, reload page | 3 tabs restored, active preserved |
| Text load | paste dump | createCe factories fire, elements appear |
| JSON load | load .json | JsonCircuitImporter path, no createCe |

### 05_04. Edge Cases  {#SP_DOC_05_04}

| Case | Expected |
|------|----------|
| Unknown tint | createCe returns null; caller warns |
| Corrupt localStorage JSON | silent fallback to single blank doc |
| Close inactive tab | active doc unchanged; tab bar repaints |
| restoreLastClosedTab when history empty | no-op |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
| 2026-09-30 | `unsavedChanges`/`savedFlag` merged into `CircuitInfo.modified`; undo depth cap 150; closed-tab history cap 20. |
