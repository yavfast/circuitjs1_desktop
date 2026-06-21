# Architectural Rules

**Metadata**
- Scope: layer discipline, module boundaries, factory/registry points.
- Config scanned: no static enforcement; layering is inferred from imports (see `.dev_flow/onboard/layers.md`, `dependency_graph.md`).
- Evidence source: `.dev_flow/onboard/layers.md`, `.dev_flow/onboard/analysis/{element-base, dialog-base, simulator-core, io-framework, util, root-utils}.md`.

---

## Rule: LayerDiscipline

**Category:** architecture
**Severity:** must (for new code)
**Applies to:** new dependencies between packages

### Description
Layering (from `layers.md`):

| Layer | Packages | Role |
|---|---|---|
| L0 | `client/util/`, plus `client/` root geometry/rendering primitives (`Point`, `Rectangle`, `Polygon`, `Graphics`, `Color`, `StringTokenizer`, `Checkbox`, `Choice`) | Stateless utilities and DTOs. |
| L1 | `client/` root widgets, menu items, `ui-tabs/` | UI primitives and tab/menu classes. |
| L2 (SCC-A) | `client/element/`, `client/dialog/`, `client/element/waveform/`, shared `*Model` classes at client root, `client/io/`+`io/text/`+`io/json/` | Domain core: elements, their edit dialogs, and their persistence formats. |
| L3 | `client/CirSim`, `client/BaseCirSim`, `client/CircuitDocument`, managers (`*Manager`), `circuitjs1` entry point | App shell. |

New code must not import **downstream** layers. `element/` may depend on L0/L1 and on the rest of the L2 SCC, but not on L3 managers directly (it reaches them via `CircuitDocument` — see `structure.md`).

### Examples
**Correct:**
```java
// element/ReadsLayeredImports
import com.lushprojects.circuitjs1.client.util.Locale;   // L0
import com.lushprojects.circuitjs1.client.Graphics;      // L0 primitive
import com.lushprojects.circuitjs1.client.dialog.EditInfo; // L2 peer
```
**Incorrect:**
```java
// util/ class importing element/
import com.lushprojects.circuitjs1.client.element.BaseCircuitElm;   // util→element inversion
```

### Rationale
Maintaining monotonic layering keeps `util/` reusable and prevents new layer cycles from being added to SCC-A.

---

## Rule: KnownLayerInversionsAreFrozen

**Category:** architecture
**Severity:** must
**Applies to:** new cross-layer dependencies

### Description
Existing layer inversions are documented and frozen — do not add more. Known inversions:

- `util/PerfMonitor` → `element/BaseCircuitElm` (for `formatNumber`) — `.dev_flow/onboard/issues.md` #1, `domain-core__element-base.md:283-285`.
- `element ↔ dialog` bidirectional coupling (104 + 16 edges) — `issues.md` #2, `domain-core__element-base.md` issue #2. Elements must reach dialog UI only via the `EditInfo`/`Editable` contract, never by instantiating concrete `*Dialog` types.
- `element/waveform` ↔ `element/` cycle — `issues.md` #3.

### Examples
**Incorrect (new inversion):**
```java
// new util/StringFormat.java
import com.lushprojects.circuitjs1.client.element.ChipElm;   // do not add new util→element edges
```

### Rationale
Each inversion narrows the refactoring window; the existing three are tolerated because untangling would be invasive. A fourth would entrench SCC-A further.

---

## Rule: ElementDialogsViaEditInfo

**Category:** architecture
**Severity:** must
**Applies to:** element classes needing parameter UI

### Description
Elements must expose their editable parameters only through `Editable.getEditInfo(n)` / `setEditValue(n, ei)`. They may call `circuitDocument.getDialogManager().showEditElementDialog(this)` to open a generic edit dialog, but they must not `new` a concrete dialog class (e.g. `new EditSliderDialog(...)`), except for intentionally specialised flows already in the codebase (`CustomCompositeElm` opens `EditCompositeModelDialog` via the manager; `DiodeElm`/`TransistorElm` open model-editor dialogs via the manager, `layer3__cross-cutting-managers.md:125`).

