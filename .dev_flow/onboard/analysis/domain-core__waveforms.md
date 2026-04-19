# Module Analysis: domain-core / waveforms

> **Path:** src/main/java/com/lushprojects/circuitjs1/client/element/waveform/
> **Layer:** 2 (inside SCC-A — domain core)
> **Analyzed:** 2026-04-18
> **Files:** 9 source files, 0 test files

## Purpose

Strategy-pattern signal generators for voltage- and current-rail elements. Each
concrete class encapsulates one waveform shape (DC, AC sine, square, triangle,
sawtooth, pulse, pseudo-noise, slider-controlled "var") and owns the logic for
computing `V(t)`, rendering the icon inside the source's circle, exposing
edit-dialog parameters, serializing to JSON, and (optionally) overriding how
the owning element stamps itself into the MNA matrix. The module is pulled
out of `element/` exactly so `VoltageElm` and its subclasses can swap behaviour
at runtime via a single `waveformInstance` field instead of a 7-way `switch`.

## Class hierarchy

```
Waveform                                  (abstract; Waveform.java:11)
├── DCWaveform                            (WF_DC = 0)
├── ACWaveform                            (WF_AC = 1)
├── SquareWaveform                        (WF_SQUARE = 2)
├── TriangleWaveform                      (WF_TRIANGLE = 3)
├── SawtoothWaveform                      (WF_SAWTOOTH = 4)
├── PulseWaveform                         (WF_PULSE = 5)
├── NoiseWaveform                         (WF_NOISE = 6)
└── VarWaveform                           (WF_VAR = 7)
```

`Waveform.create(type, old)` (Waveform.java:31) is the factory; it dispatches
on the integer `type`, then calls `copyFrom(old)` to preserve shared parameters
across type changes. Note: `WF_VAR` is marked legacy — the factory returns a
`DCWaveform` for it (Waveform.java:42); the real `VarWaveform` class still
exists but is only instantiated directly by `VarRailElm` / when it constructs
its own instance. `create` also falls back to `DCWaveform` for any unknown
type (Waveform.java:43).

## Key Entities

### Waveform (abstract base)

- **Type:** abstract class
- **File:** src/main/java/com/lushprojects/circuitjs1/client/element/waveform/Waveform.java:11
- **Public constants:**
  - Flag bits packed into `VoltageElm.flags`: `FLAG_COS = 2` (legacy cosine
    marker → auto-translated to `phaseShift = π/2` on load), `FLAG_PULSE_DUTY = 4`
    (asserts the dumped `dutyCycle` is authoritative; pre-flag circuits are
    retrofitted to `defaultPulseDuty = 1/(2π)` in `VoltageElm:72`).
  - Waveform-type ordinals `WF_DC=0 … WF_VAR=7` — these are the values written
    to the text dump and used as the switch index in `create`.
- **Public fields (shared parameter model):**
  - `double frequency = 40` — hertz; for `VarWaveform` this slot is repurposed
    as the current output voltage.
  - `double maxVoltage = 5` — amplitude (DC nominal, AC peak, pulse/square high,
    triangle/sawtooth peak, noise ±max, var upper bound).
  - `double freqTimeZero = 0` — time origin used by `w(elm)` so frequency
    changes don't glitch phase (set in `VoltageElm.reset()` at line 102).
  - `double bias = 0` — DC offset added to all generators (for `VarRailElm`
    this is the min-voltage bound).
  - `double phaseShift = 0` — radians, added inside `w(elm)`.
  - `double dutyCycle = 0.5` — 0…1 fraction; only meaningful for square/pulse.
  - `double noiseValue = 0` — scratch state for `NoiseWaveform.getVoltage`.
