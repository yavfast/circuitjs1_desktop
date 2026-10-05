# Spike: Sparse solver for large circuits; WebGPU or a server for acceleration

> **Status:** concluded
> **Created:** 2026-10-05
> **Updated:** 2026-10-05
> **Author:** main
> **Time-box:** 1 session; two parallel investigations (Q1 with a throwaway benchmark prototype; Q2+Q3 desk research, 15 sources max)
> **Scope:** codebase + external + prototype (scratch only, never production code)
> **Mode:** single (two delegated investigations split by question, not by lens)
>
> **Target concept:** [C_SIM](./simulator-engine.concept.md) (update) — backlog item TD_20261005_145712_sparse-solver in [PL_SIM](./simulator-engine.plan.md#backlog)
> **Serves:** —
> **Question(s):**
> 1. Where does the time go for a large circuit today (factorization at analysis, per-step solve, per-Newton refactor, element `doStep`, per-step overhead), and which sparse LU approach fits this solver (GWT-compiled Java, MNA matrices with zero-diagonal voltage-source rows, `simplifyMatrix`, Newton refactor with a fixed pattern)? What speed-up at what matrix size, and where is the crossover with the current dense path?
> 2. Is WebGPU (or another in-browser accelerator: WASM/SIMD, Web Workers) worth using for the solve in this app — availability in the NW.js runtime, and the gain for the matrix sizes and per-timestep call pattern of this simulator?
> 3. Is offloading the computation to a server (local native process or remote) worth it — per-timestep solve vs a whole batch run, latency, and what it would cost in duplicated engine semantics?

## Context

The solver keeps a dense `m × m` `double[][]` matrix and factors it with `CircuitMath.lu_factor` (Gaussian elimination with partial pivoting, O(m³)); `lu_solve` is O(m²). A linear circuit is factored once per analysis (2000 unconnected resistors: ~200 s, [mna-stamping Pitfall 7](../.dev_flow/skills/simulator/mna-stamping.md)); a nonlinear circuit re-factors on every Newton sub-iteration of every timestep. Only `CircuitSimulator` touches the matrix (elements stamp through `stampMatrix`/`stampRightSide`), so a solver swap stays local. [INTERNALS.md](../INTERNALS.md) already notes that a sparse solver would speed up large linear circuits. The app runs as GWT-compiled JavaScript inside NW.js 0.64.1 (`package.json`); an agent `run` and a future "Run for…" command ([TD_20261005_145712_forced-run](../.dev_flow/todos/_index.md)) are batch advances where per-step latency matters less than throughput.

## Exploration Log

_(entries are written by main from the researchers' returned conclusions)_

### Entry 1 — 2026-10-05 — Q1: where the time goes; sparse LU prototype

**What was tried / researched:** the real build (`target/site`, compiled 2026-10-05) was driven in headless Chromium 153 over CDP. The GWT-emitted `lu_factor`, `lu_solve`, `stampCircuit` and `simplifyMatrix` were wrapped with timers and CPU profiles were taken. Runs used a background document, because on the visible tab the canvas draw dominates every step. The circuits were synthetic: resistor grids, RC ladders, diode ladders and diode grids. Matrices were captured after `simplifyMatrix`, and the whole example corpus was swept for m. A throwaway JS prototype then ran in Node 26: a faithful port of the dense `lu_factor`/`lu_solve`, and a left-looking Gilbert–Peierls sparse LU in the style of KLU/CSparse `cs_lu`. The sparse LU uses threshold partial pivoting (tol 1e-3, diagonal preferred), minimum degree on A+Aᵀ in place of AMD, a maximum transversal with lookahead (MC21-style), and a refactor path with a pivot-growth check. It was benchmarked on the captured and synthetic matrices. The Node port of the dense LU runs within about 25 % of the browser times. Kit: [.dev_flow/cache/sparse-spike/](../.dev_flow/cache/sparse-spike/README.md).

**Findings — today (dense):**

| Circuit | m after simplify | `lu_factor` | Solve per step | Whole step |
|---|---|---|---|---|
| R grid 10×10 | 99 | 3 ms | 0.09 ms | — |
| R grid 22² | 483 | 245 ms | 0.9 ms | — |
| R grid 32² | 1023 | 2.5 s | 4.0 ms | 7.3 ms |
| R grid 45² | 2024 | 21.7 s | 16.8 ms | — |
| RC ladder | 1002 | 2.3–2.9 s | 3.9 ms | 7.4 ms |
| RC ladder | 2002 | 21 s | 15.8 ms | 28.4 ms |
| Diode ladder (nonlinear) | 502 / 1002 | 254 ms / 2.24 s per factor | — | 404 ms / 5.9 s (1.5 / 2.6 Newton iterations per step) |
| Diode grid 14² (nonlinear) | 197 | 13.8 ms per factor | — | 3.6 s (240 iterations per step; `lu_factor` 92 %) |

- **Factorization dominates analysis.** The factor grows as m^3.1–3.2, about 8 ns per flop with the column-strided loop. It takes about 99 % of analysis; stamp, allocation and `simplifyMatrix` together are about 200 ms at m ≈ 2000, of which `simplifyMatrix` is 156 ms (O(m²) row scans with restarts).
- **Linear stepping.** The solve is O(m²) and about 55 % of a step at m = 1000–2000. The rest is per-element work: about 3 ms per step at 1000 elements.
- **Nonlinear stepping.** Every Newton iteration re-factors, and it also copies `origMatrix` back into `circuitMatrix` at O(m²) (about 7 ms at m = 2000).
- **Example corpus (342 circuits).** m after simplify: median 7, 90th percentile 21, maximum 87 (`grid2.txt`). Only 3 examples have m ≥ 64 and none reaches 100. A sparse solver changes nothing for the examples; it is for large circuits built by users or agents.
- **Defect found (main confirmed it in code).** An agent `run` with `reset: true` analyses twice. `RunController` calls `doc.ensureAnalysed()` at the start to resolve probes (`RunController.java:241`). In the first slice it then calls `sim.resetAction()`, which runs `needsAnalysis` + `resetSolverState`, and `ensureAnalysed()` again (`:364-366`). A 2000-node grid therefore pays 2 × 21.7 s. *Fixed 2026-10-05 (task_20261005_153627_spike-solver-defects).*
- **Defect found (main confirmed it in code).** The singular-matrix retry of the Newton loop works on a damaged matrix (`CircuitSimulator.java:2104-2117`). When stabilizers are already active and `lu_factor` fails, `stampSingularMatrixStabilizers()` stamps into `circuitMatrix`, which `lu_factor` has already partly factored and row-swapped in place, and that matrix is then factored again. *Fixed 2026-10-05 (same task).*

**Findings — sparse prototype (Node):**

| Matrix | m | Dense factor | Ordering (once) | Sparse factor | Refactor | Solve dense / sparse | Fill (L+U)/nnz |
|---|---|---|---|---|---|---|---|
| Captured R grid 45² | 2024 | 27.3 s | 16.5 ms | 5.5 ms | 2.2 ms | 4.6 / 0.15 ms | 5.4 |
| Captured RC ladder | 2002 | 29 s | 0.7 ms | 0.40 ms | 0.093 ms | 4.8 / 0.044 ms | 1 |
| Captured diode ladder | 1002 | 3.6 s | 0.3 ms | 0.24 ms | 0.055 ms | 1.1 / 0.022 ms | 1 |
| Captured diode grid | 485 | 152 ms | 2 ms | 0.78 ms | 0.24 ms | 0.26 / 0.027 ms | 3.9 |
| Synthetic grid | 5043 | ~5 min (extrapolated) | 80 ms | 25 ms | 11 ms | — / 0.29 ms | 7.0 |
| Random net (degree 3–6, floating VS, controlled sources, 10 decades of values) | 5309 | — | 8–13 ms | 4.8–27 ms | 0.4 ms | — / 0.2–0.4 ms | 2.5–7.2 |

- **Accuracy.** ‖Ax−b‖/‖b‖ is 1e-13 to 1e-18 on grids and ladders, the same as dense. On the random nets both dense and sparse give 1e-8 to 1e-10; that comes from conditioning, not the algorithm.
- **Crossover.** A full sparse factor beats dense from m ≈ 30–40, and a refactor from m ≈ 15–25. Below that, both paths take < 0.05 ms.
- **Plain arrays.** On plain JS arrays, which is what GWT emits for `int[]`/`double[]`, the sparse code runs 1.5–2× slower than on typed arrays. That is still more than 1000× ahead of dense at m ≈ 1000.
- **A quick win without sparse code.** A row-oriented dense LU that skips zero multipliers runs 100–500× faster on circuit matrices (196 ms vs 26.7 s on the 2000-node grid). It still keeps O(m²) memory and has no fill control.
- **Expected real gains.**
  - Analysis at m = 2000: 44 s (counting the double analysis) → about 0.25 s; after that, `simplifyMatrix` and allocation dominate.
  - Linear step: 2–7×, limited by the per-element work.
  - Nonlinear at m ≈ 1000: about 1000× per step.
  - The 240-iteration diode grid: about 13×.

**Findings — options and fit:**
- **Licences.** The project is GPL v2-or-later, so LGPL-2.1+ and BSD code can be combined with it.
- **KLU** (SuiteSparse, LGPL-2.1+) — BTF + AMD/COLAMD + Gilbert–Peierls + refactor. It is the reference design for circuit matrices.
- **[JKLU](https://github.com/rwl/JKLU)** (Java port, LGPL-2.1+, last commit 2012) — about 7.5k lines plus AMDJ, COLAMDJ, BTFJ and CSparseJ. It uses `java.lang` only, with no threads or reflection. To compile under GWT, the `printf` in `Dklu_version` must go. It is unmaintained and large.
- **[CSparseJ](https://github.com/rwl/CSparseJ)** (LGPL-2.1+) — smaller, with `cs_lu` and AMD.
- **Kundert Sparse 1.3** (SPICE3, Markowitz pivoting, BSD-style) — C only.

**Code-specific pitfalls:**
1. `simplifyMatrix` assigns `mapRow` and `mapCol` independently, so after simplification the "diagonal" can be shifted. Without a transversal, fill doubled and the residual rose to 1e-4. A maximum transversal with lookahead fixes it.
2. Voltage-source rows have a zero diagonal, so static pivoting is not possible. The solver needs threshold partial pivoting, or the pairing from the transversal.
3. The non-zero pattern is not fixed across Newton iterations. `AnalogSwitchElm.doStep` stamps a resistor only in some states, and VCCS/CCCS skip derivatives when |dv| < 1e-6. The pattern must come from stamp calls, not values. Keep the union, and when a stamp lands outside it, extend the pattern and redo the symbolic step.
4. A refactor without a pivot-growth check gave a residual of 1e11 on the random net after large value changes. The refactor must fall back to a full factor with re-pivoting.
5. Keep the singularity semantics of the absolute 1e-14 pivot test. gmin, 1e-12 stabilizers and 1e8 Ω repairs make small pivots legitimate; KLU-style row scaling is an option. The sparse path needs equivalents of `lastLuFail*`, `dumpCircuitMatrix` and `describeMatrixVariable`.
6. Memory: `stampCircuit` allocates two dense m × m arrays, which is 400 MB at m = 5000. The stamping storage and `simplifyMatrix` must become sparse too (CSC + slot map), not only the factorization.
7. Pivot order changes results at round-off level, which is visible in chaotic circuits. A dense path below a threshold keeps small circuits bit-identical.
8. `invertMatrix` (CustomTransformerElm, ThreePhaseMotorElm) works on small matrices and is not affected.

**Open questions:**
- Measured on Chromium 153 / Node 26 only, not on the NW.js 0.64.1 runtime (Chromium 101).
- The GWT draftCompile output of a Java port was not measured; estimated 1.5–2× slower than the typed-array JS.
- Transistor arrays, BTF, and true AMD vs minimum degree at m > 5000 were not tested.
- The remaining per-element step cost (about 3 µs per element) was not split further.

---

### Entry 2 — 2026-10-05 — Q2/Q3: WebGPU, WASM, Web Workers, server offload

**What was tried / researched:** desk research (9 external searches) + a codebase count of GWT dependencies. Main spot-checked two claims: the bundled `node_modules/nw/nwjs/chromedriver --version` reports **Chromium 101.0.4951.67**, and the GWT import counts below.

**Findings:**
- **Runtime.** NW.js 0.64.1 = Chromium 101.0.4951.67 + Node 18.0.0 ([nwjs.io v0.64.1](https://nwjs.io/blog/v0.64.1/)). WebGPU is not available there: Chromium 94–101 had it only as an origin trial behind `--enable-unsafe-webgpu`, with the pre-release API. It shipped by default in Chrome 113, for Windows, macOS and ChromeOS ([Chrome blog](https://developer.chrome.com/blog/webgpu-release)). Linux support came much later and is limited ([crbug 40254953](https://issues.chromium.org/issues/40254953)). The current NW.js 0.109.1 = Chromium 146 ([nwjs.io v0.109.1](https://nwjs.io/blog/v0.109.1/)).
- **Precision.** WGSL has no f64; the only standard numeric extension is `shader-f16`. f64 is a native-only wgpu feature, and the web proposal is still open ([gpuweb#2805](https://github.com/gpuweb/gpuweb/issues/2805)). Where hardware has f64, it runs 16–64× slower than f32. WebGL (GLSL ES 3.0) is f32 only (not sourced). This solver needs f64: it has 1e8 Ω repair resistors, gmin stepping and ±1e12 clamps. Emulating f64 with two f32 values costs roughly 10–20× per flop (researcher's estimate).
- **Latency.** A `mapAsync` readback takes 5–15 ms of wall clock even for a trivial result ([gpuweb#4432](https://github.com/gpuweb/gpuweb/issues/4432), [gpuweb#3595](https://github.com/gpuweb/gpuweb/issues/3595)), and it is asynchronous. A free run needs < 1 ms per step, and each step depends on the previous solution.
- **GPU sparse LU literature.** GLU reports 19.6× over KLU on circuit matrices ([GLU, TVLSI](https://intra.ece.ucr.edu/~stan/papers/tvlsi_gpu_lu14.pdf)). A GPU pays off only above about 200 Mflop per factorization, i.e. well above m ≈ 1000. All these results are CUDA, factorization-only, with the symbolic analysis and pivoting on the CPU, and none does a per-timestep round trip.
- **WASM.** Generic WASM-vs-JS gains are 1.3–6×, and some mid-size inputs run slower ([arXiv 1901.09056](https://ar5iv.labs.arxiv.org/html/1901.09056)). Sparse LU is memory-bound, so the researcher expects about 1.5–3× over well-JIT'd JS (estimate). KLU is LGPL-2.1+ ([SuiteSparse](https://github.com/DrTimothyAldenDavis/SuiteSparse)), which is workable as a separate `.wasm` module. No existing KLU→WASM build was found. The call would fit one JSNI bridge cluster that copies `Float64Array`s in and out.
- **Web Workers** help only across independent runs (sweeps, Monte Carlo, several documents), never inside one timestep. A worker needs its own GWT module or a non-GWT solver. SharedArrayBuffer under COOP/COEP in NW.js 0.64.1 is unverified.
- **Engine outside the page.** 131 of 331 `client/` Java files import `com.google.gwt.*`, including 31 of 154 in `element/`. `CircuitSimulator.java` has 1 such import (`Window`) and `CircuitMath.java` has 0. The blocking kinds are `Context2d`, the GWT JSON classes, widgets and `Window`. A JVM or worker engine therefore needs a headless engine split first.
- **Server, per-timestep RPC.** The localhost round trip is about 130 µs over a Unix socket and about 334 µs over TCP ([source](https://nodevibe.substack.com/p/the-nodejs-developers-guide-to-unix)). At 1000 steps/s with 3 Newton iterations per step, that is 0.4–1 s of pure latency per second of simulation. An in-process dense solve at m ≈ 100 takes only tens of µs. Rejected.
- **Server, whole-run offload.** A JVM build is blocked by the GWT imports. ngspice/Xyce would need netlist translation for about 155 element types and their `doStep` behaviour: a high risk of semantic drift, against the single-engine design and the offline premise. It could pay off only for batch sweeps, and then as a worker pool running the same engine.
- **Local native helper** (N-API addon / child process with KLU). N-API costs microseconds per call, but every Newton iteration crosses from the page into Node. Each OS needs a prebuilt binary, rebuilt against the NW.js Node ABI on every upgrade. Expected gain over KLU-WASM is about 1.2–1.5×. Rejected in favour of WASM.

**Open questions:** SharedArrayBuffer/COOP-COEP behaviour in NW.js 0.64.1; the measured WASM-vs-JS gain for sparse LU (estimate only).

---

## Alternatives Considered

| # | Approach | Pros | Cons | Verdict |
|---|----------|------|------|---------|
| 1 | In-house sparse LU in Java (about 600–900 lines): transversal + minimum degree / AMD port + Gilbert–Peierls with threshold partial pivoting + refactor with growth check; dense path below m ≈ 64 | f64; runs where the engine already runs; 100–1000× on factor/refactor at m ≥ 500; examples stay bit-identical; no build, licence or JSNI cost | Pattern union and fallback logic; the stamping storage and `simplifyMatrix` must become sparse too; round-off differences above the threshold | candidate (recommended by the Q1 researcher) |
| 2 | Adopt JKLU (+ AMDJ, COLAMDJ, BTFJ, CSparseJ) as Java source under GWT | Proven KLU design including BTF; LGPL-2.1+ is compatible | About 7.5k+ lines, unmaintained since 2012, small GWT edits needed; still needs the same integration work as #1 | candidate (fallback to #1) |
| 3 | Row-oriented dense LU that skips zero multipliers | Very small change; 100–500× on circuit matrices; same O(m²) memory | No fill control; memory stays m² (1.6 GB at m = 10 000); weaker for dense-fill topologies | candidate (interim quick win, or the first step) |
| 4 | KLU compiled to WASM, called through one JSNI cluster | Best-known circuit LU; f64; about 1.5–3× over JS sparse (estimate) | Emscripten build, LGPL module, copies per call, no existing port | needs more data — only if #1 profiles above 60 % at m ≥ 1000 |
| 5 | WebGPU / WebGL | GPU throughput for very large factorizations | Not in NW.js 0.64.1 (Chromium 101); no f64 in WGSL/GLSL; 5–15 ms async readback per step; gains only well above m ≈ 1000 | rejected |
| 6 | Server / local native helper (RPC per step, whole-run offload, N-API KLU) | Native speed; threads for batches | 130–334 µs per round trip × steps × iterations; a JVM engine needs a headless split; SPICE translation drifts semantically; per-OS binaries; against the offline premise | rejected |
| 7 | Web Workers / a worker pool of the same engine | Parallelism across independent runs | Nothing inside one timestep; needs a separate GWT module or headless engine | rejected for the solve; candidate for future sweeps / Monte Carlo |

## Conclusion

**Verdict:** concluded. A sparse LU on the CPU, in the engine's own Java (option 1, or option 2 as a fallback), is the lever that pays: at m ≥ 500 it is 100–1000× faster per factorization and refactor, with the same accuracy. WebGPU and a server are not worth it for this simulator, for three reasons:
- The solve is sequential and needs f64.
- The runtime has no WebGPU.
- The per-step latency of a GPU or a round trip is larger than the whole sparse solve.

WASM-KLU is the only accelerator left, and only conditionally.

**Key constraints discovered:**
- Today's ceiling is the O(m³) dense factor: about 2.5 s at m ≈ 1000 and about 22 s at m ≈ 2000. Nonlinear circuits pay it on every Newton iteration (5.9 s per step for a 1000-node diode ladder).
- The example corpus never exceeds m = 87 (median 7), so the gain is only for large user- or agent-built circuits, and a dense path below m ≈ 64 keeps every example but three bit-identical.
- After the swap, the per-element step work (about 3 µs per element) and `simplifyMatrix` (O(m²)) become the next bottlenecks for linear circuits. The dense m × m stamping storage has to go as well (400 MB at m = 5000).
- The solver must handle: unpaired `mapRow`/`mapCol` after `simplifyMatrix` (needs a transversal); zero-diagonal VS rows (needs pivoting); a non-zero pattern that changes between Newton iterations (needs a pattern union + symbolic redo); refactor instability (needs a growth check + full-factor fallback); and the existing singularity diagnostics.
- NW.js 0.64.1 = Chromium 101 / Node 18: no WebGPU. WGSL has no f64. A `mapAsync` readback takes 5–15 ms.

**Recommendations for the concept:**
- Update C_SIM / SP_SIM for a solver seam inside `CircuitSimulator` + `CircuitMath`. It needs:
  - sparse stamping storage (CSC + slot map) and a sparse `simplifyMatrix`;
  - a sparse factor / refactor / solve with a dense path below a tunable threshold (start at m ≈ 64);
  - a pattern union with symbolic redo, a growth-checked refactor, and diagnostics parity.
- Decision forks for the interview:
  - in-house LU (#1) vs adopting JKLU (#2);
  - whether to ship the row-oriented dense LU (#3) first as a low-risk step;
  - AMD port vs minimum degree;
  - whether BTF is needed;
  - the threshold value and whether it is user-visible;
  - whether stamping storage becomes sparse in the same change.
- Verification: example-corpus results unchanged below the threshold and within tolerance above it; the kit in `.dev_flow/cache/sparse-spike/` as the performance baseline (rerun on the NW.js runtime); RULE_TEST_002 devmode check.
- Exclude WebGPU, the server, the native helper and worker-in-step from the concept. Defer WASM-KLU, triggered by option 1 profiling above 60 % at m ≥ 1000. Defer a worker pool to a sweep / Monte Carlo concept.

**Artifacts to keep:**
- `.dev_flow/cache/sparse-spike/` — benchmark kit, sparse prototype, captured matrices, results, corpus sizes, Q2/Q3 notes (cache entry `sparse-spike`).

**Artifacts to discard:**
- `/tmp/circuitjs1-desktop/sparse-spike/` — CPU profiles, the JKLU/CSparseJ shallow clones, scratch output (dies with `/tmp`).
