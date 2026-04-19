# domain-core / dialog-info

## Summary
Seven read-only / settings dialogs surface static product info (About, License, Help, Shortcuts, Log, Mod-setup) plus the non-blocking global error surface (UncaughtExceptionDialog). All are thin GWT `PopupPanel`/`Dialog` wrappers. Content is either an `<iframe src="war/help/*.html|about.html">`, runtime aggregation (logs, shortcuts, runtime error data), or hard-coded GWT widgets (ModDialog settings). Instantiation is routed through `DialogManager` from menu commands in `ActionManager`; the error dialog is installed once in `circuitjs1.onModuleLoad`.

## Purpose
Provides user-facing informational overlays and simple preference surfaces that do NOT edit circuit elements. These are "read/configure the app", not "read/configure a component" — distinct from `EditDialog`, `EditInfo`, and element-specific edit dialogs.

## Per-dialog catalog

| Dialog | Extends | Content type | Source | Shortcut / Menu | File:lines |
|---|---|---|---|---|---|
| `AboutBox` | `PopupPanel` directly (not `Dialog`!) | `<iframe src="about.html" w=400 h=430>` | `war/about.html` + `SessionStorage["versionString"]` for iframe | Help > About... (`file/about`) | `AboutBox.java:30-61` |
| `HelpDialog` | `Dialog` | `TabPanel` of `<iframe>`s — `help/EN.html`, `help/RU.html` (PL/DE/DA commented out) | `war/help/EN.html`, `war/help/RU.html` | Help > User Guide (`file/help`) | `HelpDialog.java:32-103` |
| `LicenseDialog` | `Dialog` | Single `<iframe src="help/license.html" w=500 h=400>` | `war/help/license.html` | Help > License (`file/license`) | `LicenseDialog.java:29-54` |
| `ShortcutsDialog` | `Dialog` | `FlexTable` of `(menu-item-name, TextBox shortcut char)` rows | Runtime: `sim.menuManager.mainMenuItems` | Options > Shortcuts... (`options/shortcuts`) | `ShortcutsDialog.java:40-153` |
| `ShowLogDialog` | `Dialog` (non-modal, `setGlassEnabled(false)`) | `TextArea` tail of last 100 entries + status `Label` | Runtime: `sim.logManager.logEntries` auto-polled every 500 ms | Help > Show Logs... (`file/showlogs`) | `ShowLogDialog.java:14-193` |
| `UncaughtExceptionDialog` | `Dialog` (non-modal, no-glass, singleton) | Header `Label` + read-only `TextArea` stack-trace | Runtime: `Throwable` + pre-computed stack trace string | No menu — programmatic via `show(Throwable, String)` | `UncaughtExceptionDialog.java:13-79` |
| `ModDialog` | `Dialog` | GWT widgets: scale slider (HTML+JSNI), checkboxes (menubar size, overlay sidebar, animation, pause-on-blur), ListBox (speed curve), TextBox (duration) | `OptionsManager.*OptionFromStorage("MOD_*")` + JSNI `getRealScale()` / `CirSim.executeJS` | Options > Modification Setup... (`options/modsetup`) | `ModDialog.java:42-381` |

## Content sourcing

**Iframe-backed static HTML** (requires `war/` resources):
- `AboutBox.java:45` -> `war/about.html` (reads `sessionStorage.versionString` set at `AboutBox.java:39-40`)
- `HelpDialog.java:60,64` -> `war/help/EN.html`, `war/help/RU.html` (DA/DE/PL iframes are dead code at lines 65-77)
- `LicenseDialog.java:40` -> `war/help/license.html`

**Runtime aggregation**:
- `ShortcutsDialog.java:64-79` walks `sim.menuManager.mainMenuItems` up to first item whose shortcut length > 1 (multi-char shortcuts are filtered out as non-editable). `enterPressed()` writes back via `item.setShortcut` + `sim.menuManager.shortcuts[char]` + `saveShortcuts()` (`:102-120`).
- `ShowLogDialog.java:123-146` pulls `logManager.logEntries` tail-100, joins with `\n`, sets TextArea; auto-scrolls; `lastLogSize` guard prevents redundant rebuilds (`:127-128`). Timer scheduled at `scheduleRepeating(500)` (`:112`) — note the comment says 2s but code polls every 500 ms. `onDetach` cancels timer (`:181-186`).
- `UncaughtExceptionDialog.update` composes `type + ": " + msg + \n\n + stackTrace` (`:58-73`); caller is responsible for rendering the stack.

