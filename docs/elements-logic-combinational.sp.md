# Combinational Logic Elements — Specification  {#SP_ELC}

> **Code:** SP_ELC
> **Status:** draft
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_ELC](./elements-logic-combinational.concept.md)
> **Depends on specs:** [SP_ELB](./element-base.sp.md), [SP_WFM](./waveforms.sp.md), [SP_GEO](./geometry.sp.md)
> **Used by specs:** io-framework, editor, sequential-logic
> **Plan:** [elements-logic-combinational.plan.md](./elements-logic-combinational.plan.md)
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-logic-combinational.md](../.dev_flow/onboard/analysis/domain-core__cat-logic-combinational.md)
>
> Catalog and contracts for the 10 combinational-logic elements plus
> the InvertingSchmitt trigger.

## 01. Data Structures  {#SP_ELC_01}

### 01_01. Per-element catalog  {#SP_ELC_01_01}

| Element | Extends | Posts (in→out) | V-src | Int.nodes | Dump-type | Parameters | File:lines |
|---|---|---|---|---|---|---|---|
| GateElm (abstract) | CircuitElm | N+1 (N in, 1 out) | 1 | 0 | — | inputCount, highVoltage, FLAG_SMALL=1, FLAG_SCHMITT=2, FLAG_INVERT_INPUTS=4 | GateElm.java:32-386 |
| AndGateElm | GateElm | N+1 | 1 | 0 | 150 | inherited | AndGateElm.java:28-128 |
| NandGateElm | AndGateElm | N+1 | 1 | 0 | 151 | inherited, isInverting=true | NandGateElm.java:26-54 |
| OrGateElm | GateElm | N+1 | 1 | 0 | 152 | inherited | OrGateElm.java:28-152 |
| NorGateElm | OrGateElm | N+1 | 1 | 0 | 153 | inherited, isInverting=true | NorGateElm.java:26-54 |
| XorGateElm | OrGateElm | N+1 | 1 | 0 | 154 | inherited; hides FLAG_INVERT_INPUTS row | XorGateElm.java:27-72 |
| InverterElm | CircuitElm | 2 (in, out) | 1 | 0 | `'I'` (73) | slewRate (V/ns), highVoltage | InverterElm.java:31-207 |
| InvertingSchmittElm | CircuitElm | 2 (in, out) | 1 | 0 | 183 | slewRate, lowerTrigger, upperTrigger, logicOnLevel, logicOffLevel | InvertingSchmittElm.java:33-247 |
| TriStateElm | CircuitElm | 3 (in, out, enable) | 1 | 1 | 180 | r_on, r_off, r_off_ground, highVoltage; FLAG_FLIP, FLAG_FLIP_X, FLAG_FLIP_Y | TriStateElm.java:32-293 |
| DelayBufferElm | CircuitElm | 2 (in, out) | 1 | 0 | 422 | delay (s), threshold (V), highVoltage | DelayBufferElm.java:32-203 |
| ClockElm | RailElm | 1 (out) | 1 | 0 | `'R'` (82) via inheritance + FLAG_CLOCK=1 | WF_SQUARE fixed; maxVoltage=2.5, bias=2.5, frequency=100 | ClockElm.java:25-46 |

Invariants:

- `GateElm.getPostCount() == inputCount + 1`.
- `getVoltageSourceCount() == 1` (hard-coded).
- Output is always post `inputCount` (last post).
- `getConnection(n1,n2) = false`; `hasGroundConnection(inputCount) = true`.

### 01_02. GateElm contract for concrete leaves  {#SP_ELC_01_02}

| Override | Required? | Purpose |
|----------|-----------|---------|
| `abstract boolean calcFunction()` | yes | AND/OR/XOR fold of `getInput(i)` |
| `abstract String getGateName()` | yes | info-panel label |
| `boolean isInverting()` | no (default false) | NAND/NOR bubble |
| `String getGateText()` | no | European-style text ("&", "≥1", "=1") |
| `void drawGatePolygon(g)` | no | custom body shape |
| `int getLeadAdjustment(int)` | no | OR-style staggered leads |

Dump format: `super.dump() + inputCount + lastOutputVoltage + highVoltage`.
`setupVolts()` re-seeds input voltages on load so `lastOutput` remains
stable.

## 02. Contracts  {#SP_ELC_02}

