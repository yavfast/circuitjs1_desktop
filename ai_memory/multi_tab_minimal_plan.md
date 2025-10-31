# CircuitJS1 Multi-Tab Support (Minimal Scope)

## Scope and Guiding Principles
- Introduce the ability to work with multiple circuit documents simultaneously, one per tab.
- Deliver only the essential UX: create new tab, switch between tabs, close tab (with save prompts), load/save per tab.
- Keep clipboard behavior simple: reuse the existing global clipboard (system + internal) and apply paste actions to the active tab only.
- Do not implement drag-and-drop between tabs, background simulation for non-active tabs, or automated regression tests in this iteration.

## User Workflow (Minimal Feature Set)
1. Application starts with a single default tab containing the current circuit or an empty document.
2. User can create a new blank circuit via `File → New Tab` (or Ctrl+T) that opens a new tab and becomes active.
3. User can open an existing file into a new tab via `File → Open in New Tab` or by using the existing file-open dialog while choosing whether to replace or open alongside.
4. Tab bar displays document titles (unsaved documents show `*` suffix). Selecting a tab switches the active circuit editor and simulator state.
5. Closing a tab prompts the user to save changes if the circuit is unsaved; closing the last tab leaves a single empty document to avoid a blank workspace.
6. Save/Save As actions apply to the active tab only, updating its title and unsaved state.

## Architecture Changes
### 1. Document Abstraction
- Create `CircuitDocument` to encapsulate state currently managed globally by `CirSim`: `CircuitSimulator`, `CircuitEditor`, `UndoManager`, `CircuitInfo`, and any per-document dialogs or metadata.
- Move document-specific flags (`unsavedChanges`, file path/name, recovery data) inside `CircuitDocument`.
- Provide convenience methods: `doLoad(String dump)`, `dumpCircuit()`, `markUnsaved()`, `applyClipboard(String data)`.

### 2. Document Manager
- Introduce `DocumentManager` responsible for:
  - Maintaining a `List<CircuitDocument>`.
  - Tracking the active document index.
  - Creating, duplicating, and closing documents with validation hooks.
  - Broadcasting document change events to UI components (menu manager, toolbar, scopes, power bar).
- Expose APIs such as `getActiveDocument()`, `setActiveDocument(int index)`, `createDocument(boolean fromTemplate)`, `closeDocument(int index)`.

### 3. Refactor CirSim
- Strip out direct document state from `CirSim`; retain responsibility for window-level services (menus, dialogs, clipboard manager, renderer shell).
- Replace direct references (`cirSim.simulator`, `cirSim.circuitEditor`, etc.) with getters that delegate to `DocumentManager.getActiveDocument()`.
- Update lifecycle methods (`enablePaste`, `needAnalyze`, `allowSave`, etc.) to act on the active document or forward calls.

### 4. Undo/Redo and Recovery
- Ensure each `CircuitDocument` owns its `UndoManager` and recovery storage paths.
- Adjust menu handlers so undo/redo operate on `DocumentManager.getActiveDocument().getUndoManager()`.
- For auto-recovery: persist per-document recovery data keyed by tab ID; during startup, reconstruct tabs from recovered sessions if multiple dumps are detected.

## UI Implementation (GWT/NW.js)
### 1. Tab Bar Widget
- Implement a lightweight tab widget (e.g., `TabBar` or custom FlowPanel) placed above the main canvas.
- Each tab item contains: title text, close button, unsaved indicator.
- Event handlers:
  - Click on tab → `DocumentManager.setActiveDocument(index)`.
  - Middle-click / close icon → `DocumentManager.closeDocument(index)` with save confirmation.
  - `File → New Tab` button → `DocumentManager.createDocument(false)`.

### 2. Menu and Toolbar Wiring
- Extend `ActionManager` to route actions (cut/copy/paste, rotate, analyze, etc.) through the active document.
- Update `MenuManager` to reflect per-tab enablement states (e.g., undo available only if active document has history).
- Ensure status labels (power bar, scope info) refresh when the active tab changes.

### 3. Dialog Interactions
- Modify file dialogs to support two modes:
  - `Open (replace current tab)` – default behavior.
  - `Open in New Tab` – optional checkbox or secondary button.
- `Save`/`Save As` operations read/write the active document’s metadata and update the tab title.
- When closing a tab or the application, iterate over documents to prompt saves for unsaved changes.

## Simulation and Rendering
- Keep simulation running only for the active tab in this iteration (pause inactive documents automatically when switching).
- Ensure the renderer canvas points to the active document’s circuit state; on tab change, rebind event listeners and repaint.
- Preserve selection and viewport for each document so returning to a tab restores cursor position and scale.

## Persistence and Settings
- Update recovery and settings storage:
  - Maintain a list of open documents in the persistent store (order, active index, file paths or serialized dumps).
  - On startup, reconstruct tabs or fall back to single blank document if recovery data is inconsistent.
- Reuse existing preferences (grid size, theme) globally; do not attempt per-tab settings in this phase.

## Manual Validation Checklist
- Launch app → verify initial single tab.
- Create several tabs, load different circuits, confirm independent undo/redo stacks.
- Switch between tabs and ensure toolbar/menu states update correctly.
- Modify a circuit in multiple tabs, confirm unsaved indicator and save prompts on close/application exit.
- Paste operations affect only the active tab.
- Recovery after crash: reopen app and confirm tabs are restored or gracefully fall back to single blank document.

## Follow-Up (Out of Scope for MVP)
- Drag-and-drop between tabs.
- Background simulation for inactive tabs.
- Automated integration tests for multi-tab workflows.
- Per-tab clipboard buffers or cross-tab linking.
