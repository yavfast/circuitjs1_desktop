# Module Analysis: domain-core / element-categories / sources

> **Path:** src/main/java/com/lushprojects/circuitjs1/client/element/ (11 files)
> **Layer:** 2 (SCC-A)
> **Analyzed:** 2026-04-18

## Purpose

Voltage- and current-source element family. All voltage sources are
waveform-driven and share a common branch base (`VoltageElm`) which owns a
`Waveform` strategy instance; rails are the single-terminal flavour that
pins node-0 to ground. Non-waveform members are `CurrentElm` (MNA current
stamp) and `SweepElm` (custom frequency-accumulator generator). All 11
concrete elements declare exactly one voltage source to the simulator
(except `CurrentElm`, which declares zero and stamps a current source
directly).

## VoltageElm branch base

`VoltageElm` (VoltageElm.java:33) is a concrete class extending
`CircuitElm` that serves as the **branch base for every waveform-backed
source**. It holds two fields that carry the strategy:

- `int waveform` — `WF_*` ordinal mirror of the strategy type
  (VoltageElm.java:34).
- `Waveform waveformInstance` — the plug-in (VoltageElm.java:35).

These two are kept in sync by `createWaveformInstance()` (line 107-111),
which builds a new strategy via `Waveform.create(type, old)` and then
re-reads `waveformInstance.getType()` so the int is always the canonical
type of the object.

### Lifecycle hooks — every hook delegates to the Waveform strategy

| Lifecycle hook | VoltageElm site | Delegates to |
|---|---|---|
| Constructor (new) | :40 | `createWaveformInstance()` + `reset()` |
| Constructor (undump) | :51-77 | parses 5 waveform params, translates legacy `FLAG_COS` / `FLAG_PULSE_DUTY`, then `createWaveformInstance()` |
| `reset()` | :100 | zeroes `waveformInstance.freqTimeZero` and `curcount` |
| `stamp()` | :117 | `waveformInstance.stamp(this)` |
| `doStep()` | :121 | `waveformInstance.doStep(this)` |
| `stepFinished()` | :125 | `waveformInstance.stepFinished(this)` |
| `getVoltage()` | :129 | `waveformInstance.getVoltage(this)` |
| `setPoints()` | :135 | `calcLeads(usesShortLeads ? 8 : CIRCLE_SIZE*2)` |
| `draw(g)` | :140-193 | routes DC to battery glyph; non-DC calls `drawWaveform` → `waveformInstance.draw(g, center, this)` |
| `getInfo(arr[])` | :227 | `waveformInstance.getInfo(this, arr, 3)` |
| `getEditInfo(n)` | :237 | slot 1 = waveform chooser (8 choices); others → `waveformInstance.getEditInfo(this, n)` |
| `setEditValue(n, ei)` | :255 | slot 1 recreates strategy (+ resets bias/duty on trait change); others → `waveformInstance.setEditValue(this, n, ei)` |
| `dump()` | :88-98 | writes `type x1 y1 x2 y2 flags waveform freq maxV bias phase duty` always (5 waveform params regardless of type); sets/clears `FLAG_PULSE_DUTY` bit for Pulse |
| `getJsonTypeName()` | :286 | `waveformInstance.getJsonTypeName()` |
| `getJsonProperties()` | :291 | super + `waveformInstance.getJsonProperties(this, props)` |
| `applyJsonProperties(m)` | :303 | super + `waveformInstance.applyJsonProperties(this, props)` |

### Fixed structural contract from VoltageElm

- **Posts:** 2 (inherited default). Pin names: `{ "minus", "plus" }` — post 1 is driven `voltage` above post 0 (SP_AGA_DEC_06, 2026-10-03; was `{ "positive", "negative" }`, which named post 0 "positive"). `getJsonPinAliases()` keeps `positive`→0, `negative`→1 for JSON 2.0 import. `CurrentElm`: `{ "in", "out" }`; `OhmMeterElm`: `{ "com", "probe" }`.
- **Voltage sources:** 1 (`getVoltageSourceCount() == 1`, line 215).
- **Dump type:** `'v'` (118). `getIdPrefix()` → `"V"` (line 80).
- **Voltage-diff convention:** `V(1) − V(0)` (line 223-225).
- **Power:** `-Vd · I` (line 219).
- **ScopeElm-viewable:** yes (post count 2 satisfies default).

