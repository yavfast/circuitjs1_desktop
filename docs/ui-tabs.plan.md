# Implementation Plan: UI Tabs  {#PL_TAB}

> **Code:** PL_TAB
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_TAB](./ui-tabs.concept.md)
> **Specification:** [SP_TAB](./ui-tabs.sp.md)
> **Depends on plans:** [PL_RND](./rendering-primitives.plan.md), [PL_UTL](./util-locale-log.plan.md)
> **Used by plans:** — (hosted by `CirSim`)
>
> Analysis source: [.dev_flow/onboard/analysis/ui-tabs.md](../.dev_flow/onboard/analysis/ui-tabs.md)
>
> Documents the already-shipped implementation of the `ui/tabs` package.

## Goal

Provide a multi-document tab bar bound to `DocumentManager`, with per-tab
status indicator and a popup "list all tabs" menu.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Widget layering | GWT `Composite` + `FlowPanel` | Matches project-wide GWT idiom; no Widget-leak |
| Styling | External CSS in `war/circuitjs.html` | Keeps presentation out of Java; designer-editable |
| State sync | Listener contracts (no polling) | `DocumentManager` is already event-driven |
| Status glyph | Unicode (⚠, ●, ✖, ⌄) | No image assets required |

## Progress

- [x] Phase 1 — TabWidget primitive [DONE]
- [x] Phase 2 — TabBarPanel + DocumentManager wiring [DONE]
- [x] Phase 3 — List-tabs popup [DONE]
- [x] Phase 4 — Simulation-status projection [DONE]
- [x] Phase 5 — CirSim dock integration [DONE]

## Phases

### Phase 1 — TabWidget primitive (`client/ui/tabs/TabWidget.java`) [DONE]

**Implements:** [SP_TAB_01_02](./ui-tabs.sp.md#SP_TAB_01_02),
[SP_TAB_02_05](./ui-tabs.sp.md#SP_TAB_02_05)

| Entity | Module | Purpose |
|--------|--------|---------|
| TabWidget | `ui.tabs` | Row widget with title/status/close |
| TabWidget.TabListener | `ui.tabs` | Select/close callback pair |

### Phase 2 — TabBarPanel (`client/ui/tabs/TabBarPanel.java`) [DONE]

**Implements:** [SP_TAB_02_01..04](./ui-tabs.sp.md#SP_TAB_02)

Builds root composite; implements `DocumentManagerListener` and
`TabListener`; maintains `tabMap`.

### Phase 3 — List-tabs popup [DONE]

**Implements:** [SP_TAB_05_03](./ui-tabs.sp.md#SP_TAB_05_03)

Transient `PopupPanel` + `MenuBar` populated from
`documentManager.getDocuments()`.

### Phase 4 — Simulation-status projection [DONE]

**Implements:** [SP_TAB_04_03](./ui-tabs.sp.md#SP_TAB_04_03)

Per-tab anonymous `SimulationStateListener` forwards to
`TabWidget.setStatus`.

### Phase 5 — CirSim dock integration [DONE]

**Implements:** [SP_TAB_05_03](./ui-tabs.sp.md#SP_TAB_05_03)

`CirSim` constructs `TabBarPanel` and docks it north with
`TAB_BAR_HEIGHT = 28`.

## Backlog

Items deferred (sourced from Issues in the analysis file):

- Detach anonymous `SimulationStateListener` on `onDocumentRemoved` to
  avoid listener leak (Issue 1).
- Derive `TabWidget.isActive` from the style instead of tracking a
  parallel field (Issue 2).
- Harden popup title rendering: document the `getTabTitle` plain-text
  invariant or switch to `fromString` + `SafeHtmlBuilder` (Issue 3).
- Add ARIA labels / keyboard activation for `listTabsButton` and
  `closeButton` (Issue 4).
- Rename CSS class `addTabButton` → `listTabsButton` to reflect actual
  semantics (Issue 5).
- Provide a `dispose()` / `removeListener` path on `TabBarPanel` if the
  panel ever becomes non-singleton (Issue 6).

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version; documents shipped implementation |
