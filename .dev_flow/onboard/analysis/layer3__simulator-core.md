# Layer 3 — `simulator-core` Sub-Unit

Analysis of the numerical simulation engine of CircuitJS1 Desktop, sitting beneath the element catalog and above the document/UI shell.

Files inspected (all paths absolute):
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/CirSim.java` (1533 LOC)
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/BaseCirSim.java` (288 LOC)
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/BaseCirSimDelegate.java` (64 LOC)
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/CircuitSimulator.java` (1844 LOC)
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/SimulationContextAware.java` (9 LOC)
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/Diode.java` (236 LOC)

Required companion reading (per task brief):
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/INTERNALS.md` — MNA primer, stamping idioms, LU, Newton-Raphson (lines 1–112).
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/.dev_flow/onboard/analysis/domain-core__element-base.md`
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/.dev_flow/onboard/analysis/domain-core__shared-models.md`

---

## 1. Purpose

The `simulator-core` sub-unit implements CircuitJS1's time-domain transient simulator using
**Modified Nodal Analysis (MNA)** with **Newton–Raphson** iteration for nonlinear elements and
adaptive/forced time-step control. It exposes a clean separation between:

- **Engine**: `CircuitSimulator` — matrix builder, solver driver, Newton loop, time-step controller.
- **Shell**: `BaseCirSim` + `CirSim` — application lifecycle, UI bindings, hooks, JS interop.
- **Delegate glue**: `BaseCirSimDelegate` — parent-owner proxy letting non-UI helpers (like
  `CircuitSimulator`) reach global managers via the currently-active `CircuitDocument`.
- **Contract**: `SimulationContextAware` — tiny marker interface for dialogs/helpers that need
  a handle on the `CircuitDocument` (for invoking simulator updates).
- **Numerical helper**: `Diode` — embeddable Shockley + Zener linearization + Newton limiting.

The matrix/solver algebra (`lu_factor`, `lu_solve`, `RowInfo`) and row metadata live in sibling
files (`CircuitMath`, `RowInfo`) that this unit consumes but does not own.

---

## 2. Key Entities Per File

### 2.1 `SimulationContextAware.java`
- Single interface: `void setSimulationContext(CircuitDocument circuitDocument)` (line 8).
- Purpose: dialogs (`EditDialog`) and model editors (`DiodeModel`, `TransistorModel`,
  `CustomLogicModel`) receive the active document so that after editing they can call
  `doc.simulator.*` to re-stamp/re-analyze.

### 2.2 `BaseCirSimDelegate.java`
Abstract convenience base for any helper that is "owned by" the top-level `BaseCirSim` but
scoped to a specific `CircuitDocument`. Fields:
- `final BaseCirSim cirSim` (line 5) — root shell reference.
- `CircuitDocument circuitDocument` (line 7) — nullable override.

Accessors: `getActiveDocument()` (L22, falls back to `cirSim.getActiveDocument()`),
`simulator()` (L29), `renderer()` (L33), `circuitEditor()` (L37), `menuManager()` (L41),
`undoManager()` (L45), `scopeManager()` (L49), `dialogManager()` (L53), `actionManager()`
(L57), `circuitInfo()` (L61).

**Concept**: `CircuitSimulator extends BaseCirSimDelegate` so the simulator reads
UI/document-wide state without holding UI types directly.

### 2.3 `BaseCirSim.java`
Non-UI application shell. Constructs (lines 12–20) the global managers:
`logManager, renderer, clipboardManager, dialogManager, menuManager, displaySettings,
documentManager, loadFileInput, actionManager`. Manages a single `activeDocument`.

Key methods:
- `bindDocument(CircuitDocument)` (L36) — swap active doc, install `updateListener` that
  repaints on every `onSimulationUpdate()` tick (L22–27).
- `needAnalyze()` (L99) — sets `circuitInfo.dcAnalysisFlag = true`; if sim is stopped, runs
  `simulator.analyzeCircuit()` synchronously so post/node markers stay fresh.
- `stop(msg, ce)` (L113) — forwards to `simulator.stop`.
- `setSimRunning(boolean)` (L122) — gated on `stopMessage == null`; transitions doc state,
  forces mouse mode back to "Select" when stopping.
- `resetAction()` (L139) — full reset: clears document error, `simulator.clearStopState()`,
  `simulator.resetSolverState()`, zeroes `t`, `timeStepAccum`, `timeStepCount`, resets every
  element, resets every scope, then `needAnalyze()`. This is the public *Reset* entry (see
  commit `a488ebb`).
- `doDCAnalysis()` (L261) — sets DC flag + `resetAction()`.
- Helpers: `dumpNodelist` (L227) debug dump, `enableDisableMenuItems` (L178) edit-menu gating.

`getIterCount()` (L266) is a stub returning 0; `CirSim` overrides it based on the speed bar.

### 2.4 `CirSim.java`
UI subclass: `public class CirSim extends BaseCirSim implements NativePreviewHandler` (L68).
Responsibility — wire the engine to the browser/GWT DOM: toolbar, menus, canvas, sliders,
controls dialog, touch handlers, JS bridge (`$wnd.CircuitJS1`), window events. **This is the
"app-controller" concept boundary layer**; nothing in this file computes matrix math.

Engine-facing hooks CirSim provides:
- `getIterCount()` (L520) — exponential speed from `speedBar.getValue()`:
  `0.1 * exp((v-61)/24)`. Used by `runCircuit` to gate how many iterations per frame.
- `callAnalyzeHook()` (L1516), `callTimeStepHook()` (L1522), `callUpdateHook()` (L1510),
  `callSVGRenderedHook` (L1528) — JSNI hooks fired by `CircuitSimulator` / renderer
  into `$wnd.CircuitJS1.on*` user callbacks.
- `console(String)` (L513) — static logger, routed to active document's `logBuffer`.
- `resetSimulation()` (L1195) — JS-exposed reset: stops, clears errors, zeroes time,
  resets elements, drops solver state, re-analyzes (mirrors `BaseCirSim.resetAction`).
- `stepSimulation()` (L1225) — JS-exposed single-step: stop, analyze if dirty,
  `preStampAndStampCircuit()` if `needsStamp`, force `lastIterTime = now - 1000`, call
  `runCircuit(true)`.
- `setupJSInterface()` (L1437) — installs `$wnd.CircuitJS1.*` methods: `setSimRunning`,
  `getTime`, `getTimeStep`/`setTimeStep`, `getMaxTimeStep`/`setMaxTimeStep`,
  `resetSimulation`, `stepSimulation`, `getSimInfo`, plus element/scope/log access.

### 2.5 `CircuitSimulator.java`
The engine proper. Extends `BaseCirSimDelegate` (L22). Entry points: `analyzeCircuit`,
`preStampCircuit`, `preStampAndStampCircuit`, `stampCircuit`, `runCircuit`. State:

Time state (L24–38):
- `t` — simulation time in seconds.
- `timeStep` — *current* step (may shrink below `maxTimeStep` for convergence).
- `maxTimeStep` — ceiling; user/setup chosen.
- `minTimeStep` — floor; solver refuses to halve below this.
- `timeStepAccum` — fraction of `maxTimeStep` accrued since last `timeStepCount++`.
- `timeStepCount` — integer count of "user-visible" steps (for scope sampling).
- `adjustTimeStep` (default `true` per constructor L76) — master switch for adaptive halving.
- `minFrameRate = 20` — wall-clock frame budget → `frameTimeLimit = 50ms` in `runCircuit`.

Topology / node state:
- `elmList: ArrayList<CircuitElm>` (L40) — authoritative element list.
- `elmArr: CircuitElm[]` (L43), `scopeElmArr` (L44) — packed arrays, rebuilt after `stampCircuit`.
- `nodeList: ArrayList<CircuitNode>` (L46) — node 0 is always ground.
- `nodeMap: HashMap<Point, NodeMapEntry>` (L49) — coordinate → node; cleared after stamping.
- `wireInfoList: ArrayList<WireInfo>` (L52) — wire-current reconstruction (wires are NOT
  given MNA rows; see §4).
- `postDrawList`, `postVoltageMap`, `badConnectionList` (L54–56) — UI-facing, built in
  `makePostDrawList()`.

Matrix state:
- `voltageSources: CircuitElm[]` (L58) — packed per-VS element slots.
- `circuitMatrix: double[][]` — active, possibly simplified A.
- `origMatrix: double[][]` — snapshot pre-Newton so we can restore between sub-iterations.
- `circuitRightSide: double[]`, `origRightSide: double[]` — same story for B.
- `nodeVoltages: double[]`, `lastNodeVoltages: double[]` — current + fallback.
- `circuitRowInfo: RowInfo[]` — per-row metadata: ROW_CONST vs ROW_NORMAL, `mapRow`/`mapCol`,
  `dropRow`, `lsChanges`, `rsChanges`. Drives the simplification pass.
- `circuitPermute: int[]` — LU pivot vector.
- `circuitMatrixSize` — post-simplify size; `circuitMatrixFullSize` — pre-simplify.
- `circuitNonLinear` — computed during `preStampCircuit`.
- `circuitNeedsMap` — `true` when simplification actually collapsed rows/columns.

Control flags:
- `needsStamp` — set by `analyzeCircuit` / `resetSolverState`.
- `stopMessage`/`stopElm` — hard-stop state; `warningMessage`/`warningElm` — soft non-fatal.
- `nonConvergenceRecoveryEnabled = true` (L979) — educational mode robustness switch.
- `nonConvergencePanicLevel` (0–3), `nonConvergenceStreak`, `nonConvergenceCooldown`,
  `nonConvergenceExtraGmin`, `nonConvergenceNodeShuntR` — non-convergence recovery state
  (commit `fb4ee85`).
- `singularStabilizersActive` — persistent gmin + VS diagonal padding flag.
- `converged: boolean` (L1423) — **drives Newton exit**; set by elements in `doStep`.
- `subIterations: int` (L1424) — current Newton iteration count (read by `Diode`).

### 2.6 `Diode.java`
Embeddable P-N junction model, instantiated inside semi/transistor composites. Not a
`CircuitElm`; it does not own nodes beyond `n0`/`n1` injected via `stamp(int, int)` (L151).

Physical constants:
- `vt = 0.025865 V` (L68) — thermal voltage at 300.15 K.
- `vzcoef = 1/vt` — Zener-curve exponential coefficient.
- `vscale`, `vdcoef = 1/vscale` — forward curve coefficients (from `DiodeModel`).

Per-device from `setup(DiodeModel)` (L36):
- `leakage` — Shockley Is.
- `zvoltage` — Zener breakdown voltage.
- `vcrit = vscale * ln(vscale/(sqrt(2)*Is))` — forward Newton-limiting threshold.
- `vzcrit = vt * ln(vt/(sqrt(2)*Is))` — Zener Newton-limiting threshold.
- `zoffset` — shift so reverse exponential gives 5 mA at `zvoltage`.

Key methods:
- `reset()` (L63) — clear `lastvoltdiff`.
- `stamp(int n0, int n1)` (L151) — record nodes + `simulator.stampNonLinear(n0/n1)` to mark
  both rows as left-side-changing (forces re-stamp each Newton iteration).
- `limitStep(vnew, vold)` (L96) — core Newton limiter. When `|vnew-vold|` exceeds
  `2*vscale` (or `20*vscale` in panic mode), clamp using the tangent-line log trick so diode
  current cannot change by more than e² per iteration. Mirrors SPICE's `pnjlim`. Sets
  `simulator.converged = false` whenever a clamp fires. Handles Zener by translating the
  reverse voltage region through `zoffset`.
- `doStep(voltdiff)` (L158) — the per-iteration stamp:
  1. Convergence check: `|voltdiff - lastvoltdiff| > 0.01` → `converged = false`.
  2. Apply `limitStep` → updated `voltdiff`, cache as `lastvoltdiff`.
  3. Compute **gmin** (conductance-to-ground parallel across junction):
     - Base: `leakage * 0.01`.
     - Honor `simulator.getExtraConvergenceGmin()` (panic bump).
     - After `subIterations > gminStartIter` (100 normal, 10 in panic), ramp gmin
       exponentially toward 0.1 S via `exp(-9*ln(10)*(1 - subIter/gminDenom))`
       (`gminDenom` = 3000 normal, 300 in panic).
  4. Linearize exponential around `voltdiff`:
     - Forward/fwd-zener: `geq = vdcoef*Is*exp(voltdiff*vdcoef) + gmin`,
       `nc = (exp-1)*Is - geq*voltdiff` → `stampConductance(geq)` + `stampCurrentSource(nc)`.
     - Reverse-Zener: composite `I(Vd) = Is*(exp(Vd*vdcoef) - exp((-Vd-Vz)*vzcoef) - 1)`,
       `geq` is the derivative, `nc = I + geq*(-Vd)`.
  5. Arguments to `Math.exp` are clamped to `[-700, 700]` to avoid `Infinity`.
- `calculateCurrent(voltdiff)` (L227) — closed-form Shockley(+Zener) without stamping;
  used by host elements for display/probe readout.

---

## 3. Simulation Lifecycle

The main pipeline, as called from `SimulationLoop` → `CircuitDocument.simulator.runCircuit`:

```
(edit/load) → needAnalyze()
              └─> circuitInfo.dcAnalysisFlag = true
                  └─> analyzeCircuit()
                       ├─ clear stop/warn state
                       ├─ reset nonConvergencePanicLevel = 0
                       ├─ singularStabilizersActive = false
                       ├─ makePostDrawList()
                       └─ needsStamp = true