### What subclasses typically override

Only three hooks in practice:

1. **Constructor** — selects a `WF_*` constant passed to `super(...)`.
2. **`getDumpType()`** — for DC/AC the legacy dump reader still resolves
   to the same parser, which is why both `DCVoltageElm` and
   `ACVoltageElm` return `getDumpClass() == VoltageElm.class`; they keep
   `'v'` as the token and only the `waveform` int differentiates.
3. **`getJsonTypeName()`** — every concrete subclass exports a unique
   JSON type string.

## Per-element catalog

| Element | Extends | Posts | V-sources | Waveform / WF_* | Dump-type | Parameters | File:lines |
|---|---|---|---|---|---|---|---|
| **VoltageElm** | CircuitElm | 2 | 1 | dynamic via `waveform` field | `'v'` (118) | waveform, freq, maxV, bias, phase, duty | VoltageElm.java:33-307 |
| **DCVoltageElm** | VoltageElm | 2 | 1 | `WF_DC` (hardcoded in ctor) | `'v'` (via `getDumpClass = VoltageElm.class`) | maxV, bias | DCVoltageElm.java:26-43 |
| **ACVoltageElm** | VoltageElm | 2 | 1 | `WF_AC` | `'v'` (via `getDumpClass = VoltageElm.class`) | maxV, bias, freq, phase | ACVoltageElm.java:25-38 |
| **RailElm** | VoltageElm | **1** | 1 | `WF_DC` default; pkg-ctor takes any | `'R'` (82) | inherited from VoltageElm | RailElm.java:30-143 |
| **ACRailElm** | RailElm | 1 | 1 | `WF_AC` | `'R'` (via `getDumpClass = RailElm.class`) | maxV, bias, freq, phase | ACRailElm.java:25-42 |
| **SquareRailElm** | RailElm | 1 | 1 | `WF_SQUARE` | `'R'` (via `getDumpClass = RailElm.class`) | maxV, bias, freq, phase, duty | SquareRailElm.java:25-42 |
| **VarRailElm** | RailElm | 1 | 1 | `WF_DC` (reinforced in `createSlider`); `frequency` repurposed as current slider value | `172` | bias=min, maxV=max, frequency=current slider value, sliderText | VarRailElm.java:34-244 |
| **ExtVoltageElm** | RailElm | 1 | 1 | `WF_AC` nominal; overrides `getVoltage()` to return externally set `voltage` | `418` | name + inherited | ExtVoltageElm.java:31-105 |
| **NoiseElm** | RailElm | 1 | 1 | `WF_NOISE` | `'R'` (via inheritance; commented-out `'n'` reader still lives in `CirSim.createCe` for legacy files) | maxV, bias | NoiseElm.java:27-48 |
| **CurrentElm** | CircuitElm **(not VoltageElm)** | 2 | **0** | — (none) | `'i'` (105) | currentValue | CurrentElm.java:30-174 |
| **SweepElm** | CircuitElm **(not VoltageElm)** | **1** | 1 | — (custom phase accumulator) | `170` | minF, maxF, maxV, sweepTime, FLAG_LOG, FLAG_BIDIR | SweepElm.java:30-310 |

## Shared patterns

### 1. Waveform plug-in mechanism

Every `VoltageElm`/`RailElm` descendant owns a `Waveform` instance and
delegates all time-domain behaviour to it. See
`domain-core__waveforms.md` for the full strategy contract. Key
consequences for the sources category:

- Concrete subclasses only need to pass a `WF_*` constant to the
  VoltageElm ctor and override `getJsonTypeName()`. All stamp/step/edit
  work is already done by the base + strategy.
- The DC subtype short-circuits `doStep` (VoltageElm delegates to
  `waveformInstance.doStep` whose default guards on `!isDC()`), and the
  `draw` method special-cases DC to render the two-cell battery glyph
  (VoltageElm.java:148-162) rather than the circle-with-icon glyph.
- The dump line **always** carries 5 waveform params (freq, maxV, bias,
  phase, duty), even for DC — unused slots carry stale values.

