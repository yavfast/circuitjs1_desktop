# Commands & Undo — Specification  {#SP_UND}

> **Code:** SP_UND
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-10-02
>
> **Concept:** [C_UND](./commands-undo.concept.md)
> **Depends on specs:** SP_IOF, SP_DOC
> **Used by specs:** [SP_EDI](./canvas-editor.sp.md), [SP_MEN](./menus-actions.sp.md), [SP_CLP](./clipboard.sp.md), [SP_FBR](./browser-file-bridge.sp.md)
> **Plan:** [commands-undo.plan.md](./commands-undo.plan.md)

## 01. Data Structures  {#SP_UND_01}

> Implements: [C_UND_02](./commands-undo.concept.md#C_UND_02)

### 01_01. UndoItem  {#SP_UND_01_01}

| Field | Type | Required | Constraints | Description |
|-------|------|----------|-------------|-------------|
| dump | String | yes | non-empty | Full circuit dump of the manager's own document from the default (text) exporter. |
| viewTransform | double[3] | yes | scale ≥ 0 | `{scale, tx, ty}` of the document's own view (renderer `transform[0/4/5]` while bound, `document.transform` otherwise); scale 0 = no view, not restored. |
| elementIds | String[] | yes | — | IDs of the dumped elements in dump order (`getDumpedElementIds`), [SP_AGA_01_10](./agent-api.sp.md#SP_AGA_01_10). |
| openMarks | String[] | yes | — | Open marks of the snapshot ([SP_AGA_01_12](./agent-api.sp.md#SP_AGA_01_12)). |
| endpoints | int[][] | yes | aligned with `elementIds` | `{x1, y1, x2, y2}` per dumped element; re-applied where the text reload rewrote them. |
| comment | String | no | — | Checkpoint comment; null for user edits ([SP_AGA_01_10](./agent-api.sp.md#SP_AGA_01_10)). |
| checkpointId | String | no | `cp<n>`, never reused | Present on sealed agent entries. |
| auto | boolean | no | — | True when sealed automatically (`"agent edits (auto)"`). |

Invariants: the content fields (`dump` … `endpoints`) are final after construction. The label fields (`comment`, `checkpointId`, `auto`) are set when an agent transaction is sealed; the entry an undo/redo pushes onto the other stack copies them from the popped entry. `sameContent(other)` = equal `dump`, `elementIds` and `openMarks`.

### 01_02. UndoManager state  {#SP_UND_01_02}

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| undoStack | Vector<UndoItem> | empty | LIFO of pre-edit snapshots; at most `MAX_UNDO_DEPTH` = 150, oldest dropped first. |
| redoStack | Vector<UndoItem> | empty | LIFO of post-edit snapshots after an undo; same cap. |
| transactionOpen, pendingEdits, lastAgentMutationAt, checkpointCounter, idleTimer | — | closed, 0 | The document's agent transaction ([SP_AGA_01_10](./agent-api.sp.md#SP_AGA_01_10)); its entry is always `undoStack.last()`. |
| tentative | UndoItem | null | Pre-gesture state of a mouse press held back while a transaction is open. |
| gestureActive | boolean | false | True from the mouse press (`pushUndoForGesture`) to its mouse-up (`endGesture`). |

## 02. Contracts  {#SP_UND_02}

### 02_01. pushUndo  {#SP_UND_02_01}

Purpose: Record current state before a mutation.

    FUNCTION pushUndo():
        IF document.isAgentOrigin(): RETURN    -- editor path reused inside an agent mutation
        document.cancelAgentRun()              -- SP_AGA_04_02
        resolveTentativePush()
        item = new UndoItem()                  -- captured from the manager's own document
        sealTransaction()
        IF undoStack not empty AND undoStack.last().checkpointId != null AND undoStack.last().sameContent(item):
            undoStack.pop()                    -- sealed agent entry without net change
        redoStack.clear()
        IF undoStack not empty AND item.sameContent(undoStack.last()):
            RETURN                             -- dedup
        undoStack.push(item); trimToMaxDepth(undoStack)

    FUNCTION pushUndoForGesture():             -- CircuitEditor.onMouseDown
        IF agent origin: RETURN
        cancelAgentRun(); resolveTentativePush(); gestureActive = true
        IF transactionOpen: tentative = new UndoItem() ELSE pushUndo()

    FUNCTION endGesture():                     -- CircuitEditor.onMouseUp
        resolveTentativePush(); gestureActive = false

    FUNCTION resolveTentativePush():
        IF tentative == null: RETURN
        t = tentative; tentative = null
        IF new UndoItem().sameContent(t): RETURN    -- the gesture changed nothing; transaction stays open
        push t as above (seal, no-net drop, clear redo, dedup, push, trim)

`splitGesture(op)` runs an agent operation of the document: while `gestureActive` it resolves the tentative push before `op` and afterwards (also when `op` throws) re-arms `tentative` with the post-operation state, unless no transaction is open, the state is unchanged and a user entry is on top.

### 02_02. doUndo / doRedo  {#SP_UND_02_02}

    FUNCTION doUndo():                         -- doRedo symmetric with redo(1)
        document.cancelAgentRun(); resolveTentativePush(); sealTransaction()
        IF undoStack.size < 1: RETURN
        undo(1)

    FUNCTION undo(steps) / redo(steps):        -- move(from, to, steps)
        current = new UndoItem(); save copies of both stacks
        leaving = current
        REPEAT steps: target = from.pop(); to.push(copy of leaving's content with target's labels); leaving = target
        IF redo: trimToMaxDepth(to)
        TRY: RETURN loadUndoItem(target)       -- ids_regenerated warning or null
        CATCH e: restore both stacks; loadUndoItem(current) (a failure is logged, attached as suppressed); RETHROW e

    FUNCTION loadUndoItem(item):               -- always the manager's own document
        document.beginElementIdRestore(item.elementIds)
        TRY: circuitLoader.readCircuit(item.dump, RC_NO_CENTER)
        FINALLY: document.endElementIdRestore()
        document.setOpenMarks(item.openMarks)
        re-apply item.endpoints that differ (only when count and IDs match one to one)
        IF item.viewTransform.scale != 0:
            t = renderer.transform IF document is bound ELSE document.transform
            t[0] = t[3] = scale; t[4] = tx; t[5] = ty
        RETURN document.takeIdRestoreWarning()

During the restore the import keeps the ID counters and gives element i `elementIds[i]`; a count mismatch regenerates the IDs in order with an `ids_regenerated` warning ([SP_AGA_03_02](./agent-api.sp.md#SP_AGA_03_02)). `captureState()` returns an unpushed `UndoItem`; `restoreState(item)` is `loadUndoItem(item)` without touching the stacks ([SP_AGA_03_04](./agent-api.sp.md#SP_AGA_03_04)). The menu shows `Undo: <comment>` / `Redo: <comment>` when `getUndoComment()` / `getRedoComment()` is non-null.

### 02_03. Recovery slot (removed)  {#SP_UND_02_03}

Removed 2026-10-01 (PL_AUDIT_20260930_173830 BL-C01): no `recovery` field, no `writeRecoveryToStorage` / `readRecovery` / `doRecover`, no "Recover Auto-Save" menu item. At startup `CirSim` calls `OptionsManager.removeOptionFromStorage("circuitRecovery")` once to free the dump older builds left behind. The ID stays reserved.

### 02_04. clearStacks / resetAndSeedFromCurrentCircuit  {#SP_UND_02_04}

    FUNCTION clearStacks(): undoStack.clear(); redoStack.clear()
    FUNCTION resetAndSeedFromCurrentCircuit(): sealTransaction(); tentative = null; clearStacks; pushUndo()

## 03. Validation Rules  {#SP_UND_03}

- `pushUndo` never pushes on an empty circuit during boot before `resetAndSeedFromCurrentCircuit`.
- `loadUndoItem` runs only when not inside a modal dialog (editor gating).

## 04. State Transitions  {#SP_UND_04}

| From | To | Trigger | Side effects |
|------|----|---------|--------------|
| (u_n, r_m) | (u_n+1, r_0) | pushUndo (new dump) | redo cleared |
| (u_n+1, r_m) | (u_n, r_m+1) | doUndo | loadUndoItem top of undo |
| (u_n, r_m+1) | (u_n+1, r_m) | doRedo | loadUndoItem top of redo |
| (u_n, r_m) | (0, 0) | clearStacks | stacks emptied |
| (u_n, r_m) | (u_n+1 or u_n, r_0) | first agent mutation (`noteAgentMutation`) | transaction opens; pre-mutation entry pushed unless the newest entry equals it and has no comment; redo cleared |
| (u_n, r_m), open | (u_n, r_m), open | further agent mutation | `pendingEdits += 1` |
| open | closed | seal (user push, user undo/redo, save, user content replacement, idle 300 s, `checkpoint`) | newest entry gets comment, `cp<n>`, `auto` |
| open | closed, (u_n−1, r_m) | `checkpoint` with no net change (`dropTransactionEntry`) | transaction entry removed |
| sealed agent entry on top | (u_n, r_0) | user push whose state equals that entry | entry dropped, user entry pushed |
| press, open | tentative held | `pushUndoForGesture` | resolved at `endGesture`: pushed (sealing) if changed, else dropped |

The agent-transaction state machine is defined in [SP_AGA_04_01](./agent-api.sp.md#SP_AGA_04_01); the rows above are its effect on the stacks. Closing the document discards an open transaction (`discardTransaction`).

## 05. Verification Criteria  {#SP_UND_05}

### 05_01. Functional  {#SP_UND_05_01}

| Contract | Scenario | Input | Expected |
|----------|----------|-------|----------|
| pushUndo | repeated no-op | identical dump, IDs and open marks | single stack entry (dedup) |
| doUndo/doRedo | round-trip | edit then undo then redo | dump identical to post-edit |

### 05_02. Invariants  {#SP_UND_05_02}

| Invariant | Verification |
|-----------|--------------|
| redo always empty after pushUndo | inspect redoStack after edit |
| consecutive entries with the same content (`sameContent`) collapse | inspect undoStack size |

### 05_03. Edge Cases  {#SP_UND_05_03}

| Case | Input | Expected |
|------|-------|----------|
| Gesture that places an element | mouseDown + mouseUp with `circuitChanged` | 2 undoStack entries (press and mouse-up); a move, select click or pan pushes only on press (deduped when the newest entry already equals the state) |
| Press while a transaction is open | click that changes nothing | no entry; the transaction stays open |
| Undo of a non-visible document | agent undo via `DocumentScope` | restores the manager's own document, never the visible tab |
| Transformer endpoints | undo after editing an axis-aligned transformer | endpoints the text reload rewrote are re-applied |
| ID count mismatch | restored element count ≠ `elementIds.length` | IDs regenerated in order; `ids_regenerated` warning |
| Load throws | `debugFailNextUndoLoad` | both stacks and document state restored; exception rethrown |
| Very large circuit | large elmList | snapshot still completes; latency O(dump size) |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
| 2026-10-01 | SP_UND_02_03 recovery slot removed (BL-C01). |
| 2026-10-02 | PL_AGA Phase 10 propagate: UndoItem fields (viewTransform, elementIds, openMarks, endpoints, labels) and invariants; manager state (transaction, tentative, gestureActive, 150 cap); pushUndo / gesture / doUndo / undo(n) / loadUndoItem pseudo-code; seed seals first; agent-transaction transitions (ref SP_AGA_04_01); edge cases (conditional two entries, own document, transformer endpoints, ids_regenerated, load rollback). |
