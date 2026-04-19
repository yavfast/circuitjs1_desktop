# Module Analysis: domain-core / cat-audio-rf

> **Path:** src/main/java/com/lushprojects/circuitjs1/client/element/ (6 concrete elements)
> **Layer:** 2 (SCC-A, element catalog)
> **Analyzed:** 2026-04-18
> **Files:** AudioInputElm, AudioOutputElm, AMElm, FMElm, AntennaElm, CrystalElm
> **Prereq:** `domain-core__element-base.md`

## Purpose

Bridges the simulator to the host browser's **audio subsystem** and provides
the "radio-frequency demo" elements that make CircuitJS1's AM/FM/crystal-radio
example circuits work. Six elements split three ways:

1. **Audio I/O (2):** `AudioInputElm`, `AudioOutputElm` — sample-rate-driven
   bridges to the Web Audio API via GWT JSNI. Input streams a decoded file as
   a time-varying voltage rail; output buffers the node voltage and plays it
   back as a WAV blob.
2. **RF modulation sources (2):** `AMElm`, `FMElm` — closed-form analytic
   voltage sources evaluating `sin(carrier) · (1 + sin(signal))/2` and
   `sin(∫(carrier + dev·sin(signal)))` respectively at `simulator().t`. Both
   are 1-post, hard-grounded sources (drop one terminal via
   `hasGroundConnection(0) == true`).
3. **Passive "RF" elements (2):** `AntennaElm` — a hard-coded three-AM-carrier
   + one-FM-carrier synthetic ether source; `CrystalElm` — a genuine physics
   composite of C‖(C–L–R), the standard BVD quartz-crystal model.

All six share the property that they are **not MNA-linear stamped in the usual
2-terminal way** — instead they are either voltage-source rails (AudioInput,
Antenna, AM, FM), an external sink driven by `stepFinished` polling
(AudioOutput), or a delegated sub-netlist (Crystal). None of them are
nonlinear in the Newton-iteration sense.

## Per-Element Catalog

| Element | Extends | Posts | V-sources | Linear? | Dump-type | Parameters | File:lines |
|---|---|---|---|---|---|---|---|
| `AudioInputElm` | `RailElm` (→ `VoltageElm` → `CircuitElm`) | 1 | 1 (inherited) | linear (time-varying source) | **411** | `maxVoltage`, `startPosition`, `fileNum` (+ `fileName`, `data[]`, `samplingRate` in static cache) | AudioInputElm.java:37-221 |
| `AudioOutputElm` | `CircuitElm` | **1** (override L88) | 0 | linear (sink only) | **211** | `duration`, `samplingRate`, `labelNum` | AudioOutputElm.java:24-447 |
| `AMElm` | `CircuitElm` | 1 | 1 | linear | **200** | `carrierfreq` (default 1000 Hz), `signalfreq` (40 Hz), `maxVoltage` (5 V) | AMElm.java:32-199 |
| `FMElm` | `CircuitElm` | 1 | 1 | linear | **201** | `carrierfreq` (800 Hz), `signalfreq` (40 Hz), `maxVoltage` (5 V), `deviation` (200 Hz) | FMElm.java:31-201 |
| `AntennaElm` | `RailElm` | 1 | 1 (inherited) | linear | `'A'` (65) | none user-visible; hardcoded carriers 3000/2710/2433 Hz + ~2200 Hz FM | AntennaElm.java:30-81 |
| `CrystalElm` | `CompositeElm` | 2 | 0 (children own them) | linear (C/L/R only) | **412** | `parallelCapacitance` (28.7 pF), `seriesCapacitance` (0.1 pF), `inductance` (2.5 mH), `resistance` (6.4 Ω) | CrystalElm.java:29-193 |

### Key per-element details

**AudioInputElm (AudioInputElm.java:37)**
- Constructor `(doc, xx, yy)` forces `super(..., Waveform.WF_AC)` — a RailElm
  AC rail whose `getVoltage()` is overridden to return the file-sample value
  instead of the default sine. `maxVoltage = 5`.
