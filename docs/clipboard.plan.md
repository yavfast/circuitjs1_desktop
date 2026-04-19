# Implementation Plan: Clipboard  {#PL_CLP}

> **Code:** PL_CLP
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_CLP](./clipboard.concept.md)
> **Specification:** [SP_CLP](./clipboard.sp.md)
> **Depends on plans:** PL_IOF, PL_DOC, PL_PLT, PL_EDI
> **Used by plans:** PL_EDI, PL_MEN

## Goal

Bridge circuit text between the editor selection and the system clipboard, with a synchronous internal fallback and a legacy-browser escape hatch.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Async read contract | `ClipboardCallback` (onSuccess/onError) | matches `navigator.clipboard.readText` shape |
| Sync write | dual-path (async + legacy textarea) | maximises coverage across hosts (browser + NW.js) |
| Internal buffer | `String` field | synchronous paste when OS clipboard is unavailable |
| Sniff heuristic | prefix match on `$`, `r `, `c `, `l `, `w ` | cheap; importer catches the rest |

## Progress

- [x] Phase 1 — Feature detection (`checkClipboardSupport` JSNI)
- [x] Phase 2 — setClipboard / writeToSystemClipboard / tryLegacyClipboardWrite
- [x] Phase 3 — doCopy / doCut entry points
- [x] Phase 4 — readFromSystemClipboard + ClipboardCallback
- [x] Phase 5 — doPasteFromSystem + isCircuitData
- [x] Phase 6 — hasClipboardData / clearClipboard / getClipboardInfo

## Backlog

From `.dev_flow/onboard/analysis/layer3__editor-interaction.md` §Issues:

- **`isCircuitData` heuristic** — any text containing ` r ` / ` c ` / ` l ` / ` w ` passes; a non-circuit blob may be fed to the importer.
- **JSNI method-reference string** is GWT-specific; complicates future migration off GWT.
- **No multi-payload clipboard** — only text/plain; image path is separate and non-uniform.
- **Legacy fallback requires focus** — the hidden textarea injection is order-sensitive.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
