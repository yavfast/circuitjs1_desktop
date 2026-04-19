# Commands & Undo — Full-Snapshot History  {#C_UND}

> **Code:** C_UND
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
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
> A full-snapshot undo/redo stack plus a separate crash-recovery slot. Every mutating edit in the editor calls `pushUndo()`, which serializes the entire circuit via the active `CircuitFormat` exporter (through `ActionManager.dumpCircuit()`) and dedupes against the previous snapshot. Restoration rebuilds the whole circuit through `CircuitLoader.readCircuit`.

## 1. Philosophy  {#C_UND_01}

### 1.1. Core Principle  {#C_UND_01_01}

Two unrelated senses of "command" exist in the codebase; this concept owns only the **undo record** (`UndoItem`) sense. `MyCommand(menu, item)` is a GWT menu/toolbar token handled by the menus-actions concept and is *not* undoable by itself — it merely *triggers* mutations that the editor then wraps with `pushUndo`.

The undo engine stores full textual dumps, not inverse commands. This keeps the contract trivial (anything the io exporter round-trips is reversible) at the cost of O(dump size) per edit; it exploits the existing io format as a schema-stable state vector.

### 1.2. Design Constraints  {#C_UND_01_02}

- **Full-snapshot only.** No inverse commands, no deltas, no merging.
- **Dedup on push.** Consecutive identical dumps collapse (`undoStack.last().dump.equals(newDump)`).
- **Transform captured.** The view transform (`transform[0]`, `[4]`, `[5]`) is stored in the `UndoItem` and restored after `readCircuit(RC_NO_CENTER)`.
- **Recovery is a separate slot.** `recovery` is a single `String` written to `localStorage` under key `circuitRecovery`; not a stack.
- **Redo cleared on every push.** A new edit always invalidates the redo path.

## 2. Domain Model  {#C_UND_02}

### 2.1. Key Entities  {#C_UND_02_01}

```
UndoManager extends BaseCirSimDelegate
  Vector<UndoItem> undoStack
  Vector<UndoItem> redoStack
  String           recovery          -- crash auto-save

UndoItem (inner)
  String dump           -- full circuit text from CircuitFormat exporter
  double scale, tx, ty  -- view transform snapshot
```

`UndoManager` is constructed per `CircuitDocument` but resolves the document via `getActiveDocument()` when loading — correct only while this undo instance *is* active.

### 2.2. Data Flows  {#C_UND_02_02}

```
Mutating edit path (e.g. editor.doDelete, editor.doPaste, menu "centrecircuit"):
  pushUndo()
    dump = actionManager.dumpCircuit()        -- through CircuitFormatRegistry
    if dump == undoStack.last().dump: return  -- dedup
    redoStack.clear()
    undoStack.push(UndoItem(dump, transform))

  ...perform mutation on simulator().elmList...
  needAnalyze; setUnsavedChanges; writeRecoveryToStorage (for destructive ops)

doUndo():
  push current (dump+transform) to redoStack
  item = undoStack.pop()
  loadUndoItem(item) -> circuitLoader.readCircuit(item.dump, RC_NO_CENTER)
                       restore transform[0/4/5]

doRedo(): symmetric.

Recovery:
  writeRecoveryToStorage: recovery = actionManager.dumpCircuit();
                          OptionsManager.setOptionInStorage("circuitRecovery", recovery)
  readRecovery (at boot): recovery = OptionsManager.getOptionFromStorage(...)
  doRecover (menu): pushUndo; circuitLoader.readCircuit(recovery, RC_NO_CENTER)
```

## 3. Mechanisms  {#C_UND_03}

### 3.1. Core Algorithm  {#C_UND_03_01}

**Push.** `pushUndo` is idempotent against trivial no-op moves (dedup). A drag produces two entries: one on mouseDown (before mutation), one on mouseUp (after successful change).

**Load.** `RC_NO_CENTER` preserves current pan/zoom, then the stored transform overrides. Any simulator-internal state (Newton iteration guesses, scope cursor) is *not* captured and is reset by the reload.

**Seed after load.** `resetAndSeedFromCurrentCircuit()` is called after `CircuitLoader` finishes: clears both stacks and pushes the just-loaded state, so the first `doUndo` cannot revert to the pre-load placeholder.

**Recovery slot.** Written on destructive ops (delete, paste, import) and at periodic checkpoints; read once at boot into `undoManager.recovery`. The "Recover Auto-Save" menu item calls `doRecover`.

### 3.2. Edge Cases  {#C_UND_03_02}

- First push after boot has no predecessor — dedup guard short-circuits.
- A mutation that does not change the dump (e.g. flipping an element with symmetric geometry) still pushes once, then dedups on the next push — one spurious stack entry.
- `loadUndoItem` resolves via `getActiveDocument()`; if the user switches tabs mid-undo the wrong document would be edited (not currently reachable — undo/redo is blocked while a dialog is showing).

## 4. Integration Points  {#C_UND_04}

### 4.1. Dependencies  {#C_UND_04_01}

- **C_IOF (io-framework)** — `ActionManager.dumpCircuit()` → `CircuitFormatRegistry` exporter; `CircuitLoader.readCircuit(dump, flags)` for restoration.
- **C_DOC** — per-document lifecycle (`undoManager = new UndoManager(cirSim, doc)`); `getActiveDocument()` used during load.
- **C_USR (user-preferences)** — `OptionsManager` read/write for `circuitRecovery`.
- **[C_EDI](./canvas-editor.concept.md)** — all mutation paths wrap with `pushUndo`; `doUndo/doRedo/doRecover` proxied from editor.

### 4.2. API Surface  {#C_UND_04_02}

- `clearStacks()` — wipe both stacks (new circuit, import).
- `resetAndSeedFromCurrentCircuit()` — clear + push current.
- `pushUndo()` — dedup + dump + push; clears redo.
- `doUndo()`, `doRedo()` — swap current with top of target stack.
- `writeRecoveryToStorage()`, `readRecovery()`, `doRecover()` — recovery slot.
- Inner `UndoItem(dump, scale, tx, ty)` — immutable snapshot record.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
