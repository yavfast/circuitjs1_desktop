# Document Model  {#C_DOC}

> **Code:** C_DOC
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-10-02
> **Author:** onboard
>
> **Depends on:** [C_SIM](./simulator-engine.concept.md), [C_ELB](./element-base.concept.md), [C_IOF](./io-framework.concept.md), [C_NET](./netlist-graph.concept.md)
> **Used by:** —
> **Spike:** —
> **Specification:** [SP_DOC](./document-model.sp.md)
> **Plan:** [document-model.plan.md](./document-model.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/layer3__circuit-state.md`.
>
> Per-tab state container and multi-tab lifecycle: `CircuitDocument`
> owns one simulator + editor + scopes + undo + adjustables + loader;
> `DocumentManager` orchestrates tabs with debounced LocalStorage session
> persistence + closed-tab history + per-tab UI-state save/restore;
> `CircuitInfo` carries document-scoped flags and URL params;
> `CircuitLoader` adapts raw circuit text to the `io-framework`;
> `CircuitElmCreator` is the legacy-text `dump-type → *Elm` factory plus
> a class-name constructor; `ExtListEntry` is the subcircuit external-pin
> DTO.

## 1. Philosophy  {#C_DOC_01}

### 1.1. Core Principle  {#C_DOC_01_01}

One open tab = one `CircuitDocument`. Every per-circuit concern —
simulation, editing, undo, scope traces, adjustable sliders, loading,
local UI state — lives on the document object. The shell's
`CirSim` / `BaseCirSim` holds only cross-document singletons
(renderer, menu manager, dialog manager, action manager, canvas).

### 1.2. Design Constraints  {#C_DOC_01_02}

- `new CircuitDocument(...)` is **package-private**; only `DocumentManager.createDocument()` may construct (besides `CircuitDocument.createScratch`, a never-added, never-bound scratch document with number 0 that the agent element catalogue measures against).
- Inactive documents keep solver state but have their `SimulationLoop` stopped. A document owned by an agent run (`setAgentBusy`) is not stepped by its own loop even while active and running; the run steps it in slices ([SP_AGA_04_02](./agent-api.sp.md#SP_AGA_04_02)).
- A document other than the visible tab is touched only inside a `DocumentScope` (scoped silent bind, [PL_AGA_DEC_01](./agent-api.plan.md#PL_AGA_DEC_01)) — never by `setActiveDocument` or a raw field swap.
- Elements hold a direct `circuitDocument` reference, so they stay
  bound to their tab even when another tab is active.
- LocalStorage session save is **debounced 1 s** to avoid thrashing
  under rapid edits; persistence key is `"circuitjs_tabs_session"`.
- `setInitialDocument` does not fire listener events (tab bar may not
  yet exist at bootstrap).
- `documents` list is never empty post-startup: closing the last tab
  spawns a blank replacement.

## 2. Domain Model  {#C_DOC_02}

### 2.1. Key Entities  {#C_DOC_02_01}

- **CircuitDocument** — the per-tab root. Owns `circuitInfo`, `simulator` (`CircuitSimulator`), `scopeManager`, `undoManager`, `adjustableManager`, `circuitEditor`, `circuitLoader`, `simulationLoop` (inner class; 16 ms `Timer`), `logBuffer` (inner; 100-line ring), per-document element-id counters (`elementIdRegistry`, an `ElementIdRegistry`), its identity (`documentNumber`, session-unique and never reused — the agent handle `d<n>`; `displayTitle`, the tab title while there is no file name), agent state (`busyOwner` while an agent run owns it, `agentOrigin` while an Agent API mutation runs, `openMarks` — PostRefs declared intentionally unconnected, `lastImportIssues` of the last agent import, `firedStopTrigger`), and UI-state fields (`dots`, `volts`, `power`, `showValues`, `smallGrid`, `speedValue=117`, `currentValue=50`, `powerValue=50`, `voltageRange=5`, `transform[6]`, hint `hintType=-1`/`hintItem1`/`hintItem2`).
- **DocumentScope** — stateless entry (client root) through which any operation touches a document that may not be the visible tab: `call(sim, target, op)` / `run(...)` save the visible tab's UI state, swap the bound document by field (`DocumentManager.swapActiveSilently` → `BaseCirSim.swapDocumentSilently`), apply the target's options/view/hint, run the operation, then swap back and re-derive the visible tab's session widgets. Used by the closed-tab dump, session save and the agent contracts ([SP_AGA_03_08](./agent-api.sp.md#SP_AGA_03_08)).
- **DocumentManager** — owned by `BaseCirSim`. State: `documents`,
  `activeDocument`, `closedTabsHistory: Stack<String>`, listeners,
  `saveTimer` (1 s debounce).
- **CircuitInfo** — `BaseCirSimDelegate` subclass. Three field groups:
  file identity (`filePath`, `fileName`, `lastFileName`), document
  state flags (modified state via `isModified()`, `dcAnalysisFlag`, `developerMode`,
  `showResistanceInVoltageSources`, `hideMenu`, `euroSetting`, …), and
  URL/startup options (`startCircuit`, `startLabel`, `startCircuitText`,
  `startCircuitLink`, color overrides, `mouseModeReq`).
- **CircuitLoader** — `BaseCirSimDelegate implements CircuitConst`.
  Adapter over `CircuitFormatRegistry.detectFormatOrDefault`.
- **CircuitUtils** — two static editor-predicate helpers
  (`canSplit`, `sliderItemEnabled`).
- **CircuitElmCreator** — stateless factory. Two dispatchers:
  `createCe(doc, tint, x1, y1, x2, y2, flags, st)` (legacy-int dump-type
  → ~150 `*Elm` classes) and `constructElement(doc, name, x1, y1)`
  (class-name → two-arg constructor subset). Includes legacy aliases
  (`DecadeElm↔RingCounterElm`, `NMosfetElm↔MosfetElm`, etc.).
  `readDescription(ce, st)` appends `#`-prefixed comments.
- **DocumentManagerListener** — `onDocumentAdded`, `onDocumentRemoved`,
  `onActiveDocumentChanged`, `onDocumentTitleChanged`. Implemented by
  `ui/tabs/TabBarPanel`.
- **SimulationStateListener / SimulationUpdateListener** —
  per-document fan-out for running-state and per-frame update events.
- **ExtListEntry** — DTO for subcircuit external pins
  (pin name + node index), produced when parsing composite models.

### 2.2. Data Flows  {#C_DOC_02_02}

```
User opens new tab
  └─ DocumentManager.createDocument()
       ├─ new CircuitDocument(cirSim)   (package-private ctor:
       │     documentNumber; blank-circuit time-step defaults
       │     maxTimeStep = timeStep = 5e-6, minTimeStep = 50e-12;
       │     initDefaultUIState())
       └─ notifyDocumentAdded()         → TabBarPanel adds widget

User clicks tab
  └─ DocumentManager.setActiveDocument(doc)
       ├─ oldDoc.saveUIState(menuManager, cirSim)
       ├─ cirSim.bindDocument(doc)
       ├─ newDoc.restoreUIState(menuManager, cirSim)
       ├─ cirSim.enableUndoRedo()       (Undo/Redo items follow the tab)
       ├─ notifyActiveDocumentChanged()
       └─ Timer(1 ms).run: canvas.setFocus(true)

User closes tab
  └─ DocumentManager.closeDocument(doc)
       ├─ doc.cancelAgentRun()          (an agent run ends as cancelled)
       ├─ push dumpDocument(doc) onto closedTabsHistory
       │     (DocumentScope for a background doc: no tab switch)
       ├─ doc.dispose()  (stops SimulationLoop, discards agent transaction)
       ├─ documents.remove; notifyDocumentRemoved
       │     → scheduleSave() (1 s debounce → saveSession)
       └─ if doc was active: select nearest OR spawn blank if last

Operation on a background document (dump, agent contract)
  └─ DocumentScope.call(sim, target, op)
       ├─ bound.saveUIState; swapActiveSilently(target)
       ├─ detach sliders dialog; target.applyOptionWidgets + applyViewState
       ├─ op()      (onanalyze/ontimestep skipped; console → target.logBuffer)
       ├─ setCircuitArea; target.scopeManager.setupScopes()
       └─ finally: target.saveUIState; swap back; bound options/view/area;
                   refreshSessionWidgets; re-attach sliders; MOSFET flags

Load circuit
  └─ CircuitLoader.readCircuit(data, flags)
       ├─ CircuitFormatRegistry.detectFormatOrDefault(data)
       ├─ format.createImporter()
       └─ importer.importCircuit(data, activeDoc, flags)
```

## 3. Mechanisms  {#C_DOC_03}

### 3.1. Core Algorithm  {#C_DOC_03_01}

**SimulationLoop** (inner of `CircuitDocument`, 16 ms GWT `Timer`). A tick does nothing while the document is busy (an agent run owns stepping; the running flag is kept and takes effect again after the run). Otherwise, while running: `simulator.analyzeCircuit()` if `circuitInfo.dcAnalysisFlag`; `simulator.preStampAndStampCircuit()` if `simulator.needsStamp`; `simulator.runCircuit(false)`; then the update listeners are notified. Exceptions → log to `logBuffer` and `stop(...)`. After every tick the one-shot actions queued with `runAfterNextFrame(action)` run — an agent run's next background slice waits for one free-run frame of the visible tab (PL_AGA_DEC_01 condition 4); `stop()` drops them, so callers keep a fallback timer. Started/stopped by `updateSimulationLoop()` based on `isRunning && isActive`.

**Agent run state.** `setAgentBusy(owner)` marks the document as owned by an agent run (`BusyOwner`), set and cleared by the run only; `isAgentBusy()` reads it. A user action on the document (edit, undo/redo, run/stop/reset, slider move, content replacement) and closing it call `cancelAgentRun()`, which ends the run as `cancelled` before the action proceeds; it does nothing on an idle document. A stop-trigger element calls `stopTriggerFired(elm)`: the running flag is cleared and the element recorded; a run takes it with `takeFiredStopTrigger()` and ends after the current timestep ([SP_AGA_04_02](./agent-api.sp.md#SP_AGA_04_02)).

**Session persistence.** `saveSession` serialises each document as `{title, fileName?, filePath?, lastFileName?, displayTitle?, data, active?}` under `localStorage["circuitjs_tabs_session"]`. `data` comes from `dumpDocument`, which dumps a non-active document inside a `DocumentScope` (silent field swap, PL_AGA_DEC_01) with its own options, transform and hint applied to the session widgets the exporters read; the visible tab, its loop, sliders and widgets are left alone. `restoreSession` on startup reuses the initial blank doc for the first entry and `createDocument + setActiveDocument + readCircuit` for the rest, then restores the file identity and `displayTitle`. A JSON failure is logged to the console and `restoreSession` returns false (start-up then loads its default circuit).

**Closed-tab history.** Push the dump onto a `Stack<String>` on close;
pop + `createDocument + setActiveDocument + readCircuit` on
`restoreLastClosedTab`. Undo stack is **reset** on restore (not
reconstructed) — trade-off noted in backing analysis. `discardDocument` — removal of a background document created for an agent operation that then failed — keeps no dump.

**Element ID counters.** Per-document `ElementIdRegistry` (letters-only
`prefix → int`, [SP_AGA_03_02](./agent-api.sp.md#SP_AGA_03_02));
`nextElementId(prefix)` returns `prefix + (counter + 1)`, skipping present
IDs. Content replacement calls `resetElementIds()`; every import ends with
`settleElementIds()`; undo/redo restores IDs without a reset.

**UI-state save/restore.** `saveUIState(menuManager, cirSim)` snapshots the dots/volts/power/showValues/smallGrid toggles, the speed/current/power bar values, the voltage range (`ColorSettings` holds one session-wide value), the renderer's 6-element transform and its hint (`hintType`, `hintItem1`, `hintItem2`) into document fields. `restoreUIState` applies them on tab activation: `applyOptionWidgets` (toggles, bars, voltage range); `ControlsDialog.syncTimeStepBar()`, which moves the time-step bar to the document's maximum step without firing the bar's command, so the step is kept exactly instead of being re-quantised ([SP_AGA_06_01](./agent-api.sp.md#SP_AGA_06_01) item 18); a fresh circuit area (it depends on the document's scope count); `applyViewState` (saved transform, or centring when none was saved, plus the hint); the editor grid; power-bar enablement; sliders. `DocumentScope` reuses `applyOptionWidgets` and `applyViewState` for its silent bind.

**Factory dispatch.** `CircuitElmCreator.createCe` is a giant switch
on `tint` (single char for legacy, int ≥150 for modern types).
`constructElement` routes by class name and handles
`"CustomCompositeElm:<modelname>"` specially. Adding a new element
requires editing both this file **and** `io.json.CircuitElementFactory`.

### 3.2. Edge Cases  {#C_DOC_03_02}

- **`readCircuit(null|"")`** → no-op.
- **Unknown `tint` in `createCe`** → returns `null` silently; caller
  (`TextCircuitImporter`) warns.
- **Closing last tab** → replacement blank doc spawned; documents list
  never empty post-startup.
- **`processSetupList` mutates `circuitInfo.startCircuit` on the active
  doc at menu-build time** — static method couples to the active
  document; flagged as a code smell in the backing analysis.
- **`CircuitInfo.showResistanceInVoltageSources`** is written from the simulator layer (`CircuitSimulator.preStampCircuit`) — bleed across layers.
- **Background access** — the closed-tab dump, session save and agent contracts bind a non-visible document only through `DocumentScope`, which skips listener fan-out, `setActive` and the renderer timer reset; any future listener-dependent side effect must be added to the explicit `setActiveDocument` path. While a non-visible document is bound, the user's `onanalyze`/`ontimestep` hooks are skipped ([SP_AGA_06_01](./agent-api.sp.md#SP_AGA_06_01) item 17) and `CirSim.console` lines go to the bound document's `logBuffer` (item 13).
- **Closing a background tab** never switches the visible tab (item 13); `discardDocument` refuses the active document.
- **New documents** start with the blank-circuit time-step defaults (item 16); a zero maximum step used to become the time-step bar's 1 ps position.

## 4. Integration Points  {#C_DOC_04}

### 4.1. Dependencies  {#C_DOC_04_01}

- [C_SIM](./simulator-engine.concept.md) — each document owns a
  `CircuitSimulator`.
- [C_ELB](./element-base.concept.md) — every element has a
  `circuitDocument` back-pointer.
- [C_IOF](./io-framework.concept.md) — `CircuitLoader` delegates to
  `CircuitFormatRegistry`; text path calls back into
  `CircuitElmCreator`.
- [C_NET](./netlist-graph.concept.md) — used by the owned simulator.

### 4.2. API Surface  {#C_DOC_04_02}

- `DocumentManager.createDocument`, `closeDocument`, `discardDocument` (background document only, no closed-tab dump), `setActiveDocument`, `setInitialDocument`, `restoreLastClosedTab`, `saveSession`, `restoreSession`, `getDocumentTitle`, `getTabTitle`, `notifyTitleChanged`, `addListener`; package-private `swapActiveSilently` (for `DocumentScope` only).
- `CircuitDocument.nextElementId`, `raiseIdCounter`, `resetElementIds`, `settleElementIds`, `setSimRunning`, `stop`, `clearError`, `getDocumentNumber`, `get/setDisplayTitle`, `setAgentBusy`, `isAgentBusy`, `cancelAgentRun`, `stopTriggerFired`, `takeFiredStopTrigger`, `analyzeNow`, `ensureAnalysed`, `addStateListener`, `addUpdateListener`, `saveUIState`, `restoreUIState`, `applyOptionWidgets`, `applyViewState`, `dispose`, `simulationLoop.runAfterNextFrame`, pass-through getters.
- `DocumentScope.call(sim, target, op)`, `DocumentScope.run(sim, target, op)`.
- `CircuitLoader.readCircuit` (four overloads; one takes an `ImportReport`), `readSetupFile`, `loadFileFromURL`, static `loadSetupList`, `processSetupList`.
- `CircuitElmCreator.createCe`, `constructElement`, `readDescription`.
- `CircuitUtils.canSplit`, `sliderItemEnabled`.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
| 2026-10-02 | PL_AGA Phase 10 propagate: busy documents not stepped by their loop, `runAfterNextFrame`, agent run state (`setAgentBusy`/`cancelAgentRun`/stop trigger); new owned fields (document number, display title, agent state, voltage range, hint); `DocumentScope` silent bind replaces the temporary active-doc switch in close/session save; ctor sets time-step defaults; restore uses `syncTimeStepBar` and `applyViewState`; `discardDocument`; hooks and console routing while a background doc is bound. |
