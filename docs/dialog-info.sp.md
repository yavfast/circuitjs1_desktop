# Informational Dialogs — Specification  {#SP_DIN}

> **Code:** SP_DIN
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_DIN](./dialog-info.concept.md)
> **Depends on specs:** [SP_EIC](./edit-info-contract.sp.md), [SP_PLT](./platform.sp.md), [SP_UTL](./util-locale-log.sp.md)
> **Used by specs:** — (will be filled by higher layers)
> **Plan:** [dialog-info.plan.md](./dialog-info.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/domain-core__dialog-info.md`.
>
> Defines the 7 informational dialogs, their content-sourcing patterns, and the error-handler install point at `circuitjs1.onModuleLoad:50-66`.

## 01. Data Structures  {#SP_DIN_01}

> Implements: [C_DIN_02](./dialog-info.concept.md#C_DIN_02)

### 01_01. Dialog catalog  {#SP_DIN_01_01}

| Dialog | Extends | Content type | Source | Menu | File:lines |
|--------|---------|--------------|--------|------|------------|
| `AboutBox` | `PopupPanel` (not `Dialog`) | iframe `about.html` (400×430) | `war/about.html` + `SessionStorage["versionString"]` | Help > About | `AboutBox.java:30-61` |
| `HelpDialog` | `Dialog` | `TabPanel` of iframes (EN, RU) | `war/help/{EN,RU}.html` (PL/DE/DA commented out) | Help > User Guide | `HelpDialog.java:32-103` |
| `LicenseDialog` | `Dialog` | single iframe (500×400) | `war/help/license.html` | Help > License | `LicenseDialog.java:29-54` |
| `ShortcutsDialog` | `Dialog` | `FlexTable` rows = (menu item name, TextBox[1 char]) | runtime: `sim.menuManager.mainMenuItems` | Options > Shortcuts | `ShortcutsDialog.java:40-153` |
| `ShowLogDialog` | `Dialog` (`setGlassEnabled(false)`) | `TextArea` tail-100 + status `Label` | runtime: `sim.logManager.logEntries` auto-polled 500 ms | Help > Show Logs | `ShowLogDialog.java:14-193` |
| `UncaughtExceptionDialog` | `Dialog` (non-modal, singleton) | header `Label` + stack-trace `TextArea` | runtime: `Throwable` + pre-computed stack string | programmatic via `show(Throwable, String)` | `UncaughtExceptionDialog.java:13-79` |
| `ModDialog` | `Dialog` | GWT widgets: slider, checkboxes, ListBox, TextBox | `OptionsManager.*OptionFromStorage("MOD_*")` + JSNI `getRealScale()` | Options > Modification Setup | `ModDialog.java:42-381` |

### 01_02. Content-source pattern matrix  {#SP_DIN_01_02}

| Pattern | Members | Storage / runtime |
|---------|---------|-------------------|
| Iframe-backed static HTML | `AboutBox`, `HelpDialog`, `LicenseDialog` | `war/*.html` files (outside Java classpath) |
| Runtime aggregation | `ShortcutsDialog`, `ShowLogDialog`, `UncaughtExceptionDialog` | `MenuManager.mainMenuItems`, `LogManager.logEntries`, `Throwable` |
| Hard-coded GWT + options | `ModDialog` | `OptionsManager.MOD_*` keys, `CirSim.MENU_BAR_HEIGHT`, JSNI bridges |

### 01_03. OptionsManager keys (Mod)  {#SP_DIN_01_03}

- `MOD_UIScale` — UI scale factor.
- `MOD_TopMenuBar` — menubar size toggle.
- `MOD_overlayingSidebar` — overlay vs docked sidebar (mutually exclusive pair emulated with two checkboxes; also persists typo'd key `"standart"`).
- `MOD_overlayingSBAnimation` — animation on/off.
- `MOD_SBAnim_duration` — duration (ms).
- `MOD_SBAnim_SpeedCurve` — curve selection via ListBox.
- `MOD_showSidebaronStartup` — default visibility.
- `MOD_setPauseWhenWinUnfocused` — pause simulator on window blur.

### 01_04. Error-handler installation  {#SP_DIN_01_04}

`circuitjs1.onModuleLoad:50-66`:

```
GWT.setUncaughtExceptionHandler(e -> {
    stackTrace = stringify(e.getStackTrace())   # :52-55
    GWT.log(...); e.printStackTrace()
    try {
        UncaughtExceptionDialog.show(e, stackTrace)
    } catch (Throwable ignored) { /* never recurse */ }
})
# installed BEFORE loadLocale()
```

`UncaughtExceptionDialog.show(Throwable, String)` is `static`, lazy-singletons the instance at `:49-56`, and sets `setModal(false) + setGlassEnabled(false)` at `:22-23`.

## 02. Contracts  {#SP_DIN_02}

### 02_01. DialogManager factory methods  {#SP_DIN_02_01}

| Factory | Target | activeDialog tracked? |
|---------|--------|-----------------------|
| `showHelpDialog()` | `HelpDialog` | yes |
| `showLicenseDialog()` | `LicenseDialog` | yes |
| `showAboutBox()` | `AboutBox` | no (fire-and-forget; not a `Dialog`) |
| `showModDialog()` | `ModDialog` | yes |
| `showShortcutsDialog()` | `ShortcutsDialog` | yes |
| (inline) `new ShowLogDialog(cirSim).show()` | `ShowLogDialog` | no (non-modal; parallel) |

### 02_02. ShowLogDialog polling loop  {#SP_DIN_02_02}

```
timer = Timer.scheduleRepeating(500 ms)
onTick:
    IF logManager.logEntries.size() != lastLogSize:
        ta.setText(join("\n", tail(logManager.logEntries, 100)))
        ta.setCursorPos(...)   # auto-scroll
        lastLogSize = size
    status.setText(join(" | ", queueSize, writeInProgress, currentLogFilePath))
