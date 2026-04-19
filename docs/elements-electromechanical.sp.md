# Electromechanical Elements — Specification  {#SP_EEM}

> **Code:** SP_EEM
> **Status:** draft
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_EEM](./elements-electromechanical.concept.md)
> **Depends on specs:** [SP_ELB](./element-base.sp.md), [SP_UTL](./util-locale-log.sp.md), [SP_GEO](./geometry.sp.md), [SP_RND](./rendering-primitives.sp.md), [SP_EPS](./elements-passives.sp.md)
> **Used by specs:** circuit element factory, simulator, cat-switches (cross-ref)
> **Plan:** [elements-electromechanical.plan.md](./elements-electromechanical.plan.md)
>
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-electromechanical.md](../.dev_flow/onboard/analysis/domain-core__cat-electromechanical.md)
>
> Catalog of 6 elements: DC motor, 3-phase motor, integrated relay, split
> coil+contact pair, time-delay relay. Shared companion-inductor physics
> and label-based coil→contact coupling.

## 01. Element Catalog  {#SP_EEM_01}

> Implements: [C_EEM_02](./elements-electromechanical.concept.md#C_EEM_02)

### 01_01. Element Table  {#SP_EEM_01_01}

| Element | Extends | Posts | Int.nodes | V-src | Electrical state | Mechanical state | Dump |
|---|---|---|---|---|---|---|---|
| `DCMotorElm` | `CircuitElm` | 2 | 4 | 2 | `ind.current`, L, R | angle, speed, coilCurrent, inertiaCurrent, J, b, K, Kb, gearRatio | 415 |
| `ThreePhaseMotorElm` | `CircuitElm` | 6 | 7 | 2 | coilCurrents[5], xformMatrix[5][5] | angle, speed, filteredSpeed, J, b | 427 |
| `RelayElm` (integrated) | `CircuitElm` | `2 + 3·poleCount` | 1 | 0 | coilCurrent, ind, coilR; per-pole switchCurrent | d_position ∈ [0,1], i_position ∈ {0,1,2}, onState, switchingTime | 178 |
| `RelayCoilElm` | `CircuitElm` | 2 | 1 | 0 | coilCurrent, avgCurrent, ind, coilR | state ∈ {0..3}, switchPosition, lastTransition, type | 425 |
| `RelayContactElm` | `CircuitElm` | 2 | 0 | 0 | switchCurrent via r_on/r_off | i_position via setPosition | 426 |
| `TimeDelayRelayElm` | `ChipElm` | 4 (Vin, gnd, in, out) | 0 | 0 | onResistance/offResistance | poweredState, onState, lastTransition | 414 |

Invariants:
- All relay/motor coils use `Inductor.FLAG_BACK_EULER`.
- `nonLinear()==true` for RelayElm, RelayContactElm, TimeDelayRelayElm
  (state-switched resistor each Newton iter).
- `RelayElm` pole count clamped to `[1, 4]`.
- `ThreePhaseMotorElm` pole pairs `Zp=2` hardcoded.
- Motor `reset()` clears coil currents but NOT angle/speed (intentional).

### 01_02. Physics Quick Reference  {#SP_EEM_01_02}

| Quantity | DC motor | 3-phase motor |
|---|---|---|
| Back-EMF | `Kb·ω` (VS) | `vs1,vs2 = f(ω, Lm, Lr, i)` (VS) |
| Torque | `K·i_armature` (VS → indInertia) | `Zp·√3/2·Lm·(...)` integrated in code |
| Inertia `J` | Companion L indInertia | Manual Euler `ω += Δt(τ-bω)/J` |
| Friction `b` | Stamped R to ground | Subtracted in Euler |
| Angle | `θ += ω·Δt` startIteration | `θ += ω·Δt` startIteration |
| Flip allowed | Yes | `canFlipX/Y == false` |

### 01_03. Relay Coil FSM  {#SP_EEM_01_03}

`RelayCoilElm.state ∈ {0=wait-on, 1=turning-on, 2=on, 3=turning-off}`.

- `avgCurrent = exp(-Δt·1000)·prev + (1-exp)·|coilCurrent|` (≈1 kHz RC).
- Transitions: see Concept §3.1.
- 4 type modes: NORMAL (symmetric), ON_DELAY, OFF_DELAY, LATCHING.

### 01_04. Timing Comparison  {#SP_EEM_01_04}

| Property | TimeDelayRelayElm | RelayCoilElm (ON/OFF_DELAY) |
|---|---|---|
| Coil model | 10 kΩ sense R (no inductance) | Full L/R coil with Inductor |
| Delay trigger | V threshold 2.5 V | Current threshold (onCurrent/offCurrent, 20/15 mA) |
| Smoothing | None | 1 kHz exp avg |
| Output | Single 2-terminal R | Arbitrary N contacts by label |
| Base class | `ChipElm` | `CircuitElm` |

## 02. Contracts  {#SP_EEM_02}

### 02_01. Label Broadcast (RelayCoilElm → RelayContactElm)  {#SP_EEM_02_01}

    FUNCTION setSwitchPositions(self):
        FOR each elm in elmList:
            IF elm instanceof RelayContactElm AND elm.label == self.label:
                elm.setPosition(1 - self.switchPosition, self.type)

Invoked: (a) in `stamp()` for initial sync; (b) in `startIteration`
whenever `oldSwitchPosition != switchPosition`.

NC/NO inversion happens **at the contact** (`1 - i_position` if
`FLAG_NORMALLY_CLOSED`). The same label may drive a mix of NO and NC
contacts.

### 02_02. DC Motor doStep  {#SP_EEM_02_02}

    updateVoltageSource(node4→gnd, voltSources[1], coilCurrent * K)   # torque
    updateVoltageSource(node3→node1, voltSources[0], inertiaCurrent * Kb)  # back-EMF
    ind.doStep(V_armature); indInertia.doStep(V_inertia)

### 02_03. 3-phase Motor stamp (once per analysis)  {#SP_EEM_02_03}

    build 5x5 xformMatrix; CircuitMath.invertMatrix(xformMatrix, 5)
    multiply by ts = Δt
    stampConductance for each diagonal
    stampVCCurrentSource for each off-diagonal
    stampResistor Rs on 3 stator + 1.5·Rr on 2 rotor bars
    stampVoltageSource for vs1, vs2 (rotor EMF placeholders)

### 02_04. TimeDelayRelay stepFinished  {#SP_EEM_02_04}

    new_powered = (V_Vin - V_gnd) > 2.5
    IF new_powered != poweredState:
        lastTransition = t; poweredState = new_powered
    IF t > lastTransition + (poweredState ? onDelay : offDelay):
        onState = poweredState

## 03. Validation Rules  {#SP_EEM_03}

### 03_01. Input Validation  {#SP_EEM_03_01}

- `DCMotorElm.setEditValue`: every parameter `> 0` (inductance, J, b, K,
  Kb, resistance); `inductance`/`J` change re-creates companion Inductor
  with current continuity.
- `ThreePhaseMotorElm.setEditValue`: coupling coefficient clamped `(0,1)`.
- `RelayElm`: `poleCount ∈ [1, 4]`; JSON import clamps. `reset()` preserves
  `onState` deliberately (comment: "Flip-Flop would be left in a weird
  state otherwise"). Similar preservation in `RelayCoilElm.reset` and
  `RelayContactElm.reset`.
- `RelayElm` "Use New Model" migration button sets `switchingTime =
  5e-3` when `switchingTime == 0`.
- `TimeDelayRelayElm.setChipEditValue`: `onResistance > 0`,
  `offResistance > 0`; `onDelay`/`offDelay` NOT guarded → negative
  values fire immediately.
- Label de-duplication: **none** — two coils sharing label compete.
- `RelayContactElm.applyJsonProperties`: accepts Boolean only for
  `normally_closed`/`iec_symbol`; RelayElm uses tolerant `getJsonBoolean`.

## 04. State Transitions  {#SP_EEM_04}

### 04_01. DC Motor per-step lifecycle  {#SP_EEM_04_01}

    [stamped] → startIteration → angle += ω·Δt →
        doStep (torque VS, back-EMF VS, ind.doStep) → solve →
        calculateCurrent (coilCurrent, inertiaCurrent, speed) →
        stepFinished

### 04_02. RelayCoil FSM  {#SP_EEM_04_02}

See C_EEM_03_01; LATCHING toggles `switchPosition ^= 1` on state 1→2.

### 04_03. TimeDelayRelay  {#SP_EEM_04_03}

    [off] --V>2.5 → mark edge, start onDelay--> [armed] --delay elapsed--> [on]
    [on]  --V≤2.5 → mark edge, start offDelay--> [armed'] --elapsed--> [off]

## 05. Verification Criteria  {#SP_EEM_05}

### 05_01. Functional Expectations  {#SP_EEM_05_01}

| Contract | Scenario | Input | Expected |
|---|---|---|---|
| DCMotorElm | V=12 V step, no load | — | speed ramps to steady-state ≈ V/Kb |
| DCMotorElm | steady-state with load | torque load = T | speed = (V - R·T/K)/Kb |
| ThreePhaseMotorElm | 3-phase AC on stator | balanced | rotor spins, τ>0 |
| RelayElm | I > onCurrent for > switchingTime | — | contacts flip; switchCurrent updates |
| RelayCoilElm LATCHING | two ON pulses | — | toggles position on each pulse |
| RelayContactElm | setPosition(1, NORMAL) | — | stamps r_on between common and NO |
| TimeDelayRelayElm | V_Vin crosses 2.5 V, onDelay=1s | — | onState flips after 1 s |

### 05_02. Invariant Checks  {#SP_EEM_05_02}

| Invariant | Verification |
|---|---|
| Coil broadcast idempotent | repeat setSwitchPositions → same contact states |
| 3-phase inv. matrix stable | couplingCoef ∈ (0,1) property test |
| RelayElm poleCount ≤ 4 | assertion |

### 05_03. Integration Scenarios  {#SP_EEM_05_03}

| Scenario | Preconditions | Steps | Expected |
|---|---|---|---|
| Coil → 3 contacts | 1 coil + 3 contacts same label | energize | all 3 contacts actuate |
| MotorProtection → RelayContacts | MPS + N contacts | heat to i2t | MPS blows; contacts update |
| DC motor sim reset | running motor | reset | currents zero, angle/speed preserved |

### 05_04. Edge Cases and Boundaries  {#SP_EEM_05_04}

| Case | Input | Expected |
|---|---|---|
| Two coils same label | — | last-write-wins per iteration (no warning) |
| TimeDelayRelay negative onDelay | — | fires immediately (no guard) |
| RelayElm switchingTime=0 | — | falls back to old model formula |
| RelayCoilElm.setParentList(Vector) | base expects ArrayList | shadows; verify elmList flow (Issue #1) |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
