# Implementation Plan: Dialog Routing  {#PL_DRT}

> **Code:** PL_DRT
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_DRT](./dialog-routing.concept.md)
> **Specification:** [SP_DRT](./dialog-routing.sp.md)
> **Depends on plans:** PL_EIC
> **Used by plans:** PL_MEN, PL_EDI, PL_DIE, PL_DIM, PL_DIX, PL_DIN, PL_DSP, PL_FBR

## Goal

Provide the central factory + lifecycle tracker for application modal dialogs so `ActionManager`, element context menus, and external glue (SRAMLoadFile, CustomCompositeElm) have one access point.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Tracker shape | single-slot field | simplest; one visible modal at a time |
| Factory location | 17 `show*Dialog` methods | direct discoverability in one file |
| Show convention | split (internal vs external) | legacy; document per-factory |
| Access paths | `cirSim.dialogManager` AND `circuitDocument.getDialogManager()` | dual fan-in for shell + element layer |

## Progress

- [x] Phase 1 — DialogManager class + activeDialog slot
- [x] Phase 2 — 16 tracked show factories (Help/License/Mod/ImportText/Shortcuts/Subcircuit/Search/EditOptions/EditElement/Slider/Export*×4/EditCompositeModel/ScopeProperties)
- [x] Phase 3 — Bypass factories (AboutBox, EditDiodeModel, EditTransistorModel)
- [x] Phase 4 — Lifecycle queries (dialogIsShowing, getShowingDialog, closeDialog, resetEditDialog)

## Backlog

From `.dev_flow/onboard/analysis/layer3__cross-cutting-managers.md` §DialogManager Issues:

- **Inconsistent `show()` calling convention** — some factories call internally, others return un-shown.
- **Edit-model dialogs not tracked** — `dialogIsShowing()`/`closeDialog()` can't see them; background key suppression fails.
- **Single-slot overwrite** — opening second dialog silently orphans the first.
- **19-class fan-in** — largest single-file importer of `client.dialog.*`; a registry pattern would cut imports.
- **`(CirSim) this.cirSim` downcast repeated 14×** — generic-in-owner-type would fix.
- **`showAboutBox` outlier** — consistent with `dialog-info` issue #1 (AboutBox doesn't extend Dialog).

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