### 2. Rail-vs-full voltage source (1 post vs 2 posts)

RailElm (RailElm.java:30) extends VoltageElm and reshapes it to a
single-post source by:

- `getPostCount() = 1` (line 54-56).
- `getVoltageDiff() = getNodeVoltage(0)` (line 108-110) — measured
  against implicit ground.
- `hasGroundConnection(n1) = true` (line 121-123).
- `stamp()` calls `waveformInstance.stampRail(this)` — the Waveform
  subclass picks between the default `stampVoltageSource(0, node0, vs)`
  (free source) and the DC-value-fixed `stampVoltageSource(0, node0, vs, V)`.
- `doStep()` updates only when `!waveformInstance.isDC()` (line 116-119) —
  DC rails never need per-step updates because they are stamped with a
  fixed value at `stamp()` time.
- `draw()` fully overridden (line 72-95) to render rail-label + lead line
  to a `railLead` point; delegates the glyph to
  `waveformInstance.drawRail(g, this)`.
- `getJsonTypeName()` → `waveformInstance.getJsonRailTypeName()` (default
  `"Rail"`; AC/Square/Var override to `"ACRail"` / `"SquareRail"` /
  `"VariableRail"`).
- Pin names: `{ "output" }` (line 140-142).

All four RailElm subclasses (`ACRailElm`, `SquareRailElm`, `VarRailElm`,
`ExtVoltageElm`, `NoiseElm`) inherit this behaviour.

### 3. DC source fast path

For `WF_DC`, the waveform strategy's `stamp` / `stampRail` override uses
the value-fixed `stampVoltageSource(n0, n1, vs, V)` form so the matrix
entry is constant across the simulation; `doStep` is skipped; `getVoltage`
returns `maxVoltage + bias` with no time dependence.
`DCVoltageElm`/plain `RailElm` never perform per-step work.

### 4. `dumpClass` aliasing

Subclasses that don't extend the text-dump format return the parent
class from `getDumpClass()` — this makes the dump round-trip through the
parent's constructor and treats the subclass as a *conventional default*
picked when the subclass creation hotkey is used:

- `DCVoltageElm.getDumpClass() → VoltageElm.class` (dumps as `'v'` with waveform=0).
- `ACVoltageElm.getDumpClass() → VoltageElm.class` (dumps as `'v'` with waveform=1).
- `ACRailElm.getDumpClass() → RailElm.class` (dumps as `'R'` with waveform=1).
- `SquareRailElm.getDumpClass() → RailElm.class` (dumps as `'R'` with waveform=2).

`VarRailElm`, `NoiseElm`, `ExtVoltageElm` either use a distinct dump
type (172 / 418) or still dump as the parent with the waveform int
carrying the discriminator (NoiseElm relies on waveform=6, `'R'`).

## Per-element callouts

### DCVoltageElm — fixed DC, 2 posts

`super(…, WF_DC)` (line 28). Overrides only `getDumpClass`, `getShortcut ='v'`,
`getJsonTypeName = "VoltageSourceDC"`. Rendered as a battery (two cells)
by `VoltageElm.draw()` when `waveformInstance.isDC()`.

### ACVoltageElm — waveform=AC, 2 posts

`super(…, WF_AC)` (line 27). Relies entirely on `ACWaveform` for `sin(w)·maxV+bias`.
No specific hooks; `getJsonTypeName = "VoltageSourceAC"`.

### RailElm — fixed-voltage 1-terminal source

`FLAG_CLOCK = 1` (line 48) — cosmetic flag picked up by
`SquareWaveform.drawRail` to label the glyph `"CLK"` (a code path shared
with `ClockElm`, which lives outside this category). Overrides
`setPoints` (line 58-66) to precompute `railLead` at the glyph-edge; this
is called again inside `draw()` with measurement-based width clamping.
`getShortcut() = 'V'`.

### ACRailElm — AC against ground, 1 post

`super(…, WF_AC)`. Only overrides: `getDumpClass = RailElm.class`,
`getShortcut = 0` (not on a hotkey), `getJsonTypeName = "ACRail"`.

### SquareRailElm — square-wave rail

