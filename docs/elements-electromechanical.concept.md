# Electromechanical Elements  {#C_EEM}

> **Code:** C_EEM
> **Status:** draft
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** OnboardDocGen
>
> **Depends on:** [C_ELB](./element-base.concept.md), [C_UTL](./util-locale-log.concept.md), [C_GEO](./geometry.concept.md), [C_RND](./rendering-primitives.concept.md), [C_EPS](./elements-passives.concept.md) (Inductor helper)
> **Used by:** circuit element factory, simulator, relay-contact cross-coupling
> **Spike:** —
> **Specification:** [SP_EEM](./elements-electromechanical.sp.md)
> **Plan:** [elements-electromechanical.plan.md](./elements-electromechanical.plan.md)
>
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-electromechanical.md](../.dev_flow/onboard/analysis/domain-core__cat-electromechanical.md)
>
> Six elements whose state straddles the electrical↔mechanical boundary:
> DC motor, 3-phase induction motor, integrated relay, split relay
> coil+contact pair, and time-delay relay. They share a single
> "mechanical-state-as-companion-inductor" motif for motors and a
> label-driven coil→contact coupling for split relays.

## 1. Philosophy  {#C_EEM_01}

### 1.1. Core Principle  {#C_EEM_01_01}

The concept's unifying idea is that a **non-electrical state variable**
(rotor angle/speed, armature position, heat integrator, delay counter)
drives **electrical stamping** inside the same MNA matrix via one of two
patterns:

1. **Companion-network** — reify the mechanical DOF as an inductor
   (`J` as L, `b` as parallel R) fed by controlled voltage sources. Used
   by `DCMotorElm` and (partially) `RelayElm`/`RelayCoilElm`.
2. **State-controlled resistor** — stamp `r_on` / `r_off` or
   `onResistance`/`offResistance` driven by state computed by
   `startIteration` / `stepFinished`. Used by `RelayContactElm` and
   `TimeDelayRelayElm`.

The 3-phase motor is a hybrid: a 5×5 coupled-inductance matrix
(inverted by `CircuitMath.invertMatrix`) plus speed-dependent
controlled voltage sources and **manual Euler** mechanical integration
(no inertia inductor).

### 1.2. Design Constraints  {#C_EEM_01_02}

- Every coil-bearing element allocates internal nodes
  (`getInternalNodeCount() > 0`) to isolate the winding inductor from
  its series resistor.
- `nonLinear()==true` for every state-switching element: Newton must be
  allowed to flip state mid-iteration.
- The split-relay `label` field couples `RelayCoilElm` (and
  `MotorProtectionSwitchElm`) to `RelayContactElm` peers — the only
  cross-element direct-coupling pattern in the catalog outside
  CompositeElm.
- Motors do **not** reset rotor angle/speed on simulator reset
  (intentional for pedagogy; inconsistent with relay state zeroing).
- `RelayElm` is a deliberate parallel model to the split pair — kept
  for pedagogical/compact schematic use with up to 4 poles.

## 2. Domain Model  {#C_EEM_02}

### 2.1. Key Entities  {#C_EEM_02_01}

- **`DCMotorElm`** — 2 ext posts; 4 internal nodes; 2 VS (back-EMF,
  torque); armature Inductor + inertia Inductor.
- **`ThreePhaseMotorElm`** — 6 ext posts (U1/U2/V1/V2/W1/W2); 7
  internal; 2 VS (rotor EMF placeholders); 5×5 coupled-L matrix.
- **`RelayElm`** — integrated coil+contacts (1 symbol, up to 4 poles);
  `2 + 3·poleCount` posts.
- **`RelayCoilElm`** — coil half of split pair; 4-state FSM + 4 type
  modes (NORMAL/ON_DELAY/OFF_DELAY/LATCHING); label-broadcasts to
  contacts.
- **`RelayContactElm`** — contact half of split pair; 2-post
  state-controlled resistor; `setPosition(pos,type)` entry point from
  coil.
- **`TimeDelayRelayElm`** — ChipElm-based; 4 pins (Vin, gnd, in, out);
  threshold 2.5 V; `onDelay` / `offDelay` asymmetric.

### 2.2. Data Flows  {#C_EEM_02_02}

DCMotor per step:

    startIteration: ind.startIteration(V_arm); indInertia.startIteration(V_inertia)
                    angle += speed·Δt
    doStep:         updateVS(torque = K·i_arm); updateVS(back-EMF = Kb·ω)
                    ind.doStep; indInertia.doStep
    calculateCurrent: coilCurrent = ind.calc; inertiaCurrent = indInertia.calc
                      speed = inertiaCurrent

3-phase motor:

    stamp: invert xformMatrix[5][5]; stamp L-matrix (conductance + VCCS); Rs + 1.5Rr
    startIteration: compute τ; speed += Δt(τ-bω)/J; vs1/vs2 = f(ω, i)
    doStep: 5× stampCurrentSource; 2× updateVoltageSource(rotor EMF)
    calculateCurrent: coilCurrents[i] = curSourceValue + Σ Vd_j · matrix[i][j]

