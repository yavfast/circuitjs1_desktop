# Linear System Solver  {#C_SLV}

> **Code:** C_SLV
> **Status:** draft
> **Created:** 2026-10-05
> **Updated:** 2026-10-05
> **Author:** main
> **Owner:** simulator engine (the maintainer of [C_SIM](./simulator-engine.concept.md))
> **Complexity:** high
> **Criticality:** critical — every simulation step solves through it, and a wrong solve is silent
>
> **Depends on:** [C_SIM](./simulator-engine.concept.md), [C_MDS](./math-dsp.concept.md), [C_DOC](./document-model.concept.md)
> **Used by:** [C_SIM](./simulator-engine.concept.md), [C_APC](./app-controller.concept.md), [C_AGA](./agent-api.concept.md)
> **Spike:** [sparse-solver.spike.md](./sparse-solver.spike.md)
> **Specification:** [SP_SLV](./linear-solver.sp.md)
> **Plan:** [linear-solver.plan.md](./linear-solver.plan.md)
>
> This concept covers how the simulator stores and solves its MNA system `A·x = b` so that the cost grows with the non-zeros, not with the cube of the matrix size. A sparse path handles large circuits. The current dense path stays, unchanged, for small ones, and a solver mode chooses between them: a session setting in the UI, overridable per document through the automation APIs. Read it before you change matrix assembly, row reduction, factorization or the singular-matrix handling of [C_SIM](./simulator-engine.concept.md). It defines the system store, the two solve paths, the symbolic and numeric phases, the pattern-growth rule and the solver mode.

## Contents

- [1. Philosophy](#C_SLV_01) — why the solve must scale with non-zeros, what stays bit-identical, what is out of bounds
- [2. Domain Model](#C_SLV_02) — the system store, pattern, symbolic analysis, factorization, solver mode and path, and how they flow through analysis and Newton iterations
- [3. Mechanisms](#C_SLV_03) — assembly, reduction, path choice, symbolic and numeric phases, refactorization, pattern growth, singularity, mode changes
- [4. Integration Points](#C_SLV_04) — what it takes from the engine and what it offers to the engine, the UI and the automation APIs
- [5. Design Decisions](#C_SLV_DEC) — implementation source, depth of the change, path selection, singularity semantics, mode scope

## 1. Philosophy  {#C_SLV_01}

### 1.1. Core Principle  {#C_SLV_01_01}

The engine factors the system once per stamp of a linear circuit and once per Newton iteration of a nonlinear one ([C_SIM_03_01](./simulator-engine.concept.md#C_SIM_03_01)). Today that system is held as a full `m × m` table and factored with dense elimination, so the cost grows as m³ and the memory as m². The [spike](./sparse-solver.spike.md) measured it:

- **Analysis.** A 2000-node linear circuit took 22 s, about 99 % of it in factorization.
- **Nonlinear stepping.** A 1000-node diode ladder took 5.9 s per timestep.
- **Memory.** Two m × m tables use 400 MB at m = 5000.

Circuit matrices are very sparse: a node touches only its few neighbours. A solver that stores and eliminates only the non-zeros was 100–1000× faster at m ≥ 500 in the spike, with the same accuracy.

The engine therefore solves through a **linear system solver** with two paths:

- **Sparse path.** Large systems go through a store and a factorization whose cost follows the non-zeros, including the fill-in that elimination creates.
- **Dense path.** Small systems keep today's algorithm exactly, so the example circuits and every small circuit a user builds behave bit-for-bit as before. The example corpus never exceeds m = 87, with a median of 7.

### 1.2. Design Constraints  {#C_SLV_01_02}

- **Double precision, in the page, single-threaded.** The spike rejected the alternatives:
  - GPU compute: the desktop runtime has none, its shading languages have no 64-bit floats, and every step would wait milliseconds for a readback.
  - A local or remote server: each step would pay 130–334 µs per round trip.
  - Workers: there is no parallelism inside one timestep.
  - A native helper: it needs per-OS binaries.

  Compiling a native sparse library to the browser's bytecode stays a conditional fallback (see [§4.1](#C_SLV_04_01)).
- **Same behaviour below the threshold.** In automatic mode, a system below the size threshold takes the unchanged dense path. Its results match today's bit-for-bit.
- **Same singular semantics on both paths** ([DEC_04](#C_SLV_DEC_04)). On both paths the solver declares a system singular by the same absolute pivot test and reports the same diagnostics. The engine's stabilize-then-escalate policy ([C_SIM_03_02](./simulator-engine.concept.md#C_SIM_03_02)) therefore acts the same on both sides of the threshold. One asymmetry remains: the sparse path also recognizes a structurally singular system, which the dense path can let through when round-off leaves a pivot above the threshold, so the sparse path is at least as strict.
- **Memory follows the non-zeros** ([DEC_02](#C_SLV_DEC_02)). On the sparse path no structure is m × m: not the assembled system, not the Newton snapshot, and not the row reduction.
- **Deterministic per mode.** The same circuit, mode and step sequence give the same numbers. The two paths may differ at round-off level. That is visible in chaotic circuits, and it is expected, not a defect.
- **Element contract unchanged.** Elements keep stamping through the engine's primitives ([C_SIM_03_01](./simulator-engine.concept.md#C_SIM_03_01)). No element learns which path is in use.
- **Rollback.** Selecting the Dense mode restores today's solver for every circuit without a code change. Removing the sparse path leaves the dense path as the only path. The mode is never written into circuit files ([DEC_05](#C_SLV_DEC_05)), so no file depends on it.
- **Reuse check.**
  - The dense kernel belongs to [C_MDS](./math-dsp.concept.md), and this concept keeps it as the dense path.
  - Assembly and row reduction are mechanisms of [C_SIM](./simulator-engine.concept.md). This concept takes over their storage and leaves their rules unchanged.
  - No other concept or adoption document covers sparse solving. External open-source ports exist ([spike](./sparse-solver.spike.md)), but the solver is written in-house ([DEC_01](#C_SLV_DEC_01)).
- **Maintenance.** The solver is maintained by the simulator engine owner, and the [spike](./sparse-solver.spike.md)'s benchmark kit is its performance baseline.

**This concept IS:** the storage of the MNA system, its row reduction, the choice between the dense and sparse paths, the sparse symbolic and numeric factorization, refactorization, the solve, singular-system detection with its diagnostics, and the solver mode with its scopes.

**This concept IS NOT:**
- the stamping rules of elements, the Newton loop, time-step control or the singular-matrix escalation policy — these stay in [C_SIM](./simulator-engine.concept.md);
- the small matrices that elements invert internally (transformer and motor models);
- parallel or batch execution of several runs;
- GPU, server or native acceleration.

## 2. Domain Model  {#C_SLV_02}

### 2.1. Key Entities  {#C_SLV_02_01}

| Entity | What it is | Lifetime |
|---|---|---|
| **System store** | The assembled MNA system: the right-hand side, and the matrix entries held as (row, column) slots that the stamping primitives add into. Sparse on both paths, because the path depends on the reduced size, known only after reduction; the dense path copies the reduced system into its full tables. | One stamp (the engine re-stamps after an analysis, after each time-step change and after a recovery re-stamp) |
| **Pattern** | The set of positions that may hold a non-zero: every position any stamp has touched, including those that happen to be zero at the moment. | Grows within an engine analysis and is carried across its stamps while the row reduction is unchanged; reset by a new analysis |
| **Snapshot** | The values after the constant part was stamped and the rows were reduced. A Newton iteration restores them before elements add their linearized part. | One stamp |
| **Row reduction** | The pre-elimination of rows that determine a single unknown, which turns that unknown into a constant ([C_SIM_03_01](./simulator-engine.concept.md#C_SIM_03_01)). It yields the reduced system the solver factors. | One stamp |
| **Symbolic analysis** | Sparse path only, and independent of the values: a pairing of rows with columns (a maximum transversal) that puts a non-zero on every diagonal, a fill-reducing elimination order, and the resulting structure of the factors. | Carried across stamps of one engine analysis until the pattern grows or the reduction changes |
| **Numeric factorization** | The lower and upper factors and the pivot order actually used. | Replaced by every factorization or refactorization |
| **Solve path** | Dense or sparse. It is chosen at each stamp from the solver mode and the reduced size. | One stamp |
| **Solver mode** | `Auto`, `Dense` or `Sparse`. There is a session default chosen in the UI, and a per-document override set through the automation APIs. | Session default: persisted with the application preferences. Override: lives as long as the open document, never saved. |
| **Singularity report** | The failed elimination step: column, row, pivot magnitude, and the circuit variable it belongs to (a node voltage or a voltage-source current). | One failed factorization |

```
 solver mode (session default ─┐
            per-doc override ──┴→ effective mode) ──┐
                                                     ▼
 stamps ──► system store ──► row reduction ──► reduced size ──► solve path
             │  pattern ◄──── grows on new positions             │
             └─ snapshot                                         ▼
                                           dense: today's elimination (C_MDS)
                                           sparse: symbolic ─► numeric ─► solve
```

### 2.2. Data Flows  {#C_SLV_02_02}

```
stamp (after an engine analysis, a time-step change, a recovery re-stamp, or a mode change)
  ├─ store reset (a new engine analysis also drops the carried pattern and symbolic analysis)
  ├─ engine stamps the constant part → store (records every position touched)
  ├─ row reduction → reduced system; snapshot taken
  ├─ path := choose(effective mode, reduced size)
  ├─ sparse: pattern := this stamp's positions ∪ positions carried from earlier stamps of the
  │          same engine analysis (same reduction); symbolic analysis reused or rebuilt
  └─ linear circuit: factor now (once per stamp)

timestep → Newton iteration (nonlinear circuit)
  ├─ restore snapshot (cost follows the non-zeros)
  ├─ elements add their linearized part → store
  │    └─ a position outside the pattern → pattern grows, symbolic analysis marked stale
  ├─ factor:
  │    dense  → dense elimination, as today
  │    sparse → symbolic stale? redo it, then full factorization
  │             else refactorize with the kept order; pivot check fails → full factorization
  ├─ singular → singularity report → engine's escalation (C_SIM_03_02)
  └─ solve → unknowns → engine applies node voltages and source currents
```

## 3. Mechanisms  {#C_SLV_03}

### 3.1. Core Algorithm  {#C_SLV_03_01}

**Assembly.**
- The stamping primitives keep their meaning ([C_SIM_03_01](./simulator-engine.concept.md#C_SIM_03_01)). An entry is added to the slot of its (row, column); the first stamp at a position creates the slot and adds the position to the pattern.
- On the sparse path a slot is found through a per-stamp map, so a stamp call costs a lookup, not a scan. After the constant part is stamped, the slots are frozen into a compressed column form. Later stamps at known positions go straight to their slot.

**Row reduction.**
- It keeps today's rules and results: same reduced unknowns, same constants, same reduced system. The only change is that it works over the non-zeros, so its cost follows them instead of m².
- The snapshot is a copy of the reduced values, and restoring it costs a copy of the non-zeros.

**Path choice.** The effective mode is the document's override when one is set, otherwise the session default.
- `Auto` takes the dense path below a size threshold of about 64 reduced unknowns, a constant of the solver. Above it, `Auto` takes the sparse path.
- `Dense` and `Sparse` force their path regardless of size.

The choice is made at each stamp. It is observable: the document diagnostics report the active path, the reduced size and the non-zeros, so tests and agents can see which path solved a circuit.

**Symbolic analysis** (sparse path).
1. **Transversal.** A maximum transversal with lookahead pairs every row with a column that holds a non-zero. Two things require it: row reduction can leave the reduced rows misaligned with their unknowns, and voltage-source rows have a structurally zero diagonal. Without the pairing, the spike's factors doubled in fill and lost five orders of accuracy.
2. **Ordering.** A minimum-degree order on the symmetrized pattern of the paired system limits fill-in.

The more expensive approximate-minimum-degree order is a replacement for the same step when profiling shows ordering cost dominating above m ≈ 5000. Block-triangular decomposition is not used: MNA systems are mostly one strongly connected block, and the spike reached its gains without it.

**Numeric factorization** (sparse path). A left-looking elimination, one column at a time.
- **Pivoting.** It uses threshold partial pivoting with a relative tolerance of about 10⁻³ and prefers the paired diagonal. Pivoting is needed because zero diagonals rule out a static pivot order.
- **Singularity.** The singularity test is the dense path's absolute test: a column whose best pivot magnitude is below 10⁻¹⁴ fails ([DEC_04](#C_SLV_DEC_04)).

**Refactorization** (sparse path, nonlinear circuits). It reuses the structure of the factors (kept even where a value is momentarily zero), the order and the pivot sequence of the last full factorization, and recomputes only the values. This step was 2–5× cheaper than a full factorization in the spike.
- **Pivot check.** Each reused pivot must still pass the same threshold rule a full factorization applies to its column: it is large enough relative to the other candidates of that column, and above the singularity test. When any pivot fails, the solver discards the refactorization and runs a full factorization with fresh pivoting.
- **Why the check is mandatory.** In the spike, a refactorization without it returned a residual of 10¹¹ after large value changes.

**Solve.** Forward and back substitution over the factors. Its cost follows the non-zeros of the factors.

**Pattern growth.** The pattern of one engine analysis is not fixed across Newton iterations. Some elements stamp a position only in some states, for example a switch that adds a conductance when closed. Others skip a derivative term when it is negligibly small.
- The pattern is therefore the union of every position ever stamped in the engine analysis, recorded from the stamp calls, not from the values, and carried across the engine's re-stamps (time-step changes) while the row reduction is unchanged.
- A position outside the pattern grows it and marks the symbolic analysis stale. The next factorization redoes the symbolic analysis before it factors.
- A position, once added, stays for the rest of the engine analysis, so the pattern stabilizes after the first states have been visited.

**Mode changes.** A change of the session default, or of a document's override, requests a new stamp of the affected documents: the stamp is where the path is chosen. The new path applies from that stamp on. The re-stamp is only a stamp: it does not re-validate the circuit or reset the time step. No system is ever converted between paths mid-stamp.

### 3.2. Edge Cases  {#C_SLV_03_02}

- **Empty or one-unknown system.** Row reduction can resolve every unknown. Both paths handle sizes 0 and 1 as the dense path does today.
- **Singular system.** The solver returns a singularity report. The engine's policy decides what follows, on both paths alike: it enables the stabilizers and re-stamps, and if the system is still singular it escalates the recovery or stops ([C_SIM_03_02](./simulator-engine.concept.md#C_SIM_03_02)). A failed factorization never leaves the store unusable. The sparse factors live apart from the store and the snapshot, so a re-stamp or a retry always starts from the assembled values.
- **Ill-conditioned but valid systems.** The 10⁸ Ω repair resistors, gmin, the 10⁻¹² stabilizers and the stamp clamp produce legitimately small pivots. Keeping the absolute test ([DEC_04](#C_SLV_DEC_04)) keeps today's verdict on them.
- **High fill.** A densely coupled topology produces large factors on the sparse path. The result stays correct; the cost approaches the dense one. In automatic mode such a circuit above the threshold still takes the sparse path, because the spike found no circuit for which dense was faster above m ≈ 40.
- **Very large systems** (m > 10 000). Memory follows the non-zeros. Ordering cost is the first to grow (80 ms at m ≈ 5000 in the spike). The approximate-minimum-degree replacement covers it.
- **Round-off differences between paths.** A circuit near the threshold, or one forced into the other path, may diverge at round-off level. Chaotic circuits show it on the screen. Each mode is deterministic on its own.
- **Diagnostics for small systems.** The dense path can dump the whole matrix for small systems (today: up to 12 unknowns). The sparse path offers the same dump through its store for systems of the same size.

## 4. Integration Points  {#C_SLV_04}

### 4.1. Dependencies  {#C_SLV_04_01}

- [C_SIM](./simulator-engine.concept.md) defines the stamping primitives, the row-reduction rules, the Newton loop that calls factor and solve, and the singular-matrix escalation that consumes the singularity report. This concept takes over the storage behind them.
- [C_MDS](./math-dsp.concept.md) — the dense elimination and its failure diagnostics are the dense path, unchanged.
- [C_DOC](./document-model.concept.md) — the per-document mode override is document-scoped state, alongside the document's own simulator; the session default is session-scoped, alongside the other application preferences. This is the document-versus-session split of RULE_ARCH_006: an override never leaks across tabs, and the default never belongs to one document.
- **Conditional fallback, not a dependency.** If profiling after delivery shows the sparse factorization above 60 % of step time at m ≥ 1000, a native sparse library compiled to the browser's bytecode may replace the numeric phase behind the same contracts ([spike](./sparse-solver.spike.md) Conclusion).

### 4.2. API Surface  {#C_SLV_04_02}

**To the engine** ([C_SIM](./simulator-engine.concept.md)), as abstract operations:
- begin a stamp;
- add an entry at (row, column);
- add to the right-hand side;
- reduce rows;
- take and restore the snapshot;
- factor, which returns success or a singularity report;
- solve;
- report path, size and non-zeros.

**To the UI** ([C_APC](./app-controller.concept.md)): the session default of the solver mode (`Auto`, `Dense`, `Sparse`; `Auto` when unset). It is shown with the other simulation options and persisted with the application preferences, not in circuit files.

**To the Agent API** ([C_AGA](./agent-api.concept.md); the MCP tools pass it through):
- set or clear a document's solver-mode override; it is not saved and lasts as long as the open document;
- read the effective mode, the active path, the reduced size and the non-zeros in the document diagnostics.

Consumers: agents and the live tests, which compare the two paths on one circuit.

## 5. Design Decisions  {#C_SLV_DEC}

### DEC_01 — Source of the sparse solver  {#C_SLV_DEC_01}

> **Status:** resolved
> **Date:** 2026-10-05

**Question:** Write the sparse LU in-house, or adopt an existing open-source port?

**Options considered:**
| Option | Consequence |
|--------|-------------|
| A — In-house, following the spike prototype (transversal, minimum degree, left-looking factorization with threshold pivoting, growth-checked refactorization) | About 600–900 lines owned by the project; no external code or licence; the approximate-minimum-degree order is ported later only if ordering cost demands it |
| B — Adopt an existing open-source port of a reference circuit-matrix solver and its ordering modules | A proven design including block-triangular form, but about 7.5k lines, unmaintained since 2012, needing small edits to compile for the browser; the integration work is the same as A (candidates are named in the spike) |
| C — Adopt a smaller general sparse-matrix port and add refactorization and transversal | Less algorithmic code of our own, but a foreign data structure and style in the engine |

**Decision:** A — in-house.
**Rationale:** The algorithm is small and was already validated by the prototype on the captured matrices. Owning it keeps the engine free of a large unmaintained dependency, and the integration cost dominates either way.
**Rejected because:** B — size and maintenance burden for features (block-triangular form) the measurements did not need; C — mixed style and data model for a modest saving.

### DEC_02 — Depth of the change  {#C_SLV_DEC_02}

> **Status:** resolved
> **Date:** 2026-10-05

**Question:** Make only the factorization sparse, or also the assembled store, the snapshot and the row reduction?

**Options considered:**
| Option | Consequence |
|--------|-------------|
| A — Fully sparse: store, snapshot, row reduction, factorization, solve | Memory and analysis cost follow the non-zeros; the larger change to the engine |
| B — Factorization only: dense store and reduction, converted before factoring | Fewer changes, but m² memory (1.6 GB at m = 10 000) and an O(m²) reduction remain, which become the next bottleneck after the factorization speed-up |
| C — Ship a zero-skipping dense elimination first, the sparse path later under a separate concept | A quick 100–500× win with a tiny change, but a second solver to keep and a full design deferred |

**Decision:** A — fully sparse.
**Rationale:** After a sparse factorization, the dense store and the quadratic reduction dominate analysis (about 0.2 s of 0.25 s at m = 2000) and cap circuit size by memory. Only the full change removes both.
**Rejected because:** B — leaves the next bottleneck and the memory ceiling in place; C — adds an interim solver with no lasting role, because the unchanged dense path already covers small systems.

### DEC_03 — Choosing the path  {#C_SLV_DEC_03}

> **Status:** resolved
> **Date:** 2026-10-05

**Question:** How is the dense or sparse path chosen?

**Options considered:**
| Option | Consequence |
|--------|-------------|
| A — Automatic by size only, with a hidden switch for tests | No user-facing surface; users cannot force a path |
| B — Automatic by size, plus a user-visible mode (`Auto` / `Dense` / `Sparse`) | Users can force today's solver (rollback, comparison) or the sparse one; adds a setting, its persistence, translation and documentation |
| C — Always sparse | One path, but every circuit changes at round-off level and small circuits gain nothing |

**Decision:** B — automatic by size, plus a user-visible mode.
**Rationale:** The developer wants the choice in the user's hands: forcing Dense is the rollback for anyone who sees a difference, and forcing Sparse lets anyone compare.
**Rejected because:** A — the developer chose user control over a hidden switch; C — changes every existing circuit for no gain.

### DEC_04 — Singularity semantics on the sparse path  {#C_SLV_DEC_04}

> **Status:** resolved
> **Date:** 2026-10-05

**Question:** Does the sparse path declare a system singular by the dense path's absolute pivot test, or by a scaled, relative test?

**Options considered:**
| Option | Consequence |
|--------|-------------|
| A — Same absolute test (10⁻¹⁴) and the same diagnostics | A circuit gets the same stabilizer and singular-matrix behaviour on both sides of the threshold |
| B — Row scaling and a relative test | More robust on ill-conditioned systems, but large circuits would decide differently from small ones when to enable stabilizers |
| C — A now, B as an open decision triggered by evidence | Same as A, with a recorded path to B |

**Decision:** A — same absolute test and diagnostics.
**Rationale:** Uniform behaviour across the threshold keeps the engine's stabilize-then-escalate policy predictable. The spike found no accuracy loss with the absolute test on its matrices.
**Rejected because:** B — splits behaviour by circuit size; C — no evidence calls for keeping B open, and it can return as a new decision if one appears.

### DEC_05 — Scope of the solver mode  {#C_SLV_DEC_05}

> **Status:** resolved
> **Date:** 2026-10-05

**Question:** Where does the selected solver mode live?

**Options considered:**
| Option | Consequence |
|--------|-------------|
| A — A session/application preference only | One setting for all tabs; no file-format change |
| B — In the circuit document, saved in the text and JSON formats | The circuit carries its solver; two formats, roundtrip tests and compatibility with the original simulator change |
| C — The session preference, plus a per-document override through the automation APIs that is never saved | No file-format change; agents and tests can compare paths on one open document without touching the user's setting |

**Decision:** C — session preference plus an unsaved per-document override through the APIs.
**Rationale:** The mode does not change the physics of a circuit, so it does not belong in the file. Agents and tests still need to force a path on one document without changing the user's global choice.
**Rejected because:** A — no way for an agent or test to compare paths without changing the user's setting; B — file-format and compatibility cost for a setting that is not part of the circuit.

## Changelog

| Date | Change |
|------|--------|
| 2026-10-05 | Initial version from the sparse-solver spike; DEC_01–DEC_05 resolved in interview. |
| 2026-10-05 | Review round 1: store sparse on both paths; per-stamp lifecycle with pattern and symbolic analysis carried within an engine analysis; per-column pivot check for refactorization; sparse singular test at least as strict (structural singularity); a mode change re-stamps only. |
| 2026-10-05 | Spec pass: the per-document override is set through the Agent API only (no scripting-interface method, SP_SLV_DEC_02); a mode change requests a re-stamp. |