`super(…, WF_SQUARE)`. Same triviality as ACRailElm. `SquareWaveform`'s
`drawRail` checks `elm.flags & RailElm.FLAG_CLOCK` to render `"CLK"` text
for clock-like usage.

### VarRailElm — slider-driven, 1 post

Cross-reference: bound to the `Adjustable` mechanism documented in the
sliders category.

- **Parameter repurposing:** `bias = min V`, `maxVoltage = max V`,
  `frequency = current slider value` (VarRailElm.java:179-186). This is
  deliberate legacy compatibility — old dumps wrote the value into the
  `frequency` slot.
- **Waveform:** **always forced to `WF_DC`** via `createSlider()` (line
  76-77) — even though an older `WF_VAR` ordinal exists, this class
  stores a `DCWaveform` and keeps the slider orthogonal.
- **Override of `getVoltage()`:** returns `waveformInstance.frequency`
  directly (line 185), sidestepping `DCWaveform.getVoltage` which would
  return `maxV+bias`.
- **Override of `doStep()`:** always updates the voltage source every
  step (line 189-193) — unlike `RailElm.doStep` which would skip the
  update for DC — because the slider can move at any time.
- **Adjustable binding:** `ensureVoltageAdjustable` (line 84-101) looks
  up or creates an `Adjustable` in
  `circuitDocument.adjustableManager` keyed by `(this, EDIT_VOLTAGE=3)`,
  syncs `minValue`/`maxValue`/`sliderText`, and optionally refreshes the
  sliders panel. The slider writes back into
  `waveformInstance.frequency` via the EditInfo edit-value pipeline
  (line 145-147).
- **Delete hook:** removes label/slider widgets from the vertical panel
  and asks `adjustableManager.deleteSliders(this)` (line 195-207).
- **Dump:** `super.dump()` + url-escaped `sliderText` (line 62).
- **Dump type:** `172` (line 66).
- **JSON:** adds `slider_text` property (line 230-243).

### SquareRailElm — see above.

### ExtVoltageElm — external (JS-bridge) voltage input

- `super(…, WF_AC)` (line 33) — the `WF_AC` pick is only so the element
  has a non-DC waveform instance so `RailElm.doStep` wouldn't early-exit
  on `isDC()`. BUT `ExtVoltageElm` does not actually override `doStep`;
  it overrides `getVoltage()` to return an externally set `voltage`
  field (line 63-65). A JS caller writes via `setVoltage(v)` (line
  55-57; guards NaN).
- **Dump type:** `418` (line 67).
- **Dump extension:** appends `CustomLogicModel.escape(name)` so the
  external identifier round-trips (line 47-49).
- **Edit dialog:** single row — `Name` (line 75-87).
- **JSON type:** `"ExternalVoltage"`; property `name` (line 94-104).
- **Glyph:** `drawRail` overridden to render the escaped name instead of
  a waveform icon (line 51-53).

### CurrentElm — current-source stamping

- **Parent:** `CircuitElm` **directly** — **not** a VoltageElm descendant.
- **Posts:** 2 (default), **V-sources:** 0 (no override so default is 0).
- **Dump:** `'i'` (105); appends `currentValue`.
- **Stamp (line 113-123):** the key divergence from voltage sources.
  - If `broken` (set by `analyzeCircuit` when no current path exists):
    stamp a `1e8 Ω` resistor between the two posts and zero the current
    to avoid a matrix singularity.
  - Otherwise: `simulator().stampCurrentSource(getNode(0), getNode(1), currentValue)`
    and cache `current = currentValue`. This pumps the RHS vector
    directly rather than adding a voltage-source row+column as
    `VoltageElm` does via `stampVoltageSource`.
- **No `doStep`, no `stepFinished`:** the current is stamped once per
  analysis and never changes; the class overrides nothing dynamic.
- **Drawing:** circle + internal arrow-on-shaft glyph (line 84-104).
- **Edit:** single row `Current (A)` (line 125-129).
- **JSON:** `"CurrentSource"`; property `current` parsed via
  `io.json.UnitParser`.

### NoiseElm — stochastic behaviour

- `super(…, WF_NOISE)` (line 29). Construction-only subclass — all
  stochasticity lives in `NoiseWaveform`.
