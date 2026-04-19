# Menus & Actions — Dispatch & i18n  {#C_MEN}

> **Code:** C_MEN
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard-doc-gen
>
> **Depends on:** [C_UTL](./util-locale-log.concept.md), [C_DRT](./dialog-routing.concept.md), C_IOF (io-framework), [C_DOC](./document-model.concept.md), [C_EDI](./canvas-editor.concept.md)
> **Used by:** [C_EDI](./canvas-editor.concept.md), [C_CLP](./clipboard.concept.md), [C_SCP](./scope-visualization.concept.md)
> **Spike:** —
> **Specification:** [SP_MEN](./menus-actions.sp.md)
> **Plan:** [menus-actions.plan.md](./menus-actions.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/layer3__editor-interaction.md` §§2.5–2.8, §8.
>
> One funnel — `ActionManager.menuPerformed(menu, item)` — resolves every user command from menus, popup menus, toolbar, and keyboard shortcuts. `MenuManager` builds the menu tree and owns the shortcut array and class-label map; `Toolbar` is the view-layer mirror; `MyCommand(menu, item)` is the GWT `Command` token.

## 1. Philosophy  {#C_MEN_01}

### 1.1. Core Principle  {#C_MEN_01_01}

All user intent — Menu click, toolbar button, popup menu selection, keyboard shortcut — flows through one string-keyed dispatcher, so the action surface is trivially introspectable and "shortcut or menu acts the same" is guaranteed. The cost is a ~300-line `if/else` chain keyed on interned string literals.

### 1.2. Design Constraints  {#C_MEN_01_02}

- **`MyCommand(menu, item)` is the sole command token.** Not undoable by itself (see C_UND).
- **String-literal `==` comparisons** are used throughout `menuPerformed` and `Scope.handleMenu`; both sides are interned, so identity matches equality. Fragile if any caller constructs strings dynamically.
- **`Locale.LS(...)` wraps every user-visible label.** Shortcuts are parsed from labels and stored in `MenuManager.shortcuts[]` (ASCII-127 sized).
- **Check items are the source of truth** for display flags (`dotsCheckItem`, `voltsCheckItem`, etc.); `DisplaySettings` exposes read-only views.
- **`key` menu is the shortcut facade.** When a shortcut fires over a hovered element, the menu name is promoted `"key"` → `"elm"` so keyboard shortcuts act as contextual commands.

## 2. Domain Model  {#C_MEN_02}

### 2.1. Key Entities  {#C_MEN_02_01}

```
MyCommand implements GWT Command
  String menuName, itemName
  execute()  -> circuitjs1.mysim.actionManager.menuPerformed(menuName, itemName)
  setItemName(String)  -- used by Toolbar variant-button swaps

ActionManager extends BaseCirSimDelegate
  onPreviewNativeEvent(Event.NativePreviewEvent)  -- global keyboard preview
  menuPerformed(menu, item)                       -- ~300-line dispatcher
  dumpCircuit(formatId) / dumpOptions()           -- io exit helpers
  importCircuitFromText(text, subcircuitsOnly)
  doExportAsUrl/Text/Json/Image, doCreateSubcircuit, doImageToClipboard

MenuManager extends BaseCirSimDelegate
  MenuBar mainMenuBar, fileMenuBar, editMenuBar, drawMenuBar,
          circuitsMenuBar, optionsMenuBar, elmMenuBar, selectScopeMenuBar
  CheckboxMenuItem dotsCheckItem, voltsCheckItem, powerCheckItem,
          smallGridCheckItem, crossHairCheckItem, showValuesCheckItem,
          conductanceCheckItem, euroResistorCheckItem, euroGatesCheckItem,
          printableCheckItem, conventionCheckItem, noEditCheckItem,
          mouseWheelEditCheckItem, toolbarCheckItem, mouseModeCheckItem
  ScopePopupMenu scopePopupMenu
  PopupPanel contextPanel
  String[] shortcuts = new String[127]              -- ASCII-indexed
  HashMap<String,String> classToLabelMap
  boolean isMac; String ctrlMetaKey

Toolbar extends HorizontalPanel
  playback buttons (run/stop/reset)
  edit buttons (undo/redo/cut/copy/paste/duplicate/find/center/zoom)
  element-creation button-sets (variant palettes via createButtonSet)
  Map<String,ToolbarButton> highlightableButtons  -- keyed by MyCommand.itemName
```

### 2.2. Data Flows  {#C_MEN_02_02}

```
MenuBar / MenuItem  -- setCommand(new MyCommand(menu, item)) --┐
Toolbar button      -- new MyCommand(menu, item) --------------┤
Keypress shortcut   -- ActionManager.onPreviewNativeEvent ─────┤
scopepop items      -- MyCommand("scopepop", ...) ─────────────┤
                                                                ▼
                                             ActionManager.menuPerformed
                                                 │
                                                 ├── file   → load/save/import/export
                                                 ├── edit   → undo/redo/cut/copy/paste/flip/…
                                                 ├── main   → setMouseMode(item)
                                                 ├── options→ dialogs + checkbox flags
                                                 ├── scopes → scopeManager
                                                 ├── scopepop → scope.handleMenu
                                                 ├── elm/key → context commands
                                                 ├── circuits → load preset
                                                 └── zoom   → renderer.zoomCircuit
                                                 → cirSim.repaint()
```

## 3. Mechanisms  {#C_MEN_03}

### 3.1. Core Algorithm  {#C_MEN_03_01}

**Keyboard preview.** `onPreviewNativeEvent` runs on GWT native preview priority. It guards `dialogIsShowing` (Escape/Enter go to ScrollValuePopup or active Dialog), handles zoom/search (`+`, `-`, `0`, `/`), Delete/Backspace, Escape, Ctrl/Meta combos (C/X/V/Z/Y/D/A/P/N/T/S/O), and forwards lowercase letters through `menuManager.shortcuts[code]` → `setMouseMode`.

**Menu build.** `MenuManager` constructor detects Mac (`navigator.platform`), picks `ctrlMetaKey` prefix for shortcut labels. Each menu is built by an `init*MenuBar` method that adds entries via `menuItemWithShortcut(icon, text, shortcut, MyCommand)` or `iconMenuItem(icon, text, Command)`, injecting a CSS icon class and piping labels through `Locale.LS`. Add-element trees are composed twice (main + draw) via `composeMainMenu(bar, num)` so per-variant check items are independent.

**Popup.** `doPopupMenu()` decides scope-popup vs element-popup vs main-popup based on `scopeSelected` / `mouseElm`, updates per-item enable state (can-flip, can-split, etc.), positions `contextPanel` clamped to canvas bounds.

**Shortcut persistence.** `saveShortcuts`/`loadShortcuts` round-trip through `OptionsManager` under key `"shortcuts"`.

### 3.2. Edge Cases  {#C_MEN_03_02}

- String identity on menu/item constants — any caller passing a `String.valueOf(...)` would silently miss a branch.
- `shortcuts[]` is ASCII-127 sized — non-ASCII shortcuts are unreachable.
- `Ctrl+Shift+T` shortcut registered twice — second branch unreachable but harmless.
- `Toolbar.createIconButton` has ClickHandler and MyCommand variants that duplicate ~20 lines of style/hover logic.
- Toolbar's Run/Stop button bypasses the funnel with a direct `ClickHandler`.

## 4. Integration Points  {#C_MEN_04}

### 4.1. Dependencies  {#C_MEN_04_01}

- **[C_UTL](./util-locale-log.concept.md)** — `Locale.LS` for every label.
- **[C_DRT](./dialog-routing.concept.md)** — `dialogManager.show*` launched from menuPerformed.
- **C_IOF** — `dumpCircuit`, `importCircuitFromText`, format registry for URL/Text/Json/Image.
- **[C_EDI](./canvas-editor.concept.md)** — `setMouseMode`, `doUndo/Redo/Cut/Copy/Paste/Delete/Flip/Split`, `doEditOptions`, `doSliders`.
- **C_DOC** — `getActiveDocument()` each dispatch; new tab / close tab.
- **C_USR (user-preferences)** — `OptionsManager` for shortcuts, display-flag check items.
- **C_SCP (scope-visualization)** — `scopepop` sub-menu via `ScopePopupMenu`; `ScopeManager.menuScope` handoff.

### 4.2. API Surface  {#C_MEN_04_02}

- `ActionManager.menuPerformed(menu, item)` — the funnel.
- `ActionManager.onPreviewNativeEvent(event)` — global keyboard preview.
- `ActionManager.dumpCircuit[WithState]([formatId])` / `dumpOptions()` — io exit.
- `ActionManager.importCircuitFromText(text, subcircuitsOnly)`.
- `MenuManager.doPopupMenu()`, `doMainMenuChecks()`, `composeSubcircuitMenu`, `composeSelectScopeMenu`, `saveShortcuts/loadShortcuts`.
- `MyCommand(menu, item)` / `setItemName(String)`.
- `Toolbar.highlightButton(key)`, `setEuroResistors(boolean)`, `updateRunStopButton()`, `createButtonSet(String[])`.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
