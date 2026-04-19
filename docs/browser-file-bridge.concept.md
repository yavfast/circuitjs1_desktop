# Browser File Bridge — DOM-to-Java File Acquisition  {#C_FBR}

> **Code:** C_FBR
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard-doc-gen
>
> **Depends on:** C_IOF (io-framework), C_PLT (platform), [C_DOC](./document-model.concept.md), [C_IEU](./import-export-ui.concept.md) (dialog-import covered there)
> **Used by:** [C_MEN](./menus-actions.concept.md), [C_EDI](./canvas-editor.concept.md)
> **Spike:** —
> **Specification:** [SP_FBR](./browser-file-bridge.sp.md)
> **Plan:** [browser-file-bridge.plan.md](./browser-file-bridge.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/layer3__file-io-glue.md`.
>
> Two classes (`LoadFile`, `SRAMLoadFile`) that adapt `<input type=file>` + `FileReader` DOM events into Java strings: circuit text for the io framework, or ASCII-decimal byte dumps for `SRAMElm`. `ImportFromDropbox` already covered in `dialog-import`; referenced here only as cross-reference.

## 1. Philosophy  {#C_FBR_01}

### 1.1. Core Principle  {#C_FBR_01_01}

No circuit semantics live in this concept — it is a pure acquisition adapter. Each class reads a browser artefact (`File`) and produces a Java `String` that either enters the io framework (`CircuitLoader.readCircuit`) or targets an element-local shuttle (`SRAMElm.contentsOverride`).

### 1.2. Design Constraints  {#C_FBR_01_02}

- **128 KB size cap** in `LoadFile.doLoad` and `SRAMLoadFile.handle`; `ImportFromDropbox` uses 100 KB (inconsistent).
- **DOM rebuild quirk.** `<input type=file>` does not fire `ChangeEvent` on same-file re-selection; `LoadFile.doLoad` calls `sim.createNewLoadFile()` to rebuild the widget after every load.
- **`sLoadFile` is a global.** A single static JSNI-accessible singleton; every `new LoadFile(sim)` overwrites it.
- **`SRAMElm.contentsOverride` is a cross-instance static shuttle.** Works because `DialogManager` enforces single-modal policy.
- **Orchestrator sprawl.** `LoadFile.doLoad` mixes five concerns (load, tab management, undo reseed, focus restoration, DOM rebuild).

## 2. Domain Model  {#C_FBR_02}

### 2.1. Key Entities  {#C_FBR_02_01}

```
LoadFile extends FileUpload implements ChangeHandler
  static LoadFile sLoadFile                 -- JSNI callback target
  BaseCirSim       sim
  DOM id "LoadFileElement" (offscreen)
  static native isSupported()               -- File + FileReader feature probe
  static doLoad()                           -- JSNI ChangeEvent entry; 128 KB cap
  static doLoadCallback(text, name)         -- JSNI re-entry -> instance doLoad
  void doLoad(String s, String t)           -- instance orchestrator (5 concerns)

SRAMLoadFile extends dialog.EditDialogLoadFile
  BaseCirSim cirSim
  DOM id "EditDialogLoadFileElement" (inherited)
  final native void handle()                -- readAsArrayBuffer; "0: b0 b1 …" encoding
  void handleLoad(String data)
    -> SRAMElm.contentsOverride = data
    -> cirSim.dialogManager.resetEditDialog()
    -> SRAMElm.contentsOverride = null

ImportFromDropbox  (cross-reference; see [C_IEU](./import-export-ui.concept.md))
  ctor invokes $wnd.Dropbox.choose
```

### 2.2. Data Flows  {#C_FBR_02_02}

```
File → Open (text circuit)
  LoadFile.click() -> browser picker -> ChangeEvent
    -> LoadFile.doLoad() JSNI
       reject if size >= 128000 (alert "File too large!")
       new FileReader().readAsText(files[0])
       onload -> @doLoadCallback(text, name)
  -> sLoadFile.doLoad(s, t)
       IF active document empty and no unsaved: reuse
       ELSE documentManager.createDocument() + setActiveDocument
       circuitLoader.readCircuit(s)              -- io framework
       undoManager.resetAndSeedFromCurrentCircuit()
       sim.enableUndoRedo()
       renderer.getCanvas().setFocus(true)
       sim.createNewLoadFile()                    -- DOM rebuild
       setCircuitTitle(t); setLastFileName(t); setUnsavedChanges(false)

SRAM "Load Contents From File"
  EditDialogLoadFile.open() -> picker -> ChangeEvent
    -> SRAMLoadFile.handle() JSNI
       reject if size >= 128000 -> doErrorCallback (Locale-wrapped alert)
       new FileReader().readAsArrayBuffer
       Uint8Array -> "0: " + space-separated decimal bytes
       @handleLoad(data)
  -> SRAMLoadFile.handleLoad
       SRAMElm.contentsOverride = data
       cirSim.dialogManager.resetEditDialog()     -- rebuild Edit dialog
       SRAMElm.contentsOverride = null
```

## 3. Mechanisms  {#C_FBR_03}

### 3.1. Core Algorithm  {#C_FBR_03_01}

**Tab policy (LoadFile).** Reuse active document iff empty (no elements, no unsaved, no filename); otherwise create a new tab. After load, reseed undo so Ctrl+Z cannot revert to the placeholder.

**DOM rebuild.** `createNewLoadFile()` replaces the `<input>` element in the DOM but keeps `sLoadFile` pointed at the latest instance; this is the documented workaround for the same-file ChangeEvent quirk.

**SRAM encoding.** JS loop emits one line `"0: <b0> <b1> … <bN-1>"` with unsigned decimal bytes (0–255) space-separated. `SRAMElm.setChipEditValue` later re-parses via `parseNumber` (decimal/0x/0b). No chunking; leaves the chunked form to the live-dump branch.

**Electron bridge.** `CirSim.electronOpenFileCallback` also routes into `LoadFile.doLoadCallback`, bypassing the FileReader JSNI entirely while reusing the same Java orchestrator.

### 3.2. Edge Cases  {#C_FBR_03_02}

- `LoadFile` error message not localised (`alert("File too large!")` raw English); `SRAMLoadFile` does localise via `EditDialogLoadFile.doErrorCallback` + `Locale.LS`.
- `sLoadFile` race: a pending async FileReader vs a widget rebuild could deliver text to a newer instance (not observed in practice).
- `SRAMElm.contentsOverride` cross-instance coupling relies on the single-modal-dialog assumption.
- `getPath()` returns sandbox-faked `"C:\\fakepath\\..."` on modern browsers — dead data except in Electron.
- No progress feedback or abort path on any channel.

## 4. Integration Points  {#C_FBR_04}

### 4.1. Dependencies  {#C_FBR_04_01}

- **C_IOF (io-framework)** — `CircuitLoader.readCircuit(text)` (LoadFile only; SRAMLoadFile bypasses io).
- **C_PLT** — JSNI to `File`, `FileReader`, DOM `getElementById`.
- **C_DOC** — `DocumentManager.createDocument`, `CircuitDocument.circuitLoader/undoManager`.
- **[C_UND](./commands-undo.concept.md)** — `resetAndSeedFromCurrentCircuit()` post-load.
- **[C_DRT](./dialog-routing.concept.md)** — `SRAMLoadFile` calls `dialogManager.resetEditDialog()`.
- **C_ELB** (for SRAM) — `SRAMElm.contentsOverride` static shuttle.
- **[C_IEU](./import-export-ui.concept.md)** — `ImportFromDropbox` is in this sibling concept.

### 4.2. API Surface  {#C_FBR_04_02}

- `LoadFile`: `static isSupported()`, `static doLoadCallback(text, name)`, `static doLoad()`, instance `doLoad(String, String)`, `native getPath/getFileName`, `native click()`.
- `SRAMLoadFile`: `static isSupported()` (inherited), `final native handle()`, `handleLoad(String)` (private).
- Menu/element glue reads `isSupported()` to gate the "Open Local File" menu item and the SRAM "Load Contents" button.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
