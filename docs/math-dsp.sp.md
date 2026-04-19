# Math / DSP Utilities — Specification  {#SP_MDS}

> **Code:** SP_MDS
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_MDS](./math-dsp.concept.md)
> **Depends on specs:** — (Layer 0)
> **Used by specs:** simulator-core, Scope, element-base, io-text (populated at higher layers)
> **Plan:** [math-dsp.plan.md](./math-dsp.plan.md)
>
> Numerical primitives (`CircuitMath`, `FFT`, `RandomUtils`), parsing helper
> (`StringTokenizer`), and shared constants (`CircuitConst`).
>
> Backing analysis: [.dev_flow/onboard/analysis/root-utils.md](../.dev_flow/onboard/analysis/root-utils.md)

## 01. Data Structures  {#SP_MDS_01}

> Implements: [C_MDS_02](./math-dsp.concept.md#C_MDS_02)

### 01_01. CircuitMath  {#SP_MDS_01_01}

Static utility with nested result types: `FreqData`, `WaveformMetrics`,
`DutyCycleInfo` (public static plain data classes). Diagnostics:
`private static volatile int lastLuFailColumn, lastLuFailRow; double lastLuFailPivotAbs`.

### 01_02. FFT  {#SP_MDS_01_02}

Fields: `int size`, `int bits` (= log2(size)), `double[] cosTable`,
`double[] sinTable` (length `size/2`), `double[] winTable` (length `size`;
sine window × 1.5707963 for unity gain).
Invariant: `size` must be a power of 2.

### 01_03. RandomUtils  {#SP_MDS_01_03}

`private final static Random random = new Random();`

### 01_04. StringTokenizer  {#SP_MDS_01_04}

Fields: `int pos`, `int start` (never written — dead), `final String str`,
`final int len`, `String delim`, `final boolean retDelims`.
Implements `Enumeration<Object>`.

### 01_05. CircuitConst  {#SP_MDS_01_05}

Interface. Constants:
| Name | Value | Group |
|------|-------|-------|
| RC_RETAIN | 1 | restore flags (bit) |
| RC_NO_CENTER | 2 | restore flags (bit) |
| RC_SUBCIRCUITS | 4 | restore flags (bit) |
| RC_KEEP_TITLE | 8 | restore flags (bit) |
| HINT_LC | 1 | auto-hint |
| HINT_RC | 2 | auto-hint |
| HINT_3DB_C | 3 | auto-hint |
| HINT_TWINT | 4 | auto-hint |
| HINT_3DB_L | 5 | auto-hint |

## 02. Contracts  {#SP_MDS_02}

### 02_01. CircuitMath.isConverged(double, double)  {#SP_MDS_02_01}

Returns `true` when relative error < 1% or both values ≈ 0.

### 02_02. CircuitMath.lu_factor(double[][] a, int n, int[] ipvt)  {#SP_MDS_02_02}

Purpose: in-place Crout LU factorization. Output: `boolean` (success).
Errors: sets static diagnostics on failure (pivot < 1e-14).

### 02_03. CircuitMath.lu_solve(double[][] a, int n, int[] ipvt, double[] b)  {#SP_MDS_02_03}

Purpose: in-place solve with previously factored `a`. Output: `void`; `b`
holds solution. Assumes prior `lu_factor` success (no singularity check).

### 02_04. CircuitMath.invertMatrix  {#SP_MDS_02_04}

`lu_factor` then `n` `lu_solve` calls on identity columns; does not check
the factor return value.

### 02_05. Scope metrics  {#SP_MDS_02_05}

- `calculateAverage(minV[], maxV[], ptr, width, scopePointCount)` — ring
  average; requires `scopePointCount` power of 2.
- `calculateFrequency(...) → FreqData` — rising-edge around `0.05*avg`;
  rejects periods < 12 samples; SD > 2 → zero.
- `calculateWaveformMetrics(...) → WaveformMetrics` — two-pass RMS/avg
  over full cycles; `valid=false` if < 1 cycle.
- `calculateDutyCycle(...) → DutyCycleInfo` — duty as integer percent.

### 02_06. FFT API  {#SP_MDS_02_06}

- `new FFT(int n)` — precomputes tables; `n` must be power of 2.
- `fft(double[] real, double[] imag, boolean windowed)` — in-place DIT.
- `getSize() → int`, `magnitude(double re, double im) → double`
  (= `sqrt(re²+im²)/size`).

### 02_07. RandomUtils  {#SP_MDS_02_07}

- `random` — shared `Random`.
- `getRand(int x) → int` — convenience wrapper (caveats in edge cases).

### 02_08. StringTokenizer API  {#SP_MDS_02_08}

Standard 1.4 methods (`hasMoreTokens`, `nextToken`, `nextToken(String)`,
`countTokens`) plus project-local `tryNextToken` (null instead of throw),
`getEndTokenIdx`, `getStartTokenIdx` (always 0; dead), `getOriginalString`.

## 03. Validation Rules  {#SP_MDS_03}

- `lu_factor`: `n<0` → false, `n==0` → true, `n==1` degenerate handled;
  pivot threshold `1e-14`.
- `calculateFrequency`: periods < 12 samples filtered, SD > 2 zeroed.
- Scope metrics require `scopePointCount` to be power of 2.
- `FFT`: input arrays equal length, length = power of 2.
- `RandomUtils.getRand(0)` not defensive → `ArithmeticException`.
- `StringTokenizer.nextToken` throws `NoSuchElementException` when empty;
  `tryNextToken` returns null instead.

## 04. State Transitions  {#SP_MDS_04}

Mostly N/A (static utilities). Exceptions:

- `CircuitMath` diagnostics (`lastLuFail*`) updated globally on each
  `lu_factor` call.
- `StringTokenizer.pos` advances monotonically; `delim` may change via
  `nextToken(String)`.

## 05. Verification Criteria  {#SP_MDS_05}

### 05_01. Functional Expectations  {#SP_MDS_05_01}

| Contract | Scenario | Input | Expected outcome |
|----------|----------|-------|------------------|
| lu_factor | 3×3 non-singular | random matrix | returns true; `lu_solve` reproduces `b` |
| lu_factor | singular row | zero row | false; diagnostics set |
| fft | length 8 sine | pure tone | spectral peak at the expected bin |
| calculateFrequency | 1 kHz sine | samples | freq ≈ 1000 Hz |
| tryNextToken | exhausted | — | null |

### 05_02. Invariant Checks  {#SP_MDS_05_02}

| Invariant | Verification method |
|-----------|--------------------|
| FFT size is power of 2 | ctor contract — misuse is caller's fault |
| Ring-buffer size power of 2 | inspect scope configuration |

### 05_03. Integration Scenarios  {#SP_MDS_05_03}

| Scenario | Preconditions | Steps | Expected result |
|----------|--------------|-------|-----------------|
| Simulator step | admittance matrix built | lu_factor → lu_solve | node voltages produced |
| Spectrum view | Scope with sample buffer | fft(buffer) | magnitude vector for display |

### 05_04. Edge Cases and Boundaries  {#SP_MDS_05_04}

| Case | Input | Expected behavior |
|------|-------|-------------------|
| invertMatrix on singular | zero row | silently proceeds — known bug |
| getRand(Integer.MIN_VALUE) | — | returns negative — known bug |
| getStartTokenIdx | any | returns 0 — dead field |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure |
