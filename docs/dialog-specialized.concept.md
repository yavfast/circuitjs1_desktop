# Specialized Dialogs — Bespoke UI Surfaces  {#C_DSP}

> **Code:** C_DSP
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard-doc-gen
>
> **Depends on:** [C_EIC](./edit-info-contract.concept.md), C_SHM (shared-models), C_ADJ (adjustable-sliders, pending), C_SCP (scope-viz, pending)
> **Used by:** — (will be filled by higher layers)
> **Spike:** —
> **Specification:** [SP_DSP](./dialog-specialized.sp.md)
> **Plan:** [dialog-specialized.plan.md](./dialog-specialized.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/domain-core__dialog-specialized.md` (10 files in `client/dialog/`).
>
> The ten dialogs whose data model, widget layout, or apply/cancel semantics do not fit the generic `EditInfo`-row pipeline — or that augment `EditDialog` with post-apply hooks to keep shared-model catalogs consistent. Covers ControlsDialog, EditCompositeModelDialog, EditDiodeModelDialog, EditTransistorModelDialog, ScopePropertiesDialog, ScrollValuePopup, SearchDialog, SliderDialog, SlidersDialog, SubcircuitDialog.

## 1. Philosophy  {#C_DSP_01}

### 1.1. Core Principle  {#C_DSP_01_01}

When a dialog's data source, widget topology, or commit semantics are incompatible with `Editable`'s numbered-row polling, CircuitJS1 drops the generic pipeline and hand-builds the widget tree. This concept catalogs the three integration patterns this produces — (1) thin subclasses of `EditDialog` with a post-apply hook, (2) hand-rolled `Dialog` subclasses with canvas/grid/scrollbar UIs, (3) `PopupPanel` escape hatch for inline controls — and documents per-dialog data sources and sinks.

### 1.2. Design Constraints  {#C_DSP_01_02}

- **Three integration patterns, one concept.** Model-catalog subclasses (Diode/Transistor) add a `pickName + newModelCreated` post-apply; bespoke `Dialog` subclasses (Composite, Scope, Controls, Sliders(Dialog), Subcircuit, Search) own their widget tree; `ScrollValuePopup` escapes to `PopupPanel` for inline mouse-wheel editing without position/collapse persistence.
- **Reuse of `EditDialog` utilities.** `parseUnits` / `unitString` are module-public and used across Scope/Slider/Composite dialogs; the SI codec is the canonical parser for the whole dialog subsystem.
- **Persistence targets split four ways.** Element field (via `Editable`), `Scope` display state, `AdjustableManager` list + `Adjustable` fields, `CustomCompositeModel` + browser local-storage, `simulator.maxTimeStep` direct field writes, and `MenuManager.mainMenuItems` commands.
- **Non-undo-tracked by default.** Only `ScrollValuePopup` pushes undo; model-edit and composite dialogs do not — a latent Cancel-isn't-undo footgun.

## 2. Domain Model  {#C_DSP_02}

### 2.1. Key Entities  {#C_DSP_02_01}

```
dialog/
  Pattern 1 — EditDialog subclass + post-apply hook
    EditDiodeModelDialog          — target DiodeModel; + model.pickName + newModelCreated
    EditTransistorModelDialog     — target TransistorModel; + newModelCreated (pickName commented out)

  Pattern 2 — Hand-rolled Dialog subclass
    ControlsDialog                — log-scale timestep + 3 scrollbars; stashes widgets on CirSim
    EditCompositeModelDialog      — Canvas interactive pin drag; builds CustomCompositeModel
    ScopePropertiesDialog         — 4-section Grid + 30+ widgets (970 LOC god-dialog)
    SearchDialog                  — substring filter over mainMenuItems; ListBox
    SliderDialog                  — per-element Adjustable editor; hijacks EditInfo extra fields
    SlidersDialog                 — passive Scrollbar container; fed by AdjustableManager
    SubcircuitDialog              — ListBox of CustomCompositeModel; delete only

  Pattern 3 — PopupPanel escape hatch
    ScrollValuePopup              — mouse-wheel inline R/L/C editor with E12 preferred-value table
```

### 2.2. Data Flows  {#C_DSP_02_02}

**Model-catalog dialogs (Diode / Transistor):**
```
element.getEditInfo → "Create/Edit Model" button (ei.newDialog=true)
  → EditDialog.itemStateChanged → spawns Edit{Diode,Transistor}ModelDialog
  → inherits EditDialog row-polling on the model itself (model implements Editable)
  → applyButton.removeFromParent (Apply stripped)
  → OK: super.apply + model.pickName (if diode + unnamed)
       + elm.newModelCreated(model) (force rebind via getModelWithNameOrCopy)
```

**EditCompositeModelDialog (create path):**
```
"Create Subcircuit" menu → DialogManager.showEditCompositeModelDialog
  → simulator.getCircuitAsComposite() → new CustomCompositeModel
  → validate (no pin-on-same-node, ≥1 ext pin) + auto sort extList + auto sizeX/Y
  → createDialog: Canvas 400×400 + TextBox name + show-label/save checkboxes + w/h buttons
  → mouseDrag: swap pin positions within extList; rebuild chip from model
  → enterPressed: setName + setSaved + simulator.updateModels + needAnalyze
                  + CustomCompositeElm.lastModelName = name
```

**ControlsDialog:**
```
TIME_STEP_VALUES[22] (1pS..10µS; 1/2/5 per decade)
  → Scrollbar position ↔ simulator.maxTimeStep (log-scale)
  → allocates cirSim.timeStepBar, speedBar, currentBar, powerBar (shared registry)
```

**ScopePropertiesDialog:**
```
scope → populate Grid (25-27 × 3) with ScopeCheckBox (wraps menu cmd string)
  → on change: scope.handleMenu(menuCmd, state) + persists to menu registry
  → live refreshDraw() every sim step refreshes manual-scale UI (skips in manual mode)
  → closeDialog auto-applies (inconsistent with sibling dialogs)
```

**ScrollValuePopup:**
```
right-click R/L/C → ScrollValuePopup(elm)
  → pushUndo (on open)
  → e12[]×decades + current value → sorted values[]
  → 5-slot rolling label strip; mouse-wheel moves selection
  → setElmValue: ei.value = values[i]; setEditValue(0, ei); needAnalyze
  → close(true) keep | close(false) revert
```

**SliderDialog:**
```
element right-click → SliderDialog(elm, sim)
  → walks elm.getEditInfo(i), skips !ei.canCreateAdjustable
  → hijacks ei.{checkbox,choice,minBox,maxBox,labelBox} for its own widgets
  → apply: adjustableManager.adjustables ±=; adj.{min,max,sliderText,sharedSlider}
```

**SearchDialog / SubcircuitDialog / SlidersDialog:** fuzzy-filter menu items → execute `ScheduledCommand`; composite-model list → `Window.confirm` + `model.remove`; passive container fed by `AdjustableManager.addSlider/removeSlider`.

## 3. Mechanisms  {#C_DSP_03}

### 3.1. Core Algorithm  {#C_DSP_03_01}

**Pattern 1 (model-catalog) post-apply hook:** `EditDialog.apply()` runs the normal row-polling commit; the subclass overrides `apply` to additionally call `model.pickName()` when the model is still unnamed (diode only) and `elm.newModelCreated(model)` to force the element to rebind via `getModelWithNameOrCopy`. Apply button is removed from parent in the ctor — these dialogs are terminal.

**Pattern 2 (ScopePropertiesDialog) adaptive layout:** on ctor, reads `Window.getClientHeight()`; computes `displayAll = h>600`, `displayScales = h>470`; conditionally hides/shows sections via an inner `labelledGridManager`. Transistor-scope branch replaces voltage/current checkboxes with Ib/Ic/Ie/Vbe/Vbc/Vce + Vce-vs-Ic XY plot. `ScopeCheckBox` wraps a menu-command string so toggles both persist to menu state and fire `scope.handleMenu`.

**Pattern 2 (EditCompositeModelDialog) canvas interaction:** `CustomCompositeChipElm` placed at `(50,50)-(200,50)`; `Graphics(context)` draws via renderer with bbox-derived scale; `onMouseMove` finds overlapping pin and swaps pin positions in `extList`; chip rebuilt from model on every motion.

**Pattern 3 (ScrollValuePopup) E12 preferred-value table:** 12-entry e12 array × decades (R: 1e-1..1e7; C: 1e-11..1e-3; L: 1e-6..1e0) + insertion-sort of current value if not already in table; mouse-wheel `doDeltaY` moves selection; 5-slot rolling label strip (2 off, 1 off, selected, 1 off, 2 off) gives haptic feel.

**Shared SI codec use:** `ScopePropertiesDialog` and `SliderDialog` import `EditDialog.parseUnits` / `unitString` as static utilities. Confirms the parser is module-public.

### 3.2. Edge Cases  {#C_DSP_03_02}

- **`EditTransistorModelDialog` missing `pickName`** — unnamed transistor model edited through this dialog is left with `name==null`; silent catalog-map corruption.
- **`SliderDialog.einfos[10]` fixed array** — same cap as `EditDialog`; elements with >10 adjustable-eligible rows OOB.
- **`ScrollValuePopup` undo semantics fragile** — pushes on open; `close(false)` path reverts via a second `setEditValue` without a corresponding undo entry.
- **`ScrollValuePopup` assumes `getEditInfo(0)` is numeric** — will NPE on choice/checkbox rows; caller-guarded (only on R/L/C right-click).
- **`ScopePropertiesDialog.closeDialog` auto-applies** — inconsistent with other dialogs; title-bar X treated as Save.
- **`ScopePropertiesDialog` is 970 LOC** — god-dialog; 30+ inner classes.
- **`EditCompositeModelDialog` duplicates `Editable` pipeline** — because `CustomCompositeModel` doesn't implement `Editable`; making it would reduce bespoke code.
- **`SubcircuitDialog` doesn't refresh MenuManager** after delete — placement may re-seed model or fail.
- **`ControlsDialog` stashes widgets on `cirSim`** — global state; re-creation orphans old scrollbars.
- **`SearchDialog` early-break at shortcut > 1 char** — fragile heuristic for top-level boundary.
- **`SlidersDialog` state exposed raw** — AdjustableManager must keep its list in sync; no invariant checks.
- **`EditCompositeModelDialog` split commit timing** — `labelCheck` toggles `model.setShowLabel` immediately; `saveCheck` commits only on enterPressed.

## 4. Integration Points  {#C_DSP_04}

### 4.1. Dependencies  {#C_DSP_04_01}

- **[C_EIC](./edit-info-contract.concept.md)** — all 10 dialogs depend on `dialog.Dialog` or `EditDialog`; `parseUnits`/`unitString` reused across 3 dialogs.
- **C_SHM (shared-models)** — `DiodeModel`, `TransistorModel`, `CustomCompositeModel` are data sinks for Diode/Transistor/Composite dialogs; `SubcircuitDialog` lists the composite catalog.
- **C_ADJ (adjustable-sliders, pending)** — `AdjustableManager.findAdjustable/adjustables/reorderAdjustables`; `Adjustable.{min,max,sliderText,sharedSlider,createSlider,deleteSlider}`. `SliderDialog` + `SlidersDialog` are the UI surfaces.
- **C_SCP (scope-viz, pending)** — `Scope`, `ScopeCheckBox`, `setManualScale/Position/TriggerLevel/HistoryDepth`, `handleMenu`. `ScopePropertiesDialog` is the dedicated editor.
- **Simulator core** — `ControlsDialog` writes `simulator.maxTimeStep/timeStep`; `EditCompositeModelDialog` calls `simulator.updateModels + needAnalyze`; `ScrollValuePopup` calls `needAnalyze` after setElmValue.
- **`MenuManager`** — `SearchDialog` filters `mainMenuItems` then executes `ScheduledCommand`.

### 4.2. API Surface  {#C_DSP_04_02}

- `DialogManager.show{Controls,SlidersDialog}()` — toolbar / startup factories.
- `DialogManager.showEditCompositeModelDialog(CustomCompositeModel)` — Create Subcircuit / double-click.
- `DiodeElm.getEditInfo` button → `EditDiodeModelDialog`; analogous for Transistor.
- Scope right-click → `ScopePropertiesDialog(cirSim, scope)`.
- R/L/C right-click → `ScrollValuePopup(elm)`.
- Ctrl+F / menu "Find" → `SearchDialog`.
- Element right-click → `SliderDialog(elm, sim)`.
- `AdjustableManager` lifecycle → `SlidersDialog.addSlider/removeSlider`.
- `ControlsDialog.updateTimeStepBar()` — external refresh when simulator adapts timestep.
- `EditCompositeModelDialog.createModel` / `setModel(m)` — two entry modes.
- Reuse: `EditDialog.parseUnits(String)` and `EditDialog.unitString(EditInfo, double)` are module-public utilities consumed by `ScopePropertiesDialog` and `SliderDialog`.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
