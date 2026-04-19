# Module Analysis: domain-core / dialog-base

> **Path:** src/main/java/com/lushprojects/circuitjs1/client/dialog/ (3 base files)
> **Layer:** 2 (SCC-A, same cycle as `element-base`)
> **Analyzed:** 2026-04-18
> **Files:** 3 source files — `Dialog.java`, `Editable.java`, `EditInfo.java`

## Purpose

The `dialog-base` sub-unit defines the **parameter-editing contract
infrastructure** shared by every editable object in CircuitJS1. It is the
tiny but load-bearing core on top of which the whole element/option edit
pipeline is built. Three concerns, one file each:

1. **Dialog** (`Dialog.java:18`) — A thin **GWT `DialogBox` wrapper** that
   all application dialogs (`EditDialog`, `EditOptions`, `SliderDialog`,
   `ScrollValuePopup`, plus every file/import/export chooser) subclass.
   Adds: persisted position, viewport clamping, anchor-aware resize,
   collapse toggle, enter-to-apply hook, and a shared registry of
   showing dialogs. No editing logic of its own.
2. **Editable** (`Editable.java:3`) — A **two-method marker interface**
   (`getEditInfo(int n)` / `setEditValue(int n, EditInfo ei)`) that every
   object willing to expose itself to a property-edit dialog must
   implement. This is the single most reused contract in the project:
   implemented by `CircuitElm` (→ ~135 element classes),
   `CustomLogicModel`, `DiodeModel`, `TransistorModel`, and
   `EditOptions`.
3. **EditInfo** (`EditInfo.java:30`) — The **parameter-description DTO**.
   A single struct-like class that encodes every row ever shown in any
   parameter dialog, regardless of whether the underlying value is a
   number, string, choice, checkbox, color, button, file-picker, or
   opaque widget. Used bidirectionally: the owning `Editable` *builds*
   one to describe a parameter, and the dialog *fills in* the user's
   input before handing it back via `setEditValue`.

Together these three files constitute the **edit-info contract** — the
channel through which every user-facing parameter in CircuitJS1 flows.
The contract is described once here and consumed by four very different
subsystems (elements, models, options, slider), which is why the
element-base analysis flags the `element → dialog` edge as the
strongest coupling in the codebase (771 references to `EditInfo`
across 125 files).

## Key Entities

### Dialog (base popup window)

- **Type:** `public class Dialog extends com.google.gwt.user.client.ui.DialogBox`
- **File:** src/main/java/com/lushprojects/circuitjs1/client/dialog/Dialog.java:18
- **Size:** 373 LOC.
- **Role:** Common base for every dialog in the app. Extends GWT's
  built-in `DialogBox` (modal, draggable popup) and layers on policies
  that GWT does not provide out of the box: position persistence,
  viewport-aware repositioning on window resize, collapse/expand,
  Enter-to-apply.

#### Fields

| Field | Type | Purpose |
|---|---|---|
| `closeOnEnter` | `boolean` (pkg-private) | If true, pressing Enter calls `apply()` + `closeDialog()` — used by most subclasses. Line 33, 51. |
| `positionRestored` | `boolean` (pkg-private) | True after a persisted position was loaded from storage. Set inside `loadPosition()`. Line 34, 140. |
| `positionExplicitlySet` | `boolean` (private) | True once a caller invoked the public `setPopupPosition(...)`. Used to suppress the "center on first show" default. Line 36, 58. |
| `horizontalAnchor` / `verticalAnchor` | `HorizontalAnchor` / `VerticalAnchor` enum | Which screen corner the dialog is anchored to. Recomputed on drag-end; drives `applyAnchorsAfterResize`. Lines 20-28, 37-38. |
| `horizontalAnchorOffsetPx` / `verticalAnchorOffsetPx` | `int` | Pixel offset from the chosen anchor (left/right or top/bottom). Lines 39-40. |
| `collapsed` | `boolean` | Whether the dialog body is hidden (only the caption shows). Line 42, 324-341. |
| `collapseToggleElement` | `com.google.gwt.dom.client.Element` | The caption-bar "-" / "+" span the dialog injects itself. Line 43, 278-307. |
| `resizeHandlerInstalled` | `static boolean` | Guards one-time `Window.addResizeHandler` install. Line 30. |
| `showingDialogs` | `static Set<Dialog>` (`HashSet`) | Registry of all *visible* Dialog instances; resize handler walks it to re-anchor everyone. Line 31. |

