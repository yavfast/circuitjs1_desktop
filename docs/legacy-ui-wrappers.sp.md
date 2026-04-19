# Legacy UI Wrappers — Specification  {#SP_LUW}

> **Code:** SP_LUW
> **Status:** approved
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_LUW](./legacy-ui-wrappers.concept.md)
> **Depends on specs:** [SP_UTL](./util-locale-log.sp.md), [SP_RND](./rendering-primitives.sp.md), [SP_PLT](./platform.sp.md)
> **Used by specs:** — (consumed by application-level dialogs/elements/menus)
> **Plan:** [legacy-ui-wrappers.plan.md](./legacy-ui-wrappers.plan.md)
>
> Analysis source: [.dev_flow/onboard/analysis/root-widgets.md](../.dev_flow/onboard/analysis/root-widgets.md)
>
> Specifies data shapes, contracts, validation, transitions, and
> verification criteria for the five legacy AWT-style widget adapters.

## 01. Data Structures  {#SP_LUW_01}

> Implements: [C_LUW_02](./legacy-ui-wrappers.concept.md#C_LUW_02)

### 01_01. Checkbox  {#SP_LUW_01_01}

Extends `com.google.gwt.user.client.ui.CheckBox`. No local state.

Fields: (inherited from GWT `CheckBox` only)

### 01_02. CheckboxMenuItem  {#SP_LUW_01_02}

Extends `MenuItem` and implements GWT `Command` so it is its own
scheduled command.

Fields:
| Field | Type | Required | Default | Constraints | Description |
|-------|------|----------|---------|-------------|-------------|
| cirSim | BaseCirSim | yes | — | non-null | Repaint target |
| on | boolean | yes | false | — | Toggle state |
| name | String | yes | "" | — | Label text |
| shortcut | String | yes | "" | — | Right-aligned shortcut text |
| extcmd | Command | no | null | — | External activation callback |
| checkBoxHtml | static String | yes | literal | shared with `CheckboxAlignedMenuItem` | HTML prefix |

Invariants:
- After `setState(b)` the rendered HTML contains the check glyph iff `b`.
- `execute()` toggles `on`, then invokes `extcmd` if present, then
  `cirSim.repaint()`.

### 01_03. CheckboxAlignedMenuItem  {#SP_LUW_01_03}

Extends `MenuItem`. Stateless; constructor-only wrapper that prepends
`CheckboxMenuItem.checkBoxHtml + "&nbsp;</div>"` via
`SafeHtmlUtils.fromTrustedString`.

### 01_04. Choice  {#SP_LUW_01_04}

Extends `com.google.gwt.user.client.ui.ListBox`. No local state.

### 01_05. Scrollbar  {#SP_LUW_01_05}

Extends `Composite`, hosts a `VerticalPanel` with a 150×14 `Canvas`.

Fields:
| Field | Type | Required | Default | Constraints | Description |
|-------|------|----------|---------|-------------|-------------|
| cirSim | BaseCirSim | yes | — | non-null | For repaint / mouse-elm |
| can | Canvas | yes | 150×14 | non-null | Render surface |
| g | Context2d | yes | — | from `can` | Drawing context |
| min, max, val | int | yes | ctor | min ≤ val ≤ max | Value model |
| dragging | boolean | yes | false | false when disabled | Drag gate |
| enabled | boolean | yes | true | — | Input gate |
| command | Command | no | null | — | On-change callback |
| attachedElm | CircuitElm | no | null | — | Highlight source |

Public constants: `HORIZONTAL=1`, `HMARGIN=2`, `SCROLLHEIGHT=14`,
`BARWIDTH=3`, `BARMARGIN=3`.

Invariants:
- `min ≤ val ≤ max` enforced in `calcValueFromPos` and `setValue`.
- `!enabled ⇒ !dragging` (enforced in `disable()`).

## 02. Contracts  {#SP_LUW_02}

### 02_01. Checkbox.getState / setState  {#SP_LUW_02_01}

Aliases of GWT `getValue()` / `setValue()`. No side effects.

### 02_02. Choice.add(label) / select(index)  {#SP_LUW_02_02}

`add` runs `label` through `Locale.LS` before delegating.
`select(i)` delegates to `setSelectedIndex(i)`.

### 02_03. CheckboxMenuItem.execute()  {#SP_LUW_02_03}

Purpose: activation callback.

Processing logic:
    FUNCTION execute():
        on = !on
        setState(on)                 -- re-renders HTML
        IF extcmd != null: extcmd.execute()
        cirSim.repaint()

### 02_04. CheckboxMenuItem.setState(bool)  {#SP_LUW_02_04}

Updates `on` and re-renders HTML (check glyph + name + shortcut).

### 02_05. Scrollbar.setValue(int)  {#SP_LUW_02_05}

Input:
| Parameter | Type | Required | Constraints |
|-----------|------|----------|-------------|
| v | int | yes | clamped to `[min,max]` |

Processing logic:
    FUNCTION setValue(v):
        val = clamp(v, min, max)
        draw()
        IF command != null: command.execute()

### 02_06. Scrollbar event handlers  {#SP_LUW_02_06}

`onMouseDown(x)` — arrow region decrements / increments `val` within
bounds; track region jumps knob and sets `dragging=true`,
`Event.setCapture`. `onMouseMove(x)` — if `dragging` and a button is
down, update `val`; otherwise release capture and clear `dragging`.
`onMouseUp(x)` — finalise, release capture, fire command.
`onMouseWheel(dy)` — `val += dy/3`; clamp; fire command.
Touch handlers mirror mouse handlers via `Touch.getRelativeX`.

## 03. Validation Rules  {#SP_LUW_03}

### 03_01. Input Validation  {#SP_LUW_03_01}

- `Scrollbar.calcValueFromPos` clamps result to `[min,max]`.
- `Scrollbar.setValue` clamps its argument.
- `Scrollbar.onMouseDown` decrements only if `val > min`; increments
  only if `val < max`.
- `CheckboxMenuItem.setHTML` is called via `SafeHtmlUtils.fromTrustedString`
  — label is treated as trusted input supplied by caller.
- All input paths in `Scrollbar` are gated by `enabled`.

## 04. State Transitions  {#SP_LUW_04}

### 04_01. CheckboxMenuItem  {#SP_LUW_04_01}

    [off] --execute--> [on] --execute--> [off]

### 04_02. Scrollbar value  {#SP_LUW_04_02}

    [val] --onMouseDown(arrow)--> [val ± 1]
    [val] --track-click--> [val' = calcValueFromPos(x), dragging=true]
    [val, dragging] --drag--> [val' = calcValueFromPos(x)]
    [val, dragging] --mouseUp/touchEnd--> [val, dragging=false]
    [val] --wheel(dy)--> [val + dy/3]
    [*] --setValue(v)--> [clamp(v, min, max)]

### 04_03. Scrollbar enabled  {#SP_LUW_04_03}

    [enabled] --disable()--> [disabled, dragging=false]
    [disabled] --enable()--> [enabled]

## 05. Verification Criteria  {#SP_LUW_05}

### 05_01. Functional Expectations  {#SP_LUW_05_01}

| Contract | Scenario | Input | Expected outcome |
|----------|----------|-------|------------------|
| Checkbox.setState | Happy path | true | `getState` == true |
| Choice.add | i18n | "OK" | label is `Locale.LS("OK")` |
| CheckboxMenuItem.execute | Toggle | click | `on` flips; cmd runs; repaint |
| Scrollbar.setValue | Clamp high | max+5 | `val == max`; command fired |
| Scrollbar.setValue | Clamp low | min-5 | `val == min`; command fired |
| Scrollbar wheel | Scroll down | dy=9 | `val += 3` (clamped) |

### 05_02. Invariant Checks  {#SP_LUW_05_02}

| Invariant | Verification method |
|-----------|-------------------|
| `min ≤ val ≤ max` | After any transition, assert bounds |
| `!enabled ⇒ !dragging` | After `disable()` inspect both |
| HTML glyph matches `on` | Snapshot compare after `setState` |

### 05_03. Integration Scenarios  {#SP_LUW_05_03}

| Scenario | Preconditions | Steps | Expected result |
|----------|--------------|-------|-----------------|
| CheckboxMenuItem in MenuManager | menu built | click item | state toggles, sim repaints |
| Aligned item next to checkable | both in same menu | visual | left edges align |
| Scrollbar on PotElm | element selected | drag knob | value updates; sim re-sims |
| Scrollbar hover with attachedElm | ctor with `elm` | mouse over | `cirSim.setMouseElm(elm)` called |

### 05_04. Edge Cases and Boundaries  {#SP_LUW_05_04}

| Case | Input | Expected behavior |
|------|-------|-------------------|
| Scrollbar orientation != HORIZONTAL | ctor arg = 2 | silently ignored (dead param) |
| Scrollbar mouseUp missed | `onMouseMove` with no buttons | capture released, dragging cleared |
| CheckboxMenuItem empty shortcut | `""` | no shortcut suffix rendered |
| Choice.add null label | null | delegated to `Locale.LS` (undefined by spec) |
| Scrollbar disabled | any input | no-op; canvas drawn greyed |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
