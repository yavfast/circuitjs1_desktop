# IO, Probes, Meters, Displays  {#C_EIO}

> **Code:** C_EIO
> **Status:** draft
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** OnboardDocGen
>
> **Depends on:** [C_ELB](./element-base.concept.md), [C_UTL](./util-locale-log.concept.md), [C_GEO](./geometry.concept.md), [C_RND](./rendering-primitives.concept.md)
> **Used by:** circuit simulator, scope manager, editor (plot X/Y markers), IO framework
> **Spike:** —
> **Specification:** [SP_EIO](./elements-io-probes-meters.sp.md)
> **Plan:** [elements-io-probes-meters.plan.md](./elements-io-probes-meters.plan.md)
>
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-io-probes-meters.md](../.dev_flow/onboard/analysis/domain-core__cat-io-probes-meters.md)
>
> Heterogeneous catch-all of 13 elements whose common thread is
> **output-path orientation**: each either injects a value from outside
> the netlist (LogicInput, DataInput) or extracts a value for display,
> logging, X/Y plotting, or flow-control of the simulator. Three stamping
> patterns: zero-stamp probes, 0-V-source series meters, current-source
> ohmmeter.

## 1. Philosophy  {#C_EIO_01}

### 1.1. Core Principle  {#C_EIO_01_01}

This category answers "how do I *see into* or *talk to* the netlist?"
without modelling physical two-terminal devices. Three sub-roles coexist:

1. **Probes** — zero-current, read-only voltage/wave statistics (ProbeElm,
   TestPointElm).
2. **Series meters** — inline devices that insert an ideal 0 V voltage
   source so MNA computes their current cleanly (AmmeterElm, WattmeterElm);
   OhmMeterElm uses a current-source variant.
3. **IO + display** — boundary elements (LogicInputElm, DataInputElm,
   LogicOutputElm, OutputElm, DecimalDisplayElm, DataRecorderElm,
   StopTriggerElm, ScopeElm).

### 1.2. Design Constraints  {#C_EIO_01_02}

- **Zero physics contamination.** A probe must not alter the measurement
  (ProbeElm at `R=0` reports `getConnection=false` — pure non-invasive).
- **Ammeter must not be optimized away.** Even though
  `isWireEquivalent()==true`, the 0-V source row must survive analysis —
  the row's current **is** the measurement.
- **Document-level mutation is concentrated in `StopTriggerElm`** — the
  only probe/meter that calls `setSimRunning(false)`.
- **Scope embedding** is an outlier: `ScopeElm` wraps a full `Scope`
  manager inside a zero-post graphic element with its own coordinate
  escape in `draw()` (screen coords via inverse transform).

## 2. Domain Model  {#C_EIO_02}

### 2.1. Key Entities  {#C_EIO_02_01}

    CircuitElm
      ├── LogicOutputElm, OutputElm           (1-post, zero-current sensors)
      ├── ProbeElm, TestPointElm              (voltmeter; wave stats)
      ├── DataRecorderElm, StopTriggerElm     (time-series / flow-control)
      ├── AmmeterElm, WattmeterElm            (0-V series meters)
      └── ScopeElm                            (embeds Scope manager; 0 posts)
    ChipElm
      └── DecimalDisplayElm                   (bit-pack input pins → decimal)
    SwitchElm
      └── LogicInputElm                       (voltage source driver)
    VoltageElm → RailElm
      └── DataInputElm                        (file-playback rail)
    CurrentElm
      └── OhmMeterElm                         (probe current → V/I)

### 2.2. Data Flows  {#C_EIO_02_02}

Probe sample flow (typical):

    simulator.stepFinished → elm.stepFinished → read getNodeVoltage(n) →
        update wave stats (min/max/rms/period) → repaint info panel

Ammeter/Wattmeter:

    stamp: stampVoltageSource(node_a, node_b, vs, 0V) [+ second pair for wattmeter]
    solve: VS row yields current
    calculateCurrent: current = voltage-source-row result

