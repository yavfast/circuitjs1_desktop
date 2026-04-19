# Util: Locale, Log, PerfMonitor  {#C_UTL}

> **Code:** C_UTL
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard
>
> **Depends on:** none (Layer 0 leaf)
> **Used by:** [C_RND](./rendering-primitives.concept.md), domain core, dialogs (CirSim, CircuitRenderer, ~81 files via Locale)
> **Spike:** —
> **Specification:** [SP_UTL](./util-locale-log.sp.md)
> **Plan:** [util-locale-log.plan.md](./util-locale-log.plan.md)
>
> The `client/util/` sub-package groups three cross-cutting helpers that have no
> conceptual relationship to each other beyond "generic utility": i18n string
> lookup (`Locale`), a logging façade (`Log`), and a hierarchical frame
> profiler (`PerfMonitor`). They are grouped by accident of extraction, not by
> shared domain.

## 1. Philosophy  {#C_UTL_01}

### 1.1. Core Principle  {#C_UTL_01_01}

Each of the three classes solves an isolated, app-wide need:
- `Locale` provides lazy key-fallback translation so UI strings can ship
  untranslated without crashing the app.
- `Log` centralizes console output under one entry point (currently routed to
  `CirSim.console`).
- `PerfMonitor` measures per-frame render sub-steps into a parent/child tree
  for diagnostic overlays.

### 1.2. Design Constraints  {#C_UTL_01_02}

- All three are static/singleton-shaped; GWT single-threaded runtime means no
  synchronization.
- `Locale` and `Log` are stateless per call; `PerfMonitor` owns a small
  mutable tree state scoped to one render frame.
- Layer 0 invariant is violated in two places (known inversions):
  `Log → CirSim` and `PerfMonitor → BaseCircuitElm.formatNumber`. Both are
  flagged for remediation, not blocking.

## 2. Domain Model  {#C_UTL_02}

### 2.1. Key Entities  {#C_UTL_02_01}

- **Locale** — static `HashMap<String,String>` dictionary populated once at
  startup by `circuitjs1.java`. Provides `LS(key)` lookup with trailing-`~`
  homograph disambiguator and `LSHTML(key)` SafeHtml wrapper. Two glyph
  constants: `ohmString` (Ω), `muString` (μ).
- **Log** — static `log(String... msg)` that concatenates varargs into a
  `StringBuilder` and forwards to `CirSim.console`. Zero active callers.
- **PerfMonitor** — instance with a root-rooted tree of `PerfEntry` frames.
  Each entry: `parent`, `children` (name-keyed map), `startTime`, `endTime`,
  `length`. Timing from `window.performance.now()` via JSNI.

### 2.2. Data Flows  {#C_UTL_02_02}

- Locale: startup loads map → element/dialog code calls `LS("key")` → map
  lookup (direct, then strip trailing `~`) → fallback to original key.
- Log: caller → `log(...)` → StringBuilder → `CirSim.console`.
- PerfMonitor: `reset()` per frame → `startContext(name)` pushes child →
  `stopContext()` pops to parent → `buildString` emits indented text dump.

## 3. Mechanisms  {#C_UTL_03}

### 3.1. Core Algorithm  {#C_UTL_03_01}

Locale key lookup: if key is null/empty, return as-is. Direct map lookup;
if absent and key ends with `~`, retry with trailing `~` stripped; fall
back to returning the stripped key.

PerfMonitor lifecycle: maintains a current-frame pointer `ctx`. On push,
allocate a new `PerfEntry`, attach as child by name if unique (silently
discards duplicates), set `ctx = newEntry`. On pop, stamp `endTime`,
compute `length`, walk to `ctx.parent`. `rootCtx` is retained after the
outermost pop so `buildString` can still emit the completed tree.

### 3.2. Edge Cases  {#C_UTL_03_02}

- `Locale.LS` NPEs if `localizationMap` is null (no guard).
- `PerfMonitor.stopContext` is silent no-op on underflow.
- `PerfMonitor.startContext` silently drops samples on sibling-name
  collision (latent bug: `CircuitRenderer` reuses `"elm.draw()"` per
  element → only first iteration registers).
- `PerfMonitor.buildString` NPEs if `rootCtx` is null.

## 4. Integration Points  {#C_UTL_04}

### 4.1. Dependencies  {#C_UTL_04_01}

Intended: none (Layer 0). Actual:
- `Log → CirSim.console` (Layer 3 inversion).
- `PerfMonitor → BaseCircuitElm.formatNumber` (Layer 2 inversion; used only
  in `buildStringInternal`).
- External: `java.util.HashMap`, `com.google.gwt.safehtml.*`, GWT JSNI for
  `window.performance.now()`.

### 4.2. API Surface  {#C_UTL_04_02}

- `Locale.LS(key) → String`, `Locale.LSHTML(key) → SafeHtml`,
  `Locale.ohmString`, `Locale.muString`, `Locale.localizationMap` (mutable,
  publicly assignable — writes only from `circuitjs1` entry point).
- `Log.log(String... parts)`.
- `PerfMonitor.reset()`, `startContext(name)`, `stopContext()`,
  static `buildString(mon) → StringBuilder`.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure |
