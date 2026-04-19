# Module Analysis: domain-core / dialog-edit

> **Path:** src/main/java/com/lushprojects/circuitjs1/client/dialog/ (3 files)
> **Layer:** 2 (SCC-A, same cluster as element-base)
> **Analyzed:** 2026-04-18
> **Files:** 3 source files, 0 test files

## Summary (3 lines)

`EditDialog` is the generic GWT form that renders an `Editable` (`CircuitElm`
subclass or `EditOptions`) by polling `getEditInfo(n)` until it returns null,
mapping each `EditInfo` to exactly one widget kind (Choice / Checkbox /
Button / TextArea / generic Widget / TextBox). `EditOptions` plugs into the
same form to edit app-global state (`ColorSettings`, `DisplaySettings`,
`OptionsManager`, simulator knobs) instead of an element's fields.
`EditDialogLoadFile` is an abstract `FileUpload` subclass attached to an
`EditInfo.button` so a dialog row can trigger a native file picker.

## Purpose

The `dialog-edit` sub-unit is the **generic property-sheet UI** of
CircuitJS1. It is the single entry point that turns the element-base
editing contract (`Editable.getEditInfo(n)` / `setEditValue(n, ei)` —
`element-base.md:670-681`) into a live GWT dialog.

Three concerns:

1. **Widget synthesis** — walk `Editable` rows, produce a GWT panel
   with one widget per row based on which `EditInfo` field is populated.
2. **Apply / OK / Cancel** — parse text into doubles, push values back
   via `setEditValue`, poke the simulator (`cframe.needAnalyze()`).
3. **Rebuild-on-change** — when a field change invalidates the row set
   (e.g. waveform type change, auto-timestep toggle), re-enter
   `buildDialog()` so the row list refreshes.

The same class services element editing (`ResistorElm`, `DiodeElm`, …)
**and** global options editing (`EditOptions`), because both implement
the `Editable` contract (`Editable.java:3`). This is the only dialog in
the codebase that does not know what it is editing.

## Per-file analysis

### EditDialog.java (357 LOC)

**Class:** `public class EditDialog extends Dialog`
(`EditDialog.java:46`).
Instantiated by `DialogManager.showEditOptionsDialog()` /
`DialogManager.showEditDialog(Editable)`
(`DialogManager.java:105,111`).

#### State

| Field | Type | Purpose |
|---|---|---|
| `elm` | `Editable` | Row source; queried via `getEditInfo(i)`. Line 47. |
| `cframe` | `CirSim` | App context — used to find adjustables and re-run analysis. Line 48. |
| `applyButton`, `okButton`, `cancelButton` | `Button` | Bottom bar. Lines 49, 85, 93. |
| `einfos[]` | `EditInfo[10]` | **Fixed-size** cache of rows currently rendered. Line 50 (**hard cap = 10 rows**, but `buildDialog` iterates `for (i=0;;i++)` with no bounds check — if any `Editable` returns non-null for `i >= 10`, this will throw `ArrayIndexOutOfBoundsException`). |
| `einfocount` | `int` | Number of non-null rows; used by `apply()` / `itemStateChanged()`. Line 51. |
| `mainPanel` | `VerticalPanel` | Root container (form column + button bar). Line 53. |
| `bottomButtonPanel` | `HorizontalPanel` | Holds Apply / OK / Cancel. Line 54. |
| `noCommaFormat` | `static NumberFormat` | `"####.##########"` — raw decimal, no thousands separator. Line 55. |

#### Constructor (line 57-101)

1. Calls `Dialog()` default ctor (non-modal, center-positioned on show).
2. `setText("Edit Component")` via `Locale.LS`.
3. If `elm instanceof SimulationContextAware` (line 63-65), injects
   `cframe.getActiveDocument()` into the editable before row querying.
   **This is how rows that need simulator/document context (e.g. per-doc
   settings) get it without a parameter in the contract.**
4. Builds `bottomButtonPanel` + three buttons (Apply, OK, Cancel).
5. Hands off to `buildDialog()` then `center()`.

Button semantics:
- **Apply** (line 80-84) — `apply()` only. Dialog stays open.
- **OK** (line 85-91) — `apply()` then `closeDialog()`.
- **Cancel** (line 93-98) — `closeDialog()` **only**. See
  "Apply / Cancel semantics" below — Cancel is not an undo.

#### buildDialog (line 109-187) — the widget factory

Structure:
```
mainPanel
├─ hp (HorizontalPanel)  <-- inserted BEFORE bottomButtonPanel
│   └─ vp (VerticalPanel, column 1)
│       ├─ Label/HTML (row name)
│       ├─ widget (row 0)
│       ├─ Label "topSpace"
│       ├─ widget (row 1)
│       └─ … up to 15 widgets, then hp adds a new vp (column 2)
└─ bottomButtonPanel (Apply / OK / Cancel)
```

Column wrapping: when a column's widget count exceeds 15
(`getWidgetCount() > 15`, line 179), a fresh `VerticalPanel` is
appended to `hp` with 10-px left padding. This is how giant
`getEditInfo` row lists (e.g. chip pin setups) wrap instead of
overflowing vertically.

Label rendering (line 116-127):
- `ei.name.startsWith("<")` → wrap name in `HTML` (supports rich HTML
  like `EditInfo.makeLink` anchor tags at `EditInfo.java:109`).
- Otherwise → plain `Label`.
- All rows after the first get `styleName("topSpace")` for vertical
  separation.

The **type-dispatch** block (line 128-178) picks the widget based on
which `EditInfo` field is non-null. See the mapping table below.

#### EditInfo → GWT widget mapping table

Precedence order is **top-to-bottom**: first non-null field wins.

| `EditInfo` field populated | Widget rendered | Change handler attached | EditDialog.java line |
|---|---|---|---|
| `ei.choice` (Choice) | Directly added to column | `ChangeHandler → itemStateChanged` | 128-134 |
| `ei.checkbox` (Checkbox) | Directly added | `ValueChangeHandler<Boolean> → itemStateChanged` | 135-141 |
| `ei.button` + `ei.loadFile == null` | Button added | `ClickHandler → itemStateChanged` | 142, 152-158 |
| `ei.button` + `ei.loadFile != null` | Button + hidden `FileUpload` | Button click → `ei.loadFile.open()` (native file picker) | 142-151 |
| `ei.textArea` (TextArea) | TextArea; also sets `closeOnEnter=false` (so Enter is a newline) | *(no change handler — read at apply time only)* | 160-162 |
| `ei.widget` (arbitrary Widget) | Widget added verbatim | *(dialog does not touch it after insertion)* | 163-164 |
| **default** (none of the above) | `ei.textf = new TextBox()` | *(none; parsed on apply)* | 165-177 |

TextBox specialization (line 166-177):
- If `ei.text != null`: pre-fill with raw string, set visible length 50
  (string-only row — no unit parsing).
- Else: pre-fill with `unitString(ei)` which formats `ei.value` with SI
  prefixes (`f/p/n/u/m/k/M/G`) and `rms` suffix for `VoltageElm`.
- If `ei.isColor`: set `type="color"` and style `width:178px;padding:0`
  — HTML5 color picker.

Row termination is by returning `null` from `getEditInfo(i)`
(line 117-119) — the Editable has no row-count query, so this is
"sentinel-terminated iteration".

#### unitString (line 195-228) — SI-prefix formatting

`unitString(EditInfo)` with VoltageElm special case:
- If `|value| > 1e-4` and dividing by `√2` produces a "rounder" number
  (fewer decimal digits from the integer), the string is formatted as
  `(value/√2) + "rms"`. Lines 197-201. This lets voltage sources
  display "10rms" instead of "14.1421356" when the underlying peak is
  the RMS × √2.

`unitString(EditInfo, double v)` (static, line 204-228) — tiers:
- dimensionless or infinite → plain decimal
- 0 → `"0"`
- `< 1e-12` → `f` (femto), `< 1e-9` → `p`, `< 1e-6` → `n`,
  `< 1e-3` → `u`, `< 1` → `m`, `< 1e3` → bare, `< 1e6` → `k`,
  `< 1e9` → `M`, else `G`

#### parseUnits (line 230-287) — SI-prefix parsing (inverse)

Static, re-usable by other dialogs (notably `SliderDialog`).
Transformations applied in order:
1. Trim.
2. Strip trailing `rms` → set `rmsMult = √2`.
3. Regex `([0-9]+)([pPnNuUmMkKgG])([0-9]+)` → `$1.$3$2`
   — rewrites `"2k2"` shorthand to `"2.2k"`. Line 243.
4. Regex `[mM][eE][gG]$` → `"M"` — so `"1meg"` == `"1M"` == 1e6.
5. Read last char; if it is one of `fFpPnNuUmkMGg`, apply multiplier
   table (line 249-283). **Case split for `m` vs `M`:** lowercase
   `m` always means milli (`1e-3`); uppercase `M` means mega (`1e6`).
6. Parse remainder with `noCommaFormat` and multiply.

Throws `java.text.ParseException` on bad input; `apply()` catches
and **silently keeps the old value** (line 294-297).

#### apply (line 289-312)

Pulled from every row — regardless of whether the user actually changed
anything:
1. For each `einfos[i]` where `ei.textf != null && ei.text == null`
   (numeric textbox), parse via `parseUnits(ei)`, write to `ei.value`.
   Parse errors silently swallowed (line 297).
2. Skip rows where `ei.button != null` (buttons don't carry values).
3. Call `elm.setEditValue(i, ei)` — the Editable pulls the new value
   out of whichever field applies.
4. **Slider sync** (line 304-308): if `elm instanceof CircuitElm`, look
   up the Adjustable bound to this element+row via
   `adjustableManager.findAdjustable((CircuitElm) elm, i)` and
   `setSliderValue(ei.value)`. This is how the live sliders above the
   schematic stay in sync when the user edits via dialog.
5. Flag `cframe.setUnsavedChanges(true)` + `cframe.needAnalyze()`.

#### itemStateChanged (line 314-344) — the rebuild trigger

Called whenever Choice / Checkbox / Button / file-load fires. Walks
rows to find which one matched the event source:

- For **button** sources that don't open a new dialog
  (`!ei.newDialog`): run `apply()` first (line 324-327) so any text
  edits already entered are persisted **before** the button handler
  runs. Tracked via `applied = true` to avoid double-applying.
- Call `elm.setEditValue(i, ei)` for the changed row only.
- If `ei.newDialog` is true (line 330-331): mark `changed = true`.
- `cframe.needAnalyze()` regardless.

After the loop, if `changed`:
1. If not already applied, `apply()` (line 338-339; the "Diode create
   simple model button doesn't work" comment documents the ordering
   bug this guard fixes).
2. `clearDialog()` — removes every widget before `bottomButtonPanel`
   (line 351-354: walks `mainPanel.getWidget(0)` until it hits the
   button bar).
3. `buildDialog()` — re-queries **every** `getEditInfo(i)` from
   scratch.

This is the **rebuild-on-change** pattern — see dedicated section
below.

#### resetDialog / clearDialog (line 346-354)

- `clearDialog()` removes all widgets above the button bar; called
  from `itemStateChanged` and from `closeDialog` (via `resetDialog`).
- `resetDialog()` is `clearDialog()` + `buildDialog()`. Exposed
  publicly; `SRAMLoadFile` calls `cirSim.dialogManager.resetEditDialog()`
  (`SRAMLoadFile.java:59`) after file load to refresh the ROM contents
  row.

---

### EditOptions.java (227 LOC)

**Class:** `public class EditOptions implements Editable`
(`EditOptions.java:32`).
Instantiated by `DialogManager.showEditOptionsDialog()` (line 105).
Wrapped in a standard `EditDialog` — the same form code.

**Role:** Implements the `Editable` contract for **application-global
state**, not for an element. This is the only non-`CircuitElm`
`Editable` in the project.

#### Row schedule (line 39-118) — `getEditInfo(n)`

| n | Row | Source of truth | Widget |
|---|---|---|---|
| 0 | "Range for voltage color (V)" | `ColorSettings.get().getVoltageRange()` | TextBox (numeric) |
| 1 | "Change Language" | *(hard-coded list of 15)* | Choice |
| 2 | "Positive Color" | `cs.getPositiveColor().getHexValue()` | Color TextBox |
| 3 | "Negative Color" | `cs.getNegativeColor()…` | Color TextBox |
| 4 | "Neutral Color" | `cs.getNeutralColor()…` | Color TextBox |
| 5 | "Selection Color" | `cs.getSelectColor()…` | Color TextBox |
| 6 | "Current Color" | `cs.getCurrentColor()…` | Color TextBox |
| 7 | "# of Decimal Digits (short format)" | `DisplaySettings.getShortDecimalDigits()` | TextBox |
| 8 | "# of Decimal Digits (long format)" | `DisplaySettings.getDecimalDigits()` | TextBox |
| 9 | (none) "Developer Mode" | `sim.getActiveDocument().circuitInfo.developerMode` | Checkbox |
| 10 | "Minimum Target Frame Rate" | `simulator.minFrameRate` | TextBox |
| 11 | "Mouse Wheel Sensitivity" | `circuitEditor.wheelSensitivity` | TextBox |
| 12 | (none) "Auto-Adjust Timestep" | `simulator.adjustTimeStep` | Checkbox |
| 13 | "Minimum time step size (s)" | `simulator.minTimeStep` (only when `adjustTimeStep`) | TextBox |

Row 13 is **conditional** (line 114-115) — shown only when
`adjustTimeStep` is true, which is exactly why row 12 triggers
`ei.newDialog = true` (line 213).

#### setEditValue (line 120-217) — mutation sinks

Distinct from element `setEditValue` in that the targets are **global
registries**:
- `ColorSettings.get().setVoltageRange(...)`, `setPositiveColor(...)`,
  `setNegativeColor(...)`, `setNeutralColor(...)`, `setSelectColor(...)`,
  `setCurrentColor(...)` — with explicit `updateColorScale()` calls for
  the three that feed the voltage gradient (lines 184-194).
- `DisplaySettings.setDecimalDigitsShort(...)` /
  `setDecimalDigits(...)` (lines 199-202).
- `sim.setDeveloperMode(...)` (line 204).
- `simulator.minFrameRate`, `minTimeStep`, `adjustTimeStep` direct
  field writes (lines 206, 212, 216).
- `circuitEditor.wheelSensitivity` direct write (line 208).
- Language: writes to `OptionsManager.setOptionInStorage("language",
  langString)` then `Window.confirm` + `Window.Location.reload()`
  (lines 178-181). This is a one-way door — confirmation required.

Color writer `setColor(name, ei, def)` (line 219-226): extracts
`ei.textf.getText()`, defaults to `def.getHexValue()` if empty, persists
to `OptionsManager.setOptionInStorage(name, val)`, returns the new
`Color`. Persistence is **side-effect, not through a write buffer** —
there is no apply/cancel at this layer.

#### What makes EditOptions distinct from element editing

| Concern | Element editing (e.g. ResistorElm) | EditOptions |
|---|---|---|
| Target of `setEditValue` | Instance field of a CircuitElm | Global singletons (ColorSettings, DisplaySettings, OptionsManager) + per-document simulator/editor settings |
| Persistence | Saved as part of circuit dump (next save) | Written to browser local storage immediately via `OptionsManager.setOptionInStorage` |
| Scope | Affects one element | Affects all documents / whole application |
| Cancel semantics | Would-have reverted changes (but does not — see Issues) | Side effects already persisted; Cancel is meaningless |

---

### EditDialogLoadFile.java (61 LOC)

**Class:** `public abstract class EditDialogLoadFile extends FileUpload
implements ChangeHandler` (`EditDialogLoadFile.java:32`).

A thin abstract wrapper around GWT's `FileUpload` for the
file-picker-button pattern used in editing rows. Concrete subclasses
implement `handle()` to process the selected file.

#### Structure

| Member | Role |
|---|---|
| `EditDialogLoadFile()` ctor | Sets name "Load File", HTML id `EditDialogLoadFileElement`, registers self as `ChangeHandler`, adds `offScreen` style class, sets pixel size `(0,0)`. Line 42-49. |
| `isSupported()` (static) | Delegates to `LoadFile.isSupported()`. Line 34-36. |
| `doErrorCallback(String)` (static) | Localized `Window.alert`. Line 38-40. |
| `onChange(ChangeEvent)` | Fires when user picks a file; calls abstract `handle()`. Line 51-53. |
| `open()` (JSNI native) | Dispatches a synthetic `click()` to the hidden FileUpload element to open the native file picker. Line 55-58. |
| `handle()` (abstract) | Subclass hook — consume the selected file. Line 60. |

#### UI flow

1. Element's `getEditInfo(n)` creates a normal `EditInfo` with a
   `Button` and sets `ei.loadFile = new XxxLoadFile(...)`
   (e.g. `SRAMElm.java:174` — `ei.loadFile = new SRAMLoadFile(cirSim())`).
2. `EditDialog.buildDialog` detects `ei.loadFile != null`
   (line 144-151), adds both the visible `Button` and the hidden
   `EditDialogLoadFile` widget (it has `offScreen` class, 0×0 size).
3. Button click handler calls `ei.loadFile.open()` — JSNI triggers a
   native `click()` on the hidden input, opening the browser's file
   chooser.
4. User picks a file → GWT fires `ChangeEvent` → `onChange()` →
   `handle()`.
5. Subclass `handle()` typically parses the file and calls
   `cirSim.dialogManager.resetEditDialog()` to refresh the display
   (e.g. SRAMLoadFile line 59). **This is the only subclass pattern
   in use** — only SRAMLoadFile extends EditDialogLoadFile.

The "offscreen hidden FileUpload + synthetic click" trick is the
idiomatic way to style a GWT file input; the visible button can have
any look, and the `<input type="file">` is invisible.

---

## Rebuild flow (change detection → re-query `getEditInfo`)

```
User interacts with Choice / Checkbox / Button / LoadFile
        │
        ▼
onChange / onValueChange / onClick fires in buildDialog()
        │
        ▼
itemStateChanged(event)  [EditDialog.java:314]
        │
        ├─ Find the row `i` whose widget == event source
        │
        ├─ If row.button && !row.newDialog:
        │     run apply()   [push ALL text values through]
        │     applied = true
        │
        ├─ elm.setEditValue(i, ei)   [push the CHANGED row]
        │
        ├─ If row.newDialog: mark changed
        │
        └─ cframe.needAnalyze()
        │
        ▼
  changed?
        │
        ├─ No → stop (widget stays, dialog unchanged)
        │
        └─ Yes:
             ├─ If !applied → apply()   [else Diode "simple model" regresses]
             ├─ clearDialog()           [walk mainPanel, remove everything before button bar]
             └─ buildDialog()           [full re-query of getEditInfo(0), getEditInfo(1), …]
```

The **trigger** is always `ei.newDialog = true`, set by the Editable
inside `setEditValue` (not inside `getEditInfo`). Grep shows 10 callers:

- `EditOptions.java:213` — toggling Auto-Adjust Timestep (row 13 appears
  / disappears).
- `VoltageElm.java:262` — changing waveform type (DC vs AC vs Square
  vs … → different sub-fields).
- `DiodeElm.java:255` — choosing a diode model.
- `MosfetElm.java:604,616`, `TransistorElm.java:661`,
  `OpAmpRealElm.java:323`, `OutputElm.java:150,154`,
  `RelayElm.java:522`, `SRAMElm.java:176` — similar "switching a
  configuration choice changes which parameters are relevant".

**Note:** `ei.newDialog` is a one-shot flag. `EditInfo` is discarded
on `clearDialog()`, so the new rows built by `buildDialog()` start
fresh. There is no persistent "dirty" state — each click either
rebuilds or doesn't.

**Why `apply()` runs before `buildDialog()` when `changed && !applied`:**
the new `getEditInfo(i)` calls read the current element state, so any
pending text-box edits must be committed first, or the rebuilt dialog
would show stale values.

**Corner case: concurrent Choice + unfocused TextBox.** If the user
types "1k" in a numeric field, then clicks a choice that triggers
rebuild, the `apply()` call inside `itemStateChanged` parses and saves
the "1k", then the rebuilt dialog shows "1k" formatted back. Works.
If parse fails, `apply()` silently skips the row — the old value is
preserved, and the rebuilt dialog shows the old value.

## Apply / Cancel semantics

- **Apply button** — calls `apply()`: parses all textboxes, pushes
  every `einfos[i]` into `elm.setEditValue(i, ei)`, syncs sliders,
  flags unsaved, `needAnalyze()`. Dialog stays open so user can
  continue editing.
- **OK button** — `apply()` + `closeDialog()`. Changes are permanent.
- **Cancel button** — `closeDialog()` **only**. Does NOT revert
  changes already committed via:
  - `itemStateChanged()` — every click on a Choice/Checkbox/Button
    immediately calls `setEditValue(i, ei)` (line 329) **without**
    waiting for Apply. If the user clicks a Choice, sees the wrong
    result, then clicks Cancel, the choice is already baked in.
  - Button handlers that mutate global state directly (e.g.
    `EditOptions` language change writing to `OptionsManager`
    immediately, even before Apply).

So Cancel is **not an undo** — it is merely "close the window".
This is a subtle UX trap; the only true undo path is the global undo
stack (`UndoManager.popUndo()` — outside this module).

Textbox-only edits (the most common case) do require Apply/OK to
commit — because textboxes don't fire change handlers in the dialog
(line 165-177), parseUnits only runs inside `apply()`.

## Integration points

### Depends on

- **GWT UI:** `DialogBox`, `VerticalPanel`, `HorizontalPanel`, `Button`,
  `TextBox`, `TextArea`, `Label`, `HTML`, `FileUpload`, `NumberFormat`,
  `Window`, `ChangeHandler`, `ClickHandler`, `ValueChangeHandler`.
- **client root:** `CirSim`, `SimulationContextAware`, `Adjustable`
  (slider bridge), `Checkbox`, `Choice`, `Color`, `ColorSettings`,
  `DisplaySettings`, `OptionsManager`, `LoadFile`.
- **element-base:** `CircuitElm` (for the adjustable lookup cast;
  line 304), `VoltageElm` (for the rms unit-string special case;
  line 197).
- **util:** `Locale` (translation).

### Depended on by

- **`DialogManager`** — owner. Creates `EditDialog` via
  `showEditDialog(Editable)` (`DialogManager.java:111`) and
  `showEditOptionsDialog()` (line 105). Owns close / reset lifecycle.
- **Every `*Elm` class** — indirectly, by implementing `Editable`
  through `CircuitElm` (`element-base.md:192-194`).
- **`SRAMLoadFile`** — only concrete subclass of `EditDialogLoadFile`
  in the project.
- **`SliderDialog`** — reuses `EditDialog.parseUnits(String)` as a
  static helper.

### Contract integration

The module is the "UI half" of the `Editable` contract; the element
side is documented in `element-base.md` ("Editing contract", line
669-681).

```
CircuitElm (or EditOptions)         EditDialog
  implements Editable                 manages widgets
  │                                   │
  │   getEditInfo(0)                  │
  │◄────────────────────────────────┤
  │   EditInfo(name, value, min, max)│
  │────────────────────────────────►│
  │   … continue until null          │
  │                                   │
  │   user edits, clicks OK           │
  │                                   │
  │   setEditValue(0, editedInfo)     │
  │◄────────────────────────────────┤
  │                                   │
  │ (element mutates self fields)     │
  │                                   │
  └──── needAnalyze, rebuild? ──────►│
```

## Issues

1. **Fixed `einfos[10]` cap** (`EditDialog.java:69`). `buildDialog`'s
   `for (i=0;;i++)` loop with no upper bound will throw
   `ArrayIndexOutOfBoundsException` if any `Editable` returns non-null
   for `i >= 10`. Currently no Editable goes past 10 (EditOptions stops
   at 13 but only because `n==13` is conditional; rows 0-13 are more
   than 10). **Concrete bug**: `EditOptions` alone has 14 row slots
   (0-13). Double-check — count of non-null returns for EditOptions
   is 13 when `adjustTimeStep=true`, which **does** exceed the array.
   Either the array has been silently overrun with no reports, or the
   row numbering has gaps that keep the live count ≤10. Worth
   verifying at runtime.
2. **Cancel is not an undo.** Changes via Choice/Checkbox/Button are
   committed live inside `itemStateChanged` (line 329), before the
   user ever clicks OK or Cancel. The dialog's name suggests
   transactional semantics that are absent. (UX issue.)
3. **Silent parse failure.** `apply()` catches
   `java.text.ParseException` and does nothing (line 297). No user
   feedback, no logging. Typos in textboxes silently discard the
   edit.
4. **Variable-arg label styling.** Every row except the first gets
   `"topSpace"` style (line 126-127), including HTML rows — but the
   condition is `if (i != 0 && l != null)` which is always true in
   practice since both branches assign `l`. The `l != null` guard
   is dead code.
5. **Column-wrap threshold is hard-coded to 15** (line 179) with no
   configurability. For some dense chip dialogs this produces
   awkward splits.
6. **`closeOnEnter = false` is sticky** (line 162). Once any row has
   a `textArea`, the dialog loses "press Enter to OK" for its
   lifetime. Fine because the dialog is rebuilt each time, but
   interacts badly with rebuild-on-change if the rebuilt form has no
   textArea — the flag is not reset.
7. **Bidirectional element ↔ dialog coupling.** `EditDialog` imports
   `element.CircuitElm` and `element.VoltageElm` directly (lines
   42-43). The VoltageElm import exists *only* for the `instanceof`
   RMS special case in `unitString` — leaks element-specific
   formatting into the generic dialog. A cleaner design would put the
   RMS flag on `EditInfo` itself.
8. **`EditOptions` direct-writes to singletons.** Settings persist as
   a side effect of `setEditValue`, before Apply. Inconsistent with
   element editing where `setEditValue` writes to the element
   instance (which at least can be undone via the undo stack).
   Settings have no undo path.
9. **Language change forces full page reload** (line 179-181). No
   graceful language reload; relies on `Window.Location.reload()`.
10. **`EditDialogLoadFile` uses a **shared global element id**
    (`"EditDialogLoadFileElement"`, line 45). If two file-load rows
    existed in the same dialog, the JSNI `open()` would click the
    wrong one (document.getElementById returns first match). In
    practice there is only ever one.
11. **Adjustable coupling via cast** (line 304). `elm instanceof
    CircuitElm` check inside the generic dialog means `EditOptions`
    never gets slider-sync (which is correct, but the cast is
    implicit coupling).

## Concept boundary

The three files belong to a **single `edit-dialog-infrastructure`
concept** alongside the `dialog-base` analysis
(`Dialog`, `Editable`, `EditInfo`). Justification:

- `EditDialog` has no meaning without `Editable` + `EditInfo`
  (the data model it renders).
- `EditOptions` only exists to be fed into `EditDialog`.
- `EditDialogLoadFile` is purely a widget helper used by
  `EditDialog.buildDialog`.
- The three files have no internal cleavage; every file imports /
  couples to the others.

The concept is a **direct implementation of the Editable contract
defined in element-base**. Any reshuffle (e.g. extracting `Editable`
to element-base as recommended in `element-base.md` issue #2) would
span both concepts.

Clean cut candidates if finer granularity is required later:
1. `edit-dialog-core` — `EditDialog` + `EditInfo` + `Editable`
2. `edit-dialog-fileload` — `EditDialogLoadFile` (single subclass
   today, but a candidate for `Loadable` generalization)
3. `edit-dialog-options` — `EditOptions` (distinct because its
   concerns are app-global settings, not element fields)

The dependency graph does not require splitting — the three files
form one tight sub-SCC.
