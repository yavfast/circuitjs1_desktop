# Module Analysis: domain-core / dialog-specialized

> **Path:** `src/main/java/com/lushprojects/circuitjs1/client/dialog/` (10 bespoke dialog files)
> **Layer:** 2 (SCC-A; consumers of `dialog-base` + `dialog-edit` + `shared-models`)
> **Analyzed:** 2026-04-19
> **Files:** 10 source files, 0 tests — `ControlsDialog.java`, `EditCompositeModelDialog.java`, `EditDiodeModelDialog.java`, `EditTransistorModelDialog.java`, `ScopePropertiesDialog.java`, `ScrollValuePopup.java`, `SearchDialog.java`, `SliderDialog.java`, `SlidersDialog.java`, `SubcircuitDialog.java`

## Summary (3 lines)

These ten dialogs are the **bespoke UI surfaces** that sit on top of the generic `EditDialog` / `Editable` / `EditInfo` contract — each wires a *specific* data source (shared-model catalog, `Scope`, `Adjustable` list, circuit-as-subcircuit, menu registry, playback scrollbars) to a hand-built widget tree instead of going through the row-polling flow. Three are near-empty subclasses of `EditDialog` (`Edit{Diode,Transistor}ModelDialog`, plus `EditCompositeModelDialog` which bypasses `EditDialog` but still mutates a `CustomCompositeModel`); the rest are direct `Dialog`/`PopupPanel` subclasses that build widgets from scratch. Persistence targets split four ways: element field (via `Editable`), global `Scope`, `AdjustableManager`, `CustomCompositeModel` + browser local-storage, and `simulator.maxTimeStep` direct field writes.

## Purpose

Whereas `dialog-base` defines the contract and `dialog-edit` provides the generic form renderer, `dialog-specialized` is the set of **concrete dialogs whose data model, widget layout, or apply/cancel semantics don't fit the `EditInfo`-row pattern** — or that *augment* `EditDialog` with a post-apply hook to keep the shared-model catalog consistent.

Three integration patterns are visible:

1. **`EditDialog` subclass + `model.pickName()` post-apply** — `EditDiodeModelDialog`, `EditTransistorModelDialog`. Model is itself `Editable`; the subclass exists only to strip the Apply button and auto-name + rebind the element after the model edit.
2. **Hand-rolled `Dialog` subclass with canvas/grid/scrollbar UI** — `EditCompositeModelDialog` (interactive pin drag), `ScopePropertiesDialog` (4-section grid), `ControlsDialog`, `SlidersDialog`, `SliderDialog`, `SubcircuitDialog`, `SearchDialog`. Each manually calls `setWidget(...)` on a hand-built panel.
3. **`PopupPanel` (not `Dialog`) — `ScrollValuePopup`** — a mouse-wheel inline editor that does not participate in `Dialog`'s position/collapse persistence or resize re-anchoring.

## Per-dialog catalog

