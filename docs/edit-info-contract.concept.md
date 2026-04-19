# Edit-Info Contract — Universal Parameter-Editing Pipeline  {#C_EIC}

> **Code:** C_EIC
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard-doc-gen
>
> **Depends on:** [C_UTL](./util-locale-log.concept.md), [C_GEO](./geometry.concept.md), [C_RND](./rendering-primitives.concept.md), [C_LUW](./legacy-ui-wrappers.concept.md), [C_ELB](./element-base.concept.md)
> **Used by:** — (will be filled by higher layers)
> **Spike:** —
> **Specification:** [SP_EIC](./edit-info-contract.sp.md)
> **Plan:** [edit-info-contract.plan.md](./edit-info-contract.plan.md)
>
> Backing analyses: `.dev_flow/onboard/analysis/domain-core__dialog-base.md` (3 files in `client/dialog/`) + `.dev_flow/onboard/analysis/domain-core__dialog-edit.md` (3 files). 6 files total; 771 `EditInfo` references across 125 files — the most-used DTO in the codebase.
>
> The load-bearing contract through which *every* editable parameter in CircuitJS1 flows — elements, named models, global options, adjustables. It is built from three tiny cores (`Dialog` base widget, `Editable` marker, `EditInfo` field-bag) and one generic renderer (`EditDialog`) that turns the contract into live GWT widgets.

## 1. Philosophy  {#C_EIC_01}

### 1.1. Core Principle  {#C_EIC_01_01}

An *editable parameter* is described once, by its owner (element, model, options), as a numbered sequence of `EditInfo` rows; the dialog layer is a generic renderer that walks the sequence, synthesises widgets, collects the user's input, and pushes each mutated row back through the same index. The contract decouples data ownership (elements and models) from UI concerns (widget synthesis, layout, validation), so ~135 element classes, 3 shared-model types, and 1 global-options object all plug into *one* dialog.

### 1.2. Design Constraints  {#C_EIC_01_02}

- **Sentinel-terminated iteration.** `getEditInfo(n)` returns `null` for the first unused index; there is no row-count query. Every dialog rebuild re-walks from n=0.
- **Identity-preserved round-trip.** The `EditInfo` passed to `setEditValue(n, ei)` is the *same object* the owner returned from `getEditInfo(n)`, mutated in place by the dialog.
- **No Kind enum.** The row's widget type is inferred by the first non-null field in a fixed precedence chain (choice → checkbox → button → textArea → widget → textbox). Adding a new kind requires an edit in every consumer.
- **Rebuild-on-change.** When a row's commit changes the schema shape (e.g. waveform type switches the parameter set), the owner sets `ei.newDialog = true` inside `setEditValue`; the dialog then performs `clearDialog → buildDialog` to re-query the whole list.

## 2. Domain Model  {#C_EIC_02}

### 2.1. Key Entities  {#C_EIC_02_01}

```
dialog/
  Dialog                        — GWT DialogBox base: position persistence, viewport
                                  clamp, anchor-aware resize, collapse toggle,
                                  enter-to-apply hook, showing-dialog registry
  Editable (interface)          — getEditInfo(int n) / setEditValue(int n, EditInfo)
  EditInfo                      — parameter-description DTO (field-bag)
  EditDialog  extends Dialog    — generic renderer; walks Editable, synthesises widgets
  EditOptions implements Editable — global-settings data source (the only non-CircuitElm
                                    direct Editable in the codebase)
  EditDialogLoadFile            — hidden FileUpload + JSNI click() for file-picker rows
```

Implementers of `Editable`: `CircuitElm` (transitive → ~135 element classes), `EditOptions`, `CustomLogicModel`, `DiodeModel`, `TransistorModel`.

### 2.2. Data Flows  {#C_EIC_02_02}

Build / commit handshake (per-row request/response with one shared `EditInfo`):

```
EditDialog                              Editable (CircuitElm | model | EditOptions)
-----------                             ------------------------------------------
buildDialog  ─ getEditInfo(0) ──────▶   return new EditInfo("Resistance", r, 0, 0)
  ◀── EditInfo (fields populated) ──
  render widget based on non-null fields
  (Choice > Checkbox > Button > TextArea > Widget > TextBox)

  (loop n=1,2,… until null returned)

user edits, Enter / OK / Apply / choice-click / checkbox-toggle:

apply        ─ setEditValue(0, ei) ─▶  r = ei.value; flags = ei.changeFlag(...)
  (repeat for all rows)

  needAnalyze()  →  simulator re-analyses topology

if any setEditValue set ei.newDialog = true:
  clearDialog() + buildDialog()   — full schema re-query
```

## 3. Mechanisms  {#C_EIC_03}

### 3.1. Core Algorithm  {#C_EIC_03_01}

