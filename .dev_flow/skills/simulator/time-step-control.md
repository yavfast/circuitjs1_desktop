---
skill: time-step-control
domain: simulator
topics: [time-step, adaptive, frame-budget, min-time-step, reset]
source: onboard
updated: 2026-10-02
---

# Time-Step Control

## Context

`CircuitSimulator` stacks two independent time controls: **adaptive dt
halving** (numerical) and **frame pacing** (wall-clock). Companion
models for L/C depend on `timeStep`, so every step-size change forces a
re-stamp. Missing this invariant is a common bug source.

## Key concepts

**State fields (`CircuitSimulator.java:24-38`):**
- `t` — simulation time, seconds.
- `timeStep` — current step (may shrink below `maxTimeStep` during
  non-convergence).
- `maxTimeStep` — user/setup-chosen ceiling.
- `minTimeStep` — floor; solver refuses to halve below it.
- `timeStepAccum` — fraction of `maxTimeStep` accrued since last
  `timeStepCount++`.
- `timeStepCount` — integer count of user-visible steps (scope sampling
  driver).
- `adjustTimeStep` — master switch (default `true`).
- `minFrameRate = 20` → `frameTimeLimit = 50 ms` per-frame wall budget.

**Adaptive halving** (gate: `adjustTimeStep && timeStep/2 > minTimeStep`):
- **Shrink** on Newton non-convergence: `timeStep /= 2`; restore
  `lastNodeVoltages`; restamp.
- **Grow** after 3 consecutive `subIter < 3` frames: `timeStep =
  min(timeStep*2, maxTimeStep)`; restamp (L1477-1483).
- **Restamp is mandatory** — companion resistors (`Inductor.stamp`,
  `CapacitorElm.stamp`) compute `compResistance = 2L/Δt` (trapezoidal)
  or `L/Δt` (backward-Euler); the stamp is wrong after dt change.

**Frame pacing** (wall-clock):
- `stepRate = 160 * cirSim.getIterCount()` (L1451).
- `getIterCount()` (defined at `CirSim.java:520`) maps speed-bar
  `v ∈ [1..100]` to `0.1 * exp((v-61)/24)` — ~9 orders of magnitude
  coverage.
- Early-exit at L1463 skips the frame if the speed bar says "too soon".
- Inner loop exits at L1474 when either `timeStepCount` satisfies the
  requested rate **or** 50 ms wall-clock elapsed.

**Shared step loop (since PL_AGA Phase 7).** The timestep loop of
`runCircuit` is `stepLoop(wireCurrentsEachStep, StepObserver)`; the
frame pacing above is its `FramePacing` observer (checked after every
completed step, also `simRunning`). Agent runs call `runSteps(observer,
…)`: the same Newton/halving/recovery loop with no pacing, no speed bar
and no running-flag check. The loop's early returns (stop, re-stamp after
enabling singular-matrix stabilisers) skip the frame's trailing work
(`lastIterTime`, delayed wire currents) as before.

**Fixed-step mode.** Setting `adjustTimeStep = false` (via JS
`setTimeStep`, UI, or loaded setup) disables halving; `subIterCount`
jumps to 5000.

**Reset semantics** (commit `a488ebb`, `BaseCirSim.resetAction`
L139-163):
1. `circuitInfo.dcAnalysisFlag = true` via `needsAnalysis()`.
2. Clear document error.
3. `simulator.clearStopState()` (stop/warn messages).
4. `simulator.resetSolverState()` (nulls circuit matrix, origMatrix,
   B, `lastNodeVoltages`, `circuitNonLinear`, `voltageSourceCount`,
   `circuitMatrixSize/FullSize`, `circuitNeedsMap`,
   `singularStabilizersActive`; sets `needsStamp = true`).
5. Zero `t`, `timeStepAccum`, `timeStepCount`.
6. Reset every element (`ce.reset()`).
7. Reset every scope.
8. `needAnalyze()`.

Pre-`a488ebb` the residual `stopMessage` could block `setSimRunning(true)`
after reset — that regression is fixed now but the pattern (clear
stop-state **before** solver-state) must be preserved.

## Usage in this project

- Users set `maxTimeStep` via the Simulation Speed scrollbar and
  `setTimeStep`/`setMaxTimeStep` in the `$wnd.CircuitJS1` JS API
  (installed at `CirSim.java:1437`).
- Capacitor / Inductor companion models re-read `simulator.timeStep`
  inside their `stamp()` — see `Inductor.java:65`.
- Scope sampling uses `timeStepCount` as the x-axis tick, not raw `t`,
  so compressing dt under non-convergence does not distort scope time
  axis.

## Pitfalls

1. **Changing `timeStep` without triggering re-stamp** breaks companion
   models. Use `simulator.resetSolverState()` or rely on the built-in
   halve/double paths which set `needsStamp`.
2. **`minTimeStep` floor matters for panic recovery.** If `minTimeStep`
   is set equal to `maxTimeStep`, adaptive halving is disabled — panic
   escalates immediately to level 1+ on any spike.
3. **`CirSim.resetSimulation` duplicates `BaseCirSim.resetAction` with
   drift** (§10.2 analysis). The JS-bridge reset zeros `lastIterTime` but
   not scopes; the UI Reset zeros scopes but not `lastIterTime`. Fix
   locally before relying on either for tests.
4. **`BaseCirSim.getIterCount()` stub returns 0** (L266). Headless uses
   of `BaseCirSim` see `stepRate = 0`; the early-exit at L1463 fires
   every frame and simulation is silently frozen. Override or subclass.
5. **`timeStepCount` vs `t`.** Scope sampling uses the integer count;
   waveform generators use `t`. Under panic force-advance, `t` jumps
   while `timeStepCount` does not — waveform-driven sources stay
   phase-correct, but counters tied to integer steps lag.
6. **Programmatic bar updates must not run the bar's command** (fixed
   2026-10-02 in `722f9f9`, SP_AGA_06_01 item 18 / audit BL-D01). Tab
   activation (`CircuitDocument.restoreUIState` →
   `ControlsDialog.syncTimeStepBar`), the scope exit, Agent API
   `configure` and the text/JSON importers move the thumb with
   `Scrollbar.setValueWithoutCommand` (nearest 1-2-5 position, exact label).
   Only a user moving the bar runs its command, which sets a table step
   (≤ 10 µs) and calls `needAnalyze`. Before the fix, `Scrollbar.setValue`
   fired the command and silently rewrote a file's or a configured step
   (15.625 µs → 10 µs, 1 ms → 10 µs). Never call `timeStepBar.setValue`
   from code with a non-table step. A missing, garbled, non-positive or
   infinite `$` step now falls back to 5 µs with a console line.

## References

- `.dev_flow/onboard/analysis/layer3__simulator-core.md` §6, §10.2
- `src/main/java/com/lushprojects/circuitjs1/client/CircuitSimulator.java`
  L24-38, L1442-1483, L1694-1706
- `src/main/java/com/lushprojects/circuitjs1/client/BaseCirSim.java`
  L139-163
- `src/main/java/com/lushprojects/circuitjs1/client/CirSim.java` L520
- Commit `a488ebb` — reset enhancements
- Sibling skill: `newton-raphson-loop.md`, `mna-stamping.md`
