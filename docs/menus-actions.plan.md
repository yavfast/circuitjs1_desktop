# Implementation Plan: Menus & Actions  {#PL_MEN}

> **Code:** PL_MEN
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_MEN](./menus-actions.concept.md)
> **Specification:** [SP_MEN](./menus-actions.sp.md)
> **Depends on plans:** PL_UTL, PL_DRT, PL_IOF, PL_DOC, PL_EDI
> **Used by plans:** PL_EDI, PL_CLP, PL_SCP

## Goal

Provide the single command funnel — menu bar, popup menus, toolbar, keyboard shortcuts — that dispatches every user intent into the owning subsystem via `ActionManager.menuPerformed(menu, item)`.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Command token | `MyCommand(menu, item)` | single GWT Command token plugs into every widget |
| Dispatch | giant `if/else` on interned strings | keeps all actions visible in one file |
| i18n | `Locale.LS` wraps every label | single point for translation |
| Shortcut table | `String[] shortcuts[127]` | ASCII-indexed for speed |
| Keyboard priority | GWT `Event.addNativePreviewHandler` | app-wide capture |

## Progress

- [x] Phase 1 — MyCommand token
- [x] Phase 2 — MenuManager menu trees (file/edit/draw/scopes/options/elm/circuits/help)
- [x] Phase 3 — Check items + `doMainMenuChecks` sync
- [x] Phase 4 — Shortcut parsing & persistence
- [x] Phase 5 — Popup menu routing (scope/element/main)
- [x] Phase 6 — Toolbar (playback + edit + element-creation variant palettes)
- [x] Phase 7 — ActionManager.menuPerformed dispatch
- [x] Phase 8 — ActionManager.onPreviewNativeEvent keyboard preview
- [x] Phase 9 — dumpOptions `$` header emitter
- [x] Phase 10 — import/export entry helpers

## Backlog

From `.dev_flow/onboard/analysis/layer3__editor-interaction.md` §Issues:

- **String identity (`==`) on menu/item constants** — fragile if any caller constructs strings dynamically.
- **300-line `if` chain** in `menuPerformed` — no central registry, easy to miss branches.
- **`shortcuts[]` ASCII-127 sized** — non-ASCII shortcuts unreachable.
- **`Ctrl+Shift+T` registered twice** — unreachable second branch.
- **`Toolbar.createIconButton` duplicated** for ClickHandler vs MyCommand.
- **Toolbar Run/Stop bypasses funnel** — direct ClickHandler.
- **`CustomCompositeModel.sequenceNumber` polling** in `doMainMenuChecks` to refresh subcircuit menu.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