RelayCoilElm → RelayContactElm (label broadcast):

    avgCurrent = RC1k(|coilCurrent|)
    state FSM: 0→1→2→3→0 with thresholds onCurrent/offCurrent + timing
    on switchPosition change → setSwitchPositions() →
        for each RelayContactElm with matching label:
            contact.setPosition(1 - switchPosition, type)

TimeDelayRelay:

    stepFinished: poweredState = V_Vin - V_gnd > 2.5
                  on edge: lastTransition = t
                  if t > lastTransition + (powered ? onDelay : offDelay):
                      onState = poweredState
    doStep: stampResistor(in, out, onState ? onResistance : offResistance)

## 3. Mechanisms  {#C_EEM_03}

### 3.1. Core Algorithm  {#C_EEM_03_01}

**DC motor — state-space to MNA:**

    V_ab  = R·i + L·di/dt + Kb·ω                  (electrical loop)
    J·dω/dt + b·ω = K·i                            (mechanical → companion L with current ω)

Back-EMF and torque are controlled VS updated each `doStep`.

**3-phase motor — coupled inductor matrix + Park-equiv dq rotor:**

    L_ii = self-inductance                         (diagonal)
    L_ij = couplingCoef · sqrt(L_i · L_j) · sign_ij  (120°-symmetric)
    τ = Zp · √3/2 · Lm · ((i_V - i_W)·i_d - √3·i_U·i_q)
    speed += Δt(τ - b·speed)/J                      (forward Euler)
    vs1/vs2 = speed-dependent rotor EMF placeholders (updated in doStep)

`Zp=2` hardcoded; 5×5 matrix inverted per `stamp()`.

**Relay coil FSM (`RelayCoilElm`):**

    state 0 (off): if avgCurrent > onCurrent → state=1, lastTransition=t
    state 1 (pick-up): if t - lastTransition > switchingTimeOn → state=2
                       on entry: switchPosition = (LATCHING ? 1^switchPosition : 1)
    state 2 (on): if avgCurrent < offCurrent → state=3, lastTransition=t
    state 3 (drop-out): if t - lastTransition > switchingTimeOff → state=0
                        on entry: if not LATCHING: switchPosition=0

**TimeDelayRelay:** single-step gate with separate on/off delays; 10 kΩ
internal sense resistor Vin↔gnd; state-switched resistor between
`in` and `out`.

### 3.2. Edge Cases  {#C_EEM_03_02}

- `RelayElm` carries two models: "new" (hysteresis + linear interpolation)
  and "old" (`(p·pmult)² - 1.3` heuristic). Dialog offers "Use New Model"
  migration button when `switchingTime==0`.
- Motor `reset()` does **not** reset rotor angle/speed (intentional).
- Two coils sharing a label: last-write wins (no de-duplication).
- `RelayContactElm.getJsonPinNames() == {"common","no"}` regardless of
  `FLAG_NORMALLY_CLOSED` — misleading JSON surface.
- `MotorProtectionSwitchElm` (in cat-switches) uses the same label
  mechanism to drive contacts — cross-category coupling.
- `TimeDelayRelayElm` threshold 2.5 V is hardcoded — cannot accommodate
  12 V coil circuits.

## 4. Integration Points  {#C_EEM_04}

### 4.1. Dependencies  {#C_EEM_04_01}

- [C_ELB](./element-base.concept.md) — CircuitElm, ChipElm (for
  TimeDelayRelay), lifecycle hooks.
- [C_EPS](./elements-passives.concept.md) — `Inductor` helper; DCMotor
  owns 2, Relay and RelayCoil own 1 each; all use
  `Inductor.FLAG_BACK_EULER` for mechanical-coupling stability.
- `CircuitMath.invertMatrix` — 3-phase motor only.
- `CustomLogicModel.escape/unescape` — label serialization.
- `io.json.UnitParser.parse` — "200 mH"-style properties.
- `Context2d` — rotated label text.
- `MotorProtectionSwitchElm` (cat-switches) — cross-element label
  coupling.

### 4.2. API Surface  {#C_EEM_04_02}

- `RelayCoilElm.setSwitchPositions()` — broadcast entry point.
- `RelayContactElm.setPosition(pos, type)` — coupling sink.
- `DCMotorElm.setCircuitDocument` — rewires child inductor simulator
  reference on load/reset.
- EditInfo rows: motor parameters (L, R, J, b, K, Kb, gear, V+); relay
  parameters (switchingTime, onCurrent, offCurrent, poleCount, coil
  style); time-delay (onDelay, offDelay, onResistance, offResistance).
- Shortcut: `R` (RelayElm); others via menu.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
