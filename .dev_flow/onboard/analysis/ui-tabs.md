# Module Analysis: ui-tabs

> **Path:** src/main/java/com/lushprojects/circuitjs1/client/ui/tabs/
> **Layer:** 1
> **Analyzed:** 2026-04-18
> **Files:** 2 source files, 0 test files

## Purpose

`ui/tabs/` is the only formally carved-out `ui/` sub-package in the codebase
(73-file `client/` root aside). It provides a **multi-document tab bar** for
the simulator shell: a horizontal strip of tab widgets, one per open
`CircuitDocument`, plus a popup "list all tabs" menu. The panel acts as the
visual front-end of `DocumentManager` — it observes document
add/remove/rename/active-change events and simulation state transitions, and
in turn forwards user clicks (select / close) back to `DocumentManager`.

The two classes were factored out together because they form one cohesive
reusable widget pair: `TabBarPanel` owns the list, `TabWidget` owns a single
tab row. Nothing else currently lives in `ui/` — this package is the seed of
a future UI-primitive layer.

## Key Entities

### TabBarPanel

- **Type:** class (extends `com.google.gwt.user.client.ui.Composite`)
- **File:** `src/main/java/com/lushprojects/circuitjs1/client/ui/tabs/TabBarPanel.java:19`
- **Implements:**
  - `DocumentManager.DocumentManagerListener` (receives document lifecycle events)
  - `TabWidget.TabListener` (receives per-tab user interactions)
- **Fields:**

| Field | Type | Role |
|---|---|---|
| `documentManager` | `DocumentManager` | Source of document state; target for user actions |
| `mainPanel` | `FlowPanel` | Root widget (styled `.tabBarPanel`) |
| `tabsContainer` | `FlowPanel` | Horizontal strip holding `TabWidget` children (styled `.tabsContainer`) |
| `listTabsButton` | `Label` ("⌄") | Dropdown trigger (styled `.addTabButton`, title "List Tabs") |
| `tabMap` | `Map<CircuitDocument, TabWidget>` (`HashMap`) | Reverse lookup to locate a `TabWidget` for a given document |

- **Invariants:**
  - `tabMap.keySet()` equals the set of documents the panel has observed
    via `onDocumentAdded` minus those removed via `onDocumentRemoved`.
  - `tabsContainer` children are exactly the `TabWidget` values in `tabMap`
    (insertion-order via `FlowPanel`).
  - At most one `TabWidget` carries style `activeTab` at any time (enforced
    by toggling old/new in `onActiveDocumentChanged`).

### TabWidget

- **Type:** class (extends `com.google.gwt.user.client.ui.Composite`)
- **File:** `src/main/java/com/lushprojects/circuitjs1/client/ui/tabs/TabWidget.java:10`
- **Fields:**

| Field | Type | Role |
|---|---|---|
| `document` | `CircuitDocument` | The document this tab represents (final) |
| `panel` | `FlowPanel` | Root widget (styled `.tabWidget`) |
| `titleLabel` | `Label` | Displays title + tooltip (styled `.tabTitle`) |
| `statusLabel` | `Label` | Run/error indicator (styled `.tabStatus`, `.running`, `.error`) |
| `closeButton` | `Label` ("✖") | Close control (styled `.tabCloseBtn`) |
| `isActive` | `boolean` | Active-state cache (kept in sync with `activeTab` style) |

- **Invariants:**
  - `document` is final and non-null once constructed.
  - `isActive` mirrors presence of the `activeTab` style on `panel`.
  - `statusLabel.setVisible(false)` iff neither running nor error (i.e.
    stopped, no error) — see `setStatus` branches at
    `TabWidget.java:68-83`.

### TabWidget.TabListener (inner interface)

- **File:** `TabWidget.java:19-23`
- **Members:**
  - `void onTabSelected(CircuitDocument document)`
  - `void onTabClosed(CircuitDocument document)`
- **Sole implementer:** `TabBarPanel` (`TabBarPanel.java:149-156`).

## Public Contracts

### TabBarPanel

- `TabBarPanel(DocumentManager documentManager)` — constructor; registers
  itself as `DocumentManager.DocumentManagerListener`, seeds existing
  documents via `onDocumentAdded`, and fires an initial
  `onActiveDocumentChanged(null, activeDocument)` if an active doc exists
  (`TabBarPanel.java:27-60`).
