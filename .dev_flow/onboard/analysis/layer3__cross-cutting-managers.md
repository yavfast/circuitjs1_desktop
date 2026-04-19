# layer3 / cross-cutting-managers

> **Path:** src/main/java/com/lushprojects/circuitjs1/client/
> **Layer:** 3 (app-shell managers, `BaseCirSimDelegate`-rooted)
> **Analyzed:** 2026-04-19
> **Files (3):** `LogManager.java` (448 LOC), `DialogManager.java` (175 LOC), `ExtListEntry.java` (22 LOC)

## Purpose

Three unrelated shell-level utilities that share only "lives at the `CirSim` root and is passed around by reference":

1. **LogManager** — session-scoped log buffer + async file-writer, surfaced by `ShowLogDialog` and the JS-bridge `getLogs` API on `CirSim`.
2. **DialogManager** — single-slot "currently active dialog" router; the sole factory for most modal dialogs plus lifecycle lookup (`dialogIsShowing`, `closeDialog`, `resetEditDialog`).
3. **ExtListEntry** — a plain value object (name, node, pos, side) describing one external pin of a `CustomCompositeModel` (subcircuit). Unrelated to logging or dialogs; it is **not** a general "external list" entry. It lives at package root purely because `CustomCompositeModel` does too.

The three are grouped here by directory + layer, not by cohesion. See **Concept boundary** for the recommended split.

Wiring is owned by `BaseCirSim`:
- `BaseCirSim.java:12` — `public final LogManager logManager = new LogManager(this);`
- `BaseCirSim.java:15` — `public final DialogManager dialogManager = new DialogManager(this);`

Both managers extend `BaseCirSimDelegate` (package-private `cirSim` back-reference; they down-cast to `CirSim` when they need the full API — see `DialogManager.java:76-83`).

## Per-file Key Entities

### LogManager (`LogManager.java:9`)

- **Type:** class extends `BaseCirSimDelegate`.
- **Fields:**

  | Field | Type | Default | Notes |
  |---|---|---|---|
  | `logEntries` | `public final ArrayList<String>` | `new ArrayList<>()` | In-memory buffer. **Unbounded, append-only** (see Retention). Pre-formatted strings `"[HH:mm:ss.SSS] <msg>"`. |
  | `currentLogFileName` | `String` | `null` | `circuit_log_<yyyyMMdd_HHmmss>.txt`, set by `startNewSession()` (`:414`). |
  | `sessionStartTime` | `String` | `null` | Session filename stem. |
  | `logDirectory` | `String` | `"/tmp/circuit/logs/"` | Overwritten in `createLogDirectory()` JSNI fallback (`:210-236`). |
  | `fileNameFormat` | `DateTimeFormat` | — | `"yyyyMMdd_HHmmss"`. |
  | `logEntryFormat` | `DateTimeFormat` | — | `"HH:mm:ss.SSS"`. |
  | `fileLoggingEnabled` | `boolean` | `true` | Disabled when not running under NW.js (`:230`) or on JSNI error (`:234`). |
  | `logWriteQueue` | `private final ArrayList<String>` | `new ArrayList<>()` | Batch-write staging. Accessed inside `synchronized(logWriteQueue)` blocks even though GWT runs single-threaded — defensive, not load-bearing. |
  | `isWriting` | `boolean` | `false` | True while an NW.js `fs.appendFile` callback is outstanding. |
  | `logWriteCommand` | `Scheduler.RepeatingCommand` | — | Flushes queue once. |
  | `LOG_WRITE_DELAY_MS` | `static final int` | `100` | Batch interval. |
  | `MAX_QUEUE_SIZE` | `static final int` | `1000` | Force-flush threshold. |
  | `logWriteScheduled` | `boolean` | `false` | "Is a flush currently scheduled?" bookkeeping — only ever set `true` in `scheduleLogWrite` (`:88`) and cleared in `forceFlushLogs`/`shutdown`; never actually read to gate scheduling, so functionally vestigial. |
  | `BooleanSupplier` | `private interface` (`:187`) | — | GWT has no `java.util.function.BooleanSupplier`, so this is re-declared locally. |

- **Constructor:** `protected LogManager(BaseCirSim)` (`:31`). Calls `initializeFileLogging` which (1) builds formats, (2) installs the scheduler command, (3) calls `startNewSession` (writes the session-start banner to buffer + queue), (4) calls `createLogDirectory` JSNI (may flip `fileLoggingEnabled` off), (5) appends `"CircuitJS1 Desktop application started"`.

### LogManager relationship to `util.Log`

From `analysis/util.md`: `util.Log.log(String...)` is a one-liner façade that concatenates varargs and forwards to `CirSim.console(String)`. It has **0 active callers** and a known Layer-0 → Layer-3 violation.

`LogManager` is a **different, unrelated** logging surface:
- `util.Log` → writes to `CirSim.console` (which in `BaseCirSim.console` typically reaches `GWT.log` / browser console).
- `LogManager` → writes into an in-memory `ArrayList<String>` **and** appends to an on-disk file via NW.js `fs.appendFile`, for user-visible log viewing (`ShowLogDialog`) and crash-report assembly.

They do not interoperate. `BaseCirSim.log(String)` (`BaseCirSim.java:257-259`) forwards to `logManager.addLogEntry` — callers of `sim.log(...)` feed `LogManager`, not `util.Log`. No call site in the tree routes `util.Log` into `LogManager` or vice-versa. `util.Log` is effectively dead; `LogManager` is the live logging pipeline.

### DialogManager (`DialogManager.java:29`)

- **Type:** class extends `BaseCirSimDelegate`.
- **Fields:**

  | Field | Type | Default | Notes |
  |---|---|---|---|
  | `activeDialog` | `DialogBox` (package-private) | `null` | **Single-slot** "currently open dialog" tracker. Not a list, not a map; at most one dialog is tracked at a time. |

- **Imports 19 dialog classes** (`:3-24`) — this is the fan-in hotspot of the `dialog/` package. Plus `CircuitElm`, `DiodeElm`, `TransistorElm`, `Editable`, `CustomCompositeModel`, `Scope`, `DiodeModel`, `TransistorModel` for edit-dialog factory signatures.

### ExtListEntry (`ExtListEntry.java:5`)

- **Type:** plain value object (no interface, no javadoc, no base class).
- **Fields:** all `public` (no encapsulation):

  | Field | Type | Notes |
  |---|---|---|
  | `name` | `String` | External pin label (labeled-node text). |
  | `node` | `int` | Internal node number inside the subcircuit. |
  | `pos` | `int` | Ordinal position along one side of the chip body. |
  | `side` | `int` | `ChipElm.SIDE_N/E/S/W` constant. |

- **Constructors:**
  - `ExtListEntry(String s, int n)` (`:6-10`) — two-arg; defaults `side = ChipElm.SIDE_W` and leaves `pos = 0`. Used for the stub "gnd" pin in `CustomCompositeModel.initModelMap` (`:37`).
  - `ExtListEntry(String s, int n, int p, int sd)` (`:12-17`) — four-arg; the normal case.

## LogManager retention policy

**Unbounded in memory.** `logEntries` is a plain `ArrayList<String>` with no cap, no ring buffer, no rotation. Retention rules observed:

- `addLogEntry(String)` (`:330-340`) always appends to `logEntries` AND enqueues a file write. No eviction.
- `clearLogs()` (`:342-348`) is the **only** truncation path — explicit user action ("Clear Logs" button in `ShowLogDialog.java:85`) or JS-API `CirSim.clearLogs()` (`CirSim.java:1432-1434`, which bypasses `clearLogs()` and clears the list directly — losing the `forceFlushLogs` step).
- `startNewSession()` (`:414-428`) does **not** clear memory — it only rolls over the on-disk filename. Re-invocation across a single GWT module lifetime would accumulate previous sessions in memory.
- `shutdown()` (`:351-366`) does not truncate; it flushes pending writes and appends a session-end banner directly to the file.

**On-disk policy** is write-only append (`fs.appendFile`, `LogManager.java:139`). No rotation, no size cap, no file pruning. The directory `/tmp/circuit/logs/` accumulates one `.txt` per session indefinitely; fallback `process.cwd()/logs/` ditto (`:224-226`).

**Buffering model** for the file side:
- Each `addLogEntry` → `queueLogEntry` appends to `logWriteQueue`.
- On first enqueue of a batch, `scheduleLogWrite` uses `Scheduler.scheduleFixedDelay(cmd, 100ms)` (`:87`).
- When the queue reaches `MAX_QUEUE_SIZE = 1000` (`:72`), it flushes immediately.
- `flushLogQueue` snapshots the queue, clears it, calls JSNI `writeLogEntriesAsync` which uses `setTimeout(..., 0)` + `fs.appendFile` (async). `isWriting` gates overlapping writes; on callback completion it calls `checkForPendingWrites` (`:161-167`) to re-schedule if more arrived.
- `forceFlushLogs` (`:192-202`) flushes and polls every 10 ms up to 500 ms via `waitForAsyncOperation` (`:170-184`). The poll cannot actually block the GWT event loop, so the "wait" is more a no-op tick; a subsequent operation runs as soon as `isWriting` flips.

