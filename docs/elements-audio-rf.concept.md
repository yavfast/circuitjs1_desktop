# Audio & RF Elements  {#C_EAR}

> **Code:** C_EAR
> **Status:** draft
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** OnboardDocGen
>
> **Depends on:** [C_ELB](./element-base.concept.md), [C_UTL](./util-locale-log.concept.md), [C_GEO](./geometry.concept.md), [C_RND](./rendering-primitives.concept.md), [C_ESRC](./elements-sources.concept.md)
> **Used by:** circuit element factory, IO framework, simulator timestep
> **Spike:** —
> **Specification:** [SP_EAR](./elements-audio-rf.sp.md)
> **Plan:** [elements-audio-rf.plan.md](./elements-audio-rf.plan.md)
>
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-audio-rf.md](../.dev_flow/onboard/analysis/domain-core__cat-audio-rf.md)
>
> Bridges the simulator to the host browser's Web Audio API and provides
> the RF demo elements (AM/FM/Antenna/Crystal). Six elements: two I/O
> bridges, two closed-form RF voltage sources, one hardcoded "ether"
> antenna, and one BVD-model quartz crystal composite.

## 1. Philosophy  {#C_EAR_01}

### 1.1. Core Principle  {#C_EAR_01_01}

The audio-and-RF category exists to support **crystal-radio-class demo
circuits**. The pattern that binds the six elements is
**closed-form analytic voltage-source stamping**:

- `AMElm`, `FMElm`, `AntennaElm` write a 1-post grounded voltage source
  whose value is an analytic function of `simulator().t`.
- `AudioInputElm` is the same pattern with the function replaced by a
  file-backed sample lookup.
- `AudioOutputElm` inverts the direction: it *reads* `getNodeVoltage(0)`
  each step, decimates into a WAV buffer, and plays through the browser.
- `CrystalElm` is a **CompositeElm** holding C‖(C-L-R) — the
  Butterworth-Van Dyke quartz model; no custom stamping.

### 1.2. Design Constraints  {#C_EAR_01_02}

- None of the six is Newton-nonlinear; all are linear voltage sources or
  passive (CrystalElm).
- All five voltage-source-driven elements are **hard-grounded at post 0**
  (`hasGroundConnection(0)==true`) — reference is implicit.
- AudioInput/Output are the only elements that cross the Java↔JS boundary
  via JSNI (Web Audio, FileReader, WAV-builder, Blob URL).
- AudioInputElm's static `audioFileMap` pins PCM data across
  cut/paste/undo — a deliberate leak tolerance, cleared only via
  `clearCache()`.
- AudioOutputElm silently forces `simulator.maxTimeStep ≤ sampleStep/8`
  via a one-time user confirm — session-level side effect.

## 2. Domain Model  {#C_EAR_02}

### 2.1. Key Entities  {#C_EAR_02_01}

- **`AudioInputElm` (extends `RailElm`)** — time-varying voltage rail
  whose `getVoltage()` interpolates file samples at `timeOffset *
  samplingRate`. Static audio cache keyed by `fileNum`.
- **`AudioOutputElm` (extends `CircuitElm`)** — 1-post sink that
  boxcar-decimates `getNodeVoltage(0)` and plays WAV.
- **`AMElm`** — 1-post grounded AM source, 100% modulation:
  `v = ((1+sin(2πf_s t))/2) · sin(2πf_c t) · V_max`.
- **`FMElm`** — 1-post grounded FM source with Euler-integrated phase
  `funcx`.
- **`AntennaElm` (extends `RailElm`)** — scripted synthetic ether; three
  AM carriers + one FM contribution (hardcoded frequencies).
- **`CrystalElm` (extends `CompositeElm`)** — C‖(C-L-R) BVD model with
  nodes `1..4`, externals `{1, 2}`.

### 2.2. Data Flows  {#C_EAR_02_02}

Voltage source update (AM/FM/Antenna/AudioInput):

    stamp (once):  stampVoltageSource(0, node0, vs)
    doStep:        updateVoltageSource(0, node0, vs, getVoltage())

