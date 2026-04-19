# Structural Rules

**Metadata**
- Scope: class structure, extension points, file layout.
- Config scanned: no checkstyle, no editorconfig. Structure is convention-only.
- Evidence source: `.dev_flow/onboard/analysis/domain-core__element-base.md`, `domain-core__shared-models.md`, `domain-core__dialog-base.md`, `layer3__cross-cutting-managers.md`, `io-framework.md`, `util.md`.

---

## Rule: ElementSubclassOverridePoints

**Category:** structure
**Severity:** must
**Applies to:** new `CircuitElm` subclasses

### Description
Every concrete element class must override the subset of `CircuitElm`'s contract relevant to its behavior. The minimum mandatory set for a simulation-participating element:

| Method | Default site | Must override when |
|---|---|---|
| `getDumpType()` | `CircuitElm.java:320` (throws) | always |
| `getDumpClass()` | `CircuitElm.java:330` | always if the class renames |
| `stamp()` | `CircuitElm.java:578` | element contributes MNA stamps |
| `doStep()` | `CircuitElm.java:582` | element is dynamic or nonlinear |
| `nonLinear()` | `CircuitElm.java:889` | element needs Newton iteration |
| `getPostCount()` | `CircuitElm.java:893` (=2) | not a 2-terminal element |
| `getVoltageSourceCount()` | `CircuitElm.java:857` (=0) | has a voltage-source contribution |
| `setVoltageSource(n,v)` | `CircuitElm.java:876` | multi-source element |
| `setCurrent(vn,c)` | `CircuitElm.java:563` | multi-source element |
| `draw(Graphics)` | `CircuitElm.java:558` | element is visible |
| `getInfo(String[])` | `CircuitElm.java:1128` | element has anything to report |

Ordering invariants (`domain-core__element-base.md:822-835`): `setNode` before `stamp`; `stamp` once per topology; `doStep` many times per timestep; `setCurrent` after matrix solve; `stepFinished` last.

### Examples
**Correct:**
```java
// Canonical shape — see every *Elm in element/
public class MyElm extends CircuitElm {
    @Override public int getDumpType() { return 'X'; }
    @Override public int getPostCount() { return 3; }
    @Override public int getVoltageSourceCount() { return 1; }
    @Override public boolean nonLinear() { return true; }
    @Override public void stamp() { simulator.stampVoltageSource(...); }
    @Override public void doStep() { ... }
    @Override public void draw(Graphics g) { ... }
}
```
**Incorrect:**
```java
public class MyElm extends CircuitElm { /* forgets getDumpType() */ }
// Compiles, but dump() throws IllegalStateException at save time.
```

### Rationale
`getDumpType()` is **intentionally non-abstract** (`CircuitElm.java:320-327`) to work around a GWT compiler bug; the compiler will not catch a missing override. The contract list above is the substitute for language-level enforcement.

---

## Rule: ElementGeometryThroughElmGeometry

**Category:** structure
**Severity:** must
**Applies to:** any code reading or writing `CircuitElm` endpoints / leads / bounding box

### Description
`x1/y1/x2/y2` are **no longer fields** on `CircuitElm`. All geometry reads and writes flow through `geom()` / `ElmGeometry` (`domain-core__element-base.md:239-241`, `ElmGeometry.java` file-level javadoc lines 6-10).

### Examples
**Correct:**
```java
// domain-core__element-base.md:523-529
geom().setEndpoints(x1, y1, x2, y2);
geom().translate(dx, dy);
geom().dragTo(xx, yy);
geom().movePoint(n, dx, dy);
```
**Incorrect:**
```java
this.x1 += dx;    // x1 is no longer a field
this.x2 = nx2;
```

### Rationale
`ElmGeometry` owns derived state (`dx, dy, dn, dpx1, dpy1`, bounding box) and fires `owner.adjustDerivedGeometry(this)` on each mutation. Bypassing it leaves derived state stale and breaks rendering / hit-testing.

---

## Rule: DialogExtendDialogBase

**Category:** structure
**Severity:** must
**Applies to:** new modal/popup dialogs

