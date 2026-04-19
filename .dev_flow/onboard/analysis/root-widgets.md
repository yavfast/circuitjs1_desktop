# Module Analysis: root-widgets

> **Path:** src/main/java/com/lushprojects/circuitjs1/client/ (5 selected files)
> **Layer:** 1
> **Analyzed:** 2026-04-18
> **Files:** 5 source files, 0 test files

## Purpose

These five classes are thin GWT-widget adapters that preserve AWT/Swing-style naming
(`Checkbox`, `Choice`, `Scrollbar`) from the pre-GWT origin of the codebase (Paul
Falstad's original Java applet). They sit at client root rather than in
`client/ui/` because:

1. They predate the nascent `ui/` package (only `ui/tabs/` is populated today).
2. They expose legacy AWT-style APIs (`getState()/setState()`, `add(String)`,
   `select(int)`) that let the broad callsite corpus (~64 files across `<root>`,
   `dialog/`, `element/`, `element/waveform/`) remain AWT-flavoured despite the
   GWT port.
3. `CheckboxMenuItem` and `Scrollbar` are not pure pass-throughs — they add
   behaviour (rendered checkmark glyph in menus, custom canvas-drawn scrollbar
   with mouse/touch handling). Moving them into `ui/` would require redirecting
   imports across the entire codebase.

Source files carry the historical header `Copyright (C) Paul Falstad and Iain Sharp`.

## Key Entities

| Class | Extends / Implements | File:line | Non-trivial state | Invariants |
|---|---|---|---|---|
| `Checkbox` | `com.google.gwt.user.client.ui.CheckBox` | `Checkbox.java:25` | none (pure wrapper) | — |
| `CheckboxMenuItem` | `com.google.gwt.user.client.ui.MenuItem` implements `com.google.gwt.user.client.Command` | `CheckboxMenuItem.java:25` | `cirSim:BaseCirSim`, `on:boolean`, `name:String`, `shortcut:String`, `extcmd:Command`, `static checkBoxHtml:String` | `on` toggles on each `execute()`; HTML re-rendered via `setHTML` so menu label always reflects `on` + `name` + `shortcut` |
| `CheckboxAlignedMenuItem` | `com.google.gwt.user.client.ui.MenuItem` | `CheckboxAlignedMenuItem.java:26` | none (constructor-only wrapper) | Renders an empty-checkbox-width spacer so non-checkable items align with checkable ones in the same menu |
| `Choice` | `com.google.gwt.user.client.ui.ListBox` | `Choice.java:25` | none (pure wrapper) | — |
| `Scrollbar` | `com.google.gwt.user.client.ui.Composite` implements 10 GWT mouse/touch/wheel handler interfaces | `Scrollbar.java:55-57` | `cirSim:BaseCirSim`, `can:Canvas`, `pan:VerticalPanel`, `g:Context2d`, `min/max/val:int`, `dragging:boolean`, `enabled:boolean`, `command:Command`, `attachedElm:CircuitElm` | `min ≤ val ≤ max` enforced in `calcValueFromPos` and `setValue`; `dragging=false` whenever `enabled=false`; fixed coord-space width 150, height `SCROLLHEIGHT=14` |

## Public Contracts

### Checkbox (pass-through)

GWT primitive wrapped: `com.google.gwt.user.client.ui.CheckBox`. All event wiring
(`addValueChangeHandler`, `addClickHandler`, etc.) inherited unchanged.

- `Checkbox(String s)` — label runs through `Locale.LS(s)` for i18n (`Checkbox.java:26`).
- `Checkbox(String s, boolean b)` — same, plus initial checked state (`:30`).
- `boolean getState()` — alias for `getValue()` (`:35`).
- `void setState(boolean s)` — alias for `setValue()` (`:39`).

Customization: i18n of label + AWT-style `getState/setState` aliases. Otherwise
pass-through.

### CheckboxMenuItem (customised)

GWT primitive wrapped: `com.google.gwt.user.client.ui.MenuItem`. Implements GWT
`Command` so it can be its own scheduled command. **No i18n** — the passed
`String s` is used as-is (callers in `MenuManager` / `ScopePopupMenu` localize it
themselves).

- `CheckboxMenuItem(BaseCirSim cirSim, String s)` — plain checkable item with no
  external action (`CheckboxMenuItem.java:36`).
- `CheckboxMenuItem(BaseCirSim cirSim, String s, Command cmd)` — toggles and
  invokes `cmd` on activation (`:44`).
- `CheckboxMenuItem(BaseCirSim cirSim, String s, String c, Command cmd)` — adds
  a shortcut string displayed at the right of the label (`:53`).
- `CheckboxMenuItem(BaseCirSim cirSim, String s, String c)` — shortcut without
  external command (`:58`).
- `String getName()` (`:33`), `String getShortcut()` (`:34`).
- `void setShortcut(String s)` (`:63`).
- `void setTitle(String s)` — updates `name`; **does not re-render**; callers
  must call `setState(on)` afterward to refresh HTML (`:77-79`).
- `void execute()` (Command contract) — toggles `on`, invokes `extcmd` if
  present, then calls `cirSim.repaint()` (`:67`).
- `void setState(boolean newstate)` — updates `on` and re-renders HTML
  checkmark/shortcut (`:81`).
- `boolean getState()` (`:101`).
- `static String checkBoxHtml` — shared HTML prefix reused by
  `CheckboxAlignedMenuItem` (`:31`).

Customisation beyond wrapping: unicode check-glyph (`&#10004;`) rendered inside
a 15px inline-block; shortcut text placed absolutely at the right; on toggle
triggers `cirSim.repaint()` after user command runs.

Listener/event API: **not a handler registration model** — clients pass a GWT
`Command` to the constructor; `execute()` is the sole activation callback.

### CheckboxAlignedMenuItem (pass-through + spacer)

GWT primitive wrapped: `com.google.gwt.user.client.ui.MenuItem`. Single
constructor prepends `CheckboxMenuItem.checkBoxHtml + "&nbsp;</div>"` so
uncheckable menu items visually align with checkable siblings in the same
menu.

- `CheckboxAlignedMenuItem(String s, Command cmd)` — label `s` is used as-is
  (no `Locale.LS`), wrapped with `SafeHtmlUtils.fromTrustedString`
  (`CheckboxAlignedMenuItem.java:28`).

### Choice (pass-through)

GWT primitive wrapped: `com.google.gwt.user.client.ui.ListBox`. All selection
events inherited unchanged.

- `Choice()` (`Choice.java:27`).
- `void add(String s)` — adds one item, i18n-translated via `Locale.LS(s)`
  (`:31`).
- `void select(int i)` — delegates to `setSelectedIndex(i)` (`:35`).

Customisation: `add()` runs label through i18n and provides an AWT-style name;
`select()` is an AWT-style alias.

### Scrollbar (fully custom)

GWT primitive wrapped: `com.google.gwt.user.client.ui.Composite` hosting a
`VerticalPanel` that contains a `Canvas`. **Not** a wrapper around
`com.google.gwt.user.client.ui.ScrollBar` — this is a hand-drawn scrollbar
rendered onto a 150x14 canvas and hooked to ten GWT event interfaces directly.

Public constants (all `public static int`, `Scrollbar.java:59-63`):
- `HORIZONTAL = 1` — orientation flag accepted by ctor; never read internally.
- `HMARGIN = 2`, `SCROLLHEIGHT = 14`, `BARWIDTH = 3`, `BARMARGIN = 3` — layout
  constants used in `draw()` and hit-testing.

Constructors:
- `Scrollbar(BaseCirSim cirSim, int orientation, int value, int visible, int minimum, int maximum)`
  — base ctor; registers all mouse, wheel, and touch handlers; initial
  `draw()` (`:77`). The `orientation` and `visible` args are accepted for
  AWT-compatibility only (never used in the body).
- `Scrollbar(... Command cmd, CircuitElm e)` — attaches an external
  `CircuitElm` so hover/leave reports mouse-element to `cirSim.setMouseElm`
  and the bar colour tracks `attachedElm.needsHighlight()` (`:107`).
- `Scrollbar(... Command cmd)` — same as base ctor plus a command, no attached
  element (`:114`).

Public methods:
- `void draw()` — clears canvas, draws left/right arrows, track, value bar,
  knob; uses `ColorSettings.get().getSelectColor()` when attached element
  needs highlight (`:119`).
- `int getValue()` (`:296`).
- `void setValue(int i)` — clamps to `[min,max]`, redraws, fires `command`
  (`:300`).
- `void enable()` (`:313`), `void disable()` — clears dragging, redraws
  greyed-out (`:318`).

GWT handler methods (10, all public, implementing the respective
`*Handler` interfaces):
- `onMouseDown(MouseDownEvent)` (`:183`) — arrow-click decrements/increments
  `val`; mid-track click jumps knob and starts drag; calls
  `Event.setCapture(can.getElement())`.
- `onMouseMove(MouseMoveEvent)` (`:219`) — if `dragging` but no buttons down
  (JSNI `noButtonsDown`), release capture and stop; else update `val` while
  dragging.
- `onMouseUp(MouseUpEvent)` (`:243`) — finalises `val`, releases capture,
  fires `command`.
- `onMouseOut(MouseOutEvent)` (`:256`) — clears `cirSim.setMouseElm(null)`
  when leaving and a mouse-element is attached.
- `onMouseOver(MouseOverEvent)` (`:265`) — sets `cirSim.setMouseElm(attachedElm)`.
- `onMouseWheel(MouseWheelEvent)` (`:271`) — wheel adjusts `val` by
  `deltaY/3`.
- `onClick(ClickEvent)` (`:277`) — currently empty (all logic moved to
  mouse-down); retained to satisfy interface.
- `onTouchStart/Move/End/Cancel` (`:348/:324/:331/:342`) — mirror mouse
  handlers via `Touch.getRelativeX`; `dragging=false` on cancel.

Private/package methods:
- `int calcValueFromPos(int x)` (`:173`) — maps canvas-x to clamped `val`.
- `void doMouseDown(int x, boolean mouse)` (`:190`) — shared mouse+touch
  mouse-down body.
- `void doMouseMove(int x)` (`:232`) — shared drag body.
- `native boolean noButtonsDown(NativeEvent e)` — JSNI check of
  `NativeEvent.buttons` (`:215`).

Listener/event API: single-callback `Command command` supplied to the
2-arg/3-arg constructor; fires on every value change (mouse-down jump, drag,
mouse-up, wheel, touch-end, programmatic `setValue`). No multi-listener
registration.

## Validation Rules

- `Scrollbar.calcValueFromPos` clamps result to `[min,max]`
  (`Scrollbar.java:176-179`).
- `Scrollbar.setValue` clamps the incoming argument the same way
  (`:301-305`).
- `Scrollbar.onMouseDown` decrements only if `val > min` and increments only
  if `val < max` (`:192-198`).
- `CheckboxMenuItem.shortcut` empty-string check uses `!=` reference
  comparison (`:90`) — works only because literals share interned strings;
  flagged under Issues.
- `Scrollbar.orientation` and `Scrollbar.visible` constructor parameters are
  never read — no validation, no effect (`:77`).

## State Transitions

- **`Checkbox`** — boolean checked state owned by underlying GWT `CheckBox`;
  no locally tracked state.
- **`CheckboxMenuItem`** — `on` flag flips on every `execute()`; HTML re-
  rendered by `setState`. Initial state = `false`.
- **`CheckboxAlignedMenuItem`** — no state (decorative spacer only).
- **`Choice`** — selected index is owned by GWT `ListBox`.
- **`Scrollbar`** — `val ∈ [min,max]` with transitions: arrow tap
  (`val ± 1`), track click (jump to pointer + begin drag), drag move
  (follow pointer), wheel (`val += deltaY/3`), programmatic `setValue`
  (clamp). `dragging` flag gates drag-move updates; `enabled` gates every
  input path. On `disable()` both `enabled=false` and `dragging=false`.

## Integration Points

- **Depends on (application):**
  - `com.lushprojects.circuitjs1.client.util.Locale` — `Checkbox`, `Choice`.
  - `com.lushprojects.circuitjs1.client.BaseCirSim` — `CheckboxMenuItem`,
    `Scrollbar` (for `repaint()` / `setMouseElm()`).
  - `com.lushprojects.circuitjs1.client.element.CircuitElm` — `Scrollbar`
    (for `needsHighlight()`, `isMouseElm()`, the attached element model).
  - `com.lushprojects.circuitjs1.client.ColorSettings` — `Scrollbar.draw()`
    (`:153`).
  - `CheckboxAlignedMenuItem` → `CheckboxMenuItem.checkBoxHtml` (in-module).
  - **No** dependency on `Point`, `Rectangle`, `Color`, or other root-utils
    types.

- **Used by (package granularity, grepped across `src/main/java`):**
  - `Checkbox` — **56 occurrences in 35 files**:
    - `<root>` (2 files): `CustomLogicModel.java`.
    - `dialog/` (5 files): `ImportFromTextDialog`, `SliderDialog`,
      `EditCompositeModelDialog`, `EditOptions`, `EditInfo`.
    - `element/` (28 files): `SevenSegDecoderElm`, `RingCounterElm`,
      `InductorElm`, `Switch2Elm`, `AnalogSwitchElm`, `LogicInputElm`,
      `TFlipFlopElm`, `MosfetElm`, `TransistorElm`, `DFlipFlopElm`,
      `CounterElm`, `LabeledNodeElm`, `ProbeElm`, `SwitchElm`,
      `TappedTransformerElm`, `SweepElm`, `TransformerElm`, `RelayElm`,
      `LogicOutputElm`, `SeqGenElm`, `TimerElm`, `PotElm`, `MonostableElm`,
      `CapacitorElm`, `WireElm`, `TextElm`, `OpAmpRealElm`,
      `CustomTransformerElm`, `JKFlipFlopElm`.
  - `CheckboxMenuItem` — **17 occurrences in 2 files**:
    - `<root>`: `MenuManager.java` (16), `ScopePopupMenu.java` (1).
  - `CheckboxAlignedMenuItem` — **15 occurrences in 2 files**:
    - `<root>`: `MenuManager.java` (6), `ScopePopupMenu.java` (9).
  - `Choice` — **21 occurrences in 17 files**:
    - `<root>`: none direct (other than the file itself).
    - `dialog/`: `EditOptions`, `SliderDialog`.
    - `element/`: `RelayCoilElm`, `GroundElm`, `SevenSegElm`,
      `StopTriggerElm`, `VoltageElm`, `TransistorElm`, `DiodeElm`,
      `ProbeElm`, `CustomCompositeElm`, `AudioOutputElm`, `AmmeterElm`,
      `OpAmpRealElm`, `OutputElm`, `TestPointElm`, `RelayElm`.
  - `Scrollbar` — **10 occurrences in 6 files**:
    - `<root>`: `Adjustable.java`.
    - `dialog/`: `ScopePropertiesDialog`, `ControlsDialog`.
    - `element/`: `ThermistorNTCElm`, `PotElm`, `LDRElm`.

  Summary by caller package:
  - `<root>` client → `Checkbox`, `CheckboxMenuItem`, `CheckboxAlignedMenuItem`,
    `Scrollbar` (via `Adjustable`).
  - `dialog/` → `Checkbox`, `Choice`, `Scrollbar`.
  - `element/` → `Checkbox`, `Choice`, `Scrollbar`.
  - `element/waveform/`, `io/*`, `util/`, `ui/tabs/` — **no direct usage**.

  (Also referenced by non-application files: `CirSim.java` and
  `SlidersDialog.java` carry the identifier `Scrollbar`/`Choice` in comments
  or field types — the grep above over-counts these in "files containing the
  word"; the `new X(...)` grep counts are the authoritative construction
  counts.)

- **External deps:**
  - `com.google.gwt.user.client.ui.CheckBox` / `ListBox` / `MenuItem` /
    `Composite` / `VerticalPanel`.
  - `com.google.gwt.canvas.client.Canvas`, `canvas.dom.client.Context2d`.
  - `com.google.gwt.dom.client.{NativeEvent,Touch}`.
  - `com.google.gwt.user.client.{Command,Event}`.
  - `com.google.gwt.event.dom.client.*` (10 handler interfaces for
    `Scrollbar`).
  - `com.google.gwt.safehtml.shared.SafeHtmlUtils` (in
    `CheckboxAlignedMenuItem`).

## Existing Documentation

No dedicated documentation for this cluster. `project_structure.md` lists
them under "UI widgets — Small GWT-wrapped controls"
(`.dev_flow/onboard/project_structure.md:96`). No mention in
`dependency_graph.md` (sub-file granularity not tracked there). Not referenced
from `INTERNALS.md`, `docs/project.md`, or `docs/JS_API.md`.

## Issues / Questions

1. **Three menu-item classes coexist.** `CheckboxMenuItem` (stateful,
   checkable, renders check glyph) and `CheckboxAlignedMenuItem` (stateless,
   non-checkable, renders spacer to align with the former) both use the same
   `checkBoxHtml` prefix. The alignment class only exists because
   `MenuItem`'s default layout would visually misalign uncheckable entries
   next to checkable ones in the same menu — documented only by the class
   name, not a comment.
2. **`Checkbox` vs `CheckboxMenuItem` naming collision.** They are unrelated
   abstractions (form control vs menu item) but share a stem. Some callers
   mistakenly import one expecting the other; no direct evidence in this
   grep but the naming is fragile.
3. **`Scrollbar` constructor has two dead parameters.** `orientation` (even
   though `HORIZONTAL=1` is exposed) and `visible` are never read. The
   canvas is always drawn horizontally at fixed size 150x14. Callers pass
   these for AWT-compatibility but they do nothing.
4. **`Scrollbar` uses string reference comparison.** `CheckboxMenuItem.java:90`
   uses `shortcut != ""`; should be `!shortcut.isEmpty()` or
   `!"".equals(shortcut)` — works by coincidence of interned empty string.
5. **`Scrollbar` issues `cirSim.repaint()` transitively.** `CheckboxMenuItem.execute`
   repaints the whole sim every toggle; fine for menus but couples this
   legacy widget to `BaseCirSim`. Same for `Scrollbar`'s `setMouseElm()`
   calls on hover.
6. **Mixed i18n policy.** `Checkbox` and `Choice.add` run labels through
   `Locale.LS`; `CheckboxMenuItem` and `CheckboxAlignedMenuItem` do not —
   callers in `MenuManager` / `ScopePopupMenu` must localise themselves.
   Inconsistent.
7. **`Scrollbar.onClick` is dead code.** All logic moved to `onMouseDown`;
   method body is only `e.preventDefault()`. Could be removed once handler
   registration is reviewed.
8. **No test coverage.** No unit tests exist for these widgets (consistent
   with project-wide absence of a Java test suite — see
   `project_structure.md:171-180`).

## Suggested Concept Boundaries

Two candidate groupings (purely analytical — not generating a concept doc):

**Option A — one concept, "legacy-ui-wrappers":** all five classes together.
Justification: identical historical rationale (AWT→GWT adapter layer),
identical author/copyright, all live at `client/` root, callers treat them
as a single "UI primitives" surface. Simplest mapping of the
`project_structure.md` "UI widgets" cluster.

**Option B — split into two concepts:**
1. `form-widgets` — `Checkbox`, `Choice` (pure i18n-aware pass-throughs over
   GWT form controls; trivial customisation).
2. `menu-items` — `CheckboxMenuItem`, `CheckboxAlignedMenuItem` (shared
   `checkBoxHtml` constant; menu-specific HTML rendering; owned by
   `MenuManager`/`ScopePopupMenu`).
3. `custom-scrollbar` — `Scrollbar` (genuinely custom canvas widget with its
   own event model; larger by an order of magnitude; depends on
   `ColorSettings` and `CircuitElm`).

Option B better reflects code scale (Scrollbar ≈ 355 LoC vs the others ≈ 40
LoC combined) and dependency shape (Scrollbar is the only one touching
`element/` or `ColorSettings`). Option A is simpler for the onboard layer.
