# Layer 3 — Editor Interaction Sub-Unit

Scope: 11 files under `src/main/java/com/lushprojects/circuitjs1/client/` that implement the *interactive editor* shell: input routing, mode machine, selection, rendering, undo, menus/actions, toolbar, clipboard. This is the mutable-edit counterpart of the immutable `element/*` and `io/*` layers already analysed in `domain-core__element-base.md`, `util.md`, `root-utils.md`.

All files live in the base package (not `.element`, `.io`, `.dialog`). Delegates extend `BaseCirSimDelegate` so they share one `BaseCirSim cirSim` back-pointer and reach each collaborator via helper accessors (`renderer()`, `circuitEditor()`, `simulator()`, `undoManager()`, `menuManager()`, `scopeManager()`, `dialogManager()`, `actionManager()`, `circuitInfo()`, `getActiveDocument()`).

---

## 1. Purpose

The sub-unit realises the **Canvas-editor / Commands-undo / Menus-actions / Clipboard** concepts (see §11). It:

- Converts raw GWT `Mouse*/Click/ContextMenu/KeyPress` events into high-level editor operations.
- Manages a per-document `MouseMode` state machine driving selection, element creation, dragging and splitter manipulation.
- Paints the circuit canvas every frame (render pipeline, performance counters, info box, scopes, crosshair).
- Persists edit history via a full-snapshot undo/redo stack that round-trips through the text dump importer/exporter.
- Wires menu bar, popup menus, keyboard shortcuts and toolbar buttons into one `menuPerformed(menu, item)` dispatcher.
- Bridges JS `navigator.clipboard` to the circuit-text copy/paste flow.

---

## 2. Per-file Key Entities

### 2.1 `MouseMode.java` (12 lines)

Enum of 8 states: `ADD_ELM`, `DRAG_ALL`, `DRAG_ROW`, `DRAG_COLUMN`, `DRAG_SELECTED`, `DRAG_POST`, `SELECT`, `DRAG_SPLITTER`. No methods — used as discriminator across editor/renderer.

### 2.2 `CircuitEditor.java` (1256 lines, extends `BaseCirSimDelegate`, implements 8 GWT event-handler interfaces)

State (L40–104):
- `MouseMode mouseMode` (committed mode, default `SELECT`) + `MouseMode tempMouseMode` (transient mode mutated by modifier keys on mousedown, L699–716).
- `String mouseModeStr` — label used by renderer (`drawMouseMode`) and Toolbar highlight.
- Drag state: `dragGridX/Y`, `dragScreenX/Y`, `initDragGridX/Y`, `boolean dragging`, `mouseDragging`, `mouseDownTime`, `zoomTime`.
- Hover/selection refs: `CircuitElm mouseElm`, `mouseElmRef` (hover highlight, previously static on `CircuitElm` — explicitly localised here for multi-document isolation, comment L62–64), `menuElm` (right-click target), `dragElm` (mid-creation element), `plotXElm/plotYElm`, `draggingPost`, `mousePost`.
- Grid/selection: `int gridSize/gridMask/gridRound`, `Rectangle selectedArea`.
- Misc: `Canvas window`, `SwitchElm heldSwitchElm` (momentary switch tracking), `boolean didSwitch`, `ScrollValuePopup scrollValuePopup` (L851), `double wheelSensitivity`.

Constants (L36–38): `POST_GRAB_SQ=25`, `MIN_POST_GRAB_SIZE=256`, `DRAG_DELAY=150`.

Constructor (L105): takes `BaseCirSim` + `CircuitDocument`; caches `window = renderer().getCanvas()`.

Key methods (catalog):
- Mode: `setMouseMode(String)` L114 (text→enum mapping, toggles cursor class), `setMouseMode(MouseMode)` L145, `setCursorStyle` L164.
- Event entry points: `onMouseDown` L629, `onMouseMove` L471, `onMouseUp` L742, `onMouseWheel` L796, `onMouseOut` L823, `onClick` L828, `onContextMenu` L835, `onDoubleClick` L844.
- Dispatch helpers: `setTemporaryMouseMode` L699 (modifier-key → transient mode), `handleScopeSettings` L718, `mouseSelect` L486, `mouseDragged` L175 (switch on `tempMouseMode`), `isRightMouseButton` L248.
- Drag operations: `dragSplitter` L264, `dragAll` L281, `dragRow` L293, `dragColumn` L311, `dragSelected` L329, `dragPost` L374, `selectArea` L410, `twoFingerTouch` L617.
- Hit-testing: `findElm` L515, `findElmInScope` L548, `findElmByPost` L564, `findPostOnElm` L598, `mouseIsOverSplitter` L454.
- Grid: `snapGrid` L864, `setGrid` L868.
- Selection: `clearSelection` L1211, `doSelectAll` L1220, `anySelectedButMouse` L1229, `setMenuSelection` L154, `onlyGraphicsElmsSelected` L893.
- Edits: `doSwitch` L874, `removeZeroLengthElements` L437, `doFlip` L907, `doSplit` L912, `flipX/Y/XY` L962/974/986 (all call `prepareFlip` L939 which pushes undo + computes center).
- Clipboard/undo delegation: `pushUndo` L999, `doUndo/doRedo/doRecover` L1005/1011/1017, `doCut/doCopy/doDuplicate` L1029/1093/1103, `copyOfSelectedElms` L1083, `doPaste` L1109, `doDelete(pushUndoFlag)` L1036.
- Paste layout helpers: `getBoundingBoxOfCircuit` L1162, `selectNewItems` L1177, `canMoveNewItems` L1193, `moveNewItems` L1203.
- Dialog launchers: `doEditOptions` L1239, `doEditElementOptions` L1245, `doSliders` L1251.

### 2.3 `CircuitEditorEventHandler.java` (77 lines)

Thin multiplex/indirection layer. Implements the same 8 GWT handler interfaces as `CircuitEditor` but each `onXxx()` method simply forwards to `cirSim.getActiveDocument().circuitEditor.onXxx(event)`. Registered once on the single shared Canvas so the document switcher can swap editors without re-registering DOM handlers (comment L21–24).

### 2.4 `CircuitRenderer.java` (770 lines, extends `BaseCirSimDelegate`)

Owns the `Canvas canvas`, `Context2d canvasContext`, plus `int canvasWidth/canvasHeight` and the 2D affine `double[] transform` (size 6; `[0]=[3]=scale`, `[4]=tx`, `[5]=ty`). Selected fields:

- Timing: `lastTimeMillis`, `lastFrameTimeMillis`, `lastSecondTimeMillis`, `frameCount`, `framesPerSecond`, `stepsPerSecond`, `needsAnalysis`, `needsRepaint` (coalescing flag, L174).
- Draw scaling: `currentMult`, `powerMult` (re-localised from ex-statics on `CircuitElm`, L34–45).
- `Font unitsFont` (lazy, L47).
- Layout: `Rectangle circuitArea`, `double scopeHeightFraction=0.2`.
- Hint panel: `int hintType/hintItem1/hintItem2` with public getters (L61–66).
- `PerfMonitor perfmon` (from `util/PerfMonitor`, see `util.md`).

Key methods:
- Lifecycle: constructor L73, `initCanvas` L89, `setCanvasSize` L99 (applies device pixel ratio), `checkCanvasSize` L115, `setCircuitArea` L121, `reset` L81, `needsAnalysis()` L77, `resetTimers` L190.
- View transform: `zoomCircuit(inc[, fromMenu])` L131/135, `setCircuitScale` L143 (keeps point-under-cursor stable), `transformX/Y` L166/170, `inverseTransformX/Y` L157/161, `centreCircuit` L625, `getCircuitBounds` L659.
- Frame driver: `repaint()` L178 (defers via `Scheduler.get().scheduleDeferred`), `render()` L198 (top-level paint).
- Render pipeline (called from `render`): `setupFrame` L246 (background color, printable/normal), `updateSimulationTimers` L260 (computes `currentMult`, `powerMult`, fps/sps), `drawCircuit` L289 (sets transform matrix scaled by DPR, calls sub-draws, then resets to screen coords for bottom area), `drawElements` L313 (iterates `simulator.elmList`, overlays post-dots and drag handles), `drawHandles` L365 (SELECT-mode element + dragElm handles), `drawBadConnections` L378, `drawSelectionAndCursor` L386 (rubber-band + crosshair), `drawDeveloperInfo` L405 (PerfMonitor output), `drawMouseMode` L425 (mode label + stop/warning messages), `drawBottomArea` L445, `drawInfoBox` L494.
- Export: `drawCircuitInContext` L683, `getCircuitAsCanvas(type)` L739, `getCircuitAsSVG` L757 (all save/restore `transform`).
- Hints: `getHint` L572 (LC/RC/3dB/TwinT formulae).

### 2.5 `MyCommand.java` (48 lines)

GWT `Command` implementation storing `(menuName, itemName)` pair. `execute()` calls `circuitjs1.mysim.actionManager.menuPerformed(menuName, itemName)` — a uniform bridge from any menu item / toolbar button into the action dispatcher. Mutable `setItemName` is used by Toolbar variant-button swapping (Toolbar L250).

### 2.6 `ActionManager.java` (615 lines, extends `BaseCirSimDelegate`)

Two responsibilities: **keyboard preview handler** and **menu-action dispatcher**.