### Description
New dialogs must extend `com.lushprojects.circuitjs1.client.dialog.Dialog` (not GWT `DialogBox` directly). That gives them position persistence, viewport clamping, collapse toggle, and Enter-to-apply (`domain-core__dialog-base.md:46-117`).

Two overrides are typically required:
- `getOptionPrefix()` — return a stable prefix (e.g. `"edit.r"`) so position + collapsed state persist (`Dialog.java:108`).
- `apply()` — commit the dialog's edits (`Dialog.java:104`).

The outlier `AboutBox` (extends `PopupPanel` directly) is a legacy exception — do not copy it.

### Examples
**Correct:**
```java
// All edit/export/import dialogs — see DialogManager.java imports (:3-24)
public class MyDialog extends Dialog {
    @Override protected String getOptionPrefix() { return "my.dialog"; }
    @Override void apply() { ... }
}
```

### Rationale
Without `getOptionPrefix()`, persistence silently no-ops (`Dialog.java:118`). Without extending `Dialog`, the dialog is invisible to `DialogManager.dialogIsShowing()` / `closeDialog()` (`layer3__cross-cutting-managers.md:111`).

---

## Rule: EditableEditInfoContract

**Category:** structure
**Severity:** must
**Applies to:** objects that expose parameters to the edit dialog (elements, models, option panels)

### Description
Any editable object implements `dialog.Editable` and exposes its parameters via the paired methods:
```java
EditInfo getEditInfo(int n);            // return null to terminate the row list
void     setEditValue(int n, EditInfo ei);
```
The `EditInfo` instance passed to `setEditValue` is the **same object** that was returned from `getEditInfo(n)` — dialogs mutate it in place (`domain-core__dialog-base.md:272-275`). Row indices must be stable between the two calls.

Known implementers (5): `CircuitElm` (→ ~135 elements), `EditOptions`, `DiodeModel`, `TransistorModel`, `CustomLogicModel` (`domain-core__dialog-base.md:157-167`).

### Examples
**Correct:**
```java
// Canonical shape — many *Elm classes
@Override public EditInfo getEditInfo(int n) {
    if (n == 0) return new EditInfo("Resistance (Ω)", resistance, 0, 0);
    if (n == 1) return EditInfo.createCheckbox("Show current", mustShowCurrent());
    return null;
}
@Override public void setEditValue(int n, EditInfo ei) {
    if (n == 0) resistance = ei.value;
    if (n == 1) flags = ei.changeFlag(flags, FLAG_SHOW_CURRENT);
}
```

### Rationale
`Editable` + `EditInfo` is the single contract between ~140 implementers and the dialog subsystem (771 `EditInfo` references in 125 files, `domain-core__dialog-base.md:410`). It is also the reason element → dialog is a single SCC (see `architecture.md`).

---

## Rule: SharedModelCatalogPattern

**Category:** structure
**Severity:** must
**Applies to:** catalogue classes that store shared parameters across element instances

### Description
Shared parameter objects (`*Model`) follow a fixed pattern (`domain-core__shared-models.md:16-29`):
1. `private static HashMap<String, T> modelMap` — process-wide catalog keyed by model name.
2. `public static T getModelWithName(String)` + `public static T getModelWithNameOrCopy(String, T)` entry points; the latter clones when branching off.
3. `dump()` emits one space-separated line prefixed with a **unique token** (`"34 "` diode, `"32 "` transistor, `"! "` custom logic, `". "` composite); `undumpModel(StringTokenizer)` parses it back.
4. `createModelMap()` / `initModelMap()` seeds built-in entries once, each flagged `builtIn = true`.
5. A `dumped` flag is used by `TextCircuitExporter` to avoid emitting the same entry twice; reset via `clearDumpedFlags()` before each export.
6. Implement `SimulationContextAware` so edits can call `circuitDocument.simulator.updateModels()` (`domain-core__shared-models.md:187-194`).

### Examples
**Correct:**
```java
// DiodeModel.java pattern — domain-core__shared-models.md:32-59
public class DiodeModel implements Editable, Comparable<DiodeModel>, SimulationContextAware {
    private static HashMap<String, DiodeModel> modelMap;
    public static DiodeModel getModelWithName(String name) { ... }
    public static DiodeModel getModelWithNameOrCopy(String name, DiodeModel oldModel) { ... }
    public String dump() { return "34 " + ... ; }
}
```