- `getVoltage()` (line 111-124): linear interpolation between
  `data[iptr]` and `data[iptr+1]` using the fractional part of
  `timeOffset · samplingRate`. Returns `(v1·(1-frac) + v2·frac) · maxVoltage`.
  When `data == null` → 0; when past end-of-buffer → 0.
- `stepFinished()` (line 126): `timeOffset += simulator().timeStep` — drives
  the playhead off the global simulation clock, **not** off wall-clock.
- `reset()` (line 95): sets `timeOffset = startPosition` — the one
  user-adjustable seek offset (persisted).
- **Audio caching:** static `HashMap<Integer, AudioFileEntry> audioFileMap`
  + `fileNumCounter` assigns each loaded file a `fileNum` and keeps the
  `JsArrayNumber data` pinned across cut/paste/undo/redo (line 39-56,
  82-92). Only the `fileNum` is emitted in `dump()`; the PCM data is not
  serialized to disk.
- **Sampling-rate coupling:** `setSamplingRate(sr)` is called from the JSNI
  callback with `context.sampleRate`; the last-seen rate is stored in
  static `lastSamplingRate` and also pushed into `AudioOutputElm` so
  playback matches recording (line 187-189).
- `getShortcut()` returns 0 — no keyboard shortcut for this element.

**AudioOutputElm (AudioOutputElm.java:24)**
- **Only 1 post** (`getPostCount() == 1`, line 88). It reads the node
  voltage via `getNodeVoltage(0)` and does not stamp anything — it is a
  pure sink.
- `stepFinished()` (line 150-163): implements a **boxcar decimator**.
  Every simulator step accumulates `dataSample += getNodeVoltage(0)` and
  `dataSampleCount++`; when `simulator().t >= nextDataSample`, the average
  is written into `data[dataPtr++]` and `nextDataSample += sampleStep`.
  Circular buffer: when `dataPtr == dataCount`, wraps with `dataFull = true`.
- `setTimeStep()` (line 246-270): attempts to set `simulator().maxTimeStep
  = sampleStep / 8` (≥8× oversample) with a one-time JS `confirm()`
  dialog; the "don't ask again" latch is a static `okToChangeTimeStep`
  on the class. Triggered on `draggingDone()` and on sampling-rate change.
- `play()` (line 410-446): rescales samples to ±0.25·32766 Int16 range,
  applies a 1/20 s linear fade-in and fade-out, hands to `playJS(…)`
  which builds a WAV container in-browser (JSNI block lines 291-404).
  The WAV blob is then attached as an `<audio>` element to `document`
  and auto-played. `getLastBlob()` retrieves the URL so the edit dialog
  can expose a "Download last played audio" anchor (line 194-206).
- `createButton()` (line 272): adds a `"▶ Play Audio"` button to the
  simulator's right-hand panel; `delete()` removes it.
- `getNextLabelNum()` (line 67-82) walks `simulator.elmList` picking
  max+1 so multiple AudioOutput instances get `"Audio Out"`, `"Audio 2"`,
  `"Audio 3"`, … labels.
- Default sampling rate on fresh placement: `lastSamplingRate` initial
  value `8000`, or whatever an earlier `AudioInputElm` set.
  Sampling-rate choices: `{8000, 11025, 16000, 22050, 44100, 48000}`
  (line 176).

**AMElm (AMElm.java:32)**
- 1-post grounded source. `stamp()` calls `stampVoltageSource(0, node, vs)`
  once (line 78-80); `doStep()` calls `updateVoltageSource(0, node, vs,
  getVoltage())` every Newton iteration (line 82-84).
- Closed form at `AMElm.java:86-89`:
  ```
  w = 2π · (t - freqTimeZero)
  v = ((sin(w·signalfreq) + 1)/2) · sin(w·carrierfreq) · maxVoltage
  ```
  **Double-sideband full-carrier AM** with modulation index 1 (the
  envelope always dips to zero). The envelope `(1+sin)/2` swings [0, 1].