#### Public / protected API (overridable hooks)

- `Dialog()` (line 45) — no-arg, delegates to `Dialog(false, true)` (non-autoHide, modal).
- `Dialog(boolean autoHide, boolean modal)` (line 49) — sets
  `closeOnEnter = true`, calls `loadPosition()` and `loadCollapsedState()`.
- `setPopupPosition(int left, int top)` override (line 57) — marks
  `positionExplicitlySet = true` so the deferred auto-center is skipped.
- `show()` override (line 63) — calls `super.show()`, registers with
  `showingDialogs`, then schedules deferred: `ensureInitialPosition`,
  `clampIntoViewport`, `updateAnchorsFromCurrentPosition`,
  `ensureCollapseToggle`, `applyCollapsedState`.
- `endDragging(MouseUpEvent)` override (line 78) — on drag release:
  clamp into viewport, re-derive anchors, persist position.
- `closeDialog()` (line 85) — convenience wrapper over `hide()`.
- `hide(boolean autoClosed)` override (line 90) — saves position +
  collapsed state, unregisters from `showingDialogs`, then delegates.
- `enterPressed()` (line 97) — if `closeOnEnter`, calls
  `apply() + closeDialog()`. Intended hook for Enter key on text fields.
- `apply()` (line 104, pkg-private) — **empty default**. Subclasses
  override to commit the dialog's edits.
- `getOptionPrefix()` (line 108, protected) — **returns `null`** by
  default. Subclasses that want position/collapsed-state persistence
  override this to return a unique storage prefix (e.g. `"edit.r"`).
- `isPositionRestored()` (line 112) — read-only accessor.

#### Private helpers (position + collapse)

- `savePosition()` / `loadPosition()` (lines 116-148) — persist
  `left,top` as a comma-separated string under
  `OptionsManager.getPrefixedKey(prefix, "pos")`.
- `ensureInitialPosition()` (line 150) — centers the dialog on first
  show when there is no persisted/explicit position (overrides GWT's
  top-left default).
- `clampIntoViewport()` (line 167) — guards against a dialog that was
  persisted off-screen from a previous, larger window.
- `updateAnchorsFromCurrentPosition()` (line 188) — picks LEFT vs RIGHT
  and TOP vs BOTTOM by whichever edge is closer, storing the offset.
- `applyAnchorsAfterResize()` (line 221) — called by the global resize
  handler; reprojects each dialog relative to its anchor then clamps.
- `registerShowingDialog` / `unregisterShowingDialog` / `ensureResizeHandler`
  (lines 249-276) — static registry + one-time `Window.addResizeHandler`
  install that iterates `showingDialogs` and re-anchors each.
- `ensureCollapseToggle()` / `findCaptionElement()` / `setCollapsed(boolean)` /
  `applyCollapsedState()` / `updateCollapseToggleText()` (lines 278-348) —
  DOM-injected "-" / "+" span in the GWT Caption element; toggling hides
  the widget body; state is persisted via `saveCollapsedState` /
  `loadCollapsedState` (lines 350-371).

#### Dialog invariants / integration contract

- Every dialog that wants its position remembered **must** override
  `getOptionPrefix()` to return a stable, unique string. Without a
  prefix, `savePosition` / `loadPosition` / `saveCollapsedState` /
  `loadCollapsedState` short-circuit (lines 118, 129, 353, 366).
- The collapse toggle is injected lazily *after* GWT attaches the
  dialog (deferred in `show()`); it looks for an element whose class
  contains `"Caption"` via `findCaptionElement()` (line 309-321) — a
  brittle string match against GWT internals.
- `endDragging` calls `savePosition` but **not** `saveCollapsedState` —
  collapse state is only saved when the dialog hides or the user
  toggles it (`setCollapsed` → `saveCollapsedState`).
