# Implementation Plan: Import/Export UI  {#PL_IEU}

> **Code:** PL_IEU
> **Status:** completed
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_IEU](./import-export-ui.concept.md)
> **Specification:** [SP_IEU](./import-export-ui.sp.md)
> **Depends on plans:** [PL_EIC](./edit-info-contract.plan.md), [PL_IOF](./io-framework.plan.md), [PL_UTL](./util-locale-log.plan.md)
> **Used by plans:** — (will be filled by higher layers)
>
> Reverse-engineered plan for the 7 import/export dialogs + root `ImportFromDropbox` helper. Implementation is complete.

## Goal

Deliver the user-facing file-I/O surface (text, JSON, compressed URL, local-file, image export; text-paste and Dropbox import) as thin GWT Dialog wrappers over `io-framework` payload plumbing, with hand-rolled JSNI for clipboard, Blob, base64, LZString, and Dropbox SDK.

## Technology Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Base widget | `dialog.Dialog` | Consistent position/collapse/anchor. |
| Payload source (text/json/url) | `ActionManager.dumpCircuit(formatId)` | Pluggable via `CircuitFormatRegistry`. |
| Payload source (image) | `CircuitRenderer.getCircuitAs{Canvas,SVG}` | Canvas-native; SVG via lazy-loaded `canvas2svg`. |
| Import format detection | `CircuitFormatRegistry.detectFormatOrDefault` | Single loader path for all text-shaped payloads. |
| Clipboard | `document.execCommand('copy')` JSNI | Legacy compatibility. |
| Compression | `LZString.compressToEncodedURIComponent` | Included as window global. |
| File download | Blob URL + hidden anchor + JSNI `.click()` | No FileSaver dependency. |
| Dropbox | `$wnd.Dropbox.choose` SDK | Public Dropbox API. |

## Progress

- [x] Phase 1 — ExportAsTextDialog
- [x] Phase 2 — ExportAsJsonDialog
- [x] Phase 3 — ExportAsUrlDialog
- [x] Phase 4 — ExportAsLocalFileDialog
- [x] Phase 5 — ExportAsImageDialog (PNG + SVG)
- [x] Phase 6 — ImportFromTextDialog
- [x] Phase 7 — ImportFromDropboxDialog + root ImportFromDropbox JSNI helper

## Phases

### Phase 1 — ExportAsTextDialog [DONE]

