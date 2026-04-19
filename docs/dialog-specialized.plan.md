# Implementation Plan: Specialized Dialogs  {#PL_DSP}

> **Code:** PL_DSP
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_DSP](./dialog-specialized.concept.md)
> **Specification:** [SP_DSP](./dialog-specialized.sp.md)
> **Depends on plans:** [PL_EIC](./edit-info-contract.plan.md), PL_SHM (shared-models), PL_ADJ (adjustable-sliders, pending), PL_SCP (scope-viz, pending)
> **Used by plans:** — (will be filled by higher layers)
>
> Reverse-engineered plan for the 10 bespoke dialogs. Implementation is complete.

## Goal

Deliver the specialised dialogs whose data model, widget layout, or commit semantics don't fit `Editable` row polling — covering app controls, subcircuit model editing, model-catalog edits, scope configuration, inline scroll-value editing, menu search, per-element adjustable editing, slider panel, and subcircuit catalog management.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Integration patterns | 3 (EditDialog subclass, hand-rolled, PopupPanel) | Matches three disparate UX needs. |
| Canvas-based pin layout | HTML5 `Canvas` via `Graphics(context)` | Direct manual control of pin drag. |
| Log-scale timestep | Fixed table `TIME_STEP_VALUES[22]` + O(n) log scan | Avoids float-precision drift. |
| E12 preferred values | Fixed `e12[12]` × decades + insertion-sort | User-familiar value series. |
| Scope menu command wrap | `ScopeCheckBox` | Persists state both as widget and as menu cmd. |
| SI codec reuse | `EditDialog.parseUnits`/`unitString` | Module-public; canonical. |
| SliderDialog row hijack | Reuse `EditInfo.{checkbox,choice,minBox,maxBox,labelBox}` | Scaffolds without duplicating fields. |

## Progress

- [x] Phase 1 — ControlsDialog
- [x] Phase 2 — EditCompositeModelDialog
- [x] Phase 3 — EditDiodeModelDialog
- [x] Phase 4 — EditTransistorModelDialog
- [x] Phase 5 — ScopePropertiesDialog
- [x] Phase 6 — ScrollValuePopup
- [x] Phase 7 — SearchDialog
- [x] Phase 8 — SliderDialog
- [x] Phase 9 — SlidersDialog
- [x] Phase 10 — SubcircuitDialog

## Phases

### Phase 1 — ControlsDialog [DONE]

Delivered: 22-entry log-scale `TIME_STEP_VALUES`, 4 Scrollbars (timestep, sim speed, current speed, power brightness), shared registry on `cirSim`, position/collapse via `getOptionPrefix="ControlsDialog"`.

### Phase 2 — EditCompositeModelDialog [DONE]