| Dialog | Data source (read) | Data sink (write) | Key widgets | File:lines |
|---|---|---|---|---|
| `ControlsDialog` | `cirSim.getActiveDocument().simulator.maxTimeStep`; builtin `TIME_STEP_VALUES[]` | `simulator.maxTimeStep` + `simulator.timeStep`; also allocates `cirSim.{timeStepBar, speedBar, currentBar, powerBar}` into the scroll-bar registry | 4× `Scrollbar` (timeStep, sim speed, current speed, power brightness), dynamic time-step `Label` | `ControlsDialog.java:33-75` (ctor), `:80-103` (log-scale mapping), `:108-119` (live label) |
| `EditCompositeModelDialog` | `CirSim.getActiveDocument().simulator.getCircuitAsComposite()` → new `CustomCompositeModel`; `model.extList`, `sizeX/Y`, `showLabel()`, `isSaved()` | `model.setName()`, `model.setSaved()`, `model.sizeX/Y`, `model.showLabel`, `ExtListEntry.pos/side` live; `CustomCompositeElm.lastModelName`; `simulator.updateModels()`; `cirSim.actionManager.dumpCircuit()` baked into `model.modelCircuit` | HTML5 `Canvas` (400×400, interactive pin drag), `TextBox` model-name, `Checkbox` show-label + save-across-sessions, 4× `Button` width/height +/- | `EditCompositeModelDialog.java:76-122` (createModel/validate), `:134-237` (createDialog), `:254-267` (enterPressed commit), `:271-284` (drawChip), `:286-302` (adjustChipSize), `:306-369` (pin drag handlers) |
| `EditDiodeModelDialog` | `DiodeModel` rows via `Editable.getEditInfo()` (inherited from `EditDialog`) | `DiodeModel` fields via `setEditValue`; post-apply `model.pickName()` if unnamed; `DiodeElm.newModelCreated(model)` to rebind | Inherited form; `applyButton.removeFromParent()` (Apply button hidden) | `EditDiodeModelDialog.java:12-17` (ctor strips Apply), `:19-25` (apply override) |
| `EditTransistorModelDialog` | `TransistorModel` rows via inherited `Editable` polling | `TransistorModel` fields via `setEditValue`; post-apply `TransistorElm.newModelCreated(model)`; `pickName()` call is **commented out** | Inherited form; Apply button stripped | `EditTransistorModelDialog.java:12-17`, `:19-25` (note commented `pickName()` at `:21-22`) |
| `ScopePropertiesDialog` | `Scope` + its `visiblePlots`, `manScale`, `maxScale`, trigger/history state; `ScopeCheckBox` wrapping persisted menu cmds | `scope.setManualScale/Position/TriggerLevel/HistoryDepth/...`, `scope.handleMenu(cmd, val)` via `ScopeCheckBox.addValueChangeHandler(this)`; label via `scope.setText()`; `scope.saveAsDefault()` | `Grid(25..27,3)` main form, `FlowPanel` channel-buttons, `Scrollbar` speed + position, `RadioButton` auto/max/manual + ac/dc, ~20 `CheckBox`/`ScopeCheckBox`, `ListBox` trigger mode/slope/source, history mode/source, `TextBox` manual scale/divisions/level/holdoff/position/depth/label, `expandingLabel` section collapsers, `labelledGridManager` for row visibility | `ScopePropertiesDialog.java:243-632` (ctor, huge), `:701-752` (updateUi), `:754-821` (trigger/history ui), `:835-883` (manual scale ui), `:917-933` (apply), `:935-961` (applyTriggerHistory), `:963-967` (onValueChange for ScopeCheckBox) |
| `ScrollValuePopup` | `elm.getEditInfo(0)` single row; `e12[]` preferred-value table; element subclass to pick `minpow/maxpow` range | `ei.value = values[i]` → `elm.setEditValue(0, ei)` → `sim.needAnalyze()`; `circuitEditor.pushUndo()` on open | 5× `Label` (2 off, 1 off, selected, 1 off, 2 off), mouse-wheel drives `doDeltaY` | `ScrollValuePopup.java:59-93` (ctor, pushUndo + positioning), `:95-139` (setupValues E12 table), `:142-155` (setupLabels), `:157-181` (close/mouseOut/mouseDown semantics), `:200-213` (setElmValue), `:217-225` (getSelIdx wheelSensitivity) |
| `SearchDialog` | `sim.menuManager.mainMenuItems` (filtered by shortcut length on first build); key-up filter on `TextBox` | `item.getScheduledCommand().execute()` on selected menu entry | `TextBox` (maxLength 15), `ListBox` (10 visible), OK/Cancel buttons; live filter on `KeyUpHandler` | `SearchDialog.java:52-107` (ctor), `:109-122` (apply → execute menu cmd), `:124-145` (search/filter) |
| `SliderDialog` | `elm.getEditInfo(i)` polled like `EditDialog`; `adjustableManager.findAdjustable(elm, i)` for existing bindings; `adjustables[].sliderText/sharedSlider` | `AdjustableManager.adjustables` add/remove; `adj.{minValue, maxValue, sliderText, sharedSlider, createSlider, deleteSlider}`; `adjustableManager.reorderAdjustables()` | `Checkbox` per row (reusing `EditInfo.checkbox`!), `Choice` for slider-share target (`EditInfo.choice`), `TextBox` min/max/label in `EditInfo.minBox/maxBox/labelBox` fields | `SliderDialog.java:57-93` (ctor), `:95-163` (buildDialog — notice it hijacks `ei.checkbox/choice/minBox/maxBox/labelBox`), `:170-193` (apply), `:195-248` (itemStateChanged → share vs new slider) |
| `SlidersDialog` | `AdjustableManager` drives externally via `addSlider(...)`; dialog is passive container | Slider interactions flow through `Scrollbar` callbacks registered by caller, not the dialog | `VerticalPanel` rows of (title + value labels, `Scrollbar`, Edit-Adjustable `Button`, Edit-Element `Button`); overflowY=auto; scheduled fallback position bottom-right | `SlidersDialog.java:17-24` (ctor), `:27-46` (show + fallback position right of Controls), `:53-97` (addSlider/removeSlider/clear) |
| `SubcircuitDialog` | `CustomCompositeModel.getModelList()` filtered by `!isBuiltin()` | `model.remove()` deletes from catalog **and** browser local-storage (via `CustomCompositeModel.setSaved(false)` chain) | `ListBox` (5 visible) + Delete/Done buttons | `SubcircuitDialog.java:23-70` (ctor), `:72-88` (handleDelete — Window.confirm) |