### Examples
**Correct:**
```java
// element/ -> CircuitDocument -> DialogManager
circuitDocument.getDialogManager().showEditElementDialog(this);
```
**Incorrect:**
```java
import com.lushprojects.circuitjs1.client.dialog.EditDialog;
new EditDialog(this, cirSim).show();   // bypasses DialogManager tracking
```

### Rationale
`DialogManager.activeDialog` is the app's single-slot "currently-open dialog" router; bypassing it means `dialogIsShowing()` returns false and key handling will race with the dialog (`layer3__cross-cutting-managers.md:107-118`, issue #11).

---

## Rule: IoFormatSelfRegistration

**Category:** architecture
**Severity:** must
**Applies to:** new circuit file-format implementations

### Description
Concrete circuit file formats register themselves with `CircuitFormatRegistry` via a static initialiser block (see `io-framework.md`). The registry is the single dispatch point for `io/text/*` and `io/json/*`; new formats plug in without the registry importing them directly.

### Examples
**Correct:**
```java
// io/text/TextCircuitFormat.java (pattern)
static {
    CircuitFormatRegistry.register(new TextCircuitFormat());
}
```

### Rationale
Static registration keeps `CircuitFormatRegistry` free of upstream imports (it does not need to know about each format's concrete type); removes an otherwise-inevitable L2 cycle.

---

## Rule: ElementCreationThroughFactory

**Category:** architecture
**Severity:** must
**Applies to:** all code that constructs element instances from persisted data

### Description
Element instances must be created through:
- `CircuitElmCreator` — for the legacy text format, dispatches on `getDumpType()` (`domain-core__element-base.md:704-706`).
- `CircuitElementFactory` — for the JSON format, dispatches on `getJsonTypeName()`.

Direct `new ResistorElm(...)` outside these factories, outside test fixtures, or outside explicit "placement" code paths in the editor is disallowed. `CompositeElm.loadComposite()` already uses `CircuitElmCreator` to rebuild children (`domain-core__element-base.md:405-407`).

### Examples
**Correct:**
```java
// CompositeElm.java pattern (cited element-base:405-407)
CircuitElm child = CircuitElmCreator.constructElement(type, doc, x1, y1, x2, y2, flags, tokenizer);
```
**Incorrect:**
```java
// ad-hoc construction in an import or tool
CircuitElm e = new DiodeElm(doc, x1, y1, x2, y2, flags, st);   // bypasses factory registration
```

### Rationale
Any element the factory does not know about cannot be round-tripped through save/load, cannot participate in undo, and breaks `CompositeElm`'s child rebuild (`domain-core__element-base.md:405-407`).

---

## Rule: DocumentVsSessionScope

**Category:** architecture
**Severity:** must
**Applies to:** state that persists across user actions

### Description
Separate document-scoped state from session-scoped state:

- **Document-scoped** (`CircuitDocument`, one per open circuit): `elmList`, `scopes`, `simulator`, `editor`, `renderer`, `undoManager`, `scopeManager`, `adjustableManager`, `logBuffer`, `dcAnalysisFlag`, element `elementId`s. Everything that should be forgotten when the user closes/re-opens a tab.
- **Session-scoped** (`BaseCirSim`/`CirSim`, one per JVM/GWT module): `logManager`, `dialogManager`, `menuManager`, `documentManager`, `clipboardManager`, `actionManager`, `displaySettings`, `loadFileInput`, global catalogs (`DiodeModel.modelMap`, etc.), JSNI bridge. Everything that survives a document switch.

### Examples
**Correct:**
```java
// BaseCirSim.java:12-20 (session-scoped managers)
public final LogManager logManager = new LogManager(this);
// CircuitDocument (document-scoped — cited layer3__cross-cutting-managers.md:129, layer3__simulator-core.md:463)
public CircuitSimulator simulator; public CircuitEditor editor;
```

### Rationale
`BaseCirSim.bindDocument()` swaps documents in and out (`layer3__simulator-core.md:67`). State stored in the wrong scope either leaks across documents (catalog-style state put on a doc) or is lost when the user switches tabs (doc-level state put on the session).

---

## Rule: SharedModelOrPerElementParameters

**Category:** architecture
**Severity:** should
**Applies to:** new element parameters

### Description
Parameters that are:
- **shared across multiple elements** and **re-used as a named library part** → put them in a `*Model` class following the shared-model catalog pattern (`structure.md`'s `SharedModelCatalogPattern`). Examples: diode physical parameters, transistor Gummel-Poon coefficients, custom logic truth tables, custom composite subcircuits.
- **instance-specific**, not library-like (e.g. a single capacitor's value, a source's frequency) → keep them as fields on the element. Do not create a model class for a 2-parameter bag.

### Examples
**Correct:**
```java
// DiodeModel carries the shared parameter set; DiodeElm holds only the reference + name
// domain-core__shared-models.md:135-147
public DiodeModel model;
public String modelName;
```

### Rationale
Model classes introduce global registries, serialization tokens, editable UI, and `SimulationContextAware` wiring; that weight is only worth carrying when the parameter set is genuinely reusable and serializable across circuit files.

---

## Rule: JsniBoundary

**Category:** architecture
**Severity:** must
**Applies to:** browser-integration code

### Description
All JavaScript bridge code crosses the Java/JS boundary through **discrete `native` methods**. Keep them clustered in a small set of files: `circuitjs1` (entry point, uncaught-exception install), `CirSim` (`$wnd.CircuitJS1.*` bridge, `layer3__simulator-core.md:469-475`), `ClipboardManager`, `LoadFile`, `ImportFromDropbox`, the `ExportAs*Dialog` family, `LogManager` (NW.js `fs.appendFile`), `AudioInputElm`, `AudioOutputElm`. New JSNI should land in one of these files or a new dedicated adapter class — not interleaved with element physics.

### Examples
**Correct:**
```java
// LogManager.java:139, 210-236 — clustered JSNI
private native void createLogDirectory() /*-{ ... }-*/;
private native void writeLogEntriesAsync(...) /*-{ ... }-*/;
```
**Incorrect:**
```java
// element/MyFancyElm.java — JSNI sprinkled into a domain class
private native void saveToLocalStorage() /*-{ ... }-*/;
```

### Rationale
JSNI is invisible to the Java type checker and GWT's SOYC; auditing for correctness and NW.js vs browser fallback is only tractable if the call sites are concentrated. See `layer3__cross-cutting-managers.md` LogManager JSNI notes (issues #7–9).

---

## Rule: JsonTypeNameResolvesToFactoryKey

**Category:** architecture
**Severity:** should
**Applies to:** every `CircuitElm` subclass that overrides `getJsonTypeName()`, and `CircuitElementFactory.init()`

### Description
The JSON exporter writes `elm.getJsonTypeName()` as the element `"type"` (`JsonCircuitExporter.java:237`); the importer resolves that string through `CircuitElementFactory`'s `JSON_TYPE_TO_CONSTRUCTOR` map (`createFromJson`). Therefore **every** name an element can emit from `getJsonTypeName()` — including every branch of a computed/ternary body (e.g. `pnp == 1 ? "NMOS" : "PMOS"`) and every concrete subclass that inherits the method — MUST be a registered factory key. Otherwise the lookup returns null and the element is **silently dropped on JSON import** (element-count loss, no exception).

When you add an element, or add/rename a `getJsonTypeName()` override, add the matching `register("<emittedName>", <Ctor>::new)` in `CircuitElementFactory.init()`; keep any prior emitted name as an import alias for backward compatibility. For NPN/PNP-style families, discriminate in `getJsonTypeName()` to the per-variant keys (see `TransistorElm` / `DarlingtonElm`).

### Examples
**Correct:**
```java
public String getJsonTypeName() { return pnp == 1 ? "DarlingtonNPN" : "DarlingtonPNP"; }
register("DarlingtonNPN", NDarlingtonElm::new);
register("DarlingtonPNP", PDarlingtonElm::new);
```
**Incorrect:**
```java
public String getJsonTypeName() { return "Darlington"; }   // emitted name unregistered → dropped on import
```

### Verification
A static check that extracts all string literals from each `getJsonTypeName()` body and diffs them against the `register("...")` keys must report no unregistered emitted name. This drift silently dropped ~27 element types (BL-DROP, fixed 2026-06-21).

### Rationale
The export name and the import key are two ends of one contract maintained in two files; they drift apart silently. The failure is invisible until a user loads a saved JSON circuit and finds elements missing. Relates to RULE_ARCH_004 (format self-registration), RULE_ARCH_005 (creation through factory), RULE_NAMING_010 (JSON type name).
