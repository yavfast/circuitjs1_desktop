---
skill: agent-run-behaviour
domain: automation
topics: [agent-run, determinism, reset, initial-state, throughput, sweep, time-step, quantization, rng, noise, stop-trigger, convergence, solver-quality]
source: prototype
updated: 2026-10-02
---

# How Agent API runs behave: determinism, initial state, throughput, time-step traps

## Context

Measured for [docs/circuit-script-language.spike.md](../../../docs/circuit-script-language.spike.md) (Entry 2, 2026-10-02).
- **Build:** the draft-compiled `target/site` build.
- **Browser:** the live harness `eval` scenario in headless Chromium 153, not NW.js's Chromium 101.
- **Runs:** in a background document.
- **Timing:** page-side; MCP overhead is not included.

The defects found there were fixed in `722f9f9` (2026-10-02, E_AGT fix round); the Pitfalls below give the state after that fix. The measurements were taken before it.

Read it before writing sweeps, Monte Carlo or repeatable checks over `run` ([SP_AGA](../../../docs/agent-api.sp.md) §02 `run`), or when the C_AGS skill tells agents how to run experiments.

## Key concepts

- **Determinism.** With `reset: true` and the same span, repeated runs were bit-identical. SP_AGA §02_10 and C_AGA_03_04 now state it as "from reset; noise sources excepted".
  - Circuits covered: RC, relaxation oscillator, buck converter, class-D, 555, two oscillators, a 20-BJT op-amp, and a VCVS loop forced into step halving and forced steps.
  - It held across:
    - a stopped vs. a free-running visible tab (different slice boundaries);
    - fixed vs. adaptive step;
    - two browser sessions.
- **Three different starting states:**
  - **Fresh import:** the state saved in the file.
  - **Reset:** initial conditions.
  - **Run without `reset`:** continues from where the last run ended (`tStart` = previous `tEnd`).

  Example: a relaxation oscillator runs at 113 Hz from the file state, but after a reset its capacitor reaches only 0.149 V in 20 ms. An experiment must name its start.
- **Changing a value for the next run point.** `applyEdits [{op: "set", id, properties: {...}}]` (atomic, re-analyses, clamps reported as `value_adjusted`), then `run {reset: true}`. That is two calls per sweep point; there is no batch operation.
- **Throughput.** A 20-point sweep, each point an edit plus a reset run:

  | Circuit | Steps/s | Per run | 20-point sweep |
  |---|---|---|---|
  | RC, 5 elements, 1001 steps | 60k | 27–36 ms | 0.67 s (~1780 runs/min) |
  | Relaxation oscillator, 2001 steps | 19k | 107–129 ms | ~500 runs/min (estimated) |
  | Buck converter, MOSFET + diode, 2001 steps | 9.3k | 217–270 ms | 5.0 s (~240 runs/min) |
  | 20-BJT op-amp, 107 elements, 5000 steps | 1.5k | 3.3 s | 68 s (~18 runs/min) |

  - Fixed cost is ~8 ms per `run` and 3.5–31 ms per `applyEdits`.
  - A free-running visible tab cuts throughput to 50–65 %.
  - Concurrent runs in one app instance take turns on one JS thread, so there is no parallel speedup.
  - A non-converging circuit drops to ~85 steps/s.

## Usage in this project

- **Pass `reset` explicitly on every run** of a sweep or Monte Carlo point.
- **The first sample is the solved start state, or the first timestep.** Since `722f9f9` a run samples its start state only when the circuit is solved (`CircuitSimulator.isSolved`). After an import, an edit or a reset the first sample follows the first timestep, so `samples` = `steps` (otherwise `steps + 1`). Before the fix that sample read unsolved nodes as 0 V.
- **Set the time step with `simControl` `configure`** when the file's step does not suit the experiment. Since `722f9f9` both a loaded and a configured step are kept exactly, also across tab activation.
- **Bound every run with `budgetMs`** (100 ms–120 s). Chain runs without `reset` for longer spans.
- **Check solver issues before trusting a waveform.** On non-convergence the solver recovers with gmin/shunt panic levels and forced steps instead of stopping. A run reports `convergence_failed` once, `solver_stop` and the `recovering` flag (`DiagnosticsOps.java:80`).

## Pitfalls

- **Time-step quantization — fixed in `722f9f9`** (audit BL-D01).
  - Before the fix, text import and tab activation called `Scrollbar.setValue`, which fired the slider command and rewrote `maxTimeStep` to the nearest 1-2-5 value capped at 10 µs (15.625 µs → 10 µs on import; a configured 1 ms → 10 µs on activation).
  - Code now moves the bar with `setValueWithoutCommand`; only a user moving the bar sets a table step (see [simulator/time-step-control](../simulator/time-step-control.md) pitfall 6).
  - On a build older than `722f9f9`, call `configure` after the import and do not activate the tab afterwards.
- **Noise is not reproducible.**
  - Noise sources draw from one static, unseeded `java.util.Random` (`RandomUtils.java:7`, used by `NoiseWaveform`).
  - `OpAmpElm.java:217,220` and `GateElm.java:271` draw from the same generator inside `doStep`; no effect on results was observed.
  - Monte Carlo draws must be made by the client, with its own seed, and applied with `applyEdits`.
- **`goodIterations` resets on every `stepLoop` call** (`CircuitSimulator.java:1758`, a local of `stepLoop`). After a halving, step regrowth could depend on where a slice ends. Not exercised: realistic circuits never halved.
- **A stop trigger ends an agent run** (since `722f9f9`, SP_AGA_DEC_05). A `StopTriggerElm` firing during a run ends it with reason `stop_trigger`, adds a `stop_trigger` warning naming the element, and clears the running flag. A trigger that fired before the run (free-running) is cleared at run start. Before the fix it only cleared the free-run flag.
- **Physics limits.**
  - The temperature is fixed at 27 °C (`Diode.java:67-68`).
  - No element has tolerances.
  - Only transient analysis exists; the UI "Find DC Operating Point" is likely a no-op (unverified).
  - The step is fixed and halves only on Newton failure, with no truncation-error control or breakpoints. Accuracy rests on the chosen step.
- **The bundled `ujtosc.txt` fails at analysis** ("Exception in UNI1.stamp(): TypeError").

## References

- [docs/circuit-script-language.spike.md](../../../docs/circuit-script-language.spike.md) Entry 2; [background-documents](background-documents.md); [simulator/time-step-control](../simulator/time-step-control.md); [simulator/newton-raphson-loop](../simulator/newton-raphson-loop.md)
