# Naming Rules

**Metadata**
- Scope: new Java code under `src/main/java/com/lushprojects/circuitjs1/`.
- Config scanned: no `.editorconfig`, no `checkstyle.xml`, no `.idea/codeStyles/`. Java source/target = 17 (`pom.xml:25-26,57-58`). GWT module `com.lushprojects.circuitjs1.circuitjs1` (`pom.xml:120`). Naming conventions are therefore convention-driven, not enforced by tooling.
- Evidence source: `.dev_flow/onboard/analysis/*.md` (37 files).

---

## Rule: PackageNaming

**Category:** naming
**Severity:** must
**Applies to:** all production Java code

### Description
All production code lives under `com.lushprojects.circuitjs1.client.*`. Sub-packages use lowercase single words (`dialog`, `element`, `element/waveform`, `io`, `io/text`, `io/json`, `util`). The root `client/` package still hosts legacy cruft (managers, models, `Graphics`, `Point`, etc.); new cross-cutting utilities should go into `util/` instead.

### Examples
**Correct:**
```java
// src/main/java/com/lushprojects/circuitjs1/client/element/CircuitElm.java:1
package com.lushprojects.circuitjs1.client.element;
```
```java
// src/main/java/com/lushprojects/circuitjs1/client/util/Locale.java
package com.lushprojects.circuitjs1.client.util;
```

### Rationale
The GWT module descriptor and JSNI bridge (`$wnd.CircuitJS1`) assume a single top-level package tree. `util/` is the designated Layer-0 location; the client root is grandfathered and should not grow.

---

## Rule: CircuitElementClassSuffix

**Category:** naming
**Severity:** must
**Applies to:** classes extending `CircuitElm` (or `ChipElm`, `CompositeElm`, `SwitchElm`, `GraphicElm`)

### Description
Every concrete circuit-element class ends with the literal suffix `Elm`. ~135 existing elements follow the pattern (`ResistorElm`, `DiodeElm`, `CustomCompositeElm`, `CustomLogicElm`, `LEDElm`, `ZenerElm`, `VaractorElm`, etc.).

### Examples
**Correct:**
```java
// domain-core__element-base.md:87-89
public abstract class CircuitElm extends BaseCircuitElm implements Editable
// concrete: ResistorElm, DiodeElm, CustomCompositeElm (dump 410), CustomLogicElm (dump 208)
```
**Incorrect:**
```java
public class Resistor extends CircuitElm { ... }   // missing "Elm" suffix
```

### Rationale
`CircuitElm.getJsonTypeName()` (`CircuitElm.java:1534`) defaults to the class name minus the trailing `"Elm"` (so `ResistorElm` → JSON type `Resistor`). Dropping the suffix would silently collide with JSON type names already used by existing elements.

---

## Rule: DialogClassSuffix

**Category:** naming
**Severity:** must
**Applies to:** classes extending `dialog.Dialog`