onDetach: timer.cancel()
copyToClipboardButton → execCommand('copy')   # deprecated
```

### 02_03. ShortcutsDialog commit  {#SP_DIN_02_03}

```
FOR each (item, textBox) row where item.shortcut.length <= 1:
    IF textBox.text changed:
        c = textBox.text[0]
        IF c < boxForShortcut.length:                # BUG: should be <= ... fence error
            item.setShortcut(c)
            menuManager.shortcuts[c] = item
            ...
menuManager.saveShortcuts()
closeDialog()
```

### 02_04. UncaughtExceptionDialog.show  {#SP_DIN_02_04}

```
IF instance == null: instance = new UncaughtExceptionDialog()
header.setText(e.getClass().getName())
body.setText(e.getClass().getSimpleName() + ": " + e.getMessage() + "\n\n" + stackTrace)
if !isShowing(): show(); center()
```

### 02_05. ModDialog apply (on toggle / enterPressed)  {#SP_DIN_02_05}

Every toggle/slider change writes directly — there is no Apply batch:
```
slider.change → OptionsManager.setOptionInStorage("MOD_UIScale", val)
                CirSim.executeJS("setScaleUI()")
                CirSim.executeJS("CircuitJS1.redrawCanvasSize()")
topMenuBar.toggle → CirSim.MENU_BAR_HEIGHT = newH
overlaySB.toggle → pair-exclusion logic (radio emulated with two checkboxes)
SBAnim fields → CirSim.setSidebarAnimation(duration, curve)
pauseOnBlur → OptionsManager.setBooleanOptionInStorage("MOD_setPauseWhenWinUnfocused", ...)
```

## 03. Validation Rules  {#SP_DIN_03}

### 03_01. Input Validation  {#SP_DIN_03_01}

- `ShortcutsDialog` bounds check is off-by-one (`>` should be `>=`); chars with ASCII > 126 may AIOOBE.
- `ShortcutsDialog` silently stops enumerating at first multi-char shortcut (heuristic for top-level boundary).
- `ModDialog.getSpeedCurveSBIndex` off-by-one (`i <= getItemCount()` should be `<`) and uses `==` String compare (should be `.equals()`).
- `ModDialog` hard-codes localisation-bypassed strings; typo `"standart"` is persisted as-is.
- `ShowLogDialog` tail-100 is a magic constant; no user-configurable tail length.
- `ShowLogDialog.copyToClipboard` uses deprecated `execCommand('copy')`.
- `UncaughtExceptionDialog.show` is idempotent — second call while showing is benign.
- `AboutBox` button label is not `Locale.LS`-wrapped.

## 04. State Transitions  {#SP_DIN_04}

### 04_01. Error-dialog lifecycle  {#SP_DIN_04_01}

```
app load ──onModuleLoad:50-66──▶ handler installed
                                   │
                                   ▼
