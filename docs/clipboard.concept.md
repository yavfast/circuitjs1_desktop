# Clipboard — Circuit-Text Bridge  {#C_CLP}

> **Code:** C_CLP
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard-doc-gen
>
> **Depends on:** C_IOF (io-framework), [C_DOC](./document-model.concept.md), C_PLT (platform/JSNI), [C_EDI](./canvas-editor.concept.md)
> **Used by:** [C_EDI](./canvas-editor.concept.md), [C_MEN](./menus-actions.concept.md)
> **Spike:** —
> **Specification:** [SP_CLP](./clipboard.sp.md)
> **Plan:** [clipboard.plan.md](./clipboard.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/layer3__editor-interaction.md` §§2.9–2.10, §7.
>
> Bridges circuit text between the editor's selection and the browser's clipboard via JSNI. Maintains an internal buffer for synchronous paste and falls back to a hidden textarea + `execCommand('copy')` when `navigator.clipboard.writeText` is unavailable. Reads are always asynchronous through a callback interface.

## 1. Philosophy  {#C_CLP_01}

### 1.1. Core Principle  {#C_CLP_01_01}

Circuit-text is serialised by the io exporter; the clipboard manager is a pure transport that owns no circuit semantics. An internal buffer doubles as a synchronous fallback so `paste` works even when the browser denies async clipboard access.

### 1.2. Design Constraints  {#C_CLP_01_02}

- **Asymmetric API.** Writes may be synchronous *and* async (both are attempted); reads are always async (callback).
- **Circuit-sniffing heuristic.** Pastes from the system clipboard are filtered by `isCircuitData(text)` — checks for `$`, `r `, `c `, `l `, `w ` token prefixes. False positives are possible.
- **Duplicate as internal-only copy.** `doDuplicate` short-circuits through the internal buffer to avoid OS round-trip.
- **Image-to-clipboard is separate.** `doImageToClipboard` uses a different JSNI path (`CirSim.clipboardWriteImage`) with the rasterised canvas.

## 2. Domain Model  {#C_CLP_02}

### 2.1. Key Entities  {#C_CLP_02_01}

```
ClipboardManager extends BaseCirSimDelegate
  String   internalClipboard        -- fallback synchronous buffer
  boolean  hasSystemClipboardSupport

ClipboardCallback  (interface)
  void onSuccess(String text)
  void onError(String message)
```

### 2.2. Data Flows  {#C_CLP_02_02}

```
Copy/Cut:
  menuPerformed("copy"/"cut")  OR  Ctrl+C/X
    editor.doCopy/doCut
      setMenuSelection                             -- ensure right-click elm in selection
      clipboardManager.doCopy/doCut
        text = editor.copyOfSelectedElms()         -- dumpOptions + dumpSelectedItems
        setClipboard(text):
          internalClipboard = text                 -- sync fallback
          IF hasSystemClipboardSupport:
            writeToSystemClipboard(text)           -- navigator.clipboard.writeText
          ELSE:
            tryLegacyClipboardWrite(text)          -- textarea + execCommand('copy')
      IF cut: editor.doDelete(true)
      cirSim.enablePaste()

Paste:
  menuPerformed("paste")  OR  Ctrl+V
    clipboardManager.doPasteFromSystem
      IF internalClipboard non-empty:
        editor.doPaste(internalClipboard)
      ELSE:
        readFromSystemClipboard(callback)          -- async
          onSuccess(text): IF isCircuitData(text): editor.doPaste(text)
          onError(msg): console.log

Duplicate:
  editor.doDuplicate
    text = editor.copyOfSelectedElms()
    editor.doPaste(text)

Image-to-clipboard:
  actionManager.doImageToClipboard
    canvas = renderer.getCircuitAsCanvas(CAC_IMAGE)
    CirSim.clipboardWriteImage(canvas)            -- native JS
```

## 3. Mechanisms  {#C_CLP_03}

### 3.1. Core Algorithm  {#C_CLP_03_01}

**Feature detection.** Constructor calls JSNI `checkClipboardSupport()` to set `hasSystemClipboardSupport`; the feature flag is immutable thereafter.

**JSNI callback bridge.** Async reads use GWT method-reference syntax `callback.@.../onSuccess(Ljava/lang/String;)(text)` to cross back into Java. `ClipboardCallback` is a pure-Java interface; its success handler invokes `editor.doPaste` when the sniff passes.

**Serialisation reuse.** `editor.copyOfSelectedElms()` concatenates `actionManager.dumpOptions()` (the `$` header) with `simulator().dumpSelectedItems()` — i.e. reuses the native text-format exporter.

**Paste layout.** `editor.doPaste(text)` pushes undo, clears selection, reads via `circuitLoader.readCircuit(dump, RC_RETAIN[|RC_NO_CENTER])`, then `selectNewItems` + `moveNewItems` to offset pasted geometry away from the original's bounding box or towards the cursor.

### 3.2. Edge Cases  {#C_CLP_03_02}

- `isCircuitData` is a loose heuristic — a text blob with ` w ` somewhere in it may get passed to the importer, which typically rejects it gracefully.
- Legacy fallback (`execCommand('copy')`) requires a focused text element; a hidden textarea is injected/removed around the call.
- Permissions: modern browsers may reject `navigator.clipboard.readText` without user gesture; the async callback surfaces `onError`.

## 4. Integration Points  {#C_CLP_04}

### 4.1. Dependencies  {#C_CLP_04_01}

- **C_IOF (io-framework)** — text format for selection dump and paste parse.
- **C_PLT (platform)** — JSNI bridges to `navigator.clipboard`, legacy textarea trick, `CirSim.clipboardWriteImage`.
- **C_DOC** — resolves the active document for `copyOfSelectedElms` / `doPaste`.
- **[C_EDI](./canvas-editor.concept.md)** — entry points: `doCopy/doCut/doPaste/doDuplicate`.

### 4.2. API Surface  {#C_CLP_04_02}

- `doCopy()` / `doCut()` / `doPasteFromSystem()`.
- `setClipboard(String)` / `getClipboard()` / `hasClipboardData()` / `clearClipboard()` / `getClipboardInfo()`.
- `readFromSystemClipboard(ClipboardCallback)`.
- `hasSystemClipboardSupport()` — feature flag getter.
- `ClipboardCallback { onSuccess, onError }`.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
