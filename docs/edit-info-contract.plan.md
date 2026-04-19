# Implementation Plan: Edit-Info Contract  {#PL_EIC}

> **Code:** PL_EIC
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_EIC](./edit-info-contract.concept.md)
> **Specification:** [SP_EIC](./edit-info-contract.sp.md)
> **Depends on plans:** [PL_UTL](./util-locale-log.plan.md), [PL_LUW](./legacy-ui-wrappers.plan.md), [PL_ELB](./element-base.plan.md)
> **Used by plans:** — (will be filled by higher layers)
>
> Reverse-engineered plan for the 6 files comprising the edit-info contract + generic renderer. Implementation is complete; this document records the delivered structure and backlog extracted from the analyses.

## Goal

Provide the single editable-parameter contract every element, named model, and global-options object implements, plus a generic dialog renderer that walks the contract and produces live GWT widgets with SI-prefix formatting, slider sync, and rebuild-on-change.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Contract surface | 2-method marker interface `Editable` | Minimal commitment; any class can expose parameters. |
| Row DTO | Field-bag `EditInfo` with null-probed kind | No Kind enum — keeps DTO tiny; precedence lives in renderer. |
| Terminator | `getEditInfo(n) == null` sentinel | Avoids separate row-count query. |
| Change model | Immediate-commit for Choice/Checkbox/Button; deferred for TextBox | Matches browser widget UX. |
| Rebuild trigger | `ei.newDialog` flag set in `setEditValue` | One-shot; cleared on `clearDialog`. |
| Row cache | fixed `EditInfo[10]` | Historical; covers all current Editables. |
| SI parser | `parseUnits` module-public | Reused by Slider/Scope dialogs. |

## Progress

- [x] Phase 1 — `Dialog` base (position/collapse/anchor/resize)
- [x] Phase 2 — `Editable` interface + `EditInfo` DTO
- [x] Phase 3 — `EditDialog` generic renderer
- [x] Phase 4 — `EditOptions` global-settings data source
- [x] Phase 5 — `EditDialogLoadFile` file-picker helper

## Phases

### Phase 1 — Dialog base (`client/dialog/Dialog.java`) [DONE]

