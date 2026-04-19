# Clipboard — Specification  {#SP_CLP}

> **Code:** SP_CLP
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_CLP](./clipboard.concept.md)
> **Depends on specs:** SP_IOF, SP_DOC, SP_PLT
> **Used by specs:** [SP_EDI](./canvas-editor.sp.md), [SP_MEN](./menus-actions.sp.md)
> **Plan:** [clipboard.plan.md](./clipboard.plan.md)

## 01. Data Structures  {#SP_CLP_01}

### 01_01. ClipboardManager state  {#SP_CLP_01_01}

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| internalClipboard | String | "" | Sync fallback buffer. |
| hasSystemClipboardSupport | boolean | feature-detected | True if `navigator.clipboard.writeText` exists. |

### 01_02. ClipboardCallback  {#SP_CLP_01_02}

Interface with two methods: `onSuccess(String)`, `onError(String)`. No implementations are part of this spec surface — consumers supply inline callbacks.

## 02. Contracts  {#SP_CLP_02}

### 02_01. doCopy / doCut  {#SP_CLP_02_01}

    FUNCTION doCopy():
        text = editor.copyOfSelectedElms()
        setClipboard(text)

    FUNCTION doCut():
        doCopy()
        editor.doDelete(true)

### 02_02. setClipboard  {#SP_CLP_02_02}

    FUNCTION setClipboard(text):
        internalClipboard = text
        IF hasSystemClipboardSupport:
            writeToSystemClipboard(text)   -- JSNI navigator.clipboard.writeText
        ELSE:
            tryLegacyClipboardWrite(text)  -- textarea + execCommand('copy')

### 02_03. doPasteFromSystem  {#SP_CLP_02_03}

    FUNCTION doPasteFromSystem():
        IF internalClipboard non-empty:
            editor.doPaste(internalClipboard)
            RETURN
        readFromSystemClipboard(new ClipboardCallback {
            onSuccess(text):
                IF isCircuitData(text): editor.doPaste(text)
            onError(msg):
                console.log(msg)
        })

### 02_04. isCircuitData  {#SP_CLP_02_04}

Returns true if the text contains any of: `$` header marker, ` r `, ` c `, ` l `, ` w ` token prefixes (space-delimited). Heuristic only.

## 03. Validation Rules  {#SP_CLP_03}

- `setClipboard` must always write `internalClipboard` first (sync guarantee).
- `readFromSystemClipboard` must never throw synchronously; all errors go through `onError`.
- Feature flag `hasSystemClipboardSupport` is immutable post-construction.

## 04. State Transitions  {#SP_CLP_04}

| From | To | Trigger | Side effect |
|------|----|---------|-------------|
| (internal="") | (internal=text) | doCopy | OS clipboard also written best-effort |
| (internal=text) | (internal=text) | doPasteFromSystem (internal wins) | editor.doPaste(text) |
| (internal="") | (internal="") | doPasteFromSystem (internal empty) | async read; editor.doPaste on success |

## 05. Verification Criteria  {#SP_CLP_05}

### 05_01. Functional  {#SP_CLP_05_01}

| Contract | Scenario | Input | Expected |
|----------|----------|-------|----------|
| doCopy | selection present | selected elms | internal buffer holds dump; async write attempted |
| doCut | selection present | selected elms | same as doCopy; then selection deleted |
| doPasteFromSystem | internal empty, valid text on OS clipboard | circuit text | editor.doPaste called with text |
| doPasteFromSystem | non-circuit text | "hello world" | isCircuitData=false; no paste |

### 05_02. Invariants  {#SP_CLP_05_02}

| Invariant | Verification |
|-----------|--------------|
| internal takes precedence | doPasteFromSystem uses internal when non-empty |
| fallback path on no-support | tryLegacyClipboardWrite executed iff hasSystemClipboardSupport=false |

### 05_03. Edge Cases  {#SP_CLP_05_03}

| Case | Input | Expected |
|------|-------|----------|
| False-positive sniff | "the word `r ` occurs" | passes sniff; importer typically rejects |
| Browser denies readText | no user gesture | onError fires; no paste |
| Image copy | actionManager.doImageToClipboard | bypasses this subsystem; uses CirSim.clipboardWriteImage |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure. |
