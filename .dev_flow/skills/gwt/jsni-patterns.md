---
skill: jsni-patterns
domain: gwt
topics: [jsni, native, $wnd, $doc, clipboard, audio, dropbox, callback]
source: onboard
updated: 2026-04-18
---

# JSNI Patterns in circuitjs1

## Context

circuitjs1 is compiled Java-to-JS via GWT. Any call into browser APIs
(Web Audio, Clipboard, Dropbox SDK, FileReader, NW.js Shell, fullscreen,
localStorage-level hacks, `alert`) must cross the **JSNI boundary** — a
`native` Java method with a JavaScript body between `/*-{ ... }-*/`.
Rule RULE_ARCH_008 (`.dev_flow/rules/architecture.md`) forbids scattering
JSNI into element/physics code; it must stay in a small set of adapter
files.

## Key concepts

**Core idiom.** A JSNI method is declared:
```java
public static native void foo(String s) /*-{
    $wnd.console.log(s);
}-*/;
```
- `$wnd` is the host window, `$doc` is `document`.
- Calls Java back in via `@fully.qualified.Class::method(Lsig;)(args)`
  using JVM type descriptors (`Ljava/lang/String;` for String, `I` for
  int, `V` for void, `Z` for boolean).
- Exceptions thrown inside JSNI propagate to the GWT uncaught handler
  installed in `circuitjs1.onModuleLoad` (RULE_ERR_004) — do **not**
  wrap JSNI calls in ad-hoc try/catch.

**Where JSNI lives in this project (adapter files):**

| File | Bridges | Notes |
|---|---|---|
| `circuitjs1.java:72-83` | `navigator.language(s)` for locale resolution | Only inbound JSNI in bootstrap |
| `QueryParameters.java:54-57` | `$wnd.location.search` | Read-only |
| `CirSim.java:1437` (`setupJSInterface`) | Publishes the whole `$wnd.CircuitJS1` API | Outbound: setSimRunning, getTime, setTimeStep, resetSimulation, stepSimulation, etc. |
| `CirSim.java:1510-1528` | Fires `$wnd.CircuitJS1.onupdate/onanalyze/ontimestep/onsvgrendered` hooks | Outbound event dispatch |
| `CirSim.java` static natives | `clipboardWriteImage`, `executeJS`, `nodeSave`, `nodeSaveAs`, `toggleDevTools`, `changeWindowTitle`, `createSVGContext`, `getSerializedSVG`, `devicePixelRatio`, `console`, `debugger` | OS/NW.js integration |
| `ClipboardManager.java:21, :121, :151, :72` | `navigator.clipboard.writeText/readText` + legacy `textarea+execCommand('copy')` fallback | Async read uses `ClipboardCallback.@...::onSuccess(Ljava/lang/String;)(text)` pattern |
| `LoadFile.java:32, :99` | `$wnd.File`, `$wnd.FileReader`, `$doc.getElementById("LoadFileElement").click()` | Static `sLoadFile` singleton is the JSNI anchor; 128000-byte cap enforced *inside* JSNI |
| `ImportFromDropbox.java` / `dialog/ImportFromDropboxDialog.java` | `$wnd.Dropbox.choose`, synchronous `XMLHttpRequest` | Two parallel JSNI paths; both depend on the Dropbox SDK `<script>` already loaded in `circuitjs.html` |
| `AudioInputElm` / `AudioOutputElm` | Web Audio API (`AudioContext`, `decodeAudioData`, `AnalyserNode`), stores blobs on `$doc.audioBlob` / `$doc.audioObject` | JS-side calls back `setSamplingRate(I)(rate)` |
| `DataRecorderElm` | Creates a `Blob` URL for sample export, stashed on `$doc.recorderBlob` | `<a download>` wired by the edit dialog |
| `TestPointElm.java:327` | Wraps `$wnd.alert` | Only user-facing path |
| `dialog/ExportAs*Dialog` | `navigator.clipboard.writeText`, SVG blob serialization | |
| `util/PerfMonitor` | `window.performance.now()` fallback to `Date.now()` | See `util.md:171` |
| `root-utils.md` entries: `Graphics.ellipse` (guards `rx>=0 && ry>=0`), `openURLWithJavaScript` (NW.js `nw.Shell.openExternal` detection), fullscreen helpers | Layer-0 JSNI | |