(per frame)  → runCircuit(didAnalyze)
              ├─ if needsStamp: preStampAndStampCircuit()
              │   ├─ retry preStampCircuit(false) up to 10×
              │   │   ├─ calculateWireClosure()        [L190]
              │   │   ├─ setGroundNode(subcircuit)     [L356]
              │   │   ├─ makeNodeList()                [L417]
              │   │   ├─ calcWireInfo()                [L264]
              │   │   ├─ mark nonlinear, assign VS slots
              │   │   ├─ findUnconnectedNodes()        [L484]
              │   │   ├─ validateCircuit()             [L956]
              │   │   └─ cirSim.callAnalyzeHook()
              │   └─ stampCircuit()
              │       ├─ allocate matrix/B/row-info arrays
              │       ├─ connectUnconnectedNodes()     (1e8 Ω tie to GND)
              │       ├─ stampSingularMatrixStabilizers() if enabled
              │       ├─ stampNonConvergenceStabilizers() if panic>0
              │       ├─ elm.stamp() for every element (try/catch → stop)
              │       ├─ simplifyMatrix(matrixSize)    [row collapse via RowInfo]
              │       └─ if linear: lu_factor() once (reused each frame)
              │
              └─ Frame iteration loop (until stepRate or 50ms budget exhausted):
                   ├─ grow timeStep x2 after 3 "good" iters (if below maxTimeStep)
                   ├─ for each elm: startIteration()
                   ├─ Newton loop (subIter = 0..subIterCount):
                   │    ├─ copy origRightSide → B
                   │    ├─ if nonlinear: copy origMatrix → A
                   │    ├─ for each elm: doStep() (stamps + sets converged flag)
                   │    ├─ if converged && subIter>0 → break
                   │    ├─ if nonlinear: lu_factor(A)
                   │    │    └─ failure → enable stabilizers + restamp, or escalate panic
                   │    ├─ lu_solve(A, permute, B) → applySolvedRightSide → setNodeVoltages
                   │    └─ if linear → break (solve once)
                   ├─ if subIter == subIterCount (failed):
                   │    ├─ halve timeStep + restamp (if above minTimeStep) → retry
                   │    ├─ else escalate nonConvergencePanicLevel (1→2→3)
                   │    └─ at panic=3 & streak≥3: force-advance t using lastNodeVoltages
                   ├─ advance t += timeStep, timeStepAccum += timeStep
                   ├─ elm.stepFinished(), calcWireCurrents()
                   ├─ scopeManager.scopes[i].timeStep(), scopeElm.stepScope()
                   ├─ callTimeStepHook() (JS callback)
                   ├─ snapshot nodeVoltages → lastNodeVoltages
                   └─ exit frame when stepRate or frame budget met
