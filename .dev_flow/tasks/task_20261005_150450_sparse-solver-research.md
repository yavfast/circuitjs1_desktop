# Task: Research — sparse solver for large circuits; WebGPU or a server for acceleration

> **Task ID:** `task_20261005_150450_sparse-solver-research`
> **Created:** 2026-10-05 15:04
> **Last updated:** 2026-10-05 15:50
> **Status:** `done`
> **Contributors:** `main`
> **Autonomy:** `checkpoints`

## Current Work Item

| Field | Value |
|-------|-------|
| **Document** | `spike` — [sparse-solver.spike.md](../../docs/sparse-solver.spike.md) |
| **Pipeline phase** | `research` |
| **Traceable ID** | n/a (spike; target concept C_SIM update; backlog TD_20261005_145712_sparse-solver in PL_SIM) |

## Intent

- **Goal (why):** large circuits analyse and step too slowly with the dense O(m³) LU.
- **Target state:** a solver path that scales to large circuits; a judgement on whether WebGPU or a server would help.
- **Expected result:** the spike answers the three questions with measurements where possible, so a C_SIM concept update can be written (inferred).

## Description

Research spike requested via `/dev-flow research`. Questions, scope and time-box are in the spike file. Does not touch task_E_AGT. — main

## Subtasks

### Subtask: spike investigation
> Author: `main` — Created: 15:04 — Last updated: 15:50 — Status: `done`

**Goal:** answer the three framed questions, persist durable findings as skills.

**Progress:**
- [x] Frame spike (questions, scope, time-box)
- [x] Run two researchers (Q1 profile + sparse LU prototype benchmark; Q2+Q3 platforms) and synthesize — spike `concluded`
- [x] Persist durable findings → new skill `simulator/solver-performance.md` (+ mna-stamping Pitfall 9 pointer); benchmark kit → `.dev_flow/cache/sparse-spike/` (cache entry `sparse-spike`)
- [x] Spotted defects (reset run double analysis; singular retry on the overwritten matrix) → fix round task_20261005_153627_spike-solver-defects
- [x] Hand off: next = C_SIM concept update + interview on the spike's forks (PL_SIM backlog TD_20261005_145712_sparse-solver)

**Activity:**
- 15:50 — spike concluded; spot-checked Chromium 101.0.4951.67 (bundled chromedriver), GWT import counts, the reset-run double `ensureAnalysed` and the in-place `lu_factor` + stabilizer retry in code; no rule harvested (constraints recorded in the skill)
- 15:04 — spike framed; `.dev_flow/profile/` absent → no profile writes; no `output_styles.md` → shipped defaults

## Coordination Notes

## Blocking Issues

{No blockers yet.}

## Relevant Context

- `{s:pin}` Verdict: CPU sparse LU in the engine's own Java (transversal + ordering + Gilbert–Peierls with threshold pivoting + growth-checked refactor; dense below m ≈ 64) — 100–1000× per factorization at m ≥ 500; WebGPU (absent in NW.js 0.64.1/Chromium 101, no f64, 5–15 ms readback) and server offload rejected; WASM-KLU conditional. Example corpus max m = 87.

- [mna-stamping skill](../skills/simulator/mna-stamping.md) Pitfall 7 (cubic stamp), [PL_SIM backlog](../../docs/simulator-engine.plan.md#backlog).
