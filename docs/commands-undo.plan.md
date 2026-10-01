# Implementation Plan: Commands & Undo  {#PL_UND}

> **Code:** PL_UND
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_UND](./commands-undo.concept.md)
> **Specification:** [SP_UND](./commands-undo.sp.md)
> **Depends on plans:** PL_IOF, PL_DOC
> **Used by plans:** PL_EDI, PL_MEN, PL_CLP, PL_FBR

## Goal

Implement full-snapshot undo/redo plus a single-slot crash-recovery store, driven off the io-framework text dump.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Record shape | `(dump, scale, tx, ty)` | matches what a user sees; no hidden state |
| Dedup | string equality on dump | cheap, catches trivial no-op edits |
| Recovery | single `String` in localStorage | crash resilience without disk overhead |
| Restoration | full reload through `CircuitLoader` | guarantees any format-evolution bugs match the normal load path |

## Progress

- [x] Phase 1 — UndoItem inner class
- [x] Phase 2 — push/doUndo/doRedo
- [x] Phase 3 — resetAndSeedFromCurrentCircuit
- [x] Phase 4 — writeRecoveryToStorage / readRecovery / doRecover *(removed 2026-10-01, BL-C01)*
- [x] Phase 5 — Wire pushUndo into every mutating editor path

## Backlog

From `.dev_flow/onboard/analysis/layer3__editor-interaction.md` §Issues:

- **O(dump size) per edit.** Full-snapshot scales poorly; drags produce two entries each. No coalescing.
- **No incremental/inverse commands.** Any mutation not round-tripped by the io format is lost on undo (scope cursor, simulator vectors).
- **`UndoManager` holds `CircuitDocument` but uses `getActiveDocument()`** — correct only while this undo is the active one.
- **`CirSim.clearLogs` bypass pattern** (from LogManager) recurs conceptually: external code may clear localStorage directly and skip `clearStacks`.
- **No compression** of dumps; repeated near-identical snapshots each take full string size.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