## Per-dialog callouts

### `ControlsDialog` (`ControlsDialog.java:11`)
- **Log-scale time-step slider** — uses a fixed 22-entry `TIME_STEP_VALUES[]` table (1pS → 10µS, 1/2/5 per decade; `:19-28`). `positionToTimeStep` is O(1) array lookup; `timeStepToPosition` scans in log-space (`Math.log10` diff, `:89-103`) — O(n) but n=22.
- **Stores scrollbars on `cirSim` directly**, not on itself: `cirSim.timeStepBar = ...` (`:42`), `cirSim.speedBar`, `cirSim.currentBar`, `cirSim.powerBar`, `cirSim.powerLabel`. That is the **app-wide shared registry**; other code (simulator loop) reads those refs.
- Not modal, not autohide (`super(false, false)`, `:34`). `getOptionPrefix()` returns `"ControlsDialog"` (`:123`) so position+collapse persist.
- `updateTimeStepBar()` is called externally when simulator adjusts timestep itself.

### `EditCompositeModelDialog` (`EditCompositeModelDialog.java:61`)
- **Two entry modes**: `createModel()` (`:76-122`) builds a *new* model from the current circuit via `simulator.getCircuitAsComposite()`, auto-sorts `extList` by name, validates (no two pins on same node, at least one ext pin), auto-computes `sizeX/sizeY` from pin counts per side; **alternative** `setModel(m)` edits an existing model.
- **Canvas-based interactive pin layout** — uses a `CustomCompositeChipElm` placed at `(50,50)-(200,50)` with `postCount` pins; draws via `Graphics(context)` with dynamic scale derived from bbox (`:271-284`).
- **Pin swap on drag**: `onMouseMove` → `mouseMoved` looks up overlapping pin and swaps positions (`:334-340`), then rebuilds chip from model.
- `createDialog` branch: if `model.name == null` (new model), shows name `TextBox` + Cancel button. Existing model: name is immutable in this dialog; no cancel (OK-only).
- Sets `CustomCompositeElm.lastModelName = name` — shared static used by subsequent circuit placements.
- On commit: `simulator.updateModels()` + `needAnalyze()` (singular matrix comment `:265`).
- `closeOnEnter = true` (`:127`) — Enter → `enterPressed` → same code path as OK.

### `EditDiodeModelDialog` / `EditTransistorModelDialog`
- Minimal extension of `EditDialog`. Both **remove the Apply button** (`applyButton.removeFromParent()`, `:16`) — rationale: model edits here are terminal (OK or Cancel), no apply-and-stay.
- **Asymmetry**: diode dialog calls `model.pickName()` if unnamed (`EditDiodeModelDialog.java:21-22`), transistor dialog has this **commented out** (`EditTransistorModelDialog.java:21-22`) — inconsistent behavior, latent bug when a nameless transistor model is edited.
- Both call `elm.newModelCreated(model)` (`:23-24`) to force the element to rebind via `getModelWithNameOrCopy` (shared-models contract).
- `EditDialog` subclass means they inherit the full row-polling + parse-units + slider-sync flow from `dialog-edit` — including rebuild-on-change via `ei.newDialog`.

