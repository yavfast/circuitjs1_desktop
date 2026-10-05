# Implementation Plan: Linear System Solver  {#PL_SLV}

> **Code:** PL_SLV
> **Status:** in-progress
> **Created:** 2026-10-05
> **Updated:** 2026-10-05
>
> **Concept:** [C_SLV](./linear-solver.concept.md)
> **Specification:** [SP_SLV](./linear-solver.sp.md)
> **Depends on:** [PL_SIM](./simulator-engine.plan.md) (engine as it stands, incl. the 2026-10-05 solver-defect fixes)
> **Used by:** —
>
> This plan implements SP_SLV in six phases.
> 1. Record a bit-identity baseline and the test infrastructure.
> 2. Build the pure sparse kernel with JUnit tests.
> 3. Move the store and the row reduction behind the existing engine, still on the dense path only, gated by bit identity over the whole example corpus.
> 4. Add the sparse path, the solver mode and the Agent API surface.
> 5. Add the UI row, the MCP tool and the documentation.
> 6. Run performance and full verification.

## Goal

Large circuits must analyse and step with a cost that follows their non-zeros, so that a 2000-node circuit analyses in under a second and a 1000-node nonlinear circuit steps in milliseconds. Small circuits keep today's results bit for bit. The user can force either path in Other Options, and agents can override the path per document without saving it. The intent is recorded in [task_C_SLV](../.dev_flow/tasks/task_C_SLV.md): today's dense O(m³) solver makes large circuits unusable (22 s analysis at m ≈ 2000; 5.9 s per step for a 1000-node diode ladder).

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Language | Java 17 language level, GWT 2.12-compatible (RULE_STYLE_001, RULE_STYLE_002) | Engine language; the solver runs in the page |
| Package | new `com.lushprojects.circuitjs1.client.solver` (leaf layer: imports `java.*` and `CircuitMath` only; no `CircuitSimulator`, GWT or document types) | Keeps the client root from growing; the solver holds per-stamp state, so it is not a stateless helper and RULE_STRUCT_007's `util/` placement does not apply; a leaf so the engine (L3) may use it and JUnit can run it without GWT (`CircuitMath` has no imports); the layer decision is recorded in `.dev_flow/rules/architecture.md` in P2 (RULE_ARCH_001) |
| Arrays | `int[]` / `double[]` growable by doubling; no boxed collections in the factor, refactor or solve loops | Plain JS arrays under GWT; boxing would multiply the 1.5–2× plain-array penalty the spike measured |
| Slot lookup during assembly | open-addressing hash table keyed by the (row, col) `int` pair (the hash mixes the two ints; keys stored in two `int[]`) → slot index; no `long` keys, no boxed `HashMap` | GWT emulates `long` and boxes map keys, both slow in the stamp loop ([js-api-surface](../.dev_flow/skills/automation/js-api-surface.md) GWT costs) |
| Ordering | minimum degree on the symmetrized pattern (quotient-graph free, simple degree updates) | The spike's prototype; AMD is the backlog replacement above m ≈ 5000 |
| Transversal | MC21-style depth-first augmenting paths with lookahead | Needed for zero-diagonal VS rows and the reduction's misaligned rows (spike) |
| Factorization | left-looking Gilbert–Peierls, structural reach by DFS, threshold partial pivoting (1e-3, diagonal preferred), absolute 1e-14 singularity test | SP_SLV §02_07 |
| Kernel tests | JUnit 5 (`junit-jupiter`, test scope) with maven-surefire; `src/test/java/.../client/solver/`; new npm script `test:unit` (`mvn test`) | PL_SLV_DEC_01; GWT compile does not run the test phase |
| Integration tests | live harness (`tests/live/harness.mjs`): new scenarios `solver_corpus` and `solver_paths`; `solver_defects` extended | RULE_TEST_006; the agent API is the observation channel |
| Performance tests | spike kit `.dev_flow/cache/sparse-spike/browser_bench.mjs` | Same cases as the spike, comparable numbers |
| Branch | `feat/sparse-solver` from `master`; one commit per phase after its review | Git workflow: one reviewable unit per phase |

