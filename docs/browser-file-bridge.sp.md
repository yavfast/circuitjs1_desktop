# Browser File Bridge — Specification  {#SP_FBR}

> **Code:** SP_FBR
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_FBR](./browser-file-bridge.concept.md)
> **Depends on specs:** [SP_IOF](./io-framework.sp.md), [SP_PLT](./platform-bridge.sp.md), [SP_DOC](./document-model.sp.md), [SP_IEU](./import-export-ui.sp.md)
> **Used by specs:** [SP_MEN](./menus-actions.sp.md), [SP_EDI](./canvas-editor.sp.md)
> **Plan:** [browser-file-bridge.plan.md](./browser-file-bridge.plan.md)

## 01. Data Structures  {#SP_FBR_01}

### 01_01. LoadFile  {#SP_FBR_01_01}

| Field | Type | Description |
|-------|------|-------------|
| sLoadFile | static LoadFile | JSNI-accessible singleton (last-constructed wins). |
| sim | final BaseCirSim | parent simulator. |
| DOM id | "LoadFileElement" | offscreen; located by JSNI and Electron bridge. |

Constants: 128000-byte size cap (hard-coded).

### 01_02. SRAMLoadFile  {#SP_FBR_01_02}

| Field | Type | Description |
|-------|------|-------------|
| cirSim | final BaseCirSim | needed for `dialogManager.resetEditDialog()`. |
| DOM id | "EditDialogLoadFileElement" | inherited from `EditDialogLoadFile`. |

Size cap: 128000 bytes.

Produced payload grammar: `"0: <b0> <b1> … <bN-1>"` — single line, decimal bytes, space-delimited.

## 02. Contracts  {#SP_FBR_02}

### 02_01. LoadFile.doLoad (JSNI)  {#SP_FBR_02_01}

    FUNCTION LoadFile.doLoad():
        f = this.files[0]
        IF f.size >= 128000: alert("File too large!"); RETURN
        reader = new FileReader()
        reader.onload = function(e) { @doLoadCallback(e.target.result, f.name) }
        reader.readAsText(f)

### 02_02. LoadFile.doLoad (Java instance)  {#SP_FBR_02_02}

    FUNCTION doLoad(s, t):
        doc = documentManager.getActiveDocument()
        IF doc.elmList.isEmpty() AND !doc.circuitInfo.isModified() AND doc.fileName == null:
            reuse doc
        ELSE:
            doc = documentManager.createDocument()
            documentManager.setActiveDocument(doc)
        doc.circuitLoader.readCircuit(s)
        doc.undoManager.resetAndSeedFromCurrentCircuit()
        sim.enableUndoRedo()
        renderer.getCanvas().setFocus(true)
        sim.createNewLoadFile()                     -- DOM rebuild for ChangeEvent quirk
        setCircuitTitle(t); setLastFileName(t); setUnsavedChanges(false)

### 02_03. SRAMLoadFile.handle (JSNI)  {#SP_FBR_02_03}

    FUNCTION SRAMLoadFile.handle():
        f = this.files[0]
        IF f.size >= 128000:
            @EditDialogLoadFile.doErrorCallback("Cannot load: That file is too large!")
            RETURN
        reader = new FileReader()
        reader.onload = function(e) {
            arr = new Uint8Array(e.target.result)
            str = "0:"
            FOR i in 0..arr.length-1: str += " " + arr[i]
            @handleLoad(str)
        }
        reader.readAsArrayBuffer(f)

### 02_04. SRAMLoadFile.handleLoad  {#SP_FBR_02_04}

    FUNCTION handleLoad(data):
        SRAMElm.contentsOverride = data
        cirSim.dialogManager.resetEditDialog()
        SRAMElm.contentsOverride = null

## 03. Validation Rules  {#SP_FBR_03}

- Both paths reject files `>= 128000` bytes.
- `LoadFile.doLoadCallback` is re-entered on the UI thread; must not block.
- `SRAMElm.contentsOverride` is non-null only during the window between `handleLoad` and dialog rebuild.
- `isSupported()` gates menu item visibility.

## 04. State Transitions  {#SP_FBR_04}

| From | To | Trigger | Side effect |
|------|----|---------|-------------|
| no picker | picker open | `LoadFile.click()` / SRAM "Load Contents" button | DOM click() |
| picker open | reading | user selects file | ChangeEvent fired |
| reading | loaded | FileReader onload | JSNI callback |
| loaded (LoadFile) | idle | doLoad returns | `createNewLoadFile()` rebuilds `<input>` |
| loaded (SRAM) | idle | handleLoad returns | dialog rebuild |

## 05. Verification Criteria  {#SP_FBR_05}

### 05_01. Functional  {#SP_FBR_05_01}

| Contract | Scenario | Input | Expected |
|----------|----------|-------|----------|
| LoadFile.doLoad | normal text | < 128 KB circuit | tab updated; undo reseeded; title set |
| LoadFile.doLoad | oversized | >= 128 KB | alert; no load |
| LoadFile.doLoad | empty active doc | — | reused (no new tab) |
| SRAMLoadFile.handle | 256-byte ROM | — | `"0: 0 0 … 255"`; edit dialog reopens populated |

### 05_02. Invariants  {#SP_FBR_05_02}

| Invariant | Verification |
|-----------|--------------|
| sLoadFile points at latest `LoadFile` | inspect after multiple `new LoadFile` |
| contentsOverride null after handleLoad | inspect post-handle |

### 05_03. Edge Cases  {#SP_FBR_05_03}

| Case | Input | Expected |
|------|-------|----------|
| same file re-selected | second pick of identical file | ChangeEvent fires (thanks to DOM rebuild) |
| Electron host | electronOpenFile | bypasses FileReader; calls doLoadCallback directly |
| no File API | ancient browser | isSupported()=false; menu item hidden |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
