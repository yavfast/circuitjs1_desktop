# Commands & Undo — Full-Snapshot History  {#C_UND}

> **Code:** C_UND
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-10-02
> **Author:** onboard-doc-gen
>
> **Depends on:** C_IOF (io-framework), [C_DOC](./document-model.concept.md)
> **Used by:** [C_EDI](./canvas-editor.concept.md), [C_MEN](./menus-actions.concept.md), [C_CLP](./clipboard.concept.md), [C_FBR](./browser-file-bridge.concept.md)
> **Spike:** —
> **Specification:** [SP_UND](./commands-undo.sp.md)
> **Plan:** [commands-undo.plan.md](./commands-undo.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/layer3__editor-interaction.md` §5.
>
> A full-snapshot undo/redo stack per document. Every mutating edit in the editor calls `pushUndo()`, which captures an `UndoItem` of the manager's own document — the circuit text from the default (text) exporter plus the element IDs, open marks, element endpoints and the document's own view transform — and dedupes against the previous snapshot. Restoration rebuilds the whole circuit through `CircuitLoader.readCircuit`. The same stack holds the document's agent transaction ([SP_AGA_04_01](./agent-api.sp.md#SP_AGA_04_01)).

## 1. Philosophy  {#C_UND_01}

### 1.1. Core Principle  {#C_UND_01_01}

Two unrelated senses of "command" exist in the codebase; this concept owns only the **undo record** (`UndoItem`) sense. `MyCommand(menu, item)` is a GWT menu/toolbar token handled by the menus-actions concept and is *not* undoable by itself — it merely *triggers* mutations that the editor then wraps with `pushUndo`.

The undo engine stores full textual dumps, not inverse commands. This keeps the contract trivial (anything the io exporter round-trips is reversible) at the cost of O(dump size) per edit; it exploits the existing io format as a schema-stable state vector.

### 1.2. Design Constraints  {#C_UND_01_02}

- **Full-snapshot only.** No inverse commands, no deltas. The only merge is the agent transaction: consecutive successful agent mutations of a document share one undo entry until it is sealed ([SP_AGA_04_01](./agent-api.sp.md#SP_AGA_04_01)); user edits never merge.
- **Dedup on push.** A push equal to the newest entry is skipped; equality (`UndoItem.sameContent`) compares the dump, the element IDs and the open marks.
- **Transform captured.** The document's own view transform (`{scale, tx, ty}`) is stored in the `UndoItem` — taken from the renderer while the document is bound, from the document's saved `transform` otherwise — and restored after `readCircuit(RC_NO_CENTER)`. Scale 0 means the document had no view yet; such a transform is not restored.
- **Bounded depth.** At most `MAX_UNDO_DEPTH = 150` entries per stack; the oldest entries are dropped first.
- **No crash-recovery slot.** The former "Recover Auto-Save" slot (one global `circuitRecovery` localStorage key) was removed on 2026-10-01: its read side had long been disabled while every edit still wrote a full dump. Restoring open tabs on restart is the session-restore job of [C_DOC](./document-model.concept.md); `CirSim` deletes the stale key once at startup.
- **Redo cleared on every push.** A new edit always invalidates the redo path.

## 2. Domain Model  {#C_UND_02}

### 2.1. Key Entities  {#C_UND_02_01}

```
UndoManager extends BaseCirSimDelegate
  Vector<UndoItem> undoStack
  Vector<UndoItem> redoStack

  -- agent transaction (SP_AGA_01_10), at most one per document
  boolean transactionOpen; int pendingEdits; int checkpointCounter; idle Timer
  -- mouse gesture state
  UndoItem tentative     -- pre-gesture state held back while a transaction is open
  boolean gestureActive  -- mouse press .. mouse-up

UndoItem (inner)
  String dump              -- full circuit text from the default (text) exporter
  double[3] viewTransform  -- {scale, tx, ty} of the document's own view; scale 0 = no view
  String[] elementIds      -- IDs of the dumped elements in dump order
  String[] openMarks       -- open marks of the snapshot (SP_AGA_01_12)
  int[][] endpoints        -- {x1, y1, x2, y2} per dumped element, aligned with elementIds
  String comment, checkpointId; boolean auto   -- labels, set when an agent transaction is sealed
```

`UndoManager` is constructed per `CircuitDocument` and always addresses that document: `getActiveDocument()` of the delegate returns the manager's own document, not the session's visible one. Capture and restore therefore work for a background document too; agent operations on one run inside `DocumentScope`, which binds it silently for the call so the options line and view transform are the document's own. The content fields of an `UndoItem` are final; the label fields are set at seal time, and the entry an undo/redo pushes copies them from the popped entry ([SP_AGA_01_10](./agent-api.sp.md#SP_AGA_01_10)).

### 2.2. Data Flows  {#C_UND_02_02}

```
Mutating edit path (e.g. editor.doDelete, editor.doPaste, menu "centrecircuit"):
  pushUndo()
    if document is agent origin: return       -- the transaction holds the pre-mutation entry
    cancel the document's agent run; resolve a tentative gesture push
    seal an open agent transaction
    drop the newest entry if it is a sealed agent entry equal to the new state (no net change)
    redoStack.clear()
    if new state sameContent undoStack.last(): return   -- dedup
    undoStack.push(UndoItem()); trim to MAX_UNDO_DEPTH

  ...perform mutation on simulator().elmList...
  needAnalyze; setUnsavedChanges

doUndo():
  cancel the agent run; resolve the tentative push; seal the transaction
  undo(1): push current state (with the popped entry's labels) to redoStack
           item = undoStack.pop(); loadUndoItem(item)

doRedo(): symmetric (redo(1)).
```

## 3. Mechanisms  {#C_UND_03}

### 3.1. Core Algorithm  {#C_UND_03_01}

**Push.** `pushUndo` is idempotent against trivial no-op moves (dedup). Every mouse press pushes the pre-gesture state (`pushUndoForGesture`); the mouse-up pushes again only when the gesture placed an element or released a held switch, so only those gestures produce two entries. While an agent transaction is open the press push is held back as *tentative* and resolved at mouse-up (`endGesture`): dropped when the circuit is unchanged (the transaction stays open), otherwise the transaction is sealed and the pre-gesture state is pushed above the agent entry.

**Load.** `loadUndoItem` brackets the text reload with `beginElementIdRestore`/`endElementIdRestore`: the import keeps the ID counters (no content-lifetime reset) and gives element i the snapshot's `elementIds[i]`; when the restored element count differs, the IDs are regenerated in order and an `ids_regenerated` warning is logged and returned. It then restores the open marks, re-applies every endpoint the text reload rewrote (an axis-aligned transformer's text constructor synthesizes the diagonal corner), and applies the stored transform unless its scale is 0. `RC_NO_CENTER` keeps the current pan/zoom during the reload. Any simulator-internal state (Newton iteration guesses, scope cursor) is *not* captured and is reset by the reload. When the load throws, `undo`/`redo` put both stacks and the document's state back and rethrow.

**Seed after load.** `resetAndSeedFromCurrentCircuit()` is called after a user load or a new document: it seals an open agent transaction, drops a tentative gesture push, clears both stacks and pushes the just-loaded state, so the first `doUndo` cannot revert to the pre-load placeholder.

**Agent transactions.** The transaction state lives on `UndoManager` because its entry is always the newest undo entry and almost every seal trigger is a user path at the client root. `noteAgentMutation(preMutation)` opens the transaction — reusing the newest entry when it equals the pre-mutation state and carries no comment, otherwise pushing the pre-mutation state, and clearing redo — or continues it without a new entry. A user push, user undo/redo, save, user content replacement or the 300 s idle timer seal it with the auto comment; `checkpoint` seals it with the agent's comment, or drops its entry when nothing changed (`dropTransactionEntry`); closing the document discards it. User undo/redo and every user push first cancel an agent run of the document. Agent operations run inside `splitGesture`, so a held mouse gesture is split around the operation and its remainder never joins the agent's entry. The full state machine is [SP_AGA_04_01](./agent-api.sp.md#SP_AGA_04_01).

### 3.2. Edge Cases  {#C_UND_03_02}

- First push after boot has no predecessor — dedup guard short-circuits.
- A mutation that does not change the dump (e.g. flipping an element with symmetric geometry) still pushes once, then dedups on the next push — one spurious stack entry.
- `loadUndoItem` always restores the manager's own document, even when another tab is visible; a background restore (agent undo of a non-visible document) runs inside `DocumentScope`.
- A restored element count that differs from the snapshot's ID count regenerates all IDs in order (`ids_regenerated` warning); endpoints are re-applied only when the reload recreated the snapshot's elements one to one.

## 4. Integration Points  {#C_UND_04}

### 4.1. Dependencies  {#C_UND_04_01}

- **C_IOF (io-framework)** — `CircuitFormatRegistry.getDefault()` exporter on the manager's own document; `CircuitLoader.readCircuit(dump, RC_NO_CENTER)` for restoration; `ImportLifecycle.resetCircuitState` seals the transaction on a user content replacement.
- **C_DOC** — per-document lifecycle (`undoManager = new UndoManager(cirSim, doc)`); element ID restore bracket, open marks and `dispose()` → `discardTransaction()` live on `CircuitDocument`.
- **[SP_AGA](./agent-api.sp.md)** — undo entry extension ([SP_AGA_01_10](./agent-api.sp.md#SP_AGA_01_10)), agent transaction ([SP_AGA_04_01](./agent-api.sp.md#SP_AGA_04_01)), run cancellation ([SP_AGA_04_02](./agent-api.sp.md#SP_AGA_04_02)).
- **[C_EDI](./canvas-editor.concept.md)** — all mutation paths wrap with `pushUndo`; `doUndo/doRedo` proxied from editor.

### 4.2. API Surface  {#C_UND_04_02}

- `clearStacks()` — wipe both stacks (new circuit, import).
- `resetAndSeedFromCurrentCircuit()` — seal, drop the tentative push, clear + push current.
- `pushUndo()` — seal, no-net drop, dedup, push; clears redo. `pushUndoForGesture()` / `endGesture()` — the mouse press / mouse-up pair; `splitGesture(op)` — runs an agent operation around a held gesture.
- `doUndo()`, `doRedo()` — user undo/redo (cancel run, resolve tentative, seal, then `undo(1)` / `redo(1)`). `undo(n)` / `redo(n)` — pop n entries, load only the last; return the `ids_regenerated` warning or null.
- `captureState()` / `restoreState(item)` — an unpushed snapshot and its exact restore (agent pre-call snapshot and rollback, [SP_AGA_03_04](./agent-api.sp.md#SP_AGA_03_04)).
- `getUndoEntry(p)` / `getRedoEntry(p)` / `findCheckpoint(id)` — read access by position (0 = next to apply).
- `noteAgentMutation(pre)`, `seal(comment, auto)`, `sealTransaction()`, `dropTransactionEntry()`, `discardTransaction()` — the agent transaction.
- `getUndoComment()` / `getRedoComment()` — the menu shows `Undo: <comment>` / `Redo: <comment>` when the entry the next undo/redo applies carries a comment ([SP_AGA_06_01](./agent-api.sp.md#SP_AGA_06_01) item 3).
- Inner `UndoItem` — snapshot record: final content fields, label fields set at seal; `sameContent(other)` is the dedup equality.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
| 2026-10-01 | Crash-recovery slot ("Recover Auto-Save") removed — developer decision, PL_AUDIT_20260930_173830 BL-C01. |
| 2026-10-02 | PL_AGA Phase 10 propagate: snapshot captured from the manager's own document through the default exporter with element IDs, open marks, endpoints and own view transform; `sameContent` dedup, 150-entry cap; agent transaction (merge, seal, no-net drop), tentative gesture push and `splitGesture`; load with ID-restore bracket, open marks, endpoint re-apply and failure rollback; seed seals first; background restore; API surface and `Undo: <comment>` menu labels. |
