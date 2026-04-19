# Implementation Plan: Util — Locale, Log, PerfMonitor  {#PL_UTL}

> **Code:** PL_UTL
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_UTL](./util-locale-log.concept.md)
> **Specification:** [SP_UTL](./util-locale-log.sp.md)
> **Depends on plans:** none
> **Used by plans:** [PL_RND](./rendering-primitives.plan.md), higher-layer plans
>
> Retrospective plan covering the already-implemented `client/util/` helpers.

## Goal

Document the as-built state of `Locale`, `Log`, `PerfMonitor` and record
remediation backlog items for the two Layer-0 inversions and the latent
PerfMonitor duplicate-sibling bug.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Language | Java 17 source | project-wide |
| Client compile target | GWT 2.12 | compiles Java to JS |
| i18n | static HashMap + external load | simplicity, GWT-friendly |
| Profiling clock | GWT JSNI `window.performance.now()` | sub-ms resolution in browser |
| Logging sink | `CirSim.console` (current) | temporary; flagged for inversion |

## Progress

- [DONE] Phase 1 — Locale dictionary & glyphs
- [DONE] Phase 2 — Log façade
- [DONE] Phase 3 — PerfMonitor tree + JSNI clock
- [backlog] Phase 4 — Remediate Layer-0 inversions
- [backlog] Phase 5 — Fix PerfMonitor duplicate-sibling handling

## Phases

### Phase 1 — Locale (`client/util/Locale.java`) [DONE]

Implements: [SP_UTL_01_01](./util-locale-log.sp.md#SP_UTL_01_01), [SP_UTL_02_01](./util-locale-log.sp.md#SP_UTL_02_01)

Shipped: static map, `LS`, `LSHTML`, `ohmString`, `muString`. Entry-point
(`circuitjs1.java:196`) populates `localizationMap`.

### Phase 2 — Log (`client/util/Log.java`) [DONE]

Implements: [SP_UTL_02_03](./util-locale-log.sp.md#SP_UTL_02_03)

Shipped: static `log(String...)`. Currently 0 active callers; one
commented-out reference in `dialog/Dialog.java:141`.

### Phase 3 — PerfMonitor (`client/util/PerfMonitor.java`) [DONE]

Implements: [SP_UTL_01_03](./util-locale-log.sp.md#SP_UTL_01_03), [SP_UTL_02_04](./util-locale-log.sp.md#SP_UTL_02_04), [SP_UTL_02_05](./util-locale-log.sp.md#SP_UTL_02_05)

Shipped: `PerfEntry` tree, `reset`/`start`/`stop`/`buildString`, JSNI
`getTime()` with `performance.now` → `webkitNow` → `Date.getTime` fallback.
Sole consumer: `CircuitRenderer`.

## Backlog

Items transcribed from the analysis "Issues" section:

- **Layer-0 → Layer-3 violation (Log):** `Log` imports `CirSim`. Zero active
  callers — either delete `Log` or inject a `Consumer<String>` console sink.
- **Layer-0 → Layer-2 violation (PerfMonitor → BaseCircuitElm):** move
  `formatNumber` into `util` as `NumberFormat`, inline a minimal formatter,
  or relocate `PerfMonitor` next to `CircuitRenderer`.
- **`Locale.localizationMap` is publicly mutable and nullable:** encapsulate
  with a loader + null-safe `LS` fallback ("just return key if map unset").
- **`LS` NPE on uninitialized map:** add guard returning the key.
- **`PerfMonitor.startContext` silently drops duplicate-name siblings:**
  either aggregate samples into the existing child (sum `length`) or append
  disambiguated names. Currently causes per-element draw timings to
  register only for the first element.
- **`PerfEntry` fields are `public`:** tighten visibility / make immutable
  post-`stopContext`.
- **`PerfMonitor.buildString` NPE when `rootCtx == null`:** add guard.
- **Unused method:** remove `PerfEntry.getChild` or document intent.
- **License headers missing** on `Log.java` and `PerfMonitor.java`.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure |
