# Math / DSP Utilities  {#C_MDS}

> **Code:** C_MDS
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard
>
> **Depends on:** none (Layer 0 leaf)
> **Used by:** simulator core (CirSim, CircuitSimulator), Scope, element Elm classes (LU/scope metrics/noise/random/tokenized parsing), CircuitConst consumers
> **Spike:** —
> **Specification:** [SP_MDS](./math-dsp.sp.md)
> **Plan:** [math-dsp.plan.md](./math-dsp.plan.md)
>
> Numerical and string-tokenization helpers at the client root: `CircuitMath`
> (LU solver + scope/waveform metrics), `FFT` (radix-2), `RandomUtils`
> (shared `Random`), `StringTokenizer` (GNU Classpath port with positional
> accessors), and `CircuitConst` (shared bitflags / hint codes).

## 1. Philosophy  {#C_MDS_01}

### 1.1. Core Principle  {#C_MDS_01_01}

The simulator needs numeric kernels (matrix solve, frequency analysis,
waveform metrics, randomness) and a text-format parsing aid. These are
packaged as stateless/static utilities so any layer can use them without
instantiation. `CircuitConst` defines shared symbolic codes (restore flags,
auto-hint IDs) as interface constants.

### 1.2. Design Constraints  {#C_MDS_01_02}

- Single-threaded (GWT); shared singletons are safe.
- `CircuitMath.lu_factor` stores failure diagnostics in `volatile` statics.
- `FFT` assumes input length is a power of 2; scope metrics assume
  ring-buffer size is a power of 2.
- `StringTokenizer` is a near-verbatim GNU Classpath port (preserves
  `java.util.StringTokenizer` 1.4 semantics) plus project-local helpers
  (`tryNextToken`, positional accessors).
- `CircuitConst` is an interface used as a constants bag (pre-Java-5 idiom).

## 2. Domain Model  {#C_MDS_02}

### 2.1. Key Entities  {#C_MDS_02_01}

- **CircuitMath** — static: `isConverged`, `lu_factor` (Crout with partial
  pivoting), `lu_solve`, `invertMatrix`, and scope analytics
  (`calculateAverage`, `calculateFrequency → FreqData`,
  `calculateWaveformMetrics → WaveformMetrics`,
  `calculateDutyCycle → DutyCycleInfo`). Diagnostics statics:
  `lastLuFailColumn/Row/PivotAbs`.
- **FFT** — instance with precomputed `cosTable`, `sinTable`, `winTable`
  (sine window × 1.5707963 gain). Radix-2 DIT in-place.
- **RandomUtils** — singleton `java.util.Random` and `getRand(int)` helper.
- **StringTokenizer** — `Enumeration<Object>` with `pos`, `str`, `len`,
  `delim`, `retDelims`; positional accessors for parsing error messages.
- **CircuitConst** — interface with bitflags `RC_RETAIN`, `RC_NO_CENTER`,
  `RC_SUBCIRCUITS`, `RC_KEEP_TITLE` and hint codes `HINT_LC`, `HINT_RC`,
  `HINT_3DB_C`, `HINT_TWINT`, `HINT_3DB_L`.

### 2.2. Data Flows  {#C_MDS_02_02}

- Simulator step: build admittance matrix → `lu_factor` → `lu_solve` per
  time step; on failure, static diagnostics populated.
- Scope: collects ring-buffer samples → `calculateFrequency` /
  `calculateWaveformMetrics` / `calculateDutyCycle` → displays on overlay.
- FFT: `Scope.java` spectrum view passes sample arrays to `FFT.fft`.
- Noise / random elements: `RandomUtils.getRand` / `random.nextDouble`.
- Text IO: importers tokenize legacy circuit lines via `StringTokenizer`.
- Circuit load: loaders interpret `RC_*` flags and `HINT_*` codes from
  `CircuitConst`.

## 3. Mechanisms  {#C_MDS_03}

### 3.1. Core Algorithm  {#C_MDS_03_01}

- **LU (Crout) with partial pivoting**, pivot threshold `1e-14`. On failure
  sets static diagnostics and returns false. `lu_solve` performs forward
  substitution with row swaps, then back substitution.
- **FFT** — bit-reversal permutation then log2(N) butterfly stages using
  precomputed twiddle tables. Optional sine window pre-applied.
- **Scope metrics** — rising-edge detection around `0.05 * avg` threshold,
  rejecting periods < 12 samples; SD > 2 → zero frequency. Two-pass RMS
  over full cycles for `WaveformMetrics`. Duty cycle from rising/falling
  edge averaging.
- **StringTokenizer** — mirrors Sun 1.4 semantics; extra positional fields
  enable parse-error messages quoting the offending token's position.

### 3.2. Edge Cases  {#C_MDS_03_02}

- `lu_factor(n=0)` trivially succeeds; `n<0` returns false; singular matrix
  → diagnostics set.
- `invertMatrix` does **not** check `lu_factor` return; silently proceeds on
  singular input.
- `RandomUtils.getRand(0)` throws `ArithmeticException`; `Integer.MIN_VALUE`
  negation classic bug (still negative).
- `StringTokenizer.start` field is never assigned — `getStartTokenIdx`
  always returns 0.
- `FFT.magnitude` divides by `size` (not `size/2`) — consumer trap.

## 4. Integration Points  {#C_MDS_04}

### 4.1. Dependencies  {#C_MDS_04_01}

- Java: `java.util.Random`, `Enumeration`, `NoSuchElementException`.
- No project-internal imports.

### 4.2. API Surface  {#C_MDS_04_02}

- `CircuitMath.isConverged`, `lu_factor`, `lu_solve`, `invertMatrix`,
  `calculateAverage`, `calculateFrequency`, `calculateWaveformMetrics`,
  `calculateDutyCycle`.
- `FFT(int n)`, `fft(double[] real, double[] imag, boolean windowed)`,
  `getSize()`, `magnitude(double, double)`.
- `RandomUtils.random`, `getRand(int)`.
- `StringTokenizer(String [, String [, boolean]])`, `hasMoreTokens`,
  `nextToken`, `nextToken(String)`, `countTokens`, `tryNextToken`,
  `getEndTokenIdx`, `getStartTokenIdx`, `getOriginalString`.
- `CircuitConst` constants: `RC_*`, `HINT_*`.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure |
