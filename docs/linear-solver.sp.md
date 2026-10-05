# Linear System Solver — Specification  {#SP_SLV}

> **Code:** SP_SLV
> **Status:** active
> **Created:** 2026-10-05
> **Updated:** 2026-10-05
>
> **Concept:** [C_SLV](./linear-solver.concept.md)
> **Depends on:** [SP_SIM](./simulator-engine.sp.md), [SP_MDS](./math-dsp.sp.md), [SP_AGA](./agent-api.sp.md), [SP_DOC](./document-model.sp.md)
> **Used by:** [SP_SIM](./simulator-engine.sp.md), [SP_AGA](./agent-api.sp.md), [SP_MCP](./mcp-server.sp.md)
> **Plan:** [linear-solver.plan.md](./linear-solver.plan.md)
>
> This specification defines the storage and solution of the simulator's MNA system: the system store that the stamping primitives write into, the row reduction over it, the dense and sparse solve paths and the choice between them, the sparse symbolic and numeric factorization, refactorization, pattern growth, the singularity report, and the solver mode with its scopes. It also defines the amendments to the Agent API (`simControl` action `solver`, the `solver` block of Diagnostics), to the MCP tool `circuit_sim` and to the Other Options dialog. Read it before implementing PL_SLV or before changing matrix assembly, `simplifyMatrix`, factorization or the singular-matrix diagnostics.

## Contents

