# Module Analysis: domain-core / cat-io-probes-meters-displays

> **Path:** src/main/java/com/lushprojects/circuitjs1/client/element/ (13 concrete files)
> **Layer:** 2 (SCC-A, sits on element-base)
> **Analyzed:** 2026-04-18
> **Files:** 13 source files, 0 test files

## Purpose

Heterogeneous catch-all of elements that do not model a physical two-terminal
component. Three sub-roles coexist in this category:

1. **Probes** (zero-current, 1- or 2-post, read-only voltage / wave
   statistics): `ProbeElm`, `TestPointElm`.
2. **Series meters** (inline measuring devices that must be cut into a wire
   — they insert an ideal-zero voltage source so the solver computes their
   current): `AmmeterElm`, `OhmMeterElm`, `WattmeterElm`.
3. **IO + display** (user-facing boundary elements that either inject a
   value or visualize one, with no internal physics): `LogicInputElm`,
   `LogicOutputElm`, `DataInputElm`, `OutputElm`, `DecimalDisplayElm`,
   `DataRecorderElm`, `StopTriggerElm`, `ScopeElm`.

The common thread is **output-path orientation**: every element here either
(a) drives a value from the outside world into the netlist (logic input,
data input) or (b) extracts a value from the netlist for display, logging,
or flow-control of the simulator. No element in this category participates
in a user-visible physics equation except as a stamp of ideal wires / ideal
resistors to make the measurement observable.

## Class hierarchy

```
CircuitElm
  ├── LogicOutputElm       (1 post, V-sense; 'M' = 77)
  ├── OutputElm            (1 post, V-display; 'O' = 79)
  ├── ProbeElm             (2 post, optional series R; 'p' = 112)
  ├── TestPointElm         (1 post, waveform stats; 368)
  ├── DataRecorderElm      (1 post, ring buffer; 210)
  ├── StopTriggerElm       (1 post, halts sim; 408)
  ├── AmmeterElm           (2 post, 1 vs @ 0V; 370)
  ├── WattmeterElm         (4 post, 2 vs @ 0V; 420)
  └── ScopeElm             (0 post, embeds Scope; 403)
ChipElm
  └── DecimalDisplayElm    (N posts, 0 vs; 419)
SwitchElm
  └── LogicInputElm        (1 post, 1 vs; 'L' = 76)
VoltageElm → RailElm
  └── DataInputElm         (1 post via RailElm, 1 vs; 424)
CurrentElm
  └── OhmMeterElm          (2 post, current-source-based; 216)
```

## Per-element catalog

| Element | Extends | Posts | V-sources | Measurement / role | Dump-type | File:line |
|---|---|---|---|---|---|---|
| `LogicInputElm` | `SwitchElm` | 1 | 1 | Drives hiV/loV (or 3-level/numeric) via voltage source | `'L'` (76) | `LogicInputElm.java:32` |
| `LogicOutputElm` | `CircuitElm` | 1 | 0 | Reads V0, displays L/H by threshold (or 2/1/0 ternary); optional 1 MΩ pulldown | `'M'` (77) | `LogicOutputElm.java:32` |
| `DataInputElm` | `RailElm` (→ `VoltageElm`) | 1 | 1 | Plays back double values from an uploaded text file as a voltage rail | 424 | `DataInputElm.java:38` |
| `OutputElm` | `CircuitElm` | 1 | 0 | Plain voltage label (optional unit scale/fixed-precision); also plot X/Y marker | `'O'` (79) | `OutputElm.java:33` |
| `DecimalDisplayElm` | `ChipElm` | `bitCount` (1-16, default 4) | 0 | Packs input bits into unsigned int, draws decimal | 419 | `DecimalDisplayElm.java:30` |
| `ScopeElm` | `CircuitElm` | **0** (graphic) | 0 | Embeds a `Scope` manager inside the schematic; draws canvas-inside-canvas | 403 | `ScopeElm.java:29` |
| `ProbeElm` | `CircuitElm` | 2 | 0 | Voltmeter: V/Vrms/Vmax/Vmin/Vp2p/bin/freq/period/pw/duty; optional series R | `'p'` (112) | `ProbeElm.java:35` |
| `TestPointElm` | `CircuitElm` | 1 | 0 | Same meter modes as ProbeElm but single-ended with text label | 368 | `TestPointElm.java:36` |
| `DataRecorderElm` | `CircuitElm` | 1 | 0 | Ring buffer of V0 samples, exports blob URL via JSNI | 210 | `DataRecorderElm.java:16` |
| `StopTriggerElm` | `CircuitElm` | 1 | 0 | Halts simulator when V crosses threshold (≥/≤) + delay | 408 | `StopTriggerElm.java:32` |
| `AmmeterElm` | `CircuitElm` | 2 | 1 (@ 0 V) | Series current meter (I / Irms) | 370 | `AmmeterElm.java:35` |
| `OhmMeterElm` | `CurrentElm` | 2 | 0 (inherits CurrentElm stamping) | Injects a probe current and measures V/I | 216 | `OhmMeterElm.java:10` |
| `WattmeterElm` | `CircuitElm` | 4 (I+,I−,V+,V−) | 2 (0 V on current pair + 0 V on voltage pair? see below) | P = Vd × I measurement | 420 | `WattmeterElm.java:30` |

