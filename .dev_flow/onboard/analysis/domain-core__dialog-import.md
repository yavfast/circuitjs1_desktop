# Sub-Unit Analysis: domain-core / dialog-import

> **Paths:**
> - `src/main/java/com/lushprojects/circuitjs1/client/dialog/ImportFromDropboxDialog.java`
> - `src/main/java/com/lushprojects/circuitjs1/client/dialog/ImportFromTextDialog.java`
>
> **Companion root-level class:** `src/main/java/com/lushprojects/circuitjs1/client/ImportFromDropbox.java`
> **Analyzed:** 2026-04-18
> **Files:** 2 (dialog sub-unit) + 1 tightly-coupled root helper

## Purpose

Two `Dialog`-subclass UI surfaces that feed string payloads into the shared
circuit-loading pipeline. Both dialogs are *string suppliers* only: neither
parses circuit data itself. All parsing goes through
`CircuitLoader.readCircuit` (text-default via
`CircuitFormatRegistry.detectFormatOrDefault`, so the io-framework plugin
dispatcher chooses format). The sub-unit covers two acquisition channels:

1. **Clipboard / paste** — `ImportFromTextDialog` (3 fields, 1 checkbox,
   2 buttons). Reads a multiline `TextArea` and forwards the raw string to
   `ActionManager.importCircuitFromText`.
2. **Dropbox fetch** — `ImportFromDropboxDialog` (chooser button + pasted-link
   TextArea). Two independent paths: JS SDK `Dropbox.choose()` (via the root
   `ImportFromDropbox` helper) and a user-supplied shared-link URL rewritten
   through `dl.dropboxusercontent.com` + XHR GET.

## Per-dialog catalog

### `ImportFromTextDialog`
- **File:** `dialog/ImportFromTextDialog.java:33`
- **Extends:** `Dialog` (dialog base, `closeOnEnter = false` at :44).
- **Fields:**
  | Field | Type | Role |
  |---|---|---|
  | `vp` | `VerticalPanel` | root widget (:35) |
  | `hp` | `HorizontalPanel` | button row (:36) |
  | `sim` | `CirSim` | ctor-captured backref (:37) |
  | `textArea` | `TextArea` | 300x200 px paste target (:39, :52–54) |
- **Local UI state:** `subCheck` (`Checkbox`, "Load Subcircuits Only") is a
  **local** in the ctor (:46, :55), not a field — retrieved via the inner
  `ClickHandler` closure.
- **Flow (ctor `ImportFromTextDialog(CirSim)` :41–78):**
  1. Build VBox with label, TextArea, subcircuit checkbox.
  2. HBox: OK (:58) and Cancel (:70).
  3. **OK handler (:59–69):**
     - `sim.getActiveDocument().circuitEditor.pushUndo()` (:62).
     - `closeDialog()` (:63) — **before** reading text, which is safe because
       `textArea.getText()` is called after close and TextArea state persists.
     - `s = textArea.getText()` (:66).
     - `sim.actionManager.importCircuitFromText(s, subCheck.getState())` (:67).
  4. Cancel handler: `closeDialog()` only (:71–75).
  5. `center()` + `show()` (:76–77) — this dialog auto-shows, unlike many
     sibling dialogs that rely on `DialogManager` calling `show()`.
- **Commented-out alternative (`:51`, `:64-65`):** A `RichTextArea` variant
  (`textBox.getHTML()` with `<br>` → `\r` replace) is commented out; the code
  standardised on plain-text `TextArea` to avoid HTML-entity round-tripping.

### `ImportFromDropboxDialog`
- **File:** `dialog/ImportFromDropboxDialog.java:16`
- **Extends:** `Dialog` (`closeOnEnter = false` :74).
- **Fields:**
  | Field | Type | Role |
  |---|---|---|
  | `vp` | `VerticalPanel` | root (:19) |
  | `cancelButton`, `chooserButton`, `importButton` | `Button` | actions (:20–22) |
  | `ta` | `TextArea` | 300x200 link paste (:23, :96–98) |
  | `la` | `Label` | variant prompt (:24, :88/:91) |
  | `hp` | `HorizontalPanel` | button row (:25) |
  | `importFromDropbox` | `ImportFromDropbox` | instance held so JSNI callback anchor survives (:26, :85) |
  | `sim` | `static CirSim` | **static** backref (:27) — shared with JSNI |