- `onPreviewNativeEvent(Event.NativePreviewEvent)` L41: reads `cc` (char), `t` (type), `code` (keycode); guards `dialogIsShowing` (routes Escape/Enter to `ScrollValuePopup` or `Dialog`); handles `+/-/0//` zoom/search; Delete/Backspace → `doDelete` (or clears selected scope); Escape → `setMouseMode("Select")`; Ctrl/Meta combos for C/X/V/Z/Y/D/A/P/N/T/S/O; lowercase keypress → `menuManager().shortcuts[cc]` → `setMouseMode`.
- `menuPerformed(menu, item)` L188: giant `if/else` on `menu`+`item` strings. Branches:
  - file: newtab/newwindow/newblankcircuit/openlastclosedtab/save/saveas/import*/export*/copypng/exportassvg/createsubcircuit/dcanalysis/print/recover.
  - edit: undo/redo/cut/copy/paste/duplicate/flip/split/selectAll/centrecircuit/flipx/flipy/flipxy/search/delete/sliders.
  - scopes: stackAll/unstackAll/combineAll/separateAll + `scopepop` sub-menu (dock/undock/remove/removeplot/speed2/speed1/2/maxscale/stack/unstack/combine/selecty/reset/properties).
  - zoom: zoomin/zoomout/zoom100.
  - elm/key: promotes `key` → `elm` when `mouseElm != null` so keyboard shortcuts act as contextual commands. viewInScope / viewInFloatScope / addToScopeN handled here.
  - options: shortcuts/subcircuits/other/modsetup; main→`setMouseMode(item)`+`updateToolbar`; fullscreen toggle.
  - circuits: `setup <file> <name>` opens a new tab and loads a preset.
- Circuit dump/export helpers: `dumpCircuit(formatId)` L552 via `CircuitFormatRegistry`, `dumpCircuitWithState` L567, `dumpCircuit()` L581 (default), `dumpOptions()` L591 (serialises `$` header with menu-state flags — bits: dots/smallGrid/!volts/power/!showValues/autoTimeStep; plus maxTimeStep, iterCount, current, voltageRange, power, minTimeStep; uses `CircuitElm.dumpValues`). `importCircuitFromText(text, subcircuitsOnly)` L534. `doExportAsUrl/Text/Json/Image` L500–519, `doImageToClipboard` L519 (`CirSim.clipboardWriteImage`), `doCreateSubcircuit` L524, `doExportAsLocalFile` L528.

**Identity-string comparisons**: `==` is used on menu/item string constants (e.g. L194, L316); works because both sides are interned string literals — fragile if refactored.

### 2.7 `MenuManager.java` (997 lines, extends `BaseCirSimDelegate`)

Holds every `MenuItem` / `CheckboxMenuItem` / `MenuBar` used by the app + the class→label map + keyboard shortcut array.

Fields: `MenuBar menuBar, mainMenuBar, fileMenuBar, drawMenuBar, circuitsMenuBar, optionsMenuBar, elmMenuBar, selectScopeMenuBar` and `subcircuitMenuBar[]`; `MenuItem` handles for every command (L20–75); public `CheckboxMenuItem` toggles (`dotsCheckItem, voltsCheckItem, powerCheckItem, smallGridCheckItem, crossHairCheckItem, showValuesCheckItem, conductanceCheckItem, euroResistorCheckItem, euroGatesCheckItem, printableCheckItem, conventionCheckItem, noEditCheckItem, mouseWheelEditCheckItem, toolbarCheckItem, mouseModeCheckItem`) read throughout the renderer and editor; `ScopePopupMenu scopePopupMenu`; `PopupPanel contextPanel` (current context menu); `String[] shortcuts = new String[127]` (ASCII-indexed class-name lookup); `HashMap<String,String> classToLabelMap`; `Vector<CheckboxMenuItem> mainMenuItems` + parallel `Vector<String> mainMenuItemNames` used for toggling the "currently selected add-element" state.

Constructor L96 auto-detects Mac (`isMac`) and sets `ctrlMetaKey` prefix for shortcut labels, forwarded through `Locale.LS`.

Init methods: `initMainMenuBar` L109, `initFileMenuBar` L124, `initEditMenuBar` L198, `initDrawMenuBar` L232, `initScopesMenuBar` L239, `initOptionsMenuBar` L249, `initElmMenuBar` L660, `initCircuitsMenuBar` L709, `initHelpMenuBar` L716. Each adds items via `menuItemWithShortcut(icon, text, shortcut, MyCommand)` L772 or `iconMenuItem(icon, text, Command)` L781 which inject a `cirjsicon-*` CSS-class and pass through `Locale.LS` i18n.

Composition: `composeMainMenu(bar, num)` L367 (builds the big add-element tree used by both main bar and draw bar; `num` index controls per-variant classCheckItem instance); `composeSubcircuitMenu` L613; `composeSelectScopeMenu` L633.

Popup & check logic: `doPopupMenu()` L787 — decides whether to show scope-popup, element-popup, or main-popup based on current `scopeSelected` / `mouseElm`; updates per-item enable-state (can-flip, can-split, etc.); positions `contextPanel` clamped to canvas bounds; `doMainMenuChecks()` L924 syncs check-item state from `mouseModeStr` and enables/disables scope bulk ops; refreshes subcircuit menu when `CustomCompositeModel.sequenceNumber` changes.

Shortcuts: `clearShortcuts` L749, `setShortcut(className, code)` L760, `saveShortcuts`/`loadShortcuts` L957/971 round-trip via `OptionsManager`.

### 2.8 `Toolbar.java` (361 lines, extends `HorizontalPanel`)

