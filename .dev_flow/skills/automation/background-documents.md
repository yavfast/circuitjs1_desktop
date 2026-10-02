---
skill: background-documents
domain: automation
topics: [documents, bind-document, session-state, sliders-dialog, hint, simulation-loop, agent-run, r1-r2]
source: prototype
updated: 2026-10-02
---

# Operating on a non-active document without disturbing the visible tab

## Context

Measured by the PL_AGA Phase 0 prototype (2026-10-01, scratch branch `proto/agent-bg-doc`, discarded), recorded in [agent-api.plan.md → Phase 0 result](../../../docs/agent-api.plan.md#PL_AGA_P0) and [PL_AGA_DEC_01](../../../docs/agent-api.plan.md#PL_AGA_DEC_01). Applies to any code that loads, undoes, exports, steps or renders a document that is not the active tab: SP_AGA_03_08 R1 (no disturbance) and R2 (the target behaves as if active).

## Key concepts

- **Session vs document.** `CircuitDocument` subsystems (simulator, editor, undo, adjustables, loader) resolve through their own document. Session coupling comes from direct `cirSim.*` calls: menu check items, the bars, `ColorSettings` voltage range, renderer transform and hint, the sliders dialog, the window title, menu item enablement, and the user's `CircuitJS1.onanalyze` hook (`CirSim.callAnalyzeHook`, reached from `preStampCircuit`): `DocumentScope` records the visible document (`CirSim.visibleWhileBound`) and the hook is skipped while another document is bound (PL_AGA Phase 5). The `ontimestep` hook (`CirSim.callTimeStepHook`, called after every timestep of `stepLoop`) is guarded the same way since PL_AGA Phase 7: a background run never calls it, a run of the visible document does.
- **Scoped silent bind.** Save the active tab's UI state (`saveUIState`), swap the bound document by field (`BaseCirSim.activeDocument` and `DocumentManager.activeDocument`), apply the target's options (`applyOptionWidgets`), its transform (or `centreCircuit()` if it has none) and its hint. Then run the operation, save the target's UI state and hint, swap back and re-apply the active tab's state. Finally refresh the derived widgets: `updateTimeStepBar`, `setPowerBarEnable`, `enableUndoRedo`, `allowSave(previous)` and `changeWindowTitle`. Cost measured: 0.6 ms average, 3 ms worst per bind.
- **`DocumentManager.dumpDocument`** is today's only background bind, and it uses the full `bindDocument`. That is fine for one synchronous dump but wrong for repeated binds.

## Usage in this project

- Use the silent swap, never `BaseCirSim.bindDocument`/`CirSim.bindDocument`, for a background bind. `bindDocument` calls `setActive`, which stops the active tab's 16 ms `SimulationLoop` timer when the target is bound and restarts it, with a fresh 16 ms delay, on rebind. It also moves listeners, updates the toolbar run button and resets the renderer timers.
- Detach the session `SlidersDialog` while a background document is bound. `AdjustableManager.updateSliders` (`clearSlidersDialog` + `createSliders`, reached from load, import and undo) clears the shared dialog and refills it with the target's sliders. The target's rows are rebuilt by `restoreUIState → updateSliders` when its tab is activated.
- A sliced run (PL_AGA Phase 7, `agent/RunController`) steps the bound target with `CircuitSimulator.runSteps(observer, wireCurrentsEachStep)` — the free-running frame's timestep loop (`stepLoop`) without wall-clock pacing, speed bar or running-flag check; the observer records probes and ends the slice at its budget: 20 ms − 3 ms margin from scope entry (bind cost up to 3 ms, millisecond timer granularity — the prototype, timing inside the scope, measured 23–25 ms for a 20 ms budget). Then it yields until the visible tab's loop has run one frame (`SimulationLoop.runAfterNextFrame`, with a fallback timer) when the visible tab is another free-running document, else through a zero-delay `Timer`. A fixed `schedule(1)` sometimes ran two slices back to back.
- A busy document (`CircuitDocument.setAgentBusy(owner)`) is skipped by its own free-running loop, also when it is the visible tab; the run repaints it once per slice and resets `lastIterTime` at the end, so free-running resumes without catching up the run's wall time. User actions on it and its close call `cancelAgentRun()` first.

## Pitfalls

- Per-slice `bindDocument` with a 1 ms yield starved the active tab completely (0 % of its idle simulated-time rate). With a frame-aware yield it still cost 30 % of background throughput.
- The renderer hint (`hintType`, `hintItem1/2`) is session state and is not in the saved UI state. A normal tab switch carries the previous tab's hint items into the new tab and logs `getElm: invalid index`. Give each document its own hint (PL_AGA backlog).
- `CircuitSimulator.runCircuit` reads the speed bar through `cirSim.getIterCount()`. Stepping a background simulator without a bind uses the active tab's speed, and its console lines and element-level `CirSim` calls reach the active document.
- Log lines from session UI (for example `Save option: SlidersDialog.pos`) land in whichever document is bound. Do not compare raw log buffers in R2 checks.
- `ImportLifecycle` writes the time-step, speed, current and power bars directly. Restore them after the scope; `saveUIState` does not cover the time-step bar.
- `CircuitRenderer.centreCircuit()` calls `setCircuitArea()`, which sizes the session `circuitArea` from the *bound* document's scope count. Centring a background target leaves the visible tab's scopes at height 0 unless the area is restored (`DocumentScope` keeps the reference and puts it back; `restoreUIState` recomputes it on every tab activation). `ScopeManager.setupScopes` recomputes it only when that document's own scope count changes.
- Do not refresh the time-step bar with `ControlsDialog.updateTimeStepBar()` after the scope: `Scrollbar.setValue` runs the bar's command, which writes the quantised bar value into the bound simulator's `maxTimeStep`/`timeStep` and calls `needAnalyze()` on the visible tab. `DocumentScope` uses `ControlsDialog.syncTimeStepBar()` (thumb and label only, via `Scrollbar.setValueWithoutCommand`).

## References

- [docs/agent-api.sp.md §03_08](../../../docs/agent-api.sp.md#SP_AGA_03_08), [§05_02](../../../docs/agent-api.sp.md#SP_AGA_05_02), [agent-mcp-surface](agent-mcp-surface.md), [js-api-surface](js-api-surface.md)