**Hard-coded GWT widgets + persisted options** (ModDialog):
- `OptionsManager` keys: `MOD_UIScale`, `MOD_TopMenuBar`, `MOD_overlayingSidebar`, `MOD_overlayingSBAnimation`, `MOD_SBAnim_duration`, `MOD_SBAnim_SpeedCurve`, `MOD_showSidebaronStartup`, `MOD_setPauseWhenWinUnfocused`.
- JSNI bridges: `getRealScale()` reads `devicePixelRatio` / CSS transform / localStorage (`:54-114`); `CirSimIsRunning()` (`:128-130`, unused here); `CirSim.executeJS("setScaleUI()")`, `CirSim.executeJS("CircuitJS1.redrawCanvasSize()")`.
- Static hooks: `CirSim.MENU_BAR_HEIGHT` (direct mutation at `:219,233`), `CirSim.getDefaultScale()`, `CirSim.setSidebarAnimation(duration, curve)`.

## Error-dialog hook installation

Installed once at application bootstrap:

- `circuitjs1.java:50-66` — `GWT.setUncaughtExceptionHandler` is invoked at the very top of `onModuleLoad()`, BEFORE `loadLocale()`. Handler stringifies `e.getStackTrace()` (`:52-55`), then calls `UncaughtExceptionDialog.show(e, stackTrace)` inside `try { ... } catch (Throwable ignored) {}` so a failing UI never causes a handler recursion loop. Also emits `GWT.log` + `e.printStackTrace()`.
- `UncaughtExceptionDialog.show(Throwable, String)` is `static` and lazy-singletons the instance (`:49-56`). Marked `setModal(false)` + `setGlassEnabled(false)` (`:22-23`) so automation tooling (DevTools) is not blocked — deliberate replacement for `Window.alert`-style blocking handlers (see class javadoc at `:10-11`).
- No dedicated menu entry; purely programmatic.

## Integration points

**Invocation routing** (all through a single `ActionManager.doMenuChecks` switch):
- `ActionManager.java:198-208` — `file/help`, `file/license`, `file/about`, `file/showlogs`, `options/modsetup` dispatch to `DialogManager`. Note `showlogs` constructs `new ShowLogDialog(cirSim).show()` inline (`:205`) — bypasses `DialogManager.activeDialog` tracking (intentional: it is non-modal and parallel to other dialogs).
- `ActionManager.java:284-286` — `options/shortcuts` -> `dialogManager.showShortcutsDialog()`.
- `DialogManager.java:63-88` — factory methods: `showHelpDialog`, `showLicenseDialog`, `showAboutBox`, `showModDialog`, `showShortcutsDialog`. All but `AboutBox` set `activeDialog` for lifecycle tracking; `AboutBox` is fire-and-forget (`:72`) because it doesn't extend `Dialog` and is self-showing via `show()` at `AboutBox.java:55`.

**Menu registration** (`MenuManager.java`):
- `:354` — Shortcuts menu item (`options/shortcuts`).
- `:358-359` — Mod menu item.
- `:718-730` — Help sub-menu: User Guide, License, About, Show Logs (icons: `book-open`, `license`, `info-circled`, `doc-text`).

**Shared infrastructure**:
- `Dialog` base class — provides `closeOnEnter`, `closeDialog()`, `getOptionPrefix()` (size/position memory via `OptionsManager`).
- `Locale.LS(...)` — i18n wrap applied in HelpDialog, LicenseDialog, ShortcutsDialog, ShowLogDialog (but NOT in UncaughtExceptionDialog titles/buttons — deliberate to avoid failure inside the error path; see `UncaughtExceptionDialog.java:30,43`).
- `CirSim` — `sim.menuManager` (Shortcuts), `sim.logManager` (ShowLog), static `MENU_BAR_HEIGHT` + `getDefaultScale` + `setSidebarAnimation` + `executeJS` (Mod).
- `OptionsManager` — used by ShortcutsDialog indirectly (via `menuManager.saveShortcuts()`) and ModDialog directly.

## Issues

1. **AboutBox inconsistency** — does not extend the project `Dialog` base class (`AboutBox.java:30` `extends PopupPanel`). Does not get the shared close-on-enter, `getOptionPrefix` size memory, or i18n — and its button label "OK" is not wrapped in `Locale.LS`. Likely historical; should migrate to `Dialog`.

2. **HelpDialog dead/commented locales** — PL/DE/DA tab branches (`HelpDialog.java:53-55, 65-77`) are commented out even though the HTML files exist (`war/help/DA.html`, `DE.html`, `PL.html`). Either re-enable or delete.

3. **Unused fields in HelpDialog** — `HorizontalPanel hp`, `VerticalPanel vpPL/vpDE/vpDA` are declared (`:34, 39-41`) but never assigned after the block was commented out. Dead fields.

