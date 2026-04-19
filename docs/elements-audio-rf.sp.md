# Audio & RF Elements — Specification  {#SP_EAR}

> **Code:** SP_EAR
> **Status:** draft
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_EAR](./elements-audio-rf.concept.md)
> **Depends on specs:** [SP_ELB](./element-base.sp.md), [SP_UTL](./util-locale-log.sp.md), [SP_GEO](./geometry.sp.md), [SP_RND](./rendering-primitives.sp.md), [SP_ESRC](./elements-sources.sp.md)
> **Used by specs:** circuit element factory, IO framework
> **Plan:** [elements-audio-rf.plan.md](./elements-audio-rf.plan.md)
>
> **Backing analysis:** [.dev_flow/onboard/analysis/domain-core__cat-audio-rf.md](../.dev_flow/onboard/analysis/domain-core__cat-audio-rf.md)
>
> Catalog of 6 audio/RF elements: two Web Audio bridges, two analytic
> RF sources, one scripted antenna, one BVD-model crystal composite.

## 01. Element Catalog  {#SP_EAR_01}

> Implements: [C_EAR_02](./elements-audio-rf.concept.md#C_EAR_02)

### 01_01. Element Table  {#SP_EAR_01_01}

| Element | Extends | Posts | V-src | Linear | Dump | Parameters |
|---|---|---|---|---|---|---|
| `AudioInputElm` | `RailElm` | 1 | 1 (inherited) | yes | 411 | `maxVoltage`, `startPosition`, `fileNum`, static cache |
| `AudioOutputElm` | `CircuitElm` | **1** | 0 | yes | 211 | `duration`, `samplingRate`, `labelNum` |
| `AMElm` | `CircuitElm` | 1 | 1 | yes | 200 | `carrierfreq` (1000 Hz), `signalfreq` (40 Hz), `maxVoltage` (5 V) |
| `FMElm` | `CircuitElm` | 1 | 1 | yes | 201 | `carrierfreq` (800 Hz), `signalfreq` (40 Hz), `maxVoltage` (5 V), `deviation` (200 Hz) |
| `AntennaElm` | `RailElm` | 1 | 1 (inherited) | yes | `'A'` 65 | (none — all hardcoded) |
| `CrystalElm` | `CompositeElm` | 2 | 0 (children) | yes | 412 | `parallelCapacitance` (28.7 pF), `seriesCapacitance` (0.1 pF), `inductance` (2.5 mH), `resistance` (6.4 Ω) |

Invariants:
- 5 out of 6 are hard-grounded at post 0 (`hasGroundConnection(0)==true`);
  CrystalElm is 2-post passive.
- Sampling-rate choices for AudioOutput: `{8000, 11025, 16000, 22050,
  44100, 48000}`.
- AudioInput audio cache keyed by monotonic `fileNumCounter`; never
  pruned on delete.

### 01_02. RF Numerics  {#SP_EAR_01_02}

**AM** — double-sideband full carrier, 100% modulation:

    v_AM(t) = ½·(1 + sin(2π f_s Δt)) · sin(2π f_c Δt) · V_max

Spectrum: carrier at f_c ± sidebands at f_c ± f_s, amplitude V_max/4.

**FM** — forward-Euler on phase accumulator:

    funcx ← funcx + Δt · (f_c + deviation · sin(2π f_s t))
    v_FM = sin(2π · funcx) · V_max

**Antenna** — three AM carriers (3000/2710/2433 Hz, modulator 12/13/14 Hz)
+ one integrated-FM term at 2200 Hz ±100 Hz.

### 01_03. Audio Bridge  {#SP_EAR_01_03}

**AudioInputElm.fetchLoadFileData (JSNI):**

    new AudioContext() → context.sampleRate → setSamplingRate(rate)
    FileReader.readAsArrayBuffer → context.decodeAudioData →
        buffer.getChannelData(0) → gotAudioData(JsArrayNumber)

**AudioOutputElm.playJS (JSNI):**

    int16 samples → RIFF/WAVE container (fmt=18, 1 ch, 0x0001 PCM,
        16-bit) → Blob{type: audio/wav} → URL.createObjectURL →
        <audio>.play()

Side-channel: `AudioInputElm` pushes `samplingRate` into
`AudioOutputElm.lastSamplingRate` (static). Any AudioOutput placed after
gets the matching rate.

### 01_04. Crystal Physics  {#SP_EAR_01_04}

Butterworth-Van Dyke:

    A ──── C_p ────── B
          └─ C_s ─ L ─ R ┘

- Series: `f_s = 1/(2π√(L·C_s))`; Q = `(1/R)·√(L/C_s)`.
- Parallel: `f_p = f_s · √(1 + C_s/C_p)`.
- Defaults → `f_s ≈ 10.06 MHz`, Q ≈ 24 600.

## 02. Contracts  {#SP_EAR_02}

### 02_01. Voltage Source Stamping  {#SP_EAR_02_01}

Processing logic (AM/FM):

    stamp(): stampVoltageSource(0, getNode(0), voltSource)
    doStep(): updateVoltageSource(0, getNode(0), voltSource, getVoltage())

### 02_02. AudioOutput Decimator  {#SP_EAR_02_02}

    stepFinished:
        dataSample += getNodeVoltage(0)
        dataSampleCount += 1
        IF simulator.t >= nextDataSample:
            data[dataPtr++] = dataSample / dataSampleCount
            dataSample = 0; dataSampleCount = 0
            nextDataSample += sampleStep
            IF dataPtr >= dataCount: dataPtr=0; dataFull=true

### 02_03. Crystal Composite Delegation  {#SP_EAR_02_03}

    ctor: super(doc, xx, yy, modelString, {1,2})
        where modelString = "CapacitorElm 1 2\r..."
    setEditValue: update scalar; initCrystal() re-pushes 4 values
        into children.
    stepFinished: current = getCurrentIntoNode(1)

## 03. Validation Rules  {#SP_EAR_03}

### 03_01. Input Validation  {#SP_EAR_03_01}

- **AudioInputElm.getVoltage**: null data → 0; `iptr >= data.length` → 0;
  clamp `timeOffset >= startPosition`.
- **AudioOutputElm.setEditValue(n=0)**: `ei.value > 0`.
- **AudioOutputElm.play**: refuses if < 50 ms buffered (user alert).
- **AMElm/FMElm**: ctor clears legacy `FLAG_COS`; no value-range
  enforcement (EditInfo bounds are hints).
- **CrystalElm.setEditValue**: each scalar must be `> 0`; rejected rows
  retain previous valid values; `initCrystal()` called regardless.
- **AntennaElm.getEditInfo**: returns null for every `n` (no user edits).
- **AudioOutputElm.setTimeStep**: prompts once per session via static
  `okToChangeTimeStep` latch; forces `maxTimeStep = sampleStep/8`.

### 03_02. Absent validation (known gaps)  {#SP_EAR_03_02}

- AudioOutputElm does not validate that `samplingRate` is in the allowed
  choice list — legacy dumps with non-standard rates accepted silently.
- CrystalElm does not validate `L·C_s > 0` before emitting `f_s` info.
- FMElm does not validate `deviation < carrierfreq`.

## 04. State Transitions  {#SP_EAR_04}

### 04_01. FMElm phase accumulator  {#SP_EAR_04_01}

    funcx_{t+1} = funcx_t + (t - lasttime) · (f_c + sin(2π f_s t) · deviation)
    # NOTE: funcx and lasttime NOT zeroed by reset() — Issue #3

### 04_02. AudioOutput buffer  {#SP_EAR_04_02}

    [empty] --stepFinished--> [partial]
    [partial] --dataPtr>=dataCount--> [wrapped/full]
    [*] --reset--> [empty] (data array NOT zeroed)

## 05. Verification Criteria  {#SP_EAR_05}

### 05_01. Functional Expectations  {#SP_EAR_05_01}

| Contract | Scenario | Input | Expected |
|---|---|---|---|
| AMElm | f_c=1 kHz, f_s=40 Hz, V=5 | at t=peak | v ≈ ±5 V peak |
| FMElm | deviation=200 Hz, f_c=800 Hz | long run | instantaneous freq ∈ [600, 1000] Hz |
| AntennaElm | — | — | sum of 4 hardcoded components |
| AudioInputElm | 3-sample file, rate=8k | t=1/16000 | linear interp between samples |
| AudioOutputElm.play | < 50 ms buffered | — | Window.alert "not ready" |
| CrystalElm | defaults | — | getInfo shows `fs ≈ 10.06 MHz` |

### 05_02. Invariant Checks  {#SP_EAR_05_02}

| Invariant | Verification |
|---|---|
| 5/6 elements are 1-post grounded | assertion on hasGroundConnection(0) |
| CrystalElm is CompositeElm with 4 children | children list check |
| AudioOutputElm maxTimeStep ≤ sampleStep/8 after setup | simulator state |

### 05_03. Integration Scenarios  {#SP_EAR_05_03}

| Scenario | Preconditions | Steps | Expected |
|---|---|---|---|
| Crystal radio demo | Antenna → Crystal LC → Diode → AudioOutput | run | demodulated envelope audible |
| Audio passthrough | AudioInput → wire → AudioOutput | load WAV, play | output matches input |
| Samplingrate sync | AudioInput loads 44.1k file | observe AudioOutput | its lastSamplingRate updated |

### 05_04. Edge Cases and Boundaries  {#SP_EAR_05_04}

| Case | Input | Expected |
|---|---|---|
| AudioInput EOF | past last sample | 0 V (no loop option) |
| FMElm after reset | funcx non-zero | phase drift on resume (Issue #3) |
| Crystal with C_s=0 | rejected by setter | last-good C_s retained |
| AudioOutputElm sampleStep/timeStep non-integer | — | boxcar averages ±1 sample residual |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