## Required Knowledge

| Kind | Ref | Applies to | Note |
|------|-----|-----------|------|
| rule | RULE_ARCH_001 (layers), RULE_STRUCT_007 (util / no root growth) | P2, P3 | `client/solver/` imports `java.*` and `CircuitMath` only; record the package in the layer list of `.dev_flow/rules/architecture.md` in P2 |
| rule | RULE_ARCH_006 (document vs session scope) | P4 | override on `CircuitDocument`, session default on the session |
| rule | RULE_ERR_001 / RULE_ERR_002 | P3, P4 | singular and matrix errors via `simulator.stop` / `warn`, never exceptions from the Newton loop |
| rule | RULE_STYLE_001/002/003/005/008 | all | Java 17, GWT-safe APIs, `Locale.LS` for the dialog labels, javadoc on public solver contracts, no printf logging |
| rule | RULE_TEST_001/002/006/007 | P3–P6 | build before commit; devmode check (analog, digital, subcircuit) after engine changes; live harness; NW.js MCP e2e for the `circuit_sim` change |
| skill (apply) | simulator/mna-stamping (Pitfalls 6, 7, 9), simulator/newton-raphson-loop (Pitfall 6), simulator/solver-performance | P2–P4 | current |
| skill (apply) | automation/agent-run-behaviour, automation/js-api-surface | P4, P5 | agent run, GWT cost traps |
| skill (update) | simulator/solver-performance, simulator/mna-stamping | P6 | replace "dense today" facts with the delivered solver; record new pitfalls |

## Progress