**Implements:** [SP_IEU_01_01](./import-export-ui.sp.md#SP_IEU_01_01), [SP_IEU_02_01](./import-export-ui.sp.md#SP_IEU_02_01), [SP_IEU_02_06](./import-export-ui.sp.md#SP_IEU_02_06)

Delivered: TextArea 400×300 + OK/Copy/Re-Import; `closeOnEnter=false`; Copy via `execCommand('copy')`; Re-Import calls `pushUndo + closeDialog + readCircuit + allowSave(false)`.

### Phase 2 — ExportAsJsonDialog [DONE]

Delivered: near-clone of Text dialog with 500×400 TextArea and `dumpCircuit("json")` payload; Re-Import relies on `readCircuit`'s auto-detect (JSON blobs start with `{`).

### Phase 3 — ExportAsUrlDialog [DONE]

Delivered: LZString compression JSNI, `?ctz=<compressed>` URL template, optional short-URL relay via GWT `RequestBuilder` (gated by `circuitjs1.shortRelaySupported`), 2000-char warning label.

### Phase 4 — ExportAsLocalFileDialog [DONE]

**Implements:** [SP_IEU_02_03](./import-export-ui.sp.md#SP_IEU_02_03)

Delivered: `getBlobUrl` JSNI with previous-Blob revocation, hidden Anchor + Download attribute + programmatic `click()`, filename TextBox with `setLastFileName` memoisation, feature-detect stub `downloadIsSupported`.

### Phase 5 — ExportAsImageDialog [DONE]

**Implements:** [SP_IEU_02_02](./import-export-ui.sp.md#SP_IEU_02_02)

Delivered: PNG via `canvas.toDataUrl()`, SVG via `canvas2svg` + Unicode-safe `b64encode`, filename `circuit-yyyyMMdd-HHmm.{png,svg}`, user-click Anchor.

### Phase 6 — ImportFromTextDialog [DONE]

**Implements:** [SP_IEU_02_04](./import-export-ui.sp.md#SP_IEU_02_04)

Delivered: TextArea 300×200 + "Load Subcircuits Only" checkbox; OK `pushUndo + closeDialog + actionManager.importCircuitFromText(s, subOnly)`; auto-`show()` in ctor (unlike siblings).

### Phase 7 — ImportFromDropboxDialog + ImportFromDropbox [DONE]

**Implements:** [SP_IEU_02_05](./import-export-ui.sp.md#SP_IEU_02_05)

Delivered: chooser branch via `$wnd.Dropbox.choose`, pasted-link branch with `dl.dropboxusercontent.com` rewrite and sync XHR, twin static `doLoadCallback` entry points, `isSupported` UA + SDK check. **Menu wiring is currently commented out** at `ActionManager.java:243` and `CirSim.java:331`.

## Backlog

Items deferred from current cycle (from `dialog-export.md` §Issues and `dialog-import.md` §Issues):

- **Package-location drift (export #1).** Onboard brief said `element/dialog/`; actual path is `client/dialog/`.
- **Three separate `copyToClipboard` JSNI declarations (export #2).** Identical; all use deprecated `execCommand('copy')`. Migration to `navigator.clipboard` would need 3 edits.
- **`ExportAsLocalFileDialog` bypasses DialogManager (export #3).** `ActionManager:530` constructs directly; not auto-dismissed; add `DialogManager.showExportAsLocalFileDialog(dump)` symmetric factory.
- **Dead feature-detection (export #4).** `downloadIsSupported()` referenced only from a commented `MenuManager:156` line.
- **Text ↔ JSON dialog near-duplication (export #5).** ~60 lines of copy-paste; a `TextualExportDialog` base would collapse it.
- **Inconsistent filename prefixes (export #6).** `"circuit-"` vs `"circuitjs-"`; `setLastFileName` memoisation skips only the latter.
- **No error handling on Re-Import / short-URL (export #7).** Null guard silent; short-URL error overwrites user's TextArea.
- **`ExportAsLocalFileDialog` always produces text (export #8).** No format picker; no io-framework registry plumb-through.
- **URL shortening SPOF (export #9).** `shortrelay.php` is a relative URL; 404s silently on self-host / desktop build.
- **`closeOnEnter` inconsistency (export #10).** Set false in Text/JSON/URL; not in Image/LocalFile — LocalFile filename TextBox closes on Enter.
- **Dropbox chooser size-gate bug (import #1).** `var xhr` is scoped inside the `<100000` guard but `xhr.open/send` run outside; large files silently fail via closure-scope JS accident.
- **Synchronous XHR (import #2).** Deprecated; may be disabled.
- **Silent error handling in Dropbox (import #3).** Wrap-all try/catch swallows CORS/404/network errors.
- **Static `sim` on `ImportFromDropboxDialog` (import #4).** Two instances would overwrite; modal today.
- **Duplicated `doLoadCallback` (import #5).** Dialog and root variants differ only on `allowSave(false)` — unexplained divergence.
- **No `Locale.LS` on Dropbox error messages (import #6).** English-only alert; unsupported-browser label also not localised.
- **Dropbox dead at menu layer (import #7).** Opener commented out in `ActionManager` and `CirSim` bootstrap.
- **Text-vs-name mismatch (import #8).** `ImportFromTextDialog` accepts JSON via auto-detect despite its name.
- **`subCheck` local-vs-field inconsistency (import #9).** Minor style.
- **`ImportFromTextDialog.show()` in ctor (import #10).** Differs from sibling pattern — caller auto-shows.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
