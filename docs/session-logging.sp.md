# Session Logging — Specification  {#SP_LOG}

> **Code:** SP_LOG
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_LOG](./session-logging.concept.md)
> **Depends on specs:** SP_PLT, SP_UTL
> **Used by specs:** SP_DIN, SP_APC
> **Plan:** [session-logging.plan.md](./session-logging.plan.md)

## 01. Data Structures  {#SP_LOG_01}

### 01_01. LogManager state  {#SP_LOG_01_01}

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| logEntries | ArrayList<String> | empty | Unbounded in-memory buffer. |
| logWriteQueue | ArrayList<String> | empty | Batch staging. |
| isWriting | boolean | false | True during NW.js callback wait. |
| logWriteScheduled | boolean | false | Vestigial; set, never read for gating. |
| currentLogFileName | String | null | `circuit_log_<yyyyMMdd_HHmmss>.txt`. |
| logDirectory | String | `/tmp/circuit/logs/` | May be replaced by `process.cwd()/logs/`. |
| fileLoggingEnabled | boolean | true | False under browser or JSNI error. |
| LOG_WRITE_DELAY_MS | int (const) | 100 | Batch interval. |
| MAX_QUEUE_SIZE | int (const) | 1000 | Force-flush threshold. |

Entry format: `"[HH:mm:ss.SSS] <msg>"`.

## 02. Contracts  {#SP_LOG_02}

### 02_01. addLogEntry  {#SP_LOG_02_01}

    FUNCTION addLogEntry(msg):
        s = "[" + logEntryFormat.format(now) + "] " + msg
        logEntries.add(s)
        queueLogEntry(s)

    FUNCTION addLogEntry(msg, level):
        addLogEntry("[" + level + "] " + msg)

### 02_02. queueLogEntry + scheduling  {#SP_LOG_02_02}

    FUNCTION queueLogEntry(s):
        synchronized(logWriteQueue): logWriteQueue.add(s)
        IF !isWriting:
            IF logWriteQueue.size() >= MAX_QUEUE_SIZE: flushLogQueue()
            ELSE: scheduleLogWrite()

    FUNCTION scheduleLogWrite():
        logWriteScheduled = true                                -- vestigial
        Scheduler.scheduleFixedDelay(logWriteCommand, LOG_WRITE_DELAY_MS)

    FUNCTION logWriteCommand.execute():
        flushLogQueue()
        RETURN false                                            -- single-shot

### 02_03. flushLogQueue  {#SP_LOG_02_03}

    FUNCTION flushLogQueue():
        IF !fileLoggingEnabled OR isWriting: RETURN
        snapshot = snapshot logWriteQueue; clear queue
        isWriting = true
        writeLogEntriesAsync(snapshot, currentLogFileName, {
          onComplete: isWriting = false; checkForPendingWrites()
        })

### 02_04. clearLogs / shutdown  {#SP_LOG_02_04}

    FUNCTION clearLogs():
        forceFlushLogs()
        logEntries.clear()
        updateLogCount()                                        -- empty body

    FUNCTION shutdown():
        forceFlushLogs()
        JSNI fs.appendFileSync(path, "[session end banner]")

### 02_05. startNewSession  {#SP_LOG_02_05}

    FUNCTION startNewSession():
        sessionStartTime = fileNameFormat.format(now)
        currentLogFileName = "circuit_log_" + sessionStartTime + ".txt"
        banner = "=== Session started at " + sessionStartTime + " ==="
        addLogEntry(banner)

## 03. Validation Rules  {#SP_LOG_03}

- `logEntries` is never null; append-only unless `clearLogs()` is called.
- `MAX_QUEUE_SIZE` exceed forces immediate flush, skipping the 100 ms timer.
- `fileLoggingEnabled == false` turns `queueLogEntry` into a no-op.
- `forceFlushLogs()` cannot block GWT event loop; subsequent callers must not assume disk-current.

## 04. State Transitions  {#SP_LOG_04}

| From | To | Trigger | Side effect |
|------|----|---------|-------------|
| idle | scheduled | queueLogEntry with queue<MAX | scheduleFixedDelay(100ms) |
| scheduled | writing | logWriteCommand fired | flushLogQueue → NW.js appendFile |
| writing | idle | fs.appendFile callback | checkForPendingWrites reschedules if queue non-empty |
| any | idle | clearLogs | forceFlush + logEntries.clear |

## 05. Verification Criteria  {#SP_LOG_05}

### 05_01. Functional  {#SP_LOG_05_01}

| Contract | Scenario | Input | Expected |
|----------|----------|-------|----------|
| addLogEntry | normal call | "hello" | logEntries.size()+=1; file queue +1 |
| queue fill-up | 1000 rapid calls | — | immediate flush, no 100 ms wait |
| file logging disabled | browser host | — | logEntries grows; no file I/O |

### 05_02. Invariants  {#SP_LOG_05_02}

| Invariant | Verification |
|-----------|--------------|
| In-memory log never shrinks except via clearLogs | observe size monotone otherwise |
| Session banner present | logEntries.first() starts with "=== Session started" |

### 05_03. Edge Cases  {#SP_LOG_05_03}

| Case | Input | Expected |
|------|-------|----------|
| JS bridge clearLogs | external clear | bypasses clearLogs(); loses pending queue (known bug) |
| Long-running session | hours of logs | unbounded memory growth (known smell) |
| saveLogsToFile in NW.js | user action | synchronous `fs.writeFileSync`; UI stall acceptable |
| saveLogsToFile in browser | user action | Blob + object URL download |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