- `FLAG_COS` (value 2, line 33): legacy flag parsed and cleared on load
  (line 51-53). Functionally dead — no cos-phase variant is implemented.
- `getPower()` (line 146): `-Vd · I` — standard source sign convention.

**FMElm (FMElm.java:31)**
- Same 1-post grounded-source contract as AMElm.
- Closed form at `FMElm.java:84-91`:
  ```
  deltaT = t - lasttime
  signalamplitude = sin(2π · (t - freqTimeZero) · signalfreq)
  funcx += deltaT · (carrierfreq + signalamplitude · deviation)
  v = sin(2π · funcx) · maxVoltage
  ```
  This is **numerical integration of the instantaneous frequency** —
  Euler step on the phase accumulator `funcx`. `lasttime` + `funcx` are
  instance state reset implicitly to 0 at construction (no `reset()`
  clears them; the `reset()` method only zeros `freqTimeZero` +
  `curcount`). Replaying the simulation without re-creating the element
  can therefore yield a phase that does not start at zero — a **latent
  state bug** (see Issues).
- `FLAG_COS` parsed-and-cleared exactly like AMElm (line 52-54).

**AntennaElm (AntennaElm.java:30)**
- Extends `RailElm` with `Waveform.WF_AC`. No user parameters
  (`getEditInfo(n) == null`, line 73-75).
- `getVoltage()` (line 47-53) sums three AM-modulated carriers plus one
  FM contribution `fm = 3·sin(fmphase)`:
  ```
  v = sin(2π·t·3000) · (1.3 + sin(2π·t·12)) · 3
    + sin(2π·t·2710) · (1.3 + sin(2π·t·13)) · 3
    + sin(2π·t·2433) · (1.3 + sin(2π·t·14)) · 3
    + 3·sin(fmphase)
  ```
  All constants hardcoded — the element is scene-dressing for the
  crystal-radio demo, not a general RF source.
- `stepFinished()` (line 55-58) advances `fmphase` by
  `2π · (2200 + 100·sin(2π·t·13)) · timeStep` — an integrated-FM
  identical in form to `FMElm.funcx`. Same latent state issue: no
  explicit reset of `fmphase`.
- Dump type is the single ASCII char `'A'` (65).

**CrystalElm (CrystalElm.java:29)**
- **The only genuine physics element** in this category. Models a quartz
  crystal as the **Butterworth–Van Dyke equivalent circuit**:
  ```
  nodes: 1 (ext) — 2 (ext)
         1 ── C_p ── 2                (parallel/shunt capacitance)
         1 ── C_s ── 3 ── L ── 4 ── R ── 2   (motional branch)
  ```
  Model string: `"CapacitorElm 1 2\rCapacitorElm 1 3\rInductorElm 3 4\rResistorElm 4 2"`
  with external nodes `{1, 2}` (line 33-34).
- Passes through `CompositeElm(doc, xx, yy, modelString, modelExternalNodes)`
  — no custom `stamp()`, `doStep()`, `nonLinear()`, etc.; everything is
  forwarded to the four children.
- Default component values (line 38-41): C_p = 28.7 pF, C_s = 0.1 pF,
  L = 2.5 mH, R = 6.4 Ω — gives series resonance
  `fs = 1 / (2π·√(L·C_s)) ≈ 10.06 MHz`. Reported at `CrystalElm.java:139`.
- `initCrystal()` (line 59-68) re-pushes the four user-editable scalars
  into the corresponding child elements — called after construction and
  after each `setEditValue`.
- `setPoints()` (line 76-97) computes the "sandwich" pictogram (two
  plates + central rectangle) and explicitly calls `setPost(0, …)`,
  `setPost(1, …)` — CompositeElm does **not** set posts automatically,
  so subclasses must (comment line 94).
- `stepFinished()` (line 131-134) overrides to compute
  `current = getCurrentIntoNode(1)` so the `I = …` info line shows the
  correct through-current rather than the CompositeElm default.

## Web Audio Bridge (JSNI / GWT wrapper)

