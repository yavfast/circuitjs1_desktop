# UI Tabs — Multi-Document Tab Bar  {#C_TAB}

> **Code:** C_TAB
> **Status:** approved
> **Created:** 2026-04-19
> **Updated:** 2026-04-19
> **Author:** OnboardDocGen
>
> **Depends on:** [C_GEO](./geometry.concept.md), [C_RND](./rendering-primitives.concept.md), [C_UTL](./util-locale-log.concept.md)
> **Used by:** `com.lushprojects.circuitjs1.client` (`CirSim`)
> **Spike:** —
> **Specification:** [SP_TAB](./ui-tabs.sp.md)
> **Plan:** [ui-tabs.plan.md](./ui-tabs.plan.md)
>
> Analysis source: [.dev_flow/onboard/analysis/ui-tabs.md](../.dev_flow/onboard/analysis/ui-tabs.md)
>
> `ui/tabs` provides the multi-document tab bar of the simulator shell: a
> horizontal strip of per-document tab widgets plus a popup "list all tabs"
> menu. It is the visual front-end of `DocumentManager` — observing document
> lifecycle / active-change events and forwarding user clicks back as
> manager operations.

## 1. Philosophy  {#C_TAB_01}

### 1.1. Core Principle  {#C_TAB_01_01}

CircuitJS1 supports many open documents simultaneously. The user needs a
compact, always-visible control for switching between them, closing them,
and seeing at a glance whether each is running, errored, or idle. The tab
bar owns that contract; it is *not* a generic UI primitive — it is bound
to `DocumentManager` / `CircuitDocument` by design.

### 1.2. Design Constraints  {#C_TAB_01_02}

- **Observer, not owner.** The tab bar never mutates document state
  directly; it delegates all changes through `DocumentManager`.
- **Single active tab.** At most one tab carries the `activeTab` CSS
  style at any moment. Enforced by `onActiveDocumentChanged`.
- **CSS contract with `war/circuitjs.html`.** All visual state is
  communicated through documented style classes (`.tabBarPanel`,
  `.tabsContainer`, `.tabWidget`, `.activeTab`, `.tabStatus.running`,
  `.tabStatus.error`, `.tabCloseBtn`, `.addTabButton`).
- **GWT `Composite` only.** Tab widgets do not leak `Widget` internals;
  they are composite facades.

## 2. Domain Model  {#C_TAB_02}

### 2.1. Key Entities  {#C_TAB_02_01}

- **`TabBarPanel`** — root composite; owns the horizontal tab strip and
  the "list tabs" dropdown. Implements
  `DocumentManager.DocumentManagerListener` and
  `TabWidget.TabListener`. Maintains `tabMap: Map<CircuitDocument,
  TabWidget>` as the authoritative reverse index.
- **`TabWidget`** — one row in the strip. Holds a reference to its
  `CircuitDocument`, a title label, a status glyph (running/error), and
  a close button. Emits events only via its `TabListener`.
- **`TabWidget.TabListener`** — callback pair
  (`onTabSelected`, `onTabClosed`) sent from widget up to panel.

Relationship: `TabBarPanel 1 ── N TabWidget 1 ── 1 CircuitDocument`.
The panel subscribes to two orthogonal event streams per document:
the manager's lifecycle events, and the document's own
`SimulationStateListener` for running/error status.

### 2.2. Data Flows  {#C_TAB_02_02}

- **Document added:** `DocumentManager` → `onDocumentAdded` → build
  `TabWidget`, attach `SimulationStateListener`, add to container.
- **Document removed:** `DocumentManager` → `onDocumentRemoved` →
  remove `TabWidget` from container and `tabMap`.
- **Active change:** `DocumentManager` → `onActiveDocumentChanged` →
  toggle `activeTab` class on old/new, `scrollIntoView()` on new.
- **User selects tab:** click in `TabWidget` → `TabListener.onTabSelected`
  → `DocumentManager.setActiveDocument`.
- **User closes tab:** click close-button in `TabWidget`
  (event.stopPropagation) → `TabListener.onTabClosed` →
  `DocumentManager.closeDocument`.
- **User opens popup:** click "⌄" → transient `PopupPanel` + `MenuBar`
  populated from `DocumentManager.getDocuments()`; selection calls
  `setActiveDocument` and hides popup.
- **Simulation state change:** document fires listener →
  `TabWidget.setStatus(isRunning, errorMessage)` — error wins over
  running; idle hides the status label.

## 3. Mechanisms  {#C_TAB_03}

### 3.1. Core Algorithm  {#C_TAB_03_01}

On construction the panel registers with the manager, then replays the
existing document set through `onDocumentAdded` and fires a synthetic
`onActiveDocumentChanged(null, active)` so the UI is correct even if
documents opened before the panel was created. From that moment on the
panel is a pure reactive projection of manager state onto DOM.

Status indicator is a three-state function of `(isRunning, errorMessage)`:
`error` (non-empty message → "⚠"), `running` (no error → "●"), or
`idle` (hidden empty label). Error visibility is strictly prioritized.

### 3.2. Edge Cases  {#C_TAB_03_02}

- **Listener attach on removal** — the per-document
  `SimulationStateListener` is anonymous; on `onDocumentRemoved` it is
  not detached. Acceptable only because `DocumentManager.closeDocument`
  is expected to dispose the document; otherwise leaks. Tracked in
  backlog.
- **Popup re-entry** — each popup invocation creates a fresh
  `PopupPanel`; no retention, no stale references.
- **Active doc may be null** — both sides of
  `onActiveDocumentChanged` can be null (first activation, last
  deactivation); code must tolerate either.
- **Trusted HTML in popup** — active-document label uses
  `SafeHtmlUtils.fromTrustedString("<b>…</b>")`. Safe only while
  `getTabTitle` returns plain text; see SP invariant.

## 4. Integration Points  {#C_TAB_04}

### 4.1. Dependencies  {#C_TAB_04_01}

- [C_RND](./rendering-primitives.concept.md) — widget runs alongside the render
  surface; style coordination in `war/circuitjs.html`.
- [C_UTL](./util-locale-log.concept.md) — utility conventions (not directly used
  today, but Locale-class policies documented there).
- [C_GEO](./geometry.concept.md), [C_PLT](./platform.concept.md) — only
  transitively via GWT composite layout / platform bootstrap.
- `CircuitDocument`, `DocumentManager` (Layer 3) — the concrete
  contracts this concept projects.

### 4.2. API Surface  {#C_TAB_04_02}

- Construction: `new TabBarPanel(DocumentManager)` — pure reactive
  wiring, no explicit dispose (singleton for app lifetime).
- Widget-level: `TabWidget.setTitle`, `setStatus(running, error)`,
  `setActive(bool)`, `isActive()`, `getDocument()`.
- Callback contract: `TabWidget.TabListener { onTabSelected,
  onTabClosed }`.
- CSS contract: documented class names consumed by
  `war/circuitjs.html`.

## Changelog

| Date | Change |
|------|--------|
| 2026-04-19 | Initial version |