### 02_01. N-input fold  {#SP_ELC_02_01}

| Gate | Fold | File:line |
|------|------|-----------|
| AND | `f=true; f &= getInput(i)` | AndGateElm.java:108-114 |
| OR  | `f=false; f |= getInput(i)` | OrGateElm.java:132-138 |
| XOR | `f=false; f ^= getInput(i)` | XorGateElm.java:45-51 |
| NAND | AND, then `isInverting` negates | inherited |
| NOR | OR, then `!f` | inherited |

`inputCount` editable 1–8; change calls `allocNodes(); setupVolts();
setPoints()`. Pins laid vertically with even-count zero-skip.

### 02_02. Input threshold and hysteresis  {#SP_ELC_02_02}

Simple mode: `getInput(x) = getNodeVoltage(x) > highVoltage * 0.5`.

Schmitt mode (`FLAG_SCHMITT`): hysteresis window
`[0.35·highVoltage, 0.55·highVoltage]`. Threshold selected by
`inputStates[x]` previous classification.

Invert-inputs (`FLAG_INVERT_INPUTS`): XOR with per-input bubble flag.
XorGateElm hides this row.

### 02_03. TriState  {#SP_ELC_02_03}

Topology: `VS(node3) — R(r_on|r_off) — node1 (out) — Rpulldown — ground`.

    open = V(enable) < highVoltage * 0.5
    R = open ? r_off : r_on
    stamp: VS(0, node3, voltSource)
           stampResistor(node3, node1, R)
           stampResistor(node1, 0, r_off_ground) if non-zero
    update: updateVoltageSource(0, node3, vs, in > Vh/2 ? Vh : 0)
    nonLinear() = true

Defaults: `r_on = 0.1 Ω`, `r_off = 1e10 Ω`, `r_off_ground = 1e8 Ω`
(fresh) / `0` (dump-loaded) — asymmetry.

### 02_04. DelayBuffer edge-timeout  {#SP_ELC_02_04}

See Concept mechanisms. One-pending-edge model; not FIFO.

### 02_05. InverterElm / InvertingSchmittElm slew  {#SP_ELC_02_05}

Per-step output change capped at `slewRate * timeStep * 1e9` V
(slewRate specified in V/ns). InverterElm captures
`lastOutputVoltage` in `startIteration`; InvertingSchmittElm reads it
mid-solve inside `doStep`.

## 03. Validation Rules  {#SP_ELC_03}

- `GateElm.setEditValue` enforces `inputCount >= 1`; `highVoltage ∈ [1,10]`.
- InverterElm / InvertingSchmitt / DelayBuffer dump-ctors swallow parse
  errors in bare `catch (Exception e)` → fall back to in-ctor defaults.
- TriStateElm `setEditValue` clamps `r_on`, `r_off`, `r_off_ground > 0`.
- InvertingSchmittElm `setEditValue` auto-sorts upper/lower trigger if
  user inverts; thresholds ∈ [0.01, 5] V.
- ClockElm overrides `getDumpClass` but not `getDumpType` — relies on
  inherited RailElm `'R'` token.

## 04. State Transitions  {#SP_ELC_04}

InvertingSchmittElm:

| From | To | Condition |
|------|----|-----------|
| low (logicOffLevel) | high | V(in) < lowerTrigger |
| high (logicOnLevel) | low | V(in) > upperTrigger |

TriStateElm:

| From | To | Condition |
|------|----|-----------|
| tri-stated | enabled | V(enable) > highVoltage/2 |
| enabled | tri-stated | V(enable) < highVoltage/2 |

## 05. Verification Criteria  {#SP_ELC_05}

### 05_01. Functional Expectations  {#SP_ELC_05_01}

| Element | Scenario | Expected |
|---------|----------|----------|
| AndGateElm | inputs (1,1,0) | out=0 |
| NandGateElm | inputs (1,1) | out=0 (inverted) |
| XorGateElm | (1,0,1) | out=0 |
| InverterElm | V(in) rising | output falls, slew-limited |
| InvertingSchmittElm | V(in) crossing thresholds | hysteretic switch |
| TriStateElm | enable=low | output Hi-Z (via r_off + pulldown) |
| DelayBufferElm | step input | output follows after `delay` seconds |
| ClockElm | 100 Hz default | 0→5V square wave, 50% duty |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
