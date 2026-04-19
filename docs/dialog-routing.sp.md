# Dialog Routing — Specification  {#SP_DRT}

> **Code:** SP_DRT
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_DRT](./dialog-routing.concept.md)
> **Depends on specs:** SP_EIC
> **Used by specs:** [SP_MEN](./menus-actions.sp.md), [SP_EDI](./canvas-editor.sp.md), [SP_EIC](./edit-info-contract.sp.md), [SP_IEU](./import-export-ui.sp.md), [SP_DIN](./dialog-info.sp.md), [SP_DSP](./dialog-specialized.sp.md), [SP_FBR](./browser-file-bridge.sp.md)
> **Plan:** [dialog-routing.plan.md](./dialog-routing.plan.md)

## 01. Data Structures  {#SP_DRT_01}

### 01_01. DialogManager state  {#SP_DRT_01_01}

| Field | Type | Visibility | Default | Description |
|-------|------|------------|---------|-------------|
| activeDialog | DialogBox | package | null | Single-slot tracker; overwritten on every tracked show. |

### 01_02. Dialog classification  {#SP_DRT_01_02}

Tracked (assign + some call `show()`):

| Factory | show() internal? | Returns dialog? |
|---------|------------------|-----------------|
| showHelpDialog | no | — |
| showLicenseDialog | no | — |
| showModDialog | no | — |
| showImportFromTextDialog | no | — |
| showShortcutsDialog | yes | — |
| showSubcircuitDialog | yes | — |
| showSearchDialog | yes | — |
| showEditOptionsDialog | yes | — |
| showEditElementDialog | yes | — |
| showSliderDialog | yes | — |
| showExportAsUrlDialog | yes | — |
| showExportAsTextDialog | yes | — |
| showExportAsJsonDialog | yes | — |
| showExportAsImageDialog | yes | — |
| showEditCompositeModelDialog | yes | — |
| showScopePropertiesDialog | no | yes (for `Scope.refreshDraw`) |

Bypass (do not assign `activeDialog`):

| Factory | Reason |
|---------|--------|
| showAboutBox | `PopupPanel`, not `Dialog` |
| showEditDiodeModelDialog | inconsistency (analysis flags as bug) |
| showEditTransistorModelDialog | inconsistency |

## 02. Contracts  {#SP_DRT_02}

### 02_01. Tracked show factory pattern  {#SP_DRT_02_01}

    FUNCTION showXxxDialog(args):
        activeDialog = new XxxDialog(cirSim, args)
        IF factory calls show internally: activeDialog.show()

### 02_02. Lifecycle queries  {#SP_DRT_02_02}

    FUNCTION dialogIsShowing():
        RETURN activeDialog != null AND activeDialog.isShowing()

    FUNCTION getShowingDialog():
        IF activeDialog instanceof Dialog: RETURN (Dialog) activeDialog
        RETURN null

    FUNCTION closeDialog():
        IF activeDialog instanceof Dialog: ((Dialog) activeDialog).close()
        activeDialog = null

    FUNCTION resetEditDialog():
        IF activeDialog instanceof EditDialog: ((EditDialog) activeDialog).resetDialog()

## 03. Validation Rules  {#SP_DRT_03}

- `activeDialog` is package-private; external code must not assign it.
- Factories for `EditDiodeModel`/`EditTransistorModel`/`AboutBox` must not assign the slot.
- `closeDialog()` is safe to call even when slot is null or not a `Dialog`.

## 04. State Transitions  {#SP_DRT_04}

| From | To | Trigger | Side effect |
|------|----|---------|-------------|
| null | D1 | showXxxDialog (tracked) | activeDialog = D1 |
| D1 | D2 | showYyyDialog while D1 open | D1 orphaned (still showing), activeDialog=D2 |
| D1 | null | closeDialog | D1.close(); slot cleared |
| * | * | showEditDiodeModelDialog | bypass; slot unchanged |

## 05. Verification Criteria  {#SP_DRT_05}

### 05_01. Functional  {#SP_DRT_05_01}

| Contract | Scenario | Input | Expected |
|----------|----------|-------|----------|
| showShortcutsDialog | normal | — | dialog visible; activeDialog set |
| dialogIsShowing | nothing open | — | false |
| dialogIsShowing | tracked dialog open | — | true |
| dialogIsShowing | only diode-model dialog open | — | false (bypass) |
| closeDialog | null slot | — | no-op |
| resetEditDialog | EditElementDialog active | — | dialog.resetDialog() called |

### 05_02. Invariants  {#SP_DRT_05_02}

| Invariant | Verification |
|-----------|--------------|
| at most one tracked dialog at a time | opening second orphans the first |
| bypass factories never touch the slot | grep `showAboutBox`/`showEditDiodeModelDialog` |

### 05_03. Edge Cases  {#SP_DRT_05_03}

| Case | Input | Expected |
|------|-------|----------|
| second show overwrites | open Help, then Shortcuts | Help untracked; Shortcuts tracked |
| background key during diode-model dialog | press `r` | not suppressed (bypass side effect) |
| closeDialog on AboutBox | — | no-op (bypass) |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
