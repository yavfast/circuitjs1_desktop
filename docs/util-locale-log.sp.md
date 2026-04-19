# Util: Locale, Log, PerfMonitor — Specification  {#SP_UTL}

> **Code:** SP_UTL
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_UTL](./util-locale-log.concept.md)
> **Depends on specs:** — (Layer 0)
> **Used by specs:** [SP_RND](./rendering-primitives.sp.md), domain-core specs, dialog specs (populated at higher layers)
> **Plan:** [util-locale-log.plan.md](./util-locale-log.plan.md)
>
> Data structures, public contracts, and lifecycle rules for the three
> `client/util/` helpers.
>
> Backing analysis: [.dev_flow/onboard/analysis/util.md](../.dev_flow/onboard/analysis/util.md)

## 01. Data Structures  {#SP_UTL_01}

> Implements: [C_UTL_02](./util-locale-log.concept.md#C_UTL_02)

### 01_01. Locale  {#SP_UTL_01_01}

Static i18n dictionary and glyph constants.

Fields:
| Field | Type | Required | Default | Constraints | Description |
|-------|------|----------|---------|-------------|-------------|
| `localizationMap` | `public static HashMap<String,String>` | yes | `null` | assigned once by `circuitjs1.java:196` | Translation dictionary |
| `ohmString` | `public static String` | yes | `"\u03a9"` | constant | Ω glyph |
| `muString` | `public static String` | yes | `"\u03bc"` | constant | μ glyph |

Invariants:
- `localizationMap` must be non-null before any `LS`/`LSHTML` call.

### 01_02. Log  {#SP_UTL_01_02}

Stateless static façade. No fields.

### 01_03. PerfMonitor  {#SP_UTL_01_03}

Per-frame hierarchical profiler.

Fields:
| Field | Type | Required | Default | Constraints | Description |
|-------|------|----------|---------|-------------|-------------|
| `rootCtxName` | `String` (pkg) | no | `null` | set once after reset | Name of outermost context |
| `rootCtx` | `PerfEntry` (pkg) | no | `null` | retained after outermost stop | Root of perf tree |
| `ctx` | `PerfEntry` (pkg) | no | `null` | current frame pointer | Stack top |

### 01_04. PerfMonitor.PerfEntry  {#SP_UTL_01_04}

Inner node of the perf tree. Fields: `parent` (final), `children`
(`HashMap<String,PerfEntry>`), `startTime`, `endTime`, `length` (float).
Invariant after `stopContext`: `length == endTime - startTime`.

## 02. Contracts  {#SP_UTL_02}

### 02_01. Locale.LS  {#SP_UTL_02_01}

Purpose: Look up translation for a UI key, with homograph disambiguator.

Input: `String s` (key; nullable; may end with `~`).
Output: `String` — translation or fallback.
Errors: `NullPointerException` if `localizationMap == null`.

Logic:
    IF s == null OR s.isEmpty(): return s
    IF map contains s: return map[s]
    IF s ends with "~": strip and retry map lookup
    return stripped key (or s if no strip)

### 02_02. Locale.LSHTML  {#SP_UTL_02_02}

Purpose: Return translated key wrapped as trusted `SafeHtml`.
Input/Output/Errors: as `LS`. Output type `SafeHtml`.
Logic: `SafeHtmlUtils.fromTrustedString(LS(s))`.

### 02_03. Log.log  {#SP_UTL_02_03}

Purpose: Concatenate varargs and forward to console sink.
Input: `String... msg`. Output: `void`. Errors: none (StringBuilder accepts null).
Logic: StringBuilder(1024) → append all → `CirSim.console(sb.toString())`.

### 02_04. PerfMonitor.reset / startContext / stopContext  {#SP_UTL_02_04}

- `reset()` — clears `ctx`, `rootCtx`, `rootCtxName`.
- `startContext(name)` — allocates new `PerfEntry(parent=ctx)`; if `ctx==null`,
  becomes root; else `parent.addChild(name, entry)` and updates `ctx`
  only on success. On duplicate name: entry discarded, `ctx` unchanged.
- `stopContext()` — silent no-op if `ctx==null`; else stamps `endTime`, computes
  `length`, sets `ctx = ctx.parent`.

### 02_05. PerfMonitor.buildString (static)  {#SP_UTL_02_05}

Purpose: Emit indented textual dump of the perf tree.
Input: `PerfMonitor mon`. Output: `StringBuilder`.
Errors: `NullPointerException` if `mon.rootCtx == null`.
Logic: Recurse from `rootCtx`; emit `"-"*depth + name + ": " + formatted length`.
Uses `BaseCircuitElm.formatNumber(length, 2)` (known layer inversion).

## 03. Validation Rules  {#SP_UTL_03}

- `Locale.LS`: null / empty short-circuits; trailing `~` only stripped when
  the final char (`ix == s.length()-1`).
- `PerfMonitor.stopContext`: null-check on `ctx`.
- `PerfMonitor.PerfEntry.addChild`: insert-once, rejects collisions.

## 04. State Transitions  {#SP_UTL_04}

### 04_01. PerfMonitor lifecycle  {#SP_UTL_04_01}

    [fresh/reset] --startContext(root)--> [rooted, ctx=root]
    [rooted]     --startContext(child)--> [deeper, ctx=child]
    [deeper]     --stopContext()       --> [rooted]
    [rooted]     --stopContext()       --> [outer-complete, ctx=null, rootCtx retained]
    [outer-complete] --reset()         --> [fresh]

## 05. Verification Criteria  {#SP_UTL_05}

### 05_01. Functional Expectations  {#SP_UTL_05_01}

| Contract | Scenario | Input | Expected outcome |
|----------|----------|-------|------------------|
| LS | direct hit | `"Run"` present in map | mapped translation |
| LS | trailing-~ fallback | `"Run~"`, absent; `"Run"` present | translation for `"Run"` |
| LS | no match | absent key | original key (or stripped of trailing `~`) |
| startContext | duplicate name sibling | `"elm"` pushed twice | second push silently dropped |

### 05_02. Invariant Checks  {#SP_UTL_05_02}

| Invariant | Verification method |
|-----------|--------------------|
| `length == endTime - startTime` | inspect `PerfEntry` after `stopContext` |
| `localizationMap` non-null before `LS` | startup order audit |

### 05_03. Integration Scenarios  {#SP_UTL_05_03}

| Scenario | Preconditions | Steps | Expected result |
|----------|--------------|-------|-----------------|
| CircuitRenderer profile | PerfMonitor per frame | reset → start → draws → stop → buildString | indented tree in debug overlay |

### 05_04. Edge Cases and Boundaries  {#SP_UTL_05_04}

| Case | Input | Expected behavior |
|------|-------|-------------------|
| LS with null map | any key | NPE (caller must init first) |
| buildString before any start | fresh monitor | NPE |
| stopContext underflow | ctx==null | silent no-op |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure |
