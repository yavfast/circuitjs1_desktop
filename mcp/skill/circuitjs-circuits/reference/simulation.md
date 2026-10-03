# Simulation: time step, runs and measurements

CircuitJS1 runs a transient simulation only: it steps time with a fixed maximum step and solves the circuit at each step. There is no AC, DC-sweep or noise analysis; you get those results by running the circuit and reading probe statistics.

## Contents

- Time step
- Start state and repeatability
- Run spans and recording
- Operating point
- Measurements from probe stats
- Sweeps and Monte Carlo
- Budgets and long runs
- Limits of the simulator

## Time step

- **Rule.** The maximum time step should be at most **1/50 of the smallest RC or L/R constant** and at most **1/(50·f)** of the highest frequency of interest.
  - RC low-pass with R·C = 0.1 ms → step ≤ 2 µs.
  - 50 Hz rectifier → step ≤ 400 µs; use a smaller step (20 µs) to catch the short diode conduction pulses.
- **Default.** A new document uses a 5 µs step (`circuit_get` → `simulation.time_step`). A loaded file brings its own step.
- **Setting it.**
  - `circuit_sim {"action": "configure", "settings": {"maxTimeStep": "1 us"}}` on an existing document;
  - or `"simulation": {"time_step": "1 us"}` in the AgentCircuit you import.
  The value is kept exactly.
- **No error control.** With `autoTimeStep` (the default) the step halves only when Newton iteration fails to converge, and grows back afterwards. Accuracy rests on the step you choose.
- **Convergence test.** Halve `maxTimeStep`, re-run with `reset: true`, and compare. Keep the larger step if the measurement changes by less than about 1 %; otherwise keep halving.
- **Minimum step.** `minTimeStep` (default 50 ps) is the floor for halving. Do not set it equal to `maxTimeStep`: that disables halving, and a hard edge then forces non-converged steps.
- **Cost.** `steps ≈ span / time step`; a smaller step costs proportionally more wall time.

## Start state and repeatability

- **Three start states.**
  - A run right after `circuit_import` starts from the state saved in the circuit.
  - A run with `reset: true` starts from initial conditions: time 0, capacitors at `initial_voltage`, inductors at `initial_current`.
  - A run without `reset` continues where the previous run, or free-running, stopped: its `tStart` is the previous `tEnd`.
- **Name the start of every experiment.** Pass `reset: true` on every measuring run. With it, the same circuit and arguments give the same result, except for circuits with noise sources (their generator is not seeded).
- **First sample.** Probes record only solved states.
  - After an import, an edit, a reset or a time-step change, the first sample comes after the first timestep: `stats.tStart` is one step after `data.tStart` and `samples = steps`.
  - On an already solved circuit, the start state is sampled too (`samples = steps + 1`).
- **Solver stop.** A document stopped by the solver (`reason: "solver_stop"`) does not run again until a run with `reset: true` (or `circuit_sim {"action": "reset"}`).

## Run spans and recording

- **Settled values.** Run at least **5 τ** (the slowest time constant) before reading a settled value. For a periodic steady state, wait until the envelope has settled: an RLC circuit has τ = 2L/R; a filtered rectifier has τ = R_load·C.
- **`recordFrom`** is an absolute simulated time. With `reset: true` it counts from 0: `{"span": "60 ms", "recordFrom": "40 ms", "reset": true}` records the last 20 ms, after the start-up transient.
- **Periodic signals.** Record at least 3–5 periods; `frequency` needs at least two rising crossings.
- **Probes.** At most 16 per run.
  - Forms: `{"net": "out"}`, `{"post": "R1.pin2"}` or `{"element": "C1", "quantity": "voltage" | "current" | "power"}`.
  - Give a `name` when two probes would get the same default name (two quantities of one element).
- **`maxPoints`.** Series points per probe: 10..2000 (default 200), and at most 2000 summed over all probes of a run. The series keeps the minimum and maximum of every time bucket, so peaks survive decimation. Statistics always use every sample.
- **Signs.** An element's `voltage`, `current` and `power` are the values the element reports, as its type defines them. `voltage` is post 0 minus post 1 for most two-post elements, but `plus` minus `minus` for a voltage source and `out` minus `in` for a current source, so a source reads its own positive value. Check the sign of `current` and `power` on a known case before relying on it (e.g. a source with `start` on ground and a resistor load). A BJT `current` reads 0: probe a series resistor.

## Operating point

- **Settle run.** `circuit_run {"mode": "settle", "reset": true, "probes": [...]}` runs until every net voltage varies by less than `settle.tolerance` (default 0.1 mV) over `settle.window` (default 50 steps). It gives up after `settle.maxSpan` (default 1 s).
- **Results.** `reason: "settled"` means the DC operating point is reached: read `stats.final`. Example: the non-inverting stage of the patterns settles in 57 steps at 5.4994 V.
- **AC-driven circuits never settle** (`settle_timeout`). Either set the AC source's `max_voltage` to 0 for the operating-point run, or run a span and read `stats.mean` (the DC level of a periodic signal).
- **Sanity check** after building: settle once and check that the supply nets read their expected voltages and that bias points are plausible, before measuring anything else.

