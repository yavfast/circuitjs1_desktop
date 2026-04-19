# Implementation Plan: Informational Dialogs  {#PL_DIN}

> **Code:** PL_DIN
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_DIN](./dialog-info.concept.md)
> **Specification:** [SP_DIN](./dialog-info.sp.md)
> **Depends on plans:** [PL_EIC](./edit-info-contract.plan.md), [PL_PLT](./platform.plan.md), [PL_UTL](./util-locale-log.plan.md)
> **Used by plans:** — (will be filled by higher layers)
>
> Reverse-engineered plan for the 7 informational dialogs + error-handler bootstrap. Implementation is complete.

## Goal

Deliver the app-level read-only / settings dialog surfaces (About, Help, License, Shortcuts, Show Logs, Mod) plus the global uncaught-exception surface that replaces blocking `Window.alert`-style error reporting.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Static content hosting | `war/*.html` iframes | Separates prose/licence text from Java classpath; localiseable. |
| About version handshake | `SessionStorage["versionString"]` | Decouples iframe HTML from Java build version. |
| Log polling | `Timer.scheduleRepeating(500 ms)` | Live-tail feel; cheap. |
| Error surface | Non-modal singleton Dialog | Doesn't block DevTools / automation. |
| Error install | `GWT.setUncaughtExceptionHandler` in `onModuleLoad` (before `loadLocale()`) | Catch earliest possible failures. |
| Mod persistence | `OptionsManager.*OptionFromStorage("MOD_*")` | Parallel to other app preferences. |
| AboutBox base class | `PopupPanel` (historical) | — (tech debt; see backlog). |

## Progress

- [x] Phase 1 — AboutBox (iframe)
- [x] Phase 2 — HelpDialog (TabPanel of locale iframes)
- [x] Phase 3 — LicenseDialog (iframe)
- [x] Phase 4 — ShortcutsDialog (FlexTable editor)
- [x] Phase 5 — ShowLogDialog (auto-polling tail)
- [x] Phase 6 — UncaughtExceptionDialog + bootstrap install
- [x] Phase 7 — ModDialog (app-wide preferences)

## Phases

### Phase 1 — AboutBox [DONE]

Delivered: `PopupPanel` with 400×430 iframe `about.html`; Java writes version string to `SessionStorage.versionString` before the iframe loads; self-shows in ctor.

### Phase 2 — HelpDialog [DONE]

Delivered: `TabPanel` with EN and RU iframes; PL/DE/DA tab code present but commented out; fields declared but unassigned for the dead locales.

### Phase 3 — LicenseDialog [DONE]

Delivered: single 500×400 iframe; `closeOnEnter` default; `Locale.LS`-wrapped title.

### Phase 4 — ShortcutsDialog [DONE]

**Implements:** [SP_DIN_02_03](./dialog-info.sp.md#SP_DIN_02_03)

Delivered: FlexTable of (menu name, 1-char TextBox); `mainMenuItems` enumeration with early-break on multi-char shortcut; `enterPressed` commits via `menuManager.shortcuts[c] = item + saveShortcuts`; array `boxForShortcut[127]`.

### Phase 5 — ShowLogDialog [DONE]

**Implements:** [SP_DIN_02_02](./dialog-info.sp.md#SP_DIN_02_02)

Delivered: `setGlassEnabled(false)` non-modal; `Timer.scheduleRepeating(500)` polling with `lastLogSize` change guard; status `Label` with queue/write/path; `onDetach` timer cancel; clipboard copy via `execCommand`.

### Phase 6 — UncaughtExceptionDialog + bootstrap [DONE]

**Implements:** [SP_DIN_01_04](./dialog-info.sp.md#SP_DIN_01_04), [SP_DIN_02_04](./dialog-info.sp.md#SP_DIN_02_04)

Delivered: static lazy-singleton `show(Throwable, String)`; `setModal(false)`, `setGlassEnabled(false)`; installed at `circuitjs1.onModuleLoad:50-66` **before** `loadLocale()` inside a defensive try/catch so the error path never recurses through a failing dialog.

### Phase 7 — ModDialog [DONE]

Delivered: UI-scale slider + 4 checkboxes (menubar size, overlay sidebar, animation, pause-on-blur) + ListBox (speed curve) + TextBox (duration); JSNI bridges `getRealScale`, `CirSim.executeJS("setScaleUI()/redrawCanvasSize()")`; direct writes to `CirSim.MENU_BAR_HEIGHT` and `CirSim.setSidebarAnimation`.

## Backlog

Items deferred from current cycle (from `dialog-info.md` §Issues):

- **AboutBox inconsistency (#1).** Extends `PopupPanel` not `Dialog`; missing `closeOnEnter`, `getOptionPrefix` persistence, `Locale.LS` on "OK" button. Migrate to `Dialog`.
- **HelpDialog dead/commented locales (#2).** PL/DE/DA tabs commented; HTML files (`war/help/DA.html`, `DE.html`, `PL.html`) exist. Either re-enable or delete.
- **Unused fields in HelpDialog (#3).** `HorizontalPanel hp`, `VerticalPanel vpPL/vpDE/vpDA` declared but never assigned after the commented blocks.
- **ShowLogDialog comment/code drift (#4).** Comment says "every 2 seconds"; actual poll is 500 ms.
- **ShowLogDialog JSNI copy (#5).** `document.execCommand('copy')` deprecated; migrate to `navigator.clipboard`.
- **ShortcutsDialog bounds check bug (#6).** `if (c > boxForShortcut.length)` should be `>=`; char 127 would pass the `>` test then AIOOBE at `boxForShortcut[c] = box`.
- **ShortcutsDialog enumeration stop (#7).** `break` on first multi-char shortcut silently hides every later item.
- **ModDialog off-by-one + `==` String compare (#8).** Loop `i <= getItemCount()` + `==` on Strings at `:141-142`. Should be `<` and `.equals()`.
- **ModDialog mutual-exclusion pair (#9).** Two independent CheckBoxes emulating a radio group; should be `RadioButton`. Also stores typo'd key `"standart"` at `:223`.
- **ModDialog hard-coded strings (#10).** Not localised; contrast with sibling dialogs.
- **UncaughtExceptionDialog re-entrancy (#11).** No guard beyond the bootstrap try/catch; mostly benign because `show` is idempotent.
- **AboutBox iframe handshake via SessionStorage (#12).** Magic `"versionString"` key couples HTML template to Java. A GWT template param or query string is more discoverable.
- **ShowLogDialog status query side-effects (#13).** Every 500 ms tick queries `getQueueSize / isWriteInProgress / getCurrentLogFilePath` even when unchanged.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
