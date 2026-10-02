# Document Model — Specification  {#SP_DOC}

> **Code:** SP_DOC
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-10-02
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
| documentNumber | int | yes | `cirSim.allocateDocumentNumber()` | session-unique, never reused; agent handle `d<n>` (SP_AGA_01_02); 0 for the scratch document |
| displayTitle | String | — | null | tab title while the document has no file name (null → "Untitled") |
| busyOwner | BusyOwner | — | null | agent run owning the document ([SP_AGA_04_02](./agent-api.sp.md#SP_AGA_04_02)); non-null = busy |
| agentOrigin | boolean | — | false | true while an Agent API mutation runs; editor undo pushes suppressed (SP_AGA_04_01) |
| openMarks | Set<String> | — | empty | PostRefs declared intentionally unconnected (SP_AGA_01_12); in memory, captured in undo entries, cleared on content replacement |
| lastImportIssues | JSONArray | — | null | Issues of the last agent import (SP_AGA_01_11) |
| firedStopTrigger | CircuitElm | — | null | stop-trigger element fired and not yet taken by a run |
| isRunning, isActive | boolean | — | false | loop gate |
| errorMessage | String | — | null | stop reason |
| stopElm | CircuitElm | — | null | offending element |
| dots, volts, power, showValues, smallGrid | boolean | — | true/true/false/true/false | UI toggles (set by `initDefaultUIState`) |
| speedValue, currentValue, powerValue | int | — | 117, 50, 50 | scrollbar values |
| voltageRange | double | — | 5 | voltage colour range (session `ColorSettings` holds one value) |
| transform | double[6] | — | zeros (unset → centre on first activation) | renderer matrix |
| hintType, hintItem1, hintItem2 | int | — | -1, 0, 0 | renderer hint (the "h" line) |

Invariants:
- Constructor package-private; only `DocumentManager.createDocument` may call (plus `createScratch`: number 0, never added, bound or shown).
- `setSimRunning(true)` no-op while `errorMessage != null`.
- `SimulationLoop` timer is scheduled iff `isRunning && isActive`; a tick steps the circuit only while `busyOwner == null`.
- `busyOwner` is set and cleared by the agent run only (`setAgentBusy`).

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
        // ctor: documentNumber = cirSim.allocateDocumentNumber()
        //       create circuitInfo, simulator, managers, simulationLoop, logBuffer
        //       simulator.maxTimeStep = simulator.timeStep = 5e-6
        //       simulator.minTimeStep = 50e-12      // blank-circuit defaults (SP_AGA_06_01 item 16)
        //       initDefaultUIState(); updateSimulationLoop()
    documents.add(doc)
    notifyDocumentAdded(doc)          // → scheduleSave()
    return doc

Does **not** call `setActiveDocument`.

### 02_02. DocumentManager.setActiveDocument  {#SP_DOC_02_02}

Processing:
    if doc == activeDocument: return
    if doc not in documents: documents.add(doc); notifyDocumentAdded(doc)
    if activeDocument != null: activeDocument.saveUIState(mm, cirSim)
    old = activeDocument; activeDocument = doc
    cirSim.bindDocument(doc)          // old.setActive(false), doc.setActive(true), update listener, renderer timers
    doc.restoreUIState(mm, cirSim)
        // applyOptionWidgets (toggles, bars, voltage range)
        // controlsDialog.syncTimeStepBar()   // bar moved without its command: maxTimeStep kept exactly (SP_AGA_06_01 item 18)
        // renderer.setCircuitArea(); applyViewState(cirSim, centreIfUnset = true)   // transform or centre, hint
        // circuitEditor.setGrid(); cirSim.setPowerBarEnable(); adjustableManager.updateSliders()
    if menu built: cirSim.enableUndoRedo()   // Undo/Redo state and labels follow the tab
    notifyActiveDocumentChanged(old, doc)
    cirSim.setUnsavedChanges(doc.circuitInfo.isModified())
    cirSim.needAnalyze()
    Timer(1 ms).run { canvas.setFocus(true) }

### 02_03. DocumentManager.closeDocument  {#SP_DOC_02_03}

Processing:
    if doc == null or doc not in documents: return
    doc.cancelAgentRun()              // an agent run of doc ends as cancelled first
    dump = dumpDocument(doc)          // DocumentScope.call(sim, doc, actionManager::dumpCircuit); direct when doc is active
    closedTabsHistory.push(dump)
    trim closedTabsHistory to MAX_CLOSED_TABS (drop oldest)
    doc.dispose()                     // stops SimulationLoop, discards the open agent transaction
    documents.remove(doc)
    notifyDocumentRemoved(doc)        // → scheduleSave()
    if doc was active:
        if documents.empty: createDocument + setActiveDocument
        else: setActiveDocument(nearest neighbor)

Closing a background document never changes the visible tab.

### 02_04. saveSession / restoreSession  {#SP_DOC_02_04}

`saveSession`:
    arr = []
    for d in documents: arr.push({title, fileName?, filePath?, lastFileName?, displayTitle?, data: dumpDocument(d), active: d==activeDocument})
    localStorage["circuitjs_tabs_session"] = JSON.stringify(arr)

`dumpDocument(d)` dumps an inactive document inside `DocumentScope.call` (silent field swap, [PL_AGA_DEC_01](./agent-api.plan.md#PL_AGA_DEC_01)) with its own options, transform and hint applied to the session widgets; no listener fires and the visible tab's loop keeps running.

`restoreSession`: parse JSON; reuse initial blank doc for first entry; `createDocument + setActiveDocument + circuitLoader.readCircuit(data)` for rest; then restore `fileName`, `filePath`, `lastFileName`, `displayTitle` and `notifyTitleChanged`. Parse failure → console line, returns false (start-up loads its default circuit).

### 02_05. CircuitLoader.readCircuit  {#SP_DOC_02_05}

Overloads:
- `readCircuit(data, flags)` — auto-detect via
  `CircuitFormatRegistry.detectFormatOrDefault(data)` → `importer.importCircuit(data, getActiveDocument(), flags)`.
- `readCircuit(data, formatId, flags)` — explicit format; unknown id → default.
- `readCircuit(data, formatId, flags, report)` — `formatId` null = auto-detect; every skipped, failed or adjusted item goes to `report` (SP_AGA_03_04); null report = the other overloads.
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

### 02_07. DocumentManager.discardDocument  {#SP_DOC_02_07}

Removes a background document created for an agent operation that then failed (a rejected `openFile` into a new document, SP_AGA_02_14).

Processing:
    if doc == null or doc == activeDocument or doc not in documents: return
    doc.cancelAgentRun()
    doc.dispose()
    documents.remove(doc)
    notifyDocumentRemoved(doc)        // no closed-tab dump

### 02_08. DocumentScope.call / run  {#SP_DOC_02_08}

Scoped silent bind of [SP_AGA_03_08](./agent-api.sp.md#SP_AGA_03_08) (mechanism PL_AGA_DEC_01); `run` is the `void` form.

Processing:
    FUNCTION call(sim, target, op):
        bound = sim.getActiveDocument()
        if target == bound: return op()
        if target not open: throw IllegalArgumentException
        remember sliders-detached flag, Save-item state, renderer.circuitArea, MosfetElm global flags
        bound.saveUIState(mm, sim)
        if sim.visibleWhileBound == null: sim.visibleWhileBound = bound   // outermost scope
        documentManager.swapActiveSilently(target)     // fields only: no setActive, no listeners
        try:
            sliders.setDetached(true)
            target.applyOptionWidgets(mm, sim); target.applyViewState(sim, true)
            result = op()                  // user onanalyze/ontimestep skipped; console → target.logBuffer
            renderer.setCircuitArea(); target.scopeManager.setupScopes()
            return result
        finally:
            target.saveUIState(mm, sim)
            documentManager.swapActiveSilently(bound); restore visibleWhileBound, MOSFET flags
            renderer.circuitArea = saved; bound.applyOptionWidgets; bound.applyViewState(sim, false)
            sim.refreshSessionWidgets(saveAllowed)   // time-step bar, power bar, Undo/Redo, edit items, Save, title
            sliders.setDetached(saved flag)

Nested scopes save and restore what was bound when they were entered. An exception from `op` propagates after the bind is undone.

## 03. Validation Rules  {#SP_DOC_03}

- Constructor visibility: `CircuitDocument` ctor package-private.
- `RC_RETAIN` preserves existing elements; default resets.
- All `readCircuit` paths operate on `getActiveDocument()` (the bound document; inside a `DocumentScope` the target).
- `closeDocument` must never leave `documents` empty.
- A non-visible document is bound only through `DocumentScope`; `swapActiveSilently` has no other caller.

## 04. State Transitions  {#SP_DOC_04}

CircuitDocument lifecycle:

    [created] --ctor (time-step defaults, initDefaultUIState)--> [ready]
    [ready] --setSimRunning(true)--> [running]
    [running] --stop(msg,ce)--> [error]
    [running] --stopTriggerFired(elm)--> [ready]      // firedStopTrigger = elm
    [error] --clearError--> [ready]
    [ready|running] --setAgentBusy(run)--> [busy]     // loop ticks skip; the run steps the doc in slices
    [busy] --run ends: setAgentBusy(null)--> [ready|running]   // running flag kept
    [busy] --cancelAgentRun (user action, close)--> run ends as cancelled --> [ready|running]
    [ready|running|error] --dispose--> [disposed]

A run takes `firedStopTrigger` with `takeFiredStopTrigger()` (at its start, to drop a free-running trigger, and after every timestep) and ends after the current timestep when one fired.

Tab selection: `[inactive] --setActiveDocument--> [active]`; `[inactive] --DocumentScope.call--> [bound, not visible] --scope exit--> [inactive]`.

## 05. Verification Criteria  {#SP_DOC_05}

### 05_01. Functional Expectations  {#SP_DOC_05_01}

| Contract | Scenario | Expected |
|----------|----------|----------|
| createDocument | new tab | fires onDocumentAdded, not onActiveDocumentChanged; maxTimeStep = timeStep = 5e-6 |
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
| inactive tabs paused | inactive doc's `simulationLoop.isScheduled()` == false |
| busy doc not free-run | a tick of a document with `isAgentBusy()` advances no time |
| tab activation keeps step | after `setActiveDocument`, `simulator.maxTimeStep` equals the value saved with the document |
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
| Corrupt localStorage JSON | console line "Failed to restore session…"; `restoreSession` returns false and start-up loads its default circuit |
| Close inactive tab | visible tab never switches (dump via `DocumentScope`); tab bar repaints |
| discardDocument(active) | no-op |
| restoreLastClosedTab when history empty | no-op |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
| 2026-09-30 | `unsavedChanges`/`savedFlag` merged into `CircuitInfo.modified`; undo depth cap 150; closed-tab history cap 20. |
| 2026-10-02 | PL_AGA Phase 10 propagate: §01_01 new fields (document number, display title, agent state, voltage range, hint), corrected toggle defaults, busy invariant; §02_01 ctor time-step defaults; §02_02 restoreUIState via `syncTimeStepBar`/`applyViewState` + `enableUndoRedo`; §02_03 `cancelAgentRun` + `DocumentScope` dump replaces the temporary switch; §02_04 `displayTitle`; §02_05 report overload; new §02_07 `discardDocument`, §02_08 `DocumentScope`; §04 busy and stop-trigger transitions; §05 checks. |