### Description
Dialog classes end with `Dialog` (e.g. `EditDialog`, `SliderDialog`, `ScopePropertiesDialog`, `EditCompositeModelDialog`, `ShowLogDialog`, `ExportAsImageDialog`, `SubcircuitDialog`). The only exception in-tree is `AboutBox`, which extends `PopupPanel` directly and predates the convention (`domain-core__dialog-info.md` issue #1).

### Examples
**Correct:**
```java
// domain-core__dialog-base.md:46-49
public class Dialog extends com.google.gwt.user.client.ui.DialogBox { ... }
// concrete: EditDialog, SliderDialog, ScopePropertiesDialog, ...
```

### Rationale
`DialogManager` (`layer3__cross-cutting-managers.md:60`) dispatches by concrete type name via its `show*Dialog` factory methods — predictable naming keeps the factory list scannable.

---

## Rule: ManagerSingletonSuffix

**Category:** naming
**Severity:** must
**Applies to:** app-shell cross-cutting managers wired onto `BaseCirSim`

### Description
Process-level or document-level cross-cutting components owned by `BaseCirSim` / `CircuitDocument` end with `Manager`. Observed: `OptionsManager`, `AdjustableManager`, `DocumentManager`, `MenuManager`, `DialogManager`, `LogManager`, `ActionManager`, `ScopeManager`, `ClipboardManager`, `UndoManager`.

### Examples
**Correct:**
```java
// BaseCirSim.java:12-20 (cited in layer3__cross-cutting-managers.md:18-22)
public final LogManager logManager = new LogManager(this);
public final DialogManager dialogManager = new DialogManager(this);
```

### Rationale
Managers share a lookup pattern (field on `BaseCirSim`/`CircuitDocument`, plus delegate access via `BaseCirSimDelegate.*Manager()`). The suffix makes them immediately discoverable in both `BaseCirSim` and the delegate.

---

## Rule: ModelParameterObjectSuffix

**Category:** naming
**Severity:** must
**Applies to:** shared parameter-only catalogue classes

### Description
Catalogue classes that store shared physical parameters for multiple element instances end with `Model`. Observed: `DiodeModel`, `TransistorModel`, `CustomLogicModel`, `CustomCompositeModel` (`domain-core__shared-models.md:30-129`). These are distinct from element classes (`DiodeElm`) and solver helpers (`Diode`).

### Examples
**Correct:**
```java
// domain-core__shared-models.md:32-33, 54
public class DiodeModel implements Editable, Comparable<DiodeModel>, SimulationContextAware { ... }
// dump token: "34 " (DiodeModel.java:327)
```
The three-way split `DiodeModel` (parameters) / `Diode` (Newton solver) / `DiodeElm` (UI element) is the canonical shape (`domain-core__shared-models.md:135-147`).

### Rationale
Models are bound to elements by *name* and live in static `modelMap`s. The suffix separates them from elements (`*Elm`) and solver primitives (`Diode`, `Inductor`).

---

## Rule: ConstantsUpperSnake

**Category:** naming
**Severity:** must
**Applies to:** `static final` integer bit-flags, side constants, scaling modes, and other enum-like constants

### Description
Use `UPPER_SNAKE_CASE` for `static final` constants. Dominant families:
- `FLAG_*` bit-mask flags: `FLAG_SMALL`, `FLAG_FLIP_X/Y/XY`, `FLAG_CUSTOM_VOLTAGE`, `FLAG_IEC`, `FLAG_LABEL`, `FLAG_ESCAPE`, `FLAG_SCHMITT`, `FLAG_SHOW_LABEL`, `FLAG_BACK_EULER`, `FLAGS_SIMPLE`.
- `SIDE_*`: `SIDE_N`, `SIDE_S`, `SIDE_W`, `SIDE_E` (`domain-core__element-base.md:316`).
- `SCALE_*`: `SCALE_AUTO/1/M/MU` (`BaseCircuitElm`).
- `WF_*`: waveform enum constants (`Waveform`, `domain-core__waveforms.md`).
- Other: `CURRENT_TOO_FAST`, `THICK_LINE_WIDTH`, `LOG_WRITE_DELAY_MS`, `MAX_QUEUE_SIZE`.

### Examples
**Correct:**
```java
// ChipElm.java — cited in domain-core__element-base.md:310-317
public static final int FLAG_SMALL = 1;
public static final int FLAG_FLIP_X = 1 << 10;
public static final int SIDE_N = 0, SIDE_S = 1, SIDE_W = 2, SIDE_E = 3;
```

### Rationale
Consistency with `CircuitElm`/`ChipElm`/`SwitchElm` flag tables. Readers identify flag bits at a glance, and `EditInfo.changeFlag(flags, BIT)` helpers assume this convention.

---

## Rule: MethodsCamelCase

**Category:** naming
**Severity:** must
**Applies to:** all methods

### Description
Methods use `lowerCamelCase`: `stamp()`, `doStep()`, `getPostCount()`, `setEditValue()`, `applyJsonProperties()`, `calculateWireClosure()`, `preStampAndStampCircuit()`.

### Examples
**Correct:**
```java
// CircuitElm.java contract methods — domain-core__element-base.md:130-224
public void stamp() { ... }
public void doStep() { ... }
public int getPostCount() { return 2; }
```

### Rationale
Standard Java convention; consistent across all 135+ element classes and the simulator core.

---

## Rule: GwtEntryPointLowercase

**Category:** naming
**Severity:** prefer
**Applies to:** the `EntryPoint` class only

### Description
The GWT `EntryPoint` class is named `circuitjs1` (all lowercase) — a deliberate exception matching the GWT module short name (`pom.xml:121`: `<moduleShortName>circuitjs1</moduleShortName>`). Do not propagate this convention to other classes.

### Examples
**Correct:**
```java
// layer3__app-entry.md
public class circuitjs1 implements EntryPoint { public void onModuleLoad() { ... } }
```

### Rationale
Legacy artifact of the GWT build — the generated JS filename must match. Keep it; don't rename. Any new class must follow `PascalCase`.

---

## Rule: DumpTypeIdentifier

**Category:** naming
**Severity:** must
**Applies to:** `CircuitElm.getDumpType()` overrides and model dump tokens

### Description
Element dump types are either a single ASCII character (`< 127`, written as the char) or a short numeric string. Observed:
- Single char: `SwitchElm` → `'s'` (115), `ResistorElm` → `'r'`, `CapacitorElm` → `'c'`, etc.
- Numeric: `CustomLogicElm` → `208`, `CustomCompositeElm` → `410`.

Model dump token prefixes follow the same space: `"34 "` (`DiodeModel`), `"32 "` (`TransistorModel`), `"! "` (`CustomLogicModel`), `". "` (`CustomCompositeModel`) — all cited in `domain-core__shared-models.md:167-172`.

### Examples
**Correct:**
```java
// CustomCompositeElm.java:254-256, CustomLogicElm.java:12
@Override public int getDumpType() { return 410; }
// DiodeModel.java:327
public String dump() { return "34 " + ... ; }
```
**Incorrect:**
```java
@Override public int getDumpType() { return 32; }   // collides with TransistorModel "32 " prefix
```

### Rationale
Text-format importers (`TextCircuitImporter.java:242/263/267/275`) dispatch by the first token. New dump types must not collide with existing element chars, existing numeric element types, or the four model token prefixes. There is no central registry today (`domain-core__element-base.md` issue #9, `domain-core__shared-models.md` issue #6) — check both namespaces manually.

---

## Rule: JsonTypeNamePascalCase

**Category:** naming
**Severity:** should
**Applies to:** `getJsonTypeName()` overrides

### Description
JSON type names are `PascalCase` identifiers. The default (`CircuitElm.java:1534`) strips the `Elm` suffix from the class name. Convention additions: compound waveform/mode types use a suffix (`VoltageSourceAC`, `VoltageSourceDC`), chips use `getChipName()` with whitespace/hyphens stripped (`domain-core__element-base.md:351`), subcircuits return the literal `"Subcircuit"` (`CustomCompositeElm`, `domain-core__element-base.md:432`).

### Examples
**Correct:**
```java
// default behaviour — CircuitElm.java:1534
// ResistorElm → "Resistor"
// VoltageElm (AC variant) → "VoltageSourceAC"
@Override public String getJsonTypeName() { return "Subcircuit"; }
```

### Rationale
JSON-format exporters and importers (`io/json/*`) round-trip via the type name; arbitrary casing would break cross-version compatibility.
