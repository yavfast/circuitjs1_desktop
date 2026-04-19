# IO, Probes, Meters, Displays — Specification  {#SP_EIO}

> **Code:** SP_EIO
> **Status:** draft
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_EIO](./elements-io-probes-meters.concept.md)
> **Depends on specs:** [SP_ELB](./element-base.sp.md), [SP_UTL](./util-locale-log.sp.md), [SP_GEO](./geometry.sp.md), [SP_RND](./rendering-primitives.sp.md)
> **Used by specs:** circuit simulator, scope manager, editor, IO framework
> **Plan:** [elements-io-probes-meters.plan.md](./elements-io-probes-meters.plan.md)
>
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-io-probes-meters.md](../.dev_flow/onboard/analysis/domain-core__cat-io-probes-meters.md)
>
> Dense catalog of 13 elements across probes, series meters, IO drivers,
> and displays. Three MNA stamping patterns, one current-source meter,
> JSNI file bridges, ring-buffer recorder, stop-trigger semantics.

## 01. Element Catalog  {#SP_EIO_01}

> Implements: [C_EIO_02](./elements-io-probes-meters.concept.md#C_EIO_02)

### 01_01. Element Table  {#SP_EIO_01_01}

| Element | Extends | Posts | V-sources | Role | Dump |
|---|---|---|---|---|---|
| `LogicInputElm` | `SwitchElm` | 1 | 1 | Drives hi/lo (or 3-level/numeric) via voltage source | `'L'` 76 |
| `LogicOutputElm` | `CircuitElm` | 1 | 0 | Reads V0; displays L/H; optional 1 MΩ pulldown | `'M'` 77 |
| `DataInputElm` | `RailElm` | 1 | 1 (inherited) | File playback as voltage rail | 424 |
| `OutputElm` | `CircuitElm` | 1 | 0 | Voltage label; plot X/Y marker | `'O'` 79 |
| `DecimalDisplayElm` | `ChipElm` | `bitCount` (1-16) | 0 | Bit-pack → decimal | 419 |
| `ScopeElm` | `CircuitElm` | **0** | 0 | Embeds `Scope` manager | 403 |
| `ProbeElm` | `CircuitElm` | 2 | 0 | Voltmeter + wave stats; optional series R | `'p'` 112 |
| `TestPointElm` | `CircuitElm` | 1 | 0 | Single-ended ProbeElm + text label | 368 |
| `DataRecorderElm` | `CircuitElm` | 1 | 0 | Ring buffer → Blob URL | 210 |
| `StopTriggerElm` | `CircuitElm` | 1 | 0 | Halts sim on threshold cross + delay | 408 |
| `AmmeterElm` | `CircuitElm` | 2 | 1 (0 V) | Series current meter | 370 |
| `OhmMeterElm` | `CurrentElm` | 2 | 0 (inherited) | Injects probe current → R=V/I | 216 |
| `WattmeterElm` | `CircuitElm` | 4 (I+,I−,V+,V−) | 2 (0 V) | P = Vd × I | 420 |

Invariants:
- Probes/displays have zero voltage sources (except meters).
- Ammeter/Wattmeter survive `isRemovableWire` — current row is
  load-bearing.
- `ScopeElm.canViewInScope()==false` (no nesting).
- `WattmeterElm.hasGroundConnection=false`.

### 01_02. Stamping Patterns  {#SP_EIO_01_02}

| Pattern | Elements | Stamp |
|---|---|---|
| No stamp | OutputElm, TestPointElm, DataRecorderElm, StopTriggerElm, LogicOutputElm (default) | — |
| Optional resistor | ProbeElm (10 MΩ default); LogicOutputElm + FLAG_PULLDOWN (1 MΩ) | `stampResistor` |
| Ideal 0-V VS | AmmeterElm, WattmeterElm (both pairs) | `stampVoltageSource(a,b,vs,0)` |
| Current source | OhmMeterElm (via CurrentElm) | `stampCurrentSource` |
| Voltage source | LogicInputElm, DataInputElm (via RailElm) | `stampVoltageSource`; `updateVoltageSource` per step |
| Chip protocol | DecimalDisplayElm | inherits ChipElm |
| Graphic embed | ScopeElm | no stamp; embeds Scope.dump() |

## 02. Contracts  {#SP_EIO_02}

### 02_01. Probe Statistics  {#SP_EIO_02_01}

ProbeElm / TestPointElm share modes: `TP_VOL, TP_RMS, TP_MAX, TP_MIN,
TP_P2P, TP_BIN, TP_FREQ, TP_PERIOD, TP_PW, TP_DUTY`.

Timing note: frequency / period / PW use `System.currentTimeMillis()` —
**wall-clock dependent** (known issue).

### 02_02. Series Ammeter  {#SP_EIO_02_02}

Processing logic:

    stamp():
        stampVoltageSource(node0, node1, voltSource, 0)
    calculateCurrent():
        current = voltSources[voltSource]   # row current from MNA solve

### 02_03. DataRecorder Buffer  {#SP_EIO_02_03}

- Ring buffer `data[dataCount]`; `dataPtr` write index; `dataFull` wrap flag.
- Export pulls from `dataPtr` forward when full, else `0..dataPtr`.
- Header: `# time step = <dt> sec\n` + one double per line.

### 02_04. StopTrigger  {#SP_EIO_02_04}

Processing logic (stepFinished):

    v = getNodeVoltage(0)
    IF not triggered AND comparison(v, triggerVoltage, type):
        triggered = true; triggerTime = simulator.t
    IF triggered AND simulator.t >= triggerTime + delay:
        triggered = false; stopped = true
        circuitDocument.setSimRunning(false)

## 03. Validation Rules  {#SP_EIO_03}

### 03_01. Input Validation  {#SP_EIO_03_01}

- `ProbeElm.getConnection(n1, n2) == (resistance != 0)` — R=0 isolates.
- `AmmeterElm.isWireEquivalent==true` but **not** removable wire.
- `WattmeterElm.hasGroundConnection=false`; canViewInScope=true;
  canFlipX/Y=false.
- `ScopeElm.getPostCount()==0`; `canViewInScope()==false`.
- `LogicInputElm.hasGroundConnection(0)==true`.
- `DecimalDisplayElm.setChipEditValue` clamps `[1,16]` (dialog caps 8 —
  silent widening).
- `TestPointElm.stepFinished` guards against double-sampling;
  `ProbeElm.stepFinished` does NOT guard (Issue #2).
- `DataInputElm.fileNumCounter` / `dataFileMap` persist across delete —
  `clearCache()` only release path.
- `StopTriggerElm.reset()` clears `triggered` but not `stopped` (benign).

## 04. State Transitions  {#SP_EIO_04}

### 04_01. DataRecorder  {#SP_EIO_04_01}

    [empty] --sample--> [partial] --dataPtr>=dataCount--> [full/wrap]
    [*]     --reset-->  [empty]   (dataPtr=0, dataFull=false)

### 04_02. StopTrigger  {#SP_EIO_04_02}

    [idle] --V crosses threshold--> [armed(triggerTime)]
    [armed(t0)] --t-t0>=delay--> [stopped → idle]

## 05. Verification Criteria  {#SP_EIO_05}

### 05_01. Functional Expectations  {#SP_EIO_05_01}

| Contract | Scenario | Input | Expected |
|---|---|---|---|
| AmmeterElm | 1 A through branch | — | getCurrent()=1.0 |
| ProbeElm | V(a)-V(b)=3.3V, R=10MΩ | — | V reading=3.3, current≈0 |
| WattmeterElm | 5 V × 1 A load | — | getPower()=5 W |
| OhmMeterElm | 100 Ω resistor | — | R=100 Ω ±1% |
| StopTriggerElm | V crosses 2.5 V rising | delay=0 | sim stops within 1 timestep |
| DataRecorderElm | 1000 samples, capacity 512 | — | dataFull=true, oldest overwritten |
| DataInputElm | 3-sample file, FLAG_REPEAT | t>file duration | wraps to sample 0 |
| DecimalDisplayElm | input 0b1010, bitCount=4 | — | renders "10" |

### 05_02. Invariant Checks  {#SP_EIO_05_02}

| Invariant | Verification |
|---|---|
| Ammeter isRemovableWire==false | assertion |
| ScopeElm postCount==0 | assertion |
| Probe R=0 → isolated | property test |

### 05_03. Integration Scenarios  {#SP_EIO_05_03}

| Scenario | Preconditions | Steps | Expected |
|---|---|---|---|
| X-Y plot | Output at plotX + Output at plotY | draw | markers labeled X/Y |
| ScopeElm save/load | Scope configured, dump, reload | — | waveform restored |
| StopTrigger after delay | threshold crossed t=1s, delay=0.5s | run | stops at t≈1.5s |

### 05_04. Edge Cases and Boundaries  {#SP_EIO_05_04}

| Case | Input | Expected |
|---|---|---|
| DataInputElm EOF | past last sample, no REPEAT | output 0 V |
| DataRecorder reset mid-full | — | dataPtr=0, buffer NOT zeroed (stale past dataPtr) |
| WattmeterElm V-pair | V+/V− shorted by design | works only when load is inside current loop |
| ScopeElm dump null | embedded Scope.dump() returns null | dump corruption (Issue #12) |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