Horizontal icon bar. Constructor L28 populates: file ops (new/open/save/save-as), edit ops (undo/redo/cut/copy/paste/duplicate), find, centre/zoom, all the common element-creation buttons (wire/resistor/ground/capacitor/inductor/diode, voltage/switch/opamp/transistor/fet/gate button-sets), reset, run/stop. Almost every button is built via `createIconButton(icon, tooltip, MyCommand)` L155 which:
- Adds CSS class `cirjsicon-*` or inline SVG.
- Attaches click handler that executes `command` *or* reverts to `Select` mode if clicked again (toggle behaviour, L186–189).
- Registers the button in `highlightableButtons` map keyed by `command.itemName` when the command's menu group is `"main"` — this enables `highlightButton(key)` L294 to highlight the active add-element mode.

`createButtonSet(String[] info)` L207 builds a palette of variants (shown on mouse-over) that re-binds the main button's command+icon to the chosen variant via `MyCommand.setItemName`. `setEuroResistors(boolean)` L310 swaps the resistor icon. `updateRunStopButton()` L107 flips play/stop class based on `cirSim.simIsRunning()`. SVG icon strings are embedded constants (L314 onwards).

### 2.9 `ClipboardManager.java` (220 lines)

Bridges circuit text to the browser clipboard via JSNI.

State: `String internalClipboard`, `boolean hasSystemClipboardSupport` (feature-detected at construction, L15 + `checkClipboardSupport` native L21).

Methods:
- `doCopy` / `doCut` L28/36 both call `circuitEditor.copyOfSelectedElms()` then `setClipboard`.
- `setClipboard(data)` L44: always stores internally, then either `writeToSystemClipboard` (native, L121) or `tryLegacyClipboardWrite` (textarea + `execCommand('copy')`, L151).
- `getClipboard()` L65 synchronous internal read; `readFromSystemClipboard(callback)` L72 async; `doPasteFromSystem()` L86 prefers internal if non-empty, else async-reads system clipboard and calls `circuitEditor.doPaste(data)` if `isCircuitData(data)` heuristic (`$`, `r/c/l/w ` prefixes, L182) passes.
- JSNI bodies use the GWT method-reference syntax `callback.@...::onSuccess(Ljava/lang/String;)(text)` to cross back into Java.
- `hasClipboardData` L114, `clearClipboard` L199, `getClipboardInfo` L209, `hasSystemClipboardSupport()` L217.

### 2.10 `ClipboardCallback.java` (18 lines)

Plain 2-method interface (`onSuccess(String)`, `onError(String)`) used as the JSNI async-result shim.

### 2.11 `UndoManager.java` (98 lines, extends `BaseCirSimDelegate`)

Full-snapshot, text-dump-based undo. Inner `UndoItem` captures the circuit dump plus the current zoom/pan (`transform[0]/[4]/[5]`).

Stacks: `Vector<UndoItem> undoStack`, `Vector<UndoItem> redoStack`, plus `String recovery` for crash auto-save.

- `clearStacks` L33, `resetAndSeedFromCurrentCircuit` L43 (reset after open — comment explains this prevents Undo jumping to pre-load state).
- `pushUndo()` L56: drops redo stack, dumps circuit via `actionManager().dumpCircuit()`, de-dupes against `undoStack.last()`, pushes new `UndoItem`.
- `doUndo` L64 / `doRedo` L72: move current state to the opposite stack, pop the target, call `loadUndoItem`.
- `loadUndoItem` L80: calls `circuitLoader.readCircuit(dump, RC_NO_CENTER)` then restores transform.
- `writeRecoveryToStorage` L88 / `readRecovery` L94: localStorage-based crash recovery through `OptionsManager` key `"circuitRecovery"`.

No incremental/inverse-command pattern — every edit just snapshots the entire circuit text. See §5.

---

## 3. Input Event Routing

```
GWT Canvas DOM event
     │
     ▼
CircuitEditorEventHandler.onXxx(event)         ← registered once on Canvas (per CirSim)
     │
     ▼
cirSim.getActiveDocument().circuitEditor.onXxx(event)    ← picks active tab
     │
     ├── preventDefault / focus canvas / cache menu coords
     ├── mouseDown: setTempMode(modifiers) → [splitter? | scope-settings? | switch toggle?
     │              | post-grab detection → DRAG_POST] → pushUndo → start dragElm
     ├── mouseMove (dragging): switch(tempMouseMode) { DRAG_ALL | DRAG_ROW | DRAG_COLUMN
     │              | DRAG_POST | SELECT→DRAG_SELECTED | DRAG_SELECTED | DRAG_SPLITTER }
     ├── mouseMove (hover): mouseSelect → findElm / findElmInScope / findElmByPost
     ├── mouseUp: finalise dragElm or held switch; if change → needAnalyze + pushUndo + setUnsaved
     ├── mouseWheel: scrollValues OR zoomCircuit OR element-specific (e.g. LogicInput)
     ├── contextMenu: stash menuX/Y → menuManager.doPopupMenu
     ├── doubleClick: doEditElementOptions(mouseElm)
     └── click / mouseOut: middle-button scrollValues / clear hover
```