- `NoiseWaveform.stepFinished` advances
  `noiseValue := (rand·2 − 1)·maxV + bias` via
  `RandomUtils.getRandom().nextDouble()` once per simulation step and
  latches it for the entire Newton loop (prevents in-loop value change
  from breaking convergence).
- `NoiseWaveform.getVoltage` returns the latched `noiseValue` — no DC
  analysis branch, so a NoiseElm can leak a non-zero sample into the
  DC-op-point pass if `noiseValue` happens to be non-zero at t=0.
- **Icon seeding:** `NoiseWaveform.draw` seeds its scribble with
  `elm.getElementId().hashCode()` so the icon doesn't flicker on every
  redraw.
- **Dump:** inherits RailElm dump (`'R'`); the legacy `'n'` dump reader
  is still present in `CirSim.createCe` per the commented-out note at
  line 38-39.
- **JSON type:** `"NoiseSource"`.

### SweepElm — freq-swept AC source

Lives outside the waveform plug-in system. It is a `CircuitElm`
descendant with its own frequency-accumulator algorithm.

- **Posts:** 1 (line 58-60). **V-sources:** 1 (line 192-194).
- **hasGroundConnection:** true (line 196-198).
- **Dump type:** `170` (line 54); params `minF, maxF, maxV, sweepTime`.
- **Flags:** `FLAG_LOG = 1` (log vs linear sweep), `FLAG_BIDIR = 2`
  (ping-pong between minF and maxF).
- **Internal state:** `frequency` (current), `freqTime` (phase
  accumulator in radians), `fadd` / `fmul` (per-step frequency update
  coefficients), `dir` (+1 ascending, -1 descending), `savedTimeStep`
  (for timestep-change re-init), `v` (sample value for `doStep`).
- **`setParams()`** (line 137-151): computes `fadd` and `fmul` for the
  sweep. Linear: `fadd = dir·Δt·(maxF−minF)/sweepTime`, `fmul = 1`.
  Log: `fadd = 0`, `fmul = (maxF/minF)^(dir·Δt/sweepTime)`.
- **`reset()`** (line 153-158): frequency = minF, phase = 0, dir = 1.
- **`stamp()`** (line 130-132): `stampVoltageSource(0, node0, vs)` —
  free, per-step-updated voltage source.
- **`startIteration()`** (line 162-182): compute `v = sin(freqTime)·maxV`,
  advance phase `freqTime += ω·Δt = 2π·frequency·Δt`, update
  `frequency` by fmul+fadd, flip `dir` at boundaries when `FLAG_BIDIR`
  is set else wrap `frequency = minF`.
- **`doStep()`** (line 184-186): `updateVoltageSource(0, node0, vs, v)`.
- **Editor:** 6 rows — minF, maxF, sweepTime, FLAG_LOG checkbox, maxV,
  FLAG_BIDIR checkbox. Max frequency is clamped to `1 / (8·Δt)` (line
  233-242) to enforce the simulator's Nyquist-ish stability budget.
- **JSON:** `"SweepGenerator"`; properties min/max freq (Hz), max_voltage,
  sweep_time, logarithmic, bidirectional; state preserves
  `frequency`, `freqTime`, `dir`, `v` so the sweep resumes mid-cycle
  after JSON import.
- **Rendering:** draws a static sine curve inside a circle (line
  101-113) — deliberately frozen (`w = 2` constant) to avoid visual
  time-dependence that would distract from the actual circuit.

## Validation Rules

- `VoltageElm(ctor-from-dump)` wraps token parsing in try/catch and
  falls back to `createWaveformInstance()` with whatever was parsed
  (VoltageElm.java:63-65) — tolerates truncated legacy dump lines.
- Legacy `FLAG_COS` (=2) → cleared and `phaseShift = π/2` on load
  (VoltageElm.java:66-69). One-way migration.
- `FLAG_PULSE_DUTY` (=4) — if **not** set and waveform is pulse,
  `dutyCycle` is coerced to `defaultPulseDuty = 1/(2π)` ≈ 0.159
  (VoltageElm.java:72-74). Preserves old default.
- On waveform type change in editor (VoltageElm.java:268-277):
  - into DC: zero `bias`
  - into Pulse: `dutyCycle = defaultPulseDuty`
  - out of Pulse: `dutyCycle = 0.5`