### `ScopePropertiesDialog` (`ScopePropertiesDialog.java:41`)
- **Four sections** organized by `expandingLabel` collapsers (`:216-241`): Vertical Scale, Horizontal Scale, Plots/X-Y/Trigger/History/Show Info/Custom Label (all inside one `Grid`), bottom OK/Save-as-Default. Section visibility is driven by two booleans computed from `Window.getClientHeight()`: `displayAll` (>600px) and `displayScales` (>470px), `:247-248` — small-screen adaptive UI.
- **`ScopeCheckBox` indirection** (`:402` etc.): wraps a menu-command string (`"showvoltage"`, `"showcurrent"`, ...) so the checkbox both persists to menu state *and* fires `scope.handleMenu(menuCmd, state)` on change. Generic `CheckBox` is used for trigger/history where no persisted menu cmd applies.
- **Live update cycle**: `refreshDraw()` (`:885-890`) called every simulation step by the scope runtime; only refreshes manual-scale UI when NOT in manual mode (so the user's typed value isn't clobbered).
- **`closeDialog()` override auto-applies** (`:892-895`) — unlike `EditDialog` where Cancel discards.
- Transistor-scope branch: replaces voltage/current checkboxes with Ib/Ic/Ie/Vbe/Vbc/Vce + Vce-vs-Ic XY plot (`:396-440`).
- Uses `EditDialog.unitString(null, v)` and `EditDialog.parseUnits(s)` as **utilities** (`:771, 852, 878, 899, 938`) — confirms `EditDialog.parseUnits` is the canonical SI parser for the whole dialog subsystem.

### `ScrollValuePopup` (`ScrollValuePopup.java:39`)
- **`PopupPanel`, not `Dialog`** — no persistence, no resize-anchoring, no collapse. Right-click mouse-wheel inline editor for R/L/C element values.
- **Preferred-value table `e12[]`** (E12 series, `:42`) × decades: Resistor `1e-1..1e7`, Capacitor `1e-11..1e-3`, Inductor `1e-6..1e0` (`:96-107`). Inserts current value into the sorted table as an extra entry if it doesn't match an existing E12 value.
- 5-slot rolling label strip (`---`, `1off`, `selected`, `1off`, `---`) gives scroll-wheel haptic feel.
- **Undo**: pushes undo on construct (`:64`) — so closing with Escape/right-click (`close(false)`) can revert by re-setting `setElmValue(currentidx)` (`:162-163`), but the undo snapshot is the *real* escape hatch.
- **Close semantics**: left/middle click = keep, right click = revert, mouse-out = keep. No keyboard (key handler commented out `:183-190`).
- Touches only `EditInfo` index 0 — assumes the primary row is the value. Hard-coded assumption: element must have a numeric EditInfo(0) with SI-formatted value.

