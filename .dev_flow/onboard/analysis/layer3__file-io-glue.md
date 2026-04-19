# Sub-Unit Analysis: layer3 / file-io-glue

> **Path:** `src/main/java/com/lushprojects/circuitjs1/client/`
> **Layer:** 3 (application-shell glue between the browser's file/upload DOM
> and the io-framework string pipeline).
> **Analyzed:** 2026-04-19
> **Files:** 3
>  - `LoadFile.java` (117 LOC)
>  - `SRAMLoadFile.java` (62 LOC)
>  - `ImportFromDropbox.java` (82 LOC) — already catalogued in
>    `domain-core__dialog-import.md`; recorded here only as cross-reference.

## Purpose

A three-class "browser-file-bridge" that converts `<input type=file>` /
`FileReader` / Dropbox Chooser JS events into Java strings suitable for the
io-framework loaders. None of the three files parses circuit data; they are
pure acquisition endpoints that hand off to
`CircuitLoader.readCircuit` (LoadFile, ImportFromDropbox) or to an element's
static shuttle variable (SRAMLoadFile → `SRAMElm.contentsOverride`).

Three distinct payload shapes:

1. **Textual circuit dump** — `LoadFile`: top-level File → Open menu.
   FileReader `readAsText` → `String` → `CircuitLoader.readCircuit`.
2. **Binary ROM/SRAM image** — `SRAMLoadFile`: per-element "Load Contents
   From File" button in the Edit Dialog. FileReader `readAsArrayBuffer` →
   `Uint8Array` → ASCII "`0: b0 b1 b2 …`" string → `SRAMElm.contentsOverride`
   (static shuttle consumed when the Edit Dialog is rebuilt).
3. **Dropbox shared file** — `ImportFromDropbox`: Dropbox Chooser JS SDK.
   Analysed in full at `domain-core__dialog-import.md`
   (§"Dropbox JSNI bridge" / "ImportFromDropbox.doDropboxImport()"); not
   duplicated here. This sub-unit only notes how it slots next to LoadFile.

All three are GWT `FileUpload` subclasses (LoadFile, SRAMLoadFile via
`EditDialogLoadFile`) or plain JSNI-backed POJOs (ImportFromDropbox).

---

## Per-file Key Entities

### `LoadFile`
- **File:** `LoadFile.java:26`
- **Extends:** `com.google.gwt.user.client.ui.FileUpload`
- **Implements:** `ChangeHandler` (auto-load on file chosen).
- **Fields:**
  | Field | Type | Role |
  |---|---|---|
  | `sim` | `final BaseCirSim` | parent simulator handle (:28) |
  | `sLoadFile` | `static LoadFile` | JSNI-accessible singleton (:30, :79) |
- **DOM contract:**
  - Element id `"LoadFileElement"` (`:76`) — the JSNI payloads and
    `CirSim.electronOpenFile` locate the widget by this id.
  - Named `"Import"` (`:75`); offscreen CSS class `"offScreen"` (`:78`).
- **Static native probes / callbacks:**
  - `isSupported()` (:32) — `!!($wnd.File && $wnd.FileReader)`.
  - `doLoad()` (:99) — static JSNI triggered by ChangeEvent. Reads
    `files[0]`, rejects ≥ 128000 bytes with `alert("File too large!")`
    (:104), otherwise `FileReader.readAsText` → calls
    `doLoadCallback(text, fileName)`.
  - `doLoadCallback(String s, String t)` (:36) — delegates to the
    instance via the `sLoadFile` singleton.