## Zero-current probes vs series meters vs current-source meters

Three distinct MNA stamping patterns appear, each with a different observability
trick:

**1. Zero-current passive probes — no stamp at all, read node voltage only.**
- `LogicOutputElm.stamp()` (`LogicOutputElm.java:125`): empty unless
  `FLAG_PULLDOWN`, in which case it stamps a 1 MΩ resistor to ground
  (`stampResistor(getNode(0), 0, 1e6)`).
- `OutputElm`, `TestPointElm`, `DataRecorderElm`, `StopTriggerElm` have **no
  `stamp()`** — they inherit the `CircuitElm` no-op. They read
  `getNodeVoltage(0)` in `draw`/`stepFinished`; the solver happily leaves
  their node floating unless it is otherwise connected.
- `ProbeElm.stamp()` (`ProbeElm.java:302`) stamps a resistor only when
  `resistance != 0` (default 1e7 Ω, i.e. 10 MΩ). `getConnection(n1,n2)` is
  `(resistance != 0)` so with `R = 0` the two posts appear electrically
  isolated — a pure non-invasive voltmeter.

**2. Series ammeter — ideal zero-volt voltage source.**
- `AmmeterElm.stamp()` (`AmmeterElm.java:228`):
  `simulator().stampVoltageSource(getNode(0), getNode(1), voltSource, 0)`.
  The `0 V` value makes it a short, but because it is a voltage source the
  MNA solver assigns it a dedicated current row — that row's value is the
  measured current. `isWireEquivalent() == true` but the element is **not**
  optimized away, "because we need current calculated every timestep"
  (comment at `AmmeterElm.java:260`).
- `WattmeterElm.stamp()` (`WattmeterElm.java:159`) uses the same trick on
  *both* terminal pairs: voltage source from node 0→1 (current sense) and
  from node 2→3 (voltage sense). `getCurrent()` returns `currents[1]` and
  `getVoltageDiff()` returns `V2 − V0`; `getPower() = Vd × I`.
  `getConnection(n1,n2)` returns `(n1/2)==(n2/2)` — posts 0/1 are electrically
  tied, posts 2/3 are electrically tied, but the two pairs are isolated.

**3. Ohm-meter — current source variant.**
- `OhmMeterElm` extends `CurrentElm`, inheriting its current-source stamping
  (`CurrentElm.stamp()` `stampCurrentSource` at `CurrentElm.java:113`). The
  element injects a small known current and reports
  `R = Vd / current` (`OhmMeterElm.java:77`). When the path is broken,
  `CurrentElm.setBroken(true)` substitutes a 1e8 Ω fallback to avoid a
  singular matrix.

## Voltage driver patterns (input-side)

**4. Voltage-source-driven inputs.**
- `LogicInputElm.stamp()` stamps a voltage source between `getNode(0)` and
  the implicit ground reference (`voltSource` field, 1-source). `doStep()`
  calls `updateVoltageSource(...)` each step with `loV`, `hiV`, or a ternary
  midpoint. `hasGroundConnection(0) == true` — consistent with
  rail-like behavior. `isWireEquivalent == false`.