## Measurements from probe stats

`stats` = `{samples, tStart, tEnd, min, max, mean, rms, peakToPeak, final, frequency?, dutyCycle?, riseTime?}`, over the recorded window; `mean` and `rms` are time-weighted.

| Quantity | How | Example (measured) |
|---|---|---|
| Frequency | `frequency` of the output probe: rising crossings of `mean` with 5 % hysteresis | 555 astable: 67.9 Hz |
| Duty cycle | `dutyCycle`: fraction of time above `mean`; present only with `frequency` | 555 astable: 0.527 |
| Gain | `peakToPeak` of the output over `peakToPeak` of the input, recorded over the same window after the transient; dB = 20·log10(ratio) | inverting stage: 2.0 V / 0.2 V = 10 |
| Phase or inversion | compare the two `series` at the same times, or the sign of `final` of both | inverting stage: opposite signs at every sample |
| Ripple | `peakToPeak` at the output, recorded after the filter has settled; the DC level is `mean` | rectifier: 1.53 V on 8.65 V |
| Rise time | `riseTime`: 10 % → 90 % of the min→max span on the first rising transition; record from before the edge (`reset: true`, no `recordFrom`) | RC step with τ = 1 ms: 2.197 ms (theory 2.2 τ) |
| Settled value | `final` of a settle run, or of a span run longer than 5 τ | divider: 3.000 V |
| RMS, average power | `rms` of a voltage or current probe; `mean` of a `power` probe | — |
| Frequency response | one run per frequency (edit the source `frequency`), gain from `peakToPeak` ratios | RLC: 2.0 V at 1 kHz, 1.20 V at 900 Hz |

A step input for rise-time tests: a `VoltageSourceSquare` with `max_voltage` = `dc_offset` = V/2 steps between 0 and V; at a low `frequency` the first half-period is the step.

## Sweeps and Monte Carlo

- **One point = two calls.** Apply the value with `circuit_edit {"edits": [{"op": "set", "id": "R2", "properties": {"resistance": "12k"}}]}`, then `circuit_run` with `reset: true`. There is no batch sweep call.
- **Cost.** A small RC circuit takes about 30 ms per point. A buck converter takes about 250 ms. A 100-element transistor circuit takes several seconds.
- **Monte Carlo.** Elements have no tolerances. Draw the random values yourself, with your own seed, and apply them with `set` edits; noise sources are not reproducible.
- **Background work.** Run sweeps in a background document (pass `doc`) so the user's tab is not disturbed. Runs in one app take turns; they do not run in parallel.

## Budgets and long runs

- **`budgetMs`** bounds wall time (default 10000, range 100..120000). Estimate it from the step count:
  - small circuits run about 10 000–60 000 steps per second;
  - large transistor circuits run 1 000–2 000;
  - a free-running visible tab takes a share of the time.
  Example: a 300 ms rectifier run at 5 µs = 60 000 steps took 3.1 s; at 20 µs, 1.0 s.
- **`budget_exhausted`.** The result holds the data up to `tEnd`. Choose one of:
  - continue with another run **without** `reset` (it resumes at `tEnd`);
  - raise `budgetMs`;
  - shorten the span or start recording later;
  - raise the time step within the rule above.

  Never repeat the same run in a loop.
- **`settle_timeout`.** The circuit did not settle within `maxSpan`. It oscillates, is AC-driven, or has a slower τ than expected. Use a span run, raise `maxSpan`, or loosen `tolerance`.
- **Stop trigger.** A `StopTrigger` element that fires ends the run after that step (`reason: "stop_trigger"`): useful to stop at an event, e.g. a voltage crossing a threshold.
- **Exclusive runs.** One run at a time per document (`busy` otherwise). A user edit of that document cancels the run (`cancelled`, partial data).
- **Free-running.** `circuit_sim run` is for the user to watch; only the visible tab advances. Measure with `circuit_run`.

## Limits of the simulator

- **Temperature.** Fixed at 27 °C; parts have no tolerances.
- **Analysis.** Transient only, with no truncation-error control or breakpoints.
- **Non-convergence.** On non-convergence the solver recovers (gmin steps, forced steps) and keeps going. The result then carries `convergence_failed` (once per run), and `circuit_diagnostics` shows `recovering: true`. Treat that run as invalid and fix the cause.
- **Ideal parts.** Ideal sources and wires have zero resistance: a loop of them is `source_or_wire_loop`. Add a small series resistance where real parts have one.
