<!-- Instantiated from the dev-flow todo_index template; relative links below resolve from this directory. -->

# Todos Register

This directory holds **deferred work captured by `/dev-flow todo`**: ideas the project might do later, each filed with the documentation it touches, a feasibility verdict, and a return trigger. A todo lives here only when **no owning plan exists** for the area. When a plan exists, the item goes in that plan's `## Backlog` section instead.

A todo is *deferred*, not committed work. Execution happens later via `/dev-flow do <description>`, which **re-runs a full analysis** (the entry below is a head-start, not a trusted final verdict, because context drifts) and marks the entry `promoted`. The `audit` phase grooms this register.

## Conventions

- **ID:** `TD_<YYYYMMDD_HHMMSS>_<slug>`. Immutable once assigned.
- **Status:** `candidate` → `promoted` / `dropped`; `queued` / `contested` → `promoted` / `dropped` only explicitly, with a reason.
- **Return trigger:** every entry names a date, event, task completion, file-change, or revisit cadence. No triggerless "later".
- **Heavy notes** go to a sibling `<ID>.md`; keep the index entry compact.

## Register

| ID | Description | Scope | Phase | Trigger | Status |
|----|-------------|-------|-------|---------|--------|
| [TD_20261005_145712_forced-run](#td_20261005_145712_forced-run--run-for-a-set-time) | User command: run the simulation as fast as possible for a set span (e.g. 100 ms) to skip start-up transients | Standard | concept addendum → spec → implement | after `task_E_AGT` closes, or on developer request | candidate |
| [TD_20261005_155200_cappar-retry-limit](#td_20261005_155200_cappar-retry-limit--cappartxt-warns-at-analysis) | **Verify first:** example `cappar.txt` warns "Failed to analyze circuit (retry limit)" and needs > 1.5 s wall clock for 1 ms | Trivial–Standard | fix | next validation / FindPathInfo change, or the next example-corpus sweep | candidate |

Related, filed elsewhere: **TD_20261005_145712_sparse-solver** (sparse LU for large circuits) sits in the [PL_SIM backlog](../../docs/simulator-engine.plan.md#backlog), because the simulator plan owns the solver.

## Entries

### TD_20261005_145712_forced-run — Run for a set time

> **Status:** candidate — **Created:** 2026-10-05

- **Idea:** A user-facing "Run for…" command. The user enters a span (e.g. `100 ms`), and the simulator advances that much simulated time as fast as it can, without wall-clock frame pacing or a repaint every frame. Then it stops or goes back to normal free-running, so the user can study the circuit in its settled state instead of watching start-up transients.
- **Relevant docs:** [C_AGA §3.4 Run](../../docs/agent-api.concept.md) (bounded run, run-until-settled), [SP_AGA_02_10 `run`](../../docs/agent-api.sp.md) and [JS_API `run`](../../docs/JS_API.md), [C_APC App Controller](../../docs/app-controller.concept.md) (menus, Run/Stop), [C_SIM](../../docs/simulator-engine.concept.md), [time-step-control skill](../skills/simulator/time-step-control.md).
- **Feasibility (at capture):** feasible. The engine work already exists: `agent/RunController` runs a bounded span in 20 ms slices that yield to the UI, can be cancelled, and has a budget and a settle mode. It uses the timestep loop that free-running frames also use (`CircuitSimulator.java` ~2006). Free-running is paced by wall-clock time (`runCircuit` / `FramePacing`, speed bar `160 × iterCount`). Today the user can only reach a bounded run through the agent API (`CircuitJS1Agent.callAsync("run", …)`). No menu, toolbar or `$wnd.CircuitJS1` command does it. The work is mostly a UI front-end over `RunController`'s span mode, plus deciding how it interacts with the document busy state.
- **Scope / suggested phase:** Standard. It is a new user-visible command that reuses existing contracts. Route: a short concept addendum (C_APC, citing C_AGA §3.4) → spec → implement → RULE_TEST_002 devmode check (analog, digital, subcircuit example) + a `tests/live` check.
- **Forks for pickup:** (1) where it lives: Simulate menu item + dialog, a Controls-dialog field, a hotkey, and/or a `$wnd.CircuitJS1.runFor(span)` script command; (2) "advance by Δt" vs "run to t = T"; also offer "run until settled" (it exists in the agent API); (3) what happens at the end: pause, or resume free-running at the speed-bar rate; (4) what is drawn during the run: a progress readout (t / target, Stop to cancel) and a repaint per slice or only at the end; scopes record every step anyway, so they show the last window when the run ends; (5) whether user edits during the run cancel it, as they do for agent runs (`BusyOwner`).
- **Context snapshot:** the developer's use case is skipping roughly 100 ms of start-up transients to study steady-state behaviour. Key files: `client/agent/RunController.java`, `client/CircuitSimulator.java` (`runCircuit`, `FramePacing`, shared step loop), `client/CirSim.java` (`stepSimulation`, JS bridge), the menu wiring in `MenuManager`.
- **Return trigger:** after `task_E_AGT` closes (its owed manual devmode checks cover free-run and agent runs on the same stepping code, and changing that code now would invalidate them), or earlier on the developer's request.

### TD_20261005_155200_cappar-retry-limit — cappar.txt warns at analysis

> **Status:** candidate — **Created:** 2026-10-05 — found by the corpus sweep of task_20261005_153627_spike-solver-defects (agent-initiated)

- **Observation:** a `reset: true` run of the bundled example `cappar.txt` reports `Solver warning: Failed to analyze circuit (retry limit)` (`CircuitSimulator.preStampCircuit`, the validation retry loop, `i == 10` → `singularStabilizersActive` under recovery) and spends its 1.5 s budget before 1 ms of simulated time. Not caused by the fix (the warning comes from validation, which the fix does not touch); not compared against an older build.
- **Relevant docs:** [C_SIM](../../docs/simulator-engine.concept.md), [SP_SIM_02](../../docs/simulator-engine.sp.md) Errors (WIRE_LOOP / validation).
- **Feasibility (at capture):** unknown — first decide whether a parallel-capacitor example should hit the retry limit (likely a capacitor/voltage-source loop judged by FindPathInfo) or whether validation is too strict.
- **Scope / suggested phase:** Trivial–Standard — `fix` (verify first: open `cappar.txt` in devmode, read the log).
- **Context snapshot:** sweep script `/tmp` only (not kept); reproduce with `CircuitJS1Agent.callAsync('run', '{"span":"1 ms","reset":true,"budgetMs":1500}', console.log)` after loading the example.
- **Return trigger:** the next change to validation / FindPathInfo, or the next example-corpus sweep.

---

*Created and groomed by `/dev-flow todo` and `/dev-flow audit`.*