- `DataInputElm` extends `RailElm` (→ `VoltageElm`) and therefore inherits
  `getVoltageSourceCount() == 1` from the voltage-rail machinery.
  `getVoltage()` reads the buffered sample at `timeOffset/sampleLength`,
  optionally wrapping when `FLAG_REPEAT` is set.

## Display vs probe distinction

- **Probe** = element whose only job is to *sense* a node and format a
  number. The element is either zero-current (`LogicOutputElm`, `OutputElm`,
  `TestPointElm`, `DataRecorderElm`, `StopTriggerElm`) or has a configurable
  series resistance large enough to approximate a voltmeter (`ProbeElm`
  default 10 MΩ). None of them drive the circuit.
- **Display** = element whose main UX is the rendered glyph; the measurement
  is incidental. `DecimalDisplayElm` is the pure case: it inherits the
  `ChipElm` input-pin protocol (each input pin is a high-impedance voltage
  sampler, threshold controlled by `highVoltage / 2`), converts the bit
  vector to a decimal string, and draws it. `LogicOutputElm` straddles the
  boundary — it displays the level but also optionally loads the node
  through a 1 MΩ pull-down when `FLAG_PULLDOWN` is set.
- **`ScopeElm`** is neither — it is a **container for the `Scope` manager**
  (see Integration Points). It has no posts (`getPostCount() == 0`), no
  stamping, and `canViewInScope() == false` (a scope inside a scope is not
  allowed).

## External bridge elements (IO / JS)

Two elements cross the Java↔JS boundary via JSNI (`native … /*-{ … }-*/`):

- **`DataInputElm.fetchLoadFileData(elm, uploadElement)`** at
  `DataInputElm.java:169`. A GWT `FileUpload` widget is shown in the edit
  dialog; the JSNI method reads the selected file via browser `FileReader`
  and calls back into `DataInputElm.doLoadCallback(text, name)` which parses
  one double per line (skipping blanks and `#`-comments).
- **`DataRecorderElm.getBlobUrl(data)`** at `DataRecorderElm.java:103`. A
  JSNI method creates a `Blob` URL for the dumped samples and stores it on
  `$doc.recorderBlob`. The edit dialog builds an `<a>` element with a
  `Download` attribute set to `data-YYYYMMDD-HHMM.circuitjs.txt`.
- **`TestPointElm.alert(msg)`** at `TestPointElm.java:327` — JSNI wrapper of
  `$wnd.alert`, used only for user-facing errors.

These are the **only** IO-bridge native methods in the category. The rest of
the JS-facing surface uses the generic `CircuitElm.addJSMethods()` / Pin
façade documented in `domain-core__element-base.md`.

## DataRecorder buffer semantics

`DataRecorderElm` (file `DataRecorderElm.java`):
- **Ring buffer** with `data: double[]`, `dataCount` (capacity),
  `dataPtr` (next write index), `dataFull` (wrap flag). Default capacity
  10240 samples.
- **Write policy:** one sample per unique `simulator().timeStepCount`
  (`stepFinished` at line 84 guards against double-writes during Newton
  reiterations of the same timestep).
- **Wrap:** when `dataPtr >= dataCount`, `dataPtr = 0; dataFull = true`.
  Export dumps from `dataPtr` forward (oldest first) when `dataFull`,
  otherwise from 0 to `dataPtr`.
- **Output format:** header `# time step = <dt> sec\n` followed by one
  double per line (line 124-132). No timestamp column — time is implicit
  in the fixed `dt`.
- **No stamping** — the element is fully passive.
- **Reset** (`reset()` at line 45) clears `dataPtr`, `dataFull`,
  `lastTimeStepCount`. The buffer array is **not** zeroed; stale samples
  past `dataPtr` linger but are never read back because export respects
  the `dataFull` flag.

## StopTrigger conditions

`StopTriggerElm` (`StopTriggerElm.java:32`):
- **Fields:** `triggerVoltage` (threshold), `type` (0 = `>=`, 1 = `<=`),
  `delay` (seconds after trigger before actual stop), plus state flags
  `triggered`, `stopped`, and `triggerTime` (simulator time at trigger).
- **Logic** (`stepFinished` at line 91):
  1. Read `v = getNodeVoltage(0)`.
  2. If not yet triggered and the comparison fires, set `triggered = true`
     and capture `triggerTime = simulator().t`.
  3. If `triggered` and `simulator().t >= triggerTime + delay`, clear
     `triggered`, set `stopped = true`, and call
     `circuitDocument.setSimRunning(false)`.
