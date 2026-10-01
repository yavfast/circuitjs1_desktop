# Session Logging — In-Memory Buffer + Async File Writer  {#C_LOG}

> **Code:** C_LOG
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard-doc-gen
>
> **Depends on:** C_PLT (platform / NW.js `fs`), [C_UTL](./util-locale-log.concept.md)
> **Used by:** C_DIN (dialog-info: ShowLogDialog), C_APC (simulator-core, pending)
> **Spike:** —
> **Specification:** [SP_LOG](./session-logging.sp.md)
> **Plan:** [session-logging.plan.md](./session-logging.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/layer3__cross-cutting-managers.md` §LogManager.
>
> Session-scoped logging: every log call appends a pre-timestamped string to an unbounded in-memory `ArrayList<String>` and enqueues the same entry for a batched (100 ms / 1000-entry) async file write via NW.js `fs.appendFile`. Surfaced by `ShowLogDialog` (live tail) and a JS-bridge API (`CirSim.getLogs/addLog/clearLogs`).

## 1. Philosophy  {#C_LOG_01}

### 1.1. Core Principle  {#C_LOG_01_01}

Logs are both ephemeral (live tail in the dialog) and persistent (on-disk append for post-mortem). Two concerns share one pipeline: the in-memory list powers the dialog and the JS-bridge; a batched NW.js writer persists to `/tmp/circuit/logs/circuit_log_<yyyyMMdd_HHmmss>.txt`. This concept is **unrelated** to the dormant `util.Log` façade (which forwards to `CirSim.console` and has zero active callers).

### 1.2. Design Constraints  {#C_LOG_01_02}

- **No retention bound.** `logEntries` is an unbounded `ArrayList<String>`; `clearLogs()` is the only truncation path.
- **Pre-formatted strings.** Entries are `"[HH:mm:ss.SSS] <msg>"` strings; no structured level field (level prefix is embedded if used).
- **Async file writes with a poor man's synchronisation.** `synchronized(logWriteQueue)` blocks are cosmetic — GWT compiles to single-threaded JS, so they are no-ops.
- **Fallback-safe.** `fileLoggingEnabled` flips to false if not running under NW.js, so in a browser the file side is silently no-op.
- **Never self-throws.** All file path construction + JSNI calls are try/catch-wrapped; failures disable file logging only.

## 2. Domain Model  {#C_LOG_02}

### 2.1. Key Entities  {#C_LOG_02_01}

```
LogManager extends BaseCirSimDelegate
  public final ArrayList<String> logEntries        -- unbounded
  String   currentLogFileName, sessionStartTime, logDirectory
  DateTimeFormat fileNameFormat, logEntryFormat
  boolean  fileLoggingEnabled
  private final ArrayList<String> logWriteQueue    -- batch staging
  boolean  isWriting, logWriteScheduled            -- scheduled flag is vestigial
  Scheduler.RepeatingCommand logWriteCommand
  static final int LOG_WRITE_DELAY_MS = 100
  static final int MAX_QUEUE_SIZE     = 1000
```

### 2.2. Data Flows  {#C_LOG_02_02}

```
Any caller (BaseCirSim.log(msg) or JS bridge addLog)
  -> LogManager.addLogEntry(msg)
       ts  = logEntryFormat.format(new Date())
       s   = "[" + ts + "] " + msg
       logEntries.add(s)
       queueLogEntry(s)
         synchronized(logWriteQueue): logWriteQueue.add(s)
         IF !isWriting:
           IF logWriteQueue.size >= MAX_QUEUE_SIZE: flushLogQueue()
           ELSE: scheduleLogWrite() -- Scheduler.scheduleFixedDelay(100ms)
             logWriteCommand.execute() -> flushLogQueue()

flushLogQueue
  snapshot = new ArrayList<>(logWriteQueue); logWriteQueue.clear()
  isWriting = true
  writeLogEntriesAsync(snapshot, currentLogFileName, callback):
    JSNI setTimeout(0) -> require('fs').appendFile(path, joinedLines, cb)
    cb: isWriting=false; checkForPendingWrites()
```

## 3. Mechanisms  {#C_LOG_03}

### 3.1. Core Algorithm  {#C_LOG_03_01}

**Session rollover.** `startNewSession()` builds `circuit_log_<yyyyMMdd_HHmmss>.txt`, appends a banner to both `logEntries` and the queue. Invoked once from ctor; re-invocation accumulates both sessions in memory (no clear).

**Log directory resolution.** `createLogDirectory()` JSNI attempts `/tmp/circuit/logs/` first; on failure falls back to `process.cwd() + "/logs/"`. Non-writable paths flip `fileLoggingEnabled` off silently.

**Force flush.** `forceFlushLogs()` flushes and polls `isWriting` every 10 ms up to 500 ms. Because GWT can't block, the poll is effectively a no-op tick — callers cannot synchronously guarantee disk-current state.

**Browser fallback save.** `saveLogsToFile()` triggers `saveAllLogsToFile` JSNI which uses `fs.writeFileSync` (NW.js) or a Blob + object URL download (browser) — this is the only synchronous path.

### 3.2. Edge Cases  {#C_LOG_03_02}

- `CirSim.clearLogs` bypasses `LogManager.clearLogs()` — calls `logEntries.clear()` directly, skipping `forceFlushLogs()` so queued-but-unwritten entries are lost.
- `logWriteScheduled` is set but never read for scheduling gate — functionally vestigial.
- `updateLogCount()` body is a single commented-out line — no-op UI hook.
- `logInfo/logWarning/logError/logDebug` level helpers exist but have zero callers.
- NW.js `fs.writeFileSync` on UI thread blocks during saveLogsToFile.

## 4. Integration Points  {#C_LOG_04}

### 4.1. Dependencies  {#C_LOG_04_01}

- **C_PLT** — GWT `Scheduler`, `DateTimeFormat`, `Date`; JSNI to NW.js `require('fs').appendFile` / `writeFileSync`; browser `Blob`/`URL.createObjectURL`.
- **[C_UTL](./util-locale-log.concept.md)** — no direct dependency; the dead `util.Log` was deleted on 2026-10-01 (BL-A01).
- **C_APC (simulator-core)** — `BaseCirSim.log(String)` forwards here; `CirSim` exposes `getLogs/getLastLogs/getLogCount/addLog/clearLogs` JS bridge.

### 4.2. API Surface  {#C_LOG_04_02}

- `addLogEntry(String msg)` / `addLogEntry(String msg, String level)`.
- `logInfo/logWarning/logError/logDebug(String)` — unused helpers.
- `clearLogs()`, `forceFlushLogs()`, `shutdown()`.
- `saveLogsToFile()`, `startNewSession()`.
- Observability: `getQueueSize()`, `isWriteInProgress()`, `getCurrentLogFilePath()`, `isFileLoggingEnabled()`, `setFileLoggingEnabled(boolean)`.

Primary consumers: `ShowLogDialog` (live tail; polls `logEntries`), `BaseCirSim.log` (adapter for the codebase), `CirSim` JS bridge (`getLogs`, `addLog`, etc.).

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
