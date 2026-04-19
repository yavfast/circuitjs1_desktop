# Combinational Logic Elements  {#C_ELC}

> **Code:** C_ELC
> **Status:** draft
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** OnboardDocGen
>
> **Depends on:** [C_ELB](./element-base.concept.md), [C_WFM](./waveforms.concept.md) (ClockElm), [C_GEO](./geometry.concept.md), [C_RND](./rendering-primitives.concept.md)
> **Used by:** element factory, editor, sequential-logic chips (interop)
> **Spike:** —
> **Specification:** [SP_ELC](./elements-logic-combinational.sp.md)
> **Plan:** [elements-logic-combinational.plan.md](./elements-logic-combinational.plan.md)
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-logic-combinational.md](../.dev_flow/onboard/analysis/domain-core__cat-logic-combinational.md)
>
> Discrete-logic gate family drawn as standalone symbols (not ChipElm):
> N-input gates (AND/OR/NAND/NOR/XOR), inverter and hysteretic inverter,
> tri-state buffer, delay buffer, and the digital clock source.

## 1. Philosophy  {#C_ELC_01}

### 1.1. Core Principle  {#C_ELC_01_01}

Every element here stamps a **single voltage source to ground** for its
output, treats every input as a **high-impedance voltage probe**, and on
each step thresholds inputs at `highVoltage/2`, computes a boolean
function, and `updateVoltageSource()`s the output to 0 or `highVoltage`.
`ClockElm` is the sole structural outlier (a `RailElm`-family square-wave
source) categorized here by role, not by inheritance.

### 1.2. Design Constraints  {#C_ELC_01_02}

- `getConnection(n1,n2)=false` for inputs — no analyzer-level current
  path.
- `hasGroundConnection(outputNode)=true` — the output is a grounded VS.
- `getCurrentIntoNode(n)` returns `current` only at the output post.
- `GateElm` is abstract; concrete leaves supply `calcFunction`,
  `getGateName`, and optionally `isInverting` / `getGateText` /
  `drawGatePolygon`.

## 2. Domain Model  {#C_ELC_02}

### 2.1. Key Entities  {#C_ELC_02_01}

Per-element name list (full catalog in SP_ELC):

- Gate base + leaves: `GateElm` (abstract), `AndGateElm`, `NandGateElm`,
  `OrGateElm`, `NorGateElm`, `XorGateElm`
- Single-input shapers: `InverterElm`, `InvertingSchmittElm`
- Bus driver: `TriStateElm`
- Timing: `DelayBufferElm`, `ClockElm`

Total: 11 files (10 in brief + InvertingSchmittElm).

### 2.2. Data Flows  {#C_ELC_02_02}

GateElm per-step:

    for i in 0..inputCount-1: v_i = getNodeVoltage(i)
    apply threshold (simple or Schmitt) + optional bubble (INVERT_INPUTS)
    f = calcFunction()   // AND / OR / XOR fold via getInput(i)
    if isInverting(): f = !f
    oscillation damping: count consecutive flips, randomly hold if > 50
    updateVoltageSource(out, f ? highVoltage : 0)

## 3. Mechanisms  {#C_ELC_03}

### 3.1. Core Algorithms  {#C_ELC_03_01}

**N-input fold** — AND: f=true, `f &= getInput(i)`; OR: f=false,
`f |= getInput(i)`; XOR: f=false, `f ^= getInput(i)`; NAND/NOR invert
the result via `isInverting()=true`.

**Schmitt hysteresis input mode** (`FLAG_SCHMITT`): window
`[0.35·Vh, 0.55·Vh]`. Per-input `inputStates[]` boolean latch selects
which threshold applies: rise through `0.55·Vh` to go high, fall
through `0.35·Vh` to go low.

**Oscillation damping:** Per-iteration flip counter. After 50
consecutive flips, randomly hold the previous output (`RandomUtils.getRand(10) > 5`)
to break feedback-loop limit cycles. Counter resets when
`lastTime != simulator.t` (once per real timestep).

**TriStateElm resistor-network Hi-Z:** 3 external posts (in, out,
enable) + 1 internal node (VS attached to node 3, not directly to
output). `doStep` rebuilds: `stampResistor(node3, out, r_on | r_off)`
and optional `stampResistor(out, ground, r_off_ground)` for pulldown.
Output voltage = `input > highVoltage/2 ? highVoltage : 0`.

**DelayBufferElm edge-timeout (not a queue):**

    inState = V(in) > threshold
    outState = V(out) > threshold
    if inState != outState:
        if sim.t >= delayEndTime: outState = inState
    else:
        delayEndTime = sim.t + delay   // re-arm countdown

**InverterElm slew limiting:** Captures `lastOutputVoltage` in
`startIteration` and clamps per-step output change to
`slewRate·timeStep·1e9` V (slewRate is V/ns).

**InvertingSchmittElm:** Full state machine with two thresholds + same
slew limiting formula. `state` latches output polarity.

**ClockElm:** Tiny subclass of `RailElm` that sets
`Waveform.WF_SQUARE`, `maxVoltage=2.5`, `bias=2.5` (so output swings 0–5 V),
`frequency=100 Hz`, and `flags |= FLAG_CLOCK`. Persisted via RailElm's
`'R'` dump type + flag bit.

### 3.2. Edge Cases  {#C_ELC_03_02}

- `TriStateElm` ctor-from-dump sets `r_off_ground=0` (pulldown disabled)
  while fresh-element ctor uses 1e8 Ω — loaded tri-state differs from
  fresh one.
- Output snap between rails has no intermediate; slew limit only on
  Inverter/InvertingSchmitt.
- `GateElm` dumps `lastOutputVoltage` (raw volts) rather than boolean;
  hand-editing `highVoltage` in the dump file can flip state on reload.

## 4. Integration Points  {#C_ELC_04}

### 4.1. Dependencies  {#C_ELC_04_01}

- [C_ELB](./element-base.concept.md) — `CircuitElm`, `ElmGeometry`,
  `BaseCircuitElm`.
- `RailElm → VoltageElm` + [C_WFM](./waveforms.concept.md) — ClockElm.
- `RandomUtils` — oscillation damping.
- `io.json.UnitParser` — GateElm JSON properties.

### 4.2. API Surface  {#C_ELC_04_02}

Dump-types: `'I'` (73) Inverter, 150 And, 151 Nand, 152 Or, 153 Nor,
154 Xor, 180 TriState, 183 InvertingSchmitt, 422 DelayBuffer, `'R'` (82)
with `FLAG_CLOCK` for ClockElm. Creation shortcuts via `CircuitElmCreator`.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