- The static `showingDialogs` registry leaks entries if a subclass
  hides via a path other than `hide(boolean)` — however, GWT's
  `DialogBox.hide()` always funnels through the overload, so in
  practice the registry stays consistent.

### Editable (marker interface)

- **Type:** `public interface Editable`
- **File:** src/main/java/com/lushprojects/circuitjs1/client/dialog/Editable.java:3
- **Size:** 7 LOC (the whole file).
- **Contract (two methods, no default implementation):**
  ```java
  EditInfo getEditInfo(int n);            // line 4
  void     setEditValue(int n, EditInfo ei);  // line 6
  ```
- **Semantics:**
  - `getEditInfo(n)` — produce the n-th row of the edit dialog.
    Implementations walk n = 0, 1, 2, … and return `null` to signal
    end-of-list. The returned `EditInfo` is mutated by the dialog in
    place (user types into `textf`, selects from `choice`, toggles
    `checkbox`, etc.).
  - `setEditValue(n, ei)` — called on OK / apply with the *same* index
    and the *same* (now-populated) `EditInfo` the implementor handed
    out. Implementations parse the user's input out of the `EditInfo`
    fields and mutate internal state.
- **Implementers (7 direct):**
  - `CircuitElm` (abstract, element-base) — transitive implementer for
    ~135 concrete element classes.
  - `EditOptions` (dialog/EditOptions.java:32) — the global "Options"
    dialog's data source.
  - `CustomLogicModel` (CustomLogicModel.java:14) — user-programmable
    logic chip definition.
  - `DiodeModel` (DiodeModel.java:14) — named diode model registry entry.
  - `TransistorModel` (TransistorModel.java:13) — named transistor model.
  - (`DialogManager` imports `Editable` to type its arguments but is
    not itself an `Editable`.)
