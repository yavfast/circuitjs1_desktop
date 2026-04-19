# Import/Export UI — Circuit I/O Dialog Surfaces  {#C_IEU}

> **Code:** C_IEU
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard-doc-gen
>
> **Depends on:** [C_EIC](./edit-info-contract.concept.md), [C_IOF](./io-framework.concept.md), [C_UTL](./util-locale-log.concept.md)
> **Used by:** — (will be filled by higher layers)
> **Spike:** —
> **Specification:** [SP_IEU](./import-export-ui.sp.md)
> **Plan:** [import-export-ui.plan.md](./import-export-ui.plan.md)
>
> Backing analyses: `.dev_flow/onboard/analysis/domain-core__dialog-export.md` (5 files) + `.dev_flow/onboard/analysis/domain-core__dialog-import.md` (2 dialog files + 1 root helper). 7 dialogs total.
>
> The GWT UI surface for file I/O — thin presenters over the `io-framework` plugin dispatch (`ActionManager.dumpCircuit(formatId)` / `CircuitLoader.readCircuit`). Five export dialogs (Text, JSON, URL, LocalFile, Image) and two import dialogs (Text paste, Dropbox) together form the whole user-facing file-io layer.

## 1. Philosophy  {#C_IEU_01}

### 1.1. Core Principle  {#C_IEU_01_01}

Dialogs in this concept contain **zero serialisation logic**. Payloads (text dump, JSON, PNG/SVG, compressed URL) are produced upstream by `ActionManager.dumpCircuit` or `CircuitRenderer.getCircuitAs{Canvas,SVG}`; the dialog wraps the payload in a GWT `TextArea`, download `Anchor`, or `data:` URL and offers Copy / Re-Import / Share actions. Import dialogs are string suppliers: they collect a payload (paste, XHR, Dropbox SDK) and hand it to `CircuitLoader.readCircuit`, which auto-detects format via `CircuitFormatRegistry.detectFormatOrDefault`.

### 1.2. Design Constraints  {#C_IEU_01_02}

- **Separation.** Dialogs know nothing of format parsing — they speak only strings / canvases. Format plug-ins live in `io/`.
- **Round-trip symmetry.** Text and JSON exports carry a Re-Import button that feeds the dialog's contents back through the same loader; image/URL are one-way.
- **Single active dialog.** `DialogManager` owns `activeDialog`; most dialogs are routed via factory methods. `ExportAsLocalFileDialog` is the outlier (see Issues).
- **Bookkeeping protocol.** Importers must `pushUndo()` before close, must null out filename/path, call `allowSave(false)`, reset window title.
- **Browser-glue via JSNI.** Clipboard copy, Blob URL, base64, LZString compression, Dropbox SDK chooser — all hand-rolled JSNI, ES5-compatible bodies. No modern `navigator.clipboard` / `FileReader`.

## 2. Domain Model  {#C_IEU_02}

### 2.1. Key Entities  {#C_IEU_02_01}

```
dialog/
  ExportAsTextDialog        — TextArea + Copy + Re-Import
  ExportAsJsonDialog        — TextArea + Copy + Re-Import  (near-duplicate of Text)
  ExportAsUrlDialog         — TextArea + Copy + optional shortrelay.php
  ExportAsLocalFileDialog   — Blob URL + hidden Anchor.click()
  ExportAsImageDialog       — PNG (Canvas.toDataUrl) or SVG (base64 via canvas2svg)
  ImportFromTextDialog      — TextArea paste → ActionManager.importCircuitFromText
  ImportFromDropboxDialog   — Dropbox.choose() chooser OR pasted-link XHR

client/
  ImportFromDropbox         — root-level JSNI chooser helper (shared state)
```

### 2.2. Data Flows  {#C_IEU_02_02}

**Export (text/json/url):**
```
ActionManager.doExportAs{Text,Json,Url}
  → dump = ActionManager.dumpCircuit([formatId])     # io-framework
  → DialogManager.showExportAs*Dialog(dump)
  → new ExportAs*Dialog(dump)
    → setWidget(VerticalPanel with TextArea)
    → buttons: OK / Copy (execCommand('copy')) / Re-Import (Text,Json only) / Short URL (Url only)
    → Re-Import: pushUndo + circuitLoader.readCircuit(s) + closeDialog + allowSave(false)
```

**Export (image):**
```
ActionManager.doExportAsImage | CirSim.doExportAsSVG
  → canvas = renderer.getCircuitAsCanvas(CAC_IMAGE) | renderer.getCircuitAsSVG()
  → new ExportAsImageDialog(type, canvas)
    → anchor = new Anchor("circuit-yyyyMMdd-HHmm.{png,svg}", dataUrl)  # PNG: toDataUrl; SVG: base64
```

**Export (local file):**
```
ActionManager.doExportAsLocalFile (bypasses DialogManager!)
  → dump = dumpCircuit()
  → new ExportAsLocalFileDialog(dump).show()
    → blobUrl = URL.createObjectURL(new Blob([dump]))   # JSNI
    → hidden Anchor with Download="circuitjs-yyyyMMdd-HHmmss.txt"
    → click(elem)  # programmatic JSNI click
```

**Import (text paste):**
```
Menu "Import from Text" → ActionManager → DialogManager.showImportFromTextDialog
  → new ImportFromTextDialog(sim).show()
    → user pastes into TextArea + optional "Load Subcircuits Only" checkbox
    → OK: pushUndo + closeDialog + actionManager.importCircuitFromText(s, subOnly)
      → flags = subOnly ? RC_SUBCIRCUITS|RC_RETAIN : 0
      → circuitLoader.readCircuit(s, flags)  # io-framework auto-detect
      → allowSave(false); filePath=null; fileName=null; changeWindowTitle(false)
```

