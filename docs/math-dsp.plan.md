# Implementation Plan: Math / DSP Utilities  {#PL_MDS}

> **Code:** PL_MDS
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_MDS](./math-dsp.concept.md)
> **Specification:** [SP_MDS](./math-dsp.sp.md)
> **Depends on plans:** none
> **Used by plans:** higher-layer simulator, scope, IO, and element plans
>
> Retrospective plan covering as-built numerical, DSP, random, tokenizer
> helpers, and shared constants.

## Goal

Document the as-built state of `CircuitMath`, `FFT`, `RandomUtils`,
`StringTokenizer`, `CircuitConst` and record backlog items (singular-matrix
handling, `getRand` bugs, dead fields, `CircuitConst` modernization).

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Language | Java 17 source | project-wide |
| Client compile target | GWT 2.12 | browser target |
| LU method | Crout with partial pivoting | standard for dense small matrices |
| FFT algorithm | radix-2 DIT | classic; Douglas L. Jones / MEAPsoft reference |
| StringTokenizer source | GNU Classpath port | AWT/JDK class available but behavior differences avoided |
| Constants container | interface `CircuitConst` | pre-existing; kept for compatibility |

## Progress

- [DONE] Phase 1 — CircuitMath (LU + scope metrics)
- [DONE] Phase 2 — FFT
- [DONE] Phase 3 — RandomUtils
- [DONE] Phase 4 — StringTokenizer (GNU Classpath port)
- [DONE] Phase 5 — CircuitConst constants
- [backlog] Phase 6 — Robustness & modernization

## Phases

### Phase 1 — CircuitMath (`client/CircuitMath.java`) [DONE]

Implements: [SP_MDS_02_01](./math-dsp.sp.md#SP_MDS_02_01)–[SP_MDS_02_05](./math-dsp.sp.md#SP_MDS_02_05)

Shipped: LU factor/solve/invert, `isConverged`, scope analytics (frequency,
waveform metrics, duty cycle), nested result types.

### Phase 2 — FFT (`client/FFT.java`) [DONE]

Implements: [SP_MDS_01_02](./math-dsp.sp.md#SP_MDS_01_02), [SP_MDS_02_06](./math-dsp.sp.md#SP_MDS_02_06)

Shipped: radix-2 DIT with sine window, magnitude helper. Package-private;
sole caller is `Scope`.

### Phase 3 — RandomUtils (`client/RandomUtils.java`) [DONE]

Implements: [SP_MDS_02_07](./math-dsp.sp.md#SP_MDS_02_07)

Shipped: singleton `Random`, `getRand(int)` convenience.

### Phase 4 — StringTokenizer (`client/StringTokenizer.java`) [DONE]

Implements: [SP_MDS_01_04](./math-dsp.sp.md#SP_MDS_01_04), [SP_MDS_02_08](./math-dsp.sp.md#SP_MDS_02_08)

Shipped: full GNU Classpath-style API plus project-local helpers.

### Phase 5 — CircuitConst (`client/CircuitConst.java`) [DONE]

Implements: [SP_MDS_01_05](./math-dsp.sp.md#SP_MDS_01_05)

Shipped: `RC_*` bitflags and `HINT_*` codes.

## Backlog

Items transcribed from the analysis "Issues" section:

- **`CircuitMath.invertMatrix` ignores `lu_factor` return value** — silent
  failure on singular input; `lu_solve` divides by near-zero pivot. Add a
  guard and surface a diagnostic.
- **`StringTokenizer.start` field is never assigned** — `getStartTokenIdx`
  always returns 0. Either wire up `start` in `nextToken` or delete the
  field and the accessor.
- **`RandomUtils.getRand(int)` edge cases** — `x=0` throws
  `ArithmeticException`; `Integer.MIN_VALUE` negation returns a still-negative
  value. Use `Math.floorMod` or `nextInt(bound)` directly.
- **`FFT` is package-private but documented for internal use** — add a
  javadoc tag clarifying that only `Scope` should consume it.
- **`FFT.magnitude` divides by `size`, not `size/2`** — document the
  convention in Scope to avoid user confusion.
- **`CircuitConst` is an interface-as-constants bag** (pre-Java-5
  antipattern). Convert to a `final class` with `private` constructor when
  touching its consumers.
- **No unit tests** — project has no JUnit suite; verification done via
  build + scripted import/export roundtrips.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure |
