# Implementation Plan: Simulator Engine  {#PL_SIM}

> **Code:** PL_SIM
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_SIM](./simulator-engine.concept.md)
> **Specification:** [SP_SIM](./simulator-engine.sp.md)
> **Depends on plans:** [PL_ELB](./element-base.plan.md), [PL_UTL](./util-locale-log.plan.md), [PL_MDS](./math-dsp.plan.md)
> **Used by plans:** —
>
> Retrospective implementation plan captured from existing code during
> onboarding. All phases are in-tree.

## Goal

Document the MNA solver engine as shipped: matrix stamping, Newton loop,
adaptive time-step, panic recovery, reset paths, Diode helper.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Analysis method | Modified Nodal Analysis | textbook; matches SPICE idioms |
| Solver | LU with partial pivoting (`CircuitMath.lu_factor/solve`) | simple, robust, no external deps (GWT) |
| Nonlinear | Newton–Raphson, per-element convergence | no global residual needed; cheap |
| Time-step | Adaptive halving + ×2 growth after 3 good frames | SPICE-style classical control |
| Robustness | 3-level panic + gmin/shunt escalator + force-advance fallback | educational mode must never freeze |
| Wire handling | Collapse into nodeMap; reconstruct currents post-solve | keeps matrix small on wire-heavy circuits |

## Progress

- [x] Phase 1 — Engine shell + delegate (`BaseCirSim`, `BaseCirSimDelegate`, `SimulationContextAware`)
- [x] Phase 2 — Topology + node assignment (`preStampCircuit`)
- [x] Phase 3 — Matrix stamping API + simplification
- [x] Phase 4 — Newton loop + adaptive time-step
- [x] Phase 5 — Panic recovery + stabilizers (commit fb4ee85)
- [x] Phase 6 — Reset state hardening (commit a488ebb)
- [x] Phase 7 — Diode helper (Shockley + Zener + pnjlim)

## Phases

### Phase 1 — Shell + delegate (`BaseCirSim.java`, `BaseCirSimDelegate.java`, `SimulationContextAware.java`) [DONE]

**Implements:** [SP_SIM_01](./simulator-engine.sp.md#SP_SIM_01), [SP_SIM_02_05](./simulator-engine.sp.md#SP_SIM_02_05)

- `BaseCirSim`: manager construction, `bindDocument`, `needAnalyze`,
  `resetAction`, `stop`, `setSimRunning`.
- `BaseCirSimDelegate`: `simulator()`, `renderer()`, `scopeManager()`, etc.
- `SimulationContextAware`: outbound contract.

### Phase 2 — Topology (`CircuitSimulator.java` L190–555) [DONE]

**Implements:** [SP_SIM_02_02](./simulator-engine.sp.md#SP_SIM_02_02)

- `calculateWireClosure` (L190), `setGroundNode` (L356),
  `makeNodeList` (L417), `calcWireInfo` (L264),
  `findUnconnectedNodes` (L484), `connectUnconnectedNodes` (L555).

### Phase 3 — Stamping + simplification (`CircuitSimulator.java` L658–870, L1043–1200) [DONE]

**Implements:** [SP_SIM_02_04](./simulator-engine.sp.md#SP_SIM_02_04)

- ~20 `stamp*` primitives routed through `sanitizeStampValue` (L1043).
- `simplifyMatrix` (L755) — ROW_CONST elimination + mapRow/mapCol.

### Phase 4 — Newton loop + adaptive dt (`CircuitSimulator.java` L1442–1754) [DONE]

**Implements:** [SP_SIM_02_03](./simulator-engine.sp.md#SP_SIM_02_03)

- Per-element convergence signalling via `converged` flag.
- Iteration budgets 300 / 100 / 5000.
- Halve-on-fail; double after 3 good frames.
- 50 ms frame budget via `minFrameRate=20`.

### Phase 5 — Panic recovery (`CircuitSimulator.java` L974–1015, L1493–1706, stabilizer stamps L1765/L1789; `Diode.java` L99–103, L168–186) [DONE]

**Implements:** [SP_SIM_04](./simulator-engine.sp.md#SP_SIM_04)

- `nonConvergencePanicLevel` 0→1→2→3 with gmin 1e-9→1e-3 S, shunt 1e9→1e3 Ω.
- Loosen `Diode.limitStep` ceiling at panic.
- 30-frame cooldown decay.
- Force-advance at level 3 + streak ≥ 3.

### Phase 6 — Reset hardening (`BaseCirSim.resetAction` L139; `CircuitSimulator.resetSolverState` L119, `clearStopState` L108; `CirSim.resetSimulation` L1195) [DONE]

**Implements:** [SP_SIM_02_05](./simulator-engine.sp.md#SP_SIM_02_05)

- `resetSolverState` nulls 11 solver arrays + flags.
- `resetAction` ordering: clear error → clear stop → drop solver state
  → zero time → reset elements → reset scopes → needAnalyze.

### Phase 7 — Diode (`Diode.java`) [DONE]

**Implements:** [SP_SIM_01_02](./simulator-engine.sp.md#SP_SIM_01_02), [SP_SIM_02_06](./simulator-engine.sp.md#SP_SIM_02_06)

- Shockley + Zener with composite curve in reverse-Zener region.
- `limitStep` (SPICE pnjlim) with panic-mode ceiling relaxation.
- Gmin ramp after `subIterations > gminStartIter`.
- `Math.exp` arguments clamped `[−700, 700]`.

## Backlog

- Consolidate `CirSim.resetSimulation` and `BaseCirSim.resetAction`.
- Expose `nonConvergenceRecoveryEnabled` through JS bridge / menu.
- Per-frame telemetry for `sanitizeStampValue` clamps.
- Extract narrower `SolverContext` interface for `Diode` and helpers.
- Move `BaseCirSim` analysis-aware lifecycle into `CircuitSimulator.reset()`.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