- **Abstract methods:**
  - `int getType()` (Waveform.java:51) — returns the class's `WF_*` ordinal.
  - `double getVoltage(VoltageElm elm)` (:53) — per-step V(t); each subclass
    short-circuits on `elm.circuitDocument.circuitInfo.dcAnalysisFlag` and
    returns `bias` for the DC-operating-point pass.
  - `void draw(Graphics g, Point center, VoltageElm elm)` (:55) — icon inside
    the circle.
  - `void getInfo(VoltageElm elm, String[] arr, int i)` (:61) — info-bubble
    lines (starts at index `i`; slot 0 is the element name).
  - `EditInfo getEditInfo(VoltageElm elm, int n)` (:63) /
    `void setEditValue(VoltageElm elm, int n, EditInfo ei)` (:65) — index-based
    dialog rows. Slot `n==1` is reserved by `VoltageElm` for the waveform-type
    chooser (VoltageElm.java:239), so subclasses never return rows for it.
  - `String getJsonTypeName()` (:67) — JSON element-type label
    (`VoltageSourceAC`, `VoltageSourceDC`, etc.).
- **Concrete (overridable) methods with default behaviour:**
  - `drawRail(Graphics g, RailElm elm)` (:57) — default draws the element's
    waveform at `geom().getPoint2()`.
  - `getJsonProperties` / `applyJsonProperties` (:69 / :80) — JSON
    serialization of max_voltage, dc_offset (only if != 0), frequency,
    phase_shift (deg, only if != 0), duty_cycle.
  - `copyFrom(Waveform other)` (:88) — copies all 7 shared fields.
  - `getJsonRailTypeName()` (:99) — default `"Rail"`; overridden by AC/Square/Var.
  - `isDC()` (:103) — false; DCWaveform returns true.
  - `isPulse()` (:107) — false; PulseWaveform returns true.
  - `stamp(VoltageElm)` (:111) — default stamps a *free* voltage source
    between node 0 and node 1. DCWaveform overrides to pin the value
    immediately at stamp time.
  - `stampRail(RailElm)` (:115) — default stamps between ground (node 0) and
    the rail's single node. DCWaveform overrides with a static value.
  - `doStep(VoltageElm)` (:122) — default calls
    `updateVoltageSource(n0, n1, voltSource, getVoltage())` *unless* `isDC()`
    (DC sources don't need per-step updates because they're stamped as
    fixed sources).
  - `hasCircle()` (:131) — true everywhere except DCWaveform.
  - `showFrequency()` (:138) — true except Noise and Var.
  - `usesShortLeads()` (:145) — mirrors `isDC()` by default; VarWaveform
    forces true so `VarRailElm` gets battery-style leads.
  - `stepFinished(VoltageElm)` (:149) — no-op default; NoiseWaveform uses it
    to advance `noiseValue` via `RandomUtils.getRandom().nextDouble()`.
  - `w(VoltageElm elm)` (:152, protected) — the universal angular-time helper:
    `2π · (simulator.t − freqTimeZero) · frequency + phaseShift`. All periodic
    generators call `w(elm)` and then either `Math.sin`, `% PI_2`, etc.
- **Invariants (induced by callers):**
  - `waveformInstance.getType() == VoltageElm.waveform` — enforced by
    `VoltageElm.createWaveformInstance()` (line 110) which re-reads the type
    back out after `create` normalizes it.
  - `dutyCycle ∈ [0, 1]`; UI editor multiplies by 100 on the way out and
    `ei.value * .01` on the way in.
  - `phaseShift` stored in **radians** internally, shown in **degrees** in
    the editor.

### DCWaveform
- **File:** DCWaveform.java:11
- **Overrides:** `getVoltage` returns `maxVoltage + bias` (no time dependence).
  `draw` is a no-op (`VoltageElm.draw` renders DC cells battery-style before
  calling `waveformInstance.draw`). `drawRail` renders a `RailElm` label with
  the sign (`+`), using `showFormat` for |v|<1 V and `getShortUnitText`
  otherwise.
- **Unique overrides:** `hasCircle() = false`; `isDC() = true`;
  `stamp`/`stampRail` use the *value-fixed* `stampVoltageSource(n0,n1,vs,V)`
  overload (DCWaveform.java:83, 88) so the value never needs to be re-applied
  per step. `getJsonProperties` omits frequency/phase/duty-cycle entirely.
- **Parameter set:** only `maxVoltage`, `bias`.
- **Numerical behavior:** constant output. `dcAnalysisFlag` path does not
  short-circuit (DC is already DC).

