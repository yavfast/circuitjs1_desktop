# Edit-Info Contract — Specification  {#SP_EIC}

> **Code:** SP_EIC
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_EIC](./edit-info-contract.concept.md)
> **Depends on specs:** [SP_UTL](./util-locale-log.sp.md), [SP_GEO](./geometry.sp.md), [SP_RND](./rendering-primitives.sp.md), [SP_LUW](./legacy-ui-wrappers.sp.md), [SP_ELB](./element-base.sp.md)
> **Used by specs:** — (will be filled by higher layers)
> **Plan:** [edit-info-contract.plan.md](./edit-info-contract.plan.md)
>
> Backing analyses: `.dev_flow/onboard/analysis/domain-core__dialog-base.md` + `domain-core__dialog-edit.md`.
>
> Defines the fields, contract methods, widget precedence, and state transitions of 6 files: `Dialog.java`, `Editable.java`, `EditInfo.java`, `EditDialog.java`, `EditOptions.java`, `EditDialogLoadFile.java`.

## 01. Data Structures  {#SP_EIC_01}

> Implements: [C_EIC_02](./edit-info-contract.concept.md#C_EIC_02)

### 01_01. Dialog (base)  {#SP_EIC_01_01}

`public class Dialog extends com.google.gwt.user.client.ui.DialogBox`. File: `Dialog.java:18`. 373 LOC.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `closeOnEnter` | `boolean` | `true` (ctor) | Enter → `apply() + closeDialog()`. |
| `positionRestored` | `boolean` | `false` | True after `loadPosition()` succeeded. |
| `positionExplicitlySet` | `boolean` | `false` | Suppresses center-on-first-show. |
| `horizontalAnchor` / `verticalAnchor` | enum | LEFT/TOP | Nearest-corner anchor (drag-end). |
| `horizontalAnchorOffsetPx` / `verticalAnchorOffsetPx` | `int` | 0 | Pixel offset from anchor. |
| `collapsed` | `boolean` | persisted | Body hidden, only caption visible. |
| `collapseToggleElement` | `Element` | lazy | DOM-injected "-/+" span. |
| `showingDialogs` | `static Set<Dialog>` | empty | Registry walked by resize handler. |
| `resizeHandlerInstalled` | `static boolean` | `false` | One-time install guard. |

### 01_02. Editable (interface)  {#SP_EIC_01_02}

File: `Editable.java:3`. 7 LOC.

```
public interface Editable {
  EditInfo getEditInfo(int n);         // n-th row; null terminates
  void     setEditValue(int n, EditInfo ei);  // commit n-th row
}
```

### 01_03. EditInfo (field-bag)  {#SP_EIC_01_03}

`public class EditInfo`. File: `EditInfo.java:30`. 113 LOC.

| Field | Type | Meaning |
|-------|------|---------|
| `name` | `String` | Row label (localised by caller). |
| `text` | `String` | Text-kind row: initial content. |
| `value` | `double` | Numeric-kind row: initial value. |
| `textf` | `TextBox` | Populated by dialog on render. |
| `choice` | `client.Choice` | Non-null → dropdown row. |
| `checkbox` | `client.Checkbox` | Non-null → boolean row. |
| `button` | `Button` | Non-null → action/newDialog/loadFile row. |
| `loadFile` | `EditDialogLoadFile` | Non-null → button opens file picker. |
| `textArea` | `TextArea` | Non-null → multi-line row. |
| `widget` | `Widget` | Non-null → opaque embed. |
| `newDialog` | `boolean` | Button spawns sub-dialog (also triggers rebuild). |
| `dimensionless` | `boolean` | Suppress SI formatting/parsing. |
| `noSliders` | `boolean` | Gate out "Create Adjustable" menu. |
| `minVal` / `maxVal` | `double` | Numeric range; seeds slider min/max. |
| `isColor` | `boolean` | HTML5 color picker. |
| `unit` | `String` | Suffix (`"Ω"`, `"V"`, `"Hz"`, …). |
| `minBox` / `maxBox` / `labelBox` | `TextBox` | Used only by `SliderDialog`. |

**Widget-kind catalog (populated fields → rendered widget):**

| Kind | Populated fields | Built by |
|------|------------------|----------|
| Number (ranged) | `name`, `value`, `minVal`, `maxVal`, optional `unit` | `new EditInfo(n, val, mn, mx[, u])` |
| Number (no range) | `name`, `value` | `new EditInfo(n, val)` |
| Dimensionless number | `+dimensionless=true` | `.setDimensionless()` |
| No-slider number | `+noSliders=true` | `.disallowSliders()` |
| Text / String | `name`, `text`, dimensionless+noSliders auto-set | `new EditInfo(n, txt)` |
| Text area | `name`, `textArea` | set `textArea` post-ctor |
| Choice / Dropdown | `choice` non-null | set `choice` post-ctor |
| Checkbox | `checkbox` non-null | `EditInfo.createCheckbox(name, flag)` |
| Button (action) | `button` non-null | set `button` post-ctor |
| Button (newDialog) | `button`, `newDialog=true` | set both |
| Button (load-file) | `button`, `loadFile` | set both |
| Color | `value`, `isColor=true` | set `isColor=true` post-ctor |
| Opaque widget | `widget` non-null | set `widget` post-ctor |
| Link | `name`, `text = makeLink(f, t)` | `EditInfo.makeLink(file, text)` |
| Slider config | `minBox`, `maxBox`, `labelBox` | populated by `SliderDialog` |

**Widget-precedence (`EditDialog.buildDialog` line 128-178):** `choice` → `checkbox` → `button` → `textArea` → `widget` → numeric/text TextBox.

### 01_04. EditDialog  {#SP_EIC_01_04}

`public class EditDialog extends Dialog`. File: `EditDialog.java:46`. 357 LOC.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `elm` | `Editable` | ctor | Row source. |
| `cframe` | `CirSim` | ctor | App context. |
| `einfos[]` | `EditInfo[10]` | new | **Fixed cap; AIOOBE if owner exceeds it.** |
| `einfocount` | `int` | 0 | Non-null row count. |
| `mainPanel` | `VerticalPanel` | — | Root container. |
| `bottomButtonPanel` | `HorizontalPanel` | — | Apply / OK / Cancel. |
| `applyButton` / `okButton` / `cancelButton` | `Button` | — | Action buttons. |
| `noCommaFormat` | `static NumberFormat` | `"####.##########"` | Raw decimal. |

### 01_05. EditOptions  {#SP_EIC_01_05}

`public class EditOptions implements Editable`. File: `EditOptions.java:32`. 227 LOC. The only non-`CircuitElm` `Editable` in the project; writes directly to `ColorSettings`, `DisplaySettings`, `OptionsManager`, `simulator`, `circuitEditor`.

Row schedule (n → row):

| n | Row | Source | Widget |
|---|-----|--------|--------|
| 0 | Range for voltage color (V) | `ColorSettings.getVoltageRange()` | TextBox |
| 1 | Change Language | hard-coded list of 15 | Choice |
| 2..6 | Positive/Negative/Neutral/Selection/Current Color | `ColorSettings.get*()` | Color TextBox |
| 7..8 | # of Decimal Digits (short/long) | `DisplaySettings.*` | TextBox |
| 9 | Developer Mode | `circuitInfo.developerMode` | Checkbox |
| 10 | Minimum Target Frame Rate | `simulator.minFrameRate` | TextBox |
| 11 | Mouse Wheel Sensitivity | `circuitEditor.wheelSensitivity` | TextBox |
| 12 | Auto-Adjust Timestep | `simulator.adjustTimeStep` | Checkbox (`newDialog=true`) |
| 13 | Minimum time step size (s) | `simulator.minTimeStep` (conditional) | TextBox |

### 01_06. EditDialogLoadFile  {#SP_EIC_01_06}

`public abstract class EditDialogLoadFile extends FileUpload implements ChangeHandler`. File: `EditDialogLoadFile.java:32`. 61 LOC.

| Member | Role |
|--------|------|
| `EditDialogLoadFile()` | Sets name `"Load File"`, id `"EditDialogLoadFileElement"`, registers ChangeHandler, 0×0 `offScreen` style. |
| `isSupported()` (static) | Delegates to `LoadFile.isSupported()`. |
| `doErrorCallback(String)` (static) | Localised `Window.alert`. |
| `onChange(ChangeEvent)` | Fires `handle()`. |
| `open()` (JSNI) | Synthetic `.click()` on hidden FileUpload. |
| `handle()` (abstract) | Subclass consumes selected file. |

Sole concrete subclass in-tree: `SRAMLoadFile`.

**Invariants:**
- `einfos.length == 10`; adding `Editable`s with >10 non-null rows is undefined.
- `getEditInfo(n)` must return `null` as sentinel; no row-count query exists.
- The instance passed to `setEditValue(n, ei)` is reference-equal to the one returned by `getEditInfo(n)`.
- Row indices stable between `getEditInfo` and `setEditValue` calls.
- `ei.newDialog` is one-shot; set inside `setEditValue`, consumed by the next `clearDialog+buildDialog` cycle.

## 02. Contracts  {#SP_EIC_02}

### 02_01. Editable.getEditInfo(int n) / setEditValue(int n, EditInfo ei)  {#SP_EIC_02_01}

Purpose: expose parameters and accept mutation.

Input: `n` (0-based index). Returns `EditInfo` or `null`.

Processing:
```
FUNCTION buildDialog():
    FOR n = 0, 1, 2, …:
        ei = elm.getEditInfo(n)
        IF ei == null: break
        einfos[n] = ei
        render widget by first-non-null field:
          ei.choice  → Choice (ChangeHandler → itemStateChanged)
          ei.checkbox→ Checkbox (ValueChangeHandler → itemStateChanged)
          ei.button  → Button (+ optional EditDialogLoadFile on loadFile)
          ei.textArea→ TextArea (dialog.closeOnEnter = false)
          ei.widget  → embed as-is
          default    → TextBox (color / numeric / text; parsed on apply)
```

### 02_02. EditDialog.apply  {#SP_EIC_02_02}

Processing:
```
FOR i = 0..einfocount:
    ei = einfos[i]
    IF ei.textf != null AND ei.text == null:
        TRY: ei.value = parseUnits(ei)
        CATCH ParseException: continue   # silent, keep old
    IF ei.button != null: continue
    elm.setEditValue(i, ei)
    IF elm instanceof CircuitElm:
        adj = adjustableManager.findAdjustable(elm, i)
        IF adj != null: adj.setSliderValue(ei.value)
cframe.setUnsavedChanges(true)
cframe.needAnalyze()
```

### 02_03. EditDialog.itemStateChanged  {#SP_EIC_02_03}

```
FOR i matching event source widget:
    IF ei.button != null AND !ei.newDialog AND !applied:
        apply(); applied = true
    elm.setEditValue(i, ei)
    IF ei.newDialog: changed = true
cframe.needAnalyze()
IF changed:
    IF !applied: apply()
    clearDialog()    # remove widgets above buttonBar
    buildDialog()    # full re-query from n=0
```

### 02_04. EditDialog.parseUnits(String)  {#SP_EIC_02_04}

SI-prefix parser. Steps: trim → strip `rms` (×√2) → rewrite `2k2` to `2.2k` → collapse `meg` to `M` → read suffix char (`fFpPnNuUmkMGg`) and apply multiplier (lowercase `m` = milli; uppercase `M` = mega) → parse remainder with `noCommaFormat`. Throws `ParseException` on bad input.

### 02_05. EditInfo factories/fluent  {#SP_EIC_02_05}

| Method | Purpose |
|--------|---------|
| `createCheckbox(name, flag)` | Builds checkbox row; `name == ""` (label lives on Checkbox itself). |
| `setDimensionless()` | Suppress SI. Returns `this`. |
| `disallowSliders()` | Gate out "Create Adjustable". Returns `this`. |
| `changeFlag(flags, bit)` | Apply checkbox state to single bit. |
| `changeFlagInverted(flags, bit)` | Inverted variant. |
| `canCreateAdjustable()` | `true` iff plain number (no choice/checkbox/button/textArea/widget/noSliders). |
| `makeLink(file, text)` | Build `<a>` with localised text. |

### 02_06. Dialog lifecycle hooks  {#SP_EIC_02_06}

| Hook | Default | Purpose |
|------|---------|---------|
| `show()` | super+register+defer position/collapse | Attach to DOM. |
| `hide(boolean)` | save pos/collapse+unregister | Detach. |
| `closeDialog()` | wraps `hide()` | Convenience. |
| `apply()` | no-op | Subclass commits. |
| `enterPressed()` | if closeOnEnter: apply+close | Enter key hook. |
| `getOptionPrefix()` | `null` | Subclass returns storage key; `null` disables persistence. |
| `endDragging(MouseUpEvent)` | clamp + re-anchor + savePosition | Drag-release. |

## 03. Validation Rules  {#SP_EIC_03}

### 03_01. Input Validation  {#SP_EIC_03_01}

- `EditDialog.parseUnits` throws `ParseException`; `apply()` silently discards.
- `EditDialog.einfos[10]` has a hard cap — no bounds guard in `buildDialog`'s `for(i=0;;i++)` loop; owner returning non-null ≥ 10 throws AIOOBE.
- `getEditInfo(n)` return `null` = terminator; no alternative length query.
- `setEditValue(n, ei)` is always called with the same `ei` that `getEditInfo(n)` returned.
- `ei.newDialog` is read only in `itemStateChanged`; ignored elsewhere.
- `ei.loadFile` requires `ei.button != null` (dialog short-circuits otherwise).
- `Dialog.getOptionPrefix() == null` ⇒ position/collapse persistence silently no-ops.
- `EditDialog` accepts any `Editable`; `CircuitElm` cast gate on slider sync.

## 04. State Transitions  {#SP_EIC_04}

### 04_01. EditDialog lifecycle  {#SP_EIC_04_01}

```
  constructed ──show()──▶ shown ──getEditInfo(0..n)──▶ rendered
                            │
                            │                              ┌──── choice/checkbox/button click
                            ▼                              ▼
                         user edits                   itemStateChanged
                            │                              │
                            │                              ├─ setEditValue(i, ei)
                            │                              │
                            │                              ├─ if newDialog:
                            │                              │     apply()
                            │                              │     clearDialog()
                            │                              │     buildDialog()  ─┐
                            │                              │                     │
                            ▼                              ▼                     │
                          Apply                           (same state)           │
                            │                                                    │
                            ├─ parse textboxes ───────────────────────────────────┘
                            ├─ setEditValue for all
                            ├─ sync sliders
                            └─ needAnalyze
                            │
                            ▼
                           OK  → closeDialog
                           Cancel → closeDialog (NO revert)
```

| From | To | Condition | Side effects |
|------|----|-----------|--------------|
| constructed | shown | `show()` | deferred position/collapse |
| shown | rebuilt | any row set `ei.newDialog` | `apply + clearDialog + buildDialog` |
| shown | committed | Apply/OK | `setEditValue×N + needAnalyze` |
| committed-OK | closed | OK after apply | `hide + savePosition` |
| shown | closed | Cancel | `hide`; no revert of live-committed rows |

## 05. Verification Criteria  {#SP_EIC_05}

### 05_01. Functional Expectations  {#SP_EIC_05_01}

| Contract | Scenario | Input | Expected |
|----------|----------|-------|----------|
| `getEditInfo/setEditValue` | Round-trip | numeric row r=1k | parse `"1k"` ⇒ `ei.value=1000`; `setEditValue` writes back |
| `parseUnits` | SI suffix | `"2.2k"`, `"1M"`, `"1meg"`, `"5m"` (lower = milli) | 2200, 1e6, 1e6, 0.005 |
| `parseUnits` | `"2k2"` shorthand | — | 2200 |
| Rebuild | `ei.newDialog=true` | toggle Auto-Adjust Timestep in EditOptions | row 13 appears/vanishes |
| Cancel | Choice committed live | click Choice, then Cancel | value persists (not reverted) |

### 05_02. Invariant Checks  {#SP_EIC_05_02}

| Invariant | Verification |
|-----------|--------------|
| `einfos[]` length ≤ 10 | static count of rows per Editable |
| `ei` identity preserved | reference-equal in `getEditInfo`/`setEditValue` pair |
| `ei.newDialog` one-shot | cleared by `clearDialog` |
| Position persisted only if `getOptionPrefix() != null` | no-op guard in `savePosition/loadPosition` |

### 05_03. Integration Scenarios  {#SP_EIC_05_03}

| Scenario | Preconditions | Steps | Expected |
|----------|---------------|-------|----------|
| Diode "Create Simple Model" | DiodeElm, button `newDialog=true` | click button | apply + clearDialog + buildDialog; new rows reflect new model |
| SRAM file load | ROM element with `ei.loadFile=SRAMLoadFile` | click button → choose file | JSNI opens native picker; `handle()` parses; `resetEditDialog()` refreshes |
| EditOptions language change | user picks "Français" | setEditValue row 1 | `OptionsManager.setOptionInStorage("language",…)` + confirm + page reload |
| Slider sync | Adjustable bound to `(ResistorElm,0)` | typed `"2k2"`, Apply | ei.value=2200; adj.setSliderValue(2200) |

### 05_04. Edge Cases  {#SP_EIC_05_04}

| Case | Input | Behaviour |
|------|-------|-----------|
| Owner returns 11+ rows | — | AIOOBE on `einfos[10]` write |
| Parse failure | `"abc"` | silent, old value kept |
| TextArea added | any row | `closeOnEnter=false` sticky for dialog life |
| Two loadFile rows | two SRAMLoadFile in one dialog | shared element id → `document.getElementById` returns first |
| Dialog w/ no `getOptionPrefix` | — | position/collapse not persisted |

## 06. Constants  {#SP_EIC_06}

- `Dialog.closeOnEnter` default: `true`.
- `EditDialog.einfos.length = 10`.
- Column-wrap threshold: `getWidgetCount() > 15` (hard-coded).
- `noCommaFormat = "####.##########"`.
- `EditDialogLoadFile` DOM id: `"EditDialogLoadFileElement"` (global, single-instance assumption).

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