**EditDialog.buildDialog** — walks `n=0..` calling `elm.getEditInfo(n)`; for each non-null result it picks a widget kind by probing populated fields (the precedence chain in SP_EIC §01); wraps the label in `Label` or `HTML` (for rows whose name starts with `<`); attaches a change handler for immediate-commit widgets (Choice, Checkbox, Button) and no handler for text widgets (parsed at apply time); wraps after 15 widgets into a new `VerticalPanel` column.

**EditDialog.apply** — for every cached row where `textf != null && text == null`, parse via `parseUnits(ei)` (SI-prefix parser) and write to `ei.value`; silently skip rows whose parse fails; call `elm.setEditValue(i, ei)` for every row that isn't a button; look up any `Adjustable` bound to `(elm, i)` and push `ei.value` into its slider; flag `cframe.setUnsavedChanges(true)` + `cframe.needAnalyze()`.

**EditDialog.itemStateChanged** — fired by Choice / Checkbox / Button / LoadFile; identifies the changed row by widget-identity match; if the source is a non-newDialog button, runs `apply()` first so pending text edits persist before the button's handler; calls `elm.setEditValue(i, ei)` for the changed row only; if the row set `ei.newDialog`, re-enter `clearDialog + buildDialog`.

**Dialog position/collapse persistence** — `Dialog.show()` defers `ensureInitialPosition / clampIntoViewport / updateAnchorsFromCurrentPosition / ensureCollapseToggle / applyCollapsedState`; on drag-end it re-derives the anchor (nearest corner) and persists; a global resize handler walks `showingDialogs` and re-anchors each.

**EditDialogLoadFile** — hidden `FileUpload` wired via JSNI `.click()`; subclass implements `handle()` consuming the selected file; typically ends by calling `dialogManager.resetEditDialog()` to refresh.

### 3.2. Edge Cases  {#C_EIC_03_02}

- **`einfos[10]` hard cap.** `EditDialog` caches rows in a fixed-size array; any Editable returning non-null for index ≥ 10 will throw AIOOBE (see Issue #1 / SP_EIC §03).
- **Silent parse failure.** `parseUnits` throws `ParseException`; `apply()` catches and silently keeps the old value — no user feedback.
- **Cancel ≠ Undo.** Choice/Checkbox/Button rows commit immediately inside `itemStateChanged`; only TextBox rows require Apply. Clicking Cancel after a Choice change does not revert.
- **`closeOnEnter = false` is sticky.** A TextArea row disables Enter-to-apply for the dialog's lifetime even after rebuild.
- **`ei.newDialog` is one-shot.** `EditInfo` is discarded on `clearDialog`; new rows start fresh.
- **Slider sync only works for `CircuitElm` Editables.** `EditOptions` rows cannot become adjustables (cast gate in `EditDialog.apply`).

## 4. Integration Points  {#C_EIC_04}

### 4.1. Dependencies  {#C_EIC_04_01}

- **[C_UTL](./util-locale-log.concept.md)** — `util.Locale.LS(...)` for translation.
- **[C_LUW](./legacy-ui-wrappers.concept.md)** — `client.Choice`, `client.Checkbox`, `client.OptionsManager`, `client.Color`, `client.ColorSettings`, `client.DisplaySettings`, `client.LoadFile`.
- **[C_ELB](./element-base.concept.md)** — `CircuitElm implements Editable`; this is the strongest cross-package edge in the codebase (104 imports).
- **C_SHM (shared-models)** — `DiodeModel`, `TransistorModel`, `CustomLogicModel` all implement `Editable`.
- **Root integrators** — `CirSim`, `DialogManager`, `ActionManager`, `AdjustableManager`, `SimulationContextAware`.

### 4.2. API Surface  {#C_EIC_04_02}

- `Editable` interface (two methods) — the contract any parameter-bearing object must satisfy.
- `EditInfo` DTO with 14+ populated-field variants (Number with/without range, dimensionless, no-slider, text, text-area, choice, checkbox, button × {action, newDialog, loadFile}, color, opaque widget, link, slider config) — full catalog in SP_EIC §01_03.
- `EditInfo.createCheckbox / setDimensionless / disallowSliders / changeFlag / changeFlagInverted / canCreateAdjustable / makeLink` — static factories and fluent setters.
- `Dialog` lifecycle hooks: `show`, `hide`, `closeDialog`, `apply`, `enterPressed`, `getOptionPrefix`, `setCollapsed`, `isPositionRestored`.
- `EditDialog.parseUnits(String)` / `unitString(EditInfo[, double])` — module-public SI formatter/parser reused by `SliderDialog`, `ScopePropertiesDialog`.
- `EditDialog.resetDialog()` / `dialogManager.resetEditDialog()` — external refresh hook (used by `EditDialogLoadFile` subclasses after file load).
- `EditDialogLoadFile` abstract — subclass hook for file-picker button rows.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