Keyboard path is independent of the canvas handler tree: `ActionManager.onPreviewNativeEvent` is registered on GWT `Event.addNativePreviewHandler`, giving it application-wide priority. It filters by key code and either routes to `menuPerformed("key", <item>)` or directly to `circuitEditor.setMouseMode(...)` for shortcut-to-mode transitions.

Touch: there is one explicit `twoFingerTouch(x,y)` hook at L617 that forces `DRAG_ALL` (pan) — actual touch→mouse synthesis happens upstream in `CirSim`.

---

## 4. MouseMode State Machine

```
               modifier keys on mousedown (L699)
               ─────────────────────────────────
               plain LMB            →  tempMode = mouseMode
               Shift                →  SELECT
               Alt                  →  DRAG_ALL
               Ctrl/Meta            →  DRAG_POST
               Alt+Shift            →  DRAG_ROW
               Alt+Meta             →  DRAG_COLUMN
               RMB/Middle           →  DRAG_ALL
               (over splitter)      →  DRAG_SPLITTER   (forced, L649)
               (post handle close)  →  SELECT → DRAG_POST  (upgrade, L671)

      committed mode            transient mode
      ──────────────            ──────────────
      SELECT  ◄────── Escape / Space / "Select" toolbar / "Select" menu
      ADD_ELM (+mouseModeStr)  ←── shortcut key / toolbar button / main-menu class item
      DRAG_*  (via setMouseMode(String) L114, driven by menuPerformed("main", ...))
```

Committed `mouseMode` is sticky; `tempMouseMode` is reset to `mouseMode` on `mouseUp` (L754). During a `SELECT` drag with a hovered element, the move handler auto-promotes `tempMouseMode` to `DRAG_SELECTED` once `DRAG_DELAY (150 ms)` has elapsed (L223–227), so a click never triggers a spurious move. When in `ADD_ELM`, mousedown on the canvas constructs a new element via `CircuitElmCreator.constructElement(doc, mouseModeStr, x0, y0)` (L691); the dragged element is only committed to `simulator().elmList` on mouseUp (L772) unless creation failed.

Cursor classes: `cursorCross` for `ADD_ELM`, `cursorPointer` otherwise, `cursorSplitter` while over the scope splitter (L454–469).

`noEditCheckItem` (read-only lock) forces `tempMouseMode = SELECT` regardless of choice (L656–658) and short-circuits keyboard shortcuts (L90) and popup menus (L792).

---

## 5. Command / Undo Pattern

The project uses **two unrelated meanings of "command"**, which should not be confused:

1. **`MyCommand` (menu/toolbar level)** — a GWT `Command` token `(menuName, itemName)` whose `execute()` jumps into `ActionManager.menuPerformed`. It is *not* an undoable command; it is the user-intent entry point.
2. **Undo record** — `UndoManager.UndoItem` holding `(dump, scale, tx, ty)`.

### Full-snapshot vs. incremental — this codebase uses full-snapshot.

Every mutating edit path boils down to the same idiom:

```
circuitEditor.pushUndo()            // dump whole circuit → undoStack, clear redo
...perform mutation on simulator().elmList / element fields...
cirSim.needAnalyze()                // re-run topology after next frame
cirSim.setUnsavedChanges(true)
undoManager().writeRecoveryToStorage()   // optional for destructive ops
```

`UndoManager.pushUndo` (L56) calls `actionManager().dumpCircuit()` which writes the entire circuit (via the active `CircuitFormat` exporter). Consequences:

- **No command objects, no inverse operations, no merging.** Any state that the exporter round-trips is reversible; any state it does not (e.g. simulator internal vectors, scope cursor position) is reset on undo.
- Undo is idempotent against trivial moves: `pushUndo` de-dupes identical consecutive dumps (L59–60).
- `doUndo` / `doRedo` push the current state onto the opposite stack before popping the target, so redo is always available after an undo until the next edit.
- `loadUndoItem` invokes `circuitLoader.readCircuit(dump, RC_NO_CENTER)` — `RC_NO_CENTER` preserves pan/zoom but the transform is then explicitly restored from the snapshot (L82–85).
- `recovery` is a separate slot (not stack) persisted to `localStorage` via `OptionsManager` key `circuitRecovery`, loaded on boot into `undoManager().recovery`. "Recover Auto-Save" menu item (`recoverItem`, MenuManager L173) calls `circuitEditor.doRecover()` which pushes undo and loads the recovery blob.
- There is NO incremental / diff-based undo. For long circuits this is O(dump-size) per edit; mitigated only by `pushUndo`'s consecutive-equal de-dup.

Callers of `pushUndo`: onMouseDown (L681), doDelete (L1037), doPaste (L1117), prepareFlip (L940), doEditOptions (L1241), doEditElementOptions (L1247), doSliders (L1253), doRecover (L1018), menu actions centrecircuit/flipx/flipy/flipxy/setup/newblankcircuit/importfromlocalfile (ActionManager L210/344/348/352/356/457/466), UndoManager.resetAndSeedFromCurrentCircuit (L43).

