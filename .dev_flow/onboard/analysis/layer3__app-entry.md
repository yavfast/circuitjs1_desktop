# Layer 3 — App Entry (GWT Bootstrap)

**Sub-unit:** `app-entry`
**Source:** `src/main/java/com/lushprojects/circuitjs1/client/circuitjs1.java` (single file, 210 lines)
**Concept boundary:** one thin concept — `app-entrypoint`. The class is the GWT `EntryPoint` and does nothing beyond boot-sequencing: it installs the uncaught-exception hook, resolves a locale, loads a translation catalog, constructs `CirSim`, and wires a window-resize handler. All domain logic lives downstream in `CirSim` (see `layer3__simulator-core.md`).

---

## Purpose

`circuitjs1` is the GWT module entry point (declared in `circuitjs1.gwt.xml`, referenced by the host `circuitjs.html` page). Its only responsibility is to bring the application from a blank HTML shell to a fully initialized `CirSim` instance with a localized string catalog and a global error hook, then hand control to `CirSim`.

It also owns the single version string (`versionString = "3.1.3js"`, `circuitjs1.java:39`) and the global static reference to the running simulator (`static CirSim mysim`, `circuitjs1.java:45`) which downstream modules do *not* read — `CirSim` publishes itself via its own static `sim` field instead.

---

## `onModuleLoad()` flow (numbered steps)

GWT calls `onModuleLoad()` automatically after the module's JavaScript is loaded (`circuitjs1.java:49`).

1. **Install `UncaughtExceptionHandler`** (`circuitjs1.java:50-66`).
   - Registers a `GWT.UncaughtExceptionHandler` that:
     - Formats the `Throwable`'s stack trace into a single string.
     - Calls `UncaughtExceptionDialog.show(e, stackTrace)` (non-blocking GWT dialog; cross-ref `domain-core__dialog-info.md`). Wrapped in a `try/catch(Throwable ignored)` so a UI failure cannot itself escape the handler.
     - Logs to `GWT.log(...)` and calls `e.printStackTrace()`.
   - This hook is the *first* thing installed so any exception raised during the rest of bootstrap (locale fetch, `CirSim.init()`) is captured.

2. **Invoke `loadLocale()`** (`circuitjs1.java:69`).
   The comment at line 68 is load-bearing: `loadLocale()` is the deferred continuation — it eventually calls `loadSimulator(...)` which actually instantiates `CirSim`. `onModuleLoad()` itself returns immediately; the simulator is built asynchronously after the locale file HTTP request completes.

### `loadLocale()` sub-flow (`circuitjs1.java:85-136`)

2a. **Parse query parameters.** `new QueryParameters()` (`circuitjs1.java:87`) reads `$wnd.location.search` via JSNI (`QueryParameters.java:54-57`), splits on `&`, and URL-decodes each value. The only key this class consults is `lang` (`circuitjs1.java:88`).

2b. **Resolve language** — three-tier fallback (`circuitjs1.java:88-93`):
   1. `?lang=` query parameter (explicit override).
   2. `OptionsManager.getOptionFromStorage("language", null)` (user preference from `localStorage`).
   3. `language()` JSNI (`circuitjs1.java:72-83`) — reads `navigator.languages[0]`, falling back to `"en-US"` for Electron (where the array is empty), then `navigator.language || navigator.userLanguage`.

2c. **Normalize language tag** (`circuitjs1.java:98-101`).
   - Taiwanese Chinese (`zh-tw`, `zh-cht`, case-insensitive) is preserved as `"zh-tw"`.
   - All others have their region suffix stripped via `replaceFirst("-.*", "")` (so `en-US` → `en`, `de-DE` → `de`).

2d. **English fast-path** (`circuitjs1.java:103-108`).
   If the resolved language starts with `"en"`, skip the HTTP request entirely: construct an empty `HashMap` and call `loadSimulator(...)` synchronously.

2e. **Fetch locale file** (`circuitjs1.java:110-134`).
   - URL: `GWT.getModuleBaseURL() + "locale_<lang>.txt"` (e.g. `locale_fr.txt`, `locale_zh-tw.txt`).
   - `RequestBuilder.sendRequest(null, RequestCallback)`:
     - `onError` → `GWT.log` only (the simulator is *never* started; this is a silent-failure hole — see Issues below).
     - `onResponseReceived` with `SC_OK` → `processLocale(text)` (`circuitjs1.java:168-193`) parses the `"key"="value"` line format, passing every line through `convertUnicodeEscapes` (`circuitjs1.java:138-166`) to decode `\uXXXX` sequences. Malformed lines are routed to `CirSim.console(...)` and skipped.
     - Non-OK status → empty map (defaults to English), then `loadSimulator(...)`.
   - `RequestException` from `sendRequest` is caught and only logged (another silent-failure hole).