**Implements:** [SP_DSP_02_02](./dialog-specialized.sp.md#SP_DSP_02_02)

Delivered: `createModel` (from simulator snapshot) + `setModel(m)` entry modes, validation (pin-on-same-node / ≥1 ext pin), auto sizeX/Y, HTML5 `Canvas` 400×400 with pin drag swap, width/height +/- buttons, `simulator.updateModels + needAnalyze` commit, `CustomCompositeElm.lastModelName` side-effect.

### Phase 3 — EditDiodeModelDialog [DONE]

**Implements:** [SP_DSP_02_01](./dialog-specialized.sp.md#SP_DSP_02_01)

Delivered: minimal `EditDialog` subclass; `applyButton.removeFromParent()`; `apply()` override calls `model.pickName()` if unnamed + `elm.newModelCreated(model)`.

### Phase 4 — EditTransistorModelDialog [DONE]

Delivered: same shape as Diode dialog; `pickName()` call is commented out (known-issue — see Backlog).

### Phase 5 — ScopePropertiesDialog [DONE]

**Implements:** [SP_DSP_02_06](./dialog-specialized.sp.md#SP_DSP_02_06)

Delivered: 4-section `Grid` with `expandingLabel` collapsers, `ScopeCheckBox` wrapping menu commands, adaptive small-screen layout (`displayAll`/`displayScales` from `Window.getClientHeight()`), transistor branch variant, live `refreshDraw`, auto-apply `closeDialog` override, reuses `EditDialog.parseUnits/unitString`. 970 LOC.

### Phase 6 — ScrollValuePopup [DONE]

**Implements:** [SP_DSP_02_05](./dialog-specialized.sp.md#SP_DSP_02_05)

Delivered: `PopupPanel` escape hatch; E12 × decades sorted values table with current-value insertion; 5-slot rolling label strip; mouse-wheel `doDeltaY` with `wheelSensitivity`; pushUndo on open; left/middle-click keep, right-click revert, mouse-out keep.

### Phase 7 — SearchDialog [DONE]

Delivered: TextBox (maxLength 15) + ListBox (10 visible); `KeyUpHandler` live lowercase substring filter over `mainMenuItems`; double-click / OK executes the menu item's `ScheduledCommand`; early-break at first multi-char shortcut.

### Phase 8 — SliderDialog [DONE]

**Implements:** [SP_DSP_02_04](./dialog-specialized.sp.md#SP_DSP_02_04)

Delivered: per-element Adjustable editor; walks `getEditInfo(i)` skipping `!canCreateAdjustable`; hijacks `ei.checkbox/choice/minBox/maxBox/labelBox`; share-slider Choice + `adj.sharedSlider`; rebuild-on-change via `itemStateChanged`.

### Phase 9 — SlidersDialog [DONE]

Delivered: passive `VerticalPanel` container fed by `AdjustableManager.addSlider/removeSlider`; rows (title, value labels, `Scrollbar`, Edit-Adjustable + Edit-Element buttons); CSS `overflowY:auto`; fallback position right-of-Controls; persistence via `getOptionPrefix="SlidersDialog"`.

### Phase 10 — SubcircuitDialog [DONE]

Delivered: `ListBox` (5 visible) of non-builtin `CustomCompositeModel.getModelList()`; Delete (with `Window.confirm`) + Done; modal `setGlassEnabled(true)`; no `getOptionPrefix` (no position persistence).

## Backlog

Items deferred from current cycle (from `dialog-specialized.md` §Issues):

- **`EditTransistorModelDialog` missing `pickName()` (#1).** Commented-out call at `:21-22`; unnamed transistor models edited here are left with `name==null` → silent catalog-map corruption. Asymmetric with Diode path.
- **`SliderDialog.einfos[10]` fixed array (#2).** Inherits the `EditDialog` hard cap; any element with >10 adjustable-eligible rows would AIOOBE.
- **`SliderDialog` silent try/catch on parse (#3).** Logs to console; no user feedback.
- **`ScrollValuePopup` undo semantics fragile (#4).** Pushes undo only on open; `close(false)` revert re-calls `setEditValue` without a second undo entry; midway snapshots not supported.
- **`ScrollValuePopup` assumes `getEditInfo(0)` is numeric (#5).** NPE on Choice/Checkbox; protected only by caller gating (R/L/C right-click).
- **`ScopePropertiesDialog.closeDialog` auto-applies (#6).** Inconsistent with every other dialog; titlebar X is treated as Save.
- **`ScopePropertiesDialog` 970 LOC god-dialog (#7).** `expandingLabel`, `labelledGridManager` + 30+ inner classes should be extracted.
- **`EditCompositeModelDialog` duplicates Editable pipeline (#8).** Because `CustomCompositeModel` doesn't implement `Editable`. Making it so would reduce bespoke code to canvas pin-drag only.
- **`SubcircuitDialog` doesn't refresh MenuManager (#9).** After delete, Components menu may still contain the subcircuit — re-seeds or fails on placement.
- **`ControlsDialog` stashes widgets on `cirSim` (#10).** Global state coupling; re-creation orphans old scrollbars.
- **`SearchDialog` early-break at shortcut > 1 char (#11).** Fragile heuristic for "top-level vs submenu" boundary.
- **`SlidersDialog.isEmpty()/clear()` expose raw panel state (#12).** Caller (`AdjustableManager`) must keep list in sync; no invariant checks.
- **`EditCompositeModelDialog` split commit timing (#13).** `labelCheck` → `model.setShowLabel` immediately; `saveCheck` committed only on enterPressed. Two checkboxes on same dialog with different commit semantics.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
