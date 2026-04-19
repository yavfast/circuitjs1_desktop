# Platform Bridge — Specification  {#SP_PLT}

> **Code:** SP_PLT
> **Status:** active
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_PLT](./platform-bridge.concept.md)
> **Depends on specs:** — (Layer 0)
> **Used by specs:** ActionManager, MenuManager, CircuitRenderer, CircuitEditor, UndoManager, CircuitLoader, JsonCircuitImporter specs (populated at Layer 3)
> **Plan:** [platform-bridge.plan.md](./platform-bridge.plan.md)
>
> URL opening + browser/NW.js probing (`PlatformUtils`) and CSS-on-Widget
> helpers (`GWTUtils`).
>
> Backing analysis: [.dev_flow/onboard/analysis/root-utils.md](../.dev_flow/onboard/analysis/root-utils.md)

## 01. Data Structures  {#SP_PLT_01}

> Implements: [C_PLT_02](./platform-bridge.concept.md#C_PLT_02)

### 01_01. PlatformUtils  {#SP_PLT_01_01}

Stateless static utility. No fields.

### 01_02. GWTUtils  {#SP_PLT_01_02}

Stateless static utility. No fields.

## 02. Contracts  {#SP_PLT_02}

### 02_01. PlatformUtils.openURL(String url)  {#SP_PLT_02_01}

Purpose: Open `url` externally.
Input:
| Parameter | Type | Required | Constraints |
|-----------|------|----------|-------------|
| url | String | yes | non-null, non-empty |

Output: `boolean` — true if a handler was invoked.
Errors: `null`/empty → false.

Logic:
    IF url == null OR url.isEmpty(): return false
    try openURLWithJavaScript(url)  # nw.Shell.openExternal or window.open
    IF exception: try openURLWithSystemCommand(url)  # currently stub
    log outcome via GWT.log; return success

### 02_02. PlatformUtils.getPlatformInfo()  {#SP_PLT_02_02}

Output: JSON string describing NW.js presence, `nw.Shell` availability, and
navigator.platform.

### 02_03. PlatformUtils.isURLOpeningSupported()  {#SP_PLT_02_03}

Output: `boolean` — true if NW.js shell or `window.open` is reachable.

### 02_04. GWTUtils.setStyle(Widget w, String prop, String value)  {#SP_PLT_02_04}

Purpose: Apply a single CSS property on a widget's element.
Logic: `w.getElement().getStyle().setProperty(prop, value)`.

### 02_05. GWTUtils.setStyles(Widget w, String... pairs)  {#SP_PLT_02_05}

Purpose: Apply multiple CSS properties in one call.
Errors: `IllegalArgumentException` if `pairs.length` is odd.
Logic: iterate `pairs` two at a time, delegate to `setStyle`.

## 03. Validation Rules  {#SP_PLT_03}

- `openURL`: null/empty URL → false, no side effects.
- `setStyles`: varargs length must be even.
- JSNI calls are guarded against missing globals (`nw`, `window.open`).

## 04. State Transitions  {#SP_PLT_04}

N/A — stateless utilities.

## 05. Verification Criteria  {#SP_PLT_05}

### 05_01. Functional Expectations  {#SP_PLT_05_01}

| Contract | Scenario | Input | Expected outcome |
|----------|----------|-------|------------------|
| openURL | NW.js available | `"https://example.com"` | `nw.Shell.openExternal` called; returns true |
| openURL | browser only | same | `window.open` called; returns true |
| openURL | null | `null` | false, no side effects |
| setStyles | even pairs | `"color","red","font-size","12px"` | both properties set |
| setStyles | odd pairs | `"color"` alone | IllegalArgumentException |

### 05_02. Invariant Checks  {#SP_PLT_05_02}

| Invariant | Verification method |
|-----------|--------------------|
| `setStyles` pair length even | argument check in each call |

### 05_03. Integration Scenarios  {#SP_PLT_05_03}

| Scenario | Preconditions | Steps | Expected result |
|----------|--------------|-------|-----------------|
| Help menu URL | `ActionManager` hooked | user clicks Help → `openURL(helpUrl)` | external browser/tab opens |
| Flex layout setup | editor init | `GWTUtils.setStyles(panel, "display","flex", "flex-direction","column")` | DOM updated |

### 05_04. Edge Cases and Boundaries  {#SP_PLT_05_04}

| Case | Input | Expected behavior |
|------|-------|-------------------|
| `openURLWithSystemCommand` fallback | NW.js absent and `window.open` throws | logs; returns false (stub path) |
| popup blocker | non-user-gesture context | `window.open` returns null; openURL returns false |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initialized from existing codebase via onboard procedure |
