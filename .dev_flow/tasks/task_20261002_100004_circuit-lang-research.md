# Task: Research — a language for circuit description, simulation and measurement

> **Task ID:** `task_20261002_100004_circuit-lang-research`
> **Created:** 2026-10-02 10:00
> **Last updated:** 2026-10-02 10:40
> **Status:** `done`
> **Contributors:** `main`
> **Autonomy:** `checkpoints`

## Current Work Item

| Field | Value |
|-------|-------|
| **Document** | `spike` — [circuit-script-language.spike.md](../../docs/circuit-script-language.spike.md) |
| **Pipeline phase** | `research` |
| **Traceable ID** | n/a (spike; target concept to be created; related epic E_AGT) |

## Intent

- **Goal (why):** check concepts, select element values and verify stable operation by writing a program instead of drawing and watching scopes.
- **Target state:** a programming language in which a circuit is described and simulated, with diagnostics at chosen points (voltage, current, frequency, waveform shape, other).
- **Expected result:** this spike maps the solution space, the engine/API limits and the hosting options so a concept can be written (inferred).

## Description

Research spike requested via `/dev-flow research`. Questions, scope and time-box are framed in the spike file. Builds on the Agent API of epic E_AGT (task_E_AGT, not touched by this task). — main

## Subtasks

### Subtask: spike investigation
> Author: `main` — Created: 10:00 — Last updated: 10:40 — Status: `done`

**Goal:** answer the three framed questions, persist durable findings as skills.

**Progress:**
- [x] Frame spike (questions, scope, time-box)
- [x] Run three researchers (prior art, engine/API fit, host/syntax) and synthesize — spike `concluded`
- [x] Persist durable findings → `.dev_flow/skills/` (new: automation/circuit-experiment-language, automation/agent-run-behaviour; pitfalls added: simulator/time-step-control #6, io/json-format #12, io/text-format #1)
- [x] Spotted defects filed: audit backlog BL-D01 (time-step re-quantization), BL-D02 (`ujtosc.txt` stamp exception), BL-D03 (DC operating point verify); PL_AGA findings sent to the E_AGT session (see Coordination Notes)
- [x] Hand off: next phase = concept interview (8 decision forks in the spike Conclusion)

**Activity:**
- 10:40 — spike concluded; Q2 spot-checked (27 °C `vt`, unseeded `RandomUtils`, step halving only on Newton failure, `Scrollbar.setValue` fires the 10 µs-capped slider command); no rule harvested (format-probe constraint recorded as io skill pitfall; RULE_ARCH_004 left unchanged)
- 10:36 — concurrent E_AGT session (`circuitjs1-desktop-b5`) staged PL_AGA Phase 9 incl. `io/text-format.md`; my edit there stays unstaged; sent it the list of my paths + PL_AGA findings
- 10:28 — Q2 (engine/API fit + throughput) returned and written as Entry 2
- 10:20 — Q3 (host/syntax) returned and written as Entry 3; spot-checked async-only `run`, registry order + permissive text probe, `{` sniff in ImportOps, JSON without model definitions, PL_MCP at Phase 0 only
- 10:17 — Q1 (prior art) returned and written as Entry 1; spot-checked ngspice no `.step` (§12.11.4.3), no built-in optimizer (ch. 19), AnalogCoder Table 4 (13.9 SPICE vs 21.4 Python) against staged sources
- 10:00 — spike framed; `.dev_flow/profile/` absent → no profile writes; working tree carries uncommitted E_AGT Phase 8 changes — not touched

## Coordination Notes

- 2026-10-02 `circuitjs1-desktop-b5` → `main`: E_AGT fix round for (a)–(d) implemented, uncommitted until its review. When it lands, two skills of this task go stale: `simulator/time-step-control.md` pitfall 6 (code no longer fires the slider command; `setValueWithoutCommand`, user moves only) and `automation/agent-run-behaviour.md` (first sample after the first timestep, samples = steps; quantization fixed for activation and text/JSON import; StopTrigger ends a run with `stop_trigger`, SP_AGA_DEC_05; determinism "from reset; noise sources excepted"). ~~**Pending:** update both from the commit, verified in HEAD; b5 closes BL-D01 with the commit ref.~~ **Done:** fix committed as `722f9f9` and verified in HEAD; both skills and the spike Conclusion updated; b5 closed BL-D01 (working-tree edit in the audit plan).
- 2026-10-02 10:45 `circuitjs1-desktop-b5` (task_E_AGT) → `main`: stages explicit paths, keeps this task's files out of its commits (37e5283 excluded them); files (a) and (c) in the PL_AGA Backlog and fixes them in an E_AGT fix round after PL_MCP Phase 1, citing BL-D01 as the root-cause record; qualifies the C_AGA_03_04 determinism claim for noise sources; checks (d) against the contract. BL-D01 re-pointed to that fix round; this task does not fix it.
- 2026-10-02 `main` → task_E_AGT (sent to session `circuitjs1-desktop-b5`, not written into its task file because that file is staged by its Phase 9): (a) first probe sample of an un-reset run after import/edit reads 0 V (`RunController.java:363-364`); (b) C_AGA_03_04 determinism fails with noise sources (unseeded `RandomUtils`); (c) tab activation re-quantizes a `configure`d time step (root cause BL-D01); (d) `StopTriggerElm` does not end an agent `run`. Theirs to place in the PL_AGA Backlog.

## Blocking Issues

{No blockers yet.}

## Relevant Context

- `{s:pin}` Spike verdict: feasible in layers — experiment layer (initial state, step, run, SPICE-style measures computed in-app, specs, loops) over the Agent API; host outside the app (JS/TS library first, Python optional) → no code-execution path via the always-on MCP server; circuit description separate (drawn circuits v1; builder code exists; net-text with label stubs / SPICE import later). Stability: transient substitutes + seeded MC; temperature/AC/accuracy control deferred (L solver work).
- `{s:pin}` Next: concept interview on the 8 forks of the spike Conclusion (authors, where scripts run, host language, circuit description in v1, loop placement, engine scope, placement in E_AGT vs new epic, declarative bench = code execution?).
- Builds on [agent-api.concept.md](../../docs/agent-api.concept.md) (runs, probes, ProbeStats) and [mcp-agent-bridge.spike.md](../../docs/mcp-agent-bridge.spike.md).