Two JSNI blocks, both ordinary `native` methods:

**1. AudioInputElm.fetchLoadFileData (AudioInputElm.java:167-183)**
- Signature: `static native String fetchLoadFileData(AudioInputElm elm,
  Element uploadElement)`.
- Flow:
  1. `new (window.AudioContext || window.webkitAudioContext)()` — creates
     a browser audio context (Web Audio API entry point). Works in both
     modern and legacy Safari via the `||` fallback.
  2. Immediately pushes `context.sampleRate` into the Java instance via
     the JSNI `@com.lushprojects...::setSamplingRate(I)(rate)` call.
  3. Reads the first `File` from `uploadElement.files` via `FileReader`
     as an `ArrayBuffer`.
  4. `context.decodeAudioData(arrayBuffer, onSuccess, onError)` — browser
     decodes any format the browser supports (MP3, WAV, OGG, …).
  5. On success, `buffer.getChannelData(0)` yields a `Float32Array` of
     mono PCM samples; passed back as a `JsArrayNumber` to
     `gotAudioData(data)` which stores it on the element and updates
     `lastSamplingRate` on **both** AudioInputElm and AudioOutputElm
     (line 185-189).
- Error path logs to `console.log` only — no user-visible message.

**2. AudioOutputElm.playJS (AudioOutputElm.java:291-404)**
- Signature: `static native void playJS(JsArrayInteger samples, int sampleRate)`.
- Builds a WAV-16 byte stream **inside JavaScript** (the `Wav` closure
  class). Header layout is a standard RIFF/WAVE container with `fmt `
  chunk size 18, 1 channel, `0x0001` PCM format, 16-bit samples.
- Ships the samples through `getBuffer(1000)` chunks into a `Blob`
  with `{type: 'audio/wav'}`, takes `URL.createObjectURL(blob)`, attaches
  an `<audio>` element, and calls `.play()`.
- **Cleanup:** `$doc.audioBlob` / `$doc.audioObject` hold the previous
  blob URL + DOM node; next call revokes the URL and removes the node
  first. Comment admits this is "easier than doing it when audio is
  done playing" (line 387).
- **Download hook:** `getLastBlob()` (line 406-408) returns
  `$doc.audioBlob` so the edit dialog can surface an `<a download>`
  anchor naming the file `audio-<yyyyMMdd-HHmm>.circuitjs.wav`
  (AudioOutputElm.java:194-206).

**Coupling path:** `AudioInputElm → AudioOutputElm.lastSamplingRate`
(line 188) — the input element directly mutates a static field on the
output element class to synchronize playback rate. This is a
**pull-less, one-way handshake**; if `AudioOutputElm` is constructed
before any `AudioInputElm` has loaded a file, the default 8000 Hz is
used.

## AM/FM Source Numerics

### AM — double-sideband full-carrier, 100 % modulation

```
v_AM(t) = ((sin(2π·f_s·Δt) + 1) / 2) · sin(2π·f_c·Δt) · V_max
        = (1/2)·(1 + sin(2π·f_s·Δt)) · sin(2π·f_c·Δt) · V_max
```
where `Δt = t - freqTimeZero`. The envelope is `(1+sin)/2 ∈ [0, 1]`
rather than the usual DSB `(1 + m·sin)` with `m < 1`; this gives 100 %
modulation depth and the envelope touches zero once per signal cycle
("over-modulation" is avoided because the envelope is non-negative).
Spectrum: carrier at `f_c` plus sidebands at `f_c ± f_s`, each at
amplitude `V_max/4`.

### FM — phase-integration, Euler step

The instantaneous frequency is `f_c + Δf·sin(2π·f_s·t)` with deviation
`Δf = deviation`. The phase is accumulated per step:
```
funcx ← funcx + (t_now - t_prev) · (f_c + Δf·sin(2π·f_s·t_now))
v_FM(t) = sin(2π · funcx) · V_max
```
**Integration order:** forward-Euler on `funcx`. Error accumulates
linearly with `Δt · |∂f/∂t|`; typically negligible because CircuitJS1's
global `timeStep ≤ 1/(8·samplingRate)` for audio-enabled circuits.