### `loadSimulator(localizationMap)` (`circuitjs1.java:195-208`)

3. **Install localization catalog.** `Locale.localizationMap = localizationMap` (`circuitjs1.java:196`) — publishes the map statically so `Locale.LS(key)` calls everywhere in the codebase can look strings up.

4. **Instantiate `CirSim`.** `mysim = new CirSim()` (`circuitjs1.java:197`). The constructor does minimal work; heavy initialization is deferred to `init()`.

5. **Run `CirSim.init()`** (`circuitjs1.java:198`). This is where the whole application shell is built — toolbars, menus, canvases, Graphene panels, JS API export (`$wnd.CircuitJS1 = {...}` at `CirSim.java:1439`, see `layer3__simulator-core.md`), and loading the initial circuit. After `init()` returns, the JS-bridge dispatches the `oncircuitjsloaded` hook (`CirSim.java:1505-1508`).

6. **Wire window resize handler** (`circuitjs1.java:200-205`). A `ResizeHandler` on the GWT `Window` forwards resizes to `mysim.setCanvasSize(0, 0)` and `mysim.setSlidersDialogHeight()`. This is the *only* DOM event the entry point owns directly — everything else is inside `CirSim`.

7. **Initial render.** `mysim.renderer.render()` (`circuitjs1.java:207`) paints the first frame before the user interacts.

---

## External JS API surface (JSNI bridges)

The entry point itself exposes **no** JavaScript API beyond what the GWT runtime publishes. It defines exactly two JSNI methods, both *inbound* (JS → Java), both private to the bootstrap:

| JSNI method | Location | Direction | Purpose |
|---|---|---|---|
| `language()` | `circuitjs1.java:72-83` | JS → Java | Reads `navigator.languages` / `navigator.language` / `navigator.userLanguage`. |
| `QueryParameters.getQueryString()` | `QueryParameters.java:54-57` | JS → Java | Reads `$wnd.location.search`. |

The `window.CircuitJS1` global object documented in `docs/JS_API.md` (§ "Global Object", line 172) is **not** installed here. It is built inside `CirSim.setupJSInterface()` / `CirSim.init()` — see `CirSim.java:1439` (`$wnd.CircuitJS1 = { ... }`) — and the `oncircuitjsloaded` hook is fired from `CirSim.java:1505-1508`. The other lifecycle hooks (`onupdate`, `onanalyze`, `ontimestep`, `onsvgrendered`) documented in `docs/JS_API.md` lines 186-210 are dispatched from `CirSim.java:1511-1531`.

From the entry point's perspective, the external JS contract can be summarized as:
- **Inputs it reads:** `window.location.search` (for `?lang=`), `navigator.language(s)`, `localStorage` (via `OptionsManager`).
- **Outputs it produces:** nothing directly on `window.*`. It only constructs the `CirSim` instance that then publishes `window.CircuitJS1` and fires `window.oncircuitjsloaded`.

---

## Public contracts

- **`public static final String versionString`** (`circuitjs1.java:39`) — displayed in the "About" dialog and written into exported `.circuitjs.txt` files; bump on release.
- **`public static final boolean shortRelaySupported`** (`circuitjs1.java:43`) — compile-time flag; `false` in this fork. Indicates whether the hosting server provides `shortrelay.php` for URL-shortened circuit sharing.
- **`static CirSim mysim`** (`circuitjs1.java:45`) — package-private. The canonical simulator reference used by package code is `CirSim.sim` (published from inside `CirSim`), so `mysim` is effectively bootstrap-local and is rarely read.
- **`public void onModuleLoad()`** (`circuitjs1.java:49`) — GWT-mandated entry point signature (`implements EntryPoint`, declared at line 37).
- **`public void loadSimulator(HashMap<String,String>)`** (`circuitjs1.java:195`) — public only so the async `RequestCallback` (an anonymous inner class) can call it; not a stable API.

---

## Integration points

Nearly every module in the codebase is (transitively) bootstrapped from here. The direct dependencies of `circuitjs1.java` are:

| Imported / referenced | Reason |
|---|---|
| `com.google.gwt.core.client.EntryPoint` | Implements the GWT entry-point SPI. |
| `com.google.gwt.core.client.GWT` | `setUncaughtExceptionHandler`, `log`, `getModuleBaseURL`. |
| `com.google.gwt.http.client.{RequestBuilder, Request, RequestCallback, Response, RequestException}` | Async fetch of `locale_<lang>.txt`. |
| `com.google.gwt.event.logical.shared.{ResizeEvent, ResizeHandler}` + `com.google.gwt.user.client.Window` | Canvas resize wiring. |
| `com.lushprojects.circuitjs1.client.util.Locale` | Publishes `localizationMap` statically (`circuitjs1.java:196`). |
| `com.lushprojects.circuitjs1.client.dialog.UncaughtExceptionDialog` | Uncaught-exception UI hook — see `domain-core__dialog-info.md`. |
| `QueryParameters` (same package) | `?lang=` lookup. |
| `OptionsManager` (same package) | Language preference in `localStorage` (`circuitjs1.java:90`). |
| `CirSim` (same package) | Constructed + `init()` at `circuitjs1.java:197-198`; `CirSim.console(...)` used at `circuitjs1.java:176,185` for malformed-locale lines. |

Outgoing arrows from this file (what it kicks off):
- `CirSim.init()` → builds the entire UI, solver, JS bridge, and loads the default circuit.
- `Locale.localizationMap` assignment → every `Locale.LS(...)` call across the codebase now resolves against this map.
- `Window.addResizeHandler` → pipes resize events into `CirSim`.

No other module in the codebase imports `circuitjs1` (searched for class-name usages; only `circuitjs1.mysim` is a theoretically reachable reference and is not used from outside this file). The coupling is one-way: bootstrap → everything.

---

## Issues / observations

1. **Silent locale-fetch failures.** `loadLocale()`'s `onError` callback (`circuitjs1.java:114-116`) and the outer `RequestException` catch (`circuitjs1.java:132-134`) only call `GWT.log(...)` — they never call `loadSimulator(...)`. If the locale HTTP request throws a transport error (not an HTTP status), the application **never starts**: the page stays blank and the only trace is a dev-console log line. Recommended: on `onError` / `RequestException`, fall back to an empty map and call `loadSimulator(...)`.

2. **`QueryParameters` crashes on bare flags.** `QueryParameters.java:34-36` does `pair[0], URL.decode(pair[1])` without checking `pair.length`. A URL like `?foo&lang=fr` raises `ArrayIndexOutOfBoundsException` and, because the uncaught-exception handler is already installed by step 1, the user sees the `UncaughtExceptionDialog` instead of the app. Minor, but user-visible.

3. **`QueryParameters.getBooleanValue` uses `==` for String comparison.** `val == "1"` (`QueryParameters.java:51`) only works by accident when the literal is interned; not invoked by `circuitjs1` itself but a latent bug for any other caller.

4. **`loadSimulator` is `public` without being API.** The only reason it isn't `private` is the inner-class visibility rule — the async callback needs access. Could be tightened to package-private; the `public` is misleading.

5. **`static CirSim mysim` vs `CirSim.sim`.** Two parallel static references to the same instance. Downstream code reads `CirSim.sim`; `mysim` is effectively dead weight after bootstrap. Worth a cleanup.

6. **Locale file format is fragile.** `processLocale` (`circuitjs1.java:168-193`) hand-rolls a parser for `"key"="value"` lines with no escape handling beyond `\uXXXX`. Any stray `"` inside a value silently breaks the line (rejected by the `charAt(q2+1) != '='` guard) and is logged as "ignoring line in string catalog". Translators won't notice until a string goes missing at runtime.

7. **No `oncircuitjsloaded` fires on locale error.** Because locale-fetch error paths short-circuit `loadSimulator`, external automation listening for `window.oncircuitjsloaded` (docs/JS_API.md line 187) will hang forever on transport failure. Tied to issue #1.

---

## Concept boundary

The file represents a single, very thin concept: **app-entrypoint**. Its responsibility is purely orchestration — install the error hook, resolve a locale, construct and initialize `CirSim`, attach a resize handler. It owns no domain state, no circuit logic, no UI beyond the resize wiring, and publishes no external API. Any change outside those five steps belongs in another concept (`simulator-core`, `options-settings`, `dialog-info`, or the locale/util layer). The total logical size is ~60 LOC excluding the `processLocale` / `convertUnicodeEscapes` helpers, which could reasonably be extracted into a `LocaleLoader` util to shrink this concept further.