## DialogManager dispatch flow

**Model:** single global slot, no stack, no registry keyed by name.

- **Writes to `activeDialog`:** every `show*` factory creates the dialog and assigns it to `activeDialog`, overwriting whatever was there. Examples: `showHelpDialog` (`:64`), `showLicenseDialog` (`:68`), `showModDialog` (`:77`), `showImportFromTextDialog` (`:82`), `showShortcutsDialog` (`:87`), `showSubcircuitDialog` (`:93`), `showSearchDialog` (`:99`), `showEditOptionsDialog` (`:105`), `showEditElementDialog` (`:111`), `showSliderDialog` (`:117`), `showExportAsUrlDialog` (`:122`), `showExportAsTextDialog` (`:128`), `showExportAsJsonDialog` (`:134`), `showExportAsImageDialog` (`:140`), `showEditCompositeModelDialog` (`:153`), `showScopePropertiesDialog` (`:160`).
- **Does not touch `activeDialog`:**
  - `showAboutBox` (`:71-73`) — `AboutBox` is fire-and-forget, does not extend `Dialog` (see `domain-core__dialog-info.md` issue #1).
  - `showEditDiodeModelDialog` (`:164-168`) and `showEditTransistorModelDialog` (`:170-174`) — both build an `EditDialog` subclass and call `.show()` directly without registering. Inconsistent: other edit dialogs are tracked, these two are not. Probably a bug — pressing Escape/Close via `dialogManager.closeDialog()` would not close them.
- **`show()` is called inconsistently:** some factories call `activeDialog.show()` themselves (Shortcuts, Subcircuit, Search, EditOptions, EditElement, Slider, ExportAs*, EditCompositeModel); others do not (Help, License, Mod, ImportFromText, ScopeProperties). The caller is then expected to invoke `.show()` externally — or the dialog shows itself in its own constructor. This is a subtle contract split; callers must know which pattern applies.
- **Reads:**
  - `dialogIsShowing()` (`:37-39`) — null + `isShowing()` check; used by `BaseCirSim.dialogIsShowing()` (`:215`) to suppress background key handling.
  - `getShowingDialog()` (`:41-46`) — returns `activeDialog` only if it is a project-`Dialog` subclass; returns `null` otherwise (so a tracked `AboutBox`-style `PopupPanel` would be filtered out, but no such case exists because `AboutBox` is not tracked anyway).
  - `closeDialog()` (`:48-54`) — forces close via the project-`Dialog` API, then nulls the slot. Callers: `CustomCompositeElm.java:250`.
  - `resetEditDialog()` (`:56-61`) — if the active dialog is an `EditDialog`, invoke `resetDialog()` (re-read current values). Callers: `SRAMLoadFile.java:59`.

**Dispatch invocation sources:**
- `ActionManager.java:199-291, 525` — menu-bar/shortcut commands. The **primary** caller.
- `CircuitEditor.java:1242/1248/1254` — right-click flows (edit options, edit element, slider).
- `CirSim.java:821` — keyboard "export as SVG".
- `Scope.java:2134` — scope properties dialog (also the only call that **returns** the dialog to the caller for further wiring — `showScopePropertiesDialog` returns `ScopePropertiesDialog` (`:157-162`)).
- Element dialogs accessed via `CircuitDocument.getDialogManager()`: `DiodeElm.java:263/274`, `TransistorElm.java:666/675`, `CustomCompositeElm.java:245/250`, `CustomLogicElm.java:261`. These go through `CircuitDocument.getDialogManager()` (`CircuitDocument.java:287`) instead of `cirSim.dialogManager` directly.
- `BaseCirSim.java:215` — lifecycle check.
- `SRAMLoadFile.java:59` — edit-dialog refresh.

**Observation:** There are two conventions for reaching the manager, `cirSim.dialogManager` (root) and `circuitDocument.getDialogManager()` (via document). Functionally equivalent in current code; the second form is used from element-layer code that already holds a `CircuitDocument`.

## ExtListEntry usage

Used by **exactly three files**, all around the subcircuit/custom-composite feature — **not** menus, not favorites, not an element catalogue:

1. **`CustomCompositeModel.java`** (the model for a reusable subcircuit):
   - Field `public Vector<ExtListEntry> extList;` (`:16`) — the ordered list of external pins.
   - `initModelMap` (`:36-37`) seeds a default "gnd" stub with a single 2-arg entry.
   - `undump(StringTokenizer)` (`:133-149`) reads `extCount` pin records from the serialized model.
   - `dump()` (`:193-207`) writes them out in `". name flags sizeX sizeY extCount [name node pos side]* nodeList elmDump"` format.

2. **`CircuitSimulator.java`** — subcircuit extraction from the current schematic (`:1298-1402`):
   - Walks `LabeledNodeElm`s in the selection, buckets them by inferred chip side (`ChipElm.SIDE_N/E/S/W`), sorts them along that side, and emits `new ExtListEntry(lne.text, lne.getNode(0), pos, side)` per pin (`:1356`).
   - Post-pass verifies each `ent.node` is actually used in the dumped element set (`:1397-1402`).

3. **`dialog/EditCompositeModelDialog.java`** — the UI for editing a composite model's pin layout. Sorts and iterates `model.extList` (`:85-86, 94, 107, 245, 333-337`), exposes per-pin editors for `name` / `pos` / `side` (`node` is fixed from the underlying circuit).

4. **`element/CustomCompositeElm.java:117`** — on element instantiation, reads each pin record to place the chip post.

**So the "external list" in the class name is the `Vector<ExtListEntry> extList` field on `CustomCompositeModel`: the external pin list of a subcircuit.** One entry ≡ one chip pin on the rendered composite element — derived from a labeled node inside the subcircuit body.

- `name` → pin label text (from `LabeledNodeElm.text`).
- `node` → internal node number the pin binds to.
- `pos` → ordinal position along its side (0-based, top-to-bottom or left-to-right).
- `side` → which of the four chip edges the pin sits on (`ChipElm.SIDE_*`).

## Public contracts

### LogManager

- `addLogEntry(String msg)` (`:330`) — append `"[ts] msg"` to memory and queue file write. No level.
- `addLogEntry(String msg, String level)` (`:381-384`) — prepends `"[LEVEL] "` before the timestamp call. Level is free-form (any string).
- `logInfo/logWarning/logError/logDebug(String)` (`:387-401`) — convenience wrappers → `addLogEntry(msg, "INFO"|"WARN"|"ERROR"|"DEBUG")`. **Unused by any current caller** (all usage goes through `addLogEntry(String)` via `BaseCirSim.log`).
- `clearLogs()` (`:342-348`) — `forceFlushLogs()` then `logEntries.clear()` + `updateLogCount()` (no-op).
- `forceFlushLogs()` (`:192-202`) — immediate flush + 500 ms soft wait.
- `shutdown()` (`:351-366`) — flush + session-end banner to file directly (bypasses queue).
- `getQueueSize()` (`:369-373`), `isWriteInProgress()` (`:376-378`) — observability used by `ShowLogDialog.java:153-154`.
- `saveLogsToFile()` (`:407-411`) — force-flush + JSNI `saveAllLogsToFile` (browser fallback: download via Blob URL, `:294-328`).
- `startNewSession()` (`:414-428`) — roll filename, append banner to buffer + queue. Also invoked by the ctor.
- `getCurrentLogFilePath()` (`:431-436`), `isFileLoggingEnabled()` (`:439-441`), `setFileLoggingEnabled(boolean)` (`:444-446`).
- `updateLogCount()` (`:403-405`) — **empty body** (single commented-out line). Vestigial UI hook.

### DialogManager

- All `show*` methods are listed above; signatures tend to take minimal state (a `CircuitElm` for sliders, a `Scope` for scope props, a `DiodeModel`/`TransistorModel`/`CustomCompositeModel` for model editors).
- `dialogIsShowing() : boolean`, `getShowingDialog() : Dialog` (package-private), `closeDialog() : void` (public), `resetEditDialog() : void` (package-private).
- Visibility is mixed: `closeDialog`, `showEditElementDialog`, `showEditCompositeModelDialog`, `showEditDiodeModelDialog`, `showEditTransistorModelDialog` are `public`; most others are package-private. No explicit reason beyond "reachability from `element/`".

### ExtListEntry

- No methods, no invariants enforced, no `equals`/`hashCode`/`toString`. Serialization lives in `CustomCompositeModel.dump/undump`.

## Integration points

- **LogManager in:**
  - `BaseCirSim.java:12` (owns), `:258` (`log(String)` forwards here).
  - `CirSim.java:1404-1434` — JS-bridge: `getLogs`, `getLastLogs(int)`, `getLogCount`, `addLog`, `clearLogs` (this last one **bypasses** `LogManager.clearLogs`, directly `.clear()`-ing the list — Issue).
  - `dialog/ShowLogDialog.java:85/99/124/126/139/150/152/153/154/155` — reads `logEntries`, calls `clearLogs`, `saveLogsToFile`, `getQueueSize`, `isWriteInProgress`, `getCurrentLogFilePath`. Poll-driven live tail (see `dialog-info.md`).
  - `UncaughtExceptionDialog` — installed from `circuitjs1.java:50-66`. Does **not** currently read from `LogManager` (stack trace is assembled from the `Throwable` directly). The task brief says "ShowLogDialog and UncaughtExceptionDialog" consume it; in practice only `ShowLogDialog` does. `UncaughtExceptionDialog` is `LogManager`-**adjacent**, not a consumer.

- **DialogManager in:** see dispatch section above. 14 source files (Grep: `dialogManager|getDialogManager`).

- **ExtListEntry in:** 4 source files (listed under Usage).

- **Depends on external:**
  - `LogManager` → GWT `Scheduler`, `DateTimeFormat`, `Date`, JSNI + NW.js `fs`/`path`, browser `Blob`/`URL`.
  - `DialogManager` → 19 dialog classes + 2 element classes + 4 model/scope classes.
  - `ExtListEntry` → `ChipElm` (single constant `SIDE_W`).

## Issues

1. **LogManager: no retention bound.** `logEntries` grows forever in memory (see Retention). For a long NW.js session this is a memory leak with no circuit-breaker. `ShowLogDialog` already tail-slices to 100; a ring buffer (or tail of last N) would be essentially invisible to consumers and bound RAM.
2. **LogManager: `CirSim.clearLogs` bypass.** `CirSim.java:1432-1434` calls `logManager.logEntries.clear()` instead of `LogManager.clearLogs()`, skipping `forceFlushLogs` and `updateLogCount`. JS callers effectively leak any queued-but-unwritten entries.
3. **LogManager: `synchronized` on `logWriteQueue` is load-bearing only on paper.** GWT compiles to single-threaded JS; the `synchronized` blocks (`:68, 98, 162, 370`) compile to no-ops, so the "defensive" pattern is cosmetic.
4. **LogManager: `logWriteScheduled` set but never read for scheduling.** `scheduleLogWrite` only checks `isWriting`, not `logWriteScheduled`. The flag does nothing at a semantic level.
5. **LogManager: `updateLogCount()` empty.** Body is a single commented-out line (`:404`). Either delete or repurpose.
6. **LogManager: unused level helpers.** `logInfo/logWarning/logError/logDebug` have zero call sites. Either adopt them (replacing `sim.log(...)`) or delete.
7. **LogManager: `forceFlushLogs` wait is ineffective.** `waitForAsyncOperation` loops via `scheduleFixedPeriod(10ms)` but control returns to the caller immediately (GWT cannot block). The 500 ms budget is measured but not respected synchronously — callers assuming "after `forceFlushLogs` the disk is current" are wrong.
8. **LogManager: JSNI `saveAllLogsToFile` uses `fs.writeFileSync`** (`:280`) — a synchronous blocking call on the UI thread in NW.js. Possibly intentional for the user-triggered Save action, but inconsistent with the otherwise-async write path.
9. **LogManager: log directory fallback** (`:210-236`) uses `process.cwd()` — i.e. the NW.js working directory at launch, which may be non-writable depending on how the app is packaged.
10. **DialogManager: inconsistent `show()` calling convention.** Some factories call `.show()` internally, others return the dialog un-shown. A caller that assumes the "internal show" pattern on a factory that doesn't follows it will produce an invisible dialog.
11. **DialogManager: edit-model dialogs are not tracked.** `showEditDiodeModelDialog` (`:164-168`) and `showEditTransistorModelDialog` (`:170-174`) never assign `activeDialog`. `dialogIsShowing()` and `closeDialog()` cannot see them — key handling won't be suppressed, and global close won't reach them.
12. **DialogManager: single-slot overwrite.** Opening a second dialog before closing the first silently replaces `activeDialog`, orphaning the first for lifecycle tracking (the GWT `DialogBox` itself remains showing). No re-entrancy guard.
13. **DialogManager: `showAboutBox` outlier** — consistent with existing `dialog-info.md` issue #1.
14. **DialogManager: fan-in on 19 dialog classes.** This class is the single largest single-file importer of `client.dialog.*`. A registry pattern (`Map<Command, DialogFactory>`) would cut the import list and let `ActionManager.doMenuChecks` become a single `dispatcher.run(command)` call.
15. **DialogManager: downcasts `cirSim` to `CirSim` in most methods.** Pattern `CirSim cirSim = (CirSim) this.cirSim;` appears 14× — redundant boilerplate. Either make `BaseCirSimDelegate` generic in its owner type, or introduce a protected accessor `cirSim()` that returns the already-narrowed type where safe.
16. **ExtListEntry: all fields public, mutable, no invariants.** No `equals`/`hashCode`/`toString`. Used in sort comparators by `name` (`EditCompositeModelDialog.java:85-87`) but the class does not encode that ordering. Candidate for `record` semantics (immutable value).
17. **ExtListEntry: class name is generic for a specific role.** "External list entry" without context; `SubcircuitPin` or `CompositePin` would be unambiguous.
18. **Task brief vs. reality — `UncaughtExceptionDialog` does not read `LogManager`.** Worth noting for consistency with `dialog-info.md`.

## Concept boundary

Three unrelated concepts; grouping them as "app-managers" would obscure their very different stakeholder surfaces. Recommend **three concepts** rather than one:

1. **`session-logging`** — owner: `LogManager`. Responsibilities: in-memory log buffer with unbounded retention, async batched file append via NW.js, user-initiated export, viewer-side observability (queue size, write-in-progress). Relationships: `util.Log` is distinct and dormant; `BaseCirSim.log` is the project-wide adapter into this concept; `ShowLogDialog` is the primary live consumer; `CirSim` exposes a JS-bridge subset. Boundary issue: decide if `util.Log` should be folded into (or renamed away to avoid collision with) this concept — today they are unrelated despite the shared "log" name.

2. **`dialog-routing`** — owner: `DialogManager`. Responsibilities: single-slot current-dialog tracking, centralised factory for most modal/floating dialogs, show-is-running check consumed by `BaseCirSim`. Boundaries: non-modal and programmatic dialogs (`AboutBox`, `ShowLogDialog`, `UncaughtExceptionDialog`) intentionally bypass the slot. Fits cleanly beside `dialog-base` / `dialog-info` / `dialog-edit` / `dialog-export` / `dialog-import` / `dialog-specialized` concepts as their *router*, not a peer.

3. **`subcircuit-pin`** — owner: `ExtListEntry` (+ related usage on `CustomCompositeModel`, `EditCompositeModelDialog`, `CustomCompositeElm`, `CircuitSimulator` extraction path). Responsibilities: represent one external pin of a custom composite (subcircuit) model for storage, editing, layout, and wiring. This is **not** a manager concept at all — it belongs to the subcircuit / custom-composite feature and should likely be documented together with `CustomCompositeModel` / `CustomCompositeElm` (candidate concept: `custom-composite-model`, currently unscoped in the analysis directory).

A single "app-managers" concept is **not recommended** — the three files have no shared invariants, lifecycle, or consumer surface. The only thing they share is package location and the fact that two of them extend `BaseCirSimDelegate`. `ExtListEntry` is only here by accident of directory layout and should be migrated (conceptually if not physically) into the subcircuit cluster.

## Relevant files

- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/LogManager.java`
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/DialogManager.java`
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/ExtListEntry.java`
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/BaseCirSim.java` (wiring lines 12, 15, 215, 257-259)
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/BaseCirSimDelegate.java`
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/CirSim.java` (JS bridge 1404-1434, dialog calls 821)
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/CircuitSimulator.java` (ExtListEntry producer, 1290-1402)
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/CustomCompositeModel.java` (ExtListEntry owner / serializer)
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/element/CustomCompositeElm.java` (ExtListEntry consumer; DialogManager consumer)
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/dialog/EditCompositeModelDialog.java` (ExtListEntry UI)
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/dialog/ShowLogDialog.java` (LogManager consumer)
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/ActionManager.java` (DialogManager primary caller)
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/src/main/java/com/lushprojects/circuitjs1/client/CircuitDocument.java` (`getDialogManager`, line 287)
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/.dev_flow/onboard/analysis/util.md` (Log relationship)
- `/home/yavfast/Projects/My_projects/Circuit/circuitjs1_desktop/.dev_flow/onboard/analysis/domain-core__dialog-info.md` (ShowLogDialog / UncaughtExceptionDialog surfaces)
