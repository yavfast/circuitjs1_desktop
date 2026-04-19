# Sub-unit Analysis: dialog-export

> **Path:** `src/main/java/com/lushprojects/circuitjs1/client/dialog/`
>   *(NB: the onboard brief lists these under `element/dialog/`; the actual
>   package is `client/dialog/`. The `dialog/` tree is a sibling of `element/`,
>   not a child.)*
> **Layer:** UI (presentation) — sits above `io-framework` and `CircuitRenderer`.
> **Analyzed:** 2026-04-18
> **Files (5):** 556 LOC total
>   - `ExportAsImageDialog.java` (76 LOC)
>   - `ExportAsJsonDialog.java` (100 LOC)
>   - `ExportAsLocalFileDialog.java` (129 LOC)
>   - `ExportAsTextDialog.java` (95 LOC)
>   - `ExportAsUrlDialog.java` (156 LOC)

## Purpose

Five GWT dialog widgets that are the front-end surface for circuit-export
actions. Each takes an already-produced payload (text, JSON, or raster/SVG
image) and presents it to the user via a GWT `TextArea`, GWT `Anchor` with
`Download` attribute, or `data:` URL. The payload itself is produced upstream
by `ActionManager.dumpCircuit(formatId)` (`ActionManager.java:500-517, 528-532`)
— which routes through `CircuitFormatRegistry` (see `io-framework.md`) — and
for images by `CircuitRenderer.getCircuitAsCanvas()` / `getCircuitAsSVG()`
(`CircuitRenderer.java:739, 757`). The dialogs themselves contain no
serialisation logic; they are pure result-presenters plus a handful of
browser-API bridges (Blob URL, clipboard, base64, LZString).

Three of the five also offer re-import or share actions, making the layer
bidirectional for the text/JSON formats:

- `ExportAsTextDialog` / `ExportAsJsonDialog` → Re-Import button round-trips
  through `circuitDocument.circuitLoader.readCircuit(s1)` (same one-argument
  auto-detect call both dialogs use — the "JSON auto-detect" comment in
  `ExportAsJsonDialog.java:80` matches the `detectFormatOrDefault` contract
  in `io-framework.md`).
- `ExportAsUrlDialog` → optional `shortrelay.php` hit for URL shortening.

---

## Per-dialog catalog

| Dialog | Format / payload | Presentation sink | Exporter / upstream source | File:lines |
|---|---|---|---|---|
| `ExportAsImageDialog` | PNG (`CAC_IMAGE`) or SVG (`CAC_SVG`) | GWT `Anchor` with `Download="circuit-<yyyyMMdd-HHmm>.{png,svg}"`; PNG uses `canvas.toDataUrl()`, SVG uses `data:text/plain;base64,<b64>` | `CirSim.renderer.getCircuitAsCanvas(type)` (PNG) / `getCircuitAsSVG()` (SVG) — **not** the io-framework, these are renderer methods | `ExportAsImageDialog.java:44-75` (PNG branch :57-58, SVG branch :59-63) |
| `ExportAsJsonDialog` | JSON string (from `dumpCircuit("json")`) | `TextArea` 500×400 + OK / Copy to Clipboard / Re-Import buttons | `JsonCircuitExporter` via `ActionManager.dumpCircuit("json")` → `ActionManager.java:510-513` | `ExportAsJsonDialog.java:42-94` |
| `ExportAsLocalFileDialog` | Opaque text string (currently always legacy text dump — caller at `ActionManager.java:528-532` uses `dumpCircuit()` with no formatId) | Hidden `Anchor` + programmatic `.click()`; payload wrapped in `Blob({type: 'text/plain'})`, served via `URL.createObjectURL`; previous Blob revoked via `URL.revokeObjectURL` | `TextCircuitExporter` via default `ActionManager.dumpCircuit()` | `ExportAsLocalFileDialog.java:72-128` (Blob code :46-57, click-apply :119-128) |
| `ExportAsTextDialog` | Legacy Falstad text dump | `TextArea` 400×300 + OK / Copy to Clipboard / Re-Import buttons | `TextCircuitExporter` via `ActionManager.dumpCircuit()` → `:505-508` | `ExportAsTextDialog.java:38-89` |
| `ExportAsUrlDialog` | `https://www.falstad.com/circuit/circuitjs.html?ctz=<LZString>` | `TextArea` 400×300 + OK / Copy to Clipboard / optional Create short URL; 2000-char warning label inlined | `TextCircuitExporter` via `ActionManager.dumpCircuit()`, then compressed with `LZString.compressToEncodedURIComponent` (window global) | `ExportAsUrlDialog.java:91-150` (compress :87-89, short relay :58-85) |

Every dialog extends `Dialog` (local `client/dialog/Dialog.java`) and uses the
same `VerticalPanel setWidget` + `setText(Locale.LS(...))` + `this.center()`
idiom. Three of them (`Text`, `Json`, `Url`) set `closeOnEnter = false` to
allow multi-line text entry/pasting.

### Wiring from caller

- All three of `showExportAs{Text,Json,Url,Image}Dialog` live on `DialogManager`
  (`DialogManager.java:121-140`) and follow the same pattern:
  `dismissActiveDialog(); activeDialog = new ExportAs…Dialog(...); activeDialog.show();`.
- `ExportAsLocalFileDialog` is the **odd one out** — `ActionManager.java:530`
  constructs it directly and calls `.show()` without registering it on
  `DialogManager.activeDialog`. That breaks the "one active dialog"
  invariant other exports follow.
- SVG path is reached via `CirSim.doExportAsSVG()` (`CirSim.java:817-822`),
  which lazy-loads `canvas2svg.js` before instantiating the dialog. JSON
  export uses the format registry through `dumpCircuit("json")`; the other
  two text-shaped dialogs (`Text`, `Url`) rely on the default
  (text-format) dump.

---

## Shared patterns vs duplication

### Shared (implicit) pattern

All five follow the same skeleton:

```java
super();
vp = new VerticalPanel();
setWidget(vp);
setText(Locale.LS("Export as …"));
vp.add(new Label(Locale.LS("…")));
// body (TextArea / Anchor)
HorizontalPanel hp = new HorizontalPanel();
hp.setWidth("100%");
hp.setHorizontalAlignment(ALIGN_LEFT);
hp.setStyleName("topSpace");
vp.add(hp);
hp.add(okButton = new Button(Locale.LS("OK")));
// optional extra buttons on the right
okButton.addClickHandler(event -> closeDialog());
this.center();
```

No base class or helper extracts this — every dialog repeats it verbatim. The
`topSpace` CSS style is referenced as a string literal in 4 of 5 files
(`ExportAsImageDialog` skips it because it has no `HorizontalPanel`).

### Copy-paste duplication

1. **`copyToClipboard()` JSNI.** Three dialogs each declare their own private
   static native:

   - `ExportAsJsonDialog.java:96-98`
   - `ExportAsTextDialog.java:91-93`
   - `ExportAsUrlDialog.java:152-154`

   All three bodies are identical: `return $doc.execCommand('copy');`. No
   shared helper is used, despite `CirSim.clipboardWriteImage` already existing
   (used by `ActionManager.doImageToClipboard` at `ActionManager.java:521`).
   The `execCommand('copy')` API is also deprecated in modern browsers —
   migration to `navigator.clipboard.writeText` would need to happen in three
   places.

2. **Copy-button click handler.** The Text, JSON, and URL dialogs all run the
   *exact* same five-line handler (focus, selectAll, copyToClipboard, clear
   selection). Identical across `ExportAsJsonDialog.java:86-91`,
   `ExportAsTextDialog.java:81-86`, `ExportAsUrlDialog.java:141-148`.

3. **Re-Import handler.** `ExportAsJsonDialog.java:72-84` and
   `ExportAsTextDialog.java:68-79` are **byte-identical** except for the
   surrounding class (same `pushUndo`, `closeDialog`, `readCircuit`,
   `allowSave(false)` sequence; same lack of error handling on a null
   `s1`).

4. **Button layout.** The Text/JSON pair (`ExportAsJsonDialog.java:59-68` vs.
   `ExportAsTextDialog.java:55-64`) duplicate the same three-button horizontal
   panel construction line-for-line; the URL dialog (:118-135) is a near-clone
   with an optional `shortButton` appended.

5. **Download-anchor trick.** `ExportAsImageDialog.java:64-66` and
   `ExportAsLocalFileDialog.java:124-127` both do `new Anchor(name, url)`,
   `setAttribute("Download", fname)`, add to panel. The image dialog leaves
   the user to click the anchor, the local-file dialog triggers it
   programmatically via the JSNI `click(elem)` (:115-117). No shared helper.

6. **File-name / timestamp construction.** `ExportAsImageDialog.java:53-54,65`
   (`yyyyMMdd-HHmm`) and `ExportAsLocalFileDialog.java:83,88-89`
   (`yyyyMMdd-HHmmss`) each instantiate their own `DateTimeFormat` with almost
   (but not quite — different second precision) the same pattern. Prefix also
   drifts: `"circuit-"` vs. `"circuitjs-"`.

Total duplication bill: ~35 lines of identical or near-identical code across
the set, most of it concentrated in the Text/JSON/URL triple. A
`TextExportDialog` abstract base or a small `DialogButtons` helper would
absorb the entire duplication cluster without visible behaviour change.

---

## JSNI usage for browser APIs

Five distinct JSNI bridges across these files:

| Native | File:lines | Purpose | API used |
|---|---|---|---|
| `b64encode(String)` | `ExportAsImageDialog.java:39-42` | Unicode-safe base64 for the SVG `data:text/plain;base64,…` anchor | `window.btoa(unescape(encodeURIComponent(a)))` (documented workaround for btoa's latin1-only limitation) |
| `downloadIsSupported()` | `ExportAsLocalFileDialog.java:41-44` | Feature-detect HTML5 `<a download>` | `"download" in $doc.createElement("a")`. **Unused at runtime** — the only reference in `MenuManager.java:156` is commented out. |
| `getBlobUrl(String)` | `ExportAsLocalFileDialog.java:46-57` | Wrap text in a `Blob` and produce an object URL; revoke the previous one (stashed on `$doc.exportBlob`) | `new Blob([data], {type: 'text/plain'})`, `URL.createObjectURL`, `URL.revokeObjectURL` |
| `click(Element)` | `ExportAsLocalFileDialog.java:115-117` | Programmatically click the hidden download anchor | `elem.click()` |
| `copyToClipboard()` ×3 | `ExportAsJsonDialog.java:96-98`, `ExportAsTextDialog.java:91-93`, `ExportAsUrlDialog.java:152-154` | Issue a Clipboard copy of the currently-selected TextArea contents | `$doc.execCommand('copy')` — deprecated |
| `compress(String)` | `ExportAsUrlDialog.java:87-89` | LZ77-style URL compression for the `?ctz=…` query parameter | `$wnd.LZString.compressToEncodedURIComponent(dump)` — requires `lzw.min.js` / `lzw.js` loaded globally |

Other browser APIs used via **non-JSNI** GWT wrappers:

- `Canvas.toDataUrl()` (`ExportAsImageDialog.java:58`) — GWT's `Canvas`
  wrapper exposes the DOM `HTMLCanvasElement.toDataURL()` directly.
- `RequestBuilder` for the short-URL relay (`ExportAsUrlDialog.java:62-84`) —
  GWT XHR wrapper calling `shortrelay.php?v=<url>`. No CORS / timeout
  handling; error text is shown directly in the TextArea.

No `FileReader`, no `navigator.clipboard`, no modern download library — all
browser-glue is hand-rolled JSNI with ES5-compatible bodies.

---

## Integration points

### Depends on

- `dialog.Dialog` (local base, same package) — inherits `closeDialog()`,
  `closeOnEnter`, `center()`, `setWidget`, `setText` (GWT DialogBox).
- `CirSim` — referenced by `ExportAsImageDialog` (for the renderer and the
  `CAC_IMAGE` / `CAC_SVG` int constants) and by the text/JSON dialogs (for
  `getActiveDocument()` and `allowSave(false)`).
- `CircuitDocument` — Re-Import path only (Text + JSON).
- `CircuitRenderer.getCircuitAsCanvas(int)` / `getCircuitAsSVG()`
  (`CircuitRenderer.java:739, 757`) — image payload source.
- `CircuitLoader.readCircuit(String)` — Re-Import path only; it delegates to
  `CircuitFormatRegistry.detectFormatOrDefault` so Text and JSON share the
  same single-arg method (the JSON dialog's comment at :80 is accurate).
- `com.lushprojects.circuitjs1.client.util.Locale` — all UI strings are
  wrapped in `Locale.LS`.
- `circuitjs1.shortRelaySupported` (static flag) — gates the Create-short-URL
  button (`ExportAsUrlDialog.java:47-49`).
- Window globals: `LZString` (URL dialog) and `canvas2svg` (image/SVG dialog,
  loaded lazily by `CirSim.initializeSVGScriptIfNecessary`
  at `CirSim.java:795-815`).

### Used by

| Caller | File | Entry |
|---|---|---|
| `ActionManager.doExportAsText` | `ActionManager.java:505-508` | → `ExportAsTextDialog` |
| `ActionManager.doExportAsJson` | `ActionManager.java:510-513` | → `ExportAsJsonDialog` (dumps with `"json"` formatId) |
| `ActionManager.doExportAsUrl` | `ActionManager.java:500-503` | → `ExportAsUrlDialog` |
| `ActionManager.doExportAsImage` | `ActionManager.java:515-517` | → `ExportAsImageDialog(CAC_IMAGE)` |
| `ActionManager.doExportAsLocalFile` | `ActionManager.java:528-532` | **direct** `new ExportAsLocalFileDialog(dump).show()` (bypasses DialogManager) |
| `CirSim.doExportAsSVG` | `CirSim.java:817-822` | → `ExportAsImageDialog(CAC_SVG)` after injecting canvas2svg |
| `DialogManager.showExportAs{Text,Json,Url,Image}Dialog` | `DialogManager.java:121-140` | thin "dismiss previous + show" wrapper for 4 of the 5 |
| `MenuManager` | `MenuManager.java:156` | commented-out `downloadIsSupported()` gating (dead code) |

### Relationship to io-framework

- JSON and text string payloads flow **through** `io-framework` — dialogs are
  downstream consumers via `ActionManager.dumpCircuit(formatId)`. See
  `io-framework.md#used-by-outside-io` for the corresponding export paths.
- Image/SVG payloads **do not** go through `io-framework` — they are rendered
  directly to a GWT `Canvas` / canvas2svg context by `CircuitRenderer`. Image
  export is therefore orthogonal to the format-plugin mechanism.
- The Re-Import buttons are the only UI widgets that call back *into*
  `io-framework` (via `CircuitLoader.readCircuit`), which makes these two
  dialogs the only round-trip-capable export UIs. The `Url` dialog deliberately
  does **not** implement Re-Import — the sharing path is "copy URL into
  another browser" rather than "paste URL here to re-parse".

---

## Issues

1. **Package-location drift.** The onboard brief locates these files under
   `element/dialog/`; they are actually at `client/dialog/`. Worth flagging in
   any sub-unit index / queue.yaml to prevent mis-pathed grep queries.

2. **Three separate `copyToClipboard` JSNI declarations.** All identical,
   all using the deprecated `document.execCommand('copy')`. A single shared
   helper (e.g. on `Dialog`, `CirSim`, or a new `DialogUtils`) would cut the
   duplication and make the eventual `navigator.clipboard` migration a
   one-line change instead of three.

3. **`ExportAsLocalFileDialog` bypasses `DialogManager`.** `ActionManager`
   creates it directly (`ActionManager.java:530`) instead of going through
   `dialogManager().showExportAsLocalFileDialog(dump)`. This makes it the one
   export dialog that won't be auto-dismissed by the
   `dismissActiveDialog()` call in other flows and won't participate in any
   future "close all dialogs" feature on `DialogManager`. Trivial fix — add
   a method on `DialogManager` symmetric to the other four.

4. **Dead feature-detection.** `ExportAsLocalFileDialog.downloadIsSupported()`
   is only referenced from a commented-out line in
   `MenuManager.java:156`. Either wire it up again (useful for very old
   browsers) or delete the JSNI.

5. **Text ↔ JSON dialog near-duplication.** The two dialogs are effectively
   the same class parameterised by (title, field size, optional format-id). A
   shared abstract `TextualExportDialog` would remove ~60 duplicated lines
   (including the Re-Import and Copy-to-Clipboard handlers). The file
   header on `ExportAsJsonDialog.java:33-35` even acknowledges this
   ("Similar to ExportAsTextDialog but uses JSON format.").

6. **Inconsistent file-name prefixes.** `"circuit-<timestamp>.png|svg"` from
   the image dialog vs. `"circuitjs-<timestamp>.txt"` from the local-file
   dialog. The `setLastFileName` logic in
   `ExportAsLocalFileDialog.java:63-70` has a special case to "forget" a
   filename when it starts with `"circuitjs-"` — implying auto-generated
   names should not be remembered — but it does nothing for the `"circuit-"`
   image prefix (which is fine because the image dialog never calls
   `setLastFileName`, but the asymmetry makes the memoisation rule fragile
   if someone ever adds image-name remembering).

7. **No error handling on Re-Import / short-URL.** Both re-import handlers
   ignore a null or non-parseable `s1`: the null guard just skips the call,
   and `readCircuit` itself logs errors to the console. The short-URL
   error path in `ExportAsUrlDialog.java:75-79` writes the raw HTTP status
   text into the user's TextArea, overwriting the URL they were trying to
   save. No retry, no restore.

8. **`ExportAsLocalFileDialog` always produces text**, despite the Blob's
   `{type: 'text/plain'}` implying format flexibility. No format picker in the
   dialog, no formatId parameter in the constructor. To export JSON locally
   the user has to open the JSON dialog, copy the content, then paste
   elsewhere — the dialog is not hooked into `io-framework`'s extension
   registry (`CircuitFormat.getFileExtensions`, `getMimeType`). Adding a
   format dropdown here would be the most natural place to surface the
   existing plugin framework end-to-end.

9. **URL shortening is a single-point-of-failure.** `shortrelay.php` is a
   relative URL (`ExportAsUrlDialog.java:60`) — it assumes the app is hosted
   on a server with that script. For self-hosted installations or the
   standalone desktop build, the "Create short URL" button will 404
   silently into the TextArea.

10. **`closeOnEnter = false` inconsistency.** Set in the Text/JSON/URL
    dialogs but not in the Image / LocalFile ones. For the Image dialog
    there's no text input so it doesn't matter; for the LocalFile dialog it
    does — pressing Enter in the filename TextBox will close the dialog
    without applying the rename. Likely a bug.

---

## Concept boundary

**Recommended: one `export-dialogs` concept** (`ExportAsImageDialog`,
`ExportAsJsonDialog`, `ExportAsLocalFileDialog`, `ExportAsTextDialog`,
`ExportAsUrlDialog`), grouped with `ImportFromTextDialog` and
`ImportFromDropboxDialog` (also in `client/dialog/`) into a **parent
`import-export-ui` concept**.

Rationale:

- All five export dialogs share (a) the same GWT `Dialog` base, (b) the same
  layout idiom, (c) the same positioning in the call graph (downstream of
  `ActionManager.dumpCircuit*` or `CircuitRenderer.getCircuitAs*`), and (d)
  three of them share almost-identical Re-Import / Copy-to-Clipboard logic.
  They form one coherent unit.
- The import side (`ImportFromTextDialog` already uses
  `CircuitLoader.readCircuit`, `ImportFromDropboxDialog` is listed in
  `io-framework.md#used-by`) is a natural symmetric counterpart — together
  they are the full user-facing surface of the io-framework.
- Grouping all seven dialogs as `import-export-ui` matches how the user
  experiences them (a single "save/load/share" menu block under `File →
  Import/Export` in `MenuManager`), and keeps the distinction between *UI
  widgets* (`client/dialog/`) and *serialisation framework* (`client/io/`)
  sharp.

Sub-sections inside the concept would be:

1. **Textual exports** (`ExportAsTextDialog`, `ExportAsJsonDialog`) — shared
   TextArea + Copy + Re-Import skeleton; ripe for a common base class.
2. **URL export** (`ExportAsUrlDialog`) — adds LZString compression and
   optional short-URL relay.
3. **Local-file export** (`ExportAsLocalFileDialog`) — Blob-URL / download
   anchor flow; called out as the one dialog that side-steps
   `DialogManager`.
4. **Image exports** (`ExportAsImageDialog` for both PNG and SVG) — the one
   sub-unit that does not depend on `io-framework` and instead consumes
   `CircuitRenderer`.
5. **Import counterparts** (when folded into `import-export-ui`) —
   `ImportFromTextDialog`, `ImportFromDropboxDialog`.

Alternative (not recommended): a single "image-export" concept separate from
"text-export" — would force duplicating the `Dialog` base / layout / JSNI
documentation across two concepts for a net loss of coherence.

---

## Summary

The five `dialog/ExportAs*` widgets are UI-thin presenters that wrap strings
from `ActionManager.dumpCircuit(...)` (io-framework) or images from
`CircuitRenderer.getCircuitAs{Canvas,SVG}()` in GWT TextAreas, Blob-URL
download anchors, or `data:` URLs, with hand-rolled JSNI for clipboard,
base64, Blob and LZString. The Text and JSON dialogs are byte-for-byte
duplicates minus a title/size — a shared abstract base would erase ~60 lines
of copy-paste including three identical `execCommand('copy')` bridges. These
dialogs plus `ImportFromTextDialog` / `ImportFromDropboxDialog` form one
natural `import-export-ui` concept sitting on top of (and orthogonal to, for
images) the `io-framework` plugin registry.
