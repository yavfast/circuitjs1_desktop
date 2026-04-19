# App Entrypoint — GWT Bootstrap  {#C_APE}

> **Code:** C_APE
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** onboard-doc-gen
>
> **Depends on:** C_APC (simulator-core, pending — `CirSim`), [C_UTL](./util-locale-log.concept.md), C_DIN (dialog-info: UncaughtExceptionDialog), [C_USR](./user-preferences.concept.md)
> **Used by:** —
> **Spike:** —
> **Specification:** [SP_APE](./app-entrypoint.sp.md)
> **Plan:** [app-entrypoint.plan.md](./app-entrypoint.plan.md)
>
> Backing analysis: `.dev_flow/onboard/analysis/layer3__app-entry.md`.
>
> The GWT `EntryPoint` (`circuitjs1.java`). Its sole responsibility is to go from a blank HTML shell to a fully initialised `CirSim`: install the uncaught-exception hook, resolve a locale, load a translation catalog, construct `new CirSim()`, call `init()`, and wire a window-resize handler. All domain logic lives downstream; this concept owns no circuit state, no UI beyond resize, and publishes no external API (the `window.CircuitJS1` global is built inside `CirSim`).

## 1. Philosophy  {#C_APE_01}

### 1.1. Core Principle  {#C_APE_01_01}

Bootstrap is single-purpose orchestration. Every non-orchestration concern belongs in another concept (`simulator-core`, `user-preferences`, `dialog-info`, `util`). The entry file is ~60 logical LOC excluding the locale-parser helpers.

### 1.2. Design Constraints  {#C_APE_01_02}

- **Uncaught exception hook first.** Installed before anything else so the rest of bootstrap is captured.
- **Locale resolution is three-tier** — `?lang=` query → `OptionsManager("language")` → `navigator.languages[0]` (fallback `"en-US"` under Electron).
- **English fast-path.** If resolved language starts with `"en"`, skip the HTTP request entirely.
- **Async continuation.** `onModuleLoad` returns immediately after `loadLocale()`; `CirSim` is constructed inside the `RequestCallback`.
- **No `window.*` publication.** `window.CircuitJS1` and `oncircuitjsloaded` are emitted from `CirSim`, not here.

## 2. Domain Model  {#C_APE_02}

### 2.1. Key Entities  {#C_APE_02_01}

```
circuitjs1 implements GWT EntryPoint
  public static final String  versionString      = "3.1.3js"
  public static final boolean shortRelaySupported = false
  static CirSim mysim                              -- bootstrap-local; rarely read

  onModuleLoad()
    -> install UncaughtExceptionHandler
    -> loadLocale()   -- async; eventually calls loadSimulator(map)

  loadLocale()
    parse ?lang= via QueryParameters
    resolve language: query -> storage -> navigator
    normalise tag (zh-tw preserved; others strip region suffix)
    IF starts with "en": loadSimulator(empty map)
    ELSE: RequestBuilder.sendRequest("locale_<lang>.txt") async
          onResponseReceived 200 -> processLocale(text) -> loadSimulator
          onError / RequestException -> GWT.log only (simulator never starts)

  loadSimulator(map)
    Locale.localizationMap = map
    mysim = new CirSim()
    mysim.init()                                   -- builds everything
    Window.addResizeHandler(e -> mysim.setCanvasSize; setSlidersDialogHeight)
    mysim.renderer.render()                        -- first paint

  processLocale(text)     -- hand-rolled "key"="value" parser with \uXXXX
  convertUnicodeEscapes   -- helper
  native language()       -- reads navigator.languages
```

### 2.2. Data Flows  {#C_APE_02_02}

```
Browser load
  GWT runtime -> circuitjs1.onModuleLoad
    install UncaughtExceptionHandler
      on Throwable e:
        try: UncaughtExceptionDialog.show(e, stackTrace)
        catch(Throwable ignored): -- UI failure absorbed
        GWT.log(e); e.printStackTrace

    loadLocale
      new QueryParameters (reads window.location.search)
      lang = qp.getValue("lang")
           ?: OptionsManager.getOptionFromStorage("language", null)
           ?: language()   -- navigator.languages[0] or navigator.language
      normalise lang
      IF "en*": loadSimulator({})
      ELSE RequestBuilder.sendRequest(moduleBaseURL + "locale_<lang>.txt"):
        SC_OK -> map = processLocale(text); loadSimulator(map)
        else  -> map = {}; loadSimulator(map)
        onError / RequestException -> GWT.log  (!! simulator never starts)

    loadSimulator(map)
      Locale.localizationMap = map
      mysim = new CirSim(); mysim.init()
      Window.addResizeHandler(...)
      mysim.renderer.render()
      -- CirSim.init finishes -> window.CircuitJS1 published; oncircuitjsloaded fired
```

## 3. Mechanisms  {#C_APE_03}

### 3.1. Core Algorithm  {#C_APE_03_01}

**Language normalisation.** `zh-tw` (and `zh-cht`, case-insensitive) collapses to `"zh-tw"`; every other tag has its region suffix stripped via `replaceFirst("-.*", "")` (`en-US` → `en`, `de-DE` → `de`).

**Locale file parse.** `processLocale` hand-rolls a parser for `"key"="value"` lines. Each line is pre-processed by `convertUnicodeEscapes` (decodes `\uXXXX`). Any line that does not match the shape (`"key"="value"`) is logged via `CirSim.console` and skipped — translators won't discover missing strings until runtime.

**First render.** After `init()` returns, `renderer.render()` paints the initial frame before the user interacts.

### 3.2. Edge Cases  {#C_APE_03_02}

- **Silent locale-fetch failure.** `onError` and the outer `RequestException` catch only `GWT.log`; they never call `loadSimulator`, so a transport error leaves the page blank forever and `oncircuitjsloaded` never fires.
- **`QueryParameters` crashes on bare flags** (`?foo&lang=fr`) — surfaces as `UncaughtExceptionDialog` because the handler is already installed.
- **Stray `"` inside a value** breaks `processLocale`'s shape guard and silently skips that translation.
- **`static CirSim mysim` vs `CirSim.sim`** — two parallel static references; downstream reads `CirSim.sim`, so `mysim` is effectively dead weight after bootstrap.

## 4. Integration Points  {#C_APE_04}

### 4.1. Dependencies  {#C_APE_04_01}

- **C_APC (simulator-core)** — `new CirSim()`, `mysim.init()`, `mysim.renderer.render()`, `mysim.setCanvasSize`, `mysim.setSlidersDialogHeight`, `CirSim.console`.
- **[C_UTL](./util-locale-log.concept.md)** — `Locale.localizationMap` statically assigned; every `Locale.LS(...)` resolves against it.
- **C_DIN (dialog-info)** — `UncaughtExceptionDialog.show(e, stackTrace)`.
- **[C_USR](./user-preferences.concept.md)** — `OptionsManager.getOptionFromStorage("language", null)`; `QueryParameters` for `?lang=`.
- **GWT runtime** — `GWT.log`, `GWT.setUncaughtExceptionHandler`, `GWT.getModuleBaseURL`, `RequestBuilder`, `Window.addResizeHandler`.

### 4.2. API Surface  {#C_APE_04_02}

- `onModuleLoad()` — GWT-mandated entry point.
- `public static final String versionString` — displayed in About dialog and exported into `.circuitjs.txt` headers.
- `public static final boolean shortRelaySupported` — compile-time flag.
- `public loadSimulator(HashMap<String,String>)` — public only because the anonymous `RequestCallback` inner class needs reach; not a stable API.
- No outputs on `window.*` directly.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
