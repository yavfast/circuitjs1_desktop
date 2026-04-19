# Informational Dialogs — App-Level Read-Only Surfaces  {#C_DIN}

> **Code:** C_DIN
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard-doc-gen
>
> **Depends on:** [C_EIC](./edit-info-contract.concept.md), [C_PLT](./platform.concept.md), [C_UTL](./util-locale-log.concept.md)
> **Used by:** — (will be filled by higher layers)
> **Spike:** —
> **Specification:** [SP_DIN](./dialog-info.sp.md)
> **Plan:** [dialog-info.plan.md](./dialog-info.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/domain-core__dialog-info.md` (7 dialogs in `client/dialog/`).
>
> Seven read-only / app-preferences dialog surfaces — About, Help, License, Shortcuts, ShowLog, UncaughtException, Mod — plus the global uncaught-exception error surface installed at `circuitjs1.onModuleLoad`. Distinct from per-element edit dialogs: these expose information *about the simulator itself*, not about circuit components.

## 1. Philosophy  {#C_DIN_01}

### 1.1. Core Principle  {#C_DIN_01_01}

Informational dialogs are the app's self-referential UI: static documentation (About, License, Help), runtime aggregation of app state (Logs, Shortcuts), and app-wide preferences with no per-element coupling (Mod). They do not implement `Editable` — content is sourced directly from iframes, runtime registries, or hard-coded GWT widgets, and persistence (when any) goes through `OptionsManager` + `menuManager.saveShortcuts` rather than the `EditInfo` pipeline.

The `UncaughtExceptionDialog` is the one outlier: structurally an info dialog (non-modal, read-only text), but semantically the system's global error surface — installed once at bootstrap and invoked programmatically via `GWT.setUncaughtExceptionHandler`.

### 1.2. Design Constraints  {#C_DIN_01_02}

- **No `Editable` implementation.** These dialogs build widgets directly; `EditInfo` is not used.
- **Content-sourcing is per-dialog.** Iframe (About, Help, License), runtime aggregation (Shortcuts from `menuManager.mainMenuItems`, ShowLog from `logManager.logEntries`, UncaughtException from `Throwable`), or hard-coded GWT widgets + `OptionsManager` keys (Mod).
- **Non-modal where workflow requires.** `ShowLogDialog` and `UncaughtExceptionDialog` set `setGlassEnabled(false)` so DevTools / parallel workflows are unblocked.
- **AboutBox is a `PopupPanel`, not `Dialog`.** Historical outlier — no position-persistence, no `Locale.LS` on button labels, no collapse toggle.
- **Error dialog is singleton + defensive.** Bootstrap wraps `UncaughtExceptionDialog.show` in `try { } catch (Throwable ignored) {}` so a failing UI cannot loop back into the handler.

## 2. Domain Model  {#C_DIN_02}

### 2.1. Key Entities  {#C_DIN_02_01}

```
dialog/
  AboutBox                  — PopupPanel (iframe war/about.html + SessionStorage version)
  HelpDialog                — Dialog; TabPanel of iframes (EN/RU active; PL/DE/DA commented out)
  LicenseDialog             — Dialog; single iframe war/help/license.html
  ShortcutsDialog           — Dialog; FlexTable of (menu name, shortcut TextBox)
  ShowLogDialog             — non-modal Dialog; auto-polling TextArea (500 ms)
  UncaughtExceptionDialog   — non-modal singleton Dialog; stack-trace TextArea
  ModDialog                 — Dialog; hand-rolled GWT widgets + OptionsManager MOD_* keys

circuitjs1.onModuleLoad:50-66 — installs GWT.setUncaughtExceptionHandler
```

### 2.2. Data Flows  {#C_DIN_02_02}

**Iframe-backed static HTML:**
```
AboutBox: SessionStorage["versionString"] ← Java; war/about.html ← iframe src
HelpDialog: TabPanel {iframe war/help/EN.html, iframe war/help/RU.html}
LicenseDialog: iframe war/help/license.html (500×400)
```

**Runtime aggregation:**
```
ShortcutsDialog:
  sim.menuManager.mainMenuItems → FlexTable of (name, TextBox[1 char])
  user types → enterPressed() → item.setShortcut + menuManager.shortcuts[c] + saveShortcuts

ShowLogDialog:
  Timer.scheduleRepeating(500 ms) → logManager.logEntries tail-100 → TextArea + auto-scroll
  + status line from getQueueSize/isWriteInProgress/getCurrentLogFilePath
  onDetach → timer.cancel

UncaughtExceptionDialog:
  circuitjs1.onModuleLoad:50-66:
    GWT.setUncaughtExceptionHandler(e → show(e, stackTraceString))
  show(Throwable, String) [static, lazy-singleton]:
    header + type + ": " + msg + "\n\n" + stackTrace → TextArea
```

**Hard-coded + persisted options (Mod):**
```
OptionsManager.*OptionFromStorage("MOD_*") ↔ widgets
  MOD_UIScale, MOD_TopMenuBar, MOD_overlayingSidebar, MOD_overlayingSBAnimation,
  MOD_SBAnim_duration, MOD_SBAnim_SpeedCurve, MOD_showSidebaronStartup,
  MOD_setPauseWhenWinUnfocused
JSNI: getRealScale (devicePixelRatio / CSS), CirSim.executeJS("setScaleUI() / redrawCanvasSize()")
Static hooks: CirSim.MENU_BAR_HEIGHT, CirSim.getDefaultScale, CirSim.setSidebarAnimation
```

## 3. Mechanisms  {#C_DIN_03}

### 3.1. Core Algorithm  {#C_DIN_03_01}

**Menu dispatch** (`ActionManager.doMenuChecks`): 5 file/help/license/about/showlogs/modsetup entries map to `DialogManager` factory methods. `showlogs` constructs `new ShowLogDialog(cirSim).show()` inline (bypasses `activeDialog` — non-modal intent). `options/shortcuts` routes to `dialogManager.showShortcutsDialog`.

**DialogManager factories** (`DialogManager.java:63-88`): `showHelpDialog / showLicenseDialog / showAboutBox / showModDialog / showShortcutsDialog`. All but `AboutBox` set `activeDialog` for lifecycle tracking. `AboutBox` is fire-and-forget because it extends `PopupPanel` and self-shows in its ctor.

**Error handler install** (`circuitjs1.java:50-66`): before `loadLocale()`, registers `GWT.setUncaughtExceptionHandler` which stringifies the stack, calls `UncaughtExceptionDialog.show(e, stackTrace)` inside a defensive try/catch, and emits `GWT.log + e.printStackTrace`.

**ShowLogDialog polling** (`ShowLogDialog.java:102-155`): scheduled repeating 500 ms; each tick compares `logManager.logEntries.size()` to `lastLogSize` and rebuilds TextArea tail-100 only on change; always updates status line (queue size, write-in-progress, log file path). `onDetach` cancels timer.

**ShortcutsDialog enumeration** walks `mainMenuItems` but breaks on the first item whose shortcut length > 1 (heuristic for "top-level vs submenu boundary"); key-handling maps ASCII char → `boxForShortcut[c]` (array size 127 — off-by-one bound check).

### 3.2. Edge Cases  {#C_DIN_03_02}

- **AboutBox doesn't extend Dialog** — no shared `closeOnEnter`, no `getOptionPrefix`, no `Locale.LS` on "OK" button.
- **HelpDialog PL/DE/DA locales are dead code** — iframes commented out, fields declared but unassigned.
- **ShowLogDialog comment/code drift** — comment says "every 2 seconds"; actual `scheduleRepeating(500)` polls every 0.5 s.
- **ShowLogDialog clipboard uses deprecated `execCommand('copy')`** (`:190-192`).
- **ShortcutsDialog bounds bug** — `if (c > boxForShortcut.length)` should be `>=`; char 127 would pass then AIOOBE at `boxForShortcut[c] = box`.
- **ShortcutsDialog enumeration stop on multi-char shortcut** — `break` at `:66-67` silently hides every later item.
- **ModDialog off-by-one in speed-curve loop** (`i <= getItemCount()`) + `==` String compare (`:141-142`).
- **ModDialog hard-coded strings** — not localised; contrast with siblings.
- **ModDialog typo'd key `"standart"`** (`:223`) and label.
- **UncaughtExceptionDialog** is singleton across session — last-exception-wins; no discard.
- **AboutBox / SessionStorage coupling** — iframe reads `sessionStorage.versionString` set by Java; no typed handshake.
- **`ShowLogDialog` status query side-effects** each tick (`getQueueSize` / `isWriteInProgress` / `getCurrentLogFilePath`) regardless of state change.

## 4. Integration Points  {#C_DIN_04}

### 4.1. Dependencies  {#C_DIN_04_01}

- **[C_EIC](./edit-info-contract.concept.md)** — all info dialogs except AboutBox extend `dialog.Dialog` for position/collapse/anchor behaviour. None implements `Editable`.
- **[C_PLT](./platform.concept.md)** — JSNI (Mod `getRealScale`, `CirSim.executeJS`; ShowLog clipboard); GWT `iframe`, `TabPanel`, `FlexTable`, `Timer.scheduleRepeating`.
- **[C_UTL](./util-locale-log.concept.md)** — `Locale.LS` wraps strings (all dialogs except UncaughtExceptionDialog and ModDialog).
- **`client/` root** — `CirSim` (`menuManager`, `logManager`, `MENU_BAR_HEIGHT`, `getDefaultScale`, `setSidebarAnimation`, `executeJS`), `ActionManager` (`doMenuChecks` dispatch), `DialogManager` (factory methods), `MenuManager` (menu items → `mainMenuItems`), `OptionsManager` (`MOD_*` keys, `saveShortcuts`).
- **war/ resources** — `about.html`, `help/EN.html`, `help/RU.html`, `help/license.html` (plus unused `DA/DE/PL.html`).

### 4.2. API Surface  {#C_DIN_04_02}

- `DialogManager.show{Help,License,About,Mod,Shortcuts}Dialog()` — menu-dispatch factory methods.
- `UncaughtExceptionDialog.show(Throwable, String)` (static, lazy-singleton) — the programmatic entry for global errors.
- `ShowLogDialog.onDetach` — timer-cancellation hook.
- `ShortcutsDialog.enterPressed` — commit shortcut edits to `menuManager`.
- `ModDialog` — no public surface beyond construction; all writes happen synchronously through `OptionsManager.setOption*InStorage` + direct `CirSim.*` static mutations on toggle.
- Protocol contract: every info dialog must source text through `Locale.LS` (exception: `UncaughtExceptionDialog`, deliberately bypass to avoid re-entrancy inside the error path).

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