- Implements `DocumentManagerListener`:
  - `onDocumentAdded(CircuitDocument)` — creates a `TabWidget`, sets title
    from `documentManager.getTabTitle(doc)`, attaches a fresh anonymous
    `SimulationStateListener` to the document for running/error updates,
    appends to `tabsContainer` (`:90-103`).
  - `onDocumentRemoved(CircuitDocument)` — removes the mapped `TabWidget`
    from `tabsContainer` (`:106-120`). **Does not detach the
    `SimulationStateListener`** — see Issues.
  - `onActiveDocumentChanged(old, new)` — toggles `activeTab` style on
    the appropriate tabs and calls `scrollIntoView()` on the new one
    (`:123-138`).
  - `onDocumentTitleChanged(CircuitDocument)` — refreshes title via
    `getTabTitle()` (`:141-146`).
- Implements `TabWidget.TabListener`:
  - `onTabSelected(CircuitDocument)` → `documentManager.setActiveDocument`
    (`:149-151`).
  - `onTabClosed(CircuitDocument)` → `documentManager.closeDocument`
    (`:154-156`).
- Private: `showTabsListPopup(int x, int y)` — builds a transient
  `PopupPanel`+`MenuBar` dropdown listing every document; active one is
  wrapped in `<b>…</b>` via `SafeHtmlUtils.fromTrustedString`; selecting
  an item calls `setActiveDocument` and hides the popup
  (`:62-87`).

### TabWidget

- `TabWidget(CircuitDocument document, TabListener listener)` — builds the
  DOM (status label, title label, close button); wires close button and
  panel click to the listener. The close click calls
  `event.stopPropagation()` so it does not bubble to the panel-level
  selector (`TabWidget.java:25-58`).
- `void setTitle(String title)` — sets text and tooltip
  (`:60-63`).
- `void setStatus(boolean isRunning, String errorMessage)` — three-way
  state (error → "⚠", running → "●", idle → hidden). Error tooltip = the
  error message (`:65-84`).
- `void setActive(boolean active)` — toggles the `activeTab` style
  (`:86-93`).
- `boolean isActive()` — getter (`:95-97`).
- `CircuitDocument getDocument()` — getter (`:99-101`).

## Validation Rules

- `setStatus(isRunning, errorMessage)`:
  - "Has error" = `errorMessage != null && !errorMessage.isEmpty()`
    (`TabWidget.java:66`). An error always wins over running state.
  - Running without error → `.tabStatus running`.
  - Neither → label cleared and hidden.
- `showTabsListPopup`: active-document title is injected as trusted HTML
  (`<b>…</b>`) — only safe because titles are sourced from
  `documentManager.getTabTitle` (`TabBarPanel.java:67-73`). No escaping of
  the raw title occurs besides the bolding wrapper.

## State Transitions

- **Tab lifecycle:** `onDocumentAdded` → tab in `tabMap` and
  `tabsContainer`; `onDocumentRemoved` → entry removed from both.
- **Active tab:** Exactly one tab at a time may carry the `activeTab`
  style. `onActiveDocumentChanged(old, new)` strips the style from `old`
  (if mapped) before adding it to `new`, then scrolls the new tab into
  view. Either side can be `null` (first activation; final
  deactivation).
- **Status indicator (per tab):** driven by
  `CircuitDocument.SimulationStateListener.onSimulationStateChanged`.
  States: `error` (visible ⚠), `running` (visible ●), `idle` (hidden
  empty label).
- **Popup lifecycle:** each call to `listTabsButton.onClick` constructs a
  new `PopupPanel` (auto-hide = true) and `MenuBar`; selecting an item
  calls `popup.hide()` — popups are transient, not retained.

## Integration Points

- **Depends on (application code):**
  - `com.lushprojects.circuitjs1.client.CircuitDocument`
    - `CircuitDocument.SimulationStateListener` (inner interface)
    - `addStateListener`, `isRunning`, `getErrorMessage`
  - `com.lushprojects.circuitjs1.client.DocumentManager`
    - `DocumentManager.DocumentManagerListener` (inner interface)
    - `addListener`, `getDocuments`, `getActiveDocument`,
      `setActiveDocument`, `closeDocument`, `getTabTitle`
- **External (GWT) deps:**
  - `com.google.gwt.user.client.ui.{Composite, FlowPanel, Label, MenuBar,
    MenuItem, PopupPanel}`
  - `com.google.gwt.user.client.Command`
  - `com.google.gwt.event.dom.client.{ClickEvent, ClickHandler}`
  - `com.google.gwt.safehtml.shared.SafeHtmlUtils` (trusted-string)
- **Used by (from grep):**
  - `client/CirSim.java:64` — `import …ui.tabs.TabBarPanel`
  - `client/CirSim.java:76` — field `TabBarPanel tabBarPanel`
  - `client/CirSim.java:253` — `tabBarPanel = new TabBarPanel(documentManager);`
  - `client/CirSim.java:265` — `layoutPanel.addNorth(tabBarPanel, TAB_BAR_HEIGHT);`
    (docks the tab bar below the toolbar — `TAB_BAR_HEIGHT = 28` at `CirSim.java:72`)
  - No other direct application usage. `TabWidget` is referenced only from
    within the same package (`TabBarPanel`) — it is effectively an
    implementation detail of `TabBarPanel` today, though its
    constructor/listener is public enough to host other clients.
- **CSS hook-up:** Style class selectors live in
  `war/circuitjs.html` (lines 285-377): `.tabBarPanel`,
  `.tabsContainer`, `.tabWidget`, `.tabWidget:hover`, `.activeTab`,
  `.tabTitle`, `.tabStatus`, `.tabStatus.running`, `.tabStatus.error`,
  `.tabCloseBtn`, `.tabCloseBtn:hover`, `.addTabButton`,
  `.addTabButton:hover`. (Reference HTML file, not authoritative styling
  — confirms runtime contract.)

## Existing Documentation

- `.dev_flow/onboard/project_structure.md` (lines 77, 154-157): lists
  `client/ui/tabs/` as the only populated `ui/` sub-package with two
  files.
- `.dev_flow/onboard/dependency_graph.md` (line 35): single import edge
  `<root> → ui/tabs` (Layer 3 → Layer 1). Layer classification at
  lines 86-108.
- No dedicated doc in `docs/` covers the tab widget.

## Issues / Questions

1. **Listener leak on `onDocumentRemoved`** — `TabBarPanel.java:97-103`
   attaches an anonymous `SimulationStateListener` to each document in
   `onDocumentAdded` but never detaches it in `onDocumentRemoved`
   (`:106-120`). The inline comment acknowledges the issue ("Ideally we
   should remove them … since we used anonymous class, we can't easily.
   … we might leak"). Safe today only if `DocumentManager.closeDocument`
   destroys the document; otherwise a leak. Candidate fix: store the
   listener alongside the `TabWidget` in `tabMap` (e.g. struct/record or
   parallel map) so it can be removed.
2. **`TabWidget.isActive` / `setActive` is state-duplicating** — the
   `isActive` field is kept in sync with the `activeTab` CSS class but
   never read inside the class. Only external readers (none at present)
   would use it; could be derived from the style instead.
3. **`showTabsListPopup` uses `fromTrustedString`** — safe only because
   `DocumentManager.getTabTitle` returns plain text. If that contract
   ever weakens (user-controlled titles containing HTML), this is an
   XSS vector. Worth either documenting the invariant on `getTabTitle`
   or switching to `fromString` with proper escaping + a manual `<b>`
   wrapper via a `SafeHtmlBuilder`.
4. **Glyph-only button semantics** — `listTabsButton` uses a `Label`
   "⌄" and `closeButton` uses "✖". No ARIA role/label, no keyboard
   handler. Accessibility gap (may be out of scope for GWT canvas app).
5. **No `addTabButton` action in panel** — CSS class name is
   `addTabButton`, but the widget is a *list* trigger, not an add
   trigger (title = "List Tabs"). The CSS class is a stale name.
6. **Constructor side-effect on `DocumentManager`** — the `TabBarPanel`
   constructor calls `documentManager.addListener(this)` but provides
   no `dispose()` / `removeListener`. Acceptable for a singleton
   `TabBarPanel` attached for app lifetime; would leak if re-created.

## Suggested Concept Boundaries

Single concept — **`tab-widget`**: a reusable GWT tab bar bound to
`DocumentManager`. It is tightly coupled to `DocumentManager` and
`CircuitDocument` today (not a generic tab widget), so the concept
boundary should capture:

- The `DocumentManager ↔ TabBarPanel` listener contract.
- The `CircuitDocument.SimulationStateListener ↔ TabWidget.setStatus`
  mapping.
- The `TabWidget.TabListener` callback pair (`onTabSelected`,
  `onTabClosed`).
- The CSS class-name contract with `war/circuitjs.html`.

Splitting `TabWidget` into a generic UI primitive (decoupled from
`CircuitDocument`) is a potential future refactor, but not warranted
by the current two-file surface.
