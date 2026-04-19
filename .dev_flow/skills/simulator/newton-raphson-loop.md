---
skill: newton-raphson-loop
domain: simulator
topics: [newton-raphson, convergence, panic-level, gmin, recovery]
source: onboard
updated: 2026-04-18
---

# Newton-Raphson Loop and Convergence Recovery

## Context

`CircuitSimulator.runCircuit` (L1442-1754) runs the Newton sub-iteration
loop. Unlike textbook SPICE, circuitjs1 has a **3-level panic escalator**
(commit `fb4ee85`) that trades accuracy for never failing — important
because the tool is educational: a hard `stop("Convergence failed!")`
mid-demo is worse than a degraded waveform.

## Key concepts

**Per-iteration budget** (`subIterCount`):
- `panicLevel > 0` → **300** (cheap fail-fast in panic mode).
- `adjustTimeStep && timeStep/2 > minTimeStep` → **100** (try a smaller
  dt first, standard SPICE trick).
- Otherwise → **5000** (fixed-dt last resort).

**Convergence is per-element, no global norm.** Each `doStep()` compares
its new linearization point against the previous and writes
`simulator.converged = false` on mismatch. The loop exits at
`converged && subIter > 0` (L1546) — the `> 0` is intentional: the
initial iteration is always "just-stamped", so a spurious converged=true
before any solve is rejected.

Diode tolerance example (`Diode.java:160`): `abs(vnew - vold) > 0.01 V`
→ sets `converged = false`. Tolerances are element-specific, not
centralized.

**Per-iteration body (L1546+).**
1. Clone `origRightSide → circuitRightSide`.
2. If nonlinear, clone `origMatrix → circuitMatrix` (linear circuits
   reuse the LU factorization, single solve per frame).
3. Every element's `doStep()` stamps its current linearization.
4. Check convergence. If converged, exit.
5. `CircuitMath.lu_factor(A, n, permute)` for nonlinear.
6. `lu_solve` → `applySolvedRightSide` → sets `nodeVoltages[]` +
   each VS element's current.
7. NaN in any component → `converged = false` (forces another iter).

**3-level panic escalator (commit `fb4ee85`).** When
`subIter == subIterCount` and halving `timeStep` is not an option:

| Level | Extra gmin | Node shunt-to-GND | Diode limitStep ceiling |
|---:|---:|---:|---|
| 0 | 0 | ∞ | 2·vscale |
| 1 | 1e-9 S | 1e9 Ω | 2·vscale |
| 2 | 1e-6 S | 1e6 Ω | 20·vscale (relaxed) |
| 3 | 1e-3 S | 1e3 Ω | 20·vscale |

At `panicLevel >= 3 && streak >= 3`, the solver **force-advances** time
using `lastNodeVoltages` — skipping element updates/scope samples for
that step. Visible as a freeze-then-jump, but sim survives.

The gmin ramp inside `Diode.doStep` (L172-186) kicks in at
`subIter > gminStartIter` (100 normal, 10 in panic) and ramps via
`exp(-9·ln(10)·(1 - subIter/gminDenom))` where `gminDenom` drops from
3000 to 300 in panic — **faster saturation to 0.1 S, visible distortion**.

**Cooldown.** After a healthy convergence (`subIter < 8`) while
`panicLevel > 0`, `nonConvergenceCooldown++`; at 30 calm frames, panic
drops one level (L1694-1706). Returns accuracy when the hard spike has
passed.

**Singular-matrix stabilizers** (separate from non-convergence):
`stampSingularMatrixStabilizers` (L1765) — persistent gmin + VS diagonal
padding, latched via `singularStabilizersActive`. Activated on first LU
failure.

## Usage in this project

- Every nonlinear element must return `nonLinear()` = true AND call
  `stampNonLinear(row)` inside `stamp()` for every matrix row it will
  update. Missing the mark skips the row in simplify, breaks the Newton
  re-stamp.
- `simulator.subIterations` is readable by elements — used by `Diode`
  for the gmin ramp. Do not drive control flow from it unless you are
  writing a numerical helper.
- `nonConvergenceRecoveryEnabled = true` is the default
  (`CircuitSimulator.java:979`). No UI setter yet; users who need hard
  failures for device-model debugging must flip the flag in code.

## Pitfalls

1. **Never throw from the Newton loop** (RULE_ERR_001, RULE_ERR_002) —
   use `simulator.stop(msg, ce)` or set `converged = false`.
2. **`converged` is write-by-any-element.** One buggy element that
   writes `false` every iter pins the loop at `subIterCount` every
   frame. Tolerance must be calibrated to element physical scale.
3. **At panic level 2+ the simulation is not physically accurate.**
   If you are debugging device models, set
   `nonConvergenceRecoveryEnabled = false` locally — or you will chase
   waveform distortions that are solver cheats, not model bugs.
4. **Ramped gmin in `Diode` can mask real convergence issues.** The
   exponential ramp at `subIter > 100` can converge by brute-force
   damping; a correctly modeled device should converge well before then.
5. **`CirSim.resetSimulation` and `BaseCirSim.resetAction` diverge.**
   See `simulator-core` analysis §10.2 — use `resetAction` for full
   user-visible reset, `resetSolverState` for solver-only invalidation.

## References

- `.dev_flow/onboard/analysis/layer3__simulator-core.md` §5, §10.1
- `src/main/java/com/lushprojects/circuitjs1/client/CircuitSimulator.java`
  L974-1015, L1442-1754
- `src/main/java/com/lushprojects/circuitjs1/client/Diode.java` L96-186
- Recent commits: `fb4ee85` (non-convergence recovery), `a488ebb` (reset
  enhancements)
- Rules: RULE_ERR_001, RULE_ERR_002
- Sibling skill: `mna-stamping.md`, `time-step-control.md`