DataInput (file playback rail):

    getVoltage(): data[floor(timeOffset * samplingRate)] * scaleFactor
    stepFinished: timeOffset += simulator.timeStep [wrap if FLAG_REPEAT]

DataRecorder (ring buffer):

    stepFinished (guarded on timeStepCount): data[dataPtr++] = V(0);
        wrap with dataFull=true

StopTrigger:

    stepFinished: if V crosses threshold → triggered=true; triggerTime=t;
        after delay → circuitDocument.setSimRunning(false)

## 3. Mechanisms  {#C_EIO_03}

### 3.1. Core Algorithm  {#C_EIO_03_01}

**Zero-stamp probe:** no `stamp()`; read `getNodeVoltage(n)` in
`stepFinished` / `draw`. ProbeElm optionally stamps a series resistor
(10 MΩ default) when `resistance != 0`.

**Series ammeter (0-V VS trick):** `stampVoltageSource(a, b, vs, 0)`. The
short forces `V_a = V_b` while assigning a Lagrange current row that
equals the branch current. `isWireEquivalent=true` but not
`isRemovableWire`.

**Wattmeter:** same trick on both terminal pairs; `P = Vd × I`. V-pair
is also a 0-V short — **this is a topology assumption** the dialog does
not document (Issue #1).

**Ohm-meter:** inherits `CurrentElm` current-source stamping; reports
`R = Vd / I`; if `setBroken(true)` → 1e8 Ω fallback.

**Display elements:** `DecimalDisplayElm` packs input pins via ChipElm
threshold protocol into an unsigned integer; `ScopeElm` hosts an embedded
`Scope` with its own serialization and screen-coordinate render hack.

### 3.2. Edge Cases  {#C_EIO_03_02}

- `LogicOutputElm.FLAG_PULLDOWN` ("Current Required" in dialog) adds 1 MΩ
  to ground so floating outputs have a DC path.
- `DataRecorderElm` uses `lastTimeStepCount` guard to avoid double-writes
  during Newton re-convergence.
- `ProbeElm.stepFinished` is **not guarded** against multiple Newton
  iterations — RMS/avg may over-count.
- Period/pulse-width measurements use `System.currentTimeMillis()` (wall
  clock) — break when simulator is paused or scaled.
- `ScopeElm.dump()` can return null — propagates through the dump pipeline.
- `DataInputElm.dataFileMap` is a static cache; never pruned on delete.

## 4. Integration Points  {#C_EIO_04}

### 4.1. Dependencies  {#C_EIO_04_01}

- [C_ELB](./element-base.concept.md) — base classes, pins, lifecycle.
- [C_GEO](./geometry.concept.md) — Point, Rectangle for plot markers.
- [C_RND](./rendering-primitives.concept.md) — Graphics, Font for labels.
- `Scope` / `ScopeManager` / `ActionManager` — ScopeElm coupling.
- `CircuitEditor.plotXElm` / `plotYElm` — OutputElm / ProbeElm markers.
- `CircuitDocument.setSimRunning` — StopTriggerElm only.
- IO framework (`FileUpload`, Blob URL, JSNI) — DataInputElm,
  DataRecorderElm, TestPointElm alert.

### 4.2. API Surface  {#C_EIO_04_02}

- Read-only probes: `getNodeVoltage(0)` + `getInfo()` dialog rows.
- Ammeter: `getCurrent()` via voltage-source row.
- Wattmeter: `getCurrent()` + `getVoltageDiff()` + `getPower()`.
- DataRecorder: `getBlobUrl()` / filename pattern
  `data-YYYYMMDD-HHMM.circuitjs.txt`.
- ScopeElm: `stepScope()` per timestep, `elmScope.dump()` embedded in
  element dump (space-to-underscore escape).
- DecimalDisplayElm: ChipElm input pins, `bitCount` 1..16 (dialog caps 8,
  setter widens silently).

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
