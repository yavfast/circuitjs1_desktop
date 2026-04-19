# Epic: Visualization — Scopes, Sliders & Display Settings  {#E_VISUALIZATION}

> **Code:** E_VISUALIZATION
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard

## Purpose

User-facing observation and live-tuning surfaces that sit on top of the
running simulator. Scopes visualize time-domain, frequency-domain, and XY
signals drawn from element probes; sliders provide live parameter tuning
bound to element `EditInfo` rows; user preferences govern colors, number
formats, and display options across both. Together they form the "watch and
adjust" loop that distinguishes an interactive simulator from a batch one.

## Member Concepts

- [C_SCP](scope-visualization.concept.md) — multi-trace time/XY/FFT oscilloscope with trigger state machine, cursor, RMS/avg stats, docked + floating modes
- [C_ADJ](adjustable-sliders.concept.md) — `Scrollbar`-driven live parameter binding to element `EditInfo` fields; owned + shared bindings; dump record `38`
- [C_USR](user-preferences.concept.md) — tri-layer (URL query → localStorage → defaults) preference registry: `OptionsManager`, `DisplaySettings`, `ColorSettings`, `QueryParameters`

## Cross-cutting Invariants

- **Read-after-step.** Both scopes and sliders read element state *after*
  `stepFinished()` (see Simulator epic); neither probes mid-iteration.
- **Slider drags force re-analysis.** Any slider change that mutates a
  parameter affecting stamping triggers the same re-analyze path used by
  editor mutations — slider edits are not cheaper than menu edits.
- **Colors and format come from C_USR.** Scopes, sliders, and every element
  renderer pull colors from `ColorSettings` and number formatting from
  `DisplaySettings`; no subsystem holds a private palette.
- **201-entry voltage scale** is precomputed in `ColorSettings` and shared
  by every voltage-colored renderer (wires, nodes, scope traces) for O(1)
  voltage→color lookup.
- **Persistence is text-dump based.** Sliders serialize as record `38`;
  scope state serializes into the document; both share the same format
  plug-in used by **C_IOF**.

## Known Friction

- Slider "shared binding" semantics (one scrollbar driving several elements)
  are hand-maintained in `AdjustableManager` — corner cases (delete one bound
  element, undo a binding) have been a regression source.
- `ColorSettings` is a singleton with a printable-mode override; toggling
  printable mode at runtime requires an explicit redraw trigger.
- Scope trigger state machine (AUTO/NORMAL/SINGLE × rising/falling) is a
  long case ladder in `Scope.java` — extending it (e.g. window trigger)
  touches a central hotspot.
- FFT window size is fixed per-scope and reallocated on size changes; no
  ring-buffer reuse.
- `DisplaySettings` exposes ten `MenuManager`-backed boolean views — adding
  a new toggle requires coordinated edits to both C_USR and C_MEN (Editor
  epic).

## Changelog

- 2026-04-19 — Initialized from onboard procedure.
