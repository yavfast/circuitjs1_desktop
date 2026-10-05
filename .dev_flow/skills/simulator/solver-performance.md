---
skill: solver-performance
domain: simulator
topics: [solver, performance, lu, sparse, profiling, large-circuit, webgpu, wasm, benchmark]
source: research (docs/sparse-solver.spike.md)
updated: 2026-10-05
---

# Solver performance (dense LU today, sparse LU fit, accelerators)

## Context

Measured in the 2026-10-05 spike [docs/sparse-solver.spike.md](../../../docs/sparse-solver.spike.md). Use this skill before you profile the simulator, change `CircuitMath.lu_factor`/`lu_solve`, `stampCircuit` or `simplifyMatrix`, or propose GPU, WASM or server acceleration. The benchmark kit is in [.dev_flow/cache/sparse-spike/](../../cache/sparse-spike/README.md).

## Key facts (measured)

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
- **Choosing an approach.** CPU sparse LU in the engine's own Java is the lever. Keep a dense path below a tunable threshold (start at m ≈ 64): small circuits then stay bit-identical, because a different pivot order changes round-off and chaotic circuits show it.

## Pitfalls

1. **The diagonal is not paired after simplify.** `simplifyMatrix` assigns `mapRow` and `mapCol` independently, so the matrix "diagonal" can be shifted. A sparse LU needs a maximum transversal with lookahead first. Without it, fill doubles and the residual reaches 1e-4.
2. **Voltage-source rows have zero diagonals**, so static pivoting is not possible. Use threshold partial pivoting (tol about 1e-3, diagonal preferred).
3. **The non-zero pattern changes between Newton iterations.** `AnalogSwitchElm.doStep` stamps a resistor only in some states, and VCCS/CCCS skip derivative stamps when |dv| < 1e-6. Build the pattern from stamp calls, not from values. Keep the union, and redo the symbolic step when a stamp falls outside it.
4. **A refactor with the old pivot order can blow up** (residual 1e11 after large value changes). Check pivot growth and fall back to a full factor.
5. **Keep the singularity semantics.** The absolute 1e-14 pivot test, gmin, the 1e-12 stabilizers and the 1e8 Ω repair resistors all make small pivots legitimate. The `lastLuFail*`, `dumpCircuitMatrix` and `describeMatrixVariable` diagnostics need sparse equivalents.
6. **Memory.** `stampCircuit` allocates two dense m × m arrays: 400 MB at m = 5000, 1.6 GB at m = 10 000. A sparse factor alone does not fix that; the stamping storage and `simplifyMatrix` must become sparse as well.
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