- `VarRailElm.setEditValue` auto-swaps `bias` and `maxVoltage` if
  `maxVoltage < bias` (line 150-154) to keep the range ordered.
- `VarRailElm.getEditInfo` returns null when `waveformInstance == null`
  (line 105-107) — covers race during load before `createSlider`.
- `CurrentElm.stamp` handles the `broken` state by stamping a 100 MΩ
  stand-in instead of a current source (line 114-117) — keeps the
  matrix non-singular when there is no current path.
- `CurrentElm` ctor-from-dump defaults `currentValue = 0.01 A` if the
  token is unparseable (line 43-46).
- `SweepElm.setEditValue` clamps minF and maxF to `1 / (8·timeStep)`
  (line 233-242).
- `SweepElm.startIteration` compares `simulator().timeStep` against
  `savedTimeStep` and re-runs `setParams()` on change (line 163-165)
  so the coefficients stay correct when the user rescales time.
- `ExtVoltageElm.setVoltage` ignores NaN (line 56).
- `RailElm.draw` clamps the rail-label width to `dn·0.8` so long
  labels don't overshoot the lead (line 84-85).

## Integration Points

### Depends on

- **domain-core / element-base:** `CircuitElm`, `ElmGeometry`,
  `BaseCircuitElm` (for static drawing/formatting helpers).
- **domain-core / waveforms:** `Waveform` + 8 subclasses. All
  VoltageElm/RailElm descendants own a `Waveform`.
- **Adjustable subsystem:** `client.Adjustable`,
  `circuitDocument.adjustableManager` — used only by `VarRailElm`.
- **Slider widgets:** `client.Scrollbar`, `client.ui.Label` — only
  `VarRailElm`.
- **UI primitives:** `client.Checkbox`, `client.Choice`,
  `dialog.EditInfo`.
- **GWT event:** `MouseWheelEvent` / `MouseWheelHandler` — `VarRailElm`
  only.
- **Simulator hooks (root `client/`):**
  - `CircuitSimulator.stampVoltageSource(value?)` — DC/AC/Rail/Sweep.
  - `CircuitSimulator.stampCurrentSource` — `CurrentElm` only.
  - `CircuitSimulator.stampResistor` — `CurrentElm.broken` fallback.
  - `CircuitSimulator.updateVoltageSource` — Rail/VarRail/Sweep per-step.
  - `CircuitSimulator.timeStep` — SweepElm.
  - `CircuitDocument.circuitInfo.dcAnalysisFlag` — consulted by
    waveforms, not directly by these classes.
- **Custom logic:** `CustomLogicModel.escape/unescape` — ExtVoltageElm.
- **IO:** `io.json.UnitParser` — CurrentElm for `current` property.

### Used by

- **editor / menu:** creation hotkeys (`'v'` DCVoltage, `'V'` Rail,
  `'i'` CurrentElm, `'s'` for unrelated switch) and
  `CircuitElmCreator` dispatch tables.
- **simulator:** `analyzeCircuit` reaches into `CurrentElm.setBroken`
  during current-path detection before `stamp()`.
- **io / text:** `CirSim.createCe` still knows the legacy `'n'` token
  for old NoiseElm dumps (see NoiseElm.java:38-39).
- **io / json:** element-type dispatch on
  `VoltageSource{DC,AC,Square,Triangle,Sawtooth,Pulse,Noise,Var}`,
  `ACRail` / `SquareRail` / `VariableRail` / `ExternalVoltage` /
  `CurrentSource` / `SweepGenerator` / `NoiseSource`.
- **JS bridge:** `ExtVoltageElm.setVoltage` is the primary write path.
- **sliders dialog:** `VarRailElm` via `AdjustableManager`.

### External deps

- GWT widgets (`Scrollbar`, `Label`) — VarRailElm only.
- `java.util.Map` — JSON properties bag.
- No reflection, no threading.

## Issues / Questions

1. **VoltageElm is non-abstract despite being a branch base.** It can
   be instantiated directly (ctor is package-private at line 40 but the
   text-dump ctor is public at line 51, so the factory does build it).
   All concrete hotkeys go through `DCVoltageElm` / `ACVoltageElm` but
   the base itself would work if constructed with an arbitrary WF_*.