**Import (Dropbox — currently dead at menu layer):**
```
[menu disabled — commented at ActionManager:243]
ImportFromDropboxDialog
  → branch on ImportFromDropbox.isSupported() (not Firefox + Dropbox SDK loaded)
    Yes: "Open Dropbox Chooser" button → new ImportFromDropbox(sim)
         → $wnd.Dropbox.choose({linkType:'direct', multiselect:false})
         → on success (size < 100 KB gate): XHR → doLoadCallback(text)
    No:  "Import From Dropbox Link" button
         → doImportDropboxLink(ta.getText(), true)
         → validate starts-with https://www.dropbox.com/
         → rewrite → dl.dropboxusercontent.com (CORS workaround)
         → synchronous XHR → doLoadCallback(text)
  → doLoadCallback(s): pushUndo + readCircuit(s) + allowSave(false)
```

## 3. Mechanisms  {#C_IEU_03}

### 3.1. Core Algorithm  {#C_IEU_03_01}

**Shared export skeleton (5 repeats, no base class):**
```
super Dialog()
vp = new VerticalPanel(); setWidget(vp)
setText(Locale.LS("Export as ..."))
vp.add(Label)
vp.add(body)   # TextArea | Anchor
hp = HorizontalPanel(topSpace styleName)
vp.add(hp)
hp.add(okButton → closeDialog)
# optional: hp.add(copyButton), hp.add(reimportButton), hp.add(shortButton)
center()
```

**Copy-to-clipboard handler (three identical repeats):** focus TextArea → selectAll → `execCommand('copy')` JSNI → clear selection. Deprecated; navigator.clipboard migration would require three edits.

**Re-Import (byte-identical in Text + JSON):** `sim.getActiveDocument().circuitEditor.pushUndo(); closeDialog(); circuitLoader.readCircuit(ta.getText()); sim.allowSave(false);`

**LocalFile Blob flow:** `getBlobUrl(data)` JSNI wraps `new Blob([data], {type:'text/plain'})`, produces `URL.createObjectURL`, revokes any previous Blob (stashed on `$doc.exportBlob`). Anchor.setAttribute("Download", fname). JSNI `click(elem)` triggers programmatic download.

**URL compression:** `compress(s)` JSNI = `$wnd.LZString.compressToEncodedURIComponent(s)`; result appended to `https://www.falstad.com/circuit/circuitjs.html?ctz=`. 2000-char warning label inlined.

**Dropbox size gate bug** (documented): the `if (files[0].bytes < 100000)` wraps only the `var xhr` declaration, but `xhr.open/send` sit outside the guard — large files NPE silently. See issues.

### 3.2. Edge Cases  {#C_IEU_03_02}

- **ExportAsLocalFileDialog bypasses DialogManager** (ActionManager:530). Not auto-dismissed by sibling flows.
- **No error handling on Re-Import** — null `s1` silently no-ops; `readCircuit` errors logged to console.
- **Short-URL error** overwrites the user's TextArea with HTTP status text — destructive.
- **closeOnEnter inconsistency** — set false in Text/JSON/URL; not in LocalFile (Enter in filename TextBox closes prematurely).
- **Single shared DOM id** `"EditDialogLoadFileElement"` — two loadFile rows collide.
- **Synchronous XHR** — deprecated on main thread; browsers may disable.
- **Static `sim` on `ImportFromDropboxDialog`** — two instances overwrite; modal assumption holds today.
- **Dropbox size gate** — ≥100 KB silently fails (scope-bug in JSNI closure).
- **ImportFromDropboxDialog is dead at menu layer** — opener commented out at ActionManager:243. Still constructable.
- **Filename prefix drift** — `"circuit-"` (image) vs `"circuitjs-"` (local file); memoisation only skips the latter.

## 4. Integration Points  {#C_IEU_04}

### 4.1. Dependencies  {#C_IEU_04_01}

- **[C_EIC](./edit-info-contract.concept.md)** — all dialogs extend `dialog.Dialog` (closeDialog, closeOnEnter, getOptionPrefix).
- **[C_IOF](./io-framework.concept.md)** — payload source (`ActionManager.dumpCircuit`) and sink (`CircuitLoader.readCircuit` → `CircuitFormatRegistry.detectFormatOrDefault`). Image exports bypass io-framework and go through `CircuitRenderer`.
- **[C_UTL](./util-locale-log.concept.md)** — `Locale.LS` for all user-visible strings (except a few Dropbox error messages).
- **Root integrators** — `CirSim` (renderer, `doExportAsSVG`, `initializeSVGScriptIfNecessary`), `DialogManager` (factory methods for 4 of 5 exports + 1 of 2 imports), `ActionManager` (menu dispatch, `dumpCircuit`, `importCircuitFromText` bookkeeping).
- **Window globals** — `LZString` (URL), `canvas2svg` (image/SVG, lazy-loaded via `CirSim.initializeSVGScriptIfNecessary`), `Dropbox` (chooser SDK).

### 4.2. API Surface  {#C_IEU_04_02}

- `DialogManager.showExportAs{Text,Json,Url,Image}Dialog(...)` — dismiss-previous + show factory.
- `DialogManager.showImportFromTextDialog()` — single factory for text-paste import.
- `EditDialog.resetDialog()` integration — `SRAMLoadFile` refresh after load.
- `ImportFromDropbox.isSupported()` — feature detect.
- `EditDialogLoadFile` — for element-level file-loading rows (covered in C_EIC).
- `ExportAsLocalFileDialog.downloadIsSupported()` — JSNI feature-detect (dead code; only commented reference at `MenuManager:156`).
- Protocol contract for importers: `pushUndo → closeDialog → readCircuit → allowSave(false) → filePath=null → fileName=null → changeWindowTitle(false)`.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
