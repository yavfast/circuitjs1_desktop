# Implementation Plan: Browser File Bridge  {#PL_FBR}

> **Code:** PL_FBR
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_FBR](./browser-file-bridge.concept.md)
> **Specification:** [SP_FBR](./browser-file-bridge.sp.md)
> **Depends on plans:** PL_IOF, PL_PLT, PL_DOC, PL_DIM
> **Used by plans:** PL_MEN, PL_EDI

## Goal

Adapt browser file-picker events into Java strings so "File → Open" and SRAM "Load Contents From File" flow into the io framework / element shuttles with minimal friction.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Acquisition API | GWT `FileUpload` + JSNI `FileReader` | native browser path |
| Text encoding | `readAsText` UTF-8 | matches circuit dump format |
| Binary encoding | `readAsArrayBuffer` → ASCII decimal | reuses existing SRAM parser |
| DOM re-arm | `createNewLoadFile()` after each load | works around `<input type=file>` same-file ChangeEvent quirk |
| Error surfacing | `alert()` (LoadFile) / `Locale.LS` (SRAM) | pragmatic, inconsistent |

## Progress

- [x] Phase 1 — LoadFile class + DOM wiring
- [x] Phase 2 — LoadFile JSNI (doLoad + isSupported + doLoadCallback)
- [x] Phase 3 — LoadFile orchestrator (tab policy + undo reseed + focus + rebuild)
- [x] Phase 4 — SRAMLoadFile subclass + readAsArrayBuffer encoding
- [x] Phase 5 — Electron bridge hookup (electronOpenFileCallback → doLoadCallback)
- [x] Phase 6 — ImportFromDropbox cross-reference (owned by dialog-import concept)

## Backlog

From `.dev_flow/onboard/analysis/layer3__file-io-glue.md` §Issues:

- **Hard-coded size caps** duplicated and inconsistent (128 KB vs 100 KB).
- **`LoadFile.doLoad` error message not localised** — raw English `alert`.
- **`sLoadFile` single global** — latent race on async FileReader vs widget rebuild.
- **`SRAMElm.contentsOverride` cross-instance static shuttle** — relies on single-modal policy.
- **`LoadFile.doLoad` mixes five concerns** — candidate for move to ActionManager.
- **Duplicate text-acquisition code across paths** (LoadFile, Dropbox XHR).
- **SRAM encoding duplicated** (JSNI producer vs `SRAMElm.setChipEditValue` parser).
- **`getPath()` returns sandbox-faked value** on modern browsers — dead data.
- **Dropbox callback divergence** — `allowSave(false)` and undo reseed inconsistent across channels (see dialog-import).
- **No progress feedback / abort path** on any channel.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