AudioOutput decimator:

    stepFinished:
        dataSample += getNodeVoltage(0); dataSampleCount++
        if simulator.t >= nextDataSample:
            data[dataPtr++] = dataSample / dataSampleCount
            dataSampleCount = 0; dataSample = 0
            nextDataSample += sampleStep
            wrap if dataPtr >= dataCount

Audio bridge (JSNI):

    upload file → AudioContext.decodeAudioData → Float32Array →
        gotAudioData(data) → sets AudioInputElm.data + pushes samplingRate
        to AudioOutputElm.lastSamplingRate (static)

WAV playback (JSNI):

    int16 samples → Wav header builder (RIFF/fmt/data) → Blob →
        URL.createObjectURL → <audio>.play()

Crystal: entirely delegated to CompositeElm's children; custom
`stepFinished` computes through-current `getCurrentIntoNode(1)`.

## 3. Mechanisms  {#C_EAR_03}

### 3.1. Core Algorithm  {#C_EAR_03_01}

**AM (closed-form, 100% depth):**

    w = 2π(t - freqTimeZero)
    v = ((1 + sin(w·f_signal))/2) · sin(w·f_carrier) · V_max

**FM (Euler phase accumulator):**

    deltaT = t - lasttime
    a = sin(2π(t - freqTimeZero)·f_signal)
    funcx += deltaT · (f_carrier + a · deviation)
    v = sin(2π · funcx) · V_max
    lasttime = t

**Antenna (3 AM + 1 FM, hardcoded):**

    v = Σ_{k∈{3000,2710,2433}} sin(2π t k)·(1.3 + sin(2π t f_mod))·3
        + 3·sin(fmphase)
    fmphase += 2π · (2200 + 100·sin(2π t 13)) · timeStep

**AudioInput:** `(v1·(1-frac) + v2·frac) · maxVoltage` where
`iptr = floor(timeOffset · samplingRate)`.

**AudioOutput:** boxcar decimation; 50 ms minimum before `play()`;
fade-in/out over 1/20 s.

**Crystal:** sub-netlist `"CapacitorElm 1 2 | CapacitorElm 1 3 |
InductorElm 3 4 | ResistorElm 4 2"` passed to CompositeElm; default
values give `f_s ≈ 10.06 MHz`.

### 3.2. Edge Cases  {#C_EAR_03_02}

- AudioInput past end-of-file → returns 0 (no loop option).
- FMElm.reset() does **not** reset `funcx` / `lasttime` — phase drifts
  across reset (Issue #3).
- AMElm / FMElm `FLAG_COS` parsed and cleared — dead flag.
- AudioOutput reset does not re-anchor `nextDataSample` — decimator can
  lag for up to `duration` seconds (Issue #1).
- Crystal `initCrystal()` pushes 4 scalars to children on every
  `setEditValue`; rejected scalars still propagate last-good values.
- CouplingCoef analogue: crystals have no such concern (no inversion).

## 4. Integration Points  {#C_EAR_04}

### 4.1. Dependencies  {#C_EAR_04_01}

- [C_ELB](./element-base.concept.md) — `CircuitElm`, `CompositeElm`.
- [C_ESRC](./elements-sources.concept.md) — `RailElm`/`VoltageElm`
  inheritance for `AudioInputElm` and `AntennaElm`.
- `CircuitSimulator` — `stampVoltageSource`, `updateVoltageSource`,
  `timeStep`, `t`, `maxTimeStep`.
- Web Audio API (via JSNI) — AudioInput / AudioOutput.
- `DiodeElm` (typical crystal-radio demo companion — not a hard dep).

### 4.2. API Surface  {#C_EAR_04_02}

- AudioInput: file upload via `FileUpload` widget; `fetchLoadFileData`
  JSNI; `gotAudioData(JsArrayNumber)` callback.
- AudioOutput: `▶ Play Audio` button; `playJS(samples, rate)` JSNI;
  `getLastBlob()` for download anchor.
- AM/FM: edit rows for `carrierfreq`, `signalfreq`, `maxVoltage`,
  `deviation` (FM only).
- Crystal: 4 edit rows (`parallelCapacitance`, `seriesCapacitance`,
  `inductance`, `resistance`); info pane shows `fs`.
- Antenna: no user parameters (`getEditInfo==null`).

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