### ACWaveform
- **File:** ACWaveform.java:9
- **Overrides:** `getVoltage` → `sin(w(elm)) · maxVoltage + bias`, with
  DC-operating-point early-out returning `bias` only.
- **Parameter set:** `maxVoltage`, `bias`, `frequency`, `phaseShift`.
- **Edit slots:** n=0 Max Voltage, n=2 DC Offset, n=3 Frequency (4–500 Hz),
  n=4 Phase (°, −180…180). n=1 reserved for the type chooser.
- **JSON rail type:** `ACRail` (override of default `Rail`).
- **Info:** shows `Vrms = Vmax/√2` when bias == 0 and no offset; shows
  wavelength (`c/f`) when f > 500 Hz.

### NoiseWaveform
- **File:** NoiseWaveform.java:11
- **State advance:** `stepFinished` pulls
  `RandomUtils.getRandom().nextDouble() * 2 − 1` and scales by
  `maxVoltage` then biases — this advances the shared singleton `Random`
  once per simulation step and writes `noiseValue` on the base class.
- **getVoltage:** returns latched `noiseValue`; no DC-analysis branch
  (noise is zero-mean so DC-op-point behaves naturally via last sample).
- **Icon:** deterministic xorshift hashed by `elm.getElementId().hashCode()`
  so it doesn't flicker on every redraw (NoiseWaveform.java:61).
- **Parameter set:** `maxVoltage`, `bias` only. No frequency / phase / duty.
- **Overrides:** `showFrequency() = false`, `hasCircle() = true`.

### PulseWaveform
- **File:** PulseWaveform.java:9
- **getVoltage:** `((w(elm) mod 2π) < 2π·dutyCycle) ? maxVoltage+bias : bias`
  — i.e. asymmetric high/low (low rail at `bias`, not `bias-maxVoltage`),
  which is the key numerical difference from SquareWaveform.
- **Overrides:** `isPulse() = true`; `getJsonProperties` adds `duty_cycle`
  on top of super.
- **Parameter set:** `maxVoltage`, `bias`, `frequency`, `phaseShift`,
  `dutyCycle` (Edit slots 0, 2, 3, 4, 5).

### SawtoothWaveform
- **File:** SawtoothWaveform.java:9
- **getVoltage:** `bias + (w(elm) mod 2π) · maxVoltage/π − maxVoltage`
  — ramp from `bias − maxVoltage` to `bias + maxVoltage` each cycle.
- **Parameter set:** same 4 as AC.

### SquareWaveform
- **File:** SquareWaveform.java:10
- **getVoltage:** `bias ± maxVoltage` flipping at `dutyCycle` fraction of
  2π — symmetric (unlike PulseWaveform, low rail is `bias − maxVoltage`).
- **drawRail override:** if the owning rail has `RailElm.FLAG_CLOCK`, label
  becomes `"CLK"`.
- **JSON rail type:** `SquareRail`.
- **Parameter set:** 5 (amp, bias, freq, phase, duty).

### TriangleWaveform
- **File:** TriangleWaveform.java:9
- **getVoltage:** uses helper `triangleFunc(x)` — piecewise linear: `[0,π]`
  ramps `−1 → +1`, `[π, 2π]` ramps `+1 → −1`. Returns `bias + triangleFunc(w mod 2π) · maxVoltage`.
- **Parameter set:** 4 (no duty).

