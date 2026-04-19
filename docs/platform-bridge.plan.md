# Implementation Plan: Platform Bridge  {#PL_PLT}

> **Code:** PL_PLT
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_PLT](./platform-bridge.concept.md)
> **Specification:** [SP_PLT](./platform-bridge.sp.md)
> **Depends on plans:** none
> **Used by plans:** higher-layer menu/editor plans that open URLs or style
> widgets.
>
> Retrospective plan covering as-built `PlatformUtils` and `GWTUtils`.

## Goal

Document the as-built state and record the outstanding `AI_TODO` marker for
Desktop API / `xdg-open` integration in `openURLWithSystemCommand`.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Language | Java 17 source | project-wide |
| Client compile target | GWT 2.12 | browser/NW.js target |
| URL opening primary path | NW.js `nw.Shell.openExternal` | native desktop behavior when bundled |
| URL opening fallback | `window.open` | works in plain browser |
| System-command fallback | TODO (`xdg-open` / Desktop API) | documented but not implemented |
| CSS plumbing | thin JSNI-free sugar over `Style.setProperty` | minimal surface |

## Progress

- [DONE] Phase 1 — PlatformUtils (URL opening + probes)
- [DONE] Phase 2 — GWTUtils (CSS sugar)
- [backlog] Phase 3 — System-command URL fallback

## Phases

### Phase 1 — PlatformUtils (`client/PlatformUtils.java`) [DONE]

Implements: [SP_PLT_02_01](./platform-bridge.sp.md#SP_PLT_02_01)–[SP_PLT_02_03](./platform-bridge.sp.md#SP_PLT_02_03)

Shipped: `openURL`, `getPlatformInfo`, `isURLOpeningSupported`,
`openURLWithJavaScript` (JSNI NW.js + window.open), stub
`openURLWithSystemCommand` with AI_TODO / AI_THINK markers.

### Phase 2 — GWTUtils (`client/GWTUtils.java`) [DONE]

Implements: [SP_PLT_02_04](./platform-bridge.sp.md#SP_PLT_02_04)–[SP_PLT_02_05](./platform-bridge.sp.md#SP_PLT_02_05)

Shipped: `setStyle`, `setStyles` (pair validation), plus family helpers
(flex/display/position family — full list in source).

## Backlog

Items transcribed from the analysis "Issues" section and AI_TODO markers:

- **Implement `openURLWithSystemCommand`** — currently a stub. Wire up
  `xdg-open` (Linux), `open` (macOS), `start` (Windows) when running under
  NW.js with shell access; gate on `isURLOpeningSupported`.
- **AI_TODO / AI_THINK in `PlatformUtils.openURL`** — Desktop API fallback
  strategy for non-NW.js runtimes not yet decided.
- **No negative tests** for popup-blocker / missing NW.js scenarios — add
  scripted devmode checks when test harness is extended.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure |
