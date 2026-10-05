---
skill: solver-performance
domain: simulator
topics: [solver, performance, lu, sparse, profiling, large-circuit, webgpu, wasm, benchmark]
source: research (docs/sparse-solver.spike.md)
updated: 2026-10-05
---

# Solver performance (dense and sparse paths, accelerators)

## Context

Measured in the 2026-10-05 spike [docs/sparse-solver.spike.md](../../../docs/sparse-solver.spike.md). Use this skill before you profile the simulator, change `client/solver/` (`LinearSystem.reduce`/`factor`/`solve`, `SparseLu`), `CircuitMath.lu_factor`/`lu_solve` or `stampCircuit`, or propose GPU, WASM or server acceleration. The benchmark kit is in [.dev_flow/cache/sparse-spike/](../../cache/sparse-spike/README.md).

## Delivered (PL_SLV, 2026-10-05)

- **Two paths behind one class.** `client/solver/LinearSystem` holds the MNA system of a document: a slot store behind the stamping primitives (open addressing on the (row, col) int pair), the row reduction (the former `simplifyMatrix`, same decisions and summation order), the reduced system with its snapshot, and the dense or sparse path. Dense = the former `CircuitMath.lu_factor`/`lu_solve` computation, bit for bit (`solver_corpus` fixture). Sparse = `SparseLu` (transversal → minimum degree → left-looking Gilbert–Peierls with threshold pivoting 1e-3, paired row preferred; refactorization with a per-step pivot check; absolute 1e-14 singular test).
- **Mode.** `AUTO` = dense up to `DENSE_MAX_SIZE = 64` reduced unknowns, sparse above; session default in Other Options (preference `solverMode`); per-document override via `simControl {action: "solver", mode}` (not saved). Diagnostics `solver` shows `path`, `size`, `nonZeros`, `factorNonZeros`, counters.
- **Measured on the delivered build** (spike kit, background document, median of 3; `.dev_flow/cache/sparse-spike/out/results_delivered_*.json`): 45² grid analysis 0.21 s (21.7 s before); RC ladder m ≈ 2002 analysis 0.22 s, step 5.7 ms (21 s / 28 ms); diode ladder m ≈ 1002 analysis 0.1 s, step 6.6 ms (5.9 s per step); diode grid m = 197 step 4.3 ms (3.6 s); a 4900-node grid holds 6 MB of solver data (≈ 400 MB of dense tables before).
- **What the analysis of a large circuit now costs** is the node analysis, not the solver: a 2000-section RC ladder spent 1.7 s in `calcWireInfo` (each Ground element scanned every link of the ground node) before PL_SLV P6 indexed it.

## Key facts (measured before PL_SLV, spike)

- **Dense LU cost.** The factor grows as m^3.1–3.2, about 8 ns per flop with the column-strided loop. That is 3 ms at m ≈ 100, 245 ms at m ≈ 500, 2.5 s at m ≈ 1000 and 22 s at m ≈ 2000. The solve is O(m²): 4 ms at m ≈ 1000. Analysis is about 99 % `lu_factor`. Of the rest, `simplifyMatrix` (O(m²) row scans with restarts) takes 156 ms at m ≈ 2000.
- **Nonlinear circuits** factor once per Newton iteration, and each iteration also copies `origMatrix` back into `circuitMatrix` at O(m²). A 1000-node diode ladder takes 5.9 s per step.
- **Linear stepping** after a sparse solve is limited by per-element work (about 3 µs per element per step: `setNodeVoltages`, `doStep`).
- **The example corpus is tiny.** Over all 342 examples, m after simplify has a median of 7, a 90th percentile of 21 and a maximum of 87 (`grid2.txt`). Solver speed matters only for large circuits built by users or agents.
- **A sparse prototype** (Gilbert–Peierls + minimum degree + transversal + refactor, JS) factors m ≈ 2000 in 0.4–5.5 ms against 27 s dense, refactors in 0.1–2 ms and solves in 0.04–0.15 ms, with residuals equal to dense. It wins from m ≈ 30–40 for a full factor and m ≈ 15–25 for a refactor. On plain JS arrays (what GWT emits) it runs 1.5–2× slower than on typed arrays.
- **A row-oriented dense LU that skips zero multipliers** is 100–500× faster than the current loop on circuit matrices. It is a cheap step that needs no sparse data structures.

## Usage in this project

- **Profiling.**
  - Drive `target/site` headless over CDP.
  - Wrap the GWT-emitted functions: draftCompile keeps readable names such as `lu_factor` in the JS.
  - Run the circuit on a **background document**: `CircuitJS1Agent.call('createDocument')`, then `importCircuit` into it. On the visible tab, the canvas draw dominates every step and hides the solver.
  - `browser_bench.mjs` in the kit does all of this.
- **Choosing an approach.** CPU sparse LU in the engine's own Java is the lever (delivered). The dense path below the threshold keeps small circuits bit-identical, because a different pivot order changes round-off and chaotic circuits show it.
- **Instrumenting the delivered build.** GWT splits its namespace (`clcc`, `clccs`, …): the solver classes are in `clccs`; look a name up in every `clcc*` object. Instance fields are fully qualified properties (`this.com_lushprojects_circuitjs1_client_solver_LinearSystem_rightSide`). A method with no Java caller is pruned from the build (a test-only accessor, or a setter before its UI exists) — read fields instead. `tests/live` `solver_paths` shows the wrappers.

## Pitfalls

0. **Delivered-solver pitfalls (PL_SLV):**
   - **Bit identity needs the old summation order.** A slot sums its stamps in stamp order starting from +0; the reduction subtracts constant columns in ascending column order. Skipping absent positions of constant columns is safe only because a sum that starts at +0 never becomes −0.
   - **`arr[growingCall()] += x`** evaluates `arr` before the call: if the call grows (replaces) the array, the write lands in the old one (`LinearSystem.addEntry`, caught by JUnit).
   - **A refactorization must use the factored pattern** (`SparseLu.refactor(a, patternVersion)`); after a value change the stored pivots may fail the check once (an analog switch reopening) — it falls back to a full factorization, as designed.
   - **Agreement between paths is limited by conditioning**: examples with pivot ratio ρ ≈ 5e-8 (`relayand.txt`) differ by 1.4e-9 with backward errors of 5e-17 on both paths; compare within max(1e-9, 1e-15/ρ).
   - **Mode changes re-stamp only** (no analysis); a document in an agent run keeps its path until the run ends.

1. **The diagonal is not paired after simplify.** `simplifyMatrix` assigns `mapRow` and `mapCol` independently, so the matrix "diagonal" can be shifted. A sparse LU needs a maximum transversal with lookahead first. Without it, fill doubles and the residual reaches 1e-4.
2. **Voltage-source rows have zero diagonals**, so static pivoting is not possible. Use threshold partial pivoting (tol about 1e-3, diagonal preferred).
3. **The non-zero pattern changes between Newton iterations.** `AnalogSwitchElm.doStep` stamps a resistor only in some states, and VCCS/CCCS skip derivative stamps when |dv| < 1e-6. Build the pattern from stamp calls, not from values. Keep the union, and redo the symbolic step when a stamp falls outside it.
4. **A refactor with the old pivot order can blow up** (residual 1e11 after large value changes). Check pivot growth and fall back to a full factor.
5. **Keep the singularity semantics.** The absolute 1e-14 pivot test, gmin, the 1e-12 stabilizers and the 1e8 Ω repair resistors all make small pivots legitimate. Both paths report a `SingularityReport` (column, row, pivot, the unknown through `mapCol`) and dump the assembled values (delivered by PL_SLV).
6. **Memory.** Before PL_SLV `stampCircuit` allocated two dense m × m arrays: 400 MB at m = 5000, 1.6 GB at m = 10 000. A sparse factor alone did not fix that: the store and the reduction became sparse as well (delivered); dense tables now exist only on the dense path (m ≤ 64 in Auto, or forced).
7. **Accelerators are dead ends for the per-step solve:**
   - **WebGPU.** NW.js 0.64.1 = Chromium 101, which has no WebGPU (it arrived in Chrome 113 and later still on Linux). WGSL has no f64, and an async `mapAsync` readback takes 5–15 ms.
   - **Server RPC.** A localhost round trip is 130–334 µs per call, and every Newton iteration would need one.
   - **Workers.** They help only across independent runs.
   - **WASM-KLU** (LGPL-2.1+, about 1.5–3× over JS sparse, an estimate) is the only conditional option.
8. **The engine cannot run outside the page without a split.** 131 of 331 `client/` files import `com.google.gwt.*` (canvas, GWT JSON, widgets, `Window`). `CircuitSimulator` has 1 such import and `CircuitMath` has 0.

## References

- [docs/sparse-solver.spike.md](../../../docs/sparse-solver.spike.md) — numbers, alternatives, sources.
- Sibling skills: `mna-stamping.md` (Pitfall 7, the cubic stamp), `newton-raphson-loop.md`.
- Libraries: KLU (SuiteSparse, LGPL-2.1+), [JKLU](https://github.com/rwl/JKLU) and [CSparseJ](https://github.com/rwl/CSparseJ) (Java, LGPL-2.1+, 2012). The project licence is GPL v2-or-later.