- **Static setter/callback contract (:30–38):**
  - `setSim(CirSim)` (:30) — stores into static `sim`.
  - `doLoadCallback(String)` (:34) — static; invoked from JSNI. Calls
    `sim.getActiveDocument().circuitEditor.pushUndo()`, then
    `circuitLoader.readCircuit(s)`, then `sim.allowSave(false)` (the
    text-dialog path does *not* call `allowSave(false)` directly — it goes
    through `ActionManager.importCircuitFromText` which does).
- **Flow (ctor :70–121):**
  1. `setSim(csim)` — initialises the static backref every time the dialog is
     constructed.
  2. **Branch on `ImportFromDropbox.isSupported()` (:78)** — detects Dropbox
     JS SDK presence & non-Firefox browser (see JSNI contract below).
     - Supported: adds "Open Dropbox Chooser" button (:80–87). Click → close
       dialog, instantiate `new ImportFromDropbox(sim)` which immediately
       calls `$wnd.Dropbox.choose(...)` in its JSNI ctor.
     - Unsupported: shows a message explaining the chooser is unavailable
       (:90) and keeps the manual-link form active (:91).
  3. Always adds the link-paste form: `TextArea ta` (:96–99) + "Import From
     Dropbox Link" button (:104–111) calling `doImportDropboxLink(ta.getText(), true)`.
  4. Cancel button (:113–119).
  5. `center()` (:120) — no `show()` (caller is expected to show, but the
     currently-live call-site is commented out in `ActionManager` :243).
- **Validation (`doImportDropboxLink` :58–68):**
  - If `validateIsDropbox` and link does not start with `https://www.dropbox.com/`
    → `Window.alert` + early return (:59–62). English-only literal — not
    `Locale.LS` wrapped (unlike most text in the dialog).
  - URL rewrite: `www.dropbox.com` → `dl.dropboxusercontent.com` (:65). This
    is the documented CORS work-around.
  - Delegates to `doDropboxImport(link)` (JSNI, :66).

## Dropbox JSNI bridge

Two parallel JSNI paths exist, one per class. They do **not** share code.

### `ImportFromDropboxDialog.doDropboxImport(String)` (:41–56)
- **Purpose:** fetch a pre-rewritten `dl.dropboxusercontent.com` URL and pump
  the body back into Java.
- **Mechanism:** raw `XMLHttpRequest`, **synchronous** (`xhr.open("GET", link, false)` :49),
  `load` listener reads `responseText` and calls
  `@com.lushprojects.circuitjs1.client.dialog.ImportFromDropboxDialog::doLoadCallback(Ljava/lang/String;)(text)` (:47).
- **Error handling:** wrap-all `try { ... } catch(err) {}` — silently swallows
  errors (:52–54). No user feedback on fetch failure.
- **Note:** synchronous XHR is deprecated in modern browsers; this is legacy.

### `ImportFromDropbox.doDropboxImport()` (root class, :36–81)
- **Purpose:** invoke the Dropbox **Chooser JS SDK** (`$wnd.Dropbox.choose`)
  to let the user browse their own Dropbox account.
- **Options passed to `Dropbox.choose` (:38–79):**
  | Option | Value | Purpose |
  |---|---|---|
  | `success` | inline JS callback | fires when a file is picked |
  | `linkType` | `"direct"` (:68) | expiring direct-download URL (not a preview) |
  | `multiselect` | `false` (:72) | single-file only |
  | `extensions` | (commented out :78) | no extension filter |
  | `cancel` | (commented out :61) | no cancel hook |