- **Instance method `doLoad(String s, String t)`** (:40) — the load
  orchestrator:
  1. Decide whether a new tab is needed: if the active document is empty
     (no elements, no unsaved changes, no filename) reuse it, else
     `documentManager.createDocument()` + `setActiveDocument` (:42–52).
  2. `activeDocument.circuitLoader.readCircuit(s)` (:54) — the only
     hand-off into io-framework.
  3. Reseed undo history: `undoManager.resetAndSeedFromCurrentCircuit()`
     (:58) then `sim.enableUndoRedo()` — so Ctrl+Z cannot revert the
     document to the pre-load placeholder (this was a documented
     regression fix, see `ai_memory/context_history/EDITOR-DELETE-UNDO-SHORTCUTS.md`).
  4. `renderer.getCanvas().setFocus(true)` (:63) — restore keyboard
     shortcut routing to the canvas immediately (same fix).
  5. `sim.createNewLoadFile()` (:66) — rebuild the `<input>` to force a
     change event on re-loading the same file (see
     `CirSim.createNewLoadFile` :697, explicit javadoc comment at
     :698–702 stating this is a `FileUpload` DOM quirk).
  6. `setCircuitTitle(t)`, `setLastFileName(t)`, `setUnsavedChanges(false)`
     (:67–69).
- **Instance natives:**
  - `click()` (:95) — programmatic open: `$doc.getElementById(...).click()`.
    Invoked from menu glue so "File → Open" doesn't require the user to
    click the invisible `<input>` directly.
  - `getPath()` (:87) — returns the `value` attribute (sandbox-limited in
    modern browsers; typically a fake path). Used by
    `BaseCirSim.createNewLoadFile` :273.
  - `getFileName()` (:91) — `files[0].name`.
- **Invariants:**
  - Single global `sLoadFile`; every `new LoadFile(sim)` overwrites it
    (:79). `BaseCirSim.loadFileInput` (BaseCirSim.java:19) constructs the
    original; `createNewLoadFile` replaces the DOM widget but keeps the
    JSNI singleton pointed at the latest instance.
  - The 128000-byte cap is enforced *only* in the JSNI `doLoad`; no other
    acquisition channel (Dropbox ≈ 100 KB, SRAM = 128 KB) shares the
    constant.

### `SRAMLoadFile`
- **File:** `SRAMLoadFile.java:25`
- **Extends:** `com.lushprojects.circuitjs1.client.dialog.EditDialogLoadFile`
  (the abstract single-callback variant defined at
  `dialog/EditDialogLoadFile.java:32`; see
  `domain-core__dialog-edit.md` §"EditDialogLoadFile.java").
- **Fields:**
  | Field | Type | Role |
  |---|---|---|
  | `cirSim` | `final BaseCirSim` | needed for `dialogManager.resetEditDialog()` (:27) |
- **DOM contract:** element id `"EditDialogLoadFileElement"` (inherited
  from `EditDialogLoadFile` :45). `isSupported()` delegates to
  `LoadFile.isSupported()` (EditDialogLoadFile.java:35).
- **`handle()`** (:33) — overridden JSNI ChangeEvent handler:
  - `files[0].size >= 128000` → static error callback
    `EditDialogLoadFile.doErrorCallback("Cannot load: That file is too
    large!")` (:40) which pipes through `Locale.LS` + `Window.alert`
    (EditDialogLoadFile.java:38–40).
  - Otherwise `FileReader.readAsArrayBuffer` → `Uint8Array` → format each
    byte as ASCII decimal, prefix `"0:"` then space-separated values
    (:46–49). This is the textual form `SRAMElm.setChipEditValue` parses
    on the `"Contents"` line (SRAMElm.java:145 — `contentsOverride`
    branch).
  - Back to Java via `handleLoad(String data)` (:50).
- **`handleLoad(String data)`** (:57):
  1. `SRAMElm.contentsOverride = data` (:58) — **static global shuttle**
     on the element class (`SRAMElm.java:37`), no instance targeting.
  2. `cirSim.dialogManager.resetEditDialog()` (:59) — forces the open
     Edit Dialog to rebuild. The rebuild calls `getChipEditInfo(2)`, sees
     `contentsOverride != null`, copies the payload into the text-area,
     and nulls the shuttle (SRAMElm.java:145–147).
  3. `SRAMElm.contentsOverride = null` (:60) — belt-and-braces clear
     after the rebuild returns; also nulled inside the getter.
- **Invariants / known fragility:**
  - Single global static shuttle couples all `SRAMElm` instances.
    Documented foot-gun in `domain-core__cat-complex-ics.md` §Issues 5
    ("cross-instance coupling with single-dialog UI assumption").
  - The encoded string syntax ("`0: b0 b1 …`") is hard-coded in
    *two* places (JSNI loop here and the SRAM text-area parser).
  - `EditDialogLoadFile` DOM id is global — only one such widget may be
    alive at a time (dialogManager enforces single-modal-dialog policy).

### `ImportFromDropbox`
Already analysed in full at `.dev_flow/onboard/analysis/domain-core__dialog-import.md`
§"Companion root-level class" and §"Dropbox JSNI bridge". Key facts
recorded here for completeness:

- **File:** `ImportFromDropbox.java:3`.
- Instantiating it immediately invokes `$wnd.Dropbox.choose` (ctor :8
  calls `doDropboxImport()` :11).
- Static `sim` back-reference for JSNI (:6).
- `doLoadCallback(String)` (:30) → `circuitEditor.pushUndo()` +
  `circuitLoader.readCircuit(s)` — **no** `allowSave(false)` and **no**
  tab/undo-reseed dance (LoadFile does both). This is the documented
  divergence from the dialog-linked `ImportFromDropboxDialog.doLoadCallback`
  (which adds `allowSave(false)` but still skips the LoadFile reseed
  protocol).
- 100 KB `files[0].bytes < 100000` size-gate has a closure-scope bug
  (ImportFromDropbox.java:44–53, captured in
  `domain-core__dialog-import.md` Issue 1).
- The class is live (constructed from
  `ImportFromDropboxDialog.java:85`), but that dialog is itself dead at
  the menu layer (opener commented out at `ActionManager.java:241–245`).

---

## Browser-file → simulator flow

```
LoadFile.click() / <input> picker
  → browser fires ChangeEvent
    → LoadFile.onChange(e) :83
      → LoadFile.doLoad() (static JSNI) :99
        ├── reject if size >= 128000 (alert "File too large!")
        ├── new FileReader().readAsText(files[0])
        └── onload → @LoadFile::doLoadCallback(text, name) :109
          → sLoadFile.doLoad(s, t) :37
            ├── maybe documentManager.createDocument() + setActiveDocument
            ├── activeDocument.circuitLoader.readCircuit(s)            [io-framework]
            │    └── CircuitFormatRegistry.detectFormatOrDefault(s)
            │         → TextCircuitImporter / JsonCircuitImporter
            ├── undoManager.resetAndSeedFromCurrentCircuit()
            ├── renderer.getCanvas().setFocus(true)
            ├── sim.createNewLoadFile()        [rebuild DOM input]
            └── setCircuitTitle(t), setLastFileName(t), setUnsavedChanges(false)

SRAMLoadFile.open() [inherited from EditDialogLoadFile]
  → $doc.getElementById("EditDialogLoadFileElement").click()
    → browser fires ChangeEvent
      → EditDialogLoadFile.onChange(e) :51
        → this.handle()                       [abstract dispatch]
          → SRAMLoadFile.handle() :33
            ├── reject if size >= 128000 → doErrorCallback(...) → alert
            ├── new FileReader().readAsArrayBuffer(files[0])
            └── onload → Uint8Array → "0: b0 b1 … bn"
                  → @SRAMLoadFile::handleLoad(data) :50
                    → SRAMLoadFile.handleLoad(data) :57
                      ├── SRAMElm.contentsOverride = data         [static shuttle]
                      ├── cirSim.dialogManager.resetEditDialog()  [rebuild dialog]
                      │    → SRAMElm.getChipEditInfo(2) sees contentsOverride
                      └── SRAMElm.contentsOverride = null

ImportFromDropbox (see dialog-import.md for full flow)
  → $wnd.Dropbox.choose(options) → file picked
    → XMLHttpRequest GET files[0].link (iff bytes < 100000 — buggy gate)
      → @ImportFromDropbox::doLoadCallback(text)
        → circuitEditor.pushUndo() + circuitLoader.readCircuit(text)
```

