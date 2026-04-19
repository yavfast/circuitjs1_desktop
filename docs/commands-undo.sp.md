# Commands & Undo — Specification  {#SP_UND}

> **Code:** SP_UND
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
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
| dump | String | yes | non-empty | Full circuit dump from the active `CircuitFormat`. |
| scale | double | yes | > 0 | `transform[0]`. |
| tx | double | yes | — | `transform[4]`. |
| ty | double | yes | — | `transform[5]`. |

Invariants: immutable after construction.

### 01_02. UndoManager state  {#SP_UND_01_02}

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| undoStack | Vector<UndoItem> | empty | LIFO of pre-edit snapshots. |
| redoStack | Vector<UndoItem> | empty | LIFO of post-edit snapshots after an undo. |
| recovery | String | null | Crash auto-save payload. |

## 02. Contracts  {#SP_UND_02}

### 02_01. pushUndo  {#SP_UND_02_01}

Purpose: Record current state before a mutation.

    FUNCTION pushUndo():
        dump = actionManager.dumpCircuit()
        IF undoStack not empty AND undoStack.last().dump == dump:
            RETURN                             -- dedup
        redoStack.clear()
        undoStack.push(new UndoItem(dump, transform[0], transform[4], transform[5]))

### 02_02. doUndo / doRedo  {#SP_UND_02_02}

    FUNCTION doUndo():
        IF undoStack.size < 1: RETURN
        currentDump = actionManager.dumpCircuit()
        redoStack.push(new UndoItem(currentDump, transform[0/4/5]))
        item = undoStack.pop()
        loadUndoItem(item)

    FUNCTION loadUndoItem(item):
        circuitLoader.readCircuit(item.dump, RC_NO_CENTER)
        renderer.transform[0] = renderer.transform[3] = item.scale
        renderer.transform[4] = item.tx
        renderer.transform[5] = item.ty

### 02_03. Recovery slot  {#SP_UND_02_03}

    FUNCTION writeRecoveryToStorage():
        recovery = actionManager.dumpCircuit()
        OptionsManager.setOptionInStorage("circuitRecovery", recovery)

    FUNCTION readRecovery():
        recovery = OptionsManager.getOptionFromStorage("circuitRecovery", null)

    FUNCTION doRecover():
        IF recovery == null: RETURN
        pushUndo
        circuitLoader.readCircuit(recovery, RC_NO_CENTER)

### 02_04. clearStacks / resetAndSeedFromCurrentCircuit  {#SP_UND_02_04}

    FUNCTION clearStacks(): undoStack.clear(); redoStack.clear()
    FUNCTION resetAndSeedFromCurrentCircuit(): clearStacks; pushUndo()

## 03. Validation Rules  {#SP_UND_03}

- `pushUndo` never pushes on an empty circuit during boot before `resetAndSeedFromCurrentCircuit`.
- `loadUndoItem` runs only when not inside a modal dialog (editor gating).
- `recovery` may be null (no prior session).

## 04. State Transitions  {#SP_UND_04}

| From | To | Trigger | Side effects |
|------|----|---------|--------------|
| (u_n, r_m) | (u_n+1, r_0) | pushUndo (new dump) | redo cleared |
| (u_n+1, r_m) | (u_n, r_m+1) | doUndo | loadUndoItem top of undo |
| (u_n, r_m+1) | (u_n+1, r_m) | doRedo | loadUndoItem top of redo |
| (u_n, r_m) | (0, 0) | clearStacks | stacks emptied |

## 05. Verification Criteria  {#SP_UND_05}

### 05_01. Functional  {#SP_UND_05_01}

| Contract | Scenario | Input | Expected |
|----------|----------|-------|----------|
| pushUndo | repeated no-op | identical dump | single stack entry (dedup) |
| doUndo/doRedo | round-trip | edit then undo then redo | dump identical to post-edit |
| doRecover | crash restore | recovery set | circuit equals last checkpoint; transform preserved |

### 05_02. Invariants  {#SP_UND_05_02}

| Invariant | Verification |
|-----------|--------------|
| redo always empty after pushUndo | inspect redoStack after edit |
| consecutive identical dumps collapse | inspect undoStack size |

### 05_03. Edge Cases  {#SP_UND_05_03}

| Case | Input | Expected |
|------|-------|----------|
| Drag produces two entries | mouseDown + mouseUp | 2 undoStack entries per drag |
| Undo after tab switch | switch tabs mid-drag | undoes on current active document |
| Very large circuit | large elmList | snapshot still completes; latency O(dump size) |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