### `SearchDialog` (`SearchDialog.java:43`)
- Fuzzy substring search over `sim.menuManager.mainMenuItems` names — lowercase `contains` (`:131`). Initial population stops at first item whose shortcut is > 1 char (`:82-83`) — that is the separator between top-level items and submenu entries.
- Empty query → all top-level items visible; filtered items sorted alphabetically, first selected (`:136-144`).
- Double-click or OK → execute the menu item's `ScheduledCommand` (`:116`). No fuzzy/ranking — plain `contains`.
- `center()` + `textBox.setFocus(true)` in ctor; no position persistence (doesn't override `getOptionPrefix`).

### `SliderDialog` (`SliderDialog.java:46`)
- **Per-element** "Add Sliders" editor — one instance of this dialog edits all adjustables on one element.
- Walks `elm.getEditInfo(i)` like `EditDialog` but skips rows where `!ei.canCreateAdjustable()` (`:103`) — i.e. choice/checkbox/button/textArea/widget/noSliders rows can't be sliderized.
- **Hijacks the `EditInfo` object**: writes into `ei.checkbox`, `ei.choice`, `ei.minBox`, `ei.maxBox`, `ei.labelBox` (`:111, 122, 145, 148, 153`) — exactly the extra fields defined in `EditInfo` for this dialog's private use (see dialog-base analysis).
- **Slider sharing**: shows `Choice` "New Slider / Share Slider: XXX" when an Adjustable already exists; selecting a share target sets `adj.sharedSlider` and deletes the redundant slider widget (`:216-241`).
- **Rebuild-on-change**: any checkbox or choice click triggers `apply() + clearDialog() + buildDialog()` (`:243-247`), same pattern as `EditDialog.itemStateChanged`.
- Silent try/catch on parse (`:179-191`) — same silent-parse-failure pattern as `EditDialog.apply`.

### `SlidersDialog` (`SlidersDialog.java:14`)
- **Passive container** for the visible on-screen adjustables list. `AdjustableManager` calls `addSlider(...)` to append a row (title + value label, horizontal scrollbar, "edit adjustable" + "edit element" buttons — `:53-81`), `removeSlider(row)` to drop.
- Persisted position+collapse via `getOptionPrefix()="SlidersDialog"` (`:49`).
- **Fallback position** (`:37-46`): if no saved position, place at right edge, top=50, computed after deferred show.
- CSS `overflowY: auto` on the dialog element (`:23`) for long lists.
- Independent of `EditInfo`/`Editable` — this is an aggregator, not an editor.

### `SubcircuitDialog` (`SubcircuitDialog.java:14`)
- CustomCompositeModel catalog manager. Lists all non-builtin composite models from `CustomCompositeModel.getModelList()` (`:40-41`).
- Delete flow: `Window.confirm` → `model.remove()` (which also unregisters from `OptionsManager`'s `subcircuit:<name>` local-storage key, per shared-models analysis) → `listBox.removeItem`.
- **Modal** (`setGlassEnabled(true)`, `:26`). Does not override `getOptionPrefix()` — position is not persisted.
- No edit; just delete. Renaming/editing composite models goes through `EditCompositeModelDialog`.

## Integration points

### Consumers (who opens which dialog)

- `DialogManager.showControls()`, `.showSlidersDialog()` — opens `ControlsDialog` / `SlidersDialog` on app startup / toolbar.
- `DialogManager.showEditCompositeModelDialog(CustomCompositeModel)` — from "Create Subcircuit" / double-click subcircuit.
- `DiodeElm.getEditInfo()` "Create Simple Model" button → `EditDiodeModelDialog` (via `ei.newDialog`).
- `TransistorElm.getEditInfo()` similar → `EditTransistorModelDialog`.
- Scope right-click → `ScopePropertiesDialog(cirSim, scope)`.
- R/L/C element right-click → `ScrollValuePopup`.
- Ctrl+F / menu "Find" → `SearchDialog`.
- Element right-click → `SliderDialog(elm, sim)`.
- `AdjustableManager` lifecycle → `SlidersDialog.addSlider/removeSlider`.
- Menu "Manage Subcircuits" → `SubcircuitDialog`.

### Downstream side effects

- **Simulator touched** by: `ControlsDialog` (`maxTimeStep/timeStep` + `needAnalyze`), `EditCompositeModelDialog` (`updateModels + needAnalyze`), `Edit{Diode,Transistor}ModelDialog` (via `EditDialog.apply` → `needAnalyze`), `SliderDialog` (none directly — relies on Adjustable to push values), `ScrollValuePopup` (`needAnalyze`), `ScopePropertiesDialog` (none — Scope is display only).
- **Undo stack touched** by: only `ScrollValuePopup.pushUndo()` (`:64`). Model-edit dialogs do **not** push undo — a latent Cancel-isn't-undo footgun.
- **Browser local storage touched** by: `EditCompositeModelDialog` (via `model.setSaved`), `SubcircuitDialog` (via `model.remove`), and `Dialog` base (position/collapse for `ControlsDialog` and `SlidersDialog`).

### Reuse of `dialog-edit` utilities

- `EditDialog.unitString(EditInfo, double)` is used by `ScopePropertiesDialog` (`:771, 852, 878`) and `SliderDialog` (`:157-158`).
- `EditDialog.parseUnits(String)` is used by `ScopePropertiesDialog.getManualScaleValue()` (`:899`), `applyTriggerHistory` (`:938`), and `SliderDialog.apply` (`:184, 186`). Confirms the SI parser is module-public.
- `EditDialog.itemStateChanged` rebuild-on-change pattern is **duplicated** in `SliderDialog.itemStateChanged` (`:195-248`) rather than inherited.

## Issues

1. **`EditTransistorModelDialog` missing `pickName()`** (`EditTransistorModelDialog.java:21-22`): the `pickName()` call is commented out. If a user edits a freshly-created unnamed transistor model through this dialog, the model is left with `name == null` or empty, and catalog lookup by name (shared-models `modelMap`) will have an empty key. Silent corruption vs. the diode path.
2. **`SliderDialog.einfos[10]` fixed array** (`:64`) inherits the same hard cap as `EditDialog`; any element with >10 adjustable-eligible rows would OOB.
3. **`SliderDialog` silent try/catch on parse** (`:189-191`) logs to console but doesn't surface to user; consistent with `EditDialog.apply` but equally painful.
4. **`ScrollValuePopup` undo semantics fragile** — pushes undo on open (`:64`). If the user wheel-scrolls then mouse-outs (`close(true)`, keep changes) and then changes their mind, the undo restores to the *pre-open* value; there's no midway snapshot. `close(false)` path (right-click) reverts by re-assigning, which is fine, but touches the element a second time via `setEditValue` without a corresponding undo push.
5. **`ScrollValuePopup` assumes `getEditInfo(0)` is numeric** (`:117`). If an element's first row is a Choice/Checkbox, the popup will NPE on `inf.value` reads or overwrite a flag. Protected by the caller (only opened on R/L/C right-click), but not robust.
6. **`ScopePropertiesDialog.closeDialog` auto-applies** (`:892-895`) — inconsistent with every other dialog in the app. Users who press the titlebar X expect "dismiss" semantics.
7. **`ScopePropertiesDialog` is 970 LOC in one file** — single-class god-dialog. The `expandingLabel` and `labelledGridManager` inner classes plus the 30+ event-handler inner classes should be extracted.
8. **`EditCompositeModelDialog` duplicates `DialogManager`'s `Editable`/`EditDialog` pipeline** because `CustomCompositeModel` doesn't implement `Editable` (shared-models analysis confirms). That's the reason a custom canvas dialog exists at all; making `CustomCompositeModel` expose a proper `getEditInfo` for name+sizeX+sizeY+showLabel+saved would reduce the bespoke code, leaving only the canvas pin-drag as custom.
9. **`SubcircuitDialog` doesn't refresh `MenuManager`** after delete — if a subcircuit is deleted but its menu entry is still in the Components menu, subsequent placement would re-seed the model (or fail). No visible refresh call.
10. **`ControlsDialog` stashes widgets on `cirSim`** (`cirSim.timeStepBar = ...`, `:42`) — global state coupling. If the dialog is re-created, the old scrollbars are orphaned but `cirSim.timeStepBar` points at the new ones; OK in single-dialog-instance practice.
11. **`SearchDialog` early-break at shortcut > 1 char** (`:82-83`) is a fragile heuristic for "top-level item vs nested". A future menu restructuring where a top-level item has a multi-char shortcut would truncate the search corpus silently.
12. **`SlidersDialog.isEmpty()` / `.clear()` expose raw panel state** — caller (`AdjustableManager`) is responsible for keeping its list in sync; no back-pointer or invariant checks.
13. **`EditCompositeModelDialog.saveCheck` state is committed only on enterPressed** (`:263`), but `labelCheck` toggles `model.setShowLabel` immediately (`:202`). Two checkboxes on same dialog with different commit timing.

## Concept boundaries

The ten files split naturally into **three sub-concepts**:

### 1. `dialog-specialized-model-catalog` (3 files)
`EditDiodeModelDialog`, `EditTransistorModelDialog`, `SubcircuitDialog`. All target the **shared-model catalogs** (`shared-models` SCC). The two `EditDialog` subclasses are *thin* augmentations; `SubcircuitDialog` is the composite analog (delete-only). Natural grouping, though `EditCompositeModelDialog` (see below) also touches this catalog.

### 2. `dialog-specialized-composite-layout` (1 file)
`EditCompositeModelDialog`. Large, canvas-based, owns its own data flow end-to-end (build model from simulator snapshot → edit pin layout → commit). Worth its own concept due to size (376 LOC) and the unique `Canvas` interactive-drag widget.

### 3. `dialog-specialized-ui-surfaces` (6 files)
`ControlsDialog`, `ScopePropertiesDialog`, `ScrollValuePopup`, `SearchDialog`, `SliderDialog`, `SlidersDialog`. Unrelated feature surfaces; their only commonality is "Dialog/PopupPanel subclass with hand-rolled UI". Consider keeping as one concept for bounded-context tractability, with internal sub-sections per-dialog.

Alternative single-concept grouping (`dialog-specialized` as one) is defensible because:
- All ten consume `Dialog` or `PopupPanel` base, and most reuse `EditDialog.{unitString, parseUnits}`.
- All ten are leaves in the dialog subsystem (nobody extends them).
- The shared contract is "this is a bespoke UI that doesn't fit `Editable` row polling." That is itself a meaningful architectural category.

**Recommendation:** keep as one concept `dialog-specialized` for this onboarding pass; split into the 3 sub-concepts if/when a concept-spec-plan pipeline needs finer traceability per feature area.