4. **ShowLogDialog comment/code drift** — comment says "Auto-refresh timer (every 2 seconds)" (`:102`) but actual `scheduleRepeating(500)` (`:112`) polls every 0.5 s. Aggressive for a live-tailing dialog.

5. **ShowLogDialog JSNI copy** — `copyToClipboard()` uses deprecated `document.execCommand('copy')` (`:190-192`). Modern replacement is the Clipboard API.

6. **ShortcutsDialog bounds check bug** — `if (c > boxForShortcut.length)` (`:134`) should be `>= boxForShortcut.length` (array size 127, valid indices 0..126). A single char at position 127 (any char with ASCII > 126) would currently pass the `>` test but then throw `ArrayIndexOutOfBoundsException` at `:148` (`boxForShortcut[c] = box`).

7. **ShortcutsDialog enumeration stop** — `break` on first multi-char shortcut at `:66-67` silently skips the rest of the menu (not a true filter). A user-added menu item with a multi-char shortcut early in the list would hide every later item from the dialog.

8. **ModDialog `getSpeedCurveSBIndex` off-by-one + `==` string compare** — loop runs `i <= SpeedCurveSB.getItemCount()` (`:141`) which is one past the end; comparison uses `==` on Strings (`:142`) — GWT sometimes interns, but this is incorrect and brittle. Should be `<` and `.equals()`.

9. **ModDialog checkbox pair mutual-exclusion logic** is convoluted (`:216-242`) — two independent `CheckBox`es emulating a radio group. Should use a `RadioButton` group. Also stores typo'd key value `"standart"` (`:223`) and the label is also typo'd (`:211`).

10. **ModDialog hard-coded strings** — not localized (e.g. `"Modification Setup"`, `"UI Scale:"`, `"Sidebar is overlaying"`) — contrast with sibling dialogs that all use `Locale.LS`.

11. **UncaughtExceptionDialog** does not wrap `show()` in a re-entrant guard beyond the bootstrap `try/catch`; if the handler itself throws while the dialog is already showing, it will re-enter `show()` (though being idempotent this is mostly benign). Also `instance` is never discarded, so across a session it accumulates the LAST exception only — that's fine, but the comment "non-blocking" could be clearer that it is also singleton.

12. **AboutBox iframe handshake via SessionStorage** (`AboutBox.java:39-40`) couples `about.html` to a magic key `"versionString"`. A GWT HTML template parameter or query string would be more discoverable.

13. **ShowLogDialog status query side-effects** — `getQueueSize()`, `isWriteInProgress()`, `getCurrentLogFilePath()` are called on every 500 ms tick (`:149-155`) regardless of whether state changed. Likely cheap but coupled tightly.

## Concept boundary: "informational-dialogs"

The concept is: **non-editing, app-level dialog surfaces that expose read-only information (or simple app preferences) about the simulator itself, not about circuit elements.**

Inclusion rule:
- Content is static documentation (About, License, Help) OR
- Content is runtime-aggregated app state (Logs, Shortcuts list) OR
- Content is app-wide preferences with no per-element coupling (Mod).

Exclusion rule:
- Anything that reads/writes `CircuitElm` state -> `EditDialog` / `Editable` / element-specific dialogs.
- Anything tied to scope/probe -> `ScopePropertiesDialog`.
- Import/export flows -> their own cluster.

Seams:
- `DialogManager.activeDialog` is the single-slot tracker for modal info dialogs; non-modal members (`ShowLogDialog`, `UncaughtExceptionDialog`) live outside this slot deliberately.
- `ActionManager.doMenuChecks` is the sole invocation entry (except for the error dialog, installed at bootstrap).
- `war/help/*` and `war/about.html` form a content layer outside the Java classpath — these dialogs are shell components around that content.

The error dialog is the one outlier: structurally it is an informational dialog, but it is the system's global error surface rather than a user-requested view. It belongs here by shape (non-modal, read-only text), not by workflow.

## Relevant files

- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/dialog/AboutBox.java`
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/dialog/HelpDialog.java`
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/dialog/LicenseDialog.java`
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/dialog/ShortcutsDialog.java`
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/dialog/ShowLogDialog.java`
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/dialog/UncaughtExceptionDialog.java`
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/dialog/ModDialog.java`
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/circuitjs1.java` (error-handler install, lines 50-66)
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/DialogManager.java` (factory methods, lines 63-88)
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/ActionManager.java` (dispatch, lines 198-208, 284-286)
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/MenuManager.java` (menu wiring, lines 354-358, 718-730)
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/war/about.html`
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/war/help/EN.html`, `RU.html`, `license.html` (plus unused `DA.html`, `DE.html`, `PL.html`)
