# Canvas Editor — Interactive Editing Shell  {#C_EDI}

> **Code:** C_EDI
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard-doc-gen
>
> **Depends on:** [C_ELB](./element-base.concept.md), [C_RND](./rendering-primitives.concept.md), [C_GEO](./geometry.concept.md), [C_UTL](./util-locale-log.concept.md), [C_SIM](./simulator-engine.concept.md), [C_DOC](./document-model.concept.md)
> **Used by:** [C_UND](./commands-undo.concept.md), [C_MEN](./menus-actions.concept.md), [C_CLP](./clipboard.concept.md), [C_SCP](./scope-visualization.concept.md)
> **Spike:** —
> **Specification:** [SP_EDI](./canvas-editor.sp.md)
> **Plan:** [canvas-editor.plan.md](./canvas-editor.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/layer3__editor-interaction.md`.
>
> The interactive editor shell: routes GWT Canvas events into a `MouseMode` state machine, hit-tests elements/posts/scope-splitter, drives drag/selection/creation, and repaints the circuit via a coalesced render pipeline keyed to `PerfMonitor`.

## 1. Philosophy  {#C_EDI_01}

### 1.1. Core Principle  {#C_EDI_01_01}

A single document-scoped editor owns the canvas viewport, cursor state, hit-testing, and every direct-manipulation gesture (select, drag, create, flip, split). It *never* mutates element geometry without first calling `pushUndo()` (to the undo concept) and never serializes circuits directly (delegates to the io framework via `ActionManager`). The canvas DOM handler is registered exactly once on the shared `Canvas`; per-tab isolation is achieved by routing every event through `cirSim.getActiveDocument().circuitEditor`.

### 1.2. Design Constraints  {#C_EDI_01_02}

- **Dual mode field** — the sticky `mouseMode` (user choice) is separate from `tempMouseMode` (modifier-driven transient mode reset on mouseUp). A `mouseModeStr` label tracks the current add-element class name for renderer and toolbar highlighting.
- **Delayed select→drag upgrade** — a `SELECT`-mode click does not begin moving until `DRAG_DELAY=150 ms` has elapsed, preventing spurious moves on single clicks.
- **No global hover state** — the old static `mouseElmRef` on `CircuitElm` has been explicitly localised onto `CircuitEditor` so multiple documents can coexist.
- **Render is coalescing** — `repaint()` only sets a flag and defers through `Scheduler.scheduleDeferred`; the paint pipeline runs once per frame regardless of repaint count.

## 2. Domain Model  {#C_EDI_02}

### 2.1. Key Entities  {#C_EDI_02_01}

```
MouseMode (enum, 8 states)
  ADD_ELM | DRAG_ALL | DRAG_ROW | DRAG_COLUMN | DRAG_SELECTED
  DRAG_POST | SELECT | DRAG_SPLITTER

CircuitEditor extends BaseCirSimDelegate
  implements 8 GWT event handler interfaces (Mouse/Click/ContextMenu/KeyPress…)
  state: mouseMode, tempMouseMode, mouseModeStr, dragging, dragGridX/Y,
         mouseElm (hover), menuElm (right-click), dragElm (mid-creation),
         heldSwitchElm, selectedArea, scrollValuePopup, grid*, wheelSensitivity.
  constants: POST_GRAB_SQ=25, MIN_POST_GRAB_SIZE=256, DRAG_DELAY=150

CircuitEditorEventHandler
  thin multiplex; registered once on Canvas; forwards to
  cirSim.getActiveDocument().circuitEditor.onXxx(event).

CircuitRenderer extends BaseCirSimDelegate
  owns: canvas, canvasContext, canvasWidth/Height, transform[6] affine,
         timing (fps/sps), unitsFont, circuitArea, scopeHeightFraction=0.2,
         hint state, perfmon.
```

`transform` is stored as `[a,b,c,d,tx,ty]` for direct `Context2d.setTransform`; device pixel ratio is folded in only inside `drawCircuit` to keep logical coords uniform elsewhere.

### 2.2. Data Flows  {#C_EDI_02_02}

```
GWT DOM event → CircuitEditorEventHandler → active document's CircuitEditor
  mouseDown:  setTempMouseMode(modifiers) → hit-test (splitter/scope/switch/post-grab)
              → pushUndo → start dragElm (if ADD_ELM)
  mouseMove:  if dragging, switch(tempMouseMode) → dragAll | dragRow | dragColumn
              | dragPost | SELECT→DRAG_SELECTED (after 150 ms) | dragSelected
              | dragSplitter;  else mouseSelect → findElm / findElmInScope / findElmByPost
  mouseUp:    finalise dragElm (or delete if creationFailed); needAnalyze;
              pushUndo on change; setUnsavedChanges; writeRecoveryToStorage
  wheel:      scrollValues (element-specific) OR zoomCircuit
  contextMenu: menuManager.doPopupMenu
  doubleClick: doEditElementOptions(mouseElm)

Render loop (per frame):
  CircuitRenderer.repaint() → Scheduler.scheduleDeferred → render()
  → setupFrame → updateSimulationTimers → drawCircuit (elements, handles,
    bad-connections, rubber-band, crosshair) → drawBottomArea (scopes,
    info box) → drawDeveloperInfo (PerfMonitor) → drawMouseMode.
```

## 3. Mechanisms  {#C_EDI_03}

### 3.1. Core Algorithm  {#C_EDI_03_01}

**MouseMode machine.** Committed `mouseMode` is sticky; `tempMouseMode` is modifier-driven per-gesture and reset to `mouseMode` on mouseUp. Modifier table on mouseDown: plain LMB → committed; Shift → SELECT; Alt → DRAG_ALL; Ctrl/Meta → DRAG_POST; Alt+Shift → DRAG_ROW; Alt+Meta → DRAG_COLUMN; RMB/MMB → DRAG_ALL; over splitter → DRAG_SPLITTER; close to a post handle → DRAG_POST (upgrade). A SELECT-mode drag promotes to DRAG_SELECTED only after `DRAG_DELAY`. The `noEditCheckItem` lock forces `tempMouseMode = SELECT` unconditionally.

**Element creation.** In `ADD_ELM`, mouseDown calls `CircuitElmCreator.constructElement(doc, mouseModeStr, x0, y0)` → `dragElm`. The element is committed to `simulator().elmList` on mouseUp unless `creationFailed()` (zero-length drag) — in which case it is silently dropped.

**Render pipeline.** Affine transform mixes user zoom/pan with devicePixelRatio only inside `drawCircuit`; export paths (`drawCircuitInContext`, `getCircuitAsCanvas`, `getCircuitAsSVG`) save/restore the transform and temporarily flip `printableCheckItem`/`dotsCheckItem`. `setCircuitScale` re-anchors transform so the point under the cursor stays stationary during zoom. `PerfMonitor` wraps `render()` and `graphics` sub-phases.

### 3.2. Edge Cases  {#C_EDI_03_02}

- `dragElm` is defensively deleted at two points — either branch may run.
- `removeZeroLengthElements()` prunes elements whose endpoints collapsed during a drag.
- Double registration of `Ctrl+Shift+T` shortcut; second branch unreachable but harmless.
- Export paint temporarily mutates `printable`/`dots` check items in a `finally`; a background timer firing mid-export would see the wrong state.
- `mouseElmRef` hover ref is per-editor but remnants of static state (`Graphics.isFullScreen`, color caches) still exist.

## 4. Integration Points  {#C_EDI_04}

### 4.1. Dependencies  {#C_EDI_04_01}

- **[C_ELB](./element-base.concept.md)** — reads `getPost`, `getMouseDistance`, `getBoundingBox`, `draw`, `drawHandles`; mutates `selected`, geometry via `drag/move/flipX/Y/XY`.
- **C_SIM** — `simulator().elmList` is the mutable source of truth.
- **C_DOC** — each document owns its own (`circuitEditor`, `circuitRenderer`) pair; `CircuitEditorEventHandler` dereferences the active one per event.
- **[C_RND](./rendering-primitives.concept.md)** — `Graphics`, Context2d affine, canvas DPR.
- **[C_UTL](./util-locale-log.concept.md)** — `PerfMonitor`, `Locale.LS` (mode label i18n).
- **[C_UND](./commands-undo.concept.md)** — every mutating path calls `pushUndo()` first.
- **[C_MEN](./menus-actions.concept.md)** — popup menu via `menuManager.doPopupMenu`; context panel positioning.
- **[C_CLP](./clipboard.concept.md)** — `copyOfSelectedElms`, `doPaste`.

### 4.2. API Surface  {#C_EDI_04_02}

- Event entry points: `onMouseDown/Move/Up/Wheel/Out/Click/ContextMenu/DoubleClick`.
- Mode API: `setMouseMode(String|MouseMode)`, `setCursorStyle`, `setMenuSelection`, `clearSelection`, `doSelectAll`.
- Edit API: `doFlip/doSplit/flipX/Y/XY`, `doSwitch`, `doCut/Copy/Paste/Duplicate/Delete`, `doEditOptions/doEditElementOptions/doSliders`, `doUndo/Redo/Recover`, `pushUndo`.
- Grid/view: `snapGrid`, `setGrid`, `setWheelSensitivity`.
- Renderer API: `initCanvas`, `setCanvasSize`, `setCircuitArea`, `zoomCircuit`, `setCircuitScale`, `transformX/Y`/`inverseTransformX/Y`, `centreCircuit`, `repaint`, `getCircuitAsCanvas/SVG`, `getCircuitBounds`.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
