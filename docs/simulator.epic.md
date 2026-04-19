# Epic: Simulator — Numerical Engine & Document Model  {#E_SIMULATOR}

> **Code:** E_SIMULATOR
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard

## Purpose

The numerical core and the controller/document layer that drives it. This
epic owns the Modified Nodal Analysis (MNA) matrix builder, the Newton–
Raphson non-linear iteration loop, the time-stepping scheduler, the per-tab
circuit document lifecycle, and the `CirSim` top-level application
controller that orchestrates simulation ticks against the UI.

## Member Concepts

- [C_SIM](simulator-engine.concept.md) — MNA matrix builder, non-linear iteration, time-step integrator
- [C_APC](app-controller.concept.md) — `CirSim` UI shell: top-level controller wiring simulator, editor, document, menus, scopes, dialogs
- [C_DOC](document-model.concept.md) — per-tab `CircuitDocument` state container and multi-tab document lifecycle
- [C_NET](netlist-graph.concept.md) — solver-facing node table and node–element link graph rebuilt each re-analyze pass

## Cross-cutting Invariants

- **Analyze → Stamp → Solve.** Every topology change triggers a full
  re-analyze: **C_NET** rebuilds the node graph, each element re-runs
  `stamp()` into **C_SIM**'s matrix, then the solver factorizes. Simulation
  never steps against a stale netlist.
- **Convergence may fail.** The Newton–Raphson loop is bounded; non-
  convergence marks the document as errored (**C_APC** surfaces it) and stops
  time-stepping until user intervention.
- **One active document.** `CirSim` holds many `CircuitDocument`s but steps
  only the active tab's document. Switching tabs must fully reset the solver
  state (including matrix LU cache and transient history).
- **Time-step is the single clock.** Scopes, sliders, and JS-bridge observers
  read state *after* `stepFinished()` completes — never mid-iteration.
- **Reset drops transient state only.** Reset clears solver state, stop
  flags, and error flags but preserves element parameters and topology.

## Known Friction

- Non-convergence recovery was recently hardened (see commit
  `fb4ee85`) but element-level robustness varies; some elements still
  produce singular matrices for pathological inputs.
- `CircuitDocument` and `CirSim` have a bidirectional dependency —
  `CircuitDocument` holds a back-pointer to `CirSim` for dialog routing,
  which blurs the layering boundary between C_APC and C_DOC.
- Netlist rebuild on every edit is O(N·edges); for very large circuits the
  re-analyze path dominates interactive latency.
- The "which doc is active" flag lives in `CirSim`, not `DocumentManager` —
  a tiny ownership smell flagged during onboarding.
- Reset semantics across concepts (editor, simulator, scopes, sliders) were
  aligned in commit `a488ebb`; regressions here are a watch-item.

## Changelog

- 2026-04-19 — Initialized from onboard procedure.