**Two callback styles.**
1. **Sync JS → Java with return value:** JSNI returns a Java type, GWT
   marshals automatically (see `QueryParameters.getQueryString()`).
2. **Async JS → Java callback:** pass a Java interface instance into
   JSNI, invoke via `obj.@pkg.Iface::method(Lsig;)(arg)`. Canonical
   example: `ClipboardCallback` (2-method interface, `onSuccess` /
   `onError`) used by `ClipboardManager.readFromSystemClipboard`.

**Static-singleton anchor pattern.** When JSNI needs to call a specific
instance back (file upload, Dropbox), the project stores a `static`
reference: `LoadFile.sLoadFile`, `ImportFromDropbox.sim`. The JSNI
closure closes over the static, so only the **latest-constructed
instance** survives — constructing two LoadFile/Dropbox objects in
sequence invalidates the first. This is documented as issue in
`layer3__file-io-glue.md:367`.

## Usage in this project

- GWT EntryPoint installs its uncaught handler *before* any other
  JSNI fires: `circuitjs1.java:50-66` (boot order matters for
  RULE_ERR_004 to take effect).
- `$wnd.CircuitJS1` is installed inside `CirSim.init()`, **after**
  `loadSimulator` — not in the EntryPoint. Downstream code assumes it
  exists by the time `oncircuitjsloaded` fires (`CirSim.java:1505-1508`).
- The `$wnd.CircuitJS1` API surface is documented in `docs/JS_API.md`.

## Pitfalls

1. **Never add JSNI to element classes.** Keep it in the adapter files
   above (RULE_STRUCT_008, RULE_ARCH_008). `ChipElm.setVoltageSource`
   has an outlier `System.out.println` — do not follow that pattern.
2. **GWT forbidden APIs inside JSNI scope still apply to the Java
   surface** (RULE_STYLE_002): no `java.awt.*`, `java.io.*`, threads,
   reflection. The Java method signature is what the compiler sees.
3. **JSNI method references are compile-time linked.** Renaming a Java
   callback method silently breaks JSNI — the `@fq::method(sig)` string
   is opaque to Java refactoring tools. Grep `.dev_flow/onboard/analysis/`
   for `::` to find every cross-link.
4. **Static-singleton JSNI anchors break multi-document use.** Dropbox
   and `LoadFile` only work for the last-constructed instance
   (`layer3__file-io-glue.md:367`, `domain-core__dialog-import.md:258`).
5. **Synchronous XHR inside Dropbox import JSNI** (`xhr.open(..., false)`)
   blocks the UI thread — flagged in `domain-core__dialog-import.md:247`.
   Prefer async patterns in new code.
6. **Silent-failure holes in locale fetch:** `circuitjs1.loadLocale`
   `onError` and `RequestException` branches only log, never start the
   simulator (`layer3__app-entry.md:50-53`). A pattern to avoid.

## References

- `.dev_flow/onboard/analysis/layer3__app-entry.md` (lines 33, 72-83)
- `.dev_flow/onboard/analysis/layer3__simulator-core.md` §8.3, §11
- `.dev_flow/onboard/analysis/layer3__editor-interaction.md` §2.9, §10
- `.dev_flow/onboard/analysis/layer3__file-io-glue.md` §"Dropbox JSNI bridge"
- `.dev_flow/onboard/analysis/domain-core__cat-audio-rf.md` §"Web Audio Bridge"
- `.dev_flow/onboard/analysis/domain-core__dialog-import.md` §"Dropbox JSNI bridge"
- `.dev_flow/onboard/analysis/root-utils.md` lines 235, 307, 517
- Rules: RULE_ARCH_008, RULE_STRUCT_008, RULE_ERR_004, RULE_STYLE_002
- Docs: `docs/JS_API.md`
