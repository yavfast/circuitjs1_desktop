---
skill: undo-snapshot-model
domain: editor
topics: [undo, redo, snapshot, dedup, dump-index, agent-transaction, element-ids, open-marks]
source: onboard
updated: 2026-10-02
---

# Undo Snapshot Model

## Context

Undo in circuitjs1 is **full-snapshot, text-dump based** — not the
usual command/inverse-command pattern. Each `pushUndo()` serialises the
entire circuit of the manager's own document through the default (text)
exporter and pushes it onto a stack. This has pleasant invariants
(nothing to implement per edit) and unpleasant scaling (O(dump-size)
memory per edit). The same stack carries the document's agent
transaction (SP_AGA_04_01). Concept/spec: C_UND, SP_UND.

## Key concepts

**Undo record.** `UndoManager.UndoItem` holds `dump`, `viewTransform`
(`{scale, tx, ty}` of the snapshot document's own view: the renderer
transform while it is bound, its saved UI-state transform otherwise;
scale 0 = no view, not restored), `elementIds`, `openMarks`, the element
endpoints (aligned with `elementIds`) and the label fields `comment`,
`checkpointId`, `auto` (SP_AGA_01_10). The content fields are final; the
label fields are set at seal time, and the entry an undo/redo pushes
copies them from the popped entry. `elementIds` are the IDs of the
dumped elements in dump order (`CircuitDocument.getDumpedElementIds()`).
Equality for dedup is `UndoItem.sameContent` (dump, IDs and open marks).

**Own document.** `getActiveDocument()` of the delegate returns the
manager's own `circuitDocument`, never the session's visible tab, so
capture and restore address that document also in the background.
Agent operations on a non-visible document run inside `DocumentScope`,
which binds it silently so the options line and view are its own.

**Stacks:**
- `Vector<UndoItem> undoStack` — the history.
- `Vector<UndoItem> redoStack` — cleared on every new push and when an
  agent transaction opens.
- Both capped at `MAX_UNDO_DEPTH = 150`; the oldest entries are dropped.
- *(The `recovery` slot / "Recover Auto-Save" was removed 2026-10-01,
  BL-C01; `CirSim` deletes a leftover `circuitRecovery` key at startup.)*

**Push rule** (`pushUndo`):
1. Return when the document is *agent origin* (an editor path reused
   inside an agent mutation; the transaction holds the entry).
2. Cancel an agent run of the document; resolve a pending tentative
   gesture push.
3. Capture the new `UndoItem`; seal an open agent transaction.
4. Drop the newest entry when it is a sealed agent entry
   (`checkpointId`) equal to the new state (no-net transaction), whoever
   sealed it.
5. Clear the redo stack.
6. **Dedup**: skip when the new item `sameContent` the newest entry.
7. Push and trim to 150.

**Gestures.** `CircuitEditor.onMouseDown` calls `pushUndoForGesture()`:
without an open transaction this is `pushUndo`; with one, the pre-gesture
state is held as *tentative*. `onMouseUp` calls `endGesture()`, which
resolves it: dropped when nothing changed (the transaction stays open),
otherwise pushed by the push rule (sealing first). Agent mutations and
history calls run inside `splitGesture`: while the mouse button is held
the tentative is resolved before and re-armed with the post-operation
state after, so the rest of a drag never joins the agent's entry (no
re-arm when no transaction is open, the state is unchanged and a user
entry is on top).

**Agent transaction.** State lives on `UndoManager`
(`isTransactionOpen`, `getPendingEdits`); its entry is always the newest
undo entry.
- `noteAgentMutation(pre)` opens it — reusing the newest entry when it
  equals `pre` and carries no comment (e.g. the seed after a load),
  else pushing `pre` — and clears redo; further mutations only count.
- `seal(comment, auto)` / `sealTransaction()` (auto comment
  `"agent edits (auto)"`) label the entry with `cp<n>`. Seal triggers:
  user push, `doUndo`/`doRedo`, `resetAndSeedFromCurrentCircuit`,
  `ImportLifecycle.resetCircuitState` (user content replacement),
  `CirSim.clearCircuit`, ActionManager save/saveas, the idle `Timer`
  (300 s, `CircuitJS1Agent.debugSetIdleSealMs`), agent undo/restore/save.
- `dropTransactionEntry()` — a `noChanges` checkpoint removes the entry
  and closes the transaction without a checkpoint ID.
- `discardTransaction()` — `CircuitDocument.dispose()` on close; also
  clears the tentative push and gesture flag.

**Pop rule** (`doUndo`/`doRedo`):
1. Cancel an agent run, resolve the tentative push, seal.
2. `undo(1)`/`redo(1)`: each popped entry E goes to the other stack as
   the state being left plus E's labels; only the final state is loaded.
3. When the load throws, both stacks and the document are restored and
   the exception is rethrown (a failing rollback reload is logged and
   attached as suppressed).

**Load rule** (`loadUndoItem`):
- Brackets `circuitLoader.readCircuit(dump, RC_NO_CENTER)` with
  `beginElementIdRestore(elementIds)` / `endElementIdRestore()`: the
  import keeps the ID counters and gives element i `elementIds[i]`; a
  count mismatch regenerates the IDs in order and the `ids_regenerated`
  warning is returned.
- Restores `openMarks`, re-applies every endpoint the text reload
  rewrote (an axis-aligned transformer's text constructor synthesizes
  the diagonal corner; only when count and IDs match one to one), then
  `viewTransform` (skipped when its scale is 0).

