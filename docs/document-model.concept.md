# Document Model  {#C_DOC}

> **Code:** C_DOC
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
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

- `new CircuitDocument(...)` is **package-private**; only
  `DocumentManager.createDocument()` may construct.
- Inactive documents keep solver state but have their
  `SimulationLoop` stopped.
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

- **CircuitDocument** — the per-tab root. Owns `circuitInfo`,
  `simulator` (`CircuitSimulator`), `scopeManager`, `undoManager`,
  `adjustableManager`, `circuitEditor`, `circuitLoader`,
  `simulationLoop` (inner class; 16 ms `Timer`), `logBuffer` (inner;
  100-line ring), per-document element-id counters
  (`elementTypeCounters`), and UI-state fields (`dots`, `volts`,
  `power`, `showValues`, `smallGrid`, `speedValue=117`,
  `currentValue=50`, `powerValue=50`, `transform[6]`).
- **DocumentManager** — owned by `BaseCirSim`. State: `documents`,
  `activeDocument`, `closedTabsHistory: Stack<String>`, listeners,
  `saveTimer` (1 s debounce).
- **CircuitInfo** — `BaseCirSimDelegate` subclass. Three field groups:
  file identity (`filePath`, `fileName`, `lastFileName`), document
  state flags (`unsavedChanges`, `dcAnalysisFlag`, `developerMode`,
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
       ├─ new CircuitDocument(cirSim)   (package-private ctor)
       ├─ initDefaultUIState()
       └─ notifyDocumentAdded()         → TabBarPanel adds widget

User clicks tab
  └─ DocumentManager.setActiveDocument(doc)
       ├─ oldDoc.saveUIState(menuManager, cirSim)
       ├─ cirSim.bindDocument(doc)
       ├─ newDoc.restoreUIState(menuManager, cirSim)
       ├─ notifyActiveDocumentChanged()
       └─ Timer(1 ms).run: canvas.setFocus(true)

User closes tab
  └─ DocumentManager.closeDocument(doc)
       ├─ push dump onto closedTabsHistory
       ├─ doc.dispose()  (stops SimulationLoop)
       ├─ documents.remove; notifyDocumentRemoved
       ├─ select nearest OR spawn blank if last
       └─ scheduleSave()  (1 s debounce → saveSession)

Load circuit
  └─ CircuitLoader.readCircuit(data, flags)
       ├─ CircuitFormatRegistry.detectFormatOrDefault(data)
       ├─ format.createImporter()
       └─ importer.importCircuit(data, activeDoc, flags)
```

## 3. Mechanisms  {#C_DOC_03}

### 3.1. Core Algorithm  {#C_DOC_03_01}

**SimulationLoop** (inner of `CircuitDocument`, 16 ms GWT `Timer`). Each
tick: `simulator.analyzeCircuit()` if `circuitInfo.dcAnalysisFlag`;
`simulator.preStampAndStampCircuit()` if `simulator.needsStamp`;
`simulator.runCircuit(false)`. Exceptions → log to `logBuffer` and
`stop(...)`. Started/stopped by `updateSimulationLoop()` based on
`isRunning && isActive`.

**Session persistence.** `saveSession` serialises each document as
`{title, fileName?, filePath?, lastFileName?, data, active?}` under
`localStorage["circuitjs_tabs_session"]`. For inactive docs that must
be dumped, the manager *temporarily* swaps active doc via raw field
assignment + `bindDocument` (not `setActiveDocument`) to skip listener
noise. `restoreSession` on startup reuses the initial blank doc for
the first entry and `createDocument + setActiveDocument + readCircuit`
for the rest. JSON failures are silently swallowed.

**Closed-tab history.** Push the dump onto a `Stack<String>` on close;
pop + `createDocument + setActiveDocument + readCircuit` on
`restoreLastClosedTab`. Undo stack is **reset** on restore (not
reconstructed) — trade-off noted in backing analysis.

**Element ID counters.** Per-document map `elementTypeCounters`
(`prefix → int`); `nextElementId(prefix)` returns `prefix + (++counter)`.
Importers call `resetElementIdCounters()` before reloading.

**UI-state save/restore.** `saveUIState(menuManager, cirSim)` snapshots
dots/volts/power toggles, scroll-bar values, and the renderer's 6-element
transform matrix into document fields. `restoreUIState` applies them
back into the shared UI on tab activation.

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
- **`CircuitInfo.showResistanceInVoltageSources`** is written from the
  simulator layer (`CircuitSimulator.preStampCircuit` L596) — bleed
  across layers.
- **Temp active-doc switch in `saveSession`** skips listener fan-out;
  any future listener-dependent side effect must be added to the
  explicit `setActiveDocument` path.

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

- `DocumentManager.createDocument`, `closeDocument`,
  `setActiveDocument`, `setInitialDocument`, `restoreLastClosedTab`,
  `saveSession`, `restoreSession`, `getTabTitle`, `notifyTitleChanged`,
  `addListener`.
- `CircuitDocument.nextElementId`, `resetElementIdCounters`,
  `setSimRunning`, `updateSimulationLoop`, `stop`, `clearError`,
  `addStateListener`, `addUpdateListener`, `saveUIState`,
  `restoreUIState`, `dispose`, pass-through getters.
- `CircuitLoader.readCircuit` (three overloads), `readSetupFile`,
  `loadFileFromURL`, static `loadSetupList`, `processSetupList`.
- `CircuitElmCreator.createCe`, `constructElement`, `readDescription`.
- `CircuitUtils.canSplit`, `sliderItemEnabled`.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