- **Nominal coupling note:** the interface lives in `dialog/` but is
  implemented by every element in `element/` — this is the dominant
  `element → dialog` edge in the dependency graph (see element-base
  analysis, Issue #2). A cleaner layering would relocate `Editable` +
  `EditInfo` to a neutral contract package so `element/` can depend on
  it without knowing about the `Dialog` UI layer.

### EditInfo (parameter-description DTO)

- **Type:** `public class EditInfo`
- **File:** src/main/java/com/lushprojects/circuitjs1/client/dialog/EditInfo.java:30
- **Size:** 113 LOC.
- **Role:** The **universal edit-dialog row carrier.** It is not a
  Strategy or Variant — it is a **bag of fields** where the populated
  subset tells the dialog which kind of UI control to render. Because
  there is no Kind enum, "which kind of row is this?" is answered by
  a series of null-checks in `EditDialog` / `EditOptions` /
  `SliderDialog`.

#### Fields (the complete set)

| Field | Type | Meaning |
|---|---|---|
| `name` | `String` | Row label shown to the user (localized via `Locale.LS` by callers). |
| `text` | `String` | For "text"-kind rows: initial content, replaced by user input on apply. |
| `value` | `double` | For "number"-kind rows: initial numeric value, replaced by user input on apply. |
| `textf` | `TextBox` | The GWT text input; populated by `EditDialog` when rendering a numeric or short-text row. |
| `choice` | `Choice` | GWT combobox wrapper (from `client.Choice`). Non-null → row is a dropdown. |
| `checkbox` | `Checkbox` | GWT checkbox wrapper (from `client.Checkbox`). Non-null → row is a boolean toggle. |
| `button` | `Button` | GWT button. Non-null → row is an action button (e.g. "Edit Pin Layout"). |
| `loadFile` | `EditDialogLoadFile` | If non-null, the `button` above opens a file-loading dialog (callback interface). Line 38. |
| `textArea` | `TextArea` | Multi-line text widget (large scripts/comments). |
| `widget` | `Widget` | Escape hatch — any opaque GWT `Widget` the caller wants embedded. Line 40. |
| `newDialog` | `boolean` | When true, signals that clicking the button should spawn a sub-dialog (e.g. pin-layout editor). Line 41. |
| `dimensionless` | `boolean` | Suppresses SI-unit formatting / parsing on the numeric value. Line 42. |
| `noSliders` | `boolean` | Prevents the user from attaching a live slider to this row (via right-click → Slider). Line 43. |
| `minVal`, `maxVal` | `double` | Allowed range for numeric values; also seeds slider min/max on creation. Line 44. |
| `isColor` | `boolean` | When true, the numeric value is a 24-bit color and the dialog shows a color picker instead of a text box. Line 45. |
| `unit` | `String` | Optional unit suffix (e.g. `"Ω"`, `"V"`, `"Hz"`). Line 46. |
| `minBox`, `maxBox`, `labelBox` | `TextBox` | Extra fields used only by `SliderDialog` (slider min / slider max / slider label). Line 49. |

#### Constructors

- `EditInfo(String n, double val, double mn, double mx)` (line 51) — full numeric row (no unit).
- `EditInfo(String n, double val, double mn, double mx, String u)` (line 55) — full numeric row with unit.
- `EditInfo(String n, double val)` (line 64) — numeric row with no range.
- `EditInfo(String n, String txt)` (line 70) — text row (also sets
  `dimensionless = noSliders = true`, which is the convention for
  "this is a label/string, don't try to treat it as a physical quantity").

#### Static factories / builders

- `EditInfo.createCheckbox(String name, boolean flag)` (line 76) —
  builds a checkbox row with an empty `name` (the `Checkbox` itself
  carries the label). Used heavily by elements that expose a single
  boolean flag in the dialog.
- `setDimensionless()` (line 82) — fluent setter (returns `this`).
- `disallowSliders()` (line 87) — fluent setter (returns `this`).
- `changeFlag(int flags, int bit)` (line 92) — convenience for
  checkbox rows that toggle a single bit in the element's `flags`
  integer. Returns the updated flag set based on `checkbox.getState()`.
- `changeFlagInverted(int flags, int bit)` (line 98) — same, inverted
  semantics (bit is SET when checkbox is UNchecked).
- `canCreateAdjustable()` (line 104) — predicate: true iff this row is
  a plain numeric value (no choice/checkbox/button/textArea/widget/no-sliders).
  Gates the right-click "Create Adjustable/Slider" menu item.
- `makeLink(String file, String text)` (line 109, static) — utility
  to build an HTML `<a>` tag with localized text; used inside dialogs
  that embed a help link.

## Public Contracts

### `Editable` — the two-method handshake

The flow between an `Editable` (element or model) and `EditDialog` is
always a **numbered request/response loop**, with the `EditInfo`
instance serving as both request schema and response carrier:

```
EditDialog                              Editable (e.g. CircuitElm)
-----------                             ------------------------------
build()  ─── getEditInfo(0) ─────────▶  return new EditInfo("Resistance",
                                               r, 0, 0);
  ◀── EditInfo (with name/value/...) ─
render row 0 → TextBox / Checkbox /
  Choice / Button / TextArea / etc.
  (determined by which EditInfo
   fields are non-null)

build()  ─── getEditInfo(1) ─────────▶  return EditInfo.createCheckbox(
                                           "Show current", ...);
  ◀── EditInfo (with checkbox) ──────

build()  ─── getEditInfo(2) ─────────▶  return null     ← end-of-list
  ◀── null ──────────────────────────

user edits, clicks Apply:

apply()  ─── setEditValue(0, ei0) ───▶  r = ei0.value;
apply()  ─── setEditValue(1, ei1) ───▶  flags = ei1.changeFlag(flags, FLAG_SHOWCURRENT);
```

Key invariants:

1. **The `EditInfo` instance passed to `setEditValue(n, ei)` is the
   *same object* that was returned from `getEditInfo(n)`.** Dialogs
   mutate it in place; implementors can therefore rely on the
   associated widgets (`textf`, `choice`, `checkbox`) being populated.
2. **Row indices must be stable** between the two calls — the implementor
   is expected to build the same schema in the same order on both the
   build-request pass and the commit pass.
3. **Returning `null` terminates the row list.** Dialogs call
   `getEditInfo(n)` in order starting at `n = 0` until null is returned.
4. **There is no mandatory "kind" field.** The dialog infers the UI
   control by probing the field set. The precedence (observed in
   `EditDialog`) is roughly: `choice` > `checkbox` > `button` >
   `textArea` > `widget` > numeric/text via `textf`.

### `Dialog` — lifecycle contract

Subclasses typically override:

- `getOptionPrefix()` — enables position + collapsed-state persistence
  (otherwise the methods no-op).
- `apply()` — commit action triggered by Enter or an OK button.
- Optionally `show()` — to populate child widgets before the deferred
  positioning runs.

The base class guarantees, between `show()` and the first user
interaction:

1. The dialog is attached to the DOM (`super.show()`).
2. `ensureInitialPosition` centers the dialog if not persisted/explicit.
3. `clampIntoViewport` keeps it visible even if the window shrank.
4. `updateAnchorsFromCurrentPosition` picks the nearest corner.
5. `ensureCollapseToggle` injects the "-" / "+" caption control.
6. `applyCollapsedState` hides/shows the body based on persisted state.

On window resize: every dialog in `showingDialogs` is re-anchored via
`applyAnchorsAfterResize` + `clampIntoViewport` (deferred one scheduler
tick to let GWT finish re-laying-out).

## EditInfo value types (catalog of kinds)

Since `EditInfo` has no Kind enum, the *effective* type is the
(non-null field) signature of the populated instance. The catalog
below is the complete set of "kinds" observed across the codebase:

| Kind | Populated fields | Used for | Built by |
|---|---|---|---|
| **Number (with range)** | `name`, `value`, `minVal`, `maxVal`, optional `unit` | Most element properties: resistance, capacitance, voltage, frequency, etc. | `new EditInfo(n, val, mn, mx[, u])` |
| **Number (no range)** | `name`, `value` | Values with no meaningful min/max (legacy path) | `new EditInfo(n, val)` |
| **Dimensionless number** | `name`, `value`, `dimensionless=true` | Integer counts, gains, turns ratios — no SI formatting | `.setDimensionless()` |
| **No-slider number** | `name`, `value`, `noSliders=true` | Numeric value that must not become a live slider target | `.disallowSliders()` |
| **Text / String** | `name`, `text`, `dimensionless=true`, `noSliders=true` | Labels, names, descriptions | `new EditInfo(n, txt)` |
| **Text area** | `name`, `textArea` | Multi-line content (scripts, comments) | set `textArea` after construction |
| **Choice / Dropdown** | `choice` (`Choice` wrapper) | Enumerated values: integration method, shape, model selector | set `choice` after construction |
| **Checkbox** | `checkbox` (`Checkbox` wrapper) | Boolean flags; often paired with `changeFlag(flags, BIT)` in `setEditValue` | `EditInfo.createCheckbox(name, flag)` |
| **Button (action)** | `button` | Opens sub-editor, triggers an operation | set `button` after construction |
| **Button (newDialog)** | `button`, `newDialog=true` | Button that launches a nested edit dialog | set both |
| **Button (load-file)** | `button`, `loadFile` | File-chooser button (e.g. import diode model from file) | set both |
| **Color** | `name`, `value`, `isColor=true` | Color picker; `value` carries a 24-bit RGB int | set `isColor=true` after construction |
| **Opaque widget** | `widget` | Anything else (e.g. a custom graph preview, a link, a composite control) | set `widget` after construction |
| **Link** | `name`, `text = makeLink(...)` | HTML-link row rendered as raw HTML | `EditInfo.makeLink(file, text)` |
| **Slider config** | `minBox`, `maxBox`, `labelBox` | Used only inside `SliderDialog` to configure a live slider attached to an `EditInfo` | populated by `SliderDialog` |

### Change-notification flow

There is **no observer/listener pattern** baked into `EditInfo`. The
change-notification model is pull-based and synchronous:

1. User interacts with widgets inside the dialog (keystrokes, combo
   selection, checkbox toggle). GWT updates the widget state.
2. User presses Enter (→ `Dialog.enterPressed` → `apply()`) or clicks
   Apply/OK (subclass-specific handler).
3. The dialog iterates its `EditInfo[]` rows and, for each, calls
   `editable.setEditValue(n, ei)` with the mutated `EditInfo`.
4. `setEditValue` reads `ei.value` / `ei.text` / `ei.checkbox.getState()` /
   `ei.choice.getSelectedIndex()` / etc., and mutates the element's
   fields. Subclasses are responsible for re-triggering any derived
   state (e.g. calling `setPoints()`, recomputing geometry, flagging
   the circuit dirty via `circuitDocument.setModified(true)`).
5. `EditDialog` itself (outside this module) observes per-keystroke
   changes for numeric fields so a live slider preview can update; but
   from the `Editable`'s point of view, `setEditValue` is the **only**
   sync point.

No dirty-tracking, diffing, or undo bookkeeping lives inside `EditInfo`
or `Editable`. Any "did anything change?" logic is the dialog's
concern. This keeps the `Editable` contract small at the cost of
duplicated "commit vs. cancel" logic in each dialog subclass.

## Integration Points

### Depends on

- **GWT (external):** `com.google.gwt.user.client.ui.{DialogBox, Button,
  TextArea, TextBox, Widget}`, `com.google.gwt.dom.client.{Document,
  Element}`, `com.google.gwt.event.dom.client.MouseUpEvent`,
  `com.google.gwt.event.logical.shared.{ResizeEvent, ResizeHandler}`,
  `com.google.gwt.user.client.{Event, Window}`,
  `com.google.gwt.core.client.Scheduler`.
- **Root widgets (Layer 0):** `client.Choice`, `client.Checkbox`,
  `client.OptionsManager` (used by `Dialog` for position/collapsed
  persistence, lines 13, 121-122, 130-131, 356-357, 369-370).
- **Util (Layer 0):** `client.util.Locale.LS(...)` (inside
  `EditInfo.makeLink`, line 110).
- **Peer in same package:** `EditInfo` references
  `EditDialogLoadFile` (same `dialog/` package) as the `loadFile`
  callback type. Line 38.

### Used by

This sub-unit has **no internal implementers** — the whole point is
to publish a contract that other subsystems implement:

- **Every editable element** (`element-base`, `element/*`): ~135
  classes transitively via `CircuitElm implements Editable`. The
  element override points `getEditInfo(int)` / `setEditValue(int, EditInfo)`
  live on `CircuitElm.java:1200`/`1204` (default no-ops) and are
  overridden by most concrete element classes.
- **Dialog subsystem (peers in `dialog/`):**
  - `EditDialog` — the generic per-element / per-model property
    dialog. Uses `Editable` as the dialog's data-source interface and
    iterates `getEditInfo(n)` until null.
  - `EditOptions` — global options dialog; itself `implements Editable`
    (EditOptions.java:32) and serves its own schema.
  - `SliderDialog` — the "attach a slider" configurator; consumes
    `EditInfo.minBox / maxBox / labelBox`.
  - `ScrollValuePopup` — value-nudge popup; takes an `EditInfo` to
    apply deltas against.
- **Models / registries:** `DiodeModel`, `TransistorModel`,
  `CustomLogicModel` all `implement Editable` to expose themselves
  in the "Edit <ModelType>" dialog.
- **OptionsManager / EditOptions coupling:** `EditOptions` is both a
  `Dialog` subclass (for the UI) and an `Editable` (as its own data
  source). `Dialog` also uses `OptionsManager` directly for persisting
  position and collapsed state — this is the only direct coupling
  between the base dialog class and the options subsystem.
- **`DialogManager`** (root) imports `Editable` (DialogManager.java:11)
  to type the argument it forwards into dialog constructors.
- **Aggregate coupling:** 771 `EditInfo`-reference lines across 125
  files (Grep count) — confirms this is the most-used DTO in the
  codebase.

### External deps

- GWT Widget/DOM/Event APIs (canvas is not touched here).
- Browser `Window` for viewport dimensions.
- No threading, no reflection, no GWT `JavaScriptObject` bridge.

## Issues

1. **`EditInfo` is a field-bag with no Kind enum.** The "which control
   should I render?" decision is spread across the dialogs as a
   cascade of `if (ei.choice != null) ... else if (ei.checkbox != null) ...`.
   Adding a new row kind requires edits in every consumer; the
   precedence is implicit. A proper `sealed interface EditInfoKind`
   (or even a `Kind` enum + union) would localize the rendering
   dispatch. (`EditInfo.java:30-50`)
2. **Bidirectional coupling `element → dialog`.** `Editable` lives in
   `dialog/` but is implemented by `CircuitElm` and ~5 non-dialog
   classes. A neutral `contract/` package (or move to `element/`)
   would invert the dependency and let `element/` compile without the
   dialog widgets. (`Editable.java:3`, cross-referenced in
   `element-base.md` Issue #2.)
3. **Mutation in place via widget aliasing.** `EditInfo.textf`,
   `EditInfo.choice`, etc. are public widgets that the dialog writes
   into; the implementor reads from them. This tangles lifecycle —
   the `EditInfo` is live as long as the dialog is, then discarded.
   There is no safe way to inspect an `EditInfo` after dialog close.
   (`EditInfo.java:34-40`)
4. **`Dialog.findCaptionElement()` matches a class-name substring
   (`"Caption"`).** Brittle against GWT version upgrades; if GWT ever
   renames its caption CSS class, the collapse toggle silently
   disappears. (`Dialog.java:309-321`)
5. **`Dialog.hide(boolean)` assumes `hide()` always funnels through
   it.** If a subclass or GWT path ever calls a sibling hide (e.g.
   destroying the popup without `hide(boolean autoClosed)`), the
   dialog stays in `showingDialogs` and the resize handler will
   forever iterate a dead reference. (`Dialog.java:90-95, 249-276`)
6. **`savePosition` writes `left,top` as a plain string.** No versioning,
   no schema; any change in storage format will silently lose
   position. (`Dialog.java:121-122`)
7. **`EditInfo.createCheckbox` returns an instance with `name = ""`**.
   The label lives on the `Checkbox` itself, meaning the checkbox row
   is the only row whose label is *not* carried by `EditInfo.name`.
   Renders correctly but asymmetrically. (`EditInfo.java:76-80`)
8. **`getEditInfo(n)` returning `null` is the only way to signal
   "no more rows".** No `getEditInfoCount()`, no iterator. Any new
   row count must be recomputed by re-walking from n=0 — every dialog
   refresh does O(N) allocations of fresh `EditInfo` objects even
   when nothing changed. (`Editable.java:4-6`)
9. **`canCreateAdjustable()` hard-codes the "row is a plain number"
   predicate** by excluding `choice/checkbox/button/textArea/widget/noSliders`.
   Adding a new kind (e.g. `isColor`) would silently become slider-able
   unless the predicate is updated. (`EditInfo.java:104-107`)
10. **Enter-to-apply vs. multi-line text area conflict.** `Dialog.closeOnEnter`
    defaults to true; if a subclass exposes a `TextArea`, Enter submits
    the dialog rather than inserting a newline. Dialogs with text areas
    must remember to flip `closeOnEnter = false` manually.
    (`Dialog.java:33, 97-102`)

## Suggested concept boundary

A **single `edit-info-contract` concept** covering all 3 files is the
right granularity. Rationale:

- The three files collaborate around one story: *"how an object exposes
  its editable parameters to a dialog."* `Dialog` is the base widget,
  `Editable` is the contract, `EditInfo` is the data format — they
  are meaningless apart.
- All three have identical consumer sets (`EditDialog`, `EditOptions`,
  `SliderDialog`, `ScrollValuePopup`, every element, every named
  model).
- The value of the concept document is the `EditInfo` kind catalog +
  the numbered-row handshake; both require all three files in view.
- The existing element-base analysis already cites this concept as
  `dialog.{Editable, EditInfo}` — treating it as one concept keeps
  the cross-references clean.

If a finer split were ever required:
1. **edit-info-contract** (core) — `Editable` + `EditInfo`.
2. **dialog-base-widget** — `Dialog` (the GWT wrapper with
   positioning/collapse policies), potentially generalizable beyond
   edit dialogs (file pickers, confirmation prompts).

But nothing in the current codebase uses `Dialog` without also touching
`Editable`/`EditInfo`, so the single-concept boundary is correct.
