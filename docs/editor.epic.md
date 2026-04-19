# Epic: Editor — Canvas Editing, Commands & Menus  {#E_EDITOR}

> **Code:** E_EDITOR
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard

## Purpose

Everything the user *does* to a circuit between simulation runs: mouse-driven
placement, selection, drag, rotate, delete; keyboard and menu commands;
undo/redo; clipboard copy/paste. This epic owns the interactive editing
shell that sits between raw canvas events and the domain model.

## Member Concepts

- [C_EDI](canvas-editor.concept.md) — interactive editing shell: `MouseMode` state machine, hit-testing, drag/select/create, coalesced repaint
- [C_UND](commands-undo.concept.md) — full-circuit snapshot undo/redo stack plus crash-recovery slot
- [C_MEN](menus-actions.concept.md) — `ActionManager.menuPerformed` single-funnel dispatch for menus, popups, toolbar, shortcuts; i18n class-label map
- [C_CLP](clipboard.concept.md) — circuit-text ↔ browser clipboard bridge via JSNI with async-read fallback

## Cross-cutting Invariants

- **All mutating edits push undo.** Any path that modifies element state,
  topology, or selection must call `pushUndo()` (**C_UND**) before the mutation
  or the history becomes corrupt. `ActionManager` menu handlers (**C_MEN**)
  are the canonical dispatch points that enforce this.
- **Snapshots go through C_IOF.** Undo serializes the whole circuit via the
  active `CircuitFormat` (dependency on the domain-core epic); restoration
  rebuilds via `CircuitLoader.readCircuit`.
- **Clipboard = circuit text.** Copy serializes the *selection* into the same
  text format used by the importer; paste runs the importer in "merge" mode
  at the cursor position.
- **A single dispatch funnel.** Menu items, popup items, toolbar buttons, and
  keyboard shortcuts *all* land in `ActionManager.menuPerformed(menu, item)`
  — no action is allowed to side-step this funnel.
- **Render is coalesced.** The canvas editor never draws synchronously from
  an event handler; it marks dirty and relies on the `PerfMonitor`-keyed
  animation-frame loop.

## Known Friction

- `pushUndo` dedup relies on a string compare of the full circuit dump; on
  large circuits this is a measurable per-edit cost.
- The `MouseMode` state machine is a long switch in `CircuitEditorEventHandler`
  — adding new interactions (e.g. multi-touch) requires touching a central
  hotspot.
- Keyboard shortcut suppression when a dialog is open depends on
  `DialogManager.dialogIsShowing()` (Infrastructure, not this epic); regressions
  here were the subject of recent bug-fix commits.
- Clipboard fallback path (execCommand) is deprecated in modern browsers —
  future-proofing pending.
- `ActionManager` i18n label map is hand-maintained; new element categories
  must remember to register their class label or the menu shows the raw
  class name.

## Changelog

- 2026-04-19 — Initialized from onboard procedure.