2. **`DCVoltageElm` and `ACVoltageElm` contribute almost nothing** —
   they exist to bind a default `WF_*` for UI creation, reset the
   shortcut, and re-brand `getJsonTypeName`. Everything else inherits.
   Arguably the class split exists solely because
   `CircuitElmCreator` keys on `.class` when instantiating.
3. **`NoiseElm` relies on waveform=6 in the dump** but also has a
   commented-out `getDumpType() = 'n'` and a dead code path in
   `CirSim.createCe`. History-bearing fossil; a cleanup would either
   re-enable the unique dump type or delete the legacy reader.
4. **`VarRailElm` repurposes `waveformInstance.frequency`** as the
   current slider voltage — inherited fragility from `VarWaveform`
   (which also does this). The field-name mismatch makes the class
   hard to read; a named field would be safer. Noted in
   `domain-core__waveforms.md` issue #1.
5. **`ExtVoltageElm` uses `WF_AC` as a placeholder** to dodge the DC
   `doStep` short-circuit, but the class doesn't actually need a
   waveform at all — its `getVoltage()` always returns the externally
   set `voltage`. The `WF_AC` choice leaks into the dump (waveform=1
   is written) and into the info panel via inherited `VoltageElm.getInfo`.
6. **`CurrentElm` does not consistently override `getVoltageSourceCount`;**
   it inherits the default 0 which is correct, but any future refactor
   of the default would break it silently.
7. **`CurrentElm.broken` is mutated by `analyzeCircuit` from outside**
   (via `setBroken`) — a timing contract that is not documented inside
   the class. If the simulator ever forgets to call `setBroken`, a
   floating current source will produce a singular matrix.
8. **`SweepElm.draw` uses `w = 2` (line 102) as a hard-coded "static
   icon" constant** that originally encoded a time-varying animation.
   The comment at line 101 says it's deliberate, but the code above
   (lines 95-113) still looks like it expects `w` to be time-dependent.
9. **`SweepElm.getVoltage()` is not overridden** — the default from
   `CircuitElm` returns 0, which is wrong. `getVoltageDiff() =
   getNodeVoltage(0)` (line 188-190) is correct though. Anyone reading
   `elm.getVoltage()` directly on a SweepElm will get 0, not `v`. This
   is a latent bug — confirmed by the fact that `getInfo` reads
   `getNodeVoltage(0)` instead of `getVoltage()`.
10. **`VarRailElm.doStep` always updates** the voltage source even when
    the slider hasn't moved — minor CPU waste at simulation scale but
    harmless. A `dirty` bit on the slider would skip the call.
11. **Dump-type allocation is magic-numbered:** `172` (VarRail), `170`
    (Sweep), `418` (ExtVoltage), `'v'` (118), `'R'` (82), `'i'` (105).
    No central registry (cross-cutting with the element-base issue #9
    about missing DumpTypes constants).

## Suggested Concept Boundaries

**Recommendation: single `voltage-and-current-sources` concept over
all 11 files.**

Rationale:

- All 11 share the sources-category semantics: they write to the MNA
  matrix to inject energy into the circuit (either voltage rows or
  current entries on the RHS).
- The waveform strategy is a separate concept (`domain-core__waveforms`)
  so the sources concept can reference it by name without re-stating
  the Waveform contract.
- Splitting CurrentElm or SweepElm into their own concepts would
  fragment documentation — each is only meaningful in contrast to the
  waveform-driven sources.

Secondary fracture lines (only if finer granularity is demanded):

1. **waveform-sources** — `VoltageElm`, `DCVoltageElm`, `ACVoltageElm`,
   `RailElm`, `ACRailElm`, `SquareRailElm`, `NoiseElm`. Pure
   strategy-driven.
2. **interactive-sources** — `VarRailElm`, `ExtVoltageElm`. External
   input (slider / JS) overrides the strategy.
3. **non-waveform-sources** — `CurrentElm`, `SweepElm`. Skip the
   Waveform system entirely, each with its own stamping/value
   evolution.

The dependency graph does not require this split.
