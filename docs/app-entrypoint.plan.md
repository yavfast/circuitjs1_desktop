# Implementation Plan: App Entrypoint  {#PL_APE}

> **Code:** PL_APE
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_APE](./app-entrypoint.concept.md)
> **Specification:** [SP_APE](./app-entrypoint.sp.md)
> **Depends on plans:** PL_APC, PL_UTL, PL_DIN, PL_USR
> **Used by plans:** —

## Goal

Bring the application from a blank HTML shell to a fully initialised `CirSim` with a global error hook, resolved locale, loaded translation catalog, and a live window-resize handler.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Entry contract | GWT `EntryPoint` | mandated by module system |
| Error hook | `GWT.setUncaughtExceptionHandler` first | captures all subsequent bootstrap failures |
| Locale fetch | async `RequestBuilder` | avoids blocking UI thread |
| Locale format | hand-rolled `"k"="v"` with `\uXXXX` | small footprint, no GWT-i18n dependency |
| Fast-path | skip HTTP for `"en*"` | common case; reduces boot latency |

## Progress

- [x] Phase 1 — UncaughtExceptionHandler install
- [x] Phase 2 — `language()` JSNI + normalisation
- [x] Phase 3 — QueryParameters `?lang=` integration
- [x] Phase 4 — English fast-path
- [x] Phase 5 — async `RequestBuilder` for `locale_<lang>.txt`
- [x] Phase 6 — `processLocale` + `convertUnicodeEscapes`
- [x] Phase 7 — `loadSimulator`: publish localizationMap → `new CirSim` → `init` → first render
- [x] Phase 8 — `Window.addResizeHandler` wiring

## Backlog

From `.dev_flow/onboard/analysis/layer3__app-entry.md` §Issues:

- **Silent locale-fetch failures.** `onError` / `RequestException` paths never call `loadSimulator`; page stays blank, `oncircuitjsloaded` never fires. Fix: fall back to empty map and continue.
- **`QueryParameters` crashes on bare flags** (`?foo&lang=fr`) — surfaces `UncaughtExceptionDialog` to user.
- **`QueryParameters.getBooleanValue` uses `==` for String comparison** — latent bug for other callers.
- **`loadSimulator` is `public` without being API.** Only reason: inner-class visibility.
- **`static CirSim mysim` vs `CirSim.sim`** — two parallel statics; `mysim` is dead weight after boot.
- **Locale file format fragile** — `processLocale` parser breaks on stray `"` inside values.
- **No `oncircuitjsloaded` on locale error** — tied to issue #1.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