### Rationale
The pattern is tightly coupled to `TextCircuitImporter`/`Exporter` dispatch and to element dialog wiring. Deviating will either miss the catalog-dedup step or break import round-trip.

---

## Rule: SingletonManagerPattern

**Category:** structure
**Severity:** must
**Applies to:** app-shell cross-cutting components

### Description
Cross-cutting shell components (`*Manager`) follow one of two lookup patterns:

- **Process-scope:** owned by `BaseCirSim` as a `public final` field, initialised in the constructor (`BaseCirSim.java:12,15`). Extends `BaseCirSimDelegate` (`layer3__simulator-core.md:47-56`) for back-pointers. Examples: `LogManager`, `DialogManager`, `MenuManager`, `DocumentManager`, `ClipboardManager`, `ActionManager`.
- **Document-scope:** owned by `CircuitDocument` and retrieved via `circuitDocument.getDialogManager()` / `getUndoManager()` / `getScopeManager()` (`layer3__cross-cutting-managers.md:129`). Examples: `UndoManager`, `ScopeManager`, `AdjustableManager`.

Element-layer code with a `CircuitDocument` handle should prefer the document-scoped accessor over reaching through `cirSim.*`.

### Examples
**Correct:**
```java
// BaseCirSim.java:12-20 — cited layer3__cross-cutting-managers.md:18-22
public final LogManager    logManager    = new LogManager(this);
public final DialogManager dialogManager = new DialogManager(this);
```
```java
// element-side access via document
circuitDocument.getDialogManager().showEditElementDialog(this);   // DiodeElm.java:263, CustomCompositeElm.java:245
```

### Rationale
`BaseCirSimDelegate` keeps managers unit-testable (they don't import GWT widgets directly). The dual lookup paths (`cirSim.dialogManager` vs `circuitDocument.getDialogManager()`) are functionally equivalent but differ in intent: shell code uses the former, domain code uses the latter.

---

## Rule: NewUtilitiesLiveInUtil

**Category:** structure
**Severity:** must
**Applies to:** new stateless helper classes

### Description
New utility classes (math helpers, string parsers, formatters, GWT storage wrappers) go into `client/util/` (`util.md`). The `client/` root is legacy and should not grow. `BaseCircuitElm`'s static formatting helpers are grandfathered there because `CircuitElm` extends `BaseCircuitElm`; do not use that as precedent (`domain-core__element-base.md:258-285`, issue #5).

### Examples
**Correct:**
```java
// src/main/java/com/lushprojects/circuitjs1/client/util/Locale.java
package com.lushprojects.circuitjs1.client.util;
```
**Incorrect:**
```java
// New helper placed at client root next to CirSim.java, Graphics.java, Point.java
package com.lushprojects.circuitjs1.client;   // do not grow this surface
```

### Rationale
Strict layering puts `util/` at Layer 0 (no inbound layer-cycle risk). The client root already owns a 1955-LOC `CirSim` plus `Point`/`Graphics`/`Color`/`Polygon`/`Rectangle`; adding more utilities there deepens the SCC (see `architecture.md`).

---

## Rule: JsniClusteredInAppShell

**Category:** structure
**Severity:** should
**Applies to:** new `native` (JSNI) methods

### Description
JSNI (`native ... /*-{ ... }-*/`) crosses the GWT-Java / browser boundary. Keep it in a small, documented set of files: `circuitjs1` (entry point), `CirSim` (JS bridge), `ClipboardManager`, `LogManager`, `LoadFile`, `ImportFromDropbox`, `ExportAs*Dialog`, `AudioInputElm`, `AudioOutputElm`. Do not sprinkle JSNI into random element classes.

### Examples
**Correct:**
```java
// LogManager.java:210-236 — JSNI clustered in a single file
private native void createLogDirectory() /*-{ ... NW.js fs access ... }-*/;
```

### Rationale
JSNI is invisible to the Java type checker and to IDE refactorings. Concentrating it in a handful of files makes it auditable (`architecture.md` JSNI boundary rule). See also `layer3__cross-cutting-managers.md` LogManager JSNI cluster.