- **One-shot semantics:** after firing, `triggered` is cleared while
  `stopped` remains true until next `stepFinished` (which resets
  `stopped = false` at the top of the method). So the visual "stopped"
  highlight appears for one frame only. `reset()` (line 55) clears
  `triggered`; `stopped` is not cleared by reset.
- **No edge detection** — if the input is already past the threshold when
  simulation starts, it fires on the first `stepFinished`.
- **Gap vs. expected:** there is no level/edge-select flag, no hold-off
  between re-triggers, no message on halt.

## Integration points

### Scope manager ↔ ScopeElm coupling

- **`Scope` (client/Scope.java)** is the scope manager — owns waveform
  buffers, draws axes, handles user interaction with a scope pane.
  `ScopeElm` **owns** a `Scope` instance via its `public Scope elmScope`
  field (`ScopeElm.java:31`). The manager knows how to serialize / deserialize
  itself (`elmScope.dump()` / `elmScope.undump()` in `ScopeElm.dump()` /
  ctor).
- **Two scope populations:** the `CircuitDocument` holds a `ScopeManager`
  with "side pane" scopes, and in parallel the element list contains zero
  or more `ScopeElm`s. `MenuManager.java:840` explicitly sums them:
  `scopeManager.scopeCount + simulator.countScopeElms()`. `ActionManager`
  (`ActionManager.java:388-450`) provides "move to scope elm" / "stack on
  scope elm" / `deleteUnusedScopeElms()` operations that shuttle between
  the two populations.
- **Per-step update:** `CircuitSimulator` iterates `ScopeElm`s and calls
  `stepScope()` (`ScopeElm.java:88`) which forwards to `elmScope.timeStep()`
  to append the current sample to its internal buffer.
- **Rendering trick:** `ScopeElm.draw()` escapes the outer document
  transform (`g.scale(1/transform[0], 1/transform[3])` then
  `g.translate(-transform[4], -transform[5])`) so the embedded scope draws
  in **screen coordinates** independent of document zoom/pan. The bounding
  box is recomputed from endpoints via `setScopeRect()`.
- **Dump encoding:** because the scope's own dump contains spaces, `dump()`
  replaces `' '` → `'_'` and strips the unused `"o_"` prefix
  (`ScopeElm.java:114-115`). Import reverses with a `"_"` tokenizer.

### Editor ↔ Output/Probe coupling

- **Plot X/Y markers:** `CircuitEditor.plotXElm` and `plotYElm` point to an
  `OutputElm` or `ProbeElm`. In `OutputElm.draw()` (`OutputElm.java:78-81`)
  and `ProbeElm.draw()` (`ProbeElm.java:152-155`) the label changes to "X"
  or "Y" when the element matches. This is the X-Y-plot feature at the
  editor level.

### CircuitDocument ↔ StopTrigger

- `StopTriggerElm.stepFinished` calls
  `circuitDocument.setSimRunning(false)` directly — the single element in
  this category that mutates document-level simulator state.

### ChipElm ↔ DecimalDisplayElm

- Standard `ChipElm` input-only pattern. `getVoltageSourceCount() == 0`
  means `stamp()` is a no-op (because ChipElm's default stamp only iterates
  outputs). `setupPins()` places `bitCount` input pins on the west side
  with names `"I0"..."I{n-1}"`, ordered LSB-at-bottom.

## Validation rules

- `ProbeElm.getConnection(n1, n2)` returns `resistance != 0`
  (`ProbeElm.java:312`) — zero-R probe is topologically isolated.
- `AmmeterElm.isWireEquivalent() == true` but NOT
  `isRemovableWire() == true` (not overridden; default false). The element
  must not be optimized out because the simulator needs the vs-row current
  each step.
- `WattmeterElm.hasGroundConnection(n1) == false` — wattmeter is not a
  ground reference (otherwise having both sense pairs grounded would break
  the circuit).
- `WattmeterElm.canViewInScope() == true` (overridden to true since
  `getPostCount() > 2` would default to false).
- `WattmeterElm.canFlipX/Y() == false` — geometry-aware orientation only.
- `ScopeElm.canViewInScope() == false` — you cannot add a ScopeElm to a
  scope.
