# Platform Bridge: PlatformUtils, GWTUtils  {#C_PLT}

> **Code:** C_PLT
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard
>
> **Depends on:** none (Layer 0 leaf)
> **Used by:** `ActionManager`, `MenuManager` (URL opening); `CircuitRenderer`,
> `CircuitEditor`, `UndoManager`, `CircuitLoader`, `JsonCircuitImporter`
> (CSS helpers)
> **Spike:** —
> **Specification:** [SP_PLT](./platform-bridge.sp.md)
> **Plan:** [platform-bridge.plan.md](./platform-bridge.plan.md)
>
> Thin glue between the Java/GWT client and the underlying browser / NW.js
> runtime. `PlatformUtils` opens external URLs and probes the runtime;
> `GWTUtils` provides small CSS-on-Widget sugar.

## 1. Philosophy  {#C_PLT_01}

### 1.1. Core Principle  {#C_PLT_01_01}

The simulator needs to (a) open help links and external URLs in a way that
works both in the browser and inside the NW.js desktop bundle, and (b)
apply CSS declarations to GWT `Widget`s without repeating the
`widget.getElement().getStyle()…` boilerplate everywhere. These two needs
share only the fact that they touch browser APIs, so they are colocated as
"platform bridge".

### 1.2. Design Constraints  {#C_PLT_01_02}

- GWT-only; JSNI methods access `window`, `navigator`, and `nw.Shell`.
- Fail open on URL opening: if NW.js isn't available, fall back to
  `window.open`; if even that fails, log and move on.
- `GWTUtils.setStyles` validates argument shape (pairs), throwing
  `IllegalArgumentException` on odd count.
- `PlatformUtils` uses `com.google.gwt.core.client.GWT.log` for logging
  (keeps Layer-0 invariant intact).

## 2. Domain Model  {#C_PLT_02}

### 2.1. Key Entities  {#C_PLT_02_01}

- **PlatformUtils** — static utility. Public methods: `openURL(String)`,
  `getPlatformInfo()`, `isURLOpeningSupported()`. JSNI private methods:
  `openURLWithJavaScript` (tries `nw.Shell.openExternal`, else
  `window.open`), `openURLWithSystemCommand` (AI_TODO / unimplemented).
- **GWTUtils** — static utility. Methods: `setStyle(Widget, prop, value)`,
  `setStyles(Widget, String... pairs)`, plus family helpers for common CSS
  properties (flex, position, etc.).

### 2.2. Data Flows  {#C_PLT_02_02}

- Action/menu click → `ActionManager`/`MenuManager` → `PlatformUtils.openURL(url)`
  → JSNI `openURLWithJavaScript` → NW.js shell or `window.open` →
  external browser or new tab.
- UI setup → widget construction → `GWTUtils.setStyle(...)` or
  `setStyles(...)` → DOM style mutations.

## 3. Mechanisms  {#C_PLT_03}

### 3.1. Core Algorithm  {#C_PLT_03_01}

`openURL`: null/empty → false. Try `openURLWithJavaScript`. On exception,
fall back to `openURLWithSystemCommand` (currently a stub). Log outcome via
`GWT.log`.

`getPlatformInfo`: JSNI probe that serializes NW.js + `nw.Shell` presence
and platform name to JSON.

`setStyles`: iterates varargs in pairs; throws if odd length; delegates each
pair to the underlying DOM style API.

### 3.2. Edge Cases  {#C_PLT_03_02}

- NW.js unavailable → falls back to `window.open` (may be blocked by popup
  blocker depending on event origin).
- `openURLWithSystemCommand` is unimplemented — AI_TODO marker kept.
- `setStyles` with odd varargs → `IllegalArgumentException`.

## 4. Integration Points  {#C_PLT_04}

### 4.1. Dependencies  {#C_PLT_04_01}

- `com.google.gwt.core.client.GWT` (logging) — `PlatformUtils`.
- `com.google.gwt.user.client.ui.Widget` — `GWTUtils`.
- JSNI browser APIs: `window.open`, `nw.Shell.openExternal`, `navigator`.
- No project-internal dependencies.

### 4.2. API Surface  {#C_PLT_04_02}

- `PlatformUtils.openURL(String) → boolean`.
- `PlatformUtils.getPlatformInfo() → String` (JSON).
- `PlatformUtils.isURLOpeningSupported() → boolean`.
- `GWTUtils.setStyle(Widget, String, String)`.
- `GWTUtils.setStyles(Widget, String...)`.
- Additional `GWTUtils` family helpers for common CSS properties (flex,
  display, etc.).

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure |
