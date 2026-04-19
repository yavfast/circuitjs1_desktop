# Import/Export UI — Specification  {#SP_IEU}

> **Code:** SP_IEU
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_IEU](./import-export-ui.concept.md)
> **Depends on specs:** [SP_EIC](./edit-info-contract.sp.md), [SP_IOF](./io-framework.sp.md), [SP_UTL](./util-locale-log.sp.md)
> **Used by specs:** — (will be filled by higher layers)
> **Plan:** [import-export-ui.plan.md](./import-export-ui.plan.md)
>
> Backing analyses: `.dev_flow/onboard/analysis/domain-core__dialog-export.md` + `domain-core__dialog-import.md`.
>
> Defines the 7 import/export dialog classes (+ 1 root JSNI helper), their payload sinks/sources, JSNI bridges, and the undo/title/filename protocol every importer must follow.

## 01. Data Structures  {#SP_IEU_01}

> Implements: [C_IEU_02](./import-export-ui.concept.md#C_IEU_02)

### 01_01. Export-dialog catalog  {#SP_IEU_01_01}

| Dialog | Format / payload | Presentation sink | Exporter (upstream) | JSNI bridges | File:lines |
|--------|------------------|-------------------|---------------------|--------------|------------|
| `ExportAsTextDialog` | Legacy Falstad text | TextArea 400×300 + OK + Copy + Re-Import | `TextCircuitExporter` via `ActionManager.dumpCircuit()` | `copyToClipboard()` | `ExportAsTextDialog.java:38-89` |
| `ExportAsJsonDialog` | JSON | TextArea 500×400 + OK + Copy + Re-Import | `JsonCircuitExporter` via `ActionManager.dumpCircuit("json")` | `copyToClipboard()` | `ExportAsJsonDialog.java:42-94` |
| `ExportAsUrlDialog` | `https://www.falstad.com/circuit/circuitjs.html?ctz=<LZ>` | TextArea 400×300 + OK + Copy + optional Short URL | `ActionManager.dumpCircuit()` + `LZString.compressToEncodedURIComponent` | `copyToClipboard()`, `compress(String)`, `shortrelay.php` via GWT `RequestBuilder` | `ExportAsUrlDialog.java:91-150` |
| `ExportAsLocalFileDialog` | Text (always) | hidden Anchor + `click()`, `Blob({type:'text/plain'})`, `URL.createObjectURL` | `ActionManager.dumpCircuit()` (default) | `downloadIsSupported()`, `getBlobUrl(String)`, `click(Element)` | `ExportAsLocalFileDialog.java:72-128` |
| `ExportAsImageDialog` | PNG (`CAC_IMAGE`) or SVG (`CAC_SVG`) | Anchor Download="circuit-yyyyMMdd-HHmm.{png,svg}" | `CircuitRenderer.getCircuitAsCanvas(type)` (PNG) / `getCircuitAsSVG()` (SVG) | `b64encode(String)` (Unicode-safe btoa) | `ExportAsImageDialog.java:44-75` |

### 01_02. Import-dialog catalog  {#SP_IEU_01_02}

| Dialog | Acquisition channel | Data sink | JSNI bridges | File:lines |
|--------|---------------------|-----------|--------------|------------|
| `ImportFromTextDialog` | TextArea paste + `Load Subcircuits Only` checkbox | `ActionManager.importCircuitFromText(s, subOnly)` → `circuitLoader.readCircuit(s, flags)` | — (pure GWT) | `ImportFromTextDialog.java:33-78` |
| `ImportFromDropboxDialog` | Dropbox Chooser SDK OR pasted-link rewritten to `dl.dropboxusercontent.com` | `circuitLoader.readCircuit(s)` via static `doLoadCallback` | `doDropboxImport(String)` (sync XHR) | `ImportFromDropboxDialog.java:16-121` |
| `ImportFromDropbox` (root) | `$wnd.Dropbox.choose` chooser | `readCircuit` via static `doLoadCallback` | `doDropboxImport()` (Dropbox SDK call), `isSupported()` (Firefox/Dropbox UA check) | `ImportFromDropbox.java:15-81` |

### 01_03. JSNI bridges (full list)  {#SP_IEU_01_03}

| JSNI native | File:line | Implementation |
|-------------|-----------|----------------|
| `b64encode(a)` | `ExportAsImageDialog.java:39-42` | `window.btoa(unescape(encodeURIComponent(a)))` — Unicode-safe |
| `downloadIsSupported()` | `ExportAsLocalFileDialog.java:41-44` | `"download" in $doc.createElement("a")` (**unused at runtime**) |
| `getBlobUrl(data)` | `ExportAsLocalFileDialog.java:46-57` | revoke prev `$doc.exportBlob`; `new Blob([data],{type:'text/plain'})`; `URL.createObjectURL` |
| `click(elem)` | `ExportAsLocalFileDialog.java:115-117` | `elem.click()` |
| `copyToClipboard()` ×3 | Json/Text/Url | `$doc.execCommand('copy')` (deprecated) |
| `compress(dump)` | `ExportAsUrlDialog.java:87-89` | `$wnd.LZString.compressToEncodedURIComponent(dump)` |
| `doDropboxImport(link)` | `ImportFromDropboxDialog.java:41-56` | synchronous `XMLHttpRequest`; on load → `doLoadCallback(text)` |
| `doDropboxImport()` (root) | `ImportFromDropbox.java:36-81` | `$wnd.Dropbox.choose({linkType:'direct', multiselect:false, success:...})` |
| `isSupported()` | `ImportFromDropbox.java:15-28` | !Firefox UA + `$wnd.Dropbox.isBrowserSupported()` |

### 01_04. Protocol constants  {#SP_IEU_01_04}

- `ActionManager.importCircuitFromText` flag bits: `RC_RETAIN = 1`, `RC_SUBCIRCUITS = 2`. Combined = 3 (merge subcircuits only into live document).
- Image filename prefix: `"circuit-"`; timestamp `yyyyMMdd-HHmm`.
- Local-file prefix: `"circuitjs-"`; timestamp `yyyyMMdd-HHmmss`.
- URL template: `https://www.falstad.com/circuit/circuitjs.html?ctz=<compressed>` (2000-char UX limit).
- Short-URL relay: `shortrelay.php` (relative URL; 404s on self-host / desktop build).
- Dropbox chooser size gate: `files[0].bytes < 100000` (~100 KB).

## 02. Contracts  {#SP_IEU_02}

### 02_01. Export (text/json/url) — shared skeleton  {#SP_IEU_02_01}

Purpose: wrap a pre-produced string in a TextArea with OK + Copy and optional Re-Import.

Input: `dump: String` (produced by `ActionManager.dumpCircuit[formatId]`).

Processing:
```
super(Dialog)  # closeOnEnter = false
vp = VerticalPanel; setWidget(vp); setText(Locale.LS(title))
ta = TextArea(size); ta.setText(dump); vp.add(ta)
hp = HorizontalPanel(topSpace); vp.add(hp)
hp.add(OK → closeDialog)
hp.add(Copy → focus+selectAll+copyToClipboard+clearSelection)
IF text|json: hp.add(Re-Import → pushUndo+closeDialog+readCircuit(ta.getText())+allowSave(false))
IF url AND shortRelaySupported: hp.add(Short URL → RequestBuilder GET shortrelay.php?v=<url>)
center()
```

### 02_02. Export (image)  {#SP_IEU_02_02}

Input: `type ∈ {CAC_IMAGE, CAC_SVG}`, `canvas` from renderer.

Processing:
```
IF type == CAC_IMAGE:
    dataUrl = canvas.toDataUrl()   # GWT Canvas wrapper
    ext = "png"
ELSE:
    svg = renderer.getCircuitAsSVG()
    dataUrl = "data:text/plain;base64," + b64encode(svg)
    ext = "svg"
fname = "circuit-" + yyyyMMdd_HHmm + "." + ext
anchor = new Anchor(fname, dataUrl); anchor.setAttribute("Download", fname)
vp.add(anchor)   # user clicks to download
```

### 02_03. Export (local file)  {#SP_IEU_02_03}

```
blobUrl = getBlobUrl(dump)                                 # JSNI
fname = filenameField.getText() or "circuitjs-" + ts + ".txt"
hiddenAnchor.setHref(blobUrl); setAttribute("Download", fname)
vp.add(hiddenAnchor)                                       # offscreen
click(hiddenAnchor.getElement())                           # JSNI synthetic click
IF fname starts with "circuitjs-": skip setLastFileName    # don't memoise defaults
```

### 02_04. Import (text paste)  {#SP_IEU_02_04}

```
ta = TextArea(300,200); subCheck = Checkbox("Load Subcircuits Only")
hp = {OK, Cancel}
OK: pushUndo; closeDialog; s = ta.getText(); actionManager.importCircuitFromText(s, subCheck.getState())
  → flags = subOnly ? (RC_SUBCIRCUITS|RC_RETAIN) : 0
  → circuitLoader.readCircuit(s, flags)    # io-framework auto-detect
  → allowSave(false); circuitInfo.filePath=null; fileName=null; changeWindowTitle(false)
```

### 02_05. Import (Dropbox)  {#SP_IEU_02_05}

Branch on `ImportFromDropbox.isSupported()`:

```
IF supported:
    add "Open Dropbox Chooser" Button
    click: closeDialog; importFromDropbox = new ImportFromDropbox(sim)
           → JSNI $wnd.Dropbox.choose(options)
           → on success (size gate): XHR GET files[0].link
           → onload: ImportFromDropbox.doLoadCallback(responseText)
           → doLoadCallback: pushUndo + readCircuit(s)   # NOTE: no allowSave(false)
ELSE:
    add Label("chooser unavailable") + TextArea + "Import From Dropbox Link" Button
    click: doImportDropboxLink(ta.getText(), true)
           → validate startsWith "https://www.dropbox.com/" (English-only alert)
           → rewrite to dl.dropboxusercontent.com
           → doDropboxImport(link) JSNI sync XHR
           → onload: ImportFromDropboxDialog.doLoadCallback(responseText)
             → pushUndo + readCircuit(s) + allowSave(false)
```

### 02_06. Re-Import contract (Text + JSON)  {#SP_IEU_02_06}

Input: text in the dialog's TextArea.

Processing (byte-identical in the two dialogs):
```
s1 = ta.getText()
IF s1 == null: return   # silent no-op
sim.getActiveDocument().circuitEditor.pushUndo()
closeDialog()
sim.getActiveDocument().circuitLoader.readCircuit(s1)   # single-arg auto-detect
sim.allowSave(false)
```

## 03. Validation Rules  {#SP_IEU_03}

### 03_01. Input Validation  {#SP_IEU_03_01}

- Dropbox link must start with `https://www.dropbox.com/` (hard-coded English `Window.alert`).
- Dropbox chooser size gate ≤100 KB (silent discard; chooser bug — xhr.open runs anyway and throws, caught silently).
- Re-Import silently skips null `s1`.
- Parse errors inside `readCircuit` are logged to console, not surfaced.
- Short-URL HTTP error text overwrites the user's TextArea (destructive; no retry).
- `ExportAsLocalFileDialog.downloadIsSupported()` is declared but unused (only commented reference at `MenuManager.java:156`).
- Filename TextBox edits are not validated; memoisation guard `setLastFileName` only skips `"circuitjs-"`-prefixed names (not `"circuit-"`).
- Dropbox synchronous XHR — may be hard-disabled in modern browsers.

## 04. State Transitions  {#SP_IEU_04}

### 04_01. Import lifecycle  {#SP_IEU_04_01}

```
idle ──menu action──▶ dialog open ──user paste/chooser──▶ payload captured
                                │                             │
                                │                             ▼
                                │                    pushUndo; closeDialog
                                │                             │
                                │                             ▼
                                │                    readCircuit (auto-detect)
                                │                             │
                                │                 ┌─ text path (via ActionManager)
                                │                 │    └─ allowSave(false); filePath=null; title reset
                                │                 │
                                │                 └─ Dropbox path
                                │                      ├─ dialog callback: allowSave(false) ONLY
                                │                      └─ root callback:   neither (divergence)
                                ▼
                          Cancel → closeDialog (no undo push; dialog discarded)
```

### 04_02. Export lifecycle  {#SP_IEU_04_02}

```
idle ──menu action──▶ dumpCircuit(formatId) ──▶ dialog open with payload
                                                 │
                                                 ├─ text/json/url: TextArea + Copy + [Re-Import|Short URL]
                                                 ├─ image: Anchor with Download (user clicks)
                                                 └─ localfile: programmatic click → browser download
                                                 │
                                                 ▼
                                              OK/Close → closeDialog
```

## 05. Verification Criteria  {#SP_IEU_05}

### 05_01. Functional Expectations  {#SP_IEU_05_01}

| Contract | Scenario | Input | Expected |
|----------|----------|-------|----------|
| Text round-trip | Export text → Re-Import | current circuit | identical circuit after re-import |
| JSON round-trip | Export JSON → Re-Import | current circuit | identical circuit (auto-detect → JSON importer) |
| URL export | typical circuit | — | `?ctz=` URL with < 2000 chars; compression lossless |
| PNG export | any | CAC_IMAGE | `data:image/png;base64,...` anchor with Download attribute |
| SVG export | any | CAC_SVG (canvas2svg loaded) | base64 SVG anchor; filename `circuit-yyyyMMdd-HHmm.svg` |
| LocalFile export | text payload | — | browser triggers .txt download; Blob URL revokes previous |
| Import from Text | pasted legacy dump, subOnly=false | — | replaces current circuit; `allowSave(false)` |
| Import from Text, subOnly=true | pasted `.` lines | — | merges subcircuit definitions; existing elements retained |
| Dropbox unsupported | Firefox | isSupported()==false | chooser button absent; link form shown |

### 05_02. Invariant Checks  {#SP_IEU_05_02}

| Invariant | Verification |
|-----------|--------------|
| Importers push exactly one undo entry | test: paste multi-element circuit; undo once → empty |
| `closeOnEnter` set false on TextArea dialogs | Text/JSON/URL/Import From Text |
| Single active DialogManager slot | 4 export + 1 import routed through factories; LocalFile bypass documented |
| Re-Import handler identical in Text + JSON | diff `ExportAsJsonDialog.java:72-84` vs `ExportAsTextDialog.java:68-79` |
| Copy handler identical in Text/JSON/URL | diff confirms five-line duplication |

### 05_03. Integration Scenarios  {#SP_IEU_05_03}

| Scenario | Preconditions | Steps | Expected |
|----------|---------------|-------|----------|
| Share via URL shortener | `circuitjs1.shortRelaySupported=true` | export URL → click Short URL | `shortrelay.php?v=<url>` GET; response replaces TextArea |
| SVG export | canvas2svg not loaded | click SVG menu → `CirSim.doExportAsSVG` | lazy-loads canvas2svg.js, then opens dialog |
| Import JSON via Text dialog | paste JSON blob starting `{` | OK | `CircuitFormatRegistry.detectFormatOrDefault` returns JSON; parsed as JSON |
| LocalFile re-download | open twice | second open | `URL.revokeObjectURL` cleans previous Blob |

### 05_04. Edge Cases  {#SP_IEU_05_04}

| Case | Input | Expected |
|------|-------|----------|
| Dropbox >100 KB | large file via chooser | silent failure (size-gate scope bug) |
| Short URL 404 | self-host without shortrelay.php | error text replaces URL in TextArea |
| Enter in LocalFile filename TextBox | typed filename | dialog closes prematurely (closeOnEnter not disabled — bug) |
| Two loadFile rows same dialog | hypothetical | `document.getElementById("EditDialogLoadFileElement")` returns first; second inert |
| Dropbox dialog opened | from commented bootstrap only | menu dead — unreachable in stock build |

## 06. Constants  {#SP_IEU_06}

- `ActionManager.RC_RETAIN = 1`, `RC_SUBCIRCUITS = 2`.
- `CirSim.CAC_IMAGE`, `CirSim.CAC_SVG` (image dialog mode).
- `SliderDialog.TEXTAREA_JSON = 500×400`, `TEXT = 400×300`, `URL = 400×300`, `IMPORT_FROM_TEXT = 300×200`, `DROPBOX = 300×200` (per-dialog pixel dims).
- EditDialogLoadFile DOM id: `"EditDialogLoadFileElement"` (inherited from C_EIC).
- Dropbox size gate: 100000 bytes.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