**Reset behavior:** `FMElm.reset()` (line 67-70) zeros `freqTimeZero` and
`curcount`, but **not** `funcx` or `lasttime`. `lasttime` starts at 0
(field initializer) and drifts — an initial `deltaT` of `simulator().t`
(potentially seconds, if the circuit was running before the FM source
was placed) is injected into `funcx` on the first step. Issue #3 below.

### Shared patterns

Both AM and FM:
- `getPostCount() == 1`, `hasGroundConnection(0) == true` — implicitly
  grounded at post 0.
- `getVoltageSourceCount() == 1`; `stamp()` calls `stampVoltageSource(0,
  getNode(0), voltSource)` once; `doStep()` calls
  `updateVoltageSource(0, getNode(0), voltSource, getVoltage())` every
  Newton iteration (= typically once per timestep since these are linear).
- `getPower() = -V·I` with the source sign convention; `current` is
  populated by the simulator via `setCurrent`.
- Draw: `drawThickCircle` of radius 17 at `point2`, "AM"/"FM" label text,
  current dots animated from `updateDotCount(-current, curcount)`.

## Antenna and Crystal Physics

### Antenna — scripted synthetic ether

Not a physics model — it's a stage prop. Hardcoded three AM carriers at
**3000, 2710, 2433 Hz** (not real broadcast bands; scaled for the
simulator's audio-frequency timescale), each amplitude-modulated by a
slow carrier (12, 13, 14 Hz) with DC-offset amplitude factor `1.3`.
A fourth contribution is an integrated-FM term at ~2200 Hz with
deviation 100 Hz, also modulated by the 13 Hz signal. All channels sum
into a single voltage at the antenna's post.

Voltage excursion: worst-case ≈ `3·(1.3+1)·3 + 3 = 23.7 V`. Typical
crystal-radio demo couples this antenna to a parallel LC tank + diode
detector (via the `CrystalElm` and `DiodeElm` on the same sheet).

Because `AntennaElm extends RailElm`, it inherits the full
`VoltageElm → RailElm` voltage-source stamping. The override of
`getVoltage()` + `stepFinished()` are the only new physics.

### Crystal — BVD equivalent circuit

The **Butterworth–Van Dyke** model is the canonical quartz-crystal
small-signal equivalent:
```
            ┌──── C_p ────┐
      A ────┤              ├──── B
            └─ C_s ─ L ─ R ┘
```
- **Series (motional) branch** C_s – L – R: resonates at
  `f_s = 1/(2π·√(L·C_s))` with Q = `(1/R)·√(L/C_s)`. This is the
  fundamental quartz mechanical resonance rescaled to electrical.
- **Parallel (shunt) capacitance** C_p: electrode/holder capacitance.
  Parallel-resonance (anti-resonance) at
  `f_p = f_s · √(1 + C_s/C_p)` ≈ `f_s · (1 + C_s/(2·C_p))` (tight for
  `C_s ≪ C_p`, which is always true).
- Default values (10 MHz-class crystal): C_p = 28.7 pF, C_s = 0.1 pF,
  L = 2.5 mH, R = 6.4 Ω → Q ≈ 24,600.

Implementation (`CrystalElm.java:33`): model string parsed into a 4-node
sub-netlist (nodes `1..4`, externals `{1, 2}`). `CompositeElm` handles
the rest — stamp/step/reset are all forwarded to the child
capacitor/inductor/resistor instances. Node 4 is internal only and
sits between L and R.

**`stepFinished()` override** (CrystalElm.java:131-134): forces
`current = getCurrentIntoNode(1)` so `getBasicInfo` reports a usable
through-current. Without this, `CompositeElm.stepFinished` leaves
`current == 0` (CompositeElm does not synthesize an element-wide current).

## Validation Rules

- **AudioInputElm** (AudioInputElm.java:112-120):
  - Guards `data == null` → returns 0. Prevents crash before file-load.
  - Guards `iptr >= data.length()` → returns 0. Prevents
    out-of-bounds past the end of the buffer (but does not wrap; the
    rail goes silent once the file finishes playing).
  - Clamps `timeOffset < startPosition` → `timeOffset = startPosition`
    on every sample. Serves as a lazy reset if `reset()` was skipped.
- **AudioOutputElm**:
  - `setEditValue(n=0)` requires `ei.value > 0` before accepting a new
    duration (line 212-215) — prevents a zero-size buffer.
  - `play()` line 419-422: refuses to play if fewer than 50 ms of data
    accumulated, emitting `Window.alert(...)` "Audio data is not ready
    yet". The guard is `ct · sampleStep < .05`.
  - `setTimeStep()` guards against redundant calls by checking
    `simulator.maxTimeStep != target` before prompting the user.
- **AMElm / FMElm** constructor: clears `FLAG_COS` on parse (line 51-53
  / 53-55) — legacy-flag normalization. No value range validation
  (EditInfo min/max are hints, not enforced).
- **CrystalElm** setEditValue (line 162-172): each of the four scalars
  is rejected if `ei.value <= 0` (prevents log(negative) / div-by-zero
  in the resonance-frequency display). `initCrystal()` is called
  unconditionally at the end, so a rejected row still re-pushes the
  (unchanged) good values into children.
- `AntennaElm.getEditInfo` returns `null` for every `n` — the element
  is locked-down, no user edits.

Absent validation (issues):
- **AudioOutputElm** does not validate `samplingRate` is in the choice
  list; if a legacy dump contains a non-standard rate it is accepted
  silently.
- **CrystalElm** does not validate `L·C_s > 0` before emitting the
  `fs = ...` info line — would divide by zero if somehow both went to
  zero despite the `> 0` guards.
- **FMElm** does not validate `deviation < carrierfreq` — large
  deviations can make `funcx` integrate through negative instantaneous
  frequency without any user warning.

## Integration Points

### Upstream (called by these elements)

- `CircuitSimulator.stampVoltageSource(stampValue, node, vsIdx)` —
  AMElm, FMElm (CircuitElm default used by RailElm for AudioInput/Antenna).
- `CircuitSimulator.updateVoltageSource(stampValue, node, vsIdx, v)` —
  AMElm.doStep, FMElm.doStep, RailElm.doStep.
- `CircuitSimulator.t`, `CircuitSimulator.timeStep`,
  `CircuitSimulator.maxTimeStep` — every source reads `t`; AudioInput
  advances on `timeStep`; AudioOutput writes `maxTimeStep`.
- `CircuitElm.simulator()`, `circuitEditor()`, `cirSim()`,
  `displaySettings()` — the usual per-element accessors.
- `CircuitElm.interpPoint`, `interpPoint2`, `setBbox`, `adjustBbox`,
  `setVoltageColor`, `setPowerColor`, `drawThickLine`, `drawThickCircle`,
  `drawCenteredText`, `drawDots`, `drawPosts`, `updateDotCount` —
  inherited drawing helpers.
- `CompositeElm` constructor + `compElmList` — CrystalElm exclusively.
- `CapacitorElm.setCapacitance/getCapacitance`, `InductorElm.setInductance/
  getInductance`, `ResistorElm.setResistance/getResistance` — CrystalElm
  pushes/pulls child parameters through these.

### Downstream (what imports these)

- **`CircuitElmCreator` / `CircuitElementFactory`** — each dump-type code
  (411, 211, 200, 201, 'A', 412) is registered there to route legacy-text
  loads to the right constructor.
- **Menu builder** — all six appear in the **Inputs & Sources** menu
  (AMElm, FMElm, AudioInputElm, AntennaElm) and **Passive Components**
  menu (CrystalElm), **Outputs & Labels** (AudioOutputElm). (Menu
  plumbing lives outside `element/`; see `docs/elements.md`.)
- **`RailElm`** — parent of AudioInputElm and AntennaElm; both override
  `getVoltage()` + `drawRail()`.
- **`CompositeElm`** — parent of CrystalElm.

### Cross-element coupling

- `AudioInputElm.gotAudioData` writes `AudioOutputElm.lastSamplingRate`
  (line 188) — the only inter-element direct coupling in the
  category. One-way, static-field based.
- `AudioOutputElm.okToChangeTimeStep` is a **class-level latch**: once
  the user clicks through the "adjust timestep?" confirm dialog on any
  audio output instance, all future audio outputs in the same session
  skip the prompt. Lives on the class for session lifetime.

### Persistence

| Element | Dump format (after `super.dump()`) |
|---|---|
| AudioInputElm | `maxVoltage startPosition fileNum` (PCM data NOT persisted; only `fileNum` → `audioFileMap` key) |
| AudioOutputElm | `duration samplingRate labelNum` |
| AMElm | `carrierfreq signalfreq maxVoltage` |
| FMElm | `carrierfreq signalfreq maxVoltage deviation` |
| AntennaElm | (nothing extra) — `super.dump()` only |
| CrystalElm | `super.dump()` (CompositeElm dumps children; the scalars are implicit in the child C/L/R dumps) |

JSON (`getJsonTypeName()` values): `AudioInput`, `AudioOutput`,
`AMSource`, `FMSource`, `Antenna`, `Crystal`. All six override
`getJsonProperties()` with unit-labeled scalars; AudioOutput and AM/FM
override `getJsonPinNames()` (`"input"` / `"output"`); Crystal exposes
pins `{"a", "b"}`.

## Issues

1. **Audio buffer is not reset on simulator reset.**
   `AudioOutputElm.reset()` (line 92-98) zeros `dataPtr`, `dataFull`,
   `dataSampleCount`, `nextDataSample`, `dataSample` — but does **not**
   zero `data[]` nor re-anchor `nextDataSample` relative to
   `simulator().t`. If the user hits reset mid-playback, `nextDataSample`
   stays at its pre-reset absolute-time value, meaning the boxcar
   decimator may lag/lead by up to `duration` seconds before catching up.
   Compare `setDataCount()` (line 165-174) which correctly computes
   `nextDataSample = simulator.t + sampleStep`. The reset path should
   call `setDataCount()` or inline its `nextDataSample` calculation.

2. **AudioInputElm undo/redo leaks `audioFileMap` entries.**
   The static `audioFileMap` is never pruned when an audio-input
   element is deleted permanently — only the static `clearCache()`
   (line 203-205) empties it, and nobody calls `clearCache()` on
   delete. Each loaded file stays in memory until the tab is closed.
   `fileNumCounter` monotonically increases and never reclaims indices.

3. **FMElm phase state is not fully reset.**
   `FMElm.reset()` zeros `freqTimeZero` and `curcount` but not `funcx`
   or `lasttime`. After a simulator reset, the phase accumulator can
   retain an arbitrary value, causing the output to resume with
   whatever phase offset it had accumulated before reset. Same concern
   for `AntennaElm.fmphase` (never reset). `AMElm` is not affected
   because it is stateless between calls.

4. **`AMElm.FLAG_COS` and `FMElm.FLAG_COS` are dead flags.**
   Both constructors parse and immediately clear the flag (AMElm.java:51-53,
   FMElm.java:53-55). Presumably an abandoned feature. Either implement
   a cosine-phase variant or drop the parser branch + flag constant.

5. **AudioInput past-end-of-file silence vs loop.**
   When `iptr >= data.length()`, the element returns 0 (line 120).
   There is no option to loop or signal end-of-file. A user who wants
   to drive a long-duration simulation with a short audio clip has no
   obvious recourse.

6. **AntennaElm has zero user-configurability.**
   Hardcoded frequencies (3000, 2710, 2433 Hz carriers; 12, 13, 14 Hz
   signals; 2200 Hz FM base; 100 Hz FM deviation; all amplitudes ×3)
   mean this element cannot be used outside the specific
   crystal-radio demo. `getEditInfo(n) == null` and
   `getShortcut() == 0`. Either expose edit rows or rename to
   `CrystalRadioAntennaDemoElm`.

7. **`AudioOutputElm.setTimeStep()` side-effect on maxTimeStep.**
   The element *globally* reduces `simulator.maxTimeStep` to
   `sampleStep/8` — an ≥8× oversample of the audio rate. For a 48 kHz
   output that forces `maxTimeStep ≤ 2.6 µs`, slowing every other
   simulation in the document. The static `okToChangeTimeStep` latch
   means the prompt only appears once per session; thereafter any
   added `AudioOutputElm` silently throttles the simulator. Consider
   making the oversample ratio configurable, or warning when the
   implied timestep is below 1 µs.

8. **AudioOutputElm aliases if sampleStep/timeStep is non-integer.**
   Commented-out code (line 247-259) notes "make sure sampleStep/timeStep
   is an integer. otherwise we get distortion". The author settled on
   "just make timestep = 1/sampleRate" then changed to 1/(8·sampleRate).
   The current averaging decimator (line 150-163) handles non-integer
   ratios approximately by running-average over a variable number of
   samples per output, but the average count differs by ±1 sample
   between adjacent output periods → residual quantization noise at
   high audio frequencies.

9. **CrystalElm resonance math in the info pane is shown only as `fs`.**
   No parallel resonance `fp`, no Q, no effective impedance at `fs`.
   A crystal-radio tutorial user would benefit from those.

10. **Dump-type collisions risk.**
    Numeric codes 200 (AM), 201 (FM), 211 (AudioOut), 411 (AudioIn),
    412 (Crystal), and char 'A' (65, Antenna) are scattered magic
    numbers. Same concern raised in element-base issue #9: no central
    registry of dump types.

11. **`AudioOutputElm` does not override `getShortcut()`** — inherits
    the default 0. AudioInputElm explicitly overrides to 0 (line 134-136)
    to declare "no shortcut", which is pedantically nicer than silent
    inheritance.

12. **AMElm `setPoints()` interpPoint uses `lead1` field but AMElm has
    its own `lead1`, not `geom().getLead1()`.** Compare to FMElm
    (line 123-128) which uses `geom().getLead1()`. AMElm's `lead1` is a
    private `Point` (AMElm.java:35); FMElm uses the geometry-owned
    lead. Minor inconsistency; both work because `drawThickLine` just
    reads a `Point` reference.

## Concept Boundary: single "audio-and-rf" concept

A **single `audio-and-rf` concept** covering all six elements is the
right granularity:

- All six depend on **closed-form analytic voltage-source stamping**
  (or, for AudioOutput, analytic sampling of a node voltage); none
  contribute to the Newton iteration.
- All six are **1-terminal grounded** conceptually except CrystalElm
  (which is 2-terminal but passive). The shared hard-grounded-source
  pattern is the pedagogical hook.
- The audio bridge (AudioInput/Output) is a **prerequisite** for the
  AM/FM/Antenna/Crystal demo set — a crystal-radio circuit wiring a
  `AntennaElm → CrystalElm(LC) → DiodeElm → AudioOutputElm` is
  precisely the educational target for this whole category. Splitting
  audio-I/O from the RF-source catalog would force each sub-concept
  to redocument that wiring pattern.
- `CrystalElm` is the only physics-real element and technically
  belongs with `cat-passives`, but it is so tightly coupled to the
  crystal-radio demo that grouping it here (with AntennaElm) is
  conceptually cleaner than splitting it out.

If a finer cut is ever needed:
1. **audio-io** (AudioInputElm, AudioOutputElm) — Web Audio bridge,
   JSNI, file-upload, WAV playback.
2. **rf-sources** (AMElm, FMElm, AntennaElm) — closed-form modulation
   sources.
3. **quartz-resonator** (CrystalElm) — the single BVD composite.

The current size (6 files, ~1100 LOC total) does not warrant the split.
