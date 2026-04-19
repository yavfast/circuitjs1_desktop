# Switch Elements — Specification  {#SP_ESW}

> **Code:** SP_ESW
> **Status:** draft
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_ESW](./elements-switches.concept.md)
> **Depends on specs:** [SP_ELB](./element-base.sp.md), [SP_UTL](./util-locale-log.sp.md), [SP_GEO](./geometry.sp.md), [SP_RND](./rendering-primitives.sp.md)
> **Used by specs:** editor interaction, circuit element factory
> **Plan:** [elements-switches.plan.md](./elements-switches.plan.md)
>
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-switches.md](../.dev_flow/onboard/analysis/domain-core__cat-switches.md)
>
> Catalog of 9 switch elements (7 mechanical, 2 analog, 1 latching);
> stamping patterns A/B/C; click-toggle protocol; ganged-switch
> semantics.

## 01. Element Catalog  {#SP_ESW_01}

> Implements: [C_ESW_02](./elements-switches.concept.md#C_ESW_02)

### 01_01. Element Table  {#SP_ESW_01_01}

| Element | Extends | Poles | Throws | `posCount` | V-sources | `getPostCount()` | Dump | Shortcut |
|---|---|---|---|---|---|---|---|---|
| `SwitchElm` | `CircuitElm` | 1 | 1 (SPST) | 2 (0=closed, 1=open) | 0 | 2 | `'s'` (115) | `s` |
| `Switch2Elm` | `SwitchElm` | 1 | 2..10 | `throwCount` [+1 legacy center-off] | 1 (0 center-off) | `1 + throwCount` | `'S'` (83) | `S` |
| `DPDTSwitchElm` | `SwitchElm` | 2..10 | 2 | 2 | `poleCount` | `3 * poleCount` | 429 | — |
| `PushSwitchElm` | `SwitchElm` | 1 | 1 | 2 | 0 | 2 | inherits SwitchElm | — |
| `MBBSwitchElm` | `SwitchElm` | 1 | 2 | **4** | 1 or 2 (dynamic) | 3 | 416 | — |
| `CrossSwitchElm` | `SwitchElm` | 2 | 2 | 2 | 2 | 4 | 430 | — |
| `MotorProtectionSwitchElm` | `CircuitElm` | 3 | 1 | latch `blown` | 0 | 6 | 428 | — |
| `AnalogSwitchElm` | `CircuitElm` | 1 | 1 | `open` bool | 0 | 3 | 159 | — |
| `AnalogSwitch2Elm` | `AnalogSwitchElm` | 1 | 2 | `open` bool | 0 | 4 | 160 | — |

Invariants:
- Only `SwitchElm` subclasses are clickable.
- `nonLinear()` is true iff analog or motor-protection.
- `isWireEquivalent() == true` for mechanical multi-pole variants; base
  only when closed.

### 01_02. Stamping Patterns  {#SP_ESW_01_02}

| Pattern | Elements | Stamp action |
|---|---|---|
| A — wire closure | `SwitchElm` (base) | `isWireEquivalent` when closed → node merge |
| A′ — 0-V source per pole | `Switch2Elm`, `DPDTSwitchElm`, `CrossSwitchElm`, `MBBSwitchElm` | `stampVoltageSource(a, b, vs, 0)` |
| B — resistor pair | `AnalogSwitchElm`, `AnalogSwitch2Elm` | `stampResistor(r_on\|r_off)` each doStep |
| C — latch resistor | `MotorProtectionSwitchElm` | `stampResistor(resistance or 1e9 Ω)` driven by `blown` |

### 01_03. Control Sources  {#SP_ESW_01_03}

- Mechanical: mouse click → `doSwitch` → `toggle()`; mouseUp re-toggles
  momentary.
- Analog: `open = (V_ctrl < threshold) ^ FLAG_INVERT` every doStep.
- Motor-protection: `blown` set by `heats[j] > i2t`; cleared by
  `reset()`.

## 02. Contracts  {#SP_ESW_02}

### 02_01. toggle()  {#SP_ESW_02_01}

Purpose: advance `position` and broadcast to ganged peers.

Processing logic:

    FUNCTION toggle(self):
        self.position = (self.position + 1) % posCount
        IF FLAG_LABEL on self:
            FOR each elm in simulator.elmList:
                IF elm instanceof SwitchElm AND elm.label == self.label:
                    elm.simpleToggle()
        IF link > 0 (Switch2Elm, MBBSwitchElm):
            FOR each elm: IF matching link: elm.position = self.position

### 02_02. MotorProtectionSwitchElm I²t latch  {#SP_ESW_02_02}

Processing logic (startIteration):

    FOR j in 0..2:
        heats[j] += i[j]² * Δt
        heats[j] -= Δt * i2t / 3
        IF heats[j] > i2t:
            blown = true
            setSwitchPositions()  # push to every RelayContactElm with matching label

### 02_03. AnalogSwitchElm doStep  {#SP_ESW_02_03}

    open = (V(ctl) < threshold) XOR FLAG_INVERT
    stampResistor(in, out, open ? r_off : r_on)
    IF FLAG_PULLDOWN:
        stampResistor(in, gnd, r_off)
        stampResistor(out, gnd, r_off)

## 03. Validation Rules  {#SP_ESW_03}

### 03_01. Input Validation  {#SP_ESW_03_01}

- `Switch2Elm`: `throwCount >= 2`; `momentary` auto-disabled when > 2.
- `DPDTSwitchElm`: `poleCount >= 2`; alloc + setPoints re-grown.
- `AnalogSwitchElm`: `r_on > 0`, `r_off > 0`.
- `MBBSwitchElm.getVoltageSourceCount()` has **side effect** on `both` —
  must be called before `stamp()` each analysis cycle (documented
  invariant).
- `MotorProtectionSwitchElm.canFlipX/Y == false`; `reset()` zeros heats
  and clears blown, then re-syncs linked contacts.
- `SwitchElm.calculateCurrent` forces `current = 0` when open.
- Legacy dump: `SwitchElm` ctor accepts `true`/`false` tokens (inverted
  for `LogicInputElm`).

## 04. State Transitions  {#SP_ESW_04}

### 04_01. Mechanical  {#SP_ESW_04_01}

    SwitchElm       :  position ∈ {0, 1}
    Switch2Elm      :  position ∈ {0..throwCount-1} [+center]
    DPDTSwitchElm   :  position ∈ {0, 1}  (all poles move together)
    CrossSwitchElm  :  position ∈ {0=straight, 1=crossed}
    MBBSwitchElm    :  position ∈ {0=A, 1=A+B, 2=B, 3=B+A}
    PushSwitchElm   :  toggle on mouseDown, toggle back on mouseUp

### 04_02. Analog  {#SP_ESW_04_02}

    open_t+1 = (V(ctl) < threshold) ^ FLAG_INVERT   # re-evaluated every doStep

### 04_03. MotorProtection  {#SP_ESW_04_03}

    blown ∈ {false, true}   # one-way latch until reset()
    heats[0..2] : I²t integrator per phase
    reset() → heats=0, blown=false, re-broadcast contacts

## 05. Verification Criteria  {#SP_ESW_05}

### 05_01. Functional Expectations  {#SP_ESW_05_01}

| Contract | Scenario | Input | Expected |
|---|---|---|---|
| SwitchElm.toggle | click on rect | mouseDown in getSwitchRect | position toggles; needAnalyze |
| PushSwitchElm | mouseDown+Up | hold release | position returns to original |
| AnalogSwitchElm | V_ctl > threshold | — | r_on stamped between in/out |
| AnalogSwitchElm | FLAG_INVERT | V_ctl > threshold | r_off stamped |
| MotorProtectionSwitchElm | i above i2t for full integration | 3 s | blown=true; contacts updated |
| MBBSwitchElm | position 1 or 3 | — | 2 VS stamped (both throws live) |
| Switch2Elm.link=5 | click peer | — | all link=5 switches mirror position |

### 05_02. Invariant Checks  {#SP_ESW_05_02}

| Invariant | Verification |
|---|---|
| open SwitchElm → current=0 | unit test `calculateCurrent` |
| `getSwitchRect().contains(pos)` reachable for every SwitchElm subclass | editor integration test |
| `nonLinear()` only for Analog/MotorProtection | assertion table |

### 05_03. Integration Scenarios  {#SP_ESW_05_03}

| Scenario | Preconditions | Steps | Expected |
|---|---|---|---|
| Ganged pair | two SwitchElms, FLAG_LABEL, same label | click one | both toggle |
| Motor-protection → relay contacts | 1 MPS + N contacts same label | heat to i2t | contacts open |
| Analog pulldown | FLAG_PULLDOWN on AnalogSwitch, open | isolated data posts | posts pulled to gnd via r_off |

### 05_04. Edge Cases and Boundaries  {#SP_ESW_05_04}

| Case | Input | Expected behavior |
|---|---|---|
| Switch2Elm throwCount=2, momentary | — | allowed |
| Switch2Elm throwCount=3, momentary | — | momentary auto-cleared |
| MBBSwitchElm between throws | position 1 | stamps 2 VS |
| AnalogSwitch2Elm open branch current | r_on != r_off | **wrong magnitude** reported (Issue #6) |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