```

---

## 4. Matrix / Node Mechanics

### 4.1 Node assignment (`preStampCircuit`)

Two-pass process driven by coordinates:

1. **Wire closure** (`calculateWireClosure`, L190): iterate elements that are "removable
   wires" (`WireElm`, `LabeledNodeElm`, `GroundElm`). Every pair of posts sharing a removable
   wire is coalesced into one `NodeMapEntry`. `LabeledNodeElm.resetNodeList()` and
   `GroundElm.resetNodeList()` are called first.
2. **Ground node** (`setGroundNode`, L356): every `GroundElm` maps to node 0 (robust for
   disconnected subcircuits, each with its own GND). If no GND and no rails, the first
   `VoltageElm.getPost(0)` becomes ground (unless subcircuit). Otherwise, allocate a new
   placeholder `CircuitNode` in slot 0.
3. **Allocation** (`makeNodeList`, L417): for each element, for each post: if the point is
   not in `nodeMap` or node == -1, create a new `CircuitNode` and update the element's
   local post→node mapping via `setNode(j, n)`. Internal element nodes (e.g., for op-amp or
   transistor internals) are appended after external ones with `cn.internal = true`.
   `vscount` is tallied to pre-size `voltageSources[]`.
4. **Voltage source slot allocation** (L580–589): each nonlinear element flags
   `circuitNonLinear = true`; voltage sources call `ce.setVoltageSource(j, vscount++)`
   assigning a packed VS index that becomes the extra row/column index
   `vn = nodeList.size() + vsIndex`.
5. **Unconnected-node repair** (`findUnconnectedNodes` L484, `connectUnconnectedNodes` L555):
   BFS closure from node 0 over ground-connected posts; any unreachable non-internal node is
   later stamped to ground via `1e8 Ω` resistor to keep the matrix non-singular.
6. **Wires are excluded from the matrix entirely.** Wire currents are reconstructed after
   solving via `calcWireInfo` (L264, topological ordering of wire "readiness") and
   `calcWireCurrents` (L1231, sum neighbor currents at the ready end). Circular wire loops
   trigger `warn("wire loop detected …")` under recovery, else `stop` (L338–346).

### 4.2 Stamping API (`CircuitSimulator` public)

MNA primitives (all go through `sanitizeStampValue` L1043 which clamps to `±1e12` and NaN→0,
setting `converged = false` on out-of-range):

- `stampMatrix(i, j, x)` (L1062) — A[i][j] += x. Uses `circuitRowInfo[].mapRow/mapCol` when
  `circuitNeedsMap` (post-simplification). If column maps to ROW_CONST, fold into B.
- `stampRightSide(i, x)` (L1083) — B[i] += x.
- `stampRightSide(i)` (L1096) — mark row as `rsChanges = true` (right-side updated in doStep).
- `stampNonLinear(i)` (L1103) — mark row as `lsChanges = true` (matrix updated in doStep).
- `stampResistor(n1, n2, r)` (L1146) — classic 4-corner ±1/r stamp. Guards r=0/NaN/Inf by
  clamping conductance and setting `converged = false`.
- `stampConductance(n1, n2, g)` (L1161) — same but with conductance input (used by linearized
  nonlinear elements).
- `stampVoltageSource(n1, n2, vs, v)` (L1118) — extra-row: `[… -1 … +1 …]` for KVL constraint
  in row `vn`, `[… +1 … -1 …]` for current injection columns, `B[vn] = v`.
- `stampVoltageSource(n1, n2, vs)` (L1130) — same but marks B[vn] as dynamic (for sources
  updated via `updateVoltageSource` in doStep).
- `stampVCCS`/`stampVCVS`/`stampCCCS` (L1171/L1111/L1185) — controlled-source stamps.
- `stampCurrentSource(n1, n2, i)` (L1178) — B-only, no matrix touch.

### 4.3 Matrix simplification (`simplifyMatrix`, L755)

Scans each row that has neither `lsChanges` nor `rsChanges` nor `dropRow`. If the row contains
exactly one non-constant non-zero term, solve for that variable directly:
- Mark its column as `type = ROW_CONST` with `value = (B[i] + adjustment) / pivot`.
- Mark the row as `dropRow = true`.
- Restart the outer scan from the first row that referenced the newly-constant column.

After the fixed point, rebuild A and B in reduced size, populating `mapCol`/`mapRow`
back-references so subsequent `stampMatrix` calls still work. `origMatrix`/`origRightSide`
always snapshot the *post*-simplify state for Newton restart.

This optimization is critical for digital and wire-heavy circuits where trivial rows
dominate. A simplification failure with `pivotColumnIndex == -1` triggers "Matrix error" →
`stop()` or `warn()`+panic-stabilizers under recovery.

---

## 5. Newton–Raphson Loop (`runCircuit`, L1442–1754)

### 5.1 Iteration budget

```
subIterCount =
    300                                    if panicLevel > 0          // cheap fail-fast in panic
    100                                    if adjustTimeStep && timeStep/2 > minTimeStep  // try smaller dt first
    5000                                   otherwise                  // last resort