- **Size gate (:44):** only fires XHR if `files[0].bytes < 100000`
  (~100 KB). Larger files are silently ignored — the XHR is still executed
  unconditionally at :52 because `xhr.open/send` sit **outside** the size-gate
  `if`, but `xhr` is only **declared** inside it → this is the **bug noted
  under Issues**.
- **Callback target:**
  `@com.lushprojects.circuitjs1.client.ImportFromDropbox::doLoadCallback(Ljava/lang/String;)(text)`
  (:49) — a different static than the dialog's, on the **root** class.
- **`doLoadCallback`** (:30–33): `pushUndo()` + `readCircuit(s)`. **Does not**
  call `allowSave(false)` (the dialog variant does; see note below).

### `ImportFromDropbox.isSupported()` (:15–28)
- **Returns `true`** iff: not Firefox (user-agent regex :21, workaround for
  gwtproject/gwt#7923) **and** `$wnd.Dropbox.isBrowserSupported()` is truthy.
- **Returns `false`** on any exception — defensive `try/catch` around the
  entire check.

### JSNI callback surface summary (two static entry points from JS)
| JS call | Java target | Action |
|---|---|---|
| Chooser-success XHR load | `ImportFromDropbox.doLoadCallback(String)` | pushUndo + readCircuit |
| Pasted-link XHR load | `ImportFromDropboxDialog.doLoadCallback(String)` | pushUndo + readCircuit + allowSave(false) |

Divergence between the two callbacks (`allowSave(false)` present/absent) is
unexplained and likely an oversight — both fetch non-local circuits and should
treat them identically from a "save this as new" perspective.

## Text-paste parsing path

`ImportFromTextDialog` does **zero** parsing in-dialog. The payload flow:

```
TextArea.getText()
  → CirSim.actionManager.importCircuitFromText(text, subcircuitsOnly)    [dialog:67]
     → ActionManager.importCircuitFromText(text, subcircuitsOnly)        [ActionManager.java:534]
        flags = subcircuitsOnly ? (RC_SUBCIRCUITS | RC_RETAIN) : 0       [:535]
        → getActiveDocument().circuitLoader.readCircuit(text, flags)     [:537]
           → CircuitFormatRegistry.detectFormatOrDefault(text)
              → TextCircuitImporter.importCircuit or JsonCircuitImporter.importCircuit
        → cirSim.allowSave(false); circuitInfo().filePath=null;
          circuitInfo().fileName=null; changeWindowTitle(false)          [:539–542]
```

Key observations:

1. **Format detection is automatic.** Even though the dialog is named
   "Import from Text", pasted JSON is also accepted because `CircuitLoader`
   calls `detectFormatOrDefault` (see io-framework.md §Format detection
   order). Text wins ties; JSON blobs start with `{` and fall through to JSON.
2. **Subcircuit checkbox semantics.** `subCheck.getState() == true` sets the
   bitmask `RC_SUBCIRCUITS | RC_RETAIN`:
   - `RC_SUBCIRCUITS` (= 2): importer processes only `.` lines
     (composite-model definitions) — see
     `TextCircuitImporter.processCircuitLine` (:192).
   - `RC_RETAIN` (= 1): do not reset current circuit state.
   - Combined: merge subcircuit definitions into the live document without
     touching existing elements. This is the "library merge" workflow.
3. **Undo is pushed by the dialog** (line 62), **before** close. The importer
   does not push undo itself, so a text-import always produces exactly one
   undo entry regardless of how many lines/elements are parsed.
4. **`allowSave(false)`** + title/path reset runs *after* `readCircuit`, so a
   parse error still forces the file to be treated as unsaved.

## Integration points

### Ingress (who opens these dialogs)

| Dialog | Opener | Location |
|---|---|---|
| `ImportFromTextDialog` | `DialogManager.showImportFromTextDialog()` | `DialogManager.java:80–82` |
| `DialogManager.showImportFromTextDialog()` | `ActionManager` switch on `"importfromtext"` menu item | `ActionManager.java:238–240` |
| `ImportFromDropboxDialog` | **currently unreachable via menu** — the opener in `ActionManager` is commented out (`ActionManager.java:241–245`) |
| `ImportFromDropboxDialog.doImportDropboxLink(...)` | Commented bootstrap in `CirSim.java:331–332` (URL-based startup circuit) |

So the Dropbox dialog is dead code at the menu layer — it's constructable,
but nothing in-tree wires it to a user action. The file remains because its
static helpers (`doImportDropboxLink`, `doLoadCallback`, `doDropboxImport`)
are referenced from the commented bootstrap and are potential re-entry
points if Dropbox support is re-enabled.

### Egress (what they call out to)

- **Shared pipeline:** `CircuitDocument.circuitEditor.pushUndo()` +
  `CircuitDocument.circuitLoader.readCircuit(String [, int flags])` — both
  dialogs and the root helper funnel through this exact pair.
- **Text path** additionally goes through `ActionManager.importCircuitFromText`
  for the extra bookkeeping (flags bitmask, `allowSave(false)`, filename
  reset). Dropbox dialog's `doLoadCallback` **calls `allowSave(false)`
  directly** (:37), bypassing ActionManager — the filename/title reset is
  therefore **skipped** in the Dropbox path.

### io-framework binding

- Neither dialog references `io/` classes directly.
- Both rely on `CircuitLoader.readCircuit` → `CircuitFormatRegistry`
  detection. See `io-framework.md` §"Used by (outside io/*)" row
  `ImportFromDropbox` / `dialog/ImportFromDropboxDialog`.
- Flags used: `RC_SUBCIRCUITS | RC_RETAIN` (text dialog via ActionManager).
  Dropbox path uses flags = 0 (full replace).

### Browser API dependencies

- **GWT JSNI** both classes — no abstraction layer for HTTP or Dropbox SDK.
- `XMLHttpRequest` (synchronous) in both JSNI blocks.
- `$wnd.Dropbox.choose` / `$wnd.Dropbox.isBrowserSupported` — Dropbox Chooser
  SDK script must be loaded in the host HTML page (not referenced in Java).
- `navigator.userAgent` UA sniff for Firefox (ImportFromDropbox.java:21).

## Issues

1. **Dropbox chooser bug at `ImportFromDropbox.java:44–53`.** The `if
   (files[0].bytes < 100000)` check wraps the `xhr` **declaration** but the
   `xhr.open(...)` / `xhr.send(...)` sit **after** the closing brace of that
   `if` — i.e. outside the size-gate. This is a closure-scope accident in
   JS: `xhr` is `var`-declared (hoisted to function scope), so when a file
   is ≥ 100 KB the code tries to `xhr.open` on `undefined` and throws,
   caught silently by the outer try/catch. Net effect: large files fail
   silently instead of showing a user message. The size gate is therefore
   effectively a silent upper bound, not a guard.

2. **Synchronous XHR.** Both JSNI blocks use `xhr.open(..., false)` — the
   sync form is deprecated on the main thread in modern browsers and can
   be hard-disabled. Any move to async would require reworking the Java
   callback path (currently the code assumes blocking semantics, though in
   practice `load` is event-driven so it works async too).

3. **Silent error handling.** Both JSNI catches swallow exceptions with no
   logging — the user sees no feedback if the Dropbox fetch fails for any
   reason (CORS, 404, size, network).

4. **Static state on `ImportFromDropboxDialog`.** `sim` is `static` (:27) to
   survive the JSNI round-trip. Constructing two dialogs sequentially would
   overwrite it — not an issue today because dialogs are modal, but it's a
   latent foot-gun if a test or bootstrap retains an older dialog instance.
   The root `ImportFromDropbox.sim` has the same shape (:6).

5. **Duplicated callback code.** `ImportFromDropbox.doLoadCallback` (:30)
   and `ImportFromDropboxDialog.doLoadCallback` (:34) do almost the same
   thing but differ on `allowSave(false)`. Unifying them would remove a
   subtle divergence.

6. **No `Locale.LS` on error message.** `Window.alert("Dropbox links must
   start https://www.dropbox.com/")` (:60) is not localised. The rest of
   the dialog strings go through `Locale.LS`. Also, the "unsupported browser"
   strings (:90, :91) are not localised.

7. **Dead code at menu layer.** `ActionManager.java:241–245` (Dropbox
   dialog) and `CirSim.java:331–332` (link bootstrap) are commented out.
   The dialog is functional but not invocable from the UI. Decide: ship or
   delete.

8. **Text-vs-name mismatch.** `ImportFromTextDialog` is named as if it only
   accepts the legacy text format, but the downstream `readCircuit` does
   format auto-detection — a user pasting JSON will succeed. The dialog
   should either be renamed "Import Circuit" or the detection should be
   forced to text for consistency.

9. **`subCheck` is a local, not a field.** `ImportFromTextDialog` declares
   `subCheck` as a ctor local (:46) unlike `textArea` which is a field
   (:39). Functionally equivalent (the inner class captures the final
   local), but stylistically inconsistent.

10. **Text dialog calls `show()` from its ctor (:77), Dropbox dialog does
    not (:120 ends at `center()`).** `ImportFromDropboxDialog` callers
    must remember to call `show()`. Given (7), no active caller does.

## Concept boundary

This sub-unit pairs naturally with the **export dialogs**
(`ExportAsTextDialog`, `ExportAsJsonDialog`, `ExportAsUrlDialog`,
`ExportAsLocalFileDialog`, `ExportAsImageDialog`) to form an
**"import-export-ui"** concept. Justification:

- All of them are thin GWT-UI surfaces over the `io-framework` contract.
- Import and export mirror one another (Text ↔ Text, JSON ↔ JSON,
  Dropbox ↔ URL/LocalFile payload delivery, etc.).
- Both sides share the same plumbing triad: `CirSim` → `ActionManager` →
  `CircuitDocument.circuitLoader` / `circuitExporter` → `CircuitFormatRegistry`.
- Cross-cutting invariants live only at this UI layer: undo-push timing
  (`pushUndo()` before dialog-close), `allowSave(false)` policy, title/path
  reset, and `Locale.LS` coverage.
- The io-framework concept deliberately has no UI knowledge; placing these
  dialogs there would pollute the contract.

Recommended sub-sections inside the `import-export-ui` concept:

1. **Import dialogs** (this sub-unit: text paste, Dropbox fetch, dropbox
   link) + any future `ImportFromUrlDialog`.
2. **Export dialogs** (text, JSON, URL, local file, image).
3. **Cross-cutting bookkeeping** — the pushUndo / allowSave / title-reset
   protocol that every dialog must follow to stay consistent with
   `ActionManager.importCircuitFromText`.

---

## Summary (3 lines)

`dialog-import` is a two-class UI boundary over the io-framework loader: `ImportFromTextDialog` captures a paste into `TextArea` and hands it to `ActionManager.importCircuitFromText` (flags = `RC_SUBCIRCUITS|RC_RETAIN` if the checkbox is set) while `ImportFromDropboxDialog` offers both a JS-SDK chooser (via the tightly-coupled root `ImportFromDropbox` class using `$wnd.Dropbox.choose`) and a pasted shared-link path (rewritten through `dl.dropboxusercontent.com` + synchronous `XMLHttpRequest`), both funnelling bodies through JSNI callbacks back into `circuitLoader.readCircuit`. The Dropbox dialog is currently dead at the menu layer (opener commented out in `ActionManager.java:243` and `CirSim.java:331`); it also contains a chooser-size-gate scope bug (`ImportFromDropbox.java:44–53`) and diverges from the text-dialog path on `allowSave(false)` / filename-reset semantics. The sub-unit pairs with the five export dialogs into an `import-export-ui` concept where the `pushUndo` / `allowSave` / title-reset protocol and `Locale.LS` coverage are the cross-cutting invariants.
