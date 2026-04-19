# Canvas Editor — Specification  {#SP_EDI}

> **Code:** SP_EDI
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_EDI](./canvas-editor.concept.md)
> **Depends on specs:** [SP_ELB](./element-base.sp.md), SP_RND, SP_GEO, SP_UTL, SP_SIM, SP_DOC
> **Used by specs:** —
> **Plan:** [canvas-editor.plan.md](./canvas-editor.plan.md)
>
> Specification of the interactive editor shell: data structures, operation contracts, mode state-machine rules, verification scenarios.

## 01. Data Structures  {#SP_EDI_01}

> Implements: [C_EDI_02](./canvas-editor.concept.md#C_EDI_02)

### 01_01. MouseMode  {#SP_EDI_01_01}

Enum discriminator used by `CircuitEditor` and `CircuitRenderer` for cursor style, drag branch, and toolbar highlighting.

| Value | Meaning |
|-------|---------|
| `ADD_ELM` | Creating a new element (mouseModeStr names the class). |
| `DRAG_ALL` | Pan the viewport. |
| `DRAG_ROW` | Translate row-aligned elements. |
| `DRAG_COLUMN` | Translate column-aligned elements. |
| `DRAG_SELECTED` | Move selected elements. |
| `DRAG_POST` | Drag a single post of the hovered element. |
| `SELECT` | Default; hit-test + rubber-band. |
| `DRAG_SPLITTER` | Drag the scope/circuit area splitter. |

Invariants:
- `tempMouseMode` is reset to `mouseMode` on mouseUp.
- In `ADD_ELM`, `mouseModeStr` is non-null and matches a registered element class name.

### 01_02. CircuitEditor state  {#SP_EDI_01_02}

Fields:
| Field | Type | Default | Constraints | Description |
|-------|------|---------|-------------|-------------|
| mouseMode | MouseMode | SELECT | non-null | Sticky mode. |
| tempMouseMode | MouseMode | SELECT | non-null | Per-gesture mode. |
| mouseModeStr | String | "Select" | non-null | Label for renderer/toolbar. |
| dragging | boolean | false | — | True between mouseDown and mouseUp. |
| mouseDownTime | long | 0 | ms | Used for 150 ms drag-upgrade timer. |
| dragGridX/Y, initDragGridX/Y | int | — | grid-snapped | Snapped drag state. |
| mouseElm | CircuitElm | null | — | Hovered element. |
| menuElm | CircuitElm | null | — | Right-click target. |
| dragElm | CircuitElm | null | — | Mid-creation element. |
| heldSwitchElm | SwitchElm | null | — | Momentary-switch tracking. |
| selectedArea | Rectangle | null | — | Rubber-band rectangle. |
| gridSize / gridMask / gridRound | int | 8/-8/4 | pow2 | Grid snap. |
| wheelSensitivity | double | 1.0 | > 0 | Wheel zoom gain. |

Constants: `POST_GRAB_SQ = 25`, `MIN_POST_GRAB_SIZE = 256`, `DRAG_DELAY = 150`.

### 01_03. CircuitRenderer state  {#SP_EDI_01_03}

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| transform | double[6] | identity | 2×3 affine for Context2d.setTransform. |
| canvasWidth/Height | int | — | Logical (CSS) pixels. |
| circuitArea | Rectangle | — | Usable area excluding scope strip. |
| scopeHeightFraction | double | 0.2 | Vertical share reserved for docked scopes. |
| currentMult / powerMult | double | — | Per-frame animation multipliers. |
| framesPerSecond / stepsPerSecond | double | 0 | EMA statistics. |
| needsAnalysis / needsRepaint | boolean | false | Coalescing flags. |
| perfmon | PerfMonitor | — | Developer-overlay collector. |

## 02. Contracts  {#SP_EDI_02}

### 02_01. setMouseMode  {#SP_EDI_02_01}

Purpose: Change the sticky mode and cursor.

Input: `String modeName` *or* `MouseMode mode`.

Processing logic:
    FUNCTION setMouseMode(name):
        mouseMode      = mapStringToEnum(name) or SELECT
        tempMouseMode  = mouseMode
        mouseModeStr   = name
        setCursorStyle(mouseMode)
        menuManager.doMainMenuChecks()
        toolbar.highlightButton(name)

### 02_02. pushUndo  {#SP_EDI_02_02}

Forwards to `undoManager.pushUndo()`. Every mutating edit path must call this *before* mutating `simulator().elmList`. See [SP_UND_02_01](./commands-undo.sp.md#SP_UND_02_01).

### 02_03. doPaste(text)  {#SP_EDI_02_03}

Purpose: Deserialize a clipboard payload and insert at the cursor.

Processing logic:
    FUNCTION doPaste(text):
        pushUndo
        clearSelection
        circuitLoader.readCircuit(text, RC_RETAIN | RC_NO_CENTER)
        selectNewItems
        IF canMoveNewItems THEN moveNewItems(deltaFromOldBBoxOrCursor)
        needAnalyze; writeRecoveryToStorage; setUnsavedChanges(true)

### 02_04. Render pipeline  {#SP_EDI_02_04}

    FUNCTION render():
        perfmon.startContext("render()")
        checkCanvasSize                          -- DPR resync
        scopeManager.setupScopes()
        g = new Graphics(canvasContext)
        setupFrame(g)                            -- bg colour
        updateSimulationTimers()                 -- currentMult/powerMult/fps
        perfmon.startContext("graphics")
        drawCircuit(g):
            setTransform(dpr * user scale/translate)
            drawElements
            drawHandles
            drawBadConnections
            drawSelectionAndCursor
            setTransform back to screen space
            drawBottomArea
        perfmon.stopContext("graphics")
        IF developerMode  THEN drawDeveloperInfo
        IF showMouseMode  THEN drawMouseMode
        cirSim.callUpdateHook()

## 03. Validation Rules  {#SP_EDI_03}

- `mouseMode`/`tempMouseMode`/`mouseModeStr` must agree after any `setMouseMode(String)` call; drift possible via `setMouseMode(MouseMode)` which does not update `mouseModeStr` (see Backlog #5).
- `dragElm != null` implies `mouseMode == ADD_ELM` AND `dragging == true`.
- `noEditCheckItem.getState()` locks `tempMouseMode` to `SELECT` regardless of input.
- `transform[0] == transform[3]` (uniform scale; no skew/rotation).

## 04. State Transitions  {#SP_EDI_04}

### 04_01. Gesture lifecycle  {#SP_EDI_04_01}

| From | To | Trigger | Side effects |
|------|----|---------|--------------|
| idle | dragging(SELECT) | mouseDown on empty area | record mouseDownTime |
| dragging(SELECT) | dragging(DRAG_SELECTED) | elapsed ≥ 150 ms, hovering an element | none |
| idle | dragging(ADD_ELM) | mouseDown in ADD_ELM | constructElement; pushUndo |
| idle | dragging(DRAG_SPLITTER) | mouseDown over splitter | pushUndo |
| dragging(*) | idle | mouseUp | if dragElm → commit or creationFailed→delete; if changed → needAnalyze + pushUndo + writeRecovery; reset tempMouseMode = mouseMode |

## 05. Verification Criteria  {#SP_EDI_05}

### 05_01. Functional Expectations  {#SP_EDI_05_01}

| Contract | Scenario | Input | Expected outcome |
|----------|----------|-------|------------------|
| setMouseMode | String → enum | "Resistor" | mouseMode=ADD_ELM, mouseModeStr="Resistor", cursor=cross |
| pushUndo | normal edit | valid circuit | undoStack grows; redoStack cleared |
| doPaste | circuit text | valid dump | new elements selected; layout offset applied |
| render | first frame after repaint() | canvas resized | DPR re-applied once; draws complete |
| click | SELECT mode, <150 ms hold | on element | hover only; no move |
| click+drag | SELECT mode, ≥150 ms hold | on element | promotes to DRAG_SELECTED |

### 05_02. Invariant Checks  {#SP_EDI_05_02}

| Invariant | Verification |
|-----------|--------------|
| Single canvas handler registration | `CircuitEditorEventHandler` registered exactly once on the shared Canvas. |
| No lost dragElm | after mouseUp, `dragElm == null`. |
| No undo without dump | `undoStack.last()` is never null. |

### 05_03. Edge Cases  {#SP_EDI_05_03}

| Case | Input | Expected |
|------|-------|----------|
| Zero-length create | mouseDown + immediate mouseUp in ADD_ELM | creationFailed → dragElm deleted. |
| Tab switch mid-drag | active-document change between mouseMove and mouseUp | events route to new editor (may orphan dragging flag). |
| Export during background timer | timer fires while `printable` flipped | renders in wrong colour (known wart). |
| Non-ASCII shortcut | shortcut code > 127 | silently unreachable (`shortcuts[]` is 127-wide). |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
