# Implementation Plan: User Preferences  {#PL_USR}

> **Code:** PL_USR
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_USR](./user-preferences.concept.md)
> **Specification:** [SP_USR](./user-preferences.sp.md)
> **Depends on plans:** PL_UTL, PL_PLT, PL_RND
> **Used by plans:** PL_MEN, PL_EDI, PL_UND, PL_SCP, PL_DIE

## Goal

Provide the user-preference registry — storage facade, typed caches, URL override — that seeds colours, display flags, numeric formats, shortcuts, and scope/recovery blobs.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Storage backend | `gwt.storage.Storage` (localStorage) | GWT-native, per-origin |
| Boolean encoding | `"true"/"false"` | explicit, human-readable |
| Colour scale | precomputed 201 entries | O(1) voltage lookup per frame |
| URL ingestion | one-shot at boot | deterministic; overrides are process-lifetime |

## Progress

- [x] Phase 1 — OptionsManager facade
- [x] Phase 2 — QueryParameters URL parser
- [x] Phase 3 — DisplaySettings (static caches + 10 view getters)
- [x] Phase 4 — ColorSettings singleton + updateColorScale
- [x] Phase 5 — Printable-mode override on all palette getters
- [x] Phase 6 — getPrefixedKey for dialog pos/collapsed
- [x] Phase 7 — Storage enumeration (subcircuit:* consumers)

## Backlog

From `.dev_flow/onboard/analysis/layer3__options-settings.md` §Issues:

- **No central key registry / no type safety.** Typos silently resolve to defaults.
- **Inconsistent boolean representations** (`true/false` vs `1/0` vs mixed).
- **`QueryParameters.getBooleanValue` uses `==`** reference equality.
- **`QueryParameters` ctor fragility** on bare flags, repeat keys, undecoded keys.
- **Silent coercion** in int/double getters (swallow `NumberFormatException`).
- **Logging on every set** — verbose console output including subcircuit dumps.
- **Duplicate/near-duplicate keys** (`whiteBackground` / `printable` / `printableMode`; `euroGates` vs URL `IECGates`).
- **`DisplaySettings` hybrid static + instance** — hard to test.
- **Unused API** (`hasLocalStorage`, `postColor` persistence).
- **`ColorSettings` no persistence of its own** — callers must pair setter with storage write.
- **Missing keys for bg/fg/element/post colours.**
- **`MOD_TopMenuBar` default "standart"** (misspelling).

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
