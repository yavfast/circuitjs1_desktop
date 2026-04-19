# UI Tabs — Specification  {#SP_TAB}

> **Code:** SP_TAB
> **Status:** approved
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
>
> **Concept:** [C_TAB](./ui-tabs.concept.md)
> **Depends on specs:** [SP_RND](./rendering-primitives.sp.md), [SP_UTL](./util-locale-log.sp.md)
> **Used by specs:** — (consumed by application-level `CirSim`, not a spec yet)
> **Plan:** [ui-tabs.plan.md](./ui-tabs.plan.md)
>
> Analysis source: [.dev_flow/onboard/analysis/ui-tabs.md](../.dev_flow/onboard/analysis/ui-tabs.md)
>
> Defines the data shapes, contracts, validation rules, state transitions,
> and verification criteria for the `ui/tabs` package (`TabBarPanel`,
> `TabWidget`).

## 01. Data Structures  {#SP_TAB_01}

> Implements: [C_TAB_02](./ui-tabs.concept.md#C_TAB_02)

### 01_01. TabBarPanel  {#SP_TAB_01_01}

Composite widget projecting `DocumentManager` state onto a tab strip.

Fields:
| Field | Type | Required | Default | Constraints | Description |
|-------|------|----------|---------|-------------|-------------|
| documentManager | DocumentManager | yes | — | non-null | Source of truth |
| mainPanel | FlowPanel | yes | new | styled `.tabBarPanel` | Root |
| tabsContainer | FlowPanel | yes | new | styled `.tabsContainer` | Tab strip |
| listTabsButton | Label | yes | "⌄" | title="List Tabs" | Dropdown trigger |
| tabMap | Map<CircuitDocument,TabWidget> | yes | empty HashMap | — | Reverse index |

Invariants:
- `tabMap.keySet()` equals the observed documents (added minus removed).
- `tabsContainer` children equal exactly the `TabWidget` values of
  `tabMap` in insertion order.
- At most one `TabWidget` carries style `activeTab` at any time.

### 01_02. TabWidget  {#SP_TAB_01_02}

One row in the strip.

Fields:
| Field | Type | Required | Default | Constraints | Description |
|-------|------|----------|---------|-------------|-------------|
| document | CircuitDocument | yes | — | final, non-null | The bound doc |
| panel | FlowPanel | yes | new | styled `.tabWidget` | Root |
| titleLabel | Label | yes | "" | styled `.tabTitle` | Displays title |
| statusLabel | Label | yes | hidden | `.tabStatus[.running\|.error]` | Run/error indicator |
| closeButton | Label | yes | "✖" | styled `.tabCloseBtn` | Close control |
| isActive | boolean | yes | false | mirrors `activeTab` style | Active cache |

Invariants:
- `document` is final and non-null.
- `isActive` mirrors the presence of the `activeTab` class on `panel`.
- `statusLabel.isVisible() == false` iff not running and no error.

### 01_03. TabWidget.TabListener  {#SP_TAB_01_03}

Inner callback interface: `onTabSelected(CircuitDocument)`,
`onTabClosed(CircuitDocument)`.

## 02. Contracts  {#SP_TAB_02}

### 02_01. TabBarPanel(documentManager)  {#SP_TAB_02_01}

Purpose: construct panel and subscribe to manager events.

Input:
| Parameter | Type | Required | Constraints |
|-----------|------|----------|-------------|
| documentManager | DocumentManager | yes | non-null |

Processing logic:
    FUNCTION init(dm):
        build mainPanel / tabsContainer / listTabsButton
        dm.addListener(self)
        FOR each doc IN dm.getDocuments(): onDocumentAdded(doc)
        IF dm.getActiveDocument() != null:
            onActiveDocumentChanged(null, dm.getActiveDocument())

### 02_02. onDocumentAdded(doc)  {#SP_TAB_02_02}

Creates a `TabWidget`, attaches a `SimulationStateListener` that
forwards to `widget.setStatus`, inserts into `tabsContainer` and
`tabMap`.

### 02_03. onDocumentRemoved(doc)  {#SP_TAB_02_03}

Removes `tabMap[doc]` from `tabsContainer` and `tabMap`. Does not
currently detach the simulation state listener (see backlog in
[PL_TAB](./ui-tabs.plan.md)).

### 02_04. onActiveDocumentChanged(old, new)  {#SP_TAB_02_04}

Toggles `activeTab` style from the old tab (if mapped) to the new tab
(if mapped), then calls `scrollIntoView()` on the new one. Either side
may be null.

### 02_05. TabWidget.setStatus(isRunning, errorMessage)  {#SP_TAB_02_05}

Three-way projection:

    FUNCTION setStatus(isRunning, err):
        hasError = err != null AND err.length > 0
        IF hasError: show "⚠", class "error", tooltip=err
        ELSE IF isRunning: show "●", class "running", tooltip=""
        ELSE: clear and hide label

Errors:
| Code | Condition | Guidance |
|------|-----------|----------|
| — | none | No failure modes; idempotent |

## 03. Validation Rules  {#SP_TAB_03}

### 03_01. Input Validation  {#SP_TAB_03_01}

- `documentManager` must be non-null.
- `document` in `TabWidget` must be non-null.
- `getTabTitle(doc)` is assumed to return plain text (required for
  `SafeHtmlUtils.fromTrustedString` in the popup to be XSS-safe).

## 04. State Transitions  {#SP_TAB_04}

### 04_01. Tab Lifecycle  {#SP_TAB_04_01}

    [none] --onDocumentAdded--> [present]
    [present] --onDocumentRemoved--> [none]

### 04_02. Active State  {#SP_TAB_04_02}

    [inactive] --onActiveDocumentChanged(_, self)--> [active]
    [active] --onActiveDocumentChanged(self, _)--> [inactive]

Exactly one `TabWidget` may be `active` at any time.

### 04_03. Status Indicator  {#SP_TAB_04_03}

    [idle] --setStatus(true, "")--> [running]
    [running] --setStatus(_, err)--> [error]
    [error] --setStatus(false, "")--> [idle]

Error state wins over running state regardless of `isRunning`.

## 05. Verification Criteria  {#SP_TAB_05}

### 05_01. Functional Expectations  {#SP_TAB_05_01}

| Contract | Scenario | Input | Expected outcome |
|----------|----------|-------|------------------|
| onDocumentAdded | Happy path | new doc | widget in `tabMap` + strip |
| onDocumentRemoved | Happy path | known doc | widget removed |
| onActiveDocumentChanged | Switch | (A, B) | only B has `activeTab` |
| setStatus | Error wins | (true, "err") | shows ⚠, class `error` |
| Close click | User closes tab | click ✖ | `closeDocument` called; select not fired |

### 05_02. Invariant Checks  {#SP_TAB_05_02}

| Invariant | Verification method |
|-----------|-------------------|
| ≤1 active tab | After every active-change, count `.activeTab` == 0 or 1 |
| tabMap ↔ container | Assert `tabMap.size() == tabsContainer.getWidgetCount()` |
| document non-null | Constructor precondition |

### 05_03. Integration Scenarios  {#SP_TAB_05_03}

| Scenario | Preconditions | Steps | Expected result |
|----------|--------------|-------|-----------------|
| Panel built late | 2 docs already open | new TabBarPanel(dm) | 2 tabs rendered, active highlighted |
| Title rename | active doc | `onDocumentTitleChanged` | title refreshed via `getTabTitle` |
| Popup select | ≥2 docs | click ⌄, pick non-active | `setActiveDocument` called, popup hides |

### 05_04. Edge Cases and Boundaries  {#SP_TAB_05_04}

| Case | Input | Expected behavior |
|------|-------|-------------------|
| Null old active | (null, B) | B highlighted, no strip side-effect on old |
| Null new active | (A, null) | A de-highlighted, no scroll |
| Remove active | onDocumentRemoved(active) | widget gone; active re-sync on manager side |
| Close-button bubbling | click ✖ | stopPropagation prevents `onTabSelected` |

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