---

## 6. Render Pipeline

```
CircuitRenderer.repaint()                 — coalescing flag + Scheduler.scheduleDeferred
     └─► render()  (L198)
          perfmon.startContext("render()")
          checkCanvasSize                  — re-init coord space on DPR mismatch
          scopeManager().setupScopes()
          new Graphics(canvasContext)
          setupFrame                       — fill canvas with bg colour (printable or black)
          updateSimulationTimers           — derive currentMult, powerMult, fps, sps
          perfmon.startContext("graphics")
          drawCircuit
              setTransform with devicePixelRatio * user scale / translate
              drawElements                 — elmList.forEach(ce.draw); post-dots; drag handles
              drawHandles                  — SELECT-mode highlight + dragElm preview
              drawBadConnections
              drawSelectionAndCursor       — rubber-band selectedArea + crosshair
              setTransform back to screen space
              drawBottomArea
                  scope layout & draw
                  drawInfoBox              — element info / time step / hint / bad-conn
          perfmon.stopContext("graphics")
          drawDeveloperInfo (if developerMode)   — fps/sps/iterCount/PerfMonitor tree
          drawMouseMode (if mouseModeCheckItem)  — "Mode: X"  + stop/warning message
          cirSim.callUpdateHook()
```

Coordinate transform: `double[] transform` acts as a 2×3 affine (stored as `[a,b,c,d,tx,ty]` → canvas2d `setTransform`). User-visible zoom is applied via `transform[0]=transform[3]=scale`; panning via `transform[4]=tx`, `transform[5]=ty`. The device pixel ratio is folded in *only* inside `drawCircuit` to keep logical coordinates uniform elsewhere. `setCircuitScale` L143 re-anchors the transform so the point under the mouse (or centre when called from menu) remains stationary during zoom.

`PerfMonitor perfmon` (from `util/`) produces the developer-mode overlay (see `util.md`). Context pushes/pops around `render()` and `graphics`/`elm.draw()` sub-phases.

Export paths (`drawCircuitInContext` / `getCircuitAsCanvas` / `getCircuitAsSVG`) save and restore `transform` and mutate the `printable`/`dots` check-items during the paint, then revert — this is a minor *shared-state* wart (see §10).

---

## 7. Clipboard Flow

```
Copy/Cut (menuPerformed "copy"/"cut" or Ctrl+C/X keypress)
     │
     ▼
CircuitEditor.doCopy/doCut
     ├── setMenuSelection (ensure right-click element is in selection)
     ├── clipboardManager.doCopy/doCut
     │      └── cirSim.getActiveDocument().circuitEditor.copyOfSelectedElms()
     │              = actionManager().dumpOptions() + simulator().dumpSelectedItems()
     │          └── setClipboard(text)
     │                 ├── internalClipboard = text      ← sync fallback
     │                 └── navigator.clipboard.writeText(text)   ← JSNI async
     │                         |   (or) textarea + execCommand('copy')
     │
     └── cirSim.enablePaste() / doCut also calls doDelete(true)

Paste (menuPerformed "paste" or Ctrl+V)
     │
     ▼
clipboardManager.doPasteFromSystem
     ├── if internalClipboard non-empty → circuitEditor.doPaste(internal)
     └── else navigator.clipboard.readText (ClipboardCallback)
           onSuccess → isCircuitData heuristic → doPaste
           onError   → console log
     │
     ▼
CircuitEditor.doPaste(dump)
     pushUndo → clearSelection → readCircuit(dump, RC_RETAIN[|RC_NO_CENTER])
     → selectNewItems → layout delta around old bbox or cursor position
     → moveNewItems → needAnalyze → writeRecoveryToStorage → setUnsavedChanges
```

The same flow (minus the async read) is reused by `doDuplicate` (L1103), which short-circuits through the internal buffer.

`doImageToClipboard()` in ActionManager L519 is a separate path: it rasterises the circuit via `getCircuitAsCanvas(CAC_IMAGE)` and calls `CirSim.clipboardWriteImage(canvasElement)` (native JS).

---

## 8. Action Wiring

One funnel: **every user command — menu, submenu, popup, toolbar, keyboard — flows through `ActionManager.menuPerformed(menu, item)`**.

```
MenuBar / MenuItem  ── setCommand(new MyCommand(menu, item))  ──┐
Toolbar button      ── new MyCommand(menu, item)            ────┤
Toolbar runStop     ── direct ClickHandler (bypasses funnel) ──┐│
Keypress shortcut   ── menuPerformed("key"/"edit"/...)      ───┤│
scopepop sub-items  ── MyCommand("scopepop", ...)           ───┤│
                                                               ▼▼
                                                       ActionManager.menuPerformed
                                                         │
                                                         ├── string-dispatch if/else tree
                                                         ├── delegates to circuitEditor,
                                                         │   renderer, scopeManager,
                                                         │   dialogManager, documentManager,
                                                         │   clipboardManager
                                                         └── cirSim.repaint() at end
```

