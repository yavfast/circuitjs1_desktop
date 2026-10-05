# Sparse-solver spike artifacts (2026-10-05)

Throwaway benchmark kit of [docs/sparse-solver.spike.md](../../../docs/sparse-solver.spike.md), kept as the baseline for the sparse-solver implementation (PL_SIM backlog TD_20261005_145712_sparse-solver). Not production code.

- `browser_bench.mjs` — drives `target/site` in headless Chromium over CDP (`CHROMIUM=<path>`, `SITE=<dir>` override); wraps the GWT-emitted `lu_factor`/`lu_solve`/`stampCircuit`/`simplifyMatrix`; `PROFILE=1` CPU profiles, `CAPTURE=1` writes post-simplify matrices, `BG=1` runs on a background document (the canvas draw dominates on the visible tab), `CORPUS=1` sweeps the example corpus for m. Cases `kind:N[:steps]`, kinds in `circuits.mjs` (grid, rladder, cladder, dladder, dcladder, dgrid).
- `circuits.mjs` — text-format generators of the synthetic circuits.
- `dense.mjs` — faithful JS port of `CircuitMath.lu_factor`/`lu_solve` + a row-oriented variant; `dense_layout.mjs` — memory-layout test.
- `sparse.mjs` — prototype: left-looking Gilbert–Peierls LU, threshold partial pivoting (tol 1e-3, diagonal preferred), minimum degree on A+Aᵀ, maximum transversal with lookahead, refactor with pivot-growth check; `sparse_plain.mjs`/`plain_cmp.mjs` — the same on plain arrays (GWT-like).
- `matgen.mjs`, `bench.mjs` (`MATCH=1`, `NATURAL=1`, `DENSE_MAX`), `dbg_refactor.mjs` — synthetic MNA matrices, the benchmark, the refactor-fallback test. `node bench.mjs captured` uses `matrices/` (unpack `matrices.tar.gz` here first).
- `out/` — results: `results_*.json` (browser), `bench_*.json`/`.txt` (Node), `corpus_sizes.json` (m after simplify for all 342 examples).
- `q2q3_notes.md` — condensed facts + URLs of the WebGPU/WASM/server research.
