# Implementation Plan: Legacy UI Wrappers  {#PL_LUW}

> **Code:** PL_LUW
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_LUW](./legacy-ui-wrappers.concept.md)
> **Specification:** [SP_LUW](./legacy-ui-wrappers.sp.md)
> **Depends on plans:** [PL_UTL](./util-locale-log.plan.md), [PL_RND](./rendering-primitives.plan.md), [PL_PLT](./platform.plan.md)
> **Used by plans:** — (consumed by dialogs/elements/menus; no formal plan yet)
>
> Analysis source: [.dev_flow/onboard/analysis/root-widgets.md](../.dev_flow/onboard/analysis/root-widgets.md)
>
> Documents the already-shipped implementation of the five legacy
> AWT-style widget adapters.

## Goal

Preserve the AWT-flavoured widget API (`Checkbox`, `CheckboxMenuItem`,
`CheckboxAlignedMenuItem`, `Choice`, `Scrollbar`) atop GWT controls so
that the broad set of legacy callsites (~35 files across `<root>`,
`dialog/`, `element/`) compile and run unchanged.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Location | `client/` root (not `client/ui/`) | Moving would touch every callsite import |
| `Checkbox` / `Choice` | Subclass + AWT-alias methods | Zero-cost pass-through; inherits event API |
| `CheckboxMenuItem` glyph | Inline HTML via `setHTML` | `MenuItem` has no native check state |
| `CheckboxAlignedMenuItem` | Spacer using shared `checkBoxHtml` | Visual alignment with checkable siblings |
| `Scrollbar` rendering | Custom `Canvas` + 10 GWT handlers | GWT `ScrollBar` API does not match AWT semantics |
| Touch support | Mirror mouse via `Touch.getRelativeX` | Mobile/tablet parity |

## Progress

- [x] Phase 1 — Pass-through form wrappers (Checkbox, Choice) [DONE]
- [x] Phase 2 — Menu-item wrappers (CheckboxMenuItem, CheckboxAlignedMenuItem) [DONE]
- [x] Phase 3 — Custom Scrollbar (mouse / wheel / touch) [DONE]
- [x] Phase 4 — Integration with CircuitElm (attached-element highlight) [DONE]

## Phases

### Phase 1 — Form wrappers (`client/Checkbox.java`, `client/Choice.java`) [DONE]

**Implements:** [SP_LUW_01_01](./legacy-ui-wrappers.sp.md#SP_LUW_01_01),
[SP_LUW_01_04](./legacy-ui-wrappers.sp.md#SP_LUW_01_04),
[SP_LUW_02_01](./legacy-ui-wrappers.sp.md#SP_LUW_02_01),
[SP_LUW_02_02](./legacy-ui-wrappers.sp.md#SP_LUW_02_02)

`Locale.LS` on labels; `getState/setState` / `select` AWT aliases.

### Phase 2 — Menu-item wrappers (`client/CheckboxMenuItem.java`, `client/CheckboxAlignedMenuItem.java`) [DONE]

**Implements:** [SP_LUW_01_02](./legacy-ui-wrappers.sp.md#SP_LUW_01_02),
[SP_LUW_01_03](./legacy-ui-wrappers.sp.md#SP_LUW_01_03),
[SP_LUW_02_03..04](./legacy-ui-wrappers.sp.md#SP_LUW_02_03)

Shared static `checkBoxHtml`; HTML re-render on `setState`; `execute()`
toggles and repaints.

### Phase 3 — Custom Scrollbar (`client/Scrollbar.java`) [DONE]

**Implements:** [SP_LUW_01_05](./legacy-ui-wrappers.sp.md#SP_LUW_01_05),
[SP_LUW_02_05..06](./legacy-ui-wrappers.sp.md#SP_LUW_02_05),
[SP_LUW_04_02..03](./legacy-ui-wrappers.sp.md#SP_LUW_04_02)

150×14 canvas rendering; hit-testing for arrows / track; drag with
`Event.setCapture`; wheel with `deltaY/3`; touch mirroring.

### Phase 4 — CircuitElm integration [DONE]

**Implements:** Hover→`setMouseElm` and highlight via
`ColorSettings.getSelectColor` when `attachedElm.needsHighlight()`.

## Backlog

Items deferred (sourced from Issues in the analysis file):

- Drop or implement `Scrollbar` dead ctor args `orientation` / `visible`
  (Issue 3).
- Replace `CheckboxMenuItem.java:90` reference comparison
  `shortcut != ""` with `!shortcut.isEmpty()` (Issue 4).
- Decouple `CheckboxMenuItem` from `BaseCirSim.repaint()`; emit a
  generic event instead (Issue 5).
- Unify i18n policy: either all wrappers call `Locale.LS` or none do
  (Issue 6).
- Remove `Scrollbar.onClick` dead body; re-confirm handler registrations
  still required by GWT (Issue 7).
- Consider migrating the cluster into `client/ui/` once callsites have
  been codemod-able (Issue 1 — three menu-item classes coexist;
  naming/placement clarity).
- Clarify `Checkbox` vs `CheckboxMenuItem` naming collision to avoid
  wrong imports (Issue 2).
- Add unit test coverage — none exists today (Issue 8).

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version; documents shipped implementation |