- `ScopeElm.getPostCount() == 0` — purely graphical; no electrical
  participation.
- `DataInputElm.getShortcut() == 0` — not reachable via keyboard.
- `LogicInputElm.hasGroundConnection(0) == true` — consistent with it being
  a voltage-source-rail element.
- `DecimalDisplayElm.setChipEditValue` clamps `bitCount` to `[1, 16]`
  (`DecimalDisplayElm.java:110`) even though `EditInfo` declares max 8 —
  silent widening.
- `TestPointElm.stepFinished` uses `simulator().timeStepCount` guard against
  double-sampling during Newton iterations (`TestPointElm.java:218-220`);
  `ProbeElm.stepFinished` does NOT guard — it samples once per
  `stepFinished` call which may be more than once per timestep in
  non-converged paths.
- `StopTriggerElm.reset()` clears `triggered` but not `stopped` — benign
  because `stopped` is cleared at the top of `stepFinished`.
- `DataInputElm.fileNumCounter` and static `dataFileMap` survive element
  deletion; `clearCache()` (`DataInputElm.java:216`) is the only way to
  release memory.

## Dump-type summary

| Code | Element | Notes |
|---|---|---|
| `'L'` (76) | `LogicInputElm` | Single char, via SwitchElm family |
| `'M'` (77) | `LogicOutputElm` | Single char |
| `'O'` (79) | `OutputElm` | Single char |
| `'p'` (112) | `ProbeElm` | Single char |
| 210 | `DataRecorderElm` | Numeric |
| 216 | `OhmMeterElm` | Numeric |
| 368 | `TestPointElm` | Numeric |
| 370 | `AmmeterElm` | Numeric |
| 403 | `ScopeElm` | Numeric; payload also contains embedded Scope.dump() |
| 408 | `StopTriggerElm` | Numeric |
| 419 | `DecimalDisplayElm` | Numeric; inherits ChipElm prefix + appends bitCount |
| 420 | `WattmeterElm` | Numeric |
| 424 | `DataInputElm` | Numeric; inherits VoltageElm prefix, appends sampleLength+scaleFactor+fileNum |

## Issues

1. **`WattmeterElm` voltage-sense pair is a 0 V source, not a high-Z
   pair.** `stamp()` (line 159) places `stampVoltageSource(getNode(2),
   getNode(3), voltSources[1], 0)` — this **shorts** the V+ / V− posts.
   A real wattmeter's voltage coil is high-impedance. This means placing
   the wattmeter in series with a load you want to measure across will
   bypass that load. The element seems designed for the topology
   `load-inside-current-loop, and V-pair across the load`, where the V
   short is acceptable because the load is the reference; but the dialog
   does not document this. Worth confirming against circuitjs1.js reference.
2. **`ProbeElm.stepFinished` is not guarded against multiple Newton
   iterations per timestep** (`TestPointElm` is). RMS averages over count
   = "number of `stepFinished` calls in a cycle", which may over-count on
   non-linear circuits.
3. **Period / Pulse-width timing uses wall-clock `System.currentTimeMillis()`**
   in both `ProbeElm.stepFinished` (line 244-247) and
   `TestPointElm.stepFinished` (line 239-242). Measurements break when
   the simulator is paused, sped up, or slowed down, because they drift
   with real time, not `simulator().t`.
4. **`ProbeElm` and `TestPointElm` duplicate ~200 lines of waveform-stat
   code verbatim.** Same `TP_VOL/TP_RMS/TP_MAX/...` enum, same increasing/
   decreasing edge-tracking, same rms integrator. Should share an
   `WaveformStats` helper.
5. **`DataInputElm.dataFileMap` is keyed by `fileNum` and lives forever.**
   `clearCache()` is the only release path; cut/paste → undo cycles that
   repeatedly copy a DataInputElm can leak an arbitrary number of large
   `ArrayList<Double>`s. There is no LRU or reference counting.
6. **`DataRecorderElm.getInfo` disagrees with buffer semantics.** The info
   string reports `dataFull ? dataCount : dataPtr` but this is only the
   *occupied* count; if `dataFull`, samples have been overwritten and the
   "count" overstates real new-samples-received.