**Other entry points.** `captureState()` returns an unpushed snapshot
(agent pre-call state); `restoreState(item)` is `loadUndoItem` without
touching the stacks (agent rollback). `getUndoEntry(p)`,
`getRedoEntry(p)`, `findCheckpoint(id)` read by position (0 = next to
apply). `getUndoComment()`/`getRedoComment()` drive the menu labels
`Undo: <comment>` / `Redo: <comment>` (`MenuManager.updateUndoRedoLabels`
via `enableUndoRedo`).

**Who calls `pushUndo`.** Every mutating edit path snapshots before the
mutation:
- `CircuitEditor.onMouseDown` (`pushUndoForGesture`) and `onMouseUp`
  (`pushUndo` when an element was placed or a held switch released,
  then `endGesture`).
- `doDelete`, `doPaste`, `prepareFlip`, `doEditOptions`,
  `doEditElementOptions`, `doSliders`; `ScrollValuePopup`, the Dropbox
  import paths, `ExportAsTextDialog`.
- `ActionManager` menu actions: `centrecircuit`, `flipx/y/xy`, `setup`
  (load preset), `newblankcircuit`, `importfromlocalfile`.
- `UndoManager.resetAndSeedFromCurrentCircuit` — after open/new
  document (`LoadFile`, `DocumentManager`, agent `FileOps`): seals,
  drops the tentative push, clears both stacks and seeds a baseline.

**Undo rebuilds every element.** `loadUndoItem` re-runs the text
constructors, so any state the text dump does not carry is lost on every
undo (e.g. a chip's `lastClock` — clocked chips therefore call
`ChipElm.skipExecuteAfterLoad(clockPin)` to prime the edge detector), and
any index a dump line stores must be an index among *dumped* lines
(`CircuitSimulator.locateElmForDump`), not `elmList.indexOf`.

## Usage in this project

- **Text format is the undo vehicle.** `UndoItem` exports through
  `CircuitFormatRegistry.getDefault().createExporter()` on its own
  document (`DEFAULT_FORMAT_ID = "text"`). JSON dumps would be too
  verbose for the high-frequency snapshot case.
- `RC_NO_CENTER` import flag exists **specifically for undo** — it
  keeps the viewport stable during load; the explicit transform
  restore then sets pan/zoom to the snapshotted values.
- `UndoManager` extends `BaseCirSimDelegate` with its own document, so
  undo is document-scoped (per tab) in every method, not only in
  `loadUndoItem`. Call `captureState()` while the document is bound
  (directly or via `DocumentScope`) so the options line is its own.

## Pitfalls

1. **Undo scales with circuit size, not operation count.** A 1000-
   element circuit dumps ~30-80 kB per edit; 100 edits = 3-8 MB of
   live memory. No incremental diff. For long editing sessions this
   is the #1 memory drain; the 150-entry cap bounds it.
2. **Some gestures push twice.** `mouseDown` always snapshots; `mouseUp`
   snapshots again only when the gesture placed an element or released
   a held switch (`circuitChanged`). A plain drag-move, select click or
   pan pushes once (deduped when the newest entry already equals the
   state); while an agent transaction is open a press that changes
   nothing pushes nothing.
3. **Undo only reverses what the text exporter round-trips.** Simulator
   internal vectors, scope cursor positions, transient state not in the
   dump are reset by undo. This is by design but surprises users who
   expect a "true" undo. Element IDs, open marks and rewritten
   endpoints are the exceptions the `UndoItem` carries itself.
4. **Full-snapshot undo defeats simulator warm-start.** Loading a
   snapshot rebuilds the entire netlist, re-allocates matrices, re-runs
   `analyzeCircuit`. There is no matrix/factorization reuse.
5. **Background documents.** The manager always addresses its own
   document, so a background restore is supported; but the text export
   and reload read session widgets (options line, renderer transform),
   so run capture/restore of a non-visible document inside
   `DocumentScope`, as the agent history calls do.
6. *(Removed 2026-10-01: the single-value recovery slot no longer exists.)*
7. **Clipboard paste uses `RC_RETAIN`** (keeps existing elements) with
   a `pushUndo` before. Paste cannot be undone in one step if the
   paste triggers element collisions that the importer silently
   resolves — validate paste outcomes before relying on single-step
   undo.
8. **`resetAndSeedFromCurrentCircuit`** is called after load so Undo
   does not rewind to the blank-editor state. If you add a new load
   path, remember to seed (it also seals an open agent transaction).
9. **Do not push from agent code.** Agent mutations go through
   `noteAgentMutation`; editor paths reused inside one are suppressed by
   agent origin. A user push never merges into an agent entry — it
   seals first.

## References

- `.dev_flow/onboard/analysis/layer3__editor-interaction.md` §5
  "Command / Undo Pattern", §11 "Commands-undo"
- `docs/commands-undo.concept.md` (C_UND), `docs/commands-undo.sp.md`
  (SP_UND), `docs/agent-api.sp.md` §01_10, §03_02, §04_01
- `src/main/java/com/lushprojects/circuitjs1/client/UndoManager.java`
  (`UndoItem`, `pushUndo`, `pushUndoForGesture`, `splitGesture`,
  `move`, `loadUndoItem`, agent transaction section)
- `src/main/java/com/lushprojects/circuitjs1/client/CircuitEditor.java`
  (`onMouseDown`, `onMouseUp`, `pushUndo` callers)
- `src/main/java/com/lushprojects/circuitjs1/client/ActionManager.java`
  (`menuPerformed` actions), `MenuManager.updateUndoRedoLabels`
- Rules: RULE_ARCH_004 (uses `CircuitFormatRegistry` for the dump),
  RULE_STRUCT_006 (document-scoped manager)
- Sibling skill: `io/text-format.md`
