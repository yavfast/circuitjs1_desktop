# Menus & Actions — Specification  {#SP_MEN}

> **Code:** SP_MEN
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_MEN](./menus-actions.concept.md)
> **Depends on specs:** SP_UTL, SP_DRT, SP_IOF, SP_DOC, [SP_EDI](./canvas-editor.sp.md)
> **Used by specs:** [SP_EDI](./canvas-editor.sp.md), [SP_CLP](./clipboard.sp.md), [SP_SCP](./scope-visualization.sp.md)
> **Plan:** [menus-actions.plan.md](./menus-actions.plan.md)

## 01. Data Structures  {#SP_MEN_01}

### 01_01. MyCommand  {#SP_MEN_01_01}

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| menuName | String | yes | Funnel key — `file`/`edit`/`main`/`options`/`scopes`/`scopepop`/`elm`/`key`/`circuits`/`view`/`zoom`. |
| itemName | String | yes | Slug within `menuName`. Mutable via `setItemName`. |

### 01_02. MenuManager public fields  {#SP_MEN_01_02}

`MenuBar`s: `menuBar, mainMenuBar, fileMenuBar, editMenuBar, drawMenuBar, circuitsMenuBar, optionsMenuBar, elmMenuBar, selectScopeMenuBar`, plus `subcircuitMenuBar[]`.

`CheckboxMenuItem`s (all public): `dotsCheckItem, voltsCheckItem, powerCheckItem, smallGridCheckItem, crossHairCheckItem, showValuesCheckItem, conductanceCheckItem, euroResistorCheckItem, euroGatesCheckItem, printableCheckItem, conventionCheckItem, noEditCheckItem, mouseWheelEditCheckItem, toolbarCheckItem, mouseModeCheckItem`.

Arrays / maps: `String[] shortcuts = new String[127]`, `HashMap<String,String> classToLabelMap`, `Vector<CheckboxMenuItem> mainMenuItems`, parallel `Vector<String> mainMenuItemNames`.

### 01_03. Toolbar state  {#SP_MEN_01_03}

| Field | Type | Description |
|-------|------|-------------|
| highlightableButtons | Map<String, ToolbarButton> | Keyed by `MyCommand.itemName` (only for `menu == "main"`). |
| runStopButton | ToolbarButton | Flips CSS via `updateRunStopButton` from `cirSim.simIsRunning()`. |
| euroResistors | boolean | Swaps resistor icon via `setEuroResistors`. |

## 02. Contracts  {#SP_MEN_02}

### 02_01. menuPerformed(menu, item)  {#SP_MEN_02_01}

Purpose: Dispatch a user command to the owning subsystem.

Input:
| Parameter | Type | Required | Constraints |
|-----------|------|----------|-------------|
| menu | String | yes | interned literal (`file`, `edit`, `main`, …). |
| item | String | yes | interned literal slug. |

Processing logic:
    FUNCTION menuPerformed(menu, item):
        IF menu == "key" AND mouseElm != null: menu = "elm"
        SWITCH menu:
            "file"    -> newtab|open|save|import*|export*|createsubcircuit|print|recover
            "edit"    -> undo|redo|cut|copy|paste|duplicate|flip*|split|selectAll|search|delete|sliders|centrecircuit
            "main"    -> circuitEditor.setMouseMode(item); updateToolbar
            "options" -> dialogManager.show*Dialog | setOptionInStorage toggles
            "scopes"  -> scopeManager.stackAll|unstackAll|combineAll|separateAll
            "scopepop"-> scope.handleMenu(item, state)
            "elm"     -> contextual on mouseElm (viewInScope, addToScopeN, …)
            "zoom"    -> renderer.zoomCircuit(+/-/0)
            "circuits"-> load preset file + new tab
        cirSim.repaint()

### 02_02. onPreviewNativeEvent  {#SP_MEN_02_02}

    FUNCTION onPreviewNativeEvent(e):
        IF dialogManager.dialogIsShowing():
            route Enter/Escape to ScrollValuePopup or active Dialog
            RETURN
        IF noEditCheckItem.getState(): RETURN
        dispatch Delete/Backspace/Escape/+-/0
        dispatch Ctrl/Meta + C/X/V/Z/Y/D/A/P/N/T/S/O
        FOR lowercase key `cc`:
            cls = menuManager.shortcuts[cc]
            IF cls != null: circuitEditor.setMouseMode(cls)

### 02_03. dumpOptions  {#SP_MEN_02_03}

Emits the `$` header line: `dots`, `smallGrid`, `!volts`, `power`, `!showValues`, `autoTimeStep` flags, plus `maxTimeStep`, `iterCount`, `current`, `voltageRange`, `power`, `minTimeStep`. Uses `CircuitElm.dumpValues`.

## 03. Validation Rules  {#SP_MEN_03}

- Menu + item values must be interned string literals (enforced by convention; breaks silently otherwise).
- `shortcuts[code]` accessed only for `code < 127`.
- Toolbar variant-button `MyCommand.setItemName` must match a `mainMenuItems` class name.

## 04. State Transitions  {#SP_MEN_04}

| From | To | Trigger | Side effect |
|------|----|---------|-------------|
| idle | Dispatching | menu click / shortcut | `menuPerformed(menu,item)` invoked |
| Dispatching | idle | handler returns | `cirSim.repaint()` |

## 05. Verification Criteria  {#SP_MEN_05}

### 05_01. Functional  {#SP_MEN_05_01}

| Contract | Scenario | Input | Expected |
|----------|----------|-------|----------|
| menuPerformed | "edit","cut" | selection present | `clipboardManager.doCut`; selection deleted |
| menuPerformed | "main","Resistor" | — | `setMouseMode("Resistor")`; cursor=cross |
| onPreviewNativeEvent | Ctrl+Z | no dialog | `doUndo` |
| onPreviewNativeEvent | r key | no dialog, shortcuts[r]="Resistor" | enters ADD_ELM Resistor |

### 05_02. Invariants  {#SP_MEN_05_02}

| Invariant | Verification |
|-----------|--------------|
| One funnel | every non-runStop button creates MyCommand |
| Locale-wrapped labels | `Locale.LS` on every `menuItemWithShortcut` call |

### 05_03. Edge Cases  {#SP_MEN_05_03}

| Case | Input | Expected |
|------|-------|----------|
| dynamic string | `String.valueOf("save")` | silently misses branch (known smell) |
| Ctrl+Shift+T duplicate | — | second branch unreachable |
| shortcut code ≥ 127 | accented letter | ignored |
| run/stop button | click | bypasses funnel, direct handler |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
