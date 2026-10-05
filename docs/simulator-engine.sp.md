# Simulator Engine — Specification  {#SP_SIM}

> **Code:** SP_SIM
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-10-05
>
> **Concept:** [C_SIM](./simulator-engine.concept.md)
> **Depends on specs:** [SP_ELB](./element-base.sp.md), [SP_UTL](./util-locale-log.sp.md), [SP_MDS](./math-dsp.sp.md), [SP_SHM](./shared-models.sp.md)
> **Used by specs:** [SP_SLV](./linear-solver.sp.md)
> **Plan:** [simulator-engine.plan.md](./simulator-engine.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/layer3__simulator-core.md`.
> Defines the MNA solver engine contracts, Newton-loop invariants,
> stamping API, adaptive-dt and panic-recovery semantics.

## 01. Data Structures  {#SP_SIM_01}

> Implements: [C_SIM_02](./simulator-engine.concept.md#C_SIM_02)

### 01_01. CircuitSimulator state  {#SP_SIM_01_01}

Time fields:
| Field | Type | Default | Description |
|-------|------|---------|-------------|
| t | double | 0 | simulation time (s) |
| timeStep | double | maxTimeStep | current step (may shrink) |
| maxTimeStep | double | setup | ceiling |
| minTimeStep | double | setup | floor; halving refuses below this |
| timeStepAccum | double | 0 | fraction accrued toward `timeStepCount++` |
| timeStepCount | int | 0 | user-visible step count (scope sampling) |
| adjustTimeStep | bool | true | master switch for adaptive halving |
| minFrameRate | int | 20 | → `frameTimeLimit = 50 ms` |

Topology:
| Field | Type | Description |
|-------|------|-------------|
| elmList | ArrayList<CircuitElm> | authoritative list |
| elmArr / scopeElmArr | CircuitElm[] | packed snapshots rebuilt post-stamp |
| nodeList | ArrayList<CircuitNode> | node 0 = ground |
| nodeMap | HashMap<Point,NodeMapEntry> | cleared after stamping |
| wireInfoList | ArrayList<WireInfo> | wires excluded from matrix |
| voltageSources | CircuitElm[] | packed per-VS slots |

Matrix:
| Field | Type | Description |
|-------|------|-------------|
| circuitMatrix / origMatrix | double[][] | active A / pre-Newton snapshot |
| circuitRightSide / origRightSide | double[] | B / snapshot |
| nodeVoltages / lastNodeVoltages | double[] | current / fallback |
| circuitRowInfo | RowInfo[] | per-row metadata |
| circuitPermute | int[] | LU pivot vector |
| circuitMatrixSize / FullSize | int | post / pre simplify |
| circuitNonLinear | bool | any element nonlinear |
| circuitNeedsMap | bool | simplification collapsed rows |

Convergence:
| Field | Type | Default | Description |
|-------|------|---------|-------------|
| converged | bool | true | reset at Newton-iter start; elements write false |
| subIterations | int | 0 | current Newton iter (elements read) |
| nonConvergenceRecoveryEnabled | bool | true | educational-mode toggle |
| nonConvergencePanicLevel | int (0-3) | 0 | escalator |
| nonConvergenceStreak | int | 0 | consecutive failed frames |
| nonConvergenceCooldown | int | 0 | consecutive calm frames (decays panic at 30) |
| nonConvergenceExtraGmin | double | 0 | extra gmin at junctions |
| nonConvergenceNodeShuntR | double | ∞ | node-to-GND shunt |
| singularStabilizersActive | bool | false | persistent gmin + VS diag padding |

Stop state: `stopMessage: String`, `stopElm: CircuitElm`,
`warningMessage`, `warningElm`.

Invariants:
- `nodeList.get(0)` is always ground.
- `circuitMatrix` is square of size `circuitMatrixSize`.
- After `resetSolverState()` all solver arrays are null and `needsStamp=true`.
- `sanitizeStampValue` clamps to `±1e12`, maps NaN→0, sets `converged=false` on out-of-range.

### 01_02. Diode state  {#SP_SIM_01_02}

| Field | Type | Description |
|-------|------|-------------|
| simulator | CircuitSimulator | back-ref (mutable via setter) |
| n0, n1 | int | node indices from `stamp(n0,n1)` |
| leakage | double | Shockley Is |
| zvoltage | double | Zener breakdown |
| vscale, vdcoef | double | forward coef = 1/vscale |
| vt, vzcoef | double | 0.025865 V, 1/vt |
| vcrit, vzcrit | double | Newton-limit thresholds |
| zoffset | double | reverse-region shift |
| lastvoltdiff | double | last linearization point |

### 01_03. SimulationContextAware  {#SP_SIM_01_03}

Single method `setSimulationContext(CircuitDocument)`. Implementers:
`DiodeModel`, `TransistorModel`, `CustomLogicModel`, `EditDialog`.

## 02. Contracts  {#SP_SIM_02}

### 02_01. analyzeCircuit  {#SP_SIM_02_01}

Purpose: rebuild netlist topology and flag for re-stamp.

Processing:
    FUNCTION analyzeCircuit():
        clearStopState(); clearWarnState()
        nonConvergencePanicLevel = 0; streak = 0; cooldown = 0
        singularStabilizersActive = false
        makePostDrawList()
        needsStamp = true

### 02_02. preStampAndStampCircuit  {#SP_SIM_02_02}

Purpose: build matrix skeleton + populate linear stamps + LU-factor.

Processing:
    IF the node allocation of this analysis was made by analyseNodes() and not used yet: skip it
    ELSE retry up to 10 times: preStampCircuit(false)
    stampCircuit()

`analyseNodes()` (2026-10-05, PL_AGA backlog "importCircuit scales") runs the same
retried `preStampCircuit(false)` without `stampCircuit`, marks the allocation for the
next `preStampAndStampCircuit`, and calls every element's `applyStampedValues()` (the
fields a stamp shows in the drawing: current source current, potentiometer
resistances). `CircuitDocument.ensureNodesAnalysed()` uses it for the agent's
connectivity, records and mutations; the stamp (dense matrix, O(m³) LU for a linear
circuit) is left to the next run, reading or frame. `findUnconnectedNodes` is one
breadth-first pass over the element connection graph (seeds in ascending node order,
same groups as the former fixpoint passes).

`preStampCircuit` order: wire closure → ground pick → makeNodeList →
calcWireInfo → nonlinear detect / VS slot alloc → findUnconnectedNodes
→ validateCircuit → callAnalyzeHook.

`stampCircuit` order: allocate A/B/RowInfo → connectUnconnectedNodes
(1e8 Ω to GND) → stampSingularMatrixStabilizers if enabled →
stampNonConvergenceStabilizers if panic>0 → ce.stamp() loop (try/catch →
stop) → simplifyMatrix → if linear: lu_factor once.

Errors:
| Code | Condition | Guidance |
|------|-----------|----------|
| WIRE_LOOP | circular wire chain | warn (recovery) or stop |
| MATRIX_ERROR | `simplifyMatrix` pivot-not-found | stop or enable stabilizers |
| SINGULAR | `lu_factor` fail | enable stabilizers → restamp; escalate panic 3 on persistent fail |

### 02_03. runCircuit(didAnalyze)  {#SP_SIM_02_03}

Purpose: advance simulation by one frame (multiple timesteps within
50 ms budget, gated by `stepRate`).

Processing (abbreviated):
    stepRate = 160 * cirSim.getIterCount()
    if 1000 >= stepRate * (now - lastIterTime) and not didAnalyze: return
    frameStart = now; frameBudget = 50 ms
    WHILE not done:
        if goodIterStreak >= 3: timeStep = min(timeStep*2, maxTimeStep); restamp
        ce.startIteration() for all
        subIterCount = 300 if panic>0 else 100 if canHalveDt else 5000
        FOR subIter in 0..subIterCount:
            subIterations = subIter
            converged = true
            B = origRightSide.copy()
            if nonlinear: A = origMatrix.copy()
            ce.doStep() for all   // may set converged=false
            if converged and subIter > 0: break
            if nonlinear: lu_factor(A) else skip
            lu_solve(A, permute, B); applySolvedRightSide(B)
            if linear: break
        if subIter == subIterCount:       // Newton failed
            if adjustTimeStep and timeStep/2 > minTimeStep:
                timeStep /= 2; nodeVoltages = lastNodeVoltages; restamp; retry
            else if recoveryEnabled:
                streak++; panic = min(3, panic+1)
                if panic >= 3 and streak >= 3:
                    force-advance t; skip element updates; continue
                nodeVoltages = lastNodeVoltages; restamp; continue
            else: stop("Convergence failed!", firstOffender); return
        // successful Newton
        if subIter < 3: goodIterStreak++ else goodIterStreak = 0
        if subIter < 8 and panic>0: cooldown++; if cooldown>=30: panic--; cooldown=0
        t += timeStep; timeStepAccum += timeStep
        if timeStepAccum >= maxTimeStep: timeStepCount++; timeStepAccum -= maxTimeStep
        ce.stepFinished(); calcWireCurrents()
        scopeManager.timeStep(); callTimeStepHook()
        lastNodeVoltages = nodeVoltages.copy()
        if rate-met or frame-budget-exceeded: break

### 02_04. Stamping primitives  {#SP_SIM_02_04}

All primitives route through `sanitizeStampValue(x)`:

- `stampMatrix(i,j,x)`, `stampRightSide(i,x)`, `stampRightSide(i)`,
  `stampNonLinear(i)`.
- `stampResistor(n1,n2,r)`, `stampConductance(n1,n2,g)`.
- `stampVoltageSource(n1,n2,vs,v)`, `stampVoltageSource(n1,n2,vs)`,
  `updateVoltageSource(n1,n2,vs,v)`.
- `stampVCVS(n1,n2,coef,vs)`, `stampVCCS(n1,n2,nc1,nc2,g)` a.k.a.
  `stampVCCurrentSource`.
- `stampCCCS(...)`, `stampCCVS(...)`.
- `stampCurrentSource(n1,n2,i)`.

### 02_05. Reset  {#SP_SIM_02_05}

`resetSolverState()`: null `circuitMatrix`, `origMatrix`, B arrays,
`lastNodeVoltages`, `circuitRowInfo`, `voltageSources`, etc.; clear
`circuitNonLinear`, `voltageSourceCount`, `circuitMatrixSize/FullSize`,
`circuitNeedsMap`, `singularStabilizersActive`; set `needsStamp=true`.

`clearStopState()`: null `stopMessage`, `stopElm`, `warningMessage`, `warningElm`.

`BaseCirSim.resetAction()`: renderer needsAnalysis → clear doc error →
`clearStopState` → `resetSolverState` → t=timeStepAccum=timeStepCount=0
→ elm.reset() for all → scope.resetGraph() → `needAnalyze()`.

### 02_06. Diode.doStep(voltdiff)  {#SP_SIM_02_06}

Processing:
    IF |voltdiff - lastvoltdiff| > 0.01: simulator.converged = false
    voltdiff = limitStep(voltdiff, lastvoltdiff)   // SPICE pnjlim
    lastvoltdiff = voltdiff
    gmin = leakage * 0.01 + simulator.getExtraConvergenceGmin()
    IF simulator.subIterations > gminStartIter:
        gmin += exp(-9*ln(10) * (1 - subIter/gminDenom))  // ramp to 0.1 S
    IF voltdiff >= -zvoltage + zoffset:   // forward / fwd-zener
        geq = vdcoef*leakage*exp(voltdiff*vdcoef) + gmin
        nc = (exp(voltdiff*vdcoef) - 1)*leakage - geq*voltdiff
    ELSE:                                  // reverse-zener
        // composite Shockley + Zener; geq = derivative
    simulator.stampConductance(n0, n1, geq)
    simulator.stampCurrentSource(n0, n1, nc)

`gminStartIter` = 100 normal / 10 panic; `gminDenom` = 3000 / 300.
`Math.exp` arguments clamped to `[−700, 700]`.

## 03. Validation Rules  {#SP_SIM_03}

- Elements may not stamp after `stop` has been called.
- `sanitizeStampValue` must be used for every scalar entering the matrix.
- Newton loop must not compare `converged` before subIter 1
  (`converged && subIter > 0`).
- `resetSolverState` must leave `needsStamp=true`.

## 04. State Transitions  {#SP_SIM_04}

Simulator lifecycle:

    [uninit] --ctor--> [idle]
    [idle]   --analyzeCircuit--> [needsStamp]
    [needsStamp] --preStampAndStampCircuit--> [ready]
    [ready]  --runCircuit--> [ready] (normal frame)
    [ready]  --stop--> [stopped]
    [stopped] --clearStopState+resetSolverState--> [needsStamp]

Panic escalator: 0 → 1 → 2 → 3 on convergence fail;
decrements by 1 after 30 calm frames.

## 05. Verification Criteria  {#SP_SIM_05}

### 05_01. Functional Expectations  {#SP_SIM_05_01}

| Contract | Scenario | Expected |
|----------|----------|----------|
| stampResistor | r=1kΩ | ±1e-3 at four corners |
| analyzeCircuit | resistor + VS | nodeList size = 2, voltageSourceCount=1 |
| runCircuit | linear RC | exponential decay to within 0.1% of analytic |
| Diode.doStep | forward bias 0.7 V | converges in <10 Newton iters |
| panic escalation | pathological osc | reaches panic 3, keeps running |

### 05_02. Invariant Checks  {#SP_SIM_05_02}

| Invariant | Verification |
|-----------|-------------|
| node 0 ground | assert first entry in nodeList |
| matrix square | circuitMatrix[i].length == circuitMatrix.length |
| clamp sets converged=false | stamp NaN → converged must be false |
| reset→needsStamp | after resetSolverState: needsStamp must be true |

### 05_03. Integration Scenarios  {#SP_SIM_05_03}

| Scenario | Steps | Expected |
|----------|-------|----------|
| Diode full-wave rectifier | load, run 100 ms | no stops, waveform rectified |
| Singular matrix (floating node) | unconnected R, run | stabilizers engage, not stopped |
| Reset after convergence stop | load bad ckt, stop, resetAction | setSimRunning(true) succeeds |

### 05_04. Edge Cases  {#SP_SIM_05_04}

| Case | Expected behaviour |
|------|--------------------|
| NaN stamp | clamp to 0, converged=false |
| Stamp >1e12 | clamp, converged=false |
| Wire loop | warn (recovery) / stop (strict) |
| All elements converge subIter=0 | break after first solve (linear) |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
| 2026-10-05 | §02_02: `analyseNodes` (node allocation without the stamp), linear `findUnconnectedNodes` (PL_AGA backlog "importCircuit scales"). |
