# Implementation Plan: Document Model  {#PL_DOC}

> **Code:** PL_DOC
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_DOC](./document-model.concept.md)
> **Specification:** [SP_DOC](./document-model.sp.md)
> **Depends on plans:** [PL_SIM](./simulator-engine.plan.md), [PL_ELB](./element-base.plan.md), [PL_IOF](./io-framework.plan.md), [PL_NET](./netlist-graph.plan.md)
> **Used by plans:** —
>
> Retrospective plan captured from existing code during onboarding.

## Goal

Document the multi-tab document-model as shipped: `CircuitDocument`,
`DocumentManager`, `CircuitInfo`, `CircuitLoader`, `CircuitElmCreator`,
`CircuitUtils`, `ExtListEntry`.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Per-tab ownership | Full stack per doc | tabs fully isolated |
| Session storage | LocalStorage, 1 s debounce | survives reload, cheap |
| Closed-tab history | Stack of dumps | simple reopen |
| Element factory | Dual: legacy int + class name | keep old saves + programmatic ops |
| UI-state swap | Save/restore on activate | one renderer + canvas shared across tabs |

## Progress

- [x] Phase 1 — CircuitDocument core + SimulationLoop
- [x] Phase 2 — DocumentManager + listeners
- [x] Phase 3 — CircuitInfo (flags + URL params)
- [x] Phase 4 — CircuitLoader + setup-list menu builder
- [x] Phase 5 — CircuitElmCreator (createCe + constructElement)
- [x] Phase 6 — Session persistence + closed-tab history
- [x] Phase 7 — Per-tab UI-state save/restore

## Phases

### Phase 1 — CircuitDocument core (`CircuitDocument.java`) [DONE]

**Implements:** [SP_DOC_01_01](./document-model.sp.md#SP_DOC_01_01)

- Owned-stack fields; SimulationLoop inner class (16 ms Timer);
  LogBuffer inner class (100-line ring); element-id counters.

### Phase 2 — DocumentManager (`DocumentManager.java`) [DONE]

**Implements:** [SP_DOC_02_01](./document-model.sp.md#SP_DOC_02_01), [SP_DOC_02_02](./document-model.sp.md#SP_DOC_02_02), [SP_DOC_02_03](./document-model.sp.md#SP_DOC_02_03)

- Listener interface; createDocument / setActiveDocument / closeDocument;
  closedTabsHistory; setInitialDocument bootstrap.

### Phase 3 — CircuitInfo (`CircuitInfo.java`) [DONE]

**Implements:** [SP_DOC_01_03](./document-model.sp.md#SP_DOC_01_03)

- `loadQueryParameters()` over `QueryParameters` + `OptionsManager`.
- `euroSetting` derived from `euroRes`/`usRes`/`euroGates` flags.

### Phase 4 — CircuitLoader (`CircuitLoader.java`) [DONE]

**Implements:** [SP_DOC_02_05](./document-model.sp.md#SP_DOC_02_05)

- `readCircuit` three overloads via `CircuitFormatRegistry`.
- `readSetupFile` / `loadFileFromURL` over `RequestBuilder`.
- Static `loadSetupList` / `processSetupList` (menu builder with `+`/`-`/`#`/`>` tokens).

### Phase 5 — CircuitElmCreator (`CircuitElmCreator.java`) [DONE]

**Implements:** [SP_DOC_02_06](./document-model.sp.md#SP_DOC_02_06)

- Giant switch for ~150 types; alias table; `"CustomCompositeElm:"` special case.
- `readDescription` tail-scan for `#` comments.

### Phase 6 — Session persistence (`DocumentManager.saveSession` / `restoreSession`) [DONE]

**Implements:** [SP_DOC_02_04](./document-model.sp.md#SP_DOC_02_04)

- 1 s debounce via `saveTimer`.
- Temp active-swap to dump inactive tabs.

### Phase 7 — UI-state save/restore (`CircuitDocument.saveUIState` / `restoreUIState`) [DONE]

- 6-element transform matrix copy; scroll-bar values; display toggles.

## Backlog

- Split `CircuitInfo` into `DocumentFileInfo` / `DocumentDisplayPrefs` /
  `DocumentStartupOptions`.
- Move `showResistanceInVoltageSources` out of `CircuitInfo`.
- Move `startCircuit`/`startLabel` off every doc onto a global startup
  options singleton.
- Replace `NodeMapEntry` O(n) merge walk with Union-Find (netlist-graph
  backlog; shared concern).
- Preserve undo stack across `restoreLastClosedTab`.
- Factor out dual-registration (createCe + JSON factory) into a single
  registry.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
