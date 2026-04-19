# Simulator Engine  {#C_SIM}

> **Code:** C_SIM
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard
>
> **Depends on:** [C_ELB](./element-base.concept.md), [C_UTL](./util-locale-log.concept.md), [C_MDS](./math-dsp.concept.md), [C_SHM](./shared-models.concept.md)
> **Used by:** —
> **Spike:** —
> **Specification:** [SP_SIM](./simulator-engine.sp.md)
> **Plan:** [simulator-engine.plan.md](./simulator-engine.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/layer3__simulator-core.md`.
>
> The numerical heart of CircuitJS1: Modified Nodal Analysis (MNA) matrix
> assembly, Newton–Raphson iteration for nonlinear elements, LU solve,
> adaptive/forced time-step control, panic-level convergence recovery,
> and the embeddable `Diode` junction helper. Owns all solver state but
> no UI.

## 1. Philosophy  {#C_SIM_01}

### 1.1. Core Principle  {#C_SIM_01_01}

CircuitJS1 is a time-domain transient simulator built for interactive,
educational use. The engine therefore balances two competing goals:

- **Physical correctness** — classical SPICE-style MNA + Newton–Raphson
  so that circuits behave like real ones.
- **Never-die robustness** — the user must see *something* on the canvas
  even when the netlist is ill-posed, under-determined, or drives the
  solver into non-convergence. The engine prefers degraded-but-running
  over a hard error stop.

The split across files isolates engine from UI:

- `CircuitSimulator` — pure engine (matrix, solver, Newton loop, time-step).
- `BaseCirSim` — lifecycle shell; reset, analyze, stop, start/run gates.
- `BaseCirSimDelegate` — glue that lets engine read document-scoped state.
- `SimulationContextAware` — outbound contract for dialogs/models that
  edit simulation parameters and need to poke the solver afterwards.
- `Diode` — Shockley + Zener junction with SPICE-style Newton limiting.

### 1.2. Design Constraints  {#C_SIM_01_02}

- Single-threaded (GWT). No synchronization primitives.
- Wires never receive MNA rows; currents are reconstructed post-solve.
- Convergence decision is **per-element** (no global residual norm).
- Stamping goes through `sanitizeStampValue` clamp (`±1e12`, NaN→0) so
  a bad element cannot crash the solver, only force non-convergence.
- Reset paths (`BaseCirSim.resetAction`, `CirSim.resetSimulation`,
  `CircuitSimulator.resetSolverState + clearStopState`) must leave
  `needsStamp=true` so the next frame rebuilds topology.

## 2. Domain Model  {#C_SIM_02}

### 2.1. Key Entities  {#C_SIM_02_01}

- **CircuitSimulator** — owns `elmList`, `nodeList`, `voltageSources[]`,
  `circuitMatrix`, `origMatrix`, `circuitRightSide`, `origRightSide`,
  `nodeVoltages`, `lastNodeVoltages`, `circuitRowInfo[]`, `circuitPermute[]`,
  time state (`t`, `timeStep`, `maxTimeStep`, `minTimeStep`,
  `timeStepAccum`, `timeStepCount`, `adjustTimeStep`), convergence state
  (`converged`, `subIterations`, `nonConvergencePanicLevel`,
  `nonConvergenceStreak`, `nonConvergenceCooldown`, `nonConvergenceExtraGmin`,
  `nonConvergenceNodeShuntR`, `singularStabilizersActive`), and stop/warn
  state.
- **BaseCirSim** — the non-UI shell: instantiates managers, binds the
  active `CircuitDocument`, exposes `needAnalyze`, `resetAction`,
  `doDCAnalysis`, `setSimRunning`, `stop`. Overrideable `getIterCount`.
- **BaseCirSimDelegate** — abstract parent-owner proxy; gives helpers
  like `CircuitSimulator` access to `simulator()`, `renderer()`,
  `scopeManager()`, `circuitInfo()`, etc.
- **SimulationContextAware** — `setSimulationContext(CircuitDocument)`;
  implemented by `DiodeModel`, `TransistorModel`, `CustomLogicModel`,
  and `EditDialog` so they can trigger re-analyze after edits.
- **Diode** — embeddable junction (not a `CircuitElm`). Holds `leakage`,
  `zvoltage`, `vcrit`, `vzcrit`, `vdcoef`, `vzcoef`, `zoffset`, and
  per-step `lastvoltdiff`. Used by `DiodeElm`, transistors, LEDs, etc.

### 2.2. Data Flows  {#C_SIM_02_02}

```
(edit/load) → BaseCirSim.needAnalyze() → circuitInfo.dcAnalysisFlag=true
(per frame) → runCircuit(didAnalyze)
    ├─ if needsStamp: preStampAndStampCircuit()
    │   ├─ preStampCircuit(): wire-closure → ground pick → makeNodeList
    │   │   → calcWireInfo → VS slot alloc → unconnected repair → validate
    │   └─ stampCircuit(): alloc matrix → stabilizers → ce.stamp() loop
    │       → simplifyMatrix → (if linear) lu_factor
    └─ frame-loop:
        ├─ Newton: copy orig→A/B; ce.doStep(); lu_factor/solve; apply
        ├─ on failure: halve timestep OR escalate panic (0→1→2→3)
        └─ advance t; scope sample; callTimeStepHook()
```

## 3. Mechanisms  {#C_SIM_03}

### 3.1. Core Algorithm  {#C_SIM_03_01}

**MNA matrix assembly.** Primitives on `CircuitSimulator`, all funnelled
through `sanitizeStampValue`:

- `stampMatrix(i,j,x)` — A[i][j] += x (with mapRow/mapCol remap when
  `circuitNeedsMap`; fold into B if column is ROW_CONST).
- `stampRightSide(i,x)` / `stampRightSide(i)` — B[i] += x / mark rsChanges.
- `stampNonLinear(i)` — mark row lsChanges (re-stamp each Newton iter).
- `stampResistor(n1,n2,r)`, `stampConductance(n1,n2,g)` — four-corner ±g.
- `stampVoltageSource(n1,n2,vs,v)` + overload, `updateVoltageSource`.
- `stampVCVS`, `stampVCCS` / `stampVCCurrentSource`, `stampCCCS`, `stampCCVS`.
- `stampCurrentSource(n1,n2,i)` — B-only.

After element stamping, `simplifyMatrix` performs Gaussian pre-elimination
of rows with a single non-constant term (ROW_CONST + dropRow), rebuilds
A/B at reduced size, snapshots into `origMatrix`/`origRightSide`. Linear
circuits factor once via `CircuitMath.lu_factor`.

**Newton–Raphson loop** (`runCircuit`, L1442–1754). Per Newton sub-iter:
copy origRightSide→B; if nonlinear copy origMatrix→A; call `ce.doStep()`
on every element (each may set `converged = false`); if `converged &&
subIter>0` break; otherwise `lu_factor` (nonlinear) and `lu_solve`;
`applySolvedRightSide` fans solution into `nodeVoltages[]` and VS
currents. Linear circuits exit after one solve.

Budget:
- 300 iterations when `panicLevel > 0` (cheap fail-fast in panic).
- 100 iterations when `adjustTimeStep && timeStep/2 > minTimeStep`
  (try smaller dt first).
- 5000 iterations otherwise (last resort, fixed-step mode).

**Adaptive time-step.** `adjustTimeStep=true` (default). Shrink by half
on Newton failure (guarded by `minTimeStep`). Grow by ×2 after 3
consecutive frames with `subIter < 3` (capped at `maxTimeStep`). Each
resize forces restamp (companion resistors depend on dt). Frame pacing:
`stepRate = 160 * cirSim.getIterCount()`; frame budget
`frameTimeLimit = 1000/minFrameRate = 50 ms`.

**Three-level panic escalator (commit `fb4ee85`).** On hard non-convergence
when shrinking dt no longer helps:

| Level | extra gmin | node shunt R | notes |
|-------|-----------|--------------|-------|
| 0 | 0 | ∞ | normal |
| 1 | 1e-9 S | 1e9 Ω | mild damping |
| 2 | 1e-6 S | 1e6 Ω | |
| 3 | 1e-3 S | 1e3 Ω | strong damping; `Diode.limitStep` ceiling relaxed from `2·vscale` to `20·vscale` |

At panic 3 + streak ≥ 3, force-advance `t` using `lastNodeVoltages`.
Cooldown: 30 consecutive calm frames (`subIter < 8`) drop panic by one level.

**Diode helper.** `doStep(voltdiff)` applies Newton limiting (clamp
|Δv| to avoid exp overflow), computes gmin (base `leakage*0.01`, plus
`simulator.getExtraConvergenceGmin()` plus an exponential ramp kicking
in after `subIter > gminStartIter`), linearises Shockley
`I = Is·(exp(v/vscale)−1)` (with optional Zener term
`−exp((−v−Vz)/vt)`) into `stampConductance(geq) + stampCurrentSource(nc)`.
`Math.exp` arguments clamped to `[−700, 700]`.

### 3.2. Edge Cases  {#C_SIM_03_02}

- **Singular matrix** → enable `singularStabilizersActive` (gmin-to-GND
  on every non-internal node + VS diagonal padding), restamp. If still
  singular under recovery: escalate panic to 3, restore `lastNodeVoltages`.
  Without recovery: `stop("Singular matrix!")`.
- **Wire loops** (circular wire-only chains) → `warn(...)` under recovery,
  else `stop(...)` in `calcWireInfo`.
- **Unconnected nodes** — BFS closure from node 0; every unreached
  non-internal node gets tied to ground through `1e8 Ω` in
  `connectUnconnectedNodes`.
- **Sanitize-clamp** — any stamp producing NaN or >1e12 is clamped and
  forces `converged = false` (no telemetry).
- **Reset after stop** (`a488ebb`) — `resetAction` clears document error,
  calls `clearStopState` + `resetSolverState` (nulls every solver array,
  sets `needsStamp=true`), zeroes time, resets elements/scopes.
- **Headless BaseCirSim** — `getIterCount()` returns 0 in the base class;
  consumers without `CirSim` must subclass/inject a fake or `stepRate`
  will be zero and the sim will appear frozen.

## 4. Integration Points  {#C_SIM_04}

### 4.1. Dependencies  {#C_SIM_04_01}

- [C_ELB](./element-base.concept.md) — every element's
  `stamp / startIteration / doStep / stepFinished` lifecycle.
- [C_UTL](./util-locale-log.concept.md) — `CircuitMath.lu_factor`/`lu_solve`
  + pivot-failure telemetry.
- [C_MDS](./math-dsp.concept.md),
  [C_SHM](./shared-models.concept.md) — `DiodeModel` feeds `Diode.setup`;
  `TransistorModel`, `CustomLogicModel` use `SimulationContextAware`.

### 4.2. API Surface  {#C_SIM_04_02}

Engine-facing (consumed by elements, dialogs, JS bridge):
- Stamping primitives listed in §3.1.
- Convergence signals: `converged` (bool, elements write),
  `subIterations` (int), `getConvergencePanicLevel()`,
  `getExtraConvergenceGmin()`.
- Control: `analyzeCircuit`, `preStampAndStampCircuit`, `runCircuit`,
  `stop`, `warn`, `clearStopState`, `resetSolverState`, `updateModels`.
- Queries: `getElm`, `locateElm`, `getNodeVoltages`, `getCircuitNode`,
  `t`, `timeStep`, `maxTimeStep`, `minTimeStep`, `timeStepCount`.

Shell-facing: `needAnalyze`, `resetAction`, `doDCAnalysis`,
`setSimRunning`, `stop(msg, ce)`, `getActiveDocument`, `log`,
`console` (static), `setCanvasSize`, `getIterCount` (overrideable).

Outbound contract: `SimulationContextAware.setSimulationContext(doc)`.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