### VarWaveform
- **File:** VarWaveform.java:10
- **getVoltage:** when the owning element is a `VarRailElm`, reads
  `vrelm.slider.getValue()` (0–100) and remaps to `[bias, maxVoltage]`,
  **storing the current output back into `frequency`** (unusual — the field
  is repurposed since there's no real Hz). Otherwise falls through to a
  plain DC value.
- **Edit slots are different:** n=0 Min Voltage (stored in `bias`),
  n=2 Max Voltage, n=3 Slider Text (bound to `vrelm.sliderText` /
  `vrelm.label`).
- **Overrides:** `usesShortLeads() = true`, `showFrequency() = false`.
- **JSON rail type:** `VariableRail`.

## Public Contracts

The five abstract methods (`getType`, `getVoltage`, `draw`, `getInfo`,
`getEditInfo`, `setEditValue`, `getJsonTypeName`) plus the overridable
concretes above form the full contract. `VoltageElm` and `RailElm` call
into this surface as follows:

| Caller site | Waveform method | Notes |
|---|---|---|
| `VoltageElm.stamp()` (:118) | `stamp(this)` | Per-cycle MNA stamp |
| `VoltageElm.doStep()` (:122) | `doStep(this)` | Per-step value update (skipped for DC) |
| `VoltageElm.stepFinished()` (:126) | `stepFinished(this)` | Advances noise seed |
| `VoltageElm.getVoltage()` (:130) | `getVoltage(this)` | Sampled into simulator/scopes |
| `VoltageElm.setPoints()` (:137) | `usesShortLeads()` | Decides lead length |
| `VoltageElm.draw()` (:200, :204) | `hasCircle()`, `draw(g, center, this)` | Element rendering |
| `VoltageElm.draw()` (:205) | `showFrequency()` | Whether to annotate f under the circle |
| `VoltageElm.getInfo(arr)` (:230) | `getInfo(this, arr, 3)` | Fills info-bubble |
| `VoltageElm.getEditInfo(n)` (:252) | `getEditInfo(this, n)` | Dialog rows beyond slot 1 |
| `VoltageElm.setEditValue(n, ei)` (:282) | `setEditValue(this, n, ei)` | Apply dialog edit |
| `VoltageElm.getDumpClass` → JSON (:287) | `getJsonTypeName()` | Export |
| `VoltageElm` JSON out/in (:293, :305) | `getJsonProperties`, `applyJsonProperties` | Export / import |
| `VoltageElm` chooser switch (:260–276) | `create`, `isDC`, `isPulse` | Type change resets bias / duty-cycle |
| `RailElm.draw` (:98) | `drawRail(g, this)` | Rail-specific rendering |
| `RailElm.stamp` (:113) | `stampRail(this)` | Rail stamp |
| `RailElm.doStep` (:117) | `!isDC()` guard | Rails only update non-DC |
| `RailElm.getJsonType` (:136) | `getJsonRailTypeName()` | JSON rail label |

## Parameter Model

| Parameter | Base field? | Used by | Default | Range / invariants |
|---|---|---|---|---|
| `maxVoltage` | base | all 8 | `5.0` V | Edit UI bounds −20…20 V; AC=peak, DC=value, Noise=±max |
| `bias` | base | all 8 | `0.0` V | −20…20 V; for VarWaveform used as **min voltage** bound |
| `frequency` | base | AC, Square, Triangle, Sawtooth, Pulse | `40.0` Hz | Edit 4…500 Hz; **VarWaveform repurposes this field as the current output voltage** |
| `phaseShift` | base | AC, Square, Triangle, Sawtooth, Pulse | `0.0` rad | Stored radians, shown degrees ±180; legacy `FLAG_COS` sets π/2 |
| `dutyCycle` | base | Square, Pulse | `0.5` | [0, 1]; UI shows 0–100%; `PulseWaveform` legacy default is `1/(2π)` when `FLAG_PULSE_DUTY` not set |
| `freqTimeZero` | base | all periodic (via `w()`) | `0` | Set by `VoltageElm.reset()`; anti-glitch time origin |
| `noiseValue` | base | Noise only | `0` | Latched by `stepFinished`, read by `getVoltage` |

Parameter presence in edit dialog (slot indices; slot 1 is the VoltageElm type chooser):

| Waveform | n=0 | n=2 | n=3 | n=4 | n=5 |
|---|---|---|---|---|---|
| DC | Voltage | DC Offset | — | — | — |
| AC | Max V | DC Offset | Freq | Phase | — |
| Square | Max V | DC Offset | Freq | Phase | Duty% |
| Pulse | Max V | DC Offset | Freq | Phase | Duty% |
| Triangle | Max V | DC Offset | Freq | Phase | — |
| Sawtooth | Max V | DC Offset | Freq | Phase | — |
| Noise | Max V | DC Offset | — | — | — |
| Var (VarRailElm) | Min V (= bias) | Max V | Slider Text | — | — |

## Serialization Format

### Text dump (legacy Falstad format)

Written by `VoltageElm.dump()` (VoltageElm.java:88–98) via the variadic
`CircuitElm.dumpValues` (CircuitElm.java:426). Shape:

```
<dumpType> <x1> <y1> <x2> <y2> <flags> <waveform> <frequency> <maxVoltage> <bias> <phaseShift> <dutyCycle>
```

- `dumpType` is per-subclass (`'v'` for plain VoltageElm, overridden by DC/AC/etc.).
- `flags` is where `FLAG_COS` (=2) and `FLAG_PULSE_DUTY` (=4) live; set in
  `dump()` for pulse waveforms (line 91), auto-translated to `phaseShift=π/2`
  and cleared on load (line 66–68).
- The trailing 5 values (`freq, maxV, bias, phaseShift, dutyCycle`) are always
  written regardless of which waveform — unused slots carry whatever the
  instance last held.
- Undump is the mirror constructor (`VoltageElm.java:51–77`). Parsing is
  try/catch wrapped: if any token is missing, it falls back to
  `createWaveformInstance()` with whatever was parsed so far.
- `VarRailElm` appends its slider label after this prefix; `ExtVoltageElm`
  wraps the super `dump` and appends `CustomLogicModel.escape(name)`
  (ExtVoltageElm.java:47–48).

### JSON format

Handled by `VoltageElm.writeJson`/`applyJsonProperties` bridge (lines 287, 293,
305) delegating to `Waveform.getJsonProperties` / `applyJsonProperties`.
Properties (only non-default values written):

- `max_voltage` — string with unit, via `VoltageElm.getUnitText(v, "V")`.
- `dc_offset` — string with unit, only when `bias != 0`.
- `frequency` — string with unit `"Hz"` (not for DC/Noise/Var).
- `phase_shift` — degrees, only when != 0 (not for DC/Noise/Var).
- `duty_cycle` — numeric, added only by Square/Pulse `super.getJsonProperties`.
- JSON element-type names: `VoltageSourceDC` / `VoltageSourceAC` /
  `VoltageSourceSquare` / `VoltageSourceTriangle` / `VoltageSourceSawtooth` /
  `VoltageSourcePulse` / `VoltageSourceNoise` / `VoltageSourceVar`.
- JSON rail-type names: `"Rail"` default, overridden to `ACRail` /
  `SquareRail` / `VariableRail`.

## Validation Rules

- `Waveform.create(type)` defaults to `DCWaveform` on unknown / `WF_VAR`
  types (Waveform.java:42–43) — defensive fall-through.
- `VoltageElm(… StringTokenizer)` constructor wraps parsing in try/catch
  and re-creates `waveformInstance` on any exception (line 63–65) —
  guards against truncated or pre-existing legacy dump lines.
- `FLAG_COS` legacy translation: if the bit is set, it is cleared and
  `phaseShift` is forced to `π/2` (VoltageElm.java:66–69) — one-way
  migration.
- `FLAG_PULSE_DUTY`: if **not** set and the waveform is pulse, `dutyCycle`
  is coerced to `defaultPulseDuty = 1/(2π)` (≈0.159), which matches the
  hard-coded old behaviour before duty-cycle became editable.
- `createWaveformInstance()` re-syncs the integer `waveform` field with
  `waveformInstance.getType()` (line 110), so passing an out-of-range
  type silently normalizes.
- On type change in the editor (VoltageElm.java:268–276): switching *into*
  DC zeros `bias`; switching *into* Pulse sets `dutyCycle = defaultPulseDuty`;
  switching *out of* Pulse restores `dutyCycle = 0.5`.
- `VarWaveform.getEditInfo` guards `elm instanceof VarRailElm` before
  exposing any fields (VarWaveform.java:48) — a stray `VarWaveform`
  attached to a plain `VoltageElm` returns null for all rows.
- Edit UI ranges: `maxVoltage`/`bias` clamped to ±20 V; frequency 4…500 Hz;
  phase −180…180°; duty 0…100%.
- `Waveform.getJsonProperties` omits `dc_offset` / `phase_shift` when they
  equal the default (0) — deliberate to keep round-tripping of minimal
  JSON files compact.

## State Transitions

- **NoiseWaveform:** per step, `stepFinished(elm)` writes
  `noiseValue := (rand·2−1)·maxVoltage + bias` (NoiseWaveform.java:101–103).
  `getVoltage` returns the previous-step latch — so noise is sample-and-held
  over the entire solver Newton loop (avoids changing mid-iteration and
  breaking convergence). The source of randomness is the singleton in
  `RandomUtils`.
- **Phase accumulator:** there is **no** per-step phase accumulator; all
  periodic generators recompute `w(elm) = 2π·(simulator.t − freqTimeZero)·frequency + phaseShift`
  from the simulator's absolute time. `freqTimeZero` is reset to 0 on
  `VoltageElm.reset()` (line 102) — users who retune frequency mid-run will
  see a one-shot phase jump unless the hosting element resets
  `freqTimeZero`.
- **VarWaveform:** `getVoltage` reads the live slider every call and writes
  the result back into `frequency` — a *write during read* side effect,
  tolerated because no caller actually reads `frequency` for VarWaveform.
- **DCWaveform:** no state evolution — stamp is done once with a fixed
  value and `doStep` is skipped (`isDC()` guard).

## Integration Points

### Depends on

**Intra-project imports** (15 `element/…` edges, matching dependency_graph.md):

| Waveform file | Imports from `element/` |
|---|---|
| Waveform.java | `CircuitElm`, `RailElm`, `VoltageElm` (3) |
| ACWaveform.java | `VoltageElm` (1) |
| DCWaveform.java | `VoltageElm`, `RailElm` (2) |
| NoiseWaveform.java | `VoltageElm`, `CircuitElm` (2) |
| PulseWaveform.java | `VoltageElm` (1) |
| SawtoothWaveform.java | `VoltageElm` (1) |
| SquareWaveform.java | `VoltageElm`, `RailElm` (2) |
| TriangleWaveform.java | `VoltageElm` (1) |
| VarWaveform.java | `VoltageElm`, `VarRailElm` (2) |

Total: **15 `waveform → element` edges**, confirming the
`dependency_graph.md` count.

Beyond imports, waveforms reach into the concrete element API for:

- `elm.simulator().stampVoltageSource(…)` / `updateVoltageSource(…)` — MNA matrix hooks.
- `elm.getNode(0|1)`, `elm.voltSource` — indices into the solver.
- `elm.circuitDocument.circuitInfo.dcAnalysisFlag` — DC-op-point branch.
- `elm.circuitDocument.circuitInfo.showResistanceInVoltageSources` — info-bubble toggle.
- `elm.current`, `elm.getVoltage()`, `elm.geom().getPoint2()`, `elm.needsHighlight()`,
  `elm.setPowerColor(g, false)`, `elm.drawWaveform(…)`, `elm.getElementId()`,
  `elm.flags` (RailElm.FLAG_CLOCK).
- `VoltageElm.getUnitText`, `getVoltageText`, `selectColor`, `drawThickLine`,
  `PI`, `PI_2` — static helpers (waveforms reach back for the very same
  formatters `VoltageElm` itself uses).
- `RailElm.showFormat`, `getShortUnitText`, `foregroundColor`, `selectColor`,
  `drawRailText` — rail-specific rendering.
- `VarRailElm.slider`, `sliderText`, `label`, `cirSim()` — slider widget hooks.
- `CircuitElm.getJsonDouble`, `CircuitElm.foregroundColor` — shared base utils.

**Imports from `util/`:** one edge total (`DCWaveform` imports
`util.Locale` for `Locale.ohmString`). Matches dependency_graph.md entry
"`element/waveform` → `util` (1)".

**Imports from `dialog/`:** each of the 8 concrete waveforms + `Waveform`
imports `dialog.EditInfo` (9 edges — matches dependency_graph.md's 9
`element/waveform → dialog` entries).

**Imports from root `client/`:** `Graphics`, `Point`, `RandomUtils` (the
latter only in `NoiseWaveform`).

### Used by

Grep of `import com.lushprojects.circuitjs1.client.element.waveform.*` —
**13 external consumers** (excluding intra-waveform files):

- `VoltageElm.java` — the pivot; owns `Waveform waveformInstance` and drives
  stamp/step/draw/dump/JSON.
- `RailElm.java` — single-node variant; delegates `stamp`, `draw`, `doStep`,
  `getJsonType` through the waveform.
- `DCVoltageElm.java`, `ACVoltageElm.java`, `ACRailElm.java`,
  `SquareRailElm.java`, `VarRailElm.java`, `ClockElm.java`, `NoiseElm.java`
  — convenience subclasses that hard-code a `WF_*` in their `super(…)` call.
- `AntennaElm.java`, `ExtVoltageElm.java`, `AudioInputElm.java`,
  `DataInputElm.java` — subclasses of VoltageElm / RailElm that reuse the
  waveform machinery for their own source type (antenna uses `WF_AC`,
  ext/audio/data override the per-step value but still stamp via the
  waveform).
- `FMElm.java`, `AMElm.java` — also import the base class (2 references
  each in grep count), for the `WF_*` constants.

The dependency_graph.md counts **12 `element → element/waveform` edges**.
The 13 importers above exceed 12 because `RailElm.java` uses a wildcard
import (`import … waveform.*;`) which counts as one edge regardless of how
many classes it pulls in; the file count (13) vs. import-statement count
(12) is reconciled this way.

### Cross-package cycles

Per dependency_graph.md §2, `element ↔ element/waveform` is one of the five
detected cycles (12 + 15 edges). Concrete shape of the cycle:

- `element/*Elm` owns a `Waveform` (`VoltageElm.waveformInstance`) and
  invokes it for value/draw/edit.
- `Waveform` subclasses call back into `VoltageElm.getUnitText`,
  `VoltageElm.drawThickLine`, etc. (VoltageElm statics reused as helpers)
  *and* narrow casts to `RailElm`, `VarRailElm` for rail-specific or
  slider-specific paths.
- `Waveform` never references element *instances* generically — only
  through the `VoltageElm elm` / `RailElm elm` parameter passed in. The
  cycle is therefore a *compile-time* dependency on types plus static
  helpers, not a runtime ownership loop.

### External deps

- Java standard: `java.util.Map` (JSON properties bag), `java.util.Random`
  (indirect via `RandomUtils`).
- GWT: only indirectly (via `Graphics`).
- `com.lushprojects.circuitjs1.client.util.Locale` (DCWaveform only).
- No references to `io/…` — I/O is driven *from* `VoltageElm`.

## Existing Documentation

- **Inline comments:**
  - Waveform.java:40–41 — legacy note on `WF_VAR`.
  - Waveform.java:119–147 — Javadoc on `doStep`, `hasCircle`, `showFrequency`,
    `usesShortLeads` (all one-liners).
  - DCWaveform.java:24–27 — note that `DCWaveform.draw` is intentionally a
    no-op because `VoltageElm.draw` renders batteries differently.
  - DCWaveform.java:46–50 — note on why `drawRail` doesn't reuse
    `VoltageElm.drawWaveform` (lead collapse / label misplacement).
  - NoiseWaveform.java:37–44 — rationale for the deterministic hashed-seed
    icon (avoid flicker).
  - VarWaveform.java:38 — brief note about `VarRailElm` normally drawing
    as a rail.
- **Caller-side comments (VoltageElm.java):**
  - Line 71–74: old-circuit duty-cycle migration (FLAG_PULSE_DUTY).
  - Line 97: "VarRailElm adds text at the end" — implicit contract
    annotation on dump extension.
  - Line 109: explanation for `waveform = waveformInstance.getType()`
    normalization.
  - Line 268–276: type-change reset of bias / dutyCycle.
- **No module-level README or Javadoc header on any Waveform class.**

## Issues / Questions

1. **`VarWaveform.frequency` is misused as "current output voltage"**
   (VarWaveform.java:20–22). Writing to `frequency` inside `getVoltage`
   during every call is surprising; a named field (`currentValue`?) would
   be clearer, and the JSON serializer would not risk exporting it as an
   actual frequency if a code path ever called `super.getJsonProperties`
   for VarWaveform.
2. **`WF_VAR` factory fallthrough** (Waveform.java:42): `Waveform.create`
   returns `DCWaveform` for `WF_VAR`. This means `VarRailElm` must
   instantiate `VarWaveform` directly — verified in the code — but it's
   fragile: if anyone calls `Waveform.create(WF_VAR, …)` expecting a
   VarWaveform, they get a DC source silently.
3. **Dump format always emits 5 params** regardless of waveform (`freq,
   maxV, bias, phase, duty`). Waveforms that don't use some of those
   (DC, Noise) still carry whatever values were last set, which is
   bandwidth-wasteful and makes diff-reading of circuit files noisy.
4. **`freqTimeZero` is never reset on frequency change** — only on
   `VoltageElm.reset()`. Changing frequency mid-simulation causes a phase
   discontinuity. Noted in `w(elm)` but not documented.
5. **Edit slot numbering is ad-hoc**: slots 0, 2, 3, 4, 5 are used while
   slot 1 is implicitly reserved by `VoltageElm` for the type chooser.
   A comment in VarWaveform.java:51 flags the reservation, but it's not
   enforced anywhere — accidentally returning an EditInfo for n=1 from a
   subclass would shadow the chooser.
6. **`NoiseWaveform` lacks a DC-analysis short-circuit**. Noise sources
   during the DC op-point pass return whatever `noiseValue` was latched
   last (possibly 0 at start). Unlike AC/Square/etc. which deliberately
   return `bias` during DC analysis, Noise can inject non-zero noise into
   the operating-point solve.
7. **`doStep` default guards with `!isDC()` but `DCWaveform` also overrides
   `stamp`/`stampRail`** — the guard is effectively redundant for DC
   (which never needs it) but active for any future subclass that forgets
   to override `stamp`. Asymmetric.
8. **No explicit amplitude convention comment**: AC uses `maxVoltage` as
   peak; Square/Sawtooth use it as peak-above-bias; Pulse uses it as the
   *full* high value (low rail is `bias`, not `bias − maxVoltage`). A
   module-level table would prevent confusion.
9. **`Waveform.getJsonProperties` and each subclass override forget to
   call `super.getJsonProperties` in most cases** (DC, Noise, Var write
   their own property bag; Square/Pulse do call super). Result: DC/Noise/Var
   never emit `frequency` or `phase_shift`, which is intentional but
   duplicates code.
10. **Factory `create` uses plain `switch` on int**: adding a new waveform
    requires editing three places (constant, switch, class). A registry or
    enum-driven approach would enforce consistency but is overkill for 8
    types.

## Suggested Concept Boundaries

**Recommendation: a SINGLE "waveforms" concept over these 9 files.**

Rationale:

- All 9 files share a single abstract contract (`Waveform`) with a
  well-defined dispatch surface. Splitting per-concrete-type would
  fragment the concept without improving comprehension — each subclass is
  <100 LOC and only meaningful in the context of the base.
- The package is *behaviourally cohesive*: every file exists to serve
  `VoltageElm.waveformInstance`. There is no internal sub-structure worth
  distinguishing (no "periodic vs. non-periodic" split in code, for
  example — `isDC()` is just a `boolean`).
- The cycle with `element` is a property of the whole package, not of
  individual concretes. A single concept can document the
  `VoltageElm ↔ Waveform` contract once.
- The 9 files are already colocated in `element/waveform/`, which is the
  project's signal that they are one unit.

One useful sub-hint for downstream concept docs: **note that `VarWaveform`
deviates from the strategy pattern** (it depends on `VarRailElm` specifically
and repurposes `frequency` as output voltage). If the pipeline decides to
split, VarWaveform is the natural fracture line — the remaining 8 are a
clean strategy family, VarWaveform is a partial-strategy glued to the
slider widget.