```

Convergence is **per-element** — each `doStep()` compares its new linearization point against
`lastvoltdiff`/last state and sets `simulator.converged = false` when beyond tolerance. For
diodes: `abs(vnew - vold) > 0.01 V` (Diode.java L160); other element tolerances live in their
respective `doStep()` implementations. There is **no global residual norm** — convergence is
the logical AND of every element's own check, gated by `converged && subIter > 0` (L1546).

### 5.2 Per-iteration body

1. Reset `converged = true`, clone `origRightSide → circuitRightSide`.
2. If nonlinear, clone `origMatrix → circuitMatrix` (linear circuits keep LU factorization).
3. Each element's `doStep()` stamps its current linearization (nonlinear) or updates B
   (linear dynamic — capacitors/inductors treated as companion resistor + current source).
4. Check convergence; if `converged && subIter > 0` → exit Newton loop.
5. `CircuitMath.lu_factor(A, n, permute)`. On failure:
   - If stabilizers not yet active → enable `singularStabilizersActive`, restamp, return
     (so next frame recomputes).
   - Else log pivot column/row, call `stampSingularMatrixStabilizers`, refactor; if still
     failing and recovery enabled → escalate panic to 3, `setNodeVoltages(lastNodeVoltages)`,
     restamp, break. Recovery off → `stop("Singular matrix!")`.
6. `CircuitMath.lu_solve(A, n, permute, B)` — in-place Gaussian substitution.
7. `applySolvedRightSide(B)` (L1192) — fan solution back into `nodeVoltages[]` and into each
   VS element's current via `setCurrent`. NaN in any component → `converged = false`.
8. Linear-only fast path: break after one solve (no Newton iteration).

### 5.3 Convergence failure handling (commit `fb4ee85`)

When `subIter == subIterCount`:
- If `adjustTimeStep && timeStep/2 > minTimeStep` and no mid-iter matrix failure: halve
  `timeStep`, restore `lastNodeVoltages`, restamp, retry. Standard SPICE trick.
- Else, with recovery enabled:
  - `nonConvergenceStreak++`, bump panic level (max 3) via `setNonConvergencePanicLevel`
    which sets `nonConvergenceExtraGmin` and `nonConvergenceNodeShuntR`:
    ```
    level 0: gmin=0      , shunt=∞
    level 1: gmin=1e-9   , shunt=1e9
    level 2: gmin=1e-6   , shunt=1e6
    level 3: gmin=1e-3   , shunt=1e3   (strong damping, accuracy degraded)
    ```
  - Clamp timeStep to `[minTimeStep, maxTimeStep]`.
  - At `panicLevel >= 3 && streak >= 3`: **force-advance** time using `lastNodeVoltages`
    (skip element updates/scopes for that step, accept the stale solution). Keeps the sim
    from dying on hard spikes.
  - Otherwise restore `lastNodeVoltages`, restamp, continue.
- Recovery disabled → `stop("Convergence failed! Element: <id>")`.

### 5.4 Cooldown

After a healthy Newton convergence (`subIter < 8`) while panicLevel>0, `nonConvergenceCooldown`
increments; at 30 consecutive calm frames, panic drops by one level (L1694–1706). Good
convergences also feed `goodIterations` (L1687); at 3 consecutive `subIter < 3` frames the
timestep doubles back toward `maxTimeStep` (L1477–1483).

---

## 6. Time-Step Control

Two independent controls layered on top of each other:

### 6.1 Adaptive halving (classical)

Gate: `adjustTimeStep && timeStep/2 > minTimeStep`.
- Shrink: on Newton non-convergence → `timeStep /= 2`; restore voltages; restamp.
- Grow: after 3 consecutive `subIter < 3` frames → `timeStep = min(timeStep*2, maxTimeStep)`;
  restamp.
- Restamp is required because companion-model resistors depend on `timeStep` (IL, IC
  behave differently at each dt).

### 6.2 Frame pacing (wall-clock)

- `stepRate = 160 * cirSim.getIterCount()` (L1451). `getIterCount()` maps the speed bar to
  `0.1 * exp((v-61)/24)` so values [1..100] span ~9 orders of magnitude.
- Early exit (L1463): if `1000 >= stepRate * (tm - lastIterTime)` and no forced analyze,
  skip this frame entirely (slow speeds).
- Frame budget (L1474): `frameTimeLimit = 1000/minFrameRate = 50ms`. Inner loop exits when
  either: (a) enough `timeStepCount` increments have occurred for the requested rate, or
  (b) wall time since frame start exceeds 50 ms.

### 6.3 Fixed-step hook

Users can set `adjustTimeStep = false` (via JS `setTimeStep`, UI, or loaded setup). Halving
is then suppressed, and `subIterCount` jumps to 5000. `minTimeStep` and `maxTimeStep` are
user-tunable via `CirSim.setTimeStep`/`setMaxTimeStep` and the speed scrollbar.

---

## 7. Circuit Analysis Phases (summary table)

| Phase | Function | Outputs |
|---|---|---|
| 1. Topology | `calculateWireClosure` (L190) | `nodeMap` with wire-merged `NodeMapEntry` objects, `wireInfoList` |
| 2. Ground pick | `setGroundNode` (L356) | node 0 bound to GND/VS |
| 3. Node assignment | `makeNodeList` (L417) | `nodeList`, element post→node wiring, `vscount` |
| 4. Wire ordering | `calcWireInfo` (L264) | `WireInfo.post`/`.neighbors` for current reconstruction |
| 5. Linearity + VS slots | `preStampCircuit` L580 | `circuitNonLinear`, `voltageSources[]`, `voltageSourceCount` |
| 6. Connectivity repair | `findUnconnectedNodes`/`connectUnconnectedNodes` | `unconnectedNodes` → 1e8 Ω to GND |
| 7. Validation | `validateCircuit` / `FindPathInfo.validateElement` | returns false → stopElm |
| 8. Matrix alloc | `stampCircuit` L658 | `A`, `B`, `circuitRowInfo[]`, `circuitPermute[]` |
| 9. Stabilizer stamps | `stampSingularMatrixStabilizers` (L1765), `stampNonConvergenceStabilizers` (L1789) | gmin-to-GND + VS diagonal |
| 10. Element stamping | `ce.stamp()` loop L687 | populated A/B (linear parts) |
| 11. Simplification | `simplifyMatrix` (L755) | reduced A/B, `origMatrix`/`origRightSide` snapshots, `circuitNeedsMap` |
| 12. LU prefactor | `CircuitMath.lu_factor` (L711) | factored `A` for linear circuits |
| 13. Newton sub-iteration | `runCircuit` inner loop | converged `nodeVoltages`, VS currents |

---

## 8. Public Contracts

### 8.1 `CircuitSimulator` (engine-facing)

External callers (elements, dialogs, JS bridge, UI):

- **Stamping**: `stampMatrix`, `stampRightSide(i,x)`, `stampRightSide(i)`, `stampNonLinear(i)`,
  `stampResistor`, `stampConductance`, `stampVoltageSource` (2 overloads),
  `updateVoltageSource`, `stampVCVS`, `stampVCCurrentSource`, `stampCCCS`,
  `stampCurrentSource`.
- **Convergence signals**: `converged` (bool, write from elements), `subIterations` (int,
  read by elements for gmin ramp), `getConvergencePanicLevel()`, `getExtraConvergenceGmin()`.
- **Control**: `stop(msg, ce)`, `warn(msg, ce)`, `clearStopState()`, `resetSolverState()`,
  `analyzeCircuit()`, `runCircuit(boolean didAnalyze)`, `preStampAndStampCircuit()`,
  `updateModels()`.
- **Queries**: `getElm(i)`, `locateElm(elm)`, `getNodeVoltages(n)`, `getCircuitNode(n)`,
  `t`, `timeStep`, `maxTimeStep`, `minTimeStep`, `timeStepCount`, `timeStepAccum`,
  `adjustTimeStep`, `elmList`.

### 8.2 `BaseCirSim` (shell-facing)

- `needAnalyze()`, `stop()`, `stop(msg, ce)`, `setSimRunning(boolean)`, `simIsRunning()`,
  `resetAction()`, `doDCAnalysis()`, `getActiveDocument()`, `log(text)`, `console(...)`
  static, `setCanvasSize(w,h)`, `getIterCount()`.

### 8.3 `CirSim` (JS-facing, subset of `$wnd.CircuitJS1`)

- `setSimRunning`, `isRunning`, `getTime`, `getTimeStep`/`setTimeStep`,
  `getMaxTimeStep`/`setMaxTimeStep`, `resetSimulation`, `stepSimulation`, `getSimInfo`,
  `getNodeVoltage(name)`, `setExtVoltage(name, v)`, plus element/scope/log/circuit IO.
- Hooks: `$wnd.CircuitJS1.onupdate`, `onanalyze`, `ontimestep`, `onsvgrendered`,
  `oncircuitjsloaded`.

### 8.4 `SimulationContextAware`

Implemented by: `DiodeModel`, `TransistorModel`, `CustomLogicModel`, `dialog/EditDialog`
(grep at repo root). Called by dialog/model-editor plumbing to inject the live
`CircuitDocument` after editing, so the implementer can `doc.simulator.updateModels()` /
`doc.cirSim.needAnalyze()` / similar to push changes into the matrix.

### 8.5 `Diode`

`public Diode(CircuitSimulator)`, `setSimulator`, `setup(DiodeModel)`,
`setupForDefaultModel()`, `reset()`, `stamp(n0, n1)`, `doStep(voltdiff)`,
`calculateCurrent(voltdiff)`, `limitStep(vnew, vold)`.

Clients: `DiodeElm`, any transistor/LED/varactor/etc. that embeds a junction. Host element
is responsible for: computing `voltdiff = node[n0].v - node[n1].v` each iteration,
handling external series resistance (saturation current `leakage` is the true Is;
`DiodeElm` wraps series R around this), and carrying the `Diode` through `reset/startIteration`.

---

## 9. Integration Points

- **Element stamping**: every `CircuitElm` implementation declares `nonLinear()`,
  `getVoltageSourceCount()`, `getInternalNodeCount()`, `stamp()`, `startIteration()`,
  `doStep()`, `stepFinished()`. See `domain-core__element-base.md` for the base class.
- **Shared models**: `DiodeModel` (fed into `Diode.setup`), `TransistorModel`,
  `CustomLogicModel`, `CustomCompositeModel` — persistent parameter bundles described in
  `domain-core__shared-models.md`. All three simulation-aware ones implement
  `SimulationContextAware`.
- **Solver math**: `CircuitMath.lu_factor`/`lu_solve`/`getLastLuFailRow`/`getLastLuFailColumn`/
  `getLastLuFailPivotAbs` — pivot telemetry used for singular-matrix diagnostics at
  `CircuitSimulator.java:1579–1588`.
- **Scopes**: `ScopeManager` is called from inside the Newton-done section of `runCircuit`
  (L1720) so scope sampling never happens mid-iteration.
- **Rendering**: `BaseCirSim.updateListener` (L22) repaints on every
  `CircuitDocument.notifyUpdate()`; `SimulationLoop` drives both `runCircuit` and `notifyUpdate`.
- **JS bridge**: `CirSim.setupJSInterface()` (L1437) wires engine methods into
  `$wnd.CircuitJS1` so hosting web pages can drive simulation programmatically.

---

## 10. Issues and Cross-References to Recent Commits

### 10.1 `fb4ee85` — non-convergence recovery (`CircuitSimulator` L974–1015, L1493–1706; `Diode` L99–103, L168–186)

The `nonConvergenceRecoveryEnabled` / `nonConvergencePanicLevel` machinery turns what used
to be a hard `stop("Convergence failed!")` into a progressive escalation:
1. Shrink `timeStep` (classical).
2. Enable node-to-ground shunts (`1e9..1e3 Ω`) and VS-diagonal padding.
3. Inject extra gmin (`1e-9..1e-3 S`) into every P-N junction.
4. Loosen `Diode.limitStep` ceilings from `2*vscale` to `20*vscale`.
5. As last resort, force-advance `t` using `lastNodeVoltages`.

**Trade-off**: at `panicLevel == 3` the sim is no longer physically accurate (strong node
shunts attenuate signals). The cooldown (L1694) restores fidelity once calm returns. Users
who want hard failures (debugging device models) can set
`nonConvergenceRecoveryEnabled = false`. There is **no public setter** exposed for this
flag — users currently have no UI to opt out. *Issue*: expose this via JS bridge / menu.

Also note `Diode.doStep` uses `simulator.subIterations` (packed back from Newton loop L1515)
to ramp gmin exponentially — a SPICE-style technique, but the ramp denominator drops from
3000 to 300 in panic mode, meaning gmin saturates to 0.1 S much faster. This can cause
visible waveform distortion at panic level 2+.

### 10.2 `a488ebb` — reset enhancements (`BaseCirSim.resetAction` L139–163;
`CircuitSimulator.resetSolverState` L119–141, `clearStopState` L108–113; `CirSim.resetSimulation` L1195)

`resetSolverState()` nulls every solver array (`circuitMatrix`, `origMatrix`, `B`,
`lastNodeVoltages`, etc.) plus `circuitNonLinear`, `voltageSourceCount`,
`circuitMatrixSize`, `circuitMatrixFullSize`, `circuitNeedsMap`, `singularStabilizersActive`.
It sets `needsStamp = true` so the next `runCircuit` call performs a full
`preStampAndStampCircuit` cycle. Pairs with `clearStopState()` which wipes stop/warn msgs.

`resetAction` now performs reset in this order: set analysis flag via `needsAnalysis()` on
renderer → clear document error → clear stop state → drop solver state → zero time → reset
elements → reset scopes → `needAnalyze()`. This fixes a previous regression where resetting
after a convergence stop could leave the UI blocked because residual `stopMessage` prevented
`setSimRunning(true)` (L124).

**Observed issue** (not yet addressed): `CirSim.resetSimulation()` duplicates
`BaseCirSim.resetAction()` with minor differences (the former zeros `lastIterTime` but not
renderer, the latter doesn't zero `lastIterTime` but does reset scopes via
`scopeManager.scopes[i].resetGraph(true)`). They should be consolidated; drift risks
divergent reset semantics between the JS API and the UI Reset button.

### 10.3 Other concerns

- `getIterCount()` stub returns 0 in `BaseCirSim` (L266) but is overridden in `CirSim` (L520).
  Non-UI unit tests or headless usages of `BaseCirSim` will therefore get `stepRate = 0` and
  the early-exit at L1463 will fire every frame (simulation silently frozen). Tests should
  subclass or inject a fake iter count.
- `Diode.simulator` is not final and has both a constructor injection and a `setSimulator`
  setter (L32). This means the same `Diode` instance could be mutated to point at a
  different simulator mid-flight — useful for multi-document scenarios but dangerous if
  `doStep` runs while the reference is being swapped (not thread-safe; GWT is single-threaded
  so currently moot).
- `sanitizeStampValue` silently truncates to `±1e12` and sets `converged = false`, so a buggy
  element can produce a "successful" solve that is actually clamped noise. There is no
  counter or telemetry for how often clamping fires per frame.
- `Math.exp` clamp in `Diode.doStep` is `[-700, 700]` — near IEEE-754 overflow boundaries.
  Benign, but if `vdcoef` or `vzcoef` ever change per model the clamp won't adapt.

---

## 11. Concept Boundaries ("simulator-core" vs "app-controller")

The task hypothesized two concepts: engine vs UI shell. The code supports that split:

| Concept | Files | Responsibility |
|---|---|---|
| **simulator-core** (engine) | `CircuitSimulator.java`, `Diode.java`, `BaseCirSimDelegate.java`, `SimulationContextAware.java`, plus siblings `CircuitMath`, `RowInfo`, `CircuitNode`, `NodeMapEntry`, `WireInfo` | MNA stamping, Newton loop, LU, topology, time-step control. Zero UI, zero JSNI. Pure Java. |
| **app-controller** (UI shell) | `CirSim.java`, `BaseCirSim.java` | GWT widgets, canvas, toolbar, menus, JSNI bridge, document lifecycle, hooks. Drives the engine but owns no matrix math. |

Note that `BaseCirSim` still leaks some analysis-aware logic (`needAnalyze`, `doDCAnalysis`,
`resetAction`) — these are *lifecycle/orchestration* methods and do belong to the shell,
but they reach deep into `simulator.*` fields directly (`simulator.t`, `simulator.timeStepAccum`,
`simulator.timeStepCount`). A stricter separation would move those into
`CircuitSimulator.reset()` and have the shell just call it. This is a minor architectural
smell rather than a bug.

`BaseCirSimDelegate` is the glue that makes the split possible — `CircuitSimulator`
accesses UI managers through delegate methods (`scopeManager()`, `circuitInfo()`,
`circuitEditor()`), keeping the engine file free of direct `Toolbar`/`MenuManager` imports.
`SimulationContextAware` is the analogous outbound contract: dialogs/models that need to
talk back to the engine get a `CircuitDocument` handle rather than a naked `CircuitSimulator`,
so they stay document-scoped.

---

## 12. Open Questions / Future Work

- Consolidate `CirSim.resetSimulation` and `BaseCirSim.resetAction` into a single canonical
  reset path (see §10.2).
- Expose `nonConvergenceRecoveryEnabled` to users (menu/JS setter) so accuracy-critical work
  can opt out of educational mode.
- Add per-frame telemetry for `sanitizeStampValue` clamps and `Diode.limitStep` activations
  so users see when the solver is "cheating".
- Extract a narrower `SolverContext` interface from `CircuitSimulator` for `Diode` and other
  helpers (currently they hold a full `CircuitSimulator` reference, giving them access to
  `elmList` and other private-by-convention fields).
- Consider moving solver state ownership out of `CircuitSimulator` into a `SolverState`
  value-type so `resetSolverState()` is a single assignment rather than 11 separate nullings.