`menu` values: `file`, `edit`, `main` (add-element), `options`, `scopes`, `scopepop`, `elm` (context on an element), `key` (keyboard shortcut, promoted to `elm` when an element is hovered L316), `circuits`, `view`, `zoom`. `item` values: string slugs matching constants throughout `MenuManager`/`Toolbar`.

The funnel makes the action surface trivially introspectable and supports the "shortcut or menu acts the same" model, but at the cost of a 300-line `if` chain that uses `==` on string literals.

`MyCommand.setItemName` (L45) is the one mutation point — used by Toolbar variant-button palettes (Toolbar L250) to re-bind the top-level button without rebuilding the click handler.

---

## 9. Public Contracts (cross-layer)

| Consumer | Method on editor-interaction | Reason |
| --- | --- | --- |
| CirSim shell | `CircuitEditor` ctor, `onXxx` delegation via `CircuitEditorEventHandler`, `setMouseMode`, `pushUndo`, `doUndo/Redo`, `doCut/Copy/Paste/Delete`, `doEditElementOptions`, `doSliders`, `clearSelection`, `doSelectAll`, `setGrid`, `setWheelSensitivity`, `snapGrid`, `doRecover`, `copyOfSelectedElms`, `clearMouseElm` | main shell glue |
| CircuitDocument | `CircuitRenderer`, `CircuitEditor`, `UndoManager` constructors; `renderer.initCanvas`, `renderer.reset`, `renderer.repaint`, `renderer.needsAnalysis`, `renderer.setCircuitArea`, `renderer.centreCircuit`, `undoManager.resetAndSeedFromCurrentCircuit`, `undoManager.clearStacks` | per-tab lifecycle |
| io/CircuitLoader | `actionManager.importCircuitFromText`, `renderer.transform` (persisted via UndoItem), `renderer.getHintType/Item1/Item2` (round-tripped in `$` header) | load/save hooks |
| dialog/* | `circuitEditor.doEditElementOptions`, `circuitEditor.doSliders`, `actionManager.dumpCircuit[WithState](formatId)`, `actionManager.importCircuitFromText`, `menuManager.contextPanel.hide`, all `CheckboxMenuItem` getters in `MenuManager` | options dialog, import/export dialogs |
| element/CircuitElm and subclasses | `renderer.getCurrentMult/getPowerMult/getUnitsFont`, `renderer.transformX/transformY`, `renderer.inverseTransformX/Y` (via CirSim forwarders), `circuitEditor.getMouseElmRef/setMouseElmRef/clearMouseElmRef` (hover-highlight ref that used to be static) | drawing & hover state |
| io formats | `actionManager.dumpOptions()` provides the `$` header | default text format |
| scope layer | `renderer.scopeHeightFraction`, `circuitEditor.mouseCursorX/Y`, `menuManager.scopePopupMenu`, `menuManager.menuPlot`, `menuManager.composeSelectScopeMenu`, `ScopeManager.menuScope` | scope menus + cursor readout |
| native JS | `clipboardManager.*` JSNI, `CirSim.clipboardWriteImage` (called from ActionManager) | OS integration |

Internal-visibility of most mutators is package-default (`void doXxx()`), reflecting a single-package design; `public` is used only for explicit cross-package consumers (dialog, element, io, util).

---

## 10. Integration Points

- **BaseCirSim / BaseCirSimDelegate** — shared back-pointer + per-aspect accessors. All nine delegates in this layer extend it.
- **CircuitDocument** owns per-tab `circuitEditor`, `circuitRenderer` (constructed in pairs), `undoManager`, `adjustableManager`, `circuitLoader`, `circuitInfo`.
- **DocumentManager** swaps the active document; `CircuitEditorEventHandler` re-reads it on every mouse event (not cached), so tab switches are transparent to DOM wiring.
- **CircuitSimulator.elmList** — the mutable source of truth this layer edits.
- **CircuitLoader / CircuitExporter / CircuitFormatRegistry** (see `io-framework.md`) — invoked from `UndoManager.loadUndoItem`, `ActionManager.dumpCircuit`, `importCircuitFromText`.
- **OptionsManager** — storage for shortcuts (`MenuManager.save/loadShortcuts`), `circuitRecovery` blob (`UndoManager`), `wheelSensitivity` (`CircuitEditor`).
- **Locale.LS** (from `util`) — i18n for every label the user sees (menu items, tooltips, info lines, mode label).
- **PerfMonitor** — wraps render phases (see `util.md`).
- **ColorSettings** — printable-mode + selection/voltage/background colours; mutated temporarily by export paths.
- **Scope / ScopeManager / ScopePopupMenu** — scope hit-testing in `findElmInScope`, scope drawing in `drawBottomArea`, scope popup composed in `MenuManager.doPopupMenu`.
- **DialogManager / Dialog / ScrollValuePopup** — targets for Enter/Escape forwarding in `ActionManager.onPreviewNativeEvent` and for all "Edit …" menu items.
- **CirSim** static JSNI methods used from here: `clipboardWriteImage`, `executeJS`, `nodeSave`, `nodeSaveAs`, `toggleDevTools`, `changeWindowTitle`, `createSVGContext`, `getSerializedSVG`, `devicePixelRatio`, `console`, `debugger`.

---

## 11. Concept Boundaries

Four cleanly separable concepts are tangled in this sub-unit. Good candidates if the codebase is ever refactored:

### Canvas-editor (viewport + direct manipulation)
- Files: `CircuitEditor`, `CircuitEditorEventHandler`, `CircuitRenderer`, `MouseMode`.
- Owns: canvas, transform, mouse/keyboard drag state, hit testing, selection set (selected flag on each `CircuitElm`, not a separate collection), per-frame paint.
- Exits: `pushUndo()` (to undo concept), `menuManager.doPopupMenu()` (to menus concept), `clipboardManager.do*` (to clipboard concept), `actionManager.dumpCircuit` (to io).

### Commands-undo (history & recovery)
- Files: `UndoManager`.
- Owns: two `Vector<UndoItem>` stacks + recovery blob; text-dump snapshots via io layer.
- Pattern: full-snapshot only; no inverse commands; dedup on push.
- Entry: `CircuitEditor.pushUndo/doUndo/doRedo/doRecover` wrappers.

### Menus-actions (dispatch & i18n)
- Files: `MenuManager`, `ActionManager`, `MyCommand`, `Toolbar` (view-layer of the same concept).
- Owns: every menu/toolbar widget, class-label map, shortcut array, the `menuPerformed` dispatcher, `onPreviewNativeEvent` keyboard preview.
- Pattern: single string-keyed dispatcher; `MyCommand(menu, item)` is the sole command token; `Locale.LS` wraps user strings.
- Exits: delegates back to editor/renderer/scope/document/dialog managers.

### Clipboard
- Files: `ClipboardManager`, `ClipboardCallback`.
- Owns: internal buffer + JSNI bridge to `navigator.clipboard` (with legacy fallback) and circuit-data sniffing.
- Pattern: asymmetric sync-write / async-read with callback interface.
- Exits: `CircuitEditor.copyOfSelectedElms/doPaste` for payload serialisation/deserialisation.

---

## 12. Issues & Smells

1. **String identity comparisons in ActionManager** — `menu == "edit"`, `item == "save"`, etc. (L194, L198–498). Works only because both sides are string literals interned by the JVM. A single call site that passes a `String.valueOf(...)` or a concat would silently break.
2. **300-line `if` chain in `menuPerformed`** — no central registry, no help-text, easy to miss branches when adding commands; the `MyCommand` abstraction exists but each command still resolves via the giant dispatcher.
3. **Full-snapshot undo scales with circuit size** — every edit serialises the entire circuit through the text format; no incremental history, no command coalescing for continuous drags (`mouseDown` snapshots once, but `mouseUp` snapshots again on success — so a single drag produces 2 entries).
4. **Global mutable UI state leaks into render paths** — `drawCircuitInContext` flips `printableCheckItem`/`dotsCheckItem` during export and restores them in a `finally`. If an exception escapes the paint it is OK, but any background timer firing during export sees the wrong state.
5. **"Temp" mouse-mode duplication** — `mouseMode` + `tempMouseMode` + `mouseModeStr` encode the same intent in three places; `setMouseMode(String)` assigns all three; they can drift (e.g. `setMouseMode(MouseMode)` L145 does not update `mouseModeStr`).
6. **`CircuitEditor.dragElm` is defensively deleted twice** (L767 and L789–791 after the same branch); either branch can run.
7. **`CircuitRenderer.render` checks `simulator.stopElm` twice with empty bodies** (L208/225) — leftover dead code.
8. **`Toolbar` duplicates `createIconButton` for `ClickHandler` vs `MyCommand`** (L119 and L155) with near-identical style/hover logic, diverging by ~20 lines.
9. **`UndoManager` constructor takes `CircuitDocument` but only uses it via `getActiveDocument()` in `loadUndoItem`** — implies undo is actually document-scoped but resolves through the active document, which is correct only while this undo instance *is* the active one.
10. **`MenuManager.shortcuts[]` is ASCII-127-sized** — any non-ASCII shortcut silently becomes impossible.
11. **Keyboard shortcut for `Ctrl+Shift+T`** is registered twice (ActionManager L148–155 and L165–168); the second branch is unreachable but harmless.
12. **Hover ref comment L62–64** documents that `mouseElmRef` was moved off `CircuitElm` to avoid global state — similar latent globals (`Graphics.isFullScreen`, static color caches) remain elsewhere and imply the same issue could recur in multi-document mode.
13. **Event routing has no event-capture/priority model** beyond `ActionManager.onPreviewNativeEvent`; if two editors ever needed input simultaneously (e.g. a pop-out tab), the `CircuitEditorEventHandler`'s hardcoded `getActiveDocument()` would need to change.
14. **`ClipboardManager.isCircuitData` heuristic** — matches any text containing ` r `, ` c `, ` l ` or ` w ` patterns; a non-circuit blob that happens to include "w " will be fed to `doPaste` which may or may not be rejected by the importer.