- [x] [Phase 1 — Baseline and test infrastructure](#PL_SLV_P1)
- [ ] [Phase 2 — Sparse kernel](#PL_SLV_P2)
- [ ] [Phase 3 — System store and reduction, dense path only](#PL_SLV_P3)
- [ ] [Phase 4 — Sparse path, solver mode, Agent API](#PL_SLV_P4)
- [ ] [Phase 5 — Other Options row, MCP, documentation](#PL_SLV_P5)
- [ ] [Phase 6 — Performance, full verification, propagation](#PL_SLV_P6)

## Phases

### Phase 1 — Baseline and test infrastructure (`tests/live/harness.mjs`, `pom.xml`, `package.json`) [DONE]  {#PL_SLV_P1}

**Depends on:** none (runs on the pre-change build)
**Implements:** the measurement basis of [SP_SLV_05_02](./linear-solver.sp.md#SP_SLV_05_02) ("Dense path below the threshold is today's solver")
**Verify:** two `solver_corpus` record runs on the same build give identical files; `npm run test:unit` runs one smoke test and passes; `npm run buildgwt` unaffected (no test classes in the GWT output)

What to create:
| Entity | Module | Purpose |
|--------|--------|---------|
| scenario `solver_corpus` | tests/live/harness.mjs | Every example (noise-source examples excluded with the existing `hasNoiseSource`), on a background document: import, `run {reset: true, span: 200 × maxTimeStep}`, read every net voltage. `SOLVER_CORPUS=record` writes `OUT_DIR/solver_corpus.json`; default mode compares against `tests/live/fixtures/solver_corpus.json` exactly (bit for bit via the shortest round-trip number text), reporting per example `identical` / `differs` (max abs and relative difference) and the reduced size `m` once P4 exposes it |
| fixture | tests/live/fixtures/solver_corpus.json | The baseline recorded on the build of the solver-defects fix commit, before any P3 change |
| JUnit setup | pom.xml (`junit-jupiter` 5.x test dep, `maven-surefire-plugin` 3.x), package.json (`test:unit`: `mvn -q test`) | Kernel tests in P2 |
| smoke test | src/test/java/com/lushprojects/circuitjs1/client/solver/SmokeTest.java | Proves the wiring |

Notes:
- The baseline must be recorded before P3 touches `CircuitSimulator`; the fixture file is committed with this phase.
- `scripts/dev_n_build.js` passes `skipTests` to its maven runs; it keeps doing so (packaging does not need the tests).
- Done 2026-10-05: the fixture merges three record runs of master e531346 (327 examples). Seven examples differ between runs of one build — oscillating gates and op-amps draw from the unseeded `RandomUtils` (TD_20261005_220500_run-rng-determinism) — and are excluded; compare mode re-runs a differing example up to twice.

### Phase 2 — Sparse kernel (`client/solver/`) [TODO]  {#PL_SLV_P2}

**Depends on:** Phase 1 (JUnit)
**Implements:** [SP_SLV_01_07](./linear-solver.sp.md#SP_SLV_01_07), [SP_SLV_01_08](./linear-solver.sp.md#SP_SLV_01_08), [SP_SLV_01_09](./linear-solver.sp.md#SP_SLV_01_09) (report fields of the kernel), [SP_SLV_02_07](./linear-solver.sp.md#SP_SLV_02_07) (steps S, R, F), [SP_SLV_02_08](./linear-solver.sp.md#SP_SLV_02_08) (sparse solve)
**Verify:** [SP_SLV_05_01](./linear-solver.sp.md#SP_SLV_05_01) rows "Linear accuracy", "Refactorization used" (kernel level: refactor accepted on value-only changes), "Growth fallback", "Structural singularity" and "Numeric singularity" at kernel level (the engine-level forms, through the harness wrapper, are verified in P4) — as JUnit tests on the spike's captured matrices (a subset: `grid_22`, `cladder_500`, `dladder_500`, `dgrid_14`, as test resources) and generated MNA-like matrices (grids, ladders, random nets with floating sources and controlled-source rows, the spike's `matgen` ported); plus: sparse vs `CircuitMath.lu_factor/lu_solve` on 200 random non-singular systems m ∈ [1, 80] agree within backward error 1e-13; m = 0 and m = 1

What to create:
| Entity | Module | Purpose |
|--------|--------|---------|
| `CscPattern` | solver/CscPattern.java | Compressed-column pattern + values, build from (row, col) lists, slot lookup |
| `Transversal` | solver/Transversal.java | Maximum transversal with lookahead → `colPerm`, unmatched column |
| `MinimumDegree` | solver/MinimumDegree.java | Order on the symmetrized pattern |
| `SymbolicAnalysis` | solver/SymbolicAnalysis.java | `colPerm`, `order`, `builtFor`, structural singularity |
| `SparseLu` | solver/SparseLu.java | Full factorization (reach by DFS, threshold pivoting), refactorization with the per-step pivot check, solve; owns `reach`, L, U, `pivotRow`, `fullBuiltFor` |
| `SingularityReport` | solver/SingularityReport.java | column, row, pivotAbs, unknown (variable text is filled by the engine, which knows nodes and sources) |
| tests | src/test/java/.../solver/{TransversalTest, MinimumDegreeTest, SparseLuTest}.java, src/test/resources/solver/*.json | As in Verify |
| layer rule | .dev_flow/rules/architecture.md (+ `_index.yaml` summary) | Leaf package `client/solver/` added to the layer list (RULE_ARCH_001) |

Pseudocode sketch (full factorization, step k):

    c := colPerm[order[k]]
    x := sparse triangular solve of L[:, <k] against column c (reach by DFS, kept even if 0)
    cand := reach rows not yet pivotal; candMax := max |x[r]| over cand
    if candMax < 1e-14: return report(c, argmax or -1, candMax)
    p := paired row of c if |x[paired]| >= 1e-3 * candMax else argmax
    U[:, k] := x on pivotal rows ∪ {p}; L[:, k] := x on cand \ {p} divided by x[p]

### Phase 3 — System store and reduction, dense path only (`client/solver/LinearSystem.java`, `client/CircuitSimulator.java`) [TODO]  {#PL_SLV_P3}

**Depends on:** Phase 1 (baseline), Phase 2 (`CscPattern`)
**Implements:**
- [SP_SLV_01_03](./linear-solver.sp.md#SP_SLV_01_03)–[SP_SLV_01_06](./linear-solver.sp.md#SP_SLV_01_06) (store, reduced system, snapshot, dense workspace);
- [SP_SLV_02_01](./linear-solver.sp.md#SP_SLV_02_01)–[SP_SLV_02_04](./linear-solver.sp.md#SP_SLV_02_04) (beginStamp, addEntry/addRhs, dumpSystem, reduce);
- [SP_SLV_02_10](./linear-solver.sp.md#SP_SLV_02_10) (engine rewiring), with `selectPath` fixed to `DENSE`.

**Verify:**
- [SP_SLV_05_01](./linear-solver.sp.md#SP_SLV_05_01) "reduce — Bit identity": JUnit, with today's `simplifyMatrix` copied verbatim into the test as the oracle, on 500 generated stamp sequences with `lsChanges`/`rsChanges` rows, constants and restarts.
- [SP_SLV_05_02](./linear-solver.sp.md#SP_SLV_05_02) "Dense path below the threshold": live `solver_corpus` identical for every example. Every example qualifies here, because the path is dense everywhere in this phase.
- [SP_SLV_05_04](./linear-solver.sp.md#SP_SLV_05_04) "Empty reduced system", "Reduction matrix error", "One unknown".
- Regression: `solver_defects`, `agent_run`, `agent_freerun`, `agent_bg`, `loadstate`, `verify_defects`, `roundtrip`.
- `frame_cost` and `import_cost` (opt-in) within 5 % of the P1 build.
- RULE_TEST_002 devmode check.

What to create / change:
| Entity | Module | Purpose |
|--------|--------|---------|
| `RowInfo` | moved to solver/RowInfo.java (public) | Shared by the store and the engine |
| `LinearSystem` | solver/LinearSystem.java | Per-document solver: store (open-addressing slot map), reduction in today's order, reduced system, snapshot, dense workspace and dense factor/solve via `CircuitMath`, `dumpSystem`, singularity report (dense: `lastLuFail*`), phase state |
| `CircuitMath` | CircuitMath.java (unchanged; GWT-free, no imports) | Dense kernel used by `LinearSystem` |
| `CircuitSimulator` | CircuitSimulator.java | `circuitMatrix`/`origMatrix`/`circuitRightSide`/`origRightSide`/`circuitPermute`/`circuitRowInfo`/`circuitNeedsMap` replaced by one `LinearSystem`; `stampMatrix`/`stampRightSide`/`stampNonLinear` delegate; `stampCircuit`, `simplifyMatrix` (removed), the linear factor, the Newton restore/factor/solve, `applySolvedRightSide`, `dumpCircuitMatrix`, `describeMatrixVariable` use it; the `circuitMatrix == null` sentinel becomes `!linearSystem.hasSystem()` at every current use |

Notes:
- The dense `m ≤ 12` pre-factor copy (SP_SLV §01_06) lands here with `dumpSystem`.
- `sanitizeStampValue` stays in `CircuitSimulator` (it writes `converged`).

### Phase 4 — Sparse path, solver mode, Agent API (`client/solver/`, `CircuitSimulator`, `CircuitDocument`, `agent/`) [TODO]  {#PL_SLV_P4}

**Depends on:** Phases 2, 3
**Implements:**
- [SP_SLV_01_01](./linear-solver.sp.md#SP_SLV_01_01), [SP_SLV_01_02](./linear-solver.sp.md#SP_SLV_01_02), [SP_SLV_01_10](./linear-solver.sp.md#SP_SLV_01_10), [SP_SLV_01_11](./linear-solver.sp.md#SP_SLV_01_11);
- [SP_SLV_02_05](./linear-solver.sp.md#SP_SLV_02_05)–[SP_SLV_02_09](./linear-solver.sp.md#SP_SLV_02_09);
- [SP_SLV_02_10](./linear-solver.sp.md#SP_SLV_02_10) sparse rows (snapshot restore, factor and solve on the sparse path, the restamp trigger);
- [SP_SLV_02_11](./linear-solver.sp.md#SP_SLV_02_11), [SP_SLV_02_12](./linear-solver.sp.md#SP_SLV_02_12);
- [SP_SLV_03_01](./linear-solver.sp.md#SP_SLV_03_01) (mode strings, stored-preference fallback, constants);
- [SP_SLV_04](./linear-solver.sp.md#SP_SLV_04).

**Verify:**
- [SP_SLV_05_01](./linear-solver.sp.md#SP_SLV_05_01), every row except "Options dialog".
- [SP_SLV_05_02](./linear-solver.sp.md#SP_SLV_05_02): all invariants, the memory probe included.
- [SP_SLV_05_03](./linear-solver.sp.md#SP_SLV_05_03) "Agent compares paths", "Background document and session change" (session default set through the preference key in the test), "Singular escalation unchanged".
- [SP_SLV_05_04](./linear-solver.sp.md#SP_SLV_05_04), every row.
- `solver_corpus` in `AUTO`: identical for every example with m ≤ 64.
- New live scenario `solver_paths`: dense vs sparse agreement over the corpus, the analog switch growth, the carried state across time-step changes, the simControl rows, structural and numeric singularity at engine level through a harness wrapper, the restamp-only mode change, `busy`.
- Regression set of P3.
- RULE_TEST_002 devmode check.

What to create / change:
| Entity | Module | Purpose |
|--------|--------|---------|
| `SolverMode`, `SolvePath`, `SolverInfo`, `CarriedState` | solver/*.java | SP_SLV §01 |
| `LinearSystem` | solver/LinearSystem.java | `selectPath` (DENSE_MAX_SIZE = 64), sparse factor/solve through `SparseLu`, `pending` + `mergePending`, carried state per engine analysis, counters |
| session default | `BaseCirSim` (session) reading/writing `OptionsManager` key `solverMode` | SP_SLV §01_01 |
| document override + `solverRestampPending` | CircuitDocument.java | SP_SLV §02_09; consumed in the frame loop (`CircuitDocument` ~L558), `RunController` start, `ensureAnalysed` |
| simControl `solver` | agent/SimControlOps.java (+ `AgentApi` contract-class table) | SP_SLV §02_11; not mutating, `busy` while running |
| Diagnostics `solver` | agent/DiagnosticsOps.java; shared `SolverInfo` → JSON helper in `agent/` | SP_SLV §02_12 |
| scenario `solver_paths` | tests/live/harness.mjs | As in Verify |

### Phase 5 — Other Options row, MCP, documentation (`dialog/EditOptions.java`, `mcp/server/src/tools.js`, docs) [TODO]  {#PL_SLV_P5}

**Depends on:** Phase 4
**Implements:** [SP_SLV_02_13](./linear-solver.sp.md#SP_SLV_02_13), [SP_SLV_02_14](./linear-solver.sp.md#SP_SLV_02_14); the SP_AGA edits listed in [SP_SLV_02_11](./linear-solver.sp.md#SP_SLV_02_11)
**Verify:**
- [SP_SLV_05_01](./linear-solver.sp.md#SP_SLV_05_01) "Options dialog — Session default": live, through the dialog driven by CDP as in the existing editor scenarios. "Restart app" is checked by reloading the page with the stored preference.
- [SP_SLV_05_03](./linear-solver.sp.md#SP_SLV_05_03) "MCP" and "User forces Dense as rollback": `npm run test:mcp`, extended with the `circuit_sim` `solver` call (RULE_TEST_007).
- Locale check: the "Solver", "Auto", "Dense" and "Sparse" keys exist in `locale_uk.txt` and fall back to English elsewhere.

What to change:
| Entity | Module | Purpose |
|--------|--------|---------|
| Solver row | dialog/EditOptions.java | Choice before the time-step rows; later indices shift in `getEditInfo` and `setEditValue` |
| locale keys | public/locale_uk.txt (others fall back to English) | RULE_STYLE_003 |
| `circuit_sim` | mcp/server/src/tools.js | enum `solver`, argument `mode`, description sentence, output schema `solver`; `circuit_diagnostics` output schema |
| docs | docs/agent-api.sp.md (§02_09, §01_11, contract-class table), docs/mcp-server.sp.md, docs/JS_API.md (agent table), agent skill reference for `circuit_sim` | Propagate the published contracts |

### Phase 6 — Performance, full verification, propagation [TODO]  {#PL_SLV_P6}

**Depends on:** Phases 1–5
**Implements:** [SP_SLV_05_05](./linear-solver.sp.md#SP_SLV_05_05); [SP_SLV_06_01](./linear-solver.sp.md#SP_SLV_06_01) (the runtime rollback is verified); document status changes
**Verify:**
- [SP_SLV_05_05](./linear-solver.sp.md#SP_SLV_05_05) targets with the spike kit on the headless build; the results go to `.dev_flow/cache/sparse-spike/out/` under `delivered_*` names.
- [SP_SLV_06_01](./linear-solver.sp.md#SP_SLV_06_01) runtime rollback: with the session default `Dense`, `solver_corpus` is identical to the P1 fixture for every example.
- The full live suite (`npm run test:live`), `npm run test:mcp`, `npm run test:unit`.
- The RULE_TEST_002 devmode check in NW.js: analog `lrc.txt`, digital `counter.txt`, subcircuit `alu74181.txt`, a 1000-node generated circuit, and the Other Options row.
- The C_SLV and SP_SLV statuses become `active`; C_SIM and SP_SIM describe the delegation.

What to change:
| Entity | Module | Purpose |
|--------|--------|---------|
| concept/spec | docs/simulator-engine.{concept,sp}.md, docs/linear-solver.{concept,sp}.md, docs/_index.md | Statuses and the delegated mechanisms |
| skills | simulator/solver-performance.md, simulator/mna-stamping.md | Delivered facts and new pitfalls |
| constants | `DENSE_MAX_SIZE` | Confirm or adjust from the measured crossover; any change updates SP_SLV §03_01 first |

## Backlog

- **AMD ordering.** Approximate minimum degree in place of minimum degree. Return when: ordering takes more than 25 % of the sparse analysis time at m ≥ 5000 in the P6 measurements or later reports.
- **Block-triangular form.** Return when: a real circuit shows fill or time that a block decomposition would remove (more than 2× over the P6 target).
- **WASM numeric kernel** ([spike](./sparse-solver.spike.md) conditional). Return when: the sparse factorization exceeds 60 % of step time at m ≥ 1000 after P6.
- **Per-element step cost.** About 3 µs per element per step becomes the next bottleneck for large linear circuits. Return when: a large linear circuit steps below the user's need after P6, or on the developer's request.
- **The rest of BL-A11** (JUnit for `CircuitMath`, `StringTokenizer`, `ExprParser`, `UnitParser`; `npm run check`). Return when: the next change to `CircuitMath`, `ExprParser` or `UnitParser` is planned, or a defect is found in one of them; P1 provides the JUnit wiring.

## Design Decisions  {#PL_SLV_DEC}

### DEC_01 — How the numerical kernel is tested  {#PL_SLV_DEC_01}

> **Status:** resolved
> **Date:** 2026-10-05

**Question:** Test the sparse kernel with plain-JVM JUnit, or only through the compiled build in the live harness?

**Options considered:**
| Option | Consequence |
|--------|-------------|
| A — JUnit for the GWT-free `client/solver/` kernel + live harness for integration | Fast deterministic numerical tests; a new test dependency and `npm run test:unit`; the first slice of BL-A11 |
| B — Live harness only, through a debug entry in the agent bridge | No new infrastructure, but each kernel test needs a GWT build and Chromium, and a debug API in the production bridge |
| C — A, plus finish BL-A11 in this plan | More coverage, but work outside the solver |

**Decision:** A — JUnit for the kernel, live harness for integration.
**Rationale:** Numerical code needs many fast, exact tests on fixed matrices; the kernel is GWT-free by design.
**Rejected because:** B — slow loop and a production debug API; C — scope beyond this feature.

## Changelog

| Date | Change |
|------|--------|
| 2026-10-05 | Initial version; PL_SLV_DEC_01 resolved in interview. |
| 2026-10-05 | Pre-commit review: package is a leaf layer importing `CircuitMath` (not a `util/` helper), layer rule recorded in P2; P4 claims the sparse rows of §02_10; singularity rows split kernel (P2) / engine (P4); BL-A11 trigger made concrete. |