- [01. Data Structures](#SP_SLV_01) — system store, reduced system, pattern, snapshot, symbolic analysis, factorization, solver mode, solver info, singularity report, the state carried between stamps
- [02. Contracts](#SP_SLV_02) — analysis lifecycle, assembly, reduction, path choice, factor, solve, mode scopes, Agent API, MCP and UI amendments
- [03. Validation Rules](#SP_SLV_03) — mode values, index ranges, value sanitizing, threshold
- [04. State Transitions](#SP_SLV_04) — solver state per stamp and the mode-change lifecycle
- [05. Verification Criteria](#SP_SLV_05) — functional expectations, invariants, integration scenarios, edge cases, performance targets
- [06. Reversibility](#SP_SLV_06) — rollback through the Dense mode and code removal
- [07. Design Decisions](#SP_SLV_DEC) — the Agent API surface of the override, and the decisions taken without interview

Notation: `n` is the size of the full system (node unknowns + voltage-source currents, ground excluded), `m` the size of the reduced system, `nnz` the number of pattern positions of the reduced system, `nodeCount` = `nodeList.size() − 1` (node unknowns). A **stamp** is one run of the engine's `stampCircuit` (after an analysis, after each time-step change, after a recovery re-stamp); an **engine analysis** is one `analyzeCircuit` (counted by `analysisCount`) and holds any number of stamps. Rows and columns of the full system are numbered `0..n-1`, as today's `circuitMatrix` indices (the stamping primitives' node number minus one). "Today" means the code at commit `a6bec19` plus the solver-defects fix of 2026-10-05.

## 01. Data Structures  {#SP_SLV_01}

> Implements: [C_SLV_02_01](./linear-solver.concept.md#C_SLV_02_01)

### 01_01. SolverMode  {#SP_SLV_01_01}

Enumeration: `AUTO`, `DENSE`, `SPARSE`. Wire and storage spelling: `"auto"`, `"dense"`, `"sparse"` (lower case).

| Scope | Holder | Values | Default | Persistence |
|---|---|---|---|---|
| Session default | the session (one per application) | `AUTO` · `DENSE` · `SPARSE` | `AUTO` | application preferences key `solverMode`; an absent or unrecognized stored value reads as `AUTO` |
| Document override | each open document | `AUTO` · `DENSE` · `SPARSE` · none | none | never persisted: not in text or JSON circuit files, not in session save, not in the closed-tab dump, not copied by undo/redo or checkpoints |

Effective mode of a document = its override when set, else the session default.

### 01_02. SolvePath  {#SP_SLV_01_02}

Enumeration: `DENSE`, `SPARSE`; wire spelling `"dense"`, `"sparse"`. Chosen once per stamp by [§02_05](#SP_SLV_02_05).

Constant `DENSE_MAX_SIZE = 64`: in `AUTO` mode a reduced system with `m ≤ DENSE_MAX_SIZE` takes `DENSE`, a larger one `SPARSE`.

### 01_03. SystemStore  {#SP_SLV_01_03}

The assembled full system of one stamp, before reduction.

| Field | Type | Constraints | Description |
|---|---|---|---|
| n | int | ≥ 0 | Full system size |
| entries | map (row:int, col:int) → value:double | rows, cols in `0..n-1` | One slot per position stamped in this stamp; a slot holds the sum of its stamps in stamp order |
| rhs | double[n] | — | Right-hand side |
| rowEntries | per row, the columns of its slots in ascending order | — | Derived when reduction starts |
| colRows | per column, the rows of its slots in ascending order | — | Derived when reduction starts |
| rowInfo | RowInfo[n] | — | Today's per-unknown record: `type` (`NORMAL` / `CONST`), `value`, `mapRow`, `mapCol`, `rsChanges`, `lsChanges`, `dropRow` (unchanged meaning) |

Invariants:
- A slot exists for (r, c) exactly when some stamp call addressed (r, c) in this stamp, including calls that added 0. Absence of a slot means the value is 0.
- Memory is O(n + number of slots); no structure is n × n.

### 01_04. ReducedSystem  {#SP_SLV_01_04}

The system the solve paths factor, produced by row reduction ([§02_04](#SP_SLV_02_04)).

| Field | Type | Constraints | Description |
|---|---|---|---|
| m | int | 0 ≤ m ≤ n | Number of unknowns with `type = NORMAL` |
| pattern | compressed columns: `colStart[m+1]`, `rowIndex[nnz]` (ascending within a column) | — | Positions of the reduced matrix: the slots of this stamp's reduction plus the carried positions ([§01_11](#SP_SLV_01_11)) |
| values | double[nnz] | — | Current values, aligned with `rowIndex` |
| rhs | double[m] | — | Current right-hand side |
| slotOf | (reducedRow, reducedCol) → slot index or none | lookup O(log d) or O(1), d = entries in the column | For stamps after reduction |
| pending | list of (reducedRow, reducedCol, value) | — | Stamps at positions outside the pattern since the last factorization ([§02_06](#SP_SLV_02_06)) |
| patternVersion | int | ≥ 0, +1 per growth | Identifies the pattern the symbolic analysis was built on |

### 01_05. Snapshot  {#SP_SLV_01_05}

| Field | Type | Description |
|---|---|---|
| values0 | double[nnz] | `values` right after reduction (the constant part); a position added by pattern growth has 0 here |
| rhs0 | double[m] | `rhs` right after reduction |

Restoring the snapshot copies `values0 → values` and `rhs0 → rhs`; cost O(nnz + m). It replaces today's `origMatrix → circuitMatrix` and `origRightSide → circuitRightSide` copies.

### 01_06. DenseWorkspace  {#SP_SLV_01_06}

Dense path only: today's `circuitMatrix` (m × m), `origMatrix`, `circuitRightSide`, `origRightSide`, `circuitPermute`, filled from the ReducedSystem by [§02_05](#SP_SLV_02_05). From then on the dense path computes exactly as today: stamps go to `circuitMatrix`, the Newton restore copies `origMatrix` (a linear circuit restores only the right-hand side), and [SP_MDS_02_02](./math-dsp.sp.md#SP_MDS_02_02) `lu_factor` / [SP_MDS_02_03](./math-dsp.sp.md#SP_MDS_02_03) `lu_solve` factor and solve. The one addition: when `m ≤ 12`, the matrix is copied before each factorization so a failure can dump the assembled values ([§02_03](#SP_SLV_02_03)); the copy does not change any computed value.

### 01_07. SymbolicAnalysis  {#SP_SLV_01_07}

Sparse path only.

| Field | Type | Description |
|---|---|---|
| colPerm | int[m] | Column permutation from the maximum transversal: column `colPerm[k]` is paired with row `k` (a non-zero on the paired diagonal) |
| order | int[m] | Fill-reducing elimination order (minimum degree on the symmetrized pattern of the paired system) |
| builtFor | int | `patternVersion` it was built for |
| structurallySingular | bool | The transversal found fewer than m pairs |
| unmatchedCol | int | When structurally singular: the lowest unmatched reduced column; else −1 |

### 01_08. Factorization  {#SP_SLV_01_08}

Sparse path only.

| Field | Type | Description |
|---|---|---|
| L, U | compressed columns on the structural reach (`reach` below) | Unit lower and upper factors in elimination order; a structurally reached entry is stored even when its value is 0, so a refactorization never loses a position |
| pivotRow | int[m] | Row chosen as pivot at each elimination step |
| valid | bool | The factors describe the current `values` |
| fullBuiltFor | int | `patternVersion` of the last full factorization (refactorization reuses its `pivotRow`) |
| reach | per elimination step, the structural pattern of the L and U columns computed by F | Rebuilt by every full factorization; entries present even when their value is 0 |

### 01_09. SingularityReport  {#SP_SLV_01_09}

| Field | Type | Description |
|---|---|---|
| column | int | Reduced column of the failed elimination step (−1 when unknown) |
| row | int | Reduced row holding the largest candidate pivot (−1 when unknown) |
| pivotAbs | double | Largest candidate pivot magnitude (0 for a structural failure) |
| unknown | int | Full-system index of the unknown of `column`: the `j` with `rowInfo[j].mapCol == column` (−1 when `column` is −1) |
| variable | string | `nodeVoltage(node=k)` when `unknown < nodeCount` (k = unknown + 1), else `voltageSourceCurrent(vs=i, elm=<class>, id=<element ID>)` with `i = unknown − nodeCount` |

Both paths produce this report and apply the same absolute test ([C_SLV_DEC_04](./linear-solver.concept.md#C_SLV_DEC_04)). The sparse path is at least as strict: a structurally singular system is always reported, while the dense path can let one through when round-off leaves a pivot above 1e-14 (the reviewer reproduced it in 539 of 20 000 random 3 × 3 trials). Both paths agree on systems whose dense failing pivot is exactly 0. The dense path takes `column`, `row`, `pivotAbs` from today's `lastLuFail*` diagnostics. `unknown` maps through `mapCol` on both paths. Today's `describeMatrixVariable` interprets a reduced column as a full index, which names the wrong unknown after any reduction; it is corrected to this mapping.

### 01_10. SolverInfo  {#SP_SLV_01_10}

The observable state of one document's solver. Consumers: the Agent API Diagnostics ([§02_12](#SP_SLV_02_12)) and the live tests.

| Field | Type | Description |
|---|---|---|
| mode | SolverMode | Session default |
| override | SolverMode? | Document override, absent when none |
| effectiveMode | SolverMode | [§01_01](#SP_SLV_01_01) |
| path | SolvePath? | Path of the current stamp; absent before the first stamp or after a stop that dropped the system |
| fullSize | int | n |
| size | int | m |
| nonZeros | int | nnz of the reduced pattern (dense path: number of reduced positions with a slot, same count) |
| factorNonZeros | int | nnz(L) + nnz(U) of the last factorization (sparse path); m² (dense path) |
| symbolicCount | int | Symbolic analyses since the engine analysis started (`analysisCount` changed) |
| fullFactorCount | int | Full factorizations since the engine analysis started (both paths) |
| refactorCount | int | Accepted refactorizations since the engine analysis started (sparse path; 0 on dense) |

### 01_11. CarriedState  {#SP_SLV_01_11}

Sparse path only. What survives from one stamp to the next within one engine analysis, so a time-step change or a recovery re-stamp does not lose the grown pattern or redo the ordering.

| Field | Type | Description |
|---|---|---|
| forAnalysis | int | `analysisCount` it belongs to |
| maps | (mapRow[n], mapCol[n]) | Reduction maps of the stamp that produced it |
| extraPositions | set of (reducedRow, reducedCol) | Positions added by pattern growth ([§02_06](#SP_SLV_02_06)) |
| symbolic | SymbolicAnalysis? | Last symbolic analysis and the pattern it was built for |

Rule: at `selectPath` on the sparse path, when `forAnalysis = analysisCount` and the new reduction's maps equal `maps`, the new pattern is this stamp's slots ∪ `extraPositions` (the extra positions with value 0), and `symbolic` is reused when that union equals the pattern it was built for: the new ReducedSystem then takes `patternVersion := symbolic.builtFor`, so step S of [§02_07](#SP_SLV_02_07) finds it current. When the maps match but the union differs from that pattern, the extra positions stay (positions are never removed within an engine analysis, [§02_06](#SP_SLV_02_06)) and the ReducedSystem takes a new version. When the analysis or the maps differ, the carried state is discarded and rebuilt with a new version. New versions come from a counter that never repeats (it is not reset), so a version identifies one pattern. A new engine analysis, a path change, `stop` and `resetSolverState` discard the carried state.

## 02. Contracts  {#SP_SLV_02}

The contracts below are the solver's interface to the engine ([C_SLV_04_02](./linear-solver.concept.md#C_SLV_04_02)). The engine's stamping primitives ([SP_SIM](./simulator-engine.sp.md)) keep their names, arguments, sanitizing and node-numbering rules; only the storage behind them changes.

### 02_01. beginStamp  {#SP_SLV_02_01}

Purpose: start the system of a new stamp (today's start of `stampCircuit`). The contract keeps the name `beginStamp` in the plan; this section is its definition.

Input: `n: int ≥ 0`, `analysis: int` (the engine's `analysisCount`). Output: none.

Processing:

    beginStamp(n, analysis):
        store := empty SystemStore of size n; rowInfo := n fresh RowInfo (type NORMAL)
        reduced, snapshot, dense workspace, factorization := none
        if analysis ≠ counters.forAnalysis: counters := 0; counters.forAnalysis := analysis
        carried state: kept (its reuse is decided by §01_11 at selectPath)
        phase := ASSEMBLING

### 02_02. addEntry  {#SP_SLV_02_02}

Purpose: the storage behind `stampMatrix(i, j, x)`; `x` is already sanitized, `(r, c) = (i−1, j−1)`, ground stamps (`i = 0` or `j = 0`) never reach it.

| Phase | Behaviour |
|---|---|
| ASSEMBLING | `entries[(r, c)] += x`, creating the slot (value 0 + x) when absent |
| REDUCED, dense path | today's rule: `r' = rowInfo[r].mapRow`; if `rowInfo[c].type = CONST` then `rhs[r'] −= sanitize(x · rowInfo[c].value)`, else `circuitMatrix[r'][rowInfo[c].mapCol] += x` |
| REDUCED, sparse path | same mapping; a non-constant column adds `x` to `values[slotOf(r', c')]`; when the slot is absent, append `(r', c', x)` to `pending` (pattern growth, [§02_06](#SP_SLV_02_06)) |

`addRhs(i, x)` (behind `stampRightSide(i, x)`): ASSEMBLING → `store.rhs[i−1] += x`; REDUCED → `rhs[rowInfo[i−1].mapRow] += x`, as today. `stampRightSide(i)` and `stampNonLinear(i)` keep setting `rsChanges` / `lsChanges`.

A stamp addressed to a dropped row (`mapRow = −1`) in phase REDUCED is outside today's contract (the element did not mark the row, [mna-stamping Pitfall 6](../.dev_flow/skills/simulator/mna-stamping.md)); both paths ignore it.

### 02_03. Store reads for diagnostics  {#SP_SLV_02_03}

`dumpSystem()` writes the reduced system's values, row by row as today's `dumpCircuitMatrix`, for `m ≤ 12`. On both paths it dumps the assembled values of the current iteration, never partially factored values: the sparse path keeps them in `values`, the dense path in the pre-factor copy of [§01_06](#SP_SLV_01_06). Today's dense path dumps `circuitMatrix` after a failed `lu_factor`, which is overwritten in place; that output changes to the assembled values.

### 02_04. reduce  {#SP_SLV_02_04}

Purpose: today's `simplifyMatrix`, over the store.

Output: `OK` or `MATRIX_ERROR` (today's "Matrix error" condition; the engine keeps today's handling: under recovery `warn` + `singularStabilizersActive = true`, else `stop`).

Rules (the same decisions as today, in the same order):
1. Scan rows `i = 0..n-1`; skip a row with `lsChanges`, `rsChanges` or `dropRow`.
2. For row `i`, visit its slots in ascending column order. A slot whose column is `CONST` adds `−rowInfo[c].value · value` to the adjustment. A non-constant slot with value ≠ 0 is a candidate. The scan stops at the second candidate. Zero-valued non-constant slots and absent positions are skipped. (Today also adds `−value · 0` for absent positions of `CONST` columns; with finite `CONST` values that term is ±0 and changes no sum.)
3. Exactly one candidate in column `p` → `rowInfo[p]` becomes `CONST` with `value = (rhs[i] + adjustment) / pivotValue` and row `i` is marked `dropRow`. Then restart from the lowest row `j < i` whose slot in column `p` has a non-zero value; `colRows[p]` gives the rows in ascending order. No candidate at all → `MATRIX_ERROR`. A candidate column already `CONST` → today's console message, and the row is skipped.
4. Number the `NORMAL` unknowns: `mapCol` ascending; number the rows not dropped: `mapRow` ascending. When nothing was reduced (m = n), both maps are the identity; today leaves `mapRow` at its default and does not use it in that case, so the identity reproduces today's addressing.
5. Build the ReducedSystem. For each kept row in ascending full order, visit its slots in ascending column order: a `CONST` column subtracts `value · rowInfo[c].value` from the reduced rhs; a `NORMAL` column becomes a pattern position `(mapRow, mapCol)` holding the slot value, including slots whose value is 0.
6. Take the Snapshot; phase := REDUCED.

Invariant: for finite `CONST` values, the reduced values, reduced rhs, `CONST` values and `mapCol` equal, bit for bit, what today's `simplifyMatrix` computes from the same stamps, and `mapRow` equals today's whenever today rebuilds the matrix (the identity otherwise). The floating-point sums run in today's order: stamp order within a slot, ascending column within a row. A non-finite `CONST` value (an overflowing division) is outside the invariant: today it turns every row's sum into NaN through `∞ · 0`, the store only the rows that hold that column; the engine's NaN check after the solve reacts to both. Cost: O(slots · (1 + restarts)) in the worst case, in today's scan order, instead of O(n² · (1 + restarts)).

### 02_05. selectPath  {#SP_SLV_02_05}

Purpose: choose the path of this stamp once reduction succeeded.

    selectPath(effectiveMode):
        path := DENSE   if effectiveMode = DENSE
                SPARSE  if effectiveMode = SPARSE
                DENSE   if effectiveMode = AUTO and m ≤ DENSE_MAX_SIZE
                SPARSE  otherwise
        if path = DENSE: allocate the DenseWorkspace (m × m); copy the reduced values to their positions; copy rhs; take origMatrix/origRightSide as today
        if path = SPARSE: merge carried positions (§01_11); symbolic analysis is built or reused by §02_07 step S

The path holds until the next `beginStamp`. A forced `DENSE` on a large system allocates m² values; that is the user's explicit choice and is not capped. On the sparse path, `selectPath` applies the carried-state rule of [§01_11](#SP_SLV_01_11) before building the symbolic analysis.

### 02_06. Pattern growth  {#SP_SLV_02_06}

A sparse-path stamp in phase REDUCED at a position outside the pattern lands in `pending`. Before the next factorization:

    mergePending():
        for each (r, c, x) in pending:
            if (r, c) already a slot (added earlier in this merge): values[slot] += x
            else: insert slot (r, c) with value x, snapshot values0 at that slot := 0
        pending := empty; patternVersion += 1; symbolic stale

Every merged position is also added to the carried `extraPositions` ([§01_11](#SP_SLV_01_11)), so positions are never removed within an engine analysis while the reduction maps stay the same. A restore of the snapshot after a merge gives the new positions value 0. Pending entries are part of the current iteration's values and are never lost.

### 02_07. factor  {#SP_SLV_02_07}

Purpose: factor the current values. Called once per stamp for a linear circuit (inside `stampCircuit`, as today), and once per Newton iteration for a nonlinear one.

Output: `OK` or a SingularityReport.

Dense path: today's `lu_factor` on `circuitMatrix`; failure → report from `lastLuFail*` + `mapCol`.

Sparse path:

    factor():
        if pending not empty: mergePending()
        S: if symbolic absent or symbolic.builtFor ≠ patternVersion:
               build transversal (maximum transversal, with lookahead) → colPerm, structurallySingular, unmatchedCol
               build order (minimum degree on the pattern of P + Pᵀ, P = paired system)
               symbolic.builtFor := patternVersion; symbolicCount += 1
               factorization.valid := false
        if symbolic.structurallySingular:
               return report(column = unmatchedCol, row = −1, pivotAbs = 0)
        if factorization exists and factorization.fullBuiltFor = patternVersion:
               R: refactorize on the stored structural reach with the stored pivotRow
                  for each step k: candidates := the structurally reached, not yet pivotal rows of step k
                                   (the same set step F used); candMax := max |value| over them, the values
                                   after this step's sparse triangular solve
                                   pivot := the value at pivotRow[k]
                  accept step k when |pivot| ≥ 1e-14 and |pivot| ≥ 1e-3 · candMax
                  all steps accepted → refactorCount += 1; return OK
                  any step rejected → fall through to F
        F: full factorization, left-looking, in `order`:
               for step k (eliminating reduced column c_k = colPerm[order[k]]):
                  reach := the structural reach of column c_k through the L columns built so far
                           (entries kept even when their value is 0)
                  candidates := the reached rows not yet pivotal; candMax := max |value| over them, the values
                                after the sparse triangular solve of this step
                  if candMax < 1e-14: return report(column = c_k, row = the argmax row (−1 if no candidate),
                                                    pivotAbs = candMax)
                  pivot := the row paired with c_k if |its value| ≥ 1e-3 · candMax, else the argmax row
                  store the reach as the L and U patterns of step k
               fullBuiltFor := patternVersion; fullFactorCount += 1; valid := true; return OK

The factors live apart from `values`, `rhs` and the snapshot. A failed factorization leaves the system intact: a re-stamp, a retry or a dump sees the assembled values.

Errors: none thrown. Every failure is a SingularityReport, and the engine applies [SP_SIM_02](./simulator-engine.sp.md#SP_SIM_02) SINGULAR (enable stabilizers → re-stamp; still singular → escalate or stop).

### 02_08. solve  {#SP_SLV_02_08}

Purpose: solve with the last successful factorization. Input: the current reduced `rhs`. Output: `x: double[m]` in reduced column order, handed to today's `applySolvedRightSide`, which maps `CONST` unknowns to their values and the others through `mapCol`.

Dense path: today's `lu_solve`. Sparse path: forward and back substitution over L and U with the row and column permutations; cost O(nnz(L) + nnz(U)).

Precondition: the last `factor` returned `OK` for the current values. A solve after a failed factor is never called; today's loop already leaves the iteration.

### 02_09. Solver-mode scopes  {#SP_SLV_02_09}

| Operation | Effect | Re-stamp |
|---|---|---|
| set session default (UI, [§02_14](#SP_SLV_02_14)) | writes preference `solverMode`; every open document without an override gets a new effective mode | each such document whose effective mode changed gets `solverRestampPending` |
| set document override (Agent API, [§02_11](#SP_SLV_02_11)) | sets the override of that document | that document gets `solverRestampPending` when its effective mode changed |
| clear document override (`mode: "session"`) | removes the override | same |
| document closed | override discarded | — |

`solverRestampPending` (document-scoped flag) requests a stamp only: `stampCircuit`, never `preStampCircuit`. So a mode change does not re-validate, reset the time step to its maximum, call the analyze hook, or start a new engine analysis. The flag is consumed:
- at the next free-running frame of the document, before its first timestep;
- at the start of an agent run of the document, before its first slice;
- at the next reading that stamps (`ensureAnalysed`), when no stamp is pending anyway.

While an agent run owns the document, the flag waits until the run has ended. A pending full stamp (`needsStamp`) also satisfies and clears the flag. A document with no system (never stamped, or stopped) needs nothing: its next stamp uses the new mode.

A change never converts a system between paths mid-stamp: the old path keeps serving until the re-stamp, where `beginStamp` and `selectPath` apply the new effective mode. A change that leaves the effective mode unchanged sets nothing.

### 02_10. Engine integration  {#SP_SLV_02_10}

Changes to [SP_SIM](./simulator-engine.sp.md) contracts (amendments, same behaviour on the dense path):

| SP_SIM step | Today | With SP_SLV |
|---|---|---|
| `stampCircuit` allocation | two dense n × n tables | `beginStamp(n, analysisCount)` |
| element `stamp()` loop | writes `circuitMatrix` | `addEntry` / `addRhs` (phase ASSEMBLING) |
| `simplifyMatrix` | dense scan and rebuild | `reduce` ([§02_04](#SP_SLV_02_04)), then `selectPath` |
| linear circuit factor at stamp | `lu_factor` | `factor` |
| Newton restore | `origRightSide → circuitRightSide` every iteration; `origMatrix → circuitMatrix` only for a nonlinear circuit | dense: unchanged; sparse: snapshot restore of `rhs` every iteration, of `values` only for a nonlinear circuit |
| Newton factor / solve | `lu_factor` / `lu_solve` | `factor` / `solve` |
| singular diagnostics | `lastLuFail*`, `describeMatrixVariable`, `dumpCircuitMatrix` | SingularityReport ([§01_09](#SP_SLV_01_09)), `dumpSystem` |
| "no system" sentinel | `circuitMatrix == null` | the solver has no ReducedSystem; same meaning and the same callers (stop, reset, `runCircuit` guard) |
| mode change | — | `solverRestampPending` consumed as [§02_09](#SP_SLV_02_09) says (frame, run start, stamping reading) |

Element code, the stamping primitives' signatures, the stabilizer stamps, `applySolvedRightSide` and `setNodeVoltages` are unchanged.

### 02_11. Agent API: simControl action `solver`  {#SP_SLV_02_11}

Amends [SP_AGA_02_09](./agent-api.sp.md#SP_AGA_02_09). The edits to SP_AGA: §02_09 Input (`action` enum gains `solver`, new argument `mode`) and Output (`solver` block); §01_11 Diagnostics (`solver` field, [§02_12](#SP_SLV_02_12)); the contract-classes table under §02 (the row "simControl `run`/`stop`/`reset`, run" gains `solver`); §02_09 Errors.

Input: `doc?`, `action: "solver"`, `mode: "auto" | "dense" | "sparse" | "session"`. `"session"` clears the override.

Output: `data: {running, simTime, timeStep, solver: SolverInfo}` — today's simControl data plus the `solver` block ([§01_10](#SP_SLV_01_10), wire field names as listed, modes in lower case). The values describe the state after the change. `path`, `size`, `nonZeros` describe the current stamp and the counters the current engine analysis. After a change of effective mode, they are those of the next stamp once that runs. The call itself does not stamp.

Contract class: not mutating; it opens no transaction, sets no modified flag and records no history (the same class as `run` / `stop` / `reset`). It is not served while the document is busy (`busy`).

Errors:
| Code | Condition | Guidance |
|---|---|---|
| `invalid_value` | `mode` absent or not one of the four values | names `mode`; hint lists the values |
| `invalid_value` | `mode` given with an action other than `solver`, or `settings` given with `solver` | names the argument |
| `busy` | the document is in an agent run | retry after the run |
| `unknown_document` | unknown `doc` | as today |

### 02_12. Agent API: Diagnostics `solver`  {#SP_SLV_02_12}

Amends [SP_AGA_01_11](./agent-api.sp.md#SP_AGA_01_11): Diagnostics gains `solver: SolverInfo`, always present. `getDiagnostics` stamps before reading, as today, so `path` is set for a document with elements.

### 02_13. MCP tool `circuit_sim`  {#SP_SLV_02_13}

Amends [SP_MCP](./mcp-server.sp.md) and the `circuit_sim` tool:
- `action` enum gains `solver`;
- a new optional argument `mode` takes the enum `auto` / `dense` / `sparse` / `session`;
- the tool description gains one sentence: "solver: choose the solve path of this document (auto by size, dense, sparse; session = the user's setting); not saved";
- the output schemas of `circuit_sim` and `circuit_diagnostics` gain an optional `solver` object;
- `toolsVersion` becomes 1.3 (an addition, [SP_MCP §06_01](./mcp-server.sp.md)); the agent skill's compatibility line and the agent-format header follow.

The result passes through unchanged. Tool annotations stay as they are (they are per tool; `reset` already makes `circuit_sim` destructive).

### 02_14. Other Options dialog  {#SP_SLV_02_14}

Amends [C_DSP](./dialog-specialized.concept.md) / the Other Options dialog (EditInfo rows):
- **The new row.** A row "Solver" with a choice of three entries: "Auto", "Dense", "Sparse". All labels go through the locale table (RULE_STYLE_003). The dialog's row list ends at its first absent row and the minimum-time-step row is present only with auto time step, so the new row goes before the time-step rows (the later rows and their value handlers shift by one).
- **On open.** The choice shows the session default.
- **On change.** A change applies [§02_09](#SP_SLV_02_09) "set session default" at once and persists the preference. The row is marked so the dialog does not request a circuit analysis for it (`EditInfo.noAnalyze`): documents re-stamp only. Closing the dialog with OK keeps the dialog's existing behaviour for every row (it applies all rows and requests an analysis of the active document).
- **Overrides are not shown.** A document override is never displayed here; the dialog edits the session default only.

## 03. Validation Rules  {#SP_SLV_03}

### 03_01. Input Validation  {#SP_SLV_03_01}

- **Mode strings.** Agent `mode` is one of `auto`, `dense`, `sparse`, `session`, case-sensitive; anything else is `invalid_value`. A stored preference outside `auto` / `dense` / `sparse` reads as `auto`.
- **Indices.** `addEntry` / `addRhs` indices come from the primitives' node mapping; an index outside `0..n-1` is a programming error. It is guarded as today by the primitives' `i > 0` tests and not reported to users.
- **Value sanitizing.** Values are sanitized by `sanitizeStampValue` before they reach the store (clamp ±1e12, NaN → 0, `converged = false`), as today.
- **Constants.** `DENSE_MAX_SIZE = 64`, the pivot threshold `1e-3`, and the singularity threshold `1e-14` are solver constants. Only the plan's benchmark phase may change `DENSE_MAX_SIZE`, and only by updating this section.

## 04. State Transitions  {#SP_SLV_04}

### 04_01. Solver state of one document  {#SP_SLV_04_01}

    [NONE] --beginStamp--> [ASSEMBLING] --reduce OK--> [REDUCED] --selectPath--> [READY(path)]
    [ASSEMBLING] --reduce MATRIX_ERROR--> [NONE]
    [READY] --factor OK--> [FACTORED] --stamps / restore--> [READY] --factor--> ...
    [READY|FACTORED] --pattern growth (sparse)--> [READY, symbolic stale]
    [any] --stop / resetSolverState / beginStamp--> [NONE] / [ASSEMBLING]

| From | To | Condition | Side effects |
|---|---|---|---|
| NONE | ASSEMBLING | `stampCircuit` | store reset; counters reset when the engine analysis changed |
| ASSEMBLING | REDUCED → READY | reduction OK | snapshot taken; path chosen; dense workspace or symbolic built |
| ASSEMBLING | NONE | `MATRIX_ERROR` | engine warns or stops (today's rule) |
| READY | FACTORED | `factor` OK | counters updated |
| READY | READY | `factor` fails | SingularityReport; system intact |
| FACTORED | READY | snapshot restore or new stamps | factors no longer valid for the values (sparse: kept for refactorization) |
| any | NONE | stop dropping the system, `resetSolverState` | all solver data released, carried state included |

### 04_02. Mode change  {#SP_SLV_04_02}

| From | To | Condition | Side effects |
|---|---|---|---|
| effective mode X | effective mode Y ≠ X | session default or override changed | `solverRestampPending` on the document ([§02_09](#SP_SLV_02_09)) |
| effective mode X | X | change with no effect | none |

## 05. Verification Criteria  {#SP_SLV_05}

### 05_01. Functional Expectations  {#SP_SLV_05_01}

| Contract | Scenario | Input | Expected outcome |
|---|---|---|---|
| reduce | Bit identity | every example circuit, stamps of its first analysis | reduced values, rhs, `CONST` values and `mapCol` equal today's `simplifyMatrix` output bit for bit; `mapRow` equal where today rebuilds, the identity otherwise |
| selectPath | Auto small | example with m ≤ 64 | `path = dense` |
| selectPath | Auto large | 32×32 resistor grid (m = 1023) | `path = sparse` |
| selectPath | Forced | the grid with override `dense` / an RC example with override `sparse` | `dense` / `sparse` |
| factor + solve | Linear accuracy | captured grid 45² and RC ladder 2000 (spike kit), sparse | ‖Ax − b‖ / ‖b‖ ≤ 1e-12 |
| factor + solve | Dense vs sparse agreement | every example with ≥ 1 node, first solve after the first stamp, both forced paths | each path's backward error ‖Ax − b‖ / (‖A‖·‖x‖ + ‖b‖) ≤ 1e-13; node voltages agree within max(1e-9, 1e-15 / ρ) · max(1, \|v\|), ρ = the dense pivot ratio min\|u_kk\| / max\|u_kk\| (the forward error the conditioning allows for a backward error near 1e-15), on the examples with ρ ≥ 1e-8 (the others are judged by backward error only) |
| factor | Refactorization used | diode ladder (m ≈ 1000), 100 steps | `refactorCount > 0`; `fullFactorCount` ≪ Newton iterations |
| factor | Growth fallback | the spike's random net with large value changes (`dbg_refactor` case) | the refactorization is rejected, a full factorization runs, residual ≤ 1e-8 |
| factor | Structural singularity | a reduced system with a structurally empty column, produced by a harness wrapper that drops one column's stamps on the sparse path (as `solver_defects` wraps the factorization today) (circuits cannot reach it: validation enables the stabilizers for parallel sources first) | SingularityReport with `pivotAbs = 0` and the column's unknown; engine applies SP_SIM SINGULAR |
| factor | Numeric singularity, same semantics | a system made exactly singular numerically with its pattern intact (the wrapper makes two rows identical), forced sparse | sparse also reports singular; same stabilizer / escalation outcome |
| Pattern growth | Analog switch | an analog switch with the pull-down flag, initially open, closed by its control during the run, sparse forced | `symbolicCount` grows when the switch first closes and not again on later closings; node voltages equal the dense path within 1e-9 relative at each step of the first 50 |
| Carried state | Time-step change | diode ladder, sparse, auto time step changing the step | `symbolicCount` does not grow on the re-stamps of a step change (maps unchanged) |
| SingularityReport | Variable after reduction | a singular nonlinear circuit with at least one reduced row, both paths | `variable` names the unknown of the failed reduced column (through `mapCol`), not the full index |
| dumpSystem | Assembled values | failed factorization, m ≤ 12, both paths | dump equals the assembled values of the iteration |
| simControl solver | Set override | `{action:"solver", mode:"sparse"}` on a small example | `solver.override = "sparse"`, `effectiveMode = "sparse"`; after the next run `path = "sparse"`; no history entry; modified flag unchanged; the analyze hook is not called and the current time step is kept |
| simControl solver | Clear | `mode: "session"` | `override` absent; effective = session default |
| simControl solver | Invalid | `mode: "fast"`; `mode` with `action: "run"` | `invalid_value` naming `mode` |
| simControl solver | Busy | during an agent run | `busy` |
| Diagnostics | Block present | any document | `solver` with every field of §01_10 |
| Options dialog | Session default | choose "Sparse", reopen dialog, restart app | dialog shows Sparse; preference `solverMode = "sparse"`; documents without override restamp on the sparse path |
| Persistence | Override not saved | override `dense`, save text and JSON, reload | files contain no solver field; reloaded document has no override |

### 05_02. Invariant Checks  {#SP_SLV_05_02}

| Invariant | Verification method |
|---|---|
| Dense path below the threshold is today's solver | live corpus run in `AUTO`: every example with m ≤ 64 gives node voltages identical bit for bit to the pre-change build after 200 steps (recorded baseline) |
| No n × n structure on the sparse path | memory probe: 5000-node grid analysis in `AUTO` allocates < 100 MB total solver data (vs ≥ 400 MB today) |
| Store survives a failed factorization | forced sparse failure then retry: the retry factors the assembled values (no residue of the failed attempt) |
| Override never persisted | grep of exported text/JSON and session save for `solver` after setting an override |
| Pattern only grows within an engine analysis | `nonZeros` non-decreasing between stamps of one engine analysis while the maps are unchanged; reset by a new engine analysis |

### 05_03. Integration Scenarios  {#SP_SLV_05_03}

| Scenario | Preconditions | Steps | Expected result |
|---|---|---|---|
| Agent compares paths | large RC ladder imported in a background document | run 1 ms with override `dense`, record probe; reset; override `sparse`, run again | probe statistics agree within 1e-6 relative; `wallMs` sparse < dense |
| User forces Dense as rollback | session default `sparse`, a visible circuit | Other Options → Dense | the visible circuit restamps on the dense path; behaviour equals the pre-change build |
| Background document and session change | doc A visible, doc B background, no overrides | change session default | both get `solverRestampPending`; A re-stamps at its next frame, B at its next run or stamping reading |
| Singular escalation unchanged | a nonlinear circuit singular with stabilizers active (forced as in `solver_defects`) | run on each path | `singular_matrix` event on both paths; recovery as SP_SIM SINGULAR |
| MCP | MCP server running | `circuit_sim {action:"solver", mode:"sparse"}` then `circuit_diagnostics` | override set; diagnostics show it |

### 05_04. Edge Cases and Boundaries  {#SP_SLV_05_04}

| Case | Input | Expected behavior |
|---|---|---|
| Empty reduced system | every unknown resolved by reduction (m = 0) | both paths: no factorization work; `applySolvedRightSide` uses the constants; `size = 0` |
| One unknown | m = 1 | dense path as today's `n == 1` case; forced sparse gives the same values |
| Threshold boundary | m = 64 and m = 65 in `AUTO` | dense and sparse respectively |
| Forced dense on huge system | m = 10 000, override `dense` | allowed; allocates m² (≈ 800 MB); not capped |
| Mode change during free-run | user changes session default while running | restamp at the next frame; no exception; simulation continues |
| Mode change while a run is in progress | agent `solver` action during agent run | `busy` |
| Empty document | no elements | `solver.path` absent; action `solver` still sets the override |
| Mode change during an agent run (UI) | session default changed while a background run owns a document | that document keeps its path until the run ends, then re-stamps |
| Reduction matrix error | "Matrix error" circuit | `MATRIX_ERROR` handled as today on both paths |
| Matrix stamp after reduction in a linear analysis | an element writes the matrix in `doStep` without marking its row non-linear (outside the element contract) | dense: as today; sparse: the value goes to `values`/`pending` but no factorization follows until the next stamp, so it has no effect, as today; `pending` is cleared by `beginStamp` and never enters the carried positions |

### 05_05. Performance Targets  {#SP_SLV_05_05}

Measured with the spike kit (`.dev_flow/cache/sparse-spike/`) on the headless build, background document, medians of 3. Targets set from the spike's prototype numbers, with a margin for the plain arrays the compiled code uses:

| Case | Today | Target (AUTO) |
|---|---|---|
| 45² resistor grid (m = 2024): analysis | 21.7 s | ≤ 1 s |
| RC ladder m = 2002: analysis / step | 21 s / 28 ms | ≤ 0.5 s / ≤ 15 ms |
| Diode ladder m = 1002: step | 5.9 s | ≤ 50 ms |
| Diode grid m = 197 (240 iterations / step): step | 3.6 s | ≤ 0.5 s |
| Examples m ≤ 64 | — | no slowdown beyond 5 % in the `frame_cost` and corpus timing |

## 06. Reversibility  {#SP_SLV_06}

### 06_01. Rollback Strategy  {#SP_SLV_06_01}

| Aspect | Rollback approach |
|---|---|
| Data/state changes | None persistent beyond the preference `solverMode`; an unknown or removed value reads as `auto`, and a build without the sparse path ignores it |
| Artifacts | The sparse solver code, its tests and the preference key; removing them leaves the dense path |
| Dependent modules | SP_SIM (storage behind primitives) returns to dense tables; SP_AGA `solver` action and Diagnostics `solver` block; SP_MCP `circuit_sim` enum value |
| External contracts | Agent API action `solver` and Diagnostics field `solver` are published to agents. Removal is a breaking API change: deprecate first (the action returns `invalid_value` with a hint), then remove; the MCP tool schema follows |

Runtime rollback without code change: Other Options → Solver → Dense restores today's solver for every document without an override (an agent override is cleared by `mode: "session"` or by closing the document).

## 07. Design Decisions  {#SP_SLV_DEC}

### DEC_01 — Agent API surface of the per-document override  {#SP_SLV_DEC_01}

> **Status:** resolved
> **Date:** 2026-10-05

**Question:** How does an agent set the unsaved per-document solver mode?

**Options considered:**
| Option | Consequence |
|--------|-------------|
| A — New `simControl` action `solver` with `mode` | Non-mutating call class (no transaction, history or modified flag); one enum value more in the MCP tool |
| B — A `solverMode` setting of `simControl configure` | One place for settings, but `configure` is mutating (time-step settings are saved), so `solverMode` would need exceptions inside one call |
| C — A separate contract and MCP tool | Cleanest semantics, largest surface (operation catalogue, tool list, agent skill) |

**Decision:** A — new `simControl` action `solver`.
**Rationale:** Keeps the unsaved override out of the mutating `configure` call and adds the least surface.
**Rejected because:** B — mixed semantics within one call; C — surface without benefit over A.

### DEC_02 — Decisions taken without interview  {#SP_SLV_DEC_02}

> **Status:** resolved (cheaply reversible, no consumer impact)
> **Date:** 2026-10-05

- **Store and reduction are sparse on every path.** The path depends on the reduced size, known only after reduction, so assembly cannot be dense even for the dense path. Bit identity is kept by the summation-order rules of [§02_04](#SP_SLV_02_04).
- **`DENSE_MAX_SIZE = 64`, dense when m ≤ 64.** Only 3 examples have m ≥ 64 (spike corpus sweep).
- **The UI row goes in the Other Options dialog,** with the other session preferences (language, wheel sensitivity), not in a menu.
- **No scripting-interface (`CircuitJS1`) method.** The live tests and agents use the Agent API; no consumer needs the legacy global. The concept's "scripting interface" mention is narrowed accordingly.
- **The solver lifecycle is per stamp, its memory per engine analysis.** The engine re-stamps on every time-step change and every recovery re-stamp, so the store is rebuilt per stamp, while the grown pattern, the symbolic analysis and the counters carry over within one engine analysis ([§01_11](#SP_SLV_01_11)). This keeps adaptive stepping from redoing the ordering.
- **A mode change re-stamps only** ([§02_09](#SP_SLV_02_09)): a full re-analysis would reset the time step and re-run validation and the analyze hook mid-run.
- **The dense singularity diagnostics are corrected.** The report maps a reduced column to its unknown through `mapCol` ([§01_09](#SP_SLV_01_09)), and the dump shows assembled values ([§02_03](#SP_SLV_02_03)). Both are corrections of today's output and part of DEC_04's "same diagnostics".

## Changelog

| Date | Change |
|------|--------|
| 2026-10-05 | Status active: delivered by PL_SLV P1–P6; §05_05 targets measured on the delivered build (see the plan's P6 notes). |
| 2026-10-05 | Initial version; SP_SLV_DEC_01 resolved in interview. |
| 2026-10-05 | Implementation (PL_SLV P5): §02_13 `toolsVersion` 1.3; §02_14 the Solver row changes without a dialog-requested analysis (`EditInfo.noAnalyze`), OK keeps the dialog's re-analysis. |
| 2026-10-05 | Implementation (PL_SLV P4 review): §01_11 versions come from a never-repeating counter; matching maps keep the extra positions when the symbolic pattern changed. |
| 2026-10-05 | Implementation (PL_SLV P4): the agreement tolerance scales with the conditioning — `relayand.txt` and `relaymux.txt` (ρ ≈ 4–7.5e-8, backward errors 5e-17) differ by 1.4e-9, within what their conditioning permits. |
| 2026-10-05 | Review round 2: carried symbolic analysis reused through `patternVersion` continuity (§01_11); `reach` moved to the factorization; backward-error agreement criterion; wording. |
| 2026-10-05 | Review round 1: per-stamp lifecycle with carried state (§01_11); re-stamp-only mode change (§02_09); structural L/U reach (§02_07); sparse singular test at least as strict (§01_09); reduction identity scope (§02_04); MCP schemas, dialog placement, SP_AGA edit list; realistic verification rows. |