any Throwable uncaught ─▶ handler fires
                                   │
                                   ▼
    UncaughtExceptionDialog.show(e, st)
                                   │
                       lazy-singleton create
                                   │
                                   ▼
                            visible, non-modal
                                   │
                       subsequent exception
                                   │
                                   ▼
                           instance reused; body overwritten
```

### 04_02. ShowLogDialog lifecycle  {#SP_DIN_04_02}

```
menu click ──▶ new ShowLogDialog(cirSim).show()
                │
                ▼
        timer.scheduleRepeating(500 ms)
                │
                ▼
          polling loop (tail-100 + status)
                │
          user hides / detaches
                │
                ▼
           onDetach: timer.cancel
```

## 05. Verification Criteria  {#SP_DIN_05}

### 05_01. Functional Expectations  {#SP_DIN_05_01}

| Contract | Scenario | Input | Expected |
|----------|----------|-------|----------|
| Error handler | uncaught Throwable thrown anywhere | — | dialog visible with type + stack; no blocking alert |
| ShortcutsDialog | user types 'a' into row | — | `menuManager.shortcuts['a']` set; `saveShortcuts()` persists |
| ShowLogDialog | logs grow | `logManager.logEntries.add(...)` | TextArea re-rendered within 500 ms |
| AboutBox | open | Help > About | iframe loads `about.html` displaying version from SessionStorage |
| HelpDialog | Russian tab | select RU | iframe `help/RU.html` visible |
| ModDialog UI scale | slider drag | — | `OptionsManager` persisted; `setScaleUI` + `redrawCanvasSize` run |
| LicenseDialog | open | Help > License | iframe license.html rendered 500×400 |

### 05_02. Invariant Checks  {#SP_DIN_05_02}

| Invariant | Verification |
|-----------|--------------|
| Exactly one `UncaughtExceptionDialog` instance across session | static `instance` reference check |
| Error-handler install precedes `loadLocale()` | ordering audit of `onModuleLoad` |
| `ShowLogDialog` timer cancelled on detach | leak audit |
| `AboutBox` does not track `activeDialog` | `DialogManager.showAboutBox` doesn't assign |
| `ShortcutsDialog` short-circuits on first multi-char shortcut | current behaviour; doc as design |

### 05_03. Integration Scenarios  {#SP_DIN_05_03}

| Scenario | Preconditions | Steps | Expected |
|----------|---------------|-------|----------|
| Deliberate NPE in element draw | dev-mode active | crash during draw | error dialog opens; app keeps running (non-modal) |
| Log file paging | `logManager` writes to disk queue | watch ShowLogDialog | status line updates with queue size + write-in-progress |
| Shortcut conflict | two menu items claiming same char | try second | second overwrites first entry in `menuManager.shortcuts[]` |
| Sidebar animation | Mod toggles | enable anim + duration 500 | `CirSim.setSidebarAnimation(500, curve)` invoked; sidebar slides |

### 05_04. Edge Cases  {#SP_DIN_05_04}

| Case | Input | Expected |
|------|-------|----------|
| Shortcut char with ASCII > 126 | '€' paste | currently AIOOBE (bounds bug) |
| Multi-char shortcut early in menu | artificial test | every later item hidden from dialog |
| Missing `war/help/EN.html` | delete file | iframe 404; dialog still opens |
| Error handler itself throws | UI failure inside `show` | outer try/catch swallows; no recursion |
| Two exceptions in rapid succession | — | instance reused; last exception visible |

## 06. Constants  {#SP_DIN_06}

- `ShowLogDialog` tail size: 100.
- `ShowLogDialog` poll interval: 500 ms (comment says "2 seconds" — drift).
- `ShortcutsDialog.boxForShortcut[].length = 127`.
- `AboutBox` iframe dims: 400×430; iframe src: `about.html`.
- `LicenseDialog` iframe dims: 500×400.
- `OptionsManager` key prefix for Mod: `"MOD_"`.
- DOM/SessionStorage key: `"versionString"` (AboutBox).

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
