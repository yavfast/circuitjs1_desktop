# Legacy UI Wrappers  {#C_LUW}

> **Code:** C_LUW
> **Status:** approved
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** OnboardDocGen
>
> **Depends on:** [C_UTL](./util-locale-log.concept.md), [C_RND](./rendering-primitives.concept.md), [C_PLT](./platform.concept.md)
> **Used by:** `com.lushprojects.circuitjs1.client` (root), `…client.dialog`, `…client.element`
> **Spike:** —
> **Specification:** [SP_LUW](./legacy-ui-wrappers.sp.md)
> **Plan:** [legacy-ui-wrappers.plan.md](./legacy-ui-wrappers.plan.md)
>
> Analysis source: [.dev_flow/onboard/analysis/root-widgets.md](../.dev_flow/onboard/analysis/root-widgets.md)
>
> Five AWT-style widget adapters sitting at `client/` root: `Checkbox`,
> `CheckboxMenuItem`, `CheckboxAlignedMenuItem`, `Choice`, `Scrollbar`.
> They preserve the pre-GWT applet API surface so that ~64 callsites
> across the project (dialogs, elements, menus) need not be rewritten.

## 1. Philosophy  {#C_LUW_01}

### 1.1. Core Principle  {#C_LUW_01_01}

CircuitJS1 was originally a Java AWT applet by Paul Falstad. The GWT
port retains AWT-flavoured call sites (`getState/setState`, `add/select`,
`Scrollbar(val, vis, min, max)`) by wrapping the matching GWT controls
and delegating. A thin adapter layer localizes the translation to a
handful of classes, avoiding a sweeping API rewrite across ~35 files.

### 1.2. Design Constraints  {#C_LUW_01_02}

- **Stable AWT-like surface.** Renames would ripple through dialogs and
  elements; surface is frozen.
- **Minimal custom state.** Four of the five wrappers carry no local
  state; only `CheckboxMenuItem` (on/off flag) and `Scrollbar`
  (full custom canvas model) do.
- **i18n consistency (best effort).** `Checkbox` / `Choice.add`
  funnel labels through `Locale.LS`; menu items do not (callers
  localize).
- **Root-level placement.** Classes live at `client/` root rather than
  in `client/ui/` because moving them would force widespread import
  rewrites.

## 2. Domain Model  {#C_LUW_02}

### 2.1. Key Entities  {#C_LUW_02_01}

| Class | Wraps | Custom behaviour |
|---|---|---|
| `Checkbox` | `ui.CheckBox` | i18n label, `getState/setState` aliases |
| `CheckboxMenuItem` | `ui.MenuItem` | Toggles `on` flag, renders unicode check glyph, invokes `Command`, repaints sim |
| `CheckboxAlignedMenuItem` | `ui.MenuItem` | Renders a spacer matching the check-glyph width so uncheckable items align |
| `Choice` | `ui.ListBox` | i18n-aware `add(String)`, `select(int)` alias |
| `Scrollbar` | `ui.Composite` + `Canvas` | Fully custom horizontal scrollbar with 10 GWT handlers (mouse / touch / wheel) |

Relationships:
- `CheckboxAlignedMenuItem` consumes the static `checkBoxHtml` from
  `CheckboxMenuItem` to ensure visual alignment in the same menu.
- `Scrollbar` optionally attaches a `CircuitElm` so hover feeds
  `BaseCirSim.setMouseElm` and the knob colour can follow
  `needsHighlight()`.

### 2.2. Data Flows  {#C_LUW_02_02}

- **Checkbox / Choice:** pass-through to GWT with localised labels.
  Event wiring inherited (`addValueChangeHandler` /
  `addChangeHandler`) — not surfaced by the wrappers.
- **CheckboxMenuItem:** activation → `execute()` → toggle `on` → re-
  render HTML via `setState(on)` → invoke optional external `Command`
  → `cirSim.repaint()`.
- **Scrollbar:** user input (mouse down / drag / up / wheel / touch)
  → `calcValueFromPos` → clamp → `draw()` → fire external
  `Command`. Programmatic `setValue` follows the same tail.

## 3. Mechanisms  {#C_LUW_03}

### 3.1. Core Algorithm  {#C_LUW_03_01}

`Scrollbar` owns the only non-trivial algorithmic surface:

- A 150 × 14 canvas is rendered with arrow caps, track, value fill and
  a knob. Hit-testing splits the canvas into left arrow / track /
  right arrow regions.
- Mouse-down on arrows decrements / increments `val` within `[min,max]`.
- Mouse-down on the track jumps the knob to the pointer and begins a
  drag (`Event.setCapture`).
- Drag moves map canvas-x to a clamped `val` via `calcValueFromPos`.
- Wheel adjusts `val` by `deltaY/3`.
- Touch events mirror mouse events via `Touch.getRelativeX`.
- Every change that commits fires the external `Command`.

Menu-item rendering: `CheckboxMenuItem` builds an HTML string combining
the static `checkBoxHtml` prefix (a 15px inline-block possibly containing
`&#10004;`), the name, and an absolutely-positioned shortcut string.
`CheckboxAlignedMenuItem` reuses the same prefix with `&nbsp;` so
uncheckable entries align with checkable siblings.

### 3.2. Edge Cases  {#C_LUW_03_02}

- **Dead ctor args** — `Scrollbar`'s `orientation` and `visible`
  parameters are accepted for AWT compatibility and never read; only
  horizontal layout is implemented.
- **String reference comparison** — `CheckboxMenuItem.java:90` uses
  `shortcut != ""`; works only because of interned literals. Tracked in
  backlog.
- **Mixed i18n policy** — `CheckboxMenuItem` / `CheckboxAlignedMenuItem`
  do not call `Locale.LS`; callers must localise. Tracked in backlog.
- **Global repaint on toggle** — `CheckboxMenuItem.execute` invokes
  `cirSim.repaint()`, coupling this leaf widget to `BaseCirSim`.
- **Drag with no buttons down** — `Scrollbar.onMouseMove` uses a JSNI
  `noButtonsDown` probe to recover if a mouseup was missed.

## 4. Integration Points  {#C_LUW_04}

### 4.1. Dependencies  {#C_LUW_04_01}

- [C_UTL](./util-locale-log.concept.md) — `Locale.LS` for i18n.
- [C_RND](./rendering-primitives.concept.md) — `ColorSettings` for `Scrollbar` knob
  colour (highlight when attached element requires it).
- [C_PLT](./platform.concept.md) — GWT runtime (canvas, event, safehtml).
- Application: `BaseCirSim` (`repaint`, `setMouseElm`), `CircuitElm`
  (attached-element optional in `Scrollbar`).

### 4.2. API Surface  {#C_LUW_04_02}

- `Checkbox(label[, initial])`, `getState/setState`.
- `Choice()`, `add(label)` (i18n), `select(index)`.
- `CheckboxMenuItem(cirSim, label[, shortcut][, Command])`,
  `setState(bool)`, `getState()`, `setShortcut`, `setTitle`.
- `CheckboxAlignedMenuItem(label, Command)`.
- `Scrollbar(cirSim, orientation, val, visible, min, max [, Command
  [, CircuitElm]])`, `getValue()`, `setValue(int)`, `enable()`,
  `disable()`, `draw()`.

Consumer packages (package-name level): `com.lushprojects.circuitjs1.client`
(root), `com.lushprojects.circuitjs1.client.dialog`,
`com.lushprojects.circuitjs1.client.element`.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
