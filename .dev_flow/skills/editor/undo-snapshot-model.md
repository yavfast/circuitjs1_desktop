---
skill: undo-snapshot-model
domain: editor
topics: [undo, redo, snapshot, dedup, dump-index]
source: onboard
updated: 2026-10-01
---

# Undo Snapshot Model

## Context

Undo in circuitjs1 is **full-snapshot, text-dump based** — not the
usual command/inverse-command pattern. Each `pushUndo()` serialises the
entire circuit through the default text exporter and pushes the string
onto a stack. This has pleasant invariants (nothing to implement per
edit) and unpleasant scaling (O(dump-size) memory per edit).

## Key concepts

**Undo record.** `UndoManager.UndoItem` holds
`(dump: String, scale: double, tx: double, ty: double, elementIds: String[])`.
The transform trio preserves pan/zoom across undo — otherwise undo-loading
the dump would recenter the viewport. `elementIds` (SP_AGA_01_10, PL_AGA
Phase 2) are the IDs of the dumped elements in dump order
(`CircuitDocument.getDumpedElementIds()`); `loadUndoItem` brackets the
import with `beginElementIdRestore/endElementIdRestore`, so the import does
not reset ID counters and gives element i `elementIds[i]`. Consecutive-equal
dedup compares dump *and* IDs (`UndoItem.sameContent`). Later phases add
`openMarks`, a per-document `viewTransform` and checkpoint label fields.

**Stacks** (`UndoManager.java:28-32`):
- `Vector<UndoItem> undoStack` — the history.
- `Vector<UndoItem> redoStack` — cleared on every new `pushUndo`.
- *(The `recovery` slot / "Recover Auto-Save" was removed 2026-10-01,
  BL-C01; `CirSim` deletes a leftover `circuitRecovery` key at startup.)*

**Push rule** (`pushUndo`, L56):
1. Drop the redo stack.
2. Dump the current circuit via `actionManager().dumpCircuit()` (default
   format = text).
3. **Consecutive-equal dedup**: if the new dump equals the last
   `undoStack.peek().dump`, skip the push.
4. Push new `UndoItem` capturing current `renderer.transform[0/4/5]`.

**Pop rule** (`doUndo`, L64; `doRedo`, L72):
1. Push current state onto the opposite stack (so redo is always
   available after an undo).
2. Pop the target stack.
3. `loadUndoItem(item)`.

**Load rule** (`loadUndoItem`, L80):
- Calls `circuitLoader.readCircuit(dump, RC_NO_CENTER)` (so import does
  not recenter).
- Restores `transform[0]=scale, [4]=tx, [5]=ty` explicitly.

**Who calls `pushUndo`.** Every mutating edit path snapshots before the
mutation:
- `CircuitEditor.onMouseDown` (L681) — before drag-create/drag-move.
- `doDelete` (L1037), `doPaste` (L1117), `prepareFlip` (L940),
  `doEditOptions` (L1241), `doEditElementOptions` (L1247), `doSliders`
  (L1253).
- `ActionManager` menu actions: `centrecircuit`, `flipx/y/xy`, `setup`
  (load preset), `newblankcircuit`, `importfromlocalfile` (L210, 344-466
  region).
- `UndoManager.resetAndSeedFromCurrentCircuit` (L43) — after open,
  seeds a baseline so Undo does not jump to pre-load state.

**Undo rebuilds every element.** `loadUndoItem` re-runs the text
constructors, so any state the text dump does not carry is lost on every
undo (e.g. a chip's `lastClock` — clocked chips therefore call
`ChipElm.skipExecuteAfterLoad(clockPin)` to prime the edge detector), and
any index a dump line stores must be an index among *dumped* lines
(`CircuitSimulator.locateElmForDump`), not `elmList.indexOf`.

## Usage in this project

- **Text format is the undo vehicle.** `pushUndo` runs through
  `dumpCircuit()` (default-format lookup via
  `CircuitFormatRegistry.DEFAULT_FORMAT_ID = "text"`). JSON dumps would
  be too verbose for the high-frequency snapshot case.
- `RC_NO_CENTER` import flag exists **specifically for undo** — it
  keeps the viewport stable during load; the explicit transform
  restore then sets pan/zoom to the snapshotted values.
- `UndoManager` extends `BaseCirSimDelegate`; it resolves the active
  document via `getActiveDocument()` inside `loadUndoItem` only — the
  rest of its state is per-instance. This means undo is effectively
  document-scoped (per tab), but resolves through "active" (tab
  switches must be thought through — see Pitfalls).

## Pitfalls

1. **Undo scales with circuit size, not operation count.** A 1000-
   element circuit dumps ~30-80 kB per edit; 100 edits = 3-8 MB of
   live memory. No incremental diff. For long editing sessions this
   is the #1 memory drain.
2. **One user action can push twice.** `mouseDown` snapshots, and then
   `mouseUp` on a successful change snapshots again via the change-
   propagation path. A single drag-move produces two undo entries.
   Consecutive-equal dedup only helps when the intermediate state is
   literally identical to the pre-edit state.
3. **Undo only reverses what the text exporter round-trips.** Simulator
   internal vectors, scope cursor positions, transient state not in the
   dump are reset by undo. This is by design but surprises users who
   expect a "true" undo.
4. **Full-snapshot undo defeats simulator warm-start.** Loading a
   snapshot rebuilds the entire netlist, re-allocates matrices, re-runs
   `analyzeCircuit`. There is no matrix/factorization reuse.
5. **Tab switching and undo.** `UndoManager.loadUndoItem` resolves
   `getActiveDocument()` at call time — correct only while *this*
   `UndoManager` belongs to the currently-active document. Do not call
   `loadUndoItem` from a background tab context.
6. *(Removed 2026-10-01: the single-value recovery slot no longer exists.)*
7. **Clipboard paste uses `RC_RETAIN`** (keeps existing elements) with
   a `pushUndo` before. Paste cannot be undone in one step if the
   paste triggers element collisions that the importer silently
   resolves — validate paste outcomes before relying on single-step
   undo.
8. **`resetAndSeedFromCurrentCircuit`** is called after load (L43) so
   Undo does not rewind to the blank-editor state. If you add a new
   load path, remember to seed.

## References

- `.dev_flow/onboard/analysis/layer3__editor-interaction.md` §5
  "Command / Undo Pattern", §11 "Commands-undo"
- `src/main/java/com/lushprojects/circuitjs1/client/UndoManager.java`
  L28, L33, L43, L56, L64, L72, L80, L88, L94
- `src/main/java/com/lushprojects/circuitjs1/client/CircuitEditor.java`
  L681, L999, L1005, L1011, L1017, L1036, L1109, L1117
- `src/main/java/com/lushprojects/circuitjs1/client/ActionManager.java`
  L210, L344-466
- Rules: RULE_ARCH_004 (uses `CircuitFormatRegistry` for the dump),
  RULE_STRUCT_006 (document-scoped manager)
- Sibling skill: `io/text-format.md`