**Implements:** [SP_EIC_01_01](./edit-info-contract.sp.md#SP_EIC_01_01), [SP_EIC_02_06](./edit-info-contract.sp.md#SP_EIC_02_06)

Delivered: position persistence (`save/loadPosition`), viewport clamp, anchor derivation on drag-end, global resize re-anchor via `showingDialogs` registry, collapse toggle with lazy-injected "-/+" span, Enter-to-apply hook, `getOptionPrefix` opt-in persistence.

### Phase 2 — Editable + EditInfo (`client/dialog/{Editable,EditInfo}.java`) [DONE]

**Implements:** [SP_EIC_01_02](./edit-info-contract.sp.md#SP_EIC_01_02), [SP_EIC_01_03](./edit-info-contract.sp.md#SP_EIC_01_03), [SP_EIC_02_01](./edit-info-contract.sp.md#SP_EIC_02_01), [SP_EIC_02_05](./edit-info-contract.sp.md#SP_EIC_02_05)

Delivered: 2-method interface, 14+ field DTO with 4 constructors + 7 fluent/static factories, full widget-kind catalog, `canCreateAdjustable` gating.

### Phase 3 — EditDialog renderer (`client/dialog/EditDialog.java`) [DONE]

**Implements:** [SP_EIC_01_04](./edit-info-contract.sp.md#SP_EIC_01_04), [SP_EIC_02_02](./edit-info-contract.sp.md#SP_EIC_02_02), [SP_EIC_02_03](./edit-info-contract.sp.md#SP_EIC_02_03), [SP_EIC_02_04](./edit-info-contract.sp.md#SP_EIC_02_04), [SP_EIC_04](./edit-info-contract.sp.md#SP_EIC_04)

Delivered: `buildDialog` widget synthesis with precedence chain, `apply` with SI parse + slider sync, `itemStateChanged` rebuild-on-`newDialog`, `parseUnits/unitString` SI codec, 15-widget column wrap, Apply/OK/Cancel buttons, `SimulationContextAware` injection.

### Phase 4 — EditOptions (`client/dialog/EditOptions.java`) [DONE]

**Implements:** [SP_EIC_01_05](./edit-info-contract.sp.md#SP_EIC_01_05)

Delivered: 14 rows covering color range + language + 5 palette colors + 2 decimal-digit settings + developer mode + 3 simulator settings + conditional min-timestep; direct-writes to `ColorSettings`/`DisplaySettings`/`OptionsManager`/`simulator`/`circuitEditor`; language change forces page reload.

### Phase 5 — EditDialogLoadFile (`client/dialog/EditDialogLoadFile.java`) [DONE]

**Implements:** [SP_EIC_01_06](./edit-info-contract.sp.md#SP_EIC_01_06)

Delivered: off-screen hidden `FileUpload`, JSNI `.click()` synthetic open, `ChangeHandler → handle()` hook, `isSupported`/`doErrorCallback` statics. Sole in-tree subclass: `SRAMLoadFile`.

## Backlog

Items deferred from current cycle (from `dialog-base.md` §Issues and `dialog-edit.md` §Issues):

- **`EditInfo` is a field-bag with no Kind enum (dialog-base #1).** Rendering dispatch is an `if`-cascade spread across every consumer; a sealed `Kind` union would localise it.
- **Bidirectional `element → dialog` coupling (dialog-base #2; mirrors element-base #2, issues.md #6).** `Editable` lives in `dialog/` but is implemented by `CircuitElm` and non-dialog classes. A neutral `contract/` package would invert the dependency.
- **Mutation-in-place via widget aliasing (dialog-base #3).** `EditInfo.textf/choice/…` are public widgets; `EditInfo` is live only while the dialog is; no post-close inspection.
- **`findCaptionElement()` substring match (dialog-base #4).** Brittle against GWT version renames of `"Caption"` CSS class.
- **`hide(boolean)` registry-leak assumption (dialog-base #5).** Relies on GWT funnelling all hides through the overload.
- **`savePosition` plain-string storage (dialog-base #6).** No versioning; any format change silently loses position.
- **`createCheckbox` asymmetry (dialog-base #7).** Label lives on the `Checkbox` itself, not `EditInfo.name`.
- **`getEditInfo(n)==null` terminator (dialog-base #8).** Every rebuild is O(N) with fresh allocations, even when schema unchanged.
- **`canCreateAdjustable` hard-coded predicate (dialog-base #9).** New kinds may silently become sliderable.
- **Enter-vs-TextArea conflict (dialog-base #10).** `closeOnEnter=true` default; TextArea dialogs must manually flip.
- **Fixed `einfos[10]` cap (dialog-edit #1).** `EditOptions` at 14 rows is at risk; relies on row numbering gaps.
- **Cancel is not an undo (dialog-edit #2).** Choice/Checkbox/Button commit immediately inside `itemStateChanged`.
- **Silent parse failure (dialog-edit #3).** `ParseException` in `apply()` is swallowed — no user feedback.
- **Variable-arg label styling dead code (dialog-edit #4).** `if (i != 0 && l != null)` — `l` is always non-null.
- **Column-wrap threshold = 15 hard-coded (dialog-edit #5).**
- **`closeOnEnter=false` is sticky (dialog-edit #6).** Not reset on rebuild.
- **`EditDialog` imports `VoltageElm` for RMS special case (dialog-edit #7).** Leaks element-specific formatting into generic dialog; should move to `EditInfo`.
- **`EditOptions` direct-writes to singletons (dialog-edit #8).** No apply/cancel semantics; no undo.
- **Language change forces page reload (dialog-edit #9).**
- **`EditDialogLoadFile` uses shared global DOM id (dialog-edit #10).** Two loadFile rows in one dialog would collide.
- **Adjustable-coupling via cast (dialog-edit #11).** `elm instanceof CircuitElm` inside generic dialog is implicit coupling.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