Key contrasts between the three paths:

| Aspect | LoadFile | SRAMLoadFile | ImportFromDropbox |
|---|---|---|---|
| Trigger | File → Open menu, or `click()` glue | Edit Dialog "Load Contents From File" button | Dropbox chooser button |
| DOM id | `LoadFileElement` | `EditDialogLoadFileElement` | — (no HTML; Dropbox SDK) |
| FileReader mode | `readAsText` (UTF-8) | `readAsArrayBuffer` (binary) | remote XHR; `responseText` |
| Size cap | < 128000 B | < 128000 B | < 100000 B (+ scope bug) |
| Error feedback | `alert("File too large!")` (hard-coded English) | `doErrorCallback(...)` → `Locale.LS` + alert | silent (try/catch swallow) |
| Undo policy | `resetAndSeedFromCurrentCircuit` after load | none (dialog rebuild only) | `pushUndo()` before load |
| Tab policy | may create new tab | reuses active SRAM instance | loads into active tab |
| `allowSave` | `setUnsavedChanges(false)` after load (treats as "saved clean") | n/a (doesn't touch top-level circuit) | not called (see Issue) |
| Title / filename | `setCircuitTitle(t)`, `setLastFileName(t)` | unaffected | unaffected |
| Destination | `circuitLoader.readCircuit` (io-framework) | `SRAMElm.contentsOverride` (element-local) | `circuitLoader.readCircuit` (io-framework) |

---

## Binary / SRAM format produced by `SRAMLoadFile`

- **Input:** opaque file, arbitrary binary (typically a ROM image).
- **JS transformation (SRAMLoadFile.java:44–51):**
  ```
  var arr = new Uint8Array(reader.result);
  var str = "0:";
  for (var i = 0; i < arr.length; i++)
      str += " " + arr[i];   // decimal, 0..255
  ```
  So the output is an ASCII line:
  `"0: <b0> <b1> <b2> … <bN-1>"`
  where each `<bi>` is the unsigned byte value as a decimal string
  (0–255), separated by single spaces. The leading `"0:"` is the
  starting address in the SRAM address map.
- **Consumer (`SRAMElm.getChipEditInfo` :145–147):** the string is copied
  verbatim into the "Contents" TextArea in the Edit Dialog. The TextArea
  text is later parsed line-by-line in `SRAMElm.setChipEditValue` (same
  file, not shown here) using `parseNumber` (:182) which accepts decimal,
  `0x…` hex, and `0b…` binary.
- **Address map semantics:** each line begins with `"<address>:"` and is
  followed by up to a chipful of values. The JSNI always emits a single
  line starting at address 0 — it does not chunk to 8 values/line as the
  live-dump branch (:155–165) does. Re-editing and re-saving would
  rewrite in the chunked form.
- **Size bound:** capped at < 128000 bytes of file input. Since the line
  has ~4 bytes average per value ("`255 `"), the text payload fed to
  the TextArea can reach ~500 KB for a maximally full load.
- **No checksum, no endian-ness, no address width encoding.** The
  consumer uses the element's own `addressBits` to bound the map.

---

## Public Contracts

### `LoadFile`

| Symbol | Surface | Notes |
|---|---|---|
| `static boolean isSupported()` | JS capability probe | callers: `MenuManager.java:140`, `CirSim.java:307` |
| `static void doLoadCallback(String, String)` | JSNI re-entry | also called by `CirSim.electronOpenFileCallback` (:627) for the Electron/desktop bridge |
| `static void doLoad()` | JSNI ChangeEvent entry | called by its own `onChange` and from GWT |
| `void doLoad(String s, String t)` | instance load orchestrator | the single in-Java integration seam |
| `native String getPath()` / `getFileName()` | post-load metadata | consumed by `BaseCirSim.createNewLoadFile` (:273–276) |
| `native void click()` | programmatic file-picker open | used by "File → Open" menu glue |

### `SRAMLoadFile`

| Symbol | Surface | Notes |
|---|---|---|
| `static boolean isSupported()` | inherited from `EditDialogLoadFile`, delegates to `LoadFile.isSupported()` | called at SRAMElm.java:172 before constructing the widget |
| `final native void handle()` | overrides `EditDialogLoadFile.handle()` | the single JSNI reader |
| `private void handleLoad(String)` | internal shuttle setter | not public; called only from its own JSNI |

### `ImportFromDropbox`

See `domain-core__dialog-import.md` §"Dropbox JSNI bridge" for the full
contract (three static entry points: `isSupported`, `doLoadCallback`,
the ctor-invoked `doDropboxImport`).

---

## Integration Points

### Depends on

**`LoadFile`:**
- Root: `BaseCirSim`, `CircuitDocument`, `CircuitInfo` (via
  `activeDocument.circuitInfo.*`), `DocumentManager`, `UndoManager`,
  `CircuitLoader`, `CircuitSimulator` (for `elmList.isEmpty()`),
  `CircuitRenderer` (for canvas focus).
- GWT: `FileUpload`, `ChangeHandler`, `ChangeEvent`.
- Browser: `File`, `FileReader`, `$doc.getElementById`.

**`SRAMLoadFile`:**
- Root: `BaseCirSim`, `DialogManager`.
- `dialog/EditDialogLoadFile` (superclass).
- `element/SRAMElm` (static `contentsOverride`).
- GWT: `FileUpload` (via super), JSNI.
- Browser: `FileReader`, `Uint8Array`.

**`ImportFromDropbox`:**
- Root: `CirSim`, `CircuitEditor`, `CircuitLoader`.
- Browser: `$wnd.Dropbox.*`, `XMLHttpRequest`, `navigator.userAgent`.

### Used by

| Entry point | Caller |
|---|---|
| `new LoadFile(this)` | `BaseCirSim.java:19` (single construction site) |
| `loadFileInput.click()` | File-open menu glue (menuManager actions) |
| `LoadFile.isSupported()` | `MenuManager.java:140`, `CirSim.java:307` |
| `LoadFile.doLoadCallback(text, name)` | `CirSim.electronOpenFileCallback` (:627) — desktop/Electron bridge |
| `new SRAMLoadFile(cirSim())` | `element/SRAMElm.java:174` |
| `SRAMLoadFile.isSupported()` | `element/SRAMElm.java:172` |
| `new ImportFromDropbox(asim)` | `dialog/ImportFromDropboxDialog.java:85` (chooser button click) |

### io-framework binding

- **LoadFile** is the dominant ingress point into the io-framework
  pipeline from the local filesystem. It calls
  `circuitLoader.readCircuit(s)` with no flags, so the io-framework's
  `CircuitFormatRegistry.detectFormatOrDefault` chooses the format
  (`io-framework.md` §"Format detection order" — text wins ties, JSON
  triggers on `{`).
- **SRAMLoadFile** does **not** call the io-framework at all — the
  ArrayBuffer → ASCII text string is an element-internal format owned by
  `SRAMElm`. This is the only file-acquisition path in the project that
  bypasses `CircuitLoader` / `CircuitFormatRegistry`.
- **ImportFromDropbox** calls `circuitLoader.readCircuit(s)` directly
  (flags = 0, full replace). Unlike LoadFile it does not touch undo
  reseeding, tab management, title, or `allowSave`.

### Cross-layer notes

- `BaseCirSim.createNewLoadFile()` (:270) is the DOM-rebuild helper called
  by `LoadFile.doLoad` itself after each load — the "hack" for
  `<input type=file>` not firing ChangeEvent on identical re-selection.
  `CirSim.createNewLoadFile` (:697) adds window-title bookkeeping on top
  of the base behaviour.
- `CirSim.electronOpenFile()` (:631) wires the desktop file-open JS
  callback directly into `LoadFile.doLoadCallback`. This bypasses the
  FileReader JSNI entirely but uses the same Java-side orchestrator.
- `MenuManager.importFromLocalFileItem` (menu:140) is gated by
  `LoadFile.isSupported()`. On browsers without `File`+`FileReader`, the
  entire "Import from Local File" menu path is hidden.

---

## Issues

1. **Hard-coded size caps are duplicated and inconsistent.**
   LoadFile 128000 (:103), SRAMLoadFile 128000 (:39), ImportFromDropbox
   100000 (:44). No shared constant; inconsistency between 128 KB and
   100 KB has no documented rationale.

2. **`LoadFile.doLoad` error message is not localised.**
   `"File too large!"` (:104) is a raw JSNI `alert`, not piped through
   `Locale.LS`. `SRAMLoadFile` does localise via
   `EditDialogLoadFile.doErrorCallback` (Locale-wrapped).

3. **`sLoadFile` is a single global.**
   `LoadFile.java:30`/`:79` — the JSNI callback always targets the latest
   constructed instance. Combined with `createNewLoadFile()` rebuilding
   the widget on every load, a race between a pending async FileReader
   and a widget rebuild could deliver the text to a newer instance's
   `doLoad`. In practice `readAsText` is microtask-fast and no race has
   been observed, but the coupling is structural.

4. **`SRAMElm.contentsOverride` is a cross-instance static shuttle.**
   `SRAMLoadFile.java:58`/`SRAMElm.java:37` — if two SRAM elements were
   edited concurrently (hypothetical), the last `handleLoad` would win.
   The design relies on the dialog manager's modal-single assumption.

5. **`LoadFile.doLoad` mixes four concerns.**
   Load, document/tab lifecycle, undo reseed, focus restoration, and
   file-widget rebuild all live in a single method (:40–70). This makes
   it the de-facto orchestrator for the Open action. A move to
   `ActionManager` (matching `importCircuitFromText` at
   ActionManager.java:534) would bring LoadFile in line with the rest of
   the import channels.

6. **Duplicate text-area / JSNI payload code across paths.**
   `LoadFile.doLoad` (JSNI) and the Dropbox Chooser XHR both produce a
   `String` that flows to `readCircuit` — but each implements its own
   reader, error handling, and callback registration. A shared "acquire
   String from browser" helper would dedupe.

7. **`SRAMLoadFile` encoding is duplicated in SRAMElm.**
   The `"0: b0 b1 …"` syntax is produced in JSNI (SRAMLoadFile.java:46–49)
   and consumed by `SRAMElm.setChipEditValue` / `parseNumber` (with no
   shared grammar doc). Changing one without the other silently breaks.

8. **`getPath()` returns a sandbox-faked value on modern browsers.**
   `LoadFile.java:87`/`BaseCirSim.java:273` still logs and stores
   `filePath` from this native getter, but browser security rewrites it
   to `"C:\\fakepath\\filename"` or empty. The value is dead data in
   practice — retained only for the `log("filePath: ...")` trace and
   any legacy non-browser host.

9. **Dropbox callback divergence** (covered in dialog-import.md Issues §5):
   LoadFile calls `setUnsavedChanges(false)` + title/path reset,
   `ImportFromDropbox.doLoadCallback` calls neither, and
   `ImportFromDropboxDialog.doLoadCallback` calls `allowSave(false)`
   only. Three load channels with three different bookkeeping outcomes.

10. **No progress feedback / abort path.** All three channels either
    succeed silently or fail silently (Dropbox) / with a generic alert
    (LoadFile/SRAM). Large reads block the UI on the browser's
    FileReader or synchronous XHR with no indication to the user.

---

## Concept Boundary

Recommended: **fold into the existing `io-framework` concept as a
dedicated "browser-file-bridge" sub-section** rather than stand up a
separate concept.

Rationale:

- All three files are **string suppliers** for
  `CircuitLoader.readCircuit` (except SRAMLoadFile, which bridges to an
  element-local shuttle — a single exception). No circuit semantics
  live here.
- They share one cross-cutting invariant: *acquire a browser artefact →
  produce a `String` → hand to the io-framework (or an element's text
  surface)*. This is the classic adapter layer described by the
  "Format plugin contract" sub-section of `io-framework.md`.
- The import-dialog concept (`domain-core__dialog-import.md`) already
  covers the UI side; the file-io-glue is the non-UI counterpart
  (invisible `<input>` + JSNI). Splitting them keeps the dialog concept
  focused on UI layout while this sub-section documents the browser
  capability surface.
- `SRAMLoadFile` is the single anomaly — it serves an element, not the
  document. A short cross-link from the cat-complex-ics concept
  (`domain-core__cat-complex-ics.md` §215, §258, §301) handles that
  without needing its own concept.

Suggested structure inside the `io-framework` concept (or an adjacent
`browser-file-bridge` sub-concept if the framework doc grows too large):

1. **Text-circuit bridge** — `LoadFile` (FileReader `readAsText`, DOM
   rebuild quirk, undo reseed policy, Electron re-entry via
   `LoadFile.doLoadCallback`).
2. **Binary ROM bridge** — `SRAMLoadFile` (ArrayBuffer → ASCII line
   format, static shuttle handoff, dialog rebuild trigger). Cross-links
   to cat-complex-ics / dialog-edit sub-units.
3. **Remote-file bridge** — `ImportFromDropbox` (cross-reference only
   to dialog-import.md; no duplication).
4. **Cross-cutting acquisition protocol** — size caps, error-message
   localisation, `allowSave` + undo bookkeeping divergence between the
   three channels (Issue 9 above is the one-paragraph summary).

Alternative (not recommended): a standalone `file-io` concept. This
would over-fragment because the three classes share fewer than a
hundred lines of surface area and every consumer already reaches into
the io-framework concept for the real parsing rules.

---

## Summary (3 lines)

`file-io-glue` is a three-class browser-to-Java bridge: `LoadFile` (FileUpload + FileReader `readAsText` + 128 KB cap) is the sole entry from "File → Open" into `CircuitLoader.readCircuit`, orchestrating tab creation, undo-reseed (`resetAndSeedFromCurrentCircuit`), canvas refocus, filename bookkeeping, and a DOM-input rebuild to dodge the `<input type=file>` same-file ChangeEvent quirk; `SRAMLoadFile` extends `EditDialogLoadFile` to FileReader-`readAsArrayBuffer` a ROM image, serialise it as `"0: b0 b1 …"` decimal bytes, stash the string in the static `SRAMElm.contentsOverride` shuttle, and force `dialogManager.resetEditDialog()` so the rebuilt Edit Dialog re-reads it — the only file-acquisition path that bypasses the io-framework; `ImportFromDropbox` (already fully catalogued in `domain-core__dialog-import.md`) is the Dropbox Chooser JSNI ctor-invoked sibling, noted here only as cross-reference, and diverges from `LoadFile` on undo/tab/`allowSave` bookkeeping. Recommended boundary: fold into the `io-framework` concept as a "browser-file-bridge" sub-section with three bridge slots (text-circuit, binary ROM, remote Dropbox) and one cross-cutting acquisition-protocol note covering size-cap inconsistency (128 KB vs 100 KB), silent/unlocalised error handling, and divergent post-load bookkeeping.