7. **`StopTriggerElm` is missing edge-sensitivity, hysteresis, and
   re-armable mode.** `reset()` clears `triggered` (so it can fire again
   after Reset), but during a continuous run it fires exactly once per
   level-crossing. No user-visible notification — the halt is silent.
8. **`OutputElm.getJsonProperties` stringifies scale as "auto"/"V"/"mV"/"uV"
   but `applyJsonProperties` hard-codes the back-conversion** — the
   constants `SCALE_AUTO / 1 / 2 / 3` leak into the public JSON shape,
   and a future scale (e.g. `nV`) would need additions in two places.
9. **`LogicOutputElm` flag `FLAG_PULLDOWN` is labelled "Current Required"**
   in the edit dialog (`LogicOutputElm.java:148`) — non-obvious name; it
   actually adds a 1 MΩ resistor to ground so logic-output nodes that would
   otherwise be floating (e.g. a bare gate output) have a DC path.
10. **`AmmeterElm` flag `FLAG_SHOWCURRENT` is defined but never consulted**
    (unused — `drawValues` is always called at line 218). Stale code.
11. **`DataInputElm` fileNum field can collide across circuits because
    `fileNumCounter` is static and never reset on circuit load.** Two
    different circuits loaded in sequence can both reference the same
    fileNum → wrong data attached.
12. **`ScopeElm.dump()` can return `null`** (line 112-113) if
    `elmScope.dump()` returns null — the rest of the dump pipeline is not
    null-tolerant; this would corrupt the file.
13. **`DecimalDisplayElm.getPostCount() == bitCount`** but the element is
    in practice treated as a chip (extends `ChipElm`). If `bitCount > 8`
    via JSON import while the dialog still caps at 8, the display stays
    wider than the dialog admits.

## Concept boundary — single or split?

**Recommendation: split into two adjacent concepts.** The category name
("io-probes-meters-displays") is already a triple, and the three roles
share no physics. Specifically:

1. **`cat-probes-and-meters`** — elements that measure existing quantities
   in the circuit without injecting user input. Grouped by how they stamp:
   - Zero-current probes: `ProbeElm`, `TestPointElm`.
   - Series meters (0 V voltage source trick): `AmmeterElm`,
     `WattmeterElm`.
   - Current-source meter: `OhmMeterElm`.
   - Thin-measure-only display-tied probes: `LogicOutputElm`,
     `OutputElm`.
2. **`cat-io-and-displays`** — elements that bridge the simulation to the
   outside world or accumulate time-series:
   - Value-drivers: `LogicInputElm`, `DataInputElm`.
   - Data-display: `DecimalDisplayElm`.
   - Logging / halting / embedded scope: `DataRecorderElm`,
     `StopTriggerElm`, `ScopeElm`.

Rationale for the split:
- Shared code exists **only inside each half**: the three meters share the
  "ideal zero-V source for current sensing" pattern; the two probes
  (`ProbeElm`, `TestPointElm`) share ~200 lines of waveform-statistics
  code; `DataRecorder` / `StopTrigger` / `ScopeElm` all rely on
  `stepFinished` rather than `doStep`.
- The IO-and-displays half has the only JSNI bridge points, the only
  document-level mutation (`StopTrigger.setSimRunning(false)`), and the
  only embedded-sub-UI (`ScopeElm`). These are conceptually heavier than
  the probes-and-meters half and benefit from their own section.
- Keeping a single concept forces the probes ↔ IO narrative to switch
  repeatedly, and the override tables stop being comparable (posts 0-4,
  voltage sources 0-2, dump types in five different number ranges).

If a single concept is kept for brevity, the **anchor pattern** is *output-path
orientation* — every element's primary job is to carry a value
into/out-of the netlist, observable only via node voltages and one of
three stamp tricks (no-stamp / 0 V source / 1 MΩ pulldown / resistor).

## File paths

- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/element/LogicInputElm.java`
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/element/LogicOutputElm.java`
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/element/DataInputElm.java`
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/element/OutputElm.java`
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/element/DecimalDisplayElm.java`
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/element/ScopeElm.java`
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/element/ProbeElm.java`
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/element/TestPointElm.java`
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/element/DataRecorderElm.java`
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/element/StopTriggerElm.java`
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/element/AmmeterElm.java`
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/element/OhmMeterElm.java`
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/element/WattmeterElm.java`
