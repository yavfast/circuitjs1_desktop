# App Entrypoint — Specification  {#SP_APE}

> **Code:** SP_APE
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_APE](./app-entrypoint.concept.md)
> **Depends on specs:** SP_APC, SP_UTL, SP_DIN, [SP_USR](./user-preferences.sp.md)
> **Used by specs:** —
> **Plan:** [app-entrypoint.plan.md](./app-entrypoint.plan.md)

## 01. Data Structures  {#SP_APE_01}

### 01_01. circuitjs1 class  {#SP_APE_01_01}

| Field | Type | Visibility | Value | Description |
|-------|------|------------|-------|-------------|
| versionString | String | public static final | `"3.1.3js"` | version displayed in About + baked into exports |
| shortRelaySupported | boolean | public static final | `false` | whether hosting server provides `shortrelay.php` |
| mysim | CirSim | package static | — | bootstrap-local simulator ref (dead weight after init) |

No instance state.

### 01_02. JSNI bridges  {#SP_APE_01_02}

| Method | Location | Direction | Purpose |
|--------|----------|-----------|---------|
| `language()` | circuitjs1.java:72–83 | JS → Java | `navigator.languages[0]` / `.language` / `.userLanguage`, fallback `"en-US"` for Electron |
| `QueryParameters.getQueryString()` | QueryParameters.java:54–57 | JS → Java | reads `$wnd.location.search` |

## 02. Contracts  {#SP_APE_02}

### 02_01. onModuleLoad  {#SP_APE_02_01}

    FUNCTION onModuleLoad():
        GWT.setUncaughtExceptionHandler(handler)
        loadLocale()         -- returns immediately; async continuation

### 02_02. Uncaught exception handler  {#SP_APE_02_02}

    FUNCTION handler(Throwable e):
        stackTrace = join stack frames as single String
        try: UncaughtExceptionDialog.show(e, stackTrace)
        catch(Throwable ignored): -- absorb UI failure
        GWT.log(e)
        e.printStackTrace()

### 02_03. loadLocale  {#SP_APE_02_03}

    FUNCTION loadLocale():
        qp   = new QueryParameters()
        lang = qp.getValue("lang")
            ?: OptionsManager.getOptionFromStorage("language", null)
            ?: language()
        lang = normalise(lang)
        IF lang starts with "en":
            loadSimulator(new HashMap())
            RETURN
        URL = GWT.getModuleBaseURL() + "locale_" + lang + ".txt"
        try RequestBuilder(GET, URL).sendRequest(null, new RequestCallback {
            onResponseReceived(req, res):
                IF res.statusCode == SC_OK:
                    loadSimulator(processLocale(res.text))
                ELSE:
                    loadSimulator(new HashMap())
            onError(req, ex):
                GWT.log(ex)       -- simulator never starts (known bug)
        })
        catch RequestException: GWT.log(...)  -- simulator never starts

    FUNCTION normalise(lang):
        low = lang.toLowerCase()
        IF low == "zh-tw" OR low == "zh-cht": RETURN "zh-tw"
        RETURN lang.replaceFirst("-.*", "")

### 02_04. loadSimulator  {#SP_APE_02_04}

    FUNCTION loadSimulator(map):
        Locale.localizationMap = map
        mysim = new CirSim()
        mysim.init()
        Window.addResizeHandler(e -> {
            mysim.setCanvasSize(0, 0)
            mysim.setSlidersDialogHeight()
        })
        mysim.renderer.render()

### 02_05. processLocale  {#SP_APE_02_05}

    FUNCTION processLocale(text):
        map = {}
        FOR each line in text.split("\n"):
            line = convertUnicodeEscapes(line)
            IF line matches `"<key>"="<value>"`:
                map[key] = value
            ELSE:
                CirSim.console("ignoring line in string catalog: " + line)
        RETURN map

## 03. Validation Rules  {#SP_APE_03}

- `UncaughtExceptionHandler` must be installed before any other bootstrap step.
- `loadSimulator` must be called exactly once per GWT module lifetime (bug: error path calls it zero times).
- `Locale.localizationMap` is assigned exactly once.

## 04. State Transitions  {#SP_APE_04}

| From | To | Trigger | Side effect |
|------|----|---------|-------------|
| blank page | handler-installed | `onModuleLoad` start | `GWT.setUncaughtExceptionHandler` |
| handler-installed | awaiting-locale | `loadLocale` non-English | async RequestBuilder in flight |
| awaiting-locale | locale-ready | `onResponseReceived` OK | `processLocale` populates map |
| locale-ready | running | `loadSimulator` | CirSim built, init'd, first render |
| awaiting-locale | stuck | onError / RequestException | GWT.log only; page stays blank (bug) |

## 05. Verification Criteria  {#SP_APE_05}

### 05_01. Functional  {#SP_APE_05_01}

| Contract | Scenario | Input | Expected |
|----------|----------|-------|----------|
| onModuleLoad | `?lang=fr`, locale file present | — | French strings loaded; CirSim running |
| onModuleLoad | `?lang=en-US` | — | fast-path; empty map; CirSim running |
| onModuleLoad | `?lang=xx`, locale 404 | — | empty map; CirSim running |
| onModuleLoad | locale transport error | — | CirSim never starts (known bug) |
| normalise | "de-DE" | — | "de" |
| normalise | "zh-TW" | — | "zh-tw" |

### 05_02. Invariants  {#SP_APE_05_02}

| Invariant | Verification |
|-----------|--------------|
| UncaughtExceptionHandler first | install is first statement of onModuleLoad |
| Locale map set before CirSim ctor | loadSimulator assigns map then `new CirSim()` |

### 05_03. Edge Cases  {#SP_APE_05_03}

| Case | Input | Expected |
|------|-------|----------|
| `?foo&lang=fr` | — | QueryParameters AIOOB; surfaced via UncaughtExceptionDialog |
| Electron, navigator.languages empty | — | language() returns "en-US"; fast-path |
| locale line with stray `"` | — | shape guard fails; skipped with console log |
| bare exception inside CirSim.init | — | UncaughtExceptionDialog shown |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
