# Implementation Plan: Session Logging  {#PL_LOG}

> **Code:** PL_LOG
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_LOG](./session-logging.concept.md)
> **Specification:** [SP_LOG](./session-logging.sp.md)
> **Depends on plans:** PL_PLT, PL_UTL
> **Used by plans:** PL_DIN, PL_APC

## Goal

Provide an in-memory log buffer plus batched async file appender so user-visible events can be tailed in the app and post-mortem reviewed on disk.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Buffer | `ArrayList<String>` | simple, O(1) append, easy tail-slice |
| Timestamp | `DateTimeFormat "HH:mm:ss.SSS"` | millisecond resolution |
| Flush cadence | 100 ms / 1000 entries | balance latency vs syscall volume |
| Async write | NW.js `fs.appendFile` via JSNI + `setTimeout(0)` | off-main-thread-ish |
| Export | `fs.writeFileSync` (NW.js) or Blob URL (browser) | user-triggered save |

## Progress

- [x] Phase 1 — LogManager state + formats
- [x] Phase 2 — startNewSession + createLogDirectory fallback
- [x] Phase 3 — addLogEntry / queueLogEntry
- [x] Phase 4 — scheduleLogWrite + logWriteCommand
- [x] Phase 5 — flushLogQueue + writeLogEntriesAsync JSNI
- [x] Phase 6 — forceFlushLogs + waitForAsyncOperation
- [x] Phase 7 — clearLogs / shutdown
- [x] Phase 8 — saveLogsToFile (NW.js + browser fallback)
- [x] Phase 9 — Level-prefixed convenience helpers (unused)

## Backlog

From `.dev_flow/onboard/analysis/layer3__cross-cutting-managers.md` §Issues:

- **No retention bound.** `logEntries` grows forever; no ring buffer.
- **`CirSim.clearLogs` bypass** — clears `logEntries` directly, skipping `forceFlushLogs`.
- **`synchronized` is cosmetic** under GWT single-threaded JS.
- **`logWriteScheduled` set but never read** for scheduling gate.
- **`updateLogCount()` empty** (single commented line).
- **Level helpers unused** (`logInfo/logWarning/logError/logDebug`).
- **`forceFlushLogs` wait ineffective** — GWT cannot block.
- **`saveAllLogsToFile` uses synchronous `fs.writeFileSync`** on UI thread.
- **`process.cwd()` fallback** may be non-writable.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
