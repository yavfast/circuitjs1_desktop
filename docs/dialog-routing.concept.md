# Dialog Routing — Single-Slot Active Dialog Router  {#C_DRT}

> **Code:** C_DRT
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard-doc-gen
>
> **Depends on:** C_EIC (dialog-base, pending)
> **Used by:** [C_MEN](./menus-actions.concept.md), [C_EDI](./canvas-editor.concept.md), [C_EIC](./edit-info-contract.concept.md), [C_IEU](./import-export-ui.concept.md), [C_DIN](./dialog-info.concept.md), [C_DSP](./dialog-specialized.concept.md), [C_FBR](./browser-file-bridge.concept.md)
> **Spike:** —
> **Specification:** [SP_DRT](./dialog-routing.sp.md)
> **Plan:** [dialog-routing.plan.md](./dialog-routing.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/layer3__cross-cutting-managers.md` §DialogManager.
>
> A single-slot "currently active dialog" router plus central factory for 19 dialog classes. `activeDialog` is overwritten by every `show*` factory; `dialogIsShowing()` drives background-key suppression in `BaseCirSim`. Non-modal or programmatic dialogs (`AboutBox`, `EditDiodeModelDialog`, `EditTransistorModelDialog`) deliberately bypass the slot.

## 1. Philosophy  {#C_DRT_01}

### 1.1. Core Principle  {#C_DRT_01_01}

Only one tracked dialog is shown at a time; background keyboard handling is suppressed while `activeDialog != null && activeDialog.isShowing()`. The manager is the single factory point so `ActionManager` and element context menus can reach every modal through one handle.

### 1.2. Design Constraints  {#C_DRT_01_02}

- **Single slot, not a stack.** Opening a new dialog overwrites `activeDialog`; the old `DialogBox` stays visible but loses lifecycle tracking.
- **Split calling convention.** Some factories call `show()` internally, others return the dialog unshown; callers must know which pattern applies.
- **Two model-edit dialogs bypass the slot** — `EditDiodeModelDialog` and `EditTransistorModelDialog` — deliberate or bug (the analysis flags it as inconsistent).
- **`AboutBox` is also a bypass** — it is a `PopupPanel` not a `Dialog`, so tracking would filter it anyway.
- **Two access conventions** coexist: `cirSim.dialogManager` (root) and `circuitDocument.getDialogManager()` (element-layer code); both resolve to the same instance.

## 2. Domain Model  {#C_DRT_02}

### 2.1. Key Entities  {#C_DRT_02_01}

```
DialogManager extends BaseCirSimDelegate
  DialogBox activeDialog         -- single slot (package-private)
  -- imports 19 dialog classes (largest fan-in in dialog/)

Tracked (set activeDialog + often call show()):
  HelpDialog, LicenseDialog, ModDialog, ImportFromTextDialog,
  ShortcutsDialog, SubcircuitDialog, SearchDialog, EditOptionsDialog,
  EditElementDialog (generic), SliderDialog, ExportAsUrlDialog,
  ExportAsTextDialog, ExportAsJsonDialog, ExportAsImageDialog,
  EditCompositeModelDialog, ScopePropertiesDialog  (returns to caller)

Bypass (do NOT touch activeDialog):
  AboutBox                    -- PopupPanel, not Dialog
  EditDiodeModelDialog
  EditTransistorModelDialog
```

### 2.2. Data Flows  {#C_DRT_02_02}

```
ActionManager.menuPerformed("options", "shortcuts")
  -> dialogManager.showShortcutsDialog()
       activeDialog = new ShortcutsDialog(cirSim)
       activeDialog.show()                    -- some factories; not all

Element context menu (e.g. DiodeElm)
  -> circuitDocument.getDialogManager().showEditDiodeModelDialog(elm)
       new EditDiodeModelDialog(...).show()   -- bypasses tracking

BaseCirSim background key handler
  IF dialogManager.dialogIsShowing(): suppress key routing

CustomCompositeElm close action
  -> dialogManager.closeDialog()
       IF activeDialog instanceof Dialog: activeDialog.close()
       activeDialog = null

SRAMLoadFile post-load
  -> dialogManager.resetEditDialog()
       IF activeDialog instanceof EditDialog: activeDialog.resetDialog()
```

## 3. Mechanisms  {#C_DRT_03}

### 3.1. Core Algorithm  {#C_DRT_03_01}

**show*Dialog factories.** Each creates the concrete dialog with `(cirSim, …)`-shaped constructor args, assigns to `activeDialog`, and — inconsistently — may call `show()`. `ScopePropertiesDialog` factory returns the instance so `Scope.properties()` can store a reference for `refreshDraw` calls.

**Lifecycle queries.** `dialogIsShowing()` = `activeDialog != null && activeDialog.isShowing()`. `getShowingDialog()` returns only if `activeDialog instanceof Dialog` (project base), filtering out any future `PopupPanel` use. `closeDialog()` narrows to `Dialog` and calls `close()` then nulls the slot.

**Edit-dialog refresh.** `resetEditDialog()` is used by `SRAMLoadFile` after shuttle writes — forces the open `EditDialog` to re-read element state.

### 3.2. Edge Cases  {#C_DRT_03_02}

- Second `show*` call before close silently overwrites the slot; orphaned dialog remains visible but untracked.
- `EditDiodeModelDialog`/`EditTransistorModelDialog` bypass — `dialogIsShowing()` returns false while they are open, so background keys are not suppressed and `closeDialog()` cannot reach them.
- `AboutBox` bypass is intentional (not a `Dialog`).
- Downcasts `cirSim` to `CirSim` in most methods (14×) — redundant boilerplate.

## 4. Integration Points  {#C_DRT_04}

### 4.1. Dependencies  {#C_DRT_04_01}

- **C_EIC (dialog-base)** — `Dialog` / `EditDialog` base classes; `isShowing`, `close`, `resetDialog`.
- **Every dialog concept** (dialog-edit/import/export/info/specialized) — instantiated here.
- **Element layer** — `CircuitDocument.getDialogManager()` used from `DiodeElm`, `TransistorElm`, `CustomCompositeElm`, `CustomLogicElm` context menus.

### 4.2. API Surface  {#C_DRT_04_02}

- `dialogIsShowing() : boolean`.
- `getShowingDialog() : Dialog` (package-private).
- `closeDialog() : void`.
- `resetEditDialog() : void` (package-private).
- 17 `show*Dialog(...)` factories (Help, License, Mod, ImportFromText, Shortcuts, Subcircuit, Search, EditOptions, EditElement, Slider, ExportAsUrl/Text/Json/Image, EditCompositeModel, ScopeProperties, AboutBox).
- Plus two non-tracked factories: `showEditDiodeModelDialog`, `showEditTransistorModelDialog`.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
