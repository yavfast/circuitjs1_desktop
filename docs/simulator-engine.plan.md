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
- **[done] TD_20261005_145712_sparse-solver — sparse solver for large circuits** (promoted to C_SLV, filed 2026-10-05 by `/dev-flow todo`; delivered by [PL_SLV](./linear-solver.plan.md) on branch `feat/sparse-solver`, 2026-10-05). Replace the dense `m × m` `double[][]` matrix and `CircuitMath.lu_factor`/`lu_solve` with a sparse LU for large circuits. [INTERNALS.md](../INTERNALS.md) already names this as the way to speed up large linear circuits.
  - **Feasibility (at capture):** feasible with caveats. Matrix access is encapsulated: only `CircuitSimulator` touches `circuitMatrix` (elements go through `stampMatrix`/`stampRightSide`), so the change stays inside `CircuitSimulator` + `CircuitMath`. It must be pure Java that GWT can compile (no external libraries).
  - **Why it matters:** a linear circuit is factored once per analysis, which is O(m³) (2000 unconnected resistors: ~200 s, [mna-stamping Pitfall 7](../.dev_flow/skills/simulator/mna-stamping.md)). After that each step costs O(m²). A nonlinear circuit re-factors on every Newton sub-iteration. Agent `read`/`getDiagnostics`/`run` pay the factorization once per analysis ([PL_AGA backlog](./agent-api.plan.md#backlog), "importCircuit scales" → Remaining).
  - **Forks for pickup:** algorithm (KLU-style Gilbert–Peierls + AMD/COLAMD ordering vs. Markowitz with threshold pivoting); a size threshold that keeps the dense path for small `m`; reusing the symbolic factorization across Newton iterations (the pattern is fixed per analysis). Also to check: the `simplifyMatrix` row elimination, the `origMatrix` restore copy for nonlinear circuits, and the singular-matrix escalation and gmin paths of the Newton loop (the stabilized re-factor was removed 2026-10-05, see Changelog).
  - **Scope / suggested phase:** Architectural. Start with `/dev-flow research` (algorithm + crossover size, measured on the example corpus and a synthetic 2000-node grid), then update the C_SIM concept/spec. Verify: the example-corpus results must not change beyond a tolerance, plus the RULE_TEST_002 devmode check.
  - **Return trigger:** after `task_E_AGT` closes (its owed manual free-run/run checks exercise the same solver), or earlier if the developer asks or a real circuit's analysis exceeds ~1 s.
  - **Promoted 2026-10-05:** concept [C_SLV](./linear-solver.concept.md) (draft; DEC_01–05 resolved: in-house, fully sparse, Auto + UI mode, same singular test, session mode + unsaved per-document API override). Next: SP_SLV.
  - **Spike concluded 2026-10-05:** [sparse-solver.spike.md](./sparse-solver.spike.md). Recommended approach: a CPU sparse LU in the engine's own Java (transversal, ordering, Gilbert–Peierls with threshold pivoting, growth-checked refactor), with a dense path below m ≈ 64. Rejected: WebGPU and server offload. WASM-KLU is conditional. Next step: a C_SIM concept update and an interview on the forks listed in the spike Conclusion.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
| 2026-10-05 | Fix (task_20261005_153627_spike-solver-defects, found by the [sparse-solver spike](./sparse-solver.spike.md)): a nonlinear LU failure with the singular-matrix stabilizers already active no longer stamps the stabilizers into the matrix `lu_factor` had overwritten in place and re-factors it (a wrong solve when the retry passed); it escalates as SP_SIM_02 SINGULAR says (recovery: panic 3 + re-stamp + `Singular matrix!` warning; else stop). Live checks `singularReportedNotRetried`, `recoversAfterReset` (scenario `solver_defects`). |
| 2026-10-05 | Fix (same task, found while writing [SP_SLV](./linear-solver.sp.md)): the singular-matrix message named the unknown of a reduced column by its full-system index (`describeMatrixVariable`), i.e. the wrong node or source after any row reduction; it now maps through `mapCol` (SP_SLV_01_09). Live check `singularVariableMapped` (fails on the pre-fix build with `nodeVoltage(node=3)`). |
