---
skill: mna-stamping
domain: simulator
topics: [mna, stamping, circuit-simulator, row-info, simplify-matrix, sanitize]
source: onboard
updated: 2026-04-18
---

# MNA Stamping

## Context

The simulator in `CircuitSimulator.java` (1844 LOC) implements
Modified Nodal Analysis. Every element contributes to the matrix `A`
and right-side `B` via a small set of `stamp*` primitives exposed by
`CircuitSimulator`. `INTERNALS.md` (lines 1-112) gives the MNA primer;
this skill catalogs the **project-specific API surface** — what exists,
where it is, and the quirks.

## Key concepts

**Two-phase contract on every `CircuitElm`:**
- `stamp()` — called once per topology (after `analyzeCircuit`).
  Stamps the linear/constant part of the element.
- `doStep()` — called once per Newton sub-iteration. Stamps the
  linearized nonlinear part (diodes, transistors) or updates B for
  companion-model dynamic elements (inductors, capacitors).

**Stamping primitives (`CircuitSimulator.java`).** All pass through
`sanitizeStampValue` (L1043) which clamps to ±1e12, coerces NaN to 0,
and sets `converged = false` on out-of-range — a silent robustness
net that can mask element bugs.

| Primitive | Line | Effect |
|---|---:|---|
| `stampMatrix(i, j, x)` | 1062 | `A[i][j] += x`; honors `circuitRowInfo[].mapRow/mapCol` after simplify; folds into B when column is ROW_CONST |
| `stampRightSide(i, x)` | 1083 | `B[i] += x` |
| `stampRightSide(i)` | 1096 | Mark row as `rsChanges = true` (dynamic B updated in doStep) |
| `stampNonLinear(i)` | 1103 | Mark row as `lsChanges = true` (A updated every Newton iteration) |
| `stampResistor(n1, n2, r)` | 1146 | Classic 4-corner ±1/r stamp; guards r=0/NaN/Inf |
| `stampConductance(n1, n2, g)` | 1161 | Same, conductance input (used by linearized nonlinear) |
| `stampVoltageSource(n1, n2, vs, v)` | 1118 | Extra VS row/col: KVL `[-1,+1]`, B[vn]=v |
| `stampVoltageSource(n1, n2, vs)` | 1130 | Same, marks B[vn] dynamic for `updateVoltageSource` |
| `updateVoltageSource(n1, n2, vs, v)` | — | Per-step B update (waveforms, chip outputs) |
| `stampVCCS` / `stampVCVS` / `stampCCCS` | 1171 / 1111 / 1185 | Controlled sources |
| `stampCurrentSource(n1, n2, i)` | 1178 | B-only |

**Row simplification.** `simplifyMatrix` (L755) collapses rows that
resolve to a single unknown, storing the solution as `RowInfo.ROW_CONST`
and back-referencing via `mapRow`/`mapCol`. Crucial perf for digital /
wire-heavy circuits. `origMatrix`/`origRightSide` snapshot the
*post*-simplify state for Newton restart.

**Node assignment runs first** (`preStampCircuit`): wire closure
(L190) → ground pick (L356) → `makeNodeList` (L417) → VS slot allocation
(L580) → unconnected-node repair (1e8 Ω tie to GND, L555). Wires are
**not given MNA rows** — their currents are reconstructed via
`calcWireCurrents` (L1231) after the solve.

**Voltage-source index.** Every element returning `getVoltageSourceCount()
> 0` gets one or more `vs` indices assigned via `setVoltageSource(n, vs)`
before `stamp()`. Extra matrix row is at index `nodeList.size() + vs`.

## Usage in this project

- Linear elements (resistor, capacitor, inductor) stamp entirely in
  `stamp()` — capacitors/inductors use **companion models**: a resistor
  + current source derived from Δt (see `Inductor.stamp` at
  `element/Inductor.java:65`, trapezoidal vs backward-Euler via
  `FLAG_BACK_EULER = 2`).
- Nonlinear elements (diode, transistor) mark rows with `stampNonLinear`
  in `stamp()` and re-stamp the linearization each `doStep()` — see
  `Diode.doStep` at `Diode.java:158` (Shockley + Zener + Newton limiting).
- `updateVoltageSource` is used by `ChipElm.doStep`
  (`ChipElm.java:335`) to push output pin values per step without
  full re-stamp.
- `validateCircuit` (L956) runs after `preStampCircuit`; failures trigger
  `stop()` or warn-and-recover under `nonConvergenceRecoveryEnabled`.

## Pitfalls

1. **Do not throw from `stamp`/`doStep`** (RULE_ERR_001, RULE_ERR_002).
   Signal failures via `simulator.stop(msg, ce)` or set `converged =
   false`; the Newton loop relies on it.
2. **`sanitizeStampValue` silences your bugs.** A stamp of `1e300` or
   NaN becomes `1e12` or `0` with `converged = false`, giving a
   "successful" solve that is actually clamped noise. Log
   `simulator-core` issue §10.3.
3. **Do not cache `nodeVoltages[i]` references.** The array is re-built
   whenever `circuitNeedsMap` flips or after `resetSolverState` (commit
   `a488ebb`). Always re-read via `simulator.getNodeVoltages(i)`.
4. **Wires are invisible to the matrix.** If a new element relies on
   KCL through a wire, make sure posts are shared — do not stamp across
   a wire post.
5. **`stampResistor(0, 0, r)` is a no-op** (both nodes == 0 / ground).
   Intentional, used for the 1e8 Ω unconnected-node repair.
6. **Row-simplification assumes row constancy.** If an element forgets
   to call `stampRightSide(i)` / `stampNonLinear(i)` when it actually
   updates B or A in `doStep`, the simplifier drops those rows and the
   element stops working after the first frame.

## References

- `.dev_flow/onboard/analysis/layer3__simulator-core.md` §4, §8.1
- `INTERNALS.md` lines 1-112 (MNA primer, companion models)
- `src/main/java/com/lushprojects/circuitjs1/client/CircuitSimulator.java`
  — stamp primitives at lines 1043-1185
- Rules: RULE_ERR_001, RULE_ERR_002
- Sibling skill: `newton-raphson-loop.md`, `time-step-control.md`
